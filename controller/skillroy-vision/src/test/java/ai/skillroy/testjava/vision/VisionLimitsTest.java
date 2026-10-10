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

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionActions;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.vision.VisionHarness.done;
import static ai.skillroy.testjava.vision.VisionHarness.tools;
import static ai.skillroy.testjava.vision.VisionHarness.use;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 10 and 13.1 item 8: the vision limits (30 UI actions, 20 model turns per session) enforced by the
 * service on this module's tools, a session that does not apply the tools (images off, Haiku asked for V2), and the
 * credential refusals that need a credential in flight (the recorder on a stub facade).
 */
class VisionLimitsTest {

    @TempDir
    Path dir;

    @Test
    void thirtyUiActionsPerSession() throws Exception {
        List<Content> clicks = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            clicks.add(use("t" + i, "click_mark", Map.of("mark", 1L)));
        }
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(tools("r1", clicks.toArray(new Content[0])),
                done("r2")))) {
            Turn turn = h.service.ask("click the key thirty-one times");
            assertEquals(Turn.Outcome.LIMIT, turn.outcome(), turn.answer());
            assertTrue(turn.answer().contains("limit of 30 UI actions"), turn.answer());
            assertEquals(30, h.agent.bridge.callsTo("swing.click").size(), "the thirty-first was refused");
            assertTrue(h.service.tools().stream().noneMatch(t -> t.name().equals("click_mark")), "the tools closed");
        }
    }

    @Test
    void twentyModelTurnsPerSession() throws Exception {
        List<ModelResponse> answers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            answers.add(tools("r" + i, use("t" + i, "wait", Map.of())));
        }
        try (VisionHarness h = new VisionHarness(dir, Map.of(), answers)) {
            Turn turn = h.service.ask("wait a lot");
            assertEquals(Turn.Outcome.LIMIT, turn.outcome(), turn.answer());
            assertTrue(turn.answer().contains("limit of 20 model turns"), turn.answer());
            assertEquals(20, h.gateway.requests().size());
        }
    }

    @Test
    void theToolsDoNotApplyWithoutImagesOrWithHaikuForV2() throws Exception {
        try (VisionHarness h = new VisionHarness(dir.resolve("a"), Map.of(), List.of(done("r1")),
                SessionPolicy.acting(), false, false)) {
            assertTrue(h.service.toolSets().isEmpty(), "images: false");
        }
        try (VisionHarness h = new VisionHarness(dir.resolve("b"), Map.of(VisionOptions.TIER, "v2",
                VisionOptions.MODEL, "haiku"), List.of(done("r1")))) {
            assertTrue(h.service.toolSets().isEmpty(), "Haiku is V1-only (the host refuses it first)");
        }
        try (VisionHarness h = new VisionHarness(dir.resolve("c"), Map.of(VisionOptions.TIER, "v3"),
                List.of(done("r1")))) {
            assertTrue(h.service.toolSets().isEmpty(), "v3 without the person's robot flag");
        }
        try (VisionHarness h = new VisionHarness(dir.resolve("d"), Map.of(VisionOptions.TIER, "off"),
                List.of(done("r1")))) {
            assertTrue(h.service.toolSets().isEmpty());
        }
        try (VisionHarness h = new VisionHarness(dir.resolve("e"), Map.of(), List.of(done("r1")))) {
            assertEquals(List.of("vision"), h.service.toolSets());
        }
    }

    /** A facade with a credential in flight and a focus owner: nothing reaches it. */
    private static final class InFlight implements SessionActions {

        final List<String> executed = new ArrayList<>();

        @Override
        public StepOutcome execute(String verb, Object args, Map<String, Object> provenance) {
            executed.add(verb);
            throw new AssertionError("nothing may run");
        }

        @Override
        public StepOutcome execute(PlannedCall call, Map<String, Object> provenance) {
            executed.add(call.primitive());
            throw new AssertionError("nothing may run");
        }

        @Override
        public Object read(String primitive, Map<String, Object> args) {
            return switch (primitive) {
                case "swing.edtState" -> Map.of("focusOwner", "h4");
                case "swing.hitTest" -> Map.of("target", "h4", "locators", List.of(Map.of("id", "entry")),
                        "masked", false);
                default -> Map.of();
            };
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            throw new AssertionError("no model call");
        }

        @Override
        public CapabilityMatrix capabilities() {
            return new CapabilityMatrix(Map.of());
        }

        @Override
        public String snapshotText(int maxChars) {
            return "";
        }

        @Override
        public List<Event> eventsSince(long seq, String topics, int max) {
            return List.of();
        }

        @Override
        public boolean secretsInFlight() {
            return true;
        }

        @Override
        public Path outputDir() {
            return null;
        }

        @Override
        public String screenLegend() {
            return null;
        }

        @Override
        public void note(String text) {
        }
    }

    @Test
    void noTypeRunsWhileACredentialIsInFlight() throws Exception {
        InFlight facade = new InFlight();
        Recorder recorder = new Recorder(facade, PromptMask.keysOnly(), Captures.none(), VisionTier.V1);
        assertEquals(Recorder.IN_FLIGHT, recorder.type("B2", false).text());
        assertTrue(recorder.type("B2", true).error());
        assertTrue(facade.executed.isEmpty(), "nothing ran");
        assertTrue(recorder.namesSecret("sign on as $staff.password now"));
        assertFalse(recorder.namesSecret("$item.code"), "a non-secret field");
    }
}
