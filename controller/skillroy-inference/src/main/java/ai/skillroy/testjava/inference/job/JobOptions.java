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

package ai.skillroy.testjava.inference.job;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How one {@code re-resolve} job runs (phase5-contracts.md 8.2): where its report and ledger go, its id, the job
 * budget, whether it is a dry run, the approval label, and how long it waits for a batch.
 *
 * <p>Immutable; safe to share between threads if the clock is.
 *
 * @param outDir where {@link ReResolver#run} writes {@code re-resolve-report.md} and {@code .json},
 *     {@code cost-ledger.jsonl} and {@code inference.jsonl}; null writes nothing (the report is still returned, and
 *     {@link ReResolveReport#writeTo} writes it); the CLI's default is {@code run-output/re-resolve-<jobId>/}
 * @param jobId the job's id, {@code [A-Za-z0-9][A-Za-z0-9._-]*}, at most 100 characters; part of the approval label
 * @param jobBudgetUsd the job budget ({@code budget.jobUsd}, {@code --budget-job}); there is no built-in value (B6),
 *     so a null budget makes {@link ReResolver#run} refuse to start
 * @param dryRun true writes no bindings file and reports what would change
 * @param approvedBy the label written as {@code approval.by}; null means {@code re-resolve:<jobId>}
 * @param batchWait the longest wait for one batch, from its submission; null means the settings'
 *     {@code timeouts.batchWaitMs} (default 2 h)
 * @param batchPoll the interval between two status reads of a batch; zero reads again at once (tests); null means
 *     the settings' {@code timeouts.batchPollMs} (default 30 s)
 * @param clock what the batch wait is measured on and the report's times are read from; null means the system UTC
 *     clock
 */
public record JobOptions(Path outDir, String jobId, BigDecimal jobBudgetUsd, boolean dryRun, String approvedBy,
                         Duration batchWait, Duration batchPoll, Clock clock) {

    /** The label prefix of a file the job approves. */
    public static final String APPROVAL_PREFIX = "re-resolve:";

    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * Checks the id and the durations; fills the clock.
     *
     * @throws IllegalArgumentException for an id that does not match, a negative budget, or a negative wait or poll
     */
    public JobOptions {
        Objects.requireNonNull(jobId, "jobId");
        if (!JOB_ID.matcher(jobId).matches()) {
            throw new IllegalArgumentException("a job id matches [A-Za-z0-9][A-Za-z0-9._-]*, at most 100 characters,"
                    + " found \"" + jobId + "\"");
        }
        if (jobBudgetUsd != null && jobBudgetUsd.signum() < 0) {
            throw new IllegalArgumentException("a job budget is not negative");
        }
        if (batchWait != null && batchWait.isNegative()) {
            throw new IllegalArgumentException("batchWait is not negative");
        }
        if (batchPoll != null && batchPoll.isNegative()) {
            throw new IllegalArgumentException("batchPoll is not negative");
        }
        if (approvedBy != null && approvedBy.isBlank()) {
            approvedBy = null;
        }
        clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** A job writing into {@code outDir} with this budget, not a dry run, the default label, waits and clock. */
    public static JobOptions of(Path outDir, String jobId, BigDecimal jobBudgetUsd) {
        return new JobOptions(outDir, jobId, jobBudgetUsd, false, null, null, null, null);
    }

    /** {@link #approvedBy()}, or {@code re-resolve:<jobId>}. */
    public String approvalLabel() {
        return approvedBy != null ? approvedBy : APPROVAL_PREFIX + jobId;
    }

    /** A copy that is or is not a dry run. */
    public JobOptions withDryRun(boolean on) {
        return new JobOptions(outDir, jobId, jobBudgetUsd, on, approvedBy, batchWait, batchPoll, clock);
    }

    /** A copy with another approval label (null: {@code re-resolve:<jobId>}). */
    public JobOptions withApprovedBy(String label) {
        return new JobOptions(outDir, jobId, jobBudgetUsd, dryRun, label, batchWait, batchPoll, clock);
    }

    /** A copy with another batch wait and poll interval (null: the settings'). */
    public JobOptions withBatchTimes(Duration wait, Duration poll) {
        return new JobOptions(outDir, jobId, jobBudgetUsd, dryRun, approvedBy, wait, poll, clock);
    }

    /** A copy with another clock. */
    public JobOptions withClock(Clock newClock) {
        return new JobOptions(outDir, jobId, jobBudgetUsd, dryRun, approvedBy, batchWait, batchPoll, newClock);
    }

    /** A copy with another output directory (null: none). */
    public JobOptions withOutDir(Path dir) {
        return new JobOptions(dir, jobId, jobBudgetUsd, dryRun, approvedBy, batchWait, batchPoll, clock);
    }
}
