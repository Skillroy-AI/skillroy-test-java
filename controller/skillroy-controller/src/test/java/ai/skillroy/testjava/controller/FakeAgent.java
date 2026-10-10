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
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for the agent's control server, built on the shared wire types: {@code /ping},
 * {@code /rpc} (handlers per method), {@code /events} (SSE with ring buffer, {@code Last-Event-ID}, gap and
 * heartbeats), {@code /schema} and {@code /capabilities}, with the agent's HMAC authentication (hardening item H2,
 * through {@link Auth}: request MACs checked, every reply to an authenticated request signed with
 * {@code X-Skillroy-Mac} and {@code X-Skillroy-Session}, a constant 401 otherwise) and its Host and Origin checks.
 * Records every request so tests can assert what the client sent. Knobs let tests play an impostor (a wrong MAC key,
 * no MAC), a restarted agent (a new session id) and stalled or endless replies.
 */
final class FakeAgent implements AutoCloseable {

    /** Answers one JSON-RPC call: a value (wrapped in an RpcResult), an RpcResult, {@link #DROP} or {@link Raw}. */
    interface Handler {
        Object handle(Map<String, Object> params) throws Exception;
    }

    /** Return from a handler to close the connection without any reply. */
    static final Object DROP = new Object();

    /** Return from a handler to send this exact HTTP status and body (signed as usual). */
    record Raw(int status, String body) {
    }

    /** Return from a handler to send this status and body without a reply MAC (a refusal before authentication). */
    record Unsigned(int status, String body) {
    }

    /** Return from a handler (or set as {@link #pingOverride}): send the headers, a part of the body, then stall. */
    record Stall(long declaredLength, String part) {
    }

    /** Return from a handler (or set as {@link #pingOverride}): send a chunked body that never ends. */
    static final Object ENDLESS = new Object();

    /**
     * One request as received; {@code remotePort} tells the client's connections apart; {@code bodySha256} is the
     * {@code X-Skillroy-Body-Sha256} value (P2-L1-F1), null when absent.
     */
    record Request(String method, String path, String query, String authorization, String host, String origin,
                   String lastEventId, String body, int remotePort, String bodySha256) {
    }

    final String token = SecureFiles.newToken();
    /** The session id of this "start"; tests replace it to simulate a restarted agent. */
    volatile String sessionId = Auth.newSessionId();
    /** When set, replies are signed with this key instead of the token (an impostor that guesses). */
    volatile String signingKey;
    /** When true, any well-formed Authorization is accepted (an impostor that does not hold the token). */
    volatile boolean acceptAnyAuth;
    /** When true, replies carry no MAC and no session header. */
    volatile boolean unsigned;
    /** When set, the ping body reports this session id instead of {@link #sessionId}. */
    volatile String bodySessionOverride;
    /** When set ({@link Stall} or {@link #ENDLESS}), {@code /ping} answers like that after authentication. */
    volatile Object pingOverride;
    /** Releases stalled replies. */
    final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    final String agentNonce = SecureFiles.newNonce();
    volatile long pid = ProcessHandle.current().pid();
    volatile long startTimeMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    volatile String protocol = Protocol.VERSION;
    volatile LoadMode loadMode = LoadMode.JAVAAGENT;
    volatile String echoOverride;
    volatile boolean rejectAll;
    volatile long heartbeatMs = 15_000;
    volatile boolean silent;
    /** True: gap frames carry {@code id: <to>} as the real agent's do; false: the contract's literal frame. */
    volatile boolean gapWithId;
    volatile Map<String, Object> capabilitiesJson = Map.of("capabilities", Map.of());
    volatile List<Map<String, Object>> schemaJson = List.of();
    /**
     * While positive, each {@code /rpc} request is recorded, then its connection is closed before the request is
     * handled and before a single reply byte (an agent that closed the idle keep-alive connection just as the request
     * arrived), and the count goes down by one.
     */
    final AtomicInteger closeBeforeReply = new AtomicInteger();
    /**
     * Replies for the next {@code /events} requests, one each, instead of a stream: {@link Unsigned} (a refusal before
     * authentication) or {@link Raw} (signed as usual).
     */
    final Queue<Object> eventsReplies = new ConcurrentLinkedQueue<>();

    final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger heartbeatsSent = new AtomicInteger();

    private final Object eventLock = new Object();
    private final List<Event> ring = new ArrayList<>();
    private long nextSeq = 1;
    private final Set<SseConnection> connections = ConcurrentHashMap.newKeySet();

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    FakeAgent() throws IOException {
        AtomicInteger ids = new AtomicInteger();
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-agent-" + ids.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** The endpoint a discovery file for this fake would describe (with its session id). */
    Endpoint endpoint() {
        return new Endpoint("127.0.0.1", port(), token, agentNonce, pid, startTimeMs, loadMode, sessionId);
    }

    List<Request> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** Publishes an event into the ring and to every open SSE connection whose filter matches. */
    Event publish(String topic, Map<String, Object> payload) {
        synchronized (eventLock) {
            Event event = new Event(nextSeq++, System.currentTimeMillis(), topic, "fake", null, null, payload);
            ring.add(event);
            for (SseConnection c : connections) {
                if (c.filter.matches(topic)) {
                    c.frames.add(frame(event));
                }
            }
            return event;
        }
    }

    /** Drops ring entries older than {@code seq}, as an overflowing ring would. */
    void forgetBefore(long seq) {
        synchronized (eventLock) {
            ring.removeIf(e -> e.seq() < seq);
        }
    }

    int openStreams() {
        return connections.size();
    }

    /** Writes {@code frame} verbatim to every open SSE connection. */
    void sendRaw(String frame) {
        for (SseConnection c : connections) {
            c.frames.add(frame);
        }
    }

    /** Ends every open SSE response (the client sees the connection end). */
    void dropStreams() {
        for (SseConnection c : connections) {
            c.closed = true;
            c.frames.add("");
        }
    }

    /** Writes a discovery file and token file for this fake into {@code dir}, as the agent would. */
    Path writeDiscovery(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            // Owner-only like the agent's own directory (amendment A3), whatever the umask.
            SecureFiles.ensureOwnerOnlyDirectory(dir);
        }
        Path tokenFile = dir.resolve("skillroy-" + pid + "-" + startTimeMs + ".token");
        writeOwnerOnly(tokenFile, token + "\n");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("pid", pid);
        json.put("startTimeMs", startTimeMs);
        json.put("port", port());
        json.put("tokenFile", tokenFile.toString());
        json.put("nonce", agentNonce);
        json.put("protocol", protocol);
        json.put("loadMode", loadMode.name());
        json.put("mainCommand", "com.example.Main --flag");
        json.put(Protocol.KEY_SESSION_ID, sessionId);
        Path file = dir.resolve("skillroy-" + pid + "-" + startTimeMs + ".json");
        writeOwnerOnly(file, Json.write(json));
        return file;
    }

    /** Writes {@code content} into {@code file} owner-only (rw-------) where the file system has POSIX permissions. */
    static void writeOwnerOnly(Path file, String content) throws IOException {
        Files.deleteIfExists(file);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(file, content);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        release.countDown();
        dropStreams();
        server.stop(0);
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String pathAndQuery = exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String host = exchange.getRequestHeaders().getFirst("Host");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        requests.add(new Request(exchange.getRequestMethod(), path, query, authorization, host, origin,
                exchange.getRequestHeaders().getFirst("Last-Event-ID"), body, exchange.getRemoteAddress().getPort(),
                exchange.getRequestHeaders().getFirst(Auth.HEADER_BODY_SHA256)));
        if ("/rpc".equals(path) && closeBeforeReply.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            // No response headers sent: the JDK server closes the connection, the client reads no byte at all.
            exchange.close();
            return;
        }
        try {
            Map<String, String> params = query(query);
            Auth.Credentials credentials;
            try {
                credentials = Auth.parseAuthorization(Auth.onlyValue(exchange.getRequestHeaders()
                        .get(Auth.HEADER_AUTHORIZATION)));
            } catch (IllegalArgumentException e) {
                credentials = null;
            }
            boolean ok;
            String replyNonce;
            if ("/ping".equals(path)) {
                String clientNonce = rawQueryValue(query, Auth.PING_NONCE_PARAM);
                ok = Auth.verifyHello(token, clientNonce, credentials);
                replyNonce = clientNonce;
            } else {
                // P2-L1-F1, as the agent: a body's hash travels once in the head and must be the body's.
                List<String> declared = exchange.getRequestHeaders().get(Auth.HEADER_BODY_SHA256);
                String bodyHash = Auth.sha256hex(bodyBytes);
                ok = (declared == null ? bodyBytes.length == 0 : bodyHash.equals(Auth.onlyValue(declared)))
                        && Auth.verifyRequest(token, sessionId, credentials, exchange.getRequestMethod(), pathAndQuery,
                        bodyHash);
                replyNonce = credentials == null ? null : credentials.nonce();
            }
            if (acceptAnyAuth && credentials != null) {
                ok = true;
            }
            if (rejectAll || !ok) {
                unauthorized(exchange);
                return;
            }
            Signer signer = new Signer(replyNonce);
            if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) {
                reply(exchange, 403, "{\"error\":\"bad host\"}", signer);
                return;
            }
            if (origin != null) {
                reply(exchange, 403, "{\"error\":\"origin\"}", signer);
                return;
            }
            switch (path) {
                case "/ping" -> {
                    Object override = pingOverride;
                    if (override != null) {
                        special(exchange, override, signer);
                    } else {
                        reply(exchange, 200, Json.write(pingReply(params.get("nonce"))), signer);
                    }
                }
                case "/rpc" -> rpc(exchange, body, signer);
                case "/events" -> {
                    Object canned = eventsReplies.poll();
                    if (canned instanceof Unsigned refusal) {
                        reply(exchange, refusal.status(), refusal.body(), new Signer(null) {
                            @Override
                            void sign(HttpExchange ignored, int status, byte[] bytes) {
                            }
                        });
                    } else if (canned instanceof Raw raw) {
                        reply(exchange, raw.status(), raw.body(), signer);
                    } else {
                        events(exchange, params, signer);
                    }
                }
                case "/schema" -> reply(exchange, 200, Json.write(Map.of("protocol", protocol,
                        "primitives", schemaJson)), signer);
                case "/capabilities" -> reply(exchange, 200, Json.write(capabilitiesJson), signer);
                default -> reply(exchange, 404, "{}", signer);
            }
        } catch (IOException | RuntimeException e) {
            exchange.close();
        }
    }

    /** Signs replies to one authenticated request. */
    private class Signer {
        private final String nonce;

        Signer(String nonce) {
            this.nonce = nonce;
        }

        void sign(HttpExchange exchange, int status, byte[] body) {
            if (unsigned) {
                return;
            }
            String key = signingKey != null ? signingKey : token;
            exchange.getResponseHeaders().add(Auth.HEADER_MAC, Auth.responseMac(key, sessionId, nonce, status,
                    Auth.sha256hex(body)));
            exchange.getResponseHeaders().add(Auth.HEADER_SESSION, sessionId);
        }
    }

    private static void unauthorized(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(Auth.HEADER_WWW_AUTHENTICATE, Auth.SCHEME);
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.sendResponseHeaders(401, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** A stalled or endless reply (finding S23 tests). */
    private void special(HttpExchange exchange, Object behaviour, Signer signer) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        signer.sign(exchange, 200, new byte[0]);
        try (OutputStream out = exchange.getResponseBody()) {
            if (behaviour instanceof Stall stall) {
                exchange.sendResponseHeaders(200, stall.declaredLength());
                out.write(stall.part().getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(60, TimeUnit.SECONDS);
            } else {
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = ("{\"x\":\"" + "y".repeat(8000) + "\",").getBytes(StandardCharsets.UTF_8);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!closed.get() && System.nanoTime() < deadline) {
                    out.write(chunk);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    /** The raw value of {@code name} in a raw query string, or null. */
    private static String rawQueryValue(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    private Map<String, Object> pingReply(String nonce) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("nonce", echoOverride != null ? echoOverride : nonce);
        reply.put("clientNonce", echoOverride != null ? echoOverride : nonce);
        reply.put(Protocol.KEY_SESSION_ID, bodySessionOverride != null ? bodySessionOverride : sessionId);
        reply.put("agentNonce", agentNonce);
        reply.put("protocol", protocol);
        reply.put("pid", pid);
        reply.put("startTimeMs", startTimeMs);
        reply.put("loadMode", loadMode.name());
        reply.put("ready", true);
        reply.put("readOnly", false);
        return reply;
    }

    private void rpc(HttpExchange exchange, String body, Signer signer) throws IOException {
        Map<String, Object> request = Json.parseObject(body);
        Object id = request.get("id");
        String method = (String) request.get("method");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        Handler handler = handlers.get(method);
        if (handler == null) {
            response.put("error", new RpcException(ErrorCodes.METHOD_NOT_FOUND, "no such method: " + method)
                    .error().toJson());
        } else {
            try {
                long before;
                synchronized (eventLock) {
                    before = nextSeq - 1;
                }
                Object value = handler.handle(params);
                if (value == DROP) {
                    exchange.close();
                    return;
                }
                if (value instanceof Raw raw) {
                    reply(exchange, raw.status(), raw.body(), signer);
                    return;
                }
                if (value instanceof Unsigned refusal) {
                    reply(exchange, refusal.status(), refusal.body(), new Signer(null) {
                        @Override
                        void sign(HttpExchange ignored, int status, byte[] bytes) {
                        }
                    });
                    return;
                }
                if (value instanceof Stall || value == ENDLESS) {
                    special(exchange, value, signer);
                    return;
                }
                long after;
                synchronized (eventLock) {
                    after = nextSeq - 1;
                }
                RpcResult result = value instanceof RpcResult given ? given
                        : new RpcResult(value, List.of(), before, after, 3, "fake-worker");
                response.put("result", result.toJson());
            } catch (RpcException e) {
                response.put("error", e.error().toJson());
            } catch (Exception e) {
                response.put("error", new RpcException(ErrorCodes.INTERNAL_ERROR, e.toString()).error().toJson());
            }
        }
        reply(exchange, 200, Json.write(response), signer);
    }

    private void events(HttpExchange exchange, Map<String, String> params, Signer signer) throws IOException {
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        SseConnection connection = new SseConnection(TopicFilter.parse(params.get("topics")));
        synchronized (eventLock) {
            long resume = lastEventId != null ? Long.parseLong(lastEventId.strip())
                    : params.containsKey("since") ? Long.parseLong(params.get("since")) : nextSeq - 1;
            long oldest = ring.isEmpty() ? nextSeq : ring.get(0).seq();
            if (resume < oldest - 1) {
                connection.frames.add((gapWithId ? "id: " + (oldest - 1) + "\n" : "") + "event: gap\ndata: "
                        + Json.write(Map.of("from", resume + 1, "to", oldest - 1)) + "\n\n");
            }
            for (Event e : ring) {
                if (e.seq() > resume && connection.filter.matches(e.topic())) {
                    connection.frames.add(frame(e));
                }
            }
            connections.add(connection);
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        signer.sign(exchange, 200, new byte[0]);
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (!connection.closed) {
                String frame = connection.frames.poll(heartbeatMs, TimeUnit.MILLISECONDS);
                if (connection.closed) {
                    break;
                }
                boolean heartbeat = frame == null;
                if (heartbeat) {
                    if (silent) {
                        continue;
                    }
                    frame = ": hb\n\n";
                }
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (heartbeat) {
                    heartbeatsSent.incrementAndGet();
                }
            }
        } catch (IOException | InterruptedException e) {
            // The client went away.
        } finally {
            connections.remove(connection);
            exchange.close();
        }
    }

    private static String frame(Event event) {
        return "id: " + event.seq() + "\nevent: " + event.topic() + "\ndata: " + Json.write(event.toJson()) + "\n\n";
    }

    private static void reply(HttpExchange exchange, int status, String body, Signer signer) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        signer.sign(exchange, status, bytes);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static final class SseConnection {
        final TopicFilter filter;
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        volatile boolean closed;

        SseConnection(TopicFilter filter) {
            this.filter = filter;
        }
    }
}
