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
import ai.skillroy.testjava.protocol.TopicFilter;

import java.util.ArrayList;
import java.util.Collections;
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
 * <p>{@link #publish} never throws into the caller (it may be an application thread or the EDT): a payload that
 * cannot be represented (NaN, a cycle) is logged and the call returns -1.
 *
 * <p>An event is stored, delivered and recorded for its call with the payload it was published with, also under a
 * call that named secret parameters (contract amendment A34): the payload is what the call did, not what the core
 * writes about it, and waits, queries, captures and subscriptions must see the values the target published. Only the
 * correlation id and traceparent of the call are stamped on it.
 */
final class RingEventBus implements EventBus {

    private static final TopicFilter ALL = TopicFilter.parse(null);

    private final Event[] ring;
    private final AgentLog log;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition published = lock.newCondition();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<Subscription>();
    private long lastSeq;

    RingEventBus(int capacity, AgentLog log) {
        if (capacity < 1) {
            throw new IllegalArgumentException("event ring size must be positive: " + capacity);
        }
        this.ring = new Event[capacity];
        this.log = log;
    }

    @Override
    public long publish(String topic, String source, Map<String, Object> payload) {
        try {
            if (topic == null || topic.isEmpty()) {
                log.warn("event without topic dropped (source " + source + ")");
                return -1L;
            }
            CallScope scope = CallScope.current();
            String correlationId = scope == null ? null : scope.correlationId;
            String traceparent = scope == null ? null : scope.traceparent;
            // Validates and freezes the payload outside the lock; the ring copy only re-wraps it.
            Event proto = new Event(0L, System.currentTimeMillis(), topic, source, correlationId, traceparent, payload);
            Event event;
            lock.lock();
            try {
                long seq = lastSeq + 1;
                event = new Event(seq, proto.ts(), topic, source, correlationId, traceparent, proto.payload());
                ring[index(seq)] = event;
                lastSeq = seq;
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

    /** Oldest seq in the ring; {@code lastSeq + 1} while the ring is empty. */
    private long oldestLocked() {
        return Math.max(1L, lastSeq - ring.length + 1);
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

    /** Every match entry equals the payload value by {@code String.valueOf}. */
    static boolean payloadMatches(Event event, Map<String, Object> match) {
        if (match == null) {
            return true;
        }
        Map<String, Object> payload = event.payload();
        for (Map.Entry<String, Object> entry : match.entrySet()) {
            if (!String.valueOf(entry.getValue()).equals(String.valueOf(payload.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
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
