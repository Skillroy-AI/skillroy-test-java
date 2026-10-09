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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.dist.ChildJvm.list;
import static ai.skillroy.testjava.agent.dist.ChildJvm.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * After an install and a remove through the primitives, the host's shared loaders hold nothing of the agent's but
 * {@code Spy} and its nested types in the bootstrap loader (phase6-contracts.md section 2.5; BS3 RESULT section 9 fact
 * 3 made a test; section 13.1 item 13): a probe agent of the test's own ({@code -javaagent:} on the child, which the
 * engine lists in {@code otherAgents}) snapshots the bootstrap, the platform (Java 8: extension) and the system loader
 * before and after. The restore is byte-identical with no foreign event.
 */
class SharedLoadersIT {

    static final String FEE_RULE = "ai.skillroy.testjava.fixture.hooks.FeeRule";

    private ItWork work;
    private File app;
    private File probe;

    @BeforeEach
    void prepare() throws Exception {
        work = ItWork.prepare("shared-loaders");
        app = new File(work.work, "loan-desk.jar");
        AgentHomeIT.jar(app, AgentHomeIT.packageClasses("ai.skillroy.testjava.fixture.hooks"));
        probe = new File(work.work, "probe-agent.jar");
        Map<String, byte[]> entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\nPremain-Class: "
                + "ai.skillroy.testjava.fixture.hooks.probe.ProbeAgent\n\n").getBytes(ChildJvm.UTF8));
        entries.putAll(AgentHomeIT.packageClasses("ai.skillroy.testjava.fixture.hooks.probe"));
        AgentHomeIT.jar(probe, entries);
    }

    @AfterEach
    void stop() {
        work.stopChildren();
    }

    /** {@code hook.engine} once probed (the target becomes ready shortly after the discovery file). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> awaitEngine(ChildJvm.Agent agent) throws Exception {
        long deadline = System.currentTimeMillis() + ChildJvm.START_TIMEOUT_MS;
        Map<String, Object> engine = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> caps = (Map<String, Object>) agent.value("agent.capabilities", null)
                    .get("capabilities");
            engine = (Map<String, Object>) caps.get("hook.engine");
            if (engine != null && !String.valueOf(engine.get("reason")).startsWith("not probed yet")) {
                return engine;
            }
            Thread.sleep(100);
        }
        fail("hook.engine was never probed: " + engine);
        return null;
    }

    private static List<String> snapshot(ChildJvm child, String label, String which) throws Exception {
        String value = child.awaitLine("snapshot." + label + "." + which);
        return value.isEmpty() ? new ArrayList<String>() : new ArrayList<String>(Arrays.asList(value.split(",")));
    }

    private static void send(ChildJvm child, String command) throws Exception {
        OutputStream in = child.process.getOutputStream();
        in.write((command + "\n").getBytes(ChildJvm.UTF8));
        in.flush();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlySpyReachesTheSharedLoadersAndTheRestoreIsByteIdentical() throws Exception {
        File discovery = work.newDir("disc-");
        ChildJvm child = work.launch(list("-Djava.awt.headless=true", "-Dskillroy.agent.enabled=true",
                ItWork.javaagent(work.home, "discoveryDir=" + discovery.getPath()
                        + ";optionalModules=skillroy-agent-hooks"), "-javaagent:" + probe.getPath()),
                app.getPath(), OptionalModulesIT.MAIN, list(), Collections.<String, String>emptyMap());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discovery));
        assertEquals("OK", awaitEngine(agent).get("state"));
        send(child, "snapshot before");
        List<String> bootBefore = snapshot(child, "before", "boot");
        List<String> platformBefore = snapshot(child, "before", "platform");
        List<String> systemBefore = snapshot(child, "before", "system");

        Map<String, Object> spec = map("id", "fee.computed", "mode", "CAPTURE", "target", map("class", FEE_RULE,
                "method", "feeFor", "descriptors", list("(Ljava/lang/String;)Ljava/math/BigDecimal;")),
                "capture", map("args", Arrays.asList(0L), "self", false, "result", true));
        Map<String, Object> probed = agent.value("hook.probe", map("specs", Arrays.asList(spec)));
        assertTrue(((List<Object>) probed.get("otherAgents")).contains("probe-agent.jar"),
                "the test's probe agent is another agent: " + probed.get("otherAgents"));
        assertEquals("OK", ((Map<String, Object>) ((List<Object>) probed.get("hooks")).get(0)).get("state"));
        Map<String, Object> installed = agent.value("hook.install", map("specs", Arrays.asList(spec), "catalogue",
                map("id", "loan-desk", "hash", "sha256:it")));
        assertEquals("OK", ((Map<String, Object>) ((List<Object>) installed.get("hooks")).get(0)).get("state"),
                String.valueOf(installed));
        long since = 0;
        send(child, "call");
        child.awaitOutput("call.");
        Map<String, Object> events = agent.value("hook.events", map("since", since, "id", "fee.computed"));
        List<Object> fired = (List<Object>) events.get("events");
        assertEquals(1, fired.size(), String.valueOf(events));
        Map<String, Object> payload = (Map<String, Object>) ((Map<String, Object>) fired.get(0)).get("payload");
        assertEquals("main", payload.get("thread"), "on the application's own thread");
        assertEquals("5.00", payload.get("returned"));
        Map<String, Object> removed = agent.value("hook.remove", map());
        Map<String, Object> line = (Map<String, Object>) ((List<Object>) removed.get("restored")).get(0);
        assertEquals("OK", line.get("verified"), String.valueOf(removed));
        assertEquals(0L, ((Number) line.get("foreignEvents")).longValue());

        send(child, "snapshot after");
        List<String> bootAdded = snapshot(child, "after", "boot");
        bootAdded.removeAll(bootBefore);
        List<String> platformAdded = snapshot(child, "after", "platform");
        platformAdded.removeAll(platformBefore);
        List<String> systemAdded = snapshot(child, "after", "system");
        systemAdded.removeAll(systemBefore);
        assertTrue(bootAdded.contains("ai.skillroy.testjava.agent.spy.Spy"), "Spy is on the bootstrap path: "
                + bootAdded);
        assertTrue(Arrays.asList("ai.skillroy.testjava.agent.spy.Spy", "ai.skillroy.testjava.agent.spy.Spy$Handler",
                "ai.skillroy.testjava.agent.spy.Spy$Stub").containsAll(bootAdded), "nothing else of the agent's in "
                + "the bootstrap loader: " + bootAdded);
        assertTrue(platformAdded.isEmpty(), "the platform or extension loader gained " + platformAdded);
        assertTrue(systemAdded.isEmpty(), "the system loader gained " + systemAdded);
        String out = child.output();
        assertTrue(!out.contains("sun.misc.Unsafe") && !out.contains("ByteBuddyAccessor"), out);
    }
}
