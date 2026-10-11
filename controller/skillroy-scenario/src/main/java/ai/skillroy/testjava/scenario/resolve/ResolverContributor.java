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

package ai.skillroy.testjava.scenario.resolve;

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * A controller-side module that offers resolvers to a bind without being a pack (phase5-contracts.md 5.5, decision
 * D70): how {@code skillroy-inference} brings its model-backed resolver ({@code inference.sql}, strategy
 * {@link Strategy#LLM_SQL}) to a bind although {@code skillroy-scenario} does not depend on it.
 *
 * <p>Found through {@code ServiceLoader} on the thread context class loader (as the web path is, phase4-contracts.md
 * 6.1); a provider that cannot be loaded ({@code ServiceConfigurationError}, {@code LinkageError}) is skipped and
 * named in the bind's {@code inference} record. The engine consults the contributors in {@code live}, {@code approve}
 * and {@code re-resolve}, once per bind, when the first slot whose {@code strategy} names a model token
 * ({@code llm-sql}) builds its ladder; <b>never in {@code replay}</b>, whatever the settings say (there
 * {@link InferenceSettings#none()} is substituted and logged, and no contributor is even instantiated, decision D72).
 * The resolvers a contributor offers are appended to a kind's ladder after the pack's, in {@code ServiceLoader} order,
 * and only for a slot whose {@code strategy} names their strategy's token: a slot without a {@code strategy} list keeps
 * the pack's resolvers alone, so that a plan whose {@code inference} is {@code none} never reaches a model.
 *
 * <p>Every resolver offered must have an id that starts with {@code inference.} (the prefix reserved for what the
 * controller contributes, phase2-contracts.md 3.3) and a strategy that {@link Strategy#needsModel() needs a model};
 * anything else is reported as ENVIRONMENT ("contributor … offered …"). A resolver may implement
 * {@link BatchableResolver}, which {@code re-resolve} uses to park a slot.
 *
 * <p>An instance serves one bind (one engine) and is closed when the bind ends; it is called on the binding thread.
 */
public interface ResolverContributor extends AutoCloseable {

    /**
     * The resolvers for this pack and these settings; empty when the settings disable the contributor (gateway
     * {@code none}). May open a gateway, which {@link #close()} closes when the bind ends.
     *
     * @param pack the running pack
     * @param settings the bind's settings ({@code BindOptions.inference()})
     * @param ledger the run's cost ledger: every model call is recorded in it
     * @param clock the clock the bind budget is measured on
     * @return the resolvers, in the order they are tried; never null
     * @throws ContributionException when the settings name a gateway this contributor cannot build, or something it
     *     needs is missing (reported as ENVIRONMENT)
     */
    List<Resolver<?, ?>> resolvers(DomainPack pack, InferenceSettings settings, CostLedger ledger, Clock clock)
            throws ContributionException;

    /**
     * The capability ids this module contributes to the matrix with their state under these settings; empty by
     * default.
     *
     * @deprecated since Phase 7: controller-side capabilities reach the matrix through
     *     {@code ai.skillroy.testjava.scenario.CapabilityContributor} (phase7-contracts.md 8.5, decision D326); nothing
     *     reads this method, and {@code InferenceContributor} no longer overrides it
     */
    @Deprecated
    default Map<String, CapabilityState> capabilities(InferenceSettings settings) {
        return Map.of();
    }

    /** Releases what {@link #resolvers} opened (a gateway). Called once, when the bind ends; never throws. */
    @Override
    default void close() {
    }
}
