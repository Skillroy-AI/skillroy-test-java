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

import ai.skillroy.testjava.samples.lending.LendingState;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 41: {@code loan-counts-and-events} on the headless desk (the {@code ScenarioEHeadlessTest}
 * pattern): its committed plan PASS without sleeping, twice on one desk (the second time with the first loan recorded,
 * which the counts must not depend on), with one {@code capture {source: facts}} record and every expectation held; the
 * negative {@code count-delta-fails} FAIL / ASSERTION naming the captured operand; the desk's own wait through
 * {@code PayloadMatch} (a {@code {has: Key.RETURN}} wait in DEPOSIT ends in SYNC_TIMEOUT). The headless desk is seeded
 * with seed A itself and answers no {@code jvm.properties}, so each plan runs here without its launch property
 * ({@link #onTheHeadlessDesk}); the image runs the committed plan as it is.
 */
class LoanCountsHeadlessTest {

    static final String ID = "loan-counts-and-events";

    @Test
    void theCommittedPlanPassesWithoutSleepingAndCapturesOnTheOpenLoan(@TempDir Path dir) throws Exception {
        Plan committed = Runs.committed(ID);
        assertEquals(Plan.VERSION, committed.planVersion());
        assertEquals(Map.of("lending.seed", "A"), committed.launchProperties());
        Plan plan = onTheHeadlessDesk(committed);
        assertEquals(1L, plan.steps().stream().filter(s -> s.facts() != null).count());
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A)) {
            for (String run : List.of("first", "second")) {
                Runs.Outcome outcome = Runs.run(desk, plan, dir.resolve(run), bind(desk, dir));
                Runs.assertPassedWithoutSleeping(outcome);
                List<Map<String, Object>> captures = Runs.runLog(outcome.dir()).stream()
                        .filter(r -> "capture".equals(r.get("type"))).toList();
                assertEquals(1, captures.size(), run + ": " + captures);
                assertEquals("facts", captures.get(0).get("source"));
                assertEquals("s3", captures.get(0).get("step"));
                assertEquals(Map.of("lines", 1L, "qty", "1", "scans", 1L, "signOffs", 0L), captures.get(0)
                        .get("value"), run + ": the counts on the open loan after one scan, whatever was recorded");
                long expectations = Runs.runLog(outcome.dir()).stream().filter(r -> "expect".equals(r.get("type")))
                        .count();
                assertEquals(11L, expectations, "five in the two expect steps, six in verify, every one held");
                assertEquals(LendingState.SIGNED_OFF, desk.status().state(), "the cleanup signed off");
            }
        }
    }

    @Test
    void theNegativeFailsAsAssertionWithTheCapturedOperand(@TempDir Path dir) throws Exception {
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A)) {
            Runs.Outcome outcome = Runs.run(desk, onTheHeadlessDesk(Runs.negative("count-delta-fails.yaml")), dir,
                    bind(desk, dir));
            assertEquals(Status.FAIL, outcome.result().status(), outcome.describe());
            assertEquals(FailureKind.ASSERTION, outcome.result().failureKind(), outcome.describe());
            assertEquals("s3", outcome.result().failedStepId());
            assertTrue(outcome.result().message().contains("{\"size\":{\"gt\":0}}: size 0: expected a value > 0 but"
                    + " was 0"), "the captured operand in the message: " + outcome.result().message());
            assertEquals(LendingState.SIGNED_OFF, desk.status().state(), "recover signed off");
        }
    }

    @Test
    void theDesksWaitTakesHasByTheAgentsRule(@TempDir Path dir) throws Exception {
        Plan plan = PlanCompiler.compile("""
                scenario: has-return
                pack: lending
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                  item:      { fixture: { code: YRD-001, barcode: "5000000000017" } }
                steps:
                  - signOn: { staff: $assistant }
                  - scan: { item: $item }
                  - key: { action: DUE }
                  - waitFor: { topic: input.requested, match: { state: DEPOSIT, validInputs: { has: Key.DEPOSIT } } }
                  - waitFor: { topic: input.requested, match: { validInputs: { has: Key.RETURN } }, within: 1s }
                timeouts: { step: 5s }
                """.getBytes(StandardCharsets.UTF_8), "has-return.yaml", Runs.PACK);
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A)) {
            Runs.Outcome outcome = Runs.run(desk, plan, dir, bind(desk, dir));
            assertEquals(Status.FAIL, outcome.result().status(), outcome.describe());
            assertEquals(FailureKind.SYNC_TIMEOUT, outcome.result().failureKind(), outcome.describe());
            assertEquals("s5", outcome.result().failedStepId(), "the DEPOSIT wait ended on Key.DEPOSIT, a member");
        }
    }

    /** The plan without its launch properties: the headless desk is seed A's and answers no jvm.properties. */
    static Plan onTheHeadlessDesk(Plan plan) {
        return new Plan(plan.planVersion(), plan.scenarioId(), plan.pack(), plan.packVersion(), plan.sourceHash(),
                plan.requires(), plan.slots(), plan.steps(), plan.verify(), plan.cleanup(), Map.of(), plan.artifacts(),
                plan.onPrompt(), plan.stepTimeoutMs(), plan.ui(), plan.inference(), plan.oracles(),
                plan.resolutionGraph(), plan.bindTimeoutMs(), plan.web(), plan.images());
    }

    private static BindOptions bind(HeadlessDesk desk, Path dir) throws Exception {
        return BindOptions.defaults().withMode(BindMode.LIVE).withEnvId(Env.SEED_A.id)
                .withBindingsRoot(dir.resolve("root")).withCredentials(desk.credentials(dir.resolve("credentials-"
                        + System.nanoTime()))).withLedger(new CostLedger());
    }
}
