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

import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.Resolution;

import java.util.List;

/**
 * Asked once per bind in {@code approve} mode, with every resolution and oracle of the scenario
 * (phase2-contracts.md 8.3). The CLI asks on a terminal ({@code --yes} approves without asking); the JUnit glue never
 * prompts: it approves everything with {@code skillroy.approve=all}, or uses an approver passed in code. The time an
 * approver takes is not counted against the bind budget.
 *
 * <p>Called on the executor's thread; implementations need not be thread-safe unless shared.
 */
@FunctionalInterface
public interface Approver {

    /** Approves everything without asking ({@code --yes}, {@code skillroy.approve=all}). */
    Approver APPROVE_ALL = request -> new Decision(true, null, "approved without asking");

    /** Approves nothing: an {@code approve} bind without an approver fails as RESOLUTION_FAILED / NOT_APPROVED. */
    Approver REJECT_ALL = request -> new Decision(false, null, "no approver: approve needs a terminal, --yes,"
            + " skillroy.approve=all or an Approver in code");

    /** The decision for this scenario's bind. */
    Decision review(ApprovalRequest request);

    /**
     * What is shown for approval.
     *
     * @param scenarioId the scenario
     * @param envId the environment id
     * @param env the environment fingerprint
     * @param resolutions every resolution, with provenance (statements, calls, masked row samples) and verifier
     *     evidence
     * @param oracles every oracle value with its guard
     * @param againstExisting the difference from the existing bindings file; null when no file exists
     * @param crossChecks the outcome of every oracle's cross-check, in evaluation order (phase3-contracts.md 8.3: the
     *     approval screen prints each with its oracle); empty when no oracle has one
     */
    record ApprovalRequest(String scenarioId, String envId, EnvFingerprint env, List<Resolution> resolutions,
                           List<OracleValue> oracles, BindingsDiff againstExisting,
                           List<CrossCheckOutcome> crossChecks) {

        /** Copies the lists; a null {@code crossChecks} is empty. */
        public ApprovalRequest {
            resolutions = List.copyOf(resolutions);
            oracles = List.copyOf(oracles);
            crossChecks = crossChecks == null ? List.of() : List.copyOf(crossChecks);
        }

        /** A request without cross-check outcomes (the Phase 2 shape). */
        public ApprovalRequest(String scenarioId, String envId, EnvFingerprint env, List<Resolution> resolutions,
                               List<OracleValue> oracles, BindingsDiff againstExisting) {
            this(scenarioId, envId, env, resolutions, oracles, againstExisting, List.of());
        }
    }

    /**
     * The approver's answer.
     *
     * @param approved true to write the bindings file and run
     * @param by a label for {@code approval.by} (used when no {@code --approved-by} is given), or null
     * @param reason a short reason, reported when not approved
     */
    record Decision(boolean approved, String by, String reason) {
    }
}
