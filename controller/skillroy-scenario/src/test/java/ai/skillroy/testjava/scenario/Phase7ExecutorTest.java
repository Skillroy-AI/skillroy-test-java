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

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.image.ImageCompare;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 5, the executor's part: {@code Status.SKIPPED} for a {@code run} plan in
 * {@code replay} (no agent call, four records) and its refusal elsewhere (8.6); the Robot plan refused before
 * {@code checkRequires} (8.1, 9.2); the capability merge after step 0b (8.5: {@code replay} forcing
 * {@code UNAVAILABLE (replay)}, an agent {@code inference.} id refused, two contributors answering one id refused naming
 * both, {@code capabilities.json} merged once, {@code agentHash}, {@code BaseFacts.caps} over the merged matrix, a
 * contributor answering {@code inference.vision OK} with the visual check still skipped); and the image steps (6.7,
 * 8.7): an {@code expectImage} approved at its step and written after the steps, held, failed with its three files,
 * missing for the render key, skipped in {@code live}; an anchored {@code pointer} with no anchor
 * {@code REBIND_NEEDED} in every mode, held (the pointer call runs), and mismatching ({@code REBIND_NEEDED} with the
 * three files).
 */
class Phase7ExecutorTest {

    private static final String KEY = "0123456789abcdef";

    @TempDir
    Path tmp;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private Map<String, Object> capture;
    private String renderKey = KEY;

    @BeforeEach
    void toyApplication() throws Exception {
        agent.capabilities("toy.input", "toy.state", "swing.capture.paint", "swing.pointer.routed");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("accepted", true, "acked", true);
        });
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("toy.poke", Map.of());
        agent.on("swing.rendering", params -> Map.of("renderKey", renderKey, "jdk", Map.of("specification", "25"),
                "lookAndFeel", "javax.swing.plaf.metal.MetalLookAndFeel", "keyed", List.of("jdk.specification")));
        agent.on("swing.capture", params -> capture);
        agent.value("swing.find", Map.of("matches", List.of(Map.of("ref", "h5", "bounds", Map.of("x", 0L, "y", 0L, "w",
                2L, "h", 2L)))));
        agent.value("swing.pointer", Map.of("via", "ROUTED", "fired", true, "received", "h3"));
        capture = capture(image(0xFFFFFF, -1, -1), List.of());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private RunOptions options(BindMode mode, Path out) {
        return RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withOutputDir(out)
                .withBind(TestCredentials.bind().withMode(mode).withEnvId("seed-a")
                        .withBindingsRoot(tmp.resolve("bindings")).withApprover(Approver.APPROVE_ALL));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunResult run(Plan plan, RunOptions options) {
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    private RunResult run(Plan plan, RunOptions options, ClassLoader contributors) {
        return new PlanExecutor(pack, agent, options, contributors).run(plan);
    }

    private static List<Map<String, Object>> log(Path dir) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            records.add(Json.parseObject(line));
        }
        return records;
    }

    static BufferedImage image(int rgb, int x, int y) {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < 6; j++) {
            for (int i = 0; i < 8; i++) {
                image.setRGB(i, j, rgb);
            }
        }
        if (x >= 0) {
            image.setRGB(x, y, 0x000000);
        }
        return image;
    }

    static Map<String, Object> capture(BufferedImage image, List<Map<String, Object>> carets) throws Exception {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("format", "png");
        capture.put("width", (long) image.getWidth());
        capture.put("height", (long) image.getHeight());
        capture.put("base64", Base64.getEncoder().encodeToString(ImageCompare.encodePng(image)));
        capture.put("captureId", "cap-1");
        capture.put("scope", "COMPONENT");
        capture.put("space", "WINDOW");
        capture.put("window", "h1");
        capture.put("origin", Map.of("x", 0L, "y", 0L));
        capture.put("scale", 1.0);
        capture.put("windows", List.of(Map.of("ref", "h1", "rect", Map.of("x", 0L, "y", 0L, "w", 8L, "h", 6L))));
        capture.put("carets", carets);
        capture.put("focus", Map.of("owner", "h5", "ownerLocator", Map.of("id", "entry"), "ownerRect", Map.of("x", 0L,
                "y", 0L, "w", 2L, "h", 2L), "active", true));
        capture.put("redacted", List.of());
        capture.put("hash", ImageCompare.rasterHash(image));
        return capture;
    }

    /** A class loader that finds these contributors through ServiceLoader (and the test classes through its parent). */
    private ClassLoader contributors(Class<?>... classes) throws Exception {
        Path dir = tmp.resolve("services-" + System.nanoTime());
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        StringBuilder text = new StringBuilder();
        for (Class<?> c : classes) {
            text.append(c.getName()).append('\n');
        }
        Files.writeString(services.resolve(CapabilityContributor.class.getName()), text.toString());
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader());
    }

    /** Answers inference.vision OK whatever the context: the executor must still skip a visual check (D354). */
    public static final class VisionOk implements CapabilityContributor {
        @Override
        public String id() {
            return "vision";
        }

        @Override
        public Map<String, Capability> capabilities(CapabilityContext ctx) {
            return Map.of("inference.vision", new Capability("inference.vision", CapabilityState.OK, null, "v1"));
        }
    }

    /** Answers inference.vision too: a second contributor of one id. */
    public static final class VisionToo implements CapabilityContributor {
        @Override
        public String id() {
            return "other";
        }

        @Override
        public Map<String, Capability> capabilities(CapabilityContext ctx) {
            return Map.of("inference.vision", new Capability("inference.vision", CapabilityState.OK, null, null));
        }
    }

    /** Answers an id without the controller's prefix. */
    public static final class NotPrefixed implements CapabilityContributor {
        @Override
        public String id() {
            return "rogue";
        }

        @Override
        public Map<String, Capability> capabilities(CapabilityContext ctx) {
            return Map.of("lending.extra", new Capability("lending.extra", CapabilityState.OK, null, null));
        }
    }

    // ------------------------------------------------------------------------------------------------ SKIPPED

    @Test
    void aRunPlanIsSkippedInReplayBeforeTheAgentIsContacted() throws Exception {
        Plan plan = plan("steps:\n  - poke: {}\n  - aiAct: { intent: \"lend one item\" }\n");
        Path out = tmp.resolve("skipped");
        CostLedger ledger = new CostLedger();
        RunOptions options = options(BindMode.REPLAY, out);
        RunResult result = run(plan, options.withBind(options.bind().withLedger(ledger)));
        assertEquals(Status.SKIPPED, result.status());
        assertNull(result.failureKind());
        assertEquals("not executed: the plan needs a model while steps run (inference: run)", result.message());
        assertEquals(List.of(), result.steps());
        assertEquals(List.of(), agent.methods(), "the agent was not contacted");
        assertEquals(0, agent.capabilityReads);
        List<Map<String, Object>> log = log(out);
        assertEquals(List.of("run-start", "skipped", "ledger", "run-end"), log.stream().map(r -> r.get("type"))
                .toList());
        assertEquals(0L, log.get(2).get("count"));
        assertEquals("SKIPPED", log.get(3).get("status"));
        assertFalse(Files.exists(out.resolve("capabilities.json")));
        String xml = new JunitXmlReporter(RunOptions.DegradedAs.FAILURE).render("s", List.of(result), null);
        assertTrue(xml.contains("<skipped message=\"SKIPPED: not executed"), xml);
    }

    @Test
    void aRunPlanIsRefusedInEveryOtherMode() throws Exception {
        Plan plan = plan("steps:\n  - aiAct: { intent: x }\n");
        for (BindMode mode : List.of(BindMode.LIVE, BindMode.APPROVE)) {
            RunResult result = run(plan, options(mode, null));
            assertEquals(Status.ERROR, result.status(), mode.label());
            assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
            assertEquals("inference: run: run it with skillroy session --play", result.message());
        }
        assertEquals(List.of(), agent.methods(), "refused before the agent was contacted");
    }

    // ------------------------------------------------------------------------------------------------ Robot

    @Test
    void aRobotPlanIsRefusedBeforeTheRequirementsWithoutThePersonsFlag() throws Exception {
        Plan plan = plan("steps:\n  - pointer: { by: { action: DUE }, via: ROBOT }\n");
        RunResult refused = run(plan, options(BindMode.REPLAY, null));
        assertEquals(Status.ERROR, refused.status());
        assertEquals(FailureKind.ENVIRONMENT, refused.failureKind());
        assertEquals("robot: not allowed; a person passes --allow-robot", refused.message());
        assertEquals(0, agent.capabilityReads, "before checkRequires: the agent was asked nothing");
        Path out = tmp.resolve("robot");
        RunResult precondition = run(plan, options(BindMode.REPLAY, out).withRobot(true));
        assertEquals(FailureKind.PRECONDITION_FAILED, precondition.failureKind(), precondition.message());
        assertTrue(precondition.message().contains("swing.pointer.robot"), precondition.message());
        assertEquals(Boolean.TRUE, log(out).get(0).get("robot"), "the person's flag is recorded in run-start");
    }

    /**
     * The fix wave of the Phase 7 review (FB, L1-F1 = L2-F3): a raw {@code call:} step's {@code via} or {@code mode}
     * reaches the agent as written, and the agent reads it trimmed and upper-cased, so the plan's gate reads it the
     * same way: {@code via: robot}, {@code via: " Robot"} and a glass capture's {@code mode: robot} are refused before
     * the agent is asked without {@code RunOptions.robot}, and the pointer's plan names {@code swing.pointer.robot}.
     */
    @Test
    void aRawCallSpellingRobotInAnyCaseIsRefusedBeforeTheAgentIsAsked() throws Exception {
        for (String args : List.of("{ action: CLICK, point: { x: 1, y: 1 }, via: robot }",
                "{ action: CLICK, point: { x: 1, y: 1 }, via: \" Robot\" }")) {
            Plan plan = plan("steps:\n  - call: { primitive: swing.pointer, args: " + args + " }\n");
            assertTrue(plan.requires().contains("swing.pointer.robot"), args + ": " + plan.requires());
            RunResult refused = run(plan, options(BindMode.LIVE, null));
            assertEquals(Status.ERROR, refused.status(), args);
            assertEquals(FailureKind.ENVIRONMENT, refused.failureKind(), args);
            assertEquals("robot: not allowed; a person passes --allow-robot", refused.message(), args);
        }
        RunResult glass = run(plan("steps:\n  - call: { primitive: swing.capture, args: { mode: robot } }\n"),
                options(BindMode.LIVE, null));
        assertEquals(FailureKind.ENVIRONMENT, glass.failureKind(), glass.message());
        assertEquals("robot: not allowed; a person passes --allow-robot", glass.message());
        assertEquals(0, agent.capabilityReads, "before checkRequires: the agent was asked nothing");
        assertTrue(agent.callsTo("swing.pointer").isEmpty() && agent.callsTo("swing.capture").isEmpty(),
                "nothing was sent");
    }

    /**
     * The fix wave of the Phase 7 review (FB, D-FB-2): the gates read {@code via} and {@code mode} as written, so a
     * reference that resolves to ROBOT is refused at the call, before it is sent, even with {@code RunOptions.robot}.
     */
    @Test
    void aReferenceThatResolvesToRobotIsRefusedAtTheCall() throws Exception {
        Plan plan = plan("resolve:\n  r: { fixture: { v: robot } }\nsteps:\n  - call: { primitive: swing.pointer,"
                + " args: { action: CLICK, point: { x: 1, y: 1 }, via: $r.v } }\n");
        assertFalse(plan.usesRobot(), "as written, the plan names no Robot");
        for (RunOptions options : List.of(options(BindMode.LIVE, null), options(BindMode.LIVE, null).withRobot(true))) {
            RunResult refused = run(plan, options);
            assertEquals(Status.ERROR, refused.status(), refused.message());
            assertEquals(FailureKind.ENVIRONMENT, refused.failureKind());
            assertEquals(CallRunner.ROBOT_REFERENCE_REFUSAL, refused.message());
        }
        assertTrue(agent.callsTo("swing.pointer").isEmpty(), "nothing was sent");
        Plan routed = plan("resolve:\n  r: { fixture: { v: ROUTED } }\nsteps:\n  - call: { primitive: swing.pointer,"
                + " args: { action: CLICK, point: { x: 1, y: 1 }, via: $r.v } }\n");
        assertEquals(Status.PASS, run(routed, options(BindMode.LIVE, null)).status(), "any other value is sent");
        assertEquals(1, agent.callsTo("swing.pointer").size());
    }

    // ------------------------------------------------------------------------------------------------ the merge

    @Test
    void contributedCapabilitiesAreMergedOnceAndForcedUnavailableInReplay() throws Exception {
        Plan plan = plan("steps:\n  - poke: {}\n  - aiExpect: { question: \"a table is shown\" }\n"
                + "  - expect: { \"caps[id=inference.vision].source\": \"controller:vision\" }\n");
        Path out = tmp.resolve("merged");
        RunResult replay = run(plan, options(BindMode.REPLAY, out), contributors(VisionOk.class));
        assertEquals(Status.DEGRADED, replay.status(), replay.message());
        assertEquals(1, replay.skippedExpectations().size());
        String line = replay.skippedExpectations().get(0);
        assertTrue(line.startsWith("s2: aiExpect \"a table is shown\" YES") && line.endsWith("no visual evaluator in"
                + " this host; capability inference.vision is UNAVAILABLE (replay)"), line);
        CapabilityMatrix archived = CapabilityMatrix.fromJson(Json.parseObject(Files.readString(out.resolve(
                "capabilities.json"))));
        assertEquals(new Capability("inference.vision", CapabilityState.UNAVAILABLE, "replay", null),
                archived.get("inference.vision"), "replay forces UNAVAILABLE whatever the contributor answered");
        Map<String, Object> record = log(out).stream().filter(r -> "capabilities".equals(r.get("type"))).findFirst()
                .orElseThrow();
        assertEquals(agent.capabilities().hash(), record.get("agentHash"), "the agent's matrix after step 0b");
        assertEquals(archived.hash(), record.get("hash"));
        assertEquals(List.of("inference.vision"), record.get("contributed"));

        RunResult live = run(plan, options(BindMode.LIVE, tmp.resolve("live")), contributors(VisionOk.class));
        assertEquals(Status.DEGRADED, live.status(), "a contributor's OK in live changes nothing (D354)");
        assertTrue(live.skippedExpectations().get(0).endsWith("capability inference.vision is OK"),
                live.skippedExpectations().get(0));
    }

    @Test
    void aVisualCheckReadsMissingWithoutAContributor() throws Exception {
        RunResult result = run(plan("steps:\n  - aiExpect: { question: q }\n"), options(BindMode.REPLAY, null));
        assertEquals(Status.DEGRADED, result.status());
        assertTrue(result.skippedExpectations().get(0).endsWith("capability inference.vision is missing"),
                result.skippedExpectations().get(0));
    }

    @Test
    void conflictsAreRefusedNamingBoth() throws Exception {
        Plan plan = plan("steps:\n  - poke: {}\n");
        RunResult two = run(plan, options(BindMode.REPLAY, null), contributors(VisionOk.class, VisionToo.class));
        assertEquals(FailureKind.ENVIRONMENT, two.failureKind());
        assertTrue(two.message().contains("contributed by vision and by other"), two.message());
        RunResult rogue = run(plan, options(BindMode.REPLAY, null), contributors(NotPrefixed.class));
        assertTrue(rogue.message().contains("rogue") && rogue.message().contains("'inference.'"), rogue.message());
        agent.capability("inference.gateway", CapabilityState.OK, null);
        RunResult agentPrefix = run(plan, options(BindMode.REPLAY, null));
        assertEquals(FailureKind.ENVIRONMENT, agentPrefix.failureKind());
        assertTrue(agentPrefix.message().contains("the agent declares the capability 'inference.gateway'"),
                agentPrefix.message());
    }

    // ------------------------------------------------------------------------------------------------ expectImage

    @Test
    void anExpectImageIsApprovedAtItsStepThenHeldThenFailsWithItsThreeFiles() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad, by: { id: numberPad }, mask: [ { by: { id: entry }"
                + " } ] }\n");
        List<Approver.ImageApproval> asked = new ArrayList<>();
        Approver approver = new Approver() {
            @Override
            public Decision review(ApprovalRequest request) {
                throw new AssertionError("a plan with images only has nothing to approve at bind time");
            }

            @Override
            public Decision reviewImage(ImageApproval request) {
                asked.add(request);
                return new Decision(true, "tester", null);
            }
        };
        Path approveDir = tmp.resolve("approve");
        RunOptions approve = options(BindMode.APPROVE, approveDir);
        RunResult approved = run(plan, approve.withBind(approve.bind().withApprover(approver)));
        assertEquals(Status.PASS, approved.status(), approved.message());
        assertEquals(1, asked.size());
        assertEquals("pad", asked.get(0).label());
        assertEquals(KEY, asked.get(0).renderKey());
        assertTrue(Files.isRegularFile(asked.get(0).candidate()), "the person sees the candidate's file first");
        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        BindingsFile written = BindingsFile.read(file);
        assertEquals(2, written.bindingsVersion());
        BindingsFile.ImageEntry entry = written.images().get("pad").get(KEY);
        assertEquals("t.images/pad." + KEY + ".png", entry.file());
        assertEquals(List.of(new ImageCompare.Rect(0, 0, 2, 2)), entry.masks(), "the step's mask by locator");
        assertEquals(Map.of("id", "entry"), entry.focus().owner());
        assertEquals("25", ((Map<?, ?>) entry.rendering().get("jdk")).get("specification"));
        assertFalse(entry.rendering().containsKey("renderKey"));
        Path png = file.getParent().resolve(entry.file());
        assertEquals(entry.sha256(), ImageCompare.rasterHash(ImageCompare.decodePng(Files.readAllBytes(png))));
        assertTrue(log(approveDir).stream().anyMatch(r -> "image".equals(r.get("type"))
                && "approved".equals(r.get("result"))));

        RunResult held = run(plan, options(BindMode.REPLAY, tmp.resolve("held")));
        assertEquals(Status.PASS, held.status(), held.message());
        capture = capture(image(0xFFFFFF, 1, 1), List.of());
        assertEquals(Status.PASS, run(plan, options(BindMode.REPLAY, tmp.resolve("masked"))).status(),
                "a difference under the step's mask holds");

        capture = capture(image(0xFFFFFF, 5, 4), List.of());
        Path failedDir = tmp.resolve("failed");
        RunResult failed = run(plan, options(BindMode.REPLAY, failedDir));
        assertEquals(Status.FAIL, failed.status());
        assertEquals(FailureKind.ASSERTION, failed.failureKind());
        assertEquals("s1", failed.failedStepId());
        assertEquals(1L, failed.detail().get("differing"));
        for (String suffix : List.of("expected", "actual", "diff")) {
            assertTrue(Files.isRegularFile(failedDir.resolve("failure/image-pad-" + suffix + ".png")), suffix);
        }
        capture = capture(image(0xFFFFFF, 5, 4), List.of(Map.of("ref", "h5", "rect", Map.of("x", 5L, "y", 4L, "w", 1L,
                "h", 1L))));
        assertEquals(Status.PASS, run(plan, options(BindMode.REPLAY, tmp.resolve("caret"))).status(),
                "the capture's caret is masked on both sides");

        renderKey = "fedcba9876543210";
        RunResult missing = run(plan, options(BindMode.REPLAY, tmp.resolve("missing")));
        assertEquals(Status.ERROR, missing.status());
        assertEquals(FailureKind.REBIND_NEEDED, missing.failureKind());
        assertEquals("no baseline pad for rendering fedcba9876543210", missing.message());
        assertEquals("IMAGE", missing.detail().get("reason"));
        RunResult live = run(plan, options(BindMode.LIVE, tmp.resolve("live-missing")));
        assertEquals(Status.DEGRADED, live.status(), live.message());
        assertTrue(live.skippedExpectations().get(0).contains("no baseline for rendering fedcba9876543210"),
                String.valueOf(live.skippedExpectations()));
    }

    /** A canned capture whose focus owner is {@code ownerLocator} with the window rectangle {@code ownerRect}. */
    static Map<String, Object> capture(BufferedImage image, Map<String, Object> ownerLocator,
                                       ImageCompare.Rect ownerRect) throws Exception {
        Map<String, Object> capture = capture(image, List.of());
        Map<String, Object> focus = new LinkedHashMap<>();
        focus.put("owner", "h5");
        focus.put("ownerLocator", ownerLocator);
        if (ownerRect != null) {
            focus.put("ownerRect", ownerRect.toJson());
        }
        focus.put("active", true);
        capture.put("focus", focus);
        return capture;
    }

    /** The {@code image} record of a run's {@code run.jsonl}. */
    private static Map<String, Object> imageRecord(Path dir) throws Exception {
        return log(dir).stream().filter(r -> "image".equals(r.get("type"))).findFirst().orElseThrow();
    }

    private static BufferedImage withPixels(int... xy) {
        BufferedImage image = image(0xFFFFFF, -1, -1);
        for (int i = 0; i < xy.length; i += 2) {
            image.setRGB(xy[i], xy[i + 1], 0x000000);
        }
        return image;
    }

    /**
     * phase7-contracts.md 6.7 (the focus rule; the Phase 7 review's fix wave, FB, L3-F3): when the baseline's recorded
     * {@code focus.owner} and the capture's {@code focus.ownerLocator} differ, both owners' rectangles are blanked on
     * both sides and the record says {@code focusMasked: true}; a difference outside them still fails; equal owners
     * blank nothing; an owner without a rectangle contributes none (the other side's is still blanked).
     */
    @Test
    void differingFocusOwnersBlankBothOwnersRectanglesOnBothSides() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad }\n");
        Map<String, Object> ownerA = Map.of("id", "entry");
        Map<String, Object> ownerB = Map.of("id", "amount");
        ImageCompare.Rect rectA = new ImageCompare.Rect(0, 0, 2, 2);
        ImageCompare.Rect rectB = new ImageCompare.Rect(5, 3, 2, 2);
        capture = capture(withPixels(), ownerA, rectA);
        assertEquals(Status.PASS, run(plan, options(BindMode.APPROVE, tmp.resolve("approve"))).status());
        BindingsFile.ImageEntry entry = BindingsFile.read(BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t"))
                .images().get("pad").get(KEY);
        assertEquals(new BindingsFile.Focus(ownerA, rectA, true), entry.focus(), "approved with owner A");

        capture = capture(withPixels(1, 1, 6, 4), ownerB, rectB);
        Path differ = tmp.resolve("differ");
        RunResult held = run(plan, options(BindMode.REPLAY, differ));
        assertEquals(Status.PASS, held.status(), "a difference inside each owner's rectangle holds: " + held.message());
        assertEquals(Boolean.TRUE, imageRecord(differ).get("focusMasked"));
        assertEquals(8L, imageRecord(differ).get("maskedPixels"), "A's four pixels and B's four");

        capture = capture(withPixels(1, 1, 6, 4, 3, 0), ownerB, rectB);
        Path outside = tmp.resolve("outside");
        RunResult failed = run(plan, options(BindMode.REPLAY, outside));
        assertEquals(Status.FAIL, failed.status(), failed.message());
        assertEquals(FailureKind.ASSERTION, failed.failureKind());
        assertEquals(1L, failed.detail().get("differing"), "only the pixel outside both rectangles");
        assertEquals(Boolean.TRUE, imageRecord(outside).get("focusMasked"));

        capture = capture(withPixels(1, 1), ownerA, rectA);
        Path same = tmp.resolve("same");
        RunResult sameOwner = run(plan, options(BindMode.REPLAY, same));
        assertEquals(Status.FAIL, sameOwner.status(), "equal owners blank nothing: " + sameOwner.message());
        assertEquals(Boolean.FALSE, imageRecord(same).get("focusMasked"));
        assertEquals(0L, imageRecord(same).get("maskedPixels"));

        capture = capture(withPixels(1, 1), ownerB, null);
        Path noRect = tmp.resolve("no-rect");
        RunResult baselineSide = run(plan, options(BindMode.REPLAY, noRect));
        assertEquals(Status.PASS, baselineSide.status(), "the baseline owner's rectangle alone: "
                + baselineSide.message());
        assertEquals(Boolean.TRUE, imageRecord(noRect).get("focusMasked"));
        assertEquals(4L, imageRecord(noRect).get("maskedPixels"));
        capture = capture(withPixels(6, 4), ownerB, null);
        assertEquals(Status.FAIL, run(plan, options(BindMode.REPLAY, tmp.resolve("no-rect-b"))).status(),
                "an owner without a rectangle blanks nothing of its own");

        Map<String, Object> unfocused = capture(withPixels(1, 1), List.of());
        unfocused.put("focus", Map.of("active", false));
        capture = unfocused;
        Path none = tmp.resolve("none");
        assertEquals(Status.PASS, run(plan, options(BindMode.REPLAY, none)).status(),
                "no focus owner now (a WM-less desk): the baseline owner's rectangle is blanked");
        assertEquals(Boolean.TRUE, imageRecord(none).get("focusMasked"));
    }

    @Test
    void aRejectedCandidateIsRebindNeededAndTheDefaultReviewImageDelegates() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad }\n");
        RunOptions approve = options(BindMode.APPROVE, tmp.resolve("reject"));
        RunResult rejected = run(plan, approve.withBind(approve.bind().withApprover(Approver.REJECT_ALL)));
        assertEquals(FailureKind.REBIND_NEEDED, rejected.failureKind(), rejected.message());
        assertTrue(rejected.message().contains("candidate not approved"), rejected.message());
        // Phase 7 security fix, S115: an images-only plan writes nothing at bind time, and nothing after the steps
        // without an approved baseline (the old code wrote the file at bind time with no approver asked).
        assertFalse(Files.exists(BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t")),
                "nothing approved, nothing written, no file created");
        RunResult approved = run(plan, options(BindMode.APPROVE, tmp.resolve("yes")));
        assertEquals(Status.PASS, approved.status(), "APPROVE_ALL approves through the default reviewImage");
    }

    // ------------------------------------------------------------------------------------------------ anchors

    @Test
    void anAnchoredPointerRunsOnlyWhenItsAnchorMatches() throws Exception {
        Plan plan = plan("steps:\n  - pointer: { by: { name: frame }, at: { fx: 0.5, fy: 0.5 }, guard: anchor-s1 }\n");
        RunOptions live = options(BindMode.LIVE, tmp.resolve("no-anchor"));
        RunResult none = run(plan, live.withBind(live.bind().withWriteBindings(true)));
        assertEquals(FailureKind.REBIND_NEEDED, none.failureKind(), none.message());
        assertTrue(none.message().contains("no anchor anchor-s1 for rendering " + KEY), none.message());
        assertTrue(agent.callsTo("swing.pointer").isEmpty(), "nothing acted");
        RunResult approve = run(plan, options(BindMode.APPROVE, tmp.resolve("approve-anchor")));
        assertEquals(FailureKind.REBIND_NEEDED, approve.failureKind(), "an anchor is never approved at a step");

        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        BindingsFile read = BindingsFile.read(file);
        BufferedImage anchor = image(0x336699, -1, -1);
        BindingsFile.ImageEntry entry = new BindingsFile.ImageEntry(ImageCompare.rasterHash(anchor), 8, 6, List.of(),
                List.of(), new BindingsFile.Focus(null, null, false), "t.images/anchor-s1." + KEY + ".png",
                Map.of(), null, new BindingsFile.Anchor(100, 40));
        Files.createDirectories(file.getParent().resolve("t.images"));
        Files.write(file.getParent().resolve(entry.file()), ImageCompare.encodePng(anchor));
        Files.writeString(file, read.withImages(Map.of("anchor-s1", Map.of(KEY, entry))).toJsonText());

        capture = capture(anchor, List.of());
        RunResult held = run(plan, options(BindMode.REPLAY, tmp.resolve("anchored")));
        assertEquals(Status.PASS, held.status(), held.message());
        FakeAgentConnection.Call guard = agent.callsTo("swing.capture").get(agent.callsTo("swing.capture").size() - 1);
        assertEquals(Map.of("by", Map.of("name", "frame"), "scope", "REGION", "region", Map.of("x", 100L, "y", 40L,
                "w", 8L, "h", 6L), "redact", true), guard.params(), "the anchor's rectangle in the target's space");
        assertEquals(1, agent.callsTo("swing.pointer").size(), "then the pointer call ran");

        capture = capture(image(0x336699, 3, 3), List.of());
        Path mismatchDir = tmp.resolve("mismatch");
        RunResult mismatch = run(plan, options(BindMode.REPLAY, mismatchDir));
        assertEquals(FailureKind.REBIND_NEEDED, mismatch.failureKind(), mismatch.message());
        assertEquals("IMAGE", mismatch.detail().get("reason"));
        assertEquals(1, agent.callsTo("swing.pointer").size(), "the pointer call was not sent");
        for (String suffix : List.of("expected", "actual", "diff")) {
            assertTrue(Files.isRegularFile(mismatchDir.resolve("failure/image-anchor-s1-" + suffix + ".png")),
                    suffix);
        }
    }

    @Test
    void theReplayLabelCheckAndTheDerivedFileAreTheBindersToRefuse() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad }\n");
        assertEquals(Status.PASS, run(plan, options(BindMode.APPROVE, tmp.resolve("first"))).status());
        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        BindingsFile read = BindingsFile.read(file);
        Files.writeString(file, read.withImages(Map.of()).toJsonText());
        RunResult noLabel = run(plan, options(BindMode.REPLAY, null));
        assertEquals(FailureKind.REBIND_NEEDED, noLabel.failureKind());
        assertTrue(noLabel.message().contains("has no baseline or anchor 'pad'"), noLabel.message());
        BindingsFile.ImageEntry entry = read.images().get("pad").get(KEY);
        Files.writeString(file, read.withImages(Map.of("pad", Map.of(KEY, entry.withFile("../../elsewhere.png"))))
                .toJsonText());
        RunResult planted = run(plan, options(BindMode.REPLAY, null));
        assertEquals(FailureKind.ENVIRONMENT, planted.failureKind(), planted.message());
        assertTrue(planted.message().contains("names the file '../../elsewhere.png'"), planted.message());
    }

    // ------------------------------------------------------------------------------------ the security fix round

    private static BufferedImage black() {
        return image(0x000000, -1, -1);
    }

    /** A capture with no focus owner (a WM-less desk, or a session run from a terminal). */
    private static Map<String, Object> unfocused(BufferedImage image) throws Exception {
        Map<String, Object> capture = capture(image, List.of());
        capture.put("focus", Map.of("active", false));
        return capture;
    }

    /**
     * Phase 7 security fix, S103 (phase7-contracts.md 6.7): a focus owner whose rectangle covers the compared raster no
     * longer blanks it. The baseline is approved while the compared component itself holds the focus (its rectangle
     * the whole 8 x 6 raster); at replay the focus is nowhere, or elsewhere, and every pixel differs: the comparison
     * fails, the record names the rectangle compared instead of blanked, and the message says so. The old code blanked
     * the whole raster on both sides and passed (focusMasked, 48 masked pixels).
     */
    @Test
    void aFocusOwnerThatCoversTheComparedRasterIsComparedNotMasked() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad }\n");
        Map<String, Object> whole = Map.of("id", "pad");
        capture = capture(image(0xFFFFFF, -1, -1), whole, new ImageCompare.Rect(0, 0, 8, 6));
        assertEquals(Status.PASS, run(plan, options(BindMode.APPROVE, tmp.resolve("approve"))).status());

        capture = unfocused(black());
        Path nowhere = tmp.resolve("nowhere");
        RunResult failed = run(plan, options(BindMode.REPLAY, nowhere));
        assertEquals(Status.FAIL, failed.status(), failed.message());
        assertEquals(FailureKind.ASSERTION, failed.failureKind());
        assertEquals(48L, failed.detail().get("differing"));
        assertTrue(failed.message().endsWith("48 pixels differ (at most 0); the focus owner changed ({\"id\":\"pad\"}"
                + " to nothing) and covers 100 % of the compared raster; it was compared, not masked"),
                failed.message());
        Map<String, Object> record = imageRecord(nowhere);
        assertEquals(Boolean.FALSE, record.get("focusMasked"));
        assertEquals(0L, record.get("focusMaskedPixels"));
        assertEquals(List.of(Map.of("side", "recorded", "owner", whole, "rect", Map.of("x", 0L, "y", 0L, "w", 8L, "h",
                6L), "pixels", 48L)), record.get("focusMaskSkipped"));

        capture = capture(black(), Map.of("id", "amount"), new ImageCompare.Rect(6, 4, 2, 2));
        Path elsewhere = tmp.resolve("elsewhere");
        RunResult other = run(plan, options(BindMode.REPLAY, elsewhere));
        assertEquals(Status.FAIL, other.status(), other.message());
        assertEquals(44L, other.detail().get("differing"), "the small owner's four pixels blanked, the rest compared");
        assertEquals(4L, imageRecord(elsewhere).get("focusMaskedPixels"));
    }

    /**
     * Phase 7 security fix, S103: the current focus owner's rectangle covering the raster is compared too (the
     * reviewer's path); the recorded owner's small rectangle is still blanked.
     */
    @Test
    void aCurrentFocusOwnerThatCoversTheRasterIsComparedNotMasked() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad }\n");
        capture = capture(image(0xFFFFFF, -1, -1), Map.of("id", "entry"), new ImageCompare.Rect(0, 0, 2, 2));
        assertEquals(Status.PASS, run(plan, options(BindMode.APPROVE, tmp.resolve("approve"))).status());
        capture = capture(black(), Map.of("id", "panel"), new ImageCompare.Rect(0, 0, 8, 6));
        Path dir = tmp.resolve("covering");
        RunResult failed = run(plan, options(BindMode.REPLAY, dir));
        assertEquals(Status.FAIL, failed.status(), failed.message());
        assertEquals(44L, failed.detail().get("differing"));
        assertTrue(failed.message().contains("covers 100 % of the compared raster; it was compared, not masked"),
                failed.message());
        assertEquals("current", ((Map<?, ?>) ((List<?>) imageRecord(dir).get("focusMaskSkipped")).get(0))
                .get("side"));
    }

    /**
     * Phase 7 security fix, S103, the anchored twin: an anchor recorded with the focus on the anchored component (its
     * rectangle the whole crop) no longer lets its pointer call run over a crop that differs everywhere; the guard is
     * REBIND_NEEDED and nothing is pressed (the old code ran the pointer call at its recorded point).
     */
    @Test
    void anAnchorWhoseFocusOwnerCoversItsCropGuardsItsPointerCall() throws Exception {
        Plan plan = plan("steps:\n  - pointer: { by: { name: frame }, at: { fx: 0.5, fy: 0.5 }, guard: anchor-s1 }\n");
        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        BufferedImage anchor = image(0x336699, -1, -1);
        BindingsFile.ImageEntry entry = new BindingsFile.ImageEntry(ImageCompare.rasterHash(anchor), 8, 6, List.of(),
                List.of(), new BindingsFile.Focus(Map.of("name", "frame"), new ImageCompare.Rect(0, 0, 8, 6), true),
                "t.images/anchor-s1." + KEY + ".png", Map.of(), null, new BindingsFile.Anchor(100, 40));
        RunOptions live = options(BindMode.LIVE, tmp.resolve("first"));
        run(plan, live.withBind(live.bind().withWriteBindings(true)));
        BindingsFile read = BindingsFile.read(file);
        Files.createDirectories(file.getParent().resolve("t.images"));
        Files.write(file.getParent().resolve(entry.file()), ImageCompare.encodePng(anchor));
        Files.writeString(file, read.withImages(Map.of("anchor-s1", Map.of(KEY, entry))).toJsonText());
        int before = agent.callsTo("swing.pointer").size();

        capture = unfocused(black());
        RunResult guarded = run(plan, options(BindMode.REPLAY, tmp.resolve("guarded")));
        assertEquals(FailureKind.REBIND_NEEDED, guarded.failureKind(), guarded.message());
        assertTrue(guarded.message().contains("it was compared, not masked"), guarded.message());
        assertEquals(before, agent.callsTo("swing.pointer").size(), "the pointer call was not sent");
    }

    /**
     * Phase 7 security fix, S145 (phase7-contracts.md 6.7): the mismatch's two PNGs are written as compared, the step's
     * mask blanked on both sides, as the baseline and the diff are; the old code wrote the actual capture as rendered
     * (the masked rectangle's pixels in run output).
     */
    @Test
    void theMismatchFilesHoldTheComparedRastersWithTheStepsMaskBlanked() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad, mask: [ { x: 0, y: 0, w: 3, h: 3 } ] }\n");
        assertEquals(Status.PASS, run(plan, options(BindMode.APPROVE, tmp.resolve("approve"))).status());
        capture = capture(withPixels(1, 1, 5, 4), List.of());
        Path dir = tmp.resolve("failed");
        RunResult failed = run(plan, options(BindMode.REPLAY, dir));
        assertEquals(Status.FAIL, failed.status(), failed.message());
        assertEquals(1L, failed.detail().get("differing"), "the pixel under the mask is not compared");
        BufferedImage actual = ImageCompare.decodePng(Files.readAllBytes(dir.resolve("failure/image-pad-actual.png")));
        assertEquals(ImageCompare.MASK_RGB, actual.getRGB(1, 1), "the step's mask is blanked in the actual file");
        assertEquals(0xFF000000, actual.getRGB(5, 4), "outside it the pixel as captured");
        BufferedImage expected = ImageCompare.decodePng(Files.readAllBytes(dir.resolve(
                "failure/image-pad-expected.png")));
        assertEquals(ImageCompare.MASK_RGB, expected.getRGB(2, 2));
    }

    /**
     * Phase 7 security fix, S146, the controller's half (phase7-contracts.md 8.7): a capture whose focus owner's locator
     * is a {@code {text}} candidate (an older agent's answer: a nameless text field's content) never puts that text into
     * the approved entry or the bindings file; the owner is recorded by what is left ({@code {role}}), and a replay with
     * another owner still blanks both owners' rectangles. The old code wrote the field's content into the file.
     */
    @Test
    void aFocusOwnerLocatorHoldingTextNeverEntersTheBindingsFile() throws Exception {
        Plan plan = plan("steps:\n  - expectImage: { label: pad }\n");
        capture = capture(withPixels(), Map.of("text", "Jane Doe", "role", "textbox"), new ImageCompare.Rect(0, 0, 2,
                2));
        assertEquals(Status.PASS, run(plan, options(BindMode.APPROVE, tmp.resolve("approve"))).status());
        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        String text = Files.readString(file);
        assertFalse(text.contains("Jane Doe"), text);
        assertEquals(Map.of("role", "textbox"), BindingsFile.read(file).images().get("pad").get(KEY).focus().owner());
        capture = capture(withPixels(1, 1, 6, 4), Map.of("id", "amount"), new ImageCompare.Rect(5, 3, 2, 2));
        Path dir = tmp.resolve("other-owner");
        assertEquals(Status.PASS, run(plan, options(BindMode.REPLAY, dir)).status(), "both rectangles blanked");
        assertEquals(Boolean.TRUE, imageRecord(dir).get("focusMasked"));
    }
}
