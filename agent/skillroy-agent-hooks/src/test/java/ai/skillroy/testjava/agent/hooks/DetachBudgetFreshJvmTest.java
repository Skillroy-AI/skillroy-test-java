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
import ai.skillroy.testjava.fixture.hooks.Returns;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.TimeUnit;

import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detach budget (phase6-contracts.md section 3.5, D117): a slow retransformation (a transformer that sleeps)
 * leaves the second class carrying a disarmed advice, the handler stays in the slot, the class is named at WARN,
 * nothing is thrown, and a fresh engine's probe then reports the slot held by a disarmed handler of an earlier agent.
 * By design the slot stays held for the JVM's life, so this runs in a JVM of its own.
 */
class DetachBudgetFreshJvmTest {

    /** A JVM of its own (section 2.3 as built): skipped under a merging -Dtest, a failure otherwise. */
    @BeforeAll
    static void freshJvm() {
        Harness.assumeFreshJvm();
    }

    @Test
    void aSlowRestoreLeavesInertAdviceAndTheSlotHeld() throws Exception {
        DepositRule rule = new DepositRule();
        Returns returns = new Returns();
        FakeHookContext ctx = new FakeHookContext();
        ctx.options.put(HookOptions.DETACH_BUDGET_MS, "1000");
        HooksModule module = new HooksModule();
        module.install(ctx);
        ctx.ready();
        ctx.map("hook.install", FakeHookContext.map("specs", Harness.json(
                spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("returns.count", HookMode.OBSERVE, Returns.class, "count")), "catalogue",
                FakeHookContext.map("id", "toys", "hash", "sha256:02")));
        ClassFileTransformer slow = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (classBeingRedefined == DepositRule.class) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return null;
            }
        };
        Harness.inst().addTransformer(slow, true);
        long t0 = System.nanoTime();
        module.uninstall();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        Harness.inst().removeTransformer(slow);
        assertTrue(ms < 4000, "the detach stays near its budget: " + ms + " ms");
        assertTrue(Harness.slotHeld(), "the disarmed handler stays in the slot while an advice exists");
        Harness.assertLogContains(ctx, Returns.class.getName() + " (system) still carries a disarmed advice");
        assertEquals("1", System.getProperty(HookEngineImpl.ADVICE_LEFT_PROPERTY));
        int events = ctx.events.topic("hook.returns.count").size();
        long enters = Harness.counters()[0];
        assertEquals(7, returns.count(), "the inert advice changes nothing");
        assertEquals(events, ctx.events.topic("hook.returns.count").size(), "disarmed: no event");
        assertTrue(Harness.counters()[0] > enters, "the advice still calls Spy");
        assertEquals(new java.math.BigDecimal("20.00"), rule.depositFor("T1"));

        FakeHookContext next = new FakeHookContext();
        HooksModule again = new HooksModule();
        again.install(next);
        next.ready();
        assertEquals("Spy slot held by a disarmed handler of an earlier agent; 1 classes still carry advice",
                next.capabilities.get(HooksModule.ENGINE).reason(), "a refused re-attach, not a free slot");
        again.uninstall();
    }
}
