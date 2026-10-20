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

import ai.skillroy.testjava.agent.api.Lane;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A broker for the data module's tests with the agent's semantics where {@code data.query} relies on them
 * (phase8-contracts.md 4.2, 4.3): WORKER tasks on daemon threads named {@code skillroy-worker-test-<n>}, a bounded wait
 * that interrupts the task and answers {@code TIMEOUT}, {@code post} on a worker, and declared lanes on threads named
 * {@code skillroy-lane-<name>-<n>} ({@code CAPABILITY_UNAVAILABLE "unknown lane"} for another name). It does not
 * bound its pools or count abandoned tasks: the core's {@code LaneTest} proves those.
 */
final class TestBroker implements ThreadBroker {

    private final AtomicInteger workerNumber = new AtomicInteger();
    private final ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "skillroy-worker-test-" + workerNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });
    private final Map<String, ExecutorService> lanes = new ConcurrentHashMap<String, ExecutorService>();

    @Override
    public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
        return await(workers.submit(task), timeoutMs);
    }

    @Override
    public void post(ThreadPolicy policy, Runnable task) {
        workers.execute(task);
    }

    @Override
    public boolean isForbiddenThread(Thread thread) {
        return false;
    }

    @Override
    public Lane declareLane(final String name, final int threads, final int queue) {
        final AtomicInteger number = new AtomicInteger();
        lanes.put(name, Executors.newFixedThreadPool(threads, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "skillroy-lane-" + name + "-" + number.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }));
        return new Lane() {
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
                ExecutorService pool = lanes.remove(name);
                if (pool != null) {
                    pool.shutdownNow();
                }
            }
        };
    }

    @Override
    public <T> T callOnLane(String lane, Callable<T> task, long timeoutMs) throws Exception {
        ExecutorService pool = lanes.get(lane);
        if (pool == null) {
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "unknown lane: " + lane);
        }
        return await(pool.submit(task), timeoutMs);
    }

    private static <T> T await(Future<T> future, long timeoutMs) throws Exception {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RpcException(ErrorCodes.TIMEOUT, "timed out after " + timeoutMs + " ms");
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
}
