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

package ai.skillroy.testjava.agent.web.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The kit's guard of the thread that must never be blocked (phase4-contracts.md section 10.1 item 3): every
 * {@value #PERIOD_MS} ms a marker is posted to that thread; a marker that runs more than {@value #LIMIT_MS} ms after
 * it was posted, or has not run at all when the test ends, fails the test, unless the wait overlapped a call of the
 * fixture (the fixture drives the technology the way the application does, which may legitimately occupy that
 * thread; the kit judges the adapter). Before a fixture call is excused, what was posted to the guarded thread
 * before it must have run: a block that started outside the fixture's calls (an adapter's listener still busy with
 * an event the kit triggered) is judged, not forgiven by the next fixture call. Not a sleep: the markers run on a
 * scheduled executor of their own.
 *
 * <p>Thread-safe.
 */
public final class EdtWatchdog {

    /** How often a marker is posted. */
    public static final long PERIOD_MS = 50;
    /** How late a marker may run. */
    public static final long LIMIT_MS = 500;

    /** Posts to the guarded thread. */
    public interface Poster {
        void post(Runnable task);
    }

    private final Poster poster;
    private final List<long[]> markers = new ArrayList<long[]>();
    private final List<long[]> excused = new ArrayList<long[]>();
    private ScheduledExecutorService timer;

    public EdtWatchdog(Poster poster) {
        this.poster = poster;
    }

    /** Starts posting markers. */
    public synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "kit-edt-watchdog");
                t.setDaemon(true);
                return t;
            }
        });
        timer.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                final long[] marker = {System.nanoTime(), 0};
                synchronized (EdtWatchdog.this) {
                    markers.add(marker);
                }
                poster.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (EdtWatchdog.this) {
                            marker[1] = System.nanoTime();
                        }
                    }
                });
            }
        }, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs a fixture call; markers whose wait overlaps it are not judged. First lets the guarded thread run what was
     * posted to it before (waiting a little more than {@value #LIMIT_MS} ms): when that does not happen in time, the
     * thread is blocked outside the fixture's call, which is recorded as a late marker that ends before the call's
     * window opens, and so is judged.
     */
    public <T> T excused(Call<T> call) throws Exception {
        final CountDownLatch drained = new CountDownLatch(1);
        final long posted = System.nanoTime();
        poster.post(new Runnable() {
            @Override
            public void run() {
                drained.countDown();
            }
        });
        if (!drained.await(LIMIT_MS + PERIOD_MS, TimeUnit.MILLISECONDS)) {
            synchronized (this) {
                markers.add(new long[] {posted, System.nanoTime()});
            }
        }
        long[] window = {System.nanoTime(), Long.MAX_VALUE};
        synchronized (this) {
            excused.add(window);
        }
        try {
            return call.call();
        } finally {
            synchronized (this) {
                window[1] = System.nanoTime();
            }
        }
    }

    /** A fixture call. */
    public interface Call<T> {
        T call() throws Exception;
    }

    /**
     * Stops posting, lets the markers already posted run (waiting at most {@value #LIMIT_MS} ms more), and throws an
     * {@code AssertionError} naming the worst lateness when a judged marker was late or never ran.
     */
    public void stopAndCheck() throws InterruptedException {
        ScheduledExecutorService t;
        synchronized (this) {
            t = timer;
            timer = null;
        }
        if (t == null) {
            return;
        }
        t.shutdownNow();
        t.awaitTermination(LIMIT_MS, TimeUnit.MILLISECONDS);
        final CountDownLatch drained = new CountDownLatch(1);
        poster.post(new Runnable() {
            @Override
            public void run() {
                drained.countDown();
            }
        });
        drained.await(LIMIT_MS + 1000, TimeUnit.MILLISECONDS);
        long now = System.nanoTime();
        long worst = 0;
        int late = 0;
        synchronized (this) {
            for (long[] m : markers) {
                long ran = m[1] == 0 ? now : m[1];
                long lateness = TimeUnit.NANOSECONDS.toMillis(ran - m[0]);
                if (lateness > LIMIT_MS && !overlapsExcused(m[0], ran)) {
                    late++;
                    worst = Math.max(worst, lateness);
                }
            }
        }
        if (late > 0) {
            throw new AssertionError("the guarded thread was blocked: " + late + " marker(s) ran more than " + LIMIT_MS
                    + " ms late (worst " + worst + " ms) outside the fixture's own calls");
        }
    }

    private boolean overlapsExcused(long from, long to) {
        for (long[] w : excused) {
            if (from <= w[1] && to >= w[0]) {
                return true;
            }
        }
        return false;
    }
}
