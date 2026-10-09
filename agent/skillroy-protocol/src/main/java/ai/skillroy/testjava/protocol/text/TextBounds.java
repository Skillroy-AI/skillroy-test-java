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

package ai.skillroy.testjava.protocol.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cutting and sizing values that come from outside the agent's own code (a page, a channel, the target): the receiving
 * side's bounds of security fix S43 (phase4-contracts.md 4.6, P1 2.4 {@code eventRingChars}). Java 8, JDK only.
 *
 * <p>The size of a value is an estimate of its JSON text that never under-counts a string: a string counts its
 * length plus 2, a map key its length plus 3, a map or list 2 plus its entries, every other value 8. It is computed
 * with an early stop, so that sizing a value far larger than a bound costs no more than reaching the bound.
 *
 * <p>Stateless; safe from any thread.
 */
public final class TextBounds {

    /** What every value that is neither a string, a map nor a list counts. */
    public static final int SCALAR_CHARS = 8;

    /** Nesting deeper than this counts as over any bound (the protocol's codec refuses it anyway). */
    private static final int MAX_DEPTH = 200;

    private TextBounds() {
    }

    /**
     * The text cut at {@code max} UTF-16 code units, never inside a surrogate pair (one fewer when the cut would split
     * one); the text itself when it is not longer; null for null.
     *
     * @throws IllegalArgumentException if {@code max} is negative
     */
    public static String cut(String text, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative: " + max);
        }
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = max > 0 && Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    /**
     * The estimated JSON size of a value (see the class comment), stopping as soon as it exceeds {@code stopAbove}: the
     * answer is then some number greater than {@code stopAbove}, not the full size.
     */
    public static long size(Object value, long stopAbove) {
        return size(value, stopAbove, 0L, 0);
    }

    /** True when the estimated JSON size of the value exceeds {@code max}. */
    public static boolean exceeds(Object value, long max) {
        return size(value, max) > max;
    }

    private static long size(Object value, long stopAbove, long sofar, int depth) {
        if (depth > MAX_DEPTH) {
            return stopAbove + 1;
        }
        long total = sofar;
        if (value instanceof CharSequence) {
            return total + ((CharSequence) value).length() + 2;
        }
        if (value instanceof Map) {
            total += 2;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                Object key = e.getKey();
                total += (key == null ? 4 : String.valueOf(key).length()) + 3;
                if (total > stopAbove) {
                    return total;
                }
                total = size(e.getValue(), stopAbove, total, depth + 1);
                if (total > stopAbove) {
                    return total;
                }
            }
            return total;
        }
        if (value instanceof List) {
            total += 2;
            for (Object item : (List<?>) value) {
                total = size(item, stopAbove, total + 1, depth + 1);
                if (total > stopAbove) {
                    return total;
                }
            }
            return total;
        }
        return total + SCALAR_CHARS;
    }

    /**
     * A copy of a JSON-shaped value (maps, lists, strings, scalars) in which every string longer than
     * {@code maxString} is cut to it; map keys longer than {@code maxString} are cut as well. Other values are kept as
     * they are; the argument is not changed. Nesting deeper than 200 is replaced by null.
     */
    public static Object cutStrings(Object value, int maxString) {
        return cutStrings(value, maxString, 0);
    }

    private static Object cutStrings(Object value, int maxString, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (value instanceof String) {
            return cut((String) value, maxString);
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(cut(String.valueOf(e.getKey()), maxString), cutStrings(e.getValue(), maxString, depth + 1));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>(((List<?>) value).size());
            for (Object item : (List<?>) value) {
                out.add(cutStrings(item, maxString, depth + 1));
            }
            return out;
        }
        return value;
    }
}
