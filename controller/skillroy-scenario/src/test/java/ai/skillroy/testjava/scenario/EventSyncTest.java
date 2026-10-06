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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A1: a call completed by the event it names ({@code Sync.EVENT} with an {@link AwaitSpec}), from the SPI
 * and the base verb {@code call:} through {@code plan.json} to the executor's waits and verdicts.
 */
class EventSyncTest {

    private static final Map<String, Object> EMIT_MATCH_42 = Map.of("batch", 1L, "id", "42", "kind", "toy",
            "zone", "A");

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper());

    @BeforeEach
    void toyApplication() {
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            if ("Q".equals(params.get("value"))) {
                agent.publish("prompt.waiting", Map.of("id", "q", "text", "Quantity?"));
            } else {
                agent.publish("input.requested", Map.of("state", "LOAN"));
            }
            return Map.of("accepted", true);
        });
        // toy.emit publishes some noise, a toy.done with another id, then the toy.done for its id.
        agent.on("toy.emit", params -> {
            Object id = params.get("id");
            if (!"SILENT".equals(id)) {
                agent.publish("toy.noise", Map.of("id", String.valueOf(id)));
                agent.publish("toy.done", Map.of("id", "other", "kind", "toy", "zone", "A", "batch", 1L));
                agent.publish("toy.done", Map.of("id", String.valueOf(id), "kind", "toy", "zone", "A", "batch", 1L));
            }
            return Map.of("emitted", true);
        });
        // The quiet answer publishes no ack, only the ready topic.
        agent.on("toy.prompt.quietAnswer", params -> {
            agent.publish("prompt.answered", Map.of("choice", params.get("choice")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("answered", true);
        });
        agent.value("toy.lock", Map.of());
        agent.on("toy.unlock", params -> {
            agent.publish("toy.unlocked", Map.of("which", params.get("which")));
            return Map.of();
        });
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "LOAN")));
        agent.value("swing.snapshot", Map.of("windows", List.of()));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private ScenarioException compileFails(String steps) {
        return assertThrows(ScenarioException.class, () -> plan("steps:\n" + steps));
    }

    private RunResult run(String body) throws ScenarioException {
        return new PlanExecutor(pack, agent, options).run(plan(body));
    }

    private static void assertOutcome(RunResult result, Status status, FailureKind kind) {
        assertEquals(status, result.status(), result.message());
        assertEquals(kind, result.failureKind(), result.message());
    }

    private static FakeAgentConnection.Await await(String topics, long since, Map<String, Object> match, long ms) {
        return new FakeAgentConnection.Await(topics, since, match, Duration.ofMillis(ms));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    // ------------------------------------------------------------------------------------------------ SPI

    @Test
    void awaitIsRequiredWithEventAndForbiddenOtherwise() {
        AwaitSpec spec = AwaitSpec.of("toy.done");
        assertThrows(IllegalArgumentException.class, () -> new PlannedCall("p", null, Sync.EVENT, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new PlannedCall("p", null, Sync.EVENT, 0));
        assertThrows(IllegalArgumentException.class, () -> PlannedCall.of("p", null, Sync.EVENT));
        assertThrows(IllegalArgumentException.class, () -> PlannedCall.event("p", null, null));
        for (Sync other : List.of(Sync.NONE, Sync.ACK, Sync.READY, Sync.SETTLE)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new PlannedCall("p", null, other, 0, spec));
            assertTrue(e.getMessage().contains("needs sync EVENT, found " + other), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new PlannedCall("p", null, null, 0, spec),
                "a null sync is NONE, which forbids an await");

        PlannedCall event = PlannedCall.event("p", Map.of("a", 1), spec);
        assertEquals(new PlannedCall("p", Map.of("a", 1), Sync.EVENT, 0, spec), event);
        PlannedCall settle = new PlannedCall("p", null, Sync.SETTLE, 5);
        assertNull(settle.await());
        assertEquals(new PlannedCall("p", Map.of(), Sync.SETTLE, 5, null), settle);
        assertNull(PlannedCall.of("p", null, null).await());

        assertThrows(IllegalArgumentException.class, () -> new Plan.Call("p", Map.of(), Sync.EVENT, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new Plan.Call("p", Map.of(), Sync.EVENT, 1, null, false));
        assertThrows(IllegalArgumentException.class, () -> new Plan.Call("p", Map.of(), Sync.READY, 1, spec, false));
        assertNull(new Plan.Call("p", Map.of(), Sync.READY, 1, false).await());
    }

    @Test
    void awaitSpecChecksItsTopicsAndTimeout() {
        for (String topics : new String[] {null, "", "  ", " , ", ","}) {
            assertThrows(IllegalArgumentException.class, () -> new AwaitSpec(topics, null, 0), "topics " + topics);
        }
        assertThrows(IllegalArgumentException.class, () -> new AwaitSpec("a", null, -1));
        assertThrows(IllegalArgumentException.class, () -> AwaitSpec.anyOf(null));
        assertThrows(IllegalArgumentException.class, () -> AwaitSpec.anyOf(List.of()));
        assertEquals(new AwaitSpec("a,b.*", null, 0), AwaitSpec.anyOf(List.of("a", "b.*")));
        assertEquals(new AwaitSpec("*", null, 0), AwaitSpec.of("*"));
        AwaitSpec spec = AwaitSpec.of("a").withMatch(Map.of("k", "v")).withTimeoutMs(250);
        assertEquals(new AwaitSpec("a", Map.of("k", "v"), 250), spec);
        assertThrows(UnsupportedOperationException.class, () -> spec.match().put("x", 1));
        Map<String, Object> source = new LinkedHashMap<>(Map.of("k", "v"));
        AwaitSpec copied = AwaitSpec.of("a").withMatch(source);
        source.put("later", 1);
        assertEquals(Map.of("k", "v"), copied.match(), "the match is copied");
    }

    // ------------------------------------------------------------------------------------------------ compiling

    @Test
    void baseCallCompilesAnEventWait() throws Exception {
        Plan plan = plan("""
                resolve:
                  item: { fixture: { id: "42", kind: done } }
                steps:
                  - call:
                      primitive: toy.emit
                      args: { id: $item.id }
                      sync: event
                      await: { topic: toy.done, match: { zone: A, id: $item.id, n: 3, ok: true, none: ~ }, within: 3s }
                  - call: { primitive: toy.emit, sync: EVENT, await: { topics: [toy.done, "toy.$item.kind"] } }
                  - call: { primitive: toy.emit, sync: EVENT, await: { topics: "toy.done, toy.failed" } }
                  - call: { primitive: toy.poke, sync: READY }
                timeouts: { step: 4s }
                """);
        Plan.Call first = plan.steps().get(0).calls().get(0);
        assertEquals(Sync.EVENT, first.sync());
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("id", "$item.id");
        match.put("n", 3L);
        match.put("none", null);
        match.put("ok", true);
        match.put("zone", "A");
        assertEquals(new AwaitSpec("toy.done", match, 3000), first.await());
        assertEquals(List.of("id", "n", "none", "ok", "zone"), new ArrayList<>(first.await().match().keySet()),
                "match keys are sorted");
        assertEquals(4000, first.timeoutMs(), "the call's own timeout is still the step timeout");
        assertEquals(Map.of("id", "$item.id"), first.args());
        assertFalse(first.handlesPrompt());
        assertEquals(new AwaitSpec("toy.done,toy.$item.kind", null, 4000), plan.steps().get(1).calls().get(0).await(),
                "a list of topics is joined; no within means the step timeout");
        assertEquals("toy.done, toy.failed", plan.steps().get(2).calls().get(0).await().topics());
        assertNull(plan.steps().get(3).calls().get(0).await());
    }

    @Test
    void theDefaultEventWaitIsCappedAtTheAgentLimit() throws Exception {
        Plan plan = plan("steps:\n  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done } }\n"
                + "  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done, within: 10m } }\n"
                + "timeouts: { step: 11m }\n");
        assertEquals(660_000L, plan.steps().get(0).calls().get(0).timeoutMs());
        assertEquals(600_000L, plan.steps().get(0).calls().get(0).await().timeoutMs());
        assertEquals(600_000L, plan.steps().get(1).calls().get(0).await().timeoutMs(), "600 s is allowed");
    }

    @Test
    void aPackVerbWaitsForItsReadyTopicsWithEvent() throws Exception {
        Plan plan = plan("steps:\n  - answerQuietly: { choice: OK }\n  - emit: { id: \"7\", within: 2s }\n");
        Plan.Call answer = plan.steps().get(0).calls().get(0);
        assertEquals(Sync.EVENT, answer.sync());
        assertEquals(new AwaitSpec("input.requested,prompt.waiting", null, 10_000), answer.await());
        assertTrue(answer.handlesPrompt(), "the pack marked it");
        Plan.Call emit = plan.steps().get(1).calls().get(0);
        assertEquals(new AwaitSpec("toy.done", Map.of("batch", 1L, "id", "7", "kind", "toy", "zone", "A"), 2000),
                emit.await());
        assertEquals(List.of("batch", "id", "kind", "zone"), new ArrayList<>(emit.await().match().keySet()));
    }

    @Test
    void eventPlansAreDeterministicAndRoundTrip(@TempDir Path dir) throws Exception {
        String yaml = "scenario: t\npack: toy\nsteps:\n  - emit: { id: \"7\", within: 2s }\n"
                + "  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done } }\n"
                + "  - enter: { value: 1 }\n";
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
        Plan plan = PlanCompiler.compile(bytes, "t.yaml", pack);
        String text = plan.toJsonText();
        for (int i = 0; i < 5; i++) {
            assertEquals(text, PlanCompiler.compile(bytes.clone(), "other.yaml", new ToyPack()).toJsonText());
        }

        Map<String, Object> json = Json.parseObject(text);
        List<?> steps = (List<?>) json.get("steps");
        Map<String, Object> emit = map(((List<?>) map(steps.get(0)).get("calls")).get(0));
        assertEquals(List.of("primitive", "args", "sync", "timeoutMs", "await"), new ArrayList<>(emit.keySet()));
        assertEquals("EVENT", emit.get("sync"));
        Map<String, Object> emitAwait = map(emit.get("await"));
        assertEquals(List.of("topics", "match", "timeoutMs"), new ArrayList<>(emitAwait.keySet()));
        assertEquals("toy.done", emitAwait.get("topics"));
        assertEquals(List.of("batch", "id", "kind", "zone"), new ArrayList<>(map(emitAwait.get("match")).keySet()));
        assertEquals(2000L, emitAwait.get("timeoutMs"));
        Map<String, Object> anyPayload = map(map(((List<?>) map(steps.get(1)).get("calls")).get(0)).get("await"));
        assertTrue(anyPayload.containsKey("match") && anyPayload.get("match") == null, "match is written as null");
        assertEquals(10_000L, anyPayload.get("timeoutMs"));
        Map<String, Object> ready = map(((List<?>) map(steps.get(2)).get("calls")).get(0));
        assertFalse(ready.containsKey("await"), "only EVENT calls carry an await");

        Plan parsed = Plan.parse(text);
        assertEquals(plan, parsed);
        assertEquals(text, parsed.toJsonText());
        assertEquals(plan.hash(), parsed.hash());
        Path file = dir.resolve("t.plan.json");
        parsed.write(file);
        assertEquals(plan, Plan.read(file));
        assertEquals(text, Files.readString(file));
    }

    @Test
    void aPlanWithoutTheMatchKeyReadsAsAnyPayload() throws Exception {
        Map<String, Object> json = Json.parseObject(plan("steps:\n  - emit: { id: \"7\" }\n").toJsonText());
        Map<String, Object> call = map(((List<?>) map(((List<?>) json.get("steps")).get(0)).get("calls")).get(0));
        map(call.get("await")).remove("match");
        Plan parsed = Plan.fromJson(json);
        assertNull(parsed.steps().get(0).calls().get(0).await().match());
        assertTrue(parsed.toJsonText().contains("\"match\": null"), parsed.toJsonText());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '#', quoteCharacter = '"', value = {
        "call: { primitive: toy.emit, sync: EVENT }#sync EVENT needs 'await: {topic|topics, match?, within?}'",
        "call: { primitive: toy.emit, sync: EVENT, await: ~ }#sync EVENT needs 'await",
        "call: { primitive: toy.emit, sync: READY, await: { topic: a } }#'await' needs sync: EVENT, found sync READY",
        "call: { primitive: toy.emit, await: { topic: a } }#'await' needs sync: EVENT, found sync NONE",
        "call: { primitive: toy.emit, sync: EVENT, await: toy.done }#call.await: arguments must be a mapping",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, topics: [b] } }#exactly one of 'topic' or",
        "call: { primitive: toy.emit, sync: EVENT, await: { match: { a: 1 } } }#exactly one of 'topic' or",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: ' , ' } }#or 'topics' must be a topic",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: 7 } }#or 'topics' must be a topic",
        "call: { primitive: toy.emit, sync: EVENT, await: { topics: [] } }#or 'topics' must be a topic",
        "call: { primitive: toy.emit, sync: EVENT, await: { topics: [a, 1] } }#or 'topics' must be a topic",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, match: [1] } }#'match' must be a mapping",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, match: { a: { b: 1 } } } }#'match.a' must be"
                + " a string, number, boolean or null",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, match: { a: [1] } } }#'match.a' must be a",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, within: 11m } }#call.await.within must be at"
                + " most 600000 ms",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, within: soon } }#call.await.within: expected",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, within: 0 } }#call.await.within must be"
                + " positive",
        "call: { primitive: toy.emit, sync: EVENT, await: { topic: a, after: 1 } }#unknown argument 'after'",
        "call: { primitive: toy.emit, sync: SOON }#one of NONE, ACK, READY, SETTLE, EVENT",
        "badEvent: {}#sync EVENT needs an await spec"})
    void eventCallsAreCheckedAtCompileTime(String step, String message) {
        ScenarioException e = compileFails("  - mark: first\n  - " + step + "\n");
        assertTrue(e.getMessage().contains(message), e.getMessage());
        assertEquals("t", e.scenarioId());
        assertEquals(5, e.line());
    }

    @Test
    void packEventCallsAreCheckedAtCompileTime() throws Exception {
        Consumer<PlannedCall> fails = call -> {
            pack.custom = List.of(call);
            ScenarioException e = compileFails("  - custom: {}\n");
            assertEquals(4, e.line());
            assertTrue(e.getMessage().contains("call toy.emit await: "), e.getMessage());
        };
        fails.accept(PlannedCall.event("toy.emit", null, AwaitSpec.of("a").withTimeoutMs(600_001)));
        fails.accept(PlannedCall.event("toy.emit", null, AwaitSpec.of("a").withMatch(Map.of("x", List.of(1)))));
        fails.accept(PlannedCall.event("toy.emit", null, AwaitSpec.of("a").withMatch(Map.of("x", Map.of()))));
        fails.accept(PlannedCall.event("toy.emit", null, AwaitSpec.of("a").withMatch(Map.of(" ", 1))));
        pack.custom = List.of(PlannedCall.event("toy.emit", null, AwaitSpec.of("a").withMatch(Map.of("x",
                Double.NaN))));
        assertTrue(compileFails("  - custom: {}\n").getMessage().contains("JSON"));

        pack.custom = List.of(PlannedCall.event("toy.emit", null, AwaitSpec.of("a").withTimeoutMs(600_000)
                .withMatch(Map.of("x", 1, "y", "z"))));
        Plan ok = plan("steps:\n  - custom: {}\n");
        assertEquals(new AwaitSpec("a", Map.of("x", 1L, "y", "z"), 600_000), ok.steps().get(0).calls().get(0)
                .await());
    }

    @Test
    void handEditedEventPlansAreValidated() throws Exception {
        String good = plan("steps:\n  - emit: { id: \"7\", within: 2s }\n  - enter: { value: 1 }\n").toJsonText();
        List<Consumer<Map<String, Object>>> edits = List.of(
                call -> call.remove("await"),
                call -> call.put("await", null),
                call -> call.put("sync", "READY"),
                call -> call.put("await", 5L),
                call -> map(call.get("await")).put("extra", 1L),
                call -> map(call.get("await")).remove("topics"),
                call -> map(call.get("await")).put("topics", " , "),
                call -> map(call.get("await")).put("topics", 5L),
                call -> map(call.get("await")).put("timeoutMs", 0L),
                call -> map(call.get("await")).put("timeoutMs", -1L),
                call -> map(call.get("await")).put("timeoutMs", 600_001L),
                call -> map(call.get("await")).put("timeoutMs", "2s"),
                call -> map(call.get("await")).put("match", List.of(1L)),
                call -> map(map(call.get("await")).get("match")).put("zone", Map.of("x", 1L)),
                call -> map(map(call.get("await")).get("match")).put("zone", List.of()));
        for (int i = 0; i < edits.size(); i++) {
            Map<String, Object> json = Json.parseObject(good);
            edits.get(i).accept(firstCall(json, 0));
            ScenarioException e = assertThrows(ScenarioException.class, () -> Plan.fromJson(json), "edit " + i);
            assertTrue(e.getMessage().contains("invalid plan"), e.getMessage());
        }
        Map<String, Object> readyWithAwait = Json.parseObject(good);
        firstCall(readyWithAwait, 1).put("await", firstCall(readyWithAwait, 0).get("await"));
        ScenarioException e = assertThrows(ScenarioException.class, () -> Plan.fromJson(readyWithAwait));
        assertTrue(e.getMessage().contains("'await' needs sync EVENT, found READY"), e.getMessage());
    }

    private static Map<String, Object> firstCall(Map<String, Object> plan, int step) {
        return map(((List<?>) map(((List<?>) plan.get("steps")).get(step)).get("calls")).get(0));
    }

    // ------------------------------------------------------------------------------------------------ running

    @Test
    void eventWaitsStartAtTheCallsSeqBeforeWithReferencesResolved(@TempDir Path dir) throws Exception {
        options = options.withOutputDir(dir);
        RunResult result = run("""
                resolve:
                  item: { fixture: { id: "42", kind: done } }
                steps:
                  - enter: { value: 1 }
                  - emit: { id: $item.id }
                  - call:
                      primitive: toy.emit
                      args: { id: "43" }
                      sync: EVENT
                      await: { topic: "toy.$item.kind", match: { id: "43", zone: A }, within: 2s }
                  - waitFor: { topic: toy.noise }
                """);
        assertOutcome(result, Status.PASS, null);
        // Bus: 1 start mark; enter: 2 ack, 3 ready; emit 42 (seq.before 3): 4 noise, 5 done(other), 6 done(42);
        // emit 43 (seq.before 6): 7 noise, 8 done(other), 9 done(43). EVENT never waits for the ack topic.
        assertEquals(List.of(
                await("input.acked", 1, null, 10_000),
                await("input.requested,prompt.waiting", 1, null, 10_000),
                await("toy.done", 3, EMIT_MATCH_42, 10_000),
                await("toy.done", 6, Map.of("id", "43", "zone", "A"), 2000)), agent.awaits);
        assertEquals(Map.of("id", "42"), agent.callsTo("toy.emit").get(0).params());
        assertEquals(6L, agent.callsTo("sync.await").get(0).params().get("since"),
                "an EVENT call is an action: waitFor starts at its seq.before");

        List<Map<String, Object>> waits = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if ("sync".equals(record.get("type")) && "EVENT".equals(record.get("mode"))) {
                waits.add(record);
            }
        }
        assertEquals(2, waits.size());
        assertEquals("s2", waits.get(0).get("step"));
        assertEquals("event", waits.get(0).get("wait"));
        assertEquals("toy.done", waits.get(0).get("topics"));
        assertEquals(EMIT_MATCH_42, waits.get(0).get("match"));
        assertEquals(3L, waits.get(0).get("since"));
        assertEquals(true, waits.get(0).get("ok"));
        assertEquals(6L, waits.get(0).get("eventSeq"), "the first match, not the earlier toy.done of another id");
        assertEquals(6L, waits.get(1).get("since"));
        assertEquals(9L, waits.get(1).get("eventSeq"));
        assertEquals(2000L, waits.get(1).get("timeoutMs"));
    }

    @Test
    void noMatchingEventSinceSeqBeforeIsSyncTimeout() throws Exception {
        RunResult result = run("""
                steps:
                  - emit: { id: "42" }
                  - call:
                      primitive: toy.emit
                      args: { id: SILENT }
                      sync: EVENT
                      await: { topic: toy.done, match: { id: "42" }, within: 3s }
                  - mark: never
                """);
        assertOutcome(result, Status.FAIL, FailureKind.SYNC_TIMEOUT);
        assertEquals("s2", result.failedStepId());
        assertEquals("no event (toy.done) matching {\"id\":\"42\"} within 3000 ms after toy.emit", result.message());
        // The matching toy.done of step 1 is seq 4, the call's seq.before: it must not complete the wait.
        assertEquals(await("toy.done", 4, Map.of("id", "42"), 3000), agent.awaits.get(agent.awaits.size() - 1));
        Map<String, Object> detail = result.detail();
        assertEquals("toy.emit", detail.get("primitive"));
        assertEquals("EVENT", detail.get("sync"));
        assertEquals("event", detail.get("wait"));
        assertEquals("toy.done", detail.get("topics"));
        assertEquals(Map.of("id", "42"), detail.get("match"));
        assertEquals(4L, detail.get("since"));
        assertEquals(3000L, detail.get("timeoutMs"));
        assertEquals(Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()), detail.get("edtState"));
        assertEquals(Map.of("dialogs", List.of()), detail.get("dialogs"));
        assertEquals(Map.of("toy", Map.of("name", "LOAN")), detail.get("appState"));
        assertEquals(List.of(Status.PASS, Status.FAIL), result.steps().stream().map(StepResult::status).toList());
        assertEquals(1, agent.callsTo("trace.mark").size(), "the step after the timeout never ran");
        assertEquals(1, pack.recovered.get());
    }

    @Test
    void anEventWithAnotherPayloadDoesNotCompleteTheWait() throws Exception {
        RunResult result = run("steps:\n  - call: { primitive: toy.emit, args: { id: \"43\" }, sync: EVENT,"
                + " await: { topic: toy.done, match: { id: \"42\" }, within: 1s } }\n");
        assertOutcome(result, Status.FAIL, FailureKind.SYNC_TIMEOUT);
        assertEquals(await("toy.done", 1, Map.of("id", "42"), 1000), agent.awaits.get(0));

        RunResult noMatch = run("steps:\n  - call: { primitive: toy.emit, args: { id: SILENT }, sync: EVENT,"
                + " await: { topic: toy.done, within: 1s } }\n");
        assertOutcome(noMatch, Status.FAIL, FailureKind.SYNC_TIMEOUT);
        assertEquals("no event (toy.done) within 1000 ms after toy.emit", noMatch.message());
        assertFalse(noMatch.detail().containsKey("match"));
    }

    @Test
    void aZeroAwaitTimeoutIsTheRunsStepTimeout() throws Exception {
        Plan compiled = plan("steps: []\n");
        Plan.Call call = new Plan.Call("toy.emit", Map.of("id", "SILENT"), Sync.EVENT, 5000,
                new AwaitSpec("toy.done", null, 0), false);
        Plan handMade = new Plan(1, "t", "toy", pack.version(), compiled.sourceHash(), List.of(), List.of(),
                List.of(new Plan.Step("s1", "emit", 3, List.of(call), List.of())), List.of(), List.of(), Map.of(),
                List.of(), Map.of(), 10_000);
        options = options.withStepTimeout(Duration.ofMillis(2500));
        RunResult result = new PlanExecutor(pack, agent, options).run(handMade);
        assertOutcome(result, Status.FAIL, FailureKind.SYNC_TIMEOUT);
        assertEquals(await("toy.done", 1, null, 2500), agent.awaits.get(0));
        assertEquals(2500L, result.detail().get("timeoutMs"));
    }

    @Test
    void referencesInTheAwaitAreResolvedOrFailTheRunBeforeAnyStep() throws Exception {
        RunResult unknownField = run("resolve:\n  item: { fixture: { id: \"1\" } }\nsteps:\n  - mark: first\n"
                + "  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done, match: { id: $item.code"
                + " } } }\n");
        assertOutcome(unknownField, Status.ERROR, FailureKind.RESOLUTION_FAILED);
        assertEquals("$item.code", unknownField.detail().get("reference"));
        assertTrue(unknownField.message().contains("toy.emit"), unknownField.message());
        assertTrue(agent.callsTo("toy.emit").isEmpty());
        assertEquals(List.of(), unknownField.steps(), "no step ran");

        RunResult noTopic = run("resolve:\n  t: { fixture: \" \" }\nsteps:\n"
                + "  - call: { primitive: toy.emit, sync: EVENT, await: { topic: $t } }\n");
        assertOutcome(noTopic, Status.ERROR, FailureKind.RESOLUTION_FAILED);
        assertTrue(noTopic.message().contains("resolved to no topic glob"), noTopic.message());

        RunResult list = run("resolve:\n  t: { fixture: [toy.noise, toy.done] }\nsteps:\n"
                + "  - call: { primitive: toy.emit, args: { id: \"1\" }, sync: EVENT, await: { topic: $t,"
                + " within: 1s } }\n");
        assertOutcome(list, Status.PASS, null);
        assertEquals(await("toy.noise,toy.done", 1, null, 1000), agent.awaits.get(agent.awaits.size() - 1),
                "a reference to a list of topics is joined");
    }

    @Test
    void aFailingWaitIsEnvironment() throws Exception {
        agent.failAwait(new IllegalStateException("sync.await: INVALID_PARAMS: bad topic filter"));
        RunResult result = run("steps:\n  - emit: { id: \"1\" }\n");
        assertOutcome(result, Status.ERROR, FailureKind.ENVIRONMENT);
        assertEquals("s1", result.failedStepId());
        assertTrue(result.message().startsWith("waiting for event (toy.done) matching "), result.message());
        assertTrue(result.message().endsWith(" after toy.emit failed: sync.await: INVALID_PARAMS: bad topic filter"),
                result.message());
        assertEquals(Status.ERROR, result.steps().get(0).status());
    }

    @Test
    void cleanupCallsWaitForTheirEventAndATimeoutThereIsOnlyLogged(@TempDir Path dir) throws Exception {
        options = options.withOutputDir(dir);
        RunResult result = run("steps:\n  - lockUntil: { name: a }\n");
        assertOutcome(result, Status.PASS, null);
        // Bus: 1 start mark; the cleanup unlock (seq.before 1) publishes 2.
        assertEquals(List.of(await("toy.unlocked", 1, Map.of("which", "a"), 1500)), agent.awaits);

        agent.value("toy.unlock", Map.of());
        RunResult quiet = run("steps:\n  - lockUntil: { name: b }\n");
        assertOutcome(quiet, Status.PASS, null);
        // Run 2: 3 start mark; the silent unlock (seq.before 3) must not see the toy.unlocked of run 1.
        assertEquals(await("toy.unlocked", 3, Map.of("which", "b"), 1500), agent.awaits.get(1));
        String log = Files.readString(dir.resolve("run.jsonl"));
        assertTrue(log.contains("\"type\":\"cleanup\"") && log.contains("no event (toy.unlocked) matching"
                + " {\\\"which\\\":\\\"b\\\"} within 1500 ms after toy.unlock"), log);
    }

    @Test
    void anEventWaitAnsweringAPromptMustBeMarked() throws Exception {
        // The quiet answer is marked by the pack: the prompt the scan raised is expected.
        RunResult answered = run("steps:\n  - enter: { value: Q }\n  - answerQuietly: { choice: \"2\" }\n");
        assertOutcome(answered, Status.PASS, null);
        // Bus: 1 start mark; enter: 2 ack, 3 prompt; quiet answer (seq.before 3): 4 answered, 5 ready.
        assertEquals(await("input.requested,prompt.waiting", 3, null, 10_000),
                agent.awaits.get(agent.awaits.size() - 1));

        // An unmarked EVENT wait that returns the prompt does not make the prompt expected.
        RunResult unexpected = run("steps:\n  - call: { primitive: toy.input, args: { source: Entry, value: Q },"
                + " sync: EVENT, await: { topic: prompt.waiting } }\n");
        assertOutcome(unexpected, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(unexpected.message().startsWith("unexpected prompt (prompt.waiting)"), unexpected.message());
    }
}
