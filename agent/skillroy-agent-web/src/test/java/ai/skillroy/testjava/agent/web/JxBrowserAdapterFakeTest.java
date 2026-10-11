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

import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent;
import ai.skillroy.testjava.agent.web.fake.FakeMouseMoved;
import ai.skillroy.testjava.agent.web.fake.FakeMousePressed;
import ai.skillroy.testjava.agent.web.fake.FakeMouseReleased;
import ai.skillroy.testjava.agent.web.fake.FakeMouseWheel;
import java.util.EnumSet;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.BitmapSource;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.agent.web.fake.FakeBrowser;
import ai.skillroy.testjava.agent.web.fake.FakeConsoleMessageReceived;
import ai.skillroy.testjava.agent.web.fake.FakeEngine;
import ai.skillroy.testjava.agent.web.fake.FakeInjectJsCallback;
import ai.skillroy.testjava.agent.web.fake.FakeJxFixture;
import ai.skillroy.testjava.agent.web.fake.FakeObserver;
import ai.skillroy.testjava.agent.web.fake.FakeRenderingMode;
import ai.skillroy.testjava.agent.web.fake.FakeView;
import ai.skillroy.testjava.agent.web.jxbrowser.JxBrowserAdapter;
import ai.skillroy.testjava.agent.web.kit.KitPages;
import ai.skillroy.testjava.agent.web.kit.KitScripts;
import ai.skillroy.testjava.agent.web.kit.RecordingClassLoader;
import ai.skillroy.testjava.agent.web.reflect.Descriptor;
import ai.skillroy.testjava.agent.web.reflect.ReflectiveApi;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code jxbrowser} adapter's own rules against the neutral fake (phase4-contracts.md sections 4.5 and 10.2),
 * beyond the contract kit: the fake's descriptor is the built-in one with the fake's class names and nothing else; the
 * strategy names the fake; no licence key is read; a seed of another class loader is resolved in that loader; no
 * {@code set(...)} callback is taken without the opt-in, and with it the previous occupant is called first and put
 * back on detach; a line without the bitmap member has no {@code BITMAP}, so {@code web.bitmap} is unavailable; a
 * line without the reload members reloads through the page, the SPI's default (A37);
 * detach removes the adapter's observers on the technology's side (the application's stay), fails the calls in
 * progress and leaves no thread, also when a vendor callback arrives late; a hung engine keeps every caller within
 * its bound.
 */
class JxBrowserAdapterFakeTest {

    private static final String FAKE_PACKAGE = "ai.skillroy.testjava.agent.web.fake.";
    /** The fake's descriptor for a line without the reload members (A37). */
    private static final String NO_RELOAD = "ai/skillroy/testjava/agent/web/fake/fake-jxbrowser-noreload.json";
    private static final String PAGE = "<!doctype html><html><head><title>Loan desk</title></head>"
            + "<body><p id=\"status\">ready</p></body></html>";
    private static final ClassLoader HOST = JxBrowserAdapterFakeTest.class.getClassLoader();

    private static final JxBrowserAdapter.Options CHAIN = new JxBrowserAdapter.Options() {
        @Override
        public boolean chainInjectJs() {
            return true;
        }
    };

    private static FakeBrowser browser(FakeEngine engine) throws Exception {
        FakeBrowser b = engine.newBrowser();
        b.loadAndWait(PAGE);
        return b;
    }

    private static String eval(WebBrowser b, String expression) throws Exception {
        return b.evaluate(WebEval.of(expression).build()).get(10, TimeUnit.SECONDS);
    }

    @Test
    void theFakesDescriptorIsTheBuiltInOneWithTheFakesClassNames() {
        Descriptor real = Descriptor.load(JxBrowserAdapter.DESCRIPTOR, HOST);
        Descriptor fake = Descriptor.load(FakeJxFixture.DESCRIPTOR, HOST);
        assertEquals(real.adapter(), fake.adapter());
        assertEquals(real.roles(), fake.roles(), "the same roles");
        Map<String, String> names = new LinkedHashMap<String, String>();
        for (String role : real.roles()) {
            Descriptor.Role r = real.role(role);
            Descriptor.Role f = fake.role(role);
            same(names, r, f, role);
            assertEquals(r.alternatives().size(), f.alternatives().size(), role);
            for (int i = 0; i < r.alternatives().size(); i++) {
                same(names, r.alternatives().get(i), f.alternatives().get(i), role + " alternative " + i);
            }
        }
        assertEquals(names.get(real.gate()), fake.gate());
        assertEquals(names.size(), new HashSet<String>(names.values()).size(), "one fake class per vendor class");
        for (Map.Entry<String, String> e : names.entrySet()) {
            assertTrue(e.getKey().startsWith("com.teamdev.jxbrowser."), e.getKey());
            assertTrue(e.getValue().startsWith(FAKE_PACKAGE), e.getValue());
        }
        assertEquals(Collections.emptyMap(), ReflectiveApi.resolve(fake, HOST).missing(),
                "the fake has every member the adapter may use");
    }

    private static void same(Map<String, String> names, Descriptor.Role r, Descriptor.Role f, String where) {
        mapped(names, r.className(), f.className(), where);
        assertEquals(r.method(), f.method(), where);
        assertEquals(r.field(), f.field(), where);
        assertEquals(r.arity(), f.arity(), where);
        assertEquals(r.isStatic(), f.isStatic(), where);
        assertEquals(r.isPrivate(), f.isPrivate(), where);
        assertEquals(r.isInterface(), f.isInterface(), where);
        assertEquals(r.byName(), f.byName(), where);
        assertEquals(r.group(), f.group(), where);
        assertEquals(r.params().size(), f.params().size(), where);
        for (int i = 0; i < r.params().size(); i++) {
            String rp = r.params().get(i);
            String fp = f.params().get(i);
            if (rp.startsWith("com.teamdev.")) {
                mapped(names, rp, fp, where + " parameter " + i);
            } else {
                assertEquals(rp, fp, where + " parameter " + i);
            }
        }
    }

    private static void mapped(Map<String, String> names, String real, String fake, String where) {
        String known = names.get(real);
        if (known == null) {
            names.put(real, fake);
        } else {
            assertEquals(known, fake, where + ": " + real + " stands for one fake class");
        }
    }

    @Test
    void theStrategyNamesTheFakeAndNoLicenceKeyIsRead() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(9229, Paths.get("work", "desk-profile"),
                FakeRenderingMode.OFF_SCREEN);
        try {
            FakeBrowser shown = browser(engine);
            FakeBrowser other = browser(engine);
            JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                assertEquals(ProbeResult.ok("jxbrowser (fake)" + JxBrowserAdapter.INPUT_UNVERIFIED),
                        adapter.probe(HOST), "the INPUT roles resolve, so the strategy says input is unverified (4.5)");
                assertTrue(adapter.browsers(Collections.emptyList()).isEmpty(), "seeds only");
                assertEquals(ProbeResult.degraded("no seed: an engine without a view in a window is invisible"),
                        adapter.enumerationState());
                List<WebBrowser> viaView = adapter.browsers(Collections.singletonList(new FakeView(shown)));
                assertEquals(1, viaView.size());
                assertEquals(CapabilityState.OK, adapter.enumerationState().state());
                assertEquals(2, adapter.browsers(Collections.singletonList(engine)).size(), "an engine lists all");
                assertEquals(Collections.singletonList(new CdpEndpoint("127.0.0.1", 9229, "desk-profile")),
                        adapter.cdpEndpoints(Arrays.asList(engine, shown, new FakeView(other))), "one per engine");
                assertEquals("\"Loan desk\"", eval(viaView.get(0), KitScripts.TITLE));
            } finally {
                adapter.detach();
            }
            assertEquals(Collections.emptyList(), engine.violations(), "no licence key read, no input dispatched");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void aSeedOfAnotherClassLoaderIsResolvedInItsOwnLoader() throws Exception {
        FakeEngine local = FakeEngine.newInstance(null, Paths.get("local"), FakeRenderingMode.OFF_SCREEN);
        RecordingClassLoader loader = new RecordingClassLoader(HOST, HOST, FAKE_PACKAGE);
        Class<?> engineType = Class.forName(FAKE_PACKAGE + "FakeEngine", true, loader);
        Class<?> modeType = Class.forName(FAKE_PACKAGE + "FakeRenderingMode", true, loader);
        assertNotSame(FakeEngine.class, engineType);
        Object remote = engineType.getMethod("newInstance", Integer.class, Path.class, modeType).invoke(null, null,
                Paths.get("remote"), modeType.getField("OFF_SCREEN").get(null));
        try {
            browser(local);
            Object remoteBrowser = engineType.getMethod("newBrowser").invoke(remote);
            Method load = remoteBrowser.getClass().getMethod("loadAndWait", String.class);
            load.invoke(remoteBrowser, PAGE.replace("Loan desk", "Branch desk"));
            JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                assertEquals(CapabilityState.OK, adapter.probe(HOST).state());
                List<WebBrowser> found = adapter.browsers(Arrays.asList(local, remote));
                assertEquals(2, found.size());
                WebBrowser theirs = found.get(0).vendorObject() == remoteBrowser ? found.get(0) : found.get(1);
                assertSame(remoteBrowser, theirs.vendorObject());
                assertSame(loader, theirs.vendorObject().getClass().getClassLoader());
                assertEquals("\"Branch desk\"", eval(theirs, KitScripts.TITLE), "evaluated through its own classes");
                WebBrowser ours = found.get(0) == theirs ? found.get(1) : found.get(0);
                assertEquals("\"Loan desk\"", eval(ours, KitScripts.TITLE));
                assertTrue(theirs.capabilities().contains(WebCapability.BITMAP));
            } finally {
                adapter.detach();
            }
        } finally {
            local.shutdown();
            engineType.getMethod("shutdown").invoke(remote);
        }
    }

    @Test
    void noCallbackIsTakenWithoutTheOptInAndWithItThePreviousOccupantIsCalledAndPutBack() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("chain"), FakeRenderingMode.OFF_SCREEN);
        try {
            FakeBrowser plainBrowser = browser(engine);
            JxBrowserAdapter plain = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null, JxBrowserAdapter.NO_OPTIONS);
            try {
                plain.probe(HOST);
                WebBrowser h = plain.browsers(Collections.singletonList(plainBrowser)).get(0);
                assertFalse(h.capabilities().contains(WebCapability.INIT_SCRIPT_DOCUMENT_START));
                assertEquals(WebInitScript.Timing.DOCUMENT_LOADED, h.addInitScript(KitScripts.INIT_SOURCE).timing());
                assertEquals(Collections.emptyList(), plainBrowser.setCalls(), "no set(...) without the opt-in");
            } finally {
                plain.detach();
            }

            FakeBrowser occupied = browser(engine);
            final List<String> seen = new CopyOnWriteArrayList<String>();
            FakeInjectJsCallback own = new FakeInjectJsCallback() {
                @Override
                public Response on(Params params) {
                    seen.add("application");
                    return Response.proceed();
                }
            };
            occupied.set(FakeInjectJsCallback.class, own);
            FakeBrowser empty = browser(engine);
            JxBrowserAdapter chained = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null, CHAIN);
            try {
                chained.probe(HOST);
                List<WebBrowser> found = chained.browsers(Arrays.asList(occupied, empty));
                for (WebBrowser h : found) {
                    assertTrue(h.capabilities().contains(WebCapability.INIT_SCRIPT_DOCUMENT_START));
                    assertEquals(WebInitScript.Timing.DOCUMENT_START, h.addInitScript(KitScripts.INIT_SOURCE)
                            .timing());
                }
                assertEquals(2, occupied.setCalls().size(), "the application's and the adapter's");
                assertNotSame(own, occupied.get(FakeInjectJsCallback.class).get());
                occupied.loadAndWait(KitPages.second());
                assertEquals(Collections.singletonList("application"), seen, "the previous occupant ran first");
                WebBrowser h = found.get(0).vendorObject() == occupied ? found.get(0) : found.get(1);
                assertEquals("\"string\"", eval(h, KitScripts.INIT_AT_PARSE), "the script ran before the page's");
            } finally {
                chained.detach();
            }
            assertSame(own, occupied.get(FakeInjectJsCallback.class).get(), "the previous occupant is back");
            assertFalse(empty.get(FakeInjectJsCallback.class).isPresent(), "an empty slot is empty again");
            assertEquals(Collections.emptyList(), engine.violations());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void detachRemovesTheAdaptersObserversAndLeavesTheApplicationsOwn() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("observers"), FakeRenderingMode.OFF_SCREEN);
        try {
            FakeBrowser b = browser(engine);
            b.on(FakeConsoleMessageReceived.class, new FakeObserver<FakeConsoleMessageReceived>() {
                @Override
                public void on(FakeConsoleMessageReceived event) {
                    // the application's own observer
                }
            });
            assertEquals(1, b.observerCount());
            JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                adapter.probe(HOST);
                WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
                h.subscribe(new WebEventListener() {
                    @Override
                    public void onEvent(WebBrowser browser, WebEvent event) {
                        // the module's listener
                    }
                });
                h.addInitScript(KitScripts.INIT_SOURCE);
                assertTrue(b.observerCount() > 1, "the adapter observes the browser");
            } finally {
                adapter.detach();
            }
            assertEquals(1, b.observerCount(), "detach removed the adapter's observers; the application's stays");
            assertEquals(Collections.emptyList(), engine.violations());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void detachFailsTheCallsInProgressAndALateVendorCallbackStartsNoThread() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("late"), FakeRenderingMode.OFF_SCREEN);
        CountDownLatch release = new CountDownLatch(1);
        try {
            FakeBrowser b = browser(engine);
            JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            adapter.probe(HOST);
            WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
            assertEquals("42", eval(h, KitScripts.NUMBER));
            engine.holdRenderer(release); // the page is busy: what the adapter asks now waits behind
            int asked = b.asyncEvaluations();
            final CompletableFuture<String> late = h.evaluate(WebEval.of(KitScripts.PROMISE).awaitPromise(true)
                    .build());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (b.asyncEvaluations() == asked && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(b.asyncEvaluations() > asked, "the evaluation reached the page");
            Set<Thread> before = threads("skillroy-web-jxbrowser");
            adapter.detach();
            ExecutionException e = assertThrows(ExecutionException.class, () -> late.get(10, TimeUnit.SECONDS));
            assertTrue(String.valueOf(e.getCause().getMessage()).contains("detached"), String.valueOf(e.getCause()));
            release.countDown(); // the page answers now: the callback reaches the detached adapter
            engine.awaitRenderer();
            EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    // what the renderer posted (the vendor's callback) has been delivered
                }
            });
            assertNoNewThread("skillroy-web-jxbrowser", before);
        } finally {
            release.countDown();
            engine.shutdown();
        }
    }

    @Test
    void aHungEngineKeepsEveryCallerWithinItsBound() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("hung"), FakeRenderingMode.OFF_SCREEN);
        CountDownLatch release = new CountDownLatch(1);
        JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null, JxBrowserAdapter.NO_OPTIONS);
        try {
            FakeBrowser b = browser(engine);
            adapter.probe(HOST);
            final WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
            assertEquals("42", eval(h, KitScripts.NUMBER));
            b.hang(release);
            long started = System.nanoTime();
            ExecutionException e = assertThrows(ExecutionException.class, () -> h.evaluate(WebEval.of(
                    KitScripts.NUMBER).timeoutMs(300).build()).get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof java.util.concurrent.TimeoutException, String.valueOf(e.getCause()));
            assertTrue(ms(started) < 2000, "evaluate is bounded by its timeoutMs: " + ms(started) + " ms");
            started = System.nanoTime();
            assertFalse(h.isClosed(), "unknown means open");
            assertTrue(ms(started) < 4000, "isClosed() is bounded: " + ms(started) + " ms");
            started = System.nanoTime();
            assertTrue(h.frames().isEmpty(), "no frames known");
            assertTrue(ms(started) < 4000, "frames() is bounded: " + ms(started) + " ms");
        } finally {
            release.countDown();
            adapter.detach();
            engine.shutdown();
        }
    }

    /**
     * Gap 47 (JxBrowser kit run against 8.18.3: {@code on(...)} waits while the Chromium process does not answer):
     * with the engine hung, {@code subscribe()} and {@code addInitScript()} return within their bound, and on the EDT
     * at once without calling the vendor there; once the engine answers, the observers registered late deliver; a
     * registration that answers only after {@code detach()} is unsubscribed at once, and the application's own
     * observer stays.
     */
    @Test
    void aHungObserverRegistrationKeepsSubscribeWithinItsBoundAndDetachRemovesWhatAnsweredLate() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("hung-on"), FakeRenderingMode.OFF_SCREEN);
        try {
            final FakeBrowser b = browser(engine);
            b.on(FakeConsoleMessageReceived.class, new FakeObserver<FakeConsoleMessageReceived>() {
                @Override
                public void on(FakeConsoleMessageReceived event) {
                    // the application's own observer
                }
            });
            int application = b.observerCount();
            CountDownLatch release = new CountDownLatch(1);
            JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                adapter.probe(HOST);
                final WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
                b.hang(release);
                final List<WebEvent> seen = new CopyOnWriteArrayList<WebEvent>();
                long started = System.nanoTime();
                h.subscribe(new WebEventListener() {
                    @Override
                    public void onEvent(WebBrowser browser, WebEvent event) {
                        seen.add(event);
                    }
                });
                assertTrue(ms(started) < 4000, "subscribe() is bounded: " + ms(started) + " ms");
                started = System.nanoTime();
                h.addInitScript(KitScripts.INIT_SOURCE);
                assertTrue(ms(started) < 6000, "addInitScript() is bounded (isClosed() and the registration, 2 s "
                        + "each): " + ms(started) + " ms");
                final long[] onEdt = {0};
                EventQueue.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        long t0 = System.nanoTime();
                        h.subscribe(new WebEventListener() {
                            @Override
                            public void onEvent(WebBrowser browser, WebEvent event) {
                                // nothing
                            }
                        });
                        onEdt[0] = ms(t0);
                    }
                });
                assertTrue(onEdt[0] < 100, "subscribe() on the EDT answers at once: " + onEdt[0] + " ms");
                assertEquals(application, b.observerCount(), "nothing registered while the engine hangs");
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (b.observerCount() == application && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertTrue(b.observerCount() > application, "the observers registered once the engine answered");
                b.loadAndWait(PAGE);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!kinds(seen).contains(WebEvent.Kind.LOAD_FINISHED) && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertTrue(kinds(seen).contains(WebEvent.Kind.LOAD_FINISHED), "events after a late registration: "
                        + seen);
            } finally {
                adapter.detach();
            }
            assertEquals(application, b.observerCount(), "detach removed the adapter's observers");

            CountDownLatch late = new CountDownLatch(1);
            JxBrowserAdapter second = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                second.probe(HOST);
                WebBrowser h = second.browsers(Collections.singletonList(b)).get(0);
                b.hang(late);
                h.subscribe(new WebEventListener() {
                    @Override
                    public void onEvent(WebBrowser browser, WebEvent event) {
                        // nothing
                    }
                });
                long started = System.nanoTime();
                second.detach();
                assertTrue(ms(started) < 4000, "detach() is bounded while a registration hangs: " + ms(started)
                        + " ms");
            } finally {
                late.countDown();
                second.detach();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (b.observerCount() != application && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(application, b.observerCount(), "a registration that answered after detach() was removed");
            assertEquals(Collections.emptyList(), engine.violations(), "no vendor call on the EDT");
        } finally {
            engine.shutdown();
        }
    }

    private static Set<WebEvent.Kind> kinds(List<WebEvent> events) {
        Set<WebEvent.Kind> out = new HashSet<WebEvent.Kind>();
        for (WebEvent e : events) {
            out.add(e.kind());
        }
        return out;
    }

    private static long ms(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /** The live threads whose name starts with the prefix, by identity. */
    private static Set<Thread> threads(String prefix) {
        Set<Thread> out = Collections.newSetFromMap(new IdentityHashMap<Thread, Boolean>());
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(prefix)) {
                out.add(t);
            }
        }
        return out;
    }

    /** No thread of the prefix started after {@code before} was taken, and those alive then end (bounded). */
    private static void assertNoNewThread(String prefix, Set<Thread> before) throws InterruptedException {
        List<String> started = new ArrayList<String>();
        for (Thread t : threads(prefix)) {
            if (!before.contains(t)) {
                started.add(t.getName());
            }
        }
        assertEquals(Collections.emptyList(), started, "no thread started after the detach");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Thread t : before) {
            t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            assertFalse(t.isAlive(), t.getName() + " ended with the detach");
        }
    }

    @Test
    void aLineWithoutTheBitmapMemberHasNoBitmapSoWebBitmapIsUnavailable() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("nobitmap"), FakeRenderingMode.OFF_SCREEN);
        try {
            FakeView view = new FakeView(browser(engine));
            JxBrowserAdapter adapter = new JxBrowserAdapter(
                    "ai/skillroy/testjava/agent/web/fake/fake-jxbrowser-nobitmap.json", null,
                    JxBrowserAdapter.NO_OPTIONS);
            try (Harness h = new Harness(adapter).install()) {
                h.walk.components.add(view);
                h.walk.showing.add(view);
                h.ready();
                List<?> listed = (List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers");
                Map<?, ?> entry = (Map<?, ?>) listed.get(0);
                assertEquals("jxbrowser", entry.get("adapter"));
                List<?> flags = new ArrayList<Object>((List<?>) entry.get("capabilities"));
                assertTrue(flags.contains("EVALUATE"), String.valueOf(flags));
                assertFalse(flags.contains("BITMAP"), String.valueOf(flags));
                assertEquals(CapabilityState.UNAVAILABLE, h.cap("web.bitmap").state());
                assertEquals("no indexed browser has BITMAP", h.cap("web.bitmap").reason());
                RpcException e = h.ctx.error("web.bitmap", Harness.args());
                assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, e.error().code());
                assertEquals("42", h.ctx.map("web.evaluate", Harness.args("expression", KitScripts.NUMBER))
                        .get("json"));
                assertNotNull(h.cap("web.evaluate"));
            }
        } finally {
            engine.shutdown();
        }
    }

    /**
     * Phase 7 (4.5): with the descriptor's INPUT roles the handle has INPUT and builds the vendor's events
     * ({@code MousePressed.newBuilder(Point.of(x, y)).button(..).clickCount(..).build()} and the others), handed to
     * {@code Browser.dispatch} off the EDT; modifier keys are refused (not mapped); a line without the roles has no
     * INPUT, so {@code web.pointer} is unavailable for it.
     */
    @Test
    void theInputRolesBuildTheVendorsEventsAndALineWithoutThemHasNoInput() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("input"), FakeRenderingMode.OFF_SCREEN);
        try {
            FakeBrowser b = browser(engine);
            b.loadAndWait(ai.skillroy.testjava.agent.web.kit.KitPages.main());
            JxBrowserAdapter adapter = new JxBrowserAdapter(FakeJxFixture.DESCRIPTOR, null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                assertEquals(CapabilityState.OK, adapter.probe(HOST).state());
                WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
                assertTrue(h.capabilities().contains(WebCapability.INPUT), String.valueOf(h.capabilities()));
                for (WebPointerEvent.Action a : new WebPointerEvent.Action[] {WebPointerEvent.Action.MOVE,
                        WebPointerEvent.Action.PRESS, WebPointerEvent.Action.RELEASE}) {
                    h.dispatchPointer(WebPointerEvent.of(a, 72, 216).build()).get(10, TimeUnit.SECONDS);
                }
                h.dispatchPointer(WebPointerEvent.of(WebPointerEvent.Action.PRESS, 3, 4)
                        .button(WebPointerEvent.Button.RIGHT).clickCount(2).build()).get(10, TimeUnit.SECONDS);
                h.dispatchPointer(WebPointerEvent.of(WebPointerEvent.Action.WHEEL, 3, 4).delta(0, 120).build())
                        .get(10, TimeUnit.SECONDS);
                List<Object> sent = b.dispatched();
                assertEquals(5, sent.size());
                assertTrue(sent.get(0) instanceof FakeMouseMoved && sent.get(1) instanceof FakeMousePressed
                        && sent.get(2) instanceof FakeMouseReleased && sent.get(4) instanceof FakeMouseWheel,
                        String.valueOf(sent));
                List<Map<String, Object>> page = b.document().pointerEvents();
                assertEquals("right", page.get(3).get("button"));
                assertEquals(Long.valueOf(2), page.get(3).get("clickCount"));
                assertEquals(Long.valueOf(120), page.get(4).get("deltaY"), "an int widened to the vendor's float");
                assertEquals("\"pointerdown:true,mousedown:true,mouseup:true,click:true\"",
                        eval(h, KitScripts.POINTER_EVENTS));
                Throwable modifiers = assertThrows(ExecutionException.class, () -> h.dispatchPointer(WebPointerEvent
                        .of(WebPointerEvent.Action.MOVE, 1, 1).modifiers(EnumSet.of(WebPointerEvent.Modifier.ALT))
                        .build()).get(10, TimeUnit.SECONDS)).getCause();
                assertTrue(modifiers instanceof UnsupportedOperationException, String.valueOf(modifiers));
                assertEquals(5, b.dispatched().size(), "nothing dispatched for a refused event");
            } finally {
                adapter.detach();
            }
            JxBrowserAdapter without = new JxBrowserAdapter(
                    "ai/skillroy/testjava/agent/web/fake/fake-jxbrowser-noinput.json", null,
                    JxBrowserAdapter.NO_OPTIONS);
            try {
                assertEquals(ProbeResult.ok("jxbrowser (fake)"), without.probe(HOST), "no INPUT, no note");
                WebBrowser h = without.browsers(Collections.singletonList(b)).get(0);
                assertFalse(h.capabilities().contains(WebCapability.INPUT));
                Throwable refused = assertThrows(ExecutionException.class, () -> h.dispatchPointer(WebPointerEvent
                        .of(WebPointerEvent.Action.MOVE, 1, 1).build()).get(10, TimeUnit.SECONDS)).getCause();
                assertTrue(refused instanceof UnsupportedOperationException, String.valueOf(refused));
            } finally {
                without.detach();
            }
            assertEquals(Collections.emptyList(), engine.violations(), "no vendor call on the EDT");
        } finally {
            engine.shutdown();
        }
    }

    /**
     * A37: the handle reloads through the product's {@code Navigation.reload()} / {@code reloadIgnoringCache()}; a
     * line without those members reloads through the page instead, the SPI's default ({@code location.reload()}
     * through {@code evaluate}), and never calls the product's reload. Either way a load follows, the page is a new
     * document, and the bitmap's source is {@code VENDOR}.
     */
    @Test
    void aLineWithoutTheReloadMembersReloadsThroughThePage() throws Exception {
        FakeEngine engine = FakeEngine.newInstance(null, Paths.get("noreload"), FakeRenderingMode.OFF_SCREEN);
        try {
            FakeBrowser b = browser(engine);
            for (String descriptor : new String[] {FakeJxFixture.DESCRIPTOR, NO_RELOAD}) {
                JxBrowserAdapter adapter = new JxBrowserAdapter(descriptor, null, JxBrowserAdapter.NO_OPTIONS);
                try {
                    assertEquals(CapabilityState.OK, adapter.probe(HOST).state(), descriptor);
                    WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
                    assertSame(BitmapSource.VENDOR, h.bitmapSource());
                    final CountDownLatch loaded = new CountDownLatch(1);
                    h.subscribe(new WebEventListener() {
                        @Override
                        public void onEvent(WebBrowser browser, WebEvent event) {
                            if (event.kind() == WebEvent.Kind.LOAD_FINISHED && event.frameKey() == null) {
                                loaded.countDown();
                            }
                        }
                    });
                    eval(h, KitScripts.DOCUMENT_MARK);
                    int productReloads = b.navigation().reloads();
                    h.reload(true).get(10, TimeUnit.SECONDS);
                    assertTrue(loaded.await(10, TimeUnit.SECONDS), descriptor + ": a load followed the reload");
                    assertEquals("\"undefined\"", eval(h, KitScripts.DOCUMENT_MARK_NOW), "a new document");
                    assertEquals(descriptor.equals(NO_RELOAD) ? productReloads : productReloads + 1,
                            b.navigation().reloads(), descriptor + ": the product's reload");
                } finally {
                    adapter.detach();
                }
            }
            assertEquals(Collections.emptyList(), engine.violations());
        } finally {
            engine.shutdown();
        }
    }
}
