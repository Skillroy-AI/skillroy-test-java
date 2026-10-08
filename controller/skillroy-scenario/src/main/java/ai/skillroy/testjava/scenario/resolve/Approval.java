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

import java.util.Objects;

/**
 * Whether a recorded resolution was approved by a person. {@code by} is a free-text label (decision Q6), by default
 * the mode name, so that a person's name does not land in a committed file unless someone chooses to write it.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param state {@link State#APPROVED} (written by {@code approve} and {@code re-resolve}), {@link State#REQUIRED} (a
 *     model-written value, tier (b), written by {@code live --write-bindings} and kept so until a person approves it)
 *     or {@link State#NOT_REQUIRED} (written by {@code live --write-bindings}, tier (a) only)
 * @param by the label, or null ({@code REQUIRED} has none: nobody approved)
 */
public record Approval(State state, String by) {

    /** The approval states. */
    public enum State {
        /** Tier (a): accepted on the verifier alone. */
        NOT_REQUIRED,
        /**
         * Tier (b) or (c) (phase5-contracts.md 7.1, decision D72): a model found the value, and a person has not
         * approved it yet; {@code replay} refuses it (REBIND_NEEDED, reason {@code NOT_APPROVED}).
         */
        REQUIRED,
        /** Shown to an approver and approved. */
        APPROVED
    }

    /** @throws NullPointerException if the state is null */
    public Approval {
        Objects.requireNonNull(state, "state");
    }
}
