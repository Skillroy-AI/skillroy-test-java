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

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.QueryControl;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@link QueryControl} of one {@code data.query} read (phase8-contracts.md 4.3, D529, D638): the read's remaining
 * time (the call's, less the module's reserve), the cancelled flag and the registered cancel actions.
 *
 * <p>When the module gives up on the read ({@link #cancel()}), the actions run once, in registration order, off the
 * dispatching worker: {@link #startActions(ThreadBroker)} posts one agent worker task that runs each action as a
 * nested worker task of its own, bounded by {@link QueryControl#ACTION_TIMEOUT_MS} (an action still running then is
 * abandoned there and logged by its class name, as is one that throws). An action registered after cancellation runs
 * at once on the registering thread.
 *
 * <p>Thread-safe; the action list is guarded by this object's monitor, which is never held while an action runs.
 */
final class Control implements QueryControl {

    /** An action the module registers itself (the JDBC statement's cancel): it says whether it cancelled anything. */
    interface Action {
        /** Runs the action; true when it cancelled the read, false when there was nothing to cancel. */
        boolean cancel() throws Exception;

        /** What the log names the action by. */
        String describe();
    }

    private final long deadlineNanos;
    private final AgentLog log;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** Registered and not yet unregistered; guarded by this. */
    private final List<Registration> actions = new ArrayList<Registration>();
    /** Every action registered so far, closed ones included (the bound of 4 counts these); guarded by this. */
    private int registered;
    /** Set once an action returned without throwing (and, for an {@link Action}, said it cancelled). */
    private final AtomicBoolean cancelledByAction = new AtomicBoolean();
    /** Actions started and not yet ended. */
    private final AtomicInteger running = new AtomicInteger();
    /** Counted down when the batch of actions has ended (at once when there was none). */
    private final CountDownLatch batchDone = new CountDownLatch(1);

    /** @param deadlineNanos {@code System.nanoTime()} at which the read's time is spent */
    Control(long deadlineNanos, AgentLog log) {
        this.deadlineNanos = deadlineNanos;
        this.log = log;
    }

    @Override
    public long remainingMs() {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    @Override
    public boolean cancelled() {
        return cancelled.get();
    }

    @Override
    public AutoCloseable onCancel(final Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        return register(new Action() {
            @Override
            public boolean cancel() {
                action.run();
                return true;
            }

            @Override
            public String describe() {
                return action.getClass().getName();
            }
        });
    }

    /** Registers the module's own action (the JDBC statement's cancel); the bound of four counts it too. */
    AutoCloseable onCancelInternal(Action action) {
        return register(action);
    }

    private AutoCloseable register(Action action) {
        final Registration registration = new Registration(action);
        synchronized (this) {
            if (registered >= MAX_ACTIONS) {
                throw new IllegalStateException("a read may register at most " + MAX_ACTIONS + " cancel actions");
            }
            registered++;
            if (!cancelled.get()) {
                actions.add(registration);
                return registration;
            }
        }
        // Registered after cancellation: run at once, on the registering thread.
        runOne(registration);
        return registration;
    }

    /** Marks the read cancelled; true the first time. */
    boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /**
     * Starts the registered actions, in registration order, as one posted agent worker task (never on the calling,
     * dispatching worker), each bounded by {@link QueryControl#ACTION_TIMEOUT_MS} on a nested worker task of its own.
     * Call after {@link #cancel()}.
     */
    void startActions(final ThreadBroker threads) {
        final List<Registration> batch;
        synchronized (this) {
            batch = new ArrayList<Registration>(actions);
            actions.clear();
        }
        if (batch.isEmpty()) {
            batchDone.countDown();
            return;
        }
        running.addAndGet(batch.size());
        threads.post(ThreadPolicy.WORKER, new Runnable() {
            @Override
            public void run() {
                try {
                    for (final Registration registration : batch) {
                        try {
                            Boolean done = threads.call(ThreadPolicy.WORKER, new Callable<Boolean>() {
                                @Override
                                public Boolean call() throws Exception {
                                    return Boolean.valueOf(registration.action.cancel());
                                }
                            }, ACTION_TIMEOUT_MS);
                            if (Boolean.TRUE.equals(done)) {
                                cancelledByAction.set(true);
                            }
                        } catch (RpcException e) {
                            if (e.error().code() == ErrorCodes.TIMEOUT) {
                                log.warn("data: the cancel action " + registration.action.describe() + " still ran after "
                                        + ACTION_TIMEOUT_MS + " ms; abandoned");
                            } else {
                                log.warn("data: the cancel action " + registration.action.describe()
                                        + " could not run: " + e.getClass().getName() + " (" + e.error().code() + ")");
                            }
                        } catch (Throwable t) {
                            log.warn("data: the cancel action " + registration.action.describe() + " failed: "
                                    + t.getClass().getName());
                        } finally {
                            running.decrementAndGet();
                        }
                    }
                } finally {
                    batchDone.countDown();
                }
            }
        });
    }

    /** Runs one action on the calling thread, never throwing (registered after cancellation). */
    private void runOne(Registration registration) {
        try {
            if (registration.action.cancel()) {
                cancelledByAction.set(true);
            }
        } catch (Throwable t) {
            log.warn("data: the cancel action " + registration.action.describe() + " failed: "
                    + t.getClass().getName());
        }
    }

    /** Waits until the batch of actions has ended, at most until {@code untilNanos}. */
    void awaitActions(long untilNanos) throws InterruptedException {
        batchDone.await(Math.max(0L, untilNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    /** True once an action returned without throwing (the JDBC one: and cancelled an open statement). */
    boolean cancelledByAction() {
        return cancelledByAction.get();
    }

    /** True while an action of the batch is still running. */
    boolean actionsPending() {
        return running.get() > 0;
    }

    /** One registered action; closing it unregisters the action (a no-op once the batch took it). */
    private final class Registration implements AutoCloseable {
        final Action action;

        Registration(Action action) {
            this.action = action;
        }

        @Override
        public void close() {
            synchronized (Control.this) {
                actions.remove(this);
            }
        }
    }
}
