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

import ai.skillroy.testjava.agent.api.spi.NameEnricher;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.RpcResult;

import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
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
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.JViewport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Role mapping, text and value rules, the name rule, and the snapshot depth cap. Headless. */
class RolesAndTextTest {

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    @Test
    void rolesFollowTheComponentType() throws Exception {
        onEdt(() -> {
            assertRole("button", new JButton("x"));
            assertRole("toggle", new JToggleButton("x"));
            assertRole("checkbox", new JCheckBox("x"));
            assertRole("radio", new JRadioButton("x"));
            assertRole("password", new JPasswordField());
            assertRole("textbox", new JTextField());
            assertRole("textbox", new JTextArea());
            assertRole("text", new JEditorPane());
            assertRole("text", new JTextPane());
            assertRole("label", new JLabel("x"));
            assertRole("combobox", new JComboBox<String>());
            assertRole("list", new JList<String>());
            assertRole("table", new JTable());
            assertRole("tree", new JTree());
            assertRole("tab", new JTabbedPane());
            assertRole("menu", new JMenu("x"));
            assertRole("menu", new JMenuBar());
            assertRole("menu", new JPopupMenu());
            assertRole("menuitem", new JMenuItem("x"));
            assertRole("menuitem", new JCheckBoxMenuItem("x"));
            assertRole("scrollpane", new JScrollPane());
            assertRole("panel", new JPanel());
            assertRole("panel", new JRootPane());
            assertRole("panel", new JLayeredPane());
            assertRole("panel", new JViewport());
            assertRole("panel", new JToolBar());
            assertRole("panel", new JOptionPane());
            assertRole("unknown", new JComponent() { });
            assertRole("unknown", new JSlider());
            return null;
        });
        assertTrue(Roles.ALL.contains("unknown"));
        assertEquals(20, Roles.ALL.size());
    }

    private static void assertRole(String role, Component c) {
        assertEquals(role, Roles.of(c), c.getClass().getName());
    }

    @Test
    void textRules() throws Exception {
        onEdt(() -> {
            assertEquals("Save", ComponentText.text(new JButton("Save")));
            assertNull(ComponentText.text(new JButton()), "icon-only button has no text");
            assertNull(ComponentText.text(new JPasswordField("secret")), "passwords never leak");
            assertEquals("", ComponentText.text(new JTextField()), "an empty field is empty, not absent");
            assertEquals("Line one\nLine two", ComponentText.text(new JLabel("<html>Line one<br>Line two</html>")));
            JComboBox<String> combo = new JComboBox<String>(new String[] {"Red", "Green"});
            combo.setSelectedIndex(1);
            assertEquals("Green", ComponentText.text(combo));
            assertEquals(1L, ComponentText.value(combo));
            JList<String> items = new JList<String>(new String[] {"a", "b", "c"});
            items.setSelectedIndices(new int[] {0, 2});
            assertEquals("a", ComponentText.text(items));
            assertEquals(list(0L, 2L), ComponentText.value(items));
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("First", new JPanel());
            tabs.addTab("Second", new JPanel());
            tabs.setSelectedIndex(1);
            assertEquals("Second", ComponentText.text(tabs));
            assertEquals(1L, ComponentText.value(tabs));
            JCheckBox box = new JCheckBox("Remember", true);
            assertEquals(Boolean.TRUE, ComponentText.selected(box));
            assertNull(ComponentText.selected(new JButton("x")));
            assertEquals(40L, ComponentText.value(new JSlider(0, 100, 40)));
            JProgressBar bar = new JProgressBar(0, 10);
            bar.setValue(3);
            assertEquals(3L, ComponentText.value(bar));
            assertEquals("0", ComponentText.value(new JSpinner()));
            return null;
        });
    }

    @Test
    void tableValueIsCapped() throws Exception {
        Map<String, Object> v = onEdt(() -> {
            JTable table = new JTable(60, 3);
            table.setValueAt("first", 0, 0);
            table.setRowSelectionInterval(2, 3);
            return obj(ComponentText.value(table));
        });
        JsonCheck.requirePlainJson(v, "table");
        assertEquals(60L, v.get("rowCount"));
        assertEquals(3L, v.get("columnCount"));
        assertEquals(list("A", "B", "C"), v.get("columns"));
        assertEquals(list(2L, 3L), v.get("selectedRows"));
        List<Object> cells = JsonCheck.arr(v.get("cells"));
        assertEquals(ComponentText.TABLE_ROWS, cells.size());
        assertEquals("first", JsonCheck.arr(cells.get(0)).get(0));
        assertNull(JsonCheck.arr(cells.get(1)).get(0));
    }

    @Test
    void htmlIsReducedToVisibleText() {
        assertEquals("Total due", ComponentText.visible("<html>Total <b>due</b></html>"));
        assertEquals("a < b & c", ComponentText.visible("<HTML>a &lt; b &amp; c</HTML>"));
        assertEquals("One\nTwo", ComponentText.visible("<html><p>One</p><p>Two</p></html>"));
        assertEquals("xé", ComponentText.visible("<html>x&#233;</html>"));
        assertEquals("plain <b>", ComponentText.visible("plain <b>"), "non-HTML text is untouched");
        assertNull(ComponentText.visible(null));
    }

    @Test
    void generatedAwtNamesAreNotNames() {
        Container awt = new Container();
        assertTrue(ComponentText.generatedName(awt, "frame0"));
        assertTrue(ComponentText.generatedName(awt, "dialog12"));
        assertTrue(ComponentText.generatedName(awt, "win3"));
        assertFalse(ComponentText.generatedName(awt, "mainFrame"));
        assertFalse(ComponentText.generatedName(new JPanel(), "panel0"), "Swing components never get generated names");
    }

    @Test
    void nameRuleOrder() throws Exception {
        final JButton icon = new JButton();
        icon.setName("iconButton");
        final JTextField field = new JTextField();
        field.setName("entry");
        field.getAccessibleContext().setAccessibleName("Borrower code");
        assertEquals("iconButton", onEdt(() -> new ComponentView(ctx).name(icon)), "falls back to getName()");
        assertEquals("Borrower code", onEdt(() -> new ComponentView(ctx).name(field)), "accessible before getName");
        ctx.spi.register(NameEnricher.class, c -> {
            throw new IllegalStateException("broken enricher");
        });
        ctx.spi.register(NameEnricher.class, c -> c == icon ? "DUE" : "");
        assertEquals("DUE", onEdt(() -> new ComponentView(ctx).name(icon)), "enricher first; failures skipped");
        assertEquals("Borrower code", onEdt(() -> new ComponentView(ctx).name(field)), "empty enricher result");
        assertFalse(ctx.log.warnings().isEmpty(), "a failing enricher is logged");
    }

    @Test
    void nodeShape() throws Exception {
        Map<String, Object> node = onEdt(() -> {
            JPanel panel = new JPanel(null);
            JCheckBox box = new JCheckBox("Remember");
            box.setBounds(10, 20, 100, 30);
            panel.setBounds(5, 7, 300, 300);
            panel.add(box);
            return new ComponentView(ctx).node(box);
        });
        JsonCheck.requirePlainJson(node, "node");
        assertEquals(Arrays.asList("ref", "role", "class", "name", "text", "enabled", "visible", "showing", "focused",
                "bounds", "selected"), new java.util.ArrayList<String>(node.keySet()));
        assertEquals("checkbox", node.get("role"));
        assertEquals("JCheckBox", node.get("class"));
        assertEquals("Remember", node.get("name"));
        assertEquals(Boolean.FALSE, node.get("showing"));
        assertEquals(map("x", 15L, "y", 27L, "w", 100L, "h", 30L), node.get("bounds"),
                "relative to the topmost ancestor when there is no window");
    }

    @Test
    void snapshotDepthCapKeepsTheResultOnTheWire() throws Exception {
        final JPanel top = onEdt(() -> {
            JPanel root = new JPanel();
            JPanel p = root;
            for (int i = 0; i < 120; i++) {
                JPanel child = new JPanel();
                p.add(child);
                p = child;
            }
            return root;
        });
        Map<String, Object> capped = onEdt(() -> snapshotValue(top, SwingPrimitives.MAX_DEPTH));
        assertEquals(Boolean.TRUE, capped.get("truncated"));
        new RpcResult(capped, Collections.<Event>emptyList(), 0, 0, 0, "t");
        Json.write(map("jsonrpc", "2.0", "id", 1L, "result", new RpcResult(capped,
                Collections.<Event>emptyList(), 0, 0, 0, "t").toJson()));
        final Map<String, Object> tooDeep = onEdt(() -> snapshotValue(top, 91));
        assertThrows(JsonException.class, () -> new RpcResult(tooDeep, Collections.<Event>emptyList(), 0, 0, 0,
                "t"), "91 levels of plain panels no longer fit; the cap is lower still because a table at the last "
                + "level nests deeper than a panel (see RobustnessTest)");
    }

    @Test
    void snapshotNodeLimit() throws Exception {
        final JPanel top = onEdt(() -> {
            JPanel root = new JPanel();
            for (int i = 0; i < 10; i++) {
                root.add(new JButton("b" + i));
            }
            return root;
        });
        SnapshotWalker walker = onEdt(() -> {
            SnapshotWalker w = new SnapshotWalker(new ComponentView(ctx), 40, 4, true);
            w.walk(top);
            return w;
        });
        assertTrue(walker.truncated());
        Map<String, Object> node = onEdt(() -> new SnapshotWalker(new ComponentView(ctx), 40, 4, true).walk(top));
        assertEquals(3, JsonCheck.arr(node.get("children")).size(), "root + 3 children = 4 nodes");
        Map<String, Object> full = onEdt(() -> new SnapshotWalker(new ComponentView(ctx), 40, 100, false).walk(top));
        assertFalse(full.containsKey("children"), "non-showing children are left out without includeHidden");
    }

    private Map<String, Object> snapshotValue(Component root, int maxDepth) {
        SnapshotWalker walker = new SnapshotWalker(new ComponentView(ctx), maxDepth, 100000, true);
        Map<String, Object> node = walker.walk(root);
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("seq", 0L);
        value.put("truncated", walker.truncated());
        value.put("windows", list(node));
        return value;
    }

    @Test
    void dialogMessageText() {
        assertEquals("Delete?", DialogReader.messageText("Delete?"));
        assertEquals("Bold", DialogReader.messageText("<html><b>Bold</b></html>"));
        assertEquals("one\ntwo", DialogReader.messageText(new Object[] {"one", null, "two"}));
        assertNull(DialogReader.messageText(null));
        assertEquals("42", DialogReader.messageText(42));
    }
}
