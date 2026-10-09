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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.protocol.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The telemetry options of the CLI (phase6-contracts.md 10.3, 10.6, 13.1 item 8): {@code --telemetry file} writes
 * {@code <out>/telemetry/spans.jsonl} beside the run's outputs and names the trace in {@code run-start}, {@code --json},
 * the JUnit report and the summary line; every rule of the settings is a usage error (exit 64) whose text names the
 * option, the file and line or the variable, never a value; off by default, a child JVM with {@code -Xlog:class+load}
 * loads no {@code io.opentelemetry} class; and a header's value, which reaches the OTLP endpoint, appears in no output
 * and no file.
 */
class TelemetryCommandTest {

    /** A header's value, set in the child JVM's environment only. */
    private static final String TOKEN = "otlp-cli-token-9f8e7d6c5b";

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenarios;
    private HttpServer stub;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenarios = tmp.resolve("scenarios");
        Scenarios.write(scenarios, "pass.yaml", Scenarios.PASS);
        Scenarios.write(scenarios, "wrong.yaml", Scenarios.WRONG);
    }

    @AfterEach
    void tearDown() {
        agent.close();
        if (stub != null) {
            stub.stop(0);
        }
    }

    private CliHarness.Outcome run(String... args) {
        List<String> all = new ArrayList<>(List.of("run"));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    private String file(String name) {
        return scenarios.resolve(name).toString();
    }

    @Test
    void theFileExporterWritesSpansBesideTheRunOutputsAndTheTraceIsNamedEverywhere() throws Exception {
        Path out = tmp.resolve("out");
        Path junit = tmp.resolve("junit.xml");
        CliHarness.Outcome outcome = run(file("pass.yaml"), file("wrong.yaml"), "--out", out.toString(), "--junit",
                junit.toString(), "--telemetry", "file", "--json").expectExit(Exit.FAILED);
        Map<String, Object> json = Json.parseObject(outcome.out());
        @SuppressWarnings("unchecked")
        Map<String, Object> telemetry = (Map<String, Object>) json.get("telemetry");
        assertEquals("file", telemetry.get("exporter"));
        String traceId = (String) telemetry.get("traceId");
        assertTrue(traceId.matches("[0-9a-f]{32}"), traceId);

        Path spans = out.resolve("telemetry").resolve("spans.jsonl");
        assertTrue(Files.isRegularFile(spans), "beside the run outputs: " + spans);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : Files.readAllLines(spans)) {
            lines.add(Json.parseObject(line));
        }
        List<Object> names = lines.stream().map(l -> l.get("name")).toList();
        assertTrue(names.contains("skillroy run") && names.contains("scenario poke-twice")
                && names.contains("scenario wrong-count"), String.valueOf(names));
        assertTrue(lines.stream().allMatch(l -> traceId.equals(l.get("traceId"))), "one trace per invocation");

        for (String id : List.of("poke-twice", "wrong-count")) {
            Map<String, Object> start = Json.parseObject(Files.readAllLines(out.resolve(id).resolve("run.jsonl"))
                    .get(0));
            assertEquals(traceId, start.get("traceId"), id + "'s run-start");
        }
        assertTrue(Files.readString(junit).contains("<system-out>traceId=" + traceId + "</system-out>"));
        assertTrue(outcome.err().contains("telemetry: file " + out.resolve("telemetry") + ", trace " + traceId
                + ", 0 batches dropped"), outcome.err());
    }

    @Test
    void withoutTelemetryNothingIsNamed() throws Exception {
        Path out = tmp.resolve("out");
        CliHarness.Outcome outcome = run(file("pass.yaml"), "--out", out.toString(), "--json").expectExit(Exit.OK);
        assertEquals(null, Json.parseObject(outcome.out()).get("telemetry"));
        assertFalse(Files.exists(out.resolve("telemetry")));
        assertFalse(Files.readAllLines(out.resolve("poke-twice/run.jsonl")).get(0).contains("traceId"));
        assertFalse(outcome.err().contains("telemetry:"), outcome.err());
    }

    @Test
    void everySettingsRuleIsAUsageErrorThatNamesNoValue() throws Exception {
        Path out = tmp.resolve("out");
        assertUsage(run(file("pass.yaml"), "--telemetry", "jaeger"), "skillroy.telemetry must be none, otlp or file");
        assertUsage(run(file("pass.yaml"), "--telemetry", "file"), "the file exporter needs a directory");
        assertUsage(run(file("pass.yaml"), "--telemetry", "otlp", "--telemetry-endpoint",
                "http://collector.example.org:4318/" + TOKEN), "must be a literal loopback address");
        assertUsage(run(file("pass.yaml"), "--telemetry", "otlp", "--telemetry-attributes", "everything"),
                "standard or minimal");
        Path yaml = tmp.resolve("telemetry.yaml");
        Files.writeString(yaml, "version: 1\nexporter: otlp\nheaders: {Authorization: \"Bearer " + TOKEN + "\"}\n");
        assertUsage(run(file("pass.yaml"), "--telemetry-file", yaml.toString()), "telemetry.yaml:3");
        Files.writeString(yaml, "version: 1\nexporter: otlp\nheaders: {x-api-key: env:ANTHROPIC_API_KEY}\n");
        assertUsage(run(file("pass.yaml"), "--telemetry-file", yaml.toString()), "may not carry the model key");
        Files.writeString(yaml, "version: 1\nexporter: otlp\nheaders: {x-key: env:SKILLROY_TEST_NEVER_SET}\n");
        assertUsage(run(file("pass.yaml"), "--telemetry-file", yaml.toString(), "--out", out.toString()),
                "SKILLROY_TEST_NEVER_SET");
        assertEquals(0, agent.rpcRequests("trace.mark").size(), "found before the agent is contacted");
    }

    private void assertUsage(CliHarness.Outcome outcome, String says) {
        outcome.expectExit(Exit.USAGE);
        assertTrue(outcome.err().contains("the telemetry settings cannot be used") && outcome.err().contains(says),
                outcome.err());
        assertFalse(outcome.err().contains(TOKEN) || outcome.out().contains(TOKEN), "no value: " + outcome.err());
    }

    // ------------------------------------------------------------------------------------------------ child JVMs

    private record Child(int exit, String out, String err) {
    }

    private Child child(Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Xlog:class+load=info:file=" + tmp.resolve("class-load.log"), "-cp",
                System.getProperty("java.class.path"), Main.class.getName(), "run"));
        command.addAll(List.of(args));
        command.addAll(connection);
        command.addAll(List.of("--discovery-dir", tmp.resolve("unused-discovery").toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(tmp.resolve("child.out").toFile())
                .redirectError(tmp.resolve("child.err").toFile());
        builder.environment().remove("SKILLROY_CREDENTIALS");
        builder.environment().putAll(env);
        Process process = builder.start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the CLI ends");
        return new Child(process.exitValue(), Files.readString(tmp.resolve("child.out")),
                Files.readString(tmp.resolve("child.err")));
    }

    private long openTelemetryClassesLoaded() throws IOException {
        try (Stream<String> lines = Files.lines(tmp.resolve("class-load.log"))) {
            return lines.filter(l -> l.contains(" io.opentelemetry.")).count();
        }
    }

    @Test
    void offByDefaultTheCliLoadsNoOpenTelemetryClass() throws Exception {
        Child child = child(Map.of(), file("pass.yaml"), "--out", tmp.resolve("out").toString());
        assertEquals(Exit.OK, child.exit(), child.err());
        assertTrue(Files.size(tmp.resolve("class-load.log")) > 0, "the probe logged the classes");
        assertEquals(0, openTelemetryClassesLoaded(), "no io.opentelemetry class without --telemetry");
    }

    @Test
    void aHeadersValueReachesTheEndpointAndNoOutputOrFile() throws Exception {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/v1/traces", exchange -> {
            exchange.getRequestBody().readAllBytes();
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        stub.start();
        Path yaml = tmp.resolve("telemetry.yaml");
        Files.writeString(yaml, "version: 1\nexporter: otlp\nendpoint: http://127.0.0.1:" + stub.getAddress().getPort()
                + "\nheaders: {Authorization: env:SKILLROY_TEST_OTLP_TOKEN}\n");
        Path out = tmp.resolve("out");
        Path junit = tmp.resolve("junit.xml");
        Child child = child(Map.of("SKILLROY_TEST_OTLP_TOKEN", TOKEN), file("pass.yaml"), file("wrong.yaml"),
                "--telemetry-file", yaml.toString(), "--out", out.toString(), "--junit", junit.toString());
        assertEquals(Exit.FAILED, child.exit(), child.err());
        assertFalse(authorizations.isEmpty(), "the batches reached the endpoint");
        assertTrue(authorizations.stream().allMatch(TOKEN::equals), String.valueOf(authorizations.size()));
        assertTrue(openTelemetryClassesLoaded() > 0, "the exporter ran in the child");
        assertTrue(child.out().contains("telemetry: otlp 127.0.0.1:" + stub.getAddress().getPort() + ", trace "),
                child.out());

        assertFalse(child.out().contains(TOKEN) || child.err().contains(TOKEN), "not printed");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(tmp)) {
            files = walk.filter(Files::isRegularFile).filter(f -> !f.equals(yaml)).toList();
        }
        assertTrue(files.contains(junit) && files.stream().anyMatch(f -> f.endsWith("run.jsonl")));
        for (Path f : files) {
            assertFalse(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).contains(TOKEN),
                    "no file holds the header's value: " + f);
        }
    }
}
