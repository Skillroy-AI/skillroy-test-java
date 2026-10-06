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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI tests' {@link FakeAgent} authenticates as the agent does (hardening H2), checked here with raw HTTP built
 * from the shared {@link Auth}, independent of the controller's client: so the fake cannot pass a client that sends
 * the token or skips the reply MAC.
 */
class FakeAgentAuthTest {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final FakeAgent agent;
    private FakeAgent other;

    FakeAgentAuthTest() throws IOException {
        agent = new FakeAgent();
    }

    @AfterEach
    void tearDown() {
        agent.close();
        if (other != null) {
            other.close();
        }
    }

    private HttpResponse<String> send(FakeAgent target, String method, String pathAndQuery, String body,
                                      String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port()
                + pathAndQuery)).timeout(Duration.ofSeconds(5));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rpcBody() {
        return Json.write(Map.of("jsonrpc", "2.0", "id", 1L, "method", "test.counter", "params", Map.of()));
    }

    private static void assertUnauthorized(HttpResponse<String> reply) {
        assertEquals(401, reply.statusCode());
        assertEquals("Skillroy-HMAC", reply.headers().firstValue("WWW-Authenticate").orElse(null));
        assertTrue(reply.headers().firstValue(Auth.HEADER_MAC).isEmpty(), "a 401 carries no MAC");
    }

    @Test
    void aHelloPingIsAnsweredWithAVerifiableReply() throws Exception {
        String nonce = Auth.newNonce();
        HttpResponse<String> reply = send(agent, "GET", "/ping?nonce=" + nonce, null,
                Auth.helloAuthorization(agent.token, nonce));
        assertEquals(200, reply.statusCode(), reply.body());
        assertEquals(agent.sessionId, reply.headers().firstValue(Auth.HEADER_SESSION).orElse(null));
        assertTrue(Auth.verifyResponse(agent.token, agent.sessionId, nonce, 200,
                Auth.sha256hex(reply.body().getBytes(StandardCharsets.UTF_8)),
                reply.headers().firstValue(Auth.HEADER_MAC).orElse(null)));
        Map<String, Object> ping = Json.parseObject(reply.body());
        assertEquals(nonce, ping.get("clientNonce"));
        assertEquals(agent.sessionId, ping.get("sessionId"));
    }

    @Test
    void aSignedRequestIsAnsweredAndBoundToItsNonce() throws Exception {
        String nonce = Auth.newNonce();
        String body = rpcBody();
        HttpResponse<String> reply = send(agent, "POST", "/rpc", body, Auth.requestAuthorization(agent.token,
                agent.sessionId, nonce, "POST", "/rpc", Auth.sha256hex(body.getBytes(StandardCharsets.UTF_8))));
        assertEquals(200, reply.statusCode(), reply.body());
        String mac = reply.headers().firstValue(Auth.HEADER_MAC).orElse(null);
        String bodyHash = Auth.sha256hex(reply.body().getBytes(StandardCharsets.UTF_8));
        assertTrue(Auth.verifyResponse(agent.token, agent.sessionId, nonce, 200, bodyHash, mac));
        assertFalse(Auth.verifyResponse(agent.token, agent.sessionId, Auth.newNonce(), 200, bodyHash, mac),
                "the reply MAC is bound to the request's nonce");
    }

    @Test
    void theOldBearerHeaderAndAMissingHeaderAre401() throws Exception {
        assertUnauthorized(send(agent, "POST", "/rpc", rpcBody(), "Bearer " + agent.token));
        assertUnauthorized(send(agent, "GET", "/capabilities", null, null));
        assertUnauthorized(send(agent, "GET", "/ping?nonce=" + Auth.newNonce(), null, "Bearer " + agent.token));
    }

    @Test
    void aTamperedOrMisplacedMacIs401() throws Exception {
        String nonce = Auth.newNonce();
        String body = rpcBody();
        String hash = Auth.sha256hex(body.getBytes(StandardCharsets.UTF_8));
        String signed = Auth.requestAuthorization(agent.token, agent.sessionId, nonce, "POST", "/rpc", hash);
        assertUnauthorized(send(agent, "POST", "/rpc", body.replace("test.counter", "test.poke"), signed));
        assertUnauthorized(send(agent, "POST", "/rpc?x=1", body, signed));
        String helloNonce = Auth.newNonce();
        assertUnauthorized(send(agent, "GET", "/capabilities", null, Auth.helloAuthorization(agent.token,
                helloNonce)));
        assertUnauthorized(send(agent, "GET", "/ping?nonce=" + helloNonce, null, Auth.requestAuthorization(
                agent.token, agent.sessionId, helloNonce, "GET", "/ping?nonce=" + helloNonce,
                Auth.EMPTY_BODY_SHA256)));
        assertUnauthorized(send(agent, "GET", "/ping?nonce=" + Auth.newNonce(), null, Auth.helloAuthorization(
                agent.token, helloNonce)));
    }

    @Test
    void aCapturedRequestIsRejectedByALaterInstance() throws Exception {
        // Same token, new session id: what a restarted agent is.
        other = new FakeAgent();
        assertNotEquals(agent.sessionId, other.sessionId);
        String nonce = Auth.newNonce();
        String signed = Auth.requestAuthorization(agent.token, agent.sessionId, nonce, "GET", "/capabilities",
                Auth.EMPTY_BODY_SHA256);
        assertEquals(200, send(agent, "GET", "/capabilities", null, signed).statusCode());
        HttpResponse<String> replayed = send(other, "GET", "/capabilities", null, signed);
        assertUnauthorized(replayed);
    }

    @Test
    void anImpostorsReplyFailsVerification() throws Exception {
        agent.wrongReplyMac = true;
        String nonce = Auth.newNonce();
        HttpResponse<String> reply = send(agent, "GET", "/ping?nonce=" + nonce, null,
                Auth.helloAuthorization(agent.token, nonce));
        assertEquals(200, reply.statusCode());
        assertFalse(Auth.verifyResponse(agent.token, agent.sessionId, nonce, 200,
                Auth.sha256hex(reply.body().getBytes(StandardCharsets.UTF_8)),
                reply.headers().firstValue(Auth.HEADER_MAC).orElse(null)));
    }
}
