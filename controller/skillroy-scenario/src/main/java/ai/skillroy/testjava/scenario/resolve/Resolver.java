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
 * Finds candidate answers to one query kind in an environment: committed SQL ({@link Strategy#CANONICAL_SQL}) or
 * committed Java ({@link Strategy#CANONICAL_JAVA}) in Phase 2. A resolver returns candidates; the engine hands them
 * to the slot's verifiers and accepts the first one every verifier passes (decision D3), so a resolver can never
 * skip the gate.
 *
 * <p>Rules (phase2-contracts.md 6.4): deterministic and read-only (the agent it receives refuses mutating
 * primitives); bounded (every timeout is clamped to the bind budget); SQL is committed text with {@code ?}
 * parameters and an {@code ORDER BY} that determines the order completely; it never selects a secret column and
 * never sees a credential, only whether one exists ({@link ResolveContext#credentials()}).
 *
 * <p>Shared by every run in the JVM and called by the binder on the executor's thread, one call at a time: stateless
 * or thread-safe.
 *
 * @param <Q> the query type
 * @param <T> the value type
 */
public interface Resolver<Q extends Query<T>, T> {

    /**
     * Stable id written into provenance, by convention {@code "<pack>.<sql|java>.<Kind>"}. The id is a role: a
     * variant pack replaces a resolver by registering another under the same id.
     */
    String id();

    /** The query class this resolver answers. */
    Class<Q> queryClass();

    /**
     * {@link Strategy#CANONICAL_SQL} or {@link Strategy#CANONICAL_JAVA} for a pack's resolver; a strategy that
     * {@link Strategy#needsModel() needs a model} for one a {@link ResolverContributor} offers (phase5-contracts.md
     * 5.5).
     */
    Strategy strategy();

    /**
     * Candidates, best first. Deterministic: the same data and query give the same list in the same order. An empty
     * list means nothing in this environment satisfies the query. Never null, no null element.
     *
     * @throws ResolutionException to end the slot at once with the exception's reason ({@code TRUNCATED} from
     *     {@link ResolveContext#requireComplete}, a reason of the resolver's choice, or {@code RESOLVER})
     * @throws DataException a statement failed (reported as ENVIRONMENT)
     * @throws AgentRpcException a primitive answered with an error the resolver does not handle (ENVIRONMENT)
     * @throws AgentLostException the agent is gone
     * @throws InterruptedException the thread was interrupted
     */
    List<ResolverCandidate<T>> candidates(Q query, ResolveContext ctx)
            throws ResolutionException, DataException, AgentRpcException, AgentLostException, InterruptedException;
}
