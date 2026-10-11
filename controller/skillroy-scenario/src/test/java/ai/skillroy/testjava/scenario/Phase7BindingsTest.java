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
import java.util.ArrayList;
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

    // ------------------------------------------------------------------------------------ the security fix round

    /** The toy resolution's pack (a fingerprint of {@code toy.seed}) on a fake agent that answers the image reads. */
    private final ToyResolution toy = new ToyResolution();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private Map<String, Object> capture;

    private ToyPack imagePack() throws Exception {
        toy.install(agent);
        agent.capabilities("toy.item", "swing.capture.paint");
        agent.on("swing.rendering", params -> Map.of("renderKey", KEY));
        agent.on("swing.capture", params -> capture);
        capture = Phase7ExecutorTest.capture(Phase7ExecutorTest.image(0xFFFFFF, -1, -1), List.of());
        return toy.pack();
    }

    private RunResult run(ToyPack pack, Plan plan, BindMode mode, Approver approver, String out) {
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withOutputDir(tmp.resolve(out)).withBind(TestCredentials.bind().withMode(mode).withEnvId("seed-a")
                        .withBindingsRoot(tmp.resolve("bindings")).withApprover(approver));
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    private static Plan compile(ToyPack pack, String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    /**
     * Phase 7 security fix, S115 (phase7-contracts.md 8.7, D362 as amended): an images-only plan of a committed
     * scenario's id, approved without an approver and its candidate rejected, leaves the committed file byte-identical
     * (its resolutions, oracles and baselines kept), and an unused id gets no file. The old code wrote the file at bind
     * time with no approver asked: the committed resolutions were gone.
     */
    @Test
    void anImagesOnlyApproveWithoutAnApproverLeavesACommittedFileUntouched() throws Exception {
        ToyPack pack = imagePack();
        Plan committed = compile(pack, "resolve:\n  item: { query: Item, args: { deposit: { between: [\"2.00\","
                + " \"50.00\"] } } }\nsteps:\n  - expectImage: { label: pad }\n");
        assertEquals(Status.PASS, run(pack, committed, BindMode.APPROVE, Approver.APPROVE_ALL, "committed").status());
        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        String before = Files.readString(file);
        assertTrue(before.contains("\"item\"") && before.contains("\"pad\""), before);

        Plan imagesOnly = compile(pack, "steps:\n  - expectImage: { label: other }\n");
        RunResult rejected = run(pack, imagesOnly, BindMode.APPROVE, Approver.REJECT_ALL, "client");
        assertEquals(FailureKind.REBIND_NEEDED, rejected.failureKind(), rejected.message());
        assertEquals(before, Files.readString(file), "the committed file is byte-identical");
        Plan unused = PlanCompiler.compile("scenario: u\npack: toy\nsteps:\n  - expectImage: { label: pad }\n"
                .getBytes(StandardCharsets.UTF_8), "u.yaml", pack);
        run(pack, unused, BindMode.APPROVE, Approver.REJECT_ALL, "unused");
        assertFalse(Files.exists(BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "u")), "no file planted");
    }

    /**
     * Phase 7 security fix, S115: after an environment change, an approve without an approver writes nothing, so
     * replay stays REBIND_NEEDED(FINGERPRINT) (the old code rewrote the file's environment and replay passed); with
     * {@code --yes} ({@code APPROVE_ALL}) and a baseline that still holds, the operator's refresh writes the new
     * environment and replay passes (the case that must keep working).
     */
    @Test
    void anEnvironmentChangeIsWrittenOnlyWhenTheApproverApprovesIt() throws Exception {
        ToyPack pack = imagePack();
        Plan plan = compile(pack, "steps:\n  - expectImage: { label: pad }\n");
        assertEquals(Status.PASS, run(pack, plan, BindMode.APPROVE, Approver.APPROVE_ALL, "first").status());
        assertEquals(Status.PASS, run(pack, plan, BindMode.REPLAY, Approver.REJECT_ALL, "replay-1").status());
        toy.seed = "B";
        RunResult changed = run(pack, plan, BindMode.REPLAY, Approver.REJECT_ALL, "replay-2");
        assertEquals(FailureKind.REBIND_NEEDED, changed.failureKind(), changed.message());
        assertEquals("FINGERPRINT", changed.detail().get("reason"), changed.detail().toString());
        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        String before = Files.readString(file);

        RunResult refused = run(pack, plan, BindMode.APPROVE, Approver.REJECT_ALL, "refused");
        assertEquals(Status.ERROR, refused.status(), refused.message());
        assertEquals(FailureKind.RESOLUTION_FAILED, refused.failureKind());
        assertEquals("NOT_APPROVED", refused.detail().get("reason"));
        assertTrue(refused.message().contains("was not rewritten for this run's environment and plan: not approved"),
                refused.message());
        assertEquals(before, Files.readString(file), "nothing written");
        RunResult still = run(pack, plan, BindMode.REPLAY, Approver.REJECT_ALL, "replay-3");
        assertEquals("FINGERPRINT", still.detail().get("reason"), still.message());

        List<Approver.ApprovalRequest> asked = new ArrayList<>();
        Approver yes = request -> {
            asked.add(request);
            return new Approver.Decision(true, "tester", null);
        };
        assertEquals(Status.PASS, run(pack, plan, BindMode.APPROVE, yes, "refresh").status());
        assertEquals(1, asked.size(), "asked once, at the end");
        assertTrue(asked.get(0).resolutions().isEmpty() && asked.get(0).oracles().isEmpty());
        assertTrue(asked.get(0).againstExisting().changes().stream().anyMatch(c -> c.where().contains("toy.seed")),
                String.valueOf(asked.get(0).againstExisting().changes()));
        assertEquals(Status.PASS, run(pack, plan, BindMode.REPLAY, Approver.REJECT_ALL, "replay-4").status());
        assertEquals(Status.PASS, run(pack, plan, BindMode.APPROVE, Approver.REJECT_ALL, "unchanged").status(),
                "nothing to write and nothing to ask when the file would not change");
    }

    /**
     * Phase 7 security fix, S116 (phase7-contracts.md 8.7, P2 8.3 approve step 8): a writing bind over a bindings file
     * it cannot read (a {@code bindingsVersion} this code does not know, a merge conflict left in the JSON) ends
     * ERROR / ENVIRONMENT naming the file, and the file is unchanged; the old code took it for absent and rewrote it
     * without its baselines. A non-writing live run of a plan with images says the same instead of skipping every
     * image as "no baseline".
     */
    @Test
    void aWritingBindNeverReplacesAFileItCannotRead() throws Exception {
        ToyPack pack = imagePack();
        Plan plan = compile(pack, "steps:\n  - expectImage: { label: pad }\n");
        assertEquals(Status.PASS, run(pack, plan, BindMode.APPROVE, Approver.APPROVE_ALL, "first").status());
        Path file = BindingsFile.locate(tmp.resolve("bindings"), "seed-a", "t");
        String good = Files.readString(file);
        for (String broken : List.of(good.replace("\"bindingsVersion\": 2", "\"bindingsVersion\": 3"),
                "<<<<<<< ours\n" + good + "=======\n" + good + ">>>>>>> theirs\n")) {
            Files.writeString(file, broken);
            for (BindMode mode : List.of(BindMode.APPROVE, BindMode.LIVE)) {
                RunResult result = run(pack, plan, mode, Approver.APPROVE_ALL, "broken-" + mode.label());
                assertEquals(Status.ERROR, result.status(), mode.label() + ": " + result.message());
                assertEquals(FailureKind.ENVIRONMENT, result.failureKind(), result.message());
                assertTrue(result.message().contains("the bindings file seed-a/t.bindings.json"), result.message());
                assertEquals(broken, Files.readString(file), mode.label() + ": the file is unchanged");
            }
        }
        BindOptions write = TestCredentials.bind().withEnvId("seed-a").withBindingsRoot(tmp.resolve("bindings"))
                .withMode(BindMode.LIVE).withWriteBindings(true);
        BindException refused = assertThrows(BindException.class, () -> Binder.bind(plan, pack, agent, write,
                BindListener.NONE));
        assertEquals(FailureKind.ENVIRONMENT, refused.kind());
    }

    /**
     * Phase 7 security fix, S140 (phase7-contracts.md 8.7): the writers of the bindings directory follow the reader's
     * rule. A symbolic link at the environment's directory, or at {@code <id>.images}, that the current user owns makes
     * the baseline's and the bindings file's writers refuse, naming it (the old code wrote through it into the link's
     * target), and the reader's refusal names the link (the old message named an empty path for the directory).
     */
    @Test
    void theWritersRefuseALinkInTheBindingsDirectoryAsTheReaderDoes() throws Exception {
        Path root = tmp.resolve("bindings");
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.createDirectories(root);
        Path envDir = root.resolve("seed-a");
        try {
            Files.createSymbolicLink(envDir, elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        BufferedImage image = Phase7ExecutorTest.image(0x123456, 2, 2);
        byte[] png = ImageCompare.encodePng(image);
        ImageBaselines baselines = new ImageBaselines("t", envDir, null);
        baselines.approved("pad", KEY, entry(image, "pad"), png);
        IOException pngWrite = assertThrows(IOException.class, baselines::writeApprovedPngs);
        assertTrue(pngWrite.getMessage().contains("the bindings directory " + envDir.toAbsolutePath().normalize()
                + " is a link"), pngWrite.getMessage());
        IOException jsonWrite = assertThrows(IOException.class, () -> file(Map.of()).write(BindingsFile.locate(root,
                "seed-a", "t")));
        assertTrue(jsonWrite.getMessage().contains("is a link"), jsonWrite.getMessage());
        IOException read = assertThrows(IOException.class, () -> baselines.pngPath("pad", KEY));
        assertTrue(read.getMessage().startsWith("the bindings directory "), read.getMessage());
        try (java.util.stream.Stream<Path> entries = Files.list(elsewhere)) {
            assertEquals(List.of(), entries.toList(), "nothing was written where the link points");
        }

        Files.delete(envDir);
        Files.createDirectories(envDir);
        Path images = envDir.resolve("t.images");
        Files.createSymbolicLink(images, elsewhere);
        IOException below = assertThrows(IOException.class, baselines::writeApprovedPngs);
        assertTrue(below.getMessage().startsWith("t.images is a link"), below.getMessage());
        IOException exported = assertThrows(IOException.class, () -> BindingsFile.imageFile(envDir, "t", "pad", KEY));
        assertTrue(exported.getMessage().startsWith("t.images is a link"), exported.getMessage());
        try (java.util.stream.Stream<Path> entries = Files.list(elsewhere)) {
            assertEquals(List.of(), entries.toList(), "nothing was written where the link points");
        }
    }

    /**
     * Gap 99 (the rule of S140 for the JSON reader): the bindings file of a scenario is read under the link rule of the
     * baselines' reader. A link at the file's position (to a good file elsewhere) and a linked environment directory are
     * refused, naming the link; a writing bind and a live run with images end ERROR / ENVIRONMENT over the link and leave
     * its target unchanged; the reader of a file a person names ({@code diff-bindings}) still reads it.
     */
    @Test
    void theBindingsFileIsNeverReadThroughALink() throws Exception {
        ToyPack pack = imagePack();
        Plan plan = compile(pack, "steps:\n  - expectImage: { label: pad }\n");
        assertEquals(Status.PASS, run(pack, plan, BindMode.APPROVE, Approver.APPROVE_ALL, "first").status());
        Path root = tmp.resolve("bindings");
        Path file = BindingsFile.locate(root, "seed-a", "t");
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Path target = Files.move(file, elsewhere.resolve("t.bindings.json"));
        String good = Files.readString(target);
        try {
            Files.createSymbolicLink(file, target);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        IOException atFile = assertThrows(IOException.class, () -> BindingsFile.read(root, "seed-a", "t"));
        assertTrue(atFile.getMessage().startsWith("t.bindings.json is a link"), atFile.getMessage());
        assertEquals("t", BindingsFile.read(file).scenarioId(), "a file a person names is read as named");
        for (BindMode mode : List.of(BindMode.APPROVE, BindMode.LIVE)) {
            RunResult result = run(pack, plan, mode, Approver.APPROVE_ALL, "linked-" + mode.label());
            assertEquals(Status.ERROR, result.status(), mode.label() + ": " + result.message());
            assertEquals(FailureKind.ENVIRONMENT, result.failureKind(), result.message());
            assertTrue(result.message().contains("is a link"), result.message());
            assertEquals(good, Files.readString(target), mode.label() + ": the link's target is unchanged");
            assertTrue(Files.isSymbolicLink(file), mode.label() + ": the link is left as it was");
        }

        Files.delete(file);
        Path envDir = BindingsFile.envDir(root, "seed-a");
        Path moved = Files.move(envDir, tmp.resolve("seed-a-real"));
        Files.copy(target, moved.resolve("t.bindings.json"));
        Files.createSymbolicLink(envDir, moved);
        IOException atDir = assertThrows(IOException.class, () -> BindingsFile.read(root, "seed-a", "t"));
        assertTrue(atDir.getMessage().startsWith("the bindings directory "), atDir.getMessage());
        assertTrue(atDir.getMessage().contains("is a link"), atDir.getMessage());
    }

    /**
     * Phase 7 security fix, S118, S147 (phase7-contracts.md 8.1, 8.2, 6.6): the bindings file's reader refuses two
     * labels that differ only in case (a label names a file), and its constructor refuses what its reader would, a
     * label with a separator among it, so that a file that cannot be read back is never written.
     */
    @Test
    void labelsAreUniqueIgnoringCaseAndTheConstructorChecksThemAsTheReaderDoes() throws Exception {
        BufferedImage image = Phase7ExecutorTest.image(0xABCDEF, -1, -1);
        String text = file(Map.of("pad", Map.of(KEY, entry(image, "pad")), "pane", Map.of(KEY, entry(image, "pane"))))
                .toJsonText().replace("\"pane\": {", "\"Pad\": {");
        assertTrue(refusal(text).contains("differs from"), refusal(text));
        IllegalArgumentException twin = assertThrows(IllegalArgumentException.class, () -> file(Map.of("pad",
                Map.of(KEY, entry(image, "pad")), "Pad", Map.of(KEY, entry(image, "Pad")))));
        assertTrue(twin.getMessage().contains("only in case"), twin.getMessage());
        IllegalArgumentException separator = assertThrows(IllegalArgumentException.class, () -> file(Map.of(
                "../../x", Map.of(KEY, entry(image, "pad")))));
        assertTrue(separator.getMessage().contains("is not a label"), separator.getMessage());
        assertThrows(IllegalArgumentException.class, () -> file(Map.of("pad", Map.of("a/b", entry(image, "pad")))));
    }
}
