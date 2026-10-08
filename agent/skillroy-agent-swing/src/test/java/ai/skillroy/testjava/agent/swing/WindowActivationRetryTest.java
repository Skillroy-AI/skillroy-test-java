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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A39 without a display: while the COMPONENT rung waits for the component's window to become the focused
 * window it re-issues the activation once every {@link Clicks#REACTIVATE_MS}, within the focus budget and never
 * once the budget is spent, and stops as soon as the window is focused. The wait and the clock are fakes: the fake
 * wait advances the clock by the time it was asked to wait and reports the focus at a chosen moment.
 */
class WindowActivationRetryTest {

    private static final long RETRY_MS = Clicks.REACTIVATE_MS;

    /** A clock the fake wait advances. */
    private static final class FakeClock implements Clicks.Clock {
        long now;

        @Override
        public long millis() {
            return now;
        }
    }

    /** Advances the clock by each wait's slice; the focus arrives at {@code focusAt} (never, when negative). */
    private static final class FakeWait implements Clicks.Waiter {
        final FakeClock clock;
        final long focusAt;
        final List<Long> slices = new ArrayList<Long>();

        FakeWait(FakeClock clock, long focusAt) {
            this.clock = clock;
            this.focusAt = focusAt;
        }

        @Override
        public boolean await(long ms) {
            slices.add(ms);
            long until = clock.now + ms;
            if (focusAt >= 0 && focusAt <= until) {
                clock.now = focusAt;
                return true;
            }
            clock.now = until;
            return false;
        }
    }

    @Test
    void reissuesOncePerIntervalUntilTheBudgetIsSpent() throws Exception {
        FakeClock clock = new FakeClock();
        FakeWait wait = new FakeWait(clock, -1);
        AtomicInteger reissued = new AtomicInteger();
        assertFalse(Clicks.awaitActivation(wait, 5 * RETRY_MS, RETRY_MS, clock, reissued::incrementAndGet));
        assertEquals(4, reissued.get(), "re-issued after each of the first four intervals, not once the budget is spent");
        assertEquals(Arrays.asList(RETRY_MS, RETRY_MS, RETRY_MS, RETRY_MS, RETRY_MS), wait.slices);
        assertEquals(5 * RETRY_MS, clock.now, "the whole budget was waited for, no more");
    }

    @Test
    void stopsAsSoonAsTheWindowIsFocused() throws Exception {
        FakeClock clock = new FakeClock();
        FakeWait wait = new FakeWait(clock, 2 * RETRY_MS + RETRY_MS / 2);
        AtomicInteger reissued = new AtomicInteger();
        assertTrue(Clicks.awaitActivation(wait, 10 * RETRY_MS, RETRY_MS, clock, reissued::incrementAndGet));
        assertEquals(2, reissued.get(), "re-issued twice before the focus arrived in the third interval");
        assertEquals(3, wait.slices.size());
        assertEquals(2 * RETRY_MS + RETRY_MS / 2, clock.now, "returned when the focus arrived");
    }

    @Test
    void aFocusThatIsAlreadyThereIsNeverReissued() throws Exception {
        FakeClock clock = new FakeClock();
        FakeWait wait = new FakeWait(clock, 0);
        AtomicInteger reissued = new AtomicInteger();
        assertTrue(Clicks.awaitActivation(wait, 10 * RETRY_MS, RETRY_MS, clock, reissued::incrementAndGet));
        assertEquals(0, reissued.get());
    }

    @Test
    void aBudgetShorterThanTheIntervalWaitsOnceAndNeverReissues() throws Exception {
        FakeClock clock = new FakeClock();
        FakeWait wait = new FakeWait(clock, -1);
        AtomicInteger reissued = new AtomicInteger();
        assertFalse(Clicks.awaitActivation(wait, RETRY_MS / 2, RETRY_MS, clock, reissued::incrementAndGet));
        assertEquals(0, reissued.get(), "the default focusTimeoutMs of 500 ms re-issues nothing");
        assertEquals(Arrays.asList(RETRY_MS / 2), wait.slices);
    }

    @Test
    void theLastSliceIsCutToTheBudget() throws Exception {
        FakeClock clock = new FakeClock();
        FakeWait wait = new FakeWait(clock, -1);
        AtomicInteger reissued = new AtomicInteger();
        assertFalse(Clicks.awaitActivation(wait, 2 * RETRY_MS + RETRY_MS / 2, RETRY_MS, clock,
                reissued::incrementAndGet));
        assertEquals(2, reissued.get());
        assertEquals(Arrays.asList(RETRY_MS, RETRY_MS, RETRY_MS / 2), wait.slices);
    }

    @Test
    void aSpentBudgetWaitsForNothing() throws Exception {
        FakeClock clock = new FakeClock();
        FakeWait wait = new FakeWait(clock, -1);
        AtomicInteger reissued = new AtomicInteger();
        assertFalse(Clicks.awaitActivation(wait, 0, RETRY_MS, clock, reissued::incrementAndGet));
        assertEquals(0, reissued.get());
        assertEquals(Arrays.asList(0L), wait.slices);
    }

    @Test
    void theRungsWindowWaitIsTheWaiterInProduction() throws Exception {
        Clicks.WindowWait wait = new Clicks.WindowWait();
        assertFalse(wait.await(1));
        wait.arrived();
        assertTrue(wait.await(1));
        assertTrue(wait.hasArrived());
        assertTrue(Clicks.SYSTEM_CLOCK.millis() > 0);
    }
}
