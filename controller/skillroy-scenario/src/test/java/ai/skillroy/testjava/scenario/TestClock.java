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

package ai.skillroy.testjava.scenario;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that moves only when told to; its sleeper advances it instead of sleeping. */
final class TestClock extends Clock {

    private long millis = 1_790_000_000_000L;
    int sleeps;

    synchronized void advance(Duration duration) {
        millis += duration.toMillis();
    }

    /** A sleeper that advances this clock. */
    RunOptions.Sleeper sleeper() {
        return duration -> {
            synchronized (this) {
                sleeps++;
            }
            advance(duration);
        };
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public synchronized long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }
}
