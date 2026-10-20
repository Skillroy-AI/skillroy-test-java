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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of one executed step (or of the {@code verify} block, reported with id {@code "verify"}). Steps after a
 * failure are not executed and have no result.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param id {@code s1}, {@code s2}, … or {@code verify}
 * @param verb the step's verb ({@code verify} for the verify block)
 * @param line source line in the YAML, 0 when unknown
 * @param status PASS, DEGRADED (a guarded expectation of this step was skipped, or one of its UI calls ran at a
 *     lower fidelity than requested), FAIL or ERROR
 * @param message the failure message, or null
 * @param skippedExpectations descriptions of the guarded expectations this step skipped
 * @param duration wall time of the step, as measured by the run's clock
 * @param degradedFidelity the degraded-fidelity entries of this step's calls (see
 *     {@link RunResult#degradedFidelity()}); empty when none
 * @param warnings the warnings this step's calls reported (phase8-contracts.md 7.1; see {@link RunResult#warnings()}),
 *     accepted or not; empty when none
 */
public record StepResult(String id, String verb, int line, Status status, String message,
                         List<String> skippedExpectations, Duration duration, List<String> degradedFidelity,
                         List<String> warnings) {

    /** Copies the lists; a null {@code degradedFidelity} or {@code warnings} is empty. */
    public StepResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        skippedExpectations = List.copyOf(skippedExpectations);
        degradedFidelity = degradedFidelity == null ? List.of() : List.copyOf(degradedFidelity);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** A step result without warnings (the shape before Phase 8). */
    public StepResult(String id, String verb, int line, Status status, String message,
                      List<String> skippedExpectations, Duration duration, List<String> degradedFidelity) {
        this(id, verb, line, status, message, skippedExpectations, duration, degradedFidelity, List.of());
    }

    /** A step result without degraded-fidelity entries (the shape before amendment A29). */
    public StepResult(String id, String verb, int line, Status status, String message,
                      List<String> skippedExpectations, Duration duration) {
        this(id, verb, line, status, message, skippedExpectations, duration, List.of());
    }
}
