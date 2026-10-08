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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The interactive session of phase5-contracts.md 14.4 on the sample desk, as one script for every place it runs (the
 * headless acceptance on {@link HeadlessDesk}, the end-to-end check on the real desk): a person resolves a staff member
 * who signs on with a password and signs them on through the {@code signOn} verb; asks a model, through the session
 * service, to {@value ScriptedAnswers#SESSION_REQUEST}; checks the loan's total and the desk's state through the
 * pack's facts; and the journal is exported as {@value #SCENARIO_ID}. The model is whichever gateway the caller gives:
 * the committed recordings, a recording gateway around {@link ScriptedAnswers#sessionScript()}, or the script itself.
 * Everything that reaches the export is fixed: the session id, its clock ({@link ScriptedAnswers#CLOCK}), the
 * environment {@code seed-a}.
 *
 * <p>Single-threaded test code.
 */
final class SessionScript {

    /** The exported scenario's id. */
    static final String SCENARIO_ID = "session-basic-loan";
    /** The session's id. */
    static final String SESSION_ID = "basic-loan-session";
    /** The exported scenario's description, the first line of its comment head. */
    static final String DESCRIPTION = "Exported from an interactive session on the sample desk (phase5-contracts.md"
            + " 14.4): a person signs on, a model lends one plain item to the walk-in borrower and settles the"
            + " deposit, the person checks the loan.";
    /** Who the person is in the journal. */
    static final Origin PERSON = Origin.person("tester");

    private SessionScript() {
    }

    /** What a run of the script left: the turn, the checks, both exports, the ledger and where the journal is. */
    record Result(Turn turn, StepOutcome signOn, List<CheckOutcome> checks, ExportResult export,
                  ExportResult exportFromFile, CostLedger ledger, Path journal, Path inferenceLog) {
    }

    /** {@code <dir>/session/}, where the journal goes. */
    static Path sessionDir(Path dir) {
        return dir.resolve("session");
    }

    /**
     * Runs the script on {@code desk} with {@code gateway} as the model, its outputs below {@code dir}: the journal in
     * {@code session/journal.jsonl}, the model calls' records in {@code session/inference.jsonl}. The live export and
     * the export of the journal read back from its file are both taken before the session closes.
     */
    static Result run(AgentConnection desk, Credentials credentials, InferenceGateway gateway, Path dir)
            throws Exception {
        CostLedger ledger = new CostLedger();
        Path out = sessionDir(dir);
        SessionOptions options = SessionOptions.defaults(out).withClock(ScriptedAnswers.CLOCK).withId(SESSION_ID)
                .withBind(BindOptions.defaults().withEnvId(Env.SEED_A.id).withCredentials(credentials)
                        .withLedger(ledger));
        try (ExecutorSession session = ExecutorSession.open(Runs.PACK, desk, options)) {
            session.resolve("assistant", "StaffWithPermission", ScriptedAnswers.input("perform", List.of("SIGN_ON"),
                    "interactiveSignOn", true), List.of(), List.of(), new CredentialSpec("staff", "code"), PERSON);
            StepOutcome signOn = session.execute("signOn", Map.of("staff", "$assistant"), PERSON);
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, null, "1.00", ledger);
            PromptMask mask = PromptMask.of(credentials.mask(), Runs.PACK.sqlAllowLists(), Runs.PACK.rowMasks());
            Path inferenceLog = out.resolve("inference.jsonl");
            LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.toFile(inferenceLog), mask, false,
                    ScriptedAnswers.CLOCK);
            Turn turn;
            try (SessionService service = new SessionService(session, gateway, SessionPolicy.acting(), meter, writer,
                    PriceTable.builtIn(), ScriptedAnswers.CLOCK)) {
                turn = service.ask(ScriptedAnswers.SESSION_REQUEST);
            }
            List<CheckOutcome> checks = List.of(session.check("loan.total", "$item.deposit", PERSON),
                    session.check("state.name", "IDLE", PERSON));
            ExportOptions export = ExportOptions.of(SCENARIO_ID).withDescription(DESCRIPTION);
            ExportResult live = session.journal().export(export);
            ExportResult fromFile = StepJournal.read(session.journal().file(), Runs.PACK).export(export);
            return new Result(turn, signOn, checks, live, fromFile, ledger, session.journal().file(), inferenceLog);
        }
    }

    /** The three files of an export, as {@code skillroy session export} writes them: YAML, plan, bindings. */
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

    /**
     * Writes an export's three files below {@code root} (the plan compiled from the YAML as written), the bindings as
     * the end-to-end agent channel writes them.
     */
    static Files3 write(ExportResult export, Path root) throws Exception {
        Files3 files = files(root);
        Files.createDirectories(files.plan().getParent());
        Files.createDirectories(files.bindings().getParent());
        Files.writeString(files.yaml(), export.yaml(), StandardCharsets.UTF_8);
        PlanCompiler.compileTo(files.yaml(), Runs.PACK, files.plan());
        Files.writeString(files.bindings(), ScenarioDRecordedTest.asTheAgentWritesIt(export.bindings().toJsonText()),
                StandardCharsets.UTF_8);
        return files;
    }

    /** The text of a file. */
    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
