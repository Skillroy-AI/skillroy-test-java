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
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileFilter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.dist.ChildJvm.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code EmbeddedAgent.start} in child JVMs (contract amendment A6): next to a {@code -javaagent} agent it returns
 * true and changes nothing, whether it is the same bootstrap or a copy in a loader of the host's own; and a host
 * plugin can call it by name without the bootstrap jar on its class path.
 */
class EmbeddedStartIT {

    private static final String APP = "ai.skillroy.testjava.agent.dist.fixture.app.";

    private static ItWork it;

    @BeforeAll
    static void prepare() throws Exception {
        it = ItWork.prepare("embedded-start");
    }

    @AfterEach
    void stopChildren() {
        it.stopChildren();
    }

    private static int discoveryFiles(File dir, long pid) {
        final String prefix = "skillroy-" + pid + "-";
        File[] files = dir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.getName().startsWith(prefix) && f.getName().endsWith(".json");
            }
        });
        return files == null ? 0 : files.length;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasModule(Map<String, Object> info, String id) {
        for (Object module : (List<Object>) info.get("modules")) {
            if (id.equals(((Map<String, Object>) module).get("id"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void assertOneJavaagentAgentUntouchedByTheEmbeddedCall(ChildJvm child, File discoveryDir,
                                                                          File embeddedDir) throws Exception {
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        Map<String, Object> info = agent.value("agent.info", null);
        assertEquals("JAVAAGENT", info.get("loadMode"));
        assertEquals(Boolean.TRUE, info.get("instrumentation"));
        assertFalse(hasModule(info, "probe"), "the embedded call's modules option was not applied");
        Map<String, Object> error = (Map<String, Object>) agent.rpc("probe.check", null).get("error");
        assertEquals(Long.valueOf(-32601), error.get("code"));
        assertEquals(1, discoveryFiles(discoveryDir, child.pid()), "one agent");
        assertEquals(0, embeddedDir.list().length, "nothing in the embedded call's discovery directory");
        assertEquals(String.valueOf(agent.port), child.awaitLine("agentPort"));
        assertEquals("null", child.awaitLine("agentError"));
        assertFalse(child.output().contains("[skillroy]"), child.output());
    }

    @Test
    void anEmbeddedStartNextToAJavaagentReturnsTrueAndChangesNothing() throws Exception {
        File discoveryDir = it.newDir("disc-");
        File embeddedDir = it.newDir("embedded-");
        String classPath = it.appJar.getPath() + File.pathSeparator + it.bootstrapJar().getPath();
        ChildJvm child = it.launch(list("-Djava.awt.headless=true", "-Dskillroy.agent.enabled=true",
                ItWork.javaagent(it.home, "discoveryDir=" + discoveryDir.getPath())), classPath,
                APP + "EmbeddedMain", list(embeddedDir.getPath(), it.probeJar.getPath()),
                Collections.<String, String>emptyMap());
        assertEquals("true", child.awaitLine("embedded"), child.output());
        assertOneJavaagentAgentUntouchedByTheEmbeddedCall(child, discoveryDir, embeddedDir);
    }

    @Test
    void aCopyOfTheBootstrapInTheHostsOwnLoaderAlsoLeavesTheJavaagentAgentAlone() throws Exception {
        File discoveryDir = it.newDir("disc-");
        File embeddedDir = it.newDir("embedded-");
        ChildJvm child = it.launch(list("-Djava.awt.headless=true", "-Dskillroy.agent.enabled=true",
                ItWork.javaagent(it.home, "discoveryDir=" + discoveryDir.getPath())), it.appJar.getPath(),
                APP + "ReflectiveEmbeddedMain", list(embeddedDir.getPath(), it.probeJar.getPath(),
                        it.bootstrapJar().getPath()), Collections.<String, String>emptyMap());
        assertEquals("true", child.awaitLine("embedded"), child.output());
        assertEquals("true", child.awaitLine("again"));
        assertOneJavaagentAgentUntouchedByTheEmbeddedCall(child, discoveryDir, embeddedDir);
    }

    @Test
    void aPluginStartsTheAgentByNameWithoutTheBootstrapOnItsClassPath() throws Exception {
        File discoveryDir = it.newDir("disc-");
        ChildJvm child = it.launch(list("-Djava.awt.headless=true"), it.appJar.getPath(),
                APP + "ReflectiveEmbeddedMain", list(discoveryDir.getPath(), it.probeJar.getPath(),
                        it.bootstrapJar().getPath()), Collections.<String, String>emptyMap());
        assertEquals("true", child.awaitLine("embedded"), child.output());
        assertEquals("true", child.awaitLine("again"), "a second call returns true as well");
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discoveryDir));
        Map<String, Object> info = agent.value("agent.info", null);
        assertEquals("EMBEDDED", info.get("loadMode"));
        assertTrue(hasModule(info, "probe"));
        Map<String, Object> check = agent.value("probe.check", null);
        assertEquals("HostThing[the reflective host]", check.get("hostContext"),
                "the first call's host context; the second call changed nothing");
        assertEquals(Boolean.TRUE, check.get("hostLoaderSeesApp"));
        assertEquals(1, discoveryFiles(discoveryDir, child.pid()));
    }

    @Test
    void aPluginWhoseBootstrapJarIsMissingKeepsTheApplicationRunning() throws Exception {
        File discoveryDir = it.newDir("disc-");
        ChildJvm child = it.launch(list("-Djava.awt.headless=true"), it.appJar.getPath(),
                APP + "ReflectiveEmbeddedMain", list(discoveryDir.getPath(), it.probeJar.getPath(), "-"),
                Collections.<String, String>emptyMap());
        assertEquals("absent", child.awaitLine("embedded"), child.output());
        assertEquals("null", child.awaitLine("agentPort"));
        child.awaitOutput("parked");
        assertEquals(0, discoveryDir.list().length);
    }
}
