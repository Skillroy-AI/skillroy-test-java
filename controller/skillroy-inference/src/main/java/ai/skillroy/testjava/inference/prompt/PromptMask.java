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

package ai.skillroy.testjava.inference.prompt;

import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masking before any prompt (phase5-contracts.md 6): nothing reaches a model that a bindings file could not hold, and
 * less. Applied by every request builder to every part before a {@code ModelRequest} is built, and by the recording
 * gateway once more on the way to disk. It adds a layer to the agent's redaction and the controller's
 * {@code RowMask} and {@code SecretMask}, which run first; it replaces none of them.
 *
 * <p>The rules, as 6.2 states them:
 * <ul>
 *   <li><b>By value.</b> Every secret value the run knows (the {@link SecretMask}: credentials with the environment's
 *       {@code masking.minLengthInText}, fixture secrets) is replaced wherever it appears, as for run outputs.</li>
 *   <li><b>By key.</b> A JSON-like key that {@code SecretKeys.looksSecret} or a module-supplied pattern matches has its
 *       value replaced, and so has the value of a {@code key: value} or {@code key=value} run inside a text.</li>
 *   <li><b>By column.</b> A row keeps only the allow-listed columns of its source, after the pack's row masks; a
 *       personal column's values become {@value #PERSONAL}; a source without an allow-list gives no row.</li>
 *   <li><b>By origin.</b> Text read from the target or written by a person enters a prompt only in a labelled block
 *       ({@link #block}), inside a user message; the system text declares such blocks to be data.</li>
 *   <li><b>Captures.</b> An image part is refused unless images are enabled (false until Phase 7).</li>
 *   <li><b>Bounded.</b> A text is cut at {@value #MAX_TEXT} characters and a row sample at {@value #MAX_ROWS} rows,
 *       with {@value #TRUNCATED} appended.</li>
 * </ul>
 *
 * <p>Immutable; safe to share between threads.
 */
public final class PromptMask {

    /** The longest text a prompt part keeps. */
    public static final int MAX_TEXT = 100_000;
    /** The most rows a row sample keeps. */
    public static final int MAX_ROWS = 20;
    /** What a cut text ends with. */
    public static final String TRUNCATED = "[truncated]";
    /** What a personal column's value becomes. */
    public static final String PERSONAL = "<personal>";
    /** What a secret becomes. */
    public static final String REDACTED = SecretKeys.REDACTED;
    /** The labels of {@link #block}: text from the target, and a person's own words. */
    public static final Set<String> LABELS = Set.of("target-ui", "target-event", "target-state", "person");

    private static final int MAX_DEPTH = 200;
    /** A secret may straddle the cut: the text is masked this far past it before being cut. */
    private static final int CUT_MARGIN = SecretMask.MAX_MIN_SUBSTRING;
    private static final Pattern LABEL_TAG = Pattern.compile("<(/?)(target-ui|target-event|target-state|person)\\b",
            Pattern.CASE_INSENSITIVE);

    private final SecretMask secrets;
    private final List<SqlAllowList> allow;
    private final List<RowMask> rowMasks;
    private final List<String> extraKeys;
    private final boolean images;
    private final Pattern keyPattern;

    private PromptMask(SecretMask secrets, List<SqlAllowList> allow, List<RowMask> rowMasks, List<String> extraKeys,
                       boolean images) {
        this.secrets = secrets == null ? SecretMask.NONE : secrets;
        this.allow = allow == null ? List.of() : List.copyOf(allow);
        this.rowMasks = rowMasks == null ? List.of() : List.copyOf(rowMasks);
        this.extraKeys = List.copyOf(extraKeys);
        this.images = images;
        StringBuilder alternation = new StringBuilder(SecretKeys.DEFAULT_KEYS);
        for (String extra : this.extraKeys) {
            alternation.append('|').append(extra);
        }
        this.keyPattern = Pattern.compile(alternation.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * The mask of a run (phase5-contracts.md 6.2): images refused, the default secret keys.
     *
     * @param secrets the run's secret values ({@code Credentials.mask()} plus fixture secrets); null knows none
     * @param allow the pack's allow-lists; null is none
     * @param rowMasks the pack's row masks, applied first; null is none
     */
    public static PromptMask of(SecretMask secrets, List<SqlAllowList> allow, List<RowMask> rowMasks) {
        return new PromptMask(secrets, allow, rowMasks, List.of(), false);
    }

    /** A mask that knows no secret value, no allow-list and no row mask: the key rule, the bounds and the image rule. */
    public static PromptMask keysOnly() {
        return of(SecretMask.NONE, List.of(), List.of());
    }

    /**
     * This mask with more secret-key patterns (regular expressions matched case-insensitively anywhere in a key: the
     * module-supplied patterns the agent reports, A21).
     *
     * @throws IllegalArgumentException if a pattern is not a valid regular expression
     */
    public PromptMask withKeys(Collection<String> patterns) {
        List<String> keys = new ArrayList<>(extraKeys);
        for (String p : patterns) {
            Pattern.compile(Objects.requireNonNull(p, "pattern"));
            keys.add("(?:" + p + ")");
        }
        return new PromptMask(secrets, allow, rowMasks, keys, images);
    }

    /** This mask with images allowed or refused ({@code inference.yaml} {@code images}). */
    public PromptMask withImages(boolean enabled) {
        return new PromptMask(secrets, allow, rowMasks, extraKeys, enabled);
    }

    /** True when an image part passes. */
    public boolean imagesEnabled() {
        return images;
    }

    /** The secret values this mask knows. */
    public SecretMask secrets() {
        return secrets;
    }

    /** True when a JSON-like key names a secret (the default keys and the module-supplied patterns). */
    public boolean secretKey(String key) {
        return key != null && keyPattern.matcher(key).find();
    }

    /**
     * A text through the rules: cut at {@link #MAX_TEXT} characters (masked far enough past the cut that a secret
     * straddling it is replaced first), every known secret value replaced, and the value of every {@code key: value}
     * or {@code key=value} run whose key names a secret replaced. Null stays null.
     */
    public String text(String text) {
        if (text == null) {
            return null;
        }
        boolean cut = text.length() > MAX_TEXT;
        String head = cut ? text.substring(0, Math.min(text.length(), MAX_TEXT + CUT_MARGIN)) : text;
        String masked = keyRule(secrets.apply(head));
        if (cut) {
            masked = (masked.length() > MAX_TEXT ? masked.substring(0, MAX_TEXT) : masked) + TRUNCATED;
        }
        return masked;
    }

    /**
     * The value of every {@code key: value} or {@code key=value} run whose key holds a secret word, replaced. Linear in
     * the text: each secret word found is widened to its key token, and the separator and the value are read by hand
     * (a regular expression with a free prefix before the word backtracks quadratically on a long run of letters).
     */
    private String keyRule(String text) {
        Matcher word = keyPattern.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && word.find(from)) {
            int keyEnd = word.end();
            while (keyEnd < text.length() && keyChar(text.charAt(keyEnd))) {
                keyEnd++;
            }
            int i = keyEnd;
            if (i < text.length() && (text.charAt(i) == '"' || text.charAt(i) == '\'')) {
                i++;
            }
            i = skipSpaces(text, i);
            if (i >= text.length() || (text.charAt(i) != ':' && text.charAt(i) != '=')) {
                from = Math.max(keyEnd, word.start() + 1);
                continue;
            }
            int valueStart = skipSpaces(text, i + 1);
            int valueEnd = valueEnd(text, valueStart);
            if (valueEnd <= valueStart) {
                from = Math.max(keyEnd, word.start() + 1);
                continue;
            }
            char first = text.charAt(valueStart);
            String replacement = first == '"' ? "\"" + REDACTED + "\"" : first == '\'' ? "'" + REDACTED + "'"
                    : REDACTED;
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, valueStart).append(replacement);
            copied = valueEnd;
            from = valueEnd;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, text.length()).toString();
    }

    private static boolean keyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static int skipSpaces(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The end of a quoted value (after its closing quote, escapes honoured) or of a bare run. */
    private static int valueEnd(String text, int start) {
        if (start >= text.length()) {
            return start;
        }
        char q = text.charAt(start);
        if (q == '"' || q == '\'') {
            int i = start + 1;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == q) {
                    return i + 1;
                }
                i++;
            }
            return text.length();
        }
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || ",;&}])\"'".indexOf(c) >= 0) {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * A JSON-like value through the rules, recursively: a map entry whose key names a secret gets
     * {@value #REDACTED} (a null value stays null), every text goes through {@link #text}, a number whose text is a
     * known secret is replaced, and a value nested deeper than 200 levels becomes {@code "<too deep>"}. Returns a new
     * value; the argument is not changed.
     */
    public Object jsonLike(Object value) {
        return jsonLike(value, 0);
    }

    private Object jsonLike(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return "<too deep>";
        }
        if (value instanceof String s) {
            return text(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, secretKey(key) && e.getValue() != null ? REDACTED : jsonLike(e.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(jsonLike(item, depth + 1));
            }
            return out;
        }
        if (value instanceof Number) {
            return secrets.apply(value);
        }
        return value;
    }

    /**
     * Rows that may go into a prompt (a catalogue sample, a triage request's row sample, a session's {@code db} fact):
     * the first {@link #MAX_ROWS} rows; each value through the pack's row masks (a mask that throws gives
     * {@value #REDACTED}); only the columns the source's allow-lists name are kept, a personal one's values become
     * {@value #PERSONAL}; then every value through the secret values. A source without an allow-list gives no column and
     * no row. {@code truncated} is set when rows were cut here or by the source.
     */
    public Rows rows(String source, Rows rows) {
        Objects.requireNonNull(rows, "rows");
        Map<String, Boolean> columns = allowedColumns(source);
        if (columns.isEmpty()) {
            return Rows.of(List.of(), List.of(), rows.truncated() || rows.rowCount() > 0);
        }
        List<Integer> kept = new ArrayList<>();
        List<Rows.Column> keptColumns = new ArrayList<>();
        for (int c = 0; c < rows.columns().size(); c++) {
            Rows.Column column = rows.columns().get(c);
            if (columns.containsKey(column.name().toLowerCase(Locale.ROOT))) {
                kept.add(c);
                keptColumns.add(column);
            }
        }
        List<List<Object>> out = new ArrayList<>();
        int limit = Math.min(rows.rowCount(), MAX_ROWS);
        for (int r = 0; r < limit; r++) {
            List<Object> row = new ArrayList<>(kept.size());
            for (int c : kept) {
                String name = rows.columns().get(c).name();
                Object value = rowMasked(source, name, rows.get(r, c));
                if (Boolean.TRUE.equals(columns.get(name.toLowerCase(Locale.ROOT))) && value != null) {
                    value = PERSONAL;
                }
                row.add(cell(value));
            }
            out.add(row);
        }
        return Rows.of(keptColumns, out, rows.truncated() || rows.rowCount() > MAX_ROWS);
    }

    /** Column name (lower case) to "personal" over every allow-list of the source; a name personal anywhere is personal. */
    private Map<String, Boolean> allowedColumns(String source) {
        Map<String, Boolean> columns = new LinkedHashMap<>();
        for (SqlAllowList list : allow) {
            if (!list.source().equals(source)) {
                continue;
            }
            for (SqlAllowList.AllowedTable table : list.tables()) {
                for (SqlAllowList.AllowedColumn column : table.columns()) {
                    columns.merge(column.name().toLowerCase(Locale.ROOT), column.personal(), Boolean::logicalOr);
                }
            }
        }
        return columns;
    }

    private Object rowMasked(String source, String column, Object value) {
        Object v = value;
        for (RowMask mask : rowMasks) {
            try {
                v = mask.mask(source, column, v);
            } catch (RuntimeException e) {
                return REDACTED;
            }
        }
        return v;
    }

    private Object cell(Object value) {
        if (value instanceof String s) {
            return text(s);
        }
        if (value instanceof Number n) {
            return secrets.apply((Object) n);
        }
        return value;
    }

    /**
     * The parts of a request through the rules: texts through {@link #text}, tool inputs and results through
     * {@link #jsonLike}, an opaque provider block unchanged (it must be echoed as it came), an image refused unless
     * images are enabled.
     *
     * @throws IllegalStateException for an image part while images are off: a capture is personal data until Phase 7
     */
    public List<Content> parts(List<Content> parts) {
        List<Content> out = new ArrayList<>(parts.size());
        for (Content part : parts) {
            out.add(part(part));
        }
        return List.copyOf(out);
    }

    /** One part through the rules; see {@link #parts}. */
    @SuppressWarnings("unchecked")
    public Content part(Content part) {
        return switch (part) {
            case Content.Text t -> new Content.Text(text(t.text()), t.cacheBreakpoint());
            case Content.ToolUse u -> new Content.ToolUse(u.id(), u.name(), (Map<String, Object>) jsonLike(u.input()));
            case Content.ToolResult r -> new Content.ToolResult(r.toolUseId(), jsonLike(r.content()), r.error());
            case Content.Opaque o -> o;
            case Content.Image i -> {
                if (!images) {
                    throw new IllegalStateException("an image part is personal data until Phase 7 says how a capture"
                            + " is masked (inference.yaml images: false)");
                }
                yield i;
            }
        };
    }

    /**
     * Text from the target or from a person, wrapped in its labelled block ({@code <target-ui>} … {@code </target-ui>},
     * {@code <target-event>}, {@code <target-state>}, {@code <person>}) after the rules; a label tag inside the text is
     * neutralised ({@code <} becomes {@code &lt;}) so that the text cannot close its block or open another.
     *
     * @throws IllegalArgumentException for a label not in {@link #LABELS}
     */
    public String block(String label, String text) {
        if (!LABELS.contains(label)) {
            throw new IllegalArgumentException("a labelled block is one of " + LABELS);
        }
        String masked = text(text == null ? "" : text);
        String neutral = LABEL_TAG.matcher(masked).replaceAll("&lt;$1$2");
        return "<" + label + ">\n" + neutral + "\n</" + label + ">";
    }

    /** Counts only, never a secret. */
    @Override
    public String toString() {
        return "PromptMask[" + secrets + ", " + allow.size() + " allow-lists, " + rowMasks.size() + " row masks, images "
                + (images ? "on" : "off") + "]";
    }
}
