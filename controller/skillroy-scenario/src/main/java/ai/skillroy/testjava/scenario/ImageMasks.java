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

import java.util.Map;

/**
 * The shape of an {@code expectImage} {@code mask} entry (phase7-contracts.md 8.1): {@code {by: <locator>}}, resolved
 * at the step through {@code swing.find}, or {@code {x, y, w, h}}, a rectangle in the image's coordinates. Checked by
 * the compiler and by the plan reader alike.
 */
final class ImageMasks {

    /** The most mask entries one step may have. */
    static final int MAX_MASKS = 64;

    private ImageMasks() {
    }

    /** Why a value is not a mask entry, or null when it is one. */
    static String problem(Object mask) {
        if (!(mask instanceof Map<?, ?> map)) {
            return "a mask is {by: <locator>} or {x, y, w, h}, found " + ScenarioLoader.describe(mask);
        }
        if (map.containsKey("by")) {
            if (map.size() != 1) {
                return "a locator mask has the one key 'by', found " + map.keySet();
            }
            if (!(map.get("by") instanceof Map<?, ?> by) || by.isEmpty()) {
                return "a mask's 'by' must be a locator object such as {id: entry}";
            }
            return null;
        }
        if (map.size() != 4 || !map.keySet().containsAll(java.util.List.of("x", "y", "w", "h"))) {
            return "a rectangle mask has the keys x, y, w and h, found " + map.keySet();
        }
        for (String key : java.util.List.of("x", "y", "w", "h")) {
            Object value = map.get(key);
            if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                    || Math.abs(n.doubleValue()) > 100_000) {
                return "a rectangle mask's '" + key + "' must be a whole number, found "
                        + ScenarioLoader.describe(value);
            }
            if (("w".equals(key) || "h".equals(key)) && n.longValue() <= 0) {
                return "a rectangle mask's '" + key + "' must be positive";
            }
        }
        return null;
    }
}
