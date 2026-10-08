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
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.4 and 9.6: {@code resources/list} and {@code resources/read} of each resource, marked
 * read-only, served as text (or {@code image/png} for a capture, see {@code ToolCallTest}).
 */
class ResourceTest {

    @TempDir
    Path dir;

    private static String read(McpFixture fx, String uri) {
        McpSchema.ReadResourceResult result = fx.client.readResource(new McpSchema.ReadResourceRequest(uri));
        assertEquals(1, result.contents().size(), uri);
        McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) result.contents().get(0);
        assertEquals(uri, text.uri());
        return text.text();
    }

    @Test
    void theResourcesAreListedReadOnly() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            List<McpSchema.Resource> resources = fx.client.listResources().resources();
            assertEquals(List.of("skillroy://env/profile", "skillroy://state/current", "skillroy://events/recent",
                    "skillroy://schema/primitives", "skillroy://schema/scenario",
                    "skillroy://run/sess-1/journal.jsonl"), resources.stream().map(McpSchema.Resource::uri).sorted(
                    (a, b) -> order(a) - order(b)).toList());
            for (McpSchema.Resource r : resources) {
                assertEquals(Map.of("readOnly", true), r.meta(), r.uri());
                assertFalse(r.mimeType().isBlank(), r.uri());
            }
        }
    }

    private static int order(String uri) {
        return List.of("skillroy://env/profile", "skillroy://state/current", "skillroy://events/recent",
                "skillroy://schema/primitives", "skillroy://schema/scenario", "skillroy://run/sess-1/journal.jsonl")
                .indexOf(uri);
    }

    @Test
    void eachResourceReads() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            Map<String, Object> profile = Json.parseObject(read(fx, "skillroy://env/profile"));
            assertEquals(Map.of("id", "desk", "version", "1.0.0"), profile.get("pack"));
            assertEquals("seed-a", profile.get("envId"));
            assertEquals("read-only", profile.get("mode"));
            assertEquals(4242L, ((Map<?, ?>) profile.get("target")).get("pid"));
            assertFalse(((Map<?, ?>) profile.get("target")).containsKey("nonce"));
            assertEquals("25", ((Map<?, ?>) profile.get("java")).get("javaVersion"));
            assertTrue(((Map<?, ?>) profile.get("capabilities")).containsKey("desk.input"));
            Map<String, Object> state = Json.parseObject(read(fx, "skillroy://state/current"));
            assertEquals("IDLE", ((Map<?, ?>) state.get("desk")).get("mode"));
            List<?> events = (List<?>) Json.parse(read(fx, "skillroy://events/recent"));
            assertTrue(events.stream().anyMatch(e -> "desk.opened".equals(((Map<?, ?>) e).get("topic"))), events
                    .toString());
            List<?> primitives = (List<?>) Json.parse(read(fx, "skillroy://schema/primitives"));
            assertTrue(primitives.stream().anyMatch(p -> "desk.input".equals(((Map<?, ?>) p).get("name"))));
            Map<String, Object> schema = Json.parseObject(read(fx, "skillroy://schema/scenario"));
            assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema"));
            assertTrue(Json.write(schema).contains("\"enter\""), "the pack's verbs are in it");
            fx.call("skillroy_session_note", Map.of("text", "a note"));
            String journal = read(fx, "skillroy://run/sess-1/journal.jsonl");
            List<String> lines = journal.lines().toList();
            assertEquals("session-start", Json.parseObject(lines.get(0)).get("type"));
            assertEquals("note", Json.parseObject(lines.get(lines.size() - 1)).get("type"));
            McpError unknown = assertThrows(McpError.class, () -> fx.client.readResource(
                    new McpSchema.ReadResourceRequest("skillroy://run/nothing/run.jsonl")));
            assertTrue(unknown.getMessage().toLowerCase().contains("not found"), unknown.getMessage());
        }
    }

    /** The {@code primitive} enum of the base verb {@code call} in a scenario schema. */
    private static List<?> callable(Map<String, Object> schema) {
        Map<?, ?> defs = (Map<?, ?>) schema.get("$defs");
        for (Object alternative : (List<?>) ((Map<?, ?>) defs.get("step")).get("oneOf")) {
            Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) alternative).get("properties");
            if (properties.get("call") instanceof Map<?, ?> call) {
                Map<?, ?> primitive = (Map<?, ?>) ((Map<?, ?>) call.get("properties")).get("primitive");
                return (List<?>) primitive.get("enum");
            }
        }
        throw new AssertionError("no call verb in the schema");
    }

    /**
     * Decision D96: the scenario schema the server serves lets the base verb {@code call} name only what this server
     * offers a scenario run: never a destructive primitive, the agent's own, {@code data.query} or
     * {@code web.evaluate}.
     */
    @Test
    void theScenarioSchemasCallNamesOnlyWhatThisServerOffers() throws Exception {
        try (McpFixture fx = new McpFixture(dir.resolve("mutating")).startMutating()) {
            List<?> callable = callable(Json.parseObject(read(fx, "skillroy://schema/scenario")));
            assertTrue(callable.containsAll(List.of("desk.input", "desk.lend", "desk.state", "swing.click",
                    "trace.mark")), callable.toString());
            for (String never : List.of("desk.wipe", "agent.disable", "agent.info", "data.query", "web.evaluate",
                    "web.waitFlag", "evt.query", "data.sources")) {
                assertFalse(callable.contains(never), never + " in " + callable);
            }
        }
        try (McpFixture fx = new McpFixture(dir.resolve("read-only")).startReadOnly()) {
            List<?> callable = callable(Json.parseObject(read(fx, "skillroy://schema/scenario")));
            assertTrue(callable.contains("desk.state"), callable.toString());
            assertFalse(callable.contains("desk.input") || callable.contains("swing.click"), "read-only: the reads");
        }
    }

    @Test
    void aResourceReadThatFindsTheAgentLostEndsTheServer() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            fx.desk.agent.loseOn("app.state");
            assertThrows(McpError.class, () -> read(fx, "skillroy://state/current"));
            assertEquals(SkillroyMcpServer.EXIT_AGENT_LOST, fx.server.awaitExit(McpFixture.WAIT).orElse(-1));
        }
    }
}
