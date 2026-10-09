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

package ai.skillroy.testjava.telemetry;

import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.TelemetryFixture;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OTLP/HTTP transport (phase6-contracts.md 10.4, 10.6, decision D141) against a loopback stub
 * ({@code com.sun.net.httpserver}, {@code POST /v1/traces}): a flush delivers the batch as protobuf with the header
 * whose value came from the environment variable the settings name, and nothing else of the environment is read; an
 * endpoint that refuses connections neither fails nor slows the run beyond the timeout and is reported once, with
 * the dropped count for the summary. The content is asserted through the in-memory exporter elsewhere, not by
 * decoding protobuf (no protobuf library enters the build).
 */
class OtlpTransportTest {

    /** The header's value: what must reach the stub and nothing else. */
    private static final String TOKEN = "otlp-stub-token-7a6b5c4d";

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int stub() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(Map.of("method", exchange.getRequestMethod(), "path", exchange.getRequestURI().getPath(),
                    "contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "bytes", String.valueOf(body.length)));
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private TelemetrySettings settings(int port) throws Exception {
        Path file = dir.resolve("telemetry.yaml");
        java.nio.file.Files.writeString(file, "version: 1\nexporter: otlp\nendpoint: http://127.0.0.1:" + port
                + "\nheaders: {Authorization: env:SKILLROY_TEST_OTLP_TOKEN}\ntimeoutMs: 2000\n");
        return TelemetrySettings.load(dir, Map.of(), "test", key -> null,
                name -> name.equals("SKILLROY_TEST_OTLP_TOKEN") ? TOKEN : null);
    }

    @Test
    void aFlushDeliversTheBatchWithTheHeaderFromTheEnvironment() throws Exception {
        int port = stub();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        RunObserver observer = TelemetryObservers.otlp(settings(port),
                name -> name.equals("SKILLROY_TEST_OTLP_TOKEN") ? TOKEN : null, new PrintStream(err, true,
                        StandardCharsets.UTF_8));
        observer.runStarted(new RunStart("run", null, "replay", "seed-a", "test", null, null));
        RunResult result = new TelemetryFixture(dir).run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, observer);
        assertEquals(Status.FAIL, result.status());
        observer.flush();
        assertEquals(1, requests.size(), "one request for the flush: " + requests);
        Map<String, String> request = requests.get(0);
        assertEquals("POST", request.get("method"));
        assertEquals("/v1/traces", request.get("path"));
        assertEquals("application/x-protobuf", request.get("contentType"));
        assertEquals(TOKEN, request.get("authorization"), "the header's value from the named variable");
        assertTrue(Integer.parseInt(request.get("bytes")) > 0);
        String traceId = observer.traceId();
        observer.close();
        assertEquals(2, requests.size(), "the root span at close: " + requests);
        assertEquals("telemetry: otlp 127.0.0.1:" + port + ", trace " + traceId + ", 0 batches dropped",
                ExportStatus.summaryLine(observer, traceId));
        assertEquals("", err.toString(StandardCharsets.UTF_8), "nothing on stderr when the export works");
    }

    @Test
    void aRefusedEndpointIsReportedOnceAndCountedWithoutSlowingTheRun() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        TelemetrySettings settings = TelemetrySettings.load(null, Map.of(TelemetrySettings.PROP_EXPORTER, "otlp",
                TelemetrySettings.PROP_ENDPOINT, "http://127.0.0.1:" + port, TelemetrySettings.PROP_TIMEOUT_MS,
                "1500"), "test", key -> null, name -> null);
        RunObserver observer = TelemetryObservers.otlp(settings, name -> null, new PrintStream(err, true,
                StandardCharsets.UTF_8));
        observer.runStarted(new RunStart("run", null, "replay", "seed-a", "test", null, null));
        long t0 = System.nanoTime();
        RunResult result = new TelemetryFixture(dir).run(TelemetryFixture.FAILING_YAML, BindMode.REPLAY, observer);
        assertEquals(Status.FAIL, result.status(), "the verdict is the run's, whatever the export does");
        observer.flush();
        observer.flush();
        observer.close();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 3 * 1500 + 2000, "bounded by the timeout: " + ms + " ms");
        String printed = err.toString(StandardCharsets.UTF_8);
        assertEquals(1, printed.lines().count(), "reported once: " + printed);
        assertTrue(printed.contains("127.0.0.1:" + port) && printed.contains("dropped"), printed);
        long dropped = ((ExportStatus) observer).droppedBatches();
        assertTrue(dropped >= 1, "counted: " + dropped);
        assertTrue(ExportStatus.summaryLine(observer, observer.traceId()).endsWith(dropped + " batch"
                + (dropped == 1 ? "" : "es") + " dropped"));
    }

    @Test
    void aMissingHeaderVariableIsRefusedNamingTheVariableOnly() throws Exception {
        TelemetrySettings settings = new TelemetrySettings("otlp", "http://127.0.0.1:4318",
                Map.of("Authorization", "SKILLROY_TEST_UNSET"), null, "test", null, null, 1000L, null);
        SettingsException e = assertThrows(SettingsException.class, () -> TelemetryObservers.otlp(settings,
                name -> null, System.err));
        assertTrue(e.getMessage().contains("SKILLROY_TEST_UNSET"), e.getMessage());
        assertFalse(e.getMessage().contains("Authorization"), e.getMessage());
        TelemetrySettings remote = new TelemetrySettings("otlp", "http://10.1.2.3:4318", null, null, "test", null,
                null, 1000L, null);
        assertThrows(SettingsException.class, () -> TelemetryObservers.otlp(remote, name -> null, System.err),
                "the provider checks the loopback rule again");
    }
}
