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

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.fixture.hooks.ChildFirstLoader;
import ai.skillroy.testjava.fixture.hooks.DepositRule;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * After a complete uninstall the module's loader is collectable while the application threads that ran hooked
 * methods are still alive (phase6-contracts.md section 3.2: the engine's per-thread state holds bootstrap types only,
 * so an application thread's map never pins the module's loader; BS3 RESULT section 9 fact 22).
 */
class LoaderCollectableFreshJvmTest {

    @Test
    public void theModuleLoaderIsCollectedWhileApplicationThreadsLive() throws Exception {
        final DepositRule rule = new DepositRule();
        rule.depositFor("T0");
        final CountDownLatch fired = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        WeakReference<ClassLoader> ref = installFireUninstall(rule, fired, release);
        long deadline = System.currentTimeMillis() + 30000;
        while (ref.get() != null && System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(ref.get(), "the module's loader was collected");
        release.countDown();
    }

    private static WeakReference<ClassLoader> installFireUninstall(final DepositRule rule,
                                                                   final CountDownLatch fired,
                                                                   final CountDownLatch release) throws Exception {
        ChildFirstLoader loader = ModuleLoaderFixture.loader();
        AgentModule module = ModuleLoaderFixture.module(loader);
        FakeHookContext ctx = new FakeHookContext();
        module.install(ctx);
        ctx.ready();
        HookEngine engine = ctx.hooks();
        engine.install(Collections.singletonList(spec("deposit.computed", HookMode.CAPTURE, DepositRule.class,
                "depositFor", Harness.capture(false, true, 0))), "toys", "sha256:04", false);
        Thread application = new Thread(new Runnable() {
            @Override
            public void run() {
                rule.depositFor("P1");
                fired.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "lending-desk-engine");
        application.setDaemon(true);
        application.start();
        fired.await();
        rule.depositFor("P2");
        assertEquals(2, ctx.events.topic("hook.deposit.computed").size());
        engine.remove(Collections.<String>emptyList());
        module.uninstall();
        ctx.rollback();
        loader.close();
        return new WeakReference<ClassLoader>(loader);
    }
}
