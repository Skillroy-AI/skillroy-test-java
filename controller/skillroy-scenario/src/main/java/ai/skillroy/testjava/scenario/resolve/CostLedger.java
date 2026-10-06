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
import java.util.List;
import java.util.Objects;

/**
 * Every model call of a JVM-side run (phase2-contracts.md 6.6). Phase 2 has no writer: the type and the assertion
 * exist so that a later phase cannot add a model call to {@code replay} without a red build (a non-empty ledger
 * after a {@code replay} run is ERROR / ENVIRONMENT, decision D19).
 *
 * <p>Thread-safe: entries may be recorded from any thread.
 */
public final class CostLedger {

    /**
     * One model call.
     *
     * @param scenarioId the scenario
     * @param slot the slot the call was made for, or null
     * @param strategy the strategy that made it
     * @param provider the provider id
     * @param model the model id
     * @param requestId the provider's request id
     * @param inputTokens tokens sent
     * @param outputTokens tokens received
     * @param costUsd the cost as a decimal string
     */
    public record Entry(String scenarioId, String slot, Strategy strategy, String provider, String model,
                        String requestId, long inputTokens, long outputTokens, String costUsd) {
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
