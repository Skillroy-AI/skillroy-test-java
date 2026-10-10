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
 * The check of an {@code aiExpect} step (phase7-contracts.md 8.1, 8.4, decisions D310, D312, D354): a question about
 * the screen with the answer expected, always guarded by {@code inference.vision}. In {@code plan.json}:
 * {@code {"visual": {"question", "expect"}, "capability": "inference.vision"}}.
 *
 * <p>Evaluated only by the vision module's scenario player, when the session's merged matrix has
 * {@code inference.vision OK}; {@code PlanExecutor} skips it unconditionally ("no visual evaluator in this host"), the
 * capability's state being only the reason text, so the best outcome of such a plan there is DEGRADED. A verdict is
 * advisory: it never turns a failing scenario green.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param question the question, in the scenario author's words
 * @param expect {@code YES} or {@code NO}
 * @param capability always {@link #CAPABILITY}
 */
public record VisualCheck(String question, String expect, String capability) implements Check {

    /** The one guard of a visual check. */
    public static final String CAPABILITY = "inference.vision";

    /**
     * @throws IllegalArgumentException if the question is blank, {@code expect} is not {@code YES} or {@code NO}, or the
     *     capability is not {@link #CAPABILITY}
     */
    public VisualCheck {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("a visual check needs a question");
        }
        expect = expect == null ? "YES" : expect;
        if (!"YES".equals(expect) && !"NO".equals(expect)) {
            throw new IllegalArgumentException("a visual check expects YES or NO, found " + expect);
        }
        capability = capability == null ? CAPABILITY : capability;
        if (!CAPABILITY.equals(capability)) {
            throw new IllegalArgumentException("aiExpect is always guarded by " + CAPABILITY + ", found "
                    + capability);
        }
    }

    /** A check of this question expecting {@code expect}, guarded by {@link #CAPABILITY}. */
    public VisualCheck(String question, String expect) {
        this(question, expect, CAPABILITY);
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> visual = new LinkedHashMap<>();
        visual.put("question", question);
        visual.put("expect", expect);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("visual", visual);
        json.put("capability", capability);
        return json;
    }

    /** {@code aiExpect "<question>" YES [capability inference.vision]}. */
    @Override
    public String describe() {
        return "aiExpect \"" + Objects.requireNonNull(question) + "\" " + expect + " [capability " + capability + "]";
    }
}
