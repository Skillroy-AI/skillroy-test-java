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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A loan: the items lent in one visit to the desk, open while items are entered and recorded once the deposit is
 * received. Immutable: the engine replaces its current loan with a changed copy after every change, so a loan obtained
 * from {@link Engine#currentLoan()} or {@link Engine#lastLoan()} is a consistent snapshot on any thread.
 *
 * <p>{@code total = subtotal - waiverTotal}, where the subtotal is the sum of the signed line amounts (negative
 * for returns) and waivers are positive. All money has scale 2. The id is null until the loan is recorded.
 */
public final class Loan {

    private final Long id;
    private final String staffCode;
    private final boolean returning;
    private final List<LoanLine> lines;
    private final List<LoanWaiver> waivers;
    private final List<LoanDeposit> deposits;
    private final List<LoanAuthorisation> authorisations;
    private final BigDecimal change;
    private final LocalDateTime postedAt;

    private Loan(Long id, String staffCode, boolean returning, List<LoanLine> lines, List<LoanWaiver> waivers,
                 List<LoanDeposit> deposits, List<LoanAuthorisation> authorisations, BigDecimal change,
                 LocalDateTime postedAt) {
        this.id = id;
        this.staffCode = Objects.requireNonNull(staffCode, "staffCode");
        this.returning = returning;
        this.lines = freeze(lines);
        this.waivers = freeze(waivers);
        this.deposits = freeze(deposits);
        this.authorisations = freeze(authorisations);
        this.change = change == null ? null : Deposits.money(change);
        this.postedAt = postedAt;
    }

    private static <T> List<T> freeze(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<T>(list));
    }

    /** @return a new, empty open loan */
    public static Loan open(String staffCode, boolean returning) {
        List<LoanLine> noLines = Collections.emptyList();
        List<LoanWaiver> noWaivers = Collections.emptyList();
        List<LoanDeposit> noDeposits = Collections.emptyList();
        List<LoanAuthorisation> noAuths = Collections.emptyList();
        return new Loan(null, staffCode, returning, noLines, noWaivers, noDeposits, noAuths, null, null);
    }

    Loan withLines(List<LoanLine> newLines) {
        return new Loan(id, staffCode, returning, newLines, waivers, deposits, authorisations, change, postedAt);
    }

    Loan withWaivers(List<LoanWaiver> newWaivers) {
        return new Loan(id, staffCode, returning, lines, newWaivers, deposits, authorisations, change, postedAt);
    }

    Loan withAuthorisation(LoanAuthorisation authorisation) {
        List<LoanAuthorisation> list = new ArrayList<LoanAuthorisation>(authorisations);
        list.add(authorisation);
        return new Loan(id, staffCode, returning, lines, waivers, deposits, list, change, postedAt);
    }

    Loan withDeposit(LoanDeposit deposit, BigDecimal newChange) {
        List<LoanDeposit> list = new ArrayList<LoanDeposit>(deposits);
        list.add(deposit);
        return new Loan(id, staffCode, returning, lines, waivers, list, authorisations, newChange, postedAt);
    }

    Loan posted(long newId, LocalDateTime at) {
        return new Loan(newId, staffCode, returning, lines, waivers, deposits, authorisations, change, at);
    }

    /** @return the loan id ({@code loan.id}), or null while the loan is open */
    public Long id() {
        return id;
    }

    /** @return the signed-on staff member who opened the loan */
    public String staffCode() {
        return staffCode;
    }

    /** @return true for a return (every line is a return line: the items come back and their deposits go back) */
    public boolean returning() {
        return returning;
    }

    /** @return the lines in line-number order, unmodifiable */
    public List<LoanLine> lines() {
        return lines;
    }

    /** @return the waivers in rule-id order, unmodifiable; empty until DUE */
    public List<LoanWaiver> waivers() {
        return waivers;
    }

    /** @return the deposits, unmodifiable; empty until the deposit is received */
    public List<LoanDeposit> deposits() {
        return deposits;
    }

    /** @return the supervisor authorisations, unmodifiable */
    public List<LoanAuthorisation> authorisations() {
        return authorisations;
    }

    /** @return the change given, or null until the deposit is received (0.00 for a return) */
    public BigDecimal change() {
        return change;
    }

    /** @return when the loan was recorded (engine clock), or null while open */
    public LocalDateTime postedAt() {
        return postedAt;
    }

    /** @return true once the loan was written to the database */
    public boolean posted() {
        return id != null;
    }

    /** @return the sum of the signed line amounts */
    public BigDecimal subtotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (LoanLine line : lines) {
            sum = sum.add(line.amount());
        }
        return Deposits.money(sum);
    }

    /** @return the sum of the (positive) waivers */
    public BigDecimal waiverTotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (LoanWaiver w : waivers) {
            sum = sum.add(w.amount());
        }
        return Deposits.money(sum);
    }

    /** @return {@code subtotal - waiverTotal}; negative for a return */
    public BigDecimal total() {
        return Deposits.money(subtotal().subtract(waiverTotal()));
    }

    /** @return the absolute value of the return lines' amounts (what return limits are checked against) */
    public BigDecimal returnValue() {
        BigDecimal sum = BigDecimal.ZERO;
        for (LoanLine line : lines) {
            if (line.returned()) {
                sum = sum.add(line.amount());
            }
        }
        return Deposits.money(sum.abs());
    }

    @Override
    public String toString() {
        return "Loan{id=" + id + ", staff=" + staffCode + ", returning=" + returning + ", lines=" + lines
            + ", waivers=" + waivers + ", deposits=" + deposits + ", authorisations=" + authorisations
            + ", total=" + Deposits.format(total()) + "}";
    }
}
