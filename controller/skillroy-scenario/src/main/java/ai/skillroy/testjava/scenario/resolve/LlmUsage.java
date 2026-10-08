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

/**
 * The model usage behind a resolution, written as the bindings file's {@code llm} field. Filled by the engine, never by
 * a resolver (phase2-contracts.md 6.3, phase5-contracts.md 7.3): from the cost ledger entries recorded during the
 * slot's resolver calls ({@code candidates}, and {@code prepare} and {@code complete} of a batchable resolver), the
 * tokens summed, the first entry's model and request id, the costs summed; null when no entry was recorded (every tier
 * (a) resolution).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param model the model id of the first call
 * @param requestId the provider's request id of the first call
 * @param inputTokens tokens sent, summed
 * @param outputTokens tokens received, summed
 * @param costUsd the cost as a decimal string, summed over the entries that state one; null when none does
 */
public record LlmUsage(String model, String requestId, long inputTokens, long outputTokens, String costUsd) {
}
