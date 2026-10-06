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

package ai.skillroy.testjava.samples.lending;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The engine's input queue. Producers on any thread {@link #offer} inputs and return at once; the engine thread is
 * the only consumer and blocks in {@code take()} while it waits for input. An input is checked against the state
 * only when the engine takes it, so an input offered for a state the engine has since left is dropped then.
 */
public final class Inbox {

    /** Inputs beyond this many queued are refused by {@link #offer}. */
    public static final int CAPACITY = 256;

    private final LinkedBlockingQueue<LendingInput> queue = new LinkedBlockingQueue<LendingInput>(CAPACITY);

    /**
     * Queues an input without blocking. Thread-safe.
     *
     * @param input the input; null is refused
     * @return false if the input was null or the inbox is full
     */
    public boolean offer(LendingInput input) {
        return input != null && queue.offer(input);
    }

    /** @return the number of inputs waiting to be consumed */
    public int size() {
        return queue.size();
    }

    /** Engine thread only: waits for the next input. */
    LendingInput take() throws InterruptedException {
        return queue.take();
    }

    /** Engine thread only: waits at most {@code timeoutMs} for the next input; null on timeout. */
    LendingInput poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Discards queued inputs (used when the engine stops). */
    void clear() {
        queue.clear();
    }
}
