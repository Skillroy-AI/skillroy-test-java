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

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authentication of the control channel (hardening item H2): the token is the shared secret of an agent and its
 * controller and never travels; requests and replies carry HMAC-SHA256 values keyed with it instead. Every rule both
 * sides must apply identically lives here, so that agent and controller cannot disagree: the scheme and header
 * names, nonce and session id formats, the MAC inputs, the header grammar and constant-time verification.
 *
 * <h2>MAC</h2>
 * {@code HMAC-SHA256(key, input)}, written as 64 lower-case hex characters. The key is the token's UTF-8 bytes. The
 * input is a label and the listed fields joined with a single {@code '\n'}, encoded in UTF-8; no field may contain a
 * line break (the formats below rule it out), so the joining is unambiguous:
 * <ul>
 *   <li>{@link #helloMac}: {@code "hello" \n clientNonce};</li>
 *   <li>{@link #requestMac}: {@code "req" \n sessionId \n reqNonce \n METHOD \n pathAndQuery \n sha256hex(body)};</li>
 *   <li>{@link #responseMac}: {@code "resp" \n sessionId \n reqNonce \n statusCode \n sha256hex(body)}, the status
 *       code in decimal.</li>
 * </ul>
 * {@code sha256hex} is lower-case hex of SHA-256 over the exact body bytes on the wire; a message without a body
 * uses {@link #EMPTY_BODY_SHA256}. {@code pathAndQuery} is the request target exactly as sent on the request line
 * (raw, not percent-decoded), for example {@code /events?since=12&topics=ui.*}.
 *
 * <h2>Header formats (exact)</h2>
 * <ul>
 *   <li>{@code GET /ping?nonce=<clientNonce>}: {@code Authorization: Skillroy-HMAC mac=<hello mac>}
 *       ({@link #helloAuthorization}).</li>
 *   <li>Every other request: {@code Authorization: Skillroy-HMAC nonce=<reqNonce>, mac=<request mac>}
 *       ({@link #requestAuthorization}).</li>
 *   <li>Every reply to an authenticated request: {@code X-Skillroy-Mac: <response mac>} and
 *       {@code X-Skillroy-Session: <sessionId>}. For {@code /ping} the response MAC's {@code reqNonce} is the
 *       client nonce.</li>
 *   <li>A 401 carries {@code WWW-Authenticate: Skillroy-HMAC} and neither reply header.</li>
 * </ul>
 * {@link #parseAuthorization} is deliberately stricter than RFC 7235, since both ends format with this class: the
 * scheme and the parameter names are case-sensitive, exactly one space follows the scheme, parameters are separated
 * by a comma and optional spaces or tabs, values are unquoted, each parameter appears at most once, unknown
 * parameters and schemes ({@code Bearer} included) are rejected, and the whole value is at most
 * {@link #MAX_AUTHORIZATION_LENGTH} characters. A request carrying the header more than once is rejected too
 * ({@link #onlyValue}).
 *
 * <h2>Server (agent)</h2>
 * <ol>
 *   <li>On the request head alone, before any body byte is read: take the single {@code Authorization} value and
 *       {@link #parseAuthorization} it. A missing, duplicated or malformed header, or the wrong form for the path (a
 *       {@code nonce} parameter on {@code /ping}, none elsewhere), is the constant 401 with {@code Connection: close}
 *       without reading the body.</li>
 *   <li>{@code GET}: verify at once with {@link #verifyHello} ({@code /ping}, client nonce = the raw value of the
 *       query parameter {@link #PING_NONCE_PARAM}) or {@link #verifyRequest} with {@link #EMPTY_BODY_SHA256}.</li>
 *   <li>{@code POST}: the body hash is part of the MAC, so the request is authenticated only once the body is in.
 *       The server reads the body within its limits ({@code Content-Length} required and at most the body cap, else
 *       413 without reading; read under the body deadline), then calls {@link #verifyRequest} with
 *       {@link #sha256hex} of exactly those bytes. Nothing in the body is parsed, logged or acted on before that
 *       call returned true; a false is the same 401 with {@code Connection: close}. Until then the connection
 *       counts as not authenticated.</li>
 *   <li>Reply: {@link #responseMac} over the status code and the exact reply body bytes (an empty body for
 *       {@code /events}: the MAC covers the response head, and the stream then rides that connection).</li>
 * </ol>
 * The session id binds every request to one agent start, so nothing captured is valid against a later instance and
 * the agent keeps no replay cache.
 *
 * <h2>Client (controller)</h2>
 * A fresh nonce ({@link #newNonce}) per request and per ping. On the reply, before parsing the body:
 * {@link #verifyResponse} with the same nonce, the status code and {@link #sha256hex} of the reply body. For
 * {@code /ping} the client may not know the session id yet: it takes it from {@code X-Skillroy-Session}, verifies
 * the MAC with it, and then requires the body's {@code sessionId} (and the discovery file's, when it has one) to be
 * the same value. A missing, duplicated or wrong MAC means the peer is not the agent.
 *
 * <h2>Errors</h2>
 * Malformed input to a computing or formatting method is an {@link IllegalArgumentException} whose message names the
 * field but never echoes a value. The {@code verify} methods never throw: anything malformed or wrong is
 * {@code false}. The only other exception is an {@link IllegalStateException} from a JDK that lacks HmacSHA256 or
 * SHA-256, which every Java SE platform must provide.
 *
 * <p>Stateless; safe from any thread. Every call creates its own {@link Mac} and {@link MessageDigest}. The first
 * {@link #newNonce} or {@link #newSessionId} initialises a shared {@link SecureRandom} (non-blocking sources only);
 * the agent calls them on its own threads, never on a host thread.
 */
public final class Auth {

    /** The authentication scheme of the {@code Authorization} header, and the value of {@code WWW-Authenticate}. */
    public static final String SCHEME = "Skillroy-HMAC";

    /** Request header carrying the credentials. */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Header of a 401 reply naming the expected scheme ({@link #SCHEME}). */
    public static final String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";

    /** Reply header carrying the response MAC. */
    public static final String HEADER_MAC = "X-Skillroy-Mac";

    /** Reply header carrying the agent's session id, so that a client can verify a {@code /ping} reply. */
    public static final String HEADER_SESSION = "X-Skillroy-Session";

    /** Query parameter of {@code GET /ping} carrying the client nonce. */
    public static final String PING_NONCE_PARAM = "nonce";

    /** Shortest and longest nonce, in characters of {@code [A-Za-z0-9_-]}. */
    public static final int MIN_NONCE_LENGTH = 16, MAX_NONCE_LENGTH = 64;

    /** Length of a session id: 16 random bytes as lower-case hex. */
    public static final int SESSION_ID_LENGTH = 32;

    /** Length of a MAC or a SHA-256 value as lower-case hex. */
    public static final int HEX_DIGEST_LENGTH = 64;

    /** Longest {@code Authorization} value accepted; a value as formatted here is at most 154 characters. */
    public static final int MAX_AUTHORIZATION_LENGTH = 256;

    /** Longest {@code pathAndQuery} a MAC is computed over (the agent's whole request head is 16 KB). */
    public static final int MAX_PATH_AND_QUERY_LENGTH = 16 * 1024;

    /** {@link #sha256hex} of zero bytes: the body hash of a request or reply without a body. */
    public static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String HMAC = "HmacSHA256";
    private static final String SHA256 = "SHA-256";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MAX_METHOD_LENGTH = 16;

    private Auth() {
    }

    // ---- generation ----

    /** A fresh nonce: 16 random bytes as 32 lower-case hex characters. For pings and requests alike. */
    public static String newNonce() {
        return randomHex(16);
    }

    /** A fresh session id: 16 random bytes as 32 lower-case hex characters. The agent makes one at every start. */
    public static String newSessionId() {
        return randomHex(16);
    }

    // ---- formats ----

    /**
     * True if {@code nonce} is {@value #MIN_NONCE_LENGTH} to {@value #MAX_NONCE_LENGTH} characters of
     * {@code [A-Za-z0-9_-]}.
     */
    public static boolean isValidNonce(String nonce) {
        if (nonce == null || nonce.length() < MIN_NONCE_LENGTH || nonce.length() > MAX_NONCE_LENGTH) {
            return false;
        }
        for (int i = 0; i < nonce.length(); i++) {
            if (!isNonceChar(nonce.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** True if {@code sessionId} is exactly {@value #SESSION_ID_LENGTH} lower-case hex characters. */
    public static boolean isValidSessionId(String sessionId) {
        return isLowerHex(sessionId, SESSION_ID_LENGTH);
    }

    /** True if {@code value} is a MAC or SHA-256 value in this class's form: 64 lower-case hex characters. */
    public static boolean isHexDigest(String value) {
        return isLowerHex(value, HEX_DIGEST_LENGTH);
    }

    // ---- hashing ----

    /**
     * Lower-case hex SHA-256 of all of {@code data}.
     *
     * @throws IllegalArgumentException if {@code data} is null
     */
    public static String sha256hex(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data is null");
        }
        return sha256hex(data, 0, data.length);
    }

    /**
     * Lower-case hex SHA-256 of {@code length} bytes of {@code data} from {@code offset} (for a body read into a
     * larger buffer).
     *
     * @throws IllegalArgumentException if {@code data} is null or the range is outside it
     */
    public static String sha256hex(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("data is null");
        }
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("range outside the data");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(SHA256);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this JDK has no " + SHA256, e);
        }
        digest.update(data, offset, length);
        return hex(digest.digest());
    }

    // ---- MACs ----

    /**
     * {@code HMAC("hello", clientNonce)}: authenticates a {@code GET /ping}.
     *
     * @throws IllegalArgumentException if the token is null or empty or the nonce is not valid
     */
    public static String helloMac(String token, String clientNonce) {
        return hmac(token, "hello", requireNonce(clientNonce, "client nonce"));
    }

    /**
     * {@code HMAC("req", sessionId, reqNonce, method, pathAndQuery, bodySha256)}: authenticates any request other
     * than {@code /ping}.
     *
     * @param method the request method in upper case, for example {@code POST}
     * @param pathAndQuery the request target exactly as on the request line: starts with {@code '/'}, visible ASCII
     *     only, at most {@link #MAX_PATH_AND_QUERY_LENGTH} characters
     * @param bodySha256 {@link #sha256hex} of the request body, {@link #EMPTY_BODY_SHA256} without one
     * @throws IllegalArgumentException if any argument is malformed
     */
    public static String requestMac(String token, String sessionId, String reqNonce, String method,
            String pathAndQuery, String bodySha256) {
        return hmac(token, "req", requireSessionId(sessionId), requireNonce(reqNonce, "request nonce"),
                requireMethod(method), requirePathAndQuery(pathAndQuery), requireDigest(bodySha256, "body hash"));
    }

    /**
     * {@code HMAC("resp", sessionId, reqNonce, statusCode, bodySha256)}: the value of {@link #HEADER_MAC} on a reply.
     *
     * @param reqNonce the nonce of the request answered; the client nonce for {@code /ping}
     * @param statusCode the HTTP status code, 100 to 599
     * @param bodySha256 {@link #sha256hex} of the reply body, {@link #EMPTY_BODY_SHA256} without one (and for an
     *     event stream)
     * @throws IllegalArgumentException if any argument is malformed
     */
    public static String responseMac(String token, String sessionId, String reqNonce, int statusCode,
            String bodySha256) {
        return hmac(token, "resp", requireSessionId(sessionId), requireNonce(reqNonce, "request nonce"),
                String.valueOf(requireStatus(statusCode)), requireDigest(bodySha256, "body hash"));
    }

    // ---- the Authorization header ----

    /**
     * The {@code Authorization} value of a {@code GET /ping}: {@code Skillroy-HMAC mac=<helloMac>}.
     *
     * @throws IllegalArgumentException as {@link #helloMac}
     */
    public static String helloAuthorization(String token, String clientNonce) {
        return formatAuthorization(null, helloMac(token, clientNonce));
    }

    /**
     * The {@code Authorization} value of any other request:
     * {@code Skillroy-HMAC nonce=<reqNonce>, mac=<requestMac>}.
     *
     * @throws IllegalArgumentException as {@link #requestMac}
     */
    public static String requestAuthorization(String token, String sessionId, String reqNonce, String method,
            String pathAndQuery, String bodySha256) {
        return formatAuthorization(reqNonce, requestMac(token, sessionId, reqNonce, method, pathAndQuery, bodySha256));
    }

    /**
     * An {@code Authorization} value from its parts: {@code Skillroy-HMAC mac=<mac>} when {@code reqNonce} is null,
     * else {@code Skillroy-HMAC nonce=<reqNonce>, mac=<mac>}.
     *
     * @throws IllegalArgumentException if the nonce (when given) or the MAC is malformed
     */
    public static String formatAuthorization(String reqNonce, String mac) {
        requireDigest(mac, "mac");
        if (reqNonce == null) {
            return SCHEME + " mac=" + mac;
        }
        return SCHEME + " nonce=" + requireNonce(reqNonce, "request nonce") + ", mac=" + mac;
    }

    /**
     * Parses an {@code Authorization} field value (without the surrounding optional whitespace, which an HTTP
     * parser removes) under the strict grammar described on this class.
     *
     * @throws IllegalArgumentException if the value is missing, longer than {@link #MAX_AUTHORIZATION_LENGTH}, of
     *     another scheme, or malformed in any way (the message never echoes the value)
     */
    public static Credentials parseAuthorization(String fieldValue) {
        if (fieldValue == null) {
            throw new IllegalArgumentException("Authorization is missing");
        }
        if (fieldValue.length() > MAX_AUTHORIZATION_LENGTH) {
            throw new IllegalArgumentException("Authorization is longer than " + MAX_AUTHORIZATION_LENGTH);
        }
        if (!fieldValue.startsWith(SCHEME + " ")) {
            throw new IllegalArgumentException("Authorization is not of the scheme " + SCHEME);
        }
        String nonce = null;
        String mac = null;
        int i = SCHEME.length() + 1;
        int end = fieldValue.length();
        while (true) {
            int nameStart = i;
            while (i < end && fieldValue.charAt(i) >= 'a' && fieldValue.charAt(i) <= 'z') {
                i++;
            }
            String name = fieldValue.substring(nameStart, i);
            if (i >= end || fieldValue.charAt(i) != '=') {
                throw new IllegalArgumentException("Authorization parameter without '='");
            }
            i++;
            int valueStart = i;
            while (i < end && isNonceChar(fieldValue.charAt(i))) {
                i++;
            }
            String value = fieldValue.substring(valueStart, i);
            if ("nonce".equals(name)) {
                if (nonce != null) {
                    throw new IllegalArgumentException("Authorization has nonce twice");
                }
                nonce = requireNonce(value, "Authorization nonce");
            } else if ("mac".equals(name)) {
                if (mac != null) {
                    throw new IllegalArgumentException("Authorization has mac twice");
                }
                mac = requireDigest(value, "Authorization mac");
            } else {
                throw new IllegalArgumentException("Authorization has an unknown parameter");
            }
            if (i == end) {
                break;
            }
            if (fieldValue.charAt(i) != ',') {
                throw new IllegalArgumentException("Authorization parameters must be separated by ','");
            }
            i++;
            while (i < end && (fieldValue.charAt(i) == ' ' || fieldValue.charAt(i) == '\t')) {
                i++;
            }
        }
        if (mac == null) {
            throw new IllegalArgumentException("Authorization has no mac");
        }
        return new Credentials(nonce, mac);
    }

    /**
     * The single value of a header field given as all its values (as {@code HttpHeaders.allValues} or
     * {@code Headers.get} return them); null when the field is absent or appears more than once, which callers
     * treat as a failed authentication.
     */
    public static String onlyValue(List<String> fieldValues) {
        return fieldValues != null && fieldValues.size() == 1 ? fieldValues.get(0) : null;
    }

    // ---- verification (never throws) ----

    /**
     * True if {@code credentials} are the hello form (no nonce) and carry {@link #helloMac} of {@code clientNonce}.
     * Constant-time in the MAC. False for anything malformed, null included.
     *
     * @param clientNonce the raw value of the {@code nonce} query parameter of the {@code /ping}
     */
    public static boolean verifyHello(String token, String clientNonce, Credentials credentials) {
        try {
            return credentials != null && credentials.isHello()
                    && macEquals(helloMac(token, clientNonce), credentials.mac());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * True if {@code credentials} are the request form (with a nonce) and carry the {@link #requestMac} of this
     * request. Constant-time in the MAC. False for anything malformed, null included.
     *
     * <p>For a request with a body the server calls this after reading the body within its limits, with
     * {@link #sha256hex} of exactly those bytes, and acts on nothing in the body before it returned true.
     */
    public static boolean verifyRequest(String token, String sessionId, Credentials credentials, String method,
            String pathAndQuery, String bodySha256) {
        try {
            return credentials != null && !credentials.isHello()
                    && macEquals(requestMac(token, sessionId, credentials.nonce(), method, pathAndQuery, bodySha256),
                            credentials.mac());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * True if {@code macFieldValue} (the single {@link #HEADER_MAC} value of a reply; see {@link #onlyValue}) is the
     * {@link #responseMac} of this reply. Constant-time in the MAC. False for anything malformed, null included.
     *
     * @param reqNonce the nonce the client sent with the request (the client nonce for {@code /ping})
     */
    public static boolean verifyResponse(String token, String sessionId, String reqNonce, int statusCode,
            String bodySha256, String macFieldValue) {
        try {
            return macEquals(responseMac(token, sessionId, reqNonce, statusCode, bodySha256), macFieldValue);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * The parameters of a parsed {@code Authorization} value. {@link #nonce()} is null for the hello form of a
     * {@code /ping}. Neither part is secret.
     *
     * <p>Immutable; safe to share between threads.
     */
    public static final class Credentials {

        private final String nonce;
        private final String mac;

        private Credentials(String nonce, String mac) {
            this.nonce = nonce;
            this.mac = mac;
        }

        /** The request nonce, or null for the hello form. */
        public String nonce() {
            return nonce;
        }

        /** The MAC: 64 lower-case hex characters. */
        public String mac() {
            return mac;
        }

        /** True for the hello form ({@code Skillroy-HMAC mac=...}, no nonce), which only {@code /ping} accepts. */
        public boolean isHello() {
            return nonce == null;
        }

        @Override
        public String toString() {
            return isHello() ? "Credentials[hello]" : "Credentials[request]";
        }
    }

    // ---- internals ----

    private static String hmac(String token, String label, String... fields) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("token is missing");
        }
        StringBuilder input = new StringBuilder(label);
        for (String field : fields) {
            input.append('\n').append(field);
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(token.getBytes(UTF8), HMAC));
            return hex(mac.doFinal(input.toString().getBytes(UTF8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this JDK cannot compute " + HMAC, e);
        }
    }

    /** Constant-time comparison of an expected MAC with a presented one; a malformed presented value is false. */
    private static boolean macEquals(String expected, String presented) {
        if (!isHexDigest(presented)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(UTF8), presented.getBytes(UTF8));
    }

    private static String requireNonce(String nonce, String what) {
        if (!isValidNonce(nonce)) {
            throw new IllegalArgumentException(what + " must be " + MIN_NONCE_LENGTH + " to " + MAX_NONCE_LENGTH
                    + " characters of [A-Za-z0-9_-]");
        }
        return nonce;
    }

    private static String requireSessionId(String sessionId) {
        if (!isValidSessionId(sessionId)) {
            throw new IllegalArgumentException("session id must be " + SESSION_ID_LENGTH
                    + " lower-case hex characters");
        }
        return sessionId;
    }

    private static String requireDigest(String value, String what) {
        if (!isHexDigest(value)) {
            throw new IllegalArgumentException(what + " must be " + HEX_DIGEST_LENGTH + " lower-case hex characters");
        }
        return value;
    }

    private static String requireMethod(String method) {
        boolean ok = method != null && !method.isEmpty() && method.length() <= MAX_METHOD_LENGTH;
        for (int i = 0; ok && i < method.length(); i++) {
            char c = method.charAt(i);
            ok = c >= 'A' && c <= 'Z';
        }
        if (!ok) {
            throw new IllegalArgumentException("method must be 1 to " + MAX_METHOD_LENGTH + " upper-case letters");
        }
        return method;
    }

    private static String requirePathAndQuery(String pathAndQuery) {
        boolean ok = pathAndQuery != null && pathAndQuery.startsWith("/")
                && pathAndQuery.length() <= MAX_PATH_AND_QUERY_LENGTH;
        for (int i = 0; ok && i < pathAndQuery.length(); i++) {
            char c = pathAndQuery.charAt(i);
            ok = c > 0x20 && c < 0x7f;
        }
        if (!ok) {
            throw new IllegalArgumentException("pathAndQuery must start with '/' and be at most "
                    + MAX_PATH_AND_QUERY_LENGTH + " characters of visible ASCII");
        }
        return pathAndQuery;
    }

    private static int requireStatus(int statusCode) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("status code must be 100 to 599");
        }
        return statusCode;
    }

    private static boolean isNonceChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static boolean isLowerHex(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RandomHolder.RANDOM.nextBytes(random);
        return hex(random);
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xf];
            out[2 * i + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(out);
    }

    /** Created on first use, so that loading {@link Auth} (for example on a host thread) seeds nothing. */
    private static final class RandomHolder {
        static final SecureRandom RANDOM = new SecureRandom();
    }
}
