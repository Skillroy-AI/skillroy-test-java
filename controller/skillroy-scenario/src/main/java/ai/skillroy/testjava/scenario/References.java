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
 * <p>Stateless; safe from any thread.
 */
final class References {

    static final Pattern REFERENCE = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)((?:\\.[A-Za-z0-9_]+)*)");

    private References() {
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

    /** True if the string holds at least one reference (after {@code $$} escapes are taken into account). */
    static boolean containsReference(String text) {
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
