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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.hook.ClassDeny;
import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.fixture.hooks.DepositRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module itself (phase6-contracts.md sections 3.2, 3.7, 4.2): its id and version, the six primitives' flags,
 * timeouts and capability, a {@code hooks.*} option out of range failing the install, the SPI's registration, the
 * spy jar's content check of section 3.2 step 3, and the {@code Spy} version the engine expects. Phase 8
 * (phase8-contracts.md sections 6.5, 13.1 row 24): {@code hooks.deny} read and refused naming the key, and the
 * engine registered as the catalogue's {@code ClassDeny}.
 */
class HooksModuleTest {

    @TempDir
    Path tmp;

    @Test
    void idVersionAndThePrimitivesFlags() {
        HooksModule module = new HooksModule();
        assertEquals("hooks", module.id());
        assertTrue(module.version().matches("\\d+\\.\\d+\\.\\d+.*"), "filtered: " + module.version());
        FakeHookContext ctx = new FakeHookContext();
        module.install(ctx);
        Map<String, PrimitiveSpec> specs = new LinkedHashMap<String, PrimitiveSpec>();
        for (PrimitiveSpec spec : ctx.primitives.list()) {
            specs.put(spec.name(), spec);
        }
        assertEquals(HookPrimitives.NAMES.size(), specs.size());
        for (String name : HookPrimitives.NAMES) {
            assertEquals(ThreadPolicy.WORKER, specs.get(name).thread(), name);
        }
        assertEquals("hook.engine", specs.get("hook.probe").capabilityId());
        assertEquals(10000, specs.get("hook.probe").timeoutMs());
        assertFalse(specs.get("hook.install").mutating(), "D119: an install changes bytecode, not behaviour");
        assertEquals(60000, specs.get("hook.install").timeoutMs());
        assertEquals("hook.engine", specs.get("hook.install").capabilityId());
        assertTrue(specs.get("hook.stub").mutating());
        assertTrue(specs.get("hook.stub").destructive());
        assertEquals("hook.engine", specs.get("hook.stub").capabilityId());
        assertFalse(specs.get("hook.remove").mutating(), "a restore is never gated");
        assertNull(specs.get("hook.remove").capabilityId());
        assertEquals(30000, specs.get("hook.remove").timeoutMs());
        assertNull(specs.get("hook.health").capabilityId());
        assertEquals("hook.engine", specs.get("hook.events").capabilityId());
        assertSame(module.engine(), ctx.spi.first(HookEngine.class));
        assertEquals(FakeHookContext.Caps.PENDING, ctx.capabilities.get(HooksModule.ENGINE).reason(),
                "declared with the probe: pending until the target is ready");
        module.uninstall();
        module.uninstall();
        ctx.rollback();
    }

    @Test
    void anOptionOutOfRangeOrNotANumberFailsTheInstallNamingTheKey() {
        String[][] bad = {{HookOptions.MAX_CLASS_BYTES, "100"}, {HookOptions.MAX_CLASS_BYTES, "lots"},
            {HookOptions.MAX_METHODS, "0"}, {HookOptions.MAX_CLASSES, "5000"},
            {HookOptions.DETACH_BUDGET_MS, "9500"}, {HookOptions.DETACH_BUDGET_MS, "999"},
            {HookOptions.CAPTURE_MAX_BYTES, "-1"}};
        for (String[] kv : bad) {
            FakeHookContext ctx = new FakeHookContext();
            ctx.options.put(kv[0], kv[1]);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new HooksModule().install(ctx), kv[0] + "=" + kv[1]);
            assertTrue(e.getMessage().contains(kv[0]), e.getMessage());
            assertTrue(ctx.primitives.list().isEmpty(), "nothing registered before the check");
        }
        FakeHookContext ok = new FakeHookContext();
        ok.options.put(HookOptions.DETACH_BUDGET_MS, "9000");
        ok.options.put(HookOptions.MAX_CLASS_BYTES, "16777216");
        HooksModule module = new HooksModule();
        module.install(ok);
        assertEquals(9000, module.engine().options.detachBudgetMs);
        module.uninstall();
    }

    @Test
    void anInvalidDenyOptionFailsTheInstallNamingTheKey() {
        String[] bad = {"a..b", "a.b-c.D", "a.**.C"};
        for (String value : bad) {
            FakeHookContext ctx = new FakeHookContext();
            ctx.options.put(HookOptions.DENY, "x.Y," + value);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new HooksModule().install(ctx), value);
            assertTrue(e.getMessage().startsWith("option hooks.deny: pattern 1 "), e.getMessage());
            assertTrue(ctx.primitives.list().isEmpty(), "nothing registered before the check");
        }
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            many.append(i == 0 ? "" : ",").append("a.b.C").append(i);
        }
        FakeHookContext tooMany = new FakeHookContext();
        tooMany.options.put(HookOptions.DENY, many.toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new HooksModule().install(tooMany));
        assertTrue(e.getMessage().contains("hooks.deny") && e.getMessage().contains("64"), e.getMessage());
    }

    @Test
    void theEngineIsRegisteredAsTheCataloguesClassDeny() {
        FakeHookContext ctx = new FakeHookContext();
        ctx.options.put(HookOptions.DENY, "a.b.*");
        HooksModule module = new HooksModule();
        module.install(ctx);
        assertEquals(Collections.singletonList(module.engine()), ctx.spi.all(ClassDeny.class),
                "registered beside the HookEngine at install");
        assertSame(ctx.spi.first(HookEngine.class), ctx.spi.first(ClassDeny.class));
        assertEquals(Arrays.asList("a.b.*"), module.engine().options.deny);
        assertNull(module.engine().refuse("a.b.C"), "the catalogue's source holds nothing yet; the option is apart");
        assertEquals("denied by the agent option hooks.deny: a.b.*", module.engine().deniedBy("a.b.C"));
        assertNull(module.engine().deniedBy("a.b.c.D"));
        module.uninstall();
        ctx.rollback();
    }

    @Test
    void theEngineExpectsTheSpyVersionOfTheSpyJar() throws Exception {
        File jar = new File(System.getProperty("skillroy.agent.hooks.spyJar"));
        URLClassLoader isolated = new URLClassLoader(new URL[] {jar.toURI().toURL()}, null);
        try {
            Class<?> spy = isolated.loadClass(SpyBoot.SPY);
            assertEquals(SpyBoot.VERSION, spy.getField("VERSION").getInt(null));
        } finally {
            isolated.close();
        }
        new DepositRule();
    }

    @Test
    void theSpyJarsContentCheck() throws Exception {
        File real = new File(System.getProperty("skillroy.agent.hooks.spyJar"));
        assertNull(SpyBoot.checkJar(real), "the reactor's spy jar is accepted");
        Map<String, byte[]> entries = read(real);

        Map<String, byte[]> extra = new LinkedHashMap<String, byte[]>(entries);
        extra.put("ai/skillroy/testjava/agent/spy/Extra.class", entries.get(SpyBoot.RESOURCE));
        assertEquals("spy jar holds more than Spy: ai/skillroy/testjava/agent/spy/Extra.class",
                SpyBoot.checkJar(write("extra.jar", extra)));

        Map<String, byte[]> service = new LinkedHashMap<String, byte[]>(entries);
        service.put("META-INF/services/java.lang.Runnable", "x".getBytes("UTF-8"));
        assertTrue(SpyBoot.checkJar(write("service.jar", service)).startsWith("spy jar holds more than Spy"));

        Map<String, byte[]> outside = new LinkedHashMap<String, byte[]>(entries);
        outside.put(SpyBoot.PACKAGE + "Spy$Handler.class", classBytes(DepositRule.class));
        String refusal = SpyBoot.checkJar(write("outside.jar", outside));
        assertTrue(refusal.contains("references ") && refusal.endsWith(", outside java/"), refusal);

        Map<String, byte[]> newer = new LinkedHashMap<String, byte[]>(entries);
        byte[] spy = entries.get(SpyBoot.RESOURCE).clone();
        spy[7] = 69;
        newer.put(SpyBoot.RESOURCE, spy);
        assertEquals("spy jar class " + SpyBoot.RESOURCE + " is not a --release 8 class file (major 69)",
                SpyBoot.checkJar(write("newer.jar", newer)));

        Map<String, byte[]> none = new LinkedHashMap<String, byte[]>(entries);
        none.remove(SpyBoot.RESOURCE);
        assertTrue(SpyBoot.checkJar(write("none.jar", none)).startsWith("spy jar holds no Spy"));
        assertTrue(SpyBoot.checkJar(new File(tmp.toFile(), "missing.jar")).startsWith("spy jar cannot be read"));
    }

    /**
     * Phase 6 security fix, pass 2, P2-L1-F5: a spy jar that {@code hooks.spyJar} names must, before the append, be
     * writable by nobody but its owner, as {@code byteman.jar}: the jar, its directory and a link's directory. The
     * home's own jar is trusted as the home is (content check only). Which of the two applies follows the option.
     */
    @Test
    void aNamedSpyJarMustBeWritableByNobodyButItsOwner() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        File real = new File(System.getProperty("skillroy.agent.hooks.spyJar"));
        java.nio.file.Path shared = tmp.resolve("shared");
        java.nio.file.Files.createDirectory(shared);
        File copy = shared.resolve("skillroy-agent-spy.jar").toFile();
        java.nio.file.Files.copy(real.toPath(), copy.toPath());
        java.nio.file.Files.setPosixFilePermissions(copy.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
        java.nio.file.Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwx------"));
        assertNull(SpyBoot.checkJar(copy, true), "an owner-only directory");

        java.nio.file.Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertEquals("spy jar is writable by others: " + copy, SpyBoot.checkJar(copy, true));
        assertNull(SpyBoot.checkJar(copy, false), "the home's own jar: the content check only");
        java.nio.file.Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwx------"));
        java.nio.file.Files.setPosixFilePermissions(copy.toPath(), PosixFilePermissions.fromString("rw-rw-r--"));
        assertEquals("spy jar is writable by others: " + copy, SpyBoot.checkJar(copy, true));
        java.nio.file.Files.setPosixFilePermissions(copy.toPath(), PosixFilePermissions.fromString("rw-r--r--"));

        java.nio.file.Path open = tmp.resolve("open");
        java.nio.file.Files.createDirectory(open);
        java.nio.file.Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));
        File link = java.nio.file.Files.createSymbolicLink(open.resolve("spy.jar"), copy.toPath()).toFile();
        assertEquals("spy jar is writable by others: " + link, SpyBoot.checkJar(link, true),
                "a link another user could re-point between the check and the append");
        assertTrue(SpyBoot.checkJar(new File(tmp.toFile(), "missing.jar"), true).startsWith("spy jar cannot be read"));

        FakeHookContext named = new FakeHookContext();
        assertTrue(HookOptions.read(named).spyJarNamed, "the tests name the reactor's jar: " + HookOptions.read(named)
                .spyJar);
        String saved = System.getProperty("skillroy.agent.hooks.spyJar");
        System.clearProperty("skillroy.agent.hooks.spyJar");
        try {
            FakeHookContext home = new FakeHookContext();
            home.options.put("home", tmp.toString());
            HookOptions fromHome = HookOptions.read(home);
            assertFalse(fromHome.spyJarNamed);
            assertEquals(new File(tmp.toFile(), HookOptions.SPY_JAR_NAME).getPath(), fromHome.spyJar);
        } finally {
            System.setProperty("skillroy.agent.hooks.spyJar", saved);
        }
    }

    private static Map<String, byte[]> read(File jar) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        JarFile file = new JarFile(jar);
        try {
            for (Enumeration<JarEntry> e = file.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                if (!entry.isDirectory()) {
                    out.put(entry.getName(), bytes(file.getInputStream(entry)));
                }
            }
        } finally {
            file.close();
        }
        return out;
    }

    private File write(String name, Map<String, byte[]> entries) throws Exception {
        File jar = new File(tmp.toFile(), name);
        JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } finally {
            out.close();
        }
        return jar;
    }

    static byte[] classBytes(Class<?> type) throws Exception {
        String name = type.getName();
        return bytes(type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class"));
    }

    private static byte[] bytes(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
