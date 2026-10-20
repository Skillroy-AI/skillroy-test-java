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

package ai.skillroy.testjava.scenario.hooks;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.data.yaml.YamlException;
import ai.skillroy.testjava.protocol.hook.ClassPattern;
import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookTarget;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Reads hook catalogues (phase6-contracts.md 5.1, 5.2, 11.2, decision D121) with the project's strict YAML reader:
 * unknown keys, wrong types and duplicate ids are errors naming the source and the line ({@link ScenarioException}).
 * The reader never resolves a class: whether a target exists is the agent's probe.
 *
 * <p>The rules it enforces besides the shape: the catalogue id {@code [a-z][a-z0-9-]*}, a positive version; per entry
 * the name rules of the agent's {@code HookSpec} (the id and its reserved names, the binary class name, the method,
 * every descriptor, the {@code hook.} topic prefix, a note of at most 200 characters), {@code capture} only on a
 * {@code CAPTURE} hook, non-negative {@code secretArgs} and {@code capture.args} indexes, at least one fallback and
 * the first a {@code seam} naming a primitive id ({@code reflect} fallbacks are parsed and refused: not in this
 * version); unique ids, at most {@value HookCatalogue#MAX_HOOKS} hooks. {@code enabled} defaults to true for a class
 * under {@code packages} and false otherwise; an explicit {@code enabled: true} outside them, and a {@code CAPTURE}
 * whose method name suggests a secret without the secret marks, are accepted with a warning
 * ({@link HookCatalogue#warnings()}).
 *
 * <p>Phase 8 (phase8-contracts.md 6.8): {@code deny:} is a list of at most {@value HookCatalogue#MAX_DENY}
 * {@link ClassPattern}s, an enabled entry whose class a pattern matches is an error at its {@code class} line and a
 * disabled one a warning; a {@code packages} item is a package name or a map {@code {name, codeSource?}}, whose
 * qualifier every entry under it inherits (the longest name wins) unless it carries {@code target.codeSource} of its
 * own; an overlay unions the package rules and the deny, and an enabled base entry its deny matches is an error at the
 * overlay's {@code deny} line unless the overlay disables it.
 *
 * <p>Stateless apart from the per-pack cache of {@link #ofPack}; safe from any thread.
 */
public final class HookCatalogues {

    /** Where a pack's catalogue lives: {@code META-INF/skillroy/hooks/<id>.yaml}. */
    public static final String RESOURCE_DIR = "META-INF/skillroy/hooks/";

    /** {@code PrimitiveSpec}'s name pattern: what a {@code seam} fallback must name. */
    static final Pattern PRIMITIVE = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+");

    private static final Set<String> TOP_KEYS = Set.of("catalogue", "version", "application", "hooks", "packages",
            "deny");
    private static final Set<String> OVERLAY_KEYS = Set.of("catalogue", "version", "application", "hooks", "packages",
            "disable", "deny");
    private static final Set<String> ENTRY_KEYS = Set.of("id", "mode", "enabled", "target", "capture", "secretArgs",
            "secretResult", "emits", "fallbacks", "note");
    private static final Set<String> TARGET_KEYS = Set.of("class", "method", "descriptors", "loader", "codeSource");
    private static final Set<String> PACKAGE_KEYS = Set.of("name", "codeSource");
    private static final Set<String> CAPTURE_KEYS = Set.of("args", "self", "result");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** Per pack class and pack id: the catalogue read, or the {@link IllegalStateException} reading it threw. */
    private static final ClassValue<Map<String, Object>> CACHE = new ClassValue<>() {
        @Override
        protected Map<String, Object> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private HookCatalogues() {
    }

    /**
     * Reads one catalogue.
     *
     * @param source the resource or file name, for messages
     * @throws ScenarioException when the YAML is malformed or breaks a rule; the message names {@code source} and the
     *     line
     * @throws IOException when {@code reader} fails
     */
    public static HookCatalogue read(Reader reader, String source) throws ScenarioException, IOException {
        StrictYaml.Doc doc = parse(reader, source);
        Parser parser = new Parser(doc, source);
        Map<?, ?> root = parser.root(TOP_KEYS);
        List<HookCatalogue.PackageRule> rules = parser.packages(root);
        List<String> packages = names(rules);
        List<String> deny = parser.deny(root);
        HookCatalogue.Builder builder = HookCatalogue.builder(parser.catalogueId(root), parser.version(root))
                .application(parser.optString(root, "application"))
                .packageRules(rules)
                .deny(deny);
        Set<String> ids = new HashSet<>();
        for (Map<?, ?> entry : parser.entries(root)) {
            HookEntry hook = parser.entry(entry, packages);
            if (!ids.add(hook.id())) {
                throw parser.error(parser.keyLine(entry, "id"), "duplicate hook id " + hook.id());
            }
            parser.checkDenied(hook, entry, deny, "the catalogue denies");
            builder.add(hook);
        }
        if (ids.size() > HookCatalogue.MAX_HOOKS) {
            throw parser.error(parser.keyLine(root, "hooks"), "the catalogue has " + ids.size() + " hooks, at most "
                    + HookCatalogue.MAX_HOOKS + " are allowed");
        }
        return parser.build(builder, root);
    }

    /**
     * An overlay's catalogue over {@code base} (phase6-contracts.md 11.2): the format of {@link #read} with two
     * differences. {@code catalogue:} is the overlay's id; its {@code hooks} entries replace the base's entries of the
     * same id (every field of the entry) or are added when the id is new; {@code disable: [id…]} turns base entries
     * off. Its {@code packages} are added to the base's; its {@code version} is the result's; {@code application}
     * defaults to the base's. The result's hash is its own.
     *
     * @throws ScenarioException when the YAML is malformed or breaks a rule, or {@code disable} names an id the base
     *     does not have
     * @throws IOException when {@code reader} fails
     */
    public static HookCatalogue overlay(HookCatalogue base, Reader reader, String source)
            throws ScenarioException, IOException {
        StrictYaml.Doc doc = parse(reader, source);
        Parser parser = new Parser(doc, source);
        Map<?, ?> root = parser.root(OVERLAY_KEYS);
        HookCatalogue.Builder builder = HookCatalogue.builder(base).id(parser.catalogueId(root))
                .version(parser.version(root));
        // Package rules are unioned by name; an overlay's qualifier for a name replaces the base's for the overlay's
        // own entries (the base's entries keep the value baked when the base was read; D570). A plain name adds a rule
        // only when the name is new, so that an overlay never drops a base qualifier by writing the plain form.
        List<HookCatalogue.PackageRule> overlayRules = parser.packages(root);
        for (HookCatalogue.PackageRule rule : overlayRules) {
            if (rule.codeSource() != null) {
                builder.packageRules(List.of(rule));
            } else {
                builder.packages(List.of(rule.name()));
            }
        }
        List<String> packages = new ArrayList<>(base.packages());
        for (String pkg : names(overlayRules)) {
            if (!packages.contains(pkg)) {
                packages.add(pkg);
            }
        }
        // the deny is the union of the base's and the overlay's: an overlay never lifts a base deny
        List<String> overlayDeny = parser.deny(root);
        List<String> deny = new ArrayList<>(base.deny());
        for (String pattern : overlayDeny) {
            if (!deny.contains(pattern)) {
                deny.add(pattern);
            }
        }
        if (deny.size() > HookCatalogue.MAX_DENY) {
            throw parser.error(parser.keyLine(root, "deny"), "the catalogue and its overlay have " + deny.size()
                    + " deny patterns, at most " + HookCatalogue.MAX_DENY + " are allowed");
        }
        builder.deny(overlayDeny);
        if (root.containsKey("application")) {
            builder.application(parser.optString(root, "application"));
        }
        Set<String> ids = new HashSet<>();
        for (Map<?, ?> entry : parser.entries(root)) {
            HookEntry hook = parser.entry(entry, packages);
            if (!ids.add(hook.id())) {
                throw parser.error(parser.keyLine(entry, "id"), "duplicate hook id " + hook.id());
            }
            parser.checkDenied(hook, entry, deny, "the catalogue denies");
            if (base.entry(hook.id()) != null) {
                builder.replace(hook);
            } else {
                builder.add(hook);
            }
        }
        Map<String, Integer> disable = parser.disable(root);
        for (Map.Entry<String, Integer> disabled : disable.entrySet()) {
            if (base.entry(disabled.getKey()) == null && !ids.contains(disabled.getKey())) {
                throw parser.error(disabled.getValue(), "disable names hook " + disabled.getKey() + ", which catalogue "
                        + base.id() + " does not have");
            }
            builder.disable(disabled.getKey());
        }
        for (HookEntry entry : base.hooks()) {
            if (!entry.enabled() || ids.contains(entry.id()) || disable.containsKey(entry.id())) {
                continue;
            }
            String denied = ClassPattern.firstMatch(overlayDeny, entry.target().className());
            if (denied != null) {
                throw parser.error(parser.keyLine(root, "deny"), "hook " + entry.id() + " of catalogue " + base.id()
                        + " targets " + entry.target().className() + ", which this overlay denies (" + denied + ");"
                        + " add it to disable");
            }
        }
        return parser.build(builder, root);
    }

    /**
     * The pack's catalogue (phase6-contracts.md 5.2), what {@code DomainPack.hooks()} answers by default: the resource
     * {@code META-INF/skillroy/hooks/<id()>.yaml} of the pack class's loader, read once per pack class and id;
     * {@link HookCatalogue#EMPTY} when there is none.
     *
     * @throws IllegalStateException when the resource is malformed or cannot be read (the message names the resource
     *     and the line); the same exception on every later call
     */
    public static HookCatalogue ofPack(DomainPack pack) {
        String id = pack.id();
        Object cached = CACHE.get(pack.getClass()).computeIfAbsent(String.valueOf(id), key -> load(pack, key));
        if (cached instanceof IllegalStateException e) {
            throw new IllegalStateException(e.getMessage(), e.getCause());
        }
        return (HookCatalogue) cached;
    }

    /** The resource name of a pack's or an overlay's catalogue: {@code META-INF/skillroy/hooks/<id>.yaml}. */
    public static String resourceName(String id) {
        return RESOURCE_DIR + id + ".yaml";
    }

    /**
     * Reads the resource {@code name} through {@code type}'s loader, or answers null when it does not exist.
     *
     * @throws ScenarioException when it is malformed
     * @throws IOException when it cannot be read
     */
    public static HookCatalogue readResource(Class<?> type, String name) throws ScenarioException, IOException {
        URL url = resource(type, name);
        if (url == null) {
            return null;
        }
        try (InputStream in = url.openStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return read(reader, name);
        }
    }

    /**
     * An overlay's catalogue from its resource {@code META-INF/skillroy/hooks/<overlayId>.yaml} of {@code type}'s
     * loader over {@code base}, or {@code base} itself when the resource does not exist.
     *
     * @throws IllegalStateException when the resource is malformed or cannot be read (the message names it)
     */
    public static HookCatalogue overlayResource(HookCatalogue base, Class<?> type, String overlayId) {
        String name = resourceName(overlayId);
        URL url = resource(type, name);
        if (url == null) {
            return base;
        }
        try (InputStream in = url.openStream();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return overlay(base, reader, name);
        } catch (ScenarioException e) {
            throw new IllegalStateException("hook catalogue " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("hook catalogue " + name + " cannot be read: " + e, e);
        }
    }

    private static Object load(DomainPack pack, String id) {
        String name = resourceName(id);
        try {
            HookCatalogue catalogue = readResource(pack.getClass(), name);
            return catalogue == null ? HookCatalogue.EMPTY : catalogue;
        } catch (ScenarioException e) {
            return new IllegalStateException("hook catalogue " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            return new IllegalStateException("hook catalogue " + name + " cannot be read: " + e, e);
        }
    }

    private static List<String> names(List<HookCatalogue.PackageRule> rules) {
        List<String> out = new ArrayList<>();
        for (HookCatalogue.PackageRule rule : rules) {
            out.add(rule.name());
        }
        return out;
    }

    private static URL resource(Class<?> type, String name) {
        ClassLoader loader = type.getClassLoader();
        return loader == null ? ClassLoader.getSystemResource(name) : loader.getResource(name);
    }

    private static StrictYaml.Doc parse(Reader reader, String source) throws ScenarioException, IOException {
        StringWriter text = new StringWriter();
        reader.transferTo(text);
        try {
            return StrictYaml.read(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (YamlException e) {
            throw new ScenarioException(source, e.line(), e.reason());
        }
    }

    /** The checks of one document; every error names the source and a line. */
    private static final class Parser {

        private final StrictYaml.Doc doc;
        private final String source;

        Parser(StrictYaml.Doc doc, String source) {
            this.doc = doc;
            this.source = source;
        }

        ScenarioException error(int line, String reason) {
            return new ScenarioException(source, line, reason);
        }

        int keyLine(Object map, String key) {
            int line = doc.keyLine(map, key);
            return line > 0 ? line : doc.lineOf(map);
        }

        Map<?, ?> root(Set<String> allowed) throws ScenarioException {
            if (!(doc.root() instanceof Map<?, ?> root)) {
                throw error(1, "a hook catalogue is a map with the keys catalogue, version, hooks and packages");
            }
            unknownKeys(root, allowed, "the catalogue");
            return root;
        }

        void unknownKeys(Map<?, ?> map, Set<String> allowed, String what) throws ScenarioException {
            for (Object key : map.keySet()) {
                if (!allowed.contains(String.valueOf(key))) {
                    throw error(keyLine(map, String.valueOf(key)), "unknown key '" + key + "' in " + what
                            + " (allowed: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ")");
                }
            }
        }

        String catalogueId(Map<?, ?> root) throws ScenarioException {
            String id = requireString(root, "catalogue", "the catalogue");
            if (!HookCatalogue.ID.matcher(id).matches()) {
                throw error(keyLine(root, "catalogue"), "the catalogue id must match " + HookCatalogue.ID.pattern()
                        + ", found \"" + id + "\"");
            }
            return id;
        }

        int version(Map<?, ?> root) throws ScenarioException {
            Object value = root.get("version");
            if (!(value instanceof Long number) || number <= 0 || number > Integer.MAX_VALUE) {
                throw error(keyLine(root, "version"), "version must be a positive integer"
                        + (value == null ? " and is missing" : ""));
            }
            return number.intValue();
        }

        /**
         * The package rules: a package name, or a map {@code {name, codeSource?}} (phase8-contracts.md 6.8); the same
         * name twice keeps the first.
         */
        List<HookCatalogue.PackageRule> packages(Map<?, ?> root) throws ScenarioException {
            List<HookCatalogue.PackageRule> out = new ArrayList<>();
            if (!root.containsKey("packages")) {
                return out;
            }
            Object value = root.get("packages");
            if (!(value instanceof List<?> list)) {
                throw error(keyLine(root, "packages"), "packages must be a list of package names");
            }
            Set<String> seen = new HashSet<>();
            for (Object item : list) {
                HookCatalogue.PackageRule rule;
                if (item instanceof Map<?, ?> map) {
                    int at = lineOf(map, root, "packages");
                    unknownKeys(map, PACKAGE_KEYS, "a packages entry");
                    Object name = map.get("name");
                    if (!(name instanceof String pkg) || !PACKAGE.matcher(pkg).matches()) {
                        throw error(name == null ? at : keyLine(map, "name"), "a packages entry needs a name, a"
                                + " package name" + (name == null ? "" : ", found " + name));
                    }
                    String codeSource = optString(map, "codeSource");
                    String violation = HookTarget.codeSourceViolation(codeSource);
                    if (violation != null) {
                        throw error(keyLine(map, "codeSource"), "package " + pkg + ": " + violation);
                    }
                    rule = new HookCatalogue.PackageRule(pkg, codeSource);
                } else if (item instanceof String pkg && PACKAGE.matcher(pkg).matches()) {
                    rule = new HookCatalogue.PackageRule(pkg, null);
                } else {
                    throw error(lineOf(value, root, "packages"), "packages must be a list of package names, found "
                            + item);
                }
                if (seen.add(rule.name())) {
                    out.add(rule);
                }
            }
            return out;
        }

        /** {@code deny:}: at most {@value HookCatalogue#MAX_DENY} class patterns, distinct (phase8-contracts.md 6.8). */
        List<String> deny(Map<?, ?> root) throws ScenarioException {
            List<String> out = new ArrayList<>();
            if (!root.containsKey("deny")) {
                return out;
            }
            Object value = root.get("deny");
            if (!(value instanceof List<?> list)) {
                throw error(keyLine(root, "deny"), "deny must be a list of class patterns");
            }
            for (Object item : list) {
                if (!(item instanceof String pattern)) {
                    throw error(keyLine(root, "deny"), "deny must be a list of class patterns");
                }
                String violation = ClassPattern.violation(pattern);
                if (violation != null) {
                    throw error(keyLine(root, "deny"), "deny pattern '" + pattern + "': " + violation);
                }
                if (!out.contains(pattern)) {
                    out.add(pattern);
                }
            }
            if (out.size() > HookCatalogue.MAX_DENY) {
                throw error(keyLine(root, "deny"), "the catalogue has " + out.size() + " deny patterns, at most "
                        + HookCatalogue.MAX_DENY + " are allowed");
            }
            return out;
        }

        /** An enabled entry on a class {@code deny} matches: an error at the entry's {@code class} line. */
        void checkDenied(HookEntry hook, Map<?, ?> entry, List<String> deny, String verb) throws ScenarioException {
            if (!hook.enabled()) {
                return;
            }
            String denied = ClassPattern.firstMatch(deny, hook.target().className());
            if (denied != null) {
                int line = entry.get("target") instanceof Map<?, ?> target ? keyLine(target, "class")
                        : doc.lineOf(entry);
                throw error(line, "hook " + hook.id() + " targets " + hook.target().className() + ", which " + verb
                        + " (" + denied + ")");
            }
        }

        List<Map<?, ?>> entries(Map<?, ?> root) throws ScenarioException {
            Object value = root.get("hooks");
            if (value == null) {
                if (root.containsKey("hooks")) {
                    return List.of();
                }
                throw error(1, "the catalogue has no 'hooks' list");
            }
            if (!(value instanceof List<?> list)) {
                throw error(keyLine(root, "hooks"), "hooks must be a list of hook entries");
            }
            List<Map<?, ?>> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    throw error(lineOf(value, root, "hooks"), "every hooks entry must be a map");
                }
                out.add(entry);
            }
            return out;
        }

        Map<String, Integer> disable(Map<?, ?> root) throws ScenarioException {
            Map<String, Integer> out = new java.util.LinkedHashMap<>();
            if (!root.containsKey("disable")) {
                return out;
            }
            Object value = root.get("disable");
            if (!(value instanceof List<?> list)) {
                throw error(keyLine(root, "disable"), "disable must be a list of hook ids");
            }
            for (Object item : list) {
                if (!(item instanceof String id)) {
                    throw error(keyLine(root, "disable"), "disable must be a list of hook ids, found " + item);
                }
                out.put(id, keyLine(root, "disable"));
            }
            return out;
        }

        HookEntry entry(Map<?, ?> entry, List<String> packages) throws ScenarioException {
            int line = doc.lineOf(entry);
            unknownKeys(entry, ENTRY_KEYS, "a hook entry");
            String id = requireString(entry, "id", "a hook entry");
            String where = "hook " + id + ": ";
            HookMode mode;
            String modeText = requireString(entry, "mode", where);
            try {
                mode = HookMode.valueOf(modeText);
            } catch (IllegalArgumentException e) {
                throw error(keyLine(entry, "mode"), where + "mode must be OBSERVE, CAPTURE or STUB, found " + modeText);
            }
            Object targetValue = entry.get("target");
            if (!(targetValue instanceof Map<?, ?> target)) {
                throw error(targetValue == null ? line : keyLine(entry, "target"), where + "target must be a map with"
                        + " class, method and descriptors");
            }
            unknownKeys(target, TARGET_KEYS, "the target of hook " + id);
            String className = requireString(target, "class", where + "target");
            String method = requireString(target, "method", where + "target");
            Object descriptorsValue = target.get("descriptors");
            if (!(descriptorsValue instanceof List<?> descriptorList) || descriptorList.isEmpty()) {
                throw error(descriptorsValue == null ? doc.lineOf(target) : keyLine(target, "descriptors"), where
                        + "target.descriptors must be a non-empty list of JVM method descriptors");
            }
            List<String> descriptors = new ArrayList<>();
            for (Object d : descriptorList) {
                if (!(d instanceof String descriptor)) {
                    throw error(keyLine(target, "descriptors"), where + "a descriptor must be a string, found " + d);
                }
                descriptors.add(descriptor);
            }
            String loader = optString(target, "loader");
            String codeSource = optString(target, "codeSource");
            HookCapture capture = null;
            if (entry.containsKey("capture")) {
                if (mode != HookMode.CAPTURE) {
                    throw error(keyLine(entry, "capture"), where + "capture is only allowed on a CAPTURE hook, this"
                            + " one is " + mode);
                }
                Object captureValue = entry.get("capture");
                if (!(captureValue instanceof Map<?, ?> captureMap)) {
                    throw error(keyLine(entry, "capture"), where + "capture must be a map of args, self and result");
                }
                unknownKeys(captureMap, CAPTURE_KEYS, "the capture of hook " + id);
                capture = new HookCapture(indexes(captureMap, "args", where + "capture.args"),
                        optBoolean(captureMap, "self", false, where), optBoolean(captureMap, "result", false, where));
            }
            List<Integer> secretArgs = indexes(entry, "secretArgs", where + "secretArgs");
            boolean secretResult = optBoolean(entry, "secretResult", false, where);
            String emits = optString(entry, "emits");
            String note = optString(entry, "note");
            List<HookEntry.Fallback> fallbacks = fallbacks(entry, where, line);
            boolean enabled = entry.containsKey("enabled") ? optBoolean(entry, "enabled", false, where)
                    : HookCatalogue.inPackages(className, packages);
            HookEntry hook;
            try {
                hook = new HookEntry(id, mode, enabled, new HookTarget(className, method, descriptors, loader,
                        codeSource), capture, secretArgs, secretResult, emits, fallbacks, note);
            } catch (RuntimeException e) {
                throw error(line, where + e.getMessage());
            }
            HookSpec.Violation violation = hook.toSpec().violation();
            if (violation != null) {
                throw error(violationLine(entry, target, violation.field(), line), where + violation);
            }
            return hook;
        }

        private int violationLine(Map<?, ?> entry, Map<?, ?> target, String field, int line) {
            if (field.startsWith("target.")) {
                String key = field.substring("target.".length());
                int bracket = key.indexOf('[');
                return keyLine(target, bracket < 0 ? key : key.substring(0, bracket));
            }
            int line2 = doc.keyLine(entry, field);
            return line2 > 0 ? line2 : line;
        }

        private List<HookEntry.Fallback> fallbacks(Map<?, ?> entry, String where, int line) throws ScenarioException {
            Object value = entry.get("fallbacks");
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                throw error(value == null ? line : keyLine(entry, "fallbacks"), where + "at least one fallback is"
                        + " required, and the first is always a public seam (design 2.6)");
            }
            List<HookEntry.Fallback> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> fallback) || fallback.size() != 1) {
                    throw error(keyLine(entry, "fallbacks"), where + "a fallback is a map with one key, seam");
                }
                int at = doc.lineOf(fallback) > 0 ? doc.lineOf(fallback) : keyLine(entry, "fallbacks");
                if (fallback.containsKey("reflect")) {
                    throw error(at, where + "a reflect fallback is not in this version: only seam fallbacks are"
                            + " honoured (phase6-contracts.md 1)");
                }
                Object seam = fallback.get("seam");
                if (!(seam instanceof String primitive)) {
                    throw error(at, where + "a fallback is a map with one key, seam, naming a primitive id; found "
                            + fallback.keySet());
                }
                if (!PRIMITIVE.matcher(primitive).matches()) {
                    throw error(at, where + "a seam names a primitive id, found \"" + primitive + "\"");
                }
                out.add(new HookEntry.Fallback.Seam(primitive));
            }
            return out;
        }

        private List<Integer> indexes(Map<?, ?> map, String key, String what) throws ScenarioException {
            if (!map.containsKey(key)) {
                return List.of();
            }
            Object value = map.get(key);
            if (!(value instanceof List<?> list)) {
                throw error(keyLine(map, key), what + " must be a list of argument indexes");
            }
            Set<Integer> out = new LinkedHashSet<>();
            for (Object item : list) {
                if (!(item instanceof Long index) || index < 0 || index > 255) {
                    throw error(keyLine(map, key), what + " must hold non-negative argument indexes, found " + item);
                }
                out.add(index.intValue());
            }
            return List.copyOf(out);
        }

        private boolean optBoolean(Map<?, ?> map, String key, boolean dflt, String where) throws ScenarioException {
            if (!map.containsKey(key)) {
                return dflt;
            }
            Object value = map.get(key);
            if (!(value instanceof Boolean flag)) {
                throw error(keyLine(map, key), where + key + " must be true or false");
            }
            return flag;
        }

        String optString(Map<?, ?> map, String key) throws ScenarioException {
            if (!map.containsKey(key) || map.get(key) == null) {
                return null;
            }
            Object value = map.get(key);
            if (!(value instanceof String text)) {
                throw error(keyLine(map, key), key + " must be text");
            }
            return text;
        }

        private String requireString(Map<?, ?> map, String key, String what) throws ScenarioException {
            Object value = map.get(key);
            if (!(value instanceof String text) || text.isBlank()) {
                throw error(value == null ? doc.lineOf(map) : keyLine(map, key), what + (what.endsWith(": ")
                        ? "" : ": ") + "'" + key + "' must be non-empty text" + (value == null ? " and is missing"
                        : ""));
            }
            return text;
        }

        private int lineOf(Object container, Map<?, ?> parent, String key) {
            int line = doc.lineOf(container);
            return line > 0 ? line : keyLine(parent, key);
        }

        HookCatalogue build(HookCatalogue.Builder builder, Map<?, ?> root) throws ScenarioException {
            try {
                return builder.build();
            } catch (IllegalStateException e) {
                throw error(keyLine(root, "hooks"), e.getMessage());
            }
        }
    }
}
