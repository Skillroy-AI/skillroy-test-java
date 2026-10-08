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

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.agent.api.spi.web.WebSubscription;
import ai.skillroy.testjava.agent.web.fake.FakeDocument;
import ai.skillroy.testjava.agent.web.fake.FakeScript;
import ai.skillroy.testjava.protocol.Json;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link WebBrowser} a test scripts: capabilities, URL, component, rendering, viewport, frames, closure; evaluation
 * goes through the fakes' table-driven evaluator over a {@link FakeDocument} (so the script library's calls give the
 * library's shapes) unless the test installs a {@link Responder}. It records every evaluation, init script,
 * subscription, resize and reload; {@link #fire} delivers an event to the subscribers.
 */
class ScriptedBrowser implements WebBrowser {

    /** Answers one evaluation (a test's own behaviour). */
    interface Responder {
        CompletableFuture<String> answer(WebEval eval);
    }

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            FakeAgentContext.daemon("scripted-browser-timer"));

    final String adapterId;
    final Object vendor;
    final Object seedToken = new Object();
    final List<WebEval> evals = new CopyOnWriteArrayList<WebEval>();
    final List<WebEventListener> listeners = new CopyOnWriteArrayList<WebEventListener>();
    final List<String> scripts = new CopyOnWriteArrayList<String>();
    final List<String> cancelledScripts = new CopyOnWriteArrayList<String>();
    final List<int[]> resizes = new CopyOnWriteArrayList<int[]>();
    final List<CompletableFuture<?>> handedOut = new CopyOnWriteArrayList<CompletableFuture<?>>();
    final AtomicInteger clears = new AtomicInteger();
    volatile Set<WebCapability> capabilities = EnumSet.of(WebCapability.EVALUATE, WebCapability.AWAIT_PROMISE,
            WebCapability.FRAMES, WebCapability.EVENTS_LOAD, WebCapability.EVENTS_CONSOLE,
            WebCapability.INIT_SCRIPT_DOCUMENT_START, WebCapability.RESIZE, WebCapability.BITMAP,
            WebCapability.CDP_TARGET_ID);
    volatile String url = "about:blank";
    volatile String nativeId;
    volatile Component component;
    volatile WebRendering rendering = WebRendering.UNKNOWN;
    volatile Dimension viewport = new Dimension(800, 600);
    volatile List<WebFrame> frames = new ArrayList<WebFrame>();
    volatile boolean closed;
    volatile boolean removed;
    volatile boolean seedOnly;
    volatile boolean detached;
    volatile Responder responder;
    volatile WebInitScript.Timing timing = WebInitScript.Timing.DOCUMENT_START;
    volatile String targetId = "TARGET-1";
    volatile boolean targetIdHangs;
    volatile BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
    volatile FakeDocument document;

    ScriptedBrowser(String adapterId) {
        this(adapterId, new Object());
    }

    ScriptedBrowser(String adapterId, Object vendor) {
        this.adapterId = adapterId;
        this.vendor = vendor;
        load("<html><head><title>Scripted</title></head><body><p id=\"status\">ready</p></body></html>");
    }

    /** Replaces the document (nothing is published: fire events yourself). */
    ScriptedBrowser load(String html) {
        FakeDocument old = document;
        if (old != null) {
            old.markGone();
        }
        document = FakeDocument.parse(new FakeDocument.Host() {
            @Override
            public void console(FakeDocument doc, String level, String text) {
                fire(WebEvent.console(level, text, null, 0));
            }

            @Override
            public void reload(boolean ignoreCache) {
                // recorded through evals
            }

            @Override
            public int[] innerSize() {
                Dimension v = viewport;
                return v == null ? new int[] {800, 600} : new int[] {v.width, v.height};
            }
        }, url, html);
        return this;
    }

    void fire(WebEvent event) {
        for (WebEventListener l : listeners) {
            l.onEvent(this, event);
        }
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public Object vendorObject() {
        return vendor;
    }

    @Override
    public String nativeId() {
        return nativeId;
    }

    @Override
    public Set<WebCapability> capabilities() {
        return closed ? Collections.<WebCapability>emptySet() : capabilities;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public WebRendering rendering() {
        return rendering;
    }

    @Override
    public Dimension viewport() {
        Dimension v = viewport;
        return v == null ? null : new Dimension(v);
    }

    @Override
    public List<WebFrame> frames() {
        return capabilities.contains(WebCapability.FRAMES) ? frames : Collections.<WebFrame>emptyList();
    }

    @Override
    public CompletableFuture<String> evaluate(WebEval eval) {
        evals.add(eval);
        if (closed) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        Responder r = responder;
        CompletableFuture<String> f = r != null ? r.answer(eval) : byDocument(eval);
        handedOut.add(f);
        return f;
    }

    /** The answer of the fake page (what the browser does without a responder). */
    CompletableFuture<String> byDocument(WebEval eval) {
        final CompletableFuture<String> out = new CompletableFuture<String>();
        try {
            Object v = FakeScript.evaluate(document, eval.expression());
            if (v instanceof FakeScript.Promise && eval.awaitPromise()) {
                final FakeScript.Promise p = (FakeScript.Promise) v;
                TIMER.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (p.error() != null) {
                            out.completeExceptionally(new WebScriptException(p.error().getMessage(),
                                    p.error().stack()));
                        } else {
                            out.complete(json(p.value()));
                        }
                    }
                }, p.remainingMs(), TimeUnit.MILLISECONDS);
            } else {
                out.complete(json(v));
            }
        } catch (FakeScript.Thrown t) {
            out.completeExceptionally(new WebScriptException(t.getMessage(), t.stack()));
        }
        return out;
    }

    /** The JSON text of a fake value, as an adapter hands it ({@code "null"} for undefined). */
    static String json(Object v) {
        if (v == FakeScript.UNDEFINED) {
            return "null";
        }
        if (v instanceof FakeScript.Promise) {
            return "{}";
        }
        return Json.write(v);
    }

    @Override
    public WebSubscription subscribe(final WebEventListener listener) {
        listeners.add(listener);
        return new WebSubscription() {
            @Override
            public void cancel() {
                listeners.remove(listener);
            }
        };
    }

    @Override
    public WebInitScript addInitScript(final String source) {
        if (closed) {
            throw new IllegalStateException("the browser has closed");
        }
        if (!capabilities.contains(WebCapability.EVALUATE)) {
            throw new UnsupportedOperationException("no EVALUATE");
        }
        scripts.add(source);
        final WebInitScript.Timing t = timing;
        return new WebInitScript() {
            @Override
            public Timing timing() {
                return t;
            }

            @Override
            public void cancel() {
                if (scripts.remove(source)) {
                    cancelledScripts.add(source);
                }
            }
        };
    }

    @Override
    public CompletableFuture<Void> resize(int width, int height) {
        if (closed) {
            return failed(new IllegalStateException("the browser has closed"));
        }
        if (!capabilities.contains(WebCapability.RESIZE)) {
            return failed(new UnsupportedOperationException("no RESIZE"));
        }
        resizes.add(new int[] {width, height});
        viewport = new Dimension(width, height);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> clearResize() {
        clears.incrementAndGet();
        viewport = new Dimension(800, 600);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<BufferedImage> bitmap() {
        if (!capabilities.contains(WebCapability.BITMAP)) {
            return failed(new UnsupportedOperationException("no BITMAP"));
        }
        return CompletableFuture.completedFuture(image);
    }

    @Override
    public CompletableFuture<String> cdpTargetId() {
        if (!capabilities.contains(WebCapability.CDP_TARGET_ID)) {
            return CompletableFuture.completedFuture(null);
        }
        if (targetIdHangs) {
            CompletableFuture<String> never = new CompletableFuture<String>();
            handedOut.add(never);
            return never;
        }
        return CompletableFuture.completedFuture(targetId);
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(t);
        return f;
    }

    @Override
    public String toString() {
        return "ScriptedBrowser{" + adapterId + "}";
    }
}
