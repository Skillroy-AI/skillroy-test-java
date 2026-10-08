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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Values in the JSON-like form the masks and {@link Json} take: maps with string keys, lists, strings, {@code Long},
 * {@code Double}, booleans and null. Records become maps of their components (an actor's turn, a resolution), enums
 * their names, a {@code BigDecimal} its plain text (money stays exact, as the codec writes it), a {@link Duration} its
 * milliseconds, byte arrays their length, and anything else its {@code toString()}.
 *
 * <p>Stateless; safe from any thread.
 */
final class JsonForm {

    private static final int MAX_DEPTH = 100;

    private JsonForm() {
    }

    /** The value in JSON-like form; a new structure, the argument is not changed. */
    static Object of(Object value) {
        return of(value, 0);
    }

    /**
     * Arguments as the MCP SDK hands them over (Jackson's {@code Integer}, {@code BigInteger}, …) in the project's own
     * JSON form, through one write and one parse: what the session and the compiler expect.
     *
     * @throws IllegalArgumentException if they are not JSON
     */
    static Map<String, Object> arguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.parseObject(Json.write(arguments));
        } catch (JsonException e) {
            throw new IllegalArgumentException("the arguments are not JSON: " + e.getMessage(), e);
        }
    }

    private static Object of(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return "<too deep>";
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Long) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Double d) {
            return d.isNaN() || d.isInfinite() ? d.toString() : d;
        }
        if (value instanceof Float f) {
            return f.isNaN() || f.isInfinite() ? f.toString() : (Object) f.doubleValue();
        }
        if (value instanceof BigDecimal b) {
            return b.toPlainString();
        }
        if (value instanceof BigInteger b) {
            return b.bitLength() < 64 ? (Object) b.longValue() : b.toString();
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Duration d) {
            return d.toMillis();
        }
        if (value instanceof Optional<?> o) {
            return o.isPresent() ? of(o.get(), depth + 1) : null;
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), of(e.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof Collection<?> items) {
            List<Object> out = new ArrayList<>(items.size());
            for (Object item : items) {
                out.add(of(item, depth + 1));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> out = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                out.add(of(Array.get(value, i), depth + 1));
            }
            return out;
        }
        if (value instanceof Record record) {
            return record(record, depth);
        }
        return value.toString();
    }

    private static Object record(Record record, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object part;
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                part = accessor.invoke(record);
            } catch (ReflectiveOperationException | RuntimeException e) {
                part = "<unreadable: " + e.getClass().getSimpleName() + ">";
            }
            out.put(component.getName(), of(part, depth + 1));
        }
        return out;
    }
}
