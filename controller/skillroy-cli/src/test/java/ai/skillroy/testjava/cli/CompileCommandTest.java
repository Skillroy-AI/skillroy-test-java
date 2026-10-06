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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.scenario.PlanCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code skillroy compile}, including {@code --check} and pack selection. */
class CompileCommandTest {

    @TempDir
    Path tmp;

    private CliHarness.Outcome cli(String... args) {
        return CliHarness.run(tmp.resolve("unused-discovery"), args);
    }

    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void writesThePlanNextToTheYamlAndOnlyWhenItChanged() throws Exception {
        Path yaml = Scenarios.write(tmp.resolve("scenarios"), "poke-twice.yaml", Scenarios.PASS);
        Path plan = tmp.resolve("scenarios/poke-twice.plan.json");
        String out = cli("compile", yaml.toString()).expectExit(Exit.OK).out();
        assertTrue(out.startsWith("written    " + plan.toAbsolutePath()), out);
        assertTrue(out.contains("(poke-twice, pack clitest 1.0.0)"), out);
        assertEquals(PlanCompiler.compile(yaml, new CliTestPack()).toJsonText(), Files.readString(plan));
        long modified = Files.getLastModifiedTime(plan).toMillis();
        assertTrue(cli("compile", yaml.toString()).expectExit(Exit.OK).out().startsWith("unchanged"));
        assertEquals(modified, Files.getLastModifiedTime(plan).toMillis());
    }

    @Test
    void checkPassesForAFreshPlanAndFailsForAStaleOrMissingOneWithoutWriting() throws Exception {
        Path scenarios = tmp.resolve("scenarios");
        Path yaml = Scenarios.write(scenarios, "poke-twice.yaml", Scenarios.PASS);
        Scenarios.write(scenarios, "guarded.yaml", Scenarios.GUARDED);
        Path plans = tmp.resolve("plans");
        cli("compile", scenarios.toString(), "--out", plans.toString()).expectExit(Exit.OK);
        assertEquals(List.of("guarded.plan.json", "poke-twice.plan.json"), listing(plans));

        String upToDate = cli("compile", "--check", scenarios.toString(), "--out", plans.toString())
                .expectExit(Exit.OK).out();
        assertTrue(upToDate.contains("up-to-date " + plans.resolve("guarded.plan.json")), upToDate);

        String committed = Files.readString(plans.resolve("poke-twice.plan.json"));
        Files.writeString(yaml, Scenarios.PASS + "# an edit after the plan was committed\n");
        CliHarness.Outcome stale = cli("compile", "--check", scenarios.toString(), "--out", plans.toString())
                .expectExit(Exit.FAILED);
        assertTrue(stale.out().contains("stale      " + plans.resolve("poke-twice.plan.json")), stale.out());
        assertTrue(stale.err().contains("1 of 2 plans are missing or stale"), stale.err());
        assertEquals(committed, Files.readString(plans.resolve("poke-twice.plan.json")), "--check never writes");

        Files.delete(plans.resolve("guarded.plan.json"));
        CliHarness.Outcome missing = cli("compile", "--check", scenarios.toString(), "--out", plans.toString())
                .expectExit(Exit.FAILED);
        assertTrue(missing.out().contains("missing    " + plans.resolve("guarded.plan.json")), missing.out());
        assertEquals(List.of("poke-twice.plan.json"), listing(plans));

        cli("compile", scenarios.toString(), "--out", plans.toString()).expectExit(Exit.OK);
        cli("compile", "--check", scenarios.toString(), "--out", plans.toString()).expectExit(Exit.OK);
    }

    @Test
    void checkDetectsAPackChangeThroughTheBytesNotJustTheSourceHash() throws Exception {
        Path yaml = Scenarios.write(tmp, "poke-twice.yaml", Scenarios.PASS);
        cli("compile", yaml.toString()).expectExit(Exit.OK);
        Path plan = tmp.resolve("poke-twice.plan.json");
        Files.writeString(plan, Files.readString(plan).replace("\"packVersion\": \"1.0.0\"",
                "\"packVersion\": \"0.9.0\""));
        cli("compile", "--check", yaml.toString()).expectExit(Exit.FAILED);
    }

    @Test
    void compileErrorsNameFileAndLineAndTheOtherScenariosStillCompile() throws Exception {
        Path scenarios = tmp.resolve("scenarios");
        Path broken = Scenarios.write(scenarios, "broken.yaml", Scenarios.BROKEN);
        Scenarios.write(scenarios, "poke-twice.yaml", Scenarios.PASS);
        CliHarness.Outcome outcome = cli("compile", scenarios.toString()).expectExit(Exit.SCENARIO);
        assertTrue(outcome.err().contains(broken + ":5: "), outcome.err());
        assertTrue(outcome.err().contains("frobnicate"), outcome.err());
        assertTrue(outcome.err().contains("1 of 2 scenarios could not be compiled"), outcome.err());
        assertTrue(Files.isRegularFile(scenarios.resolve("poke-twice.plan.json")));
        assertFalse(Files.exists(scenarios.resolve("broken.plan.json")));
        assertEquals(Exit.SCENARIO, cli("compile", "--check", scenarios.toString()).exit(),
                "a compile error outranks a stale plan");
    }

    @Test
    void malformedYamlIsAScenarioError() throws Exception {
        Path yaml = Scenarios.write(tmp, "bad.yaml", "scenario: bad\nsteps: [\n");
        CliHarness.Outcome outcome = cli("compile", yaml.toString()).expectExit(Exit.SCENARIO);
        assertTrue(outcome.err().startsWith(yaml.toString()), outcome.err());
    }

    @Test
    void twoScenariosWithOneIdCannotShareAPlan() throws Exception {
        Path first = Scenarios.write(tmp.resolve("a"), "one.yaml", Scenarios.PASS);
        Path second = Scenarios.write(tmp.resolve("b"), "two.yaml", Scenarios.PASS);
        Path plans = tmp.resolve("plans");
        CliHarness.Outcome outcome = cli("compile", first.toString(), second.toString(), "--out", plans.toString())
                .expectExit(Exit.SCENARIO);
        assertTrue(outcome.err().contains("scenario id 'poke-twice' is also the id of " + first), outcome.err());
    }

    @Test
    void argumentsMustExistAndDirectoriesMustHoldScenarios() throws Exception {
        assertTrue(cli("compile", tmp.resolve("nope.yaml").toString()).expectExit(Exit.USAGE).err()
                .contains("no such file or directory"));
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        assertTrue(cli("compile", empty.toString()).expectExit(Exit.USAGE).err().contains("holds no *.yaml"));
        Path yaml = Scenarios.write(tmp, "poke-twice.yaml", Scenarios.PASS);
        Path file = Files.writeString(tmp.resolve("a-file"), "x");
        assertTrue(cli("compile", yaml.toString(), "--out", file.toString()).expectExit(Exit.USAGE).err()
                .contains("is not a directory"));
        assertTrue(cli("compile", yaml.toString(), "--pack", tmp.resolve("none.jar").toString())
                .expectExit(Exit.USAGE).err().contains("--pack"));
    }

    @Test
    void packsComeFromTheClassPathAndFromPackJars() throws Exception {
        Path other = Scenarios.write(tmp, "other.yaml", Scenarios.OTHER);
        CliHarness.Outcome missing = cli("compile", other.toString()).expectExit(Exit.SCENARIO);
        assertTrue(missing.err().contains("domain pack 'other' is not on the class path; packs found: [clitest]"),
                missing.err());

        Path jar = Scenarios.otherPackJar(tmp);
        String out = cli("compile", other.toString(), "--pack", jar.toString()).expectExit(Exit.OK).out();
        assertTrue(out.contains("(other-mark, pack other 2.0.0)"), out);

        Path noPack = Scenarios.write(tmp, "no-pack.yaml", Scenarios.NO_PACK);
        cli("compile", noPack.toString()).expectExit(Exit.OK);
        CliHarness.Outcome ambiguous = cli("compile", noPack.toString(), "--pack", jar.toString())
                .expectExit(Exit.SCENARIO);
        assertTrue(ambiguous.err().contains("several packs are on the class path: [clitest, other]"),
                ambiguous.err());
    }

    @Test
    void jsonReportsEveryFile() throws Exception {
        Path scenarios = tmp.resolve("scenarios");
        Scenarios.write(scenarios, "broken.yaml", Scenarios.BROKEN);
        Scenarios.write(scenarios, "poke-twice.yaml", Scenarios.PASS);
        CliHarness.Outcome outcome = cli("compile", "--json", scenarios.toString()).expectExit(Exit.SCENARIO);
        List<Object> plans = JsonView.of(Json.parseObject(outcome.out())).list("plans");
        assertEquals(2, plans.size());
        Map<?, ?> broken = (Map<?, ?>) plans.get(0);
        assertEquals("error", broken.get("status"));
        assertTrue(String.valueOf(broken.get("error")).contains("broken.yaml:5:"), broken.toString());
        Map<?, ?> pass = (Map<?, ?>) plans.get(1);
        assertEquals("written", pass.get("status"));
        assertEquals("poke-twice", pass.get("scenarioId"));
    }
}
