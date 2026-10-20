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

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.protocol.sql.ReadOnlyJdbc;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.StatementGuard;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The JDBC form of {@code data.query}: runs one already-guarded statement through the shared read path
 * ({@link ReadOnlyJdbc}, which sets the connection read-only, binds, reads, converts and restores it) on a connection
 * obtained from {@code DataSourceProvider.open()}, and always closes that connection afterwards, since it was opened
 * for this call alone.
 *
 * <p>Clean-up failures (from the read path and from the close here) go to the agent log as {@code data: ...} lines that
 * name the exception class, SQL state and error code but never the driver's text (finding S22); they are never thrown,
 * so they cannot hide the query's own outcome.
 *
 * <p>Stateless; runs on the calling thread (an agent worker).
 */
final class JdbcExecutor {

    private JdbcExecutor() {
    }

    /**
     * Runs {@code sql} and converts at most {@code maxRows} rows, then closes the connection.
     *
     * @param sql a statement already accepted by {@link StatementGuard}; this method does not check it again
     * @param params positional parameters bound with {@code setObject}, as {@code data.query} accepts them
     * @param timeoutSeconds query timeout, at least 1
     * @throws SQLException from the driver
     * @throws InterruptedException if the calling thread is interrupted while rows are read (the dispatcher has then
     *     given up on the call)
     */
    static Rows execute(Connection connection, String sql, List<Object> params, int maxRows, int timeoutSeconds,
                        final AgentLog log) throws SQLException, InterruptedException {
        return execute(connection, sql, params, maxRows, timeoutSeconds, log, null);
    }

    /**
     * {@link #execute(Connection, String, List, int, int, AgentLog)} under a read's {@link Control}
     * (phase8-contracts.md 4.3, D530): the statement is registered through the read path's statement sink, so that
     * when the agent gives up on the read its cancel action calls {@code Statement.cancel()} while, and only while, the
     * statement is open (never a later statement of a pooled connection). A {@code SQLException} (or
     * {@code SQLFeatureNotSupportedException}) from {@code cancel()} is logged by class name and counts as not
     * cancelled.
     *
     * @param control the read's control, or null (no cancellation)
     */
    static Rows execute(Connection connection, String sql, List<Object> params, int maxRows, int timeoutSeconds,
                        final AgentLog log, Control control) throws SQLException, InterruptedException {
        Consumer<String> warn = new Consumer<String>() {
            @Override
            public void accept(String line) {
                log.warn("data: " + line);
            }
        };
        final AtomicReference<Statement> current = new AtomicReference<Statement>();
        AutoCloseable registration = null;
        try {
            if (control != null) {
                registration = control.onCancelInternal(new Control.Action() {
                    @Override
                    public boolean cancel() {
                        Statement statement = current.get();
                        if (statement == null) {
                            return false;
                        }
                        try {
                            statement.cancel();
                            return true;
                        } catch (SQLException e) {
                            log.warn("data: Statement.cancel() failed: " + e.getClass().getName());
                            return false;
                        }
                    }

                    @Override
                    public String describe() {
                        return "Statement.cancel";
                    }
                });
            }
            return ReadOnlyJdbc.executeLenient(connection, sql, params, maxRows, timeoutSeconds, warn,
                    new Consumer<Statement>() {
                        @Override
                        public void accept(Statement statement) {
                            current.set(statement);
                        }
                    });
        } finally {
            current.set(null);
            if (registration != null) {
                try {
                    registration.close();
                } catch (Exception ignored) {
                    // The control's handles do not throw.
                }
            }
            try {
                connection.close();
            } catch (Exception e) {
                log.warn("data: could not close the connection: " + Failures.describe(e));
            }
        }
    }
}
