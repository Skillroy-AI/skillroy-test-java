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
import ai.skillroy.testjava.agent.api.spi.DialogInfo;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JOptionPane;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;

/**
 * Reads dialogs for {@code swing.dialogs} and finds the button {@code swing.dismiss} should press. Registered
 * {@link DialogTextExtractor}s are asked first, in registration order; otherwise the generic extraction applies: a
 * {@code JOptionPane}'s message, else every showing label and text component in reading order, with the showing
 * buttons that have text. A dialog window is passed to {@code extract(Window)}; the root of a dialog drawn inside a
 * window (amendment A27, see {@link InWindowDialogs}) to {@code extract(Component)}, and its generic title is an
 * internal frame's title or a titled border's title.
 *
 * <p>Must be used on the EDT. One instance per primitive call.
 */
final class DialogReader {

    /** Extractor id reported when no registered extractor claimed the dialog. */
    static final String GENERIC = "generic";

    private final AgentContext ctx;
    private final ComponentView view;
    private final List<DialogTextExtractor> extractors;

    DialogReader(ComponentView view) {
        this.ctx = view.context();
        this.view = view;
        this.extractors = ctx.spi().all(DialogTextExtractor.class);
    }

    /** {@code {ref, title, modal, text, buttons:[{ref?, text}], extractor}} of a dialog window. */
    Map<String, Object> describe(Window d) {
        return describe(d, extract(d), Windows.title(d), Windows.modal(d));
    }

    /**
     * {@code {ref, title, modal, text, buttons:[{ref?, text}], extractor, inWindow:true, window}} of a dialog drawn
     * inside a window; {@code modal} is what its locator's {@code blocksInput} says.
     */
    Map<String, Object> describeInWindow(InWindowDialogs.Found f, boolean modal) {
        Map<String, Object> m = describe(f.root, extract(f.root), genericTitle(f.root), modal);
        m.put("inWindow", Boolean.TRUE);
        m.put("window", view.ref(f.window));
        return m;
    }

    private Map<String, Object> describe(Component root, DialogInfo info, String ownTitle, boolean modal) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ref", view.ref(root));
        List<Object> buttons = new ArrayList<Object>();
        String title;
        String text;
        String extractor;
        if (info != null) {
            title = info.title() != null ? info.title() : ownTitle;
            text = info.text();
            for (String label : info.buttons()) {
                Map<String, Object> b = new LinkedHashMap<String, Object>();
                Component c = buttonOrNull(root, label);
                if (c != null) {
                    b.put("ref", view.ref(c));
                }
                b.put("text", label);
                buttons.add(b);
            }
            extractor = info.extractorId();
        } else {
            title = ownTitle;
            JOptionPane pane = optionPane(root);
            text = pane != null ? messageText(pane.getMessage(), view) : readingText(root, view);
            for (AbstractButton button : textButtons(root)) {
                Map<String, Object> b = new LinkedHashMap<String, Object>();
                b.put("ref", view.ref(button));
                b.put("text", view.text(button));
                buttons.add(b);
            }
            extractor = GENERIC;
        }
        m.put("title", title);
        m.put("modal", Boolean.valueOf(modal));
        m.put("text", text);
        m.put("buttons", buttons);
        m.put("extractor", extractor);
        return m;
    }

    /** The title {@code swing.dialogs} reports for an in-window dialog: its extractor's, else the generic one. */
    String inWindowTitle(Component root) {
        DialogInfo info = extract(root);
        return info != null && info.title() != null ? info.title() : genericTitle(root);
    }

    /**
     * Button labels as {@link #describe} reports them, for a dialog window or the root of an in-window dialog.
     */
    List<String> buttonLabels(Component root) {
        List<String> labels = new ArrayList<String>();
        DialogInfo info = root instanceof Window ? extract((Window) root) : extract(root);
        if (info != null) {
            labels.addAll(info.buttons());
        } else {
            for (AbstractButton b : textButtons(root)) {
                labels.add(view.text(b));
            }
        }
        return labels;
    }

    private DialogInfo extract(Window d) {
        for (DialogTextExtractor e : extractors) {
            try {
                DialogInfo info = e.extract(d);
                if (info != null) {
                    return info;
                }
            } catch (RuntimeException | LinkageError t) {
                ctx.log().warn("swing: DialogTextExtractor " + e.getClass().getName() + " failed: " + t, t);
            }
        }
        return null;
    }

    /** The extractors' reading of an in-window dialog root (their {@code extract(Component)}). */
    private DialogInfo extract(Component root) {
        for (DialogTextExtractor e : extractors) {
            try {
                DialogInfo info = e.extract(root);
                if (info != null) {
                    return info;
                }
            } catch (RuntimeException | LinkageError t) {
                ctx.log().warn("swing: DialogTextExtractor " + e.getClass().getName() + " failed: " + t, t);
            }
        }
        return null;
    }

    /** An internal frame's title, else the title of a titled border on the root, else null; HTML reduced to text. */
    static String genericTitle(Component root) {
        if (root instanceof JInternalFrame) {
            return ComponentText.visible(((JInternalFrame) root).getTitle());
        }
        if (root instanceof JComponent) {
            Border border = ((JComponent) root).getBorder();
            if (border instanceof CompoundBorder) {
                Border outside = ((CompoundBorder) border).getOutsideBorder();
                border = outside instanceof TitledBorder ? outside : ((CompoundBorder) border).getInsideBorder();
            }
            if (border instanceof TitledBorder) {
                return ComponentText.visible(((TitledBorder) border).getTitle());
            }
        }
        return null;
    }

    /**
     * The component under {@code d} (a dialog window or an in-window dialog root) that presses the button labelled
     * {@code label}: showing buttons by their text or name, and showing childless custom components (role
     * {@code unknown}) by their name; exact match first, then case-insensitive.
     *
     * @throws RpcException NOT_FOUND (with {@code data.buttons}) or AMBIGUOUS (with {@code data.candidates})
     */
    Component findButton(Component d, String label) {
        List<Component> candidates = new ArrayList<Component>();
        collectButtonCandidates(d, candidates);
        for (int pass = 0; pass < 2; pass++) {
            List<Component> hits = new ArrayList<Component>();
            for (Component c : candidates) {
                for (String l : labelsOf(c)) {
                    if (l != null && (pass == 0 ? l.equals(label) : l.equalsIgnoreCase(label))) {
                        hits.add(c);
                        break;
                    }
                }
            }
            if (hits.size() == 1) {
                return hits.get(0);
            }
            if (hits.size() > 1) {
                List<Object> nodes = new ArrayList<Object>();
                for (Component c : hits) {
                    nodes.add(view.node(c));
                }
                throw Errors.ambiguous(hits.size() + " buttons match '" + label + "'", Errors.data("button", label,
                        "candidates", nodes));
            }
        }
        throw Errors.notFound("no button '" + label + "' in " + view.describe(d), Errors.data("button", label,
                "dialog", view.ref(d), "buttons", new ArrayList<Object>(buttonLabels(d))));
    }

    private Component buttonOrNull(Component d, String label) {
        try {
            return findButton(d, label);
        } catch (RpcException e) {
            return null;
        }
    }

    private List<String> labelsOf(Component c) {
        List<String> labels = new ArrayList<String>(2);
        if (c instanceof AbstractButton) {
            labels.add(view.text(c));
        }
        labels.add(view.name(c));
        return labels;
    }

    private static void collectButtonCandidates(Component c, List<Component> out) {
        if (!c.isShowing()) {
            return;
        }
        boolean leaf = !(c instanceof Container) || ((Container) c).getComponentCount() == 0;
        if (c instanceof AbstractButton || (leaf && "unknown".equals(Roles.of(c)))) {
            out.add(c);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collectButtonCandidates(child, out);
            }
        }
    }

    /** Showing buttons with text, in reading order (a masked button shows none, D349). */
    private List<AbstractButton> textButtons(Component root) {
        List<Component> all = new ArrayList<Component>();
        collectShowing(root, all);
        List<AbstractButton> out = new ArrayList<AbstractButton>();
        for (Component c : readingOrder(all)) {
            if (c instanceof AbstractButton && view.text(c) != null) {
                out.add((AbstractButton) c);
            }
        }
        return out;
    }

    /**
     * Texts of the showing labels and text components under root, in reading order: never a password field's, and,
     * with a view, never a masked component's (D349).
     *
     * @param view the call's view (its masked-text rule), or null for the built-in rule alone
     */
    static String readingText(Component root, ComponentView view) {
        List<Component> all = new ArrayList<Component>();
        collectShowing(root, all);
        StringBuilder sb = new StringBuilder();
        for (Component c : readingOrder(all)) {
            String role = Roles.of(c);
            if ("label".equals(role) || "textbox".equals(role) || "text".equals(role)) {
                String t = view != null ? view.text(c) : ComponentText.text(c);
                if (t != null && !t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The text of a {@code JOptionPane} message: strings (HTML reduced), arrays line by line, components read. */
    static String messageText(Object message) {
        return messageText(message, null);
    }

    /** {@link #messageText(Object)} with the call's masked-text rule for the components of the message. */
    static String messageText(Object message, ComponentView view) {
        if (message == null || message instanceof Icon) {
            return null;
        }
        if (message instanceof String) {
            return ComponentText.visible((String) message);
        }
        if (message instanceof Component) {
            return readingText((Component) message, view);
        }
        if (message instanceof Object[]) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (Object[]) message) {
                String t = messageText(part, view);
                if (t != null && !t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return String.valueOf(message);
    }

    private static JOptionPane optionPane(Component c) {
        if (c instanceof JOptionPane && c.isShowing()) {
            return (JOptionPane) c;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                JOptionPane found = optionPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectShowing(Component c, List<Component> out) {
        if (!c.isShowing()) {
            return;
        }
        out.add(c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collectShowing(child, out);
            }
        }
    }

    /** Top to bottom, then left to right, by position in the window; ties keep tree order. */
    private static List<Component> readingOrder(List<Component> components) {
        final Map<Component, Point> at = new java.util.IdentityHashMap<Component, Point>();
        for (Component c : components) {
            at.put(c, ComponentView.windowOffset(c));
        }
        List<Component> sorted = new ArrayList<Component>(components);
        Collections.sort(sorted, new Comparator<Component>() {
            @Override
            public int compare(Component a, Component b) {
                Point pa = at.get(a);
                Point pb = at.get(b);
                if (pa.y != pb.y) {
                    return pa.y < pb.y ? -1 : 1;
                }
                return pa.x == pb.x ? 0 : (pa.x < pb.x ? -1 : 1);
            }
        });
        return sorted;
    }
}
