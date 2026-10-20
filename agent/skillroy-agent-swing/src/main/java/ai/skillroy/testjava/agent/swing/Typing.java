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
import ai.skillroy.testjava.agent.api.spi.InputVeto;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JRootPane;
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
 * <p>Phase 8 (phase8-contracts.md 3.2, D502): every delivery target, explicit or default (a window's root pane
 * included), is asked the input veto after the clickable test and before the modal test, never skipped by
 * {@code force}; with ENTER ({@code swing.key ENTER} with or without modifiers, {@code swing.type enter}, and a
 * {@code '\n'} in DISPATCH text, which {@link Keys#type} sends as the same ENTER key stroke) the default button of
 * every root pane from the target up to its window too ({@link #defaultButtons}: a {@code JInternalFrame}'s and the
 * window's, since Swing's window-wide bindings of both see the key), since Swing's window-wide binding presses it (the
 * Basic look and feel binds {@code ctrl ENTER} as well as {@code ENTER}). Every posted task (the SET task, the
 * prepare task, each character's task, the ENTER task, the key's task) asks again immediately before it acts
 * ({@link InputVetoes.Withheld}); a {@code '\n'} character's task asks the default buttons too (fix wave of the Phase 8
 * review, FA, L1-F1, L2-F1, L2-S1).
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
    /** The module's count of calls whose posted task a veto withheld ({@code vetoWithheld}, D503). */
    private final AtomicLong withheld;

    Typing(AgentContext ctx, Windows windows) {
        this(ctx, windows, new AtomicLong());
    }

    Typing(AgentContext ctx, Windows windows, AtomicLong withheld) {
        this.ctx = ctx;
        this.windows = windows;
        this.withheld = withheld;
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
        final String route = SET.equals(mode) ? InputVeto.TYPE_SET : InputVeto.TYPE_DISPATCH;
        // fix wave of the Phase 8 review (FA, L1-F1, L2-F1): a '\n' in DISPATCH text is the ENTER key stroke
        // (Keys.type sends KEY_PRESSED VK_ENTER for it), so it asks the default buttons as enter does
        final boolean asksDefault = enter || (DISPATCH.equals(mode) && text.indexOf('\n') >= 0);
        final InputVetoes.Withheld task = new InputVetoes.Withheld(ctx, withheld);
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
                requireKeyTargetsNotVetoed(view, found, route, asksDefault);
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
                    if (!task.allows(route, keyTargets(tc, enter))) {
                        return;
                    }
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
                    if (!task.allows(route, c)) {
                        return;
                    }
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
                        if (task.allows(route, keyTargets(c, ch == '\n'))) {
                            Keys.type(c, ch);
                        }
                    }
                });
            }
            if (enter) {
                Edt.post(ctx, inv.remainingMs(), "swing.type DISPATCH enter", new Edt.Action() {
                    @Override
                    public void run() {
                        if (task.allows(route, keyTargets(c, true))) {
                            Keys.press(c, KeyEvent.VK_ENTER, 0);
                        }
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
        // As built (Phase 8, EA): ENTER with any modifier, since the Basic look and feel binds "ctrl ENTER" to the
        // default button as well as "ENTER" (RootPane.defaultButtonWindowKeyBindings); the safe side
        final boolean enter = code == KeyEvent.VK_ENTER;
        final InputVetoes.Withheld task = new InputVetoes.Withheld(ctx, withheld);
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
                requireKeyTargetsNotVetoed(view, found, InputVeto.KEY, enter);
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
                if (task.allows(InputVeto.KEY, keyTargets(c, enter))) {
                    Keys.press(c, code, modifiers);
                }
            }
        });
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dispatchedTo", ref[0]);
        return m;
    }

    /**
     * The input veto for a key's delivery target and, with ENTER, the default buttons of the root panes from the
     * target up to its window (D502; {@link #defaultButtons}): Swing's {@code WHEN_IN_FOCUSED_WINDOW} bindings press
     * such a button whatever the target. EDT only.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException INPUT_REJECTED {@code vetoed}; for a default button with
     *     {@code data.defaultButton} the ref of the button whose veto refused
     */
    static void requireKeyTargetsNotVetoed(ComponentView view, Component target, String route, boolean enter) {
        Clicks.requireNotVetoed(view, target, route);
        if (!enter) {
            return;
        }
        for (Component button : defaultButtons(target)) {
            InputVetoes.Refusal r = view.vetoes().check(button, route);
            if (r != null) {
                throw Errors.vetoedDefaultButton(view, target, button, r);
            }
        }
    }

    /**
     * The default buttons an ENTER delivered to the component can press, nearest first, each once (fix wave of the
     * Phase 8 review, FA, L2-S1): the default button of every {@code JRootPane} on the walk from the component (a
     * {@code RootPaneContainer} window's own root pane included) up to its window, by {@link InputVetoes#next} (so a
     * heavyweight popup's owner too, as Swing passes it the popup's window-wide bindings), at most
     * {@link InputVetoes#MAX_DEPTH} steps. A {@code JInternalFrame}'s root pane registers its ENTER under the internal
     * frame and only while it has a default button, and Swing then offers the key to the window's bindings as well, so
     * the enclosing window's default button is asked although a nearer root pane exists (the safe side: Swing presses
     * the nearest one that holds a button). EDT only.
     */
    static List<Component> defaultButtons(Component c) {
        List<Component> out = new ArrayList<Component>(2);
        int depth = 0;
        for (Component p = c; p != null && depth < InputVetoes.MAX_DEPTH; p = InputVetoes.next(p), depth++) {
            JRootPane root = p instanceof JRootPane ? (JRootPane) p
                    : p instanceof RootPaneContainer ? ((RootPaneContainer) p).getRootPane() : null;
            Component button = root == null ? null : root.getDefaultButton();
            if (button != null && !out.contains(button)) {
                out.add(button);
            }
        }
        return out;
    }

    /** The components a key task asks the veto about: the target, and with ENTER its {@link #defaultButtons}. */
    static Component[] keyTargets(Component c, boolean enter) {
        if (!enter) {
            return new Component[] {c};
        }
        List<Component> buttons = defaultButtons(c);
        Component[] out = new Component[buttons.size() + 1];
        out[0] = c;
        for (int i = 0; i < buttons.size(); i++) {
            out[i + 1] = buttons.get(i);
        }
        return out;
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
