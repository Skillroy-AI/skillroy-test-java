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
 * person (D-VM-6) and its {@code signOn} runs before the model acts.
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
            assertTrue(result.status() == Status.PASS || result.status() == Status.DEGRADED, result.message() + " "
                    + result.steps());
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
}
