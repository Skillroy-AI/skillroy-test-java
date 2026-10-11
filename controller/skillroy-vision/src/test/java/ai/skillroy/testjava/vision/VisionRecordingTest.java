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

import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.ExportResult;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.VisionBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.vision.VisionHarness.done;
import static ai.skillroy.testjava.vision.VisionHarness.member;
import static ai.skillroy.testjava.vision.VisionHarness.tools;
import static ai.skillroy.testjava.vision.VisionHarness.use;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 8, the recorded steps against the session's real bodies (VC's verbs, image route and
 * credential rule): a table-row mark recorded as {@code pointer {row}} and a region mark as {@code pointer {region}};
 * a point on a component without a locator recorded as an anchored {@code pointer} with its guard, which acts (the
 * recorder's own anchor, D361) and whose export carries the {@code images} entry and the anchor's PNG; V3 acting
 * through Robot with the focus rule left on; and, through the session, a model's {@code type} refused while a person's
 * credential is in flight, a model's {@code signOn} refused whatever the state, and typing given back after the
 * person's sign-on.
 */
class VisionRecordingTest {

    @TempDir
    Path dir;

    @Test
    void aRowMarkIsAPointerOnTheRowAndARegionMarkAPointerOnTheRegion() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "click_mark", Map.of("mark", 4L))),
                tools("r2", use("t2", "click_mark", Map.of("mark", 6L))),
                done("r3")))) {
            Turn turn = h.service.ask("select the second loan line, then press 1 on the number pad");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            List<Map<String, Object>> pointers = h.agent.bridge.callsTo("swing.pointer");
            assertEquals(2, pointers.size(), String.valueOf(pointers));
            Map<String, Object> row = pointers.get(0);
            assertEquals(Map.of("id", "loans"), row.get("by"));
            assertEquals(1L, row.get("row"), "the mark's row, the model index");
            assertEquals("CLICK", row.get("action"));
            Map<String, Object> region = pointers.get(1);
            assertEquals(Map.of("id", "numberPad"), region.get("by"));
            assertEquals("key:1", region.get("region"));
            assertTrue(h.agent.bridge.callsTo("swing.click").isEmpty(), "a row and a region are positional");
            List<StepJournal.StepEntry> steps = h.steps();
            assertEquals(List.of("pointer", "pointer"), steps.stream().map(StepJournal.StepEntry::verb).toList());
            assertTrue(steps.stream().allMatch(s -> s.status() == Status.PASS), String.valueOf(steps));
            assertEquals(4L, ((Map<?, ?>) steps.get(0).provenance().get("hitTest")).get("mark"));
        }
    }

    @Test
    void aPointWithoutALocatorIsAnAnchoredPointerWhoseExportCarriesItsImage() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(VisionOptions.TIER, "v2"), List.of(
                tools("r1", member("t1", "left_click", Map.of("coordinate", List.of(270L, 220L)))),
                done("r2")))) {
            Turn turn = h.service.ask("click the status strip");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            List<Map<String, Object>> pointers = h.agent.bridge.callsTo("swing.pointer");
            assertEquals(1, pointers.size(), "the guard let its pointer act: " + pointers);
            assertEquals(Map.of("name", "Desk", "role", "window"), pointers.get(0).get("by"), "by the window");
            List<Map<String, Object>> crops = h.agent.bridge.callsTo("swing.capture").stream()
                    .filter(c -> "REGION".equals(c.get("scope"))).toList();
            assertFalse(crops.isEmpty(), "the anchor was taken");
            assertTrue(crops.stream().allMatch(c -> Boolean.TRUE.equals(c.get("redact"))), "redacted");

            StepJournal.StepEntry step = h.steps().get(0);
            assertEquals("pointer", step.verb());
            assertEquals(Status.PASS, step.status(), String.valueOf(step));
            Map<?, ?> anchor = (Map<?, ?>) step.provenance().get("anchor");
            assertEquals("anchor-s1", anchor.get("label"));
            assertEquals(FakeVisionAgent.RENDER_KEY, anchor.get("renderKey"));

            ExportResult export = h.session.journal().export(ExportOptions.of("strip-click")
                    .withCaptures(h.session.options().outputDir()));
            assertTrue(export.yaml().contains("guard: anchor-s1"), export.yaml());
            assertNotNull(export.bindings(), "an anchor needs a bindings file");
            Map<String, BindingsFile.ImageEntry> byKey = export.bindings().images().get("anchor-s1");
            assertNotNull(byKey, String.valueOf(export.bindings().images()));
            assertEquals(java.util.Set.of(FakeVisionAgent.RENDER_KEY), byKey.keySet());
            byte[] png = export.images().get("anchor-s1");
            assertNotNull(png, "the anchor's PNG for the caller to place beside the bindings file");
            BufferedImage image = Grounding.decode(png);
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
        }
    }

    @Test
    void v3ActsThroughRobotWithTheFocusRuleOn() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(VisionOptions.TIER, "v3"), List.of(
                tools("r1", member("t1", "left_click", Map.of("coordinate", List.of(50L, 35L)))),
                done("r2")), SessionPolicy.acting(), true, true) {
            @Override
            void configure(FakeVisionAgent fake) {
                fake.bridge.capability("swing.pointer.robot", CapabilityState.OK, "robot");
            }
        }) {
            Turn turn = h.service.ask("ask for the deposit due");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            List<Map<String, Object>> pointers = h.agent.bridge.callsTo("swing.pointer");
            assertEquals(1, pointers.size(), String.valueOf(pointers));
            assertEquals("ROBOT", pointers.get(0).get("via"));
            assertNotEquals(false, pointers.get(0).get("requireFocus"), "the focus rule stays on (its default)");
            assertEquals(Map.of("action", "DUE"), pointers.get(0).get("by"));
            assertTrue(h.agent.bridge.callsTo("swing.click").isEmpty(), "V3 never records a routed click");
            assertEquals("ROBOT", h.steps().get(0).provenance().get("via"));
            for (Map<String, Object> c : h.agent.bridge.callsTo("swing.capture")) {
                assertNotEquals("ROBOT", c.get("mode"), "never a capture of the glass, even in V3");
            }
        }
    }

    @Test
    void theSessionRefusesAModelsTypeWhileACredentialIsInFlightAndAModelsSignOnAlways() throws Exception {
        Origin person = Origin.person("tester");
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "type", Map.of("text", "B2"))),
                done("r2"),
                tools("r3", use("t3", "toy_sign_on", Map.of("staff", "$assistant"))),
                done("r4"),
                tools("r5", use("t5", "type", Map.of("text", "B2"))),
                done("r6")))) {
            List<String> names = h.service.tools().stream().map(ToolSpec::name).toList();
            assertTrue(names.contains("toy_sign_on"), names.toString());
            VisionBridge.resolveItemAndAssistant(h.session, person);
            assertTrue(h.session.secretsInFlight(), "the person's resolve joined a secret");

            Turn inFlight = h.service.ask("type B2 into the entry field");
            Turn.Action typed = inFlight.actions().get(0);
            assertEquals(Recorder.IN_FLIGHT, typed.result(), String.valueOf(inFlight.actions()));
            assertTrue(h.agent.bridge.callsTo("swing.type").isEmpty(), "nothing was typed");

            Turn signOn = h.service.ask("sign the assistant on");
            Turn.Action verb = signOn.actions().get(0);
            assertTrue(verb.step() == null || verb.step().status() != Status.PASS, String.valueOf(verb));
            assertTrue(verb.result().contains("credential"), verb.result());
            assertTrue(h.session.secretsInFlight(), "still in flight: the model's sign-on did not run");

            assertEquals(Status.PASS, h.session.execute("signOn", Map.of("staff", "$assistant"), person).status());
            assertFalse(h.session.secretsInFlight(), "the person's sign-on consumed it");
            Turn after = h.service.ask("type B2 into the entry field");
            assertEquals(Turn.Outcome.DONE, after.outcome(), after.answer());
            assertEquals(1, h.agent.bridge.callsTo("swing.type").size(), "typing is the model's again");
            String journal = Files.readString(h.session.journal().file());
            assertFalse(journal.contains(VisionBridge.PASSWORD_100), "the journal holds no secret");
        }
    }
}
