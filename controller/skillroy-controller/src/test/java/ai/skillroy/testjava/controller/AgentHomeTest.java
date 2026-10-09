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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHomeTest {

    @TempDir
    Path dir;

    private Path home() throws IOException {
        Files.createDirectories(dir.resolve("lib"));
        Files.writeString(dir.resolve(AgentHome.BOOTSTRAP_JAR), "jar");
        return dir;
    }

    @Test
    void ofValidatesTheLayout() throws IOException {
        Path home = home();
        AgentHome agentHome = AgentHome.of(home.resolve("lib").resolve(".."));
        assertEquals(home.toAbsolutePath().normalize(), agentHome.dir());
        assertEquals(agentHome.dir().resolve("skillroy-agent-bootstrap.jar"), agentHome.bootstrapJar());
        assertEquals(agentHome.dir().resolve("modules"), agentHome.modulesDir());
        assertEquals(AgentHome.of(home), agentHome);
    }

    /** phase6-contracts.md 2.1: the spy jar at the root, {@code optional/} and its jars by file stem. */
    @Test
    void theOptionalDirectoryAndTheSpyJarAreNamedWhetherOrNotTheyExist() throws IOException {
        AgentHome agentHome = AgentHome.of(home());
        assertEquals(agentHome.dir().resolve("skillroy-agent-spy.jar"), agentHome.spyJar());
        assertEquals(agentHome.dir().resolve("optional"), agentHome.optionalDir());
        assertEquals(agentHome.dir().resolve("optional/skillroy-agent-hooks.jar"),
                agentHome.optionalModule(AgentHome.HOOKS_MODULE));
        for (String bad : new String[] {null, " ", "a/b", "..", "."}) {
            assertThrows(IllegalArgumentException.class, () -> agentHome.optionalModule(bad), String.valueOf(bad));
        }
    }

    @Test
    void ofRejectsIncompleteHomes() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> AgentHome.of(dir.resolve("missing")));
        IllegalArgumentException noJar = assertThrows(IllegalArgumentException.class, () -> AgentHome.of(dir));
        assertTrue(noJar.getMessage().contains("skillroy-agent-bootstrap.jar"), noJar.getMessage());
        Files.writeString(dir.resolve(AgentHome.BOOTSTRAP_JAR), "jar");
        IllegalArgumentException noLib = assertThrows(IllegalArgumentException.class, () -> AgentHome.of(dir));
        assertTrue(noLib.getMessage().contains("lib"), noLib.getMessage());
    }

    @Test
    void locatePrefersThePropertyThenTheEnvironment() throws IOException {
        Path home = home();
        Map<String, String> none = Map.of();
        assertEquals(home, AgentHome.locate(Map.of(AgentHome.PROPERTY, home.toString())::get, none::get).dir());
        assertEquals(home, AgentHome.locate(none::get, Map.of(AgentHome.ENVIRONMENT, " " + home + " ")::get).dir());
        assertEquals(home, AgentHome.locate(Map.of(AgentHome.PROPERTY, home.toString())::get,
                Map.of(AgentHome.ENVIRONMENT, "/nowhere")::get).dir());
    }

    @Test
    void locateExplainsWhatIsMissing() {
        Map<String, String> none = Map.of();
        IllegalStateException unset = assertThrows(IllegalStateException.class,
                () -> AgentHome.locate(none::get, none::get));
        assertTrue(unset.getMessage().contains("skillroy.agent.home"), unset.getMessage());
        assertTrue(unset.getMessage().contains("SKILLROY_AGENT_HOME"), unset.getMessage());
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> AgentHome.locate(Map.of(AgentHome.PROPERTY, dir.resolve("x").toString())::get, none::get));
        assertTrue(invalid.getMessage().contains("system property"), invalid.getMessage());
    }
}
