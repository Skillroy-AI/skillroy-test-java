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

package ai.skillroy.testjava.inference.triage;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a triage of one run directory did (phase5-contracts.md 11.2).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param runDir the run's output directory
 * @param outcome triaged, nothing to triage, or failed
 * @param scenarioId the run's scenario, or null when the run did not say
 * @param runStatus the run's status ({@code FAIL}, {@code ERROR}, {@code PASS}, …), or null
 * @param failureKind the run's failure kind, or null
 * @param label the label, or null when none was made
 * @param summary the summary, or null ({@code --labels-only}, or none was made)
 * @param models the model ids that answered, in call order
 * @param requestIds the providers' request ids, in call order
 * @param costUsd what the triage's model calls cost
 * @param message one line: why there was nothing to triage, or why the triage failed; null when it was triaged
 * @param json {@code failure/triage.json}, or null when it was not written
 * @param markdown {@code failure/triage.md}, or null when it was not written
 */
public record TriageResult(Path runDir, Outcome outcome, String scenarioId, String runStatus, String failureKind,
                           TriageLabel label, TriageSummary summary, List<String> models, List<String> requestIds,
                           BigDecimal costUsd, String message, Path json, Path markdown) {

    /** How a triage of one directory ended. */
    public enum Outcome {
        /** The label (and the summary) were written. */
        TRIAGED,
        /** The run passed (or was degraded), or did not finish: "nothing to triage". */
        NOTHING_TO_TRIAGE,
        /** The gateway failed for this directory (the command's exit code 1). */
        FAILED
    }

    /** Copies the lists; a null cost is zero. */
    public TriageResult {
        Objects.requireNonNull(runDir, "runDir");
        Objects.requireNonNull(outcome, "outcome");
        models = models == null ? List.of() : List.copyOf(models);
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    }

    /** 1 when the gateway failed for this directory, else 0. */
    public int exitCode() {
        return outcome == Outcome.FAILED ? 1 : 0;
    }
}
