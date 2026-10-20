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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.web.BitmapSource;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The thirteen {@code web.*} primitives of phase4-contracts.md section 4.2 and the two of phase7-contracts.md sections
 * 4.2 and 4.3 ({@code web.hitTest}, a read, and {@code web.pointer}, mutating; their bodies are {@link Pointing}'s),
 * written once against the embedded-browser SPI. Every one runs on {@link ThreadPolicy#WORKER}; the six reads ({@code
 * web.browsers}, {@code web.ariaSnapshot}, {@code web.dom.text}, {@code web.waitFlag}, {@code web.bitmap}, {@code
 * web.cdp.status}) are not mutating and are served in read-only mode (D51), except {@code web.waitFlag}, which with
 * {@code web.evaluate} carries the {@code script} flag (it evaluates the caller's expression) and is refused in
 * read-only mode by the dispatcher (phase6-contracts.md 12.9, D156); the others are mutating. Browser selection follows
 * {@link Selection}; every adapter future is awaited with the call's remaining budget less 100 ms ({@link Await}); a
 * read is retried once after a load event, a mutating call never. The script library's functions are called through
 * {@link WebLib#callExpression} with the argument objects of the library's specification, and an error object it
 * returns ({@code __error}) answers the RPC error of that code with its data (plus {@code data.browser}).
 *
 * <p>No call logs its arguments: a {@code web.dom.fill} text named in {@code _meta.secretParams} is never logged
 * or echoed (the result carries the count of characters typed only), and the values every call names in
 * {@code _meta.secretParams} are remembered for the masking of console output ({@link Secrets}).
 *
 * <p>Thread-safe: no mutable state of its own.
 */
final class WebPrimitives {

    /** {@code web.console} text is cut at this many characters (A34). */
    static final int CONSOLE_TEXT_MAX = 2000;
    /** Console text is masked over this many characters beyond the cut, so that the cut never splits a secret. */
    static final int CONSOLE_REDACTION_MARGIN = 4096;
    static final int DEFAULT_MAX_NODES = 2000;
    static final int MAX_NODES_CAP = 20000;
    static final int DEFAULT_MAX_CHARS = 10000;
    static final int MAX_CHARS_CAP = 100000;
    static final int DEFAULT_MAX_WIDTH = 4096;
    static final int MAX_VIEWPORT = 16384;
    static final long DEFAULT_POLL_MS = 50;
    static final long MIN_POLL_MS = 20;
    static final long MAX_WAIT_MS = 600000;
    /** The bound of reads that {@code web.browsers} makes per browser (title, CDP target id: D55). */
    static final long PER_BROWSER_READ_MS = 2000;

    private static final String SELECT_DOC = "select the browser by {url: regex} | {tag} | {title: regex} | "
            + "{nativeId} (every key given must match); default: the single browser whose component is showing";
    private static final String ANY_JSON = "string|number|integer|boolean|object|array|null";
    /**
     * The {@code redact} parameter of the four reads a mask withholds (Phase 7 security fix S111; Phase 8, gap 110: a
     * {@code BrowserMask} or the view-less rule masks too).
     */
    private static final String REDACT_DOC = "false reads a masked browser (a pack's CaptureMask or BrowserMask names "
            + "it, or it has no view while a mask is registered), which is otherwise refused INVALID_PARAMS "
            + "{reason: masked} (default true; a person's argument)";
    /**
     * The {@code redact} parameter of {@code web.pointer}, which stays allowed on a masked browser (Phase 8, D637;
     * phase8-contracts.md 5.2 (iv)): its answer carries no page data unless it is false.
     */
    private static final String REDACT_ACTION_DOC = "false keeps the page data of the answer (the hit) on a masked "
            + "browser, which otherwise answers without it (default true; a person's argument)";
    /**
     * The {@code redact} parameter of the three actions that evaluate the caller's locator in the page (Phase 8
     * security fix S165, FE; phase8-contracts.md 5.2 (iv)): on a masked browser they are refused before the page is
     * evaluated, since whether a locator matched would reveal the page, unless it is false.
     */
    private static final String REDACT_LOCATOR_DOC = "false acts on a masked browser (a pack's CaptureMask or "
            + "BrowserMask names it, or it has no view while a mask is registered), which is otherwise refused "
            + "INVALID_PARAMS {reason: masked} before its page evaluates the locator (whether a locator matched would "
            + "reveal the page; web.pointer at a point stays allowed) (default true; a person's argument)";

    private final AgentContext ctx;
    private final BrowserIndex index;
    private final Selection selection;
    private final EventBridge bridge;
    private final Taps taps;
    private final Secrets secrets;
    private final WebCapabilities capabilities;
    private final Pointing pointing;
    private final SeedWalk walk;

    WebPrimitives(AgentContext ctx, BrowserIndex index, Selection selection, EventBridge bridge, Taps taps,
                  Secrets secrets, WebCapabilities capabilities, SeedWalk walk) {
        this.ctx = ctx;
        this.index = index;
        this.selection = selection;
        this.bridge = bridge;
        this.taps = taps;
        this.secrets = secrets;
        this.capabilities = capabilities;
        this.walk = walk;
        this.pointing = new Pointing(ctx, walk, secrets, this);
    }

    /** Every primitive of the module, in name order. Touches no AWT and no vendor class. */
    List<Primitive> all() {
        List<Primitive> list = new ArrayList<Primitive>();
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.ariaSnapshot")
                .description("The accessibility tree of the page (role, accessible name, states), as the script "
                        + "library's walker computes it over the DOM. Not mutating."), true)
                .param("redact", "boolean", false, REDACT_DOC)
                .param("maxNodes", "integer", false, "at most this many nodes (default 2000, cap 20000)")
                .param("interestingOnly", "boolean", false, "drop generic and presentational nodes, hoisting "
                        + "their children (default true)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVALUATE).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return ariaSnapshot(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("web.bitmap")
                .description("A PNG of the page as rendered, independent of whether its component is showing "
                        + "(PROTOCOL: a DevTools screenshot; VENDOR: the product's own bitmap). Not mutating.")
                .param("browser", "string", false, "ref of the browser (from web.browsers)")
                .param("by", "object", false, SELECT_DOC)
                .param("redact", "boolean", false, REDACT_DOC)
                .param("maxWidth", "integer", false, "scale down above this width, keeping the aspect ratio "
                        + "(default 4096)")
                .thread(ThreadPolicy.WORKER).timeoutMs(20000).capability(WebCapabilities.BITMAP).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws IOException {
                        return bitmap(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("web.browsers")
                .description("The embedded browsers the agent can reach: rebuilds the index (seeds from registered "
                        + "BrowserRegistryAdapters and the Swing tree, then every present adapter) and lists each "
                        + "with its ref, adapter, URL, title, component ref, rendering, viewport, capabilities, CDP "
                        + "target id, tag, frames and, while a modal dialog blocks its window, blockedBy. Not "
                        + "mutating.")
                .param("includeClosed", "boolean", false, "also list browsers that have closed (default false)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.BROWSERS).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return browsers(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("web.cdp.status")
                .description("The remote-debugging endpoints the adapters report as configured at engine start "
                        + "(nothing is probed over the network) and each browser's CDP target id. Not mutating.")
                .thread(ThreadPolicy.WORKER).timeoutMs(5000).capability(WebCapabilities.BROWSERS).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return cdpStatus(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.dispatchEvent")
                .description("Dispatches a CustomEvent {detail, bubbles: true} on the target (window by default)."),
                true)
                .param("name", "string", true, "the event's name")
                .param("detail", ANY_JSON, false, "the event's detail")
                .param("target", "string", false, "CSS selector of the target element; default window")
                .param("redact", "boolean", false, REDACT_LOCATOR_DOC)
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVALUATE).mutating()
                .build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return dispatchEvent(inv);
                    }
                }));
        list.add(Primitives.of(locating(selecting(PrimitiveSpec.builder("web.dom.click")
                .description("Clicks an element with el.click() after a visibility check (a DOM click, not a "
                        + "pointer click: no pointer or mouse events are synthesised). One of selector, or role "
                        + "and name (and index)."), true))
                .param("redact", "boolean", false, REDACT_LOCATOR_DOC)
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVALUATE).mutating()
                .build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return domClick(inv);
                    }
                }));
        list.add(Primitives.of(locating(selecting(PrimitiveSpec.builder("web.dom.fill")
                .description("Fills an element: focus, the value through the native setter (so that framework "
                        + "bindings see it), input and change events, and Enter as keydown/keyup when asked. The "
                        + "result counts the characters typed; the text is never echoed."), true))
                .param("text", "string", true, "the text to fill (name it in _meta.secretParams when secret)")
                .param("clear", "boolean", false, "replace the current value (default true)")
                .param("enter", "boolean", false, "press Enter afterwards (default false)")
                .param("redact", "boolean", false, REDACT_LOCATOR_DOC)
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVALUATE).mutating()
                .build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return domFill(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.dom.text")
                .description("The innerText of an element (the body by default). Not mutating."), true)
                .param("redact", "boolean", false, REDACT_DOC)
                .param("selector", "string", false, "CSS selector (default body)")
                .param("maxChars", "integer", false, "cut the text at this many characters (default 10000, cap "
                        + "100000)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVALUATE).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return domText(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.evaluate")
                .description("Evaluates a JavaScript expression in the page and returns its value as JSON text "
                        + "{json, typeofResult} (undefined as null, functions and symbols as their String(), DOM "
                        + "nodes as {nodeName, id, className}, cycles as \"[cycle]\", cut at 1 MiB with "
                        + "truncated: true). A thrown error answers SCRIPT_ERROR. Mutating; runs the caller's script: "
                        + "refused in read-only mode."), true)
                .param("expression", "string", true, "a JavaScript expression (not statements)")
                .param("awaitPromise", "boolean", false, "await the value when it is a promise (default false)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVALUATE).mutating()
                .script().build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return evaluate(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.hitTest")
                .description("The element under a point of the page (phase7-contracts.md 4.2): role, accessible name, "
                        + "tag, id and client rectangle, and the locators ({role, name}, then {selector}) that match "
                        + "exactly that element; descends into iframes (depth 3). Not mutating."), true)
                .param("point", "object", true, "{x, y}: CSS pixels of the viewport, or of the browser's component "
                        + "with space: COMPONENT")
                .param("space", "string", false, "VIEWPORT (default) or COMPONENT (the agent converts)")
                .param("redact", "boolean", false, REDACT_DOC)
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.HITTEST).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return pointing.hitTest(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.pointer")
                .description("Synthetic pointer input at a point of the page (phase7-contracts.md 4.3): MOVE, PRESS, "
                        + "RELEASE, CLICK (a move, a press and a release in one call), DOUBLE_CLICK, WHEEL. Refused "
                        + "with MODAL_OPEN while a modal dialog blocks the browser's window, unless force (a person's "
                        + "argument). Mutating."), false)
                .param("action", "string", true, "MOVE | PRESS | RELEASE | CLICK | DOUBLE_CLICK | WHEEL")
                .param("point", "object", true, "{x, y}: CSS pixels of the viewport, or of the browser's component "
                        + "with space: COMPONENT")
                .param("space", "string", false, "VIEWPORT (default) or COMPONENT")
                .param("button", "string", false, "LEFT (default) | MIDDLE | RIGHT")
                .param("modifiers", "array", false, "any of ALT, CTRL, META, SHIFT")
                .param("clickCount", "integer", false, "1..3 (default 1; DOUBLE_CLICK 2)")
                .param("wheel", "object", false, "WHEEL: {dx, dy} in CSS pixels")
                .param("force", "boolean", false, "act although a modal dialog blocks the window (default false)")
                .param("redact", "boolean", false, REDACT_ACTION_DOC)
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.POINTER).mutating().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return pointing.pointer(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.reload")
                .description("Reloads the browser's page through the technology where its adapter can, else through "
                        + "the page's location.reload() (for frozen-target recovery and a pack's own recovery; no "
                        + "base verb emits it). Answers once the reload was requested."), false)
                .param("ignoreCache", "boolean", false, "bypass the cache (default false)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.BROWSERS).mutating()
                .build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return reload(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.resize")
                .description("Changes the page's layout viewport with a protocol-level override (applied: "
                        + "OVERRIDE), without touching Swing; clear: true removes the override."), false)
                .param("width", "integer", false, "CSS pixels, 1..16384")
                .param("height", "integer", false, "CSS pixels, 1..16384")
                .param("clear", "boolean", false, "remove the override instead")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.RESIZE).mutating().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return resize(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.tap")
                .description("Taps page events: an init script that listens for the named events (exact names, or "
                        + "globs such as app.*) in every new document, armed in the current one too; each event is "
                        + "published as web.tap {browser, name, detail?} and web.ready {signal: TAP}. Re-installing "
                        + "replaces the patterns; patterns: [] removes the tap."), false)
                .param("patterns", "array", true, "event names or globs")
                .param("target", "string", false, "window (default) or document")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVENTS).mutating().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return tap(inv);
                    }
                }));
        list.add(Primitives.of(selecting(PrimitiveSpec.builder("web.waitFlag")
                .description("Evaluates the expression on the agent worker until it is truthy or the budget ends; "
                        + "publishes web.ready {signal: FLAG} when it turns ready. A poll whose expression throws "
                        + "counts as not ready; answers {ready: false, lastError?} (the last poll's error) before the "
                        + "call times out. An expression that does not compile is SCRIPT_ERROR at once. Runs the "
                        + "caller's expression: refused in read-only mode."), true)
                .param("expression", "string", true, "a JavaScript expression; truthy means ready")
                .param("timeoutMs", "integer", false, "0 (default) = the call's budget; at most 600000")
                .param("pollMs", "integer", false, "poll period (default 50, at least 20)")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(WebCapabilities.EVALUATE).script().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return waitFlag(inv);
                    }
                }));
        return list;
    }

    private static PrimitiveSpec.Builder selecting(PrimitiveSpec.Builder b, boolean frame) {
        b.param("browser", "string", false, "ref of the browser (from web.browsers)")
         .param("by", "object", false, SELECT_DOC);
        if (frame) {
            b.param("frame", "string", false, "frame key (from web.browsers); default the main frame");
        }
        return b;
    }

    private static PrimitiveSpec.Builder locating(PrimitiveSpec.Builder b) {
        return b.param("selector", "string", false, "CSS selector of the element")
                .param("role", "string", false, "ARIA role of the element (instead of selector)")
                .param("name", "string|object", false, "accessible name: exact text, or {regex, ignoreCase?}")
                .param("index", "integer", false, "which of several matches (0-based)")
                .param("expectVisible", "boolean", false, "require the element to be visible (default true)");
    }

    // ------------------------------------------------------------------------------------------------ browsers

    private Object browsers(Invocation inv) {
        secrets.remember(inv);
        boolean includeClosed = inv.args().bool("includeClosed", false);
        long deadline = deadline(inv);
        BrowserIndex.Snapshot s = index.rebuild(Math.min(BrowserIndex.BUDGET_MS, remaining(deadline)));
        boolean registered = masksRegistered();
        List<Entry> listed = new ArrayList<Entry>();
        for (Entry e : s.entries) {
            if (includeClosed || !Selection.isClosed(e.browser())) {
                listed.add(e);
            }
        }
        // The titles and the target ids are read concurrently, within one window of at most 2 s (D55).
        long readBound = Math.min(PER_BROWSER_READ_MS, remaining(deadline));
        long readDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readBound);
        Map<Entry, CompletableFuture<String>> targets = startTargetIds(listed);
        Map<Entry, String> titles = Selection.titles(listed, readBound);
        Map<Entry, String> ids = awaitTargetIds(targets, remaining(readDeadline));
        List<Object> out = new ArrayList<Object>();
        for (Entry e : listed) {
            // S168 (FE): the component the adapter reports in this call, not the one of the last rebuild.
            Object component = BrowserMasks.componentNow(e);
            ComponentState state = entryState(e, component, deadline, registered);
            boolean masked = masked(registered, e, component, state);
            Map<String, Object> m = describe(e, masked ? null : titles.get(e), ids.get(e),
                    remaining(deadline) > PER_BROWSER_READ_MS / 2, masked);
            Map<String, Object> blocked = blockedBy(state);
            if (blocked != null) {
                m.put("blockedBy", blocked);
            }
            out.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("browsers", out);
        // Phase 8 (D548): what the walk answered for this call, so that a controller knows whether a page it cannot
        // map to a listed browser may be a masked one.
        result.put("masksRegistered", Boolean.valueOf(registered));
        return result;
    }

    /** {@link SeedWalk#masksRegistered()}; a walk that cannot answer counts as one with masks (the safe side). */
    boolean masksRegistered() {
        try {
            return walk.masksRegistered();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /**
     * {@code {dialog, title}} of the modal dialog that blocks the window of an open browser's component, or null (Phase
     * 7: {@code web.pointer}'s modal check, which the CDP path reads here to hand a blocked browser's pointer to the
     * agent; phase7-contracts.md section 4.3). One bounded EDT read per open browser that has a component, while the
     * call has time for it; a read that fails reports nothing.
     */
    private Map<String, Object> blockedBy(ComponentState state) {
        if (state == null || state.blocker == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dialog", ctx.handles().handleFor(state.blocker, "component"));
        m.put("title", WebLib.cut(state.blockerTitle, WebLib.MAX_URL_CHARS));
        return m;
    }

    /**
     * The state of an open browser's component, one bounded EDT read while the call has time for it, or null (no
     * component, a closed browser, no time left, or the read failed); {@link #UNREAD} when the read failed or there was
     * no time while a pack registered a {@code CaptureMask}, so that the entry is withheld as masked (the safe side).
     */
    private ComponentState entryState(Entry e, Object component, long deadline, boolean registered) {
        long left = remaining(deadline);
        if (component == null || Selection.isClosed(e.browser())) {
            return null;
        }
        if (left <= PER_BROWSER_READ_MS / 2) {
            return registered ? UNREAD : null;
        }
        try {
            return walk.state(component, Math.min(Pointing.STATE_MS, left - PER_BROWSER_READ_MS / 4));
        } catch (Exception ex) {
            return registered ? UNREAD : null;
        }
    }

    /** The state that stands for a read that could not be made while masks are registered: masked. */
    private static final ComponentState UNREAD = new ComponentState(false, 0, 0, 1.0, null, null, true);

    /**
     * Whether {@code web.browsers} lists the entry masked (S111; Phase 8, phase8-contracts.md 5.2, D547): nothing is
     * masked while no mask is registered; a {@code BrowserMask} TRUE masks; a browser without a component is masked
     * unless a {@code BrowserMask} releases it (no EDT read); a component is judged by this call's state read, or by the
     * entry's flag when there was none (a closed browser). The answer refreshes {@link Entry#masked}.
     *
     * @param component the component the adapter reports in this call (S168, FE)
     */
    private boolean masked(boolean registered, Entry e, Object component, ComponentState state) {
        Boolean decided = index.masks().decide(registered, e, component);
        boolean masked = decided != null ? decided.booleanValue() : state != null ? state.masked : e.masked;
        e.masked = masked;
        return masked;
    }

    /**
     * Whether the entry is masked now, for a call that has a browser selected (phase8-contracts.md 5.2, steps 1 to 5):
     * the {@link BrowserMasks} rule on the component the adapter reports now, and for a component one bounded EDT read
     * of the {@code CaptureMask}s ({@code state} when the caller already read it, else a read of at most
     * {@code budgetMs}); a read that fails, or that could not be made (no event-dispatch thread: the walk answers a
     * masked state, S167), counts as masked. The component is the one the adapter reports in this call (Phase 8
     * security fix S168, FE: a view lost or replaced since the last rebuild is judged as it is now; the entry's only when
     * the adapter throws); {@code state} must be a read of that component. Made only while a mask is registered;
     * refreshes {@link Entry#masked}.
     */
    boolean maskedNow(Entry e, ComponentState state, long budgetMs) {
        boolean registered = masksRegistered();
        if (!registered) {
            e.masked = false;
            return false;
        }
        Object component = BrowserMasks.componentNow(e);
        Boolean decided = index.masks().decide(true, e, component);
        boolean masked;
        if (decided != null) {
            masked = decided.booleanValue();
        } else if (state != null) {
            masked = state.masked;
        } else {
            try {
                ComponentState read = walk.state(component, Math.max(1, Math.min(Pointing.STATE_MS, budgetMs)));
                masked = read == null || read.masked;
            } catch (Exception ex) {
                masked = true;
            }
        }
        e.masked = masked;
        return masked;
    }

    private Map<String, Object> describe(Entry e, String title, String targetId, boolean withFrames, boolean masked) {
        WebBrowser b = e.browser();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ref", e.ref);
        m.put("adapter", e.adapter.id());
        String nativeId = Selection.safeNativeId(b);
        if (nativeId != null) {
            m.put("nativeId", nativeId);
        }
        if (masked) {
            // Phase 7 security fix S111 (P7 3.6, 4.8): a browser a pack's CaptureMask names is masked on every route;
            // its URL, title and frames' names and URLs are withheld, the entry says masked: true.
            m.put("url", "");
            m.put("masked", Boolean.TRUE);
        } else {
            // S43: a page's URL and title are cut on the receiving side, whatever the page or the adapter gave.
            m.put("url", WebLib.cut(Selection.safeUrl(b), WebLib.MAX_URL_CHARS));
        }
        if (title != null) {
            m.put("title", WebLib.cut(title, WebLib.MAX_URL_CHARS));
        }
        m.put("closed", Boolean.valueOf(Selection.isClosed(b)));
        m.put("visibleInSwing", Boolean.valueOf(e.showing));
        if (e.componentRef != null) {
            m.put("component", e.componentRef);
        }
        m.put("rendering", rendering(b));
        Dimension viewport = safeViewport(b);
        if (viewport != null) {
            m.put("viewport", size(viewport.width, viewport.height));
        }
        List<Object> caps = new ArrayList<Object>();
        for (WebCapability c : WebCapability.values()) {
            if (has(b, c)) {
                caps.add(c.name());
            }
        }
        m.put("capabilities", caps);
        if (targetId != null) {
            m.put("cdpTargetId", targetId);
        }
        if (e.tag != null) {
            m.put("tag", e.tag);
        }
        if (has(b, WebCapability.FRAMES)) {
            List<Object> frames = new ArrayList<Object>();
            if (withFrames) {
                for (WebFrame f : safeFrames(b)) {
                    Map<String, Object> fm = new LinkedHashMap<String, Object>();
                    fm.put("key", f.key());
                    if (f.parentKey() != null) {
                        fm.put("parentKey", f.parentKey());
                    }
                    fm.put("name", masked ? "" : WebLib.cut(f.name(), WebLib.MAX_URL_CHARS));
                    fm.put("url", masked ? "" : WebLib.cut(f.url(), WebLib.MAX_URL_CHARS));
                    fm.put("main", Boolean.valueOf(f.main()));
                    frames.add(fm);
                }
            }
            m.put("frames", frames);
        }
        return m;
    }

    /** Starts the CDP target id read of every browser that can answer it (D55). */
    private static Map<Entry, CompletableFuture<String>> startTargetIds(List<Entry> entries) {
        Map<Entry, CompletableFuture<String>> out = new LinkedHashMap<Entry, CompletableFuture<String>>();
        for (Entry e : entries) {
            WebBrowser b = e.browser();
            if (!has(b, WebCapability.CDP_TARGET_ID) || Selection.isClosed(b)) {
                continue;
            }
            try {
                CompletableFuture<String> f = b.cdpTargetId();
                if (f != null) {
                    out.put(e, f);
                }
            } catch (RuntimeException ex) {
                // the cached id, if any, is reported
            }
        }
        return out;
    }

    /**
     * The target ids within the bound; a read that fails or times out reports the id read successfully before, so
     * that a browser whose renderer is frozen keeps its id (the CDP path's frozen-target recovery needs it).
     */
    private static Map<Entry, String> awaitTargetIds(Map<Entry, CompletableFuture<String>> pending, long boundMs) {
        Map<Entry, String> out = new LinkedHashMap<Entry, String>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, boundMs));
        for (Map.Entry<Entry, CompletableFuture<String>> p : pending.entrySet()) {
            Entry e = p.getKey();
            String id = null;
            try {
                id = p.getValue().get(Math.max(1, remaining(deadline)), TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                p.getValue().cancel(false);
            }
            if (id != null && !id.isEmpty()) {
                e.cachedTargetId = id;
            } else {
                id = e.cachedTargetId;
            }
            if (id != null) {
                out.put(e, id);
            }
        }
        for (Entry e : pending.keySet()) {
            if (!out.containsKey(e) && e.cachedTargetId != null) {
                out.put(e, e.cachedTargetId);
            }
        }
        return out;
    }

    private Object cdpStatus(Invocation inv) {
        secrets.remember(inv);
        long deadline = deadline(inv);
        BrowserIndex.Snapshot s = index.snapshot();
        if (!s.built) {
            s = index.rebuild(Math.min(BrowserIndex.BUDGET_MS, remaining(deadline)));
        }
        List<Object> endpoints = new ArrayList<Object>();
        for (WebEngineAdapter a : index.adapters()) {
            List<CdpEndpoint> list;
            try {
                list = a.cdpEndpoints(s.seeds);
            } catch (Throwable t) {
                ctx.log().warn("web: adapter " + a.id() + " could not report its endpoints: " + t);
                continue;
            }
            if (list == null) {
                continue;
            }
            for (CdpEndpoint ep : list) {
                if (ep == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("host", ep.host());
                m.put("port", Long.valueOf(ep.port()));
                m.put("engineLabel", ep.engineLabel());
                m.put("adapter", a.id());
                endpoints.add(m);
            }
        }
        List<Entry> open = new ArrayList<Entry>();
        for (Entry e : s.entries) {
            if (!Selection.isClosed(e.browser())) {
                open.add(e);
            }
        }
        Map<Entry, String> ids = awaitTargetIds(startTargetIds(open),
                Math.min(PER_BROWSER_READ_MS, remaining(deadline)));
        List<Object> browsers = new ArrayList<Object>();
        for (Entry e : open) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ref", e.ref);
            if (ids.get(e) != null) {
                m.put("cdpTargetId", ids.get(e));
            }
            browsers.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("endpoints", endpoints);
        out.put("browsers", browsers);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ script

    private Object evaluate(Invocation inv) {
        secrets.remember(inv);
        JsonView a = inv.args();
        String expression = a.requireStr("expression");
        boolean await = a.bool("awaitPromise", false);
        String frame = a.str("frame");
        Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.evaluate", WebCapabilities.EVALUATE);
        if (await) {
            require(e, WebCapability.AWAIT_PROMISE, "web.evaluate", WebCapabilities.EVALUATE);
        }
        String wrapped;
        try {
            wrapped = WebLib.wrap(expression, await);
        } catch (IllegalArgumentException ex) {
            throw Errors.invalidParam("expression", "expression must be a non-blank JavaScript expression");
        }
        long budget = Await.budget(inv);
        WebEval eval = WebEval.of(wrapped).frameKey(frame).awaitPromise(await).timeoutMs(budget).build();
        String json = Await.get(e.browser().evaluate(eval), budget, e, "web.evaluate", WebCapabilities.EVALUATE);
        Map<?, ?> w = wrapperResult(e, json);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("json", w.get("json"));
        out.put("typeofResult", w.get("typeofResult"));
        if (Boolean.TRUE.equals(w.get("truncated"))) {
            out.put("truncated", Boolean.TRUE);
        }
        return out;
    }

    private Object ariaSnapshot(Invocation inv) {
        secrets.remember(inv);
        JsonView a = inv.args();
        long maxNodes = a.num("maxNodes", DEFAULT_MAX_NODES);
        if (maxNodes < 1) {
            throw Errors.invalidParam("maxNodes", "maxNodes must be at least 1");
        }
        boolean interestingOnly = a.bool("interestingOnly", true);
        Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.ariaSnapshot", WebCapabilities.EVALUATE);
        refuseMasked(inv, e);
        return library(inv, e, "web.ariaSnapshot", true, "aria", null,
                Long.valueOf(Math.min(maxNodes, MAX_NODES_CAP)), Boolean.valueOf(interestingOnly));
    }

    private Object domText(Invocation inv) {
        secrets.remember(inv);
        JsonView a = inv.args();
        long maxChars = a.num("maxChars", DEFAULT_MAX_CHARS);
        if (maxChars < 0) {
            throw Errors.invalidParam("maxChars", "maxChars must not be negative");
        }
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("selector", a.str("selector", "body"));
        options.put("maxChars", Long.valueOf(Math.min(maxChars, MAX_CHARS_CAP)));
        Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.dom.text", WebCapabilities.EVALUATE);
        refuseMasked(inv, e);
        return library(inv, e, "web.dom.text", true, "domText", options);
    }

    private Object domClick(Invocation inv) {
        secrets.remember(inv);
        Map<String, Object> options = locator(inv.args());
        Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.dom.click", WebCapabilities.EVALUATE);
        refuseLocatorOnMasked(inv, e, "web.dom.click");
        return withoutPageData(inv, e, "web.dom.click", "domClick", options);
    }

    private Object domFill(Invocation inv) {
        secrets.remember(inv);
        JsonView a = inv.args();
        Map<String, Object> options = locator(a);
        options.put("text", a.requireStr("text"));
        options.put("clear", Boolean.valueOf(a.bool("clear", true)));
        options.put("enter", Boolean.valueOf(a.bool("enter", false)));
        Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.dom.fill", WebCapabilities.EVALUATE);
        refuseLocatorOnMasked(inv, e, "web.dom.fill");
        return withoutPageData(inv, e, "web.dom.fill", "domFill", options);
    }

    /** The keys a masked browser's action answers keep of an element (phase8-contracts.md 5.2 (iv), D637). */
    private static final List<String> ELEMENT_KEYS_KEPT = java.util.Arrays.asList("role", "tag");

    /**
     * Phase 8 security fix S165 (FE; phase8-contracts.md 5.2 (iv)): {@code web.dom.click}, {@code web.dom.fill} and
     * {@code web.dispatchEvent} with a selector {@code target} evaluate the caller's locator in the page, and the kind
     * of their answer ({@code NOT_FOUND}, {@code INVALID_PARAMS} "not visible" or "not fillable", {@code AMBIGUOUS},
     * success) says whether it held: an oracle on the page a mask withholds. On a masked browser they are refused
     * {@code INVALID_PARAMS {param: "redact", reason: "masked", browser}} before the page is evaluated, as the four reads
     * are ({@link #maskedNow}: one bounded EDT read, only while a mask is registered), unless the call says
     * {@code redact: false} (a person's argument, which {@code CallPolicy} refuses for a model or a client).
     */
    private void refuseLocatorOnMasked(Invocation inv, Entry e, String primitive) {
        if (unredacted(inv) || !masksRegistered()) {
            return;
        }
        if (maskedNow(e, null, Await.budget(inv) / 2)) {
            throw Errors.invalid("browser " + e.ref + " is masked (a pack's mask names it, or it has no view while a "
                    + "mask is registered), so " + primitive + " does not evaluate a locator in its page on this route: "
                    + "whether it matched would reveal the page (a person may pass redact: false; web.pointer at a point "
                    + "stays allowed)", Errors.data("param", "redact", "reason", "masked", "browser", e.ref));
        }
    }

    /**
     * Calls {@code web.dom.click} or {@code web.dom.fill}'s library function and, on a browser judged masked meanwhile
     * (the refusal of {@link #refuseLocatorOnMasked} ran before; the entry's flag, no further EDT read), drops the page
     * data of the answer after it returned or threw (Phase 8, D637; phase8-contracts.md 5.2 (iv)): the result's
     * {@code clicked}/{@code filled} and the {@code AMBIGUOUS} error's {@code candidates} keep {@code role} and
     * {@code tag} only ({@code name} and {@code id} dropped, never emptied; {@code count} kept); skipped when the call
     * says {@code redact: false} (a person's argument).
     */
    private Object withoutPageData(Invocation inv, Entry e, String primitive, String function,
                                   Map<String, Object> options) {
        Object value;
        try {
            value = library(inv, e, primitive, false, function, options);
        } catch (RpcException ex) {
            if (ex.error().code() == ErrorCodes.AMBIGUOUS && maskedAnswer(inv, e)) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                if (ex.error().data() != null) {
                    data.putAll(ex.error().data());
                }
                Object candidates = data.get("candidates");
                if (candidates instanceof List) {
                    List<Object> kept = new ArrayList<Object>();
                    for (Object c : (List<?>) candidates) {
                        kept.add(c instanceof Map ? only((Map<?, ?>) c, ELEMENT_KEYS_KEPT) : c);
                    }
                    data.put("candidates", kept);
                }
                throw new RpcException(ex.error().code(), ex.getMessage(), data);
            }
            throw ex;
        }
        if (value instanceof Map && maskedAnswer(inv, e)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object v = entry.getValue();
                if ("clicked".equals(key) && v instanceof Map) {
                    v = only((Map<?, ?>) v, ELEMENT_KEYS_KEPT);
                } else if ("filled".equals(key) && v instanceof Map) {
                    v = only((Map<?, ?>) v, ELEMENT_KEYS_KEPT);
                }
                out.put(key, v);
            }
            return out;
        }
        return value;
    }

    /**
     * True when an action's answer must carry no page data: no {@code redact: false}, a mask registered, and the
     * browser masked by the entry's flag (set by the refusal's judgement just before, or by a rebuild or a re-judgement
     * since) or by the {@link BrowserMasks} rule as it stands now (a {@code BrowserMask} TRUE, a browser without a view);
     * no EDT read.
     */
    private boolean maskedAnswer(Invocation inv, Entry e) {
        if (unredacted(inv) || !masksRegistered()) {
            return false;
        }
        return e.masked || Boolean.TRUE.equals(index.masks().decide(true, e, BrowserMasks.componentNow(e)));
    }

    /** The entries of {@code m} whose keys are in {@code keys}, in {@code m}'s order. */
    private static Map<String, Object> only(Map<?, ?> m, List<String> keys) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (keys.contains(key)) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    /** The locator options of {@code web.dom.click} and {@code web.dom.fill}: {@code selector} or {@code role}. */
    private static Map<String, Object> locator(JsonView a) {
        String selector = a.str("selector");
        String role = a.str("role");
        if (selector != null && role != null) {
            throw Errors.invalidParam("selector", "give either selector or role (with name), not both");
        }
        if (selector == null && role == null) {
            throw Errors.invalidParam("selector", "give a selector, or a role and a name");
        }
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        if (selector != null) {
            if (selector.trim().isEmpty()) {
                throw Errors.invalidParam("selector", "selector must not be blank");
            }
            options.put("selector", selector);
        } else {
            options.put("role", role);
            Object name = a.get("name");
            if (name != null) {
                if (name instanceof Map) {
                    Object regex = ((Map<?, ?>) name).get("regex");
                    if (!(regex instanceof String)) {
                        throw Errors.invalidParam("name", "name must be a string or {regex, ignoreCase?}");
                    }
                } else if (!(name instanceof String)) {
                    throw Errors.invalidParam("name", "name must be a string or {regex, ignoreCase?}");
                }
                options.put("name", name);
            }
        }
        if (a.has("index")) {
            long index = a.num("index", 0);
            if (index < 0) {
                throw Errors.invalidParam("index", "index must not be negative");
            }
            options.put("index", Long.valueOf(index));
        }
        options.put("expectVisible", Boolean.valueOf(a.bool("expectVisible", true)));
        return options;
    }

    private Object dispatchEvent(Invocation inv) {
        secrets.remember(inv);
        JsonView a = inv.args();
        String name = a.requireStr("name");
        if (name.trim().isEmpty()) {
            throw Errors.invalidParam("name", "name must not be blank");
        }
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        String target = a.str("target");
        if (target != null) {
            options.put("target", target);
        }
        options.put("name", name);
        if (a.raw().containsKey("detail")) {
            options.put("detail", a.get("detail"));
        }
        Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.dispatchEvent", WebCapabilities.EVALUATE);
        if (target != null && !"window".equals(target) && !"document".equals(target)) {
            // S165 (FE): a selector target is a predicate the page evaluates; the window and the document are not.
            refuseLocatorOnMasked(inv, e, "web.dispatchEvent");
        }
        return library(inv, e, "web.dispatchEvent", false, "dispatch", options);
    }

    /**
     * Calls a library function in the selected browser and returns its value; a read retries once after a load
     * event. An error object answers the RPC error of its code, with {@code data.browser} added.
     */
    private Object library(Invocation inv, final Entry e, String primitive, boolean read, String function,
                           Object... args) {
        return libraryIn(e, inv.args().str("frame"), Await.budget(inv), primitive, read, function, args);
    }

    /**
     * {@link #library} in a given frame (null: the main frame) with a given budget: what {@code web.hitTest} needs to
     * descend into a child frame (phase7-contracts.md section 4.2).
     */
    Object libraryIn(final Entry e, final String frame, final long budget, String primitive, boolean read,
                     String function, Object... args) {
        // P2-L4-F2: the call goes through the guard that bounds the answer in the page, so that a page that replaced
        // the library chooses the value but never the size of what crosses the channel into this JVM.
        final String expression = WebLib.boundedCallExpression(function, args);
        Await.Attempt<String> attempt = new Await.Attempt<String>() {
            @Override
            public CompletableFuture<String> start() {
                return e.browser().evaluate(WebEval.of(expression).frameKey(frame).timeoutMs(budget).build());
            }
        };
        String json = read ? Await.read(attempt, budget, e, primitive, WebCapabilities.EVALUATE)
                : Await.get(attempt.start(), budget, e, primitive, WebCapabilities.EVALUATE);
        // S43: a library value is at most MAX_JSON_CHARS of JSON; a page that replaced the library is refused.
        Object value = boundedValue(e, parse(e, json));
        RpcError error = WebLib.errorOf(value);
        if (error != null) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            if (error.data() != null) {
                data.putAll(error.data());
            }
            if (!data.containsKey("browser")) {
                data.put("browser", e.ref);
            }
            throw new RpcException(error.code(), error.message(), data);
        }
        return value;
    }

    private Object tap(Invocation inv) {
        secrets.remember(inv);
        JsonView a = inv.args();
        if (a.get("patterns") == null) {
            throw Errors.invalidParam("patterns", "missing required parameter 'patterns'");
        }
        List<String> patterns = new ArrayList<String>();
        for (Object p : a.list("patterns")) {
            if (!(p instanceof String) || ((String) p).isEmpty()) {
                throw Errors.invalidParam("patterns", "patterns must be non-empty event names or globs");
            }
            patterns.add((String) p);
        }
        String target = a.str("target", "window");
        if (!"window".equals(target) && !"document".equals(target)) {
            throw Errors.invalidParam("target", "target must be window or document");
        }
        Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.tap", WebCapabilities.EVENTS);
        return taps.install(e, patterns, target, Await.budget(inv));
    }

    private Object waitFlag(Invocation inv) {
        secrets.remember(inv);
        long started = System.nanoTime();
        JsonView a = inv.args();
        String expression = a.requireStr("expression");
        String body = trimExpression(expression);
        if (body.isEmpty()) {
            throw Errors.invalidParam("expression", "expression must not be blank");
        }
        long timeoutMs = a.num("timeoutMs", 0);
        if (timeoutMs < 0) {
            throw Errors.invalidParam("timeoutMs", "timeoutMs must not be negative");
        }
        timeoutMs = Math.min(timeoutMs, MAX_WAIT_MS);
        long pollMs = Math.max(MIN_POLL_MS, a.num("pollMs", DEFAULT_POLL_MS));
        final String frame = a.str("frame");
        final Entry e = select(inv);
        require(e, WebCapability.EVALUATE, "web.waitFlag", WebCapabilities.EVALUATE);
        long budget = Await.budget(inv);
        long limit = timeoutMs > 0 ? Math.min(timeoutMs, budget) : budget;
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(limit);
        // A35: the shared guard (the CDP path evaluates the same text), which compiles the expression on its own and
        // answers {ready}, {ready: false, error} for a poll that threw, or {ready: false, compileError}.
        String probe = WebLib.flagExpression(body);
        long polls = 0;
        String lastError = null;
        // At most one probe outstanding: one the page has not answered (a busy or frozen page) is waited for, with
        // the adapter's bound set to the rest of the budget, and no other is sent meanwhile; abandoned at the end.
        CompletableFuture<String> pending = null;
        try {
            while (true) {
                long left = remaining(deadline);
                if (left <= 0) {
                    break;
                }
                if (pending == null) {
                    polls++;
                    pending = e.browser().evaluate(WebEval.of(probe).frameKey(frame).timeoutMs(left).build());
                }
                long bound = Math.min(left, Math.max(2000, 4 * pollMs));
                String json;
                try {
                    json = pending.get(bound, TimeUnit.MILLISECONDS);
                    pending = null;
                } catch (java.util.concurrent.TimeoutException ex) {
                    continue; // not answered yet: the same probe is waited for again
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (java.util.concurrent.CancellationException ex) {
                    pending = null;
                    json = null;
                } catch (java.util.concurrent.ExecutionException ex) {
                    pending = null;
                    json = null;
                    RpcException mapped = Await.map(ex.getCause(), e, "web.waitFlag", WebCapabilities.EVALUATE,
                            bound);
                    int code = mapped.error().code();
                    if (code == ErrorCodes.SCRIPT_ERROR) {
                        // The guard catches what the expression throws; a channel that still reports a script error
                        // for the guard itself counts the poll as not ready, cut as the guard cuts an error.
                        lastError = WebLib.errorLine(scriptMessage(mapped));
                    } else if (code != ErrorCodes.TIMEOUT) {
                        throw mapped;
                    }
                }
                if (json != null) {
                    Map<?, ?> poll = flagResult(e, json);
                    if (Boolean.TRUE.equals(poll.get("ready"))) {
                        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        bridge.ready(e, WebLib.cut(Selection.safeUrl(e.browser()), WebLib.MAX_URL_CHARS), "FLAG",
                                null);
                        return flag(true, ms, polls, null);
                    }
                    Object compileError = poll.get("compileError");
                    if (compileError != null) {
                        // A35: an expression that does not compile can never become ready.
                        throw Errors.script(e.ref, String.valueOf(compileError), "");
                    }
                    Object error = poll.get("error");
                    // S43: the guard cuts its line, but a page can break the string functions the guard calls.
                    lastError = error instanceof String ? WebLib.errorLine((String) error) : null;
                }
                long pause = Math.min(pollMs, remaining(deadline));
                if (pause <= 0) {
                    break;
                }
                try {
                    e.loads.next().get(pause, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ex) {
                    // the poll period passed without a load event
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    // a load event: poll at once
                }
            }
        } finally {
            if (pending != null) {
                pending.cancel(false);
            }
        }
        return flag(false, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), polls, lastError);
    }

    private static Map<String, Object> flag(boolean ready, long ms, long polls, String lastError) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ready", Boolean.valueOf(ready));
        out.put("ms", Long.valueOf(ms));
        out.put("polls", Long.valueOf(polls));
        if (!ready && lastError != null) {
            out.put("lastError", lastError);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ view

    private Object resize(Invocation inv) {
        secrets.remember(inv);
        JsonView a = inv.args();
        boolean clear = a.bool("clear", false);
        long width = 0;
        long height = 0;
        if (clear) {
            if (a.has("width") || a.has("height")) {
                throw Errors.invalidParam("clear", "give width and height, or clear: true, not both");
            }
        } else {
            if (!a.has("width") || !a.has("height")) {
                throw Errors.invalidParam("width", "give width and height (1..16384), or clear: true");
            }
            width = a.num("width", 0);
            height = a.num("height", 0);
            if (width < 1 || width > MAX_VIEWPORT || height < 1 || height > MAX_VIEWPORT) {
                throw Errors.invalidParam(width < 1 || width > MAX_VIEWPORT ? "width" : "height",
                        "width and height must be 1.." + MAX_VIEWPORT);
            }
        }
        Entry e = select(inv);
        WebBrowser b = e.browser();
        long budget = Await.budget(inv);
        if (clear) {
            Await.get(b.clearResize(), budget, e, "web.resize", WebCapabilities.RESIZE);
            e.overridden = false;
        } else {
            require(e, WebCapability.RESIZE, "web.resize", WebCapabilities.RESIZE);
            Await.get(b.resize((int) width, (int) height), budget, e, "web.resize", WebCapabilities.RESIZE);
            e.overridden = true;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("applied", "OVERRIDE");
        Dimension viewport = safeViewport(b);
        if (viewport != null) {
            out.put("viewport", size(viewport.width, viewport.height));
        } else if (!clear) {
            out.put("viewport", size((int) width, (int) height));
        }
        return out;
    }

    private Object bitmap(Invocation inv) throws IOException {
        secrets.remember(inv);
        long maxWidth = inv.args().num("maxWidth", DEFAULT_MAX_WIDTH);
        if (maxWidth < 1) {
            throw Errors.invalidParam("maxWidth", "maxWidth must be at least 1");
        }
        final Entry e = select(inv);
        require(e, WebCapability.BITMAP, "web.bitmap", WebCapabilities.BITMAP);
        refuseMasked(inv, e);
        long budget = Await.budget(inv);
        BufferedImage image = Await.read(new Await.Attempt<BufferedImage>() {
            @Override
            public CompletableFuture<BufferedImage> start() {
                return e.browser().bitmap();
            }
        }, budget, e, "web.bitmap", WebCapabilities.BITMAP);
        if (image == null) {
            throw Errors.internal(e.ref, "browser " + e.ref + " gave no bitmap");
        }
        BufferedImage fitted = Images.fit(image, (int) Math.min(Integer.MAX_VALUE, maxWidth));
        byte[] png = Images.png(fitted);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("format", "png");
        out.put("width", Long.valueOf(fitted.getWidth()));
        out.put("height", Long.valueOf(fitted.getHeight()));
        out.put("base64", Base64.getEncoder().encodeToString(png));
        out.put("source", bitmapSource(e.browser()).name());
        return out;
    }

    /**
     * The browser's {@link WebBrowser#bitmapSource()} (A37): {@code VENDOR}, the SPI's default, also when an adapter's
     * own answer is null or throws (it is informational).
     */
    static BitmapSource bitmapSource(WebBrowser b) {
        try {
            BitmapSource s = b.bitmapSource();
            return s != null ? s : BitmapSource.VENDOR;
        } catch (RuntimeException e) {
            return BitmapSource.VENDOR;
        }
    }

    /**
     * {@code web.reload} through the SPI's {@link WebBrowser#reload(boolean)} (A37): the built-in adapters reload
     * through their technology; an adapter that does not override it gets the SPI's default, {@code location.reload()}
     * through {@code evaluate} (which needs {@code EVALUATE}: without it the default fails with
     * {@code UnsupportedOperationException}, which answers {@code CAPABILITY_UNAVAILABLE}).
     */
    private Object reload(Invocation inv) {
        secrets.remember(inv);
        boolean ignoreCache = inv.args().bool("ignoreCache", false);
        Entry e = select(inv);
        Await.get(e.browser().reload(ignoreCache), Await.budget(inv), e, "web.reload", WebCapabilities.BROWSERS);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("reloaded", Boolean.TRUE);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The browser the call names; the selection may rebuild the index within at most its own bound. */
    Entry select(Invocation inv) {
        long budget = Math.min(Await.budget(inv), BrowserIndex.BUDGET_MS + Selection.TITLE_MS);
        Entry e = selection.select(inv.args(), budget);
        if (Selection.isClosed(e.browser())) {
            bridge.closed(e);
            throw Errors.staleBrowser(e.ref, "has closed");
        }
        return e;
    }

    /**
     * Phase 7 security fix S111 (phase7-contracts.md 3.6, 4.8) and Phase 8 (gap 110, phase8-contracts.md 5.2):
     * {@code INVALID_PARAMS} with {@code data.reason: "masked"} when the browser is masked ({@link #maskedNow}: a
     * {@code BrowserMask} names it, it has no component and no {@code BrowserMask} releases it, or a pack's
     * {@code CaptureMask} names its component or one of its ancestors; one bounded EDT read for a component, made only
     * while a mask is registered; a read that fails counts as masked, the safe side), unless the call says
     * {@code redact: false}, a person's argument that {@code CallPolicy} refuses for a model or a client. Used by
     * {@code web.bitmap}, {@code web.dom.text}, {@code web.ariaSnapshot} and {@code web.hitTest}, before the page is
     * evaluated (a view-less browser's {@code web.bitmap} is refused here before the adapter would answer
     * {@code CAPABILITY_UNAVAILABLE}: the mask wins).
     */
    void refuseMasked(Invocation inv, Entry e) {
        if (unredacted(inv) || !masksRegistered()) {
            return;
        }
        if (maskedNow(e, null, Await.budget(inv) / 2)) {
            throw Errors.invalid("browser " + e.ref + " is masked (a pack's mask names it, or it has no view while a "
                    + "mask is registered), so its page is not read on this route (a person may pass redact: false)",
                    Errors.data("param", "redact", "reason", "masked", "browser", e.ref));
        }
    }

    /** True when the call asks for the page unmasked: {@code redact} is {@code false} (or the text "false"). */
    static boolean unredacted(Invocation inv) {
        Object value = inv.args().raw().get("redact");
        return Boolean.FALSE.equals(value) || value instanceof String
                && "false".equalsIgnoreCase(((String) value).trim());
    }

    /** CAPABILITY_UNAVAILABLE when the selected browser lacks the flag the primitive needs. */
    static void require(Entry e, WebCapability flag, String primitive, String capability) {
        if (!has(e.browser(), flag)) {
            throw Errors.unavailable(primitive, capability, "browser " + e.ref + " (adapter " + e.adapter.id()
                    + ") has no " + flag.name(), e.ref);
        }
    }

    static boolean has(WebBrowser b, WebCapability flag) {
        try {
            return b.capabilities().contains(flag);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The {@code {json, typeofResult, truncated}} of a wrapper: the adapter's JSON text holds it as a string. The
     * {@code json} is cut at {@link WebLib#MAX_JSON_CHARS} with {@code truncated: true} here, whatever the page's
     * library did (security fix S43).
     */
    private static Map<?, ?> wrapperResult(Entry e, String json) {
        Object value = parse(e, json);
        if (value instanceof String) {
            value = parse(e, (String) value);
        }
        if (!(value instanceof Map) || !((Map<?, ?>) value).containsKey("typeofResult")) {
            throw Errors.internal(e.ref, "browser " + e.ref + " returned no script-library result");
        }
        Map<?, ?> w = (Map<?, ?>) value;
        Object text = w.get("json");
        Object type = w.get("typeofResult");
        boolean cut = text instanceof String && ((String) text).length() > WebLib.MAX_JSON_CHARS;
        if (!cut && !(type instanceof String && ((String) type).length() > 64)) {
            return w;
        }
        Map<String, Object> bounded = new LinkedHashMap<String, Object>();
        bounded.put("json", cut ? WebLib.cut((String) text, WebLib.MAX_JSON_CHARS) : text);
        bounded.put("typeofResult", type instanceof String ? WebLib.cut((String) type, 64) : type);
        bounded.put("truncated", Boolean.valueOf(cut || Boolean.TRUE.equals(w.get("truncated"))));
        return bounded;
    }

    /** One poll of the guard of {@link WebLib#flagExpression}: {@code {ready, error?, compileError?}}. */
    private static Map<?, ?> flagResult(Entry e, String json) {
        Object value = parse(e, json);
        if (!(value instanceof Map) || !(((Map<?, ?>) value).get("ready") instanceof Boolean)) {
            throw Errors.internal(e.ref, "browser " + e.ref + " returned no web.waitFlag guard result");
        }
        return (Map<?, ?>) value;
    }

    /** The page's own message of a {@code SCRIPT_ERROR} ({@code data.message}), else the error's message. */
    private static String scriptMessage(RpcException mapped) {
        Map<String, Object> data = mapped.error().data();
        Object message = data == null ? null : data.get("message");
        return message != null ? String.valueOf(message) : mapped.error().message();
    }

    /**
     * The library's value in the answer of a {@link WebLib#boundedCallExpression} (security fix P2-L4-F2): a thrown
     * value is {@code SCRIPT_ERROR}, a refusal ({@code oversized}, or no library text) is the shared
     * {@code INTERNAL_ERROR} with the browser named.
     */
    static Object boundedValue(Entry e, Object answer) {
        WebLib.Bounded b = WebLib.Bounded.of(answer);
        switch (b.kind()) {
            case VALUE:
                return b.value();
            case SCRIPT_ERROR:
                throw Errors.script(e.ref, b.message(), b.stack());
            default:
                RpcError refusal = b.refusal();
                Map<String, Object> data = new LinkedHashMap<String, Object>(refusal.data());
                data.put("browser", e.ref);
                throw new RpcException(refusal.code(), refusal.message(), data);
        }
    }

    /**
     * The value of an adapter's JSON text; a text longer than {@link WebLib#MAX_CHANNEL_CHARS} is refused before it is
     * parsed (security fix S43: a third-party adapter may not bound its channel).
     */
    static Object parse(Entry e, String json) {
        if (json == null) {
            return null;
        }
        if (json.length() > WebLib.MAX_CHANNEL_CHARS) {
            throw Errors.oversized(e.ref, "browser " + e.ref + "'s answer", WebLib.MAX_CHANNEL_CHARS);
        }
        try {
            return Json.parse(json);
        } catch (JsonException ex) {
            throw Errors.internal(e.ref, "browser " + e.ref + " returned text that is not JSON: " + ex.getMessage());
        }
    }

    private static String trimExpression(String expression) {
        int end = expression.length();
        while (end > 0 && (expression.charAt(end - 1) == ';' || Character.isWhitespace(expression.charAt(end - 1)))) {
            end--;
        }
        return expression.substring(0, end).trim();
    }

    private static String rendering(WebBrowser b) {
        try {
            return b.rendering() == null ? "UNKNOWN" : b.rendering().name();
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private static Dimension safeViewport(WebBrowser b) {
        try {
            return b.viewport();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<WebFrame> safeFrames(WebBrowser b) {
        try {
            List<WebFrame> frames = b.frames();
            return frames == null ? new ArrayList<WebFrame>() : frames;
        } catch (RuntimeException e) {
            return new ArrayList<WebFrame>();
        }
    }

    private static Map<String, Object> size(int w, int h) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("w", Long.valueOf(w));
        m.put("h", Long.valueOf(h));
        return m;
    }

    private static long deadline(Invocation inv) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Await.budget(inv));
    }

    private static long remaining(long deadlineNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }
}
