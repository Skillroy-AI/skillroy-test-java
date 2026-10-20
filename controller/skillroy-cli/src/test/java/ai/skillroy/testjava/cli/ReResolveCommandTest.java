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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 8.2, 13.4, 15.1 item 15: {@code skillroy re-resolve} through {@link Main#run} on the fake agent,
 * with the recorded gateway (the scenarios' oracles need no model call, so the job's plumbing is what is under test:
 * the batch rounds are the inference module's): the bindings files written, an identical file left untouched, the dry
 * run, the report files and the job's ledger, the cost lines, {@code --json}, a scenario whose requirements the target
 * does not meet reported {@code FAILED} (exit 1) while the others are written, and the usage errors (the job budget,
 * the gateway, the environment and the bindings root are required; exit 64 before the agent is contacted).
 */
class ReResolveCommandTest {

    static final String FANCY = """
            scenario: fancy-oracle
            pack: clitest
            requires: [test.fancy]
            oracle:
              same: { fn: Echo, args: IDLE }
            steps:
              - poke: {}
            """;

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenarios;
    private Path recordings;

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenarios = tmp.resolve("module/scenarios");
        Scenarios.write(scenarios, "with-oracle.yaml", BindingCommandsTest.ORACLE);
        Scenarios.write(scenarios, "pass.yaml", Scenarios.PASS);
        recordings = Files.createDirectories(tmp.resolve("recordings"));
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(String... args) {
        List<String> all = new ArrayList<>(List.of("re-resolve"));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    private String[] job(Path out, String env, String... more) {
        List<String> args = new ArrayList<>(List.of(scenarios.toString(), "--env", env, "--bindings",
                tmp.resolve("module/bindings").toString(), "--budget-job", "1.00", "--inference", "recorded",
                "--recordings", recordings.toString(), "--out", out.toString()));
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }

    private Path bindings(String env, String id) {
        return tmp.resolve("module/bindings").resolve(env).resolve(id + ".bindings.json");
    }

    @Test
    void theJobWritesTheFilesAReportAndItsLedgerAndLeavesAnIdenticalFileUntouched() throws Exception {
        Path out = tmp.resolve("job-1");
        CliHarness.Outcome first = cli(job(out, "nightly", "--approved-by", "re-resolve:nightly"))
                .expectExit(Exit.OK);
        String text = first.out();
        assertTrue(text.startsWith("re-resolve job "), text);
        assertTrue(text.contains(" on nightly: 2 scenarios: 0 unchanged, 0 changed, 1 new, 0 failed, 1 skipped"),
                text);
        assertTrue(text.contains("NEW       with-oracle (written)"), text);
        assertTrue(text.contains("SKIPPED   poke-twice"), "a scenario without bindings is listed: " + text);
        assertTrue(text.contains("model calls: 0\nmodel cost: $0 (budget $1.00)\n"), text);
        assertTrue(text.contains("report: " + out.resolve("re-resolve-report.md")), text);
        assertTrue(Files.isRegularFile(bindings("nightly", "with-oracle")));
        for (String file : List.of("re-resolve-report.md", "re-resolve-report.json", "cost-ledger.jsonl",
                "inference.jsonl")) {
            assertTrue(Files.isRegularFile(out.resolve(file)), file);
        }
        assertTrue(Files.readString(out.resolve("re-resolve-report.md")).contains("Approved by `re-resolve:nightly`"));
        byte[] before = Files.readAllBytes(bindings("nightly", "with-oracle"));

        CliHarness.Outcome second = cli(job(tmp.resolve("job-2"), "nightly", "--approved-by", "re-resolve:nightly",
                "--json")).expectExit(Exit.OK);
        Map<String, Object> report = Json.parseObject(second.out());
        Map<?, ?> scenario = ((List<?>) report.get("scenarios")).stream().map(s -> (Map<?, ?>) s)
                .filter(s -> "with-oracle".equals(s.get("scenarioId"))).findFirst().orElseThrow();
        assertEquals("UNCHANGED", scenario.get("status"), "the same label, the same file: " + second.out());
        assertEquals(false, scenario.get("written"));
        assertEquals("0", report.get("modelCost"));
        assertEquals("1.00", report.get("budget"));
        assertEquals(0L, report.get("modelCalls"));
        assertEquals("recorded", ((Map<?, ?>) report.get("inference")).get("provider"));
        assertEquals(tmp.resolve("job-2").toString(), report.get("reportDir"));
        assertEquals(0L, report.get("exitCode"));
        assertTrue(second.err().contains("model cost: $0 (budget $1.00)"), second.err());
        assertArrayEquals(before, Files.readAllBytes(bindings("nightly", "with-oracle")), "left untouched");

        CliHarness.Outcome dry = cli(job(tmp.resolve("job-3"), "fresh", "--dry-run")).expectExit(Exit.OK);
        assertTrue(dry.out().contains("(dry run: nothing written)"), dry.out());
        assertTrue(dry.out().contains("NEW       with-oracle"), dry.out());
        assertFalse(Files.exists(bindings("fresh", "with-oracle")), "a dry run writes no bindings file");
        assertTrue(Files.isRegularFile(tmp.resolve("job-3/re-resolve-report.md")), "but its report");
    }

    @Test
    void aScenarioWhoseRequirementsAreNotMetIsReportedFailedAndTheOthersAreStillWritten() throws Exception {
        Scenarios.write(scenarios, "fancy.yaml", FANCY);
        Path out = tmp.resolve("job");
        CliHarness.Outcome outcome = cli(job(out, "nightly")).expectExit(Exit.FAILED);
        assertTrue(outcome.out().contains("FAILED    fancy-oracle: PRECONDITION_FAILED REQUIRES: unmet requirements:"
                + " test.fancy"), outcome.out());
        assertTrue(outcome.out().contains("NEW       with-oracle (written)"), outcome.out());
        assertFalse(Files.exists(bindings("nightly", "fancy-oracle")));
        assertTrue(Files.isRegularFile(bindings("nightly", "with-oracle")));
        String md = Files.readString(out.resolve("re-resolve-report.md"));
        assertTrue(md.contains("## Failed") && md.contains("`fancy-oracle`: PRECONDITION_FAILED"), md);
        assertTrue(md.contains("not bound: the target does not meet"), md);
        assertEquals(1L, Json.parseObject(Files.readString(out.resolve("re-resolve-report.json"))).get("exitCode"));
    }

    /**
     * phase8-contracts.md 9.1.5 (the fix D622), 13.1 row 48: {@code requires} is checked against the agent's matrix
     * with the contributors' and the pack's capabilities merged; a pack id that is {@code UNAVAILABLE} fails the
     * scenario {@code REQUIRES} naming the reason; a pack's declaration error ends the job FAILED.
     */
    @Test
    void requiresIsCheckedAgainstTheMergedMatrix() throws Exception {
        CliTestPack.reset();
        try {
            Scenarios.write(scenarios, "gateway.yaml", FANCY.replace("scenario: fancy-oracle", "scenario: needs-gateway")
                    .replace("requires: [test.fancy]", "requires: [inference.gateway]"));
            Scenarios.write(scenarios, "env.yaml", FANCY.replace("scenario: fancy-oracle", "scenario: needs-env")
                    .replace("requires: [test.fancy]", "requires: [" + CliTestPack.ENV_READY + "]"));
            CliTestPack.envUnavailable = "the test backend is down";
            Path out = tmp.resolve("merged");
            CliHarness.Outcome outcome = cli(job(out, "nightly")).expectExit(Exit.FAILED);
            assertTrue(outcome.out().contains("NEW       needs-gateway (written)"),
                    "a contributed id is no longer missing: " + outcome.out());
            assertTrue(outcome.out().contains("FAILED    needs-env: PRECONDITION_FAILED REQUIRES: unmet requirements: "
                    + CliTestPack.ENV_READY + ": UNAVAILABLE (the test backend is down)"), outcome.out());
            assertEquals(1, CliTestPack.ENV_EVALUATIONS.get(), "evaluated once for the job");

            CliTestPack.declareTwice = true;
            CliHarness.Outcome refused = cli(job(tmp.resolve("refused"), "nightly")).expectExit(Exit.FAILED);
            assertTrue(refused.err().contains("the pack's capabilities cannot be merged: pack clitest declares the"
                    + " capability '" + CliTestPack.ENV_READY + "' twice"), refused.err());
        } finally {
            CliTestPack.reset();
        }
    }

    @Test
    void theBudgetTheGatewayTheEnvironmentAndTheRootAreRequired() throws Exception {
        String bindingsRoot = tmp.resolve("module/bindings").toString();
        String scenario = scenarios.toString();
        CliHarness.Outcome noBudget = cli(scenario, "--env", "e", "--bindings", bindingsRoot, "--inference",
                "recorded", "--recordings", recordings.toString()).expectExit(Exit.USAGE);
        assertTrue(noBudget.err().contains("re-resolve needs a job budget: --budget-job"), noBudget.err());
        CliHarness.Outcome none = cli(scenario, "--env", "e", "--bindings", bindingsRoot, "--budget-job", "1.00",
                "--inference", "none").expectExit(Exit.USAGE);
        assertTrue(none.err().contains("re-resolve needs a model gateway"), none.err());
        CliHarness.Outcome noKey = cli(scenario, "--env", "e", "--bindings", bindingsRoot, "--budget-job", "1.00",
                "--inference", "claude").expectExit(Exit.USAGE);
        assertTrue(noKey.err().contains("the model key is not set (ANTHROPIC_API_KEY)"), noKey.err());
        assertTrue(cli(scenario, "--bindings", bindingsRoot, "--budget-job", "1.00").expectExit(Exit.USAGE).err()
                .contains("re-resolve needs --env"));
        assertTrue(cli(scenario, "--env", "e", "--budget-job", "1.00").expectExit(Exit.USAGE).err()
                .contains("re-resolve needs --bindings"));
        cli(scenario, "--env", "e", "--bindings", bindingsRoot, "--budget-job", "lots", "--inference", "recorded",
                "--recordings", recordings.toString()).expectExit(Exit.USAGE);
        cli("--env", "e", "--bindings", bindingsRoot, "--budget-job", "1.00").expectExit(Exit.USAGE);
        Path broken = Scenarios.write(tmp.resolve("broken"), "broken.yaml", Scenarios.BROKEN);
        cli(broken.toString(), "--env", "e", "--bindings", bindingsRoot, "--budget-job", "1.00", "--inference",
                "recorded", "--recordings", recordings.toString()).expectExit(Exit.SCENARIO);
        assertTrue(agent.requests.isEmpty(), "nothing reached the agent");
    }
}
