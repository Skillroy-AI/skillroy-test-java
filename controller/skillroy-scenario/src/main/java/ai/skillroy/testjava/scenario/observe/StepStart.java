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


package ai.skillroy.testjava.scenario.observe;

/**
 * A step starts: of a run (a plan step, or {@code verify}), or of a session.
 *
 * @param id the step id ({@code s1}, {@code verify}; a session's {@code s1, s2, …})
 * @param verb the verb, or null for a session's raw call
 * @param line the scenario line, 0 when none
 * @param origin who acted in a session step ({@code PERSON}, {@code MODEL}, {@code SYSTEM}, {@code CLIENT}), null in a
 *     run; never the intent text
 */
public record StepStart(String id, String verb, int line, String origin) {

    /** A run's step. */
    public StepStart(String id, String verb, int line) {
        this(id, verb, line, null);
    }
}
