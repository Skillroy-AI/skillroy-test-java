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
 * Dialogs an application draws inside another window (not {@code java.awt.Dialog}): a message panel on a glass pane,
 * an internal frame, a prompt laid over the content. Without it such a dialog is invisible to {@code swing.dialogs},
 * cannot be answered with {@code swing.dismiss}, and input aimed past it is not refused with {@code MODAL_OPEN}.
 *
 * <p>The root a locator reports is the component whose tree is the dialog: {@code swing.dialogs} lists it with
 * {@code inWindow: true} and reads it through {@link DialogTextExtractor#extract(java.awt.Component)} or the generic
 * reading, {@code swing.dismiss} presses buttons inside it, and while it {@link #blocksInput blocks input},
 * {@code swing.click}, {@code swing.type} and {@code swing.key} aimed at a component of the same window outside it
 * answer {@code MODAL_OPEN} unless forced. Roots that are not showing, that are windows themselves or that lie outside
 * the window asked about are ignored.
 *
 * <p>Called on the EDT; must be fast, must not change the component tree, and must not block.
 */
public interface DialogLocator {

    /**
     * Showing in-window dialog roots inside the window, outermost first; empty when none, never null.
     *
     * @param window a showing window of the application
     */
    java.util.List<java.awt.Component> dialogRoots(java.awt.Window window);

    /**
     * True when the dialog blocks input to the rest of its window (the default). A dialog that does not block is still
     * listed by {@code swing.dialogs} (with {@code modal: false}) but never causes {@code MODAL_OPEN}.
     *
     * @param root a root this locator reported
     */
    default boolean blocksInput(java.awt.Component root) {
        return true;
    }
}
