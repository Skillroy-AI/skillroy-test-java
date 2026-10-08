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

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.recorded.RecordedGateway;
import ai.skillroy.testjava.inference.recorded.Recording;
import ai.skillroy.testjava.inference.recorded.RecordingGateway;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.CheckOutcome;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ScenarioSchema;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session acceptance without a display (phase5-contracts.md 14.4, 15.1 items 13 and 14 on the sample): the
 * session of {@link SessionScript} on the sample desk in this JVM ({@link HeadlessDesk}, seed A) with the model's
 * answers from the committed recordings: a person signs on through the {@code signOn} verb, the model lends one plain
 * item to the walk-in borrower and settles the deposit through {@code SessionService.ask} (a resolve and three verbs,
 * then {@code done}), the person checks the loan through the pack's facts, and the journal is exported as
 * {@value SessionScript#SCENARIO_ID}. The export's YAML, plan and bindings file equal the committed copies under
 * {@code scenarios/exported/} byte for byte (the bindings apart from the channel, as for every committed file); the
 * journal read back from its file exports the same; the YAML is valid against the pack's scenario schema, compiles,
 * and replays on a fresh desk with zero model calls.
 *
 * <p>{@code -Dskillroy.inference.fixtures.update=true} first regenerates the session's recordings from the scripted
 * stand-in answers ({@link ScriptedAnswers#sessionScript()} behind a {@code RecordingGateway}, the old recordings of
 * purpose {@code session} removed) and the committed copies of the export.
 */
class SessionExportTest {

    /**
     * {@code -Dskillroy.inference.fixtures.update=true}: the recordings of the session's model calls and the committed
     * export, from one run of the script with the scripted stand-in answers.
     */
    @BeforeAll
    static void regenerateWhenAsked(@TempDir Path dir) throws Exception {
        if (!ScriptedAnswers.update()) {
            return;
        }
        Path recordings = ScriptedAnswers.recordings();
        Files.createDirectories(recordings);
        try (Stream<Path> files = Files.list(recordings)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                if ("session".equals(Recording.read(file).purpose())) {
                    Files.delete(file);
                }
            }
        }
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A);
             RecordingGateway gateway = new RecordingGateway(ScriptedGateway.of(ScriptedAnswers.sessionScript()),
                     recordings, PromptMask.keysOnly(), true, ScriptedAnswers.CLOCK)) {
            SessionScript.Result result = SessionScript.run(desk, desk.credentials(dir), gateway, dir);
            assertEquals(Turn.Outcome.DONE, result.turn().outcome(), result.turn().answer());
            SessionScript.write(result.export(), ItEnv.moduleDir().resolve("scenarios").resolve("exported"));
        }
    }

    @Test
    void theRecordedSessionExportsTheCommittedScenarioWhichReplaysWithZeroModelCalls(@TempDir Path dir)
            throws Exception {
        SessionScript.Result result;
        RecordedGateway recorded = new RecordedGateway(ScriptedAnswers.recordings());
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A)) {
            result = SessionScript.run(desk, desk.credentials(dir.resolve("credentials")), recorded, dir);
            assertEquals(Status.PASS, result.signOn().status(), result.signOn().message());
            assertEquals("IDLE", desk.status().state().name(), "the loan was posted; the desk waits for the next");
        }

        // The model's turn: four actions through the session, each a step or a resolve, then done.
        Turn turn = result.turn();
        assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
        assertEquals(ScriptedAnswers.SESSION_ANSWER, turn.answer());
        assertEquals(List.of("skillroy_resolve", "lending_scan", "lending_key", "lending_deposit"), turn.actions()
                .stream().map(Turn.Action::tool).toList());
        for (Turn.Action action : turn.actions().subList(1, 4)) {
            assertEquals(Status.PASS, action.step().status(), action.tool() + ": " + action.result());
        }
        assertEquals(5, result.ledger().size(), "five model calls, all from the recordings: "
                + result.ledger().entries());
        assertTrue(result.ledger().entries().stream().allMatch(e -> "recorded".equals(e.provider())
                && "0".equals(e.costUsd()) && "session".equals(e.purpose())), String.valueOf(result.ledger()
                .entries()));
        assertEquals(5L, Files.readAllLines(result.inferenceLog()).stream().filter(l -> !l.isBlank()).count(),
                "one inference.jsonl record per call");
        for (CheckOutcome check : result.checks()) {
            assertTrue(check.held(), check.path() + ": " + check.reason());
        }

        // The journal: the person's steps and the model's, by origin; read back, it exports the same.
        List<StepJournal.StepEntry> steps = StepJournal.read(result.journal(), Runs.PACK).entries().stream()
                .filter(e -> e instanceof StepJournal.StepEntry).map(e -> (StepJournal.StepEntry) e).toList();
        assertEquals(List.of("signOn", "scan", "key", "deposit"), steps.stream().map(StepJournal.StepEntry::verb)
                .toList());
        assertEquals("PERSON", steps.get(0).origin().kind().name());
        assertTrue(steps.subList(1, 4).stream().allMatch(s -> "MODEL".equals(s.origin().kind().name())
                && ScriptedGateway.MODEL.equals(s.origin().by())), "the model acted, by its id");
        assertEquals(result.export().yaml(), result.exportFromFile().yaml(), "the export is a function of the journal");
        assertEquals(result.export().bindings().toJsonText(), result.exportFromFile().bindings().toJsonText());

        // The export: byte-identical to the committed copies, valid, compiled, no model needed to bind it.
        SessionScript.Files3 committed = SessionScript.committed();
        SessionScript.Files3 written = SessionScript.write(result.export(), dir.resolve("exported"));
        assertEquals(SessionScript.read(committed.yaml()), SessionScript.read(written.yaml()), "the YAML (regenerate"
                + " with -D" + ScriptedAnswers.UPDATE + "=true)");
        assertEquals(SessionScript.read(committed.plan()), SessionScript.read(written.plan()), "the plan");
        assertEquals(SessionScript.read(committed.bindings()), SessionScript.read(written.bindings()),
                "the bindings, apart from the channel");
        Plan plan = Plan.read(committed.plan());
        assertEquals("none", plan.inference(), "a session's tier (a) slots need no model to bind");
        assertEquals(SessionScript.read(committed.plan()), PlanCompiler.compile(committed.yaml(), Runs.PACK)
                .toJsonText(), "the committed plan is the committed YAML's");
        assertEquals(List.of(), ScenarioSchema.validate(ScenarioSchema.generate(Runs.PACK, List.of()),
                StrictYaml.read(Files.readAllBytes(committed.yaml())).root()), "valid against the pack's schema");
        assertEquals(List.of(), result.export().report().notHeld(), "every check held");
        assertEquals(List.of(), result.export().report().excluded(), "every step passed");
        String yaml = result.export().yaml();
        assertTrue(yaml.contains("$item") && yaml.contains("$assistant"), "bound values stay references:\n" + yaml);
        assertTrue(yaml.contains("Acted: person: 1 step, model " + ScriptedGateway.MODEL + ": 3 steps."), yaml);

        // The replay: a fresh desk, the committed export, no model at all.
        try (HeadlessDesk fresh = HeadlessDesk.launch(Env.SEED_A)) {
            CostLedger ledger = new CostLedger();
            Runs.Outcome replay = Runs.run(fresh, plan, dir.resolve("replay"), BindOptions.defaults()
                    .withEnvId(Env.SEED_A.id).withBindingsRoot(committed.bindings().getParent().getParent())
                    .withCredentials(fresh.credentials(dir.resolve("fresh-credentials"))).withLedger(ledger));
            Runs.assertPassedWithoutSleeping(replay);
            assertEquals(List.of(), replay.result().modelCalls(), "zero model calls");
            assertTrue(ledger.isEmpty());
            assertEquals(0L, Files.size(replay.dir().resolve("cost-ledger.jsonl")));
        }

        // No secret in what the session wrote: no password as a value (the matcher of the end-to-end scan, which also
        // reads escaped JSON and labelled blocks), no JDBC password anywhere.
        List<Path> outputs = new ArrayList<>(List.of(result.journal(), result.inferenceLog(), written.yaml(),
                written.plan(), written.bindings()));
        Set<String> passwords = Set.copyOf(SampleCredentials.SEED_A.values());
        for (Path file : outputs) {
            String text = Files.readString(file);
            assertFalse(text.contains(HeadlessDesk.readerPassword()), file + " holds the JDBC password");
            assertEquals(List.of(), SecretScan.leaks(file, passwords), file + " holds a staff password as a value");
        }
    }

    /**
     * The session's committed recordings (purpose {@code session}): five calls, each a scripted stand-in, which a live
     * session does not refresh (phase5-contracts.md 14.5). The directory's other recordings are not the session's:
     * scenario D's are live ({@code ScenarioDRecordedTest} checks them).
     */
    @Test
    void theSessionsRecordingsAreScriptedStandIns() throws Exception {
        List<Map<String, Object>> session = new ArrayList<>();
        try (Stream<Path> files = Files.list(ScriptedAnswers.recordings())) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> json = Json.parseObject(Files.readString(file));
                if ("session".equals(json.get("purpose"))) {
                    session.add(json);
                }
            }
        }
        assertEquals(5, session.size(), "one recording per model call of the session");
        for (Map<String, Object> json : session) {
            Map<?, ?> response = (Map<?, ?>) json.get("response");
            assertEquals(ScriptedGateway.PROVIDER, ((Map<?, ?>) response.get("usage")).get("provider"));
            assertEquals("TOOL_USE", response.get("stop"));
            assertFalse(((List<?>) ((Map<?, ?>) json.get("request")).get("tools")).isEmpty(), "the session's tools");
        }
    }
}
