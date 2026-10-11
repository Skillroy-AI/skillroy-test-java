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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.ExportResult;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.vision.VisionHarness.done;
import static ai.skillroy.testjava.vision.VisionHarness.tools;
import static ai.skillroy.testjava.vision.VisionHarness.use;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 8, {@link ScenarioPlayer} on the toy pack with a scripted model, over the harness's
 * session and service: an {@code aiAct} turn that ends {@code done} passes, the person's {@code signOn} having run
 * before it; an {@code ask_person} in a non-interactive run fails {@code ASSERTION}; {@code aiExpect} holds on
 * {@code YES} (and on {@code NO} with {@code expect: NO}), fails {@code ASSERTION} against its expectation, and skips on
 * {@code UNSURE} (the run {@code DEGRADED}), each question one {@code vision} call through the service's meter; a step's
 * {@code tier: V3} without the person's flag is refused before anything runs; an {@code expectImage} goes through the
 * session's image route and is journaled under its verb; a fixture credential slot is bound through the session as the
 * person (D425) and its {@code signOn} runs before the model acts; the scenario's {@code verify} is evaluated after the
 * last step and a check that does not hold fails the play (fix wave FC, L1-F2); the export of a played session lists
 * the {@code aiExpect} steps it dropped and carries the {@code verify} as expectations (L1-S1).
 */
class ScenarioPlayerTest {

    private static final Origin PERSON = Origin.person("tester");
    private static final String HEAD = String.join("\n",
            "scenario: played",
            "pack: toy",
            "resolve:",
            "  item: { query: Item, args: { deposit: { between: [\"2.00\", \"50.00\"] } } }",
            "  assistant: { query: Staff, args: { limitBelow: $item.deposit }, credential: { realm: staff, key: code } }",
            "steps:",
            "  - signOn: { staff: $assistant }",
            "");

    @TempDir
    Path dir;

    private static byte[] scenario(String... steps) {
        return (HEAD + String.join("\n", steps) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static ScenarioPlayer player(VisionHarness h, Map<String, String> options, boolean robot) {
        return new ScenarioPlayer(h.session, h.service, h.tools, VisionOptions.from(options), robot,
                PromptMask.keysOnly().withImages(true), PERSON, null, null);
    }

    private static ModelResponse verdict(String verdict) {
        return ScriptedGateway.structured(Map.of("verdict", verdict, "evidence", List.of(), "explanation",
                "the loan table as seen"), VisionHarness.usage("q-" + verdict));
    }

    @Test
    void anAiActTurnThatEndsDonePassesAfterThePersonsSignOn() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "click_mark", Map.of("mark", 1L))), done("r2")))) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).play(scenario(
                    "  - aiAct: { intent: \"ask for the deposit due\" }"), "played.yaml");
            assertEquals(Status.PASS, result.status(), result.message() + " " + result.steps());
            assertEquals(List.of("signOn", "aiAct"), result.steps().stream().map(ScenarioPlayer.StepResult::verb)
                    .toList());
            List<StepJournal.StepEntry> steps = h.steps();
            assertEquals("signOn", steps.get(0).verb(), "the person signed on first");
            assertEquals(Origin.Kind.PERSON, steps.get(0).origin().kind());
            assertEquals("click", steps.get(1).verb());
            assertEquals(Origin.Kind.MODEL, steps.get(1).origin().kind());
            assertEquals(1, h.agent.bridge.callsTo("swing.click").size());
        }
    }

    @Test
    void aQuestionFromTheModelFailsANonInteractiveRun() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "ask_person", Map.of("question", "which item?")))))) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).play(scenario(
                    "  - aiAct: { intent: \"lend an item\" }"), "played.yaml");
            assertEquals(Status.FAIL, result.status());
            ScenarioPlayer.StepResult aiAct = result.steps().get(1);
            assertEquals(FailureKind.ASSERTION, aiAct.failureKind());
            assertTrue(aiAct.message().contains("which item?"), aiAct.message());
        }
    }

    @Test
    void aiExpectHoldsFailsOrSkipsOnTheVerdict() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(verdict("YES"), verdict("NO"),
                verdict("UNSURE")))) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).play(scenario(
                    "  - aiExpect: { question: \"the loan table shows one line\" }",
                    "  - aiExpect: { question: \"the loan table is empty\", expect: NO }",
                    "  - aiExpect: { question: \"the slip is printed\" }"), "played.yaml");
            assertEquals(Status.DEGRADED, result.status(), result.message() + " " + result.steps());
            assertEquals(List.of(Status.PASS, Status.PASS, Status.PASS, Status.DEGRADED), result.steps().stream()
                    .map(ScenarioPlayer.StepResult::status).toList());
            List<ModelRequest> questions = h.gateway.requests();
            assertEquals(3, questions.size());
            assertTrue(questions.stream().allMatch(q -> "vision".equals(q.purpose())), "one vision call each");
            assertEquals(3, h.ledger.size(), "each question metered and ledgered");
        }
        try (VisionHarness h = new VisionHarness(dir.resolve("no"), Map.of(), List.of(verdict("NO")))) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).play(scenario(
                    "  - aiExpect: { question: \"the loan table shows one line\" }"), "played.yaml");
            assertEquals(Status.FAIL, result.status());
            assertEquals(FailureKind.ASSERTION, result.steps().get(1).failureKind());
        }
    }

    @Test
    void aStepsTierV3WithoutThePersonsFlagIsRefusedBeforeAnythingRuns() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(VisionOptions.TIER, "v2"), List.of(done("r1")))) {
            int before = h.agent.bridge.stepMethods().size();
            ScenarioException e = assertThrows(ScenarioException.class, () -> player(h, Map.of(VisionOptions.TIER,
                    "v2"), false).play(scenario("  - aiAct: { intent: \"lend an item\", tier: V3 }"), "played.yaml"));
            assertTrue(e.getMessage().contains("--allow-robot"), e.getMessage());
            assertEquals(before, h.agent.bridge.stepMethods().size(), "nothing ran");
            assertTrue(h.gateway.requests().isEmpty());
        }
    }

    @Test
    void anExpectImageGoesThroughTheSessionsImageRoute() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of())) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).withBaselines(dir.resolve("bindings"), "env")
                    .play(scenario("  - expectImage: { label: deskPanel, by: { id: entry } }"), "played.yaml");
            // L3-F10: an empty bindings root holds no baseline for the rendering: recorded and skipped, never PASS.
            assertEquals(Status.DEGRADED, result.status(), result.message() + " " + result.steps());
            ScenarioPlayer.StepResult image = result.steps().get(1);
            assertEquals(Status.DEGRADED, image.status(), String.valueOf(image));
            assertTrue(String.valueOf(image.message()).contains("no baseline for rendering"), String.valueOf(image));
            assertEquals("expectImage", h.steps().get(1).verb(), "journaled under its verb");
            assertTrue(h.agent.bridge.callsTo("swing.capture").stream().anyMatch(c -> Boolean.TRUE.equals(
                    c.get("redact"))), "the step's capture was taken");
            assertTrue(h.gateway.requests().isEmpty(), "no model call");
        }
    }

    @Test
    void aFixtureCredentialSlotIsBoundAsThePersonAndSignsOnBeforeTheModelActs() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "click_mark", Map.of("mark", 1L))), done("r2")))) {
            byte[] yaml = String.join("\n",
                    "scenario: fixture-played",
                    "pack: toy",
                    "resolve:",
                    "  assistant: { fixture: { code: \"100\" }, credential: { realm: staff, key: code } }",
                    "steps:",
                    "  - signOn: { staff: $assistant }",
                    "  - aiAct: { intent: \"ask for the deposit due\" }",
                    "").getBytes(StandardCharsets.UTF_8);
            ScenarioPlayer.Result result = player(h, Map.of(), false).play(yaml, "fixture-played.yaml");
            assertEquals(Status.PASS, result.status(), result.message() + " " + result.steps());
            StepJournal.FixtureEntry bound = h.session.journal().entries().stream()
                    .filter(e -> e instanceof StepJournal.FixtureEntry).map(e -> (StepJournal.FixtureEntry) e)
                    .findFirst().orElseThrow();
            assertEquals("assistant", bound.slot());
            assertEquals(Origin.Kind.PERSON, bound.origin().kind(), "the player binds as the person");
            assertTrue(!h.session.secretsInFlight(), "the person's signOn consumed the secret");
            assertEquals(1, h.agent.bridge.callsTo("swing.click").size(), "then the model acted");
            String journal = java.nio.file.Files.readString(h.session.journal().file());
            assertTrue(!journal.contains(ai.skillroy.testjava.scenario.VisionBridge.PASSWORD_100), "no secret");
        }
    }

    /**
     * L1-F2 (phase7-contracts.md 6.8, fix wave FC): the scenario's {@code verify} is evaluated after the last step
     * through the session's checks, in file order, each journaled; one that does not hold fails the play
     * {@code FAIL / ASSERTION} although every step passed; when all hold the block is a passed {@code verify} step.
     */
    @Test
    void theVerifyBlockIsEvaluatedAfterTheLastStepAndACheckThatDoesNotHoldFailsThePlay() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of())) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).play((HEAD + "verify:\n"
                    + "  - { \"loan.total\": \"7.00\" }\n  - { \"loan.total\": \"9.99\" }\n")
                    .getBytes(StandardCharsets.UTF_8), "played.yaml");
            assertEquals(Status.FAIL, result.status(), result.message() + " " + result.steps());
            assertEquals(List.of("signOn", "verify"), result.steps().stream().map(ScenarioPlayer.StepResult::verb)
                    .toList());
            ScenarioPlayer.StepResult verify = result.steps().get(1);
            assertEquals(Status.FAIL, verify.status());
            assertEquals(FailureKind.ASSERTION, verify.failureKind());
            assertTrue(verify.message().contains("loan.total") && verify.message().contains("9.99"),
                    verify.message());
            assertEquals(2L, h.session.journal().entries().stream().filter(e -> e instanceof StepJournal.CheckEntry)
                    .count(), "each verify check journaled, in file order");
        }
        try (VisionHarness h = new VisionHarness(dir.resolve("held"), Map.of(), List.of())) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).play((HEAD + "verify:\n"
                    + "  - { \"loan.total\": \"7.00\" }\n").getBytes(StandardCharsets.UTF_8), "played.yaml");
            assertEquals(Status.PASS, result.status(), result.message() + " " + result.steps());
            assertEquals(List.of(Status.PASS, Status.PASS), result.steps().stream()
                    .map(ScenarioPlayer.StepResult::status).toList());
        }
    }

    /**
     * L1-S1 (phase7-contracts.md 6.8, fix wave FC): the export of a played session lists each {@code aiExpect} it
     * dropped (its line, its question) under "Live-only" in the report, and carries the scenario's {@code verify} as
     * expectations after the last step.
     */
    @Test
    void aPlayedExportListsTheDroppedVisualChecksAndCarriesTheVerify() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(verdict("YES")))) {
            ScenarioPlayer.Result result = player(h, Map.of(), false).play((HEAD
                    + "  - aiExpect: { question: \"the loan table shows one line\" }\n"
                    + "verify:\n  - { \"loan.total\": \"7.00\" }\n").getBytes(StandardCharsets.UTF_8),
                    "played.yaml");
            assertEquals(Status.PASS, result.status(), result.message() + " " + result.steps());
            ExportResult export = result.annotate(h.session.journal().export(ExportOptions.of("played-export")));
            List<String> liveOnly = export.report().liveOnly();
            assertTrue(liveOnly.stream().anyMatch(l -> l.contains("aiExpect") && l.contains("line 8")
                    && l.contains("the loan table shows one line")), String.valueOf(liveOnly));
            assertTrue(export.report().text().contains("the loan table shows one line"), export.report().text());
            assertFalse(export.yaml().contains("aiExpect"), "dropped from the deterministic form: " + export.yaml());
            assertTrue(export.yaml().contains("loan.total"), "the verify carried as an expectation: " + export.yaml());
        }
    }
}
