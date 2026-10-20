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

package ai.skillroy.testjava.junit;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where a run's outputs hold a planted value (phase8-contracts.md 5.1, D544): the scan a layer's tests run over what a
 * scenario run, a session or a client wrote, to prove that a password, a personal value or any other planted token
 * never reached a file. It is the reader of the sample's secret scans (phase5-contracts.md 15.2 item 8), made public.
 *
 * <p>A file is read by its kind: a {@code .json} file is parsed, a {@code .jsonl} file line by line, and only their
 * strings are looked at (map keys included), never their numbers, since a duration, a time or a sequence number may
 * have the digits of a four-digit password, which fixtures hold as a string. An {@code .xml} file gives its attribute
 * values and its text; any other file, or one that does not parse as its kind, is one text (read as UTF-8, a
 * malformed byte replaced, so a binary file cannot hide an ASCII value). A PNG (by its name or its signature) is never
 * read, only its name. Each string is then read in three layers:
 * <ol>
 *   <li>a string that is itself a JSON object or array (JSON text held in JSON, an escaped JSON string) is parsed, and
 *       its strings are read the same way;</li>
 *   <li>a labelled block ({@code <target-state>} … {@code </target-state>}, any lower-case label; the innermost opening
 *       tag before a closing tag) whose content is a JSON object or array, as a prompt part, a recording and a log hold
 *       the target's state and events, is parsed, and its strings are read the same way, so that a number in it is
 *       not taken for a planted value;</li>
 *   <li>what remains is split on whitespace, quotes (double, single, backtick), commas, colons and backslashes, and a
 *       planted value is found when it is the whole string or one of the tokens (a token's trailing full stops
 *       dropped: a sentence may end with it). So {@code "1111",} inside JSON text and {@code \"1111\"} inside escaped
 *       JSON text are found, while {@code durationMs=1029} is one token.</li>
 * </ol>
 * Nesting is read to a bounded depth ({@link #MAX_DEPTH}); deeper text is split as it stands.
 *
 * <p>The directory form takes a {@link Scope}. {@link Scope#ALL} reads every regular file under the directory (names
 * included). {@link Scope#EVENTS} reads only what carries event payloads: the records of type {@code call},
 * {@code prompt}, {@code capture} and {@code step} of a {@code run.jsonl} and of a session's {@code journal.jsonl}
 * (a line that does not parse is read whole), every {@code failure/events.json} and every {@code *.xml} (JUnit
 * reports); the scope exists because a personal value in an event may legitimately be on screen too (a status line in
 * a snapshot), so a whole-run scan would find the snapshot, not the leak.
 *
 * <p>Stateless; safe from any thread.
 */
public final class OutputScan {

    /** Which files of a directory the directory form reads. */
    public enum Scope {
        /** Every regular file, its name and, unless a PNG, its content. */
        ALL,
        /**
         * The event-carrying records and files: {@code run.jsonl} and {@code journal.jsonl} records of type
         * {@code call}, {@code prompt}, {@code capture} or {@code step}; {@code failure/events.json}; {@code *.xml}.
         */
        EVENTS
    }

    /**
     * One place a planted value was found.
     *
     * @param file the file
     * @param line the 1-based line of a {@code .jsonl} record, or of the value's first literal occurrence in any other
     *     file; 0 for the file's name or a value found only after decoding (an escape)
     * @param token the planted value
     */
    public record Hit(Path file, long line, String token) {
        public Hit {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(token, "token");
        }
    }

    /** How deep JSON text inside JSON text (or inside a labelled block) is parsed. */
    static final int MAX_DEPTH = 8;
    /** The characters a remaining text is split on. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\"'`,:\\\\]+");
    /** The closing tag of a labelled block; its opening tag is the nearest one before it. */
    private static final Pattern CLOSING = Pattern.compile("</([a-z][a-z0-9-]{0,63})>");
    private static final Pattern XML_ATTRIBUTE = Pattern.compile("=\"([^\"]*)\"");
    /** The characters of context a hit shows on each side. */
    private static final int CONTEXT = 40;
    /** The record types {@link Scope#EVENTS} reads in a record file. */
    private static final Set<String> EVENT_RECORDS = Set.of("call", "prompt", "capture", "step");
    /** The record files: the run's log and a session's journal. */
    private static final Set<String> RECORD_FILES = Set.of("run.jsonl", "journal.jsonl");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private OutputScan() {
    }

    /**
     * Every place under {@code runDir} where a file holds one of {@code planted} as a value, read by the file's kind
     * (see the class comment); regular files only (a symbolic link is not followed), in the order of their paths,
     * each place once.
     *
     * @throws IOException if the directory cannot be walked or a file cannot be read
     */
    public static List<Hit> leaks(Path runDir, Set<String> planted, Scope scope) throws IOException {
        Objects.requireNonNull(runDir, "runDir");
        Objects.requireNonNull(planted, "planted");
        Objects.requireNonNull(scope, "scope");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(runDir)) {
            files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
        Set<Hit> hits = new LinkedHashSet<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (scope == Scope.EVENTS && !eventFile(file, name)) {
                continue;
            }
            for (Found found : scanText(name, planted)) {
                hits.add(new Hit(file, 0, found.token()));
            }
            byte[] bytes = Files.readAllBytes(file);
            if (png(name, bytes)) {
                continue;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (name.endsWith(".jsonl")) {
                boolean recordsOnly = scope == Scope.EVENTS && RECORD_FILES.contains(name);
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].isBlank()) {
                        continue;
                    }
                    List<String> strings = new ArrayList<>();
                    if (recordsOnly) {
                        Object record = parsed(lines[i]);
                        if (record instanceof Map<?, ?> map && !EVENT_RECORDS.contains(String.valueOf(map.get(
                                "type")))) {
                            continue;
                        }
                    }
                    parsedOrText(lines[i], strings);
                    for (Found found : scanAll(strings, planted)) {
                        hits.add(new Hit(file, i + 1, found.token()));
                    }
                }
            } else {
                for (Found found : scanAll(strings(name, text), planted)) {
                    hits.add(new Hit(file, lineOf(text, found.token()), found.token()));
                }
            }
        }
        return List.copyOf(hits);
    }

    /**
     * The places where one regular file holds one of {@code planted} as a value: its name and, unless it is a PNG, its
     * content, read by the file's kind (see the class comment); each hit is {@code <value>: ...<context>...}. Empty
     * when there is none.
     *
     * @throws IOException if the file cannot be read
     */
    public static List<String> leaks(Path file, Set<String> planted) throws IOException {
        String name = file.getFileName().toString();
        List<String> hits = format(scanText(name, planted));
        byte[] bytes = Files.readAllBytes(file);
        if (png(name, bytes)) {
            return hits;
        }
        hits.addAll(format(scanAll(strings(name, new String(bytes, StandardCharsets.UTF_8)), planted)));
        return hits;
    }

    /**
     * The places where one text (of no particular kind: a value, a line, a file's name) holds one of {@code planted}
     * as a value; each hit is {@code <value>: ...<context>...}. Empty when there is none.
     */
    public static List<String> leaksIn(String text, Set<String> planted) {
        return format(scanText(text, planted));
    }

    /** The same as {@link #leaksIn}: the form the sample's callers of the per-file scan use for a file's name. */
    public static List<String> leaks(String text, Set<String> planted) {
        return leaksIn(text, planted);
    }

    /** A planted value and the text around it. */
    private record Found(String token, String context) {
    }

    private static boolean eventFile(Path file, String name) {
        if (RECORD_FILES.contains(name) || name.endsWith(".xml")) {
            return true;
        }
        Path parent = file.getParent();
        return "events.json".equals(name) && parent != null && parent.getFileName() != null
                && "failure".equals(parent.getFileName().toString());
    }

    private static boolean png(String name, byte[] bytes) {
        return name.toLowerCase(Locale.ROOT).endsWith(".png") || bytes.length >= PNG_SIGNATURE.length
                && Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE);
    }

    /** The 1-based line of the first literal occurrence of {@code token} in {@code text}, or 0. */
    private static long lineOf(String text, String token) {
        int at = text.indexOf(token);
        if (at < 0) {
            return 0;
        }
        long line = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The strings of a whole file's text, by the file's kind. */
    private static List<String> strings(String name, String text) {
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
        return strings;
    }

    private static List<String> format(List<Found> found) {
        List<String> out = new ArrayList<>(found.size());
        for (Found f : found) {
            out.add(f.token() + ": ..." + f.context() + "...");
        }
        return out;
    }

    private static List<Found> scanAll(List<String> strings, Set<String> planted) {
        List<Found> hits = new ArrayList<>();
        for (String string : strings) {
            scan(string, planted, 0, hits);
        }
        return hits;
    }

    private static List<Found> scanText(String text, Set<String> planted) {
        List<Found> hits = new ArrayList<>();
        scan(text, planted, 0, hits);
        return hits;
    }

    /** The parsed JSON of a text, or null when it does not parse. */
    private static Object parsed(String text) {
        try {
            return Json.parse(text);
        } catch (JsonException e) {
            return null;
        }
    }

    /** The strings of {@code text} parsed as JSON, or the text itself when it does not parse. */
    private static void parsedOrText(String text, List<String> out) {
        try {
            strings(Json.parse(text), out);
        } catch (JsonException e) {
            out.add(text);
        }
    }

    private static void scan(String string, Set<String> planted, int depth, List<Found> hits) {
        Object json = depth < MAX_DEPTH ? jsonText(string) : null;
        if (json != null) {
            for (String inner : strings(json)) {
                scan(inner, planted, depth + 1, hits);
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
                        scan(inner, planted, depth + 1, hits);
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
        for (String value : planted) {
            if (!value.isEmpty() && tokens.contains(value)) {
                int at = Math.max(0, text.indexOf(value));
                hits.add(new Found(value, text.substring(Math.max(0, at - CONTEXT), Math.min(text.length(),
                        at + value.length() + CONTEXT))));
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
