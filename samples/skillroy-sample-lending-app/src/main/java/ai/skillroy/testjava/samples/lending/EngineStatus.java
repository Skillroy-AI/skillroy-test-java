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

package ai.skillroy.testjava.samples.lending;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A consistent snapshot of what the engine is doing, published by the engine thread whenever it changes. Reading
 * {@link Engine#status()} once gives a state, prompt and list of valid inputs that belong together, which separate
 * calls to {@link Engine#state()}, {@link Engine#prompt()} and {@link Engine#validInputs()} cannot promise.
 * Immutable.
 */
public final class EngineStatus {

    private final LendingState state;
    private final String prompt;
    private final List<String> validInputs;
    private final boolean awaitingInput;
    private final Staff staff;
    private final boolean returnMode;
    private final boolean secretEntry;
    private final boolean running;

    EngineStatus(LendingState state, String prompt, List<String> validInputs, boolean awaitingInput, Staff staff,
                 boolean returnMode, boolean secretEntry, boolean running) {
        this.state = state;
        this.prompt = prompt;
        this.validInputs = Collections.unmodifiableList(new ArrayList<String>(validInputs));
        this.awaitingInput = awaitingInput;
        this.staff = staff;
        this.returnMode = returnMode;
        this.secretEntry = secretEntry;
        this.running = running;
    }

    public LendingState state() {
        return state;
    }

    /** @return the prompt line (see {@link LendingState}) */
    public String prompt() {
        return prompt;
    }

    /** @return the input sources accepted now; empty while the engine is busy or a modal prompt is open */
    public List<String> validInputs() {
        return validInputs;
    }

    /** @return true while the engine is blocked waiting for an input from the inbox */
    public boolean awaitingInput() {
        return awaitingInput;
    }

    /** @return the signed-on staff member, or null (also null during the PASSWORD step) */
    public Staff staff() {
        return staff;
    }

    public boolean returnMode() {
        return returnMode;
    }

    public boolean locked() {
        return state == LendingState.LOCKED;
    }

    /** @return true while the expected entry is a password (the entry field masks what is typed) */
    public boolean secretEntry() {
        return secretEntry;
    }

    /** @return false before start and after the engine thread stopped */
    public boolean running() {
        return running;
    }

    @Override
    public String toString() {
        return "EngineStatus{state=" + state + ", prompt=" + prompt + ", validInputs=" + validInputs
            + ", awaitingInput=" + awaitingInput + ", staff=" + (staff == null ? null : staff.code())
            + ", returnMode=" + returnMode + ", running=" + running + "}";
    }
}
