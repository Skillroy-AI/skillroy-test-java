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

import java.util.List;
import java.util.Objects;

/**
 * The accepted answer to one query, with everything needed to review it. Built by the engine, never by a pack: a
 * resolver returns candidates, and only a candidate every verifier passed becomes a resolution (decision D3). This is
 * what a bindings file records per query slot, what {@code replay} compares and what a reference substitutes.
 *
 * <p>Immutable (the value is a deep unmodifiable copy); safe to share between threads.
 *
 * @param slot the slot name
 * @param query the query kind
 * @param args the query's arguments after reference substitution (JSON-like, as the plan holds them)
 * @param argsHash {@code "sha256:"} over the canonical JSON of {@code args}
 * @param value the encoded value, without secret fields; never null
 * @param strategy the strategy of the resolver that answered ({@link Strategy#REPLAY} only in a run's own outputs)
 * @param provenance how the value was found
 * @param verified the verifier results for the accepted value, in plan order
 * @param confidence the accepted candidate's confidence; 1.0 for tier (a)
 * @param credential the credentials entry the value is joined to, or null
 * @param approval the approval state
 * @param usage the model usage behind the value; null in Phase 2
 */
public record Resolution(String slot, String query, Object args, String argsHash, Object value, Strategy strategy,
                         Provenance provenance, List<VerifierResult> verified, double confidence,
                         CredentialRef credential, Approval approval, LlmUsage usage) {

    /** Freezes args and value and copies the verifier list. */
    public Resolution {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(strategy, "strategy");
        args = Frozen.freeze(args);
        value = Frozen.freeze(value);
        verified = verified == null ? List.of() : List.copyOf(verified);
    }

    /** This resolution with another approval (the binder's step after the approver answered). */
    public Resolution withApproval(Approval newApproval) {
        return new Resolution(slot, query, args, argsHash, value, strategy, provenance, verified, confidence,
                credential, newApproval, usage);
    }
}
