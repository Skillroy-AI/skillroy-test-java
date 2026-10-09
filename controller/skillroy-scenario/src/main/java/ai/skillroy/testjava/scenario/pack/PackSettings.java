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

package ai.skillroy.testjava.scenario.pack;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A pack's constants, overridable by an overlay (phase6-contracts.md 11.1, decision D145): an immutable map of string
 * keys to strings. A pack reads its variant-sensitive literals from {@link DomainPack#settings()} with a default
 * ({@code ctx.settings().get("lending.maxReturnSetting", "max_return")}), so that an overlay changes one constant with
 * {@code settings()} alone and replaces a resolver only when its logic differs. The keys are the pack's own
 * ({@code <packId>.<name>}), documented in the pack.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class PackSettings {

    /** No setting: every {@link #get} answers its default. */
    public static final PackSettings EMPTY = new PackSettings(Map.of());

    private final Map<String, String> values;

    private PackSettings(Map<String, String> values) {
        this.values = values;
    }

    /**
     * Settings holding a copy of {@code values}, sorted by key.
     *
     * @throws NullPointerException if {@code values} or one of its keys or values is null
     */
    public static PackSettings of(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        TreeMap<String, String> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value of " + key)));
        return copy.isEmpty() ? EMPTY : new PackSettings(Collections.unmodifiableMap(copy));
    }

    /** The value of {@code key}, or {@code dflt} when it is not set. */
    public String get(String key, String dflt) {
        String value = key == null ? null : values.get(key);
        return value != null ? value : dflt;
    }

    /** Every setting, sorted by key; unmodifiable. */
    public Map<String, String> asMap() {
        return values;
    }

    /**
     * These settings with {@code overrides} put over them: a key of {@code overrides} replaces the same key here.
     *
     * @throws NullPointerException if {@code overrides} or one of its keys or values is null
     */
    public PackSettings with(Map<String, String> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        TreeMap<String, String> merged = new TreeMap<>(values);
        overrides.forEach((key, value) -> merged.put(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value of " + key)));
        return of(merged);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PackSettings other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "PackSettings" + values;
    }
}
