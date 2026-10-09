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

import ai.skillroy.testjava.inference.resolve.LlmSqlResolver;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Strategy;

import java.math.BigDecimal;
import java.util.List;

/**
 * The session budget's rules for the tier (b) resolves a session's hosts run (phase5-contracts.md 12.4, decisions D101
 * and D157, phase6 security fix S56), shared by {@code SessionService.resolve} and the MCP server's
 * {@code skillroy_scenario_run}: which strategy lists need a model, the estimate reserved for one tier (b) resolve, and
 * the settlement of what the resolves added to the session's ledger.
 *
 * <p>Stateless; safe from any thread.
 */
public final class SessionMetering {

    private SessionMetering() {
    }

    /** True when a strategy list names a token whose strategy needs a model ({@code llm-sql}). */
    public static boolean namesModel(List<String> strategy) {
        if (strategy == null) {
            return false;
        }
        for (String token : strategy) {
            Strategy s = Strategy.ofToken(token);
            if (s != null && s.needsModel()) {
                return true;
            }
        }
        return false;
    }

    /** The model id the settings give a class, else the built-in default. */
    public static String model(InferenceSettings settings, ModelClass modelClass) {
        String id = settings == null ? null : settings.models().get(modelClass.settingsKey());
        return id != null ? id : InferenceSettings.DEFAULT_MODELS.get(modelClass.settingsKey());
    }

    /**
     * One tier (b) resolve's estimate: {@value LlmSqlResolver#ESTIMATE_EXTRA_TOKENS} input and
     * {@value LlmSqlResolver#MAX_TOKENS} output tokens at the price of the model id the settings give the Sonnet class
     * (the catalogue is not counted).
     */
    public static BigDecimal tierBEstimate(InferenceSettings settings, PriceTable prices) {
        return prices.estimate(model(settings, ModelClass.SONNET_CLASS), LlmSqlResolver.ESTIMATE_EXTRA_TOKENS,
                LlmSqlResolver.MAX_TOKENS, false);
    }

    /**
     * After the resolves that held {@code reservations} reservations on {@code meter}: the cost of the ledger's
     * entries from index {@code before} on is settled on the meter as one call (releasing one reservation) and the
     * others are released; with no new entry, every reservation is released.
     *
     * @param provider the gateway's name, for the settled usage
     * @return what the new entries cost
     */
    public static BigDecimal settle(BudgetMeter meter, CostLedger ledger, int before, int reservations,
                                    String provider) {
        BigDecimal spent = BigDecimal.ZERO;
        List<CostLedger.Entry> all = ledger.entries();
        for (int i = before; i < all.size(); i++) {
            spent = spent.add(cost(all.get(i)));
        }
        int open = reservations;
        if (all.size() > before && open > 0) {
            meter.settle(Usage.none(provider, null).withCost(spent.toPlainString(), null));
            open--;
        }
        for (int i = 0; i < open; i++) {
            meter.release();
        }
        return spent;
    }

    private static BigDecimal cost(CostLedger.Entry entry) {
        try {
            return entry.costUsd() == null ? BigDecimal.ZERO : new BigDecimal(entry.costUsd().strip());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
