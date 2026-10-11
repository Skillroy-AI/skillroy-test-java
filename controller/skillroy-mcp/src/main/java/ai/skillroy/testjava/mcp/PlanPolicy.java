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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.tools.CallPolicy;
import ai.skillroy.testjava.inference.tools.ToolProjection;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.References;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a scenario run on a client's behalf may call (phase5-contracts.md 9.3, 10.2 step 6; decision D96): the check
 * that {@code skillroy_scenario_run} and {@code skillroy author --run} make on the compiled plan before anything
 * runs, so that a scenario is never stronger than the tools the same server offers one by one (design 3.4, D76: the
 * server never confirms for a client and never runs what it does not offer).
 *
 * <ul>
 *   <li>A call of a base verb ({@code call}, the UI, web and wait verbs, {@code mark}) names a primitive the server's
 *       own projection offers as a tool, other than {@code data.query} ({@code data_query} is the guarded way to read),
 *       or {@value #TRACE_MARK}, the base verb {@code mark}'s marker on the agent's own event bus.</li>
 *   <li>A call a pack verb compiled (or a cleanup call a verb registered) names no primitive the server never offers:
 *       not a destructive one, nothing of {@code agent.*}, not {@code data.query}, nothing the policy excludes
 *       ({@code web.evaluate} and {@code web.waitFlag} unless a person opted in, and then also any primitive whose
 *       descriptor carries the {@code script} flag, phase6-contracts.md 12.9, D156), no {@code hook.*} or {@code rule.*}
 *       primitive but the three reads {@code hook.health}, {@code hook.events} and {@code rule.list}
 *       (phase6-contracts.md 4.2). The pack's verbs are reviewed code, over primitives of any module.</li>
 *   <li>No call's arguments hold {@code confirm} or {@code _meta}: a confirmation is never a client's to give, and a
 *       call's {@code _meta} is the executor's to build.</li>
 * </ul>
 *
 * <p>The same rule decides which of the pack's verbs the server offers as tools ({@link #verbRefusal}): a verb whose
 * compiled calls reach what the server never offers is not offered at all. And it is the gate of every verb tool's
 * call (phase6 security fix S57, D158): the verb is compiled with the client's real arguments
 * ({@code ExecutorSession.compile}) and its calls and cleanup calls are checked by {@link #packRefusals} before the
 * first call runs, since no finite set of probes covers a verb whose calls depend on its arguments; the probes decide
 * only the catalogue's {@code withheldVerbs}. The rule itself ({@link #never}, {@link #arguments}) is
 * {@link CallPolicy}'s, shared with the session service (S62). {@link #callable()} is what the scenario
 * JSON schema lets the base verb {@code call} name for such a host ({@code ScenarioSchema.generate(pack, primitives,
 * callable)}): a complement that tells an author, not the gate.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class PlanPolicy {

    /** The base verb {@code mark}'s primitive: an event on the agent's own bus, admitted with the offered ones. */
    public static final String TRACE_MARK = "trace.mark";

    private final Set<String> callable;
    private final Map<String, PrimitiveDescriptor> catalogue;
    private final Set<String> excluded;
    private final Set<String> packVerbs;

    private PlanPolicy(Set<String> callable, Map<String, PrimitiveDescriptor> catalogue, Set<String> excluded,
                       Set<String> packVerbs) {
        this.callable = Collections.unmodifiableSet(new TreeSet<>(callable));
        this.catalogue = Collections.unmodifiableMap(new TreeMap<>(catalogue));
        this.excluded = Set.copyOf(excluded);
        this.packVerbs = Set.copyOf(packVerbs);
    }

    /**
     * The policy of a server that projects {@code primitives} and {@code pack}'s verbs with {@code policy}, as the MCP
     * server does at start.
     *
     * @param primitives the target's catalogue ({@code GET /schema})
     * @throws IllegalArgumentException when the projection refuses (a tool name collision), as the server's start does
     */
    public static PlanPolicy of(List<PrimitiveDescriptor> primitives, DomainPack pack, ToolProjection.Policy policy) {
        List<PrimitiveDescriptor> known = new ArrayList<>();
        Map<String, PrimitiveDescriptor> byName = new TreeMap<>();
        for (PrimitiveDescriptor d : primitives == null ? List.<PrimitiveDescriptor>of() : primitives) {
            if (d != null && byName.putIfAbsent(d.name(), d) == null) {
                known.add(d);
            }
        }
        Set<String> tools = new HashSet<>();
        for (ToolSpec tool : ToolProjection.project(known, pack, policy)) {
            tools.add(tool.name());
        }
        Set<String> verbToolNames = ToolProjection.verbToolNames(pack);
        Set<String> offered = new TreeSet<>();
        for (PrimitiveDescriptor d : known) {
            try {
                if (tools.contains(ToolProjection.primitiveToolName(d.name(), verbToolNames))) {
                    offered.add(d.name());
                }
            } catch (IllegalArgumentException e) {
                // A name that cannot be a tool's is never offered.
            }
        }
        offered.remove(ToolProjection.DATA_QUERY);
        offered.add(TRACE_MARK);
        Set<String> verbs;
        try {
            verbs = pack.steps() == null || pack.steps().verbs() == null ? Set.of() : pack.steps().verbs();
        } catch (RuntimeException e) {
            verbs = Set.of();
        }
        return new PlanPolicy(offered, byName, policy.excluded(), verbs);
    }

    /** The primitives a base verb may call: the scenario schema's {@code call} enum for this host. */
    public Set<String> callable() {
        return callable;
    }

    /**
     * The descriptors of the {@link #callable()} primitives, in name order: what a host that builds the scenario schema
     * from a catalogue (the planner) is given, so that the schema's {@code call} names these only. {@value #TRACE_MARK}
     * is described here when the target's catalogue does not list it (every agent serves it).
     */
    public List<PrimitiveDescriptor> callableCatalogue() {
        Map<String, PrimitiveDescriptor> out = new TreeMap<>();
        catalogue.forEach((name, d) -> {
            if (callable.contains(name)) {
                out.put(name, d);
            }
        });
        out.putIfAbsent(TRACE_MARK, new PrimitiveDescriptor(TRACE_MARK, "Publishes a trace.mark event (the base verb"
                + " mark).", null, null, "CALLER", 10_000, null, false, false));
        return new ArrayList<>(out.values());
    }

    /**
     * Why the plan may not run on a client's behalf: one line per call that may not, in plan order, naming the step
     * (its id, verb and line) or the cleanup call and the primitive; empty when it may run. Never a value of the
     * arguments.
     *
     * <p>The scenario as written is required (Phase 7 security fix, S107: every caller passes it; the one-argument form
     * that judged a pack verb on its compiled calls only is gone), so that a pack verb's arguments as the client wrote
     * them meet the credential rule's plan form too (phase7-contracts.md 9.2, 9.4: a pass-through verb given
     * {@code $staff.password}), while the verb's own compiled field references (a {@code signOn} joining the password)
     * are not judged. Plan step {@code s<i>} is judged with the scenario's step {@code i}, the compiler's one-to-one
     * numbering (Phase 7 security fix, S102: by identity, never by source line, which two flow-style or JSON steps
     * share); a plan that does not number the scenario's steps so, in a plan that declares a credential slot, is
     * refused. The references are read with the executor's own tokenizer ({@link References#references}, S101),
     * whole-slot references included, and a value nested deeper than {@value References#MAX_DEPTH} levels is refused
     * rather than searched (S128).
     *
     * @param scenario the scenario the plan was compiled from
     * @throws NullPointerException if the plan or the scenario is null
     */
    public List<String> refusals(Plan plan, Scenario scenario) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scenario, "scenario");
        List<String> out = new ArrayList<>();
        Map<String, Set<String>> credentials = credentialSlots(plan);
        Map<String, Scenario.Step> written = new HashMap<>();
        List<Scenario.Step> source = scenario.steps();
        for (int i = 0; i < source.size(); i++) {
            written.put("s" + (i + 1), source.get(i));
        }
        boolean numbered = source.size() == plan.steps().size();
        for (Plan.Step step : plan.steps()) {
            String where = "step " + step.id() + " (" + step.verb() + (step.line() > 0 ? ", line " + step.line() : "")
                    + "): ";
            if (MODEL_VERBS.contains(step.verb())) {
                // phase7-contracts.md 9.4: a model step runs through the scenario player, never in a client's run.
                out.add(where + step.verb() + " runs through skillroy session --play, never in a client's scenario");
                continue;
            }
            boolean packVerb = packVerbs.contains(step.verb());
            boolean writtenRefused = false;
            if (packVerb && !credentials.isEmpty()) {
                Scenario.Step as = written.get(step.id());
                String why = !numbered || as == null || !step.verb().equals(as.verb())
                        ? "its arguments as written are not the scenario's step " + step.id() + ": refused, since a"
                        + " pack verb's arguments meet the credential rule as the client wrote them"
                        : credentialRefusal(as.args(), credentials, Judged.WRITTEN);
                if (why != null) {
                    out.add(where + why);
                    writtenRefused = true;
                }
            }
            for (Plan.Call call : step.calls()) {
                String why = packVerb ? packCall(call.primitive()) : baseCall(call.primitive());
                if (why == null) {
                    why = arguments(call);
                }
                if (why == null && !writtenRefused) {
                    why = credentialRefusal(call.args(), credentials, packVerb ? Judged.PACK_CALL : Judged.CALL);
                }
                if (why != null) {
                    out.add(where + why);
                }
            }
        }
        out.addAll(packRefusals(List.of(), plan.cleanup()));
        int n = 0;
        for (Plan.Call call : plan.cleanup()) {
            n++;
            String why = credentialRefusal(call.args(), credentials, Judged.PACK_CALL);
            if (why != null) {
                out.add("cleanup call " + n + " (" + call.primitive() + "): " + why);
            }
        }
        return out;
    }

    /**
     * Why each call a pack verb compiled to, and each cleanup call it registered, may not run on a client's behalf
     * (phase6 security fix S57, D158; the pack-call and argument rules of D96): one line per refused call naming its
     * primitive ("cleanup call n: …" for a cleanup call), never a value; empty when every call may run. What the verb
     * tools check at call time on the calls the verb compiles to with the client's arguments, and what
     * {@link #refusals} and {@link #verbRefusal} apply to a pack verb's calls.
     */
    public List<String> packRefusals(List<Plan.Call> calls, List<Plan.Call> cleanup) {
        return CallPolicy.refusals(catalogue, judged(calls), judged(cleanup), excluded);
    }

    private static List<CallPolicy.Call> judged(List<Plan.Call> calls) {
        List<CallPolicy.Call> out = new ArrayList<>();
        for (Plan.Call call : calls == null ? List.<Plan.Call>of() : calls) {
            out.add(new CallPolicy.Call(call.primitive(), call.args()));
        }
        return out;
    }

    /**
     * Why the pack's verb may not be offered as a tool (D96: the same rule as a scenario's pack verb): the verb is
     * compiled alone with probe arguments (an empty mapping, then placeholders for its schema's required keys, then for
     * all of them), and a compiled call or cleanup call of any probe that reaches a primitive the server never offers,
     * or carries {@code confirm} or {@code _meta}, withholds it; null when no probe reaches one. A verb that compiles
     * with no probe, or whose arguments choose other calls, is offered and judged on its calls when it is called
     * ({@link #packRefusals}, phase6 security fix S57).
     */
    public String verbRefusal(DomainPack pack, String verb) {
        Map<String, Object> schema;
        try {
            schema = pack.steps().argsSchema(verb);
        } catch (RuntimeException e) {
            schema = null;
        }
        for (Object args : CallPolicy.probes(schema)) {
            Plan plan;
            try {
                plan = PlanCompiler.compile(new Scenario("verb-probe", pack.id(), List.of(), List.of(), Map.of(),
                        List.of(), List.of(new Scenario.Step(verb, args, 0)), List.of(), Map.of(), List.of(),
                        Scenario.DEFAULT_STEP_TIMEOUT_MS, "verb probe", "probe"), pack);
            } catch (ScenarioException | RuntimeException e) {
                continue;
            }
            for (Plan.Step step : plan.steps()) {
                List<String> why = packRefusals(step.calls(), List.of());
                if (!why.isEmpty()) {
                    return why.get(0);
                }
            }
            for (Plan.Call call : plan.cleanup()) {
                List<String> why = packRefusals(List.of(call), List.of());
                if (!why.isEmpty()) {
                    return "its cleanup " + why.get(0);
                }
            }
        }
        return null;
    }

    /** A base verb's call: the primitive must be one this server offers. */
    private String baseCall(String primitive) {
        if (callable.contains(primitive)) {
            return null;
        }
        String never = never(primitive);
        if (never != null) {
            return never;
        }
        if (!catalogue.containsKey(primitive)) {
            return "calls " + primitive + ", which the target does not offer";
        }
        return "calls " + primitive + ", which this server does not offer as a tool (only the pack's domain and the"
                + " base primitives its policy offers)";
    }

    /** A pack verb's (or a cleanup) call: the primitive must not be one the server never offers. */
    private String packCall(String primitive) {
        return never(primitive);
    }

    /**
     * Why a primitive is never run on a client's behalf, or null: {@link CallPolicy#never} over this server's
     * catalogue and exclusions (phase6 security fix S62: the rule is shared with the session service).
     */
    private String never(String primitive) {
        return CallPolicy.never(catalogue.get(primitive), primitive, excluded);
    }

    /**
     * A call whose arguments carry {@code confirm} or {@code _meta}, and (phase7-contracts.md 9.4) {@code redact: false}
     * or {@code mode: ROBOT} on a capture, {@code force} on an acting primitive or {@code via: ROBOT} on a pointer: a
     * plan with them is a person's run ({@link CallPolicy#arguments}, without the robot flag).
     */
    private static String arguments(Plan.Call call) {
        return CallPolicy.arguments(call.primitive(), call.args(), false);
    }

    /** The verbs of a model's step, which a client's scenario never runs (phase7-contracts.md 9.4). */
    public static final Set<String> MODEL_VERBS = Set.of("aiAct", "aiExpect");

    /**
     * The slots the plan declares with {@code credential:}, each with the fields its own fixture value provides (none
     * for a query slot).
     */
    private static Map<String, Set<String>> credentialSlots(Plan plan) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Plan.Slot slot : plan.slots()) {
            if (slot.credential() == null) {
                continue;
            }
            Set<String> provided = new TreeSet<>();
            if ("fixture".equals(slot.kind()) && slot.value() instanceof Map<?, ?> fixture) {
                fixture.keySet().forEach(k -> provided.add(String.valueOf(k)));
            }
            out.put(slot.name(), provided);
        }
        return out;
    }

    /** What the credential rule's plan form judges in a value (Phase 7 security fix, S101). */
    enum Judged {
        /**
         * A base verb's or a raw {@code call}'s compiled arguments: every reference to a credential slot, whole or a
         * field its fixture does not provide.
         */
        CALL,
        /**
         * A pack verb's arguments as the client wrote them: a field its fixture does not provide, and the slot as a
         * whole embedded in a longer text; a string that is exactly {@code $<slot>} hands the slot to the verb (the
         * verb's own join, {@code signOn {staff: $staff}}, D427).
         */
        WRITTEN,
        /**
         * A pack verb's compiled call or a cleanup call: the slot as a whole, which reaches the agent with its joined
         * secret whatever the verb meant; the verb's own field references are its reviewed code (D427).
         */
        PACK_CALL
    }

    /**
     * The credential rule's plan form (phase7-contracts.md 9.2, 9.4; D342, D427): a reference in the value to a slot the
     * plan declares with {@code credential:} that reaches its joined secret, judged as {@code judged} says, refused
     * naming the slot (and the field), never a value; null when none does. The references are read with the executor's
     * own tokenizer, so that every spelling that resolves to the slot as a whole ({@code $staff}, {@code x$staff},
     * {@code x$staff.}, {@code x$staff..password}) is one (S101); a value nested deeper than
     * {@value References#MAX_DEPTH} levels is refused rather than searched (S128).
     */
    static String credentialRefusal(Object args, Map<String, Set<String>> credentials, Judged judged) {
        if (credentials.isEmpty()) {
            return null;
        }
        List<References.Reference> refs;
        try {
            refs = References.references(args);
        } catch (IllegalArgumentException e) {
            return "holds a value nested deeper than " + References.MAX_DEPTH + " levels in a scenario with a"
                    + " credential slot: refused rather than searched";
        }
        for (References.Reference ref : refs) {
            Set<String> provided = credentials.get(ref.slot());
            if (provided == null) {
                continue;
            }
            if (ref.fields().isEmpty()) {
                if (judged == Judged.WRITTEN && ref.whole()) {
                    continue;
                }
                return "names the credential slot '" + ref.slot() + "' as a whole, whose value carries its joined"
                        + " secret: a credential is never used on a client's behalf (the person signs on, or a pack"
                        + " verb joins the field it names)";
            }
            if (judged != Judged.PACK_CALL && !provided.contains(ref.fields().get(0))) {
                return "names the field '" + ref.fields().get(0) + "' of the credential slot '" + ref.slot() + "',"
                        + " which its fixture does not provide: a credential is never used on a client's behalf (the"
                        + " person signs on, or a pack verb joins it)";
            }
        }
        return null;
    }
}
