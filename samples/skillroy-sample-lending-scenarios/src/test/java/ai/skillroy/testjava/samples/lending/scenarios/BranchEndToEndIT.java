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

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The variant's acceptance (phase6-contracts.md 11.4, 13.2 item 8; decision D146): the sample desk's branch edition
 * ({@code -Dlending.edition=branch}, seed A, the hooks module loaded), each half on a freshly launched desk.
 *
 * <ol>
 *   <li>With the overlay {@code lending-branch} (its jar beside the base pack's, {@link BranchVariant}): scenarios A,
 *       B and E are approved into {@code variants/branch/bindings/branch-a/} (the committed bytes, label
 *       {@code approve}; {@code -Dskillroy.bindings.update=true} writes them) and replay PASS from the variant's
 *       committed plans, E with its three guarded expectations evaluated: the overlay's resolver, constant, allow-list
 *       and catalogue at work, the scenarios unchanged; {@code hook.limit.applied} is {@code OK} on the overlay's
 *       target, {@code ReturnCeilings.applicable}.</li>
 *   <li>The base pack alone on a fresh branch desk: E with hooks off is DEGRADED (the guard skips), with hooks on the
 *       base catalogue installs on {@code AccessControl.returnLimit}, which nothing of E's steps reaches in this
 *       edition, so the guarded expectations fail as FAIL / ASSERTION with the actual
 *       {@code hooks[id=limit.applied].count = 0}: the overlay's catalogue is needed, not decorative.</li>
 * </ol>
 *
 * <p>Needs a display (skipped when headless); run it with {@code ci/run-in-linux.sh mvn -o verify -pl
 * samples/skillroy-sample-lending-scenarios -am -Dskillroy.headless=false -Dit.test=BranchEndToEndIT}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BranchEndToEndIT {

    static final String SLUG = "branch";

    static BindOptions bind(BindMode mode) {
        return Runs.bind(Env.SEED_A).withEnvId(BranchVariant.ENV).withBindingsRoot(BranchVariant.bindingsRoot())
                .withMode(mode);
    }

    @Test
    @Order(1)
    void withTheOverlayScenariosABAndEPassOnTheBranchDesk() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        DomainPack pack = BranchVariant.pack();
        assertEquals(BranchVariant.PACK_VERSION, pack.version());
        try (LendingTarget target = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT_HOOKS, "A", false,
                BranchVariant.EDITION)) {
            assertEquals("branch", LoadModeChecks.lendingState(target.client).get("edition"));
            for (String id : BranchVariant.SCENARIOS) {
                Plan plan = Plan.read(BranchVariant.plan(id));
                Path root = ItEnv.runDir(SLUG, id + "/approve-root");
                ResolutionChecks.deleteTree(root);
                BindOutcome approved = Binder.bind(plan, pack, target.client, bind(BindMode.APPROVE)
                        .withBindingsRoot(root).withApprover(request -> new Approver.Decision(true, null, "approve")),
                        BindListener.NONE);
                assertNotNull(approved.written(), id);
                Path committed = BranchVariant.committedBindings(id);
                if (Boolean.getBoolean("skillroy.bindings.update")) {
                    Files.createDirectories(committed.getParent());
                    Files.copy(approved.written(), committed, StandardCopyOption.REPLACE_EXISTING);
                }
                assertEquals(Files.readString(committed, StandardCharsets.UTF_8),
                        Files.readString(approved.written(), StandardCharsets.UTF_8), id + ": the committed bytes");
                Runs.Outcome outcome = Runs.run(pack, target.client, plan, ItEnv.runDir(SLUG, id + "/replay"),
                        bind(BindMode.REPLAY));
                Runs.assertPassedWithoutSleeping(outcome);
                assertEquals(List.of(), outcome.result().skippedExpectations(), id);
                Runs.assertHooksRecord(outcome.dir(), true);
                ItEnv.timing("branch scenario=" + id + " status=" + outcome.result().status() + " ms="
                        + outcome.result().duration().toMillis());
            }
            // phase8-contracts.md 12.3, 13.2 item 40: the pack's capability, the base's, reads the branch's row
            // return_ceiling through the overlay's settings, and is OK in scenario A's run on the branch desk.
            Map<String, Object> capabilities = Runs.capabilitiesRecord(ItEnv.runDir(SLUG, ScenarioA.ID + "/replay"));
            Map<?, ?> ceiling = (Map<?, ?>) ((List<?>) capabilities.get("pack")).get(0);
            assertEquals(List.of(LendingPack.CAP_RETURNS_CEILING, "OK"), List.of(ceiling.get("id"),
                    ceiling.get("state")), String.valueOf(capabilities));
            List<Object> asked = new java.util.ArrayList<>();
            ai.skillroy.testjava.data.DataChannel agentData = new ai.skillroy.testjava.data.AgentDataChannel(
                    target.client);
            ai.skillroy.testjava.data.DataChannel recording = new ai.skillroy.testjava.data.DataChannel() {
                @Override
                public boolean serves(String source) throws ai.skillroy.testjava.controller.AgentLostException {
                    return agentData.serves(source);
                }

                @Override
                public ai.skillroy.testjava.protocol.sql.Rows query(String source, String sql, List<Object> params,
                        int maxRows, java.time.Duration timeout) throws ai.skillroy.testjava.data.DataException,
                        ai.skillroy.testjava.controller.AgentLostException, InterruptedException {
                    asked.addAll(params);
                    return agentData.query(source, sql, params, maxRows, timeout);
                }

                @Override
                public String kind(String source) {
                    return agentData.kind(source);
                }
            };
            ai.skillroy.testjava.protocol.Capability evaluated = pack.capabilities().get(0).evaluate(
                    new ai.skillroy.testjava.scenario.pack.PreflightCheck.PreflightContext() {
                        @Override
                        public ai.skillroy.testjava.controller.AgentConnection agent() {
                            return target.client;
                        }

                        @Override
                        public ai.skillroy.testjava.data.DataChannel data() {
                            return recording;
                        }

                        @Override
                        public String envId() {
                            return BranchVariant.ENV;
                        }

                        @Override
                        public ai.skillroy.testjava.scenario.pack.PackSettings settings() {
                            return pack.settings();
                        }
                    });
            assertEquals(CapabilityState.OK, evaluated.state(), String.valueOf(evaluated));
            assertEquals(List.of("return_ceiling"), asked, "the branch's row");
            CapabilityMatrix caps = target.client.capabilities();
            assertTrue(caps.isOk("hook.limit.applied"), String.valueOf(caps.get("hook.limit.applied")));
            Map<String, Object> health = LoadModeChecks.value(target.client.call("hook.health", Map.of(),
                    LoadModeChecks.CALL));
            assertEquals("lending-branch", ((Map<?, ?>) health.get("catalogue")).get("id"));
            Map<?, ?> limit = (Map<?, ?>) ((Map<?, ?>) health.get("hooks")).get("limit.applied");
            assertEquals("FIRING", limit.get("state"), String.valueOf(limit));
            Map<?, ?> last = (Map<?, ?>) limit.get("last");
            assertEquals("ai.skillroy.testjava.samples.lending.ReturnCeilings", last.get("class"), String.valueOf(last));
            assertEquals("applicable", last.get("method"));
        }
    }

    @Test
    @Order(2)
    void theBasePackAloneShowsTheOverlaysCatalogueIsNeeded() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        Plan plan = Plan.read(ScenarioE.plan());
        try (LendingTarget target = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT_HOOKS, "A", false,
                BranchVariant.EDITION)) {
            RunResult off = run(target, plan, HooksMode.OFF, "e-base-hooks-off");
            assertEquals(Status.DEGRADED, off.status(), off.message());
            assertEquals(3, off.skippedExpectations().size(), String.valueOf(off.skippedExpectations()));
            Runs.PACK.recover(target.client);

            RunResult on = run(target, plan, HooksMode.ON, "e-base-hooks-on");
            assertEquals(Status.FAIL, on.status(), on.message());
            assertEquals(FailureKind.ASSERTION, on.failureKind(), on.message());
            assertEquals("hooks[id=limit.applied].count", on.detail().get("path"), String.valueOf(on.detail()));
            assertEquals(0L, ((Number) on.detail().get("actual")).longValue(), String.valueOf(on.detail()));
            Map<String, Object> health = LoadModeChecks.value(target.client.call("hook.health", Map.of(),
                    LoadModeChecks.CALL));
            assertEquals("lending", ((Map<?, ?>) health.get("catalogue")).get("id"), "the base catalogue");
            Map<?, ?> limit = (Map<?, ?>) ((Map<?, ?>) health.get("hooks")).get("limit.applied");
            assertEquals("INSTALLED", limit.get("state"), "installed on returnLimit and never fired: " + limit);
            assertFalse(Json.write(health).contains("ReturnCeilings"), String.valueOf(health));
        }
    }

    private static RunResult run(LendingTarget target, Plan plan, HooksMode hooks, String what) {
        RunOptions options = RunOptions.defaults().withOutputDir(ItEnv.runDir(SLUG, what)).withBind(
                bind(BindMode.REPLAY)).withHooks(hooks).withStepTimeout(Duration.ofSeconds(10));
        return new PlanExecutor(Runs.PACK, target.client, options).run(plan);
    }
}
