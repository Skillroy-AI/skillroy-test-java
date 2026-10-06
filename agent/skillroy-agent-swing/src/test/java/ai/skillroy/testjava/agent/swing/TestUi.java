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

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

/** The main test frame: named and unnamed controls, a nested panel, and two focus-gated icon keys. */
final class TestUi {

    static final String TITLE = "Skillroy test frame";

    JFrame frame;
    JTextField entry;
    JPasswordField pin;
    JTextArea notes;
    JButton ok;
    JButton cancel;
    JButton disabled;
    JButton hidden;
    JButton unfocusable;
    JCheckBox remember;
    JComboBox<String> colour;
    JList<String> items;
    JTable grid;
    JPanel nested;
    JLabel nestedLabel;
    JButton inner;
    JLabel html;
    TestWidgets.GatedKey due;
    TestWidgets.GatedKey deposit;

    final AtomicInteger okClicks = new AtomicInteger();
    final AtomicInteger cancelClicks = new AtomicInteger();
    final AtomicInteger innerClicks = new AtomicInteger();
    final AtomicInteger unfocusableClicks = new AtomicInteger();
    final AtomicInteger entryActions = new AtomicInteger();
    final AtomicReference<String> lastEntry = new AtomicReference<String>();

    /** Builds and shows the frame on the EDT and waits until it is showing. */
    static TestUi show() throws Exception {
        final TestUi ui = new TestUi();
        LocatorTest.onEdt(() -> {
            ui.build();
            return null;
        });
        JsonCheck.await("test frame showing", 30000, () -> ui.frame.isShowing());
        return ui;
    }

    private void build() {
        frame = new JFrame(TITLE);
        frame.setName("mainFrame");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel grid3 = new JPanel(new GridLayout(0, 3, 4, 4));

        entry = new JTextField(12);
        entry.setName("entry");
        entry.addActionListener(e -> {
            entryActions.incrementAndGet();
            lastEntry.set(entry.getText());
        });
        pin = new JPasswordField("1234", 8);
        pin.setName("pin");
        notes = new JTextArea("Read only notes");
        notes.setName("notes");
        notes.setEditable(false);
        ok = new JButton("OK");
        ok.setName("okButton");
        ok.addActionListener(e -> okClicks.incrementAndGet());
        cancel = new JButton("Cancel");
        cancel.addActionListener(e -> cancelClicks.incrementAndGet());
        disabled = new JButton("Disabled");
        disabled.setEnabled(false);
        hidden = new JButton("Hidden");
        hidden.setVisible(false);
        unfocusable = new JButton("No focus");
        unfocusable.setFocusable(false);
        unfocusable.addActionListener(e -> unfocusableClicks.incrementAndGet());
        remember = new JCheckBox("Remember");
        colour = new JComboBox<String>(new String[] {"Red", "Green", "Blue"});
        colour.setName("colour");
        items = new JList<String>(new String[] {"alpha", "beta", "gamma"});
        items.setName("items");
        grid = new JTable(new Object[][] {{"YRD-001", "3.50"}, {"PNT-002", "4.10"}}, new Object[] {"Code", "Deposit"});
        grid.setName("grid");
        nested = new JPanel(new BorderLayout());
        nested.setName("nested");
        nestedLabel = new JLabel("Nested label");
        inner = new JButton("Inner");
        inner.addActionListener(e -> innerClicks.incrementAndGet());
        nested.add(nestedLabel, BorderLayout.NORTH);
        nested.add(inner, BorderLayout.SOUTH);
        html = new JLabel("<html><b>Bold</b> text</html>");
        due = new TestWidgets.GatedKey("DUE");
        deposit = new TestWidgets.GatedKey("DEPOSIT");

        grid3.add(entry);
        grid3.add(pin);
        grid3.add(notes);
        grid3.add(ok);
        grid3.add(cancel);
        grid3.add(disabled);
        grid3.add(hidden);
        grid3.add(unfocusable);
        grid3.add(remember);
        grid3.add(colour);
        grid3.add(new JScrollPane(items));
        grid3.add(new JScrollPane(grid));
        grid3.add(nested);
        grid3.add(html);
        grid3.add(due);
        grid3.add(deposit);
        frame.setContentPane(grid3);
        frame.setBounds(0, 0, 640, 480);
        frame.setVisible(true);
    }

    void dispose() throws Exception {
        disposeAll();
    }

    /** Disposes every window of the JVM and waits until none is displayable. */
    static void disposeAll() throws Exception {
        LocatorTest.onEdt(() -> {
            for (Window w : Window.getWindows()) {
                w.dispose();
            }
            return null;
        });
        JsonCheck.await("all windows disposed", 30000, () -> {
            for (Window w : Window.getWindows()) {
                if (w.isDisplayable()) {
                    return false;
                }
            }
            return true;
        });
    }
}
