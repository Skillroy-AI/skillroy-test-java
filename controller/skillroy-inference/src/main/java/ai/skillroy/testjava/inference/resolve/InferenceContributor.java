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

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.sql.SqlGuard;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.ContributionException;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverContributor;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.ValueLoader;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code skillroy-inference} contributes to a bind (phase5-contracts.md 5.5, decision D70), found through
 * {@code META-INF/services}: when the settings name a gateway other than {@code none} and the pack declares at least
 * one SQL allow-list, an {@link LlmSqlResolver} ({@code inference.sql.<kind>}, strategy {@code LLM_SQL}) for every
 * query kind of the pack whose value type its codec supports, sharing one gateway lease, one scenario budget meter
 * ({@code budget.scenarioUsd}), one ledger writer (its {@code inference.jsonl} records kept with the run's ledger,
 * {@code InferenceLog.forLedger}) and one prompt catalogue. A kind with a {@code ValueLoader} is answered through it.
 *
 * <p>The prompt mask knows the pack's allow-lists and row masks; it knows no credential value, because a resolver
 * never sees one (the binder refuses a credential reference in a query's arguments), and applies the key rule.
 *
 * <p>An instance serves one bind; {@link #close()} releases the gateway lease and the guards' parser threads.
 */
public final class InferenceContributor implements ResolverContributor {

    /** The capability of a configured gateway. */
    public static final String CAPABILITY_GATEWAY = "inference.gateway";
    /** The capability of tier (b) resolution. */
    public static final String CAPABILITY_SQL = "inference.sql";
    /** Reserved for the vision loop of Phase 7: always {@code UNAVAILABLE} here. */
    public static final String CAPABILITY_VISION = "inference.vision";

    private BindState state;

    /** The {@code ServiceLoader} constructor. */
    public InferenceContributor() {
    }

    @Override
    public synchronized List<Resolver<?, ?>> resolvers(DomainPack pack, InferenceSettings settings,
                                                       CostLedger ledger, Clock clock)
            throws ContributionException {
        if (settings == null || !settings.enabled()) {
            return List.of();
        }
        List<SqlAllowList> lists = pack.sqlAllowLists();
        if (lists == null || lists.isEmpty()) {
            return List.of();
        }
        for (SqlAllowList list : lists) {
            if (!SqlGuard.served(list.dialect())) {
                throw new ContributionException("SQL dialect " + list.dialect() + " of source " + list.source()
                        + " is not served: no BS6 corpus");
            }
        }
        PriceTable prices;
        try {
            prices = PriceTable.of(settings);
        } catch (SettingsException e) {
            throw new ContributionException(e.getMessage());
        }
        BigDecimal threshold = decimal(settings.thresholds().get("confidence"), "0.8", "thresholds.confidence");
        Effort effort;
        try {
            effort = Effort.parse(settings.effort().getOrDefault("resolve", "low"));
        } catch (IllegalArgumentException e) {
            throw new ContributionException("effort.resolve must be low, medium or high");
        }
        BudgetMeter meter;
        try {
            meter = BudgetMeter.of(BudgetMeter.Scope.SCENARIO, null,
                    settings.budgets().get(InferenceSettings.BUDGET_SCENARIO), ledger);
        } catch (IllegalArgumentException e) {
            throw new ContributionException("budget.scenarioUsd must be an amount of US dollars");
        }
        PromptMask mask = PromptMask.of(SecretMask.NONE, lists, pack.rowMasks()).withImages(settings.images());
        LedgerWriter writer = new LedgerWriter(ledger, meter, InferenceLog.forLedger(ledger), mask,
                settings.logPrompts(), clock);
        Long callMs = settings.timeouts().get(InferenceSettings.TIMEOUT_CALL_MS);
        InferenceGateway gateway;
        try {
            gateway = Gateways.open(settings);
        } catch (InferenceException e) {
            throw new ContributionException("the " + settings.gateway() + " gateway cannot be opened: "
                    + e.getMessage());
        }
        if (state != null) {
            state.close();
        }
        state = new BindState(gateway, prices, meter, writer, mask, lists, threshold, effort,
                callMs == null ? 120_000L : callMs, settings.models().get(ModelClass.SONNET_CLASS.settingsKey()));
        ResolutionCatalogue catalogue = pack.resolution();
        List<Resolver<?, ?>> out = new ArrayList<>();
        for (String kind : catalogue.kinds()) {
            QueryType<?, ?> type = catalogue.query(kind);
            if (type.codec().unsupported(type.valueType()) != null) {
                continue;
            }
            out.add(resolver(type, catalogue.valueLoader(kind)));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <Q extends Query<T>, T> LlmSqlResolver<Q, T> resolver(QueryType<Q, T> type, ValueLoader<?, ?> loader) {
        return new LlmSqlResolver<>(type, (ValueLoader) loader, state);
    }

    /**
     * {@code inference.gateway} and {@code inference.sql}: {@code OK} when the settings name a gateway, else
     * {@code UNAVAILABLE} (whether the pack declares an allow-list is the bind's to find); {@code inference.vision}:
     * {@code UNAVAILABLE} (reserved for Phase 7).
     */
    @Override
    public Map<String, CapabilityState> capabilities(InferenceSettings settings) {
        boolean on = settings != null && settings.enabled();
        Map<String, CapabilityState> out = new LinkedHashMap<>();
        out.put(CAPABILITY_GATEWAY, on ? CapabilityState.OK : CapabilityState.UNAVAILABLE);
        out.put(CAPABILITY_SQL, on ? CapabilityState.OK : CapabilityState.UNAVAILABLE);
        out.put(CAPABILITY_VISION, CapabilityState.UNAVAILABLE);
        return out;
    }

    /** The scenario meter of the bind, or null before {@link #resolvers} (for the run's summary and tests). */
    public synchronized BudgetMeter meter() {
        return state == null ? null : state.meter;
    }

    /** Releases the gateway lease and the guards; never throws. */
    @Override
    public synchronized void close() {
        if (state != null) {
            try {
                state.close();
            } catch (RuntimeException ignored) {
                // Best effort, as the engine closes contributors.
            }
            state = null;
        }
    }

    private static BigDecimal decimal(String text, String fallback, String what) throws ContributionException {
        try {
            BigDecimal d = new BigDecimal((text == null ? fallback : text).strip());
            if (d.signum() < 0 || d.compareTo(BigDecimal.ONE) > 0) {
                throw new ContributionException(what + " must be a number from 0 to 1");
            }
            return d;
        } catch (NumberFormatException e) {
            throw new ContributionException(what + " must be a number from 0 to 1");
        }
    }
}
