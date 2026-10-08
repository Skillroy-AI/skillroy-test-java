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

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.recorded.RecordingGateway;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 12, 13.4, 15.1 item 15: {@code skillroy session --script} end to end on the CLI tests' fake agent
 * (every command of 13.4, a mistyped one that the session survives, the journal, the ledger), its {@code export}, and a
 * {@code replay} of the export through {@code skillroy run} with zero model calls; {@code resolve --credential}, whose
 * secret is joined and never printed, declared in the export and joined again by its replay; {@code ask} through the
 * recorded gateway with an answer the test records first; a model-written resolution through a served gateway, its
 * records and its count on the session's budget; {@code --json}'s lines; the exit codes (a lost agent, a failed last
 * export) and the usage errors.
 */
class SessionCommandTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private Path tokenFile;

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private List<String> connection(FakeAgent to, Path token) {
        return List.of("--port", Integer.toString(to.port()), "--token-file", token.toString());
    }

    private CliHarness.Outcome cli(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(connection(agent, tokenFile));
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    private Path script(String text) throws Exception {
        return Files.writeString(tmp.resolve("commands-" + System.nanoTime() + ".session"), text);
    }

    @Test
    void aScriptedSessionExportsAScenarioThatReplaysWithZeroModelCalls() throws Exception {
        Path out = tmp.resolve("session-out");
        Path script = script("""
                # a scripted session on the fake desk
                do poke {}
                call test.counter
                check counter.value 1
                observe counter.value
                note the counter moved once
                snapshot 2000
                facts counter value
                events 0
                frobnicate now
                resolve item Item {}
                export poke-session --approved-by "a reviewer"
                quit
                do poke {}
                """);
        CliHarness.Outcome outcome = cli("session", "--script", script.toString(), "--out", out.toString(), "--id",
                "sess-cli").expectExit(Exit.OK);
        String stdout = outcome.out();
        assertTrue(stdout.contains("session sess-cli on pack clitest 1.0.0, environment default"), stdout);
        assertTrue(stdout.contains("> do poke {}\ns1 poke: PASS"), stdout);
        assertTrue(stdout.contains("s2 test.counter: PASS\n  {\n    \"value\": 1\n  }"), "a call prints its value: "
                + stdout);
        assertTrue(stdout.contains("counter.value 1: held"), stdout);
        assertTrue(stdout.contains("counter.value = 1  (journaled as observed)"), stdout);
        assertTrue(stdout.contains("- window \"Fake Desk\""), "the snapshot: " + stdout);
        assertTrue(stdout.contains("test.poked {\"count\":1}"), "the events: " + stdout);
        assertTrue(stdout.contains("frobnicate: unknown command 'frobnicate'; commands: ask, do, call, resolve,"
                + " check, observe, snapshot, facts, events, note, export, quit"), "said, and the session goes on: "
                + stdout);
        assertTrue(stdout.contains("resolve: ENVIRONMENT"), "a resolution the pack cannot serve is said: " + stdout);
        assertTrue(stdout.contains("Exported session sess-cli as poke-session"), stdout);
        assertTrue(stdout.contains("a read of test.counter is an observation, not a step"), "the raw read is left"
                + " out: " + stdout);
        assertTrue(stdout.contains("Written:\n  " + out.resolve("poke-session.yaml")), stdout);
        assertTrue(stdout.contains("session sess-cli ended: 2 steps, 0 turns\nmodel calls: 0\n"
                + "model cost: $0 (budget $1.00)\n"), stdout);
        assertEquals(1, agent.counter.get(), "nothing after quit ran");

        List<String> types = new ArrayList<>();
        for (String line : Files.readAllLines(out.resolve("journal.jsonl"))) {
            types.add(String.valueOf(Json.parseObject(line).get("type")));
        }
        assertEquals(List.of("session-start", "step", "step", "check", "observe", "note", "session-end"), types);
        assertEquals(List.of(), Files.readAllLines(out.resolve("cost-ledger.jsonl")), "no model call");
        assertFalse(Files.exists(out.resolve("inference.jsonl")), "no gateway, no record");
        String yaml = Files.readString(out.resolve("poke-session.yaml"));
        assertFalse(yaml.contains("test.counter"), "the read is not a step: " + yaml);
        assertTrue(yaml.contains("# observed in session sess-cli at step 2, not derived"), yaml);
        ai.skillroy.testjava.scenario.Plan plan = ai.skillroy.testjava.scenario.Plan.read(
                out.resolve("plan/poke-session.plan.json"));
        assertEquals(List.of("poke", "expect", "expect"), plan.steps().stream()
                .map(ai.skillroy.testjava.scenario.Plan.Step::verb).toList(), "the verb, the check that held, the"
                + " observed value");
        assertFalse(Files.exists(out.resolve("bindings")), "no slot, no bindings file");

        // The export replays on a fresh target with zero model calls (the default mode is replay).
        try (FakeAgent fresh = new FakeAgent()) {
            Path freshToken = fresh.writeTokenFile(tmp.resolve("fresh.token"));
            List<String> args = new ArrayList<>(List.of("run", out.resolve("poke-session.yaml").toString(),
                    "--bindings", out.resolve("bindings").toString()));
            args.addAll(connection(fresh, freshToken));
            CliHarness.Outcome replay = CliHarness.run(tmp.resolve("unused-discovery"), args.toArray(String[]::new))
                    .expectExit(Exit.OK);
            assertTrue(replay.out().contains("PASS      poke-session"), replay.out());
            assertTrue(replay.out().endsWith("model calls: 0\nmodel cost: $0 (budget $0.50)\n"), replay.out());
            List<String> replayAgain = new ArrayList<>(args);
            replayAgain.addAll(List.of("--mode", "replay", "--json"));
            String json = CliHarness.run(tmp.resolve("unused-discovery"), replayAgain.toArray(String[]::new))
                    .expectExit(Exit.FAILED).out();
            assertEquals(0L, Json.parseObject(json).get("modelCalls"), "the counter is 2 now: the expectation"
                    + " fails, and still no model call");
        }
    }

    @Test
    void askGoesThroughTheRecordedGatewayAndIsJournaledLedgeredAndPriced() throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("recordings"));
        record(recordings, "say hello", "hello there");
        Path out = tmp.resolve("asked");
        Path script = script("ask say hello\nnote after the turn\n");
        CliHarness.Outcome outcome = cli("session", "--script", script.toString(), "--out", out.toString(),
                "--inference", "recorded", "--recordings", recordings.toString(), "--budget-session", "0.25")
                .expectExit(Exit.OK);
        String stdout = outcome.out();
        assertTrue(stdout.contains("DONE: hello there"), stdout);
        assertTrue(stdout.contains("model calls: 1\nmodel cost: $0 (budget $0.25)\n"), stdout);
        List<String> ledger = Files.readAllLines(out.resolve("cost-ledger.jsonl"));
        assertEquals(1, ledger.size(), ledger.toString());
        assertEquals("recorded", Json.parseObject(ledger.get(0)).get("provider"));
        assertEquals("session", Json.parseObject(ledger.get(0)).get("purpose"));
        List<String> inference = Files.readAllLines(out.resolve("inference.jsonl"));
        assertEquals(1, inference.size(), inference.toString());
        assertEquals("inference", Json.parseObject(inference.get(0)).get("type"));

        CliHarness.Outcome noRecording = cli("session", "--script", script("ask something else\n").toString(),
                "--out", tmp.resolve("unrecorded").toString(), "--inference", "recorded", "--recordings",
                recordings.toString()).expectExit(Exit.OK);
        assertTrue(noRecording.out().contains("QUESTION: "), "a missing recording ends the turn for the person: "
                + noRecording.out());
        CliHarness.Outcome noGateway = cli("session", "--script", script("ask anything\n").toString(), "--out",
                tmp.resolve("none").toString(), "--inference", "none").expectExit(Exit.OK);
        assertTrue(noGateway.out().contains("ask: no model gateway"), noGateway.out());
    }

    /**
     * Records the model's answer to the first request a session service builds for {@code request} on this fake
     * agent and pack (the acting policy, the default bounds): a {@code done} with {@code answer}, through the
     * recording gateway, so that the CLI's recorded gateway finds it by the request's fingerprint.
     */
    private void record(Path recordings, String request, String answer) throws Exception {
        Endpoint endpoint = new Endpoint("127.0.0.1", agent.port(), agent.token, null, 0, 0, null);
        Usage usage = new Usage("scripted", "claude-sonnet-5-5", "claude-sonnet-5-5", "req-1", 300, 20, 0, 0, 0,
                false, "0.0012", Duration.ZERO, null);
        ModelResponse done = new ModelResponse(List.of(new Content.ToolUse("t1", "done", Map.of("answer", answer))),
                ModelResponse.StopReason.TOOL_USE, usage, null);
        CostLedger ledger = new CostLedger();
        try (AgentClient client = AgentClient.connect(endpoint);
             ExecutorSession session = ExecutorSession.open(new CliTestPack(), client, SessionOptions.defaults(null)
                     .withBind(BindOptions.defaults().withLedger(ledger)).withId("recording"));
             RecordingGateway gateway = new RecordingGateway(ScriptedGateway.sequence(List.of(done)), recordings,
                     PromptMask.keysOnly(), false, Clock.systemUTC())) {
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.NONE, PromptMask.keysOnly(), false,
                    Clock.systemUTC());
            try (SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), null)) {
                Turn turn = service.ask(request);
                assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            }
        }
        try (var files = Files.list(recordings)) {
            assertFalse(files.toList().isEmpty(), "a recording was written");
        }
    }

    @Test
    void resolveWithACredentialJoinsTheSecretAndTheExportDeclaresIt() throws Exception {
        String password = "pw-seven-secret";
        Path credentials = Files.writeString(tmp.resolve("credentials.yaml"), "version: 1\ncredentials:\n"
                + "  staff:\n    S7: { password: '" + password + "' }\n");
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
        Path out = tmp.resolve("signed-on");
        Path script = script("""
                resolve assistant Staff {} --credential staff
                resolve assistant Staff {} --credential Staff.code
                resolve assistant Staff {} --credential staff.code --credential staff.code
                resolve assistant Staff {"role":"desk"} --strategy java --credential staff.code
                resolve helper Staff {}
                do poke {}
                export signed-on
                """);
        CliHarness.Outcome outcome = cli("session", "--script", script.toString(), "--out", out.toString(),
                "--credentials", credentials.toString(), "--id", "sess-cred").expectExit(Exit.OK);
        String stdout = outcome.out();
        assertFalse((stdout + outcome.err()).contains(password), "the joined secret is never printed");
        assertTrue(stdout.contains("resolve: --credential needs <realm>.<key> (credential: {realm, key} in a"
                + " scenario), found 'staff'"), stdout);
        assertTrue(stdout.contains("resolve: --credential: a credential realm must match [a-z0-9-]+"), stdout);
        assertTrue(stdout.contains("resolve: --credential is given twice"), stdout);
        assertTrue(stdout.contains("assistant = {\"code\":\"S7\",\"name\":\"Desk Seven\"}  (clitest.java.Staff,"
                + " CANONICAL_JAVA, credential staff.code)"), stdout);
        assertTrue(stdout.contains("helper = {\"code\":\"S7\",\"name\":\"Desk Seven\"}  (clitest.java.Staff,"
                + " CANONICAL_JAVA)\n"), stdout);

        Map<String, Object> resolved = null;
        for (String line : Files.readAllLines(out.resolve("journal.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if ("resolve".equals(record.get("type")) && "assistant".equals(record.get("slot"))) {
                resolved = record;
            }
        }
        assertTrue(resolved != null, "the assistant's resolution is journaled");
        assertEquals(Map.of("realm", "staff", "key", "code"), resolved.get("credential"));
        String yaml = Files.readString(out.resolve("signed-on.yaml"));
        assertEquals(1, yaml.split("credential: \\{ realm: staff, key: code }", -1).length - 1, "the assistant's"
                + " declaration, not the helper's: " + yaml);
        assertFalse(Files.readString(out.resolve("bindings/default/signed-on.bindings.json")).contains(password));

        // The replay joins the secret again from the credentials file: the slot says where it comes from.
        try (FakeAgent fresh = new FakeAgent()) {
            Path freshToken = fresh.writeTokenFile(tmp.resolve("fresh-cred.token"));
            List<String> args = new ArrayList<>(List.of("run", out.resolve("signed-on.yaml").toString(),
                    "--bindings", out.resolve("bindings").toString(), "--credentials", credentials.toString()));
            args.addAll(connection(fresh, freshToken));
            CliHarness.Outcome replay = CliHarness.run(tmp.resolve("unused-discovery"), args.toArray(String[]::new))
                    .expectExit(Exit.OK);
            assertTrue(replay.out().contains("PASS      signed-on"), replay.out());
            assertFalse((replay.out() + replay.err()).contains(password), replay.out());
        }

        // Without the credentials file the staff member has no entry to join: the candidate is dropped.
        CliHarness.Outcome noEntry = cli("session", "--script", script("resolve assistant Staff {} --credential"
                + " staff.code\nresolve helper Staff {}\n").toString(), "--out", tmp.resolve("no-entry").toString())
                .expectExit(Exit.OK);
        assertTrue(noEntry.out().contains("resolve: RESOLUTION_FAILED"), noEntry.out());
        assertTrue(noEntry.out().contains("helper = {\"code\":\"S7\""), "a slot without a credential needs no"
                + " entry: " + noEntry.out());
    }

    /** A {@code session --script} run with the tier (b) pack, the served gateway and this script. */
    private CliHarness.Outcome tierBSession(CliTierB tierB, Path out, String commands) throws Exception {
        List<String> args = new ArrayList<>(List.of("session", "--script", script(commands).toString(), "--out",
                out.toString(), "--id", "sess-b"));
        args.addAll(tierB.options());
        args.addAll(connection(agent, tokenFile));
        return CliHarness.runWithLoader(tierB.loader, tmp.resolve("unused-discovery"), args.toArray(String[]::new))
                .never(agent.token);
    }

    /**
     * phase5-contracts.md 7.4, 13.4: a model-written resolution's call is in the session's {@code cost-ledger.jsonl}
     * and, when the session ends, in its {@code inference.jsonl}, beside the session model's own records.
     */
    @Test
    void aModelWrittenResolutionsCallIsInTheSessionsInferenceRecords() throws Exception {
        try (CliTierB tierB = new CliTierB(tmp, agent)) {
            Path out = tmp.resolve("tier-b");
            CliHarness.Outcome outcome = tierBSession(tierB, out, CliTierB.RESOLVE + "\n").expectExit(Exit.OK);
            assertTrue(outcome.out().contains("tool = {\"code\":\"T-1\"}  (inference.sql.Tool, LLM_SQL)"),
                    outcome.out());
            assertTrue(outcome.out().contains("model calls: 1\nmodel cost: $0.0123 (budget $1.00)"), outcome.out());
            assertEquals(1, tierB.calls());
            List<String> ledger = Files.readAllLines(out.resolve("cost-ledger.jsonl"));
            assertEquals(1, ledger.size(), ledger.toString());
            assertEquals("resolve", Json.parseObject(ledger.get(0)).get("purpose"));
            List<String> inference = Files.readAllLines(out.resolve("inference.jsonl"));
            assertEquals(1, inference.size(), "the resolution's record, written when the session ended: " + inference);
            Map<String, Object> record = Json.parseObject(inference.get(0));
            assertEquals("inference", record.get("type"));
            assertEquals("resolve", record.get("purpose"));
            assertEquals("tool", record.get("slot"));
        }
    }

    /**
     * phase5-contracts.md 12.4, 13.4: with a session service, the REPL's {@code resolve} goes through the service's
     * metered entry, so that a model-written resolution counts on the session's budget record.
     */
    @Test
    void aModelWrittenResolutionCountsOnTheSessionsBudget() throws Exception {
        try (CliTierB tierB = new CliTierB(tmp, agent)) {
            Path out = tmp.resolve("tier-b-budget");
            tierBSession(tierB, out, CliTierB.RESOLVE + "\n").expectExit(Exit.OK);
            List<Map<String, Object>> budgets = CliTierB.budgets(out.resolve("journal.jsonl"));
            assertFalse(budgets.isEmpty(), "a budget record after the resolution");
            Map<String, Object> last = budgets.get(budgets.size() - 1);
            assertEquals("SESSION", last.get("scope"));
            assertEquals(0, new BigDecimal(CliTierB.COST).compareTo(new BigDecimal(String.valueOf(
                    last.get("spentUsd")))), last.toString());
        }
    }

    @Test
    void jsonPrintsOneObjectPerCommandAndASummary() throws Exception {
        Path script = script("do poke {}\ncheck counter.value 5\nnote json\n");
        CliHarness.Outcome outcome = cli("session", "--script", script.toString(), "--out",
                tmp.resolve("json").toString(), "--json").expectExit(Exit.OK);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : outcome.out().lines().toList()) {
            lines.add(Json.parseObject(line));
        }
        assertEquals(4, lines.size(), outcome.out());
        assertEquals("do", lines.get(0).get("command"));
        assertEquals("PASS", lines.get(0).get("status"));
        assertEquals("check", lines.get(1).get("command"));
        assertEquals(false, lines.get(1).get("held"));
        assertEquals(false, lines.get(1).get("ok"), "a check that did not hold");
        assertEquals(true, lines.get(2).get("noted"));
        assertEquals(0L, lines.get(3).get("modelCalls"));
        assertEquals(0L, lines.get(3).get("exitCode"));
        assertTrue(outcome.err().contains("model calls: 0"), "the summary lines go to stderr: " + outcome.err());
    }

    @Test
    void aLostAgentOrAFailedLastExportIsExitCode1() throws Exception {
        agent.handlers.put("test.echo", p -> {
            agent.rejectAll = true;
            return Map.of("text", "bye");
        });
        Path lost = script("call test.echo {\"text\":\"x\"}\ndo poke {}\nnote never\n");
        CliHarness.Outcome outcome = cli("session", "--script", lost.toString(), "--out",
                tmp.resolve("lost").toString()).expectExit(Exit.FAILED);
        assertTrue(outcome.out().contains("the agent was lost"), outcome.out());
        assertTrue(outcome.out().contains("; the agent was lost"), "the summary says so: " + outcome.out());
        agent.rejectAll = false;
        agent.handlers.remove("test.echo");

        Path file = Files.writeString(tmp.resolve("not-a-dir"), "x");
        Path failed = script("do poke {}\nexport fine\nexport broken --out " + file + "\n");
        CliHarness.Outcome export = cli("session", "--script", failed.toString(), "--out",
                tmp.resolve("exports").toString()).expectExit(Exit.FAILED);
        assertTrue(export.out().contains("export: the files cannot be written"), export.out());
        Path recovered = script("do poke {}\nexport broken --out " + file + "\nexport fine\n");
        cli("session", "--script", recovered.toString(), "--out", tmp.resolve("exports2").toString())
                .expectExit(Exit.OK);
    }

    @Test
    void usageErrorsAreExitCode64BeforeTheSessionOpens() throws Exception {
        CliHarness.Outcome noTerminal = cli("session").expectExit(Exit.USAGE);
        assertTrue(noTerminal.err().contains("session needs a terminal to read its commands from, or --script"),
                noTerminal.err());
        cli("session", "--script", tmp.resolve("missing").toString()).expectExit(Exit.USAGE);
        Path script = script("quit\n");
        cli("session", "--script", script.toString(), "--id", "Bad_Id").expectExit(Exit.USAGE);
        cli("session", "--script", script.toString(), "--max-actions", "0").expectExit(Exit.USAGE);
        cli("session", "--script", script.toString(), "extra").expectExit(Exit.USAGE);
        CliHarness.Outcome noKey = cli("session", "--script", script.toString(), "--inference", "claude")
                .expectExit(Exit.USAGE);
        assertTrue(noKey.err().contains("the model key is not set (ANTHROPIC_API_KEY)"), noKey.err());
        assertTrue(agent.requests.isEmpty(), "no call before a usage error");
    }
}
