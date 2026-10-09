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

import ai.skillroy.testjava.controller.Discovery;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.JunitXmlReporter;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * {@code skillroy run}: runs scenarios (YAML, compiled on the fly) or committed plans against one agent, writes the
 * evidence per scenario and an optional JUnit XML report, and turns the verdicts into an exit code for CI.
 *
 * <p>Everything is loaded and compiled before the agent is contacted, so a broken file never leaves a target
 * half-driven. Scenarios run one after another on the calling thread; the executor brings the application back to
 * idle after a failure (the pack's {@code recover}), so later scenarios still run. If the run itself breaks (a bug
 * in the CLI or a pack), the JUnit report is still written, with the results so far and the breakage as the
 * {@code environment} failure. The report's values are scrubbed of the connection's token before they are
 * XML-escaped (security finding S27).
 *
 * <p>Phase 2 (phase2-contracts.md 12.1): {@code --mode}, {@code --env}, {@code --bindings}, {@code --credentials},
 * {@code --yes}, {@code --approved-by} and {@code --write-bindings} choose how slots are bound. Before the agent is
 * contacted the bindings root is fixed and the credentials are loaded, once; a credentials file that cannot be used
 * fails the {@code environment} testcase and gives exit code 1. {@code approve} without {@code --yes} asks on the
 * terminal, and without a terminal is a usage error (64) before anything runs. Everything printed passes the
 * credentials' mask; the human summary ends with {@code model calls: N}.
 *
 * <p>Phase 3 (phase3-contracts.md 6.2, 9): a guarded oracle cross-check the bind skipped makes the scenario DEGRADED
 * and is printed as a {@code degraded:} line (skipped expectations keep their {@code skipped:} lines); with
 * {@code --json} each result also carries the bind's {@code crossChecks} (the {@code crosscheck} records it emitted,
 * also when it then failed) and its {@code skipped} list (empty when the bind failed). ORACLE_SUSPECT is an ERROR
 * like any other.
 *
 * <p>Phase 4 (phase4-contracts.md 6.1, 8.2): the executor finds web paths through the loader of the {@code --pack}
 * jars, so a {@code --pack} jar holding the CDP path (with Playwright's jars) makes it available to {@code web.path:
 * auto} and {@code cdp}; with {@code --json} each result carries {@code web}: the {@code web.path} record of the run
 * ({@code path}, {@code reason}, {@code cdp}, {@code libHash}, {@code policy}), its {@code calls} (step, index,
 * primitive, the {@code path} that served it, ok) and its {@code fallbacks}, or null for a plan without web calls.
 *
 * <p>Phase 5 (phase5-contracts.md 4.4, 7.1, 13.4): the inference options ({@code --inference},
 * {@code --recordings}, {@code --inference-file}, {@code --budget-scenario}, {@code --log-prompts},
 * {@code --workspace-id}) become the bind's {@code InferenceSettings}, loaded once after the credentials and before the
 * agent is contacted; a settings error (the model key not set for {@code --inference claude}) fails the
 * {@code environment} testcase and is exit code 64. {@code --no-reuse-bindings} turns {@code approve}'s reuse of an
 * unchanged, verified tier (b) value off. {@code run --mode re-resolve} is refused (a command of its own). The human
 * summary prints {@code model cost: $x (budget $y)} after {@code model calls: N}; {@code --json} carries
 * {@code modelCost}, {@code budget} (the per-scenario budget) and the {@code inference} summary, and each result its
 * model calls under {@code inference}.
 *
 * <p>Phase 6 (phase6-contracts.md 10.3): the telemetry options ({@link TelemetryArgs}) are read after the inference
 * settings and before the agent is contacted (a settings error is exit code 64 and the {@code environment} testcase);
 * with an exporter named, every scenario's run reports to one observer (one trace per invocation, each scenario a
 * child of the root span {@code skillroy run}), {@code run-start} and the JUnit report carry the trace id,
 * {@code --json} carries {@code telemetry: {exporter, traceId}}, {@code --telemetry file} writes
 * {@code <out>/telemetry/spans.jsonl}, and the summary ends with the {@code telemetry:} line.
 */
final class RunCommand implements Command {

    private static final String OUT = "--out";
    private static final String JUNIT = "--junit";
    private static final String SUITE = "--suite";
    private static final String DEGRADED_AS = "--degraded-as";
    private static final String STEP_TIMEOUT = "--step-timeout";
    private static final String DEFAULT_SUITE = "skillroy";

    private static final CommandSpec SPEC = new CommandSpec("run", "run scenarios or plans against the agent",
            "<scenario.yaml|plan.json|dir>...", """
            Runs each scenario against the agent in the order given (a directory stands for its *.yaml and *.yml
            files). A YAML file is compiled first with the domain pack its 'pack:' key names, or the only pack; a
            .json file is a committed plan and runs as it is, with the pack it names. All files are loaded and
            compiled before anything runs: a load or compile error is reported as file:line: reason and gives exit
            code 2. With --out each scenario writes run.jsonl, bindings.json, cost-ledger.jsonl and, when it fails,
            failure/ (UI snapshot, capture, events, embedded browsers) into <out>/<scenario-id>/. Web verbs go
            through the CDP path when a --pack jar provides it and the scenario's web.path allows it, else through
            the agent; --json shows which, per result and per web call.
            Slots are bound by --mode: replay (default) reads <bindings>/<env>/<scenario-id>.bindings.json and
            re-runs the verifiers; approve resolves, asks (or --yes) and writes that file; live resolves now and
            writes nothing unless --write-bindings. Credentials come from credentials.yaml (--credentials).
            Exit code 0 when every scenario passed (DEGRADED counts as passed unless --degraded-as failure), 1 when
            one FAILed or ERRORed (REBIND_NEEDED and RESOLUTION_FAILED included) or the credentials cannot be
            loaded, 3 when the agent cannot be reached, 64 for approve without a terminal and without --yes.
            A slot whose strategy names llm-sql is resolved by a model in live and approve, through the gateway of
            --inference (claude needs ANTHROPIC_API_KEY in the environment; recorded answers from --recordings),
            within --budget-scenario; replay never calls a model. With --out each scenario also writes
            inference.jsonl, the masked record of its model calls. The summary ends with model calls: N and
            model cost: $x (budget $y). An inference setting that cannot be used (--inference claude without the
            key) is exit code 64 before anything runs. --telemetry otlp exports the run's spans over OTLP/HTTP (to a
            loopback address, or https://), --telemetry file writes <out>/telemetry/spans.jsonl; off by default.""",
            runOptions(), 1, CommandSpec.UNLIMITED);

    /** The options of {@code run}, in help order. */
    private static List<OptionSpec> runOptions() {
        List<OptionSpec> options = new ArrayList<>(List.of(
                OptionSpec.value(OUT, "dir", "write each scenario's evidence into <dir>/<scenario-id>/"),
                OptionSpec.value(JUNIT, "file", "write a JUnit XML report (one testcase per scenario plus an\n"
                        + "'environment' testcase)"),
                OptionSpec.value(SUITE, "name", "the test suite name in the report (default skillroy)"),
                OptionSpec.value(DEGRADED_AS, "skipped|failure|pass", "how a DEGRADED scenario is reported\n"
                        + "and counted; default skipped: shown as skipped in the\n"
                        + "report, and not a failure for the exit code"),
                OptionSpec.value(STEP_TIMEOUT, "duration", "override every scenario's step timeout (10s, 2m)")));
        options.addAll(BindArgs.RUN_OPTIONS);
        options.add(InferenceArgs.NO_REUSE_OPTION);
        options.addAll(InferenceArgs.gateway());
        options.add(InferenceArgs.BUDGET_SCENARIO_OPTION);
        options.add(Packs.OPTION_SPEC);
        options.addAll(TelemetryArgs.OPTIONS);
        options.add(OptionSpec.flag("--json", "print {results:[...], summary, junit, modelCalls, modelCost,\n"
                + "budget, inference, telemetry} as JSON when done; progress goes\nto stderr"));
        return Connector.withConnection(options.toArray(OptionSpec[]::new));
    }

    /** One loaded input: its file, the plan to run and the pack to run it with. */
    record Loaded(Path file, Plan plan, DomainPack pack) {
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        Path out = args.path(OUT);
        if (out != null && Files.exists(out) && !Files.isDirectory(out)) {
            throw new UsageException(OUT + " " + out + " is not a directory");
        }
        Path junit = args.path(JUNIT);
        String suite = args.value(SUITE) == null ? DEFAULT_SUITE : args.value(SUITE);
        RunOptions.DegradedAs degradedAs = degradedAs(args.value(DEGRADED_AS));
        Long stepTimeoutMs = args.durationMs(STEP_TIMEOUT);
        boolean json = args.flag("--json");
        List<Path> files = ScenarioFiles.expand(args.positionals());
        JunitXmlReporter reporter = new JunitXmlReporter(degradedAs);
        BindArgs.Settings bindSettings = BindArgs.parse(args, files, null);
        Approver approver = BindArgs.approver(bindSettings, cli);

        RunObserver observer = RunObserver.NONE;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            List<String> errors = new ArrayList<>();
            List<Loaded> loaded = new ArrayList<>();
            for (Path file : files) {
                try {
                    loaded.add(load(file, packs, cli.err()));
                } catch (ScenarioException e) {
                    errors.add(ScenarioFiles.describe(file, e));
                }
            }
            if (!errors.isEmpty()) {
                errors.forEach(cli.err()::println);
                String summary = errors.size() + " of " + files.size() + " scenario file"
                        + (files.size() == 1 ? "" : "s") + " could not be loaded or compiled; nothing was run";
                cli.err().println("skillroy run: " + summary);
                writeJunit(reporter, junit, suite, List.of(), summary + ":\n" + String.join("\n", errors), cli,
                        UnaryOperator.identity());
                return Exit.SCENARIO;
            }
            Map<String, Path> ids = new LinkedHashMap<>();
            for (Loaded input : loaded) {
                Path other = ids.putIfAbsent(input.plan().scenarioId(), input.file());
                if (other != null) {
                    throw new UsageException(input.file() + " and " + other + " are both scenario '"
                            + input.plan().scenarioId() + "'; their results and outputs would collide");
                }
            }

            Credentials credentials;
            try {
                credentials = BindArgs.credentials(bindSettings);
            } catch (CredentialsException e) {
                // Before the agent is contacted (12.1): nothing runs; the message never holds a value.
                String message = "the credentials cannot be loaded: " + e.getMessage();
                writeJunit(reporter, junit, suite, List.of(), message, cli, UnaryOperator.identity());
                throw new CliException(Exit.FAILED, message, e);
            }
            SecretMask mask = credentials.mask();
            InferenceSettings inference;
            try {
                inference = InferenceArgs.load(args, bindSettings.mode(), BindArgs.credentialsDir(bindSettings));
            } catch (UsageException e) {
                // Phase 5 4.4: a settings error is found before any scenario runs (exit 64 / the environment case).
                writeJunit(reporter, junit, suite, List.of(), "the inference settings cannot be used: "
                        + e.getMessage(), cli, UnaryOperator.identity());
                throw e;
            }
            TelemetrySettings telemetry;
            try {
                telemetry = TelemetryArgs.load(args, BindArgs.credentialsDir(bindSettings), out);
                observer = TelemetryArgs.open(telemetry, cli);
            } catch (UsageException e) {
                // phase6-contracts.md 10.3: a settings error is found before the agent is contacted (exit 64).
                writeJunit(reporter, junit, suite, List.of(), e.getMessage(), cli, UnaryOperator.identity());
                throw e;
            }
            if (bindSettings.mode() == BindMode.REPLAY && InferenceArgs.given(args)) {
                cli.err().println("skillroy run: note: replay makes no model call; the inference options are not"
                        + " used");
            }
            if (approver == null) {
                approver = BindArgs.terminalApprover(cli, mask);
            }
            Target target;
            try {
                target = Connector.connect(args, cli);
            } catch (CliException e) {
                TelemetryArgs.started(observer, TelemetryArgs.runStart(observer, "run", suite,
                        bindSettings.mode().label(), bindSettings.envId(), null, mask.apply(e.getMessage())));
                writeJunit(reporter, junit, suite, List.of(), e.getMessage(), cli, UnaryOperator.identity());
                throw e;
            }
            TelemetryArgs.started(observer, TelemetryArgs.runStart(observer, "run", suite,
                    bindSettings.mode().label(), bindSettings.envId(), target, null));
            reporter = reporter.withTraceId(observer.traceId());
            UnaryOperator<String> scrub = text -> mask.apply(target.scrub(text));
            List<RunResult> results = new ArrayList<>();
            List<Map<String, Object>> jsonResults = new ArrayList<>();
            try (target) {
                RunOptions base = RunOptions.defaults().withDegradedAs(degradedAs)
                        .withTargetRestarted(restartProbe(target))
                        .withBind(BindArgs.options(bindSettings, credentials, approver, inference,
                                !args.flag(InferenceArgs.NO_REUSE_BINDINGS)))
                        .withObserver(observer);
                if (stepTimeoutMs != null) {
                    base = base.withStepTimeout(Duration.ofMillis(stepTimeoutMs));
                }
                PrintStream progress = json ? cli.err() : cli.out();
                for (Loaded input : loaded) {
                    Path dir = out == null ? null : out.resolve(input.plan().scenarioId());
                    PlanExecutor.Resolved ran = new PlanExecutor(input.pack(), target.client(),
                            base.withOutputDir(dir), packs.loader()).runWithOutcome(input.plan());
                    RunResult result = ran.result();
                    results.add(result);
                    progress.println(scrub.apply(describe(result, dir)));
                    progress.flush();
                    if (json) {
                        jsonResults.add(toJson(input.file(), result, dir, ran));
                    }
                }
            } catch (RuntimeException | Error e) {
                // A crash must still leave a report: the results so far plus the breakage as the environment case.
                try {
                    writeJunit(reporter, junit, suite, results, "the run stopped after " + results.size() + " of "
                            + loaded.size() + " scenarios: internal error: " + e, cli, scrub);
                } catch (CliException | RuntimeException reportFailure) {
                    e.addSuppressed(reportFailure);
                }
                throw e;
            }
            writeJunit(reporter, junit, suite, results, null, cli, scrub);
            long modelCalls = results.stream().mapToLong(r -> r.modelCalls().size()).sum();
            List<CostLedger.Entry> calls = results.stream().flatMap(r -> r.modelCalls().stream()).toList();
            String budget = inference.budgets().get(InferenceSettings.BUDGET_SCENARIO);
            String costLine = InferenceArgs.costLine(calls, budget);
            Map<Status, Integer> counts = new EnumMap<>(Status.class);
            for (Status status : Status.values()) {
                counts.put(status, 0);
            }
            results.forEach(r -> counts.merge(r.status(), 1, Integer::sum));
            String summary = results.size() + " scenario" + (results.size() == 1 ? "" : "s") + ": "
                    + counts.get(Status.PASS) + " passed, " + counts.get(Status.DEGRADED) + " degraded, "
                    + counts.get(Status.FAIL) + " failed, " + counts.get(Status.ERROR) + " errors";
            int exit = counts.get(Status.FAIL) + counts.get(Status.ERROR) > 0
                    || (degradedAs == RunOptions.DegradedAs.FAILURE && counts.get(Status.DEGRADED) > 0)
                    ? Exit.FAILED : Exit.OK;
            if (json) {
                Map<String, Object> summaryJson = new LinkedHashMap<>();
                summaryJson.put("total", (long) results.size());
                for (Status status : Status.values()) {
                    summaryJson.put(status.name().toLowerCase(Locale.ROOT), (long) counts.get(status));
                }
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("results", jsonResults);
                document.put("summary", summaryJson);
                document.put("junit", junit == null ? null : junit.toString());
                document.put("exitCode", (long) exit);
                document.put("modelCalls", modelCalls);
                document.put("modelCost", InferenceArgs.cost(calls).toPlainString());
                document.put("budget", budget);
                document.put("inference", InferenceArgs.summary(inference, calls));
                document.put("telemetry", TelemetryArgs.json(telemetry, observer));
                cli.out().print(scrub.apply(Json.writePretty(mask.apply(document))));
                cli.err().println(summary);
                cli.err().println("model calls: " + modelCalls);
                cli.err().println(costLine);
            } else {
                cli.out().println(summary);
                cli.out().println("model calls: " + modelCalls);
                cli.out().println(costLine);
            }
            return exit;
        } finally {
            // phase6-contracts.md 10.3: the export ends (bounded) and the summary's telemetry line follows it.
            TelemetryArgs.finish(observer, json ? cli.err() : cli.out());
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** Loads a plan file, or loads and compiles a YAML file, and selects its pack. */
    static Loaded load(Path file, Packs packs, PrintStream err) throws ScenarioException {
        if (ScenarioFiles.isPlan(file)) {
            Plan plan;
            try {
                plan = Plan.read(file);
            } catch (IOException e) {
                throw new ScenarioException("the plan file cannot be read: " + e, e);
            }
            DomainPack pack;
            try {
                pack = packs.select(plan.pack());
            } catch (ScenarioException e) {
                throw e.locate(plan.scenarioId(), 0);
            }
            if (!pack.version().equals(plan.packVersion())) {
                err.println("skillroy run: warning: " + file + " was compiled with pack " + plan.pack() + " "
                        + plan.packVersion() + " and runs with version " + pack.version()
                        + "; recompile it with skillroy compile");
            }
            return new Loaded(file, plan, pack);
        }
        Scenario scenario = ScenarioLoader.load(file);
        DomainPack pack;
        try {
            pack = packs.select(scenario.pack());
        } catch (ScenarioException e) {
            throw e.locate(scenario.id(), 0);
        }
        PlanCompiler.Compiled compiled = PlanCompiler.compileWithNotes(scenario, pack);
        for (String note : compiled.notes()) {
            err.println("skillroy run: note: " + file + ": " + note);
        }
        return new Loaded(file, compiled.plan(), pack);
    }

    static RunOptions.DegradedAs degradedAs(String text) throws UsageException {
        if (text == null) {
            return RunOptions.DegradedAs.SKIPPED;
        }
        try {
            return RunOptions.DegradedAs.valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException(DEGRADED_AS + " must be skipped, failure or pass, found '" + text + "'");
        }
    }

    /**
     * Asked by the executor after the agent is lost: true when the target's process is gone and a live discovery
     * file shows a new pid with the same main command (TARGET_RESTARTED rather than AGENT_LOST). Null when the
     * target's pid or main command is unknown.
     */
    static Supplier<Boolean> restartProbe(Target target) {
        long pid = target.endpoint().pid();
        String mainCommand = target.mainCommand();
        Path dir = target.discoveryDir();
        if (pid <= 0 || mainCommand == null) {
            return null;
        }
        return () -> {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return false;
            }
            return Discovery.entries(dir).stream()
                    .anyMatch(e -> e.endpoint().pid() != pid && mainCommand.equals(e.mainCommand()));
        };
    }

    private static void writeJunit(JunitXmlReporter reporter, Path file, String suite, List<RunResult> results,
                                   String environmentFailure, Cli cli, UnaryOperator<String> scrub)
            throws CliException {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, reporter.render(suite, results, environmentFailure, scrub),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException(Exit.FAILED, "cannot write the JUnit report " + file + ": " + e, e);
        }
        cli.err().println("skillroy run: JUnit report written to " + file);
    }

    /** The human-readable verdict of one scenario: one line, plus the evidence when it did not pass. */
    static String describe(RunResult result, Path dir) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%-8s  %s  (%s)", result.status(),
                result.scenarioId(), Text.seconds(result.duration())));
        if (result.status() == Status.DEGRADED) {
            if (!result.skippedChecks().isEmpty()) {
                text.append("\n  skipped: ").append(String.join("\n           ", result.skippedChecks()));
            }
            List<String> degraded = new ArrayList<>(result.skippedCrossChecks());
            degraded.addAll(result.degradedFidelity());
            if (!degraded.isEmpty()) {
                text.append("\n  degraded: ").append(String.join("\n            ", degraded));
            }
        } else if (result.status() != Status.PASS) {
            text.append("\n  ").append(result.failureKind());
            if (result.failedStepId() != null) {
                text.append(" at ").append(result.failedStepId());
                int line = stepLine(result);
                if (line > 0) {
                    text.append(" (line ").append(line).append(')');
                }
            }
            text.append(": ").append(result.message());
            Map<String, Object> detail = new LinkedHashMap<>(result.detail());
            Object snapshot = detail.remove("snapshot");
            if (!detail.isEmpty()) {
                text.append("\n  detail:\n").append(Text.indent(Json.writePretty(detail).stripTrailing(), "    "));
            }
            if (snapshot instanceof String ui && !ui.isBlank()) {
                text.append("\n  UI at failure:\n").append(Text.indent(ui.stripTrailing(), "    "));
            }
        }
        if (dir != null && result.status() != Status.PASS) {
            text.append("\n  evidence: ").append(dir);
        }
        return text.toString();
    }

    private static Map<String, Object> toJson(Path file, RunResult result, Path dir, PlanExecutor.Resolved ran) {
        BindOutcome outcome = ran.outcome();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("scenarioId", result.scenarioId());
        json.put("source", file.toString());
        json.put("status", result.status().name());
        json.put("failureKind", result.failureKind() == null ? null : result.failureKind().name());
        json.put("failedStepId", result.failedStepId());
        int line = stepLine(result);
        json.put("failedStepLine", line > 0 ? (long) line : null);
        json.put("message", result.message());
        json.put("mode", result.mode() == null ? null : result.mode().label());
        json.put("envId", result.envId());
        json.put("modelCalls", (long) result.modelCalls().size());
        // phase5-contracts.md 13.4: the scenario's model calls (its inference records, summarised).
        json.put("inference", InferenceArgs.records(result.modelCalls()));
        json.put("durationMs", result.duration().toMillis());
        json.put("skippedExpectations", result.skippedExpectations());
        json.put("degradedFidelity", result.degradedFidelity());
        // phase3-contracts.md 9: the records the bind emitted, also when it failed; the skipped lines of a bind
        // that completed.
        json.put("crossChecks", new ArrayList<Object>(ran.crossChecks()));
        json.put("skipped", outcome == null ? List.of() : outcome.skipped());
        // phase4-contracts.md 8.2: the web path the run chose and the path of every web call, or null.
        json.put("web", ran.web());
        json.put("outputDir", dir == null ? null : dir.toString());
        json.put("detail", result.detail());
        List<Object> steps = new ArrayList<>();
        for (StepResult step : result.steps()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", step.id());
            s.put("verb", step.verb());
            s.put("line", (long) step.line());
            s.put("status", step.status().name());
            s.put("message", step.message());
            s.put("degradedFidelity", step.degradedFidelity());
            s.put("durationMs", step.duration().toMillis());
            steps.add(s);
        }
        json.put("steps", steps);
        return json;
    }

    private static int stepLine(RunResult result) {
        for (StepResult step : result.steps()) {
            if (step.id().equals(result.failedStepId())) {
                return step.line();
            }
        }
        return 0;
    }
}
