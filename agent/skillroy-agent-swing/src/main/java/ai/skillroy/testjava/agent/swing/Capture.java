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
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.JComponent;

/**
 * PAINT capture: {@code printAll} into an image on the EDT, then PNG encoding off the EDT. Needs no Robot and works
 * when the window is covered. Images larger than 4096 x 4096 are scaled down to fit, keeping the aspect ratio.
 *
 * <p><b>Guard</b> (phase4-contracts.md sections 2.5 and 14.1, a P1 section 6 change): when {@code printAll} of the
 * target throws (BS8: the GL canvas of an embedded browser rendered off-screen throws {@code IllegalStateException}),
 * the image is painted again part by part: a part whose {@code printAll} throws is painted child by child, a part
 * that alone throws is painted as a placeholder rectangle (filled, outlined and crossed; a container is outlined and
 * crossed over its children) and reported as {@link Skipped}; the heavyweight components, which only a window's own
 * printing reaches, are then painted one by one on top in the same way. When no part alone throws, the target itself
 * is the placeholder.
 *
 * <p>Stateless. {@link #paint} must run on the EDT; {@link #png} on any thread.
 */
final class Capture {

    /** Largest width and height of a capture. */
    static final int MAX_SIDE = 4096;
    /** Fill of a placeholder. */
    static final Color PLACEHOLDER_FILL = new Color(224, 224, 224);
    /** Outline and cross of a placeholder. */
    static final Color PLACEHOLDER_LINE = new Color(96, 96, 96);
    private static final int MAX_ERROR = 200;

    /** A component painted as a placeholder because its {@code printAll} threw. */
    static final class Skipped {
        final Component component;
        final String error;

        Skipped(Component component, Throwable error) {
            this.component = component;
            String text = error.getClass().getSimpleName() + (error.getMessage() == null ? ""
                    : ": " + error.getMessage());
            this.error = text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
        }
    }

    private Capture() {
    }

    /**
     * Paints the component into a new RGB image, guarded: every part painted as a placeholder is added to
     * {@code skipped}. EDT only.
     */
    static BufferedImage paint(Component c, List<Skipped> skipped) {
        int w = c.getWidth();
        int h = c.getHeight();
        if (w <= 0 || h <= 0) {
            throw Errors.invalid("nothing to capture: the component is " + w + " x " + h,
                    Errors.data("reason", "empty"));
        }
        double scale = Math.min(1.0, Math.min((double) MAX_SIDE / w, (double) MAX_SIDE / h));
        int iw = Math.max(1, (int) Math.floor(w * scale));
        int ih = Math.max(1, (int) Math.floor(h * scale));
        BufferedImage image = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(image, c, scale);
        try {
            Throwable failure = print(c, g);
            if (failure != null) {
                // What was painted before the failure stays; the parts are painted again on top of it.
                List<Skipped> found = new ArrayList<Skipped>();
                guarded(c, g, found, failure);
                heavyweights(c, g, found);
                if (found.isEmpty()) {
                    placeholder(g, w, h, true);
                    found.add(new Skipped(c, failure));
                }
                skipped.addAll(found);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** A graphics on the image, filled with the component's background and scaled. */
    private static Graphics2D canvas(BufferedImage image, Component c, double scale) {
        Graphics2D g = image.createGraphics();
        Color background = c.getBackground();
        g.setColor(background != null ? background : Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        if (scale < 1.0) {
            g.scale(scale, scale);
        }
        return g;
    }

    /** {@code printAll} on a graphics of its own; the failure, or null. */
    private static Throwable print(Component c, Graphics2D g) {
        Graphics2D cg = (Graphics2D) g.create();
        try {
            c.printAll(cg);
            return null;
        } catch (RuntimeException e) {
            return e;
        } catch (LinkageError e) {
            return e;
        } finally {
            cg.dispose();
        }
    }

    /**
     * Paints a part whose {@code printAll} failed (with {@code failure}) child by child, last child first as Swing
     * does; a part is a placeholder when it has no visible child, or when it is a Swing component (whose own printing
     * reaches no heavyweight) none of whose children failed alone.
     */
    private static void guarded(Component c, Graphics2D g, List<Skipped> found, Throwable failure) {
        List<Component> kids = visibleChildren(c);
        int before = found.size();
        for (int i = kids.size() - 1; i >= 0; i--) {
            Component k = kids.get(i);
            Graphics2D kg = (Graphics2D) g.create(k.getX(), k.getY(), k.getWidth(), k.getHeight());
            try {
                Throwable kidFailure = print(k, kg);
                if (kidFailure != null) {
                    guarded(k, kg, found, kidFailure);
                }
            } finally {
                kg.dispose();
            }
        }
        if (found.size() == before && (kids.isEmpty() || c instanceof JComponent)) {
            placeholder(g, c.getWidth(), c.getHeight(), kids.isEmpty());
            found.add(new Skipped(c, failure));
        }
    }

    /**
     * Paints the heavyweight components below the root one by one at their place, on top (a window's own printing
     * reaches them, a Swing container's does not); one that throws is handled as {@link #guarded} does.
     */
    private static void heavyweights(Component root, Graphics2D g, List<Skipped> found) {
        Map<Component, Boolean> skipped = new IdentityHashMap<Component, Boolean>();
        for (Skipped s : found) {
            skipped.put(s.component, Boolean.TRUE);
        }
        List<Component> heavy = new ArrayList<Component>();
        collectHeavyweights(root, skipped, heavy);
        for (Component k : heavy) {
            int x = 0;
            int y = 0;
            for (Component p = k; p != null && p != root; p = p.getParent()) {
                x += p.getX();
                y += p.getY();
            }
            Graphics2D kg = (Graphics2D) g.create(x, y, k.getWidth(), k.getHeight());
            try {
                Throwable failure = print(k, kg);
                if (failure != null) {
                    guarded(k, kg, found, failure);
                }
            } finally {
                kg.dispose();
            }
        }
    }

    /** The outermost heavyweight components below {@code c} (a window's own heavyweight is not one of them). */
    private static void collectHeavyweights(Component c, Map<Component, Boolean> skipped, List<Component> out) {
        for (Component k : visibleChildren(c)) {
            if (k.isDisplayable() && !k.isLightweight()) {
                if (!skipped.containsKey(k)) {
                    out.add(k);
                }
            } else {
                collectHeavyweights(k, skipped, out);
            }
        }
    }

    /** The visible children of a container with a size, in index order (index 0 is on top). */
    private static List<Component> visibleChildren(Component c) {
        List<Component> out = new ArrayList<Component>();
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                if (k != null && k.isVisible() && k.getWidth() > 0 && k.getHeight() > 0 && !(k instanceof Window)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    /** A placeholder rectangle over {@code w x h}: filled (a leaf) or not (a container), outlined and crossed. */
    private static void placeholder(Graphics2D g, int w, int h, boolean fill) {
        Graphics2D pg = (Graphics2D) g.create();
        try {
            if (fill) {
                pg.setColor(PLACEHOLDER_FILL);
                pg.fillRect(0, 0, w, h);
            }
            pg.setColor(PLACEHOLDER_LINE);
            pg.drawRect(0, 0, w - 1, h - 1);
            pg.drawLine(0, 0, w - 1, h - 1);
            pg.drawLine(0, h - 1, w - 1, 0);
        } finally {
            pg.dispose();
        }
    }

    /**
     * PNG bytes of the image. Encodes in memory with an explicit writer, so the JVM-wide ImageIO cache setting is
     * neither used (no temporary files) nor changed.
     */
    static byte[] png(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IOException("no PNG ImageWriter available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageOutputStream out = new MemoryCacheImageOutputStream(bytes);
        try {
            writer.setOutput(out);
            writer.write(image);
        } finally {
            writer.dispose();
            out.close();
        }
        return bytes.toByteArray();
    }
}
