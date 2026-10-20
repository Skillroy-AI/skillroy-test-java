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
import ai.skillroy.testjava.agent.api.spi.web.BrowserMask;
import ai.skillroy.testjava.agent.api.spi.web.BrowserRegistryAdapter;
import ai.skillroy.testjava.agent.api.spi.web.BrowserSeed;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.web.kit.KitPages;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 security fix S111 (phase7-contracts.md 3.6, 4.8): a browser whose component a pack's {@code CaptureMask}
 * names, itself or through an ancestor, is masked on every route of the web module: {@code web.bitmap},
 * {@code web.dom.text}, {@code web.ariaSnapshot} and {@code web.hitTest} answer {@code INVALID_PARAMS} with
 * {@code data.reason: "masked"} unless {@code redact: false} (a person's argument), and {@code web.browsers} says
 * {@code masked: true} and withholds the URL, the title and the frames' names and URLs; a mask that throws masks (the
 * safe side); without a mask nothing changes and no extra EDT read is made.
 *
 * <p>Phase 8 (gap 110; phase8-contracts.md 5.2, section 13.1 row 17): a browser without a view is masked while any
 * {@code CaptureMask} or {@code BrowserMask} is registered (a), (b); a {@code BrowserMask} releases it by tag (c), names
 * a browser with a component by tag (d) or an untagged one by its vendor object (e), masks when it throws (f), never
 * releases a component a {@code CaptureMask} names once the browser gained one (g), and combines by union (h); the
 * actions that stay allowed on a masked browser answer without page data (i).
 */
class MaskedBrowserTest {

    private ScriptedAdapter adapter;
    private ScriptedBrowser browser;
    private JPanel holder;
    private JPanel view;

    private Harness harness() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(new ScriptedBrowser("scripted"));
        EnumSet<WebCapability> flags = EnumSet.copyOf(browser.capabilities);
        flags.add(WebCapability.FRAMES);
        browser.capabilities = flags;
        browser.load(KitPages.main());
        browser.frames = Arrays.asList(new WebFrame("M", null, "main-name", "data:main-url", true));
        holder = new JPanel();
        view = new JPanel();
        holder.add(view);
        browser.component = view;
        Harness h = new Harness(adapter).install();
        h.walk.components.add(view);
        h.walk.showing.add(view);
        return h.ready();
    }

    /** A mask that names one component. */
    private static CaptureMask naming(final Component c) {
        return new CaptureMask() {
            @Override
            public boolean masks(Component component) {
                return component == c;
            }
        };
    }

    private static final List<String> READS = Arrays.asList("web.bitmap", "web.dom.text", "web.ariaSnapshot",
            "web.hitTest");

    private static Map<String, Object> argsOf(String primitive, boolean unredacted) {
        Map<String, Object> args = primitive.equals("web.hitTest")
                ? Harness.args("point", Harness.args("x", 84L, "y", 110L)) : Harness.args();
        if (unredacted) {
            args.put("redact", Boolean.FALSE);
        }
        return args;
    }

    @Test
    void theFourReadsOfAMaskedBrowserAreRefusedUnlessAPersonSaysRedactFalse() throws Exception {
        try (Harness h = harness()) {
            h.ctx.spi().register(CaptureMask.class, naming(view));
            for (String primitive : READS) {
                int before = browser.evals.size();
                RpcException refused = h.ctx.error(primitive, argsOf(primitive, false));
                assertEquals(ErrorCodes.INVALID_PARAMS, refused.error().code(), primitive);
                assertEquals("masked", refused.error().data().get("reason"), primitive);
                assertEquals(before, browser.evals.size(), primitive + ": the page is never evaluated");
                h.ctx.call(primitive, argsOf(primitive, true));
            }
        }
    }

    @Test
    void aMaskOnAnAncestorOrAMaskThatThrowsMasksTheBrowserToo() throws Exception {
        try (Harness h = harness()) {
            AutoCloseable onHolder = h.ctx.spi().register(CaptureMask.class, naming(holder));
            assertEquals("masked", h.ctx.error("web.dom.text", Harness.args()).error().data().get("reason"),
                    "a mask on the container");
            onHolder.close();
            h.ctx.spi().register(CaptureMask.class, new CaptureMask() {
                @Override
                public boolean masks(Component component) {
                    throw new IllegalStateException("a broken mask");
                }
            });
            assertEquals("masked", h.ctx.error("web.ariaSnapshot", Harness.args()).error().data().get("reason"),
                    "a mask that throws: the safe side");
        }
    }

    @Test
    void webBrowsersSaysMaskedAndWithholdsTheUrlTheTitleAndTheFrames() throws Exception {
        try (Harness h = harness()) {
            Map<?, ?> open = (Map<?, ?>) ((List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers")).get(0);
            assertFalse(open.containsKey("masked"), String.valueOf(open));
            assertTrue(String.valueOf(open.get("url")).length() > 0, "an unmasked browser's URL is listed");

            h.ctx.spi().register(CaptureMask.class, naming(view));
            Map<?, ?> masked = (Map<?, ?>) ((List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers"))
                    .get(0);
            assertEquals(Boolean.TRUE, masked.get("masked"));
            assertEquals("", masked.get("url"));
            assertFalse(masked.containsKey("title"), String.valueOf(masked));
            Map<?, ?> frame = (Map<?, ?>) ((List<?>) masked.get("frames")).get(0);
            assertEquals("M", frame.get("key"), "the frame's key stays (a handle)");
            assertEquals("", frame.get("name"));
            assertEquals("", frame.get("url"));
            assertFalse(String.valueOf(masked).contains("main-url"), String.valueOf(masked));
        }
    }

    @Test
    void withoutAMaskNothingChangesAndNoExtraReadIsMade() throws Exception {
        try (Harness h = harness()) {
            int before = h.walk.stateReads.get();
            h.ctx.call("web.dom.text", Harness.args());
            h.ctx.call("web.bitmap", Harness.args());
            assertEquals(before, h.walk.stateReads.get(), "no EDT read for a mask nobody registered");
        }
    }

    // ------------------------------------------------------------------------------------------------ Phase 8

    private ScriptedBrowser viewless;

    /** A browser without a view, found through a seed tagged {@code tag} (the web host's {@code detached}). */
    private Harness viewlessHarness(final String tag) throws Exception {
        adapter = new ScriptedAdapter("scripted");
        viewless = adapter.add(new ScriptedBrowser("scripted"));
        EnumSet<WebCapability> flags = EnumSet.copyOf(viewless.capabilities);
        flags.add(WebCapability.FRAMES);
        flags.add(WebCapability.INPUT);
        viewless.capabilities = flags;
        viewless.load(KitPages.main());
        viewless.url = "data:detached-url";
        viewless.frames = Arrays.asList(new WebFrame("M", null, "detached-name", "data:detached-url", true));
        viewless.component = null;
        viewless.seedOnly = true;
        Harness h = new Harness(adapter).install();
        final Object vendor = viewless.vendorObject();
        h.ctx.spi.register(BrowserRegistryAdapter.class, new BrowserRegistryAdapter() {
            @Override
            public Collection<BrowserSeed> seeds() {
                return Collections.singletonList(BrowserSeed.of(vendor, tag));
            }
        });
        return h.ready();
    }

    /** A mask answering {@code answer} for one tag, null otherwise. */
    private static BrowserMask byTag(final String tag, final Boolean answer) {
        return new BrowserMask() {
            @Override
            public Boolean masks(String t, Object vendorObject, boolean hasView) {
                return tag.equals(t) ? answer : null;
            }
        };
    }

    private static Map<?, ?> listed(Harness h) throws Exception {
        return h.ctx.map("web.browsers", Harness.args());
    }

    private static Map<?, ?> first(Map<?, ?> listing) {
        return (Map<?, ?>) ((List<?>) listing.get("browsers")).get(0);
    }

    private static Map<String, Object> byTagArgs(String primitive, String tag, boolean unredacted) {
        Map<String, Object> args = argsOf(primitive, unredacted);
        args.put("by", Harness.args("tag", tag));
        return args;
    }

    private static void assertListedMasked(Map<?, ?> entry) {
        assertEquals(Boolean.TRUE, entry.get("masked"), String.valueOf(entry));
        assertEquals("", entry.get("url"));
        assertFalse(entry.containsKey("title"), String.valueOf(entry));
        Map<?, ?> frame = (Map<?, ?>) ((List<?>) entry.get("frames")).get(0);
        assertEquals("", frame.get("name"));
        assertEquals("", frame.get("url"));
    }

    private static void assertReadsRefused(Harness h, String tag) {
        for (String primitive : READS) {
            RpcException refused = h.ctx.error(primitive, byTagArgs(primitive, tag, false));
            assertEquals(ErrorCodes.INVALID_PARAMS, refused.error().code(), primitive);
            assertEquals("masked", refused.error().data().get("reason"), primitive);
            assertEquals("redact", refused.error().data().get("param"), primitive);
        }
    }

    /** (a) Nothing registered: a view-less browser is listed with its URL and title and its page is read. */
    @Test
    void aViewlessBrowserIsUnmaskedWhileNoMaskIsRegistered() throws Exception {
        try (Harness h = viewlessHarness("detached")) {
            Map<?, ?> listing = listed(h);
            assertEquals(Boolean.FALSE, listing.get("masksRegistered"));
            Map<?, ?> entry = first(listing);
            assertFalse(entry.containsKey("masked"), String.valueOf(entry));
            assertEquals("data:detached-url", entry.get("url"));
            assertEquals(KitPages.MAIN_TITLE, entry.get("title"));
            assertFalse(entry.containsKey("component"), "no view");
            for (String primitive : READS) {
                h.ctx.call(primitive, byTagArgs(primitive, "detached", false));
            }
        }
    }

    /** (b) Any CaptureMask registered: the view-less browser is masked on every route, without an EDT read. */
    @Test
    void aViewlessBrowserIsMaskedWhileACaptureMaskIsRegistered() throws Exception {
        try (Harness h = viewlessHarness("detached")) {
            h.ctx.spi.register(CaptureMask.class, naming(new JPanel()));
            int reads = h.walk.stateReads.get();
            Map<?, ?> listing = listed(h);
            assertEquals(Boolean.TRUE, listing.get("masksRegistered"));
            assertListedMasked(first(listing));
            assertFalse(String.valueOf(listing).contains("detached-url"), String.valueOf(listing));
            int before = viewless.evals.size();
            assertReadsRefused(h, "detached");
            assertEquals(before, viewless.evals.size(), "the page is never evaluated");
            assertEquals(reads, h.walk.stateReads.get(), "a view-less browser costs no EDT read");
            for (String primitive : READS) {
                h.ctx.call(primitive, byTagArgs(primitive, "detached", true));
            }
            assertEquals(0, h.walk.maskedReads.get(), "no component to read at the rebuild either");
        }
    }

    /** (c) A BrowserMask answering FALSE for the tag releases the view-less browser. */
    @Test
    void aBrowserMaskAnsweringFalseReleasesAViewlessBrowser() throws Exception {
        try (Harness h = viewlessHarness("detached")) {
            h.ctx.spi.register(CaptureMask.class, naming(new JPanel()));
            h.ctx.spi.register(BrowserMask.class, byTag("detached", Boolean.FALSE));
            Map<?, ?> listing = listed(h);
            assertEquals(Boolean.TRUE, listing.get("masksRegistered"));
            Map<?, ?> entry = first(listing);
            assertFalse(entry.containsKey("masked"), String.valueOf(entry));
            assertEquals("data:detached-url", entry.get("url"));
            for (String primitive : READS) {
                h.ctx.call(primitive, byTagArgs(primitive, "detached", false));
            }
        }
    }

    /** (d) TRUE by tag masks a browser with a showing component on every route, with no CaptureMask registered. */
    @Test
    void aBrowserMaskAnsweringTrueByTagMasksABrowserWithAView() throws Exception {
        try (Harness h = harness()) {
            final Object vendor = browser.vendorObject();
            h.ctx.spi.register(BrowserRegistryAdapter.class, new BrowserRegistryAdapter() {
                @Override
                public Collection<BrowserSeed> seeds() {
                    return Collections.singletonList(BrowserSeed.of(vendor, "back"));
                }
            });
            final boolean[] views = new boolean[1];
            h.ctx.spi.register(BrowserMask.class, new BrowserMask() {
                @Override
                public Boolean masks(String tag, Object vendorObject, boolean hasView) {
                    views[0] = hasView;
                    return "back".equals(tag) ? Boolean.TRUE : null;
                }
            });
            Map<?, ?> listing = listed(h);
            assertEquals("back", first(listing).get("tag"));
            assertListedMasked(first(listing));
            assertTrue(views[0], "hasView is true for a browser with a component");
            for (String primitive : READS) {
                RpcException refused = h.ctx.error(primitive, argsOf(primitive, false));
                assertEquals("masked", refused.error().data().get("reason"), primitive);
            }
            RpcException byUrl = h.ctx.error("web.dom.text", Harness.args("by", Harness.args("url", ".")));
            assertEquals(ErrorCodes.NOT_FOUND, byUrl.error().code(), "by.url never matches a masked browser");
        }
    }

    /** (e) TRUE by vendor object masks an untagged browser. */
    @Test
    void aBrowserMaskAnsweringTrueByVendorObjectMasksAnUntaggedBrowser() throws Exception {
        try (Harness h = harness()) {
            final Object vendor = browser.vendorObject();
            final Object[] tags = {"unset"};
            h.ctx.spi.register(BrowserMask.class, new BrowserMask() {
                @Override
                public Boolean masks(String tag, Object vendorObject, boolean hasView) {
                    tags[0] = tag;
                    return vendorObject == vendor ? Boolean.TRUE : null;
                }
            });
            assertListedMasked(first(listed(h)));
            assertNull(tags[0], "an untagged browser's tag is null");
            assertEquals("masked", h.ctx.error("web.dom.text", Harness.args()).error().data().get("reason"));
        }
    }

    /** (f) A BrowserMask that throws masks the browser and is logged once by class. */
    @Test
    void aBrowserMaskThatThrowsMasksAndIsLoggedOnce() throws Exception {
        try (Harness h = harness()) {
            h.ctx.spi.register(BrowserMask.class, new ThrowingMask());
            assertListedMasked(first(listed(h)));
            assertEquals("masked", h.ctx.error("web.ariaSnapshot", Harness.args()).error().data().get("reason"));
            listed(h);
            int lines = 0;
            for (String line : h.ctx.log.all()) {
                if (line.contains(ThrowingMask.class.getName())) {
                    lines++;
                    assertFalse(line.contains("page text"), "the exception's message is not logged: " + line);
                }
            }
            assertEquals(1, lines, String.valueOf(h.ctx.log.all()));
        }
    }

    /** A mask whose message quotes what it saw. */
    static final class ThrowingMask implements BrowserMask {
        @Override
        public Boolean masks(String tag, Object vendorObject, boolean hasView) {
            throw new IllegalStateException("page text");
        }
    }

    /** (g) Once the browser gains a component, the CaptureMask rule governs it and a FALSE no longer releases it. */
    @Test
    void aBrowserThatGainsAViewIsJudgedByTheCaptureMaskAndAFalseNoLongerReleasesIt() throws Exception {
        try (Harness h = viewlessHarness("detached")) {
            JPanel gained = new JPanel();
            h.ctx.spi.register(CaptureMask.class, naming(gained));
            h.ctx.spi.register(BrowserMask.class, byTag("detached", Boolean.FALSE));
            assertFalse(first(listed(h)).containsKey("masked"), "released while it has no view");
            viewless.component = gained;
            h.walk.components.add(gained);
            h.walk.showing.add(gained);
            int maskedReads = h.walk.maskedReads.get();
            Map<?, ?> entry = first(listed(h));
            assertListedMasked(entry);
            assertEquals(maskedReads + 1, h.walk.maskedReads.get(), "one masked read at the rebuild");
            assertReadsRefused(h, "detached");
        }
    }

    /** (h) Masks combine by union: one TRUE over a FALSE masks. */
    @Test
    void masksCombineByUnionOneTrueOverAFalseMasks() throws Exception {
        try (Harness h = viewlessHarness("detached")) {
            h.ctx.spi.register(BrowserMask.class, byTag("detached", Boolean.FALSE));
            h.ctx.spi.register(BrowserMask.class, byTag("detached", Boolean.TRUE));
            assertListedMasked(first(listed(h)));
            assertReadsRefused(h, "detached");
        }
    }

    /**
     * (i) D637: {@code web.pointer} on a masked browser answers without its {@code hit}; {@code redact: false} keeps it.
     * Phase 8 security fix S165 (FE): {@code web.dom.click}, {@code web.dom.fill} and {@code web.dispatchEvent} with a
     * selector target are refused {@code INVALID_PARAMS {param: redact, reason: masked}} before the page evaluates the
     * locator (whether it matched would reveal the page), the page untouched; {@code redact: false} (a person's) acts
     * and answers in full; a dispatch on the window evaluates no locator and stays allowed. A browser judged masked while
     * the action ran still answers {@code role} and {@code tag} only (D637's strip, now the second line).
     */
    @Test
    void actingOnAMaskedBrowserReturnsNoPageData() throws Exception {
        try (Harness h = harness()) {
            EnumSet<WebCapability> flags = EnumSet.copyOf(browser.capabilities);
            flags.add(WebCapability.INPUT);
            browser.capabilities = flags;
            h.reprobe();
            Map<String, Object> pointer = Harness.args("action", "CLICK", "point", Harness.args("x", 72L, "y", 216L));
            Map<String, Object> control = h.ctx.map("web.pointer", new java.util.LinkedHashMap<String, Object>(
                    pointer));
            assertTrue(control.containsKey("hit"), "the control: an unmasked browser's pointer carries the hit");
            Map<String, Object> click = h.ctx.map("web.dom.click", Harness.args("selector", "#count"));
            assertEquals("count", ((Map<?, ?>) click.get("clicked")).get("name"), "the control");

            h.ctx.spi().register(CaptureMask.class, naming(view));
            Map<String, Object> masked = h.ctx.map("web.pointer", new java.util.LinkedHashMap<String, Object>(
                    pointer));
            assertFalse(masked.containsKey("hit"), String.valueOf(masked));
            assertEquals(control.get("dispatched"), masked.get("dispatched"), "the action itself goes on");
            Map<String, Object> keep = new java.util.LinkedHashMap<String, Object>(pointer);
            keep.put("redact", Boolean.FALSE);
            assertTrue(h.ctx.map("web.pointer", keep).containsKey("hit"), "redact: false keeps the hit");

            assertLocatorActionsRefused(h, Harness.args());
            Map<String, Object> unredacted = h.ctx.map("web.dom.click", Harness.args("selector", "#count", "redact",
                    Boolean.FALSE));
            assertEquals("count", ((Map<?, ?>) unredacted.get("clicked")).get("id"), "redact: false keeps the id");
            assertEquals(Long.valueOf(1), h.ctx.map("web.dom.fill", Harness.args("selector", "#count", "text", "x",
                    "redact", Boolean.FALSE)).get("typed"), "a person's redact: false fills");
            assertEquals(Boolean.TRUE, h.ctx.map("web.dispatchEvent", Harness.args("name", "desk.reset")).get(
                    "dispatched"), "a dispatch on the window evaluates no locator: allowed");
        }
    }

    /**
     * The three locator actions of a masked browser, each refused before the page is evaluated (S165): {@code
     * extra} is added to every call's arguments (a selection).
     */
    private void assertLocatorActionsRefused(Harness h, Map<String, Object> extra) {
        java.util.Map<String, Map<String, Object>> calls = new java.util.LinkedHashMap<String, Map<String, Object>>();
        calls.put("web.dom.click", Harness.args("selector", "#count"));
        calls.put("web.dom.fill", Harness.args("selector", "#count", "text", "x"));
        calls.put("web.dispatchEvent", Harness.args("name", "desk.reset", "target", "#count"));
        for (Map.Entry<String, Map<String, Object>> c : calls.entrySet()) {
            Map<String, Object> args = new java.util.LinkedHashMap<String, Object>(c.getValue());
            args.putAll(extra);
            int before = browser.evals.size();
            RpcException refused = h.ctx.error(c.getKey(), args);
            assertEquals(ErrorCodes.INVALID_PARAMS, refused.error().code(), c.getKey());
            assertEquals("masked", refused.error().data().get("reason"), c.getKey());
            assertEquals("redact", refused.error().data().get("param"), c.getKey());
            assertEquals(before, browser.evals.size(), c.getKey() + ": the page never evaluates the locator");
        }
    }

    /**
     * D637's strip as the second line (S165): a browser that a pack's mask names while the action runs (here a
     * {@code BrowserMask} registered while the page answers, after the refusal's check found no mask) answers {@code
     * role} and {@code tag} only, and the {@code AMBIGUOUS} candidates likewise with {@code count} kept.
     */
    @Test
    void aBrowserMaskedWhileTheActionRanAnswersWithoutPageData() throws Exception {
        try (final Harness h = harness()) {
            final Object vendor = browser.vendorObject();
            final AutoCloseable[] registration = new AutoCloseable[1];
            final Map<String, Object> ambiguous = Harness.args("__error", "AMBIGUOUS", "data", Harness.args(
                    "candidates", Arrays.asList(Harness.args("role", "button", "name", "Count", "tag", "button",
                            "id", "count"), Harness.args("role", "heading", "name", "Kit main", "tag", "h1")),
                    "count", Long.valueOf(25)));
            final boolean[] answerAmbiguous = {false};
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    if (registration[0] == null) {
                        registration[0] = h.ctx.spi.register(BrowserMask.class, new BrowserMask() {
                            @Override
                            public Boolean masks(String tag, Object vendorObject, boolean hasView) {
                                return vendorObject == vendor ? Boolean.TRUE : null;
                            }
                        });
                    }
                    return answerAmbiguous[0] ? CompletableFuture.completedFuture(Harness.guarded(ambiguous))
                            : browser.byDocument(eval);
                }
            };
            Map<String, Object> clicked = h.ctx.map("web.dom.click", Harness.args("selector", "#count"));
            assertEquals(Harness.args("role", "button", "tag", "button"), clicked.get("clicked"));
            assertEquals(Long.valueOf(1), clicked.get("matched"));

            registration[0].close();
            registration[0] = null;
            answerAmbiguous[0] = true;
            RpcException amb = h.ctx.error("web.dom.click", Harness.args("selector", "*"));
            assertEquals(ErrorCodes.AMBIGUOUS, amb.error().code());
            assertEquals(Long.valueOf(25), amb.error().data().get("count"));
            assertEquals(Arrays.asList(Harness.args("role", "button", "tag", "button"), Harness.args("role",
                    "heading", "tag", "h1")), amb.error().data().get("candidates"));
            assertFalse(String.valueOf(amb.error().data()).contains("Kit main"), String.valueOf(amb.error()));
        }
    }

    // ------------------------------------------------------------------------------------- Phase 8 security fixes (FE)

    /** The first {@code web.console} event after {@code seq}. */
    private static Map<String, Object> console(Harness h, long seq) throws Exception {
        ai.skillroy.testjava.protocol.Event e = h.ctx.events.awaitTopic("web.console", seq, null, 5000);
        assertTrue(e != null, "a console event");
        return e.payload();
    }

    /** The browser's ref, read by its vendor object from {@code web.browsers}. */
    private String refOf(Harness h, ScriptedBrowser b) throws Exception {
        return BrowserIndexTest.ref(h, b);
    }

    /**
     * S165 on a view-less browser, the case the stopped run's sceptic probed in a page: selected by its tag, the three
     * locator actions are refused before its page evaluates the locator; nothing registered, they act as before.
     */
    @Test
    void theLocatorActionsOfAMaskedViewlessBrowserAreRefusedBeforeItsPageIsEvaluated() throws Exception {
        try (Harness h = viewlessHarness("detached")) {
            browser = viewless;
            h.ctx.call("web.dom.click", Harness.args("by", Harness.args("tag", "detached"), "selector", "#count"));
            h.ctx.spi.register(CaptureMask.class, naming(new JPanel()));
            assertLocatorActionsRefused(h, Harness.args("by", Harness.args("tag", "detached")));
        }
    }

    /**
     * S167: without a running event-dispatch thread (the production walk told so) a masked browser stays masked on every
     * route: the walk's state answers masked while a mask is registered, so no per-call judgement lowers the flag
     * ({@code web.browsers}, the reads, the actions, the events, {@code by: {url}}). Fails on the old code, where the
     * unread state said "not masked".
     */
    @Test
    void withoutAnEventDispatchThreadAMaskedBrowserStaysMasked() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(new ScriptedBrowser("scripted"));
        browser.load(KitPages.main());
        browser.url = "data:member-4711";
        holder = new JPanel();
        view = new JPanel();
        holder.add(view);
        browser.component = view;
        try (Harness h = Harness.withoutEventDispatchThread(adapter).install().ready()) {
            h.ctx.spi.register(CaptureMask.class, naming(holder));
            Map<?, ?> entry = first(listed(h));
            assertEquals(Boolean.TRUE, entry.get("masked"), String.valueOf(entry));
            assertEquals("", entry.get("url"));
            String ref = (String) entry.get("ref");
            int before = browser.evals.size();
            assertEquals("masked", h.ctx.error("web.dom.text", Harness.args("browser", ref)).error().data()
                    .get("reason"));
            assertLocatorActionsRefused(h, Harness.args("browser", ref));
            assertEquals(before, browser.evals.size(), "the page is never evaluated");
            long seq = h.ctx.events.lastSeq();
            browser.fire(ai.skillroy.testjava.agent.api.spi.web.WebEvent.console("log", "member 4711", "app.js", 3));
            assertEquals(Harness.args("browser", ref, "level", "log", "text", "", "masked", Boolean.TRUE),
                    console(h, seq));
            RpcException byUrl = h.ctx.error("web.reload", Harness.args("by", Harness.args("url", "4711")));
            assertEquals(ErrorCodes.NOT_FOUND, byUrl.error().code(), "never selected by its withheld URL");
            assertFalse(String.valueOf(byUrl.error().data().get("browsers")).contains("4711"),
                    "the summary withholds the URL: " + byUrl.error());
        }
    }

    /**
     * S168: a view lost, or replaced by one inside the masked container, since the last rebuild is judged as the adapter
     * reports it now: the read is refused and the events stay masked (on the old code the stale view, now outside the
     * masked container, was judged and the read served). A {@code browser: <ref>} selection never rebuilds.
     */
    @Test
    void aViewLostOrReplacedSinceTheLastRebuildIsJudgedAsItIsNow() throws Exception {
        try (Harness h = harness()) {
            h.ctx.spi.register(CaptureMask.class, naming(holder));
            String ref = refOf(h, browser);
            assertListedMasked(first(listed(h)));
            Map<String, Object> byRef = Harness.args("browser", ref);

            holder.remove(view);
            browser.component = null;
            assertEquals("masked", h.ctx.error("web.dom.text", byRef).error().data().get("reason"),
                    "lost: no view now, masked by the view-less rule");
            long seq = h.ctx.events.lastSeq();
            browser.fire(ai.skillroy.testjava.agent.api.spi.web.WebEvent.console("log", "member text", null, 0));
            assertEquals("", console(h, seq).get("text"), "the events stay masked");

            browser.component = view;
            listed(h); // the old view, outside the holder, at a rebuild: unmasked
            h.ctx.call("web.dom.text", byRef);
            JPanel replacement = new JPanel();
            holder.add(replacement);
            browser.component = replacement;
            assertEquals("masked", h.ctx.error("web.dom.text", byRef).error().data().get("reason"),
                    "replaced by a view inside the masked holder, before any rebuild");
        }
    }

    /** A registry whose seed a test changes: the object with a tag, the object untagged, or a failure. */
    private static final class Registry implements BrowserRegistryAdapter {
        final Object vendor;
        volatile String tag;
        volatile boolean fails;

        Registry(Object vendor, String tag) {
            this.vendor = vendor;
            this.tag = tag;
        }

        @Override
        public Collection<BrowserSeed> seeds() {
            if (fails) {
                throw new IllegalStateException("the registry is unavailable");
            }
            return Collections.singletonList(BrowserSeed.of(vendor, tag));
        }
    }

    /**
     * S169: a release by tag lasts while the seed gives the tag. The registry stops tagging the view-less browser: the
     * next rebuild drops the tag and the browser is masked; the registry fails: the tag is kept, but it can no longer
     * release (on the old code both kept the release and served the page).
     */
    @Test
    void aReleaseByTagLastsWhileTheSeedGivesTheTag() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        viewless = adapter.add(new ScriptedBrowser("scripted"));
        viewless.load(KitPages.main());
        viewless.url = "data:detached-url";
        viewless.component = null;
        try (Harness h = new Harness(adapter).install()) {
            Registry registry = new Registry(viewless.vendorObject(), "detached");
            h.ctx.spi.register(BrowserRegistryAdapter.class, registry);
            h.ready();
            h.ctx.spi.register(CaptureMask.class, naming(new JPanel()));
            h.ctx.spi.register(BrowserMask.class, byTag("detached", Boolean.FALSE));
            Map<?, ?> released = first(listed(h));
            assertFalse(released.containsKey("masked"), "released by its tag: " + released);
            String ref = (String) released.get("ref");
            h.ctx.call("web.dom.text", Harness.args("browser", ref));

            registry.tag = null;
            Map<?, ?> untagged = first(listed(h));
            assertFalse(untagged.containsKey("tag"), "no seed gives the tag any more: " + untagged);
            assertEquals(Boolean.TRUE, untagged.get("masked"), String.valueOf(untagged));
            assertEquals("", untagged.get("url"));
            assertEquals("masked", h.ctx.error("web.dom.text", Harness.args("browser", ref)).error().data()
                    .get("reason"));

            registry.tag = "detached";
            assertFalse(first(listed(h)).containsKey("masked"), "tagged again: released again");
            registry.fails = true;
            Map<?, ?> unconfirmed = first(listed(h));
            assertEquals("detached", unconfirmed.get("tag"), "the tag is kept while the registry fails");
            assertEquals(Boolean.TRUE, unconfirmed.get("masked"), String.valueOf(unconfirmed));
            assertEquals("", unconfirmed.get("url"));
            assertEquals("masked", h.ctx.error("web.dom.text", Harness.args("browser", ref)).error().data()
                    .get("reason"), "a kept tag can mask, never release");
            registry.fails = false;
            assertFalse(first(listed(h)).containsKey("masked"), "confirmed again once every registry answers");
        }
    }

    /**
     * S172: a browser that becomes masked while open (a mask registered after the last judgement) is judged afresh by a
     * {@code by: {url}} selection before its regex runs: never selected by its withheld URL, even by a primitive that
     * judges no mask itself ({@code web.reload}), and the error data withholds the URL (on the old code the stale flag
     * let it match).
     */
    @Test
    void aBrowserThatBecomesMaskedIsNeverSelectedByItsWithheldUrl() throws Exception {
        try (Harness h = harness()) {
            browser.url = "data:member-4711";
            h.ctx.call("web.reload", Harness.args("by", Harness.args("url", "4711")));
            h.ctx.spi.register(CaptureMask.class, naming(holder));
            RpcException refused = h.ctx.error("web.reload", Harness.args("by", Harness.args("url", "4711")));
            assertEquals(ErrorCodes.NOT_FOUND, refused.error().code());
            assertFalse(String.valueOf(refused.error().data().get("browsers")).contains("4711"),
                    "the summary withholds the URL: " + refused.error());
        }
    }

    /**
     * S172: a browser that becomes masked while open (its view moved into a masked container, with no call meanwhile)
     * stops sending page data once its events have asked the index to judge the flags again (here at once: the
     * module's re-judgement threshold lowered from {@link BrowserIndex#STALE_MS}); the event that asked carries the
     * flag as it was (the window the contract states). On the old code nothing judged it again.
     */
    @Test
    void aBrowserThatBecomesMaskedStopsSendingPageDataWithoutACall() throws Exception {
        try (Harness h = harness()) {
            String ref = refOf(h, browser);
            JPanel masked = new JPanel();
            h.ctx.spi.register(CaptureMask.class, naming(masked));
            listed(h);
            long seq = h.ctx.events.lastSeq();
            browser.fire(ai.skillroy.testjava.agent.api.spi.web.WebEvent.console("log", "before", null, 0));
            assertEquals("before", console(h, seq).get("text"), "the control: unmasked");

            holder.remove(view);
            masked.add(view);
            h.module.indexForTest().rejudgeAfterMs = 0;
            int reads = h.walk.maskedReads.get();
            seq = h.ctx.events.lastSeq();
            browser.fire(ai.skillroy.testjava.agent.api.spi.web.WebEvent.console("log", "first", null, 0));
            console(h, seq);
            long deadline = System.currentTimeMillis() + 5000;
            while (h.walk.maskedReads.get() == reads && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(h.walk.maskedReads.get() > reads, "the event asked for one re-judgement on a worker");
            seq = h.ctx.events.lastSeq();
            browser.fire(ai.skillroy.testjava.agent.api.spi.web.WebEvent.console("log", "member 4711", null, 0));
            assertEquals(Harness.args("browser", ref, "level", "log", "text", "", "masked", Boolean.TRUE),
                    console(h, seq));
        }
    }
}
