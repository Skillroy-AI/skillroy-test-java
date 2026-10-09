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

import ai.skillroy.testjava.agent.api.EventSink;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.TopicFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ring buffer, sequence numbers, filters, await and subscribers. */
class EventBusTest {

    private final RingLog log = new RingLog();

    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    void sequenceStartsAtOneAndTheRingKeepsTheNewest() {
        RingEventBus bus = new RingEventBus(8, log);
        assertEquals(0, bus.lastSeq());
        assertEquals(1, bus.oldestSeq(), "empty ring: the next seq");
        assertEquals(1, bus.publish("a.one", "t", null));
        for (int i = 2; i <= 20; i++) {
            assertEquals(i, bus.publish(i % 2 == 0 ? "a.even" : "b.odd", "t", payload("i", Integer.valueOf(i))));
        }
        assertEquals(20, bus.lastSeq());
        assertEquals(13, bus.oldestSeq());
        List<Event> all = bus.since(0, null, 100);
        assertEquals(8, all.size());
        assertEquals(13, all.get(0).seq());
        assertEquals(20, all.get(7).seq());
        List<Event> even = bus.since(14, TopicFilter.parse("a.*"), 2);
        assertEquals(2, even.size());
        assertEquals(16, even.get(0).seq());
        assertEquals(18, even.get(1).seq());
        assertTrue(bus.since(20, null, 10).isEmpty());
        assertTrue(bus.since(0, null, 0).isEmpty());
        assertEquals(Long.valueOf(20), bus.since(19, null, 1).get(0).payload().get("i"));
    }

    /**
     * Security fix S43 (P1 2.4 {@code eventRingChars}): the ring is bounded by its payloads' characters as well as by
     * count: the oldest events leave first, never the newest; one payload over the bound alone is cut and marked.
     */
    @Test
    void theRingIsBoundedByCharactersAsWellAsByCount() throws Exception {
        RingEventBus bus = new RingEventBus(1000, 1000, log);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            text.append('d');
        }
        for (int i = 1; i <= 10; i++) {
            assertEquals(i, bus.publish("web.tap", "t", payload("detail", text.toString())));
        }
        assertTrue(bus.chars() <= 1000, "the total stays under the bound: " + bus.chars());
        assertEquals(10, bus.lastSeq());
        assertEquals(8, bus.oldestSeq(), "the oldest went first");
        List<Event> kept = bus.since(0, null, 100);
        assertEquals(3, kept.size());
        assertEquals(8, kept.get(0).seq());
        assertEquals(kept.get(0), bus.await(0, null, null, 0), "a waiter sees the oldest kept event");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append('h');
        }
        long seq = bus.publish("web.tap", "t", payload("detail", huge.toString(), "n", Long.valueOf(1)));
        Event cut = bus.since(seq - 1, null, 1).get(0);
        assertEquals(Boolean.TRUE, cut.payload().get("truncated"));
        assertTrue(((String) cut.payload().get("detail")).length() <= 64, "cut to 1/64 of the bound");
        assertEquals(Long.valueOf(1), cut.payload().get("n"), "the other values are kept");
        assertTrue(bus.chars() <= 1000);
        assertEquals(seq, bus.oldestSeq() + bus.since(0, null, 100).size() - 1);
        assertEquals(64L << 20, RingEventBus.DEFAULT_MAX_CHARS);
    }

    @Test
    void concurrentPublishersGetUniqueSequenceNumbersAndSubscribersSeeThemInOrder() throws Exception {
        final RingEventBus bus = new RingEventBus(64, log);
        final List<Long> seen = new ArrayList<Long>();
        bus.subscribe(null, new EventSink() {
            @Override
            public void onEvent(Event event) {
                seen.add(Long.valueOf(event.seq()));   // called under the bus lock: no synchronisation needed
            }
        });
        final int threads = 8;
        final int each = 2000;
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final java.util.Set<Long> returned = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());
        List<Thread> publishers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < each; i++) {
                        returned.add(Long.valueOf(bus.publish("load", "t", null)));
                        if (i % 97 == 0) {
                            List<Event> window = bus.since(bus.lastSeq() - 10, null, 5);
                            for (int k = 1; k < window.size(); k++) {
                                if (window.get(k).seq() != window.get(k - 1).seq() + 1) {
                                    returned.add(Long.valueOf(-999));
                                }
                            }
                        }
                    }
                }
            }, "publisher-" + t);
            publishers.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : publishers) {
            thread.join(60000);
        }
        assertEquals(threads * each, returned.size(), "every publish got its own seq");
        assertFalse(returned.contains(Long.valueOf(-999)), "since() never returned a torn window");
        assertEquals(threads * each, bus.lastSeq());
        assertEquals(threads * each, seen.size());
        for (int i = 0; i < seen.size(); i++) {
            assertEquals(i + 1L, seen.get(i).longValue(), "subscribers are called in sequence order");
        }
        assertEquals(threads * each - 63L, bus.oldestSeq());
    }

    @Test
    void publishNeverThrows() {
        RingEventBus bus = new RingEventBus(4, log);
        assertEquals(-1, bus.publish("bad", "t", payload("x", Double.valueOf(Double.NaN))));
        Map<String, Object> cycle = new LinkedHashMap<String, Object>();
        cycle.put("self", cycle);
        assertEquals(-1, bus.publish("bad", "t", cycle));
        assertEquals(-1, bus.publish(null, "t", null));
        assertEquals(0, bus.lastSeq(), "a rejected event consumes no seq");
        assertEquals(1, bus.publish("good", "t", null));
        assertTrue(String.valueOf(log.tail(10)).contains("dropped"));
    }

    @Test
    void awaitReturnsAnExistingOrFutureMatchAndNullOnTimeout() throws Exception {
        final RingEventBus bus = new RingEventBus(100, log);
        bus.publish("x.one", "t", payload("k", "a"));
        bus.publish("x.one", "t", payload("k", Integer.valueOf(2)));
        Event existing = bus.await(0, TopicFilter.parse("x.*"), Collections.<String, Object>singletonMap("k", "2"),
                1000);
        assertNotNull(existing);
        assertEquals(2, existing.seq(), "String.valueOf equality");
        assertNull(bus.await(2, TopicFilter.parse("x.*"), null, 100));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Event> waiting = pool.submit(new Callable<Event>() {
                @Override
                public Event call() throws Exception {
                    return bus.await(2, TopicFilter.parse("y.two"), payload("ok", Boolean.TRUE), 30000);
                }
            });
            bus.publish("y.two", "t", payload("ok", Boolean.FALSE));
            bus.publish("z.other", "t", null);
            bus.publish("y.two", "t", payload("ok", Boolean.TRUE));
            Event event = waiting.get(30, TimeUnit.SECONDS);
            assertEquals(5, event.seq());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void subscribersSeeEventsInOrderAndAFailingSinkHarmsNobody() throws Exception {
        RingEventBus bus = new RingEventBus(16, log);
        final List<Long> seen = Collections.synchronizedList(new ArrayList<Long>());
        AutoCloseable failing = bus.subscribe(null, new EventSink() {
            @Override
            public void onEvent(Event event) {
                throw new IllegalStateException("sink broke");
            }
        });
        AutoCloseable recording = bus.subscribe(TopicFilter.parse("keep.*"), new EventSink() {
            @Override
            public void onEvent(Event event) {
                seen.add(Long.valueOf(event.seq()));
            }
        });
        bus.publish("keep.a", "t", null);
        bus.publish("drop.b", "t", null);
        bus.publish("keep.c", "t", null);
        recording.close();
        bus.publish("keep.d", "t", null);
        failing.close();
        assertEquals(java.util.Arrays.asList(Long.valueOf(1), Long.valueOf(3)), seen);
        assertEquals(4, bus.lastSeq());
    }

    @Test
    void eventsPublishedInACallScopeAreStampedAndRecorded() {
        RingEventBus bus = new RingEventBus(16, log);
        CallScope scope = new CallScope("c-9", "00-tp");
        CallScope previous = CallScope.bind(scope);
        try {
            bus.publish("in.scope", "t", null);
        } finally {
            CallScope.restore(previous);
        }
        bus.publish("outside", "t", null);
        List<Event> recorded = scope.close();
        assertEquals(1, recorded.size());
        assertEquals("c-9", recorded.get(0).correlationId());
        assertEquals("00-tp", recorded.get(0).traceparent());
        assertNull(bus.since(1, null, 10).get(0).correlationId());
        CallScope.bind(scope);
        try {
            bus.publish("after.close", "t", null);
        } finally {
            CallScope.restore(null);
        }
        assertEquals(0, scope.close().size() - 1, "a closed scope records nothing more");
    }
}
