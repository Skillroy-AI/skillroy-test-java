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
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.TopicFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP surface (hardening items H1 and H2): /ping, HMAC authentication of requests and replies, Host/Origin checks,
 * routing, methods, body framing and cap, keep-alive and pipelining, /schema, /capabilities, and what reaches the
 * agent log.
 */
class ControlServerTest {

    private static final String CLIENT_NONCE = "client-Nonce_0123456789";

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @BeforeEach
    void start() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp);
    }

    @AfterEach
    void stop() {
        if (agent != null) {
            agent.close();
        }
    }

    @Test
    void pingEchoesTheNonceAndDescribesTheAgent() throws Exception {
        AgentHarness.Reply reply = agent.get("/ping?nonce=" + CLIENT_NONCE);
        assertEquals(200, reply.status);
        assertEquals("application/json; charset=utf-8", reply.header("Content-Type"));
        assertEquals(agent.session(), reply.header(Auth.HEADER_SESSION));
        Map<String, Object> ping = reply.json();
        assertEquals(CLIENT_NONCE, ping.get("nonce"));
        assertEquals(CLIENT_NONCE, ping.get("clientNonce"));
        assertEquals(agent.session(), ping.get(Protocol.KEY_SESSION_ID));
        assertTrue(Auth.isValidSessionId(agent.session()));
        assertEquals(agent.runtime.nonce(), ping.get("agentNonce"));
        assertEquals(Protocol.VERSION, ping.get("protocol"));
        assertEquals(Long.valueOf(JvmIds.pid()), ping.get("pid"));
        assertEquals(Long.valueOf(JvmIds.startTimeMs()), ping.get("startTimeMs"));
        assertEquals("EMBEDDED", ping.get("loadMode"));
        assertEquals(Boolean.FALSE, ping.get("readOnly"));
        assertTrue(ping.containsKey("ready"));
    }

    @Test
    void everyAgentThreadIsANamedDaemonAndNoJdkServerThreadExists() throws Exception {
        assertEquals(200, agent.get("/ping").status);
        boolean acceptor = false;
        for (Thread t : CorePrimitives.allThreads()) {
            String name = t.getName();
            assertFalse(name.equals("HTTP-Dispatcher") || name.equals("server-timer")
                    || name.equals("idle-timeout-task") || name.startsWith("skillroy-http-dispatcher"),
                    "a JDK HTTP server thread: " + name);
            if (name.startsWith("skillroy-")) {
                assertTrue(t.isDaemon(), name);
                acceptor |= name.equals("skillroy-http-accept");
            }
        }
        assertTrue(acceptor, "the agent's own acceptor thread runs");
    }

    @Test
    void pingRejectsAnotherProtocolMajor() throws Exception {
        assertEquals(200, agent.get("/ping?protocol=0.7").status);
        AgentHarness.Reply reply = agent.get("/ping?protocol=1.0");
        assertEquals(400, reply.status, "an authenticated 400 carries a reply MAC (checked by the harness)");
        assertEquals(Protocol.VERSION, reply.json().get("protocol"));
    }

    private void assertUnauthorized(AgentHarness.Reply reply, String what) {
        assertEquals(401, reply.status, what);
        assertEquals(Auth.SCHEME, reply.header("WWW-Authenticate"), what);
        assertNull(reply.header(Auth.HEADER_MAC), "a 401 carries no MAC: " + what);
        assertNull(reply.header(Auth.HEADER_SESSION), "a 401 does not name the session: " + what);
        assertEquals("close", reply.header("Connection"), what);
        assertFalse(reply.body.contains(agent.token), what);
    }

    @Test
    void everyEndpointNeedsSkillroyHmacCredentialsAndTheOldBearerSchemeIsRefused() throws Exception {
        String other = repeat('f', 64);
        for (String path : new String[] {"/ping", "/schema", "/capabilities", "/events", "/nowhere"}) {
            String target = AgentHarness.withPingNonce(path);
            assertUnauthorized(agent.sendWith("GET", target, null, null), "none " + path);
            assertUnauthorized(agent.sendWith("GET", target, null, "Bearer " + agent.token), "bearer " + path);
            assertUnauthorized(agent.sendWith("GET", target, null, "bearer " + agent.token), "bearer lc " + path);
            String good = agent.credentials("GET", target, null)[1];
            assertUnauthorized(agent.sendWith("GET", target, null, good.replace(Auth.SCHEME, "skillroy-hmac")),
                    "scheme case " + path);
            String wrongKey = AgentHarness.credentials(other, agent.session(), "GET", target, null)[1];
            assertUnauthorized(agent.sendWith("GET", target, null, wrongKey), "wrong key " + path);
        }
        assertUnauthorized(agent.sendWith("POST", "/rpc", "{}".getBytes(AgentHarness.UTF8), null), "rpc none");
    }

    @Test
    void theCredentialsAreBoundToFormPathMethodBodyAndSession() throws Exception {
        String nonce = Auth.newNonce();
        // The hello form is for /ping only, the request form for everything else.
        assertUnauthorized(agent.sendWith("GET", "/schema", null, Auth.helloAuthorization(agent.token, nonce)),
                "hello form on /schema");
        assertUnauthorized(agent.sendWith("GET", "/ping?nonce=" + nonce, null, Auth.requestAuthorization(
                agent.token, agent.session(), nonce, "GET", "/ping?nonce=" + nonce, Auth.EMPTY_BODY_SHA256)),
                "request form on /ping");
        assertUnauthorized(agent.sendWith("GET", "/ping?nonce=" + nonce, null,
                Auth.helloAuthorization(agent.token, Auth.newNonce())), "hello MAC of another nonce");
        assertUnauthorized(agent.sendWith("GET", "/ping?nonce=" + nonce + "&nonce=" + nonce, null,
                Auth.helloAuthorization(agent.token, nonce)), "nonce twice");
        assertUnauthorized(agent.sendWith("GET", "/ping", null, Auth.helloAuthorization(agent.token, nonce)),
                "no nonce");
        // The request MAC covers the method, the exact target and the body.
        String forSchema = agent.credentials("GET", "/schema", null)[1];
        assertUnauthorized(agent.sendWith("GET", "/capabilities", null, forSchema), "other path");
        assertUnauthorized(agent.sendWith("GET", "/schema?x=1", null, forSchema), "other query");
        String forDelete = agent.credentials("DELETE", "/schema", null)[1];
        assertUnauthorized(agent.sendWith("GET", "/schema", null, forDelete), "other method");
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.info\"}".getBytes(AgentHarness.UTF8);
        byte[] changed = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"agent.info\"}".getBytes(AgentHarness.UTF8);
        String forBody = agent.credentials("POST", "/rpc", body)[1];
        assertUnauthorized(agent.sendWith("POST", "/rpc", changed, forBody), "other body");
        assertEquals(200, agent.sendWith("POST", "/rpc", body, forBody).status, "the same credentials, right body");
        // Another session: what was valid against an earlier agent instance is useless here.
        String earlierSession = repeat('0', 32);
        assertUnauthorized(agent.sendWith("GET", "/schema", null, AgentHarness.credentials(agent.token,
                earlierSession, "GET", "/schema", null)[1]), "other session");
    }

    @Test
    void aRequestCapturedFromOneAgentStartIsRefusedByTheNextWithTheSameToken() throws Exception {
        agent.close();
        agent = AgentHarness.start(tmp.resolve("one"), "token", "a-token-the-operator-fixed-0123456789");
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.info\"}".getBytes(AgentHarness.UTF8);
        String captured = agent.credentials("POST", "/rpc", body)[1];
        assertEquals(200, agent.sendWith("POST", "/rpc", body, captured).status);
        String firstSession = agent.session();
        agent.close();
        agent = AgentHarness.start(tmp.resolve("two"), "token", "a-token-the-operator-fixed-0123456789");
        assertNotEquals(firstSession, agent.session(), "a new session id at every start");
        assertUnauthorized(agent.sendWith("POST", "/rpc", body, captured), "replayed against the restarted agent");
        assertEquals(200, agent.get("/ping").status);
    }

    @Test
    void aRepeatedAuthorizationHeaderIsRefused() throws Exception {
        int port = agent.runtime.port();
        String line = agent.authLine("GET", "/schema");
        AgentHarness.Reply reply = agent.rawGet("/schema", "Host: 127.0.0.1:" + port, line, line);
        assertEquals(401, reply.status);
        assertEquals(200, agent.rawGet("/schema", "Host: 127.0.0.1:" + port, line).status);
    }

    @Test
    void theReplyMacCoversStatusAndBody() throws Exception {
        String nonce = Auth.newNonce();
        AgentHarness.Reply reply = agent.sendWith("GET", "/ping?nonce=" + nonce, null,
                Auth.helloAuthorization(agent.token, nonce));
        assertEquals(200, reply.status);
        String mac = reply.header(Auth.HEADER_MAC);
        String session = reply.header(Auth.HEADER_SESSION);
        assertTrue(Auth.verifyResponse(agent.token, session, nonce, 200, Auth.sha256hex(reply.bytes), mac));
        byte[] forged = reply.body.replace("\"ready\":", "\"ready\" :").getBytes(AgentHarness.UTF8);
        assertFalse(Auth.verifyResponse(agent.token, session, nonce, 200, Auth.sha256hex(forged), mac), "body");
        assertFalse(Auth.verifyResponse(agent.token, session, nonce, 201, Auth.sha256hex(reply.bytes), mac),
                "status");
        assertFalse(Auth.verifyResponse(agent.token, session, Auth.newNonce(), 200, Auth.sha256hex(reply.bytes),
                mac), "another request's nonce");
    }

    @Test
    void wrongHostOrAnyOriginIsForbidden() throws Exception {
        int port = agent.runtime.port();
        assertEquals(200, agent.rawGet("/schema", "Host: 127.0.0.1:" + port, agent.authLine("GET", "/schema"))
                .status);
        assertEquals(200, agent.rawGet("/schema", "Host: localhost:" + port, agent.authLine("GET", "/schema"))
                .status);
        assertEquals(403, agent.rawGet("/schema", "Host: evil.example:" + port, agent.authLine("GET", "/schema"))
                .status);
        assertEquals(403, agent.rawGet("/schema", "Host: 127.0.0.1:1", agent.authLine("GET", "/schema")).status);
        assertEquals(403, agent.rawGet("/schema", agent.authLine("GET", "/schema")).status, "no Host");
        assertEquals(403, agent.rawGet("/schema", "Host: 127.0.0.1:" + port, agent.authLine("GET", "/schema"),
                "Origin: null").status);
        assertEquals(403, agent.rawGet("/schema", "Host: localhost:" + port, agent.authLine("GET", "/schema"),
                "Origin: http://localhost:" + port).status);
        assertEquals(401, agent.rawGet("/schema", "Host: evil.example:" + port).status, "authentication first");
        assertEquals(401, agent.rawGet("/schema", "Host: evil.example:" + port, "Origin: null").status);
    }

    @Test
    void unknownPathsAre404AndWrongMethods405() throws Exception {
        assertEquals(404, agent.get("/nowhere").status);
        assertEquals(404, agent.get("/rpc/extra").status);
        assertEquals(404, agent.get("/p%69ng").status, "paths are compared as sent, not decoded");
        AgentHarness.Reply getRpc = agent.get("/rpc");
        assertEquals(405, getRpc.status);
        assertEquals("POST", getRpc.header("Allow"));
        assertEquals(405, agent.send("POST", "/schema", "{}", true).status);
        assertEquals(405, agent.send("DELETE", "/schema", null, true).status);
        assertEquals(405, agent.send("PUT", "/capabilities", "{}", true).status);
        AgentHarness.Reply head = agent.send("HEAD", "/ping", null, true);
        assertEquals(405, head.status);
        assertEquals("", head.body);
        assertEquals(401, agent.send("HEAD", "/ping", null, false).status);
        String pingNonce = Auth.newNonce();
        assertUnauthorized(agent.sendWith("POST", "/ping?nonce=" + pingNonce, "{}".getBytes(AgentHarness.UTF8),
                Auth.helloAuthorization(agent.token, pingNonce)), "the hello MAC does not cover a body");
        assertFalse(String.valueOf(agent.runtime.context().log().tail(500)).contains("request HEAD"),
                "HEAD requests are answered without errors");
    }

    @Test
    void bodiesOverEightMegabytesAre413AndTransferEncodingIs501() throws Exception {
        byte[] big = new byte[ControlServer.MAX_BODY_BYTES + 1024];
        java.util.Arrays.fill(big, (byte) ' ');
        assertEquals(413, agent.rawPost("/rpc", "Content-Length: " + big.length + "\r\n", big),
                "declared length");
        assertEquals(501, agent.rawPost("/rpc", "Transfer-Encoding: chunked\r\n", chunk(big)), "chunked");
        assertEquals(400, agent.rawPost("/rpc", "Content-Length: 5\r\nContent-Length: 5\r\n",
                "{}   ".getBytes(AgentHarness.UTF8)), "Content-Length twice");
        assertEquals(400, agent.rawPost("/rpc", "Content-Length: -5\r\n", new byte[0]), "negative");
        assertEquals(400, agent.rawPost("/rpc", "Content-Length: 0x10\r\n", new byte[0]), "not decimal");
        byte[] small = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.info\"}".getBytes(AgentHarness.UTF8);
        assertEquals(200, agent.rawPost("/rpc", "Content-Length: " + small.length + "\r\n", small));
        assertEquals(411, agent.rawPost("/rpc", "", new byte[0]), "a POST needs Content-Length");
        assertEquals(200, agent.get("/ping").status);
    }

    private static byte[] chunk(byte[] body) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int size = 64 * 1024;
        for (int off = 0; off < body.length; off += size) {
            int n = Math.min(size, body.length - off);
            byte[] head = (Integer.toHexString(n) + "\r\n").getBytes(AgentHarness.UTF8);
            out.write(head, 0, head.length);
            out.write(body, off, n);
            out.write('\r');
            out.write('\n');
        }
        byte[] end = "0\r\n\r\n".getBytes(AgentHarness.UTF8);
        out.write(end, 0, end.length);
        return out.toByteArray();
    }

    @Test
    void malformedHeadsAre400AndClosed() throws Exception {
        int port = agent.runtime.port();
        String[] heads = {
            "GET  /ping HTTP/1.1\r\n\r\n",
            "get /ping HTTP/1.1\r\n\r\n",
            "GET http://127.0.0.1/ping HTTP/1.1\r\n\r\n",
            "GET /ping HTTP/2.0\r\n\r\n",
            "GET /ping HTTP/1.1\r\nHost : x\r\n\r\n",
            "GET /ping HTTP/1.1\r\nHost: x\r\n folded\r\n\r\n",
            "GET /ping HTTP/1.1\nHost: x\r\n\r\n",
            "GET /ping HTTP/1.1\r\nNoColon\r\n\r\n",
        };
        for (String head : heads) {
            Socket socket = new Socket("127.0.0.1", port);
            try {
                socket.setSoTimeout(30000);
                socket.getOutputStream().write(head.getBytes(AgentHarness.UTF8));
                AgentHarness.Reply reply = AgentHarness.readReply(socket.getInputStream());
                assertEquals(400, reply.status, head);
                assertEquals("close", reply.header("Connection"), head);
            } finally {
                socket.close();
            }
        }
        StringBuilder many = new StringBuilder("GET /ping HTTP/1.1\r\n");
        for (int i = 0; i <= ControlServer.MAX_HEADER_FIELDS; i++) {
            many.append("X-F").append(i).append(": v\r\n");
        }
        many.append("\r\n");
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(30000);
            socket.getOutputStream().write(many.toString().getBytes(AgentHarness.UTF8));
            assertEquals(431, AgentHarness.readReply(socket.getInputStream()).status, "65 header fields");
        } finally {
            socket.close();
        }
        assertEquals(200, agent.get("/ping").status);
    }

    @Test
    void anAuthenticatedConnectionIsKeptAliveAndServesPipelinedRequestsInOrder() throws Exception {
        AgentHarness.RawClient client = agent.new RawClient();
        try {
            List<String> nonces = new ArrayList<String>();
            byte[] ping = client.request("GET", "/ping?nonce=" + Auth.newNonce(), null, nonces);
            byte[] info = client.request("POST", "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"agent.info\"}",
                    nonces);
            byte[] schema = client.request("GET", "/schema", null, nonces);
            byte[] missing = client.request("GET", "/nowhere", null, nonces);
            byte[] notification = client.request("POST", "/rpc",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"agent.info\"}", nonces);
            byte[] caps = client.request("GET", "/capabilities", null, nonces);
            client.write(ping, info, schema, missing, notification, caps);
            AgentHarness.Reply first = client.read(nonces.get(0));
            assertEquals(200, first.status);
            assertEquals(agent.session(), first.json().get("sessionId"));
            AgentHarness.Reply second = client.read(nonces.get(1));
            assertEquals(200, second.status);
            assertEquals(Long.valueOf(11), second.json().get("id"));
            assertEquals(200, client.read(nonces.get(2)).status);
            assertEquals(404, client.read(nonces.get(3)).status, "a 404 keeps the connection");
            assertEquals(204, client.read(nonces.get(4)).status, "notifications only");
            AgentHarness.Reply last = client.read(nonces.get(5));
            assertEquals(200, last.status);
            assertNull(last.header("Connection"), "still kept alive");
            assertEquals(1, agent.runtime.controlServer().openConnections(), "one connection served them all");
        } finally {
            client.close();
        }
    }

    @Test
    void callsOnAKeptAliveConnectionTakeMillisecondsNotDelayedAckStalls() throws Exception {
        AgentHarness.RawClient client = agent.new RawClient();
        try {
            for (int warm = 0; warm < 5; warm++) {
                call(client, warm);
            }
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                call(client, i);
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms < 1000, "20 calls took " + ms + " ms; each reply is one write with TCP_NODELAY");
        } finally {
            client.close();
        }
    }

    private static void call(AgentHarness.RawClient client, int id) throws IOException {
        List<String> nonces = new ArrayList<String>();
        client.write(client.request("POST", "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"agent.info\"}", nonces));
        assertEquals(200, client.read(nonces.get(0)).status);
    }

    /**
     * The option httpIdleTimeoutMs sets when an idle authenticated keep-alive connection is closed: not before it (the
     * connection still serves a request after most of that time), and soon after it.
     */
    @Test
    void anIdleKeptAliveConnectionIsClosedAtTheConfiguredIdleTimeout() throws Exception {
        assertEquals(AgentRuntime.DEFAULT_HTTP_IDLE_TIMEOUT_MS, agent.runtime.controlServer().idleTimeoutMs(),
                "the default when the option is not given");
        agent.close();
        agent = AgentHarness.start(tmp.resolve("idle"), "httpIdleTimeoutMs", "1500");
        assertEquals(1500L, agent.runtime.controlServer().idleTimeoutMs());
        AgentHarness.RawClient client = agent.new RawClient();
        try {
            List<String> nonces = new ArrayList<String>();
            client.write(client.request("GET", "/schema", null, nonces));
            assertEquals(200, client.read(nonces.get(0)).status);
            client.socket.setSoTimeout(1000);
            try {
                int b = client.in.read();
                throw new AssertionError("the agent closed or wrote before the idle timeout: " + b);
            } catch (java.net.SocketTimeoutException stillOpen) {
                // Expected: 1000 ms of 1500 ms idle, the connection is open.
            }
            nonces.clear();
            client.write(client.request("GET", "/schema", null, nonces));
            assertEquals(200, client.read(nonces.get(0)).status, "kept alive and served after 1 s idle");
            client.socket.setSoTimeout(30000);
            long start = System.nanoTime();
            assertEquals(-1, readOrReset(client.in), "closed by the agent");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(ms >= 1000 && ms < 10000, "closed at the 1500 ms idle timeout: " + ms + " ms");
        } finally {
            client.close();
        }
        assertEquals(200, agent.get("/ping").status);
    }

    @Test
    void aStalledBodyIsCutOffAtTheBodyDeadline() throws Exception {
        ControlServer.bodyDeadlineMs = 500L;
        try {
            Socket socket = new Socket("127.0.0.1", agent.runtime.port());
            try {
                socket.setSoTimeout(30000);
                byte[] body = new byte[100];
                String head = "POST /rpc HTTP/1.1\r\nHost: 127.0.0.1:" + agent.runtime.port() + "\r\n"
                        + agent.authLine("POST", "/rpc") + "\r\nContent-Length: " + body.length + "\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(AgentHarness.UTF8));
                out.write(body, 0, 10);
                out.flush();
                long start = System.nanoTime();
                InputStream in = socket.getInputStream();
                assertEquals(-1, readOrReset(in), "closed without a reply");
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(ms >= 300 && ms < 10000, "closed at the body deadline: " + ms + " ms");
            } finally {
                socket.close();
            }
        } finally {
            ControlServer.bodyDeadlineMs = 30000L;
        }
        assertEquals(200, agent.get("/ping").status);
    }

    static int readOrReset(InputStream in) {
        try {
            return in.read();
        } catch (IOException reset) {
            return -1;
        }
    }

    @Test
    void nothingAnUnauthenticatedClientSendsReachesTheLogAndLinesAreBounded() throws Exception {
        agent.runtime.context().log().info("marker-before-the-flood");
        int port = agent.runtime.port();
        String huge = "/" + repeat('A', 300 * 1024);
        for (int i = 0; i < 3; i++) {
            abortAfterSending(port, "GET " + huge + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
        }
        String medium = "/B" + repeat('B', 8000);
        for (int i = 0; i < 400; i++) {
            abortAfterSending(port, (i % 2 == 0 ? "GET " : "POST ") + medium + " HTTP/1.1\r\nHost: 127.0.0.1:"
                    + port + "\r\nAuthorization: Bearer " + medium + "\r\nContent-Length: 1000\r\n\r\n");
        }
        assertEquals(200, agent.get("/ping").status);
        List<String> lines = agent.runtime.context().log().tail(RingLog.CAPACITY);
        boolean marker = false;
        for (String line : lines) {
            assertTrue(line.length() <= RingLog.MAX_LINE, "a line of " + line.length());
            assertFalse(line.contains("AAAAAAAA") || line.contains("BBBBBBBB"), "request text was logged: " + line);
            marker |= line.contains("marker-before-the-flood");
        }
        assertTrue(marker, "unauthenticated traffic did not evict the earlier diagnostics");
    }

    /** Sends the bytes and resets the connection (SO_LINGER 0) without waiting for the reply. */
    private static void abortAfterSending(int port, String request) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoLinger(true, 0);
            socket.getOutputStream().write(request.getBytes(AgentHarness.UTF8));
        } catch (IOException resetEarly) {
            // The server may answer 431 and close while this is still sending.
        } finally {
            socket.close();
        }
    }

    @Test
    void oversizedBatchesAreRefusedWhole() throws Exception {
        StringBuilder batch = new StringBuilder("[");
        for (int i = 0; i <= Protocol.MAX_BATCH; i++) {
            batch.append(i == 0 ? "" : ",").append("{\"jsonrpc\":\"2.0\",\"id\":").append(i)
                    .append(",\"method\":\"trace.mark\",\"params\":{\"label\":\"b").append(i).append("\"}}");
        }
        batch.append("]");
        long before = agent.runtime.context().events().lastSeq();
        TopicFilter marks = TopicFilter.parse("trace.mark");
        AgentHarness.Reply reply = agent.send("POST", "/rpc", batch.toString(), true);
        assertEquals(200, reply.status);
        Map<String, Object> response = reply.json();
        assertTrue(response.containsKey("error"), "one error object, not a list: " + reply.body);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(Long.valueOf(ai.skillroy.testjava.protocol.ErrorCodes.INVALID_REQUEST), error.get("code"));
        assertTrue(agent.runtime.context().events().since(before, marks, 1000).isEmpty(), "no element ran");
    }

    static String repeat(char c, int n) {
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaListsPrimitivesSortedByName() throws Exception {
        Map<String, Object> schema = agent.get("/schema").json();
        assertEquals(Protocol.VERSION, schema.get("protocol"));
        List<Object> primitives = (List<Object>) schema.get("primitives");
        String previous = "";
        boolean sawDisable = false;
        for (Object entry : primitives) {
            Map<String, Object> descriptor = (Map<String, Object>) entry;
            String name = (String) descriptor.get("name");
            assertTrue(name.compareTo(previous) > 0, name + " after " + previous);
            previous = name;
            if ("agent.disable".equals(name)) {
                sawDisable = true;
                assertEquals(Boolean.TRUE, descriptor.get("destructive"));
                assertEquals(Boolean.TRUE, descriptor.get("mutating"));
                assertEquals("CALLER", descriptor.get("thread"));
            }
            if ("sync.await".equals(name)) {
                Map<String, Object> params = (Map<String, Object>) descriptor.get("paramsSchema");
                Map<String, Object> props = (Map<String, Object>) params.get("properties");
                assertEquals(Json.parse("[\"string\",\"array\"]"), ((Map<String, Object>) props.get("topics"))
                        .get("type"));
            }
            assertFalse("failing.partial".equals(name), "rolled-back primitive must not be listed");
        }
        assertTrue(sawDisable);
    }

    @Test
    @SuppressWarnings("unchecked")
    void capabilitiesUseTheWireShape() throws Exception {
        Map<String, Object> caps = (Map<String, Object>) agent.get("/capabilities").json().get("capabilities");
        Map<String, Object> instrumentation = (Map<String, Object>) caps.get("jvm.instrumentation");
        assertEquals("UNAVAILABLE", instrumentation.get("state"));
        assertEquals("no Instrumentation in EMBEDDED mode", instrumentation.get("reason"));
        assertTrue(instrumentation.containsKey("strategy"));
        assertEquals("UNAVAILABLE", ((Map<String, Object>) caps.get("coretest.off")).get("state"));
        assertTrue(caps.containsKey("target.ready"));
    }
}
