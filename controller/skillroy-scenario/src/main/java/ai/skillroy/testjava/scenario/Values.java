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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.protocol.Json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Value rules shared by references, path filters and matchers: string forms, numeric equality ({@code 2 == "2.00"})
 * and JSON normal form. Stateless; safe from any thread.
 */
final class Values {

    /** JSON number syntax: a leading zero makes a string an identifier ("0100", a barcode), not a number. */
    private static final Pattern NUMERIC = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");
    private static final long MAX_SCALE = 1000;

    private Values() {
    }

    /** The text of a value: strings as is, numbers and booleans as JSON text, maps and lists as compact JSON. */
    static String stringOf(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        return Json.write(value);
    }

    /**
     * The decimal value of a number or of a string holding a decimal number in JSON syntax; null otherwise. Strings
     * with a superfluous leading zero ({@code "0100"}) are text, so that staff codes and barcodes never compare
     * equal to a shorter one.
     */
    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof BigInteger i) {
            return new BigDecimal(i);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : BigDecimal.valueOf(d);
        }
        if (value instanceof String s && s.length() <= 400 && NUMERIC.matcher(s).matches()) {
            try {
                BigDecimal d = new BigDecimal(s);
                // "1e999999999" is a number, but its plain text would not fit in memory: treat it as text.
                return Math.abs((long) d.scale()) <= MAX_SCALE ? d : null;
            } catch (NumberFormatException | ArithmeticException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Expectation equality: numbers and numeric strings (see {@link #decimal}) compare as decimals
     * ({@code 2 == "2.00"}, {@code "3.5" == "3.50"}, but {@code "0100" != "100"}); a boolean equals
     * the string {@code "true"}/{@code "false"} in any case; maps and lists compare element-wise by the same rule;
     * other scalars compare by their text; null equals only null.
     */
    static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Map<?, ?> || b instanceof Map<?, ?>) {
            if (!(a instanceof Map<?, ?> left) || !(b instanceof Map<?, ?> right) || left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : left.entrySet()) {
                if (!right.containsKey(e.getKey()) || !equal(e.getValue(), right.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> || b instanceof List<?>) {
            if (!(a instanceof List<?> left) || !(b instanceof List<?> right) || left.size() != right.size()) {
                return false;
            }
            Iterator<?> i = left.iterator();
            Iterator<?> j = right.iterator();
            while (i.hasNext()) {
                if (!equal(i.next(), j.next())) {
                    return false;
                }
            }
            return true;
        }
        BigDecimal da = decimal(a);
        BigDecimal db = decimal(b);
        if (da != null && db != null) {
            return da.compareTo(db) == 0;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return stringOf(a).equalsIgnoreCase(stringOf(b));
        }
        if (a instanceof Number || b instanceof Number) {
            return false;
        }
        return stringOf(a).equals(stringOf(b));
    }

    /**
     * Deep copy in the JSON codec's normal form (LinkedHashMap, ArrayList, String, Long, Double, Boolean, null), so
     * that a value written to a plan reads back equal and writes back byte-identical.
     */
    static Object normalise(Object value) {
        return Json.parse(Json.write(value));
    }

    /** {@link #normalise} with the keys of every object sorted, for values whose map order a pack controls. */
    static Object normaliseSorted(Object value) {
        return sortKeys(normalise(value));
    }

    /**
     * A copy with the keys of every map sorted ({@link String#compareTo}, the order of canonical JSON), at every depth;
     * lists keep their order and scalars are returned as they are. For text that must not depend on the order in which
     * a value's keys were given (the session export, phase5-contracts.md 12.3).
     */
    static Object sortKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sortKeys(e.getValue()));
            }
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(sortKeys(item));
            }
            return out;
        }
        return value;
    }

    /**
     * {@code text} on one line with every control character made visible (security finding S26): line breaks
     * ({@code \n}, {@code \r}) become spaces; every other C0 control, DEL, C1 control (U+0080 to U+009F), the line
     * and paragraph separators U+2028/U+2029 and the bidi overrides and isolates (U+202A to U+202E, U+2066 to
     * U+2069) become a visible escape (see {@link #escape}). Text built from target data goes into failure messages,
     * JUnit reports and terminals, where an ESC sequence or a bidi override could repaint or reorder what a reader
     * sees.
     */
    static String printableLine(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String replacement = c == '\n' || c == '\r' ? " " : unsafe(c) ? escape(c) : null;
            if (replacement != null && out == null) {
                out = new StringBuilder(text.length() + 16).append(text, 0, i);
            }
            if (out != null) {
                if (replacement != null) {
                    out.append(replacement);
                } else {
                    out.append(c);
                }
            }
        }
        return out == null ? text : out.toString();
    }

    /**
     * True for a character a terminal or log viewer may act on rather than show: C0 controls except {@code \n} and
     * {@code \t}, DEL, C1 controls, U+2028, U+2029 and the bidi overrides and isolates. A carriage return counts:
     * callers that keep line structure allow it only directly before {@code \n}.
     */
    static boolean unsafe(char c) {
        return (c < 0x20 && c != '\n' && c != '\t') || (c >= 0x7F && c <= 0x9F) || c == '\u2028' || c == '\u2029'
                || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069');
    }

    /** The visible form of a character: a backslash, {@code u} and its four lower-case hex digits (ESC: 001b). */
    static String escape(char c) {
        return String.format("\\u%04x", (int) c);
    }

    /**
     * The kind of a value for a message that must not quote it (Phase 8 security fix S176): {@code "a string"},
     * {@code "a number"}, {@code "true"}, {@code "false"}, {@code "null"}, {@code "a mapping"}, {@code "a list"}, else
     * {@code "a value"}. A path that stops at a target's value, and a {@code size} or a matcher that cannot take one,
     * names its kind only: a short numeric credential written bare would pass every mask's rule for short secrets.
     */
    static String kind(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "a string";
        }
        if (value instanceof Number) {
            return "a number";
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Map) {
            return "a mapping";
        }
        if (value instanceof List) {
            return "a list";
        }
        return "a value";
    }

    /** Text for messages, capped at {@code max} characters. */
    static String brief(Object value, int max) {
        String text = value instanceof String s ? "\"" + s + "\"" : safeWrite(value);
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "...";
    }

    /** Compact JSON, or {@code String.valueOf} for a value the codec rejects (NaN, nesting too deep). */
    static String safeWrite(Object value) {
        try {
            return Json.write(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    /** As {@link #safeWrite}, pretty-printed. */
    static String safePretty(Object value) {
        try {
            return Json.writePretty(value);
        } catch (RuntimeException e) {
            return String.valueOf(value) + "\n";
        }
    }
}
