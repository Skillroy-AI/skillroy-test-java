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

package ai.skillroy.testjava.agent.dist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static ai.skillroy.testjava.agent.dist.ChildJvm.list;
import static ai.skillroy.testjava.agent.dist.ChildJvm.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The assembled agent home in child JVMs (phase1-contracts.md 2): JAVAAGENT directly and through JAVA_TOOL_OPTIONS
 * with the main-class guard, the enable rule, EMBEDDED with a host context, agent.disable, and a module jar dropped
 * into a copy of the home. The ATTACH mode is exercised by the controller's tests.
 */
class AgentHomeIT {

    private static final String APP = "ai.skillroy.testjava.agent.dist.fixture.app.";
    private static final String PARK = APP + "ParkMain";

    private static File home;
    private static File work;
    private static File appJar;
    private static File probeJar;

    private final List<ChildJvm> children = new ArrayList<ChildJvm>();
    private File discoveryDir;

    @BeforeAll
    static void assemble() throws Exception {
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        home = new File(base, "target/skillroy-agent").getAbsoluteFile();
        assertTrue(new File(home, "skillroy-agent-bootstrap.jar").isFile(), "agent home not assembled: " + home);
        for (String lib : new String[] {"skillroy-protocol.jar", "skillroy-agent-api.jar", "skillroy-agent-core.jar"}) {
            assertTrue(new File(home, "lib/" + lib).isFile(), lib);
        }
        for (String module : new String[] {"skillroy-agent-swing.jar", "skillroy-agent-data.jar"}) {
            assertTrue(new File(home, "modules/" + module).isFile(), module);
        }
        work = new File(base, "target/it-work").getAbsoluteFile();
        deleteTree(work);
        assertTrue(work.mkdirs());
        appJar = new File(work, "app.jar");
        jar(appJar, packageClasses("ai.skillroy.testjava.agent.dist.fixture.app"));
        probeJar = new File(work, "probe.jar");
        Map<String, byte[]> probe = packageClasses("ai.skillroy.testjava.agent.dist.fixture.module");
        probe.put("META-INF/services/ai.skillroy.testjava.agent.api.AgentModule",
                "ai.skillroy.testjava.agent.dist.fixture.module.ProbeModule\n".getBytes(ChildJvm.UTF8));
        jar(probeJar, probe);
    }

    @BeforeEach
    void freshDiscoveryDir() throws IOException {
        discoveryDir = Files.createTempDirectory(work.toPath(), "disc-").toFile();
    }

    @AfterEach
    void stopChildren() {
        for (ChildJvm child : children) {
            child.close();
        }
    }

    private ChildJvm launch(List<String> jvmArgs, String classPath, String mainClass, List<String> args,
                            Map<String, String> env) throws IOException {
        List<String> all = list("-Djava.awt.headless=true");
        all.addAll(jvmArgs);
        ChildJvm child = ChildJvm.start(work, all, classPath, mainClass, args, env);
        children.add(child);
        return child;
    }

    private String javaagent(File agentHome, String options) {
        return "-javaagent:" + new File(agentHome, "skillroy-agent-bootstrap.jar").getPath() + "=" + options;
    }

    @Test
    @SuppressWarnings("unchecked")
    void javaagentModeServesTheChild() throws Exception {
        ChildJvm child = launch(list("-Dskillroy.agent.enabled=true",
                javaagent(home, "discoveryDir=" + discoveryDir.getPath())), appJar.getPath(), PARK,
                list(), Collections.<String, String>emptyMap());
        Map<String, Object> discovery = child.awaitDiscovery(discoveryDir);
        assertEquals(Long.valueOf(child.pid()), discovery.get("pid"));
        assertEquals("JAVAAGENT", discovery.get("loadMode"));
        assertEquals(PARK, discovery.get("mainCommand"));
        assertEquals(String.valueOf(discovery.get("port")), child.awaitLine("agentPort"),
                "the agent was serving before main ran");
        ChildJvm.Agent agent = new ChildJvm.Agent(discovery);
        Map<String, Object> ping = agent.ping("it-nonce-0123456789");
        assertEquals("it-nonce-0123456789", ping.get("nonce"));
        assertEquals(discovery.get("nonce"), ping.get("agentNonce"));
        assertEquals(Long.valueOf(child.pid()), ping.get("pid"));
        assertEquals(401, agent.statusWith("/schema", null), "no credentials");
        assertEquals(401, agent.statusWith("/schema", "Bearer " + agent.token), "the token itself is never accepted");
        Map<String, Object> info = agent.value("agent.info", null);
        assertEquals("JAVAAGENT", info.get("loadMode"));
        assertEquals(Boolean.TRUE, info.get("instrumentation"));
        assertTrue(String.valueOf(info.get("agentVersion")).matches("\\d+\\.\\d+\\.\\d+.*"),
                "the core reads its own resources through the agent loader: " + info.get("agentVersion"));
        assertEquals(home.getPath(), ((Map<String, Object>) info.get("options")).get("home"),
                "home defaults to the bootstrap jar's directory");
        Map<String, Object> caps = (Map<String, Object>) agent.value("agent.capabilities", null).get("capabilities");
        assertEquals("OK", ((Map<String, Object>) caps.get("jvm.instrumentation")).get("state"));
        Map<String, Object> jvm = agent.value("jvm.info", null);
        assertEquals(PARK, jvm.get("mainCommand"));
    }

    @Test
    void javaToolOptionsWithMainClassGuardStartsTheAgentInTheMatchingJvmOnly() throws Exception {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("JAVA_TOOL_OPTIONS", javaagent(home, "mainClassGuard=" + PARK + ";discoveryDir="
                + discoveryDir.getPath()));
        env.put("SKILLROY_AGENT_ENABLED", "true");
        ChildJvm matching = launch(list(), appJar.getPath(), PARK, list(), env);
        ChildJvm other = launch(list(), appJar.getPath(), APP + "OtherMain", list(), env);
        // Finding S17: a main class whose name merely starts with the guarded one is another JVM.
        ChildJvm helper = launch(list(), appJar.getPath(), PARK + "Helper", list(), env);
        Map<String, Object> discovery = matching.awaitDiscovery(discoveryDir);
        assertEquals("JAVAAGENT", new ChildJvm.Agent(discovery).ping().get("loadMode"));
        for (ChildJvm unguarded : new ChildJvm[] {other, helper}) {
            assertEquals("null", unguarded.awaitLine("agentPort"), "the guard kept the agent out of the other JVM");
            assertEquals("null", unguarded.awaitLine("agentError"));
            final String otherPrefix = "skillroy-" + unguarded.pid() + "-";
            for (String name : discoveryDir.list()) {
                assertFalse(name.startsWith(otherPrefix), name);
            }
        }
    }

    @Test
    void withoutTheEnableFlagNothingStarts() throws Exception {
        ChildJvm child = launch(list(javaagent(home, "enabled=true;discoveryDir=" + discoveryDir.getPath())),
                appJar.getPath(), PARK, list(), Collections.<String, String>emptyMap());
        assertEquals("null", child.awaitLine("agentPort"), "no port");
        assertEquals("null", child.awaitLine("agentError"), "not an error either");
        assertEquals(0, discoveryDir.list().length, "no discovery file");
        child.awaitOutput("parked");
        assertFalse(child.output().contains("[skillroy]"));
    }

    @Test
    void aFailedStartIsReportedOnceAndTheApplicationRunsOn() throws Exception {
        ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        try {
            ChildJvm child = launch(list("-Dskillroy.agent.enabled=true", javaagent(home, "discoveryDir="
                    + discoveryDir.getPath() + ";port=" + taken.getLocalPort())), appJar.getPath(), PARK, list(),
                    Collections.<String, String>emptyMap());
            assertEquals("null", child.awaitLine("agentPort"), "no agent");
            String error = child.awaitLine("agentError");
            assertTrue(error.startsWith("JAVAAGENT start failed: java.net.BindException"), error);
            child.awaitOutput("parked");   // main ran
            int lines = 0;
            for (String line : child.output().split("\\r?\\n")) {
                if (line.contains("[skillroy]")) {
                    lines++;
                    assertEquals("[skillroy] " + error, line);
                }
            }
            assertEquals(1, lines, child.output());
            assertFalse(child.output().contains("\tat "), "no stack trace on the host's console:\n" + child.output());
            assertEquals(0, discoveryDir.list().length, "no discovery or token file");
            assertTrue(child.process.isAlive());
        } finally {
            taken.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void embeddedModeHandsTheHostContextToAModule() throws Exception {
        String classPath = appJar.getPath() + File.pathSeparator
                + new File(home, "skillroy-agent-bootstrap.jar").getPath();
        ChildJvm child = launch(list(), classPath, APP + "EmbeddedMain",
                list(discoveryDir.getPath(), probeJar.getPath()), Collections.<String, String>emptyMap());
        assertEquals("true", child.awaitLine("embedded"), child.output());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        Map<String, Object> info = agent.value("agent.info", null);
        assertEquals("EMBEDDED", info.get("loadMode"));
        assertEquals(Boolean.FALSE, info.get("instrumentation"));
        assertTrue(String.valueOf(info.get("modules")).contains("probe"), String.valueOf(info.get("modules")));
        Map<String, Object> check = agent.value("probe.check", null);
        assertEquals("HostThing[the embedding host]", check.get("hostContext"));
        assertEquals("hello from the embedding host", check.get("greeting"));
        assertEquals(Boolean.TRUE, check.get("appClassVisible"));
        assertEquals(Boolean.TRUE, check.get("hostLoaderSeesApp"));
        Map<String, Object> caps = (Map<String, Object>) agent.value("agent.capabilities", null).get("capabilities");
        Map<String, Object> instrumentation = (Map<String, Object>) caps.get("jvm.instrumentation");
        assertEquals("UNAVAILABLE", instrumentation.get("state"));
        assertEquals("no Instrumentation in EMBEDDED mode", instrumentation.get("reason"));
    }

    @Test
    void embeddedStopOnAForbiddenThreadReturnsAtOnceAndStillShutsTheAgentDown() throws Exception {
        String classPath = appJar.getPath() + File.pathSeparator
                + new File(home, "skillroy-agent-bootstrap.jar").getPath();
        ChildJvm child = launch(list(), classPath, APP + "EmbeddedStopMain",
                list(discoveryDir.getPath(), probeJar.getPath()), Collections.<String, String>emptyMap());
        assertEquals("true", child.awaitLine("embedded"), child.output());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        assertEquals("EMBEDDED", agent.ping().get("loadMode"));
        child.process.getOutputStream().write("stop\n".getBytes(ChildJvm.UTF8));
        child.process.getOutputStream().flush();
        assertEquals("true", child.awaitLine("uninstalled"),
                "the module is uninstalled although the stopping thread could not wait for it:\n" + child.output());
        assertEquals("null", child.awaitLine("portAfterStop"));
        assertTrue(agent.portClosed());
        assertEquals(0, discoveryDir.list().length, "discovery and token files removed");
        assertTrue(child.process.isAlive(), "the application keeps running");
        assertFalse(child.output().contains("[skillroy]"), child.output());
    }

    @Test
    void agentDisableLeavesTheChildRunning() throws Exception {
        ChildJvm child = launch(list("-Dskillroy.agent.enabled=true",
                javaagent(home, "discoveryDir=" + discoveryDir.getPath())), appJar.getPath(), PARK,
                list(), Collections.<String, String>emptyMap());
        final ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        Map<String, Object> value = agent.value("agent.disable", map("_meta", map("confirm", Boolean.TRUE)));
        assertEquals(map("stopping", Boolean.TRUE), value);
        long deadline = System.currentTimeMillis() + ChildJvm.START_TIMEOUT_MS;
        while (!(agent.portClosed() && discoveryDir.list().length == 0)) {
            if (System.currentTimeMillis() > deadline) {
                fail("agent did not stop: port closed=" + agent.portClosed() + ", files="
                        + java.util.Arrays.toString(discoveryDir.list()));
            }
            Thread.sleep(50);
        }
        assertTrue(child.process.isAlive(), "the application keeps running");
        assertFalse(child.output().contains("Exception"), child.output());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aModuleJarDroppedIntoACopyOfTheHomeSeesTheApiAndTheApplication() throws Exception {
        File copy = new File(work, "home-copy-" + System.nanoTime());
        copyTree(home.toPath(), copy.toPath());
        Files.copy(probeJar.toPath(), new File(copy, "modules/zz-probe.jar").toPath());
        ChildJvm child = launch(list("-Dskillroy.agent.enabled=true",
                javaagent(copy, "discoveryDir=" + discoveryDir.getPath())), appJar.getPath(), PARK,
                list(), Collections.<String, String>emptyMap());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        Map<String, Object> probe = null;
        for (Object module : (List<Object>) agent.value("agent.info", null).get("modules")) {
            Map<String, Object> m = (Map<String, Object>) module;
            if ("probe".equals(m.get("id"))) {
                probe = m;
            }
        }
        assertEquals("INSTALLED", probe == null ? null : probe.get("state"));
        Map<String, Object> check = agent.value("probe.check", null);
        assertEquals("ai.skillroy.testjava.agent.core.ModuleClassLoader", check.get("moduleLoader"));
        assertEquals("ai.skillroy.testjava.agent.bootstrap.AgentClassLoader", check.get("apiLoader"),
                "the agent API comes from the agent loader");
        assertEquals(Boolean.TRUE, check.get("appClassVisible"));
        assertEquals(Boolean.TRUE, check.get("hostLoaderSeesApp"), "the application class is the host's own");
        assertEquals("null", check.get("hostContext"));
    }

    /**
     * Finding S9 through the real option merge: operator-set {@code port}, {@code token} and {@code tokenFile}
     * system properties survive a stop, so the next start in the same JVM binds the same port and never copies the
     * operator's token into a file of its own.
     */
    @Test
    void operatorSystemPropertiesSurviveAStopAndTheNextStartUsesThemAgain() throws Exception {
        java.net.ServerSocket probe = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        int port = probe.getLocalPort();
        probe.close();
        File tokenFile = new File(work, "operator-" + System.nanoTime() + ".token");
        Files.write(tokenFile.toPath(), "operator-token".getBytes(ChildJvm.UTF8));
        ChildJvm child = launch(list("-Dskillroy.agent.enabled=true", "-Dskillroy.agent.port=" + port,
                "-Dskillroy.agent.token=operator-token", "-Dskillroy.agent.tokenFile=" + tokenFile.getPath(),
                "-Dskillroy.agent.discoveryDir=" + discoveryDir.getPath(),
                "-Dskillroy.agent.home=" + home.getPath()),
                appJar.getPath() + File.pathSeparator + new File(home, "skillroy-agent-bootstrap.jar").getPath(),
                APP + "RestartMain", list(), Collections.<String, String>emptyMap());
        assertEquals("true," + port, child.awaitLine("first"));
        assertEquals(port + "," + tokenFile.getPath() + ",null", child.awaitLine("afterStop"),
                "port and tokenFile restored, loadMode cleared");
        assertEquals("true," + port, child.awaitLine("second"), "the second start binds the operator's port");
        final String own = "skillroy-" + child.pid() + "-";
        for (String name : discoveryDir.list()) {
            assertFalse(name.startsWith(own) && name.endsWith(".token"), "no copy of the operator's token: " + name);
        }
        assertEquals("operator-token", new String(Files.readAllBytes(tokenFile.toPath()), ChildJvm.UTF8));
    }

    /**
     * Finding S34 with the isolated agent loader: agent threads, and threads host code starts on them, carry the
     * host's loader as context loader, so that after {@code agent.disable} no live thread pins the agent loader.
     */
    @Test
    @SuppressWarnings("unchecked")
    void agentThreadsCarryTheHostLoaderSoNoThreadPinsTheAgentLoaderAfterDisable() throws Exception {
        File copy = new File(work, "home-copy-" + System.nanoTime());
        copyTree(home.toPath(), copy.toPath());
        Files.copy(probeJar.toPath(), new File(copy, "modules/zz-probe.jar").toPath());
        ChildJvm child = launch(list("-Dskillroy.agent.enabled=true",
                javaagent(copy, "discoveryDir=" + discoveryDir.getPath())), appJar.getPath(),
                APP + "ContextLoaderMain", list(), Collections.<String, String>emptyMap());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        Map<String, Object> loaders = agent.value("probe.contextLoader", null);
        assertEquals(Boolean.TRUE, loaders.get("workerIsHost"), String.valueOf(loaders));
        assertEquals(Boolean.TRUE, loaders.get("spawnedIsHost"), String.valueOf(loaders));
        assertFalse(String.valueOf(loaders.get("workerLoader")).endsWith("AgentClassLoader"));
        agent.value("agent.disable", map("_meta", map("confirm", Boolean.TRUE)));
        assertEquals("", child.awaitLine("agentLoaderThreads"),
                "no live thread (the host's own spawned one included) has the agent loader as context loader");
        assertTrue(child.process.isAlive());
    }

    static Map<String, byte[]> packageClasses(String packageName) throws IOException, URISyntaxException {
        String path = packageName.replace('.', '/');
        URL url = AgentHomeIT.class.getClassLoader().getResource(path);
        assertTrue(url != null && "file".equals(url.getProtocol()), "compiled test package " + packageName);
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        File[] files = new File(url.toURI()).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".class")) {
                    entries.put(path + "/" + f.getName(), Files.readAllBytes(f.toPath()));
                }
            }
        }
        return entries;
    }

    static void jar(File jar, Map<String, byte[]> entries) throws IOException {
        JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    static void copyTree(final Path from, final Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(File dir) throws IOException {
        if (!dir.exists()) {
            return;
        }
        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
