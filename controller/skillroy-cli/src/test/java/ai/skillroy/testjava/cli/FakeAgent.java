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
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for the agent's control server, built on the shared wire types: {@code /ping}, {@code /rpc}
 * (a handler per method), {@code /events} (SSE with a ring buffer and {@code Last-Event-ID}), {@code /schema} and
 * {@code /capabilities}, with the agent's authentication (hardening H2, through the shared {@link Auth}: the hello
 * MAC on {@code /ping}, the request MAC bound to this fake's session id on every other request, the reply MAC and
 * session headers on every authenticated reply, the constant 401 with {@code WWW-Authenticate: Skillroy-HMAC}
 * otherwise, the old {@code Bearer} header included) and its Host and Origin checks. The token never travels: it is
 * only the HMAC key, read by the controller from the token file.
 *
 * <p>Two knobs play an impostor: {@link #wrongReplyMac} answers with a correctly formed reply MAC under another key,
 * {@link #omitReplyMac} answers with none; a client must reject both before it reads the body.
 *
 * <p>It also plays a tiny application for scenario runs: {@code test.poke} increments a counter and publishes
 * {@code test.poked}; {@code test.counter} reads it; and it answers what the executor calls ({@code trace.mark},
 * {@code evt.query}, {@code sync.await}, {@code jvm.properties}, {@code app.state}, {@code swing.snapshot},
 * {@code swing.dialogs}, {@code swing.edtState}, {@code swing.settle}); {@code swing.click} answers as a window that
 * never takes focus.
 */
final class FakeAgent implements AutoCloseable {

    /** Answers one JSON-RPC call with a value (wrapped in an RpcResult) or throws RpcException. */
    interface Handler {
        Object handle(JsonView params) throws Exception;
    }

    /** One request as received; {@code pathAndQuery} is raw, as on the request line. */
    record Request(String method, String path, String pathAndQuery, String authorization, String body) {
    }

    final String token = randomHex(32);
    final String agentNonce = randomHex(16);
    /** This fake's session id (hardening H2): new for every instance, as for every agent start. */
    final String sessionId = Auth.newSessionId();
    /** Reply MACs are computed with another key: a correctly formed reply from a peer without the token. */
    volatile boolean wrongReplyMac;
    /** Replies carry no MAC header at all. */
    volatile boolean omitReplyMac;
    volatile long pid = ProcessHandle.current().pid();
    volatile long startTimeMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    volatile LoadMode loadMode = LoadMode.JAVAAGENT;
    volatile boolean rejectAll;
    volatile String mainCommand = "com.example.FakeMain";
    final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Map<String, Capability> capabilities = new ConcurrentHashMap<>();
    final List<PrimitiveDescriptor> schema = new CopyOnWriteArrayList<>();
    final Map<String, String> properties = new ConcurrentHashMap<>();
    final AtomicInteger counter = new AtomicInteger();
    /** What {@code test.echo} answers; null echoes the given text (the {@code clitest} cross-check agrees). */
    volatile String echoAnswer;
    /** Released once for every SSE connection that has been registered (tests wait on it, never sleep). */
    final Semaphore streamsOpened = new Semaphore(0);

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
        installDefaults();
    }

    int port() {
        return server.getAddress().getPort();
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
            eventLock.notifyAll();
            return event;
        }
    }

    long lastSeq() {
        synchronized (eventLock) {
            return nextSeq - 1;
        }
    }

    int openStreams() {
        return connections.size();
    }

    /** Ends every open SSE response. */
    void dropStreams() {
        for (SseConnection c : connections) {
            c.closed = true;
            c.frames.add("");
        }
    }

    List<Request> rpcRequests(String method) {
        List<Request> matching = new ArrayList<>();
        for (Request r : requests) {
            if (r.path().equals(Protocol.PATH_RPC) && method.equals(Json.parseObject(r.body()).get("method"))) {
                matching.add(r);
            }
        }
        return matching;
    }

    /** Writes this fake's discovery and token files into {@code dir} (created owner-only), as the agent would. */
    Path writeDiscovery(Path dir) throws IOException {
        ownerOnlyDir(dir);
        Path tokenFile = writeTokenFile(dir.resolve("skillroy-" + pid + "-" + startTimeMs + ".token"));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("pid", pid);
        json.put("startTimeMs", startTimeMs);
        json.put("port", port());
        json.put("tokenFile", tokenFile.toString());
        json.put("nonce", agentNonce);
        json.put(Protocol.KEY_SESSION_ID, sessionId);
        json.put("protocol", Protocol.VERSION);
        json.put("loadMode", loadMode.name());
        json.put("mainCommand", mainCommand);
        Path file = dir.resolve("skillroy-" + pid + "-" + startTimeMs + ".json");
        Files.writeString(file, Json.write(json));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    /** Writes the token, owner-only, to {@code file}. */
    Path writeTokenFile(Path file) throws IOException {
        Files.writeString(file, token + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    static Path ownerOnlyDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return dir;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        dropStreams();
        server.stop(0);
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void installDefaults() {
        capabilities.put("test.poke", new Capability("test.poke", CapabilityState.OK, null, "fake"));
        capabilities.put("test.fancy", new Capability("test.fancy", CapabilityState.UNAVAILABLE,
                "not in this fake", null));
        schema.add(new PrimitiveDescriptor("agent.disable", "Stops the agent.", null, null, "CALLER", 10_000, null,
                true, true));
        schema.add(new PrimitiveDescriptor("test.poke", "Increments the counter.", Map.of("type", "object",
                "properties", Map.of("by", Map.of("type", "integer"))), null, "WORKER", 5_000, "test.poke", true,
                false));
        schema.add(new PrimitiveDescriptor("test.counter", "Reads the counter.", null, null, "CALLER", 10_000, null,
                false, false));
        properties.put("fake.seed", "A");

        handlers.put("agent.info", p -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("protocol", Protocol.VERSION);
            info.put("agentVersion", "9.9.9-fake");
            info.put("pid", pid);
            info.put("startTimeMs", startTimeMs);
            info.put("loadMode", loadMode.name());
            info.put("instrumentation", true);
            info.put("readOnly", false);
            info.put("ready", true);
            info.put("javaVersion", "1.8.0_fake");
            info.put("modules", List.of(Map.of("id", "swing", "version", "0.1.0", "state", "INSTALLED")));
            // A misbehaving agent: the CLI must drop these.
            info.put("options", new LinkedHashMap<>(Map.of("token", token, "nonce", agentNonce, "eventRing",
                    "10000")));
            return info;
        });
        handlers.put("agent.capabilities", p -> Map.of("capabilities", capabilitiesJson()));
        handlers.put("agent.disable", p -> Map.of("stopping", true));
        handlers.put("trace.mark", p -> {
            Event mark = publish("trace.mark", Map.of("label", p.str("label", "")));
            return Map.of("seq", mark.seq());
        });
        handlers.put("evt.query", p -> {
            TopicFilter filter = topics(p.get("topics"));
            long since = p.num("since", 0);
            long limit = p.num("limit", 200);
            List<Object> events = new ArrayList<>();
            synchronized (eventLock) {
                for (Event e : ring) {
                    if (e.seq() > since && filter.matches(e.topic()) && events.size() < limit) {
                        events.add(e.toJson());
                    }
                }
                return Map.of("events", events, "lastSeq", nextSeq - 1, "oldestSeq", ring.isEmpty() ? nextSeq
                        : ring.get(0).seq());
            }
        });
        handlers.put("sync.await", p -> {
            TopicFilter filter = topics(p.get("topics"));
            long since = p.num("since", lastSeq());
            Map<String, Object> match = p.obj("match").raw();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(p.num("timeoutMs", 10_000));
            synchronized (eventLock) {
                while (true) {
                    for (Event e : ring) {
                        if (e.seq() > since && filter.matches(e.topic()) && matches(e, match)) {
                            return e.toJson();
                        }
                    }
                    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) {
                        throw new RpcException(ErrorCodes.TIMEOUT, "no matching event", Map.of("lastSeq",
                                nextSeq - 1));
                    }
                    eventLock.wait(remaining);
                }
            }
        });
        handlers.put("jvm.properties", p -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Object name : p.list("names")) {
                values.put(String.valueOf(name), properties.get(String.valueOf(name)));
            }
            return Map.of("properties", values);
        });
        handlers.put("app.state", p -> Map.of("test", Map.of("counter", counter.get())));
        handlers.put("swing.snapshot", p -> Map.of("seq", lastSeq(), "truncated", false, "windows", List.of(
                Map.of("ref", "h1", "role", "window", "name", "Fake Desk", "text", "Fake Desk", "enabled", true,
                        "children", List.of(
                                Map.of("ref", "h2", "role", "button", "name", "DUE", "text", "", "enabled", false),
                                Map.of("ref", "h3", "role", "textbox", "name", "entry", "text", "42"))))));
        handlers.put("swing.dialogs", p -> Map.of("dialogs", List.of()));
        handlers.put("swing.edtState", p -> Map.of("dispatchThreadAlive", true, "modalDialogs", List.of(),
                "windowCount", 1));
        handlers.put("test.poke", p -> {
            int count = counter.addAndGet((int) p.num("by", 1));
            publish("test.poked", Map.of("count", count));
            return Map.of("count", count);
        });
        handlers.put("test.counter", p -> Map.of("value", counter.get()));
        handlers.put("test.echo", p -> Map.of("text", echoAnswer != null ? echoAnswer : p.str("text", "")));
        // A window where focus never arrives (amendment A28's answers): COMPONENT with fallback ACTION fires on
        // ACTION and reports the fall-back; COMPONENT without it does not fire.
        handlers.put("swing.click", p -> {
            if (!"COMPONENT".equals(p.str("rung", ""))) {
                return Map.of("rung", "ACTION", "fired", true, "fidelity", "FULL");
            }
            if ("ACTION".equals(p.str("fallback", ""))) {
                return Map.of("rung", "ACTION", "requestedRung", "COMPONENT", "fidelity", "DEGRADED", "reason",
                        "FOCUS_FAILED: no focus within 500 ms", "fired", true);
            }
            return Map.of("rung", "FOCUS_FAILED", "fired", false, "reason", "FOCUS_FAILED: no focus within 500 ms");
        });
        handlers.put("swing.settle", p -> Map.of("settled", true, "ms", 1L));
    }

    private Map<String, Object> capabilitiesJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        capabilities.values().stream().sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(c -> json.put(c.id(), c.toJson()));
        return json;
    }

    private static TopicFilter topics(Object value) {
        if (value instanceof List<?> list) {
            List<String> globs = new ArrayList<>();
            list.forEach(v -> globs.add(String.valueOf(v)));
            return TopicFilter.of(globs);
        }
        return TopicFilter.parse(value == null ? null : String.valueOf(value));
    }

    private static boolean matches(Event event, Map<String, Object> match) {
        for (Map.Entry<String, Object> e : match.entrySet()) {
            if (!String.valueOf(e.getValue()).equals(String.valueOf(event.payload().get(e.getKey())))) {
                return false;
            }
        }
        return true;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String pathAndQuery = exchange.getRequestURI().getRawPath() + (rawQuery == null ? "" : "?" + rawQuery);
        String method = exchange.getRequestMethod();
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        List<String> authorizations = exchange.getRequestHeaders().get(Auth.HEADER_AUTHORIZATION);
        requests.add(new Request(method, path, pathAndQuery, Auth.onlyValue(authorizations), body));
        try {
            Map<String, String> rawParams = rawQuery(rawQuery);
            String replyNonce = rejectAll ? null : authenticate(path, pathAndQuery, method, bodyBytes, authorizations,
                    exchange.getRequestHeaders().get(Auth.HEADER_BODY_SHA256), rawParams);
            if (replyNonce == null) {
                unauthorized(exchange);
                return;
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) {
                reply(exchange, 403, "{\"error\":\"bad host\"}", replyNonce);
                return;
            }
            if (exchange.getRequestHeaders().getFirst("Origin") != null) {
                reply(exchange, 403, "{\"error\":\"origin\"}", replyNonce);
                return;
            }
            Map<String, String> query = query(rawQuery);
            switch (path) {
                case "/ping" -> reply(exchange, 200, Json.write(ping(rawParams.get(Auth.PING_NONCE_PARAM))),
                        replyNonce);
                case "/rpc" -> rpc(exchange, body, replyNonce);
                case "/events" -> events(exchange, query, replyNonce);
                case "/schema" -> {
                    List<Object> list = new ArrayList<>();
                    schema.forEach(d -> list.add(d.toJson()));
                    reply(exchange, 200, Json.write(Map.of("protocol", Protocol.VERSION, "primitives", list)),
                            replyNonce);
                }
                case "/capabilities" -> reply(exchange, 200, Json.write(Map.of("capabilities",
                        capabilitiesJson())), replyNonce);
                default -> reply(exchange, 404, "{}", replyNonce);
            }
        } catch (IOException | RuntimeException e) {
            exchange.close();
        }
    }

    /**
     * Checks the request's authentication as the agent does, on the head and the body hash: the hello form with the
     * raw {@code nonce} query value on {@code /ping}, the request form bound to this session everywhere else, a body's
     * hash declared once in {@code X-Skillroy-Body-Sha256} and equal to the body's (P2-L1-F1; may be absent without a
     * body).
     *
     * @return the nonce the reply MAC is bound to (the client nonce on {@code /ping}, else the request nonce), or
     *     null when the request is not authenticated
     */
    private String authenticate(String path, String pathAndQuery, String method, byte[] body,
                                List<String> authorizations, List<String> bodyHashes, Map<String, String> rawParams) {
        Auth.Credentials credentials;
        try {
            credentials = Auth.parseAuthorization(Auth.onlyValue(authorizations));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (Protocol.PATH_PING.equals(path)) {
            String clientNonce = rawParams.get(Auth.PING_NONCE_PARAM);
            return credentials.isHello() && Auth.verifyHello(token, clientNonce, credentials) ? clientNonce : null;
        }
        String bodyHash = Auth.sha256hex(body);
        boolean declared = bodyHashes == null ? body.length == 0 : bodyHash.equals(Auth.onlyValue(bodyHashes));
        return declared && !credentials.isHello() && Auth.verifyRequest(token, sessionId, credentials, method,
                pathAndQuery, bodyHash) ? credentials.nonce() : null;
    }

    /** The constant 401 of the agent: no MAC, the scheme to use, and the connection closed. */
    private static void unauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add(Auth.HEADER_WWW_AUTHENTICATE, Auth.SCHEME);
        exchange.getResponseHeaders().add("Connection", "close");
        byte[] bytes = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(401, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Adds the reply MAC and session headers for a reply with this status and body hash. */
    private void sign(HttpExchange exchange, String replyNonce, int status, String bodySha256) {
        if (!omitReplyMac) {
            String key = wrongReplyMac ? randomHex(32) : token;
            exchange.getResponseHeaders().add(Auth.HEADER_MAC, Auth.responseMac(key, sessionId, replyNonce, status,
                    bodySha256));
        }
        exchange.getResponseHeaders().add(Auth.HEADER_SESSION, sessionId);
    }

    private Map<String, Object> ping(String nonce) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("nonce", nonce);
        reply.put("clientNonce", nonce);
        reply.put(Protocol.KEY_SESSION_ID, sessionId);
        reply.put("agentNonce", agentNonce);
        reply.put("protocol", Protocol.VERSION);
        reply.put("pid", pid);
        reply.put("startTimeMs", startTimeMs);
        reply.put("loadMode", loadMode.name());
        reply.put("ready", true);
        reply.put("readOnly", false);
        return reply;
    }

    private void rpc(HttpExchange exchange, String body, String replyNonce) throws IOException {
        Map<String, Object> request = Json.parseObject(body);
        String method = (String) request.get("method");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) request.get("params"));
        Object meta = params.remove("_meta");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", request.get("id"));
        Handler handler = handlers.get(method);
        try {
            if (handler == null) {
                throw new RpcException(ErrorCodes.METHOD_NOT_FOUND, "no such method: " + method);
            }
            if (schema.stream().anyMatch(d -> d.name().equals(method) && d.destructive())
                    && !(meta instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("confirm")))) {
                throw new RpcException(ErrorCodes.CONFIRM_REQUIRED, method + " is destructive",
                        Map.of("primitive", method));
            }
            long before = lastSeq();
            Object value = handler.handle(JsonView.of(params));
            response.put("result", new RpcResult(value, List.of(), before, lastSeq(), 3, "fake-worker").toJson());
        } catch (RpcException e) {
            response.put("error", e.error().toJson());
        } catch (Exception e) {
            response.put("error", new RpcException(ErrorCodes.INTERNAL_ERROR, e.toString()).error().toJson());
        }
        reply(exchange, 200, Json.write(response), replyNonce);
    }

    private void events(HttpExchange exchange, Map<String, String> query, String replyNonce) throws IOException {
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        SseConnection connection = new SseConnection(TopicFilter.parse(query.get("topics")));
        synchronized (eventLock) {
            long resume = lastEventId != null ? Long.parseLong(lastEventId.strip())
                    : query.containsKey("since") ? Long.parseLong(query.get("since")) : nextSeq - 1;
            for (Event e : ring) {
                if (e.seq() > resume && connection.filter.matches(e.topic())) {
                    connection.frames.add(frame(e));
                }
            }
            connections.add(connection);
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Connection", "close");
        sign(exchange, replyNonce, 200, Auth.EMPTY_BODY_SHA256);
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            streamsOpened.release();
            while (!connection.closed) {
                String frame = connection.frames.poll(1, TimeUnit.SECONDS);
                if (connection.closed) {
                    break;
                }
                out.write((frame == null ? ": hb\n\n" : frame).getBytes(StandardCharsets.UTF_8));
                out.flush();
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

    private void reply(HttpExchange exchange, int status, String body, String replyNonce) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        sign(exchange, replyNonce, status, Auth.sha256hex(bytes));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The query's parameters without percent-decoding (the ping nonce is MAC'd as sent). */
    private static Map<String, String> rawQuery(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            params.putIfAbsent(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
        }
        return params;
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
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value,
                    StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
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
