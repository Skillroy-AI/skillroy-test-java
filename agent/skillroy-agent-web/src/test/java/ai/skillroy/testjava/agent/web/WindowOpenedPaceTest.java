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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import org.junit.jupiter.api.Test;

import java.awt.EventQueue;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The pace of the re-probe after {@code window.opened} (amendment A38, phase4-contracts.md sections 4.1 and 16.4): a
 * re-probe cycle at most once a second, no event dropped (an event during a cycle gets one further cycle), the wait on
 * an agent worker and never on the publishing thread, neither a wait nor a probe without a candidate, and a waiting
 * worker woken by uninstall. Deterministic: the module's clock is a {@link FakePace} that moves only when told or
 * waited on, and the adapter's probe can be held while events arrive; one test runs on the real clock.
 */
class WindowOpenedPaceTest {

    /**
     * An adapter whose technology is present but not loaded by the application: a candidate of every re-probe, whose
     * result never changes (so no full cycle runs); it counts its probes, records when they happened on its clock,
     * and can hold the next probe until released.
     */
    static final class LateAdapter implements WebEngineAdapter {
        final AtomicInteger probes = new AtomicInteger();
        final List<Long> probedAt = new CopyOnWriteArrayList<Long>();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch hold;
        volatile WebModule.Pace clock;

        @Override
        public String id() {
            return "late";
        }

        @Override
        public ProbeResult probe(ClassLoader hostLoader) {
            probes.incrementAndGet();
            WebModule.Pace c = clock;
            if (c != null) {
                probedAt.add(Long.valueOf(c.nanoTime()));
            }
            CountDownLatch h = hold;
            if (h != null) {
                entered.countDown();
                try {
                    h.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ProbeResult.unavailable(WebCapabilities.NOT_LOADED);
        }

        @Override
        public List<WebBrowser> browsers(Collection<?> seeds) {
            return Collections.emptyList();
        }

        @Override
        public List<CdpEndpoint> cdpEndpoints(Collection<?> seeds) {
            return Collections.emptyList();
        }

        @Override
        public ProbeResult enumerationState() {
            return ProbeResult.ok();
        }

        @Override
        public void detach() {
            // nothing added
        }
    }

    @Test
    void aHundredEventsDuringACycleCauseExactlyOneFurtherCycleASecondLaterAfterOneWaitOnAWorker() throws Exception {
        LateAdapter a = new LateAdapter();
        try (Harness h = new Harness(a).install().ready()) {
            a.clock = h.pace;
            int before = a.probes.get();
            CountDownLatch release = new CountDownLatch(1);
            a.hold = release;
            opened(h, 1);
            assertTrue(a.entered.await(10, TimeUnit.SECONDS), "the first cycle is probing");
            a.hold = null;
            for (int i = 2; i <= 100; i++) {
                opened(h, i);
            }
            release.countDown();
            awaitIdle(h);
            assertEquals(before + 2, a.probes.get(), "100 events, one of them before the cycle: two cycles");
            assertEquals(Collections.singletonList(Long.valueOf(WebModule.REPROBE_PACE_MS)), h.pace.waitedMs,
                    "one wait: the rest of the second");
            assertAgentWorker(h.pace.waitThreads.get(0));
            assertEquals(WebModule.REPROBE_PACE_MS, ms(a.probedAt.get(1) - a.probedAt.get(0)),
                    "the second cycle a second after the first");
        }
    }

    @Test
    void anEventASecondAfterTheLastCycleStartsOneAtOnceWithoutAWait() throws Exception {
        LateAdapter a = new LateAdapter();
        try (Harness h = new Harness(a).install().ready()) {
            a.clock = h.pace;
            int before = a.probes.get();
            opened(h, 1);
            awaitIdle(h);
            h.pace.advance(WebModule.REPROBE_PACE_MS);
            opened(h, 2);
            awaitIdle(h);
            assertEquals(before + 2, a.probes.get(), "one cycle per event");
            assertTrue(h.pace.waitedMs.isEmpty(), "no wait once a second has passed: " + h.pace.waitedMs);
        }
    }

    @Test
    void anEventWithinTheSecondWaitsOutTheRestOnAnAgentWorkerNeverOnThePublishingThread() throws Exception {
        LateAdapter a = new LateAdapter();
        try (final Harness h = new Harness(a).install().ready()) {
            a.clock = h.pace;
            opened(h, 1);
            awaitIdle(h);
            h.pace.advance(300);
            EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    opened(h, 2); // as Swing publishes it: on the EDT, which only marks and posts
                }
            });
            awaitIdle(h);
            assertEquals(Collections.singletonList(Long.valueOf(700)), h.pace.waitedMs, "the rest of the second");
            assertAgentWorker(h.pace.waitThreads.get(0));
            assertEquals(2, a.probedAt.size());
            assertEquals(WebModule.REPROBE_PACE_MS, ms(a.probedAt.get(1) - a.probedAt.get(0)));
        }
    }

    @Test
    void eventsWithoutACandidateNeitherWaitNorProbe() throws Exception {
        ScriptedAdapter present = new ScriptedAdapter("present");
        try (Harness h = new Harness(present).install().ready()) {
            int probes = present.probes.get();
            for (int i = 0; i < 10; i++) {
                opened(h, i);
                awaitIdle(h);
            }
            assertEquals(probes, present.probes.get(), "a present adapter is not probed again");
            assertTrue(h.pace.waitedMs.isEmpty(), "nothing to probe: nothing to wait for " + h.pace.waitedMs);
        }
    }

    @Test
    void uninstallWakesAWaitingWorkerWhichThenProbesNothing() throws Exception {
        LateAdapter a = new LateAdapter();
        Harness h = new Harness(a).install().ready();
        try {
            opened(h, 1);
            awaitIdle(h);
            h.pace.holdWaits = true; // the clock stands still: only a wake-up ends the wait
            opened(h, 2);
            assertTrue(h.pace.waiting.await(10, TimeUnit.SECONDS), "the worker waits out the second");
            int probes = a.probes.get();
            h.module.uninstall();
            awaitIdle(h);
            assertEquals(probes, a.probes.get(), "the woken worker probes nothing");
            assertAgentWorker(h.pace.waitThreads.get(0));
        } finally {
            h.ctx.shutdown();
        }
    }

    @Test
    void withTheRealClockTheSecondOfTwoCyclesStartsAtLeastNineHundredMillisecondsAfterTheFirst() throws Exception {
        LateAdapter a = new LateAdapter();
        try (Harness h = new Harness(a).install().ready()) {
            h.module.reprobePace(WebModule.Pace.SYSTEM);
            a.clock = WebModule.Pace.SYSTEM;
            opened(h, 1);
            awaitIdle(h);
            opened(h, 2);
            awaitIdle(h);
            assertEquals(2, a.probedAt.size());
            long ms = ms(a.probedAt.get(1) - a.probedAt.get(0));
            assertTrue(ms >= 900, "paced: " + ms + " ms");
            assertTrue(ms < 10000, "and bounded: " + ms + " ms");
        }
    }

    private static void opened(Harness h, int i) {
        h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w" + i));
    }

    /** Waits (bounded) until no re-probe is pending and no worker is queued or running. */
    private static void awaitIdle(Harness h) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!h.module.reprobeIdle()) {
            if (System.nanoTime() - deadline > 0) {
                fail("the re-probe did not settle within 10 s");
            }
            Thread.yield();
        }
    }

    private static void assertAgentWorker(String thread) {
        assertTrue(thread.startsWith("skillroy-test-worker"), "the wait ran on an agent worker: " + thread);
        assertFalse(thread.startsWith("AWT-EventQueue"), thread);
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
