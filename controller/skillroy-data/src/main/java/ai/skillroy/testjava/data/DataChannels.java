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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.protocol.sql.Rows;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The router (phase2-contracts.md 7.2, decision D16): a source named under {@code jdbc:} in {@code credentials.yaml}
 * is read over JDBC on the controller, every other source through the agent. Routing is an environment fact, so the
 * same pack and scenario work whether an environment's database is inside the target or beside it.
 *
 * <p>When neither channel serves a source the error is {@code UNKNOWN_SOURCE} and its message lists the ids each
 * channel serves. Thread-safe as its channels are; {@link #close()} closes the JDBC connections (the agent connection
 * belongs to the caller).
 */
public final class DataChannels implements DataChannel, AutoCloseable {

    private final AgentDataChannel agent;
    private final JdbcDataChannel jdbc;
    private final Set<String> jdbcSources;

    private DataChannels(AgentDataChannel agent, JdbcDataChannel jdbc, Set<String> jdbcSources) {
        this.agent = agent;
        this.jdbc = jdbc;
        this.jdbcSources = jdbcSources;
    }

    /**
     * @param agent the agent connection, or null when no target is connected (only JDBC sources are served then)
     * @param credentials the environment's credentials; null means none
     * @param masks the pack's row masks for the JDBC channel; null means none
     */
    public static DataChannels of(AgentConnection agent, Credentials credentials, List<RowMask> masks) {
        Credentials known = credentials == null ? Credentials.empty() : credentials;
        return new DataChannels(agent == null ? null : new AgentDataChannel(agent),
                new JdbcDataChannel(known, masks), known.jdbcSources());
    }

    @Override
    public boolean serves(String source) throws AgentLostException {
        return serves(source, null);
    }

    @Override
    public boolean serves(String source, Duration timeout) throws AgentLostException {
        return jdbc.serves(source) || agent != null && agent.serves(source, timeout);
    }

    /** As {@link DataChannel#query}; with a timeout, the agent's {@code data.sources} waits at most that long too. */
    @Override
    public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
            throws DataException, AgentLostException, InterruptedException {
        if (jdbc.serves(source)) {
            return jdbc.query(source, sql, params, maxRows, timeout);
        }
        if (agent != null && agent.serves(source, timeout)) {
            return agent.query(source, sql, params, maxRows, timeout);
        }
        Queries.check(source, sql, params, maxRows, timeout);
        Set<String> agentSources = agent == null ? Set.of() : agent.servedSources(timeout);
        throw new DataException(DataException.Kind.UNKNOWN_SOURCE, source, "no data channel serves source '"
                + source + "'; over JDBC (credentials.yaml): " + jdbcSources + "; through the agent: "
                + (agent == null ? "no agent" : agentSources));
    }

    /** "jdbc" for a source {@code credentials.yaml} names under {@code jdbc:}, else "agent". */
    @Override
    public String kind(String source) {
        return jdbc.serves(source) ? "jdbc" : "agent";
    }

    /**
     * The JDBC source's {@code statementTimeout}; null for a source the agent serves (its {@code data.query}
     * descriptor holds the default, which the caller reads from the agent's schema).
     */
    @Override
    public Duration defaultTimeout(String source) {
        return jdbc.serves(source) ? jdbc.defaultTimeout(source) : null;
    }

    /** Closes the JDBC connections. Idempotent; never throws. */
    @Override
    public void close() {
        jdbc.close();
    }

    @Override
    public String toString() {
        return "DataChannels[" + jdbc + ", " + (agent == null ? "no agent" : agent.toString()) + "]";
    }
}
