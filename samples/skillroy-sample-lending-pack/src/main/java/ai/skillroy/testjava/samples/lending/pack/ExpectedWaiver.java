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

import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LoanLineRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverRuleRef;
import ai.skillroy.testjava.scenario.resolve.Oracle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Oracle {@code ExpectedWaiver} (phase3-contracts.md 10.1 step 2, 10.3): what the desk waives under one
 * borrow-N-waive-M rule for these loan lines, assuming the rule is effective on the desk's date. It mirrors the
 * desk's rule exactly:
 *
 * <ol>
 *   <li>the <em>units</em> are the lines whose item is one of {@code rule.items} and not lent per day (the desk
 *       ignores per-day lines), one unit per item of quantity;</li>
 *   <li>with {@code n} units and {@code group = borrowQty + waivedQty}, {@code (n / group) * waivedQty} units are
 *       waived: the units sorted by unit deposit (at scale 2, as the desk holds it), then item code, then the line's
 *       position, and the first ones taken;</li>
 *   <li>the waiver is the sum of their unit deposits, at scale 2; {@code waivedUnits} is how many were taken.</li>
 * </ol>
 *
 * <p>A rule that waives nothing (too few units, a {@code waivedQty} below 1, a negative {@code borrowQty}, or a kind
 * other than {@code BORROW_N_WAIVE_M}, which the desk does not evaluate) gives {@code {ruleCode, "0.00", 0}}.
 * Membership is by the codes of {@code rule.items}, which holds the items the desk can lend; a loan cannot hold any
 * other item the rule covers. The units are counted per line rather than one by one, so a large quantity costs
 * nothing.
 *
 * <p>Pure and stateless: no I/O, no clock, the same answer on any JVM. Its scenario cross-checks it against the desk's
 * own dry run ({@code lending.waiver.evaluate}).
 */
public final class ExpectedWaiver implements Oracle<ExpectedWaiver.Input, ExpectedWaiver.Waiver> {

    /** The function name scenarios write. */
    public static final String FN = "ExpectedWaiver";

    private static final BigDecimal NOTHING = LendingResolvers.money(BigDecimal.ZERO);

    /**
     * The oracle's input.
     *
     * @param rule the waiver rule ({@code $rule})
     * @param lines the planned loan lines, in the order they are entered
     */
    public record Input(WaiverRuleRef rule, List<LoanLineRef> lines) {
    }

    /**
     * What the desk waives under the rule.
     *
     * @param ruleCode the rule's code
     * @param amount the waiver at scale 2; {@code 0.00} when the rule waives nothing
     * @param waivedUnits how many units have their deposit waived
     */
    public record Waiver(String ruleCode, BigDecimal amount, long waivedUnits) {
    }

    @Override
    public String fn() {
        return FN;
    }

    /**
     * @throws IllegalArgumentException if the rule or the lines are missing, or a line has no code or no deposit (a
     *     pack defect, reported as ENVIRONMENT)
     * @throws ArithmeticException if the units overflow a long (a pack defect)
     */
    @Override
    public Waiver compute(Input input) {
        if (input == null || input.rule() == null || input.lines() == null) {
            throw new IllegalArgumentException("ExpectedWaiver needs a rule and the loan lines");
        }
        WaiverRuleRef rule = input.rule();
        List<Lot> lots = units(rule, input.lines());
        Waiver nothing = new Waiver(rule.code(), NOTHING, 0);
        if (!LendingQueries.BORROW_N_WAIVE_M.equals(rule.kind()) || rule.waivedQty() < 1 || rule.borrowQty() < 0) {
            return nothing;
        }
        long units = 0;
        for (Lot lot : lots) {
            units = Math.addExact(units, lot.qty());
        }
        long group = (long) rule.borrowQty() + rule.waivedQty();
        long waived = Math.multiplyExact(units / group, (long) rule.waivedQty());
        if (waived == 0) {
            return nothing;
        }
        BigDecimal sum = BigDecimal.ZERO;
        long left = waived;
        for (Lot lot : lots) {
            long take = Math.min(left, lot.qty());
            sum = sum.add(lot.deposit().multiply(BigDecimal.valueOf(take)));
            left -= take;
            if (left == 0) {
                break;
            }
        }
        return new Waiver(rule.code(), LendingResolvers.money(sum), waived);
    }

    /**
     * The units the rule counts, one entry per line, in the order the desk takes them for the waiver: by unit deposit
     * (at scale 2), then item code, then the line's position. A line of an item that {@code rule.items} does not hold,
     * or holds as lent per day, and a line without a positive quantity, count no unit.
     *
     * @throws IllegalArgumentException if a line has no code or no deposit
     */
    static List<Lot> units(WaiverRuleRef rule, List<LoanLineRef> lines) {
        Set<String> counted = new HashSet<>();
        if (rule.items() != null) {
            for (WaiverItem item : rule.items()) {
                if (!item.perDay()) {
                    counted.add(item.code());
                }
            }
        }
        List<Lot> lots = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LoanLineRef line = lines.get(i);
            if (line == null || line.code() == null || line.unitDeposit() == null) {
                throw new IllegalArgumentException("ExpectedWaiver: line " + (i + 1) + " needs a code and a deposit");
            }
            if (counted.contains(line.code()) && line.qty() > 0) {
                lots.add(new Lot(LendingResolvers.money(line.unitDeposit()), line.code(), i, line.qty()));
            }
        }
        lots.sort(Comparator.comparing(Lot::deposit).thenComparing(Lot::code).thenComparingInt(Lot::position));
        return lots;
    }

    /**
     * The units of one line.
     *
     * @param deposit the unit deposit at scale 2
     * @param code the item code
     * @param position the line's position in the input, from 0
     * @param qty how many units
     */
    record Lot(BigDecimal deposit, String code, int position, long qty) {
    }
}
