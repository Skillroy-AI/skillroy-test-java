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
import ai.skillroy.testjava.inference.sql.GuardViolation;
import ai.skillroy.testjava.inference.sql.GuardedStatement;
import ai.skillroy.testjava.inference.sql.SqlGuard;
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
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.lang.reflect.Type;
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
 * {@code Origin.person("mcp")}; a {@code confirm} and a {@code _meta} in the client's arguments are dropped (design
 * 3.4: the server never confirms on a model's behalf).
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

    /** A pack verb through the session entry: the step protocol, the prompt policy and the journal apply. */
    Reply verb(String verb, Map<String, Object> args) throws AgentLostException, InterruptedException {
        Map<String, Object> schema = null;
        try {
            schema = pack().steps().argsSchema(verb);
        } catch (RuntimeException e) {
            // A pack that cannot describe a verb's arguments: none are dropped.
        }
        Object given = withoutConfirmOrMeta(Arguments.dropNullOptionals(args, schema));
        StepOutcome outcome = session().execute(verb, given, SkillroyMcpServer.ORIGIN);
        return step(outcome, "verb " + verb);
    }

    /** A primitive as one raw call of the session ({@code data.query} through {@link #dataQuery}). */
    Reply primitive(PrimitiveDescriptor d, Map<String, Object> args) throws AgentLostException, InterruptedException,
            ToolError {
        Map<String, Object> params = withoutConfirmOrMeta(Arguments.dropNullOptionals(args, d.paramsSchema()));
        if (SkillroyMcpServer.DATA_QUERY.equals(d.name())) {
            return dataQuery(params);
        }
        PlannedCall call = new PlannedCall(d.name(), params, syncFor(d), timeoutFor(d));
        return step(session().execute(call, SkillroyMcpServer.ORIGIN), d.name());
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
     */
    private Reply dataQuery(Map<String, Object> params) throws AgentLostException, InterruptedException, ToolError {
        String source = Arguments.text(params, "source", true);
        String sql = Arguments.text(params, "sql", true);
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
        StepOutcome outcome = session().execute(new PlannedCall(SkillroyMcpServer.DATA_QUERY, sent, Sync.NONE, 0L),
                SkillroyMcpServer.ORIGIN);
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
        return reply(json, !outcome.succeeded(), List.of());
    }

    /**
     * A step's answer: its outcome as JSON, the last call's value with any image written out and linked.
     *
     * @param what the primitive or verb, for the image's description
     */
    private Reply step(StepOutcome outcome, String what) {
        Map<String, Object> json = stepJson(outcome);
        List<McpSchema.Content> links = new ArrayList<>();
        Object value = outcome.lastResult() == null ? null : JsonForm.of(outcome.lastResult().value());
        json.put("value", images(value, links, what + " (step " + outcome.stepId() + ")", 0));
        return reply(json, !outcome.succeeded(), links);
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
     * The value with every image in it (a map with {@code format: png} and {@code base64}: a capture, a bitmap) written
     * to {@code <out>/mcp/<n>.png} and replaced by a reference to its resource link, which is added to {@code links}.
     */
    private Object images(Object value, List<McpSchema.Content> links, String what, int depth) {
        if (depth > 8) {
            return value;
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
        for (Map.Entry<String, Capability> e : server.capabilities().all().entrySet()) {
            caps.put(e.getKey(), e.getValue().toJson());
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
        return reply(json, false, List.of());
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
        return reply(json, false, List.of());
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
                actor.resolve(session(), slot, kind, queryArgs, strategy, SkillroyMcpServer.ORIGIN);
            } else {
                session().resolve(slot, kind, queryArgs, strategy, null, null, SkillroyMcpServer.ORIGIN);
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
        return reply(json, false, List.of());
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

    /**
     * {@code skillroy_scenario_run} (mutating only): the scenario compiled, checked against what this server offers
     * ({@link PlanPolicy}, D96: refused as a tool error naming each call that may not run, before anything runs), and
     * run once with {@link PlanExecutor} in {@code live} (nothing written) or {@code approve} (the session's approver
     * and bindings root decide), with recover and every expectation on masked values
     * ({@code RunOptions.withExpectationsOnMaskedValues}, so that PASS or FAIL never answers whether a credential
     * starts with a prefix), its evidence in {@code <out>/runs/<run id>/}; the run's summary.
     */
    Reply run(Map<String, Object> args) throws AgentLostException, ToolError {
        String yaml = Arguments.text(args, "yaml", true);
        String mode = Arguments.text(args, "mode", true);
        BindMode bindMode = switch (mode) {
            case "live" -> BindMode.LIVE;
            case "approve" -> BindMode.APPROVE;
            default -> throw new ToolError("skillroy_scenario_run: mode is live or approve, found '" + mode + "'");
        };
        Plan plan;
        try {
            plan = PlanCompiler.compile(ScenarioLoader.load(yaml.getBytes(StandardCharsets.UTF_8), "scenario.yaml"),
                    pack());
        } catch (ScenarioException e) {
            throw new ToolError("skillroy_scenario_run: the scenario does not compile: " + e.getMessage());
        }
        List<String> refusals = server.planPolicy().refusals(plan);
        if (!refusals.isEmpty()) {
            throw new ToolError("skillroy_scenario_run: the scenario is refused, nothing ran (this server runs only"
                    + " what it offers as tools): " + String.join("; ", refusals));
        }
        String runId = server.nextRunId();
        Path dir = server.outDir().resolve("runs").resolve(runId);
        BindOptions bind = session().options().bind().withMode(bindMode).withWriteBindings(false);
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withBind(bind).withClock(server.clock())
                .withExpectationsOnMaskedValues(true).withObserver(session().observer());
        PlanExecutor executor = new PlanExecutor(pack(), server.agent(), options,
                session().options().webPathLoader());
        PlanExecutor.Resolved ran = executor.runWithOutcome(plan);
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
        return reply(json, !passed, List.of());
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
        return reply(JsonForm.of(answer), false, List.of());
    }

    /** {@code skillroy_session_check}: an expectation evaluated now and journaled. */
    Reply check(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        String path = Arguments.text(args, "path", true);
        Object matcher = args.get("matcher");
        CheckOutcome outcome = session().check(path, matcher, SkillroyMcpServer.ORIGIN);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", outcome.path());
        json.put("matcher", outcome.matcher());
        json.put("held", outcome.held());
        json.put("actual", outcome.actual());
        json.put("reason", outcome.reason());
        json.put("durationMs", outcome.duration().toMillis());
        return reply(json, false, List.of());
    }

    /** {@code skillroy_session_observe}: a value read now and journaled as observed. */
    Reply observe(Map<String, Object> args) throws AgentLostException, InterruptedException, ToolError {
        String path = Arguments.text(args, "path", true);
        Object value;
        try {
            value = session().observe(path, SkillroyMcpServer.ORIGIN);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolError("skillroy_session_observe: " + e.getMessage());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", path);
        json.put("value", value);
        json.put("note", "journaled as observed: an export marks it for the reviewer to derive or confirm");
        return reply(json, false, List.of());
    }

    /** {@code skillroy_session_note}: a note in the journal. */
    Reply note(Map<String, Object> args) throws ToolError {
        String text = Arguments.text(args, "text", true);
        session().note(text, SkillroyMcpServer.ORIGIN);
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
     * approves with {@code skillroy resolve}, which reuses the recorded value without a model call (D82).
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
        try {
            Plan plan = PlanCompiler.compile(yaml, scenarioId + ".yaml", pack());
            Files.createDirectories(out);
            Files.write(yamlFile, yaml);
            plan.write(planFile);
            if (bindingsFile != null) {
                result.bindings().write(bindingsFile);
            }
        } catch (ScenarioException e) {
            throw new ToolError("skillroy_session_export: the exported scenario does not compile: " + e.getMessage());
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

    /** Masked pretty JSON, cut. */
    private Reply reply(Object value, boolean error, List<McpSchema.Content> extra) {
        return new Reply(Masking.json(masking().value(value)), extra, error);
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
