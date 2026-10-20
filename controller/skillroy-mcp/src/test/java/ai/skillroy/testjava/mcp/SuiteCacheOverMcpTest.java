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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.CheckScope;
import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PackCapability;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * phase8-contracts.md 13.1 row 49 (9.1.5, 9.3.3): the server owns one suite cache, set on the session's options before
 * it opens and on every {@code skillroy_scenario_run}: the pack's {@code SUITE} check runs once at the open and two
 * runs answer it from the cache ({@code cached: true}); {@code skillroy_catalogue} lists the pack's capability with
 * its source {@code pack:desk}.
 */
class SuiteCacheOverMcpTest {

    @TempDir
    Path dir;

    private static final String RUN = "scenario: suite-probe\npack: desk\nsteps:\n  - enter: {value: SAW-2}\n";

    /** The desk's pack with a counting SUITE check and one capability. */
    private static DomainPack suite(DomainPack base, AtomicInteger checks) {
        return new DelegatingDomainPack(base, "suite", "0.1") {
            @Override
            public List<PreflightCheck> preflight() {
                return List.of(new PreflightCheck() {
                    @Override
                    public String id() {
                        return "desk.open";
                    }

                    @Override
                    public CheckScope scope() {
                        return CheckScope.SUITE;
                    }

                    @Override
                    public String check(AgentConnection agent) {
                        checks.incrementAndGet();
                        return null;
                    }
                });
            }

            @Override
            public List<PackCapability> capabilities() {
                return List.of(new PackCapability() {
                    @Override
                    public String id() {
                        return "desk.env.ready";
                    }

                    @Override
                    public Capability evaluate(PreflightCheck.PreflightContext ctx) {
                        return new Capability("desk.env.ready", CapabilityState.OK, null, null);
                    }
                });
            }
        };
    }

    private static Map<String, Object> preflightRecord(McpFixture fx, String runId) throws Exception {
        for (String line : Files.readAllLines(fx.out.resolve("runs").resolve(runId).resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if ("preflight".equals(record.get("type"))) {
                return record;
            }
        }
        throw new AssertionError("no preflight record in " + runId);
    }

    @Test
    void theServersCacheAnswersTheRunsAfterTheOpen() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try (McpFixture fx = new McpFixture(dir)) {
            fx.desk.agent.session = "desk-session-1";
            fx.start(fx.options(suite(fx.desk.pack, checks)).mutating(true).build());
            assertEquals(1, checks.get(), "the session's open ran the check");
            for (String runId : List.of("run-1", "run-2")) {
                McpSchema.CallToolResult result = fx.call("skillroy_scenario_run", Map.of("yaml", RUN, "mode",
                        "live"));
                assertFalse(result.isError(), McpFixture.text(result));
                Map<String, Object> record = preflightRecord(fx, runId);
                assertEquals("desk.open", record.get("id"));
                assertEquals("suite", record.get("scope"));
                assertEquals(true, record.get("cached"), runId + " was answered from the server's cache");
            }
            assertEquals(1, checks.get(), "one evaluation for the open and two runs");

            Map<String, Object> catalogue = McpFixture.json(fx.call("skillroy_catalogue", Map.of()));
            Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) catalogue.get("capabilities")).get("desk.env.ready");
            assertEquals("OK", entry.get("state"));
            assertEquals("pack:desk", entry.get("source"));
        }
    }
}
