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

import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The outcome of one scenario run.
 *
 * <p>DEGRADED = every evaluated expectation held, but at least one guarded expectation was skipped, or at least one
 * UI call ran at a lower fidelity than requested (a COMPONENT click that fell back to ACTION, amendment A29), or at
 * least one guarded oracle cross-check was skipped because its capability was not OK (phase3-contracts.md 6.2; its
 * line is in {@code skippedExpectations}, see {@link #skippedCrossChecks()}).
 *
 * <p>Secrets (credentials, joined fields, fixture secrets) are masked in every string of the result (amendment A25,
 * phase2-contracts.md 11.4). REBIND_NEEDED and RESOLUTION_FAILED carry {@code reason}, {@code slot} (or
 * {@code oracle}), {@code mode}, {@code envId} and the reason's own fields in {@code detail}.
 *
 * <p>{@code detail} holds the evidence of a failure: for ASSERTION {@code path}, {@code matcher}, {@code actual},
 * {@code reason} (or the rejected call and its error); for SYNC_TIMEOUT the wait plus {@code edtState},
 * {@code dialogs} and {@code appState}; for PRECONDITION_FAILED the {@code unmet} ids or the {@code property},
 * {@code expected} and {@code actual}; and, when evidence was collected, {@code snapshot}: the UI as indented text
 * ({@link UiSnapshotText}).
 *
 * <p>Immutable (detail is a deep unmodifiable copy); safe to share between threads.
 *
 * @param scenarioId the scenario id
 * @param status PASS, DEGRADED, FAIL or ERROR
 * @param failureKind why the run did not pass; null for PASS and DEGRADED
 * @param failedStepId the step that failed ({@code s3}, {@code verify}), or null when no step was running
 * @param message one-line failure message, or null
 * @param detail JSON-like failure evidence; empty when none
 * @param steps the executed steps in order
 * @param skippedExpectations descriptions of every skipped guarded expectation, with the capability's state, and of
 *     every guarded oracle cross-check the bind skipped ({@code "oracle waiver: cross-check C skipped: capability K
 *     is UNAVAILABLE (…)"})
 * @param duration wall time of the run
 * @param degradedFidelity one entry per UI call that ran at a lower fidelity than requested, for example
 *     {@code "s5: click fell back from COMPONENT to ACTION (FOCUS_FAILED: …)"}; empty when none
 * @param mode the bind mode of the run (phase2-contracts.md 11.1); null for a result built without one
 * @param envId the environment id of the run; null for a result built without one
 * @param modelCalls the cost ledger entries recorded during the run; always empty in Phase 2
 * @param warnings one line per warning a step call reported (phase8-contracts.md 7.1), accepted or not, for example
 *     {@code "s2: scan: lending.input VALUE_COERCED (the desk took 4005)"}; empty when none. An accepted warning leaves
 *     the verdict alone; an unaccepted one failed its step
 */
public record RunResult(String scenarioId, Status status, FailureKind failureKind, String failedStepId,
                        String message, Map<String, Object> detail, List<StepResult> steps,
                        List<String> skippedExpectations, Duration duration, List<String> degradedFidelity,
                        BindMode mode, String envId, List<CostLedger.Entry> modelCalls, List<String> warnings) {

    /** Copies the collections; a null {@code degradedFidelity}, {@code modelCalls} or {@code warnings} is empty. */
    public RunResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(status, "status");
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) Plan.freeze(detail == null ? Map.of() : detail);
        detail = frozen;
        steps = List.copyOf(steps);
        skippedExpectations = List.copyOf(skippedExpectations);
        degradedFidelity = degradedFidelity == null ? List.of() : List.copyOf(degradedFidelity);
    }

    /** A result without warnings (the shape before Phase 8). */
    public RunResult(String scenarioId, Status status, FailureKind failureKind, String failedStepId, String message,
                     Map<String, Object> detail, List<StepResult> steps, List<String> skippedExpectations,
                     Duration duration, List<String> degradedFidelity, BindMode mode, String envId,
                     List<CostLedger.Entry> modelCalls) {
        this(scenarioId, status, failureKind, failedStepId, message, detail, steps, skippedExpectations, duration,
                degradedFidelity, mode, envId, modelCalls, List.of());
    }

    /** A result without the Phase 2 components (the shape of amendment A29). */
    public RunResult(String scenarioId, Status status, FailureKind failureKind, String failedStepId, String message,
                     Map<String, Object> detail, List<StepResult> steps, List<String> skippedExpectations,
                     Duration duration, List<String> degradedFidelity) {
        this(scenarioId, status, failureKind, failedStepId, message, detail, steps, skippedExpectations, duration,
                degradedFidelity, null, null, List.of());
    }

    /** A result without degraded-fidelity entries (the shape before amendment A29). */
    public RunResult(String scenarioId, Status status, FailureKind failureKind, String failedStepId, String message,
                     Map<String, Object> detail, List<StepResult> steps, List<String> skippedExpectations,
                     Duration duration) {
        this(scenarioId, status, failureKind, failedStepId, message, detail, steps, skippedExpectations, duration,
                List.of());
    }

    /**
     * The lines of {@link #skippedExpectations()} that are guarded oracle cross-checks the bind skipped
     * (phase3-contracts.md 6.2), in order: those of the form {@code "oracle <name>: cross-check <id> skipped: …"}.
     */
    public List<String> skippedCrossChecks() {
        List<String> out = new ArrayList<>();
        for (String line : skippedExpectations) {
            if (isSkippedCrossCheck(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** The lines of {@link #skippedExpectations()} that are skipped guarded expectations and db checks. */
    public List<String> skippedChecks() {
        List<String> out = new ArrayList<>();
        for (String line : skippedExpectations) {
            if (!isSkippedCrossCheck(line)) {
                out.add(line);
            }
        }
        return out;
    }

    private static final Pattern SKIPPED_CROSS_CHECK = Pattern.compile(
            "oracle [A-Za-z_][A-Za-z0-9_]*: cross-check \\S+ skipped: .*", Pattern.DOTALL);

    /** True for a line of the form {@code "oracle <name>: cross-check <id> skipped: …"}. */
    static boolean isSkippedCrossCheck(String line) {
        return line != null && SKIPPED_CROSS_CHECK.matcher(line).matches();
    }

    /**
     * Why a DEGRADED result is degraded, for reports: {@code "skipped expectations: …"}, {@code "skipped
     * cross-checks: …"} and/or {@code "degraded fidelity: …"}, joined with {@code "; "}; empty when none applies.
     */
    public String degradedSummary() {
        StringBuilder text = new StringBuilder();
        List<String> checks = skippedChecks();
        if (!checks.isEmpty()) {
            text.append("skipped expectations: ").append(String.join("; ", checks));
        }
        List<String> crossChecks = skippedCrossChecks();
        if (!crossChecks.isEmpty()) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append("skipped cross-checks: ").append(String.join("; ", crossChecks));
        }
        if (!degradedFidelity.isEmpty()) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append("degraded fidelity: ").append(String.join("; ", degradedFidelity));
        }
        return text.toString();
    }
}
