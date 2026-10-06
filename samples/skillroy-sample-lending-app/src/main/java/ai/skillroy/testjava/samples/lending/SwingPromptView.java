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

import java.awt.EventQueue;
import java.awt.Window;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Shows prompts as {@link PromptDialog}s. Both {@link PromptView} methods post to the event dispatch thread and
 * return at once; the modal dialog then runs its own event loop on the event dispatch thread until it is closed.
 * A prompt answered before its dialog appears is never shown.
 */
public final class SwingPromptView implements PromptView {

    private final Window owner;
    private final Map<Prompt, PromptDialog> open = new IdentityHashMap<Prompt, PromptDialog>();

    /** @param owner the window that owns the dialogs, may be null */
    public SwingPromptView(Window owner) {
        this.owner = owner;
    }

    @Override
    public void show(Prompt prompt, PromptService service) {
        EventQueue.invokeLater(() -> {
            if (service.current() != prompt) {
                return;
            }
            PromptDialog dialog = new PromptDialog(owner, prompt, service);
            open.put(prompt, dialog);
            try {
                dialog.setVisible(true);
            } finally {
                open.remove(prompt);
            }
        });
    }

    @Override
    public void hide(Prompt prompt) {
        EventQueue.invokeLater(() -> {
            PromptDialog dialog = open.get(prompt);
            if (dialog != null) {
                dialog.dispose();
            }
        });
    }

    /** @return the dialog showing the prompt, or null. Event dispatch thread. */
    public PromptDialog dialogFor(Prompt prompt) {
        return open.get(prompt);
    }
}
