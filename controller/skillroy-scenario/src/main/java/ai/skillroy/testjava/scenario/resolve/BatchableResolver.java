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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.scenario.ResolutionException;

import java.util.List;

/**
 * A resolver whose model call can be deferred into a batch (phase5-contracts.md 8.3, decision D75). In
 * {@code re-resolve}, a stepwise bind ({@code Binder.bindStepwise}) calls {@link #prepare} where it would call
 * {@link #candidates}, <b>parks</b> the slot with the prepared request, and later, when the batch's answer is in,
 * calls {@link #complete} with it; what {@code complete} returns is handled exactly as candidates are (the credential
 * drop, the cap of 20, every verifier, the next resolver when none passes). In every other mode, and in a one-shot
 * bind, the engine calls {@link #candidates} as for any resolver.
 *
 * <p>Both calls get a {@link ResolveContext} like {@code candidates} does (the read-only agent, the recording data
 * channel, the ledger); the statements and calls of {@code prepare} and {@code complete} go into one provenance. The
 * ledger entries recorded during either call count towards the slot's {@code Resolution.usage}. The time a slot is
 * parked is not counted against the bind budget. A {@link ResolutionException} (for example reason {@code BUDGET})
 * ends the slot as from {@code candidates}.
 *
 * @param <Q> the query type
 * @param <T> the value type
 */
public interface BatchableResolver<Q extends Query<T>, T> extends Resolver<Q, T> {

    /**
     * The request the job submits in its batch for this slot: opaque to the engine, never null.
     *
     * @throws ResolutionException to end the slot at once with the exception's reason ({@code BUDGET}, …)
     * @throws DataException a statement failed (ENVIRONMENT)
     * @throws AgentRpcException a primitive answered with an error the resolver does not handle (ENVIRONMENT)
     * @throws AgentLostException the agent is gone
     * @throws InterruptedException the thread was interrupted
     */
    Object prepare(Q query, ResolveContext ctx)
            throws ResolutionException, DataException, AgentRpcException, AgentLostException, InterruptedException;

    /**
     * The candidates for the batch's answer to the request {@link #prepare} returned, best first, under the rules of
     * {@link #candidates}: never null, no null element.
     *
     * @param answer what the job collected for this slot's request (the resolver knows its type: a response, or the
     *     failure the provider reported for it)
     * @throws ResolutionException to end the slot at once with the exception's reason
     * @throws DataException a statement failed (ENVIRONMENT)
     * @throws AgentRpcException a primitive answered with an error the resolver does not handle (ENVIRONMENT)
     * @throws AgentLostException the agent is gone
     * @throws InterruptedException the thread was interrupted
     */
    List<ResolverCandidate<T>> complete(Q query, ResolveContext ctx, Object answer)
            throws ResolutionException, DataException, AgentRpcException, AgentLostException, InterruptedException;
}
