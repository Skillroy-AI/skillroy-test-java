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
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.web.WebLib;
import ai.skillroy.testjava.scenario.web.WebSettings;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code web.waitFlag} on the CDP path with the shared guard of amendment A35 (phase4-contracts.md sections 5.2 and
 * 16.1), through the path's fakes (no driver, no browser). The fake frame's {@code waitForFunction} plays the page's
 * polling: it hands the predicate a scripted sequence of guard results (what {@code WebLib.flagExpression} answers in
 * a page) and ends where the predicate's text says a wait ends, at a ready flag or an expression that does not compile;
 * a poll that threw is not ready. A wait that runs out of polls times out, and the path makes its last poll, the
 * agent's guard itself, whose {@code error} becomes {@code lastError}.
 */
class CdpWaitFlagTest {

    private static final String FLAG = "document.getElementById('item').value === 'saw'";
    private static final String NOT_YET = "TypeError: Cannot read properties of null (reading 'value')";
    private static final String PREDICATE_END = ";return r.ready===true||r.compileError!==undefined?r:false;})()";

    private FakeAgent agent;
    private FakeWorld world;
    private FakeWorld.FakePage front;
    private CdpWebPath path;
    /** The guard results the page answers to the main wait's polls, in order. */
    private final List<Map<String, Object>> polls = new ArrayList<>();
    /** What the last poll answers after a timed-out wait; null: it times out too. */
    private Map<String, Object> lastPoll;
    /** What the last poll throws instead, when not null. */
    private PlaywrightException lastPollFailure;

    @BeforeEach
    void open() throws Exception {
        world = new FakeWorld();
        front = world.page("T1", "data:text/html,front", "Front page");
        agent = new FakeAgent().value("web.cdp.status", FakeAgent.status(9222))
                .value("web.browsers", Map.of("browsers", List.of(
                        FakeAgent.browser("b-front", "T1", "front", true, "data:text/html,front"))));
        TargetProbe probe = new TargetProbe(CdpEndpoint.local(9222), "FakeChrome/152", "1.3", List.of(
                new TargetProbe.Target("T1", "page", front.url, "Front page", "ws://t1", true, 2, null)), 4);
        path = new CdpWebPath((e, o, ids) -> probe, (e, o, p) -> world.attach(p, o), false);
        assertNull(path.open(agent, WebSettings.DEFAULT, null));
        front.waitForFunction = a -> {
            String script = (String) a[0];
            if (script.endsWith(PREDICATE_END)) {
                for (Map<String, Object> poll : polls) {
                    if (Boolean.TRUE.equals(poll.get("ready")) || poll.get("compileError") != null) {
                        return FakeWorld.handle(poll);
                    }
                }
                throw new TimeoutError("Timeout exceeded.");
            }
            if (lastPollFailure != null) {
                throw lastPollFailure;
            }
            if (lastPoll == null) {
                throw new TimeoutError("Timeout exceeded.");
            }
            return FakeWorld.handle(lastPoll);
        };
    }

    @AfterEach
    void close() {
        path.close();
    }

    private RpcResult waitFlag(Map<String, Object> args, long callTimeoutMs) throws Exception {
        return path.call("web.waitFlag", args, CallOptions.defaults().withTimeoutMs(callTimeoutMs));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> threw(String error) {
        return map("ready", false, "error", error);
    }

    @Test
    void thePredicateIsTheAgentsGuardAdaptedToWaitForFunction() {
        assertEquals("(function(){var r=" + WebLib.flagExpression(FLAG) + PREDICATE_END, Scripts.flagWait(FLAG));
        assertEquals(Scripts.flagWait(FLAG), Scripts.flagWait(FLAG + "; \n"), "trailing semicolons dropped");
        assertThrows(IllegalArgumentException.class, () -> Scripts.flagWait(" ;\n"));
    }

    @Test
    void aFlagThatThrowsForTheFirstPollsThenTurnsTrueIsReady() throws Exception {
        polls.addAll(List.of(threw(NOT_YET), threw(NOT_YET), threw(NOT_YET), map("ready", true)));
        RpcResult r = waitFlag(map("expression", FLAG, "pollMs", 20), 10_000);
        Map<?, ?> v = (Map<?, ?>) r.value();
        assertEquals(true, v.get("ready"), String.valueOf(v));
        assertFalse(v.containsKey("lastError"));
        assertEquals(1, front.waitForFunctions.size(), "one wait, no last poll");
        assertEquals(Scripts.flagWait(FLAG), front.waitForFunctions.get(0)[0]);
        assertEquals(Map.of("browser", "b-front", "url", front.url, "signal", "FLAG"),
                path.eventsSince(0, "web.ready").get(0).payload());
    }

    @Test
    void aFlagThatThrowsUntilTheEndAnswersReadyFalseWithTheLastPollsError() throws Exception {
        polls.addAll(List.of(threw(NOT_YET), threw(NOT_YET)));
        lastPoll = threw(NOT_YET);
        RpcResult r = waitFlag(map("expression", FLAG, "timeoutMs", 300, "pollMs", 20), 1_300);
        Map<?, ?> v = (Map<?, ?>) r.value();
        assertEquals(List.of("ready", "ms", "polls", "lastError"), new ArrayList<>(v.keySet()), "the agent's shape");
        assertEquals(false, v.get("ready"));
        assertEquals(NOT_YET, v.get("lastError"));
        assertEquals(2, front.waitForFunctions.size(), "the wait, then the last poll");
        Frame.WaitForFunctionOptions wait = (Frame.WaitForFunctionOptions) front.waitForFunctions.get(0)[2];
        assertEquals(300.0, wait.timeout, "the flag's own time");
        Object[] last = front.waitForFunctions.get(1);
        assertEquals(WebLib.flagExpression(FLAG), last[0], "the last poll is the agent's poll, byte for byte");
        double bound = ((Frame.WaitForFunctionOptions) last[2]).timeout;
        assertTrue(bound >= 1 && bound <= CdpWebPath.LAST_POLL_MAX_MS, "bounded: " + bound);
        assertTrue(path.eventsSince(0, "web.ready").isEmpty(), "no FLAG when not ready");
    }

    @Test
    void theLastPollIsBoundedWhateverTheCallsBudget() throws Exception {
        lastPoll = map("ready", false);
        RpcResult r = waitFlag(map("expression", FLAG, "timeoutMs", 200), 60_000);
        assertEquals(false, ((Map<?, ?>) r.value()).get("ready"));
        assertFalse(((Map<?, ?>) r.value()).containsKey("lastError"), "the last poll did not throw");
        assertEquals((double) CdpWebPath.LAST_POLL_MAX_MS,
                ((Frame.WaitForFunctionOptions) front.waitForFunctions.get(1)[2]).timeout);
    }

    @Test
    void aLastPollThatFailsLeavesTheAnswerAloneUnlessThePageClosed() throws Exception {
        lastPollFailure = new PlaywrightException("Protocol error (Runtime.callFunctionOn): no answer");
        Map<?, ?> v = (Map<?, ?>) waitFlag(map("expression", FLAG, "timeoutMs", 200), 1_200).value();
        assertEquals(false, v.get("ready"), "the wait is over; the last poll only explains it");
        assertFalse(v.containsKey("lastError"));
        lastPollFailure = new PlaywrightException("Target page, context or browser has been closed");
        AgentRpcException e = assertThrows(AgentRpcException.class,
                () -> waitFlag(map("expression", FLAG, "timeoutMs", 200), 1_200));
        assertEquals(ErrorCodes.STALE_HANDLE, e.error().code());
    }

    @Test
    void aFlagTheLastPollFindsReadyIsReady() throws Exception {
        polls.add(threw(NOT_YET));
        lastPoll = map("ready", true);
        Map<?, ?> v = (Map<?, ?>) waitFlag(map("expression", FLAG, "timeoutMs", 200), 1_200).value();
        assertEquals(true, v.get("ready"));
        assertFalse(v.containsKey("lastError"));
        assertEquals(1, path.eventsSince(0, "web.ready").size());
    }

    @Test
    void anExpressionThatDoesNotCompileIsScriptErrorAtOnce() {
        polls.add(map("ready", false, "compileError", "SyntaxError: Unexpected token '}'"));
        AgentRpcException e = assertThrows(AgentRpcException.class,
                () -> waitFlag(map("expression", "nonsense(", "timeoutMs", 5_000), 6_000));
        assertEquals("web.waitFlag", e.method());
        assertEquals(ErrorCodes.SCRIPT_ERROR, e.error().code());
        assertEquals("SyntaxError: Unexpected token '}'", e.error().message());
        assertEquals(Map.of("browser", "b-front", "message", "SyntaxError: Unexpected token '}'", "stack", ""),
                e.error().data(), "the agent's data");
        assertEquals(1, front.waitForFunctions.size(), "at once: no last poll");
        assertTrue(path.eventsSince(0, "web.ready").isEmpty());
    }

    @Test
    void aBlankExpressionIsInvalidParamsBeforeThePageIsAsked() {
        AgentRpcException e = assertThrows(AgentRpcException.class,
                () -> waitFlag(map("expression", " ;\n"), 5_000));
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals("expression must not be blank", e.error().message());
        assertEquals(Map.of("param", "expression"), e.error().data());
        assertTrue(front.waitForFunctions.isEmpty());
    }
}
