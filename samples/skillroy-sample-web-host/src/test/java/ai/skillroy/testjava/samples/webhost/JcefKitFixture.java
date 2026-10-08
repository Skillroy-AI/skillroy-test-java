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

package ai.skillroy.testjava.samples.webhost;

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.agent.web.jcef.JcefAdapter;
import ai.skillroy.testjava.agent.web.kit.KitPages;
import ai.skillroy.testjava.agent.web.kit.RecordingClassLoader;
import ai.skillroy.testjava.agent.web.kit.WebAdapterFixture;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.webhost.WebHostOptions.Rendering;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.cef.handler.CefLifeSpanHandlerAdapter;

import java.awt.EventQueue;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The adapter contract kit's fixture over the real web host (phase4-contracts.md 10.3): {@link WebHostApp} started in
 * this test JVM with a remote-debugging port the fixture picked (so that kit item 9 runs), the {@code jcef} adapter of
 * {@code skillroy-agent-web}, and the host's browsers as the kit's: {@code front} (shown), {@code back} (the hidden
 * card) and, off screen, {@code detached} (no view). The fixture drives the browsers as the application would:
 *
 * <ul>
 *   <li>{@link #load}: {@code Page.enable} through the browser's shared DevTools client, answered before the
 *       navigation, then {@code CefBrowser.loadURL} of a {@code data:} URL of the page (as the host loads its own
 *       pages), then a wait for the main frame's {@code Page.frameNavigated} and the following
 *       {@code Page.loadEventFired}, which the fixture hears through a DevTools listener of its own on that client
 *       (the host's single load-handler slot is the host's);</li>
 *   <li>{@link #newDisposableBrowser}: off screen, a new browser of the host's client created with
 *       {@code createImmediately()}, awaited through a life-span handler the fixture adds to the client (the host has
 *       none; its {@code doClose} defers to the browser's own, so the host's shutdown is unchanged); windowed, none
 *       (a windowed browser needs a window);</li>
 *   <li>{@link #close}: {@code setCloseAllowed()} and {@code close(true)}, then the life-span handler's
 *       {@code onBeforeClose};</li>
 *   <li>the application's own listener (kit item 10): a DevTools event listener of the fixture's.</li>
 * </ul>
 * The thread that must never be blocked is the event dispatch thread (CEF's UI thread off screen; Swing's in both
 * modes). CEF starts once per JVM: {@link #start} launches the host once and every kit test of the class shares it;
 * the test class calls {@link #stop} at the end.
 *
 * <p>Off screen the console shows bare {@code Exception in thread "AWT-EventQueue-0"} lines without a stack trace,
 * during the run and while the JVM exits: they are JCEF's own, not the adapter's. JCEF 152's
 * {@code CefClient.onGotFocus} calls {@code browser.setFocus(true)}, which makes CEF call {@code onGotFocus} again,
 * until the event dispatch thread's stack overflows inside a native callback; JNI reports the
 * {@code StackOverflowError} with {@code ExceptionDescribe}, whose own {@code printStackTrace} overflows as well (seen
 * on 2026-10-07 with {@code -XX:AbortVMOnException=java.lang.StackOverflowError}; a larger {@code -Xss} does not
 * help). No uncaught-exception handler sees them.
 */
final class JcefKitFixture implements WebAdapterFixture {

    /** Bound of a page load, a browser's creation and its closure. */
    static final long WAIT_MS = 30000;
    /** Bound of one wait for the answer to {@code Page.enable}, and how many times it is asked at most. */
    static final long ENABLE_ANSWER_MS = 5000;
    static final int ENABLE_ATTEMPTS = 3;
    static final String BLANK = "<!doctype html><html><head><title>blank</title></head><body></body></html>";

    private static JcefKitFixture running;

    final WebHostApp app;
    final Rendering rendering;
    final int port;
    private final Lifecycle lifecycle = new Lifecycle();

    private JcefKitFixture(WebHostApp app, Rendering rendering, int port) {
        this.app = app;
        this.rendering = rendering;
        this.port = port;
        app.client().addLifeSpanHandler(lifecycle);
    }

    /** Starts the host in this JVM once (the same fixture afterwards) and waits until its pages have loaded. */
    static synchronized JcefKitFixture start(Rendering rendering) throws Exception {
        if (running != null) {
            if (running.rendering != rendering) {
                throw new IllegalStateException("this JVM runs the kit with " + running.rendering.id());
            }
            return running;
        }
        int port = freePort();
        WebHostApp app = WebHostApp.launch(WebHostOptions.defaults().rendering(rendering).debugPort(port));
        for (String page : app.pageNames()) {
            if (!app.awaitLoaded(page, WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the " + page + " page did not load");
            }
        }
        running = new JcefKitFixture(app, rendering, port);
        return running;
    }

    /** The running fixture. */
    static synchronized JcefKitFixture running() {
        if (running == null) {
            throw new IllegalStateException("start() first");
        }
        return running;
    }

    /** Shuts the host down (the JVM can end afterwards). */
    static synchronized void stop() {
        if (running != null) {
            running.app.shutdown();
        }
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    // -------------------------------------------------------------------------------------------- the fixture

    @Override
    public WebEngineAdapter newAdapter() {
        return new JcefAdapter();
    }

    @Override
    public ClassLoader hostLoader() {
        return WebHostApp.class.getClassLoader();
    }

    @Override
    public Collection<?> seeds(Which which) {
        Object browser = browserObject(which);
        return browser == null ? null : Collections.singletonList(browser);
    }

    @Override
    public Object browserObject(Which which) {
        switch (which) {
            case VISIBLE:
                return app.front();
            case HIDDEN:
                return app.back();
            default:
                return app.detached();
        }
    }

    @Override
    public void load(WebBrowser browser, String html) throws Exception {
        load((CefBrowser) browser.vendorObject(), KitPages.dataUrl(html));
    }

    @Override
    public Object newDisposableBrowser() throws Exception {
        if (rendering != Rendering.OSR) {
            return null;
        }
        String url = KitPages.dataUrl(BLANK);
        CefBrowser b = app.client().createBrowser(url, true, false);
        b.createImmediately();
        lifecycle.awaitCreated(b);
        load(b, url);
        return b;
    }

    @Override
    public void close(WebBrowser browser) throws Exception {
        CefBrowser b = (CefBrowser) browser.vendorObject();
        b.setCloseAllowed();
        b.close(true);
        lifecycle.awaitClosed(b);
    }

    @Override
    public AppListener installAppListener(WebBrowser browser) throws Exception {
        final AtomicInteger received = new AtomicInteger();
        devTools((CefBrowser) browser.vendorObject()).addEventListener(new CefDevToolsClient.EventListener() {
            @Override
            public void onEvent(String eventName, String messageAsJson) {
                received.incrementAndGet();
            }
        });
        return new AppListener() {
            @Override
            public int received() {
                return received.get();
            }
        };
    }

    @Override
    public void postToGuardedThread(Runnable task) {
        EventQueue.invokeLater(task);
    }

    @Override
    public WebRendering expectedRendering(Which which) {
        return rendering == Rendering.OSR ? WebRendering.OFFSCREEN_HEAVYWEIGHT : WebRendering.WINDOWED;
    }

    @Override
    public CapabilityState expectedEnumerationState() {
        return CapabilityState.OK;
    }

    @Override
    public Integer debugPort() {
        return Integer.valueOf(port);
    }

    @Override
    public boolean checksBitmapColours() {
        return true;
    }

    @Override
    public RecordingClassLoader pristineLoader() {
        ClassLoader test = JcefKitFixture.class.getClassLoader();
        return new RecordingClassLoader(test, test, "org.cef.");
    }

    @Override
    public RecordingClassLoader recordingHostLoader() {
        // JCEF's native libraries can be loaded once per JVM: no second application can use it here.
        return null;
    }

    // -------------------------------------------------------------------------------------------- helpers

    /** The browser's DevTools client; a client found closed is fetched again (BS8: a new browser's first one). */
    static CefDevToolsClient devTools(CefBrowser b) {
        CefDevToolsClient c = b.getDevToolsClient();
        if (c != null && c.isClosed()) {
            c = b.getDevToolsClient();
        }
        if (c == null) {
            throw new IllegalStateException("the browser has no DevTools client (no native browser, or closing)");
        }
        return c;
    }

    /**
     * Navigates the main frame to {@code url} and waits (bounded) for its {@code Page.frameNavigated} and the
     * {@code Page.loadEventFired} after it, heard through a listener of the fixture's own, the Page domain being on
     * before the navigation starts ({@link #enablePage}).
     */
    static void load(CefBrowser b, String url) throws Exception {
        CefDevToolsClient c = devTools(b);
        enablePage(c);
        final AtomicBoolean navigated = new AtomicBoolean();
        final CountDownLatch loaded = new CountDownLatch(1);
        CefDevToolsClient.EventListener listener = new CefDevToolsClient.EventListener() {
            @Override
            public void onEvent(String name, String json) {
                if ("Page.frameNavigated".equals(name)) {
                    Object frame = Json.parseObject(json).get("frame");
                    if (frame instanceof Map && !((Map<?, ?>) frame).containsKey("parentId")) {
                        navigated.set(true);
                    }
                } else if ("Page.loadEventFired".equals(name) && navigated.get()) {
                    loaded.countDown();
                }
            }
        };
        c.addEventListener(listener);
        try {
            b.loadURL(url);
            if (!loaded.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the page did not load within " + WAIT_MS + " ms (navigated: "
                        + navigated.get() + ")");
            }
        } finally {
            c.removeEventListener(listener);
        }
    }

    /**
     * Turns the browser's Page domain on and waits for the answer. A {@code Page.enable} still on its way while a
     * navigation commits in a new renderer misses that navigation's main-frame {@code Page.frameNavigated}: seen on the
     * amd64 image, windowed, for the first load after the host started (the answer came after the commit, and only the
     * iframe's {@code Page.frameNavigated} followed). JCEF's client can lose an answer that arrives before the message
     * id, so an unanswered {@code Page.enable} is sent again (enabling twice is harmless), within a bound.
     */
    static void enablePage(CefDevToolsClient c) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                c.executeDevToolsMethod("Page.enable", "{}").get(ENABLE_ANSWER_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException e) {
                if (attempt == ENABLE_ATTEMPTS) {
                    throw new IllegalStateException("Page.enable was not answered (" + ENABLE_ATTEMPTS + " attempts of "
                            + ENABLE_ANSWER_MS + " ms)");
                }
            }
        }
    }

    /** The application's life-span handler: tells creation and closure apart per browser. */
    static final class Lifecycle extends CefLifeSpanHandlerAdapter {
        private final Map<CefBrowser, Boolean> created = new IdentityHashMap<CefBrowser, Boolean>();
        private final Map<CefBrowser, Boolean> closed = new IdentityHashMap<CefBrowser, Boolean>();

        @Override
        public synchronized void onAfterCreated(CefBrowser browser) {
            created.put(browser, Boolean.TRUE);
            notifyAll();
        }

        @Override
        public boolean doClose(CefBrowser browser) {
            return browser.doClose();
        }

        @Override
        public synchronized void onBeforeClose(CefBrowser browser) {
            closed.put(browser, Boolean.TRUE);
            notifyAll();
        }

        synchronized void awaitCreated(CefBrowser b) throws InterruptedException {
            await(created, b, "created");
        }

        synchronized void awaitClosed(CefBrowser b) throws InterruptedException {
            await(closed, b, "closed");
        }

        private void await(Map<CefBrowser, Boolean> seen, CefBrowser b, String what) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
            while (!seen.containsKey(b)) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    throw new IllegalStateException("the browser was not " + what + " within " + WAIT_MS + " ms");
                }
                wait(left);
            }
        }
    }
}
