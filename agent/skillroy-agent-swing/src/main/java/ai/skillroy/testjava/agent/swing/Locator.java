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

package ai.skillroy.testjava.agent.swing;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A parsed {@code by} object: exactly one strategy ({@code ref}, {@code id}, {@code name}, {@code text},
 * {@code class}, {@code path}, or a key a {@code LocatorResolver} registered), plus an optional {@code role} and
 * {@code index}. {@code id} matches the explicitly set {@code Component.getName()} exactly (see
 * {@link ComponentView#id}); {@code name} and {@code text} also accept {@code {contains}} and {@code {regex}}.
 * Parsing checks everything that does not need the EDT (shape, regex syntax, path syntax, role names); whether a
 * custom strategy is registered is checked when it is resolved.
 *
 * <p>Immutable; safe to share between threads.
 */
final class Locator {

    /** Strategies the module implements itself. A resolver cannot override them. */
    static final Set<String> BUILT_IN = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "ref", "id", "name", "text", "class", "path")));

    private static final Pattern PATH = Pattern.compile("(\\d+(/\\d+)*)?");

    private final String strategy;
    private final String value;
    private final TextMatch match;
    private final int[] path;
    private final String role;
    private final Integer index;
    private final Map<String, Object> raw;

    private Locator(String strategy, String value, TextMatch match, int[] path, String role, Integer index,
                    Map<String, Object> raw) {
        this.strategy = strategy;
        this.value = value;
        this.match = match;
        this.path = path;
        this.role = role;
        this.index = index;
        this.raw = raw;
    }

    /**
     * Parses a locator object.
     *
     * @param param the parameter name, for error messages ({@code "by"})
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS for anything malformed
     */
    @SuppressWarnings("unchecked")
    static Locator parse(Object by, String param) {
        if (!(by instanceof Map)) {
            throw Errors.invalid("parameter '" + param + "' must be a locator object, e.g. {\"name\":\"ok\"}",
                    Errors.data("param", param));
        }
        Map<String, Object> map = (Map<String, Object>) by;
        String strategy = null;
        Object rawValue = null;
        String role = null;
        Integer index = null;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if ("role".equals(key)) {
                role = scalar(v, param + ".role");
                if (!Roles.ALL.contains(role)) {
                    throw Errors.invalid("unknown role '" + role + "' in " + param + "; roles: " + Roles.ALL,
                            Errors.data("param", param + ".role"));
                }
            } else if ("index".equals(key)) {
                index = Integer.valueOf(index(v, param + ".index"));
            } else {
                if (strategy != null) {
                    throw Errors.invalid("locator '" + param + "' names more than one strategy ('" + strategy
                            + "' and '" + key + "'); give exactly one", Errors.data("param", param));
                }
                strategy = key;
                rawValue = v;
            }
        }
        if (strategy == null) {
            throw Errors.invalid("locator '" + param + "' needs exactly one strategy: ref, id, name, text, class, "
                    + "path or a registered one", Errors.data("param", param));
        }
        String where = param + "." + strategy;
        if (rawValue == null) {
            throw Errors.invalid("locator strategy '" + strategy + "' has no value", Errors.data("param", where));
        }
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(map));
        if ("name".equals(strategy) || "text".equals(strategy)) {
            return new Locator(strategy, null, TextMatch.parse(rawValue, where), null, role, index, copy);
        }
        String text = scalar(rawValue, where);
        if ("path".equals(strategy)) {
            return new Locator(strategy, text, null, parsePath(text, where), role, index, copy);
        }
        return new Locator(strategy, text, null, null, role, index, copy);
    }

    private static int[] parsePath(String text, String where) {
        String t = text.trim();
        if (!PATH.matcher(t).matches()) {
            throw Errors.invalid("path '" + text + "' must be child indexes separated by '/', e.g. \"0/2/1\"",
                    Errors.data("param", where));
        }
        if (t.isEmpty()) {
            return new int[0];
        }
        String[] parts = t.split("/");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw Errors.invalid("path index '" + parts[i] + "' is too large", Errors.data("param", where));
            }
        }
        return out;
    }

    /** A string, or the text of a number or boolean; objects and arrays are rejected. */
    static String scalar(Object v, String where) {
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        throw Errors.invalid("'" + where + "' must be a string", Errors.data("param", where));
    }

    private static int index(Object v, String where) {
        long n;
        if (v instanceof Number && ((Number) v).doubleValue() == Math.rint(((Number) v).doubleValue())) {
            n = ((Number) v).longValue();
        } else if (v instanceof String && ((String) v).trim().matches("\\d+")) {
            try {
                n = Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                n = -1;
            }
        } else {
            n = -1;
        }
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw Errors.invalid("'" + where + "' must be a non-negative integer", Errors.data("param", where));
        }
        return (int) n;
    }

    String strategy() {
        return strategy;
    }

    /** The value of ref, id, class, path and custom strategies; null for name and text. */
    String value() {
        return value;
    }

    /** The matcher of name and text strategies; null otherwise. */
    TextMatch match() {
        return match;
    }

    /** Child indexes of a path strategy; null otherwise. */
    int[] path() {
        return path == null ? null : path.clone();
    }

    /** Required role, or null. */
    String role() {
        return role;
    }

    /** Which of several matches to take (0-based), or null. */
    Integer index() {
        return index;
    }

    /** The locator as given, for error data. */
    Map<String, Object> raw() {
        return raw;
    }

    boolean builtIn() {
        return BUILT_IN.contains(strategy);
    }

    @Override
    public String toString() {
        return "Locator" + raw;
    }

    /**
     * How a name or text value is compared: a plain string matches exactly, {@code {contains}} as a substring,
     * {@code {regex}} with {@code Matcher.find()} (anchor it to match the whole text). A null text never matches.
     *
     * <p>Immutable; safe to share between threads.
     */
    static final class TextMatch {
        private final String exact;
        private final String contains;
        private final Pattern regex;
        private final String where;

        private TextMatch(String exact, String contains, Pattern regex, String where) {
            this.exact = exact;
            this.contains = contains;
            this.regex = regex;
            this.where = where;
        }

        @SuppressWarnings("unchecked")
        static TextMatch parse(Object v, String where) {
            if (!(v instanceof Map)) {
                return new TextMatch(scalar(v, where), null, null, where);
            }
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.size() != 1) {
                throw Errors.invalid("'" + where + "' must be a string, {\"contains\":...} or {\"regex\":...}",
                        Errors.data("param", where));
            }
            Map.Entry<String, Object> e = m.entrySet().iterator().next();
            if ("contains".equals(e.getKey())) {
                return new TextMatch(null, scalar(e.getValue(), where + ".contains"), null, where);
            }
            if ("regex".equals(e.getKey())) {
                String pattern = scalar(e.getValue(), where + ".regex");
                try {
                    return new TextMatch(null, null, Pattern.compile(pattern), where);
                } catch (PatternSyntaxException ex) {
                    throw Errors.invalid("invalid regex in '" + where + "': " + ex.getDescription(),
                            Errors.data("param", where + ".regex", "regex", pattern));
                }
            }
            throw Errors.invalid("'" + where + "' accepts only \"contains\" or \"regex\", not \"" + e.getKey() + "\"",
                    Errors.data("param", where));
        }

        /** As {@link #test(String, RegexBudget)} with a budget of its own. */
        boolean test(String text) {
            return test(text, new RegexBudget());
        }

        /**
         * True if the text matches.
         *
         * @param budget the regex time left for the locator being resolved; charged for the time this call takes
         * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS when a regex uses the budget up
         */
        boolean test(String text, RegexBudget budget) {
            if (text == null) {
                return false;
            }
            if (exact != null) {
                return exact.equals(text);
            }
            if (contains != null) {
                return text.contains(contains);
            }
            long start = System.nanoTime();
            try {
                return regex.matcher(new BoundedText(text, start + budget.leftNanos, this)).find();
            } finally {
                budget.leftNanos -= System.nanoTime() - start;
            }
        }

        private RuntimeException tooSlow() {
            return Errors.invalid("the regex in '" + where + "' ran longer than " + RegexBudget.TOTAL_MS
                    + " ms and was stopped; simplify it", Errors.data("param", where + ".regex", "regex",
                    regex.pattern(), "reason", "regex too slow"));
        }
    }

    /**
     * The time regex matching may take while one locator is resolved. Matching runs on the EDT, and a pattern that
     * backtracks without end would otherwise freeze the application for as long as it ran.
     *
     * <p>Not thread-safe: one instance per resolution, used on one thread.
     */
    static final class RegexBudget {
        static final long TOTAL_MS = 1000;
        private long leftNanos = TimeUnit.MILLISECONDS.toNanos(TOTAL_MS);
    }

    /** The text a regex reads; looks at the clock every 1024 characters read and gives up past the deadline. */
    private static final class BoundedText implements CharSequence {
        private final CharSequence text;
        private final long deadlineNanos;
        private final TextMatch owner;
        private int reads;

        BoundedText(CharSequence text, long deadlineNanos, TextMatch owner) {
            this.text = text;
            this.deadlineNanos = deadlineNanos;
            this.owner = owner;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 1023) == 0 && System.nanoTime() - deadlineNanos > 0) {
                throw owner.tooSlow();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedText(text.subSequence(start, end), deadlineNanos, owner);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
