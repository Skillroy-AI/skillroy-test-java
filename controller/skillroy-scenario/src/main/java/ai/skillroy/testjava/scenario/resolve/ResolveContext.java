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
     * Why a model wrote what this resolver offers (phase5-contracts.md 5.4 step 5): the engine keeps the text as the
     * slot's {@link Provenance#rationale()} when a candidate of this resolver call is accepted, so that the bindings
     * file shows a reviewer why the statement was written. Masked by the resolver and again by the engine, cut at
     * {@value Provenance#MAX_RATIONALE} characters; a later call replaces an earlier one. The default keeps it as a
     * note ({@code "rationale: …"}), for a context that has no provenance of its own.
     */
    default void rationale(String text) {
        if (text != null) {
            note("rationale: " + text);
        }
    }

    /**
     * Records something this resolver considered and refused before it became a candidate (a model's proposal under
     * the confidence threshold or refused by the guard, a row a value loader refused): the engine adds it to the
     * slot's {@link Provenance#rejected()} under this resolver's id, as it records a candidate a verifier refused, so
     * that a failure's detail and the bindings file show it. Never a secret (the engine masks it as it masks notes).
     * The default keeps it as a note ({@code "rejected <label>: <reason>"}).
     *
     * @param label what was refused ({@code "proposal"}, a row's identity values)
     * @param reason why
     */
    default void reject(String label, String reason) {
        note("rejected " + label + ": " + reason);
    }

    /**
     * For a statement whose result the resolver needs whole (a lookup it joins against in Java).
     *
     * @return {@code rows} when it is complete
     * @throws ResolutionException reason {@code TRUNCATED} when the result was cut at the row cap
     */
    Rows requireComplete(Rows rows) throws ResolutionException;
}
