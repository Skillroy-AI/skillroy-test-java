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

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The request-head grammar of the agent's own HTTP listener (hardening item H1). */
class HttpHeadTest {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private static HttpHead parse(String head) throws HttpHead.Malformed {
        byte[] bytes = head.getBytes(LATIN1);
        return HttpHead.parse(bytes, bytes.length);
    }

    private static int status(String head) {
        return assertThrows(HttpHead.Malformed.class, () -> parse(head)).status;
    }

    @Test
    void aWellFormedHeadIsParsed() throws Exception {
        HttpHead head = parse("POST /events?since=12&topics=ui.* HTTP/1.1\r\nHost: 127.0.0.1:1\r\n"
                + "Authorization:  Skillroy-HMAC mac=x \t\r\nX-Two: a\r\nx-two: b\r\nEmpty:");
        assertEquals("POST", head.method);
        assertEquals("/events?since=12&topics=ui.*", head.target);
        assertEquals("/events", head.path);
        assertEquals("since=12&topics=ui.*", head.rawQuery);
        assertTrue(head.http11);
        assertEquals("Skillroy-HMAC mac=x", head.only("authorization"), "optional whitespace is not the value");
        assertEquals(Arrays.asList("a", "b"), head.all("X-TWO"), "names are case-insensitive, order kept");
        assertNull(head.only("X-Two"), "a repeated field has no single value");
        assertEquals("", head.only("Empty"));
        assertTrue(head.has("empty"));
        assertFalse(head.has("Origin"));
        assertTrue(head.keepAlive());
        assertNull(parse("GET / HTTP/1.1").rawQuery);
    }

    @Test
    void keepAliveFollowsVersionAndConnection() throws Exception {
        assertFalse(parse("GET / HTTP/1.0").keepAlive());
        assertFalse(parse("GET / HTTP/1.1\r\nConnection: keep-alive, Close").keepAlive());
        assertTrue(parse("GET / HTTP/1.1\r\nConnection: keep-alive").keepAlive());
    }

    @Test
    void malformedHeadsAre400() {
        String[] bad = {
            "", "GET", "GET /", "GET / HTTP/1.1 extra", "GET  / HTTP/1.1", "get / HTTP/1.1",
            "GETTINGVERYLONGMETHOD / HTTP/1.1", "GET * HTTP/1.1", "GET http://x/ HTTP/1.1", "GET /a b HTTP/1.1",
            "GET /é HTTP/1.1", "GET / HTTP/2", "GET / http/1.1", "GET / HTTP/1.1\r\nNo-Colon",
            "GET / HTTP/1.1\r\n: no name", "GET / HTTP/1.1\r\nSpace : x", "GET / HTTP/1.1\r\nA: b\r\n folded",
            "GET / HTTP/1.1\r\nA: b\r\n\tfolded", "GET / HTTP/1.1\nA: b", "GET / HTTP/1.1\r\nA: b\rc",
            "GET / HTTP/1.1\r\nA: b\u0000c", "GET / HTTP/1.1\r\nA: \u007f", "GET / HTTP/1.1\r\n\r\nA: b",
        };
        for (String head : bad) {
            assertEquals(400, status(head), head);
        }
    }

    @Test
    void moreThan64FieldsAre431() throws Exception {
        StringBuilder head = new StringBuilder("GET / HTTP/1.1");
        for (int i = 0; i < ControlServer.MAX_HEADER_FIELDS; i++) {
            head.append("\r\nX-").append(i).append(": v");
        }
        assertEquals("v", parse(head.toString()).only("X-63"), "64 fields are accepted");
        assertEquals(431, status(head.append("\r\nOne-More: v").toString()));
    }

    @Test
    void contentLengthAndPingNonceAreReadStrictly() throws Exception {
        assertEquals(-1L, ControlServer.contentLength(parse("POST / HTTP/1.1")));
        assertEquals(0L, ControlServer.contentLength(parse("POST / HTTP/1.1\r\nContent-Length: 0")));
        assertEquals(42L, ControlServer.contentLength(parse("POST / HTTP/1.1\r\ncontent-length: 42")));
        for (String value : new String[] {"-1", "+1", "1.0", "0x1", "1 2", "", "9999999999999999999"}) {
            assertEquals(-2L, ControlServer.contentLength(parse("POST / HTTP/1.1\r\nContent-Length: " + value)),
                    value);
        }
        assertEquals(-2L, ControlServer.contentLength(parse(
                "POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 1")), "repeated");
        assertEquals("abc", ControlServer.pingNonce("nonce=abc"));
        assertEquals("abc", ControlServer.pingNonce("protocol=0.2&nonce=abc"));
        assertEquals("a%62c", ControlServer.pingNonce("nonce=a%62c"), "the raw value");
        assertNull(ControlServer.pingNonce(null));
        assertNull(ControlServer.pingNonce("protocol=0.2"));
        assertNull(ControlServer.pingNonce("nonce=a&nonce=a"), "twice");
        assertNull(ControlServer.pingNonce("Nonce=abc"), "names are case-sensitive");
    }
}
