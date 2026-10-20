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
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PackSettings;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 9.1, 9.4, 13.1 row 50: the lending pack's capability {@code lending.returns.ceiling} on the
 * headless desk, both environments, the desk's database read over the controller-side JDBC channel. Scenario A, which
 * requires it (the gap 24 proof), passes in {@code live} with the {@code capabilities} record listing the pack's entry
 * {@code OK}; under a test overlay whose {@code lending.maxReturnSetting} names a row the desk does not have it ends
 * {@code PRECONDITION_FAILED} naming the capability and its reason, before the pre-flight and the bind, nothing sent to
 * the desk and no cleanup or recover record.
 *
 * <p>Scenario A is compiled from its YAML (which carries the {@code requires} entry), not read from its committed plan:
 * the plan is regenerated in step R (phase8-contracts.md 2.8), and the two are the same after it.
 */
class PackCapabilityHeadlessTest {

    /** A row name the desks' {@code desk_setting} table does not hold. */
    static final String MISSING_ROW = "no_such_ceiling";

    private static Plan scenarioA() throws Exception {
        return PlanCompiler.compile(Files.readAllBytes(ScenarioA.yaml()), ScenarioA.YAML, Runs.PACK);
    }

    private static BindOptions live(Env env, HeadlessDesk desk, Path dir) throws Exception {
        return BindOptions.defaults().withMode(BindMode.LIVE).withEnvId(env.id).withBindingsRoot(dir.resolve("root"))
                .withCredentials(desk.credentials(dir.resolve("credentials-" + System.nanoTime())))
                .withLedger(new CostLedger());
    }

    /** The lending pack with its maximum-return setting naming {@link #MISSING_ROW} (an overlay's way). */
    static DomainPack missingCeiling() {
        return new DelegatingDomainPack(Runs.PACK, "missing-ceiling", "0.1") {
            @Override
            public PackSettings settings() {
                return base().settings().with(Map.of(LendingPack.MAX_RETURN_SETTING, MISSING_ROW));
            }
        };
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void scenarioARequiresTheCeilingAndPassesWhereItIsConfigured(Env env, @TempDir Path dir) throws Exception {
        Plan plan = scenarioA();
        assertTrue(plan.requires().contains(LendingPack.CAP_RETURNS_CEILING), String.valueOf(plan.requires()));
        try (HeadlessDesk desk = HeadlessDesk.launch(env, false, true)) {
            Runs.Outcome outcome = Runs.run(desk, plan, dir.resolve("live"), live(env, desk, dir));
            assertEquals(Status.PASS, outcome.result().status(), outcome.describe());
            Map<String, Object> record = Runs.capabilitiesRecord(outcome.dir());
            assertEquals(List.of(Map.of("id", LendingPack.CAP_RETURNS_CEILING, "state", "OK", "scope", "scenario",
                    "cached", false)), stripNulls(record.get("pack")), String.valueOf(record));
            CapabilityMatrix archived = CapabilityMatrix.fromJson(Json.parseObject(Files.readString(outcome.dir()
                    .resolve("capabilities.json"))));
            assertEquals(CapabilityState.OK, archived.get(LendingPack.CAP_RETURNS_CEILING).state());
            assertTrue(desk.calls.stream().noneMatch(c -> c.startsWith("data.")), "the read went over JDBC: "
                    + desk.calls);
        }
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void aMissingSettingRowIsAPreconditionFailureBeforeAnythingIsSent(Env env, @TempDir Path dir) throws Exception {
        try (HeadlessDesk desk = HeadlessDesk.launch(env, false, true)) {
            Runs.Outcome outcome = Runs.run(missingCeiling(), desk, scenarioA(), dir.resolve("missing"),
                    live(env, desk, dir));
            assertEquals(Status.ERROR, outcome.result().status(), outcome.describe());
            assertEquals(FailureKind.PRECONDITION_FAILED, outcome.result().failureKind(), outcome.describe());
            assertEquals("unmet requirements: " + LendingPack.CAP_RETURNS_CEILING + ": UNAVAILABLE (no system maximum"
                    + " return configured (desk_setting " + MISSING_ROW + "))", outcome.result().message());
            assertEquals(List.of("run-start", "pack", "hooks", "capabilities", "requires", "ledger", "run-end"),
                    Runs.runLog(outcome.dir()).stream().map(r -> String.valueOf(r.get("type"))).toList(),
                    "no preflight, bind, cleanup or recover record (the pack record notes the overlay's version)");
            assertEquals(List.of(), desk.calls, "nothing was sent to the desk: the read went over JDBC");
            assertEquals("SIGNED_OFF", desk.status().state().name());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stripNulls(Object entries) {
        return ((List<Map<String, Object>>) entries).stream().map(e -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(e);
            copy.values().removeIf(v -> v == null);
            return copy;
        }).toList();
    }
}
