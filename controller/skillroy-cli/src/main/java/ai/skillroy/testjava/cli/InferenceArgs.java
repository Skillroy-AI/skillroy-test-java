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

import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The inference options of the commands that may call a model (phase5-contracts.md 4.4, 13.4): {@code --inference},
 * {@code --recordings}, {@code --inference-file}, {@code --log-prompts}, {@code --workspace-id} and the budgets
 * ({@code --budget-scenario}, {@code --budget-job}, {@code --budget-session}, and {@code mcp}'s
 * {@code --session-budget}), turned into {@link InferenceSettings} through {@link InferenceSettings#load} under the
 * names of the system properties; and the {@code model cost} line and the {@code inference} summary every such
 * command prints.
 *
 * <p>A settings error ({@code --inference claude} without the model key, a recorded gateway without recordings, a
 * malformed {@code inference.yaml}) is a usage error, exit code 64, with the settings' own message, which names the
 * file, the line or the option and never a value. The model key is read from the environment only (its value never
 * by this class: only whether {@code ANTHROPIC_API_KEY} is set); there is no {@code --api-key}.
 *
 * <p>Stateless; safe from any thread.
 */
final class InferenceArgs {

    static final String INFERENCE = "--inference";
    static final String RECORDINGS = "--recordings";
    static final String FILE = "--inference-file";
    static final String LOG_PROMPTS = "--log-prompts";
    static final String WORKSPACE_ID = "--workspace-id";
    static final String BUDGET_SCENARIO = "--budget-scenario";
    static final String BUDGET_JOB = "--budget-job";
    static final String BUDGET_SESSION = "--budget-session";
    static final String SESSION_BUDGET = "--session-budget";
    static final String NO_REUSE_BINDINGS = "--no-reuse-bindings";

    static final OptionSpec INFERENCE_OPTION = OptionSpec.value(INFERENCE, "claude|recorded|none",
            "the model gateway (default: claude when ANTHROPIC_API_KEY\nis set, else none; replay never calls a"
                    + " model)");
    static final OptionSpec RECORDINGS_OPTION = OptionSpec.value(RECORDINGS, "dir",
            "the recorded gateway's directory of recordings");
    static final OptionSpec FILE_OPTION = OptionSpec.value(FILE, "path",
            "the inference settings (default: inference.yaml beside\nthe credentials file)");
    static final OptionSpec LOG_PROMPTS_OPTION = OptionSpec.flag(LOG_PROMPTS,
            "write the masked prompts into inference.jsonl");
    static final OptionSpec WORKSPACE_OPTION = OptionSpec.value(WORKSPACE_ID, "id",
            "the workspace id an organisation-level model key needs\n(default: ANTHROPIC_WORKSPACE_ID)");
    static final OptionSpec BUDGET_SCENARIO_OPTION = OptionSpec.value(BUDGET_SCENARIO, "usd",
            "the model budget of one scenario's bind in US dollars\n(default 0.50)");
    static final OptionSpec BUDGET_JOB_OPTION = OptionSpec.value(BUDGET_JOB, "usd",
            "the job's model budget in US dollars; required (here or\nas budget.jobUsd in inference.yaml)");
    static final OptionSpec BUDGET_SESSION_OPTION = OptionSpec.value(BUDGET_SESSION, "usd",
            "the session's model budget in US dollars (default 1.00)");
    static final OptionSpec SESSION_BUDGET_OPTION = OptionSpec.value(SESSION_BUDGET, "usd",
            "the session's model budget in US dollars (default 1.00):\nbounds skillroy_session_ask, skillroy_resolve"
                    + " and\nskillroy_scenario_run together");
    static final OptionSpec NO_REUSE_OPTION = OptionSpec.flag(NO_REUSE_BINDINGS,
            "approve: resolve every model-written slot afresh, never\nreusing an unchanged, verified recorded value");

    /** Each budget option and the system property it stands for. */
    private static final Map<String, String> BUDGETS = Map.of(BUDGET_SCENARIO,
            InferenceSettings.PROP_BUDGET_SCENARIO, BUDGET_JOB, InferenceSettings.PROP_BUDGET_JOB, BUDGET_SESSION,
            InferenceSettings.PROP_BUDGET_SESSION, SESSION_BUDGET, InferenceSettings.PROP_BUDGET_SESSION);

    private InferenceArgs() {
    }

    /** The gateway options every command that may call a model takes, in help order. */
    static List<OptionSpec> gateway() {
        return List.of(INFERENCE_OPTION, RECORDINGS_OPTION, FILE_OPTION, LOG_PROMPTS_OPTION, WORKSPACE_OPTION);
    }

    /** The options given on the command line, under the names of the system properties they override. */
    static Map<String, String> options(ParsedArgs args) {
        Map<String, String> options = new LinkedHashMap<>();
        text(args, INFERENCE, InferenceSettings.PROP_GATEWAY, options);
        text(args, RECORDINGS, InferenceSettings.PROP_RECORDINGS, options);
        text(args, FILE, InferenceSettings.PROP_FILE, options);
        text(args, WORKSPACE_ID, InferenceSettings.PROP_WORKSPACE_ID, options);
        if (declared(args, LOG_PROMPTS) && args.flag(LOG_PROMPTS)) {
            options.put(InferenceSettings.PROP_LOG_PROMPTS, "true");
        }
        BUDGETS.forEach((option, property) -> text(args, option, property, options));
        return options;
    }

    /** True when any inference option was given (a {@code replay} run says it is not used). */
    static boolean given(ParsedArgs args) {
        return !options(args).isEmpty();
    }

    /**
     * The settings of the invocation in {@code mode}: the options over the system properties over
     * {@code inference.yaml} over the defaults.
     *
     * @param credentialsDir where {@code inference.yaml} is looked for unless {@code --inference-file} names it; may
     *     be null
     * @throws UsageException with the settings' message (exit code 64), never a value
     */
    static InferenceSettings load(ParsedArgs args, BindMode mode, Path credentialsDir) throws UsageException {
        try {
            return InferenceSettings.load(mode, credentialsDir, options(args));
        } catch (SettingsException e) {
            throw new UsageException(e.getMessage());
        }
    }

    /** What the entries cost, summed (an entry without a readable cost adds nothing). */
    static BigDecimal cost(List<CostLedger.Entry> entries) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CostLedger.Entry e : entries) {
            try {
                sum = sum.add(new BigDecimal(e.costUsd()));
            } catch (RuntimeException ignored) {
                // An entry without a readable cost adds nothing.
            }
        }
        return sum;
    }

    /**
     * {@code model cost: $0.0231 (budget $0.50)} (phase5-contracts.md 7.2), or {@code (no budget)} when the scope has
     * none.
     */
    static String costLine(List<CostLedger.Entry> entries, String budget) {
        return costLine(cost(entries), budget);
    }

    /** {@link #costLine(List, String)} for a cost already summed. */
    static String costLine(BigDecimal cost, String budget) {
        return "model cost: $" + cost.toPlainString() + (budget == null ? " (no budget)" : " (budget $" + budget
                + ")");
    }

    /**
     * The {@code inference} summary of a command's {@code --json}: the gateway, the number of model calls, their cost,
     * and the providers, models and purposes that appear, in first-seen order.
     */
    static Map<String, Object> summary(InferenceSettings settings, List<CostLedger.Entry> entries) {
        Set<String> providers = new LinkedHashSet<>();
        Set<String> models = new LinkedHashSet<>();
        Set<String> purposes = new LinkedHashSet<>();
        for (CostLedger.Entry e : entries) {
            add(providers, e.provider());
            add(models, e.model());
            add(purposes, e.purpose());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("gateway", settings == null ? null : settings.gateway());
        json.put("calls", (long) entries.size());
        json.put("costUsd", cost(entries).toPlainString());
        json.put("providers", new ArrayList<>(providers));
        json.put("models", new ArrayList<>(models));
        json.put("purposes", new ArrayList<>(purposes));
        return json;
    }

    /**
     * One record per model call for a result's {@code --json}: the ledger entry's fields, and, from the
     * {@code inference.jsonl} record the entry carries, the stop reason and the fingerprint.
     */
    static List<Object> records(List<CostLedger.Entry> entries) {
        List<Object> records = new ArrayList<>();
        for (CostLedger.Entry e : entries) {
            Map<String, Object> json = e.toJson();
            if (e.details() != null) {
                json.put("stop", e.details().get("stop"));
                json.put("fingerprint", e.details().get("fingerprint"));
            }
            records.add(json);
        }
        return records;
    }

    /**
     * Writes {@code cost-ledger.jsonl} into {@code dir}: one line per entry, the secrets masked (the file a session,
     * whose output directory the CLI owns, keeps beside its {@code inference.jsonl}).
     *
     * @throws IOException if the file cannot be written
     */
    static void writeLedger(Path dir, List<CostLedger.Entry> entries, SecretMask mask) throws IOException {
        StringBuilder text = new StringBuilder();
        for (CostLedger.Entry e : entries) {
            text.append(Json.write(mask.apply(e.toJson()))).append('\n');
        }
        // Phase 6 security fix, pass 2, P2-L8-F3: created exclusively, never through what is in its place.
        OutputFiles.writeString(dir.resolve("cost-ledger.jsonl"), text.toString());
    }

    /**
     * Appends to {@code inference.jsonl} in {@code dir} the records of the model calls made through binds that recorded
     * into {@code ledger} ({@code InferenceLog.forLedger}: the tier (b) calls of a session's resolves and of the
     * scenario runs it started; phase5-contracts.md 7.4, 13.4), each line through the credentials' mask once more; a
     * session's own calls are in the file already (its service appends them). Nothing is written without a record.
     *
     * @return how many records were appended
     * @throws IOException if the file cannot be written
     */
    static int appendInferenceRecords(Path dir, CostLedger ledger, SecretMask mask) throws IOException {
        List<Map<String, Object>> records = InferenceLog.forLedger(ledger).records();
        if (records.isEmpty()) {
            return 0;
        }
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> record : records) {
            text.append(Json.write(mask.apply((Object) record))).append('\n');
        }
        // P2-L8-F3: appended only to a regular file of the current user, never through a link.
        OutputFiles.append(dir.resolve(SessionServices.INFERENCE_FILE), text.toString()
                .getBytes(StandardCharsets.UTF_8));
        return records.size();
    }

    private static void add(Set<String> set, String value) {
        if (value != null) {
            set.add(value);
        }
    }

    private static boolean declared(ParsedArgs args, String option) {
        return args.spec().option(option) != null;
    }

    private static void text(ParsedArgs args, String option, String property, Map<String, String> options) {
        if (declared(args, option) && args.value(option) != null) {
            options.put(property, args.value(option));
        }
    }
}
