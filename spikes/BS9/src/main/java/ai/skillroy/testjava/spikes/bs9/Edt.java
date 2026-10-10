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

package ai.skillroy.testjava.spikes.bs9;

import java.awt.EventQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Bounded reads on the EDT from a harness thread (never {@code invokeAndWait} without a deadline). */
final class Edt {

    static final long READ_MS = 3000;

    private Edt() {
    }

    static <T> T call(Callable<T> c) throws Exception {
        return call(c, READ_MS);
    }

    static <T> T call(Callable<T> c, long ms) throws Exception {
        if (EventQueue.isDispatchThread()) {
            return c.call();
        }
        FutureTask<T> f = new FutureTask<T>(c);
        EventQueue.invokeLater(f);
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw e;
        }
    }

    static void run(final Runnable r) throws Exception {
        call(() -> {
            r.run();
            return null;
        });
    }

    /** Waits until the EDT has run everything queued before this call (bounded). */
    static void drain() throws Exception {
        call(() -> null);
    }

    /** Polls {@code cond} on the EDT every 20 ms until it holds or {@code ms} pass. */
    static boolean waitFor(Callable<Boolean> cond, long ms) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (true) {
            if (Boolean.TRUE.equals(call(cond))) {
                return true;
            }
            if (System.nanoTime() >= end) {
                return false;
            }
            Thread.sleep(20);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
