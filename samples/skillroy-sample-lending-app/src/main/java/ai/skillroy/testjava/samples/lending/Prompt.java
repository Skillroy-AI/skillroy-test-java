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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A modal question the engine asks: a stable id (for example {@code "unknown-item"}), the text shown to the user
 * and the button labels in display order. Immutable. Identity matters: two prompts with the same id asked one after
 * the other are different objects.
 */
public final class Prompt {

    private final String id;
    private final String text;
    private final List<String> buttons;

    /**
     * @param id stable identifier, not null
     * @param text text shown to the user, not null
     * @param buttons button labels, at least one, none null or duplicated
     */
    public Prompt(String id, String text, List<String> buttons) {
        this.id = Objects.requireNonNull(id, "id");
        this.text = Objects.requireNonNull(text, "text");
        if (buttons == null || buttons.isEmpty()) {
            throw new IllegalArgumentException("a prompt needs at least one button");
        }
        List<String> copy = new ArrayList<String>(buttons.size());
        for (String button : buttons) {
            if (button == null || copy.contains(button)) {
                throw new IllegalArgumentException("null or duplicate button: " + buttons);
            }
            copy.add(button);
        }
        this.buttons = Collections.unmodifiableList(copy);
    }

    public String id() {
        return id;
    }

    public String text() {
        return text;
    }

    /** @return the button labels in display order, unmodifiable */
    public List<String> buttons() {
        return buttons;
    }

    /**
     * @param choice a button label, matched exactly first and then ignoring case
     * @return the button label as declared, or null when no button matches
     */
    public String matchButton(String choice) {
        if (choice == null) {
            return null;
        }
        if (buttons.contains(choice)) {
            return choice;
        }
        String lower = choice.toLowerCase(Locale.ROOT);
        for (String button : buttons) {
            if (button.toLowerCase(Locale.ROOT).equals(lower)) {
                return button;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "Prompt{id=" + id + ", text=" + text + ", buttons=" + buttons + "}";
    }
}
