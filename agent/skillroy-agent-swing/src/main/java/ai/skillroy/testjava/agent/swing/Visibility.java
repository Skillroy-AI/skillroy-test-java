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

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.ScrollPane;
import java.awt.Window;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.swing.JViewport;

/**
 * The effective visible area (Phase 8, phase8-contracts.md 3.3, D510): a component is <b>on screen</b> when it is
 * showing and its effective area is not empty. The effective area is the component's own rectangle
 * {@code (0, 0, width, height)} intersected, walking up, with the bounds of every ancestor up to and including its
 * window, where a {@link JViewport} and an AWT {@link ScrollPane} do not clip their view: scrolling is not hiding, so a
 * component scrolled out of a viewport keeps its own rectangle as long as the viewport itself has an area. A showing
 * component that is not on screen is <b>collapsed</b>: a zero-width or zero-height ancestor or self, or lying wholly
 * outside an ancestor's bounds or the window's. The rule is hereditary, so walkers prune a collapsed subtree. A window
 * keeps {@code isShowing()} (its own rectangle is its area, and it is never collapsed itself).
 *
 * <p>Geometry only: {@link #areaOf} and {@link #collapsedByOf} do not look at {@code isShowing()} (so that they can be
 * tested without a display); {@link #onScreenOf} does. One instance memoises one call's answers (each component's
 * area is computed from its parent's, so a walk costs O(1) per node); the static methods use a fresh instance
 * (O(depth)). EDT only; not thread-safe.
 */
final class Visibility {

    private static final Rectangle EMPTY = new Rectangle();

    private final Map<Component, Rectangle> areas = new IdentityHashMap<Component, Rectangle>();
    private final Map<Component, Component> culprits = new IdentityHashMap<Component, Component>();

    /** The effective area in the component's own coordinates; empty when it is collapsed. EDT only. */
    static Rectangle area(Component c) {
        return new Visibility().areaOf(c);
    }

    /** The component itself or the ancestor whose bounds leave it no area; null when it has an area. EDT only. */
    static Component collapsedBy(Component c) {
        return new Visibility().collapsedByOf(c);
    }

    /** Showing and not collapsed. EDT only. */
    static boolean onScreen(Component c) {
        return new Visibility().onScreenOf(c);
    }

    /** {@link #area}, memoised for this instance; the answer is a copy. */
    Rectangle areaOf(Component c) {
        return new Rectangle(cached(c));
    }

    /** {@link #collapsedBy}, memoised for this instance. */
    Component collapsedByOf(Component c) {
        if (c == null) {
            return null;
        }
        if (culprits.containsKey(c)) {
            return culprits.get(c);
        }
        Component by;
        Container parent = c.getParent();
        if (!cached(c).isEmpty() || c instanceof Window) {
            by = null;
        } else if (parent != null && collapsedByOf(parent) != null) {
            by = collapsedByOf(parent); // hereditary: the ancestor that emptied the parent empties this one too
        } else if (own(c).isEmpty()) {
            by = c;
        } else {
            by = parent; // the parent's (on-screen) area does not meet this component's rectangle
        }
        culprits.put(c, by);
        return by;
    }

    /** {@link #onScreen}, memoised for this instance. */
    boolean onScreenOf(Component c) {
        return c != null && c.isShowing() && collapsedByOf(c) == null;
    }

    private Rectangle cached(Component c) {
        Rectangle known = areas.get(c);
        if (known != null) {
            return known;
        }
        Container parent = c.getParent();
        Rectangle a = c instanceof Window || parent == null ? own(c) : childArea(cached(parent), parent, c);
        areas.put(c, a);
        return a;
    }

    /**
     * A child's effective area from its parent's (the walkers' step, O(1)): empty when either the child's own
     * rectangle or the parent's area is; the child's own rectangle when the parent scrolls its view (a viewport);
     * else the child's own rectangle intersected with the parent's area moved into the child's coordinates.
     */
    static Rectangle childArea(Rectangle parentArea, Container parent, Component child) {
        Rectangle own = own(child);
        if (own.isEmpty() || parentArea.isEmpty()) {
            return EMPTY;
        }
        if (scrolls(parent)) {
            return own;
        }
        Rectangle moved = new Rectangle(parentArea.x - child.getX(), parentArea.y - child.getY(), parentArea.width,
                parentArea.height);
        Rectangle in = own.intersection(moved);
        return in.isEmpty() ? EMPTY : in;
    }

    /** True for a container that scrolls its view instead of hiding it: a {@code JViewport}, an AWT ScrollPane. */
    static boolean scrolls(Component c) {
        return c instanceof JViewport || c instanceof ScrollPane;
    }

    private static Rectangle own(Component c) {
        int w = c.getWidth();
        int h = c.getHeight();
        return w <= 0 || h <= 0 ? EMPTY : new Rectangle(0, 0, w, h);
    }
}
