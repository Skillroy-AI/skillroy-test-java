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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.ActionFirer;
import ai.skillroy.testjava.agent.api.spi.DialogInfo;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;
import ai.skillroy.testjava.agent.api.spi.NameEnricher;
import ai.skillroy.testjava.samples.lending.IconKey;
import ai.skillroy.testjava.samples.lending.PromptButton;
import ai.skillroy.testjava.samples.lending.PromptDialog;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Teaches the generic Swing module the desk's custom widgets, which it cannot read or press on its own:
 *
 * <ul>
 *   <li>{@link NameEnricher}: an {@code IconKey} (icon only, no text, no accessible name) is named by its
 *       {@code actionId} ({@code "DUE"}); a prompt's custom-painted {@code PromptButton} by its label
 *       ({@code "OK"}), which also lets {@code swing.dismiss {button}} find it.</li>
 *   <li>{@link LocatorResolver}, strategy {@value #ACTION}: {@code {action: "DUE"}} finds the keypad key.</li>
 *   <li>{@link ActionFirer}: the ACTION click rung calls {@code IconKey.perform()} (the key's action, without its
 *       focus-gated debounce, which ignores a programmatic click on an unfocused window) and
 *       {@code PromptButton.press()}.</li>
 *   <li>{@link DialogTextExtractor}: a {@code PromptDialog} paints its text with {@code Graphics.drawString}; its
 *       text and buttons are read from the dialog's own accessors (extractor id {@value #EXTRACTOR}).</li>
 * </ul>
 *
 * <p>Every method is called on the event dispatch thread, is fast and changes nothing except {@link #fire}.
 * Stateless.
 */
final class LendingWidgets implements NameEnricher, LocatorResolver, ActionFirer, DialogTextExtractor {

    /** The locator strategy for keypad keys. */
    static final String ACTION = "action";
    /** Extractor id reported by {@code swing.dialogs}. */
    static final String EXTRACTOR = "lending";

    private static final Set<String> STRATEGIES = Collections.singleton(ACTION);

    @Override
    public String nameFor(Component component) {
        if (component instanceof IconKey) {
            return ((IconKey) component).actionId();
        }
        if (component instanceof PromptButton) {
            return ((PromptButton) component).label();
        }
        return null;
    }

    @Override
    public Set<String> strategies() {
        return STRATEGIES;
    }

    @Override
    public List<Component> resolve(String strategy, String value, Component root) {
        List<Component> found = new ArrayList<Component>();
        if (ACTION.equals(strategy) && value != null && root != null) {
            collectKeys(root, value, found);
        }
        return found;
    }

    private static void collectKeys(Component c, String actionId, List<Component> out) {
        if (c instanceof IconKey && actionId.equals(((IconKey) c).actionId())) {
            out.add(c);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collectKeys(child, actionId, out);
            }
        }
    }

    @Override
    public boolean canFire(Component component) {
        return component instanceof IconKey || component instanceof PromptButton;
    }

    @Override
    public void fire(Component component) {
        if (component instanceof IconKey) {
            ((IconKey) component).perform();
        } else if (component instanceof PromptButton) {
            ((PromptButton) component).press();
        }
    }

    @Override
    public DialogInfo extract(Window window) {
        if (!(window instanceof PromptDialog)) {
            return null;
        }
        PromptDialog dialog = (PromptDialog) window;
        String title = dialog.getTitle();
        return new DialogInfo(EXTRACTOR, title == null || title.isEmpty() ? null : title, dialog.promptText(),
                dialog.buttonLabels());
    }
}
