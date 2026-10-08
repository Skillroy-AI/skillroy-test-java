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

import java.time.Duration;
import java.util.Objects;

/**
 * One expectation an interactive session evaluated now (phase5-contracts.md 12.2): the path, the matcher as given,
 * the value found and whether the matcher held.
 *
 * <p>Immutable (values are frozen); safe to share between threads.
 *
 * @param path the expectation path
 * @param matcher the matcher as given (a scalar or a matcher object, references in place)
 * @param actual the value at the path, masked; null when the path did not resolve
 * @param held whether the matcher held
 * @param reason why it did not hold, or null
 * @param duration how long the evaluation took
 */
public record CheckOutcome(String path, Object matcher, Object actual, boolean held, String reason,
                           Duration duration) {

    /** Freezes the values. */
    public CheckOutcome {
        Objects.requireNonNull(path, "path");
        matcher = Plan.freeze(matcher);
        actual = Plan.freeze(actual);
        duration = duration == null ? Duration.ZERO : duration;
    }
}
