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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes JSON-like values as YAML text that {@code StrictYaml} (the scenario loader's reader) reads back to equal
 * values, in the style of the committed scenarios: block mappings and sequences at the top, flow collections
 * ({@code { key: value, … }}, {@code [a, b]}) inside a line, a value moved onto block lines of its own when its line
 * would pass {@link #WIDTH} characters. Deterministic: the same value gives the same text, keys in the order given.
 *
 * <p>Scalars: a string is written plain only when it can only be read back as that string (it starts with a letter,
 * {@code _} or {@code $} and holds letters, digits, {@code _ $ . / -} only, and is not a word YAML gives a meaning:
 * {@code true}, {@code false}, {@code null}, {@code yes}, {@code no}, {@code on}, {@code off}, {@code y}, {@code n});
 * every other string is double-quoted with escapes for {@code "}, {@code \} and control characters. A whole number is
 * written as digits, a fraction in plain decimal notation with at least one decimal place, a {@code BigDecimal} as
 * its plain text (read back as a number), {@code true}, {@code false} and {@code null} as such. A comment is put on one
 * line with its control characters made visible.
 *
 * <p>Not thread-safe; one writer builds one document.
 */
final class YamlText {

    /** The longest line the writer aims for; a longer value goes onto block lines when it can. */
    static final int WIDTH = 118;

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$./-]*");
    private static final Set<String> WORDS = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n");

    private final StringBuilder out = new StringBuilder();

    /** The text so far. */
    String text() {
        return out.toString();
    }

    /** The number of lines written so far. */
    int lines() {
        int count = 0;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** A line as it is (no newline in it). */
    YamlText line(String text) {
        out.append(text).append('\n');
        return this;
    }

    /** A full-line comment at {@code indent}. */
    YamlText comment(int indent, String text) {
        out.append(" ".repeat(indent)).append("# ").append(oneLine(text)).append('\n');
        return this;
    }

    /**
     * {@code key: value} at {@code indent}: on one line in flow style when it fits, else {@code key:} and the value
     * on block lines of its own (a mapping's entries, a sequence's items) at {@code indent + 2}.
     *
     * @param comment a trailing comment for the line, or null
     */
    YamlText entry(int indent, String key, Object value, String comment) {
        String head = " ".repeat(indent) + key(key) + ":";
        block(head, indent, value, comment);
        return this;
    }

    /** {@code - value} at {@code indent}, likewise. */
    YamlText item(int indent, Object value, String comment) {
        String head = " ".repeat(indent) + "-";
        if (value instanceof Map<?, ?> map && map.size() == 1) {
            // A single-key mapping as a step is written "- key: value", the scenarios' own form.
            Map.Entry<?, ?> only = map.entrySet().iterator().next();
            block(head + " " + key(String.valueOf(only.getKey())) + ":", indent + 2, only.getValue(), comment);
            return this;
        }
        block(head, indent, value, comment);
        return this;
    }

    private void block(String head, int indent, Object value, String comment) {
        String flow = flow(value);
        String line = head + " " + flow;
        if (line.length() <= WIDTH || !(value instanceof Map<?, ?> || value instanceof List<?>) || isEmpty(value)) {
            out.append(line).append(trailing(comment)).append('\n');
            return;
        }
        out.append(head).append(trailing(comment)).append('\n');
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                entry(indent + 2, String.valueOf(e.getKey()), e.getValue(), null);
            }
        } else {
            for (Object element : (List<?>) value) {
                item(indent + 2, element, null);
            }
        }
    }

    private static boolean isEmpty(Object value) {
        return value instanceof Map<?, ?> map && map.isEmpty() || value instanceof List<?> list && list.isEmpty();
    }

    private static String trailing(String comment) {
        return comment == null || comment.isBlank() ? "" : "    # " + oneLine(comment);
    }

    /** A value in flow style, on one line. */
    static String flow(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            StringBuilder text = new StringBuilder("{ ");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    text.append(", ");
                }
                first = false;
                text.append(key(String.valueOf(e.getKey()))).append(": ").append(flow(e.getValue()));
            }
            return text.append(" }").toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            StringBuilder text = new StringBuilder("[");
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    text.append(", ");
                }
                first = false;
                text.append(flow(element));
            }
            return text.append(']').toString();
        }
        return scalar(value);
    }

    /** A key: plain when safe, else double-quoted. */
    static String key(String key) {
        return plain(key) ? key : quote(key);
    }

    /** A scalar as YAML reads it back. */
    static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof BigDecimal d) {
            String text = d.toPlainString();
            return text.contains(".") ? text : text + ".0";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("YAML for a scenario cannot hold " + d);
            }
            String text = BigDecimal.valueOf(d).toPlainString();
            return text.contains(".") ? text : text + ".0";
        }
        String text = String.valueOf(value);
        return plain(text) ? text : quote(text);
    }

    private static boolean plain(String text) {
        return PLAIN.matcher(text).matches() && !WORDS.contains(text.toLowerCase(Locale.ROOT));
    }

    /** A double-quoted YAML string with JSON-style escapes. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == ' ' || c == ' ' || c == '﻿'
                            || Character.isSurrogate(c) && !validSurrogate(text, i)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static boolean validSurrogate(String text, int i) {
        char c = text.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
    }

    private static String oneLine(String text) {
        return Values.printableLine(text == null ? "" : text).strip();
    }
}
