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
import ai.skillroy.testjava.agent.api.Invocation;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.swing.JTextField;
import javax.swing.RootPaneContainer;
import javax.swing.text.JTextComponent;

/**
 * {@code swing.type} and {@code swing.key}. SET mode sets the text model directly; DISPATCH mode and keys deliver
 * non-posted key events to the target with {@code dispatchEvent}, so they reach a showing component whether or not its
 * window has the focus. All changes are posted to the EDT and the call returns once they are queued. Unless
 * {@code force}, an explicit target whose window a modal dialog blocks, or that lies outside a blocking dialog drawn
 * inside its window, gets {@code MODAL_OPEN} (see {@link Windows#requireNotBlocked}); a key without a target goes
 * where a real key press would and needs no such check.
 *
 * <p>Thread-safe. The public-to-package methods run on an agent thread and hop to the EDT themselves.
 */
final class Typing {

    static final String SET = "SET";
    static final String DISPATCH = "DISPATCH";
    /** Longest text DISPATCH mode takes: it queues one EDT task per character in the application's event queue. */
    static final int MAX_DISPATCH_CHARS = 10000;

    private final AgentContext ctx;
    private final Windows windows;

    Typing(AgentContext ctx, Windows windows) {
        this.ctx = ctx;
        this.windows = windows;
    }

    /**
     * {@code swing.type}: returns {@code {mode, typed}}. Without a target (A62, phase7-contracts.md section 3.5) the
     * text goes where {@code swing.key}'s does ({@link #defaultKeyTarget()}; a window becomes its root pane), and the
     * modal check runs for an explicit target only. Gap 60: a dispatched {@code ENTER} submits the text field that
     * most recently had the focus, so {@code enter} with DISPATCH and an omitted target may submit another field or
     * none; SET is unaffected.
     */
    Map<String, Object> type(Invocation inv, final TargetSpec target, final String mode, final String text,
                             final boolean clear, final boolean enter, final boolean force) throws Exception {
        if (DISPATCH.equals(mode) && text.length() > MAX_DISPATCH_CHARS) {
            throw Errors.invalid("mode DISPATCH takes at most " + MAX_DISPATCH_CHARS + " characters, not "
                    + text.length() + " (use mode SET for long texts)", Errors.data("param", "text", "reason",
                    "too long"));
        }
        final Component c = Edt.read(ctx, inv.remainingMs(), new Callable<Component>() {
            @Override
            public Component call() {
                ComponentView view = new ComponentView(ctx);
                Component found;
                if (target != null) {
                    found = target.resolve(view);
                } else {
                    found = defaultKeyTarget();
                    if (found == null) {
                        throw Errors.notFound("no target to type into: there is no focus owner and no showing "
                                + "window", Errors.data());
                    }
                    if (found instanceof Window && found instanceof RootPaneContainer
                            && ((RootPaneContainer) found).getRootPane() != null) {
                        found = ((RootPaneContainer) found).getRootPane();
                    }
                }
                Clicks.requireClickable(view, found);
                if (target != null && !force) {
                    windows.requireNotBlocked(view, found);
                }
                if (SET.equals(mode) && !(found instanceof JTextComponent)) {
                    throw Errors.invalid("mode SET needs a text component; " + view.describe(found)
                            + " is not one (use mode DISPATCH)", Errors.data("ref", view.ref(found), "reason",
                            "not a text component"));
                }
                if (found instanceof JTextComponent && !((JTextComponent) found).isEditable()) {
                    throw Errors.invalid(view.describe(found) + " is not editable", Errors.data("ref",
                            view.ref(found), "reason", "not editable"));
                }
                return found;
            }
        });
        if (SET.equals(mode)) {
            final JTextComponent tc = (JTextComponent) c;
            Edt.post(ctx, inv.remainingMs(), "swing.type SET", new Edt.Action() {
                @Override
                public void run() {
                    tc.setText(clear ? text : tc.getText() + text);
                    if (enter) {
                        if (tc instanceof JTextField) {
                            ((JTextField) tc).postActionEvent();
                        } else {
                            Keys.press(tc, KeyEvent.VK_ENTER, 0);
                        }
                    }
                }
            });
        } else {
            Edt.post(ctx, inv.remainingMs(), "swing.type DISPATCH prepare", new Edt.Action() {
                @Override
                public void run() {
                    if (c instanceof JTextComponent) {
                        JTextComponent tc = (JTextComponent) c;
                        if (clear) {
                            tc.setText("");
                        } else {
                            tc.setCaretPosition(tc.getDocument().getLength());
                        }
                    }
                }
            });
            // one EDT task per character, as real typing produces one event batch per key stroke
            for (int i = 0; i < text.length(); i++) {
                final char ch = text.charAt(i);
                Edt.post(ctx, inv.remainingMs(), "swing.type DISPATCH", new Edt.Action() {
                    @Override
                    public void run() {
                        Keys.type(c, ch);
                    }
                });
            }
            if (enter) {
                Edt.post(ctx, inv.remainingMs(), "swing.type DISPATCH enter", new Edt.Action() {
                    @Override
                    public void run() {
                        Keys.press(c, KeyEvent.VK_ENTER, 0);
                    }
                });
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mode", mode);
        m.put("typed", Long.valueOf(text.length()));
        return m;
    }

    /**
     * {@code swing.key}: returns {@code {dispatchedTo}}. Without a target the key goes where a real key press would
     * (see {@link #defaultKeyTarget()}). A window target is replaced by its root pane, where Swing's window-wide key
     * bindings are processed. An explicit target in a window a modal dialog blocks is refused unless {@code force}.
     */
    Map<String, Object> key(Invocation inv, final TargetSpec target, final int code, final int modifiers,
                            final boolean force) throws Exception {
        final String[] ref = new String[1];
        final Component c = Edt.read(ctx, inv.remainingMs(), new Callable<Component>() {
            @Override
            public Component call() {
                ComponentView view = new ComponentView(ctx);
                Component found;
                if (target != null) {
                    found = target.resolve(view);
                } else {
                    found = defaultKeyTarget();
                    if (found == null) {
                        throw Errors.notFound("no key target: there is no focus owner and no showing window",
                                Errors.data());
                    }
                }
                if (found instanceof Window && found instanceof RootPaneContainer
                        && ((RootPaneContainer) found).getRootPane() != null) {
                    found = ((RootPaneContainer) found).getRootPane();
                }
                Clicks.requireClickable(view, found);
                if (target != null && !force) {
                    windows.requireNotBlocked(view, found);
                }
                if (!found.isFocusable()) {
                    throw Errors.invalid(view.describe(found) + " is not focusable; the focus manager drops key "
                            + "events sent to it", Errors.data("ref", view.ref(found), "reason", "not focusable"));
                }
                ref[0] = view.ref(found);
                return found;
            }
        });
        Edt.post(ctx, inv.remainingMs(), "swing.key " + code, new Edt.Action() {
            @Override
            public void run() {
                Keys.press(c, code, modifiers);
            }
        });
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dispatchedTo", ref[0]);
        return m;
    }

    /**
     * Where a key without a target goes: the focus owner, else the active window, else the topmost modal dialog, else
     * the first showing window. One correction: while a modal dialog is showing, a focus owner (or active window) in a
     * window that owns the dialog is stale, because the dialog blocks that window and a real key press could not reach
     * it. This is the state right after a dialog opens, before the focus has moved. The key then goes into the dialog:
     * to the component that gets the focus there, else to the dialog itself. A dialog that cannot take the focus is
     * left alone: its key bindings are served through its owner. EDT only.
     *
     * @return null if there is no focus owner and no showing window
     */
    Component defaultKeyTarget() {
        Component target = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (target == null || !target.isShowing()) {
            target = windows.defaultWindow();
        }
        Window modal = windows.topmostModal();
        if (target != null && modal != null && modal.isFocusableWindow()
                && blocks(modal, Windows.windowOf(target))) {
            Component inside = modal.getMostRecentFocusOwner();
            return inside != null && inside.isShowing() && inside.isEnabled() && inside.isFocusable() ? inside : modal;
        }
        return target;
    }

    /** True if the showing modal dialog blocks the window for certain: the window owns it and is not excluded. */
    private static boolean blocks(Window modal, Window w) {
        if (w == null || w == modal || w.getModalExclusionType() != Dialog.ModalExclusionType.NO_EXCLUDE) {
            return false;
        }
        for (Window owner = modal.getOwner(); owner != null; owner = owner.getOwner()) {
            if (owner == w) {
                return true;
            }
        }
        return false;
    }
}
