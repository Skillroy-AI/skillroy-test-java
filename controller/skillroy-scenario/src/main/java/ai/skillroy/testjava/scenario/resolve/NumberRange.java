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

package ai.skillroy.testjava.scenario.resolve;

import java.math.BigDecimal;
import java.util.List;

/**
 * A numeric condition usable as a query argument: {@code {gt, gte, lt, lte, between: [lo, hi]}}. Every key that is
 * present must hold (a conjunction); {@code between} is inclusive. This is how a scenario states a margin or a range
 * ({@code deposit: {between: ["20.00", "200.00"], lte: $systemMax}}) without an expression language
 * (phase2-contracts.md 3.2).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param gt strictly greater than, or null
 * @param gte greater than or equal, or null
 * @param lt strictly less than, or null
 * @param lte less than or equal, or null
 * @param between {@code [lo, hi]}, both inclusive, or null
 */
public record NumberRange(BigDecimal gt, BigDecimal gte, BigDecimal lt, BigDecimal lte, List<BigDecimal> between) {

    /**
     * @throws IllegalArgumentException if {@code between} does not hold exactly two non-null values
     */
    public NumberRange {
        if (between != null) {
            if (between.size() != 2 || between.get(0) == null || between.get(1) == null) {
                throw new IllegalArgumentException("'between' needs exactly two values [low, high]");
            }
            between = List.copyOf(between);
        }
    }

    /** True if {@code value} satisfies every bound that is present; a null value never does. */
    public boolean test(BigDecimal value) {
        if (value == null) {
            return false;
        }
        return (gt == null || value.compareTo(gt) > 0)
                && (gte == null || value.compareTo(gte) >= 0)
                && (lt == null || value.compareTo(lt) < 0)
                && (lte == null || value.compareTo(lte) <= 0)
                && (between == null || value.compareTo(between.get(0)) >= 0 && value.compareTo(between.get(1)) <= 0);
    }

    /** The tightest lower bound over all keys, null = open below; whether it is strict is {@link #test}'s matter. */
    public BigDecimal lowerBound() {
        return max(max(gt, gte), between == null ? null : between.get(0));
    }

    /** The tightest upper bound over all keys, null = open above; whether it is strict is {@link #test}'s matter. */
    public BigDecimal upperBound() {
        return min(min(lt, lte), between == null ? null : between.get(1));
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.compareTo(b) >= 0 ? a : b;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.compareTo(b) <= 0 ? a : b;
    }
}
