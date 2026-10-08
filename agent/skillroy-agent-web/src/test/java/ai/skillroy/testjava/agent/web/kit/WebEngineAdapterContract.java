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

package ai.skillroy.testjava.agent.web.kit;

import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.api.spi.web.WebFrameGoneException;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.agent.api.spi.web.WebSubscription;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The adapter contract kit (phase4-contracts.md section 10.1; browser-spi "Testing in public CI" item 1): the same
 * assertions for every {@link WebEngineAdapter}, one test per rule and operation, run against a technology through
 * the single hook {@link #fixture()}. Published as the {@code skillroy-agent-web} test-jar; a technology's test module
 * extends this class (JUnit 5, Java 8). Here it runs against the neutral fakes; the web host sample runs it against
 * JCEF in the Linux image; a licence holder runs it against JxBrowser in a repository of their own.
 *
 * <p>Every test runs under the {@link EdtWatchdog}: a marker posted to the guarded thread every 50 ms must not run
 * more than 500 ms late while an adapter operation is under way. The fixture's own calls (loading a page, closing a
 * browser) are excused, so item 5 also triggers a console message by evaluation, whose event reaches the adapter's
 * listener inside a judged window (a reload by script cannot serve: Chromium refuses a renderer-initiated navigation
 * of a {@code data:} document). Waits are bounded ({@value #BOUND_MS} ms per operation); where the kit
 * waits for something the technology does on its own (an init script after the document loaded) it polls with a
 * bound, because no event announces it. After each test the kit detaches the test's adapter and then calls
 * {@link #afterDetach()}, where a technology's test checks what its fake recorded (rule violations), so that a
 * violation made by {@code detach()} is charged to the test that made it.
 */
public abstract class WebEngineAdapterContract {

    /** Bound of one adapter operation. */
    protected static final long BOUND_MS = 10000;
    /** Bound of the 100 evaluations of item 3. */
    protected static final long HUNDRED_EVALUATIONS_MS = 30000;
    /** A second init script, the barrier of item 6. */
    static final String INIT2_SOURCE =
            "Object.defineProperty(window, '__kitInit2', {value: 'ran', configurable: true, enumerable: false})";
    static final String INIT2_NOW = "typeof window.__kitInit2";

    protected WebAdapterFixture fx;
    protected WebEngineAdapter adapter;
    protected EdtWatchdog watchdog;

    /** The technology's fixture (the one hook). */
    protected abstract WebAdapterFixture fixture() throws Exception;

    @BeforeEach
    void setUpKit() throws Exception {
        fx = fixture();
        watchdog = new EdtWatchdog(new EdtWatchdog.Poster() {
            @Override
            public void post(Runnable task) {
                fx.postToGuardedThread(task);
            }
        });
        watchdog.start();
        adapter = fx.newAdapter();
        ProbeResult r = adapter.probe(fx.hostLoader());
        assertEquals(CapabilityState.OK, r.state(), "probe of the fixture's host: " + r);
    }

    @AfterEach
    void tearDownKit() throws Exception {
        Throwable failure = null;
        try {
            if (adapter != null) {
                adapter.detach();
            }
            afterDetach();
        } catch (Throwable t) {
            failure = t;
        }
        try {
            watchdog.stopAndCheck();
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            } else {
                failure.addSuppressed(t);
            }
        }
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    /**
     * Called after each test right after the kit detached the test's adapter, before the watchdog's check: a
     * technology's test checks here what its fake recorded during the test and the detach (rule violations). Default:
     * nothing.
     */
    protected void afterDetach() throws Exception {
    }

    // ------------------------------------------------------------------------------------------------ item 1

    /**
     * Item 1: the probe is OK; against a host that loaded (and initialised) only the technology's gate class it is OK,
     * stays within the technology's own packages (every class it makes that host define belongs to one of them, where
     * the fixture names them, {@link WebAdapterFixture#technologyPackages}) and initialises no class of the technology
     * (where the fixture can tell, {@link WebAdapterFixture#initialisedClasses}); against a host that did not load the
     * gate it is UNAVAILABLE and loads nothing (phase4-contracts.md section 3: the gate must already be loaded by the
     * host; the lookups may then load the technology's classes, never initialise them).
     */
    @Test
    void item01ProbeIsOkInitialisesNoClassAndLoadsNothingWithoutTheGate() throws Exception {
        ProbeResult ok = adapter.probe(fx.hostLoader());
        assertEquals(CapabilityState.OK, ok.state(), String.valueOf(ok));
        assertNotNull(ok.strategy(), "an OK probe names the product and line");
        RecordingClassLoader host = fx.recordingHostLoader();
        if (host != null) {
            Set<String> before = fx.initialisedClasses(host);
            Set<String> definedBefore = host.defined();
            WebEngineAdapter fresh = fx.newAdapter();
            try {
                ProbeResult r = fresh.probe(host);
                assertEquals(CapabilityState.OK, r.state(), "probe of a host that loaded the gate class only: " + r);
                Set<String> packages = fx.technologyPackages();
                if (packages != null) {
                    List<String> outside = new ArrayList<String>();
                    for (String name : host.defined()) {
                        String pkg = name.lastIndexOf('.') < 0 ? "" : name.substring(0, name.lastIndexOf('.'));
                        if (!definedBefore.contains(name) && !packages.contains(pkg)) {
                            outside.add(name);
                        }
                    }
                    assertTrue(outside.isEmpty(), "the probe loaded classes outside the technology's packages "
                            + packages + ": " + outside);
                }
                if (before != null) {
                    Set<String> after = new java.util.TreeSet<String>(fx.initialisedClasses(host));
                    after.removeAll(before);
                    assertTrue(after.isEmpty(), "the probe initialised classes of the technology: " + after);
                }
            } finally {
                fresh.detach();
            }
        }
        RecordingClassLoader pristine = fx.pristineLoader();
        if (pristine != null) {
            WebEngineAdapter fresh = fx.newAdapter();
            try {
                ProbeResult r = fresh.probe(pristine);
                assertEquals(CapabilityState.UNAVAILABLE, r.state(), "a technology the host has not loaded counts "
                        + "as absent: " + r);
                assertTrue(pristine.defined().isEmpty(), "the probe loaded classes the host had not loaded: "
                        + pristine.defined());
            } finally {
                fresh.detach();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ item 2

    @Test
    void item02EnumerationFromSeedsFindsEveryBrowserOnceAndIgnoresUnrelatedSeeds() throws Exception {
        List<Object> seeds = allSeeds();
        seeds.add(new javax.swing.JButton("unrelated"));
        seeds.add("not a seed");
        seeds.add(Integer.valueOf(7));
        List<WebBrowser> first = adapter.browsers(seeds);
        List<WebBrowser> second = adapter.browsers(seeds);
        for (WebAdapterFixture.Which which : WebAdapterFixture.Which.values()) {
            Object vendor = fx.browserObject(which);
            if (vendor == null) {
                continue;
            }
            WebBrowser a = find(first, vendor);
            WebBrowser b = find(second, vendor);
            assertNotNull(a, which + " found");
            assertNotNull(b, which + " found again");
            assertEquals(a, b, which + ": the same browser gives equal handles");
            assertEquals(a.hashCode(), b.hashCode());
            assertEquals(fx.expectedRendering(which), a.rendering(), which + " rendering");
            assertEquals(adapter.id(), a.adapterId());
            if (which == WebAdapterFixture.Which.VIEWLESS) {
                assertNull(a.component(), "a browser without a view has no component");
            }
        }
        Set<Object> vendors = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        for (WebBrowser b : first) {
            assertTrue(vendors.add(b.vendorObject()), "each browser once");
        }
        assertEquals(fx.expectedEnumerationState(), adapter.enumerationState().state(),
                String.valueOf(adapter.enumerationState()));
        assertNotNull(adapter.enumerationNotes());
    }

    // ------------------------------------------------------------------------------------------------ item 3

    @Test
    void item03EvaluateValuesErrorsPromisesTruncationAndAHundredInARow() throws Exception {
        WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        load(b, KitPages.main());
        assertEquals("42", Json.write(Json.parse(eval(b, KitScripts.NUMBER))));
        assertEquals("kit-string", Json.parse(eval(b, KitScripts.STRING)));
        Map<String, Object> object = new LinkedHashMap<String, Object>();
        object.put("a", Long.valueOf(1));
        object.put("b", Arrays.asList(Boolean.TRUE, null));
        object.put("c", "x");
        assertEquals(object, Json.parse(eval(b, KitScripts.OBJECT)));
        assertEquals("null", eval(b, KitScripts.UNDEFINED));
        Throwable thrown = failure(b.evaluate(WebEval.of(KitScripts.THROWS).timeoutMs(BOUND_MS).build()));
        assertTrue(thrown instanceof WebScriptException, "a thrown error is a WebScriptException: " + thrown);
        assertTrue(thrown.getMessage().contains("kit boom"), thrown.getMessage());
        if (b.capabilities().contains(WebCapability.AWAIT_PROMISE)) {
            String seven = b.evaluate(WebEval.of(KitScripts.PROMISE).awaitPromise(true).timeoutMs(BOUND_MS).build())
                    .get(BOUND_MS + 1000, TimeUnit.MILLISECONDS);
            assertEquals("7", Json.write(Json.parse(seven)));
        } else {
            Throwable t = failure(b.evaluate(WebEval.of(KitScripts.PROMISE).awaitPromise(true).build()));
            assertTrue(t instanceof UnsupportedOperationException, String.valueOf(t));
        }
        Object text = Json.parse(eval(b, WebLib.wrap(KitScripts.BIG_STRING, false)));
        assertTrue(text instanceof String, "the wrapper's value crosses as text");
        Map<String, Object> wrapped = Json.parseObject((String) text);
        assertEquals(Boolean.TRUE, wrapped.get("truncated"), "a 2 MiB string is cut");
        assertEquals("string", wrapped.get("typeofResult"));
        assertEquals(WebLib.MAX_JSON_CHARS, ((String) wrapped.get("json")).length(), "cut at 1 MiB");
        long started = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertEquals(String.valueOf(2 * i), Json.write(Json.parse(eval(b, KitScripts.twice(i)))));
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(ms < HUNDRED_EVALUATIONS_MS, "100 evaluations took " + ms + " ms");
    }

    // ------------------------------------------------------------------------------------------------ item 4

    @Test
    void item04FramesMainFirstTheIframeWithItsParentAndAGoneFrame() throws Exception {
        WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        assumeTrue(b.capabilities().contains(WebCapability.FRAMES), "the adapter has no FRAMES");
        load(b, KitPages.main());
        List<WebFrame> frames = b.frames();
        assertFalse(frames.isEmpty(), "frames of a loaded page");
        WebFrame main = frames.get(0);
        assertTrue(main.main(), "the main frame first");
        assertNull(main.parentKey());
        WebFrame inner = null;
        for (WebFrame f : frames) {
            if (KitPages.INNER_NAME.equals(f.name())) {
                inner = f;
            }
        }
        assertNotNull(inner, "the iframe is listed: " + frames);
        assertEquals(main.key(), inner.parentKey());
        assertFalse(inner.main());
        String title = b.evaluate(WebEval.of(KitScripts.TITLE).frameKey(inner.key()).timeoutMs(BOUND_MS).build())
                .get(BOUND_MS + 1000, TimeUnit.MILLISECONDS);
        assertEquals(KitPages.INNER_TITLE, Json.parse(title), "evaluated in the iframe");
        assertEquals(KitPages.MAIN_TITLE, Json.parse(eval(b, KitScripts.TITLE)), "the main frame is the default");
        load(b, KitPages.second());
        Throwable gone = failure(b.evaluate(WebEval.of(KitScripts.TITLE).frameKey(inner.key()).timeoutMs(BOUND_MS)
                .build()));
        assertTrue(gone instanceof WebFrameGoneException, "a frame that navigated away: " + gone);
        assertEquals(inner.key(), ((WebFrameGoneException) gone).frameKey());
    }

    // ------------------------------------------------------------------------------------------------ item 5

    @Test
    void item05EventsLoadNavigationConsoleClosureAndNoneAfterCancel() throws Exception {
        WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        assumeTrue(b.capabilities().contains(WebCapability.EVENTS_LOAD), "the adapter has no EVENTS_LOAD");
        Recorder live = new Recorder();
        Recorder cancelled = new Recorder();
        WebSubscription keep = b.subscribe(live);
        WebSubscription drop = b.subscribe(cancelled);
        try {
            load(b, KitPages.main());
            live.await(WebEvent.Kind.LOAD_FINISHED, null);
            live.await(WebEvent.Kind.NAVIGATED, null);
            if (b.capabilities().contains(WebCapability.EVENTS_CONSOLE)) {
                WebEvent console = live.await(WebEvent.Kind.CONSOLE, KitScripts.CONSOLE_MARKER);
                assertNotNull(console.level());
                // Events the kit triggers itself reach the adapter's listeners outside the fixture's calls, where
                // the watchdog judges them (rule 4: a listener that blocks the guarded thread fails here).
                int consoles = live.count(WebEvent.Kind.CONSOLE);
                eval(b, KitScripts.CONSOLE_LOG);
                live.awaitCount(WebEvent.Kind.CONSOLE, consoles + 1);
            }
            // A second navigation is made through the fixture, not by script: Chromium refuses a renderer-initiated
            // top-frame navigation of a data: document (location.reload() and location.hash included), which is
            // what the kit's pages are. The fixture's load is excused by the watchdog; the console event above is the
            // judged one.
            drop.cancel();
            drop.cancel();
            int before = cancelled.count();
            int liveBefore = live.count(WebEvent.Kind.LOAD_FINISHED);
            load(b, KitPages.second());
            live.awaitCount(WebEvent.Kind.LOAD_FINISHED, liveBefore + 1);
            assertEquals(before, cancelled.count(), "no event after cancel(): " + cancelled.events());
        } finally {
            keep.cancel();
        }
        Object disposable = watchdog.excused(new EdtWatchdog.Call<Object>() {
            @Override
            public Object call() throws Exception {
                return fx.newDisposableBrowser();
            }
        });
        if (disposable != null) {
            WebBrowser d = find(adapter.browsers(Collections.singletonList(disposable)), disposable);
            assertNotNull(d, "the disposable browser is found from its seed");
            Recorder closed = new Recorder();
            d.subscribe(closed);
            close(d);
            assertTrue(d.isClosed(), "isClosed() after the application closed it");
            closed.await(WebEvent.Kind.CLOSED, null);
        }
    }

    // ------------------------------------------------------------------------------------------------ item 6

    @Test
    void item06InitScriptRunsAtItsTimingAndNotAfterCancel() throws Exception {
        WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        assumeTrue(b.capabilities().contains(WebCapability.EVALUATE), "the adapter has no EVALUATE");
        WebInitScript s = b.addInitScript(KitScripts.INIT_SOURCE);
        assertNotNull(s.timing());
        assertEquals(s.timing() == WebInitScript.Timing.DOCUMENT_START,
                b.capabilities().contains(WebCapability.INIT_SCRIPT_DOCUMENT_START),
                "the timing matches the capability");
        try {
            load(b, KitPages.main());
            if (s.timing() == WebInitScript.Timing.DOCUMENT_START) {
                assertEquals("string", Json.parse(eval(b, KitScripts.INIT_AT_PARSE)),
                        "the page's inline script saw the init script");
                assertEquals("string", Json.parse(eval(b, KitScripts.INIT_NOW)));
            } else {
                assertEquals("undefined", Json.parse(eval(b, KitScripts.INIT_AT_PARSE)),
                        "a late init script runs after the page's own scripts");
                pollUntil(b, KitScripts.INIT_NOW, "string");
            }
        } finally {
            s.cancel();
            s.cancel();
        }
        WebInitScript barrier = b.addInitScript(INIT2_SOURCE);
        try {
            load(b, KitPages.second());
            pollUntil(b, INIT2_NOW, "string");
            assertEquals("undefined", Json.parse(eval(b, KitScripts.INIT_NOW)), "a cancelled script does not run");
        } finally {
            barrier.cancel();
        }
    }

    // ------------------------------------------------------------------------------------------------ item 7

    @Test
    void item07ResizeChangesTheViewportAndClearRestoresIt() throws Exception {
        WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        assumeTrue(b.capabilities().contains(WebCapability.RESIZE), "the adapter has no RESIZE");
        load(b, KitPages.main());
        String before = (String) Json.parse(eval(b, KitScripts.INNER_SIZE));
        b.resize(640, 480).get(BOUND_MS, TimeUnit.MILLISECONDS);
        assertEquals("640x480", Json.parse(eval(b, KitScripts.INNER_SIZE)));
        assertEquals(new Dimension(640, 480), b.viewport(), "viewport() follows the override");
        b.clearResize().get(BOUND_MS, TimeUnit.MILLISECONDS);
        String after = (String) Json.parse(eval(b, KitScripts.INNER_SIZE));
        assertEquals(before, after, "clearResize restores the viewport");
        Dimension v = b.viewport();
        if (v != null) {
            assertEquals(before, v.width + "x" + v.height, "viewport() follows the restored size");
        }
        b.clearResize().get(BOUND_MS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------------------------------------ item 8

    /**
     * Item 8: the visible browser's bitmap decodes and shows the page (its background in the corner, more than one
     * colour); the hidden browser's, after a viewport override, the same, unless the adapter refuses it at once with
     * an {@code UnsupportedOperationException} saying the browser is hidden (a technology that renders nothing for a
     * browser that is not shown; never an answer that does not come).
     */

    @Test
    void item08BitmapOfTheVisibleAndTheHiddenBrowserDecodes() throws Exception {
        for (WebAdapterFixture.Which which : new WebAdapterFixture.Which[] {WebAdapterFixture.Which.VISIBLE,
                WebAdapterFixture.Which.HIDDEN}) {
            WebBrowser b = browser(which);
            assumeTrue(b.capabilities().contains(WebCapability.BITMAP), "the adapter has no BITMAP");
            boolean resized = false;
            if (which == WebAdapterFixture.Which.HIDDEN && b.capabilities().contains(WebCapability.RESIZE)) {
                b.resize(640, 480).get(BOUND_MS, TimeUnit.MILLISECONDS);
                resized = true;
            }
            try {
                load(b, KitPages.main());
                CompletableFuture<BufferedImage> shot = b.bitmap();
                if (which == WebAdapterFixture.Which.HIDDEN && shot.isCompletedExceptionally()) {
                    // A technology that renders nothing for a hidden browser (JCEF: Chromium paints no frame for
                    // it) refuses at once rather than answering with an empty picture or never.
                    Throwable refused = failure(shot);
                    assertTrue(refused instanceof UnsupportedOperationException, which + ": refused with "
                            + refused);
                    assertTrue(String.valueOf(refused.getMessage()).contains("hidden"), refused.getMessage());
                    continue;
                }
                BufferedImage image = shot.get(BOUND_MS, TimeUnit.MILLISECONDS);
                assertNotNull(image, which + " bitmap");
                assertTrue(image.getWidth() > 0 && image.getHeight() > 0, which + " bitmap size");
                if (fx.checksBitmapColours() && (which == WebAdapterFixture.Which.VISIBLE || resized)) {
                    assertEquals(KitPages.BACKGROUND_RGB, image.getRGB(1, 1) & 0xffffff, which
                            + ": the page's background in the corner");
                    Set<Integer> colours = new HashSet<Integer>();
                    for (int y = 0; y < image.getHeight(); y += 3) {
                        for (int x = 0; x < image.getWidth(); x += 3) {
                            colours.add(Integer.valueOf(image.getRGB(x, y) & 0xffffff));
                        }
                    }
                    assertTrue(colours.size() > 1, which + ": more than one colour");
                }
            } finally {
                if (resized) {
                    b.clearResize().get(BOUND_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ item 9

    @Test
    void item09CdpTargetIdIsInTheJsonList() throws Exception {
        Integer port = fx.debugPort();
        assumeTrue(port != null, "the fixture configured no debug port");
        WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        assumeTrue(b.capabilities().contains(WebCapability.CDP_TARGET_ID), "the adapter has no CDP_TARGET_ID");
        load(b, KitPages.main());
        String id = b.cdpTargetId().get(BOUND_MS, TimeUnit.MILLISECONDS);
        assertNotNull(id, "a target id with a debug port configured");
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/json/list")
                .openConnection();
        c.setConnectTimeout((int) BOUND_MS);
        c.setReadTimeout((int) BOUND_MS);
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            List<String> ids = new ArrayList<String>();
            for (Object entry : (List<?>) Json.parse(new String(out.toByteArray(), StandardCharsets.UTF_8))) {
                ids.add(String.valueOf(((Map<?, ?>) entry).get("id")));
            }
            assertTrue(ids.contains(id), id + " is in /json/list " + ids);
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ item 10

    @Test
    void item10AClosedBrowserFailsClearlyWithinTheBound() throws Exception {
        Object disposable = watchdog.excused(new EdtWatchdog.Call<Object>() {
            @Override
            public Object call() throws Exception {
                return fx.newDisposableBrowser();
            }
        });
        assumeTrue(disposable != null, "the fixture cannot make a browser to close");
        WebBrowser d = find(adapter.browsers(Collections.singletonList(disposable)), disposable);
        assertNotNull(d);
        close(d);
        assertTrue(d.isClosed());
        List<CompletableFuture<?>> operations = new ArrayList<CompletableFuture<?>>();
        operations.add(d.evaluate(WebEval.of(KitScripts.NUMBER).timeoutMs(BOUND_MS).build()));
        operations.add(d.resize(10, 10));
        operations.add(d.clearResize());
        operations.add(d.bitmap());
        operations.add(d.cdpTargetId());
        for (CompletableFuture<?> op : operations) {
            Throwable t = failure(op);
            assertTrue(t instanceof IllegalStateException, "a closed browser fails with IllegalStateException: " + t);
            assertTrue(String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("closed"),
                    "the message says so: " + t.getMessage());
        }
        assertTrue(d.frames().isEmpty());
        assertThrows(IllegalStateException.class, () -> d.addInitScript(KitScripts.INIT_SOURCE));
    }

    @Test
    void item10DetachRemovesTheKitsListenersAndScriptsAndKeepsTheApplicationsListener() throws Exception {
        final WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        assumeTrue(b.capabilities().contains(WebCapability.EVENTS_LOAD)
                && b.capabilities().contains(WebCapability.EVALUATE), "the adapter has no events or evaluation");
        int vendorBefore = fx.vendorListenerCount(b);
        Recorder kit = new Recorder();
        b.subscribe(kit);
        b.addInitScript(KitScripts.INIT_SOURCE);
        WebAdapterFixture.AppListener app = watchdog.excused(new EdtWatchdog.Call<WebAdapterFixture.AppListener>() {
            @Override
            public WebAdapterFixture.AppListener call() throws Exception {
                return fx.installAppListener(b);
            }
        });
        adapter.detach();
        if (vendorBefore >= 0) {
            assertEquals(vendorBefore + 1, fx.vendorListenerCount(b), "detach removed what the adapter registered "
                    + "with the technology and left the application's own listener");
        }
        int kitBefore = kit.count();
        int appBefore = app.received();
        WebEngineAdapter fresh = fx.newAdapter();
        try {
            assertEquals(CapabilityState.OK, fresh.probe(fx.hostLoader()).state());
            WebBrowser again = find(fresh.browsers(fx.seeds(WebAdapterFixture.Which.VISIBLE)),
                    fx.browserObject(WebAdapterFixture.Which.VISIBLE));
            assertNotNull(again);
            load(again, KitPages.main());
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
            while (app.received() <= appBefore && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(app.received() > appBefore, "the application's own listener still receives events");
            assertEquals(kitBefore, kit.count(), "no event reaches a listener the detached adapter added: "
                    + kit.events());
            assertEquals("undefined", Json.parse(eval(again, KitScripts.INIT_NOW)),
                    "no init script of the detached adapter runs");
        } finally {
            fresh.detach();
        }
    }

    // ------------------------------------------------------------------------------------------------ item 11

    @Test
    void item11NoEnumerableGlobalIsLeftOnTheWindow() throws Exception {
        WebBrowser b = browser(WebAdapterFixture.Which.VISIBLE);
        load(b, KitPages.main());
        Object before = Json.parse(eval(b, KitScripts.WINDOW_KEYS));
        eval(b, WebLib.wrap(KitScripts.NUMBER, false));
        Map<String, Object> text = new LinkedHashMap<String, Object>();
        text.put("selector", "body");
        text.put("maxChars", Long.valueOf(100));
        eval(b, WebLib.callExpression("domText", text));
        eval(b, WebLib.tapInstaller(Collections.singletonList("kit.*"), "window"));
        eval(b, WebLib.callExpression("drain"));
        eval(b, WebLib.tapInstaller(Collections.<String>emptyList(), "window"));
        eval(b, WebLib.callExpression("aria", null, Long.valueOf(50), Boolean.TRUE));
        eval(b, WebLib.installExpression());
        assertEquals(before, Json.parse(eval(b, KitScripts.WINDOW_KEYS)), "Object.keys(window) unchanged");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The handle of one of the fixture's browsers, found from its seeds. */
    protected WebBrowser browser(WebAdapterFixture.Which which) {
        Object vendor = fx.browserObject(which);
        assertNotNull(vendor, "the fixture has a " + which + " browser");
        WebBrowser b = find(adapter.browsers(fx.seeds(which)), vendor);
        assertNotNull(b, which + " browser found from its seeds");
        return b;
    }

    private List<Object> allSeeds() {
        List<Object> seeds = new ArrayList<Object>();
        for (WebAdapterFixture.Which which : WebAdapterFixture.Which.values()) {
            Collection<?> s = fx.seeds(which);
            if (s != null) {
                seeds.addAll(s);
            }
        }
        return seeds;
    }

    protected static WebBrowser find(List<WebBrowser> browsers, Object vendor) {
        for (WebBrowser b : browsers) {
            if (b.vendorObject() == vendor) {
                return b;
            }
        }
        return null;
    }

    /** Loads through the fixture (the application's way), excused from the watchdog. */
    protected void load(final WebBrowser b, final String html) throws Exception {
        watchdog.excused(new EdtWatchdog.Call<Object>() {
            @Override
            public Object call() throws Exception {
                fx.load(b, html);
                return null;
            }
        });
    }

    private void close(final WebBrowser b) throws Exception {
        watchdog.excused(new EdtWatchdog.Call<Object>() {
            @Override
            public Object call() throws Exception {
                fx.close(b);
                return null;
            }
        });
    }

    /** The JSON text of the expression's value, within the bound. */
    protected static String eval(WebBrowser b, String expression) throws Exception {
        return b.evaluate(WebEval.of(expression).timeoutMs(BOUND_MS).build()).get(BOUND_MS + 1000,
                TimeUnit.MILLISECONDS);
    }

    /** What the future failed with (it must fail within the bound). */
    protected static Throwable failure(CompletableFuture<?> f) throws Exception {
        try {
            Object v = f.get(BOUND_MS + 1000, TimeUnit.MILLISECONDS);
            fail("expected a failure, got " + v);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /** Polls the expression until its value is {@code expected} (bounded): nothing announces a late script. */
    private static void pollUntil(WebBrowser b, String expression, Object expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
        Object v = null;
        while (System.nanoTime() < deadline) {
            v = Json.parse(eval(b, expression));
            if (expected.equals(v)) {
                return;
            }
        }
        assertEquals(expected, v, expression);
    }

    /** Records events; waits for them with a bound. */
    protected static final class Recorder implements WebEventListener {
        private final List<WebEvent> events = new ArrayList<WebEvent>();

        @Override
        public synchronized void onEvent(WebBrowser browser, WebEvent event) {
            events.add(event);
            notifyAll();
        }

        public synchronized int count() {
            return events.size();
        }

        public synchronized int count(WebEvent.Kind kind) {
            int n = 0;
            for (WebEvent e : events) {
                if (e.kind() == kind) {
                    n++;
                }
            }
            return n;
        }

        /** How many events of the kind arrived for the main frame (no frame key). */
        public synchronized int countMain(WebEvent.Kind kind) {
            int n = 0;
            for (WebEvent e : events) {
                if (e.kind() == kind && e.frameKey() == null) {
                    n++;
                }
            }
            return n;
        }

        /** Waits until {@code n} events of the kind arrived for the main frame. */
        public synchronized void awaitMain(WebEvent.Kind kind, int n) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
            while (countMain(kind) < n) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    fail("fewer than " + n + " " + kind + " of the main frame within " + BOUND_MS + " ms; got "
                            + events);
                }
                wait(left);
            }
        }

        public synchronized List<WebEvent> events() {
            return new ArrayList<WebEvent>(events);
        }

        /** The first event of the kind (whose text contains {@code text}, when given), waiting at most the bound. */
        public synchronized WebEvent await(WebEvent.Kind kind, String text) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
            while (true) {
                for (WebEvent e : events) {
                    if (e.kind() == kind && (text == null || (e.text() != null && e.text().contains(text)))) {
                        return e;
                    }
                }
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    fail("no " + kind + (text == null ? "" : " with '" + text + "'") + " within " + BOUND_MS
                            + " ms; got " + events);
                }
                wait(left);
            }
        }

        /** Waits until {@code n} events of the kind arrived. */
        public synchronized void awaitCount(WebEvent.Kind kind, int n) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
            while (count(kind) < n) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    fail("fewer than " + n + " " + kind + " within " + BOUND_MS + " ms; got " + events);
                }
                wait(left);
            }
        }
    }
}
