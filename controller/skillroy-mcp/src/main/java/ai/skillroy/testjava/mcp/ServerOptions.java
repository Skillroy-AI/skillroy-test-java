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
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.regex.Pattern;

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
 * @param allowEvaluate {@code web_wait_flag} is offered, and with {@code mutating} {@code web_evaluate} too: each runs
 *     a caller's script in the target's pages, a person's opt-in ({@code --allow-evaluate}); never the planner's
 *     server (decision D98)
 * @param clientLabel the label of the server's origin, {@code Origin.client(clientLabel)} (phase6 security fix S57,
 *     D158; {@code skillroy mcp --client}): every action of the client is journaled and exported as
 *     {@code client <label>} and confirms nothing; null or blank means {@value #DEFAULT_CLIENT}; {@code author} passes
 *     {@code planner}
 * @param sessionMeter the server's one {@code SESSION} budget meter (phase6 security fix S56, D157), shared with the
 *     actor of {@code skillroy_session_ask}: {@code skillroy_resolve} through the actor and the tier (b) slots of
 *     {@code skillroy_scenario_run} are metered on it; null when inference is not enabled (a run is then not metered
 *     beyond its scenario budget, and makes no model call without a gateway)
 */
public record ServerOptions(AgentConnection agent, DomainPack pack, SessionOptions session, boolean mutating,
                            Path outDir, SessionActorFactory actor, InferenceSettings inference, Clock clock,
                            boolean allowEvaluate, String clientLabel, BudgetMeter sessionMeter) {

    /** The client label when none is given. */
    public static final String DEFAULT_CLIENT = "mcp";

    /** What a client label may be: a short name ({@code [A-Za-z0-9._-]}, at most 64 characters). */
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * Fills the session's default and the client label's.
     *
     * @throws NullPointerException if the agent or the pack is null
     * @throws IllegalArgumentException if the client label is not a short name
     */
    public ServerOptions {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(pack, "pack");
        session = session == null ? SessionOptions.defaults(null) : session;
        clientLabel = clientLabel == null || clientLabel.isBlank() ? DEFAULT_CLIENT : clientLabel.strip();
        if (!LABEL.matcher(clientLabel).matches()) {
            throw new IllegalArgumentException("a client label is a short name of letters, digits, '.', '_' and '-'"
                    + " (at most 64 characters)");
        }
    }

    /**
     * The options without a client label or a session meter (the shape before phase6 security fixes S56 and S57).
     *
     * @throws NullPointerException if the agent or the pack is null
     */
    public ServerOptions(AgentConnection agent, DomainPack pack, SessionOptions session, boolean mutating,
                         Path outDir, SessionActorFactory actor, InferenceSettings inference, Clock clock,
                         boolean allowEvaluate) {
        this(agent, pack, session, mutating, outDir, actor, inference, clock, allowEvaluate, null, null);
    }

    /** Whether a text may be a client label ({@code skillroy mcp --client}). */
    public static boolean isClientLabel(String label) {
        return label != null && LABEL.matcher(label).matches();
    }

    /**
     * The options without {@code web_evaluate} (the shape before decision D98).
     *
     * @throws NullPointerException if the agent or the pack is null
     */
    public ServerOptions(AgentConnection agent, DomainPack pack, SessionOptions session, boolean mutating,
                         Path outDir, SessionActorFactory actor, InferenceSettings inference, Clock clock) {
        this(agent, pack, session, mutating, outDir, actor, inference, clock, false);
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
        private boolean allowEvaluate;
        private String clientLabel;
        private BudgetMeter sessionMeter;

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
         * Whether {@code web_wait_flag} and, with {@code mutating}, {@code web_evaluate} are offered
         * ({@code --allow-evaluate}, D98).
         */
        public Builder allowEvaluate(boolean on) {
            this.allowEvaluate = on;
            return this;
        }

        /** The label of the server's client origin ({@code --client}; null: {@value ServerOptions#DEFAULT_CLIENT}). */
        public Builder clientLabel(String label) {
            this.clientLabel = label;
            return this;
        }

        /** The server's one {@code SESSION} budget meter, shared with the actor (null: none, D157). */
        public Builder sessionMeter(BudgetMeter meter) {
            this.sessionMeter = meter;
            return this;
        }

        /**
         * The options.
         *
         * @throws NullPointerException if the agent or the pack is null
         * @throws IllegalArgumentException if the client label is not a short name
         */
        public ServerOptions build() {
            return new ServerOptions(agent, pack, session, mutating, outDir, actor, inference, clock, allowEvaluate,
                    clientLabel, sessionMeter);
        }
    }
}
