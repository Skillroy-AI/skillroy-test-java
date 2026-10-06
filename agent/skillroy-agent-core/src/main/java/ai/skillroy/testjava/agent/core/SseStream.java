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
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One {@code GET /events} client. Served on its own HTTP worker thread from a bounded queue the event bus fills; a
 * client that falls behind (queue full) is caught up from the ring, and when the ring has already moved past what it
 * missed it receives a {@code gap} event first. A comment heartbeat is written whenever nothing was written for
 * {@link #heartbeatMs} (events the client's filter drops do not count), so a client can tell a quiet stream from a
 * dead one. A write failure ends the stream.
 *
 * <p>The output is the connection's socket stream ({@link ControlServer} sends the response head, then the stream is
 * close-delimited): every frame (event, gap or heartbeat) is built first and written in one write.
 *
 * <p>{@link #onEvent} is called by publishers (never blocks); {@link #serve} runs on the HTTP thread;
 * {@link #close} may be called from any thread.
 */
final class SseStream implements EventSink {

    /** Heartbeat interval; package-private so that tests can shorten it. */
    static volatile long heartbeatMs = 15000L;
    /** Per-client queue bound. */
    static final int QUEUE_CAPACITY = 1000;

    private static final int BACKFILL_CHUNK = 1000;
    private static final TopicFilter ALL = TopicFilter.parse(null);
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Event POISON = new Event(-1L, 0L, "skillroy.internal.close", null, null, null, null);

    private final RingEventBus bus;
    private final TopicFilter filter;
    private final OutputStream out;
    private final ArrayBlockingQueue<Event> queue = new ArrayBlockingQueue<Event>(QUEUE_CAPACITY);
    private final AtomicBoolean overflow = new AtomicBoolean();
    private volatile boolean closed;
    private long cursor;
    private long lastWriteNanos;

    /**
     * @param resumeAfter the last seq the client has seen: events with a higher seq are sent
     */
    SseStream(RingEventBus bus, TopicFilter filter, OutputStream out, long resumeAfter) {
        this.bus = bus;
        this.filter = filter == null ? ALL : filter;
        this.out = out;
        this.cursor = resumeAfter;
    }

    @Override
    public void onEvent(Event event) {
        if (closed) {
            return;
        }
        if (!queue.offer(event)) {
            overflow.set(true);
        }
    }

    /** Ends the stream soon; safe from any thread. */
    void close() {
        closed = true;
        queue.clear();
        queue.offer(POISON);
    }

    /** Streams until the client goes away or {@link #close} is called. Must be subscribed to the bus first. */
    void serve() throws InterruptedException {
        try {
            lastWriteNanos = System.nanoTime();
            backfill();
            while (!closed) {
                if (overflow.getAndSet(false)) {
                    queue.clear();
                    backfill();
                    continue;
                }
                long quietMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastWriteNanos);
                Event event = quietMs >= heartbeatMs ? null
                        : queue.poll(heartbeatMs - quietMs, TimeUnit.MILLISECONDS);
                if (closed || event == POISON) {
                    break;
                }
                if (event == null) {
                    write(": hb\n\n");
                    out.flush();
                    continue;
                }
                if (event.seq() <= cursor) {
                    continue;
                }
                if (event.seq() > cursor + 1) {
                    backfill();
                    if (event.seq() <= cursor) {
                        continue;
                    }
                }
                send(event);
                out.flush();
            }
        } catch (IOException clientGone) {
            closed = true;
        }
    }

    /** Sends everything after the cursor that is still in the ring, with a gap event for what is not. */
    private void backfill() throws IOException {
        while (!closed) {
            List<Event> events = bus.since(cursor, ALL, BACKFILL_CHUNK);
            if (events.isEmpty()) {
                long oldest = bus.oldestSeq();
                if (oldest > cursor + 1 && bus.lastSeq() >= oldest) {
                    gap(cursor + 1, oldest - 1);
                }
                break;
            }
            for (Event event : events) {
                if (event.seq() > cursor + 1) {
                    gap(cursor + 1, event.seq() - 1);
                }
                send(event);
            }
            if (events.size() < BACKFILL_CHUNK) {
                break;
            }
        }
        out.flush();
    }

    private void gap(long from, long to) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("from", Long.valueOf(from));
        data.put("to", Long.valueOf(to));
        write("id: " + to + "\nevent: " + Topics.GAP + "\ndata: " + Json.write(data) + "\n\n");
        cursor = to;
    }

    private void send(Event event) throws IOException {
        if (filter.matches(event.topic())) {
            write("id: " + event.seq() + "\nevent: " + oneLine(event.topic()) + "\ndata: "
                    + Json.write(event.toJson()) + "\n\n");
        }
        cursor = event.seq();
    }

    private void write(String text) throws IOException {
        out.write(text.getBytes(UTF8));
        lastWriteNanos = System.nanoTime();
    }

    private static String oneLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }
}
