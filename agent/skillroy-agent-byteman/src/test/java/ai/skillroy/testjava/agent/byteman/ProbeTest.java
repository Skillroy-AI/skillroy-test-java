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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code rule.engine} probe's reasons (phase6-contracts.md 7.5, 7.6): no Instrumentation, no option, a jar without
 * Byteman's {@code Premain-Class}, a jar in a directory others may write to, and Byteman's {@code loaded} property set
 * with and without a loadable {@code RuleManager}. A test class of its own (surefire runs each in a fresh JVM): it sets
 * Byteman's property for the JVM.
 */
class ProbeTest {

    @TempDir
    Path tmp;

    @org.junit.jupiter.api.BeforeEach
    void freshJvm() {
        FreshJvm.assume(false);
    }

    @AfterEach
    void clearProperty() throws Exception {
        System.clearProperty(Engine.LOADED_PROPERTY);
        park(null);
    }

    private static void park(Object value) throws Exception {
        Class<?> manager = Class.forName(Engine.MANAGER, true, ClassLoader.getSystemClassLoader());
        Method park = manager.getDeclaredMethod("park", Object.class);
        park.setAccessible(true);
        park.invoke(null, value);
    }

    private static String bytemanJar() {
        String jar = System.getProperty("skillroy.test.bytemanJar");
        assertNotNull(jar, "skillroy.test.bytemanJar is set by the module's POM");
        return jar;
    }

    private Capability probe(String jar, ClassLoader system) throws Exception {
        FakeAgentContext ctx = new FakeAgentContext(TestAgent.instrumentation());
        assertNotNull(ctx.instrumentation(), "the test agent's Instrumentation");
        if (jar != null) {
            ctx.options.put(Settings.BYTEMAN_JAR, jar);
        }
        BytemanModule module = new BytemanModule(new File(System.getProperty("skillroy.test.sysJar")), system);
        module.install(ctx);
        return ctx.capabilities.probe(BytemanModule.CAPABILITY);
    }

    private Path jarWithManifest(Path dir, String premain) throws Exception {
        Files.createDirectories(dir);
        Path jar = dir.resolve("byteman.jar");
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (premain != null) {
            m.getMainAttributes().put(new Attributes.Name("Premain-Class"), premain);
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()), m)) {
            out.flush();
        }
        return jar;
    }

    @Test
    void withoutInstrumentationTheCapabilityIsSetUnavailableAtInstall() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext(null);
        new BytemanModule(null, null).install(ctx);
        Capability c = ctx.capabilities.get(BytemanModule.CAPABILITY);
        assertEquals(CapabilityState.UNAVAILABLE, c.state());
        assertEquals("no Instrumentation in EMBEDDED mode", c.reason());
        assertEquals(Arrays.asList("rule.check", "rule.list", "rule.submit", "rule.unload"),
                Arrays.asList(ctx.primitives.list().get(0).name(), ctx.primitives.list().get(1).name(),
                        ctx.primitives.list().get(2).name(), ctx.primitives.list().get(3).name()));
    }

    @Test
    void theJarReasons() throws Exception {
        assertEquals("no byteman.jar configured", probe(null, null).reason());
        Path fake = jarWithManifest(tmp.resolve("fake"), "com.example.NotByteman");
        assertEquals("not a Byteman jar: " + fake, probe(fake.toString(), null).reason());
        assertEquals("not a Byteman jar: relative/byteman.jar", probe("relative/byteman.jar", null).reason());
        assertEquals("not a Byteman jar: " + tmp.resolve("missing.jar"),
                probe(tmp.resolve("missing.jar").toString(), null).reason());
        Capability ok = probe(bytemanJar(), null);
        assertEquals(CapabilityState.OK, ok.state(), ok.toString());
        assertEquals("in-process", ok.strategy());
    }

    @Test
    void aJarInADirectoryOthersMayWriteToIsRefused() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX only here");
        Path shared = tmp.resolve("shared");
        Path jar = jarWithManifest(shared, JarCheck.MAIN);
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertEquals("byteman.jar is writable by others: " + jar, probe(jar.toString(), null).reason());
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-rw-rw-"));
        assertEquals("byteman.jar is writable by others: " + jar, probe(jar.toString(), null).reason());
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(CapabilityState.OK, probe(jar.toString(), null).state(), "a jar of the right shape is enough");
    }

    @Test
    void bytemanLoadedWithoutTheManagerIsTheHosts() throws Exception {
        System.setProperty(Engine.LOADED_PROPERTY, "true");
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], null)) {
            assertEquals("Byteman was loaded by the host; the agent cannot reach its rule base",
                    probe(bytemanJar(), isolated).reason());
        }
    }

    @Test
    void bytemanLoadedWithTheManagerHoldingNothingOrNamesIsAnEarlierInstancesRules() throws Exception {
        System.setProperty(Engine.LOADED_PROPERTY, "true");
        assertEquals("Byteman rules of an earlier agent instance are still installed: unknown (see the earlier"
                + " instance's log); restart the target", probe(bytemanJar(), null).reason());
        park(Collections.unmodifiableList(Arrays.asList("limit", "waiver")));
        assertEquals("Byteman rules of an earlier agent instance are still installed: limit, waiver; restart the"
                + " target", probe(bytemanJar(), null).reason());
        ProbeResult peek = new Engine(new FakeAgentContext(TestAgent.instrumentation()), Settings.defaults(),
                null, ClassLoader.getSystemClassLoader(), Collections.<ClassLoader>emptySet()).probe();
        assertEquals("no byteman.jar configured", peek.reason(), "the jar is checked first");
    }
}
