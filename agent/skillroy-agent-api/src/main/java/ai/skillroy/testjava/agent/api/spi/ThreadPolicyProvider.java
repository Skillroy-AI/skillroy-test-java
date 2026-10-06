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

package ai.skillroy.testjava.agent.api.spi;

import java.util.concurrent.Callable;

/**
 * Supplies the application thread domain calls must run on, and names threads the agent must never block or run on.
 * Backs {@code ThreadPolicy.APP} and the FORBIDDEN_THREAD check, because many applications confine domain state to
 * one thread of their own.
 *
 * <p>Called from agent threads; implementations must be thread-safe.
 */
public interface ThreadPolicyProvider {

    /** Stable id, for logs. */
    String id();

    /** True if the agent must never block or run work on this thread. Must be cheap. */
    boolean isForbidden(Thread thread);

    /**
     * Runs the task on the application's designated thread and waits at most {@code timeoutMs}; on expiry throws
     * {@code RpcException(TIMEOUT)} (the task may still run later). Exceptions from the task are rethrown.
     */
    <T> T callOnAppThread(Callable<T> task, long timeoutMs) throws Exception;

    /**
     * False while the application thread does not exist yet (for example before the application has started its
     * own worker), so that {@code ThreadPolicy.APP} work does not queue for a thread that may never come (contract
     * amendment A19): read-only APP primitives then run on an agent worker and mutating ones answer
     * {@code NOT_READY}. Must be cheap and must not block; a provider that throws counts as not available. The
     * default is {@code true}.
     */
    default boolean available() {
        return true;
    }
}
