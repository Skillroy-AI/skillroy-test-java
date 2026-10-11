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

import ai.skillroy.testjava.scenario.image.ImageCompare;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 5, {@code JournalExportTest}'s Phase 7 part (6.6, decisions D309, D355, D356): a step
 * a vision tool chose is exported as its verb step with one comment that carries no capture hash, handle or mark; the
 * report lists it under "Vision-derived steps" with them, and a Robot step and a raw pointer call under "Live-only"
 * (left out of the steps); an anchored step's anchor becomes the {@code images} entry of the export's bindings file
 * and its PNG {@code images()}; without the capture file the export is refused naming the step.
 */
class Phase7ExportTest {

    private static final Origin PERSON = Origin.person("tester");
    private static final Origin MODEL = Origin.model("claude-sonnet-5-5", "lend one item", "req-9");
    private static final String KEY = "0123456789abcdef";

    @TempDir
    Path dir;

    private SessionFixture fx;

    @BeforeEach
    void fixture() {
        fx = new SessionFixture(dir.resolve("session"));
        fx.agent.value("swing.click", Map.of("rung", "ACTION", "fired", true));
        fx.agent.value("swing.pointer", Map.of("via", "ROUTED", "fired", true));
        fx.agent.describe("swing.click", true, "swing.click.action").describe("swing.pointer", true,
                "swing.pointer.routed");
    }

    private static Map<String, Object> provenance(String via) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tier", "ROBOT".equals(via) ? "V3" : "V1");
        p.put("via", via);
        p.put("intent", "lend one item to the walk-in borrower and settle the deposit, then wait for the next");
        p.put("model", "claude-sonnet-5-5");
        p.put("requestId", "req-9");
        p.put("capture", Map.of("hash", "sha256:" + "a".repeat(64), "width", 1024L, "height", 768L, "seq", 41L,
                "file", "captures/" + "a".repeat(64) + ".png"));
        p.put("point", Map.of("x", 412L, "y", 288L));
        p.put("hitTest", Map.of("ref", "h12", "name", "DUE", "className", "IconKey", "role", "button", "locator",
                Map.of("action", "DUE"), "mark", 7L));
        return p;
    }

    @Test
    void aVisionStepIsAVerbStepWithACommentAndTheReportCarriesTheRest() throws Exception {
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withRobot(true))) {
            session.execute("poke", null, PERSON);
            assertEquals(Status.PASS, session.execute("click", Map.of("by", Map.of("action", "DUE")), MODEL,
                    provenance("ROUTED")).status());
            assertEquals(Status.PASS, session.execute("pointer", Map.of("by", Map.of("action", "DUE"), "via",
                    "ROBOT"), MODEL, provenance("ROBOT")).status());
            assertEquals(Status.PASS, session.execute(PlannedCall.of("swing.pointer", Map.of("point", Map.of("x", 3L,
                    "y", 4L), "space", Map.of("captureId", "cap-1"), "action", "MOVE"), Sync.NONE), MODEL, null)
                    .status());
            ExportResult result = session.journal().export(ExportOptions.of("vision-steps"));
            String yaml = result.yaml();
            String click = yaml.lines().filter(l -> l.contains("click:")).findFirst().orElseThrow();
            assertTrue(click.endsWith("# aiAct \"lend one item to the walk-in borrower and settle the deposit, then wait"
                    + " for the\" by claude-sonnet-5-5: button \"DUE\" (IconKey)"), click);
            assertFalse(yaml.contains("sha256:"), "no capture hash in the export (D356)");
            assertFalse(yaml.contains("h12"), "no handle");
            assertFalse(yaml.contains("mark 7"), "no mark");
            assertFalse(yaml.contains("pointer:"), "the Robot step and the raw pointer are live-only");
            assertFalse(yaml.contains("swing.pointer"));
            assertEquals(1, result.report().visionDerived().size());
            String line = result.report().visionDerived().get(0);
            assertTrue(line.startsWith("s2 click { by: { action: DUE } }") && line.contains("sha256:" + "a".repeat(64))
                    && line.contains("hit-test h12, mark 7") && line.contains("point (412, 288)"), line);
            assertEquals(2, result.report().liveOnly().size(), String.valueOf(result.report().liveOnly()));
            assertTrue(result.report().liveOnly().get(0).contains("Robot"), result.report().liveOnly().get(0));
            assertTrue(result.report().liveOnly().get(1).contains("raw swing.pointer"));
            String text = result.report().text();
            assertTrue(text.contains("Vision-derived steps: review the locator and the rung") && text.contains(
                    "Live-only"), text);
            assertEquals(Map.of(), result.images());
            PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "vision-steps.yaml", fx.pack);
        }
    }

    @Test
    void anAnchoredStepsAnchorBecomesTheExportsImageEntryAndPng() throws Exception {
        BufferedImage crop = Phase7ExecutorTest.image(0x445566, 2, 2);
        String sha = ImageCompare.rasterHash(crop);
        Path out = dir.resolve("session");
        Files.createDirectories(out.resolve("captures"));
        byte[] png = ImageCompare.encodePng(crop);
        Files.write(out.resolve("captures").resolve(sha.substring(7) + ".png"), png);
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("label", "anchor-s1");
        anchor.put("rect", Map.of("x", 380L, "y", 256L, "w", 8L, "h", 6L));
        anchor.put("sha256", sha);
        anchor.put("file", "captures/" + sha.substring(7) + ".png");
        anchor.put("renderKey", KEY);
        anchor.put("rendering", Map.of("jdk", Map.of("specification", "25")));
        anchor.put("window", Map.of("size", Map.of("w", 1024L, "h", 768L), "insets", Map.of("top", 0L, "left", 0L)));
        anchor.put("masks", List.of(Map.of("x", 0L, "y", 0L, "w", 1L, "h", 1L)));
        anchor.put("carets", List.of());
        anchor.put("focus", Map.of("ownerLocator", Map.of("id", "entry"), "active", true));
        Map<String, Object> provenance = provenance("ROUTED");
        provenance.put("anchor", anchor);
        try (ExecutorSession session = fx.open()) {
            StepOutcome step = session.execute("pointer", Map.of("by", Map.of("name", "frame"), "at", Map.of("fx", 0.4,
                    "fy", 0.4), "guard", "anchor-s1"), MODEL, provenance);
            assertEquals(Status.PASS, step.status(), "the anchor was just recorded: the step acts");
            ExportResult result = session.journal().export(ExportOptions.of("anchored").withCaptures(out));
            BindingsFile.ImageEntry entry = result.bindings().images().get("anchor-s1").get(KEY);
            assertEquals(sha, entry.sha256());
            assertEquals(new BindingsFile.Anchor(380, 256), entry.anchor());
            assertEquals("anchored.images/anchor-s1." + KEY + ".png", entry.file());
            assertEquals(Map.of("id", "entry"), entry.focus().owner());
            assertEquals(List.of(new ImageCompare.Rect(0, 0, 1, 1)), entry.masks());
            assertEquals(2, result.bindings().bindingsVersion());
            assertArrayEquals(png, result.images().get("anchor-s1"));
            assertTrue(result.yaml().contains("guard: anchor-s1"), result.yaml());
            Plan plan = PlanCompiler.compile(result.yaml().getBytes(StandardCharsets.UTF_8), "anchored.yaml", fx.pack);
            assertEquals(List.of("anchor-s1"), plan.images());

            ExportException missing = assertThrows(ExportException.class, () -> session.journal().export(
                    ExportOptions.of("anchored")));
            assertTrue(missing.getMessage().contains("ran without an output directory"), missing.getMessage());
            Files.delete(out.resolve("captures").resolve(sha.substring(7) + ".png"));
            ExportException absent = assertThrows(ExportException.class, () -> session.journal().export(
                    ExportOptions.of("anchored").withCaptures(out)));
            assertEquals("s1", absent.stepId(), "the step is named");
        }
    }
}
