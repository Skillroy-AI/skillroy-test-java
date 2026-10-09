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

package ai.skillroy.testjava.scenario.observe;

import ai.skillroy.testjava.scenario.resolve.SettingsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TelemetrySettings} (phase6-contracts.md 10.3, decisions D140, D141): every rule of the settings table, the
 * precedence options over properties over the file over the defaults, the loopback rule by literal address, the
 * header rules (a literal refused, the model key's variables refused, an unset variable named, a value never read
 * here and never in a message), and {@code OTEL_*} never asked.
 */
class TelemetrySettingsTest {

    /** A header value that must never appear in a message or a setting. */
    private static final String TOKEN = "otlp-token-5c4b3a2d1e";

    @TempDir
    Path dir;

    private final Map<String, String> properties = new HashMap<>();
    private final Map<String, String> env = new HashMap<>();
    private final List<String> asked = new ArrayList<>();

    private final UnaryOperator<String> environment = name -> {
        asked.add(name);
        return env.get(name);
    };

    private TelemetrySettings load(Map<String, String> options) throws SettingsException {
        return TelemetrySettings.load(dir, options, "9.9.9", properties::get, environment);
    }

    private SettingsException refused(Map<String, String> options) {
        SettingsException e = assertThrows(SettingsException.class, () -> load(options));
        assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        return e;
    }

    private Path file(String yaml) throws Exception {
        Path file = dir.resolve(TelemetrySettings.FILE_NAME);
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void offByDefaultWithTheDefaults() throws Exception {
        TelemetrySettings s = load(Map.of());
        assertFalse(s.enabled());
        assertEquals("none", s.exporter());
        assertEquals("skillroy", s.serviceName());
        assertEquals("9.9.9", s.serviceVersion());
        assertEquals("standard", s.attributes());
        assertEquals(10_000L, s.timeoutMs());
        assertEquals("none", TelemetrySettings.none().exporter());
        assertTrue(asked.isEmpty(), "nothing of the environment is asked with telemetry off: " + asked);
        assertEquals("(development build)", new TelemetrySettings(null, null, null, null, null, null, null, 0, null)
                .serviceVersion());
    }

    @Test
    void otlpToTheDefaultLoopbackEndpoint() throws Exception {
        TelemetrySettings s = load(Map.of(TelemetrySettings.PROP_EXPORTER, "otlp"));
        assertTrue(s.enabled());
        assertEquals("http://127.0.0.1:4318", s.endpoint());
        assertEquals("http://127.0.0.1:4318/v1/traces", s.tracesUrl());
        assertEquals("127.0.0.1:4318", s.endpointHostPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:4318", "http://127.0.0.2:4318", "http://127.255.0.9:9", "http://localhost:4318",
            "http://LOCALHOST:4318/base", "http://[::1]:4318", "https://collector.example.org", "https://10.0.0.5:4318",
            "https://collector.example.org:443/otlp/"})
    void acceptedEndpoints(String endpoint) throws Exception {
        TelemetrySettings s = load(Map.of(TelemetrySettings.PROP_EXPORTER, "otlp", TelemetrySettings.PROP_ENDPOINT,
                endpoint));
        assertEquals(endpoint, s.endpoint());
        assertTrue(s.tracesUrl().endsWith("/v1/traces") && !s.tracesUrl().contains("//v1"), s.tracesUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://10.0.0.5:4318", "http://collector.example.org:4318", "http://0.0.0.0:4318",
            "http://localhost.example.org:4318", "http://127.0.0.1.nip.io:4318", "http://[::2]:4318",
            "ftp://127.0.0.1/x", "127.0.0.1:4318", "http:///v1", "https://user:pw@collector.example.org",
            "https://collector.example.org/?token=x", "not a url"})
    void refusedEndpoints(String endpoint) {
        SettingsException e = refused(Map.of(TelemetrySettings.PROP_EXPORTER, "otlp",
                TelemetrySettings.PROP_ENDPOINT, endpoint));
        assertTrue(e.getMessage().startsWith(TelemetrySettings.PROP_ENDPOINT + ": "), e.getMessage());
        assertFalse(e.getMessage().contains("pw@") || e.getMessage().contains("token=x"), e.getMessage());
    }

    @Test
    void headersComeFromNamedVariablesWhoseValuesAreNeverRead() throws Exception {
        file("""
                version: 1
                exporter: otlp
                endpoint: https://collector.example.org
                headers: {Authorization: "env:OTLP_TOKEN", x-team: env:TEAM_ID}
                """);
        env.put("OTLP_TOKEN", TOKEN);
        env.put("TEAM_ID", "lending-desk");
        TelemetrySettings s = load(Map.of());
        assertEquals(Map.of("Authorization", "OTLP_TOKEN", "x-team", "TEAM_ID"), s.headerVariables());
        assertFalse(s.toString().contains(TOKEN), "the settings never hold a header's value");
        assertTrue(asked.stream().allMatch(n -> n.equals("OTLP_TOKEN") || n.equals("TEAM_ID")),
                "only the named variables are asked, never OTEL_*: " + asked);

        env.remove("OTLP_TOKEN");
        SettingsException unset = refused(Map.of());
        assertTrue(unset.getMessage().contains("OTLP_TOKEN") && unset.getMessage().contains("not set"),
                unset.getMessage());
        assertFalse(unset.getMessage().contains("Authorization"), "the header's name is not echoed");
    }

    @Test
    void aLiteralHeaderIsRefused() throws Exception {
        file("version: 1\nexporter: otlp\nheaders: {Authorization: \"Bearer " + TOKEN + "\"}\n");
        SettingsException e = refused(Map.of());
        assertTrue(e.getMessage().contains("telemetry.yaml:3") && e.getMessage().contains("environment variable"),
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_CUSTOM_HEADERS",
            "ANTHROPIC_WORKSPACE_ID", "ANTHROPIC_BASE_URL", "anthropic_api_key"})
    void aHeaderNamingTheModelKeysVariableIsRefused(String variable) throws Exception {
        env.put(variable, TOKEN);
        file("version: 1\nexporter: otlp\nheaders: {x-api-key: env:" + variable + "}\n");
        SettingsException e = refused(Map.of());
        assertTrue(e.getMessage().contains("may not carry the model key"), e.getMessage());
    }

    @Test
    void theFileExporterNeedsADirectory() throws Exception {
        SettingsException e = refused(Map.of(TelemetrySettings.PROP_EXPORTER, "file"));
        assertTrue(e.getMessage().contains("needs a directory"), e.getMessage());
        TelemetrySettings s = load(Map.of(TelemetrySettings.PROP_EXPORTER, "file", TelemetrySettings.PROP_OUT,
                dir.resolve("out/telemetry").toString()));
        assertEquals(dir.resolve("out/telemetry"), s.out());
        file("version: 1\nexporter: file\nout: spans\n");
        assertEquals(dir.resolve("spans").toAbsolutePath(), load(Map.of()).out().toAbsolutePath(),
                "a relative out: is the file's directory's");
    }

    @Test
    void optionsOverPropertiesOverTheFileOverTheDefaults() throws Exception {
        file("""
                version: 1
                exporter: file
                out: from-file
                attributes: minimal
                serviceName: desk-ci
                timeoutMs: 2500
                """);
        TelemetrySettings fromFile = load(Map.of());
        assertEquals("file", fromFile.exporter());
        assertTrue(fromFile.minimal());
        assertEquals("desk-ci", fromFile.serviceName());
        assertEquals(2500L, fromFile.timeoutMs());
        assertEquals(dir.resolve(TelemetrySettings.FILE_NAME), fromFile.file());

        properties.put(TelemetrySettings.PROP_EXPORTER, "otlp");
        properties.put(TelemetrySettings.PROP_ATTRIBUTES, "standard");
        properties.put(TelemetrySettings.PROP_TIMEOUT_MS, "700");
        TelemetrySettings fromProperties = load(Map.of());
        assertEquals("otlp", fromProperties.exporter());
        assertFalse(fromProperties.minimal());
        assertEquals(700L, fromProperties.timeoutMs());

        TelemetrySettings fromOptions = load(Map.of(TelemetrySettings.PROP_EXPORTER, "none"));
        assertFalse(fromOptions.enabled(), "an option wins: --telemetry none turns the file's exporter off");
    }

    @Test
    void aNamedFileMustExistAndTheGrammarIsStrict() throws Exception {
        assertTrue(refused(Map.of(TelemetrySettings.PROP_FILE, dir.resolve("nope.yaml").toString())).getMessage()
                .contains("does not exist"));
        file("exporter: otlp\n");
        assertTrue(refused(Map.of()).getMessage().contains("'version' is required"));
        file("version: 2\n");
        assertTrue(refused(Map.of()).getMessage().contains("'version' must be 1"));
        file("version: 1\nexporters: otlp\n");
        assertTrue(refused(Map.of()).getMessage().contains("not known"));
        file("version: 1\nexporter: grpc\n");
        assertTrue(refused(Map.of()).getMessage().contains("exporter must be none, otlp or file"));
        file("version: 1\nattributes: everything\n");
        assertTrue(refused(Map.of()).getMessage().contains("attributes must be standard or minimal"));
        file("version: 1\ntimeoutMs: 0\n");
        assertTrue(refused(Map.of()).getMessage().contains("timeoutMs"));
        file("version: 1\n");
        assertFalse(load(Map.of()).enabled());
    }

    @Test
    void propertiesOfTheWrongShapeAreRefusedWithoutTheirValue() {
        properties.put(TelemetrySettings.PROP_EXPORTER, "jaeger-" + TOKEN);
        assertTrue(refused(Map.of()).getMessage().startsWith(TelemetrySettings.PROP_EXPORTER + " must be"));
        properties.clear();
        properties.put(TelemetrySettings.PROP_EXPORTER, "otlp");
        properties.put(TelemetrySettings.PROP_TIMEOUT_MS, "200000");
        assertTrue(refused(Map.of()).getMessage().contains(TelemetrySettings.PROP_TIMEOUT_MS));
        properties.put(TelemetrySettings.PROP_TIMEOUT_MS, "100");
        properties.put(TelemetrySettings.PROP_ATTRIBUTES, "some");
        assertTrue(refused(Map.of()).getMessage().contains(TelemetrySettings.PROP_ATTRIBUTES));
        assertThrows(IllegalArgumentException.class, () -> load(Map.of("skillroy.telemetry.headers", "x")));
    }

    @Test
    void traceparentsAreValidatedAsW3c() {
        String valid = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        assertEquals(valid, RunObservers.traceparent(valid));
        assertEquals(valid, RunObservers.traceparent(" " + valid.toUpperCase() + " "));
        assertNull(RunObservers.traceparent("00-00000000000000000000000000000000-b7ad6b7169203331-01"));
        assertNull(RunObservers.traceparent("00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"));
        assertNull(RunObservers.traceparent("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
        assertNull(RunObservers.traceparent("00-abc-def-01"));
        assertNull(RunObservers.traceparent(42L));
        assertNull(RunObservers.traceparent(null));
    }
}
