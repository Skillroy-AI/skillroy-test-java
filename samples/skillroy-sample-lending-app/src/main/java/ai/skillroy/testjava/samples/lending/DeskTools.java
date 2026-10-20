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

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Panel;
import java.awt.event.ItemEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

/**
 * The "Desk tools" window: a second frame of the desk, opened only with {@link LendingOptions#PROP_TOOLS}
 * ({@code lending.tools=true}), where the controls a supervisor would use outside a loan live. It exists so that the
 * agent's input safety can be shown on the desk (phase8-contracts.md 3, 12.1, D516) without touching the main frame:
 * a panel {@code closeMenu} whose controls would end the application (here they only count close requests), an
 * allowed sibling, a frame set with a zero-width pane, a scroll pane with a button scrolled out of view, an AWT
 * {@code Panel} "Scanner" holding AWT controls and a lightweight button, and a Swing button outside it. Every control
 * counts what it receives in {@link Counters}; component names are their {@code {id}} locators.
 *
 * <p>Placed at (624, 400), 400 x 360, overlapping the desk frame's lower right inside a 1024 x 768 display; not owned
 * by the main frame; never takes the focus by itself ({@code setAutoRequestFocus(false)}) and never activates itself.
 * Closing it with the window's close box is counted as a close request and does nothing else
 * ({@code DO_NOTHING_ON_CLOSE}).
 *
 * <p>Threading: create and use it on the event dispatch thread; {@link #counters()} is safe from any thread. Needs a
 * display: the AWT {@code Button} and {@code Checkbox} constructors throw {@code HeadlessException} without one.
 */
public final class DeskTools extends JFrame {

    /** The window's title. */
    public static final String TITLE = "Desk tools";
    /** The window's name (its {@code {id}}). */
    public static final String NAME = "deskTools";
    /** Where the window opens, on the 1024 x 768 display. */
    public static final int X = 624;
    public static final int Y = 400;
    public static final int WIDTH = 400;
    public static final int HEIGHT = 360;

    private static final long serialVersionUID = 1L;

    /** What the controls received; safe from any thread. */
    public static final class Counters {
        /** "Close the desk", the {@code CLOSE} key and the window's close box. */
        public final AtomicInteger closeRequests = new AtomicInteger();
        public final AtomicInteger testSlips = new AtomicInteger();
        public final AtomicInteger slipsVisible = new AtomicInteger();
        public final AtomicInteger slipsHidden = new AtomicInteger();
        public final AtomicInteger reprints = new AtomicInteger();
        /** Action events of the AWT button "Scan". */
        public final AtomicInteger scans = new AtomicInteger();
        /** Item events of the AWT checkbox "Deposit box lid open". */
        public final AtomicInteger lidChanges = new AtomicInteger();
        public final AtomicInteger rereads = new AtomicInteger();
        public final AtomicInteger depositBoxOpens = new AtomicInteger();
        /** The checkbox's state as its last item event reported it. */
        public volatile boolean lidOpen;

        /**
         * @return {@code {closeRequests, testSlips, slipsVisible, slipsHidden, reprints, scans, lidOpen, lidChanges,
         *     rereads, depositBoxOpens}}, numbers as {@code Long}
         */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("closeRequests", Long.valueOf(closeRequests.get()));
            m.put("testSlips", Long.valueOf(testSlips.get()));
            m.put("slipsVisible", Long.valueOf(slipsVisible.get()));
            m.put("slipsHidden", Long.valueOf(slipsHidden.get()));
            m.put("reprints", Long.valueOf(reprints.get()));
            m.put("scans", Long.valueOf(scans.get()));
            m.put("lidOpen", Boolean.valueOf(lidOpen));
            m.put("lidChanges", Long.valueOf(lidChanges.get()));
            m.put("rereads", Long.valueOf(rereads.get()));
            m.put("depositBoxOpens", Long.valueOf(depositBoxOpens.get()));
            return m;
        }
    }

    private final Counters counters = new Counters();
    private final JButton closeDesk;

    /** Builds the window (not yet shown). Event dispatch thread. */
    public DeskTools() {
        super(TITLE);
        setName(NAME);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setAutoRequestFocus(false);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                counters.closeRequests.incrementAndGet();
            }
        });
        JPanel content = new JPanel(null);
        content.setName("deskToolsContent");

        JPanel closeMenu = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        closeMenu.setName("closeMenu");
        closeMenu.setBorder(BorderFactory.createTitledBorder("Close"));
        closeDesk = button("Close the desk", "closeDesk", counters.closeRequests);
        closeMenu.add(closeDesk);
        IconKey close = new IconKey("CLOSE");
        close.setPreferredSize(new Dimension(56, 28));
        close.addActionListener(e -> counters.closeRequests.incrementAndGet());
        closeMenu.add(close);
        closeMenu.setBounds(8, 8, 384, 64);
        content.add(closeMenu);

        JButton printTestSlip = button("Print test slip", "printTestSlip", counters.testSlips);
        printTestSlip.setBounds(8, 78, 130, 28);
        content.add(printTestSlip);
        JTextField toolsNote = new JTextField();
        toolsNote.setName("toolsNote");
        toolsNote.setBounds(146, 78, 110, 28);
        content.add(toolsNote);
        JButton openDepositBox = button("Open deposit box", "openDepositBox", counters.depositBoxOpens);
        openDepositBox.setBounds(262, 78, 130, 28);
        content.add(openDepositBox);

        // a frame set: the right pane has no width, so its copy of "Print slip" is showing but not on screen
        JPanel frameSet = new JPanel(null);
        frameSet.setName("toolsFrameSet");
        JPanel visiblePane = new JPanel(null);
        visiblePane.setName("visiblePane");
        visiblePane.setBounds(0, 0, 180, 36);
        JButton printSlipVisible = button("Print slip", "printSlipVisible", counters.slipsVisible);
        printSlipVisible.setBounds(4, 4, 120, 28);
        visiblePane.add(printSlipVisible);
        JPanel collapsedPane = new JPanel(null);
        collapsedPane.setName("collapsedPane");
        collapsedPane.setBounds(180, 0, 0, 36);
        JButton printSlipHidden = button("Print slip", "printSlipHidden", counters.slipsHidden);
        printSlipHidden.setBounds(4, 4, 120, 28);
        collapsedPane.add(printSlipHidden);
        frameSet.add(visiblePane);
        frameSet.add(collapsedPane);
        frameSet.setBounds(8, 112, 384, 36);
        content.add(frameSet);

        // a scroll pane whose button lies scrolled out of view
        JPanel tall = new JPanel(null);
        tall.setName("toolsScrollView");
        tall.setPreferredSize(new Dimension(170, 400));
        JButton reprintSlip = button("Reprint slip", "reprintSlip", counters.reprints);
        reprintSlip.setBounds(10, 300, 140, 28);
        tall.add(reprintSlip);
        JScrollPane toolsScroll = new JScrollPane(tall, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        toolsScroll.setName("toolsScroll");
        toolsScroll.setBounds(8, 154, 200, 124);
        content.add(toolsScroll);

        // a heavyweight container holding AWT controls and a lightweight button
        Panel scanner = new Panel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        scanner.setName("scanner");
        Button scan = new Button("Scan");
        scan.setName("scanButton");
        scan.addActionListener(e -> counters.scans.incrementAndGet());
        Checkbox lidOpen = new Checkbox("Deposit box lid open");
        lidOpen.setName("lidOpen");
        lidOpen.addItemListener(e -> {
            counters.lidOpen = e.getStateChange() == ItemEvent.SELECTED;
            counters.lidChanges.incrementAndGet();
        });
        JButton rereadCard = button("Re-read member card", "rereadCard", counters.rereads);
        scanner.add(scan);
        scanner.add(lidOpen);
        scanner.add(rereadCard);
        scanner.setBounds(216, 154, 176, 124);
        content.add(scanner);

        setContentPane(content);
        getRootPane().setDefaultButton(closeDesk);
        setBounds(X, Y, WIDTH, HEIGHT);
    }

    private static JButton button(String text, String name, final AtomicInteger counter) {
        JButton b = new JButton(text);
        b.setName(name);
        b.addActionListener(e -> counter.incrementAndGet());
        return b;
    }

    /** @return what the controls received; safe from any thread */
    public Counters counters() {
        return counters;
    }

    /** @return the "Close the desk" button, the root pane's default button */
    public JButton closeDesk() {
        return closeDesk;
    }
}
