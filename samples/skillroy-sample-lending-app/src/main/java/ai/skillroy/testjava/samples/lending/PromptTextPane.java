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
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * Paints a prompt's text, word-wrapped and centred, with {@code Graphics.drawString}. No label component holds the
 * text: that is the point of the sample's custom dialog.
 */
final class PromptTextPane extends JComponent {

    private static final long serialVersionUID = 1L;

    private final String text;

    PromptTextPane(String text) {
        this.text = text;
        setPreferredSize(new Dimension(420, 110));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(new Color(0x23313f));
            g2.setFont(getFont() != null ? getFont().deriveFont(18f) : g2.getFont());
            FontMetrics fm = g2.getFontMetrics();
            List<String> lines = wrap(text, fm, Math.max(50, getWidth() - 24));
            int lineHeight = fm.getHeight();
            int y = (getHeight() - lineHeight * lines.size()) / 2 + fm.getAscent();
            for (String line : lines) {
                g2.drawString(line, (getWidth() - fm.stringWidth(line)) / 2, y);
                y += lineHeight;
            }
        } finally {
            g2.dispose();
        }
    }

    private static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && fm.stringWidth(candidate) > width) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
