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

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code skillroy hooks status|install|remove} against the fake agent (phase6-contracts.md 6.2, 13.1 item 8): the
 * armed stubs and the other agents printed, the install's record and its refusals, the restore's verification and
 * {@code HOOK_VERIFY_FAILED}; and {@code --hooks} parsed by {@code run}, {@code session} and {@code mcp}. Phase 8
 * (phase8-contracts.md 6.10, section 13.1 row 27): the two deny lines, the class table with its code sources, the
 * pack table's {@code CODE SOURCE} column, {@code --json}, and the {@code unknown} line against an old agent.
 */
class HooksCommandTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Map<String, Object> installed;
    private final List<Map<String, Object>> installs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        agent.capabilities.put("hook.engine", new Capability("hook.engine", CapabilityState.OK, null, "advice"));
        agent.handlers.put("hook.health", p -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("hooks", installed == null ? Map.of() : Map.of("counter.read", Map.of("state", "FIRING",
                    "count", 3L, "armed", true)));
            answer.put("stubs", installed == null ? List.of() : List.of("counter.read"));
            answer.put("spy", Map.of("version", 1L, "counters", Map.of("enters", 3L, "unhandled", 0L, "failures",
                    0L, "skips", 0L)));
            if (installed != null) {
                answer.put("catalogue", installed);
            }
            answer.put("otherAgents", List.of("profiler.jar", "byteman"));
            return answer;
        });
        agent.handlers.put("hook.install", p -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) p.raw();
            installs.add(params);
            Map<?, ?> catalogue = (Map<?, ?>) params.get("catalogue");
            installed = Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"), "installedAt", 1L);
            return Map.of("catalogue", Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash")),
                    "hooks", List.of(Map.of("id", "counter.read", "state", "UNAVAILABLE", "reason",
                            "class not loaded", "capability", "hook.counter.read")),
                    "touched", List.of(), "otherAgents", List.of("profiler.jar"));
        });
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp.resolve("unused-default"), all.toArray(String[]::new)).never(agent.token);
    }

    @Test
    void statusShowsTheEngineBesideThePacksCatalogue() {
        String before = cli("hooks", "status").expectExit(Exit.OK).out();
        assertTrue(before.contains("installed catalogue: none"), before);
        assertTrue(before.contains("other agents: profiler.jar, byteman"), before);
        assertTrue(before.contains("pack clitest catalogue: clitest v1 sha256:") && before.contains("(not installed)"),
                before);
        assertTrue(before.contains("pin.checked") && before.contains("off"), before);
        assertTrue(before.contains("warning: hook pin.checked captures pinMatches"), before);
        cli("hooks", "install").expectExit(Exit.OK);
        String after = cli("hooks", "status").expectExit(Exit.OK).out();
        assertTrue(after.contains("armed stubs: counter.read"), after);
        assertTrue(after.contains("(installed)"), after);
        assertTrue(after.contains("counter.read") && after.contains("FIRING"), after);
        Map<String, Object> json = Json.parseObject(cli("hooks", "status", "--json").expectExit(Exit.OK).out());
        assertEquals(true, json.get("matches"));
        assertEquals(List.of("profiler.jar", "byteman"), json.get("otherAgents"));
        assertEquals(List.of("counter.read"), ((Map<?, ?>) json.get("health")).get("stubs"));
        Map<?, ?> catalogue = (Map<?, ?>) json.get("catalogue");
        assertEquals(2, ((List<?>) catalogue.get("hooks")).size());
        assertEquals(1, ((List<?>) catalogue.get("warnings")).size());
    }

    @Test
    void statusPrintsTheDenyTheClassesAndTheCodeSources() {
        String old = cli("hooks", "status").expectExit(Exit.OK).out();
        assertTrue(old.contains("deny (target): unknown (the agent reports no deny list)"), old);
        assertTrue(old.contains("deny (pack catalogue clitest): none"), old);
        assertTrue(old.contains("CODE SOURCE"), "the pack table's column: " + old);
        assertTrue(old.lines().anyMatch(l -> l.startsWith("counter.read") && l.trim().endsWith("-")),
                "no qualifier: -\n" + old);
        assertFalse(old.contains("no class touched"), "an old agent reports no classes: no class table\n" + old);
        agent.handlers.put("hook.health", p -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("hooks", Map.of("counter.read", Map.of("state", "FIRING", "count", 3L, "armed", false,
                    "deniedNow", true)));
            answer.put("stubs", List.of());
            answer.put("otherAgents", List.of());
            answer.put("classes", List.of(Map.of("class", "org.example.cli.Counter", "loader", "system",
                    "codeSource", "cli-app-1.2.jar", "bytes", 900L, "methods", 4L, "hooks", List.of("counter.read"),
                    "deniedNow", "denied by the catalogue clitest: org.example.cli.Counter")));
            answer.put("deny", Map.of("catalogue", List.of("org.example.cli.Counter"), "from", List.of("clitest"),
                    "option", List.of("org.example.cli.Clock", "x.Y"), "modules", 1L));
            return answer;
        });
        String out = cli("hooks", "status").expectExit(Exit.OK).out();
        assertTrue(out.contains("deny (target): option org.example.cli.Clock, x.Y; catalogue clitest"
                + " org.example.cli.Counter; modules 1"), out);
        assertTrue(out.contains("CLASS") && out.contains("LOADER") && out.contains("HOOKS"), out);
        assertTrue(out.lines().anyMatch(l -> l.startsWith("org.example.cli.Counter") && l.contains("system")
                && l.contains("cli-app-1.2.jar") && l.contains("counter.read DENIED: denied by the catalogue"
                + " clitest: org.example.cli.Counter")), out);
        Map<String, Object> json = Json.parseObject(cli("hooks", "status", "--json").expectExit(Exit.OK).out());
        Map<?, ?> health = (Map<?, ?>) json.get("health");
        assertEquals(List.of("org.example.cli.Counter"), ((Map<?, ?>) health.get("deny")).get("catalogue"));
        assertEquals(1, ((List<?>) health.get("classes")).size());
        Map<?, ?> catalogue = (Map<?, ?>) json.get("catalogue");
        assertEquals(List.of(), catalogue.get("deny"));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", "org.example.cli");
        rule.put("codeSource", null);
        assertEquals(List.of(rule), catalogue.get("packages"));
        agent.handlers.put("hook.health", p -> Map.of("hooks", Map.of(), "stubs", List.of(), "otherAgents",
                List.of(), "classes", List.of(), "deny", Map.of("catalogue", List.of(), "from", List.of(), "option",
                        List.of(), "modules", 0L)));
        String empty = cli("hooks", "status").expectExit(Exit.OK).out();
        assertTrue(empty.contains("deny (target): option none; catalogue none; modules 0"), empty);
        assertTrue(empty.contains("no class touched"), empty);
    }

    @Test
    void installPrintsTheRecordAndIsRefusedWithoutAnEngineOrForAnotherCatalogue() {
        String out = cli("hooks", "install").expectExit(Exit.OK).out();
        assertTrue(out.contains("unavailable: counter.read UNAVAILABLE (class not loaded), fallback seam:test.poke"),
                out);
        assertTrue(out.contains("other agents: [\"profiler.jar\"]"), out);
        assertEquals(1, installs.size());
        assertEquals(1, ((List<?>) installs.get(0).get("specs")).size(), "the enabled entries only");
        String again = cli("hooks", "install", "--json").expectExit(Exit.OK).out();
        assertEquals(true, Json.parseObject(again).get("alreadyInstalled"));
        installed = Map.of("id", "other", "hash", "sha256:0");
        String refused = cli("hooks", "install").expectExit(Exit.FAILED).err();
        assertTrue(refused.contains("catalogue other is installed on this target"), refused);
        installed = null;
        agent.capabilities.put("hook.engine", new Capability("hook.engine", CapabilityState.UNAVAILABLE,
                "no Instrumentation in EMBEDDED mode", null));
        String noEngine = cli("hooks", "install").expectExit(Exit.FAILED).err();
        assertTrue(noEngine.contains("hooks ON, but hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode"),
                noEngine);
    }

    @Test
    void removePrintsEveryClassAndHookVerifyFailedIsExitOne() {
        agent.handlers.put("hook.remove", p -> Map.of("hooks", List.of(), "restored", List.of(
                Map.of("class", "org.example.cli.Counter", "loader", "app", "verified", "OK", "foreignEvents", 0L),
                Map.of("class", "org.example.cli.Other", "loader", "app", "verified", "DEGRADED", "reason",
                        "foreign redefinition observed: 1 events", "foreignEvents", 1L))));
        String out = cli("hooks", "remove", "--ids", "counter.read,other.one").expectExit(Exit.OK).out();
        assertTrue(out.contains("org.example.cli.Counter") && out.contains("OK"), out);
        assertTrue(out.contains("DEGRADED") && out.contains("foreign redefinition observed"), out);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) Json.parseObject(agent.rpcRequests("hook.remove").get(0)
                .body()).get("params");
        assertEquals(List.of("counter.read", "other.one"), params.get("ids"));
        agent.handlers.put("hook.remove", p -> {
            throw new RpcException(ErrorCodes.HOOK_VERIFY_FAILED, "advice left behind", Map.of(
                    "classes", List.of(Map.of("class", "org.example.cli.Counter", "bytesBefore", 10L, "bytesAfter",
                            12L, "foreignEvents", 0L))));
        });
        String failed = cli("hooks", "remove").expectExit(Exit.FAILED).out();
        assertTrue(failed.contains("HOOK_VERIFY_FAILED") && failed.contains("left with advice"), failed);
    }

    @Test
    void usageErrors() {
        cli("hooks").expectExit(Exit.USAGE);
        cli("hooks", "arm").expectExit(Exit.USAGE);
        cli("hooks", "status", "--ids", "a").expectExit(Exit.USAGE);
        cli("hooks", "remove", "--ids", ",").expectExit(Exit.USAGE);
        String run = cli("run", "--hooks", "sometimes", tmp.resolve("missing.yaml").toString()).expectExit(Exit.USAGE)
                .err();
        assertTrue(run.contains("--hooks") && run.contains("auto, on or off"), run);
        assertTrue(cli("run", "--help").out().contains("--hooks"));
        assertTrue(cli("session", "--help").out().contains("--hooks"));
        assertTrue(cli("mcp", "--help").out().contains("--hooks"));
        assertTrue(cli("hooks", "--help").out().contains("status|install|remove"));
        assertFalse(agent.rpcRequests("hook.install").size() > 0, "nothing was installed");
    }
}
