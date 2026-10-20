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
import ai.skillroy.testjava.protocol.JsonException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The expectation path grammar (phase1-contracts.md 8.4, phase8-contracts.md 8.3, 8.5):
 * {@code root(.field | [n] | [field=value] | [field~regex] | [*field=value] | [*field~regex] | [json])*}.
 *
 * <ul>
 *   <li>{@code .field} reads a map key; on a list, a number, {@code first} or {@code last} indexes it
 *       ({@code slip.last.text}).</li>
 *   <li>{@code [n]}, {@code [first]}, {@code [last]} index a list.</li>
 *   <li>{@code [field=value]} on a list yields the single element whose {@code field} equals {@code value} (numbers
 *       compare as decimals); zero matches and the path does not resolve, several and it is ambiguous (no matcher
 *       holds on it, {@code exists: false} included: Phase 8 security fix S154). The value runs to the first
 *       {@code ]} and may be or contain a {@code $slot} reference.</li>
 *   <li>{@code [field~regex]} is the same with a regular expression searched for in the field's text (anchor it with
 *       {@code ^…$} for a full match); brackets inside the regex must balance.</li>
 *   <li>{@code [*field=value]} and {@code [*field~regex]} (Phase 8, the list form) yield the list, possibly empty, of
 *       every element that matches, in list order; on anything but a list the path does not resolve. What applies to
 *       a list applies after it: an index, another filter; {@code .name} that is not an index does not resolve (no
 *       projection). Count it with the {@code size} matcher.</li>
 *   <li>A filter field is a dotted sub-path (Phase 8: {@code [*payload.source=Barcode]}, {@code [lines.0.code=x]}):
 *       each part a map key on a map, or an index ({@code digits}, {@code first}, {@code last}) on a list; an element
 *       on which it does not resolve does not match. Only a map element can match.</li>
 *   <li>{@code [json]} (Phase 8) parses the current value, a string of at most 1 Mi characters, as JSON (depth at
 *       most 200) and continues with the result; a value that is not a string or not JSON leaves the path unresolved
 *       with the reason, a longer or deeper text leaves it undecidable.</li>
 * </ul>
 *
 * <p>The regex searches of one {@link #resolve} call share one {@link SafeRegex.Budget}; a search the budget or a
 * bound stops leaves the path undecidable, never unresolved, so that no negation reads a stop as an absence (Phase 8
 * security fix S149). A reason names the kind of the value a path stopped at, never the value (S176). Where the
 * masked-value rule applies the path is followed on the fact's view ({@link #resolveViewed}, S173). Immutable; safe
 * to share between threads.
 */
final class ExpectationPath {

    /** One step of a path. */
    sealed interface Segment permits Field, Index, Filter, JsonText {
    }

    /** {@code .name}: a map key, or a list index when the value is a list. */
    record Field(String name) implements Segment {
    }

    /** {@code [n]}, {@code [first]}, {@code [last]}. */
    record Index(String index) implements Segment {
    }

    /**
     * {@code [field=value]} or {@code [field~regex]}, with {@code all} the list form {@code [*…]}; {@code value} may
     * hold references until bound.
     *
     * @param field the filter field as written (spaces around it stripped), a dotted sub-path
     * @param regex true for {@code ~}
     * @param value the value or regex text
     * @param all true for the list form: every match, as a list
     */
    record Filter(String field, boolean regex, Object value, boolean all) implements Segment {

        /** A single filter (the Phase 1 form). */
        Filter(String field, boolean regex, Object value) {
            this(field, regex, value, false);
        }

        /** The field split at {@code .}: map keys, or indexes on a list. */
        List<String> fieldPath() {
            return List.of(field.split("\\.", -1));
        }

        /** The segment as written: {@code [*field=value]}. */
        String describe() {
            return "[" + (all ? "*" : "") + field + (regex ? "~" : "=") + Values.stringOf(value) + "]";
        }
    }

    /** {@code [json]}: the current value, a string, parsed as JSON (phase8-contracts.md 8.5). */
    record JsonText() implements Segment {
    }

    /**
     * What following a path gave (Phase 8 security fixes S149, S154, S173, FC): {@code RESOLVED} with a value;
     * {@code MISSING}, the path does not resolve (no such field, no element, not a list, not JSON), which only an
     * {@code exists: false} form holds on; {@code AMBIGUOUS}, a single filter matched several elements, on which no
     * matcher holds ("none" cannot be told from "two"); {@code UNDECIDABLE}, a bound stopped the walk (a filter regex
     * stopped by its budget, over 1 Mi characters or recursing too deeply, a regex a reference made that does not
     * compile, a {@code [json]} text over 1 Mi characters or nested deeper than 200), on which no matcher holds and the
     * expectation ends {@code ERROR / ENVIRONMENT}; {@code SECRET}, on a fact's view ({@link FactView}) a filter's field
     * held a secret on some element, on which no matcher holds ({@link Matchers#HOLDS_A_SECRET}).
     */
    enum State {
        RESOLVED, MISSING, AMBIGUOUS, UNDECIDABLE, SECRET
    }

    /** The outcome of following a path: the value, or why the path did not resolve, and how. */
    record Resolution(State state, Object value, String problem) {

        /** True when the path resolved (to a value, possibly null). */
        boolean resolved() {
            return state == State.RESOLVED;
        }

        static Resolution of(Object value) {
            return new Resolution(State.RESOLVED, value, null);
        }

        static Resolution missing(String problem) {
            return new Resolution(State.MISSING, null, problem);
        }

        static Resolution ambiguous(String problem) {
            return new Resolution(State.AMBIGUOUS, null, problem);
        }

        static Resolution undecidable(String problem) {
            return new Resolution(State.UNDECIDABLE, null, problem);
        }

        static Resolution secret(String problem) {
            return new Resolution(State.SECRET, null, problem);
        }
    }

    /** The deepest {@code [json]} text parsed, as the codec's own bound ({@code Json.MAX_DEPTH}). */
    static final int JSON_MAX_DEPTH = 200;

    /** The text of the {@code [json]} segment. */
    static final String JSON = "json";

    private final String text;
    private final String root;
    private final List<Segment> segments;

    private ExpectationPath(String text, String root, List<Segment> segments) {
        this.text = text;
        this.root = root;
        this.segments = List.copyOf(segments);
    }

    String text() {
        return text;
    }

    String root() {
        return root;
    }

    List<Segment> segments() {
        return segments;
    }

    /**
     * The plan version this path needs (phase8-contracts.md 8.2): 7 when it holds a list filter ({@code [*…]}), a
     * dotted filter field or {@code [json]}, else 1.
     */
    int minPlanVersion() {
        return version7Item() == null ? 1 : 7;
    }

    /**
     * The first Phase 8 item of this path as the plan reader's refusal names it ({@code "a list filter"},
     * {@code "a dotted filter field"}, {@code "[json]"}), or null: a reader below version 7 would have read the first
     * two as a literal key and refused the third.
     */
    String version7Item() {
        for (Segment segment : segments) {
            if (segment instanceof Filter f) {
                if (f.all()) {
                    return "a list filter";
                }
                if (f.field().indexOf('.') >= 0) {
                    return "a dotted filter field";
                }
            } else if (segment instanceof JsonText) {
                return "[" + JSON + "]";
            }
        }
        return null;
    }

    /**
     * @throws IllegalArgumentException with the position and reason if the text does not follow the grammar
     */
    static ExpectationPath parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        int i = 0;
        int n = text.length();
        while (i < n && text.charAt(i) != '.' && text.charAt(i) != '[') {
            i++;
        }
        String root = text.substring(0, i);
        if (root.isEmpty() || !root.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("path '" + text + "' must start with a fact root name");
        }
        List<Segment> segments = new ArrayList<>();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '.') {
                int start = ++i;
                while (i < n && text.charAt(i) != '.' && text.charAt(i) != '[' && text.charAt(i) != ']') {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("path '" + text + "': empty field name at position " + start);
                }
                segments.add(new Field(text.substring(start, i)));
            } else if (c == '[') {
                int start = ++i;
                while (i < n && text.charAt(i) != ']' && text.charAt(i) != '=' && text.charAt(i) != '~') {
                    i++;
                }
                if (i >= n) {
                    throw new IllegalArgumentException("path '" + text + "': unclosed '[' at position "
                            + (start - 1));
                }
                if (text.charAt(i) == ']') {
                    String index = text.substring(start, i).strip();
                    if (index.equals(JSON)) {
                        segments.add(new JsonText());
                        i++;
                        continue;
                    }
                    if (index.startsWith("*") && index.substring(1).isBlank()) {
                        throw new IllegalArgumentException("path '" + text + "': empty filter field at position "
                                + start);
                    }
                    if (!index.equals("first") && !index.equals("last") && !index.matches("\\d{1,9}")) {
                        throw new IllegalArgumentException("path '" + text + "': '[" + index + "]' is neither an"
                                + " index (a number, first, last), [json] nor a filter [field=value] / [field~regex]"
                                + " / [*field=value] / [*field~regex]");
                    }
                    segments.add(new Index(index));
                    i++;
                    continue;
                }
                int op = i;
                String field = text.substring(start, op).strip();
                boolean all = field.startsWith("*");
                if (all) {
                    field = field.substring(1).strip();
                }
                if (field.isEmpty()) {
                    throw new IllegalArgumentException("path '" + text + "': empty filter field at position "
                            + start);
                }
                for (String part : field.split("\\.", -1)) {
                    if (part.isEmpty()) {
                        throw new IllegalArgumentException("path '" + text + "': empty part in the filter field '"
                                + field + "' at position " + start);
                    }
                }
                boolean regex = text.charAt(op) == '~';
                int valueStart = op + 1;
                int end = regex ? regexEnd(text, valueStart) : text.indexOf(']', valueStart);
                if (end < 0) {
                    throw new IllegalArgumentException("path '" + text + "': unclosed '[' at position "
                            + (start - 1));
                }
                String value = text.substring(valueStart, end);
                if (regex && !References.containsReference(value)) {
                    try {
                        Pattern.compile(value);
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException("path '" + text + "': bad regex '" + value + "': "
                                + e.getDescription());
                    }
                }
                segments.add(new Filter(field, regex, value, all));
                i = end + 1;
            } else {
                throw new IllegalArgumentException("path '" + text + "': unexpected '" + c + "' at position " + i
                        + " (expected '.' or '[')");
            }
        }
        return new ExpectationPath(text, root, segments);
    }

    /** Index of the ']' that closes a regex filter, balancing nested brackets and skipping escapes; -1 if none. */
    private static int regexEnd(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /** This path with the references in its filter values resolved. */
    ExpectationPath bind(References.Lookup lookup) throws ResolutionException {
        boolean any = false;
        List<Segment> bound = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            if (segment instanceof Filter f && f.value() instanceof String s && s.indexOf('$') >= 0) {
                Object value = References.substituteString(s, lookup);
                bound.add(new Filter(f.field(), f.regex(), f.regex() ? Values.stringOf(value) : value, f.all()));
                any = true;
            } else {
                bound.add(segment);
            }
        }
        return any ? new ExpectationPath(text, root, bound) : this;
    }

    /**
     * Follows the segments from the root's value as read (a person's run). The regex searches of every filter of the
     * path share one {@link SafeRegex.Budget} for the whole call (S24); a search it stops leaves the path
     * {@code UNDECIDABLE} with the reason.
     */
    Resolution resolve(Object rootValue) {
        return walk(rootValue, null);
    }

    /**
     * Follows the segments on a fact's view (Phase 8 security fix S173): {@code viewedRoot} is
     * {@code view.apply(root)}, so every comparison, search and parse reads masked text; a value {@code [json]}
     * parses passes {@code view} before the path goes on (a key the text held meets the key rule as a map key); a
     * filter whose field holds the marker on any element answers {@code SECRET} before anything is compared or
     * searched. The final value may hold the marker: the caller's rule ({@link FactView#holdsMarker}) decides what
     * that means.
     */
    Resolution resolveViewed(Object viewedRoot, FactView view) {
        return walk(viewedRoot, view);
    }

    private Resolution walk(Object rootValue, FactView view) {
        SafeRegex.Budget budget = null;
        Object current = rootValue;
        StringBuilder walked = new StringBuilder(root);
        for (Segment segment : segments) {
            if (segment instanceof Field f) {
                if (current instanceof Map<?, ?> map) {
                    if (!map.containsKey(f.name())) {
                        return Resolution.missing("no field '" + f.name() + "' in " + walked + " (fields: "
                                + map.keySet() + ")");
                    }
                    current = map.get(f.name());
                } else if (current instanceof List<?> list) {
                    int index = References.index(f.name(), list.size());
                    if (index < 0) {
                        return Resolution.missing("'" + f.name() + "' is not an index of " + walked + " (a list of "
                                + list.size() + ")");
                    }
                    current = list.get(index);
                } else {
                    return Resolution.missing("cannot take field '" + f.name() + "' of " + walked + " ("
                            + Values.kind(current) + ")");
                }
                walked.append('.').append(f.name());
            } else if (segment instanceof Index ix) {
                if (!(current instanceof List<?> list)) {
                    return Resolution.missing(walked + " is not a list (" + Values.kind(current) + ")");
                }
                int index = References.index(ix.index(), list.size());
                if (index < 0) {
                    return Resolution.missing("index " + ix.index() + " is out of range for " + walked
                            + " (a list of " + list.size() + ")");
                }
                current = list.get(index);
                walked.append('[').append(ix.index()).append(']');
            } else if (segment instanceof JsonText) {
                if (!(current instanceof String json)) {
                    return Resolution.missing(walked + " is not a string (" + Values.kind(current) + ")");
                }
                if (json.length() > SafeRegex.MAX_SUBJECT) {
                    return Resolution.undecidable(walked + " is longer than " + SafeRegex.MAX_SUBJECT
                            + " characters");
                }
                try {
                    current = Json.parse(json);
                } catch (JsonException e) {
                    // S149: past the codec's depth the text is not "not JSON" but undecided; S176: never the
                    // character the parser stopped at, only its offset.
                    if (String.valueOf(e.getMessage()).startsWith("nesting depth exceeds")) {
                        return Resolution.undecidable(walked + " nests deeper than " + JSON_MAX_DEPTH);
                    }
                    return Resolution.missing(walked + " is not JSON" + (e.offset() >= 0 ? " (at offset "
                            + e.offset() + ")" : ""));
                }
                if (view != null) {
                    try {
                        current = view.apply(current);
                    } catch (IllegalArgumentException e) {
                        return Resolution.undecidable(walked + "[" + JSON + "]: " + e.getMessage());
                    }
                }
                walked.append('[').append(JSON).append(']');
            } else if (segment instanceof Filter f) {
                if (!(current instanceof List<?> list)) {
                    return Resolution.missing(walked + " is not a list (" + Values.kind(current) + ")");
                }
                String description = f.describe();
                Pattern pattern = null;
                if (f.regex()) {
                    try {
                        pattern = Pattern.compile(Values.stringOf(f.value()));
                    } catch (PatternSyntaxException e) {
                        // A regex a reference made (a literal one is checked when the path is parsed).
                        return Resolution.undecidable("bad regex in " + description + ": " + e.getDescription());
                    }
                }
                List<String> fieldPath = f.fieldPath();
                List<Object[]> fields = new ArrayList<>(list.size());
                for (Object element : list) {
                    Object[] fieldValue = element instanceof Map<?, ?> ? field(element, fieldPath) : null;
                    if (view != null && fieldValue != null && FactView.holdsMarker(fieldValue[0])) {
                        // S173: the secret test before any comparison or search, on every element.
                        return Resolution.secret("the field '" + f.field() + "' holds a secret on an element of "
                                + walked + ", so " + description + " compares nothing");
                    }
                    fields.add(fieldValue);
                }
                if (pattern != null && budget == null) {
                    budget = SafeRegex.Budget.start();
                }
                List<Object> matches = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    Object[] fieldValue = fields.get(i);
                    if (fieldValue == null) {
                        continue;
                    }
                    boolean hit;
                    try {
                        hit = pattern != null
                                ? fieldValue[0] != null && !(fieldValue[0] instanceof Map)
                                        && !(fieldValue[0] instanceof List)
                                        && SafeRegex.find(pattern, Values.stringOf(fieldValue[0]), budget)
                                : Values.equal(fieldValue[0], f.value());
                    } catch (SafeRegex.Rejected e) {
                        return Resolution.undecidable("regex in " + description + ": " + e.getMessage());
                    }
                    if (hit) {
                        matches.add(list.get(i));
                    }
                }
                if (f.all()) {
                    current = matches;
                } else {
                    if (matches.isEmpty()) {
                        return Resolution.missing("no element of " + walked + " match " + description);
                    }
                    if (matches.size() > 1) {
                        // S154: "two" is not "none"; use [*…] with {size: 0} for "none".
                        return Resolution.ambiguous(matches.size() + " elements of " + walked + " match "
                                + description + "; a single filter needs exactly one (count with [*…] and size)");
                    }
                    current = matches.get(0);
                }
                walked.append(description);
            }
        }
        return Resolution.of(current);
    }

    /**
     * The value of a filter's field on one element, as {@code References.navigate} walks: a map key on a map, an index
     * on a list; wrapped so that a null value is told from "does not resolve" (null).
     */
    private static Object[] field(Object element, List<String> fieldPath) {
        Object current = element;
        for (String part : fieldPath) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(part)) {
                    return null;
                }
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                int index = References.index(part, list.size());
                if (index < 0) {
                    return null;
                }
                current = list.get(index);
            } else {
                return null;
            }
        }
        return new Object[] {current};
    }

    @Override
    public String toString() {
        return text;
    }
}
