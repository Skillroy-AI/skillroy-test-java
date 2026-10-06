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

/**
 * Reads application-specific dialogs the generic extractor cannot (custom-painted message boxes). EDT. Without it a
 * scenario could not see what a custom prompt says.
 *
 * <p>Called on the EDT; must be fast and must not change the window.
 */
public interface DialogTextExtractor {

    /** Title, text and buttons of the dialog; null = not mine. */
    DialogInfo extract(java.awt.Window window);

    /**
     * Title, text and buttons of a dialog the application draws inside another window, as a {@link DialogLocator}
     * reported it; null = not mine (the default, so an extractor written for dialog windows only stays valid). A
     * dialog that is a window is always passed to {@link #extract(java.awt.Window)} instead.
     *
     * @param root the root component of the in-window dialog
     */
    default DialogInfo extract(java.awt.Component root) {
        return null;
    }
}
