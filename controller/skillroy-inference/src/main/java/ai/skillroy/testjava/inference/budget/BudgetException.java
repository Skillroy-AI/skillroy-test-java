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

package ai.skillroy.testjava.inference.budget;

import ai.skillroy.testjava.scenario.ResolutionException;

import java.math.BigDecimal;

/**
 * A {@link BudgetMeter} had no room for a model call's estimate (phase5-contracts.md 7.2, decision D74): for a slot it
 * is {@code RESOLUTION_FAILED} reason {@code BUDGET} ({@link #toResolutionException()}), in a session it ends the
 * turn, in the nightly job it stops further batches. The amounts are US dollars.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class BudgetException extends Exception {

    private static final long serialVersionUID = 1L;

    private final BudgetMeter.Scope scope;
    private final String id;
    private final BigDecimal limitUsd;
    private final BigDecimal spentUsd;
    private final BigDecimal estimateUsd;

    /**
     * @param scope the meter's scope
     * @param id the meter's id (the scenario, the job, the session), or null
     * @param limitUsd the limit
     * @param spentUsd what was spent (and reserved) before the call
     * @param estimateUsd what the refused call was estimated to cost
     */
    public BudgetException(BudgetMeter.Scope scope, String id, BigDecimal limitUsd, BigDecimal spentUsd,
                           BigDecimal estimateUsd) {
        super("the " + scope.name().toLowerCase(java.util.Locale.ROOT) + " budget of $" + plain(limitUsd) + " has $"
                + plain(spentUsd) + " spent and no room for a model call estimated at $" + plain(estimateUsd));
        this.scope = scope;
        this.id = id;
        this.limitUsd = limitUsd;
        this.spentUsd = spentUsd;
        this.estimateUsd = estimateUsd;
    }

    public BudgetMeter.Scope scope() {
        return scope;
    }

    public String id() {
        return id;
    }

    public BigDecimal limitUsd() {
        return limitUsd;
    }

    public BigDecimal spentUsd() {
        return spentUsd;
    }

    public BigDecimal estimateUsd() {
        return estimateUsd;
    }

    /** The slot's failure: {@code RESOLUTION_FAILED} reason {@code BUDGET} with {@code scope, id, limitUsd, spentUsd, estimateUsd}. */
    public ResolutionException toResolutionException() {
        return ResolutionException.budget(scope.name(), id, plain(limitUsd), plain(spentUsd), plain(estimateUsd));
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "null" : amount.toPlainString();
    }
}
