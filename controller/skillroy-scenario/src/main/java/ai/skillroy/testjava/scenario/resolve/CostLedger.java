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

package ai.skillroy.testjava.scenario.resolve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every model call of a JVM-side run (phase2-contracts.md 6.6, phase5-contracts.md 7.3). The writer is
 * {@code skillroy-inference}'s {@code LedgerWriter}, which every caller of a gateway uses; a call answered by a
 * recorded gateway is recorded too (provider {@code recorded}, cost {@code 0}). A non-empty ledger after a
 * {@code replay} run is ERROR / ENVIRONMENT (decision D19): the zero-model-call assertion means "zero gateway calls of
 * any provider".
 *
 * <p>Thread-safe: entries may be recorded from any thread.
 */
public final class CostLedger {

    /**
     * One model call. The first nine components are the Phase 2 shape (and its constructor stays); the next five are
     * Phase 5's (decision D83), and {@code details} is the record of the call that {@code inference.jsonl} holds
     * (decision D91), so that a run's writer, which owns the ledger but never saw the call, can write that file from
     * the entries (one line per entry that has details). The earlier constructors keep {@code details} null.
     *
     * @param scenarioId the scenario
     * @param slot the slot the call was made for, or null
     * @param strategy the strategy that made it
     * @param provider the provider id ({@code claude}, {@code recorded}, {@code scripted}, {@code claude-code})
     * @param model the model id that answered
     * @param requestId the provider's request id
     * @param inputTokens tokens sent (not counting cache reads)
     * @param outputTokens tokens received
     * @param costUsd the cost as a decimal string
     * @param purpose what the call was for: {@code resolve}, {@code triage}, {@code session}, {@code plan},
     *     {@code summary}, and (phase7-contracts.md 7.3, 10, D322) {@code vision}: a call of the vision loop or a visual
     *     question, made through the session service's metered call
     * @param cacheReadTokens input tokens read from the provider's cache
     * @param cacheWriteTokens input tokens written to the provider's cache
     * @param batch true when the call was part of a batch (priced at the batch rate)
     * @param note a short note (a refusal's category, a fallback model), or null
     * @param details the masked {@code inference.jsonl} record of the call (its fingerprint, stop reason, elapsed
     *     time, requested model, cache-write split and masked structured answer, phase5-contracts.md 7.3, 7.4), a
     *     deep unmodifiable copy; null when there is none
     */
    public record Entry(String scenarioId, String slot, Strategy strategy, String provider, String model,
                        String requestId, long inputTokens, long outputTokens, String costUsd, String purpose,
                        long cacheReadTokens, long cacheWriteTokens, boolean batch, String note,
                        Map<String, Object> details) {

        /** The purpose of an entry built with the Phase 2 constructor. */
        public static final String PURPOSE_RESOLVE = "resolve";
        /** The purpose of a vision call (phase7-contracts.md 7.3, 10, D322; added by VM with VC's agreement). */
        public static final String PURPOSE_VISION = "vision";

        /** Copies the details. */
        public Entry {
            details = details == null ? null : Frozen.freezeMap(details);
        }

        /**
         * The Phase 2 shape: purpose {@code resolve}, no cache tokens, not a batch, no note, no details.
         */
        public Entry(String scenarioId, String slot, Strategy strategy, String provider, String model,
                     String requestId, long inputTokens, long outputTokens, String costUsd) {
            this(scenarioId, slot, strategy, provider, model, requestId, inputTokens, outputTokens, costUsd,
                    PURPOSE_RESOLVE, 0L, 0L, false, null, null);
        }

        /** The shape of decision D83, without details. */
        public Entry(String scenarioId, String slot, Strategy strategy, String provider, String model,
                     String requestId, long inputTokens, long outputTokens, String costUsd, String purpose,
                     long cacheReadTokens, long cacheWriteTokens, boolean batch, String note) {
            this(scenarioId, slot, strategy, provider, model, requestId, inputTokens, outputTokens, costUsd, purpose,
                    cacheReadTokens, cacheWriteTokens, batch, note, null);
        }

        /**
         * The {@code cost-ledger.jsonl} line of this entry before masking: the Phase 2 fields in their order, then
         * {@code purpose}, {@code cacheReadTokens}, {@code cacheWriteTokens}, {@code batch} and {@code note}
         * (phase5-contracts.md 7.3); every key present, a missing value as null, the strategy by name. The details
         * are not part of it (they are the {@code inference.jsonl} line). A new map on each call.
         */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("scenarioId", scenarioId);
            json.put("slot", slot);
            json.put("strategy", strategy == null ? null : strategy.name());
            json.put("provider", provider);
            json.put("model", model);
            json.put("requestId", requestId);
            json.put("inputTokens", inputTokens);
            json.put("outputTokens", outputTokens);
            json.put("costUsd", costUsd);
            json.put("purpose", purpose);
            json.put("cacheReadTokens", cacheReadTokens);
            json.put("cacheWriteTokens", cacheWriteTokens);
            json.put("batch", batch);
            json.put("note", note);
            return json;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** An empty ledger. */
    public CostLedger() {
    }

    /** Records one model call. */
    public synchronized void record(Entry entry) {
        entries.add(Objects.requireNonNull(entry, "entry"));
    }

    /** A copy of the entries, in recording order. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The number of entries. */
    public synchronized int size() {
        return entries.size();
    }

    /** True when no model call was recorded. */
    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The entry count only. */
    @Override
    public synchronized String toString() {
        return "CostLedger[" + entries.size() + " entries]";
    }
}
