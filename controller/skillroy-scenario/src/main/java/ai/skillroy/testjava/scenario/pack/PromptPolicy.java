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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.protocol.Event;

/**
 * What the executor does when a prompt topic arrives that no step expected. Lets a pack handle known interrupting
 * prompts (a session lock, a housekeeping reminder) deterministically instead of failing the scenario.
 *
 * <p>A prompt is expected, and never reaches the policy, when the call that saw it dealt with it (a wait on a prompt
 * topic that returned it, or a prompt-answering call made after the prompt was published), or when a later call of
 * the same step, or a call of the next step that has calls, handles prompts (see
 * {@link CompileContext#markHandlesPrompt}). The scenario key {@code on_prompt: {unexpected: fail}} selects
 * {@link #FAIL}; {@code on_prompt: {unexpected: pack}}, and the absence of the key, select
 * {@link DomainPack#promptPolicy()}; any other value is refused when the scenario is loaded and when a plan is read
 * (phase8-contracts.md 9.2, gap 111; before Phase 8 any other value silently selected the pack's policy).
 *
 * <p>Called on the executor's thread; it may call the agent (to answer the prompt), and must be bounded.
 */
public interface PromptPolicy {

    /** Fails the scenario on every unexpected prompt. */
    PromptPolicy FAIL = (promptEvent, agent) -> PromptDecision.fail(
            "unexpected prompt (" + promptEvent.topic() + "): " + promptEvent.payload());

    /**
     * @param promptEvent the event on one of {@link SyncPolicy#promptTopics()}
     * @param agent the scenario's connection, for answering the prompt
     * @return the decision; null is treated as {@link PromptDecision.Action#FAIL}
     */
    PromptDecision onUnexpectedPrompt(Event promptEvent, AgentConnection agent);
}
