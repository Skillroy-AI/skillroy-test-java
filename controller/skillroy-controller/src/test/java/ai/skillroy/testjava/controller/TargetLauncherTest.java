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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.LoadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Launches real child JVMs (the Java 8 target when -Dskillroy.it.targetJava names one). */
class TargetLauncherTest {

    /** Generous: a Java 8 target under x86-64 emulation starts slowly. */
    private static final Duration AGENT_WAIT = Duration.ofSeconds(120);

    @TempDir
    Path dir;

    private final List<LaunchedTarget> launched = new ArrayList<>();

    @AfterEach
    void closeAll() {
        launched.forEach(LaunchedTarget::close);
    }

    private LaunchedTarget start(TargetLauncher.Builder builder) throws IOException {
        LaunchedTarget target = builder.start();
        launched.add(target);
        return target;
    }

    private TargetLauncher.Builder child(Fixtures f) {
        return TargetLauncher.java(Fixtures.targetJava())
                .classPath(List.of(f.childClasses))
                .mainClass(Fixtures.CHILD_MAIN)
                .inheritOutputTo(dir.resolve("child.log"));
    }

    @Test
    void launchesATrivialChildWithItsCommandLineAndEnvironmentAndKillsItsTree() throws Exception {
        Fixtures f = Fixtures.get();
        Path report = dir.resolve("report.properties");
        boolean hasHome = System.getenv("HOME") != null;
        LaunchedTarget target = start(child(f)
                .jvmArg("-Dchild.flag=on")
                .arg("report=" + report)
                .arg("grandchild=" + Fixtures.targetJava())
                .env("CHILD_ENV", "value with spaces")
                .env("HOME", null));
        assertEquals(List.of(Fixtures.targetJava().toString(), "-Dchild.flag=on", "-cp", f.childClasses.toString(),
                Fixtures.CHILD_MAIN, "report=" + report, "grandchild=" + Fixtures.targetJava()), target.command());
        assertNull(target.agentNonce());
        assertNull(target.optionsFile());
        assertEquals(Discovery.defaultDir().toAbsolutePath().normalize(), target.discoveryDir());

        Properties seen = Fixtures.report(report);
        assertEquals(Long.toString(target.pid()), seen.getProperty("pid"));
        assertEquals("on", seen.getProperty("prop.child.flag"));
        assertEquals("value with spaces", seen.getProperty("env.CHILD_ENV"));
        if (hasHome) {
            assertNull(seen.getProperty("env.HOME"), "a null value removes an inherited variable");
        }
        assertNull(seen.getProperty("prop.skillroy.agent.enabled"));
        assertNull(seen.getProperty("prop.skillroy.agent.discoveryDir"), "no agent flags without an agent");
        assertNull(seen.getProperty("env.JAVA_TOOL_OPTIONS"));
        Properties grandchild = Fixtures.report(Path.of(seen.getProperty("grandchildReport")));
        long grandchildPid = Long.parseLong(grandchild.getProperty("pid"));
        assertTrue(ProcessHandle.of(grandchildPid).map(ProcessHandle::isAlive).orElse(false));

        target.close();
        target.close();
        assertFalse(target.process().isAlive());
        Poll.until("the grandchild to exit", Duration.ofSeconds(30),
                () -> ProcessHandle.of(grandchildPid).map(h -> !h.isAlive()).orElse(true));
    }

    @Test
    void javaAgentModeWritesAnOwnerOnlyOptionsFileAndTheAgentAnswers() throws Exception {
        Fixtures f = Fixtures.get();
        Path discovery = dir.resolve("discovery");
        LaunchedTarget target = start(child(f)
                .discoveryDir(discovery)
                .javaAgent(f.home, Map.of("readOnly", "true")));
        Path options = target.optionsFile();
        assertTrue(options.startsWith(discovery));
        assertTrue(options.getFileName().toString().startsWith("skillroy-options-"));
        if (Files.getFileStore(options).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(options)));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(discovery)));
        }
        Properties written = new Properties();
        try (var in = Files.newInputStream(options)) {
            written.load(in);
        }
        assertEquals("true", written.getProperty("readOnly"));
        assertEquals(target.agentNonce(), written.getProperty("nonce"));
        assertTrue(SecureFiles.isValidToken(written.getProperty("token")));
        assertEquals(64, written.getProperty("token").length());

        List<String> command = target.command();
        assertTrue(command.contains("-Dskillroy.agent.enabled=true"), command.toString());
        assertTrue(command.contains("-Dskillroy.agent.discoveryDir=" + discovery), command.toString());
        assertTrue(command.contains("-javaagent:" + f.home.bootstrapJar() + "=t=" + options), command.toString());
        assertTrue(command.indexOf("-cp") > command.indexOf("-javaagent:" + f.home.bootstrapJar() + "=t="
                + options));

        Endpoint endpoint = target.awaitAgent(AGENT_WAIT);
        assertEquals(target.pid(), endpoint.pid());
        assertEquals(target.agentNonce(), endpoint.nonce());
        assertEquals(written.getProperty("token"), endpoint.token());
        assertEquals(LoadMode.JAVAAGENT, endpoint.loadMode());
        assertTrue(endpoint.sessionId() != null, "the verified session id: " + endpoint);
        // Finding S5: a verified ping with the launch nonce proves the agent read the options file, so it goes now.
        assertFalse(Files.exists(options), "awaitAgent deletes the options file once the agent is verified");
        try (AgentClient client = AgentClient.connect(endpoint)) {
            assertEquals("JAVAAGENT", client.ping().get("loadMode"));
        }

        target.close();
        assertFalse(Files.exists(options), "and close() does not mind");
    }

    @Test
    void viaJavaToolOptionsMovesTheAgentFlagsIntoTheEnvironmentWithAMainClassGuard() throws Exception {
        Fixtures f = Fixtures.get();
        Path discovery = dir.resolve("disco very");
        Path report = dir.resolve("report.properties");
        LaunchedTarget target = start(child(f)
                .discoveryDir(discovery)
                .javaAgent(f.home, Map.of())
                .viaJavaToolOptions(true)
                .env("JAVA_TOOL_OPTIONS", "-Dchild.flag=from-tool-options")
                .arg("report=" + report));
        assertTrue(target.command().stream().noneMatch(a -> a.startsWith("-javaagent")
                || a.startsWith("-Dskillroy.agent.")), target.command().toString());
        String toolOptions = target.environment().get("JAVA_TOOL_OPTIONS");
        assertTrue(toolOptions.startsWith("-Dchild.flag=from-tool-options \"-Dskillroy.agent.discoveryDir="),
                toolOptions);
        assertTrue(toolOptions.contains("\"-Dskillroy.agent.discoveryDir=" + discovery + "\""), toolOptions);
        assertTrue(toolOptions.endsWith(";mainClassGuard=" + Fixtures.CHILD_MAIN + "\""), toolOptions);

        Properties seen = Fixtures.report(report);
        assertEquals(toolOptions, seen.getProperty("env.JAVA_TOOL_OPTIONS"));
        assertEquals("from-tool-options", seen.getProperty("prop.child.flag"));
        assertEquals("true", seen.getProperty("prop.skillroy.agent.enabled"));
        assertEquals(discovery.toString(), seen.getProperty("prop.skillroy.agent.discoveryDir"));

        Endpoint endpoint = target.awaitAgent(AGENT_WAIT);
        assertEquals(target.agentNonce(), endpoint.nonce());
        assertEquals(LoadMode.JAVAAGENT, endpoint.loadMode());
    }

    @Test
    void embeddedTargetsAreFoundThroughTheLaunchersDiscoveryDir() throws Exception {
        Fixtures f = Fixtures.get();
        Path discovery = dir.resolve("embedded");
        LaunchedTarget target = start(TargetLauncher.java(Fixtures.targetJava())
                .classPath(List.of(f.childClasses, f.agentJar))
                .mainClass(Fixtures.CHILD_MAIN)
                .jvmArg("-Dskillroy.agent.enabled=true")
                .discoveryDir(discovery)
                .arg("embedded=true")
                .inheritOutputTo(dir.resolve("embedded.log")));
        assertTrue(target.command().contains("-Dskillroy.agent.discoveryDir=" + discovery));
        assertTrue(target.command().stream().noneMatch(a -> a.startsWith("-javaagent")));
        Endpoint endpoint = target.awaitAgent(AGENT_WAIT);
        assertEquals(LoadMode.EMBEDDED, endpoint.loadMode());
        assertEquals(target.pid(), endpoint.pid());
        try (AgentClient client = AgentClient.connect(endpoint)) {
            assertEquals("EMBEDDED", client.ping().get("loadMode"));
        }
    }

    @Test
    void awaitAgentReportsAnEarlyExitWithItsCodeAndTheAgentLog() throws Exception {
        Fixtures f = Fixtures.get();
        LaunchedTarget target = start(child(f)
                .discoveryDir(dir.resolve("discovery"))
                .arg("stderr=[skillroy] simulated failure")
                .arg("exit=3"));
        AgentLostException lost = assertThrows(AgentLostException.class, () -> target.awaitAgent(AGENT_WAIT));
        assertTrue(lost.getMessage().contains("not running"), lost.getMessage());
        assertTrue(lost.getMessage().contains("exit code 3"), lost.getMessage());
        assertTrue(lost.getMessage().contains("[skillroy] simulated failure"), lost.getMessage());
    }

    @Test
    void awaitAgentTimesOutWhenNoAgentStarts() throws Exception {
        Fixtures f = Fixtures.get();
        LaunchedTarget target = start(child(f).discoveryDir(dir.resolve("discovery")));
        long start = System.nanoTime();
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> target.awaitAgent(Duration.ofMillis(500)));
        assertTrue(lost.getMessage().contains("no agent answered"), lost.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 30);
        assertTrue(target.process().isAlive(), "a timeout does not end the target");
    }

    @Test
    void aFailedAgentStartIsReportedWithTheAgentsLogLine() throws Exception {
        Fixtures f = Fixtures.get();
        Path report = dir.resolve("report.properties");
        LaunchedTarget target = start(child(f)
                .discoveryDir(dir.resolve("discovery"))
                .javaAgent(f.home, Map.of("fakeFail", "simulated start failure"))
                .arg("report=" + report));
        // main() has run, so premain has failed and logged by now: the short wait below cannot race the log line.
        Fixtures.report(report);
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> target.awaitAgent(Duration.ofMillis(500)));
        assertTrue(lost.getMessage().contains("no agent answered"), lost.getMessage());
        assertTrue(lost.getMessage().contains("[skillroy] agent failed to start"), lost.getMessage());
        assertTrue(lost.getMessage().contains("simulated start failure"), lost.getMessage());
        assertTrue(target.process().isAlive(), "an agent failure never ends the host, and neither does the wait");
    }

    @Test
    void aFailedStartLeavesNoOptionsFileBehind() throws Exception {
        Fixtures f = Fixtures.get();
        Path discovery = dir.resolve("discovery");
        assertThrows(IOException.class, () -> TargetLauncher.java(dir.resolve("no-such-java"))
                .mainClass(Fixtures.CHILD_MAIN).discoveryDir(discovery).javaAgent(f.home, Map.of()).start());
        assertEquals(List.of(), optionFiles(discovery));

        // Both quote characters cannot be quoted for JAVA_TOOL_OPTIONS.
        Path unquotable = dir.resolve("it's \"here\"");
        assertThrows(IllegalArgumentException.class, () -> TargetLauncher.java(Fixtures.targetJava())
                .mainClass(Fixtures.CHILD_MAIN).discoveryDir(unquotable).javaAgent(f.home, Map.of())
                .viaJavaToolOptions(true).start());
        assertEquals(List.of(), optionFiles(unquotable));

        Path semicolon = dir.resolve("a;b");
        IOException refused = assertThrows(IOException.class, () -> TargetLauncher.java(Fixtures.targetJava())
                .mainClass(Fixtures.CHILD_MAIN).discoveryDir(semicolon).javaAgent(f.home, Map.of()).start());
        assertTrue(refused.getMessage().contains("';'"), refused.getMessage());
        assertEquals(List.of(), optionFiles(semicolon));
    }

    @Test
    void anAgentHomeWithAnEqualsSignIsRefused() throws Exception {
        Path home = dir.resolve("home=x");
        Files.createDirectories(home.resolve("lib"));
        Files.copy(Fixtures.get().home.bootstrapJar(), home.resolve(AgentHome.BOOTSTRAP_JAR));
        Path discovery = dir.resolve("discovery");
        IOException refused = assertThrows(IOException.class, () -> TargetLauncher.java(Fixtures.targetJava())
                .mainClass(Fixtures.CHILD_MAIN).discoveryDir(discovery).javaAgent(AgentHome.of(home), Map.of())
                .start());
        assertTrue(refused.getMessage().contains("'='"), refused.getMessage());
        assertEquals(List.of(), optionFiles(discovery));
    }

    private static List<Path> optionFiles(Path discovery) throws IOException {
        if (!Files.isDirectory(discovery)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(discovery)) {
            return files.filter(p -> p.getFileName().toString().startsWith("skillroy-options-")).toList();
        }
    }

    @Test
    void closeKillsATargetWhoseShutdownHangs() throws Exception {
        Fixtures f = Fixtures.get();
        Path report = dir.resolve("report.properties");
        LaunchedTarget target = start(child(f).arg("hang=true").arg("report=" + report));
        Fixtures.report(report);
        long start = System.nanoTime();
        target.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertFalse(target.process().isAlive(), "ended by force after the graceful attempt hung");
        assertTrue(elapsedMs >= 4_000 && elapsedMs < 60_000, "close() took " + elapsedMs + " ms");
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> target.awaitAgent(Duration.ofSeconds(30)));
        assertTrue(lost.getMessage().contains("not running"), lost.getMessage());
    }

    @Test
    void builderValidation() throws Exception {
        assertThrows(IllegalStateException.class, () -> TargetLauncher.java(Path.of("java")).start());
        assertThrows(IllegalStateException.class, () -> TargetLauncher.java(Path.of("java")).mainClass("x.Main")
                .viaJavaToolOptions(true).start());
        assertThrows(NullPointerException.class, () -> TargetLauncher.java(null));
        AgentHome home = Fixtures.get().home;
        assertThrows(IllegalArgumentException.class, () -> TargetLauncher.java(Path.of("java")).mainClass("x.Main")
                .discoveryDir(dir).javaAgent(home, Map.of("token", "two words")).start());
        // P2-L1-F4: a supplied token shorter than 16 characters is refused before anything is written or started.
        IllegalArgumentException shortToken = assertThrows(IllegalArgumentException.class,
                () -> TargetLauncher.java(Path.of("java")).mainClass("x.Main").discoveryDir(dir)
                        .javaAgent(home, Map.of("token", "fifteen-chars-x")).start());
        assertTrue(shortToken.getMessage().contains("16 to 1024") && !shortToken.getMessage().contains("fifteen"),
                shortToken.getMessage());
        assertEquals(List.of(), optionFiles(dir));
    }

    @Test
    void toolOptionsQuoting() {
        assertEquals("-Da=b", TargetLauncher.quoteForToolOptions("-Da=b"));
        assertEquals("\"-Da=b c\"", TargetLauncher.quoteForToolOptions("-Da=b c"));
        assertEquals("'-Da=\"b\"'", TargetLauncher.quoteForToolOptions("-Da=\"b\""));
        assertEquals("\"-Da=it's\"", TargetLauncher.quoteForToolOptions("-Da=it's"));
        assertThrows(IllegalArgumentException.class, () -> TargetLauncher.quoteForToolOptions("-Da='\""));
    }
}
