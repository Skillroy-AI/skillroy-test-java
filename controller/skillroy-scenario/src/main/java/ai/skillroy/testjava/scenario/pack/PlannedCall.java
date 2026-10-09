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
import java.util.Map;

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
 */
public record PlannedCall(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                          CaptureSpec capture, boolean literalArgs) {

    /**
     * @throws IllegalArgumentException if {@code primitive} is null or blank, {@code sync} is {@link Sync#EVENT}
     *     without an {@code await}, {@code await} is given with another sync, or {@code capture} is given on a call
     *     that neither has sync {@link Sync#EVENT} nor is {@code sync.await}
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
        return new PlannedCall(primitive, args, sync, timeoutMs, await, newCapture, literalArgs);
    }

    /**
     * This call with literal arguments ({@link #literalArgs()}): a session runs it without reference substitution.
     */
    public PlannedCall withLiteralArgs() {
        return new PlannedCall(primitive, args, sync, timeoutMs, await, capture, true);
    }
}
