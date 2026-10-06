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

/**
 * The keypad actions. Each one is posted as a {@link LendingInput} whose source is {@code "Key." + name()}, for example
 * {@code Key.DUE}. {@link #DEPOSIT} and {@link #QTY} may carry a value (the deposit received, the quantity); the keypad
 * takes it from the entry field.
 */
public enum KeyAction {
    /** Ends item entry: computes the deposit due, applying the waiver rules, and moves to DEPOSIT. */
    DUE,
    /** Takes the deposit. Value: the amount, else the amount entered before, else exactly the amount due. */
    DEPOSIT,
    /** Toggles return mode from IDLE (and back while no return line exists). */
    RETURN,
    /** Abandons the open loan (and any pending sub-step) and returns to IDLE. Nothing is posted. */
    CANCEL,
    /** Signs the current staff member off. */
    SIGN_OFF,
    /** Sets the quantity for the next item. Value: the quantity; without a value the engine asks for it. */
    QTY;

    /** @return the input source for this action, {@code "Key." + name()} */
    public String source() {
        return LendingInput.KEY_PREFIX + name();
    }

    /**
     * @param source an input source such as {@code "Key.DUE"}
     * @return the matching action, or null when the source is not a known key
     */
    public static KeyAction fromSource(String source) {
        if (source == null || !source.startsWith(LendingInput.KEY_PREFIX)) {
            return null;
        }
        String name = source.substring(LendingInput.KEY_PREFIX.length());
        for (KeyAction action : values()) {
            if (action.name().equals(name)) {
                return action;
            }
        }
        return null;
    }
}
