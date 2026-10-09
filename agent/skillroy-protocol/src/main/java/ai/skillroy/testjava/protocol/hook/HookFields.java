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

package ai.skillroy.testjava.protocol.hook;

import ai.skillroy.testjava.protocol.JsonException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Package-internal field readers for the {@code fromJson} methods of the hook wire types (the parent package's own
 * readers are package-private there). Shape errors become {@link JsonException}s naming the type and the field.
 * Stateless; safe from any thread.
 */
final class HookFields {

    private HookFields() {
    }

    static Map<String, Object> requireObject(Map<String, Object> json, String type) {
        if (json == null) {
            throw new JsonException(type + ": JSON object is null");
        }
        return json;
    }

    /** Refuses a key outside {@code known} (the strict types: {@code HookSpec} and its parts). */
    static void refuseUnknown(Map<String, Object> json, String type, String... known) {
        Set<String> allowed = new HashSet<String>(Arrays.asList(known));
        for (String key : json.keySet()) {
            if (!allowed.contains(key)) {
                throw new JsonException(type + ": unknown field \"" + key + "\"");
            }
        }
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
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        throw mismatch(key, type, "a boolean", v);
    }

    /** The integer value, or null when absent. */
    static Long optLong(Map<String, Object> json, String key, String type) {
        Object v = json.get(key);
        if (v == null) {
            return null;
        }
        Long l = integral(v);
        if (l == null) {
            throw mismatch(key, type, "an integer", v);
        }
        return l;
    }

    static long requireLong(Map<String, Object> json, String key, String type) {
        Long l = optLong(json, key, type);
        if (l == null) {
            throw missing(key, type);
        }
        return l.longValue();
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

    /** A list of strings, or null when absent. */
    static List<String> optStrings(Map<String, Object> json, String key, String type) {
        List<Object> raw = optList(json, key, type);
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<String>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof CharSequence)) {
                throw mismatch(key, type, "an array of strings", raw);
            }
            out.add(item.toString());
        }
        return out;
    }

    /** A list of non-negative integers (argument indexes); empty when absent. */
    static List<Integer> indexes(Map<String, Object> json, String key, String type) {
        List<Object> raw = optList(json, key, type);
        if (raw == null) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<Integer>(raw.size());
        for (Object item : raw) {
            Long l = integral(item);
            if (l == null || l.longValue() > Integer.MAX_VALUE || l.longValue() < Integer.MIN_VALUE) {
                throw mismatch(key, type, "an array of integers", raw);
            }
            if (l.longValue() < 0) {
                throw new JsonException(type + ": field \"" + key + "\" holds the negative index " + l);
            }
            out.add(Integer.valueOf(l.intValue()));
        }
        return out;
    }

    static <E extends Enum<E>> E requireEnum(Map<String, Object> json, String key, Class<E> enumType, String type) {
        String name = requireString(json, key, type);
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException unknown) {
            throw new JsonException(type + ": unknown " + key + " \"" + name + "\"");
        }
    }

    /** The value as a long when it is an integral number, else null. */
    static Long integral(Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return Long.valueOf(((Number) v).longValue());
        }
        try {
            if (v instanceof BigInteger) {
                return Long.valueOf(((BigInteger) v).longValueExact());
            }
            if (v instanceof BigDecimal) {
                return Long.valueOf(((BigDecimal) v).longValueExact());
            }
        } catch (ArithmeticException notIntegral) {
            return null;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
                return Long.valueOf((long) d);
            }
        }
        return null;
    }

    static JsonException missing(String key, String type) {
        return new JsonException(type + ": missing required field \"" + key + "\"");
    }

    static JsonException mismatch(String key, String type, String expected, Object actual) {
        String name = actual == null ? "null" : actual instanceof Map ? "an object" : actual instanceof List
                ? "an array" : actual instanceof CharSequence ? "a string" : actual instanceof Boolean ? "a boolean"
                : actual instanceof Number ? "a number" : actual.getClass().getSimpleName();
        return new JsonException(type + ": field \"" + key + "\" must be " + expected + " but was " + name);
    }
}
