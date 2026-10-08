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

import ai.skillroy.testjava.agent.api.spi.web.BitmapSource;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code web.resize}, {@code web.bitmap}, {@code web.reload}, {@code web.cdp.status} and the shape of
 * {@code web.browsers} (phase4-contracts.md section 4.2), including the CDP target id kept from an earlier read when
 * a later read cannot be answered (a frozen renderer cannot answer {@code Page.getFrameTree}; the CDP path's
 * frozen-target recovery needs the id). {@code web.reload} and the bitmap's {@code source} go through the SPI's
 * {@code WebBrowser.reload} and {@code bitmapSource()} (A37): an adapter's override, else the SPI's defaults.
 */
class ViewPrimitivesTest {

    /** A browser whose adapter overrides the SPI's reload and bitmap source, as the built-in adapters do (A37). */
    static final class OverridingBrowser extends ScriptedBrowser {
        final List<Boolean> reloads = new ArrayList<Boolean>();
        volatile BitmapSource source = BitmapSource.PROTOCOL;
        volatile RuntimeException sourceFails;

        OverridingBrowser() {
            super("scripted");
        }

        @Override
        public CompletableFuture<Void> reload(boolean ignoreCache) {
            reloads.add(Boolean.valueOf(ignoreCache));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public BitmapSource bitmapSource() {
            RuntimeException fails = sourceFails;
            if (fails != null) {
                throw fails;
            }
            return source;
        }
    }

    private ScriptedAdapter adapter;
    private ScriptedBrowser browser;

    private Harness harness(ScriptedBrowser b) throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(b);
        JPanel view = new JPanel();
        browser.component = view;
        Harness h = new Harness(adapter).install();
        h.walk.components.add(view);
        h.walk.showing.add(view);
        return h.ready();
    }

    @Test
    void resizeAppliesAnOverrideAndClearRemovesIt() throws Exception {
        try (Harness h = harness(new ScriptedBrowser("scripted"))) {
            Map<String, Object> out = h.ctx.map("web.resize", Harness.args("width", Long.valueOf(1024), "height",
                    Long.valueOf(768)));
            assertEquals("OVERRIDE", out.get("applied"));
            assertEquals(Harness.args("w", Long.valueOf(1024), "h", Long.valueOf(768)), out.get("viewport"));
            assertEquals(1, browser.resizes.size());
            Map<String, Object> cleared = h.ctx.map("web.resize", Harness.args("clear", Boolean.TRUE));
            assertEquals("OVERRIDE", cleared.get("applied"));
            assertEquals(1, browser.clears.get());
            for (Map<String, Object> bad : Arrays.asList(Harness.args("width", Long.valueOf(0), "height",
                    Long.valueOf(10)), Harness.args("width", Long.valueOf(10), "height", Long.valueOf(16385)),
                    Harness.args("width", Long.valueOf(10)), Harness.args("clear", Boolean.TRUE, "width",
                    Long.valueOf(10), "height", Long.valueOf(10)))) {
                assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.resize", bad).error().code(), bad.toString());
            }
        }
    }

    @Test
    void bitmapIsScaledAboveMaxWidthEncodedAsPngAndNamesItsSource() throws Exception {
        ScriptedBrowser plain = new ScriptedBrowser("scripted");
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(32, 96, 160));
        g.fillRect(0, 0, 800, 600);
        g.dispose();
        plain.image = image;
        try (Harness h = harness(plain)) {
            Map<String, Object> out = h.ctx.map("web.bitmap", Harness.args("maxWidth", Long.valueOf(400)));
            assertEquals("png", out.get("format"));
            assertEquals(Long.valueOf(400), out.get("width"));
            assertEquals(Long.valueOf(300), out.get("height"));
            assertEquals("VENDOR", out.get("source"), "a third-party adapter's bitmap");
            BufferedImage png = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(
                    (String) out.get("base64"))));
            assertEquals(400, png.getWidth());
            assertEquals((32 << 16) | (96 << 8) | 160, png.getRGB(10, 10) & 0xffffff);
            Map<String, Object> full = h.ctx.map("web.bitmap", Harness.args());
            assertEquals(Long.valueOf(800), full.get("width"), "4096 by default: not scaled");
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.bitmap", Harness.args("maxWidth",
                    Long.valueOf(0))).error().code());
        }
        OverridingBrowser overriding = new OverridingBrowser();
        try (Harness h = harness(overriding)) {
            assertEquals("PROTOCOL", h.ctx.map("web.bitmap", Harness.args()).get("source"), "the adapter's own");
            overriding.source = null;
            assertEquals("VENDOR", h.ctx.map("web.bitmap", Harness.args()).get("source"), "null: the default");
            overriding.sourceFails = new IllegalStateException("an adapter's defect");
            assertEquals("VENDOR", h.ctx.map("web.bitmap", Harness.args()).get("source"), "a throw: the default");
        }
    }

    @Test
    void reloadGoesThroughTheAdaptersOverrideOrTheSpisDefaultLocationReload() throws Exception {
        OverridingBrowser overriding = new OverridingBrowser();
        try (Harness h = harness(overriding)) {
            int evals = overriding.evals.size();
            assertEquals(Boolean.TRUE, h.ctx.map("web.reload", Harness.args("ignoreCache", Boolean.TRUE))
                    .get("reloaded"));
            h.ctx.call("web.reload", Harness.args());
            assertEquals(Arrays.asList(Boolean.TRUE, Boolean.FALSE), overriding.reloads);
            assertEquals(evals, overriding.evals.size(), "the override does not go through the page");
        }
        try (Harness h = harness(new ScriptedBrowser("scripted"))) {
            assertEquals(Boolean.TRUE, h.ctx.map("web.reload", Harness.args()).get("reloaded"));
            WebEval last = browser.evals.get(browser.evals.size() - 1);
            assertEquals("location.reload()", last.expression(), "the SPI's default");
            h.ctx.call("web.reload", Harness.args("ignoreCache", Boolean.TRUE));
            last = browser.evals.get(browser.evals.size() - 1);
            assertEquals("location.reload(true)", last.expression());
            assertEquals(null, last.frameKey(), "the main frame");
        }
    }

    @Test
    void aDefaultReloadThatCannotEvaluateIsUnavailableAndOneThatHangsTimesOutAndIsAbandoned() throws Exception {
        try (Harness h = harness(new ScriptedBrowser("scripted"))) {
            browser.capabilities = EnumSet.of(WebCapability.EVENTS_LOAD, WebCapability.BITMAP);
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    CompletableFuture<String> f = new CompletableFuture<String>();
                    f.completeExceptionally(new UnsupportedOperationException("no EVALUATE"));
                    return f;
                }
            };
            RpcException refused = h.ctx.error("web.reload", Harness.args());
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, refused.error().code());
            assertTrue(String.valueOf(refused.error().data()).contains("no EVALUATE"), refused.error().toString());
            browser.capabilities = EnumSet.of(WebCapability.EVALUATE, WebCapability.EVENTS_LOAD);
            browser.handedOut.clear();
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    return new CompletableFuture<String>();
                }
            };
            RpcException hung = h.ctx.error("web.reload", Harness.args(), Harness.args("timeoutMs",
                    Long.valueOf(700)));
            assertEquals(ErrorCodes.TIMEOUT, hung.error().code());
            assertEquals("web.reload", hung.error().data().get("primitive"));
            assertEquals(1, browser.handedOut.size());
            assertTrue(browser.handedOut.get(0).isCancelled(), "the abandoned reload abandoned its evaluation");
        }
    }

    @Test
    void cdpStatusListsTheConfiguredEndpointsAndTheTargetIds() throws Exception {
        try (Harness h = harness(new ScriptedBrowser("scripted"))) {
            adapter.endpoints.add(new CdpEndpoint("127.0.0.1", 9229, "scripted"));
            String ref = BrowserIndexTest.ref(h, browser);
            Map<String, Object> status = h.ctx.map("web.cdp.status", Harness.args());
            assertEquals(Arrays.asList(Harness.args("host", "127.0.0.1", "port", Long.valueOf(9229), "engineLabel",
                    "scripted", "adapter", "scripted")), status.get("endpoints"));
            assertEquals(Arrays.asList(Harness.args("ref", ref, "cdpTargetId", "TARGET-1")), status.get("browsers"));
        }
    }

    @Test
    void webBrowsersListsTheContractsShape() throws Exception {
        ScriptedBrowser b = new ScriptedBrowser("scripted");
        b.url = "app://front/";
        b.nativeId = "7";
        b.rendering = WebRendering.OFFSCREEN_HEAVYWEIGHT;
        b.frames = Arrays.asList(new WebFrame("MAIN", null, "", "app://front/", true),
                new WebFrame("F1", "MAIN", "inner", "data:x", false));
        b.load("<html><head><title>Front page</title></head><body></body></html>");
        try (Harness h = harness(b)) {
            List<?> list = (List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers");
            Map<?, ?> m = (Map<?, ?>) list.get(0);
            assertEquals(Arrays.asList("ref", "adapter", "nativeId", "url", "title", "closed", "visibleInSwing",
                    "component", "rendering", "viewport", "capabilities", "cdpTargetId", "frames"),
                    new ArrayList<Object>(m.keySet()));
            assertEquals("scripted", m.get("adapter"));
            assertEquals("7", m.get("nativeId"));
            assertEquals("Front page", m.get("title"));
            assertEquals(Boolean.FALSE, m.get("closed"));
            assertEquals(Boolean.TRUE, m.get("visibleInSwing"));
            assertEquals("OFFSCREEN_HEAVYWEIGHT", m.get("rendering"));
            assertEquals(Harness.args("w", Long.valueOf(800), "h", Long.valueOf(600)), m.get("viewport"));
            assertEquals(Arrays.asList("EVALUATE", "AWAIT_PROMISE", "FRAMES", "EVENTS_LOAD", "EVENTS_CONSOLE",
                    "INIT_SCRIPT_DOCUMENT_START", "RESIZE", "BITMAP", "CDP_TARGET_ID"), m.get("capabilities"));
            assertEquals("TARGET-1", m.get("cdpTargetId"));
            assertEquals(Arrays.asList(Harness.args("key", "MAIN", "name", "", "url", "app://front/", "main",
                    Boolean.TRUE), Harness.args("key", "F1", "parentKey", "MAIN", "name", "inner", "url", "data:x",
                    "main", Boolean.FALSE)), m.get("frames"));
            b.capabilities = EnumSet.of(WebCapability.RESIZE);
            Map<?, ?> bare = (Map<?, ?>) ((List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers"))
                    .get(0);
            assertFalse(bare.containsKey("title"), "no title without EVALUATE");
            assertFalse(bare.containsKey("frames"), "no frames without FRAMES");
            assertFalse(bare.containsKey("cdpTargetId"), "no id without CDP_TARGET_ID");
        }
    }

    @Test
    void theTargetIdReadOnceIsReportedWhenALaterReadCannotBeAnswered() throws Exception {
        try (Harness h = harness(new ScriptedBrowser("scripted"))) {
            Map<?, ?> first = (Map<?, ?>) ((List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers"))
                    .get(0);
            assertEquals("TARGET-1", first.get("cdpTargetId"));
            browser.targetIdHangs = true;
            final AtomicInteger hung = new AtomicInteger();
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    hung.incrementAndGet();
                    return new CompletableFuture<String>();
                }
            };
            long started = System.nanoTime();
            Map<?, ?> frozen = (Map<?, ?>) ((List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers"))
                    .get(0);
            long ms = (System.nanoTime() - started) / 1000000L;
            assertEquals("TARGET-1", frozen.get("cdpTargetId"), "the cached id of the frozen page");
            assertFalse(frozen.containsKey("title"), "the title read timed out and is left out");
            assertTrue(ms < 4000, "bounded at 2 s per browser read: " + ms + " ms");
            assertTrue(hung.get() >= 1);
            Map<String, Object> status = h.ctx.map("web.cdp.status", Harness.args());
            assertEquals("TARGET-1", ((Map<?, ?>) ((List<?>) status.get("browsers")).get(0)).get("cdpTargetId"));
        }
    }
}
