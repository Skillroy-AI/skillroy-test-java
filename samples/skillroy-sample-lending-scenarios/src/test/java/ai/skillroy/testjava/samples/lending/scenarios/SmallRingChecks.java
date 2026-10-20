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
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.CALL;
import static ai.skillroy.testjava.samples.lending.scenarios.LoadModeChecks.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The small-ring leg (phase8-contracts.md 13.2 item 34): the desk launched with the agent option {@code eventRing=200}
 * ({@link LendingTarget.Mode#JAVAAGENT_SMALL_RING}), once per target Java. A lost event window is a refusal, not a
 * wrong pass: (a) {@code loan-counts-and-events} is PASS (its window holds far fewer than 200 events); (b) a scenario
 * that publishes 300 marks after sign-on and then reads the {@code events} root ends ERROR / ENVIRONMENT, the
 * evicted range and the agent option named, at that step, with nothing passed; (c) the desk is signed off after each.
 * The ten-full-pages rule is unit-tested only ({@code EventsRootTest}): the two loss conditions share one code path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class SmallRingChecks {

    /** The marks of item 34 (b): more than the ring holds. */
    static final int MARKS = 300;

    LendingTarget target;

    /** The leg's mode: {@link LendingTarget.Mode#JAVAAGENT_SMALL_RING}. */
    abstract LendingTarget.Mode mode();

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        target = LendingTarget.launch(mode());
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

    @Test
    @Order(10)
    void theRingIsTheLegsOption() throws Exception {
        Map<?, ?> options = (Map<?, ?>) value(client().call("agent.info", Map.of(), CALL)).get("options");
        assertEquals(LendingTarget.SMALL_RING, String.valueOf(options.get("eventRing")), String.valueOf(options));
    }

    @Test
    @Order(20)
    void aScenarioWhoseWindowTheRingHoldsPasses() throws Exception {
        String id = LoanCountsHeadlessTest.ID;
        Runs.Outcome outcome = Runs.run(client(), Runs.committed(id), ItEnv.runDir(mode().slug, id));
        Runs.assertPassedWithoutSleeping(outcome);
        assertEquals(1L, Runs.runLog(outcome.dir()).stream().filter(r -> "capture".equals(r.get("type"))).count());
        assertSignedOff();
    }

    @Test
    @Order(30)
    void anEvictedWindowIsAnEnvironmentErrorNamingTheRangeAndTheOption() throws Exception {
        StringBuilder yaml = new StringBuilder("""
                scenario: small-ring-loss
                pack: lending
                launchProperties: { lending.seed: A }
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                steps:
                  - signOn: { staff: $assistant }
                """);
        for (int i = 0; i < MARKS; i++) {
            yaml.append("  - mark: m").append(i).append('\n');
        }
        yaml.append("""
                  - expect: { "events": { size: { gte: 0 } } }
                timeouts: { step: 3s }
                """);
        Plan plan = PlanCompiler.compile(yaml.toString().getBytes(StandardCharsets.UTF_8), "small-ring-loss.yaml",
                Runs.PACK);
        Runs.Outcome outcome = Runs.run(client(), plan, ItEnv.runDir(mode().slug, "small-ring-loss"));
        assertEquals(Status.ERROR, outcome.result().status(), outcome.describe());
        assertEquals(FailureKind.ENVIRONMENT, outcome.result().failureKind(), outcome.describe());
        assertEquals("s" + (MARKS + 2), outcome.result().failedStepId(), "the step that reads the root");
        String message = outcome.result().message();
        assertTrue(message.matches("fact 'events' could not be read: the ring evicted events \\d+\\.\\.\\d+ since"
                + " the run's start; raise the agent option eventRing"), message);
        List<Map<String, Object>> expects = Runs.runLog(outcome.dir()).stream()
                .filter(r -> "expect".equals(r.get("type"))).toList();
        assertEquals(1, expects.size(), String.valueOf(expects));
        assertEquals("failed", expects.get(0).get("result"), "nothing passed");
        assertSignedOff();
    }

    private void assertSignedOff() throws Exception {
        Map<String, Object> state = value(client().call("lending.state", Map.of(), CALL));
        assertEquals("SIGNED_OFF", state.get("name"), String.valueOf(state));
        assertEquals(Boolean.TRUE, state.get("awaitingInput"), String.valueOf(state));
    }
}
