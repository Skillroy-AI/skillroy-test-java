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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The CDP path's own events ({@code web.tap}, {@code web.ready}, {@code web.load} that the path saw itself and that
 * never reach the agent's bus), numbered by a sequence of their own: 1, 2, 3, ... per path instance, unrelated to the
 * agent's event sequence. At most {@value #CAPACITY} are kept; the oldest go first.
 *
 * <p>Thread-safe.
 */
final class PathEvents {

    /** The most events kept. */
    static final int CAPACITY = 10_000;

    /** The {@code source} of the path's events. */
    static final String SOURCE = "cdp";

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long seq;

    /** The sequence number of the last event appended; 0 before the first. */
    synchronized long lastSeq() {
        return seq;
    }

    /** Appends an event with the next sequence number and returns it. */
    synchronized Event append(String topic, Map<String, Object> payload, String correlationId) {
        Event event = new Event(++seq, System.currentTimeMillis(), topic, SOURCE, correlationId, null, payload);
        events.addLast(event);
        while (events.size() > CAPACITY) {
            events.removeFirst();
        }
        notifyAll();
        return event;
    }

    /**
     * The first event after {@code seq} whose topic matches, waiting at most {@code timeoutMs} for it to be
     * appended; null when none came.
     */
    synchronized Event await(long after, String topics, long timeoutMs) throws InterruptedException {
        TopicFilter filter = TopicFilter.parse(topics);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        while (true) {
            for (Event event : events) {
                if (event.seq() > after && filter.matches(event.topic())) {
                    return event;
                }
            }
            long left = until - System.nanoTime();
            if (left <= 0) {
                return null;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
    }

    /** The events after {@code seq} whose topic matches the comma-separated globs (null or blank: every topic). */
    synchronized List<Event> since(long after, String topics) {
        TopicFilter filter = TopicFilter.parse(topics);
        List<Event> out = new ArrayList<>();
        for (Event event : events) {
            if (event.seq() > after && filter.matches(event.topic())) {
                out.add(event);
            }
        }
        return out;
    }
}
