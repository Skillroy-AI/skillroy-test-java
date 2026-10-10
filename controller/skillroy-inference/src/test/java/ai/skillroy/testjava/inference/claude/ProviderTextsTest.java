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
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix, pass 2, P2-L5-F3 (the sceptic's restatement): what a provider, or whatever
 * {@code ANTHROPIC_BASE_URL} names, puts in a response's {@code model}, {@code id}, {@code stop_reason},
 * {@code stop_details.category} or content, or in an error's {@code request-id} header or {@code type}, reaches no
 * usage, part, exception or ledger field holding the model key, and every identifier and word is bounded. Before the
 * fix these were copied as sent (S54 scrubbed the error {@code message} only). The key is a dummy; the fake is on
 * loopback.
 */
class ProviderTextsTest {

    static final String KEY = ClaudeGatewayTest.KEY;
    /** The contract's bound on a provider identifier (P5 §4.1, P2-L5-F3). */
    static final int ID_MAX = 200;

    private FakeAnthropic fake;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeAnthropic();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private ClaudeGateway gateway() {
        Map<ModelClass, String> models = new EnumMap<>(ModelClass.class);
        models.put(ModelClass.OPUS_CLASS, "claude-opus-5-5");
        models.put(ModelClass.SONNET_CLASS, "claude-sonnet-5-5");
        models.put(ModelClass.HAIKU_CLASS, "claude-haiku-4-5");
        ClaudeGateway.Config config = new ClaudeGateway.Config(models, false, false, 10_000, PriceTable.builtIn());
        return new ClaudeGateway(ClaudeGateway.client(KEY, fake.url(), null, 10_000), config,
                Arrays.asList(KEY, null), Clock.systemUTC(), ms -> { });
    }

    private static ModelRequest request() {
        return ModelRequest.builder("resolve", ModelClass.SONNET_CLASS).effort(Effort.LOW).maxTokens(256)
                .system(Content.Text.of("the instructions")).message(Message.user("the question")).build();
    }

    private static void assertClean(String what, String text, int max) {
        if (text == null) {
            return;
        }
        assertFalse(text.contains(KEY), what + " holds the key: " + text);
        assertFalse(text.contains(KEY.substring(0, 12)), what + " holds part of the key: " + text);
        assertTrue(text.length() <= max, what + " is " + text.length() + " characters long");
    }

    @Test
    void aResponseThatEchoesTheKeyKeepsItNowhereAndIsBounded() throws Exception {
        String long5000 = "y".repeat(5000);
        String message = FakeAnthropic.message("claude-sonnet-5-5 " + KEY + " " + long5000, "weird_" + KEY + long5000,
                "[{\"type\":\"text\",\"text\":\"echo " + KEY + "\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                        + "\"name\":\"lending_sign_on\",\"input\":{\"staff\":\"" + KEY + "\"}}]", "{}", null)
                .replace("\"msg_fake_1\"", "\"msg_" + KEY + long5000 + "\"");
        fake.answer(Answer.json(message));
        ModelResponse r;
        try (ClaudeGateway g = gateway()) {
            r = g.complete(request());
        }
        Usage u = r.usage();
        assertClean("the model", u.model(), ID_MAX);
        assertClean("the request id", u.requestId(), ID_MAX);
        assertClean("the note", u.note(), 1000);
        assertTrue(u.note().contains("stop_reason weird_<redacted>"), u.note());
        assertFalse(Json.write(u.toJson()).contains(KEY), "the usage as a ledger and a recording write it");
        for (Content part : r.parts()) {
            assertFalse(String.valueOf(part).contains(KEY), "a part: " + part);
        }
        assertTrue(r.text().contains("echo <redacted>"), r.text());
    }

    @Test
    void aRefusalCategoryIsScrubbedAndBounded() throws Exception {
        fake.answer(Answer.json(FakeAnthropic.message("claude-sonnet-5-5", "refusal", "[]", "{}",
                "{\"type\":\"refusal\",\"category\":\"cat_" + KEY + "z".repeat(3000) + "\"}")));
        try (ClaudeGateway g = gateway()) {
            Usage u = g.complete(request()).usage();
            assertClean("the note", u.note(), 200);
        }
    }

    @Test
    void anErrorsRequestIdAndTypeAreScrubbedAndBounded() {
        fake.otherwise(Answer.error(400, "t".repeat(3000) + KEY, "bad request", Map.of("request-id",
                "req_" + KEY + "r".repeat(3000))));
        try (ClaudeGateway g = gateway()) {
            InferenceException e = assertThrows(InferenceException.class, () -> g.complete(request()));
            assertClean("the request id", e.requestId(), ID_MAX);
            assertClean("the message", e.getMessage(), 500);
        }
    }

    @Test
    void aBatchErrorsTypeIsBounded() throws Exception {
        String created = "{\"id\":\"msgbatch_9\",\"type\":\"message_batch\",\"processing_status\":\"ended\","
                + "\"request_counts\":{\"processing\":0,\"succeeded\":1,\"errored\":1,\"canceled\":0,\"expired\":0},"
                + "\"created_at\":\"2026-10-08T00:00:00Z\",\"expires_at\":\"2026-10-09T00:00:00Z\",\"ended_at\":null,"
                + "\"cancel_initiated_at\":null,\"archived_at\":null,\"results_url\":null}";
        String echoed = FakeAnthropic.message("claude-sonnet-5-5 " + KEY, "end_turn", "[]", "{}", null);
        fake.answer(Answer.json(created), Answer.json("{\"custom_id\":\"0\",\"result\":{\"type\":\"errored\","
                + "\"error\":{\"type\":\"error\",\"error\":{\"type\":\"" + "t".repeat(3000) + KEY + "\",\"message\":"
                + "\"bad\"}}}}\n{\"custom_id\":\"1\",\"result\":{\"type\":\"succeeded\",\"message\":" + echoed
                + "}}\n"));
        try (ClaudeGateway g = gateway()) {
            BatchHandle h = g.submitBatch(List.of(request(), request()));
            List<BatchResult> out = g.collectBatch(h);
            assertClean("the batch error", out.get(0).error().getMessage(), 500);
            assertClean("the batch model", out.get(1).response().usage().model(), ID_MAX);
        }
    }
}
