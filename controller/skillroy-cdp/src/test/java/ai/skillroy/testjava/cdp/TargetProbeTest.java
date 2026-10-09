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

import ai.skillroy.testjava.cdp.FakeDevTools.Behaviour;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TargetProbe} against a fake {@code /json} server and a hand-rolled WebSocket server (phase4-contracts.md
 * section 12.1 item 5): a target whose socket never answers is reported unresponsive within about its deadline while
 * the others are probed in parallel (shown by the sockets open at the same time), {@code /json/version} failing is
 * {@code UNREACHABLE} with one message whichever timer fired, and an endpoint that lists none of the target ids the
 * agent reports is not probed at all.
 */
class TargetProbeTest {

    private static final long DEADLINE_MS = 1000;

    /** What a whole probe may take by design: the two {@code /json} requests and the targets, a deadline each, and
     *  the 500 ms the gathering of the targets' results may add. Wall-clock bounds are loose by intent: parallelism is
     *  proven by the fake's peak of open sockets, not by timing (a cold or emulated JVM is slow). */
    private static long envelope(long deadlineMs) {
        return 3 * deadlineMs + 500;
    }

    private FakeDevTools devTools;

    @BeforeEach
    void start() throws IOException {
        devTools = new FakeDevTools();
    }

    @AfterEach
    void stop() {
        devTools.close();
    }

    private static CdpOptions options(long deadlineMs) {
        return CdpOptions.defaults().withProbeDeadlinePerTarget(Duration.ofMillis(deadlineMs));
    }

    @Test
    void aSilentTargetIsUnresponsiveWithinItsDeadlineWhileTheOthersAnswer() throws Exception {
        devTools.page("A", Behaviour.ANSWER).page("B", Behaviour.SILENT).page("C", Behaviour.ANSWER)
                .target("W", "service_worker", Behaviour.SILENT, true);
        long start = System.nanoTime();
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals("FakeChrome/152.0.0.0", probe.browserVersion());
        assertEquals("1.3", probe.protocolVersion());
        assertEquals(List.of("A", "B", "C", "W"), probe.targets().stream().map(TargetProbe.Target::id).toList());
        assertEquals(List.of("B"), probe.unresponsive().stream().map(TargetProbe.Target::id).toList());
        TargetProbe.Target b = probe.unresponsive().get(0);
        assertFalse(b.responsive());
        assertTrue(b.millis() >= DEADLINE_MS - 100 && b.millis() < 2 * DEADLINE_MS, "about one deadline: " + b);
        assertEquals("did not answer Runtime.evaluate within 1000 ms", b.error());
        for (String id : List.of("A", "C")) {
            TargetProbe.Target t = probe.targets().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertTrue(t.responsive(), t.toString());
            assertNull(t.error(), "the unrelated event before the answer is ignored: " + t);
            assertTrue(t.millis() < DEADLINE_MS, t.toString());
            assertEquals(1, devTools.requestsTo(id));
        }
        TargetProbe.Target w = probe.targets().get(3);
        assertTrue(w.responsive() && w.millis() == 0 && !w.isPage(), "other types are listed, not probed: " + w);
        assertEquals(0, devTools.requestsTo("W"));
        assertTrue(ms < envelope(DEADLINE_MS), "the whole probe took " + ms + " ms");
        assertTrue(probe.millis() < envelope(DEADLINE_MS), probe.toString());
        assertEquals(0, devTools.unmaskedFrames.get(), "client frames are masked");
        for (String text : devTools.received) {
            Map<String, Object> request = Json.parseObject(text);
            assertEquals(1L, request.get("id"), "the probe's message id");
            assertEquals("Runtime.evaluate", request.get("method"));
            assertEquals(Map.of("expression", "1", "returnByValue", true), request.get("params"));
        }
    }

    @Test
    void silentTargetsAreProbedInParallelNotOneAfterAnother() throws Exception {
        devTools.page("S1", Behaviour.SILENT).page("S2", Behaviour.SILENT).page("S3", Behaviour.SILENT)
                .page("OK", Behaviour.ANSWER);
        long deadline = 600;
        long start = System.nanoTime();
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(deadline));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(List.of("S1", "S2", "S3"), probe.unresponsive().stream().map(TargetProbe.Target::id).toList());
        assertTrue(devTools.peakOpenSockets.get() >= 3, "the three silent targets' sockets were open at the same"
                + " time (one after another, at most one is): peak " + devTools.peakOpenSockets.get());
        assertTrue(ms < envelope(deadline), "the whole probe took " + ms + " ms");
    }

    @Test
    void anErrorAnswerStillProvesTheTargetAnswers() throws Exception {
        devTools.page("E", Behaviour.ERROR_ANSWER);
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS));
        TargetProbe.Target e = probe.targets().get(0);
        assertTrue(e.responsive());
        assertEquals("Runtime.evaluate answered an error: Cannot find default execution context", e.error());
        assertTrue(probe.unresponsive().isEmpty());
    }

    @Test
    void aTargetThatClosesItsSocketIsUnresponsiveAtOnce() throws Exception {
        devTools.page("X", Behaviour.CLOSE);
        long start = System.nanoTime();
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS));
        long ms = (System.nanoTime() - start) / 1_000_000;
        TargetProbe.Target x = probe.unresponsive().get(0);
        assertTrue(x.error().startsWith("did not answer Runtime.evaluate: IOException: the target closed its"
                + " WebSocket (1000"), x.error());
        assertTrue(ms < DEADLINE_MS, "not awaited: " + ms + " ms");
    }

    @Test
    void aPageWithoutWebSocketUrlGetsTheDefaultOneOnTheEndpoint() throws Exception {
        devTools.target("N", "page", Behaviour.ANSWER, false);
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS));
        TargetProbe.Target n = probe.targets().get(0);
        assertEquals("ws://" + devTools.endpoint().authority() + "/devtools/page/N", n.webSocketDebuggerUrl());
        assertTrue(n.responsive(), "the endpoint serves its targets' sockets on its own port: " + n);
        assertEquals(1, devTools.requestsTo("N"));
    }

    /**
     * Security fix S44: a {@code webSocketDebuggerUrl} at another host or port, or of another scheme, is replaced by
     * the endpoint's own {@code ws://<authority>/devtools/page/<id>}: the probe never dials a host the list names.
     */
    @Test
    void aListedWebSocketAtAnotherHostIsProbedAtTheEndpointsOwnAuthority() throws Exception {
        devTools.page("F", Behaviour.ANSWER);
        devTools.targets.get(0).put("webSocketDebuggerUrl", "ws://198.51.100.7:9/devtools/page/F");
        devTools.page("G", Behaviour.ANSWER);
        devTools.targets.get(1).put("webSocketDebuggerUrl", "wss://" + devTools.endpoint().authority()
                + "/devtools/page/G");
        devTools.page("U", Behaviour.ANSWER);
        devTools.targets.get(2).put("webSocketDebuggerUrl", "ws://user@" + devTools.endpoint().authority()
                + "/devtools/page/U");
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS));
        for (TargetProbe.Target t : probe.targets()) {
            assertEquals("ws://" + devTools.endpoint().authority() + "/devtools/page/" + t.id(),
                    t.webSocketDebuggerUrl(), t.toString());
            assertTrue(t.responsive(), "dialled at the endpoint, which answers: " + t);
            assertEquals(1, devTools.requestsTo(t.id()), t.id());
        }
        assertTrue(TargetProbe.atEndpoint("ws://" + devTools.endpoint().authority() + "/devtools/page/X",
                devTools.endpoint()));
        assertFalse(TargetProbe.atEndpoint("ws://127.0.0.1:1/devtools/page/X", devTools.endpoint()));
        assertFalse(TargetProbe.atEndpoint("not a url", devTools.endpoint()));
    }

    /**
     * Security fix S44: a {@code /json/version} body over 64 KiB is {@code PROTOCOL}, refused by its
     * {@code Content-Length} before the body is read, or, without one, read no further than the bound; the 16 MiB
     * body is never read whole.
     */
    @Test
    void anOversizedVersionAnswerIsAProtocolErrorReadOnlyToItsBound() {
        for (boolean declared : new boolean[] {true, false}) {
            devTools.versionBytes = 16L << 20;
            devTools.versionDeclared = declared;
            devTools.versionWritten.set(0);
            long start = System.nanoTime();
            CdpException e = assertThrows(CdpException.class, () -> TargetProbe.run(devTools.endpoint(),
                    options(5000)));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertEquals(CdpException.Kind.PROTOCOL, e.kind(), e.getMessage());
            assertTrue(e.getMessage().endsWith("/json/version: the answer exceeds 65536 bytes"), e.getMessage());
            assertTrue(ms < 5000, "not read whole: " + ms + " ms");
            assertTrue(devTools.versionWritten.get() < (16L << 20), "the fake could not write it all: "
                    + devTools.versionWritten.get());
        }
    }

    /** Security fix S44: the bounded body reader stops at its bound, one buffer at a time, and keeps nothing. */
    @Test
    void theBoundedBodyReaderStopsAtItsBound() throws Exception {
        TargetProbe.CappedBody body = new TargetProbe.CappedBody(100, -1);
        java.util.concurrent.atomic.AtomicLong requested = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override
            public void request(long n) {
                requested.addAndGet(n);
            }

            @Override
            public void cancel() {
                cancelled.incrementAndGet();
            }
        });
        assertEquals(1, requested.get(), "one buffer at a time");
        body.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[60])));
        assertEquals(2, requested.get());
        body.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[60])));
        assertEquals(1, cancelled.get(), "cancelled past the bound");
        assertEquals(2, requested.get(), "nothing more requested");
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        TargetProbe.CappedBody declared = new TargetProbe.CappedBody(100, 101);
        declared.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override
            public void request(long n) {
                requested.addAndGet(1000);
            }

            @Override
            public void cancel() {
                cancelled.incrementAndGet();
            }
        });
        assertEquals(2, cancelled.get(), "a declared length over the bound is refused before any read");
        assertEquals(2, requested.get());
    }

    /** Security fix S44: a list of more than 1000 targets is {@code PROTOCOL}; 1000 are listed. */
    @Test
    void aListOfMoreThanAThousandTargetsIsAProtocolError() throws Exception {
        StringBuilder list = new StringBuilder("[");
        for (int i = 0; i < 1001; i++) {
            list.append(i == 0 ? "" : ",").append("{\"id\":\"W").append(i).append("\",\"type\":\"service_worker\"}");
        }
        devTools.listBody = list.append("]").toString();
        CdpException e = assertThrows(CdpException.class, () -> TargetProbe.run(devTools.endpoint(),
                options(DEADLINE_MS)));
        assertEquals(CdpException.Kind.PROTOCOL, e.kind());
        assertTrue(e.getMessage().endsWith("/json/list: it names 1001 targets, more than 1000"), e.getMessage());
        devTools.listBody = devTools.listBody.replace(",{\"id\":\"W1000\",\"type\":\"service_worker\"}", "");
        assertEquals(1000, TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS)).targets().size());
    }

    /** Security fix S43: a listed URL and title, and the browser version, are cut at 4096 characters. */
    @Test
    void longListedTextsAreCut() throws Exception {
        devTools.page("L", Behaviour.ANSWER);
        devTools.targets.get(0).put("title", "t".repeat(100_000));
        devTools.targets.get(0).put("url", "data:," + "u".repeat(100_000));
        devTools.versionBody = "{\"Browser\":\"" + "b".repeat(50_000) + "\"}";
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS));
        assertEquals(4096, probe.targets().get(0).title().length());
        assertEquals(4096, probe.targets().get(0).url().length());
        assertEquals(4096, probe.browserVersion().length());
    }

    @Test
    void anEndpointThatRefusesIsUnreachable() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        CdpException e = assertThrows(CdpException.class, () -> TargetProbe.run(CdpEndpoint.local(port),
                options(DEADLINE_MS)));
        assertEquals(CdpException.Kind.UNREACHABLE, e.kind());
        assertTrue(e.getMessage().startsWith("no DevTools endpoint answers at http://127.0.0.1:" + port
                + "/json/version: "), e.getMessage());
        assertTrue(e.targets().isEmpty());
    }

    @Test
    void aVersionRequestThatNeverAnswersIsUnreachableWithinTheDeadline() {
        devTools.versionHangs = true;
        long start = System.nanoTime();
        CdpException e = assertThrows(CdpException.class, () -> TargetProbe.run(devTools.endpoint(),
                options(500)));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(CdpException.Kind.UNREACHABLE, e.kind());
        assertEquals("no DevTools endpoint answers at " + devTools.endpoint().httpUri()
                + "/json/version: no answer within 500 ms", e.getMessage(), "one message, whichever timer fires");
        assertTrue(ms < 1500, "bounded: " + ms + " ms");
    }

    @Test
    void aTimeoutOfTheHttpClientItselfReadsAsTheCallersOwnTimeout() {
        assertEquals("no answer within 500 ms", TargetProbe.requestFailure(
                new HttpTimeoutException("request timed out"), 500), "the request timer");
        assertEquals("no answer within 500 ms", TargetProbe.requestFailure(
                new HttpConnectTimeoutException("HTTP connect timed out"), 500), "the connect timer");
        assertEquals("ConnectException: Connection refused", TargetProbe.requestFailure(
                new ConnectException("Connection refused"), 500));
        assertTrue(TargetProbe.REQUEST_TIMEOUT_MARGIN_MS > 0, "the request's own timer is the later one");
    }

    @Test
    void anEndpointThatListsNoneOfTheExpectedTargetsIsNotProbed() throws Exception {
        devTools.page("A", Behaviour.ANSWER).page("B", Behaviour.SILENT).target("W", "service_worker",
                Behaviour.ANSWER, true);
        assertNull(TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS), Set.of("X")), "another browser's");
        assertTrue(devTools.received.isEmpty(), "none of its pages was evaluated");
        assertEquals(0, devTools.peakOpenSockets.get());

        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS), Set.of("X", "A"));
        assertEquals(List.of("B"), probe.unresponsive().stream().map(TargetProbe.Target::id).toList());
        assertEquals(1, devTools.requestsTo("A"));
        assertTrue(TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS), Set.of("W")) != null,
                "a listed target of any type counts");
        assertTrue(TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS), Set.of()) != null, "no check");
    }

    @Test
    void aVersionErrorStatusIsUnreachable() {
        devTools.versionStatus = 500;
        CdpException e = assertThrows(CdpException.class, () -> TargetProbe.run(devTools.endpoint(),
                options(DEADLINE_MS)));
        assertEquals(CdpException.Kind.UNREACHABLE, e.kind());
        assertTrue(e.getMessage().endsWith("/json/version: HTTP 500"), e.getMessage());
    }

    @Test
    void aVersionThatIsNotAnObjectIsUnreachable() {
        devTools.versionBody = "[1]";
        CdpException e = assertThrows(CdpException.class, () -> TargetProbe.run(devTools.endpoint(),
                options(DEADLINE_MS)));
        assertEquals(CdpException.Kind.UNREACHABLE, e.kind());
        assertTrue(e.getMessage().endsWith("the answer is not a JSON object"), e.getMessage());
    }

    @Test
    void aListThatIsNotAnArrayIsAProtocolError() {
        devTools.listBody = "{\"not\":\"a list\"}";
        CdpException e = assertThrows(CdpException.class, () -> TargetProbe.run(devTools.endpoint(),
                options(DEADLINE_MS)));
        assertEquals(CdpException.Kind.PROTOCOL, e.kind());
        assertTrue(e.getMessage().endsWith("/json/list: the answer is not a JSON array"), e.getMessage());
    }

    @Test
    void noTargetsIsAnEmptyProbe() throws Exception {
        TargetProbe probe = TargetProbe.run(devTools.endpoint(), options(DEADLINE_MS));
        assertTrue(probe.targets().isEmpty());
        assertTrue(probe.unresponsive().isEmpty());
        assertEquals(devTools.endpoint(), probe.endpoint());
    }
}
