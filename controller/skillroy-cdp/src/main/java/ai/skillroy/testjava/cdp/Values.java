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

package ai.skillroy.testjava.cdp;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts what Playwright returns from {@code evaluate} (Java {@code Map}s, {@code List}s, {@code Integer}s,
 * {@code Double}s, and a few special types) into the JSON normal form of the protocol module: {@code LinkedHashMap}
 * with string keys, {@code ArrayList}, {@code Long} for every integral number up to 2^53 (the agent path parses the
 * same JSON into {@code Long}), {@code Double} otherwise, {@code null} for NaN and the infinities (as
 * {@code JSON.stringify} does), the ISO text of a date, the text of a URL, a regular expression or a big number, the
 * message of an error, {@code "[cycle]"} for a reference back into the value and {@code "[too deep]"} below 150 levels.
 *
 * <p>Stateless.
 */
final class Values {

    private static final int MAX_DEPTH = 150;
    private static final double MAX_SAFE_INTEGER = 9007199254740992d;

    private Values() {
    }

    /** The JSON normal form of a value Playwright returned. */
    static Object json(Object value) {
        return json(value, new IdentityHashMap<>(), 0);
    }

    private static Object json(Object value, IdentityHashMap<Object, Boolean> path, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            if (d == Math.rint(d) && Math.abs(d) <= MAX_SAFE_INTEGER) {
                return (long) d;
            }
            return d;
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            return value.toString();
        }
        if (value instanceof Number n) {
            return json(n.doubleValue(), path, depth);
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof URL || value instanceof Pattern || value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Throwable t) {
            return String.valueOf(t.getMessage());
        }
        if (depth >= MAX_DEPTH) {
            return "[too deep]";
        }
        if (value instanceof Map<?, ?> map) {
            if (path.put(value, Boolean.TRUE) != null) {
                return "[cycle]";
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), json(e.getValue(), path, depth + 1));
            }
            path.remove(value);
            return out;
        }
        if (value instanceof Collection<?> collection) {
            if (path.put(value, Boolean.TRUE) != null) {
                return "[cycle]";
            }
            List<Object> out = new ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(json(item, path, depth + 1));
            }
            path.remove(value);
            return out;
        }
        if (value.getClass().isArray()) {
            if (path.put(value, Boolean.TRUE) != null) {
                return "[cycle]";
            }
            int n = Array.getLength(value);
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(json(Array.get(value, i), path, depth + 1));
            }
            path.remove(value);
            return out;
        }
        return value.toString();
    }
}
