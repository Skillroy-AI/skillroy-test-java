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

import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LoanLineRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverRuleRef;
import ai.skillroy.testjava.scenario.resolve.Oracle;

/**
 * Oracle {@code LoanFromTrigger} (phase3-contracts.md 10.3): the loan line that triggers exactly one waiver of a
 * borrow-N-waive-M rule with one item, {@code borrowQty + waivedQty} units of it at the item's deposit. A scenario
 * enters the quantity and scans the item from this line, and the waiver oracles read it.
 *
 * <p>Pure and stateless: no I/O, no clock, the same answer on any JVM.
 */
public final class LoanFromTrigger implements Oracle<LoanFromTrigger.Input, LoanLineRef> {

    /** The function name scenarios write. */
    public static final String FN = "LoanFromTrigger";

    /**
     * The oracle's input.
     *
     * @param rule the waiver rule ({@code $rule})
     * @param item the item to lend, one the rule covers ({@code $item})
     */
    public record Input(WaiverRuleRef rule, ItemRef item) {
    }

    @Override
    public String fn() {
        return FN;
    }

    /**
     * @throws IllegalArgumentException if the rule or the item is missing, or the rule waives no unit (a
     *     {@code waivedQty} below 1 or a negative {@code borrowQty}): a pack defect, reported as ENVIRONMENT
     */
    @Override
    public LoanLineRef compute(Input input) {
        if (input == null || input.rule() == null || input.item() == null || input.item().deposit() == null) {
            throw new IllegalArgumentException("LoanFromTrigger needs a rule and an item with a deposit");
        }
        WaiverRuleRef rule = input.rule();
        if (rule.waivedQty() < 1 || rule.borrowQty() < 0) {
            throw new IllegalArgumentException("LoanFromTrigger: rule " + rule.code() + " is " + rule.borrowQty()
                    + " + " + rule.waivedQty() + ", which waives no unit");
        }
        return new LoanLineRef(input.item().code(), (long) rule.borrowQty() + rule.waivedQty(),
                LendingResolvers.money(input.item().deposit()));
    }
}
