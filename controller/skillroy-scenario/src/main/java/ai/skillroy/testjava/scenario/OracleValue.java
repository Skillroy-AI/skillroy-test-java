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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An oracle's output in one bind: what {@code $oracle.<name>} resolves to, recorded in the bindings file and shown at
 * approval with its guard (phase2-contracts.md 6.6, 9) and, from Phase 3, the cross-check it was accepted under
 * (phase3-contracts.md 8.3).
 *
 * <p>Immutable (values are deep unmodifiable copies); safe to share between threads.
 *
 * @param name the oracle's name in the scenario
 * @param fn the pack's oracle function
 * @param inputsHash {@code "sha256:"} over the canonical JSON of the arguments after reference substitution
 * @param value the encoded output (may be null)
 * @param mustEqual the guard with references substituted, or null when the oracle has none
 * @param crossCheck the plan's cross-check of the oracle, or null when it has none; informational in the bindings
 *     file ({@code replay} recomputes the oracle and re-runs the check, and does not compare it)
 */
public record OracleValue(String name, String fn, String inputsHash, Object value, Object mustEqual,
                          Plan.CrossCheckSpec crossCheck) {

    /** Freezes the values. */
    public OracleValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fn, "fn");
        value = Plan.freeze(value);
        mustEqual = Plan.freeze(mustEqual);
    }

    /** A value without a cross-check (the Phase 2 shape). */
    public OracleValue(String name, String fn, String inputsHash, Object value, Object mustEqual) {
        this(name, fn, inputsHash, value, mustEqual, null);
    }

    /**
     * {@code {fn, inputsHash, value, mustEqual?, crossCheck?}}: the bindings file's shape (the name is the map key);
     * {@code crossCheck} is {@code {check, tolerance?, capability?}}, written after {@code mustEqual}.
     */
    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("fn", fn);
        json.put("inputsHash", inputsHash);
        json.put("value", value);
        if (mustEqual != null) {
            json.put("mustEqual", mustEqual);
        }
        if (crossCheck != null) {
            json.put("crossCheck", crossCheck.toJson());
        }
        return json;
    }
}
