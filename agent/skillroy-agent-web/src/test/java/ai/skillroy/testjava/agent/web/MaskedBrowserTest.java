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
     * (i) D637: the actions that stay allowed on a masked browser answer without page data: {@code web.pointer} without
     * {@code hit}, {@code web.dom.click}/{@code web.dom.fill} with {@code role} and {@code tag} only, the
     * {@code AMBIGUOUS} candidates likewise with {@code count} kept; {@code redact: false} keeps today's answers.
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

            h.ctx.spi.register(CaptureMask.class, naming(view));
            Map<String, Object> masked = h.ctx.map("web.pointer", new java.util.LinkedHashMap<String, Object>(
                    pointer));
            assertFalse(masked.containsKey("hit"), String.valueOf(masked));
            assertEquals(control.get("dispatched"), masked.get("dispatched"), "the action itself goes on");
            Map<String, Object> keep = new java.util.LinkedHashMap<String, Object>(pointer);
            keep.put("redact", Boolean.FALSE);
            assertTrue(h.ctx.map("web.pointer", keep).containsKey("hit"), "redact: false keeps the hit");

            Map<String, Object> clicked = h.ctx.map("web.dom.click", Harness.args("selector", "#count"));
            assertEquals(Harness.args("role", "button", "tag", "button"), clicked.get("clicked"));
            assertEquals(Long.valueOf(1), clicked.get("matched"));
            Map<String, Object> filled = h.ctx.map("web.dom.fill", Harness.args("selector", "#count", "text", "x"));
            assertEquals(Harness.args("tag", "input"), filled.get("filled"));
            assertEquals(Long.valueOf(1), filled.get("typed"));
            Map<String, Object> unredacted = h.ctx.map("web.dom.click", Harness.args("selector", "#count", "redact",
                    Boolean.FALSE));
            assertEquals("count", ((Map<?, ?>) unredacted.get("clicked")).get("id"), "redact: false keeps the id");

            final Map<String, Object> ambiguous = Harness.args("__error", "AMBIGUOUS", "data", Harness.args(
                    "candidates", Arrays.asList(Harness.args("role", "button", "name", "Count", "tag", "button",
                            "id", "count"), Harness.args("role", "heading", "name", "Kit main", "tag", "h1")),
                    "count", Long.valueOf(25)));
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    return CompletableFuture.completedFuture(Harness.guarded(ambiguous));
                }
            };
            RpcException amb = h.ctx.error("web.dom.click", Harness.args("selector", "*"));
            assertEquals(ErrorCodes.AMBIGUOUS, amb.error().code());
            assertEquals(Long.valueOf(25), amb.error().data().get("count"));
            assertEquals(Arrays.asList(Harness.args("role", "button", "tag", "button"), Harness.args("role",
                    "heading", "tag", "h1")), amb.error().data().get("candidates"));
            assertFalse(String.valueOf(amb.error().data()).contains("Kit main"), String.valueOf(amb.error()));
            RpcException kept = h.ctx.error("web.dom.click", Harness.args("selector", "*", "redact", Boolean.FALSE));
            assertTrue(String.valueOf(kept.error().data()).contains("Kit main"), "redact: false keeps them");
        }
    }
}
