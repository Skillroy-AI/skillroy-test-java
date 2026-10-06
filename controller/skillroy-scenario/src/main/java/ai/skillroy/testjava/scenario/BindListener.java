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
 * {@code guard}, {@code bind}. The fields are not masked, except a cross-check's evidence, actual and reason, which the
 * engine masks with the credentials: the receiver masks what it writes.
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
