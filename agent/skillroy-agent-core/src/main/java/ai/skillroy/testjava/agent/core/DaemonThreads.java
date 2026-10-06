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

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates the agent's threads: daemon, named {@code skillroy-<role>-N}, in the agent's own thread group, with the
 * <em>host's</em> class loader as context class loader (finding S34) and an uncaught-exception handler that logs to
 * the agent log instead of the host's {@code System.err}. Keeps every agent thread recognisable and unable to keep the
 * host JVM alive.
 *
 * <p>Why the host loader: agent threads run host code (SPI providers, probes, {@code onTargetReady}, primitives), and
 * that code, or a thread it starts, would otherwise see the isolated agent loader as its context loader, so caches
 * keyed on the context loader (logging contexts, JNDI, XML factories) would be created for, and would pin, the agent
 * loader after the agent stops. Nothing in the core needs the agent loader there: module loaders are passed
 * explicitly.
 *
 * <p>Every pool is bounded and has no queue (finding S30): a task that finds every thread busy is rejected at once
 * with {@link java.util.concurrent.RejectedExecutionException} instead of waiting or creating a thread; the caller
 * decides what that means (the control server evicts or refuses a connection, the thread broker answers
 * {@code BUSY}).
 *
 * <p>Thread-safe.
 */
final class DaemonThreads implements ThreadFactory {

    private final ThreadGroup group;
    private final String prefix;
    private final AgentLog log;
    private final ClassLoader contextLoader;
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * @param prefix full name prefix, e.g. {@code "skillroy-worker-"}
     * @param contextLoader the context class loader of every thread (the host's loader)
     */
    DaemonThreads(ThreadGroup group, String prefix, AgentLog log, ClassLoader contextLoader) {
        this.group = group;
        this.prefix = prefix;
        this.log = log;
        this.contextLoader = contextLoader;
    }

    @Override
    public Thread newThread(Runnable r) {
        return named(group, prefix + counter.incrementAndGet(), r, log, contextLoader);
    }

    /** A daemon thread with the agent's conventions (not started); {@code contextLoader} is the host's loader. */
    static Thread named(ThreadGroup group, String name, Runnable r, final AgentLog log, ClassLoader contextLoader) {
        Thread t = new Thread(group, r, name);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        t.setContextClassLoader(contextLoader);
        t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                try {
                    log.error("uncaught exception on " + thread.getName(), e);
                } catch (Throwable ignored) {
                    // Nothing may reach the host's System.err.
                }
            }
        });
        return t;
    }

    /**
     * A pool of at most {@code maxThreads} daemon threads without a queue: {@code execute} of a task that finds every
     * thread busy throws {@link java.util.concurrent.RejectedExecutionException} at once instead of waiting or
     * creating a thread. Idle threads end after 30 s.
     */
    static ThreadPoolExecutor boundedPool(ThreadGroup group, String prefix, int maxThreads, AgentLog log,
                                          ClassLoader contextLoader) {
        return new ThreadPoolExecutor(0, maxThreads, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                new DaemonThreads(group, prefix, log, contextLoader), new ThreadPoolExecutor.AbortPolicy());
    }
}
