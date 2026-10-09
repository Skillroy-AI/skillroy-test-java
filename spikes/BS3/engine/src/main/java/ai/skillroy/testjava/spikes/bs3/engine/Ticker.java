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

package ai.skillroy.testjava.spikes.bs3.engine;

/**
 * A spinning thread that stands in for a host thread and measures the longest gap between two of its iterations: the
 * stop-the-world pause a retransformation causes, as an application thread experiences it.
 */
final class Ticker implements Runnable {

    private volatile boolean running = true;
    private volatile boolean armed;
    private volatile boolean measuring;
    private volatile long maxGap;
    private final Thread thread;

    Ticker() {
        thread = new Thread(this, "bs3-ticker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        long last = 0;
        while (running) {
            if (!armed) {
                measuring = false;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            long now = System.nanoTime();
            if (!measuring) {
                last = now;
                measuring = true;
                continue;
            }
            long gap = now - last;
            if (gap > maxGap) {
                maxGap = gap;
            }
            last = now;
        }
    }

    /** Starts a measurement once the thread spins (bounded wait). */
    void arm() {
        maxGap = 0;
        armed = true;
        long deadline = System.nanoTime() + 200000000L;
        while (!measuring && System.nanoTime() < deadline) {
            Thread.yield();
        }
    }

    /** @return the longest gap since {@link #arm} in nanoseconds */
    long disarm() {
        armed = false;
        return maxGap;
    }

    void stop() {
        running = false;
    }
}
