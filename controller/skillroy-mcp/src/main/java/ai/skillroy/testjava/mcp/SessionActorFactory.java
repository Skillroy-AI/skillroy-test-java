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

import ai.skillroy.testjava.scenario.ExecutorSession;

/**
 * Makes the {@link SessionActor} of a server's session. Given to the server in {@link ServerOptions#actor()}; without
 * one, {@code skillroy_session_ask} is not offered.
 */
@FunctionalInterface
public interface SessionActorFactory {

    /**
     * The actor of this server's session. Called at most once, on the first {@code skillroy_session_ask}, on the tool
     * thread that holds the session; the server closes the actor before it closes the session.
     *
     * @param session the server's session, owned by the server; read-only unless the server offers mutating tools
     * @param options the server's options ({@link ServerOptions#mutating()}, the inference settings, the clock)
     * @throws Exception when no actor can be made (no model key, no gateway): that {@code skillroy_session_ask} is
     *     answered with a tool error, and the next one tries again
     */
    SessionActor create(ExecutorSession session, ServerOptions options) throws Exception;
}
