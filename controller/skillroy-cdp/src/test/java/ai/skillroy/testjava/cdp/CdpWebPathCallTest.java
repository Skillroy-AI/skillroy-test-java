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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.web.WebLib;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebSettings;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CdpWebPath#call} for every served primitive against fake Playwright pages (no driver, no browser): the
 * shared library's scripts and argument objects, the result shapes, no read of the agent's sequence (A36: {@code
 * seqBefore} and {@code seqAfter} are 0, no {@code evt.query}), the path's own events, the errors with the agent's
 * codes, and the fall-backs to the agent. {@code web.waitFlag}'s guard (A35) has {@link CdpWaitFlagTest}.
 */
class CdpWebPathCallTest {

    private FakeAgent agent;
    private FakeWorld world;
    private FakeWorld.FakePage front;
    private FakeWorld.FakePage back;
    private CdpWebPath path;

    @BeforeEach
    void open() throws Exception {
        world = new FakeWorld();
        front = world.page("T1", "data:text/html,front", "Front page");
        back = world.page("T2", "data:text/html,back", "Back page");
        agent = new FakeAgent().value("web.cdp.status", FakeAgent.status(9222))
                .value("web.browsers", Map.of("browsers", List.of(
                        FakeAgent.browser("b-front", "T1", "front", true, "data:text/html,front"),
                        FakeAgent.browser("b-back", "T2", "back", false, "data:text/html,back"),
                        FakeAgent.browser("b-detached", null, "detached", false, "data:text/html,detached")),
                        // Phase 8 (D548): an agent without masks says so; the masked cases set their own answers.
                        "masksRegistered", false));
        TargetProbe probe = new TargetProbe(CdpEndpoint.local(9222), "FakeChrome/152", "1.3", List.of(
                new TargetProbe.Target("T1", "page", front.url, "Front page", "ws://t1", true, 2, null),
                new TargetProbe.Target("T2", "page", back.url, "Back page", "ws://t2", true, 2, null)), 4);
        path = new CdpWebPath((e, o, ids) -> probe, (e, o, p) -> world.attach(p, o), false);
        assertNull(path.open(agent, WebSettings.DEFAULT, null));
    }

    @AfterEach
    void close() {
        path.close();
    }

    private RpcResult call(String primitive, Map<String, Object> args) throws Exception {
        return path.call(primitive, args, CallOptions.defaults());
    }

    private AgentRpcException rpcError(String primitive, Map<String, Object> args) {
        return assertThrows(AgentRpcException.class, () -> call(primitive, args));
    }

    private String unsupported(String primitive, Map<String, Object> args, CallOptions options) {
        return assertThrows(WebPathException.class, () -> path.call(primitive, args, options)).getMessage();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void evaluateAnswersTheWrapperValueAndReadsNoAgentSequence() throws Exception {
        front.engine = a -> {
            agent.publish();
            return FakeWorld.ok("{\"json\":\"2\",\"typeofResult\":\"number\",\"truncated\":false}");
        };
        RpcResult r = call("web.evaluate", map("expression", "1+1"));
        assertEquals(Map.of("json", "2", "typeofResult", "number"), r.value());
        assertEquals(CdpWebPath.NOT_READ, r.seqBefore(), "A36: the executor reads the agent's sequence, not the path");
        assertEquals(CdpWebPath.NOT_READ, r.seqAfter());
        assertEquals(0L, r.seqBefore());
        assertTrue(r.events().isEmpty(), "the path publishes nothing on the agent's bus");
        assertEquals("controller", r.thread());
        assertEquals(Scripts.evaluate("1+1", false), front.evaluated.get(0), "the default browser's page");
        assertTrue(back.evaluated.isEmpty());
        assertEquals(List.of(), agent.callsTo("evt.query"));
    }

    @Test
    void awaitPromiseAndTruncationAreCarried() throws Exception {
        back.engine = a -> FakeWorld.ok("{\"json\":\"\\\"aaa\",\"typeofResult\":\"string\",\"truncated\":true}");
        RpcResult r = call("web.evaluate", map("expression", "p", "awaitPromise", true, "browser", "b-back"));
        assertEquals(Map.of("json", "\"aaa", "typeofResult", "string", "truncated", true), r.value());
        assertEquals(Scripts.evaluate("p", true), back.evaluated.get(0));
    }

    @Test
    void aThrownScriptIsScriptErrorNamingTheBrowser() {
        front.engine = a -> Map.of("__scriptError", Map.of("message", "Error: boom", "stack", "Error: boom\n at x"));
        AgentRpcException e = rpcError("web.evaluate", map("expression", "boom()"));
        assertEquals("web.evaluate", e.method());
        assertEquals(ErrorCodes.SCRIPT_ERROR, e.error().code());
        assertEquals(Map.of("browser", "b-front", "message", "Error: boom", "stack", "Error: boom\n at x"),
                e.error().data());
    }

    @Test
    void theReadsCallTheLibraryWithTheSharedArgumentObjects() throws Exception {
        front.engine = a -> FakeWorld.ok(Map.of("nodes", List.of(Map.of("role", "button", "name", "Borrow")),
                "truncated", false));
        RpcResult aria = call("web.ariaSnapshot", map());
        assertEquals(Map.of("nodes", List.of(Map.of("role", "button", "name", "Borrow")), "truncated", false),
                aria.value());
        assertEquals(Scripts.call("aria", null, 2000L, true), front.evaluated.get(0));
        call("web.ariaSnapshot", map("maxNodes", 50_000, "interestingOnly", false));
        assertEquals(Scripts.call("aria", null, 20_000L, false), front.evaluated.get(1), "capped at 20000");

        front.engine = a -> FakeWorld.ok(Map.of("text", "hello", "truncated", false));
        assertEquals(Map.of("text", "hello", "truncated", false), call("web.dom.text", map()).value());
        assertEquals(Scripts.call("domText", map("selector", "body", "maxChars", 10_000L)), front.evaluated.get(2));
        call("web.dom.text", map("selector", "#status", "maxChars", 500_000));
        assertEquals(Scripts.call("domText", map("selector", "#status", "maxChars", 100_000L)),
                front.evaluated.get(3));
    }

    @Test
    void aReadWhoseContextANavigationDestroyedIsEvaluatedOnceMore() throws Exception {
        AtomicInteger n = new AtomicInteger();
        front.engine = a -> {
            if (n.incrementAndGet() == 1) {
                throw new PlaywrightException("Execution context was destroyed, most likely because of a navigation");
            }
            return FakeWorld.ok(Map.of("text", "after", "truncated", false));
        };
        assertEquals(Map.of("text", "after", "truncated", false), call("web.dom.text", map()).value());
        assertEquals(2, n.get());
        n.set(0);
        front.engine = a -> {
            n.incrementAndGet();
            throw new PlaywrightException("Execution context was destroyed, most likely because of a navigation");
        };
        AgentRpcException e = rpcError("web.dom.click", map("selector", "#borrow"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals("the CDP call failed: Execution context was destroyed, most likely because of a navigation",
                e.error().message());
        assertEquals(1, n.get(), "a mutating call is never evaluated twice");
    }

    @Test
    void clickWaitsForActionabilityThenLetsTheLibraryAct() throws Exception {
        Map<String, Object> clicked = Map.of("matched", 1L, "clicked", Map.of("role", "button", "name", "Borrow",
                "tag", "BUTTON"), "fidelity", "DOM");
        front.engine = a -> FakeWorld.ok(clicked);
        RpcResult r = call("web.dom.click", map("selector", "#borrow"));
        assertEquals(clicked, r.value());
        assertEquals(List.of("css=#borrow"), front.locators);
        Locator.WaitForOptions wait = front.waits.get(0);
        assertEquals(WaitForSelectorState.VISIBLE, wait.state);
        assertTrue(wait.timeout > 5000 && wait.timeout <= 9000, "the budget less a reserve: " + wait.timeout);
        assertEquals(Scripts.call("domClick", map("selector", "#borrow")), front.evaluated.get(0));

        call("web.dom.click", map("role", "button", "name", "Borrow", "index", 1, "expectVisible", false));
        Object[] role = front.roles.get(0);
        assertEquals(AriaRole.BUTTON, role[0]);
        Frame.GetByRoleOptions options = (Frame.GetByRoleOptions) role[1];
        assertEquals("Borrow", options.name);
        assertEquals(Boolean.TRUE, options.exact);
        assertEquals(Boolean.TRUE, options.includeHidden, "a hidden element may be the target");
        assertEquals(List.of(1), front.nths);
        assertEquals(WaitForSelectorState.ATTACHED, front.waits.get(1).state);
        assertEquals(Scripts.call("domClick", map("role", "button", "name", "Borrow", "index", 1,
                "expectVisible", false)), front.evaluated.get(1));

        call("web.dom.click", map("role", "button", "name", map("regex", "^bor", "ignoreCase", true)));
        Pattern name = (Pattern) ((Frame.GetByRoleOptions) front.roles.get(1)[1]).name;
        assertEquals("^bor", name.pattern());
        assertTrue((name.flags() & Pattern.CASE_INSENSITIVE) != 0);

        call("web.dom.click", map("role", "no-such-role", "name", "x"));
        assertEquals(3, front.waits.size(), "no locator for an unknown role: the library decides");
    }

    @Test
    void aFailedActionabilityWaitIsNotAnErrorButAClosedPageIs() {
        front.waitFailure = new PlaywrightException("Error: strict mode violation: locator('button') resolved to 2"
                + " elements");
        front.engine = a -> FakeWorld.ok(Map.of("__error", "AMBIGUOUS", "data", Map.of("candidates", List.of())));
        AgentRpcException ambiguous = rpcError("web.dom.click", map("role", "button"));
        assertEquals(ErrorCodes.AMBIGUOUS, ambiguous.error().code(), "the library reports it in its own terms");

        front.waitFailure = new TimeoutError("Timeout 9000ms exceeded.");
        front.engine = a -> FakeWorld.ok(Map.of("__error", "NOT_FOUND", "data", Map.of("selector", "#gone")));
        assertEquals(ErrorCodes.NOT_FOUND, rpcError("web.dom.click", map("selector", "#gone")).error().code());

        front.waitFailure = new PlaywrightException("Target page, context or browser has been closed");
        AgentRpcException stale = rpcError("web.dom.click", map("selector", "#x"));
        assertEquals(ErrorCodes.STALE_HANDLE, stale.error().code());
        assertEquals("the browser closed during the step", stale.error().message());
    }

    @Test
    void fillAndDispatchPassTheSharedArgumentObjects() throws Exception {
        front.engine = a -> FakeWorld.ok(Map.of("matched", 1L, "filled", Map.of("tag", "INPUT", "type", "text"),
                "typed", 6L));
        call("web.dom.fill", map("selector", "#item", "text", "hammer", "clear", true, "enter", false));
        assertEquals(Scripts.call("domFill", map("selector", "#item", "text", "hammer", "clear", true,
                "enter", false)), front.evaluated.get(0));
        AgentRpcException noText = rpcError("web.dom.fill", map("selector", "#item"));
        assertEquals(ErrorCodes.INVALID_PARAMS, noText.error().code());
        assertEquals(Map.of("param", "text"), noText.error().data());

        front.engine = a -> FakeWorld.ok(Map.of("dispatched", true));
        assertEquals(Map.of("dispatched", true), call("web.dispatchEvent", map("name", "webhost.setItem", "detail",
                map("item", "saw"))).value());
        assertEquals(Scripts.call("dispatch", map("name", "webhost.setItem", "detail", map("item", "saw"))),
                front.evaluated.get(1));
        call("web.dispatchEvent", map("name", "x", "target", "#form"));
        assertEquals(Scripts.call("dispatch", map("target", "#form", "name", "x")), front.evaluated.get(2));
        assertEquals(ErrorCodes.INVALID_PARAMS, rpcError("web.dispatchEvent", map("name", " ")).error().code());
    }

    @Test
    void secretParametersReadOnlyModeAndUnknownPrimitivesGoToTheAgent() throws Exception {
        assertEquals("a call with secret parameters is served by the agent, which redacts them in its records",
                unsupported("web.dom.fill", map("selector", "#pin", "text", "1234"),
                        CallOptions.defaults().withSecretParams(List.of("text"))));
        assertTrue(front.evaluated.isEmpty());
        assertEquals("the CDP path does not serve web.browsers", unsupported("web.browsers", map(), null));
        assertEquals("the CDP path does not serve web.reload", unsupported("web.reload", map(), null));

        agent.readOnly = true;
        assertNull(path.open(agent, WebSettings.DEFAULT, null));
        assertEquals("the target is in read-only mode: the agent answers web.dom.click",
                unsupported("web.dom.click", map("selector", "#b"), null));
        front.engine = a -> FakeWorld.ok(Map.of("text", "read", "truncated", false));
        assertEquals(Map.of("text", "read", "truncated", false), call("web.dom.text", map()).value(),
                "the reads are served in read-only mode (D51)");
        assertEquals("the target is in read-only mode: the agent answers web.pointer",
                unsupported("web.pointer", map("action", "CLICK", "point", map("x", 1, "y", 1)), null),
                "Phase 7: the pointer is mutating");
        front.engine = a -> FakeWorld.ok(hitValue("button", rect(1, 2, 30, 20), null));
        assertEquals("button", ((Map<?, ?>) ((Map<?, ?>) call("web.hitTest", map("point", map("x", 1, "y", 1)))
                .value()).get("element")).get("tag"), "and the hit-test a read");
    }

    /**
     * phase6-contracts.md 12.9, D156 (security fix S42): {@code web.waitFlag} runs the caller's expression, so a
     * read-only target hands it to the agent (which answers {@code READ_ONLY}), as it hands {@code web.evaluate}.
     */
    @Test
    void aScriptPrimitiveOnAReadOnlyTargetGoesToTheAgent() throws Exception {
        agent.readOnly = true;
        assertNull(path.open(agent, WebSettings.DEFAULT, null));
        assertEquals("the target is in read-only mode: the agent answers web.waitFlag",
                unsupported("web.waitFlag", map("expression", "window.ready === true"), null));
        assertEquals("the target is in read-only mode: the agent answers web.evaluate",
                unsupported("web.evaluate", map("expression", "1"), null));
        assertTrue(front.evaluated.isEmpty(), "nothing reached the page");
        assertTrue(front.waitForFunctions.isEmpty());
        assertEquals(Set.of("web.evaluate", "web.waitFlag"), CdpWebPath.SCRIPT);
    }

    @Test
    void theSelectionPicksThePageOrFallsBack() throws Exception {
        back.engine = a -> FakeWorld.ok(Map.of("text", "back", "truncated", false));
        assertEquals("back", ((Map<?, ?>) call("web.dom.text", map("by", map("url", "back$"))).value()).get("text"));
        assertEquals("back", ((Map<?, ?>) call("web.dom.text", map("by", map("tag", "back"))).value()).get("text"));
        assertEquals("back", ((Map<?, ?>) call("web.dom.text", map("by", map("title", "^Back"))).value())
                .get("text"));
        assertEquals("browser b-detached (browser b-detached) has no CDP target id",
                unsupported("web.dom.text", map("browser", "b-detached"), null));
        assertEquals("no CDP page matches by.url nowhere",
                unsupported("web.dom.text", map("by", map("url", "nowhere")), null));
        back.closed = true;
        assertEquals("no CDP page has the target id T2 of browser b-back",
                unsupported("web.dom.text", map("browser", "b-back"), null));
    }

    // ---------------------------------------------------------------- Phase 7: web.hitTest and web.pointer

    private static Map<String, Object> hitValue(String tag, Map<String, Object> rect, Object child) {
        Map<String, Object> element = map("role", "button", "name", "Go", "tag", tag, "rect", rect);
        Map<String, Object> v = map("element", element, "locators", List.of(map("selector", "#go")), "unique", true);
        if (child != null) {
            v.put("child", child);
        }
        return v;
    }

    private static Map<String, Object> rect(long x, long y, long w, long h) {
        return map("x", x, "y", y, "w", w, "h", h);
    }

    @Test
    void hitTestRunsTheSharedLibraryAndAnswersTheAgentsShape() throws Exception {
        front.engine = a -> FakeWorld.ok(hitValue("button", rect(1, 2, 30, 20), null));
        RpcResult r = call("web.hitTest", map("point", map("x", 10, "y", 12)));
        assertEquals(Scripts.call("hit", 10L, 12L), front.evaluated.get(0), "the bounded library call, as the agent's");
        assertEquals(map("browser", "b-front", "frame", "T1", "viewportPoint", map("x", 10L, "y", 12L), "element",
                map("role", "button", "name", "Go", "tag", "button", "rect", rect(1, 2, 30, 20)), "locators",
                List.of(map("selector", "#go")), "unique", true), r.value());
        front.engine = a -> FakeWorld.ok(map("__error", "NOT_FOUND", "data", map("reason", "outside", "x", 900,
                "y", 1)));
        AgentRpcException outside = rpcError("web.hitTest", map("point", map("x", 900, "y", 1)));
        assertEquals(ErrorCodes.NOT_FOUND, outside.error().code());
        assertEquals("outside", outside.error().data().get("reason"));
        assertEquals("b-front", outside.error().data().get("browser"));
        assertEquals(ErrorCodes.INVALID_PARAMS, rpcError("web.hitTest", map("point", "10,12")).error().code());
        assertEquals("point", rpcError("web.hitTest", map()).error().data().get("param"));
        assertEquals(CdpWebPath.COMPONENT_POINT, unsupported("web.hitTest", map("point", map("x", 1, "y", 1), "space",
                "COMPONENT"), null), "a component point is the agent's");
    }

    @Test
    void hitTestDescendsIntoTheChildFrameTheFrameTreeNames() throws Exception {
        front.frameTree = map("frameTree", map("frame", map("id", "T1", "url", front.url), "childFrames",
                List.of(map("frame", map("id", "F2", "parentId", "T1", "url", "data:text/html,child", "name",
                        "kid")))));
        front.child("data:text/html,child", "kid", a -> FakeWorld.ok(hitValue("span", rect(5, 6, 7, 8), null)));
        front.engine = a -> FakeWorld.ok(hitValue("iframe", rect(100, 200, 300, 150), map("x", 15, "y", 25,
                "name", "kid", "url", "data:text/html,child")));
        Map<?, ?> v = (Map<?, ?>) call("web.hitTest", map("point", map("x", 120, "y", 230))).value();
        assertEquals("F2", v.get("frame"));
        assertEquals(map("key", "F2", "rect", rect(100, 200, 300, 150)), v.get("iframe"));
        assertEquals(rect(110, 211, 7, 8), ((Map<?, ?>) v.get("element")).get("rect"), "moved by (105, 205)");
        assertTrue(front.evaluated.contains("child:" + Scripts.call("hit", 15L, 25L)), String.valueOf(front.evaluated));
    }

    @Test
    void pointerSendsThePlanOnThePagesOwnSessionWithTheSharedParameters() throws Exception {
        front.engine = a -> FakeWorld.ok(hitValue("button", rect(1, 2, 30, 20), null));
        RpcResult r = call("web.pointer", map("action", "CLICK", "point", map("x", 10, "y", 12), "modifiers",
                List.of("CTRL")));
        assertEquals(map("via", "PROTOCOL", "viewportPoint", map("x", 10L, "y", 12L), "dispatched",
                List.of("mouseMoved", "mousePressed", "mouseReleased"), "hit", map("role", "button", "name", "Go",
                        "tag", "button")), r.value());
        assertEquals(List.of(
                "{\"type\":\"mouseMoved\",\"x\":10,\"y\":12,\"button\":\"none\",\"clickCount\":0,\"modifiers\":2}",
                "{\"type\":\"mousePressed\",\"x\":10,\"y\":12,\"button\":\"left\",\"clickCount\":1,\"modifiers\":2}",
                "{\"type\":\"mouseReleased\",\"x\":10,\"y\":12,\"button\":\"left\",\"clickCount\":1,\"modifiers\":2}"),
                front.mouse.stream().map(t -> Json.write(Json.parseObject(t))).toList(),
                "byte-identical to what the agent's jcef adapter sends");
        assertTrue(back.mouse.isEmpty());
        assertFalse(((Map<?, ?>) r.value()).containsKey("visibleInSwing"));
        back.engine = front.engine;
        Map<?, ?> hidden = (Map<?, ?>) call("web.pointer", map("browser", "b-back", "action", "MOVE", "point",
                map("x", 1, "y", 1))).value();
        assertEquals(false, hidden.get("visibleInSwing"), "the agent reports the back browser not showing");
    }

    @Test
    void aBlockedBrowsersPointerIsTheAgentsAndAPointOutsideSendsNothing() throws Exception {
        front.engine = a -> FakeWorld.ok(hitValue("button", rect(1, 2, 30, 20), null));
        Map<String, Object> blocked = FakeAgent.browser("b-front", "T1", "front", true, "data:text/html,front");
        blocked.put("blockedBy", map("dialog", "c-9", "title", "Confirm loan"));
        agent.value("web.browsers", Map.of("browsers", List.of(blocked)));
        String why = unsupported("web.pointer", map("action", "CLICK", "point", map("x", 10, "y", 12)), null);
        assertTrue(why.contains("modal dialog"), why);
        assertTrue(front.mouse.isEmpty(), "nothing sent: the agent answers MODAL_OPEN");
        call("web.pointer", map("action", "CLICK", "point", map("x", 10, "y", 12), "force", true));
        assertEquals(3, front.mouse.size(), "a person's force acts past it");
        front.mouse.clear();
        front.engine = a -> FakeWorld.ok(map("__error", "NOT_FOUND", "data", map("reason", "outside", "x", 900,
                "y", 1)));
        assertEquals(ErrorCodes.NOT_FOUND, rpcError("web.pointer", map("action", "CLICK", "point", map("x", 900,
                "y", 1), "force", true)).error().code());
        assertTrue(front.mouse.isEmpty());
    }

    @Test
    void aFailedEventAfterThePressIsFollowedByOneRelease() throws Exception {
        front.engine = a -> FakeWorld.ok(hitValue("button", rect(1, 2, 30, 20), null));
        front.failMouseAt = 2;
        AgentRpcException e = rpcError("web.pointer", map("action", "CLICK", "point", map("x", 10, "y", 12)));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code(), e.getMessage());
        assertEquals(4, front.mouse.size(), "move, press, the failed release, the balancing release");
        assertTrue(front.mouse.get(3).contains("mouseReleased"), front.mouse.get(3));
    }

    @Test
    void pointerParametersReadOnlyModeAndComponentPointsAreHandled() throws Exception {
        assertEquals("action", rpcError("web.pointer", map("point", map("x", 1, "y", 1))).error().data()
                .get("param"));
        assertEquals("wheel", rpcError("web.pointer", map("action", "WHEEL", "point", map("x", 1, "y", 1)))
                .error().data().get("param"));
        assertEquals(CdpWebPath.COMPONENT_POINT, unsupported("web.pointer", map("action", "MOVE", "point",
                map("x", 1, "y", 1), "space", "COMPONENT"), null));
        assertTrue(front.mouse.isEmpty());
    }

    @Test
    void aFrameKeyIsMappedThroughThePagesFrameTree() throws Exception {
        Map<String, Object> child = map("id", "F2", "parentId", "T1", "url", "data:text/html,child", "urlFragment",
                "#part", "name", "kid");
        front.frameTree = map("frameTree", map("frame", map("id", "T1", "url", front.url), "childFrames",
                List.of(map("frame", child))));
        front.child("data:text/html,child#part", "kid", a -> FakeWorld.ok(Map.of("text", "in the frame",
                "truncated", false)));
        front.child("data:text/html,other", "kid", a -> FakeWorld.ok(Map.of("text", "wrong", "truncated", false)));
        assertEquals("in the frame", ((Map<?, ?>) call("web.dom.text", map("frame", "F2")).value()).get("text"));
        front.engine = a -> FakeWorld.ok(Map.of("text", "main", "truncated", false));
        assertEquals("main", ((Map<?, ?>) call("web.dom.text", map("frame", "T1")).value()).get("text"));
        assertEquals("frame F9 is not in the page's frame tree",
                unsupported("web.dom.text", map("frame", "F9"), null));
    }

    @Test
    void aTapIsAPageInitScriptAndThePollerPublishesTapAndReadyEventsOnThePathsOwnSequence() throws Exception {
        front.engine = a -> FakeWorld.ok(Map.of("installed", 1L));
        RpcResult r = call("web.tap", map("patterns", List.of("webhost.*")));
        assertEquals(Map.of("installed", 1L, "timing", "DOCUMENT_START"), r.value());
        assertEquals(List.of(WebLib.tapInstaller(List.of("webhost.*"), "window")), front.initScripts);
        assertEquals(Scripts.call("tap", List.of("webhost.*"), "window"), front.evaluated.get(0));
        assertTrue(back.initScripts.isEmpty(), "only the selected browser is tapped, as on the agent path");

        String drain = Scripts.call("drain", WebLib.DRAIN_CHARS);
        front.engine = a -> drain.equals(a[0]) ? FakeWorld.ok(Map.of("events", List.of(
                map("name", "webhost.borrowed", "detail", map("item", "hammer", "qty", 2L)),
                map("name", "webhost.big", "detail", "cut", "truncated", true)), "dropped", 0L))
                : FakeWorld.ok(Map.of("installed", 0L));
        path.pollOnce();
        List<Event> taps = path.eventsSince(0, "web.tap");
        assertEquals(2, taps.size());
        assertEquals(Map.of("browser", "b-front", "name", "webhost.borrowed", "detail", Map.of("item", "hammer",
                "qty", 2L)), taps.get(0).payload());
        assertEquals(Map.of("browser", "b-front", "name", "webhost.big", "detail", "cut", "truncated", true),
                taps.get(1).payload());
        List<Event> ready = path.eventsSince(0, "web.ready");
        assertEquals(Map.of("browser", "b-front", "url", front.url, "signal", "TAP", "name", "webhost.borrowed"),
                ready.get(0).payload());
        assertEquals(List.of(1L, 2L, 3L, 4L), path.eventsSince(0, "*").stream().map(Event::seq).toList(),
                "the path's own sequence");
        assertEquals("cdp", taps.get(0).source());
        assertEquals(4, path.lastEventSeq());
        assertEquals(2, path.eventsSince(2, "web.*").size());
        assertTrue(path.eventsSince(4, null).isEmpty());

        assertEquals(Map.of("installed", 0L, "timing", "DOCUMENT_START"),
                call("web.tap", map("patterns", List.of())).value());
        assertEquals(1, front.initScriptsRemoved.get(), "the init script is cancelled");
        int evaluations = front.evaluated.size();
        int pumps = world.pumps.get();
        path.pollOnce();
        assertEquals(evaluations, front.evaluated.size(), "nothing to drain");
        assertEquals(pumps + 1, world.pumps.get(), "a browser-level call lets Playwright deliver page events");
        assertEquals(ErrorCodes.INVALID_PARAMS, rpcError("web.tap", map("patterns", List.of("a"), "target",
                "body")).error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, rpcError("web.tap", map()).error().code());
    }

    /**
     * Security fix P2-L4-F2: a drain whose answer is refused as oversized (the guard's refusal, what a page that
     * replaced the library gets for an answer past the bound) ends that page's tap: its init script is removed, the
     * tap is cleared in its document, and the next poll sends that page no drain; the next {@code web.tap} arms it
     * again.
     */
    @Test
    void anOversizedDrainEndsThePagesTap() throws Exception {
        front.engine = a -> FakeWorld.ok(Map.of("installed", 1L));
        call("web.tap", map("patterns", List.of("webhost.*")));
        String drain = Scripts.call("drain", WebLib.DRAIN_CHARS);
        AtomicInteger drains = new AtomicInteger();
        front.engine = a -> {
            if (drain.equals(a[0])) {
                drains.incrementAndGet();
                return FakeWorld.ok(WebLib.GUARD_PREFIX + "oversized");
            }
            return FakeWorld.ok(Map.of("installed", 0L));
        };
        path.pollOnce();
        assertEquals(1, drains.get());
        assertEquals(1, front.initScriptsRemoved.get(), "the tap's init script is removed");
        assertEquals(Scripts.call("tap", List.of(), "window"), front.evaluated.get(front.evaluated.size() - 1),
                "and the tap cleared in the page's document");
        path.pollOnce();
        path.pollOnce();
        assertEquals(1, drains.get(), "no drain after the oversized one");
        assertTrue(path.eventsSince(0, "web.tap").isEmpty(), "an oversized drain publishes nothing");
        front.engine = a -> drain.equals(a[0]) ? FakeWorld.ok(Map.of("events", List.of(map("name", "webhost.ok")),
                "dropped", 0L)) : FakeWorld.ok(Map.of("installed", 1L));
        call("web.tap", map("patterns", List.of("webhost.*")));
        path.pollOnce();
        assertEquals("webhost.ok", path.eventsSince(0, "web.tap").get(0).payload().get("name"),
                "the next web.tap arms it again");
    }

    @Test
    void thePollerRunsOnItsOwnWhileThePathIsOpen() throws Exception {
        CdpWebPath polling = new CdpWebPath((e, o, ids) -> new TargetProbe(CdpEndpoint.local(9222), "FakeChrome/152",
                "1.3", List.of(), 1), (e, o, p) -> world.attach(p, o), true);
        try {
            assertNull(polling.open(agent, WebSettings.DEFAULT, null));
            String drain = Scripts.call("drain", WebLib.DRAIN_CHARS);
            AtomicInteger drains = new AtomicInteger();
            front.engine = a -> {
                if (!drain.equals(a[0])) {
                    return FakeWorld.ok(Map.of("installed", 1L));
                }
                return FakeWorld.ok(Map.of("events", drains.incrementAndGet() == 1
                        ? List.of(map("name", "webhost.ready")) : List.of(), "dropped", 0L));
            };
            polling.call("web.tap", map("patterns", List.of("webhost.*")), CallOptions.defaults());
            Event tapped = polling.awaitEvent(0, "web.tap", java.time.Duration.ofSeconds(5));
            assertEquals(Map.of("browser", "b-front", "name", "webhost.ready"), tapped.payload());
            assertNull(polling.awaitEvent(polling.lastEventSeq(), "web.tap", java.time.Duration.ofMillis(300)),
                    "drained once, delivered once");
            assertTrue(drains.get() >= 2, "it keeps polling: " + drains.get());
        } finally {
            polling.close();
        }
        int drainsAfterClose = front.evaluated.size();
        assertNull(polling.awaitEvent(polling.lastEventSeq(), "*", java.time.Duration.ofMillis(300)));
        assertEquals(drainsAfterClose, front.evaluated.size(), "the poller stopped with the path");
        assertEquals(Scripts.call("tap", List.of(), "window"), front.evaluated.get(front.evaluated.size() - 1),
                "close removed the tap from the page");
    }

    @Test
    void waitFlagWaitsInPlaywrightAndAnswersReadyFalseOnTimeout() throws Exception {
        front.waitForFunction = a -> FakeWorld.handle(Map.of("ready", true));
        RpcResult r = call("web.waitFlag", map("expression", "window.webhostReady === true"));
        Map<?, ?> v = (Map<?, ?>) r.value();
        assertEquals(true, v.get("ready"));
        assertTrue((Long) v.get("polls") >= 1);
        Object[] args = front.waitForFunctions.get(0);
        assertEquals(Scripts.flagWait("window.webhostReady === true"), args[0]);
        Frame.WaitForFunctionOptions o = (Frame.WaitForFunctionOptions) args[2];
        assertEquals(50.0, o.pollingInterval);
        assertTrue(o.timeout <= 10_000 - 250, "before the call's own bound: " + o.timeout);
        Event flag = path.eventsSince(0, "web.ready").get(0);
        assertEquals(Map.of("browser", "b-front", "url", front.url, "signal", "FLAG"), flag.payload());

        front.waitForFunction = a -> {
            throw new TimeoutError("Timeout 500ms exceeded.");
        };
        RpcResult late = call("web.waitFlag", map("expression", "false", "timeoutMs", 500, "pollMs", 5));
        assertEquals(false, ((Map<?, ?>) late.value()).get("ready"));
        assertFalse(((Map<?, ?>) late.value()).containsKey("lastError"), "the last poll did not answer either");
        Frame.WaitForFunctionOptions o2 = (Frame.WaitForFunctionOptions) front.waitForFunctions.get(1)[2];
        assertEquals(20.0, o2.pollingInterval, "pollMs is at least 20");
        assertEquals(500.0, o2.timeout);
        assertEquals(WebLib.flagExpression("false"), front.waitForFunctions.get(2)[0], "then the last poll (A35)");
        assertEquals(3, front.waitForFunctions.size());
        assertEquals(1, path.eventsSince(0, "web.ready").size(), "no FLAG when not ready");

        assertEquals(ErrorCodes.INVALID_PARAMS, rpcError("web.waitFlag", map("expression", "1", "timeoutMs", -1))
                .error().code());
    }

    @Test
    void resizeIsAnOverrideOnThePagesOwnSessionAndClearRemovesItOnThatSession() throws Exception {
        assertEquals(Shapes.resizeValue(1024, 768), call("web.resize", map("width", 1024, "height", 768,
                "browser", "b-back")).value());
        assertEquals(Map.of("w", 1024L, "h", 768L), back.metrics, "the page reads the override");
        call("web.resize", map("width", 800, "height", 600, "browser", "b-back"));
        assertEquals(List.of("Emulation.setDeviceMetricsOverride", "Emulation.setDeviceMetricsOverride"),
                back.emulation.stream().map(c -> c[0]).toList());
        assertEquals(map("width", 1024L, "height", 768L, "deviceScaleFactor", 0L, "mobile", false),
                back.emulation.get(0)[1], "the parameters the in-process adapter sends");

        assertEquals(Shapes.resizeValue(1, 1), call("web.resize", map("clear", true, "browser", "b-back")).value(),
                "the override is removed, not pinned: the page reads its own 1 x 1 viewport again");
        assertNull(back.metrics);
        Object[] removal = back.emulation.get(2);
        assertEquals("Emulation.clearDeviceMetricsOverride", removal[0]);
        assertSame(back.emulation.get(0)[2], removal[2], "removed on the session that set it (Chromium ignores a"
                + " removal from another session)");
        assertEquals(Shapes.resizeValue(1, 1), call("web.resize", map("clear", true, "browser", "b-back")).value());
        assertEquals(3, back.emulation.size(), "nothing to remove the second time");
        assertTrue(back.resized.isEmpty(), "Playwright's setViewportSize, whose override cannot be removed, is unused");
        assertTrue(front.emulation.isEmpty());

        back.innerSize = Map.of("w", "unreadable");
        assertEquals(Map.of("applied", "OVERRIDE"), call("web.resize", map("clear", true, "browser", "b-back"))
                .value(), "a size that cannot be read is left out, as the agent path leaves it out");
        AgentRpcException bad = rpcError("web.resize", map("width", 0, "height", 10));
        assertEquals(Map.of("param", "width"), bad.error().data());
        assertEquals(Map.of("param", "height"), rpcError("web.resize", map("width", 10)).error().data());
        assertEquals(ErrorCodes.INVALID_PARAMS, rpcError("web.resize", map("width", 20_000, "height", 10))
                .error().code());
    }

    @Test
    void closeRemovesTheOverridesThePathLeftInPlace() throws Exception {
        call("web.resize", map("width", 1024, "height", 768, "browser", "b-back"));
        call("web.resize", map("width", 640, "height", 480));
        call("web.resize", map("clear", true));
        int frontCalls = front.emulation.size();
        path.close();
        assertNull(back.metrics, "an override outlives the session that set it, so close removes it");
        assertEquals("Emulation.clearDeviceMetricsOverride", back.emulation.get(1)[0]);
        assertSame(back.emulation.get(0)[2], back.emulation.get(1)[2], "on the session that set it");
        assertEquals(frontCalls, front.emulation.size(), "an override the scenario removed is not removed again");
    }

    @Test
    void aDrainThatOutlivesItsPollStillPublishesTheEventsItTookAndNoSecondDrainIsSent() throws Exception {
        front.engine = a -> FakeWorld.ok(Map.of("installed", 1L));
        call("web.tap", map("patterns", List.of("webhost.*")));
        String drain = Scripts.call("drain", WebLib.DRAIN_CHARS);
        CountDownLatch inPage = new CountDownLatch(1);
        CountDownLatch pageFree = new CountDownLatch(1);
        AtomicInteger drains = new AtomicInteger();
        front.engine = a -> {
            if (!drain.equals(a[0])) {
                return FakeWorld.ok(Map.of("installed", 0L));
            }
            drains.incrementAndGet();
            inPage.countDown();
            // the page fired the event, then stays busy (a long handler, a modal dialog) past the poll's bound
            pageFree.await();
            return FakeWorld.ok(Map.of("events", List.of(map("name", "webhost.borrowed")), "dropped", 0L));
        };
        long start = System.nanoTime();
        path.pollOnce();
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(inPage.await(5, TimeUnit.SECONDS), "the drain reached the page");
        assertTrue(ms >= CdpWebPath.POLL_BUDGET_MS - 50, "the poll gave up after its bound: " + ms + " ms");
        assertTrue(path.eventsSince(0, "web.tap").isEmpty(), "nothing yet: the page is busy");
        path.pollOnce();
        assertEquals(1, drains.get(), "no second drain while the first is outstanding");
        assertEquals("an earlier CDP operation has not returned (a page may be frozen)",
                unsupported("web.dom.text", map(), null));

        pageFree.countDown();
        Event tapped = path.awaitEvent(0, "web.tap", java.time.Duration.ofSeconds(10));
        assertNotNull(tapped, "the late drain's events are published, not dropped");
        assertEquals(Map.of("browser", "b-front", "name", "webhost.borrowed"), tapped.payload());
        assertEquals(Map.of("browser", "b-front", "url", front.url, "signal", "TAP", "name", "webhost.borrowed"),
                path.awaitEvent(0, "web.ready", java.time.Duration.ofSeconds(10)).payload());
        assertEquals(1, drains.get());
    }

    @Test
    void bitmapIsAProtocolScreenshotScaledAboveMaxWidth() throws Exception {
        front.png = ShapesTest.png(2000, 1000, 0xff102030);
        Map<?, ?> v = (Map<?, ?>) call("web.bitmap", map("maxWidth", 500)).value();
        assertEquals(500L, v.get("width"));
        assertEquals(250L, v.get("height"));
        assertEquals("PROTOCOL", v.get("source"));
        assertArrayEquals(new int[] {500, 250}, Png.size(Base64.getDecoder().decode((String) v.get("base64"))));
        assertEquals(ScreenshotType.PNG, front.screenshots.get(0).type);
        assertTrue(front.screenshots.get(0).timeout <= 20_000, "web.bitmap's budget is 20 s");
        assertEquals(2000L, ((Map<?, ?>) call("web.bitmap", map()).value()).get("width"), "4096 by default");
    }

    @Test
    void aCallThatOutlivesItsBudgetIsTimeoutAndLaterCallsFallBackUntilThePageReturns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        front.engine = a -> {
            release.await();
            return FakeWorld.ok("{\"json\":\"1\",\"typeofResult\":\"number\"}");
        };
        long start = System.nanoTime();
        AgentRpcException e = assertThrows(AgentRpcException.class, () -> path.call("web.evaluate",
                map("expression", "1"), CallOptions.defaults().withTimeoutMs(300L)));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(ErrorCodes.TIMEOUT, e.error().code());
        assertEquals("web.evaluate did not complete within 300 ms over CDP", e.error().message());
        assertEquals(Map.of("timeoutMs", 300L, "path", "cdp"), e.error().data());
        assertTrue(ms < 300 + 2000, "bounded: " + ms + " ms");
        assertEquals("an earlier CDP operation has not returned (a page may be frozen)",
                unsupported("web.dom.text", map(), null));
        release.countDown();
        front.engine = a -> FakeWorld.ok(Map.of("text", "free", "truncated", false));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RpcResult r = null;
        while (r == null && System.nanoTime() < until) {
            try {
                r = call("web.dom.text", map());
            } catch (WebPathException busy) {
                Thread.onSpinWait();
            }
        }
        assertEquals(Map.of("text", "free", "truncated", false), r.value());
    }

    /**
     * A36: a call the path serves reads the agent's sequence not at all (before A36 it read it before and after the
     * primitive, and the executor a third time): no {@code evt.query} for any served primitive, an agent whose
     * {@code evt.query} fails does not stop a call, and both sequence numbers of the result are {@code NOT_READ}.
     */
    @Test
    void aServedCallReadsNoAgentSequence() throws Exception {
        agent.error("evt.query", ErrorCodes.INTERNAL_ERROR, "ring gone");
        front.engine = a -> {
            String script = (String) a[0];
            if (script.equals(Scripts.evaluate("1", false))) {
                return FakeWorld.ok("{\"json\":\"1\",\"typeofResult\":\"number\",\"truncated\":false}");
            }
            if (script.contains(".domText(")) {
                return FakeWorld.ok(Map.of("text", "served", "truncated", false));
            }
            if (script.contains(".dispatch(")) {
                return FakeWorld.ok(Map.of("dispatched", true));
            }
            return FakeWorld.ok(Map.of("nodes", List.of(), "truncated", false));
        };
        front.waitForFunction = a -> FakeWorld.handle(Map.of("ready", true));
        List<RpcResult> results = List.of(call("web.evaluate", map("expression", "1")),
                call("web.dom.text", map()),
                call("web.ariaSnapshot", map()),
                call("web.dispatchEvent", map("name", "lending.ping")),
                call("web.waitFlag", map("expression", "true")),
                call("web.resize", map("width", 800, "height", 600)));
        assertEquals(List.of(), agent.callsTo("evt.query"), "zero evt.query calls for served calls");
        for (RpcResult r : results) {
            assertEquals(CdpWebPath.NOT_READ, r.seqBefore(), String.valueOf(r));
            assertEquals(CdpWebPath.NOT_READ, r.seqAfter(), String.valueOf(r));
        }
        assertEquals(Map.of("text", "served", "truncated", false), results.get(1).value());
    }

    @Test
    void invalidParametersAreInvalidParams() {
        AgentRpcException e = rpcError("web.evaluate", map());
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals("expression is required", e.error().message());
        assertEquals(Map.of("param", "expression"), e.error().data());
        assertEquals(Map.of("param", "awaitPromise"), rpcError("web.evaluate", map("expression", "1",
                "awaitPromise", "yes")).error().data());
        assertEquals(Map.of("param", "maxNodes"), rpcError("web.ariaSnapshot", map("maxNodes", "many")).error()
                .data());
        assertEquals(Map.of("param", "maxChars"), rpcError("web.dom.text", map("maxChars", -1)).error().data());
        assertTrue(agent.callsTo("web.browsers").size() <= 1, "parameters are checked before the agent is asked");
    }

    @Test
    void loadEventsOfThePagesArePublishedOnThePathsOwnSequence() {
        front.fireLoad();
        List<Event> loads = path.eventsSince(0, "web.load");
        assertEquals(Map.of("browser", "b-front", "url", front.url, "kind", "LOAD_FINISHED", "cdpTargetId", "T1"),
                loads.get(0).payload());
        assertEquals(Map.of("browser", "b-front", "url", front.url, "signal", "LOAD"),
                path.eventsSince(0, "web.ready").get(0).payload());
        Frame main = front.main;
        for (var listener : front.navigationListeners) {
            listener.accept(main);
        }
        assertEquals("NAVIGATED", path.eventsSince(0, "web.load").get(1).payload().get("kind"));
        Page popup = world.page("T9", "data:,popup", "Popup").page;
        assertFalse(world.pageListeners.isEmpty(), "new pages are watched as they appear");
        world.pageListeners.get(0).accept(popup);
    }

    /**
     * Security fix S45: a page that {@code by.url} or {@code by.title} picks must carry a target id the agent reports
     * (read once more when unknown), else the call goes to the agent; such a page's load events are not appended.
     */
    @Test
    void aForeignPageAtTheEndpointIsNeverActedOn() throws Exception {
        FakeWorld.FakePage foreign = world.page("T3", "data:text/html,foreign", "Foreign page");
        foreign.engine = a -> FakeWorld.ok(Map.of("text", "foreign", "truncated", false));
        int refreshes = agent.callsTo("web.browsers").size();
        assertEquals("no browser of the target has the CDP page matching url ~ foreign",
                unsupported("web.dom.text", map("by", map("url", "foreign")), null));
        assertEquals("no browser of the target has the CDP page matching title ~ ^Foreign",
                unsupported("web.dom.text", map("by", map("title", "^Foreign")), null));
        assertTrue(foreign.evaluated.isEmpty(), "nothing reached the foreign page");
        assertTrue(agent.callsTo("web.browsers").size() > refreshes, "the agent's list was read once more");
        back.engine = a -> FakeWorld.ok(Map.of("text", "back", "truncated", false));
        assertEquals("back", ((Map<?, ?>) call("web.dom.text", map("by", map("url", "back$"))).value()).get("text"),
                "a page the agent reports is served");

        path.eventsSince(0, "*");
        long before = path.lastEventSeq();
        for (var listener : world.pageListeners) {
            listener.accept(foreign.page);
        }
        foreign.fireLoad();
        assertEquals(before, path.lastEventSeq(), "a foreign page's load is not the target's");
        front.fireLoad();
        assertEquals(before + 2, path.lastEventSeq(), "the target's own page still is");
    }

    /**
     * Security fix S43: the receiving side's bounds on the CDP path: a wrapper's {@code json} over 1 MiB is cut, a
     * library value over 1 MiB is refused, a tap's 70 KiB {@code detail} and a 100 KiB URL are cut, a 10 KiB
     * {@code lastError} is cut to its 2000 characters, and the drain asks for {@code DRAIN_CHARS}.
     */
    @Test
    void pageSuppliedDataIsBoundedOnTheCdpPath() throws Exception {
        String big = "x".repeat(2 << 20);
        front.engine = a -> FakeWorld.ok(Json.write(Map.of("json", big, "typeofResult", "string", "truncated",
                false)));
        Map<?, ?> evaluated = (Map<?, ?>) call("web.evaluate", map("expression", "x")).value();
        assertEquals(WebLib.MAX_JSON_CHARS, ((String) evaluated.get("json")).length());
        assertEquals(true, evaluated.get("truncated"));

        front.engine = a -> FakeWorld.ok(Map.of("text", big, "truncated", false));
        AgentRpcException oversized = rpcError("web.dom.text", map());
        assertEquals(ErrorCodes.INTERNAL_ERROR, oversized.error().code());
        assertEquals("oversized", oversized.error().data().get("reason"));

        front.engine = a -> FakeWorld.ok(Map.of("installed", 1L));
        call("web.tap", map("patterns", List.of("webhost.*")));
        String drain = Scripts.call("drain", WebLib.DRAIN_CHARS);
        front.url = "data:text/html," + "u".repeat(100_000);
        front.engine = a -> drain.equals(a[0]) ? FakeWorld.ok(Map.of("events", List.of(
                map("name", "webhost.big", "detail", map("blob", "d".repeat(70 * 1024)))), "dropped", 0L))
                : FakeWorld.ok(Map.of("installed", 0L));
        path.pollOnce();
        Event tap = path.eventsSince(0, "web.tap").get(0);
        assertEquals(true, tap.payload().get("truncated"));
        assertEquals(WebLib.MAX_DETAIL_CHARS, ((String) tap.payload().get("detail")).length(),
                "the detail's JSON text, cut, as the library keeps it");
        Event ready = path.eventsSince(0, "web.ready").get(0);
        assertEquals(WebLib.MAX_URL_CHARS, ((String) ready.payload().get("url")).length());
        front.fireLoad();
        Event load = path.eventsSince(0, "web.load").get(0);
        assertEquals(WebLib.MAX_URL_CHARS, ((String) load.payload().get("url")).length());

        front.waitForFunction = a -> {
            if (a[0].equals(Scripts.flagWait("x"))) {
                throw new TimeoutError("Timeout 100ms exceeded.");
            }
            return FakeWorld.handle(Map.of("ready", false, "error", "E".repeat(10 * 1024)));
        };
        Map<?, ?> flag = (Map<?, ?>) path.call("web.waitFlag", map("expression", "x", "timeoutMs", 100L),
                CallOptions.defaults().withTimeoutMs(3000L)).value();
        assertEquals(false, flag.get("ready"));
        assertEquals(WebLib.MAX_ERROR_CHARS, ((String) flag.get("lastError")).length());
    }

    /** Security fix S43: the path's own events are bounded by characters as well as by count. */
    @Test
    void thePathsEventsAreBoundedByCharacters() {
        PathEvents events = new PathEvents(1000);
        for (int i = 0; i < 10; i++) {
            events.append("web.tap", Map.of("detail", "d".repeat(300)), null);
        }
        assertTrue(events.chars() <= 1000, "the total stays under the bound: " + events.chars());
        assertEquals(3, events.size(), "the oldest went first");
        assertEquals(10, events.lastSeq());
        Event huge = events.append("web.tap", Map.of("detail", "h".repeat(5000)), null);
        assertEquals(true, huge.payload().get("truncated"));
        assertTrue(((String) huge.payload().get("detail")).length() <= 1000 / 64 + 64);
        assertTrue(events.chars() <= 1000);
        assertEquals(64L << 20, PathEvents.MAX_CHARS);
    }

    // ---------------------------------------------------------------- Phase 8: masked and unmapped pages

    /** The setUp's three entries with {@code masked: true} on the ones named, and the given flag (null: absent). */
    private void agentBrowsers(Boolean masksRegistered, String... maskedRefs) {
        Map<String, Object> listing = new LinkedHashMap<>();
        List<Object> entries = new java.util.ArrayList<>();
        for (Map<String, Object> b : List.of(
                FakeAgent.browser("b-front", "T1", "front", true, "data:text/html,front"),
                FakeAgent.browser("b-back", "T2", "back", false, "data:text/html,back"),
                FakeAgent.browser("b-detached", "T3", "detached", false, ""))) {
            if (List.of(maskedRefs).contains(b.get("ref"))) {
                b.put("url", "");
                b.remove("title");
                b.put("masked", true);
            }
            entries.add(b);
        }
        listing.put("browsers", entries);
        if (masksRegistered != null) {
            listing.put("masksRegistered", masksRegistered);
        }
        agent.value("web.browsers", listing);
        refresh();
    }

    /** Lets the path read the agent's list once more (a by.tag selection that matches nothing reads it). */
    private void refresh() {
        assertThrows(WebPathException.class, () -> call("web.dom.text", map("by", map("tag", "nobody"))));
    }

    private static final List<String> READS = List.of("web.dom.text", "web.ariaSnapshot", "web.bitmap",
            "web.hitTest");

    private static Map<String, Object> readArgs(String primitive, Object... selection) {
        Map<String, Object> args = map(selection);
        if (primitive.equals("web.hitTest")) {
            args.put("point", map("x", 5, "y", 5));
        }
        return args;
    }

    /**
     * Phase 8 (5.2 (iii)): a view-less browser the agent reports masked (the web host's {@code detached}, mapped by its
     * target id) is handed to the agent for every read; {@code redact: false} reads it on the path.
     */
    @Test
    void aViewlessBrowserTheAgentReportsMaskedIsHandedToTheAgent() throws Exception {
        FakeWorld.FakePage detached = world.page("T3", "data:text/html,detached", "Detached page");
        detached.engine = a -> FakeWorld.ok(Map.of("text", "detached", "truncated", false));
        agentBrowsers(true, "b-detached");
        for (String primitive : READS) {
            String why = unsupported(primitive, readArgs(primitive, "browser", "b-detached"), null);
            assertEquals("browser b-detached is masked: the agent answers " + primitive, why);
        }
        assertTrue(detached.evaluated.isEmpty(), "nothing reached the masked page");
        assertEquals("detached", ((Map<?, ?>) call("web.dom.text", map("browser", "b-detached", "redact", false))
                .value()).get("text"), "a person's redact: false reads it on the path");
    }

    /**
     * Phase 8 (5.2 (iii), D551): a page the agent has no entry for (here: the browser left the agent's list between
     * the selection and the read) is handed to the agent while it reports masks registered, or says nothing about
     * them (an older agent), and served while it reports none.
     */
    @Test
    void anUnmappedPagesReadsAreHandedToTheAgentWhileMasksAreRegistered() throws Exception {
        front.engine = a -> FakeWorld.ok(Map.of("text", "front", "truncated", false));
        for (Boolean flag : new Boolean[] {Boolean.TRUE, null, Boolean.FALSE}) {
            java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
            agent.on("web.browsers", params -> {
                Map<String, Object> listing = new LinkedHashMap<>();
                // the first read selects b-front; the next one (the check before the read) no longer lists it
                listing.put("browsers", asked.getAndIncrement() == 0 ? List.of(FakeAgent.browser("b-front", "T1",
                        "front", true, "data:text/html,front")) : List.of());
                if (flag != null) {
                    listing.put("masksRegistered", flag);
                }
                return listing;
            });
            if (Boolean.FALSE.equals(flag)) {
                assertEquals("front", ((Map<?, ?>) call("web.dom.text", map("by", map("tag", "front"))).value())
                        .get("text"), "no masks: served as before");
            } else {
                String why = unsupported("web.dom.text", map("by", map("tag", "front")), null);
                assertTrue(why.startsWith("the page maps to no browser of the agent while masks are registered"),
                        flag + ": " + why);
            }
        }
    }

    /**
     * Phase 8 (5.2 (iii), D551): a {@code by: {url|title}} selection is the agent's while it reports masks registered
     * or says nothing about them; served on the path while it reports none.
     */
    @Test
    void aUrlOrTitleSelectionIsTheAgentsWhileMasksAreRegistered() throws Exception {
        back.engine = a -> FakeWorld.ok(Map.of("text", "back", "truncated", false));
        agentBrowsers(true);
        assertEquals("a by.url selection is the agent's while masks are registered",
                unsupported("web.dom.text", map("by", map("url", "back$")), null));
        assertEquals("a by.title selection is the agent's while masks are registered",
                unsupported("web.dom.text", map("by", map("title", "^Back")), null));
        agentBrowsers(null);
        assertEquals("a by.url selection is the agent's while masks are registered",
                unsupported("web.dom.text", map("by", map("url", "back$")), null), "an absent field counts true");
        assertTrue(back.evaluated.isEmpty(), "nothing reached a page by its own URL or title");
        agentBrowsers(false);
        assertEquals("back", ((Map<?, ?>) call("web.dom.text", map("by", map("url", "back$"))).value()).get("text"));
    }

    /**
     * Phase 8 (5.2 (iv), D637): {@code web.pointer}, {@code web.dom.click} and {@code web.dom.fill} of a page the agent
     * reports masked, or of an unmapped page while masks are registered, are handed to the agent (which answers
     * without page data); served while no mask is registered; {@code redact: false} keeps them on the path.
     */
    @Test
    void theThreeActionsOfAMaskedOrUnmappedPageAreHandedToTheAgent() throws Exception {
        back.engine = a -> FakeWorld.ok(hitValue("button", rect(1, 2, 30, 20), null));
        Map<String, Map<String, Object>> actions = new LinkedHashMap<>();
        actions.put("web.pointer", map("browser", "b-back", "action", "CLICK", "point", map("x", 10, "y", 12)));
        actions.put("web.dom.click", map("browser", "b-back", "selector", "#go"));
        actions.put("web.dom.fill", map("browser", "b-back", "selector", "#go", "text", "x"));
        agentBrowsers(true, "b-back");
        for (Map.Entry<String, Map<String, Object>> a : actions.entrySet()) {
            assertEquals("browser b-back is masked: the agent answers " + a.getKey(),
                    unsupported(a.getKey(), a.getValue(), null));
        }
        assertTrue(back.mouse.isEmpty() && back.evaluated.isEmpty(), "nothing reached the masked page");

        agentBrowsers(false);
        back.engine = a -> FakeWorld.ok(hitValue("button", rect(1, 2, 30, 20), null));
        Map<?, ?> pointed = (Map<?, ?>) call("web.pointer", actions.get("web.pointer")).value();
        assertTrue(pointed.containsKey("hit"), "no masks: served with its hit");

        agentBrowsers(true, "b-back");
        back.mouse.clear();
        Map<String, Object> unredacted = new LinkedHashMap<>(actions.get("web.pointer"));
        unredacted.put("redact", false);
        assertTrue(((Map<?, ?>) call("web.pointer", unredacted).value()).containsKey("hit"),
                "a person's redact: false keeps the path's answer");
        assertEquals(3, back.mouse.size());

        // the selection takes b-back from the path's cache; the agent's list read before the action no longer has it
        agentBrowsers(true);
        agent.value("web.browsers", Map.of("browsers", List.of(), "masksRegistered", true));
        assertTrue(unsupported("web.dom.click", actions.get("web.dom.click"), null)
                .startsWith("the page maps to no browser of the agent while masks are registered"));
    }

    /**
     * Phase 8 (5.2 (iii), D551): the path's own events of a page the agent reports masked carry {@code url: ""}, no
     * {@code detail} and {@code masked: true}; a step's {@code name} and {@code signal} stay.
     */
    @Test
    void theOwnEventsOfAMaskedPageWithholdPageData() throws Exception {
        agentBrowsers(true, "b-front");
        front.engine = a -> FakeWorld.ok(Map.of("installed", 1L));
        call("web.tap", map("browser", "b-front", "patterns", List.of("webhost.*"), "redact", false));
        long seq = path.lastEventSeq();
        front.fireLoad();
        assertEquals(Map.of("browser", "b-front", "url", "", "kind", "LOAD_FINISHED", "cdpTargetId", "T1", "masked",
                true), path.eventsSince(seq, "web.load").get(0).payload());
        assertEquals(Map.of("browser", "b-front", "url", "", "signal", "LOAD", "masked", true),
                path.eventsSince(seq, "web.ready").get(0).payload());
        String drain = Scripts.call("drain", WebLib.DRAIN_CHARS);
        front.engine = a -> drain.equals(a[0]) ? FakeWorld.ok(Map.of("events", List.of(map("name",
                "webhost.member", "detail", map("name", "a member's name"))), "dropped", 0L))
                : FakeWorld.ok(Map.of("installed", 0L));
        seq = path.lastEventSeq();
        path.pollOnce();
        assertEquals(Map.of("browser", "b-front", "name", "webhost.member", "masked", true),
                path.eventsSince(seq, "web.tap").get(0).payload());
        assertEquals(Map.of("browser", "b-front", "url", "", "signal", "TAP", "name", "webhost.member", "masked",
                true), path.eventsSince(seq, "web.ready").get(0).payload());
        for (Event e : path.eventsSince(0, "*")) {
            assertFalse(String.valueOf(e.payload()).contains("member's name"), String.valueOf(e.payload()));
        }
        agentBrowsers(false);
        call("web.dom.text", map("browser", "b-front"));
        seq = path.lastEventSeq();
        front.fireLoad();
        assertEquals(front.url, path.eventsSince(seq, "web.load").get(0).payload().get("url"),
                "an unmasked page's events are as before");
    }
}
