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

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.skillroy.testjava.agent.dist.ChildJvm.list;
import static ai.skillroy.testjava.agent.dist.ChildJvm.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a target JVM keeps from the agent, in child JVMs: its headless decision and an AWT-free life when it has no UI
 * (contract amendment A4), its secrets (A5), and a discovery directory nobody else controls (A3). The agent is
 * loaded with {@code -javaagent}, before the application's {@code main}, from a home without modules, so that only
 * the core is judged.
 */
class HostSafetyIT {

    private static final String APP = "ai.skillroy.testjava.agent.dist.fixture.app.";
    /** A class-loading line of {@code -verbose:class}, Java 8 ({@code [Loaded x from y]}) or later. */
    private static final Pattern AWT_CLASS_LOAD = Pattern.compile(
            "(?:\\[Loaded |\\[class,load\\] )((?:java\\.awt|sun\\.awt|sun\\.lwawt|sun\\.java2d|javax\\.swing)\\.\\S+)");

    private static ItWork it;
    private static File coreOnly;

    @BeforeAll
    static void prepare() throws Exception {
        it = ItWork.prepare("host-safety");
        coreOnly = it.coreOnlyHome();
    }

    @AfterEach
    void stopChildren() {
        it.stopChildren();
    }

    private static List<String> awtClassesLoaded(String output) {
        List<String> loaded = new ArrayList<String>();
        Matcher m = AWT_CLASS_LOAD.matcher(output);
        while (m.find()) {
            loaded.add(m.group(1));
        }
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private static List<String> threadNames(ChildJvm.Agent agent) throws Exception {
        List<String> names = new ArrayList<String>();
        for (Object t : (List<Object>) agent.value("jvm.threads", map("stacks", Boolean.TRUE)).get("threads")) {
            names.add(String.valueOf(((Map<String, Object>) t).get("name")));
        }
        return names;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTargetWithoutAUiNeverHasAwtStartedOrItsHeadlessDecisionTakenByTheAgent() throws Exception {
        File discoveryDir = it.newDir("disc-");
        ChildJvm child = it.launch(list("-Djava.awt.headless=false", "-verbose:class",
                "-Dskillroy.agent.enabled=true", "-Dskillroy.it.password=hunter2-secret",
                "-Dskillroy.it.sessionKey=k123-secret",
                ItWork.javaagent(coreOnly, "discoveryDir=" + discoveryDir.getPath() + ";redact=sessionKey")),
                it.appJar.getPath(), APP + "HeadlessMain", list(), Collections.<String, String>emptyMap());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        assertEquals("JAVAAGENT", agent.ping().get("loadMode"));

        // Everything a controller does first, before the application has touched AWT.
        List<Object> responses = new ArrayList<Object>();
        Map<String, Object> info = agent.value("agent.info", null);
        responses.add(info);
        Map<String, Object> jvm = agent.value("jvm.info", null);
        responses.add(jvm);
        assertEquals("false", jvm.get("headless"), "the property as launched, as a string");
        List<String> names = Arrays.asList("java.awt.headless", "skillroy.it.password", "skillroy.it.sessionKey");
        Map<String, Object> props = (Map<String, Object>) agent.value("jvm.properties", map("names", names))
                .get("properties");
        responses.add(props);
        assertEquals("false", props.get("java.awt.headless"));
        assertEquals("<redacted>", props.get("skillroy.it.password"));
        assertEquals("<redacted>", props.get("skillroy.it.sessionKey"));
        responses.add(agent.value("agent.capabilities", map("reprobe", Boolean.TRUE)));
        responses.add(agent.value("agent.log", map("n", Long.valueOf(500))));
        responses.add(agent.value("evt.query", null));
        responses.add(agent.value("trace.mark", map("label", "before-awt")));
        responses.add(agent.rpc("sync.await", map("topics", "no.such.topic", "since", Long.valueOf(0),
                "timeoutMs", Long.valueOf(100))));
        for (String path : new String[] {"/schema", "/capabilities"}) {
            agent.get(path);
        }
        List<String> threads = threadNames(agent);
        for (String name : threads) {
            assertFalse(name.startsWith("AWT-"), "an AWT thread exists in a target without a UI: " + threads);
        }
        assertEquals(Collections.<String>emptyList(), awtClassesLoaded(child.output()),
                "the agent loaded AWT classes in a target that never used AWT");

        List<Object> arguments = (List<Object>) jvm.get("inputArguments");
        assertTrue(arguments.contains("-Dskillroy.it.password=<redacted>"), String.valueOf(arguments));
        assertTrue(arguments.contains("-Dskillroy.it.sessionKey=<redacted>"), String.valueOf(arguments));
        String text = Json.write(responses);
        assertFalse(text.contains("hunter2-secret"), "a secret left the JVM");
        assertFalse(text.contains("k123-secret"), "a secret matched by the redact option left the JVM");

        // Now the application decides, programmatically, before its first AWT use.
        child.process.getOutputStream().write("go\n".getBytes(ChildJvm.UTF8));
        child.process.getOutputStream().flush();
        assertEquals("true", child.awaitLine("headless"),
                "the application's own headless decision must hold:\n" + child.output());
        assertEquals("true", agent.value("jvm.info", null).get("headless"));
        for (String name : threadNames(agent)) {
            assertFalse(name.startsWith("AWT-EventQueue"), name);
        }
    }

    /**
     * Security findings S12 and S16: an agent loaded with {@code -javaagent} starts its control server before the
     * application's {@code main}, and must not initialise {@code java.util.logging} there, or a manager and
     * configuration the application selects at the top of {@code main} would be silently ignored. Meaningful on a Java
     * 8 target ({@code -Dskillroy.it.targetJava}), where the JDK's HTTP server did exactly that. The test checks the
     * effect (the application's manager and configuration are in force), not class loading: on later JDKs
     * {@code ManagementFactory} loads the {@code LogManager} class without initialising it, which is harmless.
     */
    @Test
    void theApplicationStillChoosesItsOwnLogManagerAfterAnAgentStartedBeforeMain() throws Exception {
        File discoveryDir = it.newDir("disc-");
        File config = new File(it.newDir("jul-"), "logging.properties");
        Files.write(config.toPath(), "fixture.marker=from-the-application\n".getBytes(ChildJvm.UTF8));
        ChildJvm child = it.launch(list("-Dskillroy.agent.enabled=true",
                ItWork.javaagent(coreOnly, "discoveryDir=" + discoveryDir.getPath())),
                it.appJar.getPath(), APP + "JulMain", list(config.getPath()), Collections.<String, String>emptyMap());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        assertEquals("JAVAAGENT", agent.ping().get("loadMode"));
        assertTrue(!"null".equals(child.awaitLine("agentPort")), "the agent served before main");
        assertEquals(APP + "FixtureLogManager", child.awaitLine("logManager"), child.output());
        assertEquals("from-the-application", child.awaitLine("logConfig"), child.output());
    }

    /**
     * Phase 7 (phase7-contracts.md sections 2.5, 3.4, 13.2 item 11; D290): with the full home ({@code modules/} on), an
     * AWT application and the display, the {@code -verbose:class} scan shows no {@code java.awt.Robot} loaded without
     * the agent option {@code robot}; with it, none before the application started AWT (never at install), one after
     * the Swing module confirmed AWT (the capability probe constructs it), and none in a headless JVM.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFullHomeLoadsNoRobotWithoutTheOptionAndWithItOnlyAfterTheApplicationStartedAwt() throws Exception {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "needs a display: run under "
                + "ci/run-in-linux.sh with -Dskillroy.headless=false");
        // without the option
        File plain = it.newDir("disc-");
        ChildJvm without = it.launch(list("-Djava.awt.headless=false", "-verbose:class",
                "-Dskillroy.agent.enabled=true", ItWork.javaagent(it.home, "discoveryDir=" + plain.getPath())),
                it.appJar.getPath(), APP + "SwingMain", list(), Collections.<String, String>emptyMap());
        ChildJvm.Agent a = new ChildJvm.Agent(without.awaitDiscovery(plain));
        go(without);
        assertEquals("true", without.awaitLine("shown"));
        Map<String, Object> robot = awaitCapability(a, "swing.pointer.robot", "robot: not enabled");
        assertEquals("UNAVAILABLE", robot.get("state"));
        assertEquals("OK", awaitCapability(a, "swing.tree", null).get("state"), "the module confirmed AWT");
        assertFalse(robotLoaded(without.output()), "no java.awt.Robot without the option");

        // with the option: nothing before the application starts AWT, the probe's Robot after
        File withDir = it.newDir("disc-");
        ChildJvm with = it.launch(list("-Djava.awt.headless=false", "-verbose:class",
                "-Dskillroy.agent.enabled=true", ItWork.javaagent(it.home, "discoveryDir=" + withDir.getPath()
                        + ";robot=true")), it.appJar.getPath(), APP + "SwingMain", list(),
                Collections.<String, String>emptyMap());
        ChildJvm.Agent b = new ChildJvm.Agent(with.awaitDiscovery(withDir));
        assertEquals("true", String.valueOf(((Map<String, Object>) b.value("agent.info", null).get("options"))
                .get("robot")), "agent.info reports the option");
        assertEquals("AWT not started by the application yet",
                awaitCapability(b, "swing.pointer.robot", "AWT not started").get("reason"));
        assertFalse(robotLoaded(with.output()), "never at install, never before checkAwt");
        go(with);
        assertEquals("true", with.awaitLine("shown"));
        Map<String, Object> declared = awaitCapability(b, "swing.pointer.robot", "");
        assertTrue("OK".equals(declared.get("state")) || String.valueOf(declared.get("reason")).startsWith("robot: "),
                "declared by the probe: " + declared);
        assertTrue(robotLoaded(with.output()), "the capability probe constructed the Robot after checkAwt");

        // with the option in a headless JVM
        File headlessDir = it.newDir("disc-");
        ChildJvm headless = it.launch(list("-verbose:class", "-Dskillroy.agent.enabled=true",
                ItWork.javaagent(it.home, "discoveryDir=" + headlessDir.getPath() + ";robot=true")),
                it.appJar.getPath(), APP + "SwingMain", list("headless"), Collections.<String, String>emptyMap());
        ChildJvm.Agent c = new ChildJvm.Agent(headless.awaitDiscovery(headlessDir));
        go(headless);
        assertEquals("headless", headless.awaitLine("awt"));
        assertEquals("headless", awaitCapability(c, "swing.pointer.robot", "headless").get("reason"));
        assertFalse(robotLoaded(headless.output()), "never in a headless JVM");
    }

    private static void go(ChildJvm child) throws Exception {
        child.process.getOutputStream().write("go\n".getBytes(ChildJvm.UTF8));
        child.process.getOutputStream().flush();
    }

    private static boolean robotLoaded(String output) {
        return Pattern.compile("(?:\\[Loaded |\\[class,load\\] )java\\.awt\\.Robot[ \\]]").matcher(output).find();
    }

    /**
     * Polls {@code agent.capabilities} until the capability's reason starts with {@code reasonPrefix} (any state when
     * the prefix is empty and the waiting reason is gone; the state {@code OK} when it is null).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> awaitCapability(ChildJvm.Agent agent, String id, String reasonPrefix)
            throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> caps = (Map<String, Object>) agent.value("agent.capabilities", null)
                    .get("capabilities");
            last = (Map<String, Object>) caps.get(id);
            if (last != null) {
                String reason = String.valueOf(last.get("reason"));
                if (reasonPrefix == null ? "OK".equals(last.get("state"))
                        : reasonPrefix.isEmpty() ? !reason.startsWith("AWT not started")
                        : reason.startsWith(reasonPrefix)) {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("capability " + id + " never reached " + reasonPrefix + ": " + last);
    }

    @Test
    void theDefaultDiscoveryDirectoryIsPerUserAndOwnerOnly() throws Exception {
        File tmpdir = it.newDir("tmpdir-");
        ChildJvm child = it.launch(list("-Djava.io.tmpdir=" + tmpdir.getPath(), "-Dskillroy.agent.enabled=true",
                ItWork.javaagent(coreOnly, "")), it.appJar.getPath(), APP + "ParkMain", list(),
                Collections.<String, String>emptyMap());
        File expected = new File(tmpdir, "skillroy-" + System.getProperty("user.name")
                .replaceAll("[^A-Za-z0-9._-]", ""));
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(expected));
        assertEquals(Long.valueOf(child.pid()), agent.ping().get("pid"));
        assertEquals("null", child.awaitLine("agentError"));
        Path dir = expected.toPath();
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        }
        String tokenFile = String.valueOf(agent.discovery.get("tokenFile"));
        assertEquals(expected.getPath(), new File(tokenFile).getParent());
    }

    @Test
    void aDiscoveryDirectoryOthersMayWriteToIsRefusedAndReported() throws Exception {
        File tmpdir = it.newDir("tmpdir-");
        Assumptions.assumeTrue(tmpdir.toPath().getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions only");
        File planted = new File(tmpdir, "skillroy-" + System.getProperty("user.name")
                .replaceAll("[^A-Za-z0-9._-]", ""));
        assertTrue(planted.mkdir());
        Files.setPosixFilePermissions(planted.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"));
        ChildJvm child = it.launch(list("-Djava.io.tmpdir=" + tmpdir.getPath(), "-Dskillroy.agent.enabled=true",
                ItWork.javaagent(coreOnly, "")), it.appJar.getPath(), APP + "ParkMain", list(),
                Collections.<String, String>emptyMap());
        assertEquals("null", child.awaitLine("agentPort"), "no agent");
        String error = child.awaitLine("agentError");
        assertTrue(error.startsWith("JAVAAGENT start failed: java.io.IOException: cannot write the token file"),
                error);
        assertTrue(error.contains("writable by group or others"), error);
        child.awaitOutput("parked");   // the application runs on
        assertEquals(0, planted.list().length, "nothing was written into the planted directory");
        int lines = 0;
        for (String line : child.output().split("\\r?\\n")) {
            if (line.startsWith("[skillroy]")) {
                lines++;
            }
        }
        assertEquals(1, lines, child.output());
    }
}
