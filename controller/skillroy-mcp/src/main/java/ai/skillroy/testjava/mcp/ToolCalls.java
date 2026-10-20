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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.inference.budget.BudgetCaps;
import ai.skillroy.testjava.inference.budget.BudgetException;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.budget.SessionMetering;
import ai.skillroy.testjava.inference.sql.GuardViolation;
import ai.skillroy.testjava.inference.sql.GuardedStatement;
import ai.skillroy.testjava.inference.sql.SqlGuard;
import ai.skillroy.testjava.inference.tools.CallPolicy;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.CheckOutcome;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.ExportException;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.ExportResult;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.References;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.scenario.Schemas;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The tools' work (phase5-contracts.md 9.3): a pack verb through {@code ExecutorSession.execute(verb, args, origin)}, a
 * primitive through {@code execute(PlannedCall, origin)} ({@code data_query} only through {@link SqlGuard} and only on
 * an allow-listed source, its rows reduced to the allow-listed columns), and the workflow tools. Every answer is a
 * {@link Reply}: masked JSON (or masked text) cut at 25000 characters, an image written to {@code <out>/mcp/<n>.png}
 * and linked; a failed step is an error reply with its outcome. The origin of everything is
 * {@code Origin.client(<label>)} (phase6 security fix S57, D158), which the session never takes as a confirmation; a
 * {@code confirm} and a {@code _meta} in the client's arguments are dropped as well (design 3.4: the server never
 * confirms on a model's behalf), and a verb's call is checked on what it compiles to before it runs.
 *
 * <p>Called by {@link SkillroyMcpServer} on the thread that holds the session; not thread-safe on its own.
 */
final class ToolCalls {

    /** A tool's answer: its text, extra content (resource links), and whether it is an error. */
    record Reply(String text, List<McpSchema.Content> extra, boolean error) {

        Reply {
            extra = extra == null ? List.of() : List.copyOf(extra);
        }

        /** The MCP result. */
        McpSchema.CallToolResult toResult() {
            List<McpSchema.Content> content = new ArrayList<>();
            content.add(new McpSchema.TextContent(text == null ? "" : text));
            content.addAll(extra);
            return McpSchema.CallToolResult.builder().content(content).isError(error).build();
        }
    }

    /** The default and the largest {@code maxChars} of {@code skillroy_snapshot}. */
    static final int SNAPSHOT_DEFAULT = 20_000;
    static final int SNAPSHOT_MAX = 200_000;
    /** The default and the largest {@code max} of {@code skillroy_events}; the window of "the recent ones". */
    static final int EVENTS_DEFAULT = 50;
    static final int EVENTS_MAX = 200;
    /** How long a read of the event bus's last sequence number may take. */
    private static final long READ_TIMEOUT_MS = 10_000L;
    private static final String CONFIRM = "confirm";
    private static final String META = "_meta";
    /** Deeper than this an answer is not walked for images ({@link JsonForm} cuts at 100 levels before). */
    private static final int MAX_IMAGE_DEPTH = 128;
    /** The reason of a refusal for want of budget (D74), as a slot's budget failure is reported. */
    static final String BUDGET = "BUDGET";
    /** The journal's {@code budget} scope of the session meter. */
    private static final String BUDGET_SCOPE = "SESSION";
    private static final String BUDGET_SCOPE_NOTE = " (the session's budget, --session-budget, bounds"
            + " skillroy_session_ask, skillroy_resolve and skillroy_scenario_run together)";
    /** {@code data_query}'s refusal of a reference (phase6 security fix S55): the rule, never a value. */
    static final String LITERAL_ONLY = "data_query takes literal SQL and literal parameter values; a slot's field"
            + " ($slot.field) is not substituted here";

    private final SkillroyMcpServer server;

    ToolCalls(SkillroyMcpServer server) {
        this.server = server;
    }

    private ExecutorSession session() {
        return server.session();
    }

    private Masking masking() {
        return server.masking();
    }

    private DomainPack pack() {
        return server.pack();
    }

    // ------------------------------------------------------------------------------------------------ verbs, primitives

    /**
     * A pack verb through the session entry: the step protocol, the prompt policy and the journal apply. Before the first
     * call runs, the verb is compiled with the client's arguments and its calls and cleanup calls are checked
     * ({@link PlanPolicy#packRefusals}, phase6 security fix S57, D158): a verb whose arguments choose a call the server
     * never runs for a client is refused as a tool error naming each primitive, never a value, and nothing runs or is
     * journaled. A step that does not compile goes to the session, which journals the refusal as before.
     */
    Reply verb(String verb, Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        Map<String, Object> schema = null;
        try {
            schema = pack().steps().argsSchema(verb);
        } catch (RuntimeException e) {
            // A pack that cannot describe a verb's arguments: none are dropped.
        }
        Object given = withoutConfirmOrMeta(Arguments.dropNullOptionals(args, schema));
        ExecutorSession.Compiled compiled = session().compile(verb, given);
        if (compiled.refusal() == null) {
            List<String> refusals = server.planPolicy().packRefusals(compiled.calls(), compiled.cleanup());
            if (!refusals.isEmpty()) {
                throw new ToolError("verb " + verb + " is refused with these arguments, nothing ran (this server never"
                        + " runs these calls for a client): " + String.join("; ", refusals));
            }
        }
        StepOutcome outcome = session().execute(verb, given, server.origin());
        return step(outcome, "verb " + verb);
    }

    /** A primitive as one raw call of the session ({@code data.query} through {@link #dataQuery}). */
    Reply primitive(PrimitiveDescriptor d, Map<String, Object> args) throws AgentLostException, InterruptedException,
            ToolError {
        Map<String, Object> params = withoutConfirmOrMeta(Arguments.dropNullOptionals(args, d.paramsSchema()));
        if (SkillroyMcpServer.DATA_QUERY.equals(d.name())) {
            return dataQuery(params);
        }
        // phase7-contracts.md 9.4 (D341, D347): a person's arguments are refused for a client, never stripped:
        // redact: false and mode: ROBOT on a capture, force on the acting primitives, via: ROBOT whatever the flags.
        String why = CallPolicy.arguments(d.name(), params, false);
        if (why != null) {
            throw new ToolError(d.name() + " is refused, nothing was sent: " + why);
        }
        PlannedCall call = new PlannedCall(d.name(), params, syncFor(d), timeoutFor(d));
        return step(session().execute(call, server.origin()), d.name());
    }

    /** A mutating Swing primitive settles the UI after itself, as the base verbs' calls do; everything else none. */
    static Sync syncFor(PrimitiveDescriptor d) {
        return d.mutating() && d.name().startsWith("swing.") && !d.name().equals("swing.settle") ? Sync.SETTLE
                : Sync.NONE;
    }

    /** The descriptor's timeout when it is longer than the session's step timeout, else the step timeout (0). */
    private long timeoutFor(PrimitiveDescriptor d) {
        return d.timeoutMs() > session().options().stepTimeout().toMillis() ? d.timeoutMs() : 0L;
    }

    /**
     * {@code data_query}: the source must have an allow-list, the statement passes {@link SqlGuard} (the guarded text
     * is what runs, at most {@value SqlGuard#ROW_CAP} rows), and the rows keep the allow-listed columns only, a personal
     * column's values {@code <personal>} (the model's SQL is guarded even here, 9.3).
     *
     * <p>The SQL and the parameters are literal (phase6 security fix S55): a {@code $reference} in {@code sql}, or in a
     * string of {@code params} at any depth, is refused before the guard ({@link #LITERAL_ONLY}), and the call runs with
     * the session's reference substitution off ({@link PlannedCall#literalArgs()}), so that the statement the agent
     * receives is byte-equal to the guarded text: a slot's value is target content and never rebuilds the statement
     * after the guard checked it.
     */
    private Reply dataQuery(Map<String, Object> params) throws AgentLostException, InterruptedException, ToolError {
        String source = Arguments.text(params, "source", true);
        String sql = Arguments.text(params, "sql", true);
        if (References.containsReference(sql) || References.anyReference(params.get("params"))) {
            throw new ToolError(LITERAL_ONLY);
        }
        SqlAllowList allow = server.allowLists().get(source);
        if (allow == null) {
            throw new ToolError("data_query: source '" + source + "' has no SQL allow-list; data_query reads only "
                    + new TreeSet<>(server.allowLists().keySet()));
        }
        SqlGuard guard = server.guard(source);
        GuardedStatement guarded;
        try {
            guarded = guard.guard(sql);
        } catch (GuardViolation v) {
            throw new ToolError("data_query: SqlGuard refused the statement: " + v.getMessage());
        }
        List<Object> values = params.get("params") instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        if (values.size() != guarded.parameterCount()) {
            throw new ToolError("data_query: the statement has " + guarded.parameterCount() + " parameter"
                    + (guarded.parameterCount() == 1 ? "" : "s") + " (?), " + values.size() + " given in params");
        }
        long maxRows = SqlGuard.ROW_CAP;
        if (params.get("maxRows") instanceof Number n) {
            maxRows = Math.max(1L, Math.min(n.longValue(), SqlGuard.ROW_CAP));
        }
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("source", source);
        sent.put("sql", guarded.sql());
        sent.put("params", values);
        sent.put("maxRows", maxRows);
        StepOutcome outcome = session().execute(new PlannedCall(SkillroyMcpServer.DATA_QUERY, sent, Sync.NONE, 0L)
                .withLiteralArgs(), server.origin());
        Object rows = null;
        if (outcome.succeeded() && outcome.lastResult() != null
                && outcome.lastResult().value() instanceof Map<?, ?> value) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = (Map<String, Object>) JsonForm.of(value);
                rows = masking().rows(source, Rows.fromJson(json)).toJson();
            } catch (IllegalArgumentException e) {
                rows = null;
            }
        }
        Map<String, Object> json = stepJson(outcome);
        json.put("value", rows);
        json.put("statement", guarded.sql());
        json.put("injected", guarded.injected());
        return reply(json, !outcome.succeeded(), List.of(), "data_query");
    }

    /**
     * A step's answer: its outcome as JSON, the last call's value (any image in it, its detail or its events written
     * out and linked by {@link #reply}).
     *
     * @param what the primitive or verb, for the image's description
     */
    private Reply step(StepOutcome outcome, String what) {
        Map<String, Object> json = stepJson(outcome);
        json.put("value", outcome.lastResult() == null ? null : outcome.lastResult().value());
        return reply(json, !outcome.succeeded(), List.of(), what + " (step " + outcome.stepId() + ")");
    }

    private static Map<String, Object> stepJson(StepOutcome outcome) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("step", outcome.stepId());
        json.put("verb", outcome.verb());
        json.put("status", outcome.status().name());
        json.put("failureKind", outcome.failureKind() == null ? null : outcome.failureKind().name());
        json.put("message", outcome.message());
        json.put("executed", (long) outcome.executed());
        json.put("calls", outcome.calls().stream().map(PlannedCall::primitive).toList());
        json.put("value", null);
        json.put("detail", outcome.detail());
        List<Object> events = new ArrayList<>();
        for (Event event : outcome.events()) {
            events.add(eventJson(event));
        }
        json.put("events", events);
        json.put("degradedFidelity", outcome.degradedFidelity());
        if (!outcome.warnings().isEmpty()) {
            // phase8-contracts.md 7.1: only when non-empty, so that a reply without warnings is as before.
            json.put("warnings", outcome.warnings());
        }
        json.put("seqBefore", outcome.seqBefore());
        json.put("seqAfter", outcome.seqAfter());
        json.put("durationMs", outcome.duration().toMillis());
        return json;
    }

    private static Map<String, Object> eventJson(Event event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("seq", event.seq());
        json.put("topic", event.topic());
        json.put("source", event.source());
        json.put("correlationId", event.correlationId());
        json.put("payload", event.payload());
        return json;
    }

    /**
     * The value with every image in it (a map with {@code format: png} and {@code base64}: a capture, a bitmap), at any
     * depth (phase6 security fix, pass 2, P2-L6-F3: every tool's answer, not a step's value only, and not only to depth
     * 8), written to {@code <out>/mcp/<n>.png} and replaced by a reference to its resource link, which is added to
     * {@code links} (the link names the PNG only under {@code images: true}, D159). An image that cannot be decoded is
     * left to the mask, which elides its bytes ({@link Masking}).
     */
    private Object images(Object value, List<McpSchema.Content> links, String what, int depth) {
        if (depth > MAX_IMAGE_DEPTH) {
            return "<too deep>";
        }
        if (value instanceof Map<?, ?> map) {
            if ("png".equals(map.get("format")) && map.get("base64") instanceof String base64) {
                byte[] png;
                try {
                    png = Base64.getMimeDecoder().decode(base64);
                } catch (IllegalArgumentException e) {
                    png = null;
                }
                if (png != null) {
                    McpSchema.ResourceLink link = server.registerImage(png, what);
                    Map<String, Object> out = new LinkedHashMap<>();
                    map.forEach((k, v) -> out.put(String.valueOf(k), v));
                    out.put("base64", link == null ? "<not written>" : "<image: " + link.uri() + ">");
                    if (link != null) {
                        out.put("resource", link.uri());
                        links.add(link);
                    }
                    return out;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), images(e.getValue(), links, what, depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(images(item, links, what, depth + 1));
            }
            return out;
        }
        return value;
    }

    // ------------------------------------------------------------------------------------------------ workflow

    /** {@code skillroy_catalogue}: verbs with schemas, query kinds with their arguments, oracles, capabilities. */
    Reply catalogue() {
        DomainPack pack = pack();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("pack", Map.of("id", pack.id(), "version", String.valueOf(pack.version())));
        json.put("mutating", server.mutating());
        List<Object> verbs = new ArrayList<>();
        StepLibrary steps = pack.steps();
        for (String verb : new TreeSet<>(steps.verbs())) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("verb", verb);
            String tool = server.verbTool(verb);
            v.put("tool", tool);
            Map<String, Object> schema;
            try {
                schema = steps.argsSchema(verb);
            } catch (RuntimeException e) {
                schema = null;
            }
            v.put("argsSchema", schema);
            if (schema == null) {
                v.put("note", "free-form arguments: the pack declares no schema");
            }
            verbs.add(v);
        }
        json.put("verbs", verbs);
        List<Object> renamed = new ArrayList<>();
        server.renamedPrimitives().forEach((tool, primitive) -> renamed.add(Map.of("tool", tool, "primitive",
                primitive)));
        json.put("renamedPrimitives", renamed);
        List<Object> withheld = new ArrayList<>();
        server.withheldVerbs().forEach((tool, why) -> withheld.add(Map.of("verb", why[0], "tool", tool, "reason",
                why[1])));
        json.put("withheldVerbs", withheld);
        ResolutionCatalogue catalogue;
        try {
            catalogue = pack.resolution();
        } catch (RuntimeException e) {
            catalogue = ResolutionCatalogue.EMPTY;
        }
        List<Object> queries = new ArrayList<>();
        for (String kind : new TreeSet<>(catalogue.kinds())) {
            QueryType<?, ?> type = catalogue.query(kind);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("kind", kind);
            q.put("args", schemaOf(type.queryClass()));
            q.put("value", type.valueType().getTypeName());
            List<String> strategies = new ArrayList<>();
            catalogue.resolvers(kind).forEach(r -> strategies.add(r.strategy().name()));
            q.put("resolvers", strategies);
            queries.add(q);
        }
        json.put("queries", queries);
        List<Object> oracles = new ArrayList<>();
        for (String fn : new TreeSet<>(catalogue.oracleFns())) {
            Oracle<?, ?> oracle = catalogue.oracle(fn);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("fn", fn);
            try {
                o.put("input", schemaOf(oracle.inputType()));
                o.put("output", oracle.outputType().getTypeName());
            } catch (RuntimeException e) {
                o.put("input", Map.of("type", "object"));
            }
            oracles.add(o);
        }
        json.put("oracles", oracles);
        Map<String, Object> caps = new LinkedHashMap<>();
        // phase7-contracts.md 9.4, 8.5: the session's merged matrix, each entry with its source (agent or
        // controller:<contributor>).
        ai.skillroy.testjava.controller.CapabilityMatrix merged = session().capabilities();
        for (Map.Entry<String, Capability> e : merged.all().entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>(e.getValue().toJson());
            entry.put("source", merged.source(e.getKey()));
            caps.put(e.getKey(), entry);
        }
        json.put("capabilities", caps);
        List<Object> sources = new ArrayList<>();
        for (SqlAllowList list : server.dataQueryLists().values()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("source", list.source());
            s.put("dialect", list.dialect().name());
            s.put("tables", list.tables().stream().map(SqlAllowList.AllowedTable::name).toList());
            sources.add(s);
        }
        json.put("dataQuerySources", sources);
        return reply(json, false, List.of());
    }

    /**
     * The schema of a value as a scenario writes it ({@code Schemas.ofArguments}); for a record, its object branch (a
     * query's {@code args} is a mapping, never a reference).
     */
    private static Object schemaOf(Type type) {
        try {
            Map<String, Object> schema = Schemas.ofArguments(type);
            if (!schema.containsKey("properties") && schema.get("anyOf") instanceof List<?> branches) {
                for (Object branch : branches) {
                    if (branch instanceof Map<?, ?> b && b.containsKey("properties")) {
                        return b;
                    }
                }
            }
            return schema;
        } catch (RuntimeException e) {
            Map<String, Object> free = new LinkedHashMap<>();
            free.put("type", "object");
            free.put("description", "not describable as a schema: " + e.getMessage());
            return free;
        }
    }

    /** {@code skillroy_snapshot}: the UI as snapshot text, data read from the target. */
    Reply snapshot(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        int max = Arguments.integer(args, "maxChars", SNAPSHOT_DEFAULT, 100, SNAPSHOT_MAX);
        String text = session().snapshotText(max);
        return new Reply(Masking.cut(masking().text(text)), List.of(), false);
    }

    /** {@code skillroy_facts}: a fact root, or a path inside it. */
    Reply facts(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        String root = Arguments.text(args, "root", true);
        String path = Arguments.text(args, "path", false);
        Object value;
        try {
            value = session().fact(root, path);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolError("skillroy_facts: " + e.getMessage());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("root", root);
        json.put("path", path);
        json.put("value", value);
        return reply(json, false, List.of(), "skillroy_facts");
    }

    /** {@code skillroy_events}: the events after a sequence number, or the recent ones. */
    Reply events(Map<String, Object> args) throws AgentLostException, ToolError {
        Long since = Arguments.longValue(args, "sinceSeq");
        String topics = Arguments.text(args, "topics", false);
        int max = Arguments.integer(args, "max", EVENTS_DEFAULT, 1, EVENTS_MAX);
        List<Event> events = since != null ? session().eventsSince(Math.max(0L, since), topics, max)
                : recent(topics, max);
        List<Object> out = new ArrayList<>();
        for (Event event : events) {
            out.add(eventJson(event));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("events", out);
        json.put("count", (long) out.size());
        return reply(json, false, List.of(), "skillroy_events");
    }

    /** The last {@code max} events of the last {@value #EVENTS_MAX} on the bus, on {@code topics} (null: all). */
    List<Event> recent(String topics, int max) throws AgentLostException {
        long last = lastSeq();
        List<Event> window = session().eventsSince(Math.max(0L, last - EVENTS_MAX), topics, EVENTS_MAX);
        return window.size() <= max ? window : window.subList(window.size() - max, window.size());
    }

    /** The bus's last sequence number ({@code evt.query} answers it); 0 when it cannot be read. */
    private long lastSeq() throws AgentLostException {
        try {
            RpcResult result = server.agent().call("evt.query", Map.of("limit", 1L),
                    CallOptions.defaults().withTimeoutMs(READ_TIMEOUT_MS));
            if (result.value() instanceof Map<?, ?> value && value.get("lastSeq") instanceof Number n) {
                return n.longValue();
            }
            return result.seqAfter();
        } catch (AgentRpcException | RuntimeException e) {
            return 0L;
        }
    }

    /**
     * {@code skillroy_resolve}: a query resolved live; the answer joins the session's bindings. With an actor (a
     * session service), through the actor's metered entry, so that a model token counts on the session's budget
     * (13.4); else on the session itself.
     */
    Reply resolve(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        String slot = Arguments.text(args, "slot", true);
        String kind = Arguments.text(args, "kind", true);
        Map<String, Object> queryArgs = Arguments.map(args, "args");
        List<String> strategy = Arguments.texts(args, "strategy");
        try {
            SessionActor actor = server.resolvingActor();
            if (actor != null) {
                actor.resolve(session(), slot, kind, queryArgs, strategy, server.origin());
            } else {
                // D157: without an actor, a resolve that names a model is metered on the server's session meter here.
                Metering metering = meter(SessionMetering.namesModel(strategy) ? 1 : 0);
                if (metering.refusal() != null) {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("slot", slot);
                    json.put("kind", kind);
                    json.put("failureKind", FailureKind.RESOLUTION_FAILED.name());
                    json.put("reason", BUDGET);
                    json.put("message", "slot '" + slot + "': " + metering.refusal().getMessage() + "; no model call"
                            + " was made" + BUDGET_SCOPE_NOTE);
                    json.put("detail", budgetDetail(metering.refusal()));
                    return reply(json, true, List.of());
                }
                try (BudgetCaps.Cap cap = metering.cap()) {
                    session().resolve(slot, kind, queryArgs, strategy, null, null, server.origin());
                } finally {
                    settle(metering);
                }
            }
        } catch (BindException e) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("slot", slot);
            json.put("kind", kind);
            json.put("failureKind", e.kind() == null ? null : e.kind().name());
            json.put("reason", e.reason());
            json.put("message", e.getMessage());
            json.put("detail", e.detail());
            return reply(json, true, List.of());
        } catch (IllegalArgumentException e) {
            throw new ToolError("skillroy_resolve: " + e.getMessage());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("slot", slot);
        json.put("kind", kind);
        json.put("reference", "$" + slot);
        json.put("resolution", lastResolution(slot));
        return reply(json, false, List.of(), "skillroy_resolve");
    }

    /** The journal's record of the slot's resolution, in the bindings file's shape (already masked). */
    private Object lastResolution(String slot) {
        List<StepJournal.Entry> entries = session().journal().entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof StepJournal.ResolveEntry entry && entry.slot().equals(slot)) {
                return entry.resolution();
            }
        }
        return null;
    }

    /** {@code skillroy_scenario_compile}: the plan's summary, or the problem with its line. */
    Reply compile(Map<String, Object> args) throws ToolError {
        String yaml = Arguments.text(args, "yaml", true);
        Map<String, Object> json = new LinkedHashMap<>();
        PlanCompiler.Compiled compiled;
        try {
            Scenario scenario = ScenarioLoader.load(yaml.getBytes(StandardCharsets.UTF_8), "scenario.yaml");
            compiled = PlanCompiler.compileWithNotes(scenario, pack());
        } catch (ScenarioException e) {
            json.put("compiled", false);
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("line", e.line() > 0 ? (Object) (long) e.line() : null);
            problem.put("message", e.reason() != null ? e.reason() : e.getMessage());
            problem.put("text", e.getMessage());
            json.put("problems", List.of(problem));
            return reply(json, false, List.of());
        } catch (RuntimeException e) {
            json.put("compiled", false);
            json.put("problems", List.of(Map.of("message", "the pack failed to compile the scenario: " + e)));
            return reply(json, false, List.of());
        }
        json.put("compiled", true);
        json.putAll(summary(compiled.plan()));
        json.put("notes", compiled.notes());
        return reply(json, false, List.of());
    }

    private static Map<String, Object> summary(Plan plan) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("scenarioId", plan.scenarioId());
        json.put("pack", plan.pack());
        json.put("packVersion", plan.packVersion());
        json.put("planVersion", (long) plan.planVersion());
        json.put("inference", plan.inference());
        json.put("requires", plan.requires());
        List<Object> slots = new ArrayList<>();
        for (Plan.Slot slot : plan.slots()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", slot.name());
            s.put("kind", slot.kind());
            if (slot.query() != null) {
                s.put("query", slot.query().query());
                s.put("strategy", slot.query().strategy());
            }
            slots.add(s);
        }
        json.put("slots", slots);
        json.put("oracles", plan.oracles().stream().map(Plan.Oracle::name).toList());
        List<Object> steps = new ArrayList<>();
        for (Plan.Step step : plan.steps()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", step.id());
            s.put("verb", step.verb());
            s.put("line", (long) step.line());
            s.put("calls", step.calls().stream().map(Plan.Call::primitive).toList());
            s.put("expectations", (long) step.expect().size());
            steps.add(s);
        }
        json.put("steps", steps);
        json.put("verify", (long) plan.verify().size());
        json.put("cleanup", (long) plan.cleanup().size());
        json.put("stepTimeoutMs", plan.stepTimeoutMs());
        json.put("planHash", plan.hash());
        return json;
    }

    // ------------------------------------------------------------------------------------------------ the session budget

    /**
     * What a run or a resolve reserved on the server's session meter (phase6 security fix S56, D157): the meter, the
     * session's ledger and its size before, the number of tier (b) estimates reserved, the session's room before them
     * (the cap of the binds), the gateway's name; or the refusal; {@link #NONE} when nothing is metered.
     */
    private record Metering(BudgetMeter meter, CostLedger ledger, int before, int reserved, BigDecimal room,
                            String provider, BudgetException refusal) {

        static final Metering NONE = new Metering(null, null, 0, 0, null, null, null);

        /** The cap of the binds that record into the session's ledger: the session's room; none when not metered. */
        BudgetCaps.Cap cap() {
            return meter == null || ledger == null ? () -> { } : BudgetCaps.cap(ledger, room);
        }
    }

    /**
     * Reserves one tier (b) estimate per slot whose strategy names a model on the server's session meter, as
     * {@code SessionService.resolve} does (D157): refused, with the meter's figures and no model call, when the meter is
     * exhausted (D74) or has no room (which exhausts it, D74). Nothing is metered without a session meter, without a
     * gateway, or for no such slot.
     *
     * @throws ToolError when the settings' price table cannot be read
     */
    private Metering meter(int tierBSlots) throws ToolError {
        BudgetMeter meter = server.sessionMeter();
        InferenceSettings settings = session().options().bind().inference();
        if (meter == null || tierBSlots == 0 || settings == null || !settings.enabled()) {
            return Metering.NONE;
        }
        meter.identify(session().id());
        PriceTable prices;
        try {
            prices = PriceTable.of(settings);
        } catch (SettingsException e) {
            throw new ToolError("the price table of the inference settings cannot be read: " + e.getMessage());
        }
        BigDecimal estimate = SessionMetering.tierBEstimate(settings, prices);
        BigDecimal room = meter.remaining();
        if (meter.exhausted()) {
            return refused(new BudgetException(meter.scope(), meter.id(), meter.limit(), meter.spent(), estimate));
        }
        int reserved = 0;
        try {
            for (; reserved < tierBSlots; reserved++) {
                meter.reserve(estimate);
            }
        } catch (BudgetException e) {
            for (int i = 0; i < reserved; i++) {
                meter.release();
            }
            meter.markExhausted();
            recordBudget(meter);
            return refused(e);
        }
        CostLedger ledger = session().options().bind().ledger();
        return new Metering(meter, ledger, ledger.size(), tierBSlots, room, settings.gateway(), null);
    }

    private static Metering refused(BudgetException e) {
        return new Metering(null, null, 0, 0, null, null, e);
    }

    /** Settles what the metered run or resolve added to the session's ledger and journals the session's budget. */
    private void settle(Metering metering) {
        if (metering.meter() == null) {
            return;
        }
        SessionMetering.settle(metering.meter(), metering.ledger(), metering.before(), metering.reserved(),
                metering.provider());
        recordBudget(metering.meter());
    }

    private void recordBudget(BudgetMeter meter) {
        try {
            session().budget(BUDGET_SCOPE, meter.spent(), meter.limit(), meter.exhausted());
        } catch (IllegalStateException e) {
            // The session was closed; the ledger keeps the calls.
        }
    }

    /** A refusal's figures: scope, id, limit, spent and estimate, in US dollars as plain text. */
    private static Map<String, Object> budgetDetail(BudgetException e) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("scope", e.scope().name());
        json.put("id", e.id());
        json.put("limitUsd", e.limitUsd() == null ? null : e.limitUsd().toPlainString());
        json.put("spentUsd", e.spentUsd() == null ? null : e.spentUsd().toPlainString());
        json.put("estimateUsd", e.estimateUsd() == null ? null : e.estimateUsd().toPlainString());
        return json;
    }

    /**
     * {@code skillroy_scenario_run} (mutating only): the scenario compiled, checked against what this server offers
     * ({@link PlanPolicy}, D96: refused as a tool error naming each call that may not run, before anything runs), and
     * run once with {@link PlanExecutor} in {@code live} (nothing written) or {@code approve} (the session's approver
     * and bindings root decide), with recover and every expectation on masked values
     * ({@code RunOptions.withExpectationsOnMaskedValues}, so that PASS or FAIL never answers whether a credential
     * starts with a prefix), its evidence in {@code <out>/runs/<run id>/}; the run's summary. Metered on the server's
     * session meter (phase6 security fix S56, D157): one tier (b) estimate per slot whose strategy names a model is
     * reserved first ({@link #meter}; refused {@code BUDGET} with no model call when there is no room), the run executes
     * under a cap of the session's room, and what it spent is settled and journaled afterwards. The run installs the
     * pack's hooks under the server's {@code --hooks} switch, the session's (phase6 security fix, pass 2, P2-L6-F1):
     * {@code off} installs nothing, {@code auto} and {@code on} find the server's own install.
     */
    Reply run(Map<String, Object> args) throws AgentLostException, ToolError {
        String yaml = Arguments.text(args, "yaml", true);
        String mode = Arguments.text(args, "mode", true);
        BindMode bindMode = switch (mode) {
            case "live" -> BindMode.LIVE;
            case "approve" -> BindMode.APPROVE;
            default -> throw new ToolError("skillroy_scenario_run: mode is live or approve, found '" + mode + "'");
        };
        if (bindMode == BindMode.APPROVE && session().options().bind().approver()
                == ai.skillroy.testjava.scenario.Approver.REJECT_ALL) {
            // Phase 7 security fix, S115 (phase5-contracts.md 9.3, phase7-contracts.md 9.4): without an approver
            // nothing can be approved, so approve could only spend the budget and touch the operator's bindings root;
            // with live never writing, a client's run writes nothing there in either mode.
            throw new ToolError("skillroy_scenario_run: mode approve is refused: this server has no approver (a"
                    + " person approves bindings and baselines); run approve at the CLI");
        }
        Plan plan;
        Scenario scenario;
        try {
            scenario = ScenarioLoader.load(yaml.getBytes(StandardCharsets.UTF_8), "scenario.yaml");
            plan = PlanCompiler.compile(scenario, pack());
        } catch (ScenarioException e) {
            throw new ToolError("skillroy_scenario_run: the scenario does not compile: " + e.getMessage());
        }
        List<String> refusals = server.planPolicy().refusals(plan, scenario);
        if (!refusals.isEmpty()) {
            throw new ToolError("skillroy_scenario_run: the scenario is refused, nothing ran (this server runs only"
                    + " what it offers as tools): " + String.join("; ", refusals));
        }
        int tierB = 0;
        for (Plan.Slot slot : plan.slots()) {
            if (slot.query() != null && SessionMetering.namesModel(slot.query().strategy())) {
                tierB++;
            }
        }
        Metering metering = meter(tierB);
        if (metering.refusal() != null) {
            Map<String, Object> json = budgetDetail(metering.refusal());
            json.put("refused", BUDGET);
            json.put("tierBSlots", (long) tierB);
            json.put("message", "skillroy_scenario_run: the scenario is refused, nothing ran and no model call was"
                    + " made: " + metering.refusal().getMessage() + BUDGET_SCOPE_NOTE);
            return reply(json, true, List.of());
        }
        String runId = server.nextRunId();
        Path dir = server.outDir().resolve("runs").resolve(runId);
        BindOptions bind = session().options().bind().withMode(bindMode).withWriteBindings(false);
        // Phase 6 security fix, pass 2, P2-L6-F1: the run's step 0b runs under the server's --hooks switch (the
        // session's), never the run default AUTO, so that a client cannot install or replace hooks through a run.
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withBind(bind).withClock(server.clock())
                .withExpectationsOnMaskedValues(true).withObserver(session().observer())
                .withHooks(session().options().hooks());
        PlanExecutor executor = new PlanExecutor(pack(), server.agent(), options,
                session().options().webPathLoader());
        PlanExecutor.Resolved ran;
        try (BudgetCaps.Cap cap = metering.cap()) {
            ran = executor.runWithOutcome(plan);
        } finally {
            settle(metering);
        }
        RunResult result = ran.result();
        promptsHandledByTheRun();
        List<String> resources = server.registerRun(runId, dir);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("runId", runId);
        json.put("scenarioId", result.scenarioId());
        json.put("status", result.status().name());
        json.put("failureKind", result.failureKind() == null ? null : result.failureKind().name());
        json.put("failedStepId", result.failedStepId());
        json.put("message", result.message());
        json.put("mode", result.mode() == null ? mode : result.mode().label());
        json.put("envId", result.envId());
        json.put("modelCalls", (long) result.modelCalls().size());
        json.put("durationMs", result.duration().toMillis());
        json.put("skippedExpectations", result.skippedExpectations());
        json.put("degradedFidelity", result.degradedFidelity());
        if (!result.warnings().isEmpty()) {
            // phase8-contracts.md 7.1: a client's own scenario may accept warnings; only when non-empty.
            json.put("warnings", result.warnings());
        }
        json.put("detail", result.detail());
        List<Object> steps = new ArrayList<>();
        for (StepResult step : result.steps()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", step.id());
            s.put("verb", step.verb());
            s.put("line", (long) step.line());
            s.put("status", step.status().name());
            s.put("message", step.message());
            steps.add(s);
        }
        json.put("steps", steps);
        json.put("outputDir", dir.toString());
        json.put("resources", resources);
        if (result.failureKind() == FailureKind.AGENT_LOST || server.agent().isLost()) {
            throw new AgentLostException("the agent was lost during scenario run " + runId + ": "
                    + masking().text(result.message()));
        }
        boolean passed = result.status() == Status.PASS || result.status() == Status.DEGRADED;
        return reply(json, !passed, List.of(), "skillroy_scenario_run " + runId);
    }

    /**
     * After {@code skillroy_scenario_run}: the run answered (or failed on) the prompts it saw with its own prompt
     * policy, so the session is told the agent's sequence number now ({@code ExecutorSession.promptsHandledUpTo}), read
     * from a query that returns no event; the session's next step does not take those prompts for its own unexpected
     * ones. A query the agent refuses leaves the session as it was; a lost agent is the caller's to report.
     */
    private void promptsHandledByTheRun() throws AgentLostException {
        if (server.agent().isLost()) {
            return;
        }
        try {
            RpcResult now = server.agent().call("evt.query", Map.of("since", Long.MAX_VALUE, "limit", 1L),
                    CallOptions.defaults().withTimeoutMs(READ_TIMEOUT_MS));
            session().promptsHandledUpTo(now.seqAfter());
        } catch (AgentRpcException | RuntimeException e) {
            server.log().info("the agent's sequence number after the run could not be read: " + e.getMessage());
        }
    }

    /** {@code skillroy_session_ask}: the actor's turn. */
    Reply ask(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        String request = Arguments.text(args, "request", true);
        SessionActor actor = server.actor();
        Object answer = actor.ask(request);
        // phase7-contracts.md 9.4: the captures the actor's vision loop acted on, listed under images: true only.
        server.registerCaptures();
        return reply(JsonForm.of(answer), false, List.of(), "skillroy_session_ask");
    }

    /** {@code skillroy_session_check}: an expectation evaluated now and journaled. */
    Reply check(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        String path = Arguments.text(args, "path", true);
        Object matcher = args.get("matcher");
        CheckOutcome outcome = session().check(path, matcher, server.origin());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", outcome.path());
        json.put("matcher", outcome.matcher());
        json.put("held", outcome.held());
        json.put("actual", outcome.actual());
        json.put("reason", outcome.reason());
        json.put("durationMs", outcome.duration().toMillis());
        return reply(json, false, List.of(), "skillroy_session_check");
    }

    /** {@code skillroy_session_observe}: a value read now and journaled as observed. */
    Reply observe(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        String path = Arguments.text(args, "path", true);
        Object value;
        try {
            value = session().observe(path, server.origin());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolError("skillroy_session_observe: " + e.getMessage());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", path);
        json.put("value", value);
        json.put("note", "journaled as observed: an export marks it for the reviewer to derive or confirm");
        return reply(json, false, List.of(), "skillroy_session_observe");
    }

    /** {@code skillroy_session_note}: a note in the journal. */
    Reply note(Map<String, Object> args) throws ToolError {
        String text = Arguments.text(args, "text", true);
        session().note(text, server.origin());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("noted", true);
        json.put("entries", (long) session().journal().entries().size());
        return reply(json, false, List.of());
    }

    /**
     * {@code skillroy_session_export}: the journal exported as a scenario (compile-checked by the export), written as
     * {@code <out>/<id>.yaml}, {@code <out>/plan/<id>.plan.json} and {@code <out>/bindings/<env>/<id>.bindings.json};
     * the reviewer's report. No approver (decision D97): a client is often a model, so a model-found binding is always
     * written {@code REQUIRED}, and an {@code approvedBy} sent anyway is ignored and said so in the report; a person
     * approves with {@code skillroy resolve}, which reuses the recorded value without a model call (D82). An id whose
     * files exist in {@code <out>} is refused, and each file is created new ({@code CREATE_NEW}), as {@code author}
     * does (phase6 security fix S61): a client never replaces a scenario; the CLI's {@code session export}, a
     * person's, may overwrite.
     */
    Reply export(Map<String, Object> args) throws ToolError {
        String scenarioId = Arguments.text(args, "scenarioId", true);
        boolean approverIgnored = args.get("approvedBy") != null;
        ExportOptions options;
        try {
            options = ExportOptions.of(scenarioId);
        } catch (IllegalArgumentException e) {
            throw new ToolError("skillroy_session_export: " + e.getMessage());
        }
        ExportResult result;
        try {
            result = session().journal().export(options);
        } catch (ExportException e) {
            throw new ToolError("skillroy_session_export: the session cannot be exported: " + e.getMessage());
        }
        byte[] yaml = result.yaml().getBytes(StandardCharsets.UTF_8);
        Path out = server.outDir();
        Path yamlFile = out.resolve(scenarioId + ".yaml");
        Path planFile = out.resolve("plan").resolve(scenarioId + ".plan.json");
        Path bindingsFile = result.bindings() == null ? null
                : BindingsFile.locate(out.resolve("bindings"), session().options().bind().envId(), scenarioId);
        for (Path existing : new Path[] {yamlFile, planFile, bindingsFile}) {
            if (existing != null && Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new ToolError("skillroy_session_export: " + existing + " exists; export under another id");
            }
        }
        try {
            Plan plan = PlanCompiler.compile(yaml, scenarioId + ".yaml", pack());
            createNew(yamlFile, yaml);
            createNew(planFile, plan.toJsonText().getBytes(StandardCharsets.UTF_8));
            if (bindingsFile != null) {
                createNew(bindingsFile, result.bindings().toJsonText().getBytes(StandardCharsets.UTF_8));
            }
        } catch (ScenarioException e) {
            throw new ToolError("skillroy_session_export: the exported scenario does not compile: " + e.getMessage());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new ToolError("skillroy_session_export: " + e.getFile() + " exists; export under another id");
        } catch (IOException e) {
            throw new ToolError("skillroy_session_export: the files cannot be written: " + e);
        }
        StringBuilder text = new StringBuilder(result.report().text());
        text.append("Written:\n  ").append(yamlFile).append("\n  ").append(planFile).append('\n');
        if (bindingsFile != null) {
            text.append("  ").append(bindingsFile).append('\n');
        }
        if (approverIgnored) {
            text.append("approvedBy is not taken over MCP: a model-found binding is written REQUIRED; a person"
                    + " approves it with skillroy resolve.\n");
        }
        server.log().info("exported session " + session().id() + " as " + scenarioId + " into " + out);
        return new Reply(Masking.cut(masking().text(text.toString())), List.of(), false);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Writes a new file (its parent directories made), never replacing one ({@code CREATE_NEW}, S61). */
    private static void createNew(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
    }

    /** Masked pretty JSON, cut; any image in it written out and linked ({@link #images}). */
    private Reply reply(Object value, boolean error, List<McpSchema.Content> extra) {
        return reply(value, error, extra, "a tool's answer");
    }

    /**
     * Masked pretty JSON, cut: every image in the value, at any depth, written to {@code <out>/mcp/<n>.png} and
     * linked ({@link #images}; phase6 security fix, pass 2, P2-L6-F3), then the masks, which elide any image's bytes
     * left.
     *
     * @param what the tool (and step) whose answer it is, for an image's description
     */
    private Reply reply(Object value, boolean error, List<McpSchema.Content> extra, String what) {
        List<McpSchema.Content> links = new ArrayList<>(extra);
        Object linked = images(JsonForm.of(value), links, what, 0);
        return new Reply(Masking.json(masking().value(linked)), links, error);
    }

    /**
     * The arguments without {@code confirm} and {@code _meta}: at the top and in a nested {@code args} (the base verb
     * {@code call}'s form, and a verb that forwards its arguments), as the session strips a model's (design 3.4: a
     * model's request is never the confirmation, and the MCP client speaks for a model as often as for a person; a
     * call's {@code _meta} is the executor's to build, and its {@code confirm} would ride in it).
     */
    static Map<String, Object> withoutConfirmOrMeta(Map<String, Object> args) {
        Map<String, Object> copy = new LinkedHashMap<>(args);
        copy.remove(CONFIRM);
        copy.remove(META);
        if (copy.get("args") instanceof Map<?, ?> nested && (nested.containsKey(CONFIRM)
                || nested.containsKey(META))) {
            Map<String, Object> inner = new LinkedHashMap<>();
            nested.forEach((k, v) -> inner.put(String.valueOf(k), v));
            inner.remove(CONFIRM);
            inner.remove(META);
            copy.put("args", inner);
        }
        return copy;
    }
}
