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

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Package-internal value coercions and deep copies shared by {@link JsonView} and the wire types, so both apply the
 * same rules. Stateless; safe from any thread.
 */
final class Coerce {

    private static final double TWO_63 = 0x1p63;

    /**
     * Deepest nesting a wire object may hold. Lower than the codec's limit so that a payload or result value still
     * fits when it is wrapped in an event, a result, a JSON-RPC response and a batch.
     */
    static final int MAX_VALUE_DEPTH = Json.MAX_DEPTH - 16;

    /**
     * Longest text (after trimming) accepted as a number. Any long or double literal is far shorter; the bound keeps
     * {@link BigDecimal}'s parse, whose cost grows with the square of the digit count, from pinning a thread on a
     * hostile multi-megabyte digit string (security finding S13).
     */
    static final int MAX_NUMERIC_TEXT = 64;

    private Coerce() {
    }

    /** The integral value of {@code v}, or null if it has none (fraction, out of range, not numeric). */
    static Long toLong(Object v) {
        if (v instanceof Long) {
            return (Long) v;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte || v instanceof AtomicInteger
                || v instanceof AtomicLong) {
            return Long.valueOf(((Number) v).longValue());
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && d >= -TWO_63 && d < TWO_63) {
                return Long.valueOf((long) d);
            }
            return null;
        }
        if (v instanceof BigInteger) {
            return ((BigInteger) v).bitLength() < 64 ? Long.valueOf(((BigInteger) v).longValue()) : null;
        }
        if (v instanceof BigDecimal) {
            try {
                return Long.valueOf(((BigDecimal) v).longValueExact());
            } catch (ArithmeticException notIntegral) {
                return null;
            }
        }
        if (v instanceof Number || v instanceof CharSequence) {
            try {
                return Long.valueOf(decimalOf(v instanceof CharSequence ? (CharSequence) v : String.valueOf(v))
                        .longValueExact());
            } catch (NumberFormatException | ArithmeticException notIntegral) {
                return null;
            }
        }
        return null;
    }

    /** The finite double value of {@code v}, or null if it is not numeric. */
    static Double toDouble(Object v) {
        if (v instanceof Float) {
            return Double.valueOf(Double.parseDouble(v.toString()));
        }
        if (v instanceof Number) {
            return Double.valueOf(((Number) v).doubleValue());
        }
        if (v instanceof CharSequence) {
            try {
                double d = decimalOf((CharSequence) v).doubleValue();
                return Double.isInfinite(d) ? null : Double.valueOf(d);
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }

    /**
     * The decimal value of a numeric text, refusing over-long text before any parse.
     *
     * @throws NumberFormatException if the text is longer than {@link #MAX_NUMERIC_TEXT} after trimming, or not a
     *     decimal number
     */
    private static BigDecimal decimalOf(CharSequence text) {
        // Cheap pre-check on the raw length, so that a huge value is neither copied nor trimmed.
        if (text.length() > MAX_NUMERIC_TEXT + 16) {
            throw new NumberFormatException("numeric text too long");
        }
        String s = text.toString().trim();
        if (s.length() > MAX_NUMERIC_TEXT) {
            throw new NumberFormatException("numeric text too long");
        }
        return new BigDecimal(s);
    }

    /** {@code Boolean} as is, the strings "true"/"false" in any case; otherwise null. */
    static Boolean toBool(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof CharSequence) {
            String t = v.toString().trim();
            if ("true".equalsIgnoreCase(t)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(t)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** The string form of a scalar (numbers in decimal); null for objects, arrays and null. */
    static String toStr(Object v) {
        if (v == null || v instanceof Map || v instanceof Iterable || v.getClass().isArray()) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            return Json.formatDouble(v instanceof Float ? Double.parseDouble(v.toString()) : d);
        }
        if (v instanceof Enum) {
            return ((Enum<?>) v).name();
        }
        return v.toString();
    }

    /** Compact JSON for toString(); falls back to {@code String.valueOf} so toString() never throws. */
    static String safeWrite(Object value) {
        try {
            return Json.write(value);
        } catch (JsonException unwritable) {
            return String.valueOf(value);
        }
    }

    /** "an object", "a string", ... for error messages. Never includes the value itself. */
    static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map) {
            return "an object";
        }
        if (v instanceof Iterable || v.getClass().isArray()) {
            return "an array";
        }
        if (v instanceof CharSequence || v instanceof Character || v instanceof Enum) {
            return "a string";
        }
        if (v instanceof Number) {
            return "a number";
        }
        if (v instanceof Boolean) {
            return "a boolean";
        }
        return "a " + v.getClass().getSimpleName();
    }

    /**
     * Deep, unmodifiable copy of a JSON-like value in normal form, so that a wire object is immutable, always
     * writable and equal to its own JSON round trip: maps become insertion-ordered maps with String keys, iterables
     * and arrays become lists, Integer/Short/Byte/atomics become Long, Float becomes the Double with the same decimal
     * form, enums become their name, and any object the writer would write as its {@code toString()} becomes that
     * string now (a snapshot, in case the object is mutable). BigDecimal and BigInteger beyond the long range are
     * kept (they are immutable and written as numbers).
     *
     * @throws JsonException for NaN or Infinity and for nesting deeper than {@link #MAX_VALUE_DEPTH} (cycles
     *     included)
     */
    static Object freeze(Object v) {
        return freeze(v, 0);
    }

    /** {@link #freeze} of a map; null stays null. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<String, ?> map) {
        return map == null ? null : (Map<String, Object>) freeze(map, 0);
    }

    private static Object freeze(Object v, int depth) {
        if (v == null || v instanceof String || v instanceof Long || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Number) {
            return freezeNumber((Number) v);
        }
        if (v instanceof Map) {
            checkDepth(depth);
            Map<?, ?> source = (Map<?, ?>) v;
            Map<String, Object> copy = new LinkedHashMap<String, Object>(Math.max(4, source.size() * 2));
            for (Map.Entry<?, ?> e : source.entrySet()) {
                copy.put(String.valueOf(e.getKey()), freeze(e.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (Json.isOpaque(v)) {
            return v.toString();
        }
        if (v instanceof Iterable) {
            checkDepth(depth);
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (Iterable<?>) v) {
                copy.add(freeze(item, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        if (v.getClass().isArray()) {
            checkDepth(depth);
            int length = Array.getLength(v);
            List<Object> copy = new ArrayList<Object>(length);
            for (int i = 0; i < length; i++) {
                copy.add(freeze(Array.get(v, i), depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        if (v instanceof Enum) {
            return ((Enum<?>) v).name();
        }
        return v.toString();
    }

    private static Object freezeNumber(Number n) {
        if (n instanceof Double) {
            requireFinite(n.doubleValue());
            return n;
        }
        if (n instanceof Float) {
            return Double.valueOf(requireFinite(Double.parseDouble(n.toString())));
        }
        if (n instanceof Integer || n instanceof Short || n instanceof Byte || n instanceof AtomicInteger
                || n instanceof AtomicLong) {
            return Long.valueOf(n.longValue());
        }
        if (n instanceof BigInteger) {
            return ((BigInteger) n).bitLength() < 64 ? (Object) Long.valueOf(n.longValue()) : n;
        }
        if (n instanceof BigDecimal) {
            return n;
        }
        // Any other Number may be mutable (LongAdder, ...): take its value now.
        Long integral = toLong(n);
        if (integral != null) {
            return integral;
        }
        return Double.valueOf(requireFinite(n.doubleValue()));
    }

    private static double requireFinite(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new JsonException("cannot hold " + d + ": JSON has no NaN or Infinity");
        }
        return d;
    }

    private static void checkDepth(int depth) {
        if (depth >= MAX_VALUE_DEPTH) {
            throw new JsonException("nesting depth exceeds " + MAX_VALUE_DEPTH + " (cyclic structure?)");
        }
    }
}
