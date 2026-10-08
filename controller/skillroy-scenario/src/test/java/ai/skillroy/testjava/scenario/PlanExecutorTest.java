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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptDecision;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutorTest {

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
            .withBind(TestCredentials.bind());
    private final Map<String, Object> state = new LinkedHashMap<>(Map.of("name", "IDLE"));

    @BeforeEach
    void toyApplication() {
        agent.capabilities("toy.input", "toy.state", "toy.loan");
        agent.on("toy.input", params -> {
            Object value = params.get("value");
            agent.publish("input.acked", Map.of("source", params.get("source")));
            if ("UNKNOWN".equals(value)) {
                agent.publish("prompt.waiting", Map.of("id", "unknown", "text", "Unknown item", "buttons",
                        List.of("OK")));
            } else if ("POST".equals(value)) {
                agent.publish("loan.posted", Map.of("loanId", 1L));
                agent.publish("input.requested", Map.of("state", "IDLE"));
            } else if (!"SILENT".equals(value)) {
                agent.publish("input.requested", Map.of("state", "LOAN"));
            }
            return Map.of("accepted", true, "acked", true);
        });
        agent.on("toy.prompt.answer", params -> {
            agent.publish("input.acked", Map.of("source", "prompt"));
            agent.publish("prompt.answered", Map.of("choice", params.get("choice")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("answered", true);
        });
        agent.on("toy.state", params -> new LinkedHashMap<>(state));
        agent.value("toy.loan", Json.parse("{\"total\":\"7.00\",\"lines\":[{\"code\":\"YRD-001\",\"qty\":2}],"
                + "\"authorisations\":[]}"));
        agent.value("toy.poke", Map.of());
        agent.value("toy.lock", Map.of());
        agent.value("toy.unlock", Map.of());
        agent.value("swing.click", Map.of("rung", "ACTION", "fired", true));
        agent.value("swing.type", Map.of("mode", "SET", "typed", 4L));
        agent.value("swing.key", Map.of("dispatchedTo", "h3"));
        agent.value("swing.dismiss", Map.of("dismissedBy", "BUTTON_ACTION"));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of(Map.of("title", "Unknown item", "text", "?"))));
        agent.value("app.state", Map.of("toy", Map.of("name", "LOAN")));
        agent.value("swing.snapshot", Json.parse("{\"seq\":9,\"truncated\":false,\"windows\":[{\"ref\":\"h1\","
                + "\"role\":\"window\",\"name\":\"Toy\",\"children\":[{\"ref\":\"h12\",\"role\":\"button\","
                + "\"name\":\"DUE\",\"enabled\":false}]}]}"));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunResult run(String body) throws ScenarioException {
        return new PlanExecutor(pack, agent, options).run(plan(body));
    }

    private static void assertOutcome(RunResult result, Status status, FailureKind kind) {
        assertEquals(status, result.status(), result.message());
        assertEquals(kind, result.failureKind(), result.message());
    }

    // ------------------------------------------------------------------------------------------------ passing runs

    @Test
    void passingRunWithEveryBaseVerb() throws Exception {
        agent.value("toy.read", Map.of("x", 1L));
        RunResult result = run("""
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                steps:
                  - signOn: { staff: $assistant }
                  - call: { primitive: toy.read, args: { who: $assistant.code } }
                  - click: { by: { action: DEPOSIT } }
                  - type: { target: h5, text: "7.00", enter: true }
                  - press: { key: ENTER }
                  - dismiss: { button: OK }
                  - enter: { value: POST }
                  - waitFor: { topic: loan.posted, within: 2s }
                  - expect: { "loan.lines[code=YRD-001].qty": "2.00", "state.name": IDLE }
                  - mark: done
                verify:
                  - { "loan.total": { gte: 7 } }
                """);
        assertOutcome(result, Status.PASS, null);
        assertNull(result.message());
        assertEquals(List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "verify"),
                result.steps().stream().map(StepResult::id).toList());
        assertTrue(result.steps().stream().allMatch(s -> s.status() == Status.PASS));
        List<String> actions = agent.methods().stream().filter(m -> !m.equals("evt.query")
                && !m.equals("toy.state") && !m.equals("toy.loan")).toList();
        assertEquals(List.of("trace.mark", "toy.input", "toy.input", "toy.read", "swing.click", "swing.settle",
                "swing.type", "swing.settle", "swing.key", "swing.settle", "swing.dismiss", "swing.settle",
                "toy.input", "sync.await", "trace.mark", "toy.input"), actions, "the last call is the cleanup");
        assertEquals(Map.of("who", "100"), agent.callsTo("toy.read").get(0).params());
        assertEquals(Map.of("source", "Entry", "value", TestCredentials.PW_100), agent.callsTo("toy.input").get(1)
                .params());
        assertEquals(List.of("value"), agent.callsTo("toy.input").get(1).options().secretParams(),
                "A34: the joined field is announced");
        assertEquals(List.of(), agent.callsTo("toy.input").get(0).options().secretParams(), "the code is not secret");
        assertEquals(Map.of("source", "Entry", "value", "100"), agent.callsTo("toy.input").get(0).params());
        assertEquals(Map.of("source", "Key.SIGN_OFF"), agent.callsTo("toy.input").get(3).params());
        assertEquals("t/s1/2", agent.callsTo("toy.input").get(1).options().correlationId());
        assertEquals(10_000L, agent.callsTo("toy.input").get(1).options().timeoutMs());
        assertEquals(0, pack.recovered.get());
    }

    @Test
    void syncNoneWaitsForNothing() throws Exception {
        assertOutcome(run("steps:\n  - call: { primitive: toy.poke }\n"), Status.PASS, null);
        assertEquals(List.of(), agent.awaits);
        assertTrue(agent.callsTo("swing.settle").isEmpty());
    }

    @Test
    void syncAckAwaitsTheAckTopicSinceSeqBefore() throws Exception {
        assertOutcome(run("steps:\n  - ackOnly: { value: 1 }\n"), Status.PASS, null);
        long seqBefore = 1; // the run's trace.mark is event 1
        assertEquals(List.of(new FakeAgentConnection.Await("input.acked", seqBefore, null, Duration.ofSeconds(10))),
                agent.awaits);
    }

    @Test
    void syncReadyAwaitsAckThenReadySinceSeqBefore() throws Exception {
        pack.syncPolicy = new SyncPolicy("input.acked", List.of("input.requested", "prompt.waiting"), null,
                Duration.ofSeconds(2), Duration.ofSeconds(3));
        assertOutcome(run("steps:\n  - enter: { value: 1 }\n  - enter: { value: 2 }\n"), Status.PASS, null);
        assertEquals(List.of(
                new FakeAgentConnection.Await("input.acked", 1, null, Duration.ofSeconds(2)),
                new FakeAgentConnection.Await("input.requested,prompt.waiting", 1, null, Duration.ofSeconds(3)),
                new FakeAgentConnection.Await("input.acked", 3, null, Duration.ofSeconds(2)),
                new FakeAgentConnection.Await("input.requested,prompt.waiting", 3, null, Duration.ofSeconds(3))),
                agent.awaits);
    }

    @Test
    void syncReadyWithoutAckTopicAwaitsReadyOnly() throws Exception {
        pack.syncPolicy = new SyncPolicy(null, List.of("input.requested"), List.of(), null, null);
        assertOutcome(run("steps:\n  - enter: { value: 1 }\n"), Status.PASS, null);
        assertEquals(List.of(new FakeAgentConnection.Await("input.requested", 1, null, Duration.ofSeconds(10))),
                agent.awaits);
        assertFalse(agent.methods().contains("evt.query"), "no prompt topics, no prompt checks");
    }

    @Test
    void syncSettleCallsSwingSettle() throws Exception {
        assertOutcome(run("steps:\n  - poke: {}\n"), Status.PASS, null);
        assertEquals(List.of(), agent.awaits);
        List<FakeAgentConnection.Call> settles = agent.callsTo("swing.settle");
        assertEquals(1, settles.size());
        assertEquals(Map.of("timeoutMs", 3000L), settles.get(0).params());
        assertEquals(5000L, settles.get(0).options().timeoutMs());
        assertEquals(List.of("trace.mark", "toy.poke", "swing.settle", "evt.query"), agent.methods());
    }

    @Test
    void waitForWaitsSinceTheLastActionNotTheLastMark() throws Exception {
        assertOutcome(run("steps:\n  - enter: { value: POST }\n  - mark: between\n"
                + "  - waitFor: { topic: loan.posted }\n"), Status.PASS, null);
        Map<String, Object> params = agent.callsTo("sync.await").get(0).params();
        assertEquals(1L, params.get("since"), "the enter call's seq.before");
        assertEquals("loan.posted", params.get("topics"));
        assertEquals(10_000L, params.get("timeoutMs"));
    }

    // ------------------------------------------------------------------------------------------------ sync failures

    @Test
    void syncTimeoutCarriesDiagnostics() throws Exception {
        RunResult result = run("steps:\n  - enter: { value: SILENT }\n  - enter: { value: 2 }\n");
        assertOutcome(result, Status.FAIL, FailureKind.SYNC_TIMEOUT);
        assertEquals("s1", result.failedStepId());
        assertTrue(result.message().contains("no ready event (input.requested,prompt.waiting) within 10000 ms after"
                + " toy.input"), result.message());
        Map<String, Object> detail = result.detail();
        assertEquals("ready", detail.get("wait"));
        assertEquals(1L, detail.get("since"));
        assertEquals("READY", detail.get("sync"));
        assertEquals(Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()), detail.get("edtState"));
        assertTrue(String.valueOf(detail.get("dialogs")).contains("Unknown item"));
        assertEquals(Map.of("toy", Map.of("name", "LOAN")), detail.get("appState"));
        assertTrue(agent.callsTo("toy.input").stream().noneMatch(c -> "2".equals(c.params().get("value"))),
                "s2 never ran");
        assertEquals(Status.FAIL, result.steps().get(0).status());
        assertEquals(1, result.steps().size());
    }

    @Test
    void unsettledUiIsSyncTimeout() throws Exception {
        agent.value("swing.settle", Map.of("settled", false, "ms", 3000L));
        RunResult result = run("steps:\n  - poke: {}\n");
        assertOutcome(result, Status.FAIL, FailureKind.SYNC_TIMEOUT);
        assertTrue(result.message().contains("did not settle within 3000 ms"), result.message());
        assertTrue(result.detail().containsKey("edtState"));
    }

    @ParameterizedTest
    @CsvSource({
        "-32005, FAIL, ASSERTION",
        "-32006, FAIL, ASSERTION",
        "-32007, FAIL, ASSERTION",
        "-32001, FAIL, SYNC_TIMEOUT",
        "-32602, ERROR, ENVIRONMENT",
        "-32603, ERROR, ENVIRONMENT",
        "-32011, ERROR, ENVIRONMENT",
        "-32601, ERROR, ENVIRONMENT",
        "-32031, ERROR, ENVIRONMENT"})
    void rpcErrorsMapToVerdicts(int code, Status status, FailureKind kind) throws Exception {
        agent.error("toy.input", code, "nope");
        RunResult result = run("steps:\n  - enter: { value: 1 }\n");
        assertOutcome(result, status, kind);
        assertEquals("s1", result.failedStepId());
        assertEquals("toy.input failed: " + ErrorCodes.name(code) + ": nope", result.message());
        assertEquals(ErrorCodes.name(code), result.detail().get("error"));
        assertEquals((long) code, result.detail().get("code"));
        assertEquals(Map.of("why", "test"), result.detail().get("data"));
        assertEquals(kind == FailureKind.SYNC_TIMEOUT, result.detail().containsKey("edtState"));
        assertEquals(1, pack.recovered.get());
    }

    // ------------------------------------------------------------------------------------------------ preconditions

    @Test
    void requiresAlternativesAndUnmetCapabilities() throws Exception {
        agent.capability("toy.b", CapabilityState.UNAVAILABLE, "not in this build");
        RunResult result = run("requires: [toy.input, \"toy.a | toy.b\", toy.c]\nsteps:\n  - enter: {value: 1}\n");
        assertOutcome(result, Status.ERROR, FailureKind.PRECONDITION_FAILED);
        assertEquals("unmet requirements: toy.a | toy.b: toy.a: missing; toy.b: UNAVAILABLE (not in this build);"
                + " toy.c: missing", result.message());
        assertEquals(List.of("toy.a | toy.b: toy.a: missing; toy.b: UNAVAILABLE (not in this build)",
                "toy.c: missing"), result.detail().get("unmet"));
        assertNull(result.failedStepId());
        assertTrue(agent.callsTo("toy.input").isEmpty(), "no step ran");
        assertEquals(List.of(), result.steps());

        agent.capability("toy.b", CapabilityState.DEGRADED, "slow");
        agent.capabilities("toy.c");
        assertOutcome(run("requires: [toy.input, \"toy.a | toy.b\", toy.c]\nsteps: []\n"), Status.PASS, null);
    }

    @Test
    void launchPropertiesAreChecked() throws Exception {
        agent.property("toy.seed", "B");
        RunResult result = run("launchProperties: { toy.seed: A, toy.mode: test }\nsteps: []\n");
        assertOutcome(result, Status.ERROR, FailureKind.PRECONDITION_FAILED);
        assertEquals("launch property toy.seed: expected 'A', actual 'B' (and 1 more)", result.message());
        assertEquals("toy.seed", result.detail().get("property"));
        assertEquals("A", result.detail().get("expected"));
        assertEquals("B", result.detail().get("actual"));
        assertEquals(Map.of("names", List.of("toy.seed", "toy.mode")),
                agent.callsTo("jvm.properties").get(0).params());

        agent.property("toy.seed", "A");
        RunResult unset = run("launchProperties: { toy.seed: A, toy.mode: test }\nsteps: []\n");
        assertEquals("launch property toy.mode: expected 'test', actual <not set>", unset.message());

        agent.property("toy.mode", "test");
        assertOutcome(run("launchProperties: { toy.seed: A, toy.mode: test }\nsteps: []\n"), Status.PASS, null);
    }

    @Test
    void preflightFailureIsEnvironment() throws Exception {
        List<String> order = new ArrayList<>();
        pack.preflight = List.of(check("desk.open", () -> {
            order.add("desk");
            return null;
        }), check("printer.online", () -> {
            order.add("printer");
            return "paper out";
        }), check("never", () -> {
            order.add("never");
            return null;
        }));
        RunResult result = run("steps:\n  - enter: { value: 1 }\n");
        assertOutcome(result, Status.ERROR, FailureKind.ENVIRONMENT);
        assertEquals("pre-flight check printer.online: paper out", result.message());
        assertEquals(List.of("desk", "printer"), order);
        assertTrue(agent.callsTo("toy.input").isEmpty());

        pack.preflight = List.of(check("throws", () -> {
            throw new IllegalStateException("device gone");
        }));
        RunResult thrown = run("steps: []\n");
        assertOutcome(thrown, Status.ERROR, FailureKind.ENVIRONMENT);
        assertTrue(thrown.message().contains("device gone"), thrown.message());

        order.clear();
        pack.preflight = List.of(check("after-requires", () -> {
            order.add("ran");
            return null;
        }));
        run("requires: [missing.cap]\nsteps: []\n");
        assertEquals(List.of(), order, "pre-flight runs only after the requirements are met");
    }

    private interface CheckBody {
        String run() throws Exception;
    }

    private static PreflightCheck check(String id, CheckBody body) {
        return new PreflightCheck() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String check(ai.skillroy.testjava.controller.AgentConnection agent) throws Exception {
                return body.run();
            }
        };
    }

    @Test
    void unresolvableReferencesFailBeforeAnyStep() throws Exception {
        RunResult result = run("resolve:\n  item: { fixture: { code: A } }\nsteps:\n  - enter: { value: 1 }\n"
                + "  - enter: { value: $item.barcode }\n");
        assertOutcome(result, Status.ERROR, FailureKind.RESOLUTION_FAILED);
        assertTrue(result.message().contains("$item.barcode"), result.message());
        assertEquals("$item.barcode", result.detail().get("reference"));
        assertTrue(agent.callsTo("toy.input").isEmpty());

        Plan plan = plan("resolve:\n  item: { fixture: { code: A } }\nsteps:\n  - enter: { value: $item.code }\n");
        RunResult missing = new PlanExecutor(pack, agent, options).run(plan, Bindings.of(Map.of("other", 1L)));
        assertOutcome(missing, Status.ERROR, FailureKind.RESOLUTION_FAILED);
        RunResult supplied = new PlanExecutor(pack, agent, options).run(plan, Bindings.of(Map.of("item",
                Map.of("code", "B"))));
        assertOutcome(supplied, Status.PASS, null);
        assertEquals("B", agent.callsTo("toy.input").get(0).params().get("value"));
    }

    @Test
    void planForAnotherPackIsEnvironment() throws Exception {
        Plan plan = plan("steps: []\n");
        Plan foreign = new Plan(1, "t", "other", "1", plan.sourceHash(), List.of(), List.of(), List.of(), List.of(),
                List.of(), Map.of(), List.of(), Map.of(), 1000);
        assertOutcome(new PlanExecutor(pack, agent, options).run(foreign), Status.ERROR, FailureKind.ENVIRONMENT);
    }

    // ------------------------------------------------------------------------------------------------ expectations

    @Test
    void expectationsAreRetriedUntilTheyHold() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        agent.on("toy.state", params -> Map.of("name", reads.incrementAndGet() >= 4 ? "IDLE" : "BUSY"));
        RunResult result = run("steps:\n  - expect: { \"state.name\": IDLE }\n");
        assertOutcome(result, Status.PASS, null);
        assertEquals(4, reads.get());
        assertEquals(3, clock.sleeps, "three 100 ms polls");
        assertEquals(Duration.ofMillis(300), result.duration());
    }

    @Test
    void factsAreFetchedOncePerAttempt() throws Exception {
        assertOutcome(run("steps:\n  - expect: { \"loan.total\": \"7.0\", \"loan.lines[0].qty\": 2,"
                + " \"loan.lines\": { size: 1 } }\n"), Status.PASS, null);
        assertEquals(1, agent.callsTo("toy.loan").size());
    }

    @Test
    void failedExpectationIsAssertionWithSnapshot() throws Exception {
        options = options.withStepTimeout(Duration.ofSeconds(1));
        RunResult result = run("steps:\n  - expect: { \"state.name\": LOAN, \"loan.total\": 7 }\n");
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertEquals("s1", result.failedStepId());
        assertEquals("expectation failed: state.name {\"equals\":\"LOAN\"}: expected \"LOAN\" but was \"IDLE\"",
                result.message());
        assertEquals("state.name", result.detail().get("path"));
        assertEquals(Map.of("equals", "LOAN"), result.detail().get("matcher"));
        assertEquals("IDLE", result.detail().get("actual"));
        assertEquals(11L, result.detail().get("attempts"));
        assertEquals(10, clock.sleeps, "1 s step timeout / 100 ms polls");
        String snapshot = (String) result.detail().get("snapshot");
        assertTrue(snapshot.contains("- button \"DUE\" [ref=h12] (disabled)"), snapshot);
        assertTrue(snapshot.contains("toy: {\"name\":\"LOAN\"}"), snapshot);
        assertEquals(1, pack.recovered.get());
    }

    /**
     * {@code expectationsOnMaskedValues} (phase5-contracts.md 12.2, the fix wave; for a plan a model or a client
     * wrote): an expectation on a value the run's mask changes never holds, its actual is the masked value and the
     * reason says it holds a secret, so a run's PASS or FAIL answers no question about a secret; without the option (a
     * scenario a person committed) the value is matched as read, and with it a value without a secret is unchanged.
     */
    @Test
    void anExpectationOnAMaskedValueHoldsOnlyWithoutTheMaskedOption() throws Exception {
        state.put("pin", TestCredentials.PW_100);
        options = options.withStepTimeout(Duration.ofMillis(300));
        String body = "steps:\n  - expect: { \"state.pin\": { matches: \"^" + TestCredentials.PW_100.substring(0, 4)
                + "\" } }\n";
        assertOutcome(run(body), Status.PASS, null);
        assertFalse(RunOptions.defaults().expectationsOnMaskedValues(), "off by default");

        options = options.withExpectationsOnMaskedValues(true);
        RunResult masked = run(body);
        assertOutcome(masked, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(masked.message().endsWith(": " + Matchers.HOLDS_A_SECRET), masked.message());
        assertEquals(SecretMask.REDACTED, masked.detail().get("actual"));
        assertEquals(true, masked.detail().get("resolved"));
        assertOutcome(run("steps:\n  - expect: { \"state.name\": { matches: \"^ID\" } }\n"), Status.PASS, null);
        assertTrue(options.withStepTimeout(null).expectationsOnMaskedValues(), "kept by the other withers");
    }

    @Test
    void unresolvedPathFailsWithTheReason() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(200));
        RunResult result = run("resolve:\n  item: { fixture: { code: NONE } }\nsteps:\n"
                + "  - expect: { \"loan.lines[code=$item.code].qty\": 1 }\n");
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(result.message().contains("no element of loan.lines match [code=NONE]"), result.message());
        assertEquals("loan.lines[code=$item.code].qty", result.detail().get("path"));
        assertEquals(false, result.detail().get("resolved"));
    }

    @Test
    void unreadableFactIsEnvironment() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(300));
        agent.error("toy.state", ErrorCodes.INTERNAL_ERROR, "engine down");
        RunResult result = run("steps:\n  - expect: { \"state.name\": IDLE }\n");
        assertOutcome(result, Status.ERROR, FailureKind.ENVIRONMENT);
        assertTrue(result.message().contains("fact 'state' could not be read"), result.message());
        assertTrue(result.message().contains("engine down"), result.message());
    }

    @Test
    void guardedExpectationOnUnavailableCapabilityDegrades() throws Exception {
        agent.capability("toy.loan.authorisations", CapabilityState.UNAVAILABLE, "old build");
        String body = "steps:\n  - expect:\n      \"loan.total\": \"7.00\"\n"
                + "      \"loan.authorisations\": { size: 3, capability: toy.loan.authorisations }\n";
        RunResult result = run(body);
        assertOutcome(result, Status.DEGRADED, null);
        assertEquals(List.of("s1: loan.authorisations {\"size\":3} skipped: capability toy.loan.authorisations is"
                + " UNAVAILABLE (old build)"), result.skippedExpectations());
        assertEquals(Status.DEGRADED, result.steps().get(0).status());
        assertEquals(result.skippedExpectations(), result.steps().get(0).skippedExpectations());
        assertEquals(0, pack.recovered.get(), "DEGRADED does not recover");

        agent.capability("toy.loan.authorisations", CapabilityState.DEGRADED, "partial");
        assertOutcome(run(body), Status.DEGRADED, null);

        agent.capability("toy.loan.authorisations", CapabilityState.OK, null);
        options = options.withStepTimeout(Duration.ofMillis(100));
        RunResult evaluated = run(body);
        assertOutcome(evaluated, Status.FAIL, FailureKind.ASSERTION);
        assertEquals(List.of(), evaluated.skippedExpectations());

        RunResult held = run(body.replace("size: 3", "size: 0"));
        assertOutcome(held, Status.PASS, null);
    }

    @Test
    void aFailureAfterASkippedGuardIsStillAFailure() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(100));
        RunResult result = run("steps:\n  - expect: { \"loan.total\": { gt: 1, capability: toy.missing } }\n"
                + "  - expect: { \"state.name\": BUSY }\n");
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertEquals(1, result.skippedExpectations().size());
        assertTrue(result.skippedExpectations().get(0).contains("toy.missing is missing"));
    }

    // ------------------------------------------------------------------------------------------------ prompts

    @Test
    void unexpectedPromptFailsByDefault() throws Exception {
        RunResult result = run("steps:\n  - enter: { value: UNKNOWN }\n  - key: { action: DUE }\n");
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertEquals("s1", result.failedStepId());
        assertTrue(result.message().startsWith("unexpected prompt (prompt.waiting)"), result.message());
        @SuppressWarnings("unchecked")
        Map<String, Object> prompt = (Map<String, Object>) result.detail().get("prompt");
        assertEquals("prompt.waiting", prompt.get("topic"));
        Map<String, Object> query = agent.callsTo("evt.query").get(0).params();
        assertEquals(Map.of("since", 1L, "topics", "prompt.waiting", "limit", 200L), query);
    }

    @Test
    void promptAwaitedByTheNextStepIsExpected() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        pack.promptPolicy = (event, a) -> {
            asked.incrementAndGet();
            return PromptDecision.fail("asked");
        };
        RunResult result = run("steps:\n  - enter: { value: UNKNOWN }\n  - expect: { \"state.name\": IDLE }\n"
                + "  - expectPrompt: { within: 2s }\n  - answer: { choice: OK }\n  - key: { action: DUE }\n");
        assertOutcome(result, Status.PASS, null);
        assertEquals(0, asked.get());
        assertEquals(1L, agent.callsTo("sync.await").get(0).params().get("since"));
    }

    @Test
    void promptAnsweredLaterInTheSameStepIsExpected() throws Exception {
        assertOutcome(run("steps:\n  - scanAndAnswer: { value: UNKNOWN }\n  - key: { action: DUE }\n"),
                Status.PASS, null);
    }

    @Test
    void packPolicyMayHandleAPrompt(@TempDir Path dir) throws Exception {
        options = options.withOutputDir(dir);
        pack.promptPolicy = (event, a) -> {
            try {
                a.call("toy.prompt.answer", Map.of("choice", "OK"));
            } catch (Exception e) {
                return PromptDecision.fail(e.toString());
            }
            return PromptDecision.handled("answered " + event.payload().get("id"));
        };
        RunResult result = run("steps:\n  - enter: { value: UNKNOWN }\n  - key: { action: DUE }\n");
        assertOutcome(result, Status.PASS, null);
        assertEquals(1, agent.callsTo("toy.prompt.answer").size());
        assertTrue(Files.readString(dir.resolve("run.jsonl")).contains("\"decision\":\"HANDLED\",\"reason\":"
                + "\"answered unknown\""));

        RunResult strict = run("on_prompt: { unexpected: fail }\nsteps:\n  - enter: { value: UNKNOWN }\n"
                + "  - key: { action: DUE }\n");
        assertOutcome(strict, Status.FAIL, FailureKind.ASSERTION);
        assertEquals(1, agent.callsTo("toy.prompt.answer").size(), "on_prompt: fail bypasses the pack policy");

        pack.promptPolicy = (event, a) -> PromptDecision.ignore("harmless");
        assertOutcome(run("steps:\n  - enter: { value: UNKNOWN }\n"), Status.PASS, null);
        pack.promptPolicy = (event, a) -> null;
        assertOutcome(run("steps:\n  - enter: { value: UNKNOWN }\n"), Status.FAIL, FailureKind.ASSERTION);
        pack.promptPolicy = (event, a) -> {
            throw new IllegalStateException("policy bug");
        };
        assertOutcome(run("steps:\n  - enter: { value: UNKNOWN }\n"), Status.ERROR, FailureKind.ENVIRONMENT);
    }

    // ------------------------------------------------------------------------------------------------ end of run

    @Test
    void cleanupRunsInReverseOrderAndCannotChangeTheVerdict() throws Exception {
        agent.on("toy.unlock", params -> {
            if ("b".equals(params.get("which"))) {
                throw new AgentRpcException("toy.unlock", new RpcError(ErrorCodes.INTERNAL_ERROR, "stuck", null));
            }
            return Map.of();
        });
        String body = "resolve:\n  c: { fixture: { code: \"1\" }, credential: { realm: staff, key: code } }\n"
                + "steps:\n  - lock: { name: a }\n"
                + "  - signOn: { staff: $c }\n  - lock: { name: b }\n";
        RunResult result = run(body);
        assertOutcome(result, Status.PASS, null);
        List<String> tail = agent.methods().subList(agent.methods().size() - 3, agent.methods().size());
        assertEquals(List.of("toy.unlock", "toy.input", "toy.unlock"), tail);
        List<FakeAgentConnection.Call> unlocks = agent.callsTo("toy.unlock");
        assertEquals("b", unlocks.get(0).params().get("which"));
        assertEquals("a", unlocks.get(1).params().get("which"));
        assertEquals(Map.of("source", "Key.SIGN_OFF"), agent.callsTo("toy.input").get(2).params());

        RunResult failed = run(body + "  - expect: { \"state.name\": NEVER }\n");
        assertOutcome(failed, Status.FAIL, FailureKind.ASSERTION);
        assertEquals(4, agent.callsTo("toy.unlock").size(), "cleanup also runs after a failure");
    }

    @Test
    void recoverRunsOnlyAfterANonPassingRun() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(100));
        assertOutcome(run("steps:\n  - expect: { \"state.name\": IDLE }\n"), Status.PASS, null);
        assertEquals(0, pack.recovered.get());
        assertOutcome(run("steps:\n  - expect: { \"state.name\": { exists: true, capability: x } }\n"),
                Status.DEGRADED, null);
        assertEquals(0, pack.recovered.get());
        assertOutcome(run("steps:\n  - expect: { \"state.name\": BUSY }\n"), Status.FAIL, FailureKind.ASSERTION);
        assertEquals(1, pack.recovered.get());
        assertOutcome(run("requires: [x]\nsteps: []\n"), Status.ERROR, FailureKind.PRECONDITION_FAILED);
        assertEquals(1, pack.recovered.get(), "Phase 2 (D30, Q11): nothing is sent before the start mark, not even"
                + " recover");
        pack.recoverFailure = new IllegalStateException("cannot recover");
        RunResult failed = run("steps:\n  - expect: { \"state.name\": BUSY }\n");
        assertOutcome(failed, Status.FAIL, FailureKind.ASSERTION);
        assertEquals(2, pack.recovered.get());
    }

    @Test
    void lostAgentIsAgentLostOrTargetRestarted(@TempDir Path dir) throws Exception {
        agent.loseOn("toy.input");
        options = options.withOutputDir(dir);
        RunResult lost = run("resolve:\n  c: { fixture: { code: \"1\" }, credential: { realm: staff, key: code } }\n"
                + "steps:\n"
                + "  - mark: start\n  - signOn: { staff: $c }\n  - mark: never\n");
        assertOutcome(lost, Status.ERROR, FailureKind.AGENT_LOST);
        assertEquals("s2", lost.failedStepId());
        assertTrue(lost.message().contains("connection refused"), lost.message());
        assertEquals(Status.ERROR, lost.steps().get(1).status());
        assertEquals(0, pack.recovered.get(), "recover is skipped when the agent is lost");
        assertFalse(Files.exists(dir.resolve("failure")), "no evidence from a lost agent");
        String log = Files.readString(dir.resolve("run.jsonl"));
        assertTrue(log.contains("\"type\":\"recover\""), log);

        FakeAgentConnection gone = new FakeAgentConnection().loseOn("trace.mark");
        RunResult restarted = new PlanExecutor(pack, gone, options.withOutputDir(null).withTargetRestarted(() -> true))
                .run(plan("steps:\n  - mark: x\n"));
        assertOutcome(restarted, Status.ERROR, FailureKind.TARGET_RESTARTED);
        RunResult probeFails = new PlanExecutor(pack, gone, options.withOutputDir(null).withTargetRestarted(() -> {
            throw new IllegalStateException("no discovery dir");
        })).run(plan("steps: []\n"));
        assertOutcome(probeFails, Status.ERROR, FailureKind.AGENT_LOST);
        assertNull(probeFails.failedStepId());
    }

    // ------------------------------------------------------------------------------------------------ outputs

    @Test
    void runJsonlRecordsTheRun(@TempDir Path dir) throws Exception {
        options = options.withOutputDir(dir);
        RunResult result = run("resolve:\n  item: { fixture: { code: YRD-001 } }\nsteps:\n"
                + "  - enter: { value: $item.code }\n  - expect: { \"loan.lines[code=$item.code].qty\": 2 }\n"
                + "  - expect: { \"loan.total\": { gt: 0, capability: toy.nope } }\n");
        assertOutcome(result, Status.DEGRADED, null);
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            records.add(Json.parseObject(line));
        }
        List<String> types = records.stream().map(r -> (String) r.get("type")).toList();
        assertEquals("run-start", types.get(0));
        assertEquals("run-end", types.get(types.size() - 1));
        assertEquals(List.of("run-start", "requires", "bind", "call", "call", "sync", "sync", "expect", "expect",
                "ledger", "run-end"), types);
        Map<String, Object> call = records.stream().filter(r -> "toy.input".equals(r.get("primitive")))
                .findFirst().orElseThrow();
        assertEquals("s1", call.get("step"));
        assertEquals(Map.of("source", "Entry", "value", "YRD-001"), call.get("params"));
        assertEquals(Map.of("accepted", true, "acked", true), call.get("value"));
        assertEquals(Map.of("before", 1L, "after", 3L), call.get("seq"));
        assertTrue(call.containsKey("durationMs"));
        Map<String, Object> ready = records.stream().filter(r -> "ready".equals(r.get("wait"))).findFirst()
                .orElseThrow();
        assertEquals(1L, ready.get("since"));
        assertEquals(3L, ready.get("eventSeq"));
        Map<String, Object> expect = records.stream().filter(r -> "held".equals(r.get("result"))).findFirst()
                .orElseThrow();
        assertEquals("loan.lines[code=$item.code].qty", expect.get("path"));
        assertEquals(2L, expect.get("actual"));
        assertEquals(1L, expect.get("attempts"));
        assertTrue(records.stream().anyMatch(r -> "skipped".equals(r.get("result"))));
        Map<String, Object> end = records.get(records.size() - 1);
        assertEquals("DEGRADED", end.get("status"));
        assertEquals(1, ((List<?>) end.get("skippedExpectations")).size());
        String bindings = Files.readString(dir.resolve("bindings.json"));
        assertTrue(bindings.contains("\"code\": \"YRD-001\""), bindings);
        assertFalse(Files.exists(dir.resolve("failure")));
    }

    @Test
    void failureEvidenceIsWritten(@TempDir Path dir) throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        agent.capabilities("swing.capture.paint");
        agent.value("swing.capture", Map.of("format", "png", "width", 1L, "height", 1L, "base64",
                Base64.getEncoder().encodeToString(png)));
        for (int i = 0; i < 250; i++) {
            agent.publish("noise", Map.of("i", (long) i));
        }
        options = options.withOutputDir(dir).withStepTimeout(Duration.ofMillis(100));
        RunResult result = run("steps:\n  - expect: { \"state.name\": BUSY }\n");
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        Path failure = dir.resolve("failure");
        assertArrayEquals(png, Files.readAllBytes(failure.resolve("capture.png")));
        assertTrue(Files.readString(failure.resolve("snapshot.json")).contains("\"DUE\""));
        assertTrue(Files.readString(failure.resolve("app-state.json")).contains("\"LOAN\""));
        assertTrue(Files.readString(failure.resolve("dialogs.json")).contains("Unknown item"));
        Map<String, Object> events = Json.parseObject(Files.readString(failure.resolve("events.json")));
        assertEquals(200, ((List<?>) events.get("events")).size());
        List<FakeAgentConnection.Call> queries = agent.callsTo("evt.query");
        assertEquals(Map.of("since", 51L, "limit", 200L), queries.get(queries.size() - 1).params(),
                "the last 200 of 251 events");
    }

    @Test
    void evidenceIsOptionalAndCaptureNeedsItsCapability(@TempDir Path dir) throws Exception {
        options = options.withOutputDir(dir).withStepTimeout(Duration.ofMillis(100));
        assertOutcome(run("steps:\n  - expect: { \"state.name\": BUSY }\n"), Status.FAIL, FailureKind.ASSERTION);
        assertTrue(Files.exists(dir.resolve("failure/snapshot.json")));
        assertFalse(Files.exists(dir.resolve("failure/capture.png")), "swing.capture.paint is not OK");
        assertTrue(agent.callsTo("swing.capture").isEmpty());

        Path other = dir.resolve("other");
        options = options.withOutputDir(other).withCaptureOnFailure(false);
        RunResult result = run("steps:\n  - expect: { \"state.name\": BUSY }\n");
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertFalse(Files.exists(other.resolve("failure")));
        assertFalse(result.detail().containsKey("snapshot"));
    }

    @Test
    void artifactsAreKeptOnSuccess(@TempDir Path dir) throws Exception {
        agent.capabilities("swing.capture.paint");
        agent.value("swing.capture", Map.of("base64", Base64.getEncoder().encodeToString(new byte[] {7})));
        options = options.withOutputDir(dir);
        assertOutcome(run("artifacts: [capture, video]\nsteps: []\n"), Status.PASS, null);
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(dir.resolve("artifacts/capture.png")));
        assertTrue(Files.readString(dir.resolve("run.jsonl")).contains("\"kind\":\"video\",\"ok\":false"));
    }

    @Test
    void writesNothingWithoutAnOutputDirectory() throws Exception {
        RunResult result = new PlanExecutor(pack, agent, RunOptions.defaults().withClock(clock)
                .withSleeper(clock.sleeper())).run(plan("steps:\n  - enter: { value: 1 }\n"));
        assertOutcome(result, Status.PASS, null);
        assertFalse(agent.isClosed(), "the executor does not close the connection");
    }
}
