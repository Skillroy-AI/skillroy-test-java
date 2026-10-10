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

import ai.skillroy.testjava.fixture.hooks.SlipFormat;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix, pass 2, P2-L2-F6: a detach that cannot take the engine's lock within its budget (an install
 * is inside a slow retransformation) still names every class it leaves carrying a disarmed advice at WARN with the
 * remedy and sets D177's count, and the install that held the lock declares no capability and publishes no event into
 * the detached module when it ends, and reports the classes again. Before the fix the detach logged one line, set no
 * count, and the install declared {@code hook.slip.header} after the detach. By design the slot stays held for the
 * JVM's life, so this runs in a JVM of its own.
 */
class DetachLockFreshJvmTest {

    /** A JVM of its own (section 2.3 as built): skipped under a merging -Dtest, a failure otherwise. */
    @BeforeAll
    static void freshJvm() {
        Harness.assumeFreshJvm();
    }

    /** A foreign transformer that takes {@code ms} over SlipFormat's retransformation, once. */
    private static ClassFileTransformer slow(final long ms) {
        return new ClassFileTransformer() {
            private volatile boolean slept;

            @Override
            public byte[] transform(ClassLoader l, String name, Class<?> redefined, ProtectionDomain pd, byte[] b) {
                if (redefined == SlipFormat.class && !slept) {
                    slept = true;
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return null;
            }
        };
    }

    private static int warnings(FakeHookContext ctx, String className) {
        int n = 0;
        for (String line : ctx.log.all()) {
            if (line.startsWith("WARN") && line.contains(className + " (system) still carries a disarmed advice")
                    && line.contains("restart the target")) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aDetachThatCannotTakeTheLockStillNamesWhatItLeavesAndTheInstallDeclaresNothing() throws Exception {
        new SlipFormat().header("drill");
        final FakeHookContext ctx = new FakeHookContext();
        ctx.options.put(HookOptions.DETACH_BUDGET_MS, "1000");
        final Harness harness = new Harness(ctx);
        ClassFileTransformer slowOne = slow(4000);
        Harness.inst().addTransformer(slowOne, true);
        final AtomicReference<Object> outcome = new AtomicReference<Object>();
        Thread installer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    outcome.set(harness.install("slips", spec("slip.header", HookMode.OBSERVE, SlipFormat.class,
                            "header")));
                } catch (Throwable t) {
                    outcome.set(t);
                }
            }
        }, "test-installer");
        installer.start();
        Thread.sleep(800);
        long t0 = System.nanoTime();
        harness.module.uninstall();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms < 2500, "the detach stays near its budget: " + ms + " ms");
        assertEquals("1", System.getProperty(HookEngineImpl.ADVICE_LEFT_PROPERTY),
                "D177's count is set on the path that could not take the lock");
        assertTrue(warnings(ctx, SlipFormat.class.getName()) >= 1, "the class is named at WARN with the remedy: "
                + ctx.log.all());

        installer.join(15000);
        assertFalse(installer.isAlive());
        assertTrue(outcome.get() instanceof Map, "the install ended normally: " + outcome.get());
        assertFalse(ctx.capabilities.snapshot().containsKey("hook.slip.header"),
                "no capability is declared into a detached module: " + ctx.capabilities.snapshot().keySet());
        List<?> installed = ctx.events.topic(Topics.HOOKS_INSTALLED);
        assertTrue(installed.isEmpty(), "no hooks.installed from a detached module: " + installed);
        assertTrue(warnings(ctx, SlipFormat.class.getName()) >= 2, "the install reported the class again when it"
                + " ended");
        assertEquals("1", System.getProperty(HookEngineImpl.ADVICE_LEFT_PROPERTY));
        assertTrue(Harness.slotHeld(), "the disarmed handler keeps the slot while the advice exists");
        int events = ctx.events.topic("hook.slip.header").size();
        assertEquals("Slip: drill", new SlipFormat().header("drill"));
        assertEquals(events, ctx.events.topic("hook.slip.header").size(), "disarmed: no event");
        Harness.inst().removeTransformer(slowOne);

        FakeHookContext next = new FakeHookContext();
        HooksModule again = new HooksModule();
        again.install(next);
        next.ready();
        assertEquals("Spy slot held by a disarmed handler of an earlier agent; 1 classes still carry advice",
                next.capabilities.get(HooksModule.ENGINE).reason(), "a refused re-attach with the count");
        again.uninstall();
    }
}
