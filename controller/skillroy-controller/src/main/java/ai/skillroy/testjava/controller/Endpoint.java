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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.LoadMode;

import java.util.Objects;

/**
 * Where and how to reach one running agent: loopback address, the shared token (the MAC key, never sent), the nonce
 * that proves it is the agent we loaded, the identity (pid plus start time) of its JVM and the session id of the agent
 * start. Produced by discovery, attach or the launcher and consumed by the client.
 *
 * <p>Immutable; safe to share between threads. {@link #toString()} never shows the token.
 *
 * @param host loopback host, normally {@code 127.0.0.1}
 * @param port the agent's control port, 1-65535
 * @param token the token shared with the agent, may be null only before it has been read from its token file
 * @param nonce nonce reported by {@code /ping} as {@code agentNonce}, may be null when unknown
 * @param pid target JVM pid, 0 when unknown
 * @param startTimeMs target JVM start time in epoch milliseconds, 0 when unknown
 * @param loadMode how the agent was loaded, may be null when unknown
 * @param sessionId the agent's session id (32 lower-case hex characters) when known, for example from its discovery
 *     file; the client then requires the agent to report exactly this one. Null when unknown: the client learns it
 *     from the first verified {@code /ping}
 */
public record Endpoint(String host, int port, String token, String nonce, long pid, long startTimeMs,
                       LoadMode loadMode, String sessionId) {

    /** Validates host, port and the session id. */
    public Endpoint {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (sessionId != null && !Auth.isValidSessionId(sessionId)) {
            throw new IllegalArgumentException("sessionId must be " + Auth.SESSION_ID_LENGTH
                    + " lower-case hex characters");
        }
    }

    /** An endpoint whose session id is not known yet (the client learns it from the first verified ping). */
    public Endpoint(String host, int port, String token, String nonce, long pid, long startTimeMs, LoadMode loadMode) {
        this(host, port, token, nonce, pid, startTimeMs, loadMode, null);
    }

    /** A copy with another session id (null for unknown). */
    public Endpoint withSessionId(String id) {
        return new Endpoint(host, port, token, nonce, pid, startTimeMs, loadMode, id);
    }

    @Override
    public String toString() {
        return "Endpoint[host=" + host + ", port=" + port + ", token=" + (token == null ? "null" : "***")
                + ", nonce=" + nonce + ", pid=" + pid + ", startTimeMs=" + startTimeMs + ", loadMode=" + loadMode
                + ", sessionId=" + sessionId + "]";
    }
}
