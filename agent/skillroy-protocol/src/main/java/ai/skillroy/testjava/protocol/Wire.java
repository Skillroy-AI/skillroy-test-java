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

import java.util.List;
import java.util.Map;

/**
 * Package-internal field readers for the {@code fromJson} methods of the wire types. Shape errors become
 * {@link JsonException}s naming the type and the field. Stateless; safe from any thread.
 */
final class Wire {

    private Wire() {
    }

    static Map<String, Object> requireObject(Map<String, Object> json, String type) {
        if (json == null) {
            throw new JsonException(type + ": JSON object is null");
        }
        return json;
    }

    static long requireLong(Map<String, Object> json, String key, String type) {
        Object v = json.get(key);
        if (v == null) {
            throw missing(key, type);
        }
        return toLong(v, key, type);
    }

    static long optLong(Map<String, Object> json, String key, long dflt, String type) {
        Object v = json.get(key);
        return v == null ? dflt : toLong(v, key, type);
    }

    static String requireString(Map<String, Object> json, String key, String type) {
        String s = optString(json, key, type);
        if (s == null) {
            throw missing(key, type);
        }
        return s;
    }

    static String optString(Map<String, Object> json, String key, String type) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof CharSequence) {
            return v.toString();
        }
        throw mismatch(key, type, "a string", v);
    }

    static boolean optBool(Map<String, Object> json, String key, boolean dflt, String type) {
        Object v = json.get(key);
        if (v == null) {
            return dflt;
        }
        Boolean b = Coerce.toBool(v);
        if (b == null) {
            throw mismatch(key, type, "a boolean", v);
        }
        return b.booleanValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> optObject(Map<String, Object> json, String key, String type) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        throw mismatch(key, type, "an object", v);
    }

    @SuppressWarnings("unchecked")
    static List<Object> optList(Map<String, Object> json, String key, String type) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof List) {
            return (List<Object>) v;
        }
        throw mismatch(key, type, "an array", v);
    }

    private static long toLong(Object v, String key, String type) {
        Long l = Coerce.toLong(v);
        if (l == null || v instanceof Boolean) {
            throw mismatch(key, type, "an integer", v);
        }
        return l.longValue();
    }

    private static JsonException missing(String key, String type) {
        return new JsonException(type + ": missing required field \"" + key + "\"");
    }

    static JsonException mismatch(String key, String type, String expected, Object actual) {
        return new JsonException(type + ": field \"" + key + "\" must be " + expected + " but was "
                + Coerce.typeName(actual));
    }
}
