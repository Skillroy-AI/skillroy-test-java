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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Part (1) of amendment A25, the key rule: which values of a run's slots are secrets because of the key they stand
 * under. A fixture field whose key {@link SecretKeys#looksSecret} names a secret (and every value below such a field;
 * a slot whose own name looks secret counts as such a field) is a secret. Phase 2 forbids such fixtures in compiled
 * plans (phase2-contracts.md 3.1 rule 5), so this matters for version 1 plans and for caller-supplied bindings; it
 * stays the backstop for values that reach a run some other way.
 *
 * <p>The value rule, part (2), lives in {@link SecretMask} ({@code skillroy-data}), the controller's one mask
 * (decision D26): {@link #mask} feeds it the values this class collects. {@link #maskKeys} applies the key rule where
 * the fixture structure itself is written.
 *
 * <p>Stateless; safe from any thread.
 */
final class FixtureSecrets {

    private static final int MAX_DEPTH = 200;

    private FixtureSecrets() {
    }

    /**
     * The secret values of a run's slots by the key rule.
     *
     * @param slots slot name to bound value (JSON-like)
     */
    static Set<String> collect(Map<String, ?> slots) {
        Set<String> secrets = new LinkedHashSet<>();
        for (Map.Entry<String, ?> e : slots.entrySet()) {
            collect(e.getKey(), e.getValue(), false, secrets, 0);
        }
        return secrets;
    }

    /**
     * True when a reference reaches a secret by the key rule (amendment A34 on the controller, by provenance): the
     * slot's name or a field on the reference path looks secret and the value reached is a secret by {@link #collect},
     * or the value reached is a structure that holds, at any depth, a field whose key looks secret and whose value
     * {@link #collect} counts as a secret. A null, a boolean or an empty text is never a secret.
     *
     * @param slot the referenced slot
     * @param fields the reference's fields ({@code $slot.a.b}: a, b)
     * @param value the value the reference reaches
     */
    static boolean reachesSecret(String slot, List<String> fields, Object value) {
        boolean secret = SecretKeys.looksSecret(slot);
        for (String field : fields) {
            secret |= SecretKeys.looksSecret(field);
        }
        Set<String> found = new LinkedHashSet<>();
        collect(fields.isEmpty() ? slot : fields.get(fields.size() - 1), value, secret, found, 0);
        return !found.isEmpty();
    }

    /** {@link SecretMask#of} the values {@link #collect} finds. */
    static SecretMask mask(Map<String, ?> slots) {
        Set<String> secrets = collect(slots);
        return secrets.isEmpty() ? SecretMask.NONE : SecretMask.of(secrets);
    }

    private static void collect(String key, Object value, boolean inSecret, Set<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        boolean secret = inSecret || SecretKeys.looksSecret(key);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collect(String.valueOf(e.getKey()), e.getValue(), secret, out, depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collect(key, item, secret, out, depth + 1);
            }
        } else if (secret && value != null && !(value instanceof Boolean)) {
            String text = Values.stringOf(value);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
    }

    /**
     * A copy with the value of every map entry whose key looks secret replaced by {@link SecretKeys#REDACTED} (the
     * key rule; applied regardless of the run's secrets, for the fixture structure itself).
     */
    static Object maskKeys(Object value) {
        return maskKeys(value, 0);
    }

    private static Object maskKeys(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                copy.put(key, SecretKeys.looksSecret(key) && e.getValue() != null ? SecretKeys.REDACTED
                        : maskKeys(e.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(maskKeys(item, depth + 1));
            }
            return copy;
        }
        return value;
    }
}
