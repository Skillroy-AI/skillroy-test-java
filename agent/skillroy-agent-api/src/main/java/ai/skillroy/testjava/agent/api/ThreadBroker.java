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

import java.util.concurrent.Callable;

/**
 * Runs work under a {@link ThreadPolicy} with a bounded wait. Centralises the rule that the agent never blocks the
 * EDT or an application thread indefinitely and never runs on a thread the application forbids, except a task the
 * core submitted through a {@link QuiescentPoint}, at the point the provider itself runs it (phase8-contracts.md 4.1,
 * D520).
 *
 * <p>Implementations are thread-safe and may be called from any thread. The Phase 8 methods
 * ({@link #callQuiescent}, {@link #declareLane}, {@link #callOnLane}) have defaults that throw
 * {@code UnsupportedOperationException}, so that a broker written for 0.1.0 (a test fake, for example) compiles
 * unchanged; the agent's own broker implements them.
 */
public interface ThreadBroker {

    /**
     * Runs the task under the policy and waits at most timeoutMs. Throws RpcException(TIMEOUT) on expiry and
     * RpcException(FORBIDDEN_THREAD) if the calling thread is one a ThreadPolicyProvider forbids. For EDT_ASYNC the
     * returned value is always null and the call returns when the task has been posted. Under APP_QUIESCENT it is
     * {@code callQuiescent(task, 50, timeoutMs)}; under LANE it is refused (the lane has no name here: use
     * {@link #callOnLane}).
     */
    <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception;

    /**
     * Schedules the task under the policy and returns at once; failures are logged, not thrown. APP_QUIESCENT and
     * LANE are refused (one {@code warn} line; the task never runs): a posted task has no caller whose time bounds
     * it, and {@code post} names no lane.
     */
    void post(ThreadPolicy policy, Runnable task);

    /** True if a ThreadPolicyProvider forbids the agent to block or run work on this thread. */
    boolean isForbiddenThread(Thread thread);

    /**
     * Runs the task on the application's own thread at its next quiescent point, through the first
     * ThreadPolicyProvider's {@link ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider#quiescentPoint()
     * quiescentPoint()}, admitted by {@code budgetMs} and waiting at most {@code timeoutMs} (phase8-contracts.md 4.1,
     * D519). Never falls back to another thread and never interrupts the application's thread. Refused with
     * FORBIDDEN_THREAD from a forbidden thread, a task running at a quiescent point included; CAPABILITY_UNAVAILABLE
     * when no point is offered; NOT_READY when the provider is not available or no point came in time; BUSY when the
     * point's queue is full; TIMEOUT when the task was admitted and still runs.
     *
     * <p>The default throws {@code UnsupportedOperationException}.
     *
     * @param budgetMs 1 to the point's allowance: the most the task may hold the application's thread
     * @param timeoutMs 1 to 120,000
     */
    default <T> T callQuiescent(Callable<T> task, long budgetMs, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("this broker offers no quiescent point (phase8-contracts.md 4.1)");
    }

    /**
     * Declares a named lane of agent-owned daemon threads (phase8-contracts.md 4.2, D525): {@code name} follows
     * {@link Lane#NAME_PATTERN} (at most 64 characters, {@code <module id>.<word>} by convention), {@code threads} 1 to
     * 4, {@code queue} 0 to 16 (0: a task that finds every thread busy is refused at once). Threads are named
     * {@code skillroy-lane-<name>-<n>}, created on demand and ended after 30 s idle. A module's lanes are retired
     * with the module (after its {@code uninstall}, and when its install fails) and on agent close; closing the
     * returned handle retires the lane earlier.
     *
     * <p>The default throws {@code UnsupportedOperationException}.
     *
     * @throws IllegalArgumentException for a name, thread count or queue outside the bounds
     * @throws IllegalStateException for a ninth lane of the agent, a name already declared, or a declaration after the
     *     agent started closing
     */
    default Lane declareLane(String name, int threads, int queue) {
        throw new UnsupportedOperationException("this broker offers no lanes (phase8-contracts.md 4.2)");
    }

    /**
     * Runs the task on the declared lane {@code lane} and waits at most {@code timeoutMs} (phase8-contracts.md 4.2).
     * FORBIDDEN_THREAD from a forbidden caller; CAPABILITY_UNAVAILABLE for an unknown lane ("unknown lane") or a
     * retired one ("lane closed"); BUSY when every thread is busy and the queue is full ("lane full") or every thread
     * is held by a task whose caller gave up ("lane stuck"); TIMEOUT on expiry (a queued task is withdrawn and never
     * runs; a running one is interrupted, best effort, and counted abandoned until it ends). The call's scope
     * travels with the task, as on a worker.
     *
     * <p>The default throws {@code UnsupportedOperationException}.
     *
     * @throws IllegalArgumentException if {@code timeoutMs} is not positive
     */
    default <T> T callOnLane(String lane, Callable<T> task, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("this broker offers no lanes (phase8-contracts.md 4.2)");
    }
}
