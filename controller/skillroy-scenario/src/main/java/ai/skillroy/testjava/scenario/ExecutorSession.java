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
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    /** The resolved slots as a compiled step sees them (a verb may ask for a slot's kind or credential). */
    private final Map<String, Scenario.Slot> declaredSlots = new LinkedHashMap<>();
    private final Map<String, Scenario.OracleEntry> declaredOracles = new LinkedHashMap<>();
    private int stepCount;
    private boolean mutated;
    private boolean closed;
    /** The session's observer, wrapped (phase6-contracts.md 10.2). */
    private final RunObserver observer;
    /** Whether the current step's {@code sessionStepStarted} was sent. */
    private boolean stepObserved;

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
        for (FactProvider fact : packFacts == null ? List.<FactProvider>of() : packFacts) {
            facts.putIfAbsent(fact.root(), fact);
            webRoot |= "web".equals(fact.root());
            hooksRoot |= "hooks".equals(fact.root());
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
            Files.createDirectories(dir);
            Files.write(dir.resolve(PlanExecutor.CAPABILITIES_FILE), (Json.writePretty(mask.apply(caps.toJson()))
                    + "\n").getBytes(StandardCharsets.UTF_8));
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
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
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
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(origin, "origin");
        requireOpen();
        String stepId = nextStepId();
        Object given = confirms(origin) ? args : withoutConfirmation(args);
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
        long seqBefore = runner.lastSeenSeq;
        observeStepStart(stepId, verb, origin);
        runner.currentStep = stepId;
        runner.stepFidelity = new ArrayList<>();
        runner.stepEvents = new ArrayList<>();
        runner.lastResult = null;
        Map<String, Object> capturedBefore = new LinkedHashMap<>(runner.bindings.captured());
        int executed = 0;
        int position = 0;
        Abort failure = null;
        try {
            for (position = 0; position < step.calls().size(); position++) {
                Plan.Call call = step.calls().get(position);
                PrimitiveDescriptor descriptor = primitives.get(call.primitive());
                mutated |= descriptor == null || descriptor.mutating() || descriptor.destructive();
                runner.execute(step, position, call, id + "/" + stepId + "/" + (position + 1), () -> false);
                executed = position + 1;
            }
            if (!step.expect().isEmpty()) {
                expectNow(step.expect(), stepId);
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
            failure = new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "internal error: " + e,
                    RunLog.fields("exception", e.toString()));
        } finally {
            runner.currentStep = null;
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
        journal.append(new StepJournal.StepEntry(stepId, origin, verb, jsonForm(givenArgs), callRecords,
                outcomeJson, snapshotHash), mask);
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
                args = runner.bindings.substitute(call.args());
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

    /** The expectations of an {@code expect} step, evaluated once: the first that does not hold fails the step. */
    private void expectNow(List<Check> checks, String stepId) throws Abort, AgentLostException,
            InterruptedException {
        for (Check check : checks) {
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
     * @param credential the slot's credential declaration, or null
     * @throws BindException RESOLUTION_FAILED or ENVIRONMENT, as a bind reports them
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
        credentialRefs.remove(slot);
        if (resolution.credential() != null) {
            credentialRefs.put(slot, resolution.credential());
            if (secret != null) {
                joins.put(slot, secret);
                mask = mask.plus(secret.values());
                log.mask(mask);
            }
        }
        declaredSlots.remove(slot);
        declaredSlots.put(slot, new Scenario.Slot(slot, "query", null, 0, kind, substituted, tokens, verifiers,
                credential));
        rebuildBindings();
        journal.append(new StepJournal.ResolveEntry(slot, kind, jsonMap(given), tokens, verifiers, credential,
                BindingsFile.resolutionJson(resolution)), mask);
        return resolution;
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
        Object snapshot = read("swing.snapshot", Map.of());
        Object appState = read("app.state", Map.of());
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

    private Object read(String primitive, Map<String, Object> params) throws AgentLostException {
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
