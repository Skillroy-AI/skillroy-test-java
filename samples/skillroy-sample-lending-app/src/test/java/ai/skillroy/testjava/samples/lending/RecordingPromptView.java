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

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

/**
 * A headless {@link PromptView} for engine tests: records what was shown and hidden, and answers like a user pressing
 * a button (through {@link PromptService#answer(String, String)} with the shown prompt's id, as the Swing dialog does).
 */
final class RecordingPromptView implements PromptView {

    private final List<Prompt> shown = new ArrayList<Prompt>();
    private final List<Prompt> hidden = new ArrayList<Prompt>();
    private PromptService service;

    @Override
    public synchronized void show(Prompt prompt, PromptService s) {
        shown.add(prompt);
        service = s;
        notifyAll();
    }

    @Override
    public synchronized void hide(Prompt prompt) {
        hidden.add(prompt);
        notifyAll();
    }

    synchronized List<Prompt> shown() {
        return new ArrayList<Prompt>(shown);
    }

    /**
     * Waits (bounded) until at least {@code count} prompts have been shown in this view, then returns them all.
     * {@link PromptService#ask} tells its listeners (and so the engine listeners) before it shows the prompt in the
     * view, so a test woken by a {@code PROMPT_SHOWN} event must wait for the view rather than read it at once.
     */
    synchronized List<Prompt> awaitShown(int count) {
        long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
        while (shown.size() < count) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                fail(count + " prompt(s) expected in the view, shown: " + shown);
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        return new ArrayList<Prompt>(shown);
    }

    synchronized List<Prompt> hidden() {
        return new ArrayList<Prompt>(hidden);
    }

    /** Waits (bounded) until the open prompt has been shown in this view, then presses the button. */
    synchronized void press(String choice) {
        long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
        while (true) {
            Prompt open = service == null ? null : service.current();
            if (open != null && !shown.isEmpty() && shown.get(shown.size() - 1) == open) {
                if (!service.answer(open.id(), choice)) {
                    fail("prompt " + open.id() + " did not take the answer " + choice);
                }
                return;
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                fail("no prompt shown to press " + choice + " on");
            }
            try {
                wait(Math.min(left, 50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }
}
