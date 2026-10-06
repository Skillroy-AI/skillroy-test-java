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

package ai.skillroy.testjava.scenario.resolve;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The identity of an environment the bindings were made for (phase2-contracts.md 8.2): named components (schema
 * checksum, seed, site, protocol major, …) and their hash. The components are kept beside the hash so that a
 * mismatch can say which component changed. Contains no transactional data, no plan and no pack version (D7, D8).
 *
 * <p>Immutable (components sorted by name); safe to share between threads.
 *
 * @param version the fingerprint format, 1
 * @param hash {@code "sha256:"} over the canonical JSON of the components
 * @param components component name to short text or checksum
 */
public record EnvFingerprint(int version, String hash, Map<String, String> components) {

    /** The fingerprint format this code computes. */
    public static final int VERSION = 1;

    /** Sorts and freezes the components. */
    public EnvFingerprint {
        Objects.requireNonNull(hash, "hash");
        components = Collections.unmodifiableMap(new TreeMap<>(components == null ? Map.of() : components));
    }
}
