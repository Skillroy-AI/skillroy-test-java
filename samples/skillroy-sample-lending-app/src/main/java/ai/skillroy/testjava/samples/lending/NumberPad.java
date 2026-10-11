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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

/**
 * The desk's number pad: one custom-painted component with twelve keys in three columns and four rows of 48 x 40
 * pixels ({@code 7 8 9 / 4 5 6 / 1 2 3 / . 0 CLEAR}), drawn with {@code drawString}. It is the desk's one component
 * with several meanings per component, so its keys are regions, not components: region ids {@code key:0} to
 * {@code key:9}, {@code key:.} and {@code key:clear}. Named {@value #NAME}, accessible role {@code PANEL}.
 *
 * <p>A press and a release inside the same key append its digit (or the point) to the entry field through the field's
 * own {@code Document}, so that the field's mask applies while a password is expected, or clear the field; the pad
 * never posts to the engine itself (the entry field's Enter does).
 *
 * <p>Event dispatch thread only, like every Swing component.
 */
public final class NumberPad extends JComponent {

    /** Component name of the number pad. */
    public static final String NAME = "numberPad";
    /** Key size. */
    public static final int KEY_WIDTH = 48;
    public static final int KEY_HEIGHT = 40;
    /** The keys' labels in reading order. */
    public static final List<String> LABELS = Collections.unmodifiableList(Arrays.asList(
        "7", "8", "9", "4", "5", "6", "1", "2", "3", ".", "0", "CLEAR"));

    private static final long serialVersionUID = 1L;
    private static final int COLUMNS = 3;

    private final transient EntryField entry;
    private String armed;

    /** @param entry the field the keys type into */
    public NumberPad(EntryField entry) {
        this.entry = entry;
        setName(NAME);
        setOpaque(true);
        Dimension size = new Dimension(COLUMNS * KEY_WIDTH, (LABELS.size() / COLUMNS) * KEY_HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                armed = keyAt(e.getPoint());
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                String key = armed;
                armed = null;
                repaint();
                if (key != null && key.equals(keyAt(e.getPoint()))) {
                    press(key);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (armed != null) {
                    armed = null;
                    repaint();
                }
            }
        });
    }

    /** The region id of a key label: {@code key:7}, {@code key:.}, {@code key:clear}. */
    public static String regionId(String label) {
        return "key:" + ("CLEAR".equals(label) ? "clear" : label);
    }

    /** The label of a region id, or null for an unknown one. */
    public static String labelOf(String regionId) {
        for (String label : LABELS) {
            if (regionId(label).equals(regionId)) {
                return label;
            }
        }
        return null;
    }

    /** The rectangle of a key label in the pad's own coordinates, or null for an unknown label. */
    public static Rectangle keyRect(String label) {
        int i = LABELS.indexOf(label);
        if (i < 0) {
            return null;
        }
        return new Rectangle((i % COLUMNS) * KEY_WIDTH, (i / COLUMNS) * KEY_HEIGHT, KEY_WIDTH, KEY_HEIGHT);
    }

    /** The label of the key under a point of the pad's own coordinates, or null. */
    public static String keyAt(Point p) {
        if (p == null || p.x < 0 || p.y < 0) {
            return null;
        }
        int col = p.x / KEY_WIDTH;
        int row = p.y / KEY_HEIGHT;
        int i = row * COLUMNS + col;
        return col < COLUMNS && i < LABELS.size() ? LABELS.get(i) : null;
    }

    /** @return the entry field the pad types into */
    public EntryField entryField() {
        return entry;
    }

    /**
     * Presses a key: appends the digit or the point to the entry field through its document, or clears the field.
     * Event dispatch thread.
     */
    public void press(String label) {
        if ("CLEAR".equals(label)) {
            entry.setText("");
            return;
        }
        if (!LABELS.contains(label)) {
            return;
        }
        Document doc = entry.getDocument();
        try {
            doc.insertString(doc.getLength(), label, null);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g2.setColor(getParent() != null ? getParent().getBackground() : Color.LIGHT_GRAY);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setFont(getFont() != null ? getFont().deriveFont(Font.BOLD, 14f) : new Font(Font.SANS_SERIF,
                Font.BOLD, 14));
            FontMetrics fm = g2.getFontMetrics();
            for (String label : LABELS) {
                Rectangle r = keyRect(label);
                g2.setColor(label.equals(armed) ? new Color(0x9db8d2) : new Color(0xdfe7ef));
                g2.fillRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4);
                g2.setColor(new Color(0x7a8a99));
                g2.drawRect(r.x + 2, r.y + 2, r.width - 5, r.height - 5);
                g2.setColor(isEnabled() ? new Color(0x23313f) : Color.GRAY);
                int tx = r.x + (r.width - fm.stringWidth(label)) / 2;
                int ty = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(label, tx, ty);
            }
        } finally {
            g2.dispose();
        }
    }

    /** An accessible context with the panel role. */
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleNumberPad();
        }
        return accessibleContext;
    }

    /** Accessible context of a {@link NumberPad}: role {@code PANEL}. */
    protected final class AccessibleNumberPad extends AccessibleJComponent {
        private static final long serialVersionUID = 1L;

        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.PANEL;
        }
    }
}
