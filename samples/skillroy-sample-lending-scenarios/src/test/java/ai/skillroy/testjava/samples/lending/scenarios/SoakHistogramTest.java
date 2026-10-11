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

package ai.skillroy.testjava.samples.lending.scenarios;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The histogram of the listener soak reports percentiles within its stated error and the exact maximum. */
class SoakHistogramTest {

    @Test
    void smallValuesAreExactAndTheMaximumIsExact() {
        SoakHistogram h = new SoakHistogram();
        for (int v = 1; v <= 100; v++) {
            h.record(v);
        }
        assertEquals(100, h.count());
        assertEquals(50, h.percentileMicros(50));
        assertEquals(95, h.percentileMicros(95));
        assertEquals(99, h.percentileMicros(99));
        assertEquals(100, h.percentileMicros(100));
        assertEquals(100, h.maxMicros());
    }

    @Test
    void everyBucketHoldsItsValuesWithinOneSixtyFourth() {
        Random random = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            long v = (long) Math.exp(random.nextDouble() * Math.log(5_000_000_000.0));
            long upper = SoakHistogram.upperOf(SoakHistogram.bucketOf(v));
            assertTrue(upper >= v, v + " is above the end " + upper + " of its bucket");
            assertTrue(upper <= v + v / 64 + 1, v + " is more than 1/64 below the end " + upper + " of its bucket");
        }
    }

    @Test
    void percentilesOfAMillionRandomValuesAreWithinTheErrorOfTheSortedOnes() {
        Random random = new Random(11);
        long[] values = new long[1_000_000];
        SoakHistogram h = new SoakHistogram();
        for (int i = 0; i < values.length; i++) {
            values[i] = 300 + (long) (-Math.log(1.0 - random.nextDouble()) * 2_000.0);
            h.record(values[i]);
        }
        Arrays.sort(values);
        for (double percent : new double[] {50, 95, 99, 99.9}) {
            long exact = values[(int) Math.ceil(percent / 100.0 * values.length) - 1];
            long reported = h.percentileMicros(percent);
            assertTrue(reported >= exact && reported <= exact + exact / 64 + 1,
                    "p" + percent + ": exact " + exact + ", reported " + reported);
        }
        assertEquals(values[values.length - 1], h.maxMicros());
    }

    @Test
    void mergedHistogramsEqualOneThatSawEverything() {
        SoakHistogram a = new SoakHistogram();
        SoakHistogram b = new SoakHistogram();
        SoakHistogram all = new SoakHistogram();
        for (int i = 1; i <= 10_000; i++) {
            ((i % 2 == 0) ? a : b).record(i * 7L);
            all.record(i * 7L);
        }
        SoakHistogram merged = new SoakHistogram();
        merged.add(a);
        merged.add(b);
        assertEquals(all.summary(), merged.summary());
    }

    @Test
    void anEmptyHistogramReportsZeros() {
        SoakHistogram h = new SoakHistogram();
        assertEquals(0, h.percentileMicros(99));
        assertEquals(0, h.maxMicros());
        assertEquals(0L, h.summary().get("count"));
    }
}
