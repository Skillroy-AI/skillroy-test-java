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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 39: the base fact root {@code events} (8.6, decisions D602, D603) against the executor
 * tests' fake agent: the window after {@code since}, oldest first, the entry shape without {@code traceparent}, paging
 * from each full page's last {@code seq}, every loss a failed read (the first page's {@code oldestSeq}, a gap while
 * paging, a tenth full page), an {@code evt.query} error, a pack root of the same name hiding it in a run and in a
 * session, a session's window from its start mark and {@link BaseFacts#all()}'s from 0.
 */
class EventsRootTest {

    private final FakeAgentConnection agent = new FakeAgentConnection();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        assertTrue(value instanceof List, String.valueOf(value));
        return (List<Map<String, Object>>) value;
    }

    private Object read(long since) throws Exception {
        return BaseFacts.events(() -> since).fetch(agent);
    }

    private String failure(long since) {
        return assertThrows(IllegalStateException.class, () -> read(since)).getMessage();
    }

    @Test
    void theWindowAfterSinceOldestFirstWithoutTraceparent() throws Exception {
        agent.publish("trace.mark", Map.of("label", "skillroy.scenario.start"));
        agent.publish("input.acked", Map.of("source", "Barcode"));
        agent.publish("input.acked", Map.of("source", "Key.SIGN_OFF"));
        List<Map<String, Object>> events = list(read(1));
        assertEquals(List.of(2L, 3L), events.stream().map(e -> e.get("seq")).toList(), "after since, oldest first");
        assertEquals(List.of("seq", "ts", "topic", "source", "correlationId", "payload"),
                List.copyOf(events.get(0).keySet()));
        assertEquals(Map.of("source", "Barcode"), events.get(0).get("payload"));
        assertEquals("input.acked", events.get(1).get("topic"));
        assertEquals(List.of(), list(read(3)), "none since the last");
        assertEquals(3, list(BaseFacts.all().get(7).fetch(agent)).size(), "the class's own provider reads from 0");
        assertEquals("events", BaseFacts.all().get(7).root());
        Map<?, ?> params = agent.callsTo("evt.query").get(0).params();
        assertEquals(Map.of("since", 1L, "limit", 1000L), params, "no topics: a page's sequence is contiguous");
        assertEquals(10_000L, agent.callsTo("evt.query").get(0).options().timeoutMs(), "the root's 10 s READ");
    }

    @Test
    void itPagesFromEachFullPagesLastSeq() throws Exception {
        for (int i = 0; i < 2500; i++) {
            agent.publish("toy.tick", Map.of("n", (long) i));
        }
        List<Map<String, Object>> events = list(read(0));
        assertEquals(2500, events.size());
        assertEquals(1L, events.get(0).get("seq"));
        assertEquals(2500L, events.get(2499).get("seq"));
        assertEquals(List.of(0L, 1000L, 2000L), agent.callsTo("evt.query").stream().map(c -> c.params().get("since"))
                .toList(), "three calls, the cursor from each full page's last seq");
    }

    @Test
    void anEvictedStartFailsTheReadNamingTheRangeAndTheOption() {
        agent.on("evt.query", params -> page(List.of(event(20), event(21)), 20L, 21L));
        assertEquals("the ring evicted events 11..19 since the run's start; raise the agent option eventRing",
                failure(10));
        agent.on("evt.query", params -> page(List.of(), 25L, 24L));
        assertEquals("the ring evicted events 11..24 since the run's start; raise the agent option eventRing",
                failure(10), "an emptied ring past since");
    }

    @Test
    void aGapWhilePagingFailsTheRead() {
        agent.on("evt.query", params -> {
            long since = ((Number) params.get("since")).longValue();
            List<Map<String, Object>> events = new ArrayList<>();
            long first = since == 0 ? 1 : since + 50;
            for (long seq = first; seq < first + (since == 0 ? 1000 : 10); seq++) {
                events.add(event(seq));
            }
            return page(events, 1L, first + 2000);
        });
        assertEquals("the ring evicted events 1001..1049 since the run's start; raise the agent option eventRing",
                failure(0));
        agent.on("evt.query", params -> page(List.of(event(1), event(3)), 1L, 3L));
        assertEquals("the ring evicted events 2..2 since the run's start; raise the agent option eventRing",
                failure(0), "a gap inside a page");
    }

    @Test
    void aTenthFullPageFailsTheRead() {
        agent.on("evt.query", params -> {
            long since = ((Number) params.get("since")).longValue();
            List<Map<String, Object>> events = new ArrayList<>();
            for (long seq = since + 1; seq <= since + 1000; seq++) {
                events.add(event(seq));
            }
            return page(events, 1L, since + 1000);
        });
        assertEquals("more than 10000 events since the run's start: the events root cannot see them all; raise"
                + " eventRing or shorten the scenario", failure(0));
        assertEquals(10, agent.callsTo("evt.query").size(), "ten pages at most");
    }

    @Test
    void anEvtQueryErrorFailsTheRead() {
        agent.error("evt.query", ErrorCodes.INTERNAL_ERROR, "ring broken");
        assertThrows(AgentRpcException.class, () -> read(0));
        agent.value("evt.query", Map.of("lastSeq", 1L));
        assertEquals("evt.query answered no events list", failure(0));
    }

    private static Map<String, Object> event(long seq) {
        return new Event(seq, 1000L + seq, "toy.tick", "fake", null, "00-x", Map.of("n", seq)).toJson();
    }

    private static Map<String, Object> page(List<Map<String, Object>> events, long oldest, long last) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("events", events);
        value.put("lastSeq", last);
        value.put("oldestSeq", oldest);
        return value;
    }

    // ------------------------------------------------------------------------------------------- in a run

    private final ToyPack pack = new ToyPack();
    private final TestClock clock = new TestClock();

    private Plan plan(String steps) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n" + steps).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
    }

    private RunResult run(Plan plan) {
        return new PlanExecutor(pack, agent, RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withStepTimeout(java.time.Duration.ofSeconds(1))).run(plan);
    }

    @Test
    void aRunsEventsAreThoseAfterItsStartMark() throws Exception {
        agent.publish("toy.done", Map.of("id", "earlier run"));
        agent.on("toy.poke", params -> {
            agent.publish("toy.done", Map.of("id", "this run"));
            return Map.of();
        });
        agent.value("swing.settle", Map.of("settled", true, "ms", 1L));
        RunResult result = run(plan("""
                  - poke: {}
                  - expect:
                      "events[*topic=toy.done]": { size: 1 }
                      "events[*topic=toy.done][0].payload.id": this run
                      "events[*topic=trace.mark]": { size: 0 }
                """));
        assertEquals(Status.PASS, result.status(), result.message());
    }

    @Test
    void anEvictedWindowIsAnEnvironmentErrorNotAPass() throws Exception {
        agent.value("toy.poke", Map.of());
        FakeAgentConnection.Handler bus = agent.handler("evt.query");
        agent.on("evt.query", params -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = new LinkedHashMap<>((Map<String, Object>) bus.handle(params));
            value.put("oldestSeq", ((Number) params.get("since")).longValue() + 5);
            return value;
        });
        RunResult result = run(plan("  - poke: {}\n  - expect: { \"events[*topic=toy.done]\": { size: 0 } }\n"));
        assertEquals(Status.ERROR, result.status(), result.message());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertTrue(result.message().startsWith("fact 'events' could not be read: the ring evicted events "),
                result.message());
        assertEquals("s2", result.failedStepId());
    }

    @Test
    void aPackRootNamedEventsHidesTheBaseRoot(@TempDir Path dir) throws Exception {
        pack.facts = List.of(constant("events", List.of(Map.of("topic", "pack.event"))), constant("loan", Map.of()));
        agent.value("toy.poke", Map.of());
        RunResult result = run(plan("  - poke: {}\n  - expect: { \"events[*topic=pack.event]\": { size: 1 } }\n"));
        assertEquals(Status.PASS, result.status(), result.message());
        assertTrue(agent.callsTo("evt.query").stream().noneMatch(c -> Long.valueOf(1000L).equals(c.params().get(
                "limit")) && !c.params().containsKey("topics")), "the base root was never read");
        SessionFixture fx = new SessionFixture(dir);
        fx.pack.facts = pack.facts;
        try (ExecutorSession session = fx.open()) {
            CheckOutcome check = session.check("events[*topic=pack.event]", Map.of("size", 1L), Origin.person("p"));
            assertTrue(check.held(), check.reason());
        }
    }

    @Test
    void aSessionsWindowStartsAtItsStartMark(@TempDir Path dir) throws Exception {
        SessionFixture fx = new SessionFixture(dir);
        fx.agent.publish("toy.done", Map.of("id", "before the session"));
        try (ExecutorSession session = fx.open()) {
            fx.agent.publish("toy.done", Map.of("id", "in the session"));
            CheckOutcome one = session.check("events[*topic=toy.done]", Map.of("size", 1L), Origin.person("p"));
            assertTrue(one.held(), one.reason());
            assertEquals("in the session", session.fact("events", "[*topic=toy.done][0].payload.id"));
            assertFalse(session.check("events[*topic=trace.mark]", Map.of("size", Map.of("gt", 0L)),
                    Origin.person("p")).held(), "the session's own start mark is not in its window");
        }
    }

    private static FactProvider constant(String root, Object value) {
        return new FactProvider() {
            @Override
            public String root() {
                return root;
            }

            @Override
            public Object fetch(AgentConnection agent) {
                return value;
            }
        };
    }
}
