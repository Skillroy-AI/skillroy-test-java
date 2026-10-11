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
import ai.skillroy.testjava.scenario.CapabilityContributor;
import ai.skillroy.testjava.scenario.CapabilityMerge;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code skillroy caps}: the capability matrix, which says what a scenario can rely on with this target and why
 * something is unavailable.
 *
 * <p>Phase 7 (phase7-contracts.md 8.5, 9.3, decision D326): every row names its {@code source}, {@code agent} or
 * {@code controller:<contributor id>}; with {@code --pack} or an inference option the controller-side contributors
 * found through the packs' loader add their {@code inference.*} capabilities under those settings, merged as a
 * {@code live} run merges them (no host features: {@code inference.vision} answers {@code no vision session}); the
 * agent's alone otherwise.
 */
final class CapsCommand implements Command {

    private static final CommandSpec SPEC = new CommandSpec("caps", "show the capability matrix", "", """
            Shows every capability the agent declares with its state (OK, DEGRADED, UNAVAILABLE), the reason when
            it is not OK, the strategy in use and its source (agent). --reprobe asks the agent to run its probes
            again first (agent.capabilities {reprobe:true}); otherwise the current matrix is read
            (GET /capabilities). With --pack or --inference the controller's capabilities (inference.gateway,
            inference.sql, inference.vision; source controller:<module>) are merged in as a live run sees them.""",
            capsOptions(), 0, 0);

    private static List<OptionSpec> capsOptions() {
        List<OptionSpec> options = new ArrayList<>(List.of(
                OptionSpec.flag("--reprobe", "run the agent's capability probes again first"),
                OptionSpec.flag("--json", "print {\"capabilities\":{id:{state, reason, strategy, source}}} as\n"
                        + "JSON")));
        options.add(Packs.OPTION_SPEC);
        options.addAll(InferenceArgs.gateway());
        return Connector.withConnection(options.toArray(OptionSpec[]::new));
    }

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
            if (matrix == null) {
                matrix = new CapabilityMatrix(Map.of());
            }
            if (!args.values(Packs.OPTION).isEmpty() || InferenceArgs.given(args)) {
                matrix = merged(matrix, args, cli);
            }
            CapabilityMatrix shown = matrix;
            Map<String, Capability> sorted = new TreeMap<>(matrix.all());
            if (args.flag("--json")) {
                Map<String, Object> capabilities = new LinkedHashMap<>();
                sorted.forEach((id, capability) -> {
                    Map<String, Object> entry = new LinkedHashMap<>(capability.toJson());
                    entry.put("source", shown.source(id));
                    capabilities.put(id, entry);
                });
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("capabilities", capabilities);
                cli.out().print(target.scrub(Json.writePretty(json)));
                return Exit.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            for (Capability capability : sorted.values()) {
                rows.add(List.of(capability.id(), String.valueOf(capability.state()),
                        capability.reason() == null ? "" : Text.oneLine(capability.reason(), 160),
                        capability.strategy() == null ? "" : capability.strategy(), shown.source(capability.id())));
            }
            cli.out().print(target.scrub(Text.table(List.of("CAPABILITY", "STATE", "REASON", "STRATEGY", "SOURCE"),
                    rows)));
            return Exit.OK;
        }
    }

    /**
     * The agent's matrix with the contributors' capabilities (phase7-contracts.md 8.5): the contributors and the pack
     * found through the {@code --pack} jars (or the CLI's class path), the inference settings of the options, mode
     * {@code live}, no host features.
     *
     * @throws CliException FAILED when the merge is refused (an agent id with the controller's prefix, a conflict)
     */
    private static CapabilityMatrix merged(CapabilityMatrix agent, ParsedArgs args, Cli cli) throws Exception {
        InferenceSettings settings = InferenceArgs.load(args, BindMode.LIVE, null);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            DomainPack pack = packs.all().size() == 1 ? packs.all().get(0) : null;
            try {
                return CapabilityMerge.merge(agent, CapabilityMerge.load(packs.loader()).contributors(),
                        new CapabilityContributor.CapabilityContext(settings, BindMode.LIVE, agent, Set.of(), pack))
                        .merged();
            } catch (CapabilityMerge.Refused e) {
                throw new CliException(Exit.FAILED, "the controller's capabilities cannot be merged: "
                        + e.getMessage(), e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
