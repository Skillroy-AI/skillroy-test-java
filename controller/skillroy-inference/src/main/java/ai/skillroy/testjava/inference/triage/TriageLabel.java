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

import ai.skillroy.testjava.scenario.Schemas.Doc;

/**
 * The first answer of a triage (phase5-contracts.md 11.2): a label for a failed run, from a model of the
 * {@code HAIKU_CLASS}, as a structured answer. A label is a reader's aid, never a verdict: the run's own
 * classification ({@code FailureKind}) stands.
 *
 * @param label what the failure most likely is
 * @param confidence from 0 to 1
 * @param oneLine the label's reason in one line
 */
public record TriageLabel(
        @Doc("what the failure most likely is") Label label,
        @Doc("how sure you are, from 0 to 1") double confidence,
        @Doc("the reason in one line of at most 30 words; name no person") String oneLine) {

    /** The labels of 11.2. */
    public enum Label {
        /** The application did something it should not (or failed to do what it should). */
        APPLICATION_DEFECT,
        /** The scenario is wrong: a step, an expectation or a query does not say what the application should do. */
        SCENARIO_DEFECT,
        /** The environment failed: the target, the agent, the database, the network or a setting. */
        ENVIRONMENT,
        /** The data the scenario relied on changed: a binding no longer holds, a row is missing or different. */
        DATA_DRIFT,
        /** A timing problem: something arrived too late or in an unexpected order. */
        TIMING,
        /** The evidence does not say. */
        UNKNOWN
    }
}
