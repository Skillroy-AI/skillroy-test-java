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

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Rung;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Call-level rung ladders (phase8-contracts.md 7.6, gap 22; 13.1 row 32). */
class RungLadderTest {

    private static final List<Rung> LADDER = List.of(new Rung("PHYSICAL", "toy.scanner.physical"),
            Rung.of("SEMANTIC"));

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private Map<String, Object> scanned = Map.of("rung", "PHYSICAL", "fidelity", "FULL");

    @BeforeEach
    void toyApplication() {
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.scan", "toy.scanner.physical");
        agent.on("toy.scan", params -> scanned);
        agent.value("toy.state", Map.of("name", "LOAN"));
        agent.value("toy.loan", Map.of("lines", List.of()));
        pack.custom = List.of(new PlannedCall("toy.scan", Map.of("barcode", "4005"), Sync.NONE, 0)
                .withRungs(LADDER));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunResult run(String body) throws ScenarioException {
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind()).withOutputDir(dir);
        return new PlanExecutor(pack, agent, options).run(plan(body));
    }

    private List<Map<String, Object>> records(String type) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if (type.equals(record.get("type"))) {
                out.add(record);
            }
        }
        return out;
    }

    private static Map<String, Object> result(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    void theFirstRungPassesAtFullFidelityWithItsRungRecord() throws Exception {
        RunResult result = run("steps:\n  - custom: {}\n");
        assertEquals(Status.PASS, result.status(), result.message());
        Map<String, Object> rung = records("rung").get(0);
        assertEquals(List.of("PHYSICAL", "SEMANTIC"), rung.get("rungs"));
        assertEquals("PHYSICAL", rung.get("rung"));
        assertEquals("FULL", rung.get("fidelity"));
        assertEquals(Boolean.TRUE, rung.get("ok"));
        assertFalse(rung.containsKey("minRung"));
        assertTrue(records("fidelity").isEmpty());
    }

    @Test
    void aMissingOrUndeclaredRungFailsTheStep() throws Exception {
        scanned = result("accepted", true);
        RunResult missing = run("steps:\n  - custom: {}\n");
        assertEquals(Status.FAIL, missing.status());
        assertEquals(FailureKind.ASSERTION, missing.failureKind());
        assertEquals("custom: toy.scan reported no rung; its ladder is [PHYSICAL, SEMANTIC]", missing.message());
        assertEquals(Boolean.FALSE, records("rung").get(0).get("ok"), "the record is written before the failure");
        scanned = result("rung", "ROBOT");
        assertEquals("custom: toy.scan reported rung ROBOT, not one of [PHYSICAL, SEMANTIC]",
                run("steps:\n  - custom: {}\n").message());
    }

    @Test
    void aLowerRungIsOneDegradedRecordEvenWhenThePrimitiveSaysSoToo() throws Exception {
        scanned = result("rung", "SEMANTIC", "requestedRung", "PHYSICAL", "fidelity", "DEGRADED", "reason",
                "scanner driver not enabled");
        RunResult result = run("steps:\n  - custom: {}\n");
        assertEquals(Status.DEGRADED, result.status(), result.message());
        assertEquals(List.of("s1: custom fell back from PHYSICAL to SEMANTIC (scanner driver not enabled)"),
                result.degradedFidelity());
        assertEquals(1, records("fidelity").size(), "de-duplicated with the primitive's own DEGRADED");
        assertEquals("DEGRADED", records("rung").get(0).get("fidelity"));
        scanned = result("rung", "SEMANTIC");
        assertEquals(List.of("s1: custom fell back from PHYSICAL to SEMANTIC (a lower rung of the ladder)"),
                run("steps:\n  - custom: {}\n").degradedFidelity());
        // A primitive reporting DEGRADED on the first rung is recorded by the fidelity note, as before.
        scanned = result("rung", "PHYSICAL", "requestedRung", "PHYSICAL", "fidelity", "DEGRADED", "reason", "slow");
        RunResult first = run("steps:\n  - custom: {}\n");
        assertEquals(Status.DEGRADED, first.status());
        assertEquals(1, records("fidelity").size());
    }

    @Test
    void aRungBelowTheMinimumOrAMissingEchoFailsTheStep() throws Exception {
        scanned = result("rung", "SEMANTIC");
        RunResult below = run("steps:\n  - custom: {}\n    minRung: PHYSICAL\n");
        assertEquals(Status.FAIL, below.status());
        assertEquals("custom: toy.scan acted on rung SEMANTIC below the required PHYSICAL", below.message());
        assertEquals("PHYSICAL", records("rung").get(0).get("minRung"));

        pack.custom = List.of(new PlannedCall("toy.scan", Map.of("barcode", "4005", "minRung", "PHYSICAL"),
                Sync.NONE, 0).withRungs(LADDER));
        scanned = result("rung", "PHYSICAL");
        assertEquals("custom: toy.scan did not apply minRung PHYSICAL", run("steps:\n  - custom: {}\n").message(),
                "a forwarded minRung without the echo fails closed");
        scanned = result("rung", "PHYSICAL", "minRung", "PHYSICAL");
        assertEquals(Status.PASS, run("steps:\n  - custom: {}\n").status());
        // The device.act form: the forwarded minRung inside args.
        pack.custom = List.of(new PlannedCall("toy.scan", Map.of("id", "scanner", "args", Map.of("barcode", "4005",
                "minRung", "PHYSICAL")), Sync.NONE, 0).withRungs(LADDER));
        scanned = result("rung", "PHYSICAL");
        assertEquals("custom: toy.scan did not apply minRung PHYSICAL", run("steps:\n  - custom: {}\n").message());
    }

    @Test
    void theRunStartPreCheckRefusesAnUnreachableRungBeforeAnythingIsSent() throws Exception {
        agent.capability("toy.scanner.physical", CapabilityState.UNAVAILABLE, "scanner driver not enabled");
        RunResult result = run("steps:\n  - enter: { value: 1 }\n  - custom: {}\n    minRung: PHYSICAL\n");
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.PRECONDITION_FAILED, result.failureKind());
        assertEquals("s2", result.failedStepId());
        assertEquals("step s2 (custom) needs rung PHYSICAL or better: toy.scanner.physical is UNAVAILABLE (scanner"
                + " driver not enabled)", result.message());
        Map<String, Object> rungs = records("rungs").get(0);
        assertEquals("s2", rungs.get("step"));
        assertEquals(Boolean.FALSE, rungs.get("ok"));
        assertEquals(List.of(Map.of("name", "PHYSICAL", "capability", "toy.scanner.physical", "state", "UNAVAILABLE",
                "reason", "scanner driver not enabled")), rungs.get("states"));
        assertTrue(agent.callsTo("toy.input").isEmpty() && agent.callsTo("toy.scan").isEmpty(), "no step sent");
        assertTrue(records("bind").isEmpty(), "before bind");

        // A rung without a capability at or above the minimum is always reachable.
        scanned = result("rung", "SEMANTIC");
        RunResult reachable = run("steps:\n  - custom: {}\n    minRung: SEMANTIC\n");
        assertEquals(Status.DEGRADED, reachable.status(), reachable.message());
        assertEquals(Boolean.TRUE, records("rungs").get(0).get("ok"));
    }

    @Test
    void strictRequiresTheFirstRungAndTheStepsOwnWins() throws Exception {
        String[] seen = new String[1];
        pack.onCustom = ctx -> seen[0] = ctx.minRung();
        Plan strict = plan("ui: { strict: true }\nsteps:\n  - custom: {}\n");
        assertEquals("PHYSICAL", strict.steps().get(0).calls().get(0).minRung());
        assertEquals(null, seen[0], "the verb sees the step's own minRung, none here");
        Plan own = plan("ui: { strict: true }\nsteps:\n  - custom: {}\n    minRung: SEMANTIC\n");
        assertEquals("SEMANTIC", own.steps().get(0).calls().get(0).minRung());
        assertEquals("SEMANTIC", seen[0]);
        assertEquals(null, plan("steps:\n  - custom: {}\n").steps().get(0).calls().get(0).minRung());
    }

    @Test
    void aCleanupLadderIsLoggedOnly() throws Exception {
        agent.on("toy.scan", params -> params.isEmpty() ? result("rung", "ROBOT") : scanned);
        pack.onCustom = ctx -> ctx.addCleanup(new PlannedCall("toy.scan", Map.of(), Sync.NONE, 0).withRungs(LADDER));
        RunResult result = run("steps:\n  - custom: {}\n");
        assertEquals(Status.PASS, result.status(), "a cleanup call's rung never changes the verdict");
        List<Map<String, Object>> rung = records("rung");
        assertEquals(2, rung.size());
        assertEquals(Boolean.TRUE, rung.get(1).get("cleanup"));
        assertEquals(Boolean.FALSE, rung.get(1).get("ok"));
        assertTrue(records("rungs").isEmpty(), "cleanup calls are not pre-checked");
    }
}
