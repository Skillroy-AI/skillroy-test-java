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

import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario E on the headless desk (phase6-contracts.md 13.1 item 7): its committed plan, bound live over the
 * controller-side JDBC channel, is PASS when the matrix shows {@code hook.limit.applied} {@code OK} and the base fact
 * root {@code hooks} answers from the events of the engine's own return path (the desk's scripted hooks module), and
 * DEGRADED, its three guarded expectations skipped and naming the capability, when the desk has no hooks module.
 * One YAML, one plan, both outcomes; the image's {@code ResolutionChecks} runs the same against the real engine.
 */
class ScenarioEHeadlessTest {

    @ParameterizedTest
    @EnumSource(Env.class)
    void passWithTheHookAndDegradedWithout(Env env, @TempDir Path dir) throws Exception {
        Plan plan = Plan.read(ScenarioE.plan());
        try (HeadlessDesk desk = HeadlessDesk.launch(env, true, true)) {
            Runs.Outcome outcome = Runs.run(desk, plan, dir.resolve("hooks"), bind(env, desk, dir));
            Runs.assertPassedWithoutSleeping(outcome);
            assertEquals(List.of(), outcome.result().skippedExpectations());
            Runs.assertHooksRecord(outcome.dir(), true);
            Map<String, Object> hooks = Runs.runLog(outcome.dir()).stream().filter(r -> "hooks".equals(r.get("type")))
                    .findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, hooks.get("alreadyInstalled"), String.valueOf(hooks));
            assertTrue(Runs.runLog(outcome.dir()).stream().anyMatch(r -> "expect".equals(r.get("type"))
                    && "hooks[id=limit.applied].last.returned".equals(r.get("path")) && "held".equals(r.get("result"))));
        }
        try (HeadlessDesk desk = HeadlessDesk.launch(env, false, true)) {
            Runs.Outcome outcome = Runs.run(desk, plan, dir.resolve("plain"), bind(env, desk, dir));
            assertEquals(Status.DEGRADED, outcome.result().status(), outcome.describe());
            assertEquals(3, outcome.result().skippedExpectations().size(), outcome.describe());
            assertTrue(outcome.result().skippedExpectations().stream().allMatch(e -> e.contains(ScenarioE.HOOK)),
                    String.valueOf(outcome.result().skippedExpectations()));
            assertEquals(0, outcome.sleeps());
            Runs.assertHooksRecord(outcome.dir(), false);
        }
    }

    private static BindOptions bind(Env env, HeadlessDesk desk, Path dir) throws Exception {
        return BindOptions.defaults().withMode(BindMode.LIVE).withEnvId(env.id).withBindingsRoot(dir.resolve("root"))
                .withCredentials(desk.credentials(dir.resolve("credentials-" + System.nanoTime())))
                .withLedger(new CostLedger());
    }
}
