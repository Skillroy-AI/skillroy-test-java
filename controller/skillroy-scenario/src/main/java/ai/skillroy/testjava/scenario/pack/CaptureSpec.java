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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A slot bound from the event that ended a call's wait (phase2-contracts.md 3.4): when the awaited event arrives, the
 * executor binds {@code slot} to {@code {field: <payload value at path>, …}}. {@code fields} is required, so that every
 * reference to the slot can be checked at compile time. A captured slot belongs to the run and is never written to
 * a bindings file.
 *
 * <p>Built through {@link #of}; the canonical constructor is not API. Immutable (fields sorted by name); safe to share
 * between threads.
 *
 * @param slot the slot name, {@code [A-Za-z_][A-Za-z0-9_]*}
 * @param fields slot field name to a path into the event's payload, in the dotted navigation references use
 *     ({@code loanId}, {@code header.id}); at least one
 */
public record CaptureSpec(String slot, Map<String, String> fields) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*");

    /**
     * @throws IllegalArgumentException if the slot is not a name, there is no field, or a field or path is malformed
     */
    public CaptureSpec {
        Objects.requireNonNull(slot, "slot");
        if (!NAME.matcher(slot).matches()) {
            throw new IllegalArgumentException("capture: slot name '" + slot + "' must match [A-Za-z_][A-Za-z0-9_]*");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("capture of slot '" + slot + "' needs at least one field");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey() == null || !FIELD.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("capture of slot '" + slot + "': field name '" + e.getKey()
                        + "' must match [A-Za-z0-9_]+");
            }
            if (e.getValue() == null || !PATH.matcher(e.getValue()).matches()) {
                throw new IllegalArgumentException("capture of slot '" + slot + "': field '" + e.getKey()
                        + "' needs a payload path such as loanId or header.id, found " + e.getValue());
            }
            sorted.put(e.getKey(), e.getValue());
        }
        fields = Collections.unmodifiableMap(sorted);
    }

    /** A capture of {@code slot} with these fields. */
    public static CaptureSpec of(String slot, Map<String, String> fields) {
        return new CaptureSpec(slot, fields);
    }
}
