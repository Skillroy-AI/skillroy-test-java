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

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;

/**
 * Compares an oracle's output with the application's own computation of the same thing, at bind time
 * (phase3-contracts.md 5.1, decision D37): committed Java in the pack that asks the application, through a read-only
 * domain primitive or the data channel, to compute what the oracle computed (a dry run, a quote, a preview).
 * Re-running the oracle, or re-reading the rows the oracle's inputs came from, is not a cross-check.
 *
 * <p>A scenario names the check in an oracle entry ({@code crossCheck: <id>}); the binder runs it once per oracle per
 * bind, in every mode, after the oracle is computed and before its guard ({@code must_equal}). A disagreement is
 * ORACLE_SUSPECT: a verdict on the test, never on the application.
 *
 * <p>Rules: read-only and bounded, as a verifier (the agent view refuses mutating primitives, every timeout is clamped
 * to what is left of the bind budget, and the budget is checked before the call); it must not throw for "disagree"
 * but return {@link CrossCheckResult#disagree}; a {@code RuntimeException}, a {@link DataException}, an unhandled
 * JSON-RPC error or a null result is a pack defect (ENVIRONMENT). The oracle stays pure: only the check touches the
 * target. Shared by every run in the JVM and called on the executor's thread: stateless or thread-safe.
 *
 * <p>It is not a {@link Verifier}: it has no query and no candidate, and it is selected by oracle function
 * ({@link #oracleFn()}), not by query kind.
 *
 * @param <I> the oracle's input type (codec-supported; must equal the oracle's)
 * @param <O> the oracle's output type (codec-supported; must equal the oracle's)
 */
public interface CrossCheck<I, O> {

    /**
     * The name scenarios write in {@code crossCheck.check}, by convention the primitive asked
     * ({@code "lending.waiver.evaluate"}). An id that is or starts with {@code inference} is reserved for what the
     * controller contributes.
     */
    String id();

    /** The function of the oracle this check is for ({@code Oracle.fn()}). */
    String oracleFn();

    /**
     * Capability ids the check needs. For an unguarded check the compiler adds them to {@code plan.requires}; a
     * guarded check ({@code capability:}) adds nothing and is skipped where its capability is not OK.
     */
    default List<String> requires() {
        return List.of();
    }

    /**
     * Whether the check gives a meaning to {@code crossCheck.tolerance}; when false, a scenario that writes one does
     * not compile and the check always receives null.
     */
    default boolean acceptsTolerance() {
        return false;
    }

    /** The input type; by default read from the class's {@code implements CrossCheck<I, O>} declaration. */
    default Type inputType() {
        return GenericTypes.argument(getClass(), CrossCheck.class, 0);
    }

    /** The output type; by default read from the class's {@code implements CrossCheck<I, O>} declaration. */
    default Type outputType() {
        return GenericTypes.argument(getClass(), CrossCheck.class, 1);
    }

    /**
     * Asks the application to compute what the oracle computed and compares. Must not throw for "disagree".
     *
     * @param input the oracle's decoded input
     * @param output the oracle's output, decoded
     * @param tolerance the plan's tolerance when the check accepts one, else null (null means exact)
     * @param ctx the read-only agent view, the data channel, the environment id and the remaining budget
     * @return whether the application agrees, why, what it computed and the evidence; never null
     * @throws DataException a statement failed (ENVIRONMENT)
     * @throws AgentRpcException a primitive answered with an error the check does not handle (ENVIRONMENT)
     * @throws AgentLostException the agent is gone
     * @throws InterruptedException the thread was interrupted
     */
    CrossCheckResult check(I input, O output, BigDecimal tolerance, VerifyContext ctx)
            throws DataException, AgentRpcException, AgentLostException, InterruptedException;
}
