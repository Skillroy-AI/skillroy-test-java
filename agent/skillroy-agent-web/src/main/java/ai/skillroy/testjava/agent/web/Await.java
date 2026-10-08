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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.spi.web.WebFrameGoneException;
import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The bounded wait on an adapter's future (phase4-contracts.md section 4.2): every future is awaited on the calling
 * agent worker with the call's remaining budget less {@link #RESERVE_MS}; one that does not complete in time is
 * abandoned with {@code cancel(false)}, never retried on another thread, and the call answers {@code TIMEOUT}. A
 * <b>read</b> (a primitive that is not mutating, D51) is retried once after a {@code web.load} event on that browser
 * (BS8: about one evaluation in twenty timed out, mostly the first after a navigation):
 * <ul>
 *   <li>when a load event arrives while the first attempt is pending, the attempt is abandoned and the read is sent
 *       again at once;</li>
 *   <li>when the call starts within {@link #RECENT_LOAD_MS} of a load event, the first attempt gets
 *       {@link #FIRST_ATTEMPT_AFTER_LOAD_MS} before it is abandoned and the read sent again;</li>
 *   <li>when the first attempt fails because the page navigated under it (an adapter failure that is not a script
 *       error, a gone frame, a missing operation, a timeout or an error of the module, while the browser is open:
 *       Chromium answers a call in flight across a navigation with "Inspected target navigated or closed", before
 *       it reports the navigation) and a load event follows within {@link #FIRST_ATTEMPT_AFTER_LOAD_MS}, the read is
 *       sent again once the new document loaded.</li>
 * </ul>
 * Every retry stays within the call's budget. A mutating call is never retried (neither by the module nor, for a call
 * that reached the page, by the built-in adapters). Failures are mapped to the protocol's errors:
 * {@link WebScriptException} to
 * {@code SCRIPT_ERROR} with {@code data {browser, message, stack}}, {@link WebFrameGoneException} to
 * {@code NOT_FOUND} with {@code data {browser, frame}}, {@code UnsupportedOperationException} to
 * {@code CAPABILITY_UNAVAILABLE}, an {@code IllegalStateException} of a browser that has closed to
 * {@code STALE_HANDLE}, a timeout to {@code TIMEOUT}, anything else to {@code INTERNAL_ERROR}.
 *
 * <p>Stateless; call on agent workers only (it blocks the calling thread for at most the budget).
 */
final class Await {

    /** Kept back from the call's remaining budget so that the call answers before the dispatcher's TIMEOUT. */
    static final long RESERVE_MS = 100;
    /** A call that starts this soon after a load event retries its read early. */
    static final long RECENT_LOAD_MS = 2000;
    /** How long the first attempt of such a read may take before it is sent again. */
    static final long FIRST_ATTEMPT_AFTER_LOAD_MS = 2000;

    /** Supplies one attempt of a read. */
    interface Attempt<T> {
        CompletableFuture<T> start();
    }

    private Await() {
    }

    /** The budget for an adapter future: the call's remaining time less {@link #RESERVE_MS}, at least 1 ms. */
    static long budget(Invocation inv) {
        return Math.max(1, inv.remainingMs() - RESERVE_MS);
    }

    /**
     * Waits for {@code future} at most {@code timeoutMs}; abandons it on timeout.
     *
     * @param capability the capability named in a {@code CAPABILITY_UNAVAILABLE} answer
     */
    static <T> T get(CompletableFuture<T> future, long timeoutMs, Entry entry, String primitive, String capability) {
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw Errors.timeout(primitive, entry.ref, timeoutMs);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw Errors.timeout(primitive, entry.ref, timeoutMs);
        } catch (CancellationException e) {
            throw Errors.timeout(primitive, entry.ref, timeoutMs);
        } catch (ExecutionException e) {
            throw map(e.getCause(), entry, primitive, capability, timeoutMs);
        }
    }

    /** A read: as {@link #get}, retried once after a load event (see the class comment). */
    static <T> T read(Attempt<T> attempt, long timeoutMs, Entry entry, String primitive, String capability) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        boolean recentLoad = entry.loads.msSinceLast() <= RECENT_LOAD_MS;
        CompletableFuture<Void> nextLoad = entry.loads.next();
        CompletableFuture<T> first = attempt.start();
        long left = remainingMs(deadline);
        long wait = recentLoad ? Math.min(left, FIRST_ATTEMPT_AFTER_LOAD_MS) : left;
        try {
            CompletableFuture.anyOf(first, nextLoad).get(Math.max(1, wait), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (wait >= left) {
                first.cancel(false);
                throw Errors.timeout(primitive, entry.ref, timeoutMs);
            }
            // fall through: the first attempt after a recent load took too long, send it again
        } catch (InterruptedException e) {
            first.cancel(false);
            Thread.currentThread().interrupt();
            throw Errors.timeout(primitive, entry.ref, timeoutMs);
        } catch (ExecutionException e) {
            // the first attempt failed: reported below
        } catch (CancellationException e) {
            // the first attempt was cancelled: reported below
        }
        if (first.isDone() && !failedAsThePageNavigated(first, nextLoad, deadline, entry)) {
            return get(first, 1, entry, primitive, capability);
        }
        first.cancel(false);
        left = remainingMs(deadline);
        if (left <= 0) {
            throw Errors.timeout(primitive, entry.ref, timeoutMs);
        }
        return get(attempt.start(), left, entry, primitive, capability);
    }

    /**
     * True when the first attempt of a read failed with an adapter failure (not a script error, a gone frame, a missing
     * operation, a timeout or a protocol error of the module) while the browser is open, and a load event of that
     * browser follows within {@link #FIRST_ATTEMPT_AFTER_LOAD_MS} (bounded by the call's deadline): the page navigated
     * under the read, which is then sent again into the new document.
     */
    private static boolean failedAsThePageNavigated(CompletableFuture<?> first, CompletableFuture<Void> nextLoad,
                                                    long deadline, Entry entry) {
        if (!first.isCompletedExceptionally() || first.isCancelled()) {
            return false;
        }
        Throwable c;
        try {
            first.getNow(null);
            return false;
        } catch (CompletionException e) {
            c = e.getCause();
        } catch (CancellationException e) {
            return false;
        }
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof RpcException || c instanceof WebScriptException || c instanceof WebFrameGoneException
                || c instanceof UnsupportedOperationException || c instanceof TimeoutException
                || c instanceof CancellationException || closed(entry)) {
            return false;
        }
        long wait = Math.min(FIRST_ATTEMPT_AFTER_LOAD_MS, remainingMs(deadline));
        if (wait <= 0) {
            return false;
        }
        try {
            nextLoad.get(wait, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** The protocol error for an adapter's failure. */
    static RpcException map(Throwable cause, Entry entry, String primitive, String capability, long timeoutMs) {
        Throwable c = cause;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        if (c instanceof RpcException) {
            return (RpcException) c;
        }
        if (c instanceof WebScriptException) {
            WebScriptException s = (WebScriptException) c;
            return Errors.script(entry.ref, s.getMessage(), s.stack());
        }
        if (c instanceof WebFrameGoneException) {
            String key = ((WebFrameGoneException) c).frameKey();
            return Errors.notFound("frame '" + key + "' of browser " + entry.ref + " no longer exists (it navigated "
                    + "away or was removed); read the frames again with web.browsers",
                    Errors.data("browser", entry.ref, "frame", key));
        }
        if (c instanceof TimeoutException || c instanceof CancellationException) {
            return Errors.timeout(primitive, entry.ref, timeoutMs);
        }
        if (c instanceof UnsupportedOperationException) {
            String reason = c.getMessage() != null ? c.getMessage()
                    : "adapter " + entry.adapter.id() + " does not support it for this browser";
            return Errors.unavailable(primitive, capability, reason, entry.ref);
        }
        if (c instanceof IllegalStateException && closed(entry)) {
            return Errors.staleBrowser(entry.ref, "has closed");
        }
        String what = c == null ? "unknown failure" : c.getClass().getSimpleName()
                + (c.getMessage() == null ? "" : ": " + c.getMessage());
        return Errors.internal(entry.ref, primitive + " failed on browser " + entry.ref + ": " + what);
    }

    private static boolean closed(Entry entry) {
        try {
            return entry.browser().isClosed();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long remainingMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }
}
