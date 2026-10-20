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

package ai.skillroy.testjava.agent.api;

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link QuiescentPoint} (phase8-contracts.md 4.1, gap 10, D519 to D523, D639): FIFO, one task per {@code runOne},
 * admission by the budget against the free time and against the caller's remaining time, exactly once or never, the
 * queue bound, the application thread refused, close, statistics.
 */
class QuiescentPointTest {

    /** The application's thread: runOne is called here. */
    private final ExecutorService engine = Executors.newSingleThreadExecutor(named("engine"));
    /** Agent threads: await is called here. */
    private final ExecutorService agents = Executors.newCachedThreadPool(named("agent"));

    @AfterEach
    void stop() throws InterruptedException {
        engine.shutdownNow();
        agents.shutdownNow();
        assertTrue(engine.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(agents.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void boundsTheAllowanceTheCapacityAndTheArguments() {
        assertThrows(IllegalArgumentException.class, () -> new QuiescentPoint(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new QuiescentPoint(1001, 1));
        assertThrows(IllegalArgumentException.class, () -> new QuiescentPoint(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new QuiescentPoint(1, 17));
        QuiescentPoint point = new QuiescentPoint(1000, 16);
        assertEquals(1000L, point.allowanceMs());
        assertEquals(16, point.capacity());
        assertFalse(point.isClosed());
        QuiescentPoint small = new QuiescentPoint(100, 1);
        Callable<String> task = () -> "x";
        assertThrows(IllegalArgumentException.class, () -> small.await(null, 10, 100));
        assertThrows(IllegalArgumentException.class, () -> small.await(task, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> small.await(task, 101, 100), "above the allowance");
        assertThrows(IllegalArgumentException.class, () -> small.await(task, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> small.await(task, 10, 120001), "above 120,000");
        assertEquals(0, ((Integer) small.stats().get("queued")).intValue(), "nothing was queued");
    }

    @Test
    void runsTheHeadOnlyOneTaskPerCallInOrder() throws Exception {
        QuiescentPoint point = new QuiescentPoint(100, 4);
        final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        List<Future<Integer>> callers = new ArrayList<Future<Integer>>();
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            callers.add(submitAwait(point, () -> {
                order.add(Integer.valueOf(n));
                return Integer.valueOf(n);
            }, 10, 30000));
            awaitQueued(point, i);
        }
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertEquals(Collections.singletonList(Integer.valueOf(1)), order, "one task per call, the head first");
        assertEquals(2, ((Integer) point.stats().get("queued")).intValue());
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertFalse(runOne(point, Long.MAX_VALUE), "the queue is empty");
        for (int i = 0; i < 3; i++) {
            assertEquals(Integer.valueOf(i + 1), callers.get(i).get(10, TimeUnit.SECONDS));
        }
        assertEquals(java.util.Arrays.asList(1, 2, 3), order);
        assertEquals(3L, point.stats().get("ran"));
    }

    @Test
    void admitsOnlyWhenTheBudgetFitsTheFreeTime() throws Exception {
        QuiescentPoint point = new QuiescentPoint(100, 4);
        final AtomicInteger runs = new AtomicInteger();
        Future<Integer> caller = submitAwait(point, () -> Integer.valueOf(runs.incrementAndGet()), 20, 30000);
        awaitQueued(point, 1);
        assertFalse(runOne(point, 19), "19 ms free, a 20 ms budget");
        assertFalse(runOne(point, -5), "a negative value counts as 0");
        assertFalse(runOne(point, 0));
        assertEquals(0, runs.get());
        assertEquals(1, ((Integer) point.stats().get("queued")).intValue(), "it waits for the next point");
        assertTrue(runOne(point, 20));
        assertEquals(Integer.valueOf(1), caller.get(10, TimeUnit.SECONDS));
        Future<Integer> unknown = submitAwait(point, () -> Integer.valueOf(runs.incrementAndGet()), 20, 30000);
        awaitQueued(point, 1);
        assertTrue(runOne(point, Long.MAX_VALUE), "Long.MAX_VALUE: the application does not know");
        assertEquals(Integer.valueOf(2), unknown.get(10, TimeUnit.SECONDS));
    }

    @Test
    void withdrawsAHeadWhoseCallerHasLessTimeLeftThanItsBudget() throws Exception {
        QuiescentPoint point = new QuiescentPoint(1000, 4);
        final AtomicInteger runs = new AtomicInteger();
        Future<Integer> late = submitAwait(point, () -> Integer.valueOf(runs.incrementAndGet()), 600, 1000);
        awaitQueued(point, 1);
        Future<Integer> next = submitAwait(point, () -> Integer.valueOf(runs.incrementAndGet()), 1, 30000);
        awaitQueued(point, 2);
        Thread.sleep(500);
        assertFalse(runOne(point, Long.MAX_VALUE), "about 500 ms left of the call, a 600 ms budget");
        RpcException e = rpcFailure(late);
        assertEquals(ErrorCodes.NOT_READY, e.error().code());
        assertEquals("too little of the call's time left for its budget", e.error().data().get("reason"));
        assertEquals(600L, e.error().data().get("budgetMs"));
        long remaining = ((Long) e.error().data().get("remainingMs")).longValue();
        assertTrue(remaining >= 0 && remaining < 600, "remainingMs " + remaining);
        assertEquals(0, runs.get(), "withdrawn unrun, and nothing else ran on that call (FIFO kept)");
        assertFalse(next.isDone());
        assertTrue(runOne(point, Long.MAX_VALUE), "the next head runs at the next point");
        assertEquals(Integer.valueOf(1), next.get(10, TimeUnit.SECONDS));
        assertEquals(1L, point.stats().get("notReady"));
    }

    @Test
    void aTaskNoPointAdmitsInTimeIsWithdrawnAndNeverRunsLater() throws Exception {
        QuiescentPoint point = new QuiescentPoint(100, 4);
        final AtomicInteger runs = new AtomicInteger();
        long start = System.nanoTime();
        RpcException e = assertThrows(RpcException.class,
                () -> point.await(() -> Integer.valueOf(runs.incrementAndGet()), 10, 150));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(ErrorCodes.NOT_READY, e.error().code());
        assertEquals("no quiescent point within 150 ms", e.error().data().get("reason"));
        assertEquals(10L, e.error().data().get("budgetMs"));
        assertTrue(((Long) e.error().data().get("waitedMs")).longValue() >= 140, e.error().data().toString());
        assertTrue(tookMs >= 140 && tookMs < 10000, "took " + tookMs + " ms");
        assertFalse(runOne(point, Long.MAX_VALUE), "nothing is left to run");
        assertEquals(0, runs.get(), "never later");
        assertEquals(0, ((Integer) point.stats().get("queued")).intValue());
        assertEquals(1L, point.stats().get("notReady"));
    }

    /** Every task ran exactly once or its caller got NOT_READY, never both, never later. */
    @Test
    void theWithdrawOrRunRaceDecidesEachTaskOnce() throws Exception {
        final QuiescentPoint point = new QuiescentPoint(1000, 16);
        final int callers = 8;
        final int each = 300;
        final AtomicReferenceArray<Integer> runs = new AtomicReferenceArray<Integer>(callers * each);
        final AtomicReferenceArray<String> answers = new AtomicReferenceArray<String>(callers * each);
        for (int i = 0; i < callers * each; i++) {
            runs.set(i, Integer.valueOf(0));
        }
        final AtomicBoolean stop = new AtomicBoolean();
        Future<?> app = engine.submit(() -> {
            Random random = new Random(7);
            while (!stop.get()) {
                point.runOne(Long.MAX_VALUE);
                if (random.nextInt(4) == 0) {
                    LockSupport.parkNanos(random.nextInt(2000000));
                }
            }
        });
        List<Future<?>> all = new ArrayList<Future<?>>();
        for (int c = 0; c < callers; c++) {
            final int caller = c;
            all.add(agents.submit(() -> {
                Random random = new Random(caller);
                for (int k = 0; k < each; k++) {
                    final int id = caller * each + k;
                    try {
                        point.await(() -> {
                            Integer before;
                            do {
                                before = runs.get(id);
                            } while (!runs.compareAndSet(id, before, Integer.valueOf(before.intValue() + 1)));
                            return null;
                        }, 1, 1 + random.nextInt(3));
                        answers.set(id, "value");
                    } catch (RpcException e) {
                        answers.set(id, ErrorCodes.name(e.error().code()));
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : all) {
            f.get(120, TimeUnit.SECONDS);
        }
        stop.set(true);
        app.get(30, TimeUnit.SECONDS);
        for (int i = 0; i < 20; i++) {
            assertFalse(point.runOne(Long.MAX_VALUE), "nothing is left to run later");
        }
        int ran = 0;
        int notReady = 0;
        for (int i = 0; i < callers * each; i++) {
            String answer = answers.get(i);
            int count = runs.get(i).intValue();
            assertNotNull(answer, "task " + i + " was answered");
            if ("NOT_READY".equals(answer)) {
                assertEquals(0, count, "task " + i + ": NOT_READY and ran");
                notReady++;
            } else {
                assertEquals(1, count, "task " + i + " (" + answer + ") ran " + count + " times");
                assertTrue("value".equals(answer) || "TIMEOUT".equals(answer), answer);
                ran++;
            }
        }
        assertTrue(ran > 0 && notReady > 0, "both outcomes were exercised: ran " + ran + ", NOT_READY " + notReady);
        Map<String, Object> stats = point.stats();
        assertEquals(Long.valueOf(ran), stats.get("ran"));
        assertEquals(Long.valueOf(notReady), stats.get("notReady"));
        assertEquals(0, ((Integer) stats.get("queued")).intValue());
    }

    @Test
    void aFullQueueIsRefusedBusyAtOnce() throws Exception {
        QuiescentPoint point = new QuiescentPoint(100, 2);
        Future<String> first = submitAwait(point, () -> "a", 10, 30000);
        awaitQueued(point, 1);
        Future<String> second = submitAwait(point, () -> "b", 10, 30000);
        awaitQueued(point, 2);
        long start = System.nanoTime();
        RpcException e = assertThrows(RpcException.class, () -> point.await(() -> "c", 10, 30000));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 10000, "at once");
        assertEquals(ErrorCodes.BUSY, e.error().code());
        assertEquals("quiescent queue full", e.error().data().get("reason"));
        assertEquals(2L, e.error().data().get("capacity"), "the wire's numbers are longs");
        assertEquals(1L, point.stats().get("refused"));
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertEquals("a", first.get(10, TimeUnit.SECONDS));
        assertEquals("b", second.get(10, TimeUnit.SECONDS));
    }

    @Test
    void aTasksExceptionReachesItsCallerAndRunOneNeverThrows() throws Exception {
        QuiescentPoint point = new QuiescentPoint(100, 4);
        final IOException checked = new IOException("checked");
        Future<Object> caller = submitAwait(point, () -> {
            throw checked;
        }, 10, 30000);
        awaitQueued(point, 1);
        assertTrue(runOne(point, Long.MAX_VALUE), "it ran; the failure is the caller's");
        try {
            caller.get(10, TimeUnit.SECONDS);
            fail("the task threw");
        } catch (java.util.concurrent.ExecutionException e) {
            assertSame(checked, e.getCause());
        }
        final AssertionError error = new AssertionError("an Error");
        Future<Object> errorCaller = submitAwait(point, () -> {
            throw error;
        }, 10, 30000);
        awaitQueued(point, 1);
        assertTrue(runOne(point, Long.MAX_VALUE));
        try {
            errorCaller.get(10, TimeUnit.SECONDS);
            fail("the task threw");
        } catch (java.util.concurrent.ExecutionException e) {
            assertSame(error, e.getCause());
        }
    }

    @Test
    void runOneNeverBlocksWhileAnotherRuns() throws Exception {
        QuiescentPoint point = new QuiescentPoint(1000, 4);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        Future<String> slow = submitAwait(point, () -> {
            started.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return "slow";
        }, 10, 30000);
        awaitQueued(point, 1);
        Future<Boolean> running = engine.submit(() -> Boolean.valueOf(point.runOne(Long.MAX_VALUE)));
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Future<String> other = submitAwait(point, () -> "other", 10, 30000);
        awaitQueued(point, 1);
        long start = System.nanoTime();
        assertFalse(point.runOne(Long.MAX_VALUE), "another runOne is running a task: false at once");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000);
        release.countDown();
        assertTrue(running.get(10, TimeUnit.SECONDS).booleanValue());
        assertEquals("slow", slow.get(10, TimeUnit.SECONDS));
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertEquals("other", other.get(10, TimeUnit.SECONDS));
    }

    /** D639: on the application's thread, or from inside a task of the point, await is refused at once. */
    @Test
    void awaitOnTheApplicationsThreadIsRefusedAtOnce() throws Exception {
        final QuiescentPoint point = new QuiescentPoint(1000, 4);
        assertFalse(runOne(point, Long.MAX_VALUE), "the engine thread is now the point's application thread");
        Future<RpcException> onEngine = engine.submit(() -> {
            long start = System.nanoTime();
            try {
                point.await(() -> "x", 10, 60000);
                return null;
            } catch (RpcException e) {
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000, "at once");
                return e;
            }
        });
        RpcException refused = onEngine.get(10, TimeUnit.SECONDS);
        assertNotNull(refused, "refused");
        assertEquals(ErrorCodes.FORBIDDEN_THREAD, refused.error().code());
        assertEquals("await on the application's thread", refused.error().data().get("reason"));

        final AtomicReference<RpcException> nested = new AtomicReference<RpcException>();
        final AtomicReference<String> nestedThread = new AtomicReference<String>();
        Future<String> caller = agents.submit(() -> point.await(() -> {
            nestedThread.set(Thread.currentThread().getName());
            try {
                point.await(() -> "inner", 10, 60000);
            } catch (RpcException e) {
                nested.set(e);
            }
            return "outer";
        }, 100, 30000));
        awaitQueued(point, 1);
        long start = System.nanoTime();
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000, "the engine thread was not held");
        assertEquals("outer", caller.get(10, TimeUnit.SECONDS));
        assertTrue(nestedThread.get().startsWith("engine"), nestedThread.get());
        assertNotNull(nested.get());
        assertEquals(ErrorCodes.FORBIDDEN_THREAD, nested.get().error().code());
        assertEquals(0, ((Integer) point.stats().get("queued")).intValue(), "the inner task was never queued");
        assertEquals(2L, point.stats().get("refused"));
    }

    @Test
    void anAdmittedTaskStillRunningAtTheDeadlineAnswersTimeoutAndKeepsRunning() throws Exception {
        QuiescentPoint point = new QuiescentPoint(1000, 4);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        Future<String> caller = submitAwait(point, () -> {
            assertTrue(release.await(30, TimeUnit.SECONDS));
            finished.set(true);
            return "late";
        }, 10, 1000);
        awaitQueued(point, 1);
        Future<Boolean> running = engine.submit(() -> Boolean.valueOf(point.runOne(Long.MAX_VALUE)));
        RpcException e = rpcFailure(caller);
        assertEquals(ErrorCodes.TIMEOUT, e.error().code());
        assertEquals(10L, e.error().data().get("budgetMs"));
        assertFalse(finished.get(), "still running when its caller got TIMEOUT");
        release.countDown();
        assertTrue(running.get(10, TimeUnit.SECONDS).booleanValue());
        assertTrue(finished.get(), "never interrupted: it finished on its own");
        assertEquals(1L, point.stats().get("ran"));
        assertEquals(1L, point.stats().get("overruns"), "it held the thread far longer than its 10 ms");
    }

    @Test
    void closeWithdrawsTheQueueAndRefusesLaterCalls() throws Exception {
        QuiescentPoint point = new QuiescentPoint(100, 4);
        final AtomicInteger runs = new AtomicInteger();
        Future<Integer> a = submitAwait(point, () -> Integer.valueOf(runs.incrementAndGet()), 10, 30000);
        awaitQueued(point, 1);
        Future<Integer> b = submitAwait(point, () -> Integer.valueOf(runs.incrementAndGet()), 10, 30000);
        awaitQueued(point, 2);
        point.close();
        assertTrue(point.isClosed());
        for (Future<Integer> f : java.util.Arrays.asList(a, b)) {
            RpcException e = rpcFailure(f);
            assertEquals(ErrorCodes.NOT_READY, e.error().code());
            assertEquals("quiescent point closed", e.error().data().get("reason"));
        }
        RpcException later = assertThrows(RpcException.class, () -> point.await(() -> 1, 10, 1000));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, later.error().code());
        assertEquals("quiescent point closed", later.error().data().get("reason"));
        assertFalse(runOne(point, Long.MAX_VALUE));
        point.close();
        assertEquals(0, runs.get());
        Map<String, Object> stats = point.stats();
        assertEquals(2L, stats.get("notReady"));
        assertEquals(1L, stats.get("refused"));
        assertEquals(0, ((Integer) stats.get("queued")).intValue());
    }

    @Test
    void statsCountAndRecordTheLastOverrun() throws Exception {
        QuiescentPoint point = new QuiescentPoint(500, 8);
        Map<String, Object> fresh = point.stats();
        assertEquals(java.util.Arrays.asList("allowanceMs", "capacity", "queued", "ran", "notReady", "refused",
                "overruns", "lastOverrun"), new ArrayList<String>(fresh.keySet()));
        assertEquals(500L, fresh.get("allowanceMs"));
        assertEquals(8, fresh.get("capacity"));
        assertNull(fresh.get("lastOverrun"));
        Future<String> quick = submitAwait(point, () -> "quick", 100, 30000);
        awaitQueued(point, 1);
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertEquals("quick", quick.get(10, TimeUnit.SECONDS));
        assertEquals(0L, point.stats().get("overruns"));
        Future<String> slow = submitAwait(point, () -> {
            Thread.sleep(60);
            return "slow";
        }, 5, 30000);
        awaitQueued(point, 1);
        assertTrue(runOne(point, Long.MAX_VALUE));
        assertEquals("slow", slow.get(10, TimeUnit.SECONDS));
        Map<String, Object> stats = point.stats();
        assertEquals(2L, stats.get("ran"));
        assertEquals(1L, stats.get("overruns"));
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) stats.get("lastOverrun");
        assertEquals(5L, last.get("budgetMs"));
        assertTrue(((Long) last.get("ranMs")).longValue() >= 50, last.toString());
        stats.put("ran", 99L);
        assertEquals(2L, point.stats().get("ran"), "a new map each call");
    }

    // ------------------------------------------------------------------------------------------------------------

    private <T> Future<T> submitAwait(final QuiescentPoint point, final Callable<T> task, final long budgetMs,
                                      final long timeoutMs) {
        return agents.submit(() -> point.await(task, budgetMs, timeoutMs));
    }

    /** runOne on the engine thread, the point's application thread. */
    private boolean runOne(final QuiescentPoint point, final long availableMs) throws Exception {
        return engine.submit(() -> Boolean.valueOf(point.runOne(availableMs))).get(30, TimeUnit.SECONDS)
                .booleanValue();
    }

    private static void awaitQueued(QuiescentPoint point, int queued) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (((Integer) point.stats().get("queued")).intValue() != queued) {
            if (System.nanoTime() > deadline) {
                fail("the queue never held " + queued + ": " + point.stats());
            }
            Thread.sleep(1);
        }
    }

    private static RpcException rpcFailure(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(e.getCause() instanceof RpcException, String.valueOf(e.getCause()));
            return (RpcException) e.getCause();
        }
        fail("expected an RpcException");
        return null;
    }

    private static ThreadFactory named(final String prefix) {
        final AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
