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
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * A button of a {@link PromptDialog}: a custom-painted component whose label is drawn with
 * {@code Graphics.drawString} (it is not an {@code AbstractButton} and holds no label component). A mouse click or
 * {@link #press()} answers the prompt with {@link #label()}.
 *
 * <p>Event dispatch thread only.
 */
public final class PromptButton extends JComponent {

    private static final long serialVersionUID = 1L;

    private final String label;
    private final transient Consumer<PromptButton> onPress;
    private boolean armed;

    PromptButton(String label, Consumer<PromptButton> onPress) {
        this.label = Objects.requireNonNull(label, "label");
        this.onPress = Objects.requireNonNull(onPress, "onPress");
        setFocusable(false);
        setPreferredSize(new Dimension(Math.max(96, 16 + 10 * label.length()), 40));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    armed = true;
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!armed) {
                    return;
                }
                armed = false;
                repaint();
                if (contains(e.getPoint())) {
                    press();
                }
            }
        });
    }

    /** @return the button's label, which is also the prompt answer it gives */
    public String label() {
        return label;
    }

    /** Answers the dialog's prompt with this button's label. Event dispatch thread. */
    public void press() {
        onPress.accept(this);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(armed ? new Color(0x9db8d2) : new Color(0xdfe7ef));
            g2.fillRoundRect(1, 1, w - 3, h - 3, 10, 10);
            g2.setColor(new Color(0x56687a));
            g2.drawRoundRect(1, 1, w - 3, h - 3, 10, 10);
            g2.setColor(new Color(0x23313f));
            g2.setFont(getFont() != null ? getFont().deriveFont(15f) : g2.getFont());
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(label)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(label, tx, ty);
        } finally {
            g2.dispose();
        }
    }
}
