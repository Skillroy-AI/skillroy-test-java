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

import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The JSON Schema of a scenario document for one pack (phase5-contracts.md 10.2, 13.3), derived from the grammar as
 * {@link ScenarioLoader}, {@link PlanCompiler} and {@link BaseSteps} implement it, and a small validator for the subset
 * of JSON Schema the generator writes. The planner constrains the model's answer with the schema and checks the answer
 * with the validator before the loader sees it; the MCP server serves it as a resource; a test keeps the generator
 * honest against the committed scenarios.
 *
 * <p>What the schema says ({@link #generate}): the top-level keys of P1 8.1 with P2-P4's additions ({@code scenario}
 * and {@code steps} required, nothing else allowed); {@code resolve} slots that are a fixture or a query of one of the
 * pack's kinds, with the kind's argument record ({@link Schemas#ofArguments}: references allowed), the strategy tokens
 * the pack can serve for the kind ({@code sql}, {@code java}, and {@code llm-sql} when the pack declares an SQL
 * allow-list) and its verifier ids; {@code oracle} entries over the pack's functions with their cross-checks; steps as
 * single-key objects, one key per verb: the pack's verbs with {@link StepLibrary#argsSchema} or a free-form object,
 * then the base verbs that the pack does not override, with the argument shapes {@link BaseSteps} accepts (the base
 * verb {@code call} as built: {@code {primitive, args?, sync?, await?}}, sync NONE by default, the capture inside
 * {@code await}); {@code expect} and {@code verify} entries as path to matcher (or a {@code db.<table>} check);
 * {@code requires} as capability ids (a string each, the ids the target's primitives name listed in its description);
 * {@code ui}, {@code web}, {@code timeouts}, {@code on_prompt}, {@code artifacts}, {@code tags},
 * {@code launchProperties}. What only the compiler can tell (an expectation's root, a stored table's columns, a
 * reference's slot) is left to it.
 *
 * <p>What the validator checks ({@link #validate}): {@code type} (one name or a list), {@code properties},
 * {@code required}, {@code additionalProperties} (a boolean or a schema), {@code items}, {@code minItems},
 * {@code pattern} (searched for, as JSON Schema does), {@code enum}, {@code const}, {@code oneOf}, {@code anyOf} and
 * local {@code $ref}s into {@code $defs}; the annotations {@code $schema}, {@code $defs}, {@code title},
 * {@code description}, {@code $comment}, {@code default} and {@code examples} are ignored and any other keyword is an
 * {@link IllegalArgumentException}. It is not a general JSON Schema library and adds no dependency.
 *
 * <p>Stateless; safe from any thread.
 */
public final class ScenarioSchema {

    /** The dialect the generated schema declares ({@code claude -p --json-schema} reads it). */
    public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    private static final Pattern DURATION = Pattern.compile("^\\s*(\\d{1,15}|\\d{1,12}\\s*([Mm][Ss]|[Ss]|[Mm]))\\s*$");
    private static final String SYNC = "^([Nn][Oo][Nn][Ee]|[Aa][Cc][Kk]|[Rr][Ee][Aa][Dd][Yy]|[Ss][Ee][Tt][Tt][Ll][Ee]"
            + "|[Ee][Vv][Ee][Nn][Tt])$";
    private static final Set<String> ANNOTATIONS = Set.of("$schema", "$defs", "title", "description", "$comment",
            "default", "examples");
    private static final int MAX_PROBLEMS = 50;

    private ScenarioSchema() {
    }

    /**
     * One thing a document gets wrong.
     *
     * @param path where, as a JSON path ({@code $.steps[2].signOn}, {@code $.verify[0]['state.name']})
     * @param message what
     */
    public record Problem(String path, String message) {

        /** {@code path: message}. */
        @Override
        public String toString() {
            return path + ": " + message;
        }
    }

    // ------------------------------------------------------------------------------------------------ generator

    /**
     * The schema of a scenario document of {@code pack}, as a JSON-like map.
     *
     * @param primitives the target's primitive catalogue ({@code GET /schema}), or empty when it is not known: the
     *     base verb {@code call} then takes any primitive name
     * @throws IllegalArgumentException when the pack's catalogue or step library cannot be read
     */
    public static Map<String, Object> generate(DomainPack pack, List<PrimitiveDescriptor> primitives) {
        return generate(pack, primitives, null);
    }

    /**
     * {@link #generate(DomainPack, List)} for a host that offers only some primitives (phase5-contracts.md 9.3, 10.2,
     * decision D96: the MCP server and the planner's server run a scenario only when its calls are ones they offer):
     * the base verb {@code call}'s {@code primitive} is one of {@code callable}.
     *
     * @param callable the primitive names the host lets the base verb {@code call} name; null means every primitive
     *     of the catalogue (any name when the catalogue is not known)
     * @throws IllegalArgumentException when the pack's catalogue or step library cannot be read
     */
    public static Map<String, Object> generate(DomainPack pack, List<PrimitiveDescriptor> primitives,
                                               Set<String> callable) {
        Objects.requireNonNull(pack, "pack");
        List<PrimitiveDescriptor> descriptors = primitives == null ? List.of() : primitives;
        ResolutionCatalogue catalogue;
        StepLibrary library;
        boolean allowList;
        try {
            catalogue = pack.resolution() == null ? ResolutionCatalogue.EMPTY : pack.resolution();
            library = pack.steps();
            List<SqlAllowList> lists = pack.sqlAllowLists();
            allowList = lists != null && lists.stream().anyMatch(Objects::nonNull);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("pack " + pack.id() + " cannot be read: " + e, e);
        }
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("reference", Schemas.referenceSchema());
        defs.put("scalar", Schemas.anyOf(Schemas.type("string"), Schemas.type("number"), Schemas.type("boolean")));
        defs.put("duration", duration());
        defs.put("credential", object(props("realm", Schemas.type("string"), "key", Schemas.type("string")),
                List.of("realm", "key")));
        defs.put("topics", topics());
        defs.put("capture", object(props("slot", pattern("^[A-Za-z_][A-Za-z0-9_]*$"), "fields",
                withAdditional(Schemas.type("object"), Schemas.type("string"))), List.of("slot", "fields")));
        defs.put("await", object(props("topic", ref("topics"), "topics", ref("topics"), "match",
                Schemas.type("object"), "within", ref("duration"), "capture", ref("capture")), List.of()));
        defs.put("browser", Schemas.type("object"));
        defs.put("slot", slots(catalogue, allowList));
        defs.put("check", check());
        defs.put("checks", withAdditional(Schemas.type("object"), ref("check")));
        Map<String, Object> verbs = new LinkedHashMap<>();
        Set<String> packVerbs = library == null || library.verbs() == null ? Set.of()
                : new TreeSet<>(library.verbs());
        for (String verb : packVerbs) {
            if (!PlanCompiler.RESERVED_VERBS.contains(verb)) {
                verbs.put(verb, packArgs(library, verb));
            }
        }
        Map<String, Map<String, Object>> base = baseVerbs(descriptors, callable);
        for (String verb : BaseSteps.VERBS) {
            verbs.putIfAbsent(verb, base.get(verb));
        }
        List<Object> steps = new ArrayList<>();
        for (Map.Entry<String, Object> verb : verbs.entrySet()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(verb.getKey(), verb.getValue());
            steps.add(object(props, List.of(verb.getKey())));
        }
        defs.put("step", oneOf(steps));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scenario", pattern("^[a-z0-9-]+$"));
        properties.put("pack", constant(pack.id()));
        properties.put("tags", array(Schemas.type("string")));
        properties.put("requires", array(requiresItem(descriptors)));
        properties.put("launchProperties", withAdditional(Schemas.type("object"), ref("scalar")));
        properties.put("resolve", withAdditional(Schemas.type("object"), ref("slot")));
        properties.put("oracle", oracles(catalogue));
        properties.put("steps", array(ref("step")));
        properties.put("verify", array(ref("checks")));
        properties.put("on_prompt", object(props("unexpected", Schemas.type("string")), List.of()));
        properties.put("artifacts", array(Schemas.type("string")));
        properties.put("timeouts", object(props("step", ref("duration"), "bind", ref("duration")), List.of()));
        properties.put("ui", object(props("clickRung", Schemas.type("string"), "strict", Schemas.type("boolean")),
                List.of()));
        properties.put("web", object(props("path", Schemas.type("string"), "browser", ref("browser"),
                "frozenTargets", Schemas.type("string"), "trace", Schemas.type("boolean")), List.of()));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", DIALECT);
        schema.put("title", "Scenario of pack " + pack.id() + " " + pack.version());
        schema.put("description", "A scenario document: the keys of the scenario grammar, steps as single-key"
                + " mappings verb: args, $references as strings. Fact roots: " + roots(pack) + ".");
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new ArrayList<>(List.of("scenario", "steps")));
        schema.put("additionalProperties", false);
        schema.put("$defs", defs);
        return schema;
    }

    /** The fact roots, the pack's first, for the description. */
    private static String roots(DomainPack pack) {
        Set<String> roots = new LinkedHashSet<>();
        try {
            List<FactProvider> facts = pack.facts();
            if (facts != null) {
                for (FactProvider fact : facts) {
                    roots.add(fact.root());
                }
            }
        } catch (RuntimeException e) {
            // The description only; the compiler reports a broken pack.
        }
        roots.addAll(BaseFacts.ROOTS);
        return String.join(", ", roots);
    }

    /** A slot: a fixture, or a query of one of the pack's kinds. */
    private static Map<String, Object> slots(ResolutionCatalogue catalogue, boolean allowList) {
        List<Object> alternatives = new ArrayList<>();
        alternatives.add(object(props("fixture", new LinkedHashMap<>(), "credential", ref("credential")),
                List.of("fixture")));
        for (String kind : catalogue.kinds()) {
            QueryType<?, ?> type = catalogue.query(kind);
            Map<String, Object> args;
            try {
                args = Schemas.ofArgumentObject(type.queryClass());
            } catch (IllegalArgumentException e) {
                args = Schemas.type("object");
                args.put("description", "free-form: " + e.getMessage());
            }
            Set<String> tokens = new LinkedHashSet<>();
            for (Resolver<?, ?> resolver : catalogue.resolvers(kind)) {
                Strategy strategy = resolver.strategy();
                if (strategy != null && strategy.token() != null && !strategy.needsModel()) {
                    tokens.add(strategy.token());
                }
            }
            if (allowList) {
                tokens.add(Strategy.LLM_SQL.token());
            }
            List<Object> verifiers = new ArrayList<>();
            for (Verifier<?, ?> verifier : catalogue.verifiers(kind)) {
                verifiers.add(verifier.id());
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("query", constant(kind));
            props.put("args", args);
            if (!tokens.isEmpty()) {
                props.put("strategy", nameOrList(enumOf(new ArrayList<>(tokens))));
            }
            if (!verifiers.isEmpty()) {
                props.put("verify", nameOrList(enumOf(verifiers)));
            }
            props.put("credential", ref("credential"));
            alternatives.add(object(props, List.of("query")));
        }
        return oneOf(alternatives);
    }

    /** The {@code oracle} section: one entry per function the pack offers, each with its cross-checks. */
    private static Map<String, Object> oracles(ResolutionCatalogue catalogue) {
        List<Object> alternatives = new ArrayList<>();
        for (String fn : catalogue.oracleFns()) {
            Oracle<?, ?> oracle = catalogue.oracle(fn);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("fn", constant(fn));
            props.put("args", oracleArgs(oracle));
            props.put("must_equal", new LinkedHashMap<>());
            List<Object> checks = new ArrayList<>();
            for (CrossCheck<?, ?> check : catalogue.crossChecks(fn)) {
                checks.add(check.id());
            }
            if (!checks.isEmpty()) {
                props.put("crossCheck", Schemas.anyOf(enumOf(checks), object(props("check", enumOf(checks),
                        "tolerance", Schemas.anyOf(Schemas.type("string"), Schemas.type("number")), "capability",
                        Schemas.type("string")), List.of("check"))));
            }
            alternatives.add(object(props, List.of("fn")));
        }
        Map<String, Object> section = Schemas.type("object");
        if (alternatives.isEmpty()) {
            section.put("additionalProperties", false);
            section.put("description", "the pack offers no oracle function");
        } else {
            section.put("additionalProperties", oneOf(alternatives));
        }
        return section;
    }

    /** An oracle's arguments: its input as a scenario writes it, or a list (positional for a record). */
    private static Map<String, Object> oracleArgs(Oracle<?, ?> oracle) {
        Type input;
        try {
            input = oracle.inputType();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
        try {
            return Schemas.anyOf(Schemas.ofArguments(input), Schemas.type("array"));
        } catch (IllegalArgumentException e) {
            Map<String, Object> free = new LinkedHashMap<>();
            free.put("description", "free-form: " + e.getMessage());
            return free;
        }
    }

    /** A pack verb's arguments: its schema, or a free-form object. */
    private static Map<String, Object> packArgs(StepLibrary library, String verb) {
        Map<String, Object> declared;
        try {
            declared = library.argsSchema(verb);
        } catch (RuntimeException e) {
            declared = null;
        }
        if (declared != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>) Values.normalise(declared);
            return copy;
        }
        Map<String, Object> free = Schemas.type("object");
        free.put("description", "free-form: the pack declares no schema for " + verb);
        return free;
    }

    /**
     * The base verbs' arguments, as {@link BaseSteps} accepts them; {@code call}'s primitive one of {@code callable}
     * when it is given, else one of the catalogue's, else any name (the catalogue is not known).
     */
    private static Map<String, Map<String, Object>> baseVerbs(List<PrimitiveDescriptor> descriptors,
                                                              Set<String> callable) {
        Map<String, Map<String, Object>> verbs = new LinkedHashMap<>();
        Map<String, Object> primitive = Schemas.type("string");
        if (callable != null) {
            if (callable.isEmpty()) {
                primitive.put("description", "no primitive is offered here: use the pack's verbs");
            } else {
                primitive = enumOf(new ArrayList<>(new TreeSet<>(callable)));
            }
        } else if (!descriptors.isEmpty()) {
            Set<String> names = new TreeSet<>();
            for (PrimitiveDescriptor descriptor : descriptors) {
                if (descriptor != null) {
                    names.add(descriptor.name());
                }
            }
            primitive = enumOf(new ArrayList<>(names));
        }
        verbs.put("call", object(props("primitive", primitive, "args", Schemas.type("object"), "sync",
                pattern(SYNC), "await", ref("await")), List.of("primitive")));
        Map<String, Object> target = Schemas.anyOf(Schemas.type("string"), Schemas.type("object"));
        verbs.put("click", object(props("target", Schemas.type("string"), "by", Schemas.type("object"), "rung",
                Schemas.type("string"), "focusTimeoutMs", ref("duration"), "activateWindow", Schemas.type("boolean"),
                "force", Schemas.type("boolean"), "fallback", Schemas.type("string"), "strict",
                Schemas.type("boolean"), "await", ref("await")), List.of()));
        verbs.put("type", object(props("target", Schemas.type("string"), "by", Schemas.type("object"), "text",
                ref("scalar"), "enter", Schemas.type("boolean"), "mode", Schemas.type("string"), "clear",
                Schemas.type("boolean"), "force", Schemas.type("boolean"), "await", ref("await")), List.of("text")));
        verbs.put("press", object(props("key", Schemas.type("string"), "target", target, "by",
                Schemas.type("object"), "modifiers", new LinkedHashMap<>(), "force", Schemas.type("boolean"),
                "await", ref("await")), List.of("key")));
        verbs.put("dismiss", Schemas.anyOf(Schemas.type("null"), object(props("button", new LinkedHashMap<>(),
                "dialog", new LinkedHashMap<>(), "close", new LinkedHashMap<>()), List.of())));
        verbs.put("waitFor", object(props("topic", ref("topics"), "match", Schemas.type("object"), "within",
                ref("duration"), "capture", ref("capture")), List.of("topic")));
        verbs.put("expect", ref("checks"));
        verbs.put("mark", Schemas.anyOf(Schemas.type("string"), Schemas.type("number"), Schemas.type("boolean"),
                object(props("label", Schemas.type("string"), "attrs", Schemas.type("object")), List.of("label"))));
        Map<String, Object> name = Schemas.anyOf(Schemas.type("string"), object(props("regex",
                Schemas.type("string"), "ignoreCase", Schemas.type("boolean")), List.of("regex")));
        Map<String, Object> click = props("selector", Schemas.type("string"), "role", Schemas.type("string"), "name",
                name, "index", Schemas.type("integer"), "expectVisible", Schemas.type("boolean"), "browser",
                ref("browser"), "frame", Schemas.type("string"), "await", ref("await"));
        verbs.put("webClick", object(click, List.of()));
        Map<String, Object> fill = new LinkedHashMap<>(click);
        fill.putAll(props("text", ref("scalar"), "clear", Schemas.type("boolean"), "enter", Schemas.type("boolean")));
        verbs.put("webFill", object(fill, List.of("text")));
        verbs.put("webDispatch", object(props("name", Schemas.type("string"), "detail", new LinkedHashMap<>(),
                "target", Schemas.type("string"), "browser", ref("browser"), "frame", Schemas.type("string"), "await",
                ref("await")), List.of("name")));
        verbs.put("webWaitFlag", object(props("expression", Schemas.type("string"), "timeoutMs", ref("duration"),
                "pollMs", Schemas.type("integer"), "browser", ref("browser"), "frame", Schemas.type("string")),
                List.of("expression")));
        verbs.put("webResize", object(props("width", Schemas.type("integer"), "height", Schemas.type("integer"),
                "clear", Schemas.type("boolean"), "browser", ref("browser"), "await", ref("await")), List.of()));
        verbs.put("webTap", object(props("patterns", ref("topics"), "target", Schemas.type("string"), "browser",
                ref("browser")), List.of("patterns")));
        // phase7-contracts.md 8.1 (D310): the four vision verbs, as BaseSteps accepts them.
        verbs.put("aiAct", object(props("intent", Schemas.type("string"), "tier", enumOf(List.of("V1", "V2", "V3")),
                "model", enumOf(List.of("opus", "sonnet", "haiku")), "maxActions", Schemas.type("integer")),
                List.of("intent")));
        verbs.put("aiExpect", object(props("question", Schemas.type("string"), "expect", enumOf(List.of("YES", "NO"))),
                List.of("question")));
        Map<String, Object> fractions = object(props("fx", Schemas.type("number"), "fy", Schemas.type("number")),
                List.of("fx", "fy"));
        Map<String, Object> rect = object(props("x", Schemas.type("integer"), "y", Schemas.type("integer"), "w",
                Schemas.type("integer"), "h", Schemas.type("integer")), List.of("x", "y", "w", "h"));
        verbs.put("pointer", object(props("target", Schemas.type("string"), "by", Schemas.type("object"), "region",
                Schemas.type("string"), "row", Schemas.type("integer"), "at", fractions, "action",
                enumOf(new ArrayList<>(BaseSteps.POINTER_ACTIONS)), "to", fractions, "button",
                enumOf(List.of("LEFT", "MIDDLE", "RIGHT")), "modifiers", array(enumOf(List.of("SHIFT", "CTRL", "ALT",
                        "META"))), "clickCount", Schemas.type("integer"), "wheel", object(props("notches",
                        Schemas.type("integer")), List.of("notches")), "steps", Schemas.type("integer"), "via",
                enumOf(List.of("ROUTED", "ROBOT")), "guard", pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"), "force",
                Schemas.type("boolean"), "await", ref("await"), "strict", Schemas.type("boolean")), List.of()));
        verbs.put("expectImage", object(props("label", pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"), "target",
                Schemas.type("string"), "by", Schemas.type("object"), "region", Schemas.type("string"), "clip", rect,
                "mask", array(Schemas.anyOf(object(props("by", Schemas.type("object")), List.of("by")), rect)),
                "maxDiffPixels", Schemas.type("integer")), List.of("label")));
        return verbs;
    }

    /** An expectation's matcher (P1 8.4) or a stored-row check ({@code db.<table>}). */
    private static Map<String, Object> check() {
        List<Object> alternatives = new ArrayList<>();
        alternatives.add(Schemas.type("string"));
        alternatives.add(Schemas.type("number"));
        alternatives.add(Schemas.type("boolean"));
        alternatives.add(Schemas.type("null"));
        alternatives.add(Schemas.type("array"));
        for (String matcher : Matchers.NAMES) {
            alternatives.add(object(props(matcher, new LinkedHashMap<>(), "capability", Schemas.type("string")),
                    List.of(matcher)));
        }
        Map<String, Object> db = object(props("where", Schemas.type("object"), "capability", Schemas.type("string")),
                List.of("where"));
        db.put("additionalProperties", new LinkedHashMap<>());
        db.put("description", "a stored-row check: where, rows?, capability?, and column or aggregate matchers");
        alternatives.add(db);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("anyOf", alternatives);
        return schema;
    }

    private static Map<String, Object> requiresItem(List<PrimitiveDescriptor> descriptors) {
        Set<String> ids = new TreeSet<>();
        for (PrimitiveDescriptor descriptor : descriptors) {
            if (descriptor != null && descriptor.capabilityId() != null && !descriptor.capabilityId().isBlank()) {
                ids.add(descriptor.capabilityId());
            }
        }
        Map<String, Object> item = Schemas.type("string");
        item.put("description", "a capability id, or alternatives \"a | b\""
                + (ids.isEmpty() ? "" : "; the target's primitives name: " + String.join(", ", ids)));
        return item;
    }

    private static Map<String, Object> duration() {
        Map<String, Object> text = pattern(DURATION.pattern());
        return Schemas.anyOf(Schemas.type("integer"), text);
    }

    private static Map<String, Object> topics() {
        return Schemas.anyOf(Schemas.type("string"), array(Schemas.type("string"), true));
    }

    // ------------------------------------------------------------------------------------------------ builders

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = Schemas.type("object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            props.put((String) keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    private static Map<String, Object> withAdditional(Map<String, Object> schema, Object additional) {
        schema.put("additionalProperties", additional);
        return schema;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return array(items, false);
    }

    private static Map<String, Object> array(Map<String, Object> items, boolean nonEmpty) {
        Map<String, Object> schema = Schemas.type("array");
        schema.put("items", items);
        if (nonEmpty) {
            schema.put("minItems", 1L);
        }
        return schema;
    }

    private static Map<String, Object> nameOrList(Map<String, Object> name) {
        return Schemas.anyOf(name, array(name, true));
    }

    private static Map<String, Object> enumOf(List<Object> values) {
        Map<String, Object> schema = Schemas.type("string");
        schema.put("enum", values);
        return schema;
    }

    private static Map<String, Object> constant(String value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("const", value);
        return schema;
    }

    private static Map<String, Object> pattern(String regex) {
        Map<String, Object> schema = Schemas.type("string");
        schema.put("pattern", regex);
        return schema;
    }

    private static Map<String, Object> ref(String def) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$ref", "#/$defs/" + def);
        return schema;
    }

    private static Map<String, Object> oneOf(List<Object> alternatives) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("oneOf", alternatives);
        return schema;
    }

    // ------------------------------------------------------------------------------------------------ validator

    /**
     * Checks a document (JSON-like: maps, lists, strings, numbers, booleans, null, as {@code StrictYaml} and
     * {@code Json.parse} produce them) against a schema of the subset the generator writes.
     *
     * @return the problems, at most 50, each with its JSON path; empty when the document is valid
     * @throws IllegalArgumentException when the schema uses a keyword outside the subset or a {@code $ref} that does
     *     not resolve
     */
    public static List<Problem> validate(Map<String, Object> schema, Object document) {
        Objects.requireNonNull(schema, "schema");
        Validator validator = new Validator(schema);
        List<Problem> problems = new ArrayList<>();
        validator.check(schema, document, "$", problems, 0);
        return problems.size() > MAX_PROBLEMS ? List.copyOf(problems.subList(0, MAX_PROBLEMS))
                : List.copyOf(problems);
    }

    /** One validation against one root schema (for its {@code $defs}). */
    private static final class Validator {

        private static final int MAX_DEPTH = 200;
        private final Map<String, Object> root;

        Validator(Map<String, Object> root) {
            this.root = root;
        }

        void check(Map<String, Object> schema, Object value, String path, List<Problem> problems, int depth) {
            if (depth > MAX_DEPTH) {
                problems.add(new Problem(path, "nested too deep to check"));
                return;
            }
            for (String keyword : schema.keySet()) {
                if (!ANNOTATIONS.contains(keyword) && !KEYWORDS.contains(keyword)) {
                    throw new IllegalArgumentException("unsupported schema keyword '" + keyword + "' at " + path);
                }
            }
            if (schema.containsKey("$ref")) {
                check(resolve(schema.get("$ref")), value, path, problems, depth + 1);
            }
            if (schema.containsKey("type") && !typeMatches(schema.get("type"), value)) {
                problems.add(new Problem(path, "expected " + describeType(schema.get("type")) + ", found "
                        + describe(value)));
                return;
            }
            if (schema.containsKey("const") && !Objects.equals(schema.get("const"), value)) {
                problems.add(new Problem(path, "must be " + Values.brief(schema.get("const"), 100) + ", found "
                        + Values.brief(value, 100)));
            }
            if (schema.get("enum") instanceof List<?> values && !values.contains(value)) {
                problems.add(new Problem(path, "must be one of " + Values.brief(values, 300) + ", found "
                        + Values.brief(value, 100)));
            }
            if (schema.get("pattern") instanceof String regex && value instanceof String text) {
                Pattern pattern;
                try {
                    pattern = Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("bad pattern at " + path + ": " + e.getDescription(), e);
                }
                if (!pattern.matcher(text).find()) {
                    problems.add(new Problem(path, "does not match " + regex + ": " + Values.brief(text, 100)));
                }
            }
            if (value instanceof Map<?, ?> map) {
                object(schema, map, path, problems, depth);
            }
            if (value instanceof List<?> list) {
                array(schema, list, path, problems, depth);
            }
            if (schema.get("anyOf") instanceof List<?> alternatives) {
                alternatives(alternatives, value, path, problems, depth, false);
            }
            if (schema.get("oneOf") instanceof List<?> alternatives) {
                alternatives(alternatives, value, path, problems, depth, true);
            }
        }

        private void object(Map<String, Object> schema, Map<?, ?> map, String path, List<Problem> problems,
                            int depth) {
            if (schema.get("required") instanceof List<?> required) {
                for (Object key : required) {
                    if (!map.containsKey(key)) {
                        problems.add(new Problem(path, "the key '" + key + "' is required"));
                    }
                }
            }
            Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
            Object additional = schema.get("additionalProperties");
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String at = member(path, key);
                if (properties.containsKey(key)) {
                    check(schemaOf(properties.get(key), at), e.getValue(), at, problems, depth + 1);
                } else if (Boolean.FALSE.equals(additional)) {
                    problems.add(new Problem(at, "is not allowed here"
                            + (properties.isEmpty() ? "" : "; allowed: " + properties.keySet())));
                } else if (additional instanceof Map<?, ?>) {
                    check(schemaOf(additional, at), e.getValue(), at, problems, depth + 1);
                }
            }
        }

        private void array(Map<String, Object> schema, List<?> list, String path, List<Problem> problems,
                           int depth) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.longValue()) {
                problems.add(new Problem(path, "needs at least " + min + " item" + (min.longValue() == 1 ? "" : "s")));
            }
            if (schema.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < list.size(); i++) {
                    String at = path + "[" + i + "]";
                    check(schemaOf(items, at), list.get(i), at, problems, depth + 1);
                }
            }
        }

        /**
         * {@code anyOf} (at least one alternative) or {@code oneOf} (exactly one). When none holds, the problems of
         * the alternative the value means are reported: the one whose required key the object has, or whose
         * {@code const} member it matches; otherwise one line names what was expected.
         */
        private void alternatives(List<?> alternatives, Object value, String path, List<Problem> problems,
                                  int depth, boolean exactlyOne) {
            List<List<Problem>> results = new ArrayList<>();
            int held = 0;
            for (Object alternative : alternatives) {
                List<Problem> found = new ArrayList<>();
                check(schemaOf(alternative, path), value, path, found, depth + 1);
                results.add(found);
                if (found.isEmpty()) {
                    held++;
                }
            }
            if (exactlyOne && held > 1) {
                problems.add(new Problem(path, "matches " + held + " alternatives where exactly one must match"));
                return;
            }
            if (held > 0) {
                return;
            }
            int meant = meant(alternatives, value, path);
            if (meant >= 0) {
                problems.addAll(results.get(meant));
                return;
            }
            Problem constant = constantMember(alternatives, value, path);
            if (constant != null) {
                problems.add(constant);
                return;
            }
            Set<String> keys = discriminatingKeys(alternatives, path);
            if (value instanceof Map<?, ?> map && keys != null) {
                problems.add(new Problem(path, "expected one of the keys " + brief(keys) + ", found "
                        + new ArrayList<>(map.keySet().stream().map(String::valueOf).toList())));
                return;
            }
            int best = -1;
            for (int i = 0; i < results.size(); i++) {
                if (best < 0 || results.get(i).size() < results.get(best).size()) {
                    best = i;
                }
            }
            int fewest = best < 0 ? 0 : results.get(best).size();
            if (best >= 0 && results.stream().filter(r -> r.size() == fewest).count() == 1
                    && !(value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean)) {
                problems.addAll(results.get(best));
                return;
            }
            problems.add(new Problem(path, Values.brief(value, 100) + " matches none of the " + alternatives.size()
                    + " allowed forms"));
        }

        /** The alternative the value means by its required key or a {@code const} member; -1 when none. */
        private int meant(List<?> alternatives, Object value, String path) {
            if (!(value instanceof Map<?, ?> map)) {
                return -1;
            }
            for (int i = 0; i < alternatives.size(); i++) {
                Map<String, Object> schema = schemaOf(alternatives.get(i), path);
                if (schema.get("properties") instanceof Map<?, ?> properties) {
                    for (Map.Entry<?, ?> e : properties.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> member && member.containsKey("const")
                                && Objects.equals(member.get("const"), map.get(e.getKey()))) {
                            return i;
                        }
                    }
                }
            }
            for (int i = 0; i < alternatives.size(); i++) {
                Map<String, Object> schema = schemaOf(alternatives.get(i), path);
                if (schema.get("required") instanceof List<?> required && required.size() == 1
                        && map.containsKey(required.get(0)) && !constMember(schema)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * When the alternatives are told apart by a {@code const} member ({@code query: Kind}, {@code fn: name}) and
         * the object has that member with another value: the problem naming the values allowed; else null.
         */
        private Problem constantMember(List<?> alternatives, Object value, String path) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            Map<String, List<Object>> allowed = new LinkedHashMap<>();
            for (Object alternative : alternatives) {
                Map<String, Object> schema = schemaOf(alternative, path);
                if (schema.get("properties") instanceof Map<?, ?> properties) {
                    for (Map.Entry<?, ?> e : properties.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> member && member.containsKey("const")) {
                            allowed.computeIfAbsent(String.valueOf(e.getKey()), k -> new ArrayList<>())
                                    .add(member.get("const"));
                        }
                    }
                }
            }
            for (Map.Entry<String, List<Object>> e : allowed.entrySet()) {
                if (map.containsKey(e.getKey())) {
                    return new Problem(member(path, e.getKey()), "must be one of " + Values.brief(e.getValue(), 300)
                            + ", found " + Values.brief(map.get(e.getKey()), 100));
                }
            }
            return null;
        }

        private boolean constMember(Map<String, Object> schema) {
            if (schema.get("properties") instanceof Map<?, ?> properties) {
                for (Object member : properties.values()) {
                    if (member instanceof Map<?, ?> m && m.containsKey("const")) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The single required keys of the alternatives when every one has exactly one; else null. */
        private Set<String> discriminatingKeys(List<?> alternatives, String path) {
            Set<String> keys = new LinkedHashSet<>();
            for (Object alternative : alternatives) {
                Map<String, Object> schema = schemaOf(alternative, path);
                if (!(schema.get("required") instanceof List<?> required) || required.size() != 1) {
                    return null;
                }
                keys.add(String.valueOf(required.get(0)));
            }
            return keys;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> schemaOf(Object schema, String path) {
            if (!(schema instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("not a schema at " + path + ": " + Values.brief(schema, 100));
            }
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed.containsKey("$ref") && typed.size() == 1 ? resolve(typed.get("$ref")) : typed;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> resolve(Object reference) {
            if (!(reference instanceof String text) || !text.startsWith("#/$defs/")) {
                throw new IllegalArgumentException("only local references #/$defs/<name> are supported, found "
                        + reference);
            }
            Object defs = root.get("$defs");
            Object target = defs instanceof Map<?, ?> map ? map.get(text.substring("#/$defs/".length())) : null;
            if (!(target instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("the reference " + text + " does not resolve");
            }
            return (Map<String, Object>) target;
        }

        private static final Set<String> KEYWORDS = Set.of("$ref", "type", "const", "enum", "pattern", "properties",
                "required", "additionalProperties", "items", "minItems", "anyOf", "oneOf");

        private static boolean typeMatches(Object type, Object value) {
            if (type instanceof List<?> types) {
                for (Object t : types) {
                    if (typeMatches(t, value)) {
                        return true;
                    }
                }
                return false;
            }
            return switch (String.valueOf(type)) {
                case "object" -> value instanceof Map<?, ?>;
                case "array" -> value instanceof List<?>;
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "null" -> value == null;
                case "number" -> value instanceof Number;
                case "integer" -> isInteger(value);
                default -> throw new IllegalArgumentException("unknown schema type '" + type + "'");
            };
        }

        private static boolean isInteger(Object value) {
            if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                    || value instanceof BigInteger) {
                return true;
            }
            if (value instanceof Double d) {
                return !d.isInfinite() && d == Math.rint(d);
            }
            return value instanceof BigDecimal b && b.stripTrailingZeros().scale() <= 0;
        }

        private static String describeType(Object type) {
            if (type instanceof List<?> types) {
                return String.join(" or ", types.stream().map(t -> article(String.valueOf(t))).toList());
            }
            return article(String.valueOf(type));
        }

        private static String article(String type) {
            return switch (type) {
                case "object" -> "a mapping";
                case "array" -> "a list";
                case "integer" -> "a whole number";
                case "null" -> "null";
                default -> "a " + type;
            };
        }

        private static String describe(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof Map<?, ?>) {
                return "a mapping";
            }
            if (value instanceof List<?>) {
                return "a list";
            }
            if (value instanceof String s) {
                return "the text " + Values.brief(s, 80);
            }
            if (value instanceof Boolean) {
                return "a boolean";
            }
            return "the number " + value;
        }

        private static String brief(Set<String> keys) {
            List<String> list = new ArrayList<>(keys);
            return list.size() <= 30 ? list.toString() : list.subList(0, 30) + " and " + (list.size() - 30) + " more";
        }

        private static String member(String path, String key) {
            return key.matches("[A-Za-z_][A-Za-z0-9_]*") ? path + "." + key
                    : path + "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
        }
    }
}
