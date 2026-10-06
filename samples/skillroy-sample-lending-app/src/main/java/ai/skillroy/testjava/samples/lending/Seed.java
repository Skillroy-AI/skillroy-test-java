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

package ai.skillroy.testjava.samples.lending;

import java.util.Locale;

/**
 * The two seeded environments. They differ in staff codes, role assignments, limits, the item catalogue, which
 * waiver rule is active and the first loan id, so that a scenario written against one cannot hard-code values that
 * work on the other. Selected with the system property {@code lending.seed}.
 */
public enum Seed {
    /** The default environment. */
    A,
    /** The second environment. */
    B;

    /**
     * @param text {@code "A"} or {@code "B"} (any case); null or blank means {@link #A}
     * @throws IllegalArgumentException for any other value
     */
    public static Seed parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return A;
        }
        String t = text.trim().toUpperCase(Locale.ROOT);
        for (Seed seed : values()) {
            if (seed.name().equals(t)) {
                return seed;
            }
        }
        throw new IllegalArgumentException("unknown lending seed '" + text + "' (expected A or B)");
    }
}
