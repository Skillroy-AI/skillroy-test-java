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
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * The page-event tap of {@code web.tap} (phase4-contracts.md sections 4.2 and 4.3). Installing a tap adds the script
 * library's tap installer as an init script ({@code addInitScript(WebLib.tapInstaller(patterns, target))}, so that
 * every new document is tapped) and evaluates the same text in the current document; re-installing replaces the
 * patterns (the previous init script is cancelled); {@code patterns: []} drains what is buffered, removes the tap in
 * the current document and cancels the init script.
 *
 * <p><b>The poller.</b> While at least one tap is installed, a daemon thread {@value #THREAD} wakes every
 * {@value #POLL_MS} ms and hands one drain round to an agent worker ({@code ThreadPolicy.WORKER}, bounded at
 * {@value #TICK_BOUND_MS} ms; a round that finds the pool busy is skipped). A round calls the library's
 * {@code drain(maxChars)} in each tapped browser (at most {@value #DRAINS_PER_ROUND} calls per browser while it
 * answers {@code more: true}) and publishes each event as {@code web.tap {browser, name, detail?, truncated?}} followed
 * by {@code web.ready {browser, url, signal: TAP, name}}; dropped events (the page buffer holds 1000) are logged. The
 * thread ends when the last tap is removed, when the tapped browsers left the index, or when the module is
 * uninstalled ({@link #shutdown}); it starts again with the next tap. One drainer per document: the agent's poller
 * is the only consumer of the in-process buffer.
 *
 * <p><b>A busy page.</b> {@code drain} empties the page's buffer when it runs, so a drain is never abandoned: the
 * round waits for it at most {@value #DRAIN_BOUND_MS} ms; a drain the page has not answered by then (its main thread
 * is busy: a long handler, a modal dialog, a frozen renderer) stays outstanding, with the adapter's own bound of
 * {@value #LATE_DRAIN_MS} ms, and its events are published when it answers; while it is outstanding no other drain
 * is sent to that browser (at most one per browser), so a page that stays busy gets no pile of evaluations. Late
 * answers are dropped once the module is uninstalled.
 *
 * <p>Thread-safe; the tap table and the poller's life are guarded by this object's lock.
 */
final class Taps {

    /** Poll period of the tap poller. */
    static final long POLL_MS = 100;
    /** Name of the poller thread. */
    static final String THREAD = "skillroy-web-tap";
    /** Bound of one drain round on the worker. */
    static final long TICK_BOUND_MS = 5000;
    /** Bound of one {@code drain} evaluation. */
    static final long DRAIN_BOUND_MS = 2000;
    /** The JSON size a {@code drain} returns at most (the library's {@code maxChars}). */
    static final int DRAIN_CHARS = 900000;
    /** At most this many {@code drain} calls per browser and round. */
    static final int DRAINS_PER_ROUND = 8;
    /** The adapter's bound of one {@code drain}: how long its answer may still come and be published. */
    static final long LATE_DRAIN_MS = 60000;
    /** Bound of clearing the tap in the document of a browser that left the index. */
    static final long FORGET_BOUND_MS = 1000;

    private final AgentContext ctx;
    private final EventBridge bridge;
    private final Map<Entry, Tap> taps = new IdentityHashMap<Entry, Tap>();
    /** The browsers whose last drain the page has not answered yet (at most one each); guarded by this. */
    private final Map<Entry, Boolean> outstanding = new IdentityHashMap<Entry, Boolean>();
    private Thread poller;
    private int generation;
    private long rounds;
    private boolean failureLogged;
    /** Set by {@link #shutdown}: late drain answers are dropped; guarded by this. */
    private boolean closed;

    Taps(AgentContext ctx, EventBridge bridge) {
        this.ctx = ctx;
        this.bridge = bridge;
    }

    /**
     * Installs, replaces or (with no patterns) removes the browser's tap; runs on the calling worker within the
     * budget. Returns {@code {installed: n, timing}}.
     */
    Map<String, Object> install(Entry e, List<String> patterns, String target, long budgetMs) {
        String installer;
        try {
            installer = WebLib.tapInstaller(patterns, target);
        } catch (IllegalArgumentException ex) {
            throw Errors.invalid(ex.getMessage(), Errors.data("param", "patterns"));
        }
        WebBrowser b = e.browser();
        String on = target == null ? "window" : target;
        Tap previous;
        synchronized (this) {
            previous = taps.remove(e);
        }
        if (previous != null) {
            cancelQuietly(e, previous.script);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (patterns.isEmpty()) {
            if (previous != null) {
                drain(e, Math.min(DRAIN_BOUND_MS, budgetMs / 2));
            }
            Object value = evaluate(e, WebLib.tapInstaller(Collections.<String>emptyList(), on), budgetMs);
            out.put("installed", Long.valueOf(installedOf(value)));
            out.put("timing", (b.capabilities().contains(WebCapability.INIT_SCRIPT_DOCUMENT_START)
                    ? WebInitScript.Timing.DOCUMENT_START : WebInitScript.Timing.DOCUMENT_LOADED).name());
            return out;
        }
        WebInitScript script;
        try {
            script = b.addInitScript(installer);
        } catch (UnsupportedOperationException ex) {
            throw Errors.unavailable("web.tap", WebCapabilities.EVENTS, ex.getMessage() != null ? ex.getMessage()
                    : "adapter " + e.adapter.id() + " cannot add init scripts to this browser", e.ref);
        } catch (IllegalStateException ex) {
            throw Await.map(ex, e, "web.tap", WebCapabilities.EVENTS, budgetMs);
        }
        Object value;
        try {
            value = evaluate(e, installer, budgetMs);
        } catch (RuntimeException ex) {
            cancelQuietly(e, script);
            throw ex;
        }
        WebInitScript.Timing timing = script.timing() == null ? WebInitScript.Timing.DOCUMENT_LOADED
                : script.timing();
        synchronized (this) {
            taps.put(e, new Tap(new ArrayList<String>(patterns), on, script));
            ensurePoller();
        }
        out.put("installed", Long.valueOf(installedOf(value)));
        out.put("timing", timing.name());
        return out;
    }

    /** True while the browser has a tap (for tests). */
    synchronized boolean tapped(Entry e) {
        return taps.containsKey(e);
    }

    /** True while the poller thread runs (for tests). */
    synchronized boolean polling() {
        return poller != null;
    }

    /** True while a drain of the browser is outstanding (for tests). */
    synchronized boolean draining(Entry e) {
        return outstanding.containsKey(e);
    }

    /** How many drain rounds the poller has finished (for tests). */
    synchronized long rounds() {
        return rounds;
    }

    /**
     * The browser left the index (or closed): its tap is forgotten, its init script cancelled, and, while the browser
     * is open, the tap is cleared in its current document too (on an agent worker, bounded at
     * {@value #FORGET_BOUND_MS} ms), so that no listener or {@code dispatchEvent} wrapper of the agent stays behind.
     */
    void forget(final Entry e) {
        final Tap tap;
        synchronized (this) {
            tap = taps.remove(e);
        }
        if (tap == null) {
            return;
        }
        cancelQuietly(e, tap.script);
        if (Selection.isClosed(e.browser())) {
            return;
        }
        try {
            ctx.threads().post(ThreadPolicy.WORKER, new Runnable() {
                @Override
                public void run() {
                    try {
                        evaluate(e, WebLib.tapInstaller(Collections.<String>emptyList(), tap.target),
                                FORGET_BOUND_MS);
                    } catch (Throwable t) {
                        // the browser went away meanwhile: nothing to clear
                    }
                }
            });
        } catch (Throwable t) {
            // best effort: the document keeps an idle tap until it is replaced
        }
    }

    /**
     * The module is uninstalled: the poller stops and every tap is removed (init script cancelled, the tap cleared
     * in the current document so that no old listener fires, P4 section 12.2 item 8), bounded in total by the
     * budget. Never throws.
     */
    void shutdown(long budgetMs) {
        Map<Entry, Tap> all;
        synchronized (this) {
            closed = true;
            generation++;
            poller = null;
            notifyAll();
            all = new IdentityHashMap<Entry, Tap>(taps);
            taps.clear();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        for (Map.Entry<Entry, Tap> t : all.entrySet()) {
            cancelQuietly(t.getKey(), t.getValue().script);
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) {
                continue;
            }
            try {
                evaluate(t.getKey(), WebLib.tapInstaller(Collections.<String>emptyList(), t.getValue().target),
                        Math.min(left, 1000));
            } catch (Throwable ex) {
                // the browser may be gone; nothing to clear then
            }
        }
    }

    /** Starts the poller thread if it does not run. Caller holds the lock. */
    private void ensurePoller() {
        if (poller != null) {
            return;
        }
        final int gen = ++generation;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(gen);
            }
        }, THREAD);
        t.setDaemon(true);
        try {
            t.setContextClassLoader(ctx.hostClassLoader());
        } catch (SecurityException ignored) {
            // the thread keeps the creator's context loader
        }
        poller = t;
        t.start();
    }

    private void loop(int gen) {
        while (true) {
            synchronized (this) {
                if (gen != generation || taps.isEmpty()) {
                    if (gen == generation) {
                        poller = null;
                    }
                    return;
                }
                try {
                    wait(POLL_MS);
                } catch (InterruptedException e) {
                    if (gen == generation) {
                        poller = null;
                    }
                    return;
                }
                if (gen != generation) {
                    return;
                }
            }
            round();
            synchronized (this) {
                rounds++;
            }
        }
    }

    /** One drain round on an agent worker, bounded; a busy pool or a slow round is skipped quietly. */
    private void round() {
        try {
            ctx.threads().call(ThreadPolicy.WORKER, new Callable<Object>() {
                @Override
                public Object call() {
                    drainAll();
                    return null;
                }
            }, TICK_BOUND_MS);
        } catch (Exception e) {
            boolean first;
            synchronized (this) {
                first = !failureLogged;
                failureLogged = true;
            }
            if (first) {
                ctx.log().warn("web: a tap drain round did not complete (" + e + "); the poller goes on");
            }
        }
    }

    /** Drains every tapped browser once (on a worker). */
    void drainAll() {
        List<Entry> tapped;
        synchronized (this) {
            tapped = new ArrayList<Entry>(taps.keySet());
        }
        for (Entry e : tapped) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (Selection.isClosed(e.browser())) {
                forget(e);
                bridge.closed(e);
                continue;
            }
            drain(e, DRAIN_BOUND_MS);
        }
    }

    /** Drains one browser's buffer and publishes its events; never throws. */
    private void drain(Entry e, long boundMs) {
        for (int i = 0; i < DRAINS_PER_ROUND; i++) {
            Object value;
            try {
                value = drainOnce(e, boundMs);
            } catch (RpcException ex) {
                if (ex.error().code() == ErrorCodes.STALE_HANDLE) {
                    forget(e);
                    bridge.closed(e);
                }
                return;
            } catch (RuntimeException ex) {
                return;
            }
            if (!published(e, value)) {
                return;
            }
        }
    }

    /**
     * One {@code drain}, waited for at most the bound; null when the page has not answered by then (the drain stays
     * outstanding and its events are published when it answers) and while an earlier drain is outstanding (no other
     * drain is sent meanwhile). Throws the mapped error of a failed drain.
     */
    private Object drainOnce(final Entry e, long boundMs) {
        synchronized (this) {
            if (outstanding.containsKey(e)) {
                return null;
            }
        }
        WebEval eval = WebEval.of(WebLib.callExpression("drain", Integer.valueOf(DRAIN_CHARS)))
                .timeoutMs(LATE_DRAIN_MS).build();
        final CompletableFuture<String> f;
        try {
            f = e.browser().evaluate(eval);
        } catch (RuntimeException ex) {
            throw Await.map(ex, e, "web.tap", WebCapabilities.EVENTS, boundMs);
        }
        try {
            return Json.parse(f.get(Math.max(1, boundMs), TimeUnit.MILLISECONDS));
        } catch (TimeoutException late) {
            synchronized (this) {
                outstanding.put(e, Boolean.TRUE);
            }
            f.whenComplete(new BiConsumer<String, Throwable>() {
                @Override
                public void accept(String json, Throwable failure) {
                    lateAnswer(e, json, failure);
                }
            });
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException ex) {
            throw Await.map(ex.getCause(), e, "web.tap", WebCapabilities.EVENTS, boundMs);
        }
    }

    /**
     * The answer of an outstanding drain: its events are published (unless the module was uninstalled meanwhile), on
     * the thread that delivered it, or on an agent worker when that is the event-dispatch thread.
     */
    private void lateAnswer(final Entry e, final String json, Throwable failure) {
        synchronized (this) {
            outstanding.remove(e);
            if (closed) {
                return;
            }
        }
        if (failure != null || json == null) {
            return;
        }
        Runnable publish = new Runnable() {
            @Override
            public void run() {
                try {
                    published(e, Json.parse(json));
                } catch (RuntimeException ex) {
                    // an answer that cannot be read publishes nothing
                }
            }
        };
        if (Thread.currentThread().getName().startsWith(AwtThreads.EVENT_QUEUE)) {
            try {
                ctx.threads().post(ThreadPolicy.WORKER, publish);
            } catch (Throwable t) {
                // the core refused the task: nothing more to do on this thread
            }
        } else {
            publish.run();
        }
    }

    /**
     * Publishes the events of a drain's value and logs what the page dropped; true when the page has more
     * ({@code more: true}).
     */
    private boolean published(Entry e, Object value) {
        if (!(value instanceof Map)) {
            return false;
        }
        Map<?, ?> m = (Map<?, ?>) value;
        Object events = m.get("events");
        if (events instanceof List && !((List<?>) events).isEmpty()) {
            String url = Selection.safeUrl(e.browser());
            for (Object ev : (List<?>) events) {
                publish(e, ev, url);
            }
        }
        Object dropped = m.get("dropped");
        if (dropped instanceof Number && ((Number) dropped).longValue() > 0) {
            ctx.log().warn("web: the tap of browser " + e.ref + " dropped " + dropped + " page events (its "
                    + "buffer holds " + WebLib.MAX_BUFFERED_EVENTS + ")");
        }
        return Boolean.TRUE.equals(m.get("more"));
    }

    private void publish(Entry e, Object event, String url) {
        if (!(event instanceof Map)) {
            return;
        }
        Map<?, ?> ev = (Map<?, ?>) event;
        Object name = ev.get("name");
        if (!(name instanceof String)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("browser", e.ref);
        payload.put("name", name);
        if (ev.containsKey("detail") && ev.get("detail") != null) {
            payload.put("detail", ev.get("detail"));
        }
        if (Boolean.TRUE.equals(ev.get("truncated"))) {
            payload.put("truncated", Boolean.TRUE);
        }
        ctx.events().publish(WebTopics.TAP, WebTopics.SOURCE, payload);
        bridge.ready(e, url, "TAP", (String) name);
    }

    /** Evaluates a library expression and returns its value parsed from the JSON text; throws mapped errors. */
    private static Object evaluate(Entry e, String expression, long boundMs) {
        WebEval eval = WebEval.of(expression).timeoutMs(Math.max(1, boundMs)).build();
        String json = Await.get(e.browser().evaluate(eval), boundMs, e, "web.tap", WebCapabilities.EVENTS);
        return Json.parse(json);
    }

    private static long installedOf(Object value) {
        if (value instanceof Map) {
            RpcError error = WebLib.errorOf(value);
            if (error != null) {
                throw new RpcException(error.code(), error.message(), error.data());
            }
            Object n = ((Map<?, ?>) value).get("installed");
            if (n instanceof Number) {
                return ((Number) n).longValue();
            }
        }
        return 0;
    }

    private void cancelQuietly(Entry e, WebInitScript script) {
        if (script == null) {
            return;
        }
        try {
            script.cancel();
        } catch (Throwable t) {
            ctx.log().warn("web: cancelling the tap's init script of browser " + e.ref + " failed: " + t);
        }
    }

    /** One installed tap. */
    private static final class Tap {
        final List<String> patterns;
        final String target;
        final WebInitScript script;

        Tap(List<String> patterns, String target, WebInitScript script) {
            this.patterns = patterns;
            this.target = target;
            this.script = script;
        }
    }
}
