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

package ai.skillroy.testjava.telemetry;

import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.observe.AwaitRecord;
import ai.skillroy.testjava.scenario.observe.CallEnd;
import ai.skillroy.testjava.scenario.observe.CallStart;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.ScenarioStart;
import ai.skillroy.testjava.scenario.observe.SessionStart;
import ai.skillroy.testjava.scenario.observe.StepStart;
import ai.skillroy.testjava.scenario.observe.ToolCallRecord;
import ai.skillroy.testjava.scenario.observe.TurnRecord;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The {@link RunObserver} of {@code skillroy-telemetry} (phase6-contracts.md 10.4, decision D142): turns what runs,
 * sessions and the MCP server report into spans on an OpenTelemetry {@link SdkTracerProvider}, whatever its exporter
 * (OTLP/HTTP, the {@code skillroy-spans/1} file, or the tests' in-memory exporter).
 *
 * <p>One trace per CLI invocation or JUnit stream: the root span {@code skillroy <command>} (opened by
 * {@link #runStarted}, or by the first report), {@code scenario <id>} below it (or below an open {@code tools/call}),
 * then {@code bind} (opened by the first bind record, ended by the executor's {@code bind} record), {@code step <id>},
 * {@code call <primitive>} with its reconstructed {@code agent <primitive>} (centred in the call's round trip, since
 * the agent's start is not on the wire) and its {@code await} spans, and {@code chat <model>} for each model call
 * (below {@code bind} when it served a bind slot), placed after the fact from the ledger's time and elapsed time. A
 * session is {@code session <id>}, {@code turn <n>} and its {@code step <id>}s; an MCP {@code tools/call <tool>} continues
 * the client's trace when it sent a valid {@code traceparent}. Attributes and events are {@link TelemetryAttributes}'
 * allow-list only, built from identifiers, enumerations, counts, durations, hashes and masked one-line messages that
 * the reports already carry; nothing of a call's parameters or values, an event's payload, a resolved value, SQL, a
 * row or a prompt is ever read here.
 *
 * <p>Thread-safe: every method is synchronised (the MCP server reports from its transport's threads, one call at a
 * time). Never throws for a report it cannot place (a step without a scenario is dropped); {@link #close} ends what is
 * open, then shuts the provider down within the timeout.
 */
final class TelemetryObserver implements RunObserver, ExportStatus {

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private static final Set<String> SCENARIO_RECORDS = Set.of("requires", "launchProperties", "preflight", "hooks",
            "capabilities", "recover", "cleanup");
    private static final AttributeKey<List<String>> FINISH_REASONS =
            AttributeKey.stringArrayKey(TelemetryAttributes.GEN_AI_RESPONSE_FINISH_REASONS);

    private final SdkTracerProvider provider;
    private final Tracer tracer;
    private final boolean minimal;
    private final long timeoutMs;
    private final String exporter;
    private final String endpoint;
    private final Dropped dropped;
    private final Runnable afterClose;

    private Span root;
    private Context rootCtx;
    private Span scenario;
    private Context scenarioCtx;
    private Span bind;
    private Context bindCtx;
    /** The context of the scenario's (ended) bind span: the parent of the chat spans of its slots. */
    private Context lastBindCtx;
    private Span step;
    private Context stepCtx;
    private Span call;
    private Context callCtx;
    private String callPrimitive;
    private long callStartNanos;
    private Span session;
    private Context sessionCtx;
    private Span turn;
    private Context turnCtx;
    private Span sessionStep;
    private Context sessionStepCtx;
    private long sessionStepStartNanos;
    private Span toolCall;
    private Context toolCallCtx;
    private boolean closed;

    /** What an exporter failed to deliver; shared with the exporter's failure handler. */
    static final class Dropped {
        private long batches;

        synchronized void add() {
            batches++;
        }

        synchronized long batches() {
            return batches;
        }
    }

    /**
     * @param provider the provider, owned (shut down by {@link #close})
     * @param minimal the {@code minimal} attribute set
     * @param timeoutMs the bound of {@link #flush} and {@link #close}
     * @param exporter the exporter's name, for {@link #exporter()}
     * @param endpoint {@code host:port} of the endpoint, or the file's directory name, for the summary; may be null
     * @param dropped the exporter's failure count
     * @param afterClose run once after the provider is shut down (the sender's executor); may be null
     */
    TelemetryObserver(SdkTracerProvider provider, boolean minimal, long timeoutMs, String exporter, String endpoint,
                      Dropped dropped, Runnable afterClose) {
        this.provider = provider;
        this.tracer = provider.tracerBuilder("ai.skillroy.testjava.telemetry").build();
        this.minimal = minimal;
        this.timeoutMs = timeoutMs;
        this.exporter = exporter;
        this.endpoint = endpoint;
        this.dropped = dropped == null ? new Dropped() : dropped;
        this.afterClose = afterClose;
    }

    // ------------------------------------------------------------------------------------------------ ExportStatus

    @Override
    public String exporter() {
        return exporter;
    }

    @Override
    public String endpoint() {
        return endpoint;
    }

    @Override
    public long droppedBatches() {
        return dropped.batches();
    }

    // ------------------------------------------------------------------------------------------------ the run

    @Override
    public synchronized void runStarted(RunStart run) {
        if (closed || root != null) {
            return;
        }
        String command = run == null || run.command() == null ? "run" : run.command();
        root = tracer.spanBuilder("skillroy " + command).setNoParent().setSpanKind(SpanKind.INTERNAL).startSpan();
        rootCtx = Context.root().with(root);
        byte[] id = new byte[8];
        new SecureRandom().nextBytes(id);
        Attrs a = new Attrs().str(TelemetryAttributes.RUN_ID, HexFormat.of().formatHex(id))
                .str(TelemetryAttributes.COMMAND, command);
        if (run != null) {
            a.str(TelemetryAttributes.SUITE, run.suite()).str(TelemetryAttributes.BIND_MODE, run.bindMode())
                    .str(TelemetryAttributes.ENV_ID, run.envId()).str(TelemetryAttributes.CLI_VERSION, run.cliVersion());
            RunStart.Target target = run.target();
            if (target != null) {
                if (target.pid() > 0) {
                    a.num(TelemetryAttributes.TARGET_PID, target.pid());
                }
                a.str(TelemetryAttributes.TARGET_JAVA_VERSION, target.javaVersion())
                        .str(TelemetryAttributes.TARGET_LOAD_MODE, target.loadMode())
                        .str(TelemetryAttributes.AGENT_VERSION, target.agentVersion())
                        .str(TelemetryAttributes.AGENT_PROTOCOL, target.protocol())
                        .str(TelemetryAttributes.AGENT_SESSION, target.session());
            }
        }
        root.setAllAttributes(a.build());
        root.addEvent("environment", new Attrs().bool(TelemetryAttributes.OK, run == null
                || run.environment() == null).str(TelemetryAttributes.MESSAGE, run == null ? null
                : run.environment()).build());
    }

    private void ensureRoot() {
        if (root == null) {
            runStarted(null);
        }
    }

    @Override
    public synchronized void scenarioStarted(ScenarioStart s) {
        if (closed || s == null) {
            return;
        }
        ensureRoot();
        endScenario(null);
        scenario = start(TelemetryAttributes.spanName("scenario", s.scenarioId(), minimal),
                toolCallCtx != null ? toolCallCtx : rootCtx, SpanKind.INTERNAL, -1L);
        scenarioCtx = Context.root().with(scenario);
        lastBindCtx = null;
        Attrs a = new Attrs().str(TelemetryAttributes.TEST_CASE_NAME, s.scenarioId())
                .str(TelemetryAttributes.PACK, s.pack()).str(TelemetryAttributes.PACK_VERSION, s.packVersion())
                .num(TelemetryAttributes.PLAN_VERSION, (long) s.planVersion())
                .str(TelemetryAttributes.SOURCE_HASH, s.sourceHash()).str(TelemetryAttributes.BIND_MODE, s.bindMode())
                .str(TelemetryAttributes.ENV_ID, s.envId());
        idHash(a, s.scenarioId());
        scenario.setAllAttributes(a.build());
    }

    @Override
    public synchronized void scenarioEnded(RunResult result) {
        if (scenario == null || result == null) {
            return;
        }
        endScenario(result);
    }

    private void endScenario(RunResult result) {
        if (scenario == null) {
            return;
        }
        endCall(null);
        endStep(null);
        endBind(null);
        if (result != null) {
            String status = result.status().name();
            BigDecimal cost = BigDecimal.ZERO;
            for (CostLedger.Entry entry : result.modelCalls()) {
                try {
                    cost = cost.add(new BigDecimal(entry.costUsd()));
                } catch (RuntimeException e) {
                    // An entry without a readable cost adds nothing.
                }
            }
            Attrs a = new Attrs().str(TelemetryAttributes.STATUS, status)
                    .str(TelemetryAttributes.TEST_CASE_RESULT_STATUS, TelemetryAttributes.resultStatus(status))
                    .str(TelemetryAttributes.FAILURE_KIND, result.failureKind() == null ? null
                            : result.failureKind().name())
                    .str(TelemetryAttributes.FAILED_STEP, result.failedStepId())
                    .str(TelemetryAttributes.MESSAGE, result.message())
                    .num(TelemetryAttributes.SKIPPED_EXPECTATIONS, (long) result.skippedExpectations().size())
                    .num(TelemetryAttributes.DEGRADED_FIDELITY, (long) result.degradedFidelity().size())
                    .num(TelemetryAttributes.MODEL_CALLS, (long) result.modelCalls().size())
                    .str(TelemetryAttributes.MODEL_COST_USD, cost.toPlainString());
            scenario.setAllAttributes(a.build());
            if (result.status().name().equals("FAIL") || result.status().name().equals("ERROR")) {
                scenario.setStatus(StatusCode.ERROR, result.failureKind() == null ? status
                        : result.failureKind().name());
            }
        }
        scenario.end();
        scenario = null;
        scenarioCtx = null;
        lastBindCtx = null;
    }

    // ------------------------------------------------------------------------------------------------ the bind

    @Override
    public synchronized void bindEvent(String type, Map<String, Object> fields) {
        if (scenario == null || type == null) {
            return;
        }
        Map<String, Object> f = fields == null ? Map.of() : fields;
        if ("fingerprint".equals(type)) {
            scenario.addEvent("fingerprint", new Attrs().str(TelemetryAttributes.HASH, text(f.get("hash")))
                    .num(TelemetryAttributes.DURATION_MS, number(f.get("durationMs"))).build());
            return;
        }
        if (bind == null) {
            bind = start("bind", scenarioCtx, SpanKind.INTERNAL, -1L);
            bindCtx = Context.root().with(bind);
        }
        switch (type) {
            case "resolve" -> bind.addEvent(eventName("resolve", text(f.get("slot"))), resolveEvent(f));
            case "oracle" -> bind.addEvent(eventName("oracle", text(f.get("name"))),
                    new Attrs().str(TelemetryAttributes.FN, text(f.get("fn")))
                            .num(TelemetryAttributes.DURATION_MS, number(f.get("durationMs"))).build());
            case "crosscheck" -> bind.addEvent("crosscheck", new Attrs()
                    .str(TelemetryAttributes.ORACLE, text(f.get("oracle"))).str(TelemetryAttributes.FN,
                            text(f.get("fn"))).bool(TelemetryAttributes.SKIPPED, bool(f.get("skipped")))
                    .bool(TelemetryAttributes.AGREED, bool(f.get("agreed"))).build());
            case "guard" -> bind.addEvent("guard", new Attrs().str(TelemetryAttributes.ORACLE,
                    text(f.get("oracle"))).bool(TelemetryAttributes.SKIPPED, bool(f.get("skipped"))).build());
            case "inference" -> bind.addEvent("inference", new Attrs().str(TelemetryAttributes.MODE,
                    text(f.get("mode"))).str(TelemetryAttributes.GATEWAY, text(f.get("gateway")))
                    .strs(TelemetryAttributes.CONTRIBUTORS, ids(f.get("contributors"))).build());
            case "bind" -> endBind(f);
            default -> {
                // resolver and verifier records: the resolve event carries their ids; nothing else is exported.
            }
        }
    }

    private Attributes resolveEvent(Map<String, Object> f) {
        List<String> verifiers = new ArrayList<>();
        List<Boolean> passed = new ArrayList<>();
        if (f.get("verified") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> v) {
                    verifiers.add(text(v.get("id")));
                    passed.add(Boolean.TRUE.equals(v.get("passed")));
                }
            }
        }
        Attrs a = new Attrs().str(TelemetryAttributes.STRATEGY, text(f.get("strategy")))
                .str(TelemetryAttributes.RESOLVER, text(f.get("resolver")))
                .num(TelemetryAttributes.CANDIDATES, number(f.get("candidates")))
                .num(TelemetryAttributes.REJECTED, f.get("rejected") instanceof List<?> r ? (long) r.size() : null)
                .strs(TelemetryAttributes.VERIFIERS, verifiers)
                .str(TelemetryAttributes.CONFIDENCE, f.get("confidence") == null ? null
                        : String.valueOf(f.get("confidence")))
                .str(TelemetryAttributes.APPROVAL, text(f.get("approval")))
                .bool(TelemetryAttributes.REUSED, bool(f.get("reused")))
                .num(TelemetryAttributes.DURATION_MS, number(f.get("durationMs")));
        AttributesBuilder b = a.build().toBuilder();
        if (!passed.isEmpty()) {
            b.put(AttributeKey.booleanArrayKey(TelemetryAttributes.VERIFIERS_PASSED), passed);
        }
        return b.build();
    }

    private void endBind(Map<String, Object> f) {
        if (bind == null && f != null) {
            bind = start("bind", scenarioCtx, SpanKind.INTERNAL, -1L);
            bindCtx = Context.root().with(bind);
        }
        if (bind == null) {
            return;
        }
        if (f != null) {
            Object file = f.get("bindingsFile");
            String name = file == null ? null : String.valueOf(file);
            if (name != null && (name.lastIndexOf('/') >= 0 || name.lastIndexOf('\\') >= 0)) {
                name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
            }
            boolean ok = !Boolean.FALSE.equals(f.get("ok"));
            bind.setAllAttributes(new Attrs().str(TelemetryAttributes.BIND_MODE, text(f.get("mode")))
                    .str(TelemetryAttributes.ENV_ID, text(f.get("envId")))
                    .str(TelemetryAttributes.BINDINGS_FILE, name)
                    .num(TelemetryAttributes.BIND_SKIPPED, f.get("skipped") instanceof List<?> l ? (long) l.size()
                            : 0L)
                    .bool(TelemetryAttributes.OK, ok).build());
            if (!ok) {
                bind.setStatus(StatusCode.ERROR, text(f.get("failureKind")) == null ? "bind failed"
                        : text(f.get("failureKind")));
            }
        }
        bind.end();
        lastBindCtx = bindCtx;
        bind = null;
        bindCtx = null;
    }

    // ------------------------------------------------------------------------------------------------ records

    @Override
    public synchronized void scenarioRecord(String type, Map<String, Object> fields) {
        if (closed || type == null) {
            return;
        }
        Map<String, Object> f = fields == null ? Map.of() : fields;
        if (SCENARIO_RECORDS.contains(type)) {
            Span target = scenario != null ? scenario : innermost();
            if (target == null) {
                return;
            }
            target.addEvent(type, scenarioEvent(type, f, target));
            return;
        }
        Span target = step != null ? step : sessionStep != null ? sessionStep : scenario;
        if (target == null) {
            return;
        }
        switch (type) {
            case "expect" -> target.addEvent("expect", new Attrs()
                    .str(TelemetryAttributes.EXPECT_PATH, text(f.get("path")))
                    .str(TelemetryAttributes.MATCHER, matcherName(f.get("matcher")))
                    .bool(TelemetryAttributes.HELD, "held".equals(f.get("result")))
                    .bool(TelemetryAttributes.SKIPPED, "skipped".equals(f.get("result")) ? Boolean.TRUE : null)
                    .build());
            case "db" -> target.addEvent("db", new Attrs().str(TelemetryAttributes.TABLE, text(f.get("table")))
                    .str(TelemetryAttributes.SOURCE, text(f.get("source")))
                    .str(TelemetryAttributes.CHANNEL, text(f.get("channel")))
                    .num(TelemetryAttributes.ROW_COUNT, number(f.get("rowCount")))
                    .bool(TelemetryAttributes.OK, bool(f.get("ok"))).build());
            case "prompt" -> target.addEvent("prompt", new Attrs()
                    .bool(TelemetryAttributes.EXPECTED, bool(f.get("expected")))
                    .str(TelemetryAttributes.DECISION, text(f.get("decision"))).build());
            case "fidelity" -> target.addEvent("fidelity", new Attrs()
                    .str(TelemetryAttributes.REQUESTED, text(f.get("requestedRung")))
                    .str(TelemetryAttributes.ACTUAL, text(f.get("rung"))).build());
            case "capture" -> target.addEvent("capture", new Attrs()
                    .str(TelemetryAttributes.SLOT, text(f.get("slot")))
                    .bool(TelemetryAttributes.OK, !Boolean.FALSE.equals(f.get("ok"))).build());
            default -> {
            }
        }
    }

    private Attributes scenarioEvent(String type, Map<String, Object> f, Span target) {
        Attrs a = new Attrs();
        switch (type) {
            case "requires" -> a.strs(TelemetryAttributes.IDS, ids(f.get("requires")))
                    .num(TelemetryAttributes.COUNT, f.get("unmet") instanceof List<?> l ? (long) l.size() : 0L);
            case "launchProperties" -> a.strs(TelemetryAttributes.NAMES, f.get("expected") instanceof Map<?, ?> m
                    ? keys(m) : List.of()).bool(TelemetryAttributes.OK, bool(f.get("ok")));
            case "preflight" -> a.str(TelemetryAttributes.RECORD_ID, text(f.get("id")))
                    .bool(TelemetryAttributes.OK, bool(f.get("ok")));
            case "hooks" -> {
                String mode = text(f.get("mode"));
                String hash = f.get("catalogue") instanceof Map<?, ?> c ? text(c.get("hash")) : null;
                a.str(TelemetryAttributes.MODE, mode).strs(TelemetryAttributes.IDS, ids(f.get("installed")))
                        .num(TelemetryAttributes.COUNT, f.get("unavailable") instanceof List<?> l ? (long) l.size()
                                : 0L)
                        .strs(TelemetryAttributes.ARMED, ids(f.get("armed")));
                if (target == scenario) {
                    scenario.setAllAttributes(new Attrs().str(TelemetryAttributes.HOOKS_MODE, mode)
                            .str(TelemetryAttributes.HOOKS_CATALOGUE_HASH, hash).build());
                }
            }
            case "capabilities" -> {
                String hash = text(f.get("hash"));
                a.str(TelemetryAttributes.HASH, hash).num(TelemetryAttributes.COUNT, number(f.get("ids")));
                if (target == scenario) {
                    scenario.setAttribute(TelemetryAttributes.CAPABILITIES_HASH, hash == null ? "" : hash);
                }
            }
            case "recover" -> a.bool(TelemetryAttributes.OK, bool(f.get("ok")));
            case "cleanup" -> a.str(TelemetryAttributes.PRIMITIVE, text(f.get("primitive")))
                    .bool(TelemetryAttributes.OK, bool(f.get("ok")));
            default -> {
            }
        }
        return a.build();
    }

    // ------------------------------------------------------------------------------------------------ steps, calls

    @Override
    public synchronized void stepStarted(StepStart s) {
        if (scenario == null || s == null) {
            return;
        }
        endStep(null);
        step = start(TelemetryAttributes.spanName("step", s.id(), minimal), scenarioCtx, SpanKind.INTERNAL, -1L);
        stepCtx = Context.root().with(step);
        Attrs a = new Attrs().str(TelemetryAttributes.STEP_VERB, s.verb())
                .num(TelemetryAttributes.STEP_LINE, (long) s.line());
        idHash(a, s.id());
        step.setAllAttributes(a.build());
    }

    @Override
    public synchronized void stepEnded(StepResult result) {
        if (step == null) {
            return;
        }
        endStep(result);
    }

    private void endStep(StepResult result) {
        if (step == null) {
            return;
        }
        endCall(null);
        if (result != null) {
            String status = result.status().name();
            step.setAllAttributes(new Attrs().str(TelemetryAttributes.STEP_STATUS, status)
                    .str(TelemetryAttributes.STEP_MESSAGE, result.message())
                    .num(TelemetryAttributes.STEP_SKIPPED_EXPECTATIONS, (long) result.skippedExpectations().size())
                    .build());
            if (status.equals("FAIL") || status.equals("ERROR")) {
                step.setStatus(StatusCode.ERROR, status);
            }
        }
        step.end();
        step = null;
        stepCtx = null;
    }

    @Override
    public synchronized void callStarted(CallStart c) {
        if (closed || c == null) {
            return;
        }
        ensureRoot();
        endCall(null);
        Context parent = stepCtx != null ? stepCtx : sessionStepCtx != null ? sessionStepCtx
                : scenarioCtx != null ? scenarioCtx : innermostCtx();
        callStartNanos = now();
        call = start(TelemetryAttributes.spanName("call", c.primitive(), minimal), parent, SpanKind.CLIENT,
                callStartNanos);
        callCtx = Context.root().with(call);
        callPrimitive = c.primitive();
        Attrs a = new Attrs().str(TelemetryAttributes.PRIMITIVE, c.primitive())
                .num(TelemetryAttributes.CALL_INDEX, (long) c.index())
                .str(TelemetryAttributes.CORRELATION_ID, c.correlationId())
                .bool(TelemetryAttributes.CONFIRM, c.confirm());
        if (!c.secretParams().isEmpty()) {
            a.strs(TelemetryAttributes.SECRET_PARAMS, c.secretParams());
        }
        idHash(a, c.primitive());
        call.setAllAttributes(a.build());
    }

    @Override
    public synchronized void callEnded(CallEnd end) {
        if (call == null) {
            return;
        }
        endCall(end);
    }

    private void endCall(CallEnd end) {
        if (call == null) {
            return;
        }
        long endNanos = now();
        if (end != null) {
            Attrs a = new Attrs().str(TelemetryAttributes.PATH, end.path()).bool(TelemetryAttributes.OK, end.ok())
                    .str(TelemetryAttributes.ERROR_TYPE, end.errorCode())
                    .num(TelemetryAttributes.EVENTS, (long) end.events().size());
            if (end.seqBefore() >= 0) {
                a.num(TelemetryAttributes.SEQ_BEFORE, end.seqBefore());
            }
            if (end.seqAfter() >= 0) {
                a.num(TelemetryAttributes.SEQ_AFTER, end.seqAfter());
            }
            call.setAllAttributes(a.build());
            for (CallEnd.EventFact event : end.events()) {
                call.addEvent("event", new Attrs().str(TelemetryAttributes.EVENT_TOPIC, event.topic())
                        .num(TelemetryAttributes.EVENT_SEQ_NUMBER, event.seq())
                        .str(TelemetryAttributes.EVENT_SOURCE, event.source()).build(),
                        TimeUnit.MILLISECONDS.toNanos(event.ts()), TimeUnit.NANOSECONDS);
            }
            if (end.agentDurationMs() != null) {
                // Centred in the call's round trip (D142), and never outside the call span: the round trip and the
                // agent's time are clamped to what the call span measured.
                long callNanos = Math.max(0L, endNanos - callStartNanos);
                long rttNanos = end.rttMs() >= 0 ? Math.min(callNanos, TimeUnit.MILLISECONDS.toNanos(end.rttMs()))
                        : callNanos;
                long durationNanos = Math.min(rttNanos, TimeUnit.MILLISECONDS.toNanos(Math.max(0L,
                        end.agentDurationMs())));
                long startNanos = callStartNanos + Math.max(0L, (rttNanos - durationNanos) / 2);
                Span agent = start(TelemetryAttributes.spanName("agent", callPrimitive, minimal), callCtx,
                        SpanKind.SERVER, startNanos);
                Attrs b = new Attrs().num(TelemetryAttributes.AGENT_DURATION_MS, end.agentDurationMs())
                        .str(TelemetryAttributes.AGENT_THREAD, end.agentThread())
                        .str(TelemetryAttributes.AGENT_TIMING, "reconstructed");
                idHash(b, callPrimitive);
                agent.setAllAttributes(b.build());
                agent.end(startNanos + durationNanos, TimeUnit.NANOSECONDS);
            }
            if (!end.ok()) {
                call.setStatus(StatusCode.ERROR, end.errorCode() == null ? "call failed" : end.errorCode());
            }
        }
        call.end(endNanos, TimeUnit.NANOSECONDS);
        call = null;
        callCtx = null;
        callPrimitive = null;
    }

    @Override
    public synchronized void await(AwaitRecord sync) {
        if (closed || sync == null) {
            return;
        }
        Context parent = callCtx != null ? callCtx : innermostCtx();
        if (parent == null) {
            return;
        }
        long endNanos = now();
        Span await = start("await", parent, SpanKind.INTERNAL, endNanos - TimeUnit.MILLISECONDS.toNanos(
                Math.max(0L, sync.ms())));
        await.setAllAttributes(new Attrs().str(TelemetryAttributes.SYNC_MODE, sync.mode())
                .bool(TelemetryAttributes.OK, sync.ok()).num(TelemetryAttributes.EVENT_SEQ, sync.eventSeq())
                .str(TelemetryAttributes.TOPIC, sync.topic()).num(TelemetryAttributes.SYNC_MS, sync.ms())
                .str(TelemetryAttributes.SYNC_SOURCE, sync.source()).build());
        if (!sync.ok()) {
            await.setStatus(StatusCode.ERROR, sync.mode());
        }
        await.end(endNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public synchronized void modelCall(CostLedger.Entry entry) {
        if (closed || entry == null) {
            return;
        }
        ensureRoot();
        Map<String, Object> details = entry.details() == null ? Map.of() : entry.details();
        Context parent = entry.slot() != null && "resolve".equals(entry.purpose()) && (bindCtx != null
                || lastBindCtx != null) ? (bindCtx != null ? bindCtx : lastBindCtx)
                : scenarioCtx != null ? scenarioCtx : innermostCtx();
        long endNanos = now();
        if (details.get("t") instanceof String t) {
            try {
                Instant at = Instant.parse(t);
                endNanos = TimeUnit.SECONDS.toNanos(at.getEpochSecond()) + at.getNano();
            } catch (DateTimeParseException e) {
                // the time of the report, then
            }
        }
        Long elapsed = number(details.get("elapsedMs"));
        long startNanos = endNanos - TimeUnit.MILLISECONDS.toNanos(elapsed == null ? 0L : Math.max(0L, elapsed));
        String model = entry.model() != null ? entry.model() : text(details.get("requestedModel"));
        Span chat = start(TelemetryAttributes.spanName("chat", model, minimal), parent, SpanKind.CLIENT, startNanos);
        Attrs a = new Attrs().str(TelemetryAttributes.GEN_AI_OPERATION_NAME, "chat")
                .str(TelemetryAttributes.GEN_AI_PROVIDER_NAME, TelemetryAttributes.providerName(entry.provider()))
                .str(TelemetryAttributes.GEN_AI_REQUEST_MODEL, text(details.get("requestedModel")))
                .str(TelemetryAttributes.GEN_AI_RESPONSE_MODEL, entry.model())
                .str(TelemetryAttributes.GEN_AI_RESPONSE_ID, entry.requestId())
                .num(TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS, entry.inputTokens())
                .num(TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, entry.outputTokens())
                .num(TelemetryAttributes.CACHE_READ_TOKENS, entry.cacheReadTokens())
                .num(TelemetryAttributes.CACHE_WRITE_TOKENS, entry.cacheWriteTokens())
                .str(TelemetryAttributes.PURPOSE, entry.purpose()).str(TelemetryAttributes.SLOT, entry.slot())
                .str(TelemetryAttributes.STRATEGY, entry.strategy() == null ? null : entry.strategy().name())
                .bool(TelemetryAttributes.BATCH, entry.batch())
                .str(TelemetryAttributes.MODEL_COST_USD, entry.costUsd());
        idHash(a, model);
        AttributesBuilder b = a.build().toBuilder();
        String stop = text(details.get("stop"));
        if (stop != null) {
            b.put(FINISH_REASONS, List.of(TelemetryAttributes.cut(stop)));
        }
        String error = text(details.get("error"));
        if (error != null) {
            b.put(TelemetryAttributes.ERROR_TYPE, TelemetryAttributes.cut(error));
        }
        chat.setAllAttributes(b.build());
        if (error != null) {
            chat.setStatus(StatusCode.ERROR, TelemetryAttributes.cut(error));
        }
        chat.end(Math.max(startNanos, endNanos), TimeUnit.NANOSECONDS);
    }

    // ------------------------------------------------------------------------------------------------ the session

    @Override
    public synchronized void sessionStarted(SessionStart s) {
        if (closed || s == null) {
            return;
        }
        ensureRoot();
        endSession(null);
        session = start(TelemetryAttributes.spanName("session", s.id(), minimal), rootCtx, SpanKind.INTERNAL, -1L);
        sessionCtx = Context.root().with(session);
        session.setAllAttributes(new Attrs().str(TelemetryAttributes.SESSION_ID, s.id())
                .str(TelemetryAttributes.PACK, s.pack()).str(TelemetryAttributes.PACK_VERSION, s.packVersion())
                .str(TelemetryAttributes.SESSION_POLICY, s.policy()).build());
    }

    @Override
    public synchronized void sessionRecord(String type) {
        if (session != null && type != null) {
            session.addEvent(TelemetryAttributes.cut(type));
        }
    }

    @Override
    public synchronized void turnStarted(int index) {
        if (closed) {
            return;
        }
        ensureRoot();
        endTurn(null);
        turn = start("turn " + index, sessionCtx != null ? sessionCtx : rootCtx, SpanKind.INTERNAL, -1L);
        turnCtx = Context.root().with(turn);
        turn.setAttribute(TelemetryAttributes.TURN_INDEX, (long) index);
    }

    @Override
    public synchronized void sessionTurn(TurnRecord t) {
        if (closed || t == null) {
            return;
        }
        if (turn == null) {
            ensureRoot();
            long endNanos = now();
            turn = start("turn " + t.index(), sessionCtx != null ? sessionCtx : rootCtx, SpanKind.INTERNAL,
                    endNanos - TimeUnit.MILLISECONDS.toNanos(Math.max(0L, t.durationMs())));
            turnCtx = Context.root().with(turn);
        }
        endTurn(t);
    }

    private void endTurn(TurnRecord t) {
        if (turn == null) {
            return;
        }
        endSessionStep(null);
        if (t != null) {
            turn.setAllAttributes(new Attrs().num(TelemetryAttributes.TURN_INDEX, (long) t.index())
                    .str(TelemetryAttributes.TURN_OUTCOME, t.outcome())
                    .num(TelemetryAttributes.TURN_ACTIONS, (long) t.actions())
                    .num(TelemetryAttributes.GEN_AI_USAGE_INPUT_TOKENS, t.inputTokens())
                    .num(TelemetryAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, t.outputTokens())
                    .num(TelemetryAttributes.CACHE_READ_TOKENS, t.cacheReadTokens())
                    .num(TelemetryAttributes.CACHE_WRITE_TOKENS, t.cacheWriteTokens())
                    .str(TelemetryAttributes.GEN_AI_REQUEST_MODEL, t.model())
                    .str(TelemetryAttributes.GEN_AI_RESPONSE_ID, t.requestId())
                    .num(TelemetryAttributes.DURATION_MS, t.durationMs()).build());
        }
        turn.end();
        turn = null;
        turnCtx = null;
    }

    @Override
    public synchronized void sessionStepStarted(StepStart s) {
        if (closed || s == null) {
            return;
        }
        ensureRoot();
        endSessionStep(null);
        Context parent = turnCtx != null ? turnCtx : toolCallCtx != null ? toolCallCtx
                : sessionCtx != null ? sessionCtx : rootCtx;
        sessionStepStartNanos = now();
        sessionStep = start(TelemetryAttributes.spanName("step", s.id(), minimal), parent, SpanKind.INTERNAL,
                sessionStepStartNanos);
        sessionStepCtx = Context.root().with(sessionStep);
        Attrs a = new Attrs().str(TelemetryAttributes.STEP_VERB, s.verb())
                .str(TelemetryAttributes.ORIGIN, s.origin());
        idHash(a, s.id());
        sessionStep.setAllAttributes(a.build());
    }

    @Override
    public synchronized void sessionStep(StepOutcome outcome) {
        if (closed || outcome == null) {
            return;
        }
        if (sessionStep == null) {
            sessionStepStarted(new StepStart(outcome.stepId(), outcome.verb(), 0, null));
        }
        endSessionStep(outcome);
    }

    private void endSessionStep(StepOutcome outcome) {
        if (sessionStep == null) {
            return;
        }
        endCall(null);
        if (outcome != null) {
            String status = outcome.status().name();
            sessionStep.setAllAttributes(new Attrs().str(TelemetryAttributes.STEP_STATUS, status)
                    .str(TelemetryAttributes.STEP_KIND, outcome.failureKind() == null ? null
                            : outcome.failureKind().name())
                    .num(TelemetryAttributes.STEP_EXECUTED, (long) outcome.executed())
                    .num(TelemetryAttributes.SEQ_BEFORE, outcome.seqBefore())
                    .num(TelemetryAttributes.SEQ_AFTER, outcome.seqAfter())
                    .num(TelemetryAttributes.DURATION_MS, outcome.duration().toMillis())
                    .str(TelemetryAttributes.STEP_MESSAGE, outcome.message()).build());
            if (status.equals("FAIL") || status.equals("ERROR")) {
                sessionStep.setStatus(StatusCode.ERROR, status);
            }
        }
        sessionStep.end();
        sessionStep = null;
        sessionStepCtx = null;
    }

    @Override
    public synchronized void sessionEnded(String reason) {
        endSession(reason);
    }

    private void endSession(String reason) {
        if (session == null) {
            return;
        }
        endTurn(null);
        endSessionStep(null);
        if (reason != null) {
            session.setAttribute(TelemetryAttributes.OK, !reason.contains("lost"));
        }
        session.end();
        session = null;
        sessionCtx = null;
    }

    // ------------------------------------------------------------------------------------------------ MCP

    @Override
    public synchronized void toolCallStarted(String tool, String traceparent) {
        if (closed) {
            return;
        }
        ensureRoot();
        endToolCall(null);
        String valid = RunObservers.traceparent(traceparent);
        Context parent = rootCtx;
        if (valid != null) {
            Context client = W3CTraceContextPropagator.getInstance().extract(Context.root(),
                    Map.of("traceparent", valid), GETTER);
            if (Span.fromContext(client).getSpanContext().isValid()) {
                parent = client;
            }
        }
        toolCall = start(TelemetryAttributes.spanName("tools/call", tool, minimal), parent, SpanKind.SERVER, -1L);
        toolCallCtx = Context.root().with(toolCall);
        Attrs a = new Attrs().str(TelemetryAttributes.MCP_METHOD_NAME, "tools/call")
                .str(TelemetryAttributes.GEN_AI_TOOL_NAME, tool);
        idHash(a, tool);
        toolCall.setAllAttributes(a.build());
    }

    @Override
    public synchronized void toolCall(ToolCallRecord record) {
        if (closed || record == null) {
            return;
        }
        if (toolCall == null) {
            toolCallStarted(record.tool(), null);
        }
        endToolCall(record);
    }

    private void endToolCall(ToolCallRecord record) {
        if (toolCall == null) {
            return;
        }
        endSessionStep(null);
        endScenario(null);
        if (record != null) {
            toolCall.setAllAttributes(new Attrs().str(TelemetryAttributes.MCP_SESSION_ID, record.sessionId())
                    .bool(TelemetryAttributes.OK, !record.error())
                    .num(TelemetryAttributes.DURATION_MS, record.durationMs()).build());
            if (record.error()) {
                toolCall.setStatus(StatusCode.ERROR, "tool error");
            }
        }
        toolCall.end();
        toolCall = null;
        toolCallCtx = null;
    }

    // ------------------------------------------------------------------------------------------------ trace parent

    @Override
    public synchronized String currentTraceparent() {
        Span span = innermost();
        if (span == null) {
            return null;
        }
        SpanContext context = span.getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
    }

    @Override
    public synchronized String traceId() {
        return root == null ? null : root.getSpanContext().getTraceId();
    }

    private Span innermost() {
        for (Span span : new Span[] {call, step, sessionStep, bind, scenario, turn, toolCall, session, root}) {
            if (span != null) {
                return span;
            }
        }
        return null;
    }

    private Context innermostCtx() {
        for (Context ctx : new Context[] {stepCtx, sessionStepCtx, bindCtx, scenarioCtx, turnCtx, toolCallCtx,
                sessionCtx, rootCtx}) {
            if (ctx != null) {
                return ctx;
            }
        }
        return rootCtx;
    }

    // ------------------------------------------------------------------------------------------------ flush, close

    @Override
    public void flush() {
        try {
            provider.forceFlush().join(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Telemetry never fails a run.
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            endCall(null);
            endStep(null);
            endBind(null);
            endScenario(null);
            endSessionStep(null);
            endTurn(null);
            endToolCall(null);
            endSession(null);
            if (root != null) {
                root.end();
            }
            closed = true;
        }
        try {
            provider.shutdown().join(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Bounded and quiet: telemetry never fails a run.
        } finally {
            if (afterClose != null) {
                afterClose.run();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Span start(String name, Context parent, SpanKind kind, long startNanos) {
        SpanBuilder builder = tracer.spanBuilder(TelemetryAttributes.cut(name)).setSpanKind(kind);
        if (parent == null) {
            builder.setNoParent();
        } else {
            builder.setParent(parent);
        }
        if (startNanos > 0) {
            builder.setStartTimestamp(startNanos, TimeUnit.NANOSECONDS);
        }
        return builder.startSpan();
    }

    /** {@code resolve <slot>}, {@code oracle <name>}; the kind alone in {@code minimal}. */
    private String eventName(String kind, String id) {
        return minimal || id == null ? kind : TelemetryAttributes.cut(kind + " " + id);
    }

    private void idHash(Attrs attrs, String id) {
        if (minimal && id != null) {
            attrs.raw(TelemetryAttributes.ID_HASH, TelemetryAttributes.hash(id));
        }
    }

    private static long now() {
        Instant at = Instant.now();
        return TimeUnit.SECONDS.toNanos(at.getEpochSecond()) + at.getNano();
    }

    private static String text(Object value) {
        return value instanceof String s ? s : value instanceof Number || value instanceof Boolean
                ? String.valueOf(value) : null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    /** The ids of a list of texts, or of maps with an {@code id}. */
    private static List<String> ids(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String id = item instanceof Map<?, ?> m ? text(m.get("id")) : text(item);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static List<String> keys(Map<?, ?> map) {
        List<String> out = new ArrayList<>();
        for (Object key : map.keySet()) {
            out.add(String.valueOf(key));
        }
        return out;
    }

    /** A matcher's name: the key of a one-entry matcher map ({@code gte}, {@code matches}), else {@code equals}. */
    private static String matcherName(Object matcher) {
        if (matcher instanceof Map<?, ?> map && map.size() == 1) {
            return String.valueOf(map.keySet().iterator().next());
        }
        return "equals";
    }

    /**
     * An attribute set under the allow-list and the attribute set's rules: in {@code minimal} every text goes through
     * {@link TelemetryAttributes#minimal(String, String)} (dropped, replaced by its class, kept, or hashed: a name no
     * list keeps is hashed, phase6-contracts.md 10.3 as corrected by the fix wave, FC); every text is one line cut at
     * 256 characters.
     */
    private final class Attrs {

        private final AttributesBuilder builder = Attributes.builder();

        Attrs str(String key, String value) {
            if (value == null) {
                return this;
            }
            // P2-L8-F4: the run's messages lose every absolute path before any other rule.
            String v = TelemetryAttributes.PATH_FREE.contains(key) ? TelemetryAttributes.withoutPaths(value) : value;
            return raw(key, minimal ? TelemetryAttributes.minimal(key, v) : v);
        }

        Attrs raw(String key, String value) {
            if (value != null) {
                builder.put(key, TelemetryAttributes.cut(value));
            }
            return this;
        }

        Attrs num(String key, Long value) {
            if (value != null) {
                builder.put(key, value.longValue());
            }
            return this;
        }

        Attrs bool(String key, Boolean value) {
            if (value != null) {
                builder.put(key, value.booleanValue());
            }
            return this;
        }

        /** A list of texts; in {@code minimal} each element as {@link #str} would write it (null elements left out). */
        Attrs strs(String key, List<String> values) {
            if (values == null || values.isEmpty() || minimal && TelemetryAttributes.MINIMAL_DROPS.contains(key)) {
                return this;
            }
            List<String> cut = new ArrayList<>(values.size());
            for (String value : values) {
                String v = minimal ? TelemetryAttributes.minimal(key, value) : value;
                if (v != null) {
                    cut.add(TelemetryAttributes.cut(v));
                }
            }
            if (!cut.isEmpty()) {
                builder.put(AttributeKey.stringArrayKey(key), cut);
            }
            return this;
        }

        Attributes build() {
            return builder.build();
        }
    }
}
