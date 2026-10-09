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

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.job.JobOptions;
import ai.skillroy.testjava.inference.job.ReResolveReport;
import ai.skillroy.testjava.inference.job.ReResolver;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.recorded.RecordedGateway;
import ai.skillroy.testjava.inference.recorded.RecordingGateway;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.triage.Triage;
import ai.skillroy.testjava.inference.triage.TriageLabel;
import ai.skillroy.testjava.inference.triage.TriageOptions;
import ai.skillroy.testjava.inference.triage.TriageResult;
import ai.skillroy.testjava.junit.ScenarioSource;
import ai.skillroy.testjava.junit.ScenarioTests;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 5 end to end (phase5-contracts.md 15.2) against one sample lending desk launched on {@link #env()}'s seed with
 * the agent loaded in {@link #mode()}, through the real {@code AgentClient}, binder, executor, JUnit glue, inference
 * module and the committed recordings; no model key, no model call beyond the recorded and scripted gateways.
 *
 * <ol>
 *   <li>{@link #scenarioDReplaysThroughTheJunitGlueWithZeroModelCalls()} (every mode, both seeds): item 1;</li>
 *   <li>{@link #approveAgainstTheRecordingsWritesTheCommittedFileAndReuseMakesNoCall()}: item 2;</li>
 *   <li>{@link #aChangedPromptHasNoRecordingAndARejectCorpusStatementIsRefused()}: item 2, the negatives;</li>
 *   <li>{@link #theSessionScriptExportsTheCommittedScenarioWhichReplaysWithZeroModelCalls()} (seed A): item 4;</li>
 *   <li>{@link #triageLabelsTheFailedRunsThroughRecordingsAndLeavesTheirFilesAlone()} (seed A): item 6;</li>
 *   <li>{@link #aDryRunOfTheNightlyJobReportsEveryScenarioUnchanged()}: item 7;</li>
 *   <li>{@link #theMcpServerAsASubprocessListsTheLendingToolsAndServesTheDesk()} and
 *       {@link #theSessionCommandRunsAScriptExportsAndTheExportReplays()} (seed A): item 5 and the command form of
 *       item 4, through the assembled launcher;</li>
 *   <li>{@link #noSecretInAnythingTheseChecksWrote()}: item 8, last, over the outputs of every check above.</li>
 * </ol>
 *
 * <p>Items 2 to 8 run once per seed, with the agent on the command line ({@link #full()}); the other load modes run
 * item 1 only, which is what "in every load mode" asks of them. Item 3 (scenario C) is
 * {@link ResolutionChecks#scenarioCReplaysGreenAndTheReturnIsStored()}, in every load mode on both seeds.
 * Why item 4 uses the scripted model here and not the recordings: a session's later requests carry what the desk
 * answered (the snapshot's hash, which holds the desk's business date, and the events of each call, whose order the
 * engine's thread decides), so recordings made on the headless desk ({@code SessionExportTest}) answer only there;
 * the scripted model answers by position, and the export it leads to must still be the committed one.
 *
 * <p>Needs a display (skipped when headless); one process per subclass, shared by its checks in order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class InferenceChecks {

    /**
     * Seed A's desk assistant whom the desk signs on by code alone ({@code auto_sign_on}): item 5's verb with an effect
     * enters this code, because a sign-on with a password needs a slot that declares a credential, which a slot
     * resolved over MCP cannot (D94, the tools' half open).
     */
    static final String AUTO_SIGN_ON_ASSISTANT = "103";
    /** How long a subprocess of the assembled launcher may take. */
    static final Duration SUBPROCESS_WAIT = Duration.ofSeconds(120);

    LendingTarget target;
    /** The files and directories the checks before {@link #noSecretInAnythingTheseChecksWrote()} wrote. */
    final List<Path> outputs = new ArrayList<>();

    abstract LendingTarget.Mode mode();

    abstract Env env();

    /** True for the target that runs every check of its seed; the others run item 1 only. */
    boolean full() {
        return mode() == LendingTarget.Mode.JAVAAGENT;
    }

    String slug() {
        return mode().slug + "-" + env().id;
    }

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        target = LendingTarget.launch(mode(), env().seed, false);
        ItEnv.timing("mode=" + mode().slug + " env=" + env().id + " inference target ready ms=" + target.readyMs);
    }

    @AfterAll
    void close() {
        if (target != null) {
            target.close();
        }
    }

    AgentClient client() {
        return target.client;
    }

    Path runDir(String what) throws Exception {
        Path dir = ItEnv.runDir("inference", slug() + "/" + what);
        ResolutionChecks.deleteTree(dir);
        outputs.add(dir);
        return dir;
    }

    /**
     * {@code approve} with the label scenario D's committed file of this seed holds ({@link ScenarioD#approvedBy}),
     * against the recordings, with a ledger.
     */
    BindOptions approve(Path root, InferenceSettings inference, CostLedger ledger) {
        return Runs.bind(env()).withMode(BindMode.APPROVE).withBindingsRoot(root).withLedger(ledger)
                .withInference(inference).withApprovedBy(ScenarioD.approvedBy(env()))
                .withApprover(request -> new Approver.Decision(true, null, "scripted"));
    }

    /**
     * The desk is signed off with no prompt open and, when {@code since} is not negative, published nothing of its own
     * since then (as {@code ResolutionChecks} checks it): a bind sends no input.
     */
    void assertSignedOffAndQuiet(long since) throws Exception {
        Map<String, Object> state = LoadModeChecks.value(client().call("lending.state", Map.of(),
                ResolutionChecks.CALL));
        assertEquals("SIGNED_OFF", state.get("name"), String.valueOf(state));
        assertEquals(null, client().call("lending.prompt", Map.of(), ResolutionChecks.CALL).value(), "no prompt");
        if (since >= 0) {
            Map<String, Object> events = LoadModeChecks.value(client().call("evt.query", Map.of("since", since,
                    "limit", 500L), ResolutionChecks.CALL));
            List<Object> desk = new ArrayList<>();
            for (Object e : (List<?>) events.get("events")) {
                String topic = String.valueOf(((Map<?, ?>) e).get("topic"));
                if (ResolutionChecks.DESK_TOPICS.stream().anyMatch(topic::startsWith)) {
                    desk.add(e);
                }
            }
            assertEquals(List.of(), desk, "the desk was touched");
        }
    }

    long lastSeq() throws Exception {
        Map<String, Object> events = LoadModeChecks.value(client().call("evt.query", Map.of("since", 0L, "limit",
                1L), ResolutionChecks.CALL));
        return ((Number) events.get("lastSeq")).longValue();
    }

    // ------------------------------------------------------------------------------------------------ item 1

    /**
     * Item 1: scenario D, alone, through the JUnit glue in {@code replay} on this seed with the committed bindings: it
     * passes, and the stream's trailing {@code skillroy: zero model calls} test passes; its run asked no resolver and
     * its cost ledger is empty.
     */
    @TestFactory
    @Order(1)
    Stream<DynamicTest> scenarioDReplaysThroughTheJunitGlueWithZeroModelCalls() throws Exception {
        Path out = runDir("glue");
        RunOptions options = RunOptions.defaults().withOutputDir(out).withBind(Runs.bind(env()));
        ScenarioSource all = ScenarioSource.directory(ItEnv.moduleDir().resolve("scenarios"));
        ScenarioSource onlyD = new ScenarioSource() {
            @Override
            public List<Entry> entries() throws IOException {
                return all.entries().stream().filter(entry -> entry.name().equals(ScenarioD.YAML)).toList();
            }

            @Override
            public Path scenarioDirectory() {
                return all.scenarioDirectory();
            }
        };
        List<DynamicTest> tests = ScenarioTests.dynamicTests(onlyD, this::client, options).toList();
        assertEquals(List.of(ScenarioD.ID, "skillroy: zero model calls"), tests.stream()
                .map(DynamicTest::getDisplayName).toList());
        List<DynamicTest> checked = new ArrayList<>(tests);
        checked.add(DynamicTest.dynamicTest("scenario D's run: replayed, no resolver, no model call", () -> {
            List<Map<String, Object>> log = Runs.runLog(out.resolve(ScenarioD.ID));
            assertEquals("PASS", log.get(log.size() - 1).get("status"));
            assertFalse(log.stream().anyMatch(r -> "resolver".equals(r.get("type"))), "no resolver in replay");
            assertTrue(log.stream().filter(r -> "resolve".equals(r.get("type")))
                    .allMatch(r -> "REPLAY".equals(r.get("strategy"))), "every slot replayed");
            assertEquals(0L, Files.size(out.resolve(ScenarioD.ID).resolve("cost-ledger.jsonl")), "zero model calls");
            // phase6-contracts.md 13.2 item 4: in the hooks legs the run found or installed the pack's catalogue; in
            // every leg it archived its matrix.
            Runs.assertHooksRecord(out.resolve(ScenarioD.ID), mode() == LendingTarget.Mode.JAVAAGENT_HOOKS);
            assertSignedOffAndQuiet(-1);
        }));
        return checked.stream();
    }

    // ------------------------------------------------------------------------------------------------ item 2

    /**
     * Item 2: {@code approve --no-reuse-bindings} of scenario D against the committed recordings writes the committed
     * file's bytes (one recorded call); with the reuse rule on, the same file is written with no call; the desk is
     * untouched by both.
     */
    @Test
    @Order(2)
    void approveAgainstTheRecordingsWritesTheCommittedFileAndReuseMakesNoCall() throws Exception {
        assumeTrue(full(), "once per seed");
        long seq = lastSeq();
        Path root = runDir("approve-root");
        CostLedger ledger = new CostLedger();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, client(), approve(root,
                ScriptedAnswers.recorded(ScriptedAnswers.recordings()), ledger).withReuseBindings(false),
                BindListener.NONE);
        assertEquals(1, ledger.size(), "model calls: 1: " + ledger.entries());
        assertEquals("recorded", ledger.entries().get(0).provider());
        String committed = Files.readString(ScenarioD.committedBindings(env()), StandardCharsets.UTF_8);
        assertNotNull(outcome.written());
        assertEquals(committed, Files.readString(outcome.written(), StandardCharsets.UTF_8),
                "byte-identical to the committed file");

        CostLedger again = new CostLedger();
        BindOutcome reused = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, client(), approve(root,
                ScriptedAnswers.recorded(ScriptedAnswers.recordings()), again), BindListener.NONE);
        assertTrue(again.isEmpty(), "model calls: 0 with the reuse rule: " + again.entries());
        // The identical file is left alone (approve step 8 writes nothing when the text is unchanged): the outcome
        // names no written file, its file is the committed one and the first call's file is untouched on disk.
        assertNull(reused.written(), "an unchanged file is not rewritten");
        assertEquals(committed, reused.file().toJsonText(), "the identical file");
        assertEquals(committed, Files.readString(outcome.written(), StandardCharsets.UTF_8), "untouched on disk");
        assertSignedOffAndQuiet(seq);
    }

    /**
     * Item 2, the negatives: a changed prompt (one word more in the catalogue) has no recording and ends ENVIRONMENT,
     * "no recording"; a scripted model answering a statement of the guard's reject corpus ends RESOLUTION_FAILED with
     * the guard's reason. Nothing is written and the desk is untouched.
     */
    @Test
    @Order(3)
    void aChangedPromptHasNoRecordingAndARejectCorpusStatementIsRefused() throws Exception {
        assumeTrue(full(), "once per seed");
        long seq = lastSeq();
        Path root = runDir("negative-root");
        BindException changed = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioD.plan()),
                OverlayPack.changedCatalogue(), client(), approve(root, ScriptedAnswers.recorded(
                        ScriptedAnswers.recordings()), new CostLedger()).withReuseBindings(false), BindListener.NONE));
        assertEquals(FailureKind.ENVIRONMENT, changed.kind(), changed.getMessage());
        assertTrue(changed.getMessage().contains("no recording"), changed.getMessage());

        ScriptedGateway rejecting = ScriptedGateway.of(ScriptedAnswers::rejecting);
        BindException refused;
        try (Gateways.Served served = Gateways.serve(ScriptedAnswers.scripted(), rejecting)) {
            refused = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK,
                    client(), approve(root, ScriptedAnswers.scripted(), new CostLedger()).withReuseBindings(false),
                    BindListener.NONE));
        }
        assertEquals(FailureKind.RESOLUTION_FAILED, refused.kind(), refused.getMessage());
        assertTrue(Json.write(refused.detail()).contains("guard " + ScriptedAnswers.REJECT_REASON),
                Json.write(refused.detail()));
        assertFalse(Files.exists(root.resolve(env().id)), "nothing written");
        assertSignedOffAndQuiet(seq);
    }

    // ------------------------------------------------------------------------------------------------ item 4

    /**
     * Item 4: the session script of 14.4 on this desk (seed A), the model scripted by position: a person signs on, the
     * model lends one plain item and settles the deposit, the person checks the loan; the export equals the committed
     * copies (the YAML, the plan, the bindings file); after the pack's recover, the committed export replays with zero
     * model calls and passes.
     */
    @Test
    @Order(4)
    void theSessionScriptExportsTheCommittedScenarioWhichReplaysWithZeroModelCalls() throws Exception {
        assumeTrue(full() && env() == Env.SEED_A, "seed A, once");
        Path dir = runDir("session");
        ScriptedGateway model = ScriptedGateway.of(ScriptedAnswers.sessionScript());
        SessionScript.Result result = SessionScript.run(client(), Runs.credentials(env()), model, dir);
        assertEquals(Turn.Outcome.DONE, result.turn().outcome(), result.turn().answer());
        assertEquals(5, model.requests().size(), "five model calls");
        assertTrue(result.checks().stream().allMatch(c -> c.held()), String.valueOf(result.checks()));
        SessionScript.Files3 written = SessionScript.write(result.export(), dir.resolve("exported"));
        SessionScript.Files3 committed = SessionScript.committed();
        assertEquals(SessionScript.read(committed.yaml()), SessionScript.read(written.yaml()), "the YAML");
        assertEquals(SessionScript.read(committed.plan()), SessionScript.read(written.plan()), "the plan");
        assertEquals(SessionScript.read(committed.bindings()), SessionScript.read(written.bindings()), "the bindings");
        assertEquals(result.export().yaml(), result.exportFromFile().yaml(), "the journal read back exports the same");

        Runs.PACK.recover(client());
        CostLedger ledger = new CostLedger();
        Runs.Outcome replay = Runs.run(client(), Plan.read(committed.plan()), runDir("session-replay"),
                Runs.bind(env()).withBindingsRoot(committed.bindings().getParent().getParent()).withLedger(ledger));
        Runs.assertPassedWithoutSleeping(replay);
        assertEquals(List.of(), replay.result().modelCalls(), "zero model calls");
        assertTrue(ledger.isEmpty());
        assertSignedOffAndQuiet(-1);
    }

    // ------------------------------------------------------------------------------------------------ item 6

    /** The SHA-256 of every file below a run directory, except what triage writes. */
    static Map<String, String> runFiles(Path dir) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith(Triage.LEDGER_DIR + "/") || relative.equals(Triage.FAILURE_DIR + "/"
                        + Triage.JSON_FILE) || relative.equals(Triage.FAILURE_DIR + "/" + Triage.MARKDOWN_FILE)) {
                    continue;
                }
                hashes.put(relative, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file))));
            }
        }
        return hashes;
    }

    /**
     * Item 6: the two negative runs of the resolution checks, made again here on seed A (the wrong expectation ends
     * FAIL / ASSERTION, the wrong assistant ERROR / REBIND_NEEDED), triaged first through a recording gateway around
     * the scripted triage answers, then through the recorded gateway from those recordings (a triage request holds
     * the run's own records, so only recordings of this very run can answer it): each writes {@code triage.json} and
     * {@code triage.md} with the same label and summary, and no other file of the run changes.
     */
    @Test
    @Order(5)
    void triageLabelsTheFailedRunsThroughRecordingsAndLeavesTheirFilesAlone() throws Exception {
        assumeTrue(full() && env() == Env.SEED_A, "seed A, once");
        Runs.Outcome failed = Runs.run(client(), Runs.negative("wrong-expectation.yaml"), runDir("wrong-expectation"));
        assertEquals(Status.FAIL, failed.result().status(), failed.describe());
        Path wrongRoot = runDir("wrong-assistant-root");
        ScenarioA.writeWrongAssistant(env(), wrongRoot);
        Runs.Outcome error = Runs.run(client(), Plan.read(ScenarioA.plan()), runDir("wrong-assistant"),
                Runs.bind(env()).withMode(BindMode.REPLAY).withBindingsRoot(wrongRoot));
        assertEquals(Status.ERROR, error.result().status(), error.describe());
        assertEquals(FailureKind.REBIND_NEEDED, error.result().failureKind(), error.describe());

        Path recordings = runDir("triage-recordings");
        PromptMask mask = PromptMask.of(Runs.credentials(env()).mask(), Runs.PACK.sqlAllowLists(),
                Runs.PACK.rowMasks());
        TriageOptions options = new TriageOptions(false, ScriptedAnswers.CLOCK);
        Map<Path, TriageLabel.Label> expected = Map.of(failed.dir(), TriageLabel.Label.APPLICATION_DEFECT,
                error.dir(), TriageLabel.Label.DATA_DRIFT);
        for (Map.Entry<Path, TriageLabel.Label> run : expected.entrySet()) {
            Map<String, String> before = runFiles(run.getKey());
            TriageResult first;
            try (RecordingGateway recording = new RecordingGateway(ScriptedGateway.of(ScriptedAnswers::triage),
                    recordings, PromptMask.keysOnly(), true, ScriptedAnswers.CLOCK)) {
                first = Triage.run(run.getKey(), recording, mask, PriceTable.builtIn(), options);
            }
            assertEquals(TriageResult.Outcome.TRIAGED, first.outcome(), first.message());
            assertEquals(run.getValue(), first.label().label(), run.getKey().toString());
            assertNotNull(first.summary());
            String json = Files.readString(first.json());
            TriageResult again = Triage.run(run.getKey(), new RecordedGateway(recordings), mask, PriceTable.builtIn(),
                    options);
            assertEquals(TriageResult.Outcome.TRIAGED, again.outcome(), again.message());
            assertEquals(first.label(), again.label(), "the recorded answers are the same answers");
            assertEquals(first.summary(), again.summary());
            assertEquals(json, Files.readString(again.json()), "the same triage.json, model and request ids included");
            assertTrue(Files.isRegularFile(again.markdown()));
            assertTrue(Files.isRegularFile(run.getKey().resolve(Triage.LEDGER_DIR).resolve("inference.jsonl")));
            assertEquals(before, runFiles(run.getKey()), "the run's own files are unchanged");
        }
        try (Stream<Path> files = Files.list(recordings)) {
            assertEquals(4L, files.filter(f -> f.getFileName().toString().endsWith(".json")).count(),
                    "a label and a summary per run");
        }
        assertSignedOffAndQuiet(-1);
    }

    // ------------------------------------------------------------------------------------------------ item 7

    /**
     * Item 7: the nightly job's dry run on this seed against the recordings: scenarios A and B (approved as
     * {@code approve}) and scenario D (approved with the label its committed file holds) are {@code UNCHANGED} against
     * the committed files; D's request goes through the recorded gateway's batch interface; the report has its cache
     * line. Two jobs, because a job approves every file it writes under one label.
     */
    @Test
    @Order(6)
    void aDryRunOfTheNightlyJobReportsEveryScenarioUnchanged() throws Exception {
        assumeTrue(full(), "once per seed");
        long seq = lastSeq();
        BindOptions options = Runs.bind(env()).withLedger(new CostLedger())
                .withInference(ScriptedAnswers.recorded(ScriptedAnswers.recordings()));
        JobOptions job = JobOptions.of(runDir("nightly"), "nightly", new BigDecimal("1.00")).withDryRun(true)
                .withBatchTimes(Duration.ofMinutes(5), Duration.ZERO);
        ReResolveReport tierA = ReResolver.run(List.of(Plan.read(ScenarioA.plan()), Plan.read(ScenarioB.plan())),
                Runs.PACK, client(), options, job.withApprovedBy("approve"));
        assertEquals(List.of(ReResolveReport.Status.UNCHANGED, ReResolveReport.Status.UNCHANGED), tierA.scenarios()
                .stream().map(ReResolveReport.ScenarioReport::status).toList(), tierA.toMarkdown());
        Path reportD = runDir("nightly-d");
        ReResolveReport tierB = ReResolver.run(List.of(Plan.read(ScenarioD.plan())), Runs.PACK, client(), options,
                job.withOutDir(reportD).withApprovedBy(ScenarioD.approvedBy(env())));
        assertEquals(ReResolveReport.Status.UNCHANGED, tierB.scenarios().get(0).status(), tierB.toMarkdown());
        assertEquals(1, tierB.requests(), tierB.toMarkdown());
        String markdown = Files.readString(reportD.resolve(ReResolveReport.MARKDOWN_FILE));
        assertTrue(markdown.contains("; cache hits "), markdown);
        assertSignedOffAndQuiet(seq);
    }

    // ------------------------------------------------------------------------------------------------ items 5 and 4

    /**
     * Item 5: {@code skillroy mcp} as a subprocess of the assembled launcher against this desk: read-only, it lists the
     * workflow reads and the lending reads and no verb; with {@code --mutating} the verbs; a snapshot and a fact are
     * read; a resource is read; and verbs with an effect run through the session entry: entering the code of
     * {@link #AUTO_SIGN_ON_ASSISTANT} signs the assistant on (the desk IDLE), {@code signOff} signs them off (the desk
     * SIGNED_OFF), each result PASS, both steps in the server's journal under its {@code --out}.
     */
    @Test
    @Order(8)
    void theMcpServerAsASubprocessListsTheLendingToolsAndServesTheDesk() throws Exception {
        assumeTrue(full() && env() == Env.SEED_A, "seed A, once");
        Path credentials = SampleCredentials.write(runDir("mcp-secrets"), env(), null);
        List<String> connection = List.of("--pid", String.valueOf(target.pid()), "--discovery-dir",
                target.discoveryDir.toString(), "--pack", Path.of(System.getProperty("skillroy.it.packJar"))
                        .toString(), "--credentials", credentials.toString(), "--env", env().id);
        Path cli = Path.of(System.getProperty("skillroy.it.cliDist", "missing")).resolve("bin").resolve("skillroy");
        try (McpSyncClient readOnly = mcp(cli, connection, runDir("mcp"), false)) {
            List<String> names = readOnly.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertTrue(names.containsAll(List.of("skillroy_snapshot", "skillroy_facts", "lending_access_check")),
                    String.valueOf(names));
            assertFalse(names.contains("lending_sign_on") || names.contains("lending_scan"), "no verb read-only");
            McpSchema.CallToolResult snapshot = readOnly.callTool(new McpSchema.CallToolRequest("skillroy_snapshot",
                    Map.of()));
            assertTrue(text(snapshot).contains("SIGNED_OFF"), text(snapshot));
            McpSchema.CallToolResult state = readOnly.callTool(new McpSchema.CallToolRequest("skillroy_facts",
                    Map.of("root", "state")));
            assertTrue(text(state).contains("SIGNED_OFF"), text(state));
            McpSchema.ReadResourceResult profile = readOnly.readResource(new McpSchema.ReadResourceRequest(
                    "skillroy://env/profile"));
            assertTrue(((McpSchema.TextResourceContents) profile.contents().get(0)).text().contains("lending"));
        }
        assertSignedOffAndQuiet(-1);
        Path out = runDir("mcp-mutating");
        try (McpSyncClient mutating = mcp(cli, connection, out, true)) {
            List<String> names = mutating.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertTrue(names.containsAll(List.of("lending_sign_on", "lending_scan", "lending_deposit", "lending_enter",
                    "lending_sign_off")), String.valueOf(names));
            McpSchema.CallToolResult on = mutating.callTool(new McpSchema.CallToolRequest("lending_enter",
                    Map.of("value", AUTO_SIGN_ON_ASSISTANT)));
            assertStep(on, "enter");
            Map<String, Object> state = LoadModeChecks.value(client().call("lending.state", Map.of(),
                    ResolutionChecks.CALL));
            assertEquals("IDLE", state.get("name"), "signed on: " + state);
            assertEquals(AUTO_SIGN_ON_ASSISTANT, ((Map<?, ?>) state.get("staff")).get("code"), String.valueOf(state));
            McpSchema.CallToolResult off = mutating.callTool(new McpSchema.CallToolRequest("lending_sign_off",
                    Map.of()));
            assertStep(off, "signOff");
            assertSignedOffAndQuiet(-1);
            // Read while the server runs: each record is flushed when its step ends, and a server that is being
            // stopped may still be writing its session's last record.
            assertJournalSignedOnAndOff(sessionJournal(out));
        }
        Runs.PACK.recover(client());
        assertSignedOffAndQuiet(-1);
    }

    /** A verb's tool result: not an error, and the step it reports passed. */
    private static void assertStep(McpSchema.CallToolResult result, String verb) {
        assertFalse(Boolean.TRUE.equals(result.isError()), verb + ": " + text(result));
        Map<String, Object> step = Json.parseObject(text(result));
        assertEquals(verb, step.get("verb"), text(result));
        assertEquals("PASS", step.get("status"), text(result));
    }

    /** The journal of the one session a server wrote below its {@code --out}: {@code runs/<session id>/}. */
    static Path sessionJournal(Path out) throws IOException {
        try (Stream<Path> sessions = Files.list(out.resolve("runs"))) {
            List<Path> dirs = sessions.filter(Files::isDirectory).toList();
            assertEquals(1, dirs.size(), "one session: " + dirs);
            return dirs.get(0).resolve("journal.jsonl");
        }
    }

    /**
     * Item 5's verbs as a journal records them: two steps through the session entry, {@code enter} with the
     * assistant's code and {@code signOff}, each PASS and each sending one {@code lending.input}.
     */
    static void assertJournalSignedOnAndOff(Path journal) throws IOException {
        List<StepJournal.StepEntry> steps = StepJournal.read(journal, Runs.PACK).entries().stream()
                .filter(e -> e instanceof StepJournal.StepEntry).map(e -> (StepJournal.StepEntry) e).toList();
        assertEquals(List.of("enter", "signOff"), steps.stream().map(StepJournal.StepEntry::verb).toList(),
                journal.toString());
        assertEquals(Map.of("value", AUTO_SIGN_ON_ASSISTANT), steps.get(0).args());
        for (StepJournal.StepEntry step : steps) {
            assertEquals(Status.PASS, step.status(), step.verb() + ": " + step.outcome());
            assertEquals(List.of("lending.input"), step.calls().stream().map(c -> c.get("primitive")).toList(),
                    step.verb() + ": " + step.calls());
        }
    }

    private static McpSyncClient mcp(Path cli, List<String> connection, Path out, boolean mutating) {
        List<String> args = new ArrayList<>(List.of("mcp"));
        args.addAll(connection);
        args.addAll(List.of("--out", out.toString()));
        if (mutating) {
            args.add("--mutating");
        }
        McpSyncClient client = McpClient.sync(new StdioClientTransport(ServerParameters.builder(cli.toString())
                .args(args).build(), new JacksonMcpJsonMapperSupplier().get()))
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(120))
                .clientInfo(new McpSchema.Implementation("skillroy-e2e", "1.0")).build();
        client.initialize();
        return client;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /**
     * The command form of item 4: {@code skillroy session --script} against this desk, the person's commands only
     * ({@code --inference none}: the recorded session answers on the headless desk only, see the class comment): it
     * resolves, signs on, lends and settles through {@code do}, checks through facts and exports; the exported scenario
     * compiles and replays with {@code model calls: 0}. A launcher subprocess that does not end within
     * {@link #SUBPROCESS_WAIT} is destroyed before the check fails ({@link Processes#await}).
     */
    @Test
    @Order(9)
    void theSessionCommandRunsAScriptExportsAndTheExportReplays() throws Exception {
        assumeTrue(full() && env() == Env.SEED_A, "seed A, once");
        Path dir = runDir("cli-session");
        Path credentials = SampleCredentials.write(dir.resolve("secrets"), env(), null);
        Path script = dir.resolve("session.txt");
        Files.writeString(script, String.join("\n",
                "resolve assistant StaffWithPermission {\"perform\":[\"SIGN_ON\"],\"interactiveSignOn\":true}"
                        + " --credential staff.code",
                "do signOn {\"staff\":\"$assistant\"}",
                "resolve item LendableItem {\"deposit\":{\"between\":[\"1.00\",\"2.00\"]},\"perDay\":false}",
                "do scan {\"item\":\"$item\"}",
                "do key {\"action\":\"DUE\"}",
                "do deposit {}",
                "check loan.total \"$item.deposit\"",
                "check state.name \"IDLE\"",
                "export " + SessionScript.SCENARIO_ID + " --out " + dir.resolve("exported"),
                "quit", ""), StandardCharsets.UTF_8);
        Path cli = Path.of(System.getProperty("skillroy.it.cliDist", "missing")).resolve("bin").resolve("skillroy");
        Process session = new ProcessBuilder(cli.toString(), "session", "--pid", String.valueOf(
                target.pid()), "--discovery-dir", target.discoveryDir.toString(), "--pack",
                System.getProperty("skillroy.it.packJar"), "--credentials", credentials.toString(), "--env", env().id,
                "--inference", "none", "--script", script.toString(), "--out", dir.resolve("out").toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("session.out").toFile()).start();
        assertTrue(Processes.await(session, SUBPROCESS_WAIT), "the session ended");
        assertEquals(0, session.exitValue(), Files.readString(dir.resolve("session.out")));
        Path yaml = dir.resolve("exported").resolve(SessionScript.SCENARIO_ID + ".yaml");
        assertTrue(Files.isRegularFile(yaml));
        Process replay = new ProcessBuilder(cli.toString(), "run", yaml.toString(), "--mode", "replay", "--bindings",
                dir.resolve("exported").resolve("bindings").toString(), "--pid", String.valueOf(target.pid()),
                "--discovery-dir", target.discoveryDir.toString(), "--pack", System.getProperty("skillroy.it.packJar"),
                "--credentials", credentials.toString(), "--env", env().id).redirectErrorStream(true)
                .redirectOutput(dir.resolve("replay.out").toFile()).start();
        assertTrue(Processes.await(replay, SUBPROCESS_WAIT), "the replay ended");
        assertEquals(0, replay.exitValue(), Files.readString(dir.resolve("replay.out")));
        assertTrue(Files.readString(dir.resolve("replay.out")).contains("model calls: 0"));
        assertSignedOffAndQuiet(-1);
    }

    // ------------------------------------------------------------------------------------------------ item 8

    /**
     * Item 8, last, over the outputs of every check above: no file they wrote holds a staff password of this seed or
     * the desk's PIN as a value ({@link SecretScan}: strings of JSON, the JSON in labelled blocks and in escaped JSON
     * text, the tokens of the rest): the journals of the sessions and of the MCP server, {@code inference.jsonl} files,
     * the exports, {@code triage.json}, {@code triage.md}, the triage's ledgers and recordings, the nightly job's
     * reports, the runs' outputs, the launcher's output. The credentials files the checks wrote themselves (for the
     * launcher, {@value Credentials#FILE_NAME}) are left out by name: they hold the passwords by design.
     */
    @Test
    @Order(10)
    void noSecretInAnythingTheseChecksWrote() throws Exception {
        assumeTrue(full(), "once per seed");
        Set<String> secrets = new java.util.HashSet<>(SampleCredentials.staff(env()).values());
        secrets.add(LendingTarget.PIN);
        List<String> leaks = new ArrayList<>();
        Set<String> kinds = new java.util.TreeSet<>();
        Set<String> scanned = new java.util.TreeSet<>();
        for (Path output : outputs) {
            if (!Files.exists(output)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(output)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".png") || name.endsWith(".zip") || name.equals(Credentials.FILE_NAME)) {
                        continue;
                    }
                    kinds.add(name.endsWith(".bindings.json") ? "bindings file" : name.endsWith(".plan.json") ? "plan"
                            : name.endsWith(".yaml") ? "yaml" : name.matches("[0-9a-f]{64}\\.json") ? "recording"
                            : name);
                    scanned.add(output.getFileName().toString());
                    for (String hit : SecretScan.leaks(file, secrets)) {
                        leaks.add(file + ": " + hit);
                    }
                }
            }
        }
        assertEquals(List.of(), leaks, "a secret was written");
        List<String> expected = new ArrayList<>(List.of("bindings file", "cost-ledger.jsonl", "run.jsonl",
                "re-resolve-report.md"));
        if (env() == Env.SEED_A) {
            expected.addAll(List.of("journal.jsonl", "inference.jsonl", "yaml", "plan", "triage.json", "triage.md",
                    "recording", "session.out", "replay.out"));
            assertTrue(scanned.containsAll(List.of("mcp-mutating", "cli-session")), "the outputs of items 5 and 4's"
                    + " command form were looked at: " + scanned);
        }
        assertTrue(kinds.containsAll(expected), "every kind of output was looked at: " + kinds);
    }
}
