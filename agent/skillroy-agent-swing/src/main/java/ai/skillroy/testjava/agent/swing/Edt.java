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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.ThreadPolicy;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * The module's only route onto the EDT. Reads run as bounded {@link ThreadPolicy#EDT_SYNC} calls through the agent's
 * thread broker; actions are posted with {@link ThreadPolicy#EDT_ASYNC}, so that an action which opens a modal dialog
 * never holds the calling thread.
 *
 * <p>Exceptions thrown on the EDT are carried back as values, so the caller sees the original exception (an
 * {@code RpcException} keeps its code) whatever the broker does with task failures. A posted action that throws is
 * caught and logged; it never reaches the EDT's uncaught-exception handler.
 *
 * <p>Thread-safe. Call from an agent thread, never from the EDT.
 */
final class Edt {

    /** Smallest wait handed to the broker, so that an exhausted budget still produces a clean TIMEOUT. */
    private static final long MIN_WAIT_MS = 1;

    private Edt() {
    }

    /** Work to run on the EDT; may throw. */
    interface Action {
        void run() throws Exception;
    }

    /**
     * Runs {@code body} on the EDT and waits at most {@code timeoutMs}; throws what the body threw, or the broker's
     * {@code RpcException(TIMEOUT)}.
     */
    static <T> T read(AgentContext ctx, long timeoutMs, final Callable<T> body) throws Exception {
        Outcome<T> outcome = ctx.threads().call(ThreadPolicy.EDT_SYNC, new Callable<Outcome<T>>() {
            @Override
            public Outcome<T> call() {
                try {
                    return new Outcome<T>(body.call(), null);
                } catch (Throwable t) {
                    return new Outcome<T>(null, t);
                }
            }
        }, Math.max(MIN_WAIT_MS, timeoutMs));
        if (outcome == null) {
            throw new IllegalStateException("the EDT read produced no outcome");
        }
        return outcome.get();
    }

    /** Posts {@code action} to the EDT and returns once it is queued; failures of the action are logged. */
    static void post(final AgentContext ctx, long timeoutMs, final String what, final Action action)
            throws Exception {
        ctx.threads().call(ThreadPolicy.EDT_ASYNC, new Callable<Object>() {
            @Override
            public Object call() {
                runSafely(ctx, what, action);
                return null;
            }
        }, Math.max(MIN_WAIT_MS, timeoutMs));
    }

    /** Runs an action, logging anything it throws except a VM error. Used on the EDT. */
    static void runSafely(AgentContext ctx, String what, Action action) {
        try {
            action.run();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            try {
                ctx.log().warn("swing: action '" + what + "' failed on the EDT: " + t, t);
            } catch (Throwable ignored) {
                // the log must never be the reason an exception reaches the EDT
            }
        }
    }

    /** A value or the throwable that replaced it. */
    private static final class Outcome<T> {
        private final T value;
        private final Throwable failure;

        Outcome(T value, Throwable failure) {
            this.value = value;
            this.failure = failure;
        }

        T get() throws Exception {
            if (failure == null) {
                return value;
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw new ExecutionException(failure);
        }
    }
}
