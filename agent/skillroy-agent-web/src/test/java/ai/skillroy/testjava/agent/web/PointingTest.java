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

import ai.skillroy.testjava.agent.api.spi.SurfaceSource;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent;
import ai.skillroy.testjava.agent.web.kit.KitPages;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code web.hitTest} and {@code web.pointer} through the module (phase7-contracts.md sections 4.2, 4.3, 4.8; section
 * 13.1 item 3): over the fake page's layout of the kit's main page, a scripted descent into frames, the component
 * conversion and its refusal under a viewport override, the modal check on a fake dialog, the event plan, the
 * balancing release, read-only mode, and the module's {@code SurfaceSource}.
 */
class PointingTest {

    private ScriptedAdapter adapter;
    private ScriptedBrowser browser;
    private JPanel view;

    private Harness harness(boolean showing) throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(new ScriptedBrowser("scripted"));
        browser.capabilities = withInput(browser.capabilities);
        browser.load(KitPages.main());
        view = new JPanel();
        browser.component = view;
        Harness h = new Harness(adapter).install();
        h.walk.components.add(view);
        if (showing) {
            h.walk.showing.add(view);
        }
        return h.ready();
    }

    private static EnumSet<WebCapability> withInput(java.util.Set<WebCapability> flags) {
        EnumSet<WebCapability> out = EnumSet.copyOf(flags);
        out.add(WebCapability.INPUT);
        return out;
    }

    private static Map<String, Object> point(long x, long y) {
        return Harness.args("x", Long.valueOf(x), "y", Long.valueOf(y));
    }

    private static Map<String, Object> rect(long x, long y, long w, long h) {
        return Harness.args("x", Long.valueOf(x), "y", Long.valueOf(y), "w", Long.valueOf(w), "h", Long.valueOf(h));
    }

    // ------------------------------------------------------------------------------------------------ hit-test

    @Test
    void aHitTestAnswersTheElementItsRectangleAndItsLocators() throws Exception {
        try (Harness h = harness(true)) {
            Map<String, Object> hit = h.ctx.map("web.hitTest", Harness.args("point", point(84, 110)));
            assertEquals(Harness.args("x", 84L, "y", 110L), hit.get("viewportPoint"));
            Map<?, ?> element = (Map<?, ?>) hit.get("element");
            assertEquals("div", element.get("tag"));
            assertEquals(rect(24, 80, 120, 60), element.get("rect"));
            assertEquals(Arrays.asList(Harness.args("selector", "div.block")), hit.get("locators"));
            assertEquals(Boolean.TRUE, hit.get("unique"));
            assertTrue(String.valueOf(hit.get("browser")).length() > 0);
            assertFalse(hit.containsKey("frame"), "no frame key without frames");
            assertFalse(hit.containsKey("scaleAssumed"), "a viewport point");
            Map<String, Object> button = h.ctx.map("web.hitTest", Harness.args("point", point(72, 216)));
            assertEquals(Arrays.asList(Harness.args("role", "button", "name", "Count"),
                    Harness.args("selector", "#count")), button.get("locators"));
            String sent = browser.evals.get(browser.evals.size() - 1).expression();
            assertTrue(sent.startsWith(WebLib.boundedOpen()) && sent.contains(".hit(72,216)"), "the bounded hit");
        }
    }

    @Test
    void aPointOutsideTheViewportIsNotFoundAndAMalformedOneInvalid() throws Exception {
        try (Harness h = harness(true)) {
            RpcException outside = h.ctx.error("web.hitTest", Harness.args("point", point(900, 10)));
            assertEquals(ErrorCodes.NOT_FOUND, outside.error().code());
            assertEquals("outside", outside.error().data().get("reason"));
            assertTrue(outside.error().data().containsKey("browser"));
            RpcException bad = h.ctx.error("web.hitTest", Harness.args("point", "84,110"));
            assertEquals(ErrorCodes.INVALID_PARAMS, bad.error().code());
            assertEquals("point", bad.error().data().get("param"));
            RpcException space = h.ctx.error("web.hitTest", Harness.args("point", point(1, 1), "space", "SCREEN"));
            assertEquals("space", space.error().data().get("param"));
        }
    }

    @Test
    void aComponentPointIsConvertedByTheWidthFactorAndRefusedUnderAnOverride() throws Exception {
        try (Harness h = harness(true)) {
            h.walk.state(view, 800, 600, 1.0, null, null);
            Map<String, Object> same = h.ctx.map("web.hitTest", Harness.args("point", point(84, 110), "space",
                    "COMPONENT"));
            assertEquals(Harness.args("x", 84L, "y", 110L), same.get("viewportPoint"), "1 CSS pixel = 1 pixel");
            assertEquals(Boolean.FALSE, same.get("scaleAssumed"));
            h.walk.state(view, 400, 300, 2.0, null, null);
            Map<String, Object> scaled = h.ctx.map("web.hitTest", Harness.args("point", point(42, 55), "space",
                    "COMPONENT"));
            assertEquals(Harness.args("x", 84L, "y", 110L), scaled.get("viewportPoint"), "innerWidth / width = 2");
            assertEquals("div", ((Map<?, ?>) scaled.get("element")).get("tag"));
            assertEquals(Boolean.TRUE, scaled.get("scaleAssumed"), "the screen's scale is not 1");

            h.ctx.call("web.resize", Harness.args("width", 640L, "height", 480L));
            RpcException refused = h.ctx.error("web.hitTest", Harness.args("point", point(1, 1), "space",
                    "COMPONENT"));
            assertEquals(ErrorCodes.INVALID_PARAMS, refused.error().code());
            assertEquals("viewport override", refused.error().data().get("reason"));
            h.ctx.map("web.hitTest", Harness.args("point", point(1, 1)));
            h.ctx.call("web.resize", Harness.args("clear", Boolean.TRUE));
            h.ctx.map("web.hitTest", Harness.args("point", point(1, 1), "space", "COMPONENT"));

            browser.component = null;
            h.ctx.call("web.browsers", Harness.args());
            RpcException viewless = h.ctx.error("web.hitTest", Harness.args("by", Harness.args("url", "^about"),
                    "point", point(1, 1), "space", "COMPONENT"));
            assertEquals("no component", viewless.error().data().get("reason"));
        }
    }

    @Test
    void aFrameElementIsDescendedIntoAndTheRectanglesAreInTheStartingViewport() throws Exception {
        try (Harness h = harness(true)) {
            browser.frames = Arrays.asList(new WebFrame("M", null, "", "data:main", true),
                    new WebFrame("F2", "M", "inner", "data:inner", false));
            final Map<String, Object> frameElement = Harness.args("element", Harness.args("role", "generic", "name", "",
                    "tag", "iframe", "rect", rect(10, 300, 200, 100)), "locators",
                    Arrays.asList(Harness.args("selector", "iframe")), "unique", Boolean.TRUE, "child",
                    Harness.args("x", 30L, "y", 20L, "name", "inner", "url", "data:inner"));
            final Map<String, Object> inner = Harness.args("element", Harness.args("role", "button", "name", "In",
                    "tag", "button", "rect", rect(25, 15, 20, 10)), "locators",
                    Arrays.asList(Harness.args("role", "button", "name", "In")), "unique", Boolean.TRUE);
            final List<String> frames = new ArrayList<String>();
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    frames.add(String.valueOf(eval.frameKey()));
                    return CompletableFuture.completedFuture(Harness.guarded(eval.frameKey() == null ? frameElement
                            : inner));
                }
            };
            Map<String, Object> hit = h.ctx.map("web.hitTest", Harness.args("point", point(42, 322)));
            assertEquals(Arrays.asList("null", "F2"), frames, "the main frame, then the child the name names");
            assertEquals("F2", hit.get("frame"));
            assertEquals(Harness.args("key", "F2", "rect", rect(10, 300, 200, 100)), hit.get("iframe"));
            assertEquals(rect(37, 317, 20, 10), ((Map<?, ?>) hit.get("element")).get("rect"),
                    "moved by the child viewport's origin, (12, 302)");
            assertEquals(Harness.args("x", 42L, "y", 322L), hit.get("viewportPoint"));
        }
    }

    @Test
    void theDescentStopsAtDepthThree() throws Exception {
        try (Harness h = harness(true)) {
            browser.frames = Arrays.asList(new WebFrame("F0", null, "", "u0", true), new WebFrame("F1", "F0", "", "u1",
                    false), new WebFrame("F2", "F1", "", "u2", false), new WebFrame("F3", "F2", "", "u3", false),
                    new WebFrame("F4", "F3", "", "u4", false));
            final List<String> asked = new ArrayList<String>();
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    asked.add(String.valueOf(eval.frameKey()));
                    return CompletableFuture.completedFuture(Harness.guarded(Harness.args("element",
                            Harness.args("role", "generic", "name", "", "tag", "iframe", "rect", rect(0, 0, 50, 50)),
                            "locators", new ArrayList<Object>(), "unique", Boolean.FALSE, "child",
                            Harness.args("x", 5L, "y", 5L, "name", "", "url", ""))));
                }
            };
            Map<String, Object> hit = h.ctx.map("web.hitTest", Harness.args("point", point(10, 10)));
            assertEquals(Arrays.asList("null", "F1", "F2", "F3"), asked);
            assertEquals("F3", hit.get("frame"));
            assertEquals("iframe", ((Map<?, ?>) hit.get("element")).get("tag"), "the frame element at depth 3");
            assertEquals(Boolean.FALSE, hit.get("unique"));
        }
    }

    // ------------------------------------------------------------------------------------------------ pointer

    @Test
    void aClickIsAMoveAPressAndAReleaseAndThePageCountsFourTrustedEvents() throws Exception {
        try (Harness h = harness(true)) {
            Map<String, Object> click = h.ctx.map("web.pointer", Harness.args("action", "CLICK", "point",
                    point(72, 216)));
            assertEquals("VENDOR", click.get("via"), "the SPI's default source, as web.bitmap reports it");
            assertEquals(Harness.args("x", 72L, "y", 216L), click.get("viewportPoint"));
            assertEquals(Arrays.asList("mouseMoved", "mousePressed", "mouseReleased"), click.get("dispatched"));
            assertEquals(Harness.args("role", "button", "name", "Count", "tag", "button", "id", "count"),
                    click.get("hit"));
            assertFalse(click.containsKey("visibleInSwing"), "a showing browser");
            assertEquals(Arrays.asList(WebPointerEvent.of(WebPointerEvent.Action.MOVE, 72, 216).build(),
                    WebPointerEvent.of(WebPointerEvent.Action.PRESS, 72, 216).build(),
                    WebPointerEvent.of(WebPointerEvent.Action.RELEASE, 72, 216).build()), browser.pointerEvents);
            Map<?, ?> listed = (Map<?, ?>) ((List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers")).get(0);
            assertFalse(listed.containsKey("blockedBy"), "an unblocked browser's entry is as before");
            assertEquals("pointerdown:true,mousedown:true,mouseup:true,click:true",
                    h.ctx.map("web.evaluate", Harness.args("expression", ai.skillroy.testjava.agent.web.kit.KitScripts
                            .POINTER_EVENTS)).get("json").toString().replace("\"", ""));
        }
    }

    /**
     * Gap 104's listener, Phase 8 security fix S171 (FE): the expression is self-bounding as S131 requires of every raw
     * page evaluation (one {@code try} around everything, the constant 1 as its only answer), and the listener removes
     * itself on a trusted middle {@code mouseup} only. A person's middle click evaluates it once before the sequence;
     * a page whose evaluation of it fails with a long text changes nothing of the click's answer.
     */
    @Test
    void theMiddleClickListenerIsSelfBoundingAndLeavesOnATrustedEventOnly() throws Exception {
        String e = Pointing.NO_PRIMARY_PASTE;
        assertTrue(e.startsWith("(function(){try{"), e);
        assertTrue(e.endsWith("}catch(x){}return 1;})()"), e);
        assertEquals(1, e.split("return ", -1).length - 1, "the constant is the expression's only answer: " + e);
        assertTrue(e.contains("if(e.isTrusted){w.removeEventListener('mouseup',h,true);}"), e);
        try (Harness h = harness(true)) {
            final String longText = new String(new char[200000]).replace('\0', 'x');
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    if (Pointing.NO_PRIMARY_PASTE.equals(eval.expression())) {
                        CompletableFuture<String> f = new CompletableFuture<String>();
                        f.completeExceptionally(new ai.skillroy.testjava.agent.api.spi.web.WebScriptException(
                                longText, ""));
                        return f;
                    }
                    return browser.byDocument(eval);
                }
            };
            Map<String, Object> click = h.ctx.map("web.pointer", Harness.args("action", "CLICK", "point",
                    point(10, 10), "button", "MIDDLE"));
            assertEquals(Arrays.asList("mouseMoved", "mousePressed", "mouseReleased"), click.get("dispatched"));
            assertFalse(String.valueOf(click).contains("xxxx"), "nothing of the page's text in the answer");
            int listeners = 0;
            for (WebEval eval : browser.evals) {
                listeners += Pointing.NO_PRIMARY_PASTE.equals(eval.expression()) ? 1 : 0;
            }
            assertEquals(1, listeners, "evaluated once, before the sequence");
        }
    }

    @Test
    void theOtherActionsSendTheirPlan() throws Exception {
        try (Harness h = harness(true)) {
            h.ctx.map("web.pointer", Harness.args("action", "DOUBLE_CLICK", "point", point(10, 10), "button",
                    "RIGHT", "modifiers", Arrays.asList("SHIFT", "CTRL")));
            assertEquals(5, browser.pointerEvents.size());
            int[] counts = {0, 1, 1, 2, 2};
            for (int i = 0; i < 5; i++) {
                WebPointerEvent e = browser.pointerEvents.get(i);
                assertEquals(counts[i], e.clickCount(), "event " + i);
                assertEquals(EnumSet.of(WebPointerEvent.Modifier.CTRL, WebPointerEvent.Modifier.SHIFT),
                        e.modifiers());
                assertEquals(i == 0 ? WebPointerEvent.Button.NONE : WebPointerEvent.Button.RIGHT, e.button());
            }
            browser.pointerEvents.clear();
            Map<String, Object> wheel = h.ctx.map("web.pointer", Harness.args("action", "WHEEL", "point",
                    point(10, 10), "wheel", Harness.args("dx", 0L, "dy", 120L)));
            assertEquals(Arrays.asList("mouseMoved", "mouseWheel"), wheel.get("dispatched"));
            assertEquals(120, browser.pointerEvents.get(1).deltaY());
            browser.pointerEvents.clear();
            h.ctx.map("web.pointer", Harness.args("action", "MOVE", "point", point(10, 10)));
            h.ctx.map("web.pointer", Harness.args("action", "PRESS", "point", point(10, 10)));
            h.ctx.map("web.pointer", Harness.args("action", "RELEASE", "point", point(10, 10)));
            assertEquals(5, browser.pointerEvents.size(), "move; move, press; move, release");
            RpcException bad = h.ctx.error("web.pointer", Harness.args("action", "DRAG", "point", point(1, 1)));
            assertEquals("action", bad.error().data().get("param"));
        }
    }

    @Test
    void aModalDialogRefusesThePointerUnlessForceAndAHiddenBrowserIsServed() throws Exception {
        try (Harness h = harness(false)) {
            Object dialog = new Object();
            h.walk.state(view, 800, 600, 1.0, dialog, "Confirm loan");
            RpcException blocked = h.ctx.error("web.pointer", Harness.args("by", Harness.args("url", "^about"),
                    "action", "CLICK", "point", point(72, 216)));
            assertEquals(ErrorCodes.MODAL_OPEN, blocked.error().code());
            assertEquals("Confirm loan", blocked.error().data().get("title"));
            assertEquals(h.ctx.handles.handleFor(dialog, "component"), blocked.error().data().get("dialog"));
            assertTrue(blocked.error().data().containsKey("browser"));
            assertTrue(browser.pointerEvents.isEmpty(), "nothing was sent");
            Map<?, ?> listed = (Map<?, ?>) ((List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers")).get(0);
            assertEquals(Harness.args("dialog", h.ctx.handles.handleFor(dialog, "component"), "title", "Confirm loan"),
                    listed.get("blockedBy"), "web.browsers reports the modal dialog, for the CDP path");
            Map<String, Object> forced = h.ctx.map("web.pointer", Harness.args("by", Harness.args("url", "^about"),
                    "action", "CLICK", "point", point(72, 216), "force", Boolean.TRUE));
            assertEquals(3, browser.pointerEvents.size());
            assertEquals(Boolean.FALSE, forced.get("visibleInSwing"), "a browser whose component is not showing");
            h.ctx.map("web.hitTest", Harness.args("by", Harness.args("url", "^about"), "point", point(72, 216)));
        }
    }

    @Test
    void aPointOutsideTheViewportSendsNothing() throws Exception {
        try (Harness h = harness(true)) {
            RpcException outside = h.ctx.error("web.pointer", Harness.args("action", "CLICK", "point",
                    point(900, 10)));
            assertEquals(ErrorCodes.NOT_FOUND, outside.error().code());
            assertEquals("outside", outside.error().data().get("reason"));
            assertTrue(browser.pointerEvents.isEmpty());
        }
    }

    @Test
    void aFailureAfterThePressReleasesTheButtonAndAnswersTheFailure() throws Exception {
        try (Harness h = harness(true)) {
            browser.failDispatchAt = 2;
            RpcException failed = h.ctx.error("web.pointer", Harness.args("action", "CLICK", "point",
                    point(72, 216)));
            assertEquals(ErrorCodes.INTERNAL_ERROR, failed.error().code(), failed.getMessage());
            assertEquals(4, browser.pointerEvents.size(), "move, press, the failed release, the balancing release");
            WebPointerEvent balance = browser.pointerEvents.get(3);
            assertEquals(WebPointerEvent.Action.RELEASE, balance.action());
            assertEquals(WebPointerEvent.Button.LEFT, balance.button());
            browser.pointerEvents.clear();
            browser.failDispatchAt = 0;
            h.ctx.error("web.pointer", Harness.args("action", "CLICK", "point", point(72, 216)));
            assertEquals(1, browser.pointerEvents.size(), "no press, nothing to balance");
        }
    }

    @Test
    void aBrowserWithoutInputIsUnavailableAndReadOnlyRefusesThePointerOnly() throws Exception {
        try (Harness h = harness(true)) {
            ScriptedBrowser plain = adapter.add(new ScriptedBrowser("scripted"));
            plain.url = "data:text/html,plain";
            h.ctx.call("web.browsers", Harness.args());
            RpcException missing = h.ctx.error("web.pointer", Harness.args("by", Harness.args("url", "plain"),
                    "action", "MOVE", "point", point(1, 1)));
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, missing.error().code());
            assertEquals("web.pointer", missing.error().data().get("capability"));
            h.ctx.readOnly = true;
            Map<String, Object> select = Harness.args("by", Harness.args("url", "^about"));
            Map<String, Object> pointer = new LinkedHashMap<String, Object>(select);
            pointer.put("action", "CLICK");
            pointer.put("point", point(72, 216));
            assertEquals(ErrorCodes.READ_ONLY, h.ctx.error("web.pointer", pointer).error().code());
            Map<String, Object> hit = new LinkedHashMap<String, Object>(select);
            hit.put("point", point(72, 216));
            assertEquals("button", ((Map<?, ?>) h.ctx.map("web.hitTest", hit).get("element")).get("tag"));
        }
    }

    // ------------------------------------------------------------------------------------------------ surfaces

    @Test
    void theSurfaceSourceAnswersFromTheIndex() throws Exception {
        try (Harness h = harness(true)) {
            List<SurfaceSource> registered = h.ctx.spi.all(SurfaceSource.class);
            assertEquals(1, registered.size(), "registered at install");
            assertTrue(registered.get(0).surfaces().isEmpty(), "a component in no window (a headless test JVM)");
            final List<Object> inWindow = new ArrayList<Object>();
            inWindow.add(view);
            WebSurfaces surfaces = new WebSurfaces(h.module.indexForTest(), new WebSurfaces.Placement() {
                @Override
                public boolean inWindow(Object component) {
                    return inWindow.contains(component);
                }
            });
            int evaluations = browser.evals.size();
            List<SurfaceSource.Surface> found = surfaces.surfaces();
            assertEquals(1, found.size());
            assertEquals("WEB", found.get(0).kind);
            assertTrue(found.get(0).component == view);
            assertEquals(h.module.indexForTest().snapshot().entries.get(0).ref, found.get(0).browserRef);
            assertEquals(evaluations, browser.evals.size(), "no browser was asked");
            inWindow.clear();
            assertTrue(surfaces.surfaces().isEmpty(), "not in a window");
            inWindow.add(view);
            h.module.indexForTest().snapshot().entries.get(0).closedPublished.set(true);
            assertTrue(surfaces.surfaces().isEmpty(), "a browser seen closed");
        }
    }

    @Test
    void theSurfaceSourceIsWithdrawnAtUninstall() throws Exception {
        Harness h = harness(true);
        h.close();
        assertTrue(h.ctx.spi.all(SurfaceSource.class).isEmpty());
    }
}
