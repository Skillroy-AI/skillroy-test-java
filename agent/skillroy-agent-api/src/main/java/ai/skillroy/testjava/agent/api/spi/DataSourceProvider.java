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

package ai.skillroy.testjava.agent.api.spi;

import java.util.List;

/**
 * In-process, credential-free read channel to the application's own data. Lets scenarios resolve and verify facts
 * against the data the application really uses, without database credentials in the controller.
 *
 * <p>A provider implements ONE of the two forms: {@link #open()} for sources reachable through JDBC, or
 * {@link #query} for sources that only expose their own statement API (possibly without column names).
 * {@code data.query} tries {@code query()} first and falls back to {@code open()} when it returns null.
 *
 * <p>Called on agent worker threads, possibly concurrently; implementations must be thread-safe.
 *
 * <p>Agent threads carry the host's class loader as context class loader and are daemon threads in the agent's
 * thread group, which threads created on them inherit: do not lazily start host infrastructure that spawns threads
 * (connection-pool housekeeping, driver timers) from these calls; see {@code AgentModule} (finding S34).
 */
public interface DataSourceProvider {

    /** Source id used by {@code data.query}. */
    String id();

    /** Human-readable description (database kind, schema); never contains credentials. */
    String describe();

    /**
     * The JDBC form: a connection the agent sets read-only, uses for one query and then closes. Null (the default)
     * means this provider does not offer the JDBC form. The connection may come from a pool (the application's own,
     * for example): the agent runs the query in a transaction it rolls back itself, and switches auto-commit and the
     * read-only flag back to what they were, before it closes the connection (phase1-contracts.md 6; Phase 6 security
     * fix, pass 2, P2-L3-S1).
     */
    default java.sql.Connection open() throws java.sql.SQLException {
        return null;
    }

    /**
     * The statement-API form: runs one read-only statement and returns at most {@code maxRows} rows. Null (the
     * default) means this provider does not offer this form and {@link #open()} is used instead.
     *
     * @param sql a single SELECT or WITH statement, already checked by the agent
     * @param params positional parameter values (JSON scalars), never null
     * @param maxRows upper bound on the rows to return; the agent asks for one more than the caller wants so that
     *     it can tell whether the result was cut
     * @param timeoutMs time budget of the call; the implementation should give up when it is spent
     */
    default QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) throws Exception {
        return null;
    }

    /**
     * True when {@code data.query} may return this source's driver error text as {@code data.message} (finding
     * S22). The default, false, withholds it: a driver's message can echo parameter or cell values, including values
     * a {@code MaskPolicy} masks, and a mask cannot scrub free text. Even when this answers true the text is withheld
     * while any {@code MaskPolicy} is registered. The text is never logged either way. Asked on agent worker threads
     * for each failure; must not block.
     */
    default boolean exposesErrorText() {
        return false;
    }
}
