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

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The image work of {@code web.bitmap} ({@link Images}): scaling down above {@code maxWidth} keeps the aspect ratio
 * and averages the pixels it merges (alpha-weighted), and neither scaling nor PNG encoding starts AWT: run in a JVM of
 * its own (a target without a user interface), they start no {@code AWT-*}, {@code AppKit} or {@code Java2D} thread
 * (P1 A4; the {@code Graphics2D} scaling did).
 */
class ImagesTest {

    @Test
    void anImageNoWiderThanTheLimitIsKeptAndAWiderOneIsShrunkKeepingItsAspect() {
        BufferedImage small = image(400, 100);
        assertSame(small, Images.fit(small, 4096));
        assertSame(small, Images.fit(small, 400));
        BufferedImage wide = image(5000, 300);
        BufferedImage fitted = Images.fit(wide, 4096);
        assertEquals(4096, fitted.getWidth());
        assertEquals(246, fitted.getHeight(), "300 * 4096 / 5000, rounded");
        assertEquals(0xff2060a0, fitted.getRGB(0, 0), "the left half's colour");
        assertEquals(0xffffffff, fitted.getRGB(4095, 245), "the right half's colour");
        BufferedImage two = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        two.setRGB(0, 0, 0xff000000);
        two.setRGB(1, 0, 0xffffffff);
        assertEquals(0xff808080, Images.shrink(two, 1, 1).getRGB(0, 0), "merged pixels are averaged");
        BufferedImage clear = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        clear.setRGB(0, 0, 0x00000000);
        clear.setRGB(1, 0, 0xffff0000);
        assertEquals(0x80ff0000, Images.shrink(clear, 1, 1).getRGB(0, 0), "a transparent pixel adds no colour");
    }

    @Test
    void scalingAndEncodingStartNoAwtThreadInAJvmWithoutAUserInterface() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Child.class.getName());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        InputStream in = p.getInputStream();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            out = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the child JVM ended");
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("scaled 4096x246"), out);
        assertTrue(out.contains("new threads: []"), "no thread started by scaling and encoding: " + out);
    }

    private static BufferedImage image(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int x = 0; x < w; x++) {
            row[x] = x < w / 2 ? 0xff2060a0 : 0xffffffff;
        }
        for (int y = 0; y < h; y++) {
            image.setRGB(0, y, w, 1, row, 0, w);
        }
        return image;
    }

    /** The child JVM: scales and encodes a wide page and prints the threads that started meanwhile. */
    static final class Child {
        private Child() {
        }

        public static void main(String[] args) throws Exception {
            Set<String> before = threads();
            BufferedImage fitted = Images.fit(image(5000, 300), 4096);
            byte[] png = Images.png(fitted);
            List<String> started = new ArrayList<String>(threads());
            started.removeAll(before);
            List<String> awt = new ArrayList<String>();
            for (String name : started) {
                if (name.startsWith("AWT-") || name.startsWith("AppKit") || name.startsWith("Java2D")) {
                    awt.add(name);
                }
            }
            System.out.println("scaled " + fitted.getWidth() + "x" + fitted.getHeight() + " (" + png.length
                    + " PNG bytes); new threads: " + awt);
        }

        private static Set<String> threads() {
            Set<String> out = new TreeSet<String>();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                out.add(t.getName());
            }
            return out;
        }
    }
}
