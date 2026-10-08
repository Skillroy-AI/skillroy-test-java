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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * What {@link SkillroyMcpServer} serves (phase5-contracts.md 9.2, 13.4: the {@code skillroy mcp} command builds these
 * from its options).
 *
 * <p>The session the server opens is {@code session} with these changes: read-only unless {@code mutating}
 * ({@code session.readOnly()} also makes the whole server read-only); the clock, when {@code clock} is given; the
 * inference settings in its bind options, when {@code inference} is given; the id {@code session-<base 36 of the
 * clock's millis>} when it has none; and the output directory {@code <outDir>/runs/<session id>} when it has none (the
 * journal is then the resource {@code skillroy://run/<session id>/journal.jsonl}).
 *
 * <p>Immutable; safe to share between threads if its components are.
 *
 * @param agent the connection to the target's agent; the server never closes it
 * @param pack the pack the server projects and the session runs with
 * @param session the session's options; null means {@link SessionOptions#defaults} without an output directory
 * @param mutating true offers the pack's verbs, the mutating primitives and {@code skillroy_scenario_run}; never a
 *     destructive primitive (D76)
 * @param outDir where captures ({@code mcp/<n>.png}), scenario runs ({@code runs/<id>/}), the session's journal and
 *     exports go; null means a new temporary directory, kept when the server ends
 * @param actor makes the actor of {@code skillroy_session_ask}; null means the tool is not offered
 * @param inference the settings of the session's binds (tier (b) through {@code skillroy_resolve} and
 *     {@code skillroy_scenario_run}), also given to the actor factory; null keeps those of {@code session}
 * @param clock the server's clock (run and session ids, the session's durations); null keeps the session's
 */
public record ServerOptions(AgentConnection agent, DomainPack pack, SessionOptions session, boolean mutating,
                            Path outDir, SessionActorFactory actor, InferenceSettings inference, Clock clock) {

    /**
     * Fills the session's default.
     *
     * @throws NullPointerException if the agent or the pack is null
     */
    public ServerOptions {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(pack, "pack");
        session = session == null ? SessionOptions.defaults(null) : session;
    }

    /** A builder for the CLI: read-only, no output directory (a temporary one), no actor, the session's settings. */
    public static Builder builder(AgentConnection agent, DomainPack pack) {
        return new Builder(agent, pack);
    }

    /** Builds {@link ServerOptions}; not thread-safe. */
    public static final class Builder {

        private final AgentConnection agent;
        private final DomainPack pack;
        private SessionOptions session;
        private boolean mutating;
        private Path outDir;
        private SessionActorFactory actor;
        private InferenceSettings inference;
        private Clock clock;

        private Builder(AgentConnection agent, DomainPack pack) {
            this.agent = agent;
            this.pack = pack;
        }

        /** The session's options (null: the defaults). */
        public Builder session(SessionOptions options) {
            this.session = options;
            return this;
        }

        /** Whether mutating tools are offered ({@code --mutating}). */
        public Builder mutating(boolean on) {
            this.mutating = on;
            return this;
        }

        /** The output directory ({@code --out}; null: a temporary one). */
        public Builder outDir(Path dir) {
            this.outDir = dir;
            return this;
        }

        /** The actor factory of {@code skillroy_session_ask} (null: not offered). */
        public Builder actor(SessionActorFactory factory) {
            this.actor = factory;
            return this;
        }

        /** The inference settings ({@code --inference …}; null: the session's). */
        public Builder inference(InferenceSettings settings) {
            this.inference = settings;
            return this;
        }

        /** The clock (null: the session's). */
        public Builder clock(Clock newClock) {
            this.clock = newClock;
            return this;
        }

        /**
         * The options.
         *
         * @throws NullPointerException if the agent or the pack is null
         */
        public ServerOptions build() {
            return new ServerOptions(agent, pack, session, mutating, outDir, actor, inference, clock);
        }
    }
}
