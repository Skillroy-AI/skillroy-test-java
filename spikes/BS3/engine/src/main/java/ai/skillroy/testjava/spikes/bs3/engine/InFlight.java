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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BS3 item 5: a host thread is inside the hooked method ({@code DepositRule.holdReturn}, parked on a latch) while the
 * class is restored. Variant A keeps the handler during the restore, variant B clears {@code Spy}'s handler first.
 */
final class InFlight {

    private static final String HOLD_DESC =
            "(Ljava/util/concurrent/CountDownLatch;Ljava/util/concurrent/CountDownLatch;J)Ljava/lang/String;";

    private final Ctx c;

    InFlight(Ctx c) {
        this.c = c;
    }

    void run() throws Throwable {
        c.r.section("item 5: a call in flight during remove");
        variant("a", true);
        variant("b", false);
    }

    private void variant(String v, boolean keepHandler) throws Throwable {
        Report r = c.r;
        final Object rule = c.host.newToyLoader("inflight-" + v).loadClass(HostView.DEPOSIT_RULE).newInstance();
        Class<?> k = rule.getClass();
        String id = "toy.hold." + v;
        HookEngine.Installed in = c.engine.install(new HookEngine.Spec(id, k, "holdReturn", HOLD_DESC,
                HookMode.OBSERVE));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Object> result = new AtomicReference<Object>();
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        long seq = c.ring.last();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(HostView.invoke(rule, "holdReturn", new Class<?>[] {CountDownLatch.class,
                        CountDownLatch.class, long.class}, entered, release, 10000L));
                } catch (Throwable e) {
                    thrown.set(e);
                }
            }
        }, "lending-desk-return-" + v);
        t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread th, Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        boolean inside = entered.await(5, TimeUnit.SECONDS);
        long[] s0 = SpyAccess.counters();
        if (!keepHandler) {
            SpyAccess.handler(null);
        }
        long restoredAt = System.nanoTime();
        HookEngine.Removal rm = c.engine.remove(id, keepHandler);
        long restoreDone = System.nanoTime();
        release.countDown();
        t.join(10000);
        long[] s1 = SpyAccess.counters();
        if (!keepHandler) {
            SpyAccess.handler(c.registry);
        }
        int exitsAfterRestore = 0;
        for (EventRing.Event e : c.ring.since(seq)) {
            r.line("  event in flight " + v + ": " + e + (e.nanos > restoreDone ? "  (after the restore)" : ""));
            if (e.hookId.equals(id) && "exit".equals(e.phase) && e.nanos > restoreDone) {
                exitsAfterRestore++;
            }
        }
        // A new call after the restore: no Spy call at all.
        final CountDownLatch e2 = new CountDownLatch(1);
        final CountDownLatch r2 = new CountDownLatch(0);
        long[] s2 = SpyAccess.counters();
        Object second = HostView.invoke(rule, "holdReturn", new Class<?>[] {CountDownLatch.class,
            CountDownLatch.class, long.class}, e2, r2, 1000L);
        long[] s3 = SpyAccess.counters();
        c.registry.forget(id);
        String key = "item5." + v;
        r.put(key + ".handlerDuringRestore", keepHandler ? "kept" : "cleared (Spy.handler = null)");
        r.put(key + ".install", in.status);
        r.put(key + ".threadWasInside", inside);
        r.put(key + ".restore", rm.status + " in " + Report.ms(restoreDone - restoredAt) + " ms, identical "
                + rm.bytesIdentical);
        r.put(key + ".threadResult", result.get() + (thrown.get() == null ? "" : " threw " + thrown.get()));
        r.put(key + ".exitEventsAfterRestore", exitsAfterRestore);
        r.put(key + ".spyCountersDuring", "enters +" + (s1[0] - s0[0]) + ", unhandled +" + (s1[1] - s0[1])
                + ", failures +" + (s1[2] - s0[2]));
        r.put(key + ".nextCall", second + ", Spy enters +" + (s3[0] - s2[0]));
        boolean ok = inside && "returned".equals(result.get()) && thrown.get() == null && rm.bytesIdentical
                && s3[0] == s2[0];
        if (keepHandler) {
            ok &= exitsAfterRestore == 1;
        } else {
            ok &= s1[1] - s0[1] == 1 && exitsAfterRestore == 0;
        }
        c.check("item5." + v + ".inFlightCompletesOnOldVersion", ok,
                "result " + result.get() + ", exit events after restore " + exitsAfterRestore);
    }
}
