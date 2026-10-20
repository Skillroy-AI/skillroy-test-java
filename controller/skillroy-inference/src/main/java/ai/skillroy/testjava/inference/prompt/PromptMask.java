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
import ai.skillroy.testjava.protocol.text.SecretPairs;
import ai.skillroy.testjava.scenario.Images;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 *   <li><b>Captures.</b> An image part is refused unless images are enabled (false until Phase 7); and a capture's
 *       bytes inside a text or a value (a map's base64 {@code base64} entry, a PNG's base64 run) are elided to
 *       {@code <png: N bytes>} by {@link Images} whatever the setting (phase6 security fix, pass 2, P2-L6-F2,
 *       P2-L6-F3, P2-L6-S1): an image reaches a model, or an MCP client, as an image or not at all.</li>
 *   <li><b>Bounded.</b> A text is masked, then cut at {@value #MAX_TEXT} characters, and a row sample cut at
 *       {@value #MAX_ROWS} rows, with {@value #TRUNCATED} appended.</li>
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
    /**
     * The labels of {@link #block}: text from the target, a person's own words, and (phase7-contracts.md 6.4) a
     * model's own question to a visual check ({@code model-question}: the model's text, which screen content may have
     * steered, declared data like the others).
     */
    public static final Set<String> LABELS = Set.of("target-ui", "target-event", "target-state", "person",
            "model-question");

    private static final int MAX_DEPTH = 200;
    /** A secret may straddle the cut: the text is masked this far past it before being cut. */
    private static final Pattern LABEL_TAG = Pattern.compile(
            "<(/?)(target-ui|target-event|target-state|person|model-question)\\b", Pattern.CASE_INSENSITIVE);

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
     * A text through the rules: every known secret value replaced and the value of every {@code key: value} or
     * {@code key=value} run whose key names a secret replaced, over the whole text, and then cut at {@link #MAX_TEXT}
     * characters with {@link #TRUNCATED} (phase6 security fix S53: masked, then cut, so that no part of a secret of
     * any length that straddles the cut survives; the build's margin of 1000 characters past the cut is gone). An
     * image's base64 in it is elided first ({@link Images#elidedText}, P2-L6-F2). Null stays null.
     */
    public String text(String text) {
        if (text == null) {
            return null;
        }
        String masked = keyRule(secrets.apply(Images.elidedText(text)));
        if (masked.length() > MAX_TEXT) {
            masked = masked.substring(0, MAX_TEXT) + TRUNCATED;
        }
        return masked;
    }

    /** The schema keywords whose values are a schema's data rather than its structure. */
    private static final Set<String> SCHEMA_VALUES = Set.of("default", "examples", "example", "enum", "const");
    /** The keywords whose value maps a property name (or a name pattern) to a subschema. */
    private static final Set<String> NAMED_SCHEMAS = Set.of("properties", "patternProperties", "dependentSchemas");
    /** The keywords whose value maps a definition's name to a subschema that a local {@code $ref} names. */
    private static final Set<String> DEFINITIONS = Set.of("$defs", "definitions");
    private static final int MAX_SCHEMA_DEPTH = 64;

    /**
     * A JSON Schema (a pack verb's argument schema, a query kind's, a projected tool's input schema) through the mask,
     * wherever a model receives the pack's vocabulary (decision D103; phase6 security fix S49): a property's name is
     * kept (it is the vocabulary) and its schema walked with the name's secrecy; a property whose name looks secret
     * ({@link #secretKey}) loses its {@code default}, {@code examples}, {@code example}, {@code const} and {@code enum}
     * whole (its {@code type} and structure stay, so that the schema stays valid and strict; a list or a scalar is
     * never replaced by a text); every text value goes through {@link #text} (the secret values, the key rule, the
     * bound); numbers, booleans and null stay. Deeper than 64 levels, {@code "<too deep>"}. A new value; the schema is
     * not modified.
     *
     * <p>Phase 6 security fix, pass 2 (P2-L5-F1, P2-L5-S1): the secrecy is inherited by everything nested under a
     * secret-named property: its {@code properties}, {@code patternProperties} and {@code dependentSchemas} (whose
     * keys are asked as well, a pattern {@code ^password} naming a secret property), its {@code items}, its branches,
     * and a definition ({@code $defs}, {@code definitions}) that a local {@code $ref} under it names, or whose own
     * name looks secret (a fixed point over the local references, so a definition referenced from a secret definition
     * is secret too; a definition shared with a non-secret property loses its data keywords for both). A data keyword
     * of a non-secret property is data, not a schema: it goes through {@link #jsonLike} (every text through
     * {@link #text}, a secret number replaced), and is dropped whole when it holds, at any depth, a map key that looks
     * secret ({@code default: {staff: ann, password: …}}), so that {@code enum} and {@code const} never pin a model to
     * {@value #REDACTED} and the schema stays valid.
     */
    public Object schema(Object schema) {
        Set<String> secretDefinitions = new java.util.HashSet<>();
        int before;
        do {
            before = secretDefinitions.size();
            secretReferences(schema, false, 0, secretDefinitions);
        } while (secretDefinitions.size() != before);
        return schema(schema, false, 0, secretDefinitions);
    }

    private Object schema(Object schema, boolean secretProperty, int depth, Set<String> secretDefinitions) {
        if (depth > MAX_SCHEMA_DEPTH) {
            return "<too deep>";
        }
        if (schema instanceof String s) {
            return text(s);
        }
        if (schema instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if ((NAMED_SCHEMAS.contains(key) || DEFINITIONS.contains(key)) && value instanceof Map<?, ?> named) {
                    boolean definitions = DEFINITIONS.contains(key);
                    Map<String, Object> walked = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : named.entrySet()) {
                        String name = String.valueOf(entry.getKey());
                        boolean secret = secretProperty || secretKey(name)
                                || definitions && secretDefinitions.contains(name);
                        walked.put(name, schema(entry.getValue(), secret, depth + 1, secretDefinitions));
                    }
                    out.put(key, walked);
                } else if (SCHEMA_VALUES.contains(key)) {
                    if (secretProperty || holdsSecretKey(value, 0)) {
                        continue;
                    }
                    out.put(key, jsonLike(value));
                } else {
                    out.put(key, schema(value, secretProperty, depth + 1, secretDefinitions));
                }
            }
            return out;
        }
        if (schema instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(schema(item, secretProperty, depth + 1, secretDefinitions));
            }
            return out;
        }
        return schema;
    }

    /**
     * One pass of the fixed point of {@link #schema(Object)}: adds to {@code secretDefinitions} the definition named by
     * every local {@code $ref} ({@code #/$defs/<name>}, {@code #/definitions/<name>}, the last segment) met under a
     * secret-named property or inside a definition already known to be secret.
     */
    private void secretReferences(Object schema, boolean secret, int depth, Set<String> secretDefinitions) {
        if (depth > MAX_SCHEMA_DEPTH) {
            return;
        }
        if (schema instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if ((NAMED_SCHEMAS.contains(key) || DEFINITIONS.contains(key)) && value instanceof Map<?, ?> named) {
                    boolean definitions = DEFINITIONS.contains(key);
                    for (Map.Entry<?, ?> entry : named.entrySet()) {
                        String name = String.valueOf(entry.getKey());
                        secretReferences(entry.getValue(), secret || secretKey(name)
                                || definitions && secretDefinitions.contains(name), depth + 1, secretDefinitions);
                    }
                } else if (key.equals("$ref") && value instanceof String ref) {
                    String name = localDefinition(ref);
                    if (name != null && (secret || secretKey(name))) {
                        secretDefinitions.add(name);
                    }
                } else if (!SCHEMA_VALUES.contains(key)) {
                    secretReferences(value, secret, depth + 1, secretDefinitions);
                }
            }
        } else if (schema instanceof List<?> list) {
            for (Object item : list) {
                secretReferences(item, secret, depth + 1, secretDefinitions);
            }
        }
    }

    /**
     * The definition a local {@code $ref} names: the segment after the last {@code $defs} or {@code definitions}
     * segment ({@code #/$defs/a}, {@code #/definitions/a}, {@code #/$defs/a/properties/b} names {@code a}, a nested
     * block's too), JSON-pointer escapes undone; null for another reference.
     */
    private static String localDefinition(String ref) {
        int at = Math.max(ref.lastIndexOf("/$defs/"), ref.lastIndexOf("/definitions/"));
        if (!ref.startsWith("#/") || at < 0) {
            return null;
        }
        int start = ref.indexOf('/', at + 1) + 1;
        int end = ref.indexOf('/', start);
        String name = end < 0 ? ref.substring(start) : ref.substring(start, end);
        return name.isEmpty() ? null : name.replace("~1", "/").replace("~0", "~");
    }

    /** True when the data value holds, at any depth, a map key that looks secret. */
    private boolean holdsSecretKey(Object value, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (secretKey(String.valueOf(e.getKey())) || holdsSecretKey(e.getValue(), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> list) {
            for (Object item : list) {
                if (holdsSecretKey(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The value of every {@code key: value} or {@code key=value} run whose key holds a secret word, replaced: the
     * shared rule of {@link SecretPairs} with this mask's keys ({@link #secretKey}), linear in the text. Phase 8
     * security fix S175 (FC): a JSON text inside a JSON text (escaped quotes) and an object or a list value are
     * replaced too, the value whole and in its key's quote form.
     */
    private String keyRule(String text) {
        return SecretPairs.redact(text, this::secretKey);
    }

    /**
     * A JSON-like value through the rules, recursively: a map entry whose key names a secret gets
     * {@value #REDACTED} (a null value stays null), every text goes through {@link #text}, a number whose text is a
     * known secret is replaced, and a value nested deeper than 200 levels becomes {@code "<too deep>"}; an image's
     * bytes are elided first ({@link Images#elided}, P2-L6-F3). Returns a new value; the argument is not changed.
     */
    public Object jsonLike(Object value) {
        return jsonLike(Images.elided(value), 0);
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
            case Content.ToolUse u -> new Content.ToolUse(u.id(), u.name(), (Map<String, Object>) jsonLike(u.input()),
                    u.toolsetName());
            case Content.ToolResult r -> {
                // phase7-contracts.md 7.2: a parts list's texts through the rules, its images under withImages(true).
                List<Content> nested = r.parts();
                yield new Content.ToolResult(r.toolUseId(), nested == null ? jsonLike(r.content()) : parts(nested),
                        r.error(), r.toolsetName());
            }
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
     * True when the text holds a secret value this mask knows (phase7-contracts.md 9.2 rule 3, D342): the text as it
     * stands, or stripped of surrounding white space, would be changed by the by-value rule (a secret equal to the
     * whole text, a secret of {@code masking.minLengthInText} or more characters inside it, a shorter one in double
     * quotes). What the vision module's {@code type} and the facade refuse before a model's text is typed. The key rule
     * is not consulted (a {@code key=value} text is not a known secret); never names the value.
     */
    public boolean holdsSecret(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return false;
        }
        return !text.equals(secrets.apply(text)) || !text.strip().equals(secrets.apply(text.strip()));
    }

    /**
     * Text from the target or from a person, wrapped in its labelled block ({@code <target-ui>} … {@code </target-ui>},
     * {@code <target-event>}, {@code <target-state>}, {@code <person>}, {@code <model-question>}) after the rules; a label tag inside the text is
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
