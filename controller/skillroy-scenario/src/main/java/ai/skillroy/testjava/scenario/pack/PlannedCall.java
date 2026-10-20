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

package ai.skillroy.testjava.scenario.pack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One primitive call a verb compiles to; it becomes one entry of a step's {@code calls} in {@code plan.json}.
 * {@code args} may hold {@code $slot} references; they are resolved when the scenario runs, never at compile time.
 *
 * <p>Immutable apart from the values inside {@code args}, which the compiler deep-copies into the plan. Safe to share
 * between threads.
 *
 * @param primitive the primitive's name, for example {@code "lending.input"}; not blank
 * @param args the primitive's params; null means none. Any map type may be used: the compiler writes the keys of
 *     every args object sorted, so that the plan stays byte-identical across JVM runs
 * @param sync how the executor waits after the call; null means {@link Sync#NONE}
 * @param timeoutMs the call's timeout in milliseconds; zero or negative means the scenario's step timeout
 * @param await the event that completes the call: required when {@code sync} is {@link Sync#EVENT}, null otherwise
 * @param capture the slot bound from the event that ended the call's wait (phase2-contracts.md 3.4), or null; only on
 *     a call with sync {@link Sync#EVENT} or on {@code sync.await}
 * @param literalArgs true when {@code args} are literal (phase6 security fix S55; phase5-contracts.md 9.3, 12.2): an
 *     interactive session ({@code ExecutorSession.execute(PlannedCall, Origin)}) compiles and runs the call without
 *     reference substitution, so that every string reaches the agent exactly as given (a {@code $slot.field} in it is
 *     text, never a slot's value); the MCP server's {@code data_query} builds its call so, the guarded statement being
 *     what runs. False everywhere else, as before (the constructors without the component); a plan has no such call
 * @param accept the warning codes the call accepts (phase8-contracts.md 7.1, 7.3): a warning of such a code is
 *     recorded and leaves the verdict alone; any other fails the step. A verb may pre-accept a code its primitive
 *     documents as harmless ({@link #withAccept}); the compiler adds the step's {@code accept} modifier. Empty by
 *     default; sorted
 * @param rungs the call's rung ladder, best first, 2 to 8 rungs with distinct names (phase8-contracts.md 7.6); empty
 *     for a call without one
 * @param minRung the rung the step requires (a name of {@code rungs}), or null; the compiler sets it from the step's
 *     {@code minRung} modifier or, under {@code ui: {strict: true}}, to the first rung
 */
public record PlannedCall(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                          CaptureSpec capture, boolean literalArgs, Set<String> accept, List<Rung> rungs,
                          String minRung) {

    /** The fewest and the most rungs of a ladder. */
    public static final int MIN_RUNGS = 2;
    public static final int MAX_RUNGS = 8;

    /**
     * @throws IllegalArgumentException if {@code primitive} is null or blank, {@code sync} is {@link Sync#EVENT}
     *     without an {@code await}, {@code await} is given with another sync, {@code capture} is given on a call
     *     that neither has sync {@link Sync#EVENT} nor is {@code sync.await}, an {@code accept} code is not a warning
     *     code ({@code CallWarning.isValidCode}), the ladder has fewer than 2 or more than 8 rungs or a name twice,
     *     or {@code minRung} is not a rung of the ladder
     */
    public PlannedCall {
        if (primitive == null || primitive.isBlank()) {
            throw new IllegalArgumentException("primitive must not be blank");
        }
        args = args == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        sync = sync == null ? Sync.NONE : sync;
        if (sync == Sync.EVENT && await == null) {
            throw new IllegalArgumentException("call " + primitive + ": sync EVENT needs an await spec");
        }
        if (sync != Sync.EVENT && await != null) {
            throw new IllegalArgumentException("call " + primitive + ": an await spec needs sync EVENT, found "
                    + sync);
        }
        if (capture != null && sync != Sync.EVENT && !"sync.await".equals(primitive)) {
            throw new IllegalArgumentException("call " + primitive + ": capture needs sync EVENT or the primitive"
                    + " sync.await, found sync " + sync);
        }
        accept = accept == null || accept.isEmpty() ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(accept));
        rungs = rungs == null || rungs.isEmpty() ? List.of() : List.copyOf(rungs);
        Rung.check("call " + primitive, accept, rungs, minRung);
    }

    /**
     * A call without warnings accepted, ladder or required rung (the shape before Phase 8).
     *
     * @throws IllegalArgumentException as the canonical constructor
     */
    public PlannedCall(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                       CaptureSpec capture, boolean literalArgs) {
        this(primitive, args, sync, timeoutMs, await, capture, literalArgs, Set.of(), List.of(), null);
    }

    /**
     * A call whose arguments may hold references (the shape before phase6 security fix S55).
     *
     * @throws IllegalArgumentException as the canonical constructor
     */
    public PlannedCall(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                       CaptureSpec capture) {
        this(primitive, args, sync, timeoutMs, await, capture, false);
    }

    /**
     * A call without a capture (the Phase 1 shape).
     *
     * @throws IllegalArgumentException as the canonical constructor
     */
    public PlannedCall(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await) {
        this(primitive, args, sync, timeoutMs, await, null);
    }

    /**
     * A call with any sync but {@link Sync#EVENT}, which needs an {@link AwaitSpec}.
     *
     * @throws IllegalArgumentException if {@code primitive} is blank or {@code sync} is {@link Sync#EVENT}
     */
    public PlannedCall(String primitive, Map<String, Object> args, Sync sync, long timeoutMs) {
        this(primitive, args, sync, timeoutMs, null, null);
    }

    /** A call with the given params and sync (not {@link Sync#EVENT}) and the scenario's step timeout. */
    public static PlannedCall of(String primitive, Map<String, Object> args, Sync sync) {
        return new PlannedCall(primitive, args, sync, 0);
    }

    /** A call completed by the event {@code await} describes ({@link Sync#EVENT}), with the step timeout. */
    public static PlannedCall event(String primitive, Map<String, Object> args, AwaitSpec await) {
        return new PlannedCall(primitive, args, Sync.EVENT, 0, await);
    }

    /**
     * This call with a capture: when its wait ends, the executor binds {@code capture.slot()} from the event's
     * payload.
     *
     * @throws IllegalArgumentException if the call neither has sync {@link Sync#EVENT} nor is {@code sync.await}
     */
    public PlannedCall withCapture(CaptureSpec newCapture) {
        return new PlannedCall(primitive, args, sync, timeoutMs, await, newCapture, literalArgs, accept, rungs,
                minRung);
    }

    /**
     * This call with literal arguments ({@link #literalArgs()}): a session runs it without reference substitution.
     */
    public PlannedCall withLiteralArgs() {
        return new PlannedCall(primitive, args, sync, timeoutMs, await, capture, true, accept, rungs, minRung);
    }

    /**
     * This call accepting the warning codes {@code codes} (phase8-contracts.md 7.3): a verb pre-accepts a code its
     * primitive documents as harmless; the compiler adds the step's own {@code accept} to every call of the step.
     *
     * @throws IllegalArgumentException if a code is not a warning code
     */
    public PlannedCall withAccept(Set<String> codes) {
        return new PlannedCall(primitive, args, sync, timeoutMs, await, capture, literalArgs, codes, rungs, minRung);
    }

    /**
     * This call with a rung ladder, best first (phase8-contracts.md 7.6): its primitive must report the rung that
     * acted ({@code RungResult}); the executor checks it.
     *
     * @throws IllegalArgumentException if the ladder has fewer than 2 or more than 8 rungs or a name twice, or the
     *     call's {@code minRung} is not one of them
     */
    public PlannedCall withRungs(List<Rung> ladder) {
        return new PlannedCall(primitive, args, sync, timeoutMs, await, capture, literalArgs, accept, ladder,
                minRung);
    }

    /**
     * This call requiring the rung {@code rung} or a better one (phase8-contracts.md 7.6); null requires none.
     *
     * @throws IllegalArgumentException if {@code rung} is not a rung of the call's ladder
     */
    public PlannedCall withMinRung(String rung) {
        return new PlannedCall(primitive, args, sync, timeoutMs, await, capture, literalArgs, accept, rungs, rung);
    }
}
