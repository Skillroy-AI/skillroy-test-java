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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The expectation path grammar: {@code root(.field | [n] | [field=value] | [field~regex])*}.
 *
 * <ul>
 *   <li>{@code .field} reads a map key; on a list, a number, {@code first} or {@code last} indexes it
 *       ({@code slip.last.text}).</li>
 *   <li>{@code [n]}, {@code [first]}, {@code [last]} index a list.</li>
 *   <li>{@code [field=value]} on a list yields the single element whose {@code field} equals {@code value} (numbers
 *       compare as decimals); zero or several matches and the path does not resolve. The value runs to the first
 *       {@code ]} and may be or contain a {@code $slot} reference.</li>
 *   <li>{@code [field~regex]} is the same with a regular expression searched for in the field's text (anchor it with
 *       {@code ^…$} for a full match); brackets inside the regex must balance.</li>
 * </ul>
 *
 * <p>Immutable; safe to share between threads.
 */
final class ExpectationPath {

    /** One step of a path. */
    sealed interface Segment permits Field, Index, Filter {
    }

    /** {@code .name}: a map key, or a list index when the value is a list. */
    record Field(String name) implements Segment {
    }

    /** {@code [n]}, {@code [first]}, {@code [last]}. */
    record Index(String index) implements Segment {
    }

    /** {@code [field=value]} or {@code [field~regex]}; {@code value} may hold references until bound. */
    record Filter(String field, boolean regex, Object value) implements Segment {
    }

    /** The outcome of following a path: the value, or why the path did not resolve. */
    record Resolution(boolean resolved, Object value, String problem) {

        static Resolution of(Object value) {
            return new Resolution(true, value, null);
        }

        static Resolution missing(String problem) {
            return new Resolution(false, null, problem);
        }
    }

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
                    if (!index.equals("first") && !index.equals("last") && !index.matches("\\d{1,9}")) {
                        throw new IllegalArgumentException("path '" + text + "': '[" + index + "]' is neither an"
                                + " index (a number, first, last) nor a filter [field=value] / [field~regex]");
                    }
                    segments.add(new Index(index));
                    i++;
                    continue;
                }
                int op = i;
                String field = text.substring(start, op).strip();
                if (field.isEmpty()) {
                    throw new IllegalArgumentException("path '" + text + "': empty filter field at position "
                            + start);
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
                segments.add(new Filter(field, regex, value));
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
                bound.add(new Filter(f.field(), f.regex(), f.regex() ? Values.stringOf(value) : value));
                any = true;
            } else {
                bound.add(segment);
            }
        }
        return any ? new ExpectationPath(text, root, bound) : this;
    }

    /**
     * Follows the segments from the root's value. The regex searches of {@code [field~regex]} filters share one
     * {@link SafeRegex.Budget} for the whole call; a search it stops leaves the path unresolved with the reason.
     */
    Resolution resolve(Object rootValue) {
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
                            + ScenarioLoader.describe(current) + ")");
                }
                walked.append('.').append(f.name());
            } else if (segment instanceof Index ix) {
                if (!(current instanceof List<?> list)) {
                    return Resolution.missing(walked + " is not a list (" + ScenarioLoader.describe(current) + ")");
                }
                int index = References.index(ix.index(), list.size());
                if (index < 0) {
                    return Resolution.missing("index " + ix.index() + " is out of range for " + walked
                            + " (a list of " + list.size() + ")");
                }
                current = list.get(index);
                walked.append('[').append(ix.index()).append(']');
            } else if (segment instanceof Filter f) {
                if (!(current instanceof List<?> list)) {
                    return Resolution.missing(walked + " is not a list (" + ScenarioLoader.describe(current) + ")");
                }
                String description = "[" + f.field() + (f.regex() ? "~" : "=") + Values.stringOf(f.value()) + "]";
                Pattern pattern = null;
                if (f.regex()) {
                    try {
                        pattern = Pattern.compile(Values.stringOf(f.value()));
                    } catch (PatternSyntaxException e) {
                        return Resolution.missing("bad regex in " + description + ": " + e.getDescription());
                    }
                }
                if (pattern != null && budget == null) {
                    budget = SafeRegex.Budget.start();
                }
                List<Object> matches = new ArrayList<>();
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map && map.containsKey(f.field())) {
                        Object fieldValue = map.get(f.field());
                        boolean hit;
                        try {
                            hit = pattern != null
                                    ? fieldValue != null && !(fieldValue instanceof Map)
                                            && !(fieldValue instanceof List)
                                            && SafeRegex.find(pattern, Values.stringOf(fieldValue), budget)
                                    : Values.equal(fieldValue, f.value());
                        } catch (SafeRegex.Rejected e) {
                            return Resolution.missing("regex in " + description + ": " + e.getMessage());
                        }
                        if (hit) {
                            matches.add(element);
                        }
                    }
                }
                if (matches.size() != 1) {
                    return Resolution.missing((matches.isEmpty() ? "no element" : matches.size() + " elements")
                            + " of " + walked + " match " + description);
                }
                current = matches.get(0);
                walked.append(description);
            }
        }
        return Resolution.of(current);
    }

    @Override
    public String toString() {
        return text;
    }
}
