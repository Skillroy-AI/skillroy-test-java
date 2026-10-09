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

import java.util.Map;

/**
 * Receives the {@code run.jsonl} records of a bind (phase2-contracts.md 11.3). Types: {@code fingerprint},
 * {@code resolver} (one per {@code Resolver.candidates} call; never in {@code replay}), {@code verifier} (one per
 * {@code Verifier.verify} call), {@code resolve} (one per query slot), {@code oracle}, {@code crosscheck} (one per
 * oracle with a cross-check, every mode, phase3-contracts.md 8.2: {@code oracle, fn, check, tolerance}, then either
 * {@code skipped: true, capability, state, reason} or {@code agreed, reason, expected, actual, evidence}),
 * {@code guard}, {@code bind}; and, from Phase 5 (phase5-contracts.md 5.5), {@code inference}: once per bind in
 * {@code live}, {@code approve} and {@code re-resolve} when the contributors are loaded ({@code mode, gateway,
 * contributors, resolvers, loadError}), and in {@code replay} when the settings named a gateway that was replaced by
 * {@code none} ({@code mode, requested, gateway, reason}). A {@code resolver} record of a parked slot has
 * {@code parked: true} instead of a count, and the one of its resumption {@code resumed: true}; a {@code resolve}
 * record of a value a model found adds {@code confidence, approval, llm}, and one reused by {@code approve} adds
 * {@code reused: true, reusedFrom} (the recorded {@code approval.by}). From Phase 6 (phase6-contracts.md 10.2, an
 * additive change to P2 11.3) the {@code resolve}, {@code oracle} and {@code fingerprint} records end with
 * {@code durationMs}: the time since the slot started (its resolvers, verifiers and any model call; for a parked slot
 * the wait for the approval too), the oracle's evaluation, the fingerprint's computation, so that a bind span's events
 * show where model latency and SQL time went. The fields are not masked, except a
 * cross-check's evidence, actual and reason, which the engine masks with the credentials: the receiver masks what it
 * writes.
 *
 * <p>Called on the binding thread.
 */
@FunctionalInterface
public interface BindListener {

    /** A listener that ignores every record. */
    BindListener NONE = (type, fields) -> { };

    /** One record. */
    void event(String type, Map<String, Object> fields);
}
