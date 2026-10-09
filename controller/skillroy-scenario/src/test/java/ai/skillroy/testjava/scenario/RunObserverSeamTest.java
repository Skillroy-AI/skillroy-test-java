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

import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.observe.AwaitRecord;
import ai.skillroy.testjava.scenario.observe.CallEnd;
import ai.skillroy.testjava.scenario.observe.CallStart;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.ScenarioStart;
import ai.skillroy.testjava.scenario.observe.SessionStart;
import ai.skillroy.testjava.scenario.observe.StepStart;
import ai.skillroy.testjava.scenario.observe.ToolCallRecord;
import ai.skillroy.testjava.scenario.observe.TurnRecord;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code RunObserver} seam of the executor (phase6-contracts.md 10.2, 13.1 item 5): with a recording observer every
 * hook point is called beside the record it mirrors (the run's start and end, each step, each call with its waits and
 * its trace parent, every bind record and the executor's final one, the scenario records, the step records, every
 * model call), with masked values; a session's steps, records and close; {@code RunOptions} and {@code SessionOptions}
 * keep their older constructors; {@code NONE} costs a run nothing it did not cost before; an observer that throws
 * changes no verdict; the bind records carry {@code durationMs}.
 */
class RunObserverSeamTest {

    @TempDir
    Path dir;

    private final ToyResolution toy = new ToyResolution();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private ToyPack pack;
    private final CostLedger ledger = new CostLedger();

    /** Every report, as {@code method type?} plus what it carried. */
    private static class Recording implements RunObserver {

        final List<String> calls = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();
        int open;
        int maxOpen;

        private void add(String call, Object value) {
            calls.add(call);
            values.add(value);
        }

        @Override
        public void runStarted(RunStart run) {
            add("runStarted", run);
        }

        @Override
        public void scenarioStarted(ScenarioStart scenario) {
            add("scenarioStarted", scenario);
        }

        @Override
        public void scenarioEnded(RunResult result) {
            add("scenarioEnded", result);
        }

        @Override
        public void bindEvent(String type, Map<String, Object> fields) {
            add("bindEvent " + type, fields);
            records.computeIfAbsent("bind:" + type, k -> new ArrayList<>()).add(fields);
        }

        @Override
        public void scenarioRecord(String type, Map<String, Object> fields) {
            add("scenarioRecord " + type, fields);
            records.computeIfAbsent(type, k -> new ArrayList<>()).add(fields);
        }

        @Override
        public void stepStarted(StepStart step) {
            add("stepStarted", step);
        }

        @Override
        public void stepEnded(StepResult result) {
            add("stepEnded", result);
        }

        @Override
        public void callStarted(CallStart call) {
            add("callStarted", call);
            open++;
            maxOpen = Math.max(maxOpen, open);
        }

        @Override
        public void callEnded(CallEnd call) {
            add("callEnded", call);
            open--;
        }

        @Override
        public void await(AwaitRecord sync) {
            add("await", sync);
        }

        @Override
        public void modelCall(CostLedger.Entry entry) {
            add("modelCall", entry);
        }

        @Override
        public void sessionStep(StepOutcome outcome) {
            add("sessionStep", outcome);
        }

        @Override
        public void sessionTurn(TurnRecord turn) {
            add("sessionTurn", turn);
        }

        @Override
        public void toolCall(ToolCallRecord record) {
            add("toolCall", record);
        }

        @Override
        public String currentTraceparent() {
            return open > 0 ? "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" : null;
        }

        @Override
        public void flush() {
            add("flush", null);
        }

        @Override
        public void close() {
            add("close", null);
        }

        @Override
        public void sessionStarted(SessionStart session) {
            add("sessionStarted", session);
        }

        @Override
        public void sessionStepStarted(StepStart step) {
            add("sessionStepStarted", step);
        }

        @Override
        public void sessionRecord(String type) {
            add("sessionRecord " + type, null);
        }

        @Override
        public void sessionEnded(String reason) {
            add("sessionEnded", reason);
        }

        @Override
        public String traceId() {
            return "0af7651916cd43dd8448eb211c80319c";
        }

        long count(String call) {
            return calls.stream().filter(call::equals).count();
        }
    }

    private RunResult run(String yaml, BindMode mode, RunObserver observer, String out) throws Exception {
        Plan plan = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withOutputDir(dir.resolve(out)).withBind(TestCredentials.bind().withEnvId("seed-a").withMode(mode)
                        .withApprover(Approver.APPROVE_ALL).withBindingsRoot(dir.resolve("bindings"))
                        .withLedger(ledger))
                .withObserver(observer);
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    /** The toy application of the executor's Phase 2 tests, with one item, so that the binder's scenario passes. */
    private void setUp() {
        toy.install(agent);
        pack = toy.pack();
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.item");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "IDLE"));
            return Map.of("accepted", true);
        });
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("toy.unlock", Map.of());
        agent.value("toy.lock", Map.of());
        agent.value("toy.loan", Map.of("total", "-5.00"));
        toy.items.clear();
        toy.items.add(List.of("B2", "5.00"));
        pack.preflight = List.of(new ai.skillroy.testjava.scenario.pack.PreflightCheck() {
            @Override
            public String id() {
                return "toy.ready";
            }

            @Override
            public String check(ai.skillroy.testjava.controller.AgentConnection a) {
                return null;
            }
        });
        toy.beforeStaffCheck = ctx -> {
            if (ledger.isEmpty()) {
                ledger.record(new CostLedger.Entry("toy-resolve", "assistant", Strategy.LLM_REASON, "test",
                        "model", "r1", 10, 20, "0.01"));
            }
        };
    }

    @Test
    void everyHookPointIsCalledBesideTheRecordItMirrors() throws Exception {
        setUp();
        Recording observer = new Recording();
        RunResult result = run(BinderTest.YAML.replace("steps:", """
                requires: [toy.input]
                steps:"""), BindMode.APPROVE, observer, "live");
        assertEquals(Status.PASS, result.status(), result.message());

        assertEquals(1, observer.count("scenarioStarted"));
        assertEquals("scenarioStarted", observer.calls.get(0), "the start comes first, before run-start");
        assertEquals("scenarioEnded", observer.calls.get(observer.calls.size() - 1), "the end comes last");
        assertEquals(result.steps().size(), observer.count("stepStarted"));
        assertEquals(result.steps().size(), observer.count("stepEnded"));
        assertEquals(observer.count("callStarted"), observer.count("callEnded"));
        assertEquals(1, observer.maxOpen, "calls do not nest");
        assertTrue(observer.count("callStarted") >= 4, "the step calls and the sign-on's cleanup call: "
                + observer.calls);
        assertTrue(observer.count("await") >= 1, "the ACK and READY waits");
        assertEquals(1, observer.count("modelCall"), "the ledger's entry");
        for (String type : List.of("bind:fingerprint", "bind:resolve", "bind:oracle", "bind:bind", "requires",
                "preflight", "cleanup", "expect")) {
            assertTrue(observer.records.containsKey(type), type + " in " + observer.records.keySet());
        }
        assertEquals(1, observer.records.get("bind:bind").size(), "one final bind record");
        assertEquals(1, observer.records.get("requires").size());
        // P2 11.3 as amended: durationMs on resolve, oracle and fingerprint.
        for (String type : List.of("bind:fingerprint", "bind:resolve", "bind:oracle")) {
            for (Map<String, Object> record : observer.records.get(type)) {
                assertTrue(record.get("durationMs") instanceof Long ms && ms >= 0, type + ": " + record);
            }
        }
        // The fields as written: masked, no type and no time.
        for (List<Map<String, Object>> records : observer.records.values()) {
            for (Map<String, Object> record : records) {
                assertFalse(record.containsKey("type") || record.containsKey("t"), String.valueOf(record));
                assertFalse(Json.write(record).contains(TestCredentials.PW_100), "masked: " + record);
            }
        }
        // The calls carry the observer's trace parent; run-start the trace id.
        List<CallOptions> sent = agent.callsTo("toy.input").stream().map(FakeAgentConnection.Call::options).toList();
        assertFalse(sent.isEmpty());
        assertTrue(sent.stream().allMatch(o -> o.traceparent() != null), String.valueOf(sent));
        String start = Files.readAllLines(dir.resolve("live").resolve(RunLog.FILE)).get(0);
        assertEquals("0af7651916cd43dd8448eb211c80319c", Json.parseObject(start).get("traceId"));
        // A step's end carries the masked message; the result is masked.
        StepResult signOn = (StepResult) observer.values.get(observer.calls.indexOf("stepEnded"));
        assertEquals("s1", signOn.id());
    }

    @Test
    void noneCostsNothingAndSendsNoTraceParent() throws Exception {
        setUp();
        assertSame(RunObserver.NONE, RunObservers.safe(RunObserver.NONE));
        assertSame(RunObserver.NONE, RunObservers.safe(null));
        assertSame(RunObserver.NONE, RunOptions.defaults().observer());
        RunResult result = run(BinderTest.YAML, BindMode.APPROVE, RunObserver.NONE, "none");
        assertEquals(Status.PASS, result.status(), result.message());
        assertTrue(agent.callsTo("toy.input").stream().allMatch(c -> c.options().traceparent() == null));
        Map<String, Object> start = Json.parseObject(Files.readAllLines(dir.resolve("none").resolve(RunLog.FILE))
                .get(0));
        assertFalse(start.containsKey("traceId"), "no telemetry, no trace id: " + start);
    }

    @Test
    void anObserverThatThrowsChangesNoVerdict() throws Exception {
        setUp();
        List<String> seen = new ArrayList<>();
        RunObserver throwing = new Recording() {
            @Override
            public void scenarioStarted(ScenarioStart scenario) {
                seen.add("started");
                throw new IllegalStateException("telemetry broke");
            }

            @Override
            public void stepStarted(StepStart step) {
                seen.add("step");
            }
        };
        RunResult result = run(BinderTest.YAML, BindMode.APPROVE, throwing, "throwing");
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of("started"), seen, "replaced by NONE for the rest of the run after the first failure");
    }

    @Test
    void theOlderConstructorsKeepTheirMeaning() {
        RunOptions phase1 = new RunOptions(null, null, null, null, null, null, null, true);
        RunOptions phase5 = new RunOptions(null, null, null, null, null, null, null, true, BindOptions.defaults(),
                true);
        assertSame(RunObserver.NONE, phase1.observer());
        assertSame(RunObserver.NONE, phase5.observer());
        Recording observer = new Recording();
        RunOptions observed = phase5.withObserver(observer);
        assertSame(observer, observed.observer());
        assertSame(observer, observed.withOutputDir(dir).withStepTimeout(Duration.ofSeconds(3)).withClock(clock)
                .withBind(BindOptions.defaults()).withExpectationsOnMaskedValues(false).withCaptureOnFailure(false)
                .observer(), "every copy keeps it");
        assertSame(RunObserver.NONE, observed.withObserver(null).observer());
        SessionOptions session = new SessionOptions(null, null, null, null, false, false, null, null, null, null);
        assertSame(RunObserver.NONE, session.observer());
        assertSame(observer, session.withObserver(observer).withReadOnly(true).withId("s-1").observer());
    }

    @Test
    void aSessionReportsItsStartStepsRecordsAndClose(@TempDir Path sessionDir) throws Exception {
        SessionFixture fixture = new SessionFixture(sessionDir);
        Recording observer = new Recording();
        try (ExecutorSession session = ExecutorSession.open(fixture.pack, fixture.agent, fixture.options()
                .withObserver(observer))) {
            StepOutcome first = session.execute("enter", Map.of("value", "B2"), Origin.person("tester"));
            assertEquals(Status.PASS, first.status(), first.message());
            StepOutcome refused = session.execute("noSuchVerb", Map.of(), Origin.model("m", "why", "r1"));
            assertEquals(Status.ERROR, refused.status());
            session.note("a note", Origin.person("tester"));
            assertNotNull(session.observer());
        }
        List<String> calls = observer.calls;
        assertEquals("sessionStarted", calls.get(0));
        assertEquals("sessionRecord session-start", calls.get(1));
        assertEquals(2, observer.count("sessionStepStarted"));
        assertEquals(2, observer.count("sessionStep"));
        assertTrue(calls.indexOf("callStarted") > calls.indexOf("sessionStepStarted"));
        Set<String> origins = new LinkedHashSet<>();
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).equals("sessionStepStarted")) {
                origins.add(((StepStart) observer.values.get(i)).origin());
            }
        }
        assertEquals(Set.of("PERSON", "MODEL"), origins);
        assertTrue(calls.contains("sessionRecord step") && calls.contains("sessionRecord note")
                && calls.contains("sessionRecord session-end"), String.valueOf(calls));
        assertEquals("sessionEnded", calls.get(calls.size() - 1));
        assertNull(observer.values.get(calls.indexOf("sessionRecord step")), "a record's type only, no content");
    }

    @Test
    void theJunitReportNamesTheTraceOnce() {
        RunResult pass = new RunResult("a", Status.PASS, null, null, null, Map.of(), List.of(), List.of(),
                Duration.ofMillis(5));
        String plain = new JunitXmlReporter().render("suite", List.of(pass), null);
        assertFalse(plain.contains("traceId"), "no trace id without telemetry");
        String traced = new JunitXmlReporter().withTraceId("0af7651916cd43dd8448eb211c80319c").render("suite",
                List.of(pass), null);
        assertTrue(traced.contains("  <system-out>traceId=0af7651916cd43dd8448eb211c80319c</system-out>\n"
                + "</testsuite>"), traced);
        assertEquals(1, traced.split("traceId=", -1).length - 1);
        assertFalse(new JunitXmlReporter().withTraceId("not-a-trace-id").render("suite", List.of(pass), null)
                .contains("traceId"), "only 32 lower-case hex digits");
    }
}
