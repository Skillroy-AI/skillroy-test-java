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

import ai.skillroy.testjava.controller.SecureFiles;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.planner.AuthoringException;
import ai.skillroy.testjava.inference.planner.AuthoringResult;
import ai.skillroy.testjava.inference.planner.Planner;
import ai.skillroy.testjava.inference.planner.PlannerRequest;
import ai.skillroy.testjava.inference.tools.ToolProjection;
import ai.skillroy.testjava.mcp.PlanPolicy;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code skillroy author} (phase5-contracts.md 10, decision D77): the planner. It connects, runs the pack's pre-flight
 * checks, writes the MCP configuration of this CLI's own server ({@code mcp --mutating} with the connection options,
 * the pack jars, the environment, the credentials file and {@code --inference none}, so that the planner's tools
 * offer no second model) into a private, owner-only temporary file, and calls {@link Planner#author}: the Claude Code
 * CLI headless with that server as its only tool source and the scenario JSON schema as its output constraint. The
 * planner writes {@code <out>/<id>.yaml}, {@code <out>/plan/<id>.plan.json}, {@code <out>/<id>.authoring.json} and
 * {@code <out>/<id>.envelope.json} (its envelopes, masked, D112; a failure writes the ones that arrived, and the
 * command names the file on stderr). The planner is given the catalogue of the primitives its server offers a scenario
 * ({@link PlanPolicy}), so that the scenario schema's {@code call} names only those, and its deadline is
 * {@code timeouts.plannerMs}. The command then binds the plan with {@code resolve --dry-run} semantics ({@code live},
 * nothing written), with {@code --run} runs it once in {@code live} when every call of it is one the planner's server
 * offers (D96; otherwise the check fails and nothing runs), its expectations on masked values, records both checks in
 * the authoring record ({@link AuthoringResult#recordChecks}), records the planner's calls in the invocation's ledger
 * beside the checks' own, prints the instruction for the reviewer and deletes the configuration file.
 *
 * <p>This CLI's own launcher is the system property {@code skillroy.launcher} ({@code bin/skillroy} sets it to
 * itself); without it (in-process, an IDE) the configuration names this JVM's {@code java} with its class path.
 * {@code --claude-command}, else the system property {@code skillroy.claude.command}, else {@code claude} on the
 * {@code PATH} names the planner's executable.
 *
 * <p>Windows (phase6-contracts.md 9.2, D137; Phase 6, built 2026-10-09, BW, not verified on a Windows host): on the
 * {@code PATH} the planner's executable is also {@code claude} plus any extension of {@code PATHEXT}
 * ({@link #findOnPath}); a launcher that is a {@code .bat} or {@code .cmd} ({@code bin\skillroy.bat}) is written into
 * the configuration as {@code cmd.exe /c <launcher> mcp ...}, since Node-based clients refuse to spawn a batch file
 * without a shell, and a launcher path or argument that {@code cmd.exe} would split or expand is refused
 * ({@link #serverCommand}). The configuration file is written owner-only through {@link SecureFiles} (9.3).
 *
 * <p>Exit codes: 0 when the scenario was written and compiled (whatever the checks found: they are the reviewer's
 * information); 1 when the planner failed or its repairs ran out, or the pre-flight failed; 3 when the agent cannot
 * be reached; 64 for a usage error ("claude not found on PATH", an id that exists, a missing brief).
 */
final class AuthorCommand implements Command {

    private static final String BRIEF = "--brief";
    private static final String BRIEF_FILE = "--brief-file";
    private static final String ID = "--id";
    private static final String OUT = "--out";
    private static final String MODEL = "--model";
    private static final String MAX_TURNS = "--max-turns";
    private static final String MAX_REPAIRS = "--max-repairs";
    private static final String BUDGET_USD = "--budget-usd";
    private static final String RUN = "--run";
    private static final String CLAUDE_COMMAND = "--claude-command";
    /** The system property naming the planner's executable when {@code --claude-command} is not given. */
    static final String PROP_CLAUDE_COMMAND = "skillroy.claude.command";
    /** The system property {@code bin/skillroy} sets to itself: the launcher the MCP configuration names. */
    static final String PROP_LAUNCHER = "skillroy.launcher";
    /** The key of {@code timeouts} in the inference settings that bounds the planner (phase5-contracts.md 10.2). */
    static final String TIMEOUT_PLANNER_MS = InferenceSettings.TIMEOUT_PLANNER_MS;
    /**
     * The policy of the planner's server: {@code mcp --mutating} without {@code --allow-evaluate} (D98), as
     * {@link #mcpConfig} writes it; what its scenario may call (D96).
     */
    static final ToolProjection.Policy PLANNER_POLICY = ToolProjection.Policy.mutating(false);

    private static final CommandSpec SPEC = new CommandSpec("author", "author a scenario from a brief with Claude Code",
            "", """
            Authors one scenario from a one-paragraph brief with the Claude Code CLI run headless (claude -p), whose
            only tools are this target's skillroy MCP server (mcp --mutating: the planner may try steps on the
            disposable test instance) and whose answer must fit the scenario JSON schema of the pack. Writes
            <out>/<id>.yaml (with the brief, the date, the model, the session and the cost as a comment head),
            <out>/plan/<id>.plan.json, <out>/<id>.authoring.json and <out>/<id>.envelope.json (the planner's
            envelopes, masked; a failure writes the ones it received); a compile problem starts a repair round
            (claude -p --resume), at most --max-repairs times. Then binds the scenario live, writing nothing, and
            with --run runs it once live; both checks are recorded in the authoring record. Nothing is approved:
            review the YAML and the plan, then skillroy resolve approves its bindings; delete the envelope file
            before committing, or keep it as the fixture of the planner's replay. Needs the claude command
            (--claude-command, or claude on the PATH) and its login or ANTHROPIC_API_KEY. Exit code 0 when the
            scenario was written and compiled, 1 when the planner failed or its repairs ran out, 64 for a usage
            error.""",
            options(), 0, 0);

    /** The options of {@code author}, in help order. */
    private static List<OptionSpec> options() {
        List<OptionSpec> options = new ArrayList<>(List.of(
                OptionSpec.value(BRIEF, "text", "the brief: one paragraph of what the scenario does"),
                OptionSpec.value(BRIEF_FILE, "path", "the brief, read from a file"),
                OptionSpec.value(ID, "scenario-id", "the new scenario's id, [a-z0-9-]+ (required)"),
                OptionSpec.value(OUT, "dir", "the scenarios directory the files go into (required)"),
                Packs.OPTION_SPEC, BindArgs.ENV_OPTION, BindArgs.CREDENTIALS_OPTION, BindArgs.BINDINGS_OPTION,
                OptionSpec.value(MODEL, "opus|sonnet", "the planner's model class (default opus)"),
                OptionSpec.value(MAX_TURNS, "n", "the planner's turn limit (default 40)"),
                OptionSpec.value(MAX_REPAIRS, "n", "the most repair rounds (default 2)"),
                OptionSpec.value(BUDGET_USD, "usd", "the planner's budget in US dollars (default 2.00)"),
                OptionSpec.flag(RUN, "run the scenario once live after it is written"),
                OptionSpec.value(CLAUDE_COMMAND, "path", "the claude executable (default: claude on the PATH)")));
        options.addAll(InferenceArgs.gateway());
        options.addAll(TelemetryArgs.OPTIONS);
        options.add(OptionSpec.flag("--json", "print the authoring outcome as JSON when done"));
        return Connector.withConnection(options.toArray(OptionSpec[]::new));
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        boolean json = args.flag("--json");
        String brief = brief(args);
        String id = args.value(ID);
        if (id == null) {
            throw new UsageException("author needs " + ID + " <scenario-id>");
        }
        Path out = args.path(OUT);
        if (out == null) {
            throw new UsageException("author needs " + OUT + " <scenarios dir>");
        }
        if (Files.exists(out) && !Files.isDirectory(out)) {
            throw new UsageException(OUT + " " + out + " is not a directory");
        }
        Long maxTurns = args.longValue(MAX_TURNS, 1, 1000);
        Long maxRepairs = args.longValue(MAX_REPAIRS, 0, 10);
        BigDecimal budget = budget(args);
        Path claude = claudeCommand(args);
        BindArgs.Settings settings = BindArgs.parse(args, List.of(), BindMode.LIVE);
        RunObserver observer = RunObserver.NONE;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            DomainPack pack;
            try {
                pack = packs.select(null);
            } catch (ScenarioException e) {
                throw new UsageException("the planner authors for one pack: " + e.reason());
            }
            Credentials credentials;
            try {
                credentials = BindArgs.credentials(settings);
            } catch (CredentialsException e) {
                throw new CliException(Exit.FAILED, "the credentials cannot be loaded: " + e.getMessage(), e);
            }
            SecretMask mask = credentials.mask();
            InferenceSettings inference = InferenceArgs.load(args, BindMode.LIVE, BindArgs.credentialsDir(settings));
            String model = model(args, inference);
            // phase6-contracts.md 10.3: --out is the scenarios directory, so the file exporter needs out: in
            // telemetry.yaml or skillroy.telemetry.out; the planner's calls are its ledger's (modelCall).
            observer = TelemetryArgs.open(TelemetryArgs.load(args, BindArgs.credentialsDir(settings), null), cli);
            Target target = Connector.connect(args, cli);
            TelemetryArgs.started(observer, TelemetryArgs.runStart(observer, "author", null, BindMode.LIVE.label(),
                    settings.envId(), target, null));
            UnaryOperator<String> scrub = text -> mask.apply(target.scrub(text));
            try (target) {
                String preflight = Preconditions.preflight(pack, target.client(), credentials, settings.envId());
                if (preflight != null) {
                    throw new CliException(Exit.FAILED, scrub.apply(preflight) + "; the planner was not started");
                }
                List<PrimitiveDescriptor> primitives = target.client().schema();
                PlanPolicy policy;
                try {
                    policy = PlanPolicy.of(primitives, pack, PLANNER_POLICY);
                } catch (IllegalArgumentException e) {
                    throw new CliException(Exit.FAILED, "the planner's tools cannot be projected: " + e.getMessage()
                            + "; the planner was not started");
                }
                CostLedger ledger = new CostLedger();
                Path config = mcpConfig(args, cli, settings);
                AuthoringResult result;
                try {
                    // The catalogue of what the planner's server offers (D96): the scenario schema's call names those.
                    // The credentials' mask: a model's text that repeats a credential reaches neither the planner's
                    // log nor its envelope file.
                    PlannerRequest request = new PlannerRequest(brief, id, out, pack, policy.callableCatalogue(),
                            config, model, maxTurns == null ? PlannerRequest.DEFAULT_MAX_TURNS : maxTurns.intValue(),
                            maxRepairs == null ? PlannerRequest.DEFAULT_MAX_REPAIRS : maxRepairs.intValue(), budget,
                            args.flag(RUN), claude, plannerDeadline(inference), null, mask);
                    result = Planner.author(request);
                } catch (AuthoringException e) {
                    e.ledger().forEach(ledger::record);
                    cli.err().println("skillroy author: " + scrub.apply(e.getMessage()));
                    if (e.lastYaml() != null) {
                        cli.err().println("the last scenario the planner returned:");
                        cli.err().print(scrub.apply(e.lastYaml()));
                    }
                    if (e.envelope() != null) {
                        cli.err().println(scrub.apply("the planner's envelopes: " + e.envelope()));
                    }
                    failureSummary(cli, ledger.entries(), budget);
                    return e.exitCode();
                } finally {
                    Files.deleteIfExists(config);
                }
                result.ledger().forEach(ledger::record);
                result.ledger().forEach(RunObservers.safe(observer)::modelCall);
                BindOptions live = BindOptions.defaults().withMode(BindMode.LIVE).withEnvId(settings.envId())
                        .withBindingsRoot(settings.root()).withCredentials(credentials).withLedger(ledger)
                        .withInference(inference);
                Plan plan = result.plan();
                Boolean resolved = null;
                String resolveLine;
                if (plan.needsBindings()) {
                    RunResult bound = new PlanExecutor(pack, target.client(), RunOptions.defaults().withBind(live)
                            .withObserver(observer), packs.loader()).resolve(plan).result();
                    resolved = passed(bound);
                    resolveLine = resolved ? "check: its slots resolve on " + settings.envId() + " (live, nothing"
                            + " written)" : "check: the bind failed: " + bound.failureKind() + BindArgs.reason(
                            bound.detail()) + ": " + bound.message();
                } else {
                    resolveLine = "check: it needs no bindings";
                }
                Boolean ran = null;
                String runText = null;
                if (args.flag(RUN)) {
                    List<String> refusals = policy.refusals(plan);
                    if (!refusals.isEmpty()) {
                        ran = false;
                        runText = "check: run once live: refused, nothing ran (the planner's server runs only what"
                                + " it offers as tools): " + String.join("; ", refusals);
                    } else {
                        RunResult run = new PlanExecutor(pack, target.client(), RunOptions.defaults().withBind(live)
                                .withExpectationsOnMaskedValues(true).withObserver(observer), packs.loader())
                                .run(plan);
                        ran = passed(run);
                        runText = "check: run once live: " + RunCommand.describe(run, null);
                    }
                }
                result.recordChecks(resolved, ran);
                print(cli, json, scrub, result, resolved, ran, resolveLine, runText, ledger.entries(), budget,
                        inference);
                return Exit.OK;
            }
        } finally {
            TelemetryArgs.finish(observer, json ? cli.err() : cli.out());
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static boolean passed(RunResult result) {
        return result.status() == Status.PASS || result.status() == Status.DEGRADED;
    }

    private static void print(Cli cli, boolean json, UnaryOperator<String> scrub, AuthoringResult r, Boolean resolved,
                              Boolean ran, String resolveLine, String runText, List<CostLedger.Entry> calls,
                              BigDecimal budget, InferenceSettings inference) {
        if (json) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("scenarioId", r.scenarioId());
            document.put("yaml", r.yaml().toString());
            document.put("plan", r.planFile().toString());
            document.put("authoring", r.authoring().toString());
            document.put("envelope", r.envelope() == null ? null : r.envelope().toString());
            document.put("model", r.model());
            document.put("sessionId", r.sessionId());
            document.put("turns", (long) r.turns());
            document.put("repairs", (long) r.repairs());
            document.put("costUsd", r.costUsd().toPlainString());
            document.put("overBudget", r.overBudget());
            document.put("claudeVersion", r.claudeVersion());
            Map<String, Object> checks = new LinkedHashMap<>();
            checks.put("compiled", true);
            checks.put("resolved", resolved);
            checks.put("ran", ran);
            document.put("checks", checks);
            document.put("instruction", r.instruction());
            document.put("modelCalls", (long) calls.size());
            document.put("modelCost", InferenceArgs.cost(calls).toPlainString());
            document.put("budget", budget.toPlainString());
            document.put("inference", InferenceArgs.summary(inference, calls));
            document.put("exitCode", (long) Exit.OK);
            cli.out().print(scrub.apply(Json.writePretty(document)));
            cli.err().println("model calls: " + calls.size());
            cli.err().println(InferenceArgs.costLine(calls, budget.toPlainString()));
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("authored ").append(r.scenarioId()).append(" with ").append(r.model()).append(" in ")
                .append(r.turns()).append(r.turns() == 1 ? " turn" : " turns").append(", ").append(r.repairs())
                .append(r.repairs() == 1 ? " repair" : " repairs").append(" (session ").append(r.sessionId())
                .append(r.claudeVersion() == null ? "" : ", claude " + r.claudeVersion()).append(")\n");
        text.append("  written: ").append(r.yaml()).append("\n           ").append(r.planFile())
                .append("\n           ").append(r.authoring());
        if (r.envelope() != null) {
            text.append("\n           ").append(r.envelope());
        }
        text.append('\n');
        text.append("  check: it compiles\n  ").append(resolveLine).append('\n');
        if (runText != null) {
            text.append("  ").append(runText.replace("\n", "\n  ")).append('\n');
        }
        if (r.overBudget()) {
            text.append("  note: the planner's reported cost passed --budget-usd\n");
        }
        text.append(r.instruction()).append('\n');
        text.append("model calls: ").append(calls.size()).append('\n');
        text.append(InferenceArgs.costLine(calls, budget.toPlainString())).append('\n');
        cli.out().print(scrub.apply(text.toString()));
    }

    /** {@code --brief} or the text of {@code --brief-file}, exactly one of them. */
    private static String brief(ParsedArgs args) throws UsageException {
        String text = args.value(BRIEF);
        Path file = args.path(BRIEF_FILE);
        if ((text == null) == (file == null)) {
            throw new UsageException("author needs the brief: " + BRIEF + " \"<one paragraph>\" or " + BRIEF_FILE
                    + " <path>, one of them");
        }
        if (file != null) {
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UsageException(BRIEF_FILE + " " + file + " cannot be read: " + e.getClass().getSimpleName());
            }
        }
        if (text.isBlank()) {
            throw new UsageException("the brief is empty");
        }
        return text.strip();
    }

    private static BigDecimal budget(ParsedArgs args) throws UsageException {
        String text = args.value(BUDGET_USD);
        if (text == null) {
            return PlannerRequest.DEFAULT_BUDGET_USD;
        }
        try {
            BigDecimal value = new BigDecimal(text.strip());
            if (value.signum() <= 0) {
                throw new UsageException(BUDGET_USD + " must be more than 0, found " + text);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new UsageException(BUDGET_USD + " needs an amount of US dollars such as 2.00, found '" + text + "'");
        }
    }

    /**
     * The planner's deadline over all its rounds: {@code timeouts.plannerMs} of the inference settings (15 minutes by
     * default, phase5-contracts.md 10.2); null (the planner's own 15-minute default) when the settings carry none.
     */
    static Duration plannerDeadline(InferenceSettings inference) {
        Long ms = inference.timeouts().get(TIMEOUT_PLANNER_MS);
        return ms == null || ms <= 0 ? null : Duration.ofMillis(ms);
    }

    /** The model id of {@code --model}'s class, as the settings name it; the settings' Opus id by default. */
    private static String model(ParsedArgs args, InferenceSettings inference) throws UsageException {
        String chosen = args.value(MODEL);
        String key = chosen == null ? InferenceSettings.MODEL_OPUS : chosen.strip();
        if (!key.equals(InferenceSettings.MODEL_OPUS) && !key.equals(InferenceSettings.MODEL_SONNET)) {
            throw new UsageException(MODEL + " must be opus or sonnet, found '" + chosen + "'");
        }
        return inference.models().get(key);
    }

    /**
     * {@code --claude-command}, else {@code skillroy.claude.command}, else {@code claude} on the {@code PATH}; null
     * when none is found (the planner then answers "claude not found on PATH", exit code 64).
     */
    static Path claudeCommand(ParsedArgs args) throws UsageException {
        Path given = args.path(CLAUDE_COMMAND);
        if (given != null) {
            return given.toAbsolutePath();
        }
        String property = System.getProperty(PROP_CLAUDE_COMMAND);
        if (property != null && !property.isBlank()) {
            return ParsedArgs.toPath(PROP_CLAUDE_COMMAND, property.strip()).toAbsolutePath();
        }
        return findOnPath(System.getenv(), System.getProperty("os.name", ""));
    }

    /**
     * {@code claude} on the {@code PATH} of {@code env}, the first directory that has it winning: a regular,
     * executable file named exactly {@code claude}; on Windows ({@code osName} starting with "Windows") first
     * {@code claude} plus each extension of {@code PATHEXT} in its order, compared without regard to case, then a
     * regular file named exactly {@code claude} (npm installs an extensionless shell shim beside {@code claude.cmd}
     * that Windows cannot start, so the extensions come first). The environment is a parameter so that the lookup is
     * tested on any platform (phase6-contracts.md 9.2).
     *
     * @return the absolute path, or null when none is found
     */
    static Path findOnPath(Map<String, String> env, String osName) {
        boolean windows = osName != null && osName.startsWith("Windows");
        String path = variable(env, "PATH", windows);
        if (path == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        if (windows) {
            String pathext = variable(env, "PATHEXT", true);
            for (String ext : (pathext == null ? ".COM;.EXE;.BAT;.CMD" : pathext).split(";")) {
                String e = ext.strip();
                if (e.startsWith(".") && e.length() > 1 && !e.contains("/") && !e.contains("\\")) {
                    for (String variant : List.of(e, e.toLowerCase(java.util.Locale.ROOT),
                            e.toUpperCase(java.util.Locale.ROOT))) {
                        if (!names.contains("claude" + variant)) {
                            names.add("claude" + variant);
                        }
                    }
                }
            }
        }
        names.add("claude");
        String separator = windows ? ";" : File.pathSeparator;
        for (String dir : path.split(java.util.regex.Pattern.quote(separator))) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Path.of(dir.strip(), name);
                    if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                        return candidate.toAbsolutePath();
                    }
                } catch (RuntimeException e) {
                    // A PATH entry that is not a path is skipped.
                }
            }
        }
        return null;
    }

    /** An environment variable; on Windows its name is compared without regard to case ({@code Path}, {@code PATH}). */
    private static String variable(Map<String, String> env, String name, boolean ignoreCase) {
        String value = env.get(name);
        if (value != null || !ignoreCase) {
            return value;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Writes the planner's MCP configuration (10.2 step 3) into a new owner-only temporary file: this CLI's launcher
     * with {@code mcp --mutating --inference none}, the connection options as given (paths made absolute, the
     * discovery directory always named), the pack jars, the environment, the bindings root and the credentials file.
     */
    static Path mcpConfig(ParsedArgs args, Cli cli, BindArgs.Settings settings) throws IOException, UsageException {
        List<Object> serverArgs = new ArrayList<>();
        String command;
        String launcher = System.getProperty(PROP_LAUNCHER);
        if (launcher != null && !launcher.isBlank()) {
            command = launcher.strip();
        } else {
            command = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            serverArgs.add("-cp");
            serverArgs.add(System.getProperty("java.class.path"));
            serverArgs.add(Main.class.getName());
        }
        serverArgs.addAll(List.of("mcp", McpCommand.MUTATING, InferenceArgs.INFERENCE,
                InferenceSettings.GATEWAY_NONE));
        if (args.value(Connector.PORT) != null) {
            serverArgs.addAll(List.of(Connector.PORT, args.value(Connector.PORT), Connector.TOKEN_FILE,
                    args.path(Connector.TOKEN_FILE).toAbsolutePath().toString()));
        }
        if (args.value(Connector.PID) != null) {
            serverArgs.addAll(List.of(Connector.PID, args.value(Connector.PID)));
        }
        serverArgs.addAll(List.of(Connector.DISCOVERY_DIR, Connector.discoveryDir(args, cli).toString()));
        for (String jar : args.values(Packs.OPTION)) {
            serverArgs.addAll(List.of(Packs.OPTION, ParsedArgs.toPath(Packs.OPTION, jar).toAbsolutePath()
                    .toString()));
        }
        serverArgs.addAll(List.of(BindArgs.ENV, settings.envId()));
        if (settings.root() != null) {
            serverArgs.addAll(List.of(BindArgs.BINDINGS, settings.root().toAbsolutePath().toString()));
        }
        Path credentialsFile = Credentials.locate(settings.credentialsFile(), settings.root(), settings.envId());
        if (credentialsFile != null) {
            serverArgs.addAll(List.of(BindArgs.CREDENTIALS, credentialsFile.toAbsolutePath().toString()));
        }
        Map<String, Object> server = serverCommand(command, serverArgs);
        Map<String, Object> config = Map.of("mcpServers", Map.of("skillroy", server));
        return writeConfig(Path.of(System.getProperty("java.io.tmpdir")), Json.writePretty(config));
    }

    /** The characters {@code cmd.exe /c} splits at or expands in an unquoted argument (phase6-contracts.md 9.2). */
    private static final String CMD_SPECIAL = "\"%^&|<> \t";

    /**
     * The server entry {@code {command, args}} of the MCP configuration. A launcher that is a {@code .bat} or
     * {@code .cmd} (case-insensitive) is started as {@code cmd.exe /c <launcher> <args>}: Node-based clients refuse
     * to spawn a batch file without a shell since the CVE-2024-27980 fix, and the planner must start the same
     * distribution. {@code cmd.exe /c} applies its quote rule to the whole command line, so until a Windows host has
     * shown which quoted form survives the planner's client (9.5) this plain form is written and a launcher path or
     * argument holding a quote, {@code %}, {@code ^}, {@code &}, {@code |}, {@code <}, {@code >} or white space is
     * refused (exit code 64) rather than written into a configuration {@code cmd.exe} would split.
     */
    static Map<String, Object> serverCommand(String launcher, List<Object> serverArgs) throws UsageException {
        Map<String, Object> server = new LinkedHashMap<>();
        String lower = launcher.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            List<Object> args = new ArrayList<>();
            args.add("/c");
            args.add(launcher);
            args.addAll(serverArgs);
            for (Object arg : args.subList(1, args.size())) {
                String text = String.valueOf(arg);
                for (char c : text.toCharArray()) {
                    if (CMD_SPECIAL.indexOf(c) >= 0 || Character.isISOControl(c)) {
                        throw new UsageException("the planner's server would be started through cmd.exe /c, which"
                                + " would split or expand '" + text + "' (it holds " + describe(c) + "): move the"
                                + " installation or the pack to a path without spaces or special characters");
                    }
                }
            }
            server.put("command", "cmd.exe");
            server.put("args", args);
        } else {
            server.put("command", launcher);
            server.put("args", serverArgs);
        }
        return server;
    }

    private static String describe(char c) {
        return switch (c) {
            case ' ' -> "a space";
            case '\t' -> "a tab";
            case '"' -> "a quote";
            default -> Character.isISOControl(c) ? "a control character" : "'" + c + "'";
        };
    }

    /**
     * Writes the configuration into a new owner-only file under {@code tmp} through {@link SecureFiles#writeOwnerOnly}
     * (created exclusively, POSIX {@code rw-------} or an owner-only ACL, refused where the file system has neither;
     * phase6-contracts.md 9.3). The tests' seam.
     */
    static Path writeConfig(Path tmp, String json) throws IOException {
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        Path file = tmp.toAbsolutePath().resolve("skillroy-mcp-" + java.util.HexFormat.of().formatHex(random)
                + ".json");
        SecureFiles.writeOwnerOnly(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** The model calls and their cost of a planner that wrote nothing, on stderr. */
    private static void failureSummary(Cli cli, List<CostLedger.Entry> calls, BigDecimal budget) {
        cli.err().println("model calls: " + calls.size());
        cli.err().println(InferenceArgs.costLine(calls, budget.toPlainString()));
    }
}
