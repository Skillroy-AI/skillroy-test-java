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

package ai.skillroy.testjava.samples.lending;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Properties;

import org.hsqldb.jdbc.JDBCDriver;

/**
 * Used by {@link LendingAppMainTest} in a child JVM started with {@code -Dlending.dbPort=0} (any free port) and
 * {@code -Dlending.dbPassword}: once the desk waits for input it connects to the listener the desk reports
 * ({@link LendingApp#dbServer()}, on the port the desk chose) as the read-only account, with the password the child
 * JVM was given, the way a client outside the JVM does; it writes what it could read and that a write was refused to
 * the file named by {@code lending.test.marker} (never the password), and exits the JVM; its {@link #stop()} appends
 * {@code stopped}.
 */
public class DbProbePlugin implements LendingPlugin {

    private static void write(String line) {
        try {
            Files.write(Paths.get(System.getProperty("lending.test.marker")),
                Collections.singletonList(line), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String probe(LendingDbServer server) {
        if (server == null) {
            return "no listener";
        }
        String password = System.getProperty(LendingOptions.PROP_DB_PASSWORD);
        if (password == null) {
            return "no " + LendingOptions.PROP_DB_PASSWORD;
        }
        Properties props = new Properties();
        props.setProperty("user", LendingDb.READER_USER);
        props.setProperty("password", password);
        try (Connection c = new JDBCDriver().connect(server.url(), props); Statement st = c.createStatement()) {
            String staff;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM staff")) {
                rs.next();
                staff = rs.getString(1);
            }
            String delete;
            try {
                st.executeUpdate("DELETE FROM desk_setting");
                delete = "accepted";
            } catch (SQLException e) {
                delete = "refused " + e.getSQLState();
            }
            return "staff=" + staff + " delete=" + delete;
        } catch (SQLException e) {
            return "failed " + e.getSQLState();
        }
    }

    @Override
    public void start(LendingApp app) {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            while (!app.engine().isAwaitingInput() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
            LendingDbServer server = app.dbServer();
            write("db " + probe(server) + " server=" + server);
            System.exit(0);
        }, "db-probe");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void stop() {
        write("stopped");
    }
}
