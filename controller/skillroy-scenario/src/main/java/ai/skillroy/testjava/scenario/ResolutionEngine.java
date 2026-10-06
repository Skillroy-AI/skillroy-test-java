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
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.Codec;
import ai.skillroy.testjava.scenario.resolve.CodecException;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialIndex;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.FingerprintContext;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.RecordCodec;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Resolves queries one at a time outside a plan (phase2-contracts.md 6.6): the hook for {@code live} and, later,
 * interactive use (decision B15). The {@link Binder} is built on it.
 *
 * <p>What it guarantees, whoever calls it:
 * <ul>
 *   <li>a resolver returns candidates; only a candidate every verifier of the slot passes becomes a
 *       {@link Resolution} (decision D3). Resolvers are asked in catalogue order, restricted to the slot's strategy;
 *       when no candidate of one resolver passes, the next is asked (decision D25); the first candidate every
 *       verifier passes wins. Candidates without the slot's credential are dropped before the cap of 20;</li>
 *   <li>resolvers, verifiers and fingerprinters see a read-only view of the agent (a mutating or destructive
 *       primitive is refused, {@code await} is unsupported) and a recording data channel; every agent and data
 *       timeout is clamped to what is left of the budget, measured on {@code BindOptions.clock} (a call or statement
 *       without a timeout gets its primitive's or source's default, clamped), and the budget is checked before each
 *       pack call. There is no watchdog thread;</li>
 *   <li>everything a pack call throws is classified (section 10): a resolver's {@link ResolutionException} is
 *       RESOLUTION_FAILED with its reason ({@code RESOLVER} for the two-argument form); a {@link DataException}, an
 *       unhandled JSON-RPC error, a runtime exception, a null list, candidate, value or result, or a mutating call
 *       is ENVIRONMENT; a spent budget is ENVIRONMENT ("binding timed out in slot …");</li>
 *   <li>a value's secret fields never reach it: a resolver sees only whether an entry exists, values are encoded
 *       without secret fields, and the credential join happens in {@link #bindings()};</li>
 *   <li>an oracle's cross-check ({@link #crossCheck(String)}, phase3-contracts.md 5.1, 6.1) gets the same read-only
 *       view and data channel as a verifier, its call is bounded the same way, and what it throws or a null result
 *       is ENVIRONMENT ("pack defect: cross-check … failed: …"); a disagreement is a result, never an
 *       exception. What its records and outcomes hold (the check's actual and evidence, its reason, the oracle's
 *       value, a skipped check's capability reason) is JSON-like and masked before anything sees it.</li>
 * </ul>
 *
 * <p>Not thread-safe: one engine serves one bind on one thread. {@link #close()} closes the data channels the engine
 * opened itself (not the agent).
 */
public final class ResolutionEngine implements AutoCloseable {

    /** How many candidates the engine hands to the verifiers, per resolver. */
    static final int MAX_CANDIDATES = 20;
    /** How many rejected candidates are recorded. */
    static final int MAX_REJECTED = 20;
    private static final int SAMPLE_ROWS = 3;
    private static final long MAX_CALL_MS = 120_000L;
    /** The default of a statement without a timeout when nothing better is known (the agent's data.query default). */
    private static final long DEFAULT_DATA_TIMEOUT_MS = 10_000L;

    private final DomainPack pack;
    private final AgentConnection agent;
    private final BindOptions options;
    private final BindListener listener;
    private final DataChannel data;
    private final boolean ownsData;
    private final Clock clock;
    private final SecretMask mask;
    private final List<RowMask> rowMasks;
    private long deadline;
    private final long budgetMs;
    private ResolutionCatalogue catalogue;
    private View view;
    private String currentSlot;

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, String> strategies = new LinkedHashMap<>();
    private final Map<String, Resolution> resolutions = new LinkedHashMap<>();
    private final Map<String, OracleValue> oracles = new LinkedHashMap<>();
    private final Map<String, CredentialRef> credentialRefs = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> joins = new LinkedHashMap<>();
    /** The substituted arguments of each oracle bound by name: what its cross-check decodes its input from. */
    private final Map<String, Object> oracleArgs = new LinkedHashMap<>();
    private final Map<String, CrossCheckOutcome> crossChecks = new LinkedHashMap<>();
    private EnvFingerprint fingerprint;

    /**
     * @param pack the running pack (its catalogue is read once, on first use)
     * @param agent the target's agent; resolvers get a read-only view of it; never closed by the engine
     * @param options mode, environment, credentials, ledger and clock
     * @param budget the bind budget ({@code timeouts.bind})
     * @param listener receives the run.jsonl records of the bind; null ignores them
     */
    public ResolutionEngine(DomainPack pack, AgentConnection agent, BindOptions options, Duration budget,
                            BindListener listener) {
        this(pack, agent, options, budget, listener, null);
    }

    /** As the public constructor, reading through {@code data} when not null (the executor's channels, not owned). */
    ResolutionEngine(DomainPack pack, AgentConnection agent, BindOptions options, Duration budget,
                     BindListener listener, DataChannel data) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.options = options == null ? BindOptions.defaults() : options;
        this.listener = listener == null ? BindListener.NONE : listener;
        this.clock = this.options.clock();
        this.budgetMs = budget == null ? Plan.DEFAULT_BIND_TIMEOUT_MS : Math.max(1L, budget.toMillis());
        this.deadline = clock.millis() + budgetMs;
        this.mask = this.options.credentials().mask();
        List<RowMask> masks;
        try {
            masks = pack.rowMasks();
        } catch (RuntimeException e) {
            masks = List.of();
        }
        this.rowMasks = masks == null ? List.of() : List.copyOf(masks);
        if (data != null) {
            this.data = data;
            this.ownsData = false;
        } else {
            this.data = DataChannels.of(agent, this.options.credentials(), this.rowMasks);
            this.ownsData = true;
        }
    }

    // ------------------------------------------------------------------------------------------- public entries

    /**
     * Resolves one query and keeps the result under {@code slot}, with the credential join, for later calls and for
     * {@link #bindings()}.
     *
     * @param args the query's arguments, references already resolved (JSON-like)
     * @param strategy strategy tokens ({@code sql}, {@code java}); empty = not restricted
     * @param verify verifier ids; empty = the pack's verifiers for the kind
     * @param credential the slot's credential declaration, or null
     * @throws BindException RESOLUTION_FAILED ({@code NO_CANDIDATE}, {@code ALL_REJECTED}, {@code ARGS},
     *     {@code TRUNCATED}, {@code RESOLVER} or a resolver's reason) or ENVIRONMENT
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Resolution resolve(String slot, String kind, Map<String, Object> args, List<String> strategy,
                              List<String> verify, CredentialSpec credential)
            throws BindException, AgentLostException, InterruptedException {
        Resolution resolution = resolveSlot(slot, kind, args, strategy, verify, credential);
        join(slot);
        return resolution;
    }

    /**
     * Evaluates one oracle: decodes the arguments onto its input type, computes, encodes the output.
     *
     * @throws BindException ENVIRONMENT for an unknown function or an exception from {@code compute};
     *     RESOLUTION_FAILED reason {@code ARGS} for arguments that do not decode, or in {@code replay} (where they
     *     come from the bindings file, section 10) REBIND_NEEDED reason {@code UNREADABLE}
     */
    public Object evaluate(String fn, Object args) throws BindException {
        Oracle<?, ?> oracle = catalogue().oracle(fn);
        String where = currentSlot;
        if (oracle == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, where, "the plan names oracle function " + fn
                    + ", which pack " + pack.id() + " " + pack.version() + " does not have; recompile", null);
        }
        Object input;
        try {
            input = oracleInput(oracle, args);
        } catch (CodecException e) {
            if (options.mode() == BindMode.REPLAY) {
                throw new BindException(FailureKind.REBIND_NEEDED, "UNREADABLE", where, "the recorded values oracle "
                        + fn + " reads no longer fit its input: " + e.getMessage(), detail("message", e.getMessage()));
            }
            throw new BindException(FailureKind.RESOLUTION_FAILED, "ARGS", where, "the arguments of oracle " + fn
                    + " do not fit its input: " + e.getMessage(), detail("message", e.getMessage()));
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, where, "pack defect: oracle " + fn
                    + " cannot read its input type: " + e, null);
        }
        Object output;
        try {
            @SuppressWarnings("unchecked")
            Oracle<Object, Object> typed = (Oracle<Object, Object>) oracle;
            output = typed.compute(input);
        } catch (RuntimeException | StackOverflowError e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, where, "pack defect: oracle " + fn + " failed: "
                    + e, null);
        }
        try {
            return RecordCodec.DEFAULT.encode(output);
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, where, "pack defect: the output of oracle " + fn
                    + " cannot be encoded: " + e.getMessage(), null);
        }
    }

    /**
     * Evaluates one oracle as {@link #evaluate(String, Object)} does and keeps its value under {@code name}, with its
     * cross-check, for {@link #crossCheck(String)}, {@code $oracle.<name>} and {@link #bindings()}, as
     * {@link #resolve} keeps a slot (phase3-contracts.md 5.3): the public way to name an oracle in this engine.
     * Evaluating a name again replaces what was kept under it, and drops its earlier cross-check outcome. The check
     * itself is looked up in the pack's catalogue, and runs, only when {@link #crossCheck(String)} is called.
     *
     * @param name the oracle's name, as in {@code $oracle.<name>}
     * @param fn the pack's oracle function
     * @param args the oracle's arguments, references already resolved (JSON-like)
     * @param crossCheck the oracle's cross-check, or null when it has none
     * @return the encoded output, as {@link #evaluate(String, Object)} returns it
     * @throws BindException as {@link #evaluate(String, Object)}; RESOLUTION_FAILED reason {@code ARGS} also for
     *     arguments that cannot be written as JSON
     */
    public Object evaluate(String name, String fn, Object args, Plan.CrossCheckSpec crossCheck)
            throws BindException {
        Objects.requireNonNull(name, "name");
        String node = "oracle." + name;
        currentSlot = node;
        String inputsHash;
        Object kept;
        try {
            inputsHash = hash(args);
            kept = Plan.freeze(args);
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, "ARGS", node, "the arguments of oracle " + fn
                    + " cannot be written as JSON: " + e.getMessage(), detail("message", e.getMessage()));
        }
        Object value = evaluate(fn, kept);
        crossChecks.remove(name);
        bindOracle(new OracleValue(name, fn, inputsHash, value, null, crossCheck), kept);
        return value;
    }

    /**
     * Runs the cross-check of an oracle evaluated under {@code oracleName} in this engine on the values resolved so
     * far (phase3-contracts.md 5.3): the hook for interactive use, as {@link #evaluate} is; the binder's step 8 is
     * built on it (6.1). The oracle's input is decoded from its arguments and its output from its encoded value; the
     * check gets the plan's tolerance when it accepts one (else null) and a read-only, budget-bound view of the
     * target. The call is recorded as a {@code crosscheck} record and kept for {@link #bindings()}; what is returned
     * is what was recorded: the check's result with its actual and evidence in the form the JSON codec writes (a set
     * or an array as a list, a record or any other object as its text) and the credentials masked in its reason,
     * actual and evidence; the record's {@code expected}, the oracle's value, is masked too. A disagreement is
     * returned, never thrown: the binder turns it into ORACLE_SUSPECT.
     *
     * @throws IllegalArgumentException when no oracle of that name was kept in this engine (by the binder, or by
     *     {@link #evaluate(String, String, Object, Plan.CrossCheckSpec)}; the two-argument {@code evaluate} keeps
     *     nothing by name), or it has no cross-check
     * @throws BindException ENVIRONMENT when the running pack has no such check for the oracle's function (or the
     *     check takes no tolerance and the plan gives one), when the check throws or returns null, when its actual or
     *     evidence cannot be written as JSON (NaN, an infinity, nesting deeper than 200), or when the bind budget is
     *     spent
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public CrossCheckResult crossCheck(String oracleName) throws BindException, AgentLostException,
            InterruptedException {
        OracleValue value = oracles.get(oracleName);
        if (value == null) {
            throw new IllegalArgumentException("no oracle '" + oracleName + "' was computed in this engine");
        }
        Plan.CrossCheckSpec spec = value.crossCheck();
        if (spec == null) {
            throw new IllegalArgumentException("oracle '" + oracleName + "' has no cross-check");
        }
        String node = "oracle." + oracleName;
        currentSlot = node;
        ensureView();
        CrossCheck<?, ?> check = crossCheckOf(node, value.fn(), spec);
        Oracle<?, ?> oracle = catalogue().oracle(value.fn());
        if (oracle == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, node, "the plan names oracle function "
                    + value.fn() + ", which pack " + pack.id() + " " + pack.version() + " does not have; recompile",
                    null);
        }
        Object input;
        Object output;
        try {
            input = oracleInput(oracle, oracleArgs.get(oracleName));
            output = RecordCodec.DEFAULT.decode(oracle.outputType(), value.value());
        } catch (CodecException | RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, node, "pack defect: cross-check " + check.id()
                    + " failed: the input or output of oracle " + value.fn() + " does not decode: " + e.getMessage(),
                    null);
        }
        BigDecimal tolerance = spec.tolerance() == null ? null : new BigDecimal(spec.tolerance());
        Recorder recorder = new Recorder("cross-check " + check.id());
        VerifyContext ctx = verifyContext(recorder);
        @SuppressWarnings("unchecked")
        CrossCheck<Object, Object> typed = (CrossCheck<Object, Object>) check;
        CrossCheckResult result = packCall(node, recorder, () -> typed.check(input, output, tolerance, ctx));
        if (result == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, node, "pack defect: cross-check " + check.id()
                    + " failed: it returned null", null);
        }
        // 8.2: what is recorded, kept and returned holds JSON-like values only, masked, whatever the pack put into
        // its result (the mask would pass a set, an array or a record unseen, and a secret holding a quote or a
        // backslash is escaped, so unseen, in a written text); the oracle's value as well.
        Object actual;
        Map<String, Object> evidence;
        try {
            actual = maskedJson(result.actual());
            @SuppressWarnings("unchecked")
            Map<String, Object> json = (Map<String, Object>) maskedJson(result.evidence());
            evidence = json;
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, node, "pack defect: cross-check " + check.id()
                    + " failed: its actual or evidence cannot be written as JSON: " + e.getMessage(), null);
        }
        CrossCheckResult masked = new CrossCheckResult(result.agreed(), mask.apply(result.reason()), actual,
                evidence);
        CrossCheckOutcome outcome = new CrossCheckOutcome(oracleName, value.fn(), spec, mask.apply(value.value()),
                masked, null, null);
        crossChecks.put(oracleName, outcome);
        listener.event("crosscheck", outcome.toJson());
        return masked;
    }

    /**
     * The environment fingerprint v1 (phase2-contracts.md 8.2): {@code skillroy.fingerprint}, the agent protocol's
     * major version and the pack's components. Computed once per engine.
     *
     * @throws BindException ENVIRONMENT when it cannot be computed (an error, a key without the pack prefix, a
     *     truncated statement)
     */
    public EnvFingerprint fingerprint() throws BindException, AgentLostException, InterruptedException {
        if (fingerprint != null) {
            return fingerprint;
        }
        ensureView();
        Map<String, String> components = new TreeMap<>();
        components.put("skillroy.fingerprint", String.valueOf(EnvFingerprint.VERSION));
        Map<String, Object> ping = agent.ping();
        Object protocol = ping == null ? null : ping.get("protocol");
        String major = protocol == null ? "?" : String.valueOf(protocol);
        components.put("skillroy.protocolMajor", major.contains(".") ? major.substring(0, major.indexOf('.'))
                : major);
        EnvFingerprinter fingerprinter;
        try {
            fingerprinter = pack.fingerprinter();
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, null, "pack defect: fingerprinter() failed: " + e,
                    null);
        }
        if (fingerprinter != null) {
            Recorder recorder = new Recorder("fingerprinter");
            FingerprintContext ctx = new FingerprintContext() {
                @Override
                public AgentConnection agent() {
                    return view.as(recorder);
                }

                @Override
                public DataChannel data() {
                    return new RecordingData(recorder);
                }

                @Override
                public String envId() {
                    return options.envId();
                }

                @Override
                public long remainingMs() {
                    return ResolutionEngine.this.remainingMs();
                }
            };
            Map<String, String> pc = packCall("fingerprint", recorder, () -> fingerprinter.components(ctx));
            if (pc == null) {
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "pack defect: the fingerprinter"
                        + " returned null", null);
            }
            String prefix = pack.id() + ".";
            for (Map.Entry<String, String> e : pc.entrySet()) {
                if (e.getKey() == null || !e.getKey().startsWith(prefix) || e.getKey().length() == prefix.length()) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, null, "the fingerprint component '"
                            + e.getKey() + "' does not start with the pack prefix " + prefix, null);
                }
                if (e.getValue() == null) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, null, "the fingerprint component '"
                            + e.getKey() + "' has no value", null);
                }
                components.put(e.getKey(), e.getValue());
            }
            for (Provenance.Statement statement : recorder.statements) {
                if (statement.truncated()) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, null, "the fingerprint could not be"
                            + " computed: a statement on source " + statement.source() + " was truncated at "
                            + DataChannel.MAX_ROWS + " rows", detail("sql", statement.sql()));
                }
            }
        }
        fingerprint = new EnvFingerprint(EnvFingerprint.VERSION, hash(components), components);
        return fingerprint;
    }

    /**
     * Everything resolved so far, joined secrets and their mask included; what {@code PlanExecutor.run(plan,
     * bindings)} takes.
     */
    public Bindings bindings() {
        Bindings bindings = current();
        for (Map.Entry<String, Map<String, String>> e : joins.entrySet()) {
            bindings = bindings.withCredential(e.getKey(), e.getValue(), credentialRefs.get(e.getKey()));
        }
        return bindings;
    }

    /** Closes the data channels the engine opened itself. Idempotent; never throws. */
    @Override
    public void close() {
        if (ownsData && data instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Best effort: a channel that cannot close holds nothing the run needs.
            }
        }
    }

    // ------------------------------------------------------------------------------------------- for the binder

    /** What is bound so far, without joined secrets: what the graph walk substitutes from (8.3). */
    Bindings current() {
        return Bindings.of(values, strategies).withBind(options.envId(), options.mode(), fingerprint, Map.of(),
                resolutions, oracles, credentialRefs, crossChecks);
    }

    ResolutionCatalogue catalogue() throws BindException {
        if (catalogue == null) {
            ResolutionCatalogue read;
            try {
                read = pack.resolution();
            } catch (RuntimeException e) {
                throw new BindException(FailureKind.ENVIRONMENT, null, null, "pack defect: the resolution catalogue"
                        + " of pack " + pack.id() + " is invalid: " + e.getMessage(), null);
            }
            catalogue = read == null ? ResolutionCatalogue.EMPTY : read;
        }
        return catalogue;
    }

    void slot(String slot) {
        currentSlot = slot;
    }

    BindOptions options() {
        return options;
    }

    DomainPack pack() {
        return pack;
    }

    long remainingMs() {
        return deadline - clock.millis();
    }

    /** Time spent outside the budget (the approver's review) is added back. */
    void pause(long ms) {
        if (ms > 0) {
            deadline += ms;
        }
    }

    void bindFixture(String slot, Object value) {
        values.put(slot, value);
        strategies.put(slot, Strategy.FIXTURE.name());
    }

    /** Keeps an oracle's value under its name, with the substituted arguments its cross-check decodes. */
    void bindOracle(OracleValue value, Object args) {
        oracles.put(value.name(), value);
        oracleArgs.put(value.name(), args);
    }

    /**
     * Records a guarded cross-check that is skipped because its capability is not OK (phase3-contracts.md 6.1 step
     * 8a): the {@code crosscheck} record with {@code skipped: true} and the outcome.
     *
     * @return the line for {@code BindOutcome.skipped}
     */
    String skipCrossCheck(String oracleName, String state, String reason) {
        OracleValue value = oracles.get(oracleName);
        // Masked as a ran check's record is: the capability's reason comes from the target, and it reaches the
        // record, BindOutcome.skipped and every output that lists the line.
        CrossCheckOutcome outcome = new CrossCheckOutcome(oracleName, value.fn(), value.crossCheck(),
                mask.apply(value.value()), null, state, mask.apply(reason));
        crossChecks.put(oracleName, outcome);
        listener.event("crosscheck", outcome.toJson());
        return outcome.skippedLine();
    }

    /** The cross-check outcomes so far, by oracle name, in evaluation order. */
    Map<String, CrossCheckOutcome> crossCheckOutcomes() {
        return Collections.unmodifiableMap(crossChecks);
    }

    /**
     * The running pack's cross-check the plan names for an oracle (8.3 step 1): ENVIRONMENT "… recompile" when the
     * pack has no check of that id for the oracle's function, or the plan gives a tolerance the check does not take.
     */
    CrossCheck<?, ?> crossCheckOf(String node, String fn, Plan.CrossCheckSpec spec) throws BindException {
        CrossCheck<?, ?> check = catalogue().crossCheck(spec.check());
        if (check == null || !fn.equals(check.oracleFn())) {
            throw new BindException(FailureKind.ENVIRONMENT, null, node, "the plan names cross-check "
                    + spec.check() + " for oracle " + fn + ", which pack " + pack.id() + " " + pack.version()
                    + " does not have; recompile", fields("check", spec.check()));
        }
        boolean accepts;
        try {
            accepts = check.acceptsTolerance();
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, node, "pack defect: cross-check " + check.id()
                    + " failed: " + e, null);
        }
        if (spec.tolerance() != null && !accepts) {
            throw new BindException(FailureKind.ENVIRONMENT, null, node, "the plan gives cross-check "
                    + spec.check() + " a tolerance, which the check of pack " + pack.id() + " " + pack.version()
                    + " does not take; recompile", fields("check", spec.check()));
        }
        return check;
    }

    /** Keeps a resolution read from a bindings file; the run reports its strategy as REPLAY. */
    void bindRecorded(Resolution resolution) {
        values.put(resolution.slot(), resolution.value());
        strategies.put(resolution.slot(), Strategy.REPLAY.name());
        resolutions.put(resolution.slot(), resolution);
        if (resolution.credential() != null) {
            credentialRefs.put(resolution.slot(), resolution.credential());
        }
    }

    /** Replaces kept resolutions (the approval state after the approver answered). */
    void replaceResolution(Resolution resolution) {
        resolutions.put(resolution.slot(), resolution);
    }

    Map<String, Resolution> resolutions() {
        return Collections.unmodifiableMap(resolutions);
    }

    Map<String, OracleValue> oracleValues() {
        return Collections.unmodifiableMap(oracles);
    }

    /**
     * Records the credential join of a slot (8.4): the entry's secret fields are added in {@link #bindings()}.
     *
     * @throws BindException ENVIRONMENT when the value has no such field, the entry is missing, or a secret field's
     *     name is already a field of the value
     */
    void joinFixture(String slot, CredentialSpec spec) throws BindException {
        String id = credentialId(slot, values.get(slot), spec);
        credentialRefs.put(slot, new CredentialRef(spec.realm(), id));
        join(slot);
    }

    void join(String slot) throws BindException {
        CredentialRef ref = credentialRefs.get(slot);
        if (ref == null) {
            return;
        }
        Map<String, String> secret = options.credentials().secrets(ref.realm(), ref.id());
        if (secret == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "slot '" + slot + "' needs the credential "
                    + ref + ", which the credentials (" + options.credentials().origin() + ") do not have",
                    detail("credential", Map.of("realm", ref.realm(), "id", ref.id())));
        }
        if (values.get(slot) instanceof Map<?, ?> value) {
            for (String field : secret.keySet()) {
                if (value.containsKey(field)) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, slot, "slot '" + slot + "': the credential"
                            + " field '" + field + "' of " + ref + " is already a field of the value", null);
                }
            }
        }
        joins.put(slot, secret);
    }

    /** The id of a value's credential entry: the text of its field {@code key}. */
    String credentialId(String slot, Object value, CredentialSpec spec) throws BindException {
        if (!(value instanceof Map<?, ?> map) || !map.containsKey(spec.key()) || map.get(spec.key()) == null
                || map.get(spec.key()) instanceof Map || map.get(spec.key()) instanceof List) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "slot '" + slot + "' declares credential"
                    + " realm " + spec.realm() + " with key '" + spec.key() + "', but its value has no such scalar"
                    + " field", null);
        }
        return Values.stringOf(map.get(spec.key()));
    }

    /**
     * Runs the slot's verifiers on a recorded value (replay, 8.3 step 6c): no resolver is called.
     *
     * @return the results, the last one failed if a verifier refused
     * @throws BindException REBIND_NEEDED {@code UNREADABLE} when the query or value no longer decodes; ENVIRONMENT
     */
    List<VerifierResult> verifyRecorded(String slot, String kind, Map<String, Object> args, Object recordedValue,
                                        List<String> verify)
            throws BindException, AgentLostException, InterruptedException {
        currentSlot = slot;
        ensureView();
        QueryType<?, ?> type = queryType(slot, kind);
        List<Verifier<?, ?>> verifiers = verifiers(slot, type, verify);
        Object query;
        Object value;
        try {
            query = type.codec().decode(type.queryClass(), args);
            value = type.codec().decode(type.valueType(), recordedValue);
        } catch (CodecException e) {
            throw new BindException(FailureKind.REBIND_NEEDED, "UNREADABLE", slot, "the recorded binding of slot '"
                    + slot + "' no longer decodes: " + e.getMessage(), detail("message", e.getMessage()));
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: the codec of kind " + kind
                    + " failed: " + e, null);
        }
        List<VerifierResult> results = new ArrayList<>();
        for (Verifier<?, ?> verifier : verifiers) {
            VerifierResult result = verify(slot, verifier, query, value);
            results.add(result);
            if (!result.passed()) {
                break;
            }
        }
        return results;
    }

    /**
     * Resolves a query slot (8.3 live step 4) without the credential join, which the binder applies after the graph
     * walk and the guards.
     */
    Resolution resolveSlot(String slot, String kind, Map<String, Object> args, List<String> strategy,
                           List<String> verify, CredentialSpec credential)
            throws BindException, AgentLostException, InterruptedException {
        currentSlot = slot;
        ensureView();
        QueryType<?, ?> type = queryType(slot, kind);
        Map<String, Object> frozenArgs = args == null ? Map.of() : args;
        String argsHash = hash(frozenArgs);
        Object query;
        try {
            query = type.codec().decode(type.queryClass(), frozenArgs);
        } catch (CodecException e) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, "ARGS", slot, "slot '" + slot + "': the"
                    + " arguments do not fit query " + kind + ": " + e.getMessage(),
                    detail("message", e.getMessage()));
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: the codec of kind " + kind
                    + " failed: " + e, null);
        }
        List<Resolver<?, ?>> resolvers = new ArrayList<>();
        for (Resolver<?, ?> resolver : catalogue().resolvers(kind)) {
            if (strategy == null || strategy.isEmpty() || strategy.contains(tokenOf(resolver))) {
                resolvers.add(resolver);
            }
        }
        if (resolvers.isEmpty()) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack " + pack.id() + " has no "
                    + (strategy == null || strategy.isEmpty() ? "" : String.join("/", strategy) + " ")
                    + "resolver for kind " + kind, null);
        }
        List<Verifier<?, ?>> verifiers = verifiers(slot, type, verify);
        List<Provenance.Rejected> rejected = new ArrayList<>();
        boolean anyCandidate = false;
        boolean truncated = false;
        int handed = 0;
        for (Resolver<?, ?> resolver : resolvers) {
            Recorder recorder = new Recorder("resolver " + resolver.id());
            Ctx ctx = new Ctx(recorder, credential);
            @SuppressWarnings("unchecked")
            Resolver<Query<Object>, Object> typed = (Resolver<Query<Object>, Object>) resolver;
            @SuppressWarnings("unchecked")
            Query<Object> typedQuery = (Query<Object>) query;
            List<ResolverCandidate<Object>> list = packCall(slot, recorder, () -> typed.candidates(typedQuery, ctx));
            listener.event("resolver", fields("slot", slot, "resolver", resolver.id(), "candidates",
                    list == null ? null : list.size()));
            if (list == null) {
                throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: resolver " + resolver.id()
                        + " returned null", null);
            }
            truncated |= recorder.truncated();
            List<ResolverCandidate<Object>> kept = new ArrayList<>();
            List<Object> keptEncoded = new ArrayList<>();
            for (ResolverCandidate<Object> candidate : list) {
                if (candidate == null || candidate.value() == null) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: resolver "
                            + resolver.id() + " returned a null " + (candidate == null ? "candidate" : "value"), null);
                }
                anyCandidate = true;
                Object encoded = encode(slot, type.codec(), candidate.value());
                if (credential != null) {
                    String id = credentialId(slot, encoded, credential);
                    if (!options.credentials().has(credential.realm(), id)) {
                        reject(rejected, new Provenance.Rejected(resolver.id(), candidate.label(), "no credential "
                                + credential.realm() + "/" + id));
                        continue;
                    }
                }
                kept.add(candidate);
                keptEncoded.add(encoded);
            }
            if (kept.size() > MAX_CANDIDATES) {
                truncated = true;
                kept = kept.subList(0, MAX_CANDIDATES);
            }
            for (int i = 0; i < kept.size(); i++) {
                ResolverCandidate<Object> candidate = kept.get(i);
                handed++;
                List<VerifierResult> results = new ArrayList<>();
                String failure = null;
                for (Verifier<?, ?> verifier : verifiers) {
                    VerifierResult result = verify(slot, verifier, query, candidate.value());
                    results.add(result);
                    if (!result.passed()) {
                        failure = result.verifierId() + ": " + (result.reason() == null ? "refused" : result.reason());
                        break;
                    }
                }
                if (failure != null) {
                    reject(rejected, new Provenance.Rejected(resolver.id(), candidate.label(), failure));
                    continue;
                }
                Object encoded = keptEncoded.get(i);
                CredentialRef ref = credential == null ? null : new CredentialRef(credential.realm(),
                        credentialId(slot, encoded, credential));
                Provenance provenance = new Provenance(resolver.id(), recorder.statements, recorder.calls,
                        recorder.notes, handed, truncated, rejected);
                Resolution resolution = new Resolution(slot, kind, frozenArgs, argsHash, encoded, resolver.strategy(),
                        provenance, results, candidate.confidence(), ref,
                        new Approval(Approval.State.NOT_REQUIRED, null), null);
                values.put(slot, resolution.value());
                strategies.put(slot, resolution.strategy().name());
                resolutions.put(slot, resolution);
                if (ref != null) {
                    credentialRefs.put(slot, ref);
                }
                listener.event("resolve", resolveRecord(resolution, null));
                return resolution;
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rejected", rejectedJson(rejected));
        if (!anyCandidate) {
            throw new BindException(FailureKind.RESOLUTION_FAILED, "NO_CANDIDATE", slot, "slot '" + slot + "': no"
                    + " candidate in this environment answers query " + kind, detail);
        }
        throw new BindException(FailureKind.RESOLUTION_FAILED, "ALL_REJECTED", slot, "slot '" + slot + "': every"
                + " candidate for query " + kind + " was rejected (" + rejected.size() + " recorded"
                + (rejected.isEmpty() ? "" : "; first: " + rejected.get(0).reason()) + ")", detail);
    }

    /** The run.jsonl {@code resolve} record of a resolution (unmasked; the log masks). */
    Map<String, Object> resolveRecord(Resolution r, Strategy recordedStrategy) {
        Map<String, Object> record = fields("slot", r.slot(), "query", r.query(), "args", r.args(), "mode",
                options.mode().label(), "strategy", recordedStrategy == null ? r.strategy().name()
                        : Strategy.REPLAY.name());
        if (recordedStrategy != null) {
            record.put("recordedStrategy", recordedStrategy.name());
        }
        Provenance p = r.provenance();
        record.put("resolver", p == null ? null : p.resolverId());
        List<Object> statements = new ArrayList<>();
        if (p != null) {
            for (Provenance.Statement s : p.statements()) {
                statements.add(fields("channel", s.channel(), "source", s.source(), "sql", s.sql(), "params",
                        s.params(), "rowCount", s.rowCount(), "truncated", s.truncated(), "rowSample",
                        s.rowSample()));
            }
        }
        record.put("statements", statements);
        List<Object> calls = new ArrayList<>();
        if (p != null) {
            for (Provenance.Call c : p.calls()) {
                calls.add(fields("primitive", c.primitive(), "params", c.params()));
            }
        }
        record.put("calls", calls);
        record.put("candidates", p == null ? 0 : p.candidates());
        record.put("rejected", p == null ? List.of() : rejectedJson(p.rejected()));
        List<Object> verified = new ArrayList<>();
        for (VerifierResult v : r.verified()) {
            verified.add(fields("id", v.verifierId(), "passed", v.passed(), "reason", v.reason(), "evidence",
                    v.evidence()));
        }
        record.put("verified", verified);
        record.put("credential", r.credential() == null ? null : fields("realm", r.credential().realm(), "id",
                r.credential().id()));
        return record;
    }

    static List<Object> rejectedJson(List<Provenance.Rejected> rejected) {
        List<Object> out = new ArrayList<>();
        for (Provenance.Rejected r : rejected) {
            out.add(fields("resolver", r.resolverId(), "label", r.label(), "reason", r.reason()));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- internals

    private static void reject(List<Provenance.Rejected> rejected, Provenance.Rejected entry) {
        if (rejected.size() < MAX_REJECTED) {
            rejected.add(entry);
        }
    }

    private static String tokenOf(Resolver<?, ?> resolver) {
        Strategy strategy = resolver.strategy();
        return strategy == null ? null : strategy.token();
    }

    private QueryType<?, ?> queryType(String slot, String kind) throws BindException {
        QueryType<?, ?> type = catalogue().query(kind);
        if (type == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "the plan names query kind " + kind
                    + ", which pack " + pack.id() + " " + pack.version() + " does not have; recompile", null);
        }
        return type;
    }

    private List<Verifier<?, ?>> verifiers(String slot, QueryType<?, ?> type, List<String> verify)
            throws BindException {
        List<Verifier<?, ?>> out = new ArrayList<>();
        if (verify == null || verify.isEmpty()) {
            out.addAll(catalogue().verifiers(type.kind()));
        } else {
            for (String id : verify) {
                Verifier<?, ?> verifier = catalogue().verifier(type.kind(), id);
                if (verifier == null) {
                    throw new BindException(FailureKind.ENVIRONMENT, null, slot, "the plan names verifier " + id
                            + " for kind " + type.kind() + ", which pack " + pack.id() + " " + pack.version()
                            + " does not have; recompile", null);
                }
                out.add(verifier);
            }
        }
        if (out.isEmpty()) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack " + pack.id() + " has no verifier"
                    + " for kind " + type.kind() + "; every query slot needs one", null);
        }
        return out;
    }

    /** What a verifier or a cross-check may use: the read-only view and the recording data channel of one call. */
    private VerifyContext verifyContext(Recorder recorder) {
        return new VerifyContext() {
            @Override
            public AgentConnection agent() {
                return view.as(recorder);
            }

            @Override
            public DataChannel data() {
                return new RecordingData(recorder);
            }

            @Override
            public String envId() {
                return options.envId();
            }

            @Override
            public long remainingMs() {
                return ResolutionEngine.this.remainingMs();
            }
        };
    }

    /**
     * A value of a cross-check's result as its records hold it (phase3-contracts.md 8.2): in the form the JSON codec
     * writes it ({@link RunLog#jsonForm}: a set or an array becomes a list, a record or any other object its text),
     * so that the mask sees every string in it, and then masked.
     *
     * @throws RuntimeException when the codec cannot write it (NaN, an infinity, nesting deeper than 200)
     */
    private Object maskedJson(Object value) {
        Object json = RunLog.jsonForm(value, 0);
        Json.write(json);
        return mask.apply(json);
    }

    private VerifierResult verify(String slot, Verifier<?, ?> verifier, Object query, Object candidate)
            throws BindException, AgentLostException, InterruptedException {
        Recorder recorder = new Recorder("verifier " + verifier.id());
        VerifyContext ctx = verifyContext(recorder);
        @SuppressWarnings("unchecked")
        Verifier<Query<Object>, Object> typed = (Verifier<Query<Object>, Object>) verifier;
        @SuppressWarnings("unchecked")
        Query<Object> typedQuery = (Query<Object>) query;
        VerifierResult result = packCall(slot, recorder, () -> typed.verify(typedQuery, candidate, ctx));
        if (result == null) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: verifier " + verifier.id()
                    + " returned null", null);
        }
        result = result.withVerifierId(verifier.id());
        listener.event("verifier", fields("slot", slot, "id", verifier.id(), "passed", result.passed(), "reason",
                result.reason()));
        return result;
    }

    private Object encode(String slot, Codec codec, Object value) throws BindException {
        try {
            Object encoded = codec.encode(value);
            if (encoded == null) {
                throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: a candidate encodes to"
                        + " null", null);
            }
            return encoded;
        } catch (RuntimeException e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: a candidate cannot be"
                    + " encoded: " + e.getMessage(), null);
        }
    }

    /** The oracle input from {@code args} by the rules of phase2-contracts.md 6.5. */
    private static Object oracleInput(Oracle<?, ?> oracle, Object args) throws CodecException {
        Type type = oracle.inputType();
        Class<?> raw = type instanceof Class<?> c ? c : type instanceof ParameterizedType p
                ? (Class<?>) p.getRawType() : null;
        if (args instanceof List<?> list && raw != null && raw != List.class) {
            if (raw.isRecord()) {
                RecordComponent[] components = raw.getRecordComponents();
                if (list.size() > components.length) {
                    throw new CodecException("expected at most " + components.length + " arguments for "
                            + raw.getSimpleName() + ", found " + list.size());
                }
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < list.size(); i++) {
                    map.put(components[i].getName(), list.get(i));
                }
                return RecordCodec.DEFAULT.decode(type, map);
            }
            if (list.size() != 1) {
                throw new CodecException("expected exactly one argument, found " + list.size());
            }
            return RecordCodec.DEFAULT.decode(type, list.get(0));
        }
        return RecordCodec.DEFAULT.decode(type, args);
    }

    /**
     * The timeout of a statement a pack issued without one (phase2-contracts.md 7.1, "the source's default"): a JDBC
     * source's {@code statementTimeout}; for a source the agent serves, the timeout of the {@code data.query}
     * descriptor from the engine's one {@code schema()} read, or 10 s when there is none. The caller clamps it to
     * what is left of the budget.
     */
    private long defaultDataTimeoutMs(String source) throws AgentLostException {
        Duration configured = data.defaultTimeout(source);
        if (configured != null && !configured.isNegative() && !configured.isZero()) {
            return configured.toMillis();
        }
        ensureView();
        PrimitiveDescriptor descriptor = view.schema.get("data.query");
        return descriptor != null && descriptor.timeoutMs() > 0 ? descriptor.timeoutMs() : DEFAULT_DATA_TIMEOUT_MS;
    }

    private void ensureView() throws AgentLostException {
        if (view == null) {
            Map<String, PrimitiveDescriptor> byName = new HashMap<>();
            List<PrimitiveDescriptor> schema = agent.schema();
            if (schema != null) {
                for (PrimitiveDescriptor descriptor : schema) {
                    byName.put(descriptor.name(), descriptor);
                }
            }
            view = new View(byName);
        }
    }

    /** One pack call: the budget is checked first, and everything it throws is classified (section 10). */
    private <R> R packCall(String slot, Recorder recorder, PackCall<R> call)
            throws BindException, AgentLostException, InterruptedException {
        if (remainingMs() <= 0) {
            throw timedOut(slot);
        }
        R result;
        try {
            result = call.run();
        } catch (AgentLostException | InterruptedException | BindException e) {
            throw e;
        } catch (BudgetSpent e) {
            throw timedOut(slot);
        } catch (ResolutionException e) {
            String reason = e.hasReason() ? e.reason() : "RESOLVER";
            throw new BindException(FailureKind.RESOLUTION_FAILED, reason, slot, "slot '" + slot + "': "
                    + recorder.who + " ended the slot (" + reason + "): " + e.getMessage(),
                    detail("message", e.getMessage()));
        } catch (DataException e) {
            if (e.kind() == DataException.Kind.TIMEOUT && budgetTimedOut(recorder)) {
                throw timedOut(slot);
            }
            Map<String, Object> d = fields("source", e.source(), "dataKind", e.kind().name(), "sqlState",
                    e.sqlState(), "errorCode", e.errorCode(), "exceptionClass", e.exceptionClass(), "rpcCode",
                    e.rpcCode());
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, recorder.who + ": a statement on source "
                    + e.source() + " failed (" + e.kind() + "): " + e.getMessage(), d);
        } catch (AgentRpcException e) {
            if (e.error().code() == ErrorCodes.TIMEOUT && budgetTimedOut(recorder)) {
                throw timedOut(slot);
            }
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, recorder.who + ": " + e.method()
                    + " answered " + ErrorCodes.name(e.error().code()) + ": " + e.error().message(),
                    fields("primitive", e.method(), "error", e.error().toJson()));
        } catch (RuntimeException | StackOverflowError e) {
            if (recorder.violation != null) {
                throw defect(slot, recorder);
            }
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: " + recorder.who
                    + " failed: " + e, fields("exception", e.toString()));
        } catch (Exception e) {
            throw new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: " + recorder.who
                    + " failed: " + e, fields("exception", e.toString()));
        }
        if (recorder.violation != null) {
            throw defect(slot, recorder);
        }
        if (remainingMs() <= 0 && recorder.spent) {
            throw timedOut(slot);
        }
        return result;
    }

    /**
     * True when a call that timed out had its timeout cut to what was left of the budget (or the budget is spent):
     * then it is the bind that timed out, not the source or the primitive.
     */
    private boolean budgetTimedOut(Recorder recorder) {
        return recorder.budgetBound || remainingMs() <= 0;
    }

    private BindException defect(String slot, Recorder recorder) {
        return new BindException(FailureKind.ENVIRONMENT, null, slot, "pack defect: " + recorder.who
                + " called the mutating primitive " + recorder.violation, fields("primitive", recorder.violation));
    }

    private BindException timedOut(String slot) {
        return new BindException(FailureKind.ENVIRONMENT, null, slot, "binding timed out"
                + (slot == null ? "" : " in slot " + slot) + " (budget " + budgetMs + " ms)",
                fields("bindTimeoutMs", budgetMs));
    }

    static String hash(Object json) {
        return ScenarioLoader.sourceHash(Json.writeCanonical(json).getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> fields(Object... keyValues) {
        return RunLog.fields(keyValues);
    }

    private static Map<String, Object> detail(String key, Object value) {
        return fields(key, value);
    }

    /** A pack call. */
    @FunctionalInterface
    private interface PackCall<R> {
        R run() throws Exception;
    }

    /** Thrown inside the context wrappers when the budget is spent; classified as "binding timed out". */
    private static final class BudgetSpent extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BudgetSpent() {
            super("the bind budget is spent", null, false, false);
        }
    }

    /** What one pack call read: statements, calls, notes, and whether it called a mutating primitive. */
    private final class Recorder {

        final String who;
        final List<Provenance.Statement> statements = new ArrayList<>();
        final List<Provenance.Call> calls = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        String violation;
        boolean spent;
        /** The last call's timeout was cut to what was left of the budget. */
        boolean budgetBound;

        Recorder(String who) {
            this.who = who;
        }

        boolean truncated() {
            for (Provenance.Statement statement : statements) {
                if (statement.truncated()) {
                    return true;
                }
            }
            return false;
        }

        long clamp(Long timeoutMs, long fallback) {
            long remaining = remainingMs();
            if (remaining <= 0) {
                spent = true;
                throw new BudgetSpent();
            }
            long wanted = timeoutMs != null ? timeoutMs : fallback > 0 ? fallback : remaining;
            budgetBound = remaining < Math.min(wanted, MAX_CALL_MS);
            return Math.max(1L, Math.min(Math.min(wanted, remaining), MAX_CALL_MS));
        }
    }

    /** The read-only agent: the schema is read once per engine; each pack call gets a recording copy. */
    private final class View {

        private final Map<String, PrimitiveDescriptor> schema;

        View(Map<String, PrimitiveDescriptor> schema) {
            this.schema = schema;
        }

        AgentConnection as(Recorder recorder) {
            return new AgentConnection() {
                @Override
                public RpcResult call(String method, Map<String, Object> params)
                        throws AgentRpcException, AgentLostException {
                    return call(method, params, CallOptions.defaults());
                }

                @Override
                public RpcResult call(String method, Map<String, Object> params, CallOptions callOptions)
                        throws AgentRpcException, AgentLostException {
                    PrimitiveDescriptor descriptor = schema.get(method);
                    if (descriptor != null && (descriptor.mutating() || descriptor.destructive())) {
                        recorder.violation = method;
                        throw new IllegalStateException("pack defect: " + recorder.who + " called the mutating"
                                + " primitive " + method);
                    }
                    CallOptions given = callOptions == null ? CallOptions.defaults() : callOptions;
                    long timeout = recorder.clamp(given.timeoutMs(), descriptor == null ? 0 : descriptor.timeoutMs());
                    recorder.calls.add(new Provenance.Call(method, mask.apply(params == null ? Map.of()
                            : new LinkedHashMap<>(params))));
                    return agent.call(method, params, given.withTimeoutMs(timeout));
                }

                @Override
                public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
                    throw new UnsupportedOperationException("a resolver, verifier or fingerprinter cannot await"
                            + " events: binding happens before the first step");
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

                @Override
                public void close() {
                    // The view does not own the connection.
                }
            };
        }
    }

    /** The data channel a pack call reads through: timeouts clamped, statements recorded with masked samples. */
    private final class RecordingData implements DataChannel {

        private final Recorder recorder;

        RecordingData(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public boolean serves(String source) throws AgentLostException {
            return serves(source, null);
        }

        /** The agent's {@code data.sources} waits at most what is left of the budget (and 5 s at most). */
        @Override
        public boolean serves(String source, Duration timeout) throws AgentLostException {
            long clamped = recorder.clamp(timeout == null ? null : Math.max(1L, timeout.toMillis()), 0);
            return data.serves(source, Duration.ofMillis(clamped));
        }

        @Override
        public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
                throws DataException, AgentLostException, InterruptedException {
            // A statement without a timeout gets its source's default, clamped (6.4, 7.1): never the whole budget.
            long wanted = timeout == null ? defaultDataTimeoutMs(source) : Math.max(1L, timeout.toMillis());
            Rows rows = data.query(source, sql, params, maxRows, Duration.ofMillis(recorder.clamp(wanted, 0)));
            List<List<Object>> sample = new ArrayList<>();
            for (int i = 0; i < rows.rowCount() && i < SAMPLE_ROWS; i++) {
                List<Object> row = new ArrayList<>();
                for (int c = 0; c < rows.columns().size(); c++) {
                    row.add(maskCell(source, rows.columns().get(c).name(), rows.get(i, c)));
                }
                @SuppressWarnings("unchecked")
                List<Object> masked = (List<Object>) mask.apply(row);
                sample.add(masked);
            }
            @SuppressWarnings("unchecked")
            List<Object> maskedParams = (List<Object>) mask.apply(params == null ? List.of()
                    : new ArrayList<>(params));
            recorder.statements.add(new Provenance.Statement(data.kind(source), source, sql, maskedParams,
                    rows.rowCount(), rows.truncated(), sample));
            return rows;
        }

        @Override
        public String kind(String source) {
            return data.kind(source);
        }

        @Override
        public Duration defaultTimeout(String source) {
            return data.defaultTimeout(source);
        }

        private Object maskCell(String source, String column, Object value) {
            Object out = value;
            for (RowMask rowMask : rowMasks) {
                try {
                    out = rowMask.mask(source, column, out);
                } catch (RuntimeException e) {
                    return SecretKeys.REDACTED;
                }
            }
            return out;
        }
    }

    /** The context of one resolver call. */
    private final class Ctx implements ResolveContext {

        private final Recorder recorder;
        private final CredentialSpec credential;

        Ctx(Recorder recorder, CredentialSpec credential) {
            this.recorder = recorder;
            this.credential = credential;
        }

        @Override
        public AgentConnection agent() {
            return view.as(recorder);
        }

        @Override
        public DataChannel data() {
            return new RecordingData(recorder);
        }

        @Override
        public CredentialSpec credential() {
            return credential;
        }

        @Override
        public CredentialIndex credentials() {
            return options.credentials()::has;
        }

        @Override
        public String envId() {
            return options.envId();
        }

        @Override
        public BindMode mode() {
            return options.mode();
        }

        @Override
        public int maxCandidates() {
            return MAX_CANDIDATES;
        }

        @Override
        public long remainingMs() {
            return ResolutionEngine.this.remainingMs();
        }

        @Override
        public CostLedger ledger() {
            return options.ledger();
        }

        @Override
        public void note(String text) {
            if (text != null && recorder.notes.size() < 100) {
                recorder.notes.add(mask.apply(text));
            }
        }

        @Override
        public Rows requireComplete(Rows rows) throws ResolutionException {
            if (rows != null && rows.truncated()) {
                throw new ResolutionException("TRUNCATED", null, "a statement whose result the resolver needs whole"
                        + " was cut at " + DataChannel.MAX_ROWS + " rows");
            }
            return rows;
        }
    }
}
