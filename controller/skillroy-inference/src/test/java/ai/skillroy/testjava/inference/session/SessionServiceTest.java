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

import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.fakes.FakeAgent;
import ai.skillroy.testjava.inference.fakes.MutableClock;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.resolve.DeskPack;
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
import ai.skillroy.testjava.inference.sql.LendingDatabase;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
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

    /**
     * Phase 6 security fix S62 (D96, D158): the session service offers a pack verb only when no probe of it compiles to
     * what a session never runs for a model ({@code purge}, the destructive {@code desk.wipeLedger}: withheld and listed
     * by the catalogue), and checks a verb at call time on the calls it compiles to with the model's arguments: a call
     * of {@code peek} that compiles to {@code data.query} is refused as a tool result error naming the primitive, and
     * the agent receives no {@code data.query} (before the fix it ran); the same verb with harmless arguments runs.
     */
    @Test
    void aVerbIsWithheldOrRefusedByTheCallsItCompilesTo() throws Exception {
        try (ExecutorSession probed = ExecutorSession.open(pack.withProbeVerbs(), agent, SessionOptions.defaults(
                tmp.resolve("probed")).withClock(clock).withBind(BindOptions.defaults().withEnvId("test-env")
                .withLedger(ledger)).withId("sess-2"))) {
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                    tools("r1", use("t1", "lending_peek", Map.of("deep", true))),
                    tools("r2", use("t2", "lending_peek", Map.of("deep", false))),
                    tools("r3", use("t3", "skillroy_catalogue", Map.of())),
                    done("r4", "Read the loan.")));
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock);
            SessionService service = new SessionService(probed, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), clock);
            List<String> tools = names(service.tools());
            assertTrue(tools.contains("lending_peek"), tools.toString());
            assertFalse(tools.contains("lending_purge"), "withheld at the start: " + tools);

            Turn turn = service.ask("read the loan");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            Turn.Action deep = turn.actions().get(0);
            assertNull(deep.step(), "nothing ran");
            assertTrue(deep.result().contains("data.query") && deep.result().contains("nothing ran"), deep.result());
            assertTrue(agent.callsTo("data.query").isEmpty(), "the agent received no data.query");
            assertEquals(Status.PASS, turn.actions().get(1).step().status(), "a harmless call of the verb runs");
            assertEquals(1, turn.actions().get(1).step().executed());
            assertTrue(turn.actions().get(2).result().contains("lending_purge"), "the catalogue lists the withheld"
                    + " verb: " + turn.actions().get(2).result());
            assertEquals(1, probed.journal().entries().stream().filter(e -> e instanceof StepJournal.StepEntry)
                    .count(), "the refused call was not journaled");
        }
    }

    /** The values of {@link SessionDesk#withSecretSchema()} that no request part may hold (S49). */
    private static final List<String> SCHEMA_SECRETS = List.of("Vocab-Tok-8812", "Vocab-Default-4021",
            "Vocab-Enum-6613");

    /**
     * Phase 6 security fix S49 (D103 widened): the pack's vocabulary goes through the mask wherever the session's model
     * receives it. A verb schema's secret-looking property ({@code password}) keeps its name and type and loses its
     * {@code default} and {@code enum}, and a {@code key=value} secret in a description is masked, in the system part,
     * in the tools and in the {@code skillroy_catalogue} result; the masked tool schema stays a valid strict schema
     * (before the fix every value reached the request).
     */
    @Test
    void theVocabularyIsMaskedInTheSystemPartTheToolsAndTheCatalogue() throws Exception {
        try (ExecutorSession secretive = ExecutorSession.open(pack.withSecretSchema(), agent, SessionOptions.defaults(
                tmp.resolve("secretive")).withClock(clock).withBind(BindOptions.defaults().withEnvId("test-env")
                .withLedger(ledger)).withId("sess-3"))) {
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                    tools("r1", use("t1", "skillroy_catalogue", Map.of())),
                    done("r2", "Read the catalogue.")));
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock);
            SessionService service = new SessionService(secretive, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), clock);
            ToolSpec signOn = service.tools().stream().filter(t -> t.name().equals("lending_sign_on")).findFirst()
                    .orElseThrow();
            assertTrue(Json.write(signOn.inputSchema()).contains("\"password\""), "the name stays");
            assertEquals(false, signOn.inputSchema().get("additionalProperties"), "still a strict schema");
            ai.skillroy.testjava.inference.tools.ToolProjection.strict(signOn.inputSchema());
            Turn turn = service.ask("what can I do?");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            String all = everything(gateway.requests().get(0)) + everything(gateway.requests().get(1));
            assertTrue(all.contains("lending_sign_on") && all.contains("password"), all);
            for (String hidden : SCHEMA_SECRETS) {
                assertFalse(all.contains(hidden), hidden + " reached a request");
                assertFalse(turn.actions().get(0).result().contains(hidden), hidden + " in the catalogue");
            }
        }
    }

    /** The vocabulary's sentence on {@code $reference}s before Phase 7 (D342 replaced it). */
    private static final String PHASE6_REFERENCE_SENTENCE = "Its verbs, each a tool, with the JSON Schema of their"
            + " arguments (a $reference such as $item.code names a resolved slot's field):";

    /** The system text of {@link #theSystemTextIsPhase6sButForTheReferenceSentence} as Phase 6 built it. */
    private static final String PHASE6_SYSTEM_TEXT = SessionService.INSTRUCTIONS
            + "Limits: at most 10 actions per turn; a read returns at most 20000 characters.\n"
            + "\n---\n"
            + "The pack: lending 0.0.2-test.\n"
            + "Its verbs, each a tool, with the JSON Schema of their arguments (a $reference such as $item.code names"
            + " a resolved slot's field):\n"
            + "- lend (tool lending_lend): {\"properties\":{\"item\":{\"type\":\"string\"},\"qty\":{\"type\":"
            + "\"integer\"}},\"required\":[\"item\"],\"type\":\"object\"}\n"
            + "- signOn (tool lending_sign_on): {\"properties\":{\"staff\":{\"type\":\"string\"}},\"required\":"
            + "[\"staff\"],\"type\":\"object\"}\n"
            + "- takeBack (tool lending_take_back): free-form arguments\n"
            + "The tools: skillroy_snapshot, skillroy_facts, skillroy_events, skillroy_catalogue, skillroy_resolve,"
            + " skillroy_check, skillroy_observe, skillroy_note, lending_lend, lending_sign_on, lending_take_back,"
            + " done, ask_person.\n"
            + "\n---\n";

    /**
     * phase7-contracts.md 9.2 and 13.1 item 7 (D342): the session's vocabulary stops telling the model that a
     * {@code $reference} names a resolved slot's secret field; it names the non-secret fields only. A session builds the
     * same system text as before but for that one sentence (so that the committed session recordings, whose
     * fingerprints hold the text, are regenerated once in the phase, 12.5).
     */
    @Test
    void theSystemTextIsPhase6sButForTheReferenceSentence() throws Exception {
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(done("r1", "nothing to do")));
        service(gateway, SessionPolicy.acting(), "1.00").ask("anything");
        StringBuilder all = new StringBuilder();
        for (Content part : gateway.requests().get(0).system()) {
            all.append(((Content.Text) part).text()).append("\n---\n");
        }
        String text = all.toString();
        assertTrue(text.contains(SessionService.REFERENCE_SENTENCE), text);
        assertTrue(SessionService.REFERENCE_SENTENCE.contains("non-secret field"));
        assertFalse(text.contains("names a resolved slot's field"), "the Phase 6 sentence is gone: " + text);
        assertEquals(PHASE6_SYSTEM_TEXT, text.replace(SessionService.REFERENCE_SENTENCE, PHASE6_REFERENCE_SENTENCE),
                "the same system text but for the one sentence");
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

    // ------------------------------------------------------------------------------------------------ the budget

    /** A session over the inference module's test pack and database, its binds served by a scripted model. */
    private ExecutorSession deskSession(LendingDatabase db, InferenceSettings settings, String id) throws Exception {
        return ExecutorSession.open(new DeskPack(), new FakeAgent(), SessionOptions.defaults(tmp.resolve(id))
                .withClock(clock).withBind(BindOptions.defaults().withEnvId("test-env").withCredentials(
                        db.credentials()).withLedger(ledger).withInference(settings)).withId(id));
    }

    private static InferenceSettings scripted() {
        return new InferenceSettings("scripted", null, false, null, null, null, null, null, null, true, false, null,
                false, null);
    }

    /** The tier (b) answer for the desk's item query: the statement the guard accepts, its two parameters. */
    private static ModelResponse itemProposal(String cost) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("sql", "SELECT i.code, i.title, i.deposit FROM item i WHERE i.category = ? AND i.deposit <= ? ORDER BY"
                + " i.deposit");
        p.put("params", List.of(Map.of("name", "category", "value", "POWER", "type", "STRING"),
                Map.of("name", "maxDeposit", "value", "60.00", "type", "NUMBER")));
        p.put("rationale", "the items of the category within the deposit, cheapest first");
        p.put("tablesUsed", List.of("item"));
        p.put("orderBy", "i.deposit");
        p.put("confidence", 0.9);
        p.put("expectedRows", Map.of("min", 1L, "max", 5L));
        p.put("cannotAnswer", false);
        p.put("cannotAnswerReason", "");
        return ScriptedGateway.structured(p, ScriptedGateway.usage(1200, 150, cost));
    }

    private static final Map<String, Object> POWER = Map.of("category", "POWER", "maxDeposit", "60.00");

    /**
     * D101: a tier (b) resolve of a session counts on the session meter (the ledger entries it added, settled after
     * it) and in the journal's {@code budget} record, whether the person, a host or the model's
     * {@code skillroy_resolve} asked for it.
     */
    @Test
    void aSessionsTierBResolveCountsOnTheSessionMeterAndInTheBudgetRecord() throws Exception {
        ScriptedGateway model = ScriptedGateway.answering(r -> itemProposal("0.0039"));
        try (LendingDatabase db = LendingDatabase.create("session-meter");
             Gateways.Served served = Gateways.serve(scripted(), model);
             ExecutorSession desk = deskSession(db, scripted(), "sess-meter")) {
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            SessionService service = new SessionService(desk, model, SessionPolicy.acting(), meter,
                    new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock), PriceTable.builtIn(),
                    clock);
            Resolution item = service.resolve("item", "ItemByCategory", POWER, List.of("llm-sql"), List.of(), null,
                    Origin.person("desk"));
            assertEquals(Strategy.LLM_SQL, item.strategy());
            assertEquals(1, model.requests().size(), "one tier (b) call");
            assertEquals(new BigDecimal("0.0039"), meter.spent(), "settled on the session meter from the ledger");
            assertEquals(new BigDecimal("0.9961"), meter.remaining(), "no reservation left open");
            StepJournal.BudgetEntry budget = desk.journal().entries().stream()
                    .filter(e -> e instanceof StepJournal.BudgetEntry).map(e -> (StepJournal.BudgetEntry) e)
                    .reduce((a, b) -> b).orElseThrow();
            assertEquals(0, new BigDecimal("0.0039").compareTo(new BigDecimal(budget.spentUsd())), "the budget record");
            assertFalse(budget.exhausted());
        }
    }

    /**
     * D101: the resolve runs with its scenario budget capped at what is left of the session's: the contributor's
     * meter, not the default $0.50, refuses the call whose estimate (with the catalogue) does not fit.
     */
    @Test
    void aTierBResolveIsCappedAtWhatIsLeftOfTheSessionsBudget() throws Exception {
        ScriptedGateway model = ScriptedGateway.answering(r -> itemProposal("0.0039"));
        try (LendingDatabase db = LendingDatabase.create("session-cap");
             Gateways.Served served = Gateways.serve(scripted(), model);
             ExecutorSession desk = deskSession(db, scripted(), "sess-cap")) {
            // The service's own estimate (2000 tokens in, 2048 out at the Sonnet price: $0.02448) fits $0.026; the
            // resolver's, with the catalogue's tokens, does not: the cap ends the slot before any model call.
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "0.026", ledger);
            SessionService service = new SessionService(desk, model, SessionPolicy.acting(), meter,
                    new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock), PriceTable.builtIn(),
                    clock);
            BindException e = assertThrows(BindException.class, () -> service.resolve("item", "ItemByCategory",
                    POWER, List.of("llm-sql"), List.of(), null, Origin.person("desk")));
            assertEquals("BUDGET", e.reason());
            assertEquals("SCENARIO", e.detail().get("scope"), "the capped scenario meter refused: " + e.detail());
            assertEquals("0.026", e.detail().get("limitUsd"), "capped at the session's room, not $0.50");
            assertTrue(model.requests().isEmpty(), "no model call");
            assertEquals(BigDecimal.ZERO, meter.spent());
            assertEquals(new BigDecimal("0.026"), meter.remaining(), "the reservation released");
        }
    }

    /** D101: an exhausted session meter refuses a model token without a model call; tier (a) still runs. */
    @Test
    void anExhaustedSessionMeterRefusesModelTokensButNotTierA() throws Exception {
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of());
        SessionService service = service(gateway, SessionPolicy.acting(), "0.001");
        BindException refused = assertThrows(BindException.class, () -> service.resolve("item", "PlainItem",
                Map.of("category", "GARDEN"), List.of("java", "llm-sql"), List.of(), null, Origin.person("desk")));
        assertEquals(ai.skillroy.testjava.scenario.FailureKind.RESOLUTION_FAILED, refused.kind());
        assertEquals("BUDGET", refused.reason());
        assertEquals("SESSION", refused.detail().get("scope"));
        assertTrue(refused.getMessage().contains("no further model call"), refused.getMessage());
        assertTrue(service.exhausted());
        assertTrue(gateway.requests().isEmpty());
        assertTrue(session.journal().entries().stream().anyMatch(e -> e instanceof StepJournal.BudgetEntry b
                && b.exhausted()), "the journal says so");
        Resolution tierA = service.resolve("item", "PlainItem", Map.of("category", "GARDEN"), List.of("java"),
                List.of(), null, Origin.person("desk"));
        assertEquals("GAR-02", ((Map<?, ?>) tierA.value()).get("code"), "tier (a) still runs");
    }

    /**
     * The model's own {@code skillroy_resolve} goes through the metered entry: after a call that left $0.015 of $0.06
     * (less than one tier (b) call's estimate), its {@code llm-sql} resolve is refused as a tool result, and the turn
     * ends for the budget.
     */
    @Test
    void theModelsResolveIsMeteredToo() throws Exception {
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(costly("r1", "0.0450", use("t1",
                "skillroy_resolve", mapOf("slot", "other", "kind", "PlainItem", "args", Map.of("category", "GARDEN"),
                        "strategy", List.of("llm-sql")))), done("r2", "never reached")));
        SessionService service = service(gateway, SessionPolicy.acting(), "0.06");
        Turn turn = service.ask("find a garden item with the model");
        assertEquals(Turn.Outcome.BUDGET, turn.outcome());
        assertEquals(1, gateway.requests().size(), "no further model call");
        assertTrue(turn.actions().get(0).result().contains("\"reason\": \"BUDGET\""), turn.actions().get(0).result());
        assertTrue(service.exhausted());
    }

    // ------------------------------------------------------------------------------------------------ settings

    private ExecutorSession sessionWith(InferenceSettings settings, String id) throws Exception {
        return ExecutorSession.open(pack, SessionDesk.agent(), SessionOptions.defaults(tmp.resolve(id))
                .withClock(clock).withBind(BindOptions.defaults().withEnvId("test-env").withLedger(ledger)
                        .withInference(settings)).withId(id));
    }

    /** L1 F7: effort.session and effort.summary reach the requests, the model ids the estimates. */
    @Test
    void theSettingsEffortsAndModelIdsReachTheRequestsAndTheEstimates() throws Exception {
        InferenceSettings settings = new InferenceSettings("scripted", null, false, null, Map.of("opus",
                "claude-opus-5-5", "sonnet", "claude-sonnet-5-5", "haiku", "claude-haiku-4-5"), Map.of("resolve",
                "low", "triage", "low", "session", "high", "summary", "medium"), null, null, null, true, false, null,
                false, null);
        try (ExecutorSession configured = sessionWith(settings, "sess-effort")) {
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(done("r1", "one"), done("r2", "two"),
                    ScriptedGateway.text("The person asked twice."), done("r3", "three")));
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            SessionService service = new SessionService(configured, gateway, SessionPolicy.acting()
                    .withMaxHistoryTurns(1), meter, new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(),
                    false, clock), PriceTable.builtIn(), clock);
            service.ask("first");
            service.ask("second");
            service.ask("third");
            List<ModelRequest> requests = gateway.requests();
            assertEquals(ai.skillroy.testjava.inference.spi.Effort.HIGH, requests.get(0).effort(), "effort.session");
            assertEquals(SessionService.PURPOSE_SUMMARY, requests.get(2).purpose());
            assertEquals(ai.skillroy.testjava.inference.spi.Effort.MEDIUM, requests.get(2).effort(),
                    "effort.summary");
        }
        // The turn's estimate (its text / 4 plus 4096 answer tokens) is about $0.043 at the Sonnet price and about
        // $0.086 at the Opus price: $0.06 lets a call through only at the model id the settings do not name.
        InferenceSettings dear = new InferenceSettings("scripted", null, false, null, Map.of("opus",
                "claude-opus-5-5", "sonnet", "claude-opus-5-5", "haiku", "claude-haiku-4-5"), null, null, null, null,
                true, false, null, false, null);
        try (ExecutorSession expensive = sessionWith(dear, "sess-dear")) {
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(done("r1", "never")));
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "0.06", ledger);
            Turn turn = new SessionService(expensive, gateway, SessionPolicy.acting(), meter, new LedgerWriter(ledger,
                    meter, log, PromptMask.keysOnly(), false, clock), PriceTable.builtIn(), clock).ask("anything");
            assertEquals(Turn.Outcome.BUDGET, turn.outcome(), "estimated at the configured (dearer) model id");
            assertTrue(gateway.requests().isEmpty());
        }
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(done("r1", "made")));
        assertEquals(Turn.Outcome.DONE, service(gateway, SessionPolicy.acting(), "0.06").ask("anything").outcome(),
                "the default Sonnet id fits $0.06");
    }

    // ------------------------------------------------------------------------------------------------ masking

    /** What every part of a request holds, opaque parts and tool calls included. */
    static String everything(ModelRequest request) {
        List<Object> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            messages.add(ai.skillroy.testjava.inference.spi.Prompts.message(m, true));
        }
        List<Object> tools = new ArrayList<>();
        for (ToolSpec t : request.tools()) {
            tools.add(ai.skillroy.testjava.inference.spi.Prompts.tool(t));
        }
        Map<String, Object> all = new java.util.LinkedHashMap<>();
        all.put("system", ai.skillroy.testjava.inference.spi.Prompts.parts(request.system(), true));
        all.put("messages", messages);
        all.put("tools", tools);
        return Json.write(all);
    }

    /** A credential of the session's environment, 8 characters or more (the by-value rule inside a text). */
    static final String CREDENTIAL = "Probe-Cred-8812";
    /** Values only the key rule catches: under a secret-looking key, or in a key=value run inside a text. */
    static final List<String> KEYED = List.of("Probe-Fact-4431", "Probe-Note-6620", "Probe-Run-5521",
            "Probe-Event-7734", "Probe-Person-9912", "Probe-Sum-1188");

    /**
     * L3 F2: every prompt a session builds is masked: the person's text, the facts, the snapshot and the events the
     * model reads, and the folded summary, through the session service's real request builder. Values that only the
     * key rule catches and a credential inside a text appear in no part of any request, the summary call's included.
     */
    @Test
    void noSecretReachesAnyPartOfAnyRequestOfASession() throws Exception {
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("version", 1L);
        doc.put("credentials", Map.of("staff", Map.of("S01", Map.of("password", CREDENTIAL))));
        ai.skillroy.testjava.data.Credentials credentials = ai.skillroy.testjava.data.Credentials.of(doc, "test");
        FakeAgent probe = SessionDesk.agent();
        probe.on("desk.loan", params -> Map.of("lines", List.of(), "apiToken", "Probe-Fact-4431", "note",
                "the desk says secret=Probe-Note-6620 for now"));
        probe.value("swing.snapshot", Map.of("seq", 1L, "truncated", false, "windows", List.of(Map.of("ref", "h1",
                "role", "window", "name", "Desk token=Probe-Run-5521"))));
        probe.publish("desk.reminder", Map.of("secretKey", "Probe-Event-7734", "text", "a reminder"));
        try (ExecutorSession masked = ExecutorSession.open(pack, probe, SessionOptions.defaults(tmp.resolve("masked"))
                .withClock(clock).withBind(BindOptions.defaults().withEnvId("test-env").withLedger(ledger)
                        .withCredentials(credentials)).withId("sess-masked"))) {
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                    tools("r1", use("a", "skillroy_facts", mapOf("root", "loan", "path", null)),
                            use("b", "skillroy_snapshot", mapOf("maxChars", null)),
                            use("c", "skillroy_events", mapOf("sinceSeq", null, "topics", null, "max", null))),
                    done("r2", "read it all"),
                    done("r3", "second done"),
                    ScriptedGateway.text("The person asked; the summary repeats token=Probe-Sum-1188 by mistake."),
                    done("r4", "third done")));
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            SessionService service = new SessionService(masked, gateway, SessionPolicy.acting().withMaxHistoryTurns(1),
                    meter, new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock),
                    PriceTable.builtIn(), clock);
            service.ask("read the desk; my note is " + CREDENTIAL + " and password=Probe-Person-9912");
            service.ask("anything else?");
            service.ask("and now?");
            List<ModelRequest> requests = gateway.requests();
            assertEquals(5, requests.size());
            assertTrue(requests.stream().anyMatch(r -> SessionService.PURPOSE_SUMMARY.equals(r.purpose())),
                    "the summary call is among them");
            String read = everything(requests.get(1));
            assertTrue(read.contains("<target-state>") && read.contains("<target-ui>")
                    && read.contains("<target-event>"), "the facts, the snapshot and the events were read: " + read);
            for (ModelRequest r : requests) {
                String all = everything(r);
                assertFalse(all.contains(CREDENTIAL), r.purpose() + " request holds the credential: " + all);
                for (String value : KEYED) {
                    assertFalse(all.contains(value), r.purpose() + " request holds " + value + ": " + all);
                }
            }
            assertTrue(everything(requests.get(4)).contains("<redacted>"), "the folded summary, masked");
        }
    }

    /**
     * Fix wave LV-1 (phase7-contracts.md 9.1, 9.2; D342): when slots are bound in the session before a turn (a person's
     * resolve, a person's fixture with a credential), the turn's first message names them after the person's block, in
     * a target-state block: each slot's reference, its query kind or fixture, whether it is a credential, and its
     * non-secret fields as references; never a value, never a secret, never a secret field's name. Without a bound slot
     * the person's block is alone (the Phase 5 shape, so that no recording of such a session changes).
     */
    @Test
    void theTurnNamesTheSlotsBoundInTheSessionButNeverAValueOrASecret() throws Exception {
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("version", 1L);
        doc.put("credentials", Map.of("staff", Map.of("S01", Map.of("password", CREDENTIAL))));
        ai.skillroy.testjava.data.Credentials credentials = ai.skillroy.testjava.data.Credentials.of(doc, "test");
        Origin person = Origin.person("tester");
        try (ExecutorSession bound = ExecutorSession.open(pack, SessionDesk.agent(), SessionOptions.defaults(
                tmp.resolve("bound")).withClock(clock).withBind(BindOptions.defaults().withEnvId("test-env")
                .withLedger(ledger).withCredentials(credentials)).withId("sess-bound"))) {
            ScriptedGateway before = ScriptedGateway.sequence(List.of(done("r1", "nothing bound yet")));
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            new SessionService(bound, before, SessionPolicy.acting(), meter, new LedgerWriter(ledger, meter, log,
                    PromptMask.keysOnly(), false, clock), PriceTable.builtIn(), clock).ask("anything");
            assertEquals(1, before.requests().get(0).messages().get(0).parts().size(),
                    "no slot bound: the person's block alone");

            bound.resolve("item", "PlainItem", Map.of("category", "GARDEN"), List.of(), List.of(), null, person);
            bound.fixture("staff", Map.of("code", "S01"), new ai.skillroy.testjava.scenario.resolve.CredentialSpec(
                    "staff", "code"), person);
            ScriptedGateway after = ScriptedGateway.sequence(List.of(done("r2", "lent")));
            new SessionService(bound, after, SessionPolicy.acting(), meter, new LedgerWriter(ledger, meter, log,
                    PromptMask.keysOnly(), false, clock), PriceTable.builtIn(), clock)
                    .ask("lend $item to the walk-in borrower");
            List<Content> opening = after.requests().get(0).messages().get(0).parts();
            assertEquals(2, opening.size(), String.valueOf(opening));
            assertEquals("<person>\nlend $item to the walk-in borrower\n</person>",
                    ((Content.Text) opening.get(0)).text());
            String slots = ((Content.Text) opening.get(1)).text();
            assertTrue(slots.startsWith("<target-state>\n") && slots.endsWith("\n</target-state>"), slots);
            for (String named : List.of("$item", "PlainItem", "$item.code", "$item.title", "$staff", "fixture",
                    "credential", "$staff.code")) {
                assertTrue(slots.contains(named), named + " in " + slots);
            }
            assertFalse(slots.contains(CREDENTIAL), "no secret: " + slots);
            assertFalse(slots.contains("password"), "no secret field's name: " + slots);
            assertFalse(slots.contains("GAR-02") || slots.contains("Lawn aerator") || slots.contains("S01"),
                    "no value: " + slots);
            assertFalse(everything(after.requests().get(0)).contains(CREDENTIAL));
        }
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
