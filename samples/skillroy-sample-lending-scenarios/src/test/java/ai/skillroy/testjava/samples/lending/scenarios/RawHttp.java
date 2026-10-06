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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One HTTP/1.1 exchange with the agent on a plain loopback socket, with exactly the header fields the caller gives
 * (plus {@code Host}, {@code Connection: close} and {@code Content-Length}). Exists so that the end-to-end checks can
 * send what {@code AgentClient} never sends (the old {@code Bearer} header, a request signed for an earlier agent
 * start) and can look at the reply's authentication headers themselves, instead of trusting the client that is
 * under test.
 *
 * <p>Every exchange is bounded: 5 s to connect, 10 s per read, a reply of at most 1 MiB. Stateless; safe from any
 * thread.
 */
final class RawHttp {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_REPLY_BYTES = 1 << 20;

    /**
     * A reply: status code, header fields (names in lower case; a repeated field keeps its last value) and the exact
     * body bytes.
     */
    record Reply(int status, Map<String, String> headers, byte[] body) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        Map<String, Object> json() {
            return Json.parseObject(text());
        }

        /** True when the reply carries a MAC that {@code token} verifies for this session, nonce and body. */
        boolean verifies(String token, String sessionId, String nonce) {
            String mac = header(Auth.HEADER_MAC);
            return mac != null && Auth.verifyResponse(token, sessionId, nonce, status, Auth.sha256hex(body), mac);
        }

        @Override
        public String toString() {
            return status + " " + headers + " " + text();
        }
    }

    /** A JSON-RPC request as {@code POST /rpc} carries it. */
    static byte[] rpcBody(String id, String method) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", Map.of());
        return Json.write(request).getBytes(StandardCharsets.UTF_8);
    }

    private RawHttp() {
    }

    /**
     * Sends one request to {@code 127.0.0.1:port} and reads the whole reply.
     *
     * @param target the request target exactly as it goes on the request line
     * @param authorization the {@code Authorization} value, or null for none
     * @param body the body, or null for none
     */
    static Reply exchange(int port, String method, String target, String authorization, byte[] body)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        head.append("Host: 127.0.0.1:").append(port).append("\r\n");
        if (authorization != null) {
            head.append("Authorization: ").append(authorization).append("\r\n");
        }
        if (body != null) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                request.write(body);
            }
            out.write(request.toByteArray());
            out.flush();
            return read(socket.getInputStream());
        }
    }

    /** {@code GET} with the given {@code Authorization} (null: none). */
    static Reply get(int port, String target, String authorization) throws IOException {
        return exchange(port, "GET", target, authorization, null);
    }

    /** {@code POST} with the given {@code Authorization} (null: none) and body. */
    static Reply post(int port, String target, String authorization, byte[] body) throws IOException {
        return exchange(port, "POST", target, authorization, body);
    }

    /** Reads the status line, the header fields and a body of {@code Content-Length} bytes (or up to EOF). */
    private static Reply read(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int headEnd = -1;
        while (headEnd < 0) {
            int n = in.read(buffer);
            if (n < 0) {
                throw new IOException("the connection ended before the reply head: "
                        + all.toString(StandardCharsets.ISO_8859_1));
            }
            all.write(buffer, 0, n);
            limit(all);
            headEnd = indexOf(all.toByteArray(), "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        byte[] bytes = all.toByteArray();
        String[] lines = new String(bytes, 0, headEnd, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/1.")) {
            throw new IOException("not an HTTP reply: " + lines[0]);
        }
        int status = Integer.parseInt(statusLine[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(bytes, headEnd + 4, bytes.length - headEnd - 4);
        String length = headers.get("content-length");
        long expected = length == null ? -1 : Long.parseLong(length);
        while (expected < 0 || body.size() < expected) {
            int n = in.read(buffer);
            if (n < 0) {
                break;
            }
            body.write(buffer, 0, n);
            limit(body);
        }
        byte[] payload = body.toByteArray();
        if (expected >= 0 && payload.length != expected) {
            payload = Arrays.copyOf(payload, (int) Math.min(expected, payload.length));
        }
        return new Reply(status, headers, payload);
    }

    private static void limit(ByteArrayOutputStream bytes) throws IOException {
        if (bytes.size() > MAX_REPLY_BYTES) {
            throw new IOException("reply larger than " + MAX_REPLY_BYTES + " bytes");
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
