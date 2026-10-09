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

package ai.skillroy.testjava.data.yaml;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads one YAML document into plain values (LinkedHashMap, ArrayList, String, Long, Double, Boolean, null), the same
 * normal form {@code Json.parse} produces, and remembers the source line of every map, list and map key. Jackson
 * trees are never exposed: the wire codec writes a {@code JsonNode} as an array. Moved from the scenario module's
 * package-private {@code YamlReader} (phase2-contracts.md 2.1) with the same rules, so that scenario files and
 * {@code credentials.yaml} are strict in the same way.
 *
 * <p>Rules: one document only; duplicate keys, aliases, binary scalars, {@code .inf}/{@code .nan} and nesting deeper
 * than 100 are errors; so are unquoted numbers that YAML would reinterpret ({@code 0100} as octal, {@code 0x1F},
 * {@code 1_000}); {@code yes}/{@code no}/{@code on}/{@code off} stay strings (only {@code true}/{@code false}
 * are booleans); an empty plain value ({@code key:}) is null while a quoted {@code ""} is an empty string; an
 * integer beyond the long range is an error (it would lose digits as a Double).
 *
 * <p>Stateless apart from one {@link Doc} per call; safe from any thread.
 */
public final class StrictYaml {

    /** Deepest nesting of maps and lists accepted. */
    public static final int MAX_DEPTH = 100;

    /** A decimal number as a person reads it: optional sign, no superfluous leading zero, no underscores. */
    private static final Pattern PLAIN_NUMBER =
            Pattern.compile("[-+]?(0|[1-9]\\d*)?(\\.\\d*)?([eE][-+]?\\d+)?");

    private static final YAMLFactory FACTORY = YAMLFactory.builder()
            .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS)
            .enable(YAMLParser.Feature.EMPTY_STRING_AS_NULL)
            .build();

    private StrictYaml() {
    }

    /** A parsed document with the source lines of its containers and keys. Immutable once returned. */
    public static final class Doc {

        private final Object root;
        private final IdentityHashMap<Object, Integer> lines = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Map<String, Integer>> keyLines = new IdentityHashMap<>();

        private Doc(Object root) {
            this.root = root;
        }

        /** The document's value: a map, a list or a scalar. */
        public Object root() {
            return root;
        }

        /** Line where a map or list of this document starts; 0 for scalars or values not from this document. */
        public int lineOf(Object container) {
            Integer line = container == null ? null : lines.get(container);
            return line == null ? 0 : line;
        }

        /** Line of a key in a map of this document; 0 when unknown. */
        public int keyLine(Object map, String key) {
            Map<String, Integer> byKey = map == null ? null : keyLines.get(map);
            Integer line = byKey == null ? null : byKey.get(key);
            return line == null ? 0 : line;
        }
    }

    /**
     * Reads one document.
     *
     * @throws YamlException on malformed YAML or a rule violation, with the line where known
     */
    public static Doc read(byte[] yaml) throws YamlException {
        Builder builder = new Builder();
        try (JsonParser parser = FACTORY.createParser(yaml)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new YamlException(0, "the file is empty");
            }
            Object root = builder.value(parser, first, 0);
            JsonToken extra = parser.nextToken();
            if (extra != null) {
                throw new YamlException(line(parser), "the file holds more than one YAML document");
            }
            Doc doc = new Doc(root);
            doc.lines.putAll(builder.lines);
            doc.keyLines.putAll(builder.keyLines);
            return doc;
        } catch (JsonProcessingException e) {
            JsonLocation location = e.getLocation();
            int line = location == null ? 0 : Math.max(0, location.getLineNr());
            throw new YamlException(line, "malformed YAML: " + firstLine(e.getOriginalMessage()), true, e);
        } catch (IOException e) {
            throw new YamlException(0, "cannot read YAML: " + e.getMessage(), true, e);
        } catch (RuntimeException e) {
            // SnakeYAML reports some syntax errors as unchecked exceptions.
            throw new YamlException(0, "malformed YAML: " + firstLine(String.valueOf(e.getMessage())), true, e);
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static int line(JsonParser parser) {
        JsonLocation location = parser.currentTokenLocation();
        return location == null ? 0 : Math.max(0, location.getLineNr());
    }

    private static final class Builder {

        private final IdentityHashMap<Object, Integer> lines = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Map<String, Integer>> keyLines = new IdentityHashMap<>();

        Object value(JsonParser parser, JsonToken token, int depth) throws IOException, YamlException {
            int line = line(parser);
            if (parser instanceof YAMLParser yaml && yaml.isCurrentAlias()) {
                throw new YamlException(line, "YAML aliases are not supported");
            }
            switch (token) {
                case START_OBJECT -> {
                    checkDepth(depth, line);
                    Map<String, Object> map = new LinkedHashMap<>();
                    Map<String, Integer> keys = new LinkedHashMap<>();
                    lines.put(map, line);
                    keyLines.put(map, keys);
                    JsonToken next;
                    while ((next = parser.nextToken()) != JsonToken.END_OBJECT) {
                        if (next != JsonToken.FIELD_NAME) {
                            throw new YamlException(line(parser), "unexpected YAML token " + next);
                        }
                        String key = parser.currentName();
                        int keyLine = line(parser);
                        if (map.containsKey(key)) {
                            // The key's text may be a value typed where a key belongs (a secret in a credentials
                            // file): the error says it repeats the document, so a reader of secrets withholds it
                            // (security fix S35).
                            throw new YamlException(keyLine, "duplicate key '" + key + "'", true, null);
                        }
                        JsonToken valueToken = parser.nextToken();
                        keys.put(key, keyLine);
                        map.put(key, value(parser, valueToken, depth + 1));
                    }
                    return map;
                }
                case START_ARRAY -> {
                    checkDepth(depth, line);
                    List<Object> list = new ArrayList<>();
                    lines.put(list, line);
                    JsonToken next;
                    while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                        list.add(value(parser, next, depth + 1));
                    }
                    return list;
                }
                case VALUE_STRING -> {
                    return parser.getText();
                }
                case VALUE_NUMBER_INT -> {
                    plainNumber(parser.getText(), line);
                    if (parser.getNumberType() == JsonParser.NumberType.BIG_INTEGER) {
                        throw new YamlException(line, "integer " + parser.getText() + " does not fit 64 bits and"
                                + " would lose digits: write it in quotes if it is a code", true, null);
                    }
                    return parser.getLongValue();
                }
                case VALUE_NUMBER_FLOAT -> {
                    plainNumber(parser.getText(), line);
                    return finite(parser.getDoubleValue(), line);
                }
                case VALUE_TRUE -> {
                    return Boolean.TRUE;
                }
                case VALUE_FALSE -> {
                    return Boolean.FALSE;
                }
                case VALUE_NULL -> {
                    return null;
                }
                default -> throw new YamlException(line, "unsupported YAML value (" + token + ")");
            }
        }

        /**
         * YAML 1.1 reads {@code 0100} as octal 64, {@code 0x1F} as 31 and {@code 1_000} as 1000. A code written
         * without quotes would silently become another value, so only plain decimal numbers pass.
         */
        private void plainNumber(String text, int line) throws YamlException {
            if (text != null && !PLAIN_NUMBER.matcher(text).matches()) {
                throw new YamlException(line, "ambiguous number " + text + ": write \"" + text
                        + "\" in quotes if it is a code, or a plain decimal number without leading zeros,"
                        + " underscores or a radix prefix", true, null);
            }
        }

        private Double finite(double d, int line) throws YamlException {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new YamlException(line, "numbers must be finite (no .inf or .nan)");
            }
            return d;
        }

        private void checkDepth(int depth, int line) throws YamlException {
            if (depth >= MAX_DEPTH) {
                throw new YamlException(line, "YAML nesting deeper than " + MAX_DEPTH);
            }
        }
    }
}
