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
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.CALL;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.PASSWORD;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.READY_TOPICS;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.WAIT;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.value;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.centre;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.first;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.map;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.sameRaster;
import static ai.skillroy.testjava.samples.lending.scenarios.VisionSwingChecks.withoutCarets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The Robot leg (phase7-contracts.md section 13.2 item 6): the desk launched with the agent option {@code robot=true}
 * ({@link LendingTarget.Mode#JAVAAGENT_ROBOT}), once per target Java, on the WM-less display, every Robot call passing
 * {@code requireFocus: false} explicitly where the frame may legitimately hold no focus (BS9 K7). The capability is
 * {@code OK("robot")}; a Robot click on {@code DUE} fires the key; {@code requireFocus} (the default) refuses while the
 * frame holds no focus and passes once it has it; a point outside every window is {@code NOT_FOUND}; {@code PRESS} is
 * refused; a person's glass capture of the frame equals the root-pane {@code PAINT} raster (BS9); and the controller's
 * half of the double opt-in: a plan with {@code pointer {via: ROBOT}} refused without {@code RunOptions.robot}, run
 * with it, and PRECONDITION_FAILED on a target without the agent option.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class RobotChecks {

    LendingTarget target;
    String java;

    /** The leg's mode: {@link LendingTarget.Mode#JAVAAGENT_ROBOT}. */
    abstract LendingTarget.Mode mode();

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        target = LendingTarget.launch(mode());
        java = String.valueOf(value(client().call("jvm.info", Map.of(), CALL)).get("javaVersion"));
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

    Event send(String source, String value) throws Exception {
        RpcResult result = client().call("lending.input", Map.of("source", source, "value", value), CALL);
        assertEquals(Boolean.TRUE, value(result).get("acked"), source + " " + value);
        Event done = client().await(READY_TOPICS, result.seqBefore(), null, WAIT);
        assertNotNull(done);
        return done;
    }

    void activate() throws Exception {
        Map<String, Object> click = call("swing.click", "by", Map.of("id", "entry"), "rung", "COMPONENT",
                "activateWindow", true, "focusTimeoutMs", 20_000L);
        assertEquals(Boolean.TRUE, click.get("fired"), String.valueOf(click));
    }

    @Test
    @Order(10)
    void bothRobotIdsAreDeclaredByTheProbe() throws Exception {
        CapabilityMatrix caps = client().capabilities();
        for (String id : List.of("swing.pointer.robot", "swing.capture.robot")) {
            Capability c = caps.get(id);
            assertEquals(CapabilityState.OK, c.state(), id + ": " + c);
            assertEquals("robot", c.strategy(), id);
        }
        assertEquals("true", String.valueOf(map(value(client().call("agent.info", Map.of(), CALL)).get("options"))
                .get("robot")));
    }

    @Test
    @Order(20)
    void aRobotClickOnDueFiresTheKey() throws Exception {
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", PASSWORD).payload().get("state"));
        assertEquals("LOAN", send("Barcode", "5000000000017").payload().get("state"));
        activate();
        RpcResult click = client().call("swing.pointer", Map.of("by", Map.of("action", "DUE"), "action", "CLICK",
                "via", "ROBOT", "requireFocus", false), CALL);
        Map<String, Object> r = value(click);
        assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
        assertNotNull(r.get("received"));
        assertNotNull(client().await("input.requested", click.seqBefore(), Map.of("state", "DEPOSIT"), WAIT),
                "the Robot click on DUE reached the desk (BS9: 3 of 3)");
        Runs.PACK.recover(client());
    }

    @Test
    @Order(30)
    void requireFocusRefusesAnUnfocusedFrameAndPassesOnceItHasTheFocus() throws Exception {
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", PASSWORD).payload().get("state"));
        RpcResult scan = client().call("lending.input", Map.of("source", "Barcode", "value", "0000000000000"), CALL);
        assertNotNull(client().await("window.opened", scan.seqBefore(), Map.of("modal", true), WAIT));
        RpcResult answer = client().call("lending.prompt.answer", Map.of("choice", "OK"), CALL);
        assertNotNull(client().await(READY_TOPICS, answer.seqBefore(), null, WAIT));
        call("swing.settle", "timeoutMs", 10_000L);
        // On a display without a window manager nothing hands the frame the focus back after the dialog (BS9 K7)
        Object focused = map(call("swing.capture", "scale", 0.25).get("focus")).get("window");
        if (focused == null) {
            AgentRpcException e = assertThrows(AgentRpcException.class, () -> call("swing.pointer", "by",
                    Map.of("id", "entry"), "action", "CLICK", "via", "ROBOT"));
            assertEquals(ErrorCodes.INPUT_REJECTED, e.error().code());
            assertEquals("not focused", e.error().data().get("reason"));
        }
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " focusedAfterPrompt=" + focused);
        activate();
        Map<String, Object> passed = call("swing.pointer", "by", Map.of("id", "entry"), "action", "CLICK", "via",
                "ROBOT");
        assertEquals(Boolean.TRUE, passed.get("fired"), "requireFocus passes with the focus: " + passed);
        Runs.PACK.recover(client());
    }

    @Test
    @Order(40)
    void aPointOutsideEveryWindowAndAPressAreRefused() throws Exception {
        AgentRpcException outside = assertThrows(AgentRpcException.class, () -> call("swing.pointer", "point",
                Map.of("x", 5000L, "y", 5000L), "space", "SCREEN", "action", "CLICK", "via", "ROBOT",
                "requireFocus", false));
        assertEquals(ErrorCodes.NOT_FOUND, outside.error().code());
        assertEquals("outside", outside.error().data().get("reason"));
        AgentRpcException press = assertThrows(AgentRpcException.class, () -> call("swing.pointer", "by",
                Map.of("id", "entry"), "action", "PRESS", "via", "ROBOT"));
        assertEquals(ErrorCodes.INVALID_PARAMS, press.error().code());
    }

    /** A plan whose two pointer steps go through Robot (the compiler adds {@code swing.pointer.robot} to requires). */
    static final String ROBOT_PLAN = """
            scenario: robot-pointer-check
            pack: lending
            requires: [swing.tree, lending.state]
            steps:
              - pointer: { by: { id: numberPad }, region: "key:1", via: ROBOT }
              - pointer: { by: { id: numberPad }, region: "key:clear", via: ROBOT }
            timeouts: { step: 30s }
            """;

    static Plan robotPlan() throws Exception {
        return PlanCompiler.compile(ROBOT_PLAN.getBytes(StandardCharsets.UTF_8), "robot-pointer-check.yaml",
                Runs.PACK);
    }

    static RunResult runWithRobot(AgentClient agent, Plan plan, Path dir, boolean robot) {
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withBind(Runs.bind(SampleCredentials.Env.SEED_A))
                .withRobot(robot);
        return new PlanExecutor(Runs.PACK, agent, options).run(plan);
    }

    /**
     * The controller's half of the double opt-in (phase7-contracts.md 3.4, 9.2, 13.2 item 6, D332): a plan with
     * {@code pointer {via: ROBOT}} is refused ERROR / ENVIRONMENT before the agent is asked anything when the run lacks
     * {@code RunOptions.robot} (a person's {@code --allow-robot}), even on this target where the agent option is set;
     * with the flag it runs here; and on a target started without the agent option it is PRECONDITION_FAILED naming
     * {@code swing.pointer.robot}.
     */
    @Test
    @Order(45)
    void aRobotPlanNeedsThePersonsFlagAndTheAgentOption() throws Exception {
        Plan plan = robotPlan();
        assertTrue(plan.usesRobot());
        assertTrue(plan.requires().contains("swing.pointer.robot"), String.valueOf(plan.requires()));

        Path refusedDir = ItEnv.runDir(mode().slug, "robot-plan-without-flag");
        RunResult refused = runWithRobot(client(), plan, refusedDir, false);
        assertEquals(Status.ERROR, refused.status(), refused.message());
        assertEquals(FailureKind.ENVIRONMENT, refused.failureKind());
        assertEquals("robot: not allowed; a person passes --allow-robot", refused.message());
        assertEquals(List.of(), Runs.calls(refusedDir, "swing.pointer"), "the agent was asked nothing");

        activate();
        Path allowedDir = ItEnv.runDir(mode().slug, "robot-plan-with-flag");
        RunResult allowed = runWithRobot(client(), plan, allowedDir, true);
        assertEquals(Status.PASS, allowed.status(), allowed.message());
        assertEquals(2, Runs.calls(allowedDir, "swing.pointer").size());

        try (LendingTarget plain = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT)) {
            Path preconditionDir = ItEnv.runDir(mode().slug, "robot-plan-without-option");
            RunResult precondition = runWithRobot(plain.client, plan, preconditionDir, true);
            assertEquals(Status.ERROR, precondition.status(), precondition.message());
            assertEquals(FailureKind.PRECONDITION_FAILED, precondition.failureKind(), precondition.message());
            assertTrue(precondition.message().contains("swing.pointer.robot"), precondition.message());
            assertEquals(List.of(), Runs.calls(preconditionDir, "swing.pointer"));
        }
    }

    @Test
    @Order(50)
    void aPersonsGlassCaptureOfTheFrameEqualsTheRootPanePaint() throws Exception {
        String frame = null;
        for (Object w : (List<?>) call("swing.windows").get("windows")) {
            if ("frame".equals(map(w).get("type"))) {
                frame = (String) map(w).get("ref");
            }
        }
        assertNotNull(frame);
        Map<String, Object> glass = call("swing.capture", "target", frame, "mode", "ROBOT");
        assertEquals(Boolean.TRUE, glass.get("glass"));
        assertEquals("SCREEN", glass.get("space"));
        Map<String, Object> paint = call("swing.capture", "target", frame);
        BufferedImage g = withoutCarets(glass);
        BufferedImage p = withoutCarets(paint);
        Map<String, Object> origin = map(paint.get("origin"));
        long ox = ((Number) origin.get("x")).longValue();
        long oy = ((Number) origin.get("y")).longValue();
        int differing = 0;
        for (int y = 0; y < p.getHeight(); y++) {
            for (int x = 0; x < p.getWidth(); x++) {
                if (p.getRGB(x, y) != g.getRGB((int) (x + ox), (int) (y + oy))) {
                    differing++;
                }
            }
        }
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " glassVsPaintDiffering=" + differing);
        assertEquals(0, differing, "BS9: a ROBOT capture equals a root-pane PAINT inside the root pane");
        assertTrue(sameRaster(p, p));
    }
}
