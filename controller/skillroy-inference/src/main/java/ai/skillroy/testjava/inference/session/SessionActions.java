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
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.inference.budget.BudgetException;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.pack.PlannedCall;

import java.util.List;
import java.util.Map;

/**
 * The session service's facade (phase7-contracts.md 9.1, decision D353): the one way a {@link SessionTools} reaches the
 * session. The origin of every call is the turn's {@code Origin.model} (the model, its text before the call, its
 * request id), fixed by the service; {@code CallPolicy} runs on every call with the host's robot flag (the one route on
 * which {@code via: ROBOT} passes, for the vision module's own V3 steps; {@code mode: ROBOT} never); every model call is
 * metered on the session's meter and written to the ledger ({@link SessionService#visionCall}).
 *
 * <p>A refusal (of {@code CallPolicy}, the credential rule's facade half, the read list, or a call outside a tool call)
 * is an {@link IllegalArgumentException} whose message names the primitive or the verb and the reason, never a value;
 * nothing was journaled or sent. Called on the session's thread only.
 */
public interface SessionActions {

    /**
     * Runs a step that will be replayed (a base verb or a pack verb) through {@code ExecutorSession.execute(verb, args,
     * origin, provenance)}, after {@code CallPolicy} on the calls it compiles to and the facade's credential checks
     * (a {@code type}, a printable {@code press} while a secret is in flight; a {@code type} whose text holds a secret
     * the mask knows).
     *
     * @param provenance the provenance object of phase7-contracts.md 9.2, written on the step's journal line; may be
     *     null
     * @throws IllegalArgumentException for a refusal (nothing journaled or sent)
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    StepOutcome execute(String verb, Object args, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException;

    /**
     * Runs a live-only raw call (a routed pointer half, a hover) through {@code ExecutorSession.execute(call, origin,
     * provenance)}, after {@code CallPolicy} and the facade's credential checks.
     *
     * @throws IllegalArgumentException for a refusal (nothing journaled or sent)
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    StepOutcome execute(PlannedCall call, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException;

    /**
     * An unjournaled read (a capture, a hit-test, {@code swing.rendering}) through {@code ExecutorSession.read}, refused
     * first for everything {@code CallPolicy.never} names, {@code data.query}, a script primitive, {@code agent.*},
     * {@code hook.*}/{@code rule.*} beyond {@code hook.health}, {@code hook.events} and {@code rule.list}, every
     * mutating or destructive primitive, and a {@code swing.capture} with {@code mode: ROBOT} or {@code redact: false}.
     *
     * @return the masked result (an image's {@code base64} as the agent sent it)
     * @throws IllegalArgumentException for a refusal (nothing sent)
     * @throws AgentRpcException with the agent's code, message and data
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    Object read(String primitive, Map<String, Object> args)
            throws AgentRpcException, AgentLostException, InterruptedException;

    /**
     * One model call ({@link SessionService#visionCall}: purpose {@code vision}, metered before, ledgered after).
     *
     * @throws BudgetException when the session meter has no room for it
     * @throws InferenceException when the gateway fails, or the session's vision turn limit was reached
     * @throws InterruptedException if the thread is interrupted
     */
    ModelResponse complete(ModelRequest request) throws BudgetException, InferenceException, InterruptedException;

    /** The session's merged capability matrix (phase7-contracts.md 8.5). */
    CapabilityMatrix capabilities();

    /**
     * The session's text snapshot, bounded.
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    String snapshotText(int maxChars) throws AgentLostException, InterruptedException;

    /** The target's events after {@code seq}, of the topics given (null: all), at most {@code max}. */
    List<Event> eventsSince(long seq, String topics, int max);

    /** Whether a credential is in flight in the session (phase7-contracts.md 9.2, D342). */
    boolean secretsInFlight();

    /**
     * The session's output directory, where the vision module keeps {@code captures/} (phase7-contracts.md 9.6), or
     * null for a session without one (it keeps nothing). As built (Phase 7, VM): the contract's facade had no way to
     * reach it.
     */
    java.nio.file.Path outputDir();

    /**
     * The session's step timeout ({@code SessionOptions.stepTimeout}): what the vision module's {@code wait} settles
     * for (phase7-contracts.md 6.3). Fix wave (Phase 7 review, FC): the facade had no way to reach it, and the tool
     * settled for a fixed 5 s. The default, for a facade that does not know it, is
     * {@code SessionOptions.DEFAULT_STEP_TIMEOUT}.
     */
    default java.time.Duration stepTimeout() {
        return ai.skillroy.testjava.scenario.SessionOptions.DEFAULT_STEP_TIMEOUT;
    }

    /**
     * The pack's screen legend through the session's mask, or null (phase7-contracts.md 6.4, 12.3: a visual
     * question carries it). As built (Phase 7, VM): the contract's context had no way to reach the pack.
     */
    String screenLegend();

    /**
     * A note in the session's journal under the turn's origin (phase7-contracts.md 6.4: {@code skillroy_look}'s
     * verdict and the capture's hash), masked by the journal. As built (Phase 7, VM).
     *
     * @throws IllegalArgumentException outside a tool call
     */
    void note(String text);
}
