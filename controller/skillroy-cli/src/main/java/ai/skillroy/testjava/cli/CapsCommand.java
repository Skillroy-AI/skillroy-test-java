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

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code skillroy caps}: the capability matrix, which says what a scenario can rely on with this target and why
 * something is unavailable.
 */
final class CapsCommand implements Command {

    private static final CommandSpec SPEC = new CommandSpec("caps", "show the capability matrix", "", """
            Shows every capability the agent declares with its state (OK, DEGRADED, UNAVAILABLE), the reason when
            it is not OK, and the strategy in use. --reprobe asks the agent to run its probes again first
            (agent.capabilities {reprobe:true}); otherwise the current matrix is read (GET /capabilities).""",
            Connector.withConnection(
                    OptionSpec.flag("--reprobe", "run the agent's capability probes again first"),
                    OptionSpec.flag("--json", "print {\"capabilities\":{id:{state, reason, strategy}}} as JSON")),
            0, 0);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        try (Target target = Connector.connect(args, cli)) {
            CapabilityMatrix matrix;
            if (args.flag("--reprobe")) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("reprobe", Boolean.TRUE);
                Object value = target.client().call("agent.capabilities", params).value();
                if (!(value instanceof Map<?, ?> map)) {
                    throw new CliException(Exit.FAILED, "agent.capabilities returned no capability matrix");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> json = (Map<String, Object>) map;
                try {
                    matrix = CapabilityMatrix.fromJson(json);
                } catch (RuntimeException e) {
                    throw new CliException(Exit.FAILED, "agent.capabilities returned an invalid matrix: "
                            + e.getMessage(), e);
                }
            } else {
                matrix = target.client().capabilities();
            }
            Map<String, Capability> sorted = new TreeMap<>(matrix.all());
            if (args.flag("--json")) {
                Map<String, Object> capabilities = new LinkedHashMap<>();
                sorted.forEach((id, capability) -> capabilities.put(id, capability.toJson()));
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("capabilities", capabilities);
                cli.out().print(target.scrub(Json.writePretty(json)));
                return Exit.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            for (Capability capability : sorted.values()) {
                rows.add(List.of(capability.id(), String.valueOf(capability.state()),
                        capability.reason() == null ? "" : Text.oneLine(capability.reason(), 160),
                        capability.strategy() == null ? "" : capability.strategy()));
            }
            cli.out().print(target.scrub(Text.table(List.of("CAPABILITY", "STATE", "REASON", "STRATEGY"), rows)));
            return Exit.OK;
        }
    }
}
