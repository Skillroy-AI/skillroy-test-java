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

import ai.skillroy.testjava.agent.api.spi.CaptureMask;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event bridge of phase4-contracts.md section 4.3 and the tap of section 4.2: the six topics with their payloads
 * and source {@code web}; {@code web.ready} for the three signals (D57); {@code web.console} capped at 2000
 * characters with secrets hidden (A34: the values named in {@code _meta.secretParams} of a web call, and the key
 * rule); the tap's init script, its immediate installation, the poller that drains the page's buffer every 100 ms on
 * a worker and publishes {@code web.tap} and {@code web.ready {signal: TAP}}, re-installation and removal (the poller
 * stops); a page that stays busy after firing (the drain stays outstanding, no other is sent, and its events are
 * published when it answers); a browser that leaves the index has its tap cleared in its document;
 * {@code web.waitFlag}, which keeps waiting for a probe the page has not answered instead of sending another.
 */
class EventsAndTapTest {

    private static final long BOUND_MS = 10000;

    private ScriptedAdapter adapter;
    private ScriptedBrowser browser;

    private Harness harness() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(new ScriptedBrowser("scripted"));
        browser.url = "app://desk/";
        browser.load("<html><head><title>Desk</title></head><body><input id=\"item\"><p id=\"status\">idle</p>"
                + "</body></html>");
        JPanel view = new JPanel();
        browser.component = view;
        Harness h = new Harness(adapter).install();
        h.walk.components.add(view);
        h.walk.showing.add(view);
        return h.ready();
    }

    @Test
    void loadEventsArePublishedWithTheirPayloadsAndReadyOnlyForTheMainFrame() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            assertEquals(1, browser.listeners.size(), "subscribed once when the browser entered the index");
            h.ctx.call("web.browsers", Harness.args());
            assertEquals(1, browser.listeners.size(), "and only once");
            long seq = h.ctx.events.lastSeq();
            browser.fire(WebEvent.navigated("f2", "data:inner"));
            browser.fire(WebEvent.loadFinished("f2", "data:inner"));
            browser.fire(WebEvent.loadFailed(null, "app://gone", "net::ERR_NAME_NOT_RESOLVED"));
            browser.fire(WebEvent.navigated(null, "app://desk/2"));
            browser.fire(WebEvent.loadFinished(null, "app://desk/2"));
            List<Event> loads = h.ctx.events.since(seq, ai.skillroy.testjava.protocol.TopicFilter.parse("web.load"),
                    100);
            assertEquals(5, loads.size());
            assertEquals(Harness.args("browser", ref, "url", "data:inner", "frameKey", "f2", "kind", "NAVIGATED"),
                    loads.get(0).payload());
            assertEquals(Harness.args("browser", ref, "url", "app://gone", "kind", "LOAD_FAILED", "error",
                    "net::ERR_NAME_NOT_RESOLVED"), loads.get(2).payload());
            assertEquals("web", loads.get(0).source());
            List<Event> ready = h.ctx.events.since(seq, ai.skillroy.testjava.protocol.TopicFilter.parse("web.ready"),
                    100);
            assertEquals(1, ready.size(), "web.ready LOAD for the main frame's load only");
            assertEquals(Harness.args("browser", ref, "url", "app://desk/2", "signal", "LOAD"),
                    ready.get(0).payload());
        }
    }

    /**
     * Phase 8 (phase8-contracts.md 5.2 (i), D549): a masked browser's {@code web.load}, {@code web.ready},
     * {@code web.console} and {@code web.tap} withhold page data and say {@code masked: true}; {@code name} and
     * {@code signal}, what a step waits on, stay. The flag is the entry's (no EDT read on the adapter's thread).
     */
    @Test
    void aMaskedBrowsersEventsWithholdPageData() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            final Object view = browser.component;
            h.ctx.spi.register(CaptureMask.class, new CaptureMask() {
                @Override
                public boolean masks(java.awt.Component c) {
                    return c == view;
                }
            });
            h.ctx.call("web.browsers", Harness.args());
            int stateReads = h.walk.stateReads.get();
            long seq = h.ctx.events.lastSeq();
            final long start = seq;
            browser.fire(WebEvent.navigated("f2", "data:inner-secret"));
            browser.fire(WebEvent.loadFailed(null, "app://gone-secret", "net::ERR secret"));
            browser.fire(WebEvent.loadFinished(null, "app://desk/secret"));
            browser.fire(WebEvent.console("warning", "member secret text", "app://desk/secret.js", 12));
            List<Event> loads = h.ctx.events.since(seq, ai.skillroy.testjava.protocol.TopicFilter.parse("web.load"),
                    100);
            assertEquals(Harness.args("browser", ref, "url", "", "frameKey", "f2", "kind", "NAVIGATED", "masked",
                    Boolean.TRUE), loads.get(0).payload());
            assertEquals(Harness.args("browser", ref, "url", "", "kind", "LOAD_FAILED", "masked", Boolean.TRUE),
                    loads.get(1).payload(), "no error text");
            List<Event> ready = h.ctx.events.since(seq, ai.skillroy.testjava.protocol.TopicFilter.parse("web.ready"),
                    100);
            assertEquals(Harness.args("browser", ref, "url", "", "signal", "LOAD", "masked", Boolean.TRUE),
                    ready.get(0).payload());
            Event console = h.ctx.events.awaitTopic("web.console", seq, null, BOUND_MS);
            assertEquals(Harness.args("browser", ref, "level", "warning", "text", "", "masked", Boolean.TRUE),
                    console.payload(), "no text, source or line");

            browser.responder = new ScriptedBrowser.Responder() {
                private final java.util.concurrent.atomic.AtomicBoolean once
                        = new java.util.concurrent.atomic.AtomicBoolean();

                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    if (eval.expression().contains(".drain(")) {
                        List<Object> events = once.compareAndSet(false, true)
                                ? Arrays.<Object>asList(Harness.args("name", "desk.member-secret", "detail",
                                Harness.args("name", "secret member")), Harness.args("name", "desk.exact"),
                                Harness.args("name", "other.secret")) : Collections.emptyList();
                        return CompletableFuture.completedFuture(Harness.guarded(Harness.args("events", events,
                                "dropped", Long.valueOf(0))));
                    }
                    return CompletableFuture.completedFuture(Harness.guarded(Harness.args("installed",
                            Long.valueOf(1))));
                }
            };
            seq = h.ctx.events.lastSeq();
            h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("desk.*", "desk.exact")));
            Event tapped = h.ctx.events.awaitTopic("web.tap", seq, null, BOUND_MS);
            assertNotNull(tapped);
            // Phase 8 security fix S170 (FE): the name is the tap's own pattern (under a glob the event's type is page
            // data), the detail withheld; an exact pattern's event keeps its name; one no pattern matches is dropped.
            assertEquals(Harness.args("browser", ref, "name", "desk.*", "masked", Boolean.TRUE),
                    tapped.payload(), "the glob that heard it, never the page's event name; the detail is withheld");
            Event tapReady = h.ctx.events.awaitTopic("web.ready", seq, null, BOUND_MS);
            assertEquals(Harness.args("browser", ref, "url", "", "signal", "TAP", "name", "desk.*", "masked",
                    Boolean.TRUE), tapReady.payload());
            Event exact = h.ctx.events.awaitTopic("web.tap", tapped.seq(), null, BOUND_MS);
            assertNotNull(exact);
            assertEquals(Harness.args("browser", ref, "name", "desk.exact", "masked", Boolean.TRUE), exact.payload(),
                    "an exact pattern's event keeps its name: it equals the pattern");
            assertEquals(2, h.ctx.events.since(seq, ai.skillroy.testjava.protocol.TopicFilter.parse("web.tap"), 100)
                    .size(), "an event no pattern of the tap matches is not published");
            assertEquals(stateReads, h.walk.stateReads.get(), "no EDT read for an event");
            for (Event e : h.ctx.events.since(start, ai.skillroy.testjava.protocol.TopicFilter.parse("web.*"),
                    1000)) {
                assertFalse(String.valueOf(e.payload()).contains("secret"), String.valueOf(e.payload()));
            }
        }
    }

    @Test
    void consoleTextIsCappedAndTheSecretsOfWebCallsAndSecretKeysAreHidden() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            long seq = h.ctx.events.lastSeq();
            browser.fire(WebEvent.console("warning", "plain text", "app://desk/app.js", 12));
            Event plain = h.ctx.events.awaitTopic("web.console", seq, null, BOUND_MS);
            assertEquals(Harness.args("browser", ref, "level", "warning", "text", "plain text", "source",
                    "app://desk/app.js", "line", Long.valueOf(12)), plain.payload());
            h.ctx.call("web.dom.fill", Harness.args("selector", "#item", "text", "hunter2-long-secret"),
                    Harness.args("secretParams", Arrays.asList("text")));
            h.ctx.call("web.dom.fill", Harness.args("selector", "#item", "text", "1111"),
                    Harness.args("secretParams", Arrays.asList("text")));
            seq = h.ctx.events.lastSeq();
            browser.fire(WebEvent.console("log", "filled hunter2-long-secret and code 1111, not 11112", null, 0));
            browser.fire(WebEvent.console("log", "hunter2-long-secret", null, 0));
            browser.fire(WebEvent.console("log", "login password=abc&user=bob {\"token\":\"xyz\"} apiKey: k9",
                    null, 0));
            List<Event> console = h.ctx.events.since(seq, ai.skillroy.testjava.protocol.TopicFilter.parse(
                    "web.console"), 10);
            assertEquals("filled <redacted> and code <redacted>, not 11112", console.get(0).payload().get("text"));
            assertEquals("<redacted>", console.get(1).payload().get("text"));
            assertEquals("login password=<redacted>&user=bob {\"token\":\"<redacted>\"} apiKey: <redacted>",
                    console.get(2).payload().get("text"));
            // Phase 8 security fix S175: a JSON text inside a JSON text, an object value and a list value.
            seq = h.ctx.events.lastSeq();
            browser.fire(WebEvent.console("log", "{\"body\":\"{\\\"token\\\":\\\"key-only-77\\\"}\"} {\"secret\":"
                    + " {\"v\": \"key-only-78\"}} {\"credentials\": [\"key-only-79\"]}", null, 0));
            Event shapes = h.ctx.events.awaitTopic("web.console", seq, null, BOUND_MS);
            assertEquals("{\"body\":\"{\\\"token\\\":\\\"<redacted>\\\"}\"} {\"secret\": \"<redacted>\"}"
                    + " {\"credentials\": \"<redacted>\"}", shapes.payload().get("text"));
            assertFalse(h.ctx.log.all().toString().contains("hunter2"), "the fill text is never logged");
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                big.append("0123456789");
            }
            seq = h.ctx.events.lastSeq();
            browser.fire(WebEvent.console("log", big.toString(), null, 0));
            Event cut = h.ctx.events.awaitTopic("web.console", seq, null, BOUND_MS);
            assertEquals(WebPrimitives.CONSOLE_TEXT_MAX, ((String) cut.payload().get("text")).length());
            assertEquals(Boolean.TRUE, cut.payload().get("truncated"));
        }
    }

    /**
     * Security fix S43: a page that replaced the library cannot push past the bounds: a 70 KiB tap {@code detail} is
     * kept as its JSON text cut at 64 KiB with {@code truncated: true}, a drain answer over 1 MiB is not published,
     * and a 100 KiB console source, load URL and load error are cut at 4096 characters.
     */
    @Test
    void pageProducedEventDataIsBoundedOnTheAgent() throws Exception {
        try (Harness h = harness()) {
            final java.util.concurrent.atomic.AtomicInteger drains = new java.util.concurrent.atomic.AtomicInteger();
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    String text = eval.expression();
                    if (text.contains(";return window[" + WebLib.SLOT + "].stringify(window[" + WebLib.SLOT
                            + "].drain(")) {
                        int n = drains.incrementAndGet();
                        if (n == 1) {
                            return CompletableFuture.completedFuture(Harness.guarded(Harness.args("events",
                                    Arrays.asList(Harness.args("name", "desk.big", "detail", Harness.args("blob",
                                            repeat('d', 70 * 1024)))), "dropped", Long.valueOf(0))));
                        }
                        if (n == 2) {
                            // What the guard of P2-L4-F2 answers for a drain whose text exceeds its bound.
                            return CompletableFuture.completedFuture(Harness.guardedText(WebLib.GUARD_PREFIX
                                    + "oversized"));
                        }
                        return CompletableFuture.completedFuture(Harness.guarded(Harness.args("events",
                                Collections.emptyList(), "dropped", Long.valueOf(0))));
                    }
                    return CompletableFuture.completedFuture(Harness.guarded(Harness.args("installed",
                            Long.valueOf(1))));
                }
            };
            long seq = h.ctx.events.lastSeq();
            h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("desk.*")));
            Event tapped = h.ctx.events.awaitTopic("web.tap", seq, null, BOUND_MS);
            assertNotNull(tapped);
            assertEquals("desk.big", tapped.payload().get("name"));
            assertEquals(Boolean.TRUE, tapped.payload().get("truncated"));
            assertEquals(WebLib.MAX_DETAIL_CHARS, ((String) tapped.payload().get("detail")).length(),
                    "the detail's JSON text, cut");
            // P2-L4-F2: the oversized drain ends the tap (logged once); the poller sends no further drain.
            Taps taps = h.module.tapsForTest();
            Entry entry = h.module.indexForTest().snapshot().byRef.get(BrowserIndexTest.ref(h, browser));
            long deadline = System.currentTimeMillis() + BOUND_MS;
            while (taps.tapped(entry) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(taps.tapped(entry), "the oversized drain ended the tap");
            Thread.sleep(10 * Taps.POLL_MS);
            assertEquals(2, drains.get(), "no drain after the oversized one");
            int warned = 0;
            for (String line : h.ctx.log.all()) {
                if (line.startsWith("WARN web: the tap of browser") && line.contains("drain answer exceeded")) {
                    warned++;
                }
            }
            assertEquals(1, warned, "logged once");
            for (Event e : h.ctx.events.since(seq, ai.skillroy.testjava.protocol.TopicFilter.parse("web.tap"), 10)) {
                assertFalse("desk.huge".equals(e.payload().get("name")), "an oversized drain publishes nothing");
            }
            assertEquals(Long.valueOf(1), h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("desk.*")))
                    .get("installed"), "the next web.tap arms it again");
            assertTrue(taps.tapped(entry));
            h.ctx.call("web.tap", Harness.args("patterns", Collections.emptyList()));

            seq = h.ctx.events.lastSeq();
            String longUrl = "app://desk/" + repeat('u', 100 * 1024);
            browser.fire(WebEvent.console("log", "text", longUrl, 1));
            browser.fire(WebEvent.loadFailed(null, longUrl, repeat('e', 100 * 1024)));
            Event console = h.ctx.events.awaitTopic("web.console", seq, null, BOUND_MS);
            assertEquals(WebLib.MAX_URL_CHARS, ((String) console.payload().get("source")).length());
            Event load = h.ctx.events.awaitTopic("web.load", seq, null, BOUND_MS);
            assertEquals(WebLib.MAX_URL_CHARS, ((String) load.payload().get("url")).length());
            assertEquals(WebLib.MAX_URL_CHARS, ((String) load.payload().get("error")).length());
        }
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void theTapInstallsAnInitScriptArmsTheDocumentAndThePollerPublishesTappedEvents() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            Map<String, Object> tap = h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("desk.*")));
            assertEquals(Harness.args("installed", Long.valueOf(1), "timing", "DOCUMENT_START"), tap);
            String installer = WebLib.tapInstaller(Arrays.asList("desk.*"), "window");
            assertEquals(Collections.singletonList(installer), browser.scripts, "the init script for new documents");
            assertTrue(sent(WebLib.boundedCallExpression("tap", Arrays.asList("desk.*"), "window")),
                    "and the same call evaluated in the current document, through the guard (P2-L4-F2)");
            assertTrue(h.module.tapsForTest().polling(), "the poller runs while a tap is installed");
            long seq = h.ctx.events.lastSeq();
            browser.document.pageEvent("desk.borrowed", Harness.args("item", "saw", "qty", Long.valueOf(2)));
            browser.document.pageEvent("other.thing", null);
            Event tapped = h.ctx.events.awaitTopic("web.tap", seq, null, BOUND_MS);
            assertNotNull(tapped, "the poller published the page event");
            assertEquals(Harness.args("browser", ref, "name", "desk.borrowed", "detail", Harness.args("item", "saw",
                    "qty", Long.valueOf(2))), tapped.payload());
            Event ready = h.ctx.events.awaitTopic("web.ready", seq, Harness.args("signal", "TAP"), BOUND_MS);
            assertEquals(Harness.args("browser", ref, "url", "app://desk/", "signal", "TAP", "name",
                    "desk.borrowed"), ready.payload());
            Map<String, Object> again = h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("x.a", "x.b"),
                    "target", "document"));
            assertEquals(Long.valueOf(2), again.get("installed"));
            assertEquals(Collections.singletonList(installer), browser.cancelledScripts, "re-installing replaces");
            assertEquals(Collections.singletonList(WebLib.tapInstaller(Arrays.asList("x.a", "x.b"), "document")),
                    browser.scripts);
            Map<String, Object> off = h.ctx.map("web.tap", Harness.args("patterns", Collections.emptyList()));
            assertEquals(Long.valueOf(0), off.get("installed"));
            assertTrue(browser.scripts.isEmpty(), "patterns: [] cancels the init script");
            assertTrue(sent(WebLib.boundedCallExpression("tap", Collections.<String>emptyList(), "window")),
                    "and clears the tap in the current document");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
            while (h.module.tapsForTest().polling() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertFalse(h.module.tapsForTest().polling(), "the poller stops with the last tap");
        }
    }

    @Test
    void anEventFiredJustBeforeThePageStaysBusyIsPublishedWhenTheOutstandingDrainAnswers() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("desk.*")));
            Entry entry = h.module.indexForTest().snapshot().byRef.get(ref);
            Taps taps = h.module.tapsForTest();
            final ExecutorService page = Executors.newSingleThreadExecutor(); // the page's main thread
            final CountDownLatch busy = new CountDownLatch(1);
            final List<String> sent = new CopyOnWriteArrayList<String>();
            try {
                browser.responder = new ScriptedBrowser.Responder() {
                    @Override
                    public CompletableFuture<String> answer(final WebEval eval) {
                        sent.add(eval.expression());
                        final CompletableFuture<String> f = new CompletableFuture<String>();
                        page.submit(new Runnable() {
                            @Override
                            public void run() {
                                relay(browser.byDocument(eval), f);
                            }
                        });
                        return f;
                    }
                };
                long seq = h.ctx.events.lastSeq();
                page.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        browser.document.pageEvent("desk.borrowed", Harness.args("item", "saw"));
                        return Boolean.valueOf(busy.await(30, TimeUnit.SECONDS)); // a long handler, a modal dialog
                    }
                });
                await(taps, entry, true);
                long rounds = taps.rounds();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
                while (taps.rounds() < rounds + 3 && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertTrue(taps.rounds() >= rounds + 3, "the poller went on");
                assertEquals(1, drains(sent), "no other drain is sent while one is outstanding: " + sent.size());
                busy.countDown();
                Event tapped = h.ctx.events.awaitTopic("web.tap", seq, null, BOUND_MS);
                assertNotNull(tapped, "the event the outstanding drain took is published when it answers");
                assertEquals(Harness.args("browser", ref, "name", "desk.borrowed", "detail", Harness.args("item",
                        "saw")), tapped.payload());
                await(taps, entry, false);
            } finally {
                busy.countDown();
                page.shutdownNow();
            }
        }
    }

    @Test
    void aTappedBrowserThatLeavesTheIndexOpenHasItsTapClearedInItsDocument() throws Exception {
        try (Harness h = harness()) {
            h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("desk.*")));
            String installer = WebLib.tapInstaller(Arrays.asList("desk.*"), "window");
            browser.removed = true; // the adapter no longer lists it; it is not closed
            h.ctx.call("web.browsers", Harness.args());
            String clear = WebLib.boundedCallExpression("tap", Collections.<String>emptyList(), "window");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
            while (!sent(clear) && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(sent(clear), "the tap is cleared in the document of a browser that left the index");
            assertEquals(Collections.singletonList(installer), browser.cancelledScripts, "and its init script");
        }
    }

    @Test
    void theTapNeedsAnInitScriptAndValidPatterns() throws Exception {
        try (Harness h = harness()) {
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.tap", Harness.args()).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.tap", Harness.args("patterns",
                    Arrays.asList(""))).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.tap", Harness.args("patterns",
                    Arrays.asList("x"), "target", "body")).error().code());
            browser.timing = WebInitScript.Timing.DOCUMENT_LOADED;
            assertEquals("DOCUMENT_LOADED", h.ctx.map("web.tap", Harness.args("patterns", Arrays.asList("x")))
                    .get("timing"));
            browser.capabilities = EnumSet.of(WebCapability.EVENTS_LOAD);
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, h.ctx.error("web.tap", Harness.args("patterns",
                    Arrays.asList("x"))).error().code());
        }
    }

    @Test
    void waitFlagPollsUntilTheFlagIsTruthyAndPublishesReadyFlag() throws Exception {
        try (final Harness h = harness()) {
            final String ref = BrowserIndexTest.ref(h, browser);
            long seq = h.ctx.events.lastSeq();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Object> wait = caller.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return h.ctx.call("web.waitFlag", Harness.args("expression", "window.deskReady === true",
                                "pollMs", Long.valueOf(5)));
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (countProbes() < 2 && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                browser.document.pageGlobal("deskReady", Boolean.TRUE);
                Map<?, ?> out = (Map<?, ?>) wait.get(10, TimeUnit.SECONDS);
                assertEquals(Boolean.TRUE, out.get("ready"));
                assertTrue(((Number) out.get("polls")).longValue() >= 2);
                Event flag = h.ctx.events.awaitTopic("web.ready", seq, Harness.args("signal", "FLAG"), BOUND_MS);
                assertEquals(Harness.args("browser", ref, "url", "app://desk/", "signal", "FLAG"), flag.payload());
            } finally {
                caller.shutdownNow();
            }
            long started = System.nanoTime();
            Map<String, Object> no = h.ctx.map("web.waitFlag", Harness.args("expression", "window.never === true",
                    "timeoutMs", Long.valueOf(300)));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(Boolean.FALSE, no.get("ready"));
            assertTrue(ms >= 250 && ms < 3000, "bounded by timeoutMs: " + ms);
            // A35: a poll that throws at run time is not ready (an expression that does not compile: WaitFlagGuardTest).
            Map<String, Object> failing = h.ctx.map("web.waitFlag", Harness.args("expression", "nonsense()",
                    "timeoutMs", Long.valueOf(100)));
            assertEquals(Boolean.FALSE, failing.get("ready"));
            assertTrue(String.valueOf(failing.get("lastError")).startsWith("ReferenceError: the fake cannot evaluate"),
                    String.valueOf(failing));
            Map<?, ?> budget = (Map<?, ?>) h.ctx.call("web.waitFlag", Harness.args("expression",
                    "window.never === true"), Harness.args("timeoutMs", Long.valueOf(400)));
            assertEquals(Boolean.FALSE, budget.get("ready"), "answers before the dispatcher's TIMEOUT");
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.waitFlag", Harness.args("expression", "x",
                    "timeoutMs", Long.valueOf(-1))).error().code());
        }
    }

    @Test
    void waitFlagWaitsForAProbeThePageHasNotAnsweredInsteadOfSendingAnother() throws Exception {
        try (Harness h = harness()) {
            final List<WebEval> probes = new CopyOnWriteArrayList<WebEval>();
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    if (eval.expression().contains("deskReady")) {
                        probes.add(eval);
                        return new CompletableFuture<String>(); // a page that does not answer (busy, frozen)
                    }
                    return browser.byDocument(eval);
                }
            };
            browser.handedOut.clear();
            Map<String, Object> out = h.ctx.map("web.waitFlag", Harness.args("expression",
                    "window.deskReady === true", "timeoutMs", Long.valueOf(2600), "pollMs", Long.valueOf(20)));
            assertEquals(Boolean.FALSE, out.get("ready"));
            assertEquals(Long.valueOf(1), out.get("polls"));
            assertEquals(1, probes.size(), "one probe outstanding at a time, waited for until the budget ended");
            assertTrue(probes.get(0).timeoutMs() > 2000, "its adapter bound is the rest of the budget: "
                    + probes.get(0).timeoutMs());
            assertTrue(browser.handedOut.get(0).isCancelled(), "abandoned at the end");
        }
    }

    private static int drains(List<String> expressions) {
        int n = 0;
        for (String e : expressions) {
            if (e.contains(".drain(")) {
                n++;
            }
        }
        return n;
    }

    private static void await(Taps taps, Entry entry, boolean draining) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
        while (taps.draining(entry) != draining && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(draining, taps.draining(entry), "the drain is " + (draining ? "" : "no longer ") + "outstanding");
    }

    private static void relay(CompletableFuture<String> from, final CompletableFuture<String> to) {
        from.whenComplete(new java.util.function.BiConsumer<String, Throwable>() {
            @Override
            public void accept(String v, Throwable t) {
                if (t != null) {
                    to.completeExceptionally(t);
                } else {
                    to.complete(v);
                }
            }
        });
    }

    private int countProbes() {
        int n = 0;
        for (WebEval e : browser.evals) {
            if (e.expression().contains("deskReady")) {
                n++;
            }
        }
        return n;
    }

    private boolean sent(String expression) {
        for (WebEval e : browser.evals) {
            if (e.expression().equals(expression)) {
                return true;
            }
        }
        return false;
    }
}
