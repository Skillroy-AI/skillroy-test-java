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
 * One line of a loan ({@code loan_line}). The amount is signed: negative for a return line. Immutable.
 */
public final class LoanLine {

    private final int lineNo;
    private final String code;
    private final String title;
    private final BigDecimal qty;
    private final BigDecimal unitDeposit;
    private final BigDecimal amount;
    private final boolean returned;
    private final boolean perDay;

    /**
     * @param lineNo 1-based line number within the loan
     * @param code the item code
     * @param title the item's title at the time of the loan
     * @param qty units (whole number) or days for a per-day item; always positive
     * @param unitDeposit deposit per unit or per day
     * @param amount {@code qty * unitDeposit} rounded to money, negated for a return line
     * @param returned true for a return line
     * @param perDay true for a per-day item (excluded from quantity waiver rules)
     */
    public LoanLine(int lineNo, String code, String title, BigDecimal qty, BigDecimal unitDeposit,
                    BigDecimal amount, boolean returned, boolean perDay) {
        this.lineNo = lineNo;
        this.code = Objects.requireNonNull(code, "code");
        this.title = title;
        this.qty = Objects.requireNonNull(qty, "qty");
        this.unitDeposit = Deposits.money(Objects.requireNonNull(unitDeposit, "unitDeposit"));
        this.amount = Deposits.money(Objects.requireNonNull(amount, "amount"));
        this.returned = returned;
        this.perDay = perDay;
    }

    /**
     * Builds a line from an item, computing the signed amount.
     */
    public static LoanLine of(int lineNo, Item item, BigDecimal qty, boolean returned) {
        BigDecimal amount = Deposits.lineAmount(item.unitDeposit(), qty);
        return new LoanLine(lineNo, item.code(), item.title(), qty, item.unitDeposit(),
            returned ? amount.negate() : amount, returned, item.perDay());
    }

    /** @return the same line with another quantity and the amount recomputed */
    LoanLine withQty(BigDecimal newQty) {
        BigDecimal a = Deposits.lineAmount(unitDeposit, newQty);
        return new LoanLine(lineNo, code, title, newQty, unitDeposit, returned ? a.negate() : a, returned, perDay);
    }

    public int lineNo() {
        return lineNo;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    /** @return positive quantity: scale 0 for counted items, up to 3 decimals (part days) for per-day ones */
    public BigDecimal qty() {
        return qty;
    }

    /** @return scale-2 unit deposit */
    public BigDecimal unitDeposit() {
        return unitDeposit;
    }

    /** @return scale-2 signed amount (negative for a return line) */
    public BigDecimal amount() {
        return amount;
    }

    /** @return true for a return line (an item coming back; its deposit is handed back) */
    public boolean returned() {
        return returned;
    }

    /** @return true for a per-day item, whose quantity is a number of days */
    public boolean perDay() {
        return perDay;
    }

    @Override
    public String toString() {
        return lineNo + ":" + code + " x" + Deposits.formatQty(qty) + " " + Deposits.format(amount);
    }
}
