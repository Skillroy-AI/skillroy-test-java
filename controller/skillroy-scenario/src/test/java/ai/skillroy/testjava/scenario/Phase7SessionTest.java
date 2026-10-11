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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.scenario.image.ImageCompare;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 5, the session's part (9.2, decisions D308, D332, D341, D342, D348, D351): the
 * unjournaled {@code read} (throwing the agent's error, never masking an image's {@code base64}, refusing what it does
 * not serve), the Robot gates, the credential rule (a secret reference and a pack verb given a credential-joined slot
 * refused for a model and a client with nothing sent, a {@code type} refused while a credential is in flight and
 * accepted after the person's sign-on consumed it), the provenance written on the step and read back, the host
 * features seen by the merge at open, and the session's image route.
 */
class Phase7SessionTest {

    private static final Origin PERSON = Origin.person("tester");
    private static final Origin MODEL = Origin.model("a-model", "the next action", "req-1");
    private static final Origin CLIENT = Origin.client("a-client");

    @TempDir
    Path dir;

    private SessionFixture fx;

    @BeforeEach
    void fixture() {
        fx = new SessionFixture(dir);
        fx.agent.value("swing.type", Map.of("mode", "SET", "typed", 4L));
        fx.agent.value("swing.key", Map.of("dispatchedTo", "h3"));
        fx.agent.value("swing.pointer", Map.of("via", "ROUTED", "fired", true));
        fx.agent.describe("swing.capture", false).describe("swing.hitTest", false).describe("swing.rendering", false)
                .describe("data.query", false).describe("swing.pointer", true).describe("hook.events", false)
                .describe("hook.install", false).describeScript("web.evaluate");
    }

    private List<StepJournal.Entry> journal(ExecutorSession session) {
        return session.journal().entries();
    }

    // ------------------------------------------------------------------------------------------------ read

    @Test
    void readIsUnjournaledMaskedButTheImageAndThrowsTheAgentsError() throws Exception {
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            String secret = TestCredentials.PW_100;
            fx.agent.value("swing.capture", Map.of("format", "png", "base64", "AA" + secret + "AA", "hash", "sha256:x",
                    "note", "typed " + secret));
            int before = journal(session).size();
            @SuppressWarnings("unchecked")
            Map<String, Object> read = (Map<String, Object>) session.read("swing.capture", Map.of("scope", "SCREEN"));
            assertEquals("AA" + secret + "AA", read.get("base64"), "an image's base64 is never masked (D308)");
            assertEquals("typed <redacted>", read.get("note"), "every text value else is");
            assertEquals(before, journal(session).size(), "not journaled");
            fx.agent.error("swing.hitTest", ErrorCodes.NOT_FOUND, "outside");
            AgentRpcException e = assertThrows(AgentRpcException.class, () -> session.read("swing.hitTest",
                    Map.of("point", Map.of("x", 1L, "y", 1L))));
            assertEquals(ErrorCodes.NOT_FOUND, e.error().code(), "the agent's error is thrown, not swallowed");
            int queries = fx.agent.callsTo("data.query").size();
            for (String refused : List.of("data.query", "swing.pointer", "web.evaluate", "hook.install", "toy.wipe",
                    "agent.disable", "nothing.listed")) {
                assertThrows(IllegalArgumentException.class, () -> session.read(refused, Map.of()), refused);
            }
            assertThrows(IllegalArgumentException.class, () -> session.read("swing.capture", Map.of("mode", "ROBOT")));
            assertThrows(IllegalArgumentException.class, () -> session.read("swing.capture", Map.of("redact", false)));
            fx.agent.value("hook.events", Map.of("events", List.of()));
            session.read("hook.events", Map.of());
            assertEquals(queries, fx.agent.callsTo("data.query").size(), "nothing refused was sent");
            assertTrue(fx.agent.callsTo("swing.pointer").isEmpty() && fx.agent.callsTo("toy.wipe").isEmpty());
        }
    }

    // ------------------------------------------------------------------------------------------------ Robot

    @Test
    void robotIsRefusedWithoutTheFlagAndAGlassCaptureIsAPersonsOnly() throws Exception {
        try (ExecutorSession session = fx.open()) {
            StepOutcome via = session.execute(PlannedCall.of("swing.pointer", Map.of("point", Map.of("x", 1L, "y",
                    1L), "action", "CLICK", "via", "ROBOT"), Sync.NONE), PERSON);
            assertEquals(Status.ERROR, via.status());
            assertEquals("robot: not allowed; a person passes --allow-robot", via.message());
            StepOutcome mode = session.execute(PlannedCall.of("swing.capture", Map.of("mode", "ROBOT"), Sync.NONE),
                    PERSON);
            assertEquals("robot: not allowed; a person passes --allow-robot", mode.message());
            assertTrue(fx.agent.callsTo("swing.pointer").isEmpty() && fx.agent.callsTo("swing.capture").isEmpty(),
                    "nothing was sent");
        }
        fx.agent.value("swing.capture", Map.of("format", "png", "base64", ""));
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withRobot(true)
                .withId("sess-2"))) {
            for (Origin origin : List.of(MODEL, CLIENT)) {
                StepOutcome glass = session.execute(PlannedCall.of("swing.capture", Map.of("mode", "ROBOT"),
                        Sync.NONE), origin);
                assertEquals(Status.ERROR, glass.status(), origin.kind().name());
                assertTrue(glass.message().contains("a capture of the glass is a person's evidence"), glass.message());
            }
            assertTrue(fx.agent.callsTo("swing.capture").isEmpty());
            assertEquals(Status.PASS, session.execute(PlannedCall.of("swing.capture", Map.of("mode", "ROBOT"),
                    Sync.NONE), PERSON).status(), "a person's glass capture with the flag is sent");
            assertEquals(Status.PASS, session.execute(PlannedCall.of("swing.pointer", Map.of("by", Map.of("id", "x"),
                    "action", "CLICK", "via", "ROBOT"), Sync.NONE), MODEL).status(),
                    "the session's gate is origin-free for via: ROBOT (CallPolicy refuses it for a model upstream)");
            assertTrue(journal(session).get(0) instanceof StepJournal.SessionStart start
                    && Boolean.TRUE.equals(start.options().get("robot")), "journaled in session-start.options");
        }
    }

    /**
     * The fix wave of the Phase 7 review (FB, L1-F1 = L2-F3): the session's gates read {@code via} and {@code mode} as
     * the agent does (trimmed, any case): without {@code SessionOptions.robot} a pointer spelled {@code robot},
     * {@code " Robot"} or behind a control character and a capture spelled {@code robot} are refused with nothing
     * sent; with it the person-only glass gate refuses {@code mode: Robot} for a model and a client; {@code read}
     * refuses a glass capture in any spelling and a {@code redact} that is not the boolean {@code true}.
     */
    @Test
    void theRobotAndGlassGatesCannotBePassedBySpelling() throws Exception {
        try (ExecutorSession session = fx.open()) {
            for (String via : List.of("robot", " Robot", "\u0001ROBOT")) {
                StepOutcome pointer = session.execute(PlannedCall.of("swing.pointer", Map.of("point", Map.of("x", 1L,
                        "y", 1L), "action", "CLICK", "via", via), Sync.NONE), PERSON);
                assertEquals(Status.ERROR, pointer.status(), via);
                assertEquals("robot: not allowed; a person passes --allow-robot", pointer.message(), via);
            }
            StepOutcome verb = session.execute("call", Map.of("primitive", "swing.pointer", "args", Map.of("point",
                    Map.of("x", 1L, "y", 1L), "action", "CLICK", "via", "robot")), PERSON);
            assertEquals("robot: not allowed; a person passes --allow-robot", verb.message(), "the REPL's call verb");
            StepOutcome mode = session.execute(PlannedCall.of("swing.capture", Map.of("mode", "robot"), Sync.NONE),
                    PERSON);
            assertEquals("robot: not allowed; a person passes --allow-robot", mode.message());
            for (Map<String, Object> args : List.<Map<String, Object>>of(Map.of("mode", "robot"), Map.of("mode",
                    " Robot "), Map.of("mode", "\u0001ROBOT"), Map.of("redact", "false"), Map.of("redact", "no"))) {
                assertThrows(IllegalArgumentException.class, () -> session.read("swing.capture", args),
                        String.valueOf(args.keySet()));
            }
            assertTrue(fx.agent.callsTo("swing.pointer").isEmpty() && fx.agent.callsTo("swing.capture").isEmpty(),
                    "nothing was sent");
        }
        fx.agent.value("swing.capture", Map.of("format", "png", "base64", ""));
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withRobot(true)
                .withId("sess-2"))) {
            for (Origin origin : List.of(MODEL, CLIENT)) {
                for (String spelling : List.of("Robot", "robot ", "\u0001ROBOT")) {
                    StepOutcome glass = session.execute(PlannedCall.of("swing.capture", Map.of("mode", spelling),
                            Sync.NONE), origin);
                    assertEquals(Status.ERROR, glass.status(), origin.kind().name());
                    assertTrue(glass.message().contains("a capture of the glass is a person's evidence"),
                            glass.message());
                }
            }
            assertTrue(fx.agent.callsTo("swing.capture").isEmpty(), "no glass capture was sent for them");
            // D-FB-2: a reference that resolves to ROBOT passes no gate as written, so the call refuses it, for every
            // origin and whatever the flag.
            session.fixture("r", Map.of("v", "robot", "m", " Robot"), null, PERSON);
            for (Origin origin : List.of(PERSON, MODEL)) {
                StepOutcome pointer = session.execute(PlannedCall.of("swing.pointer", Map.of("point", Map.of("x", 1L,
                        "y", 1L), "action", "CLICK", "via", "$r.v"), Sync.NONE), origin);
                assertEquals(Status.ERROR, pointer.status(), origin.kind().name());
                assertEquals(CallRunner.ROBOT_REFERENCE_REFUSAL, pointer.message());
                StepOutcome glass = session.execute(PlannedCall.of("swing.capture", Map.of("mode", "$r.m"),
                        Sync.NONE), origin);
                assertEquals(CallRunner.ROBOT_REFERENCE_REFUSAL, glass.message(), origin.kind().name());
            }
            assertTrue(fx.agent.callsTo("swing.pointer").isEmpty() && fx.agent.callsTo("swing.capture").isEmpty(),
                    "nothing was sent");
        }
    }

    // ------------------------------------------------------------------------------------------------ D342

    @Test
    void aCredentialIsNeverUsedOnAModelsOrAClientsBehalf() throws Exception {
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            assertTrue(session.secretsInFlight(), "a person's resolve joined a secret");
            int sent = fx.agent.callsTo("toy.input").size();
            for (Origin origin : List.of(MODEL, CLIENT)) {
                StepOutcome raw = session.execute(PlannedCall.of("toy.input", Map.of("source", "Entry", "value",
                        "$assistant.password"), Sync.READY), origin);
                assertEquals(Status.ERROR, raw.status(), origin.kind().name());
                assertEquals("a credential is never used on a model's or a client's behalf; the person signs on first",
                        raw.message());
                StepOutcome verb = session.execute("enter", Map.of("value", "$assistant.password"), origin);
                assertEquals(raw.message(), verb.message(), "a verb naming the secret field");
                StepOutcome signOn = session.execute("signOn", Map.of("staff", "$assistant"), origin);
                assertEquals(raw.message(), signOn.message(), "a pack verb given a credential-joined slot");
                StepOutcome typed = session.execute("type", Map.of("by", Map.of("id", "entry"), "text", "1234"),
                        origin);
                assertEquals("a credential is in flight; the person signs on first", typed.message());
                StepOutcome key = session.execute(PlannedCall.of("swing.key", Map.of("key", "7"), Sync.NONE), origin);
                assertEquals(typed.message(), key.message(), "a printable key");
                assertEquals(Status.PASS, session.execute(PlannedCall.of("swing.key", Map.of("key", "ENTER"),
                        Sync.NONE), origin).status(), "a key that types nothing");
            }
            assertEquals(sent, fx.agent.callsTo("toy.input").size(), "nothing refused was sent");
            assertTrue(fx.agent.callsTo("swing.type").isEmpty());
            String text = Files.readString(dir.resolve(StepJournal.FILE));
            assertFalse(text.contains(TestCredentials.PW_100), "the journal holds no secret");

            assertEquals(Status.PASS, session.execute("signOn", Map.of("staff", "$assistant"), PERSON).status());
            assertFalse(session.secretsInFlight(), "the person's sign-on consumed it");
            assertEquals(Status.PASS, session.execute("type", Map.of("by", Map.of("id", "entry"), "text", "1234"),
                    MODEL).status(), "typing is the model's again");
            assertEquals(Status.ERROR, session.execute("signOn", Map.of("staff", "$assistant"), MODEL).status(),
                    "a model's sign-on stays refused whatever the flag");
            assertEquals(Status.PASS, session.execute(PlannedCall.of("toy.input", Map.of("source", "Entry", "value",
                    "$assistant.code"), Sync.READY), MODEL).status(), "a non-secret field stays usable");
        }
    }

    // ------------------------------------------------------------------------------------------------ provenance

    @Test
    void theProvenanceIsWrittenOnTheStepAndReadsBack() throws Exception {
        try (ExecutorSession session = fx.open()) {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("tier", "V1");
            provenance.put("intent", "x".repeat(600));
            provenance.put("model", "a-model");
            provenance.put("capture", Map.of("hash", "sha256:abc", "width", 8L, "height", 6L));
            provenance.put("hitTest", Map.of("ref", "h12", "role", "button", "name", "DUE", "locator",
                    Map.of("action", "DUE"), "mark", 7L));
            session.execute("poke", null, MODEL, provenance);
            session.execute(PlannedCall.of("toy.poke", Map.of(), Sync.NONE), MODEL, Map.of("tier", "V2", "via",
                    "ROUTED"));
            session.execute("poke", null, PERSON);
            List<StepJournal.Entry> read = StepJournal.read(dir.resolve(StepJournal.FILE), fx.pack).entries();
            assertEquals(journal(session), read, "the read-back equality covers the provenance");
            StepJournal.StepEntry first = (StepJournal.StepEntry) read.get(1);
            assertEquals(500, ((String) first.provenance().get("intent")).length(), "intent cut at 500");
            assertEquals("V1", first.provenance().get("tier"));
            assertEquals(7L, ((Map<?, ?>) first.provenance().get("hitTest")).get("mark"));
            assertEquals("V2", ((StepJournal.StepEntry) read.get(2)).provenance().get("tier"));
            assertNull(((StepJournal.StepEntry) read.get(3)).provenance(), "none for a step no vision tool chose");
            assertFalse(((StepJournal.StepEntry) read.get(3)).toJson().containsKey("provenance"));
        }
    }

    // ------------------------------------------------------------------------------------------------ host

    /** inference.vision OK in a session whose host declares vision-session, the tier as the strategy. */
    public static final class HostAware implements CapabilityContributor {
        @Override
        public String id() {
            return "vision";
        }

        @Override
        public Map<String, Capability> capabilities(CapabilityContext ctx) {
            boolean session = ctx.host().contains("vision-session");
            String tier = ctx.host().stream().filter(f -> f.startsWith("vision-tier:")).map(f -> f.substring(12))
                    .findFirst().orElse(null);
            return Map.of("inference.vision", session ? new Capability("inference.vision", CapabilityState.OK, null,
                    tier) : new Capability("inference.vision", CapabilityState.UNAVAILABLE, "no vision session", null));
        }
    }

    @Test
    void theHostFeaturesReachTheMergeAtOpenAndCapabilitiesAnswersIt() throws Exception {
        Path services = dir.resolve("loader/META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(CapabilityContributor.class.getName()), HostAware.class.getName() + "\n");
        ClassLoader loader = new URLClassLoader(new URL[] {dir.resolve("loader").toUri().toURL()},
                getClass().getClassLoader());
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withWebPathLoader(loader)
                .withHost(Set.of("vision-session", "vision-tier:v2")))) {
            CapabilityMatrix merged = session.capabilities();
            assertEquals(new Capability("inference.vision", CapabilityState.OK, null, "v2"), merged.get(
                    "inference.vision"));
            assertEquals("controller:vision", merged.source("inference.vision"));
            assertEquals("agent", merged.source("toy.input"));
            CapabilityMatrix archived = CapabilityMatrix.fromJson(Json.parseObject(Files.readString(dir.resolve(
                    "capabilities.json"), StandardCharsets.UTF_8)));
            assertEquals(merged, archived, "capabilities.json is the merged matrix, written once");
            assertEquals("controller:vision", session.fact("caps", "[id=inference.vision].source"),
                    "the fact root caps reads the merged matrix with a source");
            StepJournal.SessionStart start = (StepJournal.SessionStart) journal(session).get(0);
            assertEquals(List.of("vision-session", "vision-tier:v2"), start.options().get("host"));
        }
        try (ExecutorSession plain = ExecutorSession.open(fx.pack, fx.agent, fx.options().withWebPathLoader(loader)
                .withId("sess-3"))) {
            assertEquals(CapabilityState.UNAVAILABLE, plain.capabilities().get("inference.vision").state(),
                    "without the host feature");
        }
    }

    // ------------------------------------------------------------------------------------------------ images

    @Test
    void theSessionsImageRouteSkipsWithoutABaselineAndHoldsWithOne() throws Exception {
        java.awt.image.BufferedImage image = Phase7ExecutorTest.image(0xFFFFFF, -1, -1);
        fx.agent.on("swing.capture", params -> {
            try {
                return Phase7ExecutorTest.capture(image, List.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        fx.agent.value("swing.rendering", Map.of("renderKey", "0123456789abcdef"));
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n  - expectImage: { label: pad, by:"
                + " { id: numberPad } }\n").getBytes(StandardCharsets.UTF_8), "t.yaml", fx.pack);
        try (ExecutorSession session = fx.open()) {
            StepOutcome skipped = session.expectImage(plan.steps().get(0), PERSON);
            assertEquals(Status.DEGRADED, skipped.status(), "a live session without a baseline records and skips");
            assertTrue(skipped.degradedFidelity().get(0).contains("no baseline for rendering 0123456789abcdef"),
                    String.valueOf(skipped.degradedFidelity()));
            StepJournal.StepEntry entry = (StepJournal.StepEntry) journal(session).get(journal(session).size() - 1);
            assertEquals("expectImage", entry.verb());
            assertEquals(Map.of("label", "pad", "by", Map.of("id", "numberPad")), entry.args(),
                    "the arguments reconstructed from the compiled step");

            Path root = dir.resolve("bindings");
            Path png = root.resolve("seed-a/t.images/pad.0123456789abcdef.png");
            Files.createDirectories(png.getParent());
            Files.write(png, ImageCompare.encodePng(image));
            BindingsFile file = new BindingsFile(1, "t", "seed-a", "toy", "1", null, "sha256:r",
                    new ai.skillroy.testjava.scenario.resolve.EnvFingerprint(1, "sha256:e", Map.of()), Map.of(),
                    Map.of(), Map.of("pad", Map.of("0123456789abcdef", new BindingsFile.ImageEntry(
                    ImageCompare.rasterHash(image), 8, 6, List.of(), List.of(), new BindingsFile.Focus(Map.of("id",
                    "entry"), null, true), "t.images/pad.0123456789abcdef.png", Map.of(), null, null))));
            Files.writeString(BindingsFile.locate(root, "seed-a", "t"), file.toJsonText());
            StepOutcome held = session.expectImage(plan.steps().get(0), PERSON, ExecutorSession.baselines(root,
                    "seed-a", "t"));
            assertEquals(Status.PASS, held.status(), held.message());
        }
    }
}
