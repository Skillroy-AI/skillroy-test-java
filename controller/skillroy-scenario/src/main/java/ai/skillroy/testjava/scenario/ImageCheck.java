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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The check of an {@code expectImage} step (phase7-contracts.md 8.1, 8.4, 6.7, decisions D313-D315): the step's own
 * {@code swing.capture} result compared exactly, after masks, with the baseline of the current render key under
 * {@code label}. In {@code plan.json}: {@code {"image": {"label", "masks", "maxDiffPixels"}}}.
 *
 * <p>Evaluated by {@code PlanExecutor} and the scenario player alike through {@code ImageSteps} and
 * {@code ImageCompare}, from the step's capture call result; the expectation retry does not apply (one capture, one
 * compare). Unguarded: {@link #capability()} is null.
 *
 * <p>Immutable (the masks are a deep unmodifiable copy); safe to share between threads.
 *
 * @param label the baseline's label, {@code [A-Za-z0-9][A-Za-z0-9._-]{0,63}}
 * @param masks the masks as written: {@code {by: <locator>}} (resolved at the step through {@code swing.find}) or
 *     {@code {x, y, w, h}} in the image's coordinates
 * @param maxDiffPixels how many differing pixels still hold, 0 to 10000
 */
public record ImageCheck(String label, List<Object> masks, int maxDiffPixels) implements Check {

    /** What a label looks like: it names a file below the bindings directory, so nothing else is allowed. */
    public static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** The largest {@code maxDiffPixels} a scenario may write. */
    public static final int MAX_DIFF_PIXELS = 10_000;

    /**
     * @throws IllegalArgumentException if the label is malformed or {@code maxDiffPixels} is outside 0 to 10000
     */
    @SuppressWarnings("unchecked")
    public ImageCheck {
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("an image label must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}, found "
                    + label);
        }
        masks = masks == null ? List.of() : (List<Object>) Plan.freeze(masks);
        if (maxDiffPixels < 0 || maxDiffPixels > MAX_DIFF_PIXELS) {
            throw new IllegalArgumentException("maxDiffPixels must be from 0 to " + MAX_DIFF_PIXELS + ", found "
                    + maxDiffPixels);
        }
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("label", label);
        image.put("masks", masks);
        image.put("maxDiffPixels", maxDiffPixels);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("image", image);
        return json;
    }

    /** {@code image <label>} with the margin when it is not 0. */
    @Override
    public String describe() {
        return "image " + label + (maxDiffPixels == 0 ? "" : " (maxDiffPixels " + maxDiffPixels + ")");
    }

    /** Unguarded. */
    @Override
    public String capability() {
        return null;
    }
}
