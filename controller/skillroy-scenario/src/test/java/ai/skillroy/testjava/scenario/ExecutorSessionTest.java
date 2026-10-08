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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 acceptance 15.1 item 13, the session part (phase5-contracts.md 12.2): {@link ExecutorSession} against the
 * executor tests' fake agent and the toy pack. A verb step and a raw call through the shared step protocol, a
 * read-only session refusing a mutating call before it is sent, a model's {@code confirm} stripped, the prompt policy
 * ending a step and not the session, {@code resolve} joining the bindings, {@code check} and {@code observe}, the
 * journal's records masked, correlation ids, the agent lost, {@code recoverOnClose}; and the plan run's confirmation
 * through the same runner.
 */
class ExecutorSessionTest {

    @TempDir
    Path dir;

    private SessionFixture fx;

    @BeforeEach
    void fixture() {
        fx = new SessionFixture(dir);
    }

    private static final Origin PERSON = Origin.person("tester");
    private static final Origin MODEL = Origin.model("a-model", "the next action", "req-1");

    private List<String> journalTypes() throws Exception {
        List<String> types = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve(StepJournal.FILE), StandardCharsets.UTF_8)) {
            types.add(String.valueOf(Json.parseObject(line).get("type")));
        }
        return types;
    }

    private String journalText() throws Exception {
        return Files.readString(dir.resolve(StepJournal.FILE), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------ steps

    @Test
    void aVerbStepRunsTheStepProtocolWithTheSessionsBindings() throws Exception {
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            StepOutcome outcome = session.execute("signOn", Map.of("staff", "$assistant"), PERSON);
            assertEquals(Status.PASS, outcome.status(), outcome.message());
            assertEquals("s1", outcome.stepId());
            assertEquals("signOn", outcome.verb());
            assertEquals(2, outcome.executed());
            assertEquals(List.of("toy.input", "toy.input"), outcome.calls().stream().map(PlannedCall::primitive)
                    .toList());
            List<FakeAgentConnection.Call> inputs = fx.agent.callsTo("toy.input");
            assertEquals("100", inputs.get(0).params().get("value"), "the bound code");
            assertEquals(TestCredentials.PW_100, inputs.get(1).params().get("value"), "the joined password is sent");
            assertEquals(List.of("value"), inputs.get(1).options().secretParams(), "and announced (A34)");
            assertEquals("sess-1/s1/1", inputs.get(0).options().correlationId());
            assertEquals("sess-1/s1/2", inputs.get(1).options().correlationId());
            assertFalse(fx.agent.awaits.isEmpty(), "READY waited on the pack's topics");
            assertTrue(outcome.seqAfter() > outcome.seqBefore());
            assertFalse(outcome.events().isEmpty(), "the events the calls published");
            assertNotNull(outcome.lastResult());
            assertEquals(Map.of(), outcome.detail());
            assertFalse(journalText().contains(TestCredentials.PW_100), "the journal never holds the password");
        }
    }

    @Test
    void aRawCallIsOneStepWithItsOwnSyncAndTimeout() throws Exception {
        try (ExecutorSession session = fx.open()) {
            StepOutcome outcome = session.execute(new PlannedCall("toy.poke", Map.of("n", 1), Sync.SETTLE, 3000),
                    PERSON);
            assertEquals(Status.PASS, outcome.status(), outcome.message());
            assertNull(outcome.verb(), "a raw call has no verb");
            assertEquals(1, outcome.executed());
            assertEquals(3000L, fx.agent.callsTo("toy.poke").get(0).options().timeoutMs(), "its own timeout");
            assertEquals(1, fx.agent.callsTo("swing.settle").size(), "its own sync");
            StepOutcome bad = session.execute(PlannedCall.of("toy.read", Map.of("who", "$nobody.code"), Sync.NONE),
                    PERSON);
            assertEquals(Status.ERROR, bad.status());
            assertEquals(FailureKind.RESOLUTION_FAILED, bad.failureKind(), bad.message());
            assertEquals(0, bad.executed(), "a reference that does not resolve stops the call before it is sent");
            assertTrue(fx.agent.callsTo("toy.read").isEmpty());
        }
    }

    @Test
    void aStepThatDoesNotCompileIsAnOutcomeNotAnException() throws Exception {
        try (ExecutorSession session = fx.open()) {
            StepOutcome unknown = session.execute("teleport", Map.of(), PERSON);
            assertEquals(Status.ERROR, unknown.status());
            assertEquals(FailureKind.ENVIRONMENT, unknown.failureKind());
            assertTrue(unknown.message().contains("unknown verb 'teleport'"), unknown.message());
            assertEquals(0, unknown.executed());
            StepOutcome ok = session.execute("poke", null, PERSON);
            assertEquals(Status.PASS, ok.status(), "the session goes on");
            assertEquals("s2", ok.stepId());
        }
    }

    @Test
    void aReadOnlySessionRefusesAMutatingCallBeforeItIsSent() throws Exception {
        fx.agent.describe("toy.input", true).describe("toy.read", false);
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withReadOnly(true))) {
            StepOutcome refused = session.execute("enter", Map.of("value", "B2"), PERSON);
            assertEquals(Status.ERROR, refused.status());
            assertTrue(refused.message().contains("READ_ONLY"), refused.message());
            assertEquals("READ_ONLY", refused.detail().get("error"));
            assertEquals(0, refused.executed());
            assertTrue(fx.agent.callsTo("toy.input").isEmpty(), "nothing was sent");
            StepOutcome unknown = session.execute(PlannedCall.of("toy.poke", Map.of(), Sync.NONE), PERSON);
            assertTrue(unknown.message().contains("is not in the target's catalogue"), unknown.message());
            StepOutcome read = session.execute(PlannedCall.of("toy.read", Map.of(), Sync.NONE), PERSON);
            assertEquals(Status.PASS, read.status(), "a read runs");
            Map<String, Object> step = Json.parseObject(Files.readAllLines(dir.resolve(StepJournal.FILE)).get(1));
            assertEquals(0L, ((Map<?, ?>) step.get("outcome")).get("executed"), "journaled as not executed");
        }
    }

    @Test
    void onlyAPersonConfirmsADestructiveCall() throws Exception {
        try (ExecutorSession session = fx.open()) {
            StepOutcome person = session.execute(PlannedCall.of("toy.wipe", Map.of("confirm", true, "scope", "all"),
                    Sync.NONE), PERSON);
            assertEquals(Status.PASS, person.status(), person.message());
            FakeAgentConnection.Call sent = fx.agent.callsTo("toy.wipe").get(0);
            assertTrue(sent.options().confirm(), "confirm in the arguments is the call's confirmation");
            assertEquals(Map.of("scope", "all"), sent.params(), "and is not sent as a parameter");
            StepOutcome model = session.execute(PlannedCall.of("toy.wipe", Map.of("confirm", true), Sync.NONE),
                    MODEL);
            assertEquals(Status.ERROR, model.status());
            assertTrue(model.message().contains("CONFIRM_REQUIRED"), model.message());
            assertFalse(fx.agent.callsTo("toy.wipe").get(1).options().confirm(), "a model's confirm is stripped");
            StepOutcome verb = session.execute("call", Map.of("primitive", "toy.wipe", "args", Map.of("confirm",
                    true)), MODEL);
            assertTrue(verb.message().contains("CONFIRM_REQUIRED"), "through a verb as well: " + verb.message());
            Map<String, Object> journaled = Json.parseObject(Files.readAllLines(dir.resolve(StepJournal.FILE))
                    .get(3));
            assertEquals(Map.of("primitive", "toy.wipe", "args", Map.of()), journaled.get("args"),
                    "the journal (and so the export) holds no model's confirmation");
            assertEquals("MODEL", ((Map<?, ?>) journaled.get("origin")).get("kind"));
        }
    }

    @Test
    void thePromptPolicyEndsTheStepNotTheSession() throws Exception {
        try (ExecutorSession session = fx.open()) {
            StepOutcome prompted = session.execute("enter", Map.of("value", "UNKNOWN"), PERSON);
            assertEquals(Status.FAIL, prompted.status());
            assertEquals(FailureKind.ASSERTION, prompted.failureKind());
            assertNotNull(prompted.detail().get("prompt"), "the dialog is in the detail");
            StepOutcome answered = session.execute("answer", Map.of("choice", "OK"), PERSON);
            assertEquals(Status.PASS, answered.status(), "the session goes on: " + answered.message());
            StepOutcome next = session.execute("enter", Map.of("value", "B2"), PERSON);
            assertEquals(Status.PASS, next.status(), "the old prompt is not seen again: " + next.message());
        }
    }

    // ------------------------------------------------------------------------------------------------ resolution

    @Test
    void resolveJoinsTheSessionsBindingsAndAnOracleTheOracles() throws Exception {
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            Resolution assistant = session.bindings().resolutions().get("assistant");
            assertEquals(Strategy.CANONICAL_SQL, assistant.strategy());
            assertEquals("100", ((Map<?, ?>) session.bindings().values().get("assistant")).get("code"));
            assertEquals(Map.of("code", "B2", "deposit", "5.00"), session.bindings().values().get("item"));
            assertEquals("staff", assistant.credential().realm());
            assertEquals(Map.of("limitBelow", "5.00"), assistant.args(), "resolved with the item's deposit");
            Object negated = session.evaluate("neg", "Negate", List.of("$item.deposit"), PERSON);
            assertEquals("-5.00", negated);
            assertEquals("-5.00", session.bindings().oracle("neg"));
            StepOutcome entered = session.execute("enter", Map.of("value", "$oracle.neg"), PERSON);
            assertEquals("-5.00", fx.agent.callsTo("toy.input").get(0).params().get("value"));
            assertEquals(Status.PASS, entered.status());
            BindException missing = assertThrows(BindException.class, () -> session.resolve("x", "Staff",
                    Map.of("limitBelow", "$ghost.limit"), List.of(), List.of(), null, PERSON));
            assertEquals(FailureKind.RESOLUTION_FAILED, missing.kind());
            assertThrows(IllegalArgumentException.class, () -> session.resolve("env", "Item", Map.of(), List.of(),
                    List.of(), null, PERSON), "a reserved slot name");
        }
    }

    // ------------------------------------------------------------------------------------------------ reads

    @Test
    void checkAndObserveReadTheFactsOnceAndAreJournaled() throws Exception {
        try (ExecutorSession session = fx.open()) {
            CheckOutcome held = session.check("state.name", "IDLE", PERSON);
            assertTrue(held.held(), held.reason());
            assertEquals("IDLE", held.actual());
            CheckOutcome failed = session.check("loan.total", Map.of("gte", 8), PERSON);
            assertFalse(failed.held());
            assertTrue(failed.reason().contains("expected a value >= 8"), failed.reason());
            CheckOutcome invalid = session.check("loan.total", Map.of("nearly", 7), PERSON);
            assertFalse(invalid.held());
            assertTrue(invalid.reason().contains("unknown matcher"), invalid.reason());
            CheckOutcome db = session.check("db.loan", Map.of("where", Map.of()), PERSON);
            assertTrue(db.reason().contains("stored-row checks are not evaluated in a session"), db.reason());
            assertEquals("7.00", session.observe("loan.total", PERSON));
            assertEquals(Map.of("code", "B2", "qty", 2L), session.observe("loan.lines[code=B2]", PERSON));
            assertThrows(IllegalArgumentException.class, () -> session.observe("loan.nothing", PERSON));
            assertEquals(2L, session.fact("loan", "lines[0].qty"));
            assertNull(session.fact("loan", "missing"));
            assertTrue(session.snapshotText(4000).contains("DUE"));
            session.note("looked at the loan", PERSON);
            assertEquals(List.of("session-start", "check", "check", "check", "check", "observe", "observe", "note"),
                    journalTypes(), "a failed observe and the reads without an origin are not journaled");
            assertEquals(0, fx.clock.sleeps, "a session never sleeps");
        }
    }

    @Test
    void eventsSinceReadsTheAgentsEventsMasked() throws Exception {
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            long before = fx.agent.lastSeq();
            fx.agent.publish("toy.note", Map.of("text", "typed " + TestCredentials.PW_100));
            List<Event> events = session.eventsSince(before, "toy.*", 10);
            assertEquals(1, events.size());
            assertEquals("typed <redacted>", events.get(0).payload().get("text"), "the joined secret is masked");
            assertEquals(List.of(), session.eventsSince(before, "toy.*", 0));
        }
    }

    // ------------------------------------------------------------------------------------------------ journal

    @Test
    void theJournalHoldsEveryRecordInOrderMasked() throws Exception {
        ExecutorSession session = fx.open();
        SessionFixture.resolveItemAndAssistant(session, PERSON);
        session.execute("signOn", Map.of("staff", "$assistant"), PERSON);
        session.execute(PlannedCall.of("toy.input", Map.of("source", "Entry", "value", "$assistant.password"),
                Sync.READY), MODEL);
        session.check("state.name", "LOAN", PERSON);
        session.observe("loan.total", PERSON);
        session.note("done", Origin.system("test"));
        session.budget("SESSION", new BigDecimal("0.0100"), new BigDecimal("1.00"), false);
        session.close();
        session.close();
        assertEquals(List.of("session-start", "resolve", "resolve", "step", "step", "check", "observe", "note",
                "budget", "session-end"), journalTypes(), "closing twice writes one end");
        String text = journalText();
        assertFalse(text.contains(TestCredentials.PW_100), text);
        List<StepJournal.Entry> read = StepJournal.read(dir.resolve(StepJournal.FILE), fx.pack).entries();
        assertEquals(session.journal().entries(), read, "the file reads back to the entries kept");
        StepJournal.SessionStart start = (StepJournal.SessionStart) read.get(0);
        assertEquals("sess-1", start.id());
        assertEquals("toy", start.pack());
        assertEquals("seed-a", start.envId());
        assertEquals(10_000L, start.options().get("stepTimeoutMs"));
        assertTrue(String.valueOf(start.env().get("fingerprint")).startsWith("sha256:"));
        StepJournal.StepEntry raw = (StepJournal.StepEntry) read.get(4);
        assertNull(raw.verb());
        assertEquals(Map.of("source", "Entry", "value", "$assistant.password"), raw.args(), "as given");
        assertEquals("<redacted>", ((Map<?, ?>) raw.calls().get(0).get("args")).get("value"), "as sent, masked");
        assertEquals(Origin.Kind.MODEL, raw.origin().kind());
        assertEquals("a-model", raw.origin().by());
        assertNotNull(raw.snapshotHash(), "a model's passed step records the snapshot it left");
        assertNull(((StepJournal.StepEntry) read.get(3)).snapshotHash(), "a person's does not");
        StepJournal.ResolveEntry assistant = (StepJournal.ResolveEntry) read.get(2);
        assertEquals(Map.of("limitBelow", "$item.deposit"), assistant.args(), "the arguments as given");
        assertEquals("CANONICAL_SQL", assistant.resolution().get("strategy"));
        assertEquals(Map.of("code", "100", "limit", "2.00"), assistant.toResolution().value());
        StepJournal.SessionEnd end = (StepJournal.SessionEnd) read.get(read.size() - 1);
        assertEquals("closed", end.reason());
        assertEquals(2, end.steps());
        assertEquals("0", end.cost(), "no model call");
        assertThrows(IllegalStateException.class, () -> session.note("late", PERSON));
    }

    @Test
    void aLostAgentEndsTheStepWithAnExceptionAndTheSessionAsLost() throws Exception {
        ExecutorSession session = fx.open();
        fx.agent.loseOn("toy.poke");
        assertThrows(AgentLostException.class, () -> session.execute("poke", null, PERSON));
        session.close();
        List<StepJournal.Entry> entries = session.journal().entries();
        StepJournal.StepEntry step = (StepJournal.StepEntry) entries.get(1);
        assertEquals("AGENT_LOST", step.outcome().get("kind"));
        assertEquals("agent lost", ((StepJournal.SessionEnd) entries.get(2)).reason());
        assertEquals(0, fx.pack.recovered.get(), "no recover after a loss");
    }

    @Test
    void recoverOnCloseRunsAfterAMutatingCallOnly() throws Exception {
        fx.agent.describe("toy.poke", true).describe("toy.read", false);
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options()
                .withRecoverOnClose(true))) {
            session.execute(PlannedCall.of("toy.read", Map.of(), Sync.NONE), PERSON);
        }
        assertEquals(0, fx.pack.recovered.get(), "a read changes nothing to recover from");
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options()
                .withRecoverOnClose(true))) {
            session.execute("poke", null, PERSON);
        }
        assertEquals(1, fx.pack.recovered.get());
        try (ExecutorSession session = fx.open()) {
            session.execute("poke", null, PERSON);
        }
        assertEquals(1, fx.pack.recovered.get(), "not without recoverOnClose");
    }

    // ------------------------------------------------------------------------------------------------ open

    @Test
    void openRunsThePreflightAndRefusesAPinnedPathItCannotUse() throws Exception {
        fx.pack.preflight = List.of(new PreflightCheck() {
            @Override
            public String id() {
                return "desk.open";
            }

            @Override
            public String check(ai.skillroy.testjava.controller.AgentConnection agent) {
                return "the desk is closed";
            }
        });
        SessionOpenException closed = assertThrows(SessionOpenException.class, fx::open);
        assertEquals(FailureKind.ENVIRONMENT, closed.kind());
        assertEquals("pre-flight check desk.open: the desk is closed", closed.getMessage());
        fx.pack.preflight = List.of();
        WebSettings cdp = new WebSettings(WebSettings.Path.CDP, null, FrozenTargetPolicy.FAIL, Duration.ofSeconds(5),
                true);
        SessionOpenException pinned = assertThrows(SessionOpenException.class, () -> ExecutorSession.open(fx.pack,
                fx.agent, fx.options().withWeb(cdp).withWebPathLoader(new ClassLoader(null) {
                })));
        assertEquals(FailureKind.PRECONDITION_FAILED, pinned.kind(), pinned.getMessage());
        assertFalse(Files.exists(dir.resolve(StepJournal.FILE)), "nothing was journaled");
        assertThrows(IllegalArgumentException.class, () -> fx.options().withId("Not An Id"));
        assertEquals(Duration.ofSeconds(10), SessionOptions.defaults(null).stepTimeout());
        assertEquals(ai.skillroy.testjava.scenario.resolve.BindMode.LIVE, SessionOptions.defaults(null).bind()
                .mode(), "a session resolves live");
    }

    // ------------------------------------------------------------------------------------------------ plan runs

    /**
     * The runner both share sends {@code confirm: true} in a call's arguments as the confirmation, in a plan run too,
     * for a step call and a cleanup call (phase5-contracts.md 13.1).
     */
    @Test
    void aPlanRunConfirmsADestructiveCallFromItsArguments() throws Exception {
        fx.pack.custom = List.of(PlannedCall.of("toy.read", Map.of(), Sync.NONE));
        fx.pack.onCustom = ctx -> ctx.addCleanup(PlannedCall.of("toy.wipe", Map.of("confirm", true), Sync.NONE));
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n"
                + "  - call: { primitive: toy.wipe, args: { confirm: true, scope: all } }\n"
                + "  - custom: {}\n").getBytes(StandardCharsets.UTF_8), "t.yaml", fx.pack);
        RunResult result = new PlanExecutor(fx.pack, fx.agent, RunOptions.defaults().withClock(fx.clock)
                .withSleeper(fx.clock.sleeper()).withBind(TestCredentials.bind())).run(plan);
        assertEquals(Status.PASS, result.status(), result.message());
        List<FakeAgentConnection.Call> wipes = fx.agent.callsTo("toy.wipe");
        assertEquals(2, wipes.size());
        assertTrue(wipes.get(0).options().confirm());
        assertEquals(Map.of("scope", "all"), wipes.get(0).params());
        assertTrue(wipes.get(1).options().confirm(), "the cleanup call too");
        assertEquals(Map.of(), wipes.get(1).params());
        Plan unconfirmed = PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n"
                + "  - call: { primitive: toy.wipe, args: { confirm: false } }\n").getBytes(StandardCharsets.UTF_8),
                "t.yaml", fx.pack);
        RunResult refused = new PlanExecutor(fx.pack, fx.agent, RunOptions.defaults().withClock(fx.clock)
                .withSleeper(fx.clock.sleeper()).withBind(TestCredentials.bind())).run(unconfirmed);
        assertEquals(Status.ERROR, refused.status());
        assertTrue(refused.message().contains("CONFIRM_REQUIRED"), refused.message());
    }
}
