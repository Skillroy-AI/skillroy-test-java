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

import ai.skillroy.testjava.fixture.hooks.DepositRule;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first install of a JVM (phase6-contracts.md section 3.2 steps 2 and 3), in a JVM of its own, in order: a
 * catalogue that fits nothing appends nothing; a spy jar with an extra entry is refused and nothing is appended; the
 * reactor's jar is appended once.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstInstallFreshJvmTest {

    @TempDir
    static Path tmp;

    @Test
    @Order(1)
    void aCatalogueThatFitsNothingAppendsNothing() throws Exception {
        assertNull(Harness.bootSpy(), "a fresh JVM");
        Harness harness = new Harness();
        harness.ctx.map("hook.install", map("specs", Harness.json(Harness.spec("never", HookMode.OBSERVE,
                "ai.skillroy.testjava.fixture.hooks.Refusals$NeverLoaded", "x", "()I", null, null, null, false)),
                "catalogue", map("id", "none", "hash", "sha256:00")));
        assertNull(Harness.bootSpy(), "nothing permanent happens for a catalogue that fits nothing");
        harness.close();
    }

    @Test
    @Order(2)
    void aSpyJarHoldingMoreThanSpyIsRefusedAndNothingIsAppended() throws Exception {
        File real = new File(System.getProperty("skillroy.agent.hooks.spyJar"));
        File bad = new File(tmp.toFile(), "skillroy-agent-spy.jar");
        JarFile source = new JarFile(real);
        JarOutputStream out = new JarOutputStream(new FileOutputStream(bad));
        try {
            for (java.util.Enumeration<JarEntry> e = source.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                out.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    java.io.InputStream in = source.getInputStream(entry);
                    byte[] buffer = new byte[4096];
                    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                        out.write(buffer, 0, n);
                    }
                    in.close();
                }
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("ai/skillroy/testjava/agent/spy/Backdoor.class"));
            out.write(Files.readAllBytes(real.toPath()), 0, 8);
            out.closeEntry();
        } finally {
            out.close();
            source.close();
        }
        new DepositRule();
        FakeHookContext ctx = new FakeHookContext();
        ctx.options.put(HookOptions.SPY_JAR, bad.getPath());
        Harness harness = new Harness(ctx);
        RpcException refused = ctx.error("hook.install", map("specs", Harness.json(spec("deposit.computed",
                HookMode.OBSERVE, DepositRule.class, "depositFor")), "catalogue", map("id", "toys", "hash",
                "sha256:01")));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, refused.error().code());
        assertEquals("spy jar holds more than Spy: ai/skillroy/testjava/agent/spy/Backdoor.class",
                refused.error().data().get("reason"));
        assertNull(Harness.bootSpy(), "refused before the append");
        ctx.capabilities.reprobe(ctx);
        assertEquals("spy jar holds more than Spy: ai/skillroy/testjava/agent/spy/Backdoor.class",
                ctx.capabilities.get(HooksModule.ENGINE).reason(), "the matrix says why");
        harness.close();
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void theReactorsJarIsAppendedOnceAndASecondEngineFindsTheSameSpy() throws Exception {
        new DepositRule();
        Harness harness = new Harness();
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"));
        Class<?> boot = Harness.bootSpy();
        assertNotNull(boot);
        assertNull(boot.getClassLoader(), "defined by the bootstrap loader");
        Harness.assertLogContains(harness.ctx, "to the bootstrap search path (once for the JVM's life)");
        harness.ctx.call("hook.remove", map());
        harness.close();
        Harness second = new Harness();
        Map<String, Object> probe = second.ctx.map("hook.probe", map("specs", Collections.emptyList()));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) probe.get("spy")).get("onBootPath"));
        second.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"));
        assertTrue(Harness.bootSpy() == boot, "the same Class object");
        for (String line : second.ctx.log.all()) {
            assertTrue(!line.contains("bootstrap search path (once"), "no second append: " + line);
        }
        second.ctx.call("hook.remove", map());
        second.close();
    }
}
