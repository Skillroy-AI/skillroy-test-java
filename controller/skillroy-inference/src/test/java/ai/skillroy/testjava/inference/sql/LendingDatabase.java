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

package ai.skillroy.testjava.inference.sql;

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.data.JdbcDataChannel;
import ai.skillroy.testjava.data.RowMask;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The lending schema of spike BS6 on an embedded HSQLDB with a read-only account, and the controller's JDBC channel
 * over it ({@code JdbcDataChannel}, source {@value #SOURCE}), as a scenario's bind reads it. Closed with
 * {@link #close()}.
 */
public final class LendingDatabase implements AutoCloseable {

    /** The data source id of the channel. */
    public static final String SOURCE = "lending";
    /** The read-only account's password: test-local, long enough for the in-text masking rule. */
    public static final String READER_PASSWORD = "Desk-Reader-Secret-7319";

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final List<String> TABLES = List.of("staff", "staff_role", "role_permission", "role_cap", "item",
            "waiver_rule", "loan", "loan_line", "audit_log");

    private final String url;
    private final Connection admin;
    private final Credentials credentials;

    private LendingDatabase(String name) throws SQLException, IOException, CredentialsException {
        this.url = "jdbc:hsqldb:mem:" + name;
        this.admin = LendingFixture.hsqldb(name);
        exec("CREATE USER READER PASSWORD '" + READER_PASSWORD + "'");
        for (String table : TABLES) {
            exec("GRANT SELECT ON TABLE " + table + " TO READER");
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("url", url);
        source.put("user", "READER");
        source.put("password", READER_PASSWORD);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1L);
        doc.put("jdbc", Map.of(SOURCE, source));
        this.credentials = Credentials.of(doc, "test credentials");
    }

    /** A fresh database with the schema, its rows and the read-only account. */
    public static LendingDatabase create(String prefix) throws Exception {
        return new LendingDatabase(prefix + "-" + COUNTER.incrementAndGet() + "-" + System.nanoTime());
    }

    /** The credentials naming the read-only account's JDBC source {@value #SOURCE}. */
    public Credentials credentials() {
        return credentials;
    }

    /** The controller's JDBC channel over the read-only account, with these row masks. */
    public JdbcDataChannel channel(List<RowMask> masks) {
        return new JdbcDataChannel(credentials, masks);
    }

    /** Runs statements as the administrator (to add rows). */
    public void exec(String... statements) throws SQLException {
        try (Statement s = admin.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        exec("SHUTDOWN");
        admin.close();
    }
}
