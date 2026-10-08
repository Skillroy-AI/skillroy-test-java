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
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.fakes.FakeAgent;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 14, the overlay bridge: scripted {@code assist.requested} events through the event
 * seam, each answered by the session service and delivered through the pack's answer primitive with
 * {@code {requestId, answer}}; malformed and excess events dropped and counted; a pack without an overlay refused; the
 * subscription closed with the bridge.
 */
class OverlayBridgeTest {

    @TempDir
    Path tmp;

    private final SessionDesk pack = new SessionDesk();
    private FakeAgent agent;
    private ExecutorSession session;
    private Consumer<Event> consumer;
    private final AtomicBoolean unsubscribed = new AtomicBoolean();
    private long subscribedSince;
    private String subscribedTopics;

    @BeforeEach
    void open() throws Exception {
        agent = SessionDesk.agent();
        session = ExecutorSession.open(pack, agent, SessionOptions.defaults(tmp).withBind(BindOptions.defaults()
                .withEnvId("test-env")).withId("overlay-1"));
    }

    @AfterEach
    void close() {
        session.close();
    }

    private OverlayBridge.EventSubscription events() {
        return (sinceSeq, topics, c) -> {
            subscribedSince = sinceSeq;
            subscribedTopics = topics;
            consumer = c;
            return () -> unsubscribed.set(true);
        };
    }

    private SessionService service(List<ModelResponse> answers) {
        CostLedger ledger = new CostLedger();
        BudgetMeter meter = new BudgetMeter(BudgetMeter.Scope.SESSION, null, new java.math.BigDecimal("1.00"), ledger);
        return new SessionService(session, ScriptedGateway.sequence(answers), SessionPolicy.acting(), meter,
                new LedgerWriter(ledger, meter, null, PromptMask.keysOnly(), false, Clock.systemUTC()),
                PriceTable.builtIn(), null);
    }

    private static ModelResponse done(String answer) {
        return new ModelResponse(List.of(new Content.ToolUse("d", "done", Map.of("answer", answer))),
                ModelResponse.StopReason.TOOL_USE, Usage.none("scripted", "scripted-model"), null);
    }

    private static Event event(long seq, Map<String, Object> payload) {
        return new Event(seq, 1L, OverlayBridge.TOPIC, "overlay", null, null, payload);
    }

    @Test
    void anOverlaysRequestIsAnsweredThroughThePacksPrimitive() throws Exception {
        SessionService service = service(List.of(done("The aerator is on the shelf by the door.")));
        try (OverlayBridge bridge = OverlayBridge.open(service, agent, pack, events(), 41L)) {
            assertEquals(41L, subscribedSince);
            assertEquals("assist.requested", subscribedTopics);
            assertNull(bridge.serveNext(Duration.ZERO), "nothing asked yet");
            consumer.accept(event(42, Map.of("requestId", "rq-7", "text", "where is the lawn aerator?")));
            assertEquals(1, bridge.pending());
            OverlayBridge.Handled handled = bridge.serveNext(Duration.ofMillis(10));
            assertEquals("rq-7", handled.request().requestId());
            assertTrue(handled.answered(), String.valueOf(handled.error()));
            assertEquals(Turn.Outcome.DONE, handled.turn().outcome());
            assertEquals("where is the lawn aerator?", service.turns().get(0).request(), "asked as the person's text");
            List<FakeAgent.Call> answers = agent.callsTo("desk.assistAnswer");
            assertEquals(1, answers.size());
            assertEquals(Map.of("requestId", "rq-7", "answer", "The aerator is on the shelf by the door."),
                    answers.get(0).params());
            assertTrue(answers.get(0).options().timeoutMs() != null, "a bounded call");
        }
        assertTrue(unsubscribed.get(), "the subscription closes with the bridge");
    }

    @Test
    void malformedAndExcessRequestsAreDroppedAndCounted() throws Exception {
        try (OverlayBridge bridge = OverlayBridge.open(service(List.of()), agent, pack, events(), -1L)) {
            consumer.accept(event(1, Map.of("text", "no id")));
            consumer.accept(new Event(2, 1L, "other.topic", "overlay", null, null, Map.of("requestId", "x")));
            assertEquals(1, bridge.dropped(), "an event without a requestId");
            for (int i = 0; i < OverlayBridge.MAX_PENDING + 2; i++) {
                consumer.accept(event(10 + i, Map.of("requestId", "r" + i, "text", "hello")));
            }
            assertEquals(OverlayBridge.MAX_PENDING, bridge.pending(), "bounded");
            assertEquals(3, bridge.dropped());
            consumer.accept(event(99, Map.of("requestId", "blank", "text", "")));
        }
    }

    @Test
    void aRequestWithoutTextIsAnsweredWithoutAModelCall() throws Exception {
        SessionService service = service(List.of());
        try (OverlayBridge bridge = OverlayBridge.open(service, agent, pack, events(), 0L)) {
            consumer.accept(event(5, Map.of("requestId", "empty", "text", "  ")));
            OverlayBridge.Handled handled = bridge.serveNext(Duration.ZERO);
            assertNull(handled.turn());
            assertEquals("The request had no text.", agent.callsTo("desk.assistAnswer").get(0).params()
                    .get("answer"));
            assertTrue(service.turns().isEmpty());
        }
    }

    @Test
    void aPackWithoutAnOverlayIsRefusedAndAClosedBridgeServesNothing() throws Exception {
        pack.answerPrimitive = null;
        assertThrows(IllegalArgumentException.class, () -> OverlayBridge.open(service(List.of()), agent, pack,
                events(), 0L));
        pack.answerPrimitive = "desk.assistAnswer";
        OverlayBridge bridge = OverlayBridge.open(service(List.of()), agent, pack, events(), 0L);
        consumer.accept(event(1, Map.of("requestId", "late", "text", "hello")));
        bridge.close();
        assertNull(bridge.serveNext(Duration.ZERO));
        assertEquals(0, bridge.pending());
        consumer.accept(event(2, Map.of("requestId", "later", "text", "hello")));
        assertEquals(0, bridge.pending(), "a closed bridge takes nothing");
    }
}
