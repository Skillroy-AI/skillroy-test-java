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

package ai.skillroy.testjava.agent.api.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What a {@link DialogTextExtractor} read from a dialog.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class DialogInfo {

    private final String extractorId;
    private final String title;
    private final String text;
    private final List<String> buttons;

    /**
     * @param extractorId which extractor produced this, reported as {@code extractor} by {@code swing.dialogs}
     * @param title may be null
     * @param text the message text, may be null
     * @param buttons button labels in display order; null means none; copied
     */
    public DialogInfo(String extractorId, String title, String text, List<String> buttons) {
        this.extractorId = extractorId;
        this.title = title;
        this.text = text;
        this.buttons = buttons == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(buttons));
    }

    public String extractorId() {
        return extractorId;
    }

    public String title() {
        return title;
    }

    public String text() {
        return text;
    }

    /** Unmodifiable, never null. */
    public List<String> buttons() {
        return buttons;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DialogInfo)) {
            return false;
        }
        DialogInfo other = (DialogInfo) o;
        return Objects.equals(extractorId, other.extractorId) && Objects.equals(title, other.title)
                && Objects.equals(text, other.text) && buttons.equals(other.buttons);
    }

    @Override
    public int hashCode() {
        return Objects.hash(extractorId, title, text, buttons);
    }

    @Override
    public String toString() {
        return "DialogInfo{extractorId=" + extractorId + ", title=" + title + ", text=" + text + ", buttons="
                + buttons + "}";
    }
}
