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
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@link ThreadBroker}. Every wait is bounded; the EDT is never blocked by the agent (EDT_SYNC posts with
 * {@code invokeLater} and waits on a future; a task the EDT has not started when the wait expires is cancelled and
 * never runs) and never interrupted; WORKER tasks are interrupted on timeout (best effort); APP uses the first
 * registered {@link ThreadPolicyProvider} and falls back to WORKER, also while that provider reports
 * {@link ThreadPolicyProvider#available()} false (contract amendment A19; the dispatcher refuses mutating APP
 * primitives in that case before they get here). A task never runs on a thread a provider forbids, and a forbidden
 * thread may not wait in {@link #call}. {@code call(CALLER, …)} runs the task inline on
 * the calling thread, where no timeout can be enforced; the dispatcher runs CALLER primitives on a control-channel
 * thread of their own so that their timeout is enforced.
 *
 * <p>AWT is touched only by the EDT policies, and only when a task is actually run or posted under one of them
 * (through {@link Awt}); the other policies never load an AWT class, so a target that does not use AWT never has it
 * initialised by the agent.
 *
 * <p>The worker and caller pools are bounded (finding S30): a task that finds every thread busy is not queued but
 * answered at once with {@link #BUSY}.
 *
 * <p>The caller's {@link CallScope} travels with the task, so events published on the executing thread carry the
 * call's correlation id, and the agent log lines written there hide the call's secret parameter values (contract
 * amendment A34). The line that reports the failure of a task run on another thread (an EDT_ASYNC call, a posted
 * task) is written with the task's scope bound again, because the task restores the thread's previous scope before
 * its exception reaches the catch. Thread-safe.
 */
final class CoreThreadBroker implements ThreadBroker {

    /** How long a posted APP task may take on the application thread. */
    static final long POST_APP_TIMEOUT_MS = 60000L;
    /** Extra time the outer wait gives a provider to report its own timeout. */
    private static final long PROVIDER_GRACE_MS = 250L;
    /**
     * Error code of a call refused because its pool is full or an earlier call of the same primitive timed out and
     * is still running (finding S30): {@link ErrorCodes#BUSY}. The controller classifies it as an environment error,
     * not a timeout.
     */
    static final int BUSY = ErrorCodes.BUSY;

    private final ExecutorService workers;
    private final SpiRegistry spi;
    private final AgentLog log;

    CoreThreadBroker(ExecutorService workers, SpiRegistry spi, AgentLog log) {
        this.workers = workers;
        this.spi = spi;
        this.log = log;
    }

    @Override
    public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
        if (policy == null || task == null) {
            throw new IllegalArgumentException("policy and task must not be null");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        Thread caller = Thread.currentThread();
        if (isForbiddenThread(caller)) {
            throw forbidden(caller, "may not wait for agent work");
        }
        return run(policy, new Guarded<T>(task, CallScope.current(), this), timeoutMs, null);
    }

    /**
     * Runs a guarded task under the policy. With a {@code callerPool}, CALLER hands the task to that pool and waits
     * with the timeout (the dispatcher's path); without one CALLER runs inline and cannot be bounded.
     *
     * @throws BrokerTimeout when the wait expires
     */
    <T> T run(ThreadPolicy policy, Guarded<T> task, long timeoutMs, ExecutorService callerPool) throws Exception {
        switch (policy) {
            case CALLER:
                return callerPool == null ? task.call() : onPool(callerPool, task, timeoutMs);
            case WORKER:
                return onPool(workers, task, timeoutMs);
            case EDT_SYNC:
                return onEdt(task, timeoutMs);
            case EDT_ASYNC:
                Awt.invokeLater(logging(task));
                return null;
            case APP:
                return onApp(task, timeoutMs);
            case APP_QUIESCENT:
            case LANE:
                // phase8-contracts.md 4.1, 4.2, 4.8 defect 5: every policy is named, none falls through to a worker;
                // this broker runs no quiescent point and no lane yet.
                throw unsupported(policy);
            default:
                throw new IllegalArgumentException("unknown thread policy " + policy);
        }
    }

    /** CAPABILITY_UNAVAILABLE for a Phase 8 policy this broker does not run. */
    private static RpcException unsupported(ThreadPolicy policy) {
        String reason = policy == ThreadPolicy.LANE ? "unknown lane" : "no quiescent point offered";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", reason);
        return new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, policy + ": " + reason, data);
    }

    /** A runnable that runs the task and logs (never throws) its failure under the task's call scope. */
    private Runnable logging(final Guarded<?> task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.call();
                } catch (Throwable t) {
                    task.logFailure(log, "posted task failed", t);
                }
            }
        };
    }

    @Override
    public void post(ThreadPolicy policy, final Runnable task) {
        try {
            if (policy == null || task == null) {
                throw new IllegalArgumentException("policy and task must not be null");
            }
            final Guarded<Object> guarded = new Guarded<Object>(new Callable<Object>() {
                @Override
                public Object call() {
                    task.run();
                    return null;
                }
            }, CallScope.current(), this);
            final Runnable logged = logging(guarded);
            switch (policy) {
                case EDT_SYNC:
                case EDT_ASYNC:
                    Awt.invokeLater(logged);
                    break;
                case APP:
                    final ThreadPolicyProvider provider = appProvider();
                    if (provider == null) {
                        workers.execute(logged);
                    } else {
                        workers.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    provider.callOnAppThread(guarded, POST_APP_TIMEOUT_MS);
                                } catch (Throwable t) {
                                    guarded.logFailure(log, "posted APP task failed", t);
                                }
                            }
                        });
                    }
                    break;
                case APP_QUIESCENT:
                case LANE:
                    // phase8-contracts.md 4.1, 4.2, 4.8 defect 5: refused, never run on a worker: a posted task has no
                    // caller whose time bounds it, and post names no lane.
                    log.warn("a task posted under " + policy + " is refused: use ThreadBroker.call"
                            + (policy == ThreadPolicy.LANE ? "OnLane" : "Quiescent"));
                    break;
                case CALLER:
                case WORKER:
                default:
                    workers.execute(logged);
                    break;
            }
        } catch (Throwable t) {
            log.warn("could not post a task under " + policy, t);
        }
    }

    @Override
    public boolean isForbiddenThread(Thread thread) {
        return forbiddenBy(thread) != null;
    }

    /** The id of the first provider that forbids the thread, or null. A failing provider does not forbid. */
    String forbiddenBy(Thread thread) {
        if (thread == null) {
            return null;
        }
        for (ThreadPolicyProvider provider : spi.all(ThreadPolicyProvider.class)) {
            try {
                if (provider.isForbidden(thread)) {
                    String id = provider.id();
                    return id == null ? provider.getClass().getName() : id;
                }
            } catch (Throwable t) {
                log.warn("ThreadPolicyProvider.isForbidden failed", t);
            }
        }
        return null;
    }

    /**
     * The provider that backs APP now: the first registered {@link ThreadPolicyProvider}, or null when there is none
     * or it reports {@link ThreadPolicyProvider#available()} false (APP then runs on WORKER).
     */
    private ThreadPolicyProvider appProvider() {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        return provider == null || !isAvailable(provider) ? null : provider;
    }

    /** True when a provider is registered and its application thread does not exist yet (contract amendment A19). */
    boolean appThreadUnavailable() {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        return provider != null && !isAvailable(provider);
    }

    private boolean isAvailable(ThreadPolicyProvider provider) {
        try {
            return provider.available();
        } catch (Throwable t) {
            log.warn("ThreadPolicyProvider.available failed; treated as not available", t);
            return false;
        }
    }

    RpcException forbidden(Thread thread, String what) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("thread", thread.getName());
        data.put("provider", forbiddenBy(thread));
        return new RpcException(ErrorCodes.FORBIDDEN_THREAD,
                "thread '" + thread.getName() + "' is forbidden by a ThreadPolicyProvider: " + what, data);
    }

    /** The BUSY error for a pool that refused a task. */
    static RpcException poolFull(String what) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", "pool full");
        data.put("pool", what);
        return new RpcException(BUSY, "every agent " + what + " thread is busy; try again later", data);
    }

    private static <T> Future<T> submit(ExecutorService pool, Callable<T> task, String what) {
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw poolFull(what);
        }
    }

    private <T> T onPool(ExecutorService pool, Callable<T> task, long timeoutMs) throws Exception {
        Future<T> future = submit(pool, task, pool == workers ? "worker" : "caller");
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BrokerTimeout(timeoutMs);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private <T> T onEdt(Guarded<T> task, long timeoutMs) throws Exception {
        if (Awt.onEventDispatchThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<T>(task);
        Awt.invokeLater(future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Never interrupt the EDT; an unstarted task is cancelled and will not run.
            future.cancel(false);
            throw new BrokerTimeout(timeoutMs);
        } catch (InterruptedException e) {
            future.cancel(false);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private <T> T onApp(final Guarded<T> task, final long timeoutMs) throws Exception {
        final ThreadPolicyProvider provider = appProvider();
        if (provider == null) {
            return onPool(workers, task, timeoutMs);
        }
        Future<T> future = submit(workers, new Callable<T>() {
            @Override
            public T call() throws Exception {
                return provider.callOnAppThread(task, timeoutMs);
            }
        }, "worker");
        try {
            // The grace must not overflow a caller's "practically forever".
            return future.get(Math.min(timeoutMs, Long.MAX_VALUE - PROVIDER_GRACE_MS) + PROVIDER_GRACE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BrokerTimeout(timeoutMs);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Exception cause = unwrap(e);
            if (cause instanceof RpcException && ((RpcException) cause).error().code() == ErrorCodes.TIMEOUT
                    && !task.finished()) {
                // The provider gave up waiting for its thread: the dispatcher's timeout, not the primitive's.
                throw new BrokerTimeout(timeoutMs);
            }
            throw cause;
        }
    }

    private static Exception unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return e;
    }

    /**
     * The broker's own timeout (as opposed to a TIMEOUT a primitive raises itself), so that the dispatcher can add
     * its standard detail.
     */
    static final class BrokerTimeout extends RpcException {
        private static final long serialVersionUID = 1L;

        BrokerTimeout(long timeoutMs) {
            super(ErrorCodes.TIMEOUT, "timed out after " + timeoutMs + " ms");
        }
    }

    /**
     * Wraps a task: binds the caller's {@link CallScope} on the executing thread, refuses to run on a forbidden
     * thread, and records which thread ran it and whether it finished.
     */
    static final class Guarded<T> implements Callable<T> {
        private final Callable<T> task;
        private final CallScope scope;
        private final CoreThreadBroker broker;
        private volatile String threadName;
        private volatile boolean finished;

        Guarded(Callable<T> task, CallScope scope, CoreThreadBroker broker) {
            this.task = task;
            this.scope = scope;
            this.broker = broker;
        }

        @Override
        public T call() throws Exception {
            Thread current = Thread.currentThread();
            if (broker.isForbiddenThread(current)) {
                finished = true;
                throw broker.forbidden(current, "agent work may not run on it");
            }
            threadName = current.getName();
            CallScope previous = CallScope.bind(scope);
            try {
                return task.call();
            } finally {
                finished = true;
                CallScope.restore(previous);
            }
        }

        /**
         * Logs a failure of this task with its call's scope bound on the logging thread for the duration of the
         * line, so that the line hides the call's secret parameter values like every other line about the call
         * (contract amendment A34). {@link #call} has restored the thread's previous scope before its exception
         * reaches a catch, and the APP path logs on a worker thread that never had the scope.
         */
        void logFailure(AgentLog log, String message, Throwable t) {
            CallScope previous = CallScope.bind(scope);
            try {
                log.warn(message, t);
            } finally {
                CallScope.restore(previous);
            }
        }

        /** Name of the thread that ran (or is running) the task; null if it has not started. */
        String threadName() {
            return threadName;
        }

        /** True once the task body has returned or thrown (or was refused). */
        boolean finished() {
            return finished;
        }
    }
}
