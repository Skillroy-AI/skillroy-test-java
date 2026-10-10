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

package ai.skillroy.testjava.cdp;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CdpAttach} with fake factories and fake Playwright objects (no driver, no browser): the frozen-target
 * refusal, the external deadline that closes a hung connect, failure kinds, page mapping, close and tracing.
 */
class CdpAttachTest {

    private static final CdpEndpoint ENDPOINT = CdpEndpoint.local(9222);

    /** The browser WebSocket the probes of these tests read (at the endpoint's own authority). */
    private static final String BROWSER_WS = "ws://127.0.0.1:9222/devtools/browser/0b1c-2d3e";

    private static TargetProbe probe(TargetProbe.Target... targets) {
        return new TargetProbe(ENDPOINT, "FakeChrome/152", "1.3", BROWSER_WS, List.of(targets), 5);
    }

    private static TargetProbe.Target page(String id, boolean responsive) {
        return new TargetProbe.Target(id, "page", "data:,p" + id, "p" + id, "ws://x/devtools/page/" + id,
                responsive, responsive ? 3 : 2000, responsive ? null : "did not answer within 2000 ms");
    }

    /** A fake Playwright whose chromium().connectOverCDP runs {@code connect}; close() counts and runs onClose. */
    private static Playwright playwright(Fakes.Answer connect, AtomicInteger closed, Runnable onClose) {
        Map<String, Fakes.Answer> bt = new HashMap<>();
        bt.put("connectOverCDP(String,ConnectOverCDPOptions)", connect);
        BrowserType chromium = Fakes.fake(BrowserType.class, bt);
        Map<String, Fakes.Answer> pw = new HashMap<>();
        pw.put("chromium()", a -> chromium);
        pw.put("close()", a -> {
            closed.incrementAndGet();
            onClose.run();
            return null;
        });
        return Fakes.fake(Playwright.class, pw);
    }

    @Test
    void aProbeWithAFrozenTargetIsRefusedBeforeAnythingStarts() {
        TargetProbe probe = probe(page("A", true), page("B", false));
        CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(ENDPOINT, CdpOptions.defaults(),
                probe, env -> {
                    throw new AssertionError("nothing may start");
                }));
        assertEquals(CdpException.Kind.FROZEN_TARGETS, e.kind());
        assertEquals(List.of("B"), e.targets().stream().map(TargetProbe.Target::id).toList());
        assertEquals("page targets did not answer Runtime.evaluate: B data:,pB", e.getMessage());
        for (var policy : ai.skillroy.testjava.scenario.web.FrozenTargetPolicy.values()) {
            assertEquals(CdpException.Kind.FROZEN_TARGETS, assertThrows(CdpException.class,
                    () -> CdpAttach.connect(ENDPOINT, CdpOptions.defaults().withFrozenTargets(policy), probe,
                            env -> null)).kind(), "whatever the policy: " + policy);
        }
    }

    @Test
    void connectRunsOnThePlaywrightThreadWithTheOptionsAndTheDriverEnvironment(@TempDir Path artifacts)
            throws Exception {
        FakeWorld world = new FakeWorld();
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<Map<String, String>> env = new AtomicReference<>();
        AtomicReference<Object[]> connectArgs = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        CdpOptions options = CdpOptions.defaults().withConnectDeadline(Duration.ofSeconds(9))
                .withArtifactsDir(artifacts);
        TargetProbe probe = probe(page("A", true));
        CdpAttach attach = CdpAttach.connect(ENDPOINT, options, probe, e -> {
            thread.set(Thread.currentThread());
            env.set(e);
            return playwright(a -> {
                connectArgs.set(a);
                return world.browser;
            }, closed, () -> { });
        });
        try {
            assertEquals("skillroy-cdp-playwright", thread.get().getName());
            assertFalse(thread.get().isVirtual(), "a platform worker thread");
            assertEquals("1", env.get().get("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
            assertEquals(BROWSER_WS, connectArgs.get()[0], "P2-L4-F1: the browser WebSocket the probe read, never the"
                    + " http URL from which the driver would read /json/version itself");
            BrowserType.ConnectOverCDPOptions o = (BrowserType.ConnectOverCDPOptions) connectArgs.get()[1];
            assertTrue(o.timeout > 0 && o.timeout <= 9000, "Playwright's own timeout within the deadline: "
                    + o.timeout);
            assertEquals(Boolean.TRUE, o.noDefaults);
            assertEquals(Boolean.TRUE, o.isLocal);
            assertEquals(artifacts, o.artifactsDir);
            assertSame(world.browser, attach.browser());
            assertSame(world.context, attach.context());
            assertSame(probe, attach.probe());
            assertTrue(attach.connectMillis() >= 0);
        } finally {
            attach.close();
        }
        assertEquals(1, closed.get());
    }

    /**
     * Security fix P2-L4-F1: a probe that found no usable browser WebSocket (another host's answer re-pointed is
     * usable; an opaque or foreign path is not) is refused with {@code PROTOCOL} before anything starts; without a
     * probe the driver is given the endpoint's http URL (tests and the soak only).
     */
    @Test
    void aProbeWithoutABrowserWebSocketIsRefusedBeforeAnythingStarts() throws Exception {
        TargetProbe none = new TargetProbe(ENDPOINT, "FakeChrome/152", "1.3", null, List.of(page("A", true)), 5);
        CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(ENDPOINT, CdpOptions.defaults(),
                none, env -> {
                    throw new AssertionError("nothing may start");
                }));
        assertEquals(CdpException.Kind.PROTOCOL, e.kind());
        assertTrue(e.getMessage().contains("names no browser WebSocket"), e.getMessage());
        FakeWorld world = new FakeWorld();
        AtomicReference<Object[]> connectArgs = new AtomicReference<>();
        CdpAttach attach = CdpAttach.connect(ENDPOINT, CdpOptions.defaults(), null, env -> playwright(a -> {
            connectArgs.set(a);
            return world.browser;
        }, new AtomicInteger(), () -> { }));
        try {
            assertEquals("http://127.0.0.1:9222", connectArgs.get()[0], "no probe: the endpoint's http URL");
        } finally {
            attach.close();
        }
    }

    @Test
    void aHungConnectEndsWithTimeoutWithinTheDeadlineAndTheDriverIsClosed() throws Exception {
        CountDownLatch closedLatch = new CountDownLatch(1);
        CountDownLatch connectReturned = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        long start = System.nanoTime();
        CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(ENDPOINT,
                CdpOptions.defaults().withConnectDeadline(Duration.ofMillis(300)), probe(page("A", true)),
                env -> playwright(a -> {
                    try {
                        closedLatch.await();
                        throw new PlaywrightException("Playwright connection closed");
                    } finally {
                        connectReturned.countDown();
                    }
                }, closed, closedLatch::countDown)));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(CdpException.Kind.TIMEOUT, e.kind());
        assertEquals("connectOverCDP to 127.0.0.1:9222 did not complete within 300 ms; the Playwright driver was"
                + " closed", e.getMessage());
        assertTrue(ms >= 300 && ms < 300 + 2000, "ends at the deadline: " + ms + " ms");
        assertEquals(1, closed.get(), "closing Playwright ends the driver");
        assertTrue(connectReturned.await(5, TimeUnit.SECONDS), "and with it the hung connect");
    }

    @Test
    void aDriverThatStartsAfterTheDeadlineIsClosedAsSoonAsItExists() throws Exception {
        CountDownLatch slowStart = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(ENDPOINT,
                CdpOptions.defaults().withConnectDeadline(Duration.ofMillis(200)), null, env -> {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (slowStart.getCount() > 0 && System.nanoTime() < until) {
                        try {
                            slowStart.await(10, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException x) {
                            // an extraction that does not stop when interrupted
                        }
                    }
                    return playwright(a -> {
                        connects.incrementAndGet();
                        return null;
                    }, closed, closedLatch::countDown);
                }));
        assertEquals(CdpException.Kind.TIMEOUT, e.kind());
        slowStart.countDown();
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "the late Playwright is closed");
        assertEquals(0, connects.get(), "and never connects");
    }

    @Test
    void connectFailuresAreClassifiedAndPlaywrightIsClosed() {
        Map<RuntimeException, CdpException.Kind> cases = Map.of(
                new PlaywrightException("connect ECONNREFUSED 127.0.0.1:9222\nCall log:\n- something"),
                CdpException.Kind.UNREACHABLE,
                new TimeoutError("Timeout 29000ms exceeded."), CdpException.Kind.TIMEOUT,
                new PlaywrightException("Protocol error (Browser.getVersion): not supported"),
                CdpException.Kind.PROTOCOL);
        for (Map.Entry<RuntimeException, CdpException.Kind> c : cases.entrySet()) {
            AtomicInteger closed = new AtomicInteger();
            CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(ENDPOINT,
                    CdpOptions.defaults(), null, env -> playwright(a -> {
                        throw c.getKey();
                    }, closed, () -> { })));
            assertEquals(c.getValue(), e.kind(), c.getKey().getMessage());
            assertFalse(e.getMessage().contains("Call log"), "the first line only: " + e.getMessage());
            assertEquals(1, closed.get(), c.getKey().getMessage());
        }
        Map<String, Fakes.Answer> br = new HashMap<>();
        br.put("contexts()", a -> List.of());
        var noContexts = Fakes.fake(com.microsoft.playwright.Browser.class, br);
        CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(ENDPOINT, CdpOptions.defaults(),
                null, env -> playwright(a -> noContexts, new AtomicInteger(), () -> { })));
        assertEquals(CdpException.Kind.PROTOCOL, e.kind());
        assertEquals("connectOverCDP to 127.0.0.1:9222 failed: the browser at 127.0.0.1:9222 has no browser"
                + " context to attach to", e.getMessage());
    }

    @Test
    void theDefaultFactoryIsForbiddenInThisModulesUnitTests() {
        assertThrows(PlaywrightFactory.Forbidden.class, () -> CdpAttach.connect(ENDPOINT, CdpOptions.defaults(),
                null));
    }

    @Test
    void pagesAreMappedByTargetIdAndTheMappingFollowsThePageList() {
        FakeWorld world = new FakeWorld();
        FakeWorld.FakePage one = world.page("T1", "data:,one", "One");
        FakeWorld.FakePage two = world.page("T2", "data:,two", "Two");
        CdpAttach attach = world.attach(null, CdpOptions.defaults());
        try {
            Map<String, Page> pages = attach.pagesByTargetId();
            assertEquals(List.of("T1", "T2"), List.copyOf(pages.keySet()));
            assertSame(two.page, attach.pageFor("T2"));
            assertNull(attach.pageFor("T9"));
            assertNull(attach.pageFor(null));
            assertEquals(2, world.sessions.get(), "one CDP session per page, cached");
            one.closed = true;
            world.page("T3", "data:,three", "Three");
            assertEquals(List.of("T2", "T3"), List.copyOf(attach.pagesByTargetId().keySet()));
            assertEquals(3, world.sessions.get(), "only the new page is asked");
        } finally {
            attach.close();
        }
    }

    @Test
    void closeIsIdempotentClosesPlaywrightAndNeverTheBrowser() {
        FakeWorld world = new FakeWorld();
        world.page("T1", "data:,one", "One");
        CdpAttach attach = world.attach(null, CdpOptions.defaults());
        attach.close();
        attach.close();
        assertTrue(attach.isClosed());
        assertEquals(1, world.playwrightClosed.get());
        assertEquals(0, world.browserClosed.get());
        assertTrue(attach.pagesByTargetId().isEmpty());
        assertTrue(attach.busy());
        assertThrows(PwThread.Busy.class, () -> attach.run(() -> 1, 100));
    }

    @Test
    void tracingWritesIntoTheArtifactsDirectory(@TempDir Path artifacts) throws Exception {
        FakeWorld world = new FakeWorld();
        CdpAttach attach = world.attach(null, CdpOptions.defaults().withArtifactsDir(artifacts));
        try {
            CdpTracing tracing = attach.tracing();
            assertFalse(tracing.isActive());
            assertNull(tracing.stop(Path.of("never.zip")), "nothing to stop");
            tracing.start("run-1");
            assertTrue(tracing.isActive());
            assertEquals("run-1", world.traceStarts.get(0).name);
            assertEquals(Boolean.TRUE, world.traceStarts.get(0).screenshots);
            assertEquals(Boolean.TRUE, world.traceStarts.get(0).snapshots);
            Path written = tracing.stop(Path.of("failure", "web-trace.zip"));
            assertEquals(artifacts.resolve("failure").resolve("web-trace.zip"), written);
            assertTrue(Files.exists(written));
            assertFalse(tracing.isActive());
            tracing.start(null);
            assertNull(tracing.stop(null), "discarded");
            assertNull(world.traceStops.get(1).path);
        } finally {
            attach.close();
        }
    }
}
