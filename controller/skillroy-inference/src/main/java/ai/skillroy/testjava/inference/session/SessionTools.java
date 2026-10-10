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

package ai.skillroy.testjava.inference.session;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ProviderTool;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Tools a controller-side module adds to an interactive session's model (phase7-contracts.md 9.1, decisions D302,
 * D353; Phase 7: the vision module). Found through {@code ServiceLoader} ({@link #load}); one instance per session;
 * called on the session's thread; reaches the session, the agent and the model only through the facade in its context
 * ({@link SessionToolContext#actions()}), never through an {@code ExecutorSession} or a gateway of its own.
 *
 * <p>The session service asks every loaded tool {@link #applies} at its construction and keeps those that do: it lists
 * their {@link #tools} after the projection's and before {@code done} and {@code ask_person}, sends their
 * {@link #providerTools}, puts their {@link #instructions} into the system parts after its own (through the mask),
 * appends their {@link #atTurnStart} parts to a turn's first message and their {@link #afterBatch} parts after a batch's
 * results, consults {@link #handles} before "there is no tool", counts an {@link Outcome#step()} against the action
 * limits, ends the turn on an {@link Outcome#endTurn()} other than {@code NONE}, enforces {@code vision.maxActions} and
 * {@code vision.maxTurns}, and closes the tools with itself (or at a limit).
 */
public interface SessionTools extends AutoCloseable {

    /** The most providers {@link #load} looks at. */
    int MAX_PROVIDERS = 100;

    /**
     * The tools {@code ServiceLoader} finds through {@code loader} (null: the thread's context class loader), in its
     * order: a provider that cannot be loaded ({@code ServiceConfigurationError}, {@code LinkageError}, a runtime
     * exception from its constructor) is skipped, as {@code Contributors.load} skips a contributor. At most
     * {@value #MAX_PROVIDERS} providers are looked at. A new instance of each per call (one per session).
     */
    static List<SessionTools> load(ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        if (effective == null) {
            effective = SessionTools.class.getClassLoader();
        }
        List<SessionTools> found = new ArrayList<>();
        try {
            Iterator<SessionTools> providers = ServiceLoader.load(SessionTools.class, effective).iterator();
            for (int i = 0; i < MAX_PROVIDERS; i++) {
                try {
                    if (!providers.hasNext()) {
                        break;
                    }
                    found.add(providers.next());
                } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                    // A provider that cannot be loaded adds nothing to the session.
                }
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            // As above: no tools from this loader.
        }
        return List.copyOf(found);
    }

    /** The tool set's id ({@code "vision"}). */
    String id();

    /** False: the module stays silent in this session (nothing listed, nothing sent, nothing appended). */
    boolean applies(SessionToolContext ctx);

    /** Custom strict tools, projected through {@code ctx.mask()}; names {@code [a-z0-9_]}. */
    List<ToolSpec> tools(SessionToolContext ctx);

    /** Provider entries (the toolset); may be empty. */
    List<ProviderTool> providerTools(SessionToolContext ctx);

    /** Extra system text; null for none; masked by the service. */
    String instructions(SessionToolContext ctx);

    /** True for the names {@link #act} answers (custom tools and toolset members). */
    boolean handles(String toolName);

    /**
     * The model class the session's calls take while this tool set applies (phase7-contracts.md 6.2: the class of
     * {@code VisionOptions.model}, default Sonnet). As built (Phase 7, VM): the contract's interface had no way to name
     * it; the service takes the first applying tool set's class.
     */
    default ModelClass modelClass(SessionToolContext ctx) {
        return ModelClass.SONNET_CLASS;
    }

    /**
     * Parts appended to the turn's first {@code USER} message (a capture and its marks list).
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    List<Content> atTurnStart(SessionToolContext ctx) throws AgentLostException, InterruptedException;

    /**
     * One tool call; the step it ran, if any.
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    Outcome act(Content.ToolUse use, SessionToolContext ctx) throws AgentLostException, InterruptedException;

    /**
     * Parts appended after a batch's results (a fresh capture).
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    List<Content> afterBatch(SessionToolContext ctx) throws AgentLostException, InterruptedException;

    /** Releases what the tool set holds; never throws. */
    @Override
    default void close() {
    }

    /** How a tool call wants the turn to go on. */
    enum EndTurn {
        /** The turn goes on. */
        NONE,
        /** The turn ends as {@code ask_person} ends it, the reason as the model's question. */
        QUESTION,
        /** The turn ends as the action limit ends it. */
        LIMIT
    }

    /**
     * What one tool call gave.
     *
     * @param result the tool result the model reads (its id the call's, its toolset name the call's)
     * @param step the step the call ran through the facade, or null for a read
     * @param endTurn how the turn goes on; null is {@code NONE}
     * @param reason the reason of a {@code QUESTION} or {@code LIMIT}, or null
     */
    record Outcome(Content.ToolResult result, StepOutcome step, EndTurn endTurn, String reason) {

        /** @throws NullPointerException if the result is null */
        public Outcome {
            Objects.requireNonNull(result, "result");
            endTurn = endTurn == null ? EndTurn.NONE : endTurn;
        }

        /** A result that ran no step and lets the turn go on. */
        public static Outcome of(Content.ToolResult result) {
            return new Outcome(result, null, EndTurn.NONE, null);
        }
    }

    /**
     * What a tool set may use.
     *
     * @param actions the service's facade: the one way to the session, the agent and the model
     * @param policy the session's policy
     * @param mask the session's prompt mask (images as the settings say)
     * @param settings the session's inference settings
     * @param options the host's tool options ({@code vision.tier}, {@code vision.model}, {@code vision.tools},
     *     {@code vision.zoom}, {@code vision.maxActions}, {@code vision.maxTurns})
     * @param robotAllowed the host's robot flag ({@code SessionOptions.robot}, a person's {@code --allow-robot})
     * @param clock the session's clock
     */
    record SessionToolContext(SessionActions actions, SessionPolicy policy, PromptMask mask, InferenceSettings settings,
                              Map<String, String> options, boolean robotAllowed, Clock clock) {

        /** Copies the options. */
        public SessionToolContext {
            Objects.requireNonNull(actions, "actions");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(mask, "mask");
            settings = settings == null ? InferenceSettings.none() : settings;
            options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
            clock = clock == null ? Clock.systemUTC() : clock;
        }
    }
}
