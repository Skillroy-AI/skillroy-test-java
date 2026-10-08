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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Amendments A29 (click fidelity as a scenario setting), A30 (MODAL_OPEN is an assertion failure) and A32 (event sync
 * for the base UI verbs): compilation, plan format and execution.
 */
class UiFidelityTest {

    private static final String FOCUS_FAILED = "FOCUS_FAILED: focus did not arrive within 500 ms";

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper());

    /**
     * A click where focus never arrives, as the Swing module answers it: with {@code fallback: ACTION} it fires on the
     * ACTION rung and says so; without, it does not fire.
     */
    @BeforeEach
    void focusNeverArrives() {
        agent.capabilities("toy.input", "toy.state", "toy.loan");
        agent.on("swing.click", params -> {
            agent.publish("toy.clicked", Map.of("by", String.valueOf(params.get("by"))));
            if (!"COMPONENT".equals(params.get("rung"))) {
                return Map.of("rung", "ACTION", "fired", true, "fidelity", "FULL");
            }
            if ("ACTION".equals(params.get("fallback"))) {
                return Map.of("rung", "ACTION", "requestedRung", "COMPONENT", "fidelity", "DEGRADED", "reason",
                        FOCUS_FAILED, "fired", true);
            }
            return Map.of("rung", "FOCUS_FAILED", "fired", false, "reason", FOCUS_FAILED);
        });
        agent.value("swing.type", Map.of("mode", "SET", "typed", 4L));
        agent.value("swing.key", Map.of("dispatchedTo", "h3"));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "LOAN")));
        agent.value("swing.snapshot", Map.of("seq", 1L, "truncated", false, "windows", List.of()));
        agent.value("toy.state", Map.of("name", "IDLE"));
    }

    private Plan compile(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    private ScenarioException fails(String yaml) {
        return assertThrows(ScenarioException.class, () -> compile(yaml));
    }

    private static String scenario(String ui, String steps) {
        return "scenario: t\npack: toy\n" + (ui == null ? "" : "ui: " + ui + "\n") + "steps:\n" + steps;
    }

    private RunResult run(String ui, String steps) throws ScenarioException {
        return new PlanExecutor(pack, agent, options).run(compile(scenario(ui, steps)));
    }

    private static Map<String, Object> args(Plan plan, int step, int call) {
        return plan.steps().get(step).calls().get(call).args();
    }

    // ------------------------------------------------------------------------------------------------ compiling

    @Test
    void withoutTheUiKeyClicksAreUnchangedAndThePlanHasNoUi() throws Exception {
        Plan plan = compile(scenario(null, "  - click: { by: { action: DUE } }\n"));
        assertNull(plan.ui());
        assertEquals(Map.of("by", Map.of("action", "DUE")), args(plan, 0, 0));
        assertFalse(plan.toJsonText().contains("\"ui\""), plan.toJsonText());
        assertEquals(UiSettings.DEFAULT, ScenarioLoader.load(scenario(null, "  - mark: x\n")
                .getBytes(StandardCharsets.UTF_8), "t.yaml").effectiveUi());
        assertEquals(14, ScenarioLoader.KEYS.size(), "ui (A29), oracle (Phase 2) and web (Phase 4) make fourteen"
                + " top-level keys");
        assertEquals("ui", ScenarioLoader.KEYS.get(12));
        assertEquals("web", ScenarioLoader.KEYS.get(13));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new UiSettings("ROBOT", false)).getMessage()
                .contains("ACTION or COMPONENT"));
    }

    @Test
    void componentClicksGetActivationAndAFallBackUnlessStrict() throws Exception {
        Plan plan = compile(scenario("{ clickRung: COMPONENT }", "  - click: { by: { action: DUE } }\n"));
        assertEquals(new UiSettings("COMPONENT", false), plan.ui());
        assertEquals(Map.of("by", Map.of("action", "DUE"), "rung", "COMPONENT", "activateWindow", true, "fallback",
                "ACTION"), args(plan, 0, 0));
        assertEquals(Sync.SETTLE, plan.steps().get(0).calls().get(0).sync());
        String text = plan.toJsonText();
        assertTrue(text.contains("\"ui\": {\n    \"clickRung\": \"COMPONENT\",\n    \"strict\": false\n  }"), text);
        assertEquals(text, Plan.parse(text).toJsonText(), "the plan round-trips with its ui");
        assertEquals(plan, Plan.parse(text));

        Plan strict = compile(scenario("{ clickRung: component, strict: true }", "  - click: { target: h4 }\n"));
        assertEquals(new UiSettings("COMPONENT", true), strict.ui());
        assertEquals(Map.of("target", "h4", "rung", "COMPONENT", "activateWindow", true), args(strict, 0, 0));
    }

    @Test
    void theStepsOwnArgumentsWin() throws Exception {
        Plan plan = compile(scenario("{ clickRung: COMPONENT, strict: true }", """
                  - click: { by: { action: A }, rung: ACTION }
                  - click: { by: { action: B }, activateWindow: false }
                  - click: { by: { action: C }, fallback: action }
                  - click: { by: { action: D }, rung: COMPONENT, activateWindow: false, fallback: ACTION }
                """));
        assertEquals(Map.of("by", Map.of("action", "A"), "rung", "ACTION"), args(plan, 0, 0));
        assertEquals(Map.of("by", Map.of("action", "B"), "rung", "COMPONENT", "activateWindow", false),
                args(plan, 1, 0));
        assertEquals(Map.of("by", Map.of("action", "C"), "rung", "COMPONENT", "activateWindow", true, "fallback",
                "ACTION"), args(plan, 2, 0), "a step's fallback wins over the scenario's strict");
        assertEquals(Map.of("by", Map.of("action", "D"), "rung", "COMPONENT", "activateWindow", false, "fallback",
                "ACTION"), args(plan, 3, 0));

        Plan stepStrict = compile(scenario("{ clickRung: COMPONENT }", "  - click: { target: h1, strict: true }\n"
                + "  - click: { target: h2, strict: false }\n"));
        assertEquals(Map.of("target", "h1", "rung", "COMPONENT", "activateWindow", true), args(stepStrict, 0, 0),
                "strict is not sent to the agent");
        assertEquals(Map.of("target", "h2", "rung", "COMPONENT", "activateWindow", true, "fallback", "ACTION"),
                args(stepStrict, 1, 0));

        Plan actionDefault = compile(scenario("{ clickRung: ACTION }", "  - click: { target: h1, strict: true }\n"
                + "  - click: { target: h2, rung: COMPONENT, fallback: ACTION }\n"));
        assertEquals(Map.of("target", "h1"), args(actionDefault, 0, 0));
        assertEquals(Map.of("target", "h2", "rung", "COMPONENT", "fallback", "ACTION"), args(actionDefault, 1, 0));
    }

    @Test
    void invalidUiSettingsAndClickArgumentsAreCompileErrors() {
        assertTrue(fails(scenario("{ clickRung: ROBOT }", "  - mark: x\n")).getMessage()
                .contains("'ui.clickRung' must be ACTION or COMPONENT"));
        assertTrue(fails(scenario("{ clickRung: COMPONENT, speed: 2 }", "  - mark: x\n")).getMessage()
                .contains("unknown key 'ui.speed'"));
        assertTrue(fails(scenario("{ strict: yes }", "  - mark: x\n")).getMessage()
                .contains("'ui.strict' must be true or false"));
        assertTrue(fails(scenario("COMPONENT", "  - mark: x\n")).getMessage().contains("'ui' must be a mapping"));
        assertTrue(fails(scenario("{ clickRung: COMPONENT }", "  - click: { target: h1, fallback: DISPATCH }\n"))
                .getMessage().contains("'fallback' must be ACTION"));
        assertTrue(fails(scenario("{ clickRung: COMPONENT }",
                "  - click: { target: h1, fallback: ACTION, strict: true }\n")).getMessage().contains("contradict"));
        assertTrue(fails(scenario(null, "  - click: { target: h1, fallback: ACTION }\n")).getMessage()
                .contains("needs rung COMPONENT"));
        assertTrue(fails(scenario("{ clickRung: COMPONENT }", "  - click: { target: h1, rung: ACTION, fallback:"
                + " ACTION }\n")).getMessage().contains("needs rung COMPONENT"));
        assertTrue(fails(scenario(null, "  - click: { target: h1, strict: maybe }\n")).getMessage()
                .contains("'strict' must be true or false"));
        assertTrue(fails(scenario(null, "  - type: { target: h1, text: x, strict: true }\n")).getMessage()
                .contains("unknown argument 'strict'"), "strict and fallback belong to click only");
    }

    @Test
    void aHandEditedUiInAPlanIsChecked() throws Exception {
        String text = compile(scenario("{ clickRung: COMPONENT }", "  - mark: x\n")).toJsonText();
        assertTrue(assertThrows(ScenarioException.class, () -> Plan.parse(text.replace("\"COMPONENT\"",
                "\"ROBOT\""))).getMessage().contains("ui:"));
        assertTrue(assertThrows(ScenarioException.class, () -> Plan.parse(text.replace("\"strict\": false",
                "\"strict\": \"no\""))).getMessage().contains("'strict' must be a boolean"));
        assertTrue(assertThrows(ScenarioException.class, () -> Plan.parse(text.replace("\"strict\": false",
                "\"strict\": false, \"speed\": 1"))).getMessage().contains("unknown key 'speed'"));
    }

    @Test
    void awaitOnUiVerbsCompilesToAnEventCallAndASettle() throws Exception {
        Plan plan = compile("scenario: t\npack: toy\ntimeouts: { step: 4s }\nsteps:\n"
                + "  - click: { by: { action: DUE }, await: { topic: toy.clicked, match: { by: x }, within: 2s } }\n"
                + "  - type: { target: h5, text: \"7.00\", await: { topics: [a.b, c.*] } }\n"
                + "  - press: { key: ENTER, by: { name: entry }, await: { topic: toy.pressed } }\n");
        List<Plan.Call> click = plan.steps().get(0).calls();
        assertEquals(2, click.size());
        assertEquals("swing.click", click.get(0).primitive());
        assertEquals(Sync.EVENT, click.get(0).sync());
        assertEquals(new AwaitSpec("toy.clicked", Map.of("by", "x"), 2000), click.get(0).await());
        assertEquals(Map.of("by", Map.of("action", "DUE")), click.get(0).args(), "await is not sent to the agent");
        assertEquals("swing.settle", click.get(1).primitive());
        assertEquals(Sync.NONE, click.get(1).sync());
        assertEquals(Map.of("timeoutMs", 4000L), click.get(1).args());
        assertEquals(6000, click.get(1).timeoutMs());
        assertFalse(click.get(0).handlesPrompt(), "an EVENT wait does not handle a prompt");

        List<Plan.Call> type = plan.steps().get(1).calls();
        assertEquals(new AwaitSpec("a.b,c.*", null, 4000), type.get(0).await(), "within defaults to the step");
        assertEquals("swing.settle", type.get(1).primitive());
        List<Plan.Call> press = plan.steps().get(2).calls();
        assertEquals("swing.key", press.get(0).primitive());
        assertEquals(Map.of("key", "ENTER", "target", Map.of("name", "entry")), press.get(0).args());
        assertEquals(Sync.EVENT, press.get(0).sync());

        assertEquals(plan, Plan.parse(plan.toJsonText()));
        assertTrue(fails(scenario(null, "  - click: { target: h1, await: { within: 1s } }\n")).getMessage()
                .contains("click.await: give exactly one of 'topic' or 'topics'"));
        assertTrue(fails(scenario(null, "  - type: { target: h1, text: x, await: { topic: a, within: 11m } }\n"))
                .getMessage().contains("type.await.within must be at most"));
        assertTrue(fails(scenario(null, "  - dismiss: { button: OK, await: { topic: a } }\n")).getMessage()
                .contains("unknown argument 'await'"));
    }

    // ------------------------------------------------------------------------------------------------ running

    @Test
    void aFallBackIsRecordedAndMakesTheRunDegraded(@TempDir Path dir) throws Exception {
        options = options.withOutputDir(dir);
        RunResult result = run("{ clickRung: COMPONENT }", """
                  - click: { by: { action: DUE } }
                  - click: { by: { action: OK }, rung: ACTION }
                  - expect: { "state.name": IDLE }
                """);
        assertEquals(Status.DEGRADED, result.status(), result.message());
        assertNull(result.failureKind());
        String entry = "s1: click fell back from COMPONENT to ACTION (" + FOCUS_FAILED + ")";
        assertEquals(List.of(entry), result.degradedFidelity());
        assertEquals(List.of(), result.skippedExpectations());
        assertEquals(Status.DEGRADED, result.steps().get(0).status());
        assertEquals(List.of(entry), result.steps().get(0).degradedFidelity());
        assertEquals(Status.PASS, result.steps().get(1).status());
        assertEquals("degraded fidelity: " + entry, result.degradedSummary());

        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            records.add(Json.parseObject(line));
        }
        Map<String, Object> fidelity = records.stream().filter(r -> "fidelity".equals(r.get("type"))).findFirst()
                .orElseThrow();
        assertEquals("s1", fidelity.get("step"));
        assertEquals("COMPONENT", fidelity.get("requestedRung"));
        assertEquals("ACTION", fidelity.get("rung"));
        assertEquals(FOCUS_FAILED, fidelity.get("reason"));
        Map<String, Object> end = records.get(records.size() - 1);
        assertEquals("DEGRADED", end.get("status"));
        assertEquals(List.of(entry), end.get("degradedFidelity"));
        assertEquals(0, pack.recovered.get(), "a DEGRADED run is not recovered");

        String xml = new JunitXmlReporter().render("s", List.of(result), null);
        assertTrue(xml.contains("<skipped message=\"DEGRADED: degraded fidelity: " + entry), xml);
        assertTrue(xml.contains("  degraded " + entry), xml);
        assertTrue(new JunitXmlReporter(RunOptions.DegradedAs.FAILURE).render("s", List.of(result), null)
                .contains("<failure message=\"DEGRADED: degraded fidelity: "));
    }

    @Test
    void strictClicksThatCannotFocusFailTheStep() throws Exception {
        RunResult result = run("{ clickRung: COMPONENT, strict: true }", """
                  - click: { by: { action: DUE } }
                  - mark: never
                """);
        assertEquals(Status.FAIL, result.status(), result.message());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("s1", result.failedStepId());
        assertEquals("click did not fire: FOCUS_FAILED: " + FOCUS_FAILED, result.message());
        assertEquals(Map.of("rung", "FOCUS_FAILED", "fired", false, "reason", FOCUS_FAILED),
                result.detail().get("value"));
        assertTrue(agent.callsTo("swing.settle").isEmpty(), "the step stops before its sync");
        assertEquals(1, pack.recovered.get());
        assertEquals(List.of(), result.degradedFidelity());
    }

    @Test
    void notFiredFailsTypeAndPressToo() throws Exception {
        agent.value("swing.type", Map.of("fired", false, "reason", "the field is not editable"));
        RunResult result = run(null, "  - type: { target: h5, text: x }\n");
        assertEquals(FailureKind.ASSERTION, result.failureKind(), result.message());
        assertEquals("type did not fire: ?: the field is not editable", result.message());
        agent.value("swing.key", Map.of("rung", "DISPATCH", "fired", false));
        result = run(null, "  - press: { key: F2 }\n");
        assertEquals("press did not fire: DISPATCH: no reason given", result.message());
    }

    @Test
    void notFiredFromAPackVerbIsLeftToThePack() throws Exception {
        // Only the base verbs' calls are checked; a pack verb decides itself what its primitive's result means.
        pack.custom = List.of(new PlannedCall("swing.click", Map.of("target", "h1", "rung", "COMPONENT"), Sync.NONE,
                0));
        RunResult result = run(null, "  - custom: {}\n");
        assertEquals(Status.PASS, result.status(), result.message());
    }

    @Test
    void awaitedClickWaitsForTheEventThenSettles() throws Exception {
        RunResult result = run(null, """
                  - click: { by: { action: DUE }, await: { topic: toy.clicked, within: 1s } }
                  - waitFor: { topic: toy.clicked }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        List<String> methods = agent.stepMethods();
        assertEquals(List.of("swing.click", "swing.settle", "sync.await"), methods);
        assertEquals("toy.clicked", agent.awaits.get(0).topics());
        assertEquals(1000, agent.awaits.get(0).timeout().toMillis());
        long clickBefore = agent.awaits.get(0).since();
        assertEquals(clickBefore, ((Number) agent.callsTo("sync.await").get(0).params().get("since")).longValue(),
                "a waitFor after the verb waits from the click, not from its settle");
        assertEquals(Map.of("timeoutMs", 10_000L), agent.callsTo("swing.settle").get(0).params());
    }

    @Test
    void awaitedVerbWithoutItsEventIsASyncTimeout() throws Exception {
        RunResult result = run(null, "  - press: { key: F2, await: { topic: toy.never, within: 1s } }\n");
        assertEquals(Status.FAIL, result.status(), result.message());
        assertEquals(FailureKind.SYNC_TIMEOUT, result.failureKind());
        assertTrue(agent.callsTo("swing.settle").isEmpty());
    }

    @Test
    void anAwaitedVerbWhoseUiDoesNotSettleIsASyncTimeout() throws Exception {
        agent.value("swing.settle", Map.of("settled", false, "ms", 4000L));
        RunResult result = run(null, "  - click: { target: h1, await: { topic: toy.clicked } }\n");
        assertEquals(Status.FAIL, result.status(), result.message());
        assertEquals(FailureKind.SYNC_TIMEOUT, result.failureKind());
        assertEquals("the UI did not settle within 10000 ms after click", result.message());
        assertTrue(result.detail().containsKey("edtState"), result.detail().toString());
    }

    @Test
    void modalOpenIsAnAssertionWithTheDialog() throws Exception {
        agent.on("swing.click", params -> {
            throw new AgentRpcException("swing.click", new RpcError(ErrorCodes.MODAL_OPEN,
                    "a modal dialog blocks the window", Map.of("dialog", "h9", "title", "Confirm", "ref", "h1")));
        });
        agent.value("swing.dialogs", Map.of("dialogs", List.of(
                Map.of("ref", "h8", "title", "Other", "text", "not this one"),
                Map.of("ref", "h9", "title", "Confirm", "text", "Discard the loan?", "modal", true))));
        RunResult result = run(null, "  - click: { target: h1 }\n");
        assertEquals(Status.FAIL, result.status(), result.message());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("swing.click failed: MODAL_OPEN: a modal dialog blocks the window (dialog \"Confirm\":"
                + " \"Discard the loan?\")", result.message());
        assertEquals("Confirm", result.detail().get("dialogTitle"));
        assertEquals("Discard the loan?", result.detail().get("dialogText"));
        assertEquals("MODAL_OPEN", result.detail().get("error"));
    }

    @Test
    void modalOpenWithoutAReadableDialogStillFailsAsAnAssertion() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dialog", "h9");
        agent.on("swing.type", params -> {
            throw new AgentRpcException("swing.type", new RpcError(ErrorCodes.MODAL_OPEN, "blocked", data));
        });
        agent.error("swing.dialogs", ErrorCodes.INTERNAL_ERROR, "no dialogs");
        RunResult result = run(null, "  - type: { target: h1, text: x }\n");
        assertEquals(FailureKind.ASSERTION, result.failureKind(), result.message());
        assertEquals("swing.type failed: MODAL_OPEN: blocked", result.message());
        assertTrue(result.detail().containsKey("dialogTitle"));
        assertNull(result.detail().get("dialogText"));
    }
}
