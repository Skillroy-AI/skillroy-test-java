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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.StepJournal;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.3, 12.2-12.4: the session tools (check, observe, note, export with its three files),
 * {@code skillroy_scenario_run} with its run directory and resources, and {@code skillroy_session_ask} through the
 * actor seam (absent without a factory).
 */
class SessionToolsTest {

    @TempDir
    Path dir;

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void checkObserveAndNoteAreJournaled() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            Map<String, Object> held = McpFixture.json(fx.call("skillroy_session_check", args("path", "desk.mode",
                    "matcher", "IDLE")));
            assertEquals(true, held.get("held"), held.toString());
            Map<String, Object> notHeld = McpFixture.json(fx.call("skillroy_session_check", args("path", "desk.mode",
                    "matcher", Map.of("equals", "LOAN"))));
            assertEquals(false, notHeld.get("held"));
            assertEquals("IDLE", notHeld.get("actual"));
            Map<String, Object> observed = McpFixture.json(fx.call("skillroy_session_observe", args("path",
                    "desk.operator")));
            assertEquals("100", observed.get("value"));
            McpSchema.CallToolResult nowhere = fx.call("skillroy_session_observe", args("path", "desk.nothing"));
            assertTrue(nowhere.isError(), McpFixture.text(nowhere));
            assertFalse(fx.call("skillroy_session_note", args("text", "checked the desk")).isError());
            List<String> types = fx.server.session().journal().entries().stream().map(StepJournal.Entry::type)
                    .toList();
            assertEquals(List.of("session-start", "check", "check", "observe", "note"), types);
        }
    }

    @Test
    void exportWritesTheScenarioThePlanAndTheBindings() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            assertFalse(fx.call("skillroy_resolve", args("slot", "item", "kind", "Item", "args",
                    Map.of("category", "tools"), "strategy", null)).isError());
            assertFalse(fx.call("desk_enter", Map.of("value", "$item.code")).isError());
            assertFalse(fx.call("skillroy_session_observe", args("path", "desk.mode")).isError());
            McpSchema.CallToolResult result = fx.call("skillroy_session_export", args("scenarioId", "desk-session",
                    "approvedBy", null));
            String report = McpFixture.text(result);
            assertFalse(result.isError(), report);
            assertTrue(report.contains("Exported session sess-1 as desk-session: 2 steps"), "the verb step and the"
                    + " observed expectation: " + report);
            assertTrue(report.contains("Observed expectations: derive or confirm"), report);
            Path yaml = fx.out.resolve("desk-session.yaml");
            Path plan = fx.out.resolve("plan").resolve("desk-session.plan.json");
            Path bindings = fx.out.resolve("bindings").resolve("seed-a").resolve("desk-session.bindings.json");
            assertTrue(report.contains(yaml.toString()) && report.contains(plan.toString())
                    && report.contains(bindings.toString()), report);
            String text = Files.readString(yaml, StandardCharsets.UTF_8);
            assertTrue(text.contains("enter: {value: $item.code}") || text.contains("value: $item.code"), text);
            Plan compiled = PlanCompiler.compile(yaml, fx.desk.pack);
            assertEquals(Files.readString(plan, StandardCharsets.UTF_8), compiled.toJsonText(),
                    "the plan is the compiled YAML");
            assertEquals(compiled.hash(), Json.parseObject(Files.readString(bindings, StandardCharsets.UTF_8))
                    .get("planHash"), "the bindings belong to that plan");
            McpSchema.CallToolResult bad = fx.call("skillroy_session_export", args("scenarioId", "Not An Id"));
            assertTrue(bad.isError(), McpFixture.text(bad));
        }
    }

    @Test
    void scenarioRunRunsLiveWithItsOwnDirectoryAndResources() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            String yaml = "scenario: desk-run\npack: desk\nsteps:\n  - enter: {value: SAW-2}\n"
                    + "  - expect: {desk.mode: LOAN}\n";
            McpSchema.CallToolResult result = fx.call("skillroy_scenario_run", Map.of("yaml", yaml, "mode", "live"));
            assertFalse(result.isError(), McpFixture.text(result));
            Map<String, Object> json = McpFixture.json(result);
            assertEquals("PASS", json.get("status"), json.toString());
            assertEquals("run-1", json.get("runId"));
            assertEquals("live", json.get("mode"));
            Path runDir = fx.out.resolve("runs").resolve("run-1");
            assertTrue(Files.isRegularFile(runDir.resolve("run.jsonl")));
            assertEquals(List.of("skillroy://run/run-1/run.jsonl"), json.get("resources"));
            McpSchema.ReadResourceResult read = fx.client.readResource(new McpSchema.ReadResourceRequest(
                    "skillroy://run/run-1/run.jsonl"));
            String log = ((McpSchema.TextResourceContents) read.contents().get(0)).text();
            assertTrue(log.contains("\"run-end\"") || log.contains("run-end"), log);
            assertEquals("SAW-2", fx.desk.agent.callsTo("desk.input").get(0).params().get("value"));
            McpSchema.CallToolResult failing = fx.call("skillroy_scenario_run", Map.of("yaml",
                    "scenario: desk-fail\npack: desk\nsteps:\n  - expect: {desk.mode: CLOSED}\n"
                            + "timeouts: {step: 200ms}\n", "mode", "live"));
            assertTrue(failing.isError(), "a run that did not pass is a tool error");
            Map<String, Object> failed = McpFixture.json(failing);
            assertEquals("FAIL", failed.get("status"), failed.toString());
            assertEquals("run-2", failed.get("runId"));
            assertTrue(((List<?>) failed.get("resources")).stream().anyMatch(u -> String.valueOf(u)
                    .startsWith("skillroy://run/run-2/failure/")), failed.toString());
            assertTrue(fx.desk.recovered.get() >= 1, "a run that did not pass recovers");
            McpSchema.CallToolResult approve = fx.call("skillroy_scenario_run", Map.of("yaml", yaml.replace("desk-run",
                    "desk-approve"), "mode", "approve"));
            assertEquals("APPROVE", String.valueOf(McpFixture.json(approve).get("mode")).toUpperCase());
            assertTrue(fx.call("skillroy_scenario_run", Map.of("yaml", yaml, "mode", "replay")).isError());
        }
    }

    @Test
    void sessionAskCallsTheActorOnceMadeAndClosesItWithTheServer() throws Exception {
        AtomicInteger made = new AtomicInteger();
        AtomicReference<FakeActor> actor = new AtomicReference<>();
        AtomicReference<ServerOptions> given = new AtomicReference<>();
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options().mutating(true).actor((session, options) -> {
                made.incrementAndGet();
                given.set(options);
                FakeActor a = new FakeActor(session);
                actor.set(a);
                return a;
            }).build());
            assertEquals(0, made.get(), "made on the first ask, not at start");
            McpSchema.CallToolResult result = fx.call("skillroy_session_ask", Map.of("request", "lend the hammer"));
            assertFalse(result.isError(), McpFixture.text(result));
            Map<String, Object> turn = McpFixture.json(result);
            assertEquals("lend the hammer", turn.get("request"));
            assertEquals("DONE", turn.get("outcome"));
            assertEquals(1500L, turn.get("duration"));
            assertEquals(List.of("enter: PASS"), turn.get("actions"));
            assertFalse(fx.call("skillroy_session_ask", Map.of("request", "and again")).isError());
            assertEquals(1, made.get(), "one actor for the server's life");
            assertEquals(List.of("lend the hammer", "and again"), actor.get().requests);
            assertTrue(given.get().mutating());
            StepJournal.StepEntry step = (StepJournal.StepEntry) fx.server.session().journal().entries().get(1);
            assertEquals("MODEL", step.origin().kind().name(), "the actor's steps carry its own origin");
            assertFalse(fx.desk.agent.callsTo("desk.input").get(0).options().confirm());
            fx.clientCloses();
            fx.server.close();
            assertTrue(actor.get().closed, "closed with the server");
        }
    }

    @Test
    void anActorThatCannotBeMadeIsAToolErrorAndAbsentWithoutAFactory() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options().actor((session, options) -> {
                throw new IllegalStateException("the model key is not set (ANTHROPIC_API_KEY)");
            }).build());
            McpSchema.CallToolResult result = fx.call("skillroy_session_ask", Map.of("request", "anything"));
            assertTrue(result.isError());
            assertTrue(McpFixture.text(result).contains("the model key is not set"), McpFixture.text(result));
        }
        try (McpFixture fx = new McpFixture(dir.resolve("none")).startMutating()) {
            McpError e = assertThrows(McpError.class, () -> fx.call("skillroy_session_ask", Map.of("request", "x")));
            assertNotNull(e.getMessage());
        }
    }

    @Test
    void theSessionIsTheServersOwnAndClosesWithIt() throws Exception {
        McpFixture fx = new McpFixture(dir).startMutating();
        try {
            assertSame(fx.server.session(), fx.server.session());
            assertEquals("sess-1", fx.server.session().id());
            assertEquals(fx.out.resolve("runs").resolve("sess-1"), fx.server.session().options().outputDir());
        } finally {
            fx.close();
        }
        List<String> lines = Files.readAllLines(fx.out.resolve("runs").resolve("sess-1").resolve(StepJournal.FILE));
        assertTrue(lines.get(lines.size() - 1).contains("\"session-end\""), lines.get(lines.size() - 1));
        assertFalse(fx.desk.agent.isClosed(), "the agent connection is the caller's");
    }
}
