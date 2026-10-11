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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Binds a plan's slots at run start and checks that every {@code $slot} reference in the plan resolves, so that a
 * bad reference fails the run before any step touches the application (contract section 8.5, phase2-contracts.md
 * 8).
 *
 * <p>{@link #bind(Plan)} binds fixtures only, as Phase 1 (a fixture may reference other slots; they are bound first;
 * a cycle is an error). {@link #bind(Plan, DomainPack, AgentConnection, BindOptions, BindListener)} is the full bind:
 * a plan without query slots or oracles is bound as in Phase 1 plus the credential join; any other plan computes the
 * environment fingerprint and then, by mode:
 * <ul>
 *   <li>{@code replay}: reads {@code <bindingsRoot>/<envId>/<scenarioId>.bindings.json}, compares its
 *       {@code resolveHash}, its fingerprint and each slot's {@code argsHash}, re-runs every verifier on the recorded
 *       values (never a resolver), recomputes the oracles and compares them, checks the guards. Every mismatch is
 *       REBIND_NEEDED with its reason; after a fingerprint mismatch no verifier runs;</li>
 *   <li>{@code live}: resolves every query slot now ({@link ResolutionEngine}) in graph order, computes the oracles,
 *       checks the guards; a failure is RESOLUTION_FAILED. Writes the bindings file only with
 *       {@code writeBindings} ({@code approval: NOT_REQUIRED});</li>
 *   <li>{@code approve}: as {@code live}, then asks the {@link Approver} once and, if approved, writes the bindings
 *       file atomically (an identical file is left untouched; a text the run's mask would change is refused).</li>
 * </ul>
 * Phase 5 (phase5-contracts.md 5.5, 7.1, 8): a slot naming a model token ({@code llm-sql}) gets the contributed
 * resolvers after the pack's (never in {@code replay}); a value a model found is {@code REQUIRED} until approved, so
 * that {@code live --write-bindings} writes it {@code REQUIRED} and {@code replay} refuses it (REBIND_NEEDED, reason
 * {@code NOT_APPROVED}) until {@code approve} writes it {@code APPROVED}; {@code approve} reuses a recorded tier (b)
 * value that is unchanged and still verified, without asking a resolver ({@code BindOptions.reuseBindings}, D82);
 * {@code re-resolve} is refused by this one-shot bind and driven by {@link #bindStepwise}, which parks a slot whose
 * ladder reaches a batchable resolver and writes the file {@code APPROVED} by the job.
 * Substitution during the graph walk uses values without joined secret fields; the credential join is applied after
 * the walk and the guards, then {@link #check} runs. No backtracking across slots (decision D5): a failure names the
 * already-bound query slots it depended on ({@code fixedUpstream}). A bind never mutates the target.
 *
 * <p>Step 8, in every mode (phase3-contracts.md 6.1): after an oracle is computed (and, in {@code replay}, compared
 * with the file, so that ORACLE_CHANGED comes first) and before its guard, its cross-check runs. A guarded check whose
 * capability is not OK on the target (an undeclared one counts as UNAVAILABLE; the states come from the run's one
 * {@code agent.capabilities} read, or one read by the binder itself when it is called without them) is skipped,
 * recorded and listed in {@link BindOutcome#skipped()}, which makes the run DEGRADED at best (6.2). A check that
 * disagrees is ORACLE_SUSPECT, reason {@code DISAGREED}, in every mode: nothing is written and no step runs.
 *
 * <p>Stateless; safe from any thread (one bind runs on its calling thread).
 */
public final class Binder {

    private Binder() {
    }

    /**
     * Binds every fixture slot of the plan (Phase 1). Query slots need the full bind; captured slots are bound while
     * steps run.
     *
     * @throws ResolutionException for a query slot, a cycle, or a reference that does not resolve
     */
    public static Bindings bind(Plan plan) throws ResolutionException {
        return bindFixtures(plan, null);
    }

    /** The fixtures of a plan, with {@code $env.id} available when {@code envId} is given. */
    static Bindings bindFixtures(Plan plan, String envId) throws ResolutionException {
        Map<String, Plan.Slot> byName = new LinkedHashMap<>();
        for (Plan.Slot slot : plan.slots()) {
            if ("capture".equals(slot.kind())) {
                continue;
            }
            if (!"fixture".equals(slot.kind())) {
                throw new ResolutionException(null, "slot '" + slot.name() + "': kind '" + slot.kind()
                        + "' needs the full bind (Binder.bind with a pack, an agent and BindOptions)");
            }
            byName.put(slot.name(), slot);
        }
        Map<String, Object> bound = new LinkedHashMap<>();
        Set<String> inProgress = new HashSet<>();
        for (String name : byName.keySet()) {
            bindSlot(name, byName, bound, inProgress, envId);
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String name : byName.keySet()) {
            ordered.put(name, bound.get(name));
        }
        return Bindings.of(ordered);
    }

    private static void bindSlot(String name, Map<String, Plan.Slot> byName, Map<String, Object> bound,
                                 Set<String> inProgress, String envId) throws ResolutionException {
        if (bound.containsKey(name)) {
            return;
        }
        if (!inProgress.add(name)) {
            throw new ResolutionException("$" + name, "slots reference each other in a cycle through '" + name
                    + "'");
        }
        Object value = References.substitute(byName.get(name).value(), (slot, fields, text) -> {
            if (!byName.containsKey(slot)) {
                if ("env".equals(slot) && envId != null) {
                    return References.navigate(Map.of("id", envId), fields, text);
                }
                throw new ResolutionException(text, "cannot resolve " + text + " in slot '" + name
                        + "': unknown slot '" + slot + "'; slots: " + byName.keySet());
            }
            bindSlot(slot, byName, bound, inProgress, envId);
            return References.navigate(bound.get(slot), fields, text);
        });
        inProgress.remove(name);
        bound.put(name, value);
    }

    /**
     * Checks that the bindings cover every fixture and query slot of the plan and that every reference in the steps
     * (call args and the topics and match of an EVENT call's await), the checks (an expectation's path and matcher; a
     * {@code db} check's where values, per-row matchers, aggregate matchers and row count) and the cleanup calls
     * resolves against them. A captured slot is checked against its declared fields. A joined credential field
     * resolves only in the arguments of step and cleanup calls (phase2-contracts.md 8.4).
     *
     * @throws ResolutionException naming the first reference that does not resolve
     */
    public static void check(Plan plan, Bindings bindings) throws ResolutionException {
        Map<String, Plan.Slot> captured = new LinkedHashMap<>();
        for (Plan.Slot slot : plan.slots()) {
            if ("capture".equals(slot.kind())) {
                captured.put(slot.name(), slot);
            } else if (!bindings.has(slot.name())) {
                throw new ResolutionException("$" + slot.name(), "slot '" + slot.name() + "' is not bound");
            }
        }
        References.Lookup full = captureAware(captured, bindings, bindings::lookup);
        References.Lookup restricted = captureAware(captured, bindings, bindings::lookupPublic);
        for (Plan.Step step : plan.steps()) {
            checkCalls(step.calls(), full, restricted, "step " + step.id());
            checkChecks(step.expect(), restricted, "step " + step.id());
        }
        checkChecks(plan.verify(), restricted, "verify");
        checkCalls(plan.cleanup(), full, restricted, "cleanup");
    }

    /** A lookup in which a captured slot that is not bound yet stands for its declared fields. */
    private static References.Lookup captureAware(Map<String, Plan.Slot> captured, Bindings bindings,
                                                  References.Lookup inner) {
        return (slot, fields, text) -> {
            Plan.Slot capture = captured.get(slot);
            if (capture == null || bindings.has(slot)) {
                return inner.lookup(slot, fields, text);
            }
            if (fields.isEmpty()) {
                Map<String, Object> placeholder = new LinkedHashMap<>();
                capture.capture().fields().forEach(f -> placeholder.put(f, ""));
                return placeholder;
            }
            if (!capture.capture().fields().contains(fields.get(0))) {
                throw new ResolutionException(text, "cannot resolve " + text + ": captured slot '" + slot
                        + "' has no field '" + fields.get(0) + "'; fields: " + capture.capture().fields());
            }
            return "";
        };
    }

    private static void checkCalls(List<Plan.Call> calls, References.Lookup full, References.Lookup restricted,
                                   String where) throws ResolutionException {
        for (Plan.Call call : calls) {
            try {
                References.substitute(call.args(), full);
                if (call.await() != null) {
                    String topics = AwaitRules.topicsText(References.substitute(call.await().topics(), restricted));
                    if (!topics.isEmpty() && TopicFilter.parse(topics).globs().isEmpty()) {
                        throw new ResolutionException(call.await().topics(), "await topics " + call.await().topics()
                                + " resolved to no topic glob: \"" + topics + "\"");
                    }
                    References.substitute(call.await().match(), restricted);
                }
            } catch (ResolutionException e) {
                throw new ResolutionException(e.reason(), e.reference(), where + " (" + call.primitive() + "): "
                        + e.getMessage());
            }
        }
    }

    private static void checkChecks(List<Check> checks, References.Lookup lookup, String where)
            throws ResolutionException {
        for (Check check : checks) {
            try {
                if (check instanceof Expectation expectation) {
                    ExpectationPath.parse(expectation.path()).bind(lookup);
                    References.substitute(expectation.matcher(), lookup);
                } else if (check instanceof RowCheck row) {
                    // The whole db element (phase3-contracts.md 3.2 rule 3): where values, per-row matchers,
                    // aggregate matchers and the row count. The capability is a literal id, never a reference.
                    References.substitute(row.paramValues(), lookup);
                    References.substitute(row.expect(), lookup);
                    References.substitute(row.aggregates(), lookup);
                    References.substitute(row.rows(), lookup);
                }
            } catch (ResolutionException e) {
                throw new ResolutionException(e.reason(), e.reference(), where + " " + check.describe() + ": "
                        + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new ResolutionException(null, where + " " + check.describe() + ": " + e.getMessage());
            }
        }
    }

    /**
     * The full bind of phase2-contracts.md 8. Never mutates the target. A one-shot bind cannot park a slot, so
     * {@code re-resolve} is refused here (phase5-contracts.md 8.1: a command, never part of a run); the job binds with
     * {@link #bindStepwise}.
     *
     * @param pack the running pack
     * @param agent the target's agent (resolvers and verifiers see a read-only view)
     * @param options mode, environment, bindings root, credentials, approver, ledger, clock and inference settings
     * @param listener receives the run.jsonl records of the bind; null ignores them
     * @throws BindException REBIND_NEEDED, RESOLUTION_FAILED or ENVIRONMENT, with the reason and detail of section 10;
     *     ENVIRONMENT "re-resolve is a command: skillroy re-resolve" for mode {@code re-resolve}
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public static BindOutcome bind(Plan plan, DomainPack pack, AgentConnection agent, BindOptions options,
                                   BindListener listener)
            throws BindException, AgentLostException, InterruptedException {
        return bind(plan, pack, agent, options, listener, null, null);
    }

    /**
     * A bind that may stop at a slot (phase5-contracts.md 8.2 step 2, 8.3): in {@code re-resolve}, a query slot whose
     * ladder reaches a {@code BatchableResolver} is parked with the request the resolver prepared, and the returned
     * {@link BindStep.Parked} goes on with the batch's answer ({@link BindStep.Parked#resume}) until the next park or
     * the end. Everything else is the bind of {@link #bind}: the ladder (tier (a) resolvers are asked inline), the
     * verifiers on every candidate, the oracles, cross-checks and guards, the credential join and the reference check;
     * at the end, the file is the one {@code approve} writes, every resolution {@code APPROVED} by
     * {@code options.approvedBy()} (the job's {@code re-resolve:<jobId>}; {@code re-resolve} when none), written when
     * {@code options.writeBindings()} (false: a dry run, the outcome's file is what would be written; it must hold no
     * credential value either way). In {@code replay}, {@code live} and {@code approve} nothing parks: the bind runs as
     * {@link #bind} does and the step is {@link BindStep.Done}.
     *
     * <p>A parked bind holds its engine (the contributors and the gateways they opened; its JDBC connections are closed
     * while it is parked and opened again on resumption) until it is resumed to the end, fails or is closed; the time
     * it is parked is not counted against the bind budget.
     *
     * @throws BindException as {@link #bind} (the engine is then closed)
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public static BindStep bindStepwise(Plan plan, DomainPack pack, AgentConnection agent, BindOptions options,
                                        BindListener listener)
            throws BindException, AgentLostException, InterruptedException {
        Objects.requireNonNull(plan, "plan");
        BindOptions effective = options == null ? BindOptions.defaults() : options;
        requireBindingsRoot(plan, effective);
        ResolutionEngine engine = new ResolutionEngine(pack, agent, effective, Duration.ofMillis(plan.bindTimeoutMs()),
                listener, null);
        engine.scenario(plan.scenarioId());
        BindStep step = null;
        try {
            step = new Run(plan, engine, agent, null, effective, listener == null ? BindListener.NONE : listener,
                    true).start();
            return step;
        } finally {
            if (!(step instanceof BindStep.Parked)) {
                engine.close();
            }
        }
    }

    /** As the public form, reading through the executor's data channels when {@code data} is not null. */
    static BindOutcome bind(Plan plan, DomainPack pack, AgentConnection agent, BindOptions options,
                            BindListener listener, DataChannel data)
            throws BindException, AgentLostException, InterruptedException {
        return bind(plan, pack, agent, options, listener, data, null);
    }

    /**
     * As the public form, reading through the executor's data channels when {@code data} is not null, and taking the
     * capability states of a guarded cross-check from {@code caps} (the executor's one {@code agent.capabilities}
     * read for {@code requires}) when it is not null; otherwise they are read once, when a guarded check needs them.
     */
    static BindOutcome bind(Plan plan, DomainPack pack, AgentConnection agent, BindOptions options,
                            BindListener listener, DataChannel data, CapabilityMatrix caps)
            throws BindException, AgentLostException, InterruptedException {
        Objects.requireNonNull(plan, "plan");
        BindOptions effective = options == null ? BindOptions.defaults() : options;
        if (effective.mode() == BindMode.RE_RESOLVE) {
            throw new BindException(FailureKind.ENVIRONMENT, null, null, "re-resolve is a command: skillroy"
                    + " re-resolve (a re-resolve bind parks slots and is driven by Binder.bindStepwise, never by a"
                    + " run)", RunLog.fields("mode", effective.mode().label(), "envId", effective.envId()));
        }
        requireBindingsRoot(plan, effective);
        try (ResolutionEngine engine = new ResolutionEngine(pack, agent, effective,
                Duration.ofMillis(plan.bindTimeoutMs()), listener, data)) {
            engine.scenario(plan.scenarioId());
            BindStep step = new Run(plan, engine, agent, caps, effective, listener == null ? BindListener.NONE
                    : listener, false).start();
            return ((BindStep.Done) step).outcome();
        }
    }

    /**
     * 8.3 approve step 8: a bind that writes the bindings file ({@code approve}, or {@code live} with
     * {@code writeBindings}) needs a bindings root, and without one it is a usage error before anything runs: no pack
     * code is called and nothing is sent to the target. A plan without query slots and oracles writes no file and
     * needs none.
     *
     * @throws BindException ENVIRONMENT naming the missing root
     */
    static void requireBindingsRoot(Plan plan, BindOptions options) throws BindException {
        boolean writes = options.mode() == BindMode.APPROVE || (options.mode() == BindMode.LIVE
                || options.mode() == BindMode.RE_RESOLVE) && options.writeBindings();
        if (writes && options.bindingsRoot() == null && plan.needsBindings()) {
            String what = options.mode() == BindMode.APPROVE ? "approve"
                    : options.mode().label() + " with writeBindings";
            throw new BindException(FailureKind.ENVIRONMENT, null, null, what + " needs a bindings root to write the"
                    + " bindings file of " + plan.scenarioId() + " to, and none was given (the CLI's --bindings,"
                    + " the JUnit glue's skillroy.bindings, BindOptions.withBindingsRoot); nothing was resolved",
                    RunLog.fields("mode", options.mode().label(), "envId", options.envId()));
        }
    }

    /**
     * Refuses the text of a bindings file that would hold a credential value (8.3 approve step 8, 11.4): the
     * credentials' mask, with the fixture values A25's key rule finds in the plan, must change neither the text (it
     * also sees keys, which the mask never reads in a value) nor its parsed values (a secret with a quote or a
     * backslash is escaped in the text, and a short secret written as a number has no quotes there). Applied where
     * {@code approve} and {@code live --write-bindings} write the file, and to the file the CLI's
     * {@code resolve --dry-run} would print ({@code PlanExecutor.resolve} in {@code live}).
     *
     * @throws BindException ENVIRONMENT "the bindings file was not written: a recorded value equals a credential"
     */
    static void requireNoCredential(Plan plan, BindOptions options, String text) throws BindException {
        SecretMask mask = options.credentials().mask().plus(FixtureSecrets.collect(fixtureValues(plan)));
        Object json = Json.parse(text);
        if (!mask.apply(text).equals(text) || !Objects.equals(mask.apply(json), json)) {
            throw new BindException(FailureKind.ENVIRONMENT, null, null, "the bindings file was not written: a"
                    + " recorded value equals a credential", null);
        }
    }

    /**
     * The second write of {@code approve} (phase7-contracts.md 8.7, decision D355): after the steps, the bindings file
     * the bind wrote (or read) is written again with the baselines a person approved at their steps beside the old
     * entries, their PNGs first under {@code <scenarioId>.images/}; the text passes {@link #requireNoCredential} as the
     * bind's write does.
     *
     * @param file the file the bind wrote or carried forward
     * @param baselines this run's baselines with its approvals
     * @return the file written
     * @throws BindException ENVIRONMENT when the file or a PNG cannot be written, or a value equals a credential
     */
    static BindingsFile writeApprovedImages(Plan plan, BindOptions options, BindingsFile file,
                                            ImageBaselines baselines) throws BindException {
        Path root = options.bindingsRoot();
        if (root == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, null, "writing a baseline needs a bindings root",
                    null);
        }
        BindingsFile updated = file.withImages(baselines.merged());
        String text = updated.toJsonText();
        requireNoCredential(plan, options, text);
        String relative = options.envId() + "/" + plan.scenarioId() + BindingsFile.SUFFIX;
        try {
            baselines.writeApprovedPngs();
            BindingsFile.writeText(BindingsFile.locate(root, options.envId(), plan.scenarioId()), text);
        } catch (IOException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, null, "the bindings file " + relative
                    + " or a baseline beside it could not be written: " + e.getClass().getSimpleName(),
                    RunLog.fields("bindingsFile", relative));
        }
        return updated;
    }

    /**
     * True when the plan's bindings hold images only: no query slot and no oracle (phase7-contracts.md 8.7, D362), so
     * {@code approve} has nothing to ask at bind time.
     */
    static boolean imagesOnly(Plan plan) {
        for (Plan.Slot slot : plan.slots()) {
            if ("query".equals(slot.kind())) {
                return false;
            }
        }
        return plan.oracles().isEmpty();
    }

    /**
     * {@code approve}'s write for a plan whose bindings hold images only (Phase 7 security fix, S115;
     * phase7-contracts.md 8.7, D362 as amended): nothing was asked or written at bind time, and after the steps
     * <ul>
     *   <li>with a baseline a person approved at its step, the file is written with it beside the old entries, as the
     *     second write of D355 does; when an existing file would also change for another reason (its environment,
     *     plan hash, resolve hash, pack, resolutions or oracles), that change is written only when the approver
     *     approves it (one {@link Approver#review} with no resolution and no oracle, the difference against the
     *     existing file shown); refused, the approved baselines go into the existing file as it is and the run ends
     *     RESOLUTION_FAILED / NOT_APPROVED;</li>
     *   <li>without one, after steps that did not fail (or a bind alone, {@code skillroy resolve}), a file that is
     *     absent or would change is written only when the approver approves it, refused it is NOT_APPROVED and
     *     nothing is written; after a failed step nothing is written.</li>
     * </ul>
     * So {@code REJECT_ALL} (an MCP client's server, a terminal-less run) writes nothing, {@code --yes} refreshes the
     * file of an environment that changed while its baselines still hold, and the terminal approver asks once at the
     * end. The text passes {@link #requireNoCredential}; an identical file is left untouched.
     *
     * @param bound the file the bind built and did not write: the plan's header with the existing file's images
     * @param baselines this run's baselines with the approvals made at their steps
     * @param stepsFailed whether the run already failed (a step, or a check after the steps)
     * @return the file written, or null when nothing was written
     * @throws BindException ENVIRONMENT when the existing file cannot be used or a file cannot be written, or a value
     *     equals a credential; RESOLUTION_FAILED / NOT_APPROVED when the approver refused a change
     */
    static Path writeImagesOnly(Plan plan, BindOptions options, BindingsFile bound, ImageBaselines baselines,
                                boolean stepsFailed) throws BindException {
        Path root = options.bindingsRoot();
        if (root == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, null, "writing the bindings file needs a bindings"
                    + " root", null);
        }
        String relative = options.envId() + "/" + plan.scenarioId() + BindingsFile.SUFFIX;
        Path target = BindingsFile.locate(root, options.envId(), plan.scenarioId());
        BindingsFile existing = null;
        String existingText = null;
        if (Files.exists(target)) {
            String why;
            try {
                existingText = Files.readString(target, StandardCharsets.UTF_8);
                existing = BindingsFile.parse(existingText);
                why = existing.scenarioId().equals(plan.scenarioId()) && existing.envId().equals(options.envId())
                        ? null : "it was made for scenario " + existing.scenarioId() + " in environment "
                        + existing.envId();
            } catch (IOException | BindingsFormatException e) {
                why = "cannot be read: " + e.getMessage();
            }
            if (why != null) {
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "the bindings file " + relative + " "
                        + why + "; approve never replaces a bindings file it cannot read", RunLog.fields(
                        "bindingsFile", relative));
            }
        }
        boolean approvals = baselines != null && baselines.hasApprovals();
        Map<String, Map<String, BindingsFile.ImageEntry>> merged = baselines == null ? bound.images()
                : baselines.merged();
        BindingsFile candidate = bound.withImages(merged);
        String candidateText = candidate.toJsonText();
        boolean otherChange = existing != null && !existing.withImages(merged).toJsonText().equals(candidateText);
        if (!approvals) {
            if (stepsFailed || candidateText.equals(existingText)) {
                return null;
            }
            otherChange = true;
        }
        if (otherChange) {
            Approver.ApprovalRequest request = new Approver.ApprovalRequest(plan.scenarioId(), options.envId(),
                    bound.env(), List.of(), List.of(), existing == null ? null : existing.diff(candidate), List.of());
            Approver.Decision decision;
            try {
                decision = options.approver().review(request);
            } catch (RuntimeException e) {
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "the approver failed: " + e, null);
            }
            if (decision == null || !decision.approved()) {
                String reason = decision == null ? "the approver gave no decision" : decision.reason();
                if (approvals) {
                    writeImagesFile(plan, options, existing.withImages(merged), baselines, target, existingText,
                            relative);
                }
                throw new BindException(FailureKind.RESOLUTION_FAILED, "NOT_APPROVED", null, "the bindings file "
                        + relative + " was not " + (existing == null ? "written" : "rewritten for this run's"
                        + " environment and plan") + ": not approved" + (reason == null ? "" : " (" + reason + ")")
                        + (approvals ? "; the baselines approved at their steps were written into it as it was" : ""),
                        RunLog.fields("bindingsFile", relative, "message", reason));
            }
        }
        return writeImagesFile(plan, options, candidate, approvals ? baselines : null, target, existingText,
                relative);
    }

    /** Writes the PNGs approved in this run (when given) and then the file, unless its text is already there. */
    private static Path writeImagesFile(Plan plan, BindOptions options, BindingsFile file, ImageBaselines baselines,
                                        Path target, String existingText, String relative) throws BindException {
        String text = file.toJsonText();
        requireNoCredential(plan, options, text);
        try {
            if (baselines != null) {
                baselines.writeApprovedPngs();
            }
            if (text.equals(existingText)) {
                return null;
            }
            BindingsFile.writeText(target, text);
            return target;
        } catch (IOException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, null, "the bindings file " + relative
                    + " or a baseline beside it could not be written: " + e.getMessage(),
                    RunLog.fields("bindingsFile", relative));
        }
    }

    /** The plan's fixture slots and their values as written, for A25's key rule. */
    private static Map<String, Object> fixtureValues(Plan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Plan.Slot slot : plan.slots()) {
            if ("fixture".equals(slot.kind())) {
                out.put(slot.name(), slot.value());
            }
        }
        return out;
    }

    /**
     * One full bind. The graph walk of {@code live}, {@code approve} and {@code re-resolve} keeps its place
     * ({@link #position}), so that a stepwise bind parked at a query slot resumes there.
     */
    private static final class Run implements BindStep.Parked.Continuation {

        private final Plan plan;
        private final ResolutionEngine engine;
        private final AgentConnection agent;
        private final BindOptions options;
        private final BindListener listener;
        /** True for a stepwise bind: a batchable resolver may park its slot (re-resolve only). */
        private final boolean mayPark;
        /** The target's capability states for guarded cross-checks; read on first need when not given. */
        private CapabilityMatrix caps;
        /** The lines of the guarded cross-checks skipped so far (6.2). */
        private final List<String> skipped = new ArrayList<>();
        /** The fingerprint of this bind, once computed. */
        private EnvFingerprint env;
        /** How long the fingerprint took (the {@code fingerprint} record's {@code durationMs}, Phase 6). */
        private long fingerprintMs;
        /** The index of the resolution graph node the walk visits next. */
        private int position;
        /** approve's reuse (P5 7.1): the existing bindings file, read once on first need; null when none is usable. */
        private BindingsFile existing;
        private boolean existingRead;
        /** Why a file at the derived path is not usable (S116), or null. */
        private String existingProblem;

        Run(Plan plan, ResolutionEngine engine, AgentConnection agent, CapabilityMatrix caps, BindOptions options,
            BindListener listener, boolean mayPark) {
            this.plan = plan;
            this.engine = engine;
            this.agent = agent;
            this.caps = caps;
            this.options = options;
            this.listener = listener;
            this.mayPark = mayPark;
        }

        BindStep start() throws BindException, AgentLostException, InterruptedException {
            if (!plan.needsBindings()) {
                return new BindStep.Done(withoutBindings());
            }
            checkCatalogue();
            long f0 = options.clock().millis();
            env = engine.fingerprint();
            fingerprintMs = Math.max(0L, options.clock().millis() - f0);
            if (options.mode() == BindMode.REPLAY) {
                return new BindStep.Done(replay(env));
            }
            fingerprintEvent(env, null);
            return walk();
        }

        /** Resumes the walk at the parked slot; the engine is closed when the bind ends or fails. */
        @Override
        public BindStep resume(Object answer) throws BindException, AgentLostException, InterruptedException {
            BindStep step = null;
            try {
                Plan.Slot slot = plan.slot(plan.resolutionGraph().get(position));
                engine.slot(slot.name());
                ResolutionEngine.Parking parking;
                try {
                    parking = engine.resumeSlot(answer);
                } catch (BindException e) {
                    throw upstream(slot, e);
                }
                if (parking != null) {
                    step = parked(parking);
                    return step;
                }
                position++;
                step = walk();
                return step;
            } finally {
                if (!(step instanceof BindStep.Parked)) {
                    engine.close();
                }
            }
        }

        /** The job will not resume this bind: its engine is released. */
        @Override
        public void abandon() {
            engine.close();
        }

        /** A plan without query slots or oracles: Phase 1 fixtures plus the credential join (8.1). */
        private BindOutcome withoutBindings() throws BindException {
            Bindings fixtures;
            try {
                fixtures = bindFixtures(plan, options.envId());
            } catch (ResolutionException e) {
                throw reference(e, null);
            }
            for (Map.Entry<String, Object> e : fixtures.values().entrySet()) {
                engine.bindFixture(e.getKey(), e.getValue());
            }
            joinAndCheck();
            return new BindOutcome(engine.bindings(), null, null, null);
        }

        /** 8.3 step 1: what the plan names must exist in the running pack's catalogue. */
        private void checkCatalogue() throws BindException {
            ResolutionCatalogue catalogue = engine.catalogue();
            DomainPack pack = engine.pack();
            for (Plan.Slot slot : plan.slots()) {
                if (!"query".equals(slot.kind())) {
                    continue;
                }
                QueryType<?, ?> type = catalogue.query(slot.query().query());
                if (type == null) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, slot.name(), "the plan names query kind "
                            + slot.query().query() + ", which pack " + pack.id() + " " + pack.version()
                            + " does not have; recompile", null);
                }
                for (String id : slot.query().verify()) {
                    if (catalogue.verifier(type.kind(), id) == null) {
                        throw new BindException(FailureKind.ENVIRONMENT, null, slot.name(), "the plan names verifier "
                                + id + " for kind " + type.kind() + ", which pack " + pack.id() + " "
                                + pack.version() + " does not have; recompile", null);
                    }
                }
            }
            for (Plan.Oracle oracle : plan.oracles()) {
                if (catalogue.oracle(oracle.fn()) == null) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, "oracle." + oracle.name(), "the plan names"
                            + " oracle function " + oracle.fn() + ", which pack " + pack.id() + " " + pack.version()
                            + " does not have; recompile", null);
                }
                if (oracle.crossCheck() != null) {
                    engine.crossCheckOf("oracle." + oracle.name(), oracle.fn(), oracle.crossCheck());
                }
            }
        }

        // -------------------------------------------------------------------------------------- replay

        private BindOutcome replay(EnvFingerprint env) throws BindException, AgentLostException,
                InterruptedException {
            Path root = options.bindingsRoot();
            String relative = options.envId() + "/" + plan.scenarioId() + BindingsFile.SUFFIX;
            Map<String, Object> where = RunLog.fields("bindingsFile", relative);
            if (root == null || !Files.isRegularFile(BindingsFile.locate(root, options.envId(),
                    plan.scenarioId()))) {
                fingerprintEvent(env, null);
                throw new BindException(FailureKind.REBIND_NEEDED, "NO_BINDINGS", null, root == null
                        ? "no bindings root was given; scenario " + plan.scenarioId() + " needs bindings for"
                        + " environment " + options.envId()
                        : "no bindings file " + relative + " for environment " + options.envId(), where);
            }
            BindingsFile file;
            try {
                file = BindingsFile.read(root, options.envId(), plan.scenarioId());
            } catch (IOException | BindingsFormatException e) {
                fingerprintEvent(env, null);
                throw unreadable(relative, "cannot be read: " + e.getMessage());
            }
            fingerprintEvent(env, file.env().hash());
            checkFile(file, relative);
            checkImages(file, relative, true);
            if (!file.resolveHash().equals(plan.resolveHash())) {
                Map<String, Object> d = new LinkedHashMap<>(where);
                d.put("expected", plan.resolveHash());
                d.put("actual", file.resolveHash());
                throw new BindException(FailureKind.REBIND_NEEDED, "PLAN_CHANGED", null, "the bindings file "
                        + relative + " was made for another version of the scenario's resolve and oracle sections",
                        d);
            }
            checkQueryKinds(file, relative);
            if (!file.env().hash().equals(env.hash())) {
                List<Object> changed = new ArrayList<>();
                Set<String> names = new LinkedHashSet<>(file.env().components().keySet());
                names.addAll(env.components().keySet());
                List<String> changedNames = new ArrayList<>();
                for (String name : names) {
                    String recorded = file.env().components().get(name);
                    String actual = env.components().get(name);
                    if (!Objects.equals(recorded, actual)) {
                        changed.add(RunLog.fields("name", name, "recorded", recorded, "actual", actual));
                        changedNames.add(name);
                    }
                }
                Map<String, Object> d = new LinkedHashMap<>(where);
                d.put("expected", file.env().hash());
                d.put("actual", env.hash());
                d.put("components", changed);
                throw new BindException(FailureKind.REBIND_NEEDED, "FINGERPRINT", null, "this environment is not"
                        + " the one the bindings of " + options.envId() + " were made for; changed: "
                        + String.join(", ", changedNames), d);
            }
            for (String node : plan.resolutionGraph()) {
                if (node.startsWith("oracle.")) {
                    Plan.Oracle oracle = plan.oracle(node.substring("oracle.".length()));
                    OracleValue recorded = file.oracles().get(oracle.name());
                    OracleValue value = oracle(oracle, recorded, relative);
                    crossCheck(oracle, value);
                    guard(oracle, value, BindMode.REPLAY);
                    continue;
                }
                Plan.Slot slot = plan.slot(node);
                engine.slot(slot.name());
                if ("fixture".equals(slot.kind())) {
                    fixture(slot);
                    continue;
                }
                Resolution recorded = file.resolutions().get(slot.name());
                if (recorded.strategy().needsModel() && (recorded.approval() == null
                        || recorded.approval().state() != Approval.State.APPROVED)) {
                    // P5 7.1, D72: a value a model found replays only once a person (or the re-resolve job's review)
                    // approved it; nothing of the slot runs before.
                    Map<String, Object> d = new LinkedHashMap<>(where);
                    d.put("strategy", recorded.strategy().name());
                    d.put("approval", RunLog.fields("state", recorded.approval() == null ? null
                            : recorded.approval().state().name(), "by", recorded.approval() == null ? null
                            : recorded.approval().by()));
                    throw new BindException(FailureKind.REBIND_NEEDED, "NOT_APPROVED", slot.name(), "slot '"
                            + slot.name() + "': its recorded value was found by a model (" + recorded.strategy()
                            + ") and is not approved (" + (recorded.approval() == null ? "no approval"
                            : recorded.approval().state()) + " in " + relative + "); a person approves it with"
                            + " skillroy resolve", d);
                }
                Map<String, Object> args = args(slot);
                String argsHash = ResolutionEngine.hash(args);
                if (!argsHash.equals(recorded.argsHash())) {
                    Map<String, Object> d = new LinkedHashMap<>(where);
                    d.put("expected", recorded.argsHash());
                    d.put("actual", argsHash);
                    throw new BindException(FailureKind.REBIND_NEEDED, "ARGS_CHANGED", slot.name(), "slot '"
                            + slot.name() + "': its arguments differ from the recorded ones (an upstream value or the"
                            + " file changed)", d);
                }
                List<VerifierResult> results = engine.verifyRecorded(slot.name(), slot.query().query(), args,
                        recorded.value(), slot.query().verify());
                VerifierResult last = results.get(results.size() - 1);
                if (!last.passed()) {
                    Map<String, Object> d = new LinkedHashMap<>(where);
                    d.put("verifier", RunLog.fields("id", last.verifierId(), "reason", last.reason(), "evidence",
                            last.evidence()));
                    throw new BindException(FailureKind.REBIND_NEEDED, "VERIFIER", slot.name(), "slot '"
                            + slot.name() + "': verifier " + last.verifierId() + " refuses the recorded value: "
                            + last.reason(), d);
                }
                CredentialRef credential = recorded.credential();
                if (slot.credential() != null) {
                    String id = engine.credentialId(slot.name(), recorded.value(), slot.credential());
                    if (credential == null || !credential.realm().equals(slot.credential().realm())
                            || !credential.id().equals(id)) {
                        throw unreadable(relative, "slot '" + slot.name() + "': its credential does not match its"
                                + " value");
                    }
                    if (!options.credentials().has(credential.realm(), credential.id())) {
                        throw new BindException(FailureKind.ENVIRONMENT, null, slot.name(), "slot '" + slot.name()
                                + "' needs the credential " + credential + ", which the credentials ("
                                + options.credentials().origin() + ") do not have",
                                RunLog.fields("credential", RunLog.fields("realm", credential.realm(), "id",
                                        credential.id())));
                    }
                } else if (credential != null) {
                    throw unreadable(relative, "slot '" + slot.name() + "' records a credential the plan does not"
                            + " declare");
                }
                Resolution replayed = new Resolution(recorded.slot(), recorded.query(), recorded.args(),
                        recorded.argsHash(), recorded.value(), recorded.strategy(), recorded.provenance(), results,
                        recorded.confidence(), recorded.credential(), recorded.approval(), recorded.usage());
                engine.bindRecorded(replayed);
                listener.event("resolve", engine.resolveRecord(replayed, recorded.strategy()));
            }
            joinAndCheck();
            return new BindOutcome(engine.bindings(), file, env, null, skipped);
        }

        /** 8.3 replay step 3: the file belongs to this scenario and environment and is internally consistent. */
        private void checkFile(BindingsFile file, String relative) throws BindException {
            if (!file.scenarioId().equals(plan.scenarioId()) || !file.envId().equals(options.envId())) {
                throw unreadable(relative, "it was made for scenario " + file.scenarioId() + " in environment "
                        + file.envId() + " (copied to the wrong place?)");
            }
            Set<String> slots = new LinkedHashSet<>();
            for (Plan.Slot slot : plan.slots()) {
                if ("query".equals(slot.kind())) {
                    slots.add(slot.name());
                }
            }
            if (!slots.equals(file.resolutions().keySet())) {
                throw unreadable(relative, "it records the query slots " + file.resolutions().keySet()
                        + " but the plan has " + slots);
            }
            Set<String> oracles = new LinkedHashSet<>();
            for (Plan.Oracle oracle : plan.oracles()) {
                oracles.add(oracle.name());
            }
            if (!oracles.equals(file.oracles().keySet())) {
                throw unreadable(relative, "it records the oracles " + file.oracles().keySet() + " but the plan has "
                        + oracles);
            }
            if (!ResolutionEngine.hash(new LinkedHashMap<>(file.env().components())).equals(file.env().hash())) {
                throw unreadable(relative, "its env.fingerprint is not the hash of its env.components (edited by"
                        + " hand?)");
            }
        }

        /**
         * phase7-contracts.md 8.7 (D355): every entry's {@code file} is the derived name (a mismatch is ERROR /
         * ENVIRONMENT: a planted or hand-edited file never names a path to open) and, in {@code replay}, every label of
         * the plan's {@code images} has at least one entry (any render key; REBIND_NEEDED reason {@code IMAGE}
         * otherwise); an {@code images} event reports the keys present.
         */
        private void checkImages(BindingsFile file, String relative, boolean requireLabels) throws BindException {
            for (Map.Entry<String, Map<String, BindingsFile.ImageEntry>> e : file.images().entrySet()) {
                for (Map.Entry<String, BindingsFile.ImageEntry> k : e.getValue().entrySet()) {
                    String derived = BindingsFile.ImageEntry.fileName(plan.scenarioId(), e.getKey(), k.getKey());
                    if (!derived.equals(k.getValue().file())) {
                        throw new BindException(FailureKind.ENVIRONMENT, null, null, "the bindings file " + relative
                                + ": images." + e.getKey() + "." + k.getKey() + " names the file '"
                                + k.getValue().file() + "', not " + derived + " (edited by hand?)",
                                RunLog.fields("bindingsFile", relative, "label", e.getKey(), "renderKey", k.getKey()));
                    }
                }
            }
            if (plan.images().isEmpty()) {
                return;
            }
            Map<String, Object> keys = new LinkedHashMap<>();
            for (String label : plan.images()) {
                Map<String, BindingsFile.ImageEntry> byKey = file.images().get(label);
                keys.put(label, byKey == null ? List.of() : new ArrayList<>(byKey.keySet()));
            }
            listener.event("images", RunLog.fields("bindingsFile", relative, "keys", keys));
            if (!requireLabels) {
                return;
            }
            for (String label : plan.images()) {
                if (!file.images().containsKey(label)) {
                    throw new BindException(FailureKind.REBIND_NEEDED, "IMAGE", null, "the bindings file " + relative
                            + " has no baseline or anchor '" + label + "' for any rendering; approve the scenario (a"
                            + " baseline) or re-record the step (an anchor)", RunLog.fields("bindingsFile", relative,
                            "label", label));
                }
            }
        }

        /**
         * After the {@code resolveHash} comparison (8.3 step 4): a plan whose slot asks another query kind changed
         * its resolve section, which is PLAN_CHANGED; a kind that differs although the hash matches can only be a
         * hand edit of the file.
         */
        private void checkQueryKinds(BindingsFile file, String relative) throws BindException {
            for (Resolution resolution : file.resolutions().values()) {
                Plan.Slot slot = plan.slot(resolution.slot());
                if (!slot.query().query().equals(resolution.query())) {
                    throw unreadable(relative, "slot '" + slot.name() + "' records query " + resolution.query()
                            + " but the plan asks " + slot.query().query() + " (edited by hand?)");
                }
            }
        }

        private BindException unreadable(String relative, String why) {
            return new BindException(FailureKind.REBIND_NEEDED, "UNREADABLE", null, "the bindings file " + relative
                    + " " + why, RunLog.fields("bindingsFile", relative));
        }

        private void fingerprintEvent(EnvFingerprint env, String recorded) {
            Map<String, Object> fields = RunLog.fields("hash", env.hash(), "components",
                    new LinkedHashMap<>(env.components()));
            if (options.mode() == BindMode.REPLAY) {
                fields.put("recorded", recorded);
            }
            fields.put("durationMs", fingerprintMs);
            listener.event("fingerprint", fields);
        }

        // -------------------------------------------------------------------------------------- live, approve, re-resolve

        /**
         * 8.3 live steps 4 and 5 from the current node: fixtures, query slots (which may park in a stepwise
         * {@code re-resolve} bind) and oracles in graph order; then the end of the bind.
         */
        private BindStep walk() throws BindException, AgentLostException, InterruptedException {
            List<String> graph = plan.resolutionGraph();
            while (position < graph.size()) {
                String name = graph.get(position);
                if (name.startsWith("oracle.")) {
                    Plan.Oracle oracle = plan.oracle(name.substring("oracle.".length()));
                    OracleValue value = oracle(oracle, null, null);
                    crossCheck(oracle, value);
                    guard(oracle, value, options.mode());
                    position++;
                    continue;
                }
                Plan.Slot slot = plan.slot(name);
                engine.slot(slot.name());
                if ("fixture".equals(slot.kind())) {
                    fixture(slot);
                    position++;
                    continue;
                }
                ResolutionEngine.Parking parking = query(slot, args(slot));
                if (parking != null) {
                    return parked(parking);
                }
                position++;
            }
            return new BindStep.Done(finish());
        }

        /**
         * One query slot: in {@code approve} with reuse on, a recorded tier (b) value that still holds is taken without
         * asking a resolver (P5 7.1, D82); otherwise the ladder.
         *
         * @return null when the slot is resolved, or where it parked
         */
        private ResolutionEngine.Parking query(Plan.Slot slot, Map<String, Object> args)
                throws BindException, AgentLostException, InterruptedException {
            String notReused = null;
            if (options.mode() == BindMode.APPROVE && options.reuseBindings()) {
                List<String> why = new ArrayList<>();
                if (reuse(slot, args, why)) {
                    return null;
                }
                notReused = why.isEmpty() ? null : "not reused from the bindings file: " + why.get(0);
            }
            try {
                ResolutionEngine.Parking parking = engine.startSlot(slot.name(), slot.query().query(), args,
                        slot.query().strategy(), slot.query().verify(), slot.credential(), mayPark);
                if (parking == null && notReused != null) {
                    engine.addNote(slot.name(), notReused);
                }
                return parking;
            } catch (BindException e) {
                throw upstream(slot, e);
            }
        }

        /**
         * approve's reuse (P5 7.1, D82): the existing file is readable and belongs here, its {@code resolveHash} equals
         * the plan's, the slot's recorded value was found by a model (tier (b) or (c); tier (a) is never reused,
         * recomputing it is free), its {@code argsHash} equals the one just computed, and every verifier passes it now
         * (the engine checks that and the credential): then it is the slot's resolution and no resolver is asked.
         *
         * @param why receives the reason a recorded tier (b) value was not reused (nothing for a slot without one)
         */
        private boolean reuse(Plan.Slot slot, Map<String, Object> args, List<String> why)
                throws BindException, AgentLostException, InterruptedException {
            BindingsFile file = existing();
            Resolution recorded = file == null ? null : file.resolutions().get(slot.name());
            if (recorded == null || !recorded.strategy().needsModel()) {
                return false;
            }
            if (!file.resolveHash().equals(plan.resolveHash())) {
                why.add("the scenario's resolve section changed since it was written");
                return false;
            }
            if (!recorded.query().equals(slot.query().query())) {
                why.add("it records another query kind");
                return false;
            }
            if (!ResolutionEngine.hash(args).equals(recorded.argsHash())) {
                why.add("the slot's arguments changed");
                return false;
            }
            return engine.reuse(slot.name(), slot.query().query(), args, recorded, slot.query().verify(),
                    slot.credential(), why) != null;
        }

        /**
         * The existing bindings file of this scenario and environment, read once; null when absent. A file at the
         * derived path that cannot be read, or that names another scenario or environment, is not absent (Phase 7
         * security fix, S116; phase7-contracts.md 8.7, P2 8.3 approve step 8): a bind that would write the file
         * ({@code approve}, {@code live} or {@code re-resolve} with {@code writeBindings}), or whose plan has images
         * (their baselines come from the file), ends ERROR / ENVIRONMENT naming the file and the reason, as
         * {@code replay} refuses it, instead of rewriting it without its images; any other bind reads nothing from it.
         *
         * @throws BindException ENVIRONMENT when the file is unusable and the bind writes it or reads its images
         */
        BindingsFile existing() throws BindException {
            if (!existingRead) {
                existingRead = true;
                Path root = options.bindingsRoot();
                Path path = root == null ? null : BindingsFile.locate(root, options.envId(), plan.scenarioId());
                // Gap 99: a link at the file's position, even a dangling one, is a file that cannot be read.
                if (path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    String why;
                    try {
                        BindingsFile file = BindingsFile.read(root, options.envId(), plan.scenarioId());
                        if (file.scenarioId().equals(plan.scenarioId()) && file.envId().equals(options.envId())) {
                            existing = file;
                            why = null;
                        } else {
                            why = "it was made for scenario " + file.scenarioId() + " in environment "
                                    + file.envId() + " (copied to the wrong place?)";
                        }
                    } catch (IOException | BindingsFormatException e) {
                        why = "cannot be read: " + e.getMessage();
                    }
                    if (why != null) {
                        existingProblem = why;
                    }
                }
            }
            boolean writes = options.mode() == BindMode.APPROVE || options.writeBindings();
            if (existingProblem != null && (writes || !plan.images().isEmpty())) {
                String relative = options.envId() + "/" + plan.scenarioId() + BindingsFile.SUFFIX;
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "the bindings file " + relative + " "
                        + existingProblem + "; " + options.mode().label() + " never replaces a bindings file it"
                        + " cannot read (its baselines and anchors would be dropped): repair or remove it first",
                        RunLog.fields("bindingsFile", relative, "mode", options.mode().label()));
            }
            return existing;
        }

        /** A stepwise bind's stop: the continuation is this run; the engine stays open. */
        private BindStep.Parked parked(ResolutionEngine.Parking parking) {
            return new BindStep.Parked(plan.scenarioId(), parking.slot(), parking.kind(), parking.resolverId(),
                    parking.request(), this);
        }

        /** NO_CANDIDATE and ALL_REJECTED name the already-bound slots the failing one depended on (D5). */
        private BindException upstream(Plan.Slot slot, BindException e) {
            if ("NO_CANDIDATE".equals(e.reason()) || "ALL_REJECTED".equals(e.reason())) {
                return withUpstream(e, references(slot.query().args()));
            }
            return e;
        }

        /**
         * 8.3 live steps 6 and 7, approve steps 7 and 8, P5 8.2 step 4: the join and the reference check, then the
         * file: {@code approve} asks the approver and writes; {@code re-resolve} approves every slot by the job's label
         * and writes unless it is a dry run; {@code live} writes only with {@code writeBindings}.
         */
        private BindOutcome finish() throws BindException {
            joinAndCheck();
            BindingsFile file = file(env);
            Path written = null;
            if (options.mode() == BindMode.APPROVE) {
                file = approve(file, env);
                if (!nothingToApprove()) {
                    written = write(file);
                }
                // Phase 7 security fix, S115 (phase7-contracts.md 8.7, D362 as amended): a plan whose bindings hold
                // images only asks nothing and writes nothing at bind time; after the steps the executor writes the
                // file with the baselines a person approved, and any other change only when the approver approves it
                // (Binder.writeImagesOnly).
            } else if (options.mode() == BindMode.RE_RESOLVE) {
                String by = options.approvedBy() != null ? options.approvedBy() : options.mode().label();
                file = approved(file, new Approval(Approval.State.APPROVED, by));
                if (options.writeBindings()) {
                    written = write(file);
                } else {
                    requireNoCredential(plan, options, file.toJsonText());
                }
            } else if (options.writeBindings()) {
                written = write(file);
            }
            return new BindOutcome(engine.bindings(), file, env, written, skipped);
        }

        /**
         * The file of a {@code live} bind (and the starting point of the others): a tier (a) slot {@code NOT_REQUIRED}
         * by {@code approvedBy} or the mode's name; a value a model found {@code REQUIRED}, by nobody (P5 7.1).
         */
        private BindingsFile file(EnvFingerprint env) throws BindException {
            String by = options.approvedBy() != null ? options.approvedBy() : options.mode().label();
            Map<String, Resolution> resolutions = new LinkedHashMap<>();
            for (Resolution r : engine.resolutions().values()) {
                Approval approval = r.strategy().needsModel() ? new Approval(Approval.State.REQUIRED, null)
                        : new Approval(Approval.State.NOT_REQUIRED, by);
                Resolution withApproval = r.withApproval(approval);
                resolutions.put(r.slot(), withApproval);
                engine.replaceResolution(withApproval);
            }
            // phase7-contracts.md 8.7 (D355): a slot approval or a re-resolve never drops a baseline or an anchor.
            BindingsFile old = existing();
            return new BindingsFile(BindingsFile.VERSION, plan.scenarioId(), options.envId(), plan.pack(),
                    plan.packVersion(), plan.hash(), plan.resolveHash(), env, resolutions, engine.oracleValues(),
                    old == null ? Map.of() : old.images());
        }

        /** The file with every resolution under {@code approval}, kept in the engine too. */
        private BindingsFile approved(BindingsFile file, Approval approval) {
            Map<String, Resolution> resolutions = new LinkedHashMap<>();
            for (Resolution r : file.resolutions().values()) {
                Resolution approved = r.withApproval(approval);
                resolutions.put(r.slot(), approved);
                engine.replaceResolution(approved);
            }
            return new BindingsFile(file.bindingsVersion(), file.scenarioId(), file.envId(), file.pack(),
                    file.packVersion(), file.planHash(), file.resolveHash(), file.env(), resolutions, file.oracles(),
                    file.images());
        }

        /**
         * 8.3 approve step 7: the approver is asked once; its time is outside the budget. A plan whose bindings hold
         * only images (no query slot, no oracle; phase7-contracts.md 8.7) has nothing to approve at bind time: its
         * baselines are approved at their steps ({@link Approver#reviewImage}).
         */
        private BindingsFile approve(BindingsFile file, EnvFingerprint env) throws BindException {
            if (nothingToApprove()) {
                return file;
            }
            BindingsDiff diff = null;
            Path root = options.bindingsRoot();
            if (root != null) {
                Path existing = BindingsFile.locate(root, options.envId(), plan.scenarioId());
                if (Files.isRegularFile(existing)) {
                    try {
                        diff = BindingsFile.read(root, options.envId(), plan.scenarioId()).diff(file);
                    } catch (IOException | BindingsFormatException e) {
                        diff = null;
                    }
                }
            }
            Approver.ApprovalRequest request = new Approver.ApprovalRequest(plan.scenarioId(), options.envId(), env,
                    new ArrayList<>(engine.resolutions().values()), new ArrayList<>(engine.oracleValues().values()),
                    diff, new ArrayList<>(engine.crossCheckOutcomes().values()));
            long t0 = options.clock().millis();
            Approver.Decision decision;
            try {
                decision = options.approver().review(request);
            } catch (RuntimeException e) {
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "the approver failed: " + e, null);
            } finally {
                engine.pause(options.clock().millis() - t0);
            }
            if (decision == null || !decision.approved()) {
                String reason = decision == null ? "the approver gave no decision" : decision.reason();
                throw new BindException(FailureKind.RESOLUTION_FAILED, "NOT_APPROVED", null, "the bindings of "
                        + plan.scenarioId() + " in " + options.envId() + " were not approved"
                        + (reason == null ? "" : ": " + reason), RunLog.fields("message", reason));
            }
            String by = options.approvedBy() != null ? options.approvedBy()
                    : decision.by() != null ? decision.by() : options.mode().label();
            return approved(file, new Approval(Approval.State.APPROVED, by));
        }

        /** No resolution and no oracle: the plan's bindings hold images only (phase7-contracts.md 8.7, D362). */
        private boolean nothingToApprove() {
            return engine.resolutions().isEmpty() && engine.oracleValues().isEmpty();
        }

        /** 8.3 approve step 8. */
        private Path write(BindingsFile file) throws BindException {
            Path root = options.bindingsRoot();
            if (root == null) {
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "writing the bindings file needs a"
                        + " bindings root", null);
            }
            String text = file.toJsonText();
            requireNoCredential(plan, options, text);
            Path target = BindingsFile.locate(root, options.envId(), plan.scenarioId());
            try {
                if (Files.isRegularFile(target) && Files.readString(target, StandardCharsets.UTF_8).equals(text)) {
                    return null;
                }
                BindingsFile.writeText(target, text);
                return target;
            } catch (IOException e) {
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "the bindings file "
                        + options.envId() + "/" + plan.scenarioId() + BindingsFile.SUFFIX + " could not be written: "
                        + e.getClass().getSimpleName(), null);
            }
        }

        // -------------------------------------------------------------------------------------- shared

        private void fixture(Plan.Slot slot) throws BindException {
            try {
                engine.bindFixture(slot.name(), engine.current().substitutePublic(slot.value()));
            } catch (ResolutionException e) {
                throw reference(e, slot.name());
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> args(Plan.Slot slot) throws BindException {
            try {
                return (Map<String, Object>) engine.current().substitutePublic(slot.query().args());
            } catch (ResolutionException e) {
                throw reference(e, slot.name());
            }
        }

        /** 8.3 step 7 (and live step 5): substitute, hash, compute; in replay compare with the file. */
        private OracleValue oracle(Plan.Oracle oracle, OracleValue recorded, String relative)
                throws BindException {
            String node = "oracle." + oracle.name();
            engine.slot(node);
            Object args;
            Object mustEqual = null;
            try {
                args = engine.current().substitutePublic(oracle.args());
                if (oracle.hasMustEqual()) {
                    mustEqual = engine.current().substitutePublic(oracle.mustEqual());
                }
            } catch (ResolutionException e) {
                throw reference(e, node);
            }
            String inputsHash = ResolutionEngine.hash(args);
            if (recorded != null && !inputsHash.equals(recorded.inputsHash())) {
                throw new BindException(FailureKind.REBIND_NEEDED, "ARGS_CHANGED", node, "oracle '" + oracle.name()
                        + "': its inputs differ from the recorded ones", RunLog.fields("bindingsFile", relative,
                        "expected", recorded.inputsHash(), "actual", inputsHash));
            }
            long o0 = options.clock().millis();
            Object value = engine.evaluate(oracle.fn(), args);
            long oracleMs = Math.max(0L, options.clock().millis() - o0);
            Map<String, Object> event = RunLog.fields("name", oracle.name(), "fn", oracle.fn(), "value", value);
            if (options.mode() == BindMode.REPLAY) {
                event.put("recorded", recorded == null ? null : recorded.value());
            }
            event.put("durationMs", oracleMs);
            listener.event("oracle", event);
            if (recorded != null && !Json.writeCanonical(value).equals(Json.writeCanonical(recorded.value()))) {
                throw new BindException(FailureKind.REBIND_NEEDED, "ORACLE_CHANGED", node, "oracle '"
                        + oracle.name() + "' (" + oracle.fn() + ") now computes another value than recorded",
                        RunLog.fields("bindingsFile", relative, "expected", recorded.value(), "actual", value));
            }
            OracleValue result = new OracleValue(oracle.name(), oracle.fn(), inputsHash, value, mustEqual,
                    oracle.crossCheck());
            engine.bindOracle(result, args);
            return result;
        }

        /**
         * Step 8 (phase3-contracts.md 6.1), every mode: a guarded check whose capability is not OK is skipped and
         * recorded (8a); otherwise the check runs through {@link ResolutionEngine#crossCheck} (8b), and a
         * disagreement is ORACLE_SUSPECT, reason DISAGREED (8c). What the check throws is the engine's ENVIRONMENT
         * (8d).
         */
        private void crossCheck(Plan.Oracle oracle, OracleValue value) throws BindException, AgentLostException,
                InterruptedException {
            Plan.CrossCheckSpec spec = oracle.crossCheck();
            if (spec == null) {
                return;
            }
            String node = "oracle." + oracle.name();
            engine.slot(node);
            if (spec.capability() != null) {
                Capability capability = capabilities().get(spec.capability());
                if (capability == null || capability.state() != CapabilityState.OK) {
                    String state = capability == null ? CapabilityState.UNAVAILABLE.name()
                            : capability.state().name();
                    String reason = capability == null ? "the target does not declare it" : capability.reason();
                    skipped.add(engine.skipCrossCheck(oracle.name(), state, reason));
                    return;
                }
            }
            CrossCheckResult result = engine.crossCheck(oracle.name());
            if (result.agreed()) {
                return;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fn", oracle.fn());
            detail.put("check", spec.check());
            detail.put("expected", value.value());
            detail.put("actual", result.actual());
            detail.put("tolerance", spec.tolerance());
            detail.put("message", result.reason());
            detail.put("evidence", result.evidence());
            detail.put("inputsHash", value.inputsHash());
            detail.put("mode", options.mode().label());
            detail.put("envId", options.envId());
            BindException e = new BindException(FailureKind.ORACLE_SUSPECT, "DISAGREED", node, Values.printableLine(
                    "oracle " + oracle.name() + " (" + oracle.fn() + ") disagrees with " + spec.check()
                            + ": expected " + Values.brief(value.value(), 300) + ", the application computes "
                            + Values.brief(result.actual(), 300) + (result.reason() == null ? ""
                            : " (" + result.reason() + ")")), detail);
            throw withUpstream(e, References.collect(oracle.args()));
        }

        /** The target's capability states: the run's, else one {@code agent.capabilities} read for this bind. */
        private CapabilityMatrix capabilities() throws AgentLostException {
            if (caps == null) {
                CapabilityMatrix read = agent.capabilities();
                caps = read == null ? new CapabilityMatrix(Map.of()) : read;
            }
            return caps;
        }

        /** 8.3 step 9: the guard; REBIND_NEEDED in replay, RESOLUTION_FAILED otherwise. */
        private void guard(Plan.Oracle oracle, OracleValue value, BindMode mode) throws BindException {
            if (!oracle.hasMustEqual()) {
                return;
            }
            boolean ok = Values.equal(value.value(), value.mustEqual());
            listener.event("guard", RunLog.fields("oracle", oracle.name(), "mustEqual", value.mustEqual(), "actual",
                    value.value(), "ok", ok));
            if (ok) {
                return;
            }
            String node = "oracle." + oracle.name();
            BindException e = new BindException(mode == BindMode.REPLAY ? FailureKind.REBIND_NEEDED
                    : FailureKind.RESOLUTION_FAILED, "GUARD", node, "oracle '" + oracle.name() + "' must equal "
                    + Values.safeWrite(value.mustEqual()) + " but is " + Values.safeWrite(value.value())
                    + ": this binding does not test what the scenario claims",
                    RunLog.fields("guard", RunLog.fields("mustEqual", value.mustEqual(), "actual", value.value())));
            if (mode == BindMode.REPLAY) {
                throw e;
            }
            List<References.Ref> refs = new ArrayList<>(References.collect(oracle.args()));
            refs.addAll(References.collect(oracle.mustEqual()));
            throw withUpstream(e, refs);
        }

        /** The already-bound query slots a failing slot or oracle references (D5). */
        private BindException withUpstream(BindException e, List<References.Ref> refs) {
            List<Object> upstream = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (References.Ref ref : refs) {
                Resolution r = engine.resolutions().get(ref.slot());
                if (r != null && seen.add(ref.slot())) {
                    String label = r.credential() != null ? r.credential().id()
                            : r.value() instanceof String text ? text : Values.safeWrite(r.value());
                    upstream.add(RunLog.fields("slot", ref.slot(), "label", label.length() <= 200 ? label
                            : label.substring(0, 197) + "..."));
                }
            }
            Map<String, Object> detail = new LinkedHashMap<>(e.detail());
            detail.put("fixedUpstream", upstream);
            return new BindException(e.kind(), e.reason(), e.slot(), e.getMessage(), detail);
        }

        private static List<References.Ref> references(Object template) {
            return References.collect(template);
        }

        /** 8.3 step 10: the credential join on every slot that declares one, then {@link Binder#check}. */
        private void joinAndCheck() throws BindException {
            for (Plan.Slot slot : plan.slots()) {
                if (slot.credential() == null) {
                    continue;
                }
                engine.slot(slot.name());
                if ("fixture".equals(slot.kind())) {
                    engine.joinFixture(slot.name(), slot.credential());
                } else {
                    engine.join(slot.name());
                }
            }
            Bindings bindings = engine.bindings();
            try {
                check(plan, bindings);
            } catch (ResolutionException e) {
                throw reference(e, null);
            }
        }

        private BindException reference(ResolutionException e, String slot) {
            return new BindException(FailureKind.RESOLUTION_FAILED, e.reason(), slot, e.getMessage(),
                    RunLog.fields("reference", e.reference()));
        }
    }

    /** Strategy names that mark a value read from a bindings file. */
    static boolean replayed(String strategy) {
        return Strategy.REPLAY.name().equals(strategy);
    }
}
