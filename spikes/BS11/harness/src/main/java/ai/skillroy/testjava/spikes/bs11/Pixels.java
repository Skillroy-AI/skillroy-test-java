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
package ai.skillroy.testjava.spikes.bs11;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/** Decoded pixels, their hash, and the difference between two images of the same size. */
final class Pixels {

    /** The per-channel tolerances of the tolerance study. */
    static final int[] TOLERANCES = {0, 8, 16, 32, 64};

    final int width;
    final int height;
    /** ARGB of every pixel, row by row. */
    final int[] argb;

    Pixels(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    static Pixels decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("not a decodable image (" + png.length + " bytes)");
        }
        int w = image.getWidth();
        int h = image.getHeight();
        return new Pixels(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    }

    /** SHA-256 over width, height and the ARGB of every pixel (big-endian ints). */
    String sha256() {
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 * argb.length);
        buf.putInt(width).putInt(height);
        for (int p : argb) {
            buf.putInt(p);
        }
        return sha256(buf.array());
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The crop {@code (x, y, w, h)} clipped to the image; null when nothing remains. */
    Pixels crop(int x, int y, int w, int h) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        int cw = x1 - x0;
        int ch = y1 - y0;
        int[] out = new int[cw * ch];
        for (int r = 0; r < ch; r++) {
            System.arraycopy(argb, (y0 + r) * width + x0, out, r * cw, cw);
        }
        return new Pixels(cw, ch, out);
    }

    /** The largest absolute per-channel difference (R, G, B, A) of two pixels. */
    static int channelDiff(int a, int b) {
        int m = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int d = Math.abs(((a >>> shift) & 0xff) - ((b >>> shift) & 0xff));
            if (d > m) {
                m = d;
            }
        }
        return m;
    }

    /** The difference of two images of the same size. */
    static Diff diff(Pixels a, Pixels b) {
        if (a.width != b.width || a.height != b.height) {
            throw new IllegalArgumentException("sizes differ: " + a.width + "x" + a.height + " and " + b.width + "x"
                    + b.height);
        }
        int n = a.argb.length;
        int[] mag = new int[n];
        int differing = 0;
        int max = 0;
        long[] above = new long[TOLERANCES.length];
        for (int i = 0; i < n; i++) {
            int d = channelDiff(a.argb[i], b.argb[i]);
            mag[i] = d;
            if (d > 0) {
                differing++;
            }
            if (d > max) {
                max = d;
            }
            for (int t = 0; t < TOLERANCES.length; t++) {
                if (d > TOLERANCES[t]) {
                    above[t]++;
                }
            }
        }
        return new Diff(a.width, a.height, mag, differing, max, above);
    }

    /** A difference: the per-pixel magnitude, counts above each tolerance, and the regions. */
    static final class Diff {
        final int width;
        final int height;
        final int[] magnitude;
        final int differing;
        final int maxChannel;
        /** Pixels whose magnitude exceeds {@link #TOLERANCES}[i]. */
        final long[] above;

        Diff(int width, int height, int[] magnitude, int differing, int maxChannel, long[] above) {
            this.width = width;
            this.height = height;
            this.magnitude = magnitude;
            this.differing = differing;
            this.maxChannel = maxChannel;
            this.above = above;
        }

        int total() {
            return width * height;
        }

        double percent() {
            return 100.0 * differing / total();
        }

        double fractionAbove(int toleranceIndex) {
            return (double) above[toleranceIndex] / total();
        }

        /**
         * The bounding boxes {@code [x, y, w, h, pixels]} of the regions of differing pixels, 8-connected after
         * merging pixels closer than {@code gap}, largest first.
         */
        List<int[]> regions(int gap) {
            boolean[] seen = new boolean[magnitude.length];
            List<int[]> out = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int start = 0; start < magnitude.length; start++) {
                if (magnitude[start] == 0 || seen[start]) {
                    continue;
                }
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = -1;
                int maxY = -1;
                int count = 0;
                seen[start] = true;
                queue.add(start);
                while (!queue.isEmpty()) {
                    int p = queue.poll();
                    int px = p % width;
                    int py = p / width;
                    count++;
                    minX = Math.min(minX, px);
                    minY = Math.min(minY, py);
                    maxX = Math.max(maxX, px);
                    maxY = Math.max(maxY, py);
                    for (int dy = -gap; dy <= gap; dy++) {
                        int ny = py + dy;
                        if (ny < 0 || ny >= height) {
                            continue;
                        }
                        for (int dx = -gap; dx <= gap; dx++) {
                            int nx = px + dx;
                            if (nx < 0 || nx >= width) {
                                continue;
                            }
                            int q = ny * width + nx;
                            if (!seen[q] && magnitude[q] > 0) {
                                seen[q] = true;
                                queue.add(q);
                            }
                        }
                    }
                }
                out.add(new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1, count});
            }
            out.sort((x, y) -> Integer.compare(y[4], x[4]));
            return out;
        }
    }
}
