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

package ai.skillroy.testjava.vision;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Draws a capture's marks on the controller's own copy of the raster (phase7-contracts.md 3.1, 6.2, D346): for every
 * mark a filled dark-blue box (RGB 31, 95, 168) with its number in white, 12-pixel sans, inside the mark's rectangle at
 * its top-left corner. The agent draws nothing, the capture's {@code hash} names the content, and the captures file
 * holds no mark. Also renders the marks list the model reads in a {@code <target-ui>} block. Stateless.
 */
public final class Marks {

    /** The box's colour. */
    public static final Color BOX = new Color(31, 95, 168);

    private Marks() {
    }

    /** A copy of {@code raster} with the marks drawn; the raster is not changed. */
    public static BufferedImage draw(BufferedImage raster, List<Capture.Mark> marks) {
        BufferedImage copy = new BufferedImage(raster.getWidth(), raster.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(raster, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            for (Capture.Mark m : marks) {
                String label = Integer.toString(m.mark());
                int w = fm.stringWidth(label) + 4;
                int h = fm.getAscent() + 2;
                int x = Math.max(0, Math.min(m.rect().x(), raster.getWidth() - w));
                int y = Math.max(0, Math.min(m.rect().y(), raster.getHeight() - h));
                g.setColor(BOX);
                g.fillRect(x, y, w, h);
                g.setColor(Color.WHITE);
                g.drawString(label, x + 2, y + fm.getAscent());
            }
        } finally {
            g.dispose();
        }
        return copy;
    }

    /**
     * The marks list as the model reads it, one line a mark: {@code mark 7: button DUE},
     * {@code mark 12: table row 3 "Garden spade 1 3.50"}, {@code mark 15: region key:7}; the label as the agent gave it
     * (nothing for a masked component, which carries no mark at all).
     */
    public static String list(List<Capture.Mark> marks) {
        StringBuilder out = new StringBuilder();
        for (Capture.Mark m : marks) {
            out.append("mark ").append(m.mark()).append(": ");
            if (m.row() != null) {
                out.append("table row ").append(m.row());
                if (m.label() != null && !m.label().isBlank()) {
                    out.append(" \"").append(m.label().strip()).append('"');
                }
            } else if (m.region() != null) {
                out.append(m.role() == null ? "region" : m.role()).append(" region ").append(m.region());
            } else if (m.label() != null && !m.label().isBlank()) {
                // The agent's label already carries the role (3.1: "role, name, text").
                out.append(m.label().strip());
            } else {
                out.append(m.role() == null ? "component" : m.role());
            }
            out.append('\n');
        }
        return out.toString();
    }
}
