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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookEntry;
import ai.skillroy.testjava.scenario.hooks.HookInstaller;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code skillroy hooks status|install|remove} (phase6-contracts.md 6.2): what the target's hook engine holds beside
 * the pack's catalogue, the pack's catalogue installed as a run's step 0b installs it (mode {@code ON}), and the
 * restore of installed hooks with each class's byte verification.
 *
 * <ul>
 *   <li>{@code status}: the agent's {@code hook.health} (states, counts, the armed stubs, the spy's counters, the
 *       installed catalogue, the other bytecode agents, decision D180), beside the pack's catalogue (id, version,
 *       hash, the entries, the warnings of phase6-contracts.md 5.1) and whether they match; exit 0.</li>
 *   <li>{@code install}: {@code HookInstaller} with mode {@code ON}; prints the {@code hooks} record; exit 0 when the
 *       install ran (hooks unavailable with their fallbacks included), 1 when it was refused (PRECONDITION_FAILED,
 *       another catalogue installed, {@code HOOK_CONFLICT}), 3 when the agent cannot be reached.</li>
 *   <li>{@code remove [--ids a,b]}: {@code hook.remove}; prints every restored class with its verification and
 *       foreign-event count; exit 0 for {@code OK} and {@code DEGRADED}, 1 for {@code HOOK_VERIFY_FAILED}.</li>
 * </ul>
 */
final class HooksCommand implements Command {

    private static final String IDS = "--ids";

    private static final CommandSpec SPEC = new CommandSpec("hooks", "show, install or remove the target's hooks",
            "status|install|remove", """
            status shows the target's hook engine (hook.health: each hook's state and fire count, the armed stubs,
            the spy's counters, the installed catalogue, the other bytecode agents the engine sees) beside the
            pack's hook catalogue (id, version, hash, entries, warnings) and whether they match. install installs
            the pack's catalogue as a run does (once per target, idempotent by the catalogue's hash) and prints
            what was installed, what was not and its fallback; exit code 1 when it is refused (no catalogue, no
            usable hooks module, another catalogue on the target). remove restores the hooked classes (all hooks, or
            --ids) and prints each class's verification; exit code 1 when a class is left with advice
            (HOOK_VERIFY_FAILED). The pack is the one --pack provides or the only pack on the class path.""",
            Connector.withConnection(Packs.OPTION_SPEC,
                    OptionSpec.value(IDS, "a,b", "remove only these hooks (remove)"),
                    OptionSpec.flag("--json", "print the agent's answer and the catalogue as JSON")),
            1, 1);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        String action = args.positionals().get(0);
        if (!List.of("status", "install", "remove").contains(action)) {
            throw new UsageException("expected status, install or remove, found '" + action + "'");
        }
        if (args.value(IDS) != null && !action.equals("remove")) {
            throw new UsageException(IDS + " is an option of remove");
        }
        boolean json = args.flag("--json");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            DomainPack pack = null;
            try {
                pack = packs.select(null);
            } catch (ScenarioException e) {
                if (action.equals("install")) {
                    throw new UsageException("install needs one domain pack: " + e.reason());
                }
            }
            HookCatalogue catalogue = HookCatalogue.EMPTY;
            if (pack != null) {
                try {
                    catalogue = pack.hooks();
                } catch (RuntimeException e) {
                    throw new CliException(Exit.SCENARIO, "pack " + pack.id() + ": " + e.getMessage(), e);
                }
            }
            try (Target target = Connector.connect(args, cli)) {
                return switch (action) {
                    case "status" -> status(target, pack, catalogue, json, cli);
                    case "install" -> install(target, pack, json, cli);
                    default -> remove(target, ids(args.value(IDS)), json, cli);
                };
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static List<String> ids(String text) throws UsageException {
        List<String> ids = new ArrayList<>();
        if (text == null) {
            return ids;
        }
        for (String id : text.split(",")) {
            if (!id.isBlank()) {
                ids.add(id.strip());
            }
        }
        if (ids.isEmpty()) {
            throw new UsageException(IDS + " names no hook");
        }
        return ids;
    }

    private static int status(Target target, DomainPack pack, HookCatalogue catalogue, boolean json, Cli cli)
            throws Exception {
        Map<?, ?> health = map(target.client().call("hook.health", Map.of()).value());
        List<String> otherAgents = new ArrayList<>();
        if (health.get("otherAgents") instanceof List<?> list) {
            list.forEach(a -> otherAgents.add(String.valueOf(a)));
        }
        Map<?, ?> installed = health.get("catalogue") instanceof Map<?, ?> c ? c : null;
        boolean matches = installed != null && catalogue.id().equals(String.valueOf(installed.get("id")))
                && catalogue.hash().equals(String.valueOf(installed.get("hash")));
        if (json) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("health", health);
            out.put("otherAgents", otherAgents);
            out.put("catalogue", catalogueJson(pack, catalogue));
            out.put("matches", matches);
            cli.out().print(target.scrub(Json.writePretty(out)));
            return Exit.OK;
        }
        StringBuilder text = new StringBuilder();
        text.append("installed catalogue: ").append(installed == null ? "none" : installed.get("id") + " "
                + installed.get("hash")).append('\n');
        List<String> stubs = new ArrayList<>();
        if (health.get("stubs") instanceof List<?> list) {
            list.forEach(s -> stubs.add(String.valueOf(s)));
        }
        text.append("armed stubs: ").append(stubs.isEmpty() ? "none" : String.join(", ", stubs)).append('\n');
        if (health.get("spy") instanceof Map<?, ?> spy) {
            text.append("spy: version ").append(spy.get("version")).append(", counters ")
                    .append(Json.write(spy.get("counters"))).append('\n');
        }
        text.append("other agents: ").append(otherAgents.isEmpty() ? "none" : String.join(", ", otherAgents))
                .append('\n');
        List<List<String>> rows = new ArrayList<>();
        if (health.get("hooks") instanceof Map<?, ?> hooks) {
            new TreeMap<>(stringKeys(hooks)).forEach((id, h) -> {
                Map<?, ?> fields = h instanceof Map<?, ?> m ? m : Map.of();
                rows.add(List.of(id, String.valueOf(fields.get("state")), String.valueOf(fields.get("count")),
                        Boolean.TRUE.equals(fields.get("armed")) ? "armed" : "", fields.get("reason") == null ? ""
                        : Text.oneLine(String.valueOf(fields.get("reason")), 120)));
            });
        }
        text.append(rows.isEmpty() ? "no hook installed\n" : Text.table(List.of("HOOK", "STATE", "COUNT", "STUB",
                "REASON"), rows));
        if (pack == null) {
            text.append("pack: none (give --pack, or put one pack on the class path)\n");
        } else if (catalogue.isEmpty()) {
            text.append("pack ").append(pack.id()).append(": no hook catalogue\n");
        } else {
            text.append("pack ").append(pack.id()).append(" catalogue: ").append(catalogue.id()).append(" v")
                    .append(catalogue.version()).append(' ').append(catalogue.hash()).append(matches
                    ? " (installed)" : " (not installed)").append('\n');
            List<List<String>> entries = new ArrayList<>();
            for (HookEntry entry : catalogue.hooks()) {
                entries.add(List.of(entry.id(), entry.mode().name(), entry.enabled() ? "on" : "off",
                        entry.target().className() + "." + entry.target().method()));
            }
            text.append(Text.table(List.of("HOOK", "MODE", "ENABLED", "TARGET"), entries));
            for (String warning : catalogue.warnings()) {
                text.append("warning: ").append(warning).append('\n');
            }
        }
        cli.out().print(target.scrub(text.toString()));
        return Exit.OK;
    }

    private static int install(Target target, DomainPack pack, boolean json, Cli cli) throws Exception {
        HookInstaller.Result result;
        try {
            result = HookInstaller.install(pack, target.client(), target.client().capabilities(), HooksMode.ON, false,
                    Clock.systemUTC());
        } catch (HookInstaller.Refused e) {
            if (json) {
                cli.out().print(target.scrub(Json.writePretty(e.record())));
            }
            throw new CliException(Exit.FAILED, "refused (" + e.kind() + "): " + e.getMessage());
        }
        Map<String, Object> record = result.record();
        if (json) {
            cli.out().print(target.scrub(Json.writePretty(record)));
            return Exit.OK;
        }
        StringBuilder text = new StringBuilder();
        if (result.alreadyInstalled()) {
            text.append("already installed: ").append(Json.write(record.get("catalogue"))).append('\n');
        } else {
            text.append("installed ").append(Json.write(record.get("catalogue"))).append(": ")
                    .append(Json.write(record.get("installed"))).append(" in ").append(record.get("ms"))
                    .append(" ms, ").append(record.get("touched")).append(" classes\n");
            if (record.get("unavailable") instanceof List<?> unavailable) {
                for (Object u : unavailable) {
                    Map<?, ?> entry = (Map<?, ?>) u;
                    text.append("unavailable: ").append(entry.get("id")).append(" ").append(entry.get("state"))
                            .append(" (").append(entry.get("reason")).append("), fallback ")
                            .append(entry.get("fallback") == null ? "none" : entry.get("fallback")).append('\n');
                }
            }
            text.append("other agents: ").append(Json.write(record.get("otherAgents"))).append('\n');
        }
        text.append("armed stubs: ").append(Json.write(record.get("armed"))).append('\n');
        cli.out().print(target.scrub(text.toString()));
        return Exit.OK;
    }

    private static int remove(Target target, List<String> ids, boolean json, Cli cli) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            params.put("ids", ids);
        }
        Map<?, ?> answer;
        try {
            answer = map(target.client().call("hook.remove", params).value());
        } catch (AgentRpcException e) {
            if (e.error().code() != ErrorCodes.HOOK_VERIFY_FAILED) {
                throw e;
            }
            Map<String, Object> data = e.error().data() == null ? Map.of() : e.error().data();
            if (json) {
                cli.out().print(target.scrub(Json.writePretty(Text.rpcErrorJson(e))));
            } else {
                StringBuilder text = new StringBuilder("HOOK_VERIFY_FAILED: ").append(e.error().message()).append('\n');
                if (data.get("classes") instanceof List<?> classes) {
                    for (Object c : classes) {
                        text.append("left with advice: ").append(Json.write(c)).append('\n');
                    }
                }
                cli.out().print(target.scrub(text.toString()));
            }
            return Exit.FAILED;
        }
        if (json) {
            cli.out().print(target.scrub(Json.writePretty(answer)));
            return Exit.OK;
        }
        List<List<String>> rows = new ArrayList<>();
        if (answer.get("restored") instanceof List<?> restored) {
            for (Object r : restored) {
                Map<?, ?> entry = r instanceof Map<?, ?> m ? m : Map.of();
                rows.add(List.of(String.valueOf(entry.get("class")), String.valueOf(entry.get("verified")),
                        String.valueOf(entry.get("foreignEvents")), entry.get("reason") == null ? ""
                        : Text.oneLine(String.valueOf(entry.get("reason")), 120)));
            }
        }
        cli.out().print(target.scrub(rows.isEmpty() ? "nothing to restore\n" : Text.table(List.of("CLASS",
                "VERIFIED", "FOREIGN", "REASON"), rows)));
        return Exit.OK;
    }

    private static Map<String, Object> catalogueJson(DomainPack pack, HookCatalogue catalogue) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", pack == null ? null : pack.id());
        out.put("id", catalogue.id());
        out.put("version", (long) catalogue.version());
        out.put("hash", catalogue.hash());
        List<Object> entries = new ArrayList<>();
        for (HookEntry entry : catalogue.hooks()) {
            Map<String, Object> e = new LinkedHashMap<>(entry.toSpec().toJson());
            e.put("enabled", entry.enabled());
            List<Object> fallbacks = new ArrayList<>();
            entry.fallbacks().forEach(f -> fallbacks.add(f.toString()));
            e.put("fallbacks", fallbacks);
            entries.add(e);
        }
        out.put("hooks", entries);
        out.put("warnings", catalogue.warnings());
        return out;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> m ? m : Map.of();
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
