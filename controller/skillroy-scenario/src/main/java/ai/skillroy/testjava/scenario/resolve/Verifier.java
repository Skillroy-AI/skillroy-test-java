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

import java.util.List;

/**
 * The gate every query slot passes, in every mode: asks the application's own decision code, through a domain
 * primitive, whether a candidate really answers the query. Re-running the resolver's SQL is not a verifier.
 *
 * <p>Selected for a slot by {@code (query kind, id)}; one id may serve several query classes (one decision primitive
 * may verify several kinds). Runs before the first step, so it must be callable in the state a scenario starts in
 * (before any sign-on). Read-only and bounded, like a resolver. Shared by every run in the JVM and called on the
 * executor's thread: stateless or thread-safe.
 *
 * @param <Q> the query type
 * @param <T> the value type
 */
public interface Verifier<Q extends Query<T>, T> {

    /**
     * The name scenarios write in {@code verify:}, by convention the primitive that is asked
     * ({@code "lending.access.check"}). It names the application's decision code, which several kinds may share.
     */
    String id();

    /** The query class this verifier checks. */
    Class<Q> queryClass();

    /** Capability ids the verifier needs; the compiler adds them to {@code plan.requires}. */
    default List<String> requires() {
        return List.of();
    }

    /**
     * Whether the application itself agrees that {@code candidate} answers {@code query}. Must not throw for "no":
     * returns {@link VerifierResult#fail} with a reason. Evidence: the calls made and what the application answered.
     *
     * @throws DataException a statement failed (ENVIRONMENT)
     * @throws AgentRpcException a primitive answered with an error the verifier does not handle (ENVIRONMENT)
     * @throws AgentLostException the agent is gone
     * @throws InterruptedException the thread was interrupted
     */
    VerifierResult verify(Q query, T candidate, VerifyContext ctx)
            throws DataException, AgentRpcException, AgentLostException, InterruptedException;
}
