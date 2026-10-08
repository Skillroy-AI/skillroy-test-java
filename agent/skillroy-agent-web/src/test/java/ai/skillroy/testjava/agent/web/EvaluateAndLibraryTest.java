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

import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebFrameGoneException;
import ai.skillroy.testjava.agent.web.kit.KitScripts;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code web.evaluate} and the library primitives of phase4-contracts.md section 4.2 against a scripted adapter: the
 * JSON wrapper for every value kind, {@code awaitPromise} with the channel's own flag, {@code SCRIPT_ERROR} data,
 * frame keys, the exact argument objects of the library calls, error objects mapped to their codes with their data,
 * the per-browser capability check, and read-only mode (D51).
 */
class EvaluateAndLibraryTest {

    private ScriptedAdapter adapter;
    private ScriptedBrowser browser;

    private Harness harness() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(new ScriptedBrowser("scripted"));
        browser.load("<html><head><title>Desk</title></head><body><button id=\"go\">Go</button>"
                + "<p id=\"status\">idle</p></body></html>");
        JPanel view = new JPanel();
        browser.component = view;
        Harness h = new Harness(adapter).install();
        h.walk.components.add(view);
        h.walk.showing.add(view);
        return h.ready();
    }

    /** A responder answering every evaluation with the JSON text of a fixed wrapper text. */
    private static ScriptedBrowser.Responder wrapper(final String json, final String typeofResult,
                                                     final boolean truncated) {
        return new ScriptedBrowser.Responder() {
            @Override
            public CompletableFuture<String> answer(WebEval eval) {
                Map<String, Object> w = new LinkedHashMap<String, Object>();
                w.put("json", json);
                w.put("typeofResult", typeofResult);
                w.put("truncated", Boolean.valueOf(truncated));
                return CompletableFuture.completedFuture(Json.write(Json.write(w)));
            }
        };
    }

    @Test
    void evaluateCarriesTheWrappersJsonForEveryValueKind() throws Exception {
        try (Harness h = harness()) {
            String[][] kinds = {
                {"42", "number"}, {"\"text\"", "string"}, {"{\"a\":1,\"b\":[true,null]}", "object"},
                {"null", "undefined"}, {"\"function f() {}\"", "function"}, {"\"Symbol(s)\"", "symbol"},
                {"{\"nodeName\":\"BUTTON\",\"id\":\"go\",\"className\":\"\"}", "object"},
                {"{\"self\":\"[cycle]\"}", "object"}, {"null", "object"}, {"true", "boolean"},
            };
            for (String[] kind : kinds) {
                browser.responder = wrapper(kind[0], kind[1], false);
                Map<String, Object> out = h.ctx.map("web.evaluate", Harness.args("expression", "anything"));
                assertEquals(kind[0], out.get("json"), kind[1]);
                assertEquals(kind[1], out.get("typeofResult"));
                assertFalse(out.containsKey("truncated"), "truncated only when cut");
            }
            browser.responder = wrapper("\"xxxx", "string", true);
            Map<String, Object> cut = h.ctx.map("web.evaluate", Harness.args("expression", "big"));
            assertEquals(Boolean.TRUE, cut.get("truncated"));
            WebEval sent = browser.evals.get(browser.evals.size() - 1);
            assertEquals(WebLib.wrap("big", false), sent.expression(), "the contract's wrapper, sent as it is");
            assertFalse(sent.awaitPromise());
            assertNull(sent.frameKey());
            assertTrue(sent.timeoutMs() > 0 && sent.timeoutMs() < 10000, "the adapter abandons within the budget");
        }
    }

    @Test
    void evaluateThroughTheLibraryForRealValuesAndPromises() throws Exception {
        try (Harness h = harness()) {
            assertEquals("42", h.ctx.map("web.evaluate", Harness.args("expression", KitScripts.NUMBER)).get("json"));
            Map<String, Object> undefined = h.ctx.map("web.evaluate", Harness.args("expression", "undefined"));
            assertEquals("null", undefined.get("json"));
            assertEquals("undefined", undefined.get("typeofResult"));
            Map<String, Object> big = h.ctx.map("web.evaluate", Harness.args("expression", KitScripts.BIG_STRING));
            assertEquals(Boolean.TRUE, big.get("truncated"));
            assertEquals(WebLib.MAX_JSON_CHARS, ((String) big.get("json")).length());
            Map<String, Object> seven = h.ctx.map("web.evaluate", Harness.args("expression", KitScripts.PROMISE,
                    "awaitPromise", Boolean.TRUE));
            assertEquals("7", seven.get("json"));
            WebEval sent = browser.evals.get(browser.evals.size() - 1);
            assertTrue(sent.awaitPromise(), "the channel's own await flag is set");
            assertEquals(WebLib.wrap(KitScripts.PROMISE, true), sent.expression());
            browser.capabilities = EnumSet.of(WebCapability.EVALUATE);
            RpcException noAwait = h.ctx.error("web.evaluate", Harness.args("expression", KitScripts.PROMISE,
                    "awaitPromise", Boolean.TRUE));
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, noAwait.error().code());
            assertTrue(String.valueOf(noAwait.error().data().get("reason")).contains("AWAIT_PROMISE"));
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.evaluate", Harness.args("expression", "  "))
                    .error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.evaluate", Harness.args()).error().code());
        }
    }

    @Test
    void aThrownErrorIsScriptErrorWithBrowserMessageAndStack() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            RpcException e = h.ctx.error("web.evaluate", Harness.args("expression", KitScripts.THROWS));
            assertEquals(ErrorCodes.SCRIPT_ERROR, e.error().code());
            assertEquals(-32008, e.error().code());
            Map<String, Object> data = e.error().data();
            assertEquals(ref, data.get("browser"));
            assertEquals("Error: kit boom", data.get("message"));
            assertTrue(String.valueOf(data.get("stack")).contains("kit boom"));
            assertEquals(Arrays.asList("browser", "message", "stack"), Arrays.asList(data.keySet().toArray()));
        }
    }

    @Test
    void theFrameKeyGoesToTheAdapterAndAGoneFrameIsNotFound() throws Exception {
        try (Harness h = harness()) {
            final String[] seen = new String[1];
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    seen[0] = eval.frameKey();
                    if ("f9".equals(eval.frameKey())) {
                        CompletableFuture<String> f = new CompletableFuture<String>();
                        f.completeExceptionally(new WebFrameGoneException("f9"));
                        return f;
                    }
                    return CompletableFuture.completedFuture(Json.write(Json.write(Harness.args("json", "1",
                            "typeofResult", "number", "truncated", Boolean.FALSE))));
                }
            };
            h.ctx.call("web.evaluate", Harness.args("expression", "1", "frame", "f2"));
            assertEquals("f2", seen[0]);
            h.ctx.call("web.dom.text", Harness.args("frame", "f3"));
            assertEquals("f3", seen[0], "the library primitives take frame? too");
            RpcException gone = h.ctx.error("web.evaluate", Harness.args("expression", "1", "frame", "f9"));
            assertEquals(ErrorCodes.NOT_FOUND, gone.error().code());
            assertEquals("f9", gone.error().data().get("frame"));
            assertEquals(BrowserIndexTest.ref(h, browser), gone.error().data().get("browser"));
        }
    }

    @Test
    void theLibraryCallsSendExactlyTheSpecifiedArgumentObjects() throws Exception {
        try (Harness h = harness()) {
            Map<String, Object> click = h.ctx.map("web.dom.click", Harness.args("selector", "#go"));
            assertEquals("DOM", click.get("fidelity"));
            assertSent(WebLib.callExpression("domClick", Harness.args("selector", "#go", "expectVisible",
                    Boolean.TRUE)));
            Map<String, Object> regex = Harness.args("regex", "^Go", "ignoreCase", Boolean.TRUE);
            h.ctx.error("web.dom.click", Harness.args("role", "button", "name", regex, "index", Long.valueOf(1),
                    "expectVisible", Boolean.FALSE));
            assertSent(WebLib.callExpression("domClick", Harness.args("role", "button", "name", regex, "index",
                    Long.valueOf(1), "expectVisible", Boolean.FALSE)));
            Map<String, Object> fill = h.ctx.map("web.dom.fill", Harness.args("selector", "#go", "text", "saw",
                    "enter", Boolean.TRUE));
            assertEquals(Long.valueOf(3), fill.get("typed"));
            assertFalse(Json.write(fill).contains("saw"), "the text is never echoed");
            assertSent(WebLib.callExpression("domFill", Harness.args("selector", "#go", "expectVisible", Boolean.TRUE,
                    "text", "saw", "clear", Boolean.TRUE, "enter", Boolean.TRUE)));
            Map<String, Object> text = h.ctx.map("web.dom.text", Harness.args("maxChars", Long.valueOf(500000)));
            assertEquals(Boolean.FALSE, text.get("truncated"));
            assertSent(WebLib.callExpression("domText", Harness.args("selector", "body", "maxChars",
                    Long.valueOf(100000))));
            Map<String, Object> aria = h.ctx.map("web.ariaSnapshot", Harness.args("maxNodes", Long.valueOf(99999),
                    "interestingOnly", Boolean.FALSE));
            assertTrue(aria.containsKey("nodes"));
            assertSent(WebLib.callExpression("aria", null, Long.valueOf(20000), Boolean.FALSE));
            Map<String, Object> detail = Harness.args("item", "saw", "qty", Long.valueOf(2));
            assertEquals(Boolean.TRUE, h.ctx.map("web.dispatchEvent", Harness.args("name", "desk.setItem",
                    "detail", detail, "target", "#go")).get("dispatched"));
            assertSent(WebLib.callExpression("dispatch", Harness.args("target", "#go", "name", "desk.setItem",
                    "detail", detail)));
        }
    }

    @Test
    void aLibraryErrorObjectAnswersItsCodeWithItsDataAndTheBrowser() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            RpcException notFound = h.ctx.error("web.dom.click", Harness.args("selector", "#nothing"));
            assertEquals(ErrorCodes.NOT_FOUND, notFound.error().code());
            assertEquals("#nothing", notFound.error().data().get("selector"));
            assertEquals(ref, notFound.error().data().get("browser"));
            final Map<String, Object> ambiguous = Harness.args("__error", "AMBIGUOUS", "data", Harness.args(
                    "candidates", Arrays.asList(Harness.args("role", "button", "name", "Go", "tag", "button")),
                    "count", Long.valueOf(2)));
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    return CompletableFuture.completedFuture(Json.write(ambiguous));
                }
            };
            RpcException amb = h.ctx.error("web.dom.click", Harness.args("role", "button", "name", "Go"));
            assertEquals(ErrorCodes.AMBIGUOUS, amb.error().code());
            assertEquals(1, ((List<?>) amb.error().data().get("candidates")).size());
            final Map<String, Object> hidden = Harness.args("__error", "INVALID_PARAMS", "data",
                    Harness.args("reason", "not visible"));
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    return CompletableFuture.completedFuture(Json.write(hidden));
                }
            };
            RpcException invisible = h.ctx.error("web.dom.fill", Harness.args("selector", "#go", "text", "x"));
            assertEquals(ErrorCodes.INVALID_PARAMS, invisible.error().code());
            assertEquals("not visible", invisible.error().data().get("reason"));
        }
    }

    @Test
    void theLocatorParametersAreChecked() throws Exception {
        try (Harness h = harness()) {
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.click", Harness.args("selector", "#go",
                    "role", "button")).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.click", Harness.args()).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.click", Harness.args("role", "button",
                    "name", Long.valueOf(3))).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.click", Harness.args("selector", "#go",
                    "index", Long.valueOf(-1))).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dom.fill", Harness.args("selector", "#go"))
                    .error().code(), "text is required");
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.dispatchEvent", Harness.args()).error().code());
            assertEquals(ErrorCodes.INVALID_PARAMS, h.ctx.error("web.ariaSnapshot", Harness.args("maxNodes",
                    Long.valueOf(0))).error().code());
        }
    }

    @Test
    void aBrowserWithoutTheFlagAnswersCapabilityUnavailable() throws Exception {
        try (Harness h = harness()) {
            ScriptedBrowser plain = adapter.add(new ScriptedBrowser("scripted"));
            plain.capabilities = EnumSet.of(WebCapability.EVALUATE);
            h.ctx.call("web.browsers", Harness.args());
            String ref = BrowserIndexTest.ref(h, plain);
            for (String primitive : new String[] {"web.bitmap", "web.resize"}) {
                Map<String, Object> args = Harness.args("browser", ref, "width", Long.valueOf(10), "height",
                        Long.valueOf(10));
                RpcException e = h.ctx.error(primitive, args);
                assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, e.error().code(), primitive);
                assertEquals(primitive, e.error().data().get("primitive"));
                assertEquals(primitive, e.error().data().get("capability"));
                assertEquals(ref, e.error().data().get("browser"));
            }
        }
    }

    @Test
    void readOnlyModeRejectsTheMutatingPrimitivesAndServesTheReads() throws Exception {
        try (Harness h = harness()) {
            h.ctx.readOnly = true;
            Map<String, Map<String, Object>> mutating = new LinkedHashMap<String, Map<String, Object>>();
            mutating.put("web.evaluate", Harness.args("expression", "1"));
            mutating.put("web.dom.click", Harness.args("selector", "#go"));
            mutating.put("web.dom.fill", Harness.args("selector", "#go", "text", "x"));
            mutating.put("web.dispatchEvent", Harness.args("name", "x"));
            mutating.put("web.tap", Harness.args("patterns", Arrays.asList("x")));
            mutating.put("web.resize", Harness.args("width", Long.valueOf(5), "height", Long.valueOf(5)));
            mutating.put("web.reload", Harness.args());
            for (Map.Entry<String, Map<String, Object>> m : mutating.entrySet()) {
                assertEquals(ErrorCodes.READ_ONLY, h.ctx.error(m.getKey(), m.getValue()).error().code(), m.getKey());
            }
            h.ctx.call("web.browsers", Harness.args());
            h.ctx.call("web.ariaSnapshot", Harness.args());
            h.ctx.call("web.dom.text", Harness.args());
            h.ctx.call("web.waitFlag", Harness.args("expression", "true"));
            h.ctx.call("web.bitmap", Harness.args());
            h.ctx.call("web.cdp.status", Harness.args());
        }
    }

    private void assertSent(String expression) {
        WebEval last = browser.evals.get(browser.evals.size() - 1);
        assertEquals(expression, last.expression());
    }
}
