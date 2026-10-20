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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Playwright attached to a running Chromium over CDP (phase4-contracts.md sections 5.1 and 5.4).
 *
 * <p>{@link #connect} refuses a probe that found a frozen page target ({@code FROZEN_TARGETS}, without starting
 * Playwright: the recovery of {@link CdpWebPath} needs the agent), then creates Playwright through the module's one
 * factory and runs {@code connectOverCDP} on a platform worker thread under {@link CdpOptions#connectDeadline()}. When
 * the deadline passes, the Playwright instance is closed, which ends the driver process and with it the hung connect
 * (browser research §F9), and the call ends with {@code TIMEOUT}; the driver's own start counts against the
 * deadline. With a probe, {@code connectOverCDP} is given the browser WebSocket the probe read, re-pointed at the
 * endpoint's own authority ({@link TargetProbe#browserWebSocketUrl()}, security fix P2-L4-F1), never the endpoint's
 * {@code http} URL, so that the driver neither reads {@code /json/version} itself nor dials a host it names; the
 * driver still follows an HTTP redirect of the WebSocket handshake (Playwright 1.63 has no option to refuse one), the
 * residual recorded in phase4-contracts.md 5.1. Playwright's {@code noDefaults} and {@code isLocal} come from the
 * options; pages come from {@code
 * contexts().get(0).pages()}: the attach never calls {@code newContext()} or {@code newPage()} (browser research §C4).
 *
 * <p>The Playwright line (Phase 8, phase8-contracts.md 5.3, D552 to D554): each option's setter is called only when
 * the line has its field ({@link ConnectSupport}): without {@code isLocal} the attach connects without it; without
 * {@code noDefaults} it is refused with {@code PROTOCOL} before anything starts unless {@link
 * CdpOptions#allowDefaultOverrides()} (then it connects without it and logs the accepted overrides at WARNING); without
 * {@code artifactsDir} no directory is passed (and logged when one was asked for). A {@link LinkageError} (or any other
 * {@link Error} that is not a {@link VirtualMachineError}) from the driver's start or the connect closes the pending
 * Playwright and ends the attach's thread before {@code PROTOCOL} is thrown, so that an incompatible Playwright never
 * leaves a driver process or a {@code skillroy-cdp-playwright} thread behind.
 *
 * <p>Threads: Playwright Java is not thread-safe, so every Playwright call of an attach runs on its own platform
 * thread ({@code skillroy-cdp-playwright}); {@link #pageFor}, {@link #pagesByTargetId} and {@link #tracing()} hand
 * their work to it with a bound. {@link #browser()} and {@link #context()} expose the Playwright objects for callers
 * that own the attach alone (a test, a soak); while a {@link CdpWebPath} uses the attach, use them only through the
 * path. Methods are thread-safe.
 *
 * <p>Page mapping: the target id of a page is what {@code Target.getTargetInfo} reports through a {@code CDPSession}
 * of that page (falling back to the main frame id of {@code Page.getFrameTree}, which equals it); the mapping is cached
 * per page and refreshed whenever the page list differs from the cached one: closed pages leave the cache, new pages
 * are asked once (a page whose id could not be read is asked again at most every 5 s).
 */
public final class CdpAttach implements AutoCloseable {

    /** How long closing waits for the Playwright driver before it carries on (the close goes on in the background). */
    static final long CLOSE_GRACE_MS = 5_000;

    /** The bound of the page-mapping queries ({@link #pageFor}, {@link #pagesByTargetId}). */
    static final long QUERY_BUDGET_MS = 10_000;

    /** A page whose target id could not be read is asked again after this long, not on every refresh. */
    private static final long UNMAPPED_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final System.Logger LOG = System.getLogger(CdpAttach.class.getName());

    private final TargetProbe probe;
    private final CdpOptions options;
    private final PwThread pw;
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final long connectMillis;
    private final CdpTracing tracing;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Page to target id; touched only on the Playwright thread. */
    private final Map<Page, String> targetIds = new IdentityHashMap<>();
    /** One CDP session per page, for the target id and the frame tree; touched only on the Playwright thread. */
    private final Map<Page, CDPSession> sessions = new IdentityHashMap<>();
    /** When the target id of a page could not be read (nanoTime); touched only on the Playwright thread. */
    private final Map<Page, Long> unmapped = new IdentityHashMap<>();

    CdpAttach(TargetProbe probe, CdpOptions options, PwThread pw, Playwright playwright, Browser browser,
              BrowserContext context, long connectMillis) {
        this.probe = probe;
        this.options = Objects.requireNonNull(options, "options");
        this.pw = Objects.requireNonNull(pw, "pw");
        this.playwright = playwright;
        this.browser = browser;
        this.context = Objects.requireNonNull(context, "context");
        this.connectMillis = connectMillis;
        this.tracing = new CdpTracing(this, options.artifactsDir());
    }

    /**
     * Attaches to the browser at {@code endpoint}.
     *
     * @param probe the probe made just before, whose {@link TargetProbe#browserWebSocketUrl()} the attach dials
     *     (security fix P2-L4-F1); or null to attach without one: the frozen-target check is then skipped, only the
     *     deadline protects the caller, and Playwright reads {@code /json/version} itself, unbounded, and dials the
     *     WebSocket it names (tests and the soak only; {@link CdpWebPath} always probes first)
     * @throws CdpException {@code FROZEN_TARGETS} when the probe found a page target that did not answer, whatever
     *     {@link CdpOptions#frozenTargets()} says (nothing is started); {@code PROTOCOL} when the probe found no usable
     *     browser WebSocket (nothing is started); {@code TIMEOUT} when the deadline passed (the driver was closed);
     *     {@code UNREACHABLE} when Playwright could not reach the endpoint; {@code PROTOCOL} for every other failure
     *     of the driver or of the attach
     */
    public static CdpAttach connect(CdpEndpoint endpoint, CdpOptions options, TargetProbe probe) throws CdpException {
        return connect(endpoint, options, probe, PlaywrightFactory.DEFAULT);
    }

    static CdpAttach connect(CdpEndpoint endpoint, CdpOptions options, TargetProbe probe, PlaywrightFactory factory)
            throws CdpException {
        return connect(endpoint, options, probe, factory, ConnectSupport.detected());
    }

    /**
     * {@link #connect(CdpEndpoint, CdpOptions, TargetProbe)} with a given factory and a given Playwright line (the
     * tests' seams).
     *
     * @throws CdpException also {@code PROTOCOL} when the line has no {@code noDefaults} and the options do not
     *     {@link CdpOptions#allowDefaultOverrides() allow the overrides} (nothing is started), and when the driver's
     *     start or the connect threw a {@link LinkageError} (the pending Playwright closed, the thread ended)
     */
    static CdpAttach connect(CdpEndpoint endpoint, CdpOptions options, TargetProbe probe, PlaywrightFactory factory,
                             ConnectSupport support) throws CdpException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(support, "support");
        if (probe != null && !probe.unresponsive().isEmpty()) {
            throw frozen(probe.unresponsive());
        }
        if (!support.noDefaults() && !options.allowDefaultOverrides()) {
            throw new CdpException(CdpException.Kind.PROTOCOL, support.noDefaultsRefusal() + "; nothing was attached",
                    targetsOf(probe));
        }
        // P2-L4-F1: with a probe, the attach dials the browser WebSocket the probe read, at the endpoint's own
        // authority, so that the driver reads no /json/version of its own (unbounded) and dials no host it names.
        String url = probe == null ? endpoint.httpUri().toString() : probe.browserWebSocketUrl();
        if (url == null) {
            throw new CdpException(CdpException.Kind.PROTOCOL, "the DevTools endpoint at " + endpoint.httpUri()
                    + "/json/version names no browser WebSocket at /devtools/browser/<id>; nothing was attached",
                    targetsOf(probe));
        }
        long deadlineMs = options.connectDeadline().toMillis();
        long start = System.nanoTime();
        PwThread pw = new PwThread("skillroy-cdp-playwright");
        Pending pending = new Pending();
        try {
            Connected c = pw.call(() -> open(endpoint, url, options, factory, support, pending, start, deadlineMs),
                    deadlineMs);
            return new CdpAttach(probe, options, pw, c.playwright(), c.browser(), c.context(), elapsedMs(start));
        } catch (TimeoutException | InterruptedException e) {
            closeQuietly(pending.abandon(), CLOSE_GRACE_MS);
            pw.shutdownNow();
            throw new CdpException(CdpException.Kind.TIMEOUT, "connectOverCDP to " + endpoint
                    + " did not complete within " + deadlineMs + " ms; the Playwright driver was closed",
                    targetsOf(probe), e);
        } catch (PlaywrightFactory.Forbidden e) {
            pw.shutdownNow();
            throw e;
        } catch (Exception e) {
            closeQuietly(pending.abandon(), CLOSE_GRACE_MS);
            pw.shutdownNow();
            throw classify(endpoint, e, probe);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Error e) {
            // D554: an incompatible Playwright (NoSuchMethodError, NoClassDefFoundError, ...) never leaves a driver
            // process or the attach's thread behind.
            closeQuietly(pending.abandon(), CLOSE_GRACE_MS);
            pw.shutdownNow();
            throw new CdpException(CdpException.Kind.PROTOCOL, "the Playwright on the class path ("
                    + (support.version() == null ? "unknown" : support.version()) + ") lacks " + firstLine(
                    e.getClass().getName() + ": " + e.getMessage()) + "; nothing was attached", targetsOf(probe), e);
        }
    }

    /** The probe the attach was made after, or null. */
    public TargetProbe probe() {
        return probe;
    }

    /** The options of the attach. */
    public CdpOptions options() {
        return options;
    }

    /** The attached browser (see the class comment on threads). */
    public Browser browser() {
        return browser;
    }

    /** {@code contexts().get(0)} of the attached browser (see the class comment on threads). */
    public BrowserContext context() {
        return context;
    }

    /**
     * The page whose CDP target id is {@code targetId}, or null when none (or when the mapping could not be read
     * within 10 s, or the attach is closed).
     */
    public Page pageFor(String targetId) {
        if (targetId == null) {
            return null;
        }
        return pagesByTargetId().get(targetId);
    }

    /**
     * Every open page of the context by its CDP target id, in the context's page order; empty when the mapping could
     * not be read within 10 s or the attach is closed. Unmodifiable.
     */
    public Map<String, Page> pagesByTargetId() {
        if (closed.get()) {
            return Map.of();
        }
        try {
            return run(() -> java.util.Collections.unmodifiableMap(pagesOnPw()), QUERY_BUDGET_MS);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "the page mapping could not be read: " + e);
            return Map.of();
        }
    }

    /**
     * Context tracing into {@link CdpOptions#artifactsDir()} (browser research §F18). A trace holds page content
     * (DOM snapshots with the values of input fields, screenshots, the source of evaluated scripts) and does not pass
     * through the run's secret mask. Playwright appends the live trace to files in that directory from {@code start}
     * on and never deletes them (see {@link CdpOptions#artifactsDir()}).
     */
    public CdpTracing tracing() {
        return tracing;
    }

    /** How long Playwright's start and {@code connectOverCDP} took. */
    public long connectMillis() {
        return connectMillis;
    }

    /** True once {@link #close()} ran. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Closes Playwright, which ends the driver and the CDP connection; the browser and its pages stay as they are.
     * Idempotent; never throws; waits at most 5 s for the driver.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(playwright, CLOSE_GRACE_MS);
        pw.shutdownNow();
    }

    /** Runs {@code body} on the Playwright thread within {@code budgetMs}; see {@link PwThread#call}. */
    <T> T run(Callable<T> body, long budgetMs) throws Exception {
        if (closed.get()) {
            throw new PwThread.Busy("the CDP attach is closed");
        }
        return pw.call(body, budgetMs);
    }

    /** True when an abandoned operation still runs on the Playwright thread or the attach is closed. */
    boolean busy() {
        return closed.get() || pw.busy();
    }

    /** The page mapping, refreshed; call only on the Playwright thread. */
    Map<String, Page> pagesOnPw() {
        List<Page> current = context.pages();
        targetIds.keySet().removeIf(p -> !containsIdentity(current, p) || p.isClosed());
        unmapped.keySet().removeIf(p -> !containsIdentity(current, p) || p.isClosed());
        sessions.entrySet().removeIf(e -> {
            Page p = e.getKey();
            if (containsIdentity(current, p) && !p.isClosed()) {
                return false;
            }
            try {
                e.getValue().detach();
            } catch (RuntimeException ignored) {
                // the page is gone; so is its session
            }
            return true;
        });
        Map<String, Page> out = new LinkedHashMap<>();
        for (Page page : current) {
            if (page.isClosed()) {
                continue;
            }
            String id = targetIds.get(page);
            Long failedAt = unmapped.get(page);
            if (id == null && (failedAt == null || System.nanoTime() - failedAt > UNMAPPED_RETRY_NANOS)) {
                id = targetIdOf(page);
                if (id != null) {
                    targetIds.put(page, id);
                    unmapped.remove(page);
                } else {
                    unmapped.put(page, System.nanoTime());
                }
            }
            if (id != null) {
                out.putIfAbsent(id, page);
            }
        }
        return out;
    }

    /** The cached target id of a page, or null; call only on the Playwright thread (no protocol call is made). */
    String cachedTargetId(Page page) {
        return targetIds.get(page);
    }

    /** This page's CDP session, created on first use; call only on the Playwright thread. */
    CDPSession sessionOnPw(Page page) {
        CDPSession session = sessions.get(page);
        if (session == null) {
            session = context.newCDPSession(page);
            sessions.put(page, session);
        }
        return session;
    }

    /** Sends a protocol method without parameters on a session and returns the result as JSON-normal Java values. */
    static Map<String, Object> send(CDPSession session, String method) {
        JsonObject result = session.send(method);
        return result == null ? Map.of() : Json.parseObject(result.toString());
    }

    /** {@link #send(CDPSession, String)} with parameters given as JSON-normal Java values. */
    static Map<String, Object> send(CDPSession session, String method, Map<String, Object> params) {
        JsonObject args = JsonParser.parseString(Json.write(params)).getAsJsonObject();
        JsonObject result = session.send(method, args);
        return result == null ? Map.of() : Json.parseObject(result.toString());
    }

    private String targetIdOf(Page page) {
        try {
            Map<String, Object> info = send(sessionOnPw(page), "Target.getTargetInfo");
            if (info.get("targetInfo") instanceof Map<?, ?> ti && ti.get("targetId") instanceof String id
                    && !id.isEmpty()) {
                return id;
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Target.getTargetInfo failed for " + page.url() + ": " + e);
        }
        try {
            Map<String, Object> tree = send(sessionOnPw(page), "Page.getFrameTree");
            if (tree.get("frameTree") instanceof Map<?, ?> ft && ft.get("frame") instanceof Map<?, ?> f
                    && f.get("id") instanceof String id && !id.isEmpty()) {
                return id;
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Page.getFrameTree failed for " + page.url() + ": " + e);
        }
        return null;
    }

    private static Connected open(CdpEndpoint endpoint, String url, CdpOptions options, PlaywrightFactory factory,
                                  ConnectSupport support, Pending pending, long start, long deadlineMs) {
        Playwright created = factory.create(PlaywrightFactory.environment());
        if (!pending.created(created)) {
            closeQuietly(created, CLOSE_GRACE_MS);
            throw new CancellationException("the connect deadline passed while the Playwright driver started");
        }
        long left = Math.max(1, deadlineMs - elapsedMs(start));
        // D552: a setter only where the line has its field, so that an older line never links the missing method.
        BrowserType.ConnectOverCDPOptions connect = new BrowserType.ConnectOverCDPOptions().setTimeout(left);
        if (support.noDefaults()) {
            connect.setNoDefaults(options.noDefaults());
        } else {
            LOG.log(System.Logger.Level.WARNING, "the Playwright on the class path (" + support.version() + ") has no"
                    + " noDefaults: its download, focus and media overrides apply to the application's browser"
                    + " context at " + endpoint + " (accepted by " + ConnectSupport.ALLOW_PROPERTY + ")");
        }
        if (support.isLocal()) {
            connect.setIsLocal(options.isLocal());
        }
        if (options.artifactsDir() != null) {
            if (support.artifactsDir()) {
                connect.setArtifactsDir(options.artifactsDir());
            } else {
                LOG.log(System.Logger.Level.WARNING, "the Playwright on the class path (" + support.version()
                        + ") has no artifactsDir: no trace directory is passed to it");
            }
        }
        Browser attached = created.chromium().connectOverCDP(url, connect);
        List<BrowserContext> contexts = attached.contexts();
        if (contexts.isEmpty()) {
            throw new PlaywrightException("the browser at " + endpoint + " has no browser context to attach to");
        }
        return new Connected(created, attached, contexts.get(0));
    }

    static CdpException frozen(List<TargetProbe.Target> frozen) {
        StringBuilder sb = new StringBuilder("page targets did not answer Runtime.evaluate: ");
        for (int i = 0; i < frozen.size(); i++) {
            TargetProbe.Target t = frozen.get(i);
            sb.append(i == 0 ? "" : "; ").append(t.id()).append(' ').append(t.url());
        }
        return new CdpException(CdpException.Kind.FROZEN_TARGETS, sb.toString(), frozen);
    }

    private static CdpException classify(CdpEndpoint endpoint, Exception e, TargetProbe probe) {
        String first = firstLine(e.getMessage());
        if (e instanceof TimeoutError || e instanceof CancellationException) {
            return new CdpException(CdpException.Kind.TIMEOUT, "connectOverCDP to " + endpoint + " timed out: "
                    + first, targetsOf(probe), e);
        }
        String m = String.valueOf(e.getMessage());
        if (m.contains("ECONNREFUSED") || m.contains("ECONNRESET") || m.contains("ENOTFOUND")
                || m.contains("EHOSTUNREACH") || m.contains("socket hang up")) {
            return new CdpException(CdpException.Kind.UNREACHABLE, "connectOverCDP cannot reach " + endpoint + ": "
                    + first, targetsOf(probe), e);
        }
        return new CdpException(CdpException.Kind.PROTOCOL, "connectOverCDP to " + endpoint + " failed: " + first,
                targetsOf(probe), e);
    }

    static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "no message";
        }
        String line = message.strip();
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).strip();
        }
        return line.length() > 500 ? line.substring(0, 500) + "..." : line;
    }

    private static List<TargetProbe.Target> targetsOf(TargetProbe probe) {
        return probe == null ? List.of() : probe.pages();
    }

    /** Closes Playwright on a helper thread and waits for it at most {@code graceMs}; never throws. */
    static void closeQuietly(Playwright p, long graceMs) {
        if (p == null) {
            return;
        }
        Thread closer = Thread.ofPlatform().daemon(true).name("skillroy-cdp-close").start(() -> {
            try {
                p.close();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "closing Playwright failed: " + e);
            }
        });
        try {
            closer.join(Math.max(1, graceMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean containsIdentity(List<Page> pages, Page page) {
        for (Page p : pages) {
            if (p == page) {
                return true;
            }
        }
        return false;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private record Connected(Playwright playwright, Browser browser, BrowserContext context) {
    }

    /** The Playwright instance a connect created so far, and whether its caller gave up. */
    private static final class Pending {

        private Playwright playwright;
        private boolean abandoned;

        synchronized boolean created(Playwright p) {
            if (abandoned) {
                return false;
            }
            playwright = p;
            return true;
        }

        synchronized Playwright abandon() {
            abandoned = true;
            Playwright p = playwright;
            playwright = null;
            return p;
        }
    }
}
