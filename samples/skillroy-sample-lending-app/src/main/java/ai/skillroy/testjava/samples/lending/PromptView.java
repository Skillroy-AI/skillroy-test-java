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

package ai.skillroy.testjava.samples.lending;

/**
 * Presents prompts to the user. The Swing implementation is {@link SwingPromptView}; without a view (headless, or in
 * engine unit tests) prompts are answered only through {@link PromptService#answer}.
 *
 * <p><b>Threading.</b> Both methods are called on the thread that asks (the engine thread) and must return without
 * waiting for the user: a Swing view posts its work to the event dispatch thread.
 */
public interface PromptView {

    /**
     * Shows the prompt. The view answers it by calling {@code service.answer(prompt.id(), choice)} when the user
     * picks a button.
     */
    void show(Prompt prompt, PromptService service);

    /** Removes the prompt from view; called once it was answered (by the view or otherwise) or abandoned. */
    void hide(Prompt prompt);
}
