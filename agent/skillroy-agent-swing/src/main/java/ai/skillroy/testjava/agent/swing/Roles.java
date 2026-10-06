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

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Label;
import java.awt.Panel;
import java.awt.ScrollPane;
import java.awt.TextComponent;
import java.awt.Window;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.text.JTextComponent;

/**
 * Maps a component to its snapshot role from its Java type, not from its accessible role, because custom widgets
 * often leave the accessible role wrong.
 *
 * <p>Type rules, most specific first: {@code Dialog} -> dialog; other windows and {@code JInternalFrame} -> window;
 * {@code JMenu}, {@code JMenuBar}, {@code JPopupMenu} -> menu; other {@code JMenuItem} -> menuitem; {@code JCheckBox}
 * -> checkbox; {@code JRadioButton} -> radio; other {@code JToggleButton} -> toggle; other buttons -> button;
 * {@code JPasswordField} -> password; {@code JTextField}, {@code JTextArea} and AWT text components -> textbox; other
 * {@code JTextComponent}s ({@code JEditorPane}, {@code JTextPane}, custom documents) -> text; labels -> label; combo
 * boxes and {@code Choice} -> combobox; lists -> list; {@code JTable} -> table; {@code JTree} -> tree;
 * {@code JTabbedPane} -> tab; scroll panes -> scrollpane; panels, root/layered panes, viewports, tool bars, split
 * panes, boxes and {@code JOptionPane} -> panel; everything else -> unknown.
 *
 * <p>Stateless. Reads only the component's class (and an AWT checkbox's group), so it may be called on any thread,
 * although callers use it on the EDT.
 */
final class Roles {

    /** Every role a node can have. */
    static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "window", "dialog", "panel", "button", "toggle", "checkbox", "radio", "textbox", "password", "label",
            "combobox", "list", "table", "tree", "tab", "menu", "menuitem", "scrollpane", "text", "unknown")));

    private Roles() {
    }

    static String of(Component c) {
        if (c instanceof Dialog) {
            return "dialog";
        }
        if (c instanceof Window || c instanceof JInternalFrame) {
            return "window";
        }
        if (c instanceof JMenu || c instanceof JMenuBar || c instanceof JPopupMenu) {
            return "menu";
        }
        if (c instanceof JMenuItem) {
            return "menuitem";
        }
        if (c instanceof JCheckBox) {
            return "checkbox";
        }
        if (c instanceof JRadioButton) {
            return "radio";
        }
        if (c instanceof JToggleButton) {
            return "toggle";
        }
        if (c instanceof AbstractButton || c instanceof Button) {
            return "button";
        }
        if (c instanceof Checkbox) {
            return ((Checkbox) c).getCheckboxGroup() != null ? "radio" : "checkbox";
        }
        if (c instanceof JPasswordField) {
            return "password";
        }
        if (c instanceof JTextField || c instanceof JTextArea || c instanceof TextComponent) {
            return "textbox";
        }
        if (c instanceof JTextComponent) {
            return "text";
        }
        if (c instanceof JLabel || c instanceof Label) {
            return "label";
        }
        if (c instanceof JComboBox || c instanceof Choice) {
            return "combobox";
        }
        if (c instanceof JList || c instanceof java.awt.List) {
            return "list";
        }
        if (c instanceof JTable) {
            return "table";
        }
        if (c instanceof JTree) {
            return "tree";
        }
        if (c instanceof JTabbedPane) {
            return "tab";
        }
        if (c instanceof JScrollPane || c instanceof ScrollPane) {
            return "scrollpane";
        }
        if (c instanceof JPanel || c instanceof JRootPane || c instanceof JLayeredPane || c instanceof JViewport
                || c instanceof JToolBar || c instanceof JSplitPane || c instanceof Box || c instanceof JOptionPane
                || c instanceof Panel) {
            return "panel";
        }
        return "unknown";
    }
}
