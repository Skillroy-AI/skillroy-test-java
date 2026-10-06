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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.protocol.sql.Rows;

import java.time.Duration;
import java.util.List;

/**
 * Guarded read-only SQL against a data source of the environment, whichever side serves it (phase2-contracts.md 7):
 * the agent's {@code data.query} inside the target JVM ({@link AgentDataChannel}) or a JDBC connection the controller
 * opens itself ({@link JdbcDataChannel}); {@link DataChannels} routes by source id. Resolvers, verifiers,
 * fingerprinters, pre-flight checks and stored-row checks read through this interface, so that the same pack and
 * scenario work whether an environment's database is inside the target or beside it.
 *
 * <p>Both channels accept the same statements ({@code StatementGuard}), bind the same parameter types and return
 * values in the same forms ({@link Rows}). Implementations are thread-safe.
 */
public interface DataChannel {

    /** Most rows a statement may return; one more is fetched to report {@code truncated}. */
    int MAX_ROWS = 200;

    /**
     * True if this channel can serve the source id.
     *
     * @throws AgentLostException if the agent had to be asked and is gone
     */
    boolean serves(String source) throws AgentLostException;

    /**
     * {@link #serves(String)}, waiting at most {@code timeout} for the channel to find out (the agent channel asks
     * {@code data.sources} once); null means the channel's own bound. The binder passes what is left of its budget.
     *
     * @throws AgentLostException if the agent had to be asked and is gone
     */
    default boolean serves(String source, Duration timeout) throws AgentLostException {
        return serves(source);
    }

    /**
     * One guarded read-only statement.
     *
     * @param params each a {@code String}, {@code Long}, {@code Boolean} or null (decision D23: a decimal as its plain
     *     string, a date or time as ISO-8601 text; the committed SQL casts)
     * @param maxRows rows to return, 0 to 200
     * @param timeout the statement's timeout; null means the source's default
     * @throws IllegalArgumentException for a parameter of another type, a {@code maxRows} outside 0..200, or a null
     *     source or statement (a pack defect)
     * @throws DataException when the statement is refused or fails; never carries the driver's message
     * @throws AgentLostException if the agent serves the source and is gone
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
            throws DataException, AgentLostException, InterruptedException;

    /** "agent" or "jdbc" for a concrete channel; the router answers for the source. */
    String kind(String source);

    /**
     * The timeout a statement on this source gets when the caller passes none (the "source's default" of
     * {@link #query}), or null when this channel does not know it. The binder clamps it to what is left of its
     * budget instead of passing null (phase2-contracts.md 6.4, 7.1). Never contacts the target.
     */
    default Duration defaultTimeout(String source) {
        return null;
    }
}
