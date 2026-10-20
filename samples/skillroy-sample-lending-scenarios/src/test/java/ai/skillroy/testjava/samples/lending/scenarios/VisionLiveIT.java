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
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionExtensions;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ProviderTool;
import ai.skillroy.testjava.junit.OutputScan;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.CheckOutcome;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.ExportResult;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.vision.ScenarioPlayer;
import ai.skillroy.testjava.vision.VisionOptions;
import ai.skillroy.testjava.vision.VisionSessionTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live acceptance of the vision bolt-on (phase7-contracts.md 13.3) against one sample desk launched on seed A with
 * the agent on the command line, through the live gateway with the settings file of the profile
 * ({@code src/test/resources/inference/inference-live.yaml}: gateway {@code claude}, {@code images: true},
 * {@code fallbacks: true}, the class {@code haiku} mapped to {@code claude-haiku-5-5}, {@code effort.vision: low}), the
 * session service with the vision tools, and the scenario player. It spends money: USD 1.00 per session (the session
 * meter), USD 3.00 for the class (an item whose bound would pass it is skipped), and each item states its own bound.
 *
 * <ol>
 *   <li>{@link #aV1SessionOnSonnetLendsOneItemAndSettlesTheDeposit()} (item 1): a person signs on and resolves a plain
 *       item; one V1 turn on the class {@code sonnet} at {@code low} lends it and settles the deposit: {@code DONE},
 *       the desk's facts agree, at most 6 UI actions, at most USD 0.10; its journal exported as
 *       {@value #EXPORTED_ID};</li>
 *   <li>{@link #oneToolsetRequestWithTheFallbackHeader()} (item 3, run second so that a header the provider refuses is
 *       recorded before item 2 meets it): one request with the computer toolset and {@code fallbacks: true}; an answer
 *       means the header coexists, an {@code INVALID_REQUEST} is printed as the finding of 7.1;</li>
 *   <li>{@link #aV2SessionThroughTheToolsetWithTheLegend()} (item 2): the same task, V2 through the toolset with the
 *       pack's legend: {@code DONE}, the facts agree, at most 8 UI actions, at most USD 0.10;</li>
 *   <li>{@link #aV1SessionOnHaiku()} (item 4): the same task, V1 on the class {@code haiku} ({@code claude-haiku-5-5}
 *       by the settings file): {@code DONE}, at most USD 0.01;</li>
 *   <li>{@link #aLookQuestionAndTheAiExpectStepsOfThePlayer()} (item 5): a read-only V1 turn asked to use
 *       {@code skillroy_look}, then the player on the committed {@code loan-table-visual-check.yaml} (its true
 *       statement answered {@code YES}) and on the same scenario asking a false statement with {@code expect: NO}:
 *       both pass;</li>
 *   <li>{@link #theExportOfItemOneReplaysWithZeroModelCalls()} (item 6): the export of item 1, compiled, replays and
 *       passes with zero model calls and an empty ledger;</li>
 *   <li>{@link #noSecretAndNoKeyInAnythingThisClassWrote()}: the secret scan (the staff passwords of seed A, the desk's
 *       PIN) and the key check over every file the items wrote.</li>
 * </ol>
 *
 * <p>The key (common rule 6): the files this class writes itself are checked against the key's value before they are
 * written ({@link #write}); the files the framework writes (journals, {@code inference.jsonl}, captures, run
 * directories) are checked at the end of each item, and again by the last check. The key's value is read from the
 * environment by name, compared, and never kept, printed or put in a message.
 *
 * <p>Skipped, never failed, without the profile {@code inference-live}, without the key ({@link Live}) or without a
 * display; never run in CI. Run it in the Linux image with the key passed through the environment:
 * {@code ci/run-in-linux.sh mvn -o verify -pl samples/skillroy-sample-lending-scenarios -am -Pinference-live
 * -Dskillroy.headless=false -Dit.test=VisionLiveIT -Dfailsafe.failIfNoSpecifiedTests=false}. Every model call's figures
 * are printed with the prefix {@code [live]} ({@link InferenceLiveIT#figures}); never a prompt, an image or the key.
 * Outputs are in {@code target/it-runs/vision-live/}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VisionLiveIT {

    /** The seed every item runs on. */
    static final Env ENV = Env.SEED_A;
    /** The settings file of the profile, relative to the module. */
    static final Path SETTINGS_FILE = Path.of("src", "test", "resources", "inference", "inference-live.yaml");
    /** The task of items 1, 2 and 4, with the person's slot named by its references. */
    static final String TASK = "Lend the item $item to the walk-in borrower: enter its code $item.code at the desk, ask"
            + " for the deposit due, and take exactly the deposit due in cash.";
    /** Item 5's request: a read-only turn that asks a visual question. */
    static final String LOOK_REQUEST = "Use skillroy_look once to ask whether the desk's loan table is empty, then tell"
            + " me its verdict in one sentence. Do not act on the desk.";
    /** The id of item 1's export. */
    static final String EXPORTED_ID = "live-vision-loan";
    /** Who the person is in the journals. */
    static final Origin PERSON = Origin.person("tester");
    /** The session budget, per session. */
    static final String SESSION_BUDGET = "1.00";
    /** The class budget. */
    static final BigDecimal CLASS_BUDGET = new BigDecimal("3.00");

    LendingTarget target;
    /** The settings read from {@link #SETTINGS_FILE}. */
    InferenceSettings live;
    /** What the items spent, in USD. */
    BigDecimal spent = BigDecimal.ZERO;
    /** Item 1's export, for item 6; null until item 1 passed its checks. */
    ExportResult itemOneExport;
    /** The files and directories the items wrote. */
    final List<Path> outputs = new ArrayList<>();

    @BeforeAll
    void launch() throws Exception {
        Live.assumeLive();
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        live = InferenceSettings.load(BindMode.LIVE, null, Map.of(InferenceSettings.PROP_FILE, ItEnv.moduleDir()
                .resolve(SETTINGS_FILE).toString()));
        assertEquals(InferenceSettings.GATEWAY_CLAUDE, live.gateway());
        assertTrue(live.images(), "the profile's settings turn images on");
        target = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT, ENV.seed, false);
        ItEnv.timing("mode=javaagent env=" + ENV.id + " vision-live target ready ms=" + target.readyMs);
    }

    @AfterAll
    void close() {
        if (target != null) {
            target.close();
        }
        System.out.println("[live] vision: spent USD " + spent.toPlainString() + " of the class budget USD "
                + CLASS_BUDGET.toPlainString());
    }

    AgentClient client() {
        return target.client;
    }

    /** {@code target/it-runs/vision-live/<what>}, emptied, remembered for the scans. */
    Path runDir(String what) throws Exception {
        Path dir = ItEnv.runDir("vision-live", what);
        ResolutionChecks.deleteTree(dir);
        outputs.add(dir);
        return dir;
    }

    /** Skips the item when its bound would pass the class budget. */
    void assumeBudget(String bound) {
        BigDecimal after = spent.add(new BigDecimal(bound));
        assumeTrue(after.compareTo(CLASS_BUDGET) <= 0, "the class budget USD " + CLASS_BUDGET.toPlainString()
                + " would be passed (spent USD " + spent.toPlainString() + ", this item's bound USD " + bound + ")");
    }

    /** The total cost of a ledger's entries. */
    static BigDecimal cost(CostLedger ledger) {
        return cost(ledger.entries());
    }

    // ------------------------------------------------------------------------------------------------ the key

    /** True when {@code bytes} hold the key's value; false when the key is not set. Nothing of the value leaves. */
    static boolean holdsKey(byte[] bytes) {
        String key = System.getenv(InferenceSettings.KEY_VARIABLE);
        if (key == null || key.isBlank()) {
            return false;
        }
        byte[] needle = key.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Writes {@code text} to {@code file} after checking that it does not hold the key's value. */
    static void write(Path file, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertFalse(holdsKey(bytes), "the model key's value would be written to " + file.getFileName());
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** The files below {@code dir} that hold the key's value (their paths only). */
    static List<String> filesHoldingKey(Path dir) throws Exception {
        List<String> found = new ArrayList<>();
        if (!Files.exists(dir)) {
            return found;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (holdsKey(Files.readAllBytes(file))) {
                    found.add(dir.relativize(file).toString());
                }
            }
        }
        return found;
    }

    static void assertNoKeyBelow(Path dir) throws Exception {
        assertEquals(List.of(), filesHoldingKey(dir), "a file below " + dir.getFileName() + " holds the model key");
    }

    // ------------------------------------------------------------------------------------------------ a session

    /** What one vision session left. */
    record Ran(Turn turn, List<String> toolSets, List<CheckOutcome> checks, ExportResult export, CostLedger ledger,
               int uiActions) {
    }

    /**
     * One vision session on the desk: the person resolves a staff member who signs on with a password and signs them on,
     * resolves a plain item, and brings the desk to the front (a WM-less display never does,
     * {@link VisionSessionScript#activate}); one turn of the session service with the vision tools ({@code vision}
     * options as given) is asked {@code request}; the person checks the loan and the state; the journal is exported
     * as {@code exportId}.
     * The session's settings are the profile's, its host features {@code vision-session} and the tier's, and it closes
     * with the pack's recover.
     */
    Ran session(Path dir, Map<String, String> vision, SessionPolicy policy, String request, String exportId)
            throws Exception {
        Credentials credentials = Runs.credentials(ENV);
        CostLedger ledger = new CostLedger();
        String tier = vision.getOrDefault(VisionOptions.TIER, "v1");
        SessionOptions options = SessionOptions.defaults(dir.resolve("session")).withClock(Clock.systemUTC())
                .withId(exportId + "-session").withRecoverOnClose(true).withBind(BindOptions.defaults()
                        .withEnvId(ENV.id).withCredentials(credentials).withLedger(ledger).withInference(live))
                .withHost(Set.of("vision-session", "vision-tier:" + tier));
        PromptMask mask = PromptMask.of(credentials.mask(), Runs.PACK.sqlAllowLists(), Runs.PACK.rowMasks())
                .withImages(live.images());
        try (ExecutorSession session = ExecutorSession.open(Runs.PACK, client(), options)) {
            session.resolve("assistant", "StaffWithPermission", ScriptedAnswers.input("perform", List.of("SIGN_ON"),
                    "interactiveSignOn", true), List.of(), List.of(), new CredentialSpec("staff", "code"), PERSON);
            StepOutcome signOn = session.execute("signOn", Map.of("staff", "$assistant"), PERSON);
            assertEquals(Status.PASS, signOn.status(), signOn.message());
            session.resolve("item", "LendableItem", ScriptedAnswers.input("perDay", false), List.of(), List.of(), null,
                    PERSON);
            VisionSessionScript.activate(client());
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, SESSION_BUDGET, ledger);
            LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.toFile(dir.resolve("session")
                    .resolve("inference.jsonl")), mask, false, Clock.systemUTC());
            Turn turn;
            List<String> toolSets;
            long t0 = System.nanoTime();
            InferenceGateway gateway = Gateways.open(live, mask);
            try (SessionService service = new SessionService(session, gateway, policy, meter, writer,
                    PriceTable.builtIn(), Clock.systemUTC(), new SessionExtensions(List.of(new VisionSessionTools()),
                    vision))) {
                toolSets = service.toolSets();
                turn = service.ask(request);
            } finally {
                gateway.close();
            }
            int uiActions = (int) turn.actions().stream().filter(a -> a.step() != null).count();
            System.out.println("[live] " + exportId + ": turn " + turn.outcome() + " in " + (System.nanoTime() - t0)
                    / 1_000_000L + " ms, tool sets " + toolSets + ", actions " + turn.actions().stream()
                    .map(a -> a.tool() + (a.step() == null ? "" : " " + a.step().status())).toList() + ", UI actions "
                    + uiActions + ", cost USD " + cost(ledger).toPlainString());
            InferenceLiveIT.printCalls(exportId, ledger.entries());
            List<CheckOutcome> checks = policy.readOnly() ? List.of() : List.of(session.check("loan.total",
                    "$item.deposit", PERSON), session.check("state.name", "IDLE", PERSON));
            ExportResult export = session.journal().export(ExportOptions.of(exportId));
            return new Ran(turn, toolSets, checks, export, ledger, uiActions);
        } finally {
            spent = spent.add(cost(ledger));
            assertNoKeyBelow(dir);
        }
    }

    /** The vision calls of a ledger (the turn's; a resolution the person asked for is not one). */
    static List<CostLedger.Entry> visionCalls(CostLedger ledger) {
        return ledger.entries().stream().filter(e -> CostLedger.Entry.PURPOSE_VISION.equals(e.purpose())).toList();
    }

    /** The total cost of some entries. */
    static BigDecimal cost(List<CostLedger.Entry> entries) {
        BigDecimal total = BigDecimal.ZERO;
        for (CostLedger.Entry entry : entries) {
            if (entry.costUsd() != null) {
                total = total.add(new BigDecimal(entry.costUsd()));
            }
        }
        return total;
    }

    /**
     * The assertions items 1, 2 and 4 share: the tool set applied, {@code DONE}, the facts agree, the UI actions and
     * the cost of the turn's vision calls within their bounds, every vision call by a model whose id starts with
     * {@code model}.
     */
    static void assertLent(Ran ran, int maxActions, String maxCost, String model) {
        assertEquals(List.of("vision"), ran.toolSets(), "the vision tools applied");
        assertEquals(Turn.Outcome.DONE, ran.turn().outcome(), ran.turn().answer());
        assertTrue(ran.checks().stream().allMatch(CheckOutcome::held), "the desk's facts agree: " + ran.checks());
        assertTrue(ran.uiActions() <= maxActions, "at most " + maxActions + " UI actions: " + ran.uiActions());
        List<CostLedger.Entry> calls = visionCalls(ran.ledger());
        assertFalse(calls.isEmpty(), "the turn made vision calls");
        BigDecimal cost = cost(calls);
        assertTrue(cost.compareTo(new BigDecimal(maxCost)) <= 0, "at most USD " + maxCost + ": USD "
                + cost.toPlainString());
        assertTrue(calls.stream().allMatch(e -> e.model() != null && e.model().startsWith(model)), "every vision call"
                + " by " + model + ": " + calls.stream().map(CostLedger.Entry::model).toList());
    }

    // ------------------------------------------------------------------------------------------------ item 1

    /**
     * Item 1: V1 (marks) on the class {@code sonnet} at the settings' {@code effort.vision} ({@code low}): {@code DONE},
     * the loan's total is the item's deposit and the desk is {@code IDLE} again, at most 6 UI actions (BS12: 4-5), at
     * most USD 0.10 (BS12: 0.03). The export is kept for item 6.
     */
    @Test
    @Order(1)
    void aV1SessionOnSonnetLendsOneItemAndSettlesTheDeposit() throws Exception {
        assumeBudget("0.10");
        Ran ran = session(runDir("v1-sonnet"), Map.of(VisionOptions.TIER, "v1"), SessionPolicy.acting(), TASK,
                EXPORTED_ID);
        assertLent(ran, 6, "0.10", live.models().get(InferenceSettings.MODEL_SONNET));
        itemOneExport = ran.export();
    }

    // ------------------------------------------------------------------------------------------------ item 3

    /**
     * Item 3: one request with the computer toolset entry and the settings' {@code fallbacks: true} (the header P5 4.1
     * sends): an answer of any stop reason means the header coexists with the toolset; an {@code INVALID_REQUEST} is
     * the finding of 7.1 (the gateway must then drop the header for a request with a provider tool), printed with the
     * provider's words and not failed, since recording it is the item. Any other failure fails.
     */
    @Test
    @Order(2)
    void oneToolsetRequestWithTheFallbackHeader() throws Exception {
        assumeBudget("0.02");
        assertTrue(live.fallbacks(), "the profile's settings send the fallback header");
        PromptMask mask = PromptMask.keysOnly().withImages(true);
        ModelRequest request = ModelRequest.builder(CostLedger.Entry.PURPOSE_VISION, ModelClass.SONNET_CLASS)
                .effort(Effort.LOW).maxTokens(256).cache(CacheHint.AUTO)
                .system(Content.Text.of("You test a desktop application through the computer toolset. Answer in one"
                        + " short sentence."))
                .providerTool(ProviderTool.computerToolset(false))
                .message(Message.user("Say which tool you would call first to see the screen; do not call it."))
                .build();
        try (InferenceGateway gateway = Gateways.open(live, mask)) {
            ModelResponse response = gateway.complete(request);
            BigDecimal cost = PriceTable.builtIn().cost(response.usage());
            spent = spent.add(cost);
            System.out.println("[live] item 3: the fallback header coexists with the toolset: stop " + response.stop()
                    + ", request " + response.usage().requestId() + ", model " + response.usage().model()
                    + ", tokens in " + response.usage().inputTokens() + " out " + response.usage().outputTokens()
                    + ", cost USD " + cost.toPlainString());
            assertNotNull(response.stop());
        } catch (InferenceException e) {
            if (e.kind() != InferenceException.Kind.INVALID_REQUEST) {
                throw e;
            }
            System.out.println("[live] item 3 FINDING (phase7-contracts.md 7.1): the provider refused a toolset"
                    + " request with the fallback header: request " + e.requestId() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ item 2

    /**
     * Item 2: V2 through the computer toolset with the pack's screen legend in the system text: {@code DONE}, the facts
     * agree, at most 8 UI actions (BS12: 4.5 with the legend), at most USD 0.10.
     */
    @Test
    @Order(3)
    void aV2SessionThroughTheToolsetWithTheLegend() throws Exception {
        assumeBudget("0.10");
        assertNotNull(Runs.PACK.screenLegend(), "the sample pack has a legend");
        Ran ran = session(runDir("v2-toolset"), Map.of(VisionOptions.TIER, "v2", VisionOptions.TOOLS, "toolset"),
                SessionPolicy.acting(), TASK, "live-vision-loan-v2");
        assertLent(ran, 8, "0.10", live.models().get(InferenceSettings.MODEL_SONNET));
    }

    // ------------------------------------------------------------------------------------------------ item 4

    /**
     * Item 4: V1 on the class {@code haiku}, which the profile's settings map to {@code claude-haiku-5-5} (7.3):
     * {@code DONE}, the facts agree, at most USD 0.01; every call by that model.
     */
    @Test
    @Order(4)
    void aV1SessionOnHaiku() throws Exception {
        assumeBudget("0.01");
        assertEquals(PriceTable.HAIKU_5_5, live.models().get(InferenceSettings.MODEL_HAIKU));
        Ran ran = session(runDir("v1-haiku"), Map.of(VisionOptions.TIER, "v1", VisionOptions.MODEL, "haiku"),
                SessionPolicy.acting(), TASK, "live-vision-loan-haiku");
        assertLent(ran, 6, "0.01", PriceTable.HAIKU_5_5);
    }

    // ------------------------------------------------------------------------------------------------ item 5

    /** The committed scenario item 5 plays (seed A's fixtures: assistant 100, the item YRD-001 scanned twice). */
    static final String VISUAL_CHECK = "loan-table-visual-check.yaml";
    /** The committed scenario's question, a true statement on its screen. */
    static final String TRUE_STATEMENT = "the loan table shows one line for the scanned item, with a quantity of 2";
    /** The false statement item 5's second play asks instead, expecting {@code NO}. */
    static final String FALSE_STATEMENT = "the loan table is empty";

    /**
     * The committed scenario with its {@code aiExpect} asking {@link #FALSE_STATEMENT} and expecting {@code NO}: the
     * same fixtures and steps, one question changed.
     */
    static String falseStatementVariant(String committed) {
        String asked = "aiExpect: { question: \"" + TRUE_STATEMENT + "\" }";
        if (!committed.contains(asked)) {
            throw new IllegalStateException("the committed " + VISUAL_CHECK + " no longer asks: " + TRUE_STATEMENT);
        }
        return committed.replace(asked, "aiExpect: { question: \"" + FALSE_STATEMENT + "\", expect: NO }")
                .replace("scenario: loan-table-visual-check", "scenario: loan-table-visual-check-false");
    }

    /**
     * Item 5: (a) a read-only V1 turn ({@link SessionPolicy#readOnlyPolicy()}: the module offers {@code capture},
     * {@code wait} and {@code skillroy_look} only) asked to use {@code skillroy_look}: {@code DONE}, the tool was
     * called, nothing acted; (b) the player on the committed {@value #VISUAL_CHECK}, its fixture slots bound through the
     * session as the person (D425): its {@code aiExpect} on a true statement answers {@code YES} and the run passes;
     * (c) the same scenario with the question {@value #FALSE_STATEMENT} and {@code expect: NO}: the verdict is
     * {@code NO} and the run passes. Bound: USD 0.10.
     */
    @Test
    @Order(5)
    void aLookQuestionAndTheAiExpectStepsOfThePlayer() throws Exception {
        assumeBudget("0.10");
        Ran look = session(runDir("look"), Map.of(VisionOptions.TIER, "v1"), SessionPolicy.readOnlyPolicy(),
                LOOK_REQUEST, "live-vision-look");
        assertEquals(Turn.Outcome.DONE, look.turn().outcome(), look.turn().answer());
        assertTrue(look.turn().actions().stream().anyMatch(a -> "skillroy_look".equals(a.tool())),
                "the model asked its question: " + look.turn().actions());
        assertEquals(0, look.uiActions(), "a read-only turn acts on nothing");

        String committed = Files.readString(ItEnv.scenario(VISUAL_CHECK), StandardCharsets.UTF_8);
        Path variant = runDir("player-false").resolve("loan-table-visual-check-false.yaml");
        write(variant, falseStatementVariant(committed));
        playVisualCheck(runDir("player-true"), VISUAL_CHECK, committed.getBytes(StandardCharsets.UTF_8));
        playVisualCheck(variant.getParent(), variant.getFileName().toString(), Files.readAllBytes(variant));
    }

    /** One play of item 5 through the live gateway: PASS, its one {@code aiExpect} held. */
    private void playVisualCheck(Path dir, String name, byte[] yaml) throws Exception {
        Credentials credentials = Runs.credentials(ENV);
        PromptMask mask = PromptMask.of(credentials.mask(), Runs.PACK.sqlAllowLists(), Runs.PACK.rowMasks())
                .withImages(true);
        VisionPlayerScript.Played played;
        try (InferenceGateway gateway = Gateways.open(live, mask)) {
            played = VisionPlayerScript.play(client(), credentials, gateway, live, dir, name, yaml);
        } finally {
            assertNoKeyBelow(dir);
        }
        spent = spent.add(cost(played.ledger()));
        ScenarioPlayer.Result result = played.result();
        System.out.println("[live] item 5, " + name + ": player " + result.status() + ", steps " + result.steps()
                .stream().map(st -> st.verb() + " " + st.status()).toList() + ", cost USD "
                + cost(played.ledger()).toPlainString());
        InferenceLiveIT.printCalls("item 5", played.ledger().entries());
        assertEquals(Status.PASS, result.status(), result.message() + " " + result.steps());
        assertEquals(1, result.steps().stream().filter(st -> "aiExpect".equals(st.verb())
                && st.status() == Status.PASS).count(), "the question held: " + result.steps());
    }

    // ------------------------------------------------------------------------------------------------ item 6

    /**
     * Item 6: item 1's export written (the YAML, the plan compiled from it, the bindings file), each file checked for
     * the key before it is written; after the pack's recover the plan replays and passes with zero model calls and an
     * empty ledger. Spends nothing.
     */
    @Test
    @Order(6)
    void theExportOfItemOneReplaysWithZeroModelCalls() throws Exception {
        assumeTrue(itemOneExport != null, "item 1 did not pass");
        Path dir = runDir("replay");
        Path root = dir.resolve("exported");
        Path yaml = root.resolve(EXPORTED_ID + ".yaml");
        write(yaml, itemOneExport.yaml());
        Path plan = root.resolve("plan").resolve(EXPORTED_ID + ".plan.json");
        Files.createDirectories(plan.getParent());
        PlanCompiler.compileTo(yaml, Runs.PACK, plan);
        assertNoKeyBelow(root);
        Path bindingsRoot = root.resolve("bindings");
        if (itemOneExport.bindings() != null) {
            write(BindingsFile.locate(bindingsRoot, ENV.id, EXPORTED_ID), ScenarioDRecordedTest.asTheAgentWritesIt(
                    itemOneExport.bindings().toJsonText()));
        }
        Runs.PACK.recover(client());
        CostLedger ledger = new CostLedger();
        Runs.Outcome replay = Runs.run(client(), Plan.read(plan), dir.resolve("run"), Runs.bind(ENV)
                .withBindingsRoot(bindingsRoot).withLedger(ledger));
        Runs.assertPassedWithoutSleeping(replay);
        assertEquals(List.of(), replay.result().modelCalls(), "zero model calls");
        assertTrue(ledger.isEmpty(), "an empty ledger");
        assertNoKeyBelow(dir);
    }

    // ------------------------------------------------------------------------------------------------ last

    /**
     * The secret scan ({@link OutputScan}: no staff password of seed A, not the desk's PIN) over every text file the
     * items wrote, captures and archives left out by name as {@link InferenceChecks} leaves them out (bytes), and the
     * key check over every file, captures included.
     */
    @Test
    @Order(7)
    void noSecretAndNoKeyInAnythingThisClassWrote() throws Exception {
        Set<String> secrets = new HashSet<>(SampleCredentials.staff(ENV).values());
        secrets.add(LendingTarget.PIN);
        List<String> leaks = new ArrayList<>();
        Map<String, List<String>> keys = new LinkedHashMap<>();
        int scanned = 0;
        for (Path output : outputs) {
            if (!Files.exists(output)) {
                continue;
            }
            List<String> holding = filesHoldingKey(output);
            if (!holding.isEmpty()) {
                keys.put(output.getFileName().toString(), holding);
            }
            try (Stream<Path> walk = Files.walk(output)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (name.equals(Credentials.FILE_NAME) || name.endsWith(".png") || name.endsWith(".zip")) {
                        continue;
                    }
                    scanned++;
                    for (String hit : OutputScan.leaks(file, secrets)) {
                        leaks.add(file + ": " + hit);
                    }
                }
            }
        }
        System.out.println("[live] vision secret scan: " + scanned + " files below " + outputs.size()
                + " directories");
        assertEquals(Map.of(), keys, "the model key was written");
        assertEquals(List.of(), leaks, "a secret was written");
        assertTrue(scanned > 0, "the items wrote files to scan");
    }
}
