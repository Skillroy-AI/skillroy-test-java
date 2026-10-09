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
import ai.skillroy.testjava.inference.tools.ToolProjection;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *       ({@code web.evaluate} and {@code web.waitFlag} unless a person opted in), no {@code hook.*} or {@code rule.*}
 *       primitive but the three reads {@code hook.health}, {@code hook.events} and {@code rule.list}
 *       (phase6-contracts.md 4.2). The pack's verbs are reviewed code, over primitives of any module.</li>
 *   <li>No call's arguments hold {@code confirm} or {@code _meta}: a confirmation is never a client's to give, and a
 *       call's {@code _meta} is the executor's to build.</li>
 * </ul>
 *
 * <p>The same rule decides which of the pack's verbs the server offers as tools ({@link #verbRefusal}): a verb whose
 * compiled calls reach what the server never offers is not offered at all. {@link #callable()} is what the scenario
 * JSON schema lets the base verb {@code call} name for such a host ({@code ScenarioSchema.generate(pack, primitives,
 * callable)}): a complement that tells an author, not the gate.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class PlanPolicy {

    /** The base verb {@code mark}'s primitive: an event on the agent's own bus, admitted with the offered ones. */
    public static final String TRACE_MARK = "trace.mark";

    private static final String CONFIRM = "confirm";
    private static final String META = "_meta";
    private static final String AGENT_NAMESPACE = "agent.";
    private static final String HOOK_NAMESPACE = "hook.";
    private static final String RULE_NAMESPACE = "rule.";

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
     */
    public List<String> refusals(Plan plan) {
        List<String> out = new ArrayList<>();
        for (Plan.Step step : plan.steps()) {
            boolean packVerb = packVerbs.contains(step.verb());
            for (Plan.Call call : step.calls()) {
                String why = packVerb ? packCall(call.primitive()) : baseCall(call.primitive());
                if (why == null) {
                    why = arguments(call);
                }
                if (why != null) {
                    out.add("step " + step.id() + " (" + step.verb() + (step.line() > 0 ? ", line " + step.line()
                            : "") + "): " + why);
                }
            }
        }
        int n = 0;
        for (Plan.Call call : plan.cleanup()) {
            n++;
            String why = packCall(call.primitive());
            if (why == null) {
                why = arguments(call);
            }
            if (why != null) {
                out.add("cleanup call " + n + ": " + why);
            }
        }
        return out;
    }

    /**
     * Why the pack's verb may not be offered as a tool (D96: the same rule as a scenario's pack verb): the verb is
     * compiled alone with probe arguments (an empty mapping, then placeholders for its schema's required keys, then for
     * all of them), and a compiled call or cleanup call of any probe that reaches a primitive the server never offers,
     * or carries {@code confirm} or {@code _meta}, withholds it; null when no probe reaches one. A verb that compiles
     * with no probe is judged by its calls when it runs; its arguments may still choose other calls.
     */
    public String verbRefusal(DomainPack pack, String verb) {
        List<Object> probes = new ArrayList<>();
        probes.add(new LinkedHashMap<String, Object>());
        Map<String, Object> schema;
        try {
            schema = pack.steps().argsSchema(verb);
        } catch (RuntimeException e) {
            schema = null;
        }
        if (schema != null) {
            probes.add(placeholder(schema, true, 0));
            probes.add(placeholder(schema, false, 0));
        }
        for (Object args : probes) {
            Plan plan;
            try {
                plan = PlanCompiler.compile(new Scenario("verb-probe", pack.id(), List.of(), List.of(), Map.of(),
                        List.of(), List.of(new Scenario.Step(verb, args, 0)), List.of(), Map.of(), List.of(),
                        Scenario.DEFAULT_STEP_TIMEOUT_MS, "verb probe", "probe"), pack);
            } catch (ScenarioException | RuntimeException e) {
                continue;
            }
            for (Plan.Step step : plan.steps()) {
                for (Plan.Call call : step.calls()) {
                    String why = packCall(call.primitive());
                    why = why != null ? why : arguments(call);
                    if (why != null) {
                        return why;
                    }
                }
            }
            for (Plan.Call call : plan.cleanup()) {
                String why = packCall(call.primitive());
                why = why != null ? why : arguments(call);
                if (why != null) {
                    return "its cleanup " + why;
                }
            }
        }
        return null;
    }

    /**
     * A value of the schema's shape for a probe: the first of an enum or the const, the first branch that is not null,
     * an object with its required keys (or all of them), an empty array, {@code 1}, {@code false}, or a text.
     */
    private static Object placeholder(Object schema, boolean requiredOnly, int depth) {
        if (depth > 8 || !(schema instanceof Map<?, ?> s)) {
            return "x";
        }
        if (s.get("enum") instanceof List<?> values) {
            for (Object v : values) {
                if (v != null) {
                    return v;
                }
            }
        }
        if (s.containsKey("const")) {
            return s.get("const");
        }
        for (String combined : List.of("anyOf", "oneOf")) {
            if (s.get(combined) instanceof List<?> branches) {
                for (Object branch : branches) {
                    if (!(branch instanceof Map<?, ?> b) || !"null".equals(b.get("type"))) {
                        return placeholder(branch, requiredOnly, depth + 1);
                    }
                }
            }
        }
        Object type = s.get("type");
        if (type instanceof List<?> types) {
            Object first = "string";
            for (Object t : types) {
                if (!"null".equals(t)) {
                    first = t;
                    break;
                }
            }
            type = first;
        }
        if ("object".equals(type) || type == null && s.get("properties") instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (s.get("properties") instanceof Map<?, ?> properties) {
                List<?> required = s.get("required") instanceof List<?> r ? r : List.of();
                properties.forEach((k, v) -> {
                    if (!requiredOnly || required.contains(k)) {
                        out.put(String.valueOf(k), placeholder(v, requiredOnly, depth + 1));
                    }
                });
            }
            return out;
        }
        return switch (String.valueOf(type)) {
            case "array" -> new ArrayList<>();
            case "integer", "number" -> 1L;
            case "boolean" -> false;
            default -> "x";
        };
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

    /** Why a primitive is never run on a client's behalf, or null. */
    private String never(String primitive) {
        PrimitiveDescriptor d = catalogue.get(primitive);
        if (d != null && d.destructive()) {
            return "calls the destructive primitive " + primitive + ", which is never run for a client (a person"
                    + " runs it with skillroy call --confirm)";
        }
        if (primitive.startsWith(AGENT_NAMESPACE)) {
            return "calls " + primitive + ": the agent's own primitives are never offered";
        }
        if ((primitive.startsWith(HOOK_NAMESPACE) || primitive.startsWith(RULE_NAMESPACE))
                && !ToolProjection.HOOK_READS.contains(primitive)) {
            // phase6-contracts.md 4.2: a hook spec or a rule is a pack author's or a person's, never a client's.
            return "calls " + primitive + ": hook and rule primitives other than " + String.join(", ",
                    new TreeSet<>(ToolProjection.HOOK_READS)) + " are never run for a client (the pack's catalogue"
                    + " is installed by the server at its start under --hooks)";
        }
        if (ToolProjection.DATA_QUERY.equals(primitive)) {
            return "calls data.query, which never runs through a scenario here: data_query reads through SqlGuard";
        }
        if (excluded.contains(primitive)) {
            return "calls " + primitive + ", which this server does not offer"
                    + (ToolProjection.SCRIPT_PRIMITIVES.contains(primitive) ? " (it runs a script in the target's"
                    + " pages: a person opts in when starting the server, skillroy mcp --allow-evaluate)" : "");
        }
        return null;
    }

    /** A call whose arguments carry {@code confirm} or {@code _meta}. */
    private static String arguments(Plan.Call call) {
        if (call.args().containsKey(CONFIRM)) {
            return "the arguments of " + call.primitive() + " carry 'confirm': a confirmation is never a client's to"
                    + " give";
        }
        if (call.args().containsKey(META)) {
            return "the arguments of " + call.primitive() + " carry '_meta', which only the executor builds";
        }
        return null;
    }
}
