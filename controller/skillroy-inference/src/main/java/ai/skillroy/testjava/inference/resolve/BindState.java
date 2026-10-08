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

package ai.skillroy.testjava.inference.resolve;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.prompt.SchemaCatalogue;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.sql.SqlGuard;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the tier (b) resolvers of one bind share (phase5-contracts.md 5.4): the gateway lease, the price table, the
 * scenario's budget meter, the ledger writer, the prompt mask, one guard per allow-list and the prompt catalogue,
 * built on first use with the bind's data channel and kept for the bind. Created by {@link InferenceContributor} and
 * closed with it (the guards' parser threads and the gateway lease).
 *
 * <p>Thread-safe.
 */
final class BindState implements AutoCloseable {

    final InferenceGateway gateway;
    final PriceTable prices;
    final BudgetMeter meter;
    final LedgerWriter ledger;
    final PromptMask mask;
    final List<SqlAllowList> lists;
    final BigDecimal threshold;
    final Effort effort;
    final long callMs;
    final String estimateModel;

    private final Map<SqlAllowList, SqlGuard> guards = new IdentityHashMap<>();
    private SchemaCatalogue catalogue;
    private boolean closed;

    BindState(InferenceGateway gateway, PriceTable prices, BudgetMeter meter, LedgerWriter ledger, PromptMask mask,
              List<SqlAllowList> lists, BigDecimal threshold, Effort effort, long callMs, String estimateModel) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.meter = meter;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.mask = Objects.requireNonNull(mask, "mask");
        this.lists = List.copyOf(lists);
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        this.effort = Objects.requireNonNull(effort, "effort");
        this.callMs = callMs;
        this.estimateModel = estimateModel;
    }

    /** The guard of an allow-list, made on first use. */
    synchronized SqlGuard guard(SqlAllowList list) {
        if (closed) {
            throw new IllegalStateException("the bind's inference state is closed");
        }
        return guards.computeIfAbsent(list, SqlGuard::new);
    }

    /**
     * The prompt catalogue, built on first use with the samples read through this slot's data channel (the sample
     * statements are recorded in that slot's provenance) and kept for the bind.
     */
    synchronized SchemaCatalogue catalogue(ResolveContext ctx)
            throws DataException, AgentLostException, InterruptedException {
        if (catalogue == null) {
            catalogue = SchemaCatalogue.build(lists, ctx.data(), mask, Duration.ofMillis(Math.max(1,
                    ctx.remainingMs())));
        }
        return catalogue;
    }

    /** Closes the guards and releases the gateway lease; idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (SqlGuard guard : guards.values()) {
            guard.close();
        }
        guards.clear();
        gateway.close();
    }
}
