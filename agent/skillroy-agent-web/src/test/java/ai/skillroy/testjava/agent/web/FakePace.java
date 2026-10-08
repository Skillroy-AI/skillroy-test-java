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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock for the web module's re-probe pace (A38) that moves only when a test advances it or when the module waits
 * on it: a wait advances it at once by the time asked and records the time and the waiting thread, so that no real
 * time passes. With {@link #holdWaits} a wait waits for real instead (on the module's monitor, while the time stands
 * still), so that only the module's wake-up (uninstall) ends it.
 */
final class FakePace implements WebModule.Pace {

    private final AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(1000));
    /** The waits the module asked for, in ms, in order. */
    final List<Long> waitedMs = new CopyOnWriteArrayList<Long>();
    /** The thread of each wait. */
    final List<String> waitThreads = new CopyOnWriteArrayList<String>();
    /** Counted down by the first wait made while {@link #holdWaits} is set. */
    final CountDownLatch waiting = new CountDownLatch(1);
    volatile boolean holdWaits;

    @Override
    public long nanoTime() {
        return now.get();
    }

    @Override
    public void await(Object monitor, long ms) throws InterruptedException {
        waitedMs.add(Long.valueOf(ms));
        waitThreads.add(Thread.currentThread().getName());
        if (holdWaits) {
            waiting.countDown();
            monitor.wait(ms);
            return;
        }
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    /** Moves the clock on by {@code ms}. */
    void advance(long ms) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }
}
