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

package ai.skillroy.testjava.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A private in-memory HSQLDB database for the data channel tests, with a read-only account ({@link #READER}) that may
 * only select, the way a controller-side JDBC source should be set up.
 *
 * <pre>
 * person(code VARCHAR PK, name VARCHAR, active BOOLEAN, credit DECIMAL(10,2), born DATE, seen TIMESTAMP)   3 rows
 * item(id INT PK, code VARCHAR, deposit DECIMAL(10,2))                                                    250 rows
 * account(code VARCHAR PK, password VARCHAR)                                                              2 rows
 * </pre>
 */
final class TestDatabase implements AutoCloseable {

    static final String READER = "READER";
    static final String READER_PASSWORD = "Reader-Secret-2468";
    static final int ITEMS = 250;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    final String name;
    final String url;
    private final Connection admin;

    private TestDatabase(String name) throws SQLException {
        this.name = name;
        this.url = "jdbc:hsqldb:mem:" + name;
        this.admin = DriverManager.getConnection(url, "SA", "");
    }

    static TestDatabase create() throws SQLException {
        TestDatabase db = new TestDatabase("skillroy-data-" + COUNTER.incrementAndGet() + "-" + System.nanoTime());
        db.exec("CREATE TABLE person (code VARCHAR(10) PRIMARY KEY, name VARCHAR(50), active BOOLEAN,"
                        + " credit DECIMAL(10,2), born DATE, seen TIMESTAMP)",
                "INSERT INTO person VALUES ('100', 'Ada', TRUE, 250.00, DATE '1990-05-17',"
                        + " TIMESTAMP '2026-10-01 10:15:30.123'),"
                        + " ('200', 'Grace', TRUE, 12.50, DATE '1985-12-09', NULL),"
                        + " ('300', 'Linus', FALSE, NULL, NULL, NULL)",
                "CREATE TABLE item (id INT PRIMARY KEY, code VARCHAR(20), deposit DECIMAL(10,2))",
                "CREATE TABLE account (code VARCHAR(10) PRIMARY KEY, password VARCHAR(50))",
                "INSERT INTO account VALUES ('100', 'Acct-Secret-1111'), ('200', 'Acct-Secret-2222')",
                "CREATE USER " + READER + " PASSWORD '" + READER_PASSWORD + "'",
                "GRANT SELECT ON TABLE person TO " + READER,
                "GRANT SELECT ON TABLE item TO " + READER,
                "GRANT SELECT ON TABLE account TO " + READER);
        try (PreparedStatement insert = db.admin.prepareStatement("INSERT INTO item VALUES (?, ?, ?)")) {
            for (int i = 1; i <= ITEMS; i++) {
                insert.setInt(1, i);
                insert.setString(2, "ITEM-" + i);
                insert.setBigDecimal(3, new java.math.BigDecimal(i + ".99"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        return db;
    }

    void exec(String... statements) throws SQLException {
        try (Statement s = admin.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    /** A connection of the read-only account (what the agent's provider would hand out in the fake agent). */
    Connection reader() throws SQLException {
        return DriverManager.getConnection(url, READER, READER_PASSWORD);
    }

    /** A {@code jdbc:} source entry of credentials.yaml for this database, as the read-only account. */
    Map<String, Object> source(String url, Object... extra) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", url);
        source.put("user", READER);
        source.put("password", READER_PASSWORD);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            source.put((String) extra[i], extra[i + 1]);
        }
        return source;
    }

    /** Credentials with the given JDBC sources ({@code id, entry, id, entry, ...}). */
    static Credentials credentials(Object... idsAndSources) throws CredentialsException {
        Map<String, Object> jdbc = new LinkedHashMap<>();
        for (int i = 0; i + 1 < idsAndSources.length; i += 2) {
            jdbc.put((String) idsAndSources[i], idsAndSources[i + 1]);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1L);
        doc.put("jdbc", jdbc);
        return Credentials.of(doc, "test credentials");
    }

    @Override
    public void close() throws SQLException {
        exec("SHUTDOWN");
        admin.close();
    }
}
