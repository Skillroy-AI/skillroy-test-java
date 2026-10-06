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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Draws the keypad icons with shapes only (no images, no text), centred in a key of the given size.
 */
final class IconPainter {

    private IconPainter() {
    }

    static void paint(Graphics2D g, String actionId, int w, int h, Color color) {
        double s = Math.min(w, h) * 0.55;
        double x = (w - s) / 2.0;
        double y = (h - s) / 2.0;
        g.setColor(color);
        g.setStroke(new BasicStroke((float) Math.max(2.0, s / 12.0), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if ("DUE".equals(actionId)) {
            GeneralPath sigma = new GeneralPath();
            sigma.moveTo(x + s * 0.85, y + s * 0.1);
            sigma.lineTo(x + s * 0.2, y + s * 0.1);
            sigma.lineTo(x + s * 0.55, y + s * 0.5);
            sigma.lineTo(x + s * 0.2, y + s * 0.9);
            sigma.lineTo(x + s * 0.85, y + s * 0.9);
            g.draw(sigma);
        } else if ("DEPOSIT".equals(actionId)) {
            g.draw(new RoundRectangle2D.Double(x, y + s * 0.2, s, s * 0.6, s * 0.1, s * 0.1));
            g.draw(new Ellipse2D.Double(x + s * 0.35, y + s * 0.35, s * 0.3, s * 0.3));
        } else if ("RETURN".equals(actionId)) {
            g.draw(new Arc2D.Double(x + s * 0.1, y + s * 0.15, s * 0.8, s * 0.7, 90, 270, Arc2D.OPEN));
            GeneralPath head = new GeneralPath();
            head.moveTo(x + s * 0.35, y + s * 0.0);
            head.lineTo(x + s * 0.5, y + s * 0.15);
            head.lineTo(x + s * 0.35, y + s * 0.3);
            g.draw(head);
        } else if ("CANCEL".equals(actionId)) {
            g.draw(new Ellipse2D.Double(x, y, s, s));
            g.draw(new Line2D.Double(x + s * 0.3, y + s * 0.3, x + s * 0.7, y + s * 0.7));
            g.draw(new Line2D.Double(x + s * 0.7, y + s * 0.3, x + s * 0.3, y + s * 0.7));
        } else if ("SIGN_OFF".equals(actionId)) {
            g.draw(new Arc2D.Double(x + s * 0.1, y + s * 0.15, s * 0.8, s * 0.8, 120, 300, Arc2D.OPEN));
            g.draw(new Line2D.Double(x + s * 0.5, y, x + s * 0.5, y + s * 0.5));
        } else if ("QTY".equals(actionId)) {
            g.draw(new Line2D.Double(x + s * 0.2, y + s * 0.2, x + s * 0.8, y + s * 0.8));
            g.draw(new Line2D.Double(x + s * 0.8, y + s * 0.2, x + s * 0.2, y + s * 0.8));
            g.fill(new Ellipse2D.Double(x + s * 0.85, y + s * 0.85, s * 0.12, s * 0.12));
        } else {
            g.draw(new Ellipse2D.Double(x + s * 0.3, y + s * 0.3, s * 0.4, s * 0.4));
        }
    }
}
