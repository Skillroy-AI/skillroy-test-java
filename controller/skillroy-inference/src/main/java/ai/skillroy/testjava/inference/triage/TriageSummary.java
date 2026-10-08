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

import java.util.List;

/**
 * The second answer of a triage (phase5-contracts.md 11.2): a summary of a failed run for a person, from a model of
 * the {@code SONNET_CLASS}, as a structured answer. The proposal is prose for a person; nothing is applied (no healer,
 * ai-inference F6).
 *
 * @param summary what most likely happened, at most 200 words
 * @param lookAt what a person should look at first (files, records, steps), most useful first
 * @param proposal what a person might change, in prose; empty when there is nothing to propose
 */
public record TriageSummary(
        @Doc("what most likely happened, at most 200 words; name no person") String summary,
        @Doc("what a person should look at first: files, records or steps, most useful first") List<String> lookAt,
        @Doc("what a person might change, in prose; empty when there is nothing to propose") String proposal) {

    /** Copies the list; null is empty. */
    public TriageSummary {
        lookAt = lookAt == null ? List.of() : List.copyOf(lookAt);
    }
}
