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
import java.math.RoundingMode;

/**
 * Oracle {@code ExpectedAuthorisation}: what the desk does when a staff member with this return limit enters a return
 * of this amount (phase2-contracts.md 13.1). With {@code a} = {@code |amount|} at scale 2 (half up), in this order: a
 * system maximum below {@code a} gives {@link Outcome#NOT_ALLOWED}; no staff limit, or {@code a} within it, gives
 * {@link Outcome#NONE}; otherwise {@link Outcome#SUPERVISOR_PROMPT}.
 *
 * <p>Why an oracle: the expected outcome is computed in committed, unit-tested Java rather than in an expression the
 * framework would evaluate (decision D6), and a scenario guards it with {@code must_equal}, so that a binding under
 * which no supervisor is needed is rejected before the desk is touched. It assumes the staff member holds
 * {@code RETURN} (the scenario's query asks for it) and that no earlier authorisation exists in the loan (the scenario
 * returns one line).
 *
 * <p>Pure and stateless: no I/O, no clock, the same answer on any JVM.
 */
public final class ExpectedAuthorisation implements Oracle<ExpectedAuthorisation.Input, ExpectedAuthorisation.Outcome> {

    /** The function name scenarios write. */
    public static final String FN = "ExpectedAuthorisation";

    /**
     * The oracle's input.
     *
     * @param staffLimit the signed-on staff member's return limit; null = unlimited
     * @param systemMax the desk's system maximum return; null = none
     * @param amount the return amount (its absolute value is used)
     */
    public record Input(BigDecimal staffLimit, BigDecimal systemMax, BigDecimal amount) {
    }

    /** What the desk does with the return line. */
    public enum Outcome {
        /** The line is added on the staff member's own authority. */
        NONE,
        /** The desk asks for a supervisor ({@code supervisor-required}). */
        SUPERVISOR_PROMPT,
        /** The desk refuses the return ({@code return-not-allowed}). */
        NOT_ALLOWED
    }

    @Override
    public String fn() {
        return FN;
    }

    /**
     * @throws IllegalArgumentException if the amount is missing (a pack defect, reported as ENVIRONMENT)
     */
    @Override
    public Outcome compute(Input input) {
        if (input == null || input.amount() == null) {
            throw new IllegalArgumentException("ExpectedAuthorisation needs an amount");
        }
        BigDecimal a = input.amount().abs().setScale(2, RoundingMode.HALF_UP);
        if (input.systemMax() != null && a.compareTo(input.systemMax()) > 0) {
            return Outcome.NOT_ALLOWED;
        }
        if (input.staffLimit() == null || a.compareTo(input.staffLimit()) <= 0) {
            return Outcome.NONE;
        }
        return Outcome.SUPERVISOR_PROMPT;
    }
}
