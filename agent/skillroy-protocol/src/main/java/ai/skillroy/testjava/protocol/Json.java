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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal, strict JSON (RFC 8259) codec. It exists because nothing third-party may be loaded into the target JVM, and
 * it is shared with the controller so the wire format has exactly one implementation.
 *
 * <p>Parsing: objects become {@code LinkedHashMap<String,Object>} (document order), arrays {@code ArrayList<Object>},
 * strings {@code String}, {@code true}/{@code false} {@code Boolean}, {@code null} {@code null}; a number without
 * fraction or exponent that fits a {@code long} becomes {@code Long}, every other number {@code Double}. When an
 * object repeats a key the last value wins (as in most JSON libraries) and the key keeps its first position.
 * Rejected: trailing commas, comments, unquoted or single-quoted keys, leading zeros, NaN/Infinity, numbers outside
 * the double range, unescaped control characters, invalid escapes, a byte-order mark, trailing content, and nesting
 * deeper than 200. A lone surrogate written as an escape (D800-DFFF without its pair) is accepted and kept as it is.
 * Every error carries the character offset.
 *
 * <p>Writing accepts {@code Map} (keys via {@code String.valueOf}), {@code Iterable}, object and primitive arrays,
 * {@code Number}, {@code CharSequence}, {@code Boolean}, {@code Enum} (its {@code name()}) and {@code null}; any other
 * object is written as the string of its {@code toString()}. {@code java.nio.file.Path} and {@code Throwable} are
 * written as their {@code toString()} although some of them are {@code Iterable}, because iterating them never
 * ends (a one-element path contains itself). Key order is the map's iteration order, except in
 * {@link #writeCanonical}. NaN, Infinity and nesting deeper than 200 (including cycles) throw {@link JsonException}.
 *
 * <p>Stateless; every method is safe to call from any thread. Values being written must not be mutated
 * concurrently.
 */
public final class Json {

    /** Maximum nesting of objects and arrays, on input and on output. */
    static final int MAX_DEPTH = 200;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    /**
     * Parses one complete JSON document (any value at the top level, surrounded by optional whitespace).
     *
     * @throws JsonException if {@code text} is null or not valid JSON; the message names the offset
     */
    public static Object parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("cannot parse null text");
        }
        return new Parser(text).document();
    }

    /**
     * Parses a document whose top-level value must be an object.
     *
     * @throws JsonException if the text is not valid JSON or its top-level value is not an object
     */
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            int offset = 0;
            while (offset < text.length() && isWhitespace(text.charAt(offset))) {
                offset++;
            }
            throw new JsonException("expected a JSON object but found " + Coerce.typeName(value), offset);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    /** Compact JSON with no insignificant whitespace, keys in iteration order. */
    public static String write(Object value) {
        Writer writer = new Writer(false, false);
        writer.value(value);
        return writer.out.toString();
    }

    /**
     * Human-readable JSON for files that are committed and diffed: two-space indent, ": " after keys, empty
     * containers as {@code {}} and {@code []}, and {@code "\n"} after every line including the last.
     */
    public static String writePretty(Object value) {
        Writer writer = new Writer(true, false);
        writer.value(value);
        writer.out.append('\n');
        return writer.out.toString();
    }

    /**
     * Compact JSON with object keys sorted recursively by {@link String#compareTo} (UTF-16 code units, as in
     * RFC 8785), for hashing. Array order is kept. Two maps that differ only in key order produce the same text.
     */
    public static String writeCanonical(Object value) {
        Writer writer = new Writer(false, true);
        writer.value(value);
        return writer.out.toString();
    }

    /** JSON text of a finite double; mathematically integral values below 1e21 are written without exponent. */
    static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new JsonException("cannot write " + d + ": JSON has no NaN or Infinity");
        }
        if (d == 0) {
            return Double.doubleToRawLongBits(d) == 0L ? "0.0" : "-0.0";
        }
        String text = Double.toString(d);
        if (text.indexOf('E') >= 0 && Math.abs(d) < 1e21 && d == Math.rint(d)) {
            return new BigDecimal(text).toBigInteger().toString() + ".0";
        }
        return text;
    }

    /**
     * True for values that are written as their {@code toString()} even though they may be {@code Iterable}:
     * iterating a one-element {@code Path} yields the path itself, and a {@code SQLException} iterates its own chain.
     */
    static boolean isOpaque(Object v) {
        return v instanceof Path || v instanceof Throwable;
    }

    static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static String describe(char c) {
        if (c >= 0x20 && c < 0x7f) {
            return "'" + c + "'";
        }
        StringBuilder sb = new StringBuilder("U+");
        for (int shift = 12; shift >= 0; shift -= 4) {
            sb.append(Character.toUpperCase(HEX[(c >> shift) & 0xF]));
        }
        return sb.toString();
    }

    /** Recursive-descent parser over one input string. Single use, not thread-safe. */
    private static final class Parser {
        private final String s;
        private final int n;
        private int pos;
        private int depth;

        Parser(String s) {
            this.s = s;
            this.n = s.length();
        }

        Object document() {
            skipWhitespace();
            Object value = value();
            skipWhitespace();
            if (pos < n) {
                char c = s.charAt(pos);
                if (c == '/') {
                    throw new JsonException("comments are not allowed", pos);
                }
                throw new JsonException("unexpected " + describe(c) + " after the end of the JSON value", pos);
            }
            return value;
        }

        private Object value() {
            if (pos >= n) {
                throw new JsonException("unexpected end of input, expected a value", pos);
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || isDigit(c)) {
                        return number();
                    }
                    throw unexpected(c, "a value");
            }
        }

        private Map<String, Object> object() {
            enter();
            pos++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (pos < n && s.charAt(pos) == '}') {
                pos++;
                depth--;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= n) {
                    throw new JsonException("unexpected end of input, expected an object key", pos);
                }
                char c = s.charAt(pos);
                if (c != '"') {
                    if (c == '}') {
                        throw new JsonException("trailing comma in object", pos);
                    }
                    if (c == '/') {
                        throw new JsonException("comments are not allowed", pos);
                    }
                    throw new JsonException("expected a double-quoted object key but found " + describe(c), pos);
                }
                String key = string();
                skipWhitespace();
                if (pos >= n) {
                    throw new JsonException("unexpected end of input, expected ':'", pos);
                }
                if (s.charAt(pos) != ':') {
                    throw unexpected(s.charAt(pos), "':' after an object key");
                }
                pos++;
                skipWhitespace();
                // A repeated key keeps its first position and takes the last value.
                map.put(key, value());
                skipWhitespace();
                if (pos >= n) {
                    throw new JsonException("unexpected end of input, expected ',' or '}'", pos);
                }
                c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    depth--;
                    return map;
                } else {
                    throw unexpected(c, "',' or '}'");
                }
            }
        }

        private List<Object> array() {
            enter();
            pos++;
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (pos < n && s.charAt(pos) == ']') {
                pos++;
                depth--;
                return list;
            }
            while (true) {
                skipWhitespace();
                if (pos < n && s.charAt(pos) == ']') {
                    throw new JsonException("trailing comma in array", pos);
                }
                list.add(value());
                skipWhitespace();
                if (pos >= n) {
                    throw new JsonException("unexpected end of input, expected ',' or ']'", pos);
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    depth--;
                    return list;
                } else {
                    throw unexpected(c, "',' or ']'");
                }
            }
        }

        private String string() {
            int start = pos;
            pos++;
            StringBuilder sb = null;
            int runStart = pos;
            while (true) {
                if (pos >= n) {
                    throw new JsonException("unterminated string starting", start);
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String result;
                    if (sb == null) {
                        result = s.substring(runStart, pos);
                    } else {
                        result = sb.append(s, runStart, pos).toString();
                    }
                    pos++;
                    return result;
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, runStart, pos);
                    escape(sb);
                    runStart = pos;
                    continue;
                }
                if (c < 0x20) {
                    throw new JsonException("unescaped control character " + describe(c) + " in string", pos);
                }
                pos++;
            }
        }

        /** Decodes the escape sequence at {@code pos} (a backslash) and leaves {@code pos} after it. */
        private void escape(StringBuilder sb) {
            int start = pos;
            pos++;
            if (pos >= n) {
                throw new JsonException("unterminated escape sequence", start);
            }
            char e = s.charAt(pos);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    int code = 0;
                    for (int i = 1; i <= 4; i++) {
                        if (pos + i >= n) {
                            throw new JsonException("truncated \\u escape, expected 4 hex digits", start);
                        }
                        int digit = hexValue(s.charAt(pos + i));
                        if (digit < 0) {
                            throw new JsonException("invalid \\u escape, expected 4 hex digits", start);
                        }
                        code = (code << 4) | digit;
                    }
                    sb.append((char) code);
                    pos += 4;
                    break;
                default:
                    throw new JsonException("invalid escape sequence \\" + (e >= 0x20 && e < 0x7f
                            ? String.valueOf(e) : describe(e)), start);
            }
            pos++;
        }

        private Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
                if (pos >= n || !isDigit(s.charAt(pos))) {
                    throw new JsonException("expected a digit after '-'", pos);
                }
            }
            if (s.charAt(pos) == '0') {
                pos++;
                if (pos < n && isDigit(s.charAt(pos))) {
                    throw new JsonException("leading zeros are not allowed", start);
                }
            } else {
                while (pos < n && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            boolean integral = true;
            if (pos < n && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                if (pos >= n || !isDigit(s.charAt(pos))) {
                    throw new JsonException("expected a digit after the decimal point", pos);
                }
                while (pos < n && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < n && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (pos < n && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= n || !isDigit(s.charAt(pos))) {
                    throw new JsonException("expected a digit in the exponent", pos);
                }
                while (pos < n && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String token = s.substring(start, pos);
            if (integral) {
                try {
                    return Long.valueOf(Long.parseLong(token));
                } catch (NumberFormatException outsideLong) {
                    // falls through to Double
                }
            }
            double d = Double.parseDouble(token);
            if (Double.isInfinite(d)) {
                d = parseNormalised(token);
            }
            if (Double.isInfinite(d)) {
                throw new JsonException("number outside the range of a double", start);
            }
            return Double.valueOf(d);
        }

        /**
         * Second opinion on a token {@code Double.parseDouble} reported as infinite. Java 8 compares the written
         * exponent with a limit that ignores zeros between the decimal point and the first significant digit, so it
         * reports in-range numbers such as {@code 0.000000000000000001e326} (1e308) as infinite; later runtimes do
         * not. Both sides of the wire must read the same text as the same number, so the token is rewritten with one
         * digit before the decimal point ({@code 1.0e308}), a form every runtime parses correctly, and parsed again.
         * Linear in the token length.
         *
         * @param token a number token of the JSON grammar that has a fraction or an exponent, or is too long for a
         *     {@code long}
         * @return its value, infinite only if its magnitude really exceeds the double range
         */
        private static double parseNormalised(String token) {
            boolean negative = token.charAt(0) == '-';
            int mantissaStart = negative ? 1 : 0;
            int e = token.indexOf('e');
            if (e < 0) {
                e = token.indexOf('E');
            }
            int mantissaEnd = e < 0 ? token.length() : e;
            int dot = token.indexOf('.');
            int integerEnd = dot < 0 ? mantissaEnd : dot;
            String digits = dot < 0 ? token.substring(mantissaStart, mantissaEnd)
                    : token.substring(mantissaStart, dot) + token.substring(dot + 1, mantissaEnd);
            int first = 0;
            while (first < digits.length() && digits.charAt(first) == '0') {
                first++;
            }
            double infinite = negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            if (first == digits.length()) {
                return infinite; // all zeros: not a token parseDouble calls infinite; keep its verdict
            }
            // The written exponent, saturated far outside the double range (it may have any number of digits).
            long exponent = 0;
            if (e >= 0) {
                int p = e + 1;
                boolean negativeExponent = token.charAt(p) == '-';
                if (negativeExponent || token.charAt(p) == '+') {
                    p++;
                }
                for (; p < token.length() && exponent < 1000000L; p++) {
                    exponent = exponent * 10 + (token.charAt(p) - '0');
                }
                if (negativeExponent) {
                    exponent = -exponent;
                }
            }
            // Decimal exponent of the first significant digit.
            long adjusted = exponent + (integerEnd - mantissaStart) - first - 1;
            if (adjusted > 308) {
                return infinite;
            }
            StringBuilder normal = new StringBuilder(digits.length() - first + 16);
            if (negative) {
                normal.append('-');
            }
            normal.append(digits.charAt(first)).append('.');
            if (first + 1 < digits.length()) {
                normal.append(digits, first + 1, digits.length());
            } else {
                normal.append('0');
            }
            normal.append('e').append(adjusted);
            return Double.parseDouble(normal.toString());
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("invalid literal, expected '" + word + "'", pos);
            }
            pos += word.length();
            return value;
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw new JsonException("nesting depth exceeds " + MAX_DEPTH, pos);
            }
        }

        private void skipWhitespace() {
            while (pos < n && isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private JsonException unexpected(char c, String expected) {
            if (c == '/') {
                return new JsonException("comments are not allowed", pos);
            }
            return new JsonException("unexpected " + describe(c) + ", expected " + expected, pos);
        }
    }

    /** Serializer into one StringBuilder. Single use, not thread-safe. */
    private static final class Writer {
        final StringBuilder out = new StringBuilder();
        private final boolean pretty;
        private final boolean canonical;
        private int depth;

        Writer(boolean pretty, boolean canonical) {
            this.pretty = pretty;
            this.canonical = canonical;
        }

        void value(Object v) {
            if (v == null) {
                out.append("null");
            } else if (v instanceof CharSequence) {
                string(v.toString());
            } else if (v instanceof Boolean) {
                out.append(((Boolean) v).booleanValue() ? "true" : "false");
            } else if (v instanceof Number) {
                number((Number) v);
            } else if (v instanceof Map) {
                map((Map<?, ?>) v);
            } else if (isOpaque(v)) {
                other(v);
            } else if (v instanceof Iterable) {
                array(((Iterable<?>) v).iterator());
            } else if (v instanceof Object[]) {
                array(Arrays.asList((Object[]) v).iterator());
            } else if (v.getClass().isArray()) {
                int length = Array.getLength(v);
                List<Object> items = new ArrayList<Object>(length);
                for (int i = 0; i < length; i++) {
                    items.add(Array.get(v, i));
                }
                array(items.iterator());
            } else if (v instanceof Enum) {
                string(((Enum<?>) v).name());
            } else {
                other(v);
            }
        }

        private void other(Object v) {
            String text = v.toString();
            if (text == null) {
                out.append("null");
            } else {
                string(text);
            }
        }

        private void number(Number num) {
            if (num instanceof Long || num instanceof Integer || num instanceof Short || num instanceof Byte
                    || num instanceof AtomicInteger || num instanceof AtomicLong) {
                out.append(num.longValue());
            } else if (num instanceof Double) {
                out.append(formatDouble(num.doubleValue()));
            } else if (num instanceof Float) {
                float f = num.floatValue();
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    throw new JsonException("cannot write " + f + ": JSON has no NaN or Infinity");
                }
                out.append(formatDouble(Double.parseDouble(Float.toString(f))));
            } else if (num instanceof BigInteger || num instanceof BigDecimal) {
                out.append(num.toString());
            } else {
                String text = num.toString();
                if (text != null && isJsonNumber(text)) {
                    out.append(text);
                } else {
                    out.append(formatDouble(num.doubleValue()));
                }
            }
        }

        private void map(Map<?, ?> map) {
            enter();
            if (map.isEmpty()) {
                out.append("{}");
                depth--;
                return;
            }
            List<String> keys = new ArrayList<String>(map.size());
            List<Object> values = new ArrayList<Object>(map.size());
            Set<String> seen = null;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object rawKey = entry.getKey();
                String key = String.valueOf(rawKey);
                if (!(rawKey instanceof String) && seen == null) {
                    seen = new HashSet<String>(keys);
                }
                if (seen != null && !seen.add(key)) {
                    throw new JsonException("two map keys write as the same JSON key \"" + key + "\"");
                }
                keys.add(key);
                values.add(entry.getValue());
            }
            Integer[] order = new Integer[keys.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = Integer.valueOf(i);
            }
            if (canonical) {
                final List<String> k = keys;
                Arrays.sort(order, (a, b) -> k.get(a.intValue()).compareTo(k.get(b.intValue())));
            }
            out.append('{');
            for (int i = 0; i < order.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                newline();
                int index = order[i].intValue();
                string(keys.get(index));
                out.append(pretty ? ": " : ":");
                value(values.get(index));
            }
            depth--;
            newline();
            out.append('}');
        }

        private void array(Iterator<?> items) {
            enter();
            if (!items.hasNext()) {
                out.append("[]");
                depth--;
                return;
            }
            out.append('[');
            boolean first = true;
            while (items.hasNext()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline();
                value(items.next());
            }
            depth--;
            newline();
            out.append(']');
        }

        void string(String str) {
            out.append('"');
            int len = str.length();
            int from = 0;
            for (int i = 0; i < len; i++) {
                char c = str.charAt(i);
                boolean escape;
                if (c == '"' || c == '\\' || c < 0x20 || c == '\u2028' || c == '\u2029') {
                    escape = true;
                } else if (Character.isHighSurrogate(c)) {
                    if (i + 1 < len && Character.isLowSurrogate(str.charAt(i + 1))) {
                        i++;
                        escape = false;
                    } else {
                        escape = true;
                    }
                } else {
                    escape = Character.isLowSurrogate(c);
                }
                if (!escape) {
                    continue;
                }
                out.append(str, from, i);
                switch (c) {
                    case '"':
                        out.append("\\\"");
                        break;
                    case '\\':
                        out.append("\\\\");
                        break;
                    case '\b':
                        out.append("\\b");
                        break;
                    case '\f':
                        out.append("\\f");
                        break;
                    case '\n':
                        out.append("\\n");
                        break;
                    case '\r':
                        out.append("\\r");
                        break;
                    case '\t':
                        out.append("\\t");
                        break;
                    default:
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append(HEX[(c >> shift) & 0xF]);
                        }
                }
                from = i + 1;
            }
            out.append(str, from, len);
            out.append('"');
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw new JsonException("cannot write nesting deeper than " + MAX_DEPTH + " (cyclic structure?)");
            }
        }

        private void newline() {
            if (pretty) {
                out.append('\n');
                for (int i = 0; i < depth; i++) {
                    out.append("  ");
                }
            }
        }
    }

    /** True if {@code text} is exactly one JSON number token. */
    static boolean isJsonNumber(String text) {
        int i = 0;
        int n = text.length();
        if (i < n && text.charAt(i) == '-') {
            i++;
        }
        if (i >= n) {
            return false;
        }
        if (text.charAt(i) == '0') {
            i++;
        } else if (text.charAt(i) >= '1' && text.charAt(i) <= '9') {
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
        } else {
            return false;
        }
        if (i < n && text.charAt(i) == '.') {
            i++;
            int digits = i;
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
            if (i == digits) {
                return false;
            }
        }
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            i++;
            if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                i++;
            }
            int digits = i;
            while (i < n && isDigit(text.charAt(i))) {
                i++;
            }
            if (i == digits) {
                return false;
            }
        }
        return i == n;
    }
}
