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

import java.nio.file.Path;
import java.util.Objects;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * How {@link PlanExecutor} runs a plan: where to write outputs, the step timeout override, how DEGRADED is reported,
 * and the time seams used by tests.
 *
 * <p>Immutable; safe to share between threads if the clock, sleeper and probe are.
 *
 * @param outputDir directory for {@code run.jsonl}, {@code bindings.json}, {@code failure/} and {@code artifacts/};
 *     null writes nothing. Files of an earlier run in the same directory are replaced or removed, so use one
 *     directory per scenario
 * @param stepTimeout overrides the plan's {@code stepTimeoutMs} (how long expectations are retried); null keeps it
 * @param degradedAs how reports and the JUnit glue show a DEGRADED run; null means {@link DegradedAs#SKIPPED}
 * @param clock measures durations and deadlines and stamps {@code run.jsonl}; null means the system UTC clock
 * @param pollInterval pause between expectation attempts; null means 100 ms
 * @param sleeper performs that pause; null means {@code Thread.sleep}. Tests pass one that advances a fake clock
 * @param targetRestarted asked after the agent is lost: true classifies the run TARGET_RESTARTED (for example when a
 *     discovery file shows a new pid for the same main command), false or null AGENT_LOST; null means always
 *     AGENT_LOST
 * @param captureOnFailure whether to collect failure evidence (snapshot, capture, events, app state, dialogs)
 * @param bind how the run binds its slots (phase2-contracts.md 11.1); never null. The executor never reads system
 *     properties: only the JUnit glue's overloads without {@code RunOptions} do
 * @param expectationsOnMaskedValues whether expectations ({@code expect} steps and {@code verify}) and stored-row
 *     checks are evaluated on values as the run's secret mask leaves them, as an interactive session evaluates a
 *     check (phase5-contracts.md 12.2): a value the mask changes holds a secret and satisfies no matcher, its masked
 *     form is the reported actual, and the reason says so. For a plan a model or a client wrote (the MCP server's
 *     {@code skillroy_scenario_run}, {@code author --run}), whose PASS or FAIL would otherwise answer questions about a
 *     secret; false (the default) evaluates on the values as read, as for a scenario a person committed
 */
public record RunOptions(Path outputDir, Duration stepTimeout, DegradedAs degradedAs, Clock clock,
                         Duration pollInterval, Sleeper sleeper, Supplier<Boolean> targetRestarted,
                         boolean captureOnFailure, BindOptions bind, boolean expectationsOnMaskedValues) {

    /** How a DEGRADED run is reported (decision B14). */
    public enum DegradedAs {
        /** As skipped: neither green nor red (the default). */
        SKIPPED,
        /** As a failure. */
        FAILURE,
        /** As a pass. */
        PASS
    }

    /** Pauses the executor between expectation attempts; the only timed wait the executor itself performs. */
    @FunctionalInterface
    public interface Sleeper {
        /** Pauses for {@code duration}; may return early. */
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Duration DEFAULT_POLL = Duration.ofMillis(100);

    /**
     * Fills defaults for null seams.
     *
     * @throws IllegalArgumentException if {@code stepTimeout} or {@code pollInterval} is zero or negative
     * @throws NullPointerException if {@code bind} is null
     */
    public RunOptions {
        Objects.requireNonNull(bind, "bind");
        if (stepTimeout != null && (stepTimeout.isZero() || stepTimeout.isNegative())) {
            throw new IllegalArgumentException("stepTimeout must be positive: " + stepTimeout);
        }
        if (pollInterval != null && (pollInterval.isZero() || pollInterval.isNegative())) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        degradedAs = degradedAs == null ? DegradedAs.SKIPPED : degradedAs;
        clock = clock == null ? Clock.systemUTC() : clock;
        pollInterval = pollInterval == null ? DEFAULT_POLL : pollInterval;
        sleeper = sleeper == null ? duration -> Thread.sleep(Math.max(1L, duration.toMillis())) : sleeper;
    }

    /** Options that evaluate expectations on the values as read (the Phase 2 shape). */
    public RunOptions(Path outputDir, Duration stepTimeout, DegradedAs degradedAs, Clock clock, Duration pollInterval,
                      Sleeper sleeper, Supplier<Boolean> targetRestarted, boolean captureOnFailure, BindOptions bind) {
        this(outputDir, stepTimeout, degradedAs, clock, pollInterval, sleeper, targetRestarted, captureOnFailure, bind,
                false);
    }

    /** Options with {@link BindOptions#defaults()} (the Phase 1 shape). */
    public RunOptions(Path outputDir, Duration stepTimeout, DegradedAs degradedAs, Clock clock, Duration pollInterval,
                      Sleeper sleeper, Supplier<Boolean> targetRestarted, boolean captureOnFailure) {
        this(outputDir, stepTimeout, degradedAs, clock, pollInterval, sleeper, targetRestarted, captureOnFailure,
                BindOptions.defaults(), false);
    }

    /**
     * No output directory, the plan's step timeout, DEGRADED as skipped, system clock, 100 ms polls, evidence on,
     * {@link BindOptions#defaults()}, expectations on the values as read.
     */
    public static RunOptions defaults() {
        return new RunOptions(null, null, null, null, null, null, null, true, BindOptions.defaults(), false);
    }

    /** A copy with the given bind options (never null). */
    public RunOptions withBind(BindOptions newBind) {
        return new RunOptions(outputDir, stepTimeout, degradedAs, clock, pollInterval, sleeper, targetRestarted,
                captureOnFailure, newBind, expectationsOnMaskedValues);
    }

    /** A copy with the given output directory (null writes nothing). */
    public RunOptions withOutputDir(Path dir) {
        return new RunOptions(dir, stepTimeout, degradedAs, clock, pollInterval, sleeper, targetRestarted,
                captureOnFailure, bind, expectationsOnMaskedValues);
    }

    /** A copy with the given step timeout override (null keeps the plan's). */
    public RunOptions withStepTimeout(Duration timeout) {
        return new RunOptions(outputDir, timeout, degradedAs, clock, pollInterval, sleeper, targetRestarted,
                captureOnFailure, bind, expectationsOnMaskedValues);
    }

    /** A copy with the given DEGRADED mapping. */
    public RunOptions withDegradedAs(DegradedAs mapping) {
        return new RunOptions(outputDir, stepTimeout, mapping, clock, pollInterval, sleeper, targetRestarted,
                captureOnFailure, bind, expectationsOnMaskedValues);
    }

    /** A copy with the given clock. */
    public RunOptions withClock(Clock newClock) {
        return new RunOptions(outputDir, stepTimeout, degradedAs, newClock, pollInterval, sleeper, targetRestarted,
                captureOnFailure, bind, expectationsOnMaskedValues);
    }

    /** A copy with the given poll interval. */
    public RunOptions withPollInterval(Duration interval) {
        return new RunOptions(outputDir, stepTimeout, degradedAs, clock, interval, sleeper, targetRestarted,
                captureOnFailure, bind, expectationsOnMaskedValues);
    }

    /** A copy with the given sleeper. */
    public RunOptions withSleeper(Sleeper newSleeper) {
        return new RunOptions(outputDir, stepTimeout, degradedAs, clock, pollInterval, newSleeper, targetRestarted,
                captureOnFailure, bind, expectationsOnMaskedValues);
    }

    /** A copy with the given restart probe. */
    public RunOptions withTargetRestarted(Supplier<Boolean> probe) {
        return new RunOptions(outputDir, stepTimeout, degradedAs, clock, pollInterval, sleeper, probe,
                captureOnFailure, bind, expectationsOnMaskedValues);
    }

    /** A copy with evidence collection switched on or off. */
    public RunOptions withCaptureOnFailure(boolean on) {
        return new RunOptions(outputDir, stepTimeout, degradedAs, clock, pollInterval, sleeper, targetRestarted, on,
                bind, expectationsOnMaskedValues);
    }

    /**
     * A copy that evaluates expectations and stored-row checks on masked values (true) or on the values as read
     * (false, the default); see {@link #expectationsOnMaskedValues()}.
     */
    public RunOptions withExpectationsOnMaskedValues(boolean on) {
        return new RunOptions(outputDir, stepTimeout, degradedAs, clock, pollInterval, sleeper, targetRestarted,
                captureOnFailure, bind, on);
    }
}
