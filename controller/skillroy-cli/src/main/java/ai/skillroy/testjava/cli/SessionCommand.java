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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.CheckOutcome;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.ExportException;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.ExportResult;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.SessionOpenException;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code skillroy session} (phase5-contracts.md 12, 13.4; decision B15's "one way in"): an interactive session on the
 * live target. It opens an {@link ExecutorSession} (the connection, the pack, {@code --out}, {@code --read-only}) and,
 * unless the settings name no gateway, a session service ({@link SessionServices}) under the acting or the read-only
 * policy, with the base primitives by {@code --allow-base-primitives}, {@code --max-actions} actions a turn and the
 * session budget ({@code --budget-session}). Then a read-eval-print loop on the terminal ({@link Terminal}), or, with
 * {@code --script <file>}, the same commands from a file:
 *
 * <ul>
 *   <li>{@code ask <text>}: one turn of the model (the person's request; the model acts through the session);</li>
 *   <li>{@code do <verb> [<json>]}: a verb of the pack or a base verb, its arguments as a scenario writes them;</li>
 *   <li>{@code call <primitive> [<json>]}: one primitive (a mutating {@code swing.*} primitive settles after);</li>
 *   <li>{@code resolve <slot> <kind> [<json>] [--strategy a,b] [--credential <realm>.<key>]}: a live resolution that
 *       joins the session's slots; {@code --credential} declares the slot's credential as a scenario's
 *       {@code credential: {realm, key}} does, so that a verb that signs the value on gets its secret from the
 *       credentials file (and the export carries the declaration);</li>
 *   <li>{@code check <path> <matcher-json>}, {@code observe <path>}: an expectation now, a value observed now;</li>
 *   <li>{@code snapshot [max]}, {@code facts <root> [path]}, {@code events [since]}: reads;</li>
 *   <li>{@code note <text>}: a note in the journal;</li>
 *   <li>{@code export <scenarioId> [--approved-by <label>] [--out <dir>]}: the journal as a scenario, its plan and
 *       its bindings file ({@code <dir>/<id>.yaml}, {@code <dir>/plan/<id>.plan.json},
 *       {@code <dir>/bindings/<env>/<id>.bindings.json}; the directory defaults to the session's), and the report;</li>
 *   <li>{@code quit}.</li>
 * </ul>
 *
 * <p>The person acts as {@code Origin.person("cli")}; the model as itself. With a session service, {@code resolve} goes
 * through its metered entry ({@code SessionService.resolve}), so that a model-written resolution counts on the
 * session's budget. Everything is journaled in {@code <out>/journal.jsonl}; the model's calls go to
 * {@code <out>/inference.jsonl} (the session model's as they are made, the resolutions' when the session ends) and,
 * when the session ends, every call of the session to {@code <out>/cost-ledger.jsonl}. Everything printed passes the
 * credentials' mask and the secrets the session joined, and the CLI's control-character rule. A blank line or one that
 * starts with {@code #} is skipped; a command that fails says why and the session goes on.
 *
 * <p>Exit codes: 0; 1 when the agent was lost, when the session cannot open, or when the last {@code export} failed;
 * 3 when the agent cannot be reached; 64 for a usage error (no terminal and no {@code --script}, a settings error).
 */
final class SessionCommand implements Command {

    static final String OUT = "--out";
    static final String READ_ONLY = "--read-only";
    static final String ALLOW_BASE = "--allow-base-primitives";
    static final String MAX_ACTIONS = "--max-actions";
    static final String ID = "--id";
    static final String SCRIPT = "--script";

    /** The session's commands, exactly those of phase5-contracts.md 13.4. */
    static final List<String> COMMANDS = List.of("ask", "do", "call", "resolve", "check", "observe", "snapshot",
            "facts", "events", "note", "export", "quit");

    /** Who the person at the CLI is in the journal. */
    static final Origin PERSON = Origin.person("cli");

    private static final int SNAPSHOT_DEFAULT = 20_000;
    private static final int EVENTS_MAX = 200;
    /** An option at the end of a {@code resolve} line: {@code --strategy a,b} or {@code --credential realm.key}. */
    private static final Pattern RESOLVE_OPTION = Pattern.compile("(?:^|\\s)--(strategy|credential)\\s+(\\S+)\\s*$");
    private static final String RESOLVE_USAGE = "resolve <slot> <kind> [<json>] [--strategy a,b] [--credential"
            + " <realm>.<key>]";
    private static final Pattern SESSION_ID = Pattern.compile("[a-z0-9-]+");

    private static final CommandSpec SPEC = new CommandSpec("session", "an interactive session on the target, a model"
            + " acting on request", "", """
            Opens an interactive session on the connected target, every action journaled in <out>/journal.jsonl,
            and reads commands from the terminal, or from --script <file>:
              ask <text>                         a model carries out the request through the session
              do <verb> [<json>]                 a verb, its arguments as a scenario writes them
              call <primitive> [<json>]          one primitive
              resolve <slot> <kind> [<json>] [--strategy a,b] [--credential <realm>.<key>]
              check <path> <matcher-json>        an expectation, evaluated now
              observe <path>                     a value, journaled as observed
              snapshot [max] | facts <root> [path] | events [since]
              note <text>                        a note in the journal
              export <scenarioId> [--approved-by <label>] [--out <dir>]
              quit
            ask needs a model gateway (--inference claude with ANTHROPIC_API_KEY, or recorded); the model acts
            through the pack's verbs (the base primitives only with --allow-base-primitives), at most --max-actions
            actions a turn, within --budget-session. --read-only refuses every action that changes the target.
            resolve --credential staff.code declares the slot's credential (credential: {realm: staff, key: code}
            in a scenario), so that a verb that signs the value on gets its password from the credentials file.
            export writes the journal as a scenario that replays with zero model calls: <dir>/<id>.yaml,
            <dir>/plan/<id>.plan.json and <dir>/bindings/<env>/<id>.bindings.json (default <dir>: the session's
            --out). Exit code 0, 1 when the agent was lost or the last export failed.""",
            options(), 0, 0);

    /** The options of {@code session}, in help order. */
    private static List<OptionSpec> options() {
        List<OptionSpec> options = new ArrayList<>(List.of(Packs.OPTION_SPEC, BindArgs.ENV_OPTION,
                BindArgs.CREDENTIALS_OPTION, BindArgs.BINDINGS_OPTION,
                OptionSpec.value(OUT, "dir", "where the journal, the model's calls and the exports go\n"
                        + "(default run-output/<session id>/)"),
                OptionSpec.flag(READ_ONLY, "refuse every action that changes the target"),
                OptionSpec.flag(ALLOW_BASE, "offer the model the base swing_* and web_* actions too"),
                OptionSpec.value(MAX_ACTIONS, "n", "the most actions one turn of the model may take (10)"),
                OptionSpec.value(ID, "session-id", "the session id, [a-z0-9-]+ (default: generated)"),
                OptionSpec.value(SCRIPT, "file", "read the commands from this file instead of the terminal")));
        options.addAll(InferenceArgs.gateway());
        options.add(InferenceArgs.BUDGET_SESSION_OPTION);
        options.add(HooksArgs.OPTION);
        options.addAll(TelemetryArgs.OPTIONS);
        options.add(OptionSpec.flag("--json", "print each command's result as one JSON object per line"));
        return Connector.withConnection(options.toArray(OptionSpec[]::new));
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        boolean json = args.flag("--json");
        Path out = args.path(OUT);
        if (out != null && Files.exists(out) && !Files.isDirectory(out)) {
            throw new UsageException(OUT + " " + out + " is not a directory");
        }
        Path script = args.path(SCRIPT);
        List<String> lines = null;
        if (script != null) {
            try {
                lines = Files.readAllLines(script, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UsageException(SCRIPT + " " + script + " cannot be read: " + e.getClass().getSimpleName());
            }
        } else if (!cli.terminal().interactive()) {
            throw new UsageException("session needs a terminal to read its commands from, or " + SCRIPT + " <file>");
        }
        Long maxActions = args.longValue(MAX_ACTIONS, 1, 100);
        String id = args.value(ID);
        if (id != null && !SESSION_ID.matcher(id).matches()) {
            throw new UsageException(ID + " must match [a-z0-9-]+, found '" + id + "'");
        }
        BindArgs.Settings settings = BindArgs.parse(args, List.of(), BindMode.LIVE);
        HooksMode hooks = HooksArgs.mode(args);
        RunObserver observer = RunObserver.NONE;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            DomainPack pack;
            try {
                pack = packs.select(null);
            } catch (ScenarioException e) {
                throw new UsageException("a session runs one pack: " + e.reason());
            }
            Credentials credentials;
            try {
                credentials = BindArgs.credentials(settings);
            } catch (CredentialsException e) {
                throw new CliException(Exit.FAILED, "the credentials cannot be loaded: " + e.getMessage(), e);
            }
            InferenceSettings inference = InferenceArgs.load(args, BindMode.LIVE, BindArgs.credentialsDir(settings));
            Clock clock = Clock.systemUTC();
            String sessionId = id != null ? id : "session-" + Long.toString(clock.millis(), 36);
            Path dir = out != null ? out : Path.of("run-output", sessionId);
            // phase6-contracts.md 10.3: before the agent is contacted; <dir>/telemetry/ for the file exporter.
            observer = TelemetryArgs.open(TelemetryArgs.load(args, BindArgs.credentialsDir(settings), dir), cli);
            Target target = Connector.connect(args, cli);
            TelemetryArgs.started(observer, TelemetryArgs.runStart(observer, "session", null, BindMode.LIVE.label(),
                    settings.envId(), target, null));
            try (target) {
                CostLedger ledger = new CostLedger();
                BindOptions bind = BindOptions.defaults().withMode(BindMode.LIVE).withEnvId(settings.envId())
                        .withBindingsRoot(settings.root()).withCredentials(credentials).withLedger(ledger)
                        .withInference(inference);
                // Recover on close (D95): the desk is left as a run leaves it, so that the export replays at once.
                SessionOptions options = SessionOptions.defaults(dir).withClock(clock).withBind(bind)
                        .withReadOnly(args.flag(READ_ONLY)).withRecoverOnClose(true)
                        .withWebPathLoader(packs.loader()).withId(sessionId).withHooks(hooks)
                        .withObserver(observer);
                ExecutorSession session;
                try {
                    session = ExecutorSession.open(pack, target.client(), options);
                } catch (SessionOpenException e) {
                    throw new CliException(Exit.FAILED, "the session cannot open: " + e.getMessage(), e);
                }
                Repl repl = new Repl(cli, session, target, credentials.mask(), json, dir, settings.envId(), pack);
                try {
                    if (inference.enabled()) {
                        SessionPolicy policy = args.flag(READ_ONLY) ? SessionPolicy.readOnlyPolicy()
                                : SessionPolicy.acting();
                        policy = policy.withBasePrimitives(args.flag(ALLOW_BASE));
                        if (maxActions != null) {
                            policy = policy.withMaxActionsPerTurn(maxActions.intValue());
                        }
                        try {
                            repl.service = SessionServices.open(session, inference, policy, ledger, dir);
                        } catch (InferenceException | SettingsException e) {
                            repl.say("the model gateway cannot be opened (" + e.getMessage() + "); ask is not"
                                    + " available");
                        }
                    }
                    repl.loop(lines);
                } finally {
                    repl.close();
                    try {
                        InferenceArgs.writeLedger(dir, ledger.entries(), credentials.mask());
                    } catch (IOException e) {
                        cli.err().println("skillroy session: " + dir.resolve("cost-ledger.jsonl")
                                + " cannot be written: " + e.getClass().getSimpleName());
                    }
                    try {
                        InferenceArgs.appendInferenceRecords(dir, ledger, credentials.mask());
                    } catch (IOException e) {
                        cli.err().println("skillroy session: " + dir.resolve(SessionServices.INFERENCE_FILE)
                                + " cannot be written: " + e.getClass().getSimpleName());
                    }
                }
                repl.summary(ledger.entries(), inference.budgets().get(InferenceSettings.BUDGET_SESSION));
                return repl.agentLost || repl.lastExportFailed ? Exit.FAILED : Exit.OK;
            }
        } finally {
            TelemetryArgs.finish(observer, json ? cli.err() : cli.out());
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** One command's result: what {@code --json} prints, what a person reads, and whether it failed. */
    private record Result(Map<String, Object> json, String text, boolean failed) {
    }

    /** The loop over one session; used on the command's thread. */
    private static final class Repl {

        private final Cli cli;
        private final ExecutorSession session;
        private final Target target;
        private final SecretMask credentials;
        private final boolean json;
        private final Path dir;
        private final String envId;
        private final DomainPack pack;
        SessionServices.Opened service;
        boolean agentLost;
        boolean lastExportFailed;
        private int turns;

        Repl(Cli cli, ExecutorSession session, Target target, SecretMask credentials, boolean json, Path dir,
             String envId, DomainPack pack) {
            this.cli = cli;
            this.session = session;
            this.target = target;
            this.credentials = credentials;
            this.json = json;
            this.dir = dir;
            this.envId = envId;
            this.pack = pack;
        }

        /** Text with every secret the session knows replaced, and the connection's token. */
        private String mask(String text) {
            String t = credentials.apply(target.scrub(text));
            SecretMask joined = session.bindings() == null ? null : session.bindings().secrets();
            return joined == null ? t : joined.apply(t);
        }

        private Object mask(Object value) {
            Object v = credentials.apply(value);
            SecretMask joined = session.bindings() == null ? null : session.bindings().secrets();
            return joined == null ? v : joined.apply(v);
        }

        /** A line for the person (stderr with --json, which keeps stdout to the JSON lines). */
        void say(String text) {
            (json ? cli.err() : cli.out()).println(mask(text));
        }

        /** Reads and runs commands until quit, the end of the input, or a lost agent. */
        void loop(List<String> script) throws InterruptedException {
            Iterator<String> lines = script == null ? null : script.iterator();
            say("session " + session.id() + " on pack " + pack.id() + " " + pack.version() + ", environment "
                    + envId + (session.options().readOnly() ? " (read-only)" : "") + "; journal "
                    + dir.resolve(StepJournal.FILE) + (service == null ? "; no model (ask is not available)" : ""));
            while (!agentLost) {
                String line;
                if (lines != null) {
                    if (!lines.hasNext()) {
                        return;
                    }
                    line = lines.next();
                } else {
                    line = cli.terminal().readLine("skillroy> ");
                    if (line == null) {
                        return;
                    }
                }
                String command = line.strip();
                if (command.isEmpty() || command.startsWith("#")) {
                    continue;
                }
                if (lines != null && !json) {
                    cli.out().println(mask("> " + command));
                }
                int space = command.indexOf(' ');
                String word = space < 0 ? command : command.substring(0, space);
                String rest = space < 0 ? "" : command.substring(space + 1).strip();
                if (word.equals("quit")) {
                    return;
                }
                Result result;
                try {
                    result = run(word, rest);
                } catch (AgentLostException e) {
                    agentLost = true;
                    result = error(word, "the agent was lost: " + e.getMessage());
                } catch (Usage e) {
                    result = error(word, e.getMessage());
                }
                print(word, result);
            }
        }

        private void print(String word, Result result) {
            if (json) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("command", word);
                line.put("ok", !result.failed());
                line.putAll(result.json());
                cli.out().println(mask(Json.write(mask((Object) line))));
            } else {
                String text = result.text();
                cli.out().print(mask(text.endsWith("\n") ? text : text + "\n"));
            }
            cli.out().flush();
        }

        private Result run(String word, String rest) throws AgentLostException, InterruptedException, Usage {
            return switch (word) {
                case "ask" -> ask(rest);
                case "do" -> doVerb(rest);
                case "call" -> call(rest);
                case "resolve" -> resolve(rest);
                case "check" -> check(rest);
                case "observe" -> observe(rest);
                case "snapshot" -> snapshot(rest);
                case "facts" -> facts(rest);
                case "events" -> events(rest);
                case "note" -> note(rest);
                case "export" -> export(rest);
                default -> throw new Usage("unknown command '" + word + "'; commands: " + String.join(", ",
                        COMMANDS));
            };
        }

        private Result ask(String request) throws AgentLostException, InterruptedException, Usage {
            if (service == null) {
                throw new Usage("no model gateway: ask needs --inference claude (with ANTHROPIC_API_KEY) or"
                        + " recorded");
            }
            if (request.isEmpty()) {
                throw new Usage("ask needs the request: ask <text>");
            }
            Turn turn = service.service().ask(request);
            turns++;
            if (turn.outcome() == Turn.Outcome.LOST) {
                agentLost = true;
            }
            return new Result(SessionServices.turnJson(turn), SessionServices.turnText(turn),
                    turn.outcome() == Turn.Outcome.LOST);
        }

        private Result doVerb(String rest) throws AgentLostException, InterruptedException, Usage {
            String[] parts = head(rest, "do <verb> [<json>]");
            Object args = parts[1].isEmpty() ? null : json(parts[1], "the arguments of " + parts[0]);
            return outcome(session.execute(parts[0], args, PERSON), false);
        }

        private Result call(String rest) throws AgentLostException, InterruptedException, Usage {
            String[] parts = head(rest, "call <primitive> [<json>]");
            Map<String, Object> params = parts[1].isEmpty() ? Map.of() : object(parts[1], "the parameters of "
                    + parts[0]);
            PrimitiveDescriptor descriptor = session.primitives().get(parts[0]);
            Sync sync = descriptor != null && descriptor.mutating() && parts[0].startsWith("swing.") ? Sync.SETTLE
                    : Sync.NONE;
            return outcome(session.execute(PlannedCall.of(parts[0], params, sync), PERSON), true);
        }

        private Result resolve(String rest) throws AgentLostException, InterruptedException, Usage {
            List<String> strategy = null;
            CredentialSpec credential = null;
            String body = rest;
            Matcher m;
            while ((m = RESOLVE_OPTION.matcher(body)).find()) {
                if (m.group(1).equals("strategy")) {
                    if (strategy != null) {
                        throw new Usage("resolve: --strategy is given twice");
                    }
                    strategy = List.of(m.group(2).split(","));
                } else {
                    if (credential != null) {
                        throw new Usage("resolve: --credential is given twice");
                    }
                    credential = credential(m.group(2));
                }
                body = body.substring(0, m.start()).strip();
            }
            String[] slot = head(body, RESOLVE_USAGE);
            String[] kind = head(slot[1], RESOLVE_USAGE);
            Map<String, Object> queryArgs = kind[1].isEmpty() ? Map.of() : object(kind[1], "the arguments of "
                    + kind[0]);
            Resolution r;
            try {
                r = service == null
                        ? session.resolve(slot[0], kind[0], queryArgs, strategy, List.of(), credential, PERSON)
                        : service.service().resolve(slot[0], kind[0], queryArgs, strategy, List.of(), credential,
                                PERSON);
            } catch (BindException e) {
                return error("resolve", e.kind() + (e.reason() == null ? "" : " " + e.reason()) + ": "
                        + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new Usage(e.getMessage());
            }
            Map<String, Object> journaled = Map.of();
            List<StepJournal.Entry> entries = session.journal().entries();
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i) instanceof StepJournal.ResolveEntry entry && entry.slot().equals(r.slot())) {
                    journaled = entry.resolution();
                    break;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("slot", r.slot());
            result.put("kind", r.query());
            result.put("value", journaled.get("value"));
            result.put("strategy", r.strategy().name());
            result.put("resolver", r.provenance() == null ? null : r.provenance().resolverId());
            result.put("approval", r.approval() == null ? null : r.approval().state().name());
            result.put("credential", credential == null ? null : credential.realm() + "." + credential.key());
            String text = r.slot() + " = " + Json.write(mask(result.get("value"))) + "  (" + result.get("resolver")
                    + ", " + r.strategy() + (credential == null ? "" : ", credential " + result.get("credential"))
                    + ")";
            return new Result(result, text, false);
        }

        /**
         * {@code --credential <realm>.<key>}: the slot's credential declaration, as a scenario's
         * {@code credential: {realm, key}} writes it (the realm of the credentials file, the value's field that holds
         * the entry id).
         */
        static CredentialSpec credential(String text) throws Usage {
            int dot = text.indexOf('.');
            if (dot <= 0 || dot == text.length() - 1) {
                throw new Usage("resolve: --credential needs <realm>.<key> (credential: {realm, key} in a scenario),"
                        + " found '" + text + "'");
            }
            try {
                return new CredentialSpec(text.substring(0, dot), text.substring(dot + 1));
            } catch (IllegalArgumentException e) {
                throw new Usage("resolve: --credential: " + e.getMessage());
            }
        }

        private Result check(String rest) throws AgentLostException, InterruptedException, Usage {
            String[] parts = head(rest, "check <path> <matcher-json>");
            if (parts[1].isEmpty()) {
                throw new Usage("check needs a matcher: check <path> <matcher-json>");
            }
            Object matcher = lenientJson(parts[1]);
            CheckOutcome outcome = session.check(parts[0], matcher, PERSON);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", outcome.path());
            result.put("matcher", matcher);
            result.put("held", outcome.held());
            result.put("actual", outcome.actual());
            result.put("reason", outcome.reason());
            String text = outcome.path() + " " + Json.write(matcher) + ": " + (outcome.held() ? "held"
                    : "did not hold: " + outcome.reason()) + " (actual " + Json.write(outcome.actual()) + ")";
            return new Result(result, text, !outcome.held());
        }

        private Result observe(String rest) throws AgentLostException, InterruptedException, Usage {
            if (rest.isEmpty() || rest.contains(" ")) {
                throw new Usage("observe needs one path: observe <path>");
            }
            Object value;
            try {
                value = session.observe(rest, PERSON);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return error("observe", e.getMessage());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", rest);
            result.put("value", value);
            return new Result(result, rest + " = " + Json.write(value) + "  (journaled as observed)", false);
        }

        private Result snapshot(String rest) throws AgentLostException, InterruptedException, Usage {
            int max = SNAPSHOT_DEFAULT;
            if (!rest.isEmpty()) {
                try {
                    max = Integer.parseInt(rest);
                } catch (NumberFormatException e) {
                    throw new Usage("snapshot takes a number of characters: snapshot [max]");
                }
                if (max < 100 || max > 1_000_000) {
                    throw new Usage("snapshot: max must be between 100 and 1000000");
                }
            }
            String text = session.snapshotText(max);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snapshot", text);
            return new Result(result, text, false);
        }

        private Result facts(String rest) throws AgentLostException, InterruptedException, Usage {
            String[] parts = head(rest, "facts <root> [path]");
            if (parts[1].contains(" ")) {
                throw new Usage("facts takes a root and one path: facts <root> [path]");
            }
            Object value;
            try {
                value = session.fact(parts[0], parts[1].isEmpty() ? null : parts[1]);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return error("facts", e.getMessage());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("root", parts[0]);
            result.put("path", parts[1].isEmpty() ? null : parts[1]);
            result.put("value", value);
            return new Result(result, Json.writePretty(value).stripTrailing(), false);
        }

        private Result events(String rest) throws Usage {
            long since = 0;
            if (!rest.isEmpty()) {
                try {
                    since = Long.parseLong(rest);
                } catch (NumberFormatException e) {
                    throw new Usage("events takes a sequence number: events [since]");
                }
                if (since < 0) {
                    throw new Usage("events: since must not be negative");
                }
            }
            List<Event> events = session.eventsSince(since, null, EVENTS_MAX);
            List<Object> list = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Event e : events) {
                list.add(e.toJson());
                text.append(e.seq()).append(' ').append(e.topic()).append(' ').append(Json.write(e.payload()))
                        .append('\n');
            }
            if (events.isEmpty()) {
                text.append("no events after ").append(since).append('\n');
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("events", list);
            return new Result(result, text.toString(), false);
        }

        private Result note(String rest) throws Usage {
            if (rest.isEmpty()) {
                throw new Usage("note needs a text: note <text>");
            }
            session.note(rest, PERSON);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("noted", true);
            return new Result(result, "noted", false);
        }

        private Result export(String rest) throws Usage {
            List<String> words = words(rest);
            if (words.isEmpty() || words.get(0).startsWith("--")) {
                throw new Usage("export needs a scenario id: export <scenarioId> [--approved-by <label>] [--out"
                        + " <dir>]");
            }
            String scenarioId = words.get(0);
            String approvedBy = null;
            Path exportDir = dir;
            for (int i = 1; i < words.size(); i++) {
                String option = words.get(i);
                if (i + 1 >= words.size() || !(option.equals("--approved-by") || option.equals("--out"))) {
                    throw new Usage("export: unexpected '" + option + "'; export <scenarioId> [--approved-by"
                            + " <label>] [--out <dir>]");
                }
                String value = words.get(++i);
                if (option.equals("--approved-by")) {
                    approvedBy = value;
                } else {
                    try {
                        exportDir = Path.of(value);
                    } catch (InvalidPathException e) {
                        throw new Usage("export --out: not a valid path: " + value);
                    }
                }
            }
            ExportOptions options;
            try {
                options = ExportOptions.of(scenarioId).withApprovedBy(approvedBy);
            } catch (IllegalArgumentException e) {
                throw new Usage("export: " + e.getMessage());
            }
            lastExportFailed = true;
            ExportResult result;
            try {
                result = session.journal().export(options);
            } catch (ExportException e) {
                return error("export", "the session cannot be exported: " + e.getMessage());
            }
            byte[] yaml = result.yaml().getBytes(StandardCharsets.UTF_8);
            Path yamlFile = exportDir.resolve(scenarioId + ".yaml");
            Path planFile = exportDir.resolve("plan").resolve(scenarioId + ".plan.json");
            Path bindingsFile = result.bindings() == null ? null
                    : BindingsFile.locate(exportDir.resolve("bindings"), envId, scenarioId);
            try {
                Plan plan = PlanCompiler.compile(yaml, scenarioId + ".yaml", pack);
                Files.createDirectories(exportDir);
                Files.write(yamlFile, yaml);
                plan.write(planFile);
                if (bindingsFile != null) {
                    result.bindings().write(bindingsFile);
                }
            } catch (ScenarioException e) {
                return error("export", "the exported scenario does not compile: " + e.getMessage());
            } catch (IOException e) {
                return error("export", "the files cannot be written: " + e);
            }
            lastExportFailed = false;
            StringBuilder text = new StringBuilder(result.report().text());
            text.append("Written:\n  ").append(yamlFile).append("\n  ").append(planFile).append('\n');
            if (bindingsFile != null) {
                text.append("  ").append(bindingsFile).append('\n');
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("scenarioId", scenarioId);
            json.put("steps", (long) result.report().steps());
            json.put("inference", result.report().inference());
            json.put("yaml", yamlFile.toString());
            json.put("plan", planFile.toString());
            json.put("bindings", bindingsFile == null ? null : bindingsFile.toString());
            json.put("report", result.report().text());
            return new Result(json, text.toString(), false);
        }

        /** A step's outcome: one line with the status, then a call's value, then the detail when it did not pass. */
        private Result outcome(StepOutcome o, boolean showValue) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stepId", o.stepId());
            result.put("verb", o.verb());
            result.put("status", o.status().name());
            result.put("failureKind", o.failureKind() == null ? null : o.failureKind().name());
            result.put("message", o.message());
            result.put("executed", (long) o.executed());
            result.put("detail", o.detail());
            result.put("value", o.lastResult() == null ? null : o.lastResult().value());
            StringBuilder text = new StringBuilder(o.stepId()).append(' ')
                    .append(o.verb() != null ? o.verb() : o.calls().isEmpty() ? "call" : o.calls().get(0).primitive())
                    .append(": ").append(o.status());
            if (o.failureKind() != null) {
                text.append(' ').append(o.failureKind());
            }
            if (o.message() != null) {
                text.append(": ").append(o.message());
            }
            if (showValue && o.lastResult() != null) {
                text.append('\n').append(Text.indent(Json.writePretty(o.lastResult().value()).stripTrailing(), "  "));
            }
            if (o.detail() != null && !o.detail().isEmpty()) {
                text.append("\n  detail: ").append(Json.write(o.detail()));
            }
            boolean passed = o.status() == ai.skillroy.testjava.scenario.Status.PASS
                    || o.status() == ai.skillroy.testjava.scenario.Status.DEGRADED;
            return new Result(result, text.toString(), !passed);
        }

        private static Result error(String command, String message) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("error", message);
            return new Result(json, command + ": " + message, true);
        }

        /** The first word and the rest of a command's arguments. */
        private static String[] head(String rest, String usage) throws Usage {
            if (rest.isEmpty()) {
                throw new Usage("usage: " + usage);
            }
            int space = rest.indexOf(' ');
            return space < 0 ? new String[] {rest, ""} : new String[] {rest.substring(0, space),
                    rest.substring(space + 1).strip()};
        }

        private static Object json(String text, String what) throws Usage {
            try {
                return Json.parse(text);
            } catch (JsonException e) {
                throw new Usage(what + " are not valid JSON: " + e.getMessage());
            }
        }

        private static Map<String, Object> object(String text, String what) throws Usage {
            if (!(json(text, what) instanceof Map<?, ?> map)) {
                throw new Usage(what + " must be a JSON object");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }

        /** JSON when the text is JSON, else the text itself ({@code check state.name LOAN}). */
        private static Object lenientJson(String text) {
            try {
                return Json.parse(text);
            } catch (JsonException e) {
                return text;
            }
        }

        /** Words split at spaces, a word in single or double quotes kept whole. */
        static List<String> words(String text) throws Usage {
            List<String> words = new ArrayList<>();
            StringBuilder word = null;
            char quote = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    } else {
                        word.append(c);
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                    if (word == null) {
                        word = new StringBuilder();
                    }
                } else if (Character.isWhitespace(c)) {
                    if (word != null) {
                        words.add(word.toString());
                        word = null;
                    }
                } else {
                    if (word == null) {
                        word = new StringBuilder();
                    }
                    word.append(c);
                }
            }
            if (quote != 0) {
                throw new Usage("an unclosed quote in: " + text);
            }
            if (word != null) {
                words.add(word.toString());
            }
            return words;
        }

        /** The session's end: its steps, turns, model calls and their cost. */
        void summary(List<CostLedger.Entry> calls, String budget) {
            long steps = session.journal().entries().stream().filter(e -> e instanceof StepJournal.StepEntry)
                    .count();
            if (json) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("session", session.id());
                line.put("steps", steps);
                line.put("turns", (long) turns);
                line.put("modelCalls", (long) calls.size());
                line.put("modelCost", InferenceArgs.cost(calls).toPlainString());
                line.put("budget", budget);
                line.put("agentLost", agentLost);
                line.put("exitCode", (long) (agentLost || lastExportFailed ? Exit.FAILED : Exit.OK));
                cli.out().println(credentials.apply(Json.write(credentials.apply((Object) line))));
            }
            PrintStream summaryStream = json ? cli.err() : cli.out();
            summaryStream.println(credentials.apply("session " + session.id() + " ended: " + steps + " step"
                    + (steps == 1 ? "" : "s") + ", " + turns + " turn" + (turns == 1 ? "" : "s")
                    + (agentLost ? "; the agent was lost" : "")));
            summaryStream.println("model calls: " + calls.size());
            summaryStream.println(InferenceArgs.costLine(calls, budget));
        }

        /** Closes the service (and its gateway) and the session; never throws. */
        void close() {
            try {
                if (service != null) {
                    service.close();
                }
            } catch (RuntimeException e) {
                cli.err().println("skillroy session: the session service did not close cleanly: " + e);
            } finally {
                session.close();
            }
        }
    }

    /** A command line of the session that is wrong: said, and the session goes on. */
    private static final class Usage extends Exception {

        private static final long serialVersionUID = 1L;

        Usage(String message) {
            super(message, null, false, false);
        }
    }
}
