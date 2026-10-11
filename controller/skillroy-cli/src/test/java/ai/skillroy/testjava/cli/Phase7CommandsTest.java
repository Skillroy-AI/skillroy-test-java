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

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 6, the CLI's VC half (8.6, 8.7, 9.3, decisions D328, D332, D355, D339): {@code run}
 * counts a SKIPPED scenario and exits 0, refuses a Robot plan without {@code --allow-robot} (and with it the target's
 * missing capability is the precondition), asks the image approval at its step in {@code approve} (the candidate's
 * path and size printed; {@code --yes} approves); {@code call} refuses {@code via: ROBOT} and {@code mode: ROBOT}
 * without {@code --allow-robot} before anything is sent; {@code caps} shows the {@code source} of every row and the
 * controller's capabilities merged in with {@code --inference}.
 */
class Phase7CommandsTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenarios;

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenarios = tmp.resolve("scenarios");
        Files.createDirectories(scenarios);
        Scenarios.write(scenarios, "pass.yaml", Scenarios.PASS);
        Files.writeString(scenarios.resolve("live.yaml"), "scenario: live-only\npack: clitest\nsteps:\n"
                + "  - aiAct: { intent: \"poke the counter twice\" }\n");
        Files.writeString(scenarios.resolve("robot.yaml"), "scenario: by-robot\npack: clitest\nsteps:\n"
                + "  - pointer: { by: { name: OK }, via: ROBOT }\n");
        Files.writeString(scenarios.resolve("image.yaml"), "scenario: an-image\npack: clitest\nsteps:\n"
                + "  - expectImage: { label: pane }\n");
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(Terminal terminal, String command, String... args) {
        List<String> all = new ArrayList<>(List.of(command));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.runWithTerminal(tmp.resolve("unused-discovery"), "", terminal, all.toArray(String[]::new))
                .never(agent.token);
    }

    private CliHarness.Outcome cli(String command, String... args) {
        return cli(Terminal.NONE, command, args);
    }

    @Test
    void aSkippedScenarioIsCountedAndIsNoFailure() throws Exception {
        CliHarness.Outcome outcome = cli("run", scenarios.resolve("pass.yaml").toString(),
                scenarios.resolve("live.yaml").toString()).expectExit(Exit.OK);
        assertTrue(outcome.out().contains("SKIPPED   live-only  ("), outcome.out());
        assertTrue(outcome.out().contains("\n  not executed: the plan needs a model while steps run (inference: run)"),
                outcome.out());
        assertTrue(outcome.out().contains("2 scenarios: 1 passed, 0 degraded, 0 failed, 0 errors, 1 skipped"),
                outcome.out());
        int pokes = agent.rpcRequests("test.poke").size();
        Map<String, Object> json = Json.parseObject(cli("run", scenarios.resolve("live.yaml").toString(), "--json")
                .expectExit(Exit.OK).out());
        assertEquals(1L, ((Map<?, ?>) json.get("summary")).get("skipped"));
        Map<?, ?> result = (Map<?, ?>) ((List<?>) json.get("results")).get(0);
        assertEquals("SKIPPED", result.get("status"));
        assertEquals("not executed: the plan needs a model while steps run (inference: run)",
                result.get("skippedReason"));
        assertEquals(pokes, agent.rpcRequests("test.poke").size(), "the skipped scenario sent nothing");
    }

    @Test
    void aRobotPlanNeedsThePersonsFlagAndThenTheTargetsCapability() {
        CliHarness.Outcome refused = cli("run", scenarios.resolve("robot.yaml").toString()).expectExit(Exit.FAILED);
        assertTrue(refused.out().contains("robot: not allowed; a person passes --allow-robot"), refused.out());
        assertTrue(refused.err().contains("swing.pointer.robot") || refused.out().contains("by-robot"),
                "the compiler's note names the capability: " + refused.err());
        CliHarness.Outcome allowed = cli("run", scenarios.resolve("robot.yaml").toString(), "--allow-robot")
                .expectExit(Exit.FAILED);
        assertTrue(allowed.out().contains("PRECONDITION_FAILED") && allowed.out().contains("swing.pointer.robot"),
                allowed.out());
    }

    private static String png(int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void approveAsksForAnImageBaselineAtItsStepAndYesApproves() throws Exception {
        String base64 = png(6, 4);
        agent.handlers.put("swing.rendering", p -> Map.of("renderKey", "0123456789abcdef"));
        agent.handlers.put("swing.capture", p -> Map.of("format", "png", "width", 6L, "height", 4L, "base64", base64,
                "origin", Map.of("x", 0L, "y", 0L), "scale", 1.0));
        Path bindings = tmp.resolve("bindings");
        Path out = tmp.resolve("out");
        List<String> prompts = new ArrayList<>();
        Terminal no = new Terminal() {
            @Override
            public boolean interactive() {
                return true;
            }

            @Override
            public String readLine(String prompt) {
                prompts.add(prompt);
                return "n";
            }
        };
        CliHarness.Outcome rejected = cli(no, "run", scenarios.resolve("image.yaml").toString(), "--mode", "approve",
                "--bindings", bindings.toString(), "--out", out.toString()).expectExit(Exit.FAILED);
        assertEquals(List.of("approve the baseline pane? [y/N] "), prompts, "asked once, at the step");
        assertTrue(rejected.out().contains("image baseline pane of an-image in default for rendering 0123456789abcdef:"
                + " 6 x 4 pixels"), rejected.out());
        assertTrue(rejected.out().contains("candidate: " + out.resolve("an-image/candidates/image-pane.png")),
                rejected.out());
        assertTrue(rejected.out().contains("REBIND_NEEDED") && rejected.out().contains("candidate not approved"),
                rejected.out());
        CliHarness.Outcome yes = cli("run", scenarios.resolve("image.yaml").toString(), "--mode", "approve",
                "--bindings", bindings.toString(), "--yes").expectExit(Exit.OK);
        assertTrue(yes.out().contains("PASS      an-image"), yes.out());
        Path written = bindings.resolve("default/an-image.bindings.json");
        assertTrue(Files.readString(written).contains("\"pane\""), "written after the steps");
        assertTrue(Files.isRegularFile(bindings.resolve("default/an-image.images/pane.0123456789abcdef.png")));
        cli("run", scenarios.resolve("image.yaml").toString(), "--bindings", bindings.toString())
                .expectExit(Exit.OK);
    }

    @Test
    void callRefusesRobotArgumentsWithoutTheFlag() {
        agent.handlers.put("swing.pointer", p -> Map.of("via", "ROBOT", "fired", true));
        agent.handlers.put("swing.capture", p -> Map.of("format", "png", "base64", ""));
        CliHarness.Outcome via = cli("call", "swing.pointer", "{\"by\":{\"name\":\"OK\"},\"via\":\"ROBOT\"}")
                .expectExit(Exit.USAGE);
        assertTrue(via.err().contains("via: ROBOT acts through java.awt.Robot: a person passes --allow-robot"),
                via.err());
        CliHarness.Outcome mode = cli("call", "swing.capture", "{\"mode\":\"ROBOT\"}").expectExit(Exit.USAGE);
        assertTrue(mode.err().contains("mode: ROBOT"), mode.err());
        assertTrue(agent.rpcRequests("swing.pointer").isEmpty() && agent.rpcRequests("swing.capture").isEmpty(),
                "nothing was sent");
        cli("call", "swing.pointer", "{\"by\":{\"name\":\"OK\"},\"via\":\"ROBOT\"}", "--allow-robot")
                .expectExit(Exit.OK);
        assertEquals(1, agent.rpcRequests("swing.pointer").size(), "with the person's flag it is sent");
    }

    @Test
    void capsShowsTheSourceOfEveryRowAndTheMergedMatrixWithSettings() throws Exception {
        String table = cli("caps").expectExit(Exit.OK).out();
        assertTrue(table.lines().findFirst().orElseThrow().endsWith("SOURCE"), table);
        assertTrue(table.lines().skip(1).allMatch(l -> l.endsWith("agent")), table);
        assertFalse(table.contains("inference."), "the agent's alone without settings");
        String merged = cli("caps", "--inference", "none").expectExit(Exit.OK).out();
        assertTrue(merged.lines().anyMatch(l -> l.startsWith("inference.gateway") && l.contains("UNAVAILABLE")
                && l.contains("gateway none") && l.endsWith("controller:inference")), merged);
        Map<String, Object> json = Json.parseObject(cli("caps", "--inference", "none", "--json").expectExit(Exit.OK)
                .out());
        Map<?, ?> gateway = (Map<?, ?>) ((Map<?, ?>) json.get("capabilities")).get("inference.sql");
        assertEquals(CapabilityState.UNAVAILABLE.name(), gateway.get("state"));
        assertEquals("controller:inference", gateway.get("source"));
    }
}
