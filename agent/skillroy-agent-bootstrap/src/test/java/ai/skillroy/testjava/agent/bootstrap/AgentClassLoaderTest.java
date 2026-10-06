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

import ai.skillroy.testjava.agent.bootstrap.fixture.Plain;
import ai.skillroy.testjava.agent.core.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent loader: child-first for the agent's own packages except the bootstrap, parent-first for the rest. */
class AgentClassLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void childFirstOnlyForAgentClassesOutsideTheBootstrap() throws Exception {
        assertTrue(AgentClassLoader.isChildFirst("ai.skillroy.testjava.agent.core.AgentRuntime"));
        assertTrue(AgentClassLoader.isChildFirst("ai.skillroy.testjava.protocol.Json"));
        assertFalse(AgentClassLoader.isChildFirst("ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent"));
        assertFalse(AgentClassLoader.isChildFirst("java.lang.String"));
        assertFalse(AgentClassLoader.isChildFirst("org.example.App"));

        File jar = tmp.resolve("lib.jar").toFile();
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        put(entries, AgentRuntime.class);
        put(entries, Plain.class);
        put(entries, FakeRecorder.class);
        entries.put("ai/skillroy/testjava/agent/core/res.txt", "agent copy".getBytes("UTF-8"));
        BootJars.jar(jar, entries);
        ClassLoader parent = AgentClassLoaderTest.class.getClassLoader();
        AgentClassLoader loader = new AgentClassLoader(new URL[] {jar.toURI().toURL()}, parent);
        try {
            Class<?> runtime = loader.loadClass(AgentRuntime.class.getName());
            assertSame(loader, runtime.getClassLoader(), "agent classes come from the agent jars first");
            assertNotSame(AgentRuntime.class, runtime);
            assertSame(runtime, loader.loadClass(AgentRuntime.class.getName()), "defined once");
            assertSame(FakeRecorder.class, loader.loadClass(FakeRecorder.class.getName()),
                    "the bootstrap package is always the parent's");
            assertSame(Plain.class, loader.loadClass(Plain.class.getName()),
                    "bootstrap sub-packages are the parent's too");
            assertSame(String.class, loader.loadClass("java.lang.String"));
            assertSame(AgentClassLoaderTest.class, loader.loadClass(AgentClassLoaderTest.class.getName()),
                    "a bootstrap-package class the jar lacks comes from the parent");
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("ai.skillroy.testjava.Nope"));
            URL resource = loader.getResource("ai/skillroy/testjava/agent/core/res.txt");
            assertTrue(resource.toString().contains("lib.jar"), String.valueOf(resource));
            String runtimeResource = "ai/skillroy/testjava/agent/core/AgentRuntime.class";
            assertTrue(loader.getResource(runtimeResource).toString().startsWith("jar:"));
            List<URL> all = Collections.list(loader.getResources(runtimeResource));
            assertEquals(2, all.size());
            assertTrue(all.get(0).toString().startsWith("jar:"), "own copy first");
        } finally {
            loader.close();
        }
    }

    private static void put(Map<String, byte[]> entries, Class<?> type) throws Exception {
        String name = type.getName().replace('.', '/') + ".class";
        entries.put(name, BootJars.resourceBytes(name));
    }
}
