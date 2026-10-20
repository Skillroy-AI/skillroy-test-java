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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.EventBus;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.TopicFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Many controllers at once, and a close underneath them: no lost call, no deadlock, no thread left behind. */
class ConcurrencyTest {

    private static final int CLIENTS = 12;
    private static final int CALLS = 40;

    @TempDir
    Path tmp;

    private AgentHarness agent;
    private final ExecutorService clients = Executors.newCachedThreadPool();

    @BeforeEach
    void start() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, "coretest.appThread", "true");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
    }

    @AfterEach
    void stop() {
        clients.shutdownNow();
        agent.close();
    }

    @Test
    void concurrentCallsOnEveryPolicyAllAnswerWithTheirOwnEvents() throws Exception {
        final String[] primitives = {"test.where.caller", "test.where.worker", "test.where.edtSync",
            "test.where.app"};
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
        for (int i = 0; i < CLIENTS; i++) {
            final int client = i;
            futures.add(clients.submit(new Callable<Integer>() {
                @Override
                @SuppressWarnings("unchecked")
                public Integer call() throws Exception {
                    int ok = 0;
                    for (int n = 0; n < CALLS; n++) {
                        String correlation = "c" + client + "-" + n;
                        Map<String, Object> result = agent.result(primitives[(client + n) % primitives.length],
                                AgentHarness.withMeta(null, "correlationId", correlation));
                        List<Object> events = (List<Object>) result.get("events");
                        assertEquals(1, events.size(), "exactly the call's own event");
                        assertEquals(correlation, ((Map<String, Object>) events.get(0)).get("correlationId"));
                        ok++;
                    }
                    return Integer.valueOf(ok);
                }
            }));
        }
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get(120, TimeUnit.SECONDS).intValue();
        }
        assertEquals(CLIENTS * CALLS, total);
        List<Event> all = agent.runtime.context().events().since(0, TopicFilter.parse("test.where"), 100000);
        assertEquals(CLIENTS * CALLS, all.size());
        long previous = 0;
        for (Event event : all) {
            assertTrue(event.seq() > previous, "sequence numbers strictly increase");
            previous = event.seq();
        }
    }

    @Test
    void closingUnderLoadEndsEveryRequestAndLeavesNoThread() throws Exception {
        final AtomicBoolean run = new AtomicBoolean(true);
        final AtomicInteger answered = new AtomicInteger();
        final CountDownLatch busy = new CountDownLatch(CLIENTS);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 0; i < CLIENTS; i++) {
            final int client = i;
            futures.add(clients.submit(new Runnable() {
                @Override
                public void run() {
                    boolean counted = false;
                    while (run.get()) {
                        try {
                            if (client % 3 == 0) {
                                agent.rpc("sync.await", map("topics", "never.published", "since", Long.valueOf(0),
                                        "timeoutMs", Long.valueOf(200)));
                            } else if (client % 3 == 1) {
                                agent.rpc("test.publish", map("n", Long.valueOf(20)));
                            } else {
                                agent.rpc("test.where.app", null);
                            }
                            answered.incrementAndGet();
                        } catch (IOException | RuntimeException | AssertionError closed) {
                            // The agent is going away: refused, reset or a 5xx are all fine.
                        }
                        if (!counted) {
                            counted = true;
                            busy.countDown();
                        }
                    }
                }
            }));
        }
        AgentHarness.Stream events = agent.new Stream("/events");
        HttpURLConnection sse = events.connection;
        assertEquals(200, events.verify());
        final BufferedReader reader = new BufferedReader(new InputStreamReader(sse.getInputStream(),
                AgentHarness.UTF8));
        Future<?> stream = clients.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    while (reader.readLine() != null) {
                        // Drain until the agent ends the stream.
                    }
                } catch (IOException ended) {
                    // A reset is also an end.
                }
            }
        });
        assertTrue(busy.await(60, TimeUnit.SECONDS), "every client got through at least once");

        final int port = agent.runtime.port();
        long start = System.nanoTime();
        agent.runtime.close();
        long closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        run.set(false);
        assertTrue(closeMs < 30000, "close is bounded: " + closeMs + " ms");
        assertTrue(AgentHarness.portClosed(port));
        stream.get(60, TimeUnit.SECONDS);
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        assertTrue(answered.get() >= CLIENTS);
        assertNull(System.getProperty(ai.skillroy.testjava.protocol.Protocol.PROP_PREFIX + "port"));
        AgentHarness.await("agent threads gone", 30000, () -> AgentHarness.skillroyThreads().isEmpty());
        assertEquals(0, tmp.toFile().list().length, "discovery and token files removed");
    }

    /**
     * Phase 8 (phase8-contracts.md 5.1, 13.1 row 15): publishers on several threads with a registered policy, some
     * publishing through the module context and some through calls: every stored event is masked, every seq is its
     * own, no failure is counted.
     */
    @Test
    @SuppressWarnings("unchecked")
    void concurrentPublishersWithAPolicyAllStoreMaskedPayloads() throws Exception {
        final EventBus bus = agent.runtime.context().events();
        AutoCloseable registration = agent.runtime.context().spi().register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                return sourceId.startsWith("event:test.") && ("name".equals(column) || "detail.name".equals(column)
                        || "i".equals(column)) ? "<masked>" : value;
            }
        });
        try {
            List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < CLIENTS; i++) {
                final int client = i;
                futures.add(clients.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        int published = 0;
                        for (int n = 0; n < CALLS; n++) {
                            if (client % 2 == 0) {
                                Map<String, Object> payload = map("name", "client-" + client, "n",
                                        Long.valueOf(n), "detail", map("name", "nested-" + n));
                                assertTrue(bus.publish("test.concurrent", "t", payload) > 0);
                                published++;
                            } else {
                                agent.value("test.publish", map("n", Long.valueOf(2), "topic", "test.burst"));
                                published += 2;
                            }
                        }
                        return Integer.valueOf(published);
                    }
                }));
            }
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get(120, TimeUnit.SECONDS).intValue();
            }
            List<Event> all = bus.since(0, TopicFilter.parse("test.concurrent,test.burst"), 100000);
            assertEquals(total, all.size());
            long previous = 0;
            for (Event event : all) {
                assertTrue(event.seq() > previous, "sequence numbers strictly increase");
                previous = event.seq();
                if ("test.burst".equals(event.topic())) {
                    assertEquals("<masked>", event.payload().get("i"));
                } else {
                    assertEquals("<masked>", event.payload().get("name"));
                    assertEquals("<masked>", ((Map<String, Object>) event.payload().get("detail")).get("name"));
                    assertTrue(event.payload().get("n") instanceof Long, "the rest is kept");
                }
            }
            Map<String, Object> events = (Map<String, Object>) agent.valueMap("agent.info", null).get("events");
            assertEquals(Long.valueOf(1), events.get("maskPolicies"));
            assertEquals(Long.valueOf(0), events.get("maskFailures"));
        } finally {
            registration.close();
        }
        assertEquals(Long.valueOf(0), ((Map<String, Object>) agent.valueMap("agent.info", null).get("events"))
                .get("maskPolicies"), "withdrawn with its handle");
    }
}
