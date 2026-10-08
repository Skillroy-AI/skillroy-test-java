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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading a tool call's arguments (JSON-like, after {@link JsonForm#arguments}): typed getters that refuse with a
 * {@link ToolError}, and the inverse of the strict tool schema of {@code ToolProjection}, which made every optional
 * key required and nullable: a null under a key the original schema does not require is dropped again, so that a verb
 * or a primitive sees an absent key, as a scenario would give it.
 *
 * <p>Stateless; safe from any thread.
 */
final class Arguments {

    private static final int MAX_DEPTH = 50;

    private Arguments() {
    }

    /**
     * A copy of {@code args} without the null values under keys the schema does not require, recursively through the
     * schema's {@code properties}, {@code items} and {@code anyOf} branches; a schema without properties (free-form)
     * drops nothing below its level. A null schema drops the top level's null values only.
     */
    static Map<String, Object> dropNullOptionals(Map<String, Object> args, Map<String, Object> schema) {
        if (schema == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            args.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, v);
                }
            });
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) drop(args, schema, 0);
        return out;
    }

    private static Object drop(Object value, Object schema, int depth) {
        if (depth > MAX_DEPTH || !(schema instanceof Map<?, ?> s)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            if (!(s.get("properties") instanceof Map<?, ?>) && s.get("anyOf") instanceof List<?> branches) {
                for (Object branch : branches) {
                    if (branch instanceof Map<?, ?> b && b.get("properties") instanceof Map<?, ?>) {
                        return drop(value, b, depth + 1);
                    }
                }
                return value;
            }
            Map<?, ?> properties = s.get("properties") instanceof Map<?, ?> p ? p : null;
            Set<String> required = new HashSet<>();
            if (s.get("required") instanceof List<?> list) {
                for (Object name : list) {
                    required.add(String.valueOf(name));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (e.getValue() == null && properties != null && !required.contains(key)) {
                    continue;
                }
                out.put(key, properties == null ? e.getValue() : drop(e.getValue(), properties.get(key), depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list && s.get("items") instanceof Map<?, ?> items) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(drop(item, items, depth + 1));
            }
            return out;
        }
        return value;
    }

    /**
     * A text argument.
     *
     * @throws ToolError if a required one is missing, or it is not a string
     */
    static String text(Map<String, Object> args, String key, boolean required) throws ToolError {
        Object value = args.get(key);
        if (value == null) {
            if (required) {
                throw new ToolError("the argument '" + key + "' is required");
            }
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ToolError("the argument '" + key + "' must be a string");
        }
        return text;
    }

    /**
     * An integer argument within bounds, or the default when it is absent or null.
     *
     * @throws ToolError if it is not an integral number
     */
    static int integer(Map<String, Object> args, String key, int dflt, int min, int max) throws ToolError {
        Long value = longValue(args, key);
        if (value == null) {
            return dflt;
        }
        return (int) Math.max(min, Math.min(max, value));
    }

    /**
     * A long argument, or null.
     *
     * @throws ToolError if it is present and not an integral number
     */
    static Long longValue(Map<String, Object> args, String key) throws ToolError {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return d.longValue();
        }
        throw new ToolError("the argument '" + key + "' must be an integer");
    }

    /**
     * An object argument; an absent or null one is empty.
     *
     * @throws ToolError if it is not an object
     */
    static Map<String, Object> map(Map<String, Object> args, String key) throws ToolError {
        Object value = args.get(key);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ToolError("the argument '" + key + "' must be an object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * A list of texts, or null when absent.
     *
     * @throws ToolError if it is not a list of strings
     */
    static List<String> texts(Map<String, Object> args, String key) throws ToolError {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new ToolError("the argument '" + key + "' must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new ToolError("the argument '" + key + "' must be a list of strings");
            }
            out.add(text);
        }
        return out;
    }
}
