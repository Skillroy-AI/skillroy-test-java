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

import ai.skillroy.testjava.controller.Discovery;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole loop through the assembled launcher against the real agent: {@code attach} the agent home built by
 * {@code skillroy-agent-dist} into a running child JVM, then {@code jvms}, {@code info}, {@code caps},
 * {@code schema}, {@code call}, {@code events}, and finally {@code agent.disable}. The child runs on the Java named
 * by {@code -Dskillroy.it.targetJava} (a Java 8 target in the Linux container), headless, so no window can appear.
 */
class AttachIT {

    private static final String CHILD_SOURCE = """
            package skillroyit;

            public final class ParkedTarget {
                public static void main(String[] args) throws Exception {
                    System.out.println("ready " + System.getProperty("java.specification.version"));
                    System.out.flush();
                    while (System.in.read() >= 0) {
                        // Park until the parent closes standard input.
                    }
                }
            }
            """;

    @TempDir
    Path tmp;

    private Process child;

    @AfterEach
    void tearDown() {
        if (child != null) {
            child.destroyForcibly();
        }
    }

    private static Path agentHome() {
        String configured = System.getProperty("skillroy.agent.home");
        Path home = Path.of(configured != null && !configured.isBlank() ? configured
                : System.getProperty("skillroy.cli.it.agentHome", "target/it-agent-home/skillroy-agent"));
        Assumptions.assumeTrue(Files.isRegularFile(home.resolve("skillroy-agent-bootstrap.jar")),
                "no agent home at " + home);
        return home.toAbsolutePath();
    }

    private static Path targetJava() {
        String configured = System.getProperty("skillroy.it.targetJava");
        return configured != null && !configured.isBlank() ? Path.of(configured.strip())
                : Path.of(System.getProperty("java.home"), "bin", "java");
    }

    /** Compiles the parked target for Java 8, so that the same class runs on every target Java. */
    private Path compileChild() throws IOException {
        Path src = Files.createDirectories(tmp.resolve("child-src/skillroyit"));
        Path source = Files.writeString(src.resolve("ParkedTarget.java"), CHILD_SOURCE);
        Path classes = Files.createDirectories(tmp.resolve("child-classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = javac.run(null, diagnostics, diagnostics, "--release", "8", "-Xlint:-options", "-d",
                classes.toString(), source.toString());
        assertEquals(0, status, diagnostics.toString(StandardCharsets.UTF_8));
        return classes;
    }

    /** The child's Java specification version, as it reported it. */
    private String childJava;

    private long startChild() throws Exception {
        Path classes = compileChild();
        child = new ProcessBuilder(targetJava().toString(), "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+EnableDynamicAgentLoading", "-Djava.awt.headless=true", "-cp", classes.toString(),
                "skillroyit.ParkedTarget").redirectError(tmp.resolve("child.err").toFile()).start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8));
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (IOException e) {
                return null;
            }
        }).get(Launcher.DEADLINE_S, TimeUnit.SECONDS);
        assertTrue(line != null && line.startsWith("ready "), () -> "the child JVM did not start: " + line + "\n"
                + childErr());
        childJava = line.substring("ready ".length());
        return child.pid();
    }

    private String childErr() {
        try {
            return Files.readString(tmp.resolve("child.err"));
        } catch (IOException e) {
            return e.toString();
        }
    }

    @Test
    void attachThenEveryInspectionCommandThenDisable() throws Exception {
        Path home = agentHome();
        long pid = startChild();
        Path discovery = FakeAgent.ownerOnlyDir(tmp.resolve("discovery"));
        String dir = discovery.toString();
        String target = Long.toString(pid);

        Launcher.Result attached = Launcher.run(tmp, "attach", target, "--home", home.toString(),
                "--discovery-dir", dir).expectExit(0);
        assertTrue(attached.out().startsWith("attached to pid " + pid + ": agent "), attached.out() + childErr());
        assertTrue(attached.out().contains("(ATTACH, protocol " + Protocol.VERSION + ")"), attached.out());
        assertTrue(attached.out().contains("modules: ") && attached.out().contains("swing"), attached.out());
        assertTrue(attached.err().contains("connect with: skillroy info --pid " + pid + " --discovery-dir " + dir),
                attached.err());

        Launcher.Result jvms = Launcher.run(tmp, "jvms", "--discovery-dir", dir).expectExit(0);
        String row = jvms.out().lines().filter(l -> l.startsWith(pid + " ")).findFirst().orElse("");
        assertTrue(row.contains("* ATTACH :") && row.contains("skillroyit.ParkedTarget"), jvms.out());

        List<String> connection = List.of("--pid", target, "--discovery-dir", dir);
        String info = Launcher.run(tmp, with("info", connection)).expectExit(0).out();
        assertTrue(info.contains("load mode ATTACH") && info.contains("pid " + pid), info);

        String caps = Launcher.run(tmp, with("caps", connection)).expectExit(0).out();
        assertTrue(caps.lines().anyMatch(l -> l.startsWith("jvm.instrumentation") && l.contains("OK")), caps);

        String schema = Launcher.run(tmp, with("schema", connection, "--name", "agent.*")).expectExit(0).out();
        assertTrue(schema.contains("agent.disable  (destructive: needs --confirm"), schema);

        String properties = Launcher.run(tmp, with("call", connection, "jvm.properties",
                "{\"names\":[\"java.awt.headless\",\"java.specification.version\"]}")).expectExit(0).out();
        JsonView values = JsonView.of(Json.parseObject(properties)).obj("properties");
        assertEquals("true", values.str("java.awt.headless"));
        assertEquals(childJava, values.str("java.specification.version"));
        System.out.println("AttachIT: attached the agent to a Java " + childJava + " target and drove it");

        String events = Launcher.run(tmp, with("events", connection, "--since", "0", "--count", "1", "--json"))
                .expectExit(0).out();
        assertEquals(1L, JsonView.of(Json.parseObject(events.strip())).num("seq", 0), events);

        Launcher.Result refused = Launcher.run(tmp, with("call", connection, "agent.disable")).expectExit(1);
        assertTrue(refused.err().contains("CONFIRM_REQUIRED"), refused.err());
        String stopping = Launcher.run(tmp, with("call", connection, "agent.disable", "--confirm")).expectExit(0)
                .out();
        assertEquals(Boolean.TRUE, Json.parseObject(stopping).get("stopping"));
        assertTrue(child.isAlive(), "disabling the agent must leave the application running");
    }

    /**
     * Contract amendment A3: without a {@code discoveryDir} option the agent writes its token and discovery files
     * into a per-user directory it names by its own rule, and the controller looks for them in
     * {@code Discovery.defaultDir()}. The two rules live in different code bases (the agent's is Java 8 code inside
     * the target), so this runs the real agent with no directory named anywhere and requires that the controller
     * finds it.
     */
    @Test
    void withNoDiscoveryDirectoryNamedTheAgentAndTheControllerUseTheSamePerUserDefault() throws Exception {
        Path home = agentHome();
        long pid = startChild();
        String target = Long.toString(pid);
        Launcher.Result attached = Launcher.run(tmp, "attach", target, "--home", home.toString()).expectExit(0);
        try {
            assertTrue(attached.out().startsWith("attached to pid " + pid + ": agent "),
                    attached.out() + childErr());
            // Found through the default directory: no --discovery-dir, no --port.
            String info = Launcher.run(tmp, "call", "agent.info", "--pid", target).expectExit(0).out();
            JsonView options = JsonView.of(Json.parseObject(info)).obj("options");
            Path agentDir = Path.of(options.requireStr("discoveryDir"));
            assertEquals(Discovery.defaultDir().toAbsolutePath().normalize(), agentDir.toAbsolutePath().normalize(),
                    "the agent's own default and the controller's");
            Endpoint found = Discovery.forPid(pid, null).orElseThrow(() -> new AssertionError(
                    "no live discovery file for pid " + pid + " in " + Discovery.defaultDir()));
            assertTrue(found.token() != null && !found.token().isEmpty(), "with a readable token file");
            if (agentDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(agentDir));
                assertTrue(permissions.matches("rwx.-..-."), "writable by its owner only: " + permissions);
            }
            String jvms = Launcher.run(tmp, "jvms").expectExit(0).out();
            assertTrue(jvms.lines().anyMatch(l -> l.startsWith(pid + " ") && l.contains("* ATTACH :")), jvms);
        } finally {
            Launcher.run(tmp, "call", "agent.disable", "--confirm", "--pid", target);
        }
        assertTrue(child.isAlive(), "disabling the agent must leave the application running");
    }

    private static String[] with(String command, List<String> connection, String... rest) {
        List<String> all = new ArrayList<>();
        all.add(command);
        all.addAll(List.of(rest));
        all.addAll(connection);
        return all.toArray(String[]::new);
    }
}
