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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.junit.OutputScan;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.awt.GraphicsEnvironment;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Telemetry end to end (phase6-contracts.md 13.2 item 9): the assembled launcher runs scenario A in {@code replay}
 * against the desk with the agent on the command line (seed B), once with {@code --telemetry file --out <dir>}, which
 * writes {@code <dir>/telemetry/spans.jsonl} with the {@code skillroy run}, {@code scenario}, {@code bind},
 * {@code step}, {@code call}, {@code agent} and {@code await} spans, names the trace in {@code run-start} and
 * {@code --json}, and holds no password; and once with {@code --telemetry otlp} against a loopback OTLP/HTTP stub this
 * test starts, which receives the batches and ends the summary with {@code telemetry: otlp <host:port>, trace <id>, 0
 * batches dropped}. Needs a display (skipped when headless); outputs go to {@code target/it-runs/telemetry/}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryEndToEndIT {

    static final Env ENV = Env.SEED_B;

    private LendingTarget target;
    private Path out;
    private Path secrets;
    private Path credentials;

    private record Result(int exit, String out, String err) {
        String shown() {
            return "exit " + exit + "\n--- stdout\n" + out + "\n--- stderr\n" + err;
        }
    }

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        out = ItEnv.runDir("telemetry", "x").getParent();
        ResolutionChecks.deleteTree(out);
        Files.createDirectories(out);
        target = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT, ENV.seed, false);
        secrets = Files.createTempDirectory("skillroy-it-telemetry");
        credentials = SampleCredentials.write(secrets, ENV, null);
    }

    @AfterAll
    void close() throws Exception {
        try {
            if (target != null) {
                target.close();
            }
        } finally {
            if (secrets != null) {
                ResolutionChecks.deleteTree(secrets);
            }
        }
    }

    private List<String> replay(Path runOut) {
        return new ArrayList<>(List.of("run", ScenarioA.yaml().toString(), "--mode", "replay", "--env", ENV.id,
                "--bindings", ScenarioA.bindingsRoot().toString(), "--credentials", credentials.toString(),
                "--pid", String.valueOf(target.pid()), "--discovery-dir", target.discoveryDir.toString(), "--pack",
                PerTargetJavaChecks.packJar().toString(), "--out", runOut.toString()));
    }

    private Result run(List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(PerTargetJavaChecks.launcher().toString());
        command.addAll(args);
        Path logs = Files.createDirectories(out.resolve("cli-logs"));
        Path stdout = Files.createTempFile(logs, "cli", ".out");
        Path stderr = Files.createTempFile(logs, "cli", ".err");
        ProcessBuilder builder = new ProcessBuilder(command).directory(out.toFile()).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().remove("SKILLROY_CREDENTIALS");
        Process process = builder.start();
        process.getOutputStream().close();
        boolean exited = process.waitFor(300, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue(exited, "bin/skillroy did not finish in time");
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private Set<String> passwords() {
        return new HashSet<>(SampleCredentials.staff(ENV).values());
    }

    @Test
    void theFileExporterWritesTheRunsSpans() throws Exception {
        Path runOut = out.resolve("file");
        List<String> args = replay(runOut);
        args.addAll(List.of("--telemetry", "file", "--json"));
        Result result = run(args);
        assertEquals(0, result.exit(), result.shown());
        @SuppressWarnings("unchecked")
        Map<String, Object> telemetry = (Map<String, Object>) Json.parseObject(result.out()).get("telemetry");
        assertEquals("file", telemetry.get("exporter"), result.shown());
        String traceId = String.valueOf(telemetry.get("traceId"));
        assertTrue(traceId.matches("[0-9a-f]{32}"), traceId);
        assertTrue(result.err().contains("telemetry: file " + runOut.resolve("telemetry") + ", trace " + traceId
                + ", 0 batches dropped"), result.shown());

        Map<String, Object> start = Runs.runLog(runOut.resolve(ScenarioA.ID)).get(0);
        assertEquals("run-start", start.get("type"));
        assertEquals(traceId, start.get("traceId"), "run-start names the trace");

        Path spans = runOut.resolve("telemetry").resolve("spans.jsonl");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : Files.readAllLines(spans, StandardCharsets.UTF_8)) {
            lines.add(Json.parseObject(line));
        }
        Set<String> names = new HashSet<>();
        lines.forEach(l -> names.add(String.valueOf(l.get("name"))));
        for (String expected : List.of("skillroy run", "scenario " + ScenarioA.ID, "bind", "step s1", "await")) {
            assertTrue(names.contains(expected), expected + " in " + names);
        }
        assertTrue(names.stream().anyMatch(n -> n.startsWith("call lending.")), "a pack call: " + names);
        assertTrue(names.stream().anyMatch(n -> n.startsWith("agent lending.")), "its agent span: " + names);
        assertTrue(lines.stream().allMatch(l -> traceId.equals(l.get("traceId"))), "one trace");
        Map<?, ?> rootAttributes = (Map<?, ?>) lines.stream().filter(l -> "skillroy run".equals(l.get("name")))
                .findFirst().orElseThrow().get("attributes");
        assertEquals(target.pid(), ((Number) rootAttributes.get("skillroy.target.pid")).longValue());
        assertEquals("JAVAAGENT", rootAttributes.get("skillroy.target.load_mode"));
        assertEquals(List.of(), OutputScan.leaks(spans, passwords()), "no span holds a password");
    }

    @Test
    void theOtlpExporterDeliversItsBatchesToALoopbackStub() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/v1/traces", exchange -> {
            int bytes = exchange.getRequestBody().readAllBytes().length;
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Content-Type")
                    + " " + bytes);
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        stub.start();
        try {
            int port = stub.getAddress().getPort();
            List<String> args = replay(out.resolve("otlp"));
            args.addAll(List.of("--telemetry", "otlp", "--telemetry-endpoint", "http://127.0.0.1:" + port));
            Result result = run(args);
            assertEquals(0, result.exit(), result.shown());
            assertFalse(received.isEmpty(), "the stub received the batches");
            assertTrue(received.stream().allMatch(r -> r.startsWith("POST application/x-protobuf ")),
                    String.valueOf(received));
            assertTrue(result.out().lines().anyMatch(l -> l.matches("telemetry: otlp 127\\.0\\.0\\.1:" + port
                    + ", trace [0-9a-f]{32}, 0 batches dropped")), result.shown());
        } finally {
            stub.stop(0);
        }
    }
}
