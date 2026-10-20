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

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.EventBus;
import ai.skillroy.testjava.agent.api.EventSink;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PayloadMatch;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.text.TextBounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@link EventBus}: an array ring of the last {@code eventRing} events with sequence numbers from 1. Waiters use a
 * lock condition (no polling). Subscribers are called in sequence order under the lock and must not block.
 *
 * <p>The ring is bounded by the total characters of its payloads as well ({@code eventRingChars}, 64 Mi by default;
 * security fix S43, P1 2.4): when a new event takes the total over the bound, the oldest events leave the ring first
 * (never the new one), so {@link #oldestSeq()} may be more than {@code eventRing} behind {@link #lastSeq()}. A payload
 * is sized as {@link TextBounds#size} estimates it; one whose own size exceeds the bound is published with each of
 * its strings cut to 1/64 of the bound and {@code truncated: true}, or, when that is still over, as
 * {@code {truncated: true}} alone. A page that fires a tapped event in a loop can then fill the ring with at most the
 * bound, not with ten thousand times the largest payload.
 *
 * <p>{@link #publish} never throws into the caller (it may be an application thread or the EDT): a payload that
 * cannot be represented (NaN, a cycle) is logged and the call returns -1.
 *
 * <p>An event is stored, delivered and recorded for its call with the payload it was published with, also under a
 * call that named secret parameters (contract amendment A34): the payload is what the call did, not what the core
 * writes about it, and waits, queries, captures and subscriptions must see the values the target published. Only the
 * correlation id and traceparent of the call are stamped on it.
 *
 * <p>Since Phase 8 (phase8-contracts.md 5.1, gap 109, D539) the payload first passes the {@link EventMasks}: every
 * registered {@code MaskPolicy} as {@code event:<topic>} and the core's secret-key rule, on the publishing thread,
 * after the freeze and before the character bound, outside the lock. The masked payload is the one object that is
 * stored, delivered, returned by {@link #since} and {@link #await}, matched by {@link #payloadMatches} and recorded for
 * the call; with nothing to mask it is the frozen payload itself. A34's by-value rule stays out of events.
 */
final class RingEventBus implements EventBus {

    private static final TopicFilter ALL = TopicFilter.parse(null);

    /** The default of {@code eventRingChars}: 64 Mi characters. */
    static final long DEFAULT_MAX_CHARS = 64L << 20;

    private final Event[] ring;
    /** The size of the event in each slot of {@link #ring}; 0 for a slot left empty by the character bound. */
    private final long[] sizes;
    private final long maxChars;
    private final AgentLog log;
    private final EventMasks masks;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition published = lock.newCondition();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<Subscription>();
    private long lastSeq;
    /** The last sequence number the character bound took out of the ring (0: none). */
    private long evictedThrough;
    /** The characters of payload in the ring. */
    private long chars;

    RingEventBus(int capacity, AgentLog log) {
        this(capacity, DEFAULT_MAX_CHARS, log);
    }

    /** A bus without the mask pass ({@link EventMasks#NONE}): the payload is stored as published (tests). */
    RingEventBus(int capacity, long maxChars, AgentLog log) {
        this(capacity, maxChars, log, EventMasks.NONE);
    }

    /**
     * The agent's bus: every payload passes {@code masks} before it is stored (phase8-contracts.md 5.1).
     *
     * @param masks the pass (null: {@link EventMasks#NONE})
     */
    RingEventBus(int capacity, long maxChars, AgentLog log, EventMasks masks) {
        if (capacity < 1) {
            throw new IllegalArgumentException("event ring size must be positive: " + capacity);
        }
        if (maxChars < 1) {
            throw new IllegalArgumentException("event ring character bound must be positive: " + maxChars);
        }
        this.ring = new Event[capacity];
        this.sizes = new long[capacity];
        this.maxChars = maxChars;
        this.log = log;
        this.masks = masks == null ? EventMasks.NONE : masks;
    }

    @Override
    public long publish(String topic, String source, Map<String, Object> payload) {
        return publish(topic, source, payload, null);
    }

    /**
     * phase8-contracts.md 7.4 (amendment A84): stamps {@code givenCorrelationId}, or the calling thread's current one
     * when it is null; the traceparent of the thread's call only when the event belongs to that call. The same pass,
     * bound and ring as the three-argument form; recorded for the thread's call as that form is.
     */
    @Override
    public long publish(String topic, String source, Map<String, Object> payload, String givenCorrelationId) {
        try {
            if (topic == null || topic.isEmpty()) {
                log.warn("event without topic dropped (source " + source + ")");
                return -1L;
            }
            CallScope scope = CallScope.current();
            String correlationId = givenCorrelationId != null ? givenCorrelationId
                    : scope == null ? null : scope.correlationId;
            String traceparent = scope == null || (givenCorrelationId != null
                    && !givenCorrelationId.equals(scope.correlationId)) ? null : scope.traceparent;
            // Validates and freezes the payload outside the lock; the ring copy only re-wraps it.
            Event proto = new Event(0L, System.currentTimeMillis(), topic, source, correlationId, traceparent, payload);
            // The mask pass (phase8-contracts.md 5.1): after the freeze, before the bound, outside the lock.
            Map<String, Object> masked = masks.apply(topic, proto.payload());
            if (masked != proto.payload()) {
                proto = new Event(0L, proto.ts(), topic, source, correlationId, traceparent, masked);
            }
            long size = TextBounds.size(proto.payload(), maxChars);
            if (size > maxChars) {
                proto = bounded(proto, topic, source, correlationId, traceparent);
                size = TextBounds.size(proto.payload(), maxChars);
            }
            Event event;
            lock.lock();
            try {
                long seq = lastSeq + 1;
                event = new Event(seq, proto.ts(), topic, source, correlationId, traceparent, proto.payload());
                int slot = index(seq);
                chars -= sizes[slot];
                ring[slot] = event;
                sizes[slot] = size;
                chars += size;
                lastSeq = seq;
                while (chars > maxChars && oldestLocked() < seq) {
                    long oldest = oldestLocked();
                    int at = index(oldest);
                    chars -= sizes[at];
                    sizes[at] = 0L;
                    ring[at] = null;
                    evictedThrough = oldest;
                }
                published.signalAll();
                for (Subscription subscription : subscriptions) {
                    subscription.deliver(event);
                }
            } finally {
                lock.unlock();
            }
            if (scope != null) {
                scope.record(event);
            }
            return event.seq();
        } catch (Throwable t) {
            try {
                log.warn("event '" + topic + "' from " + source + " dropped", t);
            } catch (Throwable ignored) {
                // Never fail the publisher.
            }
            return -1L;
        }
    }

    /**
     * The event with its payload bounded: every string cut to 1/64 of the character bound and {@code truncated: true};
     * {@code {truncated: true}} alone when that is still over the bound.
     */
    private Event bounded(Event proto, String topic, String source, String correlationId, String traceparent) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cut = (Map<String, Object>) TextBounds.cutStrings(proto.payload(),
                (int) Math.min(Integer.MAX_VALUE, Math.max(64L, maxChars / 64)));
        cut.put("truncated", Boolean.TRUE);
        if (TextBounds.size(cut, maxChars) > maxChars) {
            cut = new LinkedHashMap<String, Object>();
            cut.put("truncated", Boolean.TRUE);
        }
        return new Event(0L, proto.ts(), topic, source, correlationId, traceparent, cut);
    }

    /** The characters of payload in the ring (tests). */
    long chars() {
        lock.lock();
        try {
            return chars;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long lastSeq() {
        lock.lock();
        try {
            return lastSeq;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long oldestSeq() {
        lock.lock();
        try {
            return oldestLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Oldest seq in the ring; {@code lastSeq + 1} while the ring is empty. Past the count bound and past what the
     * character bound took out.
     */
    private long oldestLocked() {
        return Math.max(Math.max(1L, lastSeq - ring.length + 1), evictedThrough + 1);
    }

    @Override
    public List<Event> since(long seqExclusive, TopicFilter filter, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        TopicFilter f = filter == null ? ALL : filter;
        List<Event> out = new ArrayList<Event>();
        lock.lock();
        try {
            for (long seq = Math.max(seqExclusive + 1, oldestLocked()); seq <= lastSeq; seq++) {
                Event event = ring[index(seq)];
                if (f.matches(event.topic())) {
                    out.add(event);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        return out;
    }

    @Override
    public Event await(long seqExclusive, TopicFilter filter, Map<String, Object> payloadMatch, long timeoutMs)
            throws InterruptedException {
        TopicFilter f = filter == null ? ALL : filter;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        lock.lockInterruptibly();
        try {
            long scanFrom = seqExclusive + 1;
            while (true) {
                for (long seq = Math.max(scanFrom, oldestLocked()); seq <= lastSeq; seq++) {
                    Event event = ring[index(seq)];
                    if (f.matches(event.topic()) && payloadMatches(event, payloadMatch)) {
                        return event;
                    }
                }
                scanFrom = Math.max(scanFrom, lastSeq + 1);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return null;
                }
                published.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Every match entry holds on the payload by the shared rule of {@link PayloadMatch} (phase8-contracts.md 8.9):
     * a scalar equal by {@code String.valueOf} text, or {@code {has: scalar}} a list member equal by text. Linear and
     * text only, since it runs under the ring lock: no regex, no parsing, no nested path.
     */
    static boolean payloadMatches(Event event, Map<String, Object> match) {
        return PayloadMatch.matches(event.payload(), match);
    }

    @Override
    public AutoCloseable subscribe(TopicFilter filter, EventSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        final Subscription subscription = new Subscription(filter == null ? ALL : filter, sink);
        subscriptions.add(subscription);
        return new AutoCloseable() {
            @Override
            public void close() {
                subscriptions.remove(subscription);
            }
        };
    }

    private int index(long seq) {
        return (int) ((seq - 1) % ring.length);
    }

    /** Ring capacity. */
    int capacity() {
        return ring.length;
    }

    /** The mask pass every payload takes ({@code agent.info.events}). */
    EventMasks masks() {
        return masks;
    }

    private final class Subscription {
        private final TopicFilter filter;
        private final EventSink sink;

        Subscription(TopicFilter filter, EventSink sink) {
            this.filter = filter;
            this.sink = sink;
        }

        void deliver(Event event) {
            try {
                if (filter.matches(event.topic())) {
                    sink.onEvent(event);
                }
            } catch (Throwable t) {
                log.warn("event subscriber " + sink + " failed on seq " + event.seq(), t);
            }
        }
    }
}
