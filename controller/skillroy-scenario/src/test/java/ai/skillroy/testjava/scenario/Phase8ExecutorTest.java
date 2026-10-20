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
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 40, the run's part: {@code captureFacts} (8.7) where the step stands (the value and
 * the size, the {@code capture {source: facts}} record, {@code bindings.json}'s {@code captured}, each failure path,
 * the masked rule), deltas against a captured count and {@code {size: 0}} on the filtered {@code events} root (8.8),
 * the {@code actual} cut of {@code run.jsonl} (8.10), against the executor tests' fake agent and the toy pack.
 */
class Phase8ExecutorTest {

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private RunOptions options;
    private final Map<String, Object> state = new LinkedHashMap<>(Map.of("name", "IDLE"));
    private final List<Object> lines = new ArrayList<>();

    @BeforeEach
    void toyApplication() {
        options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withBind(TestCredentials.bind())
                .withStepTimeout(Duration.ofMillis(300)).withOutputDir(dir.resolve("run"));
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            if ("Barcode".equals(params.get("source"))) {
                lines.add(Map.of("code", String.valueOf(params.get("value")), "qty", 1L));
            }
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("accepted", true, "acked", true);
        });
        agent.on("toy.state", params -> new LinkedHashMap<>(state));
        agent.on("toy.loan", params -> Map.of("total", "7.00", "lines", new ArrayList<>(lines)));
        agent.value("toy.poke", Map.of());
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("swing.snapshot", Map.of("windows", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "IDLE")));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunResult run(String body) throws ScenarioException {
        return new PlanExecutor(pack, agent, options).run(plan(body));
    }

    private List<Map<String, Object>> log() throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run").resolve(RunLog.FILE), StandardCharsets.UTF_8)) {
            records.add(Json.parseObject(line));
        }
        return records;
    }

    private List<Map<String, Object>> records(String type) throws Exception {
        return log().stream().filter(r -> type.equals(r.get("type"))).toList();
    }

    private static void assertOutcome(RunResult result, Status status, FailureKind kind) {
        assertEquals(status, result.status(), result.message());
        assertEquals(kind, result.failureKind(), result.message());
    }

    private static final String SCAN_COUNT = "events[*topic=input.acked][*payload.source=Barcode]";

    // ------------------------------------------------------------------------------------------ captureFacts

    @Test
    void aCaptureBindsTheValuesAndTheSizesWhereTheStepStands() throws Exception {
        RunResult result = run("""
                steps:
                  - enter: { value: A1 }
                  - poke: {}
                  - captureFacts:
                      slot: before
                      fields:
                        acks:  { size: "events[*topic=input.acked]" }
                        name:  state.name
                        total: loan.total
                        chars: { size: state.name }
                  - expect:
                      "events[*topic=input.acked]": { size: { gt: $before.acks } }
                      "state.name": $before.name
                """);
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(result.message().startsWith("expectation failed: events[*topic=input.acked]"
                + " {\"size\":{\"gt\":1}}: size 1: expected a value > 1 but was 1"), "no new ack: the delta fails"
                + " naming the captured operand: " + result.message());
        List<Map<String, Object>> captures = records("capture");
        assertEquals(1, captures.size(), String.valueOf(captures));
        Map<String, Object> capture = captures.get(0);
        assertEquals("s3", capture.get("step"));
        assertEquals("before", capture.get("slot"));
        assertEquals("facts", capture.get("source"));
        assertEquals(Map.of("acks", 1L, "chars", 4L, "name", "IDLE", "total", "7.00"), capture.get("value"));
        Map<String, Object> bindings = Json.parseObject(Files.readString(dir.resolve("run").resolve("bindings.json")));
        assertEquals(Map.of("before", Map.of("acks", 1L, "chars", 4L, "name", "IDLE", "total", "7.00")),
                bindings.get("captured"), "bindings.json's captured holds the slot");
    }

    @Test
    void aDeltaHoldsAfterANewEventAndNoneSinceIsACountEqualToTheCapture() throws Exception {
        RunResult result = run("""
                steps:
                  - enter: { value: A1 }
                  - captureFacts:
                      slot: before
                      fields:
                        scans:    { size: "%s" }
                        entries:  { size: "events[*topic=input.acked][*payload.source=Entry]" }
                  - enter: { value: B2 }
                  - expect:
                      "events[*topic=input.acked][*payload.source=Entry]": { size: { gt: $before.entries } }
                      "%s": { size: $before.scans }
                verify:
                  - { "events[*topic=input.acked][*payload.source=Barcode]": { size: 0 } }
                  - { "events[*topic=prompt.waiting]": { size: 0 } }
                """.formatted(SCAN_COUNT, SCAN_COUNT));
        assertOutcome(result, Status.PASS, null);
        assertEquals(Map.of("entries", 1L, "scans", 0L), records("capture").get(0).get("value"));
    }

    @Test
    void aStrayEventFailsANoneCountWithTheMatchingEntriesAsActual() throws Exception {
        agent.on("toy.poke", params -> {
            agent.publish("toy.stray", Map.of("id", "stray", "text", "Unexpected"));
            return Map.of();
        });
        RunResult result = run("""
                steps:
                  - call: { primitive: toy.poke }
                verify:
                  - { "events[*topic=toy.stray]": { size: 0 } }
                """);
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(result.message().contains("expected size 0 but was 1"), result.message());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actual = (List<Map<String, Object>>) result.detail().get("actual");
        assertEquals(1, actual.size());
        assertEquals(Map.of("id", "stray", "text", "Unexpected"), actual.get(0).get("payload"));
        assertNull(result.detail().get("actualTruncated"));
    }

    @Test
    void aSizeOfTwoOnAListOfThreeNamesTheSize() throws Exception {
        RunResult result = run("""
                steps:
                  - enter: { value: A1 }
                  - enter: { value: A2 }
                  - enter: { value: A3 }
                  - expect: { "events[*topic=input.acked]": { size: 2 } }
                """);
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(result.message().endsWith(": expected size 2 but was 3"), result.message());
    }

    @Test
    void anUnresolvedFieldFailsTheStepNamingSlotFieldAndPath() throws Exception {
        RunResult result = run("""
                steps:
                  - captureFacts: { slot: before, fields: { qty: "loan.lines[code=NONE].qty" } }
                """);
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertEquals("s1", result.failedStepId());
        assertTrue(result.message().startsWith("captureFacts before.qty: loan.lines[code=NONE].qty did not resolve: no"
                + " element of loan.lines match [code=NONE]"), result.message());
        assertEquals("before", result.detail().get("slot"));
        assertEquals("qty", result.detail().get("field"));
        assertEquals("loan.lines[code=NONE].qty", result.detail().get("path"));
        assertTrue(String.valueOf(result.detail().get("reason")).startsWith("no element"));
        assertEquals(1, agent.callsTo("toy.loan").size(), "no retry: one read");
        RunResult size = run("steps:\n  - captureFacts: { slot: b, fields: { n: { size: state.missing } } }\n");
        assertOutcome(size, Status.FAIL, FailureKind.ASSERTION);
        state.put("count", 3L);
        RunResult number = run("steps:\n  - captureFacts: { slot: b, fields: { n: { size: state.count } } }\n");
        assertOutcome(number, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(number.message().contains("'size' needs a list, a map or a string, but the value is 3"),
                number.message());
    }

    @Test
    void aFactReadFailureIsAnEnvironmentErrorAndAnUnresolvedReferenceAResolutionFailure() throws Exception {
        agent.error("toy.state", ErrorCodes.INTERNAL_ERROR, "engine down");
        RunResult down = run("steps:\n  - captureFacts: { slot: b, fields: { n: state.name } }\n");
        assertOutcome(down, Status.ERROR, FailureKind.ENVIRONMENT);
        assertTrue(down.message().startsWith("fact 'state' could not be read: "), down.message());
        RunResult ref = run("""
                resolve:
                  item: { fixture: { code: A1 } }
                steps:
                  - captureFacts: { slot: b, fields: { n: "loan.lines[code=$item.nope]" } }
                """);
        assertOutcome(ref, Status.ERROR, FailureKind.RESOLUTION_FAILED);
        assertTrue(ref.message().contains("$item.nope"), ref.message());
    }

    @Test
    void aMaskedCapturedValueFailsOnlyUnderTheMaskedOption() throws Exception {
        state.put("pin", TestCredentials.PW_100);
        String body = "steps:\n  - captureFacts: { slot: b, fields: { pin: state.pin } }\n";
        assertOutcome(run(body), Status.PASS, null);
        options = options.withExpectationsOnMaskedValues(true);
        RunResult masked = run(body);
        assertOutcome(masked, Status.FAIL, FailureKind.ASSERTION);
        assertEquals("captureFacts b.pin: " + Matchers.HOLDS_A_SECRET, masked.message());
        assertEquals(SecretMask.REDACTED, masked.detail().get("actual"));
        assertFalse(Files.readString(dir.resolve("run").resolve(RunLog.FILE)).contains(TestCredentials.PW_100));
        assertOutcome(run("steps:\n  - captureFacts: { slot: b, fields: { name: state.name } }\n"), Status.PASS,
                null);
    }

    // ---------------------------------------------------------------------------------------- the actual cut

    @Test
    void anActualOfMoreThan200ElementsIsCutWithActualTruncated() throws Exception {
        agent.on("toy.poke", params -> {
            for (int i = 0; i < 250; i++) {
                agent.publish("toy.tick", Map.of("n", (long) i));
            }
            return Map.of();
        });
        RunResult result = run("steps:\n  - call: { primitive: toy.poke }\n  - expect: { \"events[*topic=toy.tick]\":"
                + " { size: 3 } }\n");
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(result.message().endsWith("expected size 3 but was 250"), "the verdict is the whole list's");
        assertEquals(200, ((List<?>) result.detail().get("actual")).size(), "the detail's actual cut");
        assertEquals(true, result.detail().get("actualTruncated"));
        Map<String, Object> expect = records("expect").get(0);
        assertEquals(200, ((List<?>) expect.get("actual")).size());
        assertEquals(true, expect.get("actualTruncated"));
        assertEquals(List.of("type", "t", "step", "path", "matcher", "capability", "result", "resolved", "actual",
                "actualTruncated", "reason", "attempts"), List.copyOf(expect.keySet()), "after actual");
        RunResult held = run("steps:\n  - call: { primitive: toy.poke }\n  - expect: { \"events[*topic=toy.tick]\":"
                + " { size: 250 } }\n");
        assertOutcome(held, Status.PASS, null);
        Map<String, Object> small = new LinkedHashMap<>(Map.of("actual", List.of(1L, List.of(2L))));
        assertEquals(small, RunLog.cutActual(small), "nothing to cut: the same map");
        List<Object> nested = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            nested.add((long) i);
        }
        Map<String, Object> deep = RunLog.cutActual(Map.of("actual", Map.of("a", List.of(Map.of("b", nested)))));
        assertEquals(true, deep.get("actualTruncated"), "at any depth");
    }
}
