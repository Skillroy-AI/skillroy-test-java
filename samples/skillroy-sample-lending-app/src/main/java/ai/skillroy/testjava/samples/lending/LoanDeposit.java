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
 * A deposit taken for a loan ({@code loan_deposit}). For a loan it is the amount the borrower handed over; for a
 * return it is the (negative) deposit handed back. Immutable.
 */
public final class LoanDeposit {

    private final BigDecimal amount;

    public LoanDeposit(BigDecimal amount) {
        this.amount = Deposits.money(Objects.requireNonNull(amount, "amount"));
    }

    /** @return scale-2 amount, negative for a deposit handed back on a return */
    public BigDecimal amount() {
        return amount;
    }

    @Override
    public String toString() {
        return "DEPOSIT " + Deposits.format(amount);
    }
}
