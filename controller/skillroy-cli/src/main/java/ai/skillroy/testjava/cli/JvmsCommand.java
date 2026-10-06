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

import ai.skillroy.testjava.controller.AttachSession;
import ai.skillroy.testjava.controller.Discovery;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.controller.JvmInfo;
import ai.skillroy.testjava.protocol.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code skillroy jvms}: the JVMs of the current user, with a marker on those already running an agent, so that a
 * person or a script can pick the pid for {@code attach} or {@code --pid}.
 */
final class JvmsCommand implements Command {

    private static final CommandSpec SPEC = new CommandSpec("jvms",
            "list the JVMs of the current user; '*' marks those running an agent", "", """
            Lists the JVMs of the current OS user that the JDK attach API can see, and marks with '*' those that
            already run an agent (a live discovery file in the discovery directory). An agent whose JVM the attach
            API cannot see (for example one started with -XX:-UsePerfData) is listed too. The CLI's own JVM is left
            out. Without the jdk.attach module only the JVMs with an agent are listed.""",
            List.of(Connector.DISCOVERY_DIR_OPTION,
                    OptionSpec.flag("--json", "print {\"jvms\":[{pid, command, agent}], \"discoveryDir\"} as JSON")),
            0, 0);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws UsageException {
        Path dir = Connector.discoveryDir(args, cli);
        Map<Long, Discovery.Entry> agents = new TreeMap<>();
        for (Discovery.Entry entry : Discovery.entries(dir)) {
            agents.put(entry.endpoint().pid(), entry);
        }
        List<JvmInfo> jvms;
        try {
            jvms = AttachSession.listJvms();
        } catch (IllegalStateException e) {
            cli.err().println("skillroy jvms: " + e.getMessage() + "; listing the JVMs with an agent only");
            jvms = List.of();
        }
        long self = ProcessHandle.current().pid();
        Map<Long, String> commands = new TreeMap<>();
        for (JvmInfo jvm : jvms) {
            if (jvm.pid() > 0 && jvm.pid() != self) {
                commands.put(jvm.pid(), jvm.displayName());
            }
        }
        for (Discovery.Entry entry : agents.values()) {
            long pid = entry.endpoint().pid();
            String known = commands.get(pid);
            if (pid != self && (known == null || known.isBlank())) {
                commands.put(pid, entry.mainCommand() == null ? "" : entry.mainCommand());
            }
        }
        if (args.flag("--json")) {
            List<Object> list = new ArrayList<>();
            commands.forEach((pid, command) -> {
                Map<String, Object> jvm = new LinkedHashMap<>();
                jvm.put("pid", pid);
                jvm.put("command", command);
                Discovery.Entry entry = agents.get(pid);
                jvm.put("agent", entry == null ? null : agentJson(entry));
                list.add(jvm);
            });
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("jvms", list);
            json.put("discoveryDir", dir.toString());
            cli.out().print(Json.writePretty(json));
            return Exit.OK;
        }
        List<List<String>> rows = new ArrayList<>();
        commands.forEach((pid, command) -> {
            Discovery.Entry entry = agents.get(pid);
            rows.add(List.of(Long.toString(pid), entry == null ? "" : agentCell(entry.endpoint()),
                    Text.oneLine(command, 160)));
        });
        cli.out().print(Text.table(List.of("PID", "AGENT", "COMMAND"), rows));
        if (!agents.isEmpty()) {
            cli.err().println("* runs an agent; connect with: skillroy info --pid <pid>"
                    + (dir.equals(cli.defaultDiscoveryDir().toAbsolutePath().normalize()) ? ""
                    : " --discovery-dir " + dir));
        }
        return Exit.OK;
    }

    private static String agentCell(Endpoint endpoint) {
        return "* " + (endpoint.loadMode() == null ? "?" : endpoint.loadMode().name()) + " :" + endpoint.port();
    }

    private static Map<String, Object> agentJson(Discovery.Entry entry) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("port", (long) entry.endpoint().port());
        agent.put("loadMode", entry.endpoint().loadMode() == null ? null : entry.endpoint().loadMode().name());
        agent.put("protocol", entry.protocol());
        agent.put("startTimeMs", entry.endpoint().startTimeMs());
        agent.put("mainCommand", entry.mainCommand());
        return agent;
    }
}
