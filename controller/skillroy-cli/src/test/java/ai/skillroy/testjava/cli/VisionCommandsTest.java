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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 9.3, 9.4 and 13.1 item 8 (VM's half of the CLI): {@code session}'s {@code --vision},
 * {@code --vision-model}, {@code --vision-tools}, {@code --allow-robot}, {@code --play} and {@code --export}, and
 * {@code mcp}'s {@code --vision} and {@code --allow-robot}: {@code v3} without the robot flag, Haiku with V2, an unknown
 * value and an export without a play are usage errors before the agent is asked anything; the session's start-up line
 * names the vision tier and why it is off.
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
}
