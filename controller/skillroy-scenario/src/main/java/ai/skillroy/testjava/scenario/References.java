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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code $slot} reference syntax and substitution.
 *
 * <p>A reference is {@code $name} or {@code $name.field.sub}: a slot name ({@code [A-Za-z_][A-Za-z0-9_]*}) followed by
 * dot-separated fields ({@code [A-Za-z0-9_]+}); a numeric field, {@code first} or {@code last} indexes a list. A
 * string that is exactly one reference is replaced by the referenced value with its type (a map stays a map, a
 * number a number); a reference embedded in a longer string is replaced by the value's text. {@code $$} writes a
 * literal {@code $}; a {@code $} not followed by a slot name is kept as it is. Map keys are never substituted.
 *
 * <p>Public for two predicates only (phase6 security fix S55): a host that hands a client's text to a call whose
 * arguments must stay literal ({@code data_query}'s SQL) asks {@link #containsReference(String)} and
 * {@link #anyReference(Object)} first; and (Phase 7 security fix, S101, S128) a host that judges a client's references
 * before anything runs ({@code PlanPolicy}'s credential rule) reads them with {@link #references(Object)}, the
 * substitution's own tokenizer, rather than a second pattern; the substitution itself stays the package's.
 *
 * <p>Stateless; safe from any thread.
 */
public final class References {

    static final Pattern REFERENCE = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)((?:\\.[A-Za-z0-9_]+)*)");

    /** How deep {@link #references(Object)} and {@link #anyReference(Object)} search before they refuse. */
    public static final int MAX_DEPTH = 64;

    private References() {
    }

    /**
     * One reference as substitution reads it (Phase 7 security fix, S101): the slot name, its fields (empty for the
     * whole slot, {@code $slot}) and whether the string is exactly this one reference (substituted by the value with
     * its type) rather than embedded in a longer text (substituted by the value's text).
     *
     * @param slot the slot name
     * @param fields the fields after the slot name; empty for a whole-slot reference
     * @param whole true when the string is exactly this reference
     */
    public record Reference(String slot, List<String> fields, boolean whole) {

        /** Copies the fields. */
        public Reference {
            fields = List.copyOf(fields);
        }
    }

    /**
     * Every reference in a JSON-like value, in order, read exactly as {@link #substitute} reads them (strings at any
     * depth of its maps and lists, whole or embedded, {@code $$} a literal dollar; map keys are never substituted and
     * are not read): {@code x$staff.}, {@code x$staff..password} and {@code $staff} are the whole slot {@code staff}
     * (Phase 7 security fix, S101).
     *
     * @throws IllegalArgumentException if the value is nested deeper than {@link #MAX_DEPTH} levels: refused rather
     *     than searched, as {@link #anyReference(Object)} does (S128)
     */
    public static List<Reference> references(Object value) {
        List<Reference> out = new ArrayList<>();
        references(value, 0, out);
        return out;
    }

    private static void references(Object value, int depth, List<Reference> out) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("a value nested deeper than " + MAX_DEPTH + " levels");
        }
        if (value instanceof String text) {
            if (text.indexOf('$') < 0) {
                return;
            }
            Matcher whole = REFERENCE.matcher(text);
            if (whole.matches()) {
                out.add(new Reference(whole.group(1), fields(whole.group(2)), true));
                return;
            }
            try {
                substituteString(text, (slot, fields, ref) -> {
                    out.add(new Reference(slot, fields, false));
                    return "";
                });
            } catch (ResolutionException e) {
                throw new IllegalStateException("a collecting lookup does not fail", e);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                references(item, depth + 1, out);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                references(item, depth + 1, out);
            }
        }
    }

    /** Looks a reference up: the slot name, its fields and the reference text for messages. */
    @FunctionalInterface
    interface Lookup {
        Object lookup(String slot, List<String> fields, String text) throws ResolutionException;
    }

    /** One reference found in a template: the slot, its fields and the text as written. */
    record Ref(String slot, List<String> fields, String text) {
    }

    /** Every reference in a JSON-like template (strings at any depth, whole or embedded), in order. */
    static List<Ref> collect(Object template) {
        List<Ref> out = new ArrayList<>();
        try {
            substitute(template, (slot, fields, text) -> {
                out.add(new Ref(slot, fields, text));
                return "";
            });
        } catch (ResolutionException e) {
            throw new IllegalStateException("a collecting lookup does not fail", e);
        }
        return out;
    }

    /** Every reference in the filter values of an expectation path. */
    static List<Ref> collectPath(String path) {
        List<Ref> out = new ArrayList<>();
        try {
            ExpectationPath.parse(path).bind((slot, fields, text) -> {
                out.add(new Ref(slot, fields, text));
                return "";
            });
        } catch (ResolutionException | IllegalArgumentException e) {
            // A path that does not parse is reported where it is compiled.
        }
        return out;
    }

    /**
     * True if the string holds at least one reference (after {@code $$} escapes are taken into account): a {@code $}
     * followed by a slot name, whether or not a slot of that name exists.
     */
    public static boolean containsReference(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '$') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '$') {
                    i++;
                } else {
                    Matcher m = REFERENCE.matcher(text);
                    m.region(i, text.length());
                    if (m.lookingAt()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * True if any string of a JSON-like value, at any depth of its maps and lists, holds a reference
     * ({@link #containsReference(String)}); map keys are never substituted and are not looked at. A value nested deeper
     * than 64 levels counts as holding one (refused rather than searched).
     */
    public static boolean anyReference(Object value) {
        return anyReference(value, 0);
    }

    private static boolean anyReference(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return true;
        }
        if (value instanceof String text) {
            return containsReference(text);
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (anyReference(item, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (anyReference(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A copy of a JSON-like value with every {@code $} of every string, at any depth, written {@code $$}, so that a
     * later {@link #substitute} gives the value back unchanged: no reference is resolved in it (a call with
     * {@code PlannedCall.literalArgs}, phase6 security fix S55). Map keys are kept as they are.
     */
    static Object escaped(Object value) {
        if (value instanceof String text) {
            return text.indexOf('$') < 0 ? text : text.replace("$", "$$");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), escaped(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(escaped(item));
            }
            return out;
        }
        return value;
    }

    /** A copy of the template with every reference substituted; the template is not modified. */
    static Object substitute(Object template, Lookup lookup) throws ResolutionException {
        if (template instanceof String text) {
            return substituteString(text, lookup);
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), substitute(e.getValue(), lookup));
            }
            return out;
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(substitute(item, lookup));
            }
            return out;
        }
        return template;
    }

    static Object substituteString(String text, Lookup lookup) throws ResolutionException {
        if (text.indexOf('$') < 0) {
            return text;
        }
        Matcher whole = REFERENCE.matcher(text);
        if (whole.matches()) {
            return lookup.lookup(whole.group(1), fields(whole.group(2)), text);
        }
        StringBuilder out = new StringBuilder(text.length());
        Matcher m = REFERENCE.matcher(text);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }
            m.region(i, text.length());
            if (m.lookingAt()) {
                Object value = lookup.lookup(m.group(1), fields(m.group(2)), m.group());
                out.append(value == null ? "null" : Values.stringOf(value));
                i = m.end();
            } else {
                out.append('$');
                i++;
            }
        }
        return out.toString();
    }

    private static List<String> fields(String dotted) {
        if (dotted == null || dotted.isEmpty()) {
            return List.of();
        }
        return List.of(dotted.substring(1).split("\\."));
    }

    /**
     * Follows {@code fields} from a slot value: map keys, or list indexes ({@code 0}, {@code first}, {@code last}).
     *
     * @throws ResolutionException naming the reference and the field that is missing
     */
    static Object navigate(Object value, List<String> fields, String text) throws ResolutionException {
        Object current = value;
        StringBuilder walked = new StringBuilder();
        for (String field : fields) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(field)) {
                    throw new ResolutionException(text, "cannot resolve " + text + ": no field '" + field + "'"
                            + (walked.length() == 0 ? "" : " in " + walked) + "; fields: " + map.keySet());
                }
                current = map.get(field);
            } else if (current instanceof List<?> list) {
                int index = index(field, list.size());
                if (index < 0) {
                    throw new ResolutionException(text, "cannot resolve " + text + ": '" + field
                            + "' is not an index of a list of " + list.size());
                }
                current = list.get(index);
            } else {
                throw new ResolutionException(text, "cannot resolve " + text + ": cannot take field '" + field
                        + "' of " + ScenarioLoader.describe(current));
            }
            walked.append(walked.length() == 0 ? "" : ".").append(field);
        }
        return current;
    }

    /** The list index a field names, or -1: digits, {@code first}, {@code last}. */
    static int index(String field, int size) {
        int index;
        if ("first".equals(field)) {
            index = 0;
        } else if ("last".equals(field)) {
            index = size - 1;
        } else if (!field.isEmpty() && field.length() <= 9 && field.chars().allMatch(Character::isDigit)) {
            index = Integer.parseInt(field);
        } else {
            return -1;
        }
        return index >= 0 && index < size ? index : -1;
    }
}
