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

import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The receive budget of security fix P2-L4-S1 ({@link EventBudget}): the hand-off admits events only within
 * {@link EventBudget#QUEUE_CHARS} (each counting at least {@link EventBudget#MIN_EVENT_COST}); the console bucket
 * passes a burst, refills at its rate, stops capture once with the event that empties it and drops the rest; and
 * capture may resume {@link EventBudget#RESUME_AFTER_MS} ms after it stopped, with a full bucket.
 */
class EventBudgetTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void theHandOffIsBoundedByCharactersAndByCount() {
        EventBudget b = new EventBudget();
        assertEquals(2L * WebLib.MAX_CHANNEL_CHARS, EventBudget.QUEUE_CHARS);
        assertTrue(b.admit(WebLib.MAX_CHANNEL_CHARS));
        assertTrue(b.admit(WebLib.MAX_CHANNEL_CHARS));
        assertFalse(b.admit(EventBudget.cost(1)), "past the bound: dropped");
        assertEquals(1, b.dropped());
        b.release(WebLib.MAX_CHANNEL_CHARS);
        assertTrue(b.admit(EventBudget.cost(10)), "room again once an event ran");
        assertEquals(EventBudget.MIN_EVENT_COST, EventBudget.cost(10), "a small event counts the minimum");
        EventBudget count = new EventBudget();
        long admitted = 0;
        while (count.admit(EventBudget.cost(2))) {
            admitted++;
        }
        assertEquals(EventBudget.QUEUE_CHARS / EventBudget.MIN_EVENT_COST, admitted, "bounded in length too");
    }

    @Test
    void theConsoleBucketPassesABurstStopsOnceAndDropsTheRest() {
        EventBudget b = new EventBudget();
        long t = 1000 * SECOND;
        long chunk = 1 << 20;
        int passed = 0;
        EventBudget.Verdict v;
        while ((v = b.spend(chunk, t)) == EventBudget.Verdict.PASS) {
            passed++;
        }
        assertEquals(EventBudget.Verdict.STOP, v, "the event that empties the bucket stops capture");
        assertEquals(EventBudget.CONSOLE_BURST_CHARS / chunk, passed);
        assertTrue(b.stopped());
        assertEquals(EventBudget.Verdict.DROP, b.spend(1, t + SECOND), "once stopped, every event is dropped");
        assertEquals(EventBudget.Verdict.DROP, b.spend(1, t + 100 * SECOND));
    }

    @Test
    void theBucketRefillsAtItsRate() {
        EventBudget b = new EventBudget();
        long t = 5 * SECOND;
        assertEquals(EventBudget.Verdict.PASS, b.spend(EventBudget.CONSOLE_BURST_CHARS, t), "the whole burst");
        assertEquals(EventBudget.Verdict.PASS, b.spend(EventBudget.CONSOLE_CHARS_PER_SECOND / 2, t + SECOND),
                "half a second's worth after a second");
        assertEquals(EventBudget.Verdict.PASS, b.spend(EventBudget.CONSOLE_CHARS_PER_SECOND / 2, t + SECOND));
        assertEquals(EventBudget.Verdict.STOP, b.spend(1024, t + SECOND), "a page over the rate stops capture");
        EventBudget steady = new EventBudget();
        for (int i = 0; i < 600; i++) {
            assertEquals(EventBudget.Verdict.PASS, steady.spend(EventBudget.CONSOLE_CHARS_PER_SECOND / 10, t + i
                    * SECOND / 10), "a page within the rate never stops: " + i);
        }
    }

    @Test
    void captureMayResumeAfterItsDelayWithAFullBucket() {
        EventBudget b = new EventBudget();
        long t = 7 * SECOND;
        assertEquals(-1, b.resumeInMs(t), "not stopped");
        assertEquals(EventBudget.Verdict.STOP, b.spend(EventBudget.CONSOLE_BURST_CHARS + 1, t));
        assertEquals(EventBudget.RESUME_AFTER_MS, b.resumeInMs(t));
        assertEquals(EventBudget.RESUME_AFTER_MS - 4000, b.resumeInMs(t + 4 * SECOND));
        assertEquals(0, b.resumeInMs(t + 11 * SECOND));
        b.resume(t + 11 * SECOND);
        assertFalse(b.stopped());
        assertEquals(EventBudget.Verdict.PASS, b.spend(EventBudget.CONSOLE_BURST_CHARS, t + 11 * SECOND),
                "a full bucket");
        assertTrue(EventBudget.STOPPED_TEXT.startsWith("skillroy: console capture of this browser stopped"));
    }
}
