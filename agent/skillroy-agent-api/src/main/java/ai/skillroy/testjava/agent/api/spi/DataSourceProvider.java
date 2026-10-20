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
 * <p>Called on agent worker threads, or on the lane {@link #lane()} names, possibly concurrently; implementations must
 * be thread-safe.
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
     * fix, pass 2, P2-L3-S1). {@code open()} has no timeout: the provider bounds any wait of its own (the wait
     * for a free pooled connection, for example); the statement it serves runs with a query timeout and is cancelled while open when the
     * caller's time runs out (phase8-contracts.md 4.3).
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
     * The statement-API form with a cancellation handle (phase8-contracts.md 4.3, D529): {@code data.query} calls this
     * five-argument form, which by default delegates to the four-argument one, so that a provider written for 0.1.0
     * keeps working. A provider that can stop a running read registers the means with
     * {@link QueryControl#onCancel(Runnable)} (its statement's {@code cancel}, the application's own abort) and may
     * read {@link QueryControl#remainingMs()} and {@link QueryControl#cancelled()}. A provider that overrides only this
     * form needs a Phase 8 agent: on an older one the four-argument default answers null and the read falls to
     * {@link #open()}.
     *
     * @param control the read's handle, never null; valid for this call only
     * @see #query(String, List, int, long)
     */
    default QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs, QueryControl control)
            throws Exception {
        return query(sql, params, maxRows, timeoutMs);
    }

    /**
     * True when query() itself returns or throws within timeoutMs + 1 s whatever the source does (a statement
     * timeout the source enforces, a server-side limit). Asked per call on the dispatching worker; must not block; a
     * throw counts as false. Default false (phase8-contracts.md 4.3).
     */
    default boolean enforcesTimeout() {
        return false;
    }

    /**
     * The name of a lane this provider's module declared with {@code ThreadBroker.declareLane}, on which
     * {@code data.query} runs this source's read ({@link #query} or {@link #open()} and the JDBC read) and
     * {@link #columns(String)}; null (the default) runs it on a task of the agent's workers, as before Phase 8
     * (phase8-contracts.md 4.2, D527). Asked once per call on the dispatching worker; must not block; a throw counts
     * as null (logged by class name).
     */
    default String lane() {
        return null;
    }

    /**
     * The column names of {@code table} in the table's declared order, or null (the default) when the provider cannot
     * tell. data.query asks it only for a statement of the form SELECT * FROM &lt;table&gt; … (SelectLabels.starTable)
     * whose query() result named no column, and names that result's columns with it; the controller's allow-list
     * check (D100) reads those names. {@code table} is the table as the statement spells it, quotes removed, a schema
     * part kept; the provider compares it as its database folds names, and must answer the same relation query()
     * reads. All or nothing: a list with a null or empty entry counts as null. Called on the source's lane or the
     * read's worker task, at most once per data.query call, within that call's budget; must not block and must not
     * start host infrastructure (S34). A throw counts as null; the agent logs the exception's class only (S22).
     * (phase8-contracts.md 4.4, gap 117, D532.)
     */
    default List<String> columns(String table) {
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
