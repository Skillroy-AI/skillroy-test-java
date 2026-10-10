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

package ai.skillroy.testjava.agent.bootstrap;

import ai.skillroy.testjava.protocol.Volumes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The options file's volume (Phase 6 security fix, pass 2, SE; the rule of P2-L7-F1 applied to P2-L1-F3's check): the
 * bootstrap decides POSIX, ACL or neither by the file's volume, through a private copy of the protocol's
 * {@link Volumes} (the bootstrap depends on nothing), so an options file on a FAT or exFAT volume is refused. The copy's
 * list is held equal to the protocol's; the rule on built values; real FAT and exFAT images where {@code hdiutil}
 * exists (macOS), as {@code JarTrustTest} does. Before the fix such a file was read: macOS mounts both
 * {@code noowners}, so it reads back {@code rwx------}, owned by the current user.
 */
class OptionsFileVolumeTest {

    @TempDir
    Path tmp;

    @Test
    void theCopysListIsTheProtocolsList() {
        assertEquals(new TreeSet<String>(Volumes.WITHOUT_PERMISSIONS),
                new TreeSet<String>(OptionsFileCheck.Volume.WITHOUT_PERMISSIONS),
                "the bootstrap's private copy of the volume rule must name the protocol's types");
        for (String type : Arrays.asList("msdos", "vfat", "FAT32", "exFAT", " vfat ", "apfs", "ext4", "NTFS", null,
                "fuseblk", "zipfs")) {
            assertEquals(Volumes.withoutPermissions(type), OptionsFileCheck.Volume.withoutPermissions(type),
                    String.valueOf(type));
            for (boolean posix : new boolean[] {true, false}) {
                for (boolean acl : new boolean[] {true, false}) {
                    assertEquals(Volumes.protection(type, posix, acl).name(),
                            OptionsFileCheck.Volume.kind(type, posix, acl).name(), type + " " + posix + " " + acl);
                }
            }
        }
    }

    @Test
    void thisHostsTemporaryDirectoryIsJudgedByItsVolume() throws IOException {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path file = Files.write(tmp.resolve("options.properties"), "readOnly=true\n".getBytes("UTF-8"));
        assertEquals(posix ? OptionsFileCheck.Volume.Kind.POSIX : OptionsFileCheck.Volume.Kind.ACL,
                OptionsFileCheck.Volume.kind(file));
        assertEquals(Volumes.protection(file).name(), OptionsFileCheck.Volume.kind(file).name());
        assertEquals("", OptionsFileCheck.Volume.note(file));
        if (posix) {
            BootOptionsTest.writeOwnerOnly(file.toFile(), "token=0123456789abcdef0123\nenabled=true\n");
            assertNull(OptionsFileCheck.problem(file, true), "an owner-only file on this host's volume");
        }
    }

    /**
     * Real disk images, attached with {@code hdiutil}; skipped where it is absent (Linux, Windows, the Java 8 test JVM
     * in the Linux image). An options file there, with or without a token, is refused naming the volume's type, and
     * the token is never echoed.
     */
    @Test
    void anOptionsFileOnAFatOrExfatVolumeIsRefused() throws Exception {
        Path hdiutil = Paths.get("/usr/bin/hdiutil");
        assumeTrue(System.getProperty("os.name", "").startsWith("Mac") && Files.isExecutable(hdiutil),
                "needs hdiutil (macOS)");
        for (String[] volume : new String[][] {{"MS-DOS", "SKFAT"}, {"ExFAT", "SKEXFAT"}}) {
            Path image = tmp.resolve(volume[1] + ".dmg");
            Path mount = Files.createDirectories(tmp.resolve(volume[1].toLowerCase()));
            assumeTrue(run(hdiutil.toString(), "create", "-quiet", "-size", "4m", "-fs", volume[0], "-volname",
                    volume[1], "-layout", "NONE", image.toString()),
                    "the " + volume[0] + " image could not be created");
            assumeTrue(run(hdiutil.toString(), "attach", "-quiet", "-nobrowse", "-mountpoint", mount.toString(),
                    image.toString()), "the " + volume[0] + " image could not be attached");
            try {
                String type = Files.getFileStore(mount).type();
                assertEquals(OptionsFileCheck.Volume.Kind.NONE, OptionsFileCheck.Volume.kind(mount), type);
                final File tokenFile = mount.resolve("skillroy-41.properties").toFile();
                Files.write(tokenFile.toPath(), "token=0123456789abcdef0123\nenabled=true\n"
                        .getBytes(Charset.forName("UTF-8")));
                IOException refused = assertThrows(IOException.class, () -> BootOptions.resolve(BootOptions.ATTACH,
                        BootOptions.parseArgs("t=" + tokenFile), new Properties(),
                        Collections.<String, String>emptyMap()));
                assertTrue(refused.getMessage().contains("is refused: its file system has neither POSIX permissions"
                        + " nor ACLs (its volume is " + type + ", which keeps no permissions)"), refused.getMessage());
                assertFalse(refused.getMessage().contains("0123456789abcdef0123"), "the token is never echoed");
                Path plain = Files.write(mount.resolve("config.properties"), "readOnly=true\n".getBytes("UTF-8"));
                assertTrue(OptionsFileCheck.problem(plain, false).contains("(its volume is " + type),
                        "an options file without a token is refused there too: anybody may have written it");
            } finally {
                run(hdiutil.toString(), "detach", "-quiet", "-force", mount.toString());
            }
        }
    }

    private static boolean run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(new File("/dev/null"))
                .start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }
}
