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

import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Money spent through the ledger within one scope, against a limit (phase5-contracts.md 7.2, decision D74): consulted
 * before every model call with an estimate ({@link #reserve}) and settled after it with the call's real cost
 * ({@link #settle}). Per scenario ({@code budget.scenarioUsd}, default 0.50, one meter per bind, created by the
 * contributor), per job ({@code budget.jobUsd}, no default) and per session ({@code budget.sessionUsd}, default 1.00).
 *
 * <p>A reservation is held until it is settled or {@link #release released}, so that two calls checked at the same
 * time cannot both pass on the same room; one caller at a time (a bind, a session) sees exactly the contract's rule
 * "refused when spent + estimate exceeds the limit". A limit of {@code 0} means no model call at all; a null limit is
 * unlimited (the caller records that it is).
 *
 * <p>Thread-safe.
 */
public final class BudgetMeter {

    /** The scope a meter measures. */
    public enum Scope {
        /** One bind of one scenario. */
        SCENARIO,
        /** One nightly re-resolve job. */
        JOB,
        /** One interactive session. */
        SESSION
    }

    private final Scope scope;
    private final BigDecimal limit;
    private final CostLedger ledger;
    private final Deque<BigDecimal> reservations = new ArrayDeque<>();
    private volatile String id;
    private BigDecimal spent = BigDecimal.ZERO;
    private BigDecimal reserved = BigDecimal.ZERO;

    /**
     * @param scope what the meter measures
     * @param id the scenario, job or session id; may be null and set later ({@link #identify})
     * @param limitUsd the limit; null is unlimited
     * @param ledger the run's ledger the meter's calls are recorded in (by the {@link LedgerWriter}); may be null
     * @throws IllegalArgumentException for a negative limit
     */
    public BudgetMeter(Scope scope, String id, BigDecimal limitUsd, CostLedger ledger) {
        this.scope = java.util.Objects.requireNonNull(scope, "scope");
        if (limitUsd != null && limitUsd.signum() < 0) {
            throw new IllegalArgumentException("a budget is not negative");
        }
        this.id = id;
        this.limit = limitUsd;
        this.ledger = ledger;
    }

    /**
     * A meter of the limit a settings budget text gives ({@code "0.50"}); null or blank is unlimited.
     *
     * @throws IllegalArgumentException if the text is not a plain decimal of at least 0
     */
    public static BudgetMeter of(Scope scope, String id, String limitText, CostLedger ledger) {
        BigDecimal limit = null;
        if (limitText != null && !limitText.isBlank()) {
            try {
                limit = new BigDecimal(limitText.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("a budget is a plain decimal of US dollars");
            }
        }
        return new BudgetMeter(scope, id, limit, ledger);
    }

    public Scope scope() {
        return scope;
    }

    /** The scenario, job or session id, or null when not known yet. */
    public String id() {
        return id;
    }

    /** Sets the id when the meter was created before it was known (a bind's scenario id); a set id is kept. */
    public void identify(String newId) {
        if (id == null && newId != null) {
            id = newId;
        }
    }

    /** The ledger the meter's calls are recorded in, or null. */
    public CostLedger ledger() {
        return ledger;
    }

    /** What the settled calls cost. */
    public synchronized BigDecimal spent() {
        return spent;
    }

    /** The limit, or null when unlimited. */
    public BigDecimal limit() {
        return limit;
    }

    /** What is left of the limit after the settled calls and the open reservations; null when unlimited. */
    public synchronized BigDecimal remaining() {
        return limit == null ? null : limit.subtract(spent).subtract(reserved).max(BigDecimal.ZERO);
    }

    /** True when the meter has no limit. */
    public boolean unlimited() {
        return limit == null;
    }

    /**
     * Before a model call: reserves the estimate, or throws when spent and reserved money plus the estimate exceeds
     * the limit (a limit of 0 refuses every call). The estimate is the gateway's {@code countTokens} when it has
     * {@code TOKEN_COUNT}, else text length / 4 plus {@code maxTokens}, at the price table (the caller's).
     *
     * @throws BudgetException when there is no room
     * @throws IllegalArgumentException for a negative estimate
     */
    public synchronized void reserve(BigDecimal estimateUsd) throws BudgetException {
        BigDecimal estimate = estimateUsd == null ? BigDecimal.ZERO : estimateUsd;
        if (estimate.signum() < 0) {
            throw new IllegalArgumentException("an estimate is not negative");
        }
        if (limit != null && (limit.signum() == 0 || spent.add(reserved).add(estimate).compareTo(limit) > 0)) {
            throw new BudgetException(scope, id, limit, spent.add(reserved), estimate);
        }
        reservations.addLast(estimate);
        reserved = reserved.add(estimate);
    }

    /** After a model call: adds its real cost ({@code usage.costUsd}) and releases the oldest open reservation. */
    public synchronized void settle(Usage usage) {
        spent = spent.add(usage.cost());
        releaseOldest();
    }

    /** A reserved call that was not made, or failed without a cost: releases the oldest open reservation. */
    public synchronized void release() {
        releaseOldest();
    }

    private void releaseOldest() {
        BigDecimal r = reservations.pollFirst();
        if (r != null) {
            reserved = reservations.isEmpty() ? BigDecimal.ZERO : reserved.subtract(r);
        }
    }

    /** Scope, id and amounts. */
    @Override
    public synchronized String toString() {
        return "BudgetMeter[" + scope + " " + id + ", spent " + spent.toPlainString() + " of "
                + (limit == null ? "unlimited" : limit.toPlainString()) + "]";
    }
}
