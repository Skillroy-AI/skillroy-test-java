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

package ai.skillroy.testjava.inference.session;

import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.fakes.FakeAgent;
import ai.skillroy.testjava.inference.fakes.MutableClock;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 14: the session service with a scripted gateway on a fake desk: the tools by policy,
 * one action at a time through the session (journaled with the model as origin), a failed step returned as a result,
 * {@code done}, {@code ask_person}, the action limit, the budget ending the turn and the session's model calls, the
 * history's summary at the limit, the agent lost, a provider's opaque parts echoed back, a provider failure, and the
 * ledger.
 */
class SessionServiceTest {

    @TempDir
    Path tmp;

    private final SessionDesk pack = new SessionDesk();
    private FakeAgent agent;
    private ExecutorSession session;
    private CostLedger ledger;
    private InferenceLog.Memory log;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));

    @BeforeEach
    void open() throws Exception {
        agent = SessionDesk.agent();
        ledger = new CostLedger();
        log = InferenceLog.inMemory();
        session = ExecutorSession.open(pack, agent, SessionOptions.defaults(tmp.resolve("session")).withClock(clock)
                .withBind(BindOptions.defaults().withEnvId("test-env").withLedger(ledger)).withId("sess-1"));
    }

    @AfterEach
    void close() {
        session.close();
    }

    private SessionService service(ScriptedGateway gateway, SessionPolicy policy, String budget) {
        BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, budget, ledger);
        LedgerWriter writer = new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock);
        return new SessionService(session, gateway, policy, meter, writer, PriceTable.builtIn(), clock);
    }

    private static Usage usage(String requestId, String cost) {
        return new Usage("scripted", "claude-sonnet-5-5", "claude-sonnet-5-5", requestId, 300, 40, 0, 0, 0, false,
                cost, Duration.ZERO, null);
    }

    private static ModelResponse tools(String requestId, Content... parts) {
        return new ModelResponse(List.of(parts), ModelResponse.StopReason.TOOL_USE, usage(requestId, "0.0010"),
                null);
    }

    private static ModelResponse costly(String requestId, String cost, Content... parts) {
        return new ModelResponse(List.of(parts), ModelResponse.StopReason.TOOL_USE, usage(requestId, cost), null);
    }

    private static Content.ToolUse use(String id, String name, Map<String, Object> input) {
        return new Content.ToolUse(id, name, input);
    }

    private static ModelResponse done(String requestId, String answer) {
        return tools(requestId, use("done-" + requestId, "done", Map.of("answer", answer)));
    }

    private static List<String> names(List<ToolSpec> tools) {
        return tools.stream().map(ToolSpec::name).toList();
    }

    private List<StepJournal.StepEntry> steps() {
        return session.journal().entries().stream().filter(e -> e instanceof StepJournal.StepEntry)
                .map(e -> (StepJournal.StepEntry) e).toList();
    }

    // ------------------------------------------------------------------------------------------------ the tools

    @Test
    void theToolsAreThePolicys() {
        List<String> acting = names(service(ScriptedGateway.sequence(List.of()), SessionPolicy.acting(), "1.00")
                .tools());
        assertEquals(List.of("skillroy_snapshot", "skillroy_facts", "skillroy_events", "skillroy_catalogue",
                "skillroy_resolve", "skillroy_check", "skillroy_observe", "skillroy_note", "lending_lend",
                "lending_sign_on", "lending_take_back", "done", "ask_person"), acting, "the verbs, no primitive");
        List<String> reading = names(service(ScriptedGateway.sequence(List.of()), SessionPolicy.readOnlyPolicy(),
                "1.00").tools());
        assertFalse(reading.stream().anyMatch(n -> n.startsWith("lending_")), reading.toString());
        assertTrue(reading.contains("skillroy_snapshot") && reading.contains("done"));
        List<String> base = names(service(ScriptedGateway.sequence(List.of()), SessionPolicy.acting()
                .withBasePrimitives(true), "1.00").tools());
        assertTrue(base.contains("swing_click"), "the base mutating primitives by policy: " + base);
        for (List<String> set : List.of(acting, reading, base)) {
            assertFalse(set.contains("desk_wipe_ledger"), "never a destructive primitive");
            assertFalse(set.contains("data_query"), "never data_query");
            assertFalse(set.contains("swing_snapshot"), "the workflow reads stand for the primitive reads");
        }
        assertFalse(names(service(ScriptedGateway.sequence(List.of()), new SessionPolicy(true, true, true, 10, 20,
                20_000), "1.00").tools()).contains("swing_click"), "read-only wins over the other flags");
    }

    // ------------------------------------------------------------------------------------------------ the loop

    @Test
    void theModelActsOneActionAtATimeThroughTheSessionAndEndsWithDone() throws Exception {
        Content.Opaque thinking = new Content.Opaque("claude", "thinking", Map.of("thinking", "plan the loan",
                "signature", "sig-1"));
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                tools("r1", thinking, Content.Text.of("I sign on first."), use("t1", "lending_sign_on",
                        Map.of("staff", "S01")), Content.Text.of("Then I lend the item."), use("t2", "lending_lend",
                        mapOf("item", "GAR-02", "qty", null))),
                tools("r2", use("t3", "skillroy_facts", mapOf("root", "loan", "path", null))),
                done("r3", "The aerator is lent to the walk-in borrower.")));
        SessionService service = service(gateway, SessionPolicy.acting(), "1.00");
        Turn turn = service.ask("lend one plain item to the walk-in borrower");

        assertEquals(Turn.Outcome.DONE, turn.outcome());
        assertEquals("The aerator is lent to the walk-in borrower.", turn.answer());
        assertEquals(1, turn.index());
        assertEquals(List.of("lending_sign_on", "lending_lend", "skillroy_facts"), turn.actions().stream()
                .map(Turn.Action::tool).toList());
        assertEquals(Status.PASS, turn.actions().get(1).step().status());
        assertNull(turn.actions().get(2).step(), "a read has no step");
        assertTrue(turn.actions().get(2).result().contains("GAR-02"), turn.actions().get(2).result());
        assertTrue(turn.actions().get(2).result().startsWith("<target-state>"), "target data in its block");
        assertEquals(List.of("desk.signOn", "desk.lend", "desk.loan"), agent.stepMethods().stream()
                .filter(m -> m.startsWith("desk.")).toList(), "in order, through the session");
        Map<String, Object> lent = agent.callsTo("desk.lend").get(0).params();
        assertEquals("GAR-02", lent.get("item"));
        assertFalse(lent.containsKey("qty"), "a strict tool's null (not given) is left out: " + lent);

        List<ModelRequest> requests = gateway.requests();
        assertEquals(3, requests.size());
        ModelRequest first = requests.get(0);
        assertEquals("session", first.purpose());
        assertEquals(ModelClass.SONNET_CLASS, first.modelClass());
        assertEquals(CacheHint.SHORT, first.cache());
        assertTrue(((Content.Text) first.system().get(1)).cacheBreakpoint(), "the system part is cached");
        assertTrue(((Content.Text) first.system().get(1)).text().contains("lend (tool lending_lend)"));
        String person = ((Content.Text) first.messages().get(0).parts().get(0)).text();
        assertEquals("<person>\nlend one plain item to the walk-in borrower\n</person>", person);
        assertEquals("sess-1", first.metadata().get(SessionService.META_SESSION));

        ModelRequest second = requests.get(1);
        Message echoed = second.messages().get(1);
        assertEquals(Message.Role.ASSISTANT, echoed.role());
        assertSame(thinking.getClass(), echoed.parts().get(0).getClass());
        assertEquals(thinking, echoed.parts().get(0), "the opaque thinking block echoed back unchanged");
        Message results = second.messages().get(2);
        assertEquals(Message.Role.USER, results.role());
        assertEquals(List.of("t1", "t2"), results.parts().stream().map(p -> ((Content.ToolResult) p).toolUseId())
                .toList(), "one result per call, in order");
        String lendResult = String.valueOf(((Content.ToolResult) results.parts().get(1)).content());
        assertTrue(lendResult.contains("\"status\": \"PASS\""), lendResult);
        assertTrue(lendResult.contains("loan.line"), "the events' topics: " + lendResult);
        assertTrue(lendResult.contains("snapshotHash"), lendResult);

        List<StepJournal.StepEntry> steps = steps();
        assertEquals(2, steps.size(), "the verbs are journaled; the read is not a step");
        Origin origin = steps.get(1).origin();
        assertEquals(Origin.Kind.MODEL, origin.kind());
        assertEquals("claude-sonnet-5-5", origin.by());
        assertEquals("r1", origin.requestId());
        assertEquals("Then I lend the item.", origin.intent(), "the model's text before the call");
        assertEquals("I sign on first.", steps.get(0).origin().intent());

        assertEquals(3, ledger.size(), "every call recorded");
        assertTrue(ledger.entries().stream().allMatch(e -> "session".equals(e.purpose())
                && "sess-1".equals(e.scenarioId())));
        assertEquals(new BigDecimal("0.0030"), turn.totalUsage().cost());
        assertEquals(900, turn.totalUsage().inputTokens());
        assertEquals(3, log.records().size());
        assertTrue(session.journal().entries().stream().anyMatch(e -> e instanceof StepJournal.BudgetEntry b
                && "SESSION".equals(b.scope()) && !b.exhausted()), "the budget state in the journal");
        assertEquals(List.of(turn), service.turns());
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void aFailedStepIsAResultTheModelReactsTo() throws Exception {
        agent.on("desk.lend", params -> {
            throw new ai.skillroy.testjava.controller.AgentRpcException("desk.lend",
                    new ai.skillroy.testjava.protocol.RpcError(-32000, "the item is out on loan", Map.of()));
        });
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                tools("r1", use("t1", "lending_lend", Map.of("item", "GAR-02"))),
                tools("r2", use("t2", "ask_person", Map.of("question", "The aerator is out; lend another item?")))));
        Turn turn = service(gateway, SessionPolicy.acting(), "1.00").ask("lend the aerator");
        assertEquals(Turn.Outcome.QUESTION, turn.outcome());
        assertEquals("The aerator is out; lend another item?", turn.answer());
        Turn.Action action = turn.actions().get(0);
        assertFalse(action.step().succeeded());
        Content.ToolResult result = (Content.ToolResult) gateway.requests().get(1).messages().get(2).parts().get(0);
        assertTrue(result.error(), "a failed step is an error result, not an exception");
        assertTrue(String.valueOf(result.content()).contains("out on loan"), String.valueOf(result.content()));
    }

    @Test
    void theActionLimitEndsTheTurn() throws Exception {
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                tools("r1", use("a", "skillroy_snapshot", mapOf("maxChars", null))),
                tools("r2", use("b", "skillroy_events", mapOf("sinceSeq", null, "topics", null, "max", 5L)),
                        use("c", "skillroy_snapshot", mapOf("maxChars", 500L)))));
        Turn turn = service(gateway, SessionPolicy.acting().withMaxActionsPerTurn(2), "1.00").ask("look around");
        assertEquals(Turn.Outcome.LIMIT, turn.outcome());
        assertEquals(2, turn.actions().size(), "the third call was not run");
        assertTrue(turn.answer().startsWith("The action limit of 2 actions per turn was reached"), turn.answer());
        assertEquals(2, gateway.requests().size(), "no model call after the limit");
        assertTrue(turn.actions().get(0).result().startsWith("<target-ui>"), turn.actions().get(0).result());
        assertTrue(turn.actions().get(1).result().startsWith("<target-event>"));
    }

    @Test
    void theBudgetEndsTheTurnAndTheSessionsModelCalls() throws Exception {
        // The first call's estimate (its text / 4 plus 4096 answer tokens, about $0.042) fits $0.05; after it cost
        // $0.01, the second's does not.
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                costly("r1", "0.0100", use("t1", "lending_sign_on", Map.of("staff", "S01"))),
                done("r2", "never reached")));
        SessionService service = service(gateway, SessionPolicy.acting(), "0.05");
        Turn turn = service.ask("sign on");
        assertEquals(Turn.Outcome.BUDGET, turn.outcome(), "the second call's estimate did not fit");
        assertEquals(1, turn.actions().size(), "the action of the first call was run");
        assertEquals(1, gateway.requests().size());
        assertTrue(service.exhausted());
        Turn next = service.ask("lend something");
        assertEquals(Turn.Outcome.BUDGET, next.outcome());
        assertEquals(1, gateway.requests().size(), "no further model call in this session");
        assertTrue(next.answer().contains("exhausted"), next.answer());
        assertTrue(session.journal().entries().stream().anyMatch(e -> e instanceof StepJournal.BudgetEntry b
                && b.exhausted()), "the journal says the budget is exhausted");
        session.execute("takeBack", Map.of("item", "GAR-02"), Origin.person("desk"));
        assertTrue(agent.stepMethods().contains("desk.takeBack"), "the person may still act");
        Turn none = service(ScriptedGateway.sequence(List.of()), SessionPolicy.acting(), "0").ask("anything");
        assertEquals(Turn.Outcome.BUDGET, none.outcome(), "a limit of 0 is no model call at all");
    }

    @Test
    void olderTurnsAreSummarisedAtTheHistoryLimit() throws Exception {
        List<ModelResponse> answers = new ArrayList<>(List.of(done("r1", "first done"), done("r2", "second done"),
                ScriptedGateway.text("The person asked for a first thing; it was done."), done("r3",
                        "third done")));
        ScriptedGateway gateway = ScriptedGateway.sequence(answers);
        SessionService service = service(gateway, SessionPolicy.acting().withMaxHistoryTurns(1), "1.00");
        service.ask("first request");
        service.ask("second request");
        Turn third = service.ask("third request");
        assertEquals(Turn.Outcome.DONE, third.outcome());
        List<ModelRequest> requests = gateway.requests();
        assertEquals(4, requests.size());
        ModelRequest summary = requests.get(2);
        assertEquals(SessionService.PURPOSE_SUMMARY, summary.purpose());
        assertEquals(ModelClass.HAIKU_CLASS, summary.modelClass());
        String folded = ((Content.Text) summary.messages().get(0).parts().get(0)).text();
        assertTrue(folded.contains("first request") && !folded.contains("second request"), folded);
        String last = Json.write(Map.of("m", ai.skillroy.testjava.inference.spi.Prompts.fingerprintJson(
                requests.get(3))));
        assertTrue(last.contains("The person asked for a first thing"), "the summary is in the request");
        assertTrue(last.contains("second request"), "the last turn in full");
        assertFalse(last.contains("first request"), "the folded turn is not");
        assertTrue(ledger.entries().stream().anyMatch(e -> "summary".equals(e.purpose())), "recorded like any call");
    }

    @Test
    void aLostAgentEndsTheTurnLost() throws Exception {
        agent.loseOn("desk.lend");
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                tools("r1", use("t1", "lending_lend", Map.of("item", "GAR-02")))));
        Turn turn = service(gateway, SessionPolicy.acting(), "1.00").ask("lend");
        assertEquals(Turn.Outcome.LOST, turn.outcome());
        assertTrue(turn.answer().startsWith("The agent was lost"), turn.answer());
    }

    @Test
    void aProviderFailureOrARefusalHandsTheTurnBackToThePerson() throws Exception {
        ScriptedGateway down = ScriptedGateway.failing(new InferenceException(InferenceException.Kind.NO_RECORDING,
                "no recording for fingerprint abc"));
        Turn failed = service(down, SessionPolicy.acting(), "1.00").ask("lend");
        assertEquals(Turn.Outcome.QUESTION, failed.outcome());
        assertTrue(failed.answer().contains("could not be reached (NO_RECORDING"), failed.answer());
        Turn refused = service(ScriptedGateway.sequence(List.of(ScriptedGateway.refusal("cyber"))),
                SessionPolicy.acting(), "1.00").ask("lend");
        assertEquals(Turn.Outcome.QUESTION, refused.outcome());
        assertTrue(refused.answer().contains("declined"), refused.answer());
        Turn plain = service(ScriptedGateway.sequence(List.of(ScriptedGateway.text("Nothing to do."))),
                SessionPolicy.acting(), "1.00").ask("anything?");
        assertEquals(Turn.Outcome.DONE, plain.outcome(), "an answer in text without a tool call");
        assertEquals("Nothing to do.", plain.answer());
    }

    @Test
    void readsResolveCheckObserveAndNoteGoThroughTheSession() throws Exception {
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                tools("r1", use("a", "skillroy_resolve", mapOf("slot", "item", "kind", "PlainItem", "args",
                        Map.of("category", "GARDEN"), "strategy", null))),
                tools("r2", use("b", "skillroy_check", Map.of("path", "loan.lines", "matcher", Map.of("count", 0L))),
                        use("c", "skillroy_observe", Map.of("path", "loan.lines")),
                        use("d", "skillroy_note", Map.of("text", "checked the empty loan")),
                        use("e", "skillroy_catalogue", Map.of()), use("f", "no_such_tool", Map.of())),
                done("r3", "resolved and checked")));
        Turn turn = service(gateway, SessionPolicy.acting(), "1.00").ask("find a garden item");
        assertEquals(Turn.Outcome.DONE, turn.outcome());
        assertTrue(turn.actions().get(0).result().contains("GAR-02"), turn.actions().get(0).result());
        assertEquals("GAR-02", ((Map<?, ?>) session.bindings().values().get("item")).get("code"),
                "the resolution joined the session's bindings");
        assertTrue(turn.actions().get(1).result().contains("\"held\""), turn.actions().get(1).result());
        assertTrue(turn.actions().get(4).result().contains("PlainItem"), "the catalogue names the query kinds");
        assertTrue(turn.actions().get(5).result().contains("no tool no_such_tool"));
        List<Class<?>> kinds = session.journal().entries().stream().<Class<?>>map(Object::getClass).toList();
        assertTrue(kinds.contains(StepJournal.ResolveEntry.class) && kinds.contains(StepJournal.CheckEntry.class)
                && kinds.contains(StepJournal.ObserveEntry.class) && kinds.contains(StepJournal.NoteEntry.class),
                kinds.toString());
        StepJournal.NoteEntry note = session.journal().entries().stream().filter(e -> e instanceof
                StepJournal.NoteEntry).map(e -> (StepJournal.NoteEntry) e).findFirst().orElseThrow();
        assertEquals(Origin.Kind.MODEL, note.origin().kind());
        assertTrue(Files.readString(session.journal().file()).contains("checked the empty loan"));
    }

    @Test
    void aClosedServiceRefusesATurnAndClosesNothingOfTheSession() throws Exception {
        SessionService service = service(ScriptedGateway.sequence(List.of()), SessionPolicy.acting(), "1.00");
        service.close();
        assertThrows(IllegalStateException.class, () -> service.ask("anything"));
        assertInstanceOf(Object.class, session.fact("loan", null), "the session is still open");
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(false, true, false, 0, 20, 20_000));
    }
}
