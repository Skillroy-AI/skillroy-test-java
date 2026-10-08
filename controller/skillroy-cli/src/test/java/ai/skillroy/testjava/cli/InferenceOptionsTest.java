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

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.LlmUsage;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

/**
 * phase5-contracts.md 4.4, 7.1, 7.2, 13.4, 15.1 items 15 and 16: the inference options of {@code run} and
 * {@code resolve}: the model key not set for {@code --inference claude} (exit 64 and the {@code environment}
 * testcase, the message and never a value), {@code replay} not using them, the settings errors, {@code inference.yaml}
 * beside the credentials (or in the bindings root's environment directory) with the options over it, the
 * {@code model cost} lines and {@code --json}'s {@code modelCost}, {@code budget} and {@code inference}; the approval
 * screen's lines of a model-written value (masked; the reused value's "recorded, verified now; no model call"); and the
 * cost arithmetic.
 */
class InferenceOptionsTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenario;
    private Path recordings;

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenario = Scenarios.write(tmp.resolve("module/scenarios"), "with-oracle.yaml", BindingCommandsTest.ORACLE);
        recordings = Files.createDirectories(tmp.resolve("recordings"));
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(String command, String... args) {
        List<String> all = new ArrayList<>(List.of(command));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    @Test
    void theModelKeyNotSetIsExitCode64BeforeAnythingRunsAndReplayDoesNotUseTheOptions() throws Exception {
        CliHarness.Outcome resolve = cli("resolve", scenario.toString(), "--dry-run", "--inference", "claude")
                .expectExit(Exit.USAGE);
        assertTrue(resolve.err().contains("skillroy resolve: the model key is not set (ANTHROPIC_API_KEY)"),
                resolve.err());
        Path junit = tmp.resolve("junit.xml");
        CliHarness.Outcome run = cli("run", scenario.toString(), "--mode", "live", "--inference", "claude",
                "--junit", junit.toString()).expectExit(Exit.USAGE);
        assertTrue(run.err().contains("the model key is not set (ANTHROPIC_API_KEY)"), run.err());
        String xml = Files.readString(junit);
        assertTrue(xml.contains("<testcase name=\"environment\"") && xml.contains("the model key is not set"), xml);
        assertTrue(agent.requests.isEmpty(), "found before the agent is contacted");

        CliHarness.Outcome replay = cli("run", scenario.toString(), "--mode", "live", "--write-bindings")
                .expectExit(Exit.OK);
        assertTrue(replay.out().contains("model calls: 0"), replay.out());
        CliHarness.Outcome ignored = cli("run", scenario.toString(), "--inference", "claude").expectExit(Exit.OK);
        assertTrue(ignored.err().contains("note: replay makes no model call; the inference options are not used"),
                ignored.err());
    }

    @Test
    void settingsErrorsAreUsageErrorsNamingTheOptionNotTheValue() throws Exception {
        assertTrue(cli("run", scenario.toString(), "--mode", "live", "--inference", "bogus-gateway")
                .expectExit(Exit.USAGE).err().contains("skillroy.inference must be claude, recorded or none"));
        assertTrue(cli("resolve", scenario.toString(), "--dry-run", "--inference", "recorded").expectExit(Exit.USAGE)
                .err().contains("the recorded gateway needs a recordings directory"));
        CliHarness.Outcome budget = cli("run", scenario.toString(), "--mode", "live", "--budget-scenario",
                "lots-of-money").expectExit(Exit.USAGE);
        assertFalse(budget.err().contains("lots-of-money"), "never the value: " + budget.err());
        assertTrue(cli("run", scenario.toString(), "--mode", "live", "--inference-file",
                tmp.resolve("missing.yaml").toString()).expectExit(Exit.USAGE).err().contains("does not exist"));
        assertTrue(agent.requests.isEmpty());
    }

    @Test
    void theCostLinesAndTheJsonCarryTheBudgetAndTheInferenceSummary() throws Exception {
        String[] live = {scenario.toString(), "--mode", "live", "--inference", "recorded", "--recordings",
                recordings.toString(), "--budget-scenario", "0.25", "--log-prompts", "--workspace-id", "ws-1"};
        CliHarness.Outcome run = cli("run", live).expectExit(Exit.OK);
        assertTrue(run.out().endsWith("model calls: 0\nmodel cost: $0 (budget $0.25)\n"), run.out());
        List<String> json = new ArrayList<>(List.of(live));
        json.add("--json");
        CliHarness.Outcome runJson = cli("run", json.toArray(String[]::new)).expectExit(Exit.OK);
        Map<String, Object> document = Json.parseObject(runJson.out());
        assertEquals(0L, document.get("modelCalls"));
        assertEquals("0", document.get("modelCost"));
        assertEquals("0.25", document.get("budget"));
        Map<?, ?> summary = (Map<?, ?>) document.get("inference");
        assertEquals("recorded", summary.get("gateway"));
        assertEquals(0L, summary.get("calls"));
        assertEquals(List.of(), ((Map<?, ?>) ((List<?>) document.get("results")).get(0)).get("inference"));
        assertTrue(runJson.err().endsWith("model calls: 0\nmodel cost: $0 (budget $0.25)\n"), runJson.err());

        CliHarness.Outcome resolve = cli("resolve", scenario.toString(), "--dry-run", "--inference", "recorded",
                "--recordings", recordings.toString(), "--no-reuse-bindings").expectExit(Exit.OK);
        assertTrue(resolve.out().endsWith("model calls: 0\nmodel cost: $0 (budget $0.50)\n"), resolve.out());
        CliHarness.Outcome resolveJson = cli("resolve", scenario.toString(), "--dry-run", "--json")
                .expectExit(Exit.OK);
        // A dry run prints the file it would write before the JSON document (as since Phase 2).
        Map<String, Object> resolved = Json.parseObject(resolveJson.out().substring(resolveJson.out()
                .indexOf("{\n  \"results\"")));
        assertEquals(0L, resolved.get("modelCalls"));
        assertEquals("0.50", resolved.get("budget"));
        assertEquals("none", ((Map<?, ?>) resolved.get("inference")).get("gateway"), "no key: no gateway");
        assertEquals(0L, ((Map<?, ?>) ((List<?>) resolved.get("results")).get(0)).get("modelCalls"));
        assertTrue(resolveJson.err().contains("model cost: $0 (budget $0.50)"), resolveJson.err());
    }

    @Test
    void inferenceYamlIsReadBesideTheCredentialsAndTheOptionsWinOverIt() throws Exception {
        Path envDir = Files.createDirectories(tmp.resolve("module/bindings/default"));
        Files.createDirectories(envDir.resolve("rec"));
        Files.writeString(envDir.resolve("inference.yaml"), "version: 1\ngateway: recorded\nrecordings: rec/\n"
                + "budget: {scenarioUsd: \"0.33\"}\n");
        assertTrue(cli("run", scenario.toString(), "--mode", "live").expectExit(Exit.OK).out()
                .endsWith("(budget $0.33)\n"), "the file in the bindings root's environment directory");
        assertTrue(cli("run", scenario.toString(), "--mode", "live", "--budget-scenario", "0.10")
                .expectExit(Exit.OK).out().endsWith("(budget $0.10)\n"), "an option over the file");
        Path other = Files.writeString(tmp.resolve("other.yaml"), "version: 1\ngateway: none\n"
                + "budget: {scenarioUsd: \"0.07\"}\n");
        assertTrue(cli("run", scenario.toString(), "--mode", "live", "--inference-file", other.toString())
                .expectExit(Exit.OK).out().endsWith("(budget $0.07)\n"), "--inference-file names another");
        Files.writeString(envDir.resolve("inference.yaml"), "version: 1\ngateway: recorded\nnot-a-key: 1\n");
        CliHarness.Outcome strict = cli("run", scenario.toString(), "--mode", "live").expectExit(Exit.USAGE);
        assertTrue(strict.err().contains("inference.yaml"), "the strict reader names the file: " + strict.err());
    }

    /** A model-written value as the engine hands it to the approver: its usage, rationale and the guard's notes. */
    static Resolution modelWritten(String secret, List<String> notes) {
        Provenance provenance = new Provenance("inference.sql.Staff", List.of(new Provenance.Statement("jdbc",
                "desk", "SELECT s.code, s.name FROM staff s WHERE s.role = ? ORDER BY s.code LIMIT 200",
                List.of("SUPERVISOR"), 1, false, List.of(List.of("200", secret)))), List.of(), notes, 1, false,
                List.of(), "staff with the return permission; not the one whose word is " + secret);
        return new Resolution("supervisor", "Staff", Map.of("role", "SUPERVISOR"), "sha256:a", Map.of("code", "200"),
                Strategy.LLM_SQL, provenance, List.of(new VerifierResult("staff.active", true, "active", Map.of())),
                0.9, null, new Approval(Approval.State.REQUIRED, null), new LlmUsage("claude-sonnet-5-5", "req-42",
                1200, 80, "0.0032"));
    }

    @Test
    void theApprovalScreenShowsAModelWrittenValueMasked() {
        String secret = "Hidden-Word-8812";
        List<String> notes = List.of("inference.sql.Staff: guard injected: ORDER BY + s.code (key)",
                "inference.sql.Staff: guard injected: row limit injected: LIMIT 200",
                "inference.sql.Staff: params bind: role",
                "inference.sql.Staff: rows stated: 1..3, found 1");
        Resolution tierA = new Resolution("item", "Item", Map.of(), "sha256:i", Map.of("code", "B2"),
                Strategy.CANONICAL_SQL, new Provenance("toy.sql.Item", List.of(), List.of(), List.of(), 1, false,
                List.of()), List.of(), 1.0, null, new Approval(Approval.State.NOT_REQUIRED, null), null);
        String screen = screen(new Approver.ApprovalRequest("s", "e", null, List.of(modelWritten(secret, notes),
                tierA), List.of(), null), SecretMask.of(List.of(secret)));
        assertTrue(screen.contains("  slot supervisor = {\"code\":\"200\"}  (inference.sql.Staff, LLM_SQL)\n"), screen);
        assertTrue(screen.contains("    statement [jdbc desk] SELECT s.code, s.name FROM staff s WHERE s.role = ?"
                + " ORDER BY s.code LIMIT 200\n      params [\"SUPERVISOR\"], rows 1\n"), screen);
        assertTrue(screen.contains("    verifier staff.active: passed: active\n"
                + "    strategy llm-sql, model claude-sonnet-5-5, request req-42, cost $0.0032\n"
                + "    rationale: staff with the return permission; not the one whose word is <redacted>\n"
                + "      [injected] ORDER BY + s.code (key)\n"
                + "      [injected] row limit injected: LIMIT 200\n"
                + "    the parameters bind the arguments role, in order\n"
                + "    confidence 0.9; rows stated 1..3, found 1\n"), screen);
        assertFalse(screen.contains(secret), "masked as everything printed: " + screen);
        assertEquals(1, screen.split("strategy llm-sql", -1).length - 1, "a tier (a) value has no such lines");

        String reused = screen(new Approver.ApprovalRequest("s", "e", null, List.of(modelWritten(secret, List.of(
                "reused from the bindings file of approve: verified now, no model call"))), List.of(), null),
                SecretMask.of(List.of(secret)));
        assertTrue(reused.contains("    strategy llm-sql: recorded, verified now; no model call (reused from the"
                + " bindings file of approve: verified now, no model call)\n"), reused);
        assertFalse(reused.contains("request req-42"), "no model call to show: " + reused);
    }

    private static String screen(Approver.ApprovalRequest request, SecretMask mask) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BindArgs.print(new PrintStream(bytes, true, StandardCharsets.UTF_8), request, mask);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theCostIsSummedAndSummarisedFromTheLedgerEntries() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stop", "END_TURN");
        details.put("fingerprint", "sha256:f");
        List<CostLedger.Entry> entries = List.of(
                new CostLedger.Entry("s", "slot", Strategy.LLM_SQL, "claude", "claude-sonnet-5-5", "r1", 100, 10,
                        "0.0032", "resolve", 0, 0, false, null, details),
                new CostLedger.Entry("s", null, null, "recorded", "claude-haiku-4-5", "r2", 10, 1, "0.0100",
                        "triage", 0, 0, false, null),
                new CostLedger.Entry("s", null, null, "claude", "claude-sonnet-5-5", "r3", 1, 1, "not a number"));
        assertEquals("model cost: $0.0132 (budget $0.50)", InferenceArgs.costLine(entries, "0.50"));
        assertEquals("model cost: $0 (no budget)", InferenceArgs.costLine(List.of(), null));
        Map<String, Object> summary = InferenceArgs.summary(InferenceSettings.none(), entries);
        assertEquals("none", summary.get("gateway"));
        assertEquals(3L, summary.get("calls"));
        assertEquals("0.0132", summary.get("costUsd"));
        assertEquals(List.of("claude", "recorded"), summary.get("providers"));
        assertEquals(List.of("claude-sonnet-5-5", "claude-haiku-4-5"), summary.get("models"));
        assertEquals(List.of("resolve", "triage"), summary.get("purposes"));
        Map<?, ?> record = (Map<?, ?>) InferenceArgs.records(entries).get(0);
        assertEquals("END_TURN", record.get("stop"));
        assertEquals("sha256:f", record.get("fingerprint"));
        assertEquals("0.0032", record.get("costUsd"));
    }
}
