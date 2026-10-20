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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.QuiescentPoint;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The lending desk's thread policy: domain calls ({@code ThreadPolicy.APP}) run one at a time on a single daemon thread
 * {@value #THREAD_NAME}, never on the desk's engine thread, which is forbidden. Why not the engine thread: it is
 * the application's own state machine, blocked in its inbox or in a modal prompt, and the agent must never block it
 * or run work on it. The desk publishes immutable snapshots that any thread may read, so a separate thread is
 * enough.
 *
 * <p>The worker thread is created on first use and ends after a minute without work. Thread-safe.
 *
 * <p>Phase 8 (phase8-contracts.md 4.1, 12.2): the worker also offers the desk's quiescent point, one
 * {@link QuiescentPoint} with an allowance of {@value #POINT_ALLOWANCE_MS} ms and room for {@value #POINT_CAPACITY}
 * tasks, which the module's engine listener drives from the engine's idle callback ({@code EngineEvents.idle}); a task
 * of {@code ThreadPolicy.APP_QUIESCENT} then runs on the engine thread itself, at the point the engine chose.
 */
final class LendingWorker implements ThreadPolicyProvider {

    /** Name of the worker thread. */
    static final String THREAD_NAME = "skillroy-lending-worker";

    private static final ThreadLocal<Boolean> ON_WORKER = new ThreadLocal<Boolean>();

    /** The quiescent point's allowance and capacity (phase8-contracts.md 12.2). */
    static final long POINT_ALLOWANCE_MS = 50L;
    static final int POINT_CAPACITY = 4;

    private final LendingAccess access;
    private final ThreadPoolExecutor executor;
    private final QuiescentPoint point = new QuiescentPoint(POINT_ALLOWANCE_MS, POINT_CAPACITY);

    LendingWorker(LendingAccess access) {
        this.access = access;
        this.executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(final Runnable r) {
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ON_WORKER.set(Boolean.TRUE);
                                r.run();
                            }
                        }, THREAD_NAME);
                        t.setDaemon(true);
                        return t;
                    }
                });
        executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public String id() {
        return "lending";
    }

    @Override
    public boolean isForbidden(Thread thread) {
        return access.isEngineThread(thread);
    }

    @Override
    public <T> T callOnAppThread(Callable<T> task, long timeoutMs) throws Exception {
        if (Boolean.TRUE.equals(ON_WORKER.get())) {
            return task.call();
        }
        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "the lending module is stopped");
        }
        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RpcException(ErrorCodes.TIMEOUT, "the lending worker did not finish within " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /** The desk's quiescent point, the same instance on every call (phase8-contracts.md 4.1). */
    @Override
    public QuiescentPoint quiescentPoint() {
        return point;
    }

    /** Closes the quiescent point: queued tasks are withdrawn, nothing runs on the engine thread any more. */
    void closePoint() {
        point.close();
    }

    /** Stops the worker: queued tasks are dropped and a running one is interrupted. */
    void shutdown() {
        executor.shutdownNow();
    }
}
