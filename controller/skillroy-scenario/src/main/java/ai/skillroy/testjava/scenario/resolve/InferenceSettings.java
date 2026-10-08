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

package ai.skillroy.testjava.scenario.resolve;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.data.yaml.YamlException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The inference settings of a bind (phase5-contracts.md 4.4, 5.5): which gateway a {@link ResolverContributor} builds
 * and how. Strings, paths, maps, booleans and longs only, so that {@code skillroy-scenario} names no inference type
 * (decision D70); {@code skillroy-inference} interprets them.
 *
 * <p>Built by {@link #load}, in order of precedence, from the options a caller passes (the CLI's {@code --inference},
 * {@code --recordings}, {@code --inference-file}, {@code --budget-scenario}, …, given under the names of the system
 * properties), the system properties ({@value #PROP_GATEWAY}, {@value #PROP_RECORDINGS},
 * {@code skillroy.inference.budget.scenario|job|session}, {@value #PROP_FILE}, {@value #PROP_LOG_PROMPTS},
 * {@value #PROP_WORKSPACE_ID}, {@value #PROP_THRESHOLDS}{@code <key>}), the file {@code inference.yaml}
 * ({@value #PROP_FILE}, else beside the credentials file, else none; read with the project's strict YAML reader,
 * optional, holding no secret), and the defaults:
 *
 * <pre>
 * version: 1
 * gateway: claude                 # claude | recorded | none; replay forces none
 * recordings: inference/          # a directory, relative to the file
 * record: false
 * workspaceId: null               # the workspace an organisation-level key needs; an account id, not a secret
 * models: {opus: claude-opus-5-5, sonnet: claude-sonnet-5-5, haiku: claude-haiku-4-5}
 * effort: {resolve: low, triage: low, session: medium, summary: low}
 * budget: {scenarioUsd: "0.50", jobUsd: null, sessionUsd: "1.00"}
 * thresholds: {confidence: "0.6"} # by name, scalar values kept as text (phase5-contracts.md 5.4, D113)
 * prices: null                    # a price table file, relative to the file; null: the built-in table
 * fallbacks: true
 * images: false
 * timeouts: {callMs: 120000, batchWaitMs: 7200000, batchPollMs: 30000, plannerMs: 900000}
 * logPrompts: false
 * </pre>
 *
 * <p>The gateway: in {@code replay} always {@code none}, whatever is set (D72); otherwise the one an option, a property
 * or the file names, else {@code claude} when the environment variable {@value #KEY_VARIABLE} is set, else
 * {@code none}. Of the model key only whether the variable is set is ever looked at: its value is never read, kept or
 * printed. {@code gateway: claude} without the key, and {@code recorded} (or {@code record: true}) without a
 * recordings directory, are {@link SettingsException}s, as is a file with an unknown key, a value of the wrong type or
 * a {@code version} other than 1; their messages name the file and the line, never a value. The job budget has no
 * default (B6): {@code budgets()} holds {@value #BUDGET_JOB} only when it was set.
 *
 * <p>Immutable (the maps are unmodifiable copies); safe to share between threads.
 *
 * @param gateway {@value #GATEWAY_CLAUDE}, {@value #GATEWAY_RECORDED} or {@value #GATEWAY_NONE}; null or blank is
 *     {@code none}
 * @param recordings the recorded gateway's directory (and where {@code record} writes), or null
 * @param record true: the live gateway is wrapped in the recording gateway (the maintainer's re-record route)
 * @param workspaceId the workspace id an organisation-level model key needs (the provider refuses such a key without
 *     it; the live gateway sends it as {@code anthropic-workspace-id}): an account identifier, not a secret; null when
 *     none is set
 * @param models model ids by class: {@value #MODEL_OPUS}, {@value #MODEL_SONNET}, {@value #MODEL_HAIKU}; null is the
 *     defaults
 * @param effort {@code low}, {@code medium} or {@code high} by purpose: {@code resolve}, {@code triage},
 *     {@code session}, {@code summary}; null is the defaults
 * @param budgets limits in US dollars as plain decimal strings by scope: {@value #BUDGET_SCENARIO},
 *     {@value #BUDGET_JOB} (only when set), {@value #BUDGET_SESSION}; null is the defaults
 * @param thresholds the gates of a model's answer by name ({@code confidence}, default {@code "0.6"},
 *     phase5-contracts.md 5.4, D113), each value a scalar kept as text for the inference module to read; null is the
 *     defaults. A threshold is set by the file's {@code thresholds:} map, by the property or option
 *     {@value #PROP_THRESHOLDS}{@code <key>} (for a key the defaults, the file or the options name, or that the JVM's
 *     system properties name under that prefix), and keeps its default otherwise
 * @param prices a price table file, or null for the built-in table
 * @param fallbacks the provider's refusal fallback on (phase5-contracts.md 4.1)
 * @param images whether an image part may be sent (false until Phase 7)
 * @param timeouts milliseconds: {@value #TIMEOUT_CALL_MS}, {@value #TIMEOUT_BATCH_WAIT_MS},
 *     {@value #TIMEOUT_BATCH_POLL_MS}, {@value #TIMEOUT_PLANNER_MS}; null is the defaults
 * @param logPrompts true: masked prompt text goes into {@code inference.jsonl}
 * @param file the settings file that was read, or null
 */
public record InferenceSettings(String gateway, Path recordings, boolean record, String workspaceId,
                                Map<String, String> models, Map<String, String> effort, Map<String, String> budgets,
                                Map<String, String> thresholds, Path prices, boolean fallbacks, boolean images,
                                Map<String, Long> timeouts, boolean logPrompts, Path file) {

    /** The settings file's name, looked for beside the credentials file. */
    public static final String FILE_NAME = "inference.yaml";
    /** The settings file format version this code reads. */
    public static final int VERSION = 1;

    /** The live gateway. */
    public static final String GATEWAY_CLAUDE = "claude";
    /** The gateway that answers from committed recordings. */
    public static final String GATEWAY_RECORDED = "recorded";
    /** No gateway: what {@code replay} always uses. */
    public static final String GATEWAY_NONE = "none";

    /** The environment variable that holds the model key; only whether it is set is looked at. */
    public static final String KEY_VARIABLE = "ANTHROPIC_API_KEY";

    /** System property and option: the gateway. */
    public static final String PROP_GATEWAY = "skillroy.inference";
    /** System property and option: the recordings directory. */
    public static final String PROP_RECORDINGS = "skillroy.inference.recordings";
    /** System property and option: the per-scenario budget in US dollars. */
    public static final String PROP_BUDGET_SCENARIO = "skillroy.inference.budget.scenario";
    /** System property and option: the per-job budget in US dollars. */
    public static final String PROP_BUDGET_JOB = "skillroy.inference.budget.job";
    /** System property and option: the per-session budget in US dollars. */
    public static final String PROP_BUDGET_SESSION = "skillroy.inference.budget.session";
    /** System property and option: the settings file. */
    public static final String PROP_FILE = "skillroy.inference.file";
    /** System property and option: {@code true} logs masked prompts. */
    public static final String PROP_LOG_PROMPTS = "skillroy.inference.logPrompts";
    /** System property and option: the workspace id an organisation-level key needs. */
    public static final String PROP_WORKSPACE_ID = "skillroy.inference.workspaceId";
    /** The prefix of the system properties and options that set a threshold: {@code skillroy.inference.thresholds.<key>}. */
    public static final String PROP_THRESHOLDS = "skillroy.inference.thresholds.";

    /** The keys {@link #load} accepts as options: the property names, and {@value #PROP_THRESHOLDS}{@code <key>}. */
    public static final Set<String> OPTION_KEYS = Set.of(PROP_GATEWAY, PROP_RECORDINGS, PROP_BUDGET_SCENARIO,
            PROP_BUDGET_JOB, PROP_BUDGET_SESSION, PROP_FILE, PROP_LOG_PROMPTS, PROP_WORKSPACE_ID);

    /** {@link #models()} key of the Opus class. */
    public static final String MODEL_OPUS = "opus";
    /** {@link #models()} key of the Sonnet class. */
    public static final String MODEL_SONNET = "sonnet";
    /** {@link #models()} key of the Haiku class. */
    public static final String MODEL_HAIKU = "haiku";

    /** {@link #budgets()} key of the per-scenario budget. */
    public static final String BUDGET_SCENARIO = "scenario";
    /** {@link #budgets()} key of the per-job budget (no default). */
    public static final String BUDGET_JOB = "job";
    /** {@link #budgets()} key of the per-session budget. */
    public static final String BUDGET_SESSION = "session";

    /** {@link #timeouts()} key of a gateway call's deadline. */
    public static final String TIMEOUT_CALL_MS = "callMs";
    /** {@link #timeouts()} key of the longest wait for a batch. */
    public static final String TIMEOUT_BATCH_WAIT_MS = "batchWaitMs";
    /** {@link #timeouts()} key of the interval between batch status reads. */
    public static final String TIMEOUT_BATCH_POLL_MS = "batchPollMs";
    /** {@link #timeouts()} key of the most the planner ({@code skillroy author}) may take over all its rounds. */
    public static final String TIMEOUT_PLANNER_MS = "plannerMs";

    /** The default model ids (phase5-contracts.md 2.2, decision D84). */
    public static final Map<String, String> DEFAULT_MODELS = ordered(MODEL_OPUS, "claude-opus-5-5", MODEL_SONNET,
            "claude-sonnet-5-5", MODEL_HAIKU, "claude-haiku-4-5");
    /** The default effort by purpose. */
    public static final Map<String, String> DEFAULT_EFFORT = ordered("resolve", "low", "triage", "low", "session",
            "medium", "summary", "low");
    /** The default budgets: per scenario 0.50 (B6), per session 1.00 (B15); per job none (B6). */
    public static final Map<String, String> DEFAULT_BUDGETS = ordered(BUDGET_SCENARIO, "0.50", BUDGET_SESSION,
            "1.00");
    /**
     * The default thresholds: a model's stated confidence of at least 0.6 (phase5-contracts.md 5.4, B23 Q3; decision
     * D113: the live model stated 0.8, 0.8, 0.8 and 0.7 for four correct answers to the sample's supervisor query, so
     * 0.8 refused about one correct answer in four).
     */
    public static final Map<String, String> DEFAULT_THRESHOLDS = ordered("confidence", "0.6");
    /** The default timeouts. */
    public static final Map<String, Long> DEFAULT_TIMEOUTS;

    static {
        Map<String, Long> timeouts = new LinkedHashMap<>();
        timeouts.put(TIMEOUT_CALL_MS, 120_000L);
        timeouts.put(TIMEOUT_BATCH_WAIT_MS, 7_200_000L);
        timeouts.put(TIMEOUT_BATCH_POLL_MS, 30_000L);
        timeouts.put(TIMEOUT_PLANNER_MS, 900_000L);
        DEFAULT_TIMEOUTS = Collections.unmodifiableMap(timeouts);
    }

    private static final String MODE_PROPERTY = "skillroy.mode";
    private static final int MAX_FILE_BYTES = 1 << 20;
    private static final List<String> GATEWAYS = List.of(GATEWAY_CLAUDE, GATEWAY_RECORDED, GATEWAY_NONE);
    private static final List<String> EFFORTS = List.of("low", "medium", "high");
    private static final List<String> TOP_KEYS = List.of("version", "gateway", "recordings", "record", "workspaceId",
            "models", "effort", "budget", "thresholds", "prices", "fallbacks", "images", "timeouts", "logPrompts");
    private static final List<String> BUDGET_KEYS = List.of("scenarioUsd", "jobUsd", "sessionUsd");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,199}");
    private static final Pattern WORKSPACE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}");
    /** A threshold's name, in the file and after {@link #PROP_THRESHOLDS}. */
    private static final Pattern THRESHOLD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final String THRESHOLD_KEY_RULE = "a name of letters, digits, '_' and '-' that starts with a"
            + " letter, at most 64";

    private static final InferenceSettings NONE = new InferenceSettings(GATEWAY_NONE, null, false, null, null, null,
            null, null, null, true, false, null, false, null);

    /**
     * Copies the maps; a null map is the defaults, a null or blank gateway {@code none}, a blank workspace id null.
     *
     * @throws NullPointerException if a map holds a null key or value
     */
    public InferenceSettings {
        gateway = gateway == null || gateway.isBlank() ? GATEWAY_NONE : gateway.strip();
        workspaceId = workspaceId == null || workspaceId.isBlank() ? null : workspaceId.strip();
        models = copy(models, DEFAULT_MODELS, "models");
        effort = copy(effort, DEFAULT_EFFORT, "effort");
        budgets = copy(budgets, DEFAULT_BUDGETS, "budgets");
        thresholds = copy(thresholds, DEFAULT_THRESHOLDS, "thresholds");
        timeouts = copy(timeouts, DEFAULT_TIMEOUTS, "timeouts");
    }

    /** Gateway {@code none} with every other setting at its default: what {@code replay} uses. */
    public static InferenceSettings none() {
        return NONE;
    }

    /** True unless the gateway is {@code none}. */
    public boolean enabled() {
        return !GATEWAY_NONE.equals(gateway);
    }

    /**
     * The settings the JUnit glue uses: the system properties, the file and the defaults, for the mode the system
     * property {@code skillroy.mode} names ({@code replay} when it names none), with the environment's model key.
     *
     * @param credentialsDir where the credentials file was located (or the bindings root's environment directory when
     *     there is none): {@code inference.yaml} is looked for there unless {@value #PROP_FILE} names a file; may be
     *     null
     * @throws SettingsException as {@link #load(BindMode, Path, Map, UnaryOperator, UnaryOperator)}; also when
     *     {@code skillroy.mode} names no mode
     */
    public static InferenceSettings fromSystemProperties(Path credentialsDir) throws SettingsException {
        String modeText = System.getProperty(MODE_PROPERTY);
        BindMode mode;
        try {
            mode = modeText == null || modeText.isBlank() ? BindMode.REPLAY : BindMode.parse(modeText);
        } catch (IllegalArgumentException e) {
            throw new SettingsException(MODE_PROPERTY + " names no mode; modes: replay, approve, live, re-resolve");
        }
        return load(mode, credentialsDir, Map.of(), System::getProperty, System::getenv);
    }

    /**
     * The settings of an invocation in {@code mode}: the options over the system properties over the file over the
     * defaults, with the environment's model key.
     *
     * @param options values under the names of the system properties ({@link #OPTION_KEYS}); null is none
     * @throws SettingsException as {@link #load(BindMode, Path, Map, UnaryOperator, UnaryOperator)}
     */
    public static InferenceSettings load(BindMode mode, Path credentialsDir, Map<String, String> options)
            throws SettingsException {
        return load(mode, credentialsDir, options, System::getProperty, System::getenv);
    }

    /**
     * The settings of an invocation in {@code mode}, with the lookups given (tests and embedders pass their own;
     * production code passes {@code System::getProperty} and {@code System::getenv}).
     *
     * @param mode the bind mode; null is {@code replay}
     * @param credentialsDir where {@code inference.yaml} is looked for when no file is named; may be null
     * @param options values under the names of the system properties ({@link #OPTION_KEYS}, and
     *     {@value #PROP_THRESHOLDS}{@code <key>}), which win over the properties; null is none
     * @param properties the system properties; a threshold is looked up for every key the defaults, the file or the
     *     options name, and for every key the JVM's system properties name under {@value #PROP_THRESHOLDS}
     * @param environment the environment; only {@code environment.apply(}{@value #KEY_VARIABLE}{@code ) != null} is
     *     ever asked
     * @throws SettingsException when a named file is missing, the file is unreadable, malformed or against the
     *     grammar, an option or property has a value of the wrong shape, the gateway is {@code claude} (outside
     *     {@code replay}) without the model key, or {@code recorded} or {@code record} lacks a recordings directory;
     *     the message names the file and line or the property, never a value
     * @throws IllegalArgumentException for an option key that is not one of {@link #OPTION_KEYS} nor
     *     {@value #PROP_THRESHOLDS} followed by a threshold's name
     */
    public static InferenceSettings load(BindMode mode, Path credentialsDir, Map<String, String> options,
                                         UnaryOperator<String> properties, UnaryOperator<String> environment)
            throws SettingsException {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        BindMode effective = mode == null ? BindMode.REPLAY : mode;
        Map<String, String> given = options == null ? Map.of() : options;
        for (String key : given.keySet()) {
            if (!OPTION_KEYS.contains(key) && !(key.startsWith(PROP_THRESHOLDS)
                    && THRESHOLD_KEY.matcher(key.substring(PROP_THRESHOLDS.length())).matches())) {
                throw new IllegalArgumentException("unknown inference option " + key + "; options: "
                        + List.copyOf(new java.util.TreeSet<>(OPTION_KEYS)) + " and " + PROP_THRESHOLDS + "<key>");
            }
        }
        UnaryOperator<String> lookup = key -> given.containsKey(key) ? given.get(key) : properties.apply(key);
        Builder b = new Builder();
        String fileText = blankToNull(lookup.apply(PROP_FILE));
        Path file = null;
        if (fileText != null) {
            file = path(fileText, PROP_FILE);
            if (!Files.isRegularFile(file)) {
                throw new SettingsException("inference settings file " + file + (Files.exists(file)
                        ? " is not a regular file" : " does not exist") + " (" + PROP_FILE + ")");
            }
        } else if (credentialsDir != null) {
            Path beside = credentialsDir.resolve(FILE_NAME);
            if (Files.isRegularFile(beside)) {
                file = beside;
            }
        }
        if (file != null) {
            new FileReader(file).read(b);
        }
        String gatewayText = blankToNull(lookup.apply(PROP_GATEWAY));
        if (gatewayText != null) {
            String g = gatewayText.strip().toLowerCase(Locale.ROOT);
            if (!GATEWAYS.contains(g)) {
                throw new SettingsException(PROP_GATEWAY + " must be claude, recorded or none");
            }
            b.gateway = g;
        }
        String recordingsText = blankToNull(lookup.apply(PROP_RECORDINGS));
        if (recordingsText != null) {
            b.recordings = path(recordingsText.strip(), PROP_RECORDINGS);
        }
        budgetProperty(b, lookup, PROP_BUDGET_SCENARIO, BUDGET_SCENARIO);
        budgetProperty(b, lookup, PROP_BUDGET_JOB, BUDGET_JOB);
        budgetProperty(b, lookup, PROP_BUDGET_SESSION, BUDGET_SESSION);
        String logText = blankToNull(lookup.apply(PROP_LOG_PROMPTS));
        if (logText != null) {
            String t = logText.strip().toLowerCase(Locale.ROOT);
            if (!t.equals("true") && !t.equals("false")) {
                throw new SettingsException(PROP_LOG_PROMPTS + " must be true or false");
            }
            b.logPrompts = Boolean.parseBoolean(t);
        }
        String workspaceText = blankToNull(lookup.apply(PROP_WORKSPACE_ID));
        if (workspaceText != null) {
            String id = workspaceText.strip();
            if (!WORKSPACE_ID.matcher(id).matches()) {
                throw new SettingsException(PROP_WORKSPACE_ID + " must be a workspace id (letters, digits, '_', '-',"
                        + " '.' and ':', at most 200)");
            }
            b.workspaceId = id;
        }
        thresholdProperties(b, given, lookup);
        boolean keySet = environment.apply(KEY_VARIABLE) != null;
        String gateway;
        if (effective == BindMode.REPLAY) {
            gateway = GATEWAY_NONE;
        } else if (b.gateway != null) {
            gateway = b.gateway;
        } else {
            gateway = keySet ? GATEWAY_CLAUDE : GATEWAY_NONE;
        }
        if (GATEWAY_CLAUDE.equals(gateway) && !keySet) {
            throw new SettingsException("the model key is not set (" + KEY_VARIABLE + ")");
        }
        if (GATEWAY_RECORDED.equals(gateway) && b.recordings == null) {
            throw new SettingsException("the recorded gateway needs a recordings directory (recordings in "
                    + FILE_NAME + ", " + PROP_RECORDINGS + " or --recordings)");
        }
        if (b.record && GATEWAY_CLAUDE.equals(gateway) && b.recordings == null) {
            throw new SettingsException("record: true needs a recordings directory to write to (recordings in "
                    + FILE_NAME + ", " + PROP_RECORDINGS + " or --recordings)");
        }
        return new InferenceSettings(gateway, b.recordings, b.record, b.workspaceId, b.models, b.effort, b.budgets,
                b.thresholds, b.prices, b.fallbacks, b.images, b.timeouts, b.logPrompts, file);
    }

    // ------------------------------------------------------------------------------------------- internals

    /**
     * {@value #PROP_THRESHOLDS}{@code <key>} through {@code lookup} (an option over a property) for every key the
     * defaults or the file name, then for the keys the options and the JVM's system properties name under the prefix,
     * new ones in name order. A blank value leaves the threshold as it was; a property under the prefix that has a
     * value but names no threshold is refused, without its name's tail or its value.
     */
    private static void thresholdProperties(Builder b, Map<String, String> options, UnaryOperator<String> lookup)
            throws SettingsException {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(b.thresholds.keySet());
        java.util.SortedSet<String> named = new java.util.TreeSet<>();
        for (String key : options.keySet()) {
            if (key.startsWith(PROP_THRESHOLDS)) {
                named.add(key.substring(PROP_THRESHOLDS.length()));
            }
        }
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(PROP_THRESHOLDS)) {
                named.add(name.substring(PROP_THRESHOLDS.length()));
            }
        }
        keys.addAll(named);
        for (String key : keys) {
            String text = blankToNull(lookup.apply(PROP_THRESHOLDS + key));
            if (text == null) {
                continue;
            }
            if (!THRESHOLD_KEY.matcher(key).matches()) {
                throw new SettingsException("a property under " + PROP_THRESHOLDS + " does not name a threshold ("
                        + THRESHOLD_KEY_RULE + ")");
            }
            b.thresholds.put(key, text.strip());
        }
    }

    private static void budgetProperty(Builder b, UnaryOperator<String> lookup, String property, String scope)
            throws SettingsException {
        String text = blankToNull(lookup.apply(property));
        if (text == null) {
            return;
        }
        String amount = amount(text.strip());
        if (amount == null) {
            throw new SettingsException(property + " must be an amount of US dollars of at least 0, written as a"
                    + " plain decimal such as 0.50");
        }
        b.budgets.put(scope, amount);
    }

    /** A plain decimal string of at least 0, or null when the text is not one. */
    private static String amount(String text) {
        if (text.isEmpty() || text.length() > 40) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(text);
            return value.signum() < 0 ? null : value.toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path path(String text, String where) throws SettingsException {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new SettingsException(where + " is not a valid path");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static <V> Map<String, V> copy(Map<String, V> map, Map<String, V> defaults, String what) {
        if (map == null) {
            return defaults;
        }
        Map<String, V> out = new LinkedHashMap<>();
        for (Map.Entry<String, V> e : map.entrySet()) {
            Objects.requireNonNull(e.getKey(), what + " holds a null key");
            Objects.requireNonNull(e.getValue(), what + " holds a null value");
            out.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    /** What the file and the properties set, over the defaults. */
    private static final class Builder {

        String gateway;
        Path recordings;
        boolean record;
        String workspaceId;
        final Map<String, String> models = new LinkedHashMap<>(DEFAULT_MODELS);
        final Map<String, String> effort = new LinkedHashMap<>(DEFAULT_EFFORT);
        final Map<String, String> budgets = new LinkedHashMap<>(DEFAULT_BUDGETS);
        final Map<String, String> thresholds = new LinkedHashMap<>(DEFAULT_THRESHOLDS);
        Path prices;
        boolean fallbacks = true;
        boolean images;
        final Map<String, Long> timeouts = new LinkedHashMap<>(DEFAULT_TIMEOUTS);
        boolean logPrompts;
    }

    /**
     * Reads {@code inference.yaml} against its grammar; messages name the file, the line and the fixed keys of the
     * grammar, never a value and never the text of a key the grammar does not fix (it may be a value typed in the wrong
     * place), as the credentials reader does.
     */
    private static final class FileReader {

        private final Path file;
        private final String name;
        private StrictYaml.Doc doc;

        FileReader(Path file) {
            this.file = file;
            this.name = file.toString();
        }

        void read(Builder b) throws SettingsException {
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            } catch (IOException e) {
                throw new SettingsException("inference settings file " + name + " cannot be read ("
                        + e.getClass().getName() + ")");
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new SettingsException(name + ": an inference settings file is at most 1 MiB");
            }
            try {
                doc = StrictYaml.read(bytes);
            } catch (YamlException e) {
                throw new SettingsException(where(e.line()) + (e.quotesInput()
                        ? "not valid YAML (the reader's message is withheld because it can quote a value; check the"
                                + " syntax near this line)"
                        : e.reason()));
            }
            if (!(doc.root() instanceof Map<?, ?>)) {
                throw new SettingsException(where(doc.lineOf(doc.root())) + "an inference settings file must be a"
                        + " YAML mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) doc.root();
            keys(root, TOP_KEYS, "a top-level key", null);
            if (!root.containsKey("version")) {
                throw new SettingsException(where(doc.lineOf(root)) + "'version' is required (version: 1)");
            }
            Object version = root.get("version");
            if (!(version instanceof Long n) || n != VERSION) {
                throw error(root, "version", "'version' must be 1; this version reads inference settings of version"
                        + " 1 only");
            }
            Object gateway = root.get("gateway");
            if (gateway != null) {
                if (!(gateway instanceof String g) || !GATEWAYS.contains(g)) {
                    throw error(root, "gateway", "gateway must be claude, recorded or none");
                }
                b.gateway = g;
            }
            String recordings = text(root, "recordings", "recordings must be a directory");
            if (recordings != null) {
                b.recordings = resolve(root, "recordings", recordings);
            }
            b.record = bool(root, "record", b.record);
            Object workspace = root.get("workspaceId");
            if (workspace != null) {
                if (!(workspace instanceof String id) || !WORKSPACE_ID.matcher(id.strip()).matches()) {
                    throw error(root, "workspaceId", "workspaceId must be a workspace id (letters, digits, '_', '-',"
                            + " '.' and ':', at most 200), written as a text");
                }
                b.workspaceId = id.strip();
            }
            Map<String, Object> models = map(root, "models");
            keys(models, List.copyOf(DEFAULT_MODELS.keySet()), "a key", "models");
            for (String key : models.keySet()) {
                Object value = models.get(key);
                if (value == null) {
                    continue;
                }
                if (!(value instanceof String id) || !MODEL_ID.matcher(id).matches()) {
                    throw error(models, key, "models." + key + " must be a model id");
                }
                b.models.put(key, id);
            }
            Map<String, Object> effort = map(root, "effort");
            keys(effort, List.copyOf(DEFAULT_EFFORT.keySet()), "a key", "effort");
            for (String key : effort.keySet()) {
                Object value = effort.get(key);
                if (value == null) {
                    continue;
                }
                if (!(value instanceof String e) || !EFFORTS.contains(e)) {
                    throw error(effort, key, "effort." + key + " must be low, medium or high");
                }
                b.effort.put(key, e);
            }
            Map<String, Object> budget = map(root, "budget");
            keys(budget, BUDGET_KEYS, "a key", "budget");
            for (String key : budget.keySet()) {
                Object value = budget.get(key);
                if (value == null) {
                    continue;
                }
                String amount = value instanceof String s ? amount(s.strip())
                        : value instanceof Long l ? amount(Long.toString(l))
                        : value instanceof Double d ? amount(BigDecimal.valueOf(d).toPlainString()) : null;
                if (amount == null) {
                    throw error(budget, key, "budget." + key + " must be an amount of US dollars of at least 0,"
                            + " written as a plain decimal such as \"0.50\"");
                }
                b.budgets.put(key.substring(0, key.length() - "Usd".length()), amount);
            }
            Map<String, Object> thresholds = map(root, "thresholds");
            for (String key : thresholds.keySet()) {
                if (!THRESHOLD_KEY.matcher(key).matches()) {
                    throw error(thresholds, key, "thresholds: a key must be " + THRESHOLD_KEY_RULE);
                }
                Object value = thresholds.get(key);
                if (value == null) {
                    continue;
                }
                String text = value instanceof String s ? s.strip()
                        : value instanceof Long l ? Long.toString(l)
                        : value instanceof Double d ? BigDecimal.valueOf(d).toPlainString()
                        : value instanceof Boolean flag ? flag.toString() : null;
                if (text == null || text.isEmpty()) {
                    throw error(thresholds, key, "thresholds: a value must be a non-empty text, a number or a"
                            + " boolean");
                }
                b.thresholds.put(key, text);
            }
            String prices = text(root, "prices", "prices must be a file");
            if (prices != null) {
                b.prices = resolve(root, "prices", prices);
            }
            b.fallbacks = bool(root, "fallbacks", b.fallbacks);
            b.images = bool(root, "images", b.images);
            b.logPrompts = bool(root, "logPrompts", b.logPrompts);
            Map<String, Object> timeouts = map(root, "timeouts");
            keys(timeouts, List.copyOf(DEFAULT_TIMEOUTS.keySet()), "a key", "timeouts");
            for (String key : timeouts.keySet()) {
                Object value = timeouts.get(key);
                if (value == null) {
                    continue;
                }
                if (!(value instanceof Long ms) || ms < 1) {
                    throw error(timeouts, key, "timeouts." + key + " must be a whole number of milliseconds of at"
                            + " least 1");
                }
                b.timeouts.put(key, ms);
            }
        }

        private void keys(Map<String, Object> map, List<String> allowed, String noun, String parent)
                throws SettingsException {
            for (String key : map.keySet()) {
                if (!allowed.contains(key)) {
                    throw error(map, key, (parent == null ? "" : parent + ": ") + noun + " is not known; allowed"
                            + " keys: " + String.join(", ", allowed));
                }
            }
        }

        private String text(Map<String, Object> map, String key, String what) throws SettingsException {
            Object value = map.get(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof String s) || s.isBlank()) {
                throw error(map, key, what + " path, a non-empty text");
            }
            return s.strip();
        }

        private Path resolve(Map<String, Object> map, String key, String text) throws SettingsException {
            try {
                Path p = Path.of(text);
                Path dir = file.toAbsolutePath().getParent();
                return p.isAbsolute() || dir == null ? p : dir.resolve(p).normalize();
            } catch (InvalidPathException e) {
                throw error(map, key, key + " is not a valid path");
            }
        }

        private boolean bool(Map<String, Object> map, String key, boolean dflt) throws SettingsException {
            Object value = map.get(key);
            if (value == null) {
                return dflt;
            }
            if (!(value instanceof Boolean flag)) {
                throw error(map, key, key + " must be true or false");
            }
            return flag;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> map(Map<String, Object> map, String key) throws SettingsException {
            Object value = map.get(key);
            if (value == null) {
                return Map.of();
            }
            if (!(value instanceof Map<?, ?>)) {
                throw error(map, key, key + " must be a mapping");
            }
            return (Map<String, Object>) value;
        }

        private String where(int line) {
            return name + (line > 0 ? ":" + line : "") + ": ";
        }

        private SettingsException error(Map<String, Object> map, String key, String reason) {
            return new SettingsException(where(doc.keyLine(map, key)) + reason);
        }
    }
}
