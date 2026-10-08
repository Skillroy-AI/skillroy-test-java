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
import ai.skillroy.testjava.controller.EventStream;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.mcp.ServerOptions;
import ai.skillroy.testjava.mcp.SessionActor;
import ai.skillroy.testjava.mcp.SessionActorFactory;
import ai.skillroy.testjava.mcp.SkillroyMcpServer;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.SessionOpenException;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Resolution;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * {@code skillroy mcp} (phase5-contracts.md 9.2, 13.4): a Model Context Protocol server over stdio for one target and
 * one pack. It connects as {@code run} does, loads the credentials and the inference settings, starts the server
 * ({@link SkillroyMcpServer#start}) with the pack's verbs, the target's primitives and the workflow tools on the
 * process's own standard input and output, the raw streams (the protocol's bytes never pass the CLI's
 * control-character filter; the server masks every answer itself), its log on stderr, and waits for its end.
 * Read-only unless {@code --mutating}; a destructive primitive is never offered.
 *
 * <p>{@code skillroy_session_ask} is offered when the settings name a gateway: its actor is the session service
 * ({@link SessionServices}) over the server's session, acting under the read-only or the acting policy as the server
 * does, within {@code --session-budget}; {@code skillroy_resolve} goes through the service's metered entry then
 * ({@code SessionService.resolve}). Its calls are recorded in the session's ledger and in {@code inference.jsonl} in
 * the session's directory ({@code <out>/runs/<session id>/}). When the server ends, the command writes the session's
 * {@code cost-ledger.jsonl} there, and adds to {@code inference.jsonl} the records of the resolutions' model calls,
 * whether or not the actor was ever made. Gateway {@code none}: no actor, and the tool is not listed.
 * {@code web_wait_flag}, and with {@code --mutating} {@code web_evaluate}, are offered only with
 * {@code --allow-evaluate} (a person's opt-in, D98).
 *
 * <p>While serving, the command watches the agent's event stream and tells the server when it drops
 * ({@link SkillroyMcpServer#agentLost}), so that a loss while the client is idle ends the server too.
 *
 * <p>Exit codes: 0 when the client closed the stream, 3 when the agent was lost (or cannot be reached), 1 when the
 * server cannot start (its session's pre-flight, its output directory, an SQL allow-list its source's schema does not
 * match, a tool name collision), the credentials cannot be loaded, or the client broke the protocol (a line longer
 * than the server takes), 64 for a usage error (an inference setting that cannot be used included). There is no
 * {@code --json}: standard output is the protocol's.
 */
final class McpCommand implements Command {

    static final String MUTATING = "--mutating";
    static final String ALLOW_EVALUATE = "--allow-evaluate";
    private static final String OUT = "--out";
    /** How often the watch looks at the event stream while the server runs. */
    private static final Duration WATCH = Duration.ofMillis(250);

    private static final CommandSpec SPEC = new CommandSpec("mcp", "serve the target and the pack as an MCP server on"
            + " stdio", "", """
            Serves the connected target as a Model Context Protocol server on standard input and output (stdio),
            for an MCP client such as Claude Code: the pack's verbs and the target's primitives as tools, the
            workflow tools (skillroy_catalogue, skillroy_snapshot, skillroy_facts, skillroy_events, skillroy_resolve,
            skillroy_scenario_compile, the session tools) and the run's state as resources. Every action goes
            through one interactive session, journaled; skillroy_session_export writes it as a scenario. Read-only
            by default: --mutating offers the verbs, the mutating primitives and skillroy_scenario_run, which runs a
            scenario only when every call of it is one the server offers. A destructive primitive is never offered,
            nor a verb that reaches one; web_evaluate and web_wait_flag (each runs a script in the target's pages)
            only with --allow-evaluate. skillroy_session_ask (a second model acting through the session) is offered
            when --inference names a gateway, within --session-budget. Captures, runs, the journal and exports go to
            --out (default: a new temporary directory). The server's log goes to stderr. It ends when the client
            closes the stream (exit code 0) or the agent is lost (exit code 3); 1 when it cannot start or the client
            breaks the protocol.""",
            options(), 0, 0);

    /** The options of {@code mcp}, in help order. */
    private static List<OptionSpec> options() {
        List<OptionSpec> options = new ArrayList<>(List.of(Packs.OPTION_SPEC, BindArgs.ENV_OPTION,
                BindArgs.CREDENTIALS_OPTION, BindArgs.BINDINGS_OPTION,
                OptionSpec.flag(MUTATING, "offer the pack's verbs, the mutating primitives and\n"
                        + "skillroy_scenario_run (never a destructive primitive)"),
                OptionSpec.flag(ALLOW_EVALUATE, "offer web_wait_flag, and with --mutating web_evaluate:\n"
                        + "any script in the target's pages, a person's choice\n(never the planner's)"),
                OptionSpec.value(OUT, "dir", "where captures, runs, the journal and exports go\n"
                        + "(default: a new temporary directory)")));
        options.addAll(InferenceArgs.gateway());
        options.add(InferenceArgs.SESSION_BUDGET_OPTION);
        return Connector.withConnection(options.toArray(OptionSpec[]::new));
    }

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        boolean mutating = args.flag(MUTATING);
        boolean allowEvaluate = args.flag(ALLOW_EVALUATE);
        Path out = args.path(OUT);
        if (out != null && Files.exists(out) && !Files.isDirectory(out)) {
            throw new UsageException(OUT + " " + out + " is not a directory");
        }
        BindArgs.Settings settings = BindArgs.parse(args, List.of(), BindMode.LIVE);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            DomainPack pack;
            try {
                pack = packs.select(null);
            } catch (ScenarioException e) {
                throw new UsageException("the server serves one pack: " + e.reason());
            }
            Credentials credentials;
            try {
                credentials = BindArgs.credentials(settings);
            } catch (CredentialsException e) {
                throw new CliException(Exit.FAILED, "the credentials cannot be loaded: " + e.getMessage(), e);
            }
            InferenceSettings inference = InferenceArgs.load(args, BindMode.LIVE, BindArgs.credentialsDir(settings));
            Target target = Connector.connect(args, cli);
            try (target) {
                CostLedger ledger = new CostLedger();
                BindOptions bind = BindOptions.defaults().withMode(BindMode.LIVE).withEnvId(settings.envId())
                        .withBindingsRoot(settings.root()).withCredentials(credentials).withLedger(ledger)
                        .withInference(inference);
                SessionOptions session = SessionOptions.defaults(null).withBind(bind)
                        .withWebPathLoader(packs.loader());
                ServerOptions options = ServerOptions.builder(target.client(), pack).session(session)
                        .mutating(mutating).allowEvaluate(allowEvaluate).outDir(out).inference(inference)
                        .actor(inference.enabled() ? actors(inference, ledger) : null)
                        .build();
                SkillroyMcpServer server;
                try {
                    // The raw streams (9.2): the protocol is not text for a terminal, and the server masks itself.
                    server = SkillroyMcpServer.start(options, cli.in(), SafePrintStream.unwrap(cli.out()), cli.err());
                } catch (SessionOpenException e) {
                    throw new CliException(Exit.FAILED, "the server cannot start: " + e.getMessage(), e);
                }
                Path sessionDir = server.session().options().outputDir();
                try (server) {
                    EventStream stream = target.client().events(-1, "agent.stopping", event -> {
                    });
                    try (stream) {
                        return watch(server, stream);
                    }
                } finally {
                    writeRecords(sessionDir, ledger, credentials.mask(), cli.err());
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * Waits for the server's end, telling it when the agent's event stream drops while it waits.
     *
     * @return the server's exit code
     */
    static int watch(SkillroyMcpServer server, EventStream stream) throws InterruptedException {
        while (true) {
            OptionalInt code = server.awaitExit(WATCH);
            if (code.isPresent()) {
                return code.getAsInt();
            }
            if (!stream.isOpen()) {
                server.agentLost("the agent's event stream ended");
            }
        }
    }

    /**
     * When the server has ended: the session's {@code cost-ledger.jsonl} (every model call of the session, its runs
     * and its resolutions; empty without any) and the resolutions' records added to its {@code inference.jsonl}, in
     * the session's directory, whether or not the actor was ever made (phase5-contracts.md 13.4).
     */
    static void writeRecords(Path dir, CostLedger ledger, SecretMask mask, PrintStream err) {
        if (dir == null) {
            return;
        }
        try {
            InferenceArgs.writeLedger(dir, ledger.entries(), mask);
        } catch (IOException | RuntimeException e) {
            err.println("skillroy mcp: the session's cost-ledger.jsonl cannot be written: "
                    + e.getClass().getSimpleName());
        }
        try {
            InferenceArgs.appendInferenceRecords(dir, ledger, mask);
        } catch (IOException | RuntimeException e) {
            err.println("skillroy mcp: the session's inference.jsonl cannot be written: "
                    + e.getClass().getSimpleName());
        }
    }

    /**
     * The actor of {@code skillroy_session_ask} and {@code skillroy_resolve}: the session service over the server's
     * session, under the read-only policy unless the server offers mutating tools.
     */
    static SessionActorFactory actors(InferenceSettings inference, CostLedger ledger) {
        return (session, options) -> {
            SessionPolicy policy = options.mutating() ? SessionPolicy.acting() : SessionPolicy.readOnlyPolicy();
            Path dir = session.options().outputDir();
            return new ServiceActor(SessionServices.open(session, inference, policy, ledger, dir));
        };
    }

    /** The session service as the server's actor; the command writes the session's records when the server ends. */
    private static final class ServiceActor implements SessionActor {

        private final SessionServices.Opened opened;

        ServiceActor(SessionServices.Opened opened) {
            this.opened = opened;
        }

        @Override
        public Object ask(String request) throws AgentLostException, InterruptedException {
            return SessionServices.turnJson(opened.service().ask(request));
        }

        /** Through the service's metered entry, so that a model token counts on the session's budget (13.4). */
        @Override
        public Resolution resolve(ExecutorSession session, String slot, String kind, Map<String, Object> args,
                                  List<String> strategy, Origin origin)
                throws BindException, AgentLostException, InterruptedException {
            return opened.service().resolve(slot, kind, args, strategy, List.of(), null, origin);
        }

        @Override
        public void close() {
            opened.close();
        }
    }
}
