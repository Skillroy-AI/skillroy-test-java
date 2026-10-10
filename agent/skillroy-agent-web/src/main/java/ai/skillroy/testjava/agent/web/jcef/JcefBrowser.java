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

package ai.skillroy.testjava.agent.web.jcef;

import ai.skillroy.testjava.agent.api.spi.web.BitmapSource;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.api.spi.web.WebFrameGoneException;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.agent.api.spi.web.WebSubscription;
import ai.skillroy.testjava.agent.web.reflect.EventBudget;
import ai.skillroy.testjava.agent.web.reflect.Listeners;
import ai.skillroy.testjava.agent.web.reflect.ReflectiveException;
import ai.skillroy.testjava.agent.web.reflect.ResolvedApi;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebInput;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;

/**
 * One JCEF browser (phase4-contracts.md section 4.5), operated through its shared DevTools client
 * ({@code CefBrowser.getDevToolsClient()}): {@code Runtime.evaluate} with {@code returnByValue} (and
 * {@code contextId} for a sub-frame, from {@code Runtime.executionContextCreated}), {@code Page.getFrameTree},
 * {@code Page.*} and {@code Runtime.consoleAPICalled}/{@code exceptionThrown} events,
 * {@code Page.addScriptToEvaluateOnNewDocument}, {@code Emulation.setDeviceMetricsOverride},
 * {@code Page.captureScreenshot}, {@code Input.dispatchMouseEvent} (Phase 7); the CDP target id is the main frame's id
 * (BS8), cached once read or seen.
 *
 * <p><b>The channel.</b> When the descriptor's {@code raw} roles resolve (JCEF's package-private
 * {@code CefBrowser_N.executeDevToolsMethod}, {@code addDevToolsMessageObserver} and {@code CefRegistration}), the
 * handle registers a {@code CefDevToolsMessageObserver} of its own on the browser and pairs every answer with its
 * call by message id itself, keeping for a short while an answer that arrives before its id. JCEF's own client loses
 * exactly those: it assigns the id on the calling thread, and when the answer is delivered on CEF's UI thread before
 * the id reaches the client, the client completes and drops its future and then waits for ever on a new one (seen
 * against JCEF 152 in the Linux image on 2026-10-07: about one call in a hundred, more with windowed rendering; the
 * BS8 timeouts "mostly the first evaluation after a navigation" and the {@code Target.*} methods that "never answer"
 * are this race). The registration is the handle's own: it is disposed of on {@link #detach} and the client of the
 * application is not touched. Without the {@code raw} roles the handle uses the shared client
 * ({@code CefBrowser.getDevToolsClient()}), as on older JCEF: it belongs to the application as much as to the agent
 * and is never closed; before every call a client whose {@code isClosed()} is true is fetched again and the adapter's
 * listener, its init scripts and its viewport override are put back on the new one (the handle drops its client only
 * once that client is closed, so an open client is attached once, and a script is added to each client at most
 * once); a client that is closed at once (BS8: the first client of a browser created hidden refuses its first calls)
 * is fetched once more before the call is sent. On that path a call the client refused because it was closed
 * ({@code isClosed()} true when the failure arrives: the call never left the agent) is sent once more on a fresh
 * client, only when it is one of the handle's own reads ({@code Page.getFrameTree}, {@code Page.captureScreenshot})
 * and its caller still waits; a call that reached the page is never sent again, whatever its answer (Chromium answers
 * a call in flight across a navigation with "Inspected target navigated or closed"), and an evaluation is never sent
 * again by the handle (it cannot tell a read from a mutating one: the module retries its reads, phase4-contracts.md
 * section 4.2); the raw channel sends nothing again. Either way the handle enables the {@code Page} and
 * {@code Runtime} domains on first use; they stay enabled.
 *
 * <p><b>Init scripts</b> are added once per client (once to the raw channel's session) and removed on {@link #detach}
 * (and on {@code cancel()}) from every client they were added to, on that client, also when the page answers an
 * addition only after the detach's bound (a frozen renderer): on the shared client the removal follows the late
 * answer; on the raw channel the handle's registration then stays until that answer arrives and is disposed of right
 * after the removal is sent. Those late removals are sent from the thread that delivers the answer (a non-blocking
 * send whose answer is not awaited), the one thing the handle runs there, because the adapter's own threads are gone
 * by then. The calls still in progress at {@link #detach} fail ("the adapter was detached").
 *
 * <p><b>Threads.</b> The client completes futures and calls listeners on CEF's UI thread (the EDT with off-screen
 * rendering): the handle hands each result and event to the adapter's own threads at once (events in order, on a
 * serial executor) and runs nothing else there. Events are handed over within the browser's receive budget
 * ({@link EventBudget}, security fix P2-L4-S1): an event over the channel bound is dropped unparsed, the queue is
 * bounded by characters, and a page whose console output empties the console bucket stops the browser's console
 * capture ({@code Runtime.disable}, a {@code web.console} warning once, sub-frame evaluation unavailable) until a
 * main-frame navigation 10 s or more later. No method waits on the EDT; the accessors that ask the page
 * ({@link #frames()}) wait at most {@value #ACCESSOR_BOUND_MS} ms on the calling worker, and answer from the cache when
 * called on the EDT.
 *
 * <p><b>States.</b> A browser without a native browser yet ({@code getDevToolsClient()} null and no identifier) is a
 * handle without capabilities until the application creates it (BS8: a browser whose component is not showing). A
 * browser whose client turned null after it existed, or that left the process-wide list, has closed: every future
 * then completes exceptionally with an {@code IllegalStateException}, and subscribers get {@code CLOSED} once.
 *
 * <p>Thread-safe. Equality is the identity of the {@code CefBrowser}.
 */
final class JcefBrowser implements WebBrowser {

    /** How long a synchronous accessor waits for the page. */
    static final long ACCESSOR_BOUND_MS = 2000;
    /** How long {@code cancel()} of an init script waits for its removal. */
    static final long CANCEL_BOUND_MS = 2000;
    /** How long {@code clearResize} waits for the page to leave the cleared size. */
    static final long RESTORE_WAIT_MS = 2000;
    /** Raw channel: at most this many answers are kept before their id arrives. */
    static final int EARLY_MAX = 32;
    /** Raw channel: an answer whose id has not arrived within this time is dropped (another party's). */
    static final long EARLY_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);
    /**
     * A console event's text is built from its arguments up to this many characters (P2-L4-S1); the module masks it
     * and cuts it at 2000, so the rest is never copied.
     */
    static final int CONSOLE_TEXT_CHARS = 1 << 16;

    /** Every flag of a browser whose DevTools channel exists; {@code INPUT} since Phase 7 (phase7-contracts.md 4.5). */
    private static final Set<WebCapability> ALL = Collections.unmodifiableSet(EnumSet.of(WebCapability.EVALUATE,
            WebCapability.AWAIT_PROMISE, WebCapability.FRAMES, WebCapability.EVENTS_LOAD, WebCapability.EVENTS_CONSOLE,
            WebCapability.INIT_SCRIPT_DOCUMENT_START, WebCapability.RESIZE, WebCapability.BITMAP,
            WebCapability.CDP_TARGET_ID, WebCapability.INPUT));
    private static final Set<WebCapability> NONE = Collections.unmodifiableSet(EnumSet.noneOf(WebCapability.class));
    /** The protocol methods of the handle's own reads: the only calls sent once more after a refusal. */
    private static final Set<String> READS = Collections.unmodifiableSet(new java.util.HashSet<String>(
            java.util.Arrays.asList("Page.getFrameTree", "Page.captureScreenshot")));
    /** The key of the raw channel's one session in a script's additions. */
    private static final Object RAW_SESSION = new Object();
    private static final String ADD_SCRIPT = "Page.addScriptToEvaluateOnNewDocument";
    private static final String REMOVE_SCRIPT = "Page.removeScriptToEvaluateOnNewDocument";

    private final JcefAdapter adapter;
    private final ResolvedApi api;
    private final Object browser;
    private final Listeners listeners = new Listeners();
    private final Executor events;
    private final Map<String, Long> contexts = new ConcurrentHashMap<String, Long>();
    private final Map<String, String> frameUrls = new ConcurrentHashMap<String, String>();
    private final List<Script> scripts = new CopyOnWriteArrayList<Script>();
    private final AtomicBoolean closedDelivered = new AtomicBoolean();
    /** The futures of calls in progress for a caller, failed by {@link #detach} (their late answers are dropped). */
    private final Set<CompletableFuture<?>> inProgress = Collections.newSetFromMap(
            new ConcurrentHashMap<CompletableFuture<?>, Boolean>());
    private final Object lock = new Object();
    /** Serialises adding a script with putting the listed scripts on a fresh client. */
    private final Object scriptLock = new Object();
    private Object client;
    private Object listenerProxy;
    /** Raw channel: the descriptor's {@code raw} roles resolved. */
    private final boolean raw;
    /** Raw channel: the registration of {@link #observerProxy}, or null (none yet, closed, or detached). */
    private Object registration;
    private Object observerProxy;
    /** Raw channel: calls whose id is known and whose answer has not arrived; guarded by {@link #pendingLock}. */
    private final Map<Integer, Pending> pending = new java.util.HashMap<Integer, Pending>();
    /** Raw channel: answers that arrived before their id (or for another party's call); guarded by pendingLock. */
    private final LinkedHashMap<Integer, Answer> early = new LinkedHashMap<Integer, Answer>();
    /**
     * Raw channel: the calls of script additions the page had not answered when a removal gave up waiting; guarded by
     * pendingLock.
     */
    private final List<CompletableFuture<Map<String, Object>>> unansweredRawAdditions =
            new ArrayList<CompletableFuture<Map<String, Object>>>();
    /**
     * Raw channel after detach: the message ids of script additions the page had not answered, whose removal follows
     * their answer; guarded by pendingLock. While it is not empty the registration {@link #lingering} stays.
     */
    private final Set<Integer> lateAdditions = new java.util.HashSet<Integer>();
    /** Raw channel after detach: the registration kept for {@link #lateAdditions}, or null; guarded by pendingLock. */
    private Object lingering;
    private final Object pendingLock = new Object();
    private CompletableFuture<Map<String, Object>> runtimeEnabled;
    /** The answer to the channel's {@code Page.enable}, awaited by {@link #subscribe}; guarded by {@link #lock}. */
    private CompletableFuture<Map<String, Object>> pageEnabled;
    private volatile Set<WebCapability> capabilities = NONE;
    private volatile boolean everCreated;
    private volatile boolean closed;
    private volatile boolean detached;
    private volatile String mainFrameId;
    private volatile String mainUrl;
    private volatile Dimension override;
    private volatile Dimension lastViewport;
    private volatile List<WebFrame> lastFrames = Collections.emptyList();
    /** P2-L4-S1: the receive budget of this browser's DevTools events (the hand-off and the console). */
    private final EventBudget budget = new EventBudget();
    /** P2-L4-S1: a resumption of the stopped console capture is scheduled. */
    private final AtomicBoolean resumeScheduled = new AtomicBoolean();

    JcefBrowser(JcefAdapter adapter, ResolvedApi api, Object browser) {
        this.adapter = adapter;
        this.api = api;
        this.browser = browser;
        this.events = adapter.threads.serial();
        this.raw = api.has("raw.execute") && api.has("raw.addObserver") && api.complete("raw");
    }

    // ------------------------------------------------------------------------------------------------ identity

    @Override
    public String adapterId() {
        return JcefAdapter.ID;
    }

    @Override
    public Object vendorObject() {
        return browser;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JcefBrowser && ((JcefBrowser) o).browser == browser;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(browser);
    }

    @Override
    public String toString() {
        return "JcefBrowser{" + browser.getClass().getName() + "@" + Integer.toHexString(hashCode()) + "}";
    }

    @Override
    public String nativeId() {
        int id = identifier();
        return id >= 0 ? String.valueOf(id) : null;
    }

    private int identifier() {
        try {
            Object id = api.invoke("browser.getIdentifier", browser);
            return id instanceof Number ? ((Number) id).intValue() : -1;
        } catch (ReflectiveException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------------------------------------ state

    /** Re-reads whether the native browser exists (called by every enumeration). */
    void refresh() {
        if (closed) {
            capabilities = NONE;
            return;
        }
        Object c = fetchClientOrNull();
        int id = identifier();
        if (c != null) {
            everCreated = true;
            capabilities = ALL;
            if (listeners.any() || !scripts.isEmpty()) {
                try {
                    channel();
                } catch (IllegalStateException e) {
                    // closed meanwhile
                }
            }
        } else if (everCreated && id >= 0) {
            markClosed("its DevTools client is gone");
        } else {
            capabilities = NONE;
        }
    }

    /** True once the browser was seen closed (no live check). */
    boolean closedFlag() {
        return closed;
    }

    @Override
    public Set<WebCapability> capabilities() {
        return closed ? NONE : capabilities;
    }

    @Override
    public boolean isClosed() {
        if (closed) {
            return true;
        }
        if (everCreated && fetchClientOrNull() == null) {
            markClosed("its DevTools client is gone");
            return true;
        }
        return false;
    }

    /** Marks the browser closed and tells the subscribers once. */
    void markClosed(String why) {
        closed = true;
        capabilities = NONE;
        Object old;
        Object reg;
        synchronized (lock) {
            old = client;
            client = null;
            reg = registration;
            registration = null;
        }
        Object kept;
        synchronized (pendingLock) {
            kept = lingering;
            lingering = null;
            lateAdditions.clear();
        }
        removeListener(old);
        dispose(reg);
        dispose(kept);
        failPending(new IllegalStateException("the browser has closed"));
        if (closedDelivered.compareAndSet(false, true)) {
            try {
                events.execute(new Runnable() {
                    @Override
                    public void run() {
                        listeners.deliver(JcefBrowser.this, WebEvent.closed());
                    }
                });
            } catch (RejectedExecutionException e) {
                listeners.deliver(this, WebEvent.closed());
            }
        }
    }

    @Override
    public String url() {
        String u = mainUrl;
        if (u != null) {
            return u;
        }
        try {
            Object v = api.invoke("browser.getURL", browser);
            return v == null ? "" : String.valueOf(v);
        } catch (ReflectiveException e) {
            return "";
        }
    }

    @Override
    public Component component() {
        Component c = uiComponent();
        for (Component p = c; p != null; p = p.getParent()) {
            if (p instanceof Window) {
                return c;
            }
        }
        return null;
    }

    private Component uiComponent() {
        try {
            Object c = api.invoke("browser.getUIComponent", browser);
            return c instanceof Component ? (Component) c : null;
        } catch (ReflectiveException e) {
            return null;
        }
    }

    @Override
    public WebRendering rendering() {
        if (api.isInstance("browser.windowed", browser)) {
            return WebRendering.WINDOWED;
        }
        if (api.isInstance("browser.offscreen", browser)) {
            return WebRendering.OFFSCREEN_HEAVYWEIGHT;
        }
        Component c = uiComponent();
        if (Boolean.TRUE.equals(adapter.windowless()) && c != null && c.isLightweight()) {
            return WebRendering.OFFSCREEN_LIGHTWEIGHT;
        }
        return WebRendering.UNKNOWN;
    }

    @Override
    public Dimension viewport() {
        Dimension d = override != null ? override : lastViewport;
        return d == null ? null : new Dimension(d);
    }

    // ------------------------------------------------------------------------------------------------ channel

    /** {@code getDevToolsClient()}, or null (no native browser yet, or closing). */
    private Object fetchClientOrNull() {
        try {
            return api.invoke("browser.getDevToolsClient", browser);
        } catch (ReflectiveException e) {
            return null;
        }
    }

    private boolean clientClosed(Object c) {
        try {
            return Boolean.TRUE.equals(api.invoke("devtools.isClosed", c));
        } catch (ReflectiveException e) {
            return true;
        }
    }

    /**
     * The live client: the cached one while it is open, else a fresh one with the adapter's listener, domains, init
     * scripts and override put back.
     *
     * @throws IllegalStateException when the browser has closed or has no native browser yet
     */
    private Object client() {
        if (closed) {
            throw new IllegalStateException("the browser has closed");
        }
        if (detached) {
            throw new IllegalStateException("the adapter was detached");
        }
        Object fresh = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Object old;
            synchronized (lock) {
                old = client;
                if (old != null && !clientClosed(old)) {
                    return old;
                }
                fresh = fetchClientOrNull();
                if (fresh == null) {
                    client = null;
                } else {
                    everCreated = true;
                    capabilities = ALL;
                    client = fresh;
                }
            }
            if (fresh == null) {
                if (everCreated && identifier() >= 0) {
                    markClosed("its DevTools client is gone");
                    throw new IllegalStateException("the browser has closed");
                }
                throw new IllegalStateException("the browser has no native browser yet (it was never shown)");
            }
            if (fresh != old) {
                removeListener(old);
                attach(fresh);
            }
            // BS8: the first client of a browser created hidden refuses its first calls (the attach's own) and is
            // closed from then on: it is fetched once more, so that the caller's call goes to an open client.
            if (!clientClosed(fresh)) {
                return fresh;
            }
        }
        return fresh;
    }

    /** The channel of the next call: the raw registration, or the shared client without the raw roles. */
    private Object channel() {
        return raw ? rawChannel() : client();
    }

    /**
     * The raw channel: the handle's own observer registration on the browser, made on first use (the domains, the
     * listed init scripts and the override then put in place).
     *
     * @throws IllegalStateException when the browser has closed or has no native browser yet
     */
    private Object rawChannel() {
        if (closed) {
            throw new IllegalStateException("the browser has closed");
        }
        if (detached) {
            throw new IllegalStateException("the adapter was detached");
        }
        synchronized (lock) {
            if (registration != null) {
                return registration;
            }
        }
        // getDevToolsClient() is null before the native browser exists and once the browser is closing.
        if (fetchClientOrNull() == null) {
            if (everCreated && identifier() >= 0) {
                markClosed("its DevTools client is gone");
                throw new IllegalStateException("the browser has closed");
            }
            throw new IllegalStateException("the browser has no native browser yet (it was never shown)");
        }
        Object fresh;
        try {
            fresh = api.invoke("raw.addObserver", browser, observer());
        } catch (ReflectiveException e) {
            throw new IllegalStateException("DevTools observer: " + e.getMessage());
        }
        if (fresh == null) {
            throw new IllegalStateException("the browser has no native browser yet (it was never shown)");
        }
        boolean mine;
        synchronized (lock) {
            mine = registration == null && !closed && !detached;
            if (mine) {
                registration = fresh;
                everCreated = true;
                capabilities = ALL;
            }
        }
        if (!mine) {
            dispose(fresh);
            synchronized (lock) {
                if (registration != null) {
                    return registration;
                }
            }
            throw new IllegalStateException(closed ? "the browser has closed" : "the adapter was detached");
        }
        attachRaw();
        return fresh;
    }

    /** Enables the domains, puts the listed init scripts and the override in place on the raw channel. */
    private void attachRaw() {
        contexts.clear();
        CompletableFuture<Map<String, Object>> page = new CompletableFuture<Map<String, Object>>();
        sendRaw("Page.enable", new LinkedHashMap<String, Object>(), page);
        CompletableFuture<Map<String, Object>> runtime = enableRuntime(null);
        synchronized (lock) {
            pageEnabled = page;
            runtimeEnabled = runtime;
        }
        synchronized (scriptLock) {
            for (Script s : scripts) {
                s.addTo(null);
            }
        }
        Dimension o = override;
        if (o != null) {
            sendRaw("Emulation.setDeviceMetricsOverride", metrics(o.width, o.height),
                    new CompletableFuture<Map<String, Object>>());
        }
    }

    /** The handle's {@code CefDevToolsMessageObserver}: answers and events, handed off at once. */
    private Object observer() {
        synchronized (lock) {
            if (observerProxy == null) {
                observerProxy = api.implement("raw.observer", new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (args == null) {
                            return null;
                        }
                        if ("onDevToolsMethodResult".equals(method.getName()) && args.length == 4) {
                            onAnswer(args[1], args[2], args[3]);
                        } else if ("onDevToolsEvent".equals(method.getName()) && args.length == 3) {
                            hand(String.valueOf(args[1]), args[2] == null ? "{}" : String.valueOf(args[2]));
                        }
                        return null;
                    }
                });
            }
            return observerProxy;
        }
    }

    /** An answer, on CEF's UI thread: to its pending call, or kept until its id arrives. */
    private void onAnswer(Object id, Object success, Object result) {
        if (!(id instanceof Number)) {
            return;
        }
        Integer key = Integer.valueOf(((Number) id).intValue());
        Answer answer = new Answer(Boolean.TRUE.equals(success), result == null ? null : String.valueOf(result));
        final Pending p;
        Object done = null;
        boolean late;
        synchronized (pendingLock) {
            late = lateAdditions.remove(key);
            if (late && lateAdditions.isEmpty()) {
                done = lingering;
                lingering = null;
            }
        }
        if (late) {
            // A script addition answered after the detach: its removal, sent from here (the adapter's threads are
            // gone), then the registration kept for it is disposed of once no other such answer is awaited.
            Object identifier = answer.success ? identifierOf(answer.json) : null;
            if (identifier != null) {
                Map<String, Object> params = new LinkedHashMap<String, Object>();
                params.put("identifier", identifier);
                try {
                    api.invoke("raw.execute", browser, REMOVE_SCRIPT, Json.write(params));
                } catch (ReflectiveException e) {
                    // the browser is gone, and the script with it
                }
            }
            dispose(done);
            return;
        }
        synchronized (pendingLock) {
            p = pending.remove(key);
            if (p == null) {
                early.put(key, answer);
                pruneEarly();
            }
        }
        if (p != null) {
            final Answer a = answer;
            handOff(new Runnable() {
                @Override
                public void run() {
                    deliver(p.method, a, p.out);
                }
            }, p.out);
        }
    }

    /** Keeps the early answers few and recent (guarded by pendingLock). */
    private void pruneEarly() {
        long now = System.nanoTime();
        for (Iterator<Map.Entry<Integer, Answer>> it = early.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Answer> e = it.next();
            if (early.size() > EARLY_MAX || now - e.getValue().at > EARLY_TTL_NANOS) {
                it.remove();
            } else {
                break;
            }
        }
    }

    /** Sends a method on the raw channel; {@code out} completes on the adapter's threads with the answer. */
    private void sendRaw(final String method, Map<String, Object> params,
                         final CompletableFuture<Map<String, Object>> out) {
        Object ids;
        try {
            ids = api.invoke("raw.execute", browser, method, Json.write(params));
        } catch (ReflectiveException e) {
            out.completeExceptionally(new IllegalStateException("DevTools " + method + ": " + e.getMessage()));
            return;
        }
        if (!(ids instanceof CompletableFuture)) {
            out.completeExceptionally(new IllegalStateException("DevTools " + method + " returned no future"));
            return;
        }
        ((CompletableFuture<?>) ids).whenComplete(new BiConsumer<Object, Throwable>() {
            @Override
            public void accept(Object id, final Throwable failure) {
                if (failure != null || !(id instanceof Number)) {
                    final String why = failure == null ? "no message id" : String.valueOf(unwrap(failure)
                            .getMessage());
                    handOff(new Runnable() {
                        @Override
                        public void run() {
                            out.completeExceptionally(new IllegalStateException("DevTools " + method + ": "
                                    + why));
                        }
                    }, out);
                    return;
                }
                final Integer key = Integer.valueOf(((Number) id).intValue());
                final Answer answer;
                final Pending entry = new Pending(method, out);
                synchronized (pendingLock) {
                    answer = early.remove(key);
                    if (answer == null && !out.isDone()) {
                        pending.put(key, entry);
                    }
                }
                if (answer != null) {
                    handOff(new Runnable() {
                        @Override
                        public void run() {
                            deliver(method, answer, out);
                        }
                    }, out);
                    return;
                }
                // A caller that gives up (its bound passed) leaves no entry behind.
                out.whenComplete(new BiConsumer<Map<String, Object>, Throwable>() {
                    @Override
                    public void accept(Map<String, Object> v, Throwable t) {
                        synchronized (pendingLock) {
                            if (pending.get(key) == entry) {
                                pending.remove(key);
                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * True when a DevTools text is longer than {@link WebLib#MAX_CHANNEL_CHARS} (security fix S43): it is refused (an
     * answer) or dropped (an event) before it is parsed. {@code Page.captureScreenshot}'s answer is bounded by the
     * viewport instead (at most 16384 x 16384, set by the caller, never by the page).
     */
    static boolean oversized(String method, String json) {
        return json != null && json.length() > WebLib.MAX_CHANNEL_CHARS && !"Page.captureScreenshot".equals(method);
    }

    /** {@code INTERNAL_ERROR {reason: "oversized"}} of a DevTools answer over the bound (S43). */
    static RpcException oversizedAnswer(String method) {
        RpcError e = WebLib.oversized("DevTools " + method + "'s answer", WebLib.MAX_CHANNEL_CHARS);
        return new RpcException(e.code(), e.message(), e.data());
    }

    /** Completes {@code out} with an answer: its JSON object, or a failure with the protocol's error message. */
    private static void deliver(String method, Answer answer, CompletableFuture<Map<String, Object>> out) {
        if (oversized(method, answer.json)) {
            out.completeExceptionally(oversizedAnswer(method));
            return;
        }
        if (answer.success) {
            try {
                out.complete(answer.json == null || answer.json.isEmpty() ? new LinkedHashMap<String, Object>()
                        : Json.parseObject(answer.json));
            } catch (RuntimeException e) {
                out.completeExceptionally(new IllegalStateException("DevTools " + method
                        + " answered text that is not a JSON object"));
            }
            return;
        }
        String message = answer.json;
        try {
            Object m = Json.parseObject(answer.json).get("message");
            if (m != null) {
                message = String.valueOf(m);
            }
        } catch (RuntimeException e) {
            // keep the raw text
        }
        out.completeExceptionally(new IllegalStateException("DevTools " + method + ": "
                + WebLib.errorLine(message)));
    }

    /** Disposes of an observer registration (never throws). */
    private void dispose(Object reg) {
        if (reg == null) {
            return;
        }
        try {
            api.invoke("raw.dispose", reg);
        } catch (ReflectiveException e) {
            // already gone with the browser
        } catch (RuntimeException e) {
            // already gone with the browser
        }
    }

    /** Fails every pending raw call (the browser closed, or the adapter detached). */
    private void failPending(final Throwable why) {
        final List<Pending> all;
        synchronized (pendingLock) {
            all = new ArrayList<Pending>(pending.values());
            pending.clear();
            early.clear();
        }
        for (final Pending p : all) {
            handOff(new Runnable() {
                @Override
                public void run() {
                    p.out.completeExceptionally(why);
                }
            }, p.out);
        }
    }

    /** A raw call waiting for its answer. */
    private static final class Pending {
        final String method;
        final CompletableFuture<Map<String, Object>> out;

        Pending(String method, CompletableFuture<Map<String, Object>> out) {
            this.method = method;
            this.out = out;
        }
    }

    /** An answer of the raw channel. */
    private static final class Answer {
        final boolean success;
        final String json;
        final long at = System.nanoTime();

        Answer(boolean success, String json) {
            this.success = success;
            this.json = json;
        }
    }

    /** Puts the adapter's listener, domains, init scripts and override on a (new) client. */
    private void attach(Object c) {
        contexts.clear();
        try {
            api.invoke("devtools.addEventListener", c, listener());
        } catch (ReflectiveException e) {
            // events are lost on this client; calls still work
        }
        CompletableFuture<Map<String, Object>> page = send(c, "Page.enable", new LinkedHashMap<String, Object>(),
                false);
        CompletableFuture<Map<String, Object>> runtime = enableRuntime(c);
        synchronized (lock) {
            pageEnabled = page;
            runtimeEnabled = runtime;
        }
        synchronized (scriptLock) {
            for (Script s : scripts) {
                s.addTo(c);
            }
        }
        Dimension o = override;
        if (o != null) {
            send(c, "Emulation.setDeviceMetricsOverride", metrics(o.width, o.height), false);
        }
    }

    private Object listener() {
        synchronized (lock) {
            if (listenerProxy == null) {
                listenerProxy = api.implement("devtools.listener", new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onEvent".equals(method.getName()) && args != null && args.length == 2) {
                            hand(String.valueOf(args[0]), args[1] == null ? "{}" : String.valueOf(args[1]));
                        }
                        return null;
                    }
                });
            }
            return listenerProxy;
        }
    }

    private void removeListener(Object c) {
        Object l;
        synchronized (lock) {
            l = listenerProxy;
        }
        if (c == null || l == null) {
            return;
        }
        try {
            api.invoke("devtools.removeEventListener", c, l);
        } catch (ReflectiveException e) {
            // the client is gone
        }
    }

    /**
     * A future of a caller's call, failed by {@link #detach} if it has not completed by then (the adapter's threads
     * end there, and an answer that arrives later is dropped).
     */
    private <T> CompletableFuture<T> tracked(final CompletableFuture<T> out) {
        inProgress.add(out);
        out.whenComplete(new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T v, Throwable t) {
                inProgress.remove(out);
            }
        });
        if (detached) {
            out.completeExceptionally(new IllegalStateException("the adapter was detached"));
        }
        return out;
    }

    /** Sends a protocol method on the live channel; the result is handed to the adapter's threads at once. */
    CompletableFuture<Map<String, Object>> call(String method, Map<String, Object> params) {
        CompletableFuture<Map<String, Object>> out = tracked(new CompletableFuture<Map<String, Object>>());
        Object c;
        try {
            c = channel();
        } catch (IllegalStateException e) {
            out.completeExceptionally(e);
            return out;
        }
        if (raw) {
            sendRaw(method, params, out);
        } else {
            sendInto(c, method, params, out, READS.contains(method));
        }
        return out;
    }

    private CompletableFuture<Map<String, Object>> send(Object c, String method, Map<String, Object> params,
                                                       boolean retry) {
        CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
        if (raw) {
            sendRaw(method, params, out);
        } else {
            sendInto(c, method, params, out, retry);
        }
        return out;
    }

    private void sendInto(final Object c, final String method, final Map<String, Object> params,
                          final CompletableFuture<Map<String, Object>> out, final boolean retry) {
        Object raw;
        try {
            raw = api.invoke("devtools.execute", c, method, Json.write(params));
        } catch (ReflectiveException e) {
            if (resend(retry, out, e.vendorMessage(), c)) {
                again(c, method, params, out);
            } else {
                out.completeExceptionally(new IllegalStateException("DevTools " + method + ": " + e.getMessage()));
            }
            return;
        }
        if (!(raw instanceof CompletableFuture)) {
            out.completeExceptionally(new IllegalStateException("DevTools " + method + " returned no future"));
            return;
        }
        ((CompletableFuture<?>) raw).whenComplete(new BiConsumer<Object, Throwable>() {
            @Override
            public void accept(final Object value, final Throwable failure) {
                // On CEF's UI thread (the EDT off-screen): hand over at once, run nothing else here.
                handOff(new Runnable() {
                    @Override
                    public void run() {
                        if (failure == null) {
                            if (value != null && oversized(method, String.valueOf(value))) {
                                out.completeExceptionally(oversizedAnswer(method));
                                return;
                            }
                            try {
                                out.complete(value == null ? new LinkedHashMap<String, Object>()
                                        : Json.parseObject(String.valueOf(value)));
                            } catch (RuntimeException e) {
                                out.completeExceptionally(new IllegalStateException("DevTools " + method
                                        + " answered text that is not a JSON object"));
                            }
                            return;
                        }
                        Throwable cause = unwrap(failure);
                        if (resend(retry, out, cause.getMessage(), c)) {
                            again(c, method, params, out);
                        } else {
                            out.completeExceptionally(new IllegalStateException("DevTools " + method + ": "
                                    + cause.getClass().getSimpleName() + (cause.getMessage() == null ? ""
                                    : ": " + cause.getMessage())));
                        }
                    }
                }, out);
            }
        });
    }

    /**
     * Whether a failed call is sent once more (the shared client's path): only a read of the handle's own
     * ({@code retry}), only a call the client refused because it is closed (its {@code isClosed()} is true now and the
     * failure says so: the call never left the agent), and only while its caller still waits ({@code out} not
     * completed: no timeout, no cancellation).
     */
    private boolean resend(boolean retry, CompletableFuture<?> out, String message, Object c) {
        return retry && !out.isDone() && looksClosed(message) && clientClosed(c);
    }

    /** The call was refused because its client was closed: once more on a fresh client. */
    private void again(Object old, String method, Map<String, Object> params,
                       CompletableFuture<Map<String, Object>> out) {
        synchronized (lock) {
            if (client == old) {
                client = null;
            }
        }
        Object fresh;
        try {
            fresh = client();
        } catch (IllegalStateException e) {
            out.completeExceptionally(e);
            return;
        }
        sendInto(fresh, method, params, out, false);
    }

    private void handOff(Runnable task, CompletableFuture<?> out) {
        try {
            adapter.threads.executor().execute(task);
        } catch (RejectedExecutionException e) {
            out.completeExceptionally(new IllegalStateException("the adapter was detached"));
        }
    }

    private static boolean looksClosed(String message) {
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("closed");
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.util.concurrent.CompletionException
                || c instanceof java.util.concurrent.ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /** Completes {@code out} with a {@link TimeoutException} after the bound, unless it completes first. */
    private void timeout(final CompletableFuture<?> out, long ms) {
        try {
            final ScheduledFuture<?> task = adapter.threads.schedule(new Runnable() {
                @Override
                public void run() {
                    out.completeExceptionally(new TimeoutException("no answer within " + ms + " ms"));
                }
            }, ms);
            out.whenComplete(new BiConsumer<Object, Throwable>() {
                @Override
                public void accept(Object v, Throwable t) {
                    task.cancel(false);
                }
            });
        } catch (RejectedExecutionException e) {
            out.completeExceptionally(new IllegalStateException("the adapter was detached"));
        }
    }

    private <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(t);
        return f;
    }

    /**
     * Null when the call may go on; else a future failed with an {@code IllegalStateException} (closed) or an
     * {@code UnsupportedOperationException} (no native browser, so not the flag).
     */
    private <T> CompletableFuture<T> refuse(WebCapability flag) {
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        if (!capabilities().contains(flag)) {
            refresh();
            if (!capabilities().contains(flag)) {
                return failed(new UnsupportedOperationException("the browser has no native browser yet, so no "
                        + flag.name()));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ evaluate

    @Override
    public CompletableFuture<String> evaluate(final WebEval request) {
        CompletableFuture<String> refused = refuse(request.awaitPromise() ? WebCapability.AWAIT_PROMISE
                : WebCapability.EVALUATE);
        if (refused != null) {
            return refused;
        }
        final CompletableFuture<String> out = tracked(new CompletableFuture<String>());
        timeout(out, request.timeoutMs());
        final Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("expression", request.expression());
        params.put("returnByValue", Boolean.TRUE);
        params.put("awaitPromise", Boolean.valueOf(request.awaitPromise()));
        final String frame = request.frameKey();
        if (frame == null || frame.equals(mainFrameId)) {
            evaluateIn(params, null, out);
            return out;
        }
        contextOf(frame).whenComplete(new BiConsumer<Long, Throwable>() {
            @Override
            public void accept(Long contextId, Throwable failure) {
                if (failure != null) {
                    out.completeExceptionally(unwrap(failure));
                    return;
                }
                params.put("contextId", contextId);
                evaluateIn(params, frame, out);
            }
        });
        return out;
    }

    private void evaluateIn(Map<String, Object> params, final String frame, final CompletableFuture<String> out) {
        if (out.isDone()) {
            return; // the caller's bound passed (or it gave up) before the call could be sent: nothing is sent
        }
        final CompletableFuture<Map<String, Object>> sent = call("Runtime.evaluate", params);
        out.whenComplete(new BiConsumer<String, Throwable>() {
            @Override
            public void accept(String v, Throwable t) {
                sent.cancel(false); // the caller has its answer or gave up: the protocol call is done with too
            }
        });
        sent.whenComplete(new BiConsumer<Map<String, Object>, Throwable>() {
            @Override
            public void accept(Map<String, Object> result, Throwable failure) {
                if (failure != null) {
                    Throwable cause = unwrap(failure);
                    String m = cause.getMessage() == null ? "" : cause.getMessage();
                    if (frame != null && (m.contains("context") || m.contains("Context") || m.contains("frame"))) {
                        out.completeExceptionally(new WebFrameGoneException(frame));
                    } else {
                        out.completeExceptionally(cause);
                    }
                    return;
                }
                Object details = result.get("exceptionDetails");
                if (details instanceof Map) {
                    out.completeExceptionally(scriptError((Map<?, ?>) details));
                    return;
                }
                out.complete(valueJson(result.get("result")));
            }
        });
    }

    /** The execution context of a sub-frame: known from the events, or after {@code Runtime.enable} answered. */
    private CompletableFuture<Long> contextOf(final String frame) {
        if (budget.stopped()) {
            // P2-L4-S1: the Runtime domain is off for this browser, so its sub-frame contexts are not known.
            return failed(new UnsupportedOperationException("sub-frame evaluation is unavailable while the console"
                    + " capture of this browser is stopped (its page's console output exceeded the receive budget;"
                    + " a main-frame navigation resumes it)"));
        }
        Long known = contexts.get(frame);
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        final CompletableFuture<Long> out = new CompletableFuture<Long>();
        CompletableFuture<Map<String, Object>> enabled;
        try {
            channel();
        } catch (IllegalStateException e) {
            out.completeExceptionally(e);
            return out;
        }
        synchronized (lock) {
            enabled = runtimeEnabled;
        }
        if (enabled == null) {
            enabled = CompletableFuture.completedFuture(null);
        }
        enabled.whenComplete(new BiConsumer<Map<String, Object>, Throwable>() {
            @Override
            public void accept(Map<String, Object> ignored, Throwable failure) {
                // The context events arrive before Runtime.enable's answer and are queued on the serial executor
                // before this check, which therefore sees them.
                try {
                    events.execute(new Runnable() {
                        @Override
                        public void run() {
                            Long id = contexts.get(frame);
                            if (id != null) {
                                out.complete(id);
                                return;
                            }
                            frameExists(frame).whenComplete(new BiConsumer<Boolean, Throwable>() {
                                @Override
                                public void accept(Boolean exists, Throwable f) {
                                    if (Boolean.TRUE.equals(exists)) {
                                        out.completeExceptionally(new IllegalStateException("frame '" + frame
                                                + "' has no execution context yet"));
                                    } else {
                                        out.completeExceptionally(new WebFrameGoneException(frame));
                                    }
                                }
                            });
                        }
                    });
                } catch (RejectedExecutionException e) {
                    out.completeExceptionally(new IllegalStateException("the adapter was detached"));
                }
            }
        });
        return out;
    }

    private CompletableFuture<Boolean> frameExists(final String frame) {
        return call("Page.getFrameTree", new LinkedHashMap<String, Object>()).thenApply(
                new java.util.function.Function<Map<String, Object>, Boolean>() {
                    @Override
                    public Boolean apply(Map<String, Object> tree) {
                        for (WebFrame f : parseTree(tree)) {
                            if (f.key().equals(frame)) {
                                return Boolean.TRUE;
                            }
                        }
                        return Boolean.FALSE;
                    }
                });
    }

    /** The JSON text of a {@code RemoteObject} fetched by value ({@code "null"} for undefined). */
    static String valueJson(Object remote) {
        if (!(remote instanceof Map)) {
            return "null";
        }
        Map<?, ?> r = (Map<?, ?>) remote;
        if (r.containsKey("value")) {
            return Json.write(r.get("value"));
        }
        Object unserializable = r.get("unserializableValue");
        if (unserializable instanceof String) {
            String u = (String) unserializable;
            if ("-0".equals(u)) {
                return "0";
            }
            if (u.endsWith("n")) {
                return Json.write(u.substring(0, u.length() - 1));
            }
            return "null";
        }
        return "null";
    }

    /** A {@link WebScriptException} from {@code exceptionDetails}: the error's first line, its description as stack. */
    static WebScriptException scriptError(Map<?, ?> details) {
        Object exception = details.get("exception");
        String description = null;
        String value = null;
        if (exception instanceof Map) {
            Object d = ((Map<?, ?>) exception).get("description");
            description = d instanceof String ? (String) d : null;
            if (((Map<?, ?>) exception).containsKey("value")) {
                value = String.valueOf(((Map<?, ?>) exception).get("value"));
            }
        }
        String message;
        if (description != null) {
            int nl = description.indexOf('\n');
            message = nl < 0 ? description : description.substring(0, nl);
        } else if (value != null) {
            message = "Uncaught " + value;
        } else {
            Object text = details.get("text");
            message = text == null ? "script error" : String.valueOf(text);
        }
        String stack = description != null ? description : stackOf(details.get("stackTrace"));
        // S43: the first line at most 2000 characters, the stack at most 8000, whatever the page threw.
        return new WebScriptException(WebLib.scriptMessage(message), stack == null ? null : WebLib.scriptStack(stack));
    }

    private static String stackOf(Object stackTrace) {
        if (!(stackTrace instanceof Map)) {
            return null;
        }
        Object frames = ((Map<?, ?>) stackTrace).get("callFrames");
        if (!(frames instanceof List)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object f : (List<?>) frames) {
            if (f instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) f;
                sb.append(sb.length() == 0 ? "" : "\n").append("    at ").append(m.get("functionName")).append(" (")
                  .append(m.get("url")).append(':').append(line(m.get("lineNumber"))).append(')');
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static long line(Object zeroBased) {
        return zeroBased instanceof Number ? ((Number) zeroBased).longValue() + 1 : 0;
    }

    // ------------------------------------------------------------------------------------------------ frames

    @Override
    public List<WebFrame> frames() {
        if (closed || !capabilities().contains(WebCapability.FRAMES)) {
            return Collections.emptyList();
        }
        if (onEdt()) {
            return lastFrames;
        }
        try {
            Map<String, Object> tree = call("Page.getFrameTree", new LinkedHashMap<String, Object>())
                    .get(ACCESSOR_BOUND_MS, TimeUnit.MILLISECONDS);
            List<WebFrame> frames = parseTree(tree);
            lastFrames = frames;
            return frames;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return lastFrames;
        } catch (Exception e) {
            return lastFrames;
        }
    }

    /** The frames of a {@code Page.getFrameTree} answer, main frame first; remembers the main frame's id. */
    private List<WebFrame> parseTree(Map<String, Object> answer) {
        List<WebFrame> out = new ArrayList<WebFrame>();
        Object tree = answer == null ? null : answer.get("frameTree");
        walk(tree, null, out);
        if (!out.isEmpty()) {
            String main = out.get(0).key();
            if (mainFrameId == null || !mainFrameId.equals(main)) {
                mainFrameId = main;
            }
        }
        return out;
    }

    private static void walk(Object tree, String parent, List<WebFrame> out) {
        if (!(tree instanceof Map)) {
            return;
        }
        Object frame = ((Map<?, ?>) tree).get("frame");
        if (!(frame instanceof Map)) {
            return;
        }
        Map<?, ?> f = (Map<?, ?>) frame;
        Object id = f.get("id");
        if (!(id instanceof String) || ((String) id).isEmpty()) {
            return;
        }
        boolean main = parent == null;
        out.add(new WebFrame((String) id, main ? null : parent, str(f.get("name")), str(f.get("url")), main));
        Object children = ((Map<?, ?>) tree).get("childFrames");
        if (children instanceof List) {
            for (Object child : (List<?>) children) {
                walk(child, (String) id, out);
            }
        }
    }

    @Override
    public CompletableFuture<String> cdpTargetId() {
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        if (!capabilities().contains(WebCapability.CDP_TARGET_ID)) {
            return CompletableFuture.completedFuture(null);
        }
        String known = mainFrameId;
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        return call("Page.getFrameTree", new LinkedHashMap<String, Object>()).thenApply(
                new java.util.function.Function<Map<String, Object>, String>() {
                    @Override
                    public String apply(Map<String, Object> tree) {
                        List<WebFrame> frames = parseTree(tree);
                        return frames.isEmpty() ? null : frames.get(0).key();
                    }
                });
    }

    // ------------------------------------------------------------------------------------------------ events

    /**
     * {@inheritDoc} Returns once the page answered the channel's {@code Page.enable} and {@code Runtime.enable} (at
     * most {@value #ACCESSOR_BOUND_MS} ms on the calling worker; at once on the EDT), so that a navigation the caller
     * starts next is reported.
     */
    @Override
    public WebSubscription subscribe(WebEventListener listener) {
        WebSubscription s = listeners.add(listener);
        if (!closed) {
            try {
                channel();
                awaitDomains();
            } catch (IllegalStateException e) {
                // no native browser yet: attached when it exists (refresh)
            }
        }
        return s;
    }

    /** Waits (bounded, never on the EDT) for the answers to the channel's {@code Page.enable} and Runtime.enable. */
    private void awaitDomains() {
        if (onEdt()) {
            return;
        }
        CompletableFuture<Map<String, Object>> page;
        CompletableFuture<Map<String, Object>> runtime;
        synchronized (lock) {
            page = pageEnabled;
            runtime = runtimeEnabled;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACCESSOR_BOUND_MS);
        if (page != null) {
            await(page, deadline);
        }
        if (runtime != null) {
            await(runtime, deadline);
        }
    }

    /**
     * Hands one DevTools event to the serial executor, on CEF's thread, within the browser's receive budget (security
     * fix P2-L4-S1): a console event ({@code Runtime.consoleAPICalled}, {@code Runtime.exceptionThrown}) is spent from
     * the console bucket first, and the one that empties it stops console capture ({@link #stopConsole}, on the
     * adapter's threads); an event over {@link WebLib#MAX_CHANNEL_CHARS} is dropped here, unparsed and never queued
     * (S43); and an event is queued only while the browser's queued events stay within
     * {@link EventBudget#QUEUE_CHARS}. Nothing else runs on CEF's thread.
     */
    private void hand(final String name, final String json) {
        if (isConsole(name)) {
            EventBudget.Verdict v = budget.spend(json.length(), System.nanoTime());
            if (v == EventBudget.Verdict.STOP) {
                stopConsoleLater();
            }
            if (v != EventBudget.Verdict.PASS) {
                return;
            }
        }
        if (detached || oversized(name, json)) {
            return;
        }
        final long cost = EventBudget.cost((long) json.length() + name.length());
        if (!budget.admit(cost)) {
            return;
        }
        try {
            events.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        onDevToolsEvent(name, json);
                    } finally {
                        budget.release(cost);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            budget.release(cost); // the adapter was detached
        }
    }

    /** The events whose size and rate the page's console sets (P2-L4-S1). */
    private static boolean isConsole(String name) {
        return "Runtime.consoleAPICalled".equals(name) || "Runtime.exceptionThrown".equals(name);
    }

    /**
     * {@code Runtime.enable} on the channel (the shared client {@code c}, or the raw channel when {@code c} is null on
     * it), unless console capture is stopped (P2-L4-S1): then an answered future, and the domain stays off.
     */
    private CompletableFuture<Map<String, Object>> enableRuntime(Object c) {
        if (budget.stopped()) {
            return CompletableFuture.completedFuture((Map<String, Object>) new LinkedHashMap<String, Object>());
        }
        return send(c, "Runtime.enable", new LinkedHashMap<String, Object>(), false);
    }

    /** {@link #stopConsole} on the adapter's threads (never on CEF's); never throws. */
    private void stopConsoleLater() {
        try {
            adapter.threads.executor().execute(new Runnable() {
                @Override
                public void run() {
                    stopConsole();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // the adapter was detached
        }
    }

    /**
     * Stops this browser's console capture (P2-L4-S1): {@code Runtime.disable} on the channel, so that Chromium sends
     * no console event and no copy of the page's output reaches this JVM any more, the sub-frame contexts forgotten
     * (a sub-frame evaluation answers {@code CAPABILITY_UNAVAILABLE} meanwhile), and {@link EventBudget#STOPPED_TEXT}
     * delivered once to the subscribers as a {@code warning}. {@code Runtime} was enabled by this handle; a client
     * of the application's that relied on it on the same session loses it as well until capture resumes (the
     * documented cost, phase4-contracts.md 4.3).
     */
    private void stopConsole() {
        if (closed || detached) {
            return;
        }
        contexts.clear();
        try {
            send(channel(), "Runtime.disable", new LinkedHashMap<String, Object>(), false);
        } catch (IllegalStateException e) {
            return; // closed or detached meanwhile
        }
        try {
            events.execute(new Runnable() {
                @Override
                public void run() {
                    listeners.deliver(JcefBrowser.this, WebEvent.console("warning", EventBudget.STOPPED_TEXT, null,
                            0));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // the adapter was detached
        }
    }

    /**
     * Resumes stopped console capture at a main-frame navigation (P2-L4-S1): at once when
     * {@link EventBudget#RESUME_AFTER_MS} has passed since it stopped, else when it has; scheduled once.
     */
    private void scheduleResume() {
        if (!resumeScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = budget.resumeInMs(System.nanoTime());
        if (delay < 0) {
            resumeScheduled.set(false);
            return;
        }
        try {
            adapter.threads.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        adapter.threads.executor().execute(new Runnable() {
                            @Override
                            public void run() {
                                resumeConsole();
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        resumeScheduled.set(false);
                    }
                }
            }, delay);
        } catch (RejectedExecutionException e) {
            resumeScheduled.set(false);
        }
    }

    /** {@code Runtime.enable} again with a full console budget; the contexts are reported anew (P2-L4-S1). */
    private void resumeConsole() {
        resumeScheduled.set(false);
        if (closed || detached || !budget.stopped()) {
            return;
        }
        budget.resume(System.nanoTime());
        contexts.clear();
        Object c;
        try {
            c = channel();
        } catch (IllegalStateException e) {
            return;
        }
        CompletableFuture<Map<String, Object>> runtime = enableRuntime(c);
        synchronized (lock) {
            runtimeEnabled = runtime;
        }
    }

    /** One DevTools event, on the serial executor (in order). */
    private void onDevToolsEvent(String name, String json) {
        if (detached || oversized(name, json)) {
            return; // S43: an event over the channel bound is dropped unparsed
        }
        Map<String, Object> p;
        try {
            p = Json.parseObject(json);
        } catch (RuntimeException e) {
            return;
        }
        if ("Page.frameNavigated".equals(name)) {
            Object frame = p.get("frame");
            if (!(frame instanceof Map)) {
                return;
            }
            Map<?, ?> f = (Map<?, ?>) frame;
            String id = str(f.get("id"));
            String parent = f.get("parentId") instanceof String ? (String) f.get("parentId") : null;
            String url = str(f.get("url"));
            frameUrls.put(id, url);
            String key = parent == null ? null : id;
            if (parent == null) {
                if (!id.isEmpty() && !id.equals(mainFrameId)) {
                    mainFrameId = id;
                }
                mainUrl = url;
                if (budget.stopped()) {
                    scheduleResume(); // P2-L4-S1: a new document resumes the stopped console capture
                }
            }
            Object unreachable = f.get("unreachableUrl");
            if (unreachable instanceof String && !((String) unreachable).isEmpty()) {
                listeners.deliver(this, WebEvent.loadFailed(key, (String) unreachable, "unreachable"));
            } else {
                listeners.deliver(this, WebEvent.navigated(key, url));
            }
        } else if ("Page.navigatedWithinDocument".equals(name)) {
            String id = str(p.get("frameId"));
            String url = str(p.get("url"));
            boolean main = id.equals(mainFrameId);
            if (main) {
                mainUrl = url;
            }
            frameUrls.put(id, url);
            listeners.deliver(this, WebEvent.navigated(main ? null : id, url));
        } else if ("Page.loadEventFired".equals(name)) {
            listeners.deliver(this, WebEvent.loadFinished(null, mainUrl == null ? url() : mainUrl));
        } else if ("Page.frameStoppedLoading".equals(name)) {
            String id = str(p.get("frameId"));
            if (!id.isEmpty() && mainFrameId != null && !id.equals(mainFrameId)) {
                listeners.deliver(this, WebEvent.loadFinished(id, str(frameUrls.get(id))));
            }
        } else if ("Page.frameDetached".equals(name)) {
            String id = str(p.get("frameId"));
            frameUrls.remove(id);
            contexts.remove(id);
        } else if ("Runtime.executionContextCreated".equals(name)) {
            Object context = p.get("context");
            if (context instanceof Map) {
                Object aux = ((Map<?, ?>) context).get("auxData");
                Object id = ((Map<?, ?>) context).get("id");
                if (aux instanceof Map && id instanceof Number) {
                    Object frameId = ((Map<?, ?>) aux).get("frameId");
                    Object isDefault = ((Map<?, ?>) aux).get("isDefault");
                    if (frameId instanceof String && !Boolean.FALSE.equals(isDefault)) {
                        contexts.put((String) frameId, Long.valueOf(((Number) id).longValue()));
                    }
                }
            }
        } else if ("Runtime.executionContextDestroyed".equals(name)) {
            Object id = p.get("executionContextId");
            if (id instanceof Number) {
                long gone = ((Number) id).longValue();
                for (Iterator<Map.Entry<String, Long>> it = contexts.entrySet().iterator(); it.hasNext(); ) {
                    if (it.next().getValue().longValue() == gone) {
                        it.remove();
                    }
                }
            }
        } else if ("Runtime.executionContextsCleared".equals(name)) {
            contexts.clear();
        } else if ("Runtime.consoleAPICalled".equals(name)) {
            consoleEvent(p);
        } else if ("Runtime.exceptionThrown".equals(name)) {
            Object details = p.get("exceptionDetails");
            if (details instanceof Map) {
                Map<?, ?> d = (Map<?, ?>) details;
                String text = str(d.get("text"));
                Object exception = d.get("exception");
                if (exception instanceof Map && ((Map<?, ?>) exception).get("description") instanceof String) {
                    String description = (String) ((Map<?, ?>) exception).get("description");
                    int nl = description.indexOf('\n');
                    text = (text.isEmpty() ? "" : text + " ") + (nl < 0 ? description : description.substring(0, nl));
                }
                listeners.deliver(this, WebEvent.console("error", text, str(d.get("url")),
                        (int) line(d.get("lineNumber"))));
            }
        } else if ("Inspector.detached".equals(name)) {
            if (fetchClientOrNull() == null && everCreated) {
                markClosed("the DevTools session ended");
            }
        }
    }

    private void consoleEvent(Map<String, Object> p) {
        String type = str(p.get("type"));
        String level;
        if ("warning".equals(type) || "error".equals(type) || "info".equals(type) || "debug".equals(type)) {
            level = type;
        } else if ("assert".equals(type)) {
            level = "error";
        } else {
            level = "log";
        }
        StringBuilder text = new StringBuilder();
        Object args = p.get("args");
        if (args instanceof List) {
            for (Object a : (List<?>) args) {
                if (text.length() >= CONSOLE_TEXT_CHARS) {
                    break; // P2-L4-S1: the module masks and cuts at 2000; the rest is never copied
                }
                if (text.length() > 0) {
                    text.append(' ');
                }
                String t = argText(a);
                text.append(t, 0, Math.min(t.length(), CONSOLE_TEXT_CHARS - text.length()));
            }
        }
        String source = null;
        int line = 0;
        Object stack = p.get("stackTrace");
        if (stack instanceof Map && ((Map<?, ?>) stack).get("callFrames") instanceof List) {
            List<?> frames = (List<?>) ((Map<?, ?>) stack).get("callFrames");
            if (!frames.isEmpty() && frames.get(0) instanceof Map) {
                Map<?, ?> top = (Map<?, ?>) frames.get(0);
                source = str(top.get("url"));
                line = (int) line(top.get("lineNumber"));
            }
        }
        listeners.deliver(this, WebEvent.console(level, text.toString(), source == null || source.isEmpty() ? null
                : source, line));
    }

    private static String argText(Object remote) {
        if (!(remote instanceof Map)) {
            return String.valueOf(remote);
        }
        Map<?, ?> r = (Map<?, ?>) remote;
        if (r.containsKey("value")) {
            Object v = r.get("value");
            return v instanceof String ? (String) v : Json.write(v);
        }
        if (r.get("unserializableValue") != null) {
            return String.valueOf(r.get("unserializableValue"));
        }
        if (r.get("description") != null) {
            return String.valueOf(r.get("description"));
        }
        return str(r.get("type"));
    }

    // ------------------------------------------------------------------------------------------------ scripts

    @Override
    public WebInitScript addInitScript(String source) {
        if (isClosed()) {
            throw new IllegalStateException("the browser has closed");
        }
        if (!capabilities().contains(WebCapability.EVALUATE)) {
            refresh();
            if (!capabilities().contains(WebCapability.EVALUATE)) {
                throw new UnsupportedOperationException("the browser has no native browser yet, so no init script");
            }
        }
        Script s = new Script(source);
        synchronized (scriptLock) {
            // The channel first: a fresh channel gets the listed scripts when attached, and this one is not listed
            // yet.
            Object c = channel();
            scripts.add(s);
            s.addTo(c);
        }
        return s;
    }

    /**
     * One init script: added once to every channel the handle uses until cancelled (each shared client, or the raw
     * channel's one session), and removed from each of them (see the class comment). Additions and removals are never
     * sent again.
     */
    private final class Script implements WebInitScript {
        private final String source;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /** Each channel the script was added to (a client, or {@link #RAW_SESSION}), by identity. */
        private final Map<Object, Addition> added = new java.util.IdentityHashMap<Object, Addition>();

        Script(String source) {
            this.source = source;
        }

        /** Adds the script to the channel, unless it was added to that channel before. */
        void addTo(Object c) {
            Object key = raw ? RAW_SESSION : c;
            Addition a;
            synchronized (this) {
                if (cancelled.get() || added.containsKey(key)) {
                    return;
                }
                a = new Addition();
                added.put(key, a);
            }
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("source", source);
            if (raw) {
                a.rawOut = new CompletableFuture<Map<String, Object>>();
                relay(a.rawOut, a.answer);
                sendRaw(ADD_SCRIPT, params, a.rawOut);
            } else {
                relay(execute(c, ADD_SCRIPT, params), a.answer);
            }
        }

        @Override
        public Timing timing() {
            return Timing.DOCUMENT_START;
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            scripts.remove(this);
            remove(onEdt() ? 0 : CANCEL_BOUND_MS);
        }

        /**
         * Removes the script from every channel it was added to, on that channel: at once where the page answered the
         * addition (waiting at most the bound for the additions and the removals, never on the EDT), else as soon as
         * the page answers it (the raw channel's additions still unanswered are also listed for {@link #detach}).
         */
        void remove(long boundMs) {
            Map<Object, Addition> all;
            synchronized (this) {
                all = new java.util.IdentityHashMap<Object, Addition>(added);
                added.clear();
            }
            if (closed) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, boundMs));
            List<CompletableFuture<?>> removals = new ArrayList<CompletableFuture<?>>();
            for (Map.Entry<Object, Addition> e : all.entrySet()) {
                final Object c = e.getKey();
                Addition a = e.getValue();
                if (!a.answer.isDone() && boundMs > 0) {
                    await(a.answer, deadline);
                }
                if (a.answer.isDone()) {
                    // An addition that was refused or failed added nothing: nothing to remove.
                    CompletableFuture<?> removal = a.answer.isCompletedExceptionally() ? null
                            : removal(c, a.answer.join());
                    if (removal != null) {
                        removals.add(removal);
                    }
                } else {
                    // The page has not answered the addition (a frozen renderer): its removal follows the answer.
                    a.answer.thenAccept(new java.util.function.Consumer<Object>() {
                        @Override
                        public void accept(Object value) {
                            removal(c, value);
                        }
                    });
                    if (a.rawOut != null) {
                        synchronized (pendingLock) {
                            unansweredRawAdditions.add(a.rawOut);
                        }
                    }
                }
            }
            if (boundMs > 0) {
                for (CompletableFuture<?> r : removals) {
                    await(r, deadline);
                }
            }
        }

        /** Sends the removal of the addition the page answered with {@code value}; null when nothing was added. */
        private CompletableFuture<?> removal(Object c, Object value) {
            Object identifier = identifierOf(value);
            if (identifier == null) {
                return null;
            }
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("identifier", identifier);
            if (c == RAW_SESSION) {
                CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
                sendRaw(REMOVE_SCRIPT, params, out);
                return out;
            }
            return execute(c, REMOVE_SCRIPT, params);
        }
    }

    /** One addition of a script to a channel: the page's answer (and on the raw channel, the call's own future). */
    private static final class Addition {
        final CompletableFuture<Object> answer = new CompletableFuture<Object>();
        volatile CompletableFuture<Map<String, Object>> rawOut;
    }

    /** The identifier in an addition's answer (the client's JSON text or the raw channel's parsed object), or null. */
    private static Object identifierOf(Object value) {
        try {
            if (value instanceof Map) {
                return ((Map<?, ?>) value).get("identifier");
            }
            return value == null ? null : Json.parseObject(String.valueOf(value)).get("identifier");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Sends a protocol method on the given shared client and returns the vendor's own future (completed on CEF's UI
     * thread), or a failed one; for the init scripts, whose additions and removals are never sent again and must also
     * be sent after the adapter was detached.
     */
    private CompletableFuture<?> execute(Object c, String method, Map<String, Object> params) {
        try {
            Object future = api.invoke("devtools.execute", c, method, Json.write(params));
            if (future instanceof CompletableFuture) {
                return (CompletableFuture<?>) future;
            }
            return failed(new IllegalStateException("DevTools " + method + " returned no future"));
        } catch (ReflectiveException e) {
            return failed(new IllegalStateException("DevTools " + method + ": " + e.getMessage()));
        }
    }

    /** Completes {@code to} as {@code from} completes; runs nothing else on the completing thread. */
    private static void relay(CompletableFuture<?> from, final CompletableFuture<Object> to) {
        from.whenComplete(new BiConsumer<Object, Throwable>() {
            @Override
            public void accept(Object value, Throwable failure) {
                if (failure != null) {
                    to.completeExceptionally(failure);
                } else {
                    to.complete(value);
                }
            }
        });
    }

    /** Waits for the future until the deadline (a bounded wait on the calling worker); never throws. */
    private static void await(CompletableFuture<?> f, long deadlineNanos) {
        long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (left <= 0) {
            return;
        }
        try {
            f.get(left, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // answered with a failure, or not within the bound
        }
    }

    // ------------------------------------------------------------------------------------------------ view

    @Override
    public CompletableFuture<Void> resize(final int width, final int height) {
        CompletableFuture<Void> refused = refuse(WebCapability.RESIZE);
        if (refused != null) {
            return refused;
        }
        return call("Emulation.setDeviceMetricsOverride", metrics(width, height)).thenApply(
                new java.util.function.Function<Map<String, Object>, Void>() {
                    @Override
                    public Void apply(Map<String, Object> ignored) {
                        override = new Dimension(width, height);
                        lastViewport = new Dimension(width, height);
                        return null;
                    }
                });
    }

    private static Map<String, Object> metrics(int width, int height) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("width", Long.valueOf(width));
        params.put("height", Long.valueOf(height));
        params.put("deviceScaleFactor", Long.valueOf(0));
        params.put("mobile", Boolean.FALSE);
        return params;
    }

    @Override
    public CompletableFuture<Void> clearResize() {
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        if (override == null) {
            return CompletableFuture.completedFuture(null);
        }
        final CompletableFuture<Void> out = new CompletableFuture<Void>();
        final Dimension o = override;
        call("Emulation.clearDeviceMetricsOverride", new LinkedHashMap<String, Object>()).whenComplete(
                new BiConsumer<Map<String, Object>, Throwable>() {
                    @Override
                    public void accept(Map<String, Object> ignored, Throwable failure) {
                        if (failure != null) {
                            out.completeExceptionally(unwrap(failure));
                            return;
                        }
                        override = null;
                        readViewport(out, o);
                    }
                });
        return out;
    }

    /**
     * Reads the page's inner size into the remembered viewport, then completes {@code out} (whatever happens). The
     * page may still report the cleared override for a moment (windowed rendering applies the restored size
     * asynchronously, about 100 ms later, seen against JCEF 152): while it does, the read waits for a {@code resize}
     * event that shows another size (the one the override itself queued does not count), at most
     * {@value #RESTORE_WAIT_MS} ms.
     */
    private void readViewport(final CompletableFuture<Void> out, Dimension cleared) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        int w = cleared == null ? -1 : cleared.width;
        int h = cleared == null ? -1 : cleared.height;
        // A resize event still queued from the override itself shows the cleared size: it does not end the wait.
        params.put("expression", "(function (w, h) { return new Promise(function (resolve) {"
                + " function size() { return [window.innerWidth, window.innerHeight]; }"
                + " function left() { return window.innerWidth !== w || window.innerHeight !== h; }"
                + " if (left()) { resolve(size()); return; }"
                + " var done = false;"
                + " function finish(force) { if (!done && (force || left())) { done = true;"
                + " window.removeEventListener('resize', onResize); resolve(size()); } }"
                + " function onResize() { finish(false); }"
                + " window.addEventListener('resize', onResize);"
                + " setTimeout(function () { finish(true); }, " + RESTORE_WAIT_MS + "); });"
                + " })(" + w + ", " + h + ")");
        params.put("returnByValue", Boolean.TRUE);
        params.put("awaitPromise", Boolean.TRUE);
        call("Runtime.evaluate", params).whenComplete(new BiConsumer<Map<String, Object>, Throwable>() {
            @Override
            public void accept(Map<String, Object> answer, Throwable failure) {
                if (failure == null && answer.get("result") instanceof Map) {
                    Object v = ((Map<?, ?>) answer.get("result")).get("value");
                    if (v instanceof List && ((List<?>) v).size() == 2 && ((List<?>) v).get(0) instanceof Number
                            && ((List<?>) v).get(1) instanceof Number) {
                        lastViewport = new Dimension(((Number) ((List<?>) v).get(0)).intValue(),
                                ((Number) ((List<?>) v).get(1)).intValue());
                    }
                }
                out.complete(null);
            }
        });
    }

    /**
     * {@code Page.captureScreenshot} of a browser whose component is showing. A hidden one (a card not shown, or no
     * component in a window) is refused with an {@code UnsupportedOperationException} at once: Chromium renders
     * nothing for it (seen against JCEF 152 in the Linux image on 2026-10-07: windowed, the screenshot is never
     * answered; off screen, it holds the page's background colour only, whatever the viewport override).
     */
    @Override
    public CompletableFuture<BufferedImage> bitmap() {
        CompletableFuture<BufferedImage> refused = refuse(WebCapability.BITMAP);
        if (refused != null) {
            return refused;
        }
        Component view = uiComponent();
        if (view == null || !view.isShowing()) {
            return failed(new UnsupportedOperationException("the page is not rendered while the browser is hidden"
                    + " (its component is " + (view == null ? "missing" : "not showing") + "): a bitmap needs a"
                    + " browser that is shown"));
        }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("format", "png");
        return call("Page.captureScreenshot", params).thenApply(
                new java.util.function.Function<Map<String, Object>, BufferedImage>() {
                    @Override
                    public BufferedImage apply(Map<String, Object> answer) {
                        Object data = answer.get("data");
                        if (!(data instanceof String)) {
                            throw new IllegalStateException("Page.captureScreenshot answered no image");
                        }
                        try {
                            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder()
                                    .decode((String) data)));
                            if (image == null) {
                                throw new IllegalStateException("the screenshot is not a readable PNG");
                            }
                            return image;
                        } catch (IOException e) {
                            throw new IllegalStateException("the screenshot cannot be decoded: " + e.getMessage());
                        }
                    }
                });
    }

    /**
     * The SPI's reload (A37), through the technology: {@code CefBrowser.reload()} / {@code reloadIgnoreCache()} on the
     * adapter's threads, which works where the page cannot run a script (frozen-target recovery, phase4-contracts.md
     * section 5.3) and for a {@code data:} document, whose reload by script Chromium refuses; where the descriptor's
     * {@code reload} roles do not resolve, {@code Page.reload {ignoreCache}} on the handle's DevTools channel.
     * Completes once the reload was requested.
     */
    @Override
    public CompletableFuture<Void> reload(final boolean ignoreCache) {
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        final String role = ignoreCache && api.has("browser.reloadIgnoreCache") ? "browser.reloadIgnoreCache"
                : "browser.reload";
        if (!api.has(role)) {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("ignoreCache", Boolean.valueOf(ignoreCache));
            return call("Page.reload", params).thenApply(
                    new java.util.function.Function<Map<String, Object>, Void>() {
                        @Override
                        public Void apply(Map<String, Object> ignored) {
                            return null;
                        }
                    });
        }
        final CompletableFuture<Void> out = tracked(new CompletableFuture<Void>());
        handOff(new Runnable() {
            @Override
            public void run() {
                try {
                    api.invoke(role, browser);
                    out.complete(null);
                } catch (ReflectiveException e) {
                    out.completeExceptionally(new IllegalStateException("reload failed: " + e.getMessage()));
                }
            }
        }, out);
        return out;
    }

    /** {@code PROTOCOL}: the bitmap is {@code Page.captureScreenshot} (A37). */
    @Override
    public BitmapSource bitmapSource() {
        return BitmapSource.PROTOCOL;
    }

    /**
     * The SPI's pointer input (A66, phase7-contracts.md sections 4.3 and 4.5): {@code Input.dispatchMouseEvent} with
     * the parameters both web paths send ({@link WebInput#mouseEvent}), through {@link #call} on the handle's channel,
     * as a mutating call: never one of the handle's reads, so it is never sent again on the shared client (D62).
     * Completes once Chromium answered it.
     */
    @Override
    public CompletableFuture<Void> dispatchPointer(WebPointerEvent event) {
        if (event == null) {
            return failed(new IllegalArgumentException("a pointer event is required"));
        }
        CompletableFuture<Void> refused = refuse(WebCapability.INPUT);
        if (refused != null) {
            return refused;
        }
        return call(WebInput.MOUSE_EVENT, mouseEvent(event)).thenApply(
                new java.util.function.Function<Map<String, Object>, Void>() {
                    @Override
                    public Void apply(Map<String, Object> ignored) {
                        return null;
                    }
                });
    }

    /** The protocol parameters of one SPI event. */
    static Map<String, Object> mouseEvent(WebPointerEvent e) {
        String type;
        switch (e.action()) {
            case PRESS:
                type = WebInput.PRESSED;
                break;
            case RELEASE:
                type = WebInput.RELEASED;
                break;
            case WHEEL:
                type = WebInput.WHEEL;
                break;
            default:
                type = WebInput.MOVED;
                break;
        }
        List<String> modifiers = new ArrayList<String>();
        for (WebPointerEvent.Modifier m : e.modifiers()) {
            modifiers.add(m.name());
        }
        return WebInput.mouseEvent(type, e.x(), e.y(), e.button().name().toLowerCase(java.util.Locale.ROOT),
                e.clickCount(), WebInput.modifierBits(modifiers), e.deltaX(), e.deltaY());
    }

    // ------------------------------------------------------------------------------------------------ detach

    /**
     * Removes what the adapter added (listener, init scripts, override), bounded by the deadline, then fails the
     * calls still in progress ("the adapter was detached"); never closes anything. On the raw channel the registration
     * is disposed of, or kept until the page answers the script additions it had not answered (see the class comment).
     */
    void detach(long deadlineNanos) {
        listeners.clear();
        List<Script> all = new ArrayList<Script>(scripts);
        scripts.clear();
        for (Script s : all) {
            if (s.cancelled.compareAndSet(false, true)) {
                long left = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
                s.remove(onEdt() ? 0 : left);
            }
        }
        Object c;
        boolean open;
        synchronized (lock) {
            c = client;
            open = raw ? registration != null : c != null;
        }
        if (override != null && open && !closed) {
            CompletableFuture<Map<String, Object>> f = send(c, "Emulation.clearDeviceMetricsOverride",
                    new LinkedHashMap<String, Object>(), false);
            override = null;
            long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (left > 0 && !onEdt()) {
                try {
                    f.get(left, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // the browser may be gone
                }
            }
        }
        removeListener(c);
        // Only now: the removals above went through the channel like any call.
        detached = true;
        Object reg;
        synchronized (lock) {
            client = null;
            reg = registration;
            registration = null;
        }
        // A script addition the page has not answered keeps the registration until it does (its removal follows).
        synchronized (pendingLock) {
            for (Iterator<Map.Entry<Integer, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Pending> e = it.next();
                if (unansweredRawAdditions.contains(e.getValue().out)) {
                    lateAdditions.add(e.getKey());
                    it.remove();
                }
            }
            unansweredRawAdditions.clear();
            if (!lateAdditions.isEmpty()) {
                lingering = reg;
                reg = null;
            }
        }
        dispose(reg);
        failPending(new IllegalStateException("the adapter was detached"));
        IllegalStateException gone = new IllegalStateException("the adapter was detached");
        for (CompletableFuture<?> f : new ArrayList<CompletableFuture<?>>(inProgress)) {
            f.completeExceptionally(gone);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static boolean onEdt() {
        return Thread.currentThread().getName().startsWith("AWT-EventQueue");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
