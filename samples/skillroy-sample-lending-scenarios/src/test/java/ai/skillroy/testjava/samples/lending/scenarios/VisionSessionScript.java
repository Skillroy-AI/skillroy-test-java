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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionExtensions;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.CheckOutcome;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.ExportResult;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.vision.VisionOptions;
import ai.skillroy.testjava.vision.VisionSessionTools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vision session of phase7-contracts.md 12.4 on the real desk (seed A), one script for the end-to-end check
 * (13.2 item 8) and the live test (13.3): a person resolves a staff member who signs on with a password and signs them
 * on through the {@code signOn} verb, resolves a plain item and scans it; a model, through the session service with the
 * vision tools (V1, marks), asks for the deposit due ({@code click} on the {@code DUE} key), types the deposit at the
 * field that has the focus and records it ({@code click} on {@code DEPOSIT}); the person checks the loan; the journal is
 * exported as {@value #SCENARIO_ID}. The session opens with constructed inference settings (gateway {@code recorded},
 * {@code images: true}) beside the gateway the caller gives (the scripted one declares {@code IMAGES}) and the host
 * features {@code vision-session} and {@code vision-tier:v1}, so that {@code inference.vision} is {@code OK} in its
 * merged matrix. Everything that reaches the export is fixed: the session id, the clock, the environment.
 *
 * <p>Single-threaded test code.
 */
final class VisionSessionScript {

    /** The exported scenario's id. */
    static final String SCENARIO_ID = "session-vision-loan";
    /** The session's id. */
    static final String SESSION_ID = "vision-loan-session";
    /** The exported scenario's description, the first line of its comment head. */
    static final String DESCRIPTION = "Exported from a vision session on the sample desk (phase7-contracts.md 12.4): a"
            + " person signs on and scans a plain item, a model looking at the screen asks for the deposit due, takes"
            + " the deposit and records the loan, the person checks it.";
    /** Who the person is in the journal. */
    static final Origin PERSON = Origin.person("tester");

    private VisionSessionScript() {
    }

    /** What a run of the script left. */
    record Result(Turn turn, StepOutcome signOn, StepOutcome scan, List<CheckOutcome> checks, ExportResult export,
                  ExportResult exportFromFile, CostLedger ledger, Path journal, List<String> toolSets) {
    }

    /** The settings the session opens with: the recorded gateway's, with images on. */
    static InferenceSettings settings() {
        return new InferenceSettings(InferenceSettings.GATEWAY_RECORDED, ScriptedAnswers.recordings(), false, null,
                null, null, null, null, null, true, true, null, false, null);
    }

    /**
     * Runs the script on {@code desk} with {@code gateway} as the model, its outputs below {@code dir}
     * ({@code session/}: the journal, {@code inference.jsonl}, {@code captures/}).
     */
    static Result run(AgentConnection desk, Credentials credentials, InferenceGateway gateway, Path dir)
            throws Exception {
        CostLedger ledger = new CostLedger();
        Path out = dir.resolve("session");
        SessionOptions options = SessionOptions.defaults(out).withClock(ScriptedAnswers.CLOCK).withId(SESSION_ID)
                .withBind(BindOptions.defaults().withEnvId(Env.SEED_A.id).withCredentials(credentials)
                        .withLedger(ledger).withInference(settings()))
                .withHost(Set.of("vision-session", "vision-tier:v1"));
        try (ExecutorSession session = ExecutorSession.open(Runs.PACK, desk, options)) {
            session.resolve("assistant", "StaffWithPermission", ScriptedAnswers.input("perform", List.of("SIGN_ON"),
                    "interactiveSignOn", true), List.of(), List.of(), new CredentialSpec("staff", "code"), PERSON);
            StepOutcome signOn = session.execute("signOn", Map.of("staff", "$assistant"), PERSON);
            session.resolve("item", "LendableItem", ScriptedAnswers.input("deposit", ScriptedAnswers.input("between",
                    List.of("1.00", "2.00")), "perDay", false), List.of(), List.of(), null, PERSON);
            StepOutcome scan = session.execute("scan", Map.of("item", "$item"), PERSON);
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            PromptMask mask = PromptMask.of(credentials.mask(), Runs.PACK.sqlAllowLists(), Runs.PACK.rowMasks())
                    .withImages(true);
            LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.toFile(out.resolve("inference.jsonl")),
                    mask, false, ScriptedAnswers.CLOCK);
            Turn turn;
            List<String> toolSets;
            try (SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), ScriptedAnswers.CLOCK, new SessionExtensions(List.of(
                    new VisionSessionTools()), Map.of(VisionOptions.TIER, "v1")))) {
                toolSets = service.toolSets();
                turn = service.ask(ScriptedAnswers.VISION_REQUEST);
            }
            List<CheckOutcome> checks = List.of(session.check("loan.total", "$item.deposit", PERSON),
                    session.check("state.name", "IDLE", PERSON));
            ExportOptions export = ExportOptions.of(SCENARIO_ID).withDescription(DESCRIPTION);
            ExportResult live = session.journal().export(export);
            ExportResult fromFile = StepJournal.read(session.journal().file(), Runs.PACK).export(export);
            return new Result(turn, signOn, scan, checks, live, fromFile, ledger, session.journal().file(), toolSets);
        }
    }

    /** The three files of an export: YAML, plan, bindings. */
    record Files3(Path yaml, Path plan, Path bindings) {
    }

    /** Where the files of {@value #SCENARIO_ID} are below {@code root}. */
    static Files3 files(Path root) {
        return new Files3(root.resolve(SCENARIO_ID + ".yaml"), root.resolve("plan").resolve(SCENARIO_ID
                + ".plan.json"), BindingsFile.locate(root.resolve("bindings"), Env.SEED_A.id, SCENARIO_ID));
    }

    /** The committed copies: {@code scenarios/exported/}. */
    static Files3 committed() {
        return files(ItEnv.moduleDir().resolve("scenarios").resolve("exported"));
    }

    /** Writes an export's three files below {@code root} (the plan compiled from the YAML as written). */
    static Files3 write(ExportResult export, Path root) throws Exception {
        Files3 files = files(root);
        Files.createDirectories(files.plan().getParent());
        Files.createDirectories(files.bindings().getParent());
        Files.writeString(files.yaml(), export.yaml(), StandardCharsets.UTF_8);
        PlanCompiler.compileTo(files.yaml(), Runs.PACK, files.plan());
        if (export.bindings() != null) {
            Files.writeString(files.bindings(), ScenarioDRecordedTest.asTheAgentWritesIt(export.bindings()
                    .toJsonText()), StandardCharsets.UTF_8);
        }
        return files;
    }
}
