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
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataChannels;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.hooks.HookInstaller;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PackSettings;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.observe.CallStart;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.observe.ScenarioStart;
import ai.skillroy.testjava.scenario.observe.StepStart;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.web.AgentWebPath;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;
import ai.skillroy.testjava.scenario.image.ImageSteps;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/**
 * Runs a {@link Plan} against one agent (contract section 8.5). Written only against {@link AgentConnection}, so it
 * runs unchanged over HTTP or against a test double.
 *
 * <p>Hooks (phase6-contracts.md 5.3, 6.4): step 0b, between the first read of the capability matrix and the
 * {@code requires} check, installs the pack's hook catalogue on the target under {@code RunOptions.hooks}
 * ({@code HookInstaller}: one {@code hooks} record; an armed stub in {@code replay}, another catalogue installed or a
 * failed install is ERROR / ENVIRONMENT, hooks {@code ON} without a catalogue or an engine PRECONDITION_FAILED), the
 * final matrix is archived as {@code capabilities.json} with a {@code capabilities} record, and the start mark's
 * sequence is kept as {@code stepsSinceSeq}, from which the base fact root {@code hooks} counts a hook's events.
 *
 * <p>Run sequence (phase2-contracts.md 11.2): check {@code requires} against the capability matrix and
 * {@code launchProperties} through {@code jvm.properties} (PRECONDITION_FAILED) -> the pack's pre-flight checks
 * through {@code PreflightCheck.check(PreflightContext)} (ENVIRONMENT) -> bind ({@link Binder}, with
 * {@code RunOptions.bind()}: REBIND_NEEDED, RESOLUTION_FAILED or ENVIRONMENT) and check that every source a
 * {@code db} check names is served (PRECONDITION_FAILED) -> a {@code trace.mark} that fixes the run's starting
 * sequence number (ENVIRONMENT if it fails) -> per step: for each call substitute references, call, apply its sync,
 * bind a captured slot from the event that ended the wait, look for unexpected prompts; then evaluate the step's
 * checks -> evaluate {@code verify} -> in {@code replay}, a cost ledger that gained an entry during the run makes
 * it ERROR / ENVIRONMENT. Failure evidence is collected when the run failed during the steps; then, <b>only if the
 * start mark was written</b>, the cleanup calls run in reverse order and, unless the run passed or was DEGRADED, the
 * pack's {@code recover}: a failure before the start mark sends nothing more to the target (D30, Q11). Neither
 * cleanup nor recover can change the verdict.
 *
 * <p>Stored-row checks ({@link RowCheck}) are evaluated with the step's expectations and retried with them: the
 * parameters are substituted and converted by column kind, the statement runs through the channel that serves the
 * source, result columns are matched to the running pack's {@code StoredTable} by position, and the row count and
 * every row must satisfy the matchers; then each aggregate ({@code sum}, {@code min}, {@code max},
 * phase3-contracts.md 7) is computed here over the returned (already masked) rows, null cells ignored and null over
 * none, and compared with its matcher. A truncated result fails the check before any aggregate is computed. The
 * guarded oracle cross-checks the bind skipped are added to the run's skipped expectations before the start mark, so
 * that such a run is DEGRADED at best (phase3-contracts.md 6.2). A captured slot ({@code capture} on a call) is bound
 * to the named payload values of the event that ended the call's wait; a missing payload value fails the step as
 * FAIL / ASSERTION.
 * Arguments that came from a joined credential field or from an A25 fixture secret (by provenance, never by value)
 * are announced to the agent as {@code _meta.secretParams} (amendment A34), so that its log, trace and error data
 * redact them. A top-level {@code confirm} argument of a step or cleanup call is sent as the call's confirmation
 * ({@code _meta.confirm} when it is {@code true}, phase5-contracts.md 13.1), not as a parameter. The protocol of one
 * call (substitution, secret parameters, web routing, sync, capture, the prompt check, the failure classification)
 * is {@link CallRunner}'s, which {@link ExecutorSession} shares (decision D79).
 *
 * <p>Step protocol, with no sleeps: every wait starts from the call's {@code seq.before}. {@code NONE} returns at
 * once; {@code ACK} awaits the pack's ack topic; {@code READY} awaits the ack topic (when there is one) and then the
 * first ready topic; {@code SETTLE} calls {@code swing.settle}; {@code EVENT} awaits the first event matching the
 * call's {@code await} (topics and payload match, references resolved; a 0 timeout is the step timeout), and none
 * within its timeout is SYNC_TIMEOUT with the same diagnostics as the other waits. A {@code sync.await} call without
 * {@code since} (the
 * {@code waitFor} verb) waits from the {@code seq.before} of the last step call before it that was not a wait or a
 * mark. Expectations are retried every {@code pollInterval} (100 ms) until they hold or the step timeout expires;
 * that pause is the only timed wait the executor performs itself.
 *
 * <p>Unexpected prompts: after each call's sync, events on the pack's prompt topics since the last check are read
 * with {@code evt.query}. A prompt is expected if the call that saw it dealt with it (a wait on a prompt topic that
 * returned it, or a prompt-answering call made after it was published), or a later call of the same step or a call of
 * the next step with calls handles prompts ({@code handlesPrompt}); otherwise the prompt policy decides
 * ({@code on_prompt: {unexpected: fail}} selects {@link PromptPolicy#FAIL}, anything else the pack's policy).
 *
 * <p>Error mapping: a JSON-RPC error from a step call is ERROR/ENVIRONMENT, except INPUT_REJECTED, NOT_FOUND,
 * AMBIGUOUS and MODAL_OPEN (FAIL/ASSERTION; for MODAL_OPEN the detail carries the dialog's title and text) and TIMEOUT
 * (FAIL/SYNC_TIMEOUT). {@link AgentLostException} is ERROR/AGENT_LOST, or TARGET_RESTARTED when
 * {@link RunOptions#targetRestarted()} says so. A {@link StackOverflowError} or {@link LinkageError} from pack or
 * matcher code is ERROR/ENVIRONMENT ("internal error"), so that evidence, cleanup and recover still run; other errors
 * (out of memory) are not caught.
 *
 * <p>UI fidelity (amendment A29): a call of the base verbs {@code click}, {@code type} or {@code press} whose result
 * says {@code fired: false} fails the step as FAIL/ASSERTION ("click did not fire: rung: reason"), and their
 * {@code swing.settle} call that follows an {@code await} fails it as SYNC_TIMEOUT when the UI did not settle. Any
 * step call whose result says {@code fidelity: "DEGRADED"} is recorded ({@code run.jsonl} type {@code fidelity},
 * {@link RunResult#degradedFidelity()}, {@link StepResult#degradedFidelity()}) and makes DEGRADED the best outcome.
 *
 * <p>Secrets (amendment A25, phase2-contracts.md 11.4): the run's {@link SecretMask} knows every value of the
 * credentials, every joined credential field and every fixture value whose key looks secret; it is applied to
 * {@code run.jsonl}, {@code bindings.json}, {@code cost-ledger.jsonl}, {@code inference.jsonl} (written beside the
 * ledger from the entries that carry details, phase5-contracts.md 7.4, decision D91), {@code failure/*.json} and the
 * {@link RunResult}. With {@link RunOptions#expectationsOnMaskedValues()} (a plan a model or a client wrote) it also
 * judges the expectations and stored-row checks, as in a session's check (phase5-contracts.md 12.2): a value the mask
 * changes satisfies no matcher (the row count, a column, an aggregate), its masked form is the actual reported.
 *
 * <p>A lost agent is final: once a call reports {@link AgentLostException}, no further call is made, not even a
 * cleanup call or the pack's {@code recover} (hardening H2, client latch).
 *
 * <p>Web views (phase4-contracts.md 6, 8): a plan with a {@code web.*} call chooses its web path after the
 * capability check and before the pre-flight (step 1a; not in {@link #resolve}): the agent's in-process path, or the
 * CDP path that {@code ServiceLoader<WebPath>} finds through the class loader that loads the domain packs (see the
 * constructors), as {@code web.path} says; a pinned {@code cdp} that cannot be used ends the run before any step as
 * PRECONDITION_FAILED (ENVIRONMENT for an attach that threw). Every {@code web.*} call (steps and cleanup) goes to the
 * chosen path when it serves the primitive and to the agent otherwise, a refused call is recorded as
 * {@code web.fallback}, and its {@code call} record carries {@code path}. A wait on a {@code web.*} topic reads the
 * path's own events as well as the agent's bus. The base fact root {@code web} reads through the same path. A
 * failure gets, besides the Phase 1 evidence, {@code failure/web-browsers.json} and, for each browser visible in
 * Swing (at most four, ref order), {@code web-<ref>-aria.json} and {@code web-<ref>.png} or
 * {@code web-<ref>-error.txt}, the path's trace as {@code failure/web-trace.zip} when the scenario set
 * {@code web.trace}, and the browsers in the UI text ({@link UiSnapshotText}). A trace holds the pages as they were,
 * which the secret mask cannot read: when a step or cleanup call of the plan may announce a secret parameter (A34;
 * {@link SecretParamsForecast}, decided before the path opens), the path is opened without tracing and
 * {@code web.trace {skipped: true, reason: secrets}} is recorded; a trace is not kept either when a call announced
 * one all the same, and {@code web.trace {ok: true}} is recorded only for a trace file the path wrote. The path is
 * closed when the run ends, however it ends. SCRIPT_ERROR from a step call is FAIL / ASSERTION; STALE_HANDLE from a
 * {@code web.*} call is FAIL / ASSERTION ("the browser closed during the step"); a {@code webWaitFlag} whose flag
 * did not turn true is FAIL / SYNC_TIMEOUT; a non-agent path's failure outside the agent's error codes is ERROR /
 * ENVIRONMENT.
 *
 * <p>Telemetry (phase6-contracts.md 10.2, decision D140): {@link RunOptions#observer()} is told what the run does,
 * beside the records it mirrors: the run's start and end ({@code scenarioStarted} before {@code run-start}, whose
 * record then carries the observer's {@code traceId}, and {@code scenarioEnded} after {@code run-end}, with the masked
 * result), each step and {@code verify} ({@code stepStarted}, {@code stepEnded}), each step and cleanup call with its
 * waits ({@link CallRunner}; the call's {@code _meta.traceparent} is the observer's open call span), every bind record
 * ({@code bindEvent}: the bind listener's and the executor's final {@code bind} record), the scenario records
 * {@code requires}, {@code launchProperties}, {@code preflight}, {@code hooks}, {@code capabilities}, {@code recover}
 * and {@code cleanup} and the step records {@code expect}, {@code db}, {@code prompt}, {@code fidelity} and
 * {@code capture} ({@code scenarioRecord}, as {@link RunLog} writes them: masked, in JSON form, whoever writes them)
 * and every model call of the ledger ({@code modelCall}). The observer is
 * wrapped ({@code RunObservers.safe}): one that throws is logged once and ignored for the rest of the run, and nothing
 * of it changes a verdict; with {@link RunObserver#NONE} no record is built for it.
 *
 * <p>Phase 7 (phase7-contracts.md 8.4-8.7): a plan with {@code inference: run} is SKIPPED in {@code replay} right after
 * {@code run-start} (the agent is not contacted) and refused in every other mode; a plan with {@code via: ROBOT} or
 * {@code mode: ROBOT} calls is refused before {@code checkRequires} without {@link RunOptions#robot()}; after step 0b
 * the controller-side contributors' capabilities are merged into the matrix ({@link CapabilityMerge}); a
 * {@link VisualCheck} is skipped unconditionally; an {@link ImageCheck} and an anchored {@code pointer} call's guard
 * run through {@code ImageSteps} against the bindings file's baselines and anchors, which {@code approve} writes a
 * second time after the steps.
 *
 * <p>Not thread-safe: one executor runs one plan at a time on the calling thread; it starts no threads (a web path
 * may run threads of its own).
 */
public final class PlanExecutor {

    private static final long DIAGNOSTIC_TIMEOUT_MS = CallRunner.DIAGNOSTIC_TIMEOUT_MS;
    private static final long EVENT_EVIDENCE = 200L;
    private static final String LEDGER_FILE = "cost-ledger.jsonl";

    /**
     * The record of the run's model calls (phase5-contracts.md 7.4, decision D91), beside {@link #LEDGER_FILE}: one
     * line per ledger entry of the run that carries {@link CostLedger.Entry#details()}; not written when none does.
     */
    public static final String INFERENCE_FILE = "inference.jsonl";

    /**
     * The capability matrix the run used, archived beside {@code run.jsonl} once it is final (phase6-contracts.md 6.4,
     * decision D130): the wire shape of {@code GET /capabilities}, ids sorted.
     */
    public static final String CAPABILITIES_FILE = "capabilities.json";

    /** Every file a run may write below its output directory, files before the directories that hold them. */
    private static final List<String> OUTPUT_FILES = List.of(RunLog.FILE, "bindings.json", LEDGER_FILE,
            INFERENCE_FILE, CAPABILITIES_FILE, "failure/snapshot.json",
            "failure/app-state.json", "failure/dialogs.json", "failure/capture.png", "failure/events.json", "failure",
            "artifacts/capture.png", "artifacts");

    /** The prefix of the web evidence files in {@code failure/} (phase4-contracts.md 6.4). */
    private static final String WEB_EVIDENCE_PREFIX = "web-";

    /** The most browsers whose aria snapshot and bitmap a failure keeps. */
    private static final int WEB_EVIDENCE_BROWSERS = 4;

    private final DomainPack pack;
    private final AgentConnection agent;
    private final RunOptions options;
    private final ClassLoader webPathLoader;
    /**
     * Whether a step or cleanup call of the plan may announce a secret parameter (A34), asked before the web path
     * opens, with the caller's bindings (null when the run binds the plan).
     */
    private final BiPredicate<Plan, Bindings> secretsForecast;
    private final Map<String, FactProvider> facts = new LinkedHashMap<>();
    /** True when the pack has a fact root named {@code web}, which then hides the base root. */
    private final boolean packWebRoot;
    /** True when the pack has a fact root named {@code hooks}, which then hides the base root (P6 6.3). */
    private final boolean packHooksRoot;
    /** True when the pack has a fact root named {@code caps}, which then hides the base root. */
    private final boolean packCapsRoot;
    /** True when the pack has a fact root named {@code events}, which then hides the base root (P8 8.6). */
    private final boolean packEventsRoot;

    /** The message of a run that was not executed (phase7-contracts.md 8.6). */
    static final String SKIPPED_MESSAGE = "not executed: the plan needs a model while steps run (inference: run)";

    /** The refusal of a plan with Robot calls without the person's opt-in (phase7-contracts.md 8.1, 9.2). */
    static final String ROBOT_REFUSAL = "robot: not allowed; a person passes --allow-robot";

    /**
     * An executor that finds web paths ({@code ServiceLoader<WebPath>}) through the thread's context class loader at
     * run time, the loader the CLI and the JUnit glue set or use for the domain packs.
     *
     * @param pack the pack the plan was compiled with
     * @param agent the connection to the target's agent; not closed by the executor
     * @param options run options; null means {@link RunOptions#defaults()}
     */
    public PlanExecutor(DomainPack pack, AgentConnection agent, RunOptions options) {
        this(pack, agent, options, null);
    }

    /**
     * @param pack the pack the plan was compiled with
     * @param agent the connection to the target's agent; not closed by the executor
     * @param options run options; null means {@link RunOptions#defaults()}
     * @param webPathLoader the class loader that loads the domain packs, through which {@code ServiceLoader<WebPath>}
     *     finds a controller-side web path such as the CDP path of {@code skillroy-cdp} (phase4-contracts.md 6.1,
     *     D49); null means the thread's context class loader when the run starts
     */
    public PlanExecutor(DomainPack pack, AgentConnection agent, RunOptions options, ClassLoader webPathLoader) {
        this(pack, agent, options, webPathLoader, null);
    }

    /**
     * The four-argument executor with another forecast of secret parameters: the tests of the trace guard at failure
     * time give one that misses a secret.
     *
     * @param secretsForecast whether a step or cleanup call of the plan may announce a secret parameter, given the
     *     plan and the caller's bindings (null when the run binds the plan); null means {@link SecretParamsForecast}
     *     with the environment id of {@code options}
     */
    PlanExecutor(DomainPack pack, AgentConnection agent, RunOptions options, ClassLoader webPathLoader,
                 BiPredicate<Plan, Bindings> secretsForecast) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.options = options == null ? RunOptions.defaults() : options;
        this.webPathLoader = webPathLoader;
        String envId = this.options.bind().envId();
        this.secretsForecast = secretsForecast != null ? secretsForecast
                : (plan, provided) -> SecretParamsForecast.mayAnnounce(plan, provided, envId);
        List<FactProvider> packFacts = pack.facts();
        boolean webRoot = false;
        boolean hooksRoot = false;
        boolean capsRoot = false;
        boolean eventsRoot = false;
        if (packFacts != null) {
            for (FactProvider fact : packFacts) {
                facts.putIfAbsent(fact.root(), fact);
                webRoot |= "web".equals(fact.root());
                hooksRoot |= "hooks".equals(fact.root());
                capsRoot |= "caps".equals(fact.root());
                eventsRoot |= "events".equals(fact.root());
            }
        }
        this.packWebRoot = webRoot;
        this.packHooksRoot = hooksRoot;
        this.packCapsRoot = capsRoot;
        this.packEventsRoot = eventsRoot;
        for (FactProvider fact : BaseFacts.all()) {
            facts.putIfAbsent(fact.root(), fact);
        }
    }

    /**
     * Runs the plan, binding it with {@link Binder} and {@code RunOptions.bind()} (phase2-contracts.md 8). Never
     * throws for scenario failures.
     */
    public RunResult run(Plan plan) {
        return run(plan, null);
    }

    /**
     * Runs the plan. Never throws for scenario failures: every outcome, including a lost agent, is a
     * {@link RunResult}.
     *
     * @param bindings the slot values to use, or null to bind the plan at run start. Caller-supplied bindings skip
     *     the binder (only {@link Binder#check} runs); a caller that joins secrets itself uses
     *     {@link Bindings#withCredential}, which also puts them into the run's mask
     */
    public RunResult run(Plan plan, Bindings bindings) {
        return new Run(Objects.requireNonNull(plan, "plan"), bindings, false).execute();
    }

    /**
     * Runs the plan as {@link #run(Plan)} does and also returns the bind outcome, for a caller that reports what the
     * bind found besides the verdict (the CLI's {@code run --json}: the cross-check records and the skipped list,
     * phase3-contracts.md 9). Never throws for scenario failures.
     *
     * @return the result, the bind outcome (null when the bind failed or did not run) and the {@code crosscheck}
     *     records the bind emitted (also when it failed)
     */
    public Resolved runWithOutcome(Plan plan) {
        Run run = new Run(Objects.requireNonNull(plan, "plan"), null, false);
        RunResult result = run.execute();
        return new Resolved(result, run.outcome, run.crossCheckRecords, run.webSummary);
    }

    /**
     * What {@code skillroy resolve} needs (phase2-contracts.md 12.1): the run sequence up to and including the bind
     * (requires, launch properties, pre-flight, bind with {@code RunOptions.bind()}, the check that every source a
     * {@code db} check names is served), then it stops. No start mark is written, no step runs, no cleanup call is
     * sent and {@code recover} is not called. In {@code live} without {@code writeBindings} (the CLI's
     * {@code --dry-run}, which prints the bindings file it would write) that file is refused as {@code approve}
     * refuses to write it: when a recorded value equals a credential the result is ERROR / ENVIRONMENT "the bindings
     * file was not written: a recorded value equals a credential" and no outcome is returned.
     *
     * @return the result (PASS when bound), the bind outcome (null when the bind did not happen or failed) and the
     *     {@code crosscheck} records the bind emitted (also when it failed)
     */
    public Resolved resolve(Plan plan) {
        Run run = new Run(Objects.requireNonNull(plan, "plan"), null, true);
        RunResult result = run.execute();
        return new Resolved(result, run.outcome, run.crossCheckRecords);
    }

    /**
     * The outcome of {@link #resolve(Plan)} or {@link #runWithOutcome(Plan)}.
     *
     * @param result the run's result; its steps are empty after {@link #resolve(Plan)}
     * @param outcome the bind outcome, or null when the bind failed or did not run
     * @param crossChecks the {@code crosscheck} records the bind emitted, in order, whether or not it completed
     *     (phase3-contracts.md 8.2, 9): as {@code run.jsonl} holds them (the run's secrets masked), without
     *     {@code type} and {@code t}; so a bind that failed after an agreeing check, or as ORACLE_SUSPECT, still
     *     reports them. Empty when none ran or was skipped; never null; unmodifiable
     * @param web the run's web summary (phase4-contracts.md 8.2, for {@code run --json}): the {@code web.path}
     *     record's fields ({@code path, reason?, cdp?, libHash, policy}), then {@code calls} ({@code {step, index,
     *     primitive, path, ok}} per {@code web.*} call, cleanup calls with step {@code cleanup}) and
     *     {@code fallbacks} (the {@code web.fallback} records without {@code type} and {@code t}); masked;
     *     null when the plan has no {@code web.*} call or the run ended before the path was chosen
     */
    public record Resolved(RunResult result, BindOutcome outcome, List<Map<String, Object>> crossChecks,
                           Map<String, Object> web) {

        /** Copies the records. */
        @SuppressWarnings("unchecked")
        public Resolved {
            crossChecks = crossChecks == null ? List.of() : (List<Map<String, Object>>) Plan.freeze(crossChecks);
            web = web == null ? null : (Map<String, Object>) Plan.freeze(web);
        }

        /** The Phase 3 shape: no web summary. */
        public Resolved(RunResult result, BindOutcome outcome, List<Map<String, Object>> crossChecks) {
            this(result, outcome, crossChecks, null);
        }

        /** The Phase 2 shape: the records are those of the outcome's bind ({@code crossChecks} of its bindings). */
        public Resolved(RunResult result, BindOutcome outcome) {
            this(result, outcome, outcome == null ? List.of() : outcome.bindings().crossChecks().values().stream()
                    .map(CrossCheckOutcome::toJson).toList(), null);
        }
    }

    /**
     * The {@code inference.jsonl} line of a ledger entry that carries details (phase5-contracts.md 7.4, decision D91),
     * before the run's mask: {@code type: "inference"}, then the details in their own order (the record the ledger
     * writer built: time, purpose, scenario and slot, provider, requested and answering model, request id, fingerprint,
     * tokens, cost, elapsed time, stop reason, the masked structured answer and, with prompt logging, the masked
     * prompt), then the entry's fields the details do not hold (its strategy, for one), null ones left out. A new map.
     *
     * @throws NullPointerException if the entry has no details
     */
    static Map<String, Object> inferenceRecord(CostLedger.Entry entry) {
        Map<String, Object> details = Objects.requireNonNull(entry.details(), "details");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "inference");
        record.putAll(details);
        entry.toJson().forEach((key, value) -> {
            if (value != null) {
                record.putIfAbsent(key, value);
            }
        });
        return record;
    }

    /**
     * Ref order (phase4-contracts.md 6.4): by the text before a ref's trailing digits, then by the number they form
     * ({@code h2} before {@code h10}), then as text.
     */
    static int compareRefs(String a, String b) {
        int ia = digitsStart(a);
        int ib = digitsStart(b);
        int prefix = a.substring(0, ia).compareTo(b.substring(0, ib));
        if (prefix != 0) {
            return prefix;
        }
        String da = a.substring(ia).replaceFirst("^0+(?=\\d)", "");
        String db = b.substring(ib).replaceFirst("^0+(?=\\d)", "");
        if (da.length() != db.length()) {
            return Integer.compare(da.length(), db.length());
        }
        int digits = da.compareTo(db);
        return digits != 0 ? digits : a.compareTo(b);
    }

    private static int digitsStart(String ref) {
        int i = ref.length();
        while (i > 0 && Character.isDigit(ref.charAt(i - 1)) && ref.charAt(i - 1) < 128) {
            i--;
        }
        return i;
    }

    /** The SQL-standard timestamp text: seconds always, the fraction only when it is not zero, no trailing zeros. */
    private static final DateTimeFormatter SQL_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss").appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter(Locale.ROOT);

    /**
     * A {@code TIMESTAMP} where value as a statement parameter (phase2-contracts.md 7.4 step 1): a local date-time in
     * ISO-8601, with a {@code T} or a space between date and time ({@code 2026-10-02T10:00},
     * {@code 2026-10-02 10:00:00.123}), becomes the SQL-standard text {@code yyyy-MM-dd HH:mm:ss[.fraction]}, the one
     * form JDBC drivers accept for a string bound to a timestamp (what {@code java.sql.Timestamp.valueOf} parses).
     * Anything else is null (refused): a date alone, an offset or {@code Z} (the column is a local date-time, and
     * databases differ in how they apply a zone), or a value that is not text.
     */
    static String sqlTimestamp(Object value) {
        if (!(value instanceof String text) || text.length() < 16 || text.length() > 40) {
            return null;
        }
        String iso = text.charAt(10) == ' ' ? text.substring(0, 10) + 'T' + text.substring(11) : text;
        try {
            return SQL_TIMESTAMP.format(LocalDateTime.parse(iso));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** One expectation with its references resolved. */
    private record Bound(Expectation source, ExpectationPath path, Object matcher) {
    }

    /** One stored-row check with its parameters converted and its matchers (and aggregates' matchers) resolved. */
    private record BoundRow(RowCheck source, StoredTable table, List<Object> params, Object rows,
                            Map<String, Object> expect, Map<String, Object> aggregates) {
    }

    /** The body of one step; it adds the guarded expectations it skips to {@code stepSkipped}. */
    @FunctionalInterface
    private interface Block {
        void run(List<String> stepSkipped) throws Abort, AgentLostException, InterruptedException;
    }

    /** The outcome of one attempt at one expectation. */
    private record Attempt(boolean holds, boolean resolved, Object actual, String reason, String fetchError) {
    }

    /** The state of one run. */
    private final class Run {

        private final Plan plan;
        private final Bindings provided;
        private final long startMs;
        private final long stepTimeoutMs;
        private final List<StepResult> steps = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private SecretMask mask = SecretMask.NONE;
        private DataChannels data;
        private boolean startMarked;
        private final CostLedger ledger;
        private final int ledgerStart;
        private String bindingsFile;
        private final boolean bindOnly;
        private BindOutcome outcome;
        /** The {@code crosscheck} records the bind emitted, as run.jsonl holds them, also when it failed (P3 9). */
        private final List<Map<String, Object>> crossCheckRecords = new ArrayList<>();
        private RunLog log;
        /**
         * The run's output directory: {@code RunOptions.outputDir()}, or null when there is none or it was refused
         * ({@link #outputRefused}), so that nothing is written then.
         */
        private Path outDir;
        /**
         * Why the output directory or the run log cannot be used (Phase 6 security fix, pass 2, P2-L8-F3, P2-L8-S1),
         * or null: the run then ends ERROR / ENVIRONMENT before anything is sent to the agent.
         */
        private String outputRefused;
        private CapabilityMatrix caps;
        private boolean stepsStarted;
        /** The step protocol (decision D79): the calls, their syncs and the protocol's state; set in execute(). */
        private CallRunner runner;
        /** The run's web side: the chosen path, its calls and waits (created when the log is open). */
        private WebRun web;
        /** The run's web summary for {@link Resolved#web()}, masked; null without web calls. */
        private Map<String, Object> webSummary;
        /** The fact providers of this run: the executor's, with the base root {@code web} reading through the path. */
        private final Map<String, FactProvider> runFacts = new LinkedHashMap<>();
        /** Fact and fall-back notes written once each. */
        private final Set<String> noted = new HashSet<>();
        /** What fact providers read through: web calls go to the run's web path. */
        private final AgentConnection factConnection = new FactConnection();
        /**
         * True when a call of the plan may announce a secret parameter, so that the web path was opened without
         * tracing (phase4-contracts.md 6.4).
         */
        private boolean traceWithheld;
        /** The run's observer, wrapped so that it never throws (phase6-contracts.md 10.2). */
        private final RunObserver observer = RunObservers.safe(options.observer());
        /** False with {@link RunObserver#NONE}: no record is built for the observer. */
        private final boolean observing = !RunObservers.isNone(observer);
        /**
         * The agent's sequence when the first step began (the start mark's), from which the base fact root
         * {@code hooks} counts a hook's events (phase6-contracts.md 5.3 step 6, 6.3): a bind-time verifier's call or
         * an earlier run's never count.
         */
        private long stepsSinceSeq;
        /** The agent's matrix after step 0b, before the merge (phase7-contracts.md 8.5); null before. */
        private CapabilityMatrix agentCaps;
        /** The ids the controller-side contributors added (phase7-contracts.md 8.5). */
        private List<String> contributed = List.of();
        /** True for a {@code run} plan in {@code replay}: nothing was executed (phase7-contracts.md 8.6). */
        private boolean notExecuted;
        /** The run's baselines and anchors (phase7-contracts.md 8.7); built after the bind. */
        private ImageBaselines baselines;
        /** The run's image steps (phase7-contracts.md 6.7); built on first need. */
        private ImageSteps imageSteps;

        Run(Plan plan, Bindings provided, boolean bindOnly) {
            this.plan = plan;
            this.provided = provided;
            this.bindOnly = bindOnly;
            this.startMs = options.clock().millis();
            this.stepTimeoutMs = options.stepTimeout() != null ? options.stepTimeout().toMillis()
                    : plan.stepTimeoutMs();
            this.ledger = options.bind().ledger();
            this.ledgerStart = ledger.size();
            runFacts.putAll(facts);
            if (!packWebRoot) {
                runFacts.put("web", BaseFacts.web(BaseFacts.AGENT_READS, options.clock(), this::textOmitted));
            }
            if (!packHooksRoot) {
                runFacts.put("hooks", BaseFacts.hooks(() -> stepsSinceSeq));
            }
            if (!packCapsRoot) {
                // phase7-contracts.md 8.5: the base root caps reads the run's merged matrix, with a source per entry.
                runFacts.put("caps", BaseFacts.caps(() -> caps));
            }
            if (!packEventsRoot) {
                // phase8-contracts.md 8.6: the base root events reads the run's window, from the start mark on.
                runFacts.put("events", BaseFacts.events(() -> stepsSinceSeq));
            }
        }

        /** Reads the pack's policies; inside the run's try block so that a pack failure becomes ENVIRONMENT. */
        private void init() throws Abort {
            if (!plan.pack().equals(pack.id())) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, "the plan was compiled for pack '"
                        + plan.pack() + "' but is run with pack '" + pack.id() + "'", null);
            }
            if (DomainPacks.reservedId(pack.id())) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, "pack id '" + pack.id() + "': the prefix"
                        + " 'inference' is reserved", null);
            }
            String runVersion = pack.version();
            if (!Objects.equals(plan.packVersion(), runVersion)) {
                log.write("pack", RunLog.fields("note", "the plan was compiled with pack version "
                        + plan.packVersion() + " and runs with " + runVersion));
            }
            SyncPolicy policy = pack.syncPolicy();
            if (policy != null) {
                runner.sync = policy;
            }
            Object unexpected = plan.onPrompt().get("unexpected");
            PromptPolicy packPolicy = pack.promptPolicy();
            boolean strict = unexpected instanceof String name && name.strip().equalsIgnoreCase("fail");
            runner.promptPolicy = strict || packPolicy == null ? PromptPolicy.FAIL : packPolicy;
            StepLibrary library = pack.steps();
            Set<String> verbs = library == null ? null : library.verbs();
            runner.packVerbs = verbs == null ? Set.of() : Set.copyOf(verbs);
        }

        /**
         * The run's secret mask (phase2-contracts.md 11.4): every value of the credentials, the secrets the caller's
         * bindings joined, and the fixture values A25's key rule finds in the bindings it will use (the caller's,
         * else the plan's fixtures bound now, else, when they do not bind, the fixtures as written). Set before the
         * first record so that nothing escapes it.
         */
        private SecretMask secretMask() {
            SecretMask base = options.bind().credentials().mask();
            if (provided != null) {
                return base.plus(provided.secretValues()).plus(FixtureSecrets.collect(provided.values()));
            }
            try {
                return base.plus(FixtureSecrets.collect(Binder.bindFixtures(plan, options.bind().envId()).values()));
            } catch (ResolutionException | RuntimeException e) {
                Map<String, Object> raw = new LinkedHashMap<>();
                for (Plan.Slot slot : plan.slots()) {
                    if ("fixture".equals(slot.kind())) {
                        raw.put(slot.name(), slot.value());
                    }
                }
                return base.plus(FixtureSecrets.collect(raw));
            }
        }

        RunResult execute() {
            prepareOutput();
            log = RunLog.open(outDir, options.clock());
            if (outDir != null && log.error() != null) {
                // P2-L8-F3: run.jsonl is created exclusively; something in its place that the run cannot remove (a
                // link or another user's file) refuses the run rather than receiving its log.
                outputRefused = "the run log cannot be written: " + log.error();
                outDir = null;
            }
            // phase6-contracts.md 10.2: the scenario and step records reach the observer as written (RunLog.observe).
            log.observe(observer);
            web = new WebRun(agent, log, options.clock(), webPathLoader);
            runner = new CallRunner(agent, log, options.clock(), web, stepTimeoutMs, this::keyRuleSlots);
            runner.observer = observer;
            try {
                mask = secretMask();
                log.mask(mask);
                return executeLogged();
            } finally {
                // Also when an Error from pack code passes through: the web path, the file and the channels close.
                web.close();
                log.close();
                if (data != null) {
                    data.close();
                }
            }
        }

        /** The run's data channels: JDBC for the sources credentials.yaml names, the agent for every other. */
        private DataChannel data() {
            if (data == null) {
                data = DataChannels.of(agent, options.bind().credentials(), pack.rowMasks());
            }
            return data;
        }

        private RunResult executeLogged() {
            observeStart();
            Map<String, Object> start = RunLog.fields("scenarioId", plan.scenarioId(), "pack", plan.pack(),
                    "packVersion", plan.packVersion(), "planVersion", plan.planVersion(), "sourceHash",
                    plan.sourceHash(), "stepTimeoutMs", stepTimeoutMs, "startedAt",
                    options.clock().instant().toString());
            String traceId = observing ? observer.traceId() : null;
            if (traceId != null) {
                // phase6-contracts.md 10.3: with telemetry on, run-start names the trace (a trace id is not a secret).
                start.put("traceId", traceId);
            }
            if (options.robot()) {
                // phase7-contracts.md 9.2: the person's half of the Robot opt-in is recorded in run-start.
                start.put("robot", true);
            }
            log.write("run-start", start);
            Abort failure = null;
            try {
                if (Plan.INFERENCE_RUN.equals(plan.inference())) {
                    // phase7-contracts.md 8.6 (D311, D328): decided before anything is sent to the target.
                    if (options.bind().mode() != BindMode.REPLAY) {
                        throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, "inference: run: run it with"
                                + " skillroy session --play", RunLog.fields("inference", Plan.INFERENCE_RUN, "mode",
                                options.bind().mode().label()));
                    }
                    notExecuted = true;
                    log.write("skipped", RunLog.fields("reason", SKIPPED_MESSAGE));
                }
            } catch (Abort abort) {
                failure = abort;
            }
            try {
                if (failure != null || notExecuted) {
                    throw new SkipRest();
                }
                refuseOutput();
                requireBindingsRoot();
                init();
                refuseRobot();
                checkRequires();
                checkLaunchProperties();
                if (!bindOnly) {
                    selectWebPath();
                }
                runPreflight();
                bind();
                checkSources();
                if (!bindOnly) {
                    markStart();
                    for (int i = 0; i < plan.steps().size(); i++) {
                        runStep(i);
                    }
                    runVerify();
                }
            } catch (SkipRest skip) {
                // A run plan: refused or not executed above.
            } catch (Abort abort) {
                failure = abort;
            } catch (AgentLostException e) {
                failure = lost(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new Abort(Status.ERROR, FailureKind.ENVIRONMENT, runner.currentStep,
                        "the run was interrupted", null);
            } catch (RuntimeException | StackOverflowError | LinkageError e) {
                // Not OutOfMemoryError or another VirtualMachineError: the JVM may not be able to go on. The message
                // names the exception's class only, its text (which can name a file) stays in the detail, which no
                // span carries (Phase 6 security fix, pass 2, P2-L8-F4).
                failure = new Abort(Status.ERROR, FailureKind.ENVIRONMENT, runner.currentStep, "internal error: "
                        + e.getClass().getName(), RunLog.fields("exception", e.toString()));
            }
            if (baselines != null && provided == null && outcome != null && outcome.file() != null
                    && options.bind().mode() == BindMode.APPROVE && Binder.imagesOnly(plan)) {
                // Phase 7 security fix, S115 (phase7-contracts.md 8.7, D362 as amended): a plan whose bindings hold
                // images only wrote nothing at bind time; its file is written now, with the baselines a person
                // approved at their steps, and any other change only when the approver approves it (also for a bind
                // alone, skillroy resolve, which has no steps).
                try {
                    Path written = Binder.writeImagesOnly(plan, options.bind(), outcome.file(), baselines,
                            failure != null);
                    if (written != null) {
                        outcome = new BindOutcome(outcome.bindings(), outcome.file(), outcome.env(), written,
                                outcome.skipped());
                        log.write("bind", RunLog.fields("ok", true, "images", new ArrayList<>(baselines.merged()
                                .keySet()), "written", options.bind().envId() + "/" + plan.scenarioId()
                                + BindingsFile.SUFFIX));
                    }
                } catch (BindException e) {
                    log.write("bind", RunLog.fields("ok", false, "images", true, "reason", e.reason(), "error",
                            e.getMessage()));
                    if (failure == null) {
                        failure = new Abort(Status.ERROR, e.kind(), null, e.getMessage(),
                                new LinkedHashMap<>(e.detail()));
                    }
                }
            } else if (!bindOnly && baselines != null && baselines.hasApprovals()) {
                // phase7-contracts.md 8.7 (D355): approve's second write, after the steps, also after a failed one (a
                // person approved each baseline at its step).
                try {
                    BindingsFile written = Binder.writeApprovedImages(plan, options.bind(), outcome != null
                            && outcome.file() != null ? outcome.file() : emptyFile(), baselines);
                    log.write("bind", RunLog.fields("ok", true, "images", new ArrayList<>(written.images().keySet()),
                            "written", options.bind().envId() + "/" + plan.scenarioId() + BindingsFile.SUFFIX));
                } catch (BindException e) {
                    log.write("bind", RunLog.fields("ok", false, "images", true, "error", e.getMessage()));
                    if (failure == null) {
                        failure = new Abort(Status.ERROR, e.kind(), null, e.getMessage(),
                                new LinkedHashMap<>(e.detail()));
                    }
                }
            }
            List<CostLedger.Entry> modelCalls = modelCalls();
            log.write("ledger", RunLog.fields("count", modelCalls.size()));
            if (options.bind().mode() == BindMode.REPLAY && !modelCalls.isEmpty()) {
                // Decision D19: a model call in replay is never acceptable, whatever the steps did.
                failure = new Abort(Status.ERROR, FailureKind.ENVIRONMENT, failure == null ? null : failure.stepId,
                        "replay made " + modelCalls.size() + " model call" + (modelCalls.size() == 1 ? "" : "s"),
                        RunLog.fields("modelCalls", modelCalls.size(), "mode", "replay"));
            }
            writeLedger(modelCalls);
            Map<String, Object> detail = failure == null ? new LinkedHashMap<>() : failure.detail;
            Status status = failure != null ? failure.status : notExecuted ? Status.SKIPPED
                    : skipped.isEmpty() && runner.degradedFidelity.isEmpty() ? Status.PASS : Status.DEGRADED;
            try {
                if (failure != null && stepsStarted && !runner.agentLost && options.captureOnFailure()) {
                    collectEvidence(detail);
                }
                if (failure != null && stepsStarted && options.captureOnFailure()) {
                    saveWebTrace();
                }
                if (failure == null && !notExecuted) {
                    keepArtifacts();
                }
            } catch (RuntimeException e) {
                log.write("evidence", RunLog.fields("ok", false, "error", e.toString()));
            }
            if (startMarked) {
                runCleanup();
                if (status != Status.PASS && status != Status.DEGRADED) {
                    runRecover();
                }
            }
            // Otherwise (D30, Q11): a failure before the start mark sends nothing more to the target, and run.jsonl
            // has neither a cleanup nor a recover record.
            web.close();
            Map<String, Object> summary = web.summary();
            webSummary = summary == null ? null : logged(summary);
            if (runner.bindings != null && outDir != null) {
                try {
                    writeBindings(outDir.resolve("bindings.json"));
                } catch (IOException | RuntimeException e) {
                    log.write("bind", RunLog.fields("ok", true, "writeError", e.toString()));
                }
            }
            Duration duration = Duration.ofMillis(Math.max(0, options.clock().millis() - startMs));
            String message = failure != null ? failure.getMessage() : notExecuted ? SKIPPED_MESSAGE : null;
            RunResult result = masked(new RunResult(plan.scenarioId(), status, failure == null ? null : failure.kind,
                    failure == null ? null : failure.stepId, message,
                    safeDetail(detail), steps, skipped, duration, runner.degradedFidelity, options.bind().mode(),
                    options.bind().envId(), modelCalls));
            Map<String, Object> end = RunLog.fields("status", status.name(), "failureKind",
                    result.failureKind() == null ? null : result.failureKind().name(), "failedStepId",
                    result.failedStepId(), "message", result.message(), "skippedExpectations", skipped);
            if (!runner.degradedFidelity.isEmpty()) {
                end.put("degradedFidelity", runner.degradedFidelity);
            }
            end.put("durationMs", duration.toMillis());
            log.write("run-end", end);
            observer.scenarioEnded(result);
            return result;
        }

        // ---------------------------------------------------------------- telemetry (phase6-contracts.md 10.2)

        /** {@code scenarioStarted}: before {@code run-start}, so that its record can name the trace. */
        private void observeStart() {
            if (observing) {
                observer.scenarioStarted(new ScenarioStart(plan.scenarioId(), plan.pack(), plan.packVersion(),
                        plan.planVersion(), plan.sourceHash(), options.bind().mode().label(),
                        options.bind().envId()));
            }
        }

        /** A bind record for the observer as {@code run.jsonl} holds it (the listener's, and the final bind record). */
        private void observeBind(String type, Map<String, Object> fields) {
            if (observing) {
                observer.bindEvent(type, logged(fields));
            }
        }

        /** The executor's final {@code bind} record (success or failure): written, and it ends the bind span. */
        private void bindRecord(Map<String, Object> fields) {
            log.write("bind", fields);
            observeBind("bind", fields);
        }

        /** The result with the run's secrets masked in every string it carries. */
        private RunResult masked(RunResult result) {
            if (mask.isEmpty()) {
                return result;
            }
            List<StepResult> maskedSteps = new ArrayList<>();
            for (StepResult step : result.steps()) {
                maskedSteps.add(new StepResult(step.id(), step.verb(), step.line(), step.status(),
                        mask.apply(step.message()), texts(step.skippedExpectations()), step.duration(),
                        texts(step.degradedFidelity())));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> maskedDetail = (Map<String, Object>) mask.apply(result.detail());
            return new RunResult(result.scenarioId(), result.status(), result.failureKind(), result.failedStepId(),
                    mask.apply(result.message()), maskedDetail, maskedSteps, texts(result.skippedExpectations()),
                    result.duration(), texts(result.degradedFidelity()), result.mode(), result.envId(),
                    result.modelCalls());
        }

        private List<String> texts(List<String> values) {
            List<String> out = new ArrayList<>(values.size());
            for (String value : values) {
                out.add(mask.apply(value));
            }
            return out;
        }

        /** The ledger entries recorded since this run started. */
        private List<CostLedger.Entry> modelCalls() {
            List<CostLedger.Entry> all = ledger.entries();
            return all.size() <= ledgerStart ? List.of() : List.copyOf(all.subList(ledgerStart, all.size()));
        }

        /**
         * {@code cost-ledger.jsonl}: one line per entry of this run; always written; masked. Beside it
         * {@code inference.jsonl} (phase5-contracts.md 7.4, decision D91): one line per entry that carries details,
         * {@link #inferenceRecord}, masked; not written when no entry has details.
         */
        private void writeLedger(List<CostLedger.Entry> entries) {
            if (observing) {
                // phase6-contracts.md 10.2: each model call of the run, after the fact (its details place its span).
                for (CostLedger.Entry entry : entries) {
                    observer.modelCall(entry);
                }
            }
            Path dir = outDir;
            if (dir == null) {
                return;
            }
            StringBuilder text = new StringBuilder();
            StringBuilder inference = new StringBuilder();
            for (CostLedger.Entry e : entries) {
                text.append(Json.write(mask.apply(e.toJson()))).append('\n');
                if (e.details() != null) {
                    inference.append(Json.write(mask.apply(inferenceRecord(e)))).append('\n');
                }
            }
            try {
                OutputFiles.writeString(dir.resolve(LEDGER_FILE), text.toString());
            } catch (IOException e) {
                log.write("ledger", RunLog.fields("ok", false, "error", e.toString()));
            }
            if (!inference.isEmpty()) {
                try {
                    OutputFiles.writeString(dir.resolve(INFERENCE_FILE), inference.toString());
                } catch (IOException e) {
                    log.write("ledger", RunLog.fields("ok", false, "file", INFERENCE_FILE, "error", e.toString()));
                }
            }
        }

        /**
         * The output directory, checked (Phase 6 security fix, pass 2, P2-L8-F3, P2-L8-S1): created if needed and
         * refused unless it is the current user's ({@link OutputFiles#directory}); a refusal leaves {@link #outDir}
         * null and ends the run before anything is sent ({@link #refuseOutput}). Then the files an earlier run left
         * there are removed ({@link #clearOldOutputs}).
         */
        private void prepareOutput() {
            outDir = options.outputDir();
            if (outDir == null) {
                return;
            }
            try {
                OutputFiles.directory(outDir);
            } catch (IOException e) {
                outputRefused = e.getMessage();
                outDir = null;
                return;
            }
            clearOldOutputs();
        }

        /** Ends the run when its output cannot be used, before the agent is contacted (P2-L8-F3, P2-L8-S1). */
        private void refuseOutput() throws Abort {
            if (outputRefused != null) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, outputRefused,
                        RunLog.fields("outputDir", String.valueOf(options.outputDir())));
            }
        }

        /**
         * Removes the files an earlier run into the same output directory left behind, so that evidence of an old
         * failure is never read as belonging to this run. Only the files the executor itself writes are touched.
         */
        private void clearOldOutputs() {
            Path dir = outDir;
            if (dir == null) {
                return;
            }
            // phase4-contracts.md 6.4: the web evidence files are named after the browsers' refs. P2-L8-F3: an entry
            // is judged without following links, so that a link of that name goes too (a dangling one included),
            // never the file it names.
            Path failure = dir.resolve("failure");
            if (Files.isDirectory(failure, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> files = Files.list(failure)) {
                    for (Path file : files.toList()) {
                        String name = file.getFileName().toString();
                        if ((name.startsWith(WEB_EVIDENCE_PREFIX) || name.startsWith(ImageSteps.FILE_PREFIX))
                                && (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                || Files.isSymbolicLink(file))) {
                            Files.deleteIfExists(file);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // Best effort, as below.
                }
            }
            Path candidates = dir.resolve("candidates");
            if (Files.isDirectory(candidates, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> files = Files.list(candidates)) {
                    for (Path file : files.toList()) {
                        if (file.getFileName().toString().startsWith(ImageSteps.FILE_PREFIX)
                                && (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                || Files.isSymbolicLink(file))) {
                            Files.deleteIfExists(file);
                        }
                    }
                    Files.deleteIfExists(candidates);
                } catch (IOException | RuntimeException e) {
                    // Best effort, as below.
                }
            }
            for (String name : OUTPUT_FILES) {
                try {
                    Files.deleteIfExists(dir.resolve(name));
                } catch (IOException | RuntimeException e) {
                    // Best effort: every file is written through OutputFiles, which removes the entry again and
                    // creates the file exclusively, so an entry that cannot be removed is reported when it is
                    // written, never written through (P2-L8-F3).
                }
            }
        }

        // ---------------------------------------------------------------- preconditions, pre-flight, binding

        /**
         * A bind that writes the bindings file without a bindings root is refused before anything runs (8.3 approve
         * step 8): no pack call, nothing sent to the target. Caller-supplied bindings are not bound and need none.
         */
        private void requireBindingsRoot() throws Abort {
            if (provided != null) {
                return;
            }
            try {
                Binder.requireBindingsRoot(plan, options.bind());
            } catch (BindException e) {
                bindRecord(RunLog.fields("ok", false, "mode", options.bind().mode().label(), "envId",
                        options.bind().envId(), "failureKind", e.kind().name(), "error", e.getMessage()));
                throw new Abort(Status.ERROR, e.kind(), null, e.getMessage(), new LinkedHashMap<>(e.detail()));
            }
        }

        /**
         * The controller half of the double opt-in (phase7-contracts.md 8.1, 9.2, decision D332): a plan whose calls
         * carry {@code via: ROBOT} or {@code mode: ROBOT} is refused without {@link RunOptions#robot()}, before
         * {@code requires} is checked and before the agent is asked anything.
         */
        private void refuseRobot() throws Abort {
            if (!options.robot() && plan.usesRobot()) {
                log.write("robot", RunLog.fields("allowed", false, "reason", ROBOT_REFUSAL));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, ROBOT_REFUSAL,
                        RunLog.fields("robot", false));
            }
        }

        /** A bindings file of this plan without resolutions, for approve's second write when the bind wrote none. */
        private BindingsFile emptyFile() {
            return new BindingsFile(BindingsFile.VERSION, plan.scenarioId(), options.bind().envId(), plan.pack(),
                    plan.packVersion(), plan.hash(), plan.resolveHash(), outcome != null && outcome.env() != null
                    ? outcome.env() : new EnvFingerprint(EnvFingerprint.VERSION, ResolutionEngine.hash(
                    new LinkedHashMap<>()), Map.of()), Map.of(), Map.of());
        }

        private void checkRequires() throws Abort, AgentLostException {
            caps = agent.capabilities();
            if (caps == null) {
                caps = new CapabilityMatrix(Map.of());
            }
            installHooks();
            mergeContributed();
            archiveCapabilities();
            List<String> unmet = caps.unmet(plan.requires());
            log.write("requires", RunLog.fields("requires", plan.requires(), "unmet", unmet));
            if (!unmet.isEmpty()) {
                throw new Abort(Status.ERROR, FailureKind.PRECONDITION_FAILED, null,
                        "unmet requirements: " + String.join("; ", unmet), RunLog.fields("unmet", unmet));
            }
        }

        /**
         * Step 0b (phase6-contracts.md 5.3, decision D129): the pack's hook catalogue onto the target under
         * {@code RunOptions.hooks}, between the first read of the matrix and the {@code requires} check, because
         * {@code requires:} may name a {@code hook.<id>} capability that exists only after the install; one record
         * {@code hooks}; the matrix read again when something was installed or found installed. An armed stub is
         * ERROR / ENVIRONMENT in {@code replay}; a refused install ends the run before anything else is sent.
         */
        private void installHooks() throws Abort, AgentLostException {
            HookInstaller.Result result;
            try {
                result = HookInstaller.install(pack, agent, caps, options.hooks(),
                        options.bind().mode() == BindMode.REPLAY, options.clock());
            } catch (HookInstaller.Refused e) {
                log.write("hooks", e.record());
                throw new Abort(Status.ERROR, e.kind(), null, e.getMessage(), e.detail());
            }
            log.write("hooks", result.record());
            if (!result.skipped()) {
                CapabilityMatrix after = agent.capabilities();
                caps = after == null ? new CapabilityMatrix(Map.of()) : after;
            }
        }

        /**
         * phase7-contracts.md 8.5 (D326, D354): the controller-side contributors' capabilities merged into the
         * matrix after step 0b and before {@code requires}, with no host features; {@code replay} forces every
         * contributed id {@code UNAVAILABLE (replay)}. A refused merge ends the run ERROR / ENVIRONMENT.
         */
        private void mergeContributed() throws Abort {
            agentCaps = caps;
            CapabilityMerge.Loaded loaded = CapabilityMerge.load(webPathLoader);
            try {
                CapabilityMerge.Result merged = CapabilityMerge.merge(agentCaps, loaded.contributors(),
                        new CapabilityContributor.CapabilityContext(options.bind().inference(), options.bind().mode(),
                                agentCaps, Set.of(), pack));
                caps = merged.merged();
                contributed = merged.contributed();
            } catch (CapabilityMerge.Refused e) {
                log.write("capabilities", RunLog.fields("ok", false, "error", e.getMessage()));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, e.getMessage(),
                        RunLog.fields("capabilities", "refused"));
            }
            mergePackCapabilities();
        }

        /**
         * phase8-contracts.md 9.1.3, 9.1.5 (gap 106, decisions D610 to D612): the running pack's capabilities, every
         * declared id, evaluated with {@link #preflightContext()} into the matrix after the contributors' merge and
         * before it is archived and {@code requires} is checked, a {@code SUITE} one answered from
         * {@link RunOptions#suite()} under {@code agent.agentSession()}. As built (Phase 8, EP step 1): the seam only; a
         * pack that declares none changes nothing, and the evaluation is not built yet, so a pack that declares any
         * ends the run (an internal error) until it lands.
         */
        private void mergePackCapabilities() {
            List<ai.skillroy.testjava.scenario.pack.PackCapability> declared = pack.capabilities();
            if (declared == null || declared.isEmpty()) {
                return;
            }
            throw new UnsupportedOperationException("pack capabilities are not built yet (phase8-contracts.md 9.1)");
        }

        /**
         * The matrix as the run uses it, archived (phase6-contracts.md 6.4, decision D130): {@code capabilities.json}
         * beside {@code run.jsonl} (with an output directory) and the record {@code capabilities {hash, ids, file,
         * agentHash, contributed}} (phase7-contracts.md 8.5: {@code agentHash} is the agent's matrix after step 0b, what
         * the "one matrix per target" check compares; {@code hash} and the file are the merged matrix's).
         */
        private void archiveCapabilities() {
            Path dir = outDir;
            String file = null;
            if (dir != null) {
                try {
                    OutputFiles.writeString(dir.resolve(CAPABILITIES_FILE), Json.writePretty(mask.apply(
                            caps.toJson())) + "\n");
                    file = CAPABILITIES_FILE;
                } catch (IOException | RuntimeException e) {
                    log.write("capabilities", RunLog.fields("ok", false, "error", e.toString()));
                }
            }
            log.write("capabilities", RunLog.fields("hash", caps.hash(), "ids", (long) caps.all().size(), "file",
                    file, "agentHash", (agentCaps == null ? caps : agentCaps).hash(), "contributed", contributed));
        }

        private void checkLaunchProperties() throws Abort, AgentLostException {
            if (plan.launchProperties().isEmpty()) {
                return;
            }
            Map<String, Object> params = RunLog.fields("names", new ArrayList<>(plan.launchProperties().keySet()));
            RpcResult result;
            try {
                result = agent.call("jvm.properties", params, options(DIAGNOSTIC_TIMEOUT_MS, null));
            } catch (AgentRpcException e) {
                log.write("launchProperties", RunLog.fields("ok", false, "error", e.error().toJson()));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null,
                        "cannot read launch properties: " + e.getMessage(), errorDetail("jvm.properties", e));
            }
            noteSeq(result.seqAfter());
            Map<?, ?> actual = result.value() instanceof Map<?, ?> v && v.get("properties") instanceof Map<?, ?> p
                    ? p : Map.of();
            List<Object> mismatches = new ArrayList<>();
            for (Map.Entry<String, String> e : plan.launchProperties().entrySet()) {
                Object value = actual.get(e.getKey());
                String text = value == null ? null : Values.stringOf(value);
                if (!e.getValue().equals(text)) {
                    mismatches.add(RunLog.fields("property", e.getKey(), "expected", e.getValue(), "actual", text));
                }
            }
            log.write("launchProperties", RunLog.fields("expected", plan.launchProperties(), "actual", actual,
                    "ok", mismatches.isEmpty()));
            if (!mismatches.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> first = (Map<String, Object>) mismatches.get(0);
                Map<String, Object> detail = new LinkedHashMap<>(first);
                detail.put("mismatches", mismatches);
                String actualText = first.get("actual") == null ? "<not set>" : "'" + first.get("actual") + "'";
                throw new Abort(Status.ERROR, FailureKind.PRECONDITION_FAILED, null, "launch property "
                        + first.get("property") + ": expected '" + first.get("expected") + "', actual " + actualText
                        + (mismatches.size() > 1 ? " (and " + (mismatches.size() - 1) + " more)" : ""), detail);
            }
        }

        /**
         * Step 1a (phase4-contracts.md 6.1): for a plan with a {@code web.*} call, the web path that serves it,
         * recorded once as {@code web.path}. A pinned {@code cdp} that cannot be used ends the run before any step.
         *
         * <p>A trace records the pages as they are, a typed secret included, and the run's mask cannot read it
         * (phase4-contracts.md 6.4). So when the scenario asks for one and a step or cleanup call of the plan may
         * announce a secret parameter ({@link SecretParamsForecast}: decided now, from the plan and the caller's
         * bindings, before the bind), the path is opened without tracing, and once a path opens that way
         * {@code web.trace {skipped: true, reason: secrets}} records it. A forecast that fails withholds the trace too
         * (and the record names the failure).
         */
        private void selectWebPath() throws Abort, AgentLostException, InterruptedException {
            if (!hasWebCalls()) {
                return;
            }
            WebSettings settings = plan.effectiveWeb();
            String forecastError = null;
            if (settings.trace()) {
                try {
                    traceWithheld = secretsForecast.test(plan, provided);
                } catch (RuntimeException | StackOverflowError e) {
                    traceWithheld = true;
                    forecastError = e.toString();
                }
            }
            if (traceWithheld) {
                settings = new WebSettings(settings.path(), settings.defaultBrowser(), settings.frozenTargets(),
                        settings.attachDeadline(), false);
            }
            try {
                web.select(settings, caps, new WebContext());
            } catch (WebRun.SelectionFailure e) {
                throw new Abort(Status.ERROR, e.kind, null, e.getMessage(), new LinkedHashMap<>(e.detail));
            }
            if (traceWithheld && web.hasOpenedPath()) {
                Map<String, Object> record = RunLog.fields("skipped", true, "reason", "secrets");
                if (forecastError != null) {
                    record.put("error", forecastError);
                }
                log.write("web.trace", record);
            }
        }

        /** True when a step or cleanup call of the plan is a {@code web.*} primitive. */
        private boolean hasWebCalls() {
            Stream<Plan.Call> calls = Stream.concat(plan.steps().stream().flatMap(step -> step.calls().stream()),
                    plan.cleanup().stream());
            return calls.anyMatch(call -> WebRun.isWeb(call.primitive()));
        }

        /**
         * What a web path may know about the run (the contract's {@code RunContext}): the run's output directory, and
         * a record appender for {@code run.jsonl} (the record's {@code type} names it; the log adds the time and the
         * mask).
         */
        private final class WebContext implements WebRunContext {

            @Override
            public Path artifactsDir() {
                return outDir;
            }

            @Override
            public void record(Map<String, Object> record) {
                if (record == null) {
                    return;
                }
                Map<String, Object> fields = new LinkedHashMap<>(record);
                Object type = fields.remove("type");
                log.write(type == null ? "web" : String.valueOf(type), fields);
            }
        }

        /**
         * The connection fact providers read through (phase4-contracts.md 6.2): a {@code web.*} call goes to the
         * run's web path with the fall-back rules (a refused read is recorded once per primitive and reason), every
         * other call and wait to the agent. A non-agent path's failure outside the agent's error codes is an
         * {@link IllegalStateException}, which fails the fact read as any other failure does.
         */
        private final class FactConnection implements AgentConnection {

            @Override
            public RpcResult call(String method, Map<String, Object> params)
                    throws AgentRpcException, AgentLostException {
                return call(method, params, CallOptions.defaults());
            }

            @Override
            public RpcResult call(String method, Map<String, Object> params, CallOptions callOptions)
                    throws AgentRpcException, AgentLostException {
                if (!WebRun.isWeb(method)) {
                    return agent.call(method, params, callOptions);
                }
                try {
                    return web.call(runner.currentStep == null ? "fact" : runner.currentStep, method,
                            params == null ? Map.of() : params, callOptions == null ? CallOptions.defaults()
                                    : callOptions, true, runner.lastSeenSeq, new String[1]).result();
                } catch (WebRun.PathFailure e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }

            @Override
            public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
                    throws AgentRpcException, AgentLostException {
                return agent.await(topics, sinceSeq, match, timeout);
            }

            @Override
            public CapabilityMatrix capabilities() throws AgentLostException {
                return agent.capabilities();
            }

            @Override
            public List<ai.skillroy.testjava.protocol.PrimitiveDescriptor> schema() throws AgentLostException {
                return agent.schema();
            }

            @Override
            public Map<String, Object> ping() throws AgentLostException {
                return agent.ping();
            }

            @Override
            public boolean isLost() {
                return agent.isLost();
            }

            /** The run's connection's (phase8-contracts.md 9.3.2): no I/O. */
            @Override
            public String agentSession() {
                return agent.agentSession();
            }

            /** The connection is the caller's: never closed here. */
            @Override
            public void close() {
            }
        }

        /** A browser's {@code text} the root {@code web} left out: logged once per browser and reason. */
        private void textOmitted(String ref, String reason) {
            if (noted.add("text\n" + ref + "\n" + reason)) {
                log.write("fact", RunLog.fields("root", "web", "browser", ref, "field", "text", "ok", false,
                        "reason", reason));
            }
        }

        /**
         * {@code failure/web-trace.zip} (phase4-contracts.md 6.4): the opened path's trace, when the scenario set
         * {@code web.trace} and the run writes outputs; recorded as {@code web.trace}: {@code {ok: true, file}} only
         * when the path wrote the file, {@code {ok: false, reason}} when it wrote none (its tracing never started,
         * say) and {@code {ok: false, error}} when it failed. A trace is a binary archive the secret mask cannot
         * read: a path opened without tracing because a call may announce a secret parameter
         * ({@link #selectWebPath}) has none, and, the second guard, a trace is not kept when a call of the run
         * announced one all the same (A34): a filled password would be in its page snapshots.
         */
        private void saveWebTrace() {
            if (!plan.effectiveWeb().trace() || traceWithheld || !web.hasOpenedPath()
                    || outDir == null) {
                return;
            }
            if (runner.secretsSent) {
                log.write("web.trace", RunLog.fields("skipped", true, "reason", "secrets"));
                return;
            }
            Path dir = outDir.resolve("failure");
            try {
                // P2-L8-F3: the directory is the current user's and nothing of that name is left for the path's
                // writer to follow.
                OutputFiles.directory(dir);
                Files.deleteIfExists(dir.resolve("web-trace.zip"));
                if (web.saveTrace(dir.resolve("web-trace.zip"))) {
                    log.write("web.trace", RunLog.fields("ok", true, "file", "failure/web-trace.zip"));
                } else {
                    log.write("web.trace", RunLog.fields("ok", false, "reason", "the " + web.pathId()
                            + " path wrote no trace"));
                }
            } catch (Exception | LinkageError e) {
                log.write("web.trace", RunLog.fields("ok", false, "error", e.toString()));
            }
        }

        /**
         * What the pack's pre-flight checks and its capabilities read (phase2-contracts.md 6.1; phase8-contracts.md
         * 9.1.3): the run's connection, its data channels, its environment id and the running pack's settings; no bind
         * mode, no inference settings, no gateway.
         */
        private PreflightCheck.PreflightContext preflightContext() {
            return new PreflightCheck.PreflightContext() {
                @Override
                public AgentConnection agent() {
                    return agent;
                }

                @Override
                public DataChannel data() {
                    return Run.this.data();
                }

                @Override
                public String envId() {
                    return options.bind().envId();
                }

                @Override
                public PackSettings settings() {
                    PackSettings settings = pack.settings();
                    return settings == null ? PackSettings.EMPTY : settings;
                }
            };
        }

        private void runPreflight() throws Abort, AgentLostException {
            List<PreflightCheck> checks = pack.preflight();
            if (checks == null) {
                return;
            }
            PreflightCheck.PreflightContext ctx = preflightContext();
            for (PreflightCheck check : checks) {
                String reason;
                try {
                    reason = check.check(ctx);
                } catch (AgentLostException e) {
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    reason = "interrupted";
                } catch (Exception e) {
                    reason = "failed: " + e;
                }
                log.write("preflight", RunLog.fields("id", check.id(), "ok", reason == null, "reason", reason));
                if (reason != null) {
                    throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, "pre-flight check " + check.id()
                            + ": " + reason, RunLog.fields("preflight", check.id(), "reason", reason));
                }
            }
        }

        /**
         * Binds the plan (phase2-contracts.md 8) with {@code RunOptions.bind()}, or checks the caller's bindings. A
         * failure is reported with the binder's kind, reason and detail, plus the mode and the environment id.
         */
        private void bind() throws Abort, AgentLostException, InterruptedException {
            // phase2-contracts.md 11.1: the run's clock is the bind clock.
            BindOptions bind = options.bind().withClock(options.clock());
            String mode = bind.mode().label();
            if (provided != null) {
                runner.bindings = provided;
                try {
                    Binder.check(plan, runner.bindings);
                } catch (ResolutionException e) {
                    bindRecord(RunLog.fields("ok", false, "mode", mode, "envId", bind.envId(), "reason",
                            e.reason(), "error", e.getMessage()));
                    throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, null, e.getMessage(),
                            RunLog.fields("reason", e.reason(), "reference", e.reference(), "mode", mode, "envId",
                                    bind.envId()));
                }
            } else {
                BindOutcome outcome;
                try {
                    outcome = Binder.bind(plan, pack, agent, bind, (type, fields) -> {
                        if ("crosscheck".equals(type)) {
                            crossCheckRecords.add(logged(fields));
                        }
                        log.write(type, fields);
                        observeBind(type, fields);
                    }, data(), caps);
                    if (bindOnly && bind.mode() == BindMode.LIVE && !bind.writeBindings() && outcome.file() != null) {
                        // phase2-contracts.md 12.1, 11.4: skillroy resolve --dry-run prints the file it would write,
                        // so a file approve would refuse to write is refused here alike and reaches no output.
                        Binder.requireNoCredential(plan, bind, outcome.file().toJsonText());
                    }
                } catch (BindException e) {
                    Map<String, Object> detail = new LinkedHashMap<>(e.detail());
                    detail.put("mode", mode);
                    detail.put("envId", bind.envId());
                    Map<String, Object> record = RunLog.fields("ok", false, "mode", mode, "envId", bind.envId(),
                            "failureKind", e.kind().name(), "reason", e.reason(), "error", e.getMessage(),
                            "bindingsFile", detail.get("bindingsFile"));
                    if (e.kind() == FailureKind.ORACLE_SUSPECT) {
                        // phase3-contracts.md 8.2: the failing bind record carries the ORACLE_SUSPECT detail.
                        record.put("detail", detail);
                    }
                    bindRecord(record);
                    throw new Abort(Status.ERROR, e.kind(), null, e.getMessage(), detail);
                }
                this.outcome = outcome;
                runner.bindings = outcome.bindings();
                // phase3-contracts.md 6.2: a guarded cross-check that could not run is not a PASS.
                skipped.addAll(outcome.skipped());
                if (outcome.file() != null && (bind.mode() != BindMode.LIVE || bind.writeBindings())) {
                    bindingsFile = outcome.file().envId() + "/" + outcome.file().scenarioId() + BindingsFile.SUFFIX;
                }
                if (outcome.written() != null) {
                    log.write("bind", RunLog.fields("ok", true, "written", bindingsFile));
                }
            }
            mask = mask.plus(runner.bindings.secretValues());
            log.mask(mask);
            if (!plan.images().isEmpty()) {
                baselines = new ImageBaselines(plan.scenarioId(), bind.bindingsRoot() == null ? null
                        : BindingsFile.envDir(bind.bindingsRoot(), bind.envId()), outcome != null ? outcome.file()
                        : providedFile(bind));
            }
            Map<String, Object> record = RunLog.fields("ok", true, "slots", new ArrayList<>(runner.bindings.values()
                    .keySet()), "mode", mode, "envId", bind.envId(), "bindingsFile", bindingsFile);
            if (outcome != null && !outcome.skipped().isEmpty()) {
                record.put("skipped", outcome.skipped());
            }
            bindRecord(record);
            if (outDir != null) {
                try {
                    writeBindings(outDir.resolve("bindings.json"));
                } catch (IOException | RuntimeException e) {
                    log.write("bind", RunLog.fields("ok", true, "writeError", e.toString()));
                }
            }
        }

        /** With caller-supplied bindings: the scenario's bindings file read for its images, or null. */
        private BindingsFile providedFile(BindOptions bind) {
            if (bind.bindingsRoot() == null) {
                return null;
            }
            Path file = BindingsFile.locate(bind.bindingsRoot(), bind.envId(), plan.scenarioId());
            try {
                return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        ? BindingsFile.read(bind.bindingsRoot(), bind.envId(), plan.scenarioId()) : null;
            } catch (IOException | BindingsFormatException e) {
                return null;
            }
        }

        /**
         * A record's fields as {@code run.jsonl} holds them: in JSON form, with the run's secrets masked; the fields
         * as they are (masked) should the codec not be able to write them, which the engine already refuses for a
         * cross-check's values.
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> logged(Map<String, Object> fields) {
            Object json;
            try {
                json = RunLog.jsonForm(fields, 0);
            } catch (RuntimeException e) {
                json = fields;
            }
            return (Map<String, Object>) mask.apply(json);
        }

        /**
         * Before the first step, every source a {@code db} check names must be served by a channel
         * (phase2-contracts.md 7.2): PRECONDITION_FAILED otherwise.
         */
        private void checkSources() throws Abort, AgentLostException {
            Set<String> sources = new LinkedHashSet<>();
            for (RowCheck check : plan.rowChecks()) {
                sources.add(check.source());
            }
            List<String> unserved = new ArrayList<>();
            for (String source : sources) {
                if (!data().serves(source)) {
                    unserved.add(source);
                }
            }
            if (!sources.isEmpty()) {
                log.write("sources", RunLog.fields("sources", new ArrayList<>(sources), "unserved", unserved));
            }
            if (!unserved.isEmpty()) {
                throw new Abort(Status.ERROR, FailureKind.PRECONDITION_FAILED, null, "no data channel serves the"
                        + " source" + (unserved.size() == 1 ? " " : "s ") + String.join(", ", unserved)
                        + " that the plan's db checks read", RunLog.fields("unserved", unserved));
            }
        }

        /**
         * Writes {@code bindings.json} with the secrets masked: by key in the fixture structure (a slot whose name
         * looks secret has its whole value masked), then by value.
         */
        private void writeBindings(Path file) throws IOException {
            Map<String, Object> json = runner.bindings.toJson(plan);
            if (json.get("resolutions") instanceof Map<?, ?> resolutions) {
                Map<String, Object> maskedResolutions = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : resolutions.entrySet()) {
                    String slot = String.valueOf(e.getKey());
                    Object resolution = e.getValue();
                    if (resolution instanceof Map<?, ?> r) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        r.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        Object value = copy.get("value");
                        copy.put("value", SecretKeys.looksSecret(slot) && value != null ? SecretKeys.REDACTED
                                : FixtureSecrets.maskKeys(value));
                        resolution = copy;
                    }
                    maskedResolutions.put(slot, resolution);
                }
                json.put("resolutions", maskedResolutions);
            }
            OutputFiles.writeString(file, Json.writePretty(mask.apply(json)));
        }

        /**
         * Fixes the sequence number the run starts from: waits and prompt checks must never see an event of an
         * earlier scenario, so a run that cannot mark its start does not run its steps.
         */
        private void markStart() throws Abort, AgentLostException {
            Map<String, Object> params = RunLog.fields("label", "skillroy.scenario.start", "attrs",
                    RunLog.fields("scenarioId", plan.scenarioId()));
            RpcResult result;
            try {
                result = agent.call("trace.mark", params, options(DIAGNOSTIC_TIMEOUT_MS, null));
            } catch (AgentRpcException e) {
                log.write("call", RunLog.fields("step", null, "primitive", "trace.mark", "params", params, "ok",
                        false, "error", e.error().toJson()));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, null, "cannot mark the start of the run: "
                        + e.getMessage(), errorDetail("trace.mark", e));
            }
            noteSeq(result.seqAfter());
            startMarked = true;
            stepsSinceSeq = result.seqAfter();
            runner.lastActionSeq = result.seqAfter();
            runner.promptCursor = runner.lastActionSeq;
            log.write("call", RunLog.fields("step", null, "primitive", "trace.mark", "params", params, "ok", true,
                    "value", result.value(), "seq", seq(result)));
        }

        // ---------------------------------------------------------------- steps

        private void runStep(int index) throws Abort, AgentLostException, InterruptedException {
            Plan.Step step = plan.steps().get(index);
            runBlock(step.id(), step.verb(), step.line(), stepSkipped -> {
                for (int j = 0; j < step.calls().size(); j++) {
                    Plan.Call call = step.calls().get(j);
                    if (call.guard() != null) {
                        guard(step, call);
                    }
                    runner.execute(step, j, call, plan.scenarioId() + "/" + step.id() + "/" + (j + 1),
                            () -> nextStepHandlesPrompt(index));
                }
                if (step.facts() != null) {
                    captureFacts(step);
                }
                evaluate(step.expect(), step.id(), stepSkipped);
            });
        }

        /**
         * A {@code captureFacts} step (phase8-contracts.md 8.7, decisions D604, D605): one read per named root, each
         * path resolved once, no retry; the slot bound, {@code capture {step, slot, value, source: facts}} written.
         * With {@link RunOptions#expectationsOnMaskedValues()} a captured value the mask changes fails the step.
         */
        private void captureFacts(Plan.Step step) throws Abort, AgentLostException, InterruptedException {
            Map<String, Object[]> cache = new HashMap<>();
            Map<String, Object> value = FactCapturing.capture(step, runner.bindings, runFacts.keySet(), root -> {
                Object[] fact = cache.get(root);
                if (fact == null) {
                    fact = fetch(root);
                    cache.put(root, fact);
                }
                return fact;
            }, this::secret);
            runner.bindings = runner.bindings.withCaptured(step.facts().slot(), value);
            log.write("capture", RunLog.fields("step", step.id(), "slot", step.facts().slot(), "value", value,
                    "source", "facts"));
        }

        /**
         * An anchored {@code pointer} call's image guard (phase7-contracts.md 6.7, decision D357): the anchor of the
         * current render key compared before the pointer call is sent; a mismatch, or no anchor for the key, is
         * REBIND_NEEDED (reason {@code IMAGE}) in every mode.
         */
        private void guard(Plan.Step step, Plan.Call call) throws Abort, AgentLostException, InterruptedException {
            Object args;
            try {
                args = runner.bindings.substitute(call.args());
            } catch (ResolutionException e) {
                throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, step.id(), e.getMessage(),
                        RunLog.fields("reason", e.reason(), "reference", e.reference()));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> substituted = args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            ImageRuns.raise(step.id(), ImageRuns.run(step.id(), () -> imageSteps().guard(step.id(), call.guard(),
                    substituted)));
        }

        /** The run's image steps, built on first need (phase7-contracts.md 6.7). */
        private ImageSteps imageSteps() {
            if (imageSteps == null) {
                imageSteps = new ImageSteps(new ImageSteps.Host() {
                    @Override
                    public Object read(String primitive, Map<String, Object> args) throws AgentRpcException,
                            AgentLostException {
                        RpcResult result = agent.call(primitive, args, options(Math.max(stepTimeoutMs, 10_000L),
                                null));
                        noteSeq(result.seqAfter());
                        return result.value();
                    }

                    @Override
                    public void record(Map<String, Object> fields) {
                        log.write("image", fields);
                    }

                    @Override
                    public Path outputDir() {
                        return outDir;
                    }

                    @Override
                    public BindMode mode() {
                        return options.bind().mode();
                    }

                    @Override
                    public String scenarioId() {
                        return plan.scenarioId();
                    }

                    @Override
                    public String envId() {
                        return options.bind().envId();
                    }

                    @Override
                    public Approver approver() {
                        return options.bind().approver();
                    }
                }, baselines);
            }
            return imageSteps;
        }

        private void runVerify() throws Abort, AgentLostException, InterruptedException {
            if (!plan.verify().isEmpty()) {
                runBlock("verify", "verify", 0, stepSkipped -> evaluate(plan.verify(), "verify", stepSkipped));
            }
        }

        /** Runs one step (or the verify block) and records its {@link StepResult} however it ends. */
        private void runBlock(String id, String verb, int line, Block body)
                throws Abort, AgentLostException, InterruptedException {
            runner.currentStep = id;
            stepsStarted = true;
            if (observing) {
                observer.stepStarted(new StepStart(id, verb, line));
            }
            long t0 = options.clock().millis();
            List<String> stepSkipped = new ArrayList<>();
            runner.stepFidelity = new ArrayList<>();
            Status status = Status.ERROR;
            String message = null;
            try {
                body.run(stepSkipped);
                status = stepSkipped.isEmpty() && runner.stepFidelity.isEmpty() ? Status.PASS : Status.DEGRADED;
            } catch (Abort abort) {
                status = abort.status;
                message = abort.getMessage();
                throw abort;
            } catch (AgentLostException e) {
                message = "agent lost: " + e.getMessage();
                throw e;
            } catch (InterruptedException e) {
                message = "interrupted";
                throw e;
            } catch (RuntimeException | StackOverflowError | LinkageError e) {
                // P2-L8-F4: the class only; the run's detail keeps the exception's text.
                message = "internal error: " + e.getClass().getName();
                throw e;
            } finally {
                StepResult result = new StepResult(id, verb, line, status, message, stepSkipped, since(t0),
                        runner.stepFidelity);
                steps.add(result);
                if (observing) {
                    observer.stepEnded(new StepResult(id, verb, line, status, mask.apply(message),
                            texts(stepSkipped), result.duration(), texts(result.degradedFidelity())));
                }
            }
        }


        /**
         * The slots whose values the run's mask collected by A25's key rule ({@link #secretMask()}): the caller's
         * slots when the caller supplied the bindings, else the plan's fixture slots.
         */
        private Set<String> keyRuleSlots() {
            Set<String> slots = new LinkedHashSet<>();
            if (provided != null) {
                slots.addAll(provided.values().keySet());
            } else {
                for (Plan.Slot slot : plan.slots()) {
                    if ("fixture".equals(slot.kind())) {
                        slots.add(slot.name());
                    }
                }
            }
            return slots;
        }

        private boolean nextStepHandlesPrompt(int stepIndex) {
            for (int k = stepIndex + 1; k < plan.steps().size(); k++) {
                List<Plan.Call> calls = plan.steps().get(k).calls();
                if (!calls.isEmpty()) {
                    return calls.stream().anyMatch(Plan.Call::handlesPrompt);
                }
            }
            return false;
        }

        // ---------------------------------------------------------------- expectations

        private void evaluate(List<Check> checks, String stepId, List<String> stepSkipped)
                throws Abort, AgentLostException, InterruptedException {
            if (checks.isEmpty()) {
                return;
            }
            List<Bound> active = new ArrayList<>();
            List<BoundRow> rows = new ArrayList<>();
            for (Check check : checks) {
                if (check instanceof VisualCheck visual) {
                    // phase7-contracts.md 8.4 (D312, D354): no visual evaluator in this host, whatever the state.
                    Capability capability = caps.get(visual.capability());
                    String state = capability == null ? "missing" : capability.state().name()
                            + (capability.reason() == null || capability.reason().isBlank() ? ""
                            : " (" + capability.reason() + ")");
                    String description = stepId + ": " + skippedText(check) + " skipped: no visual evaluator in this"
                            + " host; capability " + visual.capability() + " is " + state;
                    skipped.add(description);
                    stepSkipped.add(description);
                    log.write("expect", RunLog.fields("step", stepId, "visual", RunLog.fields("question",
                            visual.question(), "expect", visual.expect()), "capability", visual.capability(),
                            "result", "skipped", "state", state, "reason", "no visual evaluator in this host"));
                    continue;
                }
                if (check instanceof ImageCheck image) {
                    // phase7-contracts.md 6.7: from the step's own capture call's result, one compare, no retry.
                    Object capture = runner.lastResult == null ? null : runner.lastResult.value();
                    String line = ImageRuns.raise(stepId, ImageRuns.run(stepId, () -> imageSteps().check(stepId,
                            image, capture)));
                    if (line != null) {
                        skipped.add(line);
                        stepSkipped.add(line);
                    }
                    continue;
                }
                if (check.capability() != null && !caps.isOk(check.capability())) {
                    Capability capability = caps.get(check.capability());
                    String state = capability == null ? "missing" : capability.state().name()
                            + (capability.reason() == null || capability.reason().isBlank() ? ""
                            : " (" + capability.reason() + ")");
                    String description = stepId + ": " + skippedText(check) + " skipped: capability "
                            + check.capability() + " is " + state;
                    skipped.add(description);
                    stepSkipped.add(description);
                    Map<String, Object> record = check instanceof Expectation e
                            ? RunLog.fields("step", stepId, "path", e.path(), "matcher", e.matcher())
                            : RunLog.fields("step", stepId, "table", ((RowCheck) check).table(), "source",
                            ((RowCheck) check).source());
                    record.putAll(RunLog.fields("capability", check.capability(), "result", "skipped", "state",
                            state));
                    log.write(check instanceof Expectation ? "expect" : "db", record);
                    continue;
                }
                if (check instanceof RowCheck row) {
                    rows.add(bindRow(row, stepId));
                    continue;
                }
                Expectation expectation = (Expectation) check;
                try {
                    ExpectationPath path = ExpectationPath.parse(expectation.path())
                            .bind(runner.bindings::lookupPublic);
                    if (!runFacts.containsKey(path.root())) {
                        throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "no fact provider for root '"
                                + path.root() + "' of " + expectation.path() + "; roots: " + runFacts.keySet(),
                                RunLog.fields("path", expectation.path()));
                    }
                    active.add(new Bound(expectation, path, runner.bindings.substitutePublic(expectation.matcher())));
                } catch (ResolutionException e) {
                    throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, stepId, e.getMessage(),
                            RunLog.fields("reason", e.reason(), "reference", e.reference(), "path",
                                    expectation.path()));
                } catch (IllegalArgumentException e) {
                    throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "invalid expectation in the"
                            + " plan: " + e.getMessage(), RunLog.fields("path", expectation.path()));
                }
            }
            if (active.isEmpty() && rows.isEmpty()) {
                return;
            }
            long deadline = options.clock().millis() + stepTimeoutMs;
            List<Attempt> last = new ArrayList<>(Collections.nCopies(active.size(), null));
            List<Attempt> lastRows = new ArrayList<>(Collections.nCopies(rows.size(), null));
            int attempts = 0;
            boolean allHold;
            while (true) {
                attempts++;
                Map<String, Object[]> cache = new HashMap<>();
                allHold = true;
                for (int i = 0; i < active.size(); i++) {
                    Attempt attempt = attempt(active.get(i), cache);
                    last.set(i, attempt);
                    allHold &= attempt.holds();
                }
                for (int i = 0; i < rows.size(); i++) {
                    Attempt attempt = attemptRow(rows.get(i), stepId);
                    lastRows.set(i, attempt);
                    allHold &= attempt.holds();
                }
                if (allHold || options.clock().millis() >= deadline) {
                    break;
                }
                options.sleeper().sleep(options.pollInterval());
            }
            List<Object> failures = new ArrayList<>();
            int firstFailure = -1;
            for (int i = 0; i < active.size(); i++) {
                Bound bound = active.get(i);
                Attempt attempt = last.get(i);
                log.write("expect", RunLog.fields("step", stepId, "path", bound.path().text(), "matcher",
                        bound.matcher(), "capability", bound.source().capability(), "result",
                        attempt.holds() ? "held" : "failed", "resolved", attempt.resolved(), "actual",
                        attempt.actual(), "reason", attempt.reason(), "attempts", attempts));
                if (!attempt.holds()) {
                    failures.add(RunLog.fields("path", bound.path().text(), "matcher", bound.matcher(), "reason",
                            attempt.reason()));
                    if (firstFailure < 0) {
                        firstFailure = i;
                    }
                }
            }
            int firstRowFailure = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (!lastRows.get(i).holds()) {
                    BoundRow row = rows.get(i);
                    failures.add(RunLog.fields("db", row.source().table(), "params", row.params(), "reason",
                            lastRows.get(i).reason()));
                    if (firstRowFailure < 0) {
                        firstRowFailure = i;
                    }
                }
            }
            if (firstFailure < 0 && firstRowFailure < 0) {
                return;
            }
            if (firstFailure < 0) {
                BoundRow row = rows.get(firstRowFailure);
                Attempt attempt = lastRows.get(firstRowFailure);
                Map<String, Object> detail = RunLog.fields("db", row.source().table(), "source",
                        row.source().source(), "where", row.params(), "rows", row.rows(), "expect", row.expect());
                if (attempt.actual() instanceof Map<?, ?> found) {
                    found.forEach((k, v) -> detail.put(String.valueOf(k), v));
                }
                detail.putAll(RunLog.fields("reason", attempt.reason(), "attempts", attempts, "timeoutMs",
                        stepTimeoutMs, "failures", failures));
                throw new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, "db check failed: db."
                        + row.source().table() + " " + Values.safeWrite(row.params()) + ": " + attempt.reason(),
                        detail);
            }
            Bound bound = active.get(firstFailure);
            Attempt attempt = last.get(firstFailure);
            // phase8-contracts.md 8.10 (D608): the detail's actual cut as the expect record's is.
            Map<String, Object> detail = RunLog.cutActual(RunLog.fields("path", bound.path().text(), "matcher",
                    bound.matcher(), "actual", attempt.actual(), "resolved", attempt.resolved(), "reason",
                    attempt.reason(), "attempts", attempts, "timeoutMs", stepTimeoutMs, "failures", failures));
            if (attempt.fetchError() != null) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "fact '" + bound.path().root()
                        + "' could not be read: " + attempt.fetchError(), detail);
            }
            throw new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, "expectation failed: " + bound.path().text()
                    + " " + Values.safeWrite(bound.matcher()) + ": " + attempt.reason(), detail);
        }

        private String skippedText(Check check) {
            if (check instanceof Expectation e) {
                return e.path() + " " + Values.safeWrite(e.matcher());
            }
            if (check instanceof VisualCheck || check instanceof ImageCheck) {
                return check.describe();
            }
            RowCheck row = (RowCheck) check;
            return "db." + row.table() + " " + Values.safeWrite(row.expect());
        }

        /**
         * A row check with its parameters substituted and converted by kind (phase2-contracts.md 7.4 step 1): a
         * value that is null or does not convert is RESOLUTION_FAILED reason REFERENCE.
         */
        private BoundRow bindRow(RowCheck row, String stepId) throws Abort {
            StoredTable table = null;
            List<StoredTable> declared;
            try {
                declared = pack.storedTables();
            } catch (RuntimeException e) {
                declared = List.of();
            }
            for (StoredTable t : declared == null ? List.<StoredTable>of() : declared) {
                if (t != null && t.name().equals(row.table())) {
                    table = t;
                }
            }
            if (table == null) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "the plan checks db." + row.table()
                        + ", which pack " + pack.id() + " " + pack.version() + " does not declare; recompile",
                        RunLog.fields("db", row.table()));
            }
            List<Object> params = new ArrayList<>();
            try {
                for (RowCheck.Param param : row.params()) {
                    Object value = runner.bindings.substitutePublic(param.value());
                    params.add(convert(param.kind(), value, row, stepId));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> expect = (Map<String, Object>) runner.bindings.substitutePublic(row.expect());
                @SuppressWarnings("unchecked")
                Map<String, Object> aggregates = (Map<String, Object>) runner.bindings.substitutePublic(
                        row.aggregates());
                return new BoundRow(row, table, params, runner.bindings.substitutePublic(row.rows()), expect,
                        aggregates);
            } catch (ResolutionException e) {
                throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, stepId, "db." + row.table() + ": "
                        + e.getMessage(), RunLog.fields("reason", e.reason(), "reference", e.reference(), "db",
                        row.table()));
            }
        }

        /** A parameter in the form of phase2-contracts.md 7.1 for its column kind. */
        private Object convert(ColumnKind kind, Object value, RowCheck row, String stepId) throws Abort {
            Object converted = value == null ? null : switch (kind) {
                case TEXT -> value instanceof Map || value instanceof List ? null : Values.stringOf(value);
                case INTEGER -> {
                    BigDecimal d = Values.decimal(value);
                    try {
                        BigInteger i = d == null ? null : d.toBigIntegerExact();
                        yield i == null || i.bitLength() > 63 ? null : (Object) i.longValue();
                    } catch (ArithmeticException e) {
                        yield null;
                    }
                }
                case DECIMAL -> {
                    BigDecimal d = Values.decimal(value);
                    yield d == null ? null : d.toPlainString();
                }
                case BOOLEAN -> value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value))
                        ? Boolean.TRUE : "false".equalsIgnoreCase(String.valueOf(value)) ? Boolean.FALSE : null;
                case TIMESTAMP -> sqlTimestamp(value);
            };
            if (converted == null) {
                String why = value == null ? "is null; the statement was compiled for a value"
                        : kind == ColumnKind.TIMESTAMP ? "must be a local date-time such as 2026-10-02 10:00:00"
                        + " (yyyy-MM-dd HH:mm:ss[.fraction], or with a T between date and time), without an offset"
                        + " or Z; a date alone is not one"
                        : "does not convert to " + kind + "; the statement was compiled for a value";
                throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, stepId, "db." + row.table() + ": a where"
                        + " value " + why, RunLog.fields("reason", ResolutionException.REFERENCE, "db", row.table(),
                        "kind", kind.name()));
            }
            return converted;
        }

        /**
         * One attempt at a row check (7.4 steps 2-4): a {@link DataException} or a wrong column count ends the step
         * as ERROR / ENVIRONMENT at once; a truncated result, a row count or a column value that does not match does
         * not hold (and is retried).
         */
        private Attempt attemptRow(BoundRow row, String stepId) throws Abort, AgentLostException,
                InterruptedException {
            RowCheck check = row.source();
            String channel = data().kind(check.source());
            Rows result;
            try {
                result = data().query(check.source(), check.sql(), row.params(), DataChannel.MAX_ROWS, null);
            } catch (DataException e) {
                log.write("db", RunLog.fields("step", stepId, "table", check.table(), "source", check.source(),
                        "channel", channel, "params", row.params(), "ok", false, "error", e.getMessage()));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "db." + check.table() + ": the"
                        + " statement on source " + check.source() + " failed (" + e.kind() + "): " + e.getMessage(),
                        RunLog.fields("db", check.table(), "source", e.source(), "dataKind", e.kind().name(),
                                "sqlState", e.sqlState(), "errorCode", e.errorCode(), "exceptionClass",
                                e.exceptionClass(), "rpcCode", e.rpcCode()));
            } catch (IllegalArgumentException e) {
                log.write("db", RunLog.fields("step", stepId, "table", check.table(), "source", check.source(),
                        "channel", channel, "params", row.params(), "ok", false, "error", e.getMessage()));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "db." + check.table() + ": "
                        + e.getMessage(), RunLog.fields("db", check.table()));
            }
            List<StoredColumn> columns = row.table().columns();
            if (result.columns().size() != columns.size()) {
                log.write("db", RunLog.fields("step", stepId, "table", check.table(), "source", check.source(),
                        "channel", channel, "params", row.params(), "rowCount", result.rowCount(), "ok", false));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "db." + check.table() + ": the"
                        + " statement returned " + result.columns().size() + " columns, the pack declares "
                        + columns.size(), RunLog.fields("db", check.table()));
            }
            Map<String, Object> computed = new LinkedHashMap<>();
            Attempt attempt = rowAttempt(row, result, columns, computed);
            Map<String, Object> record = RunLog.fields("step", stepId, "table", check.table(), "source",
                    check.source(), "channel", channel, "params", row.params(), "rowCount", result.rowCount(), "ok",
                    attempt.holds());
            if (!computed.isEmpty()) {
                record.put("aggregates", computed);
            }
            log.write("db", record);
            return attempt;
        }

        /**
         * One attempt's verdict on the rows (7.4 steps 3-4, phase3-contracts.md 7 step 5): truncation first, then the
         * row count, every row's column matchers, and only then the aggregates, whose values go into
         * {@code computed}.
         */
        private Attempt rowAttempt(BoundRow row, Rows result, List<StoredColumn> columns,
                                   Map<String, Object> computed) {
            Map<String, Object> found = RunLog.fields("rowCount", result.rowCount());
            if (result.truncated()) {
                return new Attempt(false, true, found, "more than " + DataChannel.MAX_ROWS + " rows match; narrow"
                        + " the where", null);
            }
            Matchers.Outcome count = secret((long) result.rowCount()) != null
                    ? new Matchers.Outcome(false, Matchers.HOLDS_A_SECRET)
                    : Matchers.evaluate(row.rows(), true, (long) result.rowCount(), null);
            if (!count.holds()) {
                return new Attempt(false, true, found, "row count: " + count.reason(), null);
            }
            for (int r = 0; r < result.rowCount(); r++) {
                for (Map.Entry<String, Object> e : row.expect().entrySet()) {
                    int index = -1;
                    for (int c = 0; c < columns.size(); c++) {
                        if (columns.get(c).name().equals(e.getKey())) {
                            index = c;
                        }
                    }
                    Object value = index < 0 ? null : result.get(r, index);
                    Matchers.Outcome outcome = index >= 0 && secret(value) != null
                            ? new Matchers.Outcome(false, Matchers.HOLDS_A_SECRET)
                            : Matchers.evaluate(e.getValue(), index >= 0, value, "no column " + e.getKey());
                    if (!outcome.holds()) {
                        Map<String, Object> first = new LinkedHashMap<>();
                        for (int c = 0; c < columns.size(); c++) {
                            Object cell = result.get(r, c);
                            Object masked = secret(cell);
                            first.put(columns.get(c).name(), masked != null ? masked : cell);
                        }
                        found.put("row", first);
                        found.put("column", e.getKey());
                        return new Attempt(false, true, found, "row " + (r + 1) + " column " + e.getKey() + ": "
                                + outcome.reason(), null);
                    }
                }
            }
            Map<String, Object> failedAggregates = new LinkedHashMap<>();
            String firstReason = null;
            for (Map.Entry<String, Object> e : row.aggregates().entrySet()) {
                RowCheck.Aggregate aggregate = RowCheck.Aggregate.parse(e.getKey());
                int index = -1;
                for (int c = 0; aggregate != null && c < columns.size(); c++) {
                    if (columns.get(c).name().equals(aggregate.column())) {
                        index = c;
                    }
                }
                Object value = null;
                String problem;
                if (index < 0) {
                    problem = "no column " + (aggregate == null ? e.getKey() : aggregate.column());
                } else {
                    Aggregates.Result computedValue = Aggregates.compute(aggregate.fn(), columns.get(index).kind(),
                            result, index, aggregate.column());
                    value = computedValue.value();
                    problem = computedValue.problem();
                    Object masked = problem == null ? secret(value) : null;
                    if (masked != null) {
                        value = masked;
                        problem = Matchers.HOLDS_A_SECRET;
                    }
                }
                computed.put(e.getKey(), value);
                Matchers.Outcome outcome = problem != null ? new Matchers.Outcome(false, problem)
                        : Matchers.evaluate(e.getValue(), true, value, null);
                if (!outcome.holds()) {
                    failedAggregates.put(e.getKey(), RunLog.fields("expected", e.getValue(), "actual", value));
                    if (firstReason == null) {
                        firstReason = e.getKey() + ": " + outcome.reason();
                    }
                }
            }
            if (!failedAggregates.isEmpty()) {
                found.put("aggregates", failedAggregates);
                return new Attempt(false, true, found, "aggregate " + firstReason, null);
            }
            return new Attempt(true, true, found, null, null);
        }

        /** One attempt; {@code cache} holds {value} or {null, error} per root for this attempt. */
        private Attempt attempt(Bound bound, Map<String, Object[]> cache) throws AgentLostException,
                InterruptedException {
            String root = bound.path().root();
            Object[] fact = cache.get(root);
            if (fact == null) {
                fact = fetch(root);
                cache.put(root, fact);
            }
            if (fact.length == 2) {
                return new Attempt(false, false, null, "fact '" + root + "' could not be read: " + fact[1],
                        (String) fact[1]);
            }
            ExpectationPath.Resolution resolution = bound.path().resolve(fact[0]);
            Object secret = resolution.resolved() ? secret(resolution.value()) : null;
            if (secret != null) {
                return new Attempt(false, true, secret, Matchers.HOLDS_A_SECRET, null);
            }
            Matchers.Outcome outcome = Matchers.evaluate(bound.matcher(), resolution.resolved(), resolution.value(),
                    resolution.problem());
            return new Attempt(outcome.holds(), resolution.resolved(), resolution.value(), outcome.reason(), null);
        }

        /**
         * With {@link RunOptions#expectationsOnMaskedValues()}, a value the run's mask changes holds a secret and
         * satisfies no matcher, as in a session's check (phase5-contracts.md 12.2): its masked form, else null. Null
         * always without the option (a scenario a person committed is evaluated on the values as read).
         */
        private Object secret(Object value) {
            return options.expectationsOnMaskedValues() ? Matchers.maskedSecret(mask, value) : null;
        }

        private Object[] fetch(String root) throws AgentLostException, InterruptedException {
            try {
                return new Object[] {runFacts.get(root).fetch(factConnection)};
            } catch (AgentLostException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                return new Object[] {null, e.getMessage() == null ? e.toString() : e.getMessage()};
            }
        }

        // ---------------------------------------------------------------- end of run

        private Abort lost(AgentLostException e) {
            runner.agentLost = true;
            boolean restarted = false;
            if (options.targetRestarted() != null) {
                try {
                    restarted = Boolean.TRUE.equals(options.targetRestarted().get());
                } catch (RuntimeException probeFailure) {
                    restarted = false;
                }
            }
            FailureKind kind = restarted ? FailureKind.TARGET_RESTARTED : FailureKind.AGENT_LOST;
            return new Abort(Status.ERROR, kind, runner.currentStep, (restarted ? "the target restarted: "
                    : "the agent was lost: ") + e.getMessage(), RunLog.fields("exception", e.toString()));
        }

        private void collectEvidence(Map<String, Object> detail) {
            Path dir = outDir == null ? null : outDir.resolve("failure");
            boolean[] lost = {false};
            Object snapshot = evidence("swing.snapshot", Map.of(), 10_000L, lost);
            writeJson(dir, "snapshot.json", snapshot);
            Object appState = evidence("app.state", Map.of(), DIAGNOSTIC_TIMEOUT_MS, lost);
            writeJson(dir, "app-state.json", appState);
            Object browsers = webBrowsers(lost);
            List<UiSnapshotText.WebView> views = webViews(browsers, lost);
            if (snapshot != null || appState != null || !views.isEmpty()) {
                // Masked as values first, so that a secret the UI shows never reaches the text unquoted.
                List<UiSnapshotText.WebView> masked = new ArrayList<>();
                for (UiSnapshotText.WebView view : views) {
                    masked.add(new UiSnapshotText.WebView((Map<?, ?>) mask.apply(view.browser()),
                            mask.apply(view.aria()), view.path()));
                }
                detail.put("snapshot", UiSnapshotText.render(mask.apply(snapshot), mask.apply(appState), masked,
                        UiSnapshotText.DEFAULT_BUDGET));
            }
            if (dir == null) {
                return;
            }
            writeJson(dir, "dialogs.json", evidence("swing.dialogs", Map.of(), DIAGNOSTIC_TIMEOUT_MS, lost));
            if (caps != null && caps.isOk("swing.capture.paint")) {
                writePng(dir.resolve("capture.png"), evidence("swing.capture", Map.of(), 15_000L, lost));
            } else {
                log.write("evidence", RunLog.fields("kind", "swing.capture", "ok", false, "reason",
                        "capability swing.capture.paint is not OK"));
            }
            Object probe = evidence("evt.query", RunLog.fields("since", runner.lastSeenSeq, "limit", 1L),
                    DIAGNOSTIC_TIMEOUT_MS, lost);
            if (probe instanceof Map<?, ?> p && p.get("lastSeq") instanceof Long lastSeq) {
                writeJson(dir, "events.json", evidence("evt.query", RunLog.fields("since",
                        Math.max(0, lastSeq - EVENT_EVIDENCE), "limit", EVENT_EVIDENCE), DIAGNOSTIC_TIMEOUT_MS, lost));
            }
            writeWebEvidence(dir, browsers, lost);
        }

        /**
         * The {@code web.browsers} value for the evidence (phase4-contracts.md 6.4) when the target's
         * {@code web.browsers} capability is present and not UNAVAILABLE and it lists at least one browser; else null.
         */
        private Object webBrowsers(boolean[] lost) {
            Capability capability = caps == null ? null : caps.get("web.browsers");
            if (capability == null || capability.state() == CapabilityState.UNAVAILABLE) {
                return null;
            }
            WebRead read = webEvidence("web.browsers", Map.of(), 10_000L, lost);
            if (read == null || !(read.value() instanceof Map<?, ?> value)
                    || !(value.get("browsers") instanceof List<?> list) || list.isEmpty()) {
                return null;
            }
            return read.value();
        }

        /** The browsers of {@code web.browsers} in ref order. */
        private List<Map<?, ?>> browsersInRefOrder(Object browsers) {
            List<Map<?, ?>> out = new ArrayList<>();
            if (browsers instanceof Map<?, ?> value && value.get("browsers") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> browser && browser.get("ref") != null) {
                        out.add(browser);
                    }
                }
            }
            out.sort(Comparator.comparing(b -> String.valueOf(b.get("ref")), PlanExecutor::compareRefs));
            return out;
        }

        /**
         * The {@code WEB} surface (phase4-contracts.md 6.2): every browser in ref order, a visible one with its
         * {@code web.ariaSnapshot {maxNodes: 200, interestingOnly: true}} read through the run's web path.
         */
        private List<UiSnapshotText.WebView> webViews(Object browsers, boolean[] lost) {
            List<UiSnapshotText.WebView> views = new ArrayList<>();
            for (Map<?, ?> browser : browsersInRefOrder(browsers)) {
                if (!Boolean.TRUE.equals(browser.get("visibleInSwing"))) {
                    views.add(new UiSnapshotText.WebView(browser, null, null));
                    continue;
                }
                WebRead aria = webEvidence("web.ariaSnapshot", RunLog.fields("browser", String.valueOf(
                        browser.get("ref")), "maxNodes", 200L, "interestingOnly", true), 10_000L, lost);
                views.add(new UiSnapshotText.WebView(browser, aria == null ? null : aria.value(),
                        aria == null ? null : aria.path()));
            }
            return views;
        }

        /**
         * The web evidence files (phase4-contracts.md 6.4): {@code web-browsers.json}, and for each browser visible in
         * Swing, at most four in ref order, {@code web-<ref>-aria.json} ({@code web.ariaSnapshot {maxNodes: 2000}})
         * and {@code web-<ref>.png} ({@code web.bitmap}), or {@code web-<ref>-error.txt} with the error code of each
         * read that failed. Nothing when the target has no indexed browser.
         */
        private void writeWebEvidence(Path dir, Object browsers, boolean[] lost) {
            if (browsers == null) {
                return;
            }
            writeJson(dir, "web-browsers.json", browsers);
            int kept = 0;
            for (Map<?, ?> browser : browsersInRefOrder(browsers)) {
                if (kept >= WEB_EVIDENCE_BROWSERS || lost[0] || runner.agentLost) {
                    break;
                }
                if (!Boolean.TRUE.equals(browser.get("visibleInSwing"))) {
                    continue;
                }
                kept++;
                String ref = String.valueOf(browser.get("ref"));
                String name = WEB_EVIDENCE_PREFIX + fileSafe(ref);
                List<String> errors = new ArrayList<>();
                WebRead aria = webEvidence("web.ariaSnapshot", RunLog.fields("browser", ref, "maxNodes", 2000L),
                        10_000L, lost, errors);
                if (aria != null) {
                    writeJson(dir, name + "-aria.json", aria.value());
                }
                WebRead bitmap = webEvidence("web.bitmap", RunLog.fields("browser", ref), 20_000L, lost, errors);
                if (bitmap != null) {
                    writePng(dir.resolve(name + ".png"), bitmap.value());
                }
                if (!errors.isEmpty()) {
                    writeText(dir, name + "-error.txt", String.join("\n", errors) + "\n");
                }
            }
        }

        /** A web read for the evidence or the surface, and the path that served it. */
        private record WebRead(Object value, String path) {
        }

        private WebRead webEvidence(String primitive, Map<String, Object> params, long timeoutMs, boolean[] lost) {
            return webEvidence(primitive, params, timeoutMs, lost, new ArrayList<>());
        }

        /**
         * One web read through the run's web path (the fall-back rules apply), logged as an {@code evidence} record;
         * null when it failed, its {@code <primitive>: <CODE> (<code>): <message>} line then added to {@code errors}.
         */
        private WebRead webEvidence(String primitive, Map<String, Object> params, long timeoutMs, boolean[] lost,
                                    List<String> errors) {
            if (lost[0] || runner.agentLost) {
                return null;
            }
            String[] servedBy = new String[1];
            try {
                WebRun.Served served = web.call(runner.currentStep == null ? "evidence" : runner.currentStep,
                        primitive, params, options(timeoutMs, null), true, runner.lastSeenSeq, servedBy);
                log.write("evidence", RunLog.fields("kind", primitive, "ok", true, "path", served.path()));
                return new WebRead(served.result().value(), served.path());
            } catch (AgentRpcException e) {
                RpcError error = e.error();
                errors.add(primitive + ": " + ErrorCodes.name(error.code()) + " (" + error.code() + "): "
                        + error.message());
                log.write("evidence", RunLog.fields("kind", primitive, "ok", false, "path", servedBy[0], "error",
                        error.toJson()));
            } catch (AgentLostException e) {
                lost[0] = true;
                runner.agentLost = true;
                log.write("evidence", RunLog.fields("kind", primitive, "ok", false, "error", e.toString()));
            } catch (WebRun.PathFailure | RuntimeException e) {
                errors.add(primitive + ": " + e.getMessage());
                log.write("evidence", RunLog.fields("kind", primitive, "ok", false, "path", servedBy[0], "error",
                        e.toString()));
            }
            return null;
        }

        /** A ref as part of a file name: letters, digits, {@code -} and {@code _}; anything else becomes {@code _}. */
        private static String fileSafe(String ref) {
            String safe = ref.replaceAll("[^A-Za-z0-9_-]", "_");
            return safe.isEmpty() ? "_" : safe.length() > 64 ? safe.substring(0, 64) : safe;
        }

        private void writeText(Path dir, String name, String text) {
            try {
                OutputFiles.writeString(dir.resolve(name), mask.apply(Images.elidedText(text)));
            } catch (IOException e) {
                log.write("evidence", RunLog.fields("file", name, "ok", false, "error", e.toString()));
            }
        }

        private Object evidence(String primitive, Map<String, Object> params, long timeoutMs, boolean[] lost) {
            if (lost[0] || runner.agentLost) {
                return null;
            }
            try {
                RpcResult result = agent.call(primitive, params, options(timeoutMs, null));
                log.write("evidence", RunLog.fields("kind", primitive, "ok", true));
                return result.value();
            } catch (AgentRpcException e) {
                log.write("evidence", RunLog.fields("kind", primitive, "ok", false, "error", e.error().toJson()));
            } catch (AgentLostException e) {
                lost[0] = true;
                runner.agentLost = true;
                log.write("evidence", RunLog.fields("kind", primitive, "ok", false, "error", e.toString()));
            } catch (RuntimeException e) {
                log.write("evidence", RunLog.fields("kind", primitive, "ok", false, "error", e.toString()));
            }
            return null;
        }

        private void writeJson(Path dir, String name, Object value) {
            if (dir == null || value == null) {
                return;
            }
            try {
                // P2-L6-F2: a failure file carries no image's bytes; the pixels go to the PNG files only.
                OutputFiles.writeString(dir.resolve(name), RunLog.pretty(mask.apply(Images.elided(value))));
            } catch (IOException | JsonException e) {
                log.write("evidence", RunLog.fields("file", name, "ok", false, "error", e.toString()));
            }
        }

        private void writePng(Path file, Object capture) {
            if (!(capture instanceof Map<?, ?> map) || !(map.get("base64") instanceof String base64)) {
                return;
            }
            try {
                OutputFiles.write(file, Base64.getMimeDecoder().decode(base64));
            } catch (IOException | IllegalArgumentException e) {
                log.write("evidence", RunLog.fields("file", file.getFileName().toString(), "ok", false, "error",
                        e.toString()));
            }
        }

        private void keepArtifacts() {
            for (String kind : plan.artifacts()) {
                if (!"capture".equals(kind)) {
                    log.write("artifact", RunLog.fields("kind", kind, "ok", false, "reason",
                            "unknown artifact kind; Phase 1 keeps 'capture' only"));
                    continue;
                }
                if (outDir == null) {
                    continue;
                }
                if (caps == null || !caps.isOk("swing.capture.paint")) {
                    log.write("artifact", RunLog.fields("kind", kind, "ok", false, "reason",
                            "capability swing.capture.paint is not OK"));
                    continue;
                }
                Object capture = evidence("swing.capture", Map.of(), 15_000L, new boolean[1]);
                writePng(outDir.resolve("artifacts").resolve("capture.png"), capture);
                log.write("artifact", RunLog.fields("kind", kind, "ok", capture != null));
            }
        }

        private void runCleanup() {
            List<Plan.Call> calls = new ArrayList<>(plan.cleanup());
            Collections.reverse(calls);
            // Hardening H2: after a loss no call goes out at all, not even the first cleanup call.
            boolean lostNow = runner.agentLost;
            Bindings cleanupBindings = runner.bindings == null ? Bindings.empty() : runner.bindings;
            for (Plan.Call call : calls) {
                if (lostNow) {
                    log.write("cleanup", RunLog.fields("primitive", call.primitive(), "ok", false, "error",
                            "skipped: the agent was lost"));
                    continue;
                }
                Map<String, Object> params = null;
                boolean webCall = WebRun.isWeb(call.primitive());
                String[] servedBy = new String[1];
                CleanupCall observed = new CleanupCall(calls.indexOf(call) + 1, call.primitive());
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> substituted = (Map<String, Object>) cleanupBindings.substitute(call.args());
                    params = substituted;
                    AwaitSpec await = AwaitRules.resolve(call.await(), cleanupBindings, stepTimeoutMs);
                    List<String> secretParams = runner.secretParams(call);
                    runner.secretsSent |= !secretParams.isEmpty();
                    CallOptions callOptions = options(call.timeoutMs(), null).withSecretParams(secretParams);
                    if (CallRunner.confirmation(params)) {
                        // phase5-contracts.md 13.1: a destructive cleanup call is confirmed as a step's is.
                        callOptions = callOptions.withConfirm(true);
                    }
                    callOptions = observed.start(callOptions, secretParams);
                    RpcResult result;
                    long agentSince;
                    long pathSince;
                    if (webCall) {
                        // phase4-contracts.md 6.1: cleanup's web calls (webTap's removal) take the run's path too.
                        WebRun.Served served = web.call("cleanup", call.primitive(), params, callOptions, false,
                                runner.lastSeenSeq, servedBy);
                        result = served.result();
                        agentSince = served.agentSince();
                        pathSince = served.pathSince();
                        if (AgentWebPath.ID.equals(served.path())) {
                            noteSeq(result.seqAfter());
                        }
                        web.noteCall("cleanup", calls.indexOf(call) + 1, call.primitive(), served.path(), true);
                    } else {
                        pathSince = web.pathSeqNow();
                        result = agent.call(call.primitive(), params, callOptions);
                        agentSince = result.seqBefore();
                        noteSeq(result.seqAfter());
                    }
                    observed.result(result, webCall ? servedBy[0] : AgentWebPath.ID);
                    runner.applySync(call, await, agentSince, pathSince, "cleanup");
                    Map<String, Object> record = RunLog.fields("primitive", call.primitive());
                    if (webCall) {
                        record.put("path", servedBy[0]);
                    }
                    record.putAll(RunLog.fields("params", params, "ok", true, "value", result.value()));
                    log.write("cleanup", record);
                } catch (AgentLostException e) {
                    lostNow = true;
                    runner.agentLost = true;
                    log.write("cleanup", cleanupFailure(call, servedBy[0], params, e.toString()));
                } catch (AgentRpcException e) {
                    observed.error(ErrorCodes.name(e.error().code()), servedBy[0]);
                    noteCleanupFailure(call, calls, servedBy[0]);
                    log.write("cleanup", cleanupFailure(call, servedBy[0], params, e.error().toJson()));
                } catch (Abort | ResolutionException | WebRun.PathFailure | RuntimeException e) {
                    noteCleanupFailure(call, calls, servedBy[0]);
                    log.write("cleanup", cleanupFailure(call, servedBy[0], params, e.getMessage() == null
                            ? e.toString() : e.getMessage()));
                } finally {
                    observed.end();
                }
            }
        }

        /**
         * A cleanup call's {@code call} span (phase6-contracts.md 10.4: a child of the scenario): started once its
         * options are known, ended however the call ends; nothing with {@link RunObserver#NONE}.
         */
        private final class CleanupCall {

            private final int index;
            private final String primitive;
            private boolean started;
            private RpcResult result;
            private String path;
            private String errorCode;
            private long startMs;
            private long rttMs = -1L;

            CleanupCall(int index, String primitive) {
                this.index = index;
                this.primitive = primitive;
            }

            /** Starts the span; the options gain its trace parent. */
            CallOptions start(CallOptions callOptions, List<String> secretParams) {
                if (!observing) {
                    return callOptions;
                }
                started = true;
                startMs = options.clock().millis();
                observer.callStarted(new CallStart("cleanup", index, primitive, callOptions.correlationId(),
                        secretParams, callOptions.confirm()));
                return callOptions.withTraceparent(observer.currentTraceparent());
            }

            void result(RpcResult value, String servedBy) {
                this.result = value;
                this.path = servedBy;
                this.rttMs = options.clock().millis() - startMs;
            }

            void error(String code, String servedBy) {
                this.errorCode = code;
                this.path = servedBy == null ? AgentWebPath.ID : servedBy;
            }

            void end() {
                if (started) {
                    observer.callEnded(CallRunner.callEnd(result, path, errorCode, rttMs));
                }
            }
        }

        /** A failed cleanup call's record; a {@code web.*} call names the path it went to. */
        private Map<String, Object> cleanupFailure(Plan.Call call, String path, Map<String, Object> params,
                                                   Object error) {
            Map<String, Object> record = RunLog.fields("primitive", call.primitive());
            if (WebRun.isWeb(call.primitive())) {
                record.put("path", path);
            }
            record.putAll(RunLog.fields("params", params, "ok", false, "error", error));
            return record;
        }

        private void noteCleanupFailure(Plan.Call call, List<Plan.Call> calls, String path) {
            if (WebRun.isWeb(call.primitive()) && path != null) {
                web.noteCall("cleanup", calls.indexOf(call) + 1, call.primitive(), path, false);
            }
        }

        private void runRecover() {
            if (runner.agentLost) {
                log.write("recover", RunLog.fields("ok", false, "error", "skipped: the agent was lost"));
                return;
            }
            try {
                pack.recover(agent);
                log.write("recover", RunLog.fields("ok", true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.write("recover", RunLog.fields("ok", false, "error", e.toString()));
            } catch (Exception e) {
                log.write("recover", RunLog.fields("ok", false, "error", e.toString()));
            }
        }

        // ---------------------------------------------------------------- helpers

        private CallOptions options(long timeoutMs, String correlationId) {
            return CallRunner.options(timeoutMs, correlationId);
        }

        private void noteSeq(long seq) {
            runner.noteSeq(seq);
        }

        private Map<String, Object> seq(RpcResult result) {
            return CallRunner.seq(result);
        }

        private Map<String, Object> errorDetail(String primitive, AgentRpcException e) {
            return CallRunner.errorDetail(primitive, e);
        }

        private Duration since(long t0) {
            return Duration.ofMillis(Math.max(0, options.clock().millis() - t0));
        }

        private Map<String, Object> safeDetail(Map<String, Object> detail) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> normal = (Map<String, Object>) Values.normalise(detail);
                return normal;
            } catch (JsonException e) {
                return RunLog.fields("unwritableDetail", e.getMessage());
            }
        }
    }

    /** Leaves the run's main sequence for a plan that was refused or not executed before it began (P7 8.6). */
    private static final class SkipRest extends Exception {

        private static final long serialVersionUID = 1L;

        SkipRest() {
            super(null, null, false, false);
        }
    }
}
