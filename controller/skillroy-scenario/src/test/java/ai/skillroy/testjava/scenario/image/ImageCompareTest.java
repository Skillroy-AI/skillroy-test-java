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

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 5, {@code ImageCompareTest} (6.7, 3.1, decision D314): the raster hash's pinned layout
 * against the one test vector the agent's tests share, the exact comparison (equal, one pixel, masks, the carets'
 * union, the focus mask, {@code maxDiffPixels}, another size), the diff image and the PNG bounds.
 */
class ImageCompareTest {

    /**
     * The shared test vector (phase7-contracts.md 3.1: "both modules' tests share one vector"): a 3 x 2 raster, rows
     * {@code 0xFF000000 0xFFFFFFFF 0xFF808080 / 0xFF102030 0xFFFF0000 0xFF00FF00}; its hash over the width and the
     * height as 4-byte big-endian integers, then every pixel's {@code getRGB} as a 4-byte big-endian integer. The same
     * six pixels and the same hash literal are the agent's {@code CaptureTest.VECTOR} and {@code VECTOR_HASH}
     * ({@code skillroy-agent-swing}), so that the two implementations of the layout are proven to agree (fix wave of
     * the Phase 7 review, FA, L3-F1: the two tests used to hold two different vectors).
     */
    static final int[] VECTOR = {0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFF102030, 0xFFFF0000, 0xFF00FF00};

    /** The vector's hash: the agent's {@code CaptureTest.VECTOR_HASH}, the same literal. */
    static final String VECTOR_HASH = "sha256:6be118c38e93cd2b790c98897d10e69afa4ea5b1248c59203b1c2633a884c4ba";

    static BufferedImage vector(int type) {
        BufferedImage image = new BufferedImage(3, 2, type);
        for (int i = 0; i < VECTOR.length; i++) {
            image.setRGB(i % 3, i / 3, VECTOR[i]);
        }
        return image;
    }

    static BufferedImage filled(int w, int h, int rgb) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    @Test
    void theRasterHashHasThePinnedLayoutOfTheSharedVector() throws Exception {
        assertEquals(VECTOR_HASH, ImageCompare.rasterHash(vector(BufferedImage.TYPE_INT_ARGB)));
        assertEquals(VECTOR_HASH, ImageCompare.rasterHash(vector(BufferedImage.TYPE_INT_RGB)),
                "an opaque image hashes alpha 0xFF: the same bytes");
        BufferedImage decoded = ImageCompare.decodePng(ImageCompare.encodePng(vector(BufferedImage.TYPE_INT_RGB)));
        assertEquals(VECTOR_HASH, ImageCompare.rasterHash(decoded), "the decoded raster, never the PNG bytes");
    }

    @Test
    void equalRastersHoldAndOnePixelIsCountedBoxedAndMeasured() {
        BufferedImage a = filled(20, 10, 0xFFFFFF);
        BufferedImage b = filled(20, 10, 0xFFFFFF);
        ImageCompare.Result equal = ImageCompare.compare(a, b, List.of(), 0, false);
        assertTrue(equal.equal());
        assertTrue(equal.holds());
        assertEquals(0, equal.differing());
        assertNull(equal.box());
        assertEquals(equal.expectedHash(), equal.actualHash());
        b.setRGB(7, 3, 0xF0F0F0);
        ImageCompare.Result one = ImageCompare.compare(a, b, List.of(), 0, false);
        assertFalse(one.equal());
        assertFalse(one.holds(), "exact: maxDiffPixels 0");
        assertEquals(1, one.differing());
        assertEquals(new ImageCompare.Rect(7, 3, 1, 1), one.box());
        assertEquals(15, one.maxChannelDelta());
        assertTrue(ImageCompare.compare(a, b, List.of(), 1, false).holds(), "the one explicit margin");
        assertEquals(1L, one.toJson().get("differing"));
    }

    @Test
    void masksAreBlankedOnBothSidesAndCounted() {
        BufferedImage a = filled(20, 10, 0xFFFFFF);
        BufferedImage b = filled(20, 10, 0xFFFFFF);
        b.setRGB(2, 2, 0x000000);
        b.setRGB(15, 8, 0x000000);
        List<ImageCompare.Rect> masks = List.of(new ImageCompare.Rect(0, 0, 5, 5), new ImageCompare.Rect(14, 7, 10,
                10));
        ImageCompare.Result result = ImageCompare.compare(a, b, masks, 0, false);
        assertTrue(result.equal(), "both differences lie under a mask");
        assertEquals(25 + 6 * 3, result.maskedPixels(), "a mask is clipped to the image, overlaps counted once");
        ImageCompare.Result caretUnion = ImageCompare.compare(a, b, List.of(new ImageCompare.Rect(2, 2, 1, 1),
                new ImageCompare.Rect(15, 8, 1, 1)), 0, true);
        assertTrue(caretUnion.equal(), "the union of the baseline's and the capture's carets");
        assertTrue(caretUnion.focusMasked(), "reported as given");
        BufferedImage masked = ImageCompare.masked(b, masks);
        assertEquals(ImageCompare.MASK_RGB, masked.getRGB(2, 2));
        assertEquals(0xFF000000, b.getRGB(2, 2), "the image itself is unchanged");
    }

    @Test
    void anotherSizeNeverHolds() {
        ImageCompare.Result result = ImageCompare.compare(filled(10, 10, 0), filled(10, 11, 0), List.of(), 10_000,
                false);
        assertFalse(result.sameSize());
        assertFalse(result.holds(), "whatever the margin");
        assertEquals(110, result.differing());
    }

    @Test
    void theDiffImageIsRedOrangeAndGrey() {
        BufferedImage a = filled(4, 1, 0x808080);
        BufferedImage b = filled(4, 1, 0x808080);
        b.setRGB(1, 0, 0xFF0000);
        b.setRGB(2, 0, 0x8A8080);
        BufferedImage diff = ImageCompare.diff(a, b, List.of(new ImageCompare.Rect(3, 0, 1, 1)));
        assertEquals(0xFFFF0000, diff.getRGB(1, 0), "above 32 in a channel: red");
        assertEquals(0xFFFFA500, diff.getRGB(2, 0), "below: orange");
        int equal = diff.getRGB(0, 0);
        assertEquals((equal >> 16) & 0xFF, equal & 0xFF, "an equal pixel is grey");
        assertEquals(ImageCompare.MASK_RGB, diff.getRGB(3, 0), "a masked pixel the mask grey");
        assertThrows(IllegalArgumentException.class, () -> ImageCompare.diff(a, filled(5, 1, 0), List.of()));
    }

    @Test
    void aPngIsBoundedBeforeItIsDecoded() throws Exception {
        assertThrows(IOException.class, () -> ImageCompare.decodePng(new byte[0]));
        assertThrows(IOException.class, () -> ImageCompare.decodePng("not a png".getBytes()));
        BufferedImage wide = new BufferedImage(ImageCompare.MAX_SIDE + 1, 1, BufferedImage.TYPE_INT_RGB);
        byte[] png = ImageCompare.encodePng(wide);
        IOException tooWide = assertThrows(IOException.class, () -> ImageCompare.decodePng(png));
        assertTrue(tooWide.getMessage().contains("at most 4096"), tooWide.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ImageCompare.compare(wide, wide, List.of(), 0, false));
        byte[] again = ImageCompare.encodePng(vector(BufferedImage.TYPE_INT_ARGB));
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        copy.write(again);
        assertArrayEquals(again, copy.toByteArray());
    }

    @Test
    void aRectangleIsReadFromItsJsonShape() {
        assertEquals(new ImageCompare.Rect(1, 2, 3, 4), ImageCompare.Rect.of(java.util.Map.of("x", 1L, "y", 2L, "w",
                3L, "h", 4L)));
        assertNull(ImageCompare.Rect.of(java.util.Map.of("x", 1L)));
        assertNull(ImageCompare.Rect.of(java.util.Map.of("x", 1L, "y", 2L, "w", -3L, "h", 4L)));
        assertThrows(IllegalArgumentException.class, () -> new ImageCompare.Rect(0, 0, -1, 1));
    }

    /**
     * Phase 7 security fix, S143 (P2 11.3: no pixel data passes through {@code java.io.tmpdir}): the PNG encoding and
     * decoding never use ImageIO's file cache. The cache directory is pointed at a directory that is then removed, so
     * any use of the file cache fails ({@code ImageIO.write} and {@code ImageIO.createImageInputStream} did, throwing
     * "Not a directory!" or "Can't create cache file!"), while the memory-cached streams encode and decode as before
     * and nothing is created where the cache would be. The bytes are those {@code ImageIO.write} gives.
     */
    @Test
    void aPngIsEncodedAndDecodedWithoutImageIoFileCache() throws Exception {
        java.nio.file.Path parent = java.nio.file.Files.createTempDirectory("s143-");
        java.nio.file.Path cache = java.nio.file.Files.createDirectory(parent.resolve("cache"));
        boolean useCache = javax.imageio.ImageIO.getUseCache();
        java.io.File cacheDir = javax.imageio.ImageIO.getCacheDirectory();
        BufferedImage image = vector(BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream reference = new ByteArrayOutputStream();
        javax.imageio.ImageIO.setUseCache(false);
        assertTrue(javax.imageio.ImageIO.write(image, "png", reference));
        try {
            javax.imageio.ImageIO.setUseCache(true);
            javax.imageio.ImageIO.setCacheDirectory(cache.toFile());
            java.nio.file.Files.delete(cache);
            byte[] png = ImageCompare.encodePng(image);
            assertArrayEquals(reference.toByteArray(), png, "the bytes ImageIO.write gives");
            assertEquals(VECTOR_HASH, ImageCompare.rasterHash(ImageCompare.decodePng(png)));
            try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(parent)) {
                assertEquals(List.of(), entries.toList(), "nothing was created where the file cache would be");
            }
        } finally {
            javax.imageio.ImageIO.setCacheDirectory(cacheDir);
            javax.imageio.ImageIO.setUseCache(useCache);
            java.nio.file.Files.deleteIfExists(cache);
            java.nio.file.Files.deleteIfExists(parent);
        }
    }
}
