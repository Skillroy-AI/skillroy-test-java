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

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard of the {@code PAINT} capture (phase4-contracts.md sections 2.5 and 14.1, a P1 section 6 change): a part
 * whose printing throws, as an embedded browser's GL canvas does (BS8), is painted as a placeholder rectangle and
 * listed, everything else is painted as before. Headless: the throwing part is a Swing component here (headless AWT
 * has no heavyweights); the heavyweight pass is the display tests' and the web acceptance's.
 */
class CaptureGuardTest {

    private static final Color BACK = new Color(20, 40, 160);
    private static final Color GREEN = new Color(30, 170, 60);
    private static final Color RED = new Color(190, 30, 30);

    /** Throws when printed, as JOGL's canvas does without {@code setupPrint()}. */
    static final class Throwing extends JComponent {
        private static final long serialVersionUID = 1L;

        @Override
        public void print(Graphics g) {
            throw new IllegalStateException("setupPrint() not called");
        }
    }

    /** A panel whose own painting throws (its children paint). */
    static final class ThrowingPanel extends JPanel {
        private static final long serialVersionUID = 1L;

        ThrowingPanel() {
            super(null);
        }

        @Override
        protected void paintComponent(Graphics g) {
            throw new IllegalStateException("own painting failed");
        }
    }

    private static JPanel panel(Color colour, int x, int y, int w, int h) {
        JPanel p = new JPanel(null);
        p.setOpaque(true);
        p.setBackground(colour);
        p.setBounds(x, y, w, h);
        return p;
    }

    private static <T> T onEdt(final Callable<T> task) throws Exception {
        final CompletableFuture<T> out = new CompletableFuture<T>();
        EventQueue.invokeLater(() -> {
            try {
                out.complete(task.call());
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out.get(10, TimeUnit.SECONDS);
    }

    private static BufferedImage capture(final Component c, final List<Capture.Skipped> skipped) throws Exception {
        return onEdt(() -> Capture.paint(c, skipped));
    }

    private static Color at(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y) & 0xffffff);
    }

    @Test
    void aCaptureWithoutAFailureListsNothing() throws Exception {
        JPanel root = panel(BACK, 0, 0, 200, 100);
        root.add(panel(GREEN, 10, 10, 50, 40));
        List<Capture.Skipped> skipped = new ArrayList<Capture.Skipped>();
        BufferedImage image = capture(root, skipped);
        assertTrue(skipped.isEmpty());
        assertEquals(GREEN, at(image, 20, 20));
        assertEquals(BACK, at(image, 150, 50));
    }

    @Test
    void aPartWhosePrintingThrowsIsAFilledPlaceholderAndTheRestIsPainted() throws Exception {
        JPanel root = panel(BACK, 0, 0, 200, 100);
        Throwing canvas = new Throwing();
        canvas.setBounds(100, 20, 60, 60);
        root.add(canvas);
        root.add(panel(GREEN, 10, 10, 50, 40));
        List<Capture.Skipped> skipped = new ArrayList<Capture.Skipped>();
        BufferedImage image = capture(root, skipped);
        assertEquals(1, skipped.size());
        assertSame(canvas, skipped.get(0).component);
        assertEquals("IllegalStateException: setupPrint() not called", skipped.get(0).error);
        assertEquals(GREEN, at(image, 20, 20), "painted before and after the failure");
        assertEquals(BACK, at(image, 180, 50));
        assertEquals(Capture.PLACEHOLDER_FILL, at(image, 130, 30), "inside the placeholder, off its lines");
        assertEquals(Capture.PLACEHOLDER_LINE, at(image, 100, 50), "its outline");
        assertEquals(Capture.PLACEHOLDER_LINE, at(image, 130, 50), "its cross");
    }

    @Test
    void theThrowingPartInsideAContainerIsTheOneListed() throws Exception {
        JPanel root = panel(BACK, 0, 0, 200, 100);
        JPanel holder = panel(RED, 0, 0, 100, 100);
        Throwing canvas = new Throwing();
        canvas.setBounds(20, 20, 40, 40);
        holder.add(canvas);
        root.add(holder);
        List<Capture.Skipped> skipped = new ArrayList<Capture.Skipped>();
        BufferedImage image = capture(root, skipped);
        assertEquals(1, skipped.size());
        assertSame(canvas, skipped.get(0).component);
        assertEquals(RED, at(image, 80, 80), "the container is painted around it");
        assertEquals(Capture.PLACEHOLDER_FILL, at(image, 45, 25));
        assertEquals(BACK, at(image, 150, 50));
    }

    @Test
    void aComponentWhoseOwnPaintingThrowsIsOutlinedOverItsChildren() throws Exception {
        ThrowingPanel root = new ThrowingPanel();
        root.setBackground(BACK);
        root.setBounds(0, 0, 200, 100);
        root.add(panel(GREEN, 10, 10, 50, 40));
        List<Capture.Skipped> skipped = new ArrayList<Capture.Skipped>();
        BufferedImage image = capture(root, skipped);
        assertEquals(1, skipped.size());
        assertSame(root, skipped.get(0).component);
        assertEquals("IllegalStateException: own painting failed", skipped.get(0).error);
        assertEquals(GREEN, at(image, 50, 15), "its child is painted, the placeholder is not filled");
        assertEquals(Capture.PLACEHOLDER_LINE, at(image, 0, 50), "the outline");
    }

    @Test
    void aTargetThatThrowsIsAPlaceholderOfItsSize() throws Exception {
        Throwing canvas = new Throwing();
        canvas.setBounds(0, 0, 80, 60);
        List<Capture.Skipped> skipped = new ArrayList<Capture.Skipped>();
        BufferedImage image = capture(canvas, skipped);
        assertEquals(80, image.getWidth());
        assertEquals(1, skipped.size());
        assertSame(canvas, skipped.get(0).component);
        assertEquals(Capture.PLACEHOLDER_FILL, at(image, 40, 10));
    }

    @Test
    void theSkippedPartsAreListedWithRefClassNameAndError() throws Exception {
        final FakeContext ctx = new FakeContext();
        JPanel root = panel(BACK, 0, 0, 200, 100);
        final Throwing canvas = new Throwing();
        canvas.setBounds(10, 10, 30, 30);
        root.add(canvas);
        final List<Capture.Skipped> skipped = new ArrayList<Capture.Skipped>();
        capture(root, skipped);
        List<Map<String, Object>> json = onEdt(() -> SwingPrimitives.skippedJson(new ComponentView(ctx), skipped));
        assertEquals(1, json.size());
        Map<String, Object> one = json.get(0);
        assertEquals(java.util.Arrays.asList("ref", "className", "error"), new ArrayList<String>(one.keySet()));
        assertSame(canvas, ctx.handles().resolve((String) one.get("ref"), Component.class));
        assertEquals(Throwing.class.getName(), one.get("className"));
        assertEquals("IllegalStateException: setupPrint() not called", one.get("error"));
    }
}
