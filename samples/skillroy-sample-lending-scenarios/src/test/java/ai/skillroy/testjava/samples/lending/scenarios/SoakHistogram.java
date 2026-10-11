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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A latency histogram of constant size for the listener soak ({@link ListenerSoakIT}): values in microseconds, kept in
 * log-linear buckets (the first 128 microseconds exactly, then 64 buckets per power of two), so that a percentile is
 * within 1.6 % of the true value however many millions of calls a one-hour run records, and the maximum is exact.
 *
 * <p>One writer thread per histogram ({@link #record} is a read-modify-write on a slot); any thread may read it
 * while it is written (the counts are atomic slots, so a read sees some recent state, never a torn one) and the
 * histograms of the writers are merged with {@link #add}.
 */
final class SoakHistogram {

    /** Values below this are exact; above it a bucket spans 1/64 of its power of two. */
    private static final int EXACT = 128;
    private static final int SUB = 64;
    /** 2^40 microseconds is about 12.7 days. */
    private static final int MAX_EXPONENT = 40;
    private static final int BUCKETS = EXACT + SUB * MAX_EXPONENT;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private volatile long count;
    private volatile long sumMicros;
    private volatile long maxMicros;

    /** Records one value, in microseconds (a negative one counts as zero). */
    void record(long micros) {
        long v = Math.max(0L, micros);
        int bucket = bucketOf(v);
        counts.lazySet(bucket, counts.get(bucket) + 1);
        count = count + 1;
        sumMicros = sumMicros + v;
        if (v > maxMicros) {
            maxMicros = v;
        }
    }

    /** Adds every value of {@code other} to this histogram. */
    void add(SoakHistogram other) {
        for (int i = 0; i < BUCKETS; i++) {
            long n = other.counts.get(i);
            if (n != 0) {
                counts.addAndGet(i, n);
            }
        }
        count += other.count;
        sumMicros += other.sumMicros;
        maxMicros = Math.max(maxMicros, other.maxMicros);
    }

    long count() {
        return count;
    }

    long maxMicros() {
        return maxMicros;
    }

    /**
     * The value, in microseconds, below which {@code percent} of the recorded values lie: the upper end of the bucket
     * that holds the rank, never above the maximum. Zero when nothing was recorded.
     */
    long percentileMicros(double percent) {
        long total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            total += counts.get(i);
        }
        if (total == 0) {
            return 0;
        }
        long rank = Math.max(1L, (long) Math.ceil(percent / 100.0 * total));
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts.get(i);
            if (seen >= rank) {
                return Math.min(upperOf(i), maxMicros);
            }
        }
        return maxMicros;
    }

    /** {count, meanMs, p50Ms, p95Ms, p99Ms, maxMs}, all times in milliseconds. */
    Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("meanMs", count == 0 ? 0.0 : round(sumMicros / 1000.0 / count));
        m.put("p50Ms", round(percentileMicros(50) / 1000.0));
        m.put("p95Ms", round(percentileMicros(95) / 1000.0));
        m.put("p99Ms", round(percentileMicros(99) / 1000.0));
        m.put("maxMs", round(maxMicros / 1000.0));
        return m;
    }

    /** One line: {@code n=… p50=… p95=… p99=… max=… ms}. */
    String line() {
        return String.format(Locale.ROOT, "n=%d p50=%.2f p95=%.2f p99=%.2f max=%.2f ms", count,
                percentileMicros(50) / 1000.0, percentileMicros(95) / 1000.0, percentileMicros(99) / 1000.0,
                maxMicros / 1000.0);
    }

    static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    static int bucketOf(long v) {
        if (v < EXACT) {
            return (int) v;
        }
        int exponent = 63 - Long.numberOfLeadingZeros(v) - 6;
        if (exponent > MAX_EXPONENT) {
            return BUCKETS - 1;
        }
        return exponent * SUB + (int) (v >> exponent);
    }

    /** The largest value that falls in {@code bucket}. */
    static long upperOf(int bucket) {
        if (bucket < EXACT) {
            return bucket;
        }
        int exponent = bucket / SUB - 1;
        long sub = SUB + bucket % SUB;
        return ((sub + 1) << exponent) - 1;
    }
}
