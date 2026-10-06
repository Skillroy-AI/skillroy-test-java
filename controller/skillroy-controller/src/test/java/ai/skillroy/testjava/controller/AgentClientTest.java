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
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentClientTest {

    /** Defaults, but a short HTTP slack and ceiling so timeout tests run in well under a second. */
    static final ClientSettings FAST = new ClientSettings(Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(10), Duration.ofMillis(400), Duration.ofSeconds(130), Duration.ofSeconds(10),
            Duration.ofSeconds(45), Duration.ofMillis(50), Duration.ofMillis(400));

    private FakeAgent agent;

    @BeforeEach
    void startAgent() throws Exception {
        agent = new FakeAgent();
    }

    @AfterEach
    void stopAgent() {
        agent.close();
    }

    @Test
    void connectPingsWithAFreshNonceAndAHelloMacAndSendsHost() throws Exception {
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            Map<String, Object> ping = client.ping();
            assertEquals(agent.agentNonce, ping.get("agentNonce"));
            assertEquals("JAVAAGENT", ping.get("loadMode"));
            assertEquals(agent.sessionId, ping.get("sessionId"));
            assertEquals(agent.sessionId, client.sessionId());
            assertThrows(UnsupportedOperationException.class, () -> ping.put("x", 1));
            List<FakeAgent.Request> pings = agent.requests("/ping");
            assertEquals(2, pings.size());
            assertFalse(pings.get(0).query().equals(pings.get(1).query()), "each ping uses a fresh nonce");
            for (FakeAgent.Request r : agent.requests) {
                String clientNonce = r.query().substring("nonce=".length());
                assertTrue(Auth.isValidNonce(clientNonce), r.query());
                assertEquals(Auth.helloAuthorization(agent.token, clientNonce), r.authorization());
                assertFalse(r.authorization().contains(agent.token), "the token never travels");
                assertEquals("127.0.0.1:" + agent.port(), r.host());
                assertNull(r.origin());
            }
            assertEquals(agent.endpoint(), client.endpoint());
        }
    }

    /** H2: every other request carries a fresh request nonce and the request MAC over method, path and body. */
    @Test
    void requestsCarryAFreshNonceAndTheRequestMac() throws Exception {
        agent.handlers.put("x", params -> 1);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            client.call("x", Map.of("a", "b"));
            client.call("x", Map.of("a", "b"));
            client.schema();
            client.capabilities();
            Set<String> nonces = new HashSet<>();
            for (FakeAgent.Request r : agent.requests) {
                if (r.path().equals("/ping")) {
                    continue;
                }
                Auth.Credentials credentials = Auth.parseAuthorization(r.authorization());
                assertFalse(credentials.isHello(), r.toString());
                assertTrue(nonces.add(credentials.nonce()), "a fresh nonce per request");
                String pathAndQuery = r.path() + (r.query() == null ? "" : "?" + r.query());
                assertTrue(Auth.verifyRequest(agent.token, agent.sessionId, credentials, r.method(), pathAndQuery,
                        Auth.sha256hex(r.body().getBytes(StandardCharsets.UTF_8))), r.toString());
                assertFalse(r.authorization().contains(agent.token), "the token never travels");
            }
            assertEquals(4, nonces.size());
            assertFalse(client.isLost());
        }
    }

    @Test
    void localhostIsNormalisedAndOtherHostsAreRejected() throws Exception {
        Endpoint e = agent.endpoint();
        try (AgentClient client = AgentClient.connect(new Endpoint("localhost", e.port(), e.token(), e.nonce(),
                e.pid(), e.startTimeMs(), e.loadMode()))) {
            assertNotNull(client.ping());
        }
        assertThrows(IllegalArgumentException.class, () -> AgentClient.connect(new Endpoint("10.0.0.1", e.port(),
                e.token(), null, 0, 0, null)));
        assertThrows(IllegalArgumentException.class, () -> AgentClient.connect(new Endpoint("127.0.0.1", e.port(),
                null, null, 0, 0, null)));
        assertThrows(IllegalArgumentException.class, () -> AgentClient.connect(new Endpoint("127.0.0.1", e.port(),
                "bad token\r\n", null, 0, 0, null)));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint("127.0.0.1", e.port(), e.token(), null, 0, 0,
                null, "not-a-session"));
    }

    @Test
    void connectFailsOnAWrongAgentNonce() {
        Endpoint e = agent.endpoint();
        AgentLostException lost = assertThrows(AgentLostException.class, () -> AgentClient.connect(
                new Endpoint("127.0.0.1", e.port(), e.token(), "someone-else", e.pid(), e.startTimeMs(), null)));
        assertTrue(lost.getMessage().contains("agentNonce"), lost.getMessage());
    }

    @Test
    void connectFailsWhenTheNonceIsNotEchoed() {
        agent.echoOverride = "stale";
        AgentLostException lost = assertThrows(AgentLostException.class, () -> AgentClient.connect(agent.endpoint()));
        assertTrue(lost.getMessage().contains("echo"), lost.getMessage());
    }

    @Test
    void connectFailsOnAProtocolMajorMismatch() {
        agent.protocol = "1.0";
        AgentLostException lost = assertThrows(AgentLostException.class, () -> AgentClient.connect(agent.endpoint()));
        assertTrue(lost.getMessage().contains("protocol mismatch"), lost.getMessage());
        agent.protocol = "0.7";
        assertDoesNotThrowLost(() -> AgentClient.connect(agent.endpoint()).close());
    }

    @Test
    void connectFailsOnAWrongPidOrStartTime() {
        Endpoint e = agent.endpoint();
        AgentLostException pid = assertThrows(AgentLostException.class, () -> AgentClient.connect(
                new Endpoint("127.0.0.1", e.port(), e.token(), e.nonce(), e.pid() + 1, 0, null)));
        assertTrue(pid.getMessage().contains("pid"), pid.getMessage());
        AgentLostException start = assertThrows(AgentLostException.class, () -> AgentClient.connect(
                new Endpoint("127.0.0.1", e.port(), e.token(), e.nonce(), 0, e.startTimeMs() + 1, null)));
        assertTrue(start.getMessage().contains("restarted"), start.getMessage());
        assertDoesNotThrowLost(() -> AgentClient.connect(
                new Endpoint("127.0.0.1", e.port(), e.token(), null, 0, 0, null)).close());
    }

    @Test
    void aRejectedTokenIsAgentLost() {
        Endpoint e = agent.endpoint();
        AgentLostException lost = assertThrows(AgentLostException.class, () -> AgentClient.connect(
                new Endpoint("127.0.0.1", e.port(), SecureFiles.newToken(), null, 0, 0, null)));
        assertTrue(lost.getMessage().contains("401"), lost.getMessage());
    }

    /**
     * H2: a peer that answers {@code /ping} well formed but cannot produce the reply MAC (it does not hold the token)
     * is rejected, and all it ever saw is one hello MAC, which is worthless for anything but a ping: replayed as an
     * {@code /rpc} request to the real agent it is a 401.
     */
    @Test
    void aPeerWithoutTheKeyIsRejectedAndLearnsNothingReplayable() throws Exception {
        try (FakeAgent impostor = new FakeAgent()) {
            impostor.acceptAnyAuth = true;
            impostor.signingKey = SecureFiles.newToken();
            Endpoint e = agent.endpoint();
            Endpoint lured = new Endpoint("127.0.0.1", impostor.port(), e.token(), null, 0, 0, null);
            AgentLostException lost = assertThrows(AgentLostException.class, () -> AgentClient.connect(lured));
            assertTrue(lost.getMessage().contains("is not the agent"), lost.getMessage());
            assertEquals(1, impostor.requests.size(), "nothing after the failed ping");
            FakeAgent.Request seen = impostor.requests.get(0);
            assertTrue(Auth.parseAuthorization(seen.authorization()).isHello());
            assertFalse(seen.authorization().contains(e.token()));

            // Replayed as a request to the real agent: no request nonce, no session binding: 401.
            HttpResponse<String> replayed = raw(agent.port(), "POST", "/rpc", seen.authorization(),
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.disable\"}");
            assertEquals(401, replayed.statusCode());
            assertEquals(List.of(Auth.SCHEME), replayed.headers().allValues(Auth.HEADER_WWW_AUTHENTICATE));
            assertTrue(replayed.headers().allValues(Auth.HEADER_MAC).isEmpty());

            impostor.signingKey = null;
            impostor.unsigned = true;
            AgentLostException unsigned = assertThrows(AgentLostException.class, () -> AgentClient.connect(lured));
            assertTrue(unsigned.getMessage().contains("is not the agent"), unsigned.getMessage());
            assertEquals(2, impostor.requests.size(), "again nothing after the failed ping");
        }
    }

    /** H2: a request captured from one agent start is a 401 against the next start (a new session id). */
    @Test
    void aCapturedRequestReplayedAgainstARestartedAgentIsRejected() throws Exception {
        agent.handlers.put("x", params -> 1);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            client.call("x", null);
        }
        FakeAgent.Request captured = agent.requests("/rpc").get(0);
        assertEquals(200, raw(agent.port(), "POST", "/rpc", captured.authorization(), captured.body()).statusCode(),
                "the same session accepts it (the agent keeps no replay cache)");
        agent.sessionId = Auth.newSessionId();
        assertEquals(401, raw(agent.port(), "POST", "/rpc", captured.authorization(), captured.body()).statusCode());
    }

    /** H2: the old bearer header is rejected like any other unknown scheme. */
    @Test
    void theOldBearerHeaderIsRejected() throws Exception {
        HttpResponse<String> bearer = raw(agent.port(), "GET", "/ping?nonce=" + Auth.newNonce(),
                "Bearer " + agent.token, null);
        assertEquals(401, bearer.statusCode());
        assertEquals(List.of(Auth.SCHEME), bearer.headers().allValues(Auth.HEADER_WWW_AUTHENTICATE));
    }

    /** One raw request with the given Authorization value (null: none). */
    private static HttpResponse<String> raw(int port, String method, String pathAndQuery, String authorization,
                                            String body) throws Exception {
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1).build();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                    .timeout(Duration.ofSeconds(10))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            http.shutdownNow();
        }
    }

    /** H2: a restarted agent (another session id) is noticed by the next ping and by the next call, and latched. */
    @Test
    void aRestartedAgentIsLostAtTheNextPingOrCall() throws Exception {
        agent.handlers.put("x", params -> 1);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            String before = agent.sessionId;
            agent.sessionId = Auth.newSessionId();
            AgentLostException ping = assertThrows(AgentLostException.class, client::ping);
            assertTrue(ping.getMessage().contains("restarted"), ping.getMessage());
            assertTrue(client.isLost());
            int seen = agent.requests.size();
            AgentLostException call = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertTrue(call.getMessage().contains("is lost"), call.getMessage());
            assertEquals(seen, agent.requests.size(), "a lost client sends nothing");
            agent.sessionId = before;
        }
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            agent.sessionId = Auth.newSessionId();
            AgentLostException call = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertTrue(call.getMessage().contains("401"), call.getMessage());
            assertTrue(client.isLost());
        }
    }

    /** H2: the session id an endpoint names (from its discovery file) must be the one the agent reports. */
    @Test
    void theEndpointsSessionIdMustMatch() throws Exception {
        Endpoint other = agent.endpoint().withSessionId(Auth.newSessionId());
        AgentLostException lost = assertThrows(AgentLostException.class, () -> AgentClient.connect(other));
        assertTrue(lost.getMessage().contains("session"), lost.getMessage());
        agent.bodySessionOverride = Auth.newSessionId();
        AgentLostException body = assertThrows(AgentLostException.class, () -> AgentClient.connect(agent.endpoint()));
        assertTrue(body.getMessage().contains("body reports session"), body.getMessage());
    }

    /** H2: a reply whose MAC is missing or wrong latches the client; nothing in the body is used. */
    @Test
    void aReplyWithoutAValidMacLatchesTheClient() throws Exception {
        agent.handlers.put("x", params -> 1);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            agent.unsigned = true;
            AgentLostException lost = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertTrue(lost.getMessage().contains("is not the agent"), lost.getMessage());
            assertTrue(client.isLost());
            agent.unsigned = false;
            int seen = agent.requests.size();
            assertThrows(AgentLostException.class, client::ping);
            assertThrows(AgentLostException.class, client::schema);
            assertEquals(seen, agent.requests.size(), "a lost client sends nothing");
        }
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            agent.signingKey = SecureFiles.newToken();
            assertThrows(AgentLostException.class, client::capabilities);
            assertTrue(client.isLost());
        }
    }

    /**
     * The agent refuses some requests before authenticating them (400, 413, 431, 501, and 503 when its budget for
     * unauthenticated bodies is spent), so those replies carry no MAC: they fail the call but do not latch, while the
     * same status with a wrong MAC, or an unsigned 200, still does.
     */
    @Test
    void anUnsignedRefusalBeforeAuthenticationFailsTheCallWithoutLatching() throws Exception {
        agent.handlers.put("busy", params -> new FakeAgent.Unsigned(503, "{\"error\":\"busy\"}"));
        agent.handlers.put("big", params -> new FakeAgent.Unsigned(413, ""));
        agent.handlers.put("ok", params -> new FakeAgent.Unsigned(200, "{}"));
        agent.handlers.put("x", params -> 1);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            AgentLostException busy = assertThrows(AgentLostException.class, () -> client.call("busy", null));
            assertTrue(busy.getMessage().contains("refused before authentication: HTTP 503"), busy.getMessage());
            assertFalse(busy.getMessage().contains("busy\""), "the unsigned body is not used: " + busy.getMessage());
            AgentLostException big = assertThrows(AgentLostException.class, () -> client.call("big", null));
            assertTrue(big.getMessage().contains("HTTP 413"), big.getMessage());
            assertFalse(client.isLost());
            assertEquals(1L, client.call("x", null).value());
            assertThrows(AgentLostException.class, () -> client.call("ok", null));
            assertTrue(client.isLost(), "an unsigned 200 is not the agent");
        }
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            agent.signingKey = SecureFiles.newToken();
            assertThrows(AgentLostException.class, () -> client.call("busy", null));
            assertFalse(client.isLost(), "no MAC header at all on the refusal");
            agent.handlers.put("busy", params -> new FakeAgent.Raw(503, "{}"));
            assertThrows(AgentLostException.class, () -> client.call("busy", null));
            assertTrue(client.isLost(), "a wrong MAC latches whatever the status");
        }
    }

    /** H2 latch: a successful agent.disable through this client, and a target process that is gone. */
    @Test
    void disableAndAGoneProcessLatchTheClient() throws Exception {
        agent.handlers.put("agent.disable", params -> Map.of("stopping", true));
        agent.handlers.put("x", params -> 1);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            client.call("agent.disable", null);
            assertTrue(client.isLost());
            int seen = agent.requests.size();
            AgentLostException after = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertTrue(after.getMessage().contains("agent.disable"), after.getMessage());
            assertEquals(seen, agent.requests.size());
            EventStream stream = client.events(0, null, e -> { });
            Poll.until("the stream of a lost client to end", Duration.ofSeconds(20), () -> !stream.isOpen());
            assertEquals(List.of(), agent.requests("/events"), "a lost client opens no event stream");
        }
        java.nio.file.Path sleep = java.nio.file.Path.of("/bin/sleep");
        assumeTrue(java.nio.file.Files.isExecutable(sleep), "no /bin/sleep here");
        Process child = new ProcessBuilder(sleep.toString(), "60").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            agent.pid = child.pid();
            agent.startTimeMs = 0;
            Endpoint e = agent.endpoint();
            try (AgentClient client = AgentClient.connect(new Endpoint("127.0.0.1", e.port(), e.token(), null,
                    e.pid(), 0, null))) {
                assertEquals(1L, client.call("x", null).value());
                child.destroyForcibly();
                assertTrue(child.waitFor(60, TimeUnit.SECONDS));
                int seen = agent.requests.size();
                AgentLostException gone = assertThrows(AgentLostException.class, () -> client.call("x", null));
                assertTrue(gone.getMessage().contains("no longer running"), gone.getMessage());
                assertEquals(seen, agent.requests.size(), "nothing was sent to the port");
                assertTrue(client.isLost());
            }
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void callSendsJsonRpcWithMetaAndParsesTheResult() throws Exception {
        agent.handlers.put("lending.state", params -> {
            agent.publish("state.changed", Map.of("to", "IDLE"));
            return Map.of("name", "IDLE", "echo", params.get("x"));
        });
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            RpcResult result = client.call("lending.state", Map.of("x", 7));
            assertEquals(Map.of("name", "IDLE", "echo", 7L), result.value());
            assertEquals(0, result.seqBefore());
            assertEquals(1, result.seqAfter());
            assertEquals("fake-worker", result.thread());

            Map<String, Object> request = Json.parseObject(agent.requests("/rpc").get(0).body());
            assertEquals("2.0", request.get("jsonrpc"));
            assertEquals("c-1", request.get("id"));
            assertEquals("lending.state", request.get("method"));
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) request.get("params");
            assertEquals(7L, params.get("x"));
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) params.get("_meta");
            assertNotNull(meta.get("correlationId"), "a correlation id is generated");
            assertFalse(meta.containsKey("timeoutMs"));
            assertFalse(meta.containsKey("confirm"));

            client.call("lending.state", Map.of("x", 8));
            Map<String, Object> second = Json.parseObject(agent.requests("/rpc").get(1).body());
            assertEquals("c-2", second.get("id"));
            assertFalse(meta.get("correlationId").equals(meta(second).get("correlationId")));
        }
    }

    @Test
    void callOptionsGoIntoMetaAndWinOverAGivenMeta() throws Exception {
        agent.handlers.put("lending.reset", params -> Map.of("reset", true));
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("_meta", Map.of("traceparent", "00-abc-def-01", "correlationId", "given"));
            client.call("lending.reset", params, new CallOptions("step-7", 2500L, true));
            Map<String, Object> meta = meta(Json.parseObject(agent.requests("/rpc").get(0).body()));
            assertEquals("step-7", meta.get("correlationId"));
            assertEquals(2500L, meta.get("timeoutMs"));
            assertEquals(true, meta.get("confirm"));
            assertEquals("00-abc-def-01", meta.get("traceparent"));

            client.call("lending.reset", params, null);
            assertEquals("given", meta(Json.parseObject(agent.requests("/rpc").get(1).body())).get("correlationId"));
        }
    }

    /**
     * Amendment A34: the call options' secret param names travel as {@code _meta.secretParams}, only when there are
     * any, and win over a {@code secretParams} the params already carried; the params themselves are sent unchanged.
     */
    @Test
    void secretParamsGoIntoMetaOnlyWhenGiven() throws Exception {
        agent.handlers.put("lending.input", params -> Map.of("accepted", true));
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("source", "Entry");
            params.put("value", "pw-secret-value");
            params.put("_meta", Map.of("secretParams", List.of("other")));
            client.call("lending.input", params, CallOptions.defaults().withSecretParams(List.of("value")));
            Map<String, Object> first = Json.parseObject(agent.requests("/rpc").get(0).body());
            assertEquals(List.of("value"), meta(first).get("secretParams"));
            assertEquals("pw-secret-value", ((Map<?, ?>) first.get("params")).get("value"));

            client.call("lending.input", Map.of("source", "Entry", "value", "100"), CallOptions.defaults());
            assertFalse(meta(Json.parseObject(agent.requests("/rpc").get(1).body())).containsKey("secretParams"),
                    "no secret params, no key");
        }
    }

    @Test
    void jsonRpcErrorsBecomeAgentRpcException() throws Exception {
        agent.handlers.put("lending.input", params -> {
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "not valid in IDLE", Map.of("state", "IDLE"));
        });
        agent.handlers.put("swing.click", params -> {
            throw new RpcException(ErrorCodes.TIMEOUT, "timed out", Map.of("primitive", "swing.click"));
        });
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            AgentRpcException rejected = assertThrows(AgentRpcException.class,
                    () -> client.call("lending.input", Map.of("source", "Entry")));
            assertEquals("lending.input", rejected.method());
            assertEquals(ErrorCodes.INPUT_REJECTED, rejected.error().code());
            assertEquals("not valid in IDLE", rejected.error().message());
            assertEquals(Map.of("state", "IDLE"), rejected.error().data());

            AgentRpcException timeout = assertThrows(AgentRpcException.class, () -> client.call("swing.click", null));
            assertEquals(ErrorCodes.TIMEOUT, timeout.error().code());

            AgentRpcException missing = assertThrows(AgentRpcException.class, () -> client.call("no.such", null));
            assertEquals(ErrorCodes.METHOD_NOT_FOUND, missing.error().code());
        }
    }

    @Test
    void anUnansweredCallTimesOutAfterTimeoutPlusSlack() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        agent.handlers.put("slow", params -> {
            release.await(20, TimeUnit.SECONDS);
            return null;
        });
        try (AgentClient client = AgentClient.connect(agent.endpoint(), FAST)) {
            long start = System.nanoTime();
            AgentLostException lost = assertThrows(AgentLostException.class,
                    () -> client.call("slow", null, CallOptions.defaults().withTimeoutMs(200L)));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(lost.getMessage().contains("no reply within 600 ms"), lost.getMessage());
            assertTrue(elapsedMs >= 550 && elapsedMs < 15_000, "elapsed " + elapsedMs);
            assertFalse(client.isLost(), "a plain timeout does not latch");
        } finally {
            release.countDown();
        }
    }

    @Test
    void withoutATimeoutTheCeilingApplies() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        agent.handlers.put("slow", params -> {
            release.await(20, TimeUnit.SECONDS);
            return null;
        });
        ClientSettings lowCeiling = new ClientSettings(FAST.connectTimeout(), FAST.pingTimeout(), FAST.getTimeout(),
                FAST.callSlack(), Duration.ofMillis(300), FAST.sseHeaderTimeout(), FAST.sseIdleTimeout(),
                FAST.sseInitialBackoff(), FAST.sseMaxBackoff());
        try (AgentClient client = AgentClient.connect(agent.endpoint(), lowCeiling)) {
            AgentLostException lost = assertThrows(AgentLostException.class, () -> client.call("slow", null));
            assertTrue(lost.getMessage().contains("no reply within 300 ms"), lost.getMessage());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aDroppedConnectionOrAStoppedAgentIsAgentLost() throws Exception {
        agent.handlers.put("drop", params -> FakeAgent.DROP);
        agent.handlers.put("x", params -> 1);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            assertThrows(AgentLostException.class, () -> client.call("drop", null));
            assertFalse(client.isLost(), "a dropped connection to a port that still accepts does not latch");
            assertEquals(1L, client.call("x", null).value());
            agent.close();
            AgentLostException refused = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertTrue(refused.getMessage().contains("127.0.0.1:" + agent.port()), refused.getMessage());
            assertTrue(refused.getMessage().contains("refused") || refused.getMessage().contains("no longer accepts"),
                    refused.getMessage());
            assertTrue(client.isLost(), "a refused connection latches");
            AgentLostException ping = assertThrows(AgentLostException.class, client::ping);
            assertTrue(ping.getMessage().contains("is lost"), ping.getMessage());
            assertThrows(AgentLostException.class, client::schema);
        }
    }

    /**
     * The keep-alive race: the agent closes the pooled connection just as a call goes out on it, before reading it.
     * The call is sent once more, with a fresh nonce, on a fresh connection, and succeeds; the first attempt was never
     * handled. Only once: a second close before the reply fails the call (without latching).
     */
    @Test
    void aCallMeetingAKeepAliveConnectionTheAgentClosedIsRetriedOnceOnAFreshConnection() throws Exception {
        java.util.concurrent.atomic.AtomicLong handled = new java.util.concurrent.atomic.AtomicLong();
        agent.handlers.put("x", params -> handled.incrementAndGet());
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            assertEquals(1L, client.call("x", null).value());
            int pooled = agent.requests("/rpc").get(0).remotePort();
            agent.closeBeforeReply.set(1);
            assertEquals(2L, client.call("x", null).value());
            assertFalse(client.isLost());
            List<FakeAgent.Request> rpcs = agent.requests("/rpc");
            assertEquals(3, rpcs.size(), "the first call, the closed attempt and its one retry");
            assertEquals(pooled, rpcs.get(1).remotePort(), "the closed attempt rode the pooled connection");
            assertTrue(rpcs.get(2).remotePort() != pooled, "the retry came on a fresh connection");
            assertEquals(rpcs.get(1).body(), rpcs.get(2).body(), "the same JSON-RPC request");
            assertFalse(nonce(rpcs.get(1)).equals(nonce(rpcs.get(2))), "with a fresh request nonce");
            assertEquals(2L, handled.get(), "the closed attempt was never handled");

            agent.closeBeforeReply.set(2);
            AgentLostException twice = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertTrue(twice.getMessage().contains("again on a fresh connection"), twice.getMessage());
            assertEquals(5, agent.requests("/rpc").size(), "one retry, not more");
            assertFalse(client.isLost(), "the port still accepts connections: not latched");
            assertEquals(3L, client.call("x", null).value());
        }
    }

    private static String nonce(FakeAgent.Request request) {
        return Auth.parseAuthorization(request.authorization()).nonce();
    }

    @Test
    void aPortThatDoesNotSpeakHttpIsAgentLost() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        try (Socket socket = server.accept()) {
                            socket.getOutputStream().write("220 not http\r\n".getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (IOException closed) {
                    // The test closed the server socket.
                }
            }, "not-http");
            acceptor.setDaemon(true);
            acceptor.start();
            Endpoint endpoint = new Endpoint("127.0.0.1", server.getLocalPort(), agent.token, null, 0, 0, null);
            AgentLostException lost = assertThrows(AgentLostException.class, () -> AgentClient.connect(endpoint, FAST));
            assertTrue(lost.getMessage().contains("/ping"), lost.getMessage());
            assertFalse(lost.getMessage().contains(agent.token), "the token never appears in a message");
        }
    }

    @Test
    void protocolViolationsAreAgentLost() throws Exception {
        agent.handlers.put("garbage", params -> new FakeAgent.Raw(200, "not json"));
        agent.handlers.put("noResult", params -> new FakeAgent.Raw(200, "{\"jsonrpc\":\"2.0\",\"id\":\"c-2\"}"));
        agent.handlers.put("wrongId", params -> new FakeAgent.Raw(200,
                "{\"jsonrpc\":\"2.0\",\"id\":\"c-999\",\"result\":{\"value\":1,\"seq\":{\"before\":0,\"after\":0}}}"));
        agent.handlers.put("status500", params -> new FakeAgent.Raw(500, "boom"));
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            assertTrue(assertThrows(AgentLostException.class, () -> client.call("garbage", null)).getMessage()
                    .contains("not a JSON object"));
            assertTrue(assertThrows(AgentLostException.class, () -> client.call("noResult", null)).getMessage()
                    .contains("no result"));
            assertTrue(assertThrows(AgentLostException.class, () -> client.call("wrongId", null)).getMessage()
                    .contains("does not match"));
            assertTrue(assertThrows(AgentLostException.class, () -> client.call("status500", null)).getMessage()
                    .contains("HTTP 500"));
        }
    }

    @Test
    void awaitReturnsTheEventOrNullOnTimeout() throws Exception {
        List<Map<String, Object>> seen = Collections.synchronizedList(new ArrayList<>());
        agent.handlers.put("sync.await", params -> {
            seen.add(params);
            if (params.get("timeoutMs").equals(5L)) {
                throw new RpcException(ErrorCodes.TIMEOUT, "no event", Map.of("lastSeq", 4));
            }
            if (params.get("timeoutMs").equals(6L)) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "bad topics");
            }
            return new Event(5, 1000, "input.acked", "lending", "corr", null, Map.of("source", "Entry")).toJson();
        });
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            Event event = client.await("input.*,prompt.*", 4, Map.of("source", "Entry"), Duration.ofSeconds(3));
            assertEquals(5, event.seq());
            assertEquals("input.acked", event.topic());
            assertEquals(Map.of("source", "Entry"), event.payload());
            Map<String, Object> params = seen.get(0);
            assertEquals("input.*,prompt.*", params.get("topics"));
            assertEquals(4L, params.get("since"));
            assertEquals(Map.of("source", "Entry"), params.get("match"));
            assertEquals(3000L, params.get("timeoutMs"));
            assertFalse(meta(params).containsKey("timeoutMs"), "the dispatcher derives its own timeout");

            assertNull(client.await("x", 0, null, Duration.ofMillis(5)));
            assertFalse(seen.get(1).containsKey("match"));

            // Amendment A31: any other JSON-RPC error of the wait is an AgentRpcException.
            AgentRpcException other = assertThrows(AgentRpcException.class,
                    () -> client.await("x", 0, null, Duration.ofMillis(6)));
            assertEquals(ErrorCodes.INVALID_PARAMS, other.error().code());
            assertEquals("sync.await", other.method());
        }
    }

    @Test
    void awaitClampsExtremeTimeoutsInsteadOfOverflowing() throws Exception {
        List<Object> sent = Collections.synchronizedList(new ArrayList<>());
        agent.handlers.put("sync.await", params -> {
            sent.add(params.get("timeoutMs"));
            throw new RpcException(ErrorCodes.TIMEOUT, "no event", Map.of("lastSeq", 0));
        });
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            assertNull(client.await("x", 0, null, Duration.ofSeconds(Long.MAX_VALUE)));
            assertNull(client.await("x", 0, null, Duration.ZERO));
            assertNull(client.await("x", 0, null, Duration.ofSeconds(-5)));
            assertEquals(List.of(Duration.ofDays(1).toMillis(), 1L, 1L), sent);
            assertThrows(NullPointerException.class, () -> client.await("x", 0, null, null));
        }
    }

    @Test
    void anInterruptedCallerGetsAgentLostAndKeepsItsInterruptFlag() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        agent.handlers.put("slow", params -> {
            entered.countDown();
            release.await(20, TimeUnit.SECONDS);
            return 1;
        });
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            Thread caller = Thread.currentThread();
            Thread interrupter = new Thread(() -> {
                try {
                    if (entered.await(20, TimeUnit.SECONDS)) {
                        caller.interrupt();
                    }
                } catch (InterruptedException ignored) {
                    // The test fails on its own assertion.
                }
            });
            interrupter.start();
            try {
                assertThrows(AgentLostException.class, () -> client.call("slow", null));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                interrupter.join();
                release.countDown();
            }
            agent.handlers.put("x", params -> 2);
            assertEquals(2L, client.call("x", null).value(), "the client survives an interrupted call");
        }
    }

    @Test
    void closeFromAnotherThreadFailsACallInFlight() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        agent.handlers.put("slow", params -> {
            entered.countDown();
            release.await(20, TimeUnit.SECONDS);
            return 1;
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AgentClient client = AgentClient.connect(agent.endpoint());
        try {
            Future<Object> call = pool.submit(() -> {
                try {
                    return client.call("slow", null);
                } catch (AgentLostException e) {
                    return e;
                }
            });
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            client.close();
            Object outcome = call.get(30, TimeUnit.SECONDS);
            assertInstanceOf(AgentLostException.class, outcome);
            assertTrue(((AgentLostException) outcome).getMessage().contains("closed"),
                    ((AgentLostException) outcome).getMessage());
        } finally {
            release.countDown();
            pool.shutdownNow();
            client.close();
        }
    }

    @Test
    void capabilitiesAndSchemaAreParsed() throws Exception {
        agent.capabilitiesJson = Map.of("capabilities", Map.of(
                "swing.tree", Map.of("state", "OK", "strategy", "EDT"),
                "swing.click.robot", Map.of("state", "UNAVAILABLE", "reason", "opt-in rung, not implemented")));
        PrimitiveDescriptor click = new PrimitiveDescriptor("swing.click", "click", Map.of("type", "object"), null,
                "EDT_ASYNC", 10_000, "swing.tree", true, false);
        PrimitiveDescriptor info = new PrimitiveDescriptor("agent.info", "info", null, null, "CALLER", 10_000, null,
                false, false);
        agent.schemaJson = List.of(click.toJson(), info.toJson());
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            CapabilityMatrix matrix = client.capabilities();
            assertTrue(matrix.isOk("swing.tree"));
            assertEquals(CapabilityState.UNAVAILABLE, matrix.get("swing.click.robot").state());
            assertEquals(List.of("swing.click.robot: UNAVAILABLE (opt-in rung, not implemented)"),
                    matrix.unmet(List.of("swing.tree", "swing.click.robot")));

            List<PrimitiveDescriptor> schema = client.schema();
            assertEquals(List.of(info, click), schema, "sorted by name");
            assertThrows(UnsupportedOperationException.class, () -> schema.add(info));
        }
        agent.schemaJson = List.of(Map.of("description", "no name"));
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            assertThrows(AgentLostException.class, client::schema);
        }
    }

    @Test
    void closeIsIdempotentAndLaterCallsAreAgentLost() throws Exception {
        agent.handlers.put("x", params -> 1);
        AgentClient client = AgentClient.connect(agent.endpoint());
        assertEquals(1L, client.call("x", null).value());
        client.close();
        client.close();
        AgentLostException lost = assertThrows(AgentLostException.class, () -> client.call("x", null));
        assertTrue(lost.getMessage().contains("closed"), lost.getMessage());
        assertThrows(AgentLostException.class, client::ping);
        assertThrows(IllegalStateException.class, () -> client.events(0, null, e -> { }));
    }

    @Test
    void closedAndFailedClientsLeaveNoThreadsBehind() throws Exception {
        agent.handlers.put("x", params -> 1);
        // Warm up once so that lazily started JDK threads are part of the baseline.
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            client.call("x", null);
        }
        Poll.until("the warm-up client's threads to end", Duration.ofSeconds(30), () -> clientThreads() == 0);
        Endpoint good = agent.endpoint();
        Endpoint wrongNonce = new Endpoint("127.0.0.1", good.port(), good.token(), "other", 0, 0, null);
        for (int i = 0; i < 25; i++) {
            try (AgentClient client = AgentClient.connect(good)) {
                client.call("x", null);
                client.events(0, null, e -> { });
            }
            assertThrows(AgentLostException.class, () -> AgentClient.connect(wrongNonce));
        }
        Poll.until("all client and stream threads to end", Duration.ofSeconds(30), () -> clientThreads() == 0);
    }

    /** Live threads of the JDK HTTP client and of event streams. */
    private static long clientThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("HttpClient-") || t.getName().startsWith("skillroy-sse-"))
                .count();
    }

    @Test
    void concurrentCallsGetDistinctIdsAndTheirOwnResults() throws Exception {
        agent.handlers.put("echo", params -> params.get("n"));
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (AgentClient client = AgentClient.connect(agent.endpoint())) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * 1000;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        long n = base + i;
                        if (!Long.valueOf(n).equals(client.call("echo", Map.of("n", n)).value())) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : futures) {
                assertTrue(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        Set<Object> ids = new HashSet<>();
        for (FakeAgent.Request r : agent.requests("/rpc")) {
            ids.add(Json.parseObject(r.body()).get("id"));
        }
        assertEquals(threads * perThread, ids.size());
    }

    /** Finding S23: a reply that stalls after its headers fails at the call's deadline, not never. */
    @Test
    void aStalledReplyBodyFailsAtTheDeadline() throws Exception {
        agent.handlers.put("stall", params -> new FakeAgent.Stall(100, "{\"jso"));
        try (AgentClient client = AgentClient.connect(agent.endpoint(), FAST)) {
            long start = System.nanoTime();
            AgentLostException lost = assertThrows(AgentLostException.class,
                    () -> client.call("stall", null, CallOptions.defaults().withTimeoutMs(200L)));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(lost.getMessage().contains("no reply within 600 ms"), lost.getMessage());
            assertTrue(elapsedMs >= 550 && elapsedMs < 10_000, "elapsed " + elapsedMs);
            assertFalse(client.isLost(), "a timeout does not latch");
        }
        agent.pingOverride = new FakeAgent.Stall(100, "{");
        ClientSettings quickPing = FAST.withPingTimeout(Duration.ofMillis(400));
        long start = System.nanoTime();
        AgentLostException ping = assertThrows(AgentLostException.class,
                () -> AgentClient.connect(agent.endpoint(), quickPing));
        assertTrue(ping.getMessage().contains("no reply within 400 ms"), ping.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 10_000);
    }

    /** Finding S23: an endless or oversized reply is cut off at the cap instead of filling the heap. */
    @Test
    void anEndlessOrOversizedReplyIsCutOffAtTheCap() throws Exception {
        agent.handlers.put("endless", params -> FakeAgent.ENDLESS);
        agent.handlers.put("big", params -> "x".repeat(300_000));
        agent.handlers.put("small", params -> "ok");
        ClientSettings capped = FAST.withLimits(256 * 1024, 64 * 1024, 4096, 4096);
        try (AgentClient client = AgentClient.connect(agent.endpoint(), capped)) {
            long start = System.nanoTime();
            AgentLostException endless = assertThrows(AgentLostException.class, () -> client.call("endless", null));
            assertTrue(endless.getMessage().contains("exceeds 262144 bytes"), endless.getMessage());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 10_000);
            AgentLostException big = assertThrows(AgentLostException.class, () -> client.call("big", null));
            assertTrue(big.getMessage().contains("exceeds 262144 bytes"), big.getMessage());
            assertFalse(client.isLost());
            assertEquals("ok", client.call("small", null).value());
        }
        agent.pingOverride = FakeAgent.ENDLESS;
        AgentLostException ping = assertThrows(AgentLostException.class,
                () -> AgentClient.connect(agent.endpoint(), capped));
        assertTrue(ping.getMessage().contains("exceeds 65536 bytes"), ping.getMessage());
    }

    @Test
    void majorVersionIsThePartBeforeTheFirstDot() {
        assertEquals("0", AgentClient.major("0.1"));
        assertEquals("12", AgentClient.major("12.3.4"));
        assertEquals("7", AgentClient.major("7"));
    }

    @Test
    void endpointLoadModeIsNotCheckedByThePing() throws Exception {
        agent.loadMode = LoadMode.ATTACH;
        Endpoint e = agent.endpoint();
        try (AgentClient client = AgentClient.connect(new Endpoint("127.0.0.1", e.port(), e.token(), e.nonce(),
                e.pid(), e.startTimeMs(), LoadMode.EMBEDDED))) {
            assertEquals("ATTACH", client.ping().get("loadMode"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> requestOrParams) {
        Map<String, Object> params = requestOrParams.containsKey("params")
                ? (Map<String, Object>) requestOrParams.get("params") : requestOrParams;
        return (Map<String, Object>) params.get("_meta");
    }

    private interface LostAction {
        void run() throws AgentLostException;
    }

    private static void assertDoesNotThrowLost(LostAction action) {
        try {
            action.run();
        } catch (AgentLostException e) {
            throw new AssertionError("unexpected: " + e.getMessage(), e);
        }
    }
}
