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

package ai.skillroy.testjava.spikes.bs12;

import ai.skillroy.testjava.inference.budget.PriceTable;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageTokensCount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * The Messages API through the Anthropic Java SDK the CLI distribution pins (2.70.0). Request bodies are built as
 * JSON and handed to the SDK as {@link MessageCreateParams.Body} (the SDK's own mapper reads them; the computer
 * toolset, {@code toolset_name} on results and {@code cache_control} go through unchanged), responses are kept as the
 * SDK's JSON of the {@link Message}, so that an assistant turn is replayed exactly as received.
 *
 * <p><b>Budget.</b> Before each call the worst case (an estimate of the input at the cache-write price plus
 * {@code max_tokens} of output) is reserved against {@link Bs12#HARD_CAP} minus everything recorded so far
 * ({@link Bs12.Ledger}) minus the reservations of the calls in flight; a call that could cross the cap is not sent.
 * A soft cap per run ({@code --max-usd}) works the same way.
 *
 * <p><b>Prices.</b> {@code claude-sonnet-5-5} and {@code claude-opus-5-5} from the project's {@link PriceTable};
 * {@code claude-haiku-5-5}, which that table does not list, at the claude-api reference's USD 0.10 / 0.50 per million
 * (cache reads 0.1x input, five-minute cache writes 1.25x input).
 */
final class Model {

    static final String SONNET = "claude-sonnet-5-5";
    static final String OPUS = "claude-opus-5-5";
    static final String HAIKU = "claude-haiku-5-5";

    /** One completed call. */
    record Call(JsonNode message, long latencyMs, String requestId, long input, long output, long cacheRead,
                long cacheWrite, BigDecimal cost, String stopReason) {

        ObjectNode toJson() {
            ObjectNode o = Bs12.MAPPER.createObjectNode();
            o.put("latencyMs", latencyMs);
            o.put("requestId", requestId);
            o.put("input", input);
            o.put("output", output);
            o.put("cacheRead", cacheRead);
            o.put("cacheWrite", cacheWrite);
            o.put("costUsd", cost.toPlainString());
            o.put("stopReason", stopReason);
            return o;
        }
    }

    /** The budget would be crossed by the next call. */
    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    private final AnthropicClient client;
    private final BigDecimal softCap;
    private BigDecimal committed;
    private BigDecimal reserved = BigDecimal.ZERO;
    private final BigDecimal runStart;

    Model(BigDecimal softCap) throws Exception {
        if (System.getenv("ANTHROPIC_API_KEY") == null || System.getenv("ANTHROPIC_API_KEY").isBlank()) {
            throw new IllegalStateException("no model key in the environment (ANTHROPIC_API_KEY); nothing live runs");
        }
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().fromEnv().maxRetries(2)
                .timeout(Duration.ofSeconds(180));
        String ws = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (ws != null && !ws.isBlank()) {
            b.putHeader("anthropic-workspace-id", ws);
        }
        this.client = b.build();
        this.committed = Bs12.Ledger.spent();
        this.runStart = committed;
        this.softCap = softCap == null ? Bs12.HARD_CAP : softCap.min(Bs12.HARD_CAP);
        Bs12.log("budget: spent so far USD %s, cap for this run USD %s (hard cap USD %s)", committed.toPlainString(),
                this.softCap.toPlainString(), Bs12.HARD_CAP.toPlainString());
    }

    synchronized BigDecimal spentThisRun() {
        return committed.subtract(runStart);
    }

    synchronized BigDecimal committed() {
        return committed;
    }

    /** Sends one Messages API request. */
    Call send(ObjectNode body) throws Exception {
        String model = body.get("model").asText();
        BigDecimal worst = worstCase(body);
        synchronized (this) {
            BigDecimal after = committed.add(reserved).add(worst);
            if (after.compareTo(softCap) > 0) {
                throw new BudgetExceeded("the next call (worst case USD " + worst.setScale(4, RoundingMode.UP)
                        + ") could take the total to USD " + after.setScale(4, RoundingMode.UP) + ", above USD " + softCap);
            }
            reserved = reserved.add(worst);
        }
        try {
            MessageCreateParams.Body b = Bs12.MAPPER.treeToValue(body, MessageCreateParams.Body.class);
            MessageCreateParams params = MessageCreateParams.builder().body(b).build();
            long t0 = System.nanoTime();
            HttpResponseFor<Message> response = client.messages().withRawResponse().create(params);
            Message message = response.parse();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            JsonNode raw = Bs12.MAPPER.valueToTree(message);
            JsonNode u = raw.path("usage");
            long in = u.path("input_tokens").asLong();
            long out = u.path("output_tokens").asLong();
            long cr = u.path("cache_read_input_tokens").asLong();
            long cw = u.path("cache_creation_input_tokens").asLong();
            long cw1h = u.path("cache_creation").path("ephemeral_1h_input_tokens").asLong();
            BigDecimal cost = cost(model, in, out, cr, cw - cw1h, cw1h);
            synchronized (this) {
                committed = committed.add(cost);
            }
            return new Call(raw, ms, response.requestId().orElse(""), in, out, cr, cw, cost,
                    raw.path("stop_reason").asText(""));
        } finally {
            synchronized (this) {
                reserved = reserved.subtract(worst);
            }
        }
    }

    /** {@code count_tokens} of a body (free; not counted against the budget). */
    long countTokens(ObjectNode body) throws Exception {
        ObjectNode copy = body.deepCopy();
        copy.remove("max_tokens");
        copy.remove("output_config");
        MessageCountTokensParams.Body b = Bs12.MAPPER.treeToValue(copy, MessageCountTokensParams.Body.class);
        MessageTokensCount c = client.messages().countTokens(MessageCountTokensParams.builder().body(b).build());
        return c.inputTokens();
    }

    static BigDecimal cost(String model, long in, long out, long cacheRead, long cacheWrite5m, long cacheWrite1h) {
        BigDecimal pin;
        BigDecimal pout;
        BigDecimal pread;
        BigDecimal f5 = new BigDecimal("1.25");
        BigDecimal f1h = new BigDecimal("2");
        if (PriceTable.builtIn().knows(model) && !model.startsWith("claude-haiku-5-5")) {
            PriceTable.Price p = PriceTable.builtIn().price(model);
            pin = p.inputPerMTok();
            pout = p.outputPerMTok();
            pread = p.cacheReadPerMTok();
            f5 = p.cacheWrite5mFactor();
            f1h = p.cacheWrite1hFactor();
        } else if (model.startsWith("claude-haiku-5-5")) {
            pin = new BigDecimal("0.10");
            pout = new BigDecimal("0.50");
            pread = new BigDecimal("0.01");
        } else {
            throw new IllegalArgumentException("no price for " + model);
        }
        BigDecimal m = BigDecimal.valueOf(1_000_000L);
        return BigDecimal.valueOf(in).multiply(pin)
                .add(BigDecimal.valueOf(out).multiply(pout))
                .add(BigDecimal.valueOf(cacheRead).multiply(pread))
                .add(BigDecimal.valueOf(cacheWrite5m).multiply(pin).multiply(f5))
                .add(BigDecimal.valueOf(cacheWrite1h).multiply(pin).multiply(f1h))
                .divide(m, 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** The input estimated generously (text at 3 characters a token, 1,700 tokens an image, the toolset's 5,000). */
    static BigDecimal worstCase(ObjectNode body) {
        String model = body.get("model").asText();
        long[] chars = {0};
        long[] images = {0};
        walk(body, chars, images);
        long tokens = chars[0] / 3 + images[0] * 1700;
        for (JsonNode t : body.path("tools")) {
            if (t.path("type").asText().startsWith("computer_toolset")) {
                tokens += 5000;
            }
        }
        long maxTokens = body.path("max_tokens").asLong(4096);
        return cost(model, 0, maxTokens, 0, tokens, 0);
    }

    private static void walk(JsonNode n, long[] chars, long[] images) {
        if (n.isObject()) {
            if ("base64".equals(n.path("type").asText()) && n.has("data")) {
                images[0]++;
                return;
            }
            n.fields().forEachRemaining(e -> {
                chars[0] += e.getKey().length() + 3;
                walk(e.getValue(), chars, images);
            });
        } else if (n.isArray()) {
            for (JsonNode c : (ArrayNode) n) {
                walk(c, chars, images);
            }
        } else {
            chars[0] += n.asText().length() + 2;
        }
    }
}
