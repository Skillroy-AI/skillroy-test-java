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

import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The primitives and the pack's verbs as tools (phase5-contracts.md 9.3, 12.4 step 1): one projection, so that a
 * scenario authored over MCP and an interactive session's actions share one vocabulary. The MCP server and the session
 * service consume it; it runs nothing itself.
 *
 * <p>Names use {@code [a-z0-9_]} only, at most 64 characters: a primitive {@code a.b.c} becomes {@code a_b_c} (a
 * camel-case part in snake case: {@code app.getState} is {@code app_get_state}); a verb {@code signOn} of pack
 * {@code lending} becomes {@code lending_sign_on}; a workflow tool is {@code skillroy_*}. A primitive whose name a
 * verb of the pack would take (the verb {@code deposit} and the primitive {@code lending.deposit} of pack
 * {@code lending}, both {@code lending_deposit}) is named with {@value #PRIMITIVE_SUFFIX}
 * ({@code lending_deposit_primitive}), whatever the policy, so that one name never means two things by mode; the verb
 * keeps the plain name (decision D92). Any other two tools projecting to one name are refused. A description is the
 * descriptor's, prefixed with the thread and the timeout. An input schema is
 * the descriptor's {@code paramsSchema} without {@code _meta}, or the verb's {@code argsSchema} (a free-form object
 * when there is none), made strict: every object that declares properties requires all of them, an optional one
 * becoming nullable ({@code type: [<t>, "null"]}), with {@code additionalProperties: false}; the numeric bounds and
 * lengths the provider does not take are left out. The pack's own {@code argsSchema} is not changed (the scenario
 * schema keeps it). {@code readOnly} comes from the descriptor's {@code mutating}, {@code destructive} from its
 * {@code destructive}.
 *
 * <p>What a {@link Policy} offers: the reads always (when it offers primitive reads at all); mutating verbs, domain
 * and base primitives by flags; a destructive primitive never; {@code data_query} only for a source with an allow-list
 * (and the model's SQL is then guarded); nothing of the agent's own namespace ({@code agent.*}). Hooks and rules
 * (phase6-contracts.md 4.2): {@link #HOOK_READS} ({@code hook.health}, {@code hook.events}, {@code rule.list}) are
 * offered as reads by every policy, the {@link #NEVER_OFFERED} seven never, whatever the policy and whatever their
 * flags ({@code hook.install} and {@code hook.remove} are not mutating): a hook spec or a rule is a pack author's or a
 * person's, never a client's or a model's.
 *
 * <p>Stateless; safe from any thread.
 */
public final class ToolProjection {

    /** The base primitives' namespaces (phase5-contracts.md 9.3). */
    public static final Set<String> BASE_NAMESPACES = Set.of("jvm", "app", "swing", "web", "sync");
    /** The one data primitive a policy may offer, and only with an allow-list. */
    public static final String DATA_QUERY = "data.query";
    /** What a primitive's tool name ends with when a verb of the pack takes its plain name (D92). */
    public static final String PRIMITIVE_SUFFIX = "_primitive";
    /**
     * The primitives that run a caller's script in the target's pages ({@code web.evaluate}, and {@code web.waitFlag},
     * which evaluates an expression until it is true): offered by no policy unless a person opted in (D98).
     */
    public static final Set<String> SCRIPT_PRIMITIVES = Set.of("web.evaluate", "web.waitFlag");
    /**
     * The hook and rule primitives offered by no policy (phase6-contracts.md 4.2, 9.3): named, not decided by namespace
     * or flag, since {@code hook.install} and {@code hook.remove} are not mutating. A client's {@code CAPTURE} on a
     * password-taking method would disclose a credential to a model, and a client's {@code hook.remove} would drop a
     * person's evidence mid-run.
     */
    public static final Set<String> NEVER_OFFERED = Set.of("hook.probe", "hook.install", "hook.stub", "hook.remove",
            "rule.check", "rule.submit", "rule.unload");
    /** The hook and rule primitives every policy offers as reads (phase6-contracts.md 4.2). */
    public static final Set<String> HOOK_READS = Set.of("hook.health", "hook.events", "rule.list");
    /** Keywords the provider's strict schemas do not take. */
    private static final Set<String> UNSUPPORTED_KEYWORDS = Set.of("minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "maxItems", "uniqueItems", "minProperties",
            "maxProperties", "$schema", "$id");

    private ToolProjection() {
    }

    /**
     * Which groups are offered.
     *
     * @param primitiveReads the read primitives of the domain and base groups
     * @param mutatingVerbs the pack's verbs (a verb drives the target, so every verb counts as mutating)
     * @param mutatingDomainPrimitives the domain module's mutating primitives
     * @param mutatingBaseNamespaces the base namespaces whose mutating primitives are offered
     * @param dataQuery {@code data_query}, over the allow-listed sources only
     * @param excluded primitives never offered by this policy, whatever their flags ({@code web.evaluate} in a session)
     */
    public record Policy(boolean primitiveReads, boolean mutatingVerbs, boolean mutatingDomainPrimitives,
                         Set<String> mutatingBaseNamespaces, boolean dataQuery, Set<String> excluded) {

        /** Copies the sets. */
        public Policy {
            mutatingBaseNamespaces = mutatingBaseNamespaces == null ? Set.of() : Set.copyOf(mutatingBaseNamespaces);
            excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
        }

        /**
         * The MCP server's default: reads only, {@code data_query} over allow-listed sources; never the
         * {@link ToolProjection#SCRIPT_PRIMITIVES} ({@code web.waitFlag} is a read that runs a caller's script, D98).
         */
        public static Policy readOnly() {
            return readOnly(false);
        }

        /**
         * {@link #readOnly()}, with {@code web.waitFlag} offered only when {@code allowEvaluate} is true (a person's
         * opt-in, {@code skillroy mcp --allow-evaluate}, D98; {@code web.evaluate} changes the page and is mutating).
         */
        public static Policy readOnly(boolean allowEvaluate) {
            return new Policy(true, false, false, Set.of(), true, allowEvaluate ? Set.of() : SCRIPT_PRIMITIVES);
        }

        /**
         * The MCP server with {@code --mutating}: the reads, the verbs, the domain and base mutating primitives; never
         * the {@link ToolProjection#SCRIPT_PRIMITIVES}, which run any script in the target's pages (decision D98):
         * {@link #mutating(boolean)} offers them when a person opted in.
         */
        public static Policy mutating() {
            return mutating(false);
        }

        /**
         * {@link #mutating()}, with the {@link ToolProjection#SCRIPT_PRIMITIVES} offered only when
         * {@code allowEvaluate} is true (a person's opt-in, {@code skillroy mcp --mutating --allow-evaluate}; the
         * planner's server never has it, D98).
         */
        public static Policy mutating(boolean allowEvaluate) {
            return new Policy(true, true, true, BASE_NAMESPACES, true, allowEvaluate ? Set.of() : SCRIPT_PRIMITIVES);
        }

        /**
         * The session service's primitives and verbs (12.4): no primitive reads (the workflow reads stand for them),
         * the verbs with {@code allowMutatingVerbs}, the base {@code swing_*} and {@code web_*} mutating primitives
         * with {@code allowBasePrimitives}; never {@code data_query}, never the
         * {@link ToolProjection#SCRIPT_PRIMITIVES}.
         */
        public static Policy session(boolean allowMutatingVerbs, boolean allowBasePrimitives) {
            return new Policy(false, allowMutatingVerbs, false, allowBasePrimitives ? Set.of("swing", "web") : Set.of(),
                    false, SCRIPT_PRIMITIVES);
        }
    }

    /**
     * The primitives and verbs a policy offers: the primitives by name, then the verbs by name. A primitive whose tool
     * name a verb of the pack would take is named with {@value #PRIMITIVE_SUFFIX}, whether or not the policy offers
     * the verbs (D92).
     *
     * @param primitives the target's catalogue ({@code GET /schema})
     * @param pack the running pack (its id is the domain, its verbs and allow-lists)
     * @throws IllegalArgumentException when two tools project to one name, or a name is longer than 64
     */
    public static List<ToolSpec> project(List<PrimitiveDescriptor> primitives, DomainPack pack, Policy policy) {
        List<ToolSpec> out = new ArrayList<>();
        List<PrimitiveDescriptor> sorted = new ArrayList<>(primitives);
        sorted.sort((a, b) -> a.name().compareTo(b.name()));
        Set<String> sources = new TreeSet<>();
        for (SqlAllowList list : pack.sqlAllowLists()) {
            sources.add(list.source());
        }
        String domain = pack.id();
        Set<String> verbNames = verbToolNames(pack);
        for (PrimitiveDescriptor d : sorted) {
            if (offered(d, domain, policy, !sources.isEmpty())) {
                out.add(primitive(d, DATA_QUERY.equals(d.name()) ? sources : null, verbNames));
            }
        }
        if (policy.mutatingVerbs()) {
            for (String verb : new TreeSet<>(pack.steps().verbs())) {
                out.add(verb(domain, verb, pack.steps().argsSchema(verb)));
            }
        }
        return unique(out);
    }

    private static boolean offered(PrimitiveDescriptor d, String domain, Policy policy, boolean allowLists) {
        String name = d.name();
        if (d.destructive() || policy.excluded().contains(name) || NEVER_OFFERED.contains(name)) {
            return false;
        }
        if (HOOK_READS.contains(name)) {
            return !d.mutating();
        }
        if (DATA_QUERY.equals(name)) {
            return policy.dataQuery() && allowLists;
        }
        int dot = name.indexOf('.');
        String namespace = dot < 0 ? name : name.substring(0, dot);
        boolean isDomain = namespace.equals(domain);
        boolean isBase = BASE_NAMESPACES.contains(namespace);
        if (!isDomain && !isBase) {
            return false;
        }
        if (!d.mutating()) {
            return policy.primitiveReads();
        }
        return isDomain ? policy.mutatingDomainPrimitives() : policy.mutatingBaseNamespaces().contains(namespace);
    }

    /**
     * The tool names of every verb of the pack, whatever a policy offers: the names a primitive's tool may not take
     * (D92). A verb whose name cannot be a tool name is left out (it is refused when it is projected).
     */
    public static Set<String> verbToolNames(DomainPack pack) {
        Set<String> names = new TreeSet<>();
        Set<String> verbs = pack.steps() == null ? null : pack.steps().verbs();
        for (String verb : verbs == null ? Set.<String>of() : verbs) {
            try {
                names.add(verbToolName(pack.id(), verb));
            } catch (IllegalArgumentException e) {
                // Refused when the verb itself is projected.
            }
        }
        return names;
    }

    /**
     * A primitive's tool name in a pack's projection: {@link #toolName}, with {@value #PRIMITIVE_SUFFIX} when a verb of
     * the pack takes that name (D92).
     *
     * @param verbToolNames the pack's {@link #verbToolNames}
     * @throws IllegalArgumentException if the name is not {@code [a-z0-9_]{1,64}}
     */
    public static String primitiveToolName(String primitive, Set<String> verbToolNames) {
        String name = toolName(primitive);
        return verbToolNames.contains(name) ? check(name + PRIMITIVE_SUFFIX, primitive) : name;
    }

    /**
     * The tool of a primitive: its name projected, its description prefixed with the thread and timeout, its params
     * schema without {@code _meta}, made strict.
     *
     * @param sources for {@code data.query}: the allow-listed sources its {@code source} is limited to; else null
     */
    public static ToolSpec primitive(PrimitiveDescriptor d, Collection<String> sources) {
        return primitive(d, sources, Set.of());
    }

    /**
     * {@link #primitive(PrimitiveDescriptor, Collection)} named as {@link #primitiveToolName} names it in a pack whose
     * verbs take {@code verbToolNames}.
     */
    public static ToolSpec primitive(PrimitiveDescriptor d, Collection<String> sources, Set<String> verbToolNames) {
        Map<String, Object> schema = d.paramsSchema() == null ? freeForm() : withoutMeta(d.paramsSchema());
        Map<String, Object> strict = strict(schema);
        String description = "[thread: " + (d.thread() == null ? "any" : d.thread()) + ", timeout: " + d.timeoutMs()
                + " ms] " + (d.description() == null ? "" : d.description());
        if (sources != null && strict.get("properties") instanceof Map<?, ?> props
                && props.get("source") instanceof Map<?, ?> source) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) props;
            Map<String, Object> limited = new LinkedHashMap<>();
            source.forEach((k, v) -> limited.put(String.valueOf(k), v));
            limited.put("enum", List.copyOf(sources));
            p.put("source", limited);
            description += " Only the allow-listed sources; the statement passes SqlGuard before it runs.";
        }
        return new ToolSpec(primitiveToolName(d.name(), verbToolNames), description, strict, !d.mutating(),
                d.destructive());
    }

    /** The tool of a pack verb: {@code <domain>_<verb_in_snake_case>}, its args schema made strict, or free-form. */
    public static ToolSpec verb(String domain, String verb, Map<String, Object> argsSchema) {
        Map<String, Object> schema = argsSchema == null ? freeForm() : strict(argsSchema);
        String description = "Pack verb '" + verb + "' of domain '" + domain + "': runs the verb's calls through the"
                + " executor's step protocol" + (argsSchema == null ? "; its arguments are free-form" : "") + ".";
        return new ToolSpec(verbToolName(domain, verb), description, schema, false, false);
    }

    /** {@code a.b.c} as {@code a_b_c}, camel case as snake case; {@code [a-z0-9_]} only. */
    public static String toolName(String primitive) {
        return check(snake(primitive.replace('.', '_')), primitive);
    }

    /** A verb's tool name: {@code <domain>_<verb_in_snake_case>}. */
    public static String verbToolName(String domain, String verb) {
        return check(snake(domain) + "_" + snake(verb), domain + "." + verb);
    }

    private static String snake(String text) {
        StringBuilder out = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && (Character.isLowerCase(prev) || Character.isDigit(prev))) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(c);
            } else {
                out.append('_');
            }
            prev = c;
        }
        return out.toString().replaceAll("_+", "_").toLowerCase(Locale.ROOT);
    }

    private static String check(String name, String from) {
        if (!ToolSpec.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(from + " projects to the tool name " + name + ", which is not"
                    + " [a-z0-9_]{1,64}");
        }
        return name;
    }

    private static List<ToolSpec> unique(List<ToolSpec> tools) {
        Map<String, ToolSpec> seen = new TreeMap<>();
        for (ToolSpec t : tools) {
            ToolSpec before = seen.put(t.name(), t);
            if (before != null) {
                throw new IllegalArgumentException("tool name collision: two tools project to " + t.name());
            }
        }
        return List.copyOf(tools);
    }

    // ------------------------------------------------------------------------------------------------ schemas

    /** {@code {type: object}}: a free-form object (a verb without an {@code argsSchema}). */
    static Map<String, Object> freeForm() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        return schema;
    }

    private static Map<String, Object> withoutMeta(Map<String, Object> schema) {
        Map<String, Object> copy = copyMap(schema);
        if (copy.get("properties") instanceof Map<?, ?> props) {
            Map<String, Object> p = copyMap(props);
            p.remove("_meta");
            copy.put("properties", p);
        }
        if (copy.get("required") instanceof List<?> required) {
            List<Object> r = new ArrayList<>(required);
            r.remove("_meta");
            copy.put("required", r);
        }
        return copy;
    }

    /**
     * A strict copy of a schema: an object that declares properties requires every one of them, the ones it did not
     * require made nullable, and refuses others; nested objects, array items and {@code anyOf} branches likewise; the
     * keywords the provider's strict mode does not take are left out. An object without declared properties stays
     * free-form. The argument is not changed.
     */
    public static Map<String, Object> strict(Map<String, Object> schema) {
        return strict(schema, 0);
    }

    private static Map<String, Object> strict(Map<?, ?> schema, int depth) {
        if (depth > 50) {
            throw new IllegalArgumentException("a schema nests deeper than 50");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : schema.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!UNSUPPORTED_KEYWORDS.contains(key) && !(key.equals("minItems") && e.getValue() instanceof Number n
                    && n.longValue() > 1)) {
                out.put(key, e.getValue());
            }
        }
        if (out.get("items") instanceof Map<?, ?> items) {
            out.put("items", strict(items, depth + 1));
        }
        if (out.get("anyOf") instanceof List<?> any) {
            List<Object> branches = new ArrayList<>();
            for (Object b : any) {
                branches.add(b instanceof Map<?, ?> m ? strict(m, depth + 1) : b);
            }
            out.put("anyOf", branches);
        }
        if (out.get("properties") instanceof Map<?, ?> props) {
            Set<String> required = new LinkedHashSet<>();
            if (out.get("required") instanceof List<?> r) {
                for (Object name : r) {
                    required.add(String.valueOf(name));
                }
            }
            Map<String, Object> strictProps = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : props.entrySet()) {
                String name = String.valueOf(e.getKey());
                Map<String, Object> property = e.getValue() instanceof Map<?, ?> m ? strict(m, depth + 1)
                        : new LinkedHashMap<>();
                strictProps.put(name, required.contains(name) ? property : nullable(property));
            }
            out.put("properties", strictProps);
            // The schema's own required keys in their order, then the others by name: a stable order whatever the
            // iteration order of the pack's map (a tool's schema is part of a request's fingerprint).
            List<String> all = new ArrayList<>();
            for (String name : required) {
                if (strictProps.containsKey(name)) {
                    all.add(name);
                }
            }
            for (String name : new TreeSet<>(strictProps.keySet())) {
                if (!all.contains(name)) {
                    all.add(name);
                }
            }
            out.put("required", all);
            out.put("additionalProperties", false);
            if (!out.containsKey("type")) {
                out.put("type", "object");
            }
        }
        return out;
    }

    /** The schema with {@code null} allowed: {@code type: [<t>, "null"]} (and null in an enum), else {@code anyOf}. */
    static Map<String, Object> nullable(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>(schema);
        Object type = schema.get("type");
        if (type instanceof String t) {
            out.put("type", t.equals("null") ? t : List.of(t, "null"));
        } else if (type instanceof List<?> types) {
            List<Object> withNull = new ArrayList<>(types);
            if (!withNull.contains("null")) {
                withNull.add("null");
            }
            out.put("type", withNull);
        } else {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            Map<String, Object> nothing = new LinkedHashMap<>();
            nothing.put("type", "null");
            wrapped.put("anyOf", List.of(schema, nothing));
            if (schema.get("description") != null) {
                wrapped.put("description", schema.get("description"));
            }
            return wrapped;
        }
        if (schema.get("enum") instanceof List<?> values) {
            // List.of(...) refuses contains(null): look on a copy.
            List<Object> withNull = new ArrayList<>(values);
            if (!withNull.contains(null)) {
                withNull.add(null);
            }
            out.put("enum", withNull);
        }
        return out;
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
