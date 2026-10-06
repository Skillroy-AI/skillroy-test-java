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

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.core.jarfixture.JarModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The module loader: child-first for the jar's own classes, parent-first for the JDK and the shared agent types. */
class ModuleClassLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void ownClassesComeFromTheJarSharedTypesFromTheParent() throws Exception {
        File jar = tmp.resolve("m.jar").toFile();
        Map<String, byte[]> entries = TestJars.packageClasses("ai.skillroy.testjava.agent.core.jarfixture");
        // A stray copy of an API type inside the jar must not shadow the shared one.
        String apiClass = AgentModule.class.getName().replace('.', '/') + ".class";
        entries.put(apiClass, TestJars.resourceBytes(apiClass));
        entries.put("ai/skillroy/testjava/agent/core/jarfixture/own.txt", "own".getBytes(AgentHarness.UTF8));
        TestJars.jar(jar, entries);
        ClassLoader parent = ModuleClassLoaderTest.class.getClassLoader();
        ModuleClassLoader loader = new ModuleClassLoader(new URL[] {jar.toURI().toURL()}, parent);
        try {
            Class<?> module = loader.loadClass(JarModule.class.getName());
            assertSame(loader, module.getClassLoader(), "child-first for the jar's classes");
            assertTrue(module != JarModule.class);
            assertSame(AgentModule.class, loader.loadClass(AgentModule.class.getName()), "API from the parent");
            assertTrue(AgentModule.class.isAssignableFrom(module));
            assertSame(String.class, loader.loadClass("java.lang.String"));
            assertSame(ModuleClassLoaderTest.class, loader.loadClass(ModuleClassLoaderTest.class.getName()),
                    "classes the jar does not contain come from the parent");
            URL own = loader.getResource("ai/skillroy/testjava/agent/core/jarfixture/own.txt");
            assertNotNull(own);
            assertTrue(own.toString().contains("m.jar"));
            URL compiled = loader.getResource("ai/skillroy/testjava/agent/core/jarfixture/JarModule.class");
            assertTrue(compiled.toString().startsWith("jar:"), "own resources first: " + compiled);
            List<URL> both = Collections.list(loader.getResources(
                    "ai/skillroy/testjava/agent/core/jarfixture/JarModule.class"));
            assertEquals(2, both.size());
            assertTrue(both.get(0).toString().startsWith("jar:"));
            assertTrue(ModuleClassLoader.isParentFirst("ai.skillroy.testjava.protocol.Json"));
            assertTrue(!ModuleClassLoader.isParentFirst("ai.skillroy.testjava.agent.core.AgentRuntime"));
        } finally {
            loader.close();
        }
    }

    @Test
    void serviceFilesAreReadFromTheJarOnly() throws Exception {
        File jar = tmp.resolve("svc.jar").toFile();
        TestJars.moduleJar(jar, "ai.skillroy.testjava.agent.core.jarfixture.JarModuleTwo");
        ModuleClassLoader loader = new ModuleClassLoader(new URL[] {jar.toURI().toURL()},
                ModuleClassLoaderTest.class.getClassLoader());
        try {
            List<String> names = ModuleLoader.providerNames(loader.findResources(ModuleLoader.SERVICE_FILE));
            assertEquals(Collections.singletonList("ai.skillroy.testjava.agent.core.jarfixture.JarModuleTwo"),
                    names, "the test class path's own service file is not included");
        } finally {
            loader.close();
        }
    }

    /**
     * An embedding host ships the module jar on its own class path too (for its plugin class): the copy of a provider
     * a module jar of the agent already named is not loaded again and leaves no FAILED record, while any other
     * provider on the class path that cannot be loaded still does (finding S14, fail closed).
     */
    @Test
    void aClassPathCopyOfAJarModuleIsSkippedButAnUnloadableProviderStillFails() throws Exception {
        File home = tmp.resolve("home").toFile();
        File modules = new File(home, "modules");
        assertTrue(modules.mkdirs());
        TestJars.moduleJar(new File(modules, "10-jarmod.jar"), JarModule.class.getName());
        File hostServices = tmp.resolve("host-services").toFile();
        File serviceFile = new File(hostServices, ModuleLoader.SERVICE_FILE);
        assertTrue(serviceFile.getParentFile().mkdirs());
        Files.write(serviceFile.toPath(), (JarModule.class.getName() + "\nno.such.Provider\n")
                .getBytes(AgentHarness.UTF8));
        final URLClassLoader host = new URLClassLoader(new URL[] {hostServices.toURI().toURL()}, null);
        ClassLoader agentLoader = new ClassLoader(ModuleClassLoaderTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                List<URL> all = Collections.list(super.getResources(name));
                all.addAll(Collections.list(host.getResources(name)));
                return Collections.enumeration(all);
            }
        };
        RingLog log = new RingLog();
        ModuleLoader loader = new ModuleLoader(log);
        try {
            Map<String, String> options = new HashMap<String, String>();
            options.put("home", home.getPath());
            Map<String, ModuleLoader.Record> byId = new HashMap<String, ModuleLoader.Record>();
            for (ModuleLoader.Record record : loader.discover(options, agentLoader)) {
                byId.put(record.id, record);
            }
            ModuleLoader.Record jarModule = byId.get(new JarModule().id());
            assertNotNull(jarModule, byId.keySet().toString());
            assertTrue(jarModule.origin.endsWith("10-jarmod.jar"), jarModule.origin);
            assertTrue(jarModule.state == null, "not failed: " + jarModule.message);
            for (ModuleLoader.Record record : byId.values()) {
                assertTrue(!"FAILED".equals(record.state) || record.id.equals("no.such.Provider"),
                        "only the unloadable provider fails: " + record.id + ": " + record.message);
            }
            ModuleLoader.Record missing = byId.get("no.such.Provider");
            assertNotNull(missing, byId.keySet().toString());
            assertEquals("FAILED", missing.state);
            assertTrue(missing.message.contains("ClassNotFoundException"), missing.message);
            assertTrue(String.valueOf(log.tail(50)).contains("already loaded from a module jar"));
        } finally {
            for (ModuleClassLoader l : loader.loaders()) {
                l.close();
            }
            host.close();
        }
    }
}
