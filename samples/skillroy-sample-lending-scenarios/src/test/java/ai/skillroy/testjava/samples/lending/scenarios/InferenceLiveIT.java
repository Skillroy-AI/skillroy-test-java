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
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.job.JobOptions;
import ai.skillroy.testjava.inference.job.ReResolveReport;
import ai.skillroy.testjava.inference.job.ReResolver;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.ExportResult;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live acceptance of the sample (phase5-contracts.md 15.3 items 3 to 6; 2.3, decision D111): against one sample
 * desk launched on seed A with the agent on the command line (as {@link InferenceChecks} launches it), through the live
 * gateway and the real binder, nightly job, assembled launcher and session service. It spends money, and each item
 * states its bound.
 *
 * <ol>
 *   <li>{@link #scenarioDResolvesLiveToTheCommittedSupervisor()} (item 3): scenario D bound in {@code approve} without
 *       reuse through the live gateway (no recording), label {@code live}, into a temporary bindings root: one model
 *       call, provider {@code claude}, a positive cost below the settings' scenario budget (USD 0.50); the model's
 *       statement passed the guard and the desk's access check, and the supervisor is the committed SQL's;</li>
 *   <li>{@link #theNightlyJobRunsLiveAndItsSecondJobReadsTheCachedPrefix()} (item 4, read as 8.3's last paragraph
 *       reads it): over copies of the committed files of A, B and D, a job over A and B (no model: both
 *       {@code UNCHANGED}) and two jobs over D in a row (each {@code CHANGED}, since a live answer carries a new
 *       request id and cost, with the committed supervisor, one batch of one request, under the job budget of USD
 *       1.00, the batch waited for at most 20 minutes); the second job's cache-hit ratio is above 0.80, since the
 *       first job's request wrote the one-hour prefix the second reads;</li>
 *   <li>{@link #theAuthorCommandAuthorsTheBriefOfScenarioC()} (item 5, skipped with "no claude on the PATH" without
 *       the Claude Code CLI): the assembled launcher's {@code author} on the brief of 14.3 as a subprocess, bounded by
 *       the planner's 15-minute deadline plus one; exit 0, the authored scenario compiled and resolved (its content is
 *       not compared with anything);</li>
 *   <li>{@link #oneLiveSessionTurnLendsAnItemAndItsExportCompiles()} (item 6): a person signs on as in 14.4 and asks
 *       one turn of the live session service "lend one plain item to the walk-in borrower", under the settings' session
 *       budget (USD 1.00); it ends {@code DONE} within the policy's action limit with an answer, and the export
 *       {@value #EXPORTED_ID} compiles; the session closes with the pack's recover, as the CLI's does;</li>
 *   <li>{@link #noSecretInAnythingThisClassWrote()}: the secret scan of 15.2 item 8, last, over every file the checks
 *       above wrote.</li>
 * </ol>
 *
 * <p>Skipped, never failed, without the profile {@code inference-live} ("the profile inference-live is not active"),
 * without the key ("no model key"; {@link Live}) or without a display; never run in CI. Run it in the Linux image with
 * the key passed through the environment: {@code ci/run-in-linux.sh mvn -o verify -pl
 * samples/skillroy-sample-lending-scenarios -am -Pinference-live -Dskillroy.headless=false -Dit.test=InferenceLiveIT
 * -Dfailsafe.failIfNoSpecifiedTests=false}. In the image, item 5 needs a Linux build of the CLI mounted through
 * {@code SKILLROY_CLAUDE_BIN} ({@code ci/run-in-linux.sh}). On a host with the CLI and a display, item 5 and the scan
 * run natively and alone (checked on 2026-10-10 on macOS): the same Maven command without {@code ci/run-in-linux.sh},
 * with {@code -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false} added and {@code -Dit.test} set to
 * {@code InferenceLiveIT#theAuthorCommandAuthorsTheBriefOfScenarioC+noSecretInAnythingThisClassWrote}.
 * Every model call's figures are printed with the prefix {@code [live]} (the request id, the model, the tokens, the
 * cache reads and writes, the cost, the elapsed time): never a prompt, never the key. Outputs are in
 * {@code target/it-runs/inference-live/}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InferenceLiveIT {

    /** The seed every item runs on. */
    static final Env ENV = Env.SEED_A;
    /** The brief of phase5-contracts.md 14.3, verbatim: what item 5's planner authors. */
    static final String BRIEF = "A borrower returns the only item of a loan on the same day it was lent. The desk takes"
            + " the item back, hands the deposit back in cash, and prints a slip for the return. Choose an item that"
            + " needs no induction and an assistant who may lend and take returns within the item's deposit. Check the"
            + " stored loan line is marked returned, the loan's total is back to zero, and the slip shows the deposit"
            + " handed back.";
    /** The id of the scenario item 5 authors. */
    static final String AUTHORED_ID = "live-return-refunds-deposit";
    /** The id of the scenario item 6 exports. */
    static final String EXPORTED_ID = "live-session-basic-loan";
    /** What the person asks the live session in item 6. */
    static final String SESSION_REQUEST = "lend one plain item to the walk-in borrower";
    /** Item 5's bound: the planner's deadline ({@code timeouts.plannerMs}, 15 minutes) plus one minute. */
    static final Duration AUTHOR_WAIT = Duration.ofMinutes(16);
    /** Item 4's longest wait for one batch, and its interval between two status reads. */
    static final Duration BATCH_WAIT = Duration.ofMinutes(20);
    static final Duration BATCH_POLL = Duration.ofSeconds(15);
    /** Item 4's job budget. */
    static final BigDecimal JOB_BUDGET = new BigDecimal("1.00");
    /** The exit criterion of item 4: the second job's cache-hit ratio is above this. */
    static final BigDecimal CACHE_HITS = new BigDecimal("0.80");

    LendingTarget target;
    /** The live settings: gateway {@code claude}, no recording, every other setting at its default. */
    InferenceSettings live;
    /** The files and directories the checks before {@link #noSecretInAnythingThisClassWrote()} wrote. */
    final List<Path> outputs = new ArrayList<>();

    @BeforeAll
    void launch() throws Exception {
        Live.assumeLive();
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        live = new InferenceSettings(InferenceSettings.GATEWAY_CLAUDE, null, false, null, null, null, null, null, null,
                true, false, null, false, null);
        target = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT, ENV.seed, false);
        ItEnv.timing("mode=javaagent env=" + ENV.id + " inference-live target ready ms=" + target.readyMs);
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

    /** {@code target/it-runs/inference-live/<what>}, emptied, remembered for the secret scan. */
    Path runDir(String what) throws Exception {
        Path dir = ItEnv.runDir("inference-live", what);
        ResolutionChecks.deleteTree(dir);
        outputs.add(dir);
        return dir;
    }

    /** {@code approve} through the live settings with this label, the scripted approver, a ledger. */
    BindOptions approve(Path root, String label, CostLedger ledger) {
        return Runs.bind(ENV).withMode(BindMode.APPROVE).withBindingsRoot(root).withLedger(ledger)
                .withInference(live).withApprovedBy(label)
                .withApprover(request -> new Approver.Decision(true, null, "scripted"));
    }

    /**
     * The figures of one model call from its {@code inference.jsonl} record (a ledger entry's details, or a line of a
     * job's file): the request id, the model, the tokens, the cache reads and writes, the cost and the elapsed time.
     * Nothing else of the record: no answer, and a record holds no prompt unless {@code logPrompts} is on (it is off).
     */
    static String figures(Map<?, ?> record) {
        return record.get("purpose") + (record.get("slot") == null ? "" : " slot " + record.get("slot")) + ": request "
                + record.get("requestId") + ", model " + record.get("model") + ", tokens in "
                + record.get("inputTokens") + " out " + record.get("outputTokens") + ", cache read "
                + record.get("cacheReadTokens") + " write 5m " + record.get("cacheWrite5mTokens") + " 1h "
                + record.get("cacheWrite1hTokens") + ", batch " + record.get("batch") + ", cost USD "
                + record.get("costUsd") + ", elapsed " + record.get("elapsedMs") + " ms";
    }

    /** Prints every model call of a ledger, one {@code [live]} line each. */
    static void printCalls(String what, List<CostLedger.Entry> entries) {
        for (CostLedger.Entry entry : entries) {
            System.out.println("[live] " + what + ": " + (entry.details() == null ? entry.provider() + " "
                    + entry.requestId() + " (no record)" : figures(entry.details())));
        }
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000L;
    }

    // ------------------------------------------------------------------------------------------------ item 3

    /**
     * Item 3: scenario D bound in {@code approve} without reuse through the live gateway, nothing recorded, label
     * {@code live}, into a temporary bindings root: one model call, by provider {@code claude}, whose cost is positive
     * and below the settings' scenario budget; the supervisor resolved through tier (b) (so the guard passed the
     * model's statement: a refused one ends the bind), every verifier passed (the desk's access check is the gate), and
     * it is the supervisor the committed SQL finds on seed A.
     */
    @Test
    @Order(1)
    void scenarioDResolvesLiveToTheCommittedSupervisor() throws Exception {
        Path root = runDir("approve-root");
        CostLedger ledger = new CostLedger();
        long t0 = System.nanoTime();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, client(), approve(root, "live",
                ledger).withReuseBindings(false), BindListener.NONE);
        System.out.println("[live] item 3: bound in " + millisSince(t0) + " ms");
        printCalls("item 3", ledger.entries());
        assertEquals(1, ledger.size(), "one live model call");
        CostLedger.Entry call = ledger.entries().get(0);
        assertEquals("claude", call.provider());
        BigDecimal cost = new BigDecimal(call.costUsd());
        BigDecimal budget = new BigDecimal(live.budgets().get(InferenceSettings.BUDGET_SCENARIO));
        assertTrue(cost.signum() > 0 && cost.compareTo(budget) < 0, "cost USD " + cost.toPlainString()
                + ", positive and below the scenario budget USD " + budget.toPlainString());
        Resolution supervisor = outcome.bindings().resolutions().get(ScenarioD.MODEL_SLOT);
        assertEquals(Strategy.LLM_SQL, supervisor.strategy());
        assertTrue(supervisor.verified().stream().allMatch(VerifierResult::passed), String.valueOf(
                supervisor.verified()));
        assertEquals(ScenarioA.expected(ENV).supervisor(), supervisor.value(),
                "the supervisor the committed SQL finds");
        assertNotNull(outcome.written(), "approve wrote the file into the temporary root");
    }

    // ------------------------------------------------------------------------------------------------ item 4

    /**
     * Item 4, the nightly job live (8.3's last paragraph): into a temporary bindings root holding copies of the
     * committed files of A, B and D (they are in the agent channel already), three jobs, none a dry run, each with the
     * job budget of USD 1.00 and a batch wait of 20 minutes: A and B approved as {@code approve} need no model and stay
     * {@code UNCHANGED}; then D, approved with the label its committed file holds, twice in a row ({@code live-1},
     * {@code live-2}): each {@code CHANGED} (a live answer carries a new request id and cost), written with the
     * committed supervisor, under the budget, one batch of one request; and the second job's cache-hit ratio is above
     * 0.80 (its request reads the one-hour prefix the first job's request wrote).
     */
    @Test
    @Order(2)
    void theNightlyJobRunsLiveAndItsSecondJobReadsTheCachedPrefix() throws Exception {
        Path root = runDir("nightly-root");
        for (Path committed : List.of(ScenarioA.committedBindings(ENV), ScenarioB.committedBindings(ENV),
                ScenarioD.committedBindings(ENV))) {
            Path copy = root.resolve(ENV.id).resolve(committed.getFileName());
            Files.createDirectories(copy.getParent());
            Files.copy(committed, copy);
        }
        ReResolveReport tierA = job(root, "live-ab", List.of(ScenarioA.plan(), ScenarioB.plan()), "approve");
        assertEquals(List.of(ReResolveReport.Status.UNCHANGED, ReResolveReport.Status.UNCHANGED), tierA.scenarios()
                .stream().map(ReResolveReport.ScenarioReport::status).toList(), tierA.toMarkdown());
        assertEquals(0, tierA.costUsd().signum(), "no model, no cost: " + tierA.toMarkdown());
        assertEquals(0, tierA.requests(), "tier (a) needs no model");

        Object committedSupervisor = BindingsFile.read(ScenarioD.committedBindings(ENV)).resolutions()
                .get(ScenarioD.MODEL_SLOT).value();
        String label = ScenarioD.approvedBy(ENV);
        ReResolveReport first = job(root, "live-1", List.of(ScenarioD.plan()), label);
        assertScenarioDChanged(first, root, committedSupervisor);
        ReResolveReport second = job(root, "live-2", List.of(ScenarioD.plan()), label);
        assertScenarioDChanged(second, root, committedSupervisor);
        String ratios = "cache hits: job live-1 " + first.cacheHitRatio() + ", job live-2 " + second.cacheHitRatio();
        System.out.println("[live] item 4: " + ratios);
        assertNotNull(second.cacheHitRatio(), ratios);
        assertTrue(second.cacheHitRatio().compareTo(CACHE_HITS) > 0, "the second job reads the prefix the first"
                + " wrote, above " + CACHE_HITS.toPlainString() + ": " + ratios);
    }

    /** One job of item 4 over these plans into its own output directory, its figures printed. */
    private ReResolveReport job(Path root, String jobId, List<Path> plans, String label) throws Exception {
        Path out = runDir("nightly-" + jobId);
        List<Plan> read = new ArrayList<>();
        for (Path plan : plans) {
            read.add(Plan.read(plan));
        }
        BindOptions options = Runs.bind(ENV).withBindingsRoot(root).withLedger(new CostLedger()).withInference(live);
        JobOptions job = JobOptions.of(out, jobId, JOB_BUDGET).withBatchTimes(BATCH_WAIT, BATCH_POLL)
                .withApprovedBy(label);
        ReResolveReport report = ReResolver.run(read, Runs.PACK, client(), options, job);
        System.out.println("[live] item 4, job " + jobId + ": " + report.scenarios().stream().map(s -> s.scenarioId()
                + " " + s.status()).toList() + ", cost USD " + report.costUsd().toPlainString() + " (budget USD "
                + JOB_BUDGET.toPlainString() + "), rounds " + report.rounds() + ", batches " + report.batches()
                + ", requests " + report.requests() + ", cache hits " + report.cacheHitRatio() + ", duration "
                + report.duration().toMillis() + " ms, models " + report.models());
        Path inference = out.resolve(ReResolveReport.INFERENCE_FILE);
        for (String line : Files.readAllLines(inference, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                System.out.println("[live] item 4, job " + jobId + ": " + figures(Json.parseObject(line)));
            }
        }
        return report;
    }

    /** A job of D: CHANGED and written with the committed supervisor, under the budget, one batch of one request. */
    private static void assertScenarioDChanged(ReResolveReport report, Path root, Object committedSupervisor)
            throws Exception {
        ReResolveReport.ScenarioReport d = report.scenario(ScenarioD.ID);
        assertNotNull(d, report.toMarkdown());
        assertEquals(ReResolveReport.Status.CHANGED, d.status(), "a live answer carries a new request id and cost: "
                + report.toMarkdown());
        assertTrue(d.written(), report.toMarkdown());
        BindingsFile written = BindingsFile.read(BindingsFile.locate(root, ENV.id, ScenarioD.ID));
        assertEquals(committedSupervisor, written.resolutions().get(ScenarioD.MODEL_SLOT).value(),
                "the job wrote the committed supervisor");
        assertTrue(report.costUsd().compareTo(JOB_BUDGET) < 0, "cost USD " + report.costUsd().toPlainString()
                + " under the job budget");
        assertEquals(1, report.batches(), report.toMarkdown());
        assertEquals(1, report.requests(), report.toMarkdown());
        assertEquals(0, report.exitCode(), report.toMarkdown());
    }

    // ------------------------------------------------------------------------------------------------ item 5

    /**
     * Whether a regular executable file named {@code claude} is in a {@code PATH} entry: the lookup the CLI's
     * {@code author} makes when neither its option nor its property names the executable (the CLI's own lookup is
     * package-private there).
     */
    static boolean claudeOnPath() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir, "claude");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // A PATH entry that is not a path is skipped, as the CLI skips it.
            }
        }
        return false;
    }

    /**
     * Item 5: the assembled launcher's {@code author} on the brief of 14.3 against this desk, as a subprocess with its
     * standard input closed, its output and errors in files, the environment inherited (the key reaches it that way)
     * and its working directory this item's output directory; bounded by {@link #AUTHOR_WAIT}, after which it is
     * destroyed with its descendants. It exits 0, and its JSON says the authored scenario compiled and resolved; the
     * scenario itself is not compared with anything.
     */
    @Test
    @Order(3)
    void theAuthorCommandAuthorsTheBriefOfScenarioC() throws Exception {
        assumeTrue(claudeOnPath(), "no claude on the PATH");
        Path dir = runDir("author");
        Files.createDirectories(dir);
        Path brief = dir.resolve("brief.txt");
        Files.writeString(brief, BRIEF, StandardCharsets.UTF_8);
        Path credentials = SampleCredentials.write(dir.resolve("secrets"), ENV, null);
        Path cli = Path.of(System.getProperty("skillroy.it.cliDist", "missing")).resolve("bin").resolve("skillroy");
        Path stdout = dir.resolve("author.out");
        Path stderr = dir.resolve("author.err");
        long t0 = System.nanoTime();
        Process author = new ProcessBuilder(cli.toString(), "author", "--brief-file", brief.toString(), "--id",
                AUTHORED_ID, "--out", dir.resolve("scenarios").toString(), "--pack",
                System.getProperty("skillroy.it.packJar"), "--env", ENV.id, "--credentials", credentials.toString(),
                "--pid", String.valueOf(target.pid()), "--discovery-dir", target.discoveryDir.toString(),
                "--budget-usd", "2.00", "--json")
                .directory(dir.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        author.getOutputStream().close();
        boolean ended = Processes.await(author, AUTHOR_WAIT);
        System.out.println("[live] item 5: author " + (ended ? "exited " + author.exitValue() : "destroyed after "
                + AUTHOR_WAIT) + " in " + millisSince(t0) + " ms");
        assertTrue(ended, "author ended within " + AUTHOR_WAIT + "; its errors: " + Files.readString(stderr));
        assertEquals(0, author.exitValue(), "author's exit code; its errors: " + Files.readString(stderr));
        Map<String, Object> json = Json.parseObject(Files.readString(stdout, StandardCharsets.UTF_8).strip());
        System.out.println("[live] item 5: model " + json.get("model") + ", turns " + json.get("turns") + ", repairs "
                + json.get("repairs") + ", cost USD " + json.get("costUsd") + ", claude " + json.get("claudeVersion"));
        Map<?, ?> checks = (Map<?, ?>) json.get("checks");
        assertNotNull(checks, String.valueOf(json));
        assertEquals(Boolean.TRUE, checks.get("compiled"), "the authored scenario compiled: " + checks);
        assertEquals(Boolean.TRUE, checks.get("resolved"), "the authored scenario resolved on " + ENV.id + ": "
                + checks);
    }

    // ------------------------------------------------------------------------------------------------ item 6

    /**
     * Item 6: as the session of 14.4 runs ({@link SessionScript}) but with the live gateway and the real clock: a
     * person resolves the assistant and signs on through the {@code signOn} verb; one turn of the session service
     * ({@link SessionPolicy#acting()}, the settings' session budget) is asked {@value #SESSION_REQUEST}; it ends
     * {@code DONE} within the policy's action limit with an answer. The journal exported as {@value #EXPORTED_ID}
     * compiles (nothing is compared). The session closes with the pack's recover, as the CLI's does, and the desk is
     * signed off again.
     */
    @Test
    @Order(4)
    void oneLiveSessionTurnLendsAnItemAndItsExportCompiles() throws Exception {
        Path dir = runDir("session");
        Credentials credentials = Runs.credentials(ENV);
        CostLedger ledger = new CostLedger();
        SessionOptions options = SessionOptions.defaults(dir.resolve("session")).withClock(Clock.systemUTC())
                .withId("live-basic-loan").withRecoverOnClose(true).withBind(BindOptions.defaults()
                        .withEnvId(ENV.id).withCredentials(credentials).withLedger(ledger).withInference(live));
        PromptMask mask = PromptMask.of(credentials.mask(), Runs.PACK.sqlAllowLists(), Runs.PACK.rowMasks());
        Turn turn;
        ExportResult export;
        try (ExecutorSession session = ExecutorSession.open(Runs.PACK, client(), options)) {
            session.resolve("assistant", "StaffWithPermission", ScriptedAnswers.input("perform", List.of("SIGN_ON"),
                    "interactiveSignOn", true), List.of(), List.of(), new CredentialSpec("staff", "code"),
                    SessionScript.PERSON);
            StepOutcome signOn = session.execute("signOn", Map.of("staff", "$assistant"), SessionScript.PERSON);
            assertEquals(Status.PASS, signOn.status(), signOn.message());
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, live.budgets().get(
                    InferenceSettings.BUDGET_SESSION), ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.toFile(dir.resolve("session")
                    .resolve("inference.jsonl")), mask, false, Clock.systemUTC());
            long t0 = System.nanoTime();
            InferenceGateway gateway = Gateways.open(live, mask);
            try (SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), Clock.systemUTC())) {
                turn = service.ask(SESSION_REQUEST);
            } finally {
                gateway.close();
            }
            Usage usage = turn.totalUsage();
            System.out.println("[live] item 6: turn " + turn.outcome() + " in " + millisSince(t0) + " ms, actions "
                    + turn.actions().stream().map(a -> a.tool() + (a.step() == null ? "" : " " + a.step().status()))
                    .toList() + ", model " + usage.model() + ", tokens in " + usage.inputTokens() + " out "
                    + usage.outputTokens() + ", cache read " + usage.cacheReadTokens() + ", cost USD "
                    + usage.costUsd());
            printCalls("item 6", ledger.entries());
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            assertTrue(turn.actions().size() <= SessionPolicy.acting().maxActionsPerTurn(), "within the action"
                    + " limit: " + turn.actions().size());
            assertTrue(turn.answer() != null && !turn.answer().isBlank(), "the model answered");
            export = session.journal().export(ExportOptions.of(EXPORTED_ID));
        }
        Path yaml = dir.resolve("exported").resolve(EXPORTED_ID + ".yaml");
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, export.yaml(), StandardCharsets.UTF_8);
        Plan plan = PlanCompiler.compile(yaml, Runs.PACK);
        System.out.println("[live] item 6: exported " + plan.scenarioId() + ", " + export.report().steps()
                + " steps, inference " + export.report().inference());
        assertEquals(EXPORTED_ID, plan.scenarioId());
        Map<String, Object> state = LoadModeChecks.value(client().call("lending.state", Map.of(),
                ResolutionChecks.CALL));
        assertEquals("SIGNED_OFF", state.get("name"), "the session recovered the desk when it closed: " + state);
    }

    // ------------------------------------------------------------------------------------------------ last

    /**
     * The secret scan of 15.2 item 8 over every file the checks above wrote ({@link SecretScan}): no staff password of
     * seed A and not the desk's PIN as a value. The credentials files this class wrote itself
     * ({@value Credentials#FILE_NAME}) are left out by name, since they hold the passwords by design, and so are
     * captures and archives ({@code .png}, {@code .zip}), as {@link InferenceChecks} leaves them out.
     */
    @Test
    @Order(5)
    void noSecretInAnythingThisClassWrote() throws Exception {
        Set<String> secrets = new HashSet<>(SampleCredentials.staff(ENV).values());
        secrets.add(LendingTarget.PIN);
        List<String> leaks = new ArrayList<>();
        int scanned = 0;
        for (Path output : outputs) {
            if (!Files.exists(output)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(output)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (name.equals(Credentials.FILE_NAME) || name.endsWith(".png") || name.endsWith(".zip")) {
                        continue;
                    }
                    scanned++;
                    for (String hit : SecretScan.leaks(file, secrets)) {
                        leaks.add(file + ": " + hit);
                    }
                }
            }
        }
        System.out.println("[live] secret scan: " + scanned + " files below " + outputs.size() + " directories");
        assertEquals(List.of(), leaks, "a secret was written");
        assertTrue(scanned > 0, "the checks wrote files to scan");
    }
}
