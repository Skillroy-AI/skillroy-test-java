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
 * The model usage behind a resolution (Phase 5). Always null in Phase 2: no model is called. The type exists so that
 * the bindings file's {@code llm} field and the cost ledger have their final shape.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param model the model id
 * @param requestId the provider's request id
 * @param inputTokens tokens sent
 * @param outputTokens tokens received
 * @param costUsd the cost as a decimal string
 */
public record LlmUsage(String model, String requestId, long inputTokens, long outputTokens, String costUsd) {
}
