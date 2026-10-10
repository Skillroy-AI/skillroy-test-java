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

package ai.skillroy.testjava.vision;

import java.util.Locale;

/**
 * The tier of a vision session (phase7-contracts.md 6.2, design 3.10): what the model sees and how a click is made.
 * In every tier the model sees a redacted {@code PAINT} capture of the target's own windows (D341).
 */
public enum VisionTier {
    /** Marks on the capture; the model answers with a mark, grounded by a hit-test; the existing rungs act. */
    V1,
    /** The computer toolset (or the custom tools with {@code click_at}): a pixel, hit-tested and recorded. */
    V2,
    /** V2 acting through Robot ({@code via: ROBOT}, {@code requireFocus: true}); needs a person's robot flag. */
    V3;

    /**
     * The tier of a host's option ({@code v1 | v2 | v3}), or null for {@code off}.
     *
     * @throws IllegalArgumentException for another value
     */
    public static VisionTier parse(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "v1" -> V1;
            case "v2" -> V2;
            case "v3" -> V3;
            case "off" -> null;
            default -> throw new IllegalArgumentException("the vision tier is v1, v2, v3 or off, found " + text);
        };
    }

    /** The option's spelling: {@code v1}, {@code v2}, {@code v3}. */
    public String option() {
        return name().toLowerCase(Locale.ROOT);
    }
}
