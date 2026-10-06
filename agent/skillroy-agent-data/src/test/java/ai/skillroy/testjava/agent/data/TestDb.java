/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.QueryResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A private in-memory HSQLDB database with a small fixed data set, plus DataSourceProvider implementations over it.
 *
 * <pre>
 * person(code VARCHAR PK, name VARCHAR, active BOOLEAN, credit DECIMAL(10,2), born DATE)   3 rows
 * item(id INT PK, code VARCHAR, deposit DECIMAL(10,2))                                      250 rows, id 1..250
 * kinds(...)                                                                                one row of every type
 * </pre>
 */
final class TestDb implements AutoCloseable {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    static final int ITEMS = 250;
    static final int BLOB_BYTES = 2000;
    static final int CLOB_CHARS = 12000;

    final String url;
    private final Connection keeper;

    private TestDb(String url) throws SQLException {
        this.url = url;
        this.keeper = DriverManager.getConnection(url, "SA", "");
    }

    static TestDb create() throws SQLException {
        TestDb db = new TestDb("jdbc:hsqldb:mem:skillroy-data-" + COUNTER.incrementAndGet() + "-" + System.nanoTime());
        db.exec("CREATE TABLE person (code VARCHAR(10) PRIMARY KEY, name VARCHAR(50), active BOOLEAN,"
                        + " credit DECIMAL(10,2), born DATE)",
                "INSERT INTO person VALUES ('100', 'Ada', TRUE, 250.00, DATE '1990-05-17'),"
                        + " ('200', 'Grace', TRUE, 12.50, DATE '1985-12-09'),"
                        + " ('300', 'Linus', FALSE, NULL, NULL)",
                "CREATE TABLE item (id INT PRIMARY KEY, code VARCHAR(20), deposit DECIMAL(10,2))",
                "CREATE TABLE kinds (i INT, bi BIGINT, si SMALLINT, ti TINYINT, de DECIMAL(10,2), nu NUMERIC(30,5),"
                        + " d DOUBLE, r REAL, b BOOLEAN, v VARCHAR(20000), c CHAR(5), dt DATE, tm TIME,"
                        + " ts TIMESTAMP, tstz TIMESTAMP WITH TIME ZONE, tmtz TIME WITH TIME ZONE, vb VARBINARY(100),"
                        + " bl BLOB, cl CLOB, small_cl CLOB, ar INT ARRAY, u UUID, n VARCHAR(10))");
        try (PreparedStatement insert = db.keeper.prepareStatement("INSERT INTO item VALUES (?, ?, ?)")) {
            for (int i = 1; i <= ITEMS; i++) {
                insert.setInt(1, i);
                insert.setString(2, "ITEM-" + i);
                insert.setBigDecimal(3, new java.math.BigDecimal(i + ".99"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = db.keeper.prepareStatement("INSERT INTO kinds VALUES (1, 9007199254740993,"
                + " 3, 4, 12.50, 12345678901234567890.12300, 1.5, 2.5, TRUE, ?, 'ab', DATE '2026-10-01',"
                + " TIME '10:15:30', TIMESTAMP '2026-10-01 10:15:30.123', TIMESTAMP '2026-10-01 10:15:30+02:00',"
                + " TIME '10:15:00+02:00', X'0102FF', ?, ?, 'short clob', ARRAY[1,2,3],"
                + " '6f1e8f7a-0000-4000-8000-000000000001', NULL)")) {
            insert.setString(1, repeat('v', 15000));
            byte[] blob = new byte[BLOB_BYTES];
            for (int i = 0; i < blob.length; i++) {
                blob[i] = (byte) i;
            }
            insert.setBytes(2, blob);
            insert.setString(3, repeat('c', CLOB_CHARS));
            insert.executeUpdate();
        }
        return db;
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    void exec(String... statements) throws SQLException {
        try (Statement s = keeper.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    /** A new connection to the database, writable unless the caller changes it. */
    Connection open() throws SQLException {
        return DriverManager.getConnection(url, "SA", "");
    }

    /** Single value of a query run on the keeper connection (outside the code under test). */
    Object scalar(String sql) throws SQLException {
        try (Statement s = keeper.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    @Override
    public void close() throws SQLException {
        exec("SHUTDOWN");
        keeper.close();
    }

    /** JDBC-form provider; every opened connection goes through {@link #spy}, and opens are counted. */
    final class JdbcProvider implements DataSourceProvider {
        final String id;
        final AtomicInteger opens = new AtomicInteger();
        volatile JdbcSpy spy = new JdbcSpy();

        JdbcProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return "HSQLDB in-memory test database";
        }

        @Override
        public Connection open() throws SQLException {
            opens.incrementAndGet();
            return spy.wrap(TestDb.this.open());
        }
    }

    /** Statement-API-form provider: runs the statement itself and returns rows without asking for column names. */
    final class StatementProvider implements DataSourceProvider {
        final String id;
        final boolean withNames;
        final AtomicInteger opens = new AtomicInteger();
        volatile String lastSql;
        volatile List<Object> lastParams;
        volatile int lastMaxRows;
        volatile long lastTimeoutMs;

        StatementProvider(String id, boolean withNames) {
            this.id = id;
            this.withNames = withNames;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return "statement API over the test database";
        }

        @Override
        public Connection open() {
            opens.incrementAndGet();
            return null;
        }

        @Override
        public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) throws SQLException {
            lastSql = sql;
            lastParams = params;
            lastMaxRows = maxRows;
            lastTimeoutMs = timeoutMs;
            try (Connection c = TestDb.this.open(); PreparedStatement ps = c.prepareStatement(sql)) {
                c.setReadOnly(true);
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                List<String> names = new ArrayList<String>();
                List<List<Object>> rows = new ArrayList<List<Object>>();
                boolean truncated = false;
                try (ResultSet rs = ps.executeQuery()) {
                    int width = rs.getMetaData().getColumnCount();
                    for (int i = 1; i <= width; i++) {
                        names.add(rs.getMetaData().getColumnLabel(i));
                    }
                    while (rs.next()) {
                        if (rows.size() == maxRows) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<Object>();
                        for (int i = 1; i <= width; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
                return new QueryResult(withNames ? names : null, null, rows, truncated);
            }
        }
    }
}
