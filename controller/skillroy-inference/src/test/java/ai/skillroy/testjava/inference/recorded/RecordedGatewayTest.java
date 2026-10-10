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

package ai.skillroy.testjava.inference.recorded;

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.ProviderTool;
import ai.skillroy.testjava.inference.spi.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 3: the recording and recorded gateways (the round trip, {@code NO_RECORDING}, the mask
 * on the way to disk, the overwrite rule, byte-stable files, batch replay and recording) and the scripted gateway.
 */
class RecordedGatewayTest {

    private static final String LONG = "Zq7-Lantern-42x";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T09:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties",
            Map.of("answer", Map.of("type", "string")), "required", List.of("answer"), "additionalProperties", false);

    private static ModelRequest request(String question) {
        return ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).system(Content.Text.cached("the catalogue"))
                .message(Message.user(question)).outputSchema(SCHEMA).metadata(ModelRequest.META_SLOT, "item")
                .build();
    }

    private static Usage usage() {
        return new Usage("claude", "claude-sonnet-5-5", "claude-sonnet-5-5", "msg_live_1", 100, 20, 30, 40, 0, false,
                "0.000478", Duration.ofMillis(900), null);
    }

    private RecordingGateway recording(ScriptedGateway live, boolean overwrite) {
        return new RecordingGateway(live, dir, PromptMask.of(SecretMask.of(List.of(LONG)), List.of(), List.of()),
                overwrite, CLOCK);
    }

    @Test
    void anAnswerRecordedIsReplayedVerbatimAtNoCost() throws Exception {
        ScriptedGateway live = ScriptedGateway.answering(r -> ScriptedGateway.structured(Map.of("answer", "DRL-01"),
                usage()));
        ModelRequest r = request("which item?");
        ModelResponse answered;
        try (RecordingGateway rec = recording(live, false)) {
            answered = rec.complete(r);
        }
        assertTrue(live.closed(), "the recording gateway closes the live one");
        Path file = Recording.file(dir, r.fingerprint());
        assertTrue(Files.isRegularFile(file));
        try (RecordedGateway replay = new RecordedGateway(dir)) {
            ModelResponse again = replay.complete(r);
            assertEquals(answered.structured(), again.structured());
            assertEquals(answered.parts(), again.parts());
            assertEquals(answered.stop(), again.stop());
            Usage u = again.usage();
            assertEquals("recorded", u.provider());
            assertEquals("0", u.costUsd());
            assertEquals(100, u.inputTokens());
            assertEquals(40, u.cacheWriteTokens());
            assertEquals("claude-sonnet-5-5", u.model());
            assertEquals(Duration.ZERO, u.elapsed());
            assertEquals(170, replay.countTokens(r), "the recorded input tokens, cache included");
            // phase7-contracts.md 7.3 (D321): a recording is keyed by a fingerprint that hashes an image.
            assertTrue(replay.capabilities().contains(GatewayCapability.IMAGES));
            assertTrue(replay.capabilities().contains(GatewayCapability.PROVIDER_TOOLS));
            assertTrue(replay.capabilities().contains(GatewayCapability.BATCH));
            ModelRequest budgetChanged = ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                    .system(Content.Text.cached("the catalogue")).message(Message.user("which item?"))
                    .outputSchema(SCHEMA).maxTokens(17).metadata("slot", "other").build();
            assertEquals(answered.structured(), replay.complete(budgetChanged).structured(),
                    "maxTokens and labels are outside the fingerprint");
        }
    }

    /**
     * phase7-contracts.md 7.3 and 13.1 item 7: a toolset request with an image in a tool result is recorded and
     * replayed: the file holds the provider tools, the toolset names and the image by its hash (never its bytes), the
     * answer's toolset call comes back with its toolset name, and a request whose image differs finds no recording.
     */
    @Test
    void aToolsetRequestWithAnImageIsRecordedAndReplayed() throws Exception {
        byte[] pixels = {(byte) 0x89, 'P', 'N', 'G', 7, 7, 7, 7};
        ModelRequest r = ModelRequest.builder("vision", ModelClass.SONNET_CLASS).cache(CacheHint.AUTO)
                .message(Message.user("lend one item"))
                .message(Message.assistant(List.of(new Content.ToolUse("t1", "screenshot", Map.of(), "computer"))))
                .message(Message.user(new Content.ToolResult("t1", List.of(new Content.Image("image/png", pixels,
                        null)), false, "computer")))
                .providerTool(ProviderTool.computerToolset(false)).build();
        ModelResponse answer = new ModelResponse(List.of(new Content.ToolUse("t2", "left_click",
                Map.of("coordinate", List.of(412L, 288L)), "computer")), ModelResponse.StopReason.TOOL_USE, usage(),
                null);
        try (RecordingGateway rec = recording(ScriptedGateway.answering(q -> answer), false)) {
            rec.complete(r);
        }
        Path file = Recording.file(dir, r.fingerprint());
        String text = Files.readString(file);
        assertTrue(text.contains("\"providerTools\""), text);
        assertTrue(text.contains("computer_toolset_20260801"), text);
        assertTrue(java.util.regex.Pattern.compile("\"toolsetName\"\\s*:\\s*\"computer\"").matcher(text).find(), text);
        assertTrue(text.contains(new Content.Image("image/png", pixels, null).sha256()), "the image by its hash");
        assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString(pixels)), "never the bytes");
        try (RecordedGateway replay = new RecordedGateway(dir)) {
            Content.ToolUse back = replay.complete(r).toolUses().get(0);
            assertEquals("computer", back.toolsetName());
            assertEquals("left_click", back.name());
            ModelRequest otherImage = ModelRequest.builder("vision", ModelClass.SONNET_CLASS)
                    .message(Message.user("lend one item"))
                    .message(Message.assistant(List.of(new Content.ToolUse("t1", "screenshot", Map.of(), "computer"))))
                    .message(Message.user(new Content.ToolResult("t1", List.of(new Content.Image("image/png",
                            new byte[] {(byte) 0x89, 'P', 'N', 'G', 8}, null)), false, "computer")))
                    .providerTool(ProviderTool.computerToolset(false)).build();
            assertEquals(InferenceException.Kind.NO_RECORDING, assertThrows(InferenceException.class,
                    () -> replay.complete(otherImage)).kind());
        }
    }

    @Test
    void aChangedPromptIsSeenNeverGuessed() throws Exception {
        try (RecordedGateway replay = new RecordedGateway(dir)) {
            ModelRequest r = request("a question nobody recorded");
            InferenceException e = assertThrows(InferenceException.class, () -> replay.complete(r));
            assertEquals(InferenceException.Kind.NO_RECORDING, e.kind());
            assertTrue(e.getMessage().contains(r.fingerprint()));
            assertTrue(e.getMessage().contains("resolve request of slot item"));
            assertTrue(e.getMessage().endsWith("; record with -Pinference-record (the sample's ScenarioDRecordIT) or"
                    + " with a recording gateway (record: true; see the recordings README of the sample)"),
                    e.getMessage());
            assertEquals(-1, replay.countTokens(r));
        }
    }

    @Test
    void aRecordingHoldsNothingAPromptMayNotHold() throws Exception {
        ModelRequest r = ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .system(Content.Text.cached("catalogue with " + LONG))
                .message(Message.user("password=hunter-two-x and the item"))
                .message(Message.assistant(List.of(new Content.ToolUse("t1", "lending_sign_on",
                        Map.of("secret", "s-3", "staff", "S01")))))
                .message(Message.user(new Content.ToolResult("t1", "ok", false)))
                .build();
        ScriptedGateway live = ScriptedGateway.answering(x -> ScriptedGateway.structured(Map.of("token", "tok-42",
                "answer", "fine " + LONG), usage()));
        try (RecordingGateway rec = recording(live, false)) {
            rec.complete(r);
        }
        String text = Files.readString(Recording.file(dir, r.fingerprint()), StandardCharsets.UTF_8);
        for (String secret : List.of(LONG, "hunter-two-x", "s-3", "tok-42")) {
            assertFalse(text.contains(secret), secret + " in " + text);
        }
        assertTrue(text.contains("password=<redacted>"));
        assertTrue(text.contains("\"staff\": \"S01\""));
    }

    /**
     * A provider's opaque blocks (a thinking block with its signature, a fallback block) go to disk as they came (the
     * mask passes them untouched: the model's own output, built from masked input) and come back from the recording
     * unchanged, so that a loop that echoes them can replay.
     */
    @Test
    void anOpaquePartIsRecordedAsReceivedAndReplayedUnchanged() throws Exception {
        Content.Opaque thinking = new Content.Opaque("claude", "thinking", Map.of("type", "thinking", "thinking",
                "the deposit rule first", "signature", "sig-opaque-42"));
        Content.Opaque fallback = new Content.Opaque("claude", "fallback", Map.of("type", "fallback", "model",
                "claude-sonnet-5", "reason", List.of("refusal", Map.of("category", "cyber"))));
        ModelResponse answer = new ModelResponse(List.of(thinking, Content.Text.of("{\"answer\":\"DRL-01\"}"),
                fallback), ModelResponse.StopReason.END_TURN, usage(), Map.of("answer", "DRL-01"));
        assertTrue(PromptMask.keysOnly().part(thinking) == thinking, "the mask passes an opaque part untouched");
        ModelRequest r = request("which item, thinking first?");
        try (RecordingGateway rec = recording(ScriptedGateway.answering(x -> answer), false)) {
            rec.complete(r);
        }
        String text = Files.readString(Recording.file(dir, r.fingerprint()), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"blockType\": \"thinking\"") && text.contains("sig-opaque-42"), text);
        assertTrue(text.contains("\"blockType\": \"fallback\""), text);
        try (RecordedGateway replay = new RecordedGateway(dir)) {
            ModelResponse again = replay.complete(r);
            assertEquals(answer.parts(), again.parts(), "every part, the opaque ones included, in order");
            Content.Opaque back = (Content.Opaque) again.parts().get(0);
            assertEquals("claude", back.provider());
            assertEquals("thinking", back.type());
            assertEquals(thinking.raw(), back.raw(), "the raw block, signature included");
            assertEquals(fallback, again.parts().get(2));
            assertEquals(answer.structured(), again.structured());
        }
    }

    @Test
    void anExistingRecordingIsKeptUnlessOverwritingIsAsked() throws Exception {
        ModelRequest r = request("which item?");
        try (RecordingGateway first = recording(ScriptedGateway.answering(x -> ScriptedGateway.structured(
                Map.of("answer", "first"), usage())), false)) {
            first.complete(r);
        }
        Path file = Recording.file(dir, r.fingerprint());
        byte[] before = Files.readAllBytes(file);
        try (RecordingGateway second = recording(ScriptedGateway.answering(x -> ScriptedGateway.structured(
                Map.of("answer", "second"), usage())), false)) {
            assertEquals(Map.of("answer", "second"), second.complete(r).structured(), "the live answer is used");
        }
        assertArrayEquals(before, Files.readAllBytes(file), "and the file kept");
        try (RecordingGateway third = recording(ScriptedGateway.answering(x -> ScriptedGateway.structured(
                Map.of("answer", "third"), usage())), true)) {
            third.complete(r);
        }
        assertTrue(Files.readString(file).contains("\"third\""));
    }

    @Test
    void recordingsAreByteStableWithSortedKeys() throws Exception {
        ModelRequest r = request("which item?");
        ModelResponse answer = ScriptedGateway.structured(Map.of("b", "2", "a", "1"), usage());
        Path one = Recording.write(dir.resolve("one"), r, answer, PromptMask.keysOnly(), CLOCK.instant());
        Path two = Recording.write(dir.resolve("two"), r, answer, PromptMask.keysOnly(), CLOCK.instant());
        assertArrayEquals(Files.readAllBytes(one), Files.readAllBytes(two));
        String text = Files.readString(one);
        assertTrue(text.startsWith("{\n  \"fingerprint\": \"" + r.fingerprint() + "\",\n  \"model\""), text);
        assertTrue(text.indexOf("\"a\": \"1\"") < text.indexOf("\"b\": \"2\""));
        Recording read = Recording.read(one);
        assertEquals(r.fingerprint(), read.fingerprint());
        assertEquals("resolve", read.purpose());
        assertEquals(answer.structured(), read.response().structured());
        Files.writeString(dir.resolve("bad.json"), "{\"version\": 2}");
        assertThrows(IllegalArgumentException.class, () -> Recording.read(dir.resolve("bad.json")));
    }

    @Test
    void batchesAreReplayedAndRecordedRequestByRequest() throws Exception {
        ModelRequest known = request("known");
        ModelRequest unknown = request("unknown");
        ScriptedGateway live = ScriptedGateway.of(x -> {
            if (x.messages().get(0).parts().get(0) instanceof Content.Text t && t.text().equals("unknown")) {
                throw new InferenceException(InferenceException.Kind.OVERLOADED, "busy");
            }
            return ScriptedGateway.structured(Map.of("answer", "k"), usage());
        });
        try (RecordingGateway rec = recording(live, false)) {
            BatchHandle h = rec.submitBatch(List.of(known, unknown));
            assertEquals(BatchStatus.State.ENDED, rec.batchStatus(h).state());
            List<BatchResult> results = rec.collectBatch(h);
            assertTrue(results.get(0).succeeded());
            assertEquals(InferenceException.Kind.OVERLOADED, results.get(1).error().kind());
        }
        assertTrue(Files.isRegularFile(Recording.file(dir, known.fingerprint())), "a batch answer is recorded");
        try (RecordedGateway replay = new RecordedGateway(dir)) {
            BatchHandle h = replay.submitBatch(List.of(known, unknown));
            assertEquals(new BatchStatus(BatchStatus.State.ENDED, 1, 1, 0, 0, 0), replay.batchStatus(h));
            List<BatchResult> results = replay.collectBatch(h);
            assertEquals(0, results.get(0).index());
            assertTrue(results.get(0).response().usage().batch());
            assertEquals("recorded", results.get(0).response().usage().provider());
            assertEquals(InferenceException.Kind.NO_RECORDING, results.get(1).error().kind());
            replay.cancelBatch(h);
            assertThrows(InferenceException.class, () -> replay.collectBatch(h));
        }
    }

    @Test
    void theScriptedGatewayKeepsItsRequests() throws Exception {
        ScriptedGateway seq = ScriptedGateway.sequence(List.of(ScriptedGateway.text("one"),
                ScriptedGateway.refusal("cyber")));
        assertEquals("one", seq.complete(request("a")).text());
        ModelResponse refusal = seq.complete(request("b"));
        assertEquals(ModelResponse.StopReason.REFUSAL, refusal.stop());
        assertEquals("refusal: cyber", refusal.usage().note());
        assertEquals(InferenceException.Kind.UNSUPPORTED, assertThrows(InferenceException.class,
                () -> seq.complete(request("c"))).kind());
        assertEquals(3, seq.requests().size());
        InferenceException auth = new InferenceException(InferenceException.Kind.AUTH, "no");
        assertEquals(auth, assertThrows(InferenceException.class,
                () -> ScriptedGateway.failing(auth).complete(request("x"))));
        ScriptedGateway echo = ScriptedGateway.answering(r -> ScriptedGateway.text(r.purpose()));
        assertEquals("resolve", echo.complete(request("x"), Duration.ofMillis(1)).text(), "the timeout overload");
        assertEquals(5, echo.countTokens(request("12345678")), "text length / 4: (13 + 8) / 4");
        BatchHandle h = echo.submitBatch(List.of(request("p"), request("q")));
        assertTrue(echo.collectBatch(h).stream().allMatch(b -> b.response().usage().batch()));
    }
}
