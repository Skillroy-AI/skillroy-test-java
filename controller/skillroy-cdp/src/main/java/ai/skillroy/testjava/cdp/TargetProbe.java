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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The liveness probe of a remote-debugging endpoint (phase4-contracts.md sections 5.1 and 5.3): {@code GET
 * /json/version} and {@code /json/list} with {@link HttpClient}, then {@code Runtime.evaluate("1")} over each page
 * target's {@code webSocketDebuggerUrl} with {@link WebSocket}. Every page target is probed on a virtual thread of its
 * own, all in parallel, each under {@link CdpOptions#probeDeadlinePerTarget()}; a target that does not answer within
 * it is reported unresponsive (frozen), never awaited, so the whole probe ends within about one deadline plus the two
 * HTTP requests, whatever the targets do: at most three deadlines (the two requests, then the targets) and the 500 ms
 * the gathering of the per-target results may add.
 *
 * <p>Protocol details: one WebSocket per target, one request with the message id {@value #PROBE_ID} ({@code
 * {"id":1,"method":"Runtime.evaluate","params":{"expression":"1","returnByValue":true}}}); a text message is assembled
 * from its frames and parsed with the protocol module's {@link Json}; the first message whose {@code id} is 1 is the
 * answer (an {@code error} answer still proves that the target answers and is reported in {@link Target#error()});
 * every other message is ignored; the socket is aborted when the answer arrives or the deadline passes. Targets of
 * another type than {@code page} are listed as reported by {@code /json/list} and not probed ({@code responsive},
 * {@code millis} 0). A page target without {@code webSocketDebuggerUrl} gets {@code ws://<host>:<port>/devtools/page/
 * <id>}.
 *
 * <p>The answers are bounded before they are trusted (security fix S44, phase4-contracts.md 5.1): {@code /json/version}
 * is read through a bound of {@value #MAX_VERSION_BYTES} bytes and {@code /json/list} through one of
 * {@value #MAX_LIST_BYTES} (a {@code Content-Length} over the bound is refused before the body is read, and the body
 * read stops at the bound; either is {@code PROTOCOL}); a list of more than {@value #MAX_TARGETS} targets is {@code
 * PROTOCOL}; a target's WebSocket is opened only at the endpoint's own authority: a {@code webSocketDebuggerUrl} whose
 * scheme is not {@code ws} or whose authority is not the endpoint's is replaced by {@code ws://<host>:<port>/devtools/
 * page/<id>}, so that a list never makes the probe dial another host. A target's URL and title and the browser version
 * are cut at {@link WebLib#MAX_URL_CHARS} (S43). The browser's own WebSocket, which the attach dials, is taken from
 * {@code /json/version} by the same rule: its path at the endpoint's own authority ({@link #browserWebSocketUrl()},
 * security fix P2-L4-F1), so that the attach neither reads {@code /json/version} again nor dials a host the answer
 * names.
 *
 * <p>Each {@code /json} request is bounded by the deadline as its caller waits for it; the request's own timeout is
 * {@value #REQUEST_TIMEOUT_MARGIN_MS} ms longer (it only releases the exchange), and a request that times out either
 * way is reported as "no answer within &lt;deadline&gt; ms", so the message does not depend on which timer fired.
 *
 * <p>Immutable once returned; safe to share between threads.
 */
public final class TargetProbe {

    /** The message id of the probe's one request on each target socket. */
    public static final long PROBE_ID = 1;

    /** The request every page target must answer. */
    static final String REQUEST =
            "{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":\"1\",\"returnByValue\":true}}";

    /** How much longer than the caller's bound a {@code /json} request's own timeout is. */
    static final long REQUEST_TIMEOUT_MARGIN_MS = 250;

    /** The largest WebSocket message the probe keeps (anything longer cannot be the answer it waits for). */
    private static final int MAX_MESSAGE_CHARS = 1 << 20;

    /** Waiting for the per-target threads may take this much longer than the deadline before they are abandoned. */
    private static final long GATHER_GRACE_MS = 500;

    /** The most bytes of {@code /json/version} read (S44). */
    static final int MAX_VERSION_BYTES = 64 * 1024;

    /** The most bytes of {@code /json/list} read (S44). */
    static final int MAX_LIST_BYTES = 4 * 1024 * 1024;

    /** The most targets a {@code /json/list} may name (S44). */
    static final int MAX_TARGETS = 1000;

    /**
     * One target of {@code /json/list} and what the probe found.
     *
     * @param id the target id (equal to the main frame id of a page)
     * @param type {@code page}, {@code iframe}, {@code service_worker}, ...
     * @param url the target's URL
     * @param title the target's title
     * @param webSocketDebuggerUrl the target's own DevTools WebSocket
     * @param responsive true if a page target answered the probe within the deadline (always true for other types)
     * @param millis how long the probe of this target took; 0 when it was not probed
     * @param error null, or why the target is unresponsive, or the error a responsive target answered
     */
    public record Target(String id, String type, String url, String title, String webSocketDebuggerUrl,
                         boolean responsive, long millis, String error) {

        public Target {
            id = id == null ? "" : id;
            type = type == null ? "" : type;
            url = url == null ? "" : url;
            title = title == null ? "" : title;
            webSocketDebuggerUrl = webSocketDebuggerUrl == null ? "" : webSocketDebuggerUrl;
        }

        /** True for a target of type {@code page}, the only type the probe evaluates in. */
        public boolean isPage() {
            return "page".equals(type);
        }

        /** {@code {id, url, title}}: how a frozen target is named in a failure detail. */
        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("url", url);
            m.put("title", title);
            return m;
        }

        Target withResult(boolean isResponsive, long ms, String why) {
            return new Target(id, type, url, title, webSocketDebuggerUrl, isResponsive, ms, why);
        }
    }

    /** The path of a browser's own DevTools WebSocket the attach accepts (P2-L4-F1): {@code /devtools/browser/<id>}. */
    private static final java.util.regex.Pattern BROWSER_PATH =
            java.util.regex.Pattern.compile("/devtools/browser/[A-Za-z0-9._~-]{1,128}");

    private final CdpEndpoint endpoint;
    private final String browserVersion;
    private final String protocolVersion;
    private final String browserWebSocketUrl;
    private final List<Target> targets;
    private final long millis;

    TargetProbe(CdpEndpoint endpoint, String browserVersion, String protocolVersion, List<Target> targets,
                long millis) {
        this(endpoint, browserVersion, protocolVersion, null, targets, millis);
    }

    TargetProbe(CdpEndpoint endpoint, String browserVersion, String protocolVersion, String browserWebSocketUrl,
                List<Target> targets, long millis) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.browserVersion = browserVersion == null ? "" : WebLib.cut(browserVersion, WebLib.MAX_URL_CHARS);
        this.protocolVersion = protocolVersion == null ? "" : WebLib.cut(protocolVersion, WebLib.MAX_URL_CHARS);
        this.browserWebSocketUrl = browserWebSocketUrl;
        this.targets = List.copyOf(targets);
        this.millis = millis;
    }

    /**
     * Probes the endpoint.
     *
     * @throws CdpException {@code UNREACHABLE} when {@code /json/version} fails in any way (refused, timed out, not
     *     HTTP 200, not a JSON object) or {@code /json/list} cannot be reached; {@code PROTOCOL} when {@code
     *     /json/list} answers something other than a JSON array
     */
    public static TargetProbe run(CdpEndpoint endpoint, CdpOptions options) throws CdpException {
        return run(endpoint, options, Set.of());
    }

    /**
     * Probes an endpoint that must serve at least one of {@code targetIds} (the CDP target ids the agent reports for
     * the target's browsers): when that set is not empty and {@code /json/list} names none of them, the endpoint is
     * another browser's (a stale {@code DevToolsActivePort} file, a port another process holds), so no page target is
     * probed and the answer is null. An empty set checks nothing ({@link #run(CdpEndpoint, CdpOptions)}).
     *
     * @return the probe, or null when the endpoint serves none of {@code targetIds}
     * @throws CdpException as {@link #run(CdpEndpoint, CdpOptions)}
     */
    static TargetProbe run(CdpEndpoint endpoint, CdpOptions options, Set<String> targetIds) throws CdpException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(targetIds, "targetIds");
        long start = System.nanoTime();
        long deadlineMs = options.probeDeadlinePerTarget().toMillis();
        ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(options.probeDeadlinePerTarget())
                .executor(threads)
                .build();
        try {
            Object version;
            try {
                version = getJson(http, endpoint, "/json/version", deadlineMs, MAX_VERSION_BYTES);
            } catch (ProbeHttpException e) {
                if (e.oversized) {
                    throw new CdpException(CdpException.Kind.PROTOCOL, "the DevTools endpoint at "
                            + endpoint.httpUri() + "/json/version: " + e.getMessage(), null, e.getCause());
                }
                throw new CdpException(CdpException.Kind.UNREACHABLE, "no DevTools endpoint answers at "
                        + endpoint.httpUri() + "/json/version: " + e.getMessage(), null, e.getCause());
            }
            if (!(version instanceof Map<?, ?> versionMap)) {
                throw new CdpException(CdpException.Kind.UNREACHABLE, "no DevTools endpoint answers at "
                        + endpoint.httpUri() + "/json/version: the answer is not a JSON object", null);
            }
            Object list;
            try {
                list = getJson(http, endpoint, "/json/list", deadlineMs, MAX_LIST_BYTES);
            } catch (ProbeHttpException e) {
                throw new CdpException(e.unreachable ? CdpException.Kind.UNREACHABLE : CdpException.Kind.PROTOCOL,
                        "cannot list the targets at " + endpoint.httpUri() + "/json/list: " + e.getMessage(), null,
                        e.getCause());
            }
            if (!(list instanceof List<?> entries)) {
                throw new CdpException(CdpException.Kind.PROTOCOL, "cannot list the targets at " + endpoint.httpUri()
                        + "/json/list: the answer is not a JSON array", null);
            }
            if (entries.size() > MAX_TARGETS) {
                throw new CdpException(CdpException.Kind.PROTOCOL, "cannot list the targets at " + endpoint.httpUri()
                        + "/json/list: it names " + entries.size() + " targets, more than " + MAX_TARGETS, null);
            }
            List<Target> listed = parseTargets(endpoint, entries);
            if (!servesOneOf(listed, targetIds)) {
                return null;
            }
            List<Target> probed = probeAll(http, threads, listed, deadlineMs);
            return new TargetProbe(endpoint, text(versionMap.get("Browser")),
                    text(versionMap.get("Protocol-Version")),
                    browserWebSocket(text(versionMap.get("webSocketDebuggerUrl")), endpoint), probed,
                    elapsedMs(start));
        } finally {
            http.shutdownNow();
            threads.shutdownNow();
        }
    }

    /** The endpoint that was probed. */
    public CdpEndpoint endpoint() {
        return endpoint;
    }

    /** {@code Browser} of {@code /json/version} (for example {@code Chrome/152.0.7977.83}); "" when absent. */
    public String browserVersion() {
        return browserVersion;
    }

    /** {@code Protocol-Version} of {@code /json/version}; "" when absent. */
    public String protocolVersion() {
        return protocolVersion;
    }

    /**
     * The browser's own DevTools WebSocket, which {@link CdpAttach} dials (security fix P2-L4-F1): the path of
     * {@code /json/version}'s {@code webSocketDebuggerUrl} at the endpoint's own authority,
     * {@code ws://<host:port>/devtools/browser/<id>}, whatever scheme, host, port or user information the answer
     * named; null when the answer named none, or one whose path is not {@code /devtools/browser/<id>} (an opaque URL
     * such as {@code ws+unix:} among them), and the attach is then refused.
     */
    public String browserWebSocketUrl() {
        return browserWebSocketUrl;
    }

    /** Every target of {@code /json/list} in its order, with the probe's findings; unmodifiable. */
    public List<Target> targets() {
        return targets;
    }

    /** The page targets that did not answer within the deadline (frozen); unmodifiable, empty when all answered. */
    public List<Target> unresponsive() {
        List<Target> out = new ArrayList<>();
        for (Target t : targets) {
            if (t.isPage() && !t.responsive()) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The page targets; unmodifiable. */
    public List<Target> pages() {
        List<Target> out = new ArrayList<>();
        for (Target t : targets) {
            if (t.isPage()) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** How long the whole probe took. */
    public long millis() {
        return millis;
    }

    @Override
    public String toString() {
        return "TargetProbe[" + endpoint + ", " + browserVersion + ", " + targets.size() + " targets, "
                + unresponsive().size() + " unresponsive, " + millis + " ms]";
    }

    private static List<Target> parseTargets(CdpEndpoint endpoint, List<?> entries) {
        List<Target> out = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> m)) {
                continue;
            }
            String id = text(m.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            String ws = text(m.get("webSocketDebuggerUrl"));
            if (!atEndpoint(ws, endpoint)) {
                ws = "ws://" + endpoint.authority() + "/devtools/page/" + id;
            }
            out.add(new Target(id, WebLib.cut(text(m.get("type")), WebLib.MAX_URL_CHARS),
                    WebLib.cut(text(m.get("url")), WebLib.MAX_URL_CHARS),
                    WebLib.cut(text(m.get("title")), WebLib.MAX_URL_CHARS), ws, true, 0, null));
        }
        return out;
    }

    /**
     * True when a listed {@code webSocketDebuggerUrl} is a {@code ws} URL at the endpoint's own authority (S44): the
     * probe never dials a host the list names.
     */
    static boolean atEndpoint(String ws, CdpEndpoint endpoint) {
        if (ws == null || ws.isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(ws);
            return "ws".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && endpoint.authority().equalsIgnoreCase(uri.getRawAuthority());
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /**
     * The browser WebSocket of a {@code /json/version} answer, re-pointed at the endpoint's own authority (see
     * {@link #browserWebSocketUrl()}); null when it cannot be used.
     */
    static String browserWebSocket(String listed, CdpEndpoint endpoint) {
        if (listed == null || listed.isEmpty() || listed.length() > WebLib.MAX_URL_CHARS) {
            return null;
        }
        try {
            URI uri = new URI(listed);
            String path = uri.getRawPath();
            if (uri.isOpaque() || path == null || !BROWSER_PATH.matcher(path).matches()) {
                return null;
            }
            return "ws://" + endpoint.authority() + path;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** True when {@code targetIds} is empty or a listed target (of any type) has one of those ids. */
    static boolean servesOneOf(List<Target> listed, Set<String> targetIds) {
        if (targetIds.isEmpty()) {
            return true;
        }
        for (Target t : listed) {
            if (targetIds.contains(t.id())) {
                return true;
            }
        }
        return false;
    }

    private static List<Target> probeAll(HttpClient http, ExecutorService threads, List<Target> listed,
                                         long deadlineMs) {
        long start = System.nanoTime();
        List<Future<Target>> futures = new ArrayList<>();
        for (Target t : listed) {
            futures.add(t.isPage() ? threads.submit(() -> probeOne(http, t, deadlineMs)) : null);
        }
        List<Target> out = new ArrayList<>();
        long gatherUntil = start + TimeUnit.MILLISECONDS.toNanos(deadlineMs + GATHER_GRACE_MS);
        for (int i = 0; i < listed.size(); i++) {
            Target t = listed.get(i);
            Future<Target> f = futures.get(i);
            if (f == null) {
                out.add(t);
                continue;
            }
            try {
                out.add(f.get(Math.max(0, gatherUntil - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                f.cancel(true);
                out.add(t.withResult(false, elapsedMs(start), "did not answer Runtime.evaluate within " + deadlineMs
                        + " ms"));
            } catch (ExecutionException e) {
                out.add(t.withResult(false, elapsedMs(start), "the probe failed: " + describe(e.getCause())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                out.add(t.withResult(false, elapsedMs(start), "the probe was interrupted"));
            }
        }
        return out;
    }

    /** Probes one page target; never takes much longer than the deadline and never throws. */
    static Target probeOne(HttpClient http, Target t, long deadlineMs) {
        long start = System.nanoTime();
        long until = start + TimeUnit.MILLISECONDS.toNanos(deadlineMs);
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        WebSocket ws = null;
        String stage = "open its WebSocket";
        try {
            URI uri = URI.create(t.webSocketDebuggerUrl());
            ws = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofNanos(Math.max(1_000_000, until - System.nanoTime())))
                    .buildAsync(uri, new Listener(answer))
                    .get(remainingNanos(until), TimeUnit.NANOSECONDS);
            stage = "accept the request";
            ws.sendText(REQUEST, true).get(remainingNanos(until), TimeUnit.NANOSECONDS);
            stage = "answer Runtime.evaluate";
            Map<String, Object> reply = answer.get(remainingNanos(until), TimeUnit.NANOSECONDS);
            Object error = reply.get("error");
            String why = null;
            if (error instanceof Map<?, ?> e) {
                why = "Runtime.evaluate answered an error: " + text(e.get("message"));
            } else if (error != null) {
                why = "Runtime.evaluate answered an error: " + error;
            }
            return t.withResult(true, elapsedMs(start), why);
        } catch (TimeoutException e) {
            return t.withResult(false, elapsedMs(start), "did not " + stage + " within " + deadlineMs + " ms");
        } catch (ExecutionException e) {
            return t.withResult(false, elapsedMs(start), "did not " + stage + ": " + describe(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return t.withResult(false, elapsedMs(start), "the probe was interrupted");
        } catch (IllegalArgumentException e) {
            return t.withResult(false, elapsedMs(start), "the webSocketDebuggerUrl cannot be used: "
                    + e.getMessage());
        } finally {
            if (ws != null) {
                ws.abort();
            }
        }
    }

    /**
     * {@code GET <path>} within {@code deadlineMs}: the caller's wait is the bound; the request's own timeout is a
     * margin longer, so that it only releases the exchange, and either timeout gives the same message.
     */
    private static Object getJson(HttpClient http, CdpEndpoint endpoint, String path, long deadlineMs, int maxBytes)
            throws ProbeHttpException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.httpUri() + path))
                .timeout(Duration.ofMillis(deadlineMs + REQUEST_TIMEOUT_MARGIN_MS))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.sendAsync(request, capped(maxBytes)).get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ProbeHttpException(true, noAnswer(deadlineMs), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause instanceof java.io.IOException && cause.getCause() instanceof AnswerTooLarge) {
                cause = cause.getCause();
            }
            if (cause instanceof AnswerTooLarge) {
                throw new ProbeHttpException(false, cause.getMessage(), cause, true);
            }
            throw new ProbeHttpException(true, requestFailure(cause, deadlineMs), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProbeHttpException(true, "interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new ProbeHttpException(false, "HTTP " + response.statusCode(), null);
        }
        try {
            return Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (JsonException e) {
            throw new ProbeHttpException(false, "the answer is not JSON (" + e.getMessage() + ")", e);
        }
    }

    /**
     * A body handler that reads at most {@code maxBytes} (S44, the shape of the controller's agent client): a declared
     * {@code Content-Length} over the bound is refused before the body is read, and the read stops (the subscription
     * cancelled) as soon as the bytes seen pass the bound, one buffer requested at a time; the failure is
     * {@link AnswerTooLarge}.
     */
    static HttpResponse.BodyHandler<byte[]> capped(int maxBytes) {
        return info -> new CappedBody(maxBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1L));
    }

    /** An answer of the endpoint over its bound. */
    static final class AnswerTooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        AnswerTooLarge(long maxBytes) {
            super("the answer exceeds " + maxBytes + " bytes");
        }
    }

    /** See {@link #capped}. */
    static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long maxBytes;
        private final long declared;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<ByteBuffer> parts = new ArrayList<>();
        private Flow.Subscription subscription;
        private long seen;

        CappedBody(long maxBytes, long declared) {
            this.maxBytes = maxBytes;
            this.declared = declared;
        }

        /** The bytes seen so far (tests). */
        synchronized long seen() {
            return seen;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (declared > maxBytes) {
                s.cancel();
                result.completeExceptionally(new AnswerTooLarge(maxBytes));
                return;
            }
            s.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer b : items) {
                seen += b.remaining();
                if (seen > maxBytes) {
                    subscription.cancel();
                    parts.clear();
                    result.completeExceptionally(new AnswerTooLarge(maxBytes));
                    return;
                }
                parts.add(b);
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onError(Throwable t) {
            parts.clear();
            result.completeExceptionally(t);
        }

        @Override
        public synchronized void onComplete() {
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

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String noAnswer(long deadlineMs) {
        return "no answer within " + deadlineMs + " ms";
    }

    /**
     * Why a {@code /json} request failed: a timeout of the HTTP client (its connect or request timer) is reported as
     * {@link #noAnswer}, as the caller's own bound is, whichever fired first; anything else by its type and message.
     */
    static String requestFailure(Throwable cause, long deadlineMs) {
        return cause instanceof HttpTimeoutException ? noAnswer(deadlineMs) : describe(cause);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static long remainingNanos(long until) throws TimeoutException {
        long left = until - System.nanoTime();
        if (left <= 0) {
            throw new TimeoutException();
        }
        return left;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * One HTTP request of the probe failed; {@code unreachable} distinguishes I/O from a wrong answer, and
     * {@code oversized} an answer over its bound (S44: {@code PROTOCOL} for either request).
     */
    private static final class ProbeHttpException extends Exception {

        private static final long serialVersionUID = 1L;

        final boolean unreachable;
        final boolean oversized;

        ProbeHttpException(boolean unreachable, String message, Throwable cause) {
            this(unreachable, message, cause, false);
        }

        ProbeHttpException(boolean unreachable, String message, Throwable cause, boolean oversized) {
            super(message, cause);
            this.unreachable = unreachable;
            this.oversized = oversized;
        }
    }

    /** Collects the frames of each text message and completes {@code answer} with the message whose id is 1. */
    private static final class Listener implements WebSocket.Listener {

        private final CompletableFuture<Map<String, Object>> answer;
        private final StringBuilder text = new StringBuilder();
        private boolean overflow;

        Listener(CompletableFuture<Map<String, Object>> answer) {
            this.answer = answer;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!overflow) {
                if (text.length() + data.length() > MAX_MESSAGE_CHARS) {
                    overflow = true;
                    text.setLength(0);
                } else {
                    text.append(data);
                }
            }
            if (last) {
                if (!overflow) {
                    accept(text.toString());
                }
                text.setLength(0);
                overflow = false;
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            answer.completeExceptionally(new IOException("the target closed its WebSocket (" + statusCode
                    + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")"));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            answer.completeExceptionally(error);
        }

        private void accept(String message) {
            try {
                Object parsed = Json.parse(message);
                if (parsed instanceof Map<?, ?> m && Long.valueOf(PROBE_ID).equals(m.get("id"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> reply = (Map<String, Object>) m;
                    answer.complete(reply);
                }
            } catch (JsonException ignored) {
                // not the answer; keep waiting until the deadline
            }
        }
    }
}
