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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Topics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EventStreamTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private FakeAgent agent;
    private AgentClient client;
    private final List<Event> received = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void connect() throws Exception {
        agent = new FakeAgent();
        client = AgentClient.connect(agent.endpoint(), AgentClientTest.FAST);
    }

    @AfterEach
    void close() {
        client.close();
        agent.close();
    }

    private List<Long> seqs() {
        synchronized (received) {
            return received.stream().map(Event::seq).toList();
        }
    }

    @Test
    void deliversMatchingEventsInOrderAndTracksLastSeq() {
        agent.publish("state.changed", Map.of("to", "IDLE"));
        EventStream stream = client.events(0, "state.*", received::add);
        assertEquals(0, stream.lastSeq(), "the starting point until something is delivered");
        agent.publish("other.thing", Map.of());
        agent.publish("state.changed", Map.of("to", "LOAN"));
        Poll.until("two events", WAIT, () -> received.size() == 2);
        assertEquals(List.of(1L, 3L), seqs());
        assertEquals(Map.of("to", "LOAN"), received.get(1).payload());
        assertEquals("fake", received.get(1).source());
        assertEquals(3, stream.lastSeq());
        assertTrue(stream.isOpen());
        FakeAgent.Request request = agent.requests("/events").get(0);
        assertEquals("since=0&topics=state.*", request.query());
        assertNull(request.lastEventId());
        Auth.Credentials credentials = Auth.parseAuthorization(request.authorization());
        assertTrue(Auth.verifyRequest(agent.token, agent.sessionId, credentials, "GET",
                "/events?since=0&topics=state.*", Auth.EMPTY_BODY_SHA256), "the request MAC covers the query");
        assertFalse(request.authorization().contains(agent.token));
        assertEquals("127.0.0.1:" + agent.port(), request.host());
        stream.close();
    }

    @Test
    void aNegativeSinceMeansFromNow() {
        agent.publish("a", Map.of());
        EventStream stream = client.events(-1, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        agent.publish("b", Map.of());
        Poll.until("one event", WAIT, () -> received.size() == 1);
        assertEquals("b", received.get(0).topic());
        assertNull(agent.requests("/events").get(0).query());
        assertEquals(2, stream.lastSeq());
        stream.close();
    }

    @Test
    void reconnectsWithLastEventIdAndDeliversNothingTwice() {
        EventStream stream = client.events(0, null, received::add);
        agent.publish("a", Map.of());
        agent.publish("b", Map.of());
        Poll.until("two events", WAIT, () -> received.size() == 2);
        agent.dropStreams();
        agent.publish("c", Map.of());
        Poll.until("the third event after reconnecting", WAIT, () -> received.size() == 3);
        agent.publish("d", Map.of());
        Poll.until("the fourth event", WAIT, () -> received.size() == 4);
        assertEquals(List.of(1L, 2L, 3L, 4L), seqs());
        List<FakeAgent.Request> requests = agent.requests("/events");
        assertTrue(requests.size() >= 2, "reconnected");
        assertEquals("2", requests.get(1).lastEventId());
        assertTrue(stream.isOpen());
        stream.close();
    }

    @Test
    void aResumePointOlderThanTheRingYieldsAGapEventFirst() {
        for (int i = 0; i < 5; i++) {
            agent.publish("t", Map.of("i", i));
        }
        agent.forgetBefore(4);
        EventStream stream = client.events(0, null, received::add);
        Poll.until("gap plus two events", WAIT, () -> received.size() == 3);
        Event gap = received.get(0);
        assertEquals(Topics.GAP, gap.topic());
        assertEquals(Map.of("from", 1L, "to", 3L), gap.payload());
        assertEquals(List.of(4L, 5L), seqs().subList(1, 3));
        assertEquals(5, stream.lastSeq());
        stream.close();
    }

    @Test
    void aGapIsNotRepeatedAfterAReconnect() {
        for (int i = 0; i < 5; i++) {
            agent.publish("t", Map.of("i", i));
        }
        agent.forgetBefore(4);
        // The filter matches nothing that is left, so the gap is the only thing delivered.
        EventStream stream = client.events(0, "other.*", received::add);
        Poll.until("the gap", WAIT, () -> received.size() == 1);
        assertEquals(Topics.GAP, received.get(0).topic());
        assertEquals(3, received.get(0).seq(), "a gap's seq is the end of the lost range");
        Poll.until("the resume point to move past the gap", WAIT, () -> stream.lastSeq() == 3);
        agent.dropStreams();
        Poll.until("a reconnect", WAIT, () -> agent.requests("/events").size() >= 2);
        agent.publish("other.thing", Map.of());
        Poll.until("the event after the reconnect", WAIT, () -> received.size() >= 2);
        assertEquals(List.of(3L, 6L), seqs(), "no second gap");
        assertEquals("3", agent.requests("/events").get(1).lastEventId());
        stream.close();
    }

    @Test
    void aGapFrameWithAnIdAsTheRealAgentSendsIt() {
        agent.gapWithId = true;
        for (int i = 0; i < 5; i++) {
            agent.publish("t", Map.of("i", i));
        }
        agent.forgetBefore(3);
        EventStream stream = client.events(0, null, received::add);
        Poll.until("gap plus three events", WAIT, () -> received.size() == 4);
        assertEquals(Topics.GAP, received.get(0).topic());
        assertEquals(Map.of("from", 1L, "to", 2L), received.get(0).payload());
        assertEquals(List.of(2L, 3L, 4L, 5L), seqs());
        stream.close();
    }

    @Test
    void aBurstLargerThanTheDemandWindowArrivesCompleteAndInOrder() {
        EventStream stream = client.events(0, null, received::add);
        int count = 1000;
        for (int i = 0; i < count; i++) {
            agent.publish("burst", Map.of("i", i, "text", "line one\nline two \u00e9\u4e2d"));
        }
        Poll.until("all events", Duration.ofSeconds(60), () -> received.size() == count);
        List<Long> seqs = seqs();
        for (int i = 0; i < count; i++) {
            assertEquals(i + 1L, seqs.get(i));
        }
        assertEquals("line one\nline two \u00e9\u4e2d", received.get(count - 1).payload().get("text"));
        assertEquals(1, agent.requests("/events").size(), "no reconnect was needed");
        stream.close();
    }

    @Test
    void malformedFramesAreSkippedAndTheStreamContinues() {
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        agent.sendRaw("id: 1\nevent: x\ndata: {not json\n\n");
        agent.sendRaw("event: x\ndata: {\"topic\":\"no seq\"}\n\n");
        agent.sendRaw("data: [1,2]\n\n");
        agent.sendRaw("retry: 10\n\n");
        agent.sendRaw("event: gap\ndata: {\"from\":\"x\"}\r\n\r\n");
        agent.publish("good", Map.of());
        Poll.until("the gap without a range and the good event", WAIT, () -> received.size() == 2);
        assertEquals(Topics.GAP, received.get(0).topic());
        assertEquals("good", received.get(1).topic());
        assertEquals(1, stream.lastSeq());
        assertTrue(stream.isOpen());
        assertEquals(1, agent.requests("/events").size());
        stream.close();
    }

    @Test
    void aHealthyStreamOutlivesTheHeaderTimeout() throws Exception {
        // Newer JDKs apply a request timeout to the whole response body; the stream must not inherit that.
        agent.heartbeatMs = 50;
        ClientSettings shortHeaders = new ClientSettings(Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofMillis(400), Duration.ofSeconds(130), Duration.ofMillis(400),
                Duration.ofSeconds(45), Duration.ofMillis(50), Duration.ofMillis(200));
        try (AgentClient quick = AgentClient.connect(agent.endpoint(), shortHeaders)) {
            EventStream stream = quick.events(0, null, received::add);
            Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
            int before = agent.heartbeatsSent.get();
            // 40 heartbeats of 50 ms are five header timeouts.
            Poll.until("forty more heartbeats", WAIT, () -> agent.heartbeatsSent.get() >= before + 40);
            agent.publish("late", Map.of());
            Poll.until("the event", WAIT, () -> received.size() == 1);
            assertEquals(1, agent.requests("/events").size(), "the first connection is still the one in use");
            assertTrue(stream.isOpen());
        }
    }

    @Test
    void manyStreamsOpenedAndClosedConcurrentlyLeaveNothingBehind() throws Exception {
        // The fake notices a closed connection only when it writes, so let it write often.
        agent.heartbeatMs = 50;
        int threads = 6;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<SseEventStream> streams = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        SseEventStream stream = (SseEventStream) client.events(0, null, received::add);
                        streams.add(stream);
                        if (i % 2 == 0) {
                            Poll.until("the stream to connect", WAIT, () -> agent.openStreams() >= 1);
                        }
                        stream.close();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, streams.size());
        for (SseEventStream stream : streams) {
            assertFalse(stream.isOpen());
            assertTrue(stream.awaitTermination(TimeUnit.SECONDS.toNanos(20)), "reader thread ended");
        }
        Poll.until("the server to see every connection closed", WAIT, () -> agent.openStreams() == 0);
        agent.handlers.put("x", params -> 1);
        assertEquals(1L, client.call("x", null).value(), "the client still works");
    }

    @Test
    void heartbeatsKeepTheConnectionWithoutDeliveringAnything() {
        agent.heartbeatMs = 30;
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        Poll.until("five heartbeats", WAIT, () -> agent.heartbeatsSent.get() >= 5);
        assertTrue(received.isEmpty());
        assertEquals(1, agent.requests("/events").size());
        assertTrue(stream.isOpen());
        stream.close();
    }

    @Test
    void aSilentConnectionIsReplacedAfterTheIdleTimeout() throws Exception {
        agent.silent = true;
        agent.heartbeatMs = 50;
        ClientSettings shortIdle = new ClientSettings(Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofMillis(400), Duration.ofSeconds(130), Duration.ofSeconds(10),
                Duration.ofMillis(300), Duration.ofMillis(50), Duration.ofMillis(200));
        try (AgentClient quick = AgentClient.connect(agent.endpoint(), shortIdle)) {
            EventStream stream = quick.events(0, null, received::add);
            Poll.until("a reconnect after the idle timeout", WAIT, () -> agent.requests("/events").size() >= 2);
            agent.publish("late", Map.of());
            Poll.until("the event on the new connection", WAIT, () -> received.size() == 1);
            assertTrue(stream.isOpen());
        }
    }

    @Test
    void closeStopsDeliveryAndTheReaderThread() {
        agent.heartbeatMs = 100;
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        stream.close();
        stream.close();
        assertFalse(stream.isOpen());
        agent.publish("after", Map.of());
        Poll.until("the server to see the connection closed", WAIT, () -> agent.openStreams() == 0);
        assertTrue(received.isEmpty());
        assertTrue(((SseEventStream) stream).awaitTermination(0), "the reader thread has ended");
    }

    @Test
    void closingTheClientClosesItsStreams() {
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        client.close();
        assertFalse(stream.isOpen());
    }

    @Test
    void aRejectedReconnectMeansTheAgentIsLost() {
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        agent.rejectAll = true;
        agent.dropStreams();
        Poll.until("the stream to give up", WAIT, () -> !stream.isOpen());
        assertTrue(((SseEventStream) stream).lostReason().contains("401"), ((SseEventStream) stream).lostReason());
    }

    @Test
    void aStreamWhoseProcessIsGoneIsLost() throws Exception {
        java.nio.file.Path sleep = java.nio.file.Path.of("/bin/sleep");
        assumeTrue(java.nio.file.Files.isExecutable(sleep), "no /bin/sleep here");
        Process child = new ProcessBuilder(sleep.toString(), "60").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            agent.pid = child.pid();
            agent.startTimeMs = 0;
            Endpoint e = agent.endpoint();
            try (AgentClient other = AgentClient.connect(new Endpoint("127.0.0.1", e.port(), e.token(), e.nonce(),
                    e.pid(), 0, null), AgentClientTest.FAST)) {
                EventStream stream = other.events(0, null, received::add);
                Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
                child.destroyForcibly();
                assertTrue(child.waitFor(60, TimeUnit.SECONDS));
                agent.dropStreams();
                Poll.until("the stream to give up", WAIT, () -> !stream.isOpen());
                assertTrue(((SseEventStream) stream).lostReason().contains("no longer running"),
                        ((SseEventStream) stream).lostReason());
                assertEquals(1, agent.requests("/events").size(), "no reconnect to a gone process");
                assertTrue(other.isLost());
            }
        } finally {
            child.destroyForcibly();
        }
    }

    /** H2 latch: an agent.stopping event ends the stream after delivering it, and marks the client lost. */
    @Test
    void anAgentStoppingEventEndsTheStreamAndLatchesTheClient() {
        EventStream stream = client.events(0, null, received::add);
        agent.publish("a", Map.of());
        agent.publish(Topics.AGENT_STOPPING, Map.of("reason", "disable"));
        Poll.until("the stream to end", WAIT, () -> !stream.isOpen());
        assertEquals(List.of("a", Topics.AGENT_STOPPING), received.stream().map(Event::topic).toList());
        assertTrue(((SseEventStream) stream).lostReason().contains(Topics.AGENT_STOPPING));
        assertTrue(client.isLost());
        assertEquals(1, agent.requests("/events").size());
    }

    /** H2: a 200 for /events without the right MAC is not the agent: nothing of it is read. */
    @Test
    void aStreamHeadWithoutAValidMacIsNotTheAgent() {
        agent.publish("secret.looking", Map.of());
        agent.signingKey = SecureFiles.newToken();
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to end", WAIT, () -> !stream.isOpen());
        assertTrue(((SseEventStream) stream).lostReason().contains("is not the agent"),
                ((SseEventStream) stream).lostReason());
        assertTrue(received.isEmpty(), "no event of an unverified stream is delivered");
        assertTrue(client.isLost());
    }

    /**
     * The rule of AgentClient.send for event streams: a refusal the agent sends before it has authenticated the request
     * (400, 413, 431, 501, 503 without any MAC) is a failed attempt, retried with the usual back-off, and does not latch;
     * its body is not used. The same status with a wrong MAC still latches.
     */
    @Test
    void anUnsignedRefusalBeforeAuthenticationIsRetriedNotLatched() throws Exception {
        agent.publish("a", Map.of());
        agent.eventsReplies.add(new FakeAgent.Unsigned(503, "{\"error\":\"too many request bodies\"}"));
        agent.eventsReplies.add(new FakeAgent.Unsigned(400, "{}"));
        agent.eventsReplies.add(new FakeAgent.Unsigned(431, ""));
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the event, after three refusals", WAIT, () -> received.size() == 1);
        assertEquals(4, agent.requests("/events").size(), "three refused attempts and the stream");
        assertTrue(stream.isOpen());
        assertFalse(client.isLost());
        assertNotNull(client.ping(), "the client still works");
        stream.close();

        agent.signingKey = SecureFiles.newToken();
        agent.eventsReplies.add(new FakeAgent.Raw(503, "{}"));
        EventStream wrongMac = client.events(0, null, received::add);
        Poll.until("the stream to end", WAIT, () -> !wrongMac.isOpen());
        assertTrue(((SseEventStream) wrongMac).lostReason().contains("is not the agent"),
                ((SseEventStream) wrongMac).lostReason());
        assertTrue(client.isLost(), "a wrong MAC latches whatever the status");
    }

    /** An unsigned 200 is no refusal: a stream head without any MAC is not the agent, as before. */
    @Test
    void anUnsignedStreamHeadStillLatches() {
        agent.unsigned = true;
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to end", WAIT, () -> !stream.isOpen());
        assertTrue(((SseEventStream) stream).lostReason().contains("is not the agent"),
                ((SseEventStream) stream).lostReason());
        assertTrue(client.isLost());
        assertEquals(1, agent.requests("/events").size(), "not retried");
    }

    /** S1 part B: a refused reconnect is fatal and latches the client instead of retrying for ever. */
    @Test
    void aRefusedReconnectIsFatal() {
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        agent.close();
        Poll.until("the stream to give up", WAIT, () -> !stream.isOpen());
        assertTrue(((SseEventStream) stream).lostReason().contains("refused"),
                ((SseEventStream) stream).lostReason());
        assertTrue(client.isLost());
    }

    /** Finding S25: a line longer than the cap, terminated or not, ends the stream as a protocol violation. */
    @Test
    void anOverlongLineEndsTheStream() throws Exception {
        // The fake notices a closed connection only when it writes, so let it write often.
        agent.heartbeatMs = 50;
        ClientSettings small = AgentClientTest.FAST.withLimits(1 << 20, 64 * 1024, 1000, 2000);
        for (String raw : List.of("data: " + "x".repeat(5000) + "\n\n", "data: " + "y".repeat(5000))) {
            try (AgentClient quick = AgentClient.connect(agent.endpoint(), small)) {
                EventStream stream = quick.events(0, null, received::add);
                Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
                agent.sendRaw(raw);
                Poll.until("the stream to give up", WAIT, () -> !stream.isOpen());
                assertTrue(((SseEventStream) stream).lostReason().contains("longer than 1000 bytes"),
                        ((SseEventStream) stream).lostReason());
                assertFalse(quick.isLost(), "the agent is not lost, only this stream");
                Poll.until("the server to see the connection closed", WAIT, () -> agent.openStreams() == 0);
            }
        }
        assertTrue(received.isEmpty());
    }

    /** Finding S25: data lines without the blank line that ends the event are capped too. */
    @Test
    void anEventThatNeverEndsIsCapped() throws Exception {
        ClientSettings small = AgentClientTest.FAST.withLimits(1 << 20, 64 * 1024, 1000, 2000);
        try (AgentClient quick = AgentClient.connect(agent.endpoint(), small)) {
            EventStream stream = quick.events(0, null, received::add);
            Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
            for (int i = 0; i < 30; i++) {
                agent.sendRaw("data: " + "z".repeat(100) + "\n");
            }
            Poll.until("the stream to give up", WAIT, () -> !stream.isOpen());
            assertTrue(((SseEventStream) stream).lostReason().contains("more than 2000 characters"),
                    ((SseEventStream) stream).lostReason());
            assertEquals(1, agent.requests("/events").size(), "no reconnect after a protocol violation");
        }
    }

    /** Lines may end with CR LF, LF or a lone CR, also split across reads. */
    @Test
    void allThreeLineEndingsAreAccepted() {
        EventStream stream = client.events(0, null, received::add);
        Poll.until("the stream to connect", WAIT, () -> agent.openStreams() == 1);
        String json = "{\"seq\":%d,\"ts\":1,\"topic\":\"t\",\"payload\":{}}";
        agent.sendRaw("id: 1\r\ndata: " + String.format(json, 1) + "\r\n\r\n");
        agent.sendRaw("id: 2\rdata: " + String.format(json, 2) + "\r\r");
        agent.sendRaw("id: 3\ndata: " + String.format(json, 3) + "\r");
        agent.sendRaw("\n\n");
        Poll.until("three events", WAIT, () -> received.size() == 3);
        assertEquals(List.of(1L, 2L, 3L), seqs());
        assertTrue(stream.isOpen());
        stream.close();
    }

    @Test
    void aThrowingConsumerDoesNotStopTheStream() {
        EventStream stream = client.events(0, null, event -> {
            received.add(event);
            if (event.seq() == 1) {
                throw new IllegalStateException("consumer bug");
            }
        });
        agent.publish("a", Map.of());
        agent.publish("b", Map.of());
        Poll.until("both events", WAIT, () -> received.size() == 2);
        assertTrue(stream.isOpen());
        stream.close();
    }

    @Test
    void aConsumerMayCloseItsOwnStream() {
        EventStream[] holder = new EventStream[1];
        holder[0] = client.events(0, null, event -> {
            received.add(event);
            holder[0].close();
        });
        agent.publish("a", Map.of());
        agent.publish("b", Map.of());
        Poll.until("the first event", WAIT, () -> received.size() >= 1);
        Poll.until("the stream to stop", WAIT, () -> !holder[0].isOpen());
        assertEquals(1, received.size());
    }
}
