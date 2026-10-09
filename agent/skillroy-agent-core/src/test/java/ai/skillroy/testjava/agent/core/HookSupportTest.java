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
import ai.skillroy.testjava.agent.core.jarfixture.JarModule;
import ai.skillroy.testjava.agent.core.jarfixture.JarModuleTwo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the core gives the optional hooks module (phase6-contracts.md sections 2.1 and 3.6): the
 * {@code optionalModules} source of module discovery (D114) and the contexts' {@code isSecretKey} and
 * {@code redactText} over the runtime's {@code Redactor} (D118), with the {@code redact} option and a registered
 * {@code RedactionKeys} pattern honoured (section 13.1 item 3).
 */
class HookSupportTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @AfterEach
    void stop() throws Exception {
        if (agent != null) {
            agent.close();
        }
    }

    @Test
    void theContextsSecretRuleAndRedactionAreTheRuntimes() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp.resolve("disc"), "redact", "badge",
                "coretest.redactionKeys", "staffPin");
        Map<String, Object> secret = agent.valueMap("test.redact", map("key", "db.password"));
        assertEquals(Boolean.TRUE, secret.get("secret"), "the protocol's rule");
        assertEquals(Boolean.TRUE, secret.get("coreSecret"), "the core context answers the same");
        assertEquals(Boolean.TRUE, agent.valueMap("test.redact", map("key", "app.badgeNumber")).get("secret"),
                "the redact option");
        assertEquals(Boolean.TRUE, agent.valueMap("test.redact", map("key", "staffPin")).get("secret"),
                "a registered RedactionKeys pattern");
        assertEquals(Boolean.FALSE, agent.valueMap("test.redact", map("key", "staffCode")).get("secret"));
        assertEquals(Boolean.FALSE, agent.valueMap("test.redact", map("key", null)).get("secret"));
        Map<String, Object> text = agent.valueMap("test.redact", map("text",
                "login failed: staffPin=1234 badgeNo=abc url=jdbc:x://u:pw@host/db code=100"));
        assertEquals("login failed: staffPin=" + Redactor.REDACTED + " badgeNo=" + Redactor.REDACTED
                + " url=jdbc:x://u:" + Redactor.REDACTED + "@host/db code=100", text.get("text"));
        assertNull(agent.valueMap("test.redact", map()).get("text"), "null stays null");
    }

    @Test
    void anOptionalJarIsLoadedOnlyWhenNamedAndAnUnknownStemOrAJarWithoutServiceFileFails() throws Exception {
        File home = tmp.resolve("home").toFile();
        File optional = new File(home, ModuleLoader.OPTIONAL_DIR);
        assertTrue(new File(home, "modules").mkdirs());
        assertTrue(optional.mkdirs());
        TestJars.moduleJar(new File(optional, "jarmod-two.jar"), JarModuleTwo.class.getName());
        TestJars.jar(new File(optional, "no-service.jar"), TestJars.packageClasses(
                "ai.skillroy.testjava.agent.core.jarfixture"));

        Map<String, ModuleLoader.Record> unnamed = discover(home, null);
        assertFalse(unnamed.containsKey(new JarModuleTwo().id()), "optional/ is never scanned: " + unnamed.keySet());
        for (ModuleLoader.Record r : unnamed.values()) {
            assertFalse(r.origin.contains(File.separator + ModuleLoader.OPTIONAL_DIR + File.separator),
                    "nothing of optional/ is opened: " + r.origin);
        }

        Map<String, ModuleLoader.Record> named = discover(home, " jarmod-two , missing,no-service,../modules/x,");
        ModuleLoader.Record two = named.get(new JarModuleTwo().id());
        assertNotNull(two, named.keySet().toString());
        assertTrue(two.origin.endsWith("optional" + File.separator + "jarmod-two.jar"), two.origin);
        assertNull(two.state, "discovered, not failed");
        ModuleLoader.Record missing = named.get(new File(optional, "missing.jar").getPath());
        assertNotNull(missing, named.keySet().toString());
        assertEquals("FAILED", missing.state);
        assertTrue(missing.message.contains("does not exist"), missing.message);
        ModuleLoader.Record noService = named.get(new File(optional, "no-service.jar").getPath());
        assertNotNull(noService, named.keySet().toString());
        assertEquals("FAILED", noService.state);
        assertTrue(noService.message.contains("declares no"), noService.message);
        ModuleLoader.Record traversal = named.get("optional/../modules/x");
        assertNotNull(traversal, named.keySet().toString());
        assertEquals("FAILED", traversal.state);
        assertTrue(traversal.message.contains("not a file stem"), traversal.message);
    }

    @Test
    void anOptionalModuleSitsBetweenTheHomesModulesAndTheModulesOption() throws Exception {
        File home = tmp.resolve("order").toFile();
        File modules = new File(home, "modules");
        File optional = new File(home, ModuleLoader.OPTIONAL_DIR);
        assertTrue(modules.mkdirs());
        assertTrue(optional.mkdirs());
        TestJars.moduleJar(new File(modules, "a.jar"), JarModule.class.getName());
        TestJars.moduleJar(new File(optional, "b.jar"), JarModuleTwo.class.getName());
        File extra = tmp.resolve("extra.jar").toFile();
        TestJars.moduleJar(extra, JarModuleTwo.class.getName());
        Map<String, String> options = new HashMap<String, String>();
        options.put("home", home.getPath());
        options.put(ModuleLoader.OPTIONAL_MODULES, "b");
        options.put("modules", extra.getPath());
        ModuleLoader loader = new ModuleLoader(new RingLog());
        try {
            List<ModuleLoader.Record> records = loader.discover(options, HookSupportTest.class.getClassLoader());
            assertEquals(new JarModule().id(), records.get(0).id, "the home's modules first");
            ModuleLoader.Record two = records.get(1);
            assertEquals(new JarModuleTwo().id(), two.id);
            assertTrue(two.origin.endsWith("b.jar"), "the optional jar wins over the modules option's copy: "
                    + two.origin);
        } finally {
            for (ModuleClassLoader l : loader.loaders()) {
                l.close();
            }
        }
    }

    private Map<String, ModuleLoader.Record> discover(File home, String optionalModules) throws Exception {
        Map<String, String> options = new HashMap<String, String>();
        options.put("home", home.getPath());
        if (optionalModules != null) {
            options.put(ModuleLoader.OPTIONAL_MODULES, optionalModules);
        }
        ModuleLoader loader = new ModuleLoader(new RingLog());
        try {
            Map<String, ModuleLoader.Record> byId = new LinkedHashMap<String, ModuleLoader.Record>();
            for (ModuleLoader.Record record : loader.discover(options, HookSupportTest.class.getClassLoader())) {
                byId.put(record.id, record);
            }
            return byId;
        } finally {
            for (ModuleClassLoader l : loader.loaders()) {
                l.close();
            }
        }
    }
}
