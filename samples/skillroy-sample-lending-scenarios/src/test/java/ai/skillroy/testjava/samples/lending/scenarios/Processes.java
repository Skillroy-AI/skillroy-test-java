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

package ai.skillroy.testjava.samples.lending.scenarios;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The subprocesses the end-to-end checks start (the assembled launcher's {@code session} and {@code run}): waited for
 * within a bound, and never left running when the bound passes, since a launcher still attached to the desk would
 * act on it while the next check runs (as {@code ModelKeyTest} of the CLI destroys its child in a {@code finally}).
 *
 * <p>Stateless; safe from any thread.
 */
final class Processes {

    /** How long the forced end of a process that did not end in time is waited for. */
    static final Duration KILL_WAIT = Duration.ofSeconds(10);

    private Processes() {
    }

    /**
     * Waits for {@code process} to end within {@code within}. When it has not (or the wait is interrupted), it and its
     * descendants are destroyed forcibly and their end is awaited for at most {@link #KILL_WAIT}, before this returns.
     *
     * @return true when the process ended in time; false when it had to be destroyed
     */
    static boolean await(Process process, Duration within) throws InterruptedException {
        boolean ended = false;
        try {
            ended = process.waitFor(within.toMillis(), TimeUnit.MILLISECONDS);
            return ended;
        } finally {
            if (!ended) {
                destroy(process);
            }
        }
    }

    /** Destroys the process and its descendants forcibly and waits, boundedly, until they are gone. */
    static void destroy(Process process) {
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        tree.add(process.toHandle());
        CompletableFuture<?> gone = CompletableFuture.allOf(tree.stream().map(ProcessHandle::onExit)
                .toArray(CompletableFuture[]::new));
        try {
            gone.get(KILL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Still there after the bound: the caller's assertion fails on the process anyway.
        }
    }
}
