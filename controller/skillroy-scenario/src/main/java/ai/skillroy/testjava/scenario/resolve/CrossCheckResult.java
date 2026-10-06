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
 * What a {@link CrossCheck} says about one oracle's output (phase3-contracts.md 5.1): whether the application's own
 * computation agrees, why, what the application computed and the evidence (the calls made and what the application
 * answered). A disagreement is how a check says "no"; it must not throw for that.
 *
 * <p>Built through {@link #agree} and {@link #disagree}. Immutable ({@code actual} and {@code evidence} are deep
 * unmodifiable copies with the keys of every map in sorted order, so that a pack's {@code Map.of} order never reaches
 * a run record); safe to share between threads.
 *
 * @param agreed true when the application computes what the oracle computed (within the tolerance, if any)
 * @param reason a short reason for reports and the approval screen, naming both values; never a secret
 * @param actual what the application computed, encoded (JSON-like) for the report
 * @param evidence JSON-like evidence, shown at approval and written to the run's outputs (masked), never to the
 *     bindings file
 */
public record CrossCheckResult(boolean agreed, String reason, Object actual, Map<String, Object> evidence) {

    /** Freezes {@code actual} and {@code evidence}, keys sorted at every depth; a null evidence is empty. */
    public CrossCheckResult {
        actual = Frozen.freezeSorted(actual);
        evidence = Frozen.freezeSortedMap(evidence);
    }

    /** The application computes what the oracle computed. */
    public static CrossCheckResult agree(String reason, Object actual, Map<String, Object> evidence) {
        return new CrossCheckResult(true, reason, actual, evidence);
    }

    /** The application computes something else: the bind fails as ORACLE_SUSPECT. */
    public static CrossCheckResult disagree(String reason, Object actual, Map<String, Object> evidence) {
        return new CrossCheckResult(false, reason, actual, evidence);
    }
}
