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
import java.awt.Frame;
import java.awt.Label;
import java.awt.TextComponent;
import java.awt.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.table.TableModel;
import javax.swing.text.JTextComponent;

/**
 * The text-shaped facts of a component for snapshots, locators and dialogs: the visible text, the selected state, the
 * role-specific value, whether a {@code getName()} result is a name AWT made up, and the plain text of an HTML label.
 *
 * <p>Stateless. Every method reads Swing state and must be called on the EDT.
 */
final class ComponentText {

    /** Rows and columns of a table that a node's value carries. */
    static final int TABLE_ROWS = 50;
    static final int TABLE_COLUMNS = 50;
    /** Longest cell text a table value carries. */
    static final int CELL_CHARS = 200;

    /**
     * Names AWT generates when {@code getName()} is called on a heavyweight component without an explicit name
     * ({@code frame0}, {@code dialog3}, {@code win1}, {@code panel2}, ...). They change from run to run, so they are
     * not names.
     */
    private static final Pattern GENERATED_NAME = Pattern.compile(
            "(button|canvas|checkbox|choice|dialog|filedlg|frame|label|list|panel|scrollbar|scrollpane|text"
                    + "|textfield|win)\\d+");

    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>|</tr\\s*>"
            + "|</h[1-6]\\s*>|<p(\\s[^>]*)?>|<div(\\s[^>]*)?>|<li(\\s[^>]*)?>|<tr(\\s[^>]*)?>");
    private static final Pattern TAG = Pattern.compile("(?s)<!--.*?-->|<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);");

    private ComponentText() {
    }

    /**
     * The visible text: button and label text (HTML reduced to plain text), text-component content (never a password
     * field's), the selected item of a combo box or list, the selected tab title, a window title. Null if the
     * component shows no text; an empty text field gives "".
     */
    static String text(Component c) {
        if (c instanceof JPasswordField) {
            return null;
        }
        if (c instanceof JTextComponent) {
            return ((JTextComponent) c).getText();
        }
        if (c instanceof AbstractButton) {
            return emptyToNull(visible(((AbstractButton) c).getText()));
        }
        if (c instanceof JLabel) {
            return emptyToNull(visible(((JLabel) c).getText()));
        }
        if (c instanceof JComboBox) {
            Object item = ((JComboBox<?>) c).getSelectedItem();
            return item == null ? null : String.valueOf(item);
        }
        if (c instanceof JList) {
            Object item = ((JList<?>) c).getSelectedValue();
            return item == null ? null : String.valueOf(item);
        }
        if (c instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) c;
            int i = tabs.getSelectedIndex();
            return i < 0 ? null : emptyToNull(visible(tabs.getTitleAt(i)));
        }
        if (c instanceof Frame) {
            return emptyToNull(((Frame) c).getTitle());
        }
        if (c instanceof Dialog) {
            return emptyToNull(((Dialog) c).getTitle());
        }
        if (c instanceof TextField && ((TextField) c).echoCharIsSet()) {
            return null;
        }
        if (c instanceof TextComponent) {
            return ((TextComponent) c).getText();
        }
        if (c instanceof Button) {
            return emptyToNull(((Button) c).getLabel());
        }
        if (c instanceof Label) {
            return emptyToNull(((Label) c).getText());
        }
        if (c instanceof Checkbox) {
            return emptyToNull(((Checkbox) c).getLabel());
        }
        if (c instanceof Choice) {
            return ((Choice) c).getSelectedItem();
        }
        return null;
    }

    /** True/false for components with a selected state (toggles, check boxes, radios, check menu items); else null. */
    static Boolean selected(Component c) {
        if (c instanceof JToggleButton || c instanceof JCheckBoxMenuItem || c instanceof JRadioButtonMenuItem) {
            return Boolean.valueOf(((AbstractButton) c).isSelected());
        }
        if (c instanceof Checkbox) {
            return Boolean.valueOf(((Checkbox) c).getState());
        }
        return null;
    }

    /**
     * The role-specific value, or null: combo box -> selected index; list -> selected indices; table -> {rowCount,
     * columnCount, columns, selectedRows, cells (first 50 rows and columns, cell text capped at 200 characters)};
     * slider, progress bar, scroll bar -> the int value; spinner -> its value as a string; tabbed pane -> selected
     * index.
     */
    static Object value(Component c) {
        if (c instanceof JComboBox) {
            return Long.valueOf(((JComboBox<?>) c).getSelectedIndex());
        }
        if (c instanceof JList) {
            return longs(((JList<?>) c).getSelectedIndices());
        }
        if (c instanceof JTable) {
            return tableValue((JTable) c);
        }
        if (c instanceof JSlider) {
            return Long.valueOf(((JSlider) c).getValue());
        }
        if (c instanceof JProgressBar) {
            return Long.valueOf(((JProgressBar) c).getValue());
        }
        if (c instanceof JScrollBar) {
            return Long.valueOf(((JScrollBar) c).getValue());
        }
        if (c instanceof JSpinner) {
            Object v = ((JSpinner) c).getValue();
            return v == null ? null : String.valueOf(v);
        }
        if (c instanceof JTabbedPane) {
            return Long.valueOf(((JTabbedPane) c).getSelectedIndex());
        }
        return null;
    }

    private static Map<String, Object> tableValue(JTable table) {
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        int rows = table.getRowCount();
        int cols = table.getColumnCount();
        v.put("rowCount", Long.valueOf(rows));
        v.put("columnCount", Long.valueOf(cols));
        List<Object> names = new ArrayList<Object>();
        for (int col = 0; col < Math.min(cols, TABLE_COLUMNS); col++) {
            names.add(table.getColumnName(col));
        }
        v.put("columns", names);
        v.put("selectedRows", longs(table.getSelectedRows()));
        List<Object> cells = new ArrayList<Object>();
        for (int row = 0; row < Math.min(rows, TABLE_ROWS); row++) {
            List<Object> line = new ArrayList<Object>();
            for (int col = 0; col < Math.min(cols, TABLE_COLUMNS); col++) {
                Object cell = table.getValueAt(row, col);
                line.add(cell == null ? null : cap(String.valueOf(cell), CELL_CHARS));
            }
            cells.add(line);
        }
        v.put("cells", cells);
        TableModel model = table.getModel();
        if (model != null && model.getRowCount() != rows) {
            v.put("modelRowCount", Long.valueOf(model.getRowCount()));
        }
        return v;
    }

    /**
     * True if {@code name} is one AWT made up for a heavyweight component that was never named. Swing components never
     * get generated names, so their names are always kept.
     */
    static boolean generatedName(Component c, String name) {
        return !(c instanceof JComponent) && name != null && GENERATED_NAME.matcher(name).matches();
    }

    /**
     * The plain text a user sees for a Swing label text: HTML ({@code "<html>..."}) is reduced to its text with line
     * breaks for {@code <br>}, paragraphs, list items and rows; anything else is returned unchanged.
     */
    static String visible(String s) {
        if (s == null || !isHtml(s)) {
            return s;
        }
        String t = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        t = BREAK.matcher(t).replaceAll("\n");
        t = TAG.matcher(t).replaceAll("");
        t = unescape(t);
        StringBuilder out = new StringBuilder();
        for (String line : t.split("\n", -1)) {
            String collapsed = line.replace('\u00a0', ' ').trim().replaceAll(" {2,}", " ");
            if (!collapsed.isEmpty()) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(collapsed);
            }
        }
        return out.toString();
    }

    /** True for text Swing renders as HTML. */
    static boolean isHtml(String s) {
        return s.length() >= 6 && s.charAt(0) == '<' && s.charAt(5) == '>' && s.regionMatches(true, 1, "html", 0, 4);
    }

    private static String unescape(String t) {
        if (t.indexOf('&') < 0) {
            return t;
        }
        Matcher m = ENTITY.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String r;
            if (e.startsWith("#x") || e.startsWith("#X")) {
                r = codePoint(e.substring(2), 16, m.group());
            } else if (e.startsWith("#")) {
                r = codePoint(e.substring(1), 10, m.group());
            } else if ("lt".equals(e)) {
                r = "<";
            } else if ("gt".equals(e)) {
                r = ">";
            } else if ("amp".equals(e)) {
                r = "&";
            } else if ("quot".equals(e)) {
                r = "\"";
            } else if ("apos".equals(e)) {
                r = "'";
            } else if ("nbsp".equals(e)) {
                r = "\u00a0";
            } else {
                r = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String codePoint(String digits, int radix, String original) {
        try {
            int cp = Integer.parseInt(digits, radix);
            return Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : original;
        } catch (NumberFormatException e) {
            return original;
        }
    }

    static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    private static List<Object> longs(int[] values) {
        List<Object> out = new ArrayList<Object>(values.length);
        for (int v : values) {
            out.add(Long.valueOf(v));
        }
        return out;
    }
}
