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

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.job.JobOptions;
import ai.skillroy.testjava.inference.job.ReResolveException;
import ai.skillroy.testjava.inference.job.ReResolveReport;
import ai.skillroy.testjava.inference.job.ReResolver;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * {@code skillroy re-resolve} (phase5-contracts.md 8.1, 8.2): the nightly job. It loads and compiles every scenario,
 * loads the credentials and the inference settings, refuses to start without a job budget ({@code --budget-job} or
 * {@code budget.jobUsd}) or with gateway {@code none} (exit 64), connects, checks each plan's {@code requires} and
 * launch properties on the target (a plan that fails them is not bound and is reported {@code FAILED} with its
 * reason), and runs {@link ReResolver#run}: every scenario bound in {@code re-resolve} mode, the model's calls in
 * batches, round by round, the pack's pre-flight once, the bindings files written {@code APPROVED} by
 * {@code re-resolve:<jobId>} (or {@code --approved-by}), nothing written with {@code --dry-run}. The report
 * ({@code re-resolve-report.md} and {@code .json}, the job's {@code cost-ledger.jsonl} and {@code inference.jsonl})
 * goes to {@code --out}, by default {@code run-output/re-resolve-<jobId>/}; the job id is the UTC time it started
 * ({@code yyyyMMdd-HHmmss}).
 *
 * <p>Exit codes: 0 when every scenario resolved (changed or not) or needed no bindings; 1 when any failed; 2 when a
 * file could not be loaded or compiled; 3 when the agent cannot be reached; 64 for a usage error (the job budget, the
 * gateway, the job's own refusal before it starts); 70 when the report could not be written.
 */
final class ReResolveCommand implements Command {

    private static final String OUT = "--out";
    private static final String DRY_RUN = "--dry-run";
    private static final DateTimeFormatter JOB_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final CommandSpec SPEC = new CommandSpec("re-resolve", "re-resolve the bindings of every scenario"
            + " in model batches", "<scenario.yaml|plan.json|dir>...", """
            The nightly job: binds every scenario of the environment again against the live target, the model's
            calls of tier (b) slots in one batch per round (their scenarios park and resume), verifies every
            binding, and writes each bindings file APPROVED by re-resolve:<jobId> (--approved-by replaces the
            label), leaving an identical file untouched. Scenarios that need no bindings are listed and skipped.
            The report goes to --out (default run-output/re-resolve-<jobId>/): re-resolve-report.md for the pull
            request a pipeline opens, re-resolve-report.json, and the job's cost-ledger.jsonl and inference.jsonl.
            --dry-run writes no bindings file and reports what would change. The job needs a model gateway
            (--inference) and a budget (--budget-job, or budget.jobUsd in inference.yaml); without either it does
            not start (exit code 64). Exit code 0 when every scenario resolved, 1 when one failed (the report says
            which; the others' files are still written), 2 for a file that does not load or compile, 3 when the
            agent cannot be reached, 70 when the report cannot be written.""",
            options(), 1, CommandSpec.UNLIMITED);

    /** The options of {@code re-resolve}, in help order. */
    private static List<OptionSpec> options() {
        List<OptionSpec> options = new ArrayList<>(List.of(
                OptionSpec.value(BindArgs.ENV, "id", "the environment whose bindings are re-resolved, [a-z0-9-]+\n"
                        + "(required)"),
                OptionSpec.value(BindArgs.BINDINGS, "dir", "the bindings root the files are written into\n"
                        + "(required)"),
                BindArgs.CREDENTIALS_OPTION, InferenceArgs.BUDGET_JOB_OPTION));
        options.addAll(InferenceArgs.gateway());
        options.addAll(List.of(
                OptionSpec.value(OUT, "dir", "where the report and the job's ledger go\n"
                        + "(default run-output/re-resolve-<jobId>/)"),
                OptionSpec.value(BindArgs.APPROVED_BY, "label", "written as approval.by (default re-resolve:<jobId>;\n"
                        + "a nightly job passes a stable label)"),
                OptionSpec.flag(DRY_RUN, "write no bindings file; report what would change"),
                Packs.OPTION_SPEC,
                OptionSpec.flag("--json", "print the report as JSON when done")));
        return Connector.withConnection(options.toArray(OptionSpec[]::new));
    }

    /** One loaded scenario. */
    private record Input(Path file, Plan plan, DomainPack pack) {
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        boolean json = args.flag("--json");
        boolean dryRun = args.flag(DRY_RUN);
        if (args.value(BindArgs.ENV) == null) {
            throw new UsageException("re-resolve needs " + BindArgs.ENV + ": the environment whose bindings it"
                    + " re-resolves");
        }
        if (args.value(BindArgs.BINDINGS) == null) {
            throw new UsageException("re-resolve needs " + BindArgs.BINDINGS + ": the bindings root it writes");
        }
        Path out = args.path(OUT);
        if (out != null && Files.exists(out) && !Files.isDirectory(out)) {
            throw new UsageException(OUT + " " + out + " is not a directory");
        }
        List<Path> files = ScenarioFiles.expand(args.positionals());
        BindArgs.Settings settings = BindArgs.parse(args, files, BindMode.RE_RESOLVE);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            List<String> errors = new ArrayList<>();
            List<Input> inputs = new ArrayList<>();
            for (Path file : files) {
                try {
                    RunCommand.Loaded loaded = RunCommand.load(file, packs, cli.err());
                    inputs.add(new Input(file, loaded.plan(), loaded.pack()));
                } catch (ScenarioException e) {
                    errors.add(ScenarioFiles.describe(file, e));
                }
            }
            if (!errors.isEmpty()) {
                errors.forEach(cli.err()::println);
                throw new CliException(Exit.SCENARIO, errors.size() + " of " + files.size() + " scenario file"
                        + (files.size() == 1 ? "" : "s") + " could not be loaded or compiled; nothing was"
                        + " re-resolved");
            }
            Set<String> packIds = new LinkedHashSet<>();
            inputs.forEach(i -> packIds.add(i.pack().id()));
            if (packIds.size() > 1) {
                throw new UsageException("re-resolve runs the scenarios of one pack; these name " + packIds);
            }
            Credentials credentials;
            try {
                credentials = BindArgs.credentials(settings);
            } catch (CredentialsException e) {
                throw new CliException(Exit.FAILED, "the credentials cannot be loaded: " + e.getMessage(), e);
            }
            SecretMask mask = credentials.mask();
            InferenceSettings inference = InferenceArgs.load(args, BindMode.RE_RESOLVE,
                    BindArgs.credentialsDir(settings));
            String budget = inference.budgets().get(InferenceSettings.BUDGET_JOB);
            if (budget == null) {
                throw new UsageException("re-resolve needs a job budget: " + InferenceArgs.BUDGET_JOB
                        + " <usd> (or budget.jobUsd in inference.yaml); there is no built-in value");
            }
            if (!inference.enabled()) {
                throw new UsageException("re-resolve needs a model gateway: " + InferenceArgs.INFERENCE
                        + " claude (with ANTHROPIC_API_KEY) or recorded (with " + InferenceArgs.RECORDINGS + ")");
            }
            Clock clock = Clock.systemUTC();
            String jobId = JOB_ID.format(clock.instant());
            Path reportDir = out != null ? out : Path.of("run-output", "re-resolve-" + jobId);
            JobOptions job = new JobOptions(reportDir, jobId, new BigDecimal(budget), dryRun, settings.approvedBy(),
                    null, null, clock);
            Target target = Connector.connect(args, cli);
            UnaryOperator<String> scrub = text -> mask.apply(target.scrub(text));
            try (target) {
                CapabilityMatrix caps = target.client().capabilities();
                if (caps == null) {
                    caps = new CapabilityMatrix(Map.of());
                }
                List<Plan> plans = new ArrayList<>();
                Map<String, ReResolveReport.ScenarioReport> refused = new LinkedHashMap<>();
                for (Input input : inputs) {
                    // A plan that needs no bindings is the job's to list as skipped; the others must be able to run.
                    Preconditions.Unmet unmet = input.plan().needsBindings()
                            ? Preconditions.check(input.plan(), caps, target.client()) : null;
                    if (unmet == null) {
                        plans.add(input.plan());
                    } else {
                        String id = input.plan().scenarioId();
                        refused.put(id, new ReResolveReport.ScenarioReport(id, ReResolveReport.Status.FAILED,
                                settings.envId() + "/" + id + ".bindings.json", false, 0, BigDecimal.ZERO, List.of(),
                                unmet.kind(), unmet.code(), mask.apply(unmet.message())));
                    }
                }
                DomainPack pack = inputs.isEmpty() ? null : inputs.get(0).pack();
                BindOptions options = BindOptions.defaults().withMode(BindMode.RE_RESOLVE)
                        .withEnvId(settings.envId()).withBindingsRoot(settings.root()).withCredentials(credentials)
                        .withInference(inference);
                ReResolveReport report;
                try {
                    report = ReResolver.run(plans, pack, target.client(), options, job);
                } catch (ReResolveException e) {
                    if (e.beforeStart()) {
                        throw new UsageException(e.getMessage());
                    }
                    print(cli, e.report(), reportDir, json, scrub);
                    throw new CliException(Exit.INTERNAL, e.getMessage(), e);
                }
                if (!refused.isEmpty()) {
                    report = withRefused(report, inputs, refused);
                    if (job.outDir() != null) {
                        try {
                            report.writeTo(job.outDir());
                        } catch (IOException e) {
                            print(cli, report, reportDir, json, scrub);
                            throw new CliException(Exit.INTERNAL, "the report could not be written to "
                                    + job.outDir() + ": " + e.getClass().getSimpleName(), e);
                        }
                    }
                }
                print(cli, report, reportDir, json, scrub);
                return report.exitCode();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * The job's report with the scenarios the target's preconditions kept out of the job, as {@code FAILED}, in the
     * order the files were given.
     */
    private static ReResolveReport withRefused(ReResolveReport report, List<Input> inputs,
                                               Map<String, ReResolveReport.ScenarioReport> refused) {
        List<ReResolveReport.ScenarioReport> scenarios = new ArrayList<>();
        for (Input input : inputs) {
            String id = input.plan().scenarioId();
            ReResolveReport.ScenarioReport kept = refused.get(id);
            scenarios.add(kept != null ? kept : report.scenario(id));
        }
        scenarios.removeIf(s -> s == null);
        List<String> notes = new ArrayList<>(report.notes());
        notes.add(refused.size() + " scenario" + (refused.size() == 1 ? " was" : "s were") + " not bound: the target"
                + " does not meet " + (refused.size() == 1 ? "its" : "their") + " requirements or launch properties");
        return new ReResolveReport(report.jobId(), report.envId(), report.dryRun(), report.approvedBy(),
                report.provider(), report.startedAt(), report.duration(), scenarios, report.costUsd(),
                report.budgetUsd(), report.rounds(), report.batches(), report.requests(), report.cacheHitRatio(),
                report.models(), notes, report.ledger(), report.inference());
    }

    private static void print(Cli cli, ReResolveReport report, Path reportDir, boolean json,
                              UnaryOperator<String> scrub) {
        String budget = report.budgetUsd() == null ? null : report.budgetUsd().toPlainString();
        String costLine = InferenceArgs.costLine(report.costUsd(), budget);
        if (json) {
            Map<String, Object> document = report.toJson();
            document.put("reportDir", reportDir.toString());
            document.put("modelCalls", (long) report.ledger().size());
            document.put("modelCost", report.costUsd().toPlainString());
            document.put("budget", budget);
            Map<String, Object> inference = new LinkedHashMap<>();
            inference.put("provider", report.provider());
            inference.put("calls", (long) report.ledger().size());
            inference.put("costUsd", report.costUsd().toPlainString());
            inference.put("models", report.models());
            inference.put("cacheHitRatio", report.cacheHitRatio() == null ? null
                    : report.cacheHitRatio().toPlainString());
            document.put("inference", inference);
            cli.out().print(scrub.apply(Json.writePretty(document)));
            cli.err().println("model calls: " + report.ledger().size());
            cli.err().println(costLine);
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("re-resolve job ").append(report.jobId()).append(" on ").append(report.envId()).append(": ")
                .append(report.scenarios().size()).append(" scenario").append(report.scenarios().size() == 1 ? ""
                        : "s").append(": ").append(report.count(ReResolveReport.Status.UNCHANGED))
                .append(" unchanged, ").append(report.count(ReResolveReport.Status.CHANGED)).append(" changed, ")
                .append(report.count(ReResolveReport.Status.NEW)).append(" new, ")
                .append(report.count(ReResolveReport.Status.FAILED)).append(" failed, ")
                .append(report.count(ReResolveReport.Status.SKIPPED)).append(" skipped")
                .append(report.dryRun() ? " (dry run: nothing written)" : "").append('\n');
        for (ReResolveReport.ScenarioReport s : report.scenarios()) {
            text.append(String.format(Locale.ROOT, "  %-9s %s", s.status(), s.scenarioId()));
            switch (s.status()) {
                case CHANGED -> text.append(" (").append(s.changes().size()).append(" change")
                        .append(s.changes().size() == 1 ? "" : "s").append(s.written() ? ", written" : "")
                        .append(')');
                case NEW -> text.append(s.written() ? " (written)" : "");
                case FAILED -> text.append(": ").append(s.failureKind() == null ? "" : s.failureKind() + " ")
                        .append(s.reason() == null ? "" : s.reason()).append(s.message() == null ? ""
                                : ": " + Text.oneLine(s.message(), 300));
                default -> {
                }
            }
            text.append('\n');
        }
        text.append(report.rounds()).append(report.rounds() == 1 ? " round, " : " rounds, ").append(report.batches())
                .append(report.batches() == 1 ? " batch, " : " batches, ").append(report.requests())
                .append(report.requests() == 1 ? " request" : " requests").append("; cache hits ")
                .append(report.cacheHitRatio() == null ? "n/a" : report.cacheHitRatio().toPlainString())
                .append('\n');
        text.append("model calls: ").append(report.ledger().size()).append('\n');
        text.append(costLine).append('\n');
        text.append("report: ").append(reportDir.resolve(ReResolveReport.MARKDOWN_FILE)).append('\n');
        cli.out().print(scrub.apply(text.toString()));
    }
}
