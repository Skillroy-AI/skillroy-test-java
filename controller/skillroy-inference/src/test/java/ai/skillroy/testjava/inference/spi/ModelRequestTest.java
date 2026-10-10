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

package ai.skillroy.testjava.inference.spi;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 1: the SPI's request and its fingerprint (stable across the key order of maps,
 * independent of {@code maxTokens}, {@code cache} and the metadata, sensitive to a one-character change of any text,
 * an image by its hash, an opaque part not at all), the builder's defaults, the parts and the usage.
 */
class ModelRequestTest {

    private static Map<String, Object> schema(boolean reversed) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (reversed) {
            props.put("b", Map.of("type", "integer"));
            props.put("a", Map.of("type", "string"));
        } else {
            props.put("a", Map.of("type", "string"));
            props.put("b", Map.of("type", "integer"));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        if (reversed) {
            schema.put("additionalProperties", false);
            schema.put("required", List.of("a", "b"));
            schema.put("properties", props);
            schema.put("type", "object");
        } else {
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("a", "b"));
            schema.put("additionalProperties", false);
        }
        return schema;
    }

    private static ModelRequest.Builder base(boolean reversed) {
        return ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW)
                .system(Content.Text.cached("catalogue text"))
                .system(Content.Text.of("instructions"))
                .message(Message.user("Query kind: X"))
                .tool(new ToolSpec("lending_sign_on", "signs on", schema(reversed), false, false))
                .outputSchema(schema(reversed));
    }

    @Test
    void theBuilderHasTheContractDefaults() {
        ModelRequest r = ModelRequest.builder("triage", ModelClass.HAIKU_CLASS).message(Message.user("x")).build();
        assertEquals(Effort.MEDIUM, r.effort());
        assertEquals(4096, r.maxTokens());
        assertEquals(CacheHint.SHORT, r.cache());
        assertTrue(r.tools().isEmpty());
        assertNull(r.outputSchema());
        assertTrue(r.system().isEmpty());
    }

    @Test
    void theFingerprintIsStableAcrossMapOrderAndIgnoresBudgetCacheAndLabels() {
        String f = base(false).build().fingerprint();
        assertTrue(f.matches("[0-9a-f]{64}"), f);
        assertEquals(f, base(true).build().fingerprint(), "map key order does not matter");
        assertEquals(f, base(false).maxTokens(17).cache(CacheHint.NONE).metadata("slot", "item")
                .metadata("scenarioId", "s").build().fingerprint(), "maxTokens, cache and metadata are outside it");
        assertEquals(f, base(false).build().withCache(CacheHint.LONG).fingerprint());
        ModelRequest movedBreakpoint = ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW)
                .system(Content.Text.of("catalogue text")).system(Content.Text.cached("instructions"))
                .message(Message.user("Query kind: X"))
                .tool(new ToolSpec("lending_sign_on", "signs on", schema(false), false, false))
                .outputSchema(schema(false)).build();
        assertEquals(f, movedBreakpoint.fingerprint(), "where the cache breakpoint sits is a cache concern");
    }

    @Test
    void theFingerprintSeesEveryCharacterOfWhatTheModelSees() {
        String f = base(false).build().fingerprint();
        assertNotEquals(f, base(false).effort(Effort.HIGH).build().fingerprint());
        assertNotEquals(f, ModelRequest.builder("resolve", ModelClass.OPUS_CLASS).effort(Effort.LOW)
                .system(Content.Text.cached("catalogue text")).system(Content.Text.of("instructions"))
                .message(Message.user("Query kind: X"))
                .tool(new ToolSpec("lending_sign_on", "signs on", schema(false), false, false))
                .outputSchema(schema(false)).build().fingerprint(), "the model class");
        assertNotEquals(f, ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW)
                .system(Content.Text.cached("catalogue texT")).system(Content.Text.of("instructions"))
                .message(Message.user("Query kind: X"))
                .tool(new ToolSpec("lending_sign_on", "signs on", schema(false), false, false))
                .outputSchema(schema(false)).build().fingerprint(), "one character of a system part");
        assertNotEquals(f, ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW)
                .system(Content.Text.cached("catalogue text")).system(Content.Text.of("instructions"))
                .message(Message.user("Query kind: Y"))
                .tool(new ToolSpec("lending_sign_on", "signs on", schema(false), false, false))
                .outputSchema(schema(false)).build().fingerprint(), "one character of a message");
        assertNotEquals(f, ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW)
                .system(Content.Text.cached("catalogue text")).system(Content.Text.of("instructions"))
                .message(Message.user("Query kind: X"))
                .tool(new ToolSpec("lending_sign_on", "signs on.", schema(false), false, false))
                .outputSchema(schema(false)).build().fingerprint(), "one character of a tool");
        Map<String, Object> other = new LinkedHashMap<>(schema(false));
        other.put("required", List.of("a"));
        assertNotEquals(f, base(false).outputSchema(other).build().fingerprint(), "the output schema");
    }

    /**
     * phase7-contracts.md 7 and 13.1 item 7 (D318, D319): the new fields enter the fingerprint only when they are set,
     * so a request without them is fingerprinted over the same JSON as before (no {@code providerTools} key, no
     * {@code toolsetName} on a tool call or a result); a provider tool, its configs, a toolset name and an image inside
     * a tool result each change it, the image by its hash.
     */
    @Test
    void theNewFieldsEnterTheFingerprintOnlyWhenSet() {
        ModelRequest plain = base(false).build();
        assertEquals(List.of("modelClass", "effort", "system", "messages", "tools", "outputSchema"),
                List.copyOf(Prompts.fingerprintJson(plain).keySet()), "the Phase 5 keys, nothing more");
        assertEquals(plain.fingerprint(), new ModelRequest(plain.purpose(), plain.modelClass(), plain.effort(),
                plain.maxTokens(), plain.cache(), plain.system(), plain.messages(), plain.tools(), plain.outputSchema(),
                plain.metadata()).fingerprint(), "the ten-argument constructor keeps the provider tools empty");
        assertEquals(Prompts.part(new Content.ToolUse("t1", "n", Map.of("a", 1L))), Prompts.part(new Content.ToolUse(
                "t1", "n", Map.of("a", 1L), null)));
        assertFalse(Prompts.part(new Content.ToolUse("t1", "n", Map.of())).containsKey("toolsetName"));
        assertFalse(Prompts.part(new Content.ToolResult("t1", "ok", false)).containsKey("toolsetName"));

        ModelRequest toolset = base(false).providerTool(ProviderTool.computerToolset(false)).build();
        assertNotEquals(plain.fingerprint(), toolset.fingerprint(), "a provider tool");
        assertNotEquals(toolset.fingerprint(), base(false).providerTool(ProviderTool.computerToolset(true)).build()
                .fingerprint(), "its configs");
        assertEquals(toolset.fingerprint(), base(true).providerTool(ProviderTool.computerToolset(false)).build()
                .fingerprint(), "stable across map order");
        assertEquals(toolset.providerTools(), toolset.withCache(CacheHint.AUTO).providerTools());
        assertEquals(toolset.providerTools(), toolset.withMessages(toolset.messages()).providerTools());

        ModelRequest.Builder echoed = ModelRequest.builder("vision", ModelClass.SONNET_CLASS).message(Message.user("go"));
        String withoutName = echoed.message(Message.assistant(List.of(new Content.ToolUse("t1", "left_click",
                Map.of())))).build().fingerprint();
        String withName = ModelRequest.builder("vision", ModelClass.SONNET_CLASS).message(Message.user("go"))
                .message(Message.assistant(List.of(new Content.ToolUse("t1", "left_click", Map.of(), "computer"))))
                .build().fingerprint();
        assertNotEquals(withoutName, withName, "a toolset name");

        Content.ToolResult one = new Content.ToolResult("t1", List.of(new Content.Image("image/png",
                new byte[] {1, 2, 3}, null)), false, "computer");
        Content.ToolResult other = new Content.ToolResult("t1", List.of(new Content.Image("image/png",
                new byte[] {1, 2, 4}, null)), false, "computer");
        ModelRequest r1 = ModelRequest.builder("vision", ModelClass.SONNET_CLASS).message(Message.user(one)).build();
        ModelRequest r2 = ModelRequest.builder("vision", ModelClass.SONNET_CLASS).message(Message.user(other)).build();
        assertNotEquals(r1.fingerprint(), r2.fingerprint(), "an image inside a result, by its hash");
        assertFalse(Prompts.canonical(Prompts.fingerprintJson(r1)).contains("AQID"), "never the bytes");
    }

    /** phase7-contracts.md 7.5 (D324): an image's size from its PNG header, and its tokens. */
    @Test
    void anImagesSizeAndTokensComeFromItsHeader() {
        byte[] png = new byte[33];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'}, 0,
                png, 0, 16);
        png[18] = 0x04; // width 1024
        png[22] = 0x03; // height 768
        assertEquals(List.of(1024, 768), List.of(Prompts.imageSize(png)[0], Prompts.imageSize(png)[1]));
        assertEquals(37L * 28L, Prompts.imageTokens(png), "ceil(1024 / 28) * ceil(768 / 28) = 37 * 28 = 1036");
        assertNull(Prompts.imageSize(new byte[] {1, 2, 3}));
        assertEquals(0L, Prompts.imageTokens(new byte[] {1, 2, 3}));
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 0, 0, (byte) 0xFF, (byte) 0xC0, 0, 11,
                8, 0, 100, 0, (byte) 200, 3, 0, 0, 0};
        assertEquals(List.of(200, 100), List.of(Prompts.imageSize(jpeg)[0], Prompts.imageSize(jpeg)[1]));
    }

    @Test
    void anImageEntersByItsHashAndAnOpaquePartNotAtAll() {
        ModelRequest.Builder plain = ModelRequest.builder("session", ModelClass.SONNET_CLASS)
                .message(Message.user("look"));
        ModelRequest withImage = ModelRequest.builder("session", ModelClass.SONNET_CLASS)
                .message(Message.user(Content.Text.of("look"), new Content.Image("image/png", new byte[] {1, 2, 3},
                        null))).build();
        ModelRequest sameImage = ModelRequest.builder("session", ModelClass.SONNET_CLASS)
                .message(Message.user(Content.Text.of("look"), new Content.Image("image/png", new byte[] {1, 2, 3},
                        null))).build();
        ModelRequest otherImage = ModelRequest.builder("session", ModelClass.SONNET_CLASS)
                .message(Message.user(Content.Text.of("look"), new Content.Image("image/png", new byte[] {1, 2, 4},
                        null))).build();
        assertEquals(withImage.fingerprint(), sameImage.fingerprint());
        assertNotEquals(withImage.fingerprint(), otherImage.fingerprint());
        assertFalse(Prompts.canonical(Prompts.fingerprintJson(withImage)).contains("AQID"), "never the bytes");
        String base = plain.build().fingerprint();
        ModelRequest withOpaque = ModelRequest.builder("session", ModelClass.SONNET_CLASS)
                .message(Message.user("look"))
                .message(Message.assistant(List.of(new Content.Opaque("claude", "thinking",
                        Map.of("type", "thinking", "signature", "sig-1")), Content.Text.of("ok"))))
                .message(Message.user("more")).build();
        ModelRequest otherSignature = ModelRequest.builder("session", ModelClass.SONNET_CLASS)
                .message(Message.user("look"))
                .message(Message.assistant(List.of(new Content.Opaque("claude", "thinking",
                        Map.of("type", "thinking", "signature", "sig-2")), Content.Text.of("ok"))))
                .message(Message.user("more")).build();
        assertEquals(withOpaque.fingerprint(), otherSignature.fingerprint(), "signatures differ per call");
        assertNotEquals(base, withOpaque.fingerprint());
    }

    @Test
    void theRequestRefusesWhatTheProviderWould() {
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .build(), "no message");
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.builder(" ", ModelClass.SONNET_CLASS)
                .message(Message.user("x")).build(), "no purpose");
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .maxTokens(0).message(Message.user("x")).build());
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .message(Message.assistant(List.of(Content.Text.of("x")))).build(), "a user message first");
        ToolSpec t = new ToolSpec("a_tool", "", Map.of("type", "object"), true, false);
        assertThrows(IllegalArgumentException.class, () -> ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .message(Message.user("x")).tool(t).tool(t).build(), "a tool twice");
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec("a.b", "", Map.of(), true, false),
                "dots are not allowed in a tool name");
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec("x".repeat(65), "", Map.of(), true, false));
        assertThrows(IllegalArgumentException.class, () -> new Content.Image("image/gif", new byte[] {1}, null));
        assertThrows(IllegalArgumentException.class, () -> new Content.Opaque("claude", "has space", Map.of()));
    }

    @Test
    void partsAreDeepCopies() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("x", 1);
        Content.ToolUse use = new Content.ToolUse("tu_1", "t", input);
        input.put("y", 2);
        assertEquals(Map.of("x", 1L), use.input(), "copied, and an int is a long");
        assertThrows(UnsupportedOperationException.class, () -> use.input().put("z", 3L));
        byte[] bytes = {1, 2};
        Content.Image image = new Content.Image("image/jpeg", bytes, "c");
        bytes[0] = 9;
        assertEquals(1, image.bytes()[0]);
        assertFalse(image.toString().contains("[1, 2]"));
        assertTrue(image.sha256().startsWith("sha256:"));
    }

    @Test
    void theResponseAndTheUsage() {
        Usage usage = new Usage("claude", "m1", "m2", "msg_1", 10, 5, 3, 7, 11, true, "0.000123",
                Duration.ofMillis(250), "fallback");
        assertEquals(18, usage.cacheWriteTokens());
        assertEquals(usage, Usage.fromJson(usage.toJson()));
        assertEquals("0", new Usage("x", null, null, null, 0, 0, 0, 0, 0, false, null, null, null).costUsd());
        assertThrows(IllegalArgumentException.class, () -> new Usage("x", null, null, null, -1, 0, 0, 0, 0, false,
                "0", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Usage("x", null, null, null, 0, 0, 0, 0, 0, false,
                "one dollar", null, null));
        ModelResponse r = new ModelResponse(List.of(Content.Text.of("a"), new Content.ToolUse("tu", "t", Map.of()),
                new Content.Opaque("claude", "thinking", Map.of()), Content.Text.of("b")),
                ModelResponse.StopReason.TOOL_USE, usage, null);
        assertEquals("ab", r.text());
        assertEquals(1, r.toolUses().size());
        assertEquals("", new ModelResponse(List.of(), ModelResponse.StopReason.REFUSAL, usage, null).text());
    }

    @Test
    void theExceptionKindsAndTheBatchResult() {
        for (InferenceException.Kind kind : InferenceException.Kind.values()) {
            boolean retryable = kind == InferenceException.Kind.RATE_LIMIT || kind == InferenceException.Kind.OVERLOADED
                    || kind == InferenceException.Kind.TRANSPORT;
            assertEquals(retryable, new InferenceException(kind, "m").retryable(), kind.name());
        }
        Usage usage = Usage.none("claude", "m");
        assertEquals(usage, new InferenceException(InferenceException.Kind.PROVIDER, "m", "r", usage, null).usage());
        ModelResponse r = new ModelResponse(List.of(), ModelResponse.StopReason.END_TURN, usage, null);
        assertTrue(new BatchResult(0, r, null).succeeded());
        assertThrows(IllegalArgumentException.class, () -> new BatchResult(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new BatchResult(0, r,
                new InferenceException(InferenceException.Kind.PROVIDER, "m")));
        assertThrows(IllegalArgumentException.class, () -> new BatchResult(-1, r, null));
    }
}
