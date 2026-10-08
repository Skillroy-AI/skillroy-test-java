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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.triage.Triage;
import ai.skillroy.testjava.inference.triage.TriageOptions;
import ai.skillroy.testjava.inference.triage.TriageResult;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code skillroy triage} (phase5-contracts.md 11): after the fact, for each finished run directory whose
 * {@code run.jsonl} ends {@code FAIL} or {@code ERROR}, a model's label of the failure and, unless
 * {@code --labels-only}, a summary for the person who reviews it ({@link Triage#run}): {@code failure/triage.json} and
 * {@code failure/triage.md}, and the command's {@code cost-ledger.jsonl} and {@code inference.jsonl} under
 * {@code <run dir>/triage/} (the masked prompts in it only with {@code --log-prompts}; the options are the settings'
 * through {@link TriageOptions#of}: the prompt logging, {@code effort.triage} and the model ids). A
 * {@code PASS} or {@code DEGRADED} run prints "nothing to triage". The run's own files, its status and its exit code
 * are never touched: triage is never a verdict.
 *
 * <p>Exit codes: 0; 1 when the model provider failed for a directory (or its files could not be written); 64 for a
 * usage error (a directory that is not a run's output, no gateway, a settings error).
 */
final class TriageCommand implements Command {

    private static final String LABELS_ONLY = "--labels-only";

    private static final CommandSpec SPEC = new CommandSpec("triage", "label and summarise failed runs with a model",
            "<run-output-dir>...", """
            For each run output directory (what run --out wrote for one scenario) whose run.jsonl ends FAIL or
            ERROR, asks a model for a label (APPLICATION_DEFECT, SCENARIO_DEFECT, ENVIRONMENT, DATA_DRIFT, TIMING
            or UNKNOWN) and, unless --labels-only, a summary with what to look at, from the run's masked evidence
            (no image). Writes failure/triage.json and failure/triage.md, and the model calls into triage/ (its
            cost-ledger.jsonl and inference.jsonl); the run's own files and verdict are untouched. A run that
            passed (or was degraded) prints "nothing to triage". Needs a model gateway (--inference). Exit code 0,
            1 when the model provider failed for a directory, 64 for a usage error.""",
            options(), 1, CommandSpec.UNLIMITED);

    /** The options of {@code triage}, in help order. */
    private static List<OptionSpec> options() {
        List<OptionSpec> options = new ArrayList<>(InferenceArgs.gateway());
        options.add(OptionSpec.flag(LABELS_ONLY, "ask for the label only, no summary"));
        options.add(OptionSpec.flag("--json", "print {results:[...], modelCalls, modelCost, budget, inference}\n"
                + "as JSON when done"));
        return options;
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        boolean json = args.flag("--json");
        boolean labelsOnly = args.flag(LABELS_ONLY);
        List<Path> dirs = new ArrayList<>();
        for (String arg : args.positionals()) {
            Path dir = ParsedArgs.toPath("<run-output-dir>", arg);
            if (!Files.isDirectory(dir)) {
                throw new UsageException(arg + ": no such directory");
            }
            if (!Files.isRegularFile(dir.resolve("run.jsonl"))) {
                throw new UsageException(arg + ": not a run output directory (no run.jsonl)");
            }
            dirs.add(dir);
        }
        InferenceSettings inference = InferenceArgs.load(args, BindMode.LIVE, null);
        if (!inference.enabled()) {
            throw new UsageException("triage needs a model gateway: " + InferenceArgs.INFERENCE + " claude (with"
                    + " ANTHROPIC_API_KEY) or recorded (with " + InferenceArgs.RECORDINGS + ")");
        }
        PriceTable prices;
        try {
            prices = PriceTable.of(inference);
        } catch (SettingsException e) {
            throw new UsageException(e.getMessage());
        }
        PromptMask mask = PromptMask.keysOnly();
        TriageOptions options;
        try {
            options = TriageOptions.of(inference, labelsOnly, null);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        List<Object> results = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        int calls = 0;
        int failed = 0;
        InferenceGateway gateway;
        try {
            gateway = Gateways.open(inference);
        } catch (InferenceException e) {
            throw new UsageException("the " + inference.gateway() + " gateway cannot be opened: " + e.getMessage());
        }
        try (gateway) {
            for (Path dir : dirs) {
                TriageResult result;
                try {
                    result = Triage.run(dir, gateway, mask, prices, options);
                } catch (IOException e) {
                    failed++;
                    String message = dir + ": the triage files cannot be written: " + e.getClass().getSimpleName();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("runDir", dir.toString());
                    entry.put("outcome", TriageResult.Outcome.FAILED.name());
                    entry.put("message", message);
                    results.add(entry);
                    (json ? cli.err() : cli.out()).println(message);
                    continue;
                }
                cost = cost.add(result.costUsd());
                calls += result.models().size();
                if (result.outcome() == TriageResult.Outcome.FAILED) {
                    failed++;
                }
                results.add(toJson(result));
                if (!json) {
                    cli.out().print(mask.text(describe(result)));
                }
            }
        }
        String costLine = InferenceArgs.costLine(cost, null);
        int exit = failed > 0 ? Exit.FAILED : Exit.OK;
        if (json) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("results", results);
            document.put("modelCalls", (long) calls);
            document.put("modelCost", cost.toPlainString());
            document.put("budget", null);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("gateway", inference.gateway());
            summary.put("calls", (long) calls);
            summary.put("costUsd", cost.toPlainString());
            document.put("inference", summary);
            document.put("exitCode", (long) exit);
            cli.out().print(mask.text(Json.writePretty(mask.jsonLike(document))));
            cli.err().println("model calls: " + calls);
            cli.err().println(costLine);
        } else {
            cli.out().println("model calls: " + calls);
            cli.out().println(costLine);
        }
        return exit;
    }

    /** One directory's outcome for a person. */
    private static String describe(TriageResult r) {
        StringBuilder text = new StringBuilder(r.runDir().toString()).append(": ");
        switch (r.outcome()) {
            case NOTHING_TO_TRIAGE -> text.append("nothing to triage")
                    .append(r.message() == null ? "" : " (" + r.message() + ")");
            case FAILED -> text.append("triage failed: ").append(r.message());
            case TRIAGED -> {
                text.append(r.scenarioId()).append(' ').append(r.runStatus())
                        .append(r.failureKind() == null ? "" : " " + r.failureKind()).append(" -> ");
                if (r.label() != null) {
                    text.append(r.label().label()).append(" (confidence ").append(r.label().confidence())
                            .append("): ").append(Text.oneLine(r.label().oneLine(), 300));
                }
                if (r.summary() != null) {
                    text.append("\n  ").append(Text.indent(r.summary().summary().strip(), "  ").strip());
                }
                if (r.markdown() != null) {
                    text.append("\n  written: ").append(r.markdown());
                }
            }
            default -> {
            }
        }
        return text.append('\n').toString();
    }

    private static Map<String, Object> toJson(TriageResult r) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("runDir", r.runDir().toString());
        json.put("outcome", r.outcome().name());
        json.put("scenarioId", r.scenarioId());
        json.put("runStatus", r.runStatus());
        json.put("failureKind", r.failureKind());
        if (r.label() != null) {
            Map<String, Object> label = new LinkedHashMap<>();
            label.put("label", r.label().label() == null ? null : String.valueOf(r.label().label()));
            label.put("confidence", r.label().confidence());
            label.put("oneLine", r.label().oneLine());
            json.put("label", label);
        } else {
            json.put("label", null);
        }
        if (r.summary() != null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("summary", r.summary().summary());
            summary.put("lookAt", r.summary().lookAt());
            summary.put("proposal", r.summary().proposal());
            json.put("summary", summary);
        } else {
            json.put("summary", null);
        }
        json.put("models", r.models());
        json.put("requestIds", r.requestIds());
        json.put("costUsd", r.costUsd().toPlainString());
        json.put("message", r.message());
        json.put("json", r.json() == null ? null : r.json().toString());
        json.put("markdown", r.markdown() == null ? null : r.markdown().toString());
        return json;
    }
}
