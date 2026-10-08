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

package ai.skillroy.testjava.inference.session;

import ai.skillroy.testjava.inference.spi.JsonLike;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.StepOutcome;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One turn of an interactive session (phase5-contracts.md 12.4): the person's request, the actions the model took
 * through the session (one at a time, each journaled with its model origin), the model's final answer, how the turn
 * ended, what its model calls cost and how long it took.
 *
 * <p>Immutable (lists and maps are copied); safe to share between threads.
 *
 * @param index the turn's number in the session, from 1
 * @param request the person's text as given
 * @param actions the actions in order
 * @param answer the model's answer ({@code done}), its question ({@code ask_person}), or the service's account of why
 *     the turn ended otherwise
 * @param outcome how the turn ended
 * @param totalUsage the turn's model calls summed (tokens, cost, elapsed); a usage of nothing when it made none
 * @param duration how long the turn took
 */
public record Turn(int index, String request, List<Action> actions, String answer, Outcome outcome, Usage totalUsage,
                   Duration duration) {

    /** How a turn ended. */
    public enum Outcome {
        /** The model called {@code done} (or answered in text without a tool call): the request is carried out. */
        DONE,
        /**
         * The model called {@code ask_person}; also how a turn ends when the model declined, its answer was cut, or
         * the model provider could not be reached: the person decides how to go on ({@link #answer()} says why).
         */
        QUESTION,
        /** The turn took {@code maxActionsPerTurn} actions. */
        LIMIT,
        /** The session budget had no room for the next model call; the service makes no further model call. */
        BUDGET,
        /** The agent was lost. */
        LOST
    }

    /**
     * One action of a turn.
     *
     * @param tool the tool the model called ({@code lending_sign_on}, {@code skillroy_snapshot}, …)
     * @param input its input, masked
     * @param step the step's outcome for a verb or a primitive; null for a read or another tool
     * @param result what the model was told, masked and bounded
     */
    public record Action(String tool, Map<String, Object> input, StepOutcome step, String result) {

        /** Copies the input. */
        public Action {
            Objects.requireNonNull(tool, "tool");
            input = JsonLike.freezeMap(input);
            result = result == null ? "" : result;
        }
    }

    /** Copies the actions. */
    public Turn {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(totalUsage, "totalUsage");
        actions = actions == null ? List.of() : List.copyOf(actions);
        duration = duration == null ? Duration.ZERO : duration;
    }
}
