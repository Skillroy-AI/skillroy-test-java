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
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.SessionOpenException;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * does, within {@code --session-budget}; its calls are recorded in the session's ledger and in {@code inference.jsonl}
 * in the session's directory ({@code <out>/runs/<session id>/}), where {@code cost-ledger.jsonl} is written when the
 * server ends. Gateway {@code none}: no actor, and the tool is not listed.
 *
 * <p>While serving, the command watches the agent's event stream and tells the server when it drops
 * ({@link SkillroyMcpServer#agentLost}), so that a loss while the client is idle ends the server too.
 *
 * <p>Exit codes: 0 when the client closed the stream, 3 when the agent was lost (or cannot be reached), 1 when the
 * server cannot start (its session's pre-flight, its output directory, a tool name collision) or the credentials cannot
 * be loaded, 64 for a usage error (an inference setting that cannot be used included). There is no {@code --json}:
 * standard output is the protocol's.
 */
final class McpCommand implements Command {

    static final String MUTATING = "--mutating";
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
            by default: --mutating offers the verbs, the mutating primitives and skillroy_scenario_run. A destructive
            primitive is never offered. skillroy_session_ask (a second model acting through the session) is offered
            when --inference names a gateway, within --session-budget. Captures, runs, the journal and exports go to
            --out (default: a new temporary directory). The server's log goes to stderr. It ends when the client
            closes the stream (exit code 0) or the agent is lost (exit code 3); 1 when it cannot start.""",
            options(), 0, 0);

    /** The options of {@code mcp}, in help order. */
    private static List<OptionSpec> options() {
        List<OptionSpec> options = new ArrayList<>(List.of(Packs.OPTION_SPEC, BindArgs.ENV_OPTION,
                BindArgs.CREDENTIALS_OPTION, BindArgs.BINDINGS_OPTION,
                OptionSpec.flag(MUTATING, "offer the pack's verbs, the mutating primitives and\n"
                        + "skillroy_scenario_run (never a destructive primitive)"),
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
                        .mutating(mutating).outDir(out).inference(inference)
                        .actor(inference.enabled() ? actors(inference, ledger, credentials.mask(), cli.err()) : null)
                        .build();
                SkillroyMcpServer server;
                try {
                    // The raw streams (9.2): the protocol is not text for a terminal, and the server masks itself.
                    server = SkillroyMcpServer.start(options, cli.in(), SafePrintStream.unwrap(cli.out()), cli.err());
                } catch (SessionOpenException e) {
                    throw new CliException(Exit.FAILED, "the server cannot start: " + e.getMessage(), e);
                }
                EventStream stream = target.client().events(-1, "agent.stopping", event -> {
                });
                try (server; stream) {
                    return watch(server, stream);
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
     * The actor of {@code skillroy_session_ask}: the session service over the server's session, under the read-only
     * policy unless the server offers mutating tools.
     */
    static SessionActorFactory actors(InferenceSettings inference, CostLedger ledger, SecretMask mask,
                                      PrintStream err) {
        return (session, options) -> {
            SessionPolicy policy = options.mutating() ? SessionPolicy.acting() : SessionPolicy.readOnlyPolicy();
            Path dir = session.options().outputDir();
            SessionServices.Opened opened = SessionServices.open(session, inference, policy, ledger, dir);
            return new ServiceActor(opened, ledger, dir, mask, err);
        };
    }

    /** The session service as the server's actor; the session's ledger is written when it closes. */
    private static final class ServiceActor implements SessionActor {

        private final SessionServices.Opened opened;
        private final CostLedger ledger;
        private final Path dir;
        private final SecretMask mask;
        private final PrintStream err;

        ServiceActor(SessionServices.Opened opened, CostLedger ledger, Path dir, SecretMask mask, PrintStream err) {
            this.opened = opened;
            this.ledger = ledger;
            this.dir = dir;
            this.mask = mask;
            this.err = err;
        }

        @Override
        public Object ask(String request) throws AgentLostException, InterruptedException {
            return SessionServices.turnJson(opened.service().ask(request));
        }

        @Override
        public void close() {
            try {
                opened.close();
            } finally {
                if (dir != null) {
                    try {
                        InferenceArgs.writeLedger(dir, ledger.entries(), mask);
                    } catch (IOException | RuntimeException e) {
                        err.println("skillroy mcp: the session's cost-ledger.jsonl cannot be written: "
                                + e.getClass().getSimpleName());
                    }
                }
            }
        }
    }
}
