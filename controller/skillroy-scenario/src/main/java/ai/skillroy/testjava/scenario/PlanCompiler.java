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

import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CaptureSpec;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.RecordCodec;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compiles scenario YAML into a {@link Plan} (contract section 8.2, phase2-contracts.md 3 and 5). Deterministic: the
 * same YAML and pack version produce a byte-identical {@code plan.json}, because nothing time-, path-, JVM- or
 * environment-dependent goes into the plan and the keys of every call's and query's args are written sorted.
 *
 * <p>Verb lookup: the pack's {@link StepLibrary} first, then {@link BaseSteps}; {@code expect} is always the base verb
 * and compiles to no calls and a list of checks attached to the step. An unknown verb is a
 * {@link ScenarioException} listing the available verbs. A call's non-positive timeout becomes the step timeout.
 * A call with sync {@code EVENT} keeps its {@link AwaitSpec} with the match keys sorted, the match values checked to
 * be scalars and a 0 await timeout replaced by the step timeout (at most 600 s). Expectation paths are checked
 * against the grammar and the known fact roots (pack roots, then {@link BaseFacts}); matchers are put in normal form.
 *
 * <p>Phase 2 (phase2-contracts.md 3): query slots are checked against the pack's {@link ResolutionCatalogue} (kind,
 * argument shape through the kind's codec, strategy tokens the pack offers, verifier ids, at least one verifier per
 * query slot); a fixture field whose key looks secret is refused; oracles name a function of the pack; the tier (c)
 * strategies ({@code llm-reason}, {@code llm-vision}) are refused; slots and oracles form one graph whose evaluation
 * order is written as {@code resolutionGraph} (among the nodes that are ready, the one written first comes first;
 * {@code resolve} before
 * {@code oracle}) and a cycle is an error naming it; a captured slot is declared by the call that captures it and may
 * be referenced only in later steps and in {@code verify}, and only by its declared fields; {@code db.<table>} checks
 * are compiled to guarded SQL from the pack's {@code StoredTable}; the capability ids the slots' verifiers require are
 * added to {@code requires}. Reserved: the verb names {@code aiAct}, {@code aiExpect}, {@code pointer} and
 * {@code expectImage}, the fact root {@code db}, and the pack id prefix {@code inference}.
 *
 * <p>Phase 3 (phase3-contracts.md 3, 4): an oracle's {@code crossCheck} names a {@code CrossCheck} the pack declares
 * for the oracle's function, its tolerance is a decimal of zero or more written as a plain decimal string and only for
 * a check that accepts one, and an unguarded check's {@code requires()} join {@code requires} (a guarded one adds
 * nothing); a {@code db} check's {@code sum(c)}, {@code min(c)} and {@code max(c)} keys are aggregates over an
 * {@code INTEGER} or {@code DECIMAL} column, written as {@code aggregates} with sorted keys, while any other key with
 * the form of a function call is refused; {@code $env.now} is refused (decision D39).
 *
 * <p>Phase 4 (phase4-contracts.md 7): the scenario's {@code web} key is copied into the plan (only when the YAML has
 * it, as {@code ui} is); a scenario with a base web verb ({@code webClick}, {@code webFill}, {@code webDispatch},
 * {@code webWaitFlag}, {@code webResize}, {@code webTap}, by name, also when a pack overrides it) gets
 * {@code web.evaluate} and {@code web.browsers} added to {@code requires}, and one with {@code web.path: cdp} gets
 * {@code web.cdp}, with a compile-time note ({@link #compileWithNotes}) when its {@code requires:} did not list it.
 *
 * <p>Phase 5 (phase5-contracts.md 13.1, 13.2): the strategy token {@code llm-sql} is accepted on a query slot when the
 * pack declares at least one {@code SqlAllowList} (the model-written SQL is contributed at bind time by the
 * inference module, not by the pack's catalogue), and is a compile error naming the pack otherwise; the plan's
 * {@code inference} is {@code bind} when a slot names it ({@link Plan#inferenceOf}). The base verb {@code call} (P1
 * 8.3, unchanged) is reserved: a pack may not declare a verb of that name, since an exported session step relies on
 * it. Plans were written as version 5 (version 6 since Phase 7). A {@code _meta} key at any depth of a step's
 * arguments (every verb but
 * {@code expect}) or of a call a verb compiles to, cleanup calls and a session's raw call included, is a compile
 * error naming its path: a call's {@code _meta} is the executor's, built from the call's options (13.1, the fix wave).
 *
 * <p>Phase 7 (phase7-contracts.md 8.1, 8.2, decisions D310, D325): the four verbs {@code aiAct}, {@code aiExpect},
 * {@code pointer} and {@code expectImage} are base verbs (still reserved for a pack). {@code aiAct} compiles to no call
 * and makes the plan's {@code inference} {@code run}; with {@code tier: V3} it adds {@code swing.pointer.robot} to
 * {@code requires} with a note. {@code aiExpect} compiles to a step with one {@link VisualCheck} and no call.
 * {@code pointer} compiles to {@code swing.pointer}'s component form; {@code via: ROBOT} adds
 * {@code swing.pointer.robot} to {@code requires} with a note, and {@code guard} puts a {@link Plan.Guard} on the call.
 * {@code expectImage} compiles to one {@code swing.capture} call and one {@link ImageCheck}. The labels of every
 * image check and guard are the plan's {@code images} (unique in the scenario). Plans are written as version 6.
 *
 * <p>Stateless; safe from any thread, provided the pack's step library is.
 */
public final class PlanCompiler {

    /**
     * Verb names a pack verb may not take: the four Phase 7 base verbs (reserved since decision B16, built in Phase 7,
     * phase7-contracts.md 8.1) and the base verb {@code call} (phase5-contracts.md 13.1), which an exported session step
     * relies on.
     */
    public static final Set<String> RESERVED_VERBS = Set.of("aiAct", "aiExpect", "pointer", "expectImage", "call");

    private PlanCompiler() {
    }

    /**
     * @throws ScenarioException if the YAML cannot be read or compiled with this pack
     */
    public static Plan compile(Path yaml, DomainPack pack) throws ScenarioException {
        return compile(ScenarioLoader.load(yaml), pack);
    }

    /**
     * Compiles YAML that is not a file (a class path resource, for instance).
     *
     * @param sourceName the file name, for messages only
     * @throws ScenarioException if the YAML cannot be compiled with this pack
     */
    public static Plan compile(byte[] yaml, String sourceName, DomainPack pack) throws ScenarioException {
        return compile(ScenarioLoader.load(yaml, sourceName), pack);
    }

    /**
     * Compiles with the pack the scenario names, found with {@code ServiceLoader} on the thread's context class
     * loader (or the only pack when the scenario names none).
     *
     * @throws ScenarioException if the YAML or the pack selection is invalid
     */
    public static Plan compile(Path yaml) throws ScenarioException {
        Scenario scenario = ScenarioLoader.load(yaml);
        return compile(scenario, DomainPacks.forScenario(scenario, Thread.currentThread().getContextClassLoader()));
    }

    /**
     * Compiles a loaded scenario.
     *
     * @throws ScenarioException if a verb, path, matcher, slot, oracle or check is invalid, or the scenario names a
     *     different pack
     */
    public static Plan compile(Scenario scenario, DomainPack pack) throws ScenarioException {
        return compileWithNotes(scenario, pack).plan();
    }

    /**
     * A compiled plan and the compiler's notes: what it added or assumed that the scenario did not say, and that is
     * not an error (phase4-contracts.md 7.2: {@code web.cdp} added to {@code requires} for {@code web.path: cdp}).
     *
     * @param plan the plan, as {@link #compile(Scenario, DomainPack)} returns it
     * @param notes one sentence each, in the order they arose; empty when there is nothing to say
     */
    public record Compiled(Plan plan, List<String> notes) {

        /** Copies the notes. */
        public Compiled {
            Objects.requireNonNull(plan, "plan");
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * Compiles a loaded scenario and returns the notes with the plan; the plan is the one
     * {@link #compile(Scenario, DomainPack)} gives.
     *
     * @throws ScenarioException as {@link #compile(Scenario, DomainPack)}
     */
    public static Compiled compileWithNotes(Scenario scenario, DomainPack pack) throws ScenarioException {
        Compilation compilation = new Compilation(scenario, pack);
        Plan plan = compilation.run();
        return new Compiled(plan, compilation.notes);
    }

    /**
     * One call an interactive session runs outside any scenario (phase5-contracts.md 12.2), compiled as a step's call
     * is: its arguments and await match in sorted normal form, a 0 timeout the step timeout, ACK and READY checked
     * against the pack's sync policy, a prompt-answering call marked as the compiler marks it.
     *
     * @param sessionId the session id, which names the compilation in messages
     * @throws ScenarioException if the call cannot run with this pack
     */
    static Plan.Call sessionCall(PlannedCall call, DomainPack pack, long stepTimeoutMs, String sessionId)
            throws ScenarioException {
        Scenario scenario = new Scenario(sessionId, pack.id(), List.of(), List.of(), Map.of(), List.of(), List.of(),
                List.of(), Map.of(), List.of(), stepTimeoutMs, "session", "session", null);
        return new Compilation(scenario, pack).toCall(call, false, 0, "call");
    }

    /**
     * Compiles and writes {@code plan.json}.
     *
     * @throws IOException if the plan cannot be written
     */
    public static Plan compileTo(Path yaml, DomainPack pack, Path planFile) throws ScenarioException, IOException {
        Plan plan = compile(yaml, pack);
        plan.write(planFile);
        return plan;
    }

    /**
     * True if {@code planFile} exists, is a readable plan and was compiled from exactly these YAML bytes (its
     * {@code sourceHash} matches). A pack change is not detected; use {@link #isUpToDate(Path, Path, DomainPack)}
     * for that.
     *
     * @throws IOException if the YAML cannot be read
     */
    public static boolean isUpToDate(Path yaml, Path planFile) throws IOException {
        String hash = ScenarioLoader.sourceHash(Files.readAllBytes(yaml));
        if (!Files.isRegularFile(planFile)) {
            return false;
        }
        try {
            return hash.equals(Plan.read(planFile).sourceHash());
        } catch (ScenarioException | IOException e) {
            return false;
        }
    }

    /**
     * True if compiling {@code yaml} with {@code pack} now gives exactly the bytes of {@code planFile}.
     *
     * @throws IOException if the YAML cannot be read
     * @throws ScenarioException if the YAML does not compile
     */
    public static boolean isUpToDate(Path yaml, Path planFile, DomainPack pack) throws IOException, ScenarioException {
        String expected = compile(yaml, pack).toJsonText();
        if (!Files.isRegularFile(planFile)) {
            return false;
        }
        return expected.equals(Files.readString(planFile));
    }

    /**
     * The path of the first key {@code _meta} at any depth of a step's or a call's arguments ({@code args._meta},
     * {@code lines[0]._meta}), or null when there is none: the request's {@code _meta} is the executor's, built from a
     * call's options, and never a parameter (phase5-contracts.md 12.2, 13.1).
     *
     * @param value arguments in the JSON normal form, a tree (normalising refuses cycles and deep nesting first)
     * @param at the path of {@code value}, empty at the top
     */
    private static String metaPath(Object value, String at) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String path = at.isEmpty() ? key : at + "." + key;
                if (CallRunner.META.equals(key)) {
                    return path;
                }
                String found = metaPath(e.getValue(), path);
                if (found != null) {
                    return found;
                }
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String found = metaPath(list.get(i), at + "[" + i + "]");
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The refusal of a {@code _meta} key found at {@code path}. */
    private static String noMeta(String path) {
        return "a step's arguments may not carry _meta (at " + path + "); a call's _meta is built by the executor from"
                + " its options";
    }

    /** A captured slot as declared so far: the capturing step and the field names. */
    private record Captured(String name, int stepIndex, String stepId, Set<String> fields) {
    }

    /** One compilation; holds the per-scenario state. */
    private static final class Compilation {

        private final Scenario scenario;
        private final DomainPack pack;
        private final SyncPolicy policy;
        private final StepLibrary library;
        private final Set<String> packVerbs;
        private final Set<String> roots = new LinkedHashSet<>();
        private final List<PlannedCall> cleanups = new ArrayList<>();
        private final ResolutionCatalogue catalogue;
        private final Map<String, StoredTable> tables = new LinkedHashMap<>();
        private final Map<String, Scenario.Slot> declared = new LinkedHashMap<>();
        private final Map<String, Captured> captured = new LinkedHashMap<>();
        private final Set<String> oracleNames = new LinkedHashSet<>();
        private final List<String> notes = new ArrayList<>();
        /** Whether the pack declares an SQL allow-list (null until a slot names llm-sql). */
        private Boolean allowList;

        Compilation(Scenario scenario, DomainPack pack) throws ScenarioException {
            this.scenario = scenario;
            this.pack = pack;
            if (pack == null) {
                throw new ScenarioException(scenario.id(), 0, "no domain pack to compile with");
            }
            if (scenario.pack() != null && !scenario.pack().equals(pack.id())) {
                throw new ScenarioException(scenario.id(), 0, "the scenario names pack '" + scenario.pack()
                        + "' but is compiled with pack '" + pack.id() + "'");
            }
            if (DomainPacks.reservedId(pack.id())) {
                throw error(0, "pack id '" + pack.id() + "': the prefix 'inference' is reserved for what the"
                        + " controller contributes");
            }
            this.policy = pack.syncPolicy();
            this.library = pack.steps();
            if (policy == null || library == null) {
                throw new ScenarioException(scenario.id(), 0, "pack '" + pack.id()
                        + "' returned no sync policy or step library");
            }
            this.packVerbs = library.verbs() == null ? Set.of() : library.verbs();
            for (String verb : packVerbs) {
                if (RESERVED_VERBS.contains(verb)) {
                    throw error(0, "pack '" + pack.id() + "' declares the verb '" + verb + "', which is reserved"
                            + " (reserved verbs: " + new TreeSet<>(RESERVED_VERBS) + ")");
                }
            }
            List<FactProvider> facts = pack.facts();
            if (facts != null) {
                for (FactProvider fact : facts) {
                    if ("db".equals(fact.root())) {
                        throw error(0, "pack '" + pack.id() + "' claims the fact root 'db', which is reserved for"
                                + " stored-row checks");
                    }
                    roots.add(fact.root());
                }
            }
            roots.addAll(BaseFacts.ROOTS);
            ResolutionCatalogue read;
            List<StoredTable> stored;
            try {
                read = pack.resolution();
                stored = pack.storedTables();
            } catch (RuntimeException e) {
                throw error(0, "pack '" + pack.id() + "': its resolution catalogue or stored tables are invalid: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            this.catalogue = read == null ? ResolutionCatalogue.EMPTY : read;
            try {
                // phase6-contracts.md 5.2: a malformed hook catalogue is a compile error naming the resource, although
                // nothing of it enters the plan.
                pack.hooks();
            } catch (RuntimeException e) {
                throw error(0, "pack '" + pack.id() + "': its hook catalogue is invalid: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            if (stored != null) {
                for (StoredTable table : stored) {
                    if (table == null || tables.put(table.name(), table) != null) {
                        throw error(0, "pack '" + pack.id() + "' declares the stored table "
                                + (table == null ? "null" : table.name()) + " twice");
                    }
                }
            }
        }

        private ScenarioException error(int line, String reason) {
            return new ScenarioException(scenario.id(), line, reason);
        }

        Plan run() throws ScenarioException {
            for (Scenario.Slot slot : scenario.slots()) {
                declared.put(slot.name(), slot);
            }
            for (Scenario.OracleEntry oracle : scenario.oracles()) {
                oracleNames.add(oracle.name());
            }
            Set<String> requires = new LinkedHashSet<>(scenario.requires());
            Set<String> added = new TreeSet<>();
            List<Plan.Slot> slots = new ArrayList<>();
            for (Scenario.Slot slot : scenario.slots()) {
                slots.add("query".equals(slot.kind()) ? querySlot(slot, added) : fixtureSlot(slot));
            }
            List<Plan.Oracle> oracles = new ArrayList<>();
            for (Scenario.OracleEntry entry : scenario.oracles()) {
                oracles.add(oracle(entry, added));
            }
            List<String> graph = graph(slots, oracles);
            List<Plan.Step> steps = new ArrayList<>();
            int index = 0;
            for (Scenario.Step step : scenario.steps()) {
                index++;
                steps.add(step("s" + index, index, step));
            }
            List<Check> verify = new ArrayList<>();
            for (Scenario.Verify item : scenario.verify()) {
                verify.addAll(checks(item.expectations(), item.line(), "verify"));
            }
            List<Plan.Call> cleanup = new ArrayList<>();
            for (PlannedCall call : cleanups) {
                cleanup.add(toCall(call, false, 0, "cleanup"));
            }
            for (Captured capture : captured.values()) {
                slots.add(new Plan.Slot(capture.name(), "capture", null, null, null,
                        new Plan.CaptureDecl(capture.stepId(), new ArrayList<>(capture.fields()))));
            }
            checkReferences(slots, oracles, steps, verify, cleanup);
            webRequires(added);
            visionRequires(steps, added);
            for (String id : added) {
                if (!requires.contains(id)) {
                    requires.add(id);
                }
            }
            return new Plan(Plan.VERSION, scenario.id(), pack.id(), String.valueOf(pack.version()),
                    scenario.sourceHash(), new ArrayList<>(requires), slots, steps, verify, cleanup,
                    scenario.launchProperties(), scenario.artifacts(), scenario.onPrompt(), scenario.stepTimeoutMs(),
                    scenario.ui(), Plan.inferenceOf(slots, steps), oracles, graph, scenario.bindTimeoutMs(),
                    scenario.web(), Plan.imagesOf(steps));
        }

        /**
         * phase7-contracts.md 8.1: a {@code pointer} call with {@code via: ROBOT} and an {@code aiAct} with
         * {@code tier: V3} need {@code swing.pointer.robot}; the compiler adds it with a note (the executor refuses such
         * a plan without the person's {@code --allow-robot} before it checks {@code requires}).
         */
        private void visionRequires(List<Plan.Step> steps, Set<String> added) {
            boolean robot = false;
            for (int i = 0; i < steps.size(); i++) {
                Plan.Step step = steps.get(i);
                for (Plan.Call call : step.calls()) {
                    robot |= "swing.pointer".equals(call.primitive()) && Plan.robotValue(call.args().get("via"));
                }
                if ("aiAct".equals(step.verb()) && scenario.steps().get(i).args() instanceof Map<?, ?> a
                        && "V3".equals(a.get("tier"))) {
                    robot = true;
                }
            }
            if (robot) {
                added.add("swing.pointer.robot");
                if (!scenario.requires().contains("swing.pointer.robot")) {
                    notes.add("a step acts through Robot (pointer via: ROBOT or aiAct tier: V3): the capability"
                            + " swing.pointer.robot was added to requires; the run needs the agent option robot=true and"
                            + " a person's --allow-robot");
                }
            }
        }

        /**
         * phase4-contracts.md 7.2: a base web verb needs {@code web.evaluate} (the agent path runs the script
         * library through it) and {@code web.browsers} (the CDP path maps refs to targets through it);
         * {@code web.path: cdp} needs {@code web.cdp}, and a scenario that did not list it is told so.
         */
        private void webRequires(Set<String> added) {
            for (Scenario.Step step : scenario.steps()) {
                if (BaseSteps.WEB_VERBS.contains(step.verb())) {
                    added.add("web.browsers");
                    added.add("web.evaluate");
                    break;
                }
            }
            if (scenario.effectiveWeb().path() == WebSettings.Path.CDP) {
                added.add("web.cdp");
                if (!scenario.requires().contains("web.cdp")) {
                    notes.add("web.path is cdp: the capability web.cdp was added to requires (list it there to say"
                            + " so in the scenario)");
                }
            }
        }

        // ------------------------------------------------------------------ slots and oracles

        private Plan.Slot fixtureSlot(Scenario.Slot slot) throws ScenarioException {
            Object value = normalise(slot.value(), false, slot.line());
            String secret = Plan.secretFixtureKey(slot.name(), value);
            if (secret != null) {
                throw error(slot.line(), "slot '" + slot.name() + "': the fixture field '" + secret + "' looks"
                        + " secret; a fixture may not hold a secret. Move the value to credentials.yaml and add"
                        + " credential: {realm: <realm>, key: <id field>} to the slot");
            }
            return new Plan.Slot(slot.name(), "fixture", value, null, slot.credential(), null);
        }

        private Plan.Slot querySlot(Scenario.Slot slot, Set<String> requires) throws ScenarioException {
            int line = slot.line();
            String where = "slot '" + slot.name() + "'";
            QueryType<?, ?> type = catalogue.query(slot.query());
            if (type == null) {
                throw error(line, where + ": pack '" + pack.id() + "' has no query kind '" + slot.query() + "'"
                        + (catalogue.kinds().isEmpty() ? " (it declares none)" : "; kinds: "
                        + String.join(", ", catalogue.kinds())));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) normalise(slot.args() == null ? Map.of() : slot.args(),
                    true, line);
            List<String> problems;
            try {
                problems = type.codec().check(type.queryClass(), args);
            } catch (RuntimeException e) {
                throw error(line, where + ": the codec of kind " + type.kind() + " failed: " + e);
            }
            if (problems != null && !problems.isEmpty()) {
                throw error(line, where + ": args do not fit query " + type.kind() + ": "
                        + String.join("; ", problems));
            }
            List<Resolver<?, ?>> resolvers = catalogue.resolvers(type.kind());
            for (String token : slot.strategy()) {
                Strategy strategy = Strategy.ofToken(token);
                if (strategy == Strategy.LLM_SQL) {
                    // phase5-contracts.md 13.1: served by the inference module's contributed resolver, which needs
                    // the pack's allow-list; the pack's own catalogue never holds a model strategy.
                    if (!declaresAllowList(line)) {
                        throw error(line, where + ": pack " + pack.id() + " declares no SQL allow-list: llm-sql"
                                + " cannot be served");
                    }
                    continue;
                }
                if (strategy != null && strategy.needsModel()) {
                    throw error(line, where + ": strategy " + token + " is not in this version");
                }
                boolean offered = false;
                for (Resolver<?, ?> resolver : resolvers) {
                    offered |= resolver.strategy() == strategy;
                }
                if (!offered) {
                    Set<String> offers = new TreeSet<>();
                    for (Resolver<?, ?> resolver : resolvers) {
                        if (resolver.strategy() != null && resolver.strategy().token() != null) {
                            offers.add(resolver.strategy().token());
                        }
                    }
                    throw error(line, where + ": pack '" + pack.id() + "' has no " + token + " resolver for kind "
                            + type.kind() + "; it offers: " + (offers.isEmpty() ? "none" : String.join(", ", offers)));
                }
            }
            List<String> verify = new ArrayList<>();
            if (slot.verify().isEmpty()) {
                for (Verifier<?, ?> verifier : catalogue.verifiers(type.kind())) {
                    verify.add(verifier.id());
                }
                if (verify.isEmpty()) {
                    throw error(line, where + ": no verifier for kind " + type.kind() + "; every query slot needs one"
                            + " (name one with verify:, or the pack must declare one for the kind)");
                }
            } else {
                verify.addAll(slot.verify());
            }
            for (String id : verify) {
                Verifier<?, ?> verifier = catalogue.verifier(type.kind(), id);
                if (verifier == null) {
                    throw error(line, where + ": no verifier " + id + " for kind " + type.kind());
                }
                requires.addAll(verifier.requires());
            }
            return new Plan.Slot(slot.name(), "query", null, new Plan.QuerySpec(type.kind(), args, slot.strategy(),
                    verify), slot.credential(), null);
        }

        /** True when the pack declares at least one SQL allow-list (read once per compilation). */
        private boolean declaresAllowList(int line) throws ScenarioException {
            if (allowList == null) {
                List<SqlAllowList> lists;
                try {
                    lists = pack.sqlAllowLists();
                } catch (RuntimeException e) {
                    throw error(line, "pack '" + pack.id() + "': its SQL allow-lists are invalid: "
                            + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
                allowList = lists != null && lists.stream().anyMatch(Objects::nonNull);
            }
            return allowList;
        }

        private Plan.Oracle oracle(Scenario.OracleEntry entry, Set<String> requires) throws ScenarioException {
            Oracle<?, ?> oracle = catalogue.oracle(entry.fn());
            if (oracle == null) {
                throw error(entry.line(), "oracle '" + entry.name() + "': pack '" + pack.id() + "' has no oracle "
                        + "function '" + entry.fn() + "'" + (catalogue.oracleFns().isEmpty() ? " (it declares none)"
                        : "; functions: " + String.join(", ", catalogue.oracleFns())));
            }
            Object args = normalise(entry.args(), true, entry.line());
            if (args instanceof Map<?, ?>) {
                Type input = oracle.inputType();
                Class<?> raw = input instanceof Class<?> c ? c : null;
                if (raw != null && raw.isRecord()) {
                    List<String> problems = RecordCodec.DEFAULT.check(input, args);
                    if (!problems.isEmpty()) {
                        throw error(entry.line(), "oracle '" + entry.name() + "': args do not fit " + entry.fn()
                                + ": " + String.join("; ", problems));
                    }
                }
            }
            Object mustEqual = entry.hasMustEqual() ? normalise(entry.mustEqual(), true, entry.line()) : null;
            Plan.CrossCheckSpec crossCheck = entry.crossCheck() == null ? null : crossCheck(entry, requires);
            return new Plan.Oracle(entry.name(), entry.fn(), args, mustEqual, entry.hasMustEqual(), crossCheck);
        }

        /**
         * phase3-contracts.md 3.1: the check exists for the oracle's function, a tolerance is a decimal of zero or
         * more (written as its plain decimal string) and only for a check that accepts one, and an unguarded check's
         * {@code requires()} are added to {@code requires}.
         */
        private Plan.CrossCheckSpec crossCheck(Scenario.OracleEntry entry, Set<String> requires)
                throws ScenarioException {
            int line = entry.line();
            String where = "oracle '" + entry.name() + "'";
            Object source = entry.crossCheck();
            String id;
            Object rawTolerance = null;
            String capability = null;
            if (source instanceof Map<?, ?> map) {
                id = String.valueOf(map.get("check"));
                rawTolerance = map.get("tolerance");
                capability = map.get("capability") instanceof String c ? c : null;
            } else {
                id = String.valueOf(source);
            }
            CrossCheck<?, ?> check = catalogue.crossCheck(id);
            if (check == null || !entry.fn().equals(check.oracleFn())) {
                List<String> offers = new ArrayList<>();
                for (CrossCheck<?, ?> offered : catalogue.crossChecks(entry.fn())) {
                    offers.add(offered.id());
                }
                throw error(line, where + ": no cross-check " + id + " for oracle " + entry.fn() + "; the pack offers: "
                        + (offers.isEmpty() ? "none" : String.join(", ", offers)));
            }
            String tolerance = null;
            if (rawTolerance != null) {
                BigDecimal decimal = Values.decimal(rawTolerance);
                if (decimal == null || decimal.signum() < 0) {
                    throw error(line, where + ": the tolerance of cross-check " + id + " must be a decimal of zero or"
                            + " more, found " + ScenarioLoader.describe(rawTolerance));
                }
                boolean accepts;
                try {
                    accepts = check.acceptsTolerance();
                } catch (RuntimeException e) {
                    throw error(line, where + ": cross-check " + id + " of pack '" + pack.id() + "' failed: " + e);
                }
                if (!accepts) {
                    throw error(line, where + ": cross-check " + id + " takes no tolerance");
                }
                tolerance = decimal.toPlainString();
            }
            if (capability == null) {
                List<String> needed = check.requires();
                if (needed != null) {
                    requires.addAll(needed);
                }
            }
            try {
                return new Plan.CrossCheckSpec(id, tolerance, capability);
            } catch (IllegalArgumentException e) {
                throw error(line, where + ": " + e.getMessage());
            }
        }

        /**
         * The evaluation order of slots and oracles: a topological order of the reference graph in which, among the
         * nodes that are ready, the one written first comes first ({@code resolve} entries before {@code oracle}
         * entries). A cycle is an error that names it.
         */
        private List<String> graph(List<Plan.Slot> slots, List<Plan.Oracle> oracles) throws ScenarioException {
            Map<String, Set<String>> edges = new LinkedHashMap<>();
            Map<String, Integer> lines = new HashMap<>();
            for (Plan.Slot slot : slots) {
                Scenario.Slot source = declared.get(slot.name());
                Object template = "query".equals(slot.kind()) ? slot.query().args() : slot.value();
                edges.put(slot.name(), dependencies(References.collect(template), source.line(),
                        "slot '" + slot.name() + "'"));
                lines.put(slot.name(), source.line());
            }
            for (Plan.Oracle oracle : oracles) {
                int line = lineOfOracle(oracle.name());
                List<References.Ref> refs = new ArrayList<>(References.collect(oracle.args()));
                if (oracle.hasMustEqual()) {
                    refs.addAll(References.collect(oracle.mustEqual()));
                }
                edges.put("oracle." + oracle.name(), dependencies(refs, line, "oracle '" + oracle.name() + "'"));
                lines.put("oracle." + oracle.name(), line);
            }
            List<String> order = new ArrayList<>();
            Set<String> placed = new HashSet<>();
            while (order.size() < edges.size()) {
                String next = null;
                for (Map.Entry<String, Set<String>> e : edges.entrySet()) {
                    if (!placed.contains(e.getKey()) && placed.containsAll(e.getValue())) {
                        next = e.getKey();
                        break;
                    }
                }
                if (next == null) {
                    List<String> cycle = cycle(edges, placed);
                    throw error(lines.getOrDefault(cycle.get(0), 0), "slots and oracles reference each other in a"
                            + " cycle: " + String.join(" -> ", cycle));
                }
                order.add(next);
                placed.add(next);
            }
            return order;
        }

        private int lineOfOracle(String name) {
            for (Scenario.OracleEntry entry : scenario.oracles()) {
                if (entry.name().equals(name)) {
                    return entry.line();
                }
            }
            return 0;
        }

        /** The graph nodes a slot's or oracle's references point at; a captured slot is an error. */
        private Set<String> dependencies(List<References.Ref> refs, int line, String where) throws ScenarioException {
            Set<String> out = new LinkedHashSet<>();
            for (References.Ref ref : refs) {
                if (isOracleRef(ref)) {
                    checkOracleRef(ref, line, where);
                    out.add("oracle." + ref.fields().get(0));
                } else if (isEnvRef(ref)) {
                    checkEnvRef(ref, line, where);
                } else if (captured.containsKey(ref.slot()) || capturedLater(ref.slot())) {
                    throw error(line, where + ": " + ref.text() + " refers to a captured slot, which is bound only"
                            + " while steps run; it may be referenced in later steps and in verify");
                } else if (declared.containsKey(ref.slot())) {
                    out.add(ref.slot());
                }
            }
            return out;
        }

        /** A reference in {@code resolve} or {@code oracle} to a captured slot is a compile error (3.2). */
        private void noCapturedSlot(List<References.Ref> refs, int line, String where) throws ScenarioException {
            for (References.Ref ref : refs) {
                if (!isOracleRef(ref) && !isEnvRef(ref) && captured.containsKey(ref.slot())) {
                    throw error(line, where + ": " + ref.text() + " refers to a captured slot, which is bound only"
                            + " while steps run; it may be referenced in later steps and in verify");
                }
            }
        }

        /**
         * True when a later step captures this slot (graph edges are built before steps compile, so the steps' args
         * are scanned for a base verb's {@code capture: {slot: …}}; a pack verb's capture is checked after the steps,
         * in {@link #checkReferences}).
         */
        private boolean capturedLater(String slot) {
            for (Scenario.Step step : scenario.steps()) {
                if (mentionsCapture(step.args(), slot, 0)) {
                    return true;
                }
            }
            return false;
        }

        private boolean mentionsCapture(Object args, String slot, int depth) {
            if (depth > 4 || !(args instanceof Map<?, ?> map)) {
                return false;
            }
            if (map.get("capture") instanceof Map<?, ?> c && slot.equals(c.get("slot"))) {
                return true;
            }
            for (Object value : map.values()) {
                if (mentionsCapture(value, slot, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isOracleRef(References.Ref ref) {
            return "oracle".equals(ref.slot()) && !declared.containsKey("oracle");
        }

        private boolean isEnvRef(References.Ref ref) {
            return "env".equals(ref.slot()) && !declared.containsKey("env");
        }

        private void checkOracleRef(References.Ref ref, int line, String where) throws ScenarioException {
            if (ref.fields().isEmpty() || !oracleNames.contains(ref.fields().get(0))) {
                throw error(line, where + ": " + ref.text() + " names no oracle of the scenario"
                        + (oracleNames.isEmpty() ? " (it has none)" : "; oracles: " + oracleNames));
            }
        }

        private void checkEnvRef(References.Ref ref, int line, String where) throws ScenarioException {
            if (ref.fields().size() == 1 && "now".equals(ref.fields().get(0))) {
                // Decision D39: not provided. A date in a query's args would change its argsHash every day.
                throw error(line, where + ": " + ref.text() + ": $env has the field id only; a query that depends on"
                        + " the date reads the target's business date at bind time (see the pack's documentation)");
            }
            if (ref.fields().size() != 1 || !"id".equals(ref.fields().get(0))) {
                throw error(line, where + ": " + ref.text() + ": $env has only the field id in this version");
            }
        }

        private static List<String> cycle(Map<String, Set<String>> edges, Set<String> placed) {
            for (String start : edges.keySet()) {
                if (placed.contains(start)) {
                    continue;
                }
                List<String> path = new ArrayList<>();
                List<String> found = walk(start, edges, placed, path, new HashSet<>());
                if (found != null) {
                    return found;
                }
            }
            return List.of("?");
        }

        private static List<String> walk(String node, Map<String, Set<String>> edges, Set<String> placed,
                                         List<String> path, Set<String> done) {
            int at = path.indexOf(node);
            if (at >= 0) {
                List<String> cycle = new ArrayList<>(path.subList(at, path.size()));
                cycle.add(node);
                return cycle;
            }
            if (!done.add(node)) {
                return null;
            }
            path.add(node);
            for (String next : edges.getOrDefault(node, Set.of())) {
                if (!placed.contains(next)) {
                    List<String> found = walk(next, edges, placed, path, done);
                    if (found != null) {
                        return found;
                    }
                }
            }
            path.remove(path.size() - 1);
            return null;
        }

        // ------------------------------------------------------------------ steps

        private Plan.Step step(String id, int index, Scenario.Step step) throws ScenarioException {
            String verb = step.verb();
            int line = step.line();
            if (BaseSteps.VISION_VERBS.contains(verb)) {
                return visionStep(id, index, step);
            }
            if ("expect".equals(verb)) {
                if (!(step.args() instanceof Map<?, ?> map) || map.isEmpty()) {
                    throw new ScenarioException(scenario.id(), line, "expect: needs a mapping of path: matcher");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return new Plan.Step(id, verb, line, List.of(), checks(typed, line, "expect"));
            }
            StepLibrary chosen;
            if (packVerbs.contains(verb)) {
                chosen = library;
            } else if (BaseSteps.INSTANCE.verbs().contains(verb)) {
                chosen = BaseSteps.INSTANCE;
            } else {
                throw new ScenarioException(scenario.id(), line, "unknown verb '" + verb + "'; available verbs: "
                        + String.join(", ", BaseSteps.available(packVerbs)));
            }
            Object args = normalise(step.args(), false, line);
            String meta = metaPath(args, "");
            if (meta != null) {
                throw error(line, "verb '" + verb + "': " + noMeta(meta));
            }
            Context ctx = new Context(line);
            List<PlannedCall> planned;
            try {
                planned = chosen.compile(verb, args, ctx);
            } catch (ScenarioException e) {
                throw e.locate(scenario.id(), line);
            } catch (RuntimeException e) {
                throw new ScenarioException(scenario.id(), line, "verb '" + verb + "' failed to compile: " + e, e);
            }
            ctx.closed = true;
            List<Plan.Call> calls = new ArrayList<>();
            List<Captured> stepCaptures = new ArrayList<>();
            if (planned != null) {
                for (PlannedCall call : planned) {
                    if (call == null) {
                        throw new ScenarioException(scenario.id(), line, "verb '" + verb + "' returned a null call");
                    }
                    calls.add(toCall(call, ctx.handlers.contains(call), line, "verb '" + verb + "'"));
                    if (call.capture() != null) {
                        stepCaptures.add(capture(call.capture(), index, id, line));
                    }
                }
            }
            for (Captured capture : stepCaptures) {
                captured.put(capture.name(), capture);
            }
            cleanups.addAll(ctx.cleanups);
            return new Plan.Step(id, verb, line, calls, List.of());
        }

        /** The labels of the scenario's image checks and guards so far: each label once (phase7-contracts.md 8.1). */
        private final Set<String> imageLabels = new LinkedHashSet<>();

        /** The four Phase 7 verbs (phase7-contracts.md 8.1). */
        private Plan.Step visionStep(String id, int index, Scenario.Step step) throws ScenarioException {
            String verb = step.verb();
            int line = step.line();
            Object args = normalise(step.args(), false, line);
            String meta = metaPath(args, "");
            if (meta != null) {
                throw error(line, "verb '" + verb + "': " + noMeta(meta));
            }
            try {
                switch (verb) {
                    case "aiAct" -> {
                        BaseSteps.aiAct(args);
                        return new Plan.Step(id, verb, line, List.of(), List.of());
                    }
                    case "aiExpect" -> {
                        Map<String, Object> a = BaseSteps.aiExpect(args);
                        return new Plan.Step(id, verb, line, List.of(), List.of(new VisualCheck(
                                (String) a.get("question"), (String) a.get("expect"))));
                    }
                    case "expectImage" -> {
                        ImageCheck check = BaseSteps.expectImageCheck(args);
                        label(check.label(), line);
                        Plan.Step compiled = compiledBaseStep(id, index, verb, args, line);
                        return new Plan.Step(id, verb, line, compiled.calls(), List.of(check));
                    }
                    default -> {
                        String guard = BaseSteps.pointerGuard(args);
                        Plan.Step compiled = compiledBaseStep(id, index, verb, args, line);
                        if (guard == null) {
                            return compiled;
                        }
                        label(guard, line);
                        List<Plan.Call> calls = new ArrayList<>();
                        for (Plan.Call call : compiled.calls()) {
                            calls.add("swing.pointer".equals(call.primitive()) ? call.withGuard(new Plan.Guard(guard))
                                    : call);
                        }
                        return new Plan.Step(id, verb, line, calls, List.of());
                    }
                }
            } catch (ScenarioException e) {
                throw e.locate(scenario.id(), line);
            }
        }

        private void label(String label, int line) throws ScenarioException {
            if (!imageLabels.add(label)) {
                throw error(line, "the image label '" + label + "' is used twice; a label names one baseline or"
                        + " anchor per scenario");
            }
        }

        /** A base verb's calls compiled as {@link #step} compiles a step's calls (no captures, no cleanups). */
        private Plan.Step compiledBaseStep(String id, int index, String verb, Object args, int line)
                throws ScenarioException {
            Context ctx = new Context(line);
            List<PlannedCall> planned;
            try {
                planned = BaseSteps.INSTANCE.compile(verb, args, ctx);
            } catch (RuntimeException e) {
                throw new ScenarioException(scenario.id(), line, "verb '" + verb + "' failed to compile: " + e, e);
            }
            ctx.closed = true;
            List<Plan.Call> calls = new ArrayList<>();
            List<Captured> stepCaptures = new ArrayList<>();
            for (PlannedCall call : planned) {
                calls.add(toCall(call, ctx.handlers.contains(call), line, "verb '" + verb + "'"));
                if (call.capture() != null) {
                    stepCaptures.add(capture(call.capture(), index, id, line));
                }
            }
            for (Captured capture : stepCaptures) {
                captured.put(capture.name(), capture);
            }
            cleanups.addAll(ctx.cleanups);
            return new Plan.Step(id, verb, line, calls, List.of());
        }

        private Captured capture(CaptureSpec spec, int index, String stepId, int line) throws ScenarioException {
            String slot = spec.slot();
            if (ScenarioLoader.RESERVED_NAMES.contains(slot)) {
                throw error(line, "capture: slot name '" + slot + "' is reserved");
            }
            if (declared.containsKey(slot)) {
                throw error(line, "capture: slot '" + slot + "' is also declared in resolve; a captured slot may"
                        + " not appear there");
            }
            if (captured.containsKey(slot)) {
                throw error(line, "capture: slot '" + slot + "' is already captured by step "
                        + captured.get(slot).stepId() + "; a slot may be captured once per scenario");
            }
            return new Captured(slot, index, stepId, new TreeSet<>(spec.fields().keySet()));
        }

        private Plan.Call toCall(PlannedCall call, boolean marked, int line, String where) throws ScenarioException {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) normalise(call.args(), true, line);
            String meta = metaPath(args, "");
            if (meta != null) {
                throw new ScenarioException(scenario.id(), line, where + ": call " + call.primitive() + ": "
                        + noMeta(meta));
            }
            Sync sync = call.sync();
            if (sync == Sync.ACK && policy.ackTopic() == null) {
                throw new ScenarioException(scenario.id(), line, where + ": call " + call.primitive()
                        + " uses sync ACK but pack '" + pack.id() + "' has no ack topic");
            }
            if (sync == Sync.READY && policy.readyTopics().isEmpty()) {
                throw new ScenarioException(scenario.id(), line, where + ": call " + call.primitive()
                        + " uses sync READY but pack '" + pack.id() + "' has no ready topics");
            }
            long timeoutMs = call.timeoutMs() > 0 ? call.timeoutMs() : scenario.stepTimeoutMs();
            boolean handlesPrompt = marked || "swing.dismiss".equals(call.primitive())
                    || ("sync.await".equals(call.primitive()) && awaitsPrompt(args.get("topics")));
            AwaitSpec await = call.await() == null ? null : await(call, line, where);
            return new Plan.Call(call.primitive(), args, sync, timeoutMs, await, handlesPrompt, call.capture());
        }

        /**
         * The await spec of an EVENT call in plan form: the match in sorted JSON normal form with scalar values, and
         * a 0 timeout replaced by the step timeout (at most the agent's 600 s limit for {@code sync.await}).
         */
        private AwaitSpec await(PlannedCall call, int line, String where) throws ScenarioException {
            AwaitSpec spec = call.await();
            String prefix = where + ": call " + call.primitive() + " await: ";
            Map<String, Object> match = null;
            if (spec.match() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> normal = (Map<String, Object>) normalise(spec.match(), true, line);
                match = normal;
                String problem = AwaitRules.checkMatch(match);
                if (problem != null) {
                    throw new ScenarioException(scenario.id(), line, prefix + problem);
                }
            }
            long timeoutMs = spec.timeoutMs() > 0 ? spec.timeoutMs()
                    : Math.min(scenario.stepTimeoutMs(), AwaitRules.MAX_WAIT_MS);
            String problem = AwaitRules.checkTimeout(timeoutMs);
            if (problem != null) {
                throw new ScenarioException(scenario.id(), line, prefix + problem);
            }
            return new AwaitSpec(spec.topics(), match, timeoutMs);
        }

        private boolean awaitsPrompt(Object topics) {
            List<String> globs = new ArrayList<>();
            if (topics instanceof String s) {
                globs.addAll(TopicFilter.parse(s).globs());
            } else if (topics instanceof List<?> list) {
                for (Object t : list) {
                    globs.addAll(TopicFilter.parse(String.valueOf(t)).globs());
                }
            }
            for (String glob : globs) {
                if (References.containsReference(glob)) {
                    continue;
                }
                for (String prompt : policy.promptTopics()) {
                    if (TopicFilter.parse(glob).matches(prompt) || TopicFilter.parse(prompt).matches(glob)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // ------------------------------------------------------------------ checks

        /** The checks of an {@code expect} step or a {@code verify} entry: expectations and {@code db.*} checks. */
        private List<Check> checks(Map<String, Object> entries, int line, String where) throws ScenarioException {
            List<Check> out = new ArrayList<>();
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                String path = e.getKey();
                if (path.startsWith("db.")) {
                    out.add(rowCheck(path.substring(3), e.getValue(), line, where));
                    continue;
                }
                ExpectationPath parsed;
                try {
                    parsed = ExpectationPath.parse(path);
                } catch (IllegalArgumentException ex) {
                    throw new ScenarioException(scenario.id(), line, where + ": " + ex.getMessage());
                }
                if (!roots.contains(parsed.root())) {
                    throw new ScenarioException(scenario.id(), line, where + ": unknown fact root '" + parsed.root()
                            + "' in path '" + path + "'; roots: " + String.join(", ", roots));
                }
                Object raw = normalise(e.getValue(), false, line);
                String capability = null;
                if (raw instanceof Map<?, ?> map && map.containsKey("capability")) {
                    if (!(map.get("capability") instanceof String cap) || cap.isBlank()) {
                        throw new ScenarioException(scenario.id(), line, where + " " + path
                                + ": 'capability' must be a capability id");
                    }
                    capability = cap.strip();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rest = (Map<String, Object>) map;
                    rest.remove("capability");
                    if (rest.isEmpty()) {
                        throw new ScenarioException(scenario.id(), line, where + " " + path
                                + ": a guarded expectation needs a matcher next to 'capability'");
                    }
                }
                Object matcher;
                try {
                    matcher = Matchers.normalise(raw);
                } catch (IllegalArgumentException ex) {
                    throw new ScenarioException(scenario.id(), line, where + " " + path + ": " + ex.getMessage());
                }
                out.add(new Expectation(path, matcher, capability));
            }
            return out;
        }

        /** {@code db.<name>: {where, rows?, capability?, <column>: matcher…}} compiled to SQL (7.4). */
        private RowCheck rowCheck(String name, Object raw, int line, String where) throws ScenarioException {
            String label = where + " db." + name;
            StoredTable table = tables.get(name);
            if (table == null) {
                throw error(line, label + ": pack '" + pack.id() + "' declares no stored table '" + name + "'"
                        + (tables.isEmpty() ? " (it declares none)" : "; tables: " + String.join(", ",
                        tables.keySet())));
            }
            if (!(normalise(raw, false, line) instanceof Map<?, ?> rawSpec)) {
                throw error(line, label + ": needs a mapping {where: {…}, rows?, capability?, <column>: matcher}");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) rawSpec;
            if (!(spec.get("where") instanceof Map<?, ?> whereMap)) {
                throw error(line, label + ": 'where' is required and must be a mapping of column to value ({} for"
                        + " none)");
            }
            List<String> clauses = new ArrayList<>();
            List<RowCheck.Param> params = new ArrayList<>();
            for (Map.Entry<?, ?> e : whereMap.entrySet()) {
                String columnName = String.valueOf(e.getKey());
                StoredColumn column = column(table, columnName, line, label);
                Object value = e.getValue();
                if (value instanceof Map || value instanceof List) {
                    throw error(line, label + ": where." + columnName + " must be a scalar, null or a reference;"
                            + " comparisons other than equality are not part of this version");
                }
                if (value == null) {
                    clauses.add(column.sql() + " IS NULL");
                } else {
                    clauses.add(column.sql() + " = " + column.paramSql());
                    params.add(new RowCheck.Param(column.kind(), value));
                }
            }
            Object rows = Map.of("equals", 1L);
            boolean rowsGiven = false;
            String capability = null;
            Map<String, Object> expect = new LinkedHashMap<>();
            Map<String, Object> aggregates = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : spec.entrySet()) {
                String key = e.getKey();
                switch (key) {
                    case "where" -> {
                    }
                    case "rows" -> {
                        rowsGiven = true;
                        rows = rowsMatcher(e.getValue(), line, label);
                    }
                    case "capability" -> {
                        if (!(e.getValue() instanceof String cap) || cap.isBlank()) {
                            throw error(line, label + ": 'capability' must be a capability id");
                        }
                        capability = cap.strip();
                    }
                    default -> {
                        RowCheck.Aggregate aggregate = RowCheck.Aggregate.parse(key);
                        if (aggregate != null) {
                            aggregates.put(key, aggregate(table, aggregate, e.getValue(), line, label));
                            continue;
                        }
                        if (RowCheck.Aggregate.looksLikeCall(key)) {
                            throw error(line, label + ": " + key + " is not an aggregate; the aggregates are "
                                    + RowCheck.Aggregate.FORMS + " over an INTEGER or DECIMAL column");
                        }
                        column(table, key, line, label);
                        if (e.getValue() == null) {
                            throw error(line, label + "." + key + ": needs a matcher");
                        }
                        try {
                            expect.put(key, Matchers.normalise(e.getValue()));
                        } catch (IllegalArgumentException ex) {
                            throw error(line, label + "." + key + ": " + ex.getMessage());
                        }
                    }
                }
            }
            if (expect.isEmpty() && aggregates.isEmpty() && !rowsGiven) {
                throw error(line, label + ": name at least one column matcher, an aggregate or 'rows'");
            }
            List<String> selected = new ArrayList<>();
            for (StoredColumn column : table.columns()) {
                selected.add(column.sql());
            }
            List<String> order = new ArrayList<>();
            for (String columnName : table.orderBy()) {
                order.add(table.column(columnName).sql());
            }
            String sql = "SELECT " + String.join(", ", selected) + " FROM " + table.tableSql()
                    + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                    + " ORDER BY " + String.join(", ", order);
            try {
                StatementGuard.check(sql);
            } catch (RpcException ex) {
                throw error(line, label + ": the statement guard refuses the SQL generated from pack '" + pack.id()
                        + "' table " + name + ": " + ex.getMessage());
            }
            return new RowCheck(name, table.source(), sql, params, rows, expect, aggregates, capability);
        }

        /**
         * phase3-contracts.md 3.2: the column exists and is {@code INTEGER} or {@code DECIMAL}; the matcher in normal
         * form.
         */
        private Object aggregate(StoredTable table, RowCheck.Aggregate aggregate, Object matcher, int line,
                                 String label) throws ScenarioException {
            String key = aggregate.key();
            StoredColumn column = column(table, aggregate.column(), line, label + "." + key);
            if (column.kind() != ColumnKind.INTEGER && column.kind() != ColumnKind.DECIMAL) {
                throw error(line, label + ": " + key + ": " + column.name() + " is " + column.kind() + "; aggregates"
                        + " take INTEGER or DECIMAL columns");
            }
            if (matcher == null) {
                throw error(line, label + "." + key + ": needs a matcher");
            }
            try {
                return Matchers.normalise(matcher);
            } catch (IllegalArgumentException ex) {
                throw error(line, label + "." + key + ": " + ex.getMessage());
            }
        }

        private StoredColumn column(StoredTable table, String name, int line, String label)
                throws ScenarioException {
            StoredColumn column = table.column(name);
            if (column == null) {
                List<String> names = new ArrayList<>();
                for (StoredColumn c : table.columns()) {
                    names.add(c.name());
                }
                throw error(line, label + ": '" + name + "' is not a column of table " + table.name() + "; columns: "
                        + String.join(", ", names));
            }
            return column;
        }

        private Object rowsMatcher(Object value, int line, String label) throws ScenarioException {
            if (value instanceof Long n) {
                if (n < 0) {
                    throw error(line, label + ": 'rows' must not be negative");
                }
                return Map.of("equals", n);
            }
            if (!(value instanceof Map)) {
                throw error(line, label + ": 'rows' must be a whole number or a matcher such as {gte: 1}");
            }
            try {
                return Matchers.normalise(value);
            } catch (IllegalArgumentException ex) {
                throw error(line, label + ".rows: " + ex.getMessage());
            }
        }

        // ------------------------------------------------------------------ references

        /**
         * Checks references that can be checked at compile time: {@code $oracle.<name>} names an oracle,
         * {@code $env} has only {@code id}, a captured slot is never referenced in {@code resolve} or {@code oracle}
         * (3.2; checked again here because a pack verb's capture is known only once the steps compiled), and only in
         * steps after the capturing step and in {@code verify}, and only by its declared fields.
         */
        private void checkReferences(List<Plan.Slot> slots, List<Plan.Oracle> oracles, List<Plan.Step> steps,
                                     List<Check> verify, List<Plan.Call> cleanup) throws ScenarioException {
            for (Plan.Slot slot : slots) {
                Scenario.Slot source = declared.get(slot.name());
                if (source == null || "capture".equals(slot.kind())) {
                    continue;
                }
                Object template = "query".equals(slot.kind()) ? slot.query().args() : slot.value();
                noCapturedSlot(References.collect(template), source.line(), "slot '" + slot.name() + "'");
            }
            for (Plan.Oracle oracle : oracles) {
                List<References.Ref> refs = new ArrayList<>(References.collect(oracle.args()));
                if (oracle.hasMustEqual()) {
                    refs.addAll(References.collect(oracle.mustEqual()));
                }
                noCapturedSlot(refs, lineOfOracle(oracle.name()), "oracle '" + oracle.name() + "'");
            }
            for (int i = 0; i < steps.size(); i++) {
                Plan.Step step = steps.get(i);
                String where = "step " + step.id() + " (" + step.verb() + ")";
                for (Plan.Call call : step.calls()) {
                    checkRefs(callRefs(call), i + 1, step.line(), where);
                }
                checkRefs(checkRefs(step.expect()), i + 1, step.line(), where);
            }
            checkRefs(checkRefs(verify), Integer.MAX_VALUE, 0, "verify");
            for (Plan.Call call : cleanup) {
                checkRefs(callRefs(call), -1, 0, "cleanup call " + call.primitive());
            }
        }

        private List<References.Ref> callRefs(Plan.Call call) {
            List<References.Ref> refs = new ArrayList<>(References.collect(call.args()));
            if (call.await() != null) {
                refs.addAll(References.collect(call.await().topics()));
                refs.addAll(References.collect(call.await().match()));
            }
            return refs;
        }

        private List<References.Ref> checkRefs(List<Check> checks) {
            List<References.Ref> refs = new ArrayList<>();
            for (Check check : checks) {
                if (check instanceof Expectation e) {
                    refs.addAll(References.collectPath(e.path()));
                    refs.addAll(References.collect(e.matcher()));
                } else if (check instanceof RowCheck r) {
                    refs.addAll(References.collect(r.paramValues()));
                    refs.addAll(References.collect(r.expect()));
                    refs.addAll(References.collect(r.aggregates()));
                    refs.addAll(References.collect(r.rows()));
                }
            }
            return refs;
        }

        /**
         * @param stepIndex the 1-based index of the step the references are in; {@code Integer.MAX_VALUE} for
         *     verify, -1 for cleanup (which runs even when the capturing step did not)
         */
        private void checkRefs(List<References.Ref> refs, int stepIndex, int line, String where)
                throws ScenarioException {
            for (References.Ref ref : refs) {
                if (isOracleRef(ref)) {
                    checkOracleRef(ref, line, where);
                    continue;
                }
                if (isEnvRef(ref)) {
                    checkEnvRef(ref, line, where);
                    continue;
                }
                Captured capture = captured.get(ref.slot());
                if (capture == null) {
                    continue;
                }
                if (stepIndex <= capture.stepIndex()) {
                    throw error(line, where + ": " + ref.text() + " refers to slot '" + ref.slot() + "', which step "
                            + capture.stepId() + " captures; it may be referenced only in later steps and in verify");
                }
                if (!ref.fields().isEmpty() && !capture.fields().contains(ref.fields().get(0))) {
                    throw error(line, where + ": " + ref.text() + ": captured slot '" + ref.slot() + "' has no field '"
                            + ref.fields().get(0) + "'; fields: " + capture.fields());
                }
            }
        }

        private Object normalise(Object value, boolean sorted, int line) throws ScenarioException {
            try {
                return sorted ? Values.normaliseSorted(value) : Values.normalise(value);
            } catch (JsonException e) {
                throw new ScenarioException(scenario.id(), line, "value is not JSON-compatible: " + e.getMessage(),
                        e);
            }
        }

        /** The context handed to one verb. */
        private final class Context implements CompileContext {

            private final int line;
            private final List<PlannedCall> cleanups = new ArrayList<>();
            private final Set<PlannedCall> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
            private boolean closed;

            Context(int line) {
                this.line = line;
            }

            @Override
            public void addCleanup(PlannedCall call) {
                check();
                cleanups.add(Objects.requireNonNull(call, "call"));
            }

            @Override
            public int sourceLine() {
                return line;
            }

            @Override
            public DomainPack pack() {
                return pack;
            }

            @Override
            public long stepTimeoutMs() {
                return scenario.stepTimeoutMs();
            }

            @Override
            public UiSettings ui() {
                return scenario.effectiveUi();
            }

            @Override
            public WebSettings web() {
                return scenario.effectiveWeb();
            }

            @Override
            public void markHandlesPrompt(PlannedCall call) {
                check();
                handlers.add(Objects.requireNonNull(call, "call"));
            }

            @Override
            public SlotInfo slot(String name) {
                String bare = name != null && name.startsWith("$") ? name.substring(1) : name;
                Scenario.Slot slot = declared.get(bare);
                if (slot != null) {
                    return new SlotInfo(slot.name(), slot.kind(), slot.query(), slot.credential(), Set.of());
                }
                Captured capture = captured.get(bare);
                if (capture != null) {
                    return new SlotInfo(capture.name(), "capture", null, null, capture.fields());
                }
                return null;
            }

            private void check() {
                if (closed) {
                    throw new IllegalStateException("the compile context is only valid during compile()");
                }
            }
        }
    }
}
