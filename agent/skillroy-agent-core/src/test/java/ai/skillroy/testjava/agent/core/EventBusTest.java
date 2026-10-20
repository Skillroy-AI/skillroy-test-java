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
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.agent.api.spi.RedactionKeys;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.TopicFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring buffer, sequence numbers, filters, await and subscribers; since Phase 8 the mask pass every payload takes
 * before it is stored (phase8-contracts.md 5.1, 13.1 row 15).
 */
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

    /**
     * phase8-contracts.md 13.1 row 36 (8.9): {@code {has: scalar}} holds on a list member equal by text, on an
     * existing and on a future event, and never on a payload value that is not a list.
     */
    @Test
    void awaitWithHasMatchesAListMemberByText() throws Exception {
        final RingEventBus bus = new RingEventBus(100, log);
        bus.publish("input.requested", "t", payload("state", "LOAN", "validInputs", "Key.DEPOSIT"));
        bus.publish("input.requested", "t", payload("state", "DEPOSIT", "validInputs", Arrays.asList("Entry",
                "Key.DEPOSIT", Long.valueOf(2))));
        final Map<String, Object> deposit = payload("validInputs", payload("has", "Key.DEPOSIT"));
        Event existing = bus.await(0, TopicFilter.parse("input.requested"), deposit, 1000);
        assertNotNull(existing);
        assertEquals(2, existing.seq(), "the scalar payload value of seq 1 never matches has");
        assertNotNull(bus.await(0, TopicFilter.parse("input.*"), payload("validInputs", payload("has", "2")), 100),
                "numbers by text");
        assertNull(bus.await(0, TopicFilter.parse("input.*"), payload("validInputs", payload("has", "Key")), 100),
                "no substring meaning");
        assertNull(bus.await(0, TopicFilter.parse("input.*"), payload("validInputs", payload("in", "Entry")), 100),
                "an operator other than has never holds");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Event> waiting = pool.submit(new Callable<Event>() {
                @Override
                public Event call() throws Exception {
                    return bus.await(2, TopicFilter.parse("input.requested"), payload("validInputs",
                            payload("has", "Key.RETURN")), 30000);
                }
            });
            bus.publish("input.requested", "t", payload("validInputs", "Key.RETURN"));
            bus.publish("input.requested", "t", payload("validInputs", Arrays.asList("Key.RETURN")));
            assertEquals(4, waiting.get(30, TimeUnit.SECONDS).seq(), "a future event; the scalar one never");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(RingEventBus.payloadMatches(existing, deposit));
        assertTrue(RingEventBus.payloadMatches(existing, null));
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

    // --- Phase 8 (phase8-contracts.md 5.1, gap 109): the mask pass at publish ---

    /** A bus with the runtime's pass over {@code spi}'s policies and the key rule ({@code redact}: the option). */
    private RingEventBus maskedBus(CoreSpiRegistry spi, String redact, int capacity, long maxChars) {
        return new RingEventBus(capacity, maxChars, log,
                new EventMasks(spi, Redactor.withExtraKeys(redact, spi, log), log));
    }

    private RingEventBus maskedBus(CoreSpiRegistry spi) {
        return maskedBus(spi, null, 64, RingEventBus.DEFAULT_MAX_CHARS);
    }

    private static Event only(RingEventBus bus) {
        List<Event> all = bus.since(0, null, 10);
        assertEquals(1, all.size(), String.valueOf(all));
        return all.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> payload, Object... path) {
        Object current = payload;
        for (Object step : path) {
            current = step instanceof Integer ? ((List<Object>) current).get(((Integer) step).intValue())
                    : ((Map<String, Object>) current).get(step);
        }
        return current;
    }

    /**
     * The source id is {@code event:<topic>}, the column the dotted map keys (a list element adds no segment), the
     * scalars the frozen ones and never null; the policies run in registration order, each on the one before's
     * answer; the envelope is never masked.
     */
    @Test
    void aPolicySeesEveryScalarByTopicAndDottedColumnInRegistrationOrder() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                seen.add(sourceId + " " + column + " " + value.getClass().getSimpleName() + " " + value);
                if ("name".equals(column) || "detail.lines.name".equals(column)) {
                    return "<personal>";
                }
                return value;
            }
        });
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                return "detail.lines.name".equals(column) ? value + "|second" : value;
            }
        });
        RingEventBus bus = maskedBus(spi);
        Map<String, Object> lines0 = payload("name", "Ada");
        Map<String, Object> lines1 = payload("name", "Bo", "qty", Integer.valueOf(2));
        Map<String, Object> published = payload("name", "Cy", "detail", payload("lines", Arrays.asList(lines0,
                lines1), "tags", Arrays.asList("t1")), "buttons", Arrays.asList("OK", "Cancel"), "flag",
                Boolean.TRUE, "ratio", Double.valueOf(0.5), "none", null);
        assertEquals(1, bus.publish("test.topic", "src", published));
        assertEquals(Arrays.asList("event:test.topic name String Cy",
                "event:test.topic detail.lines.name String Ada", "event:test.topic detail.lines.name String Bo",
                "event:test.topic detail.lines.qty Long 2", "event:test.topic detail.tags String t1",
                "event:test.topic buttons String OK", "event:test.topic buttons String Cancel",
                "event:test.topic flag Boolean true", "event:test.topic ratio Double 0.5"), seen,
                "null is not passed; an Integer is seen as the freeze made it");
        Event stored = only(bus);
        assertEquals("<personal>", at(stored.payload(), "name"));
        assertEquals("<personal>|second", at(stored.payload(), "detail", "lines", 0, "name"));
        assertEquals("<personal>|second", at(stored.payload(), "detail", "lines", 1, "name"));
        assertEquals(Long.valueOf(2), at(stored.payload(), "detail", "lines", 1, "qty"));
        assertEquals(Arrays.asList("OK", "Cancel"), stored.payload().get("buttons"));
        assertTrue(stored.payload().containsKey("none"));
        assertNull(stored.payload().get("none"));
        assertEquals("test.topic", stored.topic(), "the envelope is never masked");
        assertEquals("src", stored.source());
        assertEquals(Arrays.asList("name", "detail", "buttons", "flag", "ratio", "none"),
                new ArrayList<String>(stored.payload().keySet()), "the key order is kept");
    }

    /**
     * What a policy may return: null, a String, a finite JDK Number or a Boolean replace the value (null ends the
     * chain); anything else, NaN and an application Number included, is {@code <redacted>}.
     */
    @Test
    void aReturnThatIsNotAStorableScalarIsRedacted() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        final AtomicInteger secondCalls = new AtomicInteger();
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                if ("map".equals(column)) {
                    return Collections.singletonMap("k", "v");
                }
                if ("list".equals(column)) {
                    return Arrays.asList("a");
                }
                if ("object".equals(column)) {
                    return new Object();
                }
                if ("nan".equals(column)) {
                    return Double.valueOf(Double.NaN);
                }
                if ("appNumber".equals(column)) {
                    return new Number() {
                        @Override
                        public int intValue() {
                            return 1;
                        }

                        @Override
                        public long longValue() {
                            return 1L;
                        }

                        @Override
                        public float floatValue() {
                            return 1f;
                        }

                        @Override
                        public double doubleValue() {
                            return 1d;
                        }
                    };
                }
                if ("nulled".equals(column)) {
                    return null;
                }
                if ("typed".equals(column)) {
                    return Integer.valueOf(7);
                }
                return value;
            }
        });
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                secondCalls.incrementAndGet();
                return value;
            }
        });
        RingEventBus bus = maskedBus(spi);
        assertEquals(1, bus.publish("t", "s", payload("map", "x", "list", "x", "object", "x", "nan", "x",
                "appNumber", "x", "nulled", "x", "typed", "x", "kept", "x")));
        Map<String, Object> stored = only(bus).payload();
        for (String key : Arrays.asList("map", "list", "object", "nan", "appNumber")) {
            assertEquals("<redacted>", stored.get(key), key);
        }
        assertTrue(stored.containsKey("nulled"));
        assertNull(stored.get("nulled"));
        assertEquals(Long.valueOf(7), stored.get("typed"), "the type may change; the freeze normalises it");
        assertEquals("x", stored.get("kept"));
        assertEquals(2, secondCalls.get(), "the second policy saw typed and kept only: a redaction or null ends it");
    }

    /**
     * A policy that throws turns the scalar into {@code <redacted>}, is counted and logged once per class with no
     * value in the line; the event is published and its seq returned. A {@link StackOverflowError} is caught like
     * an exception; any other {@link VirtualMachineError} drops the event as an unfreezable payload is dropped.
     */
    @Test
    void aThrowingPolicyRedactsTheValueIsLoggedOnceAndNeverFailsThePublish() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        final String[] mode = {"exception"};
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                if (!"bad".equals(column)) {
                    return value;
                }
                if ("overflow".equals(mode[0])) {
                    throw new StackOverflowError();
                }
                if ("oom".equals(mode[0])) {
                    throw new OutOfMemoryError("test");
                }
                throw new IllegalStateException("cannot mask " + value);
            }
        });
        RingEventBus bus = maskedBus(spi);
        assertEquals(1, bus.publish("t", "s", payload("bad", "planted-4711", "good", "g")));
        assertEquals(2, bus.publish("t", "s", payload("bad", "planted-4712")));
        mode[0] = "overflow";
        assertEquals(3, bus.publish("t", "s", payload("bad", "planted-4713")));
        List<Event> all = bus.since(0, null, 10);
        assertEquals(3, all.size());
        for (Event event : all) {
            assertEquals("<redacted>", event.payload().get("bad"));
        }
        assertEquals("g", all.get(0).payload().get("good"), "the other scalars are kept");
        assertEquals(3L, bus.masks().maskFailures());
        String lines = String.valueOf(log.tail(100));
        assertEquals(1, count(lines, "MaskPolicy "), "logged once per policy class: " + lines);
        assertTrue(lines.contains("IllegalStateException") && lines.contains("event:t"), lines);
        assertFalse(lines.contains("planted-471"), "the line holds no value: " + lines);
        mode[0] = "oom";
        assertEquals(-1, bus.publish("t", "s", payload("bad", "planted-4714")));
        assertEquals(3, bus.lastSeq(), "a VirtualMachineError drops the event and consumes no seq");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            n++;
        }
        return n;
    }

    /** No policy and no secret key: the walk changes nothing and copies nothing. */
    @Test
    void withNothingToMaskThePayloadIsStoredAsPublishedAndNotCopied() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        RingEventBus bus = maskedBus(spi);
        Map<String, Object> published = payload("name", "Ada", "detail", payload("lines", Arrays.asList(
                payload("code", "T-1"))), "n", Long.valueOf(3));
        bus.publish("t", "s", published);
        assertEquals(published, only(bus).payload());
        EventMasks masks = new EventMasks(spi, Redactor.withExtraKeys(null, spi, log), log);
        Map<String, Object> frozen = new Event(0L, 0L, "t", "s", null, null, published).payload();
        assertSame(frozen, masks.apply("t", frozen), "no change, no copy");
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                return value;
            }
        });
        assertSame(frozen, masks.apply("t", frozen), "a policy that changes nothing copies nothing");
        Map<String, Object> redactedAlready = new Event(0L, 0L, "t", "s", null, null,
                payload("password", "<redacted>")).payload();
        assertSame(redactedAlready, masks.apply("t", redactedAlready), "an encoder's redaction passes unchanged");
        assertSame(frozen, EventMasks.NONE.apply("t", frozen));
    }

    /**
     * The masked payload is the one object stored, delivered to a subscription, returned by since and await,
     * matched by the payload match and recorded for the call.
     */
    @Test
    void everyReaderSeesTheOneMaskedEvent() throws Exception {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                return "event:staff.signedOn".equals(sourceId) && "name".equals(column) ? "<personal>" : value;
            }
        });
        RingEventBus bus = maskedBus(spi);
        final List<Event> delivered = Collections.synchronizedList(new ArrayList<Event>());
        bus.subscribe(null, new EventSink() {
            @Override
            public void onEvent(Event event) {
                delivered.add(event);
            }
        });
        CallScope scope = new CallScope("c-1", null);
        CallScope previous = CallScope.bind(scope);
        try {
            assertEquals(1, bus.publish("staff.signedOn", "lending", payload("code", "S1", "name", "Ada Planted")));
        } finally {
            CallScope.restore(previous);
        }
        Event stored = only(bus);
        assertEquals(payload("code", "S1", "name", "<personal>"), stored.payload());
        assertSame(stored, delivered.get(0), "a subscription (the SSE stream among them)");
        assertSame(stored, scope.close().get(0), "the call's copy");
        assertSame(stored, bus.await(0, null, null, 0), "await");
        assertSame(stored, bus.await(0, TopicFilter.parse("staff.*"), payload("name", "<personal>"), 0),
                "the match sees the masked value");
        assertNull(bus.await(0, TopicFilter.parse("staff.*"), payload("name", "Ada Planted"), 0),
                "and never the published one");
        assertTrue(RingEventBus.payloadMatches(stored, payload("code", "S1", "name", "<personal>")));
        assertFalse(RingEventBus.payloadMatches(stored, payload("name", "Ada Planted")));
    }

    /** The character bound runs after the pass: a policy that lengthens a value is sized after it did. */
    @Test
    void theCharacterBoundIsHonouredAfterMasking() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        final StringBuilder longer = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longer.append('p');
        }
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                return "detail".equals(column) ? longer.toString() : value;
            }
        });
        RingEventBus bus = maskedBus(spi, null, 100, 1000);
        bus.publish("web.tap", "t", payload("detail", "x", "n", Long.valueOf(1)));
        Event cut = only(bus);
        assertEquals(Boolean.TRUE, cut.payload().get("truncated"));
        assertTrue(((String) cut.payload().get("detail")).length() <= 64, "cut to 1/64 of the bound");
        assertEquals(Long.valueOf(1), cut.payload().get("n"));
        assertTrue(bus.chars() <= 1000, String.valueOf(bus.chars()));
    }

    /**
     * At most 10,000 scalars reach the policies; the rest is {@code <redacted>}, the payload says {@code truncated}
     * and {@code overBudget} counts it. Without a policy nothing is visited and nothing is cut.
     */
    @Test
    void theScalarBudgetRedactsTheRestAndMarksThePayload() {
        List<Object> many = new ArrayList<Object>();
        for (int i = 0; i < EventMasks.MAX_SCALARS + 5; i++) {
            many.add("v" + i);
        }
        CoreSpiRegistry spi = new CoreSpiRegistry();
        RingEventBus plain = maskedBus(spi);
        plain.publish("t", "s", payload("items", many));
        assertEquals(many, only(plain).payload().get("items"), "no policy: nothing visited, nothing cut");
        assertEquals(0L, plain.masks().overBudget());

        final AtomicInteger calls = new AtomicInteger();
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                calls.incrementAndGet();
                return value;
            }
        });
        RingEventBus bus = maskedBus(spi);
        bus.publish("t", "s", payload("items", many));
        @SuppressWarnings("unchecked")
        List<Object> stored = (List<Object>) only(bus).payload().get("items");
        assertEquals(EventMasks.MAX_SCALARS, calls.get());
        assertEquals("v9999", stored.get(EventMasks.MAX_SCALARS - 1));
        for (int i = EventMasks.MAX_SCALARS; i < stored.size(); i++) {
            assertEquals("<redacted>", stored.get(i), "beyond the budget: " + i);
        }
        assertEquals(Boolean.TRUE, only(bus).payload().get("truncated"));
        assertEquals(1L, bus.masks().overBudget());
    }

    /**
     * {@code slowPasses} and {@code maxPassMs}: a policy that takes 70 ms is a slow pass, and while it runs another
     * publisher completes, because the pass holds no ring lock.
     */
    @Test
    void aSlowPolicyIsCountedAndHoldsNoRingLock() throws Exception {
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        CoreSpiRegistry spi = new CoreSpiRegistry();
        spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                if ("event:slow".equals(sourceId)) {
                    long start = System.nanoTime();
                    inside.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                        while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(70)) {
                            Thread.sleep(5);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return value;
            }
        });
        final RingEventBus bus = maskedBus(spi);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Long> slow = pool.submit(new Callable<Long>() {
                @Override
                public Long call() {
                    return Long.valueOf(bus.publish("slow", "s", payload("v", "x")));
                }
            });
            assertTrue(inside.await(30, TimeUnit.SECONDS));
            assertEquals(1, bus.publish("fast", "s", payload("v", "y")), "published while the slow pass runs");
            assertFalse(slow.isDone());
            release.countDown();
            assertEquals(Long.valueOf(2), slow.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, bus.masks().slowPasses());
        assertTrue(bus.masks().maxPassMs() >= 70, String.valueOf(bus.masks().maxPassMs()));
        Map<String, Object> json = bus.masks().toJson();
        assertEquals(Arrays.asList("maskPolicies", "maskFailures", "overBudget", "slowPasses", "maxPassMs"),
                new ArrayList<String>(json.keySet()));
        assertEquals(Long.valueOf(1), json.get("maskPolicies"));
        assertEquals(Long.valueOf(1), json.get("slowPasses"));
    }

    /**
     * The key rule (D540), with no policy registered: a key the protocol's rule, the {@code redact} option or a
     * registered {@code RedactionKeys} calls secret is {@code <redacted>}, a map or list under it one string; a
     * neutral key keeps its value even where the value looks like a secret (A34's by-value rule stays out).
     */
    @Test
    void theKeyRuleRedactsSecretKeysWhateverTheirValue() {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        spi.register(RedactionKeys.class, new RedactionKeys() {
            @Override
            public Pattern secretKeys() {
                return Pattern.compile("^pin$");
            }
        });
        RingEventBus bus = maskedBus(spi, "memberNo", 64, RingEventBus.DEFAULT_MAX_CHARS);
        bus.publish("t", "s", payload("password", "hunter2", "nested", payload("apiKey", payload("a",
                Long.valueOf(1)), "keep", "k"), "lines", Arrays.asList(payload("token", Arrays.asList("x", "y"))),
                "memberNo", Long.valueOf(42), "pin", "1234", "empty", payload("secret", null), "echo", "hunter2"));
        Map<String, Object> stored = only(bus).payload();
        assertEquals("<redacted>", stored.get("password"), "the protocol's rule");
        assertEquals("<redacted>", at(stored, "nested", "apiKey"), "a map under a secret key is one string");
        assertEquals("k", at(stored, "nested", "keep"));
        assertEquals("<redacted>", at(stored, "lines", 0, "token"), "inside a list, a list under it");
        assertEquals("<redacted>", stored.get("memberNo"), "the redact option");
        assertEquals("<redacted>", stored.get("pin"), "a registered RedactionKeys");
        assertEquals("<redacted>", at(stored, "empty", "secret"), "whatever its value, null included");
        assertEquals("hunter2", stored.get("echo"), "by key only, never by value");
        assertEquals(0L, bus.masks().maskFailures());
        assertEquals(0L, bus.masks().policies());
    }
}
