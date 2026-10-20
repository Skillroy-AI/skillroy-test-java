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
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.CallWarning;
import ai.skillroy.testjava.protocol.CompositeResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.InputResult;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.RungResult;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.Rung;
import ai.skillroy.testjava.scenario.pack.PromptDecision;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.observe.AwaitRecord;
import ai.skillroy.testjava.scenario.observe.CallEnd;
import ai.skillroy.testjava.scenario.observe.CallStart;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.web.AgentWebPath;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The executor's step protocol for one call (phase5-contracts.md 12.1, decision D79): the one implementation that a
 * plan run ({@link PlanExecutor}) and an interactive session ({@link ExecutorSession}) share. For a call it
 * substitutes the references against the current {@link #bindings}, announces the secret parameters
 * ({@code _meta.secretParams}, amendment A34), sends a top-level {@code confirm} argument as the call's confirmation
 * ({@code _meta.confirm}, phase5-contracts.md 13.1), drops a parameter named {@code _meta} (the request's metadata
 * comes from the call's options alone, 12.2), routes a {@code web.*} call to the chosen web path, applies the
 * call's sync, binds a captured slot, checks the base verbs' fidelity, looks for unexpected prompts and classifies
 * every failure as an {@link Abort}; it writes the {@code call}, {@code sync}, {@code capture}, {@code fidelity} and
 * {@code prompt} records of {@code run.jsonl}. From Phase 6 (phase6-contracts.md 10.2) it also tells its owner's
 * {@link #observer} where each call starts and ends (its sync and prompt check included) and every wait it did, and
 * sends the observer's open call span as the call's {@code _meta.traceparent}.
 *
 * <p>From Phase 8 (phase8-contracts.md 7.7, decision D592) a successful call's result is checked in a fixed order
 * after its {@code call} record: the secret-echo check (a result never carries a secret parameter's value, 7.5), the
 * warnings (7.1, with the derived {@code INPUT_QUEUED} of 7.2), the composite shape (7.5), the rung checks of a ladder
 * call (7.6), the fidelity note (de-duplicated with the ladder's), the base verbs' checks, the sync (correlated by
 * {@code inputId} under {@link SyncPolicy#ackByInputId()}, 7.4), the capture, and the prompt check, which expects a
 * prompt a composite lists by {@code seq} inside the call's window. The first failure wins; every record of an
 * earlier stage is written first. It writes the {@code warning}, {@code rung} records too.
 *
 * <p>Its fields are the protocol's state, which its owner reads and sets between calls: the sequence numbers every
 * wait starts from, the prompt cursor, whether the agent was lost and whether a secret parameter was sent. Not
 * thread-safe: one runner serves one run or one session on one thread.
 */
final class CallRunner {

    static final long MAX_CALL_TIMEOUT_MS = 120_000L;
    static final long DIAGNOSTIC_TIMEOUT_MS = 5_000L;
    private static final long PROMPT_QUERY_LIMIT = 200L;

    /** The argument that confirms a destructive call: sent as {@code _meta.confirm}, never as a parameter. */
    static final String CONFIRM = "confirm";

    /**
     * The request's metadata, which the connection builds from the call's {@link CallOptions} alone: never a parameter
     * (phase5-contracts.md 12.2, the fix wave), so a parameter of this name is dropped before a call is sent.
     */
    static final String META = "_meta";
    /** The arguments that name Robot ({@code via} of a pointer, {@code mode} of a capture). */
    private static final List<String> ROBOT_ARGUMENTS = List.of("via", "mode");
    /** The refusal of a reference that resolves to ROBOT in {@code via} or {@code mode} (D441). */
    static final String ROBOT_REFERENCE_REFUSAL = "robot: via and mode take a literal value; a reference that resolves"
            + " to ROBOT is refused";

    final AgentConnection agent;
    final RunLog log;
    final Clock clock;
    final WebRun web;
    final long stepTimeoutMs;
    /** The slots whose values the owner's mask collected by A25's key rule; asked once, on first use. */
    private final Supplier<Set<String>> keyRuleSlotSource;
    private Set<String> keyRuleSlots;

    /** The pack's sync policy (set by the owner before the first call). */
    SyncPolicy sync = new SyncPolicy(null, null, null, null, null);
    /** The prompt policy that decides on an unexpected prompt. */
    PromptPolicy promptPolicy = PromptPolicy.FAIL;
    /** The pack's own warning codes (phase8-contracts.md 7.3), which a call may accept; set by the owner. */
    Set<String> warningCodes = Set.of();
    /**
     * The run mask's {@code minLengthInText} (amendment A25, B19): the secret-echo check matches a secret inside a
     * longer text from this length on, and by equality only below it (phase8-contracts.md 7.5); set by the owner.
     */
    int minLengthInText = SecretMask.MIN_SUBSTRING;
    /** Every warning a step call reported, as one line each, in order (phase8-contracts.md 7.1). */
    final List<String> warnings = new ArrayList<>();
    /** The same, for the current step only; the owner replaces the list when a step starts. */
    List<String> stepWarnings = new ArrayList<>();
    /** The pack's verbs: a base UI or web verb a pack overrides gets no base-verb checks. */
    Set<String> packVerbs = Set.of();
    /** The slot values call arguments resolve against; a capture replaces them. */
    Bindings bindings;
    /**
     * True while the owner runs a step whose call arguments resolve against the public values only, never a joined
     * credential field (Phase 7 security fix, S101: a session step of any origin but a person's); false for a plan
     * run and a person's step.
     */
    boolean publicArguments;
    /** The step being run, for messages and the web path's records; null outside a step. */
    String currentStep;
    /** True once the agent was lost: nothing more is sent. */
    boolean agentLost;
    /** True once a call announced a secret parameter (A34). */
    boolean secretsSent;
    /** The agent's sequence number before the last call that was not a wait, a settle or a mark. */
    long lastActionSeq;
    /** The web path's sequence number before that call. */
    long lastActionPathSeq;
    /** The highest agent sequence number seen. */
    long lastSeenSeq;
    /** The last agent event the prompt check has read. */
    long promptCursor;
    /** Every call that ran at a lower fidelity than requested, in order. */
    final List<String> degradedFidelity = new ArrayList<>();
    /** The same, for the current step only; the owner replaces the list when a step starts. */
    List<String> stepFidelity = new ArrayList<>();
    /** The result of the last call that returned one, or null. */
    RpcResult lastResult;
    /** When not null, the events every call's result carried are added here (a session step's outcome). */
    List<Event> stepEvents;
    /**
     * The owner's observer, already wrapped so that it never throws (phase6-contracts.md 10.2); set by the owner
     * before the first call. {@link RunObserver#NONE} builds no record.
     */
    RunObserver observer = RunObserver.NONE;
    /** The observed call in flight: whether one is open, its result, the path that served it and its error code. */
    private boolean callOpen;
    private RpcResult callResult;
    private String callPath;
    private String callError;
    private long callRttMs;

    /**
     * @param keyRuleSlots the slots whose values the owner's mask collected by A25's key rule (asked once)
     */
    CallRunner(AgentConnection agent, RunLog log, Clock clock, WebRun web, long stepTimeoutMs,
               Supplier<Set<String>> keyRuleSlots) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.log = Objects.requireNonNull(log, "log");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.web = Objects.requireNonNull(web, "web");
        this.stepTimeoutMs = stepTimeoutMs;
        this.keyRuleSlotSource = Objects.requireNonNull(keyRuleSlots, "keyRuleSlots");
    }

    /**
     * Phase 5 (phase5-contracts.md 13.1): a top-level {@code confirm} argument is the call's confirmation of a
     * destructive primitive. It is removed from the parameters and the call is confirmed ({@code _meta.confirm}) when
     * it is the literal {@code true}; a call without the key is sent as before.
     *
     * @param params the substituted parameters (modified)
     * @return whether the call is confirmed
     */
    static boolean confirmation(Map<String, Object> params) {
        if (params == null || !params.containsKey(CONFIRM)) {
            return false;
        }
        return Boolean.TRUE.equals(params.remove(CONFIRM));
    }

    /**
     * Opens the call's {@code call} span (phase6-contracts.md 10.4) and returns the options with its trace parent;
     * the options as given with {@link RunObserver#NONE}.
     */
    private CallOptions startObservedCall(String stepId, int index, String primitive, CallOptions callOptions,
                                          List<String> secretParams) {
        callOpen = false;
        callResult = null;
        callPath = null;
        callError = null;
        callRttMs = -1L;
        if (observer == RunObserver.NONE) {
            return callOptions;
        }
        callOpen = true;
        observer.callStarted(new CallStart(stepId, index, primitive, callOptions.correlationId(), secretParams,
                callOptions.confirm()));
        return callOptions.withTraceparent(observer.currentTraceparent());
    }

    /** Ends the open {@code call} span with what the call returned, however it ended. */
    private void endObservedCall() {
        if (callOpen) {
            callOpen = false;
            observer.callEnded(callEnd(callResult, callPath, callError, callRttMs));
        }
    }

    /**
     * The facts of a call's reply for its span: never its value or the events' payloads. The agent's own duration and
     * thread only for a reply the agent made (not one the controller assembled from a combined wait).
     *
     * @param result the reply, or null when the call failed
     * @param path the path that served it, or null
     * @param errorCode the error code's name of a failed call, or null
     * @param rttMs the call's own round trip, -1 when unknown
     */
    static CallEnd callEnd(RpcResult result, String path, String errorCode, long rttMs) {
        if (result == null) {
            return new CallEnd(false, path, errorCode, -1L, -1L, rttMs, null, null, List.of());
        }
        List<CallEnd.EventFact> events = new ArrayList<>(result.events().size());
        for (Event event : result.events()) {
            events.add(new CallEnd.EventFact(event.topic(), event.seq(), event.source(), event.ts()));
        }
        boolean byAgent = AgentWebPath.ID.equals(path) && !"controller".equals(result.thread());
        return new CallEnd(true, path, null, result.seqBefore(), result.seqAfter(), rttMs,
                byAgent ? result.durationMs() : null, byAgent ? result.thread() : null, events);
    }

    /** The slots of A25's key rule, asked of the owner once. */
    private Set<String> keyRuleSlots() {
        if (keyRuleSlots == null) {
            keyRuleSlots = keyRuleSlotSource.get();
        }
        return keyRuleSlots;
    }

    /**
     * Runs call {@code callIndex} of {@code step} with the step protocol (see the class description). Never returns
     * for a failed call: the failure is an {@link Abort} with its status, kind and detail.
     *
     * @param correlationId the call's correlation id ({@code <scenarioId>/<stepId>/<n>} in a run)
     * @param nextStepHandlesPrompt whether the next step with calls handles prompts (a prompt it answers is
     *     expected); asked only when a prompt arrives
     * @throws Abort when the call, its sync, its capture or the prompt check fails
     * @throws AgentLostException when the agent is lost
     */
    void execute(Plan.Step step, int callIndex, Plan.Call call, String correlationId,
                 BooleanSupplier nextStepHandlesPrompt) throws Abort, AgentLostException {
        try {
            executeCall(step, callIndex, call, correlationId, nextStepHandlesPrompt);
        } finally {
            endObservedCall();
        }
    }

    /** {@link #execute}'s body: the call's {@code call} span, when observed, is ended by {@link #execute}. */
    private void executeCall(Plan.Step step, int callIndex, Plan.Call call, String correlationId,
                             BooleanSupplier nextStepHandlesPrompt) throws Abort, AgentLostException {
        String stepId = step.id();
        boolean baseUi = BaseSteps.UI_VERBS.contains(step.verb()) && !packVerbs.contains(step.verb());
        boolean baseWeb = BaseSteps.WEB_VERBS.contains(step.verb()) && !packVerbs.contains(step.verb());
        Map<String, Object> params;
        AwaitSpec await;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> substituted = (Map<String, Object>) (publicArguments
                    ? bindings.substitutePublic(call.args()) : bindings.substitute(call.args()));
            params = substituted;
            await = AwaitRules.resolve(call.await(), bindings, stepTimeoutMs);
        } catch (ResolutionException e) {
            throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, stepId, e.getMessage(),
                    RunLog.fields("reason", e.reason(), "reference", e.reference(), "primitive",
                            call.primitive()));
        }
        // phase7-contracts.md 9.1, the fix wave's note (FB, D441): every Robot gate reads the call's arguments as
        // written, before this substitution, so via and mode take a literal value; a reference that resolves to ROBOT
        // in any spelling would pass them all, and is refused here, on every route, before anything is sent.
        for (String key : ROBOT_ARGUMENTS) {
            if (Plan.robotValue(params.get(key)) && !Plan.robotValue(call.args().get(key))) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, ROBOT_REFERENCE_REFUSAL,
                        RunLog.fields("refused", "robot", "argument", key, "primitive", call.primitive()));
            }
        }
        // For every caller (plan steps, cleanup calls, sessions and so the MCP server): a plan read from its file or a
        // pack's call may hold a _meta parameter, which a connection that merged it could take as a confirmation.
        params.remove(META);
        boolean isAwait = "sync.await".equals(call.primitive());
        if (isAwait && !params.containsKey("since")) {
            params.put("since", lastActionSeq);
        }
        List<String> secretParams = secretParams(call);
        secretsSent |= !secretParams.isEmpty();
        boolean confirm = confirmation(params);
        CallOptions callOptions = options(call.timeoutMs(), correlationId).withSecretParams(secretParams);
        if (confirm) {
            callOptions = callOptions.withConfirm(true);
        }
        callOptions = startObservedCall(stepId, callIndex + 1, call.primitive(), callOptions, secretParams);
        boolean webCall = WebRun.isWeb(call.primitive());
        String[] servedBy = new String[1];
        long t0 = clock.millis();
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
                        : Math.max(lastSeenSeq, waited.event().seq()), clock.millis() - t0,
                        "controller");
            } else {
                pathSince = web.pathSeqNow();
                result = agent.call(call.primitive(), params, callOptions);
                agentSince = result.seqBefore();
            }
        } catch (AgentRpcException e) {
            callError = ErrorCodes.name(e.error().code());
            callPath = webCall && servedBy[0] != null ? servedBy[0] : AgentWebPath.ID;
            Map<String, Object> record = RunLog.fields("step", stepId, "index", callIndex + 1, "primitive",
                    call.primitive());
            if (webCall) {
                record.put("path", servedBy[0]);
                web.noteCall(stepId, callIndex + 1, call.primitive(), servedBy[0], false);
            }
            record.putAll(RunLog.fields("params", params, "ok", false, "error", e.error().toJson(), "durationMs",
                    clock.millis() - t0));
            if (!secretParams.isEmpty()) {
                record.put("secretParams", secretParams);
            }
            if (confirm) {
                record.put("confirm", true);
            }
            log.write("call", record);
            throw rpcFailure(e, stepId, call.primitive(), params);
        } catch (WebRun.PathFailure e) {
            callError = "PATH_FAILURE";
            callPath = servedBy[0];
            Map<String, Object> record = RunLog.fields("step", stepId, "index", callIndex + 1, "primitive",
                    call.primitive(), "path", servedBy[0], "params", params, "ok", false, "error", e.getMessage(),
                    "durationMs", clock.millis() - t0);
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
        callResult = result;
        callPath = webCall ? servedBy[0] : fromPath ? web.pathId() : AgentWebPath.ID;
        callRttMs = clock.millis() - t0;
        Map<String, Object> record = RunLog.fields("step", stepId, "index", callIndex + 1, "primitive",
                call.primitive());
        if (webCall) {
            record.put("path", servedBy[0]);
        }
        record.putAll(RunLog.fields("params", params, "ok", true, "value", result.value(), "events",
                result.events().size(), "seq", seq(result), "durationMs", clock.millis() - t0,
                "thread", result.thread()));
        if (fromPath) {
            record.put("eventSource", web.pathId());
        }
        if (!secretParams.isEmpty()) {
            record.put("secretParams", secretParams);
        }
        if (confirm) {
            record.put("confirm", true);
        }
        log.write("call", record);
        lastResult = result;
        if (stepEvents != null) {
            stepEvents.addAll(result.events());
        }
        if (webCall) {
            web.noteCall(stepId, callIndex + 1, call.primitive(), servedBy[0], true);
        }
        boolean isSettle = "swing.settle".equals(call.primitive());
        if (!isAwait && !isSettle && !"trace.mark".equals(call.primitive())) {
            lastActionSeq = agentSince;
            lastActionPathSeq = pathSince;
        }
        // phase8-contracts.md 7.7 (D592): the result checks in their fixed order, the first failure winning.
        checkSecretEcho(call, params, secretParams, result, stepId);
        checkWarnings(step, callIndex, call, result, await);
        checkComposite(call, params, secretParams, result.value(), stepId);
        boolean ladderRecorded = checkRungs(step, callIndex, call, params, result.value());
        if (!ladderRecorded) {
            noteFidelity(step, call, result.value());
        }
        if (baseUi) {
            checkFired(step, call, result.value());
        }
        if ((baseUi || baseWeb) && isSettle) {
            checkSettled(step, call, params, result.value());
        }
        if (baseWeb && "web.waitFlag".equals(call.primitive())) {
            checkWaitFlag(step, call, params, result.value());
        }
        Event ended = applySync(call, await, agentSince, pathSince, stepId, result.value());
        if (call.capture() != null) {
            capture(step, call, isAwait ? awaitedEvent(result.value()) : ended);
        }
        List<Long> answered = agentServed && !fromPath ? CompositeResult.answeredSeqs(result.value()) : List.of();
        checkPrompts(step, callIndex, nextStepHandlesPrompt, handledUpTo(call, result, agentSince, fromPath),
                new Answered(call.primitive(), answered, result.seqBefore(), result.seqAfter()));
    }

    // ---------------------------------------------------------------- result checks (phase8-contracts.md 7)

    /** The most scalars and the deepest nesting the secret-echo walk reads (phase8-contracts.md 7.5). */
    static final int ECHO_MAX_SCALARS = 10_000;
    static final int ECHO_MAX_DEPTH = 32;

    /**
     * phase8-contracts.md 7.5 (D585): a call with secret parameters must not return one of their values. The walk
     * reads the result's value and its warnings' messages and data: a scalar equal to a secret's value, or a string
     * containing one at least {@link #minLengthInText} long, fails the step ERROR / ENVIRONMENT; the detail names the
     * parameter, never the value. Bounded: at most {@link #ECHO_MAX_SCALARS} scalars and depth
     * {@link #ECHO_MAX_DEPTH}, beyond which the check fails closed.
     */
    private void checkSecretEcho(Plan.Call call, Map<String, Object> params, List<String> secretParams,
                                 RpcResult result, String stepId) throws Abort {
        if (secretParams.isEmpty()) {
            return;
        }
        Map<String, Set<String>> secrets = new LinkedHashMap<>();
        for (String name : secretParams) {
            Set<String> values = new java.util.LinkedHashSet<>();
            secretLeaves(params.get(name), values, 0);
            if (!values.isEmpty()) {
                secrets.put(name, values);
            }
        }
        if (secrets.isEmpty()) {
            return;
        }
        EchoWalk walk = new EchoWalk(secrets, minLengthInText);
        walk.visit(result.value(), 0);
        for (CallWarning warning : result.warnings()) {
            walk.visit(warning.message(), 1);
            walk.visit(warning.data(), 1);
        }
        if (walk.tooLarge) {
            throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, call.primitive() + " returned a result too"
                    + " large to check for secrets (more than " + ECHO_MAX_SCALARS + " scalars or deeper than "
                    + ECHO_MAX_DEPTH + ")", RunLog.fields("primitive", call.primitive(), "parameters",
                    secretParams));
        }
        if (walk.echoed != null) {
            throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, call.primitive() + " returned the value of"
                    + " its secret parameter '" + walk.echoed + "'", RunLog.fields("primitive", call.primitive(),
                    // Not "secretParam": a secret-looking key would have the run mask hide the parameter's name.
                    "parameter", walk.echoed));
        }
    }

    /** The texts of a secret parameter's value: a string's, a number's, every such leaf below a structure. */
    private static void secretLeaves(Object value, Set<String> out, int depth) {
        if (value == null || value instanceof Boolean || depth > ECHO_MAX_DEPTH) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                secretLeaves(v, out, depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object v : list) {
                secretLeaves(v, out, depth + 1);
            }
        } else {
            String text = Values.stringOf(value);
            if (text != null && !text.isEmpty()) {
                out.add(text);
            }
        }
    }

    /** One bounded walk of the secret-echo check. */
    private static final class EchoWalk {
        private final Map<String, Set<String>> secrets;
        private final int minLengthInText;
        private int scalars;
        boolean tooLarge;
        String echoed;

        EchoWalk(Map<String, Set<String>> secrets, int minLengthInText) {
            this.secrets = secrets;
            this.minLengthInText = minLengthInText;
        }

        void visit(Object value, int depth) {
            if (echoed != null || tooLarge || value == null || value instanceof Boolean) {
                return;
            }
            if (depth > ECHO_MAX_DEPTH) {
                tooLarge = true;
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Object v : map.values()) {
                    visit(v, depth + 1);
                }
                return;
            }
            if (value instanceof List<?> list) {
                for (Object v : list) {
                    visit(v, depth + 1);
                }
                return;
            }
            if (++scalars > ECHO_MAX_SCALARS) {
                tooLarge = true;
                return;
            }
            String text = Values.stringOf(value);
            if (text == null) {
                return;
            }
            for (Map.Entry<String, Set<String>> e : secrets.entrySet()) {
                for (String secret : e.getValue()) {
                    if (text.equals(secret) || (value instanceof String && secret.length() >= minLengthInText
                            && text.contains(secret))) {
                        echoed = e.getKey();
                        return;
                    }
                }
            }
        }
    }

    /**
     * phase8-contracts.md 7.1, 7.2 (D577, D578, D580): the call's warnings, the agent's plus the derived
     * {@code INPUT_QUEUED}, each recorded ({@code warning}) and listed ({@link #warnings}, {@link #stepWarnings});
     * then, when one is not accepted, the step fails FAIL / ASSERTION naming the first.
     */
    private void checkWarnings(Plan.Step step, int callIndex, Plan.Call call, RpcResult result, AwaitSpec await)
            throws Abort {
        List<Sourced> all = warningsOf(call, result, await);
        if (all.isEmpty()) {
            return;
        }
        List<String> unaccepted = new ArrayList<>();
        List<Object> json = new ArrayList<>();
        Sourced first = null;
        for (Sourced sourced : all) {
            CallWarning warning = sourced.warning();
            boolean accepted = accepted(call, warning.code());
            writeWarning(step.id(), callIndex + 1, call.primitive(), warning, accepted, sourced.source(), false);
            String line = step.id() + ": " + step.verb() + ": " + call.primitive() + " " + warning.code()
                    + (warning.message() == null ? "" : " (" + warning.message() + ")");
            line = Values.printableLine(line);
            warnings.add(line);
            stepWarnings.add(line);
            json.add(warning.toJson());
            if (!accepted) {
                unaccepted.add(warning.code());
                if (first == null) {
                    first = sourced;
                }
            }
        }
        if (first == null) {
            return;
        }
        CallWarning warning = first.warning();
        String code = warning.code();
        String text = warning.message() == null ? "" : ": " + warning.message();
        String message;
        if (CallWarning.NEVER_ACCEPTABLE.contains(code)) {
            message = step.verb() + ": " + call.primitive() + " reported " + code + text + " (never acceptable)";
        } else if (!acceptable(code)) {
            message = step.verb() + ": " + call.primitive() + " reported the undeclared warning " + code + text;
        } else {
            message = step.verb() + ": " + call.primitive() + " reported " + code + text + " (a step accepts it with"
                    + " accept: [" + code + "])";
        }
        throw new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), Values.printableLine(message),
                RunLog.fields("primitive", call.primitive(), "warnings", json, "unaccepted", unaccepted));
    }

    /**
     * A cleanup call's warnings (phase8-contracts.md 7.1): recorded with {@code step: null, cleanup: true}, never
     * changing the verdict and never in the result lists.
     */
    void noteCleanupWarnings(int index, Plan.Call call, RpcResult result) {
        for (Sourced sourced : warningsOf(call, result, call.await())) {
            writeWarning(null, index, call.primitive(), sourced.warning(), accepted(call, sourced.warning().code()),
                    sourced.source(), true);
        }
    }

    /** A warning with where it came from: the agent's channel or the executor's derivation. */
    private record Sourced(CallWarning warning, String source) {
    }

    private List<Sourced> warningsOf(Plan.Call call, RpcResult result, AwaitSpec await) {
        List<Sourced> out = new ArrayList<>();
        for (CallWarning warning : result.warnings()) {
            out.add(new Sourced(warning, "agent"));
        }
        if (InputResult.queued(result.value()) && !correlated(call, await)) {
            String id = InputResult.inputId(result.value());
            out.add(new Sourced(new CallWarning(CallWarning.INPUT_QUEUED, "input " + (id == null ? "(no inputId)"
                    : id) + " was queued and nothing waits for its acknowledgement", id == null ? null
                    : RunLog.fields(InputResult.INPUT_ID, id)), "executor"));
        }
        return out;
    }

    /**
     * phase8-contracts.md 7.2: the call's sync waits for that input's own acknowledgement: {@code ACK} or
     * {@code READY} under {@link SyncPolicy#ackByInputId()} (with an ack topic), or {@code EVENT} whose resolved match
     * has the key {@code inputId}.
     */
    private boolean correlated(Plan.Call call, AwaitSpec await) {
        return switch (call.sync()) {
            case ACK, READY -> sync.ackByInputId() && sync.ackTopic() != null;
            case EVENT -> await != null && await.match() != null && await.match().containsKey(InputResult.INPUT_ID);
            default -> false;
        };
    }

    private void writeWarning(String stepId, int index, String primitive, CallWarning warning, boolean accepted,
                              String source, boolean cleanup) {
        Map<String, Object> record = RunLog.fields("step", stepId, "index", index, "primitive", primitive, "code",
                warning.code());
        if (warning.message() != null) {
            record.put("message", warning.message());
        }
        if (warning.data() != null) {
            record.put("data", warning.data());
        }
        record.put("accepted", accepted);
        record.put("source", source);
        if (cleanup) {
            record.put("cleanup", true);
        }
        log.write("warning", record);
    }

    /** True when the call accepts the code and the code can be accepted at all. */
    private boolean accepted(Plan.Call call, String code) {
        return call.accept().contains(code) && acceptable(code);
    }

    /** A base code but the two never-acceptable ones, or a code the pack declares. */
    private boolean acceptable(String code) {
        return (CallWarning.BASE_CODES.contains(code) && !CallWarning.NEVER_ACCEPTABLE.contains(code))
                || warningCodes.contains(code);
    }

    /**
     * phase8-contracts.md 7.5 (D584): a result with a {@code composite} key follows the convention
     * ({@link CompositeResult#problems}); a violation is ERROR / ENVIRONMENT naming it.
     */
    private void checkComposite(Plan.Call call, Map<String, Object> params, List<String> secretParams, Object value,
                                String stepId) throws Abort {
        List<String> problems = CompositeResult.problems(value, params.keySet(), secretParams);
        if (!problems.isEmpty()) {
            throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, call.primitive() + " returned a composite"
                    + " result that breaks the convention: " + problems.get(0), RunLog.fields("primitive",
                    call.primitive(), "problems", problems));
        }
    }

    /**
     * phase8-contracts.md 7.6 (D588, D589): a ladder call's reported rung is checked against its ladder: missing,
     * undeclared, below {@code minRung}, or a forwarded {@code minRung} without its echo, is FAIL / ASSERTION; a rung
     * below the first is one {@code fidelity} record {@code DEGRADED} and a {@link #degradedFidelity} line. Every
     * ladder call writes one {@code rung} record, before a failure is thrown.
     *
     * @return true when the ladder check recorded the call's fidelity ({@link #noteFidelity} then skips it)
     */
    private boolean checkRungs(Plan.Step step, int callIndex, Plan.Call call, Map<String, Object> params,
                               Object value) throws Abort {
        List<Rung> ladder = call.rungs();
        if (ladder.isEmpty()) {
            return false;
        }
        List<String> names = ladder.stream().map(Rung::name).toList();
        String rung = RungResult.rung(value);
        int at = rung == null ? -1 : names.indexOf(rung);
        String forwarded = forwardedMinRung(params);
        String echo = RungResult.minRung(value);
        String failure = null;
        String where = step.verb() + ": " + call.primitive();
        if (rung == null) {
            failure = where + " reported no rung; its ladder is " + names;
        } else if (at < 0) {
            failure = where + " reported rung " + Values.printableLine(rung) + ", not one of " + names;
        } else if (call.minRung() != null && at > names.indexOf(call.minRung())) {
            failure = where + " acted on rung " + rung + " below the required " + call.minRung();
        } else if (forwarded != null && !forwarded.equals(echo)) {
            failure = where + " did not apply minRung " + forwarded;
        }
        Map<String, Object> record = RunLog.fields("step", step.id(), "index", callIndex + 1, "primitive",
                call.primitive(), "rungs", names);
        if (call.minRung() != null) {
            record.put("minRung", call.minRung());
        }
        record.putAll(RunLog.fields("rung", rung, "fidelity", at == 0 ? RungResult.FULL : RungResult.DEGRADED,
                "ok", failure == null));
        log.write("rung", record);
        if (failure != null) {
            throw new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), failure, RunLog.fields("primitive",
                    call.primitive(), "rungs", names, "minRung", call.minRung(), "value", value));
        }
        if (at == 0) {
            return false;
        }
        Object reason = value instanceof Map<?, ?> v && v.get(RungResult.REASON) != null ? v.get(RungResult.REASON)
                : "a lower rung of the ladder";
        String entry = Values.printableLine(step.id() + ": " + step.verb() + " fell back from " + names.get(0) + " to "
                + rung + " (" + reason + ")");
        degradedFidelity.add(entry);
        stepFidelity.add(entry);
        log.write("fidelity", RunLog.fields("step", step.id(), "primitive", call.primitive(), "fidelity",
                "DEGRADED", "requestedRung", names.get(0), "rung", rung, "reason", reason));
        return true;
    }

    /**
     * A cleanup call's ladder (phase8-contracts.md 7.6): its {@code rung} record only, never a verdict or a fidelity
     * line.
     */
    void noteCleanupRung(int index, Plan.Call call, Object value) {
        if (call.rungs().isEmpty()) {
            return;
        }
        List<String> names = call.rungs().stream().map(Rung::name).toList();
        String rung = RungResult.rung(value);
        int at = rung == null ? -1 : names.indexOf(rung);
        log.write("rung", RunLog.fields("step", null, "index", index, "primitive", call.primitive(), "rungs", names,
                "rung", rung, "fidelity", at == 0 ? RungResult.FULL : RungResult.DEGRADED, "ok", at >= 0,
                "cleanup", true));
    }

    /**
     * The {@code minRung} the call forwards to its primitive: a top-level parameter, or one inside the {@code args} of
     * a {@code device.act}-shaped call; null when it forwards none.
     */
    private static String forwardedMinRung(Map<String, Object> params) {
        if (params.get(RungResult.MIN_RUNG) instanceof String rung) {
            return rung;
        }
        if (params.get("args") instanceof Map<?, ?> args && args.get(RungResult.MIN_RUNG) instanceof String rung) {
            return rung;
        }
        return null;
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
                    clock.millis() - t0)));
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
    List<String> secretParams(Plan.Call call) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Object> e : call.args().entrySet()) {
            if (META.equals(e.getKey())) {
                continue;   // never sent (see execute)
            }
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
     * ({@code ready} not true) within the primitive's wait is SYNC_TIMEOUT, as an expired settle is. The result
     * is the detail's {@code waitFlag}; its {@code lastError} (the last poll's error, either path: A35) also ends
     * the message.
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
        String lastError = value instanceof Map<?, ?> v && v.get("lastError") instanceof String error
                ? Values.printableLine(error) : null;
        throw new Abort(Status.FAIL, FailureKind.SYNC_TIMEOUT, step.id(), step.verb() + ": the flag did not turn"
                + " true within " + ms + " ms: " + shortened(expression) + (lastError == null ? ""
                : " (last error: " + shortened(lastError) + ")"), detail);
    }

    /** At most 200 characters of a line for a failure message: longer ones end in "...". */
    private static String shortened(String line) {
        return line.length() <= 200 ? line : line.substring(0, 197) + "...";
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
     * @param value the call's result value, from which an acknowledgement correlated by input reads the
     *     {@code inputId} (phase8-contracts.md 7.4); null for a call that returned none
     * @return the event that ended an EVENT wait, else null
     */
    Event applySync(Plan.Call call, AwaitSpec await, long since, long pathSince, String stepId, Object value)
            throws Abort, AgentLostException {
        switch (call.sync()) {
            case NONE -> {
            }
            case ACK -> {
                if (sync.ackTopic() != null) {
                    awaitTopic("ack", sync.ackTopic(), ackMatch(call, value, stepId), since, pathSince,
                            timeout(sync.ackTimeout(), call), call, stepId);
                } else {
                    log.write("sync", RunLog.fields("step", stepId, "primitive", call.primitive(), "mode", "ACK",
                            "ok", true, "note", "the pack has no ack topic"));
                }
            }
            case READY -> {
                if (sync.ackTopic() != null) {
                    awaitTopic("ack", sync.ackTopic(), ackMatch(call, value, stepId), since, pathSince,
                            timeout(sync.ackTimeout(), call), call, stepId);
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

    /**
     * phase8-contracts.md 7.4 (D582): the ack wait's match. Null (any acknowledgement since {@code seq.before}, as
     * before Phase 8) unless the pack's acknowledgements are correlated by input; then {@code {inputId: <the
     * result's>}}, and a result without one is ERROR / ENVIRONMENT, nothing awaited.
     */
    private Map<String, Object> ackMatch(Plan.Call call, Object value, String stepId) throws Abort {
        if (!sync.ackByInputId()) {
            return null;
        }
        String id = InputResult.inputId(value);
        if (id == null) {
            throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "the pack's acknowledgements are correlated"
                    + " by inputId; " + call.primitive() + " returned none", RunLog.fields("primitive",
                    call.primitive(), "sync", call.sync().name(), "ackTopic", sync.ackTopic()));
        }
        return RunLog.fields(InputResult.INPUT_ID, id);
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
        long t0 = clock.millis();
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
            observeAwait(call.sync().name(), false, null, null, clock.millis() - t0, null);
            detail.put("error", e.toString());
            throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "waiting for " + describe(wait,
                    topics, match) + " after " + call.primitive() + " failed: " + e.getMessage(), detail);
        }
        record.putAll(RunLog.fields("ok", event != null, "eventSeq", event == null ? null : event.seq(), "topic",
                event == null ? null : event.topic(), "ms", clock.millis() - t0));
        if (combined && event != null) {
            record.put("source", fromPath ? web.pathId() : AgentWebPath.ID);
        }
        log.write("sync", record);
        observeAwait(call.sync().name(), event != null, event == null ? null : event.seq(), event == null ? null
                : event.topic(), clock.millis() - t0, event == null ? null : fromPath ? web.pathId()
                : AgentWebPath.ID);
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

    /** A wait of the open call for the observer (phase6-contracts.md 10.4's {@code await} span). */
    private void observeAwait(String mode, boolean ok, Long eventSeq, String topic, long ms, String source) {
        if (observer != RunObserver.NONE) {
            observer.await(new AwaitRecord(mode, ok, eventSeq, topic, ms, source));
        }
    }

    /** "ready event (a,b)", or for an EVENT wait "event (a,b)" plus " matching {…}" when it has a match. */
    private String describe(String wait, String topics, Map<String, Object> match) {
        return ("event".equals(wait) ? "" : wait + " ") + "event (" + topics + ")"
                + (match == null ? "" : " matching " + Values.safeWrite(match));
    }

    private void settle(Plan.Call call, String stepId) throws Abort, AgentLostException {
        long timeoutMs = call.timeoutMs();
        Map<String, Object> params = RunLog.fields("timeoutMs", timeoutMs);
        long t0 = clock.millis();
        RpcResult result;
        try {
            result = agent.call("swing.settle", params, options(timeoutMs + 2000, null));
        } catch (AgentRpcException e) {
            log.write("sync", RunLog.fields("step", stepId, "primitive", call.primitive(), "mode", "SETTLE",
                    "ok", false, "error", e.error().toJson()));
            observeAwait("SETTLE", false, null, null, clock.millis() - t0, null);
            throw rpcFailure(e, stepId, "swing.settle", params);
        }
        noteSeq(result.seqAfter());
        boolean settled = result.value() instanceof Map<?, ?> v && Boolean.TRUE.equals(v.get("settled"));
        log.write("sync", RunLog.fields("step", stepId, "primitive", call.primitive(), "mode", "SETTLE",
                "timeoutMs", timeoutMs, "ok", settled, "value", result.value(), "ms",
                clock.millis() - t0));
        observeAwait("SETTLE", settled, null, null, clock.millis() - t0, null);
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

    /**
     * The prompts a composite answered (phase8-contracts.md 7.5, D586): the {@code seq}s its result lists and the
     * call's window {@code (seqBefore, seqAfter]}; a listed prompt event inside the window is expected.
     */
    private record Answered(String primitive, List<Long> seqs, long seqBefore, long seqAfter) {

        boolean covers(long seq) {
            return seq > seqBefore && seq <= seqAfter && seqs.contains(seq);
        }

        List<Long> outside() {
            return seqs.stream().filter(seq -> seq <= seqBefore || seq > seqAfter).toList();
        }
    }

    private void checkPrompts(Plan.Step step, int callIndex, BooleanSupplier nextStepHandlesPrompt, long handledUpTo,
                              Answered answered) throws Abort, AgentLostException {
        List<String> topics = sync.promptTopics();
        if (topics.isEmpty()) {
            return;
        }
        String stepId = step.id();
        List<Long> ignored = answered.outside();
        if (!ignored.isEmpty()) {
            // A composite cannot vouch for a prompt outside its own call's window.
            log.write("prompt", RunLog.fields("step", stepId, "primitive", answered.primitive(), "ignoredSeqs",
                    ignored));
        }
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
                if (answered.covers(event.seq())) {
                    log.write("prompt", RunLog.fields("step", stepId, "event", event.toJson(), "expected", true,
                            "answeredBy", answered.primitive()));
                    continue;
                }
                boolean expected = event.seq() <= handledUpTo
                        || laterCallHandlesPrompt(step, callIndex) || nextStepHandlesPrompt.getAsBoolean();
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

    private static boolean laterCallHandlesPrompt(Plan.Step step, int callIndex) {
        List<Plan.Call> calls = step.calls();
        for (int j = callIndex + 1; j < calls.size(); j++) {
            if (calls.get(j).handlesPrompt()) {
                return true;
            }
        }
        return false;
    }

    /** Per-call options: the timeout clamped to 1 ms .. 120 s, and the correlation id (null: the client's). */
    static CallOptions options(long timeoutMs, String correlationId) {
        long capped = Math.min(Math.max(1L, timeoutMs), MAX_CALL_TIMEOUT_MS);
        return CallOptions.defaults().withCorrelationId(correlationId).withTimeoutMs(capped);
    }

    /** Raises {@link #lastSeenSeq} to {@code seq}. */
    void noteSeq(long seq) {
        lastSeenSeq = Math.max(lastSeenSeq, seq);
    }

    /** {@code {before, after}} of a result, for a {@code call} record. */
    static Map<String, Object> seq(RpcResult result) {
        return RunLog.fields("before", result.seqBefore(), "after", result.seqAfter());
    }

    /** The detail of a failed call: primitive, code, error name, message and data. */
    static Map<String, Object> errorDetail(String primitive, AgentRpcException e) {
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
}
