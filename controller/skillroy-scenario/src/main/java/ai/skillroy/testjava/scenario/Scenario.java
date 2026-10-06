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

import ai.skillroy.testjava.scenario.resolve.CredentialSpec;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scenario file as read by {@link ScenarioLoader}: the keys of the YAML, validated for shape, with source lines.
 * Verbs and expectations are not compiled yet and {@code $slot} references are left intact; {@link PlanCompiler}
 * turns this into a {@link Plan}.
 *
 * <p>Immutable lists and maps; the values inside {@link Step#args()} and {@link Slot#value()} are plain JSON-like data
 * owned by this object and must not be modified. Safe to share between threads once built.
 *
 * @param id {@code scenario:}, matching {@code [a-z0-9-]+}
 * @param pack {@code pack:}, or null when omitted
 * @param tags {@code tags:}
 * @param requires {@code requires:} capability ids; {@code "a | b"} means either
 * @param launchProperties {@code launchProperties:} with values as strings, in file order
 * @param slots {@code resolve:} in file order: fixture and query slots
 * @param steps {@code steps:} in file order
 * @param verify {@code verify:} items in file order
 * @param onPrompt {@code on_prompt:}, empty when omitted
 * @param artifacts {@code artifacts:}
 * @param stepTimeoutMs {@code timeouts.step} in milliseconds (default 10000)
 * @param sourceName the file name, for messages
 * @param sourceHash {@code "sha256:"} and the lower-case hex SHA-256 of the file's bytes
 * @param ui {@code ui:} (amendment A29), or null when the scenario has no such key (then {@link UiSettings#DEFAULT}
 *     applies and {@code plan.json} carries no {@code ui})
 * @param oracles {@code oracle:} in file order (phase2-contracts.md 3.1)
 * @param bindTimeoutMs {@code timeouts.bind} in milliseconds (default 60000, at most 600000)
 */
public record Scenario(String id, String pack, List<String> tags, List<String> requires,
                       Map<String, String> launchProperties, List<Slot> slots, List<Step> steps, List<Verify> verify,
                       Map<String, Object> onPrompt, List<String> artifacts, long stepTimeoutMs, String sourceName,
                       String sourceHash, UiSettings ui, List<OracleEntry> oracles, long bindTimeoutMs) {

    /** The step timeout when {@code timeouts.step} is absent. */
    public static final long DEFAULT_STEP_TIMEOUT_MS = 10_000L;

    /** Defaults for the Phase 2 components: no oracles, a 60 s bind budget. */
    public Scenario {
        oracles = oracles == null ? List.of() : List.copyOf(oracles);
        if (bindTimeoutMs <= 0) {
            bindTimeoutMs = Plan.DEFAULT_BIND_TIMEOUT_MS;
        }
    }

    /** A scenario without the Phase 2 keys {@code oracle} and {@code timeouts.bind}. */
    public Scenario(String id, String pack, List<String> tags, List<String> requires,
                    Map<String, String> launchProperties, List<Slot> slots, List<Step> steps, List<Verify> verify,
                    Map<String, Object> onPrompt, List<String> artifacts, long stepTimeoutMs, String sourceName,
                    String sourceHash, UiSettings ui) {
        this(id, pack, tags, requires, launchProperties, slots, steps, verify, onPrompt, artifacts, stepTimeoutMs,
                sourceName, sourceHash, ui, List.of(), Plan.DEFAULT_BIND_TIMEOUT_MS);
    }

    /** A scenario without the {@code ui} key. */
    public Scenario(String id, String pack, List<String> tags, List<String> requires,
                    Map<String, String> launchProperties, List<Slot> slots, List<Step> steps, List<Verify> verify,
                    Map<String, Object> onPrompt, List<String> artifacts, long stepTimeoutMs, String sourceName,
                    String sourceHash) {
        this(id, pack, tags, requires, launchProperties, slots, steps, verify, onPrompt, artifacts, stepTimeoutMs,
                sourceName, sourceHash, null);
    }

    /** The settings that apply: {@link #ui()}, or {@link UiSettings#DEFAULT} when the key is absent. */
    public UiSettings effectiveUi() {
        return ui != null ? ui : UiSettings.DEFAULT;
    }

    /** {@code timeouts.bind}. */
    public Duration bindTimeout() {
        return Duration.ofMillis(bindTimeoutMs);
    }

    /**
     * The {@code oracle} section in source form: name to {@code {fn, args?, must_equal?, crossCheck?}}, in file
     * order.
     */
    public Map<String, Object> oracle() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (OracleEntry entry : oracles) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("fn", entry.fn());
            if (entry.args() != null) {
                e.put("args", entry.args());
            }
            if (entry.hasMustEqual()) {
                e.put("must_equal", entry.mustEqual());
            }
            if (entry.crossCheck() != null) {
                e.put("crossCheck", entry.crossCheck());
            }
            out.put(entry.name(), e);
        }
        return out;
    }

    /**
     * One entry of {@code resolve:}: a fixture slot ({@code value}) or a query slot ({@code query}, {@code args},
     * {@code strategy}, {@code verify}); either may carry a {@code credential}.
     *
     * @param name the slot name referenced as {@code $name}
     * @param kind {@code "fixture"} or {@code "query"}
     * @param value the fixture value; null for a query slot
     * @param line source line of the entry
     * @param query the query kind of a query slot, else null
     * @param args the query's arguments with references in place ({@code {}} when absent), else null
     * @param strategy the strategy tokens as written; empty = not restricted
     * @param verify the verifier ids as written; empty = the pack's verifiers for the kind
     * @param credential the slot's credential declaration, or null
     */
    public record Slot(String name, String kind, Object value, int line, String query, Map<String, Object> args,
                       List<String> strategy, List<String> verify, CredentialSpec credential) {

        /** Copies the lists; null is empty. */
        public Slot {
            strategy = strategy == null ? List.of() : List.copyOf(strategy);
            verify = verify == null ? List.of() : List.copyOf(verify);
        }

        /** A fixture slot without a credential (the Phase 1 constructor). */
        public Slot(String name, String kind, Object value, int line) {
            this(name, kind, value, line, null, null, List.of(), List.of(), null);
        }
    }

    /**
     * One entry of {@code oracle:}.
     *
     * @param name the oracle name ({@code $oracle.<name>})
     * @param fn the pack's oracle function
     * @param args the arguments (a map, a list or a scalar) with references in place, or null when absent
     * @param mustEqual the bind-time guard; meaningful when {@code hasMustEqual}
     * @param hasMustEqual true when {@code must_equal} was written
     * @param line source line of the entry
     * @param crossCheck the {@code crossCheck} value in source form (phase3-contracts.md 3.1): a check id (the
     *     shorthand) or a mapping {@code {check, tolerance?, capability?}}, its shape checked by the loader; null when
     *     absent
     */
    public record OracleEntry(String name, String fn, Object args, Object mustEqual, boolean hasMustEqual,
                              int line, Object crossCheck) {

        /** An entry without a cross-check (the Phase 2 shape). */
        public OracleEntry(String name, String fn, Object args, Object mustEqual, boolean hasMustEqual, int line) {
            this(name, fn, args, mustEqual, hasMustEqual, line, null);
        }
    }

    /**
     * One entry of {@code steps:}.
     *
     * @param verb the map's single key
     * @param args the YAML value of the verb
     * @param line source line of the step
     */
    public record Step(String verb, Object args, int line) {
    }

    /**
     * One entry of {@code verify:}: a map of expectation path to matcher, or a single {@code db.<table>} key with a
     * stored-row check (phase2-contracts.md 3.1 rule 6).
     *
     * @param expectations path (or {@code db.<table>}) to matcher (or row check), in file order
     * @param line source line of the entry
     */
    public record Verify(Map<String, Object> expectations, int line) {
    }
}
