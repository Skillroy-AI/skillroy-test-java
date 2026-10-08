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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.agent.web.jcef.JcefAdapter;
import ai.skillroy.testjava.agent.web.kit.KitScripts;
import ai.skillroy.testjava.protocol.CapabilityState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.EventQueue;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code jcef} adapter's own rules against the fake JCEF shapes (phase4-contracts.md section 4.5), beyond the
 * contract kit: the process-wide walk only in state {@code INITIALIZED} and never through {@code getInstance()}; seeds
 * only otherwise, or when a private member is missing; UNAVAILABLE without the in-process channel; a browser without
 * a native browser is listed without capabilities until it exists; a DevTools client the application closed is
 * replaced with the adapter's listener and scripts put back; the re-send rule (only a read the client refused before
 * sending it, once, while its caller waits; never a call that reached the page, never an evaluation); a closed
 * browser is reported once; the target id stays known while the page is frozen; the endpoints (a stale
 * {@code DevToolsActivePort} ignored); rendering by class and the windowed-on-Java-8 mark; detach removes what the
 * adapter added, also an init script the page adds only afterwards, closes nothing, fails the calls in progress and
 * leaves no thread, also when a late answer arrives.
 */
class JcefAdapterFakeTest {

    private static final String PAGE = "<!doctype html><html><head><title>Loan desk</title></head>"
            + "<body><p id=\"status\">ready</p></body></html>";
    private static final String NEXT = "<!doctype html><html><head><title>Returns</title></head><body></body></html>";
    private static final ClassLoader HOST = JcefAdapterFakeTest.class.getClassLoader();
    /** What Chromium answers a call in flight when the page navigates, as JCEF 152's client reports it. */
    static final String NAVIGATED = "DevTools method failed: {\"code\":-32000,\"message\":"
            + "\"Inspected target navigated or closed\"}";

    private FakeCefSettings settings;
    private FakeCefApp app;
    private FakeCefClient client;
    private JcefAdapter adapter;

    @BeforeEach
    void startTheApplication() {
        settings = new FakeCefSettings();
        app = FakeCefApp.startup(settings);
        client = app.createClient();
        adapter = new JcefAdapter(FakeCefFixture.DESCRIPTOR, null);
    }

    @AfterEach
    void stopIt() {
        adapter.detach();
        FakeCefApp.dispose();
        assertEquals(Collections.emptyList(), FakeCefApp.violations(), "getInstance() is never called");
        assertEquals(Collections.emptyList(), FakeCefBrowserBase.violations(), "no client is closed by the adapter");
    }

    private FakeCefBrowserBase shown(FakeCefClient c, boolean osr) throws Exception {
        FakeCefBrowserBase b = (FakeCefBrowserBase) c.createBrowser("about:blank", osr);
        b.show(640, 480);
        b.loadHtml(PAGE).get(10, TimeUnit.SECONDS);
        return b;
    }

    private static WebBrowser handleOf(List<WebBrowser> found, Object vendor) {
        for (WebBrowser b : found) {
            if (b.vendorObject() == vendor) {
                return b;
            }
        }
        throw new AssertionError("not listed: " + vendor);
    }

    private static String eval(WebBrowser b, String expression) throws Exception {
        return b.evaluate(WebEval.of(expression).build()).get(10, TimeUnit.SECONDS);
    }

    private static BlockingQueue<WebEvent> subscribe(WebBrowser b) {
        final BlockingQueue<WebEvent> events = new LinkedBlockingQueue<WebEvent>();
        b.subscribe(new WebEventListener() {
            @Override
            public void onEvent(WebBrowser browser, WebEvent event) {
                events.add(event);
            }
        });
        return events;
    }

    private static WebEvent next(BlockingQueue<WebEvent> events, WebEvent.Kind kind) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            WebEvent e = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (e != null && e.kind() == kind) {
                return e;
            }
        }
        throw new AssertionError("no " + kind + " event");
    }

    @Test
    void theWalkFindsTheBrowsersOfEveryClientInStateInitialized() throws Exception {
        FakeCefBrowserBase offscreen = shown(client, true);
        FakeCefBrowserBase windowed = shown(app.createClient(), false);
        ProbeResult probe = adapter.probe(HOST);
        assertEquals(CapabilityState.OK, probe.state(), String.valueOf(probe));
        assertEquals("jcef 152", probe.strategy(), "the Chromium major of the running instance");
        List<WebBrowser> found = adapter.browsers(Collections.emptyList());
        assertEquals(2, found.size(), "found without a seed");
        assertEquals(CapabilityState.OK, adapter.enumerationState().state());
        WebBrowser a = handleOf(found, offscreen);
        WebBrowser b = handleOf(found, windowed);
        assertEquals(WebRendering.OFFSCREEN_HEAVYWEIGHT, a.rendering());
        assertEquals(WebRendering.WINDOWED, b.rendering());
        assertEquals(String.valueOf(offscreen.getIdentifier()), a.nativeId());
        assertTrue(a.capabilities().contains(WebCapability.CDP_TARGET_ID));
        assertNull(a.component(), "a component that is in no window is not reported");
        assertEquals("42", eval(a, KitScripts.NUMBER));
        assertSame(a, handleOf(adapter.browsers(Collections.emptyList()), offscreen), "one handle per browser");
    }

    @Test
    void anotherStateLeavesTheSeedsOnly() throws Exception {
        FakeCefBrowserBase seeded = shown(client, true);
        FakeCefBrowserBase other = shown(app.createClient(), true);
        FakeCefApp.forceState(FakeCefAppState.INITIALIZING);
        ProbeResult probe = adapter.probe(HOST);
        assertEquals(CapabilityState.OK, probe.state());
        assertEquals("jcef", probe.strategy(), "no version is read from an instance that is not initialised");
        List<WebBrowser> found = adapter.browsers(Collections.singletonList(seeded));
        assertEquals(1, found.size());
        assertSame(seeded, found.get(0).vendorObject());
        assertEquals(ProbeResult.degraded("seeds only: CefApp is INITIALIZING"), adapter.enumerationState());
        assertEquals(1, adapter.browsers(Collections.singletonList(client)).size(), "a client seed lists its own");
        assertEquals(2, adapter.browsers(Collections.singletonList(app)).size(), "an application seed lists all");
        assertTrue(adapter.browsers(Collections.emptyList()).isEmpty());
        assertNotNull(other);
    }

    @Test
    void aComponentSeedKeepsAKnownBrowserListedOnlyWhileSeedsAreAllThereIs() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        FakeCefApp.forceState(FakeCefAppState.INITIALIZING);
        adapter.probe(HOST);
        WebBrowser h = adapter.browsers(Collections.singletonList(b)).get(0);
        assertTrue(adapter.browsers(Collections.singletonList(new JPanel())).isEmpty(), "a component finds no browser");
        assertSame(h, adapter.browsers(Collections.singletonList(b.getUIComponent())).get(0),
                "the component of a known browser keeps it listed");
        FakeCefApp.forceState(FakeCefAppState.INITIALIZED);
        b.close(true);
        assertTrue(adapter.browsers(Collections.singletonList(b.getUIComponent())).isEmpty(),
                "after a walk the process-wide list decides");
        assertTrue(h.isClosed());
    }

    @Test
    void aForkWithoutTheWalkIsSeedsOnlyAndAnOldJcefIsUnavailable() throws Exception {
        FakeCefBrowserBase seeded = shown(client, true);
        JcefAdapter fork = new JcefAdapter("ai/skillroy/testjava/agent/web/fake/fake-jcef-nowalk.json", null);
        try {
            assertEquals(CapabilityState.OK, fork.probe(HOST).state());
            assertEquals(1, fork.browsers(Arrays.asList(seeded, app)).size());
            ProbeResult state = fork.enumerationState();
            assertEquals(CapabilityState.DEGRADED, state.state());
            assertTrue(state.reason().startsWith("seeds only: ") && state.reason().contains("clientsOfAFork"),
                    state.reason());
            assertTrue(fork.enumerationNotes().get(0).startsWith("CefApp seed skipped: "),
                    String.valueOf(fork.enumerationNotes()));
        } finally {
            fork.detach();
        }
        JcefAdapter old = new JcefAdapter("ai/skillroy/testjava/agent/web/fake/fake-jcef-old.json", null);
        try {
            assertEquals(ProbeResult.unavailable(JcefAdapter.OLD_JCEF), old.probe(HOST));
            assertTrue(old.enumerationNotes().get(0).contains("getDevToolsClientOfANewerJcef"));
            assertTrue(old.browsers(Collections.singletonList(seeded)).isEmpty());
        } finally {
            old.detach();
        }
    }

    @Test
    void aBrowserIsListedWithoutCapabilitiesUntilItsNativeBrowserExists() throws Exception {
        FakeCefBrowserBase later = (FakeCefBrowserBase) client.createBrowser("about:blank", true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), later);
        assertEquals(Collections.emptySet(), h.capabilities());
        assertNull(h.nativeId());
        assertFalse(h.isClosed(), "not created yet is not closed");
        ExecutionException refused = assertThrows(ExecutionException.class, () -> eval(h, KitScripts.NUMBER));
        assertTrue(refused.getCause() instanceof UnsupportedOperationException, String.valueOf(refused.getCause()));
        later.show(320, 240);
        later.loadHtml(PAGE).get(10, TimeUnit.SECONDS);
        assertSame(h, handleOf(adapter.browsers(Collections.emptyList()), later));
        assertTrue(h.capabilities().contains(WebCapability.EVALUATE));
        assertEquals("42", eval(h, KitScripts.NUMBER));
    }

    @Test
    void aClientTheApplicationClosedIsReplacedWithTheListenerAndScriptsPutBack() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        BlockingQueue<WebEvent> events = subscribe(h);
        h.addInitScript(KitScripts.INIT_SOURCE);
        FakeDevToolsClient first = b.getDevToolsClient();
        assertEquals(1, first.listenerCount());
        b.closeClientByApplication();
        assertEquals("42", eval(h, KitScripts.NUMBER), "the next call goes to a fresh client");
        FakeDevToolsClient second = b.getDevToolsClient();
        assertNotSame(first, second);
        assertEquals(1, second.listenerCount(), "the listener is back");
        assertEquals(Collections.singletonList(KitScripts.INIT_SOURCE), second.scriptSources(), "so is the script");
        b.loadHtml(NEXT).get(10, TimeUnit.SECONDS);
        assertNull(next(events, WebEvent.Kind.LOAD_FINISHED).frameKey(), "events arrive from the fresh client");
        assertEquals("\"string\"", eval(h, KitScripts.INIT_NOW), "the init script ran in the new document");
    }

    @Test
    void aClientThatRefusesItsFirstCallsIsReplacedBeforeTheCallIsSent() throws Exception {
        FakeCefBrowserBase hidden = (FakeCefBrowserBase) client.createBrowser("about:blank", true);
        hidden.createImmediately();
        hidden.loadHtml(PAGE).get(10, TimeUnit.SECONDS);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), hidden);
        int before = hidden.evaluations();
        assertEquals("\"Loan desk\"", eval(h, KitScripts.TITLE), "answered by the second client");
        assertEquals(1, hidden.evaluations() - before, "sent once, to the open client");
        assertFalse(hidden.getDevToolsClient().isClosed());
    }

    @Test
    void aReadTheClientRefusedIsSentOnceOnAFreshClientAndAnEvaluationIsNot() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        assertEquals("42", eval(h, KitScripts.NUMBER));
        FakeDevToolsClient first = b.getDevToolsClient();
        first.refuseNextCall();
        assertFalse(h.frames().isEmpty(), "Page.getFrameTree, refused by a client that closed, answered on a new one");
        FakeDevToolsClient second = b.getDevToolsClient();
        assertNotSame(first, second);
        second.refuseNextCall();
        int before = b.evaluations();
        ExecutionException refused = assertThrows(ExecutionException.class, () -> eval(h, KitScripts.NUMBER));
        assertTrue(String.valueOf(refused.getCause().getMessage()).contains("Client is closed"),
                String.valueOf(refused.getCause()));
        assertEquals(before, b.evaluations(), "a refused evaluation is not sent again by the adapter");
        assertEquals("42", eval(h, KitScripts.NUMBER), "the next call goes to a new client");
    }

    @Test
    void aCallThatReachedThePageIsNeverSentAgainWhateverItsAnswerSays() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        BlockingQueue<WebEvent> events = subscribe(h);
        h.addInitScript(KitScripts.INIT_SOURCE);
        assertEquals("42", eval(h, KitScripts.NUMBER));
        FakeDevToolsClient c = b.getDevToolsClient();
        assertEquals(1, c.scriptSources().size());
        assertEquals(1, c.listenerCount());
        int before = b.evaluations();
        b.answerNextEvaluationWithFailure(NAVIGATED);
        ExecutionException e = assertThrows(ExecutionException.class, () -> h.evaluate(WebEval.of(KitScripts.NUMBER)
                .timeoutMs(5000).build()).get(10, TimeUnit.SECONDS));
        assertTrue(String.valueOf(e.getCause().getMessage()).contains("Inspected target navigated or closed"),
                String.valueOf(e.getCause()));
        assertEquals(1, b.evaluations() - before, "run once in the page, not sent again");
        assertSame(c, b.getDevToolsClient());
        assertEquals(1, c.scriptSources().size(), "the client was not attached again: one registration of the script");
        assertEquals(1, c.listenerCount());
        adapter.detach();
        assertEquals(Collections.emptyList(), c.scriptSources(), "detach removed it");
        assertNotNull(events);
    }

    @Test
    void aCallItsCallerGaveUpOnIsNotSentAgainWhenItsLateAnswerSaysNavigatedOrClosed() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        assertEquals("42", eval(h, KitScripts.NUMBER));
        int before = b.evaluations();
        b.holdNextEvaluation();
        final CompletableFuture<String> f = h.evaluate(WebEval.of(KitScripts.NUMBER).timeoutMs(300).build());
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof TimeoutException, String.valueOf(e.getCause()));
        f.cancel(false); // as the module's bounded wait does
        b.failHeld(NAVIGATED);
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                // the late answer has been delivered
            }
        });
        assertEquals("42", eval(h, KitScripts.NUMBER));
        assertEquals(2, b.evaluations() - before, "the held call and the next one only");
    }

    @Test
    void anInitScriptThePageAddsOnlyAfterDetachIsRemovedThen() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        assertEquals("42", eval(h, KitScripts.NUMBER));
        FakeDevToolsClient c = b.getDevToolsClient();
        b.freeze(true);
        h.addInitScript(KitScripts.INIT_SOURCE);
        adapter.detach(); // gives the frozen page its bound, then leaves the removal to follow the addition
        b.freeze(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (b.scriptRemovals() < 1 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, b.scriptAdditions(), "the page added the script after the detach");
        assertEquals(1, b.scriptRemovals());
        assertEquals(Collections.emptyList(), c.scriptSources(), "and its removal followed");
        assertFalse(c.isClosed());
    }

    @Test
    void anAdditionTheClientRefusedNeitherStopsTheDetachNorIsSentAgain() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        subscribe(h);
        assertEquals("42", eval(h, KitScripts.NUMBER));
        FakeDevToolsClient first = b.getDevToolsClient();
        first.refuseNextCall();
        h.addInitScript(KitScripts.INIT_SOURCE); // refused: the client closed under it
        assertEquals("42", eval(h, KitScripts.NUMBER), "the next call goes to a new client");
        FakeDevToolsClient second = b.getDevToolsClient();
        assertNotSame(first, second);
        assertEquals(Collections.singletonList(KitScripts.INIT_SOURCE), second.scriptSources(),
                "the listed script was added to the new client, once");
        assertEquals(1, b.scriptAdditions());
        adapter.detach();
        assertEquals(Collections.emptyList(), second.scriptSources(), "removed from the client it was added to");
        assertEquals(0, second.listenerCount(), "the detach went on past the refused addition");
    }

    @Test
    void detachFailsTheCallsInProgressAndALateAnswerStartsNoThread() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        assertEquals("42", eval(h, KitScripts.NUMBER));
        b.freeze(true);
        final CompletableFuture<String> late = h.evaluate(WebEval.of(KitScripts.NUMBER).build());
        Set<Thread> before = threads("skillroy-web-jcef");
        adapter.detach();
        ExecutionException e = assertThrows(ExecutionException.class, () -> late.get(10, TimeUnit.SECONDS));
        assertTrue(String.valueOf(e.getCause().getMessage()).contains("detached"), String.valueOf(e.getCause()));
        int evaluations = b.evaluations();
        b.freeze(false); // the page answers now, after the detach
        awaitRenderer();
        assertTrue(b.evaluations() > evaluations, "the page ran the call");
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                // the late answer, posted by the renderer before, has been delivered to the detached adapter
            }
        });
        assertNoNewThread("skillroy-web-jcef", before);
    }

    @Test
    void subscribeReturnsOnceThePageEnabledItsDomains() throws Exception {
        final FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        final WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        b.freeze(true); // the page does not answer Page.enable yet
        final long[] returned = new long[1];
        Thread subscriber = new Thread(new Runnable() {
            @Override
            public void run() {
                subscribe(h);
                returned[0] = System.nanoTime();
            }
        }, "subscriber");
        subscriber.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (b.queuedCalls() < 2 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(b.queuedCalls() >= 2, "Page.enable and Runtime.enable were sent");
        long thawed = System.nanoTime();
        b.freeze(false);
        subscriber.join(10000);
        assertFalse(subscriber.isAlive());
        assertTrue(returned[0] >= thawed, "subscribe() waited for the page's answer to Page.enable");
    }

    @Test
    void theWindowedOnJava8MarkFollowsTheRenderingModeAndTheTargetsJava() throws Exception {
        settings.windowless_rendering_enabled = false;
        shown(client, false);
        ProbeResult probe = adapter.probe(HOST);
        boolean java8 = System.getProperty("java.specification.version", "").startsWith("1.");
        assertEquals(java8 ? "jcef 152, windowed, Java 8: unverified" : "jcef 152", probe.strategy());
        settings.windowless_rendering_enabled = true;
        assertEquals("jcef 152", adapter.probe(HOST).strategy(), "off screen is not marked");
    }

    /** Waits (at most 10 s) until the fake's renderer has run everything asked of it before. */
    private void awaitRenderer() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        app.renderer.execute(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the renderer ran what was asked before");
    }

    /** The live threads whose name starts with the prefix, by identity. */
    static Set<Thread> threads(String prefix) {
        Set<Thread> out = Collections.newSetFromMap(new IdentityHashMap<Thread, Boolean>());
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(prefix)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * No thread of the prefix started after {@code before} was taken, and those alive then end (an adapter's
     * threads after its detach); waits for their end at most 10 s.
     */
    static void assertNoNewThread(String prefix, Set<Thread> before) throws InterruptedException {
        Set<Thread> now = threads(prefix);
        List<String> started = new ArrayList<String>();
        for (Thread t : now) {
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
    void aClosedBrowserIsSeenAndReportedOnce() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        BlockingQueue<WebEvent> events = subscribe(h);
        b.close(true);
        assertTrue(adapter.browsers(Collections.emptyList()).isEmpty());
        assertTrue(h.isClosed());
        assertEquals(Collections.emptySet(), h.capabilities());
        next(events, WebEvent.Kind.CLOSED);
        adapter.browsers(Collections.emptyList());
        assertTrue(h.isClosed());
        assertNull(events.poll(300, TimeUnit.MILLISECONDS), "CLOSED is delivered once");
        ExecutionException e = assertThrows(ExecutionException.class, () -> eval(h, KitScripts.NUMBER));
        assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
    }

    @Test
    void theTargetIdStaysKnownWhileThePageIsFrozen() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        String id = h.cdpTargetId().get(10, TimeUnit.SECONDS);
        assertNotNull(id);
        b.freeze(true);
        try {
            assertEquals(id, h.cdpTargetId().get(1, TimeUnit.SECONDS), "no protocol call is needed");
            long started = System.nanoTime();
            ExecutionException e = assertThrows(ExecutionException.class, () -> h.evaluate(
                    WebEval.of(KitScripts.NUMBER).timeoutMs(300).build()).get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof TimeoutException, String.valueOf(e.getCause()));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000);
        } finally {
            b.freeze(false);
        }
    }

    @Test
    void theEndpointComesFromTheSettingsOrTheActivePortFile(@TempDir Path dir) throws Exception {
        shown(client, true);
        adapter.probe(HOST);
        assertTrue(adapter.cdpEndpoints(Collections.emptyList()).isEmpty(), "nothing configured");
        settings.remote_debugging_port = 9333;
        assertEquals(Collections.singletonList(new CdpEndpoint("127.0.0.1", 9333, "jcef")),
                adapter.cdpEndpoints(Collections.emptyList()));
        settings.remote_debugging_port = 0;
        File root = dir.resolve("root").toFile();
        assertTrue(root.mkdirs());
        Files.write(new File(root, "DevToolsActivePort").toPath(), "9444\n/devtools/browser/fake\n"
                .getBytes(StandardCharsets.UTF_8));
        settings.root_cache_path = root.getPath();
        assertEquals(Collections.singletonList(new CdpEndpoint("127.0.0.1", 9444, "jcef")),
                adapter.cdpEndpoints(Collections.emptyList()));
        File written = new File(root, "DevToolsActivePort");
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        assertTrue(written.setLastModified(jvmStart - 60000));
        assertTrue(adapter.cdpEndpoints(Collections.emptyList()).isEmpty(),
                "a file older than this JVM is a previous run's: its port is not reported");
        assertTrue(written.setLastModified(System.currentTimeMillis()));
        settings.root_cache_path = dir.resolve("empty").toString();
        File cache = dir.resolve("cache").toFile();
        assertTrue(cache.mkdirs());
        Files.write(new File(cache, "DevToolsActivePort").toPath(), "9555\n".getBytes(StandardCharsets.UTF_8));
        settings.cache_path = cache.getPath();
        assertEquals(9555, adapter.cdpEndpoints(Collections.emptyList()).get(0).port());
    }

    @Test
    void detachRemovesWhatTheAdapterAddedAndClosesNothing() throws Exception {
        FakeCefBrowserBase b = shown(client, true);
        adapter.probe(HOST);
        WebBrowser h = handleOf(adapter.browsers(Collections.emptyList()), b);
        subscribe(h);
        h.addInitScript(KitScripts.INIT_SOURCE);
        h.resize(500, 400).get(10, TimeUnit.SECONDS);
        FakeDevToolsClient c = b.getDevToolsClient();
        assertEquals(1, c.listenerCount());
        assertEquals(1, c.scriptSources().size());
        assertTrue(Arrays.equals(new int[] {500, 400}, c.override));
        adapter.detach();
        assertEquals(0, c.listenerCount());
        assertEquals(Collections.emptyList(), c.scriptSources());
        assertNull(c.override, "the override is cleared");
        assertFalse(c.isClosed(), "the client is the application's");
    }
}
