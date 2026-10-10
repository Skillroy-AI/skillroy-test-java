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
package ai.skillroy.testjava.spikes.bs11.toy;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

/**
 * The BS11 toy: a small lending-desk-like window whose state changes only through its own command field, so that the
 * harness can make the three "real changes" of the tolerance study through the agent's own {@code swing.type}
 * ({@code SET} plus {@code enter}): {@code label} changes the amount label by one character ({@code 3.50} to
 * {@code 3.60}), {@code disable} disables the {@code Lend} button, {@code colour} turns the status swatch from green to
 * red and {@code tint} shifts its blue channel by 6 (a change below a loose tolerance), {@code reset} restores all.
 * The command field and the button are not focusable, so no caret and no focus ring are ever painted: the toy is a
 * measuring instrument, the desk is the realistic case.
 *
 * <p>With {@code -Dtoy.role=cover} it is only a plain opaque window of the given geometry ({@code toy.x}, {@code toy.y},
 * {@code toy.w}, {@code toy.h}) laid over the desk for the "covered" check.
 */
public final class CaptureToy {

    static final Color GREEN = new Color(46, 125, 50);
    static final Color RED = new Color(198, 40, 40);
    static final Color TINT = new Color(46, 125, 56);

    private CaptureToy() {
    }

    public static void main(String[] args) throws Exception {
        final String role = System.getProperty("toy.role", "toy");
        final int x = Integer.getInteger("toy.x", 0).intValue();
        final int y = Integer.getInteger("toy.y", 0).intValue();
        final int w = Integer.getInteger("toy.w", 480).intValue();
        final int h = Integer.getInteger("toy.h", 320).intValue();
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                JFrame frame = "cover".equals(role) ? cover() : toy();
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.setBounds(x, y, w, h);
                frame.setVisible(true);
            }
        });
        if ("cover".equals(role)) {
            // One more trip through the event queue, so that the window has been laid out and asked to paint.
            EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });
            System.out.println("BS11-COVER-SHOWN");
            System.out.flush();
        }
    }

    private static JFrame cover() {
        JFrame frame = new JFrame("BS11 cover");
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(30, 30, 90));
        JLabel label = new JLabel("covering window", JLabel.CENTER);
        label.setForeground(Color.WHITE);
        panel.add(label, BorderLayout.CENTER);
        frame.setContentPane(panel);
        return frame;
    }

    private static JFrame toy() {
        JFrame frame = new JFrame("BS11 toy");
        final JLabel amount = new JLabel("Deposit due 3.50");
        amount.setName("amount");
        amount.setFont(amount.getFont().deriveFont(Font.BOLD, 18f));
        final JButton lend = new JButton("Lend");
        lend.setName("lend");
        lend.setFocusable(false);
        final JPanel swatch = new JPanel();
        swatch.setName("status");
        swatch.setPreferredSize(new Dimension(96, 40));
        swatch.setBackground(GREEN);
        final JTextField command = new JTextField(16);
        command.setName("command");
        command.setFocusable(false);
        command.addActionListener(e -> {
            String c = command.getText().trim();
            if ("label".equals(c)) {
                amount.setText("Deposit due 3.60");
            } else if ("disable".equals(c)) {
                lend.setEnabled(false);
            } else if ("colour".equals(c)) {
                swatch.setBackground(RED);
            } else if ("tint".equals(c)) {
                swatch.setBackground(TINT);
            } else if ("reset".equals(c)) {
                amount.setText("Deposit due 3.50");
                lend.setEnabled(true);
                swatch.setBackground(GREEN);
            }
            command.setText("");
        });
        JPanel rows = new JPanel(new GridLayout(3, 1, 0, 8));
        rows.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel first = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        first.add(new JLabel("Item HND-001  Claw hammer"));
        first.add(swatch);
        rows.add(first);
        JPanel second = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        second.add(amount);
        second.add(lend);
        rows.add(second);
        JPanel third = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        third.add(new JLabel("Command"));
        third.add(command);
        rows.add(third);
        frame.setContentPane(rows);
        return frame;
    }
}
