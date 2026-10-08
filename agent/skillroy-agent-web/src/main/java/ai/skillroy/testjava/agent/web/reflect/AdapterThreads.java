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

package ai.skillroy.testjava.agent.web.reflect;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The few threads an adapter owns, so that nothing an adapter does after a technology's callback runs on the
 * technology's thread (with JCEF off-screen that thread is the EDT, BS8): a result handed over by the technology is
 * parsed, decoded and delivered here, and a blocking vendor call (JxBrowser's {@code Browser.bitmap()}) runs here so
 * that the caller can bound its wait. Created on first use, never at install (an inert agent starts no thread); daemon
 * threads named {@code <prefix>-<n>} with the host's class loader as context class loader; idle threads end after 30
 * seconds; {@link #shutdown()} ends them at once and a later use starts new ones; {@link #close()} (an adapter's
 * {@code detach()}) ends them at once for good: afterwards {@link #executor()} and {@link #schedule} refuse every
 * task with a {@code RejectedExecutionException} and a {@link #serial()} executor drops it (so a technology's callback
 * that arrives late is dropped by the caller's own handling of that refusal), and no thread is started again.
 *
 * <p>Thread-safe.
 */
public final class AdapterThreads {

    private static final int POOL_THREADS = 4;
    private static final long IDLE_SECONDS = 30;

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();
    private volatile ClassLoader contextLoader;
    private ThreadPoolExecutor pool;
    private ScheduledThreadPoolExecutor timer;
    private boolean closed;

    /** @param prefix the thread name prefix, {@code skillroy-web-<adapter>} */
    public AdapterThreads(String prefix) {
        this.prefix = prefix;
    }

    /** The context class loader of threads started from now on (the host's loader). */
    public void contextLoader(ClassLoader loader) {
        this.contextLoader = loader;
    }

    /** Runs short tasks; a task waits in a queue while every thread is busy. */
    public Executor executor() {
        return new Executor() {
            @Override
            public void execute(Runnable task) {
                pool().execute(task);
            }
        };
    }

    /** A new executor that runs its tasks one at a time, in submission order, on {@link #executor()}. */
    public Executor serial() {
        return new SerialExecutor(executor());
    }

    /**
     * Runs the task once after the delay on the timer thread; the task must be short (hand longer work to
     * {@link #executor()}).
     *
     * @throws RejectedExecutionException if the threads were shut down concurrently, or closed
     */
    public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        return timer().schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Ends every thread at once and for good (an adapter's {@code detach()}): queued tasks are dropped, and every later
     * task is refused with a {@code RejectedExecutionException}, so that no thread starts again.
     */
    public void close() {
        synchronized (this) {
            closed = true;
        }
        shutdown();
    }

    /** True once {@link #close()} was called. */
    public synchronized boolean isClosed() {
        return closed;
    }

    /** Ends every thread at once; queued tasks are dropped. A later use starts new threads (unless closed). */
    public void shutdown() {
        ThreadPoolExecutor p;
        ScheduledThreadPoolExecutor t;
        synchronized (this) {
            p = pool;
            t = timer;
            pool = null;
            timer = null;
        }
        if (p != null) {
            p.shutdownNow();
        }
        if (t != null) {
            t.shutdownNow();
        }
    }

    private synchronized ThreadPoolExecutor pool() {
        if (closed) {
            throw new RejectedExecutionException("the adapter was detached");
        }
        if (pool == null || pool.isShutdown()) {
            ThreadPoolExecutor p = new ThreadPoolExecutor(POOL_THREADS, POOL_THREADS, IDLE_SECONDS, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(), factory(""));
            p.allowCoreThreadTimeOut(true);
            pool = p;
        }
        return pool;
    }

    private synchronized ScheduledThreadPoolExecutor timer() {
        if (closed) {
            throw new RejectedExecutionException("the adapter was detached");
        }
        if (timer == null || timer.isShutdown()) {
            ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, factory("timer-"));
            t.setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS);
            t.allowCoreThreadTimeOut(true);
            t.setRemoveOnCancelPolicy(true);
            timer = t;
        }
        return timer;
    }

    private ThreadFactory factory(final String kind) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + kind + counter.incrementAndGet());
                t.setDaemon(true);
                ClassLoader cl = contextLoader;
                if (cl != null) {
                    t.setContextClassLoader(cl);
                }
                t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread thread, Throwable e) {
                        // Tasks report their own failures; nothing reaches the host's default handler.
                    }
                });
                return t;
            }
        };
    }

    /** Runs tasks one at a time in submission order (the {@code Executor} Javadoc's pattern). */
    private static final class SerialExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<Runnable>();
        private final Executor executor;
        private Runnable active;

        SerialExecutor(Executor executor) {
            this.executor = executor;
        }

        @Override
        public synchronized void execute(final Runnable r) {
            tasks.add(new Runnable() {
                @Override
                public void run() {
                    try {
                        r.run();
                    } finally {
                        scheduleNext();
                    }
                }
            });
            if (active == null) {
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            active = tasks.poll();
            if (active != null) {
                try {
                    executor.execute(active);
                } catch (RejectedExecutionException e) {
                    tasks.clear();
                    active = null;
                }
            }
        }
    }
}
