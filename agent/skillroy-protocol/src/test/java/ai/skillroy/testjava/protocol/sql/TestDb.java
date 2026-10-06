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

package ai.skillroy.testjava.protocol.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A private in-memory HSQLDB database with a small fixed data set (the one the agent's data module tests use).
 *
 * <pre>
 * person(code VARCHAR PK, name VARCHAR, active BOOLEAN, credit DECIMAL(10,2), born DATE)   3 rows
 * item(id INT PK, code VARCHAR, deposit DECIMAL(10,2))                                      250 rows, id 1..250
 * </pre>
 */
final class TestDb implements AutoCloseable {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    static final int ITEMS = 250;

    final String url;
    private final Connection keeper;

    private TestDb(String url) throws SQLException {
        this.url = url;
        this.keeper = DriverManager.getConnection(url, "SA", "");
    }

    static TestDb create() throws SQLException {
        TestDb db = new TestDb("jdbc:hsqldb:mem:skillroy-sql-" + COUNTER.incrementAndGet() + "-" + System.nanoTime());
        db.exec("CREATE TABLE person (code VARCHAR(10) PRIMARY KEY, name VARCHAR(50), active BOOLEAN,"
                        + " credit DECIMAL(10,2), born DATE)",
                "INSERT INTO person VALUES ('100', 'Ada', TRUE, 250.00, DATE '1990-05-17'),"
                        + " ('200', 'Grace', TRUE, 12.50, DATE '1985-12-09'),"
                        + " ('300', 'Linus', FALSE, NULL, NULL)",
                "CREATE TABLE item (id INT PRIMARY KEY, code VARCHAR(20), deposit DECIMAL(10,2))");
        try (PreparedStatement insert = db.keeper.prepareStatement("INSERT INTO item VALUES (?, ?, ?)")) {
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
}
