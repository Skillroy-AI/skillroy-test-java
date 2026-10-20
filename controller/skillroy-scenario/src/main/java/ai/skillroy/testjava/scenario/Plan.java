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

import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.PayloadMatch;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CaptureSpec;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A compiled scenario, {@code plan.json}: the exact primitive calls, syncs and checks a run executes, and the slots
 * and oracles its bind resolves. It is committed beside the YAML, reviewed as a diff and executed verbatim;
 * {@code $slot} references inside it are resolved at bind time. {@link #toJsonText()} is deterministic (fixed key
 * order, no timestamps, no absolute paths, nothing from an environment), so compiling the same YAML with the same pack
 * version gives byte-identical files.
 *
 * <p>Shape, version 7 (phase2-contracts.md 5, phase3-contracts.md 4, phase4-contracts.md 7.3, phase5-contracts.md
 * 13.2, phase7-contracts.md 8.2, phase8-contracts.md 8.2; version 6 adds {@code images} and a call's {@code guard} to
 * the keys of version 4 and 5, version 7 a step's {@code facts}): {@code planVersion,
 * scenarioId, pack, packVersion, sourceHash, inference, requires, slots, oracles, resolutionGraph, steps[{id, verb,
 * source{line}, calls[{primitive, args, sync, timeoutMs, await?, handlesPrompt?, capture?, guard?}], expect[],
 * facts?}], verify, cleanup, launchProperties, artifacts, onPrompt, stepTimeoutMs, ui?, web?, images?,
 * bindTimeoutMs}}. A slot is
 * {@code fixture} ({@code value}, optional {@code credential}), {@code query} ({@code query, args, strategy?, verify,
 * credential?}) or {@code capture} ({@code step, fields}); an oracle is {@code {name, fn, args, mustEqual?,
 * crossCheck?}}; an element of {@code verify} or of a step's {@code expect} is an {@link Expectation} ({@code path})
 * or a {@link RowCheck} ({@code db}, with {@code aggregates} from version 3); a step's {@code expect} may also hold
 * a {@link VisualCheck} ({@code visual}) or an {@link ImageCheck} ({@code image}), both version 6.
 * {@code "ui"} is written only when the
 * scenario has the key (amendment A29), and so is {@code "web"} ({@code {path, browser?, frozenTargets, trace}},
 * version 4: the web path policy, the default browser, the frozen-target policy and CDP tracing).
 *
 * <p>{@code inference} (phase5-contracts.md 13.2, decision D81; phase7-contracts.md 8.2, decision D325) is {@code run}
 * when a step's verb is {@code aiAct} (a model acts while steps run: {@code replay} reports the plan SKIPPED, every
 * other mode of {@code PlanExecutor} refuses it), else {@code bind} when a query slot's strategy list holds a token
 * whose strategy needs a model ({@code llm-sql}), and {@code none} otherwise: {@link #inferenceOf(List, List)}
 * computes it, and the reader requires a plan to say exactly that. {@code images} (version 6) lists, sorted, the labels
 * of every {@code expectImage} check and of every anchored {@code pointer} call's guard; absent when empty.
 *
 * <p>Version 7 (phase8-contracts.md 8.2, decisions D595, D596) adds no top-level key. It adds a step's {@code facts}
 * (the verb {@code captureFacts}, {@link FactCapture}), list filters, dotted filter fields and {@code [json]} in a path,
 * the matchers {@code anyOf} and {@code containsWhere}, {@code {has: scalar}} in an event match, and the call keys
 * {@code accept}, {@code rungs} and {@code minRung} (phase8-contracts.md 7.3, 7.6), each refused below version 7
 * with "needs a version 7 plan". From version 7 on a step or a call holding a key outside {@code STEP_KEYS_V7} or
 * {@code CALL_KEYS_V7} is refused; below 7 such a key is ignored, as every earlier reader did.
 *
 * <p>The reader accepts versions 1 to 7 (decisions D41, D58, D81, D325, D595; a version 6 plan is read as version 7
 * without the Phase 8 content, which needs version 7; a version 5 plan is read as version 6 without
 * {@code images}, guards, the two Phase 7 checks, the four Phase 7 verbs and {@code inference: run}, which need
 * version 6). A version 1 plan is read as version 5 with
 * {@code inference: none}, no oracles, an empty graph and {@code bindTimeoutMs} 60000; a version 2 plan is read as
 * version 5 without cross-checks and aggregates (the keys {@code crossCheck} and {@code aggregates} need version 3);
 * a version 3 plan is read as version 5 without {@code web} (the key needs version 4); a version 4 plan is read as
 * version 5 (its {@code inference} must be {@code none} and no slot may name {@code llm-sql}, which needs version 5);
 * any other version is refused.
 * A plan of version 2 or later may not hold a fixture field whose key looks secret (a hand-edited plan cannot bring a
 * secret back; a version 1 plan relies on the masking of amendment A25).
 *
 * <p>Immutable (every nested value is a deep unmodifiable copy); safe to share between threads.
 *
 * @param planVersion {@link #VERSION} for a compiled plan
 * @param scenarioId the scenario id
 * @param pack the pack id
 * @param packVersion the pack version the plan was compiled with
 * @param sourceHash {@code "sha256:<hex>"} of the YAML bytes
 * @param requires capability ids; {@code "a | b"} means either
 * @param slots fixture and query slots in the order of the {@code resolve} section, then capture slots in step order
 * @param steps the steps, ids {@code s1, s2, …}
 * @param verify checks evaluated after the last step
 * @param cleanup calls run at the end of a run whose start mark was written, in reverse order
 * @param launchProperties system properties the target must have been launched with
 * @param artifacts evidence kinds to keep even on success
 * @param onPrompt {@code {"unexpected": "fail" | "pack"}}, or empty for the pack's policy (phase8-contracts.md 9.2: no
 *     other value is read)
 * @param stepTimeoutMs the step timeout in milliseconds
 * @param ui the scenario's {@code ui} settings, or null when it has none
 * @param inference {@code none}, or {@code bind} when a slot's strategy needs a model at bind time ({@code run} is
 *     reserved, B16)
 * @param oracles the oracles, in file order
 * @param resolutionGraph fixture and query slot names and {@code oracle.<name>} in evaluation order
 * @param bindTimeoutMs the bind budget in milliseconds ({@code timeouts.bind}, default 60000)
 * @param web the scenario's {@code web} settings (phase4-contracts.md 7.2), or null when it has none (then
 *     {@link WebSettings#DEFAULT} applies, see {@link #effectiveWeb()}, and {@code plan.json} carries no {@code web})
 * @param images the labels of the plan's image baselines and anchors, sorted (phase7-contracts.md 8.2); null means
 *     none
 */
public record Plan(int planVersion, String scenarioId, String pack, String packVersion, String sourceHash,
                   List<String> requires, List<Slot> slots, List<Step> steps, List<Check> verify,
                   List<Call> cleanup, Map<String, String> launchProperties, List<String> artifacts,
                   Map<String, Object> onPrompt, long stepTimeoutMs, UiSettings ui, String inference,
                   List<Oracle> oracles, List<String> resolutionGraph, long bindTimeoutMs, WebSettings web,
                   List<String> images) {

    /** The plan format version this code writes; it reads 1 to 7 (phase8-contracts.md 8.2, decision D595). */
    public static final int VERSION = 7;

    /** {@code inference} of a plan whose bind needs no model (every plan before version 5). */
    public static final String INFERENCE_NONE = "none";

    /** {@code inference} of a plan whose bind needs a model: a slot names {@code llm-sql} (P5 13.2). */
    public static final String INFERENCE_BIND = "bind";

    /**
     * {@code inference} of a plan whose run needs a model: a step's verb is {@code aiAct} (phase7-contracts.md 8.2,
     * 8.6): SKIPPED in {@code replay}, refused by {@code PlanExecutor} in every other mode, run by the vision module's
     * scenario player.
     */
    public static final String INFERENCE_RUN = "run";

    /** The verbs a plan below version 6 cannot hold (phase7-contracts.md 8.2). */
    static final Set<String> PHASE7_VERBS = Set.of("aiAct", "aiExpect", "pointer", "expectImage");

    /** The base verb that captures facts into a slot (phase8-contracts.md 8.7): the one step that holds {@code facts}. */
    static final String CAPTURE_FACTS = "captureFacts";

    /** The verbs a plan below version 7 cannot hold (phase8-contracts.md 8.2). */
    static final Set<String> PHASE8_VERBS = Set.of(CAPTURE_FACTS);

    /**
     * The keys a step object may hold from version 7 on (phase8-contracts.md 8.2, decision D596): any other is refused.
     * A YAML step modifier compiles into the step's calls, so no step key is reserved for phase8-contracts.md 7.
     */
    static final Set<String> STEP_KEYS_V7 = Set.of("id", "verb", "source", "calls", "expect", "facts");

    /**
     * The keys a call object may hold from version 7 on (phase8-contracts.md 8.2, decision D596): any other is refused.
     * {@code accept}, {@code rungs} and {@code minRung} are reserved here for phase8-contracts.md 7.3 and 7.6, whose
     * readers are that section's own.
     */
    static final Set<String> CALL_KEYS_V7 = Set.of("primitive", "args", "sync", "timeoutMs", "await", "handlesPrompt",
            "capture", "guard", "accept", "rungs", "minRung");

    /** The most fields one {@code captureFacts} step captures (phase8-contracts.md 8.7). */
    public static final int MAX_FACT_FIELDS = 32;

    /** A field name of a {@code captureFacts} step: the name a later step reads as {@code $slot.<name>}. */
    static final Pattern FACT_FIELD = Pattern.compile("[A-Za-z0-9_]+");

    /** The bind budget when {@code timeouts.bind} is absent: 60 s. */
    public static final long DEFAULT_BIND_TIMEOUT_MS = 60_000L;

    /** The longest bind budget: 10 minutes. */
    public static final long MAX_BIND_TIMEOUT_MS = 600_000L;

    /**
     * The values {@code onPrompt.unexpected} may hold (phase8-contracts.md 9.2, gap 111, decision D615): {@code fail}
     * (every unexpected prompt fails the scenario) or {@code pack} (the pack's policy, the default spelled out); a
     * reader rule whatever the plan version, so that no plan carries a value that silently means the pack's policy.
     */
    static final Set<String> ON_PROMPT_VALUES = Set.of("fail", "pack");

    private static final Set<String> TOP_KEYS_V1 = Set.of("planVersion", "scenarioId", "pack", "packVersion",
            "sourceHash", "requires", "slots", "steps", "verify", "cleanup", "launchProperties", "artifacts",
            "onPrompt", "stepTimeoutMs", "ui");

    private static final Set<String> TOP_KEYS_V2;

    /** Version 4 adds {@code web} (phase4-contracts.md 7.3). */
    private static final Set<String> TOP_KEYS_V4;

    /** Version 6 adds {@code images} (phase7-contracts.md 8.2). */
    private static final Set<String> TOP_KEYS_V6;

    static {
        Set<String> keys = new HashSet<>(TOP_KEYS_V1);
        keys.addAll(List.of("inference", "oracles", "resolutionGraph", "bindTimeoutMs"));
        TOP_KEYS_V2 = Set.copyOf(keys);
        keys.add("web");
        TOP_KEYS_V4 = Set.copyOf(keys);
        keys.add("images");
        TOP_KEYS_V6 = Set.copyOf(keys);
    }

    private static final Set<String> UI_KEYS = Set.of("clickRung", "strict");

    private static final Set<String> WEB_KEYS = Set.of("path", "browser", "frozenTargets", "trace");

    private static final Set<String> AWAIT_KEYS = Set.of("topics", "match", "timeoutMs");

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Copies every collection into an unmodifiable one. */
    public Plan {
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(pack, "pack");
        requires = List.copyOf(requires);
        slots = List.copyOf(slots);
        steps = List.copyOf(steps);
        verify = List.copyOf(verify);
        cleanup = List.copyOf(cleanup);
        launchProperties = Collections.unmodifiableMap(new LinkedHashMap<>(launchProperties));
        artifacts = List.copyOf(artifacts);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) freeze(onPrompt);
        onPrompt = frozen;
        inference = inference == null ? INFERENCE_NONE : inference;
        oracles = oracles == null ? List.of() : List.copyOf(oracles);
        resolutionGraph = resolutionGraph == null ? List.of() : List.copyOf(resolutionGraph);
        if (bindTimeoutMs <= 0) {
            bindTimeoutMs = DEFAULT_BIND_TIMEOUT_MS;
        }
        images = images == null ? List.of() : List.copyOf(new TreeSet<>(images));
    }

    /** A plan without image baselines or anchors (the Phase 4-6 shape). */
    public Plan(int planVersion, String scenarioId, String pack, String packVersion, String sourceHash,
                List<String> requires, List<Slot> slots, List<Step> steps, List<Check> verify,
                List<Call> cleanup, Map<String, String> launchProperties, List<String> artifacts,
                Map<String, Object> onPrompt, long stepTimeoutMs, UiSettings ui, String inference,
                List<Oracle> oracles, List<String> resolutionGraph, long bindTimeoutMs, WebSettings web) {
        this(planVersion, scenarioId, pack, packVersion, sourceHash, requires, slots, steps, verify, cleanup,
                launchProperties, artifacts, onPrompt, stepTimeoutMs, ui, inference, oracles, resolutionGraph,
                bindTimeoutMs, web, List.of());
    }

    /** A plan of a scenario without the {@code web} key (the Phase 3 shape). */
    public Plan(int planVersion, String scenarioId, String pack, String packVersion, String sourceHash,
                List<String> requires, List<Slot> slots, List<Step> steps, List<Check> verify,
                List<Call> cleanup, Map<String, String> launchProperties, List<String> artifacts,
                Map<String, Object> onPrompt, long stepTimeoutMs, UiSettings ui, String inference,
                List<Oracle> oracles, List<String> resolutionGraph, long bindTimeoutMs) {
        this(planVersion, scenarioId, pack, packVersion, sourceHash, requires, slots, steps, verify, cleanup,
                launchProperties, artifacts, onPrompt, stepTimeoutMs, ui, inference, oracles, resolutionGraph,
                bindTimeoutMs, null, List.of());
    }

    /** A plan of a scenario without the {@code ui} key and without query slots or oracles (the Phase 1 shape). */
    public Plan(int planVersion, String scenarioId, String pack, String packVersion, String sourceHash,
                List<String> requires, List<Slot> slots, List<Step> steps, List<? extends Check> verify,
                List<Call> cleanup, Map<String, String> launchProperties, List<String> artifacts,
                Map<String, Object> onPrompt, long stepTimeoutMs) {
        this(planVersion, scenarioId, pack, packVersion, sourceHash, requires, slots, steps, verify, cleanup,
                launchProperties, artifacts, onPrompt, stepTimeoutMs, null);
    }

    /** A plan without query slots or oracles (the Phase 1 shape with amendment A29's {@code ui}). */
    public Plan(int planVersion, String scenarioId, String pack, String packVersion, String sourceHash,
                List<String> requires, List<Slot> slots, List<Step> steps, List<? extends Check> verify,
                List<Call> cleanup, Map<String, String> launchProperties, List<String> artifacts,
                Map<String, Object> onPrompt, long stepTimeoutMs, UiSettings ui) {
        this(planVersion, scenarioId, pack, packVersion, sourceHash, requires, slots, steps, List.copyOf(verify),
                cleanup, launchProperties, artifacts, onPrompt, stepTimeoutMs, ui, INFERENCE_NONE, List.of(),
                List.of(), DEFAULT_BIND_TIMEOUT_MS);
    }

    /**
     * One slot.
     *
     * @param name the slot name
     * @param kind {@code "fixture"}, {@code "query"} or {@code "capture"}
     * @param value the fixture value (may hold references); null for the other kinds
     * @param query the query of a query slot, else null
     * @param credential the slot's credential declaration (fixture and query slots), or null
     * @param capture the declaration of a captured slot, else null
     */
    public record Slot(String name, String kind, Object value, QuerySpec query, CredentialSpec credential,
                       CaptureDecl capture) {

        /**
         * Freezes the value.
         *
         * @throws IllegalArgumentException if the kind's own component is missing or another kind's is present
         */
        public Slot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            value = freeze(value);
            if ("query".equals(kind) != (query != null)) {
                throw new IllegalArgumentException("slot " + name + ": a query slot, and only a query slot, has a"
                        + " query");
            }
            if ("capture".equals(kind) != (capture != null)) {
                throw new IllegalArgumentException("slot " + name + ": a capture slot, and only a capture slot, has"
                        + " a capture declaration");
            }
            if ("capture".equals(kind) && credential != null) {
                throw new IllegalArgumentException("slot " + name + ": a captured slot cannot have a credential");
            }
        }

        /** A fixture slot (the Phase 1 constructor). */
        public Slot(String name, String kind, Object value) {
            this(name, kind, value, null, null, null);
        }

        /** The {@code plan.json} shape. */
        Map<String, Object> toJson() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("kind", kind);
            switch (kind) {
                case "query" -> {
                    s.put("query", query.query());
                    s.put("args", query.args());
                    if (!query.strategy().isEmpty()) {
                        s.put("strategy", query.strategy());
                    }
                    s.put("verify", query.verify());
                }
                case "capture" -> {
                    s.put("step", capture.step());
                    s.put("fields", capture.fields());
                }
                default -> s.put("value", value);
            }
            if (credential != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("realm", credential.realm());
                c.put("key", credential.key());
                s.put("credential", c);
            }
            return s;
        }
    }

    /**
     * The query of a query slot.
     *
     * @param query the query kind
     * @param args the arguments with references in place, keys sorted
     * @param strategy the scenario's restriction as tokens ({@code sql}, {@code java}); empty = not restricted
     * @param verify the verifier ids after defaulting; at least one
     */
    public record QuerySpec(String query, Map<String, Object> args, List<String> strategy, List<String> verify) {

        /** Freezes and copies. */
        public QuerySpec {
            Objects.requireNonNull(query, "query");
            @SuppressWarnings("unchecked")
            Map<String, Object> frozen = (Map<String, Object>) freeze(args == null ? Map.of() : args);
            args = frozen;
            strategy = strategy == null ? List.of() : List.copyOf(strategy);
            verify = verify == null ? List.of() : List.copyOf(verify);
        }
    }

    /**
     * A captured slot's declaration.
     *
     * @param step the id of the capturing step
     * @param fields the slot's field names, sorted
     */
    public record CaptureDecl(String step, List<String> fields) {

        /** Sorts the fields. */
        public CaptureDecl {
            Objects.requireNonNull(step, "step");
            fields = List.copyOf(new TreeSet<>(fields));
        }
    }

    /**
     * One oracle.
     *
     * @param name the oracle's name ({@code $oracle.<name>})
     * @param fn the pack's oracle function
     * @param args map keys sorted; a list or scalar as written; may hold references
     * @param mustEqual the bind-time guard, may hold references; meaningful only when {@code hasMustEqual}
     * @param hasMustEqual true when the scenario wrote {@code must_equal} (its value may be null)
     * @param crossCheck the oracle's cross-check (phase3-contracts.md 3.1), or null when the scenario wrote none
     */
    public record Oracle(String name, String fn, Object args, Object mustEqual, boolean hasMustEqual,
                         CrossCheckSpec crossCheck) {

        /** Freezes args and guard. */
        public Oracle {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(fn, "fn");
            args = freeze(args);
            mustEqual = freeze(mustEqual);
        }

        /** An oracle without a cross-check (the Phase 2 shape). */
        public Oracle(String name, String fn, Object args, Object mustEqual, boolean hasMustEqual) {
            this(name, fn, args, mustEqual, hasMustEqual, null);
        }

        /**
         * {@code {name, fn, args, mustEqual?, crossCheck?}}: {@code crossCheck} is the last key and written only when
         * the scenario wrote one, so that an oracle without one is written exactly as in Phase 2 (and the
         * {@code resolveHash} of a Phase 2 scenario does not change, decision D42).
         */
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", name);
            json.put("fn", fn);
            json.put("args", args);
            if (hasMustEqual) {
                json.put("mustEqual", mustEqual);
            }
            if (crossCheck != null) {
                json.put("crossCheck", crossCheck.toJson());
            }
            return json;
        }
    }

    /**
     * An oracle's cross-check as the plan holds it (phase3-contracts.md 3.1, 4.1): the {@code CrossCheck} id, the
     * tolerance as a plain decimal string, and the guarding capability.
     *
     * @param check the cross-check id
     * @param tolerance a plain decimal string of zero or more ({@code "0.00"}), or null for none (exact)
     * @param capability the guarding capability id, or null for an unguarded check
     */
    public record CrossCheckSpec(String check, String tolerance, String capability) {

        /**
         * @throws IllegalArgumentException if the check or the capability is blank, or the tolerance is not a plain
         *     decimal string of zero or more
         */
        public CrossCheckSpec {
            if (check == null || check.isBlank()) {
                throw new IllegalArgumentException("a cross-check needs a check id");
            }
            if (capability != null && capability.isBlank()) {
                throw new IllegalArgumentException("cross-check " + check + ": the capability must not be blank");
            }
            if (tolerance != null && !TOLERANCE.matcher(tolerance).matches()) {
                throw new IllegalArgumentException("cross-check " + check + ": the tolerance must be a plain decimal"
                        + " of zero or more, such as \"0.00\"");
            }
        }

        /** {@code {check, tolerance?, capability?}}, in that order; a new map on each call. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("check", check);
            if (tolerance != null) {
                json.put("tolerance", tolerance);
            }
            if (capability != null) {
                json.put("capability", capability);
            }
            return json;
        }
    }

    /** A plain non-negative decimal: digits, an optional fraction, no sign, no exponent. */
    private static final Pattern TOLERANCE = Pattern.compile("\\d+(\\.\\d+)?");

    /**
     * One step.
     *
     * @param id {@code s1}, {@code s2}, …
     * @param verb the YAML verb
     * @param line source line in the YAML, 0 when unknown
     * @param calls the calls in execution order (empty for {@code expect} and {@code captureFacts})
     * @param expect checks evaluated after the calls (only {@code expect} steps have them)
     * @param facts what a {@code captureFacts} step captures (phase8-contracts.md 8.7; plan version 7), null for every
     *     other verb
     */
    public record Step(String id, String verb, int line, List<Call> calls, List<Check> expect, FactCapture facts) {

        /**
         * Copies the lists.
         *
         * @throws IllegalArgumentException if {@code facts} is given on a verb other than {@code captureFacts}, or
         *     beside a call or a check
         */
        public Step {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(verb, "verb");
            calls = List.copyOf(calls);
            expect = List.copyOf(expect);
            if (facts != null && !CAPTURE_FACTS.equals(verb)) {
                throw new IllegalArgumentException("step " + id + ": 'facts' is allowed on " + CAPTURE_FACTS
                        + " only");
            }
            if (facts != null && (!calls.isEmpty() || !expect.isEmpty())) {
                throw new IllegalArgumentException("step " + id + ": " + CAPTURE_FACTS + " has no calls");
            }
        }

        /** A step without captured facts (the Phase 1-7 shape). */
        public Step(String id, String verb, int line, List<Call> calls, List<Check> expect) {
            this(id, verb, line, calls, expect, null);
        }
    }

    /**
     * What a {@code captureFacts} step captures (phase8-contracts.md 8.7, decision D604): fact paths read once where the
     * step stands and bound into a captured slot. {@code plan.json}: {@code "facts": {"slot", "fields": {<name>:
     * {"path": p} | {"size": p}}}}, the fields sorted by name.
     *
     * @param slot the captured slot's name, {@code [A-Za-z_][A-Za-z0-9_]*}
     * @param fields 1 to {@link Plan#MAX_FACT_FIELDS} fields by name ({@code [A-Za-z0-9_]+}), sorted
     */
    public record FactCapture(String slot, Map<String, FactField> fields) {

        /**
         * Sorts the fields.
         *
         * @throws IllegalArgumentException if the slot or a field name is malformed, or there are no fields or more
         *     than {@link Plan#MAX_FACT_FIELDS}
         */
        public FactCapture {
            if (slot == null || !NAME.matcher(slot).matches()) {
                throw new IllegalArgumentException("the slot name must match [A-Za-z_][A-Za-z0-9_]*, found " + slot);
            }
            if (fields == null || fields.isEmpty() || fields.size() > MAX_FACT_FIELDS) {
                throw new IllegalArgumentException("captureFacts needs 1 to " + MAX_FACT_FIELDS + " fields, found "
                        + (fields == null ? 0 : fields.size()));
            }
            Map<String, FactField> sorted = new TreeMap<>();
            for (Map.Entry<String, FactField> e : fields.entrySet()) {
                if (e.getKey() == null || !FACT_FIELD.matcher(e.getKey()).matches()) {
                    throw new IllegalArgumentException("the field name '" + e.getKey() + "' must match [A-Za-z0-9_]+");
                }
                sorted.put(e.getKey(), Objects.requireNonNull(e.getValue(), "field " + e.getKey()));
            }
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        }

        /** {@code {slot, fields}}, the fields sorted; a new map on each call. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("slot", slot);
            Map<String, Object> fieldsJson = new LinkedHashMap<>();
            for (Map.Entry<String, FactField> e : fields.entrySet()) {
                fieldsJson.put(e.getKey(), e.getValue().toJson());
            }
            json.put("fields", fieldsJson);
            return json;
        }
    }

    /**
     * One captured fact (phase8-contracts.md 8.7): the value at a path, or with {@code size} the size the {@code size}
     * matcher measures (a list's or map's size, a string's length). {@code plan.json}: {@code {"path": p}} or
     * {@code {"size": p}}.
     *
     * @param path an expectation path; it may hold {@code $slot} references in filter values
     * @param size true to capture the size of the value at the path
     */
    public record FactField(String path, boolean size) {

        /** @throws IllegalArgumentException if the path is null or blank */
        public FactField {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("a captured fact needs a path");
            }
        }

        /** {@code {"path": p}} or {@code {"size": p}}; a new map on each call. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put(size ? "size" : "path", path);
            return json;
        }
    }

    /**
     * One primitive call.
     *
     * @param primitive the primitive name
     * @param args the params, possibly holding {@code $slot} references
     * @param sync how the executor waits after the call
     * @param timeoutMs the call's timeout in milliseconds
     * @param await the event that completes the call: required when {@code sync} is {@link Sync#EVENT}, null
     *     otherwise. Its topics and match may hold {@code $slot} references; a timeout of 0 means the run's step
     *     timeout (compiled plans always carry a positive one)
     * @param handlesPrompt true if the call answers or awaits an application prompt (see
     *     {@code CompileContext.markHandlesPrompt})
     * @param capture the slot bound from the event that ended the call's wait, or null; only with sync
     *     {@link Sync#EVENT} or on {@code sync.await}
     * @param guard the image guard of an anchored {@code swing.pointer} call (phase7-contracts.md 6.7, 8.2, decision
     *     D357; plan version 6), or null: the executor compares the anchor of the current render key before it sends
     *     the call
     * @param accept the warning codes the call accepts (phase8-contracts.md 7.1, 7.3; plan version 7), sorted; empty
     *     for none
     * @param rungs the call's rung ladder, best first (phase8-contracts.md 7.6; plan version 7); empty for none
     * @param minRung the rung the step requires, one of {@code rungs}, or null (plan version 7)
     */
    public record Call(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                       boolean handlesPrompt, CaptureSpec capture, Guard guard, Set<String> accept,
                       List<ai.skillroy.testjava.scenario.pack.Rung> rungs, String minRung) {

        /**
         * Freezes the args and the await match.
         *
         * @throws IllegalArgumentException if {@code sync} is {@link Sync#EVENT} without an {@code await},
         *     {@code await} is given with another sync, or a capture is given on a call that has neither sync EVENT
         *     nor the primitive {@code sync.await}
         */
        public Call {
            Objects.requireNonNull(primitive, "primitive");
            Objects.requireNonNull(sync, "sync");
            @SuppressWarnings("unchecked")
            Map<String, Object> frozen = (Map<String, Object>) freeze(args == null ? Map.of() : args);
            args = frozen;
            if (sync == Sync.EVENT && await == null) {
                throw new IllegalArgumentException("call " + primitive + ": sync EVENT needs an await spec");
            }
            if (sync != Sync.EVENT && await != null) {
                throw new IllegalArgumentException("call " + primitive + ": an await spec needs sync EVENT, found "
                        + sync);
            }
            if (capture != null && sync != Sync.EVENT && !"sync.await".equals(primitive)) {
                throw new IllegalArgumentException("call " + primitive + ": capture needs sync EVENT or the"
                        + " primitive sync.await, found sync " + sync);
            }
            if (await != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> match = (Map<String, Object>) freeze(await.match());
                await = new AwaitSpec(await.topics(), match, await.timeoutMs());
            }
            if (guard != null && !"swing.pointer".equals(primitive)) {
                throw new IllegalArgumentException("call " + primitive + ": a guard is allowed on swing.pointer only");
            }
            accept = accept == null || accept.isEmpty() ? Set.of()
                    : java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(accept));
            rungs = rungs == null || rungs.isEmpty() ? List.of() : List.copyOf(rungs);
            ai.skillroy.testjava.scenario.pack.Rung.check("call " + primitive, accept, rungs, minRung);
        }

        /** A call without accepted warnings, ladder or required rung (the Phase 7 shape). */
        public Call(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                    boolean handlesPrompt, CaptureSpec capture, Guard guard) {
            this(primitive, args, sync, timeoutMs, await, handlesPrompt, capture, guard, Set.of(), List.of(), null);
        }

        /** A call without a guard (the Phase 2 shape). */
        public Call(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                    boolean handlesPrompt, CaptureSpec capture) {
            this(primitive, args, sync, timeoutMs, await, handlesPrompt, capture, null);
        }

        /** A call without a capture (the Phase 1 shape). */
        public Call(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                    boolean handlesPrompt) {
            this(primitive, args, sync, timeoutMs, await, handlesPrompt, null, null);
        }

        /** A call with any sync but {@link Sync#EVENT}, which needs an {@link AwaitSpec}. */
        public Call(String primitive, Map<String, Object> args, Sync sync, long timeoutMs, boolean handlesPrompt) {
            this(primitive, args, sync, timeoutMs, null, handlesPrompt, null, null);
        }

        /** This call with another guard (null: none). */
        public Call withGuard(Guard newGuard) {
            return new Call(primitive, args, sync, timeoutMs, await, handlesPrompt, capture, newGuard, accept, rungs,
                    minRung);
        }

        /** This call with the step's modifiers applied (phase8-contracts.md 7.3). */
        Call withModifiers(Set<String> newAccept, String newMinRung) {
            return new Call(primitive, args, sync, timeoutMs, await, handlesPrompt, capture, guard, newAccept, rungs,
                    newMinRung);
        }

        /**
         * The {@code plan.json} shape; a new map on each call. {@code await} is written only for {@link Sync#EVENT},
         * always with its three keys ({@code "match": null} for any payload); {@code handlesPrompt} only when true;
         * {@code capture} only when present.
         */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("primitive", primitive);
            json.put("args", args);
            json.put("sync", sync.name());
            json.put("timeoutMs", timeoutMs);
            if (await != null) {
                Map<String, Object> awaitJson = new LinkedHashMap<>();
                awaitJson.put("topics", await.topics());
                awaitJson.put("match", await.match());
                awaitJson.put("timeoutMs", await.timeoutMs());
                json.put("await", awaitJson);
            }
            if (handlesPrompt) {
                json.put("handlesPrompt", true);
            }
            if (capture != null) {
                Map<String, Object> captureJson = new LinkedHashMap<>();
                captureJson.put("slot", capture.slot());
                captureJson.put("fields", new LinkedHashMap<>(capture.fields()));
                json.put("capture", captureJson);
            }
            if (guard != null) {
                Map<String, Object> guardJson = new LinkedHashMap<>();
                guardJson.put("label", guard.label());
                json.put("guard", guardJson);
            }
            // phase8-contracts.md 7.3, 7.6: written only when set, so that a plan without them reads as before.
            if (!accept.isEmpty()) {
                json.put("accept", new ArrayList<>(accept));
            }
            if (!rungs.isEmpty()) {
                List<Object> ladder = new ArrayList<>();
                for (ai.skillroy.testjava.scenario.pack.Rung rung : rungs) {
                    Map<String, Object> rungJson = new LinkedHashMap<>();
                    rungJson.put("name", rung.name());
                    rungJson.put("capability", rung.capability());
                    ladder.add(rungJson);
                }
                json.put("rungs", ladder);
            }
            if (minRung != null) {
                json.put("minRung", minRung);
            }
            return json;
        }
    }

    /**
     * The image guard of an anchored {@code pointer} step (phase7-contracts.md 6.7, 8.2, decision D357): the label of
     * the anchor in the bindings file's {@code images} section. The guard's rectangle is environment data and lives in
     * the bindings entry of the current render key, never in the plan. {@code plan.json}: {@code "guard": {"label"}}.
     *
     * @param label the anchor's label, {@code [A-Za-z0-9][A-Za-z0-9._-]{0,63}}
     */
    public record Guard(String label) {

        /** @throws IllegalArgumentException if the label is malformed */
        public Guard {
            if (label == null || !ImageCheck.LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("a guard label must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}, found "
                        + label);
            }
        }
    }

    /** The web settings that apply: {@link #web()}, or {@link WebSettings#DEFAULT} when the scenario has none. */
    public WebSettings effectiveWeb() {
        return web != null ? web : WebSettings.DEFAULT;
    }

    /**
     * True when the plan has a query slot, an oracle or an image baseline or anchor ({@code images}, phase7-contracts.md
     * 8.2, 8.7, decision D355): its bind reads or writes a bindings file (8.1).
     */
    public boolean needsBindings() {
        if (!oracles.isEmpty() || !images.isEmpty()) {
            return true;
        }
        for (Slot slot : slots) {
            if ("query".equals(slot.kind())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code inference} field these slots give (phase5-contracts.md 13.2): {@link #INFERENCE_BIND} when a query
     * slot's strategy list holds a token whose strategy {@link Strategy#needsModel() needs a model}, else
     * {@link #INFERENCE_NONE}.
     */
    public static String inferenceOf(List<Slot> slots) {
        return inferenceOf(slots, List.of());
    }

    /**
     * The {@code inference} field these slots and steps give (phase7-contracts.md 8.2): {@link #INFERENCE_RUN} when a
     * step's verb is {@code aiAct}, else {@link #inferenceOf(List)}.
     */
    public static String inferenceOf(List<Slot> slots, List<Step> steps) {
        for (Step step : steps == null ? List.<Step>of() : steps) {
            if ("aiAct".equals(step.verb())) {
                return INFERENCE_RUN;
            }
        }
        return slotInference(slots);
    }

    private static String slotInference(List<Slot> slots) {
        for (Slot slot : slots) {
            if (slot.query() == null) {
                continue;
            }
            for (String token : slot.query().strategy()) {
                Strategy strategy = Strategy.ofToken(token);
                if (strategy != null && strategy.needsModel()) {
                    return INFERENCE_BIND;
                }
            }
        }
        return INFERENCE_NONE;
    }

    /**
     * The image labels these steps name (phase7-contracts.md 8.2): every {@link ImageCheck}'s and every call
     * {@link Guard}'s label, sorted and without repeats.
     */
    public static List<String> imagesOf(List<Step> steps) {
        Set<String> labels = new TreeSet<>();
        for (Step step : steps) {
            for (Call call : step.calls()) {
                if (call.guard() != null) {
                    labels.add(call.guard().label());
                }
            }
            for (Check check : step.expect()) {
                if (check instanceof ImageCheck image) {
                    labels.add(image.label());
                }
            }
        }
        return List.copyOf(labels);
    }

    /** True when a call of the steps or the cleanup carries {@code via: ROBOT} or {@code mode: ROBOT} (P7 9.2). */
    public boolean usesRobot() {
        for (Step step : steps) {
            for (Call call : step.calls()) {
                if (robot(call)) {
                    return true;
                }
            }
        }
        for (Call call : cleanup) {
            if (robot(call)) {
                return true;
            }
        }
        return false;
    }

    /** Whether one call carries {@code via: ROBOT} (a pointer) or {@code mode: ROBOT} (a capture), in any spelling. */
    static boolean robot(Call call) {
        return robotValue(call.args().get("via")) || robotValue(call.args().get("mode"));
    }

    /**
     * Whether an argument's value names Robot ({@code via} of {@code swing.pointer}, {@code mode} of
     * {@code swing.capture}) as the agent would read it: the controller's one definition for every gate of the double
     * opt-in and of the person-only glass rule (phase7-contracts.md 3.4, 9.2; the Phase 7 review's fix wave, FB,
     * L1-F1 = L2-F3). The agent reads both arguments as {@code value.trim().toUpperCase(Locale.ROOT)}, so a gate
     * comparing the exact {@code "ROBOT"} let {@code robot}, {@code " Robot"} or {@code ROBOT} behind a control
     * character through to an agent that acts on them. This predicate accepts a superset of the agent's spellings:
     * both ends are stripped of every character {@link String#trim()} strips (code points up to U+0020, the control
     * characters included) and of every whitespace character {@link String#strip()} strips, and the rest is compared
     * with {@code ROBOT} ignoring case, or upper-cased under {@link Locale#ROOT} as the agent does.
     *
     * @param value the argument's value as the call carries it; null (absent) is not Robot
     * @return true when the agent would read the value as {@code ROBOT}, or a looser spelling of it
     */
    public static boolean robotValue(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        int from = 0;
        int to = text.length();
        while (from < to && blankEnd(text.charAt(from))) {
            from++;
        }
        while (to > from && blankEnd(text.charAt(to - 1))) {
            to--;
        }
        String core = text.substring(from, to);
        return ROBOT.equalsIgnoreCase(core) || ROBOT.equals(core.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether a capture's arguments ask for an unredacted image: {@code redact} present and anything but the boolean
     * {@code true} (the agent coerces the strings {@code "true"}/{@code "false"}, so a string {@code "false"} is one;
     * the facade's and {@code CallPolicy}'s rule, fix wave FB).
     */
    public static boolean unredacted(Map<?, ?> args) {
        return args != null && args.containsKey("redact") && !Boolean.TRUE.equals(args.get("redact"));
    }

    private static final String ROBOT = "ROBOT";

    private static boolean blankEnd(char c) {
        return c <= ' ' || Character.isWhitespace(c);
    }

    /** The slot with this name, or null. */
    public Slot slot(String name) {
        for (Slot slot : slots) {
            if (slot.name().equals(name)) {
                return slot;
            }
        }
        return null;
    }

    /** The oracle with this name, or null. */
    public Oracle oracle(String name) {
        for (Oracle oracle : oracles) {
            if (oracle.name().equals(name)) {
                return oracle;
            }
        }
        return null;
    }

    /** Every {@link RowCheck} of the steps and of {@code verify}, in plan order. */
    public List<RowCheck> rowChecks() {
        List<RowCheck> out = new ArrayList<>();
        for (Step step : steps) {
            for (Check check : step.expect()) {
                if (check instanceof RowCheck row) {
                    out.add(row);
                }
            }
        }
        for (Check check : verify) {
            if (check instanceof RowCheck row) {
                out.add(row);
            }
        }
        return out;
    }

    /** The {@code plan.json} object in contract key order; a new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("planVersion", planVersion);
        json.put("scenarioId", scenarioId);
        json.put("pack", pack);
        json.put("packVersion", packVersion);
        json.put("sourceHash", sourceHash);
        json.put("inference", inference);
        json.put("requires", requires);
        json.put("slots", slots.stream().map(Slot::toJson).toList());
        json.put("oracles", oracles.stream().map(Oracle::toJson).toList());
        json.put("resolutionGraph", resolutionGraph);
        List<Object> stepsJson = new ArrayList<>();
        for (Step step : steps) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", step.id());
            s.put("verb", step.verb());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("line", step.line());
            s.put("source", source);
            s.put("calls", step.calls().stream().map(Call::toJson).toList());
            s.put("expect", step.expect().stream().map(Check::toJson).toList());
            if (step.facts() != null) {
                s.put("facts", step.facts().toJson());
            }
            stepsJson.add(s);
        }
        json.put("steps", stepsJson);
        json.put("verify", verify.stream().map(Check::toJson).toList());
        json.put("cleanup", cleanup.stream().map(Call::toJson).toList());
        json.put("launchProperties", launchProperties);
        json.put("artifacts", artifacts);
        json.put("onPrompt", onPrompt);
        json.put("stepTimeoutMs", stepTimeoutMs);
        if (ui != null) {
            json.put("ui", ui.toJson());
        }
        if (web != null) {
            json.put("web", webJson(web));
        }
        if (!images.isEmpty()) {
            json.put("images", images);
        }
        json.put("bindTimeoutMs", bindTimeoutMs);
        return json;
    }

    /**
     * The {@code web} object of {@code plan.json}: {@code {path, browser?, frozenTargets, trace}}, the enumerated
     * values in lower case as the scenario writes them, the browser's key sorted; a new map on each call. The attach
     * deadline is not a scenario key and is not written.
     */
    static Map<String, Object> webJson(WebSettings web) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", web.path().name().toLowerCase(Locale.ROOT));
        if (web.defaultBrowser() != null) {
            json.put("browser", new TreeMap<>(web.defaultBrowser()));
        }
        json.put("frozenTargets", web.frozenTargets().name().toLowerCase(Locale.ROOT));
        json.put("trace", web.trace());
        return json;
    }

    /** The {@code plan.json} text: {@code Json.writePretty} of {@link #toJson()}, ending with a newline. */
    public String toJsonText() {
        return Json.writePretty(toJson());
    }

    /** {@code "sha256:<hex>"} of the plan's canonical JSON; written to bindings files as {@code planHash}. */
    public String hash() {
        return ScenarioLoader.sourceHash(Json.writeCanonical(toJson()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code "sha256:"} over the canonical JSON of {@code {slots (kinds fixture and query), oracles,
     * resolutionGraph}}: what bindings are tied to (decision D7). A step, an expectation or the pack version does not
     * enter it, so that editing those does not invalidate every environment's bindings.
     */
    public String resolveHash() {
        Map<String, Object> json = new LinkedHashMap<>();
        List<Object> slotJson = new ArrayList<>();
        for (Slot slot : slots) {
            if (!"capture".equals(slot.kind())) {
                slotJson.add(slot.toJson());
            }
        }
        json.put("slots", slotJson);
        json.put("oracles", oracles.stream().map(Oracle::toJson).toList());
        json.put("resolutionGraph", resolutionGraph);
        return ScenarioLoader.sourceHash(Json.writeCanonical(json).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes {@link #toJsonText()} as UTF-8, creating parent directories; created exclusively in a directory of the
     * current user, never through what is in its place (Phase 6 security fix, pass 2, P2-L8-F3).
     */
    public void write(Path file) throws IOException {
        OutputFiles.writeString(file, toJsonText());
    }

    /**
     * @throws IOException if the file cannot be read
     * @throws ScenarioException if it is not a valid plan of version 1 to 7
     */
    public static Plan read(Path file) throws IOException, ScenarioException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * @throws ScenarioException if the text is not a valid plan of version 1 to 7
     */
    public static Plan parse(String text) throws ScenarioException {
        Map<String, Object> json;
        try {
            json = Json.parseObject(text);
        } catch (JsonException e) {
            throw new ScenarioException("plan.json is not a JSON object: " + e.getMessage(), e);
        }
        return fromJson(json);
    }

    /**
     * Reads the {@code plan.json} shape and checks what the compiler guarantees, because a plan file may have been
     * edited by hand: the scenario id (it names output directories), positive timeouts, expectation paths and
     * matchers, slot and oracle shapes, the resolution graph, the generated SQL of row checks (through the statement
     * guard), and, for version 2 and later, that no fixture field key looks secret. Unknown keys are rejected,
     * because a later plan version may need them to run correctly: an oracle's {@code crossCheck} and a {@code db}
     * element's {@code aggregates} need version 3, the top-level {@code web} needs version 4, {@code inference: bind}
     * and the strategy token {@code llm-sql} need version 5; {@code images}, a call's {@code guard}, the checks
     * {@code visual} and {@code image}, the verbs {@code aiAct}, {@code aiExpect}, {@code pointer} and
     * {@code expectImage} and {@code inference: run} need version 6; a step's {@code facts} (the verb
     * {@code captureFacts}), a list filter, a dotted filter field or {@code [json]} in a path, the matchers
     * {@code anyOf} and {@code containsWhere} and an operator object ({@code {has: …}}) in an event match need version
     * 7, from which on a step or a call holding a key outside {@code STEP_KEYS_V7} or {@code CALL_KEYS_V7} is refused
     * (phase8-contracts.md 8.2). A plan's {@code inference} must be what its slots
     * and steps give ({@link #inferenceOf(List, List)}) and its {@code images} what its steps name
     * ({@link #imagesOf}); the tokens {@code llm-reason} and {@code llm-vision} are refused as not in this version.
     *
     * @throws ScenarioException if a key is missing, unknown or of the wrong type, or the version is not 1 to 7
     */
    public static Plan fromJson(Map<String, Object> json) throws ScenarioException {
        PlanReader r = new PlanReader(json);
        if (!(json.get("planVersion") instanceof Long rawVersion) || rawVersion < 1 || rawVersion > VERSION) {
            throw r.error("planVersion " + json.get("planVersion") + " is not supported (expected 1, 2, 3, 4, 5, 6 or "
                    + VERSION + ")");
        }
        boolean v2OrLater = rawVersion >= 2;
        r.version = rawVersion.intValue();
        for (String key : json.keySet()) {
            if ("web".equals(key) && rawVersion < 4) {
                throw r.error("'web' needs a version 4 plan");
            }
            if ("images".equals(key) && rawVersion < 6) {
                throw r.error("'images' needs a version 6 plan");
            }
            if (!(rawVersion >= 6 ? TOP_KEYS_V6 : rawVersion >= 4 ? TOP_KEYS_V4 : v2OrLater ? TOP_KEYS_V2
                    : TOP_KEYS_V1).contains(key)) {
                throw r.error("unknown key '" + key + "'" + (v2OrLater ? "" : " in a version 1 plan"));
            }
        }
        String scenarioId = r.str(json, "scenarioId");
        if (!ScenarioLoader.ID.matcher(scenarioId).matches()) {
            r.id = null;
            throw r.error("'scenarioId' must match [a-z0-9-]+");
        }
        String inference = INFERENCE_NONE;
        if (v2OrLater) {
            inference = r.str(json, "inference");
            if (INFERENCE_RUN.equals(inference) && rawVersion < 6) {
                throw r.error("inference 'run' needs a version 6 plan");
            }
            if (rawVersion < 5 && !INFERENCE_NONE.equals(inference)) {
                throw r.error("inference '" + inference + "' needs a version 5 plan; a version " + rawVersion
                        + " plan has inference 'none'");
            }
            if (!INFERENCE_NONE.equals(inference) && !INFERENCE_BIND.equals(inference)
                    && !INFERENCE_RUN.equals(inference)) {
                throw r.error("unknown inference '" + inference + "' (expected none, bind or run)");
            }
        }
        List<Slot> slots = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> s : r.objects(json, "slots")) {
            Slot slot = r.slot(s, v2OrLater);
            if (!names.add(slot.name())) {
                throw r.error("slot '" + slot.name() + "' appears twice");
            }
            slots.add(slot);
        }
        List<Step> steps = new ArrayList<>();
        for (Map<String, Object> s : r.objects(json, "steps")) {
            if (rawVersion >= 7) {
                r.keys(s, STEP_KEYS_V7, "step " + r.str(s, "id"));
            }
            List<Call> calls = new ArrayList<>();
            for (Map<String, Object> c : r.objects(s, "calls")) {
                calls.add(r.call(c));
            }
            List<Check> expect = new ArrayList<>();
            for (Map<String, Object> e : r.objects(s, "expect")) {
                expect.add(r.check(e));
            }
            long line = r.num(r.object(s, "source"), "line");
            if (line < 0 || line > Integer.MAX_VALUE) {
                throw r.error("'line' must be a line number or 0");
            }
            String verb = r.str(s, "verb");
            if (PHASE7_VERBS.contains(verb) && rawVersion < 6) {
                throw r.error("the verb '" + verb + "' needs a version 6 plan");
            }
            String id = r.str(s, "id");
            steps.add(new Step(id, verb, (int) line, calls, expect, r.facts(s, id, verb, calls, expect)));
        }
        String given = inference;
        String derived = inferenceOf(slots, steps);
        if (!given.equals(derived)) {
            throw r.error(switch (given) {
                case INFERENCE_RUN -> "inference is 'run' but no step's verb is aiAct";
                case INFERENCE_BIND -> INFERENCE_RUN.equals(derived)
                        ? "inference is 'bind' but a step's verb is aiAct; such a plan has inference 'run'"
                        : "inference is 'bind' but no slot names a strategy that needs a model";
                default -> INFERENCE_RUN.equals(derived)
                        ? "inference is 'none' but a step's verb is aiAct; such a plan has inference 'run'"
                        : "inference is 'none' but a slot names a strategy that needs a model (llm-sql); such a"
                        + " plan has inference 'bind'";
            });
        }
        List<String> images = json.containsKey("images") ? r.strings(json, "images") : List.of();
        Set<String> folded = new HashSet<>();
        for (String label : images) {
            if (!ImageCheck.LABEL.matcher(label).matches()) {
                throw r.error("images: '" + label + "' is not a label ([A-Za-z0-9][A-Za-z0-9._-]{0,63})");
            }
            // Phase 7 security fix, S118 (phase7-contracts.md 8.2): a label names a file, so two labels equal
            // ignoring case would share one on a case-insensitive volume.
            if (!folded.add(label.toLowerCase(Locale.ROOT))) {
                throw r.error("images: '" + label + "' differs from another label only in case; a label names a file");
            }
        }
        if (!images.equals(imagesOf(steps))) {
            throw r.error("'images' must list the labels of the plan's image checks and guards, sorted: expected "
                    + imagesOf(steps) + ", found " + images);
        }
        List<Check> verify = new ArrayList<>();
        for (Map<String, Object> e : r.objects(json, "verify")) {
            verify.add(r.check(e));
        }
        List<Call> cleanup = new ArrayList<>();
        for (Map<String, Object> c : r.objects(json, "cleanup")) {
            Call call = r.call(c);
            // Phase 7 security fix, S119 (phase7-contracts.md 8.2): a cleanup call is sent without a guard, so the
            // reader refuses one rather than show a guard that is never evaluated.
            if (call.guard() != null) {
                throw r.error("cleanup: a guard is allowed on a step's swing.pointer only");
            }
            cleanup.add(call);
        }
        Map<String, String> launchProperties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : r.object(json, "launchProperties").entrySet()) {
            if (!(e.getValue() instanceof String value)) {
                throw r.error("launchProperties." + e.getKey() + " must be a string");
            }
            launchProperties.put(e.getKey(), value);
        }
        List<Oracle> oracles = new ArrayList<>();
        List<String> graph = List.of();
        long bindTimeoutMs = DEFAULT_BIND_TIMEOUT_MS;
        if (v2OrLater) {
            Set<String> oracleNames = new HashSet<>();
            for (Map<String, Object> o : r.objects(json, "oracles")) {
                Oracle oracle = r.oracle(o);
                if (!oracleNames.add(oracle.name())) {
                    throw r.error("oracle '" + oracle.name() + "' appears twice");
                }
                oracles.add(oracle);
            }
            graph = r.strings(json, "resolutionGraph");
            r.checkGraph(graph, slots, oracles);
            bindTimeoutMs = r.positive(json, "bindTimeoutMs");
            if (bindTimeoutMs > MAX_BIND_TIMEOUT_MS) {
                throw r.error("'bindTimeoutMs' must be at most " + MAX_BIND_TIMEOUT_MS + ", found " + bindTimeoutMs);
            }
        }
        for (Slot slot : slots) {
            if ("capture".equals(slot.kind()) && !stepIds(steps).contains(slot.capture().step())) {
                throw r.error("captured slot '" + slot.name() + "' names step " + slot.capture().step()
                        + ", which the plan does not have");
            }
        }
        checkOnPrompt(r, r.object(json, "onPrompt"));
        return new Plan(VERSION, scenarioId, r.str(json, "pack"), r.str(json, "packVersion"),
                r.str(json, "sourceHash"), r.strings(json, "requires"), slots, steps, verify, cleanup,
                launchProperties, r.strings(json, "artifacts"), r.object(json, "onPrompt"),
                r.positive(json, "stepTimeoutMs"), json.get("ui") == null ? null : r.ui(r.object(json, "ui")),
                inference, oracles, graph, bindTimeoutMs, json.containsKey("web") ? r.web(r.object(json, "web"))
                        : null, images);
    }

    /**
     * The reader's {@code onPrompt} rule (phase8-contracts.md 9.2, gap 111, decision D615), whatever the plan version:
     * the key {@code unexpected} only, holding {@code fail} or {@code pack} (ignoring case and surrounding blanks, as
     * the loader reads them); an empty object is the pack's policy.
     */
    private static void checkOnPrompt(PlanReader r, Map<String, Object> onPrompt) throws ScenarioException {
        for (Map.Entry<String, Object> e : onPrompt.entrySet()) {
            if (!"unexpected".equals(e.getKey()) || !(e.getValue() instanceof String value)
                    || !ON_PROMPT_VALUES.contains(value.strip().toLowerCase(Locale.ROOT))) {
                throw r.error("onPrompt.unexpected must be fail or pack, found " + Json.write(onPrompt));
            }
        }
    }

    private static Set<String> stepIds(List<Step> steps) {
        Set<String> ids = new HashSet<>();
        for (Step step : steps) {
            ids.add(step.id());
        }
        return ids;
    }

    /** Deep unmodifiable copy of a JSON-like value (maps keep their order and may hold null values). */
    static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), freeze(e.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(freeze(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * The first key inside a fixture value (at any depth) that looks secret, or the slot name itself when it does;
     * null when there is none. Phase 2 forbids such fixtures (phase2-contracts.md 3.1 rule 5).
     */
    static String secretFixtureKey(String slotName, Object value) {
        if (SecretKeys.looksSecret(slotName)) {
            return slotName;
        }
        return secretKey(value, 0);
    }

    private static String secretKey(Object value, int depth) {
        if (depth > 200) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (SecretKeys.looksSecret(key)) {
                    return key;
                }
                String inner = secretKey(e.getValue(), depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                String inner = secretKey(item, depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** Typed access to plan JSON with errors that name the scenario. */
    private static final class PlanReader {

        private static final Set<String> FIXTURE_KEYS = Set.of("name", "kind", "value", "credential");
        private static final Set<String> QUERY_KEYS = Set.of("name", "kind", "query", "args", "strategy", "verify",
                "credential");
        private static final Set<String> CAPTURE_KEYS = Set.of("name", "kind", "step", "fields");
        private static final Set<String> ORACLE_KEYS = Set.of("name", "fn", "args", "mustEqual", "crossCheck");
        private static final Set<String> ROW_KEYS = Set.of("db", "source", "sql", "params", "rows", "expect",
                "aggregates", "capability");
        private static final Set<String> CROSS_CHECK_KEYS = Set.of("check", "tolerance", "capability");
        private static final Set<String> EXPECTATION_KEYS = Set.of("path", "matcher", "capability");

        private String id;
        /** The plan's own version (1 to 7); keys of a later version are refused. */
        private int version = VERSION;

        /** Errors name the scenario id as soon as the JSON holds one. */
        PlanReader(Map<String, Object> json) {
            Object rawId = json.get("scenarioId");
            this.id = rawId instanceof String s ? s : null;
        }

        ScenarioException error(String reason) {
            return new ScenarioException(id, 0, "invalid plan: " + reason);
        }

        String str(Map<String, Object> json, String key) throws ScenarioException {
            if (!(json.get(key) instanceof String s)) {
                throw error("'" + key + "' must be a string");
            }
            return s;
        }

        long num(Map<String, Object> json, String key) throws ScenarioException {
            if (!(json.get(key) instanceof Long n)) {
                throw error("'" + key + "' must be an integer");
            }
            return n;
        }

        long positive(Map<String, Object> json, String key) throws ScenarioException {
            long n = num(json, key);
            if (n <= 0) {
                throw error("'" + key + "' must be positive, found " + n);
            }
            return n;
        }

        Map<String, Object> object(Map<String, Object> json, String key) throws ScenarioException {
            if (!(json.get(key) instanceof Map<?, ?> map)) {
                throw error("'" + key + "' must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }

        List<Map<String, Object>> objects(Map<String, Object> json, String key) throws ScenarioException {
            if (!(json.get(key) instanceof List<?> list)) {
                throw error("'" + key + "' must be an array");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw error("'" + key + "' must hold objects");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                out.add(typed);
            }
            return out;
        }

        List<String> strings(Map<String, Object> json, String key) throws ScenarioException {
            if (!(json.get(key) instanceof List<?> list)) {
                throw error("'" + key + "' must be an array");
            }
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw error("'" + key + "' must hold strings");
                }
                out.add(s);
            }
            return out;
        }

        private void keys(Map<String, Object> json, Set<String> allowed, String where) throws ScenarioException {
            for (String key : json.keySet()) {
                if (!allowed.contains(key)) {
                    throw error(where + ": unknown key '" + key + "'");
                }
            }
        }

        Slot slot(Map<String, Object> s, boolean v2OrLater) throws ScenarioException {
            String name = str(s, "name");
            String kind = str(s, "kind");
            String where = "slot " + name;
            if (v2OrLater && !NAME.matcher(name).matches()) {
                throw error(where + ": the name must match [A-Za-z_][A-Za-z0-9_]*");
            }
            CredentialSpec credential = s.get("credential") == null ? null : credential(object(s, "credential"),
                    where);
            switch (kind) {
                case "fixture" -> {
                    if (v2OrLater) {
                        keys(s, FIXTURE_KEYS, where);
                        String secret = secretFixtureKey(name, s.get("value"));
                        if (secret != null) {
                            throw error(where + ": the fixture field '" + secret + "' looks secret; a fixture may not"
                                    + " hold a secret (move the value to credentials.yaml and add credential:)");
                        }
                    } else if (credential != null) {
                        throw error(where + ": 'credential' needs a version 2 plan");
                    }
                    return new Slot(name, kind, s.get("value"), null, credential, null);
                }
                case "query" -> {
                    if (!v2OrLater) {
                        throw error(where + ": kind 'query' needs a version 2 plan");
                    }
                    keys(s, QUERY_KEYS, where);
                    List<String> strategy = s.containsKey("strategy") ? strings(s, "strategy") : List.of();
                    for (String token : strategy) {
                        Strategy parsed = Strategy.ofToken(token);
                        if (parsed == null) {
                            throw error(where + ": unknown strategy '" + token + "'; strategies: "
                                    + Strategy.TOKENS);
                        }
                        if (parsed == Strategy.LLM_REASON || parsed == Strategy.LLM_VISION) {
                            throw error(where + ": strategy " + token + " is not in this version");
                        }
                        if (parsed.needsModel() && version < 5) {
                            throw error(where + ": strategy " + token + " needs a version 5 plan");
                        }
                    }
                    List<String> verify = strings(s, "verify");
                    if (verify.isEmpty()) {
                        throw error(where + ": a query slot needs at least one verifier");
                    }
                    return new Slot(name, kind, null, new QuerySpec(str(s, "query"), object(s, "args"), strategy,
                            verify), credential, null);
                }
                case "capture" -> {
                    if (!v2OrLater) {
                        throw error(where + ": kind 'capture' needs a version 2 plan");
                    }
                    keys(s, CAPTURE_KEYS, where);
                    List<String> fields = strings(s, "fields");
                    if (fields.isEmpty()) {
                        throw error(where + ": a captured slot needs at least one field");
                    }
                    return new Slot(name, kind, null, null, null, new CaptureDecl(str(s, "step"), fields));
                }
                default -> throw error(where + ": unknown kind '" + kind + "'");
            }
        }

        CredentialSpec credential(Map<String, Object> json, String where) throws ScenarioException {
            keys(json, Set.of("realm", "key"), where + " credential");
            try {
                return new CredentialSpec(str(json, "realm"), str(json, "key"));
            } catch (IllegalArgumentException e) {
                throw error(where + ": " + e.getMessage());
            }
        }

        Oracle oracle(Map<String, Object> json) throws ScenarioException {
            keys(json, ORACLE_KEYS, "oracle");
            String name = str(json, "name");
            if (!NAME.matcher(name).matches()) {
                throw error("oracle '" + name + "': the name must match [A-Za-z_][A-Za-z0-9_]*");
            }
            if (!json.containsKey("args")) {
                throw error("oracle " + name + ": 'args' is required");
            }
            CrossCheckSpec crossCheck = null;
            if (json.containsKey("crossCheck")) {
                if (version < 3) {
                    throw error("oracle " + name + ": 'crossCheck' needs a version 3 plan");
                }
                crossCheck = crossCheck(object(json, "crossCheck"), "oracle " + name);
            }
            return new Oracle(name, str(json, "fn"), json.get("args"), json.get("mustEqual"),
                    json.containsKey("mustEqual"), crossCheck);
        }

        /** {@code {check, tolerance?, capability?}}: strings, the tolerance a plain decimal of zero or more. */
        CrossCheckSpec crossCheck(Map<String, Object> json, String where) throws ScenarioException {
            keys(json, CROSS_CHECK_KEYS, where + " crossCheck");
            Object tolerance = json.get("tolerance");
            Object capability = json.get("capability");
            if (tolerance != null && !(tolerance instanceof String)) {
                throw error(where + ": the crossCheck tolerance must be a plain decimal string");
            }
            if (capability != null && !(capability instanceof String)) {
                throw error(where + ": the crossCheck capability must be a string");
            }
            try {
                return new CrossCheckSpec(str(json, "check"), (String) tolerance, (String) capability);
            } catch (IllegalArgumentException e) {
                throw error(where + ": " + e.getMessage());
            }
        }

        /** The graph holds every fixture and query slot and every oracle exactly once, and nothing else. */
        void checkGraph(List<String> graph, List<Slot> slots, List<Oracle> oracles) throws ScenarioException {
            Set<String> expected = new LinkedHashSet<>();
            for (Slot slot : slots) {
                if (!"capture".equals(slot.kind())) {
                    expected.add(slot.name());
                }
            }
            for (Oracle oracle : oracles) {
                expected.add("oracle." + oracle.name());
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String node : graph) {
                if (!seen.add(node)) {
                    throw error("resolutionGraph names '" + node + "' twice");
                }
                if (!expected.contains(node)) {
                    throw error("resolutionGraph names '" + node + "', which is not a fixture or query slot or an"
                            + " oracle of the plan");
                }
            }
            if (!seen.equals(expected)) {
                Set<String> missing = new LinkedHashSet<>(expected);
                missing.removeAll(seen);
                throw error("resolutionGraph lacks " + missing);
            }
        }

        Call call(Map<String, Object> json) throws ScenarioException {
            Sync sync;
            try {
                sync = Sync.valueOf(str(json, "sync"));
            } catch (IllegalArgumentException e) {
                throw error("unknown sync '" + json.get("sync") + "'");
            }
            Object handles = json.get("handlesPrompt");
            if (handles != null && !(handles instanceof Boolean)) {
                throw error("'handlesPrompt' must be a boolean");
            }
            String primitive = str(json, "primitive");
            if (primitive.isBlank()) {
                throw error("'primitive' must not be blank");
            }
            if (version >= 7) {
                keys(json, CALL_KEYS_V7, "call " + primitive);
            }
            AwaitSpec await = json.get("await") == null ? null : await(primitive, object(json, "await"));
            if (sync == Sync.EVENT && await == null) {
                throw error("call " + primitive + ": sync EVENT needs 'await'");
            }
            if (sync != Sync.EVENT && await != null) {
                throw error("call " + primitive + ": 'await' needs sync EVENT, found " + sync);
            }
            CaptureSpec capture = null;
            if (json.get("capture") != null) {
                Map<String, Object> c = object(json, "capture");
                keys(c, Set.of("slot", "fields"), "call " + primitive + " capture");
                Map<String, String> fields = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : object(c, "fields").entrySet()) {
                    if (!(e.getValue() instanceof String path)) {
                        throw error("call " + primitive + " capture: field '" + e.getKey() + "' needs a path");
                    }
                    fields.put(e.getKey(), path);
                }
                try {
                    capture = CaptureSpec.of(str(c, "slot"), fields);
                } catch (IllegalArgumentException e) {
                    throw error("call " + primitive + ": " + e.getMessage());
                }
                if (sync != Sync.EVENT && !"sync.await".equals(primitive)) {
                    throw error("call " + primitive + ": 'capture' needs sync EVENT or sync.await");
                }
            }
            Guard guard = null;
            if (json.get("guard") != null) {
                if (version < 6) {
                    throw error("call " + primitive + ": 'guard' needs a version 6 plan");
                }
                Map<String, Object> g = object(json, "guard");
                keys(g, Set.of("label"), "call " + primitive + " guard");
                if (!"swing.pointer".equals(primitive)) {
                    throw error("call " + primitive + ": a guard is allowed on swing.pointer only");
                }
                try {
                    guard = new Guard(str(g, "label"));
                } catch (IllegalArgumentException e) {
                    throw error("call " + primitive + ": " + e.getMessage());
                }
            }
            syncAwaitMatch(primitive, object(json, "args"));
            Set<String> accept = callAccept(primitive, json);
            List<ai.skillroy.testjava.scenario.pack.Rung> rungs = callRungs(primitive, json);
            String minRung = null;
            if (json.get("minRung") != null) {
                if (version < 7) {
                    throw error("call " + primitive + ": 'minRung' needs a version 7 plan");
                }
                minRung = str(json, "minRung");
            }
            try {
                return new Call(primitive, object(json, "args"), sync, positive(json, "timeoutMs"), await,
                        Boolean.TRUE.equals(handles), capture, guard, accept, rungs, minRung);
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        }

        /** A call's {@code accept} (phase8-contracts.md 7.3; version 7): a list of distinct warning codes. */
        private Set<String> callAccept(String primitive, Map<String, Object> json) throws ScenarioException {
            if (json.get("accept") == null) {
                return Set.of();
            }
            if (version < 7) {
                throw error("call " + primitive + ": 'accept' needs a version 7 plan");
            }
            if (!(json.get("accept") instanceof List<?> codes) || codes.isEmpty()) {
                throw error("call " + primitive + ": 'accept' must be a non-empty list of warning codes");
            }
            Set<String> out = new LinkedHashSet<>();
            for (Object code : codes) {
                if (!(code instanceof String text) || !ai.skillroy.testjava.protocol.CallWarning.isValidCode(text)
                        || !out.add(text)) {
                    throw error("call " + primitive + ": 'accept' holds " + code + ", which is not a distinct warning"
                            + " code");
                }
            }
            return out;
        }

        /** A call's {@code rungs} (phase8-contracts.md 7.6; version 7): a list of {@code {name, capability}}. */
        private List<ai.skillroy.testjava.scenario.pack.Rung> callRungs(String primitive, Map<String, Object> json)
                throws ScenarioException {
            if (json.get("rungs") == null) {
                return List.of();
            }
            if (version < 7) {
                throw error("call " + primitive + ": 'rungs' needs a version 7 plan");
            }
            if (!(json.get("rungs") instanceof List<?> raw)) {
                throw error("call " + primitive + ": 'rungs' must be a list of {name, capability}");
            }
            List<ai.skillroy.testjava.scenario.pack.Rung> out = new ArrayList<>();
            for (Object item : raw) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw error("call " + primitive + ": 'rungs' must be a list of {name, capability}");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> rung = (Map<String, Object>) m;
                keys(rung, Set.of("name", "capability"), "call " + primitive + " rung");
                Object capability = rung.get("capability");
                if (capability != null && !(capability instanceof String)) {
                    throw error("call " + primitive + ": a rung's 'capability' must be a string or null");
                }
                try {
                    out.add(new ai.skillroy.testjava.scenario.pack.Rung(str(rung, "name"), (String) capability));
                } catch (IllegalArgumentException e) {
                    throw error("call " + primitive + ": " + e.getMessage());
                }
            }
            return out;
        }

        /**
         * The {@code match} argument of a {@code sync.await} call (phase8-contracts.md 8.9): the rules of an await's
         * match ({@link AwaitRules#checkMatch}), and an operator object only from version 7 on.
         */
        private void syncAwaitMatch(String primitive, Map<String, Object> args) throws ScenarioException {
            if (!"sync.await".equals(primitive) || !(args.get("match") instanceof Map<?, ?> raw)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> match = (Map<String, Object>) raw;
            if (version < 7 && PayloadMatch.usesOperator(match)) {
                throw error("call " + primitive + " await: {has} needs a version 7 plan");
            }
            String problem = AwaitRules.checkMatch(match);
            if (problem != null) {
                throw error("call " + primitive + ": " + problem);
            }
        }

        /**
         * A step's {@code facts} (phase8-contracts.md 8.7; version 7): exactly {@code slot} and {@code fields} on a
         * {@code captureFacts} step without calls or checks, each field exactly one of {@code path} and {@code size}
         * holding an expectation path; null for every other step. Below version 7 the verb and the member are refused.
         */
        FactCapture facts(Map<String, Object> s, String id, String verb, List<Call> calls, List<Check> expect)
                throws ScenarioException {
            boolean capture = PHASE8_VERBS.contains(verb);
            boolean present = s.containsKey("facts");
            if (version < 7) {
                if (capture) {
                    throw error("the verb '" + verb + "' needs a version 7 plan");
                }
                if (present) {
                    throw error("step " + id + ": 'facts' needs a version 7 plan");
                }
                return null;
            }
            if (!present) {
                if (capture) {
                    throw error("step " + id + ": " + CAPTURE_FACTS + " needs 'facts'");
                }
                return null;
            }
            if (!capture) {
                throw error("step " + id + ": 'facts' is allowed on " + CAPTURE_FACTS + " only");
            }
            if (!calls.isEmpty() || !expect.isEmpty()) {
                throw error("step " + id + ": " + CAPTURE_FACTS + " has no calls");
            }
            String where = "step " + id + " facts";
            Map<String, Object> json = object(s, "facts");
            keys(json, Set.of("slot", "fields"), where);
            String slot = str(json, "slot");
            Map<String, FactField> fields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : object(json, "fields").entrySet()) {
                String field = where + ": field '" + e.getKey() + "'";
                if (!(e.getValue() instanceof Map<?, ?> raw) || raw.size() != 1
                        || !(raw.containsKey("path") || raw.containsKey("size"))) {
                    throw error(field + " needs exactly one of 'path' and 'size'");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) raw;
                boolean size = typed.containsKey("size");
                if (!(typed.get(size ? "size" : "path") instanceof String path)) {
                    throw error(field + ": '" + (size ? "size" : "path") + "' must be a string");
                }
                try {
                    gatePath(ExpectationPath.parse(path), field);
                    fields.put(e.getKey(), new FactField(path, size));
                } catch (IllegalArgumentException ex) {
                    throw error(field + ": " + ex.getMessage());
                }
            }
            try {
                return new FactCapture(slot, fields);
            } catch (IllegalArgumentException e) {
                throw error(where + ": " + e.getMessage());
            }
        }

        /** Refuses a path's Phase 8 item (a list filter, a dotted filter field, {@code [json]}) below version 7. */
        private void gatePath(ExpectationPath path, String where) throws ScenarioException {
            if (version < 7 && path.minPlanVersion() >= 7) {
                throw error(where + ": " + path.version7Item() + " needs a version 7 plan");
            }
        }

        /** Refuses {@code anyOf} or {@code containsWhere} inside a matcher in normal form below version 7. */
        private void gateMatcher(Object normalised, String where) throws ScenarioException {
            if (version < 7 && Matchers.minPlanVersion(normalised) >= 7) {
                throw error(where + ": matcher '" + Matchers.version7Matcher(normalised) + "' needs a version 7"
                        + " plan");
            }
        }

        /** A call's {@code await} object: {@code topics}, {@code match} (object or null), {@code timeoutMs}. */
        AwaitSpec await(String primitive, Map<String, Object> json) throws ScenarioException {
            String where = "call " + primitive + " await: ";
            for (String key : json.keySet()) {
                if (!AWAIT_KEYS.contains(key)) {
                    throw error(where + "unknown key '" + key + "'");
                }
            }
            String topics = str(json, "topics");
            if (TopicFilter.parse(topics).globs().isEmpty()) {
                throw error(where + "'topics' must name at least one topic glob");
            }
            Map<String, Object> match = json.get("match") == null ? null : object(json, "match");
            if (version < 7 && PayloadMatch.usesOperator(match)) {
                throw error(where + "{has} needs a version 7 plan");
            }
            String problem = match == null ? null : AwaitRules.checkMatch(match);
            if (problem == null) {
                problem = AwaitRules.checkTimeout(num(json, "timeoutMs"));
            }
            if (problem != null) {
                throw error(where + problem);
            }
            return new AwaitSpec(topics, match, num(json, "timeoutMs"));
        }

        /** The {@code ui} object: {@code clickRung} (ACTION or COMPONENT) and {@code strict} (boolean). */
        UiSettings ui(Map<String, Object> json) throws ScenarioException {
            for (String key : json.keySet()) {
                if (!UI_KEYS.contains(key)) {
                    throw error("ui: unknown key '" + key + "'");
                }
            }
            if (!(json.get("strict") instanceof Boolean strict)) {
                throw error("ui: 'strict' must be a boolean");
            }
            try {
                return new UiSettings(str(json, "clickRung"), strict);
            } catch (IllegalArgumentException e) {
                throw error("ui: " + e.getMessage());
            }
        }

        /**
         * The {@code web} object (version 4): {@code path} ({@code auto}, {@code cdp} or {@code agent}),
         * {@code browser} (optional: a {@code by} object with exactly one of {@code url}, {@code tag}, {@code title},
         * {@code nativeId}), {@code frozenTargets} ({@code fail} or {@code reload}; {@code close} is refused in Phase
         * 4, decision D52) and {@code trace} (a boolean), all four checked as the scenario loader checks them.
         */
        WebSettings web(Map<String, Object> json) throws ScenarioException {
            for (String key : json.keySet()) {
                if (!WEB_KEYS.contains(key)) {
                    throw error("web: unknown key '" + key + "'");
                }
            }
            WebSettings.Path path = WebGrammar.path(str(json, "path"));
            if (path == null) {
                throw error("web: 'path' must be auto, cdp or agent, found \"" + json.get("path") + "\"");
            }
            Map<String, Object> browser = null;
            if (json.containsKey("browser")) {
                String problem = WebGrammar.byProblem("browser", json.get("browser"));
                if (problem != null) {
                    throw error("web: " + problem);
                }
                browser = object(json, "browser");
            }
            String frozen = str(json, "frozenTargets");
            FrozenTargetPolicy policy = WebGrammar.frozenTargets(frozen);
            if (policy == FrozenTargetPolicy.CLOSE) {
                throw error("web: 'frozenTargets' " + WebGrammar.CLOSE_REFUSED);
            }
            if (policy == null) {
                throw error("web: 'frozenTargets' must be fail or reload, found \"" + frozen + "\"");
            }
            if (!(json.get("trace") instanceof Boolean trace)) {
                throw error("web: 'trace' must be a boolean");
            }
            return new WebSettings(path, browser, policy, WebSettings.DEFAULT.attachDeadline(), trace);
        }

        /**
         * An element of verify or expect: {@code db} makes a row check, {@code path} an expectation, {@code visual} a
         * visual check and {@code image} an image check (the last two version 6, phase7-contracts.md 8.4).
         */
        Check check(Map<String, Object> json) throws ScenarioException {
            int shapes = (json.containsKey("db") ? 1 : 0) + (json.containsKey("path") ? 1 : 0)
                    + (json.containsKey("visual") ? 1 : 0) + (json.containsKey("image") ? 1 : 0);
            if (shapes != 1) {
                throw error("a check needs exactly one of 'path' (an expectation), 'db' (a stored-row check), 'visual'"
                        + " (a visual check) and 'image' (an image check)");
            }
            if (json.containsKey("visual")) {
                return visualCheck(json);
            }
            if (json.containsKey("image")) {
                return imageCheck(json);
            }
            return json.containsKey("db") ? rowCheck(json) : expectation(json);
        }

        /** {@code {visual: {question, expect}, capability: "inference.vision"}} (version 6). */
        VisualCheck visualCheck(Map<String, Object> json) throws ScenarioException {
            if (version < 6) {
                throw error("a 'visual' check needs a version 6 plan");
            }
            keys(json, Set.of("visual", "capability"), "visual check");
            Map<String, Object> visual = object(json, "visual");
            keys(visual, Set.of("question", "expect"), "visual check");
            try {
                return new VisualCheck(str(visual, "question"), str(visual, "expect"), str(json, "capability"));
            } catch (IllegalArgumentException e) {
                throw error("visual check: " + e.getMessage());
            }
        }

        /** {@code {image: {label, masks, maxDiffPixels}}} (version 6). */
        ImageCheck imageCheck(Map<String, Object> json) throws ScenarioException {
            if (version < 6) {
                throw error("an 'image' check needs a version 6 plan");
            }
            keys(json, Set.of("image"), "image check");
            Map<String, Object> image = object(json, "image");
            keys(image, Set.of("label", "masks", "maxDiffPixels"), "image check");
            if (!(image.get("masks") instanceof List<?> masks)) {
                throw error("image check: 'masks' must be an array");
            }
            if (masks.size() > ImageMasks.MAX_MASKS) {
                // Phase 7 security fix, S119: the compiler's bound holds for a plan written by hand too.
                throw error("image check: at most " + ImageMasks.MAX_MASKS + " masks, found " + masks.size());
            }
            for (Object mask : masks) {
                String problem = ImageMasks.problem(mask);
                if (problem != null) {
                    throw error("image check: " + problem);
                }
            }
            long max = num(image, "maxDiffPixels");
            try {
                return new ImageCheck(str(image, "label"), new ArrayList<>(masks), (int) Math.min(max,
                        Integer.MAX_VALUE));
            } catch (IllegalArgumentException e) {
                throw error("image check: " + e.getMessage());
            }
        }

        Expectation expectation(Map<String, Object> json) throws ScenarioException {
            if (json.get("matcher") == null) {
                throw error("an expectation needs a non-null 'matcher'");
            }
            for (String key : json.keySet()) {
                if (!EXPECTATION_KEYS.contains(key)) {
                    throw error("expectation: unknown key '" + key + "'");
                }
            }
            Object capability = json.get("capability");
            if (capability != null && !(capability instanceof String)) {
                throw error("'capability' must be a string");
            }
            String path = str(json, "path");
            try {
                gatePath(ExpectationPath.parse(path), "expectation " + path);
                Map<String, Object> matcher = Matchers.normalise(json.get("matcher"));
                gateMatcher(matcher, "expectation " + path);
                return new Expectation(path, matcher, (String) capability);
            } catch (IllegalArgumentException e) {
                throw error("expectation " + path + ": " + e.getMessage());
            }
        }

        RowCheck rowCheck(Map<String, Object> json) throws ScenarioException {
            keys(json, ROW_KEYS, "db check");
            String table = str(json, "db");
            String where = "db check " + table;
            String sql = str(json, "sql");
            try {
                StatementGuard.check(sql);
            } catch (RpcException e) {
                throw error(where + ": the statement guard refuses its sql: " + e.getMessage());
            }
            List<RowCheck.Param> params = new ArrayList<>();
            for (Map<String, Object> p : objects(json, "params")) {
                keys(p, Set.of("kind", "value"), where + " param");
                ColumnKind kind;
                try {
                    kind = ColumnKind.valueOf(str(p, "kind"));
                } catch (IllegalArgumentException e) {
                    throw error(where + ": unknown column kind '" + p.get("kind") + "'");
                }
                Object value = p.get("value");
                if (value == null || value instanceof Map || value instanceof List) {
                    throw error(where + ": a parameter value must be a scalar or a reference");
                }
                params.add(new RowCheck.Param(kind, value));
            }
            Object capability = json.get("capability");
            if (capability != null && !(capability instanceof String)) {
                throw error(where + ": 'capability' must be a string");
            }
            if (json.get("rows") == null) {
                throw error(where + ": 'rows' is required");
            }
            Map<String, Object> aggregates = new LinkedHashMap<>();
            if (json.containsKey("aggregates")) {
                if (version < 3) {
                    throw error(where + ": 'aggregates' needs a version 3 plan");
                }
                for (Map.Entry<String, Object> e : object(json, "aggregates").entrySet()) {
                    if (RowCheck.Aggregate.parse(e.getKey()) == null) {
                        throw error(where + ": '" + e.getKey() + "' is not an aggregate; aggregates: "
                                + RowCheck.Aggregate.FORMS);
                    }
                    if (e.getValue() == null) {
                        throw error(where + ": aggregate " + e.getKey() + " needs a matcher");
                    }
                    aggregates.put(e.getKey(), e.getValue());
                }
            }
            try {
                Object rows = rowMatcher(Matchers.normalise(json.get("rows")), where);
                Map<String, Object> expect = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : object(json, "expect").entrySet()) {
                    if (e.getValue() == null) {
                        throw error(where + ": column " + e.getKey() + " needs a matcher");
                    }
                    expect.put(e.getKey(), rowMatcher(Matchers.normalise(e.getValue()), where));
                }
                Map<String, Object> normalAggregates = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : aggregates.entrySet()) {
                    normalAggregates.put(e.getKey(), rowMatcher(Matchers.normalise(e.getValue()), where));
                }
                return new RowCheck(table, str(json, "source"), sql, params, rows, expect, normalAggregates,
                        (String) capability);
            } catch (IllegalArgumentException e) {
                throw error(where + ": " + e.getMessage());
            }
        }

        /**
         * A stored-row check's matcher in normal form (its {@code rows}, a column's, an aggregate's): {@code anyOf}
         * only from version 7 on, and {@code containsWhere} never, whatever the version (phase8-contracts.md 8.4,
         * decision D600: a cell is a scalar).
         */
        private Object rowMatcher(Map<String, Object> normalised, String where) throws ScenarioException {
            if (Matchers.mentions(normalised, Matchers.CONTAINS_WHERE)) {
                throw error(where + ": '" + Matchers.CONTAINS_WHERE + "' needs a list, and a stored-row cell is a"
                        + " scalar");
            }
            gateMatcher(normalised, where);
            return normalised;
        }
    }
}
