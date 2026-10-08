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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.ResolutionException;

/**
 * What a {@link Resolver} may use while it finds candidates for one slot. Implemented by the engine; valid only
 * during the {@code candidates} call it was passed to, and only on that thread.
 */
public interface ResolveContext {

    /**
     * A read-only view of the agent: a mutating or destructive primitive is refused with
     * {@code IllegalStateException} (a pack defect, reported as ENVIRONMENT), {@code await} and event streams throw
     * {@code UnsupportedOperationException}, {@code close} does nothing, and every call's timeout is clamped to
     * {@link #remainingMs()}. Calls are recorded into the provenance.
     */
    AgentConnection agent();

    /** Guarded read-only SQL, routed by source id; statements are recorded into the provenance. */
    DataChannel data();

    /** The slot's credential declaration, or null. */
    CredentialSpec credential();

    /** Whether an entry exists; never the fields. */
    CredentialIndex credentials();

    /** The environment id of the bind. */
    String envId();

    /** The mode of the bind. */
    BindMode mode();

    /** How many candidates the engine will hand to the verifiers: 20. A resolver may stop there. */
    int maxCandidates();

    /** What is left of the bind budget, in milliseconds. */
    long remainingMs();

    /**
     * The run's cost ledger: a resolver that calls a model records each call in it (phase5-contracts.md 7.3); the
     * entries recorded during this call fill the slot's {@code Resolution.usage}.
     */
    CostLedger ledger();

    /**
     * The slot being resolved (Phase 5: what a model-backed resolver writes into its ledger entry and its request's
     * metadata); null from an implementation that does not know it.
     */
    default String slot() {
        return null;
    }

    /**
     * The scenario being bound, or null when the engine resolves outside a plan (an interactive session that set none)
     * or the implementation does not know it.
     */
    default String scenarioId() {
        return null;
    }

    /** Adds a text to {@link Provenance#notes()}; never a secret. */
    void note(String text);

    /**
     * For a statement whose result the resolver needs whole (a lookup it joins against in Java).
     *
     * @return {@code rows} when it is complete
     * @throws ResolutionException reason {@code TRUNCATED} when the result was cut at the row cap
     */
    Rows requireComplete(Rows rows) throws ResolutionException;
}
