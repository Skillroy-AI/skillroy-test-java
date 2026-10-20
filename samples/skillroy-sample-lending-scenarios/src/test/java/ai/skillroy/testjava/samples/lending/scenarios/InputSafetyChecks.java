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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.junit.OutputScan;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.CALL;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.PASSWORD;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.lendingState;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.value;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.centre;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.first;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.map;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.n;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The tools leg (phase8-contracts.md 13.2 items 1 to 10): the desk launched with {@code lending.tools=true} (its "Desk
 * tools" window, phase8-contracts.md 12.1) and the agent option {@code robot=true}
 * ({@link LendingTarget.Mode#JAVAAGENT_TOOLS}), once per target Java. The lending module's {@code LendingInputVeto}
 * refuses the window's {@code closeMenu} panel: every route that resolves a target is refused, {@code force} or not,
 * readers flag the vetoed components, an allowed sibling fires; the effective visible area (a zero-width pane, a
 * button scrolled out of a viewport); the routed pointer into the AWT {@code Panel} "Scanner"; the AWT controls on the
 * ACTION and COMPONENT rungs; the negative scenario {@code vetoed-click}; and the Robot route refused before anything
 * moves while a Robot click on an allowed button fires.
 *
 * <p>The desk frame spans the 1024 x 768 display and is the active window, so the agent ranks it above the tools
 * window and a routed or Robot pointer call into the tools window is refused {@code covered} unless forced: the calls
 * that must act pass {@code force: true}, which skips the covering and modal rules and never the veto (the refusals
 * below are asked without it and with it). No golden is compared on this leg.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class InputSafetyChecks {

    /** The lending module's refusal. */
    static final String VETO = "closes the desk (the application would end)";
    static final String TITLE = "Desk tools";

    LendingTarget target;
    String java;
    String tools;
    String desk;
    String closeMenu;

    /** The leg's mode: {@link LendingTarget.Mode#JAVAAGENT_TOOLS}. */
    abstract LendingTarget.Mode mode();

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        target = LendingTarget.launch(mode());
        java = String.valueOf(value(client().call("jvm.info", Map.of(), CALL)).get("javaVersion"));
        for (Object o : (List<?>) call("swing.windows").get("windows")) {
            Map<String, Object> w = map(o);
            if (TITLE.equals(w.get("title"))) {
                tools = String.valueOf(w.get("ref"));
            } else {
                desk = String.valueOf(w.get("ref"));
            }
        }
        closeMenu = String.valueOf(node("closeMenu").get("ref"));
    }

    @AfterAll
    void close() {
        if (target != null) {
            target.close();
        }
    }

    AgentClient client() {
        return target.client;
    }

    Map<String, Object> call(String method, Object... kv) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            params.put((String) kv[i], kv[i + 1]);
        }
        return value(client().call(method, params, CALL));
    }

    /** The tools window's counters ({@code app.state.lending.tools}). */
    Map<String, Object> counters() throws Exception {
        Object t = lendingState(client()).get("tools");
        assertNotNull(t, "the tools window's counters are in app.state on this leg");
        return map(t);
    }

    long counter(String name) throws Exception {
        return n(counters().get(name));
    }

    /** The node of a component of the tools window by its id (includeHidden: the collapsed copy too). */
    Map<String, Object> node(String id) throws Exception {
        return first(call("swing.find", "by", Map.of("id", id), "includeHidden", true).get("matches"));
    }

    String ref(String id) throws Exception {
        return String.valueOf(node(id).get("ref"));
    }

    void settle() throws Exception {
        call("swing.settle", "timeoutMs", 10_000L);
    }

    /** INPUT_REJECTED vetoed, with the module's text, the close panel as {@code vetoed} and the route. */
    AgentRpcException vetoed(String route, String method, Object... kv) {
        AgentRpcException e = assertThrows(AgentRpcException.class, () -> call(method, kv), method + " " + route);
        assertEquals(ErrorCodes.INPUT_REJECTED, e.error().code(), method + ": " + e.error());
        Map<String, Object> data = e.error().data();
        assertEquals("vetoed", data.get("reason"), method + " " + data);
        assertEquals(VETO, data.get("veto"), method + " " + data);
        assertEquals(closeMenu, data.get("vetoed"), method + ": the close panel's veto refused " + data);
        assertEquals(route, data.get("route"), method + " " + data);
        return e;
    }

    /** A point in the tools window's space: a component's centre. */
    Map<String, Object> at(String id) throws Exception {
        return centre(node(id));
    }

    // ---------------------------------------------------------------------------------------------- item 1

    @Test
    @Order(10)
    void theToolsWindowIsTheSecondFrameAndItsCountersStartAtZero() throws Exception {
        List<Object> windows = new ArrayList<>((List<?>) call("swing.windows").get("windows"));
        assertEquals(2, windows.size(), String.valueOf(windows));
        assertNotNull(tools, "a window titled Desk tools: " + windows);
        for (Object o : windows) {
            Map<String, Object> w = map(o);
            if (desk.equals(w.get("ref"))) {
                assertEquals(Boolean.TRUE, w.get("focused"), "the desk keeps the focus: " + w);
            } else {
                assertEquals("deskTools", w.get("id"));
                assertEquals(Boolean.FALSE, w.get("focused"), "the tools window never takes it: " + w);
            }
        }
        Map<String, Object> c = counters();
        assertEquals(List.of("closeRequests", "testSlips", "slipsVisible", "slipsHidden", "reprints", "scans",
                "lidOpen", "lidChanges", "rereads", "depositBoxOpens"), new ArrayList<>(c.keySet()));
        for (Map.Entry<String, Object> e : c.entrySet()) {
            assertEquals("lidOpen".equals(e.getKey()) ? Boolean.FALSE : (Object) 0L, e.getValue(), e.getKey());
        }
    }

    // ---------------------------------------------------------------------------------------------- item 2

    @Test
    @Order(20)
    void everyRouteIsRefusedForceOrNot() throws Exception {
        for (Object by : List.of(Map.of("id", "closeDesk"), Map.of("action", "CLOSE"))) {
            for (boolean force : new boolean[] {false, true}) {
                vetoed("swing.click.action", "swing.click", "by", by, "rung", "ACTION", "force", force);
                vetoed("swing.click.action", "swing.click", "by", by, "rung", "AUTO", "force", force);
                vetoed("swing.click.component", "swing.click", "by", by, "rung", "COMPONENT", "activateWindow", true,
                        "force", force);
                vetoed("swing.click.component", "swing.click", "by", by, "rung", "COMPONENT", "fallback", "ACTION",
                        "force", force);
                vetoed("swing.click.dispatch", "swing.click", "by", by, "rung", "DISPATCH", "force", force);
                vetoed("swing.key", "swing.key", "target", by, "key", "SPACE", "force", force);
                vetoed("swing.key", "swing.key", "target", by, "key", "ENTER", "force", force);
                vetoed("swing.pointer.routed", "swing.pointer", "by", by, "action", "CLICK", "force", force);
            }
        }
        vetoed("swing.pointer.routed", "swing.pointer", "point", at("closeDesk"), "space", Map.of("window", tools),
                "action", "CLICK");
        Map<String, Object> shot = call("swing.capture", "target", tools);
        Map<String, Object> origin = map(shot.get("origin"));
        Map<String, Object> c = at("closeDesk");
        vetoed("swing.pointer.routed", "swing.pointer", "point", Map.of("x", n(c.get("x")) - n(origin.get("x")), "y",
                n(c.get("y")) - n(origin.get("y"))), "space", Map.of("captureId", shot.get("captureId")), "action",
                "CLICK", "force", true);
        vetoed("swing.pointer.routed", "swing.pointer", "point", at("printTestSlip"), "space", Map.of("window", tools),
                "to", at("closeDesk"), "action", "DRAG", "force", true);
        vetoed("swing.pointer.robot", "swing.pointer", "by", Map.of("id", "closeDesk"), "action", "CLICK", "via",
                "ROBOT", "requireFocus", false, "force", true);
        settle();
        assertEquals(0L, counter("closeRequests"), "nothing reached the close controls");
        assertEquals(0L, counter("testSlips"), "the refused drag pressed nothing either");
    }

    // ---------------------------------------------------------------------------------------------- item 3

    @Test
    @Order(30)
    void enterIsRefusedWhenItWouldPressTheVetoedDefaultButton() throws Exception {
        String closeDesk = ref("closeDesk");
        AgentRpcException key = vetoed("swing.key", "swing.key", "key", "ENTER", "target", Map.of("id",
                "toolsNote"));
        assertEquals(closeDesk, key.error().data().get("defaultButton"), String.valueOf(key.error().data()));
        AgentRpcException type = vetoed("swing.type.set", "swing.type", "target", Map.of("id", "toolsNote"), "text",
                "x", "enter", true);
        assertEquals(closeDesk, type.error().data().get("defaultButton"));
        Map<String, Object> typed = call("swing.type", "target", Map.of("id", "toolsNote"), "text", "drill bits");
        assertEquals(10L, typed.get("typed"));
        settle();
        assertEquals("drill bits", node("toolsNote").get("text"));
        assertEquals(0L, counter("closeRequests"));
    }

    // ---------------------------------------------------------------------------------------------- item 4

    @Test
    @Order(40)
    void readersFlagTheVetoedControls() throws Exception {
        assertEquals(Boolean.TRUE, call("swing.hitTest", "target", Map.of("id", "closeDesk")).get("vetoed"));
        Map<String, Object> keyNode = first(call("swing.find", "by", Map.of("action", "CLOSE")).get("matches"));
        assertEquals(Boolean.TRUE, keyNode.get("vetoed"));
        assertEquals(Boolean.TRUE, call("swing.hitTest", "point", centre(keyNode), "space", Map.of("window", tools))
                .get("vetoed"));
        Map<String, Object> root = first(call("swing.snapshot", "root", tools).get("windows"));
        Map<String, Boolean> flags = new LinkedHashMap<>();
        collect(root, flags);
        assertEquals(Boolean.TRUE, flags.get(closeMenu));
        assertEquals(Boolean.TRUE, flags.get(ref("closeDesk")));
        assertEquals(Boolean.TRUE, flags.get(String.valueOf(keyNode.get("ref"))));
        assertEquals(Boolean.FALSE, flags.get(ref("printTestSlip")), "an allowed sibling has no flag");
        assertEquals(Boolean.TRUE, node("closeDesk").get("vetoed"), "swing.find answers the flag too");
        Map<String, Object> shot = call("swing.capture", "target", tools, "annotateRefs", true);
        List<String> marked = new ArrayList<>();
        for (Object m : (List<?>) shot.get("marks")) {
            marked.add(String.valueOf(map(m).get("ref")));
        }
        assertFalse(marked.contains(ref("closeDesk")), "no mark for the vetoed button: " + marked);
        assertFalse(marked.contains(String.valueOf(keyNode.get("ref"))), "no mark for the vetoed key: " + marked);
        assertTrue(marked.contains(ref("printTestSlip")), "one for the allowed sibling: " + marked);
        assertEquals(Boolean.FALSE, call("swing.hitTest", "target", Map.of("id", "printTestSlip")).get("vetoed"));
    }

    /** Each node's ref to whether it carries {@code vetoed: true}. */
    private static void collect(Map<String, Object> node, Map<String, Boolean> out) {
        out.put(String.valueOf(node.get("ref")), Boolean.TRUE.equals(node.get("vetoed")));
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object c : list) {
                collect(map(c), out);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- item 5

    @Test
    @Order(50)
    void anAllowedSiblingFires() throws Exception {
        assertEquals(Boolean.TRUE, call("swing.click", "by", Map.of("id", "printTestSlip")).get("fired"));
        settle();
        assertEquals(1L, counter("testSlips"));
    }

    // ---------------------------------------------------------------------------------------------- item 6

    @Test
    @Order(60)
    void theVisibleAreaDecidesWhatMatchesAndWhatIsClicked() throws Exception {
        List<?> matches = (List<?>) call("swing.find", "by", Map.of("text", "Print slip")).get("matches");
        assertEquals(1, matches.size(), "unique with the rule: " + matches);
        assertEquals(ref("printSlipVisible"), map(matches.get(0)).get("ref"));
        Map<String, Object> root = first(call("swing.snapshot", "root", tools).get("windows"));
        Map<String, Boolean> shown = new LinkedHashMap<>();
        collect(root, shown);
        String hidden = ref("printSlipHidden");
        assertFalse(shown.containsKey(hidden), "the snapshot omits the collapsed copy");
        Map<String, Object> all = first(call("swing.snapshot", "root", tools, "includeHidden", true).get("windows"));
        assertEquals(Boolean.TRUE, findNode(all, hidden).get("collapsed"), "with includeHidden it is there, flagged");
        AgentRpcException collapsed = assertThrows(AgentRpcException.class, () -> call("swing.click", "target",
                hidden));
        assertEquals(ErrorCodes.INVALID_PARAMS, collapsed.error().code());
        assertEquals("collapsed", collapsed.error().data().get("reason"));
        assertEquals(ref("collapsedPane"), collapsed.error().data().get("by"));
        call("swing.click", "by", Map.of("text", "Print slip"));
        settle();
        assertEquals(1L, counter("slipsVisible"));
        assertEquals(0L, counter("slipsHidden"));
        List<?> locators = (List<?>) call("swing.hitTest", "target", Map.of("id", "printSlipVisible")).get("locators");
        assertTrue(locators.contains(Map.of("text", "Print slip", "role", "button")), String.valueOf(locators));
        assertEquals(Boolean.TRUE, call("swing.click", "by", Map.of("id", "reprintSlip")).get("fired"));
        settle();
        assertEquals(1L, counter("reprints"), "a button scrolled out of the viewport is clicked (ACTION)");
        AgentRpcException notVisible = assertThrows(AgentRpcException.class, () -> call("swing.pointer", "by",
                Map.of("id", "reprintSlip"), "action", "CLICK"));
        assertEquals(ErrorCodes.INPUT_REJECTED, notVisible.error().code());
        assertEquals("not visible", notVisible.error().data().get("reason"));
        settle();
        assertEquals(1L, counter("reprints"));
    }

    private static Map<String, Object> findNode(Map<String, Object> node, String ref) {
        if (ref.equals(node.get("ref"))) {
            return node;
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object c : list) {
                Map<String, Object> found = findNode(map(c), ref);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------- item 7

    @Test
    @Order(70)
    void theRoutedPointerReachesInsideTheScannerPanel() throws Exception {
        String scanner = ref("scanner");
        for (boolean byPoint : new boolean[] {false, true}) {
            for (String id : List.of("scanButton", "lidOpen", "rereadCard")) {
                String counterName = "scanButton".equals(id) ? "scans" : "lidOpen".equals(id) ? "lidChanges"
                        : "rereads";
                long before = counter(counterName);
                boolean lid = Boolean.TRUE.equals(counters().get("lidOpen"));
                Map<String, Object> r = byPoint
                        ? call("swing.pointer", "point", at(id), "space", Map.of("window", tools), "action", "CLICK",
                                "force", true)
                        : call("swing.pointer", "by", Map.of("id", id), "action", "CLICK", "force", true);
                settle();
                assertEquals(Boolean.TRUE, r.get("fired"), id + ": " + r);
                assertEquals(before + 1, counter(counterName), id + " counted exactly once (" + java + ")");
                assertEquals(ref(id), r.get("received"), id);
                // As built (Phase 8, EA): an AWT control is a heavyweight leaf, its own source (D513 rule 1); the
                // lightweight button inside the panel is sourced from the panel
                assertEquals("rereadCard".equals(id) ? scanner : ref(id), r.get("source"), id);
                assertEquals(tools, r.get("window"));
                if ("lidOpen".equals(id)) {
                    assertEquals(!lid, Boolean.TRUE.equals(counters().get("lidOpen")), "the lid toggled");
                }
                Map<String, Object> hit = call("swing.hitTest", "point", at(id), "space", Map.of("window", tools));
                assertEquals(ref(id), hit.get("target"), id);
                assertEquals(ref(id), map(hit.get("mouseTarget")).get("ref"), id);
                assertEquals("rereadCard".equals(id) ? scanner : ref(id), hit.get("source"), id);
            }
        }
        Map<String, Object> open = call("swing.pointer", "by", Map.of("id", "openDepositBox"), "action", "CLICK",
                "force", true);
        assertEquals(tools, open.get("source"), "a Swing button outside the panel is routed from the window");
        Map<String, Object> again = call("swing.pointer", "by", Map.of("id", "openDepositBox"), "action", "CLICK",
                "force", true);
        long plain = n(map(again.get("dispatched")).get("of"));
        long scans = counter("scans");
        call("swing.pointer", "by", Map.of("id", "scanButton"), "action", "MOVE", "force", true);
        Map<String, Object> back = call("swing.pointer", "by", Map.of("id", "openDepositBox"), "action", "CLICK",
                "force", true);
        settle();
        assertEquals(Boolean.TRUE, back.get("fired"), String.valueOf(back));
        assertEquals(plain + 1, n(map(back.get("dispatched")).get("of")), "one larger: the exit " + back);
        assertEquals(scans, counter("scans"), "the hover fired nothing");
        assertEquals(3L, counter("depositBoxOpens"));
    }

    // ---------------------------------------------------------------------------------------------- item 9

    @Test
    @Order(80)
    void theVetoedClickScenarioFailsAsAssertionAndRecovers() throws Exception {
        Runs.Outcome outcome = Runs.run(client(), Runs.negative("vetoed-click.yaml"),
                ItEnv.runDir(mode().slug, "vetoed-click"));
        RunResult result = outcome.result();
        assertEquals(Status.FAIL, result.status(), outcome.describe());
        assertEquals(FailureKind.ASSERTION, result.failureKind(), outcome.describe());
        assertEquals("s2", result.failedStepId());
        // As built (Phase 8, EA): the message is the agent's (D504: "<component> takes no input from the agent
        // (<route>): <text>"), so it names INPUT_REJECTED and the veto's text; the token "vetoed" is the failure
        // detail's data.reason
        assertTrue(result.message().contains("INPUT_REJECTED") && result.message().contains(VETO), result.message());
        Map<String, Object> data = map(result.detail().get("data"));
        assertEquals("vetoed", data.get("reason"), String.valueOf(result.detail()));
        assertEquals(VETO, data.get("veto"), String.valueOf(result.detail()));
        assertEquals(closeMenu, data.get("vetoed"));
        List<Map<String, Object>> log = Runs.runLog(outcome.dir());
        assertTrue(log.stream().anyMatch(r -> "recover".equals(r.get("type")) && Boolean.TRUE.equals(r.get("ok"))),
                "recover ran and succeeded");
        assertEquals(0L, counter("closeRequests"));
        Map<String, Object> state = value(client().call("lending.state", Map.of(), CALL));
        assertEquals("SIGNED_OFF", state.get("name"), String.valueOf(state));
        assertEquals(List.of(), OutputScan.leaks(outcome.dir(), Set.of(PASSWORD), OutputScan.Scope.ALL),
                "the password appears in no output of the run");
    }

    // ---------------------------------------------------------------------------------------------- item 8

    @Test
    @Order(90)
    void theAwtControlsFireSemanticallyOnTheActionRung() throws Exception {
        long scans = counter("scans");
        assertEquals(Boolean.TRUE, call("swing.click", "by", Map.of("id", "scanButton")).get("fired"));
        settle();
        assertEquals(scans + 1, counter("scans"), "exactly one ActionEvent");
        long changes = counter("lidChanges");
        boolean lid = Boolean.TRUE.equals(counters().get("lidOpen"));
        call("swing.click", "by", Map.of("id", "lidOpen"));
        settle();
        assertEquals(!lid, Boolean.TRUE.equals(counters().get("lidOpen")));
        call("swing.click", "by", Map.of("id", "lidOpen"), "rung", "ACTION");
        settle();
        assertEquals(lid, Boolean.TRUE.equals(counters().get("lidOpen")), "twice back");
        assertEquals(changes + 2, counter("lidChanges"), "one ItemEvent per click");
        Map<String, Object> dispatch = call("swing.click", "by", Map.of("id", "scanButton"), "rung", "DISPATCH");
        assertEquals(Boolean.TRUE, dispatch.get("fired"), "DISPATCH is reported; its effect is the toolkit's");
        settle();
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " scansAfterDispatch=" + counter("scans")
                + " (before " + (scans + 1) + ")");
    }

    /** Item 8's COMPONENT half, run late: it activates the tools window. */
    @Test
    @Order(100)
    void theComponentRungFocusesThenFiresTheAwtControls() throws Exception {
        long scans = counter("scans");
        Map<String, Object> r = call("swing.click", "by", Map.of("id", "scanButton"), "rung", "COMPONENT",
                "activateWindow", true, "focusTimeoutMs", 20_000L);
        settle();
        assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
        assertEquals(scans + 1, counter("scans"));
        long changes = counter("lidChanges");
        r = call("swing.click", "by", Map.of("id", "lidOpen"), "rung", "COMPONENT", "activateWindow", true,
                "focusTimeoutMs", 20_000L);
        settle();
        assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
        assertEquals(changes + 1, counter("lidChanges"));
    }

    // ---------------------------------------------------------------------------------------------- item 10

    @Test
    @Order(110)
    void aRobotClickOnAnAllowedButtonFires() throws Exception {
        long slips = counter("testSlips");
        Map<String, Object> r = call("swing.pointer", "by", Map.of("id", "printTestSlip"), "action", "CLICK", "via",
                "ROBOT", "requireFocus", false, "force", true);
        settle();
        assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
        assertEquals(slips + 1, counter("testSlips"), "the leg's Robot works (" + java + ")");
        vetoed("swing.pointer.robot", "swing.pointer", "by", Map.of("action", "CLOSE"), "action", "CLICK", "via",
                "ROBOT", "requireFocus", false, "force", true);
        assertEquals(0L, counter("closeRequests"), "and still refuses the vetoed key");
    }
}
