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
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one retry of a {@code POST /rpc} (see "Keep-alive and the one retry" on {@link AgentClient}) against a raw
 * socket agent that can reset a connection at any byte: retried when the connection is reset before the first byte
 * of the reply, never once a byte of the reply has arrived, and not at all when the port stops accepting connections.
 */
class RpcRetryTest {

    /** What the raw agent does with one {@code /rpc} request. */
    private enum Behaviour {
        /** A signed JSON-RPC result. */
        REPLY,
        /** Reset before writing a byte. */
        RESET,
        /** Part of the status line, then a reset. */
        RESET_IN_STATUS_LINE,
        /** The complete head and part of the body, then a reset. */
        RESET_IN_BODY,
        /** Close the listener, then reset: an agent that stops. */
        STOP_AND_RESET
    }

    private record Received(int remotePort, String nonce, String body) {
    }

    private RawAgent agent;

    @BeforeEach
    void start() throws IOException {
        agent = new RawAgent();
    }

    @AfterEach
    void stop() {
        agent.close();
    }

    @Test
    void aResetBeforeTheFirstReplyByteIsRetriedOnceOnAFreshConnection() throws Exception {
        try (AgentClient client = agent.connect()) {
            agent.behaviours.add(Behaviour.RESET);
            agent.behaviours.add(Behaviour.REPLY);
            assertEquals("ok", client.call("x", null).value());
            assertEquals(2, agent.rpcs.size());
            assertNotEquals(agent.rpcs.get(0).remotePort(), agent.rpcs.get(1).remotePort(), "a fresh connection");
            assertNotEquals(agent.rpcs.get(0).nonce(), agent.rpcs.get(1).nonce(), "a fresh nonce");
            assertEquals(agent.rpcs.get(0).body(), agent.rpcs.get(1).body());
            assertFalse(client.isLost());
        }
    }

    @Test
    void aResetAfterTheReplyBodyStartedIsNotRetried() throws Exception {
        try (AgentClient client = agent.connect()) {
            agent.behaviours.add(Behaviour.RESET_IN_BODY);
            AgentLostException lost = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertEquals(1, agent.rpcs.size(), "not sent again: " + lost.getMessage());
            assertFalse(client.isLost(), "the port still accepts connections");
            agent.behaviours.add(Behaviour.REPLY);
            assertEquals("ok", client.call("x", null).value());
        }
    }

    @Test
    void aResetInsideTheStatusLineIsNotRetried() throws Exception {
        try (AgentClient client = agent.connect()) {
            agent.behaviours.add(Behaviour.RESET_IN_STATUS_LINE);
            AgentLostException lost = assertThrows(AgentLostException.class, () -> client.call("x", null));
            assertEquals(1, agent.rpcs.size(), "a byte of the reply had arrived: " + lost.getMessage());
            assertFalse(client.isLost());
        }
    }

    /**
     * Two wordings are possible, both correct, and which one appears depends on timing inside the JDK client: when
     * the reset is seen as a closed connection, the client probes the port, finds it closed and latches ("the port no
     * longer accepts connections"); when the JDK client treats the pooled connection as stale and opens a fresh one
     * first, that connect is refused and the client latches on the {@code ConnectException} ("nothing listens there
     * any more"). What the contract fixes is the latch and that no second request reached the agent.
     */
    @Test
    void aConnectionResetByAnAgentThatStopsLatchesWithoutARetry() throws Exception {
        try (AgentClient client = agent.connect()) {
            agent.behaviours.add(Behaviour.STOP_AND_RESET);
            AgentLostException lost = assertThrows(AgentLostException.class, () -> client.call("x", null));
            String message = lost.getMessage();
            assertTrue(message.contains("no longer accepts connections")
                    || message.contains("nothing listens there any more"), message);
            assertTrue(client.isLost());
            assertEquals(1, agent.rpcs.size());
        }
    }

    /** The classification itself, on the exceptions the JDK client produces. */
    @Test
    void onlyAConnectionThatEndedBeforeTheFirstReplyByteCounts() {
        assertTrue(AgentClient.closedBeforeReply(new IOException("HTTP/1.1 header parser received no bytes",
                new EOFException("EOF reached while reading"))));
        assertFalse(AgentClient.closedBeforeReply(new IOException("parsing HTTP/1.1 status line, receiving "
                + "[HTTP/1.1 2], parser state [STATUS_LINE]", new EOFException())));
        assertFalse(AgentClient.closedBeforeReply(new IOException("fixed content-length: 100, bytes received: 10")));
        assertFalse(AgentClient.closedBeforeReply(new HttpTimeoutException("request timed out")));
        assertFalse(AgentClient.closedBeforeReply(new java.net.ConnectException("Connection refused")));
        assertFalse(AgentClient.closedBeforeReply(new IllegalStateException("header parser received no bytes")));
    }

    /**
     * A loopback agent on a raw {@link ServerSocket}: answers {@code /ping} with a signed handshake, keeps connections
     * alive, and treats each {@code /rpc} by the next {@link Behaviour} (a reply when none is queued).
     */
    private static final class RawAgent implements AutoCloseable {
        final String token = SecureFiles.newToken();
        final String sessionId = Auth.newSessionId();
        final Queue<Behaviour> behaviours = new ConcurrentLinkedQueue<>();
        final List<Received> rpcs = new CopyOnWriteArrayList<>();
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        RawAgent() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        Thread connection = new Thread(() -> serve(socket), "raw-agent-connection");
                        connection.setDaemon(true);
                        connection.start();
                    }
                } catch (IOException closed) {
                    // The listener was closed.
                }
            }, "raw-agent-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        AgentClient connect() throws AgentLostException {
            return AgentClient.connect(new Endpoint("127.0.0.1", server.getLocalPort(), token, null, 0, 0, null),
                    AgentClientTest.FAST);
        }

        @Override
        public void close() {
            closeQuietly(server);
            for (Socket socket : sockets) {
                closeQuietly(socket);
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(30_000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                while (true) {
                    String head = readHead(in);
                    if (head == null) {
                        return;
                    }
                    String[] lines = head.split("\r\n");
                    String target = lines[0].split(" ")[1];
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (int i = 1; i < lines.length; i++) {
                        int colon = lines[i].indexOf(':');
                        headers.put(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT),
                                lines[i].substring(colon + 1).strip());
                    }
                    int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    byte[] body = in.readNBytes(length);
                    if (target.startsWith(Protocol.PATH_PING + "?")) {
                        String nonce = target.substring(target.indexOf("nonce=") + "nonce=".length());
                        Map<String, Object> ping = new LinkedHashMap<>();
                        ping.put("nonce", nonce);
                        ping.put(Protocol.KEY_SESSION_ID, sessionId);
                        ping.put("protocol", Protocol.VERSION);
                        out.write(signed(200, Json.write(ping), nonce));
                        out.flush();
                        continue;
                    }
                    String nonce = Auth.parseAuthorization(headers.get("authorization")).nonce();
                    String text = new String(body, StandardCharsets.UTF_8);
                    rpcs.add(new Received(socket.getPort(), nonce, text));
                    Behaviour behaviour = behaviours.poll();
                    switch (behaviour == null ? Behaviour.REPLY : behaviour) {
                        case REPLY -> {
                            Map<String, Object> response = new LinkedHashMap<>();
                            response.put("jsonrpc", "2.0");
                            response.put("id", Json.parseObject(text).get("id"));
                            response.put("result", new RpcResult("ok", List.of(), 0, 0, 1, "test").toJson());
                            out.write(signed(200, Json.write(response), nonce));
                            out.flush();
                        }
                        case RESET -> {
                            reset(socket);
                            return;
                        }
                        case RESET_IN_STATUS_LINE -> {
                            writeThenReset(socket, "HTTP/1.1 20");
                            return;
                        }
                        case RESET_IN_BODY -> {
                            writeThenReset(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                    + "Content-Length: 100\r\n\r\n{\"jsonrpc\":");
                            return;
                        }
                        case STOP_AND_RESET -> {
                            server.close();
                            reset(socket);
                            return;
                        }
                        default -> throw new IllegalStateException();
                    }
                }
            } catch (IOException | RuntimeException e) {
                // The client went away or the test closed the agent.
            }
        }

        /** The bytes of a reply with a valid reply MAC for {@code nonce}. */
        private byte[] signed(int status, String body, String nonce) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + bytes.length + "\r\n" + Auth.HEADER_MAC + ": "
                    + Auth.responseMac(token, sessionId, nonce, status, Auth.sha256hex(bytes)) + "\r\n"
                    + Auth.HEADER_SESSION + ": " + sessionId + "\r\n\r\n";
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            all.writeBytes(head.getBytes(StandardCharsets.ISO_8859_1));
            all.writeBytes(bytes);
            return all.toByteArray();
        }

        /**
         * Writes {@code part} and resets the connection once the client has had time to read it (a reset can discard
         * bytes the peer has not read yet, and this test is about bytes that did arrive).
         */
        private static void writeThenReset(Socket socket, String part) throws IOException {
            socket.getOutputStream().write(part.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reset(socket);
        }

        private static void reset(Socket socket) throws IOException {
            socket.setSoLinger(true, 0);
            socket.close();
        }

        /** The request head up to the blank line (at most 64 KiB), or null at the end of the stream. */
        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b < 0 || head.size() > 64 * 1024) {
                    return null;
                }
                head.write(b);
                matched = (b == '\r' && matched % 2 == 0) || (b == '\n' && matched % 2 == 1) ? matched + 1
                        : b == '\r' ? 1 : 0;
            }
            String text = head.toString(StandardCharsets.ISO_8859_1);
            return text.substring(0, text.length() - 4);
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Already closed.
            }
        }
    }
}
