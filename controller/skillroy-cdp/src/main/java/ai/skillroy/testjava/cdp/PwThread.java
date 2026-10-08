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

package ai.skillroy.testjava.cdp;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one platform thread that makes every Playwright call of an attach. Playwright Java is not thread-safe, and a
 * call into a page whose renderer is busy (an endless loop, a frozen target) does not return until the page is free,
 * so callers never make Playwright calls themselves: they hand an operation to this thread and wait for it with
 * their own bound.
 *
 * <p>A caller whose bound passes gets {@link TimeoutException}; an operation that had not started by then never runs,
 * and one that is running is left to finish (it cannot be stopped without closing Playwright). While such an
 * abandoned operation still runs, every new operation is refused at once with {@link Busy}, so that nothing queues
 * behind a frozen page and a later operation never runs after its caller gave up.
 *
 * <p>Thread-safe.
 */
final class PwThread {

    /** The thread is still running an operation whose caller gave up, or the thread was shut down. */
    static final class Busy extends Exception {

        private static final long serialVersionUID = 1L;

        Busy(String message) {
            super(message);
        }
    }

    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int DONE = 2;
    private static final int ABANDONED_BEFORE_START = 3;

    /** One submitted operation; {@code givenUp} is set when its caller stopped waiting while it ran. */
    private static final class Op<T> implements Callable<T> {

        final Callable<T> body;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        volatile boolean givenUp;

        Op(Callable<T> body) {
            this.body = body;
        }

        @Override
        public T call() throws Exception {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                throw new CancellationException("the caller gave up before the operation started");
            }
            try {
                return body.call();
            } finally {
                state.set(DONE);
            }
        }
    }

    private final ExecutorService executor;
    private volatile Op<?> abandoned;

    PwThread(String name) {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Runs {@code body} on the thread and waits at most {@code budgetMs} (at least 1 ms).
     *
     * @throws Busy if an abandoned operation is still running or the thread was shut down; {@code body} did not run
     * @throws TimeoutException if the budget passed; {@code body} either never runs or is left to finish
     * @throws InterruptedException if the caller was interrupted (handled as a timeout)
     * @throws Exception whatever {@code body} threw
     */
    <T> T call(Callable<T> body, long budgetMs) throws Exception {
        if (busy()) {
            throw new Busy("an earlier CDP operation has not returned (a page may be frozen)");
        }
        Op<T> op = new Op<>(body);
        Future<T> future;
        try {
            future = executor.submit(op);
        } catch (RejectedExecutionException e) {
            throw new Busy("the CDP attach is closed");
        }
        try {
            return future.get(Math.max(1, budgetMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            if (!op.state.compareAndSet(QUEUED, ABANDONED_BEFORE_START) && op.state.get() == RUNNING) {
                op.givenUp = true;
                abandoned = op;
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception x) {
                throw x;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /** True while an operation whose caller gave up is still running, or after {@link #shutdownNow()}. */
    boolean busy() {
        if (executor.isShutdown()) {
            return true;
        }
        Op<?> op = abandoned;
        if (op == null) {
            return false;
        }
        if (op.state.get() == RUNNING) {
            return true;
        }
        abandoned = null;
        return false;
    }

    /** Stops accepting operations and interrupts a running one (after Playwright was closed, which ends it). */
    void shutdownNow() {
        executor.shutdownNow();
    }
}
