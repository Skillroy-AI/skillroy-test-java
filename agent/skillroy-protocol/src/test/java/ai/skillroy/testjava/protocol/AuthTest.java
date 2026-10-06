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

package ai.skillroy.testjava.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The H2 authentication helpers: fixed test vectors (computed independently of this code) pin the MAC inputs for
 * both sides, then formats, the strict header grammar and the never-throwing verification.
 */
class AuthTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ---- test vectors: if one changes, agent and controller no longer interoperate ----

    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String SESSION = "00112233445566778899aabbccddeeff";
    static final String CLIENT_NONCE = "client-Nonce_0123456789";
    static final String REQ_NONCE = "req_Nonce-ABCDEFGHIJ0123";
    static final String REQUEST_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.info\"}";
    static final String REQUEST_BODY_SHA256 = "118cf74e5caffd1d83b104dfabbd3de8426f29ffb8a9aaf30f6605deb07cb421";
    static final String REPLY_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":42}}";
    static final String REPLY_BODY_SHA256 = "f29f7cb3fb00e323e0775bff2ff49eb59d79279028dc832eb14ef3c2dbd3baf9";

    /** HMAC(TOKEN, "hello\n" + CLIENT_NONCE). */
    static final String HELLO_MAC = "a32c3d26c843b47c62ba0307e0b879f6278aa8a04f3107f143e9a49478b36e45";
    /** HMAC(TOKEN, "req\n" + SESSION + "\n" + REQ_NONCE + "\nPOST\n/rpc\n" + REQUEST_BODY_SHA256). */
    static final String POST_RPC_MAC = "d54b7842c51bebf8ab5cf002e56f984a0367b447a695d066c40482b0a1a7b974";
    /** HMAC(TOKEN, "req\n" + SESSION + "\n" + REQ_NONCE + "\nGET\n/events?since=12&topics=ui.*\n" + empty). */
    static final String GET_EVENTS_MAC = "b9bfbd5ead37926684279c610f733a9dfb1daa6a05e1f7d368579c064a60a825";
    /** HMAC(TOKEN, "resp\n" + SESSION + "\n" + REQ_NONCE + "\n200\n" + REPLY_BODY_SHA256). */
    static final String RPC_REPLY_MAC = "651f2b109b8b087bf1d68b85338de231e3524c6b863fb83681f3158ca4a1cc8e";
    /** HMAC(TOKEN, "resp\n" + SESSION + "\n" + CLIENT_NONCE + "\n200\n" + empty). */
    static final String EMPTY_REPLY_MAC = "be1ae66a631951179ec367e4045fd0844c05db0e18b456eb9d768efc10406796";
    /** The key is the token's UTF-8 bytes: HMAC("tökén-ключ", "hello\n" + CN). */
    static final String NON_ASCII_TOKEN = "tökén-ключ";
    static final String NON_ASCII_HELLO_MAC = "7c0aacba0ad9ffaac4a7903eb8a18b4305aad7069c2e4ae566c31061ac0b381f";

    @Test
    void sha256Vectors() {
        assertEquals(Auth.EMPTY_BODY_SHA256, Auth.sha256hex(new byte[0]));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Auth.sha256hex("abc".getBytes(UTF8)));
        assertEquals(REQUEST_BODY_SHA256, Auth.sha256hex(REQUEST_BODY.getBytes(UTF8)));
        assertEquals(REPLY_BODY_SHA256, Auth.sha256hex(REPLY_BODY.getBytes(UTF8)));
        byte[] framed = ("xx" + REQUEST_BODY + "yyy").getBytes(UTF8);
        assertEquals(REQUEST_BODY_SHA256, Auth.sha256hex(framed, 2, framed.length - 5));
        assertEquals(Auth.EMPTY_BODY_SHA256, Auth.sha256hex(framed, framed.length, 0));
    }

    @Test
    void macVectors() {
        assertEquals(HELLO_MAC, Auth.helloMac(TOKEN, CLIENT_NONCE));
        assertEquals(POST_RPC_MAC, Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "POST", "/rpc", REQUEST_BODY_SHA256));
        assertEquals(GET_EVENTS_MAC, Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "GET",
                "/events?since=12&topics=ui.*", Auth.EMPTY_BODY_SHA256));
        assertEquals(RPC_REPLY_MAC, Auth.responseMac(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256));
        assertEquals(EMPTY_REPLY_MAC, Auth.responseMac(TOKEN, SESSION, CLIENT_NONCE, 200, Auth.EMPTY_BODY_SHA256));
        assertEquals(NON_ASCII_HELLO_MAC, Auth.helloMac(NON_ASCII_TOKEN, CLIENT_NONCE));
    }

    @Test
    void headerVectors() {
        assertEquals("Skillroy-HMAC mac=" + HELLO_MAC, Auth.helloAuthorization(TOKEN, CLIENT_NONCE));
        assertEquals("Skillroy-HMAC nonce=" + REQ_NONCE + ", mac=" + POST_RPC_MAC,
                Auth.requestAuthorization(TOKEN, SESSION, REQ_NONCE, "POST", "/rpc", REQUEST_BODY_SHA256));
        assertEquals("Skillroy-HMAC", Auth.SCHEME);
        assertEquals("Authorization", Auth.HEADER_AUTHORIZATION);
        assertEquals("WWW-Authenticate", Auth.HEADER_WWW_AUTHENTICATE);
        assertEquals("X-Skillroy-Mac", Auth.HEADER_MAC);
        assertEquals("X-Skillroy-Session", Auth.HEADER_SESSION);
        assertEquals("nonce", Auth.PING_NONCE_PARAM);
        int longest = Auth.formatAuthorization(repeat('n', Auth.MAX_NONCE_LENGTH), HELLO_MAC).length();
        assertEquals(154, longest);
        assertTrue(longest <= Auth.MAX_AUTHORIZATION_LENGTH);
    }

    // ---- every field is bound: changing any one of them changes the MAC ----

    @Test
    void everyFieldChangesTheMac() {
        Set<String> macs = new HashSet<String>();
        macs.add(POST_RPC_MAC);
        macs.add(Auth.requestMac(TOKEN + "x", SESSION, REQ_NONCE, "POST", "/rpc", REQUEST_BODY_SHA256));
        macs.add(Auth.requestMac(TOKEN, "f0112233445566778899aabbccddeeff", REQ_NONCE, "POST", "/rpc",
                REQUEST_BODY_SHA256));
        macs.add(Auth.requestMac(TOKEN, SESSION, REQ_NONCE + "x", "POST", "/rpc", REQUEST_BODY_SHA256));
        macs.add(Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "GET", "/rpc", REQUEST_BODY_SHA256));
        macs.add(Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "POST", "/rpc?x", REQUEST_BODY_SHA256));
        macs.add(Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "POST", "/rpc", Auth.EMPTY_BODY_SHA256));
        assertEquals(7, macs.size());
        // the labels separate the three kinds even where the remaining fields line up
        assertNotEquals(Auth.responseMac(TOKEN, SESSION, REQ_NONCE, 200, Auth.EMPTY_BODY_SHA256),
                Auth.responseMac(TOKEN, SESSION, REQ_NONCE, 201, Auth.EMPTY_BODY_SHA256));
        assertNotEquals(Auth.helloMac(TOKEN, CLIENT_NONCE), Auth.helloMac(TOKEN, REQ_NONCE));
    }

    // ---- generation and formats ----

    @Test
    void generatedNoncesAndSessionIdsAreValidAndFresh() {
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            String nonce = Auth.newNonce();
            String session = Auth.newSessionId();
            assertTrue(Auth.isValidNonce(nonce), nonce);
            assertTrue(Auth.isValidSessionId(session), session);
            assertEquals(Auth.SESSION_ID_LENGTH, session.length());
            assertTrue(seen.add(nonce));
            assertTrue(seen.add(session));
        }
    }

    @Test
    void nonceFormat() {
        assertTrue(Auth.isValidNonce(repeat('a', 16)));
        assertTrue(Auth.isValidNonce(repeat('Z', 64)));
        assertTrue(Auth.isValidNonce("AZaz09_-AZaz09_-"));
        assertFalse(Auth.isValidNonce(null));
        assertFalse(Auth.isValidNonce(repeat('a', 15)));
        assertFalse(Auth.isValidNonce(repeat('a', 65)));
        for (String bad : Arrays.asList(" ", "\n", ",", "=", ".", "+", "/", "%", "é", "\u0000")) {
            assertFalse(Auth.isValidNonce("abcdefghijklmno" + bad), bad);
        }
    }

    @Test
    void sessionIdAndDigestFormats() {
        assertTrue(Auth.isValidSessionId(SESSION));
        assertFalse(Auth.isValidSessionId(null));
        assertFalse(Auth.isValidSessionId(SESSION.toUpperCase()));
        assertFalse(Auth.isValidSessionId(SESSION + "0"));
        assertFalse(Auth.isValidSessionId(SESSION.substring(1)));
        assertFalse(Auth.isValidSessionId("g0112233445566778899aabbccddeeff"));
        assertTrue(Auth.isHexDigest(HELLO_MAC));
        assertFalse(Auth.isHexDigest(HELLO_MAC.toUpperCase()));
        assertFalse(Auth.isHexDigest(HELLO_MAC.substring(2)));
        assertFalse(Auth.isHexDigest(null));
    }

    @Test
    void malformedInputToComputationsIsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Auth.helloMac(null, CLIENT_NONCE));
        assertThrows(IllegalArgumentException.class, () -> Auth.helloMac("", CLIENT_NONCE));
        assertThrows(IllegalArgumentException.class, () -> Auth.helloMac(TOKEN, "short"));
        assertThrows(IllegalArgumentException.class, () -> Auth.helloMac(TOKEN, null));
        assertThrows(IllegalArgumentException.class,
                () -> Auth.requestMac(TOKEN, "nope", REQ_NONCE, "POST", "/rpc", REQUEST_BODY_SHA256));
        assertThrows(IllegalArgumentException.class,
                () -> Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "post", "/rpc", REQUEST_BODY_SHA256));
        assertThrows(IllegalArgumentException.class,
                () -> Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "", "/rpc", REQUEST_BODY_SHA256));
        assertThrows(IllegalArgumentException.class,
                () -> Auth.requestMac(TOKEN, SESSION, REQ_NONCE, null, "/rpc", REQUEST_BODY_SHA256));
        for (String path : Arrays.asList(null, "", "rpc", "/r pc", "/rpc\n", "/rpc\r", "/ré",
                "/" + repeat('a', Auth.MAX_PATH_AND_QUERY_LENGTH))) {
            assertThrows(IllegalArgumentException.class,
                    () -> Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "POST", path, REQUEST_BODY_SHA256), path);
        }
        assertThrows(IllegalArgumentException.class,
                () -> Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "POST", "/rpc", "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> Auth.requestMac(TOKEN, SESSION, REQ_NONCE, "POST", "/rpc", REQUEST_BODY_SHA256.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
                () -> Auth.responseMac(TOKEN, SESSION, REQ_NONCE, 99, Auth.EMPTY_BODY_SHA256));
        assertThrows(IllegalArgumentException.class,
                () -> Auth.responseMac(TOKEN, SESSION, REQ_NONCE, 600, Auth.EMPTY_BODY_SHA256));
        assertThrows(IllegalArgumentException.class, () -> Auth.sha256hex(null));
        assertThrows(IllegalArgumentException.class, () -> Auth.sha256hex(new byte[4], 2, 3));
        assertThrows(IllegalArgumentException.class, () -> Auth.sha256hex(new byte[4], -1, 1));
        assertThrows(IllegalArgumentException.class, () -> Auth.sha256hex(new byte[4], 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Auth.formatAuthorization(REQ_NONCE, "zz"));
        assertThrows(IllegalArgumentException.class, () -> Auth.formatAuthorization("bad nonce", HELLO_MAC));
    }

    @Test
    void errorMessagesNeverEchoTheValue() {
        String secretish = "Bearer " + TOKEN;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Auth.parseAuthorization(secretish));
        assertFalse(e.getMessage().contains(TOKEN));
        e = assertThrows(IllegalArgumentException.class,
                () -> Auth.parseAuthorization("Skillroy-HMAC mac=" + TOKEN.toUpperCase()));
        assertFalse(e.getMessage().contains(TOKEN.toUpperCase()));
    }

    // ---- the Authorization grammar ----

    @Test
    void parsesBothFormsAndRoundTrips() {
        Auth.Credentials hello = Auth.parseAuthorization(Auth.helloAuthorization(TOKEN, CLIENT_NONCE));
        assertTrue(hello.isHello());
        assertNull(hello.nonce());
        assertEquals(HELLO_MAC, hello.mac());
        Auth.Credentials request = Auth.parseAuthorization(
                "Skillroy-HMAC nonce=" + REQ_NONCE + ", mac=" + POST_RPC_MAC);
        assertFalse(request.isHello());
        assertEquals(REQ_NONCE, request.nonce());
        assertEquals(POST_RPC_MAC, request.mac());
        // parameter order is free, the space after the comma optional (spaces or tabs)
        assertEquals(REQ_NONCE, Auth.parseAuthorization("Skillroy-HMAC mac=" + POST_RPC_MAC + ",nonce=" + REQ_NONCE)
                .nonce());
        assertEquals(REQ_NONCE, Auth.parseAuthorization("Skillroy-HMAC nonce=" + REQ_NONCE + ",\t mac="
                + POST_RPC_MAC).nonce());
        assertFalse(request.toString().contains(POST_RPC_MAC));
    }

    @Test
    void rejectsEverythingElse() {
        String n = "nonce=" + REQ_NONCE;
        String m = "mac=" + POST_RPC_MAC;
        for (String bad : Arrays.asList(
                null,
                "",
                "Bearer " + TOKEN,
                "bearer " + TOKEN,
                TOKEN,
                "Basic dXNlcjpwYXNz",
                "skillroy-hmac " + m,
                "SKILLROY-HMAC " + m,
                "Skillroy-HMAC",
                "Skillroy-HMAC ",
                "Skillroy-HMAC  " + m,
                "Skillroy-HMAC\t" + m,
                " Skillroy-HMAC " + m,
                "Skillroy-HMAC " + m + " ",
                "Skillroy-HMAC " + m + ",",
                "Skillroy-HMAC " + m + ", ",
                "Skillroy-HMAC " + n,
                "Skillroy-HMAC " + n + ", " + m + ", " + m,
                "Skillroy-HMAC " + m + ", " + m,
                "Skillroy-HMAC " + n + ", " + n + ", " + m,
                "Skillroy-HMAC " + n + " , " + m,
                "Skillroy-HMAC " + n + "; " + m,
                "Skillroy-HMAC " + n + " " + m,
                "Skillroy-HMAC " + n + ", " + m + ", extra=1234567890123456",
                "Skillroy-HMAC NONCE=" + REQ_NONCE + ", " + m,
                "Skillroy-HMAC nonce=\"" + REQ_NONCE + "\", " + m,
                "Skillroy-HMAC nonce =" + REQ_NONCE + ", " + m,
                "Skillroy-HMAC nonce= " + REQ_NONCE + ", " + m,
                "Skillroy-HMAC nonce=, " + m,
                "Skillroy-HMAC nonce=short, " + m,
                "Skillroy-HMAC nonce=" + repeat('a', 65) + ", " + m,
                "Skillroy-HMAC mac=" + POST_RPC_MAC.toUpperCase(),
                "Skillroy-HMAC mac=" + POST_RPC_MAC.substring(1),
                "Skillroy-HMAC mac=" + POST_RPC_MAC + "0",
                "Skillroy-HMAC mac=",
                "Skillroy-HMAC =" + POST_RPC_MAC,
                "Skillroy-HMAC token=" + TOKEN,
                "Skillroy-HMAC " + m + "\r\nX-Injected: 1",
                "Skillroy-HMAC " + n + ", " + m + repeat(' ', Auth.MAX_AUTHORIZATION_LENGTH),
                "Skillroy-HMAC " + n + "," + repeat(' ', Auth.MAX_AUTHORIZATION_LENGTH) + m)) {
            assertThrows(IllegalArgumentException.class, () -> Auth.parseAuthorization(bad), String.valueOf(bad));
        }
    }

    @Test
    void overlongValueIsRejectedBeforeParsing() {
        StringBuilder huge = new StringBuilder("Skillroy-HMAC mac=");
        for (int i = 0; i < 1 << 20; i++) {
            huge.append('a');
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Auth.parseAuthorization(huge.toString()));
        assertTrue(e.getMessage().contains("longer"), e.getMessage());
    }

    @Test
    void onlyValueRejectsAbsentAndDuplicatedFields() {
        assertNull(Auth.onlyValue(null));
        assertNull(Auth.onlyValue(Collections.<String>emptyList()));
        assertNull(Auth.onlyValue(Arrays.asList("a", "b")));
        assertEquals("a", Auth.onlyValue(Collections.singletonList("a")));
    }

    // ---- verification ----

    @Test
    void verifiesWhatTheOtherSideComputed() {
        Auth.Credentials hello = Auth.parseAuthorization("Skillroy-HMAC mac=" + HELLO_MAC);
        assertTrue(Auth.verifyHello(TOKEN, CLIENT_NONCE, hello));
        Auth.Credentials post = Auth.parseAuthorization("Skillroy-HMAC nonce=" + REQ_NONCE + ", mac=" + POST_RPC_MAC);
        assertTrue(Auth.verifyRequest(TOKEN, SESSION, post, "POST", "/rpc",
                Auth.sha256hex(REQUEST_BODY.getBytes(UTF8))));
        Auth.Credentials get = Auth.parseAuthorization("Skillroy-HMAC nonce=" + REQ_NONCE + ", mac=" + GET_EVENTS_MAC);
        assertTrue(Auth.verifyRequest(TOKEN, SESSION, get, "GET", "/events?since=12&topics=ui.*",
                Auth.EMPTY_BODY_SHA256));
        assertTrue(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256, RPC_REPLY_MAC));
        assertTrue(Auth.verifyResponse(TOKEN, SESSION, CLIENT_NONCE, 200, Auth.EMPTY_BODY_SHA256, EMPTY_REPLY_MAC));
    }

    @Test
    void wrongValuesVerifyFalse() {
        Auth.Credentials hello = Auth.parseAuthorization("Skillroy-HMAC mac=" + HELLO_MAC);
        Auth.Credentials post = Auth.parseAuthorization("Skillroy-HMAC nonce=" + REQ_NONCE + ", mac=" + POST_RPC_MAC);
        // wrong token, other session (a restarted agent), changed body, changed path, form swapped
        assertFalse(Auth.verifyHello(TOKEN + "0", CLIENT_NONCE, hello));
        assertFalse(Auth.verifyHello(TOKEN, CLIENT_NONCE + "x", hello));
        assertFalse(Auth.verifyHello(TOKEN, CLIENT_NONCE, post));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, hello, "POST", "/rpc", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, "ffffffffffffffffffffffffffffffff", post, "POST", "/rpc",
                REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, post, "POST", "/rpc", Auth.EMPTY_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, post, "POST", "/rpc?", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, post, "GET", "/rpc", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 500, REPLY_BODY_SHA256, RPC_REPLY_MAC));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, CLIENT_NONCE, 200, REPLY_BODY_SHA256, RPC_REPLY_MAC));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, Auth.EMPTY_BODY_SHA256, RPC_REPLY_MAC));
        // a reply MAC can never stand in for a request MAC or the other way round
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, REQUEST_BODY_SHA256, POST_RPC_MAC));
    }

    @Test
    void malformedInputToVerificationIsFalseNeverAnException() {
        Auth.Credentials post = Auth.parseAuthorization("Skillroy-HMAC nonce=" + REQ_NONCE + ", mac=" + POST_RPC_MAC);
        assertFalse(Auth.verifyHello(null, CLIENT_NONCE, Auth.parseAuthorization("Skillroy-HMAC mac=" + HELLO_MAC)));
        assertFalse(Auth.verifyHello(TOKEN, null, Auth.parseAuthorization("Skillroy-HMAC mac=" + HELLO_MAC)));
        assertFalse(Auth.verifyHello(TOKEN, "bad nonce", Auth.parseAuthorization("Skillroy-HMAC mac=" + HELLO_MAC)));
        assertFalse(Auth.verifyHello(TOKEN, CLIENT_NONCE, null));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, null, "POST", "/rpc", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest("", SESSION, post, "POST", "/rpc", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, null, post, "POST", "/rpc", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, post, null, "/rpc", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, post, "POST", "/rpc\n", REQUEST_BODY_SHA256));
        assertFalse(Auth.verifyRequest(TOKEN, SESSION, post, "POST", "/rpc", null));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256, null));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256, ""));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256, " " + RPC_REPLY_MAC));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256,
                RPC_REPLY_MAC.toUpperCase()));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256, RPC_REPLY_MAC + "00"));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, REQ_NONCE, -1, REPLY_BODY_SHA256, RPC_REPLY_MAC));
        assertFalse(Auth.verifyResponse(TOKEN, SESSION, null, 200, REPLY_BODY_SHA256, RPC_REPLY_MAC));
    }

    @Test
    void theTokenNeverAppearsInAnythingSent() {
        String hello = Auth.helloAuthorization(TOKEN, CLIENT_NONCE);
        String request = Auth.requestAuthorization(TOKEN, SESSION, REQ_NONCE, "POST", "/rpc", REQUEST_BODY_SHA256);
        String reply = Auth.responseMac(TOKEN, SESSION, REQ_NONCE, 200, REPLY_BODY_SHA256);
        for (String sent : Arrays.asList(hello, request, reply)) {
            assertFalse(sent.contains(TOKEN));
            assertFalse(sent.contains(TOKEN.substring(0, 16)));
        }
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
