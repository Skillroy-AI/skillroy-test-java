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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookTarget;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookEntry;
import ai.skillroy.testjava.scenario.hooks.HookInstaller;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix, pass 2, P2-L6-F1 (the reviewer's probe 2 and the sceptic's probe S-A, inverted, and the
 * {@code on} twin): {@code skillroy_scenario_run} runs step 0b under the server's {@code --hooks} switch, never the run
 * default {@code AUTO}. On a {@code --hooks off} server a client's run installs nothing and leaves a person's own
 * edition of the catalogue alone (no {@code replace}, so no {@code hook.remove} of everything first); on a
 * {@code --hooks on} server it finds the server's own install. Before the fix both {@code off} cases sent
 * {@code hook.install}, the second with {@code replace: true}.
 */
class ScenarioRunHooksTest {

    @TempDir
    Path dir;

    private static final String RUN = "scenario: hooks-probe\npack: desk\nsteps:\n  - enter: {value: SAW-2}\n";

    /** The desk's pack with a one-entry catalogue {@code toy}. */
    private static DomainPack hooked(DomainPack base, HookCatalogue catalogue) {
        return new DelegatingDomainPack(base, "probe", "0.1") {
            @Override
            public HookCatalogue hooks() {
                return catalogue;
            }
        };
    }

    private static HookCatalogue catalogue() {
        return HookCatalogue.builder("toy", 1).packages(List.of("org.example.toy"))
                .add(new HookEntry("toy.applied", HookMode.CAPTURE, true, new HookTarget("org.example.toy.Desk",
                        "limit", List.of("(Ljava/lang/String;)Ljava/math/BigDecimal;"), null), null, List.of(),
                        false, null, List.of(), null))
                .build();
    }

    /**
     * A target with the hooks engine whose {@code hook.health} names {@code installed} (null: none) until a
     * {@code hook.install} installs another, which it then names.
     */
    private static void hookEngine(FakeAgent agent, Map<String, Object> installed) {
        agent.capability(HookInstaller.ENGINE, CapabilityState.OK);
        agent.describe("hook.health", false, false, null).describe("hook.install", false, false, null);
        Map<String, Object> current = new LinkedHashMap<>();
        if (installed != null) {
            current.putAll(installed);
        }
        agent.on("hook.health", params -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("hooks", current.isEmpty() ? Map.of() : Map.of("toy.applied", Map.of("state", "OK")));
            answer.put("stubs", List.of());
            answer.put("spy", Map.of("version", 1L));
            synchronized (current) {
                if (!current.isEmpty()) {
                    answer.put("catalogue", new LinkedHashMap<>(current));
                }
            }
            return answer;
        });
        agent.on("hook.install", params -> {
            Map<?, ?> catalogue = (Map<?, ?>) params.get("catalogue");
            synchronized (current) {
                current.clear();
                current.put("id", catalogue.get("id"));
                current.put("hash", catalogue.get("hash"));
                current.put("installedAt", "2026-10-09T07:00:00Z");
            }
            agent.capability("hook.toy.applied", CapabilityState.OK);
            return Map.of("catalogue", Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"),
                    "alreadyInstalled", false), "hooks", List.of(Map.of("id", "toy.applied", "state", "OK",
                    "strategy", "advice", "capability", "hook.toy.applied")), "touched",
                    List.of(Map.of("class", "org.example.toy.Desk")), "otherAgents", List.of());
        });
    }

    private static SessionOptions session(HooksMode mode) {
        return SessionOptions.defaults(null)
                .withBind(BindOptions.defaults().withCredentials(Desk.credentials()).withEnvId("seed-a"))
                .withId("sess-1").withHooks(mode);
    }

    private static String run(McpFixture fx) throws Exception {
        McpSchema.CallToolResult result = fx.call("skillroy_scenario_run", Map.of("yaml", RUN, "mode", "live"));
        assertFalse(result.isError(), McpFixture.text(result));
        return Files.readString(fx.out.resolve("runs").resolve("run-1").resolve("run.jsonl"));
    }

    @Test
    void aClientsRunInstallsNothingOnAHooksOffServer() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            FakeAgent agent = fx.desk.agent;
            hookEngine(agent, null);
            fx.start(fx.options(hooked(fx.desk.pack, catalogue())).session(session(HooksMode.OFF)).mutating(true)
                    .build());
            assertTrue(agent.callsTo("hook.install").isEmpty(), "the server's own session installed nothing (off)");
            String log = run(fx);
            assertTrue(agent.callsTo("hook.install").isEmpty(), "the client's run installed nothing: "
                    + agent.methods());
            assertTrue(log.contains("\"type\":\"hooks\",") && log.contains("\"mode\":\"off\",\"skipped\":\"off\""),
                    log);
        }
    }

    @Test
    void aClientsRunLeavesThePersonsEditionAloneOnAHooksOffServer() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            FakeAgent agent = fx.desk.agent;
            hookEngine(agent, Map.of("id", "toy", "hash", "sha256:persons-own-edition", "installedAt",
                    "2026-10-09T07:00:00Z"));
            fx.start(fx.options(hooked(fx.desk.pack, catalogue())).session(session(HooksMode.OFF)).mutating(true)
                    .build());
            String log = run(fx);
            assertTrue(agent.callsTo("hook.install").isEmpty(), "no install and no replace: " + agent.methods());
            assertFalse(log.contains("\"replaced\":true"), log);
        }
    }

    @Test
    void aClientsRunFindsTheServersOwnInstallOnAHooksOnServer() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            FakeAgent agent = fx.desk.agent;
            hookEngine(agent, null);
            HookCatalogue catalogue = catalogue();
            fx.start(fx.options(hooked(fx.desk.pack, catalogue)).session(session(HooksMode.ON)).mutating(true)
                    .build());
            assertEquals(1, agent.callsTo("hook.install").size(), "the server installed at its start (on)");
            String log = run(fx);
            assertEquals(1, agent.callsTo("hook.install").size(), "one install in all, the server's: "
                    + agent.methods());
            assertTrue(log.contains("\"mode\":\"on\",\"alreadyInstalled\":true"), log);
            assertTrue(log.contains(catalogue.hash()), log);
        }
    }
}
