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

import ai.skillroy.testjava.data.SecretMask;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Expectation matchers (contract section 8.4): a scalar (equality), or exactly one of {@code {equals}},
 * {@code {contains}} (substring, or list membership), {@code {matches: regex}} (searched for; anchor with
 * {@code ^…$}), {@code {in: […]}}, {@code {gt|gte|lt|lte: n}}, {@code {size: n}} (list or map size, string length;
 * {@code n} may itself be a matcher), {@code {exists: bool}} (the path resolves to a non-null value) and
 * {@code {not: matcher}}.
 *
 * <p>Equality follows {@link Values#equal}: numbers and numeric strings compare as decimals, so {@code 2 == "2.00"};
 * a string with a superfluous leading zero ({@code "0100"}) is text, not a number.
 * When the path does not resolve, only {@code exists} (and {@code not} of it) can hold. A {@code matches} search is
 * bounded by {@link SafeRegex} (about a second, no stack overflow): a search it stops is a failed outcome with the
 * reason.
 *
 * <p>Stateless; safe from any thread.
 */
final class Matchers {

    /** The matcher keys, in documentation order. */
    static final List<String> NAMES = List.of("equals", "contains", "matches", "in", "gt", "gte", "lt", "lte",
            "size", "exists", "not");

    private Matchers() {
    }

    /** The result of one evaluation; {@code reason} explains a failure. */
    record Outcome(boolean holds, String reason) {
    }

    /** Why no matcher holds on a value that holds a secret ({@link #maskedSecret}). */
    static final String HOLDS_A_SECRET = "the value holds a secret (the mask changes it), and a check on a secret never"
            + " holds";

    /**
     * The rule of a check on a secret (phase5-contracts.md 12.2): when {@code mask} changes a value, the value holds a
     * secret and satisfies no matcher, since {@code matches}, {@code contains} and the comparisons would otherwise
     * rebuild it one character at a time. Used by a session's checks always and by a run's expectations and
     * stored-row checks with {@link RunOptions#expectationsOnMaskedValues()}.
     *
     * @return the value as the mask leaves it when the mask changes it (what a check reports as its actual), else null
     */
    static Object maskedSecret(SecretMask mask, Object value) {
        Object form;
        try {
            form = value == null ? null : RunLog.jsonForm(value, 0);
        } catch (RuntimeException e) {
            form = String.valueOf(value);
        }
        Object masked = mask.apply(form);
        return Objects.equals(masked, form) ? null : masked;
    }

    /**
     * Compile-time check and normal form: a scalar or list becomes {@code {"equals": value}}; nested matchers of
     * {@code not} and {@code size} are normalised too. References are left in place; only values without references
     * are type-checked.
     *
     * @throws IllegalArgumentException naming the problem
     */
    static Map<String, Object> normalise(Object matcher) {
        if (!(matcher instanceof Map<?, ?> map)) {
            return single("equals", matcher);
        }
        if (map.size() != 1) {
            throw new IllegalArgumentException("a matcher object must have exactly one of " + NAMES
                    + ", found keys " + map.keySet() + " (use {equals: {…}} to compare with an object)");
        }
        Map.Entry<?, ?> only = map.entrySet().iterator().next();
        String key = String.valueOf(only.getKey());
        Object value = only.getValue();
        switch (key) {
            case "equals", "contains" -> {
                return single(key, value);
            }
            case "matches" -> {
                if (!(value instanceof String regex)) {
                    throw new IllegalArgumentException("'matches' needs a regex string, found "
                            + ScenarioLoader.describe(value));
                }
                if (!References.containsReference(regex)) {
                    try {
                        Pattern.compile(regex);
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException("bad regex '" + regex + "': " + e.getDescription());
                    }
                }
                return single(key, value);
            }
            case "in" -> {
                if (!(value instanceof List) && !isReference(value)) {
                    throw new IllegalArgumentException("'in' needs a list, found " + ScenarioLoader.describe(value));
                }
                return single(key, value);
            }
            case "gt", "gte", "lt", "lte" -> {
                if (Values.decimal(value) == null && !isReference(value)) {
                    throw new IllegalArgumentException("'" + key + "' needs a number, found "
                            + ScenarioLoader.describe(value));
                }
                return single(key, value);
            }
            case "size" -> {
                if (value instanceof Map) {
                    return single(key, normalise(value));
                }
                BigDecimal n = Values.decimal(value);
                if ((n == null || n.signum() < 0 || n.stripTrailingZeros().scale() > 0) && !isReference(value)) {
                    throw new IllegalArgumentException("'size' needs a non-negative integer or a matcher, found "
                            + ScenarioLoader.describe(value));
                }
                return single(key, value);
            }
            case "exists" -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException("'exists' needs true or false, found "
                            + ScenarioLoader.describe(value));
                }
                return single(key, value);
            }
            case "not" -> {
                return single(key, normalise(value));
            }
            case "capability" -> throw new IllegalArgumentException(
                    "'capability' may only guard a top-level matcher, next to its matcher key");
            default -> throw new IllegalArgumentException("unknown matcher '" + key + "'; matchers: " + NAMES);
        }
    }

    /** The matcher of a list of alternatives (phase8-contracts.md 8.4); it needs a version 7 plan. */
    static final String ANY_OF = "anyOf";

    /** The matcher of "some element of the list where …" (phase8-contracts.md 8.4); it needs a version 7 plan. */
    static final String CONTAINS_WHERE = "containsWhere";

    /** The matchers a plan below version 7 cannot hold (phase8-contracts.md 8.2). */
    private static final List<String> VERSION7_NAMES = List.of(ANY_OF, CONTAINS_WHERE);

    /** How deep {@link #mentions} looks; the plan reader's own depth bound is 200. */
    private static final int MAX_WALK_DEPTH = 200;

    /**
     * The plan version a matcher in normal form needs (phase8-contracts.md 8.2): 7 when {@code anyOf} or
     * {@code containsWhere} appears anywhere in it (through {@code not}, {@code size}, {@code anyOf} and
     * {@code containsWhere}), else 1.
     */
    static int minPlanVersion(Object normalised) {
        return version7Matcher(normalised) == null ? 1 : 7;
    }

    /** The first of {@code anyOf} and {@code containsWhere} the matcher holds anywhere, or null. */
    static String version7Matcher(Object normalised) {
        for (String name : VERSION7_NAMES) {
            if (mentions(normalised, name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * True when the matcher in normal form uses the matcher {@code name} at its top or nested inside {@code not},
     * {@code size}, {@code anyOf} or {@code containsWhere}; the operand of {@code equals}, {@code contains} and
     * {@code in} is a value and is not searched.
     */
    static boolean mentions(Object normalised, String name) {
        return mentions(normalised, name, 0);
    }

    private static boolean mentions(Object matcher, String name, int depth) {
        if (depth > MAX_WALK_DEPTH || !(matcher instanceof Map<?, ?> map) || map.size() != 1) {
            return false;
        }
        Map.Entry<?, ?> only = map.entrySet().iterator().next();
        String key = String.valueOf(only.getKey());
        if (key.equals(name)) {
            return true;
        }
        Object value = only.getValue();
        switch (key) {
            case "not", "size" -> {
                return mentions(value, name, depth + 1);
            }
            case ANY_OF -> {
                if (value instanceof List<?> alternatives) {
                    for (Object alternative : alternatives) {
                        if (mentions(alternative, name, depth + 1)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            case CONTAINS_WHERE -> {
                if (value instanceof Map<?, ?> fields) {
                    for (Object field : fields.values()) {
                        if (mentions(field, name, depth + 1)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean isReference(Object value) {
        return value instanceof String s && References.containsReference(s);
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    /**
     * Evaluates a matcher in normal form (anything else is treated as equality).
     *
     * @param resolved whether the path resolved
     * @param actual the resolved value (null when not resolved)
     * @param problem why the path did not resolve, when it did not
     */
    static Outcome evaluate(Object matcher, boolean resolved, Object actual, String problem) {
        if (!(matcher instanceof Map<?, ?> map) || map.size() != 1) {
            return evaluate(single("equals", matcher), resolved, actual, problem);
        }
        Map.Entry<?, ?> only = map.entrySet().iterator().next();
        String key = String.valueOf(only.getKey());
        Object expected = only.getValue();
        if ("exists".equals(key)) {
            boolean want = Boolean.TRUE.equals(expected);
            boolean has = resolved && actual != null;
            if (has == want) {
                return new Outcome(true, null);
            }
            return new Outcome(false, want
                    ? "expected the path to exist but " + (resolved ? "it is null" : "it did not resolve: " + problem)
                    : "expected the path not to exist but it is " + Values.brief(actual, 200));
        }
        if ("not".equals(key)) {
            if (!resolved && !isExists(expected)) {
                return new Outcome(false, "the path did not resolve: " + problem);
            }
            Outcome inner = evaluate(expected, resolved, actual, problem);
            return inner.holds()
                    ? new Outcome(false, "expected not " + Values.safeWrite(expected) + " but was "
                            + Values.brief(actual, 200))
                    : new Outcome(true, null);
        }
        if (!resolved) {
            return new Outcome(false, "the path did not resolve: " + problem);
        }
        return switch (key) {
            case "equals" -> Values.equal(actual, expected)
                    ? new Outcome(true, null)
                    : new Outcome(false, "expected " + Values.brief(expected, 200) + " but was "
                            + Values.brief(actual, 200));
            case "contains" -> contains(actual, expected);
            case "matches" -> matches(actual, expected);
            case "in" -> in(actual, expected);
            case "gt", "gte", "lt", "lte" -> compare(key, actual, expected);
            case "size" -> size(actual, expected);
            default -> new Outcome(false, "unknown matcher '" + key + "'");
        };
    }

    private static boolean isExists(Object matcher) {
        return matcher instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("exists");
    }

    private static boolean isScalar(Object value) {
        return value != null && !(value instanceof Map) && !(value instanceof List);
    }

    private static Outcome contains(Object actual, Object expected) {
        if (actual instanceof List<?> list) {
            for (Object element : list) {
                if (Values.equal(element, expected)) {
                    return new Outcome(true, null);
                }
            }
            return new Outcome(false, "expected a list containing " + Values.brief(expected, 200) + " but was "
                    + Values.brief(actual, 300));
        }
        if (isScalar(actual)) {
            if (isScalar(expected) && Values.stringOf(actual).contains(Values.stringOf(expected))) {
                return new Outcome(true, null);
            }
            return new Outcome(false, "expected text containing " + Values.brief(expected, 200) + " but was "
                    + Values.brief(actual, 300));
        }
        return new Outcome(false, "'contains' needs a string or a list, but the value is "
                + ScenarioLoader.describe(actual));
    }

    private static Outcome matches(Object actual, Object expected) {
        if (!isScalar(actual)) {
            return new Outcome(false, "'matches' needs a scalar, but the value is " + ScenarioLoader.describe(actual));
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(Values.stringOf(expected));
        } catch (PatternSyntaxException e) {
            return new Outcome(false, "bad regex " + Values.brief(expected, 200) + ": " + e.getDescription());
        }
        boolean found;
        try {
            found = SafeRegex.find(pattern, Values.stringOf(actual), SafeRegex.Budget.start());
        } catch (SafeRegex.Rejected e) {
            return new Outcome(false, "'matches' /" + Values.stringOf(expected) + "/: " + e.getMessage());
        }
        return found
                ? new Outcome(true, null)
                : new Outcome(false, "expected a match for /" + Values.stringOf(expected) + "/ but was "
                        + Values.brief(actual, 300));
    }

    private static Outcome in(Object actual, Object expected) {
        if (!(expected instanceof List<?> options)) {
            return new Outcome(false, "'in' needs a list, found " + ScenarioLoader.describe(expected));
        }
        for (Object option : options) {
            if (Values.equal(actual, option)) {
                return new Outcome(true, null);
            }
        }
        return new Outcome(false, "expected one of " + Values.brief(expected, 200) + " but was "
                + Values.brief(actual, 200));
    }

    private static Outcome compare(String op, Object actual, Object expected) {
        BigDecimal a = Values.decimal(actual);
        BigDecimal e = Values.decimal(expected);
        if (a == null || e == null) {
            return new Outcome(false, "'" + op + "' needs numbers, but compared " + Values.brief(actual, 200)
                    + " with " + Values.brief(expected, 200));
        }
        int c = a.compareTo(e);
        boolean holds = switch (op) {
            case "gt" -> c > 0;
            case "gte" -> c >= 0;
            case "lt" -> c < 0;
            default -> c <= 0;
        };
        String symbol = switch (op) {
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            default -> "<=";
        };
        return holds ? new Outcome(true, null)
                : new Outcome(false, "expected a value " + symbol + " " + e.toPlainString() + " but was "
                        + Values.brief(actual, 200));
    }

    private static Outcome size(Object actual, Object expected) {
        long n;
        if (actual instanceof List<?> list) {
            n = list.size();
        } else if (actual instanceof Map<?, ?> map) {
            n = map.size();
        } else if (actual instanceof String s) {
            n = s.length();
        } else {
            return new Outcome(false, "'size' needs a list, a map or a string, but the value is "
                    + ScenarioLoader.describe(actual));
        }
        if (expected instanceof Map) {
            Outcome inner = evaluate(expected, true, n, null);
            return inner.holds() ? inner : new Outcome(false, "size " + n + ": " + inner.reason());
        }
        return Values.equal(n, expected)
                ? new Outcome(true, null)
                : new Outcome(false, "expected size " + Values.stringOf(expected) + " but was " + n);
    }
}
