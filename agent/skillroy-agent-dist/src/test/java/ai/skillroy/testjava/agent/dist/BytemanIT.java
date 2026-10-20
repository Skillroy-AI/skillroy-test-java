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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Topics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static ai.skillroy.testjava.agent.dist.ChildJvm.list;
import static ai.skillroy.testjava.agent.dist.ChildJvm.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The optional Byteman module in the assembled home (phase6-contracts.md 7.6, 13.1 item 13): a child JVM on the target
 * Java with the agent through {@code -javaagent} and {@code optionalModules=skillroy-agent-byteman;byteman.jar=<path>},
 * the jar being the Byteman on this test's class path (test scope, never in the home). A rule checked before Byteman
 * is loaded, a confirm and a read-only refusal by the dispatcher, forbidden rules and a type error refused (the message
 * in the reply, not on the console), a rule submitted, fired into {@code rule.fired} with its bindings, listed and
 * unloaded with {@code verified: OK}; the child's listening sockets before the first submit and after the unload equal
 * and hold exactly the agent's port; after {@code agent.disable} and a re-attach the module reuses the parked
 * {@code Retransformer}; a cut-short uninstall (the test seam) leaves a rule the next instance refuses by name while
 * the rule keeps running its action with no {@code rule.fired}; after the last disable no agent thread is left.
 * Phase 8 (phase8-contracts.md 6.5, 6.7; section 13.2 item 23): the agent option {@code hooks.deny} without the hooks
 * module refuses a rule on the denied class before Byteman loads, and an invalid pattern fails the module naming the
 * key.
 */
class BytemanIT {

    private static final String TOY = "ai.skillroy.testjava.fixture.byteman.LendingToyMain";

    private static File home;
    private static File work;
    private static File appJar;
    private static File keeperJar;
    private static String bytemanJar;

    private final List<ChildJvm> children = new ArrayList<ChildJvm>();
    private File discoveryDir;

    @BeforeAll
    static void assemble() throws Exception {
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        home = new File(base, "target/skillroy-agent").getAbsoluteFile();
        for (String jar : new String[] {"optional/skillroy-agent-byteman.jar", "optional/skillroy-agent-byteman-sys.jar"}) {
            assertTrue(new File(home, jar).isFile(), "agent home without " + jar + ": " + home);
        }
        work = new File(base, "target/it-work-byteman").getAbsoluteFile();
        AgentHomeIT.deleteTree(work);
        assertTrue(work.mkdirs());
        Map<String, byte[]> classes = AgentHomeIT.packageClasses("ai.skillroy.testjava.fixture.byteman");
        appJar = new File(work, "toy.jar");
        AgentHomeIT.jar(appJar, classes);
        keeperJar = new File(work, "keeper.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"),
                "ai.skillroy.testjava.fixture.byteman.InstrumentationKeeper");
        // A real attach hands agentmain the bootstrap's own Instrumentation, with the bootstrap manifest's rights.
        manifest.getMainAttributes().put(new Attributes.Name("Can-Retransform-Classes"), "true");
        manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "true");
        JarOutputStream out = new JarOutputStream(new FileOutputStream(keeperJar), manifest);
        try {
            String keeper = "ai/skillroy/testjava/fixture/byteman/InstrumentationKeeper.class";
            out.putNextEntry(new JarEntry(keeper));
            out.write(classes.get(keeper));
            out.closeEntry();
        } finally {
            out.close();
        }
        // The Byteman jar of this test's class path (test scope): the code source of its Main, loaded uninitialised.
        Class<?> main = Class.forName("org.jboss.byteman.agent.Main", false, BytemanIT.class.getClassLoader());
        bytemanJar = new File(main.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
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

    private String options(String extra) {
        return "discoveryDir=" + discoveryDir.getPath() + ";optionalModules=skillroy-agent-byteman;byteman.jar="
                + bytemanJar + (extra.isEmpty() ? "" : ";" + extra);
    }

    private ChildJvm launch(String extra) throws IOException {
        ChildJvm child = ChildJvm.start(work, list("-Djava.awt.headless=true", "-Dskillroy.agent.enabled=true",
                "-javaagent:" + new File(home, "skillroy-agent-bootstrap.jar").getPath() + "=" + options(extra),
                "-javaagent:" + keeperJar.getPath()), appJar.getPath(), TOY, list(),
                Collections.<String, String>emptyMap());
        children.add(child);
        return child;
    }

    /** Sends one command line to the toy and returns the value of the next {@code key=} line it prints. */
    private static String command(ChildJvm child, String line, String key) throws Exception {
        int mark = child.output().length();
        OutputStream in = child.process.getOutputStream();
        in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
        long deadline = System.currentTimeMillis() + ChildJvm.START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String out = child.output();
            if (out.length() > mark) {
                for (String l : out.substring(mark).split("\\r?\\n")) {
                    if (l.startsWith(key + "=")) {
                        return l.substring(key.length() + 1);
                    }
                }
            }
            if (!child.process.isAlive()) {
                fail("child exited:\n" + out);
            }
            Thread.sleep(20);
        }
        fail("no " + key + " line after " + line + ":\n" + child.output());
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(ChildJvm.Agent agent, String method, Map<String, Object> params)
            throws IOException {
        Map<String, Object> response = agent.rpc(method, params);
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertNotNull(error, method + " should fail: " + response);
        return error;
    }

    private static Map<String, Object> confirmed(String rule) {
        return map("rule", rule, "_meta", map("confirm", Boolean.TRUE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> engine(ChildJvm.Agent agent) throws Exception {
        long deadline = System.currentTimeMillis() + ChildJvm.START_TIMEOUT_MS;
        while (true) {
            Map<String, Object> caps = (Map<String, Object>) agent.value("agent.capabilities", null)
                    .get("capabilities");
            Map<String, Object> engine = (Map<String, Object>) caps.get("rule.engine");
            if (engine != null && !String.valueOf(engine.get("reason")).contains("not probed")) {
                return engine;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("rule.engine was never probed: " + caps);
            }
            Thread.sleep(50);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fired(ChildJvm.Agent agent) throws IOException {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<String, Object> events = agent.value("evt.query", map("since", 0L, "topics", list(Topics.RULE_FIRED)));
        for (Object e : (List<Object>) events.get("events")) {
            out.add((Map<String, Object>) ((Map<String, Object>) e).get("payload"));
        }
        return out;
    }

    private static String rule(String name, String at, String condition, String action) {
        return "RULE " + name + "\nCLASS " + TOY + "\nMETHOD ceiling\nAT " + at + "\nIF " + condition + "\nDO "
                + action + "\nENDRULE\n";
    }

    /**
     * The process's listening TCP ports: {@code /proc/<pid>/net/tcp} and {@code tcp6} entries in state {@code 0A}
     * whose inode is the target of one of {@code /proc/<pid>/fd/*}; null on a host without {@code /proc}.
     */
    static Set<Integer> listening(long pid) throws IOException {
        Path proc = Paths.get("/proc", Long.toString(pid));
        if (!Files.isDirectory(proc.resolve("fd")) || !Files.isReadable(proc.resolve("net/tcp"))) {
            return null;
        }
        Set<String> inodes = new HashSet<String>();
        File[] fds = proc.resolve("fd").toFile().listFiles();
        if (fds != null) {
            for (File fd : fds) {
                try {
                    String target = Files.readSymbolicLink(fd.toPath()).toString();
                    if (target.startsWith("socket:[")) {
                        inodes.add(target.substring(8, target.length() - 1));
                    }
                } catch (IOException gone) {
                    // closed while listing
                }
            }
        }
        Set<Integer> ports = new TreeSet<Integer>();
        for (String table : new String[] {"net/tcp", "net/tcp6"}) {
            Path p = proc.resolve(table);
            if (!Files.isReadable(p)) {
                continue;
            }
            List<String> lines = Files.readAllLines(p, StandardCharsets.US_ASCII);
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).trim().split("\\s+");
                if (f.length > 9 && "0A".equals(f[3]) && inodes.contains(f[9])) {
                    ports.add(Integer.parseInt(f[1].substring(f[1].indexOf(':') + 1), 16));
                }
            }
        }
        return ports;
    }

    private void awaitDisabled(ChildJvm.Agent agent) throws Exception {
        long deadline = System.currentTimeMillis() + ChildJvm.START_TIMEOUT_MS;
        while (!(agent.portClosed() && discoveryDir.list().length == 0)) {
            if (System.currentTimeMillis() > deadline) {
                fail("agent did not stop");
            }
            Thread.sleep(50);
        }
    }

    private void disable(ChildJvm.Agent agent) throws Exception {
        agent.value("agent.disable", map("_meta", map("confirm", Boolean.TRUE)));
        awaitDisabled(agent);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rulesAreCheckedSubmittedFiredListedAndUnloadedWithNoListenerAndReusedAfterAReattach() throws Exception {
        ChildJvm child = launch("");
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        child.awaitOutput("parked");
        Map<String, Object> engine = engine(agent);
        assertEquals("OK", engine.get("state"), String.valueOf(engine));
        assertEquals("in-process", engine.get("strategy"));
        long pid = child.pid();
        Set<Integer> before = listening(pid);
        int agentPort = ((Number) agent.discovery.get("port")).intValue();
        if (before == null) {
            System.out.println("BytemanIT: no /proc on this host; the listening-socket checks are skipped");
        } else {
            assertEquals(Collections.singleton(agentPort), before, "only the agent listens before the first submit");
        }

        String limit = rule("limit", "EXIT", "true", "fired($0, $!)");
        Map<String, Object> check = agent.value("rule.check", map("rule", limit));
        assertEquals(Boolean.TRUE, check.get("ok"), String.valueOf(check));
        assertEquals(Boolean.FALSE, check.get("typeChecked"), "Byteman is not loaded before the first submit");
        assertEquals((long) ErrorCodes.CONFIRM_REQUIRED,
                ((Number) error(agent, "rule.submit", map("rule", limit)).get("code")).longValue());
        for (String forbidden : new String[] {rule("kill", "ENTRY", "true", "killJVM()"),
                rule("thread", "ENTRY", "true", "fired($0)").replace("CLASS " + TOY, "CLASS java.lang.Thread"),
                rule("agent", "ENTRY", "true", "fired($0)").replace("CLASS " + TOY,
                        "CLASS ai.skillroy.testjava.agent.core.Dispatcher"),
                rule("process", "ENTRY", "true", "fired(new ProcessBuilder(\"sh\"))")}) {
            Map<String, Object> refused = error(agent, "rule.submit", confirmed(forbidden));
            assertEquals((long) ErrorCodes.RULE_REJECTED, ((Number) refused.get("code")).longValue(), forbidden);
        }

        Map<String, Object> submitted = agent.value("rule.submit", confirmed(limit));
        assertEquals(Boolean.TRUE, submitted.get("installed"), String.valueOf(submitted));
        assertEquals("150.00", command(child, "CEILING STAFF-7", "ceiling").split(":")[1]);
        List<Map<String, Object>> fired = fired(agent);
        assertEquals(1, fired.size(), String.valueOf(fired));
        assertEquals("limit", fired.get(0).get("rule"));
        assertEquals(TOY, fired.get(0).get("class"));
        assertEquals("ceiling", fired.get(0).get("method"));
        Map<String, Object> bindings = (Map<String, Object>) fired.get(0).get("bindings");
        assertEquals(TOY, ((Map<String, Object>) bindings.get("0")).get("class"), "$0 as a handle");
        assertEquals("150.00", bindings.get("1"), "$! the limit");

        Map<String, Object> listed = agent.value("rule.list", null);
        assertEquals(Boolean.TRUE, listed.get("loaded"));
        assertEquals("limit", ((Map<String, Object>) ((List<Object>) listed.get("rules")).get(0)).get("name"));
        String typeError = rule("broken", "EXIT", "true", "fired($!.noSuchMethod())");
        Map<String, Object> rejected = error(agent, "rule.submit", confirmed(typeError));
        assertEquals((long) ErrorCodes.RULE_REJECTED, ((Number) rejected.get("code")).longValue());
        assertTrue(String.valueOf(rejected.get("data")).contains("noSuchMethod"), String.valueOf(rejected));
        assertFalse(child.output().contains("noSuchMethod"), "the type error stays off the console:\n"
                + child.output());

        Map<String, Object> unloaded = agent.value("rule.unload", map("name", "limit"));
        assertEquals(Boolean.TRUE, unloaded.get("removed"));
        Map<String, Object> restored = (Map<String, Object>) ((List<Object>) unloaded.get("restored")).get(0);
        assertEquals("OK", restored.get("verified"), String.valueOf(unloaded));
        assertEquals(0L, ((Number) restored.get("foreignEvents")).longValue());
        if (before != null) {
            assertEquals(before, listening(pid), "the listening sockets after the unload equal those before");
        }

        // agent.disable and a re-attach: the second instance reuses the parked Retransformer
        disable(agent);
        assertEquals("1", command(child, "REATTACH enabled=true;" + options(""), "reattached"));
        agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        assertEquals("in-process, reused", engine(agent).get("strategy"));
        agent.value("rule.submit", confirmed(limit));
        assertEquals(Boolean.TRUE, agent.value("rule.list", null).get("reused"));
        command(child, "CEILING SUP-3", "ceiling");
        assertEquals("500.00", ((Map<String, Object>) fired(agent).get(0).get("bindings")).get("1"));
        assertEquals("OK", ((Map<String, Object>) ((List<Object>) agent.value("rule.unload", map("name", "limit"))
                .get("restored")).get(0)).get("verified"));

        // a cut-short uninstall leaves a rule: the next instance refuses by name, the rule's action keeps running
        agent.value("rule.submit", confirmed(rule("left", "ENTRY", "$1.equals(\"LEFT\")",
                "RETURN new java.math.BigDecimal(\"1.00\")")));
        assertEquals("LEFT:1.00", command(child, "CEILING LEFT", "ceiling"));
        assertEquals("set", command(child, "SEAM", "seam"));
        disable(agent);
        assertEquals("2", command(child, "REATTACH enabled=true;" + options(""), "reattached"));
        agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        Map<String, Object> refused = engine(agent);
        assertEquals("UNAVAILABLE", refused.get("state"));
        assertEquals("Byteman rules of an earlier agent instance are still installed: left; restart the target",
                refused.get("reason"));
        assertEquals("LEFT:1.00", command(child, "CEILING LEFT", "ceiling"), "the rule's action still runs");
        assertTrue(fired(agent).isEmpty(), "but no rule.fired arrives");
        if (before != null) {
            assertEquals(Collections.singleton(((Number) agent.discovery.get("port")).intValue()), listening(pid));
        }
        disable(agent);
        long deadline = System.currentTimeMillis() + ChildJvm.START_TIMEOUT_MS;
        String threads = command(child, "THREADS", "threads");
        while (!threads.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            threads = command(child, "THREADS", "threads");
        }
        assertEquals("", threads, "no agent thread after the last agent.disable");
        assertFalse(child.output().contains("Exception"), child.output());
    }

    @Test
    void readOnlyModeRefusesASubmitAndStillChecks() throws Exception {
        ChildJvm child = launch("readOnly=true");
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        child.awaitOutput("parked");
        engine(agent);
        String limit = rule("limit", "EXIT", "true", "fired($0, $!)");
        assertEquals((long) ErrorCodes.READ_ONLY,
                ((Number) error(agent, "rule.submit", confirmed(limit)).get("code")).longValue());
        assertEquals(Boolean.TRUE, agent.value("rule.check", map("rule", limit)).get("ok"));
        assertEquals(Boolean.FALSE, agent.value("rule.list", null).get("loaded"), "a read-only agent loads nothing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDenyOptionRefusesARuleBeforeBytemanLoadsAndABadPatternFailsTheModule() throws Exception {
        ChildJvm child = launch("hooks.deny=" + TOY);
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        child.awaitOutput("parked");
        assertEquals("OK", engine(agent).get("state"));
        String limit = rule("limit", "EXIT", "true", "fired($0, $!)");
        String reason = TOY + ": denied by the agent option hooks.deny: " + TOY;
        Map<String, Object> check = agent.value("rule.check", map("rule", limit));
        assertEquals(Boolean.FALSE, check.get("ok"), String.valueOf(check));
        assertEquals(Collections.singletonList(reason), check.get("errors"));
        Map<String, Object> refused = error(agent, "rule.submit", confirmed(limit));
        assertEquals((long) ErrorCodes.RULE_REJECTED, ((Number) refused.get("code")).longValue());
        assertEquals("the rule was refused: " + reason, refused.get("message"));
        Map<String, Object> listed = agent.value("rule.list", null);
        assertEquals(Boolean.FALSE, listed.get("loaded"), "nothing appended, no premain: " + listed);
        Map<String, Object> deny = (Map<String, Object>) listed.get("deny");
        assertEquals(Collections.singletonList(TOY), deny.get("option"), String.valueOf(listed));
        assertEquals(0L, ((Number) deny.get("modules")).longValue());
        assertFalse(deny.containsKey("catalogue"), "no hooks module: no catalogue list");
        assertEquals("150.00", command(child, "CEILING STAFF-7", "ceiling").split(":")[1], "the toy is untouched");
        assertFalse(child.output().contains("org.jboss.byteman"), child.output());

        ChildJvm bad = launch("hooks.deny=a..b");
        ChildJvm.Agent second = new ChildJvm.Agent(bad.awaitDiscovery(discoveryDir));
        Map<String, Object> failed = null;
        for (Object module : (List<Object>) second.value("agent.info", null).get("modules")) {
            if ("byteman".equals(((Map<String, Object>) module).get("id"))) {
                failed = (Map<String, Object>) module;
            }
        }
        assertNotNull(failed, "the byteman module's record");
        assertEquals("FAILED", failed.get("state"), String.valueOf(failed));
        assertTrue(String.valueOf(failed.get("message")).contains("hooks.deny"), String.valueOf(failed));
    }
}
