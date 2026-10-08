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
import java.util.Map;

/**
 * Fills a value that one flat SQL row cannot hold (phase5-contracts.md 5.4 step 4, decision D90): a value type with a
 * list or a map component (a staff member with the permissions their roles grant and their limits by kind). A pack
 * registers at most one per query kind ({@code ResolutionCatalogue.Builder.valueLoader}); a model-written statement for
 * that kind then selects only the loader's {@link #identityColumns()}, and each row becomes a candidate through
 * {@link #load}, which reads the rest with the pack's own committed SQL or read-only calls. Without a loader, a row
 * fills a flat record by column name and a list or map component is refused.
 *
 * <p>Rules as for a resolver (phase2-contracts.md 6.4): deterministic and read-only, bounded by the context's budget,
 * never selecting a secret column. Shared by every run in the JVM: stateless or thread-safe.
 *
 * @param <Q> the query type
 * @param <T> the value type
 */
public interface ValueLoader<Q extends Query<T>, T> {

    /** The query kind it serves; the catalogue refuses a loader for an undeclared kind, or a second one for a kind. */
    String kind();

    /**
     * The columns a row must carry, by name, in order: the identity the loader reads the whole value by (the staff
     * code). At least one; a row's other columns are ignored, a missing one rejects the row.
     */
    List<String> identityColumns();

    /**
     * The value of one row's identity.
     *
     * @param query the slot's query
     * @param identity the row's identity values by {@link #identityColumns()} name, in the channel's value forms
     *     (strings, longs, booleans, doubles, null)
     * @param ctx the slot's context (the read-only agent, the recording data channel)
     * @return the value, never null
     * @throws ResolutionException to reject this row with the exception's reason (other rows are still tried)
     * @throws DataException a statement failed (ENVIRONMENT)
     * @throws AgentRpcException a primitive answered with an error the loader does not handle (ENVIRONMENT)
     * @throws AgentLostException the agent is gone
     * @throws InterruptedException the thread was interrupted
     */
    T load(Q query, Map<String, Object> identity, ResolveContext ctx)
            throws ResolutionException, DataException, AgentRpcException, AgentLostException, InterruptedException;
}
