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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.pack.PlannedCall;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one step of an interactive session did (phase5-contracts.md 12.2): the step protocol's classification instead
 * of an exception. A step stops at its first call that does not pass; {@code executed} counts the calls that were
 * sent.
 *
 * <p>Immutable (lists copied, the detail frozen); safe to share between threads.
 *
 * @param stepId the session's step id, {@code s1, s2, …}
 * @param verb the verb, or null for a raw call
 * @param calls the step's calls as compiled (a raw call's own), in order
 * @param executed how many of them were sent
 * @param status PASS, DEGRADED (a call ran at a lower fidelity than requested), FAIL or ERROR
 * @param failureKind why it did not pass, or null
 * @param message one line, or null when it passed
 * @param detail the classification's detail, masked; empty when it passed (a captured slot's values are under
 *     {@code captured})
 * @param lastResult the result of the last call that returned one, masked; null when none did
 * @param events the events those calls published, masked, in order
 * @param seqBefore the agent's sequence number before the step
 * @param seqAfter the highest agent sequence number seen when it ended
 * @param degradedFidelity the calls that ran at a lower fidelity than requested
 * @param duration how long the step took
 */
public record StepOutcome(String stepId, String verb, List<PlannedCall> calls, int executed, Status status,
                          FailureKind failureKind, String message, Map<String, Object> detail, RpcResult lastResult,
                          List<Event> events, long seqBefore, long seqAfter, List<String> degradedFidelity,
                          Duration duration) {

    /** Copies the lists and freezes the detail. */
    @SuppressWarnings("unchecked")
    public StepOutcome {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(status, "status");
        calls = calls == null ? List.of() : List.copyOf(calls);
        detail = detail == null ? Map.of() : (Map<String, Object>) Plan.freeze(detail);
        events = events == null ? List.of() : List.copyOf(events);
        degradedFidelity = degradedFidelity == null ? List.of() : List.copyOf(degradedFidelity);
        duration = duration == null ? Duration.ZERO : duration;
    }

    /** True for PASS and DEGRADED: the step did what it was asked. */
    public boolean succeeded() {
        return status == Status.PASS || status == Status.DEGRADED;
    }
}
