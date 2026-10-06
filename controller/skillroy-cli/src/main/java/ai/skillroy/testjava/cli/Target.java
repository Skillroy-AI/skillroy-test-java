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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.Endpoint;

import java.nio.file.Path;

/**
 * An open connection to the agent a command talks to, with what the CLI learned while choosing it.
 *
 * <p>Thread-safe as far as {@link AgentClient} is; closing it closes the client and its event streams.
 *
 * @param client the connected client; owned by this object
 * @param endpoint the endpoint the client was connected with (its {@code toString()} hides the token)
 * @param discoveryDir the discovery directory in effect for this command
 * @param mainCommand the target's main command from its discovery file, or null when unknown (used to recognise a
 *     restarted target)
 */
record Target(AgentClient client, Endpoint endpoint, Path discoveryDir, String mainCommand) implements AutoCloseable {

    /**
     * {@code text} with this connection's token replaced by {@code <redacted>}. The agent never sends the
     * token, but output that echoes application data (call results, events, failure details) passes through here
     * so that the CLI cannot print it even if a misbehaving module or application does.
     */
    String scrub(String text) {
        String token = endpoint.token();
        return text == null || token == null || token.isEmpty() ? text : text.replace(token, "<redacted>");
    }

    /** Closes the client. Never throws. */
    @Override
    public void close() {
        client.close();
    }
}
