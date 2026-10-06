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

package ai.skillroy.testjava.samples.lending;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;

/**
 * The desk's main window (1024x768): the prompt line and status at the top, the loan table and totals in the
 * middle, the printer's slip panel on the right, and the entry field and keypad of {@link IconKey}s at the bottom.
 *
 * <p>The window only observes the engine and feeds its inbox: the entry field posts {@code Entry} on Enter, each key
 * posts {@code Key.<ACTION>} ({@code DEPOSIT} and {@code QTY} carry the entry-field text as their value). It never
 * calls the engine. Engine callbacks (engine thread) capture snapshots and post the screen update to the event dispatch
 * thread. The entry field is masked whenever the engine expects a password.
 *
 * <p>Create it on the event dispatch thread before the engine starts (it registers an engine listener). Closing the
 * window exits the JVM.
 */
public final class LendingFrame extends JFrame {

    /** Title used unless {@code lending.title} is set. */
    public static final String DEFAULT_TITLE = "Tool Library Lending Desk";
    /** Window width. */
    public static final int FRAME_WIDTH = 1024;
    /** Window height. */
    public static final int FRAME_HEIGHT = 768;
    /** The keypad keys, left to right. */
    public static final List<KeyAction> KEYPAD = Collections.unmodifiableList(Arrays.asList(
        KeyAction.QTY, KeyAction.CANCEL, KeyAction.RETURN, KeyAction.DUE, KeyAction.DEPOSIT, KeyAction.SIGN_OFF));

    private static final long serialVersionUID = 1L;

    private final transient Engine engine;
    private final transient SlipPrinter printer;
    private final JLabel promptLine = new JLabel(" ");
    private final JLabel statusLine = new JLabel(" ");
    private final EntryField entry = new EntryField(24);
    private final LoanTableModel loanModel = new LoanTableModel();
    private final JTable loanTable = new JTable(loanModel);
    private final JLabel totalsLine = new JLabel(" ");
    private final JTextArea slipArea = new JTextArea(30, SlipPrinter.WIDTH + 2);
    private final List<IconKey> keys;
    private final transient EngineListener engineListener;
    private final transient SlipListener slipListener;

    /**
     * @param title window title
     * @param engine the engine to observe and feed
     * @param printer the printer whose slips the slip panel shows
     */
    public LendingFrame(String title, Engine engine, SlipPrinter printer) {
        super(title);
        this.engine = engine;
        this.printer = printer;
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(FRAME_WIDTH, FRAME_HEIGHT);
        setLocation(0, 0);

        promptLine.setFont(promptLine.getFont().deriveFont(Font.BOLD, 22f));
        promptLine.setName("promptLine");
        statusLine.setName("statusLine");
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        top.add(promptLine, BorderLayout.CENTER);
        top.add(statusLine, BorderLayout.EAST);

        loanTable.setName("loanTable");
        loanTable.setFillsViewportHeight(true);
        loanTable.setFocusable(false);
        totalsLine.setName("totalsLine");
        totalsLine.setFont(totalsLine.getFont().deriveFont(Font.BOLD, 16f));
        totalsLine.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        JPanel middle = new JPanel(new BorderLayout());
        middle.add(new JScrollPane(loanTable), BorderLayout.CENTER);
        middle.add(totalsLine, BorderLayout.SOUTH);

        slipArea.setName("slip");
        slipArea.setEditable(false);
        slipArea.setFocusable(false);
        slipArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        slipArea.setBackground(new Color(0xfbfaf4));
        JScrollPane slipScroll = new JScrollPane(slipArea);
        slipScroll.setBorder(BorderFactory.createTitledBorder("Printer"));

        entry.setFont(entry.getFont().deriveFont(20f));
        entry.addActionListener(e -> engine.inbox().offer(LendingInput.entry(entry.takeValue())));
        JPanel keypad = new JPanel(new GridLayout(1, KEYPAD.size(), 8, 0));
        List<IconKey> list = new ArrayList<IconKey>();
        for (KeyAction action : KEYPAD) {
            IconKey key = new IconKey(action.name());
            key.addActionListener(e -> onKey(action));
            keypad.add(key);
            list.add(key);
        }
        keys = Collections.unmodifiableList(list);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        bottom.add(entry, BorderLayout.NORTH);
        bottom.add(keypad, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout(8, 0));
        content.add(top, BorderLayout.NORTH);
        content.add(middle, BorderLayout.CENTER);
        content.add(slipScroll, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        engineListener = new EngineListener() {
            @Override
            public void inputRequested(LendingState state, String prompt) {
                post();
            }

            @Override
            public void stateChanged(LendingState from, LendingState to) {
                post();
            }

            @Override
            public void promptShown(String id, String text, List<String> buttons) {
                post();
            }
        };
        slipListener = slip -> {
            String text = slip.text();
            EventQueue.invokeLater(() -> appendSlip(text));
        };
        engine.addListener(engineListener);
        printer.addListener(slipListener);
        for (Slip r : printer.slips()) {
            appendSlip(r.text());
        }
        refresh(engine.status(), engine.currentLoan(), engine.lastLoan());
    }

    /** Engine thread: captures snapshots and posts the update to the event dispatch thread. */
    private void post() {
        EngineStatus status = engine.status();
        Loan current = engine.currentLoan();
        Loan last = engine.lastLoan();
        EventQueue.invokeLater(() -> refresh(status, current, last));
    }

    private void refresh(EngineStatus status, Loan current, Loan last) {
        promptLine.setText(status.prompt());
        Staff staff = status.staff();
        statusLine.setText((staff == null ? "Signed off" : "Staff " + staff.code() + " " + staff.name())
            + "  |  " + status.state() + (status.returnMode() ? "  |  RETURN MODE" : ""));
        entry.setMasked(status.secretEntry());
        loanModel.setLoan(current);
        if (current != null) {
            totalsLine.setText("Items " + current.lines().size() + "    Subtotal " + Deposits.format(current.subtotal())
                + "    Waivers " + Deposits.format(current.waiverTotal())
                + "    Due " + Deposits.format(current.total()));
        } else if (last != null) {
            totalsLine.setText("Last loan " + last.id() + "    Due " + Deposits.format(last.total())
                + "    Change " + Deposits.format(last.change() == null ? BigDecimal.ZERO : last.change()));
        } else {
            totalsLine.setText(" ");
        }
    }

    private void appendSlip(String text) {
        if (slipArea.getDocument().getLength() > 0) {
            slipArea.append("\n\n");
        }
        slipArea.append(text);
        slipArea.setCaretPosition(slipArea.getDocument().getLength());
    }

    private void onKey(KeyAction action) {
        String value = null;
        if (action == KeyAction.DEPOSIT || action == KeyAction.QTY) {
            String typed = entry.takeValue();
            value = typed.trim().isEmpty() ? null : typed;
        }
        engine.inbox().offer(LendingInput.key(action, value));
        entry.requestFocusInWindow();
    }

    /** Removes the window's engine and printer listeners. Call after the engine has stopped (see LendingApp). */
    void detach() {
        engine.removeListener(engineListener);
        printer.removeListener(slipListener);
    }

    /** @return the entry field */
    public EntryField entryField() {
        return entry;
    }

    /** @return the loan table */
    public JTable loanTable() {
        return loanTable;
    }

    /** @return the loan table's model */
    public LoanTableModel loanModel() {
        return loanModel;
    }

    /** @return the printer's slip panel */
    public JTextArea slipArea() {
        return slipArea;
    }

    /** @return the prompt line */
    public JLabel promptLine() {
        return promptLine;
    }

    /** @return the totals line under the loan table */
    public JLabel totalsLine() {
        return totalsLine;
    }

    /** @return the keypad keys in {@link #KEYPAD} order */
    public List<IconKey> keys() {
        return keys;
    }

    /** @return the key with that action id, or null */
    public IconKey key(String actionId) {
        for (IconKey key : keys) {
            if (key.actionId().equals(actionId)) {
                return key;
            }
        }
        return null;
    }
}
