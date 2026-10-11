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
 * The scripted model answers of the sample (phase5-contracts.md 14.4, 14.5): what a scripted gateway answers where a
 * test writes the model's side by hand, correctly, and where the module's recordings are and which settings read them.
 *
 * <ul>
 *   <li>{@link #sessionScript}: the model's side of the session of 14.4, one action per answer. The session's
 *       recordings are made from it ({@code RecordingGateway} around a {@code ScriptedGateway},
 *       {@code -D}{@value #UPDATE}{@code =true} in {@code SessionExportTest}) and stay stand-ins: a live session is a
 *       new session with its own answers, not a refresh of the recorded one, and the image's session check drives the
 *       script by position. A recording made this way names {@code provider: "scripted"} and the model
 *       {@value ScriptedGateway#MODEL}: that is what marks it as a stand-in;</li>
 *   <li>{@link #reject}: a statement of the guard's HSQLDB reject corpus, for the negative;</li>
 *   <li>{@link #triage}: a label and a summary for any failed run (§11), which the image's check records for the runs
 *       it fails itself.</li>
 * </ul>
 *
 * <p>Scenario D's tier (b) requests are not answered here: their recordings are live, made by the record route
 * ({@code -Pinference-record}, {@link ScenarioDRecordIT}; decision D111), and the generator of their stand-ins is gone,
 * so that no update path can replace a live recording with a scripted one (14.2, 4.2).
 *
 * <p>The usage of every answer is that of a scripted call: provider {@value ScriptedGateway#PROVIDER}, cost
 * {@code "0"} (a stand-in costs nothing), and tokens counted as a {@code ScriptedGateway} counts them (text length /
 * 4), so that a recording is a function of its request.
 *
 * <p>Stateless apart from the session script's position; the scripts are not shared between threads.
 */
final class ScriptedAnswers {

    /**
     * The system property of the fixture update path (phase5-contracts.md 4.2): it regenerates what stand-ins still
     * make (the session's recordings and export) and rewrites scenario D's bindings files from the recordings that
     * exist, with their label; it records no tier (b) request.
     */
    static final String UPDATE = "skillroy.inference.fixtures.update";
    /** When the session's stand-in recordings were made, and the clock of everything written from them. */
    static final Instant RECORDED_AT = Instant.parse("2026-10-08T12:00:00Z");
    /** {@link #RECORDED_AT} as a clock. */
    static final Clock CLOCK = Clock.fixed(RECORDED_AT, ZoneOffset.UTC);
    /** The gateway name of settings that only a served gateway answers ({@code Gateways.serve}). */
    static final String SCRIPTED = "scripted";

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

    /** True on the fixture update path ({@code -D}{@value #UPDATE}{@code =true}; see {@link #UPDATE}). */
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

    /** The reject-corpus statement as a proposal, confident: only the guard stops it. */
    static SqlProposal reject() {
        return new SqlProposal(REJECT_SQL, List.of(), "Clears the lines of an old loan before reading.",
                List.of("loan_line"), "", 0.9, new SqlProposal.RowBounds(1, 5), false, "");
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

    /** The vision session's request (phase7-contracts.md 12.4, 13.2 item 8). */
    static final String VISION_REQUEST = "Settle the deposit of this loan: ask for the deposit due, take ten as the"
            + " deposit, and record it.";
    /** The vision session's answer. */
    static final String VISION_ANSWER = "The deposit due was asked for, ten was taken and the loan is recorded.";
    /** The deposit the model types. */
    static final String VISION_DEPOSIT = "10.00";

    /**
     * The model's side of the vision session of phase7-contracts.md 12.4 (V1, the real desk), one answer per call, in
     * order: click the mark of the keypad's {@code DUE} key, type the deposit into the field that has the focus, click
     * the mark of the {@code DEPOSIT} key, and say {@code done}. A mark is named by its label in the marks list of the
     * request's latest capture (the {@code <target-ui>} block), never by a pixel, so that the script holds on every
     * target whatever its pixels. A call beyond the script, or a capture without the mark, is
     * {@code UNSUPPORTED}.
     */
    static ScriptedGateway.Script visionScript() {
        AtomicInteger next = new AtomicInteger();
        return request -> {
            int i = next.getAndIncrement();
            if (!"vision".equals(request.purpose())) {
                throw new InferenceException(InferenceException.Kind.UNSUPPORTED, "the vision script answers vision"
                        + " requests only, not " + request.purpose());
            }
            Object[] step = switch (i) {
                case 0 -> step("I ask for the deposit due.", "click_mark", input("mark", mark(request, "DUE"),
                        "button", null, "count", null));
                case 1 -> step("I take ten as the deposit.", "type", input("text", VISION_DEPOSIT, "enter", null));
                case 2 -> step("I record the deposit.", "click_mark", input("mark", mark(request, "DEPOSIT"), "button",
                        null, "count", null));
                case 3 -> step("The loan is recorded.", "done", input("answer", VISION_ANSWER));
                default -> throw new InferenceException(InferenceException.Kind.UNSUPPORTED, "the vision script has no"
                        + " answer " + (i + 1));
            };
            Content.ToolUse use = new Content.ToolUse("toolu_vision_" + (i + 1), (String) step[1],
                    copy((Map<?, ?>) step[2]));
            return new ModelResponse(List.of(Content.Text.of((String) step[0]), use), ModelResponse.StopReason.TOOL_USE,
                    usage(request, Map.of("text", step[0], "tool", use.name(), "input", use.input())), null);
        };
    }

    /** A mark line of the marks list: {@code mark 7: button DUE}. */
    private static final java.util.regex.Pattern MARK_LINE = java.util.regex.Pattern.compile("mark (\\d+): ([^\\n]*)");

    /**
     * The number of the mark whose label names {@code word} as a whole word, in the latest marks list of the request
     * (the last {@code <target-ui>} block of its messages that holds one).
     *
     * @throws InferenceException {@code UNSUPPORTED} when no mark names it
     */
    static long mark(ModelRequest request, String word) throws InferenceException {
        java.util.regex.Pattern named = java.util.regex.Pattern.compile("\\b" + word + "\\b");
        String marks = null;
        for (Message message : request.messages()) {
            for (Content part : message.parts()) {
                if (part instanceof Content.Text t && t.text().contains("mark ")) {
                    marks = t.text();
                }
            }
        }
        if (marks != null) {
            java.util.regex.Matcher m = MARK_LINE.matcher(marks);
            while (m.find()) {
                if (named.matcher(m.group(2)).find()) {
                    return Long.parseLong(m.group(1));
                }
            }
        }
        throw new InferenceException(InferenceException.Kind.UNSUPPORTED, "no mark of the latest capture names "
                + word);
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
