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
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookCatalogues;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.StoredTable;

import java.util.List;
import java.util.Set;

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

    /**
     * Applied to prompts no step expected, unless the scenario says {@code on_prompt: {unexpected: fail}};
     * {@code on_prompt: {unexpected: pack}} names this policy explicitly, and no other value loads
     * (phase8-contracts.md 9.2).
     */
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

    /**
     * What a model may read, per data source (phase5-contracts.md 5.2): one {@link SqlAllowList} per source. Empty
     * (the default) means tier (b) is unavailable for this pack: a scenario of it may not name the strategy
     * {@code llm-sql} (a compile error), and the inference module contributes no model-written SQL to its binds.
     * Read once per compile and once per bind.
     */
    default List<SqlAllowList> sqlAllowLists() {
        return List.of();
    }

    /**
     * The primitive through which an in-application overlay receives the answer to an assistance request
     * (phase5-contracts.md 12.4): the session service's overlay bridge calls it with {@code {requestId, answer}}.
     * Null (the default) means the application has no overlay.
     */
    default String assistAnswerPrimitive() {
        return null;
    }

    /**
     * The pack's hook catalogue (phase6-contracts.md 5): by default the resource
     * {@code META-INF/skillroy/hooks/<id()>.yaml} of this class's loader, read once; {@link HookCatalogue#EMPTY} when
     * there is none. An overlay answers a catalogue built from the base's with
     * {@code HookCatalogue.builder(base.hooks())} or {@code HookCatalogues.overlay(base.hooks(), …)}. Nothing of it enters {@code plan.json}: the controller
     * installs it on the target at a run's step 0b ({@code HookInstaller}) when the run's hooks switch allows.
     *
     * @throws IllegalStateException when the resource exists and is malformed (the message names the resource and the
     *     line); a compile of any scenario of the pack reports it as a compile error
     */
    default HookCatalogue hooks() {
        return HookCatalogues.ofPack(this);
    }

    /**
     * The pack's constants, overridable by an overlay (phase6-contracts.md 11.1, decision D145): resolvers, verifiers,
     * the fingerprinter and pre-flight checks read them through their contexts' {@code settings()}. Empty by default.
     */
    default PackSettings settings() {
        return PackSettings.EMPTY;
    }

    /**
     * A short plain-text legend of the application's icon-only or custom-painted controls, for a model that looks at
     * the screen (phase7-contracts.md 12.3, 6.2): the vision loop puts it among the system parts, masked like the
     * session's vocabulary, never inside a {@code <target-ui>} block. Null (the default) means the pack has none.
     * Nothing of it enters {@code plan.json}.
     */
    default String screenLegend() {
        return null;
    }

    /**
     * The pack's capabilities (phase8-contracts.md 9.1, gap 106, decision D609): environment facts only the pack
     * knows, each {@code <packId>.<name>}, evaluated by the host after the controller-side contributors' merge and
     * merged into the run's matrix with the source {@code pack:<packId>}, so that a scenario names them in
     * {@code requires:} or a guard. At most 64. Empty by default. Read once per host (a run, a session's open, a
     * command); nothing of it enters {@code plan.json}.
     */
    default List<PackCapability> capabilities() {
        return List.of();
    }

    /**
     * The pack's own warning codes (phase8-contracts.md 7.1, 7.3, gap 18, amendment A87): each
     * {@code <packId>.<NAME>} with this pack's id before the dot (the protocol's {@code CallWarning.PACK_CODE}),
     * checked once when the compiler is constructed. A step may accept only a base code or a code declared here; a
     * pack code a primitive reports that the pack does not declare is never acceptable. An overlay overrides it to add
     * its own. Empty by default.
     */
    default Set<String> warningCodes() {
        return Set.of();
    }
}
