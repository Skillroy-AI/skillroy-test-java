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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 48, the CLI's EP half (9.1.5, 9.3.3): {@code run} and {@code resolve} own one suite
 * cache per invocation (the {@code clitest} pack's {@code SUITE} check runs once for two scenarios, the second
 * scenario's {@code preflight} record {@code cached: true}); {@code caps --pack} shows the pack's capability with the
 * source {@code pack:clitest} (table and {@code --json}); {@code caps} without {@code --pack} shows no pack row. (The
 * {@code author} and {@code re-resolve} cases are in their commands' tests.)
 */
class Phase8CommandsTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenarios;

    @BeforeEach
    void setUp() throws IOException {
        CliTestPack.reset();
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenarios = Files.createDirectories(tmp.resolve("module/scenarios"));
        // The fake agent's counter is shared by the runs of one invocation: each expects at least two pokes.
        String pass = Scenarios.PASS.replace("\"counter.value\": 2", "\"counter.value\": { gte: 2 }");
        Scenarios.write(scenarios, "pass.yaml", pass);
        Scenarios.write(scenarios, "pass-again.yaml", pass.replace("scenario: poke-twice", "scenario: poke-again"));
    }

    @AfterEach
    void tearDown() {
        agent.close();
        CliTestPack.reset();
    }

    private CliHarness.Outcome cli(String command, String... args) {
        List<String> all = new ArrayList<>(List.of(command));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    private static List<Map<String, Object>> records(Path runDir, String type) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(runDir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if (type.equals(record.get("type"))) {
                out.add(record);
            }
        }
        return out;
    }

    @Test
    void oneRunInvocationIsOneSuite() throws Exception {
        Path out = tmp.resolve("out");
        CliHarness.Outcome run = cli("run", scenarios.toString(), "--mode", "live", "--out", out.toString())
                .expectExit(Exit.OK);
        assertTrue(run.out().contains("PASS      poke-again") && run.out().contains("PASS      poke-twice"),
                run.out());
        assertEquals(1, CliTestPack.BACKEND_RUNS.get(), "two scenarios in one run: one evaluation");
        Map<String, Object> first = records(out.resolve("poke-again"), "preflight").get(0);
        Map<String, Object> second = records(out.resolve("poke-twice"), "preflight").get(0);
        assertEquals(CliTestPack.BACKEND, first.get("id"));
        assertEquals("suite", first.get("scope"));
        assertEquals(false, first.get("cached"));
        assertEquals(true, second.get("cached"), "the second scenario's answer came from the invocation's cache");
        assertEquals(2, CliTestPack.ENV_EVALUATIONS.get(), "a SCENARIO capability is evaluated in every run");
        Map<?, ?> pack = (Map<?, ?>) ((List<?>) records(out.resolve("poke-twice"), "capabilities").get(0).get("pack"))
                .get(0);
        assertEquals(CliTestPack.ENV_READY, pack.get("id"));
        assertEquals("OK", pack.get("state"));

        cli("run", scenarios.resolve("pass.yaml").toString(), "--mode", "live").expectExit(Exit.OK);
        assertEquals(2, CliTestPack.BACKEND_RUNS.get(), "another invocation is another suite");
    }

    @Test
    void oneResolveInvocationIsOneSuite() throws Exception {
        Path oracles = Files.createDirectories(tmp.resolve("oracles/scenarios"));
        Scenarios.write(oracles, "with-oracle.yaml", BindingCommandsTest.ORACLE);
        Scenarios.write(oracles, "with-oracle-2.yaml", BindingCommandsTest.ORACLE.replace("scenario: with-oracle",
                "scenario: with-oracle-two"));
        CliHarness.Outcome dry = cli("resolve", oracles.toString(), "--dry-run").expectExit(Exit.OK);
        assertTrue(dry.out().contains("with-oracle-two"), dry.out());
        assertEquals(1, CliTestPack.BACKEND_RUNS.get(), "two scenarios resolved in one invocation: one evaluation");
    }

    @Test
    void capsWithPackShowsThePacksCapabilityAndItsSource() throws Exception {
        Path noJar = Files.createDirectories(tmp.resolve("no-more-packs"));
        String table = cli("caps", "--pack", noJar.toString()).expectExit(Exit.OK).out();
        String row = table.lines().filter(l -> l.startsWith(CliTestPack.ENV_READY)).findFirst().orElseThrow(
                () -> new AssertionError(table));
        assertTrue(row.contains("OK") && row.endsWith("pack:clitest"), row);
        assertEquals(1, CliTestPack.ENV_EVALUATIONS.get());
        Map<String, Object> json = Json.parseObject(cli("caps", "--pack", noJar.toString(), "--json")
                .expectExit(Exit.OK).out());
        Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) json.get("capabilities")).get(CliTestPack.ENV_READY);
        assertEquals("OK", entry.get("state"));
        assertEquals("pack:clitest", entry.get("source"));

        CliTestPack.envUnavailable = "the test backend is down";
        String down = cli("caps", "--pack", noJar.toString()).expectExit(Exit.OK).out();
        assertTrue(down.lines().anyMatch(l -> l.startsWith(CliTestPack.ENV_READY) && l.contains("UNAVAILABLE")
                && l.contains("the test backend is down")), down);

        CliTestPack.declareTwice = true;
        CliHarness.Outcome refused = cli("caps", "--pack", noJar.toString()).expectExit(Exit.FAILED);
        assertTrue(refused.err().contains("the pack's capabilities cannot be merged: pack clitest declares the"
                + " capability '" + CliTestPack.ENV_READY + "' twice"), refused.err());
    }

    @Test
    void capsWithoutPackShowsNoPackRow() throws Exception {
        String table = cli("caps").expectExit(Exit.OK).out();
        assertFalse(table.contains(CliTestPack.ENV_READY), table);
        String merged = cli("caps", "--inference", "none").expectExit(Exit.OK).out();
        assertFalse(merged.contains("pack:"), "the controller's capabilities only: " + merged);
        assertEquals(0, CliTestPack.ENV_EVALUATIONS.get(), "nothing was evaluated");
    }
}
