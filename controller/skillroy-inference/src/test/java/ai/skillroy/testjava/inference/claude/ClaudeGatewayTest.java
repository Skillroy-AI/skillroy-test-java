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

package ai.skillroy.testjava.inference.claude;

import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.claude.FakeAnthropic.Answer;
import ai.skillroy.testjava.inference.claude.FakeAnthropic.Seen;
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ModelResponse.StopReason;
import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 2: the Claude gateway against a loopback fake of the provider's HTTP API. The request
 * body of each mapping row of 4.1, the response mapping, each error status to its kind, the deadline and the
 * gateway's own retries, the batch endpoints with results out of order, {@code count_tokens}, the workspace header;
 * the key never in a message or an output. No test reaches the network.
 */
class ClaudeGatewayTest {

    /** A dummy key: never a real one; it must appear in no message. */
    static final String KEY = "sk-test-dummy-0451-not-a-real-key";
    static final String WORKSPACE = "wrkspc_test_0451";

    private FakeAnthropic fake;
    private final List<Long> waits = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        fake = new FakeAnthropic();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private ClaudeGateway gateway(boolean fallbacks, boolean images, long callMs, String workspace) {
        Map<ModelClass, String> models = new EnumMap<>(ModelClass.class);
        models.put(ModelClass.OPUS_CLASS, "claude-opus-5-5");
        models.put(ModelClass.SONNET_CLASS, "claude-sonnet-5-5");
        models.put(ModelClass.HAIKU_CLASS, "claude-haiku-4-5");
        ClaudeGateway.Config config = new ClaudeGateway.Config(models, fallbacks, images, callMs,
                PriceTable.builtIn());
        return new ClaudeGateway(ClaudeGateway.client(KEY, fake.url(), workspace, callMs), config,
                java.util.Arrays.asList(KEY, workspace), Clock.systemUTC(), ms -> waits.add(ms));
    }

    private ClaudeGateway gateway() {
        return gateway(true, false, 10_000, null);
    }

    private static Map<String, Object> strictSchema() {
        return Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")),
                "required", List.of("answer"), "additionalProperties", false);
    }

    private static ModelRequest.Builder request() {
        return ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW).maxTokens(2048)
                .system(Content.Text.cached("the catalogue"))
                .system(Content.Text.of("the instructions"))
                .message(Message.user("the question"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object json, Object... path) {
        Object current = json;
        for (Object step : path) {
            current = step instanceof Integer i ? ((List<?>) current).get(i) : ((Map<?, ?>) current).get(step);
        }
        return (T) current;
    }

    // ------------------------------------------------------------------------------------------------ requests

    @Test
    void theRequestBodyCarriesEveryMappingRow() throws Exception {
        try (ClaudeGateway g = gateway(true, false, 10_000, WORKSPACE)) {
            ModelRequest r = request()
                    .message(Message.assistant(List.of(new Content.Opaque("claude", "thinking",
                                    Map.of("type", "thinking", "thinking", "", "signature", "sig-77")),
                            new Content.ToolUse("toolu_1", "lending_sign_on", Map.of("staff", "S01")))))
                    .message(Message.user(new Content.ToolResult("toolu_1", Map.of("status", "PASS"), false)))
                    .tool(new ToolSpec("lending_sign_on", "signs on", strictSchema(), false, false))
                    .tool(new ToolSpec("lending_free", "free-form", Map.of("type", "object"), false, false))
                    .outputSchema(strictSchema())
                    .metadata("slot", "item").build();
            fake.answer(Answer.json(FakeAnthropic.message("end_turn",
                    "[{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\\\"x\\\"}\"}]", "{}")));
            assertEquals(Map.of("answer", "x"), g.complete(r).structured());
        }
        Seen seen = fake.last();
        assertEquals("POST", seen.method());
        assertEquals("/v1/messages", seen.path());
        assertEquals(KEY, seen.header("x-api-key"), "the key goes in its header");
        assertEquals(WORKSPACE, seen.header("anthropic-workspace-id"));
        assertEquals("server-side-fallback-2026-07-01", seen.header("anthropic-beta"));
        Map<String, Object> body = seen.json();
        assertEquals("claude-sonnet-5-5", body.get("model"));
        assertEquals(2048L, body.get("max_tokens"));
        assertEquals("default", body.get("fallbacks"));
        assertFalse(body.containsKey("thinking"), "thinking is left at the model's default");
        assertFalse(body.containsKey("metadata"), "the metadata is never sent");
        assertEquals(Map.of("type", "ephemeral"), at(body, "system", 0, "cache_control"), "SHORT: the default TTL");
        assertNull(at(body, "system", 1, "cache_control"), "only the last part marked as a breakpoint");
        assertEquals("the instructions", at(body, "system", 1, "text"));
        assertEquals("assistant", at(body, "messages", 1, "role"));
        assertEquals(Map.of("type", "thinking", "thinking", "", "signature", "sig-77"),
                at(body, "messages", 1, "content", 0), "an opaque block goes back unchanged");
        assertEquals("tool_use", at(body, "messages", 1, "content", 1, "type"));
        assertEquals(Map.of("staff", "S01"), at(body, "messages", 1, "content", 1, "input"));
        assertEquals("tool_result", at(body, "messages", 2, "content", 0, "type"));
        assertEquals("{\"status\":\"PASS\"}", at(body, "messages", 2, "content", 0, "content"),
                "JSON-like content as JSON text");
        assertEquals(true, at(body, "tools", 0, "strict"));
        assertEquals(false, at(body, "tools", 0, "input_schema", "additionalProperties"));
        assertEquals(false, at(body, "tools", 1, "strict"), "a free-form schema cannot be strict");
        assertEquals(Map.of("type", "auto"), body.get("tool_choice"));
        assertEquals("low", at(body, "output_config", "effort"));
        assertEquals("json_schema", at(body, "output_config", "format", "type"));
        assertEquals(strictSchema(), at(body, "output_config", "format", "schema"), "the project's own schema");
    }

    @Test
    void theCacheHintSetsTheTimeToLive() throws Exception {
        try (ClaudeGateway g = gateway()) {
            g.complete(request().cache(CacheHint.LONG).build());
            assertEquals(Map.of("type", "ephemeral", "ttl", "1h"), at(fake.last().json(), "system", 0,
                    "cache_control"));
            g.complete(request().cache(CacheHint.NONE).build());
            assertFalse(fake.last().body().contains("cache_control"));
            g.complete(ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).message(Message.user("q")).build());
            assertFalse(fake.last().json().containsKey("system"), "no system part, no system field");
        }
    }

    @Test
    void noFallbackWhenItIsOff() throws Exception {
        try (ClaudeGateway g = gateway(false, false, 10_000, null)) {
            g.complete(request().build());
        }
        assertFalse(fake.last().json().containsKey("fallbacks"));
        assertNull(fake.last().header("anthropic-beta"));
        assertNull(fake.last().header("anthropic-workspace-id"), "no workspace id, no header");
    }

    /**
     * 4.1's {@code Effort} row as verified live (BS13): the Haiku class's model refuses the effort parameter, so its
     * request sends {@code output_config} with the format and without the effort, and none at all without a schema;
     * the token count goes through the same body; the Sonnet class still sends both; the effort stays part of the
     * request's fingerprint.
     */
    @Test
    void theHaikuClassSendsTheFormatWithoutTheEffort() throws Exception {
        ModelRequest haiku = ModelRequest.builder("triage", ModelClass.HAIKU_CLASS).effort(Effort.LOW)
                .message(Message.user("label the run")).outputSchema(strictSchema()).build();
        String answer = "[{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\\\"x\\\"}\"}]";
        try (ClaudeGateway g = gateway()) {
            fake.answer(Answer.json(FakeAnthropic.message("end_turn", answer, "{}")));
            assertEquals(Map.of("answer", "x"), g.complete(haiku).structured());
            Map<String, Object> body = fake.last().json();
            assertEquals("claude-haiku-4-5", body.get("model"));
            Map<String, Object> output = at(body, "output_config");
            assertFalse(output.containsKey("effort"), "no effort for the Haiku class: " + output);
            assertEquals("json_schema", at(output, "format", "type"));
            assertEquals(strictSchema(), at(output, "format", "schema"), "the format stays");

            fake.answer(Answer.json("{\"input_tokens\":42}"));
            assertEquals(42, g.countTokens(haiku));
            assertEquals("/v1/messages/count_tokens", fake.last().path());
            Map<String, Object> counted = at(fake.last().json(), "output_config");
            assertFalse(counted.containsKey("effort"), "the token count's body follows: " + counted);
            assertEquals("json_schema", at(counted, "format", "type"));

            g.complete(ModelRequest.builder("triage", ModelClass.HAIKU_CLASS).message(Message.user("q")).build());
            assertFalse(fake.last().json().containsKey("output_config"), "no schema and no effort: no output_config");

            fake.answer(Answer.json(FakeAnthropic.message("end_turn", answer, "{}")));
            g.complete(request().outputSchema(strictSchema()).build());
            assertEquals("low", at(fake.last().json(), "output_config", "effort"), "the Sonnet class keeps its effort");
            assertEquals("json_schema", at(fake.last().json(), "output_config", "format", "type"));
        }
        assertNotEquals(haiku.fingerprint(), ModelRequest.builder("triage", ModelClass.HAIKU_CLASS).effort(Effort.HIGH)
                .message(Message.user("label the run")).outputSchema(strictSchema()).build().fingerprint(),
                "the effort is part of the fingerprint although it is not sent");
    }

    /** A strict object schema of these properties: every one required, nothing else allowed. */
    private static Map<String, Object> objectOf(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    /** {@code n} properties that each take a union, by turns a nullable type (a type array) and an {@code anyOf}. */
    private static Map<String, Object> unionProperties(String prefix, int n) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            properties.put(prefix + i, i % 2 == 0 ? Map.of("type", List.of("string", "null"))
                    : Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "string", "pattern", "^\\$"))));
        }
        return properties;
    }

    /**
     * Strict tools against the provider's limit of union-typed parameters (2026-10-08, live: HTTP 400 "too many
     * parameters with union types ... limit: 16 parameters with unions"): tools holding 17 such parameters between
     * them, eight of them nested in an object, go out without a {@code strict} field on any tool and with their schemas
     * as given; with 16 the tools whose schema can be strict are strict, and a free-form one is not.
     */
    @Test
    void moreThanSixteenUnionTypedParametersTurnStrictOffForEveryTool() throws Exception {
        for (int unions : new int[] {17, 16}) {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("item", objectOf(unionProperties("b", unions - 9)));
            nested.put("note", Map.of("type", "string"));
            ModelRequest r = ModelRequest.builder("session", ModelClass.SONNET_CLASS).message(Message.user("lend it"))
                    .tool(new ToolSpec("lending_lend", "lends an item", objectOf(unionProperties("a", 9)), false,
                            false))
                    .tool(new ToolSpec("lending_return", "takes an item back", objectOf(nested), false, false))
                    .tool(new ToolSpec("lending_note", "free-form", Map.of("type", "object"), false, false)).build();
            assertEquals(unions, ClaudeMapping.unionParameters(r.tools()), "counted at any depth");
            try (ClaudeGateway g = gateway()) {
                g.complete(r);
            }
            Map<String, Object> body = fake.last().json();
            List<Map<String, Object>> tools = at(body, "tools");
            assertEquals(3, tools.size());
            assertEquals(List.of("string", "null"), at(body, "tools", 0, "input_schema", "properties", "a0", "type"),
                    "the schema as given");
            assertEquals(Map.of("type", "auto"), body.get("tool_choice"));
            if (unions > ClaudeMapping.MAX_STRICT_UNION_PARAMETERS) {
                for (Map<String, Object> tool : tools) {
                    assertFalse(tool.containsKey("strict"), unions + " union-typed parameters: no strict field on "
                            + tool.get("name"));
                }
            } else {
                assertEquals(true, tools.get(0).get("strict"), unions + " union-typed parameters: strict as before");
                assertEquals(true, tools.get(1).get("strict"));
                assertEquals(false, tools.get(2).get("strict"), "a free-form schema cannot be strict");
            }
        }
    }

    @Test
    void anImageIsRefusedWhileImagesAreOffAndSentAsBase64WhenOn() throws Exception {
        ModelRequest r = ModelRequest.builder("session", ModelClass.SONNET_CLASS)
                .message(Message.user(Content.Text.of("look"), new Content.Image("image/png", new byte[] {1, 2, 3},
                        null))).build();
        try (ClaudeGateway off = gateway(false, false, 10_000, null)) {
            assertFalse(off.capabilities().contains(GatewayCapability.IMAGES));
            assertEquals(Kind.UNSUPPORTED, assertThrows(InferenceException.class, () -> off.complete(r)).kind());
            assertTrue(fake.seen().isEmpty(), "nothing was sent");
        }
        try (ClaudeGateway on = gateway(false, true, 10_000, null)) {
            assertTrue(on.capabilities().contains(GatewayCapability.IMAGES));
            on.complete(r);
        }
        assertEquals(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), at(fake.last().json(), "messages", 0,
                "content", 1, "source", "data"));
        assertEquals("image/png", at(fake.last().json(), "messages", 0, "content", 1, "source", "media_type"));
    }

    // ------------------------------------------------------------------------------------------------ Phase 7

    /** A tiny PNG's bytes stand-in (the gateway sends them as base64; nothing decodes them here). */
    private static final byte[] PIXELS = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    /**
     * phase7-contracts.md 7.1 (D318), 13.1 item 7: a provider tool of provider {@code claude} becomes the computer
     * toolset entry with its {@code configs} beside the request's own tools, tool choice {@code auto}; the gateway
     * declares {@code PROVIDER_TOOLS}; another provider's entry is {@code INVALID_REQUEST} with nothing sent; the token
     * count's body carries the entry too.
     */
    @Test
    void aProviderToolBecomesTheToolsetEntryWithItsConfigs() throws Exception {
        ModelRequest r = request().tool(new ToolSpec("capture", "a fresh capture", Map.of("type", "object",
                        "properties", Map.of(), "required", List.of(), "additionalProperties", false), true, false))
                .providerTool(ai.skillroy.testjava.inference.spi.ProviderTool.computerToolset(false)).build();
        try (ClaudeGateway g = gateway()) {
            assertTrue(g.capabilities().contains(GatewayCapability.PROVIDER_TOOLS));
            g.complete(r);
            Map<String, Object> body = fake.last().json();
            List<Map<String, Object>> tools = at(body, "tools");
            assertEquals(2, tools.size(), "the request's tool and the toolset: " + tools);
            assertEquals("capture", at(body, "tools", 0, "name"));
            assertEquals("computer_toolset_20260801", at(body, "tools", 1, "type"));
            assertEquals(Map.of("enabled", false), at(body, "tools", 1, "configs", "zoom"), "zoom off by default");
            assertEquals(Map.of("type", "auto"), body.get("tool_choice"));
            assertEquals("default", body.get("fallbacks"), "the fallback header as today (7.1)");

            fake.answer(Answer.json("{\"input_tokens\":5000}"));
            assertEquals(5000, g.countTokens(r));
            assertEquals("computer_toolset_20260801", at(fake.last().json(), "tools", 1, "type"),
                    "the token count's body carries the toolset");

            int before = fake.seen().size();
            ModelRequest other = request().providerTool(new ai.skillroy.testjava.inference.spi.ProviderTool(
                    "another", "browser_20260801", Map.of())).build();
            assertEquals(Kind.INVALID_REQUEST, assertThrows(InferenceException.class, () -> g.complete(other))
                    .kind());
            assertEquals(before, fake.seen().size(), "nothing was sent");
        }
        ModelRequest toolsetOnly = ModelRequest.builder("vision", ModelClass.SONNET_CLASS).message(Message.user("go"))
                .providerTool(ai.skillroy.testjava.inference.spi.ProviderTool.computerToolset(true)).build();
        try (ClaudeGateway g = gateway()) {
            g.complete(toolsetOnly);
        }
        assertEquals(Map.of("type", "auto"), fake.last().json().get("tool_choice"), "a toolset alone has tools too");
        assertEquals(Map.of("enabled", true), at(fake.last().json(), "tools", 0, "configs", "zoom"));
    }

    /**
     * phase7-contracts.md 7.2 (D319, D320), 13.1 item 7: {@code toolset_name} is read back from a {@code tool_use}
     * block and sent on the echoed tool use and on the tool result; a parts-list result goes out as content blocks,
     * the image as base64 while images are on and refused ({@code UNSUPPORTED}, nothing sent) while they are off.
     */
    @Test
    void theToolsetNameTravelsBothWaysAndAPartsResultIsContentBlocks() throws Exception {
        fake.answer(Answer.json(FakeAnthropic.message("tool_use", "[{\"type\":\"tool_use\",\"id\":\"toolu_3\","
                + "\"name\":\"left_click\",\"input\":{\"coordinate\":[412,288]},\"toolset_name\":\"computer\"}]",
                "{}")));
        Content.ToolUse use;
        try (ClaudeGateway g = gateway(false, true, 10_000, null)) {
            ModelResponse response = g.complete(request().build());
            use = response.toolUses().get(0);
            assertEquals("computer", use.toolsetName(), "read back from the block");
            assertEquals("left_click", use.name());
            Content.ToolResult result = new Content.ToolResult("toolu_3", List.of(Content.Text.of("clicked"),
                    new Content.Image("image/png", PIXELS, null)), false, "computer");
            g.complete(request().message(Message.assistant(List.of(use))).message(Message.user(result)).build());
        }
        Map<String, Object> body = fake.last().json();
        assertEquals("computer", at(body, "messages", 1, "content", 0, "toolset_name"), "on the echoed tool use");
        assertEquals("computer", at(body, "messages", 2, "content", 0, "toolset_name"), "on the tool result");
        assertEquals("text", at(body, "messages", 2, "content", 0, "content", 0, "type"));
        assertEquals("clicked", at(body, "messages", 2, "content", 0, "content", 0, "text"));
        assertEquals("image", at(body, "messages", 2, "content", 0, "content", 1, "type"));
        assertEquals(Base64.getEncoder().encodeToString(PIXELS), at(body, "messages", 2, "content", 0, "content", 1,
                "source", "data"));
        assertFalse(at(body, "messages", 0, "content", 0) instanceof Map<?, ?> m && m.containsKey("toolset_name"),
                "a plain part carries none");

        Content.ToolResult imageResult = new Content.ToolResult("toolu_3", List.of(new Content.Image("image/png",
                PIXELS, null)), false, "computer");
        try (ClaudeGateway off = gateway(false, false, 10_000, null)) {
            int before = fake.seen().size();
            assertEquals(Kind.UNSUPPORTED, assertThrows(InferenceException.class, () -> off.complete(request()
                    .message(Message.assistant(List.of(use))).message(Message.user(imageResult)).build())).kind());
            assertEquals(before, fake.seen().size(), "nothing was sent");
        }
    }

    /** phase7-contracts.md 7.2 (D320): an error result carries text only, refused at construction with an image. */
    @Test
    void anErrorResultWithAnImageIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Content.ToolResult("t", List.of(Content.Text.of("x"),
                new Content.Image("image/png", PIXELS, null)), true, "computer"));
        assertThrows(IllegalArgumentException.class, () -> new Content.ToolResult("t", List.of(Content.Text.of("x"),
                new Content.ToolUse("u", "n", Map.of())), false));
        Content.ToolResult text = new Content.ToolResult("t", List.of(Content.Text.of("x")), true, "computer");
        assertEquals(List.of(Content.Text.of("x")), text.parts(), "an error result with text parts is fine");
        assertNull(new Content.ToolResult("t", List.of("a", "b"), false).parts(), "a JSON list is not a parts list");
    }

    /**
     * phase7-contracts.md 7.4 (D323): {@code CacheHint.AUTO} keeps the system breakpoint at the default time to live
     * and adds the request's top-level {@code cache_control}; {@code SHORT} has no top-level marker.
     */
    @Test
    void theAutomaticCacheHintAddsTheTopLevelMarker() throws Exception {
        try (ClaudeGateway g = gateway()) {
            g.complete(request().cache(CacheHint.AUTO).build());
            Map<String, Object> body = fake.last().json();
            assertEquals(Map.of("type", "ephemeral"), body.get("cache_control"), "the top-level marker");
            assertEquals(Map.of("type", "ephemeral"), at(body, "system", 0, "cache_control"), "the breakpoint stays");
            g.complete(request().build());
            assertFalse(fake.last().json().containsKey("cache_control"), "SHORT: no top-level marker");
        }
    }

    // ------------------------------------------------------------------------------------------------ responses

    @Test
    void theResponseIsReadTolerantly() throws Exception {
        fake.answer(Answer.json(FakeAnthropic.message("claude-sonnet-5", "tool_use", "["
                + "{\"type\":\"fallback\",\"from\":{\"model\":\"claude-sonnet-5-5\"},\"to\":{\"model\":\"claude-sonnet-5\"}},"
                + "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},"
                + "{\"type\":\"text\",\"text\":\"Signing on.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"lending_sign_on\",\"input\":{\"staff\":\"S01\"}},"
                + "{\"type\":\"a_block_from_the_future\",\"x\":1}]",
                "{\"cache_read_input_tokens\":50,\"cache_creation_input_tokens\":70,\"cache_creation\":"
                        + "{\"ephemeral_5m_input_tokens\":30,\"ephemeral_1h_input_tokens\":40},\"iterations\":[]}",
                null)));
        ModelResponse r;
        try (ClaudeGateway g = gateway()) {
            r = g.complete(request().build());
        }
        assertEquals(StopReason.TOOL_USE, r.stop());
        assertEquals(5, r.parts().size());
        assertInstanceOf(Content.Opaque.class, r.parts().get(0));
        assertEquals("fallback", ((Content.Opaque) r.parts().get(0)).type());
        assertEquals("thinking", ((Content.Opaque) r.parts().get(1)).type());
        assertEquals("Signing on.", r.text());
        assertEquals("lending_sign_on", r.toolUses().get(0).name());
        assertEquals("a_block_from_the_future", ((Content.Opaque) r.parts().get(4)).type());
        Usage u = r.usage();
        assertEquals("claude", u.provider());
        assertEquals("claude-sonnet-5-5", u.requestedModel());
        assertEquals("claude-sonnet-5", u.model(), "a fallback answered");
        assertEquals("msg_fake_1", u.requestId());
        assertEquals(100, u.inputTokens());
        assertEquals(20, u.outputTokens());
        assertEquals(50, u.cacheReadTokens());
        assertEquals(30, u.cacheWrite5mTokens());
        assertEquals(40, u.cacheWrite1hTokens());
        assertEquals(70, u.cacheWriteTokens());
        // claude-sonnet-5: 100 x 2 + 20 x 10 + 50 x 0.20 + 30 x 2 x 1.25 + 40 x 2 x 2 = 645 per million.
        assertEquals("0.000645", u.costUsd());
        assertEquals("fallback: answered by claude-sonnet-5", u.note());
        assertFalse(u.batch());
    }

    /**
     * A dated snapshot id answering for the id asked for is the same model (BS13 live: a request for
     * {@code claude-haiku-4-5} is answered by {@code claude-haiku-4-5-20251001}): the usage names the dated id, with no
     * fallback note and no unknown-model note, at Haiku's price; another id is still a fallback.
     */
    @Test
    void aDatedSnapshotIdIsTheModelAskedFor() throws Exception {
        fake.answer(Answer.json(FakeAnthropic.message("claude-haiku-4-5-20251001", "end_turn",
                "[{\"type\":\"text\",\"text\":\"a label\"}]", "{}", null)));
        Usage u;
        try (ClaudeGateway g = gateway()) {
            u = g.complete(ModelRequest.builder("triage", ModelClass.HAIKU_CLASS).message(Message.user("label it"))
                    .build()).usage();
        }
        assertEquals("claude-haiku-4-5", u.requestedModel());
        assertEquals("claude-haiku-4-5-20251001", u.model(), "the id the response names");
        assertNull(u.note(), "neither a fallback nor an unknown model");
        // claude-haiku-4-5: 100 x 1.00 + 20 x 5.00 = 200 per million.
        assertEquals("0.000200", u.costUsd());
        assertTrue(ClaudeMapping.sameModel("claude-haiku-4-5", "claude-haiku-4-5-20251001"));
        assertTrue(ClaudeMapping.sameModel("claude-sonnet-5-5", "claude-sonnet-5-5"));
        assertFalse(ClaudeMapping.sameModel("claude-haiku-4-5", "claude-haiku-4-5-2025100"), "seven digits");
        assertFalse(ClaudeMapping.sameModel("claude-haiku-4-5", "claude-haiku-4-5-202510011"), "nine digits");
        assertFalse(ClaudeMapping.sameModel("claude-sonnet-5-5", "claude-sonnet-5"), "Sonnet 5.5's fallback target");
        assertFalse(ClaudeMapping.sameModel("claude-opus-5", "claude-opus-5-5"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"end_turn, END_TURN", "stop_sequence, END_TURN", "max_tokens, MAX_TOKENS", "tool_use, TOOL_USE",
            "pause_turn, END_TURN"})
    void eachStopReason(String stop, StopReason expected) throws Exception {
        fake.answer(Answer.json(FakeAnthropic.message(stop, "[{\"type\":\"text\",\"text\":\"x\"}]", "{}")));
        try (ClaudeGateway g = gateway()) {
            assertEquals(expected, g.complete(request().build()).stop());
        }
    }

    @Test
    void aRefusalIsAnAnswerWithItsCategoryAsAString() throws Exception {
        fake.answer(Answer.json(FakeAnthropic.message("claude-sonnet-5-5", "refusal", "[]", "{}",
                "{\"type\":\"refusal\",\"category\":\"a_category_newer_than_the_sdk\",\"explanation\":\"x\"}")));
        ModelResponse r;
        try (ClaudeGateway g = gateway()) {
            r = g.complete(request().outputSchema(strictSchema()).build());
        }
        assertEquals(StopReason.REFUSAL, r.stop());
        assertNull(r.structured());
        assertEquals("refusal: a_category_newer_than_the_sdk", r.usage().note());
    }

    @Test
    void theStructuredAnswerIsParsedFromTheText() throws Exception {
        fake.answer(Answer.json(FakeAnthropic.message("end_turn",
                        "[{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\\\"DRL-01\\\"}\"}]", "{}")),
                Answer.json(FakeAnthropic.message("end_turn", "[{\"type\":\"text\",\"text\":\"not json\"}]", "{}")),
                Answer.json(FakeAnthropic.message("max_tokens", "[{\"type\":\"text\",\"text\":\"{\\\"ans\"}]", "{}")));
        try (ClaudeGateway g = gateway()) {
            ModelRequest r = request().outputSchema(strictSchema()).build();
            assertEquals(Map.of("answer", "DRL-01"), g.complete(r).structured());
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(r));
            assertEquals(Kind.PROVIDER, e.kind());
            assertEquals(100, e.usage().inputTokens(), "a billed call carries its usage");
            ModelResponse cut = g.complete(r);
            assertEquals(StopReason.MAX_TOKENS, cut.stop());
            assertNull(cut.structured(), "a cut answer has no structured part");
        }
    }

    // ------------------------------------------------------------------------------------------------ errors

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({"401, authentication_error, AUTH", "403, permission_error, AUTH", "402, billing_error, BILLING",
            "400, invalid_request_error, INVALID_REQUEST", "404, not_found_error, INVALID_REQUEST",
            "413, request_too_large, INVALID_REQUEST", "422, invalid_request_error, INVALID_REQUEST",
            "500, api_error, PROVIDER", "502, api_error, PROVIDER"})
    void anErrorStatusIsItsKindAndIsNotRetried(int status, String type, Kind kind) {
        fake.otherwise(Answer.error(status, type, "the provider says no", Map.of("request-id", "req_h1")));
        try (ClaudeGateway g = gateway(true, false, 10_000, WORKSPACE)) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
            assertEquals(kind, e.kind());
            assertEquals("req_h1", e.requestId());
            assertTrue(e.getMessage().startsWith("HTTP " + status + " " + type + ": the provider says no"),
                    e.getMessage());
            assertFalse(e.getMessage().contains(KEY));
            assertFalse(e.getMessage().contains(WORKSPACE));
        }
        assertEquals(1, fake.seen().size(), "not retried");
        assertTrue(waits.isEmpty());
    }

    @Test
    void aWorkspaceRefusalIsAuthWithThePathToTheSetting() {
        fake.otherwise(Answer.error(400, "invalid_request_error", "This API key is not scoped to a workspace, so"
                + " this request must include the anthropic-workspace-id header", Map.of()));
        try (ClaudeGateway g = gateway()) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
            assertEquals(Kind.AUTH, e.kind());
            assertTrue(e.getMessage().contains("ANTHROPIC_WORKSPACE_ID"), e.getMessage());
        }
    }

    @Test
    void aProviderMessageThatEchoedTheKeyIsScrubbed() {
        fake.otherwise(Answer.error(401, "authentication_error", "invalid x-api-key " + KEY, Map.of()));
        try (ClaudeGateway g = gateway()) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
            assertFalse(e.getMessage().contains(KEY), e.getMessage());
            assertTrue(e.getMessage().contains("<redacted>"));
        }
    }

    /**
     * Phase 6 security fix S54: the provider's message is scrubbed of the key before it is cut, so that a key at offset
     * 290 (cut at 300) leaves no part of itself, in a call's error and in a batch result's.
     */
    @Test
    void aKeyTheCutWouldSplitIsScrubbedFirst() throws Exception {
        String echoed = "x".repeat(290) + KEY + " and more";
        fake.otherwise(Answer.error(400, "invalid_request_error", echoed, Map.of()));
        try (ClaudeGateway g = gateway()) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
            assertFalse(e.getMessage().contains(KEY.substring(0, 6)), e.getMessage());
        }
        String created = "{\"id\":\"msgbatch_9\",\"type\":\"message_batch\",\"processing_status\":\"ended\","
                + "\"request_counts\":{\"processing\":0,\"succeeded\":0,\"errored\":1,\"canceled\":0,\"expired\":0},"
                + "\"created_at\":\"2026-10-08T00:00:00Z\",\"expires_at\":\"2026-10-09T00:00:00Z\",\"ended_at\":null,"
                + "\"cancel_initiated_at\":null,\"archived_at\":null,\"results_url\":null}";
        fake.answer(Answer.json(created), Answer.json("{\"custom_id\":\"0\",\"result\":{\"type\":\"errored\","
                + "\"error\":{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\""
                + echoed + "\"}}}}\n"));
        try (ClaudeGateway g = gateway()) {
            BatchHandle h = g.submitBatch(List.of(request().build()));
            List<BatchResult> out = g.collectBatch(h);
            String message = out.get(0).error().getMessage();
            assertTrue(message.startsWith("batch request 0: invalid_request_error: xxx"), message);
            assertFalse(message.contains(KEY.substring(0, 6)), message);
        }
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({"429, rate_limit_error, RATE_LIMIT", "529, overloaded_error, OVERLOADED",
            "500, overloaded_error, OVERLOADED"})
    void aRetryableErrorIsTriedTwiceMoreThenReported(int status, String type, Kind kind) {
        fake.otherwise(Answer.error(status, type, "busy", Map.of()));
        try (ClaudeGateway g = gateway()) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
            assertEquals(kind, e.kind());
            assertTrue(e.retryable());
        }
        assertEquals(3, fake.seen().size(), "one call and two retries");
        assertEquals(List.of(500L, 1000L), waits, "the gateway's own back-off");
    }

    @Test
    void retryAfterIsHonouredWhileItFitsTheDeadline() throws Exception {
        fake.answer(Answer.error(429, "rate_limit_error", "slow down", Map.of("retry-after", "2")),
                Answer.json(FakeAnthropic.message("end_turn", "[{\"type\":\"text\",\"text\":\"OK\"}]", "{}")));
        try (ClaudeGateway g = gateway()) {
            assertEquals("OK", g.complete(request().build()).text());
        }
        assertEquals(List.of(2000L), waits);
        assertEquals(2, fake.seen().size());
        waits.clear();
        fake.answer(Answer.error(429, "rate_limit_error", "slow down", Map.of("retry-after", "3600")));
        try (ClaudeGateway g = gateway()) {
            assertEquals(Kind.RATE_LIMIT, assertThrows(InferenceException.class,
                    () -> g.complete(request().build())).kind(), "a wait past the deadline is not taken");
        }
        assertTrue(waits.isEmpty());
        assertEquals(3, fake.seen().size());
    }

    /**
     * A {@code retry-after} that is not a finite number of at least 0 is no wait at all (the gateway's back-off
     * applies), and one too long for the deadline (1e19 overflowed it before) is not waited for.
     */
    @ParameterizedTest(name = "{0}: {1} -> {2} calls")
    @CsvSource({"retry-after, Infinity, 3", "retry-after, NaN, 3", "retry-after, -5, 3", "retry-after-ms, -5, 3",
            "retry-after-ms, Infinity, 3", "retry-after-ms, 1e19, 1", "retry-after, 1e19, 1",
            "retry-after-ms, 10001, 1"})
    void aRetryAfterThatIsNotAFiniteFittingWaitIsNeverSlept(String header, String value, int calls) {
        fake.otherwise(Answer.error(429, "rate_limit_error", "slow down", Map.of(header, value)));
        try (ClaudeGateway g = gateway(true, false, 10_000, null)) {
            assertEquals(Kind.RATE_LIMIT, assertThrows(InferenceException.class,
                    () -> g.complete(request().build())).kind());
        }
        assertEquals(calls, fake.seen().size());
        assertEquals(calls == 3 ? List.of(500L, 1000L) : List.of(), waits,
                "the back-off for a value that is no wait, nothing for one that does not fit the deadline");
        assertTrue(waits.stream().allMatch(w -> w >= 0 && w < 10_000), "never a negative or an endless sleep");
    }

    @Test
    void theDeadlineBoundsTheCallWhateverTheProviderDoes() {
        fake.otherwise(new Answer(200, Map.of(), FakeAnthropic.message("end_turn", "[]", "{}"), 3000));
        try (ClaudeGateway g = gateway()) {
            // Only the call is timed: building and closing the client is not the deadline's business.
            long started = System.nanoTime();
            InferenceException e = assertThrows(InferenceException.class,
                    () -> g.complete(request().build(), Duration.ofMillis(400)));
            long ms = (System.nanoTime() - started) / 1_000_000;
            assertEquals(Kind.TIMEOUT, e.kind());
            assertTrue(ms < 2500, "returned after " + ms + " ms");
        }
        try (ClaudeGateway g = gateway(true, false, 400, null)) {
            assertEquals(Kind.TIMEOUT, assertThrows(InferenceException.class,
                    () -> g.complete(request().build())).kind(), "the settings' callMs alone");
        }
    }

    @Test
    void aRefusedConnectionIsATransportFailureRetried() throws Exception {
        String closed = fake.url();
        fake.close();
        Map<ModelClass, String> models = new EnumMap<>(ModelClass.class);
        models.put(ModelClass.SONNET_CLASS, "claude-sonnet-5-5");
        try (ClaudeGateway g = new ClaudeGateway(ClaudeGateway.client(KEY, closed, null, 5000),
                new ClaudeGateway.Config(models, false, false, 5000, PriceTable.builtIn()), List.of(KEY),
                Clock.systemUTC(), ms -> waits.add(ms))) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
            assertEquals(Kind.TRANSPORT, e.kind());
            assertFalse(e.getMessage().contains(KEY));
        }
        assertEquals(List.of(500L, 1000L), waits);
        fake = new FakeAnthropic();
    }

    @Test
    void aClosedGatewayRefusesCalls() {
        ClaudeGateway g = gateway();
        g.close();
        g.close();
        assertEquals(Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                () -> g.complete(request().build())).kind());
    }

    // ------------------------------------------------------------------------------------------------ batches

    @Test
    void batchesAreSubmittedPolledAndCollectedByCustomId() throws Exception {
        String created = "{\"id\":\"msgbatch_7\",\"type\":\"message_batch\",\"processing_status\":\"in_progress\","
                + "\"request_counts\":{\"processing\":4,\"succeeded\":0,\"errored\":0,\"canceled\":0,\"expired\":0},"
                + "\"created_at\":\"2026-10-08T00:00:00Z\",\"expires_at\":\"2026-10-09T00:00:00Z\",\"ended_at\":null,"
                + "\"cancel_initiated_at\":null,\"archived_at\":null,\"results_url\":null}";
        String ended = created.replace("\"in_progress\"", "\"ended\"")
                .replace("\"processing\":4,\"succeeded\":0,\"errored\":0", "\"processing\":0,\"succeeded\":2,"
                        + "\"errored\":1").replace("\"expired\":0", "\"expired\":1");
        String results = String.join("\n",
                "{\"custom_id\":\"2\",\"result\":{\"type\":\"succeeded\",\"message\":" + FakeAnthropic.message(
                        "end_turn", "[{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\\\"two\\\"}\"}]", "{}") + "}}",
                "{\"custom_id\":\"0\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":"
                        + "{\"type\":\"invalid_request_error\",\"message\":\"bad request 0\"}}}}",
                "{\"custom_id\":\"3\",\"result\":{\"type\":\"expired\"}}",
                "{\"custom_id\":\"1\",\"result\":{\"type\":\"succeeded\",\"message\":" + FakeAnthropic.message(
                        "end_turn", "[{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\\\"one\\\"}\"}]", "{}") + "}}",
                "{\"custom_id\":\"99\",\"result\":{\"type\":\"succeeded\"}}") + "\n";
        fake.answer(Answer.json(created), Answer.json(created), Answer.json(ended), Answer.json(results));
        List<ModelRequest> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(request().cache(CacheHint.LONG).outputSchema(strictSchema()).build());
        }
        try (ClaudeGateway g = gateway(true, false, 10_000, null)) {
            BatchHandle h = g.submitBatch(requests);
            assertEquals("msgbatch_7", h.id());
            assertEquals(4, h.requests());
            Seen submit = fake.seen().get(0);
            assertEquals("/v1/messages/batches", submit.path());
            assertNull(submit.header("anthropic-beta"), "no fallback beta in a batch");
            Map<String, Object> body = submit.json();
            for (int i = 0; i < 4; i++) {
                assertEquals(Integer.toString(i), at(body, "requests", i, "custom_id"));
                Map<String, Object> params = at(body, "requests", i, "params");
                assertFalse(params.containsKey("fallbacks"), "never fallbacks in a batch");
                assertEquals(Map.of("type", "ephemeral", "ttl", "1h"), at(params, "system", 0, "cache_control"));
            }
            BatchStatus running = g.batchStatus(h);
            assertEquals(BatchStatus.State.IN_PROGRESS, running.state());
            assertEquals(4, running.processing());
            assertEquals("/v1/messages/batches/msgbatch_7", fake.last().path());
            BatchStatus done = g.batchStatus(h);
            assertEquals(new BatchStatus(BatchStatus.State.ENDED, 2, 1, 1, 0, 0), done);
            List<BatchResult> out = g.collectBatch(h);
            assertEquals("/v1/messages/batches/msgbatch_7/results", fake.last().path());
            assertEquals(4, out.size());
            for (int i = 0; i < 4; i++) {
                assertEquals(i, out.get(i).index(), "in request order");
            }
            assertEquals(Kind.INVALID_REQUEST, out.get(0).error().kind());
            assertTrue(out.get(0).error().getMessage().contains("bad request 0"));
            assertEquals(Map.of("answer", "one"), out.get(1).response().structured(), "matched by custom_id");
            assertEquals(Map.of("answer", "two"), out.get(2).response().structured());
            assertEquals(Kind.TIMEOUT, out.get(3).error().kind(), "expired");
            Usage u = out.get(1).response().usage();
            assertTrue(u.batch());
            // 100 x 2 + 20 x 10 = 400 per million, at the batch rate 0.5.
            assertEquals("0.000200", u.costUsd());
            fake.answer(Answer.json(created.replace("\"in_progress\"", "\"canceling\"")));
            g.cancelBatch(h);
            assertEquals("/v1/messages/batches/msgbatch_7/cancel", fake.last().path());
        }
    }

    @Test
    void aMissingResultIsReported() throws Exception {
        String created = "{\"id\":\"msgbatch_8\",\"type\":\"message_batch\",\"processing_status\":\"ended\","
                + "\"request_counts\":{\"processing\":0,\"succeeded\":1,\"errored\":0,\"canceled\":1,\"expired\":0},"
                + "\"created_at\":\"2026-10-08T00:00:00Z\",\"expires_at\":\"2026-10-09T00:00:00Z\",\"ended_at\":null,"
                + "\"cancel_initiated_at\":null,\"archived_at\":null,\"results_url\":null}";
        fake.answer(Answer.json(created), Answer.json("{\"custom_id\":\"1\",\"result\":{\"type\":\"canceled\"}}\n"));
        try (ClaudeGateway g = gateway()) {
            BatchHandle h = g.submitBatch(List.of(request().build(), request().build(), request().build()));
            List<BatchResult> out = g.collectBatch(h);
            assertTrue(out.get(0).error().getMessage().contains("no result"));
            assertTrue(out.get(1).error().getMessage().contains("canceled"));
            assertTrue(out.get(2).error().getMessage().contains("no result"));
        }
    }

    @Test
    void countTokensSendsTheBodyWithoutMaxTokensOrFallbacks() throws Exception {
        fake.answer(Answer.json("{\"input_tokens\":42}"));
        try (ClaudeGateway g = gateway()) {
            assertEquals(42, g.countTokens(request().outputSchema(strictSchema()).build()));
        }
        Seen seen = fake.last();
        assertEquals("/v1/messages/count_tokens", seen.path());
        assertFalse(seen.json().containsKey("max_tokens"));
        assertFalse(seen.json().containsKey("fallbacks"));
        assertNull(seen.header("anthropic-beta"));
        assertEquals("json_schema", at(seen.json(), "output_config", "format", "type"));
    }

    // ------------------------------------------------------------------------------------------------ the key

    private static InferenceSettings settings(String workspaceId) {
        return new InferenceSettings("claude", null, false, workspaceId, null, null, null, null, null, false, false,
                null, false, null);
    }

    @Test
    void theKeyComesFromTheEnvironmentOnlyAndNeverReachesAMessage() throws Exception {
        InferenceException missing = assertThrows(InferenceException.class,
                () -> ClaudeGateway.open(settings(null), name -> null));
        assertEquals(Kind.AUTH, missing.kind());
        assertEquals("the model key is not set (ANTHROPIC_API_KEY)", missing.getMessage());
        Map<String, String> env = Map.of(ClaudeGateway.KEY_VARIABLE, KEY, ClaudeGateway.BASE_URL_VARIABLE, fake.url(),
                ClaudeGateway.WORKSPACE_VARIABLE, "wrkspc_from_env");
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            try (ClaudeGateway g = ClaudeGateway.open(settings(null), env::get)) {
                g.complete(request().build());
                assertFalse(g.toString().contains(KEY));
            }
            assertEquals("wrkspc_from_env", fake.last().header("anthropic-workspace-id"));
            try (ClaudeGateway g = ClaudeGateway.open(settings("wrkspc_from_settings"), env::get)) {
                g.complete(request().build());
            }
            assertEquals("wrkspc_from_settings", fake.last().header("anthropic-workspace-id"), "the settings win");
            assertEquals(KEY, fake.last().header("x-api-key"));
            fake.answer(Answer.error(401, "authentication_error", "invalid x-api-key", Map.of()));
            try (ClaudeGateway g = ClaudeGateway.open(settings(null), env::get)) {
                InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
                assertFalse(e.getMessage().contains(KEY));
            }
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains(KEY), "nothing logged with the key");
        assertEquals("", captured.toString(StandardCharsets.UTF_8), "the SDK's logging is off");
    }

    /**
     * Phase 6 security fix S51: {@code ANTHROPIC_BASE_URL} must be {@code https}, or {@code http} on a loopback host,
     * and carry no user-info; otherwise the gateway refuses to open with {@code AUTH} naming the variable and never
     * its value (before the fix any scheme and host were accepted and the key travelled there).
     */
    @Test
    void aBaseUrlMustBeHttpsOrLoopbackAndCarryNoUserInfo() throws Exception {
        for (String url : List.of("http://proxy.example/", "https://user:pw-0451@api.example/", "ftp://api.example/",
                "api.example:443", "http ://api.example")) {
            Map<String, String> env = Map.of(ClaudeGateway.KEY_VARIABLE, KEY, ClaudeGateway.BASE_URL_VARIABLE, url);
            InferenceException e = assertThrows(InferenceException.class, () -> ClaudeGateway.open(settings(null),
                    env::get), url);
            assertEquals(Kind.AUTH, e.kind(), url);
            assertTrue(e.getMessage().startsWith(ClaudeGateway.BASE_URL_VARIABLE + " "), e.getMessage());
            assertFalse(e.getMessage().contains("api.example") || e.getMessage().contains("proxy.example")
                    || e.getMessage().contains("pw-0451"), e.getMessage());
        }
        for (String url : List.of(fake.url(), "http://localhost:1/", "http://[::1]:1/", "https://api.example/")) {
            try (ClaudeGateway g = ClaudeGateway.open(settings(null), Map.of(ClaudeGateway.KEY_VARIABLE, KEY,
                    ClaudeGateway.BASE_URL_VARIABLE, url)::get)) {
                assertNotNull(g, url);
            }
        }
    }

    /**
     * Phase 6 security fix S52: a configured model id the price table does not know is refused when the gateway opens
     * ({@code INVALID_REQUEST}, naming the id and {@code prices:}); a dated snapshot id of a known model passes.
     */
    @Test
    void aConfiguredModelOutsideThePriceTableIsRefused() throws Exception {
        Map<String, String> env = Map.of(ClaudeGateway.KEY_VARIABLE, KEY, ClaudeGateway.BASE_URL_VARIABLE, fake.url());
        InferenceSettings unknown = new InferenceSettings("claude", null, false, null, Map.of(
                InferenceSettings.MODEL_SONNET, "claude-example-9"), null, null, null, null, false, false, null,
                false, null);
        InferenceException e = assertThrows(InferenceException.class, () -> ClaudeGateway.open(unknown, env::get));
        assertEquals(Kind.INVALID_REQUEST, e.kind());
        assertTrue(e.getMessage().contains("claude-example-9") && e.getMessage().contains("prices:"), e.getMessage());
        InferenceSettings dated = new InferenceSettings("claude", null, false, null, Map.of(
                InferenceSettings.MODEL_HAIKU, "claude-haiku-4-5-20251001"), null, null, null, null, false, false,
                null, false, null);
        try (ClaudeGateway g = ClaudeGateway.open(dated, env::get)) {
            assertNotNull(g);
        }
    }

    /**
     * A key or a workspace id a header cannot carry (a no-break space, a zero-width space, a control character: none
     * of them is stripped) is refused when the gateway opens, with {@code AUTH} naming the variable, never the value;
     * nothing is sent (the HTTP client's own refusal would have quoted the key).
     */
    @ParameterizedTest(name = "suffix U+{0}")
    @CsvSource({"00a0", "200b", "0007"})
    void aKeyOrWorkspaceIdAHeaderCannotCarryIsRefusedNamingTheVariable(String hex) {
        String bad = String.valueOf((char) Integer.parseInt(hex, 16));
        Map<String, String> badKey = Map.of(ClaudeGateway.KEY_VARIABLE, KEY + bad, ClaudeGateway.BASE_URL_VARIABLE,
                fake.url());
        InferenceException key = assertThrows(InferenceException.class,
                () -> ClaudeGateway.open(settings(null), badKey::get));
        assertEquals(Kind.AUTH, key.kind());
        assertTrue(key.getMessage().contains(ClaudeGateway.KEY_VARIABLE), key.getMessage());
        assertFalse(key.getMessage().contains(KEY), key.getMessage());
        Map<String, String> badWorkspace = Map.of(ClaudeGateway.KEY_VARIABLE, KEY, ClaudeGateway.BASE_URL_VARIABLE,
                fake.url(), ClaudeGateway.WORKSPACE_VARIABLE, WORKSPACE + bad);
        InferenceException env = assertThrows(InferenceException.class,
                () -> ClaudeGateway.open(settings(null), badWorkspace::get));
        assertEquals(Kind.AUTH, env.kind());
        assertTrue(env.getMessage().contains(ClaudeGateway.WORKSPACE_VARIABLE), env.getMessage());
        assertFalse(env.getMessage().contains(WORKSPACE), env.getMessage());
        Map<String, String> goodEnv = Map.of(ClaudeGateway.KEY_VARIABLE, KEY, ClaudeGateway.BASE_URL_VARIABLE,
                fake.url());
        InferenceException settings = assertThrows(InferenceException.class,
                () -> ClaudeGateway.open(settings(WORKSPACE + bad), goodEnv::get));
        assertEquals(Kind.AUTH, settings.kind());
        assertTrue(settings.getMessage().contains("workspaceId"), settings.getMessage());
        assertFalse(settings.getMessage().contains(WORKSPACE), settings.getMessage());
        assertTrue(fake.seen().isEmpty(), "nothing was sent");
    }

    /**
     * Any other exception of the SDK or its HTTP client (here the client's own refusal of a header value, which
     * quotes the key) leaves the gateway as {@code PROVIDER} with the known secrets scrubbed and no cause.
     */
    @Test
    void anyOtherRuntimeExceptionOfTheClientIsProviderScrubbedAndWithoutACause() {
        String bad = KEY + " ";
        Map<ModelClass, String> models = new EnumMap<>(ModelClass.class);
        models.put(ModelClass.SONNET_CLASS, "claude-sonnet-5-5");
        try (ClaudeGateway g = new ClaudeGateway(ClaudeGateway.client(bad, fake.url(), null, 5000),
                new ClaudeGateway.Config(models, false, false, 5000, PriceTable.builtIn()), List.of(bad),
                Clock.systemUTC(), ms -> waits.add(ms))) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request().build()));
            assertEquals(Kind.PROVIDER, e.kind());
            assertNull(e.getCause(), "no cause: nothing of the client's exception travels on");
            assertFalse(e.getMessage().contains(KEY), e.getMessage());
            assertTrue(e.getMessage().contains("<redacted>"), "the client's message, scrubbed: " + e.getMessage());
            assertFalse(e.retryable());
        }
        assertTrue(fake.seen().isEmpty(), "the request never left");
        assertTrue(waits.isEmpty(), "not retried");
    }
}
