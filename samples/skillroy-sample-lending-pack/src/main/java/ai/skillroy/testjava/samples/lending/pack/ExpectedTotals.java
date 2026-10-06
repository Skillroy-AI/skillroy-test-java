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
import ai.skillroy.testjava.scenario.resolve.Oracle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Oracle {@code ExpectedTotals} (phase3-contracts.md 10.1 step 3, 10.3): the subtotal, the waiver total and the total
 * of a loan, as the desk computes them when {@code DUE} is pressed. The subtotal is the sum of the line amounts (each
 * {@code qty} times the unit deposit, at scale 2), the waiver total the sum of the waivers, and the total the subtotal
 * less the waiver total; all at scale 2, rounded half up.
 *
 * <p>Pure and stateless: no I/O, no clock, the same answer on any JVM.
 */
public final class ExpectedTotals implements Oracle<ExpectedTotals.Input, ExpectedTotals.Totals> {

    /** The function name scenarios write. */
    public static final String FN = "ExpectedTotals";

    /**
     * The oracle's input.
     *
     * @param lines the loan lines
     * @param waivers the loan's waivers (positive amounts); null means none
     */
    public record Input(List<LoanLineRef> lines, List<BigDecimal> waivers) {
    }

    /**
     * A loan's totals, each at scale 2.
     *
     * @param subtotal the sum of the line amounts
     * @param waiverTotal the sum of the waivers
     * @param total {@code subtotal - waiverTotal}: what the desk asks as deposit
     */
    public record Totals(BigDecimal subtotal, BigDecimal waiverTotal, BigDecimal total) {
    }

    @Override
    public String fn() {
        return FN;
    }

    /**
     * @throws IllegalArgumentException if the lines are missing, a line has no deposit or a waiver is null (a pack
     *     defect, reported as ENVIRONMENT)
     */
    @Override
    public Totals compute(Input input) {
        if (input == null || input.lines() == null) {
            throw new IllegalArgumentException("ExpectedTotals needs the loan lines");
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (LoanLineRef line : input.lines()) {
            if (line == null || line.unitDeposit() == null) {
                throw new IllegalArgumentException("ExpectedTotals: every line needs a unit deposit");
            }
            subtotal = subtotal.add(LendingResolvers.money(LendingResolvers.money(line.unitDeposit())
                    .multiply(BigDecimal.valueOf(line.qty()))));
        }
        BigDecimal waiverTotal = BigDecimal.ZERO;
        for (BigDecimal waiver : input.waivers() == null ? List.<BigDecimal>of() : input.waivers()) {
            if (waiver == null) {
                throw new IllegalArgumentException("ExpectedTotals: a waiver is missing");
            }
            waiverTotal = waiverTotal.add(LendingResolvers.money(waiver));
        }
        subtotal = LendingResolvers.money(subtotal);
        waiverTotal = LendingResolvers.money(waiverTotal);
        return new Totals(subtotal, waiverTotal, LendingResolvers.money(subtotal.subtract(waiverTotal)));
    }
}
