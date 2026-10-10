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

package ai.skillroy.testjava.scenario.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exact image comparison (phase7-contracts.md 6.7, decision D314): decoded ARGB rasters, both of the same size, the
 * masks blanked on both sides, the raster hashes compared; equal hashes hold, otherwise the differing pixels are
 * counted and the comparison holds only when the count is at most {@code maxDiffPixels} (the one explicit margin,
 * default 0). BS11 found the measured noise within one rendering zero and no tolerance that separates environment noise
 * from a one-character change, so there is no per-channel tolerance.
 *
 * <p>The raster hash ({@link #rasterHash}) has a pinned byte layout that the agent's {@code swing.capture} computes too
 * (phase7-contracts.md 3.1): {@code sha256:} and the lower-case hex SHA-256 over the width and the height as 4-byte
 * big-endian integers, then every pixel's {@code getRGB} value (alpha included, {@code 0xFF} for an opaque image) as a
 * 4-byte big-endian integer, rows top to bottom; never the PNG bytes, which differ between Java 8 and Java 25 for one
 * raster. Both modules' tests share one vector ({@code ImageCompareTest}).
 *
 * <p>Bounds: rasters of at most {@value #MAX_SIDE} pixels a side; a PNG's size is read from its header before it is
 * decoded. Stateless; thread-safe.
 */
public final class ImageCompare {

    /** The longest side of a raster this class decodes or compares. */
    public static final int MAX_SIDE = 4096;

    /** The fill of a masked pixel, on both sides of a comparison: opaque grey 128, the agent's redaction grey. */
    public static final int MASK_RGB = 0xFF808080;

    /** A channel difference above this is drawn red in the diff image, at or below it orange. */
    static final int RED_ABOVE = 32;

    private ImageCompare() {
    }

    /**
     * A rectangle in image coordinates.
     *
     * @param x the left edge
     * @param y the top edge
     * @param w the width, at least 0
     * @param h the height, at least 0
     */
    public record Rect(int x, int y, int w, int h) {

        /** @throws IllegalArgumentException if the width or the height is negative */
        public Rect {
            if (w < 0 || h < 0) {
                throw new IllegalArgumentException("a rectangle has a non-negative size: " + w + " x " + h);
            }
        }

        /** {@code {x, y, w, h}}; a new map. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("x", (long) x);
            json.put("y", (long) y);
            json.put("w", (long) w);
            json.put("h", (long) h);
            return json;
        }

        /**
         * A rectangle from {@code {x, y, w, h}} (numbers), or null when the value is not one.
         */
        public static Rect of(Object value) {
            if (!(value instanceof Map<?, ?> map) || !(map.get("x") instanceof Number x)
                    || !(map.get("y") instanceof Number y) || !(map.get("w") instanceof Number w)
                    || !(map.get("h") instanceof Number h) || w.longValue() < 0 || h.longValue() < 0
                    || Math.abs(x.longValue()) > Integer.MAX_VALUE / 2 || Math.abs(y.longValue()) > Integer.MAX_VALUE / 2
                    || w.longValue() > Integer.MAX_VALUE / 2 || h.longValue() > Integer.MAX_VALUE / 2) {
                return null;
            }
            return new Rect(x.intValue(), y.intValue(), w.intValue(), h.intValue());
        }
    }

    /**
     * What a comparison found.
     *
     * @param equal whether the masked rasters are identical (their hashes are equal)
     * @param sameSize whether both rasters have the same width and height; when not, nothing else was compared
     * @param differing how many unmasked pixels differ (0 when equal; every pixel of the larger raster when the sizes
     *     differ)
     * @param maxChannelDelta the largest difference in one channel (alpha, red, green, blue) over the differing pixels
     * @param box the bounding box of the differing pixels, or null when none differ
     * @param maskedPixels how many pixels the masks covered
     * @param focusMasked whether the focus owners' rectangles were among the masks (their locators differed)
     * @param expectedHash the masked baseline raster's hash
     * @param actualHash the masked current raster's hash
     * @param maxDiffPixels the margin the comparison was made with
     */
    public record Result(boolean equal, boolean sameSize, long differing, int maxChannelDelta, Rect box,
                         long maskedPixels, boolean focusMasked, String expectedHash, String actualHash,
                         int maxDiffPixels) {

        /** Whether the check holds: equal, or the same size with at most {@code maxDiffPixels} differing pixels. */
        public boolean holds() {
            return equal || (sameSize && differing <= maxDiffPixels);
        }

        /**
         * {@code {equal, differing, maxChannelDelta, box, maskedPixels, focusMasked, sameSize, expected, actual}}: the
         * run log's and the detail's fields; a new map.
         */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("equal", equal);
            json.put("differing", differing);
            json.put("maxChannelDelta", (long) maxChannelDelta);
            json.put("box", box == null ? null : box.toJson());
            json.put("maskedPixels", maskedPixels);
            json.put("focusMasked", focusMasked);
            json.put("sameSize", sameSize);
            json.put("expected", expectedHash);
            json.put("actual", actualHash);
            return json;
        }
    }

    /**
     * The raster hash of the pinned layout (see the class comment).
     *
     * @throws NullPointerException if the image is null
     */
    public static String rasterHash(BufferedImage image) {
        MessageDigest digest = sha256();
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer head = ByteBuffer.allocate(8);
        head.putInt(w).putInt(h);
        digest.update(head.array());
        int[] row = new int[w];
        ByteBuffer bytes = ByteBuffer.allocate(4 * w);
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            bytes.clear();
            for (int x = 0; x < w; x++) {
                bytes.putInt(row[x]);
            }
            digest.update(bytes.array(), 0, 4 * w);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Compares two rasters exactly after masks (see the class comment).
     *
     * @param expected the baseline
     * @param actual the current capture
     * @param masks the rectangles blanked on both sides (the baseline's masks, both captures' carets, the focus owners'
     *     rectangles on a focus mismatch), in image coordinates; clipped to the image
     * @param maxDiffPixels the margin, 0 to 10000
     * @param focusMasked whether the focus owners' rectangles are among the masks (reported only)
     * @throws IllegalArgumentException if a raster exceeds {@value #MAX_SIDE} pixels a side or the margin is negative
     */
    public static Result compare(BufferedImage expected, BufferedImage actual, List<Rect> masks, int maxDiffPixels,
                                 boolean focusMasked) {
        requireBounded(expected, "the baseline");
        requireBounded(actual, "the capture");
        if (maxDiffPixels < 0) {
            throw new IllegalArgumentException("maxDiffPixels must not be negative: " + maxDiffPixels);
        }
        List<Rect> clipped = clip(masks, expected.getWidth(), expected.getHeight());
        BufferedImage left = masked(expected, clipped);
        BufferedImage right = masked(actual, clip(masks, actual.getWidth(), actual.getHeight()));
        String leftHash = rasterHash(left);
        String rightHash = rasterHash(right);
        long maskedPixels = coverage(clipped, expected.getWidth(), expected.getHeight());
        boolean sameSize = expected.getWidth() == actual.getWidth() && expected.getHeight() == actual.getHeight();
        if (!sameSize) {
            long larger = Math.max((long) expected.getWidth() * expected.getHeight(),
                    (long) actual.getWidth() * actual.getHeight());
            return new Result(false, false, larger, 0, null, maskedPixels, focusMasked, leftHash, rightHash,
                    maxDiffPixels);
        }
        if (leftHash.equals(rightHash)) {
            return new Result(true, true, 0, 0, null, maskedPixels, focusMasked, leftHash, rightHash, maxDiffPixels);
        }
        int w = left.getWidth();
        int h = left.getHeight();
        long differing = 0;
        int maxDelta = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int[] a = new int[w];
        int[] b = new int[w];
        for (int y = 0; y < h; y++) {
            left.getRGB(0, y, w, 1, a, 0, w);
            right.getRGB(0, y, w, 1, b, 0, w);
            for (int x = 0; x < w; x++) {
                if (a[x] != b[x]) {
                    differing++;
                    maxDelta = Math.max(maxDelta, delta(a[x], b[x]));
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        Rect box = maxX < 0 ? null : new Rect(minX, minY, maxX - minX + 1, maxY - minY + 1);
        return new Result(false, true, differing, maxDelta, box, maskedPixels, focusMasked, leftHash, rightHash,
                maxDiffPixels);
    }

    /**
     * A copy of the image with the rectangles filled with {@link #MASK_RGB}, as an ARGB raster; the image itself is
     * not changed.
     */
    public static BufferedImage masked(BufferedImage image, List<Rect> masks) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            copy.setRGB(0, y, w, 1, row, 0, w);
        }
        for (Rect r : clip(masks, w, h)) {
            for (int y = r.y(); y < r.y() + r.h(); y++) {
                for (int x = r.x(); x < r.x() + r.w(); x++) {
                    copy.setRGB(x, y, MASK_RGB);
                }
            }
        }
        return copy;
    }

    /**
     * The diff image of two rasters of the same size (phase7-contracts.md 6.7): a pixel whose largest channel
     * difference is above {@value #RED_ABOVE} red, a smaller difference orange, an equal pixel grey (its luminance
     * lightened), a masked pixel the mask grey.
     *
     * @throws IllegalArgumentException if the sizes differ or a raster is too large
     */
    public static BufferedImage diff(BufferedImage expected, BufferedImage actual, List<Rect> masks) {
        requireBounded(expected, "the baseline");
        requireBounded(actual, "the capture");
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            throw new IllegalArgumentException("a diff needs two rasters of one size");
        }
        int w = expected.getWidth();
        int h = expected.getHeight();
        BufferedImage left = masked(expected, masks);
        BufferedImage right = masked(actual, masks);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] a = new int[w];
        int[] b = new int[w];
        for (int y = 0; y < h; y++) {
            left.getRGB(0, y, w, 1, a, 0, w);
            right.getRGB(0, y, w, 1, b, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb;
                if (a[x] == b[x]) {
                    if (a[x] == MASK_RGB) {
                        rgb = MASK_RGB;
                    } else {
                        int lum = (((a[x] >> 16) & 0xFF) * 3 + ((a[x] >> 8) & 0xFF) * 6 + (a[x] & 0xFF)) / 10;
                        int g = 176 + lum / 4;
                        rgb = 0xFF000000 | (g << 16) | (g << 8) | g;
                    }
                } else {
                    rgb = delta(a[x], b[x]) > RED_ABOVE ? 0xFFFF0000 : 0xFFFFA500;
                }
                out.setRGB(x, y, rgb);
            }
        }
        return out;
    }

    /**
     * Decodes a PNG, its size read from the header first.
     *
     * @throws IOException if the bytes are not a PNG this JDK reads, or the image exceeds {@value #MAX_SIDE} pixels a
     *     side
     */
    public static BufferedImage decodePng(byte[] png) throws IOException {
        if (png == null || png.length == 0) {
            throw new IOException("no image bytes");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            if (in == null) {
                throw new IOException("the bytes cannot be read as an image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) {
                throw new IOException("no PNG reader in this runtime");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
                    throw new IOException("the image is " + w + " x " + h + " pixels; at most " + MAX_SIDE
                            + " a side is read");
                }
                return reader.read(0);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IOException("the bytes are not a PNG: " + e.getMessage(), e);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Encodes a raster as PNG.
     *
     * @throws IOException if the runtime cannot write PNG
     */
    public static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer in this runtime");
        }
        return out.toByteArray();
    }

    /** The rectangles clipped to a {@code w} x {@code h} image; empty intersections are dropped. */
    static List<Rect> clip(List<Rect> masks, int w, int h) {
        List<Rect> out = new ArrayList<>();
        if (masks == null) {
            return out;
        }
        for (Rect r : masks) {
            if (r == null) {
                continue;
            }
            long x0 = Math.max(0, (long) r.x());
            long y0 = Math.max(0, (long) r.y());
            long x1 = Math.min(w, (long) r.x() + r.w());
            long y1 = Math.min(h, (long) r.y() + r.h());
            if (x1 > x0 && y1 > y0) {
                out.add(new Rect((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0)));
            }
        }
        return out;
    }

    /** How many pixels of a {@code w} x {@code h} image the (clipped) rectangles cover, overlaps counted once. */
    static long coverage(List<Rect> clipped, int w, int h) {
        if (clipped.isEmpty()) {
            return 0;
        }
        boolean[] covered = new boolean[w * h];
        long count = 0;
        for (Rect r : clipped) {
            for (int y = r.y(); y < r.y() + r.h(); y++) {
                for (int x = r.x(); x < r.x() + r.w(); x++) {
                    int i = y * w + x;
                    if (!covered[i]) {
                        covered[i] = true;
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int delta(int a, int b) {
        int max = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            max = Math.max(max, Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)));
        }
        return max;
    }

    private static void requireBounded(BufferedImage image, String what) {
        if (image.getWidth() > MAX_SIDE || image.getHeight() > MAX_SIDE) {
            throw new IllegalArgumentException(what + " is " + image.getWidth() + " x " + image.getHeight()
                    + " pixels; at most " + MAX_SIDE + " a side is compared");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
