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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.DialogLocator;

import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;

/**
 * Dialogs an application draws inside another window, as the registered {@link DialogLocator}s report them (amendment
 * A27). A reported root counts only if it is a showing component, not a window itself, and lies inside the window it
 * was reported for; a root reported twice counts once. A locator that throws is logged and skipped, and one whose
 * {@code blocksInput} throws is taken to block (the default), so a broken locator can make input fail with
 * {@code MODAL_OPEN}, which {@code force} overrides, but never fail a read.
 *
 * <p>Phase 8 (phase8-contracts.md 3.3, D511): a reported root without an effective visible area (collapsed) is not a
 * dialog: it blocks nothing and is not listed. The first such root of each class is logged ("swing: in-window dialog
 * root <class> has no visible area and blocks nothing"), so the loss is visible in the agent log.
 *
 * <p>Must be used on the EDT. One instance per primitive call: the locators are looked up when it is made, so modules
 * that register later are seen by the next call.
 */
final class InWindowDialogs {

    /** Most root classes whose collapsed root is logged (once each). */
    static final int MAX_LOGGED_CLASSES = 64;
    private static final Set<String> LOGGED_COLLAPSED = new LinkedHashSet<String>();

    private final AgentContext ctx;
    private final List<DialogLocator> locators;

    InWindowDialogs(AgentContext ctx) {
        this.ctx = ctx;
        this.locators = ctx.spi().all(DialogLocator.class);
    }

    /** One in-window dialog: its root, the window that contains it and the locator that reported it. */
    static final class Found {
        final Component root;
        final Window window;
        final DialogLocator locator;

        Found(Component root, Window window, DialogLocator locator) {
            this.root = root;
            this.window = window;
            this.locator = locator;
        }
    }

    /** The in-window dialogs of one window: each locator's roots in its order (outermost first), locators in turn. */
    List<Found> in(Window window) {
        if (locators.isEmpty() || window == null || !window.isShowing()) {
            return Collections.emptyList();
        }
        List<Found> out = new ArrayList<Found>();
        Set<Component> seen = Collections.newSetFromMap(new IdentityHashMap<Component, Boolean>());
        for (DialogLocator locator : locators) {
            List<Component> roots;
            try {
                roots = locator.dialogRoots(window);
            } catch (RuntimeException | LinkageError e) {
                ctx.log().warn("swing: DialogLocator " + locator.getClass().getName() + " failed: " + e, e);
                continue;
            }
            if (roots == null) {
                continue;
            }
            for (Component root : new ArrayList<Component>(roots)) {
                if (root != null && !(root instanceof Window) && root.isShowing()
                        && Windows.windowOf(root) == window && seen.add(root)) {
                    if (Visibility.collapsedBy(root) != null) {
                        collapsed(root);
                        continue;
                    }
                    out.add(new Found(root, window, locator));
                }
            }
        }
        return out;
    }

    /** Logs a collapsed root, once per root class (D511). */
    private void collapsed(Component root) {
        String name = root.getClass().getName();
        boolean first;
        synchronized (LOGGED_COLLAPSED) {
            first = LOGGED_COLLAPSED.size() < MAX_LOGGED_CLASSES && LOGGED_COLLAPSED.add(name);
        }
        if (first) {
            try {
                ctx.log().warn("swing: in-window dialog root " + name + " has no visible area and blocks nothing");
            } catch (Throwable ignored) {
                // the log must never be the reason a read fails
            }
        }
    }

    /** The in-window dialogs of every showing window, windows in creation order. */
    List<Found> all() {
        if (locators.isEmpty()) {
            return Collections.emptyList();
        }
        List<Found> out = new ArrayList<Found>();
        for (Window w : Windows.roots(false)) {
            out.addAll(in(w));
        }
        return out;
    }

    /** The in-window dialog whose root is {@code component}, or null if no locator reports it. */
    Found find(Component component) {
        if (locators.isEmpty() || component == null || component instanceof Window) {
            return null;
        }
        for (Found f : in(Windows.windowOf(component))) {
            if (f.root == component) {
                return f;
            }
        }
        return null;
    }

    /** {@link DialogLocator#blocksInput}; true (the default) if the locator throws. */
    boolean blocksInput(Found f) {
        try {
            return f.locator.blocksInput(f.root);
        } catch (RuntimeException | LinkageError e) {
            ctx.log().warn("swing: DialogLocator " + f.locator.getClass().getName() + ".blocksInput failed: " + e,
                    e);
            return true;
        }
    }

    /**
     * The in-window dialog that blocks input to {@code target}: a blocking dialog of the target's window that does not
     * contain the target. Of several, the last one reported (the innermost, which is drawn on top). Null if none.
     */
    Found blocking(Component target) {
        if (locators.isEmpty() || target == null) {
            return null;
        }
        Found blocker = null;
        for (Found f : in(Windows.windowOf(target))) {
            if (target != f.root && !SwingUtilities.isDescendingFrom(target, f.root) && blocksInput(f)) {
                blocker = f;
            }
        }
        return blocker;
    }
}
