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

package ai.skillroy.testjava.scenario.resolve;

import java.util.Map;

/**
 * What a {@link Verifier} says about one candidate: whether the application itself agrees that it answers the query,
 * why, and the evidence (the calls made and what the application answered). A failed result is how a verifier says
 * "no"; it must not throw for that.
 *
 * <p>Built through {@link #pass} and {@link #fail}; the engine fills in the verifier id. Immutable (evidence is a
 * deep unmodifiable copy, the keys of every map in sorted order); safe to share between threads.
 *
 * @param verifierId the verifier's id, filled in by the engine
 * @param passed true if the application accepts the candidate
 * @param reason a short reason for reports and the bindings file; never a secret
 * @param evidence JSON-like evidence, shown at approval and written to the run's outputs (masked), never to the
 *     bindings file
 */
public record VerifierResult(String verifierId, boolean passed, String reason, Map<String, Object> evidence) {

    /** Freezes the evidence, keys sorted at every depth; null is empty. */
    public VerifierResult {
        evidence = Frozen.freezeSortedMap(evidence);
    }

    /** The application accepts the candidate. */
    public static VerifierResult pass(String reason, Map<String, Object> evidence) {
        return new VerifierResult(null, true, reason, evidence);
    }

    /** The application refuses the candidate, for the given reason. */
    public static VerifierResult fail(String reason, Map<String, Object> evidence) {
        return new VerifierResult(null, false, reason, evidence);
    }

    /** This result with the verifier id set (the engine's step). */
    public VerifierResult withVerifierId(String id) {
        return new VerifierResult(id, passed, reason, evidence);
    }
}
