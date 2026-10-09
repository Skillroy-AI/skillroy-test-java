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
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.observe.AwaitRecord;
import ai.skillroy.testjava.scenario.observe.CallEnd;
import ai.skillroy.testjava.scenario.observe.CallStart;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.ScenarioStart;
import ai.skillroy.testjava.scenario.observe.StepStart;
import ai.skillroy.testjava.scenario.observe.ToolCallRecord;
import ai.skillroy.testjava.scenario.observe.TurnRecord;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session service's turns for telemetry (phase6-contracts.md 10.2): {@code ask} opens the turn before the model's
 * first action, so that the steps it takes are the turn's children, and ends it with a {@link TurnRecord} built from
 * the {@code Turn}: its number, outcome, action count, usage sums, model, request id and duration, never its request,
 * answer or an action's input or result.
 */
class SessionTelemetryTest {

    @TempDir
    Path tmp;

    /** The turn hooks and the session steps, in order. */
    private static final class Recording implements RunObserver {

        final List<String> calls = new ArrayList<>();
        final List<TurnRecord> turns = new ArrayList<>();

        @Override
        public void runStarted(RunStart run) {
        }

        @Override
        public void scenarioStarted(ScenarioStart scenario) {
        }

        @Override
        public void scenarioEnded(RunResult result) {
        }

        @Override
        public void bindEvent(String type, Map<String, Object> fields) {
        }

        @Override
        public void scenarioRecord(String type, Map<String, Object> fields) {
        }

        @Override
        public void stepStarted(StepStart step) {
        }

        @Override
        public void stepEnded(StepResult result) {
        }

        @Override
        public void callStarted(CallStart call) {
        }

        @Override
        public void callEnded(CallEnd call) {
        }

        @Override
        public void await(AwaitRecord sync) {
        }

        @Override
        public void modelCall(CostLedger.Entry entry) {
        }

        @Override
        public void sessionStep(StepOutcome outcome) {
            calls.add("sessionStep");
        }

        @Override
        public void sessionTurn(TurnRecord turn) {
            calls.add("sessionTurn");
            turns.add(turn);
        }

        @Override
        public void toolCall(ToolCallRecord record) {
        }

        @Override
        public String currentTraceparent() {
            return null;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public void sessionStepStarted(StepStart step) {
            calls.add("sessionStepStarted " + step.origin());
        }

        @Override
        public void turnStarted(int index) {
            calls.add("turnStarted " + index);
        }
    }

    private static Usage usage(String requestId) {
        return new Usage("scripted", "claude-sonnet-5-5", "claude-sonnet-5-5", requestId, 300, 40, 120, 10, 5, false,
                "0.0010", Duration.ofMillis(250), null);
    }

    @Test
    void aTurnIsOpenedFirstAndEndedWithItsFactsOnly() throws Exception {
        Recording observer = new Recording();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-09T09:00:00Z"));
        CostLedger ledger = new CostLedger();
        try (ExecutorSession session = ExecutorSession.open(new SessionDesk(), SessionDesk.agent(),
                SessionOptions.defaults(tmp.resolve("session")).withClock(clock).withBind(BindOptions.defaults()
                        .withEnvId("test-env").withLedger(ledger)).withId("sess-1").withObserver(observer))) {
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.inMemory(), PromptMask.keysOnly(),
                    false, clock);
            ScriptedGateway gateway = ScriptedGateway.sequence(List.of(
                    new ModelResponse(List.of(new Content.ToolUse("a1", "lending_sign_on", Map.of("staff", "1001"))),
                            ModelResponse.StopReason.TOOL_USE, usage("req-1"), null),
                    new ModelResponse(List.of(new Content.ToolUse("d1", "done", Map.of("answer",
                            "signed on, the secret answer"))), ModelResponse.StopReason.TOOL_USE, usage("req-2"),
                            null)));
            SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), clock);
            Turn turn = service.ask("sign on the person with the request text");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
        }
        assertEquals("turnStarted 1", observer.calls.get(0), "the turn opens before its steps: " + observer.calls);
        assertTrue(observer.calls.indexOf("sessionStepStarted MODEL") > 0, "a model's step: " + observer.calls);
        assertEquals("sessionTurn", observer.calls.get(observer.calls.size() - 1));
        TurnRecord record = observer.turns.get(0);
        assertEquals(1, record.index());
        assertEquals("DONE", record.outcome());
        assertEquals(1, record.actions());
        assertEquals(600L, record.inputTokens());
        assertEquals(80L, record.outputTokens());
        assertEquals(240L, record.cacheReadTokens());
        assertEquals(30L, record.cacheWriteTokens());
        assertEquals("claude-sonnet-5-5", record.model());
        assertFalse(record.toString().contains("request text") || record.toString().contains("secret answer")
                || record.toString().contains("1001"), "never the request, the answer or an action's input");
    }
}
