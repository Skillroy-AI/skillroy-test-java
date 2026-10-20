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
import ai.skillroy.testjava.controller.SecureFiles;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.text.TextBounds;
import ai.skillroy.testjava.protocol.web.WebInput;
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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * The CDP implementation of the web verbs (phase4-contracts.md sections 5.2, 5.3 and 6.1), registered as the
 * {@code ServiceLoader} service {@code ai.skillroy.testjava.scenario.web.WebPath} with the id {@value #ID}. It serves
 * every {@code web.*} primitive of section 4.2 except {@code web.cdp.status}, {@code web.reload} and {@code
 * web.browsers} (facts of the target, always the agent's), and since Phase 7 {@code web.hitTest} and
 * {@code web.pointer} (phase7-contracts.md section 4.7; viewport points only, a component point is the agent's), with
 * the agent path's
 * parameters and result shapes: the page work is the shared script library ({@link WebLib}) evaluated through
 * Playwright, the pointer's events raw {@code Input.dispatchMouseEvent} calls on the path's own session
 * ({@link WebInput}).
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
 * <p>The Playwright line (Phase 8, phase8-contracts.md 5.3, D552 to D555): {@link ConnectSupport} says which
 * {@code connectOverCDP} options the Playwright on the class path has. Without {@code noDefaults} (before 1.60) the
 * path is not used: {@code open} returns {@link ConnectSupport#noDefaultsRefusal()} before any driver starts, unless
 * the operator set the controller's system property {@value ConnectSupport#ALLOW_PROPERTY} (read into {@link
 * CdpOptions#allowDefaultOverrides()}); without {@code artifactsDir} (before 1.61) a traced run opens untraced. The
 * {@link #openInfo()} of the {@code web.path} record names the line ({@code playwright}) and what was applied
 * ({@code connectOptions}).
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
 * trace or debug log would not), the target is in read-only mode and the primitive is mutating or evaluates a
 * caller's expression ({@link #SCRIPT}, D156), an earlier operation
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
 * <p>{@code web.waitFlag} answers as the agent does (amendment A35): the page polls the shared guard of {@link
 * WebLib#flagExpression} through {@code waitForFunction}, a poll whose expression throws counts as not ready, and a
 * wait that times out makes one more poll of the guard, bounded, whose {@code error} is the result's {@code
 * lastError}; an expression that does not compile is {@code SCRIPT_ERROR} at once.
 *
 * <h2>Masked pages (Phase 8, phase8-contracts.md 5.2 (iii) and (iv), D548, D551, D637)</h2>
 * The path keeps the agent's {@code web.browsers.masksRegistered} of its last read (an answer without the field counts
 * as true). Unless the call says {@code redact: false} (a person's argument), the four reads ({@code web.bitmap},
 * {@code web.dom.text}, {@code web.ariaSnapshot}, {@code web.hitTest}) and the three actions ({@code web.pointer},
 * {@code web.dom.click}, {@code web.dom.fill}) of a page the agent reports {@code masked}, or of a page it has no
 * entry for while masks are registered, are handed to the agent ({@code UNSUPPORTED}), which refuses the reads and
 * answers the actions without page data; a {@code by: {url|title}} selection is handed to the agent while masks are
 * registered (the agent never matches a masked browser's withheld URL or title); and the path's own events of a
 * masked page (or one without an entry while masks are registered) carry {@code url: ""}, no {@code detail} and
 * {@code masked: true}.
 *
 * <h2>Two event sequences</h2>
 * The path reads no agent sequence (amendment A36): the {@code seqBefore} and {@code seqAfter} of a call's {@link
 * RpcResult} are {@code 0}, because the executor reads the agent's sequence itself just before the call (one {@code
 * evt.query}), waits on the agent's bus from there, and writes that number in their place. Its {@code events} list is
 * empty (the path publishes nothing on the agent's bus). The path's own events ({@code web.tap}, {@code web.ready} with
 * {@code signal} {@code TAP}, {@code FLAG} or {@code LOAD}, and {@code web.load}) are numbered by a sequence of their
 * own (1, 2, 3, ...) and read with {@link #eventsSince}; {@link #lastEventSeq()} is the current end of it and {@link
 * #awaitEvent} waits for the next one.
 *
 * <h2>Taps</h2>
 * {@code web.tap} taps the selected browser's page only, as the agent path taps one browser: an init script of that
 * page ({@link WebLib#tapInstaller}, run before the page's own scripts in every new document, so the timing is
 * {@code DOCUMENT_START}) plus the immediate {@code tap} call in its current document; {@code patterns: []} removes
 * both. The poller drains the tapped pages; {@link #close()} removes the taps it left. The library's {@code drain()}
 * empties the page's buffer, so the events a drain took are published by the drain operation itself, on the
 * Playwright thread: a drain that outlives the poll's 1 s bound (a page that stays busy) still publishes them when it
 * returns, and while it runs no further drain, and no other operation, is sent. Every library call goes through the
 * guard of {@link WebLib#boundedCallExpression} ({@link Scripts#call}, read by {@link Shapes#library}); a drain whose
 * answer is refused as oversized ends that page's tap (security fix P2-L4-F2).
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
            "web.dom.fill", "web.dispatchEvent", "web.tap", "web.waitFlag", "web.resize", "web.bitmap", "web.hitTest",
            "web.pointer");

    /** The mutating ones among them (D51; {@code web.pointer} since Phase 7). */
    static final Set<String> MUTATING = Set.of("web.evaluate", "web.dom.click", "web.dom.fill",
            "web.dispatchEvent", "web.tap", "web.resize", "web.pointer");

    /**
     * The ones that evaluate a caller's expression in the page (phase6-contracts.md 12.9, D156; security fix S42):
     * refused on a read-only target as the {@link #MUTATING} ones are, so that the agent answers them
     * ({@code READ_ONLY}).
     */
    static final Set<String> SCRIPT = Set.of("web.evaluate", "web.waitFlag");

    /** The reason {@code open} gives for the policy {@code CLOSE}. */
    public static final String CLOSE_REFUSED = "frozenTargets: close is refused in Phase 4 (D52): closing a browser the"
            + " application owns can break it";

    /** Why a component point is handed to the agent (phase7-contracts.md 4.7). */
    static final String COMPONENT_POINT = "a component point is converted by the agent: the CDP path has no component"
            + " bounds";

    /** The reason {@code open} gives when the agent knows no endpoint. */
    public static final String NO_ENDPOINT = "no remote-debugging endpoint configured";

    /**
     * The {@code seqBefore} and {@code seqAfter} of every result (A36): the path reads no agent sequence; the executor
     * reads it once before the call and writes it in their place.
     */
    static final long NOT_READ = 0L;

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
    /** A35: the last poll of a timed-out {@code web.waitFlag} waits at most this long for the page. */
    static final long LAST_POLL_MAX_MS = 1_000;
    /** A35: and leaves this much of the call's budget, so that the call still answers before its own bound. */
    static final long LAST_POLL_RESERVE_MS = 100;
    static final long ACTIONABILITY_RESERVE_MS = 1_000;

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
        /**
         * The agent's {@code web.browsers.masksRegistered} at the last read (Phase 8, D548): true until a read said
         * false, and for an answer without the field (fail closed).
         */
        volatile boolean masksRegistered = true;
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
    /** The Playwright line's options ({@link ConnectSupport#detected()} unless a test replaces it). */
    private final Supplier<ConnectSupport> support;
    private final Object lock = new Object();
    private final PathEvents events = new PathEvents();
    private volatile Session session;
    private volatile boolean closed;

    /** The path as {@code ServiceLoader} creates it. */
    public CdpWebPath() {
        this(TargetProbe::run, CdpAttach::connect, true);
    }

    CdpWebPath(Prober prober, Connector connector, boolean poll) {
        this(prober, connector, poll, ConnectSupport::detected);
    }

    CdpWebPath(Prober prober, Connector connector, boolean poll, Supplier<ConnectSupport> support) {
        this.prober = Objects.requireNonNull(prober, "prober");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.poll = poll;
        this.support = Objects.requireNonNull(support, "support");
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
        // Phase 8 (D553): without noDefaults the path is not used unless the operator accepted the overrides; the
        // reason is returned before any driver starts (WebRun falls back under auto, refuses under a pinned cdp).
        ConnectSupport line = support.get();
        boolean allowOverrides = Boolean.getBoolean(ConnectSupport.ALLOW_PROPERTY);
        if (!line.noDefaults() && !allowOverrides) {
            return line.noDefaultsRefusal();
        }
        Set<String> targetIds = targetIds(status);
        boolean readOnly = Boolean.TRUE.equals(agent.ping().get("readOnly"));
        CdpOptions options = CdpOptions.defaults()
                .withConnectDeadline(settings.attachDeadline())
                .withFrozenTargets(settings.frozenTargets())
                .withAllowDefaultOverrides(allowOverrides);
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
        // Phase 8 (D553): a line without artifactsDir opens untraced, so that no live trace goes to Playwright's own
        // temporary directory (D138); saveTrace then writes nothing, which the executor records.
        if (settings.trace() && !line.artifactsDir()) {
            LOG.log(System.Logger.Level.WARNING, "web.trace is on, but the Playwright on the class path ("
                    + line.version() + ") has no artifactsDir: the CDP path runs untraced");
        }
        Path traceDir = settings.trace() && line.artifactsDir() ? createTraceDir() : null;
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
            s = new Session(agent, ctx, endpoint, attach, readOnly, openInfo(endpoint, probe, attach, line,
                    options, traceDir != null), traceDir);
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
     * {@code {endpoint, browserVersion, connectMs, targets, unresponsive, playwright, connectOptions}} of the open
     * attach for the {@code web.path} record: the endpoint as {@code host:port}, the browser's {@code /json/version},
     * the attach time, the number of page targets and of those that did not answer the last probe, and (Phase 8, A80)
     * the Playwright line's version (null when its jar names none) and {@link ConnectSupport#connectOptions what the
     * attach applied}; null when the path is not open.
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
        if (s.readOnly && (MUTATING.contains(primitive) || SCRIPT.contains(primitive))) {
            throw WebPathException.unsupported("the target is in read-only mode: the agent answers " + primitive);
        }
        if (s.attach.busy()) {
            throw WebPathException.unsupported("an earlier CDP operation has not returned (a page may be frozen)");
        }
        Map<String, Object> params = args == null ? Map.of() : args;
        long budgetMs = budgetMs(primitive, o);
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        Callable<Object> body = prepare(s, primitive, params, budgetMs, deadline, o.correlationId());
        Object value = body.call();
        // A36: the path reads no agent sequence (the executor reads it once before the call and writes it in place of
        // these, phase4-contracts.md 6.3), so seqBefore and seqAfter are NOT_READ.
        return new RpcResult(value, List.of(), NOT_READ, NOT_READ, elapsedMs(start), "controller");
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
     * A new private temporary directory for Playwright's trace ({@value #TRACE_DIR_PREFIX}...) under the system's
     * temporary directory, owner-only through {@link SecureFiles} (POSIX permissions or an owner-only ACL; P4 D59 as
     * amended by phase6-contracts.md 9.3, D138; Phase 6, built 2026-10-09, BW).
     *
     * @throws IOException when none can be made, a file system with neither view included: {@link #open} then fails,
     *     which the executor reports as {@code ENVIRONMENT} (the agent path under {@code auto}); a trace with a filled
     *     value in a page snapshot is never written where another user could read it
     */
    static Path createTraceDir() throws IOException {
        return createTraceDir(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** {@link #createTraceDir()} under {@code parent} (the tests' seam). */
    static Path createTraceDir(Path parent) throws IOException {
        try {
            return SecureFiles.createOwnerOnlyTempDirectory(parent, TRACE_DIR_PREFIX);
        } catch (IOException e) {
            throw new IOException("CDP tracing is on, but its private directory cannot be made: " + e.getMessage(), e);
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

    private static Map<String, Object> openInfo(CdpEndpoint endpoint, TargetProbe probe, CdpAttach attach,
                                                ConnectSupport line, CdpOptions options, boolean traced) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("endpoint", endpoint.toString());
        info.put("browserVersion", WebLib.cut(probe.browserVersion(), WebLib.MAX_URL_CHARS));
        info.put("connectMs", attach.connectMillis());
        info.put("targets", (long) probe.pages().size());
        info.put("unresponsive", (long) probe.unresponsive().size());
        info.put("playwright", line.version());
        info.put("connectOptions", Collections.unmodifiableMap(line.connectOptions(options, traced)));
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
            browsers = AgentBrowser.parse(agent.call("web.browsers", Map.of(), query(AGENT_QUERY_MS)).value())
                    .browsers();
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
     * ({@code "elimination"}). The fall-backs exist because the agent may not read the target id of a frozen page;
     * they apply only when at least one listed page target is one the agent reports (security fix S45), so that the
     * pages of another browser at the endpoint never decide which of the target's browsers is reloaded.
     */
    static String[] matchFrozen(TargetProbe.Target target, List<TargetProbe.Target> frozen,
                                List<TargetProbe.Target> pages, List<AgentBrowser> browsers) {
        for (AgentBrowser b : browsers) {
            if (!b.closed() && target.id().equals(b.cdpTargetId())) {
                return new String[] {b.ref(), "cdpTargetId"};
            }
        }
        boolean anyReported = false;
        for (TargetProbe.Target p : pages) {
            for (AgentBrowser b : browsers) {
                if (p.id().equals(b.cdpTargetId())) {
                    anyReported = true;
                }
            }
        }
        if (!anyReported) {
            return null;
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
                        // Phase 8 (D637): a masked or unmapped page's answer carries page data: the agent's.
                        refuseMasked(s, primitive, p, at, deadline);
                        waitActionable(at.frame(), p, deadline);
                        return Shapes.library(primitive, at.frame().evaluate(script), refOf(s, at));
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
                        return Shapes.library(primitive, at.frame().evaluate(script), refOf(s, at));
                    });
                };
            }
            case "web.tap":
                return tap(s, primitive, p, budgetMs, deadline);
            case "web.waitFlag":
                return waitFlag(s, primitive, p, budgetMs, deadline, frameKey, correlationId);
            case "web.resize":
                return resize(s, primitive, p, budgetMs, deadline);
            case "web.hitTest":
                return hitTest(s, primitive, p, budgetMs, deadline, frameKey);
            case "web.pointer":
                return pointer(s, primitive, p, budgetMs, deadline);
            case "web.bitmap": {
                long maxWidth = Params.integer(primitive, p, "maxWidth", 4096, 1, Integer.MAX_VALUE);
                return () -> {
                    Selection sel = select(s, p, deadline);
                    byte[] png = onPw(s, primitive, sel, budgetMs, deadline, () -> {
                        Place at = place(s, sel, null);
                        refuseMasked(s, primitive, p, at, deadline);
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
                refuseMasked(s, primitive, p, at, deadline);
                Object raw;
                try {
                    raw = at.frame().evaluate(script);
                } catch (PlaywrightException e) {
                    if (!contextDestroyed(e)) {
                        throw e;
                    }
                    raw = place(s, sel, frameKey).frame().evaluate(script);
                }
                Object value = Shapes.library(primitive, raw, refOf(s, at));
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
                Object value = Shapes.library(primitive, at.page().evaluate(Scripts.call("tap", patterns, on)),
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
        String predicate;
        try {
            predicate = Scripts.flagWait(expression);
        } catch (IllegalArgumentException e) {
            throw Params.invalid(primitive, "expression", "expression must not be blank");
        }
        String poll = WebLib.flagExpression(expression);
        return () -> {
            Selection sel = select(s, p, deadline);
            Object[] result = onPw(s, primitive, sel, budgetMs, deadline, () -> {
                Place at = place(s, sel, frameKey);
                long wait = Math.max(1, Math.min(wanted, remainingMs(deadline) - WAIT_FLAG_MARGIN_MS));
                long t0 = System.nanoTime();
                Map<?, ?> outcome;
                try {
                    // A35: the page polls the shared guard; a poll that throws is not ready, Playwright polls on.
                    outcome = flagPoll(primitive, at.frame().waitForFunction(predicate, null,
                            new Frame.WaitForFunctionOptions().setPollingInterval(pollMs).setTimeout(wait)));
                } catch (TimeoutError e) {
                    outcome = lastPoll(primitive, at.frame(), poll, pollMs, deadline);
                }
                if (outcome != null && outcome.get("compileError") != null) {
                    throw Shapes.scriptError(primitive, Shapes.data("message",
                            String.valueOf(outcome.get("compileError")), "stack", ""), refOf(s, at));
                }
                boolean ready = outcome != null && Boolean.TRUE.equals(outcome.get("ready"));
                // S43: the guard cuts its line, but a page can break the string functions the guard calls.
                String lastError = !ready && outcome != null && outcome.get("error") instanceof String error
                        ? WebLib.errorLine(error) : null;
                return new Object[] {ready, elapsedMs(t0), WebLib.cut(at.page().url(), WebLib.MAX_URL_CHARS),
                        refOf(s, at), lastError};
            });
            boolean ready = (Boolean) result[0];
            if (ready && result[3] != null) {
                if (eventsMasked(s, (String) result[3])) {
                    events.append("web.ready", Shapes.data("browser", result[3], "url", "", "signal", "FLAG",
                            "masked", true), correlationId);
                } else {
                    events.append("web.ready", Shapes.data("browser", result[3], "url", result[2], "signal",
                            "FLAG"), correlationId);
                }
            }
            return Shapes.waitFlagValue(ready, (Long) result[1], pollMs, (String) result[4]);
        };
    }

    /**
     * A35: after a timed-out wait, one more poll of the guard, the last poll, which says why the flag is not ready
     * ({@code error}) or that it turned ready meanwhile; bounded by {@value #LAST_POLL_MAX_MS} ms and by what is left of
     * the call less {@value #LAST_POLL_RESERVE_MS} ms, through {@code waitForFunction} (the guard always answers an
     * object, so its first evaluation ends it), because {@code evaluate} has no bound of its own. Null when no time is
     * left, the page did not answer in time, or the poll failed otherwise: it only explains a wait that is over, so
     * only a page that closed (thrown on, {@code STALE_HANDLE}) changes the answer. Call only on the Playwright thread.
     */
    private static Map<?, ?> lastPoll(String primitive, Frame frame, String poll, long pollMs, long deadline)
            throws AgentRpcException {
        long bound = Math.min(LAST_POLL_MAX_MS, remainingMs(deadline) - LAST_POLL_RESERVE_MS);
        if (bound < 1) {
            return null;
        }
        try {
            return flagPoll(primitive, frame.waitForFunction(poll, null,
                    new Frame.WaitForFunctionOptions().setPollingInterval(pollMs).setTimeout(bound)));
        } catch (PlaywrightException e) {
            if (targetClosed(e)) {
                throw e;
            }
            return null;
        }
    }

    /** The guard's {@code {ready, error?, compileError?}} behind a handle, which is disposed. */
    private static Map<?, ?> flagPoll(String primitive, JSHandle handle) throws AgentRpcException {
        Object value = Values.json(handle.jsonValue());
        disposeQuietly(handle);
        if (TextBounds.exceeds(value, WebLib.MAX_CHANNEL_CHARS)) {
            throw Shapes.oversized(primitive, "the web.waitFlag guard's answer", WebLib.MAX_CHANNEL_CHARS);
        }
        if (!(value instanceof Map<?, ?> m) || !(m.get("ready") instanceof Boolean)) {
            throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, "the web.waitFlag guard returned no result",
                    Shapes.data("path", ID));
        }
        return m;
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

    // ---------------------------------------------------------------- hit-test and pointer (Phase 7)

    /**
     * {@code web.hitTest} (phase7-contracts.md sections 4.2 and 4.7): the library's {@code hit} on the place's frame,
     * descending into a frame element's child frame as the agent does ({@link WebInput#childFrame} over the page's
     * {@code Page.getFrameTree}, at most {@link WebInput#MAX_FRAME_DEPTH} times), every rectangle in the viewport of
     * the frame the call started in, the shape of {@link WebInput#hitResult}. A component point is the agent's (the
     * path has no component bounds): {@code UNSUPPORTED} before anything reached the page.
     */
    private Callable<Object> hitTest(Session s, String primitive, Map<String, Object> p, long budgetMs, long deadline,
                                     Object frameKey) throws AgentRpcException, WebPathException {
        WebInput.Target target;
        try {
            target = WebInput.target(p);
        } catch (WebInput.Invalid e) {
            throw Params.invalid(primitive, e.param(), e.getMessage());
        }
        if (target.component()) {
            throw WebPathException.unsupported(COMPONENT_POINT);
        }
        return () -> {
            Selection sel = select(s, p, deadline);
            return onPw(s, primitive, sel, budgetMs, deadline, () -> {
                Place at = place(s, sel, frameKey);
                refuseMasked(s, primitive, p, at, deadline);
                String ref = refOf(s, at);
                List<WebInput.Frame> frames = frameList(s, at.page());
                String foundIn = frameKey instanceof String key ? key : frames.isEmpty() ? null : frames.get(0).key();
                WebInput.Hit hit = hitIn(primitive, at.frame(), target.x(), target.y(), ref);
                int px = target.x();
                int py = target.y();
                int offX = 0;
                int offY = 0;
                String iframeKey = null;
                Map<String, Object> iframeRect = null;
                for (int depth = 0; hit.child() != null && depth < WebInput.MAX_FRAME_DEPTH; depth++) {
                    WebInput.Child child = hit.child();
                    String childKey = WebInput.childFrame(frames, foundIn, child);
                    if (childKey == null) {
                        break;
                    }
                    WebInput.Hit inner;
                    try {
                        inner = hitIn(primitive, frame(s, at.page(), childKey), child.x(), child.y(), ref);
                    } catch (WebPathException e) {
                        break;
                    } catch (AgentRpcException e) {
                        int code = e.error().code();
                        if (code == ErrorCodes.NOT_FOUND || code == ErrorCodes.SCRIPT_ERROR) {
                            break;
                        }
                        throw e;
                    }
                    iframeKey = childKey;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) hit.translated(offX, offY).element().get("rect");
                    iframeRect = r;
                    offX += px - child.x();
                    offY += py - child.y();
                    px = child.x();
                    py = child.y();
                    foundIn = childKey;
                    hit = inner;
                }
                return WebInput.hitResult(ref, foundIn, target.x(), target.y(), offX == 0 && offY == 0 ? hit
                        : hit.translated(offX, offY), iframeKey, iframeRect, null);
            });
        };
    }

    /**
     * {@code web.pointer} (phase7-contracts.md sections 4.3 and 4.7): the agent's {@code web.browsers} read once more
     * for the browser's modal state and visibility (a browser the agent reports blocked by a modal dialog is handed to
     * the agent, which answers {@code MODAL_OPEN}, unless {@code force}); the library's {@code hit} at the point (a
     * point outside the viewport is {@code NOT_FOUND} and nothing is sent); then the events of {@link WebInput#plan},
     * each an {@code Input.dispatchMouseEvent} with {@link WebInput#mouseEvent}'s parameters on the path's own session
     * of the page (D64's precedent; Playwright's {@code page.mouse()} is not used, D335), awaited in turn and never
     * sent again; after a failure that follows a press, one release of that button. A component point is the agent's.
     */
    private Callable<Object> pointer(Session s, String primitive, Map<String, Object> p, long budgetMs, long deadline)
            throws AgentRpcException, WebPathException {
        WebInput.Pointer ptr;
        try {
            ptr = WebInput.pointer(p);
        } catch (WebInput.Invalid e) {
            throw Params.invalid(primitive, e.param(), e.getMessage());
        }
        if (ptr.target().component()) {
            throw WebPathException.unsupported(COMPONENT_POINT);
        }
        int x = ptr.target().x();
        int y = ptr.target().y();
        return () -> {
            Selection sel = select(s, p, deadline);
            return onPw(s, primitive, sel, budgetMs, deadline, () -> {
                Place at = place(s, sel, null);
                // Phase 8 (D637): the hit of a masked or unmapped page is page data: the agent answers without it.
                refuseMasked(s, primitive, p, at, deadline);
                String ref = refOf(s, at);
                Map<String, Object> hit = null;
                try {
                    hit = hitIn(primitive, at.page().mainFrame(), x, y, ref).summary();
                } catch (AgentRpcException e) {
                    int code = e.error().code();
                    if (code == ErrorCodes.NOT_FOUND || code == ErrorCodes.STALE_HANDLE) {
                        throw e;
                    }
                } catch (PlaywrightException e) {
                    if (targetClosed(e)) {
                        throw e;
                    }
                }
                if (x < 0 || y < 0) {
                    throw Shapes.error(primitive, ErrorCodes.NOT_FOUND, "the point " + x + "," + y + " lies outside"
                            + " the viewport or hits no element", Shapes.data("reason", "outside", "x", (long) x, "y",
                            (long) y, "browser", ref));
                }
                // Phase 7 security fix S133 (4.7): the modal check after the page's hit, immediately before the first
                // event, so that a page that stalls its own hit cannot make it stale; nothing after the deadline.
                requireTime(primitive, budgetMs, deadline);
                refreshBrowsers(s, deadline);
                AgentBrowser browser = ref == null ? null : s.byRef.get(ref);
                if (browser != null && browser.blocked() && !ptr.force()) {
                    throw WebPathException.unsupported("a modal dialog blocks the window of browser " + ref
                            + ": the agent answers web.pointer");
                }
                CDPSession session = s.attach.sessionOnPw(at.page());
                List<String> dispatched = new ArrayList<>();
                WebInput.Step held = null;
                try {
                    for (WebInput.Step step : WebInput.plan(ptr)) {
                        // S133: no event once the caller's deadline has passed (it has its TIMEOUT answer already).
                        requireTime(primitive, budgetMs, deadline);
                        CdpAttach.send(session, WebInput.MOUSE_EVENT, WebInput.mouseEvent(step, x, y, ptr));
                        dispatched.add(step.type());
                        if (WebInput.PRESSED.equals(step.type())) {
                            held = step;
                        } else if (WebInput.RELEASED.equals(step.type())) {
                            held = null;
                        }
                    }
                } catch (RuntimeException | AgentRpcException e) {
                    if (held != null) {
                        try {
                            CdpAttach.send(session, WebInput.MOUSE_EVENT, WebInput.mouseEvent(WebInput.RELEASED, x, y,
                                    held.button(), Math.max(1, held.clickCount()), ptr.modifierBits(), 0, 0));
                        } catch (RuntimeException ignored) {
                            // the failure is what the call answers
                        }
                    }
                    throw e;
                }
                return WebInput.pointerResult("PROTOCOL", x, y, dispatched, hit, browser == null
                        || browser.visibleInSwing());
            });
        };
    }

    /**
     * Phase 7 security fix S133 (4.7): {@code TIMEOUT} when the call's deadline has passed, so that an operation its
     * caller gave up on (it answered {@code TIMEOUT} already) sends nothing more; the pointer's failure path then
     * balances a press already sent with one release.
     */
    private static void requireTime(String primitive, long budgetMs, long deadline) throws AgentRpcException {
        if (remainingMs(deadline) <= 0) {
            throw timeout(primitive, budgetMs);
        }
    }

    /**
     * Phase 7 security fix S111 (P7 3.6, 4.8; D404's method) and Phase 8 (phase8-contracts.md 5.2 (iii), (iv), D551,
     * D637): the agent's {@code web.browsers} read once more, and a browser it reports {@code masked} (a pack's mask
     * names it, or it has no view while a mask is registered) is handed to the agent ({@code UNSUPPORTED}), which
     * refuses the read {@code INVALID_PARAMS masked} or answers the action without page data, unless {@code redact:
     * false} (a person's argument, which {@code CallPolicy} refuses for a model or a client); so is a page the agent
     * has no entry for while it reports masks registered (an absent {@code masksRegistered} counts as true). A
     * {@code web.browsers} that fails hands the call to the agent too. Used by the four reads and the three actions
     * that stay allowed on a masked browser; call only on the Playwright thread.
     */
    private static void refuseMasked(Session s, String primitive, Map<String, Object> p, Place at, long deadline)
            throws WebPathException, AgentLostException {
        if (unredacted(p)) {
            return;
        }
        refreshBrowsers(s, deadline);
        String ref = refOf(s, at);
        AgentBrowser browser = ref == null ? null : s.byRef.get(ref);
        if (browser != null && browser.masked()) {
            throw WebPathException.unsupported("browser " + ref + " is masked: the agent answers " + primitive);
        }
        if (browser == null && s.masksRegistered) {
            throw WebPathException.unsupported("the page maps to no browser of the agent while masks are registered"
                    + " (it may be a masked browser without a view): the agent answers " + primitive);
        }
    }

    /** True when the call asks for the page unmasked: {@code redact} is {@code false} (or the text "false"). */
    static boolean unredacted(Map<String, Object> p) {
        Object redact = p.get("redact");
        return Boolean.FALSE.equals(redact) || redact instanceof String text && "false".equalsIgnoreCase(text.strip());
    }

    /**
     * Whether the path's own events of the browser behind {@code ref} withhold page data (Phase 8, phase8-contracts.md
     * 5.2 (iii)): the agent reported it masked, or it has no entry while masks are registered.
     */
    private static boolean eventsMasked(Session s, String ref) {
        AgentBrowser b = ref == null ? null : s.byRef.get(ref);
        return b == null ? s.masksRegistered : b.masked();
    }

    /** The library's {@code hit} in a frame, read and bounded as the agent reads it; call on the Playwright thread. */
    private static WebInput.Hit hitIn(String primitive, Frame frame, int x, int y, String ref)
            throws AgentRpcException {
        String script = Scripts.call("hit", (long) x, (long) y);
        Object raw;
        try {
            raw = frame.evaluate(script);
        } catch (PlaywrightException e) {
            if (!contextDestroyed(e)) {
                throw e;
            }
            raw = frame.evaluate(script);
        }
        Object value = Shapes.library(primitive, raw, ref);
        try {
            return WebInput.hit(value);
        } catch (WebInput.Malformed m) {
            throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, m.getMessage() + " (browser " + ref + "; a page"
                    + " may replace the script library)", Shapes.data("browser", ref));
        }
    }

    /**
     * The page's frames from {@code Page.getFrameTree} (key, parent, name, URL), main first; call only on the
     * Playwright thread.
     */
    private static List<WebInput.Frame> frameList(Session s, Page page) {
        Map<String, Object> tree = CdpAttach.send(s.attach.sessionOnPw(page), "Page.getFrameTree");
        List<WebInput.Frame> out = new ArrayList<>();
        if (tree.get("frameTree") instanceof Map<?, ?> root) {
            collectFrames(root, null, out);
        }
        return out;
    }

    private static void collectFrames(Map<?, ?> node, String parent, List<WebInput.Frame> out) {
        if (!(node.get("frame") instanceof Map<?, ?> f) || !(f.get("id") instanceof String id) || id.isEmpty()) {
            return;
        }
        out.add(new WebInput.Frame(id, parent, f.get("name") instanceof String n ? n : "",
                f.get("url") instanceof String u ? u : ""));
        if (node.get("childFrames") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> c) {
                    collectFrames(c, id, out);
                }
            }
        }
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
        } catch (Selection.RegexStopped e) {
            // S46: INVALID_PARAMS, as the agent answers it, so that the executor does not hand the same pattern on.
            throw Shapes.error(primitive, ErrorCodes.INVALID_PARAMS, e.getMessage(), Shapes.data("param", e.param(),
                    "reason", e.reason(), "path", ID));
        } catch (RuntimeException e) {
            throw Shapes.error(primitive, ErrorCodes.INTERNAL_ERROR, "the CDP path failed: " + e,
                    Shapes.data("path", ID));
        }
    }

    /**
     * The page and frame of a selection; call only on the Playwright thread. A page picked by {@code by.url} or
     * {@code by.title} must carry a target id the agent reports ({@code web.browsers}, read once more when it is not
     * among the known refs), else the call is {@code UNSUPPORTED} and the agent serves it: the path acts only on the
     * target's pages, never on another browser's at the same endpoint (security fix S45, phase4-contracts.md 5.2).
     */
    private Place place(Session s, Selection sel, Object frameKey) throws WebPathException, AgentLostException {
        Map<String, Page> pages = s.attach.pagesOnPw();
        String targetId = sel.pick(pages, Page::url, Page::title);
        if (sel.targetId() == null && !s.refByTarget.containsKey(targetId)) {
            refreshBrowsers(s, sel.deadlineNanos() != 0L ? sel.deadlineNanos() : deadline(AGENT_QUERY_MS));
            if (!s.refByTarget.containsKey(targetId)) {
                throw WebPathException.unsupported("no browser of the target has the CDP page matching " + sel);
            }
        }
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
        if (p.get("browser") == null && p.get("by") instanceof Map<?, ?> by
                && (by.containsKey("url") || by.containsKey("title"))) {
            // Phase 8 (D551): a url or title selection is the agent's while masks are registered: the pages' own
            // values would select a masked browser by its withheld URL or title (the agent never matches one).
            refreshBrowsers(s, deadline);
            if (s.masksRegistered) {
                throw WebPathException.unsupported("a by." + (by.containsKey("url") ? "url" : "title") + " selection"
                        + " is the agent's while masks are registered");
            }
        }
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
        }, deadline);
    }

    /** Reads the agent's {@code web.browsers} and refreshes the ref caches; a failure is {@code UNSUPPORTED}. */
    private static List<AgentBrowser> refreshBrowsers(Session s, long deadline)
            throws WebPathException, AgentLostException {
        AgentBrowser.Listing listing;
        try {
            listing = AgentBrowser.parse(s.agent.call("web.browsers", Map.of(),
                    query(Math.max(1, Math.min(AGENT_QUERY_MS, remainingMs(deadline))))).value());
        } catch (AgentRpcException e) {
            throw WebPathException.unsupported("web.browsers failed: " + describe(e));
        }
        List<AgentBrowser> browsers = listing.browsers();
        s.masksRegistered = listing.masksRegistered();
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

    /**
     * {@code web.load} (and {@code web.ready} with {@code signal: LOAD} for a finished load) of a main frame; only for a
     * page whose target id the agent reports (security fix S45), with its URL cut at {@link WebLib#MAX_URL_CHARS}
     * (S43).
     */
    private void loadEvent(Session s, Page page, String kind) {
        try {
            String targetId = s.attach.cachedTargetId(page);
            String ref = targetId == null ? null : s.refByTarget.get(targetId);
            if (ref == null) {
                return;
            }
            if (eventsMasked(s, ref)) {
                // Phase 8 (D551): a masked page's own events withhold its URL.
                events.append("web.load", Shapes.data("browser", ref, "url", "", "kind", kind, "cdpTargetId",
                        targetId, "masked", true), null);
                if (kind.equals("LOAD_FINISHED")) {
                    events.append("web.ready", Shapes.data("browser", ref, "url", "", "signal", "LOAD", "masked",
                            true), null);
                }
                return;
            }
            String url = WebLib.cut(page.url(), WebLib.MAX_URL_CHARS);
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
                        publish(s, d);
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
                Object value = Shapes.library("web.tap", page.evaluate(Scripts.call("drain", WebLib.DRAIN_CHARS)),
                        e.getValue().ref);
                out.add(new Drained(e.getValue(), page.url(), value));
            } catch (AgentRpcException x) {
                if (Shapes.isOversized(x)) {
                    // P2-L4-F2: an oversized drain ends the page's tap, logged once; the next web.tap arms it again.
                    Tap tap = e.getValue(); // read before the removal: an IdentityHashMap entry is undefined after it
                    it.remove();
                    endTap(page, tap);
                } else {
                    LOG.log(System.Logger.Level.DEBUG, "draining a tapped page failed: " + x.getMessage());
                }
            } catch (PlaywrightException x) {
                LOG.log(System.Logger.Level.DEBUG, "draining a tapped page failed: " + x.getMessage());
            }
        }
        return out;
    }

    /**
     * Ends a tap whose drain answer was refused as oversized (security fix P2-L4-F2; the caller removed it from the
     * session): its init script is removed and the tap is cleared in the page's current document, best effort, and a
     * warning is logged; so a page that answers oversized drains costs one bounded answer per {@code web.tap}, not one
     * every {@value #POLL_PERIOD_MS} ms. Call only on the Playwright thread.
     */
    private static void endTap(Page page, Tap tap) {
        closeQuietly(tap.initScript);
        try {
            if (!page.isClosed()) {
                page.evaluate(Scripts.call("tap", List.of(), tap.target));
            }
        } catch (PlaywrightException x) {
            LOG.log(System.Logger.Level.DEBUG, "a tap could not be cleared: " + x.getMessage());
        }
        LOG.log(System.Logger.Level.WARNING, "the tap of browser " + tap.ref + " was removed: a drain answer exceeded "
                + WebLib.MAX_JSON_CHARS + " characters (a page may replace the script library); the next web.tap "
                + "arms it again");
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

    /**
     * Appends a drain's events: only for a page whose target id the agent reports (security fix S45), each
     * {@code detail} whose JSON text exceeds {@link WebLib#MAX_DETAIL_CHARS} kept as that text cut, with
     * {@code truncated: true}, and the event name and URL cut at {@link WebLib#MAX_URL_CHARS} (S43), whatever the
     * page's library did.
     */
    private void publish(Session s, Drained d) {
        if (!(d.value() instanceof Map<?, ?> m)) {
            return;
        }
        String ref = d.tap().targetId == null ? null : s.refByTarget.get(d.tap().targetId);
        if (ref == null) {
            return;
        }
        boolean masked = eventsMasked(s, ref);
        String url = masked ? "" : WebLib.cut(d.url(), WebLib.MAX_URL_CHARS);
        if (m.get("events") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> event) || !(event.get("name") instanceof String raw)) {
                    continue;
                }
                String name = WebLib.cut(raw, WebLib.MAX_URL_CHARS);
                Map<String, Object> tap = new LinkedHashMap<>();
                tap.put("browser", ref);
                tap.put("name", name);
                if (masked) {
                    // Phase 8 (D551, D549's shape): the name a step waits on stays, the detail is withheld.
                    tap.put("masked", true);
                    events.append("web.tap", tap, null);
                    events.append("web.ready", Shapes.data("browser", ref, "url", "", "signal", "TAP", "name", name,
                            "masked", true), null);
                    continue;
                }
                boolean truncated = Boolean.TRUE.equals(event.get("truncated"));
                if (event.containsKey("detail")) {
                    Object detail = event.get("detail");
                    if (WebLib.detailTooLong(detail)) {
                        detail = WebLib.cutDetail(detail);
                        truncated = true;
                    }
                    tap.put("detail", detail);
                }
                if (truncated) {
                    tap.put("truncated", true);
                }
                events.append("web.tap", tap, null);
                events.append("web.ready", Shapes.data("browser", ref, "url", url, "signal", "TAP", "name", name),
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
