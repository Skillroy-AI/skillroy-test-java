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
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

/**
 * Window enumeration, the choice of a default window, and the modal-blocking heuristic of {@code MODAL_OPEN} (dialog
 * windows here, dialogs drawn inside a window through {@link InWindowDialogs}).
 * Enumerates {@code Window.getWindows()} (displayable ones only) and remembers, weakly, the order in which the AWT tap
 * last reported each window opened (first shown or shown again), which is how the topmost of several modal dialogs is
 * chosen (AWT has no public z-order).
 *
 * <p>Thread-safe. The open order is guarded by its own lock; the other methods read AWT state and are called on the
 * EDT.
 */
final class Windows {

    /** Open order per window, weakly held so that it never keeps a closed window alive. */
    private final Map<Window, Long> openOrder = new WeakHashMap<Window, Long>();
    private long openCounter;
    /** Where the stacking of the showing windows comes from: the real windows, or a test's stand-ins. */
    private volatile Source source = new Source() {
        @Override
        public List<Layer> stacking() {
            return realStacking();
        }
    };

    /**
     * One showing window as the SCREEN composite and the hit-test see it (phase7-contracts.md section 3.1): the
     * window, the component painted for it (its root pane, or the window itself without one), its screen bounds and
     * where the painted component's (0, 0) lies in the window's space (the insets).
     */
    static final class Layer {
        /** The window (a {@code java.awt.Window} in production; a stand-in component in headless tests). */
        final Component window;
        /** The component painted for the window. */
        final Component paint;
        /** The window's screen bounds. */
        final Rectangle screen;
        /** The painted component's origin in the window's space. */
        final Point content;
        /** The layer may be placed above the windows before it with confidence (modal, owned or active). */
        final boolean ranked;

        Layer(Component window, Component paint, Rectangle screen, Point content, boolean ranked) {
            this.window = window;
            this.paint = paint;
            this.screen = new Rectangle(screen);
            this.content = new Point(content);
            this.ranked = ranked;
        }
    }

    /**
     * The package-private window-list seam (phase7-contracts.md section 13.1 item 1): the showing windows bottom to
     * top. Production reads {@code Window.getWindows()}; headless tests, which cannot create a window, hand in
     * lightweight stand-ins. EDT only.
     */
    interface Source {
        List<Layer> stacking();
    }

    /** Replaces the window list (tests only). */
    void source(Source s) {
        this.source = s;
    }

    /** The showing windows bottom to top, from the {@link Source}. EDT only. */
    List<Layer> stacking() {
        return source.stacking();
    }

    /**
     * The module's stacking order of the showing windows, bottom to top (phase7-contracts.md section 3.1): windows
     * neither active nor owned nor modal in creation order, then the active window, then owned windows above their
     * owners, then the modal dialogs as {@link #topmostModal()} ranks them, last on top. AWT has no public z-order, so
     * this is assumed ({@code stackingAssumed}). EDT only.
     */
    List<Window> stackingOrder() {
        List<Window> showing = roots(false);
        Window active = activeWindow();
        List<Window> base = new ArrayList<Window>();
        List<Window> owned = new ArrayList<Window>();
        List<Dialog> modal = new ArrayList<Dialog>();
        Window activeBase = null;
        for (Window w : showing) {
            if (modal(w)) {
                modal.add((Dialog) w);
            } else if (w.getOwner() != null && w.getOwner().isShowing()) {
                owned.add(w);
            } else if (w == active) {
                activeBase = w;
            } else {
                base.add(w);
            }
        }
        List<Window> out = new ArrayList<Window>(base);
        if (activeBase != null) {
            out.add(activeBase);
        }
        final Map<Window, Integer> depth = new IdentityHashMap<Window, Integer>();
        for (Window w : owned) {
            int d = 0;
            for (Window o = w.getOwner(); o != null; o = o.getOwner()) {
                d++;
            }
            depth.put(w, Integer.valueOf(d));
        }
        List<Window> ownedSorted = new ArrayList<Window>(owned);
        Collections.sort(ownedSorted, new Comparator<Window>() {
            @Override
            public int compare(Window a, Window b) {
                return depth.get(a).intValue() - depth.get(b).intValue();
            }
        });
        out.addAll(ownedSorted);
        List<Dialog> rest = new ArrayList<Dialog>(modal);
        List<Dialog> top = new ArrayList<Dialog>();
        while (!rest.isEmpty()) {
            Dialog t = topmostOf(rest);
            top.add(0, t);
            rest.remove(t);
        }
        out.addAll(top);
        return out;
    }

    private List<Layer> realStacking() {
        List<Layer> out = new ArrayList<Layer>();
        Window active = activeWindow();
        for (Window w : stackingOrder()) {
            Component paint = paintRoot(w);
            Point content = paint == w ? new Point(0, 0) : SwingUtilities.convertPoint(paint, 0, 0, w);
            Rectangle screen;
            try {
                screen = new Rectangle(w.getLocationOnScreen(), w.getSize());
            } catch (RuntimeException e) {
                continue; // stopped showing in between
            }
            boolean ranked = modal(w) || w == active || (w.getOwner() != null && w.getOwner().isShowing());
            out.add(new Layer(w, paint, screen, content, ranked));
        }
        return out;
    }

    /**
     * The component a capture paints for a window (A61, D288): the root pane of a {@code RootPaneContainer} window
     * (every {@code JFrame}, {@code JDialog}, {@code JWindow}), else the window itself.
     */
    static Component paintRoot(Window w) {
        if (w instanceof RootPaneContainer) {
            JRootPane root = ((RootPaneContainer) w).getRootPane();
            if (root != null && root.getParent() == w) {
                return root;
            }
        }
        return w;
    }

    /**
     * Records that the tap reported the window opened (first shown, or shown again after it was hidden or disposed):
     * it becomes the most recently opened window.
     *
     * @return true if the window was never reported opened before, false if it is shown again
     */
    boolean recordOpen(Window w) {
        synchronized (openOrder) {
            boolean first = !openOrder.containsKey(w);
            openOrder.put(w, Long.valueOf(++openCounter));
            return first;
        }
    }

    private long openSeq(Window w) {
        synchronized (openOrder) {
            Long seq = openOrder.get(w);
            return seq == null ? -1L : seq.longValue();
        }
    }

    /** Displayable windows in creation order. */
    static List<Window> displayable() {
        List<Window> out = new ArrayList<Window>();
        for (Window w : Window.getWindows()) {
            if (w.isDisplayable()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Snapshot and locator roots: showing windows, or every displayable window when {@code includeHidden}. */
    static List<Window> roots(boolean includeHidden) {
        List<Window> out = new ArrayList<Window>();
        for (Window w : displayable()) {
            if (includeHidden || w.isShowing()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Showing dialogs in creation order. */
    static List<Dialog> showingDialogs() {
        List<Dialog> out = new ArrayList<Dialog>();
        for (Window w : displayable()) {
            if (w instanceof Dialog && w.isShowing()) {
                out.add((Dialog) w);
            }
        }
        return out;
    }

    /** Showing modal dialogs (modality type other than {@code MODELESS}) in creation order. */
    static List<Dialog> showingModalDialogs() {
        List<Dialog> out = new ArrayList<Dialog>();
        for (Dialog d : showingDialogs()) {
            if (d.isModal()) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * The modal dialog a user would be looking at, or null if none is showing: among showing modal dialogs, drop any
     * that owns another one; then the active window if it is one of them; else the one the tap reported opened last
     * (dialogs it never reported rank by creation order, below those it did).
     */
    Window topmostModal() {
        return topmostOf(showingModalDialogs());
    }

    /**
     * The showing modal dialog that blocks input to a window, or null if none does. This is a heuristic, not AWT's own
     * rule: a window is taken as blocked when a showing dialog whose modality type is not {@code MODELESS} exists and
     * the window is neither that dialog nor owned by it, directly or transitively. Document modality (which blocks only
     * the dialog's own document) and modal exclusion ({@code Window.setModalExclusionType}) are not modelled, so a
     * window that AWT would let through can be reported as blocked; callers that know better pass {@code force}.
     * When several dialogs block the window, the topmost of them (as {@link #topmostModal()} ranks them) is returned.
     * EDT only.
     *
     * @param w the window that would receive the input; null gives null
     */
    Dialog blockingModal(Window w) {
        if (w == null) {
            return null;
        }
        List<Dialog> blockers = new ArrayList<Dialog>();
        for (Dialog d : showingModalDialogs()) {
            if (d != w && !ownedBy(w, d)) {
                blockers.add(d);
            }
        }
        return blockers.isEmpty() ? null : topmostOf(blockers);
    }

    /**
     * {@code MODAL_OPEN} unless the component may receive input: its window must not be blocked by a modal dialog
     * window (see {@link #blockingModal(Window)}), and the component must not lie outside a blocking dialog that the
     * application draws inside the same window (amendment A27, see {@link InWindowDialogs#blocking}). EDT only.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException MODAL_OPEN with {@code data.dialog} (the blocking dialog's
     *     ref), {@code data.title} and {@code data.ref} (the target's ref), and {@code data.inWindow: true} when the
     *     blocker is an in-window dialog
     */
    void requireNotBlocked(ComponentView view, Component target) {
        Dialog blocker = blockingModal(windowOf(target));
        if (blocker != null) {
            throw Errors.modalOpen(view.describe(target) + " is in a window blocked by the modal dialog '"
                    + blocker.getTitle() + "' (" + view.ref(blocker) + "); answer the dialog first, or pass force:true"
                    + " to act anyway", view.ref(blocker), blocker.getTitle(), view.ref(target));
        }
        InWindowDialogs.Found inWindow = new InWindowDialogs(view.context()).blocking(target);
        if (inWindow != null) {
            String title = new DialogReader(view).inWindowTitle(inWindow.root);
            String dialogRef = view.ref(inWindow.root);
            throw Errors.modalOpenInWindow(view.describe(target) + " lies outside the dialog "
                    + (title == null ? "" : "'" + title + "' ") + "(" + dialogRef + ") that blocks input to "
                    + view.describe(inWindow.window) + "; answer the dialog first, or pass force:true to act anyway",
                    dialogRef, title, view.ref(target));
        }
    }

    /** True if {@code owner} owns {@code w}, directly or through other windows. */
    static boolean ownedBy(Window w, Window owner) {
        for (Window o = w.getOwner(); o != null; o = o.getOwner()) {
            if (o == owner) {
                return true;
            }
        }
        return false;
    }

    private Dialog topmostOf(List<Dialog> modal) {
        if (modal.isEmpty()) {
            return null;
        }
        List<Dialog> candidates = new ArrayList<Dialog>();
        for (Dialog d : modal) {
            if (!ownsAnyOf(d, modal)) {
                candidates.add(d);
            }
        }
        if (candidates.isEmpty()) {
            candidates = modal;
        }
        Window active = activeWindow();
        if (active != null && candidates.contains(active)) {
            return (Dialog) active;
        }
        Map<Window, Integer> creation = new IdentityHashMap<Window, Integer>();
        Window[] all = Window.getWindows();
        for (int i = 0; i < all.length; i++) {
            creation.put(all[i], Integer.valueOf(i));
        }
        Dialog best = null;
        long bestSeq = Long.MIN_VALUE;
        int bestIndex = -1;
        for (Dialog d : candidates) {
            long seq = openSeq(d);
            Integer idx = creation.get(d);
            int index = idx == null ? -1 : idx.intValue();
            if (best == null || seq > bestSeq || (seq == bestSeq && index > bestIndex)) {
                best = d;
                bestSeq = seq;
                bestIndex = index;
            }
        }
        return best;
    }

    private static boolean ownsAnyOf(Window w, List<Dialog> others) {
        for (Dialog other : others) {
            if (other != w && ownedBy(other, w)) {
                return true;
            }
        }
        return false;
    }

    /** The focus manager's active window if it is showing, else null. */
    static Window activeWindow() {
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        return active != null && active.isShowing() ? active : null;
    }

    /**
     * The window a primitive without an explicit target acts on: the active window, else the topmost showing modal
     * dialog, else the first showing window; null if no window is showing. On a display without a window manager
     * there is often no active window, which is why the fallbacks exist.
     */
    Window defaultWindow() {
        Window active = activeWindow();
        if (active != null) {
            return active;
        }
        Window modal = topmostModal();
        if (modal != null) {
            return modal;
        }
        List<Window> showing = roots(false);
        return showing.isEmpty() ? null : showing.get(0);
    }

    /** {@code frame}, {@code dialog} or {@code window}. */
    static String type(Window w) {
        if (w instanceof Frame) {
            return "frame";
        }
        if (w instanceof Dialog) {
            return "dialog";
        }
        return "window";
    }

    /** A frame's or dialog's title; null for other windows. */
    static String title(Window w) {
        if (w instanceof Frame) {
            return ((Frame) w).getTitle();
        }
        if (w instanceof Dialog) {
            return ((Dialog) w).getTitle();
        }
        return null;
    }

    /** True for a dialog whose modality type is not {@code MODELESS}. */
    static boolean modal(Window w) {
        return w instanceof Dialog && ((Dialog) w).isModal();
    }

    /** The window containing the component (the component itself if it is a window), or null. */
    static Window windowOf(Component c) {
        for (Component p = c; p != null; p = p.getParent()) {
            if (p instanceof Window) {
                return (Window) p;
            }
        }
        return null;
    }
}
