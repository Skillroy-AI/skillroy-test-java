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

package ai.skillroy.testjava.spikes.bs10;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.MetalSliderUI;

/**
 * The toy window of spike BS10: one frame holding every case (a counting button, a disabled button, a button that a
 * glass pane can cover, a button that another window can cover, two text fields, two popup menus, a combo box, a
 * table, a long list, a slider), plus the glass panes, the covering window and the modal dialog the cases show. The
 * layout is absolute so that the points are the same on every JDK. Every field is touched on the EDT only.
 */
public final class Toy {

    static final String TEXT = "borrow the cordless drill until friday noon";
    static final String[] TOOLS = {"Hammer", "Drill", "Saw", "Ladder", "Clamp", "Sander"};
    static final int WIDTH = 700;
    static final int HEIGHT = 600;

    static volatile Toy instance;

    final JFrame frame;
    final JPanel content = new JPanel(null);
    final JButton count = new JButton("Count");
    final JButton disabled = new JButton("Disabled");
    final JButton underGlass = new JButton("Under glass");
    final JButton covered = new JButton("Covered");
    final JButton home = new JButton("Home");
    final JTextField caretField = new JTextField(TEXT);
    final JTextField selField = new JTextField(TEXT);
    final JLabel popupArea = new JLabel("Right-click (listener)", JLabel.CENTER);
    final JLabel popupArea2 = new JLabel("Right-click (component)", JLabel.CENTER);
    final JPopupMenu popup = new JPopupMenu();
    final JPopupMenu popup2 = new JPopupMenu();
    final JComboBox<String> combo = new JComboBox<String>(TOOLS);
    final JTable table;
    final JList<String> list;
    final JScrollPane listScroll;
    final JSlider slider = new JSlider(0, 100, 0);
    final ToySliderUI sliderUi = new ToySliderUI(slider);
    final JPanel glassListening = new JPanel(null);
    final JPanel glassTransparent = new JPanel(null);
    final JWindow cover;
    final JDialog modal;

    int countClicks;
    int countEntered;
    int countExited;
    int disabledActions;
    int disabledPresses;
    int underGlassClicks;
    int glassPresses;
    int coveredClicks;
    int coverPresses;
    int doubleClicks;
    int lastDoubleIndex = -1;
    String popupChosen = "-";
    String popup2Chosen = "-";

    /** The slider's UI with its thumb and track rectangles readable (Metal, on every platform). */
    static final class ToySliderUI extends MetalSliderUI {
        ToySliderUI(JSlider s) {
            super();
        }

        Rectangle thumb() {
            return new Rectangle(thumbRect);
        }

        Rectangle track() {
            return new Rectangle(trackRect);
        }
    }

    private Toy(String title, int x, int y, boolean autoRequestFocus) {
        frame = new JFrame(title);
        frame.setName("toyFrame");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setAutoRequestFocus(autoRequestFocus);
        content.setPreferredSize(new Dimension(WIDTH, HEIGHT));

        name(count, "count");
        count.setToolTipText("Counts clicks");
        count.addActionListener(e -> countClicks++);
        count.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                countEntered++;
            }

            @Override
            public void mouseExited(MouseEvent e) {
                countExited++;
            }
        });
        place(count, 10, 10, 120, 30);

        name(disabled, "disabled");
        disabled.setEnabled(false);
        disabled.addActionListener(e -> disabledActions++);
        disabled.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                disabledPresses++;
            }
        });
        place(disabled, 140, 10, 120, 30);

        name(underGlass, "underGlass");
        underGlass.addActionListener(e -> underGlassClicks++);
        place(underGlass, 270, 10, 140, 30);

        name(covered, "covered");
        covered.addActionListener(e -> coveredClicks++);
        place(covered, 420, 10, 120, 30);

        name(home, "home");
        place(home, 590, 10, 100, 30);

        name(caretField, "caretField");
        place(caretField, 10, 55, 420, 28);
        name(selField, "selField");
        place(selField, 10, 95, 420, 28);

        name(popupArea, "popupArea");
        popupArea.setOpaque(true);
        popupArea.setBackground(new Color(0xe8eef4));
        popupArea.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        for (String s : new String[] {"Alpha", "Beta", "Gamma"}) {
            JMenuItem item = new JMenuItem(s);
            item.setName("item" + s);
            item.addActionListener(e -> popupChosen = s);
            popup.add(item);
        }
        popup.setName("popup");
        popupArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
        place(popupArea, 10, 140, 200, 40);

        name(popupArea2, "popupArea2");
        popupArea2.setOpaque(true);
        popupArea2.setBackground(new Color(0xf4eee8));
        popupArea2.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        for (String s : new String[] {"One", "Two", "Three"}) {
            JMenuItem item = new JMenuItem(s);
            item.setName("item" + s);
            item.addActionListener(e -> popup2Chosen = s);
            popup2.add(item);
        }
        popup2.setName("popup2");
        popupArea2.setComponentPopupMenu(popup2);
        place(popupArea2, 220, 140, 200, 40);

        name(combo, "combo");
        place(combo, 440, 140, 160, 28);

        String[][] rows = new String[12][3];
        for (int r = 0; r < rows.length; r++) {
            rows[r] = new String[] {"Item " + r, "Loan " + (100 + r), "Deposit " + (5 * r)};
        }
        table = new JTable(rows, new String[] {"Item", "Loan", "Deposit"});
        name(table, "table");
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setName("tableScroll");
        place(tableScroll, 10, 195, 330, 170);

        String[] items = new String[200];
        for (int i = 0; i < items.length; i++) {
            items[i] = "Tool " + i;
        }
        list = new JList<String>(items);
        name(list, "list");
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doubleClicks++;
                    lastDoubleIndex = list.locationToIndex(e.getPoint());
                }
            }
        });
        listScroll = new JScrollPane(list);
        listScroll.setName("listScroll");
        place(listScroll, 360, 195, 240, 170);

        name(slider, "slider");
        slider.setUI(sliderUi);
        place(slider, 10, 385, 320, 50);

        frame.setContentPane(content);

        glassListening.setName("glassListening");
        glassListening.setOpaque(false);
        glassListening.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                glassPresses++;
            }
        });
        glassTransparent.setName("glassTransparent");
        glassTransparent.setOpaque(false);
        frame.setGlassPane(glassListening);

        frame.pack();
        frame.setLocation(x, y);

        cover = new JWindow(frame);
        cover.setName("cover");
        cover.setFocusableWindowState(false);
        cover.setAutoRequestFocus(false);
        JPanel coverPanel = new JPanel(new BorderLayout());
        coverPanel.setName("coverPanel");
        coverPanel.setBackground(new Color(0xf0a040));
        coverPanel.add(new JLabel("cover", JLabel.CENTER));
        coverPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                coverPresses++;
            }
        });
        cover.setContentPane(coverPanel);

        modal = new JDialog(frame, "BS10 modal", Dialog.ModalityType.APPLICATION_MODAL);
        modal.setName("modal");
        modal.getContentPane().setName("modalContent");
        modal.getContentPane().add(new JLabel("modal dialog", JLabel.CENTER));
        modal.setSize(240, 90);
    }

    private void place(JComponent c, int x, int y, int w, int h) {
        c.setBounds(x, y, w, h);
        content.add(c);
    }

    private static void name(Component c, String name) {
        c.setName(name);
    }

    /** Clears every counter and state the cases read. EDT only. */
    void resetState() {
        countClicks = 0;
        countEntered = 0;
        countExited = 0;
        disabledActions = 0;
        disabledPresses = 0;
        underGlassClicks = 0;
        glassPresses = 0;
        coveredClicks = 0;
        coverPresses = 0;
        doubleClicks = 0;
        lastDoubleIndex = -1;
        popupChosen = "-";
        popup2Chosen = "-";
        javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
        popup.setVisible(false);
        popup2.setVisible(false);
        combo.setPopupVisible(false);
        combo.setSelectedIndex(0);
        glassListening.setVisible(false);
        glassTransparent.setVisible(false);
        frame.setGlassPane(glassListening);
        caretField.setCaretPosition(0);
        selField.setCaretPosition(0);
        table.clearSelection();
        list.clearSelection();
        listScroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
        slider.setValue(0);
        javax.swing.ToolTipManager.sharedInstance().setEnabled(false);
        javax.swing.ToolTipManager.sharedInstance().setEnabled(true);
    }

    /**
     * Starts the toy: {@code -Dbs10.x}, {@code -Dbs10.y} place the frame (default 40, 40), {@code -Dbs10.title} names
     * it, {@code -Dbs10.autoRequestFocus=false} shows it without asking for the focus.
     */
    public static void main(String[] args) throws Exception {
        final int x = Integer.getInteger("bs10.x", 40);
        final int y = Integer.getInteger("bs10.y", 40);
        final String title = System.getProperty("bs10.title", "BS10 toy");
        final boolean auto = !"false".equals(System.getProperty("bs10.autoRequestFocus"));
        EventQueue.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(new MetalLookAndFeel());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Toy t = new Toy(title, x, y, auto);
            t.frame.setVisible(true);
            instance = t;
        });
    }
}
