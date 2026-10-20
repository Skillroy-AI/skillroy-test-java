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

import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 5, the compiler and plan parts (8.1, 8.2, decisions D310, D325, D343): the four verbs
 * compile to their shapes, their argument rules hold, a plan is written as version 6 (7 since phase8-contracts.md
 * 8.2) with {@code inference: run}, {@code images} and a call's {@code guard}, and the reader refuses that content in a
 * version 5 plan.
 */
class Phase7CompileTest {

    private final ToyPack pack = new ToyPack();

    private Plan compile(String steps) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n" + steps).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
    }

    private PlanCompiler.Compiled withNotes(String steps) throws ScenarioException {
        return PlanCompiler.compileWithNotes(ScenarioLoader.load(("scenario: t\npack: toy\nsteps:\n" + steps)
                .getBytes(StandardCharsets.UTF_8), "t.yaml"), pack);
    }

    private String refusal(String steps) {
        return assertThrows(ScenarioException.class, () -> compile(steps)).reason();
    }

    @Test
    void theFourVerbsAreBaseVerbsAndStayReservedForAPack() {
        assertEquals(18, BaseSteps.VERBS.size());
        assertTrue(BaseSteps.VERBS.containsAll(List.of("aiAct", "aiExpect", "pointer", "expectImage")));
        assertTrue(PlanCompiler.RESERVED_VERBS.containsAll(List.of("aiAct", "aiExpect", "pointer", "expectImage")),
                "a pack may not take them");
        assertTrue(BaseSteps.UI_VERBS.contains("pointer"), "fired: false fails a pointer step (A29)");
    }

    @Test
    void aiActCompilesToNoCallAndMakesThePlanRun() throws Exception {
        Plan plan = compile("  - aiAct: { intent: \"lend one item\", tier: V2, model: sonnet, maxActions: 12 }\n");
        assertEquals(List.of(), plan.steps().get(0).calls());
        assertEquals(Plan.INFERENCE_RUN, plan.inference());
        assertEquals(Plan.VERSION, plan.planVersion());
        assertEquals(7, Plan.VERSION);
        assertEquals(Plan.INFERENCE_RUN, Plan.inferenceOf(plan.slots(), plan.steps()));
        assertEquals(Plan.INFERENCE_NONE, Plan.inferenceOf(plan.slots()), "the slots alone say none");
        assertFalse(plan.requires().contains("swing.pointer.robot"));
        PlanCompiler.Compiled v3 = withNotes("  - aiAct: { intent: x, tier: V3 }\n");
        assertTrue(v3.plan().requires().contains("swing.pointer.robot"), "tier V3 needs Robot");
        assertTrue(v3.notes().stream().anyMatch(n -> n.contains("swing.pointer.robot")), String.valueOf(v3.notes()));
        assertTrue(refusal("  - aiAct: { intent: x, tier: V4 }\n").contains("'tier' must be V1, V2 or V3"));
        assertTrue(refusal("  - aiAct: { intent: x, model: gpt }\n").contains("'model' must be opus, sonnet or haiku"));
        assertTrue(refusal("  - aiAct: { intent: x, maxActions: 31 }\n").contains("from 1 to 30"));
        assertTrue(refusal("  - aiAct: { intent: \"\" }\n").contains("'intent'"));
    }

    @Test
    void aiExpectCompilesToAVisualCheckWithTheFixedGuard() throws Exception {
        Plan plan = compile("  - aiExpect: { question: \"the loan table shows one line\" }\n"
                + "  - aiExpect: { question: \"a prompt is open\", expect: NO }\n");
        assertEquals(List.of(), plan.steps().get(0).calls());
        assertEquals(new VisualCheck("the loan table shows one line", "YES", "inference.vision"),
                plan.steps().get(0).expect().get(0));
        assertEquals("NO", ((VisualCheck) plan.steps().get(1).expect().get(0)).expect());
        assertEquals(Plan.INFERENCE_NONE, plan.inference(), "a visual check is no model at run time in a plan");
        assertEquals(Map.of("visual", Map.of("question", "a prompt is open", "expect", "NO"), "capability",
                "inference.vision"), plan.steps().get(1).expect().get(0).toJson());
        assertTrue(refusal("  - aiExpect: { question: q, capability: lending.loan }\n")
                .contains("aiExpect is always guarded by inference.vision"));
        assertTrue(refusal("  - aiExpect: { question: q, expect: MAYBE }\n").contains("YES or NO"));
        assertEquals(plan.toJsonText(), Plan.parse(plan.toJsonText()).toJsonText(), "reads back");
    }

    @Test
    void pointerCompilesToTheComponentFormOfSwingPointer() throws Exception {
        Plan plan = compile("""
                  - pointer: { by: { id: numberPad }, region: "key:7" }
                  - pointer: { target: h4, row: 3, at: { fx: 0.25, fy: 0.5 }, action: DOUBLE_CLICK, button: RIGHT }
                  - pointer: { by: { name: list }, action: DRAG, at: { fx: 0.1, fy: 0.1 }, to: { fx: 0.9, fy: 0.9 },
                               steps: 12 }
                  - pointer: { by: { name: slip }, action: WHEEL, wheel: { notches: -3 } }
                  - pointer: { by: { action: DUE }, await: { topic: input.requested } }
                """);
        Plan.Call region = plan.steps().get(0).calls().get(0);
        assertEquals("swing.pointer", region.primitive());
        assertEquals(Map.of("by", Map.of("id", "numberPad"), "region", "key:7", "action", "CLICK"), region.args());
        assertEquals(Sync.SETTLE, region.sync());
        assertNull(region.guard());
        assertEquals(Map.of("target", "h4", "row", 3L, "at", Map.of("fx", 0.25, "fy", 0.5), "action", "DOUBLE_CLICK",
                "button", "RIGHT"), plan.steps().get(1).calls().get(0).args());
        assertEquals(12L, plan.steps().get(2).calls().get(0).args().get("steps"));
        assertEquals(Map.of("notches", -3L), plan.steps().get(3).calls().get(0).args().get("wheel"));
        List<Plan.Call> awaited = plan.steps().get(4).calls();
        assertEquals(Sync.EVENT, awaited.get(0).sync(), "await: EVENT then swing.settle, as uiCalls does");
        assertEquals("swing.settle", awaited.get(1).primitive());
        assertEquals(List.of(), plan.requires(), "a routed pointer adds no requirement");
        assertTrue(refusal("  - pointer: { region: \"key:1\" }\n").contains("exactly one of 'by'"));
        assertTrue(refusal("  - pointer: { by: { id: p }, region: { x: 1 } }\n").contains("region id"));
        assertTrue(refusal("  - pointer: { by: { id: p }, action: DRAG }\n").contains("DRAG needs 'to"));
        assertTrue(refusal("  - pointer: { by: { id: p }, to: { fx: 1, fy: 1 } }\n")
                .contains("'to' needs action DRAG"));
        assertTrue(refusal("  - pointer: { by: { id: p }, at: { fx: 2, fy: 0 } }\n").contains("fraction"));
        assertTrue(refusal("  - pointer: { by: { id: p }, action: WHEEL, wheel: { notches: 51 } }\n")
                .contains("-50 to 50"));
        assertTrue(refusal("  - pointer: { by: { id: p }, row: 1, region: \"key:1\" }\n").contains("not both"));
        assertTrue(refusal("  - pointer: { by: { id: p }, expectTarget: h3 }\n").contains("unknown argument"),
                "expectTarget is no argument: a ref cannot be committed");
    }

    @Test
    void viaRobotNeedsTheRobotCapabilityAndAPressIsNeverLeftHeld() throws Exception {
        PlanCompiler.Compiled robot = withNotes("  - pointer: { by: { action: DUE }, via: ROBOT }\n");
        assertEquals("ROBOT", robot.plan().steps().get(0).calls().get(0).args().get("via"));
        assertTrue(robot.plan().requires().contains("swing.pointer.robot"));
        assertTrue(robot.notes().stream().anyMatch(n -> n.contains("swing.pointer.robot")));
        assertTrue(robot.plan().usesRobot());
        for (String action : List.of("PRESS", "RELEASE")) {
            assertTrue(refusal("  - pointer: { by: { id: p }, action: " + action + ", via: ROBOT }\n")
                    .contains("a Robot press is never left held"), action);
        }
        assertFalse(compile("  - pointer: { by: { id: p }, action: PRESS }\n").usesRobot(), "a routed press is fine");
    }

    @Test
    void guardMakesAnAnchoredStepAndNamesItsLabelInImages() throws Exception {
        Plan plan = compile("  - pointer: { by: { name: frame }, at: { fx: 0.5, fy: 0.5 }, guard: anchor-s3 }\n");
        Plan.Call call = plan.steps().get(0).calls().get(0);
        assertEquals(new Plan.Guard("anchor-s3"), call.guard());
        assertFalse(call.args().containsKey("guard"), "the label is on the call, not among the primitive's arguments");
        assertEquals(Map.of("label", "anchor-s3"), call.toJson().get("guard"));
        assertEquals(List.of("anchor-s3"), plan.images());
        assertTrue(plan.needsBindings(), "a plan with images reads and writes its bindings file (D355)");
        assertEquals(plan.toJsonText(), Plan.parse(plan.toJsonText()).toJsonText(), "reads back");
        assertTrue(refusal("  - pointer: { by: { id: p }, guard: \"a/b\" }\n").contains("label"));
    }

    @Test
    void expectImageCompilesToOneCaptureAndAnImageCheck() throws Exception {
        Plan plan = compile("""
                  - expectImage: { label: pad, by: { id: numberPad } }
                  - expectImage: { label: key7, by: { id: numberPad }, region: "key:7", maxDiffPixels: 4 }
                  - expectImage: { label: corner, clip: { x: 0, y: 0, w: 64, h: 32 },
                                   mask: [ { by: { id: entry } }, { x: 1, y: 2, w: 3, h: 4 } ] }
                """);
        Plan.Step first = plan.steps().get(0);
        assertEquals(1, first.calls().size());
        assertEquals("swing.capture", first.calls().get(0).primitive());
        assertEquals(Map.of("by", Map.of("id", "numberPad"), "redact", true), first.calls().get(0).args());
        assertEquals(Sync.NONE, first.calls().get(0).sync());
        assertEquals(new ImageCheck("pad", List.of(), 0), first.expect().get(0));
        assertEquals(Map.of("by", Map.of("id", "numberPad"), "scope", "REGION", "region", "key:7", "redact", true),
                plan.steps().get(1).calls().get(0).args());
        assertEquals(4, ((ImageCheck) plan.steps().get(1).expect().get(0)).maxDiffPixels());
        assertEquals(Map.of("scope", "REGION", "region", Map.of("x", 0L, "y", 0L, "w", 64L, "h", 32L), "redact", true),
                plan.steps().get(2).calls().get(0).args(), "clip is a rectangle on the wire (D297)");
        assertEquals(2, ((ImageCheck) plan.steps().get(2).expect().get(0)).masks().size());
        assertEquals(List.of("corner", "key7", "pad"), plan.images(), "sorted");
        assertTrue(plan.needsBindings());
        assertEquals(plan.toJsonText(), Plan.parse(plan.toJsonText()).toJsonText(), "reads back");
        assertTrue(refusal("  - expectImage: { label: a, region: \"key:1\", clip: { x: 0, y: 0, w: 1, h: 1 } }\n")
                .contains("not both"));
        assertTrue(refusal("  - expectImage: { label: a, by: { id: x }, target: h1 }\n").contains("not both"));
        assertTrue(refusal("  - expectImage: { label: \"../a\" }\n").contains("'label' must match"));
        assertTrue(refusal("  - expectImage: { label: a, maxDiffPixels: 10001 }\n").contains("0 to 10000"));
        assertTrue(refusal("  - expectImage: { label: a, mask: [ { x: 1 } ] }\n").contains("rectangle mask"));
        assertTrue(refusal("  - expectImage: { label: a }\n  - expectImage: { label: a }\n").contains("used twice"));
        assertTrue(refusal("  - expectImage: { label: a }\n  - pointer: { by: { id: p }, guard: a }\n")
                .contains("used twice"), "one label per baseline or anchor");
    }

    @Test
    void theReaderRefusesVersion6ContentBelowVersion6() throws Exception {
        String run = compile("  - aiAct: { intent: x }\n").toJsonText();
        assertTrue(reason(run.replace("\"planVersion\": 7", "\"planVersion\": 5")).contains("inference 'run' needs a"
                + " version 6 plan"));
        String images = compile("  - expectImage: { label: a }\n").toJsonText();
        assertTrue(reason(images.replace("\"planVersion\": 7", "\"planVersion\": 5")).contains("'images' needs a"
                + " version 6 plan"));
        String guard = compile("  - pointer: { by: { id: p }, guard: g }\n").toJsonText()
                .replace("\"planVersion\": 7", "\"planVersion\": 5").replace(",\n  \"images\": [\n    \"g\"\n  ]",
                        "");
        assertTrue(reason(guard).contains("needs a version 6 plan"), reason(guard));
        String visual = compile("  - aiExpect: { question: q }\n").toJsonText();
        assertTrue(reason(visual.replace("\"planVersion\": 7", "\"planVersion\": 5")).contains("needs a version 6"
                + " plan"));
        String pointer = compile("  - pointer: { by: { id: p } }\n").toJsonText();
        assertTrue(reason(pointer.replace("\"planVersion\": 7", "\"planVersion\": 5")).contains("the verb 'pointer'"
                + " needs a version 6 plan"));
        assertTrue(reason(run.replace("\"inference\": \"run\"", "\"inference\": \"none\"")).contains("a step's verb is"
                + " aiAct"));
        assertTrue(reason(images.replace("\"images\": [\n    \"a\"\n  ],\n", "")).contains("'images' must list"));
        assertEquals(Plan.parse(pointer).toJsonText(), pointer, "version 7 accepted");
        assertTrue(reason(images.replace("\"label\": \"a\",", "\"label\": \"b\",")).contains("'images' must list"));
    }

    @Test
    void aPlanWithoutPhase7ContentIsWrittenAsBeforeButForTheVersion() throws Exception {
        Plan plan = compile("  - poke: {}\n");
        String text = plan.toJsonText();
        assertFalse(text.contains("\"images\""), "absent when empty, as web is");
        assertFalse(text.contains("\"guard\""));
        assertTrue(text.startsWith("{\n  \"planVersion\": 7,\n"), text);
        assertFalse(plan.needsBindings());
        assertEquals(List.of(), plan.images());
    }

    private static String reason(String planText) {
        return assertThrows(ScenarioException.class, () -> Plan.parse(planText)).reason();
    }

    @Test
    void theCheckRecordsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new VisualCheck("q", "YES", "lending.loan"));
        assertThrows(IllegalArgumentException.class, () -> new VisualCheck(" ", "YES"));
        assertThrows(IllegalArgumentException.class, () -> new ImageCheck("a b", List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new ImageCheck("a", List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> new Plan.Call("swing.click", Map.of(), Sync.SETTLE, 1000,
                null, false, null, new Plan.Guard("g")), "a guard on swing.pointer only");
        assertInstanceOf(Check.class, new ImageCheck("a", List.of(), 0));
        assertNull(new ImageCheck("a", List.of(), 0).capability(), "an image check is unguarded");
    }

    // ------------------------------------------------------------------------------------ the security fix round

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Plan plan) {
        return (Map<String, Object>) ai.skillroy.testjava.protocol.Json.parseObject(plan.toJsonText());
    }

    private static String readerRefusal(Map<String, Object> json) {
        return assertThrows(ScenarioException.class, () -> Plan.parse(ai.skillroy.testjava.protocol.Json.writePretty(
                json))).getMessage();
    }

    /**
     * Phase 7 security fix, S118 (phase7-contracts.md 8.1, 8.2): image labels are unique ignoring case, since a label
     * names a file and two labels equal ignoring case share one on a case-insensitive volume; the compiler and the plan
     * reader refuse them (the old code compiled {@code Pad} and {@code pad} and read them back).
     */
    @Test
    void imageLabelsAreUniqueIgnoringCase() throws Exception {
        String twin = refusal("  - expectImage: { label: Pad }\n  - expectImage: { label: pad }\n");
        assertTrue(twin.contains("the image label 'pad' differs from 'Pad' only in case; a label names a file"), twin);
        assertTrue(refusal("  - expectImage: { label: Pad }\n  - pointer: { by: { name: f }, guard: PAD }\n")
                .contains("only in case"), "a guard's label counts alike");
        String text = compile("  - expectImage: { label: Pad }\n  - expectImage: { label: pax }\n").toJsonText()
                .replace("\"pax\"", "\"pad\"");
        String read = assertThrows(ScenarioException.class, () -> Plan.parse(text)).getMessage();
        assertTrue(read.contains("differs from another label only in case"), read);
    }

    /**
     * Phase 7 security fix, S119 (phase7-contracts.md 8.2): the plan reader is as strict as the compiler for two
     * Phase 7 constructs: a guard on a cleanup call (sent without its guard, so never evaluated) and more than 64 masks
     * on an image check are refused; the {@code ImageCheck} constructor bounds the masks on every construction path.
     * The old reader accepted both.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theReaderRefusesACleanupGuardAndTooManyMasks() throws Exception {
        Map<String, Object> guarded = json(compile("  - pointer: { by: { name: frame }, guard: anchor-s1 }\n"));
        Object call = ((List<Map<String, Object>>) ((List<Map<String, Object>>) guarded.get("steps")).get(0)
                .get("calls")).get(0);
        guarded.put("cleanup", List.of(call));
        assertTrue(readerRefusal(guarded).contains("cleanup: a guard is allowed on a step's swing.pointer only"),
                readerRefusal(guarded));

        Map<String, Object> masked = json(compile("  - expectImage: { label: pad }\n"));
        Map<String, Object> image = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) ((List<Map<String,
                Object>>) masked.get("steps")).get(0).get("expect")).get(0)).get("image");
        List<Object> masks = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) {
            masks.add(Map.of("x", 0L, "y", 0L, "w", 1L, "h", 1L));
        }
        image.put("masks", masks);
        Plan.parse(ai.skillroy.testjava.protocol.Json.writePretty(masked));
        masks.add(Map.of("x", 0L, "y", 0L, "w", 1L, "h", 1L));
        image.put("masks", masks);
        assertTrue(readerRefusal(masked).contains("at most 64 masks, found 65"), readerRefusal(masked));
        IllegalArgumentException bound = assertThrows(IllegalArgumentException.class, () -> new ImageCheck("pad",
                masks, 0));
        assertTrue(bound.getMessage().contains("at most 64 masks"), bound.getMessage());
    }
}
