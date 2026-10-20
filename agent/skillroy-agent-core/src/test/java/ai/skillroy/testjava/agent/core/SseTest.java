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

import ai.skillroy.testjava.agent.core.fixture.CoreTestModule;
import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** GET /events: delivery, filters, resume, gap and heartbeat. */
class SseTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;
    private final List<HttpURLConnection> open = new ArrayList<HttpURLConnection>();

    @AfterEach
    void stop() {
        SseStream.heartbeatMs = 15000L;
        for (HttpURLConnection c : open) {
            c.disconnect();
        }
        if (agent != null) {
            agent.close();
        }
    }

    /** One SSE message: id, event and data lines, or a comment. */
    static final class Message {
        String id;
        String event;
        String data;
        String comment;

        @Override
        public String toString() {
            return comment != null ? ":" + comment : "id=" + id + " event=" + event + " data=" + data;
        }
    }

    private BufferedReader connect(String query, String lastEventId) throws IOException {
        AgentHarness.Stream stream = lastEventId == null ? agent.new Stream("/events" + query)
                : agent.new Stream("/events" + query, "Last-Event-ID", lastEventId);
        HttpURLConnection c = stream.connection;
        c.setReadTimeout(30000);
        assertEquals(200, stream.verify(), "the stream's reply head carries a MAC over an empty body");
        assertTrue(c.getContentType().startsWith("text/event-stream"), c.getContentType());
        assertEquals("close", c.getHeaderField("Connection"), "an event stream is close-delimited");
        open.add(c);
        return new BufferedReader(new InputStreamReader(c.getInputStream(), AgentHarness.UTF8));
    }

    private static Message next(BufferedReader reader) throws IOException {
        Message m = new Message();
        String line;
        boolean any = false;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (any) {
                        return m;
                    }
                    continue;
                }
                any = true;
                if (line.startsWith(":")) {
                    m.comment = line.substring(1).trim();
                } else if (line.startsWith("id: ")) {
                    m.id = line.substring(4);
                } else if (line.startsWith("event: ")) {
                    m.event = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    m.data = line.substring(6);
                }
            }
        } catch (SocketTimeoutException e) {
            fail("no SSE message within the read timeout");
        }
        fail("stream ended");
        return m;
    }

    /** The next message that is not a heartbeat. */
    private static Message nextEvent(BufferedReader reader) throws IOException {
        Message m;
        do {
            m = next(reader);
        } while (m.comment != null);
        return m;
    }

    private long mark(String label) throws IOException {
        return ((Number) agent.valueMap("trace.mark", map("label", label)).get("seq")).longValue();
    }

    @Test
    void eventsArriveWithIdTopicAndJson() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp);
        BufferedReader reader = connect("?topics=trace.*", null);
        long seq = mark("first");
        agent.value("test.publish", map("n", Long.valueOf(2)));
        long second = mark("second");
        Message m = nextEvent(reader);
        assertEquals(String.valueOf(seq), m.id);
        assertEquals("trace.mark", m.event);
        Map<String, Object> event = Json.parseObject(m.data);
        assertEquals(Long.valueOf(seq), event.get("seq"));
        assertEquals("first", ((Map<?, ?>) event.get("payload")).get("label"));
        Message filtered = nextEvent(reader);
        assertEquals(String.valueOf(second), filtered.id, "test.burst events are filtered out");
    }

    @Test
    void resumesFromLastEventIdOrSince() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp);
        long a = mark("a");
        long b = mark("b");
        long c = mark("c");
        BufferedReader byHeader = connect("?since=0&topics=trace.mark", String.valueOf(a));
        assertEquals(String.valueOf(b), nextEvent(byHeader).id, "Last-Event-ID wins over since");
        assertEquals(String.valueOf(c), nextEvent(byHeader).id);
        BufferedReader bySince = connect("?since=" + b + "&topics=trace.mark", null);
        assertEquals(String.valueOf(c), nextEvent(bySince).id);
        BufferedReader fromNow = connect("?topics=trace.mark", null);
        long d = mark("d");
        assertEquals(String.valueOf(d), nextEvent(fromNow).id);
    }

    @Test
    void aResumePointOlderThanTheRingGetsAGapFirst() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, "eventRing", "16");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        agent.value("test.publish", map("n", Long.valueOf(40)));
        long oldest = agent.runtime.context().events().oldestSeq();
        BufferedReader reader = connect("?since=2", null);
        Message gap = nextEvent(reader);
        assertEquals("gap", gap.event);
        Map<String, Object> data = Json.parseObject(gap.data);
        assertEquals(Long.valueOf(3), data.get("from"));
        assertEquals(Long.valueOf(oldest - 1), data.get("to"));
        assertEquals(String.valueOf(oldest - 1), gap.id);
        assertEquals(String.valueOf(oldest), nextEvent(reader).id);
    }

    @Test
    void aClientThatFallsBehindIsCaughtUpWithoutLosingOrder() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, "eventRing", "100000");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        BufferedReader reader = connect("?topics=test.burst", null);
        long before = agent.runtime.context().events().lastSeq();
        agent.value("test.publish", map("n", Long.valueOf(SseStream.QUEUE_CAPACITY * 3)));
        long expected = before + 1;
        for (int i = 0; i < SseStream.QUEUE_CAPACITY * 3; i++) {
            Message m = nextEvent(reader);
            assertEquals("test.burst", m.event, m.toString());
            assertEquals(String.valueOf(expected), m.id);
            expected++;
        }
    }

    /** An output stream whose writes block until released, then collect everything. */
    static final class GatedOutput extends java.io.OutputStream {
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch firstWrite = new java.util.concurrent.CountDownLatch(1);
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        /** Every write as one string: each must be whole SSE frames. */
        final List<String> writes = new java.util.concurrent.CopyOnWriteArrayList<String>();

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            firstWrite.countDown();
            try {
                gate.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new java.io.InterruptedIOException();
            }
            synchronized (bytes) {
                bytes.write(b, off, len);
            }
            writes.add(new String(b, off, len, AgentHarness.UTF8));
        }

        String text() {
            synchronized (bytes) {
                return new String(bytes.toByteArray(), AgentHarness.UTF8);
            }
        }
    }

    @Test
    void anOverflowingQueueIsRecoveredFromTheRingWithAGapForWhatTheRingLost() throws Exception {
        final RingEventBus bus = new RingEventBus(50, new RingLog());
        bus.publish("before", "t", null);
        GatedOutput out = new GatedOutput();
        final SseStream stream = new SseStream(bus, null, out, 0);
        AutoCloseable subscription = bus.subscribe(null, stream);
        Thread server = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    stream.serve();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "sse-unit");
        server.setDaemon(true);
        server.start();
        assertTrue(out.firstWrite.await(30, java.util.concurrent.TimeUnit.SECONDS), "blocked in the first write");
        for (int i = 0; i < SseStream.QUEUE_CAPACITY * 2; i++) {
            bus.publish("burst", "t", null);
        }
        long last = bus.lastSeq();
        out.gate.countDown();
        AgentHarness.await("caught up", 30000, () -> out.text().contains("id: " + last + "\n"));
        stream.close();
        server.join(30000);
        subscription.close();
        String text = out.text();
        assertTrue(text.startsWith("id: 1\nevent: before\n"), text.substring(0, 60));
        int gap = text.indexOf("event: gap\n");
        assertTrue(gap > 0, "the queue overflowed and the ring had moved on");
        long previous = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("id: ")) {
                long id = Long.parseLong(line.substring(4));
                assertTrue(id > previous, "ids strictly increase: " + id + " after " + previous);
                previous = id;
            }
        }
        assertEquals(last, previous);
        for (String write : out.writes) {
            assertTrue(write.endsWith("\n\n") && (write.startsWith("id: ") || write.startsWith(": hb")),
                    "each frame is written whole, in one write: " + write);
            assertEquals(1, write.split("\n\n", -1).length - 1, "one frame per write: " + write);
        }
    }

    @Test
    void heartbeatCommentsKeepTheStreamAlive() throws Exception {
        TestState.reset();
        SseStream.heartbeatMs = 100L;
        agent = AgentHarness.start(tmp);
        BufferedReader reader = connect("?topics=nothing.ever", null);
        Message m = next(reader);
        assertEquals("hb", m.comment);
        assertEquals("hb", next(reader).comment);
    }

    @Test
    void heartbeatsContinueWhileOnlyFilteredOutEventsFlow() throws Exception {
        TestState.reset();
        SseStream.heartbeatMs = 300L;
        agent = AgentHarness.start(tmp);
        final java.util.concurrent.atomic.AtomicBoolean run = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread noise = new Thread(new Runnable() {
            @Override
            public void run() {
                // Far more often than the heartbeat interval, so that the stream is never idle.
                while (run.get()) {
                    agent.runtime.context().events().publish("test.noise", "test", null);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "sse-noise");
        noise.setDaemon(true);
        noise.start();
        try {
            BufferedReader reader = connect("?topics=nothing.ever", null);
            assertEquals("hb", next(reader).comment, "a client that filters everything out still sees the stream "
                    + "is alive");
            assertEquals("hb", next(reader).comment);
        } finally {
            run.set(false);
            noise.interrupt();
        }
    }

    @Test
    void closingTheAgentEndsOpenStreams() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp);
        BufferedReader reader = connect("", null);
        agent.close();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                assertTrue(line.isEmpty() || line.startsWith(":") || line.startsWith("id:")
                        || line.startsWith("event:") || line.startsWith("data:"), line);
            }
        } catch (SocketTimeoutException stillOpen) {
            fail("the stream was not ended by the close");
        } catch (IOException expected) {
            // A reset connection is also an end.
        }
        agent = null;
    }

    /**
     * Phase 8 (phase8-contracts.md 5.1, 13.1 row 15): the stream is a subscription, so it carries the masked payload,
     * of an event published from {@code install}'s context and of one published through {@code inv.context()}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theStreamCarriesTheMaskedPayload() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, "coretest.eventMask", "on");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        BufferedReader reader = connect("?since=0&topics=" + CoreTestModule.MASKED_TOPIC, null);
        Message installed = nextEvent(reader);
        agent.value("test.publish.masked", null);
        Message invoked = nextEvent(reader);
        for (Message m : new Message[] {installed, invoked}) {
            assertEquals(CoreTestModule.MASKED_TOPIC, m.event);
            assertFalse(m.data.contains(CoreTestModule.PLANTED_NAME), m.data);
            assertFalse(m.data.contains(CoreTestModule.PLANTED_PASSWORD), m.data);
            Map<String, Object> payload = (Map<String, Object>) Json.parseObject(m.data).get("payload");
            assertEquals(CoreTestModule.PERSONAL, payload.get("name"));
            assertEquals(CoreTestModule.PERSONAL, ((Map<String, Object>) payload.get("person")).get("name"));
            assertEquals("<redacted>", payload.get("password"));
        }
        assertEquals("install", ((Map<?, ?>) Json.parseObject(installed.data).get("payload")).get("via"));
        assertEquals("invocation", ((Map<?, ?>) Json.parseObject(invoked.data).get("payload")).get("via"));
    }
}
