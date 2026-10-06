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

import ai.skillroy.testjava.samples.lending.pack.ExpectedWaiver;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LoanLineRef;
import ai.skillroy.testjava.scenario.resolve.Oracle;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A deliberately wrong {@code ExpectedWaiver} (phase3-contracts.md 10.4, decision D46): {@code borrowQty} times the
 * unit deposit of the first line, instead of the deposits of the waived units. For one group of the trigger item it
 * expects {@code 1.98} where the desk waives {@code 0.99} (seed A), {@code 1.58} where it waives {@code 0.79} (seed B).
 *
 * <p>Test code only, never committed under {@code scenarios/}: a test overlays the lending pack's oracle with it
 * ({@link OverlayPack#wrongOracle()}) to show that the cross-check reports a wrong oracle as ORACLE_SUSPECT. It has
 * the real oracle's function name and types, so the catalogue still builds with the pack's cross-check. Pure.
 */
final class WrongExpectedWaiver implements Oracle<ExpectedWaiver.Input, ExpectedWaiver.Waiver> {

    @Override
    public String fn() {
        return ExpectedWaiver.FN;
    }

    @Override
    public ExpectedWaiver.Waiver compute(ExpectedWaiver.Input input) {
        LoanLineRef first = input.lines().get(0);
        int borrow = input.rule().borrowQty();
        return new ExpectedWaiver.Waiver(input.rule().code(), first.unitDeposit().multiply(BigDecimal.valueOf(borrow))
                .setScale(2, RoundingMode.HALF_UP), borrow);
    }
}
