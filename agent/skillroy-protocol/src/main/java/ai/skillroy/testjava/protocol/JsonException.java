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

package ai.skillroy.testjava.protocol;

/**
 * Malformed JSON on input, or a value that cannot be written as JSON (NaN, Infinity, nesting deeper than 200), or a
 * JSON document whose shape does not match a wire type. Unchecked because callers on both ends treat it as a
 * protocol error rather than something to recover from locally.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    /** A failure that is not tied to a position in an input text. {@link #offset()} is -1. */
    public JsonException(String message) {
        super(message);
        this.offset = -1;
    }

    /** A failure at a character offset (0-based, in UTF-16 units) of the input; the message ends with the offset. */
    public JsonException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    /** The 0-based offset of the offending character in the input, or -1 when not applicable. */
    public int offset() {
        return offset;
    }
}
