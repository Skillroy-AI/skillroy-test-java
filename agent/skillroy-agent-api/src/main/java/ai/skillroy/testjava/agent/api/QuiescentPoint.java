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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application thread's quiescent point: a bounded queue of agent tasks that the application's own thread runs, one
 * at a time, at its idle moments (phase8-contracts.md 4.1, gap 10, D519 to D523, D639). Backs
 * {@link ThreadPolicy#APP_QUIESCENT}. The base owns the queue, the admission, the exactly-once-or-never rule and the
 * statistics, so that every module gets the same semantics and the agent can see, bound and report the runner; a
 * module's {@code ThreadPolicyProvider} creates one point, returns it from
 * {@link ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider#quiescentPoint()}, calls {@link #runOne(long)} from
 * the application's idle callback and closes it in its {@code uninstall}.
 *
 * <p><b>Admitted by a budget, never pre-empted.</b> The application's thread is never interrupted by the agent, so
 * "bounded" means admission and measurement: a task runs only when its budget fits both the time the application says
 * is free and the time its caller has left; a task that runs longer than its budget is counted ({@code overruns}),
 * and the agent penalises a primitive whose task ran past twice its budget.
 *
 * <p><b>Exactly once or never.</b> A queued task is either run by {@link #runOne(long)} or withdrawn (its caller's time
 * ran out, too little of it was left at the point, or the point closed), decided by one atomic step; a withdrawn task
 * never runs later.
 *
 * <p>Threads: {@link #await} on agent threads only (refused at once on the application's thread, D639);
 * {@link #runOne(long)} on the application's thread at its idle point, never blocking (the point's monitor is held for
 * queue operations only, never while a task runs) and never throwing. Thread-safe. Depends on the protocol's
 * {@link RpcException} and {@link ErrorCodes} only.
 */
public final class QuiescentPoint implements AutoCloseable {

    /** The most one task may hold the application's thread, and the largest allowance. */
    public static final long MAX_ALLOWANCE_MS = 1000L;

    /** The largest queue. */
    public static final int MAX_CAPACITY = 16;

    /** The longest {@link #await} (the dispatcher's own cap on a call's time). */
    public static final long MAX_TIMEOUT_MS = 120000L;

    /** A spec's budget when it names none ({@code PrimitiveSpec.Builder.quiescentBudgetMs}). */
    public static final long DEFAULT_BUDGET_MS = 50L;

    private static final Object QUEUED = new Object();
    private static final Object RUNNING = new Object();

    private final long allowanceMs;
    private final int capacity;
    private final Object lock = new Object();
    private final ArrayDeque<Entry> queue = new ArrayDeque<Entry>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closed;
    /** The thread of the latest {@link #runOne} call (D639). */
    private volatile Thread appThread;
    /** The thread running a task of this point right now, or null. */
    private volatile Thread runner;

    // Statistics, guarded by lock.
    private long ran;
    private long notReady;
    private long refused;
    private long overruns;
    private long lastOverrunBudgetMs = -1L;
    private long lastOverrunRanMs;

    /**
     * @param allowanceMs the most one task may hold the application's thread, 1 to {@value #MAX_ALLOWANCE_MS}
     * @param capacity how many tasks may wait, 1 to {@value #MAX_CAPACITY}
     * @throws IllegalArgumentException outside those bounds
     */
    public QuiescentPoint(long allowanceMs, int capacity) {
        if (allowanceMs < 1 || allowanceMs > MAX_ALLOWANCE_MS) {
            throw new IllegalArgumentException("allowanceMs must be 1.." + MAX_ALLOWANCE_MS + ": " + allowanceMs);
        }
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be 1.." + MAX_CAPACITY + ": " + capacity);
        }
        this.allowanceMs = allowanceMs;
        this.capacity = capacity;
    }

    /**
     * Queues the task for the application's thread and waits for it at most {@code timeoutMs}. Agent threads only:
     * the agent calls it (a caller its broker forbids is refused {@code FORBIDDEN_THREAD} before it gets here); this
     * method guards the one route the broker cannot see, a call on the thread that runs {@link #runOne} or from inside
     * a task of this point, which it refuses at once with {@code RpcException(FORBIDDEN_THREAD, data {reason: "await on
     * the application's thread"})}, since the next idle point cannot come while the only application thread waits.
     *
     * @param budgetMs 1 to {@link #allowanceMs()}: the most the task may hold the application's thread
     * @param timeoutMs 1 to {@value #MAX_TIMEOUT_MS}
     * @return the task's value
     * @throws IllegalArgumentException for a null task or a bound outside its range
     * @throws RpcException {@code FORBIDDEN_THREAD} as above; {@code CAPABILITY_UNAVAILABLE} ("quiescent point
     *     closed") after {@link #close()}; {@code BUSY} ("quiescent queue full", {@code capacity}) at once when the
     *     queue is full; {@code NOT_READY} when the task was withdrawn unrun: no point admitted it within
     *     {@code timeoutMs} ("no quiescent point within &lt;t&gt; ms", {@code budgetMs}, {@code waitedMs}), too little
     *     of the call's time was left for its budget at the point ({@code budgetMs}, {@code remainingMs}), or the point
     *     closed; {@code TIMEOUT} when the task was admitted and still runs at the deadline (it keeps running)
     * @throws Exception what the task threw, as it threw it
     */
    public <T> T await(Callable<T> task, long budgetMs, long timeoutMs) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (budgetMs < 1 || budgetMs > allowanceMs) {
            throw new IllegalArgumentException("budgetMs must be 1.." + allowanceMs + ": " + budgetMs);
        }
        if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("timeoutMs must be 1.." + MAX_TIMEOUT_MS + ": " + timeoutMs);
        }
        Thread current = Thread.currentThread();
        if (current == appThread || current == runner) {
            synchronized (lock) {
                refused++;
            }
            throw error(ErrorCodes.FORBIDDEN_THREAD, "await on the application's thread", null);
        }
        long start = System.nanoTime();
        Entry entry = new Entry(task, budgetMs, start + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        synchronized (lock) {
            if (closed) {
                refused++;
                throw error(ErrorCodes.CAPABILITY_UNAVAILABLE, "quiescent point closed", null);
            }
            if (queue.size() >= capacity) {
                refused++;
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("capacity", Integer.valueOf(capacity));
                throw error(ErrorCodes.BUSY, "quiescent queue full", data);
            }
            queue.addLast(entry);
        }
        try {
            entry.done.await(Math.max(0L, entry.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            withdraw(entry, notReady("the waiting thread was interrupted", budgetMs, "waitedMs", elapsedMs(start)));
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        Object state = entry.state.get();
        if (!(state instanceof Outcome)) {
            long waitedMs = elapsedMs(start);
            RpcException late = notReady("no quiescent point within " + timeoutMs + " ms", budgetMs, "waitedMs",
                    waitedMs);
            if (withdraw(entry, late)) {
                throw late;
            }
            state = entry.state.get();
            if (state == RUNNING) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("budgetMs", Long.valueOf(budgetMs));
                data.put("waitedMs", Long.valueOf(waitedMs));
                throw error(ErrorCodes.TIMEOUT, "quiescent task still running after " + timeoutMs + " ms", data);
            }
        }
        return entry.<T>outcome();
    }

    /**
     * Runs the head of the queue on the calling thread if it fits: the application's thread, at its idle point (the
     * provider calls it from the application's idle callback). Runs at most one task per call, the FIFO head only,
     * and only when its budget is at most {@code availableMs} and its caller still has at least its budget of time
     * left; a head whose caller has less is withdrawn ({@code NOT_READY}, "too little of the call's time left for its
     * budget") and nothing else runs on this call (the next head waits for the next point). Never blocks, never
     * throws: what the task throws goes to its caller. Returns false at once when another {@code runOne} is running a
     * task, after {@link #close()}, and when the queue is empty.
     *
     * @param availableMs what the application says is free until its next scheduled work ({@code Long.MAX_VALUE} when
     *     unknown; a negative value counts as 0)
     * @return true when a task ran
     */
    public boolean runOne(long availableMs) {
        try {
            Thread current = Thread.currentThread();
            appThread = current;
            if (closed || !running.compareAndSet(false, true)) {
                return false;
            }
            try {
                Entry entry;
                RpcException withdrawn = null;
                synchronized (lock) {
                    entry = queue.peekFirst();
                    while (entry != null && entry.state.get() != QUEUED) {
                        queue.pollFirst();
                        entry = queue.peekFirst();
                    }
                    if (closed || entry == null || entry.budgetMs > Math.max(0L, availableMs)) {
                        return false;
                    }
                    long remainingMs = TimeUnit.NANOSECONDS.toMillis(entry.deadlineNanos - System.nanoTime());
                    if (remainingMs < entry.budgetMs) {
                        withdrawn = notReady("too little of the call's time left for its budget", entry.budgetMs,
                                "remainingMs", Math.max(0L, remainingMs));
                        if (entry.state.compareAndSet(QUEUED, new Outcome(null, withdrawn))) {
                            queue.pollFirst();
                            notReady++;
                        } else {
                            withdrawn = null;
                        }
                    } else if (entry.state.compareAndSet(QUEUED, RUNNING)) {
                        queue.pollFirst();
                    } else {
                        return false;
                    }
                }
                if (withdrawn != null) {
                    entry.done.countDown();
                    return false;
                }
                if (entry.state.get() != RUNNING) {
                    return false;
                }
                runner = current;
                long start = System.nanoTime();
                Object value = null;
                Throwable failure = null;
                try {
                    value = entry.task.call();
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    runner = null;
                }
                long ranMs = elapsedMs(start);
                synchronized (lock) {
                    ran++;
                    if (ranMs > entry.budgetMs) {
                        overruns++;
                        lastOverrunBudgetMs = entry.budgetMs;
                        lastOverrunRanMs = ranMs;
                    }
                }
                entry.state.set(new Outcome(value, failure));
                entry.done.countDown();
                return true;
            } finally {
                running.set(false);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Withdraws every queued task with {@code NOT_READY} ("quiescent point closed"); a later {@link #await} answers
     * {@code CAPABILITY_UNAVAILABLE} ("quiescent point closed") and {@link #runOne(long)} runs nothing. A task running
     * now finishes on its own (never interrupted). Idempotent. The provider calls it from its module's
     * {@code uninstall}, the agent on {@code agent.disable} and on close, before the module's {@code uninstall}
     * returns the thread to the application.
     */
    @Override
    public void close() {
        List<Entry> withdrawn = new ArrayList<Entry>();
        synchronized (lock) {
            closed = true;
            for (Entry entry : queue) {
                RpcException e = error(ErrorCodes.NOT_READY, "quiescent point closed", null);
                if (entry.state.compareAndSet(QUEUED, new Outcome(null, e))) {
                    notReady++;
                    withdrawn.add(entry);
                }
            }
            queue.clear();
        }
        for (Entry entry : withdrawn) {
            entry.done.countDown();
        }
    }

    /** The most one task may hold the application's thread, in ms. */
    public long allowanceMs() {
        return allowanceMs;
    }

    /** How many tasks may wait. */
    public int capacity() {
        return capacity;
    }

    /** True after {@link #close()}. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * {@code {allowanceMs, capacity, queued, ran, notReady, refused, overruns, lastOverrun: {budgetMs, ranMs} | null}},
     * a new map each call: {@code ran} counts the tasks that ran, {@code notReady} those withdrawn unrun,
     * {@code refused} the {@link #await} calls refused at once (a full queue, a closed point, the application's own
     * thread), {@code overruns} the tasks that ran longer than their budget.
     */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        synchronized (lock) {
            stats.put("allowanceMs", Long.valueOf(allowanceMs));
            stats.put("capacity", Integer.valueOf(capacity));
            stats.put("queued", Integer.valueOf(queue.size()));
            stats.put("ran", Long.valueOf(ran));
            stats.put("notReady", Long.valueOf(notReady));
            stats.put("refused", Long.valueOf(refused));
            stats.put("overruns", Long.valueOf(overruns));
            if (lastOverrunBudgetMs < 0) {
                stats.put("lastOverrun", null);
            } else {
                Map<String, Object> last = new LinkedHashMap<String, Object>();
                last.put("budgetMs", Long.valueOf(lastOverrunBudgetMs));
                last.put("ranMs", Long.valueOf(lastOverrunRanMs));
                stats.put("lastOverrun", last);
            }
        }
        return stats;
    }

    @Override
    public String toString() {
        return "QuiescentPoint" + stats();
    }

    /** Withdraws a queued entry with {@code why}; false when it already runs, ran or was withdrawn. */
    private boolean withdraw(Entry entry, RpcException why) {
        if (!entry.state.compareAndSet(QUEUED, new Outcome(null, why))) {
            return false;
        }
        synchronized (lock) {
            queue.remove(entry);
            notReady++;
        }
        entry.done.countDown();
        return true;
    }

    private static RpcException notReady(String reason, long budgetMs, String timeKey, long ms) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("budgetMs", Long.valueOf(budgetMs));
        data.put(timeKey, Long.valueOf(ms));
        return error(ErrorCodes.NOT_READY, reason, data);
    }

    /** An RpcException whose data starts with {@code reason}, then {@code more}. */
    private static RpcException error(int code, String reason, Map<String, Object> more) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", reason);
        if (more != null) {
            data.putAll(more);
        }
        return new RpcException(code, reason, data);
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** A task's end: its value, or what it threw, or why it was withdrawn. */
    private static final class Outcome {
        final Object value;
        final Throwable failure;

        Outcome(Object value, Throwable failure) {
            this.value = value;
            this.failure = failure;
        }
    }

    /** One queued task; {@code state} is QUEUED, RUNNING or its Outcome, moved by compare-and-set only from QUEUED. */
    private static final class Entry {
        final Callable<?> task;
        final long budgetMs;
        final long deadlineNanos;
        final AtomicReference<Object> state = new AtomicReference<Object>(QUEUED);
        final CountDownLatch done = new CountDownLatch(1);

        Entry(Callable<?> task, long budgetMs, long deadlineNanos) {
            this.task = task;
            this.budgetMs = budgetMs;
            this.deadlineNanos = deadlineNanos;
        }

        @SuppressWarnings("unchecked")
        <T> T outcome() throws Exception {
            Outcome outcome = (Outcome) state.get();
            Throwable failure = outcome.failure;
            if (failure == null) {
                return (T) outcome.value;
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw new IllegalStateException("the quiescent task threw " + failure.getClass().getName(), failure);
        }
    }
}
