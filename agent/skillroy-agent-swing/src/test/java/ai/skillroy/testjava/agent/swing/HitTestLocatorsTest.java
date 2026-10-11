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

import ai.skillroy.testjava.agent.api.spi.ActionFirer;
import ai.skillroy.testjava.agent.api.spi.CaptureMask;
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;

import java.awt.Component;
import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code swing.hitTest}'s parts that need no window (phase7-contracts.md sections 3.2, 13.1 item 1): the locator
 * candidates in authoring preference, each checked through the locator engine to match exactly the component (a
 * resolver's {@code describe} first, then id, name and role, text and role, class and index, path), {@code row} for a
 * table and a list, {@code fires}, and the masked-text rule in the dialog reader. The point-to-component half (the
 * deepest component, the mouse target, the covering windows, the blocker) needs real windows and is the display
 * tests'.
 */
class HitTestLocatorsTest {

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    /** A domain strategy {@code key}: buttons by their action command, described for the hit-test. */
    static final class KeyResolver implements LocatorResolver {
        @Override
        public Set<String> strategies() {
            return Collections.singleton("key");
        }

        @Override
        public List<Component> resolve(String strategy, String value, Component root) {
            List<Component> out = new java.util.ArrayList<Component>();
            collect(root, value, out);
            return out;
        }

        private static void collect(Component c, String value, List<Component> out) {
            if (c instanceof JButton && value.equals(((JButton) c).getActionCommand())) {
                out.add(c);
            }
            if (c instanceof java.awt.Container) {
                for (Component k : ((java.awt.Container) c).getComponents()) {
                    collect(k, value, out);
                }
            }
        }

        @Override
        public Map<String, String> describe(Component component) {
            return component instanceof JButton
                    ? Collections.singletonMap("key", ((JButton) component).getActionCommand())
                    : Collections.<String, String>emptyMap();
        }
    }

    private List<Object> locators(Component target, Component root) throws Exception {
        return onEdt(() -> HitTest.locators(new ComponentView(ctx), new SwingSpis(ctx), target,
                HitTest.MAX_LOCATORS, root, true));
    }

    @Test
    void candidatesComeInAuthoringPreferenceEachMatchingExactlyTheComponent() throws Exception {
        JPanel root = new JPanel(null);
        JButton due = new JButton("Due");
        due.setActionCommand("DUE");
        due.setName("dueButton");
        JButton twin = new JButton("Twin");
        JButton twin2 = new JButton("Twin");
        root.add(new JLabel("Desk"));
        root.add(due);
        root.add(twin);
        root.add(twin2);
        ctx.spi.register(LocatorResolver.class, new KeyResolver());
        List<Object> found = locators(due, root);
        assertEquals(list(map("key", "DUE"), map("id", "dueButton"), map("name", "Due", "role", "button"),
                map("text", "Due", "role", "button"), map("class", "JButton", "index", 0L), map("path", "1")), found);

        List<Object> twins = locators(twin2, root);
        for (Object o : twins) {
            Map<?, ?> m = (Map<?, ?>) o;
            assertFalse(m.containsKey("text") || m.containsKey("name"), "two buttons share the text: " + twins);
        }
        assertTrue(twins.contains(map("class", "JButton", "index", 2L)), twins.toString());
    }

    @Test
    void atMostSixCandidatesAndNoTextCandidateForALongText() throws Exception {
        JPanel root = new JPanel(null);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("word ");
        }
        JTextField field = new JTextField(sb.toString());
        root.add(field);
        List<Object> found = locators(field, root);
        for (Object o : found) {
            assertFalse(((Map<?, ?>) o).containsKey("text"), found.toString());
        }
        assertTrue(found.size() <= HitTest.MAX_LOCATORS);
    }

    @Test
    void theRowOfATableIsTheModelIndexAndOfAListTheIndex() throws Exception {
        JTable table = new JTable(new Object[][] {{"a"}, {"b"}, {"c"}}, new Object[] {"Item"});
        table.setRowHeight(20);
        table.setBounds(0, 0, 100, 60);
        table.setAutoCreateRowSorter(true);
        assertEquals(Long.valueOf(1), onEdt(() -> HitTest.rowAt(table, new Point(5, 25))));
        onEdt(() -> {
            table.getRowSorter().toggleSortOrder(0);
            table.getRowSorter().toggleSortOrder(0); // descending: view row 0 is model row 2
            return null;
        });
        assertEquals(Long.valueOf(2), onEdt(() -> HitTest.rowAt(table, new Point(5, 5))));
        JList<String> items = new JList<String>(new String[] {"x", "y"});
        items.setFixedCellHeight(20);
        items.setBounds(0, 0, 100, 100);
        assertEquals(Long.valueOf(1), onEdt(() -> HitTest.rowAt(items, new Point(5, 30))));
        assertNull(onEdt(() -> HitTest.rowAt(items, new Point(5, 90))), "below the last cell");
        assertNull(onEdt(() -> HitTest.rowAt(new JButton(), new Point(1, 1))));
    }

    @Test
    void firesIsWhatARegisteredFirerAccepts() throws Exception {
        final JButton key = new JButton("k");
        ctx.spi.register(ActionFirer.class, new ActionFirer() {
            @Override
            public boolean canFire(Component c) {
                return c == key;
            }

            @Override
            public void fire(Component c) {
            }
        });
        assertTrue(onEdt(() -> new SwingSpis(ctx).fires(key)));
        assertFalse(onEdt(() -> new SwingSpis(ctx).fires(new JButton())));
    }

    /**
     * Security fix S106 (L2-S1; the L2 sceptic's probe A): a masked label's or button's accessible name is its text,
     * so the name is withheld with the text, for a component a mask names and for one inside a masked container: no
     * node, no candidate, no first unique locator ({@code focus.ownerLocator}'s rule) and no description carries it, and
     * the {@code name} matcher finds nothing by it; a name the application set apart from the text stays.
     */
    @Test
    void aMaskedLabelsOrButtonsTextIsWithheldAsItsName() throws Exception {
        JPanel root = new JPanel(null);
        root.setBounds(0, 0, 400, 200);
        JPanel card = new JPanel(null);
        card.setBounds(10, 10, 300, 80);
        card.setName("memberCard");
        JLabel number = new JLabel("Member no 4111 2222 3333");
        number.setBounds(5, 5, 200, 20);
        card.add(number);
        JButton reveal = new JButton("PIN 4711");
        reveal.setBounds(5, 40, 120, 25);
        card.add(reveal);
        root.add(card);
        JLabel direct = new JLabel("Deposit ref 0815-77");
        direct.setBounds(10, 120, 200, 20);
        root.add(direct);
        JTextField code = new JTextField("9911");
        code.getAccessibleContext().setAccessibleName("Member code");
        code.setBounds(220, 120, 100, 20);
        root.add(code);
        ctx.spi.register(CaptureMask.class, c -> c == card || c == direct || c == code);
        for (final Component c : new Component[] {number, reveal, direct}) {
            final String content = onEdt(() -> ComponentText.text(c));
            Map<String, Object> node = onEdt(() -> new ComponentView(ctx).node(c));
            assertEquals(Boolean.TRUE, node.get("masked"), content);
            assertNull(node.get("text"), content);
            assertNull(node.get("name"), "the name Swing takes from the text is withheld: " + content);
            assertFalse(onEdt(() -> new ComponentView(ctx).describe(c)).contains(content));
            List<Object> found = locators(c, root);
            assertFalse(String.valueOf(found).contains(content), String.valueOf(found));
            assertFalse(String.valueOf(onEdt(() -> HitTest.firstUniqueLocator(new ComponentView(ctx),
                    new SwingSpis(ctx), c, root, true))).contains(content));
        }
        List<Component> byRegex = onEdt(() -> new LocatorEngine(new ComponentView(ctx)).find(Locator.parse(
                map("name", map("regex", "^Member no 41")), "by"), root, true));
        assertTrue(byRegex.isEmpty(), "the name matcher is no oracle: " + byRegex);
        Map<String, Object> field = onEdt(() -> new ComponentView(ctx).node(code));
        assertEquals("Member code", field.get("name"), "an accessible name set apart from the text stays");
        assertTrue(locators(code, root).contains(map("name", "Member code", "role", "textbox")));
        String snapshot = String.valueOf(onEdt(() -> new SnapshotWalker(new ComponentView(ctx), 10, 100, true)
                .walk(root)));
        for (String secret : new String[] {"4111", "4711", "0815", "9911"}) {
            assertFalse(snapshot.contains(secret), secret + " in " + snapshot);
        }
    }

    @Test
    void theDialogReaderWithholdsAMaskedComponentsText() throws Exception {
        JPanel message = new JPanel();
        JLabel label = new JLabel("Enter the code");
        JTextField code = new JTextField("4711");
        message.add(label);
        message.add(code);
        message.setSize(300, 50);
        message.doLayout();
        ctx.spi.register(CaptureMask.class, c -> c == code);
        String text = onEdt(() -> DialogReader.messageText(message, new ComponentView(ctx)));
        assertTrue(text == null || !text.contains("4711"), "the masked field's text is withheld: " + text);
    }
}
