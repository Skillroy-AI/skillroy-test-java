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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;
import ai.skillroy.testjava.agent.web.kit.KitPages;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The neutral fake of the vendor's browser, with the F21 member shapes the {@code jxbrowser} adapter uses:
 * {@code url()}, {@code title()}, {@code isClosed()}, {@code engine()}, {@code mainFrame()}, {@code frames()},
 * {@code navigation()}, {@code on(Class, Observer)}, {@code size()}, {@code resize(int, int)}, {@code bitmap()},
 * {@code devTools()}, {@code set(Class, callback)}, {@code get(Class)}, {@code remove(Class)}, and
 * {@code dispatch(...)} per input event type, which hands the event to the page (Phase 7). The application's own
 * actions are {@link #loadHtml}, {@link #close} and {@link #fire}.
 *
 * <p>A load replaces the document: the main frame stays, the child frames are new (the old ones are closed); a
 * chained document-start callback runs first, then the page's inline scripts, then the events
 * ({@code NavigationFinished}, {@code FrameDocumentLoadFinished} per frame, {@code FrameLoadFinished} of the children
 * and then of the main frame), each delivered on the EDT. For the tests of bounded vendor calls, {@link #hang} makes
 * {@code isClosed()}, {@code mainFrame()}, {@code frames()} and {@code on(...)} block, as calls to a hung Chromium
 * process do.
 */
public final class FakeBrowser {

    static {
        ClassInitialisations.record(FakeBrowser.class);
    }

    private static final int BLOCK_RGB = (240 << 16) | (200 << 8) | 40;

    private final FakeEngine engine;
    private final FakeProfile profile;
    private final FakeFrame main;
    private final FakeNavigation navigation;
    private final List<Object[]> observers = new CopyOnWriteArrayList<Object[]>();
    private final Map<Class<?>, Object> callbacks = Collections.synchronizedMap(new LinkedHashMap<Class<?>, Object>());
    private final List<String> setCalls = Collections.synchronizedList(new ArrayList<String>());
    private final List<Object> dispatched = new CopyOnWriteArrayList<Object>();
    private volatile boolean closed;
    private volatile int width = 800;
    private volatile int height = 600;
    private volatile String html;
    private volatile FakeDocument document;
    private volatile java.util.concurrent.CountDownLatch hang;
    final java.util.concurrent.atomic.AtomicInteger asyncEvaluations = new java.util.concurrent.atomic.AtomicInteger();

    FakeBrowser(FakeEngine engine, FakeProfile profile) {
        this.engine = engine;
        this.profile = profile;
        this.main = new FakeFrame(this, null);
        this.navigation = new FakeNavigation(this);
    }

    public String url() {
        engine.violations.blockingCall("Browser.url()");
        FakeDocument d = document;
        return d == null ? "about:blank" : d.url();
    }

    public String title() {
        engine.violations.blockingCall("Browser.title()");
        FakeDocument d = document;
        return d == null ? "" : d.title();
    }

    public boolean isClosed() {
        hangIfAsked();
        return closed;
    }

    /**
     * Until the latch is released (at most 30 s) {@code isClosed()}, {@code mainFrame()}, {@code frames()} and
     * {@code on(...)} block.
     */
    public void hang(java.util.concurrent.CountDownLatch until) {
        hang = until;
    }

    private void hangIfAsked() {
        java.util.concurrent.CountDownLatch h = hang;
        if (h != null) {
            try {
                h.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** How many asynchronous evaluations were asked of the browser's frames (for tests). */
    public int asyncEvaluations() {
        return asyncEvaluations.get();
    }

    boolean isClosedNow() {
        return closed;
    }

    public FakeEngine engine() {
        return engine;
    }

    public FakeProfile profile() {
        return profile;
    }

    public FakeSize size() {
        engine.violations.blockingCall("Browser.size()");
        return new FakeSize(width, height);
    }

    public void resize(int w, int h) {
        engine.violations.blockingCall("Browser.resize()");
        width = w;
        height = h;
    }

    public Optional<FakeFrame> mainFrame() {
        hangIfAsked();
        return document == null ? Optional.<FakeFrame>empty() : Optional.of(main);
    }

    public List<FakeFrame> frames() {
        hangIfAsked();
        return framesNow();
    }

    private List<FakeFrame> framesNow() {
        List<FakeFrame> out = new ArrayList<FakeFrame>();
        if (document != null) {
            collect(main, out);
        }
        return out;
    }

    private static void collect(FakeFrame f, List<FakeFrame> out) {
        out.add(f);
        for (FakeFrame c : f.children) {
            collect(c, out);
        }
    }

    public FakeNavigation navigation() {
        return navigation;
    }

    /**
     * Subscribes an observer to the events of the type (and its subtypes). A round trip to the Chromium process in the
     * product (JxBrowser 8.18.3 waits here while that process is stopped): blocks while {@link #hang} says so, and a
     * call on the EDT is a violation.
     */
    public FakeSubscription on(Class<?> type, FakeObserver<?> observer) {
        engine.violations.blockingCall("Browser.on()");
        hangIfAsked();
        final Object[] entry = {type, observer};
        observers.add(entry);
        return new FakeSubscription(new Runnable() {
            @Override
            public void run() {
                observers.remove(entry);
            }
        });
    }

    /** How many observers are subscribed (for tests). */
    public int observerCount() {
        return observers.size();
    }

    public FakeBitmap bitmap() {
        engine.violations.blockingCall("Browser.bitmap()");
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        FakeDocument d = document;
        int background = d == null ? 0xffffff : d.background();
        byte[] px = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = x >= 24 && x < 144 && y >= 24 && y < 84 ? BLOCK_RGB : background;
                int i = (y * w + x) * 4;
                px[i] = (byte) (rgb & 0xff);
                px[i + 1] = (byte) ((rgb >> 8) & 0xff);
                px[i + 2] = (byte) ((rgb >> 16) & 0xff);
                px[i + 3] = (byte) 0xff;
            }
        }
        return new FakeBitmap(px, new FakeSize(w, h));
    }

    public FakeDevTools devTools() {
        Optional<Integer> port = engine.options().remoteDebuggingPort();
        String id = "FAKE" + Integer.toHexString(System.identityHashCode(this)).toUpperCase(java.util.Locale.ROOT);
        return new FakeDevTools(port.isPresent() ? "http://localhost:" + port.get()
                + "/devtools/inspector.html?ws=localhost:" + port.get() + "/devtools/page/" + id : null);
    }

    /** The single-slot callbacks; every call is recorded (an adapter takes none without the opt-in). */
    public void set(Class<?> type, Object callback) {
        setCalls.add(type.getSimpleName());
        callbacks.put(type, callback);
    }

    public Optional<Object> get(Class<?> type) {
        return Optional.ofNullable(callbacks.get(type));
    }

    public void remove(Class<?> type) {
        callbacks.remove(type);
    }

    /** What {@code set(...)} was called with (for tests). */
    public List<String> setCalls() {
        synchronized (setCalls) {
            return new ArrayList<String>(setCalls);
        }
    }

    /**
     * The vendor's input (Phase 7, {@code INPUT}), one overload per event type as the product has (JxBrowser 8.18.3:
     * {@code dispatch(MousePressed)}, {@code dispatch(MouseReleased)}, ...): the page receives the event; every event
     * is also kept in {@link #dispatched()}.
     */
    public void dispatch(FakeMousePressed event) {
        dispatchAny(event);
    }

    /** As {@link #dispatch(FakeMousePressed)}. */
    public void dispatch(FakeMouseReleased event) {
        dispatchAny(event);
    }

    /** As {@link #dispatch(FakeMousePressed)}. */
    public void dispatch(FakeMouseMoved event) {
        dispatchAny(event);
    }

    /**
     * As {@link #dispatch(FakeMousePressed)}; the deltas have the opposite sign of the page's, as the product's do (a
     * {@code deltaY} of -120 reaches the page as 120, downwards).
     */
    public void dispatch(FakeMouseWheel event) {
        dispatchAny(event);
    }

    private void dispatchAny(Object event) {
        engine.violations.blockingCall("Browser.dispatch()");
        dispatched.add(event);
        FakeDocument d = document;
        if (event instanceof FakeMouseMoved) {
            FakePoint p = ((FakeMouseMoved) event).location;
            pointer(d, "mouseMoved", p, "none", 0, 0, 0);
        } else if (event instanceof FakeMousePressed) {
            FakeMousePressed e = (FakeMousePressed) event;
            pointer(d, "mousePressed", e.location, e.button.protocolName(), e.clickCount, 0, 0);
        } else if (event instanceof FakeMouseReleased) {
            FakeMouseReleased e = (FakeMouseReleased) event;
            pointer(d, "mouseReleased", e.location, e.button.protocolName(), e.clickCount, 0, 0);
        } else if (event instanceof FakeMouseWheel) {
            FakeMouseWheel e = (FakeMouseWheel) event;
            pointer(d, "mouseWheel", e.location, "none", 0, -Math.round(e.deltaX), -Math.round(e.deltaY));
        } else {
            throw new IllegalArgumentException("not an input event: " + event);
        }
    }

    private static void pointer(FakeDocument d, String type, FakePoint p, String button, int clickCount, long dx,
                                long dy) {
        if (d != null) {
            d.pointer(type, p.x, p.y, button, clickCount, 0, dx, dy);
        }
    }

    /** The input events dispatched so far (for tests). */
    public List<Object> dispatched() {
        return new ArrayList<Object>(dispatched);
    }

    // ------------------------------------------------------------------------------------------------ the app

    /**
     * Loads the HTML (the application's action, through its own API); the future completes once every load event
     * was delivered on the EDT.
     */
    public CompletableFuture<Void> loadHtml(final String newHtml) {
        final CompletableFuture<Void> done = new CompletableFuture<Void>();
        engine.renderer().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    load(newHtml, done);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            }
        });
        return done;
    }

    /** Loads and waits (bounded). */
    public void loadAndWait(String newHtml) throws Exception {
        loadHtml(newHtml).get(10, TimeUnit.SECONDS);
    }

    void reloadDocument() {
        String h = html;
        if (h != null) {
            loadHtml(h);
        }
    }

    private void load(String newHtml, CompletableFuture<Void> done) {
        if (closed) {
            done.completeExceptionally(new IllegalStateException("closed"));
            return;
        }
        FakeDocument old = document;
        if (old != null) {
            for (FakeDocument d : old.tree()) {
                d.gone = true;
            }
        }
        for (FakeFrame c : main.children) {
            close(c);
        }
        main.children.clear();
        html = newHtml;
        String url = KitPages.dataUrl(newHtml);
        FakeDocument doc = FakeDocument.parse(host(), url, newHtml);
        main.document = doc;
        build(main, doc);
        document = doc;
        Object callback = callbacks.get(FakeInjectJsCallback.class);
        List<FakeFrame> frames = framesNow();
        for (FakeFrame f : frames) {
            if (callback instanceof FakeInjectJsCallback) {
                ((FakeInjectJsCallback) callback).on(new FakeInjectJsCallback.Params(f));
            }
            f.document.runInlineScripts();
        }
        fire(new FakeNavigationFinished(url, main, true, true, null));
        for (FakeFrame f : frames) {
            fire(new FakeFrameDocumentLoadFinished(f));
        }
        for (FakeFrame f : frames) {
            if (!f.isMain()) {
                fire(new FakeFrameLoadFinished(f, f.document.url()));
            }
        }
        fire(new FakeFrameLoadFinished(main, url));
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                done.complete(null);
            }
        });
    }

    private void build(FakeFrame frame, FakeDocument doc) {
        for (FakeDocument child : doc.children()) {
            FakeFrame f = new FakeFrame(this, frame);
            f.document = child;
            frame.children.add(f);
            build(f, child);
        }
    }

    private static void close(FakeFrame f) {
        f.closed = true;
        for (FakeFrame c : f.children) {
            close(c);
        }
    }

    /** Closes the browser (the application's action): {@code BrowserClosed} is delivered. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        profile.browsers.remove(this);
        fire(new FakeBrowserClosed(this));
    }

    /** Delivers an event to the observers of its type, on the EDT. */
    public void fire(final Object event) {
        for (final Object[] entry : observers) {
            if (((Class<?>) entry[0]).isInstance(event)) {
                EventQueue.invokeLater(new Runnable() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public void run() {
                        if (observers.contains(entry)) {
                            ((FakeObserver<Object>) entry[1]).on(event);
                        }
                    }
                });
            }
        }
    }

    /** The current main document (for tests). */
    public FakeDocument document() {
        return document;
    }

    /** The page logs {@code text} on its console, as a page script would (P2-L4-S1). */
    public void pageConsole(String text) {
        host().console(document, "log", text);
    }

    /** How many observers of the given event type are subscribed (for tests). */
    public int observerCount(Class<?> type) {
        int n = 0;
        for (Object[] entry : observers) {
            if (entry[0] == type) {
                n++;
            }
        }
        return n;
    }

    private FakeDocument.Host host() {
        return new FakeDocument.Host() {
            @Override
            public void console(FakeDocument doc, String level, String text) {
                fire(new FakeConsoleMessageReceived(new FakeConsoleMessage(FakeConsoleMessageLevel.LEVEL_INFO, text,
                        doc.url().length() > 60 ? doc.url().substring(0, 60) : doc.url(), 1)));
            }

            @Override
            public void reload(boolean ignoreCache) {
                reloadDocument();
            }

            @Override
            public int[] innerSize() {
                return new int[] {width, height};
            }
        };
    }
}
