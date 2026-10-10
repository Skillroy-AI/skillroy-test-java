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

import java.nio.file.Path;
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
     * The decision for one image baseline candidate, asked at its {@code expectImage} step in {@code approve}
     * (phase7-contracts.md 6.7, 8.7, decision D355), after the candidate was written to its file so that the person
     * can look at it. The default delegates to {@link #review} with a request that names the scenario and the
     * environment and holds no resolution (its fingerprint is null), so that {@link #APPROVE_ALL} approves and
     * {@link #REJECT_ALL} rejects; the CLI's terminal approver overrides it to print the candidate's path and size and
     * ask. An anchor is never approved here: it is recorded by a session.
     */
    default Decision reviewImage(ImageApproval request) {
        return review(new ApprovalRequest(request.scenarioId(), request.envId(), null, List.of(), List.of(), null));
    }

    /**
     * One image baseline candidate shown for approval (phase7-contracts.md 8.7).
     *
     * @param scenarioId the scenario
     * @param envId the environment id
     * @param label the baseline's label
     * @param renderKey the render key the candidate was captured with
     * @param candidate the candidate's PNG file under the run's {@code candidates/}, or null when the run has no output
     *     directory
     * @param width the candidate's width
     * @param height the candidate's height
     * @param existing the baseline the file holds for this label under another render key or this one, or null
     */
    record ImageApproval(String scenarioId, String envId, String label, String renderKey, Path candidate, int width,
                         int height, BindingsFile.ImageEntry existing) {
    }

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
