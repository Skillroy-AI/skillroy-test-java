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
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.text.SecretPairs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fact as the mask leaves it, where the masked-value rule applies (phase5-contracts.md 12.2: a session always, a run
 * with {@link RunOptions#expectationsOnMaskedValues()}; phase8-contracts.md 8.3, 8.5, 8.7; Phase 8 security fixes
 * S173, S174, FC). Every filter, regex, index, {@code [json]} and {@code size} of such a check, observe, fact read or
 * capture reads this view, never the fact as read, so that no comparison and no search a model or a client writes ever
 * runs over a secret, and its answer, its reason and its time are the same for a right guess and a wrong one.
 *
 * <p>The view of a value is its JSON form ({@link RunLog#jsonForm}) through, in order:
 * <ul>
 *   <li>the run's or the session's {@link SecretMask} (the secret values it knows: credentials, fixture secrets);</li>
 *   <li>the key rule of the model-facing mask (phase5-contracts.md 6.2, A25): a map entry whose key
 *       {@link SecretKeys#looksSecret} names, unless its value is null, becomes {@value SecretKeys#REDACTED}, and in
 *       every text the value of a secret-keyed pair ({@link SecretPairs}: {@code password=…}, {@code "token": …},
 *       escaped JSON inside JSON, an object or a list value).</li>
 * </ul>
 * A value {@code [json]} parses is put through the view again before the path goes on, so that a key the text held
 * meets the key rule as a map key.
 *
 * <p>The marker counts as a secret ({@link #holdsMarker}): a value that holds {@value SecretKeys#REDACTED} anywhere
 * is a secret for every check, and a filter whose field holds it on any element compares nothing ("holds a secret").
 * The view is uncut: a bound that cut it would make {@code [json]} fail where the fact does not.
 *
 * <p>Immutable; safe from any thread.
 */
final class FactView {

    /** What a secret becomes, and so what a value that holds one holds. */
    static final String MARKER = SecretKeys.REDACTED;

    private static final int MAX_DEPTH = 200;

    private final SecretMask mask;

    private FactView(SecretMask mask) {
        this.mask = mask == null ? SecretMask.NONE : mask;
    }

    /** The view through {@code mask} (the session's or the run's, as it stands now). */
    static FactView of(SecretMask mask) {
        return new FactView(mask);
    }

    /**
     * The view of a value read from the target (see the class comment); a new value, the argument unchanged.
     *
     * @throws IllegalArgumentException if the value has no JSON form (nested deeper than 200, a number JSON cannot
     *     write): it cannot be masked, so it is not read
     */
    Object apply(Object value) {
        Object form;
        try {
            form = value == null ? null : RunLog.jsonForm(value, 0);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the value cannot be masked (" + e.getClass().getSimpleName() + ")");
        }
        return keys(mask.apply(form), 0);
    }

    private static Object keys(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return MARKER;
        }
        if (value instanceof String s) {
            return SecretPairs.redact(s, SecretKeys::looksSecret);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, SecretKeys.looksSecret(key) && e.getValue() != null ? MARKER
                        : keys(e.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(keys(item, depth + 1));
            }
            return out;
        }
        return value;
    }

    /**
     * True when a value of the view holds the marker: a text that contains it, or a map or list that holds such a text
     * at any depth (deeper than 200 counts as holding it).
     */
    static boolean holdsMarker(Object value) {
        return holdsMarker(value, 0);
    }

    private static boolean holdsMarker(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return true;
        }
        if (value instanceof String s) {
            return s.contains(MARKER);
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (holdsMarker(item, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (holdsMarker(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
