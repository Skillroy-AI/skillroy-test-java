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

import java.awt.Component;
import java.awt.Rectangle;
import java.util.List;

/**
 * Which components show a secret: a capture blanks their pixels before the image leaves the JVM, and every reader of
 * the tree (the snapshot, the hit-test, the marks, the dialog reader) withholds their text (phase7-contracts.md
 * section 3.6, A64). The built-in rule masks every {@code javax.swing.JPasswordField} without a registration.
 *
 * <p>Called on the EDT inside the capture's, the snapshot's or the hit-test's read, for every component in the tree;
 * must be fast, lock-free and must not change the component. A mask that throws is logged once per agent start and the
 * component is then treated as masked (blanked, its text withheld): the safe side.
 *
 * <p>Phase 7 security fix (S106, S122, S123): a masked label's or button's name is withheld with its text when it is
 * that text (Swing's accessible-name fallback); several registered masks combine by union (one mask's
 * {@link #regions} never cancel another mask's whole-component blanking); and a rectangle {@link #regions} blank
 * withholds the text under it too.
 */
public interface CaptureMask {

    /** True to blank the component's whole rectangle and withhold its text. */
    boolean masks(Component component);

    /**
     * Rectangles inside the component to blank instead of the whole (a table's cells, a row's column), in the
     * component's own coordinates; null or empty means the whole rectangle when {@link #masks} is true, nothing when
     * it is false. With {@link #masks} true as well, only these rectangles are blanked and all of the component's text
     * is withheld (the conservative per-column form). With {@link #masks} false, the text under these rectangles is
     * withheld (phase7-contracts.md section 3.6, Phase 7 security fix, S122): a component a rectangle holds, or a
     * label, button or field it meets, is treated as masked, and a table's cell or a list's item it meets has its
     * text withheld; the rest of the component's text stays.
     */
    default List<Rectangle> regions(Component component) {
        return null;
    }
}
