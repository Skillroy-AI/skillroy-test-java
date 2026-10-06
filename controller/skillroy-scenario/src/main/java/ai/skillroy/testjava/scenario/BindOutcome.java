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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The result of a full bind (phase2-contracts.md 6.6, phase3-contracts.md 5.3).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param bindings the run's bindings: slot values with the credential join, oracles, the mask of joined secrets
 * @param file the bindings file that was read ({@code replay}), written ({@code approve}, {@code live
 *     --write-bindings}) or, in {@code live} without writing, would be written ({@code resolve --dry-run} prints
 *     it); null for a plan that needs no bindings
 * @param env the environment fingerprint; null for a plan that needs no bindings
 * @param written the file written, or null when nothing was written (also when an identical file was left untouched)
 * @param skipped one line per guarded oracle cross-check that was skipped because its capability was not OK
 *     ({@code "oracle waiver: cross-check C skipped: capability K is UNAVAILABLE (…)"}); the executor adds them to
 *     {@code RunResult.skippedExpectations}, so that the run is DEGRADED at best (gap 26, decision D38). Never null
 */
public record BindOutcome(Bindings bindings, BindingsFile file, EnvFingerprint env, Path written,
                          List<String> skipped) {

    /** @throws NullPointerException if the bindings are null */
    public BindOutcome {
        Objects.requireNonNull(bindings, "bindings");
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /** An outcome without skipped cross-checks (the Phase 2 shape). */
    public BindOutcome(Bindings bindings, BindingsFile file, EnvFingerprint env, Path written) {
        this(bindings, file, env, written, List.of());
    }
}
