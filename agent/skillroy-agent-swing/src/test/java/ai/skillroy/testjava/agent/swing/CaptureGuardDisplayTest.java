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

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The guard of the {@code PAINT} capture on a display (phase4-contracts.md sections 2.5 and 14.1): a heavyweight
 * canvas that throws when printed, as JOGL's off-screen canvas does (BS8), reached only by the window's own printing,
 * is painted as a placeholder and listed in {@code skipped} with its ref, class name and error; a capture without a
 * failure has no {@code skipped}. Skipped headless.
 */
class CaptureGuardDisplayTest extends DisplayTestBase {

    /** Throws when printed, as JOGL's canvas does without {@code setupPrint()}. */
    static final class ThrowingCanvas extends Canvas {
        private static final long serialVersionUID = 1L;

        @Override
        public void print(Graphics g) {
            throw new IllegalStateException("setupPrint() not called");
        }
    }

    @Test
    void aHeavyweightThatThrowsWhenPrintedIsAPlaceholderInTheWindowsCapture() throws Exception {
        final JFrame frame = onEdt(() -> {
            JFrame f = new JFrame("capture guard");
            JPanel content = new JPanel(null);
            content.setBackground(new Color(20, 40, 160));
            content.setPreferredSize(new Dimension(320, 200));
            ThrowingCanvas canvas = new ThrowingCanvas();
            canvas.setBounds(50, 40, 120, 80);
            content.add(canvas);
            JLabel label = new JLabel("Loan desk");
            label.setBounds(200, 10, 100, 20);
            content.add(label);
            f.setContentPane(content);
            f.pack();
            f.setVisible(true);
            return f;
        });
        try {
            final ThrowingCanvas canvas = onEdt(() -> (ThrowingCanvas) frame.getContentPane().getComponent(0));
            await("the frame and its canvas showing", WAIT_MS, () -> frame.isShowing() && canvas.isShowing());
            Point at = onEdt(() -> SwingUtilities.convertPoint(canvas.getParent(), canvas.getLocation(), frame));
            Map<String, Object> shot = call("swing.capture", "target", ref(frame));
            List<?> skipped = (List<?>) shot.get("skipped");
            assertEquals(1, skipped.size(), String.valueOf(skipped));
            Map<?, ?> one = (Map<?, ?>) skipped.get(0);
            assertEquals(ref(canvas), one.get("ref"));
            assertEquals(ThrowingCanvas.class.getName(), one.get("className"));
            assertEquals("IllegalStateException: setupPrint() not called", one.get("error"));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) shot
                    .get("base64"))));
            assertEquals(Capture.PLACEHOLDER_FILL.getRGB() & 0xffffff, img.getRGB(at.x + 30, at.y + 10) & 0xffffff,
                    "the placeholder fills the canvas, off its lines");
            Map<String, Object> plain = call("swing.capture", "target", ref(ui.ok));
            assertFalse(plain.containsKey("skipped"), "nothing is skipped when nothing throws");
        } finally {
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }
}
