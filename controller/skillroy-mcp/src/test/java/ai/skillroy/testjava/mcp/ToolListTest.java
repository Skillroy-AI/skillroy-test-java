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

import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.tools.ToolProjection;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.3 and 9.6: {@code initialize}, {@code tools/list} (names, hints, schemas) in the read-only
 * default and with {@code mutating}, a mutating tool refused in read-only mode (not listed, and a call refused), never
 * a destructive primitive, nothing of the agent's own namespace, no verb that reaches either, {@code web_evaluate} and
 * {@code web_wait_flag} only with a person's opt-in, {@code skillroy_session_ask} only with an actor.
 */
class ToolListTest {

    @TempDir
    Path dir;

    private static final List<String> WORKFLOW_READ_ONLY = List.of("skillroy_catalogue", "skillroy_snapshot",
            "skillroy_facts", "skillroy_events", "skillroy_resolve", "skillroy_scenario_compile",
            "skillroy_session_check", "skillroy_session_observe", "skillroy_session_note", "skillroy_session_export");

    @Test
    void initializeAnswersTheServerAndTheLatestProtocol() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpSchema.InitializeResult init = fx.client.getCurrentInitializationResult();
            assertEquals(ProtocolVersions.MCP_2025_11_25, init.protocolVersion());
            assertEquals(SkillroyMcpServer.SERVER_NAME, init.serverInfo().name());
            assertNotNull(init.capabilities().tools(), "tools are offered");
            assertNotNull(init.capabilities().resources(), "resources are offered");
            assertTrue(init.instructions().contains("read-only"), init.instructions());
            assertTrue(init.instructions().contains("sess-1"), init.instructions());
        }
    }

    @Test
    void theReadOnlyDefaultListsTheReadsAndNoMutatingTool() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            List<String> names = fx.toolNames();
            assertEquals(WORKFLOW_READ_ONLY, names.subList(0, WORKFLOW_READ_ONLY.size()), "the workflow tools first");
            assertEquals(List.of("app_state", "data_query", "desk_deposit_primitive", "desk_dump", "desk_echo",
                    "desk_item", "desk_state", "jvm_info", "swing_capture", "swing_snapshot"),
                    names.subList(WORKFLOW_READ_ONLY.size(), names.size()), "the reads of the domain and base"
                    + " namespaces, data_query with its allow-list; desk.deposit with the suffix, since the verb"
                    + " deposit takes desk_deposit even where it is not offered (D92)");
            assertFalse(names.contains("desk_deposit"), "one name never means two things by mode");
            for (McpSchema.Tool tool : fx.client.listTools().tools()) {
                McpSchema.ToolAnnotations hints = tool.annotations();
                assertNotNull(hints, tool.name());
                assertEquals(Boolean.FALSE, hints.destructiveHint(), tool.name());
                assertEquals(Boolean.FALSE, hints.idempotentHint(), tool.name());
                assertEquals(Boolean.FALSE, hints.openWorldHint(), tool.name());
                boolean filesOnly = tool.name().equals("skillroy_session_export");
                assertEquals(!filesOnly, hints.readOnlyHint(), tool.name() + ": only the export writes (files, not"
                        + " the target)");
            }
            assertFalse(names.contains("desk_enter") || names.contains("desk_input") || names.contains("swing_click")
                    || names.contains("skillroy_scenario_run"), "nothing that changes the target: " + names);
            assertFalse(names.contains("skillroy_session_ask"), "no actor, no ask");
            assertFalse(names.contains("desk_wipe") || names.contains("agent_disable") || names.contains("agent_info")
                    || names.contains("evt_query") || names.contains("trace_mark") || names.contains("data_sources"),
                    "never destructive, never the agent's own, no other namespace: " + names);
            assertTrue(fx.server.session().options().readOnly(), "the session is read-only too");
        }
    }

    @Test
    void mutatingAddsTheVerbsTheMutatingPrimitivesAndTheRunNeverADestructiveOne() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            List<String> names = fx.toolNames();
            assertTrue(names.containsAll(List.of("desk_deposit", "desk_deposit_primitive", "desk_enter", "desk_lend",
                    "desk_lend_primitive", "desk_poke", "desk_poke_primitive", "desk_input", "swing_click",
                    "skillroy_scenario_run")), names.toString());
            assertEquals(names.size(), new java.util.HashSet<>(names).size(), "distinct names");
            assertFalse(names.contains("desk_wipe") || names.contains("agent_disable"), "never destructive: " + names);
            McpSchema.Tool deposit = fx.tool("desk_deposit");
            assertTrue(deposit.description().contains("Pack verb 'deposit'"), "the verb keeps the plain name");
            assertEquals(Boolean.FALSE, deposit.annotations().readOnlyHint());
            McpSchema.Tool depositPrimitive = fx.tool("desk_deposit_primitive");
            assertTrue(depositPrimitive.description().startsWith("[thread: WORKER, timeout: 5000 ms] does desk.deposit"),
                    depositPrimitive.description());
            assertEquals(Boolean.TRUE, depositPrimitive.annotations().readOnlyHint(), "the read primitive, renamed");
            assertEquals(Boolean.FALSE, fx.tool("desk_lend_primitive").annotations().readOnlyHint());
            String log = fx.logText();
            assertEquals(1, log.split("primitives named with _primitive", -1).length - 1, "logged once: " + log);
            assertTrue(log.contains("desk_deposit_primitive (desk.deposit), desk_lend_primitive (desk.lend),"
                    + " desk_poke_primitive (desk.poke)"), log);
            assertFalse(names.contains("skillroy_session_ask"), "no actor, no ask");
            McpSchema.Tool enter = fx.tool("desk_enter");
            assertEquals(Boolean.FALSE, enter.annotations().readOnlyHint(), "a verb changes the target");
            assertEquals(Boolean.FALSE, enter.annotations().destructiveHint());
            ToolSpec projected = ToolProjection.verb("desk", "enter", fx.desk.pack.steps().argsSchema("enter"));
            assertEquals(projected.inputSchema(), enter.inputSchema(), "the verb's argsSchema, made strict");
            assertTrue(enter.description().contains("Pack verb 'enter'"), enter.description());
            McpSchema.Tool lend = fx.tool("desk_lend");
            assertEquals(List.of("item", "borrower"), lend.inputSchema().get("required"), "every key required");
            assertEquals(List.of("string", "null"), ((Map<?, ?>) ((Map<?, ?>) lend.inputSchema().get("properties"))
                    .get("borrower")).get("type"), "an optional key nullable");
            assertEquals(Map.of("type", "object"), fx.tool("desk_poke").inputSchema(), "no argsSchema: free-form");
            McpSchema.Tool click = fx.tool("swing_click");
            assertEquals(Boolean.FALSE, click.annotations().readOnlyHint());
            assertTrue(click.description().startsWith("[thread: WORKER, timeout: 5000 ms] does swing.click"),
                    click.description());
            assertEquals(Boolean.TRUE, fx.tool("desk_state").annotations().readOnlyHint());
            assertFalse(fx.server.session().options().readOnly());
        }
    }

    /**
     * Decision D98: {@code web_evaluate} and {@code web_wait_flag} (each runs a caller's script in the target's pages;
     * the second is a read) are offered only when a person opted in ({@code allowEvaluate},
     * {@code skillroy mcp --allow-evaluate}); never otherwise, and {@code web_evaluate} only on a mutating server.
     */
    @Test
    void theScriptPrimitivesAreOfferedOnlyWithAPersonsOptIn() throws Exception {
        try (McpFixture fx = new McpFixture(dir.resolve("default")).startMutating()) {
            assertFalse(fx.toolNames().contains("web_evaluate") || fx.toolNames().contains("web_wait_flag"),
                    fx.toolNames().toString());
            assertTrue(fx.toolNames().contains("swing_click"), "the other mutating base primitives are offered");
            assertThrows(McpError.class, () -> fx.call("web_evaluate", Map.of("script", "1")));
            assertThrows(McpError.class, () -> fx.call("web_wait_flag", Map.of("expression", "1")));
            assertTrue(fx.desk.agent.callsTo("web.evaluate").isEmpty() && fx.desk.agent.callsTo("web.waitFlag")
                    .isEmpty());
        }
        try (McpFixture fx = new McpFixture(dir.resolve("read-only-default")).startReadOnly()) {
            assertFalse(fx.toolNames().contains("web_wait_flag"), "a read that runs a script: " + fx.toolNames());
        }
        try (McpFixture fx = new McpFixture(dir.resolve("opted-in"))) {
            fx.start(fx.options().mutating(true).allowEvaluate(true).build());
            assertTrue(fx.toolNames().containsAll(List.of("web_evaluate", "web_wait_flag")), fx.toolNames()
                    .toString());
            assertFalse(fx.call("web_evaluate", Map.of("script", "1")).isError());
            assertEquals(1, fx.desk.agent.callsTo("web.evaluate").size());
        }
        try (McpFixture fx = new McpFixture(dir.resolve("read-only"))) {
            fx.start(fx.options().allowEvaluate(true).build());
            assertTrue(fx.toolNames().contains("web_wait_flag"), "read-only, opted in: " + fx.toolNames());
            assertFalse(fx.toolNames().contains("web_evaluate"), "read-only: nothing mutating, opted in or not");
        }
    }

    /**
     * Decision D96: a verb whose compiled calls (or cleanup calls) reach what the server never offers is not offered
     * as a tool at all, since its tool would run them for the client; the other verbs are; the withheld ones are
     * logged and listed by {@code skillroy_catalogue}.
     */
    @Test
    void aVerbThatReachesWhatTheServerNeverOffersIsNotOffered() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(ScenarioRunPolicyTest.extended(fx.desk)).mutating(true).build());
            List<String> names = fx.toolNames();
            assertFalse(names.contains("desk_clear"), "clear calls the destructive desk.wipe: " + names);
            assertFalse(names.contains("desk_tidy"), "tidy's cleanup calls desk.wipe: " + names);
            assertTrue(names.containsAll(List.of("desk_deposit", "desk_enter", "desk_lend", "desk_poke", "desk_peek")),
                    "the other verbs, a verb over another module's primitive among them: " + names);
            assertThrows(McpError.class, () -> fx.call("desk_clear", Map.of()));
            assertTrue(fx.desk.agent.callsTo("desk.wipe").isEmpty());
            assertTrue(fx.logText().contains("not offered because their calls reach what this server never offers:"
                    + " desk_clear (clear calls the destructive primitive desk.wipe"), fx.logText());
            List<?> withheld = (List<?>) McpFixture.json(fx.call("skillroy_catalogue", Map.of())).get("withheldVerbs");
            assertEquals(List.of("clear", "tidy"), withheld.stream().map(w -> ((Map<?, ?>) w).get("verb")).toList());
            Map<?, ?> tidy = (Map<?, ?>) withheld.get(1);
            assertEquals("desk_tidy", tidy.get("tool"));
            assertTrue(String.valueOf(tidy.get("reason")).startsWith("its cleanup calls the destructive primitive"
                    + " desk.wipe"), tidy.toString());
        }
    }

    @Test
    void aMutatingToolIsRefusedInReadOnlyMode() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpError verb = assertThrows(McpError.class, () -> fx.call("desk_enter", Map.of("value", "HAM-1")));
            assertTrue(verb.getMessage().contains("Unknown tool"), verb.getMessage());
            assertThrows(McpError.class, () -> fx.call("swing_click", Map.of("target", "h12")));
            assertThrows(McpError.class, () -> fx.call("desk_wipe", Map.of("confirm", true)));
            assertThrows(McpError.class, () -> fx.call("skillroy_scenario_run", Map.of("yaml", "x", "mode", "live")));
            assertTrue(fx.desk.agent.callsTo("desk.input").isEmpty(), "nothing reached the agent");
            assertTrue(fx.desk.agent.callsTo("swing.click").isEmpty());
            assertTrue(fx.desk.agent.callsTo("desk.wipe").isEmpty());
        }
    }

    @Test
    void sessionAskIsListedWithAnActorOnly() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options().actor((session, options) -> new FakeActor()).build());
            assertTrue(fx.toolNames().contains("skillroy_session_ask"), "read-only: the actor acts read-only");
            assertFalse(fx.toolNames().contains("skillroy_scenario_run"));
        }
        try (McpFixture fx = new McpFixture(dir.resolve("m"))) {
            fx.start(fx.options().mutating(true).actor((session, options) -> new FakeActor()).build());
            assertTrue(fx.toolNames().containsAll(List.of("skillroy_session_ask", "skillroy_scenario_run")));
        }
    }

    @Test
    void aReadOnlySessionMakesTheWholeServerReadOnly() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options().mutating(true).session(fx.options().build().session().withReadOnly(true)).build());
            assertFalse(fx.server.mutating());
            assertFalse(fx.toolNames().contains("desk_enter"));
        }
    }

    /**
     * Phase 6 security fix S49: the server lists its tools through the prompt mask: a verb schema's secret-looking
     * property keeps its name and loses its {@code default} and {@code enum}, and a {@code key=value} secret in a
     * description is masked (before the fix {@code tools/list} carried them).
     */
    @Test
    void theToolsAreListedThroughThePromptMask() throws Exception {
        Map<String, Object> schema = Desk.object(Map.of("item", Map.of("type", "string",
                "description", "the item; token=List-Tok-7713"), "password", Map.of("type", "string",
                "default", "List-Default-2209", "enum", List.of("List-Enum-4410"))), List.of("item"));
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(fx.desk.packWithLendSchema(schema)).mutating(true).build());
            McpSchema.Tool lend = fx.tool("desk_lend");
            String listed = ai.skillroy.testjava.protocol.Json.write(lend.inputSchema());
            assertTrue(listed.contains("\"password\""), listed);
            for (String hidden : List.of("List-Tok-7713", "List-Default-2209", "List-Enum-4410")) {
                assertFalse(listed.contains(hidden), hidden + " in tools/list: " + listed);
            }
        }
    }
}
