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

package ai.skillroy.testjava.inference.spi;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deep copies of JSON-like values (maps with string keys, lists, strings, numbers, booleans, null), the form every
 * part, schema and structured answer of the SPI holds, so that a value given to a record cannot change under it (a
 * fingerprint must not move). Stateless; safe from any thread.
 */
public final class JsonLike {

    /** The deepest nesting a value may have; deeper values are refused rather than truncated silently. */
    public static final int MAX_DEPTH = 200;

    private JsonLike() {
    }

    /**
     * A deep unmodifiable copy: maps keep their order (keys as {@code String.valueOf}), lists and other collections
     * become unmodifiable lists, numbers other than {@code Long}, {@code Double}, {@code BigDecimal} and
     * {@code BigInteger} become {@code Long} or {@code Double}, enums their name.
     *
     * @throws IllegalArgumentException for a value that is not JSON-like (a record, an array, an arbitrary object), a
     *     non-finite double, or nesting deeper than {@link #MAX_DEPTH}
     */
    public static Object freeze(Object value) {
        return copy(value, 0, false);
    }

    /** {@link #freeze} with the keys of every map sorted, for files that must be byte-stable. */
    public static Object sorted(Object value) {
        return copy(value, 0, true);
    }

    /** {@link #freeze} of a map; null is an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> freezeMap(Map<String, ?> map) {
        return map == null ? Map.of() : (Map<String, Object>) freeze(map);
    }

    private static Object copy(Object value, int depth, boolean sort) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("a JSON-like value nests deeper than " + MAX_DEPTH);
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Long
                || value instanceof BigDecimal || value instanceof BigInteger) {
            return value;
        }
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException("a JSON-like value holds a non-finite number");
            }
            return d;
        }
        if (value instanceof Float f) {
            return copy(f.doubleValue(), depth, sort);
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof CharSequence s) {
            return s.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = sort ? new TreeMap<>() : new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), copy(e.getValue(), depth + 1, sort));
            }
            return Collections.unmodifiableMap(sort ? new LinkedHashMap<>(out) : out);
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(copy(item, depth + 1, sort));
            }
            return Collections.unmodifiableList(out);
        }
        throw new IllegalArgumentException("not a JSON-like value: " + value.getClass().getName());
    }
}
