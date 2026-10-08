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
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptDecision;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.web.AgentWebPath;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * redact them.
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
 * {@code run.jsonl}, {@code bindings.json}, {@code cost-ledger.jsonl}, {@code failure/*.json} and the
 * {@link RunResult}.
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
 * <p>Not thread-safe: one executor runs one plan at a time on the calling thread; it starts no threads (a web path
 * may run threads of its own).
 */
public final class PlanExecutor {

    private static final long MAX_CALL_TIMEOUT_MS = 120_000L;
    private static final long DIAGNOSTIC_TIMEOUT_MS = 5_000L;
    private static final long EVENT_EVIDENCE = 200L;
    private static final long PROMPT_QUERY_LIMIT = 200L;
    private static final String LEDGER_FILE = "cost-ledger.jsonl";

    /** Every file a run may write below its output directory, files before the directories that hold them. */
    private static final List<String> OUTPUT_FILES = List.of(RunLog.FILE, "bindings.json", LEDGER_FILE,
            "failure/snapshot.json",
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
        if (packFacts != null) {
            for (FactProvider fact : packFacts) {
                facts.putIfAbsent(fact.root(), fact);
                webRoot |= "web".equals(fact.root());
            }
        }
        this.packWebRoot = webRoot;
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

    /** Stops the run with a verdict. */
    private static final class Abort extends Exception {

        private static final long serialVersionUID = 1L;

        final Status status;
        final FailureKind kind;
        final String stepId;
        final transient Map<String, Object> detail;

        Abort(Status status, FailureKind kind, String stepId, String message, Map<String, Object> detail) {
            super(message, null, false, false);
            this.status = status;
            this.kind = kind;
            this.stepId = stepId;
            this.detail = detail == null ? new LinkedHashMap<>() : detail;
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
        private SyncPolicy sync = new SyncPolicy(null, null, null, null, null);
        private PromptPolicy promptPolicy = PromptPolicy.FAIL;
        private final List<StepResult> steps = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> degradedFidelity = new ArrayList<>();
        private List<String> stepFidelity = new ArrayList<>();
        private Set<String> packVerbs = Set.of();
        private SecretMask mask = SecretMask.NONE;
        private Set<String> keyRuleSlots;
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
        private CapabilityMatrix caps;
        private Bindings bindings;
        private String currentStep;
        private boolean stepsStarted;
        private boolean agentLost;
        private long lastActionSeq;
        private long promptCursor;
        private long lastSeenSeq;
        /** The web path's sequence number before the last step call that was not a wait, a settle or a mark. */
        private long lastActionPathSeq;
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
        /** True once a call of the run announced a secret parameter (A34): no web trace is kept then. */
        private boolean secretsSent;
        /**
         * True when a call of the plan may announce a secret parameter, so that the web path was opened without
         * tracing (phase4-contracts.md 6.4).
         */
        private boolean traceWithheld;

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
                sync = policy;
            }
            Object unexpected = plan.onPrompt().get("unexpected");
            PromptPolicy packPolicy = pack.promptPolicy();
            boolean strict = unexpected instanceof String name && name.strip().equalsIgnoreCase("fail");
            promptPolicy = strict || packPolicy == null ? PromptPolicy.FAIL : packPolicy;
            StepLibrary library = pack.steps();
            Set<String> verbs = library == null ? null : library.verbs();
            packVerbs = verbs == null ? Set.of() : Set.copyOf(verbs);
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
            clearOldOutputs();
            log = RunLog.open(options.outputDir(), options.clock());
            web = new WebRun(agent, log, options.clock(), webPathLoader);
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
            log.write("run-start", RunLog.fields("scenarioId", plan.scenarioId(), "pack", plan.pack(),
                    "packVersion", plan.packVersion(), "planVersion", plan.planVersion(), "sourceHash",
                    plan.sourceHash(), "stepTimeoutMs", stepTimeoutMs, "startedAt",
                    options.clock().instant().toString()));
            Abort failure = null;
            try {
                requireBindingsRoot();
                init();
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
            } catch (Abort abort) {
                failure = abort;
            } catch (AgentLostException e) {
                failure = lost(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new Abort(Status.ERROR, FailureKind.ENVIRONMENT, currentStep, "the run was interrupted",
                        null);
            } catch (RuntimeException | StackOverflowError | LinkageError e) {
                // Not OutOfMemoryError or another VirtualMachineError: the JVM may not be able to go on.
                failure = new Abort(Status.ERROR, FailureKind.ENVIRONMENT, currentStep, "internal error: " + e,
                        RunLog.fields("exception", e.toString()));
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
            Status status = failure != null ? failure.status
                    : skipped.isEmpty() && degradedFidelity.isEmpty() ? Status.PASS : Status.DEGRADED;
            try {
                if (failure != null && stepsStarted && !agentLost && options.captureOnFailure()) {
                    collectEvidence(detail);
                }
                if (failure != null && stepsStarted && options.captureOnFailure()) {
                    saveWebTrace();
                }
                if (failure == null) {
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
            if (bindings != null && options.outputDir() != null) {
                try {
                    writeBindings(options.outputDir().resolve("bindings.json"));
                } catch (IOException | RuntimeException e) {
                    log.write("bind", RunLog.fields("ok", true, "writeError", e.toString()));
                }
            }
            Duration duration = Duration.ofMillis(Math.max(0, options.clock().millis() - startMs));
            RunResult result = masked(new RunResult(plan.scenarioId(), status, failure == null ? null : failure.kind,
                    failure == null ? null : failure.stepId, failure == null ? null : failure.getMessage(),
                    safeDetail(detail), steps, skipped, duration, degradedFidelity, options.bind().mode(),
                    options.bind().envId(), modelCalls));
            Map<String, Object> end = RunLog.fields("status", status.name(), "failureKind",
                    result.failureKind() == null ? null : result.failureKind().name(), "failedStepId",
                    result.failedStepId(), "message", result.message(), "skippedExpectations", skipped);
            if (!degradedFidelity.isEmpty()) {
                end.put("degradedFidelity", degradedFidelity);
            }
            end.put("durationMs", duration.toMillis());
            log.write("run-end", end);
            return result;
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

        /** {@code cost-ledger.jsonl}: one line per entry of this run; always written; masked. */
        private void writeLedger(List<CostLedger.Entry> entries) {
            Path dir = options.outputDir();
            if (dir == null) {
                return;
            }
            StringBuilder text = new StringBuilder();
            for (CostLedger.Entry e : entries) {
                text.append(Json.write(mask.apply(RunLog.fields("scenarioId", e.scenarioId(), "slot", e.slot(),
                        "strategy", e.strategy() == null ? null : e.strategy().name(), "provider", e.provider(),
                        "model", e.model(), "requestId", e.requestId(), "inputTokens", e.inputTokens(),
                        "outputTokens", e.outputTokens(), "costUsd", e.costUsd())))).append('\n');
            }
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve(LEDGER_FILE), text.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.write("ledger", RunLog.fields("ok", false, "error", e.toString()));
            }
        }

        /**
         * Removes the files an earlier run into the same output directory left behind, so that evidence of an old
         * failure is never read as belonging to this run. Only the files the executor itself writes are touched.
         */
        private void clearOldOutputs() {
            Path dir = options.outputDir();
            if (dir == null) {
                return;
            }
            // phase4-contracts.md 6.4: the web evidence files are named after the browsers' refs.
            Path failure = dir.resolve("failure");
            if (Files.isDirectory(failure)) {
                try (Stream<Path> files = Files.list(failure)) {
                    for (Path file : files.toList()) {
                        if (file.getFileName().toString().startsWith(WEB_EVIDENCE_PREFIX)
                                && Files.isRegularFile(file)) {
                            Files.deleteIfExists(file);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // Best effort, as below.
                }
            }
            for (String name : OUTPUT_FILES) {
                try {
                    Files.deleteIfExists(dir.resolve(name));
                } catch (IOException | RuntimeException e) {
                    // Best effort: a file that cannot be removed is overwritten or reported when it is written.
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
                log.write("bind", RunLog.fields("ok", false, "mode", options.bind().mode().label(), "envId",
                        options.bind().envId(), "failureKind", e.kind().name(), "error", e.getMessage()));
                throw new Abort(Status.ERROR, e.kind(), null, e.getMessage(), new LinkedHashMap<>(e.detail()));
            }
        }

        private void checkRequires() throws Abort, AgentLostException {
            caps = agent.capabilities();
            if (caps == null) {
                caps = new CapabilityMatrix(Map.of());
            }
            List<String> unmet = caps.unmet(plan.requires());
            log.write("requires", RunLog.fields("requires", plan.requires(), "unmet", unmet));
            if (!unmet.isEmpty()) {
                throw new Abort(Status.ERROR, FailureKind.PRECONDITION_FAILED, null,
                        "unmet requirements: " + String.join("; ", unmet), RunLog.fields("unmet", unmet));
            }
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
                return options.outputDir();
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
                    return web.call(currentStep == null ? "fact" : currentStep, method,
                            params == null ? Map.of() : params, callOptions == null ? CallOptions.defaults()
                                    : callOptions, true, lastSeenSeq, new String[1]).result();
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
                    || options.outputDir() == null) {
                return;
            }
            if (secretsSent) {
                log.write("web.trace", RunLog.fields("skipped", true, "reason", "secrets"));
                return;
            }
            Path dir = options.outputDir().resolve("failure");
            try {
                Files.createDirectories(dir);
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

        private void runPreflight() throws Abort, AgentLostException {
            List<PreflightCheck> checks = pack.preflight();
            if (checks == null) {
                return;
            }
            PreflightCheck.PreflightContext ctx = new PreflightCheck.PreflightContext() {
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
            };
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
                bindings = provided;
                try {
                    Binder.check(plan, bindings);
                } catch (ResolutionException e) {
                    log.write("bind", RunLog.fields("ok", false, "mode", mode, "envId", bind.envId(), "reason",
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
                    log.write("bind", record);
                    throw new Abort(Status.ERROR, e.kind(), null, e.getMessage(), detail);
                }
                this.outcome = outcome;
                bindings = outcome.bindings();
                // phase3-contracts.md 6.2: a guarded cross-check that could not run is not a PASS.
                skipped.addAll(outcome.skipped());
                if (outcome.file() != null && (bind.mode() != BindMode.LIVE || bind.writeBindings())) {
                    bindingsFile = outcome.file().envId() + "/" + outcome.file().scenarioId() + BindingsFile.SUFFIX;
                }
                if (outcome.written() != null) {
                    log.write("bind", RunLog.fields("ok", true, "written", bindingsFile));
                }
            }
            mask = mask.plus(bindings.secretValues());
            log.mask(mask);
            Map<String, Object> record = RunLog.fields("ok", true, "slots", new ArrayList<>(bindings.values()
                    .keySet()), "mode", mode, "envId", bind.envId(), "bindingsFile", bindingsFile);
            if (outcome != null && !outcome.skipped().isEmpty()) {
                record.put("skipped", outcome.skipped());
            }
            log.write("bind", record);
            if (options.outputDir() != null) {
                try {
                    writeBindings(options.outputDir().resolve("bindings.json"));
                } catch (IOException | RuntimeException e) {
                    log.write("bind", RunLog.fields("ok", true, "writeError", e.toString()));
                }
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
            Map<String, Object> json = bindings.toJson(plan);
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
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, Json.writePretty(mask.apply(json)).getBytes(StandardCharsets.UTF_8));
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
            lastActionSeq = result.seqAfter();
            promptCursor = lastActionSeq;
            log.write("call", RunLog.fields("step", null, "primitive", "trace.mark", "params", params, "ok", true,
                    "value", result.value(), "seq", seq(result)));
        }

        // ---------------------------------------------------------------- steps

        private void runStep(int index) throws Abort, AgentLostException, InterruptedException {
            Plan.Step step = plan.steps().get(index);
            runBlock(step.id(), step.verb(), step.line(), stepSkipped -> {
                for (int j = 0; j < step.calls().size(); j++) {
                    executeCall(index, j, step.calls().get(j));
                }
                evaluate(step.expect(), step.id(), stepSkipped);
            });
        }

        private void runVerify() throws Abort, AgentLostException, InterruptedException {
            if (!plan.verify().isEmpty()) {
                runBlock("verify", "verify", 0, stepSkipped -> evaluate(plan.verify(), "verify", stepSkipped));
            }
        }

        /** Runs one step (or the verify block) and records its {@link StepResult} however it ends. */
        private void runBlock(String id, String verb, int line, Block body)
                throws Abort, AgentLostException, InterruptedException {
            currentStep = id;
            stepsStarted = true;
            long t0 = options.clock().millis();
            List<String> stepSkipped = new ArrayList<>();
            stepFidelity = new ArrayList<>();
            Status status = Status.ERROR;
            String message = null;
            try {
                body.run(stepSkipped);
                status = stepSkipped.isEmpty() && stepFidelity.isEmpty() ? Status.PASS : Status.DEGRADED;
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
                message = "internal error: " + e;
                throw e;
            } finally {
                steps.add(new StepResult(id, verb, line, status, message, stepSkipped, since(t0), stepFidelity));
            }
        }

        private void executeCall(int stepIndex, int callIndex, Plan.Call call) throws Abort, AgentLostException {
            Plan.Step step = plan.steps().get(stepIndex);
            String stepId = step.id();
            boolean baseUi = BaseSteps.UI_VERBS.contains(step.verb()) && !packVerbs.contains(step.verb());
            boolean baseWeb = BaseSteps.WEB_VERBS.contains(step.verb()) && !packVerbs.contains(step.verb());
            Map<String, Object> params;
            AwaitSpec await;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> substituted = (Map<String, Object>) bindings.substitute(call.args());
                params = substituted;
                await = AwaitRules.resolve(call.await(), bindings, stepTimeoutMs);
            } catch (ResolutionException e) {
                throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, stepId, e.getMessage(),
                        RunLog.fields("reason", e.reason(), "reference", e.reference(), "primitive",
                                call.primitive()));
            }
            boolean isAwait = "sync.await".equals(call.primitive());
            if (isAwait && !params.containsKey("since")) {
                params.put("since", lastActionSeq);
            }
            List<String> secretParams = secretParams(call);
            secretsSent |= !secretParams.isEmpty();
            String correlationId = plan.scenarioId() + "/" + stepId + "/" + (callIndex + 1);
            CallOptions callOptions = options(call.timeoutMs(), correlationId).withSecretParams(secretParams);
            boolean webCall = WebRun.isWeb(call.primitive());
            String[] servedBy = new String[1];
            long t0 = options.clock().millis();
            RpcResult result;
            long agentSince;
            long pathSince;
            boolean fromPath = false;
            try {
                if (webCall) {
                    WebRun.Served served = web.call(stepId, call.primitive(), params, callOptions, false, lastSeenSeq,
                            servedBy);
                    result = served.result();
                    agentSince = served.agentSince();
                    pathSince = served.pathSince();
                } else if (isAwait && web.combines(AwaitRules.topicsText(params.get("topics")))) {
                    // phase4-contracts.md 6.3: a waitFor on a web topic reads the path's events and the agent's bus.
                    WebRun.Waited waited = combinedAwait(params, t0);
                    fromPath = waited.fromPath();
                    agentSince = lastActionSeq;
                    pathSince = lastActionPathSeq;
                    result = new RpcResult(waited.event().toJson(), List.of(), agentSince, fromPath ? lastSeenSeq
                            : Math.max(lastSeenSeq, waited.event().seq()), options.clock().millis() - t0,
                            "controller");
                } else {
                    pathSince = web.pathSeqNow();
                    result = agent.call(call.primitive(), params, callOptions);
                    agentSince = result.seqBefore();
                }
            } catch (AgentRpcException e) {
                Map<String, Object> record = RunLog.fields("step", stepId, "index", callIndex + 1, "primitive",
                        call.primitive());
                if (webCall) {
                    record.put("path", servedBy[0]);
                    web.noteCall(stepId, callIndex + 1, call.primitive(), servedBy[0], false);
                }
                record.putAll(RunLog.fields("params", params, "ok", false, "error", e.error().toJson(), "durationMs",
                        options.clock().millis() - t0));
                if (!secretParams.isEmpty()) {
                    record.put("secretParams", secretParams);
                }
                log.write("call", record);
                throw rpcFailure(e, stepId, call.primitive(), params);
            } catch (WebRun.PathFailure e) {
                Map<String, Object> record = RunLog.fields("step", stepId, "index", callIndex + 1, "primitive",
                        call.primitive(), "path", servedBy[0], "params", params, "ok", false, "error", e.getMessage(),
                        "durationMs", options.clock().millis() - t0);
                if (!secretParams.isEmpty()) {
                    record.put("secretParams", secretParams);
                }
                log.write("call", record);
                web.noteCall(stepId, callIndex + 1, call.primitive(), servedBy[0], false);
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, e.getMessage(), RunLog.fields(
                        "primitive", call.primitive(), "path", servedBy[0], "params", params));
            }
            boolean agentServed = !webCall || AgentWebPath.ID.equals(servedBy[0]);
            if (agentServed && !fromPath) {
                noteSeq(result.seqAfter());
            }
            Map<String, Object> record = RunLog.fields("step", stepId, "index", callIndex + 1, "primitive",
                    call.primitive());
            if (webCall) {
                record.put("path", servedBy[0]);
            }
            record.putAll(RunLog.fields("params", params, "ok", true, "value", result.value(), "events",
                    result.events().size(), "seq", seq(result), "durationMs", options.clock().millis() - t0,
                    "thread", result.thread()));
            if (fromPath) {
                record.put("eventSource", web.pathId());
            }
            if (!secretParams.isEmpty()) {
                record.put("secretParams", secretParams);
            }
            log.write("call", record);
            if (webCall) {
                web.noteCall(stepId, callIndex + 1, call.primitive(), servedBy[0], true);
            }
            boolean isSettle = "swing.settle".equals(call.primitive());
            if (!isAwait && !isSettle && !"trace.mark".equals(call.primitive())) {
                lastActionSeq = agentSince;
                lastActionPathSeq = pathSince;
            }
            noteFidelity(step, call, result.value());
            if (baseUi) {
                checkFired(step, call, result.value());
            }
            if ((baseUi || baseWeb) && isSettle) {
                checkSettled(step, call, params, result.value());
            }
            if (baseWeb && "web.waitFlag".equals(call.primitive())) {
                checkWaitFlag(step, call, params, result.value());
            }
            Event ended = applySync(call, await, agentSince, pathSince, stepId);
            if (call.capture() != null) {
                capture(step, call, isAwait ? awaitedEvent(result.value()) : ended);
            }
            checkPrompts(stepIndex, callIndex, handledUpTo(call, result, agentSince, fromPath));
        }

        /**
         * A {@code sync.await} call on a web topic while a non-agent web path serves the run: the executor waits on
         * the path's events and the agent's bus itself (since the last action on each). No event in time is the
         * agent's own TIMEOUT error, so that it is reported exactly as on the agent path; a rejected wait is the
         * agent's error.
         */
        private WebRun.Waited combinedAwait(Map<String, Object> params, long t0)
                throws AgentRpcException, AgentLostException, Abort {
            String topics = AwaitRules.topicsText(params.get("topics"));
            long since = params.get("since") instanceof Number n ? n.longValue() : lastActionSeq;
            long timeoutMs = params.get("timeoutMs") instanceof Number n ? Math.max(1L, n.longValue()) : 10_000L;
            @SuppressWarnings("unchecked")
            Map<String, Object> match = params.get("match") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            WebRun.Waited waited;
            try {
                waited = web.await(topics, since, lastActionPathSeq, match, timeoutMs);
            } catch (RuntimeException e) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, currentStep, "waiting for event (" + topics
                        + ") failed: " + e, RunLog.fields("primitive", "sync.await", "error", e.toString()));
            }
            if (waited == null) {
                throw new AgentRpcException("sync.await", new RpcError(ErrorCodes.TIMEOUT, "no matching event within "
                        + timeoutMs + " ms", RunLog.fields("lastSeq", lastSeenSeq, "topics", topics, "since", since,
                        "pathSince", lastActionPathSeq, "timeoutMs", timeoutMs, "waitedMs",
                        options.clock().millis() - t0)));
            }
            return waited;
        }

        /**
         * Amendment A34, by provenance: the top-level params whose template references (whole, embedded in a text or
         * inside a structure) a joined credential field, or an A25 fixture secret (a reference into a slot the key
         * rule covers whose slot name or path field looks secret, or which reaches a structure holding a field whose
         * key looks secret; {@link FixtureSecrets#reachesSecret}). They are sent as {@code _meta.secretParams}
         * ({@link CallOptions#withSecretParams}), so that the agent redacts their values wherever it logs, traces or
         * reports the call; the primitive itself receives them unchanged. Never by value: a literal that happens to
         * equal a credential is not named. Step and cleanup calls alike.
         */
        private List<String> secretParams(Plan.Call call) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, Object> e : call.args().entrySet()) {
                for (References.Ref ref : References.collect(e.getValue())) {
                    if (bindings.isSecretField(ref.slot(), ref.fields()) || fixtureSecret(ref)) {
                        names.add(e.getKey());
                        break;
                    }
                }
            }
            return names;
        }

        /** True when the reference reaches an A25 secret of a slot the run's mask covers by the key rule. */
        private boolean fixtureSecret(References.Ref ref) {
            if (!keyRuleSlots().contains(ref.slot()) || !bindings.has(ref.slot())) {
                return false;
            }
            try {
                return FixtureSecrets.reachesSecret(ref.slot(), ref.fields(),
                        References.navigate(bindings.values().get(ref.slot()), ref.fields(), ref.text()));
            } catch (ResolutionException e) {
                // Substitution succeeded before this is asked, so the path exists; judge by the path alone.
                return SecretKeys.looksSecret(ref.slot()) || ref.fields().stream().anyMatch(SecretKeys::looksSecret);
            }
        }

        /**
         * The slots whose values the run's mask collected by A25's key rule ({@link #secretMask()}): the caller's
         * slots when the caller supplied the bindings, else the plan's fixture slots.
         */
        private Set<String> keyRuleSlots() {
            if (keyRuleSlots == null) {
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
                keyRuleSlots = slots;
            }
            return keyRuleSlots;
        }

        /** The event a {@code sync.await} call returned. */
        private Event awaitedEvent(Object value) {
            if (value instanceof Map<?, ?> map) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = (Map<String, Object>) map;
                    return Event.fromJson(json);
                } catch (RuntimeException e) {
                    return null;
                }
            }
            return null;
        }

        /**
         * Binds the call's captured slot from the event that ended its wait (phase2-contracts.md 3.4). A field whose
         * payload path leads nowhere fails the step as FAIL / ASSERTION.
         */
        private void capture(Plan.Step step, Plan.Call call, Event event) throws Abort {
            String slot = call.capture().slot();
            String topic = event == null ? "(none)" : event.topic();
            Map<String, Object> payload = event == null || event.payload() == null ? Map.of() : event.payload();
            Map<String, Object> value = new LinkedHashMap<>();
            for (Map.Entry<String, String> field : call.capture().fields().entrySet()) {
                String path = field.getValue();
                try {
                    value.put(field.getKey(), References.navigate(payload, List.of(path.split("\\.")),
                            "$" + slot + "." + field.getKey()));
                } catch (ResolutionException e) {
                    log.write("capture", RunLog.fields("step", step.id(), "slot", slot, "ok", false, "topic", topic,
                            "path", path));
                    throw new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), "event " + topic + " has no payload"
                            + " value at " + path, RunLog.fields("slot", slot, "field", field.getKey(), "path", path,
                            "topic", topic, "payload", payload));
                }
            }
            bindings = bindings.withCaptured(slot, value);
            log.write("capture", RunLog.fields("step", step.id(), "slot", slot, "value", value));
        }

        /**
         * Records a result with {@code fidelity: "DEGRADED"}: the call ran, but at a lower fidelity than requested
         * (a COMPONENT click that fell back to ACTION). The run can then be DEGRADED at best.
         */
        private void noteFidelity(Plan.Step step, Plan.Call call, Object value) {
            if (!(value instanceof Map<?, ?> v) || !"DEGRADED".equals(v.get("fidelity"))) {
                return;
            }
            Object requested = v.get("requestedRung");
            Object rung = v.get("rung");
            Object reason = v.get("reason");
            String entry = step.id() + ": " + step.verb() + " fell back from " + (requested == null ? "?" : requested)
                    + " to " + (rung == null ? "?" : rung) + (reason == null ? "" : " (" + reason + ")");
            entry = Values.printableLine(entry);
            degradedFidelity.add(entry);
            stepFidelity.add(entry);
            log.write("fidelity", RunLog.fields("step", step.id(), "primitive", call.primitive(), "fidelity",
                    "DEGRADED", "requestedRung", requested, "rung", rung, "reason", reason));
        }

        /** A base UI verb's call that reports {@code fired: false} did nothing: FAIL/ASSERTION. */
        private void checkFired(Plan.Step step, Plan.Call call, Object value) throws Abort {
            if (!(value instanceof Map<?, ?> v) || !Boolean.FALSE.equals(v.get("fired"))) {
                return;
            }
            Object rung = v.get("rung");
            Object reason = v.get("reason");
            Map<String, Object> detail = RunLog.fields("primitive", call.primitive(), "value", value);
            throw new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), step.verb() + " did not fire: "
                    + (rung == null ? "?" : rung) + ": " + (reason == null ? "no reason given" : reason), detail);
        }

        /**
         * The {@code swing.settle} call a base UI verb emits after an {@code await}: {@code settled: false} is
         * SYNC_TIMEOUT, as for the SETTLE sync.
         */
        private void checkSettled(Plan.Step step, Plan.Call call, Map<String, Object> params, Object value)
                throws Abort {
            if (value instanceof Map<?, ?> v && Boolean.TRUE.equals(v.get("settled"))) {
                return;
            }
            Object timeoutMs = params.get("timeoutMs");
            Map<String, Object> detail = RunLog.fields("primitive", call.primitive(), "sync", "NONE", "timeoutMs",
                    timeoutMs, "settle", value);
            detail.putAll(diagnostics());
            throw new Abort(Status.FAIL, FailureKind.SYNC_TIMEOUT, step.id(), "the UI did not settle within "
                    + timeoutMs + " ms after " + step.verb(), detail);
        }

        /**
         * The {@code web.waitFlag} call of the base verb {@code webWaitFlag}: a flag that did not turn true
         * ({@code ready} not true) within the primitive's wait is SYNC_TIMEOUT, as an expired settle is.
         */
        private void checkWaitFlag(Plan.Step step, Plan.Call call, Map<String, Object> params, Object value)
                throws Abort {
            if (value instanceof Map<?, ?> v && Boolean.TRUE.equals(v.get("ready"))) {
                return;
            }
            Object ms = value instanceof Map<?, ?> v && v.get("ms") != null ? v.get("ms")
                    : params.get("timeoutMs") != null ? params.get("timeoutMs") : call.timeoutMs();
            Map<String, Object> detail = RunLog.fields("primitive", call.primitive(), "sync", "NONE", "expression",
                    params.get("expression"), "waitFlag", value);
            detail.putAll(diagnostics());
            String expression = Values.printableLine(Values.stringOf(params.get("expression")));
            throw new Abort(Status.FAIL, FailureKind.SYNC_TIMEOUT, step.id(), step.verb() + ": the flag did not turn"
                    + " true within " + ms + " ms: " + (expression.length() <= 200 ? expression
                    : expression.substring(0, 197) + "..."), detail);
        }

        /**
         * The highest sequence number of a prompt this call itself dealt with: for a wait on a prompt topic the event
         * it returned, for any other prompt-handling call everything published before the call ran (the prompt it
         * answered was open by then; one it raised comes later). -1 for a call that handles no prompt, and for a wait
         * a web path's own event ended (its number is not the agent's, and the path publishes no prompt).
         *
         * @param agentBefore the agent's sequence number before the call
         */
        private long handledUpTo(Plan.Call call, RpcResult result, long agentBefore, boolean fromPath) {
            if (!call.handlesPrompt()) {
                return -1;
            }
            if (!"sync.await".equals(call.primitive())) {
                return agentBefore;
            }
            if (fromPath) {
                return -1;
            }
            if (result.value() instanceof Map<?, ?> event && event.get("seq") instanceof Long seq) {
                noteSeq(seq);
                return seq;
            }
            return -1;
        }

        private Abort rpcFailure(AgentRpcException e, String stepId, String primitive, Map<String, Object> params) {
            RpcError error = e.error();
            Map<String, Object> detail = errorDetail(primitive, e);
            detail.put("params", params);
            String message = primitive + " failed: " + ErrorCodes.name(error.code()) + ": " + error.message();
            return switch (error.code()) {
                // phase4-contracts.md 8.1: a script the page ran threw; the page did not do what the step expected.
                case ErrorCodes.INPUT_REJECTED, ErrorCodes.NOT_FOUND, ErrorCodes.AMBIGUOUS, ErrorCodes.SCRIPT_ERROR ->
                        new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, message, detail);
                case ErrorCodes.STALE_HANDLE -> WebRun.isWeb(primitive)
                        ? new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, primitive + " failed: the browser"
                                + " closed during the step (STALE_HANDLE: " + error.message() + ")", detail)
                        : new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, message, detail);
                case ErrorCodes.MODAL_OPEN -> {
                    String dialog = modalDialog(error, detail);
                    yield new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, message + dialog, detail);
                }
                case ErrorCodes.TIMEOUT -> {
                    detail.putAll(diagnostics());
                    yield new Abort(Status.FAIL, FailureKind.SYNC_TIMEOUT, stepId, message, detail);
                }
                default -> new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, message, detail);
            };
        }

        /**
         * Adds {@code dialogTitle} and {@code dialogText} of the dialog a MODAL_OPEN error names to the detail: the
         * title from the error's data, the text from {@code swing.dialogs} (best effort, one bounded call). Returns
         * {@code " (dialog \"title\": text)"} for the message, or "" when nothing is known.
         */
        private String modalDialog(RpcError error, Map<String, Object> detail) {
            Map<String, Object> data = error.data();
            Object ref = data == null ? null : data.get("dialog");
            Object title = data == null ? null : data.get("title");
            Object text = null;
            if (diagnostic("swing.dialogs") instanceof Map<?, ?> d && d.get("dialogs") instanceof List<?> dialogs) {
                for (Object item : dialogs) {
                    if (item instanceof Map<?, ?> dialog && (ref != null ? ref.equals(dialog.get("ref"))
                            : title != null && title.equals(dialog.get("title")))) {
                        text = dialog.get("text");
                        if (title == null) {
                            title = dialog.get("title");
                        }
                        break;
                    }
                }
            }
            detail.put("dialogTitle", title);
            detail.put("dialogText", text);
            if (title == null && text == null) {
                return "";
            }
            return " (dialog" + (title == null ? "" : " \"" + Values.printableLine(Values.stringOf(title)) + "\"")
                    + (text == null ? "" : ": " + Values.brief(Values.printableLine(Values.stringOf(text)), 200))
                    + ")";
        }

        // ---------------------------------------------------------------- sync

        /**
         * Applies the call's sync.
         *
         * @param await the call's await spec with its references resolved (EVENT only, else null)
         * @param since the agent's sequence number before the call ({@code seq.before} of an agent call)
         * @param pathSince the web path's sequence number before the call (used when the wait reads the path's
         *     events, phase4-contracts.md 6.3)
         * @return the event that ended an EVENT wait, else null
         */
        private Event applySync(Plan.Call call, AwaitSpec await, long since, long pathSince, String stepId)
                throws Abort, AgentLostException {
            switch (call.sync()) {
                case NONE -> {
                }
                case ACK -> {
                    if (sync.ackTopic() != null) {
                        awaitTopic("ack", sync.ackTopic(), null, since, pathSince, timeout(sync.ackTimeout(), call),
                                call, stepId);
                    } else {
                        log.write("sync", RunLog.fields("step", stepId, "primitive", call.primitive(), "mode", "ACK",
                                "ok", true, "note", "the pack has no ack topic"));
                    }
                }
                case READY -> {
                    if (sync.ackTopic() != null) {
                        awaitTopic("ack", sync.ackTopic(), null, since, pathSince, timeout(sync.ackTimeout(), call),
                                call, stepId);
                    }
                    if (!sync.readyTopics().isEmpty()) {
                        awaitTopic("ready", String.join(",", sync.readyTopics()), null, since, pathSince,
                                timeout(sync.readyTimeout(), call), call, stepId);
                    }
                }
                case SETTLE -> settle(call, stepId);
                case EVENT -> {
                    return awaitTopic("event", await.topics(), await.match(), since, pathSince, await.timeoutMs(),
                            call, stepId);
                }
                default -> throw new IllegalStateException("unknown sync " + call.sync());
            }
            return null;
        }

        private long timeout(Duration configured, Plan.Call call) {
            return configured != null ? Math.max(1, configured.toMillis()) : call.timeoutMs();
        }

        /**
         * Awaits the first event on {@code topics} (whose payload holds every {@code match} entry, when not null)
         * since {@code since}; none within {@code timeoutMs} is SYNC_TIMEOUT with the diagnostics in the detail, and
         * an error from the wait itself (a topic filter the agent rejects, for instance) is ENVIRONMENT. While a
         * non-agent web path serves the run, a wait whose topics can match a {@code web.*} topic also reads the
         * path's own events since {@code pathSince} (phase4-contracts.md 6.3); the record then carries
         * {@code pathSince} and {@code source} (the path's id when its event ended the wait, {@code eventSeq} being
         * the path's own sequence number then; {@code agent} otherwise).
         */
        private Event awaitTopic(String wait, String topics, Map<String, Object> match, long since, long pathSince,
                                 long timeoutMs, Plan.Call call, String stepId) throws Abort, AgentLostException {
            long t0 = options.clock().millis();
            boolean combined = web.combines(topics);
            Map<String, Object> waitFields = RunLog.fields("wait", wait, "topics", topics);
            if (match != null) {
                waitFields.put("match", match);
            }
            waitFields.putAll(RunLog.fields("since", since, "timeoutMs", timeoutMs));
            if (combined) {
                waitFields.put("pathSince", pathSince);
            }
            Map<String, Object> record = RunLog.fields("step", stepId, "primitive", call.primitive(), "mode",
                    call.sync().name());
            record.putAll(waitFields);
            Map<String, Object> detail = RunLog.fields("primitive", call.primitive(), "sync", call.sync().name());
            detail.putAll(waitFields);
            Event event;
            boolean fromPath = false;
            try {
                if (combined) {
                    WebRun.Waited waited = web.await(topics, since, pathSince, match, timeoutMs);
                    event = waited == null ? null : waited.event();
                    fromPath = waited != null && waited.fromPath();
                } else {
                    event = agent.await(topics, since, match, Duration.ofMillis(timeoutMs));
                }
            } catch (RuntimeException | AgentRpcException e) {
                record.putAll(RunLog.fields("ok", false, "error", e.toString()));
                log.write("sync", record);
                detail.put("error", e.toString());
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "waiting for " + describe(wait,
                        topics, match) + " after " + call.primitive() + " failed: " + e.getMessage(), detail);
            }
            record.putAll(RunLog.fields("ok", event != null, "eventSeq", event == null ? null : event.seq(), "topic",
                    event == null ? null : event.topic(), "ms", options.clock().millis() - t0));
            if (combined && event != null) {
                record.put("source", fromPath ? web.pathId() : AgentWebPath.ID);
            }
            log.write("sync", record);
            if (event == null) {
                detail.putAll(diagnostics());
                throw new Abort(Status.FAIL, FailureKind.SYNC_TIMEOUT, stepId, "no " + describe(wait, topics, match)
                        + " within " + timeoutMs + " ms after " + call.primitive(), detail);
            }
            if (!fromPath) {
                noteSeq(event.seq());
            }
            return event;
        }

        /** "ready event (a,b)", or for an EVENT wait "event (a,b)" plus " matching {…}" when it has a match. */
        private String describe(String wait, String topics, Map<String, Object> match) {
            return ("event".equals(wait) ? "" : wait + " ") + "event (" + topics + ")"
                    + (match == null ? "" : " matching " + Values.safeWrite(match));
        }

        private void settle(Plan.Call call, String stepId) throws Abort, AgentLostException {
            long timeoutMs = call.timeoutMs();
            Map<String, Object> params = RunLog.fields("timeoutMs", timeoutMs);
            long t0 = options.clock().millis();
            RpcResult result;
            try {
                result = agent.call("swing.settle", params, options(timeoutMs + 2000, null));
            } catch (AgentRpcException e) {
                log.write("sync", RunLog.fields("step", stepId, "primitive", call.primitive(), "mode", "SETTLE",
                        "ok", false, "error", e.error().toJson()));
                throw rpcFailure(e, stepId, "swing.settle", params);
            }
            noteSeq(result.seqAfter());
            boolean settled = result.value() instanceof Map<?, ?> v && Boolean.TRUE.equals(v.get("settled"));
            log.write("sync", RunLog.fields("step", stepId, "primitive", call.primitive(), "mode", "SETTLE",
                    "timeoutMs", timeoutMs, "ok", settled, "value", result.value(), "ms",
                    options.clock().millis() - t0));
            if (!settled) {
                Map<String, Object> detail = RunLog.fields("primitive", call.primitive(), "sync", "SETTLE",
                        "timeoutMs", timeoutMs, "settle", result.value());
                detail.putAll(diagnostics());
                throw new Abort(Status.FAIL, FailureKind.SYNC_TIMEOUT, stepId, "the UI did not settle within "
                        + timeoutMs + " ms after " + call.primitive(), detail);
            }
        }

        /** {@code swing.edtState}, {@code swing.dialogs} and {@code app.state}, each best effort. */
        private Map<String, Object> diagnostics() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("edtState", diagnostic("swing.edtState"));
            d.put("dialogs", diagnostic("swing.dialogs"));
            d.put("appState", diagnostic("app.state"));
            return d;
        }

        private Object diagnostic(String primitive) {
            if (agentLost) {
                return RunLog.fields("error", "skipped: the agent was lost");
            }
            try {
                RpcResult result = agent.call(primitive, Map.of(), options(DIAGNOSTIC_TIMEOUT_MS, null));
                noteSeq(result.seqAfter());
                return result.value();
            } catch (AgentRpcException e) {
                return RunLog.fields("error", e.getMessage());
            } catch (AgentLostException e) {
                agentLost = true;
                return RunLog.fields("error", e.toString());
            } catch (RuntimeException e) {
                return RunLog.fields("error", e.toString());
            }
        }

        // ---------------------------------------------------------------- prompts

        private void checkPrompts(int stepIndex, int callIndex, long handledUpTo)
                throws Abort, AgentLostException {
            List<String> topics = sync.promptTopics();
            if (topics.isEmpty()) {
                return;
            }
            String stepId = plan.steps().get(stepIndex).id();
            TopicFilter filter = TopicFilter.of(topics);
            for (int round = 0; round < 10; round++) {
                Map<String, Object> params = RunLog.fields("since", promptCursor, "topics", String.join(",", topics),
                        "limit", PROMPT_QUERY_LIMIT);
                RpcResult result;
                try {
                    result = agent.call("evt.query", params, options(DIAGNOSTIC_TIMEOUT_MS, null));
                } catch (AgentRpcException e) {
                    log.write("prompt", RunLog.fields("step", stepId, "ok", false, "error", e.error().toJson()));
                    return;
                }
                noteSeq(result.seqAfter());
                List<Event> events = events(result.value());
                for (Event event : events) {
                    if (event.seq() <= promptCursor) {
                        continue;
                    }
                    promptCursor = event.seq();
                    noteSeq(event.seq());
                    if (!filter.matches(event.topic())) {
                        continue;
                    }
                    boolean expected = event.seq() <= handledUpTo
                            || laterCallHandlesPrompt(stepIndex, callIndex) || nextStepHandlesPrompt(stepIndex);
                    if (expected) {
                        log.write("prompt", RunLog.fields("step", stepId, "event", event.toJson(), "expected", true));
                        continue;
                    }
                    applyPolicy(event, stepId);
                }
                if (events.size() < PROMPT_QUERY_LIMIT) {
                    return;
                }
            }
        }

        private void applyPolicy(Event event, String stepId) throws Abort {
            PromptDecision decision;
            try {
                decision = promptPolicy.onUnexpectedPrompt(event, agent);
            } catch (RuntimeException e) {
                log.write("prompt", RunLog.fields("step", stepId, "event", event.toJson(), "expected", false,
                        "error", e.toString()));
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "the prompt policy failed on an"
                        + " unexpected prompt: " + e, RunLog.fields("prompt", event.toJson()));
            }
            if (decision == null) {
                decision = PromptDecision.fail("the prompt policy returned no decision");
            }
            log.write("prompt", RunLog.fields("step", stepId, "event", event.toJson(), "expected", false,
                    "decision", decision.action().name(), "reason", decision.reason()));
            if (decision.action() == PromptDecision.Action.FAIL) {
                String reason = decision.reason() != null ? decision.reason()
                        : "unexpected prompt (" + event.topic() + "): " + event.payload();
                throw new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, reason,
                        RunLog.fields("prompt", event.toJson(), "policyReason", decision.reason()));
            }
        }

        private boolean laterCallHandlesPrompt(int stepIndex, int callIndex) {
            List<Plan.Call> calls = plan.steps().get(stepIndex).calls();
            for (int j = callIndex + 1; j < calls.size(); j++) {
                if (calls.get(j).handlesPrompt()) {
                    return true;
                }
            }
            return false;
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
                    ExpectationPath path = ExpectationPath.parse(expectation.path()).bind(bindings::lookupPublic);
                    if (!runFacts.containsKey(path.root())) {
                        throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "no fact provider for root '"
                                + path.root() + "' of " + expectation.path() + "; roots: " + runFacts.keySet(),
                                RunLog.fields("path", expectation.path()));
                    }
                    active.add(new Bound(expectation, path, bindings.substitutePublic(expectation.matcher())));
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
            Map<String, Object> detail = RunLog.fields("path", bound.path().text(), "matcher", bound.matcher(),
                    "actual", attempt.actual(), "resolved", attempt.resolved(), "reason", attempt.reason(),
                    "attempts", attempts, "timeoutMs", stepTimeoutMs, "failures", failures);
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
                    Object value = bindings.substitutePublic(param.value());
                    params.add(convert(param.kind(), value, row, stepId));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> expect = (Map<String, Object>) bindings.substitutePublic(row.expect());
                @SuppressWarnings("unchecked")
                Map<String, Object> aggregates = (Map<String, Object>) bindings.substitutePublic(row.aggregates());
                return new BoundRow(row, table, params, bindings.substitutePublic(row.rows()), expect, aggregates);
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
            Matchers.Outcome count = Matchers.evaluate(row.rows(), true, (long) result.rowCount(), null);
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
                    Matchers.Outcome outcome = Matchers.evaluate(e.getValue(), index >= 0, value, "no column "
                            + e.getKey());
                    if (!outcome.holds()) {
                        Map<String, Object> first = new LinkedHashMap<>();
                        for (int c = 0; c < columns.size(); c++) {
                            first.put(columns.get(c).name(), result.get(r, c));
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
            Matchers.Outcome outcome = Matchers.evaluate(bound.matcher(), resolution.resolved(), resolution.value(),
                    resolution.problem());
            return new Attempt(outcome.holds(), resolution.resolved(), resolution.value(), outcome.reason(), null);
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
            agentLost = true;
            boolean restarted = false;
            if (options.targetRestarted() != null) {
                try {
                    restarted = Boolean.TRUE.equals(options.targetRestarted().get());
                } catch (RuntimeException probeFailure) {
                    restarted = false;
                }
            }
            FailureKind kind = restarted ? FailureKind.TARGET_RESTARTED : FailureKind.AGENT_LOST;
            return new Abort(Status.ERROR, kind, currentStep, (restarted ? "the target restarted: "
                    : "the agent was lost: ") + e.getMessage(), RunLog.fields("exception", e.toString()));
        }

        private void collectEvidence(Map<String, Object> detail) {
            Path dir = options.outputDir() == null ? null : options.outputDir().resolve("failure");
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
            Object probe = evidence("evt.query", RunLog.fields("since", lastSeenSeq, "limit", 1L),
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
                if (kept >= WEB_EVIDENCE_BROWSERS || lost[0] || agentLost) {
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
            if (lost[0] || agentLost) {
                return null;
            }
            String[] servedBy = new String[1];
            try {
                WebRun.Served served = web.call(currentStep == null ? "evidence" : currentStep, primitive, params,
                        options(timeoutMs, null), true, lastSeenSeq, servedBy);
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
                agentLost = true;
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
                Files.createDirectories(dir);
                Files.write(dir.resolve(name), mask.apply(text).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.write("evidence", RunLog.fields("file", name, "ok", false, "error", e.toString()));
            }
        }

        private Object evidence(String primitive, Map<String, Object> params, long timeoutMs, boolean[] lost) {
            if (lost[0] || agentLost) {
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
                agentLost = true;
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
                Files.createDirectories(dir);
                Files.write(dir.resolve(name), RunLog.pretty(mask.apply(value)).getBytes(StandardCharsets.UTF_8));
            } catch (IOException | JsonException e) {
                log.write("evidence", RunLog.fields("file", name, "ok", false, "error", e.toString()));
            }
        }

        private void writePng(Path file, Object capture) {
            if (!(capture instanceof Map<?, ?> map) || !(map.get("base64") instanceof String base64)) {
                return;
            }
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, Base64.getMimeDecoder().decode(base64));
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
                if (options.outputDir() == null) {
                    continue;
                }
                if (caps == null || !caps.isOk("swing.capture.paint")) {
                    log.write("artifact", RunLog.fields("kind", kind, "ok", false, "reason",
                            "capability swing.capture.paint is not OK"));
                    continue;
                }
                Object capture = evidence("swing.capture", Map.of(), 15_000L, new boolean[1]);
                writePng(options.outputDir().resolve("artifacts").resolve("capture.png"), capture);
                log.write("artifact", RunLog.fields("kind", kind, "ok", capture != null));
            }
        }

        private void runCleanup() {
            List<Plan.Call> calls = new ArrayList<>(plan.cleanup());
            Collections.reverse(calls);
            // Hardening H2: after a loss no call goes out at all, not even the first cleanup call.
            boolean lostNow = agentLost;
            Bindings cleanupBindings = bindings == null ? Bindings.empty() : bindings;
            for (Plan.Call call : calls) {
                if (lostNow) {
                    log.write("cleanup", RunLog.fields("primitive", call.primitive(), "ok", false, "error",
                            "skipped: the agent was lost"));
                    continue;
                }
                Map<String, Object> params = null;
                boolean webCall = WebRun.isWeb(call.primitive());
                String[] servedBy = new String[1];
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> substituted = (Map<String, Object>) cleanupBindings.substitute(call.args());
                    params = substituted;
                    AwaitSpec await = AwaitRules.resolve(call.await(), cleanupBindings, stepTimeoutMs);
                    List<String> secretParams = secretParams(call);
                    secretsSent |= !secretParams.isEmpty();
                    CallOptions callOptions = options(call.timeoutMs(), null).withSecretParams(secretParams);
                    RpcResult result;
                    long agentSince;
                    long pathSince;
                    if (webCall) {
                        // phase4-contracts.md 6.1: cleanup's web calls (webTap's removal) take the run's path too.
                        WebRun.Served served = web.call("cleanup", call.primitive(), params, callOptions, false,
                                lastSeenSeq, servedBy);
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
                    applySync(call, await, agentSince, pathSince, "cleanup");
                    Map<String, Object> record = RunLog.fields("primitive", call.primitive());
                    if (webCall) {
                        record.put("path", servedBy[0]);
                    }
                    record.putAll(RunLog.fields("params", params, "ok", true, "value", result.value()));
                    log.write("cleanup", record);
                } catch (AgentLostException e) {
                    lostNow = true;
                    agentLost = true;
                    log.write("cleanup", cleanupFailure(call, servedBy[0], params, e.toString()));
                } catch (AgentRpcException e) {
                    noteCleanupFailure(call, calls, servedBy[0]);
                    log.write("cleanup", cleanupFailure(call, servedBy[0], params, e.error().toJson()));
                } catch (Abort | ResolutionException | WebRun.PathFailure | RuntimeException e) {
                    noteCleanupFailure(call, calls, servedBy[0]);
                    log.write("cleanup", cleanupFailure(call, servedBy[0], params, e.getMessage() == null
                            ? e.toString() : e.getMessage()));
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
            if (agentLost) {
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
            long capped = Math.min(Math.max(1L, timeoutMs), MAX_CALL_TIMEOUT_MS);
            return CallOptions.defaults().withCorrelationId(correlationId).withTimeoutMs(capped);
        }

        private void noteSeq(long seq) {
            lastSeenSeq = Math.max(lastSeenSeq, seq);
        }

        private Duration since(long t0) {
            return Duration.ofMillis(Math.max(0, options.clock().millis() - t0));
        }

        private Map<String, Object> seq(RpcResult result) {
            return RunLog.fields("before", result.seqBefore(), "after", result.seqAfter());
        }

        private Map<String, Object> errorDetail(String primitive, AgentRpcException e) {
            RpcError error = e.error();
            return RunLog.fields("primitive", primitive, "code", error.code(), "error", ErrorCodes.name(error.code()),
                    "message", error.message(), "data", error.data());
        }

        private List<Event> events(Object value) {
            List<Event> out = new ArrayList<>();
            if (value instanceof Map<?, ?> map && map.get("events") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> json = (Map<String, Object>) raw;
                            out.add(Event.fromJson(json));
                        } catch (JsonException e) {
                            log.write("prompt", RunLog.fields("ok", false, "error", "unreadable event: " + e));
                        }
                    }
                }
            }
            out.sort((a, b) -> Long.compare(a.seq(), b.seq()));
            return out;
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
}
