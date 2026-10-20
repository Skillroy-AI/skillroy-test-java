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

import ai.skillroy.testjava.protocol.CallWarning;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warning channel in the executor (phase8-contracts.md 7.1, 7.2; 13.1 row 32). */
class WarningChannelTest {

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    /** What toy.input answers, by the value sent. */
    private final Map<String, FakeAgentConnection.Warned> answers = new LinkedHashMap<>();
    private Object consumed;

    @BeforeEach
    void toyApplication() {
        agent.capabilities("toy.input", "toy.state", "toy.loan");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            FakeAgentConnection.Warned answer = answers.get(String.valueOf(params.get("value")));
            Map<String, Object> value = new LinkedHashMap<>(Map.of("accepted", true));
            if (consumed != null) {
                value.put("consumed", consumed);
                value.put("inputId", "toy-1");
            }
            return answer == null ? value : new FakeAgentConnection.Warned(value, answer.warnings());
        });
        agent.value("toy.state", Map.of("name", "LOAN"));
        agent.value("toy.loan", Map.of("lines", List.of()));
    }

    private static CallWarning warning(String code, String message) {
        return new CallWarning(code, message, null);
    }

    private void answer(String value, CallWarning... warnings) {
        answers.put(value, FakeAgentConnection.Warned.of(null, warnings));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunResult run(String body) throws ScenarioException {
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind()).withOutputDir(dir);
        return new PlanExecutor(pack, agent, options).run(plan(body));
    }

    private List<Map<String, Object>> records(String type) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if (type.equals(record.get("type"))) {
                out.add(record);
            }
        }
        return out;
    }

    @Test
    void anUnacceptedWarningFailsTheStepAfterEveryWarningWasRecorded() throws Exception {
        answer(" 4005 ", warning("VALUE_COERCED", "the desk took 4005"), warning("VALUE_DROPPED", null));
        RunResult result = run("steps:\n  - enter: { value: \" 4005 \" }\n  - enter: { value: next }\n");
        assertEquals(Status.FAIL, result.status(), result.message());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("s1", result.failedStepId());
        assertEquals("enter: toy.input reported VALUE_COERCED: the desk took 4005 (a step accepts it with accept:"
                + " [VALUE_COERCED])", result.message());
        assertEquals(List.of("VALUE_COERCED", "VALUE_DROPPED"), result.detail().get("unaccepted"));
        assertEquals("toy.input", result.detail().get("primitive"));
        List<Map<String, Object>> warnings = records("warning");
        assertEquals(2, warnings.size(), "both recorded before the step fails");
        assertEquals(Map.of("type", "warning", "t", warnings.get(0).get("t"), "step", "s1", "index", 1L,
                "primitive", "toy.input", "code", "VALUE_COERCED", "message", "the desk took 4005", "accepted",
                false, "source", "agent"), warnings.get(0));
        assertEquals(List.of("s1: enter: toy.input VALUE_COERCED (the desk took 4005)",
                "s1: enter: toy.input VALUE_DROPPED"), result.warnings());
        assertEquals(result.warnings(), result.steps().get(0).warnings());
        assertEquals(1, agent.callsTo("toy.input").size(), "the next step never ran");
    }

    @Test
    void anAcceptedWarningLeavesThePass() throws Exception {
        answer(" 4005 ", warning("VALUE_COERCED", "the desk took 4005"));
        answer("   ", warning("VALUE_DROPPED", "an empty entry"));
        RunResult result = run("""
                steps:
                  - enter: { value: " 4005 " }
                    accept: [VALUE_COERCED]
                  - enter: { value: "   " }
                    accept: [VALUE_DROPPED, VALUE_COERCED]
                  - enter: { value: plain }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of("s1: enter: toy.input VALUE_COERCED (the desk took 4005)",
                "s2: enter: toy.input VALUE_DROPPED (an empty entry)"), result.warnings());
        assertEquals(List.of("s1: enter: toy.input VALUE_COERCED (the desk took 4005)"),
                result.steps().get(0).warnings());
        assertTrue(result.steps().get(2).warnings().isEmpty());
        assertTrue(records("warning").stream().allMatch(r -> Boolean.TRUE.equals(r.get("accepted"))));
        assertEquals(2, records("warning").size());
        assertEquals(List.of("s1: enter: toy.input VALUE_COERCED (the desk took 4005)",
                "s2: enter: toy.input VALUE_DROPPED (an empty entry)"), records("run-end").get(0).get("warnings"));
        // The plan carries the accepted codes on every call of the step, sorted.
        Plan plan = plan("steps:\n  - enter: { value: x }\n    accept: [VALUE_DROPPED, VALUE_COERCED]\n");
        assertEquals(Set.of("VALUE_COERCED", "VALUE_DROPPED"), plan.steps().get(0).calls().get(0).accept());
        assertEquals(List.of("VALUE_COERCED", "VALUE_DROPPED"), plan.steps().get(0).calls().get(0).toJson()
                .get("accept"));
    }

    @Test
    void anUndeclaredCodeIsNeverAcceptableAndADeclaredOneIs() throws Exception {
        answer("a", warning("other.CODE", "from another pack"));
        RunResult undeclared = run("steps:\n  - enter: { value: a }\n");
        assertEquals(Status.FAIL, undeclared.status());
        assertEquals("enter: toy.input reported the undeclared warning other.CODE: from another pack",
                undeclared.message());
        ScenarioException refused = assertThrows(ScenarioException.class, () -> plan(
                "steps:\n  - enter: { value: a }\n    accept: [other.CODE]\n"));
        assertTrue(refused.getMessage().contains("accept: unknown warning code other.CODE; the base codes are"
                + " [INPUT_QUEUED, VALUE_COERCED, VALUE_DROPPED, ANSWERED_NESTED]; pack 'toy' declares none"),
                refused.getMessage());

        pack.warningCodes = Set.of("toy.LATE");
        answer("b", warning("toy.LATE", "late"));
        assertEquals(Status.PASS, run("steps:\n  - enter: { value: b }\n    accept: [toy.LATE]\n").status());
        ScenarioException never = assertThrows(ScenarioException.class, () -> plan(
                "steps:\n  - enter: { value: a }\n    accept: [INVALID_WARNING]\n"));
        assertTrue(never.getMessage().contains("INVALID_WARNING is never acceptable"), never.getMessage());
    }

    @Test
    void aMalformedChannelFailsClosed() throws Exception {
        answer("a", warning("INVALID_WARNING", "a warning that is not {code, message?, data?} with a valid code"));
        RunResult result = run("steps:\n  - enter: { value: a }\n    accept: [VALUE_COERCED]\n");
        assertEquals(Status.FAIL, result.status());
        assertTrue(result.message().startsWith("enter: toy.input reported INVALID_WARNING: "), result.message());
        assertTrue(result.message().endsWith("(never acceptable)"), result.message());
    }

    @Test
    void aPackThatDeclaresAMalformedCodeFailsTheCompilerConstruction() {
        pack.warningCodes = Set.of("other.CODE");
        ScenarioException e = assertThrows(ScenarioException.class, () -> plan("steps:\n  - enter: { value: a }\n"));
        assertTrue(e.getMessage().contains("pack 'toy' declares the warning code 'other.CODE'"), e.getMessage());
    }

    @Test
    void aCleanupCallsWarningsAreLoggedOnly() throws Exception {
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return "Key.SIGN_OFF".equals(params.get("source")) ? FakeAgentConnection.Warned.of(Map.of("accepted",
                    true), warning("VALUE_DROPPED", "signed off already")) : Map.of("accepted", true);
        });
        RunResult result = run("""
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                steps:
                  - signOn: { staff: $assistant }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        assertTrue(result.warnings().isEmpty(), "a cleanup warning is in no result list");
        List<Map<String, Object>> warnings = records("warning");
        assertEquals(1, warnings.size());
        assertEquals(Boolean.TRUE, warnings.get(0).get("cleanup"));
        assertEquals(null, warnings.get(0).get("step"));
        assertEquals(Boolean.FALSE, warnings.get(0).get("accepted"));
    }

    @Test
    void inputQueuedIsDerivedForAnUncorrelatedSync() throws Exception {
        consumed = "QUEUED";
        RunResult ready = run("steps:\n  - enter: { value: a }\n");
        assertEquals(Status.FAIL, ready.status(), "READY without ackByInputId waits for no input's own ack");
        assertEquals("enter: toy.input reported INPUT_QUEUED: input toy-1 was queued and nothing waits for its"
                + " acknowledgement (a step accepts it with accept: [INPUT_QUEUED])", ready.message());
        Map<String, Object> record = records("warning").get(0);
        assertEquals("executor", record.get("source"));
        assertEquals(Map.of("inputId", "toy-1"), record.get("data"));
        assertEquals(Status.PASS, run("steps:\n  - enter: { value: a }\n    accept: [INPUT_QUEUED]\n").status());

        pack.custom = List.of(new PlannedCall("toy.input", Map.of("source", "Entry", "value", "x"), Sync.NONE, 0));
        assertEquals(Status.FAIL, run("steps:\n  - custom: {}\n").status(), "NONE");
        pack.custom = List.of(PlannedCall.event("toy.input", Map.of("source", "Entry", "value", "x"),
                new AwaitSpec("input.acked", Map.of("source", "Entry"), 1000)));
        assertEquals(Status.FAIL, run("steps:\n  - custom: {}\n").status(), "EVENT without inputId in the match");
    }

    @Test
    void inputQueuedIsNotDerivedForACorrelatedSync() throws Exception {
        consumed = "QUEUED";
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source"), "inputId", "toy-1"));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("accepted", true, "consumed", "QUEUED", "inputId", "toy-1");
        });
        pack.custom = List.of(PlannedCall.event("toy.input", Map.of("source", "Entry", "value", "x", "inputId",
                "toy-1"), new AwaitSpec("input.acked", Map.of("inputId", "toy-1"), 1000)));
        RunResult event = run("steps:\n  - custom: {}\n");
        assertEquals(Status.PASS, event.status(), "EVENT whose match has inputId: " + event.message());
        assertTrue(records("warning").isEmpty());

        pack.syncPolicy = pack.syncPolicy.withAckByInputId(true);
        RunResult correlated = run("steps:\n  - enter: { value: a }\n  - ackOnly: { value: b }\n");
        assertEquals(Status.PASS, correlated.status(), correlated.message());
        assertTrue(correlated.warnings().isEmpty());
    }

    @Test
    void aWarningsTextIsMaskedInTheRecordAndTheResult() throws Exception {
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return FakeAgentConnection.Warned.of(Map.of("accepted", true), new CallWarning("VALUE_DROPPED",
                    "dropped for " + TestCredentials.PW_200, Map.of("note", TestCredentials.PW_200 + "!")));
        });
        RunResult result = run("steps:\n  - enter: { value: a }\n    accept: [VALUE_DROPPED]\n");
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of("s1: enter: toy.input VALUE_DROPPED (dropped for <redacted>)"), result.warnings());
        String log = Files.readString(dir.resolve("run.jsonl"));
        assertFalse(log.contains(TestCredentials.PW_200), "the run mask covers warning records");
        assertTrue(log.contains("dropped for <redacted>"));
    }

    @Test
    void theFiveArgumentSyncPolicyIsUncorrelated() {
        SyncPolicy policy = new SyncPolicy("input.acked", List.of("input.requested"), null, null, null);
        assertFalse(policy.ackByInputId());
        assertTrue(policy.withAckByInputId(true).ackByInputId());
        assertEquals(policy, policy.withAckByInputId(true).withAckByInputId(false));
    }
}
