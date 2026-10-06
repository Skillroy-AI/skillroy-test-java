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
import ai.skillroy.testjava.controller.AgentHome;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.AttachFailedException;
import ai.skillroy.testjava.controller.AttachSession;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.protocol.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code skillroy attach <pid>}: loads the agent into a running JVM (ATTACH mode) and verifies it, so that the other
 * commands can then reach it with {@code --pid}.
 */
final class AttachCommand implements Command {

    static final String HOME = "--home";
    static final String MODULE = "--module";
    static final String OPTION = "--option";

    private static final CommandSpec SPEC = new CommandSpec("attach", "load the agent into a running JVM", "<pid>",
            """
            Loads the agent from an agent home (the skillroy-agent directory built by skillroy-agent-dist) into the
            running JVM <pid> through the JDK attach API, then proves it with a /ping handshake. The JVM must run as
            the same OS user and allow attach. A JVM younger than 5 s is given that long first, because the attach
            signal ends a JVM that is still starting. An agent that already runs there is kept (re-attach). The
            agent publishes itself in the discovery directory; connect afterwards with --pid <pid>. Needs the
            jdk.attach module (a full JDK).""",
            List.of(OptionSpec.value(HOME, "dir", "the agent home (default: -Dskillroy.agent.home, else\n"
                            + "$SKILLROY_AGENT_HOME)"),
                    OptionSpec.repeatable(MODULE, "jar", "an extra agent module jar to install (added to the\n"
                            + "agent's 'modules' option)"),
                    OptionSpec.repeatable(OPTION, "key=value", "an agent option, for example readOnly=true or\n"
                            + "port=0; token and nonce are generated when not given"),
                    OptionSpec.value(Connector.DISCOVERY_DIR, "dir", "where the agent publishes its discovery file\n"
                            + "(default: <java.io.tmpdir>/skillroy-<user>, or\n"
                            + "<user.home>/.skillroy/run when another user owns that)"),
                    OptionSpec.flag("--json", "print {pid, port, loadMode, startTimeMs, discoveryDir,\n"
                            + "agentVersion, modules} as JSON")),
            1, 1);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws UsageException, CliException {
        long pid = parsePid(args.positionals().get(0));
        AgentHome home = home(args);
        Map<String, String> options = options(args, cli);
        Endpoint endpoint;
        try {
            endpoint = AttachSession.attach(pid, home, options);
        } catch (AttachFailedException e) {
            throw new CliException(Exit.UNREACHABLE, "attach failed: " + e.getMessage(), e);
        }
        Map<String, Object> info = Map.of();
        try (AgentClient client = AgentClient.connect(endpoint)) {
            if (client.call("agent.info", Map.of()).value() instanceof Map<?, ?> value) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) value;
                info = typed;
            }
        } catch (AgentLostException | AgentRpcException e) {
            cli.err().println("skillroy attach: warning: attached, but agent.info failed: " + e.getMessage());
        }
        String dir = options.get("discoveryDir");
        if (args.flag("--json")) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("pid", endpoint.pid());
            json.put("port", (long) endpoint.port());
            json.put("loadMode", endpoint.loadMode() == null ? null : endpoint.loadMode().name());
            json.put("startTimeMs", endpoint.startTimeMs());
            json.put("discoveryDir", dir);
            json.put("agentVersion", info.get("agentVersion"));
            json.put("modules", info.getOrDefault("modules", List.of()));
            cli.out().print(Json.writePretty(json));
        } else {
            cli.out().println("attached to pid " + endpoint.pid() + ": agent " + info.getOrDefault("agentVersion", "?")
                    + " on 127.0.0.1:" + endpoint.port() + " (" + endpoint.loadMode() + ", protocol "
                    + info.getOrDefault("protocol", "?") + ")");
            String modules = InfoCommand.modules(info.get("modules"));
            if (!modules.isEmpty()) {
                cli.out().println("modules: " + modules);
            }
            boolean defaultDir = Path.of(dir).equals(cli.defaultDiscoveryDir().toAbsolutePath().normalize());
            cli.err().println("connect with: skillroy info --pid " + endpoint.pid()
                    + (defaultDir ? "" : " " + Connector.DISCOVERY_DIR + " " + dir));
        }
        return Exit.OK;
    }

    static long parsePid(String text) throws UsageException {
        try {
            long pid = Long.parseLong(text.strip());
            if (pid > 0) {
                return pid;
            }
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw new UsageException("<pid> must be a positive process id, found '" + text + "'");
    }

    private static AgentHome home(ParsedArgs args) throws UsageException {
        Path given = args.path(HOME);
        try {
            return given != null ? AgentHome.of(given) : AgentHome.locate();
        } catch (IllegalArgumentException e) {
            throw new UsageException(HOME + ": " + e.getMessage());
        } catch (IllegalStateException e) {
            throw new UsageException(e.getMessage() + "; or pass " + HOME + " <dir>");
        }
    }

    /** The agent options: every {@code --option}, the {@code --module} jars and the discovery directory. */
    static Map<String, String> options(ParsedArgs args, Cli cli) throws UsageException {
        Map<String, String> options = new LinkedHashMap<>();
        for (String pair : args.values(OPTION)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new UsageException(OPTION + " needs key=value, found '" + pair + "'");
            }
            String key = pair.substring(0, eq).strip();
            if (key.isEmpty() || key.chars().anyMatch(Character::isWhitespace)) {
                throw new UsageException(OPTION + " has an invalid key in '" + pair + "'");
            }
            options.put(key, pair.substring(eq + 1));
        }
        List<String> modules = new ArrayList<>();
        String already = options.get("modules");
        if (already != null && !already.isBlank()) {
            modules.add(already.strip());
        }
        for (String jar : args.values(MODULE)) {
            Path path = ParsedArgs.toPath(MODULE, jar);
            if (!Files.isRegularFile(path)) {
                throw new UsageException(MODULE + " " + jar + ": no such file");
            }
            String absolute = path.toAbsolutePath().normalize().toString();
            if (absolute.indexOf(',') >= 0) {
                throw new UsageException(MODULE + " " + jar + ": the agent's 'modules' option cannot carry a path"
                        + " with a comma");
            }
            modules.add(absolute);
        }
        if (!modules.isEmpty()) {
            options.put("modules", String.join(",", modules));
        }
        Path dir = args.has(Connector.DISCOVERY_DIR) ? Connector.discoveryDir(args, cli)
                : options.containsKey("discoveryDir") ? ParsedArgs.toPath(OPTION + " discoveryDir",
                options.get("discoveryDir")) : cli.defaultDiscoveryDir();
        options.put("discoveryDir", dir.toAbsolutePath().normalize().toString());
        return options;
    }
}
