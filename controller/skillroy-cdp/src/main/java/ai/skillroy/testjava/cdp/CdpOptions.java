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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * How the CDP path probes and attaches (phase4-contracts.md section 5.1).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param probeDeadlinePerTarget how long one page target has to answer the probe's {@code Runtime.evaluate("1")}
 *     (default 2 s; at least 1 ms, at most {@link #MAX_PROBE_DEADLINE}); also bounds each {@code /json} request
 * @param connectDeadline the external deadline of {@code connectOverCDP}, including the start of the Playwright
 *     driver (default 30 s; at least 1 ms, at most {@link #MAX_CONNECT_DEADLINE})
 * @param noDefaults Playwright's {@code noDefaults}: the attached context keeps the application's own viewport and
 *     settings (default true)
 * @param isLocal Playwright's {@code isLocal}: the browser shares this host's file system (default true)
 * @param artifactsDir Playwright's artifacts directory: where it records a trace while tracing runs (relative trace
 *     paths resolve against it); null lets Playwright use a temporary directory of its own, which it deletes when it
 *     disconnects. Playwright appends the live trace (page snapshots with the values of input fields, console text)
 *     to files there and never deletes a directory it was given, so this must be a private directory its owner
 *     deletes, never a run's output directory: {@link CdpWebPath} gives it a private temporary one per open path
 * @param frozenTargets what to do with a page target that does not answer the probe (default {@code FAIL}); applied
 *     by {@link CdpWebPath}, which can ask the agent to reload; {@link CdpAttach#connect} refuses a probe with a
 *     frozen target whatever the policy
 */
public record CdpOptions(Duration probeDeadlinePerTarget, Duration connectDeadline, boolean noDefaults,
                         boolean isLocal, Path artifactsDir, FrozenTargetPolicy frozenTargets) {

    /** The longest per-target probe deadline accepted. */
    public static final Duration MAX_PROBE_DEADLINE = Duration.ofSeconds(60);

    /** The longest connect deadline accepted. */
    public static final Duration MAX_CONNECT_DEADLINE = Duration.ofMinutes(10);

    private static final CdpOptions DEFAULTS = new CdpOptions(Duration.ofSeconds(2), Duration.ofSeconds(30), true,
            true, null, FrozenTargetPolicy.FAIL);

    /**
     * @throws IllegalArgumentException if a deadline is null, shorter than 1 ms or longer than its maximum, or the
     *     policy is null
     */
    public CdpOptions {
        requireDeadline("probeDeadlinePerTarget", probeDeadlinePerTarget, MAX_PROBE_DEADLINE);
        requireDeadline("connectDeadline", connectDeadline, MAX_CONNECT_DEADLINE);
        if (frozenTargets == null) {
            throw new IllegalArgumentException("frozenTargets is required");
        }
    }

    /** 2 s per target, 30 s to connect, {@code noDefaults} and {@code isLocal}, no artifacts directory, FAIL. */
    public static CdpOptions defaults() {
        return DEFAULTS;
    }

    /** A copy with the given per-target probe deadline. */
    public CdpOptions withProbeDeadlinePerTarget(Duration deadline) {
        return new CdpOptions(deadline, connectDeadline, noDefaults, isLocal, artifactsDir, frozenTargets);
    }

    /** A copy with the given connect deadline. */
    public CdpOptions withConnectDeadline(Duration deadline) {
        return new CdpOptions(probeDeadlinePerTarget, deadline, noDefaults, isLocal, artifactsDir, frozenTargets);
    }

    /** A copy with the given {@code noDefaults}. */
    public CdpOptions withNoDefaults(boolean on) {
        return new CdpOptions(probeDeadlinePerTarget, connectDeadline, on, isLocal, artifactsDir, frozenTargets);
    }

    /** A copy with the given {@code isLocal}. */
    public CdpOptions withIsLocal(boolean on) {
        return new CdpOptions(probeDeadlinePerTarget, connectDeadline, noDefaults, on, artifactsDir, frozenTargets);
    }

    /** A copy with the given artifacts directory (null for none). */
    public CdpOptions withArtifactsDir(Path dir) {
        return new CdpOptions(probeDeadlinePerTarget, connectDeadline, noDefaults, isLocal, dir, frozenTargets);
    }

    /** A copy with the given frozen-target policy. */
    public CdpOptions withFrozenTargets(FrozenTargetPolicy policy) {
        return new CdpOptions(probeDeadlinePerTarget, connectDeadline, noDefaults, isLocal, artifactsDir, policy);
    }

    private static void requireDeadline(String name, Duration value, Duration max) {
        Objects.requireNonNull(max);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.isNegative() || value.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(name + " must be at least 1 ms: " + value);
        }
        if (value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be at most " + max.toSeconds() + " s: " + value);
        }
    }
}
