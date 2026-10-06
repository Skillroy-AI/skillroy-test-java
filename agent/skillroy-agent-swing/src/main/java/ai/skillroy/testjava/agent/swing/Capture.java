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
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * PAINT capture: {@code printAll} into an image on the EDT, then PNG encoding off the EDT. Needs no Robot and works
 * when the window is covered. Images larger than 4096 x 4096 are scaled down to fit, keeping the aspect ratio.
 *
 * <p>Stateless. {@link #paint} must run on the EDT; {@link #png} on any thread.
 */
final class Capture {

    /** Largest width and height of a capture. */
    static final int MAX_SIDE = 4096;

    private Capture() {
    }

    /** Paints the component into a new RGB image. EDT only. */
    static BufferedImage paint(Component c) {
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
        Graphics2D g = image.createGraphics();
        try {
            Color background = c.getBackground();
            g.setColor(background != null ? background : Color.WHITE);
            g.fillRect(0, 0, iw, ih);
            if (scale < 1.0) {
                g.scale(scale, scale);
            }
            c.printAll(g);
        } finally {
            g.dispose();
        }
        return image;
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
