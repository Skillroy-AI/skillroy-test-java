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

package ai.skillroy.testjava.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix P2-L7-F1 (pass 2) on real volumes: a FAT and an exFAT disk image, attached with {@code hdiutil} (macOS
 * mounts both {@code noowners}, so that every file reads back {@code rwx------} and every user is its owner), are
 * refused by every member of the controller's {@code SecureFiles} before anything is created, in the "neither" wording
 * naming the volume's type; before the fix every member accepted both. Skipped where {@code hdiutil} is absent (Linux,
 * Windows); the rule itself is tested on built values everywhere ({@code VolumesTest}, {@link #theReadBackIsExact}).
 */
class PermissionlessVolumeTest {

    private static final Path HDIUTIL = Path.of("/usr/bin/hdiutil");

    @TempDir
    static Path tmp;

    private static final List<Path> MOUNTED = new ArrayList<>();
    private static Path fat;
    private static Path exfat;

    @BeforeAll
    static void attach() throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Mac") || !Files.isExecutable(HDIUTIL)) {
            return;
        }
        fat = attach("MS-DOS", "SKFAT");
        exfat = attach("ExFAT", "SKEXFAT");
    }

    @AfterAll
    static void detach() {
        for (Path mount : MOUNTED) {
            try {
                hdiutil("detach", "-quiet", "-force", mount.toString());
            } catch (Exception e) {
                // The volume goes with the machine's next restart; nothing of the test is on it.
            }
        }
    }

    private static Path attach(String fs, String name) throws Exception {
        Path image = tmp.resolve(name + ".dmg");
        Path mount = Files.createDirectories(tmp.resolve(name.toLowerCase()));
        if (!hdiutil("create", "-quiet", "-size", "4m", "-fs", fs, "-volname", name, "-layout", "NONE",
                image.toString())) {
            return null;
        }
        if (!hdiutil("attach", "-quiet", "-nobrowse", "-mountpoint", mount.toString(), image.toString())) {
            return null;
        }
        MOUNTED.add(mount);
        return mount;
    }

    private static boolean hdiutil(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(HDIUTIL.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(
                ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    private static List<Path> volumes() {
        Assumptions.assumeTrue(fat != null && exfat != null, "needs hdiutil (macOS) to attach a FAT and an exFAT image");
        return List.of(fat, exfat);
    }

    private static void assertRefused(IOException refused, String type) {
        assertTrue(refused.getMessage().contains("neither POSIX permissions nor ACLs (its volume is " + type
                + ", which keeps no permissions)"), refused.getMessage());
    }

    private static void assertEmpty(Path volume) throws IOException {
        try (Stream<Path> entries = Files.list(volume)) {
            assertEquals(List.of(), entries.filter(p -> !p.getFileName().toString().startsWith(".")).toList(),
                    "nothing was created");
        }
    }

    @Test
    void everyMemberRefusesAFatOrExfatVolumeBeforeCreatingAnything() throws IOException {
        for (Path volume : volumes()) {
            String type = Files.getFileStore(volume).type();
            assertTrue(type.equals("msdos") || type.equals("exfat"), type);
            assertEquals(SecureFiles.Protection.NONE, SecureFiles.protection(volume));
            assertRefused(assertThrows(IOException.class, () -> SecureFiles.createOwnerOnlyTempDirectory(volume,
                    "skillroy-author-")), type);
            assertRefused(assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(
                    volume.resolve("run"), "the test discovery directory")), type);
            assertRefused(assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(
                    volume.resolve("discovery"))), type);
            IOException file = assertThrows(IOException.class, () -> SecureFiles.writeOwnerOnly(
                    volume.resolve("skillroy-mcp-0.json"), "{}".getBytes()));
            assertTrue(file.getMessage().contains("(its volume is " + type + ", which keeps no permissions)"),
                    file.getMessage());
            assertEmpty(volume);
            String reason = SecureFiles.untrustedDirectoryReason(volume);
            assertNotNull(reason, "a directory on such a volume is never trusted");
            assertTrue(reason.contains("neither POSIX permissions nor ACLs"), reason);
        }
    }

    /**
     * The ancestor rule of P2-L1-F3 takes the per-volume decision of P2-L7-F1 too (merged with both pass 2 fixes): a
     * directory whose parent is on a FAT or exFAT volume, which reads {@code rwx------} on macOS and so passed the
     * POSIX test of an ancestor, is refused because anybody with the volume may rename the parent's entries.
     */
    @Test
    void anAncestorOnAFatOrExfatVolumeIsRefused() throws IOException {
        for (Path volume : volumes()) {
            String type = Files.getFileStore(volume).type();
            Path sub = Files.createDirectory(volume.resolve("ancestor-" + SecureFiles.randomHex(4)));
            try {
                String reason = SecureFiles.untrustedAncestorReason(sub);
                assertEquals("the directory " + volume + " above it is on a volume with neither POSIX permissions nor"
                        + " ACLs (its volume is " + type + ", which keeps no permissions)", reason);
            } finally {
                Files.deleteIfExists(sub);
            }
        }
    }

    /** The POSIX read-back's rule on built values: exactly what was asked, or a refusal that says what was read. */
    @Test
    void theReadBackIsExact() {
        assertNull(SecureFiles.readBackProblem(PosixFilePermissions.fromString("rw-------"),
                PosixFilePermissions.fromString("rw-------")));
        assertEquals("it reads back rwx------ where rw------- was asked, so its file system does not keep permissions",
                SecureFiles.readBackProblem(PosixFilePermissions.fromString("rw-------"),
                        PosixFilePermissions.fromString("rwx------")));
        assertEquals("it reads back rwxr-xr-x where rwx------ was asked, so its file system does not keep permissions",
                SecureFiles.readBackProblem(PosixFilePermissions.fromString("rwx------"),
                        PosixFilePermissions.fromString("rwxr-xr-x")));
        assertTrue(SecureFiles.readBackProblem(PosixFilePermissions.fromString("rwx------"), null)
                .startsWith("it reads back nothing"));
    }
}
