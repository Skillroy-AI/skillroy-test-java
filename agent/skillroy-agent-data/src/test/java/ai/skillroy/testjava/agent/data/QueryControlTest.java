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

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.QueryControl;
import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ai.skillroy.testjava.agent.data.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code data.query}'s hand-off, its own timeout, the cancel actions and the per-source gate (phase8-contracts.md
 * 4.2, 4.3, 13.1 row 9; D527, D529, D638), over providers that block until the test or a cancel action releases them.
 */
class QueryControlTest {

    private static final List<List<Object>> ONE_ROW = Collections.singletonList(Arrays.<Object>asList("x"));

    private FakeAgentContext ctx;
    private DataModule module;
    private final List<CountDownLatch> releases = new ArrayList<CountDownLatch>();

    @BeforeEach
    void install() throws Exception {
        ctx = new FakeAgentContext();
        module = new DataModule();
        module.install(ctx);
        ctx.spi.register(DataSourceProvider.class, new ProviderQueryFormTest.FixedProvider("other",
                new QueryResult(Arrays.asList("V"), null, ONE_ROW, false)));
    }

    @AfterEach
    void release() {
        for (CountDownLatch latch : releases) {
            latch.countDown();
        }
    }

    private CountDownLatch latch() {
        CountDownLatch latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private RpcException fails(String source) throws Exception {
        return ctx.fails("data.query", args("source", source, "sql", "SELECT v FROM t"));
    }

    private Object query(String source) throws Exception {
        return ctx.call("data.query", args("source", source, "sql", "SELECT v FROM t"));
    }

    @Test
    void theReadRunsOnANestedWorkerWithTheBudgetAndALiveControl() throws Exception {
        ctx.remainingMs = 5000;
        BlockingProvider provider = new BlockingProvider("p", null);
        provider.release.countDown();
        ctx.spi.register(DataSourceProvider.class, provider);
        assertEquals("[[\"x\"]]", Json.write(((Map<?, ?>) query("p")).get("rows")));
        assertEquals(5000L - DataQuery.RESERVE_MS, provider.timeoutMs.get());
        assertTrue(provider.remainingAtStart.get() <= 5000L - DataQuery.RESERVE_MS
                && provider.remainingAtStart.get() > 4000L, String.valueOf(provider.remainingAtStart.get()));
        assertFalse(provider.cancelledAtStart.get());
        assertTrue(provider.thread.get().startsWith("skillroy-worker-test-"), provider.thread.get());
        assertNotEquals(Thread.currentThread().getName(), provider.thread.get());
    }

    @Test
    void aCancelActionRunsOnceAtExpiryOnANestedWorkerTaskOfItsOwn() throws Exception {
        ctx.remainingMs = 700;
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<String> actionThread = new AtomicReference<String>();
        final BlockingProvider provider = new BlockingProvider("cancellable", null);
        provider.action = new Runnable() {
            @Override
            public void run() {
                runs.incrementAndGet();
                actionThread.set(Thread.currentThread().getName());
                provider.release.countDown();
            }
        };
        ctx.spi.register(DataSourceProvider.class, provider);
        long start = System.nanoTime();
        RpcException e = fails("cancellable");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(ErrorCodes.TIMEOUT, e.error().code());
        Map<String, Object> data = e.error().data();
        assertEquals(Arrays.asList("primitive", "timeoutMs", "source", "lane", "waitedMs", "cancelled",
                "stillRunning", "enforcesTimeout"), new ArrayList<String>(data.keySet()));
        assertEquals("data.query", data.get("primitive"));
        assertEquals(700L, data.get("timeoutMs"));
        assertEquals("cancellable", data.get("source"));
        assertNull(data.get("lane"));
        assertEquals(Boolean.TRUE, data.get("cancelled"));
        assertEquals(Boolean.FALSE, data.get("stillRunning"));
        assertEquals(Boolean.FALSE, data.get("enforcesTimeout"));
        assertTrue(((Number) data.get("waitedMs")).longValue() >= 700L - DataQuery.RESERVE_MS, data.toString());
        assertTrue(tookMs < 700L - DataQuery.ANSWER_MARGIN_MS + 30, "inside the reserve: " + tookMs);
        assertEquals(1, runs.get());
        assertTrue(actionThread.get().startsWith("skillroy-worker-test-"), actionThread.get());
        assertNotEquals(provider.thread.get(), actionThread.get(), "not the read's thread");
        assertNotEquals(Thread.currentThread().getName(), actionThread.get(), "not the dispatching worker");
        assertTrue(provider.cancelledAfterRelease.get(), "the read saw cancelled()");
        // The source answers again at once: the read ended inside the reserve.
        provider.action = null;
        provider.release = latch();
        provider.release.countDown();
        assertEquals("[[\"x\"]]", Json.write(((Map<?, ?>) query("cancellable")).get("rows")));
    }

    @Test
    void anActionRegisteredAfterCancellationRunsAtOnceOnTheRegisteringThread() throws Exception {
        ctx.remainingMs = 600;
        final AtomicReference<String> ranOn = new AtomicReference<String>();
        final AtomicReference<String> readOn = new AtomicReference<String>();
        ctx.spi.register(DataSourceProvider.class, new Provider("late") {
            @Override
            public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs,
                                     QueryControl control) throws Exception {
                readOn.set(Thread.currentThread().getName());
                awaitIgnoringInterrupts(control);
                control.onCancel(() -> ranOn.set(Thread.currentThread().getName()));
                return new QueryResult(null, null, ONE_ROW, false);
            }
        });
        RpcException e = fails("late");
        assertEquals(Boolean.TRUE, e.error().data().get("cancelled"));
        assertEquals(Boolean.FALSE, e.error().data().get("stillRunning"));
        assertEquals(readOn.get(), ranOn.get());
    }

    @Test
    void aThrowingActionIsLoggedByItsClassNameOnly() throws Exception {
        ctx.remainingMs = 600;
        BlockingProvider provider = new BlockingProvider("throws", null);
        provider.action = new ThrowingAction();
        ctx.spi.register(DataSourceProvider.class, provider);
        RpcException e = fails("throws");
        assertEquals(Boolean.FALSE, e.error().data().get("cancelled"), "no action returned without throwing");
        assertEquals(Boolean.TRUE, e.error().data().get("stillRunning"));
        assertTrue(ctx.log.all().contains("data: the cancel action " + ThrowingAction.class.getName()
                + " failed: java.lang.IllegalStateException"), ctx.log.all());
        assertFalse(ctx.log.all().contains("planted value"), "never the exception's text");
        provider.release.countDown();
    }

    @Test
    void aBlockingActionIsAbandonedOnItsOwnTaskWhileTheAnswerArrivesInsideTheReserve() throws Exception {
        ctx.remainingMs = 700;
        final CountDownLatch actionRelease = latch();
        final BlockingProvider provider = new BlockingProvider("slowCancel", null);
        provider.action = new BlockingAction(actionRelease);
        ctx.spi.register(DataSourceProvider.class, provider);
        long start = System.nanoTime();
        RpcException e = fails("slowCancel");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < 700L - DataQuery.ANSWER_MARGIN_MS + 30, "inside the reserve: " + tookMs);
        Map<String, Object> data = e.error().data();
        assertEquals(Boolean.TRUE, data.get("cancelPending"), data.toString());
        assertEquals(Boolean.FALSE, data.get("cancelled"));
        assertEquals(Boolean.TRUE, data.get("stillRunning"));
        // Another source answers meanwhile; this one is gated until its read ends.
        assertEquals("[[\"x\"]]", Json.write(((Map<?, ?>) query("other")).get("rows")));
        RpcException busy = fails("slowCancel");
        assertEquals(ErrorCodes.BUSY, busy.error().code());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!ctx.log.all().contains("still ran after 2000 ms; abandoned") && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(ctx.log.all().contains("data: the cancel action " + BlockingAction.class.getName()
                + " still ran after 2000 ms; abandoned"), ctx.log.all());
        actionRelease.countDown();
        provider.release.countDown();
    }

    @Test
    void aFifthActionIsRefused() throws Exception {
        final AtomicReference<Throwable> fifth = new AtomicReference<Throwable>();
        ctx.spi.register(DataSourceProvider.class, new Provider("five") {
            @Override
            public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs,
                                     QueryControl control) throws Exception {
                for (int i = 0; i < QueryControl.MAX_ACTIONS; i++) {
                    control.onCancel(() -> { }).close();
                }
                fifth.set(assertThrows(IllegalStateException.class, () -> control.onCancel(() -> { })));
                return new QueryResult(null, null, ONE_ROW, false);
            }
        });
        query("five");
        assertTrue(fifth.get() instanceof IllegalStateException);
    }

    @Test
    void aStuckReadGatesItsOwnSourceOnlyUntilItEnds() throws Exception {
        ctx.remainingMs = 600;
        BlockingProvider provider = new BlockingProvider("stuck", null);
        ctx.spi.register(DataSourceProvider.class, provider);
        RpcException e = fails("stuck");
        assertEquals(Boolean.FALSE, e.error().data().get("cancelled"), "nothing registered");
        assertEquals(Boolean.TRUE, e.error().data().get("stillRunning"));
        assertFalse(e.error().data().containsKey("cancelPending"));
        assertTrue(provider.interrupted.get() > 0, "the read's worker was interrupted, best effort");
        RpcException busy = fails("stuck");
        assertEquals(ErrorCodes.BUSY, busy.error().code());
        assertEquals("an earlier read of the source still runs", busy.error().data().get("reason"));
        assertEquals("stuck", busy.error().data().get("source"));
        assertTrue(((Number) busy.error().data().get("runningForMs")).longValue() >= 0);
        assertEquals(1, provider.calls.get(), "the gate refuses before the provider is called");
        assertEquals("[[\"x\"]]", Json.write(((Map<?, ?>) query("other")).get("rows")), "B answers");
        provider.release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!ctx.log.all().contains("the read of source 'stuck' ended after") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(ctx.log.all().contains("data: the read of source 'stuck' ended after"), ctx.log.all());
        assertEquals("[[\"x\"]]", Json.write(((Map<?, ?>) query("stuck")).get("rows")), "the gate is clear");
    }

    @Test
    void aSourceWithALaneRunsThereAndSaysSoInDataSources() throws Exception {
        ctx.broker.declareLane("test.reads", 1, 2);
        BlockingProvider provider = new BlockingProvider("laned", "test.reads");
        provider.enforces = true;
        provider.release.countDown();
        ctx.spi.register(DataSourceProvider.class, provider);
        query("laned");
        assertEquals("skillroy-lane-test.reads-1", provider.thread.get());
        assertEquals("{\"sources\":[{\"id\":\"other\",\"describe\":\"fixed rows\",\"lane\":null,"
                + "\"enforcesTimeout\":false},{\"id\":\"laned\",\"describe\":\"blocks\",\"lane\":\"test.reads\","
                + "\"enforcesTimeout\":true}]}", Json.write(ctx.call("data.sources",
                Collections.<String, Object>emptyMap())));
        // A stuck read on a lane leaves no per-source gate: the lane's own bound applies.
        ctx.remainingMs = 500;
        provider.release = latch();
        RpcException e = fails("laned");
        assertEquals("test.reads", e.error().data().get("lane"));
        assertEquals(Boolean.TRUE, e.error().data().get("enforcesTimeout"));
        RpcException next = fails("laned");
        assertEquals(ErrorCodes.TIMEOUT, next.error().code(), "queued behind the stuck read, not BUSY");
        assertEquals(Boolean.FALSE, next.error().data().get("stillRunning"), "a queued read never started");
        provider.release.countDown();
    }

    @Test
    void aThrowingLaneOrTimeoutAnswerCountsAsTheDefault() throws Exception {
        BlockingProvider provider = new BlockingProvider("odd", null) {
            @Override
            public String lane() {
                throw new IllegalStateException("no lane today");
            }

            @Override
            public boolean enforcesTimeout() {
                throw new IllegalStateException("no answer today");
            }
        };
        provider.release.countDown();
        ctx.spi.register(DataSourceProvider.class, provider);
        query("odd");
        assertTrue(provider.thread.get().startsWith("skillroy-worker-test-"));
        assertTrue(ctx.log.all().contains("failed in lane(); its reads run on a worker: "
                + "java.lang.IllegalStateException"), ctx.log.all());
        assertTrue(ctx.log.all().contains("failed in enforcesTimeout(): java.lang.IllegalStateException"));
    }

    @Test
    void uninstallCancelsTheReadsStillRunning() throws Exception {
        ctx.remainingMs = 30000;
        final BlockingProvider provider = new BlockingProvider("long", null);
        final AtomicInteger runs = new AtomicInteger();
        provider.action = new Runnable() {
            @Override
            public void run() {
                runs.incrementAndGet();
                provider.release.countDown();
            }
        };
        ctx.spi.register(DataSourceProvider.class, provider);
        final AtomicReference<Object> outcome = new AtomicReference<Object>();
        Thread caller = new Thread(() -> {
            try {
                outcome.set(query("long"));
            } catch (Exception e) {
                outcome.set(e);
            }
        }, "skillroy-test-data-caller");
        caller.setDaemon(true);
        caller.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (provider.calls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        long start = System.nanoTime();
        module.uninstall();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < DataModule.UNINSTALL_CANCEL_MS + 500);
        assertEquals(1, runs.get(), "the read's action ran on uninstall");
        caller.join(10000);
        assertTrue(provider.cancelledAfterRelease.get());
        assertTrue(outcome.get() instanceof Map, String.valueOf(outcome.get()));
    }

    /** Waits until the control is cancelled, ignoring interrupts, at most 10 s. */
    static void awaitIgnoringInterrupts(QueryControl control) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!control.cancelled() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {
                // like host code that does not stop on an interrupt
            }
        }
    }

    /** A provider of the five-argument form only. */
    abstract static class Provider implements DataSourceProvider {
        private final String id;

        Provider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return "blocks";
        }

        @Override
        public abstract QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs,
                                          QueryControl control) throws Exception;
    }

    /**
     * Registers {@link #action} (if set), then blocks until {@link #release} opens, ignoring interrupts (like host
     * code on a monitor), counting the interrupts it saw; returns one row.
     */
    static class BlockingProvider extends Provider {
        private final String lane;
        volatile Runnable action;
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile boolean enforces;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger interrupted = new AtomicInteger();
        final AtomicReference<String> thread = new AtomicReference<String>();
        final AtomicReference<Long> timeoutMs = new AtomicReference<Long>();
        final AtomicReference<Long> remainingAtStart = new AtomicReference<Long>();
        final AtomicReference<Boolean> cancelledAtStart = new AtomicReference<Boolean>();
        final AtomicReference<Boolean> cancelledAfterRelease = new AtomicReference<Boolean>(Boolean.FALSE);

        BlockingProvider(String id, String lane) {
            super(id);
            this.lane = lane;
        }

        @Override
        public String lane() {
            return lane;
        }

        @Override
        public boolean enforcesTimeout() {
            return enforces;
        }

        @Override
        public QueryResult query(String sql, List<Object> params, int maxRows, long budget, QueryControl control)
                throws Exception {
            calls.incrementAndGet();
            thread.set(Thread.currentThread().getName());
            timeoutMs.set(Long.valueOf(budget));
            remainingAtStart.set(Long.valueOf(control.remainingMs()));
            cancelledAtStart.set(Boolean.valueOf(control.cancelled()));
            Runnable a = action;
            AutoCloseable registration = a == null ? null : control.onCancel(a);
            CountDownLatch gate = release;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                try {
                    if (gate.await(50, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                }
            }
            cancelledAfterRelease.set(Boolean.valueOf(control.cancelled()));
            if (registration != null) {
                registration.close();
            }
            return new QueryResult(null, null, ONE_ROW, false);
        }
    }

    /** A cancel action that throws with a planted value in its message. */
    static final class ThrowingAction implements Runnable {
        @Override
        public void run() {
            throw new IllegalStateException("planted value 4711");
        }
    }

    /** A cancel action that blocks until released, ignoring interrupts. */
    static final class BlockingAction implements Runnable {
        private final CountDownLatch release;

        BlockingAction(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void run() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                try {
                    if (release.await(50, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    // like host code on a monitor
                }
            }
        }
    }
}
