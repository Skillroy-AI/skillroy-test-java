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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start and close of the hardening wave: operator properties restored on close (finding S9), a close that
 * overlaps the start (S32), stale files of the agent's own pid (amendment A23), and nothing on the host's calling
 * thread that could look up a host name (S20, S29).
 */
class StartHardeningTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] PUBLISHED = {"port", "nonce", "tokenFile", "protocol", "loadMode", "discoveryFile",
        "session", "token", "error"};

    @TempDir
    Path tmp;

    private AgentRuntime runtime;

    @BeforeEach
    void reset() {
        TestState.reset();
        clearAgentProperties();
    }

    @AfterEach
    void stop() {
        TestState.gate.countDown();
        TestState.READY.set(true);
        AgentRuntime.startTimeoutMs = AgentRuntime.START_TIMEOUT_MS;
        if (runtime != null) {
            runtime.close();
        }
        clearAgentProperties();
    }

    private static void clearAgentProperties() {
        for (String name : PUBLISHED) {
            System.clearProperty(Protocol.PROP_PREFIX + name);
        }
    }

    /** The skillroy.agent.* system properties as options, as the bootstrap's merge reads them (enabled added). */
    private Map<String, String> mergedFromSystemProperties() {
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(Protocol.PROP_PREFIX)) {
                String key = name.substring(Protocol.PROP_PREFIX.length());
                if (!Arrays.asList("enabled", "error", "protocol", "loadMode", "discoveryFile", "session")
                        .contains(key)) {
                    options.put(key, System.getProperty(name));
                }
            }
        }
        options.put("enabled", "true");
        options.put("discoveryDir", tmp.resolve("disc").toString());
        return options;
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static String prop(String name) {
        return System.getProperty(Protocol.PROP_PREFIX + name);
    }

    // ---- S9 ----

    @Test
    void operatorSetPortTokenFileAndNonceSurviveACloseAndAreUsedByTheNextStart() throws Exception {
        int port = freePort();
        File tokenFile = tmp.resolve("operator.token").toFile();
        Files.write(tokenFile.toPath(), "operator-secret-0123456789".getBytes(UTF8));
        System.setProperty(Protocol.PROP_PREFIX + "port", String.valueOf(port));
        System.setProperty(Protocol.PROP_PREFIX + "token", "operator-secret-0123456789");
        System.setProperty(Protocol.PROP_PREFIX + "tokenFile", tokenFile.getPath());
        System.setProperty(Protocol.PROP_PREFIX + "nonce", "operator-nonce");

        runtime = AgentRuntime.start(mergedFromSystemProperties(), null, getClass().getClassLoader(), "EMBEDDED",
                null);
        assertEquals(port, runtime.port());
        assertEquals("EMBEDDED", prop("loadMode"));
        runtime.close();
        runtime = null;
        assertEquals(String.valueOf(port), prop("port"), "restored, not cleared");
        assertEquals(tokenFile.getAbsolutePath(), new File(prop("tokenFile")).getAbsolutePath());
        assertEquals("operator-nonce", prop("nonce"));
        for (String cleared : new String[] {"loadMode", "protocol", "discoveryFile", "session"}) {
            assertNull(prop(cleared), cleared);
        }

        runtime = AgentRuntime.start(mergedFromSystemProperties(), null, getClass().getClassLoader(), "EMBEDDED",
                null);
        assertEquals(port, runtime.port(), "the second start binds the operator's port again");
        assertEquals(tokenFile.getAbsolutePath(), runtime.tokenFile());
        assertEquals("operator-nonce", runtime.nonce());
        File[] tokens = tmp.resolve("disc").toFile().listFiles();
        for (File f : tokens == null ? new File[0] : tokens) {
            assertFalse(f.getName().endsWith(".token"), "no copy of the operator's token: " + f);
        }
        assertEquals("operator-secret-0123456789", new String(Files.readAllBytes(tokenFile.toPath()), UTF8));
    }

    @Test
    void aStartThatFailsBeforePublishingLeavesTheOperatorsPropertiesAlone() throws Exception {
        ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        try {
            String port = String.valueOf(taken.getLocalPort());
            System.setProperty(Protocol.PROP_PREFIX + "port", port);
            System.setProperty(Protocol.PROP_PREFIX + "nonce", "operator-nonce");
            assertThrows(IOException.class, () -> AgentRuntime.start(mergedFromSystemProperties(), null,
                    getClass().getClassLoader(), "EMBEDDED", null));
            assertEquals(port, prop("port"));
            assertEquals("operator-nonce", prop("nonce"));
        } finally {
            taken.close();
        }
    }

    // ---- S32 ----

    @Test
    void aPreInterruptedCallerLeavesNoListenerAndNoThreadBehind() throws Exception {
        final Set<String> before = new HashSet<String>(AgentHarness.skillroyThreads());
        int port = freePort();
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("discoveryDir", tmp.toString());
        options.put("port", String.valueOf(port));
        final AtomicInteger released = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> AgentRuntime.start(options, null,
                    getClass().getClassLoader(), "EMBEDDED", null, new Runnable() {
                        @Override
                        public void run() {
                            released.incrementAndGet();
                        }
                    }));
        } finally {
            Thread.interrupted();
        }
        AgentHarness.await("no agent thread left", 15000, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(before.containsAll(AgentHarness.skillroyThreads()));
            }
        });
        assertTrue(AgentHarness.portClosed(port), "nothing listens on the port");
        AgentHarness.await("the release hook ran once", 5000, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(released.get() == 1);
            }
        });
        File[] files = tmp.toFile().listFiles();
        for (File f : files == null ? new File[0] : files) {
            assertFalse(f.getName().endsWith(".json"), "no discovery file: " + f);
        }
    }

    @Test
    void aStartTimeoutDuringASlowInstallKeepsTheLoadersUntilTheInstallReturns() throws Exception {
        AgentRuntime.startTimeoutMs = 1000L;
        File home = tmp.resolve("home").toFile();
        File modules = new File(home, "modules");
        assertTrue(modules.mkdirs());
        TestJars.moduleJar(new File(modules, "10-jarmod.jar"), "ai.skillroy.testjava.agent.core.jarfixture.JarModule");
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("discoveryDir", tmp.resolve("disc").toString());
        options.put("home", home.getPath());
        options.put("coretest.slowInstall", "stubborn");
        final AtomicInteger released = new AtomicInteger();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AgentRuntime.start(options, null,
                getClass().getClassLoader(), "EMBEDDED", null, new Runnable() {
                    @Override
                    public void run() {
                        released.incrementAndGet();
                    }
                }));
        assertTrue(e.getMessage().contains("did not finish within 1000 ms"), e.getMessage());
        assertEquals(1, TestState.JAR_LOADERS.size(), "the jar module installed before the slow one");
        URLClassLoader jarLoader = (URLClassLoader) TestState.JAR_LOADERS.get(0);
        Thread.sleep(300);
        assertNotNull(jarLoader.findResource(ModuleLoader.SERVICE_FILE),
                "the module loaders stay open while the install still runs");
        assertEquals(0, released.get(), "and so does the agent loader (the release hook has not run)");

        TestState.gate.countDown();
        AgentHarness.await("released once the start thread ended", 15000, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(released.get() == 1);
            }
        });
        assertNull(jarLoader.findResource(ModuleLoader.SERVICE_FILE), "closed after the install returned");
        assertTrue(TestState.UNINSTALLED.contains("coretest"), "the late install was undone at once");
    }

    // ---- A23 ----

    @Test
    void staleFilesOfAnEarlierJvmWithThisPidAreDeletedOthersAreKept() throws Exception {
        Path dir = tmp.resolve("disc");
        SecureFiles.prepareDirectory(dir);
        long pid = JvmIds.pid();
        long otherPid = pid + 1;
        List<Path> stale = Arrays.asList(dir.resolve("skillroy-" + pid + "-1.json"),
                dir.resolve("skillroy-" + pid + "-1.token"));
        List<Path> kept = new ArrayList<Path>(Arrays.asList(dir.resolve("skillroy-" + otherPid + "-1.json"),
                dir.resolve("skillroy-" + otherPid + "-1.token"), dir.resolve("notes.txt"),
                dir.resolve("skillroy-" + pid + "-1.json.bak"), dir.resolve("skillroy-" + pid + "-x.json")));
        for (Path p : stale) {
            Files.write(p, "old".getBytes(UTF8));
        }
        for (Path p : kept) {
            Files.write(p, "keep".getBytes(UTF8));
        }
        Path directory = dir.resolve("skillroy-" + pid + "-2.json");
        Files.createDirectory(directory);
        kept.add(directory);

        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("discoveryDir", dir.toString());
        runtime = AgentRuntime.start(options, null, getClass().getClassLoader(), "EMBEDDED", null);
        for (Path p : stale) {
            assertFalse(Files.exists(p), "deleted: " + p);
        }
        for (Path p : kept) {
            assertTrue(Files.exists(p), "kept: " + p);
        }
        assertTrue(new File(runtime.discoveryFile()).isFile(), "this start's own file is written");
        assertTrue(String.valueOf(runtime.context().log().tail(200)).contains("stale file"));
    }

    // ---- S20, S29 ----

    @Test
    void thePidIsResolvedOnTheStartThreadWithoutTheHostNameLookup() throws Exception {
        JvmIds.lastPidCaller = null;
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("discoveryDir", tmp.toString());
        runtime = AgentRuntime.start(options, null, getClass().getClassLoader(), "EMBEDDED", null);
        assertNotNull(JvmIds.lastPidCaller);
        assertEquals("skillroy-start", JvmIds.lastPidCaller.getName(), "never the host's calling thread");
        String name = ManagementFactory.getRuntimeMXBean().getName();
        long expected = Long.parseLong(name.substring(0, name.indexOf('@')));
        assertEquals(expected, JvmIds.pidWithoutNameLookup(), "found before the getName() fallback");
        assertEquals(expected, runtime.pid());
    }

    @Test
    void aJavaagentStartWarnsWhenTheApplicationChangesANetworkStackPropertyLater() throws Exception {
        String key = "java.net.preferIPv6Addresses";
        String saved = System.getProperty(key);
        try {
            Map<String, String> options = new LinkedHashMap<String, String>();
            options.put("enabled", "true");
            options.put("discoveryDir", tmp.toString());
            options.put("coretest.readiness", "manual");
            runtime = AgentRuntime.start(options, null, getClass().getClassLoader(), "JAVAAGENT", null);
            System.setProperty(key, "true".equals(saved) ? "false" : "true");
            TestState.READY.set(true);
            AgentHarness.await("ready", 30000, new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return Boolean.valueOf(runtime.isReady());
                }
            });
            String log = String.valueOf(runtime.context().log().tail(200));
            assertTrue(log.contains("the application set " + key), log);
        } finally {
            if (saved == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, saved);
            }
        }
    }
}
