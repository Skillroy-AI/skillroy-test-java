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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.web.WebLib;
import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebPath;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebPathOpenException;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * The CDP implementation of the web verbs (phase4-contracts.md sections 5.2, 5.3 and 6.1), registered as the
 * {@code ServiceLoader} service {@code ai.skillroy.testjava.scenario.web.WebPath} with the id {@value #ID}. It serves
 * every {@code web.*} primitive of section 4.2 except {@code web.cdp.status}, {@code web.reload} and {@code
 * web.browsers} (facts of the target, always the agent's), with the agent path's parameters and result shapes: the
 * page work is the shared script library ({@link WebLib}) evaluated through Playwright.
 *
 * <h2>Opening</h2>
 * {@link #open} takes the endpoint from the agent ({@code web.cdp.status}: the first of {@code endpoints}; none is
 * the reason {@value #NO_ENDPOINT}), refuses the policy {@code CLOSE} with a reason (D52), and checks that the
 * endpoint serves the target: when the agent reports CDP target ids for its browsers ({@code browsers[].cdpTargetId}
 * of the same {@code web.cdp.status}) and the endpoint's {@code /json/list} names none of them, the endpoint is another
 * browser's (a stale {@code DevToolsActivePort} file, a port another process holds), none of its pages is probed and
 * the reason is "the endpoint at &lt;host:port&gt; serves none of the target's browsers" (no target id reported: no
 * check). It probes the endpoint ({@link TargetProbe}, 2 s per page target), applies the frozen-target policy ({@code
 * FAIL}: {@link WebPathOpenException} {@code FROZEN_TARGETS} with {@code detail.frozenTargets [{id, url, title}]};
 * {@code RELOAD}: for each frozen target the agent's browser with that {@code cdpTargetId} (failing that, the one
 * browser without a target id that has the target's URL, or the only one left) is reloaded with {@code web.reload}, a
 * {@code web.recover} record is written, the agent's {@code web.ready} of that browser is awaited for at most 10 s,
 * and the endpoint is probed once more), then attaches ({@link CdpAttach}) under {@link WebSettings#attachDeadline()}
 * and keeps the attach for the run; {@code UNREACHABLE}, {@code TIMEOUT} and {@code PROTOCOL} become a {@link
 * WebPathOpenException} of that kind. A failure of any other kind after the attach closes it before {@code open}
 * throws.
 *
 * <p>With {@link WebSettings#trace()} the path starts context tracing into a private temporary directory of its own
 * ({@code skillroy-cdp-trace-*}, owner-only), never into the run's output directory: Playwright appends the live trace
 * to files there from the start of the run (page snapshots with the values of input fields, console text) and never
 * deletes them, so the path deletes the directory when it closes, and when {@code open} fails after creating it.
 * {@link #saveTrace} writes the zip to the file it is given, outside that directory; nothing else of the trace is
 * kept.
 *
 * <h2>Calls</h2>
 * A call that this path cannot serve throws {@link WebPathException} ({@code UNSUPPORTED}) before anything reached
 * the page, and the executor sends it to the agent and records the fall-back: the path is not open or closed, the
 * call names secret parameters ({@code CallOptions.secretParams()}: the agent's records redact them, a Playwright
 * trace or debug log would not), the target is in read-only mode and the primitive is mutating, an earlier operation
 * of this path has not returned (a frozen page), or the browser selection cannot pick exactly one page (see {@link
 * Selection}). Failures of the call itself are {@link AgentRpcException}s with the agent's codes, the type {@code
 * AgentConnection.call} throws: a library error object as its code ({@code NOT_FOUND}, {@code AMBIGUOUS}, {@code
 * INVALID_PARAMS}), a thrown script as {@code SCRIPT_ERROR} with {@code data {browser, message, stack}}, the call's
 * budget ({@code CallOptions.timeoutMs()}, else the primitive's default of section 4.2, at most 120 s, 600 s for
 * {@code web.waitFlag}) passing as {@code TIMEOUT}, a page that closed as {@code STALE_HANDLE} ("the browser closed
 * during the step"), any other Playwright failure as {@code INTERNAL_ERROR}. A read ({@code web.ariaSnapshot},
 * {@code web.dom.text}) whose execution context was destroyed by a navigation is evaluated once more; a mutating call
 * never is. Argument values are never logged.
 *
 * <h2>Two event sequences</h2>
 * The {@link RpcResult} of a call carries the <em>agent's</em> event sequence: {@code seqBefore} and {@code seqAfter}
 * are the agent's {@code lastSeq} read with {@code evt.query} just before and just after the primitive ran, so the
 * executor waits on the agent's bus from them as after any agent call. Its {@code events} list is empty (the path
 * publishes nothing on the agent's bus). The path's own events ({@code web.tap}, {@code web.ready} with {@code signal}
 * {@code TAP}, {@code FLAG} or {@code LOAD}, and {@code web.load}) are numbered by a sequence of their own (1, 2, 3,
 * ...) and read with {@link #eventsSince}; {@link #lastEventSeq()} is the current end of it and {@link #awaitEvent}
 * waits for the next one.
 *
 * <h2>Taps</h2>
 * {@code web.tap} taps the selected browser's page only, as the agent path taps one browser: an init script of that
 * page ({@link WebLib#tapInstaller}, run before the page's own scripts in every new document, so the timing is
 * {@code DOCUMENT_START}) plus the immediate {@code tap} call in its current document; {@code patterns: []} removes
 * both. The poller drains the tapped pages; {@link #close()} removes the taps it left. The library's {@code drain()}
 * empties the page's buffer, so the events a drain took are published by the drain operation itself, on the
 * Playwright thread: a drain that outlives the poll's 1 s bound (a page that stays busy) still publishes them when it
 * returns, and while it runs no further drain, and no other operation, is sent.
 *
 * <h2>Viewport overrides</h2>
 * {@code web.resize} sets a device-metrics override through the path's own {@code CDPSession} of the page ({@code
 * Emulation.setDeviceMetricsOverride {width, height, deviceScaleFactor: 0, mobile: false}}, the protocol call of the
 * in-process adapter), and {@code clear: true} removes it on that same session ({@code
 * Emulation.clearDeviceMetricsOverride}), after which the page follows its own window again. Playwright's {@code
 * setViewportSize} is not used: Playwright has no way to remove its override, and Chromium ignores a clear sent on a
 * session that did not set the override. An override outlives the DevTools session that set it, so {@link #close()}
 * removes every override the path made that is still in place.
 *
 * <h2>Class path</h2>
 * The executor finds this path through the class loader of the run. A JUnit suite puts {@code skillroy-cdp} on its
 * test class path (a Maven dependency brings the rest); the CLI needs every jar with {@code --pack}: {@code
 * skillroy-cdp-<version>.jar}, {@code playwright-1.63.0.jar}, {@code driver-1.63.0.jar}, {@code
 * driver-bundle-1.63.0.jar}, {@code gson-2.14.0.jar}, {@code jspecify-1.0.1.jar} and {@code opentest4j-1.3.0.jar}.
 *
 * <p>Thread-safe. {@link #call} blocks its caller for at most the call's budget; the Playwright work runs on the
 * attach's thread; a poller on a virtual thread drains tapped pages and lets Playwright deliver page events every
 * 100 ms while the path is open. After {@link #close()} every call is {@code UNSUPPORTED} until the path is opened
 * again.
 */
public final class CdpWebPath implements WebPath {

    /** The id of this path. */
    public static final String ID = "cdp";

    /** The primitives this path serves. */
    static final Set<String> SERVED = Set.of("web.evaluate", "web.ariaSnapshot", "web.dom.text", "web.dom.click",
            "web.dom.fill", "web.dispatchEvent", "web.tap", "web.waitFlag", "web.resize", "web.bitmap");

    /** The mutating ones among them (D51). */
    static final Set<String> MUTATING = Set.of("web.evaluate", "web.dom.click", "web.dom.fill",
            "web.dispatchEvent", "web.tap", "web.resize");

    /** The reason {@code open} gives for the policy {@code CLOSE}. */
    public static final String CLOSE_REFUSED = "frozenTargets: close is refused in Phase 4 (D52): closing a browser the"
            + " application owns can break it";

    /** The reason {@code open} gives when the agent knows no endpoint. */
    public static final String NO_ENDPOINT = "no remote-debugging endpoint configured";

    /** The prefix of the private temporary directory a traced path records into. */
    static final String TRACE_DIR_PREFIX = "skillroy-cdp-trace-";

    /** The viewport override of {@code web.resize}, on the path's own CDP session of the page. */
    static final String SET_OVERRIDE = "Emulation.setDeviceMetricsOverride";

    /** Its removal, on the same session (Chromium ignores a removal from a session that did not set the override). */
    static final String CLEAR_OVERRIDE = "Emulation.clearDeviceMetricsOverride";

    static final long POLL_PERIOD_MS = 100;
    static final long POLL_BUDGET_MS = 1_000;
    static final long AGENT_QUERY_MS = 5_000;
    static final long RELOAD_TIMEOUT_MS = 10_000;
    static final long RELOAD_WAIT_MS = 10_000;
    static final long CLOSE_STEP_MS = 2_000;
    static final long WAIT_FLAG_MARGIN_MS = 250;
    static final long ACTIONABILITY_RESERVE_MS = 1_000;

    private static final Map<String, Object> LAST_SEQ_QUERY = Map.of("since", Long.MAX_VALUE, "limit", 1L);
    private static final System.Logger LOG = System.getLogger(CdpWebPath.class.getName());

    /**
     * Probes an endpoint that must serve one of {@code targetIds} (empty: no check), answering null when it serves
     * none of them; {@link TargetProbe#run(CdpEndpoint, CdpOptions, Set)} unless a test replaces it.
     */
    @FunctionalInterface
    interface Prober {
        TargetProbe probe(CdpEndpoint endpoint, CdpOptions options, Set<String> targetIds) throws CdpException;
    }

    /** Attaches; {@link CdpAttach#connect} unless a test replaces it. */
    @FunctionalInterface
    interface Connector {
        CdpAttach connect(CdpEndpoint endpoint, CdpOptions options, TargetProbe probe) throws CdpException;
    }

    /** One open run; the maps marked confined are touched only on the attach's Playwright thread. */
    private static final class Session {

        final AgentConnection agent;
        final WebRunContext ctx;
        final CdpEndpoint endpoint;
        final CdpAttach attach;
        final boolean readOnly;
        final Map<String, Object> openInfo;
        final Map<String, AgentBrowser> byRef = new ConcurrentHashMap<>();
        final Map<String, String> refByTarget = new ConcurrentHashMap<>();
        /** Confined: the tapped pages. */
        final Map<Page, Tap> taps = new IdentityHashMap<>();
        /** Confined: the pages with a viewport override of this path, set on the page's CDP session of the attach. */
        final Map<Page, Boolean> overridden = new IdentityHashMap<>();
        /** Confined: the pages whose load events are watched. */
        final Map<Page, Boolean> watched = new IdentityHashMap<>();
        /** The private directory Playwright traces into, deleted at shutdown; null without tracing. */
        final Path traceDir;
        /** Confined: the browser-level session that lets Playwright deliver events; null until needed. */
        CDPSession browserSession;
        boolean pumpBroken;
        volatile ScheduledExecutorService poller;

        Session(AgentConnection agent, WebRunContext ctx, CdpEndpoint endpoint, CdpAttach attach, boolean readOnly,
                Map<String, Object> openInfo, Path traceDir) {
            this.agent = agent;
            this.ctx = ctx;
            this.endpoint = endpoint;
            this.attach = attach;
            this.readOnly = readOnly;
            this.openInfo = openInfo;
            this.traceDir = traceDir;
        }
    }

    /** A tap on one page. */
    private static final class Tap {

        final String targetId;
        final String target;
        final AutoCloseable initScript;
        volatile String ref;

        Tap(String targetId, String ref, String target, AutoCloseable initScript) {
            this.targetId = targetId;
            this.ref = ref;
            this.target = target;
            this.initScript = initScript;
        }
    }

    /** What one poll drained from one page. */
    private record Drained(Tap tap, String url, Object value) {
    }

    /** The page and frame a call works on. */
    private record Place(Page page, Frame frame, String targetId) {
    }

    private final Prober prober;
    private final Connector connector;
    private final boolean poll;
    private final Object lock = new Object();
    private final PathEvents events = new PathEvents();
    private volatile Session session;
    private volatile boolean closed;

    /** The path as {@code ServiceLoader} creates it. */
    public CdpWebPath() {
        this(TargetProbe::run, CdpAttach::connect, true);
    }

    CdpWebPath(Prober prober, Connector connector, boolean poll) {
        this.prober = Objects.requireNonNull(prober, "prober");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.poll = poll;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Probes and attaches (see the class comment). Returns null when the path is usable, {@value #NO_ENDPOINT} when
     * the agent knows no endpoint, {@link #CLOSE_REFUSED} for the policy {@code CLOSE}, {@link #notServing} when the
     * endpoint serves none of the target's browsers, or another reason when the agent's {@code web.cdp.status} failed.
     * Whatever {@code open} throws after the attach was made, the attach is closed first.
     *
     * @throws WebPathOpenException {@code UNREACHABLE}, {@code TIMEOUT}, {@code PROTOCOL} or {@code FROZEN_TARGETS}
     *     ({@code detail.frozenTargets}); every detail names the {@code endpoint}
     * @throws AgentLostException when the agent is gone
     */
    @Override
    public String open(AgentConnection agent, WebSettings settings, WebRunContext ctx) throws Exception {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(settings, "settings");
        Session previous;
        synchronized (lock) {
            previous = session;
            session = null;
            closed = false;
        }
        if (previous != null) {
            shutdown(previous);
        }
        if (settings.frozenTargets() == FrozenTargetPolicy.CLOSE) {
            return CLOSE_REFUSED;
        }
        Object status;
        try {
            status = agent.call("web.cdp.status", Map.of(), query(AGENT_QUERY_MS)).value();
        } catch (AgentRpcException e) {
            return "web.cdp.status failed: " + describe(e);
        }
        CdpEndpoint endpoint;
        try {
            endpoint = firstEndpoint(status);
        } catch (IllegalArgumentException e) {
            return "the agent reported an endpoint that cannot be used: " + e.getMessage();
        }
        if (endpoint == null) {
            return NO_ENDPOINT;
        }
        Set<String> targetIds = targetIds(status);
        boolean readOnly = Boolean.TRUE.equals(agent.ping().get("readOnly"));
        CdpOptions options = CdpOptions.defaults()
                .withConnectDeadline(settings.attachDeadline())
                .withFrozenTargets(settings.frozenTargets());
        TargetProbe probe = probe(endpoint, options, targetIds);
        if (probe != null && !probe.unresponsive().isEmpty()
                && settings.frozenTargets() == FrozenTargetPolicy.RELOAD) {
            recover(agent, ctx, probe, options);
            probe = probe(endpoint, options, targetIds);
        }
        if (probe == null) {
            return notServing(endpoint);
        }
        if (!probe.unresponsive().isEmpty()) {
            throw openFailure(CdpAttach.frozen(probe.unresponsive()), endpoint);
        }
        // Playwright records a live trace into its artifacts directory and never deletes a directory it was given:
        // a private one of this path, deleted at shutdown, never the run's output directory (where a filled value in
        // a page snapshot would stay, and the next run's trace would be appended to this one).
        Path traceDir = settings.trace() ? createTraceDir() : null;
        CdpAttach attach;
        try {
            attach = connector.connect(endpoint, options.withArtifactsDir(traceDir), probe);
        } catch (CdpException e) {
            deleteTree(traceDir);
            throw openFailure(e, endpoint);
        } catch (Throwable t) {
            deleteTree(traceDir);
            throw t;
        }
        Session s = null;
        try {
            s = new Session(agent, ctx, endpoint, attach, readOnly, openInfo(endpoint, probe, attach), traceDir);
            begin(s);
        } catch (Throwable t) {
            if (s != null) {
                shutdown(s);
            } else {
                attach.close();
                deleteTree(traceDir);
            }
            throw t;
        }
        boolean closedMeanwhile;
        synchronized (lock) {
            closedMeanwhile = closed;
            if (!closedMeanwhile) {
                session = s;
            }
        }
        if (closedMeanwhile) {
            shutdown(s);
            return "the CDP path was closed while it opened";
        }
        return null;
    }

    /**
     * {@code {endpoint, browserVersion, connectMs, targets, unresponsive}} of the open attach for the {@code web.path}
     * record: the endpoint as {@code host:port}, the browser's {@code /json/version}, the attach time, the number of
     * page targets and of those that did not answer the last probe; null when the path is not open.
     */
    @Override
    public Map<String, Object> openInfo() {
        Session s = session;
        return s == null ? null : s.openInfo;
    }

    @Override
    public boolean serves(String primitive) {
        return primitive != null && SERVED.contains(primitive);
    }

    /** See the class comment for what is served, what falls back to the agent and how failures are reported. */
    @Override
    public RpcResult call(String primitive, Map<String, Object> args, CallOptions options) throws Exception {
        long start = System.nanoTime();
        Session s = session;
        if (s == null) {
            throw WebPathException.unsupported(closed ? "the CDP path is closed" : "the CDP path is not open");
        }
        if (!serves(primitive)) {
            throw WebPathException.unsupported("the CDP path does not serve " + primitive);
        }
        CallOptions o = options == null ? CallOptions.defaults() : options;
        if (!o.secretParams().isEmpty()) {
            throw WebPathException.unsupported("a call with secret parameters is served by the agent, which redacts"
                    + " them in its records");
        }
        if (s.readOnly && MUTATING.contains(primitive)) {
            throw WebPathException.unsupported("the target is in read-only mode: the agent answers " + primitive);
        }
        if (s.attach.busy()) {
            throw WebPathException.unsupported("an earlier CDP operation has not returned (a page may be frozen)");
        }
        Map<String, Object> params = args == null ? Map.of() : args;
        long budgetMs = budgetMs(primitive, o);
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        Callable<Object> body = prepare(s, primitive, params, budgetMs, deadline, o.correlationId());
        long agentBefore = agentSeq(s, deadline);
        Object value = body.call();
        long agentAfter = agentSeqAfter(s, agentBefore);
        return new RpcResult(value, List.of(), agentBefore, agentAfter, elapsedMs(start), "controller");
    }

    /** The path's own events after {@code seq} (its own sequence) whose topic matches {@code topics}. */
    @Override
    public List<Event> eventsSince(long seq, String topics) {
        return events.since(seq, topics);
    }

    /** The sequence number of the path's last own event; 0 before the first. */
    public long lastEventSeq() {
        return events.lastSeq();
    }

    /**
     * The first of the path's own events after {@code seq} whose topic matches {@code topics}, waiting at most {@code
     * timeout} for the poller (or a call) to append it; null when none came. Not part of {@link WebPath}: an executor
     * that waits on both sources at once can use it with a short timeout instead of reading {@link #eventsSince}
     * repeatedly.
     */
    public Event awaitEvent(long seq, String topics, Duration timeout) throws InterruptedException {
        return events.await(seq, topics, timeout == null ? 0 : timeout.toMillis());
    }

    /**
     * Stops tracing and writes the trace to {@code target} (a relative path resolves against the working directory,
     * never into the path's private trace directory, which {@link #close()} deletes); does nothing when tracing is
     * off or the path is not open. A trace holds page content and bypasses the run's secret mask (see {@link
     * CdpTracing}).
     */
    @Override
    public void saveTrace(Path target) throws Exception {
        Session s = session;
        if (s == null || target == null || !s.attach.tracing().isActive()) {
            return;
        }
        s.attach.tracing().stop(target.toAbsolutePath());
    }

    /**
     * Stops the poller, removes the viewport overrides and the taps it made, stops tracing without saving it, closes
     * the attach (which closes Playwright and never the browser) and deletes the private trace directory. Idempotent;
     * never throws.
     */
    @Override
    public void close() {
        Session s;
        synchronized (lock) {
            s = session;
            session = null;
            closed = true;
        }
        if (s != null) {
            shutdown(s);
        }
    }

    // ---------------------------------------------------------------- opening

    /** The probe, or null when the endpoint serves none of {@code targetIds}. */
    private TargetProbe probe(CdpEndpoint endpoint, CdpOptions options, Set<String> targetIds)
            throws WebPathOpenException {
        try {
            return prober.probe(endpoint, options, targetIds);
        } catch (CdpException e) {
            throw openFailure(e, endpoint);
        }
    }

    /** Reads the agent's refs, watches the pages' loads, starts tracing and the poller of a new attach. */
    private void begin(Session s) throws AgentLostException {
        try {
            refreshBrowsers(s, deadline(AGENT_QUERY_MS));
        } catch (WebPathException e) {
            LOG.log(System.Logger.Level.DEBUG, "web.browsers could not be read at open: " + e.getMessage());
        }
        watchPages(s);
        if (s.traceDir != null) {
            try {
                s.attach.tracing().start("skillroy");
            } catch (CdpException e) {
                LOG.log(System.Logger.Level.WARNING, "CDP tracing could not start: " + e.getMessage());
            }
        }
        if (poll) {
            startPoller(s);
        }
    }

    /** The reason {@code open} gives for an endpoint that lists none of the target ids the agent reports. */
    static String notServing(CdpEndpoint endpoint) {
        return "the endpoint at " + endpoint + " serves none of the target's browsers";
    }

    /**
     * The CDP target ids the agent reports for its open browsers in a {@code web.cdp.status} value ({@code
     * browsers[].cdpTargetId}, phase4-contracts.md section 4.2); empty when it reports none.
     */
    static Set<String> targetIds(Object status) {
        if (!(status instanceof Map<?, ?> m) || !(m.get("browsers") instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> b && b.get("cdpTargetId") instanceof String id && !id.isEmpty()) {
                ids.add(id);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * A new private temporary directory for Playwright's trace ({@value #TRACE_DIR_PREFIX}..., {@code rwx------}
     * where the file system has POSIX permissions); null, with a warning, when none can be made: the run is then not
     * traced.
     */
    static Path createTraceDir() {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return Files.createTempDirectory(TRACE_DIR_PREFIX,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            return Files.createTempDirectory(TRACE_DIR_PREFIX);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "CDP tracing is off: no private directory could be made for it: "
                    + e);
            return null;
        }
    }

    /** Deletes a directory and everything in it, as far as it can (symbolic links are not followed); never throws. */
    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(dir)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | UncheckedIOException e) {
            LOG.log(System.Logger.Level.WARNING, "the private trace directory " + dir + " could not be read: " + e);
            return;
        }
        int left = 0;
        for (Path p : entries) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                left++;
            }
        }
        if (left > 0) {
            LOG.log(System.Logger.Level.WARNING, left + " entries of the private trace directory " + dir
                    + " could not be deleted");
        }
    }

    static CdpEndpoint firstEndpoint(Object status) {
        if (!(status instanceof Map<?, ?> m) || !(m.get("endpoints") instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (!(list.get(0) instanceof Map<?, ?> first) || !(first.get("port") instanceof Number port)) {
            throw new IllegalArgumentException("endpoints[0] has no port");
        }
        Object host = first.get("host");
        return new CdpEndpoint(host == null ? null : String.valueOf(host), port.intValue());
    }

    private static Map<String, Object> openInfo(CdpEndpoint endpoint, TargetProbe probe, CdpAttach attach) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("endpoint", endpoint.toString());
        info.put("browserVersion", probe.browserVersion());
        info.put("connectMs", attach.connectMillis());
        info.put("targets", (long) probe.pages().size());
        info.put("unresponsive", (long) probe.unresponsive().size());
        return Collections.unmodifiableMap(info);
    }

    static WebPathOpenException openFailure(CdpException e, CdpEndpoint endpoint) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("endpoint", endpoint.toString());
        WebPathOpenException.Kind kind = switch (e.kind()) {
            case UNREACHABLE -> WebPathOpenException.Kind.UNREACHABLE;
            case TIMEOUT -> WebPathOpenException.Kind.TIMEOUT;
            case PROTOCOL -> WebPathOpenException.Kind.PROTOCOL;
            case FROZEN_TARGETS -> WebPathOpenException.Kind.FROZEN_TARGETS;
        };
        if (kind == WebPathOpenException.Kind.FROZEN_TARGETS) {
            List<Object> frozen = new ArrayList<>();
            for (TargetProbe.Target t : e.targets()) {
                frozen.add(t.summary());
            }
            detail.put("frozenTargets", frozen);
        }
        WebPathOpenException failure = new WebPathOpenException(kind, e.getMessage(), detail);
        failure.initCause(e);
        return failure;
    }

    /** Asks the agent to reload the browser of each frozen target once, recording {@code web.recover} lines. */
    private void recover(AgentConnection agent, WebRunContext ctx, TargetProbe probe, CdpOptions options)
            throws AgentLostException {
        List<AgentBrowser> browsers;
        try {
            browsers = AgentBrowser.parse(agent.call("web.browsers", Map.of(), query(AGENT_QUERY_MS)).value());
        } catch (AgentRpcException e) {
            browsers = List.of();
        }
        List<TargetProbe.Target> frozen = probe.unresponsive();
        for (TargetProbe.Target target : frozen) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "web.recover");
            String[] match = matchFrozen(target, frozen, probe.pages(), browsers);
            record.put("browser", match == null ? null : match[0]);
            record.put("action", "reload");
            record.put("targetId", target.id());
            if (match == null) {
                record.put("ok", false);
                record.put("reason", "no browser of the agent's web.browsers matches the target");
                record(ctx, record);
                continue;
            }
            record.put("matchedBy", match[1]);
            long since = -1;
            try {
                RpcResult reloaded = agent.call("web.reload", Map.of("browser", match[0]), query(RELOAD_TIMEOUT_MS));
                since = reloaded.seqBefore();
                record.put("ok", true);
            } catch (AgentRpcException e) {
                record.put("ok", false);
                record.put("reason", describe(e));
            }
            record(ctx, record);
            if (since >= 0) {
                try {
                    agent.await("web.ready", since, Map.of("browser", match[0], "signal", "LOAD"),
                            Duration.ofMillis(Math.min(RELOAD_WAIT_MS, options.connectDeadline().toMillis())));
                } catch (AgentRpcException e) {
                    LOG.log(System.Logger.Level.DEBUG, "waiting for the reloaded page failed: " + describe(e));
                }
            }
        }
    }

    /**
     * {@code {ref, rule}} of the agent's browser for a frozen target, or null: the browser whose {@code cdpTargetId}
     * is the target's ({@code "cdpTargetId"}); else the one open browser without a known target id whose URL is the
     * target's ({@code "url"}); else, for a single frozen target, the only open browser without a known target id
     * ({@code "elimination"}). The fall-backs exist because the agent may not read the target id of a frozen page.
     */
    static String[] matchFrozen(TargetProbe.Target target, List<TargetProbe.Target> frozen,
                                List<TargetProbe.Target> pages, List<AgentBrowser> browsers) {
        for (AgentBrowser b : browsers) {
            if (!b.closed() && target.id().equals(b.cdpTargetId())) {
                return new String[] {b.ref(), "cdpTargetId"};
            }
        }
        List<AgentBrowser> unknown = new ArrayList<>();
        for (AgentBrowser b : browsers) {
            if (b.closed()) {
                continue;
            }
            boolean known = false;
            for (TargetProbe.Target p : pages) {
                if (p.id().equals(b.cdpTargetId())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                unknown.add(b);
            }
        }
        List<AgentBrowser> sameUrl = new ArrayList<>();
        for (AgentBrowser b : unknown) {
            if (!target.url().isEmpty() && target.url().equals(b.url())) {
                sameUrl.add(b);
            }
        }
        if (sameUrl.size() == 1) {
            return new String[] {sameUrl.get(0).ref(), "url"};
        }
        if (frozen.size() == 1 && unknown.size() == 1) {
            return new String[] {unknown.get(0).ref(), "elimination"};
        }
        return null;
    }

    private static void record(WebRunContext ctx, Map<String, Object> record) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.record(record);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "a run record could not be written: " + e);
        }
    }

    // ---------------------------------------------------------------- calls

    /** Checks the parameters (INVALID_PARAMS before anything else) and returns the call's work. */
    private Callable<Object> prepare(Session s, String primitive, Map<String, Object> p, long budgetMs,
                                     long deadline, String correlationId) throws Exception {
        Object frameKey = p.get("frame");
        switch (primitive) {
            case "web.evaluate": {
                String expression = Params.string(primitive, p, "expression", true);
                boolean await = Params.bool(primitive, p, "awaitPromise", false);
                return () -> {
                    Selection sel = select(s, p, deadline);
                    return onPw(s, primitive, sel, budgetMs, deadline, () -> {
                        Place at = place(s, sel, frameKey);
                        Object ok = Shapes.unwrap(primitive, at.frame().evaluate(Scripts.evaluate(expression, await)),
                                refOf(s, at));
                        if (!(ok instanceof String text)) {
                            throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR,
                                    "the evaluate wrapper returned no text", Shapes.data("path", ID));
                        }
                        try {
                            return Shapes.evaluateValue(text);
                        } catch (IllegalArgumentException e) {
                            throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, e.getMessage(),
                                    Shapes.data("path", ID));
                        }
                    });
                };
            }
            case "web.ariaSnapshot": {
                long maxNodes = Params.integer(primitive, p, "maxNodes", 2000, 1, 20_000);
                boolean interesting = Params.bool(primitive, p, "interestingOnly", true);
                String script = Scripts.call("aria", null, maxNodes, interesting);
                return read(s, primitive, p, budgetMs, deadline, frameKey, script);
            }
            case "web.dom.text": {
                String selector = Params.string(primitive, p, "selector", false);
                Long maxChars = Params.integer(primitive, p, "maxChars");
                if (maxChars != null && maxChars < 0) {
                    throw Params.invalid(primitive, "maxChars", "maxChars must not be negative");
                }
                Map<String, Object> lib = new LinkedHashMap<>();
                lib.put("selector", selector == null ? "body" : selector);
                lib.put("maxChars", maxChars == null ? 10_000L : Math.min(maxChars, 100_000L));
                return read(s, primitive, p, budgetMs, deadline, frameKey, Scripts.call("domText", lib));
            }
            case "web.dom.click":
            case "web.dom.fill": {
                boolean fill = primitive.equals("web.dom.fill");
                Map<String, Object> lib = new LinkedHashMap<>();
                for (String key : fill ? List.of("selector", "role", "name", "index", "expectVisible", "text", "clear",
                        "enter") : List.of("selector", "role", "name", "index", "expectVisible")) {
                    if (p.containsKey(key)) {
                        lib.put(key, p.get(key));
                    }
                }
                if (fill) {
                    Params.string(primitive, p, "text", true);
                }
                String script = Scripts.call(fill ? "domFill" : "domClick", lib);
                return () -> {
                    Selection sel = select(s, p, deadline);
                    return onPw(s, primitive, sel, budgetMs, deadline, () -> {
                        Place at = place(s, sel, frameKey);
                        waitActionable(at.frame(), p, deadline);
                        return Shapes.unwrap(primitive, at.frame().evaluate(script), refOf(s, at));
                    });
                };
            }
            case "web.dispatchEvent": {
                String name = Params.string(primitive, p, "name", true);
                if (name.isBlank()) {
                    throw Params.invalid(primitive, "name", "name must not be blank");
                }
                String target = Params.string(primitive, p, "target", false);
                Map<String, Object> lib = new LinkedHashMap<>();
                if (target != null) {
                    lib.put("target", target);
                }
                lib.put("name", name);
                if (p.containsKey("detail")) {
                    lib.put("detail", p.get("detail"));
                }
                String script = Scripts.call("dispatch", lib);
                return () -> {
                    Selection sel = select(s, p, deadline);
                    return onPw(s, primitive, sel, budgetMs, deadline, () -> {
                        Place at = place(s, sel, frameKey);
                        return Shapes.unwrap(primitive, at.frame().evaluate(script), refOf(s, at));
                    });
                };
            }
            case "web.tap":
                return tap(s, primitive, p, budgetMs, deadline);
            case "web.waitFlag":
                return waitFlag(s, primitive, p, budgetMs, deadline, frameKey, correlationId);
            case "web.resize":
                return resize(s, primitive, p, budgetMs, deadline);
            case "web.bitmap": {
                long maxWidth = Params.integer(primitive, p, "maxWidth", 4096, 1, Integer.MAX_VALUE);
                return () -> {
                    Selection sel = select(s, p, deadline);
                    byte[] png = onPw(s, primitive, sel, budgetMs, deadline, () -> {
                        Place at = place(s, sel, null);
                        return at.page().screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG)
                                .setTimeout(Math.max(1, remainingMs(deadline) - 200)));
                    });
                    try {
                        return Shapes.bitmapValue(png, (int) maxWidth);
                    } catch (IOException | IllegalArgumentException e) {
                        throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, "the screenshot cannot be used: "
                                + e.getMessage(), Shapes.data("path", ID));
                    }
                };
            }
            default:
                throw WebPathException.unsupported("the CDP path does not serve " + primitive);
        }
    }

    /** A library read, evaluated once more when a navigation destroyed its execution context. */
    private Callable<Object> read(Session s, String primitive, Map<String, Object> p, long budgetMs, long deadline,
                                  Object frameKey, String script) {
        return () -> {
            Selection sel = select(s, p, deadline);
            return onPw(s, primitive, sel, budgetMs, deadline, () -> {
                Place at = place(s, sel, frameKey);
                Object raw;
                try {
                    raw = at.frame().evaluate(script);
                } catch (PlaywrightException e) {
                    if (!contextDestroyed(e)) {
                        throw e;
                    }
                    raw = place(s, sel, frameKey).frame().evaluate(script);
                }
                Object value = Shapes.unwrap(primitive, raw, refOf(s, at));
                if (!(value instanceof Map)) {
                    throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, "the page library returned no object",
                            Shapes.data("path", ID));
                }
                return value;
            });
        };
    }

    private Callable<Object> tap(Session s, String primitive, Map<String, Object> p, long budgetMs, long deadline)
            throws AgentRpcException {
        List<String> patterns = Params.strings(primitive, p, "patterns");
        String target = Params.string(primitive, p, "target", false);
        String on = target == null ? "window" : target;
        if (!on.equals("window") && !on.equals("document")) {
            throw Params.invalid(primitive, "target", "target must be window or document");
        }
        return () -> {
            Selection sel = select(s, p, deadline);
            Object[] result = onPw(s, primitive, sel, budgetMs, deadline, () -> {
                Place at = place(s, sel, null);
                Object value = Shapes.unwrap(primitive, at.page().evaluate(Scripts.call("tap", patterns, on)),
                        refOf(s, at));
                Tap old = s.taps.remove(at.page());
                if (old != null) {
                    closeQuietly(old.initScript);
                }
                Tap tap = null;
                if (!patterns.isEmpty()) {
                    tap = new Tap(at.targetId(), refOf(s, at), on, at.page().addInitScript(WebLib.tapInstaller(patterns,
                            on)));
                    s.taps.put(at.page(), tap);
                }
                return new Object[] {value, tap};
            });
            if (result[1] instanceof Tap tap && tap.ref == null) {
                try {
                    refreshBrowsers(s, deadline);
                } catch (WebPathException ignored) {
                    // the events then name no browser
                }
                tap.ref = s.refByTarget.get(tap.targetId);
            }
            long installed = result[0] instanceof Map<?, ?> m && m.get("installed") instanceof Number n
                    ? n.longValue() : patterns.size();
            return Shapes.tapValue(installed);
        };
    }

    private Callable<Object> waitFlag(Session s, String primitive, Map<String, Object> p, long budgetMs,
                                      long deadline, Object frameKey, String correlationId) throws AgentRpcException {
        String expression = Params.string(primitive, p, "expression", true);
        Long timeout = Params.integer(primitive, p, "timeoutMs");
        if (timeout != null && timeout < 0) {
            throw Params.invalid(primitive, "timeoutMs", "timeoutMs must not be negative");
        }
        long pollMs = Params.integer(primitive, p, "pollMs", 50, 20, 60_000);
        long wanted = timeout == null || timeout == 0 ? budgetMs : Math.min(timeout, 600_000);
        String predicate = Scripts.flag(expression);
        return () -> {
            Selection sel = select(s, p, deadline);
            Object[] result = onPw(s, primitive, sel, budgetMs, deadline, () -> {
                Place at = place(s, sel, frameKey);
                long wait = Math.max(1, Math.min(wanted, remainingMs(deadline) - WAIT_FLAG_MARGIN_MS));
                long t0 = System.nanoTime();
                boolean ready;
                try {
                    JSHandle handle = at.frame().waitForFunction(predicate, null,
                            new Frame.WaitForFunctionOptions().setPollingInterval(pollMs).setTimeout(wait));
                    Object value = Values.json(handle.jsonValue());
                    disposeQuietly(handle);
                    if (value instanceof Map<?, ?> m && m.containsKey("__scriptError")) {
                        throw Shapes.scriptError(primitive, m.get("__scriptError"), refOf(s, at));
                    }
                    ready = true;
                } catch (TimeoutError e) {
                    ready = false;
                }
                return new Object[] {ready, elapsedMs(t0), at.page().url(), refOf(s, at)};
            });
            boolean ready = (Boolean) result[0];
            if (ready) {
                events.append("web.ready", Shapes.data("browser", result[3], "url", result[2], "signal", "FLAG"),
                        correlationId);
            }
            return Shapes.waitFlagValue(ready, (Long) result[1], pollMs);
        };
    }

    private Callable<Object> resize(Session s, String primitive, Map<String, Object> p, long budgetMs,
                                    long deadline) throws AgentRpcException {
        boolean clear = Params.bool(primitive, p, "clear", false);
        Long width = Params.integer(primitive, p, "width");
        Long height = Params.integer(primitive, p, "height");
        if (!clear) {
            for (Object[] dim : new Object[][] {{"width", width}, {"height", height}}) {
                Long v = (Long) dim[1];
                if (v == null || v < 1 || v > 16_384) {
                    throw Params.invalid(primitive, (String) dim[0], dim[0] + " must be between 1 and 16384, or"
                            + " clear must be true");
                }
            }
        }
        return () -> {
            Selection sel = select(s, p, deadline);
            return onPw(s, primitive, sel, budgetMs, deadline, () -> {
                Page page = place(s, sel, null).page();
                if (clear) {
                    if (s.overridden.containsKey(page)) {
                        CdpAttach.send(s.attach.sessionOnPw(page), CLEAR_OVERRIDE);
                        s.overridden.remove(page);
                    }
                    return Shapes.resizeValue(innerSize(page));
                }
                s.overridden.put(page, Boolean.TRUE);
                CdpAttach.send(s.attach.sessionOnPw(page), SET_OVERRIDE, metrics(width, height));
                return Shapes.resizeValue(width, height);
            });
        };
    }

    /** The parameters of {@code Emulation.setDeviceMetricsOverride} for {@code web.resize}, as the agent sends them. */
    static Map<String, Object> metrics(long width, long height) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", width);
        m.put("height", height);
        m.put("deviceScaleFactor", 0L);
        m.put("mobile", false);
        return m;
    }

    /**
     * The page's inner size {w, h} as the page reads it now, or null when it cannot be read (the page navigated);
     * call only on the Playwright thread.
     */
    private static long[] innerSize(Page page) {
        Object value;
        try {
            value = Values.json(page.evaluate(Scripts.innerSize()));
        } catch (PlaywrightException e) {
            if (targetClosed(e)) {
                throw e;
            }
            return null;
        }
        if (value instanceof Map<?, ?> m && m.get("w") instanceof Long w && m.get("h") instanceof Long h) {
            return new long[] {w, h};
        }
        return null;
    }

    /** Waits for Playwright's actionability of the element the library is about to act on; never fails the call. */
    private static void waitActionable(Frame frame, Map<String, Object> p, long deadline) {
        long left = remainingMs(deadline);
        long wait = left - Math.min(ACTIONABILITY_RESERVE_MS, left / 2);
        if (wait < 50) {
            return;
        }
        Locator locator = locator(frame, p);
        if (locator == null) {
            return;
        }
        boolean visible = !Boolean.FALSE.equals(p.get("expectVisible"));
        try {
            locator.waitFor(new Locator.WaitForOptions()
                    .setState(visible ? WaitForSelectorState.VISIBLE : WaitForSelectorState.ATTACHED)
                    .setTimeout(wait));
        } catch (PlaywrightException e) {
            if (targetClosed(e)) {
                throw e;
            }
            // missing, several, or a selector Playwright does not take: the library reports it in its own terms
        }
    }

    /** The Playwright locator of a click or fill target, or null when the parameters do not make one. */
    static Locator locator(Frame frame, Map<String, Object> p) {
        Locator locator;
        if (p.get("selector") instanceof String selector && !selector.isBlank()) {
            locator = frame.locator("css=" + selector);
        } else if (p.get("role") instanceof String role && !role.isBlank()) {
            AriaRole aria;
            try {
                aria = AriaRole.valueOf(role.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
            Frame.GetByRoleOptions options = new Frame.GetByRoleOptions();
            if (Boolean.FALSE.equals(p.get("expectVisible"))) {
                options.setIncludeHidden(true);
            }
            Object name = p.get("name");
            if (name instanceof String text) {
                options.setName(text).setExact(true);
            } else if (name instanceof Map<?, ?> m && m.get("regex") instanceof String regex) {
                try {
                    options.setName(Pattern.compile(regex, Boolean.TRUE.equals(m.get("ignoreCase"))
                            ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0));
                } catch (PatternSyntaxException e) {
                    return null;
                }
            } else if (name != null) {
                return null;
            }
            locator = frame.getByRole(aria, options);
        } else {
            return null;
        }
        if (p.get("index") != null) {
            if (!(p.get("index") instanceof Number n) || n.longValue() < 0 || n.longValue() > Integer.MAX_VALUE
                    || n.doubleValue() != Math.rint(n.doubleValue())) {
                return null;
            }
            locator = locator.nth(n.intValue());
        }
        return locator;
    }

    /** Runs {@code op} on the Playwright thread within the call's deadline and maps the failures. */
    private <T> T onPw(Session s, String primitive, Selection sel, long budgetMs, long deadline, Callable<T> op)
            throws Exception {
        long left = remainingMs(deadline);
        if (left <= 0) {
            throw timeout(primitive, budgetMs);
        }
        try {
            return s.attach.run(op, left);
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw timeout(primitive, budgetMs);
        } catch (PwThread.Busy e) {
            throw WebPathException.unsupported(e.getMessage());
        } catch (PlaywrightException e) {
            throw playwrightFailure(primitive, e, sel.ref());
        } catch (RuntimeException e) {
            throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, "the CDP path failed: " + e,
                    Shapes.data("path", ID));
        }
    }

    /** The page and frame of a selection; call only on the Playwright thread. */
    private Place place(Session s, Selection sel, Object frameKey) throws WebPathException {
        Map<String, Page> pages = s.attach.pagesOnPw();
        String targetId = sel.pick(pages, Page::url, Page::title);
        Page page = pages.get(targetId);
        watch(s, page);
        return new Place(page, frame(s, page, frameKey), targetId);
    }

    /**
     * The Playwright frame of a protocol frame key: the frame of that id in the page's {@code Page.getFrameTree} (the
     * main frame when it is the root), else the one frame of the page with the same URL (with its fragment) and
     * name; anything else is {@code UNSUPPORTED}. Call only on the Playwright thread.
     */
    private static Frame frame(Session s, Page page, Object frameKey) throws WebPathException {
        if (frameKey == null) {
            return page.mainFrame();
        }
        if (!(frameKey instanceof String key) || key.isEmpty()) {
            throw WebPathException.unsupported("frame must be a frame key");
        }
        Map<String, Object> tree = CdpAttach.send(s.attach.sessionOnPw(page), "Page.getFrameTree");
        Map<?, ?> root = tree.get("frameTree") instanceof Map<?, ?> r ? r : null;
        if (root != null && root.get("frame") instanceof Map<?, ?> top && key.equals(top.get("id"))) {
            return page.mainFrame();
        }
        Map<?, ?> found = root == null ? null : findFrame(root, key);
        if (found == null) {
            throw WebPathException.unsupported("frame " + key + " is not in the page's frame tree");
        }
        String url = String.valueOf(found.get("url") == null ? "" : found.get("url"))
                + (found.get("urlFragment") instanceof String fragment ? fragment : "");
        String name = found.get("name") instanceof String n ? n : "";
        List<Frame> hits = new ArrayList<>();
        for (Frame f : page.frames()) {
            if (f != page.mainFrame() && url.equals(f.url()) && name.equals(f.name())) {
                hits.add(f);
            }
        }
        if (hits.size() != 1) {
            throw WebPathException.unsupported((hits.isEmpty() ? "no frame" : hits.size() + " frames")
                    + " of the page have the URL and name of frame " + key);
        }
        return hits.get(0);
    }

    /** The {@code frame} object of the node of the frame tree whose frame has the given id, or null. */
    static Map<?, ?> findFrame(Map<?, ?> node, String id) {
        if (node.get("frame") instanceof Map<?, ?> f && id.equals(f.get("id"))) {
            return f;
        }
        if (node.get("childFrames") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> c) {
                    Map<?, ?> hit = findFrame(c, id);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    private Selection select(Session s, Map<String, Object> p, long deadline) throws Exception {
        return Selection.resolve(p, new Selection.Source() {
            @Override
            public AgentBrowser byRef(String ref) throws Exception {
                AgentBrowser b = s.byRef.get(ref);
                if (b == null) {
                    refreshBrowsers(s, deadline);
                    b = s.byRef.get(ref);
                }
                return b;
            }

            @Override
            public List<AgentBrowser> current() throws Exception {
                return refreshBrowsers(s, deadline);
            }
        });
    }

    /** Reads the agent's {@code web.browsers} and refreshes the ref caches; a failure is {@code UNSUPPORTED}. */
    private static List<AgentBrowser> refreshBrowsers(Session s, long deadline)
            throws WebPathException, AgentLostException {
        List<AgentBrowser> browsers;
        try {
            browsers = AgentBrowser.parse(s.agent.call("web.browsers", Map.of(),
                    query(Math.max(1, Math.min(AGENT_QUERY_MS, remainingMs(deadline))))).value());
        } catch (AgentRpcException e) {
            throw WebPathException.unsupported("web.browsers failed: " + describe(e));
        }
        Map<String, AgentBrowser> refs = new LinkedHashMap<>();
        Map<String, String> targets = new LinkedHashMap<>();
        for (AgentBrowser b : browsers) {
            refs.put(b.ref(), b);
            if (b.cdpTargetId() != null && !b.closed()) {
                targets.put(b.cdpTargetId(), b.ref());
            }
        }
        s.byRef.keySet().retainAll(refs.keySet());
        s.byRef.putAll(refs);
        s.refByTarget.keySet().retainAll(targets.keySet());
        s.refByTarget.putAll(targets);
        return browsers;
    }

    /** The agent's {@code lastSeq}; a failure before the primitive ran is {@code UNSUPPORTED}. */
    private static long agentSeq(Session s, long deadline) throws WebPathException, AgentLostException {
        try {
            RpcResult r = s.agent.call("evt.query", LAST_SEQ_QUERY,
                    query(Math.max(1, Math.min(AGENT_QUERY_MS, remainingMs(deadline)))));
            if (r.value() instanceof Map<?, ?> m && m.get("lastSeq") instanceof Number n) {
                return n.longValue();
            }
            throw WebPathException.unsupported("the agent's evt.query answered no lastSeq");
        } catch (AgentRpcException e) {
            throw WebPathException.unsupported("the agent's evt.query failed: " + describe(e));
        }
    }

    /** The agent's {@code lastSeq} after the primitive ran; {@code before} when it cannot be read. */
    private static long agentSeqAfter(Session s, long before) throws AgentLostException {
        try {
            RpcResult r = s.agent.call("evt.query", LAST_SEQ_QUERY, query(AGENT_QUERY_MS));
            if (r.value() instanceof Map<?, ?> m && m.get("lastSeq") instanceof Number n) {
                return Math.max(before, n.longValue());
            }
        } catch (AgentRpcException e) {
            LOG.log(System.Logger.Level.DEBUG, "evt.query after a CDP call failed: " + describe(e));
        }
        return before;
    }

    private static String refOf(Session s, Place at) {
        return at.targetId() == null ? null : s.refByTarget.get(at.targetId());
    }

    static long budgetMs(String primitive, CallOptions options) {
        long dflt = "web.bitmap".equals(primitive) ? 20_000 : 10_000;
        long cap = "web.waitFlag".equals(primitive) ? 600_000 : 120_000;
        long b = options.timeoutMs() == null ? dflt : options.timeoutMs();
        return Math.max(1, Math.min(b, cap));
    }

    static AgentRpcException timeout(String primitive, long budgetMs) {
        return Shapes.error(primitive, ErrorCodes.TIMEOUT, primitive + " did not complete within " + budgetMs
                + " ms over CDP", Shapes.data("timeoutMs", budgetMs, "path", ID));
    }

    /** {@code TIMEOUT}, {@code STALE_HANDLE} ("the browser closed during the step") or {@code INTERNAL_ERROR}. */
    static AgentRpcException playwrightFailure(String primitive, PlaywrightException e, String ref) {
        if (e instanceof TimeoutError) {
            return Shapes.error(primitive, ErrorCodes.TIMEOUT, primitive + " timed out in Playwright: "
                    + CdpAttach.firstLine(e.getMessage()), Shapes.data("path", ID));
        }
        if (targetClosed(e)) {
            return Shapes.error(primitive, ErrorCodes.STALE_HANDLE, "the browser closed during the step",
                    Shapes.data("browser", ref, "path", ID));
        }
        return Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, "the CDP call failed: "
                + CdpAttach.firstLine(e.getMessage()), Shapes.data("path", ID));
    }

    static boolean targetClosed(PlaywrightException e) {
        String m = String.valueOf(e.getMessage());
        return e.getClass().getSimpleName().equals("TargetClosedError") || m.contains("Target closed")
                || m.contains("Target page, context or browser has been closed")
                || m.contains("Browser has been closed");
    }

    static boolean contextDestroyed(PlaywrightException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("Execution context was destroyed") || m.contains("Cannot find context with specified id");
    }

    // ---------------------------------------------------------------- events and the poller

    /** Maps the open pages to their target ids and watches their loads (best effort, bounded). */
    private void watchPages(Session s) {
        try {
            s.attach.run(() -> {
                s.attach.pagesOnPw();
                for (Page page : s.attach.context().pages()) {
                    watch(s, page);
                }
                s.attach.context().onPage(page -> watch(s, page));
                return null;
            }, AGENT_QUERY_MS);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "page events are not watched: " + e);
        }
    }

    /** Adds the load listeners to a page once; call only on the Playwright thread. */
    private void watch(Session s, Page page) {
        if (page == null || s.watched.put(page, Boolean.TRUE) != null) {
            return;
        }
        page.onLoad(p -> loadEvent(s, p, "LOAD_FINISHED"));
        page.onFrameNavigated(f -> {
            if (f.parentFrame() == null) {
                loadEvent(s, f.page(), "NAVIGATED");
            }
        });
    }

    /** {@code web.load} (and {@code web.ready} with {@code signal: LOAD} for a finished load) of a main frame. */
    private void loadEvent(Session s, Page page, String kind) {
        try {
            String targetId = s.attach.cachedTargetId(page);
            String ref = targetId == null ? null : s.refByTarget.get(targetId);
            String url = page.url();
            events.append("web.load", Shapes.data("browser", ref, "url", url, "kind", kind, "cdpTargetId",
                    targetId), null);
            if (kind.equals("LOAD_FINISHED")) {
                events.append("web.ready", Shapes.data("browser", ref, "url", url, "signal", "LOAD"), null);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "a page event could not be recorded: " + e);
        }
    }

    private void startPoller(Session s) {
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("skillroy-cdp-poller").factory());
        s.poller = poller;
        poller.scheduleWithFixedDelay(() -> pollOnce(s), POLL_PERIOD_MS, POLL_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * One poll: refreshes the page mapping (new pages get their target id and load listeners), then drains every
     * tapped page ({@code drain()} of the library) into {@code web.tap} and {@code web.ready} events; with no tap, a
     * browser-level protocol call that never enters a page lets Playwright deliver the page events that arrived.
     * Waits at most {@value #POLL_BUDGET_MS} ms; skipped while an abandoned operation runs; never throws.
     */
    void pollOnce() {
        Session s = session;
        if (s != null) {
            pollOnce(s);
        }
    }

    private void pollOnce(Session s) {
        if (session != s || s.attach.busy()) {
            return;
        }
        try {
            // drain() empties the page's buffer, so the events are published by the operation itself: a drain that
            // outlives this poll's bound (a page that stays busy) still publishes them when it returns, and the
            // attach stays busy until then, so no further drain is sent meanwhile.
            s.attach.run(() -> {
                List<Drained> drained = drainOnPw(s);
                if (session == s) {
                    for (Drained d : drained) {
                        publish(d);
                    }
                }
                return null;
            }, POLL_BUDGET_MS);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "a poll failed: " + e);
        }
    }

    private List<Drained> drainOnPw(Session s) {
        List<Drained> out = new ArrayList<>();
        try {
            for (Page page : s.attach.pagesOnPw().values()) {
                watch(s, page);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "the page mapping could not be refreshed: " + e);
        }
        if (s.taps.isEmpty()) {
            pump(s);
            return out;
        }
        for (Iterator<Map.Entry<Page, Tap>> it = s.taps.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Page, Tap> e = it.next();
            Page page = e.getKey();
            if (page.isClosed()) {
                it.remove();
                continue;
            }
            try {
                Object value = Shapes.unwrap("web.tap", page.evaluate(Scripts.call("drain")), e.getValue().ref);
                out.add(new Drained(e.getValue(), page.url(), value));
            } catch (AgentRpcException | PlaywrightException x) {
                LOG.log(System.Logger.Level.DEBUG, "draining a tapped page failed: " + x.getMessage());
            }
        }
        return out;
    }

    private static void pump(Session s) {
        if (s.pumpBroken) {
            return;
        }
        try {
            if (s.browserSession == null) {
                s.browserSession = s.attach.browser().newBrowserCDPSession();
            }
            s.browserSession.send("Browser.getVersion");
        } catch (RuntimeException e) {
            s.pumpBroken = true;
            LOG.log(System.Logger.Level.DEBUG, "page events are delivered only during calls: " + e);
        }
    }

    private void publish(Drained d) {
        if (!(d.value() instanceof Map<?, ?> m)) {
            return;
        }
        String ref = d.tap().ref;
        if (m.get("events") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> event) || !(event.get("name") instanceof String name)) {
                    continue;
                }
                Map<String, Object> tap = new LinkedHashMap<>();
                if (ref != null) {
                    tap.put("browser", ref);
                }
                tap.put("name", name);
                if (event.containsKey("detail")) {
                    tap.put("detail", event.get("detail"));
                }
                if (Boolean.TRUE.equals(event.get("truncated"))) {
                    tap.put("truncated", true);
                }
                events.append("web.tap", tap, null);
                events.append("web.ready", Shapes.data("browser", ref, "url", d.url(), "signal", "TAP", "name", name),
                        null);
            }
        }
        if (m.get("dropped") instanceof Number dropped && dropped.longValue() > 0) {
            LOG.log(System.Logger.Level.WARNING, "a tapped page dropped " + dropped + " events (its buffer was full)");
        }
    }

    // ---------------------------------------------------------------- closing

    private void shutdown(Session s) {
        ScheduledExecutorService poller = s.poller;
        if (poller != null) {
            poller.shutdown();
            try {
                if (!poller.awaitTermination(POLL_BUDGET_MS + 100, TimeUnit.MILLISECONDS)) {
                    poller.shutdownNow();
                }
            } catch (InterruptedException e) {
                poller.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (!s.attach.busy()) {
            try {
                s.attach.run(() -> {
                    restorePages(s);
                    return null;
                }, CLOSE_STEP_MS);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "the pages could not be restored: " + e);
            }
        }
        if (s.attach.tracing().isActive() && !s.attach.busy()) {
            try {
                s.attach.tracing().stop(null, CLOSE_STEP_MS);
            } catch (CdpException e) {
                LOG.log(System.Logger.Level.DEBUG, "the trace could not be stopped: " + e.getMessage());
            }
        }
        s.attach.close();
        deleteTree(s.traceDir);
    }

    /**
     * Removes what the path left in the application's pages: first its viewport overrides (an override outlives the
     * DevTools session that set it), then its taps; call only on the Playwright thread.
     */
    private static void restorePages(Session s) {
        for (Page page : s.overridden.keySet()) {
            try {
                if (!page.isClosed()) {
                    CdpAttach.send(s.attach.sessionOnPw(page), CLEAR_OVERRIDE);
                }
            } catch (RuntimeException x) {
                LOG.log(System.Logger.Level.DEBUG, "a viewport override could not be removed: " + x.getMessage());
            }
        }
        s.overridden.clear();
        for (Map.Entry<Page, Tap> e : s.taps.entrySet()) {
            closeQuietly(e.getValue().initScript);
            try {
                if (!e.getKey().isClosed()) {
                    e.getKey().evaluate(Scripts.call("tap", List.of(), e.getValue().target));
                }
            } catch (PlaywrightException x) {
                LOG.log(System.Logger.Level.DEBUG, "a tap could not be removed: " + x.getMessage());
            }
        }
        s.taps.clear();
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "an init script could not be removed: " + e);
        }
    }

    private static void disposeQuietly(JSHandle handle) {
        try {
            handle.dispose();
        } catch (PlaywrightException ignored) {
            // the handle dies with its document anyway
        }
    }

    // ---------------------------------------------------------------- helpers

    private static CallOptions query(long timeoutMs) {
        return CallOptions.defaults().withTimeoutMs(timeoutMs);
    }

    private static String describe(AgentRpcException e) {
        return ErrorCodes.name(e.error().code()) + ": " + e.error().message();
    }

    private static long deadline(long ms) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
    }

    static long remainingMs(long deadline) {
        return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
