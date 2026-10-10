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

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix, pass 2, P2-L6-F2 and P2-L6-F3: {@code run.jsonl} (every record type, the observer's copy
 * included), the session journal and the {@code failure/} JSON files carry a capture's shape and size, never its bytes;
 * the pixels are in the PNG files only. Before the fix each of these held the capture's base64 as written.
 */
class CaptureElisionTest {

    static final String PNG_1X1 = FakeAgentConnection.PNG_1X1;
    static final String SIGNATURE = Images.PNG_SIGNATURE;

    @TempDir
    Path dir;

    private static Map<String, Object> capture() {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("format", "png");
        capture.put("width", 1L);
        capture.put("height", 1L);
        capture.put("base64", PNG_1X1);
        return capture;
    }

    /** An observer that keeps the scenario records it is given (a proxy: every other method does nothing). */
    private static RunObserver keeping(List<String> records) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("scenarioRecord")) {
                records.add(args[0] + " " + Json.write(args[1]));
            }
            if (method.getName().equals("traceId")) {
                return null;
            }
            return method.getReturnType() == boolean.class ? false : null;
        };
        return (RunObserver) Proxy.newProxyInstance(RunObserver.class.getClassLoader(),
                new Class<?>[] {RunObserver.class}, handler);
    }

    @Test
    void everyRunLogRecordAndTheObserversCopyCarryNoCapturesBytes() throws Exception {
        List<String> observed = new ArrayList<>();
        try (RunLog log = RunLog.open(dir, new TestClock())) {
            log.observe(keeping(observed));
            log.mask(SecretMask.NONE);
            log.write("call", RunLog.fields("step", "s1", "index", 1, "primitive", "swing.capture", "params",
                    Map.of(), "ok", true, "value", capture()));
            log.write("sync", RunLog.fields("step", "s1", "mode", "SETTLE", "value", Map.of("evidence", capture())));
            log.write("cleanup", RunLog.fields("primitive", "desk.capture", "ok", true, "value",
                    List.of(capture())));
            log.write("run-end", RunLog.fields("status", "ERROR", "detail", Map.of("last", capture(),
                    "text", "data:image/png;base64," + PNG_1X1)));
        }
        String text = Files.readString(dir.resolve(RunLog.FILE), StandardCharsets.UTF_8);
        assertFalse(text.contains(SIGNATURE), text);
        assertEquals(4, text.lines().count());
        for (String line : text.lines().toList()) {
            assertTrue(line.contains("\"base64\":\"<png: 70 bytes>\""), line);
            assertTrue(line.contains("\"width\":1"), "the shape stays: " + line);
        }
        assertTrue(text.contains("data:image/png;base64,<png: 70 bytes>"), text);
        assertEquals(1, observed.size(), "cleanup is the observed type of the four: " + observed);
        assertFalse(observed.get(0).contains(SIGNATURE), observed.get(0));
        assertTrue(observed.get(0).contains("<png: 70 bytes>"), observed.get(0));
    }

    @Test
    void theSessionJournalCarriesNoCapturesBytes() throws Exception {
        StepJournal journal = StepJournal.open(dir, null);
        StepJournal.Entry kept = journal.append(new StepJournal.ObserveEntry("app.desk.view", capture()),
                SecretMask.NONE);
        journal.append(new StepJournal.CheckEntry("app.desk.view", "anything", Map.of("deep", List.of(capture())),
                false, "differs"), SecretMask.NONE);
        journal.close();
        String text = Files.readString(journal.file(), StandardCharsets.UTF_8);
        assertFalse(text.contains(SIGNATURE), text);
        assertEquals(2, text.split("<png: 70 bytes>", -1).length - 1, text);
        assertEquals(Images.elided(capture()), ((StepJournal.ObserveEntry) kept).value(), "kept as written");
    }

    @Test
    void aStepsCaptureCallIsLoggedByItsShapeAndTheFailureFilesHoldNoBytes() throws Exception {
        ToyPack pack = new ToyPack();
        FakeAgentConnection agent = new FakeAgentConnection();
        TestClock clock = new TestClock();
        agent.capabilities("swing.capture.paint", "toy.state");
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("swing.capture", capture());
        agent.value("app.state", Map.of("toy", Map.of("name", "IDLE"), "view", capture()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("swing.snapshot", Map.of("seq", 1L, "truncated", false, "windows", List.of()));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.publish("desk.viewed", Map.of("view", capture()));
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind()).withOutputDir(dir).withStepTimeout(java.time.Duration.ofMillis(100));
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n  - call: { primitive: swing.capture }\n"
                + "  - expect: { \"state.name\": BUSY }\n").getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.FAIL, result.status(), result.message());
        String log = Files.readString(dir.resolve(RunLog.FILE), StandardCharsets.UTF_8);
        assertFalse(log.contains(SIGNATURE), log);
        String call = log.lines().filter(l -> l.contains("\"type\":\"call\"") && l.contains("swing.capture"))
                .findFirst().orElseThrow(() -> new AssertionError(log));
        assertTrue(call.contains("\"value\":{\"format\":\"png\",\"width\":1,\"height\":1,\"base64\":"
                + "\"<png: 70 bytes>\"}"), call);
        Path failure = dir.resolve("failure");
        assertArrayEquals(Base64.getDecoder().decode(PNG_1X1), Files.readAllBytes(failure.resolve("capture.png")),
                "the pixels are in the PNG file");
        try (var files = Files.list(failure)) {
            for (Path f : files.filter(f -> !f.toString().endsWith(".png")).toList()) {
                assertFalse(Files.readString(f, StandardCharsets.UTF_8).contains(SIGNATURE), f.toString());
            }
        }
        assertTrue(Files.readString(failure.resolve("app-state.json")).contains("<png: 70 bytes>"));
    }
}
