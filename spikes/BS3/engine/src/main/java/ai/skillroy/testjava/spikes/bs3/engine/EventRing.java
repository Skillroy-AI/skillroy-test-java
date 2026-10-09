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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** A bounded, lock-free ring of hook events (the oldest are overwritten). */
final class EventRing {

    /** One hook event. */
    static final class Event {
        final long seq;
        final long nanos;
        final String hookId;
        final String handler;
        final String phase;
        final String thread;
        final String detail;

        Event(long seq, String hookId, String handler, String phase, String detail) {
            this.seq = seq;
            this.nanos = System.nanoTime();
            this.hookId = hookId;
            this.handler = handler;
            this.phase = phase;
            this.thread = Thread.currentThread().getName();
            this.detail = detail;
        }

        @Override
        public String toString() {
            return "#" + seq + " " + hookId + " " + phase + " [" + handler + " on " + thread + "] " + detail;
        }
    }

    private final AtomicReferenceArray<Event> ring;
    private final AtomicLong seq = new AtomicLong();

    EventRing(int capacity) {
        ring = new AtomicReferenceArray<Event>(capacity);
    }

    void add(String hookId, String handler, String phase, String detail) {
        long s = seq.incrementAndGet();
        ring.set((int) (s % ring.length()), new Event(s, hookId, handler, phase, detail));
    }

    long last() {
        return seq.get();
    }

    /** @return the events after {@code since} that are still in the ring, in order */
    List<Event> since(long since) {
        List<Event> out = new ArrayList<Event>();
        long last = seq.get();
        for (long s = Math.max(since + 1, last - ring.length() + 1); s <= last; s++) {
            Event e = ring.get((int) (s % ring.length()));
            if (e != null && e.seq == s) {
                out.add(e);
            }
        }
        return out;
    }
}
