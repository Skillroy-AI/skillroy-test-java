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
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebSubscription;
import ai.skillroy.testjava.protocol.web.WebLib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Publishes what the adapters observe on the agent's event bus (phase4-contracts.md section 4.3), source
 * {@value WebTopics#SOURCE}. One subscription per browser, made when the browser first enters the index and cancelled
 * when it leaves or the module is uninstalled:
 * <ul>
 *   <li>{@code LOAD_FINISHED}, {@code LOAD_FAILED}, {@code NAVIGATED}: {@code web.load {browser, url, frameKey?, kind,
 *       error?}}; a {@code LOAD_FINISHED} of the main frame (no frame key) also publishes {@code web.ready {browser,
 *       url, signal: LOAD}}; every one wakes a read waiting for the retry of section 4.2;</li>
 *   <li>{@code CONSOLE}: {@code web.console {browser, level, text, source?, line?}}, the text with secrets hidden
 *       ({@link Secrets}) and then cut at {@link WebPrimitives#CONSOLE_TEXT_MAX} characters ({@code truncated: true}
 *       when cut); the source URL is masked the same way;</li>
 *   <li>{@code CLOSED}: {@code web.closed {browser}}, at most once per browser (also when a rebuild finds a browser
 *       closed or gone from the technology's list).</li>
 * </ul>
 * When a browser enters the index its CDP target id is read once in the background and cached, so that a later
 * {@code web.browsers} can report it while the page cannot answer (a frozen renderer).
 *
 * <p>{@link #close()} (the module's uninstall, before the adapters are detached) cancels every subscription the
 * bridge made and refuses new ones, also one a rebuild in flight would make, so that no subscription of a detached
 * adapter survives the uninstall.
 *
 * <p>Every page-supplied text is bounded here, whatever the adapter gave (security fix S43): a load's {@code url} and
 * {@code error}, a ready's {@code url} and a console {@code source} at {@link WebLib#MAX_URL_CHARS} (the source masked
 * over a margin first, as the text is).
 *
 * <p>A masked browser's events withhold page data (Phase 8, phase8-contracts.md 5.2 (i), D549), read from its
 * {@link Entry#masked} flag (a volatile read; no EDT read on the adapter's thread): {@code web.load {browser, url: "",
 * frameKey?, kind, masked: true}} (no {@code error}), {@code web.ready {browser, url: "", signal, name?, masked:
 * true}}, {@code web.console {browser, level, text: "", masked: true}} (no {@code source}, {@code line} or
 * {@code truncated}); {@code web.closed} carries the ref only and is unchanged. An event of an unmasked browser whose
 * flag is older than {@link BrowserIndex#STALE_MS} asks the index to judge the flags again on an agent worker (Phase 8
 * security fix S172, FE): the event itself reads the flag as it is, so a browser that became masked while open sends
 * page data for at most about that long after its next event.
 *
 * <p>The listener runs on the adapter's threads (with JCEF off-screen, possibly the EDT): it only builds a small map
 * and publishes, which never blocks; it never throws into the adapter. Thread-safe.
 */
final class EventBridge implements BrowserIndex.Listener {

    private final AgentContext ctx;
    private final Secrets secrets;
    /** The entries the bridge subscribed to and has not cancelled; guarded by this. */
    private final Set<Entry> subscribed = Collections.newSetFromMap(new IdentityHashMap<Entry, Boolean>());
    /** Set by {@link #close()}; guarded by this. */
    private boolean closed;
    private volatile Taps taps;
    private volatile BrowserIndex index;

    EventBridge(AgentContext ctx, Secrets secrets) {
        this.ctx = ctx;
        this.secrets = secrets;
    }

    void taps(Taps t) {
        this.taps = t;
    }

    /** The index whose flags an event may ask to have judged again (Phase 8 security fix S172, FE). */
    void index(BrowserIndex i) {
        this.index = i;
    }

    /**
     * An event of the browser is about to be published: lets the index judge the flags again on a worker when they are
     * stale ({@link BrowserIndex#eventPublished}; S172); never blocks, never throws.
     */
    void published(Entry e) {
        BrowserIndex i = index;
        if (i != null) {
            try {
                i.eventPublished(e);
            } catch (Throwable t) {
                // never into the publishing thread
            }
        }
    }

    @Override
    public void entered(final Entry e) {
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        WebSubscription s = null;
        try {
            s = e.browser().subscribe(new Listener(e));
        } catch (Throwable t) {
            ctx.log().warn("web: could not subscribe to the events of browser " + e.ref + ": " + t);
        }
        boolean refused;
        synchronized (this) {
            refused = closed;
            if (!refused) {
                e.subscription = s;
                subscribed.add(e);
            }
        }
        if (refused) {
            cancelQuietly(e, s); // closed while subscribing: nothing of it stays
            return;
        }
        prefetchTargetId(e);
        if (Selection.isClosed(e.browser())) {
            closed(e);
        }
    }

    @Override
    public void left(Entry e) {
        cancel(e);
        if (Selection.isClosed(e.browser())) {
            closed(e);
        }
        Taps t = taps;
        if (t != null) {
            t.forget(e);
        }
    }

    /** Cancels the browser's subscription (idempotent). */
    void cancel(Entry e) {
        WebSubscription s;
        synchronized (this) {
            s = e.subscription;
            e.subscription = null;
            subscribed.remove(e);
        }
        cancelQuietly(e, s);
    }

    /**
     * Cancels every subscription the bridge made and refuses new ones, also those a rebuild in flight is making (the
     * module's uninstall). Idempotent; never throws.
     */
    void close() {
        List<Entry> all;
        synchronized (this) {
            closed = true;
            all = new ArrayList<Entry>(subscribed);
        }
        for (Entry e : all) {
            cancel(e);
        }
    }

    private void cancelQuietly(Entry e, WebSubscription s) {
        if (s != null) {
            try {
                s.cancel();
            } catch (Throwable t) {
                ctx.log().warn("web: cancelling the subscription of browser " + e.ref + " failed: " + t);
            }
        }
    }

    /** Publishes {@code web.closed} once for the browser. */
    void closed(Entry e) {
        if (e.closedPublished.compareAndSet(false, true)) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("browser", e.ref);
            ctx.events().publish(WebTopics.CLOSED, WebTopics.SOURCE, payload);
        }
    }

    /**
     * Publishes {@code web.ready {browser, url, signal, name?}}; the URL cut at {@link WebLib#MAX_URL_CHARS}; for a
     * masked browser {@code url: ""} and {@code masked: true} (D549: {@code signal} and {@code name} are what a step
     * waits on, kept).
     */
    void ready(Entry e, String url, String signal, String name) {
        published(e);
        boolean masked = e.masked;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("browser", e.ref);
        payload.put("url", url == null || masked ? "" : WebLib.cut(url, WebLib.MAX_URL_CHARS));
        payload.put("signal", signal);
        if (name != null) {
            payload.put("name", name);
        }
        if (masked) {
            payload.put("masked", Boolean.TRUE);
        }
        ctx.events().publish(WebTopics.READY, WebTopics.SOURCE, payload);
    }

    private void prefetchTargetId(final Entry e) {
        WebBrowser b = e.browser();
        try {
            if (!b.capabilities().contains(WebCapability.CDP_TARGET_ID)) {
                return;
            }
            CompletableFuture<String> f = b.cdpTargetId();
            if (f != null) {
                f.whenComplete(new BiConsumer<String, Throwable>() {
                    @Override
                    public void accept(String id, Throwable failure) {
                        if (id != null && failure == null) {
                            e.cachedTargetId = id;
                        }
                    }
                });
            }
        } catch (Throwable t) {
            // the id is read again by web.browsers
        }
    }

    private void onEvent(Entry e, WebEvent event) {
        published(e);
        switch (event.kind()) {
            case LOAD_FINISHED:
            case LOAD_FAILED:
            case NAVIGATED:
                boolean maskedLoad = e.masked;
                Map<String, Object> load = new LinkedHashMap<String, Object>();
                load.put("browser", e.ref);
                load.put("url", event.url() == null || maskedLoad ? ""
                        : WebLib.cut(event.url(), WebLib.MAX_URL_CHARS));
                if (event.frameKey() != null) {
                    load.put("frameKey", event.frameKey());
                }
                load.put("kind", event.kind().name());
                if (event.error() != null && !maskedLoad) {
                    load.put("error", WebLib.cut(event.error(), WebLib.MAX_URL_CHARS));
                }
                if (maskedLoad) {
                    load.put("masked", Boolean.TRUE);
                }
                ctx.events().publish(WebTopics.LOAD, WebTopics.SOURCE, load);
                if (event.kind() == WebEvent.Kind.LOAD_FINISHED && event.frameKey() == null) {
                    ready(e, event.url(), "LOAD", null);
                }
                e.loads.loaded();
                break;
            case CONSOLE:
                Map<String, Object> console = new LinkedHashMap<String, Object>();
                console.put("browser", e.ref);
                console.put("level", event.level() == null ? "log" : event.level());
                if (e.masked) {
                    // D549: no text, no source, no line of a masked browser's page.
                    console.put("text", "");
                    console.put("masked", Boolean.TRUE);
                    ctx.events().publish(WebTopics.CONSOLE, WebTopics.SOURCE, console);
                    break;
                }
                // Masked before it is cut, so that a cut never leaves part of a secret: a value straddling the
                // first cut lies beyond the second one.
                String text = event.text() == null ? "" : event.text();
                boolean cut = false;
                int first = WebPrimitives.CONSOLE_TEXT_MAX + WebPrimitives.CONSOLE_REDACTION_MARGIN;
                if (text.length() > first) {
                    text = text.substring(0, first);
                    cut = true;
                }
                text = secrets.redact(text);
                if (text.length() > WebPrimitives.CONSOLE_TEXT_MAX) {
                    text = text.substring(0, WebPrimitives.CONSOLE_TEXT_MAX);
                    cut = true;
                }
                console.put("text", text);
                if (event.source() != null && !event.source().isEmpty()) {
                    // S43: cut (beyond a masking margin), masked, then cut at its bound, as the text is.
                    console.put("source", WebLib.cut(secrets.redact(WebLib.cut(event.source(),
                            WebLib.MAX_URL_CHARS + WebPrimitives.CONSOLE_REDACTION_MARGIN)), WebLib.MAX_URL_CHARS));
                }
                if (event.line() > 0) {
                    console.put("line", Long.valueOf(event.line()));
                }
                if (cut) {
                    console.put("truncated", Boolean.TRUE);
                }
                ctx.events().publish(WebTopics.CONSOLE, WebTopics.SOURCE, console);
                break;
            case CLOSED:
                closed(e);
                break;
            default:
                break;
        }
    }

    /** The subscription's listener for one browser. */
    private final class Listener implements WebEventListener {
        private final Entry entry;

        Listener(Entry entry) {
            this.entry = entry;
        }

        @Override
        public void onEvent(WebBrowser browser, WebEvent event) {
            if (event == null) {
                return;
            }
            try {
                EventBridge.this.onEvent(entry, event);
            } catch (Throwable t) {
                // never into the adapter's thread
            }
        }
    }
}
