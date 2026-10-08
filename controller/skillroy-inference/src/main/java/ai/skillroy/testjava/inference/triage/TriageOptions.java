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

package ai.skillroy.testjava.inference.triage;

import java.time.Clock;

/**
 * How a triage runs (phase5-contracts.md 11.2).
 *
 * @param labelsOnly true makes the label call only ({@code --labels-only}), no summary
 * @param clock what the triage's date and its records' times are read from; null means the system UTC clock
 */
public record TriageOptions(boolean labelsOnly, Clock clock) {

    /** Fills the clock. */
    public TriageOptions {
        clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** A label and a summary, the system clock. */
    public static TriageOptions defaults() {
        return new TriageOptions(false, null);
    }
}
