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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.resolve.Resolution;

import java.util.List;
import java.util.Map;

/**
 * A second model acting through the server's session: what {@code skillroy_session_ask} calls
 * (phase5-contracts.md 9.3; 12.4, host 2: the MCP client is the person's side, and the actor's model acts, both
 * journaled by origin). The CLI wires the inference module's session service into it; the server never names that
 * service, and tests use a fake. The server's {@code skillroy_resolve} goes through {@link #resolve} too when it has
 * an actor, so that a model-written resolution counts on the session's budget (13.4).
 *
 * <p>Called on the server's tool thread while it holds the session: one {@link #ask} or {@link #resolve} at a time.
 */
public interface SessionActor extends AutoCloseable {

    /**
     * Carries out one request through the session and answers when the actor's turn ends.
     *
     * @param request what the MCP client asked for, as it sent it (masked and labelled by the actor before any prompt)
     * @return the turn: a JSON-like value or a record of such values, which the server answers as masked JSON
     * @throws AgentLostException if the agent was lost during the turn; the server then ends with exit code 3
     * @throws InterruptedException if the thread is interrupted
     */
    Object ask(String request) throws AgentLostException, InterruptedException;

    /**
     * Resolves one slot of the server's session for {@code skillroy_resolve} through the actor's metered entry (a
     * model token reserved and settled on the session's budget). By default, the session's own resolve.
     *
     * @param session the server's session
     * @param strategy strategy tokens; null or empty means not restricted
     * @throws BindException as the session's resolve, or when the session's budget has no room for a model token
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    default Resolution resolve(ExecutorSession session, String slot, String kind, Map<String, Object> args,
                               List<String> strategy, Origin origin)
            throws BindException, AgentLostException, InterruptedException {
        return session.resolve(slot, kind, args, strategy, List.of(), null, origin);
    }

    /** Releases what the actor holds (its gateway); never the session, which the server owns. Never throws. */
    @Override
    void close();
}
