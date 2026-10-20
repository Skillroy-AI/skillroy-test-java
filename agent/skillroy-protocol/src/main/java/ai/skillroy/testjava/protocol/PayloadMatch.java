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

package ai.skillroy.testjava.protocol;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The payload rule of {@code sync.await}'s {@code match} and of every controller-side wait on an event
 * (phase1-contracts.md 5, phase8-contracts.md 8.9). Shared so that the agent and the controller interpret a
 * {@code match} identically, as {@link TopicFilter} is for topics.
 *
 * <p>Every entry of a match must hold on the event's payload:
 * <ul>
 *   <li>a scalar or null value holds when its text equals the text of the payload's value for that key
 *       ({@code String.valueOf} on both sides, so {@code 2} and {@code 2.0} differ and a missing key reads as
 *       {@code "null"}): the rule since Phase 1;</li>
 *   <li>{@code {has: <scalar>}} holds when the payload's value is a list and some element of it is a scalar whose
 *       text equals the scalar's text (a map or list element never equals; a payload value that is not a list never
 *       holds; no substring meaning).</li>
 * </ul>
 * Anything else is invalid ({@link #problem}): a list or map value, an empty object, {@code {has: null}}, a {@code has}
 * whose value is not a scalar, or an object with any other key. An invalid entry never holds; callers refuse an
 * invalid match with {@link #problem} before they wait.
 *
 * <p>Linear in the match's entries and one list's length, text comparison only: the agent evaluates it under its event
 * ring's lock, so it uses no regular expression, parses nothing and follows no nested path. Stateless; safe from any
 * thread.
 */
public final class PayloadMatch {

    /** The one operator: {@code {has: <scalar>}}, list membership by text. */
    public static final String HAS = "has";

    /** The longest key or key list a {@link #problem} reason quotes. */
    private static final int MAX_QUOTED = 100;

    private PayloadMatch() {
    }

    /**
     * True when every entry of {@code match} holds on {@code payload}.
     *
     * @param payload the event's payload; null is the empty payload
     * @param match the match; null matches any payload
     * @return false as soon as one entry does not hold, or is invalid
     */
    public static boolean matches(Map<String, Object> payload, Map<String, Object> match) {
        if (match == null) {
            return true;
        }
        for (Map.Entry<String, Object> entry : match.entrySet()) {
            Object actual = payload == null ? null : payload.get(entry.getKey());
            if (!holds(entry.getValue(), actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean holds(Object expected, Object actual) {
        if (expected instanceof Map) {
            Object scalar = operand((Map<?, ?>) expected);
            if (scalar == null || !(actual instanceof List)) {
                return false;
            }
            String text = String.valueOf(scalar);
            for (Object element : (List<?>) actual) {
                if (isScalar(element) && text.equals(String.valueOf(element))) {
                    return true;
                }
            }
            return false;
        }
        if (!isScalar(expected)) {
            return false;
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    /** The scalar of a valid {@code {has: scalar}} object, else null. */
    private static Object operand(Map<?, ?> operator) {
        if (operator.size() != 1 || !operator.containsKey(HAS)) {
            return null;
        }
        Object scalar = operator.get(HAS);
        return scalar != null && isScalar(scalar) ? scalar : null;
    }

    /**
     * Null when {@code match} is valid, else the reason for the first invalid entry, such as {@code "'match.sources'
     * must be a scalar or {has: scalar}, found a list"}. Keys are not checked here (the controller refuses a blank key
     * itself).
     *
     * @param match the match; null is valid (any payload)
     */
    public static String problem(Map<String, Object> match) {
        if (match == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : match.entrySet()) {
            Object value = entry.getValue();
            String found = null;
            if (value instanceof Map) {
                Map<?, ?> operator = (Map<?, ?>) value;
                if (operand(operator) == null) {
                    found = describeOperator(operator);
                }
            } else if (!isScalar(value)) {
                found = "a list";
            }
            if (found != null) {
                return "'match." + cut(String.valueOf(entry.getKey())) + "' must be a scalar or {has: scalar}, found "
                        + found;
            }
        }
        return null;
    }

    /**
     * True when any entry of {@code match} is an operator object (a map value, valid or not): what the plan reader's
     * version gate asks (a plan below version 7 holds none, phase8-contracts.md 8.2).
     *
     * @param match the match; null holds none
     */
    public static boolean usesOperator(Map<String, Object> match) {
        if (match == null) {
            return false;
        }
        for (Object value : match.values()) {
            if (value instanceof Map) {
                return true;
            }
        }
        return false;
    }

    private static String describeOperator(Map<?, ?> operator) {
        if (operator.isEmpty()) {
            return "an empty mapping";
        }
        if (operator.size() != 1 || !operator.containsKey(HAS)) {
            return "a mapping with keys " + cut(String.valueOf(operator.keySet()));
        }
        Object scalar = operator.get(HAS);
        if (scalar == null) {
            return "{has: null}";
        }
        return scalar instanceof Map ? "{has: a mapping}" : "{has: a list}";
    }

    /** A string, a number, a boolean or null: anything but a map, a collection or an array. */
    private static boolean isScalar(Object value) {
        return !(value instanceof Map) && !(value instanceof Collection)
                && !(value != null && value.getClass().isArray());
    }

    private static String cut(String text) {
        return text.length() <= MAX_QUOTED ? text : text.substring(0, MAX_QUOTED) + "...";
    }
}
