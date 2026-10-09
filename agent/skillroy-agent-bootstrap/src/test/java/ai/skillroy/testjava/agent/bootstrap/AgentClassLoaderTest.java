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
import java.net.URLClassLoader;
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
        assertFalse(AgentClassLoader.isChildFirst("ai.skillroy.testjava.agent.spy.Spy"),
                "the hooks' Spy package exists once, on the bootstrap path (phase6-contracts.md 2.1)");
        assertFalse(AgentClassLoader.isChildFirst("ai.skillroy.testjava.agent.spy.Spy$Handler"));

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

    /**
     * The hooks' {@code Spy} package is asked of the parent first (phase6-contracts.md 2.1, D116): once {@code Spy} is
     * on the bootstrap path the host loader resolves it there, and a stray copy in an agent jar must not shadow it.
     */
    @Test
    void theSpyPackageIsTheParentsEvenWhenAnAgentJarHoldsACopy() throws Exception {
        String spy = "ai/skillroy/testjava/agent/spy/Spy";
        File parentJar = tmp.resolve("boot-path.jar").toFile();
        Map<String, byte[]> parentEntries = new LinkedHashMap<String, byte[]>();
        parentEntries.put(spy + ".class", minimalClass(spy));
        BootJars.jar(parentJar, parentEntries);
        File agentJar = tmp.resolve("agent-lib.jar").toFile();
        Map<String, byte[]> agentEntries = new LinkedHashMap<String, byte[]>();
        agentEntries.put(spy + ".class", minimalClass(spy));
        BootJars.jar(agentJar, agentEntries);
        URLClassLoader parent = new URLClassLoader(new URL[] {parentJar.toURI().toURL()},
                AgentClassLoaderTest.class.getClassLoader());
        AgentClassLoader loader = new AgentClassLoader(new URL[] {agentJar.toURI().toURL()}, parent);
        try {
            Class<?> loaded = loader.loadClass("ai.skillroy.testjava.agent.spy.Spy");
            assertSame(parent, loaded.getClassLoader(), "the parent's Spy, not the agent jar's copy");
            assertTrue(loader.getResource(spy + ".class").toString().contains("boot-path.jar"),
                    "the parent's resource first");
        } finally {
            loader.close();
            parent.close();
        }
    }

    /** A public class with no members: just enough of a class file for a loader to define. */
    static byte[] minimalClass(String internalName) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(52);
        out.writeShort(5);
        out.writeByte(1);
        out.writeUTF(internalName);
        out.writeByte(7);
        out.writeShort(1);
        out.writeByte(1);
        out.writeUTF("java/lang/Object");
        out.writeByte(7);
        out.writeShort(3);
        out.writeShort(0x0021);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.flush();
        return bytes.toByteArray();
    }

    private static void put(Map<String, byte[]> entries, Class<?> type) throws Exception {
        String name = type.getName().replace('.', '/') + ".class";
        entries.put(name, BootJars.resourceBytes(name));
    }
}
