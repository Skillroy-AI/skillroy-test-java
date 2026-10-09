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


package ai.skillroy.testjava.scenario.observe;

/**
 * One turn of the session service (phase6-contracts.md 10.2): built by {@code SessionService} from its {@code Turn}
 * before the call, never carrying the turn's request, answer or an action's input or result.
 *
 * @param index the turn's number, from 1
 * @param outcome how it ended ({@code DONE}, {@code QUESTION}, {@code LIMIT}, {@code BUDGET}, {@code LOST})
 * @param actions how many actions the model took
 * @param inputTokens the turn's input tokens, summed
 * @param outputTokens the turn's output tokens, summed
 * @param cacheReadTokens the turn's cache read tokens, summed
 * @param cacheWriteTokens the turn's cache write tokens, summed
 * @param model the model that answered, or null
 * @param requestId the last request id, or null
 * @param durationMs how long the turn took
 */
public record TurnRecord(int index, String outcome, int actions, long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheWriteTokens, String model, String requestId,
                         long durationMs) {
}
