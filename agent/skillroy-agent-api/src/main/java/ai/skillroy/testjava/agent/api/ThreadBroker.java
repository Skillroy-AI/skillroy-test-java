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
 * EDT or an application thread indefinitely and never runs on a thread the application forbids.
 *
 * <p>Implementations are thread-safe and may be called from any thread.
 */
public interface ThreadBroker {

    /**
     * Runs the task under the policy and waits at most timeoutMs. Throws RpcException(TIMEOUT) on expiry and
     * RpcException(FORBIDDEN_THREAD) if the calling thread is one a ThreadPolicyProvider forbids. For EDT_ASYNC the
     * returned value is always null and the call returns when the task has been posted.
     */
    <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception;

    /** Schedules the task under the policy and returns at once; failures are logged, not thrown. */
    void post(ThreadPolicy policy, Runnable task);

    /** True if a ThreadPolicyProvider forbids the agent to block or run work on this thread. */
    boolean isForbiddenThread(Thread thread);
}
