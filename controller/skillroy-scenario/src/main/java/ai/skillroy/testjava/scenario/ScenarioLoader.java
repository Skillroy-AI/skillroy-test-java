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

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.data.yaml.YamlException;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a scenario YAML file (contract section 8.1, phase2-contracts.md 3) into a {@link Scenario}, checking its
 * shape: unknown top-level keys, a step that is not a single-key map, a bad duration, a slot that is neither a
 * fixture nor a query, a reserved slot or oracle name, an {@code llm-*} strategy, an oracle's {@code crossCheck}
 * that is neither a check id nor a mapping {@code {check, tolerance?, capability?}} (phase3-contracts.md 3.1) and a
 * {@code db.<table>} verify item with other keys are errors naming the key and line, and so is a {@code web} key
 * (phase4-contracts.md 7.2) with an unknown key or a bad value, including {@code frozenTargets: close}, which Phase 4
 * refuses (decision D52). Verbs, expectations, query kinds, verifiers, oracle functions, cross-checks, aggregates and
 * tables are checked later, by the compiler, against the pack. The YAML is read by {@link StrictYaml}, the reader
 * {@code credentials.yaml} uses.
 *
 * <p>Stateless; safe from any thread.
 */
public final class ScenarioLoader {

    /**
     * The top-level keys, in documentation order ({@code ui} is amendment A29, {@code oracle} Phase 2, {@code web}
     * Phase 4).
     */
    public static final List<String> KEYS = List.of("scenario", "pack", "tags", "requires", "launchProperties",
            "resolve", "oracle", "steps", "verify", "on_prompt", "artifacts", "timeouts", "ui", "web");

    /** The keys of {@code web} (phase4-contracts.md 7.2). */
    private static final List<String> WEB_KEYS = List.of("path", "browser", "frozenTargets", "trace");

    /** Names a slot or an oracle may not have (phase2-contracts.md 3.3). */
    public static final Set<String> RESERVED_NAMES = Set.of("oracle", "env", "run", "db", "secret", "credentials",
            "inference");

    /** A scenario id; it also names the scenario's output directory, so nothing else is allowed in it. */
    static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern SLOT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern VERB = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Set<String> KEY_SET = Set.copyOf(KEYS);

    private ScenarioLoader() {
    }

    /**
     * @throws ScenarioException if the file cannot be read or is not a valid scenario
     */
    public static Scenario load(Path yaml) throws ScenarioException {
        String name = String.valueOf(yaml.getFileName());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(yaml);
        } catch (IOException e) {
            throw new ScenarioException(name, 0, "cannot read the scenario file: " + e, e);
        }
        return load(bytes, name);
    }

    /**
     * @param sourceName the file name, used in messages only
     * @throws ScenarioException if the bytes are not a valid scenario
     */
    public static Scenario load(byte[] yaml, String sourceName) throws ScenarioException {
        StrictYaml.Doc doc;
        try {
            doc = StrictYaml.read(yaml);
        } catch (YamlException e) {
            throw new ScenarioException(sourceName, e.line(), e.reason(), e);
        }
        if (!(doc.root() instanceof Map<?, ?> rawRoot)) {
            throw new ScenarioException(sourceName, doc.lineOf(doc.root()), "a scenario file must be a YAML mapping");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rawRoot;
        for (String key : root.keySet()) {
            if (!KEY_SET.contains(key)) {
                throw new ScenarioException(sourceName, doc.keyLine(root, key), "unknown top-level key '" + key
                        + "'; allowed keys: " + String.join(", ", KEYS));
            }
        }
        Reader r = new Reader(doc, root, sourceName);
        Object rawId = root.get("scenario");
        if (!(rawId instanceof String id) || !ID.matcher(id).matches()) {
            throw r.error("scenario", "'scenario' must be an id matching [a-z0-9-]+"
                    + (rawId == null ? " (missing)" : ", found " + describe(rawId)));
        }
        r.id = id;
        String pack = r.optionalString("pack");
        List<String> tags = r.stringList("tags");
        List<String> requires = r.stringList("requires");
        Map<String, String> launchProperties = r.launchProperties();
        List<Scenario.Slot> slots = r.slots();
        List<Scenario.OracleEntry> oracles = r.oracles();
        List<Scenario.Step> steps = r.steps();
        List<Scenario.Verify> verify = r.verify();
        Map<String, Object> onPrompt = r.onPrompt();
        List<String> artifacts = r.stringList("artifacts");
        long stepTimeoutMs = r.timeout("step", Scenario.DEFAULT_STEP_TIMEOUT_MS);
        long bindTimeoutMs = r.timeout("bind", Plan.DEFAULT_BIND_TIMEOUT_MS);
        if (bindTimeoutMs > Plan.MAX_BIND_TIMEOUT_MS) {
            throw r.error("timeouts", "'timeouts.bind' must be at most 10m, found " + bindTimeoutMs + " ms");
        }
        UiSettings ui = r.ui();
        WebSettings web = r.web();
        return new Scenario(id, pack, tags, requires, launchProperties, slots, steps, verify, onPrompt, artifacts,
                stepTimeoutMs, sourceName, sourceHash(yaml), ui, oracles, bindTimeoutMs, web);
    }

    /** {@code "sha256:"} plus the lower-case hex SHA-256 of the bytes, as written to {@code plan.json}. */
    public static String sourceHash(byte[] yaml) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        if (value instanceof Map) {
            return "a mapping";
        }
        if (value instanceof List) {
            return "a list";
        }
        return String.valueOf(value);
    }

    /** Reads the top-level keys; {@link #id} is set once known so that errors name the scenario. */
    private static final class Reader {

        private final StrictYaml.Doc doc;
        private final Map<String, Object> root;
        private final String sourceName;
        private String id;

        Reader(StrictYaml.Doc doc, Map<String, Object> root, String sourceName) {
            this.doc = doc;
            this.root = root;
            this.sourceName = sourceName;
        }

        ScenarioException error(String key, String reason) {
            return new ScenarioException(id != null ? id : sourceName, doc.keyLine(root, key), reason);
        }

        ScenarioException errorAt(int line, String reason) {
            return new ScenarioException(id != null ? id : sourceName, line, reason);
        }

        String optionalString(String key) throws ScenarioException {
            Object value = root.get(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof String s) || s.isBlank()) {
                throw error(key, "'" + key + "' must be a non-empty string, found " + describe(value));
            }
            return s;
        }

        List<String> stringList(String key) throws ScenarioException {
            Object value = root.get(key);
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> list)) {
                throw error(key, "'" + key + "' must be a list of strings, found " + describe(value));
            }
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String s) || s.isBlank()) {
                    throw error(key, "'" + key + "' must hold non-empty strings, found " + describe(item));
                }
                out.add(s.strip());
            }
            return List.copyOf(out);
        }

        Map<String, String> launchProperties() throws ScenarioException {
            Object value = root.get("launchProperties");
            if (value == null) {
                return Map.of();
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw error("launchProperties", "'launchProperties' must be a mapping of property name to value");
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object v = e.getValue();
                if (v == null || v instanceof Map || v instanceof List) {
                    throw errorAt(doc.keyLine(map, (String) e.getKey()), "launch property '" + e.getKey()
                            + "' must have a scalar value, found " + describe(v));
                }
                out.put((String) e.getKey(), Values.stringOf(v));
            }
            if (out.size() > 50) {
                throw error("launchProperties", "at most 50 launch properties can be checked, found " + out.size());
            }
            return Collections.unmodifiableMap(out);
        }

        List<Scenario.Slot> slots() throws ScenarioException {
            Object value = root.get("resolve");
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw error("resolve", "'resolve' must be a mapping of slot name to {fixture: value} or"
                        + " {query: Kind, …}");
            }
            List<Scenario.Slot> out = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = (String) e.getKey();
                int line = doc.keyLine(map, name);
                name(name, line, "slot");
                if (!(e.getValue() instanceof Map<?, ?> spec) || spec.containsKey("fixture") == spec.containsKey(
                        "query")) {
                    throw errorAt(line, "slot '" + name + "' must have exactly one of 'fixture' and 'query':"
                            + " {fixture: <value>} or {query: Kind, args?, strategy?, verify?, credential?}");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> slot = (Map<String, Object>) spec;
                CredentialSpec credential = credential(slot, name);
                if (slot.containsKey("fixture")) {
                    for (String key : slot.keySet()) {
                        if (List.of("args", "strategy", "verify").contains(key)) {
                            throw errorAt(lineIn(slot, key, line), "slot '" + name + "': '" + key
                                    + "' belongs to a query slot, not to a fixture");
                        }
                        if (!"fixture".equals(key) && !"credential".equals(key)) {
                            throw errorAt(lineIn(slot, key, line), "slot '" + name + "': unknown key '" + key
                                    + "'; a fixture slot has fixture and credential");
                        }
                    }
                    out.add(new Scenario.Slot(name, "fixture", slot.get("fixture"), line, null, null, List.of(),
                            List.of(), credential));
                    continue;
                }
                for (String key : slot.keySet()) {
                    if (!List.of("query", "args", "strategy", "verify", "credential").contains(key)) {
                        throw errorAt(lineIn(slot, key, line), "slot '" + name + "': unknown key '" + key
                                + "'; a query slot has query, args, strategy, verify and credential");
                    }
                }
                if (!(slot.get("query") instanceof String kind) || kind.isBlank()) {
                    throw errorAt(lineIn(slot, "query", line), "slot '" + name + "': 'query' must name a query"
                            + " kind, found " + describe(slot.get("query")));
                }
                Object args = slot.get("args");
                if (args != null && !(args instanceof Map)) {
                    throw errorAt(lineIn(slot, "args", line), "slot '" + name + "': 'args' must be a mapping, found "
                            + describe(args));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typedArgs = args == null ? new LinkedHashMap<>() : (Map<String, Object>) args;
                List<String> strategy = strategy(slot, name, line);
                List<String> verify = idList(slot, "verify", name, line);
                out.add(new Scenario.Slot(name, "query", null, line, kind.strip(), typedArgs, strategy, verify,
                        credential));
            }
            return List.copyOf(out);
        }

        private int lineIn(Map<?, ?> map, String key, int fallback) {
            int line = doc.keyLine(map, key);
            return line != 0 ? line : fallback;
        }

        private void name(String name, int line, String what) throws ScenarioException {
            if (!SLOT.matcher(name).matches()) {
                throw errorAt(line, what + " name '" + name + "' must match [A-Za-z_][A-Za-z0-9_]*");
            }
            if (RESERVED_NAMES.contains(name)) {
                throw errorAt(line, what + " name '" + name + "' is reserved; reserved names: "
                        + new java.util.TreeSet<>(RESERVED_NAMES));
            }
        }

        private CredentialSpec credential(Map<String, Object> slot, String name) throws ScenarioException {
            if (!slot.containsKey("credential")) {
                return null;
            }
            int line = doc.keyLine(slot, "credential");
            if (!(slot.get("credential") instanceof Map<?, ?> c) || c.size() != 2 || !c.containsKey("realm")
                    || !c.containsKey("key")) {
                throw errorAt(line, "slot '" + name + "': 'credential' must be {realm: <realm>, key: <field>}");
            }
            if (!(c.get("realm") instanceof String realm) || !(c.get("key") instanceof String key)) {
                throw errorAt(line, "slot '" + name + "': credential realm and key must be strings");
            }
            try {
                return new CredentialSpec(realm, key);
            } catch (IllegalArgumentException e) {
                throw errorAt(line, "slot '" + name + "': " + e.getMessage());
            }
        }

        private List<String> strategy(Map<String, Object> slot, String name, int fallback) throws ScenarioException {
            List<String> tokens = idList(slot, "strategy", name, fallback);
            int line = lineIn(slot, "strategy", fallback);
            List<String> out = new ArrayList<>();
            for (String token : tokens) {
                Strategy strategy = Strategy.ofToken(token);
                if (strategy == null) {
                    throw errorAt(line, "slot '" + name + "': unknown strategy '" + token + "'; strategies: "
                            + String.join(", ", Strategy.TOKENS));
                }
                if (strategy.needsModel()) {
                    throw errorAt(line, "slot '" + name + "': strategy " + strategy.token() + " needs the inference"
                            + " module, which is not part of this version");
                }
                if (out.contains(strategy.token())) {
                    throw errorAt(line, "slot '" + name + "': strategy " + strategy.token() + " is listed twice");
                }
                out.add(strategy.token());
            }
            return out;
        }

        /** A key holding one id or a list of ids; absent = empty. */
        private List<String> idList(Map<String, Object> slot, String key, String name, int fallback)
                throws ScenarioException {
            Object value = slot.get(key);
            if (value == null) {
                return List.of();
            }
            int line = lineIn(slot, key, fallback);
            List<String> out = new ArrayList<>();
            if (value instanceof String s && !s.isBlank()) {
                out.add(s.strip());
            } else if (value instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    if (!(item instanceof String s) || s.isBlank()) {
                        throw errorAt(line, "slot '" + name + "': '" + key + "' must hold names, found "
                                + describe(item));
                    }
                    out.add(s.strip());
                }
            } else {
                throw errorAt(line, "slot '" + name + "': '" + key + "' must be a name or a list of names, found "
                        + describe(value));
            }
            return out;
        }

        List<Scenario.OracleEntry> oracles() throws ScenarioException {
            Object value = root.get("oracle");
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw error("oracle", "'oracle' must be a mapping of name to {fn, args?, must_equal?, crossCheck?}");
            }
            List<Scenario.OracleEntry> out = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = (String) e.getKey();
                int line = doc.keyLine(map, name);
                name(name, line, "oracle");
                if (!(e.getValue() instanceof Map<?, ?> spec)) {
                    throw errorAt(line, "oracle '" + name + "' must be a mapping {fn, args?, must_equal?,"
                            + " crossCheck?}");
                }
                for (Object key : spec.keySet()) {
                    if (!List.of("fn", "args", "must_equal", "crossCheck").contains(key)) {
                        throw errorAt(lineIn(spec, String.valueOf(key), line), "oracle '" + name + "': unknown key '"
                                + key + "'; allowed: fn, args, must_equal, crossCheck");
                    }
                }
                if (!(spec.get("fn") instanceof String fn) || fn.isBlank()) {
                    throw errorAt(line, "oracle '" + name + "': 'fn' must name an oracle function of the pack");
                }
                Object crossCheck = spec.containsKey("crossCheck") ? crossCheck(spec, name, line) : null;
                out.add(new Scenario.OracleEntry(name, fn.strip(), spec.get("args"), spec.get("must_equal"),
                        spec.containsKey("must_equal"), line, crossCheck));
            }
            return List.copyOf(out);
        }

        /**
         * An oracle's {@code crossCheck} in source form (phase3-contracts.md 3.1): a check id (the shorthand for
         * {@code {check: <id>}}) or a mapping with {@code check} (a name), and optionally {@code tolerance} (a number
         * or a text; the compiler checks it is a decimal of zero or more) and {@code capability} (a name). The id is
         * stripped; whether the pack has the check for the oracle's function is the compiler's matter.
         */
        private Object crossCheck(Map<?, ?> spec, String name, int fallback) throws ScenarioException {
            int line = lineIn(spec, "crossCheck", fallback);
            String where = "oracle '" + name + "': crossCheck";
            Object value = spec.get("crossCheck");
            if (value instanceof String id) {
                if (id.isBlank()) {
                    throw errorAt(line, where + " must name a cross-check of the pack");
                }
                return id.strip();
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw errorAt(line, where + " must be a cross-check id or a mapping {check, tolerance?,"
                        + " capability?}, found " + describe(value));
            }
            for (Object key : map.keySet()) {
                if (!List.of("check", "tolerance", "capability").contains(key)) {
                    throw errorAt(lineIn(map, String.valueOf(key), line), where + ": unknown key '" + key
                            + "'; allowed: check, tolerance, capability");
                }
            }
            if (!(map.get("check") instanceof String check) || check.isBlank()) {
                throw errorAt(line, where + ": 'check' must name a cross-check of the pack"
                        + (map.containsKey("check") ? ", found " + describe(map.get("check")) : " (missing)"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("check", check.strip());
            if (map.containsKey("tolerance")) {
                Object tolerance = map.get("tolerance");
                if (!(tolerance instanceof String) && !(tolerance instanceof Number)) {
                    throw errorAt(lineIn(map, "tolerance", line), where + ": 'tolerance' must be a decimal"
                            + " number or its text, found " + describe(tolerance));
                }
                out.put("tolerance", tolerance);
            }
            if (map.containsKey("capability")) {
                if (!(map.get("capability") instanceof String capability) || capability.isBlank()) {
                    throw errorAt(lineIn(map, "capability", line), where + ": 'capability' must name a capability,"
                            + " found " + describe(map.get("capability")));
                }
                out.put("capability", capability.strip());
            }
            return Collections.unmodifiableMap(out);
        }

        List<Scenario.Step> steps() throws ScenarioException {
            Object value = root.get("steps");
            if (value == null) {
                throw error("steps", "'steps' is required (a list of single-key mappings verb: args)");
            }
            if (!(value instanceof List<?> list)) {
                throw error("steps", "'steps' must be a list of single-key mappings verb: args");
            }
            List<Scenario.Step> out = new ArrayList<>();
            for (Object item : list) {
                int line = doc.lineOf(item);
                if (!(item instanceof Map<?, ?> step) || step.size() != 1) {
                    throw errorAt(line != 0 ? line : doc.keyLine(root, "steps"), "step " + (out.size() + 1)
                            + " must be a single-key mapping verb: args, found " + describe(item)
                            + (item instanceof Map<?, ?> m ? " with keys " + m.keySet() : ""));
                }
                Map.Entry<?, ?> only = step.entrySet().iterator().next();
                String verb = (String) only.getKey();
                if (!VERB.matcher(verb).matches()) {
                    throw errorAt(line, "verb '" + verb + "' must match [A-Za-z][A-Za-z0-9_]*");
                }
                out.add(new Scenario.Step(verb, only.getValue(), line));
            }
            return Collections.unmodifiableList(out);
        }

        List<Scenario.Verify> verify() throws ScenarioException {
            Object value = root.get("verify");
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> list)) {
                throw error("verify", "'verify' must be a list of mappings path: matcher");
            }
            List<Scenario.Verify> out = new ArrayList<>();
            for (Object item : list) {
                int line = doc.lineOf(item);
                if (!(item instanceof Map<?, ?> map) || map.isEmpty()) {
                    throw errorAt(line != 0 ? line : doc.keyLine(root, "verify"),
                            "each 'verify' entry must be a non-empty mapping path: matcher, found " + describe(item));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> expectations = (Map<String, Object>) map;
                for (String key : expectations.keySet()) {
                    if (key.startsWith("db.") && expectations.size() != 1) {
                        throw errorAt(line != 0 ? line : doc.keyLine(root, "verify"), "a 'verify' entry with a"
                                + " stored-row check '" + key + "' must have exactly one key; write each check as its"
                                + " own entry");
                    }
                }
                out.add(new Scenario.Verify(Collections.unmodifiableMap(expectations), line));
            }
            return Collections.unmodifiableList(out);
        }

        Map<String, Object> onPrompt() throws ScenarioException {
            Object value = root.get("on_prompt");
            if (value == null) {
                return Map.of();
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw error("on_prompt", "'on_prompt' must be a mapping such as {unexpected: fail}");
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!"unexpected".equals(e.getKey())) {
                    throw errorAt(doc.keyLine(map, (String) e.getKey()), "unknown key 'on_prompt." + e.getKey()
                            + "'; allowed: unexpected");
                }
                if (!(e.getValue() instanceof String s) || s.isBlank()) {
                    throw errorAt(doc.keyLine(map, "unexpected"), "'on_prompt.unexpected' must be 'fail' or the"
                            + " name of a pack policy, found " + describe(e.getValue()));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("unexpected", ((String) map.get("unexpected")).strip());
            return Collections.unmodifiableMap(out);
        }

        /** {@code ui: {clickRung?: ACTION|COMPONENT, strict?: bool}}; null when the key is absent. */
        UiSettings ui() throws ScenarioException {
            if (!root.containsKey("ui")) {
                return null;
            }
            Object value = root.get("ui");
            if (!(value instanceof Map<?, ?> map)) {
                throw error("ui", "'ui' must be a mapping such as {clickRung: COMPONENT, strict: false}, found "
                        + describe(value));
            }
            for (Object key : map.keySet()) {
                if (!"clickRung".equals(key) && !"strict".equals(key)) {
                    throw errorAt(doc.keyLine(map, (String) key), "unknown key 'ui." + key
                            + "'; allowed: clickRung, strict");
                }
            }
            String rung = UiSettings.DEFAULT.clickRung();
            if (map.containsKey("clickRung")) {
                if (!(map.get("clickRung") instanceof String s) || !(s.strip().equalsIgnoreCase(UiSettings.ACTION)
                        || s.strip().equalsIgnoreCase(UiSettings.COMPONENT))) {
                    throw errorAt(doc.keyLine(map, "clickRung"), "'ui.clickRung' must be ACTION or COMPONENT, found "
                            + describe(map.get("clickRung")));
                }
                rung = s;
            }
            boolean strict = false;
            if (map.containsKey("strict")) {
                if (!(map.get("strict") instanceof Boolean b)) {
                    throw errorAt(doc.keyLine(map, "strict"), "'ui.strict' must be true or false, found "
                            + describe(map.get("strict")));
                }
                strict = b;
            }
            return new UiSettings(rung, strict);
        }

        /**
         * {@code web: {path?: auto|cdp|agent, browser?: {url|tag|title|nativeId: text}, frozenTargets?: fail|reload,
         * trace?: bool}} (phase4-contracts.md 7.2); null when the key is absent. The enumerated values are accepted in
         * any case; {@code frozenTargets: close} is refused (decision D52).
         */
        WebSettings web() throws ScenarioException {
            if (!root.containsKey("web")) {
                return null;
            }
            Object value = root.get("web");
            if (!(value instanceof Map<?, ?> map)) {
                throw error("web", "'web' must be a mapping such as {path: auto, browser: {tag: front}}, found "
                        + describe(value));
            }
            for (Object key : map.keySet()) {
                if (!WEB_KEYS.contains(key)) {
                    throw errorAt(doc.keyLine(map, (String) key), "unknown key 'web." + key + "'; allowed: "
                            + String.join(", ", WEB_KEYS));
                }
            }
            WebSettings defaults = WebSettings.DEFAULT;
            WebSettings.Path path = defaults.path();
            if (map.containsKey("path")) {
                path = map.get("path") instanceof String s ? WebGrammar.path(s) : null;
                if (path == null) {
                    throw errorAt(doc.keyLine(map, "path"), "'web.path' must be auto, cdp or agent, found "
                            + describe(map.get("path")));
                }
            }
            Map<String, Object> browser = null;
            if (map.containsKey("browser")) {
                String problem = WebGrammar.byProblem("web.browser", map.get("browser"));
                if (problem != null) {
                    throw errorAt(doc.keyLine(map, "browser"), problem);
                }
                Map.Entry<?, ?> only = ((Map<?, ?>) map.get("browser")).entrySet().iterator().next();
                browser = new LinkedHashMap<>();
                browser.put(String.valueOf(only.getKey()), only.getValue());
            }
            FrozenTargetPolicy frozen = defaults.frozenTargets();
            if (map.containsKey("frozenTargets")) {
                frozen = map.get("frozenTargets") instanceof String s ? WebGrammar.frozenTargets(s) : null;
                if (frozen == FrozenTargetPolicy.CLOSE) {
                    throw errorAt(doc.keyLine(map, "frozenTargets"), "'web.frozenTargets' "
                            + WebGrammar.CLOSE_REFUSED);
                }
                if (frozen == null) {
                    throw errorAt(doc.keyLine(map, "frozenTargets"), "'web.frozenTargets' must be fail or reload,"
                            + " found " + describe(map.get("frozenTargets")));
                }
            }
            boolean trace = defaults.trace();
            if (map.containsKey("trace")) {
                if (!(map.get("trace") instanceof Boolean b)) {
                    throw errorAt(doc.keyLine(map, "trace"), "'web.trace' must be true or false, found "
                            + describe(map.get("trace")));
                }
                trace = b;
            }
            return new WebSettings(path, browser, frozen, defaults.attachDeadline(), trace);
        }

        /** {@code timeouts.<key>}: {@code step} (default 10 s) or {@code bind} (default 60 s). */
        long timeout(String key, long defaultMs) throws ScenarioException {
            Object value = root.get("timeouts");
            if (value == null) {
                return defaultMs;
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw error("timeouts", "'timeouts' must be a mapping such as {step: 10s, bind: 60s}");
            }
            for (Object k : map.keySet()) {
                if (!"step".equals(k) && !"bind".equals(k)) {
                    throw errorAt(doc.keyLine(map, (String) k), "unknown key 'timeouts." + k
                            + "'; allowed: step, bind");
                }
            }
            if (!map.containsKey(key)) {
                return defaultMs;
            }
            try {
                return Durations.toMillis(map.get(key), "timeouts." + key);
            } catch (ScenarioException e) {
                throw errorAt(doc.keyLine(map, key), e.reason());
            }
        }
    }
}
