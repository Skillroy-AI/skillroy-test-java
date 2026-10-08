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

package ai.skillroy.testjava.agent.web.reflect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An adapter's threads ({@link AdapterThreads}): {@code shutdown()} ends them and a later task starts new ones;
 * {@code close()} (an adapter's detach) ends them for good: every later task is refused, a serial executor drops it,
 * and no thread starts again.
 */
class AdapterThreadsTest {

    private static final String PREFIX = "adapter-threads-test";

    @Test
    void afterCloseEveryTaskIsRefusedAndNoThreadStarts() throws Exception {
        final AdapterThreads threads = new AdapterThreads(PREFIX);
        final CountDownLatch ran = new CountDownLatch(1);
        threads.executor().execute(new Runnable() {
            @Override
            public void run() {
                ran.countDown();
            }
        });
        assertTrue(ran.await(10, TimeUnit.SECONDS));
        Executor serial = threads.serial();
        threads.close();
        assertTrue(threads.isClosed());
        assertThrows(RejectedExecutionException.class, () -> threads.executor().execute(new Runnable() {
            @Override
            public void run() {
                // never runs
            }
        }));
        assertThrows(RejectedExecutionException.class, () -> threads.schedule(new Runnable() {
            @Override
            public void run() {
                // never runs
            }
        }, 1));
        final AtomicInteger dropped = new AtomicInteger();
        serial.execute(new Runnable() {
            @Override
            public void run() {
                dropped.incrementAndGet();
            }
        });
        assertEquals(0, dropped.get(), "a serial executor of closed threads drops its task");
        threads.shutdown();
        assertThrows(RejectedExecutionException.class, () -> threads.executor().execute(new Runnable() {
            @Override
            public void run() {
                // never runs
            }
        }), "shutdown() does not reopen closed threads");
        awaitNoThread();
    }

    @Test
    void afterShutdownALaterTaskStartsNewThreads() throws Exception {
        AdapterThreads threads = new AdapterThreads(PREFIX);
        try {
            final CountDownLatch first = new CountDownLatch(1);
            threads.executor().execute(new Runnable() {
                @Override
                public void run() {
                    first.countDown();
                }
            });
            assertTrue(first.await(10, TimeUnit.SECONDS));
            threads.shutdown();
            assertFalse(threads.isClosed());
            final CountDownLatch second = new CountDownLatch(1);
            threads.executor().execute(new Runnable() {
                @Override
                public void run() {
                    second.countDown();
                }
            });
            assertTrue(second.await(10, TimeUnit.SECONDS));
        } finally {
            threads.close();
        }
        awaitNoThread();
    }

    /** Every thread of the prefix ends (bounded). */
    private static void awaitNoThread() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> alive = new ArrayList<String>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith(PREFIX)) {
                t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                if (t.isAlive()) {
                    alive.add(t.getName());
                }
            }
        }
        assertEquals(new ArrayList<String>(), alive, "every thread ended");
    }
}
