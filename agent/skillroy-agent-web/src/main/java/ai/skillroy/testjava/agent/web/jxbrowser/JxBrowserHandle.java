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

package ai.skillroy.testjava.agent.web.jxbrowser;

import ai.skillroy.testjava.agent.api.spi.web.BitmapSource;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.api.spi.web.WebFrameGoneException;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.agent.api.spi.web.WebSubscription;
import ai.skillroy.testjava.agent.web.reflect.Listeners;
import ai.skillroy.testjava.agent.web.reflect.ReflectiveException;
import ai.skillroy.testjava.agent.web.reflect.ResolvedApi;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One JxBrowser browser (phase4-contracts.md section 4.5), operated through the F21 members by reflection.
 *
 * <p><b>Evaluation.</b> {@code Frame.executeJavaScript(String, Consumer)} from an agent worker, with the expression in
 * the wrapper of {@link JxBrowserAdapter#wrap}: its value crosses as a {@code String} (a string as itself, anything
 * else as the script library's JSON text, a thrown error as its message and stack), so no live vendor object is left
 * behind (browser-spi F24). {@code awaitPromise} goes through the library's private slot: a first evaluation defers the
 * value ({@code WebLib.deferExpression}), later ones collect it ({@code WebLib.collectExpression}) every few
 * milliseconds until it settles. Frames are keyed per {@code Frame} identity ("f1", "f2", ...); a key whose frame is
 * no longer among the browser's frames raises {@link WebFrameGoneException}.
 *
 * <p><b>Events</b> come from {@code on(Class, Observer)} with {@code java.lang.reflect.Proxy} observers
 * ({@code FrameLoadFinished}, {@code FrameLoadFailed}, {@code NavigationFinished}, {@code ConsoleMessageReceived},
 * {@code BrowserClosed}), handed to the adapter's threads in order. <b>Init scripts</b> run after
 * {@code FrameDocumentLoadFinished} (timing {@code DOCUMENT_LOADED}); with the agent option
 * {@code web.jxbrowser.chainInjectJs=true} they run in a chained {@code InjectJsCallback} instead (timing
 * {@code DOCUMENT_START}), which calls the previous occupant first and puts it back on detach.
 *
 * <p><b>Bounded vendor calls.</b> The product's calls are IPC to the Chromium process and can hang (browser-spi C1):
 * every vendor call made for a caller runs on the adapter's threads, never on the calling worker before a future
 * exists, so that the caller's bounded wait covers it. {@code evaluate} checks closure and finds the frame in its own
 * task; the accessors ({@code isClosed()}, {@code url()}, {@code viewport()}, {@code rendering()},
 * {@code frames()}) wait at most {@value #ACCESSOR_BOUND_MS} ms and then answer what they know (open, "", unknown,
 * no frames), with at most one such call in flight per accessor (a hung one is waited for, not repeated); detach's
 * vendor calls run on the adapter's threads within detach's bound. Only the registration of the observers and of a
 * chained callback ({@code on(...)}, {@code set(...)}) runs on the calling worker.
 *
 * <p>Thread-safe. Equality is the identity of the vendor's {@code Browser}.
 */
final class JxBrowserHandle implements WebBrowser {

    /** How long an accessor that asks the vendor waits. */
    static final long ACCESSOR_BOUND_MS = 2000;
    /** First and last delay between two collections of an awaited value. */
    static final long FIRST_COLLECT_MS = 5;
    static final long MAX_COLLECT_MS = 50;

    private final JxBrowserAdapter adapter;
    private final ResolvedApi api;
    private final Object browser;
    private final Listeners listeners = new Listeners();
    private final Executor events;
    private final Map<Object, String> frameKeys = new IdentityHashMap<Object, String>();
    private final Map<String, Object> framesByKey = new HashMap<String, Object>();
    private final List<Script> scripts = new CopyOnWriteArrayList<Script>();
    private final List<Object> subscriptions = new ArrayList<Object>();
    /** The bounded vendor calls in flight, by accessor; guarded by itself. */
    private final Map<String, CompletableFuture<Object>> inFlight = new HashMap<String, CompletableFuture<Object>>();
    /** The futures of calls in progress for a caller, failed by {@link #detach} (their late answers are dropped). */
    private final Set<CompletableFuture<?>> pending = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<CompletableFuture<?>, Boolean>());
    private final AtomicBoolean closedDelivered = new AtomicBoolean();
    private final Set<WebCapability> fixed;
    private int nextKey;
    private boolean observing;
    private boolean chained;
    private Object chainProxy;
    private Object chainPrevious;
    private volatile Object view;
    private volatile boolean closed;
    private volatile boolean detached;
    private volatile Dimension originalSize;
    private volatile boolean resized;

    JxBrowserHandle(JxBrowserAdapter adapter, ResolvedApi api, Object browser) {
        this.adapter = adapter;
        this.api = api;
        this.browser = browser;
        this.events = adapter.threads.serial();
        EnumSet<WebCapability> caps = EnumSet.of(WebCapability.EVALUATE, WebCapability.AWAIT_PROMISE);
        if (api.complete("FRAMES")) {
            caps.add(WebCapability.FRAMES);
        }
        if (api.complete("events") && api.complete("navigation")) {
            caps.add(WebCapability.EVENTS_LOAD);
        }
        if (api.complete("events") && api.complete("EVENTS_CONSOLE")) {
            caps.add(WebCapability.EVENTS_CONSOLE);
        }
        if (api.complete("RESIZE") && api.complete("size")) {
            caps.add(WebCapability.RESIZE);
        }
        if (api.complete("BITMAP") && api.complete("size")) {
            caps.add(WebCapability.BITMAP);
        }
        if (api.complete("CDP_TARGET_ID")) {
            caps.add(WebCapability.CDP_TARGET_ID);
        }
        this.fixed = Collections.unmodifiableSet(caps);
    }

    /** The view a seed showed this browser in. */
    void view(Object v) {
        this.view = v;
    }

    // ------------------------------------------------------------------------------------------------ identity

    @Override
    public String adapterId() {
        return JxBrowserAdapter.ID;
    }

    @Override
    public Object vendorObject() {
        return browser;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JxBrowserHandle && ((JxBrowserHandle) o).browser == browser;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(browser);
    }

    @Override
    public String toString() {
        return "JxBrowserHandle{" + browser.getClass().getName() + "@" + Integer.toHexString(hashCode()) + "}";
    }

    @Override
    public String nativeId() {
        return null;
    }

    @Override
    public Set<WebCapability> capabilities() {
        if (closed) {
            return Collections.emptySet();
        }
        if (chainAvailable()) {
            EnumSet<WebCapability> caps = EnumSet.copyOf(fixed);
            caps.add(WebCapability.INIT_SCRIPT_DOCUMENT_START);
            return Collections.unmodifiableSet(caps);
        }
        return fixed;
    }

    private boolean chainAvailable() {
        return adapter.options().chainInjectJs() && api.complete("chain");
    }

    boolean closedFlag() {
        return closed;
    }

    /** {@inheritDoc} Asks the vendor on the adapter's threads, waiting at most {@value #ACCESSOR_BOUND_MS} ms. */
    @Override
    public boolean isClosed() {
        if (closed) {
            return true;
        }
        if (Boolean.TRUE.equals(bounded("browser.isClosed", null))) {
            markClosed();
            return true;
        }
        return false;
    }

    /** The vendor's {@code isClosed()}, asked on the calling thread (the adapter's own threads only). */
    private boolean closedNow() {
        if (closed) {
            return true;
        }
        try {
            if (Boolean.TRUE.equals(api.invoke("browser.isClosed", browser))) {
                markClosed();
                return true;
            }
        } catch (ReflectiveException e) {
            // unknown: open
        }
        return false;
    }

    private void markClosed() {
        closed = true;
        if (closedDelivered.compareAndSet(false, true)) {
            try {
                events.execute(new Runnable() {
                    @Override
                    public void run() {
                        listeners.deliver(JxBrowserHandle.this, WebEvent.closed());
                    }
                });
            } catch (RejectedExecutionException e) {
                listeners.deliver(this, WebEvent.closed());
            }
        }
    }

    @Override
    public String url() {
        Object u = bounded("browser.url", null);
        return u == null ? "" : String.valueOf(u);
    }

    @Override
    public Component component() {
        Object v = view;
        return v instanceof Component ? (Component) v : null;
    }

    @Override
    public WebRendering rendering() {
        Integer line = adapter.line();
        if (line != null && line.intValue() >= 9) {
            return WebRendering.UNKNOWN;
        }
        if (!api.complete("options") || !api.complete("rendering")) {
            return WebRendering.UNKNOWN;
        }
        Object mode = singleFlight("rendering", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Object engine = api.invoke("browser.engine", browser);
                return api.invoke("options.renderingMode", api.invoke("engine.options", engine));
            }
        }, null);
        if (mode == null) {
            return WebRendering.UNKNOWN;
        }
        String name = mode instanceof Enum ? ((Enum<?>) mode).name() : String.valueOf(mode);
        if ("HARDWARE_ACCELERATED".equals(name)) {
            return WebRendering.WINDOWED;
        }
        // An off-screen view is lightweight (the vendor's rendering guide); a view that is not displayable has no
        // peer yet, so isLightweight() can only be asked once it is.
        Component c = component();
        if ("OFF_SCREEN".equals(name) && c != null && (!c.isDisplayable() || c.isLightweight())) {
            return WebRendering.OFFSCREEN_LIGHTWEIGHT;
        }
        return WebRendering.UNKNOWN;
    }

    @Override
    public Dimension viewport() {
        if (!fixed.contains(WebCapability.RESIZE)) {
            return null;
        }
        Dimension d = sizeNow();
        return d == null || d.width <= 0 || d.height <= 0 ? null : d;
    }

    private Dimension sizeNow() {
        Object s = bounded("browser.size", null);
        if (s == null) {
            return null;
        }
        try {
            Object w = api.invoke("size.width", s);
            Object h = api.invoke("size.height", s);
            if (w instanceof Number && h instanceof Number) {
                return new Dimension(((Number) w).intValue(), ((Number) h).intValue());
            }
        } catch (ReflectiveException e) {
            // unknown
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ threads

    /** A no-argument vendor call on the adapter's threads, waited for at most {@link #ACCESSOR_BOUND_MS}. */
    private Object bounded(final String role, Object dflt) {
        return singleFlight(role, new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return api.invoke(role, browser);
            }
        }, dflt);
    }

    /**
     * A vendor call on the adapter's threads, waited for at most {@link #ACCESSOR_BOUND_MS}; while the call of the same
     * key is still in flight (the vendor hangs) it is waited for again instead of being sent once more. Answers
     * {@code dflt} on the EDT (never waited on), on a failure and on a timeout.
     */
    private Object singleFlight(String key, Callable<Object> task, Object dflt) {
        if (onEdt()) {
            return dflt;
        }
        CompletableFuture<Object> f;
        boolean start = false;
        synchronized (inFlight) {
            f = inFlight.get(key);
            if (f == null || f.isDone()) {
                f = new CompletableFuture<Object>();
                inFlight.put(key, f);
                start = true;
            }
        }
        if (start) {
            submit(task, f);
        }
        try {
            Object v = f.get(ACCESSOR_BOUND_MS, TimeUnit.MILLISECONDS);
            return v == null ? dflt : v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return dflt;
        } catch (Exception e) {
            return dflt;
        }
    }

    private static boolean onEdt() {
        return Thread.currentThread().getName().startsWith("AWT-EventQueue");
    }

    /** Runs the task on the adapter's threads and completes {@code out} with its value or failure. */
    private <T> void submit(final Callable<T> task, final CompletableFuture<T> out) {
        try {
            adapter.threads.executor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        out.complete(task.call());
                    } catch (ReflectiveException e) {
                        out.completeExceptionally(new IllegalStateException(e.getMessage()));
                    } catch (Throwable t) {
                        out.completeExceptionally(t);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            out.completeExceptionally(new IllegalStateException("the adapter was detached"));
        }
    }

    private void timeout(final CompletableFuture<?> out, final long ms) {
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

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(t);
        return f;
    }

    /**
     * A future of a caller's call, failed by {@link #detach} if it has not completed by then (the adapter's threads
     * end there, and a vendor callback that arrives later is dropped).
     */
    private <T> CompletableFuture<T> tracked(final CompletableFuture<T> out) {
        pending.add(out);
        out.whenComplete(new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T v, Throwable t) {
                pending.remove(out);
            }
        });
        if (detached) {
            out.completeExceptionally(new IllegalStateException("the adapter was detached"));
        }
        return out;
    }

    private <T> CompletableFuture<T> refuse(WebCapability flag) {
        if (detached) {
            return failed(new IllegalStateException("the adapter was detached"));
        }
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        return refuseFlag(flag);
    }

    /** As {@link #refuse} without asking the vendor (the closure the handle already knows of). */
    private <T> CompletableFuture<T> refuseKnown(WebCapability flag) {
        if (detached) {
            return failed(new IllegalStateException("the adapter was detached"));
        }
        if (closed) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        return refuseFlag(flag);
    }

    private <T> CompletableFuture<T> refuseFlag(WebCapability flag) {
        if (!capabilities().contains(flag)) {
            return failed(new UnsupportedOperationException("adapter jxbrowser has no " + flag.name()
                    + " for this browser: " + missingFor(flag)));
        }
        return null;
    }

    private String missingFor(WebCapability flag) {
        for (String group : new String[] {flag.name(), "size", "events", "navigation"}) {
            String m = api.firstMissing(group);
            if (m != null) {
                return m;
            }
        }
        return "not supported";
    }

    // ------------------------------------------------------------------------------------------------ evaluate

    @Override
    public CompletableFuture<String> evaluate(final WebEval request) {
        CompletableFuture<String> refused = refuseKnown(request.awaitPromise() ? WebCapability.AWAIT_PROMISE
                : WebCapability.EVALUATE);
        if (refused != null) {
            return refused;
        }
        final CompletableFuture<String> out = tracked(new CompletableFuture<String>());
        timeout(out, request.timeoutMs());
        // The vendor is asked (closure, frames) on the adapter's threads, within the caller's bound.
        try {
            adapter.threads.executor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        evaluateNow(request, out);
                    } catch (RuntimeException e) {
                        out.completeExceptionally(e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            out.completeExceptionally(new IllegalStateException("the adapter was detached"));
        }
        return out;
    }

    /** The evaluation itself, on the adapter's threads. */
    private void evaluateNow(final WebEval request, final CompletableFuture<String> out) {
        if (out.isDone()) {
            return;
        }
        if (closedNow()) {
            out.completeExceptionally(new IllegalStateException("the browser has closed"));
            return;
        }
        final Object frame;
        try {
            frame = frameFor(request.frameKey());
        } catch (WebFrameGoneException e) {
            out.completeExceptionally(e);
            return;
        } catch (IllegalStateException e) {
            out.completeExceptionally(e);
            return;
        }
        if (!request.awaitPromise()) {
            run(frame, request.frameKey(), JxBrowserAdapter.wrap(request.expression()), out);
            return;
        }
        final CompletableFuture<String> deferred = new CompletableFuture<String>();
        run(frame, request.frameKey(), JxBrowserAdapter.wrap(WebLib.deferExpression(request.expression())),
                deferred);
        deferred.whenComplete(new BiConsumer<String, Throwable>() {
            @Override
            public void accept(String idJson, Throwable failure) {
                if (failure != null) {
                    out.completeExceptionally(failure);
                    return;
                }
                Object id = Json.parse(idJson);
                if (!(id instanceof String)) {
                    out.completeExceptionally(new IllegalStateException("the deferred value has no id"));
                    return;
                }
                collect(frame, request.frameKey(), (String) id, out, FIRST_COLLECT_MS);
            }
        });
    }

    /** Runs a wrapped expression; {@code out} completes with the JSON text of its value, or fails as the SPI says. */
    private void run(Object frame, final String frameKey, String script, final CompletableFuture<String> out) {
        Consumer<Object> consumer = new Consumer<Object>() {
            @Override
            public void accept(final Object result) {
                // On the vendor's thread: hand over at once.
                try {
                    adapter.threads.executor().execute(new Runnable() {
                        @Override
                        public void run() {
                            decode(result, out);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    out.completeExceptionally(new IllegalStateException("the adapter was detached"));
                }
            }
        };
        try {
            api.invoke("frame.executeJavaScriptAsync", frame, script, consumer);
        } catch (ReflectiveException e) {
            if (frameKey != null) {
                out.completeExceptionally(new WebFrameGoneException(frameKey));
            } else {
                out.completeExceptionally(new IllegalStateException("executeJavaScript failed: " + e.getMessage()));
            }
        }
    }

    /**
     * Decodes the wrapper's tagged text ({@code s}tring, {@code j}son, {@code e}rror). A text longer than
     * {@link WebLib#MAX_CHANNEL_CHARS} is refused before it is parsed, {@code INTERNAL_ERROR {reason: "oversized"}}, and
     * a script error's message and stack are cut as {@link WebLib#scriptMessage} and {@link WebLib#scriptStack} cut
     * them (security fix S43: a page may replace the library).
     */
    static void decode(Object result, CompletableFuture<String> out) {
        if (!(result instanceof String) || ((String) result).isEmpty()) {
            out.completeExceptionally(new IllegalStateException("the page returned no result"));
            return;
        }
        String text = (String) result;
        if (text.length() > WebLib.MAX_CHANNEL_CHARS) {
            RpcError e = WebLib.oversized("the page's result", WebLib.MAX_CHANNEL_CHARS);
            out.completeExceptionally(new RpcException(e.code(), e.message(), e.data()));
            return;
        }
        char tag = text.charAt(0);
        String rest = text.substring(1);
        try {
            if (tag == 's') {
                out.complete(Json.write(rest));
                return;
            }
            Map<String, Object> wrapper = Json.parseObject(rest);
            if (tag == 'j') {
                if (Boolean.TRUE.equals(wrapper.get("truncated"))) {
                    out.completeExceptionally(new IllegalStateException("the value's JSON exceeds "
                            + WebLib.MAX_JSON_CHARS + " characters, the limit of the jxbrowser channel"));
                    return;
                }
                Object json = wrapper.get("json");
                out.complete(json == null ? "null" : String.valueOf(json));
                return;
            }
            if (tag == 'e') {
                Map<String, Object> error = Json.parseObject(String.valueOf(wrapper.get("json")));
                Object message = error.get("message");
                Object stack = error.get("stack");
                out.completeExceptionally(new WebScriptException(message == null ? "script error"
                        : WebLib.scriptMessage(String.valueOf(message)), stack == null ? null
                        : WebLib.scriptStack(String.valueOf(stack))));
                return;
            }
        } catch (RuntimeException e) {
            out.completeExceptionally(new IllegalStateException("the page's result cannot be read: " + e));
            return;
        }
        out.completeExceptionally(new IllegalStateException("the page's result has an unknown form"));
    }

    /** Collects a deferred value, again and again (5 ms doubling to 50 ms) until it settles or {@code out} ends. */
    private void collect(final Object frame, final String frameKey, final String id,
                         final CompletableFuture<String> out, final long delay) {
        if (out.isDone()) {
            return;
        }
        final CompletableFuture<String> state = new CompletableFuture<String>();
        run(frame, frameKey, JxBrowserAdapter.wrap(WebLib.collectExpression(id)), state);
        state.whenComplete(new BiConsumer<String, Throwable>() {
            @Override
            public void accept(String json, Throwable failure) {
                if (failure != null) {
                    out.completeExceptionally(failure);
                    return;
                }
                Map<String, Object> s;
                try {
                    s = Json.parseObject((String) Json.parse(json));
                } catch (RuntimeException e) {
                    out.completeExceptionally(new IllegalStateException("the awaited value cannot be read"));
                    return;
                }
                String st = String.valueOf(s.get("state"));
                if ("pending".equals(st)) {
                    try {
                        adapter.threads.schedule(new Runnable() {
                            @Override
                            public void run() {
                                collect(frame, frameKey, id, out, Math.min(MAX_COLLECT_MS, delay * 2));
                            }
                        }, delay);
                    } catch (RejectedExecutionException e) {
                        out.completeExceptionally(new IllegalStateException("the adapter was detached"));
                    }
                } else if ("fulfilled".equals(st)) {
                    decode("j" + s.get("text"), out);
                } else if ("rejected".equals(st)) {
                    Object message = s.get("message");
                    Object stack = s.get("stack");
                    out.completeExceptionally(new WebScriptException(message == null ? "promise rejected"
                            : WebLib.scriptMessage(String.valueOf(message)), stack == null ? null
                            : WebLib.scriptStack(String.valueOf(stack))));
                } else {
                    out.completeExceptionally(new IllegalStateException("the awaited value was lost: the document "
                            + "was replaced"));
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ frames

    /** The vendor frame of a key (null: the main frame). */
    private Object frameFor(String key) throws WebFrameGoneException {
        if (key == null) {
            Object main;
            try {
                main = JxBrowserAdapter.unwrap(api.invoke("browser.mainFrame", browser));
            } catch (ReflectiveException e) {
                throw new IllegalStateException("the main frame cannot be read: " + e.getMessage());
            }
            if (main == null) {
                throw new IllegalStateException("the browser has no document yet");
            }
            return main;
        }
        Object frame;
        synchronized (frameKeys) {
            frame = framesByKey.get(key);
        }
        if (frame == null || !currentFrames().containsKey(frame)) {
            throw new WebFrameGoneException(key);
        }
        return frame;
    }

    /** The browser's frames now, by identity. */
    private Map<Object, Boolean> currentFrames() {
        Map<Object, Boolean> out = new IdentityHashMap<Object, Boolean>();
        try {
            if (api.has("browser.frames")) {
                for (Object f : JxBrowserAdapter.list(api.invoke("browser.frames", browser))) {
                    out.put(f, Boolean.TRUE);
                }
            }
            Object main = JxBrowserAdapter.unwrap(api.invoke("browser.mainFrame", browser));
            if (main != null) {
                out.put(main, Boolean.TRUE);
            }
        } catch (ReflectiveException e) {
            // none known
        }
        return out;
    }

    private String keyOf(Object frame) {
        synchronized (frameKeys) {
            String key = frameKeys.get(frame);
            if (key == null) {
                key = "f" + (++nextKey);
                frameKeys.put(frame, key);
                framesByKey.put(key, frame);
            }
            return key;
        }
    }

    private boolean isMain(Object frame) {
        try {
            return Boolean.TRUE.equals(api.invoke("frame.isMain", frame));
        } catch (ReflectiveException e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<WebFrame> frames() {
        if (closed || !fixed.contains(WebCapability.FRAMES) || onEdt()) {
            return Collections.emptyList();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACCESSOR_BOUND_MS);
        Object walked = singleFlight("frames", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Object main = JxBrowserAdapter.unwrap(api.invoke("browser.mainFrame", browser));
                List<Object[]> out = new ArrayList<Object[]>();
                if (main != null) {
                    walk(main, null, out, 0);
                }
                return out;
            }
        }, null);
        if (!(walked instanceof List) || ((List<?>) walked).isEmpty()) {
            return Collections.emptyList();
        }
        List<Object[]> found = (List<Object[]>) walked;
        Map<Object, CompletableFuture<String>> urls = new IdentityHashMap<Object, CompletableFuture<String>>();
        for (Object[] f : found) {
            CompletableFuture<String> u = new CompletableFuture<String>();
            run(f[0], keyOf(f[0]), JxBrowserAdapter.wrap("location.href"), u);
            urls.put(f[0], u);
        }
        List<WebFrame> out = new ArrayList<WebFrame>();
        for (Object[] f : found) {
            String url = "";
            try {
                Object v = Json.parse(urls.get(f[0]).get(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline
                        - System.nanoTime())), TimeUnit.MILLISECONDS));
                url = v == null ? "" : String.valueOf(v);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                urls.get(f[0]).cancel(false);
            }
            boolean isMain = f[1] == null;
            out.add(new WebFrame(keyOf(f[0]), isMain ? null : keyOf(f[1]), (String) f[2], url, isMain));
        }
        return out;
    }

    private void walk(Object frame, Object parent, List<Object[]> out, int depth) {
        if (depth > 50) {
            return;
        }
        String name = "";
        try {
            Object n = api.invoke("frame.name", frame);
            name = n == null ? "" : String.valueOf(n);
        } catch (ReflectiveException e) {
            // unnamed
        }
        out.add(new Object[] {frame, parent, name});
        try {
            for (Object child : JxBrowserAdapter.list(api.invoke("frame.children", frame))) {
                walk(child, frame, out, depth + 1);
            }
        } catch (ReflectiveException e) {
            // no children known
        }
    }

    @Override
    public CompletableFuture<String> cdpTargetId() {
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        if (!fixed.contains(WebCapability.CDP_TARGET_ID)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<String> out = tracked(new CompletableFuture<String>());
        submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Object devTools = api.invoke("browser.devTools", browser);
                Object url = JxBrowserAdapter.unwrap(api.invoke("devTools.remoteDebuggingUrl", devTools));
                return url == null ? null : lastSegment(String.valueOf(url));
            }
        }, out);
        return out;
    }

    /** The last path segment of a URL, without a query or fragment, or null for an empty one. */
    static String lastSegment(String url) {
        String u = url.trim();
        int slash = u.lastIndexOf('/');
        String s = slash >= 0 ? u.substring(slash + 1) : u;
        int cut = s.length();
        for (char c : new char[] {'?', '#', '&'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        s = s.substring(0, cut);
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------------------------------------ events

    @Override
    public WebSubscription subscribe(WebEventListener listener) {
        WebSubscription s = listeners.add(listener);
        if (!closed && !detached) {
            observe();
        }
        return s;
    }

    /** Registers the adapter's observers once (load, navigation, document, console, closure). */
    private void observe() {
        synchronized (subscriptions) {
            if (observing || !api.complete("events")) {
                return;
            }
            observing = true;
            Object observer = api.implement("observer", new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("on".equals(method.getName()) && args != null && args.length == 1) {
                        final Object event = args[0];
                        try {
                            events.execute(new Runnable() {
                                @Override
                                public void run() {
                                    onVendorEvent(event);
                                }
                            });
                        } catch (RejectedExecutionException ignored) {
                            // detached
                        }
                    }
                    return null;
                }
            });
            try {
                if (api.complete("navigation")) {
                    Object navigation = api.invoke("browser.navigation", browser);
                    for (String type : new String[] {"event.frameLoadFinished", "event.frameLoadFailed",
                            "event.navigationFinished"}) {
                        subscriptions.add(api.invoke("navigation.on", navigation, api.type(type), observer));
                    }
                    if (api.complete("initScript")) {
                        subscriptions.add(api.invoke("navigation.on", navigation,
                                api.type("event.frameDocumentLoadFinished"), observer));
                    }
                }
                if (api.complete("EVENTS_CONSOLE")) {
                    subscriptions.add(api.invoke("browser.on", browser, api.type("event.consoleMessageReceived"),
                            observer));
                }
                subscriptions.add(api.invoke("browser.on", browser, api.type("event.browserClosed"), observer));
            } catch (ReflectiveException e) {
                // the events that registered still arrive
            }
        }
    }

    /** One vendor event, on the serial executor (in order). */
    private void onVendorEvent(Object event) {
        if (detached) {
            return;
        }
        try {
            if (api.isInstance("event.frameLoadFinished", event)) {
                Object frame = api.invoke("frameLoadFinished.frame", event);
                Object url = api.invoke("frameLoadFinished.url", event);
                listeners.deliver(this, WebEvent.loadFinished(keyFor(frame), str(url)));
            } else if (api.isInstance("event.frameLoadFailed", event)) {
                Object frame = api.invoke("frameLoadFailed.frame", event);
                Object error = api.has("frameLoadFailed.error") ? api.get("frameLoadFailed.error", event) : null;
                listeners.deliver(this, WebEvent.loadFailed(keyFor(frame), "", error == null ? "load failed"
                        : String.valueOf(error)));
            } else if (api.isInstance("event.navigationFinished", event)) {
                boolean main = Boolean.TRUE.equals(api.invoke("navigationFinished.isInMainFrame", event));
                Object frame = main ? null : api.invoke("navigationFinished.frame", event);
                String key = main ? null : keyOf(frame);
                String url = str(api.invoke("navigationFinished.url", event));
                if (Boolean.TRUE.equals(api.invoke("navigationFinished.hasCommitted", event))) {
                    listeners.deliver(this, WebEvent.navigated(key, url));
                } else if (api.has("navigationFinished.error")) {
                    Object error = api.invoke("navigationFinished.error", event);
                    listeners.deliver(this, WebEvent.loadFailed(key, url, error == null ? "navigation failed"
                            : String.valueOf(error)));
                }
            } else if (api.isInstance("event.frameDocumentLoadFinished", event)) {
                Object frame = api.invoke("frameDocumentLoadFinished.frame", event);
                runLateScripts(frame);
            } else if (api.isInstance("event.consoleMessageReceived", event)) {
                Object message = api.invoke("consoleMessageReceived.consoleMessage", event);
                String level = level(api.invoke("consoleMessage.level", message));
                String text = str(api.invoke("consoleMessage.message", message));
                String source = api.has("consoleMessage.source") ? str(api.invoke("consoleMessage.source", message))
                        : null;
                Object line = api.has("consoleMessage.lineNumber") ? api.invoke("consoleMessage.lineNumber", message)
                        : null;
                listeners.deliver(this, WebEvent.console(level, text, source == null || source.isEmpty() ? null
                        : source, line instanceof Number ? ((Number) line).intValue() : 0));
            } else if (api.isInstance("event.browserClosed", event)) {
                markClosed();
            }
        } catch (ReflectiveException e) {
            // an event that cannot be read is dropped
        }
    }

    /** The key of a frame for an event: null for the main frame. */
    private String keyFor(Object frame) {
        if (frame == null || isMain(frame)) {
            return null;
        }
        return keyOf(frame);
    }

    /** {@code debug}, {@code log}, {@code info}, {@code warning} or {@code error} from a vendor level. */
    static String level(Object vendorLevel) {
        String name = vendorLevel instanceof Enum ? ((Enum<?>) vendorLevel).name() : String.valueOf(vendorLevel);
        String n = name.toUpperCase(Locale.ROOT);
        if (n.contains("ERROR")) {
            return "error";
        }
        if (n.contains("WARN")) {
            return "warning";
        }
        if (n.contains("INFO")) {
            return "info";
        }
        if (n.contains("DEBUG") || n.contains("VERBOSE")) {
            return "debug";
        }
        if (n.contains("LOG")) {
            return "log";
        }
        return name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------------ scripts

    @Override
    public WebInitScript addInitScript(String source) {
        if (detached) {
            throw new IllegalStateException("the adapter was detached");
        }
        if (isClosed()) {
            throw new IllegalStateException("the browser has closed");
        }
        boolean chain = chainAvailable();
        if (!chain && !(api.complete("initScript") && api.complete("events") && api.complete("navigation"))) {
            throw new UnsupportedOperationException("adapter jxbrowser cannot add init scripts to this browser: "
                    + (api.firstMissing("initScript") != null ? api.firstMissing("initScript")
                    : api.firstMissing("events")));
        }
        Script s = new Script(source, chain ? WebInitScript.Timing.DOCUMENT_START
                : WebInitScript.Timing.DOCUMENT_LOADED);
        scripts.add(s);
        if (chain) {
            chain();
        } else {
            observe();
        }
        return s;
    }

    /** Runs the scripts that wait for the document (no chain) in a frame whose document loaded. */
    private void runLateScripts(Object frame) {
        for (Script s : scripts) {
            if (s.timing == WebInitScript.Timing.DOCUMENT_LOADED) {
                try {
                    api.invoke("frame.executeJavaScriptAsync", frame, s.source, new Consumer<Object>() {
                        @Override
                        public void accept(Object ignored) {
                            // nothing to collect
                        }
                    });
                } catch (ReflectiveException e) {
                    // the frame went away
                }
            }
        }
    }

    /** Chains {@code InjectJsCallback} once: the previous occupant first, then the scripts (the opt-in). */
    private void chain() {
        synchronized (subscriptions) {
            if (chained) {
                return;
            }
            try {
                Class<?> type = api.type("injectJs");
                chainPrevious = JxBrowserAdapter.unwrap(api.invoke("browser.get", browser, type));
                chainProxy = api.implement("injectJs", new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (!"on".equals(method.getName()) || args == null || args.length != 1) {
                            return null;
                        }
                        Object response = null;
                        Object previous = chainPrevious;
                        if (previous != null) {
                            try {
                                response = method.invoke(previous, args);
                            } catch (InvocationTargetException e) {
                                // the application's own callback failed: its own concern
                            }
                        }
                        try {
                            Object frame = api.invoke("injectJs.params.frame", args[0]);
                            for (Script s : scripts) {
                                if (s.timing == WebInitScript.Timing.DOCUMENT_START) {
                                    api.invoke("frame.executeJavaScript", frame, s.source);
                                }
                            }
                        } catch (ReflectiveException e) {
                            // the document goes on without the scripts
                        }
                        return response != null ? response : api.invoke("injectJs.response.proceed", null);
                    }
                });
                api.invoke("browser.set", browser, type, chainProxy);
                chained = true;
            } catch (ReflectiveException e) {
                throw new IllegalStateException("chaining InjectJsCallback failed: " + e.getMessage());
            }
        }
    }

    /** Puts the previous {@code InjectJsCallback} back, when ours is still the occupant. */
    private void unchain() {
        synchronized (subscriptions) {
            if (!chained) {
                return;
            }
            chained = false;
            try {
                Class<?> type = api.type("injectJs");
                Object current = JxBrowserAdapter.unwrap(api.invoke("browser.get", browser, type));
                if (current == chainProxy) {
                    if (chainPrevious != null) {
                        api.invoke("browser.set", browser, type, chainPrevious);
                    } else {
                        api.invoke("browser.remove", browser, type);
                    }
                }
            } catch (ReflectiveException e) {
                // the browser is gone
            }
            chainProxy = null;
            chainPrevious = null;
        }
    }

    /** One init script. */
    private final class Script implements WebInitScript {
        final String source;
        final Timing timing;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        Script(String source, Timing timing) {
            this.source = source;
            this.timing = timing;
        }

        @Override
        public Timing timing() {
            return timing;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                scripts.remove(this);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ view

    @Override
    public CompletableFuture<Void> resize(final int width, final int height) {
        CompletableFuture<Void> refused = refuse(WebCapability.RESIZE);
        if (refused != null) {
            return refused;
        }
        CompletableFuture<Void> out = tracked(new CompletableFuture<Void>());
        submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                if (!resized) {
                    originalSize = sizeDirect();
                }
                api.invoke("browser.resize", browser, Integer.valueOf(width), Integer.valueOf(height));
                resized = true;
                return null;
            }
        }, out);
        return out;
    }

    private Dimension sizeDirect() throws ReflectiveException {
        Object s = api.invoke("browser.size", browser);
        Object w = api.invoke("size.width", s);
        Object h = api.invoke("size.height", s);
        return w instanceof Number && h instanceof Number ? new Dimension(((Number) w).intValue(),
                ((Number) h).intValue()) : null;
    }

    @Override
    public CompletableFuture<Void> clearResize() {
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        if (!resized) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> out = tracked(new CompletableFuture<Void>());
        submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Dimension o = originalSize;
                if (o != null && o.width > 0 && o.height > 0) {
                    api.invoke("browser.resize", browser, Integer.valueOf(o.width), Integer.valueOf(o.height));
                }
                resized = false;
                return null;
            }
        }, out);
        return out;
    }

    @Override
    public CompletableFuture<BufferedImage> bitmap() {
        CompletableFuture<BufferedImage> refused = refuse(WebCapability.BITMAP);
        if (refused != null) {
            return refused;
        }
        CompletableFuture<BufferedImage> out = tracked(new CompletableFuture<BufferedImage>());
        submit(new Callable<BufferedImage>() {
            @Override
            public BufferedImage call() throws Exception {
                Object bitmap = api.invoke("browser.bitmap", browser);
                Object pixels = api.invoke("bitmap.pixels", bitmap);
                Object size = api.invoke("bitmap.size", bitmap);
                Object w = api.invoke("size.width", size);
                Object h = api.invoke("size.height", size);
                if (!(pixels instanceof byte[]) || !(w instanceof Number) || !(h instanceof Number)) {
                    throw new IllegalStateException("the bitmap has no pixels");
                }
                return toImage((byte[]) pixels, ((Number) w).intValue(), ((Number) h).intValue());
            }
        }, out);
        return out;
    }

    /** BGRA, pre-multiplied (JxBrowser's {@code Bitmap.pixels()}), to an image of {@code TYPE_INT_ARGB}. */
    static BufferedImage toImage(byte[] bgra, int width, int height) {
        if (width <= 0 || height <= 0 || bgra.length < width * height * 4) {
            throw new IllegalStateException("the bitmap is " + width + " x " + height + " with " + bgra.length
                    + " bytes");
        }
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            int b = bgra[4 * i] & 0xff;
            int g = bgra[4 * i + 1] & 0xff;
            int r = bgra[4 * i + 2] & 0xff;
            int a = bgra[4 * i + 3] & 0xff;
            if (a == 0) {
                argb[i] = 0;
                continue;
            }
            if (a < 255) {
                r = Math.min(255, (r * 255 + a / 2) / a);
                g = Math.min(255, (g * 255 + a / 2) / a);
                b = Math.min(255, (b * 255 + a / 2) / a);
            }
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    /**
     * The SPI's reload (A37), through the product: {@code navigation().reload()} / {@code reloadIgnoringCache()} on
     * the adapter's threads, which works where the page cannot run a script; where the descriptor's
     * {@code navigation} or {@code reload} roles do not resolve, the SPI's default, {@code location.reload()} through
     * the page ({@link #evaluate}). Completes once the reload was requested.
     */
    @Override
    public CompletableFuture<Void> reload(final boolean ignoreCache) {
        if (isClosed()) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        if (!api.complete("navigation") || !api.complete("reload")) {
            return WebBrowser.super.reload(ignoreCache);
        }
        CompletableFuture<Void> out = tracked(new CompletableFuture<Void>());
        submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Object navigation = api.invoke("browser.navigation", browser);
                api.invoke(ignoreCache ? "navigation.reloadIgnoringCache" : "navigation.reload", navigation);
                return null;
            }
        }, out);
        return out;
    }

    /** {@code VENDOR}: the bitmap is the product's own {@code Browser.bitmap()} (A37). */
    @Override
    public BitmapSource bitmapSource() {
        return BitmapSource.VENDOR;
    }

    // ------------------------------------------------------------------------------------------------ detach

    /**
     * Fails the calls in progress ("the adapter was detached"), then unsubscribes the observers, drops the scripts,
     * unchains, restores the size; the vendor calls run on the adapter's threads and are waited for until the deadline
     * (not on the EDT); never throws.
     */
    void detach(final long deadlineNanos) {
        detached = true;
        IllegalStateException gone = new IllegalStateException("the adapter was detached");
        for (CompletableFuture<?> f : new ArrayList<CompletableFuture<?>>(pending)) {
            f.completeExceptionally(gone);
        }
        listeners.clear();
        scripts.clear();
        final List<Object> subs;
        synchronized (subscriptions) {
            subs = new ArrayList<Object>(subscriptions);
            subscriptions.clear();
            observing = false;
        }
        final Dimension o = resized ? originalSize : null;
        resized = false;
        CompletableFuture<Void> done = new CompletableFuture<Void>();
        submit(new Callable<Void>() {
            @Override
            public Void call() {
                for (Object s : subs) {
                    try {
                        api.invoke("subscription.unsubscribe", s);
                    } catch (ReflectiveException e) {
                        // already gone
                    }
                }
                unchain();
                if (o != null && o.width > 0 && o.height > 0 && System.nanoTime() < deadlineNanos) {
                    try {
                        api.invoke("browser.resize", browser, Integer.valueOf(o.width), Integer.valueOf(o.height));
                    } catch (ReflectiveException e) {
                        // the browser is gone
                    }
                }
                return null;
            }
        }, done);
        long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (left > 0 && !onEdt()) {
            try {
                done.get(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // the vendor did not answer within the bound (or the browser is gone)
            }
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
