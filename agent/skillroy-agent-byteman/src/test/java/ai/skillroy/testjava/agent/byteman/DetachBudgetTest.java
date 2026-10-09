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

import ai.skillroy.testjava.agent.byteman.sys.RuleHelper;
import ai.skillroy.testjava.fixture.byteman.DepositDesk;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

import static ai.skillroy.testjava.agent.byteman.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detach budget bounds the module's {@code uninstall} (phase6-contracts.md 7.5 and its fix-wave note; review
 * findings L1-F5 and L2-S4): a {@code rule.*} call that holds the module past the budget, and a removal slower than the
 * budget, each give a return within the budget; a removal that ends after the cut runs no module code (no log line,
 * nothing to the default uncaught-exception handler) and the names parked for the next instance then over-report, as
 * the contract records. The slow removal is a slow transformer of the JVM (a foreign transformer that takes 3 s over
 * the toy's retransformation on the uninstall's worker), not a seam of the module. A test class of its own (a fresh
 * JVM): a cut-short detach leaves Byteman's state for the JVM's life.
 */
class DetachBudgetTest {

    static final long BUDGET_MS = 1000;
    static final long HOLD_MS = 3000;
    /** What the bound allows on top of the budget: the thread start, the cut-short bookkeeping, a slow host. */
    static final long MARGIN_MS = 1500;
    static final String WORKER = "skillroy-uninstall-byteman";

    /**
     * Holds the engine's lock on a thread of its own for {@code ms}; returns once it is held. Works on a monitor and on
     * a {@link Lock}, so that the same test runs against both shapes of the engine.
     */
    static Thread holdLock(Engine engine, final long ms) throws Exception {
        Field f = Engine.class.getDeclaredField("lock");
        f.setAccessible(true);
        final Object lock = f.get(engine);
        final CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(new Runnable() {
            @Override
            public void run() {
                if (lock instanceof Lock) {
                    ((Lock) lock).lock();
                    try {
                        held.countDown();
                        pause(ms);
                    } finally {
                        ((Lock) lock).unlock();
                    }
                } else {
                    synchronized (lock) {
                        held.countDown();
                        pause(ms);
                    }
                }
            }
        }, "test-lock-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS), "the holder took the engine's lock");
        return holder;
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The time {@code module.uninstall()} takes, in ms. */
    static long timedUninstall(BytemanModule module) {
        long t0 = System.nanoTime();
        module.uninstall();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    static FakeAgentContext context(long budgetMs) {
        assertNotNull(TestAgent.instrumentation(), "the test agent's Instrumentation");
        FakeAgentContext ctx = new FakeAgentContext(TestAgent.instrumentation());
        ctx.options.put(Settings.BYTEMAN_JAR, System.getProperty("skillroy.test.bytemanJar"));
        ctx.options.put(Settings.DETACH_BUDGET_MS, Long.toString(budgetMs));
        return ctx;
    }

    static BytemanModule module(FakeAgentContext ctx) throws Exception {
        BytemanModule module = new BytemanModule(new File(System.getProperty("skillroy.test.sysJar")), null);
        module.install(ctx);
        return module;
    }

    private static Thread thread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive()) {
                return t;
            }
        }
        return null;
    }

    /** A foreign transformer that takes {@code ms} over the toy's retransformation on the uninstall's worker, once. */
    static final class SlowTransformer implements ClassFileTransformer {
        private final long ms;
        final AtomicBoolean slept = new AtomicBoolean();

        SlowTransformer(long ms) {
            this.ms = ms;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined != null && RuleLintTest.TOY.replace('.', '/').equals(className)
                    && WORKER.equals(Thread.currentThread().getName()) && slept.compareAndSet(false, true)) {
                pause(ms);
            }
            return null;
        }
    }

    @Test
    void aCallHoldingTheModulePastTheBudgetDoesNotHoldTheUninstall() throws Exception {
        FakeAgentContext ctx = context(BUDGET_MS);
        BytemanModule module = module(ctx);
        Thread holder = holdLock(module.engine(), HOLD_MS);
        long ms = timedUninstall(module);
        assertTrue(ms < BUDGET_MS + MARGIN_MS, "uninstall took " + ms + " ms with a detach budget of " + BUDGET_MS
                + " ms while a call held the module for " + HOLD_MS + " ms");
        assertTrue(ctx.log.all().contains("detach budget of " + BUDGET_MS + " ms"), ctx.log.all());
        holder.join(HOLD_MS + 10000);
        assertFalse(module.engine().loaded(), "nothing was loaded");
    }

    @Test
    void aRemovalSlowerThanTheBudgetIsCutShortAndItsLateEndRunsNoModuleCode() throws Exception {
        FreshJvm.assume(false);
        Instrumentation inst = TestAgent.instrumentation();
        assertNotNull(inst, "the test agent's Instrumentation");
        DepositDesk desk = new DepositDesk();
        desk.ceiling("STAFF-1");
        final List<Throwable> uncaught = new CopyOnWriteArrayList<Throwable>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                uncaught.add(e);
            }
        });
        SlowTransformer slow = new SlowTransformer(HOLD_MS);
        try {
            FakeAgentContext ctx = context(BUDGET_MS);
            BytemanModule module = module(ctx);
            ctx.call("rule.submit", args("rule", RuleLintTest.rule("slow", "ceiling", "fired($0)")));
            long firedBefore = RuleHelper.counters()[0];
            desk.ceiling("STAFF-2");
            assertEquals(firedBefore + 1, RuleHelper.counters()[0], "the rule is installed");
            inst.addTransformer(slow, true);

            long ms = timedUninstall(module);
            int linesAtReturn = ctx.log.lines.size();
            assertTrue(ms < BUDGET_MS + MARGIN_MS, "uninstall took " + ms + " ms with a detach budget of "
                    + BUDGET_MS + " ms while the removal took " + HOLD_MS + " ms");
            assertTrue(slow.slept.get(), "the removal reached the slow transformer");
            assertTrue(ctx.log.all().contains("rules still installed after detach: [slow]"), ctx.log.all());
            assertTrue(RuleHelper.held(), "the disarmed handler keeps the helper's slot");

            Thread worker = thread(WORKER);
            if (worker != null) {
                worker.join(HOLD_MS + 10000);
                assertFalse(worker.isAlive(), "the worker ends once the removal returns");
            }
            assertEquals(linesAtReturn, ctx.log.lines.size(), "the late end of the removal logs nothing: "
                    + ctx.log.tail(5));
            assertTrue(uncaught.isEmpty(), "nothing reaches the default handler: " + uncaught);

            long firedAfter = RuleHelper.counters()[0];
            desk.ceiling("STAFF-3");
            assertEquals(firedAfter, RuleHelper.counters()[0], "the late removal took the rule out");
            FakeAgentContext next = context(BUDGET_MS);
            module(next);
            Capability refused = next.capabilities.probe(BytemanModule.CAPABILITY);
            assertEquals(CapabilityState.UNAVAILABLE, refused.state());
            assertEquals("Byteman rules of an earlier agent instance are still installed: slow; restart the target",
                    refused.reason(), "the parked names over-report after a late removal (7.5)");
        } finally {
            inst.removeTransformer(slow);
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }
}
