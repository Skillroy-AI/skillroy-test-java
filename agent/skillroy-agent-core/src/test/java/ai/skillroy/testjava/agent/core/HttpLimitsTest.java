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

import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.Auth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The bounded control server (contract amendment A2, hardening item H1): at most {@code httpThreads} connections,
 * each owning one thread, with the oldest connection that has not authenticated closed to make room; absolute
 * deadlines for request heads; a 16 KB head; a budget for bodies of connections that have not authenticated; at
 * most eight {@code /events} streams. After each abuse an authenticated {@code /ping} still works.
 */
class HttpLimitsTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;
    private final List<Socket> sockets = new ArrayList<Socket>();
    private final List<HttpURLConnection> streams = new ArrayList<HttpURLConnection>();
    /** HTTP threads of agents that earlier tests closed and that may not have ended yet. */
    private final List<Thread> earlier = new ArrayList<Thread>();

    @BeforeEach
    void reset() {
        TestState.reset();
        earlier.addAll(httpPoolThreadsOfAnyAgent());
    }

    @AfterEach
    void cleanUp() {
        SseStream.heartbeatMs = 15000L;
        ControlServer.headDeadlineMs = 5000L;
        ControlServer.bodyDeadlineMs = 30000L;
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed by the server.
            }
        }
        for (HttpURLConnection c : streams) {
            c.disconnect();
        }
        if (agent != null) {
            agent.close();
        }
    }

    /** Live threads of bounded HTTP pools ({@code skillroy-http-N}; not the call pool or the acceptor). */
    private static List<Thread> httpPoolThreadsOfAnyAgent() {
        List<Thread> threads = new ArrayList<Thread>();
        for (Thread t : CorePrimitives.allThreads()) {
            if (t.isAlive() && t.getName().matches("skillroy-http-\\d+")) {
                threads.add(t);
            }
        }
        return threads;
    }

    /** Names of the live HTTP pool threads of this test's agent. */
    private List<String> httpPoolThreads() {
        List<String> names = new ArrayList<String>();
        for (Thread t : httpPoolThreadsOfAnyAgent()) {
            if (!earlier.contains(t)) {
                names.add(t.getName());
            }
        }
        return names;
    }

    private ControlServer server() {
        return agent.runtime.controlServer();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", agent.runtime.port());
        sockets.add(socket);
        return socket;
    }

    /** A connection that sends half a request head and so holds one HTTP thread until the head deadline. */
    private Socket halfRequest() throws IOException {
        Socket socket = connect();
        OutputStream out = socket.getOutputStream();
        out.write(("GET /schema HTTP/1.1\r\nHost: 127.0.0.1:" + agent.runtime.port() + "\r\n")
                .getBytes(AgentHarness.UTF8));
        out.flush();
        return socket;
    }

    /** Milliseconds until the server closes the connection (EOF or reset), failing after {@code maxMs}. */
    private static long msUntilClosed(Socket socket, long maxMs) throws IOException {
        long start = System.nanoTime();
        socket.setSoTimeout((int) maxMs);
        InputStream in = socket.getInputStream();
        try {
            while (in.read() >= 0) {
                // A reply (431, 400, ...) may come first; wait for the close.
            }
        } catch (SocketTimeoutException e) {
            fail("the connection was not closed within " + maxMs + " ms");
        } catch (IOException reset) {
            // Closed with unread bytes: the peer resets. Also a close.
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private boolean pingAnswers() {
        try {
            return agent.get("/ping").status == 200;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void silentConnectionsBeyondHttpThreadsCloseTheOldestAndTheRestEndAtTheHeadDeadline() throws Exception {
        agent = AgentHarness.start(tmp, "httpThreads", "4");
        List<Socket> silent = new ArrayList<Socket>();
        for (int i = 0; i < 10; i++) {
            silent.add(connect());
            if (i < 4) {
                final int open = i + 1;
                AgentHarness.await("connection " + open + " has a thread", 30000,
                        () -> server().openConnections() == open);
            }
        }
        AgentHarness.await("six connections closed to make room", 30000, () -> server().evictedCount() == 6);
        for (int i = 0; i < 6; i++) {
            assertTrue(msUntilClosed(silent.get(i), 4000) < 4000, "the oldest are closed at once: " + i);
        }
        assertEquals(4, httpPoolThreads().size(), "no thread beyond httpThreads: " + httpPoolThreads());
        assertTrue(server().maxOpenConnections() <= 4, "open " + server().maxOpenConnections());
        for (int i = 6; i < 10; i++) {
            long ms = msUntilClosed(silent.get(i), 20000);
            assertTrue(ms < 20000, "closed at the head deadline: " + ms + " ms");
        }
        AgentHarness.await("no connection left open", 30000, () -> server().openConnections() == 0);
        assertTrue(String.valueOf(agent.runtime.context().log().tail(2000)).contains("control server busy"),
                "the saturation is logged");
        assertEquals(200, agent.get("/ping").status);
        assertEquals(0, server().rejectedCount(), "no authenticated connection was in the way");
    }

    @Test
    void aControllerGetsInWhileProcessesWithoutTheKeySquatOnEverySlot() throws Exception {
        agent = AgentHarness.start(tmp, "httpThreads", "4");
        for (int i = 0; i < 40; i++) {
            halfRequest();
        }
        AgentHarness.await("all but four squatters closed", 30000, () -> server().evictedCount() >= 36);
        assertEquals(4, httpPoolThreads().size(), httpPoolThreads().toString());
        long start = System.nanoTime();
        assertEquals(200, agent.get("/ping").status, "the newest connection displaces the oldest squatter");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
        assertTrue(server().maxOpenConnections() <= 4, "open " + server().maxOpenConnections());
    }

    @Test
    void authenticatedConnectionsAreNeverDisplacedAndANewOneIsThenRefused() throws Exception {
        agent = AgentHarness.start(tmp, "httpThreads", "2");
        List<AgentHarness.RawClient> clients = new ArrayList<AgentHarness.RawClient>();
        try {
            for (int i = 0; i < 2; i++) {
                AgentHarness.RawClient client = agent.new RawClient();
                clients.add(client);
                List<String> nonces = new ArrayList<String>();
                client.write(client.request("GET", "/schema", null, nonces));
                assertEquals(200, client.read(nonces.get(0)).status);
            }
            Socket extra = halfRequest();
            assertTrue(msUntilClosed(extra, 10000) < 10000, "refused at once");
            assertEquals(1, server().rejectedCount());
            assertEquals(0, server().evictedCount());
            for (AgentHarness.RawClient client : clients) {
                List<String> nonces = new ArrayList<String>();
                client.write(client.request("GET", "/capabilities", null, nonces));
                assertEquals(200, client.read(nonces.get(0)).status, "the kept-alive controller works on");
            }
        } finally {
            for (AgentHarness.RawClient client : clients) {
                client.close();
            }
        }
        AgentHarness.await("served again", 30000, this::pingAnswers);
    }

    @Test
    void aTrickleOfOneBytePerSecondIsClosedAtTheAbsoluteHeadDeadline() throws Exception {
        agent = AgentHarness.start(tmp);
        final Socket socket = connect();
        final byte[] head = ("GET /schema HTTP/1.1\r\nHost: 127.0.0.1:" + agent.runtime.port() + "\r\nX-Pad: "
                + ControlServerTest.repeat('p', 200)).getBytes(AgentHarness.UTF8);
        final AtomicLong sent = new AtomicLong();
        Thread trickle = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream out = socket.getOutputStream();
                    for (byte b : head) {
                        out.write(b);
                        out.flush();
                        sent.incrementAndGet();
                        Thread.sleep(1000);
                    }
                } catch (IOException | InterruptedException closed) {
                    // The server closed the connection.
                }
            }
        }, "trickle");
        trickle.setDaemon(true);
        long start = System.nanoTime();
        trickle.start();
        msUntilClosed(socket, 30000);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms >= ControlServer.headDeadlineMs - 1000 && ms < ControlServer.headDeadlineMs + 5000,
                "closed after " + ms + " ms, " + sent.get() + " bytes, although every read got a byte in time");
        assertEquals(200, agent.get("/ping").status);
    }

    @Test
    void aRequestLineOfAMegabyteGets431AndClose() throws Exception {
        agent = AgentHarness.start(tmp);
        final Socket socket = connect();
        socket.setSoTimeout(30000);
        final byte[] line = ("GET /" + ControlServerTest.repeat('a', 1024 * 1024)).getBytes(AgentHarness.UTF8);
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket.getOutputStream().write(line);
                } catch (IOException closed) {
                    // Expected once the server has answered.
                }
            }
        }, "big-line");
        writer.setDaemon(true);
        writer.start();
        AgentHarness.Reply reply = AgentHarness.readReply(socket.getInputStream());
        assertEquals(431, reply.status);
        assertEquals("close", reply.header("Connection"));
        assertTrue(msUntilClosed(socket, 10000) < 10000);
        assertEquals(200, agent.get("/ping").status);
        String log = String.valueOf(agent.runtime.context().log().tail(RingLog.CAPACITY));
        assertTrue(!log.contains("aaaaaaaa"), "nothing of the request was logged");
    }

    @Test
    void anUnauthenticatedPostIsAnswered401WithoutReadingItsBody() throws Exception {
        agent = AgentHarness.start(tmp, "httpThreads", "2");
        for (String auth : new String[] {"", "Authorization: Bearer " + agent.token + "\r\n"}) {
            Socket socket = connect();
            String head = "POST /rpc HTTP/1.1\r\nHost: 127.0.0.1:" + agent.runtime.port() + "\r\n" + auth
                    + "Content-Length: " + ControlServer.MAX_BODY_BYTES + "\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(AgentHarness.UTF8));
            socket.setSoTimeout(10000);
            long start = System.nanoTime();
            AgentHarness.Reply reply = AgentHarness.readReply(socket.getInputStream());
            assertEquals(401, reply.status);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000, "no body was awaited");
            socket.close();
        }
        AgentHarness.await("the threads are free again", 30000, this::pingAnswers);
    }

    /**
     * Phase 6 security fix, pass 2, P2-L1-F1: a process without the key cannot hold the body budget against the
     * controller. Two connections each send a right-shaped {@code Authorization} and body hash, declare 8 MB, send
     * 6 MB and stall. The MAC fails on the head, so each gets the constant 401 and none of its body is buffered, and
     * a signed call on a fresh connection (a controller's first request on a new connection) is served meanwhile.
     * Before the fix the two bodies held the whole budget and that call was answered 503.
     */
    @Test
    void connectionsWithoutTheKeyCannotHoldTheBodyBudgetAgainstASignedCallOnAFreshConnection() throws Exception {
        ControlServer.bodyDeadlineMs = 120000L;
        agent = AgentHarness.start(tmp);
        String garbage = Auth.formatAuthorization(Auth.newNonce(), ControlServerTest.repeat('0', 64));
        int port = agent.runtime.port();
        final byte[] part = new byte[6 * 1024 * 1024];
        final List<Integer> statuses = java.util.Collections.synchronizedList(new ArrayList<Integer>());
        for (int i = 0; i < 2; i++) {
            final Socket socket = connect();
            socket.setSoTimeout(60000);
            socket.getOutputStream().write(("POST /rpc HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: "
                    + garbage + "\r\n" + AgentHarness.bodyHashLine(part) + "Content-Length: "
                    + ControlServer.MAX_BODY_BYTES + "\r\n\r\n").getBytes(AgentHarness.UTF8));
            socket.getOutputStream().flush();
            daemon("stall-writer-" + i, new Runnable() {
                @Override
                public void run() {
                    try {
                        socket.getOutputStream().write(part);
                        socket.getOutputStream().flush();
                    } catch (IOException refused) {
                        // Answered and closed before the body went out: the point of the fix.
                    }
                }
            });
            daemon("stall-reader-" + i, new Runnable() {
                @Override
                public void run() {
                    try {
                        statuses.add(Integer.valueOf(AgentHarness.readReply(socket.getInputStream()).status));
                    } catch (IOException noReply) {
                        statuses.add(Integer.valueOf(-1));
                    }
                }
            });
        }
        // Old behaviour: the two bodies grow to 8 MB buffers each, the whole budget. New: both are answered at once.
        AgentHarness.await("the server has dealt with the two stalled bodies", 60000,
                () -> server().unauthenticatedBodyBytes() == ControlServer.UNAUTHENTICATED_BODY_BUDGET
                        || statuses.size() == 2);
        byte[] call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.info\"}".getBytes(AgentHarness.UTF8);
        assertEquals(200, agent.rawPost("/rpc", "Content-Length: " + call.length + "\r\n", call),
                "a signed call on a fresh connection while connections without the key stall");
        assertEquals(0L, server().unauthenticatedBodyBytes(), "no byte of a body without the key was buffered");
        AgentHarness.await("both answered", 30000, () -> statuses.size() == 2);
        assertEquals(java.util.Arrays.asList(401, 401), statuses);
    }

    /**
     * The budget stays as defence in depth (P2-L1-F1): bodies of requests whose heads verified, on connections that
     * have not authenticated yet (only a holder of the key, or of a head captured within this session, sends one),
     * share 16 MB; a further one is 503 with {@code Retry-After}. An authenticated connection is not limited by it.
     */
    @Test
    void bodiesOfVerifiedHeadsOnConnectionsNotYetAuthenticatedShareABoundedBudget() throws Exception {
        // The two bodies below stay incomplete on purpose; however slow the machine, they must still be held (and
        // so hold the budget) when the third request comes in, not cut off at the 30 s body deadline before it.
        ControlServer.bodyDeadlineMs = 120000L;
        agent = AgentHarness.start(tmp);
        int port = agent.runtime.port();
        int declared = ControlServer.MAX_BODY_BYTES;
        byte[] whole = new byte[declared];
        byte[] part = new byte[6 * 1024 * 1024];
        // Two connections that each hold 6 MB of an 8 MB body: buffers of 8 MB each, the whole budget.
        for (int i = 0; i < 2; i++) {
            Socket socket = connect();
            socket.getOutputStream().write(signedHead(port, whole).getBytes(AgentHarness.UTF8));
            socket.getOutputStream().write(part);
            socket.getOutputStream().flush();
        }
        // A returned write only means the bytes are in socket buffers (several MB on Linux loopback): wait until the
        // server has read past 4 MB of each body and so grown both buffers to 8 MB. Otherwise the third connection
        // can reserve its first buffers ahead of the second one's last growth, wait for a body that never comes, and
        // the 503 goes to the second connection instead.
        AgentHarness.await("the two bodies hold the whole budget", 60000,
                () -> server().unauthenticatedBodyBytes() == ControlServer.UNAUTHENTICATED_BODY_BUDGET);
        Socket third = connect();
        third.setSoTimeout(30000);
        third.getOutputStream().write(signedHead(port, whole).getBytes(AgentHarness.UTF8));
        try {
            third.getOutputStream().write(new byte[256 * 1024]);
        } catch (IOException answeredEarly) {
            // The 503 may come first.
        }
        AgentHarness.Reply reply = AgentHarness.readReply(third.getInputStream());
        assertEquals(503, reply.status, reply.body);
        assertEquals("1", reply.header("Retry-After"));
        assertEquals(ControlServer.UNAUTHENTICATED_BODY_BUDGET, server().unauthenticatedBodyBytes(),
                "the refused body holds nothing and the budget was never exceeded");
        for (Socket socket : sockets) {
            socket.close();
        }
        // The budget is released with the connections; an authenticated large body is not limited by it.
        AgentHarness.await("budget released", 60000, () -> server().unauthenticatedBodyBytes() == 0L);
        byte[] big = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) ' ');
        byte[] call = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.info\"}]".getBytes(AgentHarness.UTF8);
        System.arraycopy(call, 0, big, 0, call.length);
        assertEquals(200, agent.rawPost("/rpc", "Content-Length: " + big.length + "\r\n", big));
    }

    /** The head of a signed {@code POST /rpc} for {@code body} (with its body hash), without the body. */
    private String signedHead(int port, byte[] body) {
        return "POST /rpc HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: "
                + agent.credentials("POST", "/rpc", body)[1] + "\r\n" + AgentHarness.bodyHashLine(body)
                + "Content-Length: " + body.length + "\r\n\r\n";
    }

    private static void daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private HttpURLConnection openStream() throws IOException {
        AgentHarness.Stream stream = agent.new Stream("/events");
        return stream.connection;
    }

    @Test
    void atMostEightEventStreamsAndTheNinthGets503() throws Exception {
        agent = AgentHarness.start(tmp);
        SseStream.heartbeatMs = 100L;
        for (int i = 0; i < ControlServer.MAX_STREAMS; i++) {
            AgentHarness.Stream s = agent.new Stream("/events");
            streams.add(s.connection);
            assertEquals(200, s.verify(), "stream " + (i + 1));
        }
        AgentHarness.Stream ninth = agent.new Stream("/events");
        assertEquals(503, ninth.verify());
        String body = ninth.errorBody;
        assertTrue(body.contains("at most 8"), body);
        ninth.connection.disconnect();
        assertEquals(200, agent.get("/ping").status, "requests are still served");
        assertEquals("EMBEDDED", agent.valueMap("agent.info", null).get("loadMode"));

        streams.remove(0).disconnect();
        AgentHarness.await("a slot freed by a stream that ended", 60000, () -> {
            HttpURLConnection c = openStream();
            if (c.getResponseCode() == 200) {
                streams.add(c);
                return true;
            }
            c.disconnect();
            return false;
        });
    }

    @Test
    void stopClosesIdleAndHalfReadConnectionsAtOnceAndLeavesNoThread() throws Exception {
        agent = AgentHarness.start(tmp);
        Socket half = halfRequest();
        AgentHarness.RawClient idle = agent.new RawClient();
        try {
            List<String> nonces = new ArrayList<String>();
            idle.write(idle.request("GET", "/schema", null, nonces));
            assertEquals(200, idle.read(nonces.get(0)).status);
            AgentHarness.await("both connections open", 30000, () -> server().openConnections() == 2);
            long start = System.nanoTime();
            agent.close();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < AgentRuntime.STOP_GRACE_MS, "nothing in a request: no grace period needed, " + ms
                    + " ms");
            assertTrue(msUntilClosed(half, 5000) < 5000);
            assertEquals(-1, ControlServerTest.readOrReset(idle.in));
        } finally {
            idle.close();
        }
        assertEquals(0, server().openConnections());
        AgentHarness.await("agent threads gone", 30000, () -> AgentHarness.skillroyThreads().isEmpty());
        agent = null;
    }

    @Test
    void streamCapFollowsSmallPools() {
        assertEquals(8, ControlServer.maxStreams(32));
        assertEquals(8, ControlServer.maxStreams(1024));
        assertEquals(2, ControlServer.maxStreams(4));
        assertEquals(1, ControlServer.maxStreams(2));
    }
}
