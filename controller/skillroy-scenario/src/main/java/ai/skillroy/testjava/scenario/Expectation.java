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
import java.util.Map;
import java.util.Objects;

/**
 * One compiled expectation: a fact path, a matcher in normal form and an optional capability guard. In
 * {@code plan.json}: {@code {"path":…, "matcher":…}} plus {@code "capability": id} for a guarded expectation, which is
 * skipped (and makes the best outcome DEGRADED) when that capability is not OK on the target.
 *
 * <p>Matcher normal form: an object with exactly one of {@code equals}, {@code contains}, {@code matches}, {@code in},
 * {@code gt}, {@code gte}, {@code lt}, {@code lte}, {@code size}, {@code exists}, {@code not}; a scalar in the YAML
 * becomes {@code {"equals": scalar}}. Path and matcher may hold {@code $slot} references, resolved when the scenario
 * runs.
 *
 * <p>Immutable (the matcher is a deep unmodifiable copy); safe to share between threads.
 *
 * @param path the path text, for example {@code "loan.lines[code=$item.code].qty"}
 * @param matcher the matcher in normal form
 * @param capability the guarding capability id, or null
 */
public record Expectation(String path, Object matcher, String capability) implements Check {

    /**
     * @throws NullPointerException if {@code path} or {@code matcher} is null
     */
    public Expectation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(matcher, "matcher");
        matcher = Plan.freeze(matcher);
    }

    /** The {@code plan.json} shape; a new map on each call. */
    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", path);
        json.put("matcher", matcher);
        if (capability != null) {
            json.put("capability", capability);
        }
        return json;
    }

    /** A one-line description for messages and reports: {@code path {"equals":2}}. */
    @Override
    public String describe() {
        return path + " " + Values.safeWrite(matcher) + (capability == null ? "" : " [capability " + capability + "]");
    }
}
