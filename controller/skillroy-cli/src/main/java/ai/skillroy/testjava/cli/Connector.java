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

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.Discovery;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.protocol.LoadMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chooses and opens the agent connection the connection options describe:
 *
 * <ul>
 *   <li>{@code --pid <pid>}: the agent of that JVM, from its discovery file in the discovery directory;</li>
 *   <li>{@code --port <n> --token-file <file>}: an agent at {@code 127.0.0.1:<n>} with the token in the file
 *       (no discovery file needed);</li>
 *   <li>neither: the only live agent in the discovery directory; several are a usage error that lists their
 *       pids.</li>
 * </ul>
 *
 * <p>{@code --discovery-dir} replaces the default directory ({@code ${java.io.tmpdir}/skillroy-<user>}, or
 * {@code <user.home>/.skillroy/run} when another user owns that; contract amendment A3, findings S8/S19). Every connection is verified by the client's {@code /ping} handshake. Failures to find or reach an
 * agent are {@link CliException}s with {@link Exit#UNREACHABLE}; contradictory options are
 * {@link UsageException}s. No message ever contains the token.
 *
 * <p>Stateless; safe from any thread.
 */
final class Connector {

    static final String PID = "--pid";
    static final String PORT = "--port";
    static final String TOKEN_FILE = "--token-file";
    static final String DISCOVERY_DIR = "--discovery-dir";
    /**
     * Why a discovery file's token file may be unreadable: discovery honours only an owner-only token file inside
     * the directory it reads (findings S6/S28).
     */
    static final String TOKEN_FILE_HINT = " (only an owner-only token file inside the discovery directory is read;"
            + " an agent whose tokenFile lies elsewhere is reached with " + PORT + " and " + TOKEN_FILE + ")";

    /** The discovery directory option alone, for commands that read discovery files but do not connect. */
    static final OptionSpec DISCOVERY_DIR_OPTION = OptionSpec.value(DISCOVERY_DIR, "dir",
            "where agents publish discovery files\n(default: <java.io.tmpdir>/skillroy-<user>, or\n"
                    + "<user.home>/.skillroy/run when another user owns that)");

    /** The connection options every agent-facing command accepts, in help order. */
    static final List<OptionSpec> OPTIONS = List.of(
            OptionSpec.value(PID, "pid", "connect to the agent of this JVM, found through its discovery file"),
            OptionSpec.value(PORT, "port", "connect to the agent at 127.0.0.1:<port> (with --token-file)"),
            OptionSpec.value(TOKEN_FILE, "file", "the file holding that agent's token"),
            OptionSpec.value(DISCOVERY_DIR, "dir", "where agents publish discovery files\n"
                    + "(default: <java.io.tmpdir>/skillroy-<user>, or\n"
                    + "<user.home>/.skillroy/run when another user owns that);\nwithout --pid"
                    + " or --port the only live agent there is used"));

    /** {@code extra} followed by the connection options, for an agent-facing command's spec. */
    static List<OptionSpec> withConnection(OptionSpec... extra) {
        List<OptionSpec> options = new ArrayList<>(List.of(extra));
        options.addAll(OPTIONS);
        return options;
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{1,1024}");
    private static final long MAX_TOKEN_FILE_BYTES = 4096;

    private Connector() {
    }

    /**
     * Opens the connection the options of {@code args} describe; see the class comment.
     *
     * @throws UsageException if the options contradict each other, a value is malformed, the token file is not a
     *     token file, or several agents qualify
     * @throws CliException ({@link Exit#UNREACHABLE}) if no agent qualifies or the one chosen does not answer
     */
    static Target connect(ParsedArgs args, Cli cli) throws UsageException, CliException {
        Long pid = args.longValue(PID, 1, Long.MAX_VALUE);
        Long port = args.longValue(PORT, 1, 65535);
        Path tokenFile = args.path(TOKEN_FILE);
        Path dir = discoveryDir(args, cli);
        if (pid != null && (port != null || tokenFile != null)) {
            throw new UsageException(PID + " cannot be combined with " + PORT + " or " + TOKEN_FILE);
        }
        if ((port == null) != (tokenFile == null)) {
            throw new UsageException(PORT + " and " + TOKEN_FILE + " must be given together");
        }
        if (port != null) {
            return direct(port.intValue(), readToken(tokenFile), dir);
        }
        if (pid != null) {
            return byPid(pid, dir);
        }
        return onlyAgent(dir);
    }

    /** The discovery directory in effect: {@code --discovery-dir}, else the default, as an absolute path. */
    static Path discoveryDir(ParsedArgs args, Cli cli) throws UsageException {
        Path given = args.path(DISCOVERY_DIR);
        return (given != null ? given : cli.defaultDiscoveryDir()).toAbsolutePath().normalize();
    }

    /**
     * Reads a token file: at most 4 KiB, surrounding whitespace removed, header-safe characters only. The
     * content is never echoed.
     *
     * @throws UsageException if the file is missing, unreadable or does not hold a token
     */
    static String readToken(Path file) throws UsageException {
        try {
            if (!Files.isRegularFile(file)) {
                throw new UsageException(TOKEN_FILE + " " + file + ": no such file");
            }
            if (Files.size(file) > MAX_TOKEN_FILE_BYTES) {
                throw new UsageException(TOKEN_FILE + " " + file + " is not a token file (larger than 4 KiB)");
            }
            String token = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!TOKEN.matcher(token).matches()) {
                throw new UsageException(TOKEN_FILE + " " + file + " does not hold a token");
            }
            return token;
        } catch (IOException e) {
            throw new UsageException(TOKEN_FILE + " " + file + " cannot be read: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Why agents and the CLI ignore {@code dir}, as far as can be told cheaply, or null: missing, not a directory, or
     * writable by group or others (contract amendment A3). For messages only.
     */
    static String directoryProblem(Path dir) {
        if (!Files.exists(dir)) {
            return "it does not exist";
        }
        if (!Files.isDirectory(dir)) {
            return "it is not a directory";
        }
        try {
            Set<PosixFilePermission> permissions = Files.readAttributes(dir, PosixFileAttributes.class).permissions();
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                return "it is writable by group or others, so agents and the CLI ignore it";
            }
        } catch (IOException | UnsupportedOperationException e) {
            // No POSIX attributes: nothing more to say.
        }
        return null;
    }

    private static Target direct(int port, String token, Path dir) throws CliException {
        Endpoint bare = new Endpoint("127.0.0.1", port, token, null, 0, 0, null);
        Map<String, Object> ping;
        try (AgentClient probe = open(bare)) {
            ping = probe.ping();
        } catch (AgentLostException e) {
            throw unreachable(e);
        }
        // Learn the target's identity so that every later ping, and the event stream's liveness check, verify it.
        // A pid that is not a live process here (another PID namespace) stays unknown.
        long pid = ping.get("pid") instanceof Long n ? n : 0L;
        long startTimeMs = ping.get("startTimeMs") instanceof Long n ? n : 0L;
        if (pid <= 0 || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            pid = 0;
            startTimeMs = 0;
        }
        String nonce = ping.get("agentNonce") instanceof String s ? s : null;
        Endpoint full = new Endpoint("127.0.0.1", port, token, nonce, pid, startTimeMs, loadMode(ping.get("loadMode")));
        return new Target(open(full), full, dir, pid > 0 ? mainCommand(pid, dir) : null);
    }

    private static Target byPid(long pid, Path dir) throws CliException {
        Optional<Endpoint> found = Discovery.forPid(pid, dir);
        if (found.isEmpty()) {
            String problem = directoryProblem(dir);
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            String why;
            if (problem != null) {
                why = ": " + dir + " is not usable (" + problem + ")";
            } else if (alive) {
                why = ": the JVM is running but no agent of it is published there (load one with 'skillroy attach "
                        + pid + "', or pass the " + DISCOVERY_DIR + " its agent uses)";
            } else {
                why = ": no process " + pid + " is running";
            }
            throw new CliException(Exit.UNREACHABLE, "no live agent for pid " + pid + " in " + dir + why);
        }
        Endpoint endpoint = found.get();
        if (endpoint.token() == null) {
            throw new CliException(Exit.UNREACHABLE, "the discovery file of pid " + pid + " names a token file that"
                    + " cannot be read" + TOKEN_FILE_HINT);
        }
        return new Target(open(endpoint), endpoint, dir, mainCommand(pid, dir));
    }

    private static Target onlyAgent(Path dir) throws UsageException, CliException {
        List<Discovery.Entry> entries = Discovery.entries(dir);
        if (entries.isEmpty()) {
            String problem = directoryProblem(dir);
            throw new CliException(Exit.UNREACHABLE, "no live agent in " + dir
                    + (problem != null ? " (" + problem + ")" : "")
                    + "; start a target with the agent, attach one with 'skillroy attach <pid>', or name one with "
                    + PID + ", " + PORT + " or " + DISCOVERY_DIR);
        }
        if (entries.size() > 1) {
            throw new UsageException(entries.size() + " live agents in " + dir + " (pids "
                    + entries.stream().map(e -> Long.toString(e.endpoint().pid())).collect(Collectors.joining(", "))
                    + "): choose one with " + PID);
        }
        Discovery.Entry entry = entries.get(0);
        if (entry.endpoint().token() == null) {
            throw new CliException(Exit.UNREACHABLE, "the discovery file of pid " + entry.endpoint().pid()
                    + " names a token file that cannot be read" + TOKEN_FILE_HINT);
        }
        return new Target(open(entry.endpoint()), entry.endpoint(), dir, entry.mainCommand());
    }

    private static AgentClient open(Endpoint endpoint) throws CliException {
        try {
            return AgentClient.connect(endpoint);
        } catch (AgentLostException e) {
            throw unreachable(e);
        } catch (IllegalArgumentException e) {
            throw new CliException(Exit.UNREACHABLE, "cannot connect: " + e.getMessage(), e);
        }
    }

    private static CliException unreachable(AgentLostException e) {
        return new CliException(Exit.UNREACHABLE, "agent unreachable: " + e.getMessage(), e);
    }

    private static String mainCommand(long pid, Path dir) {
        return Discovery.entries(dir).stream().filter(e -> e.endpoint().pid() == pid).map(Discovery.Entry::mainCommand)
                .filter(c -> c != null && !c.isBlank()).reduce((first, second) -> second).orElse(null);
    }

    private static LoadMode loadMode(Object text) {
        if (!(text instanceof String name)) {
            return null;
        }
        try {
            return LoadMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
