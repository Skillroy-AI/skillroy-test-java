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

package ai.skillroy.testjava.samples.lending;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A waiver rule's benefit on a loan ({@code loan_waiver}). The amount is the positive value taken off the total
 * (the slip prints it negated). Immutable.
 */
public final class LoanWaiver {

    private final String ruleCode;
    private final BigDecimal amount;

    public LoanWaiver(String ruleCode, BigDecimal amount) {
        this.ruleCode = Objects.requireNonNull(ruleCode, "ruleCode");
        this.amount = Deposits.money(Objects.requireNonNull(amount, "amount"));
    }

    public String ruleCode() {
        return ruleCode;
    }

    /** @return the positive scale-2 waiver */
    public BigDecimal amount() {
        return amount;
    }

    @Override
    public String toString() {
        return ruleCode + " " + Deposits.format(amount);
    }
}
