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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CaptureSpec;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebSettings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase4-contracts.md 7 (12.1 item 4, the compile half): the base web verbs compile to the primitives and sync levels
 * of 7.1 with the parameters of 4.2, the scenario key {@code web} with its defaults and its errors, {@code requires},
 * the compile-time note, and plan version 4.
 */
class WebCompileTest {

    private final ToyPack pack = new ToyPack();

    private Plan compile(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    private static String scenario(String web, String steps) {
        return "scenario: t\npack: toy\n" + (web == null ? "" : "web: " + web + "\n") + "steps:\n" + steps;
    }

    private ScenarioException fails(String yaml) {
        return assertThrows(ScenarioException.class, () -> compile(yaml));
    }

    /** The step's single compile error: its reason exactly, at the step's line. */
    private void stepFails(String step, String reason) {
        ScenarioException e = fails(scenario(null, "  - " + step + "\n"));
        assertEquals(reason, e.reason(), step);
        assertEquals(4, e.line(), step);
    }

    /** The {@code web} key's single error: its reason exactly, at line 3. */
    private void webFails(String web, String reason) {
        ScenarioException e = fails(scenario(web, "  - mark: x\n"));
        assertEquals(reason, e.reason(), web);
        assertEquals(3, e.line(), web);
    }

    private static Plan.Call call(Plan plan, int step, int call) {
        return plan.steps().get(step).calls().get(call);
    }

    // ------------------------------------------------------------------------------------------------ the verbs

    @Test
    void everyWebVerbCompilesToItsPrimitiveWithTheSyncOf7_1() throws Exception {
        Plan plan = compile(scenario(null, """
                  - webTap: { patterns: [webhost.*, app.ready], browser: { tag: front } }
                  - webFill: { selector: "#item", text: hammer }
                  - webFill: { role: textbox, name: Quantity, text: 2, clear: false, enter: true }
                  - webClick:
                      role: button
                      name: { regex: "^borrow$", ignoreCase: true }
                      index: 0
                      expectVisible: false
                      frame: F1
                  - webDispatch: { name: webhost.setItem, detail: { item: saw, qty: 2 }, target: "#form" }
                  - webWaitFlag: { expression: "window.webhostReady === true", timeoutMs: 5s, pollMs: 50 }
                  - webResize: { width: 1024, height: 768, browser: { tag: back } }
                  - webResize: { clear: true }
                  - webWaitFlag: { expression: "document.readyState == 'complete'" }
                """));
        assertEquals(new Plan.Call("web.tap", Map.of("by", Map.of("tag", "front"), "patterns", List.of("webhost.*",
                "app.ready")), Sync.NONE, 10_000L, false), call(plan, 0, 0));
        assertEquals(new Plan.Call("web.dom.fill", Map.of("selector", "#item", "text", "hammer"), Sync.SETTLE,
                10_000L, false), call(plan, 1, 0));
        assertEquals(new Plan.Call("web.dom.fill", Map.of("role", "textbox", "name", "Quantity", "text", "2",
                "clear", false, "enter", true), Sync.SETTLE, 10_000L, false), call(plan, 2, 0),
                "a number is filled as its text, as type does");
        assertEquals(new Plan.Call("web.dom.click", Map.of("role", "button", "name", Map.of("regex", "^borrow$",
                "ignoreCase", true), "index", 0L, "expectVisible", false, "frame", "F1"), Sync.SETTLE, 10_000L, false),
                call(plan, 3, 0));
        assertEquals(new Plan.Call("web.dispatchEvent", Map.of("name", "webhost.setItem", "detail", Map.of("item",
                "saw", "qty", 2L), "target", "#form"), Sync.SETTLE, 10_000L, false), call(plan, 4, 0));
        assertEquals(new Plan.Call("web.waitFlag", Map.of("expression", "window.webhostReady === true", "timeoutMs",
                5000L, "pollMs", 50L), Sync.NONE, 6000L, false), call(plan, 5, 0),
                "the primitive waits; the call's timeout is a second longer");
        assertEquals(new Plan.Call("web.resize", Map.of("by", Map.of("tag", "back"), "width", 1024L, "height", 768L),
                Sync.SETTLE, 10_000L, false), call(plan, 6, 0));
        assertEquals(new Plan.Call("web.resize", Map.of("clear", true), Sync.SETTLE, 10_000L, false),
                call(plan, 7, 0));
        assertEquals(new Plan.Call("web.waitFlag", Map.of("expression", "document.readyState == 'complete'"),
                Sync.NONE, 10_000L, false), call(plan, 8, 0), "without timeoutMs the primitive waits for the budget");
        for (Plan.Step step : plan.steps()) {
            assertEquals(1, step.calls().size(), step.id());
        }
        assertEquals(List.of(new Plan.Call("web.tap", Map.of("by", Map.of("tag", "front"), "patterns", List.of()),
                Sync.NONE, 10_000L, false)), plan.cleanup(), "webTap removes its tap from the same browser");
        assertEquals(List.of("web.browsers", "web.evaluate"), plan.requires());
        assertNull(plan.web());
        assertFalse(plan.toJsonText().contains("\"web\""), "no web key in the YAML, none in the plan");
        assertEquals(WebSettings.DEFAULT, plan.effectiveWeb());
        String text = plan.toJsonText();
        assertTrue(text.contains("\"args\": {\n            \"by\": {\n              \"tag\": \"front\"\n"
                + "            },\n            \"patterns\": ["), "the args' keys sorted: " + text);
        assertEquals(text, compile(scenario(null, """
                  - webTap: { patterns: [webhost.*, app.ready], browser: { tag: front } }
                  - webFill: { selector: "#item", text: hammer }
                  - webFill: { role: textbox, name: Quantity, text: 2, clear: false, enter: true }
                  - webClick:
                      role: button
                      name: { regex: "^borrow$", ignoreCase: true }
                      index: 0
                      expectVisible: false
                      frame: F1
                  - webDispatch: { name: webhost.setItem, detail: { item: saw, qty: 2 }, target: "#form" }
                  - webWaitFlag: { expression: "window.webhostReady === true", timeoutMs: 5s, pollMs: 50 }
                  - webResize: { width: 1024, height: 768, browser: { tag: back } }
                  - webResize: { clear: true }
                  - webWaitFlag: { expression: "document.readyState == 'complete'" }
                """)).toJsonText(), "byte-identical recompilation");
        assertEquals(text, Plan.parse(text).toJsonText(), "and it reads back");
    }

    @Test
    void awaitTurnsTheSettleIntoAnEventFollowedByASettleAsA32Does() throws Exception {
        Plan plan = compile("scenario: t\npack: toy\ntimeouts: { step: 20s }\nsteps:\n"
                + "  - webClick: { role: button, name: Borrow, await: { topics: web.tap, match: { name:"
                + " webhost.borrowed }, within: 5s, capture: { slot: borrowed, fields: { qty: detail.qty } } } }\n"
                + "  - webFill: { selector: \"#item\", text: saw, await: { topic: web.ready } }\n"
                + "  - webDispatch: { name: webhost.reset, await: { topics: [web.load, web.ready] } }\n"
                + "  - webResize: { width: 10, height: 10, await: { topic: web.ready, match: { signal: LOAD } } }\n"
                + "  - expect: { \"web.browsers[tag=front].text\": { contains: \"x $borrowed.qty\" } }\n");
        Plan.Call click = call(plan, 0, 0);
        assertEquals(Sync.EVENT, click.sync());
        assertEquals(new AwaitSpec("web.tap", Map.of("name", "webhost.borrowed"), 5000L), click.await());
        assertEquals(CaptureSpec.of("borrowed", Map.of("qty", "detail.qty")), click.capture());
        assertEquals(new Plan.Call("swing.settle", Map.of("timeoutMs", 20_000L), Sync.NONE, 22_000L, false),
                call(plan, 0, 1), "a page action may open a Swing dialog: the settle still runs");
        assertEquals(new AwaitSpec("web.ready", null, 20_000L), call(plan, 1, 0).await(),
                "within defaults to the step timeout");
        assertEquals(new AwaitSpec("web.load,web.ready", null, 20_000L), call(plan, 2, 0).await());
        assertEquals(new AwaitSpec("web.ready", Map.of("signal", "LOAD"), 20_000L), call(plan, 3, 0).await());
        for (int step = 0; step < 4; step++) {
            assertEquals(2, plan.steps().get(step).calls().size());
            assertEquals("swing.settle", call(plan, step, 1).primitive());
        }
        assertEquals(List.of("borrowed"), plan.slots().stream().map(Plan.Slot::name).toList(),
                "the capture declares the slot");
    }

    @Test
    void theScenariosDefaultBrowserIsTheByOfEveryWebVerbUnlessTheStepNamesItsOwn() throws Exception {
        Plan plan = compile(scenario("{ browser: { url: \"^data:\" } }", """
                  - webClick: { selector: "#a" }
                  - webClick: { selector: "#a", browser: { nativeId: "7" } }
                  - webTap: { patterns: [app.*], target: document }
                  - webWaitFlag: { expression: "1", browser: { title: "^Front$" } }
                  - webResize: { clear: true }
                  - webDispatch: { name: go }
                """));
        Map<String, Object> url = Map.of("url", "^data:");
        assertEquals(url, call(plan, 0, 0).args().get("by"));
        assertEquals(Map.of("nativeId", "7"), call(plan, 1, 0).args().get("by"));
        assertEquals(Map.of("by", url, "patterns", List.of("app.*"), "target", "document"), call(plan, 2, 0).args());
        assertEquals(Map.of("by", url, "patterns", List.of(), "target", "document"), plan.cleanup().get(0).args());
        assertEquals(Map.of("title", "^Front$"), call(plan, 3, 0).args().get("by"));
        assertEquals(url, call(plan, 4, 0).args().get("by"));
        assertEquals(url, call(plan, 5, 0).args().get("by"));
        assertEquals(new WebSettings(WebSettings.Path.AUTO, url, FrozenTargetPolicy.FAIL, Duration.ofSeconds(30),
                false), plan.web());
    }

    @Test
    void aPackVerbSeesTheScenariosWebSettings() throws Exception {
        AtomicReference<WebSettings> seen = new AtomicReference<>();
        pack.onCustom = ctx -> seen.set(ctx.web());
        compile(scenario("{ path: agent, browser: { tag: back } }", "  - custom: {}\n"));
        assertEquals(new WebSettings(WebSettings.Path.AGENT, Map.of("tag", "back"), FrozenTargetPolicy.FAIL,
                Duration.ofSeconds(30), false), seen.get());
        compile(scenario(null, "  - custom: {}\n"));
        assertSame(WebSettings.DEFAULT, seen.get(), "the default without the key");
    }

    @Test
    void theWebVerbsArgumentsAreChecked() {
        stepFails("webClick: { role: button }",
                "webClick: 'role' needs 'name' (the accessible name: a text, or {regex, ignoreCase?})");
        stepFails("webClick: { selector: \"#a\", role: button, name: x }", "webClick: give exactly one of"
                + " 'selector' (a CSS selector) or 'role' with 'name' (an ARIA role and the accessible name)");
        stepFails("webClick: { name: x }", "webClick: give exactly one of 'selector' (a CSS selector) or 'role' with"
                + " 'name' (an ARIA role and the accessible name)");
        stepFails("webClick: { selector: \"#a\", name: x }", "webClick: 'name' goes with 'role', not with"
                + " 'selector'");
        stepFails("webClick: { selector: \"\" }",
                "webClick: 'selector' must be a CSS selector (a non-empty string), found \"\"");
        stepFails("webClick: { role: \" \", name: x }", "webClick: 'role' must be an ARIA role such as button or"
                + " textbox (a non-empty string), found \" \"");
        stepFails("webClick: { role: button, name: { regex: \"\" } }", "webClick: 'name' must be a text or {regex,"
                + " ignoreCase?} (ignoreCase true or false), found a mapping");
        stepFails("webClick: { role: button, name: { regex: a, ignoreCase: yes } }", "webClick: 'name' must be a"
                + " text or {regex, ignoreCase?} (ignoreCase true or false), found a mapping");
        stepFails("webClick: { role: button, name: { pattern: a } }", "webClick: 'name' must be a text or {regex,"
                + " ignoreCase?} (ignoreCase true or false), found a mapping");
        stepFails("webClick: { role: button, name: [a] }", "webClick: 'name' must be a text or {regex, ignoreCase?}"
                + " (ignoreCase true or false), found a list");
        stepFails("webClick: { selector: \"#a\", index: -1 }",
                "webClick: 'index' must be a whole number of 0 or more, found -1");
        stepFails("webClick: { selector: \"#a\", index: first }",
                "webClick: 'index' must be a whole number of 0 or more, found \"first\"");
        stepFails("webClick: { selector: \"#a\", expectVisible: maybe }",
                "webClick: 'expectVisible' must be true or false, found \"maybe\"");
        stepFails("webClick: { selector: \"#a\", browser: { tag: a, title: b } }", "webClick: 'browser' must be a"
                + " mapping with exactly one of url, tag, title, nativeId, found a mapping with keys [tag, title]");
        stepFails("webClick: { selector: \"#a\", browser: { ref: h7 } }",
                "webClick: 'browser' must have one of the keys url, tag, title, nativeId, found 'ref'");
        stepFails("webClick: { selector: \"#a\", browser: { url: \"(\" } }",
                "webClick: 'browser.url' is not a valid regular expression: Unclosed group");
        stepFails("webClick: { selector: \"#a\", browser: front }", "webClick: 'browser' must be a mapping with"
                + " exactly one of url, tag, title, nativeId, found \"front\"");
        stepFails("webClick: { selector: \"#a\", frame: 3 }",
                "webClick: 'frame' must be a frame key (a non-empty string), found 3");
        stepFails("webClick: { selector: \"#a\", by: { tag: x } }", "webClick: unknown argument 'by'; allowed:"
                + " [selector, role, name, index, expectVisible, browser, frame, await]");
        stepFails("webClick: \"#a\"", "webClick: arguments must be a mapping with keys [selector, role, name, index,"
                + " expectVisible, browser, frame, await], found \"#a\"");
        stepFails("webClick: { selector: \"#a\", await: { topic: web.tap, within: 700s } }", "webClick.await.within"
                + " must be at most 600000 ms (the agent's limit for sync.await), found 700000 ms");
        stepFails("webFill: { selector: \"#a\" }", "webFill: 'text' must be a string, found null");
        stepFails("webFill: { selector: \"#a\", text: [1] }", "webFill: 'text' must be a string, found a list");
        stepFails("webFill: { selector: \"#a\", text: x, clear: no }",
                "webFill: 'clear' must be true or false, found \"no\"");
        stepFails("webFill: { selector: \"#a\", text: x, enter: 1 }",
                "webFill: 'enter' must be true or false, found 1");
        stepFails("webDispatch: { detail: 1 }",
                "webDispatch: 'name' must be the event's name (a non-empty string), found null");
        stepFails("webDispatch: { name: x, target: \"\" }",
                "webDispatch: 'target' must be a CSS selector (a non-empty string), found \"\"");
        stepFails("webDispatch: { name: x, selector: \"#a\" }", "webDispatch: unknown argument 'selector'; allowed:"
                + " [name, detail, target, browser, frame, await]");
        stepFails("webWaitFlag: {}",
                "webWaitFlag: 'expression' must be a JavaScript expression (a non-empty string), found null");
        stepFails("webWaitFlag: { expression: x, timeoutMs: 120s }", "webWaitFlag.timeoutMs must be at most 119000 ms"
                + " (the agent caps a call at 120 s), found 120000 ms");
        stepFails("webWaitFlag: { expression: x, timeoutMs: soon }", "webWaitFlag.timeoutMs: expected a duration"
                + " such as \"10s\", \"500ms\", \"2m\" or a number of milliseconds, found \"soon\"");
        stepFails("webWaitFlag: { expression: x, pollMs: 10 }",
                "webWaitFlag: 'pollMs' must be a whole number of 20 or more, found 10");
        stepFails("webWaitFlag: { expression: x, await: { topic: web.ready } }", "webWaitFlag: unknown argument"
                + " 'await'; allowed: [expression, timeoutMs, pollMs, browser, frame]");
        String usage = "webResize: give 'width' and 'height' (1 to 16384), or 'clear: true'";
        stepFails("webResize: { width: 10 }", usage);
        stepFails("webResize: { clear: false }", usage);
        stepFails("webResize: { clear: true, width: 1 }", usage);
        stepFails("webResize: { width: 10, height: 0 }",
                "webResize: 'height' must be a whole number from 1 to 16384, found 0");
        stepFails("webResize: { width: 20000, height: 10 }",
                "webResize: 'width' must be a whole number from 1 to 16384, found 20000");
        stepFails("webResize: { width: 10, height: 10, frame: F1 }", "webResize: unknown argument 'frame'; allowed:"
                + " [width, height, clear, browser, await]");
        stepFails("webTap: {}", "webTap: 'patterns' must be a list of event names or globs such as [app.ready,"
                + " app.*], found null");
        stepFails("webTap: { patterns: [] }", "webTap: 'patterns' must be a list of event names or globs such as"
                + " [app.ready, app.*], found a list");
        stepFails("webTap: { patterns: [a], target: body }",
                "webTap: 'target' must be window or document, found \"body\"");
    }

    @Test
    void aSinglePatternIsAListOfOneAndTheTargetIsAnyCase() throws Exception {
        Plan plan = compile(scenario(null, "  - webTap: { patterns: \" app.* \", target: Window }\n"));
        assertEquals(Map.of("patterns", List.of("app.*"), "target", "window"), call(plan, 0, 0).args());
    }

    // ------------------------------------------------------------------------------------------------ the key web

    @Test
    void theWebKeyIsReadWithItsDefaultsAndWrittenOnlyWhenTheScenarioHasIt() throws Exception {
        Plan empty = compile(scenario("{}", "  - mark: x\n"));
        assertEquals(new WebSettings(WebSettings.Path.AUTO, null, FrozenTargetPolicy.FAIL, Duration.ofSeconds(30),
                false), empty.web());
        assertEquals(WebSettings.DEFAULT, empty.web(), "every default");
        assertTrue(empty.toJsonText().contains("  \"stepTimeoutMs\": 10000,\n  \"web\": {\n    \"path\": \"auto\",\n"
                + "    \"frozenTargets\": \"fail\",\n    \"trace\": false\n  },\n  \"bindTimeoutMs\": 60000\n"),
                empty.toJsonText());

        Plan full = compile(scenario("{ path: CDP, browser: { tag: front }, frozenTargets: Reload, trace: true }",
                "  - mark: x\n"));
        assertEquals(new WebSettings(WebSettings.Path.CDP, Map.of("tag", "front"), FrozenTargetPolicy.RELOAD,
                Duration.ofSeconds(30), true), full.web(), "the values in any case");
        String text = full.toJsonText();
        assertTrue(text.contains("  \"web\": {\n    \"path\": \"cdp\",\n    \"browser\": {\n      \"tag\": \"front\"\n"
                + "    },\n    \"frozenTargets\": \"reload\",\n    \"trace\": true\n  },\n"), text);
        assertEquals(full, Plan.parse(text), "the plan reads back with its web settings");
        assertEquals(text, Plan.parse(text).toJsonText());
        List<String> keys = new ArrayList<>(full.toJson().keySet());
        assertEquals(List.of("stepTimeoutMs", "web", "bindTimeoutMs"), keys.subList(keys.size() - 3, keys.size()));

        Plan withUi = compile(scenario("{ path: agent }", "  - mark: x\n").replace("steps:",
                "ui: { clickRung: COMPONENT }\nsteps:"));
        List<String> uiKeys = new ArrayList<>(withUi.toJson().keySet());
        assertEquals(List.of("stepTimeoutMs", "ui", "web", "bindTimeoutMs"), uiKeys.subList(uiKeys.size() - 4,
                uiKeys.size()), "ui, then web");

        Scenario none = ScenarioLoader.load(scenario(null, "  - mark: x\n").getBytes(StandardCharsets.UTF_8),
                "t.yaml");
        assertNull(none.web());
        assertSame(WebSettings.DEFAULT, none.effectiveWeb());
    }

    @Test
    void theWebKeysErrorsNameTheKeyAndItsLine() {
        webFails("auto", "'web' must be a mapping such as {path: auto, browser: {tag: front}}, found \"auto\"");
        webFails("{ mode: cdp }", "unknown key 'web.mode'; allowed: path, browser, frozenTargets, trace");
        webFails("{ path: remote }", "'web.path' must be auto, cdp or agent, found \"remote\"");
        webFails("{ path: 1 }", "'web.path' must be auto, cdp or agent, found 1");
        webFails("{ browser: front }", "'web.browser' must be a mapping with exactly one of url, tag, title,"
                + " nativeId, found \"front\"");
        webFails("{ browser: { tag: a, url: b } }", "'web.browser' must be a mapping with exactly one of url, tag,"
                + " title, nativeId, found a mapping with keys [tag, url]");
        webFails("{ browser: { id: a } }",
                "'web.browser' must have one of the keys url, tag, title, nativeId, found 'id'");
        webFails("{ browser: { tag: \"\" } }", "'web.browser.tag' must be a non-empty string, found \"\"");
        webFails("{ browser: { title: \"[\" } }",
                "'web.browser.title' is not a valid regular expression: Unclosed character class");
        webFails("{ frozenTargets: close }", "'web.frozenTargets' close is not available in Phase 4: closing a"
                + " browser the application owns can break it (decision D52); use fail or reload");
        webFails("{ frozenTargets: CLOSE }", "'web.frozenTargets' close is not available in Phase 4: closing a"
                + " browser the application owns can break it (decision D52); use fail or reload");
        webFails("{ frozenTargets: retry }", "'web.frozenTargets' must be fail or reload, found \"retry\"");
        webFails("{ trace: yes }", "'web.trace' must be true or false, found \"yes\"");
        assertEquals(new WebSettings(WebSettings.Path.AUTO, Map.of("url", "$page"), FrozenTargetPolicy.FAIL,
                Duration.ofSeconds(30), false), assertDoesNotFail(scenario("{ browser: { url: $page } }",
                "  - mark: x\n")).web(), "a reference is resolved at run time, not checked as a regex");
    }

    private Plan assertDoesNotFail(String yaml) {
        try {
            return compile(yaml);
        } catch (ScenarioException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------ requires

    @Test
    void aWebVerbAddsWebEvaluateAndWebBrowsersAndPathCdpAddsWebCdpWithANote() throws Exception {
        PlanCompiler.Compiled cdp = PlanCompiler.compileWithNotes(ScenarioLoader.load(("scenario: t\npack: toy\n"
                + "requires: [toy.state]\nweb: { path: cdp }\nsteps:\n  - webClick: { selector: \"#a\" }\n")
                .getBytes(StandardCharsets.UTF_8), "t.yaml"), pack);
        assertEquals(List.of("toy.state", "web.browsers", "web.cdp", "web.evaluate"), cdp.plan().requires(),
                "the scenario's own first, then the added ones sorted");
        assertEquals(List.of("web.path is cdp: the capability web.cdp was added to requires (list it there to say"
                + " so in the scenario)"), cdp.notes());
        assertEquals(cdp.plan(), PlanCompiler.compile(ScenarioLoader.load(("scenario: t\npack: toy\n"
                + "requires: [toy.state]\nweb: { path: cdp }\nsteps:\n  - webClick: { selector: \"#a\" }\n")
                .getBytes(StandardCharsets.UTF_8), "t.yaml"), pack), "compile gives the same plan");

        PlanCompiler.Compiled listed = PlanCompiler.compileWithNotes(ScenarioLoader.load(("scenario: t\npack: toy\n"
                + "requires: [web.cdp]\nweb: { path: cdp }\nsteps:\n  - mark: x\n").getBytes(StandardCharsets.UTF_8),
                "t.yaml"), pack);
        assertEquals(List.of("web.cdp"), listed.plan().requires(), "no web verb: web.cdp alone");
        assertEquals(List.of(), listed.notes(), "the scenario said it: no note");

        for (String path : List.of("auto", "agent")) {
            Plan plan = compile(scenario("{ path: " + path + " }", "  - webTap: { patterns: [a] }\n"));
            assertEquals(List.of("web.browsers", "web.evaluate"), plan.requires(), path);
        }
        assertEquals(List.of(), compile(scenario(null, "  - call: { primitive: web.evaluate, args: { expression:"
                + " \"1\" } }\n")).requires(), "a raw call is not a web verb");
        assertEquals(List.of(), PlanCompiler.compileWithNotes(ScenarioLoader.load(scenario(null, "  - mark: x\n")
                .getBytes(StandardCharsets.UTF_8), "t.yaml"), pack).notes());
    }

    // ------------------------------------------------------------------------------------------------ plan.json

    @Test
    void version5IsWrittenAndAVersion3PlanIsReadAsVersion5WithoutWeb() throws Exception {
        Plan plan = compile(scenario("{ path: agent }", "  - webClick: { selector: \"#a\" }\n"));
        assertEquals(5, plan.planVersion());
        String v5 = plan.toJsonText();
        assertTrue(v5.startsWith("{\n  \"planVersion\": 5,\n"), v5);
        assertEquals(v5, Plan.parse(v5.replace("\"planVersion\": 5", "\"planVersion\": 4")).toJsonText(),
                "a version 4 plan with web is read as version 5");
        ScenarioException needsFour = assertThrows(ScenarioException.class, () -> Plan.parse(v5.replace(
                "\"planVersion\": 5", "\"planVersion\": 3")));
        assertEquals("invalid plan: 'web' needs a version 4 plan", needsFour.reason());

        Plan withoutWeb = compile(scenario(null, "  - webClick: { selector: \"#a\" }\n"));
        Plan v3 = Plan.parse(withoutWeb.toJsonText().replace("\"planVersion\": 5", "\"planVersion\": 3"));
        assertEquals(5, v3.planVersion(), "a version 3 plan is read as version 5");
        assertNull(v3.web(), "without web");
        assertEquals(withoutWeb.toJsonText(), v3.toJsonText());
        Plan v2 = Plan.parse(withoutWeb.toJsonText().replace("\"planVersion\": 5", "\"planVersion\": 2"));
        assertEquals(5, v2.planVersion(), "a version 2 plan is read as version 5");
        assertEquals(withoutWeb.toJsonText(), v2.toJsonText());
        Plan v1 = SecretMaskTest.version1(withoutWeb, Map.of());
        assertEquals(5, v1.planVersion(), "a version 1 plan is read as version 5");
        assertEquals(withoutWeb.steps(), v1.steps());
    }

    @Test
    void aHandEditedWebObjectIsChecked() throws Exception {
        String text = compile(scenario("{ browser: { tag: front } }", "  - mark: x\n")).toJsonText();
        assertEquals("invalid plan: web: unknown key 'mode'", reason(text.replace("\"trace\": false",
                "\"trace\": false,\n    \"mode\": 1")));
        assertEquals("invalid plan: web: 'path' must be auto, cdp or agent, found \"remote\"", reason(text.replace(
                "\"path\": \"auto\"", "\"path\": \"remote\"")));
        assertEquals("invalid plan: web: 'frozenTargets' close is not available in Phase 4: closing a browser the"
                + " application owns can break it (decision D52); use fail or reload", reason(text.replace(
                "\"frozenTargets\": \"fail\"", "\"frozenTargets\": \"close\"")));
        assertEquals("invalid plan: web: 'frozenTargets' must be fail or reload, found \"later\"", reason(
                text.replace("\"frozenTargets\": \"fail\"", "\"frozenTargets\": \"later\"")));
        assertEquals("invalid plan: web: 'trace' must be a boolean", reason(text.replace("\"trace\": false",
                "\"trace\": \"no\"")));
        assertEquals("invalid plan: web: 'browser' must be a mapping with exactly one of url, tag, title, nativeId,"
                + " found a mapping with keys [tag, url]", reason(text.replace("\"tag\": \"front\"",
                "\"tag\": \"front\", \"url\": \"x\"")));
        Map<String, Object> json = new LinkedHashMap<>(Json.parseObject(text));
        json.put("web", 1L);
        assertEquals("invalid plan: 'web' must be an object", assertThrows(ScenarioException.class,
                () -> Plan.fromJson(json)).reason());
    }

    private static String reason(String planText) {
        return assertThrows(ScenarioException.class, () -> Plan.parse(planText)).reason();
    }
}
