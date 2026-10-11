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
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ProviderTool;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.VisionBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.vision.VisionHarness.done;
import static ai.skillroy.testjava.vision.VisionHarness.member;
import static ai.skillroy.testjava.vision.VisionHarness.tools;
import static ai.skillroy.testjava.vision.VisionHarness.use;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 8, the loop on {@link FakeVisionAgent} and a scripted model through the real session
 * service: a V1 turn (a mark grounded by a hit-test at its centre, recorded as {@code click} by the domain locator, the
 * provenance on the step, the captures file the raster as captured), the marks drawn on the controller's copy only, a
 * redacted web surface staying grey, the capture after a batch and skipped after the model's own capture, a batch
 * stopping at its first failure, the toolset's member calls, the refusals of a masked target and of a credential, the
 * stale-capture rule, {@code zoom} mapped to the topmost window, V3 stopping the turn without a focused window, and no
 * capture ever asked in {@code ROBOT} mode.
 */
class VisionLoopTest {

    @TempDir
    Path dir;

    private static Map<String, Object> click(Map<?, ?> params) {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) params;
        return p;
    }

    @Test
    void aV1TurnGroundsAMarkAndRecordsAClickByTheDomainLocator() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", Content.Text.of("I compute the deposit."), use("t1", "click_mark", Map.of("mark", 1L))),
                done("r2")))) {
            Turn turn = h.service.ask("compute the deposit due");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());

            List<Map<String, Object>> clicks = h.agent.bridge.callsTo("swing.click");
            assertEquals(1, clicks.size(), "the recorded step acted");
            assertEquals(Map.of("action", "DUE"), click(clicks.get(0)).get("by"), "by the domain locator");
            List<Map<String, Object>> hits = h.agent.bridge.callsTo("swing.hitTest");
            assertEquals(Map.of("x", 50L, "y", 35L), hits.get(0).get("point"), "at the mark's centre");
            assertEquals(Map.of("captureId", "cap-1"), hits.get(0).get("space"));

            StepJournal.StepEntry step = h.steps().get(0);
            assertEquals("click", step.verb());
            assertEquals(Origin.Kind.MODEL, step.origin().kind());
            Map<String, Object> provenance = step.provenance();
            assertEquals("V1", provenance.get("tier"));
            assertEquals("ROUTED", provenance.get("via"));
            Map<?, ?> hit = (Map<?, ?>) provenance.get("hitTest");
            assertEquals("h2", hit.get("ref"));
            assertEquals("button", hit.get("role"));
            assertEquals(1L, hit.get("mark"));
            assertEquals(Map.of("action", "DUE"), hit.get("locator"));
            Map<?, ?> capture = (Map<?, ?>) provenance.get("capture");
            assertEquals(FakeVisionAgent.hash(h.agent.desk), capture.get("hash"));
            assertEquals("captures/" + FakeVisionAgent.hash(h.agent.desk).substring(7) + ".png", capture.get("file"));
            Path file = h.session.options().outputDir().resolve(String.valueOf(capture.get("file")));
            assertArrayEquals(h.agent.desk, Files.readAllBytes(file), "the raster as captured, without marks");

            ModelRequest first = h.gateway.requests().get(0);
            assertEquals("vision", first.purpose());
            assertEquals(CacheHint.AUTO, first.cache());
            List<Content> opening = first.messages().get(0).parts();
            Content.Image image = assertInstanceOf(Content.Image.class, opening.get(1));
            assertFalse(java.util.Arrays.equals(h.agent.desk, image.bytes()), "the marks drawn on the model's copy");
            BufferedImage marked = Grounding.decode(image.bytes());
            assertEquals(Marks.BOX.getRGB(), marked.getRGB(21, 21), "mark 1's box at the DUE key's corner");
            String marksList = ((Content.Text) opening.get(2)).text();
            assertTrue(marksList.startsWith("<target-ui>") && marksList.contains("mark 1: button DUE\n"), marksList);
            assertTrue(marksList.contains("table row 1"), marksList);
            assertTrue(marksList.contains("region key:4"), marksList);
            List<String> names = h.service.tools().stream().map(ToolSpec::name).toList();
            assertTrue(names.containsAll(List.of("capture", "click_mark", "type", "key", "wait", "skillroy_look")),
                    names.toString());
            assertFalse(names.contains("click_at"), "V1 offers marks, not pixels");
            for (Map<String, Object> c : h.agent.bridge.callsTo("swing.capture")) {
                assertNotEquals("ROBOT", c.get("mode"), "never a capture of the glass (D341)");
                assertEquals(true, c.get("redact"));
            }
        }
    }

    @Test
    void aCaptureFollowsEveryBatchUnlessTheModelsOwnCaptureEndedIt() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "wait", Map.of())),
                tools("r2", use("t2", "capture", Map.of())),
                done("r3")))) {
            h.service.ask("look around");
            assertEquals(3, h.agent.bridge.callsTo("swing.capture").size(), "the turn's start, after the wait, the"
                    + " model's own capture; none after it");
            List<Content> afterWait = h.gateway.requests().get(1).messages().get(2).parts();
            assertInstanceOf(Content.Image.class, afterWait.get(1), "the batch's capture after its results");
            Content.ToolResult captured = (Content.ToolResult) h.gateway.requests().get(2).messages().get(4)
                    .parts().get(0);
            assertInstanceOf(Content.Image.class, captured.parts().get(0), "an image result");
            assertEquals(1, h.gateway.requests().get(2).messages().get(4).parts().size(), "no second capture");
        }
    }

    @Test
    void aRedactedWebSurfaceStaysGreyAndAnUnredactedOneShowsThePage() throws Exception {
        for (boolean redacted : new boolean[] {true, false}) {
            Path sub = dir.resolve("web-" + redacted);
            try (VisionHarness h = new VisionHarness(sub, Map.of(VisionOptions.TIER, "v2", VisionOptions.TOOLS,
                    "custom"), List.of(done("r1")))) {
                h.agent.webRedacted = redacted;
                h.service.ask("look");
                Content.Image image = (Content.Image) h.gateway.requests().get(0).messages().get(0).parts().get(1);
                int rgb = Grounding.decode(image.bytes()).getRGB(60, 220) & 0xFFFFFF;
                if (redacted) {
                    assertEquals(0x808080, rgb, "a redacted surface stays grey");
                    assertTrue(h.agent.bridge.callsTo("web.bitmap").isEmpty(), "its page is never read");
                } else {
                    assertEquals(0xC82828, rgb, "the page composited into the surface");
                }
            }
        }
    }

    @Test
    void aBatchStopsAtItsFirstFailure() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "click_mark", Map.of("mark", 99L)), use("t2", "click_mark", Map.of("mark", 1L))),
                done("r2")))) {
            Turn turn = h.service.ask("click");
            assertTrue(turn.actions().get(0).result().contains("no mark 99"), turn.actions().get(0).result());
            assertEquals(VisionSessionTools.NOT_EXECUTED, turn.actions().get(1).result());
            assertTrue(h.agent.bridge.callsTo("swing.click").isEmpty());
        }
    }

    @Test
    void aMaskedTargetTakesNoInputFromTheModel() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(VisionOptions.TIER, "v2", VisionOptions.TOOLS,
                "custom"), List.of(
                tools("r1", use("t1", "click_at", Map.of("x", 150L, "y", 35L))),
                tools("r2", use("t2", "type", Map.of("text", "1234"))),
                tools("r3", use("t3", "key", Map.of("key", "ENTER"))),
                done("r4")))) {
            h.agent.focusOwner = "h3";
            Turn turn = h.service.ask("sign on");
            for (int i = 0; i < 3; i++) {
                assertEquals(Recorder.MASKED, turn.actions().get(i).result(), turn.actions().get(i).tool());
            }
            assertTrue(h.agent.bridge.callsTo("swing.click").isEmpty());
            assertTrue(h.agent.bridge.callsTo("swing.type").isEmpty());
            assertTrue(h.agent.bridge.callsTo("swing.key").isEmpty());
            assertTrue(h.steps().isEmpty(), "nothing journaled");
        }
    }

    /**
     * L2-F2 (phase7-contracts.md 6.5 step 2, D358; fix wave FC): a browser surface a pack's mask names takes no click
     * either: the masked test comes before the web branch for every surface, so the page is never hit-tested and no page
     * text enters a step, the journal or the export; a surface the capture reports redacted is refused the same.
     */
    @Test
    void aMaskedOrRedactedBrowserSurfaceTakesNoClickAndItsPageIsNeverRead() throws Exception {
        for (String why : List.of("masked", "redacted")) {
            Path sub = dir.resolve("web-" + why);
            try (VisionHarness h = new VisionHarness(sub, Map.of(VisionOptions.TIER, "v2", VisionOptions.TOOLS,
                    "custom"), List.of(tools("r1", use("t1", "click_at", Map.of("x", 60L, "y", 220L))),
                    done("r2")))) {
                h.agent.webMasked = "masked".equals(why);
                h.agent.webRedacted = "redacted".equals(why);
                Turn turn = h.service.ask("borrow on the page");
                assertEquals(Recorder.MASKED, turn.actions().get(0).result(), why);
                assertTrue(h.agent.bridge.callsTo("web.hitTest").isEmpty(), why + ": the page is never read");
                assertTrue(h.agent.bridge.callsTo("web.dom.click").isEmpty(), why);
                assertTrue(h.agent.bridge.callsTo("web.pointer").isEmpty(), why);
                assertTrue(h.steps().isEmpty(), why + ": nothing journaled");
                assertFalse(Files.readString(h.session.journal().file()).contains("Borrow"), why + ": no page text");
            }
        }
    }

    /**
     * L1-F6 (phase7-contracts.md 6.3; fix wave FC): the {@code wait} tool settles for the session's step timeout, not a
     * fixed 5 s.
     */
    @Test
    void waitSettlesForTheSessionsStepTimeout() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(tools("r1", use("t1", "wait", Map.of())),
                done("r2")))) {
            h.service.ask("wait until the desk is quiet");
            List<Map<String, Object>> settles = h.agent.bridge.callsTo("swing.settle");
            assertEquals(1, settles.size());
            assertEquals(h.session.options().stepTimeout().toMillis(), settles.get(0).get("timeoutMs"),
                    "the session's step timeout");
        }
    }

    /**
     * LV-1 (phase7-contracts.md 9.1, 9.2, D342; fix wave FC): the person bound slots before the turn, and the turn's
     * first message names them after the person's block (their references, kinds and non-secret fields, never a value
     * or a secret), before the capture; the model's {@code $item.code} is filled in by the session when it types it.
     */
    @Test
    void theTurnNamesTheSlotsThePersonBoundAndTheirReferencesWork() throws Exception {
        Origin person = Origin.person("tester");
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "type", Map.of("text", "$item.code"))), done("r2")))) {
            VisionBridge.resolveItemAndAssistant(h.session, person);
            assertEquals(ai.skillroy.testjava.scenario.Status.PASS, h.session.execute("signOn",
                    Map.of("staff", "$assistant"), person).status());
            Turn turn = h.service.ask("enter the code of $item at the desk");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            List<Content> opening = h.gateway.requests().get(0).messages().get(0).parts();
            assertTrue(((Content.Text) opening.get(0)).text().startsWith("<person>"));
            String slots = ((Content.Text) opening.get(1)).text();
            assertTrue(slots.startsWith("<target-state>"), slots);
            assertTrue(slots.contains("$item") && slots.contains("$item.code") && slots.contains("$assistant"),
                    slots);
            assertFalse(slots.contains(VisionBridge.PASSWORD_100), "no secret: " + slots);
            assertFalse(slots.contains("password"), "no secret field's name: " + slots);
            assertInstanceOf(Content.Image.class, opening.get(2), "the capture after the slots");
            List<Map<String, Object>> typed = h.agent.bridge.callsTo("swing.type");
            assertEquals(1, typed.size(), String.valueOf(turn.actions()));
            assertNotEquals("$item.code", typed.get(0).get("text"), "the session filled the reference in");
            assertEquals("type", h.steps().get(1).verb());
        }
    }

    @Test
    void aTypeNamingOrHoldingACredentialIsRefused() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(
                tools("r1", use("t1", "type", Map.of("text", "$assistant.password"))),
                tools("r2", use("t2", "type", Map.of("text", VisionBridge.PASSWORD_100))),
                tools("r3", use("t3", "type", Map.of("text", "B2", "enter", true))),
                done("r4")))) {
            Turn turn = h.service.ask("type");
            assertEquals(Recorder.CREDENTIAL, turn.actions().get(0).result());
            assertEquals(Recorder.CREDENTIAL, turn.actions().get(1).result(), "a value the mask knows");
            assertEquals(1, h.agent.bridge.callsTo("swing.type").size(), "only the item code was typed");
            assertEquals("B2", h.agent.bridge.callsTo("swing.type").get(0).get("text"));
            StepJournal.StepEntry step = h.steps().get(0);
            assertEquals("type", step.verb());
            assertEquals(Map.of("id", "entry"), ((Map<?, ?>) step.provenance().get("hitTest")).get("locator"),
                    "at the focus owner's locator");
        }
    }

    /**
     * 6.4 (D305): a window event between the capture and the act (here published by the batch's {@code wait}) makes the
     * loop take a fresh capture and hit-test the same point on it: the same target acts; another target (a dialog that
     * opened beside the frame moved the composite's origin, so the point names another component) is refused with
     * nothing acted.
     */
    @Test
    void theStaleCaptureRuleActsOnTheSameTargetAndRefusesAnother() throws Exception {
        for (boolean dialogOpens : new boolean[] {false, true}) {
            Path sub = dir.resolve("stale-" + dialogOpens);
            // The same image point: on the fresh capture with the dialog the composite's origin moved 40 px left.
            int x = 50;
            int y = 35;
            try (VisionHarness h = new VisionHarness(sub, Map.of(VisionOptions.TIER, "v2", VisionOptions.TOOLS,
                    "custom"), List.of(tools("r1", use("t1", "wait", Map.of()), use("t2", "click_at", Map.of("x",
                    (long) x, "y", (long) y))), done("r2")))) {
                h.agent.bridge.on("swing.settle", params -> {
                    h.agent.dialog = dialogOpens;
                    h.agent.bridge.publish("window.opened", Map.of("window", dialogOpens ? "d1" : "h1"));
                    return Map.of("settled", true, "ms", 1L);
                });
                Turn turn = h.service.ask("act");
                List<Map<String, Object>> hits = h.agent.bridge.callsTo("swing.hitTest");
                assertEquals(2, hits.size(), "the point hit-tested on both captures");
                assertNotEquals(hits.get(0).get("space"), hits.get(1).get("space"), "on the fresh capture");
                if (dialogOpens) {
                    assertTrue(turn.actions().get(1).result().contains("the screen changed"),
                            turn.actions().get(1).result());
                    assertTrue(h.agent.bridge.callsTo("swing.click").isEmpty());
                    assertTrue(h.agent.bridge.callsTo("swing.pointer").isEmpty());
                } else {
                    assertTrue(turn.actions().get(1).result().startsWith("click: PASS"), turn.actions().get(1)
                            .result());
                    assertEquals(1, h.agent.bridge.callsTo("swing.click").size());
                }
            }
        }
    }

    @Test
    void theToolsetsMembersMapToTheRecordedStepsAndEchoTheToolsetName() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(VisionOptions.TIER, "v2"), List.of(
                tools("r1", member("t1", "left_click", Map.of("coordinate", List.of(50L, 35L))),
                        member("t2", "key", Map.of("text", "Return"))),
                done("r2")))) {
            assertTrue(h.service.tools().stream().noneMatch(t -> t.name().equals("click_at")), "the toolset, not"
                    + " the custom pixel tools");
            Turn turn = h.service.ask("compute");
            assertEquals(Turn.Outcome.DONE, turn.outcome(), turn.answer());
            ModelRequest first = h.gateway.requests().get(0);
            assertEquals(1, first.providerTools().size());
            ProviderTool toolset = first.providerTools().get(0);
            assertEquals("computer_toolset_20260801", toolset.type());
            assertEquals(Map.of("zoom", Map.of("enabled", false), "middle_click", Map.of("enabled", false)),
                    toolset.raw().get("configs"), "zoom off; the middle click never offered (security fix S110)");
            List<Content> results = h.gateway.requests().get(1).messages().get(2).parts();
            assertEquals("computer", ((Content.ToolResult) results.get(0)).toolsetName());
            assertEquals("computer", ((Content.ToolResult) results.get(1)).toolsetName());
            assertEquals(Map.of("action", "DUE"), h.agent.bridge.callsTo("swing.click").get(0).get("by"));
            assertEquals("ENTER", h.agent.bridge.callsTo("swing.key").get(0).get("key"), "Return as ENTER");
        }
    }

    @Test
    void zoomIsMappedToTheTopmostWindowAtTheRectanglesCentre() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(VisionOptions.TIER, "v2", VisionOptions.ZOOM, "true"),
                List.of(tools("r1", member("t1", "zoom", Map.of("region", List.of(20L, 80L, 100L, 130L)))),
                        done("r2")))) {
            h.agent.dialog = true;
            h.service.ask("look closer");
            Map<String, Object> zoom = h.agent.bridge.callsTo("swing.capture").get(1);
            assertEquals("d1", zoom.get("target"), "the dialog lies on top at the centre");
            assertEquals("REGION", zoom.get("scope"));
            assertEquals(Map.of("x", 20L, "y", 20L, "w", 80L, "h", 50L), zoom.get("region"),
                    "in the dialog's own coordinates");
            Content.ToolResult result = (Content.ToolResult) h.gateway.requests().get(1).messages().get(2).parts()
                    .get(0);
            assertInstanceOf(Content.Image.class, result.parts().get(0));
        }
    }

    @Test
    void v3StopsTheTurnWhenTheTargetHoldsNoFocusedWindow() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(VisionOptions.TIER, "v3"), List.of(
                tools("r1", member("t1", "type", Map.of("text", "B2")), member("t2", "left_mouse_down", Map.of()))),
                SessionPolicy.acting(), true, true)) {
            h.agent.focusOwner = null;
            Turn turn = h.service.ask("type the item");
            assertEquals(Turn.Outcome.QUESTION, turn.outcome());
            assertEquals(Recorder.FOCUS_LOST, turn.answer());
            assertTrue(h.agent.bridge.callsTo("swing.type").isEmpty());
            assertTrue(h.agent.bridge.callsTo("swing.pointer").isEmpty(), "no Robot call");
        }
    }

    @Test
    void v3AnswersTheButtonHalvesAsText() {
        ComputerToolsetAdapter adapter = new ComputerToolsetAdapter();
        ToolAdapter.Action down = adapter.map(member("t1", "left_mouse_down", Map.of()), VisionTier.V3);
        assertEquals(new ToolAdapter.Refused("not supported in V3; use left_click_drag"), down);
        assertInstanceOf(ToolAdapter.MouseButton.class, adapter.map(member("t1", "left_mouse_up", Map.of()),
                VisionTier.V2));
        assertNull(new CustomToolAdapter().toolsetName());
    }
}
