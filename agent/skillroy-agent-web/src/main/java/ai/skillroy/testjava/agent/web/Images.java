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

package ai.skillroy.testjava.agent.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * The image work of {@code web.bitmap}: scaling down above a maximum width (keeping the aspect ratio) and PNG
 * encoding in memory with an explicit writer, so that the JVM-wide ImageIO cache setting is neither used nor changed
 * (as the Swing module's capture does). Off-screen only ({@code BufferedImage}), so it works headless; loaded only
 * when {@code web.bitmap} runs. The scaling is plain Java over the pixels (no {@code Graphics2D}, no
 * {@code AffineTransformOp}): a target without a user interface (a browser engine without a view) gets no graphics
 * environment, toolkit or Java2D thread from it (P1 A4).
 *
 * <p>Stateless; runs on agent workers, never on the EDT.
 */
final class Images {

    private Images() {
    }

    /** The image itself when it is not wider than {@code maxWidth}, else a scaled copy {@code maxWidth} wide. */
    static BufferedImage fit(BufferedImage image, int maxWidth) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= maxWidth || maxWidth <= 0) {
            return image;
        }
        int nh = Math.max(1, Math.min(h, (int) Math.round((double) h * maxWidth / w)));
        return shrink(image, maxWidth, nh);
    }

    /**
     * The image shrunk to {@code nw x nh} (each at most the source's size) by averaging the source pixels each target
     * pixel covers (a box filter: no aliasing at large factors), colours weighted by their alpha, in plain Java over
     * one row of pixels at a time.
     */
    static BufferedImage shrink(BufferedImage source, int nw, int nh) {
        int w = source.getWidth();
        int h = source.getHeight();
        int[] column = new int[w];
        for (int x = 0; x < w; x++) {
            column[x] = (int) ((long) x * nw / w);
        }
        long[] alpha = new long[nw];
        long[] red = new long[nw];
        long[] green = new long[nw];
        long[] blue = new long[nw];
        int[] count = new int[nw];
        int[] row = new int[w];
        int[] out = new int[nw];
        BufferedImage target = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        int sy = 0;
        for (int ty = 0; ty < nh; ty++) {
            int end = Math.max(sy + 1, (int) ((long) (ty + 1) * h / nh));
            java.util.Arrays.fill(alpha, 0);
            java.util.Arrays.fill(red, 0);
            java.util.Arrays.fill(green, 0);
            java.util.Arrays.fill(blue, 0);
            java.util.Arrays.fill(count, 0);
            for (; sy < end && sy < h; sy++) {
                source.getRGB(0, sy, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    int a = p >>> 24;
                    int t = column[x];
                    alpha[t] += a;
                    red[t] += (long) ((p >> 16) & 0xff) * a;
                    green[t] += (long) ((p >> 8) & 0xff) * a;
                    blue[t] += (long) (p & 0xff) * a;
                    count[t]++;
                }
            }
            for (int tx = 0; tx < nw; tx++) {
                long a = alpha[tx];
                if (count[tx] == 0 || a == 0) {
                    out[tx] = 0;
                    continue;
                }
                int na = (int) ((a + count[tx] / 2) / count[tx]);
                int nr = (int) ((red[tx] + a / 2) / a);
                int ng = (int) ((green[tx] + a / 2) / a);
                int nb = (int) ((blue[tx] + a / 2) / a);
                out[tx] = (na << 24) | (nr << 16) | (ng << 8) | nb;
            }
            target.setRGB(0, ty, nw, 1, out, 0, nw);
        }
        return target;
    }

    /** PNG bytes of the image. */
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
