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

import ai.skillroy.testjava.protocol.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code skillroy info}: what the agent is and how it was loaded ({@code agent.info}), for a first look. */
final class InfoCommand implements Command {

    /** Option keys never printed, even if an agent forgot to remove them. */
    private static final Set<String> SECRET_OPTIONS = Set.of("token", "nonce");

    private static final CommandSpec SPEC = new CommandSpec("info",
            "show the agent's version, load mode, readiness, modules and options", "", """
            Shows what agent.info reports: agent and protocol version, load mode, the target's pid and Java version,
            whether the target is ready and the agent read-only, whether Instrumentation is available, the installed
            modules and the agent options (secrets are redacted by the agent; the token is never shown).""",
            Connector.withConnection(OptionSpec.flag("--json", "print the agent.info value as JSON")), 0, 0);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        try (Target target = Connector.connect(args, cli)) {
            Object value = target.client().call("agent.info", Map.of()).value();
            Map<String, Object> info = value instanceof Map<?, ?> map ? sanitise(map) : Map.of("value", value);
            if (args.flag("--json")) {
                cli.out().print(target.scrub(Json.writePretty(info)));
                return Exit.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("agent", info.getOrDefault("agentVersion", "?") + ", protocol "
                    + info.getOrDefault("protocol", "?") + ", load mode " + info.getOrDefault("loadMode", "?")));
            rows.add(List.of("target", "pid " + info.getOrDefault("pid", "?") + ", Java "
                    + info.getOrDefault("javaVersion", "?") + started(info.get("startTimeMs"))));
            rows.add(List.of("endpoint", "127.0.0.1:" + target.endpoint().port()));
            rows.add(List.of("state", (Boolean.TRUE.equals(info.get("ready")) ? "ready" : "not ready")
                    + ", " + (Boolean.TRUE.equals(info.get("readOnly")) ? "read-only" : "read-write")
                    + ", instrumentation " + (Boolean.TRUE.equals(info.get("instrumentation")) ? "available"
                    : "not available")));
            rows.add(List.of("modules", modules(info.get("modules"))));
            if (info.get("options") instanceof Map<?, ?> options && !options.isEmpty()) {
                List<String> pairs = new ArrayList<>();
                options.forEach((k, v) -> pairs.add(k + "=" + v));
                rows.add(List.of("options", String.join(", ", pairs)));
            }
            for (List<String> row : rows) {
                cli.out().printf("%-10s %s%n", row.get(0), target.scrub(row.get(1)));
            }
            return Exit.OK;
        }
    }

    /** {@code swing 0.1.0 INSTALLED, data 0.1.0 FAILED (reason)} from {@code agent.info.modules}. */
    static String modules(Object modules) {
        if (!(modules instanceof List<?> list)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> module) {
                StringBuilder part = new StringBuilder(String.valueOf(module.get("id")));
                if (module.get("version") != null) {
                    part.append(' ').append(module.get("version"));
                }
                if (module.get("state") != null) {
                    part.append(' ').append(module.get("state"));
                }
                if (module.get("message") != null) {
                    part.append(" (").append(Text.oneLine(String.valueOf(module.get("message")), 120)).append(')');
                }
                parts.add(part.toString());
            }
        }
        return String.join(", ", parts);
    }

    /** A copy of {@code agent.info} whose options hold no token or nonce. */
    private static Map<String, Object> sanitise(Map<?, ?> info) {
        Map<String, Object> copy = new LinkedHashMap<>();
        info.forEach((k, v) -> copy.put(String.valueOf(k), v));
        if (copy.get("options") instanceof Map<?, ?> options) {
            Map<String, Object> kept = new LinkedHashMap<>();
            options.forEach((k, v) -> {
                if (!SECRET_OPTIONS.contains(String.valueOf(k))) {
                    kept.put(String.valueOf(k), v);
                }
            });
            copy.put("options", kept);
        }
        return copy;
    }

    private static String started(Object startTimeMs) {
        return startTimeMs instanceof Long ms && ms > 0 ? ", started " + Instant.ofEpochMilli(ms) : "";
    }
}
