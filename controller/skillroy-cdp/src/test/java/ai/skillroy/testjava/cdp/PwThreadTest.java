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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one Playwright thread of an attach: bounded waits, abandoned operations, refusal while one still runs. */
class PwThreadTest {

    @Test
    void runsOnOnePlatformDaemonThreadAndRethrowsTheBodysException() throws Exception {
        PwThread pw = new PwThread("pw-test");
        try {
            AtomicReference<Thread> thread = new AtomicReference<>();
            assertEquals(42, pw.call(() -> {
                thread.set(Thread.currentThread());
                return 42;
            }, 1000));
            assertEquals("pw-test", thread.get().getName());
            assertTrue(thread.get().isDaemon());
            assertFalse(thread.get().isVirtual());
            IOException e = assertThrows(IOException.class, () -> pw.call(() -> {
                throw new IOException("checked");
            }, 1000));
            assertEquals("checked", e.getMessage());
        } finally {
            pw.shutdownNow();
        }
    }

    @Test
    void aBodyThatOutlivesItsCallerMakesTheThreadBusyUntilItReturns() throws Exception {
        PwThread pw = new PwThread("pw-test");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            assertThrows(TimeoutException.class, () -> pw.call(() -> {
                release.await();
                finished.countDown();
                return null;
            }, 100));
            assertTrue(pw.busy());
            PwThread.Busy busy = assertThrows(PwThread.Busy.class, () -> pw.call(() -> 1, 1000));
            assertEquals("an earlier CDP operation has not returned (a page may be frozen)", busy.getMessage());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(2, waitForNotBusy(pw).call(() -> 2, 1000));
        } finally {
            release.countDown();
            pw.shutdownNow();
        }
    }

    @Test
    void aQueuedBodyWhoseCallerGaveUpNeverRuns() throws Exception {
        PwThread pw = new PwThread("pw-test");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();
        try {
            CompletableFuture<Object> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return pw.call(() -> {
                        running.countDown();
                        release.await();
                        return "first";
                    }, 10_000);
                } catch (Exception e) {
                    return e;
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> pw.call(() -> {
                secondRan.set(true);
                return null;
            }, 100));
            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals(3, waitForNotBusy(pw).call(() -> 3, 1000), "the queue is free again");
            assertFalse(secondRan.get(), "the second body was abandoned before it started");
        } finally {
            release.countDown();
            pw.shutdownNow();
        }
    }

    @Test
    void aShutDownThreadRefusesEverything() {
        PwThread pw = new PwThread("pw-test");
        pw.shutdownNow();
        assertTrue(pw.busy());
        assertThrows(PwThread.Busy.class, () -> pw.call(() -> 1, 1000));
    }

    /** The thread leaves the busy state as soon as the abandoned body returned; poll that bounded. */
    private static PwThread waitForNotBusy(PwThread pw) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pw.busy() && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertFalse(pw.busy());
        return pw;
    }
}
