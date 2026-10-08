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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a file holds a secret as a value: the matcher of the secret scans of phase5-contracts.md 15.2 item 8
 * ({@code InferenceChecks}) and of the session's headless acceptance ({@code SessionExportTest}), which read the
 * files the AI layer writes (recordings, {@code inference.jsonl}, journals, exports, triage files, reports).
 *
 * <p>A file is read by its kind: a {@code .json} file is parsed, a {@code .jsonl} file line by line, and only their
 * strings are looked at (map keys included), never their numbers, since a duration, a time or a sequence number may
 * have the digits of a four-digit password, which the fixtures always hold as a string. An {@code .xml} file gives its
 * attribute values and its text; any other file, or one that does not parse as its kind, is one text. Each string is
 * then read in three layers:
 * <ol>
 *   <li>a string that is itself a JSON object or array (JSON text held in JSON, an escaped JSON string) is parsed, and
 *       its strings are read the same way;</li>
 *   <li>a labelled block ({@code <target-state>} … {@code </target-state>}, any lower-case label; the innermost opening
 *       tag before a closing tag) whose content is a JSON object or array, as a prompt part, a recording and a log hold
 *       the target's state and events, is parsed, and its strings are read the same way, so that a number in it is
 *       not taken for a password;</li>
 *   <li>what remains is split on whitespace, quotes (double, single, backtick), commas, colons and backslashes, and a
 *       secret is found when it is the whole string or one of the tokens (a token's trailing full stops dropped: a
 *       sentence may end with it). So {@code "1111",} inside JSON text and {@code \"1111\"} inside escaped JSON text
 *       are found, while {@code durationMs=1029} is one token: splitting on every other character would make a
 *       password such as seed B's {@code 1029} match a duration.</li>
 * </ol>
 * Nesting is read to a bounded depth; deeper text is split as it stands.
 *
 * <p>Stateless; safe from any thread.
 */
final class SecretScan {

    /** How deep JSON text inside JSON text (or inside a labelled block) is parsed. */
    static final int MAX_DEPTH = 8;
    /** The characters a remaining text is split on. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\"'`,:\\\\]+");
    /** The closing tag of a labelled block; its opening tag is the nearest one before it. */
    private static final Pattern CLOSING = Pattern.compile("</([a-z][a-z0-9-]{0,63})>");
    private static final Pattern XML_ATTRIBUTE = Pattern.compile("=\"([^\"]*)\"");
    /** The characters of context a hit shows on each side. */
    private static final int CONTEXT = 40;

    private SecretScan() {
    }

    /**
     * The places where {@code file} holds one of {@code secrets} as a value, read by the file's kind (see the class
     * comment); each hit is {@code <secret>: ...<context>...}. Empty when there is none.
     */
    static List<String> leaks(Path file, Set<String> secrets) throws IOException {
        String name = file.getFileName().toString();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<String> strings = new ArrayList<>();
        if (name.endsWith(".jsonl")) {
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    parsedOrText(line, strings);
                }
            }
        } else if (name.endsWith(".json")) {
            parsedOrText(text, strings);
        } else if (name.endsWith(".xml")) {
            Matcher attribute = XML_ATTRIBUTE.matcher(text);
            while (attribute.find()) {
                strings.add(attribute.group(1));
            }
            strings.add(text.replaceAll("<[^>]*>", " "));
        } else {
            strings.add(text);
        }
        List<String> hits = new ArrayList<>();
        for (String string : strings) {
            scan(string, secrets, 0, hits);
        }
        return hits;
    }

    /** The places where one text (of no particular kind) holds one of {@code secrets} as a value. */
    static List<String> leaks(String text, Set<String> secrets) {
        List<String> hits = new ArrayList<>();
        scan(text, secrets, 0, hits);
        return hits;
    }

    /** The strings of {@code text} parsed as JSON, or the text itself when it does not parse. */
    private static void parsedOrText(String text, List<String> out) {
        try {
            strings(Json.parse(text), out);
        } catch (JsonException e) {
            out.add(text);
        }
    }

    private static void scan(String string, Set<String> secrets, int depth, List<String> hits) {
        Object json = depth < MAX_DEPTH ? jsonText(string) : null;
        if (json != null) {
            for (String inner : strings(json)) {
                scan(inner, secrets, depth + 1, hits);
            }
            return;
        }
        StringBuilder rest = new StringBuilder(string.length());
        int from = 0;
        if (depth < MAX_DEPTH && string.contains("</")) {
            Matcher closing = CLOSING.matcher(string);
            while (closing.find()) {
                String open = "<" + closing.group(1) + ">";
                int start = string.lastIndexOf(open, closing.start());
                if (start < from) {
                    continue;
                }
                Object content = jsonText(string.substring(start + open.length(), closing.start()));
                if (content != null) {
                    rest.append(string, from, start).append(' ');
                    for (String inner : strings(content)) {
                        scan(inner, secrets, depth + 1, hits);
                    }
                    from = closing.end();
                }
            }
        }
        rest.append(string, from, string.length());
        String text = rest.toString();
        Set<String> tokens = new HashSet<>();
        tokens.add(string.strip());
        for (String token : SEPARATORS.split(text)) {
            tokens.add(token);
            int end = token.length();
            while (end > 0 && token.charAt(end - 1) == '.') {
                end--;
            }
            tokens.add(token.substring(0, end));
        }
        for (String secret : secrets) {
            if (!secret.isEmpty() && tokens.contains(secret)) {
                int at = Math.max(0, text.indexOf(secret));
                hits.add(secret + ": ..." + text.substring(Math.max(0, at - CONTEXT), Math.min(text.length(), at
                        + secret.length() + CONTEXT)) + "...");
            }
        }
    }

    /** The value of a text that is a JSON object or array, or null for any other text. */
    private static Object jsonText(String text) {
        String t = text.strip();
        if (t.length() < 2 || !(t.charAt(0) == '{' && t.charAt(t.length() - 1) == '}'
                || t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']')) {
            return null;
        }
        try {
            return Json.parse(t);
        } catch (JsonException e) {
            return null;
        }
    }

    private static List<String> strings(Object json) {
        List<String> out = new ArrayList<>();
        strings(json, out);
        return out;
    }

    /** Every string of a parsed JSON value, map keys included, depth first; numbers and literals are not strings. */
    private static void strings(Object value, List<String> out) {
        if (value instanceof String string) {
            out.add(string);
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.add(String.valueOf(e.getKey()));
                strings(e.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                strings(item, out);
            }
        }
    }
}
