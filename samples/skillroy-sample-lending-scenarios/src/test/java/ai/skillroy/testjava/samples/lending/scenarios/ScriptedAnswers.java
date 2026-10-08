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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.inference.resolve.SqlProposal;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Structured;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.inference.triage.TriageLabel;
import ai.skillroy.testjava.inference.triage.TriageSummary;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The stand-in model answers of the sample (phase5-contracts.md 14.2, 14.4, 14.5): what a scripted gateway answers
 * where the build would otherwise need a live model, written by hand to be correct, so that the recordings made from
 * them ({@code RecordingGateway} around a {@code ScriptedGateway}, {@code -D}{@value #UPDATE}{@code =true}) have the
 * real format and the whole tier (b) and session paths run for real with no key. A recording made this way names
 * {@code provider: "scripted"} and the model {@value ScriptedGateway#MODEL}: that is what marks it as a stand-in.
 *
 * <p>Once a model key can be used, scenario D's tier (b) requests can be recorded live through the route that exists,
 * {@code record: true} in an {@code inference.yaml} with the live gateway (a {@code RecordingGateway} around it,
 * {@code Gateways.build}; {@code -Dskillroy.inference.record.overwrite=true} to replace a file); the profile
 * {@code inference-record} is specified, not built (phase5-contracts.md D110). That is not "no code changes": the
 * recordings and the bindings files written from them then carry another provider, model, statement, rationale, usage
 * and label, and the assertions that pin the stand-ins (listed in the recordings' README) are loosened in the same
 * change, {@link #APPROVED_BY} included. The session's recordings stay stand-ins: a live session is a new session, and
 * the image's session check drives {@link #sessionScript()} by position.
 *
 * <ul>
 *   <li>{@link #supervisor}: scenario D's supervisor query on each seed, one {@code SELECT} over the allow-listed
 *       tables that finds every active staff member, other than the assistant, who may authorise a return of the
 *       item's deposit (they hold {@code RETURN_OVERRIDE} through a role, and their {@code RETURN} limit covers the
 *       deposit or they have none), selecting the identity column {@code code} of the pack's value loader, every
 *       value from the query's arguments bound as a {@code ?} parameter;</li>
 *   <li>{@link #sessionScript}: the model's side of the session of 14.4, one action per answer;</li>
 *   <li>{@link #reject}: a statement of the guard's HSQLDB reject corpus, for the negative;</li>
 *   <li>{@link #triage}: a label and a summary for any failed run (§11).</li>
 * </ul>
 *
 * <p>The usage of every answer is that of a scripted call: provider {@value ScriptedGateway#PROVIDER}, cost
 * {@code "0"} (a stand-in costs nothing), and tokens counted as a {@code ScriptedGateway} counts them (text length /
 * 4), so that a recording is a function of its request.
 *
 * <p>Stateless apart from the session script's position; the scripts are not shared between threads.
 */
final class ScriptedAnswers {

    /** The system property that regenerates the sample's recordings and what is written from them. */
    static final String UPDATE = "skillroy.inference.fixtures.update";
    /** When the stand-in recordings were made, and the clock of everything written from them. */
    static final Instant RECORDED_AT = Instant.parse("2026-10-08T12:00:00Z");
    /** {@link #RECORDED_AT} as a clock. */
    static final Clock CLOCK = Clock.fixed(RECORDED_AT, ZoneOffset.UTC);
    /** The {@code approval.by} of scenario D's committed bindings files. */
    static final String APPROVED_BY = "recorded 2026-10-08 (scripted stand-in)";
    /** The gateway name of settings that only a served gateway answers ({@code Gateways.serve}). */
    static final String SCRIPTED = "scripted";

    /**
     * Scenario D's supervisor query as one statement (HSQLDB, the pack's allow-list): parameters, in order, the
     * assistant to exclude, the permission, the limit kind, the deposit, the limit kind again.
     */
    static final String SUPERVISOR_SQL = "SELECT s.code AS code FROM staff s WHERE s.active = TRUE AND s.code <> ?"
            + " AND EXISTS (SELECT sr.staff_code FROM staff_role sr JOIN role_permission rp ON rp.role_id = sr.role_id"
            + " WHERE sr.staff_code = s.code AND rp.permission = ?)"
            + " AND ((SELECT MAX(rc.amount) FROM staff_role sr2 JOIN role_cap rc ON rc.role_id = sr2.role_id"
            + " WHERE sr2.staff_code = s.code AND rc.kind = ?) >= CAST(? AS DECIMAL(10,2))"
            + " OR NOT EXISTS (SELECT sr3.staff_code FROM staff_role sr3 JOIN role_cap rc3 ON rc3.role_id = sr3.role_id"
            + " WHERE sr3.staff_code = s.code AND rc3.kind = ?))"
            + " ORDER BY s.code";

    /** Why the statement answers the question, as a model would say it. */
    static final String SUPERVISOR_RATIONALE = "A supervisor for this return is an active staff member other than"
            + " the one in distinctFrom who holds RETURN_OVERRIDE through any of their roles and whose RETURN limit"
            + " covers the amount: the highest RETURN amount over their roles is at least limit.gte, or none of their"
            + " roles has a RETURN limit, which the desk treats as unlimited. One row per staff member, by code.";

    /** A statement of the HSQLDB reject corpus ({@code hsqldb-reject.sql}, "DML DELETE"): the guard refuses it. */
    static final String REJECT_SQL = "DELETE FROM loan_line WHERE loan_id = 101";
    /** The guard's reason code for {@link #REJECT_SQL}. */
    static final String REJECT_REASON = "DML";

    /** What the person asks the model in the session of 14.4. */
    static final String SESSION_REQUEST = "Lend one plain item to the walk-in borrower and settle the deposit.";
    /** The model's answer at the end of that turn. */
    static final String SESSION_ANSWER = "One plain item, the cheapest counted one with a deposit between 1.00 and"
            + " 2.00, is lent to the walk-in borrower and its deposit is settled; the desk waits for the next loan.";

    private ScriptedAnswers() {
    }

    /** True when the build regenerates the recordings ({@code -D}{@value #UPDATE}{@code =true}). */
    static boolean update() {
        return Boolean.getBoolean(UPDATE);
    }

    /** The module's recordings: {@code src/test/resources/inference/}. */
    static Path recordings() {
        return ItEnv.moduleDir().resolve("src").resolve("test").resolve("resources").resolve("inference");
    }

    /** Settings of the recorded gateway over {@code dir}: the defaults otherwise (budgets, threshold, models). */
    static InferenceSettings recorded(Path dir) {
        return new InferenceSettings(InferenceSettings.GATEWAY_RECORDED, dir, false, null, null, null, null, null,
                null, true, false, null, false, null);
    }

    /** Settings that name {@value #SCRIPTED}: answered only while a gateway serves them ({@code Gateways.serve}). */
    static InferenceSettings scripted() {
        return new InferenceSettings(SCRIPTED, null, false, null, null, null, null, null, null, true, false, null,
                false, null);
    }

    // ------------------------------------------------------------------------------------------------ usage

    /** The characters of a request's text parts, tool results and tool definitions. */
    static long chars(ModelRequest request) {
        long chars = 0;
        for (Content part : request.system()) {
            chars += part instanceof Content.Text t ? t.text().length() : 0;
        }
        for (Message message : request.messages()) {
            for (Content part : message.parts()) {
                chars += switch (part) {
                    case Content.Text t -> t.text().length();
                    case Content.ToolResult r -> String.valueOf(r.content()).length();
                    case Content.ToolUse u -> Json.write(u.input()).length();
                    default -> 0;
                };
            }
        }
        for (ToolSpec tool : request.tools()) {
            chars += tool.description().length() + Json.write(tool.inputSchema()).length();
        }
        if (request.outputSchema() != null) {
            chars += Json.write(request.outputSchema()).length();
        }
        return chars;
    }

    /** A scripted call's usage for this request and answer: tokens as text length / 4, cost {@code "0"}. */
    static Usage usage(ModelRequest request, Object answer) {
        return ScriptedGateway.usage(Math.max(1, chars(request) / 4), Math.max(1, Json.write(answer).length() / 4),
                "0");
    }

    /** A structured answer with the usage of {@link #usage}. */
    static ModelResponse structured(ModelRequest request, Object answer) {
        Object json = Structured.encode(answer);
        return ScriptedGateway.structured(json, usage(request, json));
    }

    // ------------------------------------------------------------------------------------------------ tier (b)

    /** Scenario D's supervisor query, answered correctly for the seed whose arguments it carries. */
    static SqlProposal supervisor(Env env) {
        ScenarioA.Expected expected = ScenarioA.expected(env);
        String deposit = String.valueOf(expected.item().get("deposit"));
        return new SqlProposal(SUPERVISOR_SQL, List.of(
                new SqlProposal.Param("distinctFrom.0", expected.assistantCode(), "STRING"),
                new SqlProposal.Param("perform.0", "RETURN_OVERRIDE", "STRING"),
                new SqlProposal.Param("limit.type", "RETURN", "STRING"),
                new SqlProposal.Param("limit.gte", deposit, "NUMBER"),
                new SqlProposal.Param("limit.type", "RETURN", "STRING")),
                SUPERVISOR_RATIONALE, List.of("staff", "staff_role", "role_permission", "role_cap"), "s.code", 0.9,
                new SqlProposal.RowBounds(1, 5), false, "");
    }

    /** The reject-corpus statement as a proposal, confident: only the guard stops it. */
    static SqlProposal reject() {
        return new SqlProposal(REJECT_SQL, List.of(), "Clears the lines of an old loan before reading.",
                List.of("loan_line"), "", 0.9, new SqlProposal.RowBounds(1, 5), false, "");
    }

    /** The arguments of a tier (b) request, from its user message ({@code Arguments of the query (JSON):}). */
    static Map<String, Object> arguments(ModelRequest request) {
        String marker = "Arguments of the query (JSON):\n";
        for (Message message : request.messages()) {
            for (Content part : message.parts()) {
                if (part instanceof Content.Text t && t.text().contains(marker)) {
                    String json = t.text().substring(t.text().indexOf(marker) + marker.length()).strip();
                    int end = json.indexOf('\n');
                    try {
                        return Json.parseObject(end < 0 ? json : json.substring(0, end));
                    } catch (JsonException e) {
                        return Map.of();
                    }
                }
            }
        }
        return Map.of();
    }

    /**
     * The tier (b) script: scenario D's supervisor query on the seed its arguments name (the assistant it excludes),
     * correctly; any other request is {@code UNSUPPORTED}, so that a request the sample does not expect is seen.
     */
    static ModelResponse resolve(ModelRequest request) throws InferenceException {
        if ("resolve".equals(request.purpose()) && text(request).contains("Query kind: StaffWithPermission")
                && arguments(request).get("distinctFrom") instanceof List<?> excluded && !excluded.isEmpty()) {
            for (Env env : Env.values()) {
                if (ScenarioA.expected(env).assistantCode().equals(String.valueOf(excluded.get(0)))) {
                    return structured(request, supervisor(env));
                }
            }
        }
        throw new InferenceException(InferenceException.Kind.UNSUPPORTED, "no scripted answer for this "
                + request.purpose() + " request");
    }

    /** Every tier (b) request answered with {@link #reject()}. */
    static ModelResponse rejecting(ModelRequest request) {
        return structured(request, reject());
    }

    // ------------------------------------------------------------------------------------------------ session

    /**
     * The model's side of the session of 14.4, one answer per call, in order: resolve a plain item (counted, a deposit
     * between 1.00 and 2.00), scan it, press DUE, take the deposit due, and say {@code done}. Each answer is a short
     * text (the intent the journal keeps) and one tool call; the tool ids are fixed. A call beyond the script is
     * {@code UNSUPPORTED}.
     */
    static ScriptedGateway.Script sessionScript() {
        List<Object[]> steps = List.of(
                step("Find a plain item the desk lends first, cheap and counted.", "skillroy_resolve",
                        input("slot", "item", "kind", "LendableItem", "args", input("deposit",
                                input("between", List.of("1.00", "2.00")), "perDay", false), "strategy", null)),
                step("Scan the item.", "lending_scan", input("item", "$item", "barcode", null, "times", null)),
                step("Ask for the deposit due.", "lending_key", input("action", "DUE", "value", null)),
                step("Take exactly the deposit due.", "lending_deposit", input("amount", null)),
                step("The loan is recorded.", "done", input("answer", SESSION_ANSWER)));
        AtomicInteger next = new AtomicInteger();
        return request -> {
            int i = next.getAndIncrement();
            if (!"session".equals(request.purpose()) || i >= steps.size()) {
                throw new InferenceException(InferenceException.Kind.UNSUPPORTED, "the session script has no answer "
                        + (i + 1) + " for a " + request.purpose() + " request");
            }
            Object[] step = steps.get(i);
            Content.ToolUse use = new Content.ToolUse("toolu_scripted_" + (i + 1), (String) step[1],
                    copy((Map<?, ?>) step[2]));
            List<Content> parts = List.of(Content.Text.of((String) step[0]), use);
            return new ModelResponse(parts, ModelResponse.StopReason.TOOL_USE, usage(request, Map.of("text", step[0],
                    "tool", use.name(), "input", use.input())), null);
        };
    }

    private static Object[] step(String intent, String tool, Map<String, Object> input) {
        return new Object[] {intent, tool, input};
    }

    /** A JSON-like map that may hold nulls (a strict tool's optional keys arrive as null), in the order given. */
    static Map<String, Object> input(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> m = new LinkedHashMap<>();
        map.forEach((k, v) -> m.put(String.valueOf(k), v instanceof Map<?, ?> inner ? copy(inner)
                : v instanceof List<?> list ? new ArrayList<>(list) : v));
        return m;
    }

    // ------------------------------------------------------------------------------------------------ triage

    /**
     * A label ({@code HAIKU_CLASS}) and a summary ({@code SONNET_CLASS}) for any failed run, read from the failure kind
     * the run itself wrote into the request's evidence: a binding the target no longer holds or a query nothing
     * answers is {@code DATA_DRIFT}, an oracle that disagrees is {@code SCENARIO_DEFECT}, a failed expectation
     * {@code APPLICATION_DEFECT}, a failed pre-flight or capability check {@code ENVIRONMENT}.
     */
    static ModelResponse triage(ModelRequest request) throws InferenceException {
        if (!"triage".equals(request.purpose())) {
            throw new InferenceException(InferenceException.Kind.UNSUPPORTED, "no scripted answer for a "
                    + request.purpose() + " request");
        }
        String text = text(request);
        String kind = failureKind(text);
        if (request.modelClass() == ModelClass.HAIKU_CLASS) {
            TriageLabel.Label label = switch (kind) {
                case "REBIND_NEEDED", "RESOLUTION_FAILED" -> TriageLabel.Label.DATA_DRIFT;
                case "ORACLE_SUSPECT", "ORACLE_CHANGED" -> TriageLabel.Label.SCENARIO_DEFECT;
                case "ASSERTION" -> TriageLabel.Label.APPLICATION_DEFECT;
                case "ENVIRONMENT", "PRECONDITION_FAILED" -> TriageLabel.Label.ENVIRONMENT;
                case "SYNC_TIMEOUT" -> TriageLabel.Label.TIMING;
                default -> TriageLabel.Label.UNKNOWN;
            };
            return structured(request, new TriageLabel(label, 0.7, "The run classified itself " + kind
                    + "; the evidence points at " + label.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                    + "."));
        }
        return structured(request, new TriageSummary("The run ended with its own classification " + kind + ". The"
                + " evidence shows where the run stopped: the bind or step record that failed, with the target's"
                + " answer in its detail. Nothing was applied; the classification stands.",
                List.of("run.jsonl: the record with ok false", "failure/: the evidence files"), ""));
    }

    /** The failure kind the run wrote ({@code "failureKind": "…"} in the evidence), or {@code UNKNOWN}. */
    static String failureKind(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"failureKind\"\\s*:\\s*\"([A-Z_]+)\"")
                .matcher(text);
        return m.find() ? m.group(1) : "UNKNOWN";
    }

    /** Every text part of a request's messages, joined. */
    static String text(ModelRequest request) {
        StringBuilder out = new StringBuilder();
        for (Message message : request.messages()) {
            for (Content part : message.parts()) {
                if (part instanceof Content.Text t) {
                    out.append(t.text()).append('\n');
                }
            }
        }
        return out.toString();
    }
}
