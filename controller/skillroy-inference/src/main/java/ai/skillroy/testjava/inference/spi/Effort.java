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

package ai.skillroy.testjava.inference.spi;

import java.util.Locale;

/** How much the model may think before it answers (phase5-contracts.md 3.2; the Claude gateway's {@code output_config.effort}). */
public enum Effort {
    /** {@code low}. */
    LOW,
    /** {@code medium}. */
    MEDIUM,
    /** {@code high}. */
    HIGH;

    /**
     * The effort a settings text names ({@code low}, {@code medium}, {@code high}, any case).
     *
     * @throws IllegalArgumentException for any other text
     */
    public static Effort parse(String text) {
        if (text != null) {
            for (Effort e : values()) {
                if (e.name().equalsIgnoreCase(text.strip())) {
                    return e;
                }
            }
        }
        throw new IllegalArgumentException("an effort is low, medium or high");
    }

    /** The lower-case name the settings and the provider use. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
