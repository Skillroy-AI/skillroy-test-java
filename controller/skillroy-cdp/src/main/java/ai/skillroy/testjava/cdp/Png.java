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

package ai.skillroy.testjava.cdp;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * PNG helpers of {@code web.bitmap}: the dimensions from the {@code IHDR} chunk, and scaling down to a maximum width
 * with {@link ImageIO} and an {@link AffineTransformOp} (no {@code Graphics2D}, so no graphics environment and no
 * toolkit are touched; works in a headless JVM). Scaling keeps the aspect ratio (height rounded, at least 1), halves
 * the image while it is at least twice too wide and finishes with one bilinear step, so that a large factor does not
 * alias.
 *
 * <p>Stateless.
 */
final class Png {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private Png() {
    }

    /**
     * {@code {width, height}} of a PNG.
     *
     * @throws IllegalArgumentException if the bytes are not a PNG with an {@code IHDR} first chunk
     */
    static int[] size(byte[] png) {
        if (png == null || png.length < 24) {
            throw new IllegalArgumentException("not a PNG: too short");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                throw new IllegalArgumentException("not a PNG: wrong signature");
            }
        }
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
            throw new IllegalArgumentException("not a PNG: the first chunk is not IHDR");
        }
        int width = readInt(png, 16);
        int height = readInt(png, 20);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("not a PNG: size " + width + " x " + height);
        }
        return new int[] {width, height};
    }

    /**
     * The PNG itself when it is at most {@code maxWidth} wide, else a PNG scaled to {@code maxWidth} keeping the
     * aspect ratio.
     *
     * @throws IOException if ImageIO cannot decode or encode it
     */
    static byte[] scaleToWidth(byte[] png, int maxWidth) throws IOException {
        if (maxWidth < 1) {
            throw new IllegalArgumentException("maxWidth must be positive: " + maxWidth);
        }
        int[] size = size(png);
        if (size[0] <= maxWidth) {
            return png;
        }
        BufferedImage decoded = decode(png);
        int width = maxWidth;
        int height = (int) Math.max(1, Math.round(size[1] * (double) maxWidth / size[0]));
        BufferedImage image = argb(decoded);
        while (image.getWidth() / 2 >= width && image.getHeight() / 2 >= 1) {
            image = resize(image, image.getWidth() / 2, Math.max(1, image.getHeight() / 2));
        }
        if (image.getWidth() != width || image.getHeight() != height) {
            image = resize(image, width, height);
        }
        return encode(image);
    }

    /**
     * Decodes a PNG through a memory-cached stream (Phase 7 security fix S143, P2 11.3): no pixel data passes through
     * {@code java.io.tmpdir}, where {@code ImageIO.read} of a stream writes it with its default disk cache.
     *
     * @throws IOException if no reader decodes it
     */
    static BufferedImage decode(byte[] png) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("ImageIO cannot decode the PNG");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new IOException("ImageIO cannot decode the PNG");
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Encodes a raster as PNG through a memory-cached stream (S143: nothing through {@code java.io.tmpdir}).
     *
     * @throws IOException if there is no PNG writer or the write fails
     */
    static byte[] encode(BufferedImage image) throws IOException {
        java.util.Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IOException("ImageIO has no PNG writer");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(image);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BufferedImage argb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            source.getRGB(0, y, w, 1, row, 0, w);
            copy.setRGB(0, y, w, 1, row, 0, w);
        }
        return copy;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        AffineTransform scale = AffineTransform.getScaleInstance(width / (double) source.getWidth(),
                height / (double) source.getHeight());
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        new AffineTransformOp(scale, AffineTransformOp.TYPE_BILINEAR).filter(source, target);
        return target;
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }
}
