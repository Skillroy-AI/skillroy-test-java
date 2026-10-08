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

package ai.skillroy.testjava.inference.planner;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.data.yaml.YamlException;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.ScenarioLoader;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Renders a scenario document (the planner's structured answer, JSON-like) as scenario YAML in the style of the
 * committed scenarios (phase5-contracts.md 10.2 item 5): a leading comment head, the top-level keys in the order of the
 * grammar ({@link ScenarioLoader#KEYS}), a mapping's entries and a list's items one per line as flow collections. A
 * string is written plain only when it can be read back as that string alone; every other string is double-quoted.
 * The text is checked by reading it back with the scenario loader's strict reader: the values must be equal, or the
 * rendering is refused (a defect of this class, never a silently different scenario).
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioYaml {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$./-]*");
    private static final Set<String> WORDS = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n");

    private ScenarioYaml() {
    }

    /**
     * The YAML of a document, with the comment lines first.
     *
     * @throws IllegalArgumentException when the document is not a JSON-like mapping, or its YAML does not read back
     *     to the same values
     */
    static String render(Map<String, Object> document, List<String> comments) {
        Objects.requireNonNull(document, "document");
        Object normal = normal(document);
        StringBuilder out = new StringBuilder();
        for (String comment : comments) {
            for (String line : comment.split("\n", -1)) {
                out.append("#").append(line.isEmpty() ? "" : " " + visible(line)).append('\n');
            }
        }
        List<String> keys = new ArrayList<>();
        for (String key : ScenarioLoader.KEYS) {
            if (document.containsKey(key)) {
                keys.add(key);
            }
        }
        for (String key : new TreeSet<>(document.keySet())) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        for (String key : keys) {
            Object value = document.get(key);
            out.append(key(key)).append(':');
            if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                out.append('\n');
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.append("  ").append(key(String.valueOf(e.getKey()))).append(": ").append(flow(e.getValue()))
                            .append('\n');
                }
            } else if (value instanceof List<?> list && !list.isEmpty()) {
                out.append('\n');
                for (Object item : list) {
                    out.append("  - ").append(flow(item)).append('\n');
                }
            } else {
                out.append(' ').append(flow(value)).append('\n');
            }
        }
        String text = out.toString();
        Object back;
        try {
            back = StrictYaml.read(text.getBytes(StandardCharsets.UTF_8)).root();
        } catch (YamlException e) {
            throw new IllegalArgumentException("the rendered YAML does not read back: " + e.getMessage(), e);
        }
        if (!Objects.equals(normal, back)) {
            throw new IllegalArgumentException("the rendered YAML reads back to other values");
        }
        return text;
    }

    /** The document in the reader's normal form (Long, Double, String, Boolean, maps, lists). */
    private static Object normal(Object value) {
        return Json.parse(Json.write(value));
    }

    private static String flow(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                parts.add(key(String.valueOf(e.getKey())) + ": " + flow(e.getValue()));
            }
            return "{ " + String.join(", ", parts) + " }";
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(flow(item));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        return scalar(value);
    }

    private static String scalar(Object value) {
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
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("a scenario holds no NaN or infinity");
            }
            String plain = BigDecimal.valueOf(d).toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        if (value instanceof BigDecimal d) {
            String plain = d.toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        String text = String.valueOf(value);
        return plain(text) ? text : quote(text);
    }

    private static String key(String key) {
        return plain(key) ? key : quote(key);
    }

    private static boolean plain(String text) {
        return PLAIN.matcher(text).matches() && !WORDS.contains(text.toLowerCase(Locale.ROOT));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == 0x2028 || c == 0x2029
                            || c == 0xfeff) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String visible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }
}
