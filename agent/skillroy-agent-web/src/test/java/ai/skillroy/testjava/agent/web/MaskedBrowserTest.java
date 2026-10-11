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
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.web.kit.KitPages;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 security fix S111 (phase7-contracts.md 3.6, 4.8): a browser whose component a pack's {@code CaptureMask}
 * names, itself or through an ancestor, is masked on every route of the web module: {@code web.bitmap},
 * {@code web.dom.text}, {@code web.ariaSnapshot} and {@code web.hitTest} answer {@code INVALID_PARAMS} with
 * {@code data.reason: "masked"} unless {@code redact: false} (a person's argument), and {@code web.browsers} says
 * {@code masked: true} and withholds the URL, the title and the frames' names and URLs; a mask that throws masks (the
 * safe side); without a mask nothing changes and no extra EDT read is made.
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
}
