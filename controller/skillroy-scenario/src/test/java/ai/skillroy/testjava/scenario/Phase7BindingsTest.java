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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.image.ImageCompare;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 13.1 item 5, {@code BindingsFileTest} and {@code BinderTest}'s Phase 7 parts (8.7, decisions
 * D329, D355): version 2 read and written with {@code images} ({@code carets}, {@code focus} with {@code owner},
 * {@code ownerRect} and {@code active}, the nested {@code rendering}, the derived {@code file}), version 1 kept for a
 * file without images, the reader's refusals; a baseline's PNG read from its derived path only, never through a link
 * or from outside the environment's directory, and only when it is the entry's raster; a bind that writes the file
 * (a live write, a re-resolve) carries the existing {@code images} forward.
 */
class Phase7BindingsTest {

    private static final String KEY = "0123456789abcdef";

    @TempDir
    Path tmp;

    static BindingsFile.ImageEntry entry(BufferedImage image, String label) {
        return new BindingsFile.ImageEntry(ImageCompare.rasterHash(image), image.getWidth(), image.getHeight(),
                List.of(new ImageCompare.Rect(0, 0, 10, 10)), List.of(new ImageCompare.Rect(12, 40, 1, 24)),
                new BindingsFile.Focus(Map.of("id", "entry"), new ImageCompare.Rect(0, 30, 200, 24), true),
                BindingsFile.ImageEntry.fileName("t", label, KEY), Map.of("jdk", Map.of("vendor", "v", "version",
                "25.0.1", "specification", "25"), "os", Map.of("name", "Linux", "version", "6", "arch", "aarch64"),
                "lookAndFeel", "Metal", "antialiasing", Map.of("desktopHints", List.of(), "property", "lcd"),
                "screenScale", 1.0, "probeHash", "sha256:p"), Map.of("size", Map.of("w", 1024L, "h", 768L),
                "insets", Map.of("top", 0L, "left", 0L)), null);
    }

    static BindingsFile file(Map<String, Map<String, BindingsFile.ImageEntry>> images) {
        return new BindingsFile(BindingsFile.VERSION, "t", "seed-a", "toy", "1.0.0", "sha256:plan", "sha256:resolve",
                new EnvFingerprint(EnvFingerprint.VERSION, "sha256:env", Map.of("skillroy.fingerprint", "1")),
                Map.of(), Map.of(), images);
    }

    @Test
    void version2HoldsImagesAndVersion1StaysForAFileWithout() throws Exception {
        BufferedImage image = Phase7ExecutorTest.image(0xABCDEF, 1, 1);
        BindingsFile with = file(Map.of("pad", Map.of(KEY, entry(image, "pad"))));
        assertEquals(2, with.bindingsVersion());
        String text = with.toJsonText();
        assertTrue(text.contains("\"bindingsVersion\": 2"), text);
        BindingsFile read = BindingsFile.parse(text);
        assertEquals(with, read, "the images round-trip");
        assertEquals(text, read.toJsonText(), "byte-identical");
        BindingsFile.ImageEntry entry = read.images().get("pad").get(KEY);
        assertEquals(new ImageCompare.Rect(12, 40, 1, 24), entry.carets().get(0));
        assertEquals(new BindingsFile.Focus(Map.of("id", "entry"), new ImageCompare.Rect(0, 30, 200, 24), true),
                entry.focus());
        assertEquals("aarch64", ((Map<?, ?>) entry.rendering().get("os")).get("arch"), "nested, for the reviewer");
        assertEquals("t.images/pad." + KEY + ".png", entry.file());
        BindingsFile without = file(Map.of());
        assertEquals(1, without.bindingsVersion(), "a file without images is still written as version 1");
        assertTrue(without.toJsonText().contains("\"bindingsVersion\": 1"));
        assertFalse(without.toJsonText().contains("images"));
        assertEquals(1, new BindingsFile(BindingsFile.VERSION, "t", "seed-a", "toy", "1", null, "r", without.env(),
                Map.of(), Map.of()).bindingsVersion(), "whatever the version given");
    }

    @Test
    void theReaderRefusesWhatAFileMayNotHold() throws Exception {
        BufferedImage image = Phase7ExecutorTest.image(0xABCDEF, -1, -1);
        String text = file(Map.of("pad", Map.of(KEY, entry(image, "pad")))).toJsonText();
        assertTrue(refusal(text.replace("\"bindingsVersion\": 2", "\"bindingsVersion\": 1")).contains("'images' needs"
                + " bindingsVersion 2"));
        assertTrue(refusal(text.replace("\"bindingsVersion\": 2", "\"bindingsVersion\": 3")).contains("not supported"));
        assertTrue(refusal(text.replace(KEY, "a/b")).contains("render key"));
        assertTrue(refusal(text.replace("\"pad\": {", "\"../pad\": {")).contains("not a label"));
        assertTrue(refusal(text.replace(ImageCompare.rasterHash(image), "sha256:zz")).contains("'sha256'"));
        assertTrue(refusal(text.replace("\"active\": true", "\"active\": true, \"extra\": 1")).contains("unknown key"));
    }

    private static String refusal(String text) {
        return assertThrows(BindingsFormatException.class, () -> BindingsFile.parse(text)).getMessage();
    }

    @Test
    void aBaselineIsReadFromItsDerivedPathOnlyAndMustBeItsRaster() throws Exception {
        BufferedImage image = Phase7ExecutorTest.image(0x123456, 3, 3);
        Path envDir = tmp.resolve("bindings/seed-a");
        Path png = envDir.resolve("t.images/pad." + KEY + ".png");
        Files.createDirectories(png.getParent());
        Files.write(png, ImageCompare.encodePng(image));
        BindingsFile.ImageEntry entry = entry(image, "pad").withFile("../../../etc/elsewhere.png");
        ImageBaselines baselines = new ImageBaselines("t", envDir, file(Map.of("pad", Map.of(KEY, entry))));
        assertEquals(ImageCompare.rasterHash(image), ImageCompare.rasterHash(baselines.image("pad", KEY)),
                "the path is derived from the label and the key, never the entry's file");
        Files.write(png, ImageCompare.encodePng(Phase7ExecutorTest.image(0x123456, 4, 4)));
        IOException other = assertThrows(IOException.class, () -> baselines.image("pad", KEY));
        assertTrue(other.getMessage().contains("not the entry's raster"), other.getMessage());
        Files.delete(png);
        Path real = tmp.resolve("real.png");
        Files.write(real, ImageCompare.encodePng(image));
        try {
            Files.createSymbolicLink(png, real);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        IOException link = assertThrows(IOException.class, () -> baselines.image("pad", KEY));
        assertTrue(link.getMessage().contains("link"), link.getMessage());
        assertThrows(IOException.class, () -> baselines.pngPath("../x", KEY));
        assertThrows(IOException.class, () -> new ImageBaselines("t", null, null).image("pad", KEY));
    }

    @Test
    void aWritingBindCarriesTheImagesForward() throws Exception {
        ToyResolution toy = new ToyResolution();
        ToyPack pack = toy.pack();
        FakeAgentConnection agent = new FakeAgentConnection();
        toy.install(agent);
        agent.capabilities("toy.item");
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\nresolve:\n  item: { query: Item, args: { deposit:"
                + " { between: [\"2.00\", \"50.00\"] } } }\nsteps:\n  - expectImage: { label: pad }\n")
                .getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        Path root = tmp.resolve("bindings");
        BindOptions live = TestCredentials.bind().withEnvId("seed-a").withBindingsRoot(root).withMode(BindMode.LIVE)
                .withWriteBindings(true);
        BindOutcome first = Binder.bind(plan, pack, agent, live, BindListener.NONE);
        BindingsFile written = BindingsFile.read(first.written());
        BufferedImage image = Phase7ExecutorTest.image(0x0, -1, -1);
        Files.writeString(first.written(), written.withImages(Map.of("pad", Map.of(KEY, entry(image, "pad"))))
                .toJsonText());
        BindOutcome again = Binder.bind(plan, pack, agent, live, BindListener.NONE);
        assertEquals(List.of(KEY), List.copyOf(BindingsFile.read(BindingsFile.locate(root, "seed-a", "t")).images()
                .get("pad").keySet()), "a live write keeps the baselines");
        assertEquals(1, again.file().images().size());
        BindStep reResolve = Binder.bindStepwise(plan, pack, agent, live.withMode(BindMode.RE_RESOLVE)
                .withApprovedBy("job"), BindListener.NONE);
        assertEquals(1, ((BindStep.Done) reResolve).outcome().file().images().size(), "a re-resolve keeps them too");
        BindOutcome replay = Binder.bind(plan, pack, agent, live.withMode(BindMode.REPLAY).withWriteBindings(false),
                BindListener.NONE);
        assertEquals(1, replay.file().images().size(), "replay reads them");
    }
}
