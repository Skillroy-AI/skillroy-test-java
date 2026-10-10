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

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Starts an in-process agent (EMBEDDED, no Instrumentation) and talks to it over HTTP like a controller would: every
 * request carries {@code Skillroy-HMAC} credentials (hardening item H2) and the MAC of every reply to it is verified
 * before the reply is used.
 */
final class AgentHarness implements AutoCloseable {

    static final Charset UTF8 = Charset.forName("UTF-8");

    final AgentRuntime runtime;
    final String token;
    final Path discoveryDir;

    private AgentHarness(AgentRuntime runtime, Path discoveryDir) throws IOException {
        this.runtime = runtime;
        this.discoveryDir = discoveryDir;
        this.token = new String(Files.readAllBytes(Paths.get(runtime.tokenFile())), UTF8);
    }

    /** Starts with enabled=true, the given discovery dir and extra key/value options. */
    static AgentHarness start(Path discoveryDir, String... keyValues) throws Exception {
        return startWithHost(discoveryDir, null, keyValues);
    }

    static AgentHarness startWithHost(Path discoveryDir, Object hostContext, String... keyValues) throws Exception {
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("discoveryDir", discoveryDir.toString());
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            options.put(keyValues[i], keyValues[i + 1]);
        }
        AgentRuntime runtime = AgentRuntime.start(options, null, AgentHarness.class.getClassLoader(), "EMBEDDED",
                hostContext);
        return new AgentHarness(runtime, discoveryDir);
    }

    @Override
    public void close() {
        runtime.close();
    }

    String base() {
        return "http://127.0.0.1:" + runtime.port();
    }

    String session() {
        return runtime.sessionId();
    }

    /** One HTTP exchange. */
    static final class Reply {
        final int status;
        final String body;
        final byte[] bytes;
        final Map<String, List<String>> headers;

        Reply(int status, byte[] bytes, Map<String, List<String>> headers) {
            this.status = status;
            this.bytes = bytes;
            this.body = new String(bytes, UTF8);
            this.headers = headers;
        }

        Map<String, Object> json() {
            return Json.parseObject(body);
        }

        /** The single value of a header field, or null. */
        String header(String name) {
            List<String> values = headers == null ? null : headers.get(name);
            return values == null || values.size() != 1 ? null : values.get(0);
        }
    }

    // ---- credentials ----

    /**
     * The {@code Authorization} value for a request, as a controller computes it: the hello form for {@code /ping}
     * (the client nonce is the query's {@code nonce}), else the request form with a fresh nonce.
     *
     * @return {nonce used for the reply MAC, Authorization value}
     */
    static String[] credentials(String token, String sessionId, String method, String pathAndQuery, byte[] body) {
        if (isPing(pathAndQuery)) {
            String nonce = ControlServer.pingNonce(query(pathAndQuery));
            return new String[] {nonce, Auth.helloAuthorization(token, nonce)};
        }
        String nonce = Auth.newNonce();
        String bodyHash = body == null || body.length == 0 ? Auth.EMPTY_BODY_SHA256 : Auth.sha256hex(body);
        return new String[] {nonce, Auth.requestAuthorization(token, sessionId, nonce, method, pathAndQuery,
                bodyHash)};
    }

    String[] credentials(String method, String pathAndQuery, byte[] body) {
        return credentials(token, session(), method, pathAndQuery, body);
    }

    /** The {@code X-Skillroy-Body-Sha256} header line of a request with this body (P2-L1-F1), with its CRLF. */
    static String bodyHashLine(byte[] body) {
        return Auth.HEADER_BODY_SHA256 + ": " + Auth.sha256hex(body) + "\r\n";
    }

    /** The {@code Authorization} header line of a request without a body. */
    String authLine(String method, String pathAndQuery) {
        return Auth.HEADER_AUTHORIZATION + ": " + credentials(method, pathAndQuery, null)[1];
    }

    static boolean isPing(String pathAndQuery) {
        return pathAndQuery.equals("/ping") || pathAndQuery.startsWith("/ping?");
    }

    private static String query(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        return q < 0 ? null : pathAndQuery.substring(q + 1);
    }

    /** {@code /ping} without a {@code nonce} parameter gets a fresh one, as every controller ping does. */
    static String withPingNonce(String pathAndQuery) {
        if (!isPing(pathAndQuery) || ControlServer.pingNonce(query(pathAndQuery)) != null) {
            return pathAndQuery;
        }
        String rest = query(pathAndQuery);
        return "/ping?" + Auth.PING_NONCE_PARAM + "=" + Auth.newNonce() + (rest == null ? "" : "&" + rest);
    }

    /** Asserts that a reply carries this session and a correct MAC for the request nonce, status and body. */
    static void assertReplyMac(String token, String sessionId, String nonce, int status, byte[] body,
                               String macField, String sessionField) {
        assertEquals(sessionId, sessionField, "X-Skillroy-Session of the reply");
        String hash = body.length == 0 ? Auth.EMPTY_BODY_SHA256 : Auth.sha256hex(body);
        assertTrue(Auth.verifyResponse(token, sessionId, nonce, status, hash, macField),
                "the reply MAC verifies (status " + status + ")");
    }

    // ---- requests ----

    Reply get(String pathAndQuery) throws IOException {
        return send("GET", pathAndQuery, null, true);
    }

    /** A request with correct credentials (when {@code authenticated}) or none at all. */
    Reply send(String method, String pathAndQuery, String body, boolean authenticated) throws IOException {
        String target = withPingNonce(pathAndQuery);
        byte[] bytes = body == null ? null : body.getBytes(UTF8);
        if (!authenticated) {
            return sendWith(method, target, bytes, null);
        }
        String[] creds = credentials(method, target, bytes);
        Reply reply = sendWith(method, target, bytes, creds[1]);
        if (reply.status != 401) {
            assertReplyMac(token, session(), creds[0], reply.status, reply.bytes, reply.header(Auth.HEADER_MAC),
                    reply.header(Auth.HEADER_SESSION));
        } else {
            fail("authenticated request refused: " + method + " " + target + ": " + reply.body);
        }
        return reply;
    }

    /** A request with the given {@code Authorization} value (null: none); the reply is not verified. */
    Reply sendWith(String method, String pathAndQuery, byte[] body, String authorization) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + pathAndQuery).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setUseCaches(false);
        if (authorization != null) {
            c.setRequestProperty(Auth.HEADER_AUTHORIZATION, authorization);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty(Auth.HEADER_BODY_SHA256, Auth.sha256hex(body));
            OutputStream out = c.getOutputStream();
            out.write(body);
            out.close();
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] bytes = in == null ? new byte[0] : readBytes(in);
        Map<String, List<String>> headers = new java.util.TreeMap<String, List<String>>(
                String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null) {
                headers.put(e.getKey(), e.getValue());
            }
        }
        c.disconnect();
        return new Reply(status, bytes, headers);
    }

    /** An authenticated GET connection (for event streams); {@link #verify} checks its reply head. */
    final class Stream {
        final HttpURLConnection connection;
        final String nonce;
        /** The body of an error reply, once {@link #verify} read it. */
        String errorBody;

        Stream(String pathAndQuery, String... extraHeaders) throws IOException {
            connection = (HttpURLConnection) new URL(base() + pathAndQuery).openConnection();
            String[] creds = credentials("GET", pathAndQuery, null);
            nonce = creds[0];
            connection.setRequestProperty(Auth.HEADER_AUTHORIZATION, creds[1]);
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
                connection.setRequestProperty(extraHeaders[i], extraHeaders[i + 1]);
            }
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(60000);
        }

        /** The status, after checking the reply MAC (an event stream's MAC covers an empty body). */
        int verify() throws IOException {
            int status = connection.getResponseCode();
            byte[] body = new byte[0];
            if (status != 200) {
                InputStream err = connection.getErrorStream();
                body = err == null ? body : readBytes(err);
                errorBody = new String(body, UTF8);
            }
            assertReplyMac(token, session(), nonce, status, body, connection.getHeaderField(Auth.HEADER_MAC),
                    connection.getHeaderField(Auth.HEADER_SESSION));
            return status;
        }
    }

    /** A raw HTTP/1.1 GET with full control over the headers (HttpURLConnection restricts Host and Origin). */
    Reply rawGet(String path, String... headerLines) throws IOException {
        Socket socket = new Socket("127.0.0.1", runtime.port());
        try {
            socket.setSoTimeout(30000);
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\n");
            for (String line : headerLines) {
                request.append(line).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(UTF8));
            return readReply(socket.getInputStream());
        } finally {
            socket.close();
        }
    }

    /**
     * Sends a raw authenticated POST whose body is written on another thread (the server may answer and close before
     * reading it all) and returns the status line's code.
     */
    int rawPost(String path, String extraHeaders, final byte[] body) throws Exception {
        final Socket socket = new Socket("127.0.0.1", runtime.port());
        try {
            socket.setSoTimeout(30000);
            String head = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + runtime.port()
                    + "\r\nAuthorization: " + credentials("POST", path, body)[1] + "\r\n" + bodyHashLine(body)
                    + extraHeaders + "Connection: close\r\n\r\n";
            final OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(UTF8));
            out.flush();
            Thread writer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        out.write(body);
                        out.flush();
                    } catch (IOException ignored) {
                        // The server may close early: that is the point.
                    }
                }
            }, "raw-post-writer");
            writer.setDaemon(true);
            writer.start();
            InputStream in = socket.getInputStream();
            StringBuilder status = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') {
                status.append((char) c);
            }
            return Integer.parseInt(status.substring(9, 12));
        } finally {
            socket.close();
        }
    }

    /** A raw keep-alive client on one socket, for pipelining and latency tests. */
    final class RawClient implements AutoCloseable {
        final Socket socket;
        final InputStream in;

        RawClient() throws IOException {
            socket = new Socket("127.0.0.1", runtime.port());
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30000);
            in = new java.io.BufferedInputStream(socket.getInputStream());
        }

        /**
         * The bytes of one authenticated request; {@code nonces} receives the nonce its reply MAC must use.
         */
        byte[] request(String method, String pathAndQuery, String body, List<String> nonces) {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(UTF8);
            String[] creds = credentials(method, pathAndQuery, bytes);
            nonces.add(creds[0]);
            String head = method + " " + pathAndQuery + " HTTP/1.1\r\nHost: 127.0.0.1:" + runtime.port()
                    + "\r\nAuthorization: " + creds[1] + "\r\n"
                    + (body == null ? "" : "Content-Type: application/json\r\nContent-Length: " + bytes.length
                    + "\r\n" + bodyHashLine(bytes)) + "\r\n";
            byte[] headBytes = head.getBytes(UTF8);
            byte[] all = new byte[headBytes.length + bytes.length];
            System.arraycopy(headBytes, 0, all, 0, headBytes.length);
            System.arraycopy(bytes, 0, all, headBytes.length, bytes.length);
            return all;
        }

        void write(byte[]... requests) throws IOException {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] r : requests) {
                all.write(r, 0, r.length);
            }
            socket.getOutputStream().write(all.toByteArray());
            socket.getOutputStream().flush();
        }

        /** The next reply, its MAC verified against {@code nonce}. */
        Reply read(String nonce) throws IOException {
            Reply reply = readReply(in);
            assertReplyMac(token, session(), nonce, reply.status, reply.bytes, reply.header(Auth.HEADER_MAC),
                    reply.header(Auth.HEADER_SESSION));
            return reply;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Reads one response: status line, fields, then Content-Length bytes (or to EOF without one). */
    static Reply readReply(InputStream in) throws IOException {
        String statusLine = line(in);
        if (statusLine == null) {
            throw new java.io.EOFException("no reply");
        }
        int status = Integer.parseInt(statusLine.substring(9, 12));
        Map<String, List<String>> headers = new java.util.TreeMap<String, List<String>>(
                String.CASE_INSENSITIVE_ORDER);
        String field;
        while ((field = line(in)) != null && !field.isEmpty()) {
            int colon = field.indexOf(':');
            String name = field.substring(0, colon);
            List<String> values = headers.get(name);
            if (values == null) {
                values = new java.util.ArrayList<String>();
                headers.put(name, values);
            }
            values.add(field.substring(colon + 1).trim());
        }
        List<String> length = headers.get("Content-Length");
        byte[] body;
        if (status == 204) {
            body = new byte[0];
        } else if (length != null) {
            body = new byte[Integer.parseInt(length.get(0))];
            int have = 0;
            while (have < body.length) {
                int n = in.read(body, have, body.length - have);
                if (n < 0) {
                    throw new java.io.EOFException("short body");
                }
                have += n;
            }
        } else {
            body = readBytes(in);
        }
        return new Reply(status, body, headers);
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                return len > 0 && sb.charAt(len - 1) == '\r' ? sb.substring(0, len - 1) : sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** A JSON-RPC response for one request (params may be null). */
    Map<String, Object> rpc(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Long.valueOf(7));
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        Reply reply = send("POST", "/rpc", Json.write(request), true);
        assertEquals(200, reply.status, reply.body);
        return reply.json();
    }

    /** The result envelope of a call that must succeed. */
    @SuppressWarnings("unchecked")
    Map<String, Object> result(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> response = rpc(method, params);
        assertNull(response.get("error"), method + " failed: " + response);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertNotNull(result, String.valueOf(response));
        return result;
    }

    /** The value of a call that must succeed. */
    Object value(String method, Map<String, Object> params) throws IOException {
        return result(method, params).get("value");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> valueMap(String method, Map<String, Object> params) throws IOException {
        return (Map<String, Object>) value(method, params);
    }

    /** The error object of a call that must fail. */
    @SuppressWarnings("unchecked")
    Map<String, Object> error(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> response = rpc(method, params);
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertNotNull(error, method + " should have failed: " + response);
        return error;
    }

    static long code(Map<String, Object> error) {
        return ((Number) error.get("code")).longValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> error) {
        return (Map<String, Object>) error.get("data");
    }

    /** params with a _meta object. */
    static Map<String, Object> withMeta(Map<String, Object> params, Object... metaKeyValues) {
        Map<String, Object> out = params == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(params);
        out.put("_meta", map(metaKeyValues));
        return out;
    }

    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    static String readAll(InputStream in) throws IOException {
        return new String(readBytes(in), UTF8);
    }

    static byte[] readBytes(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Polls the condition until it holds or the deadline passes. */
    static void await(String what, long timeoutMs, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(20);
        }
        if (!Boolean.TRUE.equals(condition.call())) {
            fail("timed out waiting for " + what);
        }
    }

    /** True if nothing accepts connections on the port any more. */
    static boolean portClosed(int port) {
        try {
            new Socket("127.0.0.1", port).close();
            return false;
        } catch (IOException refused) {
            return true;
        }
    }

    /** Names of live threads that start with skillroy-. */
    static List<String> skillroyThreads() {
        List<String> names = new java.util.ArrayList<String>();
        for (Thread t : CorePrimitives.allThreads()) {
            if (t.getName().startsWith("skillroy-") && t.isAlive()) {
                names.add(t.getName());
            }
        }
        return names;
    }

    static void assertOwnerOnly(File file) throws IOException {
        Path path = file.toPath();
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(path)), file.toString());
        } else {
            assertTrue(file.canRead());
        }
    }
}
