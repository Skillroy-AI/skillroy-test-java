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

package ai.skillroy.testjava.agent.swing;

import java.awt.Canvas;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Rectangle;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JViewport;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effective visible area without a display (phase8-contracts.md 3.3, 13.1 row 2): {@code area} and
 * {@code collapsedBy} over a root container (no window can be built headless and {@code isShowing()} is false for
 * every component here, so {@code onScreen} and the outside-the-window case are {@code CollapsedPaneDisplayTest}'s): a
 * zero-width ancestor, a zero-height self, a child outside its parent, a view scrolled out of a {@code JViewport} (an
 * area: scrolling is not hiding), a viewport inside a collapsed panel (collapsed), the culprit named, and AWT
 * containers ({@code Panel}, {@code Canvas} construct headless; {@code ScrollPane} does not).
 */
class VisibilityTest {

    private static JPanel panel(int x, int y, int w, int h) {
        JPanel p = new JPanel(null);
        p.setBounds(x, y, w, h);
        return p;
    }

    private static JButton button(int x, int y, int w, int h) {
        JButton b = new JButton("Print slip");
        b.setBounds(x, y, w, h);
        return b;
    }

    @Test
    void aComponentInsideItsParentsBoundsHasItsOwnRectangle() throws Exception {
        assertEquals(new Rectangle(0, 0, 50, 20), onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JButton b = button(10, 10, 50, 20);
            root.add(b);
            assertNull(Visibility.collapsedBy(b));
            return Visibility.area(b);
        }));
    }

    @Test
    void aPartlyClippedComponentKeepsTheVisiblePart() throws Exception {
        assertEquals(new Rectangle(0, 0, 50, 20), onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JButton b = button(250, 0, 100, 20);
            root.add(b);
            return Visibility.area(b);
        }));
    }

    @Test
    void aZeroWidthAncestorCollapsesItsSubtreeAndIsNamed() throws Exception {
        onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JPanel visible = panel(0, 0, 180, 200);
            JPanel hidden = panel(180, 0, 0, 200);
            JButton shown = button(10, 10, 100, 20);
            JButton gone = button(10, 10, 100, 20);
            visible.add(shown);
            hidden.add(gone);
            root.add(visible);
            root.add(hidden);
            assertTrue(Visibility.area(gone).isEmpty());
            assertSame(hidden, Visibility.collapsedBy(gone), "the zero-width pane leaves it no area");
            assertSame(hidden, Visibility.collapsedBy(hidden), "the pane is collapsed by itself");
            assertNull(Visibility.collapsedBy(shown));
            assertFalse(Visibility.onScreen(shown), "headless: nothing is showing");
            return null;
        });
    }

    @Test
    void aZeroHeightComponentIsCollapsedByItself() throws Exception {
        onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JButton flat = button(10, 10, 100, 0);
            root.add(flat);
            assertSame(flat, Visibility.collapsedBy(flat));
            return null;
        });
    }

    @Test
    void aComponentWhollyOutsideItsParentIsCollapsedByTheParent() throws Exception {
        onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JPanel inner = panel(0, 0, 100, 100);
            JButton outside = button(150, 10, 40, 20);
            inner.add(outside);
            root.add(inner);
            assertTrue(Visibility.area(outside).isEmpty());
            assertSame(inner, Visibility.collapsedBy(outside));
            return null;
        });
    }

    @Test
    void aViewScrolledOutOfAViewportKeepsAnArea() throws Exception {
        onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JViewport viewport = new JViewport();
            viewport.setBounds(0, 0, 200, 120);
            JPanel view = panel(0, 0, 200, 400);
            JButton reprint = button(10, 300, 100, 20);
            view.add(reprint);
            viewport.setView(view);
            view.setBounds(0, 0, 200, 400);
            viewport.setViewPosition(new Point(0, 0));
            root.add(viewport);
            assertNull(Visibility.collapsedBy(reprint), "scrolled out of view is not collapsed");
            assertEquals(new Rectangle(0, 0, 100, 20), Visibility.area(reprint), "the viewport does not clip");
            assertFalse(reprint.getVisibleRect().equals(new Rectangle(0, 0, 100, 20)),
                    "the strict rectangle (getVisibleRect) does clip it");
            return null;
        });
    }

    @Test
    void aViewportInsideACollapsedPanelIsCollapsed() throws Exception {
        onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JPanel collapsed = panel(0, 0, 0, 0);
            JViewport viewport = new JViewport();
            viewport.setBounds(0, 0, 200, 120);
            JPanel view = panel(0, 0, 200, 400);
            JButton inside = button(10, 10, 100, 20);
            view.add(inside);
            viewport.setView(view);
            view.setBounds(0, 0, 200, 400);
            collapsed.add(viewport);
            root.add(collapsed);
            assertTrue(Visibility.area(inside).isEmpty());
            assertSame(collapsed, Visibility.collapsedBy(inside), "the culprit is the collapsed panel");
            return null;
        });
    }

    @Test
    void awtContainersFollowTheSameRule() throws Exception {
        onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            Panel heavy = new Panel(null);
            heavy.setBounds(0, 0, 0, 100);
            Canvas canvas = new Canvas();
            canvas.setBounds(0, 0, 50, 50);
            heavy.add(canvas);
            root.add(heavy);
            assertSame(heavy, Visibility.collapsedBy(canvas));
            heavy.setBounds(0, 0, 100, 100);
            assertNull(new Visibility().collapsedByOf(canvas), "a fresh evaluation sees the new bounds");
            return null;
        });
    }

    @Test
    void anInstanceMemoisesAndHandsOutCopies() throws Exception {
        onEdt(() -> {
            JPanel root = panel(0, 0, 300, 200);
            JButton b = button(0, 0, 50, 20);
            root.add(b);
            Visibility v = new Visibility();
            Rectangle first = v.areaOf(b);
            first.width = 0;
            assertEquals(new Rectangle(0, 0, 50, 20), v.areaOf(b), "a caller cannot change the memo");
            b.setBounds(0, 0, 0, 20);
            assertNull(v.collapsedByOf(b), "the answer is memoised for the call");
            assertSame(b, Visibility.collapsedBy(b));
            return null;
        });
    }
}
