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

package ai.skillroy.testjava.agent.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * premain, agentmain and EmbeddedAgent end to end against a fake agent home whose lib/ holds a test double of the
 * core runtime: enable rules, main-class guard, idempotency, cmd=stop, failures and the agent loader.
 */
class BootLauncherTest {

    private static final String ENABLED = "skillroy.agent.enabled";

    @TempDir
    Path tmp;

    private File home;
    private PrintStream originalErr;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() throws IOException {
        FakeRecorder.reset();
        home = BootJars.fakeHome(tmp.resolve("home").toFile());
        BootLauncher.environment = Collections.emptyMap();
        System.clearProperty(ENABLED);
        System.clearProperty(BootLauncher.PROP_ERROR);
        originalErr = System.err;
        err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, "UTF-8"));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
        BootLauncher.stop();
        BootLauncher.environment = null;
        System.clearProperty(ENABLED);
        System.clearProperty(BootLauncher.PROP_ERROR);
    }

    private String errText() {
        return new String(err.toByteArray(), Charset.forName("UTF-8"));
    }

    private String homeArg() {
        return "home=" + home.getPath();
    }

    @Test
    void javaAgentNeedsTheSystemPropertyOrEnvironmentNotItsArguments() {
        AgentBootstrap.premain("enabled=true;" + homeArg(), null);
        assertTrue(FakeRecorder.CALLS.isEmpty(), "the argument string cannot enable the agent");
        assertFalse(BootLauncher.isRunning());

        BootLauncher.environment = Collections.singletonMap(BootOptions.ENV_ENABLED, "true");
        AgentBootstrap.premain(homeArg(), null);
        assertEquals(Collections.singletonList("start JAVAAGENT"), FakeRecorder.CALLS);
        BootLauncher.stop();

        BootLauncher.environment = Collections.emptyMap();
        System.setProperty(ENABLED, "true");
        AgentBootstrap.premain(homeArg() + ";port=0", null);
        assertEquals(Arrays.asList("start JAVAAGENT", "close", "start JAVAAGENT"), FakeRecorder.CALLS);
        assertEquals("true", FakeRecorder.lastOptions.get("enabled"));
        assertEquals(home.getAbsolutePath(), FakeRecorder.lastOptions.get("home"));
        assertSame(ClassLoader.getSystemClassLoader(), FakeRecorder.lastHostLoader);
        assertEquals(AgentClassLoader.class, FakeRecorder.runtimeLoader.getClass(), "core runs in the agent loader");
        assertNull(FakeRecorder.lastHostContext);
        assertEquals("", errText());
    }

    @Test
    void mainClassGuardKeepsTheAgentOutOfOtherJvms() {
        System.setProperty(ENABLED, "true");
        AgentBootstrap.premain("mainClassGuard=no.such.Main;" + homeArg(), null);
        assertTrue(FakeRecorder.CALLS.isEmpty());
        String command = System.getProperty("sun.java.command", "").trim();
        String target = command.split("\\s+")[0];
        assertTrue(target.length() > 1, "the test JVM has a launch target: " + command);
        // Finding S17: a strict prefix of the launch target, or the target plus a suffix, is another JVM.
        AgentBootstrap.premain("mainClassGuard=" + target.substring(0, target.length() - 1) + ";" + homeArg(), null);
        assertTrue(FakeRecorder.CALLS.isEmpty(), "a guard that is only a prefix of the launch target");
        AgentBootstrap.premain("mainClassGuard=" + target + "Helper;" + homeArg(), null);
        assertTrue(FakeRecorder.CALLS.isEmpty(), "a launch target that merely starts like the guard");
        AgentBootstrap.premain("mainClassGuard=" + target + ";" + homeArg(), null);
        assertEquals(Collections.singletonList("start JAVAAGENT"), FakeRecorder.CALLS, "the whole launch target");
    }

    @Test
    void theGuardMatchesTheWholeLaunchTargetOnATokenBoundary() {
        String saved = System.getProperty("sun.java.command");
        try {
            System.setProperty("sun.java.command", "com.example.Register --flag value");
            assertTrue(BootLauncher.guardAllows("com.example.Register"));
            assertTrue(BootLauncher.guardAllows("  com.example.Register  "), "surrounding white space is ignored");
            assertTrue(BootLauncher.guardAllows(null));
            assertTrue(BootLauncher.guardAllows(" "));
            assertFalse(BootLauncher.guardAllows("com.example.Registe"));
            assertFalse(BootLauncher.guardAllows("com.example"));
            System.setProperty("sun.java.command", "com.example.RegisterUpdater");
            assertFalse(BootLauncher.guardAllows("com.example.Register"));
            System.setProperty("sun.java.command", "com.example.Register2 x");
            assertFalse(BootLauncher.guardAllows("com.example.Register"));
            System.setProperty("sun.java.command", "com.example.Register");
            assertTrue(BootLauncher.guardAllows("com.example.Register"), "no arguments at all");
            System.setProperty("sun.java.command", "/opt/my app/register.jar\targ");
            assertTrue(BootLauncher.guardAllows("/opt/my app/register.jar"), "a -jar path with a space");
            assertFalse(BootLauncher.guardAllows("/opt/my app/register"));
        } finally {
            if (saved == null) {
                System.clearProperty("sun.java.command");
            } else {
                System.setProperty("sun.java.command", saved);
            }
        }
    }

    @Test
    void attachIsIdempotentAndStopsOnCommand() {
        AgentBootstrap.agentmain("enabled=true;" + homeArg(), null);
        assertEquals(Collections.singletonList("start ATTACH"), FakeRecorder.CALLS);
        assertTrue(BootLauncher.isRunning());
        AgentBootstrap.agentmain("enabled=true;" + homeArg(), null);
        AgentBootstrap.agentmain("", null);
        assertEquals(Arrays.asList("start ATTACH", "republish", "republish"), FakeRecorder.CALLS,
                "a second load re-publishes discovery instead of starting again");
        AgentBootstrap.agentmain("cmd=stop", null);
        assertEquals("close", FakeRecorder.CALLS.get(3));
        assertFalse(BootLauncher.isRunning());
        AgentBootstrap.agentmain("cmd=stop", null);
        assertEquals(4, FakeRecorder.CALLS.size(), "stopping twice is harmless");
        AgentBootstrap.agentmain("enabled=true;" + homeArg(), null);
        assertEquals("start ATTACH", FakeRecorder.CALLS.get(4), "a stopped agent can be attached again");
    }

    @Test
    void attachReadsTokenAndEnabledFromTheOptionsFile() throws IOException {
        File options = tmp.resolve("attach.properties").toFile();
        BootOptionsTest.writeOwnerOnly(options, "enabled=true\ntoken=tok\nnonce=non\n" + homeArg() + "\n");
        AgentBootstrap.agentmain("t=" + options.getPath(), null);
        assertEquals(Collections.singletonList("start ATTACH"), FakeRecorder.CALLS);
        assertEquals("tok", FakeRecorder.lastOptions.get("token"));
        assertEquals("non", FakeRecorder.lastOptions.get("nonce"));
        BootLauncher.stop();
        FakeRecorder.CALLS.clear();
        AgentBootstrap.premain("t=" + options.getPath(), null);
        assertTrue(FakeRecorder.CALLS.isEmpty(), "the same file cannot enable JAVAAGENT");
    }

    /**
     * Phase 6 security fix, pass 2, P2-L1-F3: an options file that others may write (or that holds a token and others
     * may read) starts nothing, and the refusal is the one recorded error naming the rule.
     */
    @Test
    void attachRefusesAnOptionsFileOthersCouldHaveWritten() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        File options = tmp.resolve("planted.properties").toFile();
        Files.write(options.toPath(), ("enabled=true\nnonce=non\n" + homeArg() + "\n")
                .getBytes(Charset.forName("UTF-8")));
        Files.setPosixFilePermissions(options.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
        AgentBootstrap.agentmain("t=" + options.getPath(), null);
        assertTrue(FakeRecorder.CALLS.isEmpty(), "nothing started");
        String error = System.getProperty(BootLauncher.PROP_ERROR);
        assertNotNull(error);
        assertTrue(error.contains("is refused: it is writable by group or others"), error);
    }

    @Test
    void embeddedStartsWithTheHostsOptionsLoaderAndContext() {
        Properties props = new Properties();
        props.setProperty("home", home.getPath());
        assertFalse(EmbeddedAgent.start(props), "not enabled");
        assertTrue(FakeRecorder.CALLS.isEmpty());
        props.setProperty("enabled", "true");
        ClassLoader host = new ClassLoader(getClass().getClassLoader()) {
        };
        Object context = new Object();
        assertTrue(EmbeddedAgent.start(props, host, context));
        assertEquals(Collections.singletonList("start EMBEDDED"), FakeRecorder.CALLS);
        assertSame(context, FakeRecorder.lastHostContext);
        assertSame(host, FakeRecorder.lastHostLoader);
        assertSame(host, FakeRecorder.runtimeLoader.getParent(), "the agent loader's parent is the host loader");
        assertTrue(EmbeddedAgent.start(props), "already running");
        assertEquals(Collections.singletonList("start EMBEDDED"), FakeRecorder.CALLS,
                "a second EMBEDDED start changes nothing, not even discovery (A6)");
        EmbeddedAgent.stop();
        EmbeddedAgent.stop();
        assertEquals(Arrays.asList("start EMBEDDED", "close"), FakeRecorder.CALLS);
    }

    @Test
    void theAgentLoaderIsReleasedOnlyWhenTheRuntimeHasFinishedClosing() {
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("home", home.getPath());
        assertTrue(EmbeddedAgent.start(props));
        URLClassLoader loader = (URLClassLoader) FakeRecorder.runtimeLoader;
        assertNotNull(loader.findResource(BootJars.FAKE_RUNTIME));
        EmbeddedAgent.stop();
        assertNull(loader.findResource(BootJars.FAKE_RUNTIME), "a finished close releases the agent jars");

        props.setProperty("fake.asyncClose", "true");
        assertTrue(EmbeddedAgent.start(props));
        loader = (URLClassLoader) FakeRecorder.runtimeLoader;
        EmbeddedAgent.stop();
        assertFalse(BootLauncher.isRunning());
        assertNotNull(loader.findResource(BootJars.FAKE_RUNTIME),
                "the runtime is still shutting down on its own thread and may need to load classes");
        FakeRecorder.finishClose.run();
        assertNull(loader.findResource(BootJars.FAKE_RUNTIME));
    }

    @Test
    void aStartInterruptedByTheHostKeepsTheHostsInterrupt() {
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("home", home.getPath());
        props.setProperty("fake.interrupt", "true");
        try {
            assertFalse(EmbeddedAgent.start(props));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is the host's to see");
        } finally {
            Thread.interrupted();
        }
        assertTrue(errText().startsWith("[skillroy] EMBEDDED start failed: "), errText());
    }

    @Test
    void anAgentOfAnotherBootstrapCopyIsLeftAlone() {
        System.setProperty(BootLauncher.PROP_RUNNING, "JAVAAGENT");
        try {
            Properties props = new Properties();
            props.setProperty("enabled", "true");
            props.setProperty("home", home.getPath());
            assertTrue(EmbeddedAgent.start(props));
            assertTrue(FakeRecorder.CALLS.isEmpty());
        } finally {
            System.clearProperty(BootLauncher.PROP_RUNNING);
        }
    }

    @Test
    void failuresAreRecordedAndPrintedOnceNeverThrown() {
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("home", home.getPath());
        props.setProperty("fake.fail", "true");
        assertFalse(EmbeddedAgent.start(props));
        String error = System.getProperty(BootLauncher.PROP_ERROR);
        assertTrue(error.contains("fake start failure"), error);
        String printed = errText();
        assertTrue(printed.startsWith("[skillroy] EMBEDDED start failed: "), printed);
        assertEquals(1, printed.trim().split("\n").length, "one line");

        props.setProperty("fake.fail", "false");
        props.setProperty("home", tmp.resolve("empty").toString());
        tmp.resolve("empty").toFile().mkdirs();
        assertFalse(EmbeddedAgent.start(props));
        assertTrue(System.getProperty(BootLauncher.PROP_ERROR).contains("lib/*.jar"));

        props.setProperty("home", home.getPath());
        assertTrue(EmbeddedAgent.start(props));
        assertNull(System.getProperty(BootLauncher.PROP_ERROR), "a successful start clears the old error");

        AgentBootstrap.agentmain("enabled=true;t=" + tmp.resolve("missing.properties"), null);
        assertTrue(BootLauncher.isRunning(), "a failed second load does not disturb the running agent");
    }

    @Test
    void homeDefaultsToTheBootstrapJarDirectory() throws IOException {
        // Loaded from target/classes here, so there is no jar directory and the home option is required.
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> BootLauncher.resolveHome(null));
        assertTrue(e.getMessage().contains("home"));
        assertEquals(home.getAbsoluteFile(), BootLauncher.resolveHome(home.getPath()));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> BootLauncher.resolveHome(tmp.resolve("absent").toString()));
        assertEquals(1, BootLauncher.libJars(home).length);
    }
}
