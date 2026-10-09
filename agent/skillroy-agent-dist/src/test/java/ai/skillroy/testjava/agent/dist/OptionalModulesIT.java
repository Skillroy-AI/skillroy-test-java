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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.dist.ChildJvm.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default path loads none of the hooks (phase6-contracts.md sections 2.1 and 2.5, D114; section 13.1 item 13): a
 * child JVM on the target Java with {@code -verbose:class} and the default home loads no class of the hooks module, of
 * the relocated Byte Buddy or of {@code Spy}; with {@code optionalModules=skillroy-agent-hooks} the module is INSTALLED
 * and {@code hook.engine} is OK in JAVAAGENT mode.
 */
class OptionalModulesIT {

    static final String MAIN = "ai.skillroy.testjava.fixture.hooks.LoanDeskMain";

    private ItWork work;
    private File app;

    @BeforeEach
    void prepare() throws Exception {
        work = ItWork.prepare("optional-modules");
        app = new File(work.work, "loan-desk.jar");
        AgentHomeIT.jar(app, AgentHomeIT.packageClasses("ai.skillroy.testjava.fixture.hooks"));
    }

    @AfterEach
    void stop() {
        work.stopChildren();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDefaultHomeLoadsNoClassOfTheHooks() throws Exception {
        File discovery = work.newDir("disc-");
        ChildJvm child = work.launch(list("-Djava.awt.headless=true", "-verbose:class",
                "-Dskillroy.agent.enabled=true", ItWork.javaagent(work.home, "discoveryDir=" + discovery.getPath())),
                app.getPath(), MAIN, list(), Collections.<String, String>emptyMap());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discovery));
        Map<String, Object> info = agent.value("agent.info", null);
        for (Object module : (List<Object>) info.get("modules")) {
            assertFalse("hooks".equals(((Map<String, Object>) module).get("id")), "the hooks module is not loaded");
        }
        Map<String, Object> caps = (Map<String, Object>) agent.value("agent.capabilities", null).get("capabilities");
        assertFalse(caps.containsKey("hook.engine"));
        String out = child.output();
        assertTrue(out.contains("ai.skillroy.testjava.agent.core."), "-verbose:class reports the agent's classes");
        assertFalse(out.contains("ai.skillroy.testjava.agent.hooks"), "no class of the hooks module or of the "
                + "relocated Byte Buddy was loaded");
        assertFalse(out.contains("ai.skillroy.testjava.agent.spy"), "Spy was not loaded");
        assertFalse(out.toLowerCase(java.util.Locale.ROOT).contains("net.bytebuddy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void namedInOptionalModulesTheModuleIsInstalledAndTheEngineIsOk() throws Exception {
        File discovery = work.newDir("disc-");
        ChildJvm child = work.launch(list("-Djava.awt.headless=true", "-Dskillroy.agent.enabled=true",
                ItWork.javaagent(work.home, "discoveryDir=" + discovery.getPath()
                        + ";optionalModules=skillroy-agent-hooks")), app.getPath(), MAIN, list(),
                Collections.<String, String>emptyMap());
        ChildJvm.Agent agent = new ChildJvm.Agent(child.awaitDiscovery(discovery));
        Map<String, Object> info = agent.value("agent.info", null);
        boolean installed = false;
        for (Object module : (List<Object>) info.get("modules")) {
            Map<String, Object> m = (Map<String, Object>) module;
            if ("hooks".equals(m.get("id"))) {
                assertEquals("INSTALLED", m.get("state"), String.valueOf(m));
                installed = true;
            }
        }
        assertTrue(installed, "the hooks module: " + info.get("modules"));
        Map<String, Object> engine = SharedLoadersIT.awaitEngine(agent);
        assertEquals("OK", engine.get("state"), String.valueOf(engine));
        assertEquals("advice", engine.get("strategy"));
    }
}
