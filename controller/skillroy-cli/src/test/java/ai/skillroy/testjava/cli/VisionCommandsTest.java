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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 9.3, 9.4 and 13.1 item 8 (VM's half of the CLI): {@code session}'s {@code --vision},
 * {@code --vision-model}, {@code --vision-tools}, {@code --allow-robot}, {@code --play} and {@code --export}, and
 * {@code mcp}'s {@code --vision} and {@code --allow-robot}: {@code v3} without the robot flag, Haiku with V2, an unknown
 * value and an export without a play are usage errors before the agent is asked anything; the session's start-up line
 * names the vision tier and why it is off; {@code --play} runs a scenario's {@code aiAct} through the player with a
 * scripted model (served for the session's settings) and {@code --export} writes what the model did.
 */
class VisionCommandsTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private Path tokenFile;

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString()));
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    private Path script(String text) throws Exception {
        return Files.writeString(tmp.resolve("commands-" + System.nanoTime() + ".session"), text);
    }

    @Test
    void theVisionOptionsAreCheckedBeforeTheAgentIsAsked() throws Exception {
        Path script = script("quit\n");
        CliHarness.Outcome v3 = cli("session", "--script", script.toString(), "--vision", "v3")
                .expectExit(Exit.USAGE);
        assertTrue(v3.err().contains("--vision v3 needs --allow-robot"), v3.err());
        CliHarness.Outcome haiku = cli("session", "--script", script.toString(), "--vision", "v2", "--vision-model",
                "haiku").expectExit(Exit.USAGE);
        assertTrue(haiku.err().contains("haiku is allowed with --vision v1 only"), haiku.err());
        cli("session", "--script", script.toString(), "--vision", "v9").expectExit(Exit.USAGE);
        cli("session", "--script", script.toString(), "--vision-tools", "both").expectExit(Exit.USAGE);
        CliHarness.Outcome export = cli("session", "--script", script.toString(), "--export", "x")
                .expectExit(Exit.USAGE);
        assertTrue(export.err().contains("--export needs --play"), export.err());
        cli("session", "--play", tmp.resolve("missing.yaml").toString()).expectExit(Exit.USAGE);
        Path played = Files.writeString(tmp.resolve("p.yaml"), "scenario: p\npack: clitest\nsteps: []\n");
        cli("session", "--play", played.toString(), "--script", script.toString()).expectExit(Exit.USAGE);
        CliHarness.Outcome noGateway = cli("session", "--play", played.toString()).expectExit(Exit.USAGE);
        assertTrue(noGateway.err().contains("--play needs a model gateway"), noGateway.err());

        CliHarness.Outcome mcpV3 = cli("mcp", "--vision", "v3").expectExit(Exit.USAGE);
        assertTrue(mcpV3.err().contains("--vision v3 needs --allow-robot"), mcpV3.err());
        cli("mcp", "--vision", "v4").expectExit(Exit.USAGE);
        assertTrue(agent.requests.isEmpty(), "no call before a usage error");
    }

    @Test
    void theStartUpLineNamesTheTierAndWhyItIsOff() throws Exception {
        Path script = script("quit\n");
        CliHarness.Outcome none = cli("session", "--script", script.toString(), "--out", tmp.resolve("a")
                .toString()).expectExit(Exit.OK);
        assertTrue(none.out().contains("; vision off (no model gateway)"), none.out());
        Path recordings = Files.createDirectories(tmp.resolve("recordings"));
        CliHarness.Outcome noImages = cli("session", "--script", script.toString(), "--out", tmp.resolve("b")
                .toString(), "--inference", "recorded", "--recordings", recordings.toString()).expectExit(Exit.OK);
        assertTrue(noImages.out().contains("; vision off (images: false in the inference settings)"),
                noImages.out());
        CliHarness.Outcome off = cli("session", "--script", script.toString(), "--out", tmp.resolve("c").toString(),
                "--vision", "off").expectExit(Exit.OK);
        assertTrue(off.out().contains("; vision off (--vision off)"), off.out());
        Path settings = Files.writeString(tmp.resolve("inference.yaml"), "version: 1\ngateway: recorded\n"
                + "recordings: recordings/\nimages: true\n");
        CliHarness.Outcome screen = cli("session", "--script", script.toString(), "--out", tmp.resolve("d")
                .toString(), "--inference-file", settings.toString()).expectExit(Exit.OK);
        assertTrue(screen.out().contains("; vision off (the target's swing.capture.screen is not OK"),
                "the module is present and the settings allow it, but the fake target has no screen capture: "
                        + screen.out());
    }

    /** A 4 x 3 PNG, as a target's capture would carry it. */
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void playRunsTheScenariosAiActThroughThePlayerAndExportWritesWhatTheModelDid() throws Exception {
        byte[] png = png();
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        agent.capabilities.put("swing.capture.screen", new Capability("swing.capture.screen", CapabilityState.OK,
                null, "fake"));
        agent.schema.add(new PrimitiveDescriptor("swing.capture", "Captures.", null, null, "EDT", 10_000, null,
                false, false));
        agent.handlers.put("swing.capture", p -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("format", "png");
            r.put("width", 4L);
            r.put("height", 3L);
            r.put("base64", Base64.getEncoder().encodeToString(png));
            r.put("captureId", "cap-1");
            r.put("seq", 1L);
            r.put("space", "SCREEN");
            r.put("origin", Map.of("x", 0L, "y", 0L));
            r.put("scale", 1.0);
            r.put("windows", List.of());
            r.put("marks", List.of());
            r.put("hash", hash);
            return r;
        });
        Path recordings = Files.createDirectories(tmp.resolve("recordings")).toAbsolutePath().normalize();
        Path file = Files.writeString(tmp.resolve("inference.yaml"), "version: 1\ngateway: recorded\nrecordings: "
                + recordings + "\nimages: true\n");
        InferenceSettings settings = InferenceSettings.load(BindMode.LIVE, null, Map.of(InferenceSettings.PROP_FILE,
                file.toString()));
        ScriptedGateway model = ScriptedGateway.sequence(List.of(
                new ModelResponse(List.of(new Content.ToolUse("t1", "clitest_poke", Map.of())),
                        ModelResponse.StopReason.TOOL_USE, ScriptedGateway.usage(900, 20, "0"), null),
                new ModelResponse(List.of(new Content.ToolUse("t2", "done", Map.of("answer", "poked"))),
                        ModelResponse.StopReason.TOOL_USE, ScriptedGateway.usage(900, 20, "0"), null)));
        Path played = Files.writeString(tmp.resolve("poke-once.yaml"), "scenario: poke-once\npack: clitest\n"
                + "steps:\n  - aiAct: { intent: \"poke the counter once\" }\n");
        Path out = tmp.resolve("play-out");
        try (Gateways.Served served = Gateways.serve(settings, model)) {
            CliHarness.Outcome outcome = cli("session", "--play", played.toString(), "--export", "poked",
                    "--inference-file", file.toString(), "--out", out.toString()).expectExit(Exit.OK);
            assertTrue(outcome.out().contains("vision v1 ("), outcome.out());
            assertTrue(outcome.out().contains("step 1 aiAct: PASS"), outcome.out());
            assertTrue(outcome.out().contains("poke-once: PASS"), outcome.out());
            assertEquals(1, agent.counter.get(), "the model poked once");
            assertEquals(2, model.requests().size());
            assertEquals("vision", model.requests().get(0).purpose());
        }
        Path yaml = out.resolve("poked.yaml");
        assertTrue(Files.isRegularFile(yaml), "the export's YAML");
        assertTrue(Files.readString(yaml).contains("poke"), Files.readString(yaml));
        assertTrue(Files.isRegularFile(out.resolve("plan").resolve("poked.plan.json")));
        assertFalse(Files.exists(out.resolve("captures").resolve(hash.substring(7) + ".png")),
                "a verb's act keeps no capture: only one a recorded vision step acted on (9.6)");
        String journal = Files.readString(out.resolve("journal.jsonl"));
        assertTrue(journal.contains("\"host\":[\"vision-session\",\"vision-tier:v1\"]"), "the host features journaled");
        assertTrue(journal.contains("inference.vision"), "the vision module contributed its capability");
    }
}
