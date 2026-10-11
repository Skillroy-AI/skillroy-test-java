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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.inference.budget.BudgetException;
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
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 9.1, 10 and 13.1 item 7: the {@code SessionTools} seam of the session service with a
 * {@link FakeSessionTools}: the tool set's tools listed, its instructions and provider tools in the request and masked,
 * the vision shape of a call while it applies, {@code atTurnStart} in the turn's first message, {@code act} consulted,
 * {@code afterBatch} appended, {@code endTurn}, the vision limits enforced and the tools closed, {@code visionCall} one
 * ledger entry of purpose {@code vision} on the session meter, the raw primitive branch under {@code CallPolicy}, the
 * facade's refusals, a read-only policy, the estimate with an image part, and a session without an applying tool set
 * building the Phase 5 system text.
 */
class SessionServiceToolsTest {

    @TempDir
    Path tmp;

    private final SessionDesk pack = new SessionDesk();
    private FakeAgent agent;
    private ExecutorSession session;
    private CostLedger ledger;
    private InferenceLog.Memory log;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T09:00:00Z"));
    private final FakeSessionTools fake = new FakeSessionTools();

    /** The settings of a vision session: a scripted gateway, images on. */
    private static InferenceSettings images(boolean on) {
        return new InferenceSettings("scripted", null, false, null, null, null, null, null, null, true, on, null,
                false, null);
    }

    @BeforeEach
    void open() throws Exception {
        agent = SessionDesk.agent();
        agent.describe("swing.pointer", true, false).describe("swing.type", true, false)
                .describe("swing.key", true, false).describe("swing.capture", false, false)
                .describe("swing.hitTest", false, false);
        agent.value("swing.capture", Map.of("format", "png", "width", 4L, "height", 4L, "base64", "iVBORw0KGgo=",
                "hash", "sha256:abc", "captureId", "cap-1"));
        agent.on("swing.pointer", params -> Map.of("fired", true));
        ledger = new CostLedger();
        log = InferenceLog.inMemory();
        session = open("sess-v", images(true), false);
    }

    private ExecutorSession open(String id, InferenceSettings settings, boolean robot) throws Exception {
        return ExecutorSession.open(pack, agent, SessionOptions.defaults(tmp.resolve(id)).withClock(clock)
                .withBind(BindOptions.defaults().withEnvId("test-env").withLedger(ledger).withInference(settings))
                .withId(id).withRobot(robot));
    }

    @AfterEach
    void close() {
        session.close();
    }

    private SessionService service(ExecutorSession s, InferenceGateway gateway, SessionPolicy policy, String budget,
                                   Map<String, String> options) {
        BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, budget, ledger);
        LedgerWriter writer = new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock);
        return new SessionService(s, gateway, policy, meter, writer, PriceTable.builtIn(), clock,
                new SessionExtensions(List.of(fake), options));
    }

    private SessionService service(InferenceGateway gateway) {
        return service(session, gateway, SessionPolicy.acting(), "1.00", Map.of());
    }

    private static Usage usage(String requestId) {
        return new Usage("scripted", "claude-sonnet-5-5", "claude-sonnet-5-5", requestId, 300, 40, 0, 0, 0, false,
                "0.0010", Duration.ZERO, null);
    }

    private static ModelResponse tools(String requestId, Content... parts) {
        return new ModelResponse(List.of(parts), ModelResponse.StopReason.TOOL_USE, usage(requestId), null);
    }

    private static Content.ToolUse use(String id, String name, Map<String, Object> input) {
        return new Content.ToolUse(id, name, input);
    }

    private static ModelResponse done(String requestId) {
        return tools(requestId, use("done-" + requestId, "done", Map.of("answer", "finished")));
    }

    private List<StepJournal.StepEntry> steps(ExecutorSession s) {
        return s.journal().entries().stream().filter(e -> e instanceof StepJournal.StepEntry)
                .map(e -> (StepJournal.StepEntry) e).toList();
    }

    private static String text(Content part) {
        return part instanceof Content.Text t ? t.text() : String.valueOf(part);
    }

    /** {@code fake_click}: a lend through the facade with provenance; the step as the result. */
    private void clickLends() {
        fake.handler = (u, ctx) -> {
            StepOutcome step = ctx.actions().execute("lend", Map.of("item", "GAR-02"), Map.of("tier", "V1",
                    "intent", "lend the aerator", "point", Map.of("x", 412L, "y", 288L)));
            return new SessionTools.Outcome(new Content.ToolResult(u.id(), "lent: " + step.status(), !step.succeeded()),
                    step, SessionTools.EndTurn.NONE, null);
        };
    }

    // ------------------------------------------------------------------------------------------------ the seam

    @Test
    void anApplyingToolSetIsListedSentAndConsulted() throws Exception {
        pack.legend = "The keypad's DUE key computes the deposit due; token=" + FakeSessionTools.SECRET;
        clickLends();
        fake.toolset = true;
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                tools("r1", Content.Text.of("I lend the item."), use("t1", "fake_click", Map.of())),
                done("r2")));
        SessionService service = service(gateway);
        List<String> names = service.tools().stream().map(ToolSpec::name).toList();
        assertEquals(List.of("fake_click", "fake_look", "done", "ask_person"), names.subList(names.size() - 4,
                names.size()), "after the projection's, before done and ask_person: " + names);
        assertTrue(names.contains("lending_lend"));
        assertEquals(List.of("fake"), service.toolSets());
        assertTrue(fake.seen.robotAllowed() == false && fake.seen.policy() == service.policy());

        Turn turn = service.ask("lend one plain item");
        assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
        assertEquals(List.of("fake_click"), fake.asked, "act consulted for its tool");
        assertEquals(1, fake.turnStarts);
        assertEquals(1, fake.batches, "afterBatch once, after the one batch");

        ModelRequest first = gateway.requests().get(0);
        assertEquals(SessionService.PURPOSE_VISION, first.purpose(), "a call while a tool set applies");
        assertEquals(CacheHint.AUTO, first.cache());
        assertEquals("LOW", first.effort().name(), "effort.vision, low by default");
        assertEquals(1, first.providerTools().size(), "the tool set's provider tools");
        assertEquals(4, first.system().size(), "the instructions, the tool set's, the legend, the vocabulary");
        assertTrue(text(first.system().get(1)).startsWith("Fake instructions"));
        assertTrue(text(first.system().get(2)).contains("DUE key"));
        assertTrue(((Content.Text) first.system().get(3)).cacheBreakpoint(), "the vocabulary keeps the breakpoint");
        assertTrue(text(first.system().get(3)).contains("fake_click"), "the vocabulary names the tool set's tools");
        String all = SessionServiceTest.everything(first);
        assertFalse(all.contains(FakeSessionTools.SECRET), "instructions, legend and tools masked: " + all);

        List<Content> opening = first.messages().get(0).parts();
        assertTrue(text(opening.get(0)).startsWith("<person>"));
        assertTrue(text(opening.get(1)).contains("mark 1: button fake"), "atTurnStart's parts follow");
        assertInstanceOf(Content.Image.class, opening.get(2));

        ModelRequest second = gateway.requests().get(1);
        List<Content> results = second.messages().get(second.messages().size() - 1).parts();
        assertEquals("t1", ((Content.ToolResult) results.get(0)).toolUseId());
        assertTrue(text(results.get(1)).contains("after the batch 1"), "afterBatch after the results: " + results);

        List<StepJournal.StepEntry> steps = steps(session);
        assertEquals(1, steps.size());
        assertEquals(Origin.Kind.MODEL, steps.get(0).origin().kind());
        assertEquals("I lend the item.", steps.get(0).origin().intent(), "the turn's origin, fixed by the facade");
        assertEquals("V1", steps.get(0).provenance().get("tier"), "the provenance on the step's own line");
        assertEquals(2, ledger.entries().size());
        assertTrue(ledger.entries().stream().allMatch(e -> "vision".equals(e.purpose())));
        service.close();
        assertTrue(fake.closed, "the service closes its tool sets");
    }

    @Test
    void aToolSetThatDoesNotApplyLeavesThePhase5SessionAndIsClosed() throws Exception {
        fake.applies = false;
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(done("r1")));
        SessionService service = service(gateway);
        assertTrue(fake.closed);
        assertTrue(service.toolSets().isEmpty());
        assertFalse(service.tools().stream().anyMatch(t -> t.name().startsWith("fake_")));
        service.ask("anything");
        ModelRequest request = gateway.requests().get(0);
        assertEquals(SessionService.PURPOSE, request.purpose());
        assertEquals(CacheHint.SHORT, request.cache());
        assertEquals(2, request.system().size());
        assertTrue(request.providerTools().isEmpty());
        assertEquals(0, fake.turnStarts);

        ScriptedGateway plain = ScriptedGateway.sequence(List.of(done("r1")));
        BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
        new SessionService(session, plain, SessionPolicy.acting(), meter, new LedgerWriter(ledger, meter, log,
                PromptMask.keysOnly(), false, clock), PriceTable.builtIn(), clock).ask("anything");
        assertEquals(plain.requests().get(0).system(), request.system(), "the same system text as without the seam");
    }

    @Test
    void anEndTurnEndsTheTurnAsAQuestionOrALimit() throws Exception {
        fake.handler = (u, ctx) -> new SessionTools.Outcome(new Content.ToolResult(u.id(), "lost focus", true), null,
                SessionTools.EndTurn.QUESTION, "the target lost the focus; bring it to the front and ask again");
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1", use("t1", "fake_click", Map.of()),
                use("t2", "fake_look", Map.of()))));
        Turn turn = service(gateway).ask("lend");
        assertEquals(Turn.Outcome.QUESTION, turn.outcome());
        assertEquals("the target lost the focus; bring it to the front and ask again", turn.answer());
        assertEquals(List.of("fake_click"), fake.asked, "the batch's later call is not run");
        assertEquals(0, fake.batches, "no capture after a batch that ended the turn");

        fake.asked.clear();
        fake.handler = (u, ctx) -> new SessionTools.Outcome(new Content.ToolResult(u.id(), "stop", true), null,
                SessionTools.EndTurn.LIMIT, "the tool's own limit");
        ScriptedGateway limited = ScriptedGateway.sequence(List.of(tools("r1", use("t1", "fake_look", Map.of()))));
        Turn ended = service(limited).ask("look");
        assertEquals(Turn.Outcome.LIMIT, ended.outcome());
        assertEquals("the tool's own limit", ended.answer());
    }

    @Test
    void theVisionActionLimitIsEnforcedByTheServiceAndClosesTheTools() throws Exception {
        clickLends();
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                tools("r1", use("t1", "fake_click", Map.of()), use("t2", "fake_click", Map.of())),
                tools("r2", use("t3", "fake_click", Map.of())),
                done("r3")));
        SessionService service = service(session, gateway, SessionPolicy.acting(), "1.00",
                Map.of(SessionService.OPTION_MAX_ACTIONS, "2"));
        Turn turn = service.ask("lend three");
        assertEquals(Turn.Outcome.LIMIT, turn.outcome(), turn.answer());
        assertTrue(turn.answer().contains("limit of 2 UI actions"), turn.answer());
        assertEquals(2, agent.callsTo("desk.lend").size(), "the third action was refused, nothing sent");
        assertTrue(turn.actions().get(2).result().contains("refused"), turn.actions().get(2).result());
        assertTrue(fake.closed, "the tools are closed for the session");
        assertNotNull(service.toolSetsClosed());
        assertFalse(service.tools().stream().anyMatch(t -> t.name().startsWith("fake_")), "tools() lists none");

        service.ask("and now?");
        ModelRequest after = gateway.requests().get(gateway.requests().size() - 1);
        assertEquals(SessionService.PURPOSE, after.purpose(), "the next turn is a plain session turn");
        assertTrue(text(after.system().get(after.system().size() - 1)).contains("closed for the rest of this session"),
                "the model is told why");
    }

    @Test
    void theVisionTurnLimitCountsEveryModelCallWhileTheToolsApply() throws Exception {
        fake.handler = (u, ctx) -> {
            ModelResponse answer;
            try {
                answer = ctx.actions().complete(ModelRequest.builder("anything", ModelClass.SONNET_CLASS)
                        .message(Message.user("is it shown?")).build());
            } catch (BudgetException | InferenceException e) {
                return SessionTools.Outcome.of(new Content.ToolResult(u.id(), e.getMessage(), true));
            }
            return SessionTools.Outcome.of(new Content.ToolResult(u.id(), answer.text(), false));
        };
        List<ModelResponse> answers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            answers.add(tools("r" + i, use("t" + i, "fake_look", Map.of())));
            answers.add(ScriptedGateway.text("YES"));
        }
        ScriptedGateway gateway = ScriptedGateway.sequence(answers);
        SessionService service = service(session, gateway, SessionPolicy.acting(), "1.00",
                Map.of(SessionService.OPTION_MAX_TURNS, "3"));
        Turn turn = service.ask("look");
        assertEquals(Turn.Outcome.LIMIT, turn.outcome(), turn.answer());
        assertTrue(turn.answer().contains("limit of 3 model turns"), turn.answer());
        assertEquals(3, gateway.requests().size(), "a loop call, a tool's call, a loop call; the fourth refused");
        assertTrue(fake.closed);
        assertEquals("vision", gateway.requests().get(1).purpose(), "the tool's call through visionCall");
    }

    @Test
    void visionCallIsOneLedgerEntryOfPurposeVisionOnTheSessionMeter() throws Exception {
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(ScriptedGateway.text("NO")));
        SessionService service = service(gateway);
        BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
        ModelResponse answer = service.visionCall(ModelRequest.builder("question", ModelClass.SONNET_CLASS)
                .message(Message.user(Content.Text.of("<person>\nthe loan shows two lines\n</person>"),
                        new Content.Image("image/png", FakeSessionTools.PNG, null))).build());
        assertEquals("NO", answer.text());
        assertEquals(1, ledger.entries().size());
        CostLedger.Entry entry = ledger.entries().get(0);
        assertEquals("vision", entry.purpose(), "the purpose forced to vision");
        assertEquals("sess-v", entry.scenarioId(), "named by the session");
        assertNull(entry.strategy());
        assertNull(entry.slot());
        assertEquals("vision", gateway.requests().get(0).purpose());
        assertEquals("sess-v", gateway.requests().get(0).metadata().get(SessionService.META_SESSION));
        assertTrue(session.journal().entries().stream().anyMatch(e -> e instanceof StepJournal.BudgetEntry),
                "the budget state in the journal");

        ScriptedGateway failing = ScriptedGateway.failing(new InferenceException(InferenceException.Kind.OVERLOADED,
                "busy"));
        SessionService other = service(failing);
        assertThrows(InferenceException.class, () -> other.visionCall(ModelRequest.builder("q", ModelClass.SONNET_CLASS)
                .message(Message.user("x")).build()));
        assertEquals(2, ledger.entries().size(), "a failed call is a ledger entry too (D102)");
        assertEquals("vision", ledger.entries().get(1).purpose());
    }

    @Test
    void anImageIsRefusedWhileImagesAreOff() throws Exception {
        try (ExecutorSession off = open("sess-off", images(false), false)) {
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(done("r1")));
            SessionService service = service(off, gateway, SessionPolicy.acting(), "1.00", Map.of());
            Turn turn = service.ask("look");
            assertEquals(Turn.Outcome.QUESTION, turn.outcome());
            assertTrue(turn.answer().contains("images are off"), turn.answer());
            assertTrue(gateway.requests().isEmpty(), "nothing was sent");
            assertEquals(InferenceException.Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                    () -> service.visionCall(ModelRequest.builder("q", ModelClass.SONNET_CLASS).message(Message.user(
                            new Content.Image("image/png", FakeSessionTools.PNG, null))).build())).kind());
        }
    }

    // ------------------------------------------------------------------------------------------------ CallPolicy

    /**
     * 9.1's table, the raw primitive branch (D347): a model's raw primitive call that carries a person's argument is a
     * tool error naming the primitive and the argument, with nothing sent and nothing journaled, whatever the robot
     * flag.
     */
    @Test
    void theRawPrimitiveBranchRefusesAPersonsArguments() throws Exception {
        fake.applies = false;
        try (ExecutorSession robot = open("sess-robot", images(true), true)) {
            for (ExecutorSession s : List.of(session, robot)) {
                List<Content> calls = new ArrayList<>();
                int n = 0;
                for (Map.Entry<String, Map<String, Object>> c : List.of(
                        Map.entry("swing_pointer", Map.<String, Object>of("action", "CLICK", "via", "ROBOT")),
                        Map.entry("swing_click", Map.<String, Object>of("target", "h1", "force", true)),
                        Map.entry("swing_type", Map.<String, Object>of("text", "x", "force", true)),
                        Map.entry("swing_key", Map.<String, Object>of("key", "ENTER", "force", true)),
                        Map.entry("swing_pointer", Map.<String, Object>of("action", "CLICK", "force", true)),
                        Map.entry("swing_capture", Map.<String, Object>of("redact", false)),
                        Map.entry("swing_capture", Map.<String, Object>of("mode", "ROBOT")))) {
                    calls.add(use("t" + (++n), c.getKey(), c.getValue()));
                }
                ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1", calls.toArray(new Content[0])),
                        done("r2")));
                Turn turn = service(s, gateway, SessionPolicy.acting().withBasePrimitives(true)
                        .withMaxActionsPerTurn(20), "1.00", Map.of()).ask("act");
                assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
                List<String> expected = List.of("via: ROBOT", "force", "force", "force", "force", "", "");
                for (int i = 0; i < calls.size(); i++) {
                    Turn.Action a = turn.actions().get(i);
                    assertNull(a.step(), "nothing journaled: " + a.result());
                    assertTrue(a.result().contains(expected.get(i)), a.tool() + ": " + a.result());
                }
                for (String m : List.of("swing.pointer", "swing.click", "swing.type", "swing.key", "swing.capture")) {
                    assertTrue(agent.callsTo(m).isEmpty(), m + " reached the agent");
                }
                assertTrue(steps(s).isEmpty(), "nothing journaled");
            }
        }
    }

    /**
     * 9.1's facade (D353): a tool set's {@code read("data.query")}, an {@code execute} with {@code confirm: true} and
     * a read with {@code mode: ROBOT} are refused with nothing sent; a step with {@code via: ROBOT} passes under the
     * host's robot flag and is refused without it; outside a tool call the facade refuses to act.
     */
    @Test
    void theFacadeRefusesWhatAToolSetMayNotDo() throws Exception {
        List<String> refusals = new ArrayList<>();
        Map<String, Object> robotClick = new LinkedHashMap<>(Map.of("action", "CLICK", "via", "ROBOT",
                "requireFocus", true, "target", "h1"));
        fake.handler = (u, ctx) -> {
            SessionActions actions = ctx.actions();
            try {
                actions.read("data.query", Map.of("source", "db", "sql", "SELECT 1"));
            } catch (IllegalArgumentException | AgentRpcException e) {
                refusals.add("data.query: " + e.getMessage());
            }
            try {
                actions.execute(new PlannedCall("swing.click", Map.of("target", "h1", "confirm", true), Sync.NONE,
                        1000), null);
            } catch (IllegalArgumentException e) {
                refusals.add("confirm: " + e.getMessage());
            }
            try {
                actions.read("swing.capture", Map.of("mode", "ROBOT"));
            } catch (IllegalArgumentException | AgentRpcException e) {
                refusals.add("glass: " + e.getMessage());
            }
            try {
                actions.read("swing.capture", Map.of("redact", false));
            } catch (IllegalArgumentException | AgentRpcException e) {
                refusals.add("unredacted: " + e.getMessage());
            }
            try {
                StepOutcome step = actions.execute(new PlannedCall("swing.pointer", robotClick, Sync.NONE, 1000),
                        Map.of("tier", "V3", "via", "ROBOT"));
                refusals.add("robot ran: " + step.status());
            } catch (IllegalArgumentException e) {
                refusals.add("robot: " + e.getMessage());
            }
            return SessionTools.Outcome.of(new Content.ToolResult(u.id(), "tried", false));
        };
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1", use("t1", "fake_look", Map.of())),
                done("r2")));
        SessionService service = service(gateway);
        service.ask("try everything");
        assertEquals(5, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).startsWith("data.query: ") && refusals.get(0).contains("data.query"));
        assertTrue(refusals.get(1).contains("'confirm'"), refusals.get(1));
        assertTrue(refusals.get(2).contains("mode: ROBOT"), refusals.get(2));
        assertTrue(refusals.get(3).contains("redact: false"), refusals.get(3));
        assertTrue(refusals.get(4).startsWith("robot: ") && refusals.get(4).contains("via: ROBOT"),
                "without the host's flag: " + refusals.get(4));
        assertTrue(agent.callsTo("data.query").isEmpty());
        assertTrue(agent.callsTo("swing.click").isEmpty());
        assertTrue(agent.callsTo("swing.capture").isEmpty());
        assertTrue(agent.callsTo("swing.pointer").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fake.seen.actions().execute("lend", Map.of("item", "x"),
                null), "outside a tool call the facade acts on nothing");

        refusals.clear();
        try (ExecutorSession robot = open("sess-robot", images(true), true)) {
            ScriptedGateway again = ScriptedGateway.sequence(List.of(tools("r1", use("t1", "fake_look", Map.of())),
                    done("r2")));
            service(robot, again, SessionPolicy.acting(), "1.00", Map.of()).ask("try everything");
            assertEquals("robot ran: PASS", refusals.get(4), "the host's flag lets the tool set's V3 step through");
            assertEquals(1, agent.callsTo("swing.pointer").size());
            assertEquals("ROBOT", agent.callsTo("swing.pointer").get(0).params().get("via"));
            StepJournal.StepEntry step = steps(robot).get(0);
            assertEquals("ROBOT", step.provenance().get("via"));
        }
    }

    /** 9.1: a read the facade serves goes through {@code ExecutorSession.read}, unjournaled. */
    @Test
    void theFacadesReadIsUnjournaled() throws Exception {
        List<Object> read = new ArrayList<>();
        fake.handler = (u, ctx) -> {
            try {
                read.add(ctx.actions().read("swing.capture", Map.of("scope", "SCREEN", "redact", true)));
            } catch (AgentRpcException e) {
                read.add(e);
            }
            return SessionTools.Outcome.of(new Content.ToolResult(u.id(), "captured", false));
        };
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1", use("t1", "fake_look", Map.of())),
                done("r2")));
        Turn turn = service(gateway).ask("look");
        assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
        assertInstanceOf(Map.class, read.get(0), String.valueOf(read));
        assertEquals("iVBORw0KGgo=", ((Map<?, ?>) read.get(0)).get("base64"), "the base64 as the agent sent it");
        assertEquals(1, agent.callsTo("swing.capture").size());
        assertTrue(steps(session).isEmpty(), "a read is not journaled");
        assertNull(turn.actions().get(0).step());
    }

    /** 9.1: under a read-only session a step a tool set ran anyway meets the session's read-only refusal. */
    @Test
    void underAReadOnlySessionATooLsStepMeetsTheReadOnlyRefusal() throws Exception {
        clickLends();
        try (ExecutorSession readOnly = ExecutorSession.open(pack, agent, SessionOptions.defaults(tmp.resolve("ro"))
                .withClock(clock).withBind(BindOptions.defaults().withEnvId("test-env").withLedger(ledger)
                        .withInference(images(true))).withId("ro").withReadOnly(true))) {
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1", use("t1", "fake_click", Map.of())),
                    done("r2")));
            SessionService service = service(readOnly, gateway, SessionPolicy.readOnlyPolicy(), "1.00", Map.of());
            assertTrue(fake.seen.policy().readOnly(), "the tool set sees the policy");
            Turn turn = service.ask("lend");
            assertTrue(agent.callsTo("desk.lend").isEmpty(), "nothing was lent");
            StepOutcome step = turn.actions().get(0).step();
            assertTrue(step == null || step.status() != Status.PASS, String.valueOf(turn.actions().get(0).result()));
        }
    }

    // ------------------------------------------------------------------------------------------------ the estimate

    /** A gateway without a token count: the estimate's fallback is taken. */
    private static InferenceGateway uncounted(ScriptedGateway scripted) {
        return new InferenceGateway() {
            @Override
            public String provider() {
                return scripted.provider();
            }

            @Override
            public java.util.Set<GatewayCapability> capabilities() {
                return java.util.Set.of(GatewayCapability.TOOLS, GatewayCapability.IMAGES);
            }

            @Override
            public ModelResponse complete(ModelRequest request) throws InferenceException {
                return scripted.complete(request);
            }

            @Override
            public ai.skillroy.testjava.inference.spi.BatchHandle submitBatch(List<ModelRequest> requests) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ai.skillroy.testjava.inference.spi.BatchStatus batchStatus(
                    ai.skillroy.testjava.inference.spi.BatchHandle handle) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ai.skillroy.testjava.inference.spi.BatchResult> collectBatch(
                    ai.skillroy.testjava.inference.spi.BatchHandle handle) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void cancelBatch(ai.skillroy.testjava.inference.spi.BatchHandle handle) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long countTokens(ModelRequest request) {
                return -1;
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * phase7-contracts.md 7.5 (D324): the estimate's fallback counts {@code ceil(w/28) * ceil(h/28)} tokens per image:
     * ten 1024 x 768 captures are 10,360 tokens, USD 0.0207 at the Sonnet price, which a USD 0.02 meter refuses before
     * the call while the same request without the images fits.
     */
    @Test
    void theEstimateCountsAnImagesTokens() throws Exception {
        fake.applies = false;
        List<Content> parts = new ArrayList<>();
        parts.add(Content.Text.of("look at these"));
        for (int i = 0; i < 10; i++) {
            parts.add(new Content.Image("image/png", FakeSessionTools.png(1024, 768 + i), null));
        }
        ScriptedGateway scripted = ScriptedGateway.sequence(List.of(ScriptedGateway.text("fine")));
        SessionService service = service(session, uncounted(scripted), SessionPolicy.acting(), "0.02", Map.of());
        ModelRequest heavy = ModelRequest.builder("q", ModelClass.SONNET_CLASS).maxTokens(1)
                .message(new Message(Message.Role.USER, parts)).build();
        assertThrows(BudgetException.class, () -> service.visionCall(heavy));
        assertTrue(scripted.requests().isEmpty(), "refused before the call");

        ScriptedGateway again = ScriptedGateway.sequence(List.of(ScriptedGateway.text("fine")));
        SessionService light = service(session, uncounted(again), SessionPolicy.acting(), "0.02", Map.of());
        assertEquals("fine", light.visionCall(ModelRequest.builder("q", ModelClass.SONNET_CLASS).maxTokens(1)
                .message(Message.user("look at these")).build()).text());
    }

    // ------------------------------------------------------------------------------------------------ security fixes

    /**
     * Phase 7 security fix S110 (phase7-contracts.md 11, 9.1): the raw primitive branch refuses a clipboard chord and a
     * middle button, as every route does; nothing sent, nothing journaled.
     */
    @Test
    void theRawPrimitiveBranchRefusesTheClipboard() throws Exception {
        fake.applies = false;
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1",
                use("t1", "swing_key", Map.of("key", "V", "modifiers", List.of("CTRL"))),
                use("t2", "swing_key", Map.of("key", "INSERT", "modifiers", List.of("SHIFT"))),
                use("t3", "swing_pointer", Map.of("action", "CLICK", "button", "MIDDLE"))), done("r2")));
        Turn turn = service(session, gateway, SessionPolicy.acting().withBasePrimitives(true), "1.00", Map.of())
                .ask("paste");
        assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
        for (int i = 0; i < 3; i++) {
            Turn.Action a = turn.actions().get(i);
            assertNull(a.step(), a.result());
            assertTrue(a.result().contains("clipboard"), a.tool() + ": " + a.result());
        }
        assertTrue(agent.callsTo("swing.key").isEmpty());
        assertTrue(agent.callsTo("swing.pointer").isEmpty());
        assertTrue(steps(session).isEmpty());
    }

    /** A gateway with the provider's token count that counts what it was asked. */
    private static final class Counting implements InferenceGateway {
        final ScriptedGateway scripted;
        int counts;
        int completions;

        Counting(ScriptedGateway scripted) {
            this.scripted = scripted;
        }

        @Override
        public String provider() {
            return scripted.provider();
        }

        @Override
        public java.util.Set<GatewayCapability> capabilities() {
            return scripted.capabilities();
        }

        @Override
        public ModelResponse complete(ModelRequest request) throws InferenceException {
            completions++;
            return scripted.complete(request);
        }

        @Override
        public ai.skillroy.testjava.inference.spi.BatchHandle submitBatch(List<ModelRequest> requests) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ai.skillroy.testjava.inference.spi.BatchStatus batchStatus(
                ai.skillroy.testjava.inference.spi.BatchHandle handle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ai.skillroy.testjava.inference.spi.BatchResult> collectBatch(
                ai.skillroy.testjava.inference.spi.BatchHandle handle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelBatch(ai.skillroy.testjava.inference.spi.BatchHandle handle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countTokens(ModelRequest request) {
            counts++;
            return scripted.countTokens(request);
        }

        @Override
        public void close() {
        }
    }

    private static ModelRequest picture() {
        return ModelRequest.builder("question", ModelClass.SONNET_CLASS).message(Message.user(
                Content.Text.of("is the loan shown?"), new Content.Image("image/png", FakeSessionTools.PNG, null)))
                .build();
    }

    /**
     * Phase 7 security fix S135 (P5 7.2, P7 10): a token count transmits the request, pixels included, so an exhausted
     * meter, a zero budget and a call whose local estimate exceeds what is left are refused before the count: neither
     * {@code countTokens} nor {@code complete} is reached, on {@code visionCall} and on a tool set's {@code complete}.
     */
    @Test
    void noRequestIsCountedOrSentWithoutBudget() throws Exception {
        for (String budget : List.of("exhausted", "0", "0.000001")) {
            Counting gateway = new Counting(ScriptedGateway.sequence(List.of(ScriptedGateway.text("YES"))));
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, budget.equals("exhausted") ? "1.00"
                    : budget, ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, clock);
            SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), clock, new SessionExtensions(List.of(fake), Map.of()));
            if (budget.equals("exhausted")) {
                meter.markExhausted();
            }
            assertThrows(BudgetException.class, () -> service.visionCall(picture()), budget);
            assertEquals(0, gateway.counts, budget + ": the request was counted (sent to the provider)");
            assertEquals(0, gateway.completions, budget);
        }
        Counting gateway = new Counting(ScriptedGateway.sequence(List.of(ScriptedGateway.text("YES"))));
        BudgetMeter roomy = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
        LedgerWriter writer = new LedgerWriter(ledger, roomy, log, PromptMask.keysOnly(), false, clock);
        SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), roomy, writer,
                PriceTable.builtIn(), clock, new SessionExtensions(List.of(fake), Map.of()));
        assertEquals("YES", service.visionCall(picture()).text());
        assertEquals(1, gateway.counts, "with room the provider's count is asked");
    }

    /**
     * Phase 7 security fix S136 (P7 9.1 as built): a tool set's request reaches the provider with its tools, output
     * schema and provider tools masked, as its parts are; a recording masks the output schema too.
     */
    @Test
    void aToolSetsToolsSchemaAndProviderToolsAreMasked() throws Exception {
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(ScriptedGateway.text("NO")));
        SessionService service = service(gateway);
        Map<String, Object> secretProperty = Map.of("type", "object", "properties", Map.of("password",
                Map.of("type", "string", "default", "Hunter-Pw-8812")), "required", List.of(), "additionalProperties",
                false);
        ModelRequest request = ModelRequest.builder("question", ModelClass.SONNET_CLASS)
                .message(Message.user("look"))
                .tools(List.of(new ToolSpec("fake_t", "uses token=" + FakeSessionTools.SECRET, secretProperty, true,
                        false)))
                .outputSchema(Map.of("type", "object", "properties", Map.of("apiKey", Map.of("type", "string",
                        "default", "Key-Default-4471"))))
                .providerTools(List.of(new ai.skillroy.testjava.inference.spi.ProviderTool("claude", "other_tool",
                        Map.of("configs", Map.of("password", "Prov-Pw-0193")))))
                .build();
        service.visionCall(request);
        ModelRequest sent = gateway.requests().get(0);
        String text = String.valueOf(sent.tools()) + sent.outputSchema() + sent.providerTools();
        for (String secret : List.of(FakeSessionTools.SECRET, "Hunter-Pw-8812", "Key-Default-4471", "Prov-Pw-0193")) {
            assertFalse(text.contains(secret), secret + " reached the provider: " + text);
        }
        Map<String, Object> recorded = ai.skillroy.testjava.inference.recorded.Recording.json(request,
                ScriptedGateway.text("NO"), PromptMask.keysOnly(), Instant.parse("2026-10-11T09:00:00Z"));
        assertFalse(String.valueOf(recorded).contains("Key-Default-4471"), "the recording's output schema");
    }

    /**
     * Phase 7 security fix S137 (P7 6.3, 9.1): a tool set's custom tool the request did not list is no tool, though the
     * set would handle its name; nothing is acted.
     */
    @Test
    void anUnlistedCustomToolIsNeverActed() throws Exception {
        fake.unlisted.add("fake_hidden");
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1", use("t1", "fake_hidden", Map.of())),
                done("r2")));
        Turn turn = service(gateway).ask("act");
        assertTrue(turn.actions().get(0).result().contains("there is no tool fake_hidden"),
                turn.actions().get(0).result());
        assertTrue(fake.asked.isEmpty(), "the tool set was never asked to act");
    }

    /**
     * Phase 7 security fix S138 (P7 9.1, 10): a tool set's reads are bounded per turn ({@code maxReadsPerTurn}, here
     * 20 since twice the action limit of 3 is less); the calls past the bound are not run and the turn ends LIMIT.
     */
    @Test
    void aTurnsToolReadsAreBounded() throws Exception {
        List<Content> looks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            looks.add(use("t" + i, "fake_look", Map.of()));
        }
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(tools("r1", looks.toArray(new Content[0])),
                done("r2")));
        SessionPolicy policy = SessionPolicy.acting().withMaxActionsPerTurn(3);
        assertEquals(20, policy.maxReadsPerTurn());
        assertEquals(30, SessionPolicy.acting().withMaxActionsPerTurn(15).maxReadsPerTurn());
        Turn turn = service(session, gateway, policy, "1.00", Map.of()).ask("look and look");
        assertEquals(Turn.Outcome.LIMIT, turn.outcome(), turn.answer());
        assertTrue(turn.answer().contains("read limit of 20"), turn.answer());
        assertEquals(20, fake.asked.size(), "20 reads acted, the rest not run");
    }
}
