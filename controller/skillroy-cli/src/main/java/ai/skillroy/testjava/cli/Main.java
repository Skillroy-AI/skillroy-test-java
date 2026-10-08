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
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.Discovery;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code skillroy} command line: {@code jvms}, {@code attach}, {@code info}, {@code caps}, {@code schema},
 * {@code call}, {@code snapshot}, {@code events}, {@code compile}, {@code run} (contract section 8.5), from
 * Phase 2 {@code resolve} and {@code diff-bindings} (phase2-contracts.md 12.1), and from Phase 5
 * {@code re-resolve}, {@code mcp}, {@code author}, {@code triage} and {@code session} (phase5-contracts.md 13.4). It
 * lets a person, a CI job or an AI agent drive a target application through its in-JVM agent without writing Java.
 *
 * <p>Human-readable output goes to standard output, diagnostics to standard error; {@code --json} gives
 * machine-readable output where it makes sense. Both streams pass through {@link SafePrintStream}, which makes
 * control characters in printed text visible (security finding S26). The token is never printed. Exit codes: 0
 * success, 1 a
 * negative outcome (a scenario FAIL or ERROR, a JSON-RPC error, a stale plan), 2 a scenario load or compile error, 3
 * the agent cannot be reached, 64 a usage error, 70 an internal error.
 *
 * <p>{@link #run} executes one command on the calling thread and returns its exit code; it never calls
 * {@code System.exit}, so tests and embedding tools can call it in-process.
 */
public final class Main {

    private static final Map<String, Command> COMMANDS = commands();

    private Main() {
    }

    /** Runs the command named by {@code args} and exits the JVM with its exit code. */
    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /**
     * Runs one command with the given standard streams.
     *
     * @param args the command line words, starting with the command name
     * @return the exit code (0 success, 1 failed, 2 scenario error, 3 agent unreachable, 64 usage, 70 internal)
     */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Cli cli = new Cli(in, out, err, classLoader(), Discovery.defaultDir());
        return run(Arrays.asList(args), cli);
    }

    /**
     * {@link #run(String[], InputStream, PrintStream, PrintStream)} with an explicit context (tests). The context's
     * streams are wrapped in {@link SafePrintStream} here, so that every caller gets the same neutralised output.
     */
    static int run(List<String> args, Cli cli) {
        Cli safe = new Cli(cli.in(), SafePrintStream.wrap(cli.out()), SafePrintStream.wrap(cli.err()),
                cli.classLoader(), cli.defaultDiscoveryDir(), cli.terminal());
        try {
            return dispatch(args, safe);
        } finally {
            safe.out().flush();
            safe.err().flush();
        }
    }

    private static int dispatch(List<String> args, Cli cli) {
        if (args.isEmpty()) {
            cli.err().print(overview());
            return Exit.USAGE;
        }
        String name = args.get(0);
        if (name.equals("--help") || name.equals("-h")) {
            cli.out().print(overview());
            return Exit.OK;
        }
        if (name.equals("help")) {
            if (args.size() == 1) {
                cli.out().print(overview());
                return Exit.OK;
            }
            Command command = COMMANDS.get(args.get(1));
            if (command == null) {
                return usage(cli, null, "unknown command '" + args.get(1) + "'");
            }
            cli.out().print(command.spec().help());
            return Exit.OK;
        }
        if (name.equals("--version")) {
            cli.out().println("skillroy " + version());
            return Exit.OK;
        }
        Command command = COMMANDS.get(name);
        if (command == null) {
            return usage(cli, null, (name.startsWith("-") ? "unknown option '" : "unknown command '") + name + "'");
        }
        ParsedArgs parsed;
        try {
            parsed = ArgParser.parse(command.spec(), args.subList(1, args.size()));
        } catch (UsageException e) {
            return usage(cli, name, e.getMessage());
        }
        if (parsed.help()) {
            cli.out().print(command.spec().help());
            return Exit.OK;
        }
        String prefix = "skillroy " + name + ": ";
        try {
            return command.run(parsed, cli);
        } catch (UsageException e) {
            return usage(cli, name, e.getMessage());
        } catch (CliException e) {
            cli.err().println(prefix + e.getMessage());
            return e.exitCode();
        } catch (AgentLostException e) {
            cli.err().println(prefix + "agent unreachable: " + e.getMessage());
            return Exit.UNREACHABLE;
        } catch (AgentRpcException e) {
            cli.err().println(prefix + Text.rpcError(e));
            return Exit.FAILED;
        } catch (Exception | LinkageError | StackOverflowError e) {
            cli.err().println(prefix + "internal error: " + e);
            e.printStackTrace(cli.err());
            return Exit.INTERNAL;
        }
    }

    private static int usage(Cli cli, String command, String message) {
        cli.err().println("skillroy" + (command == null ? "" : " " + command) + ": " + message);
        cli.err().println("Run 'skillroy " + (command == null ? "--help" : command + " --help") + "' for usage.");
        return Exit.USAGE;
    }

    /** {@code skillroy --help}: the commands, the connection rules and the exit codes. */
    static String overview() {
        StringBuilder text = new StringBuilder("""
                Usage: skillroy <command> [options]

                Drives a Java application through its in-JVM skillroy agent: find and attach to JVMs, inspect the
                agent, call primitives, compile scenarios to plans and run them.

                Commands:
                """);
        for (Command command : COMMANDS.values()) {
            text.append(String.format("  %-10s %s%n", command.spec().name(), command.spec().summary()));
        }
        text.append("""

                Connecting (info, caps, schema, call, snapshot, events, run, resolve, re-resolve, mcp, author,
                session):
                  --pid <pid>                     the agent of this JVM, found through its discovery file
                  --port <port> --token-file <f>  the agent at 127.0.0.1:<port> with its token in <f>
                  neither                         the only live agent in the discovery directory
                  --discovery-dir <dir>           where agents publish discovery files
                                                  (default: <java.io.tmpdir>/skillroy-<user>, or
                                                  <user.home>/.skillroy/run when another user owns that)

                Model calls (run, resolve, re-resolve, mcp, author, triage, session): --inference claude needs the
                model key in the environment variable ANTHROPIC_API_KEY (never an option or a file); recorded
                answers from --recordings; replay never calls a model.

                Exit codes: 0 ok; 1 failed (scenario FAIL or ERROR, REBIND_NEEDED and RESOLUTION_FAILED included,
                JSON-RPC error, stale plan, credentials that cannot be loaded, bindings that differ); 2 scenario load
                or compile error, or a file that is not a bindings file; 3 agent unreachable; 64 usage error
                (including approve without a terminal and without --yes, and an inference setting that cannot be
                used); 70 internal error.

                Run 'skillroy <command> --help' for the options of a command; 'skillroy --version' for the version.
                """);
        return text.toString();
    }

    private static String version() {
        String version = Main.class.getPackage() == null ? null : Main.class.getPackage().getImplementationVersion();
        return version == null ? "(development build)" : version;
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = Main.class.getClassLoader();
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    private static Map<String, Command> commands() {
        Map<String, Command> commands = new LinkedHashMap<>();
        for (Command command : List.of(new JvmsCommand(), new AttachCommand(), new InfoCommand(), new CapsCommand(),
                new SchemaCommand(), new CallCommand(), new SnapshotCommand(), new EventsCommand(),
                new CompileCommand(), new RunCommand(), new ResolveCommand(), new DiffBindingsCommand(),
                new ReResolveCommand(), new McpCommand(), new AuthorCommand(), new TriageCommand(),
                new SessionCommand())) {
            commands.put(command.spec().name(), command);
        }
        return Collections.unmodifiableMap(commands);
    }
}
