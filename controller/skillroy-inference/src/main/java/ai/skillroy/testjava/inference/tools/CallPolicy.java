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

package ai.skillroy.testjava.inference.tools;

import ai.skillroy.testjava.protocol.PrimitiveDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rule "never for a client" of a call that a pack verb compiles to (phase5-contracts.md 9.3, 12.4; decisions D96
 * and D158, phase6 security fixes S57 and S62), shared by the two hosts that let a client or a model call a pack verb:
 * the MCP server ({@code PlanPolicy}, whose {@code never} and {@code arguments} delegate here) and the session service
 * (its verb tools, checked at start with probe arguments and at call time on the calls the verb compiles to with the
 * model's real arguments). A pack's verbs are reviewed code over any module's primitives, so a verb's call is refused
 * only when it reaches what is never run on a client's or a model's behalf: a destructive primitive, anything of
 * {@code agent.*}, a {@code hook.*} or {@code rule.*} primitive other than the three reads
 * ({@link ToolProjection#HOOK_READS}), {@code data.query} (read through the guarded {@code data_query} only), or what
 * the host's policy excludes ({@code web.evaluate} and {@code web.waitFlag} unless a person opted in, and then also any
 * primitive whose descriptor carries the {@code script} flag, D156); and a call
 * whose arguments carry {@code confirm} or {@code _meta}. Every reason names the primitive, never a value.
 *
 * <p>Stateless; safe from any thread.
 */
public final class CallPolicy {

    private static final String CONFIRM = "confirm";
    private static final String META = "_meta";
    private static final String AGENT_NAMESPACE = "agent.";
    private static final String HOOK_NAMESPACE = "hook.";
    private static final String RULE_NAMESPACE = "rule.";

    private CallPolicy() {
    }

    /**
     * Why a primitive is never run on a client's or a model's behalf, or null when it may run.
     *
     * @param d the target's descriptor of the primitive, or null when the target does not list it
     * @param primitive the primitive's name
     * @param excluded what the host's policy excludes ({@code ToolProjection.Policy.excluded()})
     */
    public static String never(PrimitiveDescriptor d, String primitive, Set<String> excluded) {
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
                    + " is installed by the session's open under --hooks)";
        }
        if (ToolProjection.DATA_QUERY.equals(primitive)) {
            return "calls data.query, which never runs through a scenario here: data_query reads through SqlGuard";
        }
        if (excluded != null && excluded.contains(primitive)) {
            return "calls " + primitive + ", which this server does not offer"
                    + (ToolProjection.SCRIPT_PRIMITIVES.contains(primitive) ? " (it runs a script in the target's"
                    + " pages: a person opts in when starting the server, skillroy mcp --allow-evaluate)" : "");
        }
        return scriptRefusal(d, primitive, excluded);
    }

    /**
     * A primitive whose descriptor carries the {@code script} flag, for a host whose policy offers no script primitive
     * (it excludes every one of {@link ToolProjection#SCRIPT_PRIMITIVES}; phase6-contracts.md 12.9, D156; security fix
     * S42): refused as the named ones are, so that a third-party adapter's or a later primitive that runs a caller's
     * script is never run for a client or a model without a person's opt-in; null otherwise.
     */
    static String scriptRefusal(PrimitiveDescriptor d, String primitive, Set<String> excluded) {
        if (d == null || !d.script() || excluded == null || !excluded.containsAll(ToolProjection.SCRIPT_PRIMITIVES)) {
            return null;
        }
        return "calls " + primitive + ", which this server does not offer (it runs a script in the target: a person"
                + " opts in when starting the server, skillroy mcp --allow-evaluate)";
    }

    /** Why a call's arguments may not be sent for a client or a model ({@code confirm}, {@code _meta}), or null. */
    public static String arguments(String primitive, Map<String, ?> args) {
        if (args == null) {
            return null;
        }
        if (args.containsKey(CONFIRM)) {
            return "the arguments of " + primitive + " carry 'confirm': a confirmation is never a client's to"
                    + " give";
        }
        if (args.containsKey(META)) {
            return "the arguments of " + primitive + " carry '_meta', which only the executor builds";
        }
        return null;
    }

    /**
     * Why each of these calls may not run for a client or a model: one line per call that may not ({@link #never}, then
     * {@link #arguments}), the cleanup calls numbered ("cleanup call 2: …"); empty when every call may run.
     *
     * @param catalogue the target's descriptors by primitive name
     * @param calls the calls of a step, as {@code (primitive, args)} pairs
     * @param cleanup the cleanup calls, likewise
     * @param excluded what the host's policy excludes
     */
    public static List<String> refusals(Map<String, PrimitiveDescriptor> catalogue, List<Call> calls,
                                        List<Call> cleanup, Set<String> excluded) {
        List<String> out = new ArrayList<>();
        for (Call call : calls) {
            String why = why(catalogue, call, excluded);
            if (why != null) {
                out.add(why);
            }
        }
        int n = 0;
        for (Call call : cleanup) {
            n++;
            String why = why(catalogue, call, excluded);
            if (why != null) {
                out.add("cleanup call " + n + ": " + why);
            }
        }
        return out;
    }

    private static String why(Map<String, PrimitiveDescriptor> catalogue, Call call, Set<String> excluded) {
        String why = never(catalogue.get(call.primitive()), call.primitive(), excluded);
        return why != null ? why : arguments(call.primitive(), call.args());
    }

    /**
     * The probe arguments a host compiles a verb with at its start to decide whether to offer it (D96): an empty
     * mapping, then, when the verb has an argument schema, placeholders for its required keys, then for all of them.
     * A verb whose calls depend on its arguments is still checked at call time (D158).
     *
     * @param schema the verb's argument schema, or null
     */
    public static List<Object> probes(Map<String, Object> schema) {
        List<Object> probes = new ArrayList<>();
        probes.add(new java.util.LinkedHashMap<String, Object>());
        if (schema != null) {
            probes.add(placeholder(schema, true, 0));
            probes.add(placeholder(schema, false, 0));
        }
        return probes;
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
            Map<String, Object> out = new java.util.LinkedHashMap<>();
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

    /**
     * One call to judge: the primitive and its arguments as compiled.
     *
     * @param primitive the primitive's name
     * @param args its arguments; null means none
     */
    public record Call(String primitive, Map<String, ?> args) {
    }
}
