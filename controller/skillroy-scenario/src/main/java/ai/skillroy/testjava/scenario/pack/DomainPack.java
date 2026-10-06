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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.StoredTable;

import java.util.List;

/**
 * The controller half of a domain: verbs, sync topics, fact roots, prompt handling, pre-flight checks and recovery.
 * Scenarios stay free of primitive names and topics; the pack translates. Its agent half is the {@code AgentModule}
 * with the same id.
 *
 * <p>Discovered with {@code ServiceLoader<DomainPack>} (a {@code META-INF/services} entry and a public no-argument
 * constructor). Instances are shared by every scenario run in the JVM, so they must be stateless or thread-safe.
 */
public interface DomainPack {

    /** The pack id that scenarios name in {@code pack:}; by convention the primitive namespace ({@code "lending"}). */
    String id();

    /** Written into {@code plan.json} as {@code packVersion}; change it whenever a verb compiles differently. */
    String version();

    /** The pack's verbs. */
    StepLibrary steps();

    /** The topics the step protocol waits on. */
    SyncPolicy syncPolicy();

    /** Fact roots for expectation paths; a root here hides a base root of the same name. */
    List<FactProvider> facts();

    /** Applied to prompts no step expected, unless the scenario says {@code on_prompt: {unexpected: fail}}. */
    default PromptPolicy promptPolicy() {
        return PromptPolicy.FAIL;
    }

    /**
     * Environment checks run before binding (desk open, device usable, …); a failure is ENVIRONMENT, not a test
     * failure.
     */
    default List<PreflightCheck> preflight() {
        return List.of();
    }

    /**
     * Called after a scenario that did not PASS, to bring the application back to a known idle state so the next
     * scenario can run without a restart. Must be idempotent and bounded.
     *
     * <p>Called on the executor's thread after the cleanup calls; an exception is logged and never changes the
     * verdict.
     */
    default void recover(AgentConnection agent) throws Exception {
    }

    /**
     * Query kinds, resolvers, verifiers and oracles of this pack (phase2-contracts.md 6.4). Read once per compile and
     * once per run. A pack without resolution support keeps the default, {@link ResolutionCatalogue#EMPTY}.
     */
    default ResolutionCatalogue resolution() {
        return ResolutionCatalogue.EMPTY;
    }

    /** Supplies the pack's fingerprint components; null means the base components only. */
    default EnvFingerprinter fingerprinter() {
        return null;
    }

    /** Tables usable in {@code db.<name>} checks. */
    default List<StoredTable> storedTables() {
        return List.of();
    }

    /**
     * Applied on the controller to every row read over either channel before it is kept or shown
     * (phase2-contracts.md 7.3): by the JDBC channel to the values it returns, and by the binder to every row sample
     * it puts into provenance. Declare the same rule as the agent module's {@code MaskPolicy}.
     */
    default List<RowMask> rowMasks() {
        return List.of();
    }
}
