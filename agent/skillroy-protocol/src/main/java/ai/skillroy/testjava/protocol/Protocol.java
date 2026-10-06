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

package ai.skillroy.testjava.protocol;

/**
 * Constants both ends of the control channel must agree on: the protocol version, the HTTP paths and the names under
 * which the agent publishes itself. Kept in one place so that agent and controller never drift apart.
 *
 * <p>Constants only; usable from any thread.
 */
public final class Protocol {

    /** Protocol version of this build. The major part ("0") must match on both ends; {@code /ping} rejects others. */
    public static final String VERSION = "0.2";

    /** HTTP paths served by the agent's loopback control server. */
    public static final String PATH_RPC = "/rpc", PATH_EVENTS = "/events", PATH_SCHEMA = "/schema",
            PATH_CAPABILITIES = "/capabilities", PATH_PING = "/ping";

    /**
     * Prefix of the agent's system properties: {@code port}, {@code nonce}, {@code tokenFile}, {@code protocol},
     * {@code loadMode}, {@code discoveryFile}, {@code session}, {@code enabled}, {@code error}. Option defaults are
     * also read from {@code skillroy.agent.<key>}.
     */
    public static final String PROP_PREFIX = "skillroy.agent.";

    /**
     * System property under which a running agent publishes the session id of its current start
     * ({@code skillroy.agent.session}; see {@link Auth#newSessionId()}). Not a secret, and never an option: the agent
     * generates a new session id at every start.
     */
    public static final String PROP_SESSION = PROP_PREFIX + "session";

    /**
     * Field name of the session id in the discovery file and in the {@code /ping} reply body ({@code "sessionId"}).
     */
    public static final String KEY_SESSION_ID = "sessionId";

    /**
     * Most elements a JSON-RPC batch on {@code POST /rpc} may hold. A larger batch is answered with a single
     * {@code INVALID_REQUEST} error and none of its elements runs. Bounds the work and the size of the reply one
     * request can cause.
     */
    public static final int MAX_BATCH = 100;

    /**
     * Key in a request's {@code params._meta}: an array naming the top-level params whose values are secrets
     * ({@code "secretParams": ["value"]}; contract amendment A34). The agent passes those params to the primitive
     * unchanged and replaces their values by {@link SecretKeys#REDACTED} wherever it logs, traces or reports the
     * call: its log lines about the call and the error data it answers with. Events keep their payloads as
     * published: what the call does is not what the core writes about it.
     */
    public static final String META_SECRET_PARAMS = "secretParams";

    /** Environment variable that enables the agent in JAVAAGENT and EMBEDDED mode. */
    public static final String ENV_ENABLED = "SKILLROY_AGENT_ENABLED";

    private Protocol() {
    }
}
