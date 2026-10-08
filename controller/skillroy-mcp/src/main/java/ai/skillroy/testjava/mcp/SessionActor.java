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

/**
 * A second model acting through the server's session: what {@code skillroy_session_ask} calls
 * (phase5-contracts.md 9.3; 12.4, host 2: the MCP client is the person's side, and the actor's model acts, both
 * journaled by origin). The CLI wires the inference module's session service into it; the server never names that
 * service, and tests use a fake.
 *
 * <p>Called on the server's tool thread while it holds the session: one {@link #ask} at a time.
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

    /** Releases what the actor holds (its gateway); never the session, which the server owns. Never throws. */
    @Override
    void close();
}
