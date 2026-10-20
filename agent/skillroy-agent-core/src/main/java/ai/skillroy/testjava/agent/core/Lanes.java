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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.Lane;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The agent's named lanes (phase8-contracts.md 4.2, gap 9, D525): bounded groups of agent-owned daemon threads with a
 * bounded FIFO, declared by modules through {@link ai.skillroy.testjava.agent.api.ThreadBroker#declareLane}, reported
 * in {@code agent.info.threads.lanes} and retired with their module and on agent close.
 *
 * <p>Each lane is a pool of at most {@code threads} threads named {@code skillroy-lane-<name>-<n>} (the agent's
 * {@link DaemonThreads} conventions: daemon, the agent's group, the host's context class loader, finding S34), created
 * on demand and ended after 30 s idle, in front of an {@code ArrayBlockingQueue(queue)} (a {@code SynchronousQueue}
 * for 0) with the abort policy, so that a task that finds every thread busy and the queue full is refused at once.
 *
 * <p>A task is run exactly once or never: a task still queued when its caller's time runs out is withdrawn (it never
 * runs); a running one is interrupted (best effort) and counted {@code abandoned} for its lane until it ends; a lane
 * whose every thread is held by an abandoned task answers {@code BUSY "lane stuck"} at once. Closing a lane withdraws
 * its queued tasks ({@code CAPABILITY_UNAVAILABLE "lane closed"}), interrupts the running ones and frees the name.
 *
 * <p>Thread-safe: the registry is guarded by this object's monitor, each task by its own.
 */
final class Lanes {

    /** How long an idle lane thread lives. */
    static final long IDLE_SECONDS = 30L;
    private static final Pattern NAME = Pattern.compile(Lane.NAME_PATTERN);

    private final ThreadGroup group;
    private final ClassLoader contextLoader;
    private final AgentLog log;
    /** Live lanes by name; guarded by this. */
    private final Map<String, LaneImpl> lanes = new TreeMap<String, LaneImpl>();
    /** Names of retired lanes not declared again, newest last, at most {@link #MAX_RETIRED}; guarded by this. */
    private final LinkedHashSet<String> retired = new LinkedHashSet<String>();
    /** Set once the agent started closing; guarded by this. */
    private boolean closing;
    /** How many retired names are remembered (so that a call names "lane closed" rather than "unknown lane"). */
    static final int MAX_RETIRED = 64;

    /**
     * @param group the agent's thread group
     * @param contextLoader the context class loader of every lane thread (the host's loader, S34)
     */
    Lanes(ThreadGroup group, ClassLoader contextLoader, AgentLog log) {
        this.group = group;
        this.contextLoader = contextLoader;
        this.log = log;
    }

    /**
     * Declares a lane for {@code owner} (a module id, or "core").
     *
     * @throws IllegalArgumentException for a name, thread count or queue outside the bounds
     * @throws IllegalStateException for a ninth lane, a name already declared, or after the agent started closing
     */
    Lane declare(String owner, String name, int threads, int queue) {
        if (name == null || name.length() > Lane.MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a lane name must match " + Lane.NAME_PATTERN + " and have at most "
                    + Lane.MAX_NAME_LENGTH + " characters: " + name);
        }
        if (threads < 1 || threads > Lane.MAX_THREADS) {
            throw new IllegalArgumentException("a lane has 1.." + Lane.MAX_THREADS + " threads: " + threads);
        }
        if (queue < 0 || queue > Lane.MAX_QUEUE) {
            throw new IllegalArgumentException("a lane's queue holds 0.." + Lane.MAX_QUEUE + " tasks: " + queue);
        }
        LaneImpl lane;
        synchronized (this) {
            if (closing) {
                throw new IllegalStateException("the agent is closing; lane " + name + " cannot be declared");
            }
            if (lanes.containsKey(name)) {
                throw new IllegalStateException("lane " + name + " is already declared");
            }
            if (lanes.size() >= Lane.MAX_LANES) {
                throw new IllegalStateException("an agent holds at most " + Lane.MAX_LANES + " lanes; " + name
                        + " would be one more");
            }
            lane = new LaneImpl(owner == null ? "core" : owner, name, threads, queue);
            lanes.put(name, lane);
            retired.remove(name);
        }
        log.info("lane " + name + " declared by " + lane.owner + " (" + threads + " threads, queue " + queue + ")");
        return lane;
    }

    /**
     * Runs the task on the lane and waits at most {@code timeoutMs}.
     *
     * @throws RpcException CAPABILITY_UNAVAILABLE ("unknown lane", "lane closed"), BUSY ("lane full", "lane stuck")
     * @throws CoreThreadBroker.BrokerTimeout when the wait expires (a queued task withdrawn, a running one abandoned)
     */
    <T> T call(String name, CoreThreadBroker.Guarded<T> task, long timeoutMs) throws Exception {
        LaneImpl lane;
        boolean wasRetired;
        synchronized (this) {
            lane = name == null ? null : lanes.get(name);
            wasRetired = lane == null && name != null && retired.contains(name);
        }
        if (lane == null) {
            throw unavailable(wasRetired ? "lane closed" : "unknown lane", name);
        }
        return lane.run(task, timeoutMs);
    }

    /** Marks the agent as closing: later declarations are refused. */
    synchronized void startClosing() {
        closing = true;
    }

    /** Retires every lane (on agent close, after the modules' uninstall and before the pools shut down). */
    void closeAll() {
        List<LaneImpl> all;
        synchronized (this) {
            closing = true;
            all = new ArrayList<LaneImpl>(lanes.values());
        }
        for (LaneImpl lane : all) {
            lane.close();
        }
        for (LaneImpl lane : all) {
            try {
                if (!lane.pool.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                    log.warn("lane " + lane.name + " still has a running task after it was retired");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** {@code {<name>: {owner, threads, queue, active, queued, abandoned}}} for {@code agent.info.threads.lanes}. */
    Map<String, Object> toJson() {
        List<LaneImpl> all;
        synchronized (this) {
            all = new ArrayList<LaneImpl>(lanes.values());
        }
        Map<String, Object> out = new TreeMap<String, Object>();
        for (LaneImpl lane : all) {
            out.put(lane.name, lane.toJson());
        }
        return out;
    }

    private synchronized void forget(LaneImpl lane) {
        if (lanes.get(lane.name) == lane) {
            lanes.remove(lane.name);
            retired.remove(lane.name);
            retired.add(lane.name);
            if (retired.size() > MAX_RETIRED) {
                retired.remove(retired.iterator().next());
            }
        }
    }

    private static RpcException unavailable(String reason, String lane) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", reason);
        data.put("lane", lane);
        return new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, reason + ": " + lane, data);
    }

    /** One lane: its pool, its abandoned tasks and its handle. */
    private final class LaneImpl implements Lane {
        final String owner;
        final String name;
        final int threads;
        final int queue;
        final ThreadPoolExecutor pool;
        /** Tasks whose caller gave up while they ran and that still run; guarded by this lane. */
        private final List<LaneTask<?>> abandoned = new ArrayList<LaneTask<?>>();
        private volatile boolean closed;

        LaneImpl(String owner, String name, int threads, int queue) {
            this.owner = owner;
            this.name = name;
            this.threads = threads;
            this.queue = queue;
            BlockingQueue<Runnable> work = queue == 0 ? new SynchronousQueue<Runnable>()
                    : new ArrayBlockingQueue<Runnable>(queue);
            // Core size = the bound, timing out like the rest: threads are made on demand up to the bound before
            // anything queues, and end after 30 s idle.
            this.pool = new ThreadPoolExecutor(threads, threads, IDLE_SECONDS, TimeUnit.SECONDS, work,
                    new DaemonThreads(group, "skillroy-lane-" + name + "-", log, contextLoader),
                    new ThreadPoolExecutor.AbortPolicy());
            this.pool.allowCoreThreadTimeOut(true);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int threads() {
            return threads;
        }

        @Override
        public int queue() {
            return queue;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            forget(this);
            List<Runnable> queued = pool.shutdownNow();
            int withdrawn = 0;
            for (Runnable r : queued) {
                if (r instanceof LaneTask && ((LaneTask<?>) r).withdraw(unavailable("lane closed", name))) {
                    withdrawn++;
                }
            }
            log.info("lane " + name + " retired" + (withdrawn == 0 ? "" : "; " + withdrawn + " queued task(s) refused"));
        }

        <T> T run(CoreThreadBroker.Guarded<T> guarded, long timeoutMs) throws Exception {
            if (closed) {
                throw unavailable("lane closed", name);
            }
            long stuckForMs = stuckForMs();
            if (stuckForMs >= 0) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("reason", "lane stuck");
                data.put("lane", name);
                data.put("runningForMs", Long.valueOf(stuckForMs));
                throw new RpcException(CoreThreadBroker.BUSY, "every thread of lane " + name + " is held by a task"
                        + " whose caller gave up (" + stuckForMs + " ms); try again when it has ended", data);
            }
            LaneTask<T> task = new LaneTask<T>(this, guarded);
            try {
                pool.execute(task);
            } catch (RejectedExecutionException e) {
                if (closed) {
                    throw unavailable("lane closed", name);
                }
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("reason", "lane full");
                data.put("lane", name);
                data.put("threads", Long.valueOf(threads));
                data.put("queue", Long.valueOf(queue));
                throw new RpcException(CoreThreadBroker.BUSY, "every thread of lane " + name
                        + " is busy and its queue is full; try again later", data);
            }
            try {
                if (task.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return task.outcome();
                }
            } catch (InterruptedException e) {
                if (!task.withdraw(new CoreThreadBroker.BrokerTimeout(timeoutMs))) {
                    task.abandonIfRunning();
                } else {
                    pool.remove(task);
                }
                throw e;
            }
            if (task.withdraw(new CoreThreadBroker.BrokerTimeout(timeoutMs))) {
                pool.remove(task);
                throw new CoreThreadBroker.BrokerTimeout(timeoutMs);
            }
            if (!task.abandonIfRunning()) {
                return task.outcome();   // it ended in the meantime
            }
            throw new CoreThreadBroker.BrokerTimeout(timeoutMs);
        }

        /** How long the oldest abandoned task has run when every thread is held by one, else -1. */
        synchronized long stuckForMs() {
            if (abandoned.size() < threads) {
                return -1L;
            }
            long oldest = Long.MAX_VALUE;
            for (LaneTask<?> t : abandoned) {
                oldest = Math.min(oldest, t.startNanos);
            }
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oldest);
        }

        synchronized void abandoned(LaneTask<?> task) {
            abandoned.add(task);
        }

        void ended(LaneTask<?> task, long ranMs) {
            boolean was;
            synchronized (this) {
                was = abandoned.remove(task);
            }
            if (was) {
                log.info("the abandoned task on lane " + name + " ended after " + ranMs + " ms");
            }
        }

        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("owner", owner);
            out.put("threads", Long.valueOf(threads));
            out.put("queue", Long.valueOf(queue));
            out.put("active", Long.valueOf(pool.getActiveCount()));
            out.put("queued", Long.valueOf(pool.getQueue().size()));
            synchronized (this) {
                out.put("abandoned", Long.valueOf(abandoned.size()));
            }
            return out;
        }
    }

    /**
     * One task on a lane. Its state moves once from QUEUED to RUNNING (the lane thread took it) or to an outcome (it
     * was withdrawn), by compare-and-set, so it runs exactly once or never.
     */
    private static final class LaneTask<T> implements Runnable {
        private static final Object QUEUED = new Object();
        private static final Object RUNNING = new Object();

        private final LaneImpl lane;
        private final CoreThreadBroker.Guarded<T> guarded;
        private final AtomicReference<Object> state = new AtomicReference<Object>(QUEUED);
        final CountDownLatch done = new CountDownLatch(1);
        /** Guarded by this. */
        private Thread runner;
        private boolean ended;
        private boolean abandoned;
        volatile long startNanos;

        LaneTask(LaneImpl lane, CoreThreadBroker.Guarded<T> guarded) {
            this.lane = lane;
            this.guarded = guarded;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                return;   // withdrawn: never runs
            }
            startNanos = System.nanoTime();
            synchronized (this) {
                runner = Thread.currentThread();
            }
            Object value = null;
            Throwable failure = null;
            try {
                value = guarded.call();
            } catch (Throwable t) {
                failure = t;
            } finally {
                boolean wasAbandoned;
                synchronized (this) {
                    runner = null;
                    ended = true;
                    wasAbandoned = abandoned;
                    // An interrupt meant for this task must not reach the lane's next one.
                    Thread.interrupted();
                }
                if (wasAbandoned) {
                    lane.ended(this, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
                }
            }
            state.set(new Outcome(value, failure));
            done.countDown();
        }

        /** Withdraws a still-queued task with {@code why}; false when it already runs or ended. */
        boolean withdraw(Exception why) {
            if (!state.compareAndSet(QUEUED, new Outcome(null, why))) {
                return false;
            }
            done.countDown();
            return true;
        }

        /** Marks a running task abandoned and interrupts it (best effort); false when it has already ended. */
        boolean abandonIfRunning() {
            synchronized (this) {
                if (ended) {
                    return false;
                }
                abandoned = true;
                lane.abandoned(this);
                if (runner != null) {
                    runner.interrupt();
                }
                return true;
            }
        }

        T outcome() throws Exception {
            Object s = state.get();
            if (!(s instanceof Outcome)) {
                // Ended but the outcome is being written: wait (it is set right after the thread's bookkeeping).
                done.await();
                s = state.get();
            }
            Outcome outcome = (Outcome) s;
            if (outcome.failure == null) {
                @SuppressWarnings("unchecked")
                T value = (T) outcome.value;
                return value;
            }
            if (outcome.failure instanceof Exception) {
                throw (Exception) outcome.failure;
            }
            if (outcome.failure instanceof Error) {
                throw (Error) outcome.failure;
            }
            throw new IllegalStateException("a lane task threw " + outcome.failure.getClass().getName(),
                    outcome.failure);
        }
    }

    private static final class Outcome {
        final Object value;
        final Throwable failure;

        Outcome(Object value, Throwable failure) {
            this.value = value;
            this.failure = failure;
        }
    }
}
