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

package ai.skillroy.testjava.samples.lending.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock whose time the test sets, for a desk whose business date must move while it runs: the desk reads its
 * date from its clock ({@code LocalDate.now(clock)}) whenever it needs it. {@link #withZone} returns a detached
 * copy, which the desk never asks for. Thread-safe.
 */
final class TestClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** Moves the clock on by whole days. */
    void advanceDays(long days) {
        instant = instant.plus(Duration.ofDays(days));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new TestClock(instant, other);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
