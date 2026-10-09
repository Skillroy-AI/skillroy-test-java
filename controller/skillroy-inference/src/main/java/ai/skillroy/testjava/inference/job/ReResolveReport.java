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

package ai.skillroy.testjava.inference.job;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.FailureKind;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@code re-resolve} job did (phase5-contracts.md 8.2 step 5), for a person who reviews it as a pull request:
 * per scenario {@link Status#UNCHANGED}, {@link Status#CHANGED} (one line per change against the previous bindings
 * file), {@link Status#NEW}, {@link Status#FAILED} (the binder's failure kind and reason, {@code BUDGET} included, or
 * the job's own reason {@value #REASON_BATCH_TIMEOUT}) or {@link Status#SKIPPED} (the scenario needs no bindings);
 * the job's model cost against its budget, the rounds, batches and requests, the cache-hit ratio over the job's ledger,
 * the model ids, notes, and the reviewer's instruction. It also keeps the lines of the job's {@code cost-ledger.jsonl}
 * and {@code inference.jsonl}. Every text in it was masked by the job (the credentials' values); nothing in it is a
 * prompt.
 *
 * <p>{@link #writeTo} writes {@value #MARKDOWN_FILE} (the pull request's body), {@value #JSON_FILE},
 * {@value #LEDGER_FILE} and {@value #INFERENCE_FILE}.
 *
 * <p>Immutable (lists and maps are copied); safe to share between threads.
 *
 * @param jobId the job's id
 * @param envId the environment
 * @param dryRun true when nothing was written
 * @param approvedBy the label the files were (or would have been) approved by
 * @param provider the gateway's provider ({@code claude}, {@code recorded}, …)
 * @param startedAt when the job started, by its clock
 * @param duration how long it took, by its clock
 * @param scenarios one report per plan, in the order given
 * @param costUsd what the job's model calls cost (its ledger)
 * @param budgetUsd the job budget
 * @param rounds how many rounds of parked scenarios there were
 * @param batches how many batches were submitted
 * @param requests how many model requests the batches (or, without a batch interface, the single calls) held
 * @param cacheHitRatio {@code cacheReadTokens / (inputTokens + cacheReadTokens)} over the job's ledger, 4 decimals;
 *     null when no input was sent
 * @param models the model ids that answered, in first-seen order
 * @param notes what else a reviewer should know (a gateway without a batch interface, a cancelled batch), masked
 * @param ledger the lines of the job's {@code cost-ledger.jsonl}, masked
 * @param inference the lines of the job's {@code inference.jsonl}, masked
 */
public record ReResolveReport(String jobId, String envId, boolean dryRun, String approvedBy, String provider,
                              Instant startedAt, Duration duration, List<ScenarioReport> scenarios, BigDecimal costUsd,
                              BigDecimal budgetUsd, int rounds, int batches, int requests, BigDecimal cacheHitRatio,
                              List<String> models, List<String> notes, List<Map<String, Object>> ledger,
                              List<Map<String, Object>> inference) {

    /** The report a person reads (the pull request's body). */
    public static final String MARKDOWN_FILE = "re-resolve-report.md";
    /** The same report as JSON. */
    public static final String JSON_FILE = "re-resolve-report.json";
    /** The job's ledger. */
    public static final String LEDGER_FILE = "cost-ledger.jsonl";
    /** The job's record of its model calls. */
    public static final String INFERENCE_FILE = "inference.jsonl";
    /** The job's own reason for a scenario whose batch did not end in time (not a {@code FailureKind}). */
    public static final String REASON_BATCH_TIMEOUT = "BATCH_TIMEOUT";
    /** The reason of a scenario the job budget cut (the binder's {@code RESOLUTION_FAILED} reason as well). */
    public static final String REASON_BUDGET = "BUDGET";
    /** The reason of a scenario a failed pre-flight check stopped. */
    public static final String REASON_PREFLIGHT = "PREFLIGHT";
    /** What the reviewer is told. */
    public static final String REVIEWER_INSTRUCTION = "Approve by merging; a slot's statement and rationale are in the"
            + " file's provenance.";

    private static final int CHANGE_VALUE_MAX = 200;

    /** Where a scenario stands at the end of the job. */
    public enum Status {
        /** The bindings file is byte-identical to the one it had (it was left untouched). */
        UNCHANGED,
        /** The bindings file differs from the one it had; {@link ScenarioReport#changes()} says how. */
        CHANGED,
        /** There was no bindings file (or it could not be read); one was written. */
        NEW,
        /** The scenario was not resolved; {@link ScenarioReport#failureKind()} and {@code reason} say why. */
        FAILED,
        /** The scenario needs no bindings (no query slot, no oracle). */
        SKIPPED
    }

    /**
     * One scenario of the job.
     *
     * @param scenarioId the scenario
     * @param status where it stands
     * @param bindingsFile {@code <envId>/<scenarioId>.bindings.json}; null when skipped
     * @param written true when the job wrote the file (never in a dry run; not for an identical file)
     * @param parked how many times the bind parked for a model's answer (its rounds)
     * @param costUsd what its model calls cost (the job's ledger entries that name it)
     * @param changes one line per change ({@code resolutions.supervisor.value: … -> …}), for {@code CHANGED}; for
     *     {@code NEW}, one line per slot with its value
     * @param failureKind the binder's failure kind, for {@code FAILED} ({@code ENVIRONMENT} for a batch timeout)
     * @param reason the binder's reason ({@code BUDGET}, {@code NO_CANDIDATE}, …) or the job's
     *     ({@value #REASON_BATCH_TIMEOUT}, {@value #REASON_PREFLIGHT}), for {@code FAILED}; may be null
     * @param message one line, masked, for {@code FAILED}
     */
    public record ScenarioReport(String scenarioId, Status status, String bindingsFile, boolean written, int parked,
                                 BigDecimal costUsd, List<String> changes, FailureKind failureKind, String reason,
                                 String message) {

        /** Copies the changes; a null cost is zero. */
        public ScenarioReport {
            Objects.requireNonNull(scenarioId, "scenarioId");
            Objects.requireNonNull(status, "status");
            changes = changes == null ? List.of() : List.copyOf(changes);
            costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        }

        /** The JSON form of the report's {@code scenarios} entries. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("scenarioId", scenarioId);
            json.put("status", status.name());
            json.put("bindingsFile", bindingsFile);
            json.put("written", written);
            json.put("parked", (long) parked);
            json.put("costUsd", costUsd.toPlainString());
            json.put("changes", new ArrayList<>(changes));
            json.put("failureKind", failureKind == null ? null : failureKind.name());
            json.put("reason", reason);
            json.put("message", message);
            return json;
        }
    }

    /** Copies the lists. */
    public ReResolveReport {
        Objects.requireNonNull(jobId, "jobId");
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        models = models == null ? List.of() : List.copyOf(models);
        notes = notes == null ? List.of() : List.copyOf(notes);
        ledger = ledger == null ? List.of() : List.copyOf(ledger);
        inference = inference == null ? List.of() : List.copyOf(inference);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
        duration = duration == null ? Duration.ZERO : duration;
    }

    /** True when any scenario failed: the command's exit code 1 (phase5-contracts.md 8.2 step 6). */
    public boolean anyFailed() {
        return count(Status.FAILED) > 0;
    }

    /** 0 when every scenario resolved (changed or not) or was skipped; 1 when any failed. */
    public int exitCode() {
        return anyFailed() ? 1 : 0;
    }

    /** How many scenarios have this status. */
    public int count(Status status) {
        int n = 0;
        for (ScenarioReport s : scenarios) {
            if (s.status() == status) {
                n++;
            }
        }
        return n;
    }

    /** The report of one scenario, or null. */
    public ScenarioReport scenario(String scenarioId) {
        for (ScenarioReport s : scenarios) {
            if (s.scenarioId().equals(scenarioId)) {
                return s;
            }
        }
        return null;
    }

    /** {@code model cost: $0.0231 (budget $1.00)}: the line every command prints (phase5-contracts.md 7.2). */
    public String costLine() {
        return "model cost: $" + costUsd.toPlainString() + " (budget $" + (budgetUsd == null ? "none"
                : budgetUsd.toPlainString()) + ")";
    }

    /** The JSON report ({@value #JSON_FILE}); a new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("jobId", jobId);
        json.put("envId", envId);
        json.put("dryRun", dryRun);
        json.put("approvedBy", approvedBy);
        json.put("provider", provider);
        json.put("startedAt", startedAt == null ? null : startedAt.toString());
        json.put("durationMs", duration.toMillis());
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            summary.put(s.name().toLowerCase(java.util.Locale.ROOT), (long) count(s));
        }
        json.put("summary", summary);
        List<Object> list = new ArrayList<>();
        for (ScenarioReport s : scenarios) {
            list.add(s.toJson());
        }
        json.put("scenarios", list);
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("spentUsd", costUsd.toPlainString());
        cost.put("budgetUsd", budgetUsd == null ? null : budgetUsd.toPlainString());
        json.put("cost", cost);
        json.put("rounds", (long) rounds);
        json.put("batches", (long) batches);
        json.put("requests", (long) requests);
        json.put("cacheHitRatio", cacheHitRatio == null ? null : cacheHitRatio.toPlainString());
        json.put("models", new ArrayList<>(models));
        json.put("notes", new ArrayList<>(notes));
        json.put("instruction", REVIEWER_INSTRUCTION);
        json.put("exitCode", (long) exitCode());
        return json;
    }

    /** The Markdown report ({@value #MARKDOWN_FILE}): what a pull request carries as its body. */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Re-resolve report: job ").append(code(jobId)).append("\n\n");
        md.append("Environment ").append(code(envId)).append(", ").append(scenarios.size()).append(" scenario")
                .append(scenarios.size() == 1 ? "" : "s").append(": ").append(count(Status.UNCHANGED))
                .append(" unchanged, ").append(count(Status.CHANGED)).append(" changed, ").append(count(Status.NEW))
                .append(" new, ").append(count(Status.FAILED)).append(" failed, ").append(count(Status.SKIPPED))
                .append(" skipped.");
        if (dryRun) {
            md.append(" Dry run: no bindings file was written; the report says what would change.");
        }
        md.append("\n\n");
        md.append(capitalised(costLine())).append("; ").append(rounds).append(rounds == 1 ? " round, " : " rounds, ")
                .append(batches).append(batches == 1 ? " batch, " : " batches, ").append(requests)
                .append(requests == 1 ? " request" : " requests").append("; cache hits ")
                .append(cacheHitRatio == null ? "n/a" : percent(cacheHitRatio)).append("; provider ")
                .append(code(provider)).append("; models: ").append(models.isEmpty() ? "none"
                        : String.join(", ", models.stream().map(ReResolveReport::code).toList()))
                .append(". Approved by ").append(code(approvedBy)).append(".\n");
        section(md, "Changed", Status.CHANGED, true);
        section(md, "New", Status.NEW, true);
        if (count(Status.FAILED) > 0) {
            md.append("\n## Failed\n\n");
            for (ScenarioReport s : scenarios) {
                if (s.status() == Status.FAILED) {
                    md.append("- ").append(code(s.scenarioId())).append(": ")
                            .append(s.failureKind() == null ? "FAILED" : s.failureKind().name())
                            .append(s.reason() == null ? "" : " " + code(s.reason()))
                            .append(s.message() == null ? "" : ": " + code(s.message(), TEXT_MAX)).append('\n');
                }
            }
        }
        section(md, "Unchanged", Status.UNCHANGED, false);
        section(md, "Skipped (no bindings needed)", Status.SKIPPED, false);
        if (!notes.isEmpty()) {
            md.append("\n## Notes\n\n");
            for (String note : notes) {
                md.append("- ").append(code(note, TEXT_MAX)).append('\n');
            }
        }
        md.append("\n## For the reviewer\n\n").append(REVIEWER_INSTRUCTION).append('\n');
        return md.toString();
    }

    private void section(StringBuilder md, String title, Status status, boolean details) {
        if (count(status) == 0) {
            return;
        }
        md.append("\n## ").append(title).append("\n\n");
        for (ScenarioReport s : scenarios) {
            if (s.status() != status) {
                continue;
            }
            md.append("- ").append(code(s.scenarioId()));
            if (s.bindingsFile() != null) {
                md.append(" (").append(code(s.bindingsFile())).append(s.written() ? ", written" : "").append(')');
            }
            md.append('\n');
            if (details) {
                for (String change : s.changes()) {
                    md.append("  - ").append(code(oneLine(change))).append('\n');
                }
            }
        }
    }

    /**
     * Writes {@value #MARKDOWN_FILE}, {@value #JSON_FILE}, {@value #LEDGER_FILE} and {@value #INFERENCE_FILE} into the
     * directory (created when needed), replacing them.
     *
     * @throws IOException if a file cannot be written
     */
    public void writeTo(Path dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MARKDOWN_FILE), toMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(JSON_FILE), Json.writePretty(toJson()), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(LEDGER_FILE), lines(ledger), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(INFERENCE_FILE), lines(inference), StandardCharsets.UTF_8);
    }

    private static String lines(List<Map<String, Object>> records) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> record : records) {
            text.append(Json.write(record)).append('\n');
        }
        return text.toString();
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + " %";
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** A text in backticks, with a backtick in it replaced (the report is Markdown a pull request renders). */
    private static String code(String text) {
        return code(text, CHANGE_VALUE_MAX * 3);
    }

    /**
     * A text in backticks, on one line, a backtick in it replaced and cut at {@code max} characters: every text a
     * model or the binder wrote (a failure's message, a note) is rendered as code, so that its markup (a link, an
     * image, a heading) is inert in the pull request (phase6 security fix S50).
     */
    private static String code(String text, int max) {
        if (text == null) {
            return "`-`";
        }
        String t = oneLine(text).replace('`', '\'');
        return "`" + (t.length() > max ? t.substring(0, max - 3) + "..." : t) + "`";
    }

    /** The most characters of a failure's message or a note in the Markdown report. */
    private static final int TEXT_MAX = 1000;

    private static String oneLine(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }
}
