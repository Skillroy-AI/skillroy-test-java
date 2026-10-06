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
import java.util.List;
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
        Consumer<String> warn = new Consumer<String>() {
            @Override
            public void accept(String line) {
                log.warn("data: " + line);
            }
        };
        try {
            return ReadOnlyJdbc.executeLenient(connection, sql, params, maxRows, timeoutSeconds, warn);
        } finally {
            try {
                connection.close();
            } catch (Exception e) {
                log.warn("data: could not close the connection: " + Failures.describe(e));
            }
        }
    }
}
