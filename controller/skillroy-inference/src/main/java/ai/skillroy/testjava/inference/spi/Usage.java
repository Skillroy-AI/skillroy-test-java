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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What one call cost (phase5-contracts.md 3.2). {@code costUsd} is computed by the gateway from its price table (the
 * provider reports tokens, not money), as a plain decimal string; {@code requestedModel} differs from {@code model}
 * when a fallback answered (4.1). The provider splits cache writes by time to live (BS13 F-4), and the price is taken
 * from that split, not from the request's {@link CacheHint}.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param provider {@code claude}, {@code recorded}, {@code scripted}
 * @param requestedModel the model id the settings named for the request's class
 * @param model the model id that answered
 * @param requestId the provider's id of the response, or null
 * @param inputTokens input tokens, not counting cache reads or writes
 * @param outputTokens output tokens
 * @param cacheReadTokens input tokens read from the cache
 * @param cacheWrite5mTokens input tokens written to the cache with the five-minute time to live
 * @param cacheWrite1hTokens input tokens written to the cache with the one-hour time to live
 * @param batch true for a call made through a batch (priced at the batch rate)
 * @param costUsd the cost in US dollars, a plain decimal string
 * @param elapsed how long the call took
 * @param note a refusal's category, the model a fallback used, or a price-table note; null when none
 */
public record Usage(String provider, String requestedModel, String model, String requestId, long inputTokens,
                    long outputTokens, long cacheReadTokens, long cacheWrite5mTokens, long cacheWrite1hTokens,
                    boolean batch, String costUsd, Duration elapsed, String note) {

    /**
     * Normalises a null cost to {@code "0"} and a null duration to zero.
     *
     * @throws IllegalArgumentException if a token count is negative or the cost is not a plain decimal
     */
    public Usage {
        Objects.requireNonNull(provider, "provider");
        if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWrite5mTokens < 0
                || cacheWrite1hTokens < 0) {
            throw new IllegalArgumentException("token counts are not negative");
        }
        if (costUsd == null) {
            costUsd = "0";
        } else {
            try {
                costUsd = new BigDecimal(costUsd.strip()).toPlainString();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("a cost is a plain decimal string");
            }
        }
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
    }

    /** A usage of nothing (a scripted answer that names no tokens). */
    public static Usage none(String provider, String model) {
        return new Usage(provider, model, model, null, 0, 0, 0, 0, 0, false, "0", Duration.ZERO, null);
    }

    /** The cache writes of both times to live: what the ledger entry carries. */
    public long cacheWriteTokens() {
        return cacheWrite5mTokens + cacheWrite1hTokens;
    }

    /** The cost as a decimal. */
    public BigDecimal cost() {
        return new BigDecimal(costUsd);
    }

    /** This usage with another provider, cost and batch flag (the recorded gateway's replay). */
    public Usage replayed(String newProvider, String newCost, boolean newBatch, Duration newElapsed) {
        return new Usage(newProvider, requestedModel, model, requestId, inputTokens, outputTokens, cacheReadTokens,
                cacheWrite5mTokens, cacheWrite1hTokens, newBatch, newCost, newElapsed, note);
    }

    /** This usage with another cost and note. */
    public Usage withCost(String newCost, String newNote) {
        return new Usage(provider, requestedModel, model, requestId, inputTokens, outputTokens, cacheReadTokens,
                cacheWrite5mTokens, cacheWrite1hTokens, batch, newCost, elapsed, newNote);
    }

    /** The JSON-like form a recording and {@code inference.jsonl} hold. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("provider", provider);
        json.put("requestedModel", requestedModel);
        json.put("model", model);
        json.put("requestId", requestId);
        json.put("inputTokens", inputTokens);
        json.put("outputTokens", outputTokens);
        json.put("cacheReadTokens", cacheReadTokens);
        json.put("cacheWrite5mTokens", cacheWrite5mTokens);
        json.put("cacheWrite1hTokens", cacheWrite1hTokens);
        json.put("batch", batch);
        json.put("costUsd", costUsd);
        json.put("elapsedMs", elapsed.toMillis());
        json.put("note", note);
        return json;
    }

    /**
     * Reads {@link #toJson()} back; missing counts are 0.
     *
     * @throws IllegalArgumentException if the provider is missing or a value has the wrong type
     */
    public static Usage fromJson(Map<?, ?> json) {
        Object provider = json.get("provider");
        if (!(provider instanceof String p)) {
            throw new IllegalArgumentException("a usage names its provider");
        }
        return new Usage(p, text(json, "requestedModel"), text(json, "model"), text(json, "requestId"),
                count(json, "inputTokens"), count(json, "outputTokens"), count(json, "cacheReadTokens"),
                count(json, "cacheWrite5mTokens"), count(json, "cacheWrite1hTokens"),
                Boolean.TRUE.equals(json.get("batch")), text(json, "costUsd"),
                Duration.ofMillis(count(json, "elapsedMs")), text(json, "note"));
    }

    private static String text(Map<?, ?> json, String key) {
        Object value = json.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("usage." + key + " is a text");
        }
        return (String) value;
    }

    private static long count(Map<?, ?> json, String key) {
        Object value = json.get(key);
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Long n)) {
            throw new IllegalArgumentException("usage." + key + " is a whole number");
        }
        return n;
    }
}
