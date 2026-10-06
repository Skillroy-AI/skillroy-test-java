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

package ai.skillroy.testjava.scenario.pack;

import java.util.Objects;

/**
 * What a {@link PromptPolicy} decided about a prompt no step expected. Recorded in {@code run.jsonl}.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param action what the executor does next
 * @param reason a short explanation for the run log and, for {@link Action#FAIL}, the failure message; may be null
 */
public record PromptDecision(Action action, String reason) {

    /** What the executor does after the policy returns. */
    public enum Action {
        /** Fail the scenario (status FAIL, failure kind ASSERTION). */
        FAIL,
        /** The policy dealt with the prompt (answered or dismissed it); the scenario continues. */
        HANDLED,
        /** The prompt is harmless and stays as it is; the scenario continues. */
        IGNORE
    }

    /**
     * @throws NullPointerException if {@code action} is null
     */
    public PromptDecision {
        Objects.requireNonNull(action, "action");
    }

    /** Fail the scenario because of this prompt. */
    public static PromptDecision fail(String reason) {
        return new PromptDecision(Action.FAIL, reason);
    }

    /** The policy answered or dismissed the prompt; continue. */
    public static PromptDecision handled(String reason) {
        return new PromptDecision(Action.HANDLED, reason);
    }

    /** Leave the prompt alone; continue. */
    public static PromptDecision ignore(String reason) {
        return new PromptDecision(Action.IGNORE, reason);
    }
}
