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

/** The tool vocabulary of V2 and V3 (phase7-contracts.md 6.3); V1 always uses the custom tools. */
public enum VisionTools {
    /** The Claude computer toolset (the default of V2 and V3). */
    TOOLSET,
    /** The provider-neutral custom tools ({@code click_at}, {@code drag}, {@code scroll}, {@code click_ref}). */
    CUSTOM;

    /**
     * The vocabulary of a host's option ({@code toolset | custom}).
     *
     * @throws IllegalArgumentException for another value
     */
    public static VisionTools parse(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "toolset" -> TOOLSET;
            case "custom" -> CUSTOM;
            default -> throw new IllegalArgumentException("the vision tools are toolset or custom, found " + text);
        };
    }
}
