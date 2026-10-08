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

import ai.skillroy.testjava.agent.api.spi.web.BrowserRegistryAdapter;
import ai.skillroy.testjava.agent.api.spi.web.BrowserSeed;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The browser selection of phase4-contracts.md section 4.2: {@code browser: <ref>} or {@code by: {url | tag | title
 * | nativeId}} (several keys all match), else the single browser whose component is showing; {@code NOT_FOUND}
 * with {@code data.browsers} (the open browsers, with their capabilities and the titles the selection read),
 * {@code AMBIGUOUS} with {@code data.candidates}; refs of other things, unknown refs and bad {@code by} objects.
 */
class SelectionTest {

    private ScriptedAdapter adapter;
    private ScriptedBrowser front;
    private ScriptedBrowser back;
    private JPanel frontView;
    private JPanel backView;

    private Harness harness() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        front = adapter.add(new ScriptedBrowser("scripted"));
        back = adapter.add(new ScriptedBrowser("scripted"));
        front.url = "app://front/index";
        back.url = "app://back/index";
        front.nativeId = "1";
        back.nativeId = "2";
        front.load("<html><head><title>Front page</title></head><body><p id=\"who\">front</p></body></html>");
        back.load("<html><head><title>Back page</title></head><body><p id=\"who\">back</p></body></html>");
        frontView = new JPanel();
        backView = new JPanel();
        front.component = frontView;
        back.component = backView;
        Harness h = new Harness(adapter).install();
        h.walk.components.addAll(Arrays.<Object>asList(frontView, backView));
        h.walk.showing.add(frontView);
        final ScriptedBrowser tagged = back;
        h.ctx.spi.register(BrowserRegistryAdapter.class, new BrowserRegistryAdapter() {
            @Override
            public Collection<BrowserSeed> seeds() {
                return Arrays.asList(BrowserSeed.of(tagged.vendorObject(), "back"));
            }
        });
        return h.ready();
    }

    private static String text(Harness h, Map<String, Object> args) throws Exception {
        return (String) h.ctx.map("web.dom.text", args).get("text");
    }

    @Test
    void theDefaultIsTheSingleShowingBrowser() throws Exception {
        try (Harness h = harness()) {
            assertEquals("front", text(h, Harness.args("selector", "#who")));
            h.walk.showing.clear();
            RpcException none = h.ctx.error("web.dom.text", Harness.args());
            assertEquals(ErrorCodes.NOT_FOUND, none.error().code());
            assertEquals(2, ((List<?>) none.error().data().get("browsers")).size(), "data.browsers lists the index");
            h.walk.showing.add(frontView);
            h.walk.showing.add(backView);
            RpcException two = h.ctx.error("web.dom.text", Harness.args());
            assertEquals(ErrorCodes.AMBIGUOUS, two.error().code());
            List<?> candidates = (List<?>) two.error().data().get("candidates");
            assertEquals(2, candidates.size());
            Map<?, ?> c = (Map<?, ?>) candidates.get(0);
            assertTrue(c.containsKey("ref") && c.containsKey("adapter") && c.containsKey("url"), String.valueOf(c));
        }
    }

    @Test
    void aRefSelectsItsBrowserAndOtherRefsAreRefused() throws Exception {
        try (Harness h = harness()) {
            String backRef = BrowserIndexTest.ref(h, back);
            assertEquals("back", text(h, Harness.args("browser", backRef, "selector", "#who")));
            String componentRef = h.ctx.handles.handleFor(frontView, "component");
            RpcException notABrowser = h.ctx.error("web.dom.text", Harness.args("browser", componentRef));
            assertEquals(ErrorCodes.INVALID_PARAMS, notABrowser.error().code(), notABrowser.getMessage());
            assertEquals("browser", notABrowser.error().data().get("param"));
            RpcException unknown = h.ctx.error("web.dom.text", Harness.args("browser", "h999"));
            assertEquals(ErrorCodes.STALE_HANDLE, unknown.error().code());
            RpcException both = h.ctx.error("web.dom.text", Harness.args("browser", backRef, "by",
                    Harness.args("tag", "back")));
            assertEquals(ErrorCodes.INVALID_PARAMS, both.error().code());
        }
    }

    @Test
    void byMatchesUrlTagTitleAndNativeIdAndAllKeysMustMatch() throws Exception {
        try (Harness h = harness()) {
            assertEquals("back", text(h, Harness.args("by", Harness.args("url", "^app://back/"), "selector", "#who")));
            assertEquals("back", text(h, Harness.args("by", Harness.args("tag", "back"), "selector", "#who")));
            assertEquals("front", text(h, Harness.args("by", Harness.args("title", "^Front"), "selector", "#who")));
            assertEquals("front", text(h, Harness.args("by", Harness.args("nativeId", "1"), "selector", "#who")));
            assertEquals("back", text(h, Harness.args("by", Harness.args("url", "index", "tag", "back"),
                    "selector", "#who")));
            RpcException none = h.ctx.error("web.dom.text", Harness.args("by", Harness.args("url", "^nowhere")));
            assertEquals(ErrorCodes.NOT_FOUND, none.error().code());
            assertEquals(Harness.args("url", "^nowhere"), none.error().data().get("by"));
            assertEquals(2, ((List<?>) none.error().data().get("browsers")).size());
            RpcException several = h.ctx.error("web.dom.text", Harness.args("by", Harness.args("url", "index$")));
            assertEquals(ErrorCodes.AMBIGUOUS, several.error().code());
            assertEquals(2, ((List<?>) several.error().data().get("candidates")).size());
        }
    }

    @Test
    void notFoundListsTheOpenBrowsersWithTheirCapabilitiesAndTheTitlesTheSelectionRead() throws Exception {
        try (Harness h = harness()) {
            String frontRef = BrowserIndexTest.ref(h, front);
            String backRef = BrowserIndexTest.ref(h, back);
            ScriptedBrowser gone = adapter.add(new ScriptedBrowser("scripted"));
            gone.closed = true;
            int frontBefore = front.evals.size();
            int backBefore = back.evals.size();
            RpcException byTitle = h.ctx.error("web.dom.text", Harness.args("by", Harness.args("title", "nomatch")));
            assertEquals(ErrorCodes.NOT_FOUND, byTitle.error().code());
            List<?> listed = (List<?>) byTitle.error().data().get("browsers");
            assertEquals(2, listed.size(), "a closed browser is left out, as web.browsers leaves it out: " + listed);
            Map<?, ?> first = (Map<?, ?>) listed.get(0);
            assertEquals(frontRef, first.get("ref"));
            assertEquals("Front page", first.get("title"), "the title the selection read");
            assertEquals(Boolean.FALSE, first.get("closed"));
            assertTrue(((List<?>) first.get("capabilities")).contains("EVALUATE"), String.valueOf(first));
            assertEquals(Arrays.asList("ref", "adapter", "nativeId", "url", "title", "closed", "visibleInSwing",
                    "component", "capabilities"), new java.util.ArrayList<Object>(first.keySet()),
                    "the key order of web.browsers");
            Map<?, ?> second = (Map<?, ?>) listed.get(1);
            assertEquals(backRef, second.get("ref"));
            assertEquals("back", second.get("tag"));
            List<WebEval> sent = new java.util.ArrayList<WebEval>(front.evals.subList(frontBefore,
                    front.evals.size()));
            sent.addAll(back.evals.subList(backBefore, back.evals.size()));
            assertFalse(sent.isEmpty());
            for (WebEval e : sent) {
                assertEquals("document.title", e.expression(), "only the selection's own title reads reach the page");
            }
            RpcException byUrl = h.ctx.error("web.dom.text", Harness.args("by", Harness.args("url", "^nowhere")));
            Map<?, ?> untitled = (Map<?, ?>) ((List<?>) byUrl.error().data().get("browsers")).get(0);
            assertFalse(untitled.containsKey("title"), "no page is read for the error: " + untitled);
            assertTrue(untitled.containsKey("capabilities"));
            h.walk.showing.clear();
            RpcException none = h.ctx.error("web.dom.text", Harness.args());
            assertEquals(2, ((List<?>) none.error().data().get("browsers")).size(), "the default selection too");
        }
    }

    @Test
    void aBadByObjectIsInvalid() throws Exception {
        try (Harness h = harness()) {
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.text", Harness.args("by",
                    Harness.args("colour", "red"))).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.text", Harness.args("by",
                    Harness.args())).error().code());
            RpcException regex = h.ctx.error("web.dom.text", Harness.args("by", Harness.args("url", "(")));
            assertEquals(ErrorCodes.INVALID_PARAMS, regex.error().code());
            assertEquals("by.url", regex.error().data().get("param"));
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.text", Harness.args("by", "not an object"))
                    .error().code());
        }
    }

    @Test
    void aNewBrowserIsFoundByARebuildWhenNothingMatches() throws Exception {
        try (Harness h = harness()) {
            ScriptedBrowser late = adapter.add(new ScriptedBrowser("scripted"));
            late.url = "app://late/";
            late.load("<html><head><title>Late</title></head><body><p id=\"who\">late</p></body></html>");
            assertEquals("late", text(h, Harness.args("by", Harness.args("url", "late"), "selector", "#who")));
        }
    }
}
