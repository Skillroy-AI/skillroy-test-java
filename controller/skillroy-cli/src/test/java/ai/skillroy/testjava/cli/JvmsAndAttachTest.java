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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jvms} against a real child JVM (with a fake agent's discovery file naming it), and the {@code attach}
 * command's argument handling and failure reporting. A real attach of the real agent is exercised by
 * {@code LauncherIT}.
 */
class JvmsAndAttachTest {

    private static final long DEADLINE_S = 120;

    @TempDir
    Path tmp;

    private final List<Process> children = new ArrayList<>();
    private final List<FakeAgent> agents = new ArrayList<>();

    @AfterEach
    void tearDown() {
        agents.forEach(FakeAgent::close);
        for (Process child : children) {
            child.destroyForcibly();
        }
    }

    private Process startChild() throws Exception {
        Path classes = Path.of(ParkedChild.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes.toString(), ParkedChild.class.getName()).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        children.add(child);
        BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8));
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (IOException e) {
                return null;
            }
        }).get(DEADLINE_S, TimeUnit.SECONDS);
        assertEquals("ready", line, "the child JVM did not start");
        return child;
    }

    @Test
    void jvmsListsAChildJvmAndMarksItsAgent() throws Exception {
        Process child = startChild();
        FakeAgent agent = new FakeAgent();
        agents.add(agent);
        agent.pid = child.pid();
        agent.startTimeMs = child.info().startInstant().map(Instant::toEpochMilli)
                .orElse(System.currentTimeMillis());
        agent.loadMode = LoadMode.ATTACH;
        Path discovery = tmp.resolve("discovery");
        agent.writeDiscovery(discovery);

        CliHarness.Outcome outcome = CliHarness.run(tmp, "jvms", "--discovery-dir", discovery.toString())
                .expectExit(Exit.OK).never(agent.token);
        List<String> lines = List.of(outcome.out().split("\n"));
        assertTrue(lines.get(0).startsWith("PID") && lines.get(0).contains("AGENT")
                && lines.get(0).contains("COMMAND"), outcome.out());
        String row = lines.stream().filter(l -> l.startsWith(child.pid() + " ")).findFirst().orElse("");
        assertTrue(row.contains("* ATTACH :" + agent.port()), outcome.out());
        assertTrue(row.contains(ParkedChild.class.getName()), outcome.out());
        long self = ProcessHandle.current().pid();
        assertFalse(lines.stream().anyMatch(l -> l.startsWith(self + " ")), "the CLI's own JVM is left out");
        assertTrue(outcome.err().contains("* runs an agent"), outcome.err());

        String json = CliHarness.run(tmp, "jvms", "--json", "--discovery-dir", discovery.toString())
                .expectExit(Exit.OK).out();
        JsonView view = JsonView.of(Json.parseObject(json));
        assertEquals(discovery.toAbsolutePath().normalize().toString(), view.str("discoveryDir"));
        Map<?, ?> entry = view.list("jvms").stream().map(o -> (Map<?, ?>) o)
                .filter(m -> Long.valueOf(child.pid()).equals(m.get("pid"))).findFirst().orElseThrow();
        Map<?, ?> agentJson = (Map<?, ?>) entry.get("agent");
        assertEquals((long) agent.port(), agentJson.get("port"));
        assertEquals("ATTACH", agentJson.get("loadMode"));
    }

    @Test
    void jvmsWithoutAgentsHasNoMarkerAndNoHint() throws Exception {
        Process child = startChild();
        CliHarness.Outcome outcome = CliHarness.run(tmp, "jvms", "--discovery-dir", tmp.resolve("none").toString())
                .expectExit(Exit.OK);
        String row = List.of(outcome.out().split("\n")).stream().filter(l -> l.startsWith(child.pid() + " "))
                .findFirst().orElse("");
        assertTrue(row.contains(ParkedChild.class.getName()) && !row.contains("*"), outcome.out());
        assertEquals("", outcome.err());
    }

    // --- attach -----------------------------------------------------------------------------------------------------

    private Path fakeHome() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("agent-home"));
        Files.createDirectories(home.resolve("lib"));
        Files.write(home.resolve("skillroy-agent-bootstrap.jar"), new byte[0]);
        return home;
    }

    @Test
    void attachValidatesItsArgumentsBeforeTouchingAnyProcess() throws IOException {
        Path home = fakeHome();
        assertTrue(CliHarness.run(tmp, "attach", "abc", "--home", home.toString()).expectExit(Exit.USAGE).err()
                .contains("<pid> must be a positive process id"));
        assertTrue(CliHarness.run(tmp, "attach", "0", "--home", home.toString()).expectExit(Exit.USAGE).err()
                .contains("<pid> must be a positive process id"));
        assertTrue(CliHarness.run(tmp, "attach", "1", "--home", tmp.resolve("nohome").toString())
                .expectExit(Exit.USAGE).err().contains("agent home is not a directory"));
        assertTrue(CliHarness.run(tmp, "attach", "1", "--home", home.toString(), "--option", "readOnly")
                .expectExit(Exit.USAGE).err().contains("--option needs key=value"));
        assertTrue(CliHarness.run(tmp, "attach", "1", "--home", home.toString(), "--module",
                tmp.resolve("missing.jar").toString()).expectExit(Exit.USAGE).err().contains("no such file"));
        assertTrue(CliHarness.run(tmp, "attach", "--home", home.toString()).expectExit(Exit.USAGE).err()
                .contains("missing argument <pid>"));
    }

    @Test
    void attachWithoutAHomeSaysHowToConfigureOne() {
        Assumptions.assumeTrue(System.getProperty("skillroy.agent.home") == null
                && System.getenv("SKILLROY_AGENT_HOME") == null, "an agent home is configured in this environment");
        CliHarness.Outcome outcome = CliHarness.run(tmp, "attach", "1").expectExit(Exit.USAGE);
        assertTrue(outcome.err().contains("no agent home configured") && outcome.err().contains("--home"),
                outcome.err());
    }

    @Test
    void attachToAProcessThatIsGoneIsUnreachable() throws Exception {
        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version").redirectErrorStream(true).start();
        gone.getInputStream().readAllBytes();
        gone.waitFor();
        Path discovery = FakeAgent.ownerOnlyDir(tmp.resolve("discovery"));
        CliHarness.Outcome outcome = CliHarness.run(tmp, "attach", Long.toString(gone.pid()), "--home",
                fakeHome().toString(), "--discovery-dir", discovery.toString()).expectExit(Exit.UNREACHABLE);
        assertTrue(outcome.err().contains("attach failed: no running process with pid " + gone.pid()),
                outcome.err());
        try (var files = Files.list(discovery)) {
            assertEquals(0, files.count(), "the options file (it holds the token) must be deleted");
        }
    }

    @Test
    void attachOptionsCollectModulesOptionsAndTheDiscoveryDirectory() throws Exception {
        Path jar = Files.write(tmp.resolve("extra-module.jar"), new byte[0]);
        Path discovery = tmp.resolve("disc");
        CommandSpec spec = new AttachCommand().spec();
        ParsedArgs args = ArgParser.parse(spec, List.of("42", "--option", "readOnly=true", "--option",
                "modules=/opt/m.jar", "--module", jar.toString(), "--discovery-dir", discovery.toString(),
                "--option", "redact=a=b"));
        Cli cli = new Cli(System.in, System.out, System.err, getClass().getClassLoader(), tmp.resolve("default"));
        Map<String, String> options = AttachCommand.options(args, cli);
        assertEquals("true", options.get("readOnly"));
        assertEquals("a=b", options.get("redact"));
        assertEquals("/opt/m.jar," + jar.toAbsolutePath().normalize(), options.get("modules"));
        assertEquals(discovery.toAbsolutePath().normalize().toString(), options.get("discoveryDir"));

        Map<String, String> defaults = AttachCommand.options(ArgParser.parse(spec, List.of("42")), cli);
        assertEquals(tmp.resolve("default").toAbsolutePath().normalize().toString(), defaults.get("discoveryDir"));
        assertEquals(1, defaults.size());
    }
}
