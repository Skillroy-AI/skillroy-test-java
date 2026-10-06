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

package ai.skillroy.testjava.data;

import ai.skillroy.testjava.protocol.SecretKeys;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces secrets in anything about to be written or printed: the value rule of amendment A25 (part 2), which
 * phase2-contracts.md 4 and decision D26 make the controller's one mask, fed by {@code credentials.yaml}
 * ({@link Credentials#mask()}) and by whatever else a run knows to be secret ({@link #plus}).
 *
 * <p><b>By value only.</b> Keys are never looked at: a mask that also redacted by key would destroy the contract's own
 * fields ({@code "credential": {"realm": ...}} in a bindings file, {@code inputTokens} in the cost ledger). The key
 * rule of A25 ({@code SecretKeys.looksSecret}) belongs to the code that writes a fixture structure, not here.
 *
 * <p>The rule, for each secret value (empty texts are never secrets):
 * <ul>
 *   <li>a string equal to it becomes {@link #REDACTED} (the whole value), and so does a number whose text equals it
 *       (a {@code BigDecimal} by its plain text; booleans never);</li>
 *   <li>inside a longer string, a secret of {@link #minLengthInText()} or more characters is replaced wherever it
 *       occurs. The threshold is {@link #MIN_SUBSTRING} (8) unless the environment's {@code credentials.yaml} sets
 *       {@code masking.minLengthInText} (phase2-contracts.md 4; the owner's decision of 2026-10-06 on §17 Q14);</li>
 *   <li>a shorter secret is replaced inside a longer string only where it stands in double quotes
 *       ({@code "1111"} becomes {@code "<redacted>"}), the way JSON text and the executor's messages write a string
 *       value. Elsewhere it is left: a four-digit code cannot be told from a number that happens to have the same
 *       digits (A25, §17 Q14: test accounts should use credentials of at least the threshold's length, or the
 *       environment lowers the threshold and accepts the false redactions).</li>
 * </ul>
 *
 * <p>Immutable once built; thread-safe.
 */
public final class SecretMask {

    /** What a secret is replaced by: {@code "<redacted>"}. */
    public static final String REDACTED = SecretKeys.REDACTED;

    /** The default of {@link #minLengthInText()}: the shortest secret that is also replaced inside a longer string. */
    public static final int MIN_SUBSTRING = 8;

    /** The largest threshold a mask accepts; a secret longer than the threshold is only ever masked as a whole value. */
    public static final int MAX_MIN_SUBSTRING = 1000;

    /** A mask that knows no secret and changes nothing. */
    public static final SecretMask NONE = new SecretMask(Set.of(), MIN_SUBSTRING);

    private static final int MAX_DEPTH = 200;
    /** What replaces a value nested deeper than {@link #MAX_DEPTH}: it was not looked at, so it is not passed on. */
    private static final String TOO_DEEP = "<too deep>";
    private static final Comparator<String> LONGEST_FIRST =
            Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder());

    private final Set<String> exact;
    /** The threshold of this mask: {@link #MIN_SUBSTRING} unless built with another one. */
    private final int minLengthInText;
    /** Secrets of at least the threshold's length, longest first so that one containing another is replaced whole. */
    private final List<String> substrings;
    /** Shorter secrets in double quotes, longest first. */
    private final List<String> quoted;

    private SecretMask(Set<String> secrets, int minLengthInText) {
        if (minLengthInText < 1 || minLengthInText > MAX_MIN_SUBSTRING) {
            throw new IllegalArgumentException("minLengthInText must be from 1 to " + MAX_MIN_SUBSTRING);
        }
        this.exact = Set.copyOf(secrets);
        this.minLengthInText = minLengthInText;
        List<String> longOnes = new ArrayList<>();
        List<String> shortOnes = new ArrayList<>();
        for (String secret : secrets) {
            if (secret.length() >= minLengthInText) {
                longOnes.add(secret);
            } else {
                shortOnes.add('"' + secret + '"');
            }
        }
        longOnes.sort(LONGEST_FIRST);
        shortOnes.sort(LONGEST_FIRST);
        this.substrings = List.copyOf(longOnes);
        this.quoted = List.copyOf(shortOnes);
    }

    /** A mask of these secret values with the default threshold; nulls and empty texts are ignored. */
    public static SecretMask of(Collection<String> secretValues) {
        return NONE.plus(secretValues);
    }

    /**
     * A mask of these secret values whose threshold is {@code minLengthInText} (the environment's
     * {@code masking.minLengthInText}); nulls and empty texts are ignored. {@link #plus} keeps the threshold.
     *
     * @throws IllegalArgumentException if the threshold is below 1 or above {@link #MAX_MIN_SUBSTRING}
     */
    public static SecretMask of(Collection<String> secretValues, int minLengthInText) {
        return new SecretMask(Set.of(), minLengthInText).plus(secretValues);
    }

    /** A mask that knows these secrets as well, with the same threshold; this one is unchanged. */
    public SecretMask plus(Collection<String> moreSecretValues) {
        if (moreSecretValues == null || moreSecretValues.isEmpty()) {
            return this;
        }
        Set<String> all = new LinkedHashSet<>(exact);
        for (String value : moreSecretValues) {
            if (value != null && !value.isEmpty()) {
                all.add(value);
            }
        }
        return all.size() == exact.size() ? this : new SecretMask(all, minLengthInText);
    }

    /** True when the mask knows no secret. */
    public boolean isEmpty() {
        return exact.isEmpty();
    }

    /** The shortest secret this mask also replaces where it occurs inside a longer string. */
    public int minLengthInText() {
        return minLengthInText;
    }

    /**
     * A deep copy of a JSON-like value (maps, lists, strings, numbers, booleans, null) in which every string and number
     * is masked by the rule of the class comment. Map keys are kept as they are; other objects are returned as they
     * are, so a caller that may hold sets, arrays or records brings them into JSON form first (the run log does). A
     * value nested deeper than 200 levels becomes {@code "<too deep>"}. With no secrets, the value itself is
     * returned.
     */
    public Object apply(Object jsonLike) {
        return exact.isEmpty() ? jsonLike : mask(jsonLike, 0);
    }

    /** {@link #apply(Object)} for one text; null stays null. */
    public String apply(String text) {
        return text == null || exact.isEmpty() ? text : maskString(text);
    }

    private Object mask(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return TOO_DEEP;
        }
        if (value instanceof String s) {
            return maskString(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(e.getKey(), mask(e.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(mask(item, depth + 1));
            }
            return copy;
        }
        if (value instanceof Number n && !(value instanceof Double d && (d.isNaN() || d.isInfinite()))) {
            String text = n instanceof BigDecimal b ? b.toPlainString() : String.valueOf(n);
            return exact.contains(text) ? REDACTED : value;
        }
        return value;
    }

    private String maskString(String s) {
        if (exact.contains(s)) {
            return REDACTED;
        }
        String out = s;
        for (String secret : substrings) {
            if (out.contains(secret)) {
                out = out.replace(secret, REDACTED);
            }
        }
        for (String secret : quoted) {
            if (out.contains(secret)) {
                out = out.replace(secret, '"' + REDACTED + '"');
            }
        }
        return out;
    }

    /** The number of secrets only, never one of them. */
    @Override
    public String toString() {
        return "SecretMask[" + exact.size() + " secrets]";
    }
}
