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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.scenario.resolve.Oracle;

import java.math.BigDecimal;

/**
 * Oracle {@code Money.negate}: the amount with its sign turned round and its scale kept ({@code "34.99"} becomes
 * {@code "-34.99"}), the value of a return line for an item's deposit. Why an oracle for something this small: the
 * base has no expression language (decision D6) and ships no arithmetic, because rounding and scale are the domain's
 * business.
 *
 * <p>Pure and stateless.
 */
public final class MoneyNegate implements Oracle<BigDecimal, BigDecimal> {

    /** The function name scenarios write. */
    public static final String FN = "Money.negate";

    @Override
    public String fn() {
        return FN;
    }

    /**
     * @throws IllegalArgumentException if the amount is missing (a pack defect, reported as ENVIRONMENT)
     */
    @Override
    public BigDecimal compute(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Money.negate needs an amount");
        }
        return amount.negate();
    }
}
