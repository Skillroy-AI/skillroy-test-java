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

import ai.skillroy.testjava.junit.OutputScan;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.2 item 3 without a display: scenario C's committed plan replays green on the sample desk in
 * this JVM ({@link HeadlessDesk}) on both seeds, with the committed bindings, the generated credentials and the desk's
 * database read through the controller-side JDBC channel: no resolver ran, each verifier once, no model call, every
 * expectation and {@code db} check held at its first attempt, and the return is stored as
 * {@link ScenarioC#assertReplayed} reads it back; the password input was announced as secret (A34) and no file the
 * run wrote holds a staff password or the JDBC password. {@code ResolutionChecks} runs the same check against the
 * launched desk, in every load mode, in the Linux image.
 */
class ScenarioCReplayTest {

    @ParameterizedTest
    @EnumSource(Env.class)
    void theCommittedPlanReplaysGreenAndTheReturnIsStored(Env env, @TempDir Path dir) throws Exception {
        Plan plan = Plan.read(ScenarioC.plan());
        try (HeadlessDesk desk = HeadlessDesk.launch(env)) {
            CostLedger ledger = new CostLedger();
            Runs.Outcome outcome = Runs.run(desk, plan, dir.resolve("replay"), BindOptions.defaults()
                    .withEnvId(env.id).withBindingsRoot(ScenarioA.bindingsRoot())
                    .withCredentials(desk.credentials(dir.resolve("credentials"))).withLedger(ledger));
            Runs.assertPassedWithoutSleeping(outcome);
            ScenarioC.assertReplayed(outcome, env, "jdbc", desk);
            assertTrue(ledger.isEmpty(), String.valueOf(ledger.entries()));
            assertEquals("SIGNED_OFF", desk.status().state().name(), "the cleanup signed the assistant off");
            ResolutionChecks.assertPasswordInputsAnnounced(outcome.dir(), plan, List.of("signOn"));

            Set<String> passwords = Set.copyOf(SampleCredentials.staff(env).values());
            List<Path> files;
            try (Stream<Path> walk = Files.walk(outcome.dir())) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("run.jsonl")), "the run wrote");
            for (Path file : files) {
                assertEquals(List.of(), OutputScan.leaks(file, passwords), file + " holds a staff password");
                assertFalse(Files.readString(file).contains(HeadlessDesk.readerPassword()), file
                        + " holds the JDBC password");
            }
        }
    }
}
