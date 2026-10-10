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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one rule for a capture's bytes on a text route (D159 as completed by the Phase 6 security fix, pass 2,
 * P2-L6-F2, P2-L6-F3, P2-L6-S1): a capture's bytes leave the controller to a client or a model only as an image, and
 * only under the inference settings' {@code images: true} (the MCP server's blob resource behind a tool's link; an
 * image part of a model request); on every text route (a run log or journal record, an evidence file, a tool's
 * result, a resource served as text, a fact, an event, a model request's text) they are elided, whatever
 * {@code images} says. The executor writes {@code run.jsonl}, the session journal and the {@code failure/} files
 * through it, {@code PromptMask} (everything a model or an MCP client reads as text) applies it, and so does the MCP
 * server's {@code Masking}.
 *
 * <p>What counts as an image's bytes:
 * <ul>
 *   <li>in a value: the string of a map's {@code base64} entry that is base64 text (the shape every capture primitive
 *       answers, {@code {format: "png", width, height, base64}}), replaced by {@code "<png: N bytes>"} ({@code png}
 *       when the map's {@code format} says so or the bytes are a PNG's; otherwise the format, or {@code base64});
 *       every other entry kept, so a record still says what was captured and how large;</li>
 *   <li>in a text (and in every string of a value): a run of base64 text that starts with a PNG's signature
 *       ({@value #PNG_SIGNATURE}, the base64 of a PNG's first eight bytes), as in a data URI, a value cut or written
 *       into another text, replaced by {@code <png: N bytes>}; and the base64 string of a JSON member named
 *       {@code "base64"} (also written with escaped quotes, as inside another JSON string), replaced by
 *       {@code <base64: N bytes>}. A JSON escape of {@code /} and a line break inside the run belong to it.</li>
 * </ul>
 * N is the number of bytes the base64 text encodes. A value nested deeper than {@value #MAX_DEPTH} levels becomes
 * {@code "<too deep>"}. Idempotent: an elided text holds no base64 run. Stateless; safe from any thread.
 */
public final class Images {

    /** The base64 text every PNG starts with: the encoding of its eight-byte signature. */
    public static final String PNG_SIGNATURE = "iVBORw0KGgo";

    /** The key of an image's bytes in a capture's value. */
    public static final String BASE64 = "base64";

    /** Deeper than this, a value is replaced (the JSON codec writes 200 levels at most). */
    static final int MAX_DEPTH = 256;

    private Images() {
    }

    /**
     * The value with every image's bytes elided: a new structure for a map or a collection (as a list), the text rule
     * for a string, anything else as it is. The argument is not changed.
     */
    public static Object elided(Object value) {
        return elided(value, 0);
    }

    private static Object elided(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return "<too deep>";
        }
        if (value instanceof String s) {
            return elidedText(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (BASE64.equals(key) && e.getValue() instanceof String bytes && base64Text(bytes)) {
                    out.put(key, elision(kind(map.get("format"), bytes), bytes, 0, bytes.length()));
                } else {
                    out.put(key, elided(e.getValue(), depth + 1));
                }
            }
            return out;
        }
        if (value instanceof Collection<?> items) {
            List<Object> out = new ArrayList<>(items.size());
            for (Object item : items) {
                out.add(elided(item, depth + 1));
            }
            return out;
        }
        return value;
    }

    /** The text with every PNG's base64 run and every {@code "base64"} member's base64 string elided; null stays. */
    public static String elidedText(String text) {
        if (text == null || (!text.contains(PNG_SIGNATURE) && !text.contains(BASE64))) {
            return text;
        }
        return base64Members(pngRuns(text));
    }

    /** Every run that starts with {@link #PNG_SIGNATURE}, elided. Linear in the text. */
    private static String pngRuns(String text) {
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (true) {
            int start = text.indexOf(PNG_SIGNATURE, from);
            if (start < 0) {
                break;
            }
            int end = runEnd(text, start, true);
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, start).append(elision("png", text, start, end));
            copied = end;
            from = end;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /** The base64 string of every JSON member named {@code "base64"} ({@code \"base64\"} as well), elided. */
    private static String base64Members(String text) {
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(BASE64, from);
            if (at < 0) {
                break;
            }
            from = at + BASE64.length();
            if (at == 0 || text.charAt(at - 1) != '"') {
                continue;
            }
            int i = closingQuote(text, from);
            if (i < 0) {
                continue;
            }
            i = skipSpaces(text, i);
            if (i >= text.length() || text.charAt(i) != ':') {
                continue;
            }
            i = skipSpaces(text, i + 1);
            int start = openingQuote(text, i);
            if (start < 0) {
                continue;
            }
            int end = runEnd(text, start, false);
            if (end == start || end < text.length() && closingQuote(text, end) < 0) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, start).append(elision(BASE64, text, start, end));
            copied = end;
            from = end;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /** After a closing {@code "} or {@code \"} at {@code i}, its end; -1 when there is none. */
    private static int closingQuote(String text, int i) {
        if (i < text.length() && text.charAt(i) == '"') {
            return i + 1;
        }
        if (i + 1 < text.length() && text.charAt(i) == '\\' && text.charAt(i + 1) == '"') {
            return i + 2;
        }
        return -1;
    }

    /** After an opening {@code "} or {@code \"} at {@code i}, the string's start; -1 when there is none. */
    private static int openingQuote(String text, int i) {
        return closingQuote(text, i);
    }

    private static int skipSpaces(String text, int i) {
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /**
     * The end of the base64 run at {@code start}: the alphabet, {@code =}, a JSON escape of {@code /}, a line break
     * written as a JSON escape ({@code \n}, {@code \r}), and with {@code lineBreaks} a raw line break where a
     * line of MIME base64 ends (after a whole number of four-symbol groups, no padding yet, more base64 after it).
     */
    private static int runEnd(String text, int start, boolean lineBreaks) {
        int i = start;
        long symbols = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (alphabet(c)) {
                symbols++;
                i++;
            } else if (c == '\\' && i + 1 < text.length() && "/rn".indexOf(text.charAt(i + 1)) >= 0) {
                symbols += text.charAt(i + 1) == '/' ? 1 : 0;
                i += 2;
            } else if (lineBreaks && (c == '\n' || c == '\r') && symbols % 4 == 0 && text.charAt(i - 1) != '='
                    && lineContinues(text, i)) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** True when the line break at {@code i} (a CR, LF or CRLF) is followed by base64 text. */
    private static boolean lineContinues(String text, int i) {
        int next = text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? i + 2 : i + 1;
        return next < text.length() && alphabet(text.charAt(next));
    }

    private static boolean alphabet(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/'
                || c == '=';
    }

    /** True for a non-empty base64 text (the alphabet, padding and line breaks only). */
    private static boolean base64Text(String s) {
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (alphabet(c)) {
                any = true;
            } else if (c != '\n' && c != '\r') {
                return false;
            }
        }
        return any;
    }

    /** {@code png} when the format or the bytes say so; otherwise a plain format word, or {@code base64}. */
    private static String kind(Object format, String bytes) {
        String trimmed = bytes.strip();
        if (trimmed.startsWith(PNG_SIGNATURE)) {
            return "png";
        }
        if (format instanceof String f && f.matches("[A-Za-z0-9]{1,16}")) {
            return f.toLowerCase(Locale.ROOT);
        }
        return BASE64;
    }

    /** {@code <kind: N bytes>} for the base64 text between {@code start} and {@code end}. */
    private static String elision(String kind, String text, int start, int end) {
        long symbols = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 < end && text.charAt(i + 1) == '/') {
                    symbols++;
                }
                i++;
            } else if (alphabet(c) && c != '=') {
                symbols++;
            }
        }
        return "<" + kind + ": " + (symbols * 3 / 4) + " bytes>";
    }
}
