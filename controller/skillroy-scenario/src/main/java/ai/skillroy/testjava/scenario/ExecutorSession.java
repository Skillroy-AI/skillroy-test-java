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
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.hooks.HookInstaller;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PackSettings;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.observe.SessionStart;
import ai.skillroy.testjava.scenario.observe.StepStart;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;
import ai.skillroy.testjava.scenario.image.ImageSteps;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The executor's public single-call entry (phase5-contracts.md 12.1, 12.2; decision B15's "one way in"): an
 * interactive session on a live target, in which a person, an MCP client or a model acts one step at a time through
 * the same step protocol a plan run uses ({@link CallRunner}, decision D79), and every action is journaled
 * ({@link StepJournal}) so that the session exports an ordinary scenario.
 *
 * <p>Rules. A session has no plan: what a step's calls need is checked by the agent's dispatcher as it receives them,
 * the step timeout is {@link SessionOptions#stepTimeout()}, and an unexpected prompt is handled by the pack's
 * {@link PromptPolicy} as in a run, except that {@code FAIL} ends the <i>step</i> (FAIL / ASSERTION, the prompt in the
 * detail), not the session. {@link SessionOptions#readOnly()} refuses a call whose primitive is mutating, destructive
 * or not in the target's catalogue before anything is sent ({@code READ_ONLY} in the outcome; journaled with
 * {@code executed: 0}). A destructive call needs {@code confirm: true} in its arguments, which only a
 * {@link Origin.Kind#PERSON} may carry: the session strips {@code confirm}, and the request's {@code _meta} it could
 * hide in, from the calls and arguments of every other origin (a {@link Origin.Kind#MODEL}, a
 * {@link Origin.Kind#CLIENT} or a {@link Origin.Kind#SYSTEM} one), journals the arguments as stripped, and the agent
 * then answers
 * {@code CONFIRM_REQUIRED} (design 3.4; a person's {@code _meta} does not compile). Every outcome's detail and result,
 * every journal record and every value returned pass the session's secret mask (the credentials, and the secret
 * fields a resolution joined). A session's calls carry the correlation id {@code <sessionId>/<stepId>/<n>}.
 * Expectations ({@link #check}, the {@code expect} verb) are evaluated once, now, on the value as the mask leaves it:
 * a session does not wait for them to hold, and a value the mask changes satisfies none.
 *
 * <p>Telemetry (phase6-contracts.md 10.2; as built, Phase 6, TL): {@link SessionOptions#observer()}, wrapped so that
 * it never throws, is told that the session opened ({@code sessionStarted}, before the journal's first record), each
 * step's start and outcome ({@code sessionStepStarted}, {@code sessionStep}, the outcome masked), the type of every
 * journal record ({@code sessionRecord}), and the close ({@code sessionEnded}); the session's calls are observed as a
 * run's are ({@link CallRunner}), and {@link #observer()} hands the same observer to the session service and the MCP
 * server for their turns and tool calls.
 *
 * <p>Phase 7 (phase7-contracts.md 9.2): {@link #read} is the unjournaled read of a capture, a hit-test or
 * {@code swing.rendering}; the four-argument {@code execute} forms write a vision tool's provenance on the step; the
 * session merges the controller-side capabilities at {@link #open} with {@link SessionOptions#host()}
 * ({@link #capabilities()}); a call with {@code via: ROBOT} or {@code mode: ROBOT} needs {@link SessionOptions#robot()}
 * and a {@code mode: ROBOT} capture a person's origin; the credential rule refuses, for every origin but a person's, a
 * step that names or compiles to a credential join's secret field or the joined slot as a whole, and every call that
 * changes the target while a credential is in flight ({@link #secretsInFlight()}), and resolves such a step's call
 * arguments against the slots' public values, never a joined secret (Phase 7 security fix, S101, S127); a slot a
 * person bound is never rebound on another origin's behalf (S113); {@link #expectImage} runs an image step through
 * {@code ImageSteps}.
 *
 * <p>Not thread-safe: one session is driven by one thread at a time; it starts no threads (a web path may run its
 * own). {@link #close()} is idempotent.
 */
public final class ExecutorSession implements AutoCloseable {

    /** How long one {@link #resolve} may take: a session has no bind budget, so each resolution gets the default. */
    static final long RESOLVE_BUDGET_MS = Plan.DEFAULT_BIND_TIMEOUT_MS;

    private static final long READ_TIMEOUT_MS = 10_000L;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DomainPack pack;
    private final AgentConnection agent;
    private final SessionOptions options;
    private final String id;
    private final Clock clock;
    private final RunLog log;
    private final WebRun web;
    private final CallRunner runner;
    private final StepJournal journal;
    private final EnvFingerprint fingerprint;
    private final CapabilityMatrix caps;
    private final Map<String, PrimitiveDescriptor> primitives;
    private final DataChannels data;
    /** The {@code hooks} record of the open's install (phase6-contracts.md 5.3), or null before {@link #open} ends. */
    private Map<String, Object> hooksRecord;
    private final Map<String, FactProvider> facts = new LinkedHashMap<>();
    private final AgentConnection factConnection = new FactConnection();
    private final int ledgerStart;
    private SecretMask mask;

    private final Map<String, Object> slotValues = new LinkedHashMap<>();
    private final Map<String, String> strategies = new LinkedHashMap<>();
    private final Map<String, Resolution> resolutions = new LinkedHashMap<>();
    private final Map<String, OracleValue> oracleValues = new LinkedHashMap<>();
    private final Map<String, CredentialRef> credentialRefs = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> joins = new LinkedHashMap<>();
    /** The credential-joined slots whose secret no person's step has consumed yet (phase7-contracts.md 9.2, D342). */
    private final Set<String> inFlight = new java.util.LinkedHashSet<>();
    /** The resolved slots as a compiled step sees them (a verb may ask for a slot's kind or credential). */
    private final Map<String, Scenario.Slot> declaredSlots = new LinkedHashMap<>();
    /** Who made each slot's current binding (Phase 7 security fix, S113): a person's is never rebound by another. */
    private final Map<String, Origin.Kind> boundBy = new LinkedHashMap<>();
    private final Map<String, Scenario.OracleEntry> declaredOracles = new LinkedHashMap<>();
    private int stepCount;
    private boolean mutated;
    private boolean closed;
    /** The session's observer, wrapped (phase6-contracts.md 10.2). */
    private final RunObserver observer;
    /** Whether the current step's {@code sessionStepStarted} was sent. */
    private boolean stepObserved;
    /** The provenance the current step's acting call handed in (phase7-contracts.md 9.2), or null. */
    private Map<String, Object> provenance;
    /** The ids the controller-side contributors added at open (phase7-contracts.md 8.5). */
    private List<String> contributed = List.of();
    /** The baselines of the current {@link #expectImage} step; {@link ImageSteps.Baselines#none()} otherwise. */
    private ImageSteps.Baselines stepBaselines = ImageSteps.Baselines.none();

    private ExecutorSession(DomainPack pack, AgentConnection agent, SessionOptions options, String id, RunLog log,
                            WebRun web, CallRunner runner, StepJournal journal, EnvFingerprint fingerprint,
                            CapabilityMatrix caps, Map<String, PrimitiveDescriptor> primitives, DataChannels data) {
        this.pack = pack;
        this.agent = agent;
        this.options = options;
        this.id = id;
        this.clock = options.clock();
        this.log = log;
        this.web = web;
        this.runner = runner;
        this.journal = journal;
        this.fingerprint = fingerprint;
        this.caps = caps;
        this.primitives = primitives;
        this.data = data;
        this.ledgerStart = options.bind().ledger().size();
        this.mask = options.bind().credentials().mask();
        this.observer = runner.observer;
        List<FactProvider> packFacts;
        try {
            packFacts = pack.facts();
        } catch (RuntimeException e) {
            packFacts = List.of();
        }
        boolean webRoot = false;
        boolean hooksRoot = false;
        boolean capsRoot = false;
        for (FactProvider fact : packFacts == null ? List.<FactProvider>of() : packFacts) {
            facts.putIfAbsent(fact.root(), fact);
            webRoot |= "web".equals(fact.root());
            hooksRoot |= "hooks".equals(fact.root());
            capsRoot |= "caps".equals(fact.root());
        }
        for (FactProvider fact : BaseFacts.all()) {
            facts.putIfAbsent(fact.root(), fact);
        }
        if (!webRoot) {
            facts.put("web", BaseFacts.web(BaseFacts.AGENT_READS, clock, (ref, reason) -> {
            }));
        }
        if (!hooksRoot) {
            // phase6-contracts.md 6.3: a session's hook counts start at its own start mark, as a run's at its first step.
            long startSeq = runner.lastActionSeq;
            facts.put("hooks", BaseFacts.hooks(() -> startSeq));
        }
        if (!capsRoot) {
            // phase7-contracts.md 8.5: the base root caps reads the session's merged matrix, with a source per entry.
            facts.put("caps", BaseFacts.caps(() -> this.caps));
        }
    }

    // ------------------------------------------------------------------------------------------------ open

    /**
     * Opens a session: reads the target's capability matrix, installs the pack's hook catalogue under
     * {@link SessionOptions#hooks()} as a run's step 0b does (phase6-contracts.md 5.3; an armed stub is recorded, not
     * refused) and archives the final matrix as {@code capabilities.json} in the output directory, reads the target's
     * catalogue, runs the pack's pre-flight checks as a run does, opens the web path (P4 6.1) with the session's web settings (without tracing: a session keeps no
     * failure evidence, and any step may type a secret), computes the environment fingerprint, marks the start
     * ({@code trace.mark}, so that waits and prompt checks never see an earlier event) and writes the journal's first
     * record. No model is involved.
     *
     * @param options null means {@link SessionOptions#defaults} without an output directory
     * @throws SessionOpenException ENVIRONMENT for a failed pre-flight check, fingerprint, start mark or journal, or a
     *     refused hook install (another catalogue installed, an agent error); PRECONDITION_FAILED for hooks {@code ON}
     *     without a catalogue or a usable engine;
     *     PRECONDITION_FAILED (or ENVIRONMENT) for a pinned web path that cannot be used
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public static ExecutorSession open(DomainPack pack, AgentConnection agent, SessionOptions options)
            throws SessionOpenException, AgentLostException, InterruptedException {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(agent, "agent");
        SessionOptions effective = options == null ? SessionOptions.defaults(null) : options;
        if (DomainPacks.reservedId(pack.id())) {
            throw new SessionOpenException(FailureKind.ENVIRONMENT, "pack id '" + pack.id() + "': the prefix"
                    + " 'inference' is reserved", null);
        }
        Clock clock = effective.clock();
        String id = effective.id() != null ? effective.id() : "session-" + Long.toString(clock.millis(), 36);
        SecretMask mask = effective.bind().credentials().mask();
        if (effective.outputDir() != null) {
            // Phase 6 security fix, pass 2, P2-L8-F3, P2-L8-S1: a directory another user owns or may write to is
            // refused before the agent is asked anything; the journal and the matrix are created exclusively in it.
            try {
                OutputFiles.directory(effective.outputDir());
            } catch (IOException e) {
                throw new SessionOpenException(FailureKind.ENVIRONMENT, e.getMessage(), null);
            }
        }
        CapabilityMatrix caps = agent.capabilities();
        if (caps == null) {
            caps = new CapabilityMatrix(Map.of());
        }
        // phase6-contracts.md 5.3, 6.4 (decisions D129, D130): the pack's hooks under the session's switch, as a run's
        // step 0b installs them (a session is live: an armed stub is recorded, not refused), then the final matrix
        // archived beside the journal.
        HookInstaller.Result hooks;
        try {
            hooks = HookInstaller.install(pack, agent, caps, effective.hooks(), false, clock);
        } catch (HookInstaller.Refused e) {
            throw new SessionOpenException(e.kind(), mask.apply(e.getMessage()), mask(mask, e.detail()));
        }
        if (!hooks.skipped()) {
            CapabilityMatrix after = agent.capabilities();
            caps = after == null ? new CapabilityMatrix(Map.of()) : after;
        }
        // phase7-contracts.md 8.5 (D326, D348): the controller-side contributors' capabilities merged once, with the
        // host's features (SessionOptions.host, set before open), so that capabilities.json is written once.
        CapabilityMerge.Result merge;
        try {
            merge = CapabilityMerge.merge(caps, CapabilityMerge.load(effective.webPathLoader()).contributors(),
                    new CapabilityContributor.CapabilityContext(effective.bind().inference(), BindMode.LIVE, caps,
                            effective.host(), pack));
        } catch (CapabilityMerge.Refused e) {
            throw new SessionOpenException(FailureKind.ENVIRONMENT, mask.apply(e.getMessage()), null);
        }
        caps = merge.merged();
        archiveCapabilities(effective.outputDir(), caps, mask);
        Map<String, PrimitiveDescriptor> primitives = new TreeMap<>();
        List<PrimitiveDescriptor> schema = agent.schema();
        for (PrimitiveDescriptor descriptor : schema == null ? List.<PrimitiveDescriptor>of() : schema) {
            if (descriptor != null) {
                primitives.putIfAbsent(descriptor.name(), descriptor);
            }
        }
        DataChannels data = DataChannels.of(agent, effective.bind().credentials(), rowMasks(pack));
        RunLog log = RunLog.open(null, clock);
        log.mask(mask);
        WebRun web = new WebRun(agent, log, clock, effective.webPathLoader());
        boolean ok = false;
        try {
            preflight(pack, agent, data, effective);
            WebSettings settings = effective.effectiveWeb();
            WebSettings untraced = new WebSettings(settings.path(), settings.defaultBrowser(),
                    settings.frozenTargets(), settings.attachDeadline(), false);
            try {
                web.select(untraced, caps, new WebContext(effective.outputDir()));
            } catch (WebRun.SelectionFailure e) {
                throw new SessionOpenException(e.kind, e.getMessage(), mask(mask, e.detail));
            }
            EnvFingerprint fingerprint;
            try (ResolutionEngine engine = new ResolutionEngine(pack, agent, effective.bind(),
                    Duration.ofMillis(RESOLVE_BUDGET_MS), BindListener.NONE, data)) {
                fingerprint = engine.fingerprint();
            } catch (BindException e) {
                throw new SessionOpenException(e.kind(), mask.apply("the environment fingerprint could not be"
                        + " computed: " + e.getMessage()), mask(mask, e.detail()));
            }
            CallRunner runner = new CallRunner(agent, log, clock, web, effective.stepTimeout().toMillis(), Set::of);
            runner.observer = RunObservers.safe(effective.observer());
            log.observe(runner.observer);
            runner.bindings = Bindings.empty();
            configure(runner, pack);
            mark(runner, id);
            StepJournal journal;
            try {
                journal = StepJournal.open(effective.outputDir(), pack);
            } catch (IOException e) {
                throw new SessionOpenException(FailureKind.ENVIRONMENT, "the journal cannot be written: " + e, null);
            }
            journal.observer = runner.observer;
            runner.observer.sessionStarted(new SessionStart(id, pack.id(), pack.version(), effective.readOnly()
                    ? "read-only" : "acting"));
            ExecutorSession session = new ExecutorSession(pack, agent, effective, id, log, web, runner, journal,
                    fingerprint, caps, primitives, data);
            session.contributed = merge.contributed();
            session.hooksRecord = mask(mask, hooks.record());
            session.start();
            ok = true;
            return session;
        } finally {
            if (!ok) {
                web.close();
                data.close();
                log.close();
            }
        }
    }

    /**
     * {@code capabilities.json} in the session's output directory (phase6-contracts.md 6.4), as a run writes it beside
     * {@code run.jsonl}; nothing without a directory. Best effort: a session opens even when the file cannot be
     * written.
     */
    private static void archiveCapabilities(Path dir, CapabilityMatrix caps, SecretMask mask) {
        if (dir == null) {
            return;
        }
        try {
            OutputFiles.writeString(dir.resolve(PlanExecutor.CAPABILITIES_FILE), Json.writePretty(mask.apply(
                    caps.toJson())) + "\n");
        } catch (IOException | RuntimeException e) {
            // Best effort: the session opens all the same.
        }
    }

    private static List<ai.skillroy.testjava.data.RowMask> rowMasks(DomainPack pack) {
        try {
            List<ai.skillroy.testjava.data.RowMask> masks = pack.rowMasks();
            return masks == null ? List.of() : masks;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static void preflight(DomainPack pack, AgentConnection agent, DataChannel data, SessionOptions options)
            throws SessionOpenException, AgentLostException {
        List<PreflightCheck> checks;
        try {
            checks = pack.preflight();
        } catch (RuntimeException e) {
            throw new SessionOpenException(FailureKind.ENVIRONMENT, "pack " + pack.id() + ": its pre-flight checks"
                    + " cannot be read: " + e, null);
        }
        PreflightCheck.PreflightContext ctx = new PreflightCheck.PreflightContext() {
            @Override
            public AgentConnection agent() {
                return agent;
            }

            @Override
            public DataChannel data() {
                return data;
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
        SecretMask mask = options.bind().credentials().mask();
        for (PreflightCheck check : checks == null ? List.<PreflightCheck>of() : checks) {
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
            if (reason != null) {
                throw new SessionOpenException(FailureKind.ENVIRONMENT, mask.apply("pre-flight check " + check.id()
                        + ": " + reason), RunLog.fields("preflight", check.id(), "reason", mask.apply(reason)));
            }
        }
    }

    private static void configure(CallRunner runner, DomainPack pack) {
        SyncPolicy policy;
        PromptPolicy prompts;
        Set<String> verbs;
        try {
            policy = pack.syncPolicy();
            prompts = pack.promptPolicy();
            StepLibrary library = pack.steps();
            verbs = library == null || library.verbs() == null ? Set.of() : Set.copyOf(library.verbs());
        } catch (RuntimeException e) {
            policy = null;
            prompts = null;
            verbs = Set.of();
        }
        if (policy != null) {
            runner.sync = policy;
        }
        runner.promptPolicy = prompts == null ? PromptPolicy.FAIL : prompts;
        runner.packVerbs = verbs;
    }

    /** Fixes the sequence number the session starts from, as a run's start mark does. */
    private static void mark(CallRunner runner, String id) throws SessionOpenException, AgentLostException {
        Map<String, Object> params = RunLog.fields("label", "skillroy.session.start", "attrs",
                RunLog.fields("sessionId", id));
        RpcResult result;
        try {
            result = runner.agent.call("trace.mark", params, CallRunner.options(CallRunner.DIAGNOSTIC_TIMEOUT_MS,
                    null));
        } catch (AgentRpcException e) {
            throw new SessionOpenException(FailureKind.ENVIRONMENT, "cannot mark the start of the session: "
                    + e.getMessage(), CallRunner.errorDetail("trace.mark", e));
        }
        runner.noteSeq(result.seqAfter());
        runner.lastActionSeq = result.seqAfter();
        runner.promptCursor = result.seqAfter();
    }

    private void start() {
        Map<String, Object> sessionOptions = RunLog.fields("stepTimeoutMs", options.stepTimeout().toMillis(),
                "readOnly", options.readOnly(), "recoverOnClose", options.recoverOnClose());
        if (options.ui() != null) {
            sessionOptions.put("ui", options.ui().toJson());
        }
        if (options.web() != null) {
            sessionOptions.put("web", Plan.webJson(options.web()));
        }
        if (hooksRecord != null) {
            sessionOptions.put("hooks", hooksRecord);
        }
        if (options.robot()) {
            sessionOptions.put("robot", true);
        }
        if (!options.host().isEmpty()) {
            sessionOptions.put("host", new ArrayList<>(options.host()));
        }
        if (!contributed.isEmpty()) {
            sessionOptions.put("contributed", contributed);
        }
        Map<String, String> capabilityOf = new TreeMap<>();
        List<String> reads = new ArrayList<>();
        for (PrimitiveDescriptor descriptor : primitives.values()) {
            if (descriptor.capabilityId() != null && !descriptor.capabilityId().isBlank()) {
                capabilityOf.put(descriptor.name(), descriptor.capabilityId());
            }
            if (!descriptor.mutating() && !descriptor.destructive()) {
                reads.add(descriptor.name());
            }
        }
        Map<String, Object> env = RunLog.fields("fingerprint", fingerprint.hash(), "components",
                new LinkedHashMap<>(fingerprint.components()));
        journal.append(new StepJournal.SessionStart(id, pack.id(), pack.version(), options.bind().envId(), env,
                options.readOnly(), sessionOptions, clock.instant().toString(), capabilityOf, reads), mask);
    }

    // ------------------------------------------------------------------------------------------------ steps

    /**
     * One call with the step protocol: substitution against the session's bindings, secret parameters, web routing,
     * the call's sync, its capture, the prompt policy, the failure classification. Never throws for a failed call:
     * the outcome says. Journaled as one step without a verb.
     *
     * <p>A call with {@link PlannedCall#literalArgs()} (phase6 security fix S55; phase5-contracts.md 12.2) is compiled
     * and run without reference substitution: every {@code $} of its string arguments is escaped ({@code $$}) before it
     * compiles, so that the substitution the runner applies to every call gives each string back exactly as given and
     * the agent receives it byte for byte; the journal records the arguments as given.
     *
     * @throws AgentLostException if the agent is gone (the step is journaled first)
     * @throws InterruptedException if the thread is interrupted
     */
    public StepOutcome execute(PlannedCall call, Origin origin) throws AgentLostException, InterruptedException {
        return execute(call, origin, null);
    }

    /**
     * {@link #execute(PlannedCall, Origin)} with the provenance a vision tool hands in with the act
     * (phase7-contracts.md 9.2, decisions D331, D351): written (masked, {@code intent} cut at 500 characters) into the
     * step's journal record, so that the journal holds it on the step's own line; null writes none.
     *
     * @param provenance the provenance object of P7 9.2, or null
     * @throws AgentLostException if the agent is gone (the step is journaled first)
     * @throws InterruptedException if the thread is interrupted
     */
    public StepOutcome execute(PlannedCall call, Origin origin, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        this.provenance = provenance;
        String stepId = nextStepId();
        PlannedCall given = confirms(origin) ? call : withoutConfirmation(call);
        Plan.Step step;
        try {
            Plan.Call compiled = PlanCompiler.sessionCall(literal(given), pack, options.stepTimeout().toMillis(), id);
            step = new Plan.Step(stepId, "call", 0, List.of(compiled), List.of());
        } catch (ScenarioException e) {
            return refused(stepId, null, given.args(), List.of(), origin, "the call does not compile: " + e.reason());
        }
        return run(stepId, null, given.args(), step, origin);
    }

    /**
     * A verb (the pack's or a base verb) with its arguments, compiled as the compiler compiles a step (references
     * allowed against the session's slots and oracles), then run call by call; it stops at the first call that does
     * not pass. Journaled as one step with the verb and its arguments as given.
     *
     * @param args the verb's arguments as a scenario writes them (JSON-like, {@code $references} in place)
     * @throws AgentLostException if the agent is gone (the step is journaled first)
     * @throws InterruptedException if the thread is interrupted
     */
    public StepOutcome execute(String verb, Object args, Origin origin) throws AgentLostException,
            InterruptedException {
        return execute(verb, args, origin, null);
    }

    /**
     * {@link #execute(String, Object, Origin)} with the provenance a vision tool hands in with the act
     * (phase7-contracts.md 9.2, decisions D331, D351): written (masked, {@code intent} cut at 500 characters) into the
     * step's journal record; null writes none.
     *
     * @param provenance the provenance object of P7 9.2, or null
     * @throws AgentLostException if the agent is gone (the step is journaled first)
     * @throws InterruptedException if the thread is interrupted
     */
    public StepOutcome execute(String verb, Object args, Origin origin, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException {
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        this.provenance = provenance;
        String stepId = nextStepId();
        Object given = confirms(origin) ? args : withoutConfirmation(args);
        if (!confirms(origin)) {
            // phase7-contracts.md 9.2 (D342) rule 1: a step whose arguments as given name a credential join's secret
            // field, or the joined slot as a whole (Phase 7 security fix, S101), is refused for every origin but a
            // person's, before anything is compiled.
            String secret = secretReference(given);
            if (secret != null) {
                return refusedCredential(stepId, verb, given, origin, secret);
            }
        }
        Plan.Step step;
        try {
            Plan compiled = PlanCompiler.compile(scenarioOf(verb, given), pack);
            Plan.Step only = compiled.steps().get(0);
            step = new Plan.Step(stepId, verb, 0, only.calls(), only.expect());
        } catch (ScenarioException e) {
            return refused(stepId, verb, given, List.of(), origin, "the step does not compile: " + e.reason());
        } catch (RuntimeException e) {
            return refused(stepId, verb, given, List.of(), origin, "the step does not compile: " + e);
        }
        if (!confirms(origin)) {
            List<Plan.Call> calls = new ArrayList<>();
            for (Plan.Call call : step.calls()) {
                calls.add(withoutConfirmation(call));
            }
            step = new Plan.Step(stepId, verb, 0, calls, step.expect());
        }
        return run(stepId, verb, given, step, origin);
    }

    /**
     * What a verb compiles to with these arguments, as {@link #execute(String, Object, Origin)} would compile it,
     * without running or journaling anything (phase6 security fix S57, D158): the calls of its one step and the cleanup
     * calls it registered, each with its arguments as the verb wrote them (a verb's own {@code confirm} included, which
     * {@code execute} strips for every origin but a person's). A host that offers a verb to a client or a model checks
     * these with the client's real arguments before the first call runs (the MCP server's {@code PlanPolicy}, the
     * session service's {@code CallPolicy}). A step that does not compile is a refusal reason, never an exception.
     *
     * @param args the verb's arguments as a scenario writes them (JSON-like, {@code $references} in place)
     * @throws IllegalStateException after {@link #close()}
     */
    public Compiled compile(String verb, Object args) {
        Objects.requireNonNull(verb, "verb");
        requireOpen();
        try {
            Plan compiled = PlanCompiler.compile(scenarioOf(verb, args), pack);
            return new Compiled(compiled.steps().get(0).calls(), compiled.cleanup(), null);
        } catch (ScenarioException e) {
            return new Compiled(List.of(), List.of(), "the step does not compile: " + e.reason());
        } catch (RuntimeException e) {
            return new Compiled(List.of(), List.of(), "the step does not compile: " + e);
        }
    }

    /**
     * {@link #compile(String, Object)} with the arguments as {@link #execute(String, Object, Origin)} takes them from
     * this origin: without {@code confirm} and {@code _meta} (at the top and in a nested {@code args}) for every origin
     * but a person's. The calls keep what the verb itself wrote (its own {@code confirm} included).
     *
     * @throws IllegalStateException after {@link #close()}
     */
    public Compiled compile(String verb, Object args, Origin origin) {
        Objects.requireNonNull(origin, "origin");
        return compile(verb, confirms(origin) ? args : withoutConfirmation(args));
    }

    /**
     * What {@link #compile} answers: the step's calls and the cleanup calls, or why the step does not compile.
     *
     * @param calls the calls of the verb's step, in order; empty when it does not compile
     * @param cleanup the cleanup calls the verb registered; empty when it does not compile
     * @param refusal why the step does not compile, or null
     */
    public record Compiled(List<Plan.Call> calls, List<Plan.Call> cleanup, String refusal) {

        /** Copies the lists. */
        public Compiled {
            calls = calls == null ? List.of() : List.copyOf(calls);
            cleanup = cleanup == null ? List.of() : List.copyOf(cleanup);
        }
    }

    /** A one-step scenario of the session: its resolved slots and oracles declared, so a verb may ask after them. */
    private Scenario scenarioOf(String verb, Object args) {
        Object copy;
        try {
            copy = args == null ? null : Values.normalise(args);
        } catch (JsonException e) {
            throw new IllegalArgumentException("the arguments are not JSON-like: " + e.getMessage(), e);
        }
        return new Scenario(id, pack.id(), List.of(), List.of(), Map.of(), new ArrayList<>(declaredSlots.values()),
                List.of(new Scenario.Step(verb, copy, 0)), List.of(), Map.of(), List.of(),
                options.stepTimeout().toMillis(), "session " + id, "session", options.ui(),
                new ArrayList<>(declaredOracles.values()), Plan.DEFAULT_BIND_TIMEOUT_MS, options.web());
    }

    private StepOutcome run(String stepId, String verb, Object givenArgs, Plan.Step step, Origin origin)
            throws AgentLostException, InterruptedException {
        // Phase 7 security fix, S101 (P2 8.4, P7 9.2): a step of any origin but a person's resolves its call arguments
        // against the slots' public values, so that no spelling of a reference reaches a joined secret on a model's or
        // a client's behalf; the credential rule's refusals below stay the early, well-worded answer.
        runner.publicArguments = !confirms(origin);
        try {
            return runSteps(stepId, verb, givenArgs, step, origin);
        } finally {
            runner.publicArguments = false;
        }
    }

    private StepOutcome runSteps(String stepId, String verb, Object givenArgs, Plan.Step step, Origin origin)
            throws AgentLostException, InterruptedException {
        long t0 = clock.millis();
        List<Map<String, Object>> callRecords = callRecords(step.calls());
        if (options.readOnly()) {
            for (Plan.Call call : step.calls()) {
                PrimitiveDescriptor descriptor = primitives.get(call.primitive());
                if (descriptor == null || descriptor.mutating() || descriptor.destructive()) {
                    String why = descriptor == null ? "is not in the target's catalogue"
                            : descriptor.destructive() ? "is destructive" : "changes the target";
                    Map<String, Object> detail = RunLog.fields("primitive", call.primitive(), "code",
                            (long) ErrorCodes.READ_ONLY, "error", "READ_ONLY");
                    return finish(stepId, verb, givenArgs, step, callRecords, origin, 0, Status.ERROR,
                            FailureKind.ENVIRONMENT, call.primitive() + " refused: READ_ONLY: the session is read-only"
                                    + " and " + call.primitive() + " " + why, detail, runner.lastSeenSeq, t0, null);
                }
            }
        }
        String gate = gate(step, origin);
        if (gate != null) {
            Map<String, Object> detail = RunLog.fields("refused", gate.startsWith("robot") ? "robot"
                    : gate.startsWith("a capture") ? "glass" : "credential");
            return finish(stepId, verb, givenArgs, step, callRecords, origin, 0, Status.ERROR, FailureKind.ENVIRONMENT,
                    gate, detail, runner.lastSeenSeq, t0, null);
        }
        long seqBefore = runner.lastSeenSeq;
        observeStepStart(stepId, verb, origin);
        runner.currentStep = stepId;
        runner.stepFidelity = new ArrayList<>();
        runner.stepEvents = new ArrayList<>();
        runner.lastResult = null;
        Map<String, Object> capturedBefore = new LinkedHashMap<>(runner.bindings.captured());
        String imageSkipped = null;
        int executed = 0;
        int position = 0;
        Abort failure = null;
        try {
            for (position = 0; position < step.calls().size(); position++) {
                Plan.Call call = step.calls().get(position);
                PrimitiveDescriptor descriptor = primitives.get(call.primitive());
                if (call.guard() != null && !recordedAnchor(call.guard())) {
                    guard(stepId, call);
                }
                mutated |= descriptor == null || descriptor.mutating() || descriptor.destructive();
                runner.execute(step, position, call, id + "/" + stepId + "/" + (position + 1), () -> false);
                executed = position + 1;
                if (confirms(origin)) {
                    consumed(call);
                }
            }
            if (!step.expect().isEmpty()) {
                imageSkipped = expectNow(step.expect(), stepId);
            }
        } catch (Abort abort) {
            failure = abort;
            // A reference that did not resolve stopped the call before it was sent; any other failure came after.
            executed = abort.kind == FailureKind.RESOLUTION_FAILED || position >= step.calls().size() ? executed
                    : position + 1;
        } catch (AgentLostException e) {
            runner.agentLost = true;
            finish(stepId, verb, givenArgs, step, callRecords, origin, executed, Status.ERROR, FailureKind.AGENT_LOST,
                    "the agent was lost: " + e.getMessage(), RunLog.fields("exception", e.toString()), seqBefore, t0,
                    null);
            throw e;
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            // P2-L8-F4: the message names the class only; the detail keeps the exception's text.
            failure = new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "internal error: "
                    + e.getClass().getName(), RunLog.fields("exception", e.toString()));
        } finally {
            runner.currentStep = null;
        }
        if (failure == null && imageSkipped != null) {
            runner.stepFidelity.add(imageSkipped);
        }
        Map<String, Object> captured = new LinkedHashMap<>();
        runner.bindings.captured().forEach((slot, value) -> {
            if (!Objects.equals(capturedBefore.get(slot), value) || !capturedBefore.containsKey(slot)) {
                captured.put(slot, value);
            }
        });
        if (failure != null) {
            return finish(stepId, verb, givenArgs, step, callRecords, origin, executed, failure.status, failure.kind,
                    failure.getMessage(), failure.detail, seqBefore, t0, null);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        if (!captured.isEmpty()) {
            detail.put("captured", captured);
        }
        Status status = runner.stepFidelity.isEmpty() ? Status.PASS : Status.DEGRADED;
        String snapshotHash = origin.kind() == Origin.Kind.MODEL ? snapshotHash() : null;
        return finish(stepId, verb, givenArgs, step, callRecords, origin, executed, status, null, null, detail,
                seqBefore, t0, snapshotHash);
    }

    /** A step refused before anything was sent (it does not compile): ERROR / ENVIRONMENT, journaled. */
    private StepOutcome refused(String stepId, String verb, Object givenArgs, List<Plan.Call> calls, Origin origin,
                                String message) {
        Plan.Step step = new Plan.Step(stepId, verb == null ? "call" : verb, 0, calls, List.of());
        return finish(stepId, verb, givenArgs, step, callRecords(calls), origin, 0, Status.ERROR,
                FailureKind.ENVIRONMENT, message, RunLog.fields("compile", message), runner.lastSeenSeq,
                clock.millis(), null);
    }

    /** Builds the outcome (masked) and journals the step. */
    private StepOutcome finish(String stepId, String verb, Object givenArgs, Plan.Step step,
                               List<Map<String, Object>> callRecords, Origin origin, int executed, Status status,
                               FailureKind kind, String message, Map<String, Object> detail, long seqBefore, long t0,
                               String snapshotHash) {
        Duration duration = Duration.ofMillis(Math.max(0, clock.millis() - t0));
        @SuppressWarnings("unchecked")
        Map<String, Object> maskedDetail = (Map<String, Object>) mask.apply(jsonForm(detail));
        List<String> fidelity = executed == 0 || runner.stepFidelity == null ? List.of() : runner.stepFidelity;
        List<String> maskedFidelity = new ArrayList<>();
        for (String line : fidelity) {
            maskedFidelity.add(mask.apply(line));
        }
        List<Event> events = new ArrayList<>();
        if (executed > 0 && runner.stepEvents != null) {
            for (Event event : runner.stepEvents) {
                events.add(maskEvent(event));
            }
        }
        RpcResult last = executed > 0 && runner.lastResult != null ? maskResult(runner.lastResult) : null;
        List<PlannedCall> planned = new ArrayList<>();
        for (Plan.Call call : step.calls()) {
            planned.add(new PlannedCall(call.primitive(), call.args(), call.sync(), call.timeoutMs(), call.await(),
                    call.capture()));
        }
        StepOutcome outcome = new StepOutcome(stepId, verb, planned, executed, status, kind,
                message == null ? null : mask.apply(message), maskedDetail, last, events, seqBefore,
                runner.lastSeenSeq, maskedFidelity, duration);
        Map<String, Object> outcomeJson = new LinkedHashMap<>();
        outcomeJson.put("status", status.name());
        if (kind != null) {
            outcomeJson.put("kind", kind.name());
        }
        if (message != null) {
            outcomeJson.put("message", message);
        }
        if (detail != null && !detail.isEmpty()) {
            outcomeJson.put("detail", jsonForm(detail));
        }
        outcomeJson.put("executed", executed);
        outcomeJson.put("seqBefore", seqBefore);
        outcomeJson.put("seqAfter", runner.lastSeenSeq);
        outcomeJson.put("durationMs", duration.toMillis());
        outcomeJson.put("degradedFidelity", maskedFidelity);
        observeStepStart(stepId, verb, origin);
        Map<String, Object> stepProvenance = provenance(provenance);
        provenance = null;
        journal.append(new StepJournal.StepEntry(stepId, origin, verb, jsonForm(givenArgs), callRecords,
                outcomeJson, snapshotHash, stepProvenance), mask);
        runner.stepEvents = null;
        stepObserved = false;
        observer.sessionStep(outcome);
        return outcome;
    }

    /** {@code sessionStepStarted} once per step: before its first call, or before a refusal's outcome. */
    private void observeStepStart(String stepId, String verb, Origin origin) {
        if (!stepObserved) {
            stepObserved = true;
            observer.sessionStepStarted(new StepStart(stepId, verb, 0, origin.kind().name()));
        }
    }

    /**
     * The session's observer (phase6-contracts.md 10.2), wrapped so that it never throws: what the session service
     * tells about its turns and the MCP server about its tool calls. {@link RunObserver#NONE} without telemetry. The
     * observer is the opener's; the session never closes it.
     */
    public RunObserver observer() {
        return observer;
    }

    /** The journal's {@code calls}: each call with its arguments as they will be sent (substituted). */
    private List<Map<String, Object>> callRecords(List<Plan.Call> calls) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Plan.Call call : calls) {
            Object args;
            try {
                args = callArguments(call.args());
            } catch (ResolutionException e) {
                args = call.args();
            }
            Map<String, Object> record = RunLog.fields("primitive", call.primitive(), "args", args, "sync",
                    call.sync().name(), "timeoutMs", call.timeoutMs());
            if (call.await() != null) {
                record.put("await", RunLog.fields("topics", call.await().topics(), "match", call.await().match(),
                        "timeoutMs", call.await().timeoutMs()));
            }
            if (call.capture() != null) {
                record.put("capture", RunLog.fields("slot", call.capture().slot(), "fields",
                        new LinkedHashMap<>(call.capture().fields())));
            }
            records.add(record);
        }
        return records;
    }

    /**
     * A call's arguments substituted as the runner will send them: against the public values for a step of any origin
     * but a person's (Phase 7 security fix, S101), with the joined secret fields for a person's.
     */
    private Object callArguments(Map<String, Object> args) throws ResolutionException {
        return runner.publicArguments ? runner.bindings.substitutePublic(args) : runner.bindings.substitute(args);
    }

    /**
     * The expectations of an {@code expect} step, evaluated once: the first that does not hold fails the step. An
     * {@link ImageCheck} is evaluated from the step's own capture result through the image steps (phase7-contracts.md
     * 6.7); a {@link VisualCheck} is the scenario player's to evaluate, never the session's.
     *
     * @return the skipped line of an image check without a baseline in this live session, or null
     */
    private String expectNow(List<Check> checks, String stepId) throws Abort, AgentLostException,
            InterruptedException {
        String skippedLine = null;
        for (Check check : checks) {
            if (check instanceof ImageCheck image) {
                Object capture = runner.lastResult == null ? null : runner.lastResult.value();
                String line = ImageRuns.raise(stepId, ImageRuns.run(stepId, () -> imageSteps(stepBaselines)
                        .check(stepId, image, capture)));
                skippedLine = line != null ? line : skippedLine;
                continue;
            }
            if (check instanceof VisualCheck visual) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "a visual check (" + visual.describe()
                        + ") is evaluated by the scenario player, not by a session step", RunLog.fields("check",
                        visual.describe()));
            }
            if (!(check instanceof Expectation expectation)) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "a stored-row check ("
                        + check.describe() + ") is not evaluated in a session", RunLog.fields("check",
                        check.describe()));
            }
            Evaluation evaluation = evaluateCheck(expectation.path(), expectation.matcher());
            if (evaluation.fetchError() != null) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, evaluation.reason(),
                        RunLog.fields("path", expectation.path()));
            }
            if (!evaluation.held()) {
                throw new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, "expectation failed: "
                        + expectation.path() + " " + Values.safeWrite(expectation.matcher()) + ": "
                        + evaluation.reason(), RunLog.fields("path", expectation.path(), "matcher",
                        expectation.matcher(), "actual", evaluation.actual(), "reason", evaluation.reason()));
            }
        }
        return skippedLine;
    }

    // ------------------------------------------------------------------------------------------------ resolution

    /**
     * Live resolution of one query through a {@link ResolutionEngine} in {@code live} mode (the pack's resolvers and
     * the contributed ones, the slot's verifiers): the result joins the session's bindings under the slot name, with
     * its credential's secret fields for later calls. Journaled (only a resolution that succeeds).
     *
     * @param args the query's arguments, {@code $references} to earlier slots, oracles and captures in place
     * @param strategy strategy tokens; empty or null means not restricted
     * @param verify verifier ids; empty or null means the pack's for the kind
     * <p>Phase 7 security fix, S113, S139: a slot whose current binding a person made (a person's {@code resolve} or
     * {@code fixture}, the scenario player's binding of a scenario's slots) is rebound for a person's origin only; for
     * any other origin the call is refused before anything is resolved, joined or journaled
     * ({@link #personSlotRefusal}), while a model or a client may bind new slots and rebind the ones it bound. The
     * {@code resolve} record carries the origin.
     *
     * @param credential the slot's credential declaration, or null
     * @throws BindException RESOLUTION_FAILED or ENVIRONMENT, as a bind reports them; ENVIRONMENT for a slot a person
     *     bound, asked by another origin ({@code detail.refused: slot})
     * @throws IllegalArgumentException if the slot name is not a name or is reserved
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Resolution resolve(String slot, String kind, Map<String, Object> args, List<String> strategy,
                              List<String> verify, CredentialSpec credential, Origin origin)
            throws BindException, AgentLostException, InterruptedException {
        requireName(slot, "slot");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        refuseRebind(slot, origin);
        List<String> tokens = strategy == null ? List.of() : List.copyOf(strategy);
        List<String> verifiers = verify == null ? List.of() : List.copyOf(verify);
        Map<String, Object> given = args == null ? Map.of() : args;
        Map<String, Object> substituted;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) runner.bindings.substitutePublic(
                    Values.normaliseSorted(given));
            substituted = typed;
        } catch (ResolutionException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, ResolutionException.REFERENCE, slot,
                    mask.apply(e.getMessage()), RunLog.fields("reference", e.reference()));
        } catch (JsonException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, "ARGS", slot, "slot '" + slot + "': the"
                    + " arguments are not JSON-like: " + e.getMessage(), null);
        }
        Resolution resolution;
        try (ResolutionEngine engine = engine()) {
            resolution = engine.resolve(slot, kind, substituted, tokens, verifiers, credential);
        }
        Map<String, String> secret = null;
        if (resolution.credential() != null) {
            secret = options.bind().credentials().secrets(resolution.credential().realm(),
                    resolution.credential().id());
        }
        slotValues.remove(slot);
        slotValues.put(slot, resolution.value());
        strategies.put(slot, resolution.strategy().name());
        resolutions.remove(slot);
        resolutions.put(slot, resolution);
        joins.remove(slot);
        inFlight.remove(slot);
        credentialRefs.remove(slot);
        if (resolution.credential() != null) {
            credentialRefs.put(slot, resolution.credential());
            if (secret != null) {
                joins.put(slot, secret);
                mask = mask.plus(secret.values());
                log.mask(mask);
                // phase7-contracts.md 9.2 (D342): in flight until a person's step consumes it.
                inFlight.add(slot);
            }
        }
        declaredSlots.remove(slot);
        declaredSlots.put(slot, new Scenario.Slot(slot, "query", null, 0, kind, substituted, tokens, verifiers,
                credential));
        boundBy.remove(slot);
        boundBy.put(slot, origin.kind());
        rebuildBindings();
        journal.append(new StepJournal.ResolveEntry(slot, kind, jsonMap(given), tokens, verifiers, credential,
                BindingsFile.resolutionJson(resolution), origin), mask);
        return resolution;
    }

    /** Why a credential slot is never bound on a model's or a client's behalf (phase7-contracts.md 9.2, D342). */
    public static final String FIXTURE_CREDENTIAL_REFUSED = "a credential slot is bound by a person, never on a model's"
            + " or a client's behalf";

    /**
     * Why a slot a person bound is not rebound on a model's or a client's behalf (Phase 7 security fix, S113, S139;
     * phase7-contracts.md 6.8, 9.2): {@code slot $<slot> was bound by the person; resolve under another name}.
     */
    public static String personSlotRefusal(String slot) {
        return "slot $" + slot + " was bound by the person; resolve under another name";
    }

    /** The slot rule of {@link #resolve} and {@link #fixture}: a person's binding is rebound by a person only. */
    private void refuseRebind(String slot, Origin origin) throws BindException {
        if (!confirms(origin) && boundBy.get(slot) == Origin.Kind.PERSON) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, personSlotRefusal(slot),
                    RunLog.fields("refused", "slot"));
        }
    }

    /**
     * Binds a fixture slot into the session as a plan's fixture is bound (phase7-contracts.md 9.2, D425): the value's
     * {@code $references} to earlier slots are substituted with public values; the slot is declared {@code fixture},
     * so that a later verb compiles against it (a {@code signOn} sees its credential); its strategy is
     * {@code FIXTURE}, with no resolution and no bindings-file entry. With a credential, the entry's id is the value's
     * {@code key} field and its secret fields are joined, which puts the slot in flight as a {@link #resolve} does.
     * A slot with a credential is bound only for a person's origin. Journaled as one {@code fixture} record.
     *
     * @param value the fixture's value as a scenario writes it (JSON-like, {@code $references} in place)
     * @param credential the slot's credential declaration, or null
     * @param origin who binds it
     * @throws BindException RESOLUTION_FAILED for a reference that does not resolve or a value that is not JSON-like;
     *     ENVIRONMENT for a credential whose key field is missing, whose entry the credentials do not have, or whose
     *     secret field is already a field of the value, and for a slot a person bound, asked by another origin (Phase 7
     *     security fix, S113, as {@link #resolve}; nothing is joined or journaled)
     * @throws IllegalArgumentException if the slot name is not a name or is reserved, or a credential slot is bound for
     *     a model's or a client's origin ({@link #FIXTURE_CREDENTIAL_REFUSED}; nothing is joined or journaled)
     */
    public Object fixture(String slot, Object value, CredentialSpec credential, Origin origin) throws BindException {
        requireName(slot, "slot");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        if (credential != null && origin.kind() != Origin.Kind.PERSON) {
            throw new IllegalArgumentException("slot '" + slot + "': " + FIXTURE_CREDENTIAL_REFUSED);
        }
        refuseRebind(slot, origin);
        Object substituted;
        try {
            substituted = runner.bindings.substitutePublic(Values.normaliseSorted(value));
        } catch (ResolutionException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, ResolutionException.REFERENCE, slot,
                    mask.apply(e.getMessage()), RunLog.fields("reference", e.reference()));
        } catch (JsonException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, "ARGS", slot, "slot '" + slot + "': the value is"
                    + " not JSON-like: " + e.getMessage(), null);
        }
        CredentialRef ref = null;
        Map<String, String> secret = null;
        if (credential != null) {
            if (!(substituted instanceof Map<?, ?> map) || map.get(credential.key()) == null
                    || map.get(credential.key()) instanceof Map || map.get(credential.key()) instanceof List) {
                throw new BindException(FailureKind.ENVIRONMENT, null, slot, "slot '" + slot + "' declares credential"
                        + " realm " + credential.realm() + " with key '" + credential.key() + "', but its value has no"
                        + " such scalar field", null);
            }
            ref = new CredentialRef(credential.realm(), Values.stringOf(map.get(credential.key())));
            secret = options.bind().credentials().secrets(ref.realm(), ref.id());
            if (secret == null) {
                throw new BindException(FailureKind.ENVIRONMENT, null, slot, "slot '" + slot + "' needs the credential "
                        + ref + ", which the credentials (" + options.bind().credentials().origin() + ") do not have",
                        RunLog.fields("credential", Map.of("realm", ref.realm(), "id", ref.id())));
            }
            for (String field : secret.keySet()) {
                if (map.containsKey(field)) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, slot, "slot '" + slot + "': the credential"
                            + " field '" + field + "' of " + ref + " is already a field of the value", null);
                }
            }
        }
        slotValues.remove(slot);
        slotValues.put(slot, substituted);
        strategies.put(slot, Strategy.FIXTURE.name());
        resolutions.remove(slot);
        joins.remove(slot);
        inFlight.remove(slot);
        credentialRefs.remove(slot);
        if (ref != null) {
            credentialRefs.put(slot, ref);
            joins.put(slot, secret);
            mask = mask.plus(secret.values());
            log.mask(mask);
            // phase7-contracts.md 9.2 (D342): in flight until a person's step consumes it.
            inFlight.add(slot);
        }
        declaredSlots.remove(slot);
        declaredSlots.put(slot, new Scenario.Slot(slot, "fixture", substituted, 0, null, null, null, null,
                credential));
        boundBy.remove(slot);
        boundBy.put(slot, origin.kind());
        rebuildBindings();
        journal.append(new StepJournal.FixtureEntry(slot, jsonForm(value), credential, origin), mask);
        return substituted;
    }

    /**
     * Evaluates one of the pack's oracles on arguments that may reference the session's slots; the output joins the
     * bindings as {@code $oracle.<name>}. Journaled.
     *
     * @throws BindException as a bind reports an oracle's failure
     * @throws IllegalArgumentException if the name is not a name
     */
    public Object evaluate(String name, String fn, Object args, Origin origin) throws BindException {
        requireName(name, "oracle");
        Objects.requireNonNull(fn, "fn");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        Object substituted;
        try {
            substituted = runner.bindings.substitutePublic(Values.normaliseSorted(args));
        } catch (ResolutionException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, ResolutionException.REFERENCE, "oracle." + name,
                    mask.apply(e.getMessage()), RunLog.fields("reference", e.reference()));
        } catch (JsonException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, "ARGS", "oracle." + name, "oracle '" + name
                    + "': the arguments are not JSON-like: " + e.getMessage(), null);
        }
        Object value;
        try (ResolutionEngine engine = engine()) {
            value = engine.evaluate(name, fn, substituted, null);
        }
        String inputsHash = ResolutionEngine.hash(substituted);
        oracleValues.remove(name);
        oracleValues.put(name, new OracleValue(name, fn, inputsHash, value, null, null));
        declaredOracles.remove(name);
        declaredOracles.put(name, new Scenario.OracleEntry(name, fn, substituted, null, false, 0));
        rebuildBindings();
        journal.append(new StepJournal.OracleEntry(name, fn, jsonForm(args), inputsHash, value), mask);
        return mask.apply(jsonForm(value));
    }

    private ResolutionEngine engine() {
        return new ResolutionEngine(pack, agent, options.bind().withClock(clock), Duration.ofMillis(RESOLVE_BUDGET_MS),
                BindListener.NONE, data);
    }

    /** The session's bindings from its slots, oracles, joins and captures; the runner resolves calls against them. */
    private void rebuildBindings() {
        Map<String, Object> captured = runner.bindings == null ? Map.of() : runner.bindings.captured();
        Bindings bindings = Bindings.of(slotValues, strategies).withBind(options.bind().envId(), BindMode.LIVE,
                fingerprint, Map.of(), resolutions, oracleValues, credentialRefs, Map.of());
        for (Map.Entry<String, Map<String, String>> e : joins.entrySet()) {
            bindings = bindings.withCredential(e.getKey(), e.getValue(), credentialRefs.get(e.getKey()));
        }
        for (Map.Entry<String, Object> e : captured.entrySet()) {
            bindings = bindings.withCaptured(e.getKey(), e.getValue());
        }
        runner.bindings = bindings;
    }

    // ------------------------------------------------------------------------------------------------ reads

    /**
     * An expectation evaluated now (P1 8.4 matchers over the fact roots, the UI snapshot among them): one read of the
     * path's root, no waiting. Journaled with its outcome; a matcher or path that is not valid does not hold, and
     * neither does any matcher on a value the session's mask changes (it holds a secret, which {@link #observe} shows
     * as {@code <redacted>}): a check is no oracle on a secret (phase5-contracts.md 12.2).
     *
     * @param matcher a scalar or a matcher object, {@code $references} in place
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public CheckOutcome check(String path, Object matcher, Origin origin) throws AgentLostException,
            InterruptedException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        long t0 = clock.millis();
        Evaluation evaluation = evaluateCheck(path, matcher);
        Object actual = mask.apply(jsonForm(evaluation.actual()));
        String reason = evaluation.reason() == null ? null : mask.apply(evaluation.reason());
        journal.append(new StepJournal.CheckEntry(path, jsonForm(matcher), actual, evaluation.held(), reason), mask);
        return new CheckOutcome(path, matcher, actual, evaluation.held(), reason,
                Duration.ofMillis(Math.max(0, clock.millis() - t0)));
    }

    /** One evaluation of an expectation: whether it held, the value found, why not, and a read's failure. */
    private record Evaluation(boolean held, Object actual, String reason, String fetchError) {
    }

    private Evaluation evaluateCheck(String path, Object matcher) throws AgentLostException,
            InterruptedException {
        ExpectationPath parsed;
        Object normal;
        try {
            parsed = ExpectationPath.parse(path).bind(runner.bindings::lookupPublic);
            if (!facts.containsKey(parsed.root())) {
                return new Evaluation(false, null, "no fact root '" + parsed.root() + "'; roots: " + facts.keySet()
                        + ("db".equals(parsed.root()) ? " (stored-row checks are not evaluated in a session)" : ""),
                        null);
            }
            normal = Matchers.normalise(runner.bindings.substitutePublic(Values.normalise(matcher)));
        } catch (IllegalArgumentException | ResolutionException | JsonException e) {
            return new Evaluation(false, null, "not a valid expectation: " + e.getMessage(), null);
        }
        Object fact;
        try {
            fact = fetch(parsed.root());
        } catch (FactFailure e) {
            return new Evaluation(false, null, "fact '" + parsed.root() + "' could not be read: " + e.getMessage(),
                    e.getMessage());
        }
        ExpectationPath.Resolution resolution = parsed.resolve(fact);
        if (resolution.resolved()) {
            // On the value as the session's mask leaves it (phase5-contracts.md 12.2).
            Object masked = Matchers.maskedSecret(mask, resolution.value());
            if (masked != null) {
                return new Evaluation(false, masked, Matchers.HOLDS_A_SECRET, null);
            }
        }
        Matchers.Outcome outcome = Matchers.evaluate(normal, resolution.resolved(), resolution.value(),
                resolution.problem());
        return new Evaluation(outcome.holds(), resolution.value(), outcome.reason(), null);
    }

    /**
     * The current value at an expectation path, for an exported expectation the export marks as observed (D86).
     * Journaled.
     *
     * @return the value, masked
     * @throws IllegalArgumentException if the path is not valid, names no fact root or does not resolve now
     * @throws IllegalStateException if the root's fact could not be read
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Object observe(String path, Origin origin) throws AgentLostException, InterruptedException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        Object value = valueAt(path);
        Object masked = mask.apply(jsonForm(value));
        journal.append(new StepJournal.ObserveEntry(path, masked), mask);
        return masked;
    }

    /**
     * The value of a fact root, or of a path inside it ({@code fact("loan", "lines[0].qty")}); not journaled.
     *
     * @param path a path inside the root, or null or empty for the whole root
     * @return the value, masked; null when the path does not resolve
     * @throws IllegalArgumentException if the root is unknown or the path is not valid
     * @throws IllegalStateException if the root's fact could not be read
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Object fact(String root, String path) throws AgentLostException, InterruptedException {
        Objects.requireNonNull(root, "root");
        requireOpen();
        String full = path == null || path.isBlank() ? root : root + (path.startsWith("[") ? "" : ".") + path;
        ExpectationPath parsed = parsePath(full);
        Object fact;
        try {
            fact = fetch(parsed.root());
        } catch (FactFailure e) {
            throw new IllegalStateException("fact '" + parsed.root() + "' could not be read: " + e.getMessage(), e);
        }
        ExpectationPath.Resolution resolution = parsed.resolve(fact);
        return resolution.resolved() ? mask.apply(jsonForm(resolution.value())) : null;
    }

    private Object valueAt(String path) throws AgentLostException, InterruptedException {
        ExpectationPath parsed = parsePath(path);
        Object fact;
        try {
            fact = fetch(parsed.root());
        } catch (FactFailure e) {
            throw new IllegalStateException("fact '" + parsed.root() + "' could not be read: " + e.getMessage(), e);
        }
        ExpectationPath.Resolution resolution = parsed.resolve(fact);
        if (!resolution.resolved()) {
            throw new IllegalArgumentException("path " + path + " does not resolve now: " + resolution.problem());
        }
        return resolution.value();
    }

    private ExpectationPath parsePath(String path) {
        ExpectationPath parsed;
        try {
            parsed = ExpectationPath.parse(path).bind(runner.bindings::lookupPublic);
        } catch (ResolutionException e) {
            throw new IllegalArgumentException(mask.apply(e.getMessage()), e);
        }
        if (!facts.containsKey(parsed.root())) {
            throw new IllegalArgumentException("no fact root '" + parsed.root() + "'; roots: " + facts.keySet());
        }
        return parsed;
    }

    /** A fact root could not be read (not a lost agent). */
    private static final class FactFailure extends Exception {

        private static final long serialVersionUID = 1L;

        FactFailure(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    private Object fetch(String root) throws FactFailure, AgentLostException, InterruptedException {
        try {
            return facts.get(root).fetch(factConnection);
        } catch (AgentLostException e) {
            runner.agentLost = true;
            throw e;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new FactFailure(mask.apply(message), e);
        }
    }

    /**
     * The UI as text (the {@link UiSnapshotText} renderer over {@code swing.snapshot}, {@code app.state} and the
     * embedded browsers read through the web path), masked; a read that fails leaves its part out.
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public String snapshotText(int maxChars) throws AgentLostException, InterruptedException {
        requireOpen();
        Object snapshot = quietRead("swing.snapshot", Map.of());
        Object appState = quietRead("app.state", Map.of());
        List<UiSnapshotText.WebView> views = new ArrayList<>();
        Capability capability = caps.get("web.browsers");
        if (capability != null && capability.state() != CapabilityState.UNAVAILABLE) {
            Object browsers = webRead("web.browsers", Map.of());
            List<Map<?, ?>> ordered = new ArrayList<>();
            if (browsers instanceof Map<?, ?> value && value.get("browsers") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> browser && browser.get("ref") != null) {
                        ordered.add(browser);
                    }
                }
            }
            ordered.sort(Comparator.comparing(b -> String.valueOf(b.get("ref")), PlanExecutor::compareRefs));
            for (Map<?, ?> browser : ordered) {
                Object aria = null;
                if (Boolean.TRUE.equals(browser.get("visibleInSwing"))) {
                    aria = webRead("web.ariaSnapshot", RunLog.fields("browser", String.valueOf(browser.get("ref")),
                            "maxNodes", 200L, "interestingOnly", true));
                }
                views.add(new UiSnapshotText.WebView((Map<?, ?>) mask.apply(jsonForm(browser)),
                        mask.apply(jsonForm(aria)), aria == null ? null : web.pathId()));
            }
        }
        return UiSnapshotText.render(mask.apply(jsonForm(snapshot)), mask.apply(jsonForm(appState)), views,
                Math.max(100, maxChars));
    }

    /** A read for the snapshot text: an error answers null (the part is left out). */
    private Object quietRead(String primitive, Map<String, Object> params) throws AgentLostException {
        try {
            RpcResult result = agent.call(primitive, params, CallRunner.options(READ_TIMEOUT_MS, null));
            runner.noteSeq(result.seqAfter());
            return result.value();
        } catch (AgentRpcException | RuntimeException e) {
            return null;
        } catch (AgentLostException e) {
            runner.agentLost = true;
            throw e;
        }
    }

    private Object webRead(String primitive, Map<String, Object> params) throws AgentLostException {
        try {
            return web.call("snapshot", primitive, params, CallRunner.options(READ_TIMEOUT_MS, null), true,
                    runner.lastSeenSeq, new String[1]).result().value();
        } catch (AgentRpcException | WebRun.PathFailure | RuntimeException e) {
            return null;
        } catch (AgentLostException e) {
            runner.agentLost = true;
            throw e;
        }
    }

    /** {@code sha256:} of the snapshot text a model would read after a step; null when it cannot be read. */
    private String snapshotHash() throws InterruptedException {
        try {
            String text = snapshotText(UiSnapshotText.DEFAULT_BUDGET);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (AgentLostException | NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The agent's events after {@code seq} on {@code topics} (comma-separated globs; null or blank for all), then the
     * web path's own, at most {@code max} in all, masked. A read that fails gives what was read (a lost agent: none).
     */
    public List<Event> eventsSince(long seq, String topics, int max) {
        requireOpen();
        int limit = Math.max(0, Math.min(max, 200));
        List<Event> out = new ArrayList<>();
        if (limit == 0) {
            return out;
        }
        Map<String, Object> params = RunLog.fields("since", seq, "limit", (long) limit);
        if (topics != null && !topics.isBlank()) {
            params.put("topics", topics);
        }
        try {
            RpcResult result = agent.call("evt.query", params, CallRunner.options(READ_TIMEOUT_MS, null));
            if (result.value() instanceof Map<?, ?> value && value.get("events") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw && out.size() < limit) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> json = (Map<String, Object>) raw;
                        out.add(maskEvent(Event.fromJson(json)));
                    }
                }
            }
        } catch (AgentLostException e) {
            runner.agentLost = true;
            return out;
        } catch (AgentRpcException | RuntimeException e) {
            // Best effort: the path's events below.
        }
        for (Event event : web.pathEventsSince(seq, topics)) {
            if (out.size() >= limit) {
                break;
            }
            out.add(maskEvent(event));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ Phase 7

    /** The longest {@code intent} a step's provenance keeps (phase7-contracts.md 9.2). */
    static final int PROVENANCE_INTENT_MAX = 500;

    /** The longest bounded read {@link #read} allows, when a primitive's own timeout is longer than 10 s. */
    static final long READ_TIMEOUT_CAP_MS = 120_000L;

    /** The {@code hook.*} and {@code rule.*} reads {@link #read} serves (phase7-contracts.md 9.1). */
    static final Set<String> HOOK_READS = Set.of("hook.health", "hook.events", "rule.list");

    /** The provenance as the journal keeps it: a JSON-like copy, {@code intent} cut; null for none. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> provenance(Map<String, Object> given) {
        if (given == null) {
            return null;
        }
        Object form = jsonForm(given);
        if (!(form instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
        if (copy.get("intent") instanceof String intent && intent.length() > PROVENANCE_INTENT_MAX) {
            copy.put("intent", intent.substring(0, PROVENANCE_INTENT_MAX));
        }
        return copy;
    }

    /**
     * One unjournaled read of the target (phase7-contracts.md 9.2, decision D308): a capture, a hit-test,
     * {@code swing.rendering}. Only a primitive the target's catalogue lists as neither mutating, destructive nor
     * {@code script}, and not on the session's exclusion list ({@code data.query}, {@code agent.*}, {@code hook.*} and
     * {@code rule.*} beyond {@code hook.health}, {@code hook.events} and {@code rule.list}, and a {@code swing.capture}
     * with {@code mode: ROBOT} or {@code redact: false}, since a read has no origin). Bounded: 10 s, or the primitive's
     * own timeout when longer, at most 120 s. Masked: every text value through the session's secret mask, except an
     * image result's {@code base64} entry (its pixels the agent redacted; it never reaches a text route). Not
     * journaled.
     *
     * @param args the primitive's arguments; null means none
     * @return the masked result value
     * @throws IllegalArgumentException if the primitive is not one {@code read} serves
     * @throws AgentRpcException with the agent's code, message and data when the agent answers an error
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Object read(String primitive, Map<String, Object> args) throws AgentRpcException, AgentLostException,
            InterruptedException {
        Objects.requireNonNull(primitive, "primitive");
        requireOpen();
        Map<String, Object> params = args == null ? Map.of() : args;
        String refusal = readRefusal(primitive, params);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        PrimitiveDescriptor descriptor = primitives.get(primitive);
        long timeout = Math.min(READ_TIMEOUT_CAP_MS, Math.max(READ_TIMEOUT_MS, descriptor.timeoutMs()));
        RpcResult result;
        try {
            result = agent.call(primitive, params, CallRunner.options(timeout, null));
        } catch (AgentLostException e) {
            runner.agentLost = true;
            throw e;
        }
        if (Thread.interrupted()) {
            throw new InterruptedException("interrupted during " + primitive);
        }
        runner.noteSeq(result.seqAfter());
        return maskRead(result.value());
    }

    /** Why {@link #read} refuses this primitive and these arguments, or null when it serves them. */
    String readRefusal(String primitive, Map<String, Object> args) {
        PrimitiveDescriptor descriptor = primitives.get(primitive);
        if (descriptor == null) {
            return primitive + " is not in the target's catalogue";
        }
        if (descriptor.mutating() || descriptor.destructive()) {
            return primitive + " changes the target: a read serves no mutating or destructive primitive";
        }
        boolean hookOrRule = primitive.startsWith("hook.") || primitive.startsWith("rule.");
        if (descriptor.script() || "data.query".equals(primitive) || primitive.startsWith("agent.")
                || (hookOrRule && !HOOK_READS.contains(primitive))) {
            return primitive + " is not served by a read";
        }
        if ("swing.capture".equals(primitive)) {
            if (Plan.robotValue(args.get("mode"))) {
                return "swing.capture {mode: ROBOT} is a person's evidence: never a read";
            }
            if (Plan.unredacted(args)) {
                return "swing.capture {redact: false} is a person's call: never a read";
            }
        }
        return null;
    }

    /** A read's value through the mask, an image's {@code base64} entry kept as it is (P7 9.2). */
    @SuppressWarnings("unchecked")
    private Object maskRead(Object value) {
        if (value instanceof Map<?, ?> map && map.get("base64") instanceof String base64) {
            Map<String, Object> rest = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (!"base64".equals(k)) {
                    rest.put(String.valueOf(k), v);
                }
            });
            Map<String, Object> masked = new LinkedHashMap<>((Map<String, Object>) mask.apply(jsonForm(rest)));
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object key : map.keySet()) {
                String k = String.valueOf(key);
                out.put(k, "base64".equals(k) ? base64 : masked.get(k));
            }
            return out;
        }
        return mask.apply(jsonForm(value));
    }

    /**
     * The session's merged capability matrix (phase7-contracts.md 8.5, decision D348): the agent's, with what the
     * controller-side contributors added at {@link #open} under {@link SessionOptions#host()}; each entry's
     * {@link CapabilityMatrix#source} says where it came from.
     *
     * @throws IllegalStateException after {@link #close()}
     */
    public CapabilityMatrix capabilities() {
        requireOpen();
        return caps;
    }

    /**
     * Whether a credential is in flight (phase7-contracts.md 9.2, decision D342): true from the moment a credential
     * join resolves a secret into the session's bindings (a person's {@link #resolve} of a credential slot) until a
     * step of a {@link Origin.Kind#PERSON} origin has consumed every such reference (its compiled calls referenced the
     * slot's secret field). While it is true, {@code execute} refuses, for every origin but a person's, every step
     * whose compiled calls change the target (Phase 7 security fix, S127: a mutating, destructive or uncatalogued
     * primitive, every {@code type}, {@code press}, raw {@code swing.type} or {@code swing.key} among them); reads
     * still run.
     *
     * @throws IllegalStateException after {@link #close()}
     */
    public boolean secretsInFlight() {
        requireOpen();
        return !inFlight.isEmpty();
    }

    /**
     * Runs an {@code expectImage} step, or an anchored {@code pointer} step, through the image steps' one code path
     * (phase7-contracts.md 6.7, 6.8): the scenario player's route. The step is a compiled plan step (its calls and its
     * {@link ImageCheck}, or its guarded {@code swing.pointer} call); it is journaled as one step with its verb.
     *
     * @param step the compiled step
     * @param origin who runs it (the player passes the person's origin)
     * @throws AgentLostException if the agent is gone (the step is journaled first)
     * @throws InterruptedException if the thread is interrupted
     */
    public StepOutcome expectImage(Plan.Step step, Origin origin) throws AgentLostException, InterruptedException {
        return expectImage(step, origin, ImageSteps.Baselines.none());
    }

    /**
     * {@link #expectImage(Plan.Step, Origin)} against the baselines and anchors of the scenario's bindings file (as
     * VC built it, D359: the player reads the file it binds from and hands its entries in; without them a session
     * holds none, so an {@code expectImage} is recorded and skipped, the session being {@code live}, and an anchored
     * step is REBIND_NEEDED). The step is journaled with its verb and the arguments reconstructed from the compiled
     * step ({@link #reconstructedArgs}).
     *
     * @param baselines the scenario's baselines ({@link #baselines(Path, String, String)}), or null for none
     * @throws AgentLostException if the agent is gone (the step is journaled first)
     * @throws InterruptedException if the thread is interrupted
     */
    public StepOutcome expectImage(Plan.Step step, Origin origin, ImageSteps.Baselines baselines)
            throws AgentLostException, InterruptedException {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        String stepId = nextStepId();
        Plan.Step renamed = new Plan.Step(stepId, step.verb(), 0, step.calls(), step.expect());
        stepBaselines = baselines == null ? ImageSteps.Baselines.none() : baselines;
        try {
            return run(stepId, step.verb(), reconstructedArgs(step), renamed, origin);
        } finally {
            stepBaselines = ImageSteps.Baselines.none();
        }
    }

    /**
     * The baselines and anchors of a scenario's bindings file in this session's environment, read for
     * {@link #expectImage(Plan.Step, Origin, ImageSteps.Baselines)}: the file's {@code images} section and the PNGs
     * beside it, each derived from its label and render key inside {@code <bindingsRoot>/<envId>/}. No file gives no
     * baselines.
     *
     * @throws IOException if the file exists and cannot be read
     */
    public static ImageSteps.Baselines baselines(Path bindingsRoot, String envId, String scenarioId)
            throws IOException {
        Objects.requireNonNull(bindingsRoot, "bindingsRoot");
        Path file = BindingsFile.locate(bindingsRoot, envId, scenarioId);
        BindingsFile read = null;
        if (java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                read = BindingsFile.read(file);
            } catch (BindingsFormatException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return new ImageBaselines(scenarioId, BindingsFile.envDir(bindingsRoot, envId), read);
    }

    /**
     * The verb arguments an {@code expectImage} or {@code pointer} step was compiled from, reconstructed from its
     * compiled call and check, so that the journal and the export hold the step as written (as VC built it, D360).
     */
    static Map<String, Object> reconstructedArgs(Plan.Step step) {
        Map<String, Object> args = new LinkedHashMap<>();
        Plan.Call first = step.calls().isEmpty() ? null : step.calls().get(0);
        if ("expectImage".equals(step.verb())) {
            for (Check check : step.expect()) {
                if (check instanceof ImageCheck image) {
                    args.put("label", image.label());
                }
            }
            if (first != null) {
                if (first.args().containsKey("target")) {
                    args.put("target", first.args().get("target"));
                }
                if (first.args().containsKey("by")) {
                    args.put("by", first.args().get("by"));
                }
                Object region = first.args().get("region");
                if (region instanceof String id) {
                    args.put("region", id);
                } else if (region instanceof Map<?, ?>) {
                    args.put("clip", region);
                }
            }
            for (Check check : step.expect()) {
                if (check instanceof ImageCheck image) {
                    if (!image.masks().isEmpty()) {
                        args.put("mask", image.masks());
                    }
                    if (image.maxDiffPixels() != 0) {
                        args.put("maxDiffPixels", (long) image.maxDiffPixels());
                    }
                }
            }
            return args;
        }
        for (Plan.Call call : step.calls()) {
            if ("swing.pointer".equals(call.primitive())) {
                args.putAll(call.args());
                if (call.guard() != null) {
                    args.put("guard", call.guard().label());
                }
            }
        }
        return args;
    }

    /** The image steps of the current step, over this session (live mode, its own output directory). */
    private ImageSteps imageSteps(ImageSteps.Baselines baselines) {
        return new ImageSteps(new ImageSteps.Host() {
            @Override
            public Object read(String primitive, Map<String, Object> args) throws AgentRpcException,
                    AgentLostException, InterruptedException {
                return ExecutorSession.this.read(primitive, args);
            }

            @Override
            public void record(Map<String, Object> fields) {
                log.write("image", fields);
            }

            @Override
            public Path outputDir() {
                return options.outputDir();
            }

            @Override
            public BindMode mode() {
                return BindMode.LIVE;
            }

            @Override
            public String scenarioId() {
                return id;
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

    /**
     * Whether the acting call's provenance carries the anchor of this guard's label (phase7-contracts.md 6.5, as VC
     * built it, D361): the recorder took the anchor with the guard's own call an instant before, so the step acts and
     * the anchor goes to the export; the guard is compared from the next run on.
     */
    private boolean recordedAnchor(Plan.Guard guard) {
        return provenance != null && provenance.get("anchor") instanceof Map<?, ?> anchor
                && guard.label().equals(anchor.get("label"));
    }

    /** An anchored pointer call's guard (phase7-contracts.md 6.7), in the session: REBIND_NEEDED unless it holds. */
    private void guard(String stepId, Plan.Call call) throws Abort, AgentLostException, InterruptedException {
        Object args;
        try {
            args = callArguments(call.args());
        } catch (ResolutionException e) {
            throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, stepId, mask.apply(e.getMessage()),
                    RunLog.fields("reason", e.reason(), "reference", e.reference()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> substituted = args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        ImageRuns.raise(stepId, ImageRuns.run(stepId, () -> imageSteps(stepBaselines).guard(stepId, call.guard(),
                substituted)));
    }

    // ------------------------------------------------------------------------------------------------ gates (P7 9.2)

    /** The keys of {@code swing.key} that type nothing (phase7-contracts.md 9.2, as VC built it: D369). */
    static final Set<String> NON_PRINTABLE_KEYS = Set.of("ENTER", "TAB", "ESCAPE", "BACK_SPACE", "DELETE", "UP", "DOWN",
            "LEFT", "RIGHT", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "INSERT", "SHIFT", "CONTROL", "ALT", "META",
            "CAPS_LOCK", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14",
            "F15", "F16", "F17", "F18", "F19", "F20", "F21", "F22", "F23", "F24", "KP_UP", "KP_DOWN", "KP_LEFT",
            "KP_RIGHT", "CONTEXT_MENU", "WINDOWS", "PAUSE", "PRINTSCREEN", "SCROLL_LOCK", "NUM_LOCK");

    /** The refusal of a credential reference from an origin that is not a person's (D342). */
    static final String CREDENTIAL_REFUSAL = "a credential is never used on a model's or a client's behalf; the person"
            + " signs on first";

    /** The refusal of typing while a credential is in flight (D342). */
    static final String IN_FLIGHT_REFUSAL = "a credential is in flight; the person signs on first";

    /** The refusal of a glass capture for an origin that is not a person's (D341). */
    static final String GLASS_REFUSAL = "a capture of the glass is a person's evidence (swing.capture mode: ROBOT)";

    /**
     * Why the step's compiled calls may not be sent for this origin (phase7-contracts.md 9.2), or null: Robot without
     * {@link SessionOptions#robot()}, a glass capture for an origin that is not a person's, and for such an origin a
     * call that references a credential join's secret field or the joined slot as a whole (S101), or, while a
     * credential is in flight, a call that changes the target (S127: a primitive the catalogue lists as mutating or
     * destructive, one it does not list, and every typing call).
     */
    private String gate(Plan.Step step, Origin origin) {
        boolean person = confirms(origin);
        for (Plan.Call call : step.calls()) {
            if (Plan.robot(call) && !options.robot()) {
                return PlanExecutor.ROBOT_REFUSAL;
            }
            if (!person && "swing.capture".equals(call.primitive()) && Plan.robotValue(call.args().get("mode"))) {
                return GLASS_REFUSAL;
            }
        }
        if (person) {
            return null;
        }
        for (Plan.Call call : step.calls()) {
            if (secretReference(call.args()) != null) {
                return CREDENTIAL_REFUSAL;
            }
        }
        if (!inFlight.isEmpty()) {
            // Phase 7 security fix, S127 (D342 rule 2 widened): while the person is mid-sign-on nothing of another
            // origin changes the target, a pack verb over the application's input seam included; reads still run.
            for (Plan.Call call : step.calls()) {
                if (types(call) || changes(call)) {
                    return IN_FLIGHT_REFUSAL;
                }
            }
        }
        return null;
    }

    /** Whether a call may change the target: its primitive is mutating, destructive or not in the catalogue. */
    private boolean changes(Plan.Call call) {
        PrimitiveDescriptor descriptor = primitives.get(call.primitive());
        return descriptor == null || descriptor.mutating() || descriptor.destructive();
    }

    /** Whether a call types: {@code swing.type}, or {@code swing.key} with a key outside the non-printable set. */
    private static boolean types(Plan.Call call) {
        if ("swing.type".equals(call.primitive())) {
            return true;
        }
        if ("swing.key".equals(call.primitive())) {
            Object key = call.args().get("key");
            return !(key instanceof String name) || !NON_PRINTABLE_KEYS.contains(name.strip().toUpperCase(
                    java.util.Locale.ROOT));
        }
        return false;
    }

    /**
     * The first reference in the value that reaches a credential join's secret, or null: a secret field
     * ({@code $<slot>.<key>}) or, Phase 7 security fix S101, the joined slot as a whole ({@code $<slot>}, embedded or
     * not, which resolves to the slot's value with its joined fields: A34's rule, {@code Bindings.isSecretField}).
     * A value too deep to be read (S128) counts as one while a join exists: refused rather than searched.
     */
    private String secretReference(Object value) {
        if (joins.isEmpty() || value == null) {
            return null;
        }
        List<References.Reference> refs;
        try {
            refs = References.references(value);
        } catch (RuntimeException e) {
            return "(a value nested too deep to be read)";
        }
        for (References.Reference ref : refs) {
            if (reachesSecret(ref.slot(), ref.fields())) {
                return "$" + ref.slot() + (ref.fields().isEmpty() ? "" : "." + ref.fields().get(0));
            }
        }
        return null;
    }

    /** {@code Bindings.isSecretField}'s predicate over the session's joins: a secret field or the slot as a whole. */
    private boolean reachesSecret(String slot, List<String> fields) {
        Map<String, String> secret = joins.get(slot);
        return secret != null && (fields.isEmpty() || secret.containsKey(fields.get(0)));
    }

    /** A person's call that referenced a credential join's secret field, or the slot as a whole, consumed it (D342). */
    private void consumed(Plan.Call call) {
        if (inFlight.isEmpty()) {
            return;
        }
        try {
            for (References.Reference ref : References.references(call.args())) {
                if (reachesSecret(ref.slot(), ref.fields())) {
                    inFlight.remove(ref.slot());
                }
            }
        } catch (RuntimeException e) {
            // A call whose references cannot be read consumed nothing.
        }
    }

    /** A step refused under the credential rule (rule 1, before anything is compiled): journaled, nothing sent. */
    private StepOutcome refusedCredential(String stepId, String verb, Object givenArgs, Origin origin, String ref) {
        Plan.Step step = new Plan.Step(stepId, verb == null ? "call" : verb, 0, List.of(), List.of());
        return finish(stepId, verb, givenArgs, step, List.of(), origin, 0, Status.ERROR, FailureKind.ENVIRONMENT,
                CREDENTIAL_REFUSAL, RunLog.fields("refused", "credential"), runner.lastSeenSeq, clock.millis(),
                null);
    }

    // ------------------------------------------------------------------------------------------------ journal

    /** A note in the journal: a remark of a person, a model or the system. */
    public void note(String text, Origin origin) {
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        journal.append(new StepJournal.NoteEntry(origin, text == null ? "" : text), mask);
    }

    /**
     * Records the state of a money budget in the journal (the session service calls it; phase5-contracts.md 12.3's
     * {@code budget} record).
     *
     * @param scope the budget's scope ({@code SESSION})
     * @param spentUsd what was spent
     * @param limitUsd the limit, or null for none
     * @param exhausted whether no further model call is made
     */
    public void budget(String scope, BigDecimal spentUsd, BigDecimal limitUsd, boolean exhausted) {
        Objects.requireNonNull(scope, "scope");
        requireOpen();
        journal.append(new StepJournal.BudgetEntry(scope, spentUsd == null ? "0" : spentUsd.toPlainString(),
                limitUsd == null ? null : limitUsd.toPlainString(), exhausted), mask);
    }

    /**
     * Tells the session that the agent's events up to {@code seq} were handled outside it: a host that ran something
     * else on the same target between two steps (the MCP server's {@code skillroy_scenario_run}, a plan run with its
     * own prompt policy) passes the agent's sequence number after it, so that the next step's prompt check does not
     * read the prompts that run saw (and answered, or failed on) as unexpected prompts of this session. The prompt
     * cursor and the highest seen sequence number only move forward (a lower number changes nothing); not journaled.
     *
     * @param seq the agent's sequence number up to which prompts were handled
     * @throws IllegalStateException after {@link #close()}
     */
    public void promptsHandledUpTo(long seq) {
        requireOpen();
        runner.promptCursor = Math.max(runner.promptCursor, seq);
        runner.noteSeq(seq);
    }

    /** The session's slots, oracles and captures; what a call's references resolve against. */
    public Bindings bindings() {
        return runner.bindings;
    }

    /** The session's journal. */
    public StepJournal journal() {
        return journal;
    }

    /** The session's options. */
    public SessionOptions options() {
        return options;
    }

    /**
     * What the open's hook install did (phase6-contracts.md 5.3): the {@code hooks} record's fields, masked; a copy.
     */
    public Map<String, Object> hooks() {
        return hooksRecord == null ? Map.of() : new LinkedHashMap<>(hooksRecord);
    }

    /** The session id. */
    public String id() {
        return id;
    }

    /**
     * The pack the session runs: what a client of the session (the session service, phase5-contracts.md 12.4) offers
     * as the verbs a step may name.
     */
    public DomainPack pack() {
        return pack;
    }

    /** The target's primitive catalogue as the session read it when it opened, by name. */
    public Map<String, PrimitiveDescriptor> primitives() {
        return java.util.Collections.unmodifiableMap(primitives);
    }

    /**
     * Closes the session: with {@link SessionOptions#recoverOnClose()} and a mutating call sent (and the agent not
     * lost), the pack's {@code recover} is called first, as after a run that did not pass; then the journal's
     * {@code session-end} record, the web path, the data channels and the journal. Idempotent; never throws.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (options.recoverOnClose() && mutated && !runner.agentLost) {
                try {
                    pack.recover(agent);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception | LinkageError e) {
                    // As after a run: a failed recover never fails anything else.
                }
            }
            journal.append(new StepJournal.SessionEnd(runner.agentLost ? "agent lost" : "closed", stepCount, cost()),
                    mask);
            observer.sessionEnded(runner.agentLost ? "agent lost" : "closed");
        } finally {
            web.close();
            data.close();
            journal.close();
            log.close();
        }
    }

    /** The model cost the session's ledger recorded since it opened, a plain decimal in USD. */
    private String cost() {
        CostLedger ledger = options.bind().ledger();
        List<CostLedger.Entry> entries = ledger.entries();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = ledgerStart; i < entries.size(); i++) {
            try {
                sum = sum.add(new BigDecimal(entries.get(i).costUsd()));
            } catch (RuntimeException e) {
                // An entry without a readable cost adds nothing.
            }
        }
        return sum.toPlainString();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private String nextStepId() {
        return "s" + (++stepCount);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("session " + id + " is closed");
        }
    }

    private static void requireName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a " + what + " name must match [A-Za-z_][A-Za-z0-9_]*, found " + name);
        }
        if (ScenarioLoader.RESERVED_NAMES.contains(name)) {
            throw new IllegalArgumentException(what + " name '" + name + "' is reserved; reserved names: "
                    + new java.util.TreeSet<>(ScenarioLoader.RESERVED_NAMES));
        }
    }

    /**
     * Whether the origin may confirm a destructive call: only a person (design 3.4; phase5-contracts.md 12.2). A
     * model's request, an MCP client's (D158) and the system's is never a confirmation.
     */
    private static boolean confirms(Origin origin) {
        return origin.kind() == Origin.Kind.PERSON;
    }

    /** True when the arguments hold a key that could confirm a call: {@code confirm}, or a request's {@code _meta}. */
    private static boolean holdsConfirmation(Map<?, ?> args) {
        return args.containsKey(CallRunner.CONFIRM) || args.containsKey(CallRunner.META);
    }

    /** A copy of the arguments without {@code confirm} and {@code _meta}, in their order. */
    private static Map<String, Object> stripped(Map<?, ?> args) {
        Map<String, Object> copy = new LinkedHashMap<>();
        args.forEach((k, v) -> copy.put(String.valueOf(k), v));
        copy.remove(CallRunner.CONFIRM);
        copy.remove(CallRunner.META);
        return copy;
    }

    /** A raw call of an origin that is not a person's, without {@code confirm} and {@code _meta}. */
    private static PlannedCall withoutConfirmation(PlannedCall call) {
        if (!holdsConfirmation(call.args())) {
            return call;
        }
        return new PlannedCall(call.primitive(), stripped(call.args()), call.sync(), call.timeoutMs(), call.await(),
                call.capture(), call.literalArgs());
    }

    /**
     * The call as it is compiled: a call with literal arguments has every {@code $} of its strings escaped, so that no
     * reference is substituted in it (phase6 security fix S55); any other call as it is.
     */
    @SuppressWarnings("unchecked")
    private static PlannedCall literal(PlannedCall call) {
        if (!call.literalArgs()) {
            return call;
        }
        return new PlannedCall(call.primitive(), (Map<String, Object>) References.escaped(call.args()), call.sync(),
                call.timeoutMs(), call.await(), call.capture(), true);
    }

    /** A call a verb compiled to, for an origin that is not a person's: without {@code confirm} and {@code _meta}. */
    private static Plan.Call withoutConfirmation(Plan.Call call) {
        if (!holdsConfirmation(call.args())) {
            return call;
        }
        return new Plan.Call(call.primitive(), stripped(call.args()), call.sync(), call.timeoutMs(), call.await(),
                call.handlesPrompt(), call.capture());
    }

    /**
     * Verb arguments of an origin that is not a person's, without {@code confirm} and {@code _meta}: at the top, and in
     * a nested {@code args} (the base verb {@code call}'s form), so that neither the step nor its export confirms
     * anything. A {@code _meta} deeper down is left for the compiler, which refuses the step.
     */
    private static Object withoutConfirmation(Object args) {
        if (!(args instanceof Map<?, ?> map)) {
            return args;
        }
        Map<String, Object> copy = stripped(map);
        if (copy.get("args") instanceof Map<?, ?> nested && holdsConfirmation(nested)) {
            copy.put("args", stripped(nested));
        }
        return copy;
    }

    private Event maskEvent(Event event) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) mask.apply(jsonForm(event.payload()));
        return new Event(event.seq(), event.ts(), event.topic(), event.source(), event.correlationId(),
                event.traceparent(), payload);
    }

    private RpcResult maskResult(RpcResult result) {
        List<Event> events = new ArrayList<>();
        for (Event event : result.events()) {
            events.add(maskEvent(event));
        }
        return new RpcResult(mask.apply(jsonForm(result.value())), events, result.seqBefore(), result.seqAfter(),
                result.durationMs(), result.thread());
    }

    private static Map<String, Object> mask(SecretMask mask, Map<String, Object> detail) {
        @SuppressWarnings("unchecked")
        Map<String, Object> masked = detail == null ? null : (Map<String, Object>) mask.apply(jsonForm(detail));
        return masked;
    }

    /** A value in the JSON codec's form (what the mask and the journal take); null stays null. */
    private static Object jsonForm(Object value) {
        try {
            return value == null ? null : RunLog.jsonForm(value, 0);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(Map<String, Object> value) {
        Object form = jsonForm(value);
        return form instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The connection fact providers read through: a {@code web.*} read goes to the web path, the rest to the agent. */
    private final class FactConnection implements AgentConnection {

        @Override
        public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException,
                AgentLostException {
            return call(method, params, CallOptions.defaults());
        }

        @Override
        public RpcResult call(String method, Map<String, Object> params, CallOptions callOptions)
                throws AgentRpcException, AgentLostException {
            if (!WebRun.isWeb(method)) {
                return agent.call(method, params, callOptions);
            }
            try {
                return web.call("fact", method, params == null ? Map.of() : params, callOptions == null
                        ? CallOptions.defaults() : callOptions, true, runner.lastSeenSeq, new String[1]).result();
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
        public List<PrimitiveDescriptor> schema() throws AgentLostException {
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

        /** The session's connection is the caller's: never closed here. */
        @Override
        public void close() {
        }
    }

    /** What a web path may know about the session: its output directory; its records are not kept. */
    private static final class WebContext implements WebRunContext {

        private final Path dir;

        WebContext(Path dir) {
            this.dir = dir;
        }

        @Override
        public Path artifactsDir() {
            return dir;
        }

        @Override
        public void record(Map<String, Object> record) {
            // A session keeps its journal only.
        }
    }
}
