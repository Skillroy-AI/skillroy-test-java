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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 acceptance 15.1 item 13, the journal and export part (phase5-contracts.md 12.3, decisions D80, D86): the
 * export rules one by one (verb steps as given, raw calls as {@code call} steps with bound values written back as
 * references and a masked argument refused, observed expectations marked and listed, failed steps excluded and
 * included, tier (a) and tier (b) slots in the bindings file, the head, the compile check), the export of a journal
 * read back from its file equal to the live export, and an exported scenario replaying with zero model calls.
 */
class StepJournalTest {

    @TempDir
    Path dir;

    private SessionFixture fx;

    private static final Origin PERSON = Origin.person("tester");
    private static final Origin MODEL = Origin.model("a-model", "lend the item", "req-7");

    @BeforeEach
    void fixture() {
        fx = new SessionFixture(dir.resolve("session"));
    }

    /**
     * The session most tests export: an item and an assistant resolved, an oracle, a sign-on, a model's entry of the
     * item, two checks, a post and a wait that captures the posted loan.
     */
    private ExecutorSession loanSession() throws Exception {
        ExecutorSession session = fx.open();
        SessionFixture.resolveItemAndAssistant(session, PERSON);
        session.evaluate("neg", "Negate", List.of("$item.deposit"), PERSON);
        assertEquals(Status.PASS, session.execute("signOn", Map.of("staff", "$assistant"), PERSON).status());
        assertEquals(Status.PASS, session.execute(PlannedCall.of("toy.input", Map.of("source", "Entry", "value",
                "B2"), Sync.READY), MODEL).status());
        session.check("state.name", "LOAN", PERSON);
        session.check("loan.lines[code=$item.code].qty", 2, PERSON);
        assertEquals(Status.PASS, session.execute("enter", Map.of("value", "POST"), PERSON).status());
        StepOutcome wait = session.execute("waitFor", Map.of("topic", "loan.posted", "within", "2s", "capture",
                Map.of("slot", "loan", "fields", Map.of("id", "loanId"))), PERSON);
        assertEquals(Status.PASS, wait.status(), wait.message());
        assertEquals(Map.of("loan", Map.of("id", 41L)), wait.detail().get("captured"));
        return session;
    }

    private Map<String, Object> yamlOf(ExportResult result) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) StrictYaml.read(result.yaml().getBytes(
                StandardCharsets.UTF_8)).root();
        return root;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Map<String, Object> yaml) {
        return (List<Map<String, Object>>) yaml.get("steps");
    }

    // ------------------------------------------------------------------------------------------------ 1. steps

    @Test
    void verbStepsAreWrittenAsGivenAndRawCallsAsCallStepsWithReferencesWrittenBack() throws Exception {
        try (ExecutorSession session = loanSession()) {
            ExportResult result = session.journal().export(ExportOptions.of("toy-loan-session"));
            Map<String, Object> yaml = yamlOf(result);
            List<Map<String, Object>> steps = steps(yaml);
            assertEquals(Map.of("signOn", Map.of("staff", "$assistant")), steps.get(0), "the verb as given");
            assertEquals(Map.of("call", Map.of("primitive", "toy.input", "args", Map.of("source", "Entry", "value",
                    "$item.code"), "sync", "READY")), steps.get(1), "B2 is the bound item's code: written back");
            assertEquals(Map.of("expect", Map.of("state.name", "LOAN", "loan.lines[code=$item.code].qty", 2L)),
                    steps.get(2), "the checks that held, as given, after their step");
            assertEquals(Map.of("enter", Map.of("value", "POST")), steps.get(3));
            assertEquals("waitFor", steps.get(4).keySet().iterator().next());
            assertEquals(5, steps.size());
            assertEquals(5, result.report().steps());
            assertEquals("none", result.report().inference());
        }
    }

    @Test
    void aLiteralWithADollarIsWrittenAsALiteral() throws Exception {
        try (ExecutorSession session = fx.open()) {
            StepOutcome sent = session.execute(PlannedCall.of("toy.read", Map.of("note", "costs $$5 or $$item"),
                    Sync.NONE), PERSON);
            assertEquals(Status.PASS, sent.status(), sent.message());
            assertEquals("costs $5 or $item", fx.agent.callsTo("toy.read").get(0).params().get("note"));
            ExportResult result = session.journal().export(ExportOptions.of("dollars"));
            assertTrue(result.yaml().contains("\"costs $$5 or $$item\""), result.yaml());
            Plan plan = PlanCompiler.compile(result.yaml().getBytes(StandardCharsets.UTF_8), "dollars.yaml", fx.pack);
            assertEquals(Map.of("note", "costs $$5 or $$item"), plan.steps().get(0).calls().get(0).args());
            assertEquals("costs $5 or $item", Bindings.empty().substitute("costs $$5 or $$item"),
                    "and it substitutes back to the value that was sent");
        }
    }

    @Test
    void aMaskedArgumentIsRefusedNamingTheStepUnlessItCameFromAReference() throws Exception {
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            session.execute(PlannedCall.of("toy.input", Map.of("source", "Entry", "value", "$assistant.password"),
                    Sync.READY), PERSON);
            ExportResult fine = session.journal().export(ExportOptions.of("by-reference"));
            assertTrue(fine.yaml().contains("value: $assistant.password"), fine.yaml());
            session.execute(PlannedCall.of("toy.input", Map.of("source", "Entry", "value", TestCredentials.PW_100),
                    Sync.READY), PERSON);
            ExportException e = assertThrows(ExportException.class, () -> session.journal().export(
                    ExportOptions.of("typed-secret")));
            assertEquals("s2", e.stepId());
            assertTrue(e.getMessage().contains("argument 'value' of toy.input was masked"), e.getMessage());
        }
    }

    @Test
    void aStepThatDidNotPassIsLeftOutUnlessIncludedAndThenNothingAfterIt() throws Exception {
        try (ExecutorSession session = fx.open()) {
            session.execute("enter", Map.of("value", "B2"), PERSON);
            session.execute("enter", Map.of("value", "UNKNOWN"), PERSON);
            session.check("state.name", "LOAN", PERSON);
            session.execute("answer", Map.of("choice", "OK"), PERSON);
            ExportResult passed = session.journal().export(ExportOptions.of("passed"));
            List<Map<String, Object>> steps = steps(yamlOf(passed));
            assertEquals(List.of("enter", "expect", "answer"), steps.stream().map(s -> s.keySet().iterator().next())
                    .toList());
            assertEquals(1, passed.report().excluded().size());
            assertTrue(passed.report().excluded().get(0).startsWith("s2 (enter): FAIL"), passed.report().excluded()
                    .toString());
            ExportResult included = session.journal().export(ExportOptions.of("included").withIncludeFailed(true));
            List<Map<String, Object>> withFailure = steps(yamlOf(included));
            assertEquals(List.of(Map.of("enter", Map.of("value", "B2")), Map.of("enter", Map.of("value",
                    "UNKNOWN"))), withFailure, "up to and including the first failure");
            assertTrue(included.report().excluded().get(0).contains("after the first failure"));
        }
    }

    // ------------------------------------------------------------------------------------------------ 2. expect

    @Test
    void anObservedValueIsAnExpectationMarkedInTheYamlAndListedInTheReport() throws Exception {
        try (ExecutorSession session = fx.open()) {
            session.execute("enter", Map.of("value", "B2"), PERSON);
            session.observe("loan.total", PERSON);
            session.observe("loan.lines[code=B2]", PERSON);
            session.check("loan.total", Map.of("gte", 100), PERSON);
            ExportResult result = session.journal().export(ExportOptions.of("observed"));
            assertTrue(result.yaml().contains("\n      loan.total: \"7.00\"    # observed in session sess-1 at step 1,"
                    + " not derived\n"), result.yaml());
            Map<String, Object> expect = (Map<String, Object>) steps(yamlOf(result)).get(1).get("expect");
            assertEquals(Map.of("equals", Map.of("code", "B2", "qty", 2L)), expect.get("loan.lines[code=B2]"),
                    "a mapping is compared whole");
            assertEquals(List.of("\"loan.total\": \"7.00\" (step 1)",
                    "\"loan.lines[code=B2]\": { equals: { code: B2, qty: 2 } } (step 1)"), result.report().observed());
            assertEquals(1, result.report().notHeld().size(), "a check that did not hold is not exported");
            assertTrue(result.report().text().contains("Observed expectations: derive or confirm"));
        }
    }

    // ------------------------------------------------------------------------------------------------ 3. bindings

    @Test
    void theBindingsFileHoldsTheResolutionsAsApproveWouldWriteThem() throws Exception {
        try (ExecutorSession session = loanSession()) {
            ExportResult result = session.journal().export(ExportOptions.of("toy-loan-session"));
            Map<String, Object> resolve = (Map<String, Object>) yamlOf(result).get("resolve");
            assertEquals(Map.of("query", "Staff", "args", Map.of("limitBelow", "$item.deposit"), "credential",
                    Map.of("realm", "staff", "key", "code")), resolve.get("assistant"), "kind, args as given");
            BindingsFile file = result.bindings();
            assertNotNull(file);
            assertEquals(List.of("item", "assistant"), List.copyOf(file.resolutions().keySet()));
            Resolution assistant = file.resolutions().get("assistant");
            assertEquals(Approval.State.NOT_REQUIRED, assistant.approval().state(), "tier (a)");
            assertEquals("live", assistant.approval().by());
            assertEquals(Map.of("code", "100", "limit", "2.00"), assistant.value());
            assertEquals("-5.00", file.oracles().get("neg").value());
            assertEquals("seed-a", file.envId());
            assertEquals("toy-loan-session", file.scenarioId());
            Plan plan = PlanCompiler.compile(result.yaml().getBytes(StandardCharsets.UTF_8), "x.yaml", fx.pack);
            assertEquals(plan.resolveHash(), file.resolveHash());
            assertEquals(plan.hash(), file.planHash());
            assertEquals(file.toJsonText(), BindingsFile.parse(file.toJsonText()).toJsonText(), "canonical");
            ExportResult approved = session.journal().export(ExportOptions.of("toy-loan-session")
                    .withApprovedBy("reviewer"));
            assertEquals("reviewer", approved.bindings().resolutions().get("item").approval().by());
        }
    }

    /**
     * A tier (b) slot (the strategy the inference module's resolver records): {@code APPROVED} by the exporter when
     * one is named, else {@code REQUIRED}; its tokens name {@code llm-sql}, so the plan says {@code inference: bind}.
     */
    @Test
    void aTierBSlotIsRequiredOrApprovedByTheExporterAndThePlanBinds() throws Exception {
        fx.pack.sqlAllowLists = List.of(Phase5CompileTest.TOY_ALLOW_LIST);
        Path file;
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            file = session.journal().file();
        }
        // The engine of this module has no model resolver: a model's resolution is recorded as the journal would.
        String text = Files.readString(file, StandardCharsets.UTF_8).replaceFirst(
                "\"strategy\":\"CANONICAL_SQL\"", "\"strategy\":\"LLM_SQL\"");
        Path edited = dir.resolve("tier-b.jsonl");
        Files.writeString(edited, text, StandardCharsets.UTF_8);
        StepJournal journal = StepJournal.read(edited, fx.pack);
        ExportResult approved = journal.export(ExportOptions.of("tier-b").withApprovedBy("reviewer"));
        Resolution item = approved.bindings().resolutions().get("item");
        assertEquals(Strategy.LLM_SQL, item.strategy());
        assertEquals(new Approval(Approval.State.APPROVED, "reviewer"), item.approval());
        assertEquals(Approval.State.NOT_REQUIRED, approved.bindings().resolutions().get("assistant").approval()
                .state(), "the tier (a) slot beside it");
        assertEquals(List.of("sql", "llm-sql"), ((Map<?, ?>) ((Map<?, ?>) yamlOf(approved).get("resolve"))
                .get("item")).get("strategy"), "the pack's tokens and llm-sql");
        assertEquals("bind", approved.report().inference());
        boolean required = Arrays.stream(Approval.State.values()).anyMatch(s -> s.name().equals("REQUIRED"));
        if (required) {
            ExportResult pending = journal.export(ExportOptions.of("tier-b"));
            assertEquals("REQUIRED", pending.bindings().resolutions().get("item").approval().state().name());
            assertEquals("live", pending.bindings().resolutions().get("item").approval().by(), "as live writes it");
        } else {
            // E2: Approval.State.REQUIRED arrives with the approval states of 7.1; until then the export says so.
            ExportException e = assertThrows(ExportException.class, () -> journal.export(ExportOptions.of("tier-b")));
            assertTrue(e.getMessage().contains("REQUIRED"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ 4. head

    @Test
    void theHeadCarriesTheSessionAndTheTimeoutsUiAndWeb() throws Exception {
        fx.agent.describe("toy.input", true, "toy.input");
        fx.agent.describe("toy.poke", true, "toy.poke");
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options()
                .withStepTimeout(java.time.Duration.ofSeconds(30)).withUi(new UiSettings("COMPONENT", true)))) {
            session.execute("enter", Map.of("value", "B2"), PERSON);
            session.execute("poke", null, MODEL);
            session.execute("poke", null, MODEL);
            ExportResult result = session.journal().export(ExportOptions.of("head").withDescription("Lend B2."));
            List<String> lines = result.yaml().lines().toList();
            assertEquals("# Lend B2.", lines.get(0));
            assertTrue(lines.get(1).startsWith("# Session sess-1 on 2026-"), lines.get(1));
            assertEquals("# Acted: person: 1 step, model a-model: 2 steps.", lines.get(2));
            assertEquals("# Model cost: none recorded.", lines.get(3));
            Map<String, Object> yaml = yamlOf(result);
            assertEquals("head", yaml.get("scenario"));
            assertEquals("toy", yaml.get("pack"));
            assertEquals(List.of("toy.input", "toy.poke"), yaml.get("requires"));
            assertEquals(Map.of("step", "30s"), yaml.get("timeouts"));
            assertEquals(Map.of("clickRung", "COMPONENT", "strict", true), yaml.get("ui"));
            assertNull(yaml.get("web"), "no web key without web settings");
            ExportResult plain = session.journal().export(ExportOptions.of("head"));
            assertTrue(plain.yaml().startsWith("# Exported from session sess-1 on 2026-"), plain.yaml());
        }
    }

    // ------------------------------------------------------------------------------------------------ 5. check

    @Test
    void anExportThatDoesNotCompileOrResolveIsRefusedNamingTheStep() throws Exception {
        Path file;
        try (ExecutorSession session = fx.open()) {
            session.execute("enter", Map.of("value", "B2"), PERSON);
            session.execute("poke", null, PERSON);
            file = session.journal().file();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path ghost = dir.resolve("ghost.jsonl");
        Files.writeString(ghost, text.replace("\"args\":{\"value\":\"B2\"}", "\"args\":{\"value\":\"$ghost.code\"}"),
                StandardCharsets.UTF_8);
        ExportException unresolved = assertThrows(ExportException.class, () -> StepJournal.read(ghost, fx.pack)
                .export(ExportOptions.of("ghost")));
        assertEquals("s1", unresolved.stepId());
        assertTrue(unresolved.getMessage().contains("unknown slot 'ghost'"), unresolved.getMessage());
        Path renamed = dir.resolve("renamed.jsonl");
        Files.writeString(renamed, text.replace("\"verb\":\"poke\"", "\"verb\":\"prod\""), StandardCharsets.UTF_8);
        ExportException uncompiled = assertThrows(ExportException.class, () -> StepJournal.read(renamed, fx.pack)
                .export(ExportOptions.of("renamed")));
        assertEquals("s2", uncompiled.stepId());
        assertTrue(uncompiled.getMessage().contains("unknown verb 'prod'"), uncompiled.getMessage());
    }

    // ------------------------------------------------------------------------------------------------ read back

    @Test
    void aJournalReadBackFromItsFileExportsWhatTheLiveJournalExports() throws Exception {
        ExecutorSession session = loanSession();
        session.observe("loan.total", PERSON);
        session.close();
        ExportResult live = session.journal().export(ExportOptions.of("same").withApprovedBy("reviewer"));
        Path file = session.journal().file();
        assertTrue(live.yaml().contains("# Model cost: $0."), "the session's end records its cost");
        ExportResult read = StepJournal.read(file, fx.pack).export(ExportOptions.of("same")
                .withApprovedBy("reviewer"));
        assertEquals(live.yaml(), read.yaml());
        assertEquals(live.bindings().toJsonText(), read.bindings().toJsonText());
        assertEquals(live.report(), read.report());
        Path broken = dir.resolve("broken.jsonl");
        Files.writeString(broken, "{\"type\":\"session-start\",\"id\":\"x\"}\n{\"type\":\"teleport\"}\n");
        assertThrows(java.io.IOException.class, () -> StepJournal.read(broken, fx.pack));
    }

    /** The point of the export: the scenario replays with the exported bindings and makes no model call. */
    @Test
    void theExportReplaysWithZeroModelCalls() throws Exception {
        ExportResult result;
        try (ExecutorSession session = loanSession()) {
            result = session.journal().export(ExportOptions.of("toy-loan-session").withApprovedBy("reviewer"));
        }
        Path root = dir.resolve("bindings");
        result.bindings().write(BindingsFile.locate(root, "seed-a", "toy-loan-session"));
        Plan plan = PlanCompiler.compile(result.yaml().getBytes(StandardCharsets.UTF_8), "toy-loan-session.yaml",
                fx.pack);
        assertEquals("none", plan.inference());
        fx.agent.on("toy.input", params -> {
            fx.agent.publish("input.acked", Map.of("source", params.get("source")));
            fx.agent.publish("input.requested", Map.of("state", "LOAN"));
            fx.agent.publish("loan.posted", Map.of("loanId", 42L));
            return Map.of("accepted", true);
        });
        fx.state.put("name", "LOAN");
        RunOptions options = RunOptions.defaults().withClock(fx.clock).withSleeper(fx.clock.sleeper())
                .withBind(TestCredentials.bind().withMode(BindMode.REPLAY).withEnvId("seed-a").withBindingsRoot(root));
        RunResult run = new PlanExecutor(fx.pack, fx.agent, options).run(plan);
        assertEquals(Status.PASS, run.status(), run.message() + "\n" + result.yaml());
        assertEquals(List.of(), run.modelCalls(), "replay made no model call");
    }

    /**
     * A raw call of a primitive that changes nothing (by the target's catalogue, which the journal's
     * {@code session-start} keeps as {@code reads}) is an observation, not a step: left out and listed in the report
     * unless {@code includeReads}; a mutating raw call and every verb step are kept; a journal read back from its file
     * exports the same, and a journal without {@code reads} (written before the field) keeps every raw call.
     */
    @Test
    void aRawCallOfAReadIsAnObservationLeftOutUnlessReadsAreIncluded() throws Exception {
        fx.agent.describe("toy.read", false, "toy.read").describe("toy.poke", true, "toy.poke");
        Path file;
        try (ExecutorSession session = fx.open()) {
            assertEquals(Status.PASS, session.execute(PlannedCall.of("toy.read", Map.of("x", 1), Sync.NONE), PERSON)
                    .status());
            assertEquals(Status.PASS, session.execute(PlannedCall.of("toy.poke", Map.of("n", 1), Sync.NONE), PERSON)
                    .status());
            assertEquals(Status.PASS, session.execute("enter", Map.of("value", "B2"), PERSON).status());
            file = session.journal().file();
        }
        StepJournal journal = StepJournal.read(file, fx.pack);
        StepJournal.SessionStart start = (StepJournal.SessionStart) journal.entries().get(0);
        assertEquals(List.of("toy.read"), start.reads(), "the catalogue's reads, kept in the journal");

        ExportResult plain = journal.export(ExportOptions.of("no-reads"));
        List<Map<String, Object>> steps = steps(yamlOf(plain));
        assertEquals(List.of("call", "enter"), steps.stream().map(s -> s.keySet().iterator().next()).toList());
        assertEquals("toy.poke", ((Map<?, ?>) steps.get(0).get("call")).get("primitive"), "the mutating call stays");
        assertEquals(List.of("toy.poke"), yamlOf(plain).get("requires"), "a read left out requires nothing");
        assertEquals(1, plain.report().excluded().size(), plain.report().excluded().toString());
        assertTrue(plain.report().excluded().get(0).startsWith("s1 (call): PASS; a read of toy.read is an"
                + " observation, not a step"), plain.report().excluded().get(0));

        ExportResult reads = journal.export(ExportOptions.of("with-reads").withIncludeReads(true));
        assertEquals(List.of("call", "call", "enter"), steps(yamlOf(reads)).stream()
                .map(s -> s.keySet().iterator().next()).toList());
        assertEquals("toy.read", ((Map<?, ?>) steps(yamlOf(reads)).get(0).get("call")).get("primitive"));
        assertTrue(reads.report().excluded().isEmpty(), reads.report().excluded().toString());

        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path old = dir.resolve("before-reads.jsonl");
        Files.writeString(old, text.replace(",\"reads\":[\"toy.read\"]", ""), StandardCharsets.UTF_8);
        assertNotEquals(text, Files.readString(old, StandardCharsets.UTF_8), "the field was taken out");
        assertEquals(3, steps(yamlOf(StepJournal.read(old, fx.pack).export(ExportOptions.of("old")))).size(),
                "a journal without reads keeps every raw call");
    }

    @Test
    void exportOptionsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> ExportOptions.of("Not Valid"));
        assertThrows(IllegalArgumentException.class, () -> ExportOptions.of("ok").withApprovedBy(" "));
        assertFalse(ExportOptions.of("ok").includeFailed());
        assertFalse(ExportOptions.of("ok").includeReads(), "reads are left out by default");
        assertTrue(ExportOptions.of("ok").withIncludeReads(true).withApprovedBy("x").includeReads());
        assertFalse(new ExportOptions("ok", true, null, null).includeReads(), "the earlier constructor");
        assertEquals("1500ms", JournalExport.duration(1500));
        assertEquals("2m", JournalExport.duration(120_000));
        assertEquals("10s", JournalExport.duration(10_000));
    }
}
