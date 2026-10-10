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

package ai.skillroy.testjava.spikes.bs10;

import java.awt.Component;
import java.awt.Container;
import javax.swing.SwingUtilities;

/**
 * A hit-test with public API only: {@link #deepest} is {@code SwingUtilities.getDeepestComponentAt}; {@link
 * #mouseTarget} re-implements AWT's package-private {@code Container.getMouseEventTargetImpl} with its
 * {@code MouseEventTargetFilter} read through the listener arrays (the enabled-event-mask half of the filter is not
 * readable from outside, research vision-input F7), searching lightweight children only, as the window's
 * {@code LightweightDispatcher} does. EDT only.
 */
final class HitTest {

    private HitTest() {
    }

    static Component deepest(Container window, int x, int y) {
        return SwingUtilities.getDeepestComponentAt(window, x, y);
    }

    /** What the window's lightweight dispatcher would deliver a mouse event at {@code (x, y)} to (the window itself when no lightweight listens). */
    static Component mouseTarget(Container window, int x, int y) {
        Component c = target(window, x, y, true);
        return c;
    }

    private static Component target(Container parent, int x, int y, boolean includeSelf) {
        synchronized (parent.getTreeLock()) {
            int n = parent.getComponentCount();
            for (int i = 0; i < n; i++) {
                Component comp = parent.getComponent(i);
                if (comp != null && comp.isVisible() && comp.isLightweight()
                        && comp.contains(x - comp.getX(), y - comp.getY())) {
                    if (comp instanceof Container) {
                        Component deeper = target((Container) comp, x - comp.getX(), y - comp.getY(), includeSelf);
                        if (deeper != null) {
                            return deeper;
                        }
                    } else if (listens(comp)) {
                        return comp;
                    }
                }
            }
            boolean peerOk = parent.isLightweight() || includeSelf;
            if (parent.contains(x, y) && peerOk && listens(parent)) {
                return parent;
            }
            return null;
        }
    }

    static boolean listens(Component c) {
        return c.getMouseListeners().length > 0 || c.getMouseMotionListeners().length > 0
                || c.getMouseWheelListeners().length > 0;
    }
}
