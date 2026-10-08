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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InferenceSettings} (phase5-contracts.md 4.4, 5.5; acceptance 15.1 item 16): the file read strictly, the
 * properties and options over it, the defaults, the default-gateway rule with a controlled environment, {@code replay}
 * forcing {@code none}, and the model key never read beyond whether it is set.
 */
class InferenceSettingsTest {

    /** A value that must never appear in a message: the dummy model key. */
    private static final String KEY = "sk-dummy-key-0f9e8d7c6b5a";

    @TempDir
    Path dir;

    private final Map<String, String> properties = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private String keyValue;

    /** The environment: only the model key's variable, recorded when asked. */
    private final UnaryOperator<String> environment = name -> {
        asked.add(name);
        return InferenceSettings.KEY_VARIABLE.equals(name) ? keyValue : null;
    };

    private InferenceSettings load(BindMode mode) throws SettingsException {
        return load(mode, Map.of());
    }

    private InferenceSettings load(BindMode mode, Map<String, String> options) throws SettingsException {
        return InferenceSettings.load(mode, dir, options, properties::get, environment);
    }

    private Path file(String yaml) throws Exception {
        Path file = dir.resolve(InferenceSettings.FILE_NAME);
        Files.writeString(file, yaml);
        return file;
    }

    private SettingsException refused(BindMode mode) {
        SettingsException e = assertThrows(SettingsException.class, () -> load(mode));
        assertFalse(e.getMessage().contains(KEY), e.getMessage());
        return e;
    }

    @Test
    void noneIsTheGatewayNoneWithEveryDefault() {
        InferenceSettings none = InferenceSettings.none();
        assertEquals("none", none.gateway());
        assertFalse(none.enabled());
        assertNull(none.recordings());
        assertFalse(none.record());
        assertNull(none.workspaceId());
        assertEquals(Map.of("opus", "claude-opus-5-5", "sonnet", "claude-sonnet-5-5", "haiku", "claude-haiku-4-5"),
                none.models());
        assertEquals(Map.of("resolve", "low", "triage", "low", "session", "medium", "summary", "low"),
                none.effort());
        assertEquals(Map.of("scenario", "0.50", "session", "1.00"), none.budgets(), "the job budget has no default");
        assertEquals(Map.of("confidence", "0.6"), none.thresholds(), "phase5-contracts.md 5.4 (D113): 0.6");
        assertNull(none.prices());
        assertTrue(none.fallbacks());
        assertFalse(none.images());
        assertEquals(Map.of("callMs", 120_000L, "batchWaitMs", 7_200_000L, "batchPollMs", 30_000L, "plannerMs",
                900_000L), none.timeouts(), "plannerMs: the planner's 15 minutes (phase5-contracts.md 10.2)");
        assertFalse(none.logPrompts());
        assertNull(none.file());
        assertSame(none, InferenceSettings.none());

        InferenceSettings blank = new InferenceSettings(" ", null, false, " ", null, null, null, null, null, true,
                false, null, false, null);
        assertEquals(none, blank, "null maps are the defaults, a blank gateway none, a blank workspace null");
        Map<String, String> models = new LinkedHashMap<>(Map.of("sonnet", "x"));
        Map<String, String> thresholds = new LinkedHashMap<>(Map.of("confidence", "0.9"));
        InferenceSettings copied = new InferenceSettings("recorded", dir, false, null, models, null, null,
                thresholds, null, true, false, null, false, null);
        models.put("opus", "y");
        thresholds.put("rows", "5");
        assertEquals(Map.of("sonnet", "x"), copied.models(), "copied");
        assertEquals(Map.of("confidence", "0.9"), copied.thresholds(), "copied too, taken as given");
        assertThrows(UnsupportedOperationException.class, () -> copied.models().put("haiku", "z"));
        assertThrows(UnsupportedOperationException.class, () -> copied.thresholds().put("rows", "1"));
        Map<String, String> withNull = new HashMap<>();
        withNull.put("sonnet", null);
        assertThrows(NullPointerException.class, () -> new InferenceSettings("none", null, false, null, withNull,
                null, null, null, null, true, false, null, false, null));
        assertThrows(NullPointerException.class, () -> new InferenceSettings("none", null, false, null, null, null,
                null, withNull, null, true, false, null, false, null));
    }

    @Test
    void theFileIsReadAndItsPathsResolvedAgainstItsDirectory() throws Exception {
        Path file = file("""
                version: 1
                gateway: recorded
                recordings: inference/
                record: true
                workspaceId: wrkspc_01ABC
                models: {sonnet: claude-sonnet-x}
                effort: {resolve: high, session: low}
                budget: {scenarioUsd: "0.25", jobUsd: 2, sessionUsd: 0.75}
                thresholds: {rows: 5, confidence: 0.85, strict: true, note: " text ", unset: null}
                prices: prices.yaml
                fallbacks: false
                images: true
                timeouts: {callMs: 5000, batchPollMs: 1000}
                logPrompts: true
                """);
        InferenceSettings s = load(BindMode.LIVE);
        assertEquals("recorded", s.gateway());
        assertEquals(dir.resolve("inference").toAbsolutePath().normalize(), s.recordings());
        assertTrue(s.record());
        assertEquals("wrkspc_01ABC", s.workspaceId());
        assertEquals(Map.of("opus", "claude-opus-5-5", "sonnet", "claude-sonnet-x", "haiku", "claude-haiku-4-5"),
                s.models(), "a key overrides its default and keeps the others");
        assertEquals(Map.of("resolve", "high", "triage", "low", "session", "low", "summary", "low"), s.effort());
        assertEquals(Map.of("scenario", "0.25", "session", "0.75", "job", "2"), s.budgets());
        assertEquals(List.of("confidence", "rows", "strict", "note"), List.copyOf(s.thresholds().keySet()),
                "the default's key keeps its place, the file's follow in file order; a null value sets nothing");
        assertEquals(Map.of("confidence", "0.85", "rows", "5", "strict", "true", "note", "text"), s.thresholds(),
                "scalars kept as text");
        assertEquals(dir.resolve("prices.yaml").toAbsolutePath().normalize(), s.prices());
        assertFalse(s.fallbacks());
        assertTrue(s.images());
        assertEquals(Map.of("callMs", 5000L, "batchWaitMs", 7_200_000L, "batchPollMs", 1000L, "plannerMs",
                900_000L), s.timeouts());
        assertTrue(s.logPrompts());
        assertEquals(file, s.file());
        assertEquals(List.of(InferenceSettings.KEY_VARIABLE), asked, "the environment is asked for the key only");

        Path elsewhere = dir.resolve("other/settings.yaml");
        Files.createDirectories(elsewhere.getParent());
        Files.writeString(elsewhere, "version: 1\ngateway: none\n");
        properties.put(InferenceSettings.PROP_FILE, elsewhere.toString());
        assertEquals(elsewhere, load(BindMode.LIVE).file(), "skillroy.inference.file names another file");
        assertEquals("none", load(BindMode.LIVE).gateway());
        properties.put(InferenceSettings.PROP_FILE, dir.resolve("missing.yaml").toString());
        assertTrue(refused(BindMode.LIVE).getMessage().contains("does not exist"));
        properties.remove(InferenceSettings.PROP_FILE);
        Files.delete(file);
        assertEquals(InferenceSettings.none(), load(BindMode.LIVE), "no file beside the credentials: the defaults");
        assertEquals(InferenceSettings.none(), InferenceSettings.load(BindMode.LIVE, null, null, properties::get,
                environment), "no credentials directory either");
    }

    @Test
    void optionsWinOverPropertiesWhichWinOverTheFile() throws Exception {
        file("""
                version: 1
                gateway: recorded
                recordings: from-file
                budget: {scenarioUsd: "0.10", jobUsd: "5"}
                thresholds: {confidence: "0.7", rows: "3", depth: "2"}
                workspaceId: from-file
                logPrompts: false
                """);
        properties.put(InferenceSettings.PROP_RECORDINGS, "from-property");
        properties.put(InferenceSettings.PROP_BUDGET_SCENARIO, "0.20");
        properties.put(InferenceSettings.PROP_LOG_PROMPTS, "TRUE");
        properties.put(InferenceSettings.PROP_WORKSPACE_ID, "from-property");
        properties.put(InferenceSettings.PROP_THRESHOLDS + "confidence", " 0.9 ");
        properties.put(InferenceSettings.PROP_THRESHOLDS + "rows", " ");
        InferenceSettings s = load(BindMode.APPROVE);
        assertEquals(Path.of("from-property"), s.recordings());
        assertEquals("0.20", s.budgets().get("scenario"));
        assertEquals("5", s.budgets().get("job"), "the file's, untouched by the properties");
        assertTrue(s.logPrompts());
        assertEquals("from-property", s.workspaceId());
        assertEquals(Map.of("confidence", "0.9", "rows", "3", "depth", "2"), s.thresholds(), "a property over the"
                + " file; a blank property sets nothing");

        Map<String, String> options = new LinkedHashMap<>(Map.of(InferenceSettings.PROP_GATEWAY, "none",
                InferenceSettings.PROP_BUDGET_SCENARIO, "0.30", InferenceSettings.PROP_BUDGET_JOB, "1.00",
                InferenceSettings.PROP_BUDGET_SESSION, "0", InferenceSettings.PROP_RECORDINGS, "from-option",
                InferenceSettings.PROP_THRESHOLDS + "confidence", "0.95"));
        options.put(InferenceSettings.PROP_THRESHOLDS + "zeta", "1");
        options.put(InferenceSettings.PROP_THRESHOLDS + "alpha", "2");
        InferenceSettings o = load(BindMode.APPROVE, options);
        assertEquals("none", o.gateway());
        assertEquals(Map.of("scenario", "0.30", "job", "1.00", "session", "0"), o.budgets());
        assertEquals(Path.of("from-option"), o.recordings());
        assertEquals("0.95", o.thresholds().get("confidence"), "an option over the property");
        assertEquals(List.of("confidence", "rows", "depth", "alpha", "zeta"), List.copyOf(o.thresholds().keySet()),
                "an option may name a new threshold; new ones follow in name order");
        assertThrows(IllegalArgumentException.class, () -> load(BindMode.LIVE, Map.of("skillroy.mode", "live")),
                "an option the settings do not know is a caller's mistake");
        assertThrows(IllegalArgumentException.class, () -> load(BindMode.LIVE, Map.of(InferenceSettings
                .PROP_THRESHOLDS + "not a name", "1")), "and so is a threshold option that names no threshold");
        properties.clear();
        properties.put(InferenceSettings.PROP_THRESHOLDS + "unheard", "4");
        assertEquals(Map.of("confidence", "0.7", "rows", "3", "depth", "2"), load(BindMode.LIVE).thresholds(),
                "a lookup can only answer for the keys it is asked: the defaults', the file's, the options' and the"
                        + " JVM's system properties'");
    }

    @Test
    void theDefaultGatewayIsClaudeOnlyWithTheKeyOutsideReplay() throws Exception {
        keyValue = KEY;
        for (BindMode mode : List.of(BindMode.LIVE, BindMode.APPROVE, BindMode.RE_RESOLVE)) {
            InferenceSettings s = load(mode);
            assertEquals("claude", s.gateway(), mode.label());
            assertFalse(s.toString().contains(KEY), "the key's value is never kept");
        }
        assertEquals("none", load(BindMode.REPLAY).gateway(), "replay: none, key or not");
        assertEquals("none", InferenceSettings.load(null, dir, null, properties::get, environment).gateway(),
                "no mode is replay");
        properties.put(InferenceSettings.PROP_GATEWAY, "claude");
        assertEquals("none", load(BindMode.REPLAY).gateway(), "replay forces none whatever is set");
        properties.put(InferenceSettings.PROP_GATEWAY, "recorded");
        properties.put(InferenceSettings.PROP_RECORDINGS, "rec");
        assertEquals("recorded", load(BindMode.LIVE).gateway(), "a gateway named wins over the key");
        assertEquals("none", load(BindMode.REPLAY).gateway());
        properties.clear();

        keyValue = null;
        assertEquals("none", load(BindMode.LIVE).gateway(), "no key, nothing named: none");
        properties.put(InferenceSettings.PROP_GATEWAY, "Claude");
        assertEquals("the model key is not set (ANTHROPIC_API_KEY)", refused(BindMode.LIVE).getMessage());
        assertEquals("none", load(BindMode.REPLAY).gateway(), "replay needs no key");
        keyValue = "";
        assertEquals("claude", load(BindMode.LIVE).gateway(), "set means set: the value is never looked at");
        assertTrue(asked.stream().allMatch(InferenceSettings.KEY_VARIABLE::equals), asked.toString());
    }

    /**
     * phase5-contracts.md 10.2: the planner's deadline is a setting, {@code timeouts.plannerMs} (15 minutes when not
     * set), which the strict reader accepts like the other timeouts (L1 F7: it refused the key).
     */
    @Test
    void thePlannersDeadlineIsASetting() throws Exception {
        assertEquals(900_000L, InferenceSettings.none().timeouts().get(InferenceSettings.TIMEOUT_PLANNER_MS));
        file("version: 1\ntimeouts: {plannerMs: 600000}\n");
        InferenceSettings s = load(BindMode.LIVE);
        assertEquals(600_000L, s.timeouts().get(InferenceSettings.TIMEOUT_PLANNER_MS));
        assertEquals(120_000L, s.timeouts().get(InferenceSettings.TIMEOUT_CALL_MS), "the others keep their defaults");
    }

    @Test
    void combinationsThatCannotWorkAreRefused() throws Exception {
        properties.put(InferenceSettings.PROP_GATEWAY, "recorded");
        assertTrue(refused(BindMode.LIVE).getMessage().startsWith("the recorded gateway needs a recordings"
                + " directory"));
        keyValue = KEY;
        properties.put(InferenceSettings.PROP_GATEWAY, "claude");
        file("version: 1\nrecord: true\n");
        assertTrue(refused(BindMode.LIVE).getMessage().startsWith("record: true needs a recordings directory"));
        properties.put(InferenceSettings.PROP_RECORDINGS, "rec");
        assertTrue(load(BindMode.LIVE).record());
    }

    @Test
    void aFileAgainstTheGrammarIsRefusedNamingTheLineButNoValue() throws Exception {
        String secretish = "zz-not-for-messages-42";
        Map<String, Integer> cases = new LinkedHashMap<>();
        cases.put("version: 1\ngateway: recorded\n" + secretish + ": 1\n", 3);
        cases.put("version: 1\nmodels:\n  sonnet: x\n  " + secretish + ": y\n", 4);
        cases.put("version: 1\ngateway: " + secretish + "\n", 2);
        cases.put("version: 1\nrecord: " + secretish + "\n", 2);
        cases.put("version: 1\nrecordings: 7\n", 2);
        cases.put("version: 1\neffort: {resolve: " + secretish + "}\n", 2);
        cases.put("version: 1\nbudget: {scenarioUsd: \"" + secretish + "\"}\n", 2);
        cases.put("version: 1\nbudget: {jobUsd: \"-1\"}\n", 2);
        cases.put("version: 1\ntimeouts: {callMs: 0}\n", 2);
        cases.put("version: 1\ntimeouts: {plannerMs: 0}\n", 2);
        cases.put("version: 1\ntimeouts: {callMs: \"" + secretish + "\"}\n", 2);
        cases.put("version: 1\nmodels: " + secretish + "\n", 2);
        cases.put("version: 1\nmodels: {opus: \"" + secretish + " with spaces\"}\n", 2);
        cases.put("version: 1\nworkspaceId: \"" + secretish + " x\"\n", 2);
        cases.put("version: 1\nlogPrompts: \"" + secretish + "\"\n", 2);
        cases.put("version: 1\nthresholds: " + secretish + "\n", 2);
        cases.put("version: 1\nthresholds:\n  confidence: [\"" + secretish + "\"]\n", 3);
        cases.put("version: 1\nthresholds:\n  rows: 1\n  \"" + secretish + " x\": 2\n", 4);
        cases.put("version: 1\nthresholds:\n  confidence: \" \"\n", 3);
        cases.put("version: 1\nthresholds:\n  confidence: {" + secretish + ": 1}\n", 3);
        cases.put("version: 2\n", 1);
        cases.put("version: \"1\"\n", 1);
        cases.put("version: 1.0\n", 1);
        for (Map.Entry<String, Integer> c : cases.entrySet()) {
            file(c.getKey());
            SettingsException e = refused(BindMode.LIVE);
            String expected = dir.resolve(InferenceSettings.FILE_NAME) + ":" + c.getValue() + ": ";
            assertTrue(e.getMessage().startsWith(expected), c.getKey() + " -> " + e.getMessage());
            assertFalse(e.getMessage().contains(secretish), "a message never quotes a value: " + e.getMessage());
        }
        file("gateway: none\n");
        assertTrue(refused(BindMode.LIVE).getMessage().endsWith("'version' is required (version: 1)"));
        file("- just\n- a list\n");
        assertTrue(refused(BindMode.LIVE).getMessage().endsWith("an inference settings file must be a YAML mapping"));
        file("version: 1\ngateway: [unclosed\n  " + secretish + "\n");
        SettingsException malformed = refused(BindMode.LIVE);
        assertFalse(malformed.getMessage().contains(secretish), malformed.getMessage());
        file("version: 1\nversion: 1\n");
        assertTrue(refused(BindMode.LIVE).getMessage().contains("duplicate key"));
        file("version: 1\ngateway: none\nunknownKey: 1\n");
        String unknown = refused(BindMode.LIVE).getMessage();
        assertTrue(unknown.endsWith("a top-level key is not known; allowed keys: version, gateway, recordings, record,"
                + " workspaceId, models, effort, budget, thresholds, prices, fallbacks, images, timeouts, logPrompts"),
                unknown);
        assertFalse(unknown.contains("unknownKey"), "the text of an unknown key is not quoted either");
    }

    @Test
    void propertiesOfTheWrongShapeAreRefusedWithoutTheirValue() {
        String bad = "zz-property-value-17";
        for (String property : List.of(InferenceSettings.PROP_GATEWAY, InferenceSettings.PROP_BUDGET_SCENARIO,
                InferenceSettings.PROP_BUDGET_JOB, InferenceSettings.PROP_BUDGET_SESSION,
                InferenceSettings.PROP_LOG_PROMPTS, InferenceSettings.PROP_WORKSPACE_ID)) {
            properties.clear();
            properties.put(property, bad + " !");
            SettingsException e = refused(BindMode.LIVE);
            assertTrue(e.getMessage().startsWith(property + " must be"), e.getMessage());
            assertFalse(e.getMessage().contains(bad), e.getMessage());
        }
        properties.clear();
        properties.put(InferenceSettings.PROP_BUDGET_SCENARIO, "-0.01");
        assertTrue(refused(BindMode.LIVE).getMessage().startsWith(InferenceSettings.PROP_BUDGET_SCENARIO));
        properties.put(InferenceSettings.PROP_BUDGET_SCENARIO, " 1e1 ");
        properties.put(InferenceSettings.PROP_GATEWAY, " ");
        assertEquals("10", assertDoesNotThrow().budgets().get("scenario"), "a plain decimal; blank is not set");
    }

    private InferenceSettings assertDoesNotThrow() {
        try {
            return load(BindMode.LIVE);
        } catch (SettingsException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    @Test
    void fromSystemPropertiesReadsTheModeTheGatewayAndTheFile() throws Exception {
        file("version: 1\nrecordings: rec\n");
        String rows = InferenceSettings.PROP_THRESHOLDS + "rows";
        String bad = InferenceSettings.PROP_THRESHOLDS + "zz bad name";
        try {
            System.setProperty("skillroy.mode", "live");
            System.setProperty(InferenceSettings.PROP_GATEWAY, "recorded");
            System.setProperty(rows, "7");
            InferenceSettings s = InferenceSettings.fromSystemProperties(dir);
            assertEquals("recorded", s.gateway());
            assertEquals(dir.resolve("rec").toAbsolutePath().normalize(), s.recordings());
            assertEquals(Map.of("confidence", "0.6", "rows", "7"), s.thresholds(), "a system property may name a"
                    + " new threshold");
            System.setProperty(bad, "zz-value-31");
            SettingsException badName = assertThrows(SettingsException.class, () -> InferenceSettings
                    .fromSystemProperties(dir));
            assertTrue(badName.getMessage().startsWith("a property under skillroy.inference.thresholds. does not name"
                    + " a threshold"), badName.getMessage());
            assertFalse(badName.getMessage().contains("zz"), "neither its name's tail nor its value");
            assertEquals(Map.of("confidence", "0.6"), InferenceSettings.load(BindMode.LIVE, null, null, name -> null,
                    name -> null).thresholds(), "with another lookup a system property's name is only a key to ask"
                    + " for, never a value");
            System.clearProperty(bad);
            System.setProperty("skillroy.mode", "replay");
            assertEquals("none", InferenceSettings.fromSystemProperties(dir).gateway());
            System.clearProperty("skillroy.mode");
            assertEquals("none", InferenceSettings.fromSystemProperties(dir).gateway(), "no mode is replay");
            System.setProperty("skillroy.mode", "sometimes");
            assertEquals("skillroy.mode names no mode; modes: replay, approve, live, re-resolve", assertThrows(
                    SettingsException.class, () -> InferenceSettings.fromSystemProperties(dir)).getMessage());
        } finally {
            System.clearProperty("skillroy.mode");
            System.clearProperty(InferenceSettings.PROP_GATEWAY);
            System.clearProperty(rows);
            System.clearProperty(bad);
        }
    }
}
