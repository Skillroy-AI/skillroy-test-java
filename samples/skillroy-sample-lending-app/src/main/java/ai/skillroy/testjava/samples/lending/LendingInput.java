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

import java.util.Objects;

/**
 * One input to the desk: the entry field, the barcode scanner or a keypad key. Every input source (the user
 * interface, a scanner driver, a plugin) posts these to the {@link Inbox}; only the engine thread consumes them.
 * Immutable and safe to share between threads.
 *
 * <p>Sources: {@value #ENTRY} (value = the entry-field text, submitted with Enter), {@value #BARCODE} (value = the
 * scanned code) and {@code Key.<ACTION>} for each {@link KeyAction}.
 */
public final class LendingInput {

    /** Source of the entry field. */
    public static final String ENTRY = "Entry";
    /** Source of the barcode scanner. */
    public static final String BARCODE = "Barcode";
    /** Prefix of keypad sources. */
    public static final String KEY_PREFIX = "Key.";

    private final String source;
    private final String value;

    /**
     * @param source the input source, not null
     * @param value the value, may be null
     */
    public LendingInput(String source, String value) {
        this.source = Objects.requireNonNull(source, "source");
        this.value = value;
    }

    /** @return an entry-field input */
    public static LendingInput entry(String value) {
        return new LendingInput(ENTRY, value);
    }

    /** @return a scanner input */
    public static LendingInput barcode(String code) {
        return new LendingInput(BARCODE, code);
    }

    /** @return a keypad input without a value */
    public static LendingInput key(KeyAction action) {
        return new LendingInput(action.source(), null);
    }

    /** @return a keypad input with a value (only {@link KeyAction#DEPOSIT} and {@link KeyAction#QTY} use it) */
    public static LendingInput key(KeyAction action, String value) {
        return new LendingInput(action.source(), value);
    }

    public String source() {
        return source;
    }

    /** @return the value, possibly null */
    public String value() {
        return value;
    }

    /** @return the keypad action of a {@code Key.*} source, else null */
    public KeyAction keyAction() {
        return KeyAction.fromSource(source);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LendingInput)) {
            return false;
        }
        LendingInput other = (LendingInput) o;
        return source.equals(other.source) && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, value);
    }

    @Override
    public String toString() {
        return "LendingInput{source=" + source + ", value=" + value + "}";
    }
}
