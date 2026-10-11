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

package ai.skillroy.testjava.agent.web;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.GraphicsConfiguration;
import java.awt.Window;

/**
 * The EDT reads of one browser component for the input primitives ({@link SwingSeeds#state}, phase7-contracts.md
 * section 4.3): a class of its own, loaded only when such a read runs, so that loading {@link SwingSeeds} verifies no
 * code that names the AWT class hierarchy (the module requests no AWT class at install).
 *
 * <p>Stateless; EDT only.
 */
final class ComponentReads {

    private ComponentReads() {
    }

    /** {@link SwingSeeds#state} on the EDT. */
    static ComponentState stateOf(Object component) {
        Component c = (Component) component;
        double scale = 1.0;
        GraphicsConfiguration gc = c.getGraphicsConfiguration();
        if (gc != null) {
            double s = gc.getDefaultTransform().getScaleX();
            if (s > 0 && !Double.isInfinite(s)) {
                scale = s;
            }
        }
        Dialog blocker = blockingModal(windowOf(c));
        return new ComponentState(c.isShowing(), c.getWidth(), c.getHeight(), scale, blocker,
                blocker == null ? null : blocker.getTitle());
    }

    /** The window of a component (itself when it is one), or null. */
    static Window windowOf(Component c) {
        for (Component p = c; p != null; p = p.getParent()) {
            if (p instanceof Window) {
                return (Window) p;
            }
        }
        return null;
    }

    /**
     * The showing modal dialog that blocks input to a window, or null (phase7-contracts.md section 4.3, the Swing
     * module's heuristic re-stated here because the two modules do not depend on each other): a showing dialog whose
     * modality type is not {@code MODELESS}, that is not the window itself and that does not own it, directly or
     * through other windows. When several block it, the active window among them if there is one, else the last in
     * creation order. A window that is null (a component in no window) is not blocked. EDT only.
     */
    static Dialog blockingModal(Window w) {
        if (w == null) {
            return null;
        }
        Dialog last = null;
        for (Window x : Window.getWindows()) {
            if (!(x instanceof Dialog) || x == w || !x.isShowing() || !((Dialog) x).isModal() || ownedBy(w, x)) {
                continue;
            }
            if (x.isActive()) {
                return (Dialog) x;
            }
            last = (Dialog) x;
        }
        return last;
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

}
