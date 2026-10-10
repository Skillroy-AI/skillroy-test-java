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
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The HTTP implementation of {@link AgentConnection}: JSON-RPC over {@code POST /rpc}, the {@code GET} endpoints, and
 * SSE event streams, against one agent on the loopback interface. Exists so that the executor, the CLI and tests talk
 * to an agent through one class that applies authentication, the handshake and the error mapping consistently.
 *
 * <p>Transport: {@link HttpClient}, HTTP/1.1, no proxy, no redirects, connect timeout 5 s, always
 * {@code http://127.0.0.1:<port>} (an endpoint host of {@code localhost} is accepted and normalised, anything else is
 * rejected). The {@code Host} header is {@code 127.0.0.1:<port>}, which the agent's Host check accepts. No
 * {@code Origin} header is sent.
 *
 * <p>Authentication (hardening item H2, {@link Auth}): the token never travels. {@code GET /ping?nonce=<n>} carries
 * {@code Authorization: Skillroy-HMAC mac=<HMAC("hello", n)>} with a fresh client nonce; every other request carries
 * {@code Authorization: Skillroy-HMAC nonce=<r>, mac=<HMAC("req", sessionId, r, method, pathAndQuery,
 * sha256(body))>} with a fresh request nonce, and a {@code POST} also carries the body hash in
 * {@code X-Skillroy-Body-Sha256}, so that the agent verifies the request on its head before it reads the body (Phase 6
 * security fix, pass 2, P2-L1-F1). Every reply must carry {@code X-Skillroy-Mac} over the session, the same
 * nonce, the status and the SHA-256 of the exact reply body; it is verified before anything in the body is looked at.
 * The session id is taken from the {@code X-Skillroy-Session} header of the first verified {@code /ping} (or, when the
 * endpoint names one, for example from its discovery file, it must be that one); every later ping must report the
 * same session, so a restarted agent is noticed at once.
 *
 * <p>Handshake: {@link #connect} and every {@link #ping()} require, after the MAC, the body's {@code sessionId} to be
 * the header's, the client nonce to be echoed ({@code nonce}, and {@code clientNonce} when present), the same
 * protocol major version as {@link Protocol#VERSION}, and, when the endpoint knows them, the endpoint's
 * {@code agentNonce}, pid and JVM start time.
 *
 * <p>Calls: request ids are {@code "c-<n>"} per client. {@link CallOptions} go into {@code params._meta}
 * ({@code correlationId}, always present and generated when not given; {@code timeoutMs}; {@code confirm} when true;
 * {@code secretParams} when not empty, amendment A34; {@code traceparent} when the options carry one, Phase 6:
 * phase6-contracts.md 10.2), and nothing else does: a {@code _meta} among the params is dropped (phase5-contracts.md
 * 12.2).
 * The deadline of a call is {@code timeoutMs + 5 s}, or 130 s when no timeout is given, so the agent's own TIMEOUT
 * error normally arrives first. Every deadline covers the whole reply, headers and body (finding S23), and a reply
 * body is limited to 64 MiB (64 KiB for {@code /ping}); a late or oversized reply is an {@link AgentLostException}.
 *
 * <p>The lost latch (H2): the client becomes <em>lost</em> for good on a refused connection, a reset or closed
 * connection after which the port no longer accepts connections, an HTTP 401, a missing or wrong reply MAC, a failed
 * handshake (including a changed session id), a successful {@code agent.disable} sent through this client, an
 * {@code agent.stopping} event seen by one of its event streams, and, when the endpoint names a pid, that process no
 * longer running (checked before every request). From then on every request and every event stream fails with
 * {@link AgentLostException} without any I/O. A plain timeout does not set it.
 *
 * <p>Error mapping: a JSON-RPC error is {@link AgentRpcException}. Everything above, a closed client, an HTTP status
 * other than 200 (403 = Host/Origin rejected) and a reply that is not valid protocol JSON are
 * {@link AgentLostException}. A refusal the agent sends before it has authenticated the request (400, 413, 431, 501,
 * or 503 when its budget for the bodies of connections that have not authenticated is spent, which since P2-L1-F1
 * only holders of the key can spend) carries no MAC: it fails the call without setting the
 * latch, and its body is not used. Event streams apply the same rule ({@link #isUnsignedRefusal}).
 *
 * <p>Keep-alive and the one retry. The HTTP client pools an idle connection for 30 s
 * ({@code jdk.httpclient.keepalive.timeout}); the agent closes an idle authenticated connection after its option
 * {@code httpIdleTimeoutMs}, two minutes by default. Two measures keep a call from meeting a connection the agent has
 * just closed, which would otherwise end a scenario with an {@link AgentLostException}: the agent's longer timeout
 * makes this client drop an idle connection first, so the race does not arise with the defaults; and because a JVM
 * may set a longer pool timeout, or an operator a shorter agent timeout, a {@code POST /rpc} whose connection closes
 * or is reset before the first byte of the reply, while the port still accepts connections, is sent once more on a
 * fresh connection with a fresh request nonce. That retry is safe because the agent closes a connection without
 * replying only before it has read a request (idle, evicted, or turned away when every thread is busy) or while it
 * stops, and a stopping agent's port no longer accepts connections (which latches instead). No other failure of a
 * JSON-RPC call is retried: a call is not idempotent. (The JDK client itself retries such a {@code GET} once, never a
 * {@code POST}.) The retry has the same deadline as the first attempt.
 *
 * <p>Thread-safe: any number of threads may call concurrently. {@link #close()} may be called from any thread; calls
 * in flight then fail with {@link AgentLostException}.
 */
public final class AgentClient implements AgentConnection {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String META = "_meta";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String DISABLE = "agent.disable";
    private static final Duration MAX_AWAIT = Duration.ofDays(1);
    private static final int PROBE_TIMEOUT_MS = 1000;
    private static final byte[] NO_BYTES = new byte[0];

    private final Endpoint endpoint;
    private final ClientSettings settings;
    private final HttpClient http;
    private final String baseUri;
    private final String token;
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicLong correlationIds = new AtomicLong();
    private final String correlationPrefix = "ctl-" + SecureFiles.randomHex(4) + "-";
    private final Set<SseEventStream> streams = ConcurrentHashMap.newKeySet();
    /** The short-lived clients of retries in flight ({@link #postRpc}), shut down by {@link #close()}. */
    private final Set<HttpClient> retryClients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> lostReason = new AtomicReference<>();
    private volatile String sessionId;
    private volatile Map<String, Object> lastPing;

    /** A reply as it came off the wire: the body is verified against the MAC before it is decoded or parsed. */
    private record Reply(int status, byte[] body, HttpHeaders headers) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private AgentClient(Endpoint endpoint, ClientSettings settings) {
        this.endpoint = endpoint;
        this.settings = settings;
        this.baseUri = "http://" + LOOPBACK + ":" + endpoint.port();
        this.token = endpoint.token();
        this.sessionId = endpoint.sessionId();
        this.http = newHttpClient(settings);
    }

    /** HTTP/1.1, no proxy, no redirects, the settings' connect timeout. */
    private static HttpClient newHttpClient(ClientSettings settings) {
        return HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Connects to the agent at {@code endpoint} and performs the authenticated handshake (see the class comment).
     *
     * @param endpoint a loopback endpoint with a token; its nonce, pid, start time and session id are checked when
     *     known
     * @return a connected client; close it when done
     * @throws AgentLostException if the agent cannot be reached, the peer cannot prove it holds the token, or the
     *     handshake does not match
     * @throws IllegalArgumentException if the host is not loopback or the endpoint has no usable token
     */
    public static AgentClient connect(Endpoint endpoint) throws AgentLostException {
        return connect(endpoint, ClientSettings.DEFAULTS);
    }

    /** {@link #connect(Endpoint)} with explicit timeouts and limits (tests and discovery). */
    static AgentClient connect(Endpoint endpoint, ClientSettings settings) throws AgentLostException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(settings, "settings");
        String host = endpoint.host().strip().toLowerCase(Locale.ROOT);
        if (!host.equals(LOOPBACK) && !host.equals("localhost")) {
            throw new IllegalArgumentException("the agent listens on 127.0.0.1 only, not on " + endpoint.host());
        }
        if (!SecureFiles.isValidToken(endpoint.token())) {
            throw new IllegalArgumentException("endpoint has no usable token: " + endpoint);
        }
        AgentClient client = new AgentClient(endpoint, settings);
        try {
            client.ping();
            return client;
        } catch (AgentLostException | RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /** The endpoint this client was connected to. */
    public Endpoint endpoint() {
        return endpoint;
    }

    @Override
    public boolean isLost() {
        return lostReason.get() != null;
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException, AgentLostException {
        return call(method, params, CallOptions.defaults());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The request's {@code _meta} is built from the options alone: a {@code _meta} in {@code params} is dropped,
     * never merged (phase5-contracts.md 12.2), so no map a caller passes on can confirm a destructive call. A
     * successful {@code agent.disable} marks this client lost (the agent is going away).
     *
     * @param options null means {@link CallOptions#defaults()}
     */
    @Override
    public RpcResult call(String method, Map<String, Object> params, CallOptions options)
            throws AgentRpcException, AgentLostException {
        CallOptions effective = options == null ? CallOptions.defaults() : options;
        Duration httpTimeout = effective.timeoutMs() != null
                ? Duration.ofMillis(effective.timeoutMs()).plus(settings.callSlack())
                : settings.callCeiling();
        return invoke(method, params, effective, httpTimeout);
    }

    /**
     * Calls {@code sync.await}: {@code topics}, {@code since}, {@code match} (when not null) and {@code timeoutMs} go
     * into the params; the agent's dispatcher allows {@code timeoutMs + 1 s} for it, and the HTTP deadline is a
     * further 5 s.
     *
     * @param timeout how long the agent waits; values below 1 ms are sent as 1 ms, values above one day as one day
     * @return the first matching event, or null when the agent answered TIMEOUT
     * @throws AgentRpcException for any other JSON-RPC error (for example INVALID_PARAMS for a malformed topic
     *     filter); contract amendment A31
     */
    @Override
    public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
            throws AgentRpcException, AgentLostException {
        Objects.requireNonNull(timeout, "timeout");
        long timeoutMs = timeout.compareTo(MAX_AWAIT) >= 0 ? MAX_AWAIT.toMillis() : Math.max(1L, timeout.toMillis());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topics", topics);
        params.put("since", sinceSeq);
        if (match != null) {
            params.put("match", match);
        }
        params.put("timeoutMs", timeoutMs);
        RpcResult result;
        try {
            result = invoke("sync.await", params, CallOptions.defaults(),
                    Duration.ofMillis(timeoutMs + 1000L).plus(settings.callSlack()));
        } catch (AgentRpcException e) {
            if (e.error().code() == ErrorCodes.TIMEOUT) {
                return null;
            }
            throw e;
        }
        Object value = result.value();
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw lost("sync.await returned " + value.getClass().getSimpleName() + " instead of an event", null);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventJson = (Map<String, Object>) value;
            return Event.fromJson(eventJson);
        } catch (JsonException e) {
            throw lost("sync.await returned an invalid event: " + e.getMessage(), e);
        }
    }

    /** {@code GET /capabilities}. */
    @Override
    public CapabilityMatrix capabilities() throws AgentLostException {
        Map<String, Object> json = getJson(Protocol.PATH_CAPABILITIES, settings.getTimeout());
        try {
            return CapabilityMatrix.fromJson(json);
        } catch (RuntimeException e) {
            throw lost("invalid " + Protocol.PATH_CAPABILITIES + " reply: " + e.getMessage(), e);
        }
    }

    /** {@code GET /schema}; the list is sorted by name and unmodifiable. */
    @Override
    public List<PrimitiveDescriptor> schema() throws AgentLostException {
        Map<String, Object> json = getJson(Protocol.PATH_SCHEMA, settings.getTimeout());
        try {
            Object primitives = json.get("primitives");
            if (!(primitives instanceof List<?> entries)) {
                throw new JsonException("missing array \"primitives\"");
            }
            List<PrimitiveDescriptor> descriptors = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?>)) {
                    throw new JsonException("a primitive is not an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> descriptorJson = (Map<String, Object>) entry;
                descriptors.add(PrimitiveDescriptor.fromJson(descriptorJson));
            }
            descriptors.sort(Comparator.comparing(PrimitiveDescriptor::name));
            return Collections.unmodifiableList(descriptors);
        } catch (RuntimeException e) {
            throw lost("invalid " + Protocol.PATH_SCHEMA + " reply: " + e.getMessage(), e);
        }
    }

    /**
     * {@code GET /ping} with a fresh client nonce, authenticated and verified as described on the class.
     *
     * @return the reply, unmodifiable
     * @throws AgentLostException if the agent cannot be reached or a check fails
     */
    @Override
    public Map<String, Object> ping() throws AgentLostException {
        String clientNonce = Auth.newNonce();
        String pathAndQuery = Protocol.PATH_PING + "?" + Auth.PING_NONCE_PARAM + "=" + clientNonce;
        HttpRequest request = builder(pathAndQuery, settings.pingTimeout())
                .header(Auth.HEADER_AUTHORIZATION, Auth.helloAuthorization(token, clientNonce))
                .GET()
                .build();
        Reply reply = exchange(request, Protocol.PATH_PING, settings.maxPingBytes());
        if (reply.status() == 401) {
            throw latch(Protocol.PATH_PING + " answered HTTP 401" + REJECTED, null);
        }
        String session = Auth.onlyValue(reply.headers().allValues(Auth.HEADER_SESSION));
        String mac = Auth.onlyValue(reply.headers().allValues(Auth.HEADER_MAC));
        if (!Auth.isValidSessionId(session) || !Auth.verifyResponse(token, session, clientNonce, reply.status(),
                Auth.sha256hex(reply.body()), mac)) {
            throw latch(Protocol.PATH_PING + ": the reply (HTTP " + reply.status() + ") carries no valid "
                    + Auth.HEADER_MAC + " and " + Auth.HEADER_SESSION + ": " + notTheAgent(), null);
        }
        if (reply.status() != 200) {
            throw lost(Protocol.PATH_PING + " answered HTTP " + reply.status() + explain(reply.status())
                    + snippet(reply.text()), null);
        }
        String known = sessionId;
        if (known != null && !known.equals(session)) {
            throw latch(Protocol.PATH_PING + ": the agent's session is " + session + ", not " + known
                    + ": the agent was restarted, or this is another agent", null);
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(reply.text());
        } catch (JsonException e) {
            throw latch(Protocol.PATH_PING + ": reply is not a JSON object: " + e.getMessage(), e);
        }
        verifyPing(body, clientNonce, session);
        sessionId = session;
        Map<String, Object> result = Collections.unmodifiableMap(body);
        lastPing = result;
        return result;
    }

    /**
     * Opens an SSE subscription to {@code /events}. Events (and {@code gap} notices, delivered as events with topic
     * {@code "gap"}, the agent's {@code {from,to}} as payload and the end of the lost range as {@code seq}) are
     * passed to {@code onEvent} in sequence order on a daemon thread named {@code skillroy-sse-<n>}. After a dropped
     * connection or 45 s without a line (the agent sends a heartbeat every 15 s) the stream reconnects with
     * {@code Last-Event-ID} and capped back-off (0.1 s to 2 s), skipping events it has already delivered, until it
     * is closed or the agent is lost (a refused connection, HTTP 401/403/404, a missing or wrong reply MAC, an
     * {@code agent.stopping} event, the endpoint's process gone, or this client lost). A refusal before
     * authentication ({@link #isUnsignedRefusal}) is a failed attempt like a dropped connection. A line longer than
     * 4 MiB or an event with more than 4 Mi characters of data ends the stream as a protocol violation (finding S25).
     *
     * <p>On a client that is already lost the stream ends at once, without any I/O ({@link EventStream#isOpen()} is
     * false).
     *
     * @param sinceSeq deliver events after this sequence number; a negative value means "from now" (until the first
     *     event has been delivered such a stream has no resume point, so a reconnect in that window starts from now
     *     again)
     * @param topics comma-separated globs; null or blank means all topics
     * @param onEvent must not block for long; exceptions it throws are logged and the stream continues
     * @throws IllegalStateException if this client is closed
     */
    public EventStream events(long sinceSeq, String topics, Consumer<Event> onEvent) {
        Objects.requireNonNull(onEvent, "onEvent");
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        SseEventStream stream = new SseEventStream(this, sinceSeq, topics, onEvent, settings);
        streams.add(stream);
        if (closed.get()) {
            // close() ran in between and may already have taken its copy of the set.
            streams.remove(stream);
            throw new IllegalStateException("client is closed");
        }
        stream.start();
        return stream;
    }

    /** Closes every event stream and the HTTP client. Idempotent; never throws. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<SseEventStream> open = new ArrayList<>(streams);
        for (SseEventStream stream : open) {
            stream.signalClose();
        }
        try {
            http.shutdownNow();
        } catch (RuntimeException ignored) {
            // Nothing left to release.
        }
        for (HttpClient retry : new ArrayList<>(retryClients)) {
            try {
                retry.shutdownNow();
            } catch (RuntimeException ignored) {
                // Nothing left to release.
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        for (SseEventStream stream : open) {
            stream.awaitTermination(Math.max(0L, deadline - System.nanoTime()));
        }
        streams.clear();
    }

    /** The reply of the last successful {@link #ping()}, or null (package-private for attach). */
    Map<String, Object> lastPing() {
        return lastPing;
    }

    /** The session id verified by the last successful ping (or given by the endpoint), or null. */
    String sessionId() {
        return sessionId;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Package-private plumbing shared with SseEventStream.

    HttpClient http() {
        return http;
    }

    boolean isClosed() {
        return closed.get();
    }

    void forget(SseEventStream stream) {
        streams.remove(stream);
    }

    /** Why this client is lost, or null while it is not. */
    String lostReason() {
        return lostReason.get();
    }

    /** Sets the lost latch (the first reason wins). */
    void markLost(String reason) {
        lostReason.compareAndSet(null, Objects.requireNonNull(reason, "reason"));
    }

    /** "the peer on port N is not the agent (or does not hold this token)". */
    String notTheAgent() {
        return "the peer on port " + endpoint.port() + " is not the agent (or does not hold this token)";
    }

    /**
     * An authenticated {@code GET} request builder for {@code pathAndQuery} (visible ASCII, as it goes on the request
     * line), for the event stream.
     *
     * @param reqNonce the fresh request nonce the reply MAC will be checked against
     * @param timeout the request timeout; null for none (an event stream, which bounds its own waits: newer JDKs
     *     apply a request timeout to the whole response body, which would cut a healthy stream)
     * @throws AgentLostException if no session id is known
     */
    HttpRequest.Builder authorizedGet(String pathAndQuery, String reqNonce, Duration timeout)
            throws AgentLostException {
        return builder(pathAndQuery, timeout)
                .header(Auth.HEADER_AUTHORIZATION, requestAuthorization(GET, pathAndQuery, reqNonce,
                        Auth.EMPTY_BODY_SHA256))
                .GET();
    }

    /** True if {@code headers} carry the reply MAC of a reply to {@code reqNonce} with this status and body hash. */
    boolean verifyReply(int status, HttpHeaders headers, String reqNonce, String bodySha256) {
        String session = sessionId;
        return session != null && Auth.verifyResponse(token, session, reqNonce, status, bodySha256,
                Auth.onlyValue(headers.allValues(Auth.HEADER_MAC)));
    }

    /**
     * A body handler that keeps at most {@code maxBytes} of the reply body and fails the exchange with an
     * {@link IOException} above that (finding S23); a {@code Content-Length} above the cap fails it at once.
     */
    static HttpResponse.BodyHandler<byte[]> capped(long maxBytes) {
        return info -> {
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new CappedSubscriber(maxBytes, declared);
        };
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static final String REJECTED = " (authentication rejected: the token is not this agent's, or the agent"
            + " was restarted)";

    private HttpRequest.Builder builder(String pathAndQuery, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri + pathAndQuery));
        return timeout == null ? builder : builder.timeout(timeout);
    }

    private String requestAuthorization(String method, String pathAndQuery, String reqNonce, String bodySha256)
            throws AgentLostException {
        String session = sessionId;
        if (session == null) {
            throw lost(method + " " + pathAndQuery + ": no verified session yet (ping first)", null);
        }
        return Auth.requestAuthorization(token, session, reqNonce, method, pathAndQuery, bodySha256);
    }

    private RpcResult invoke(String method, Map<String, Object> params, CallOptions options, Duration httpTimeout)
            throws AgentRpcException, AgentLostException {
        Objects.requireNonNull(method, "method");
        String id = "c-" + requestIds.incrementAndGet();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", withMeta(params, options));
        byte[] body = Json.write(request).getBytes(StandardCharsets.UTF_8);
        String text = postRpc(method, body, httpTimeout);
        Map<String, Object> response;
        try {
            response = Json.parseObject(text);
        } catch (JsonException e) {
            throw lost(method + ": reply is not a JSON object: " + e.getMessage(), e);
        }
        Object error = response.get("error");
        if (error != null) {
            if (!(error instanceof Map<?, ?>)) {
                throw lost(method + ": JSON-RPC error member is not an object", null);
            }
            RpcError rpcError;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> errorJson = (Map<String, Object>) error;
                rpcError = RpcError.fromJson(errorJson);
            } catch (JsonException e) {
                throw lost(method + ": invalid JSON-RPC error: " + e.getMessage(), e);
            }
            throw new AgentRpcException(method, rpcError);
        }
        if (!id.equals(response.get("id"))) {
            throw lost(method + ": reply id " + response.get("id") + " does not match request id " + id, null);
        }
        Object result = response.get("result");
        if (!(result instanceof Map<?, ?>)) {
            throw lost(method + ": reply has no result object", null);
        }
        RpcResult rpcResult;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultJson = (Map<String, Object>) result;
            rpcResult = RpcResult.fromJson(resultJson);
        } catch (JsonException e) {
            throw lost(method + ": invalid result: " + e.getMessage(), e);
        }
        if (DISABLE.equals(method)) {
            markLost("the agent was disabled through this client (" + DISABLE + ")");
        }
        return rpcResult;
    }

    /**
     * Posts one JSON-RPC body to {@code /rpc} and returns the reply text once its MAC is verified. When the
     * connection closes or is reset before the first byte of the reply and the port still accepts connections, the
     * same body goes once more, with a fresh request nonce, on a fresh connection (see "Keep-alive and the one retry"
     * on the class); nothing else is retried.
     */
    private String postRpc(String method, byte[] body, Duration httpTimeout) throws AgentLostException {
        String reqNonce = Auth.newNonce();
        Reply reply;
        try {
            reply = send(http, rpcRequest(body, reqNonce, httpTimeout), method, settings.maxReplyBytes());
        } catch (ClosedBeforeReply first) {
            reqNonce = Auth.newNonce();
            reply = retryOnFreshConnection(rpcRequest(body, reqNonce, httpTimeout), method, first.getCause());
        }
        return verified(reply, reqNonce, method);
    }

    private HttpRequest rpcRequest(byte[] body, String reqNonce, Duration httpTimeout) throws AgentLostException {
        String bodyHash = Auth.sha256hex(body);
        // P2-L1-F1: the body hash travels in the head, so that the agent verifies the MAC before it reads the body.
        return builder(Protocol.PATH_RPC, httpTimeout)
                .header(Auth.HEADER_AUTHORIZATION, requestAuthorization(POST, Protocol.PATH_RPC, reqNonce, bodyHash))
                .header(Auth.HEADER_BODY_SHA256, bodyHash)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    /**
     * Sends {@code request} on a client of its own, so on a new connection whatever this client's pool holds, and
     * shuts that client down afterwards (or when {@link #close()} runs meanwhile).
     */
    private Reply retryOnFreshConnection(HttpRequest request, String what, Throwable firstCause)
            throws AgentLostException {
        HttpClient fresh = newHttpClient(settings);
        retryClients.add(fresh);
        try {
            // close() sets the flag before it shuts the retry clients down: it either sees this one or is seen here.
            if (closed.get()) {
                throw new AgentLostException("connection to " + where() + " is closed (" + what + ")");
            }
            return send(fresh, request, what, settings.maxReplyBytes());
        } catch (ClosedBeforeReply again) {
            throw lost(what + " failed: the connection closed before any byte of the reply (" + describe(firstCause)
                    + "), and again on a fresh connection (" + describe(again.getCause()) + ")", again.getCause());
        } finally {
            retryClients.remove(fresh);
            try {
                fresh.shutdownNow();
            } catch (RuntimeException ignored) {
                // Nothing left to release.
            }
        }
    }

    /**
     * The params as sent: every param but {@code _meta}, then {@code _meta} built from the options alone. A
     * {@code _meta} among the params is dropped, never merged (phase5-contracts.md 12.2, the Phase 5 fix wave): a
     * caller's map could otherwise confirm a destructive call without {@link CallOptions#confirm()}.
     */
    private Map<String, Object> withMeta(Map<String, Object> params, CallOptions options) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!META.equals(e.getKey())) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        String correlationId = options.correlationId() != null ? options.correlationId()
                : correlationPrefix + correlationIds.incrementAndGet();
        meta.put("correlationId", correlationId);
        if (options.timeoutMs() != null) {
            meta.put("timeoutMs", options.timeoutMs());
        }
        if (options.confirm()) {
            meta.put("confirm", Boolean.TRUE);
        }
        if (!options.secretParams().isEmpty()) {
            meta.put(Protocol.META_SECRET_PARAMS, new ArrayList<>(options.secretParams()));
        }
        if (options.traceparent() != null) {
            // Phase 6 (phase6-contracts.md 10.2): the controller's open call span, which the agent stamps on the
            // call's events; sent only when telemetry supplies one.
            meta.put("traceparent", options.traceparent());
        }
        copy.put(META, meta);
        return copy;
    }

    private Map<String, Object> getJson(String path, Duration timeout) throws AgentLostException {
        String reqNonce = Auth.newNonce();
        HttpRequest request = builder(path, timeout)
                .header(Auth.HEADER_AUTHORIZATION, requestAuthorization(GET, path, reqNonce, Auth.EMPTY_BODY_SHA256))
                .GET()
                .build();
        String text = verified(exchange(request, path, settings.maxReplyBytes()), reqNonce, path);
        try {
            return Json.parseObject(text);
        } catch (JsonException e) {
            throw lost(path + ": reply is not a JSON object: " + e.getMessage(), e);
        }
    }

    /**
     * Fails without I/O when the client is closed or lost, or when the endpoint's process is gone (which sets the
     * latch).
     */
    private void checkUsable(String what) throws AgentLostException {
        if (closed.get()) {
            throw new AgentLostException("connection to " + where() + " is closed (" + what + ")");
        }
        String reason = lostReason.get();
        if (reason != null) {
            throw new AgentLostException("agent at " + where() + " is lost, " + what + " not sent: " + reason);
        }
        if (endpoint.pid() > 0 && !Discovery.isLive(endpoint.pid(), endpoint.startTimeMs())) {
            throw latch("process " + endpoint.pid() + " is no longer running (" + what + " not sent)", null);
        }
    }

    /**
     * Sends {@code request} and waits for the whole reply within the request's timeout (or the call ceiling), at most
     * {@code maxBytes} of body. Nothing in the reply is verified here. Not retried here (a {@code GET} the JDK client
     * retries itself, see the class comment).
     */
    private Reply exchange(HttpRequest request, String what, long maxBytes) throws AgentLostException {
        try {
            return send(http, request, what, maxBytes);
        } catch (ClosedBeforeReply e) {
            throw lost(what + " failed: " + describe(e.getCause()), e.getCause());
        }
    }

    /**
     * {@link #exchange} on {@code client}, reporting a connection that closed before any byte of the reply, on a port
     * that still accepts connections, as {@link ClosedBeforeReply} for the caller to decide on a retry.
     */
    private Reply send(HttpClient client, HttpRequest request, String what, long maxBytes)
            throws AgentLostException, ClosedBeforeReply {
        checkUsable(what);
        Duration limit = request.timeout().orElse(settings.callCeiling());
        AtomicBoolean headReceived = new AtomicBoolean();
        HttpResponse.BodyHandler<byte[]> handler = info -> {
            headReceived.set(true);
            return capped(maxBytes).apply(info);
        };
        CompletableFuture<HttpResponse<byte[]>> future;
        try {
            future = client.sendAsync(request, handler);
        } catch (RuntimeException e) {
            if (closed.get()) {
                throw new AgentLostException("connection to " + where() + " is closed (" + what + ")", e);
            }
            throw e;
        }
        HttpResponse<byte[]> response;
        try {
            response = future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw lost(what + ": no reply within " + limit.toMillis() + " ms (the reply did not complete)", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentLostException("interrupted while waiting for " + what + " from " + where(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (!closed.get() && !headReceived.get() && closedBeforeReply(cause)) {
                if (portRefuses()) {
                    throw latch(what + " failed: " + describe(cause) + ", and the port no longer accepts connections",
                            cause);
                }
                throw new ClosedBeforeReply(cause);
            }
            throw failure(what, limit, cause);
        }
        byte[] body = response.body();
        return new Reply(response.statusCode(), body == null ? NO_BYTES : body, response.headers());
    }

    /**
     * How the JDK's HTTP/1.1 client words a failed exchange whose connection ended (EOF, reset, broken pipe) before
     * a single byte of the reply's status line had arrived: its header parser is still in its initial state. Once a
     * byte has arrived the text names the part being parsed instead, and once the head is complete the body handler
     * has been called. Should a later JDK word it differently, nothing is retried any more (the safe direction), and
     * {@code RpcRetryTest} and {@code AgentClientTest} fail.
     */
    static final String NO_REPLY_BYTES = "header parser received no bytes";

    /** True if {@code cause} is a connection that closed or was reset before any byte of the reply arrived. */
    static boolean closedBeforeReply(Throwable cause) {
        if (!(cause instanceof IOException) || cause instanceof HttpTimeoutException
                || cause instanceof ReplyTooLargeException || causedBy(cause, ConnectException.class)) {
            return false;
        }
        for (Throwable c = cause; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String message = c.getMessage();
            if (message != null && message.contains(NO_REPLY_BYTES)) {
                return true;
            }
        }
        return false;
    }

    /** The connection closed before any byte of the reply while the port still accepts connections. */
    private static final class ClosedBeforeReply extends Exception {
        private static final long serialVersionUID = 1L;

        ClosedBeforeReply(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    /** Maps a failed exchange to the exception to throw, setting the latch where the agent is evidently gone. */
    private AgentLostException failure(String what, Duration limit, Throwable cause) {
        if (closed.get()) {
            return new AgentLostException("connection to " + where() + " was closed during " + what, cause);
        }
        if (cause instanceof HttpTimeoutException) {
            return lost(what + ": no reply within " + limit.toMillis() + " ms (" + describe(cause) + ")", cause);
        }
        if (cause instanceof ReplyTooLargeException) {
            return lost(what + ": " + cause.getMessage(), cause);
        }
        if (causedBy(cause, ConnectException.class)) {
            return latch(what + " failed: connection refused (" + describe(cause) + "): nothing listens there any "
                    + "more", cause);
        }
        if (cause instanceof IOException && portRefuses()) {
            return latch(what + " failed: " + describe(cause) + ", and the port no longer accepts connections",
                    cause);
        }
        return lost(what + " failed: " + describe(cause), cause);
    }

    /**
     * True if nothing accepts a connection on the endpoint's port now. Used after a reset or closed connection to
     * tell an agent that went away from a connection that merely went stale; a squatter that binds the port later
     * gains nothing, because the token never travels and it cannot produce a valid reply MAC.
     */
    private boolean portRefuses() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, endpoint.port()), PROBE_TIMEOUT_MS);
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The body of a reply to a request with {@code reqNonce}, after its MAC has been verified: a 401 and a missing or
     * wrong MAC set the latch; another status than 200 is a plain {@link AgentLostException}.
     */
    private String verified(Reply reply, String reqNonce, String what) throws AgentLostException {
        if (reply.status() == 401) {
            throw latch(what + " answered HTTP 401" + REJECTED, null);
        }
        if (isUnsignedRefusal(reply.status(), reply.headers())) {
            // The agent refuses some requests before it has authenticated them, so without a MAC. Nothing in the
            // body is used, so an impostor gains nothing; not latching keeps a transient 503 from ending the client.
            throw lost(what + " was refused before authentication: HTTP " + reply.status()
                    + explainPreAuthentication(reply.status()), null);
        }
        if (!verifyReply(reply.status(), reply.headers(), reqNonce, Auth.sha256hex(reply.body()))) {
            throw latch(what + ": the reply (HTTP " + reply.status() + ") carries no valid " + Auth.HEADER_MAC + ": "
                    + notTheAgent(), null);
        }
        if (reply.status() != 200) {
            throw lost(what + " answered HTTP " + reply.status() + explain(reply.status()) + snippet(reply.text()),
                    null);
        }
        return reply.text();
    }

    /**
     * Statuses the agent sends before a request is authenticated, without a reply MAC (malformed head 400, body over
     * the cap 413, head over the cap 431, {@code Transfer-Encoding} 501, budget for unauthenticated bodies spent 503).
     */
    private static final Set<Integer> PRE_AUTHENTICATION_STATUSES = Set.of(400, 413, 431, 501, 503);

    /**
     * True for a refusal the agent sends before it has authenticated the request: one of the statuses of
     * {@link #PRE_AUTHENTICATION_STATUSES} without any reply MAC header. Such a reply fails the attempt without
     * setting the lost latch, and its body is never used, so an impostor gains nothing by sending one. A wrong MAC,
     * or no MAC on any other status (an unsigned 200 included), is not the agent. Shared with {@link SseEventStream}.
     */
    static boolean isUnsignedRefusal(int status, HttpHeaders headers) {
        return PRE_AUTHENTICATION_STATUSES.contains(status) && headers.allValues(Auth.HEADER_MAC).isEmpty();
    }

    private static String explainPreAuthentication(int status) {
        return switch (status) {
            case 413 -> " (the request body is over the agent's limit)";
            case 431 -> " (the request head is over the agent's limit)";
            case 503 -> " (the agent is busy reading the bodies of requests not yet authenticated; try again)";
            default -> " (the agent could not read the request)";
        };
    }

    private static String explain(int status) {
        return status == 403 ? " (refused by the agent's Host/Origin check)" : "";
    }

    private void verifyPing(Map<String, Object> reply, String clientNonce, String session) throws AgentLostException {
        if (!session.equals(reply.get(Protocol.KEY_SESSION_ID))) {
            throw latch("/ping body reports session " + reply.get(Protocol.KEY_SESSION_ID) + ", its "
                    + Auth.HEADER_SESSION + " header " + session, null);
        }
        Object echoed = reply.get("nonce");
        if (!clientNonce.equals(echoed)) {
            throw latch("/ping did not echo the nonce (sent " + clientNonce + ", got " + echoed + ")", null);
        }
        if (reply.containsKey("clientNonce") && !clientNonce.equals(reply.get("clientNonce"))) {
            throw latch("/ping did not echo the nonce as clientNonce (sent " + clientNonce + ", got "
                    + reply.get("clientNonce") + ")", null);
        }
        Object protocol = reply.get("protocol");
        if (!(protocol instanceof String version) || !major(version).equals(major(Protocol.VERSION))) {
            throw latch("protocol mismatch: agent speaks " + protocol + ", controller speaks " + Protocol.VERSION
                    + " (major versions must match)", null);
        }
        if (endpoint.nonce() != null && !endpoint.nonce().equals(reply.get("agentNonce"))) {
            throw latch("/ping agentNonce " + reply.get("agentNonce") + " is not the expected " + endpoint.nonce()
                    + ": this is not the agent the endpoint describes", null);
        }
        if (endpoint.pid() > 0 && !Long.valueOf(endpoint.pid()).equals(reply.get("pid"))) {
            throw latch("/ping reports pid " + reply.get("pid") + ", expected " + endpoint.pid(), null);
        }
        if (endpoint.startTimeMs() > 0 && !Long.valueOf(endpoint.startTimeMs()).equals(reply.get("startTimeMs"))) {
            throw latch("/ping reports JVM start time " + reply.get("startTimeMs") + ", expected "
                    + endpoint.startTimeMs() + ": the target was restarted", null);
        }
    }

    /** The part before the first dot. */
    static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    private AgentLostException lost(String message, Throwable cause) {
        String text = "agent at " + where() + ": " + message;
        return cause == null ? new AgentLostException(text) : new AgentLostException(text, cause);
    }

    /** Sets the latch and returns the exception to throw. */
    private AgentLostException latch(String message, Throwable cause) {
        markLost(message);
        return lost(message, cause);
    }

    private String where() {
        return LOOPBACK + ":" + endpoint.port() + (endpoint.pid() > 0 ? " (pid " + endpoint.pid() + ")" : "");
    }

    static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return ": " + (flat.length() > 200 ? flat.substring(0, 200) + "..." : flat);
    }

    /** A reply body above the cap. */
    private static final class ReplyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ReplyTooLargeException(long maxBytes) {
            super("the reply exceeds " + maxBytes + " bytes");
        }
    }

    /**
     * Collects a reply body into a byte array, cancelling the exchange as soon as it grows past the cap. Called by the
     * HTTP client serially, as reactive streams require.
     */
    private static final class CappedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long maxBytes;
        private final long declared;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<ByteBuffer> parts = new ArrayList<>();
        private Flow.Subscription subscription;
        private long seen;

        CappedSubscriber(long maxBytes, long declared) {
            this.maxBytes = maxBytes;
            this.declared = declared;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (declared > maxBytes) {
                s.cancel();
                result.completeExceptionally(new ReplyTooLargeException(maxBytes));
                return;
            }
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer b : items) {
                seen += b.remaining();
                if (seen > maxBytes) {
                    subscription.cancel();
                    parts.clear();
                    result.completeExceptionally(new ReplyTooLargeException(maxBytes));
                    return;
                }
                parts.add(b);
            }
        }

        @Override
        public void onError(Throwable t) {
            parts.clear();
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            if (result.isDone()) {
                return;
            }
            byte[] all = new byte[(int) seen];
            int at = 0;
            for (ByteBuffer b : parts) {
                int n = b.remaining();
                b.get(all, at, n);
                at += n;
            }
            parts.clear();
            result.complete(all);
        }
    }
}
