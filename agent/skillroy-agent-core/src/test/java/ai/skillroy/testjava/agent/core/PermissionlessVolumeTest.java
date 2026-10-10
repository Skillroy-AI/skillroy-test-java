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

package ai.skillroy.testjava.agent.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix P2-L7-F1 (pass 2) on real volumes: a FAT and an exFAT disk image, attached with {@code hdiutil} (macOS
 * mounts both {@code noowners}: every file reads back {@code rwx------} and every user is its owner), where the agent
 * writes neither its discovery directory nor a token file; before the fix it wrote both. Skipped where {@code hdiutil}
 * is absent (Linux, Windows, the Java 8 test JVM in the Linux image); the read-back rule is tested on built values
 * everywhere.
 */
class PermissionlessVolumeTest {

    private static final Path HDIUTIL = Paths.get("/usr/bin/hdiutil");

    @TempDir
    static Path tmp;

    private static final List<Path> MOUNTED = new ArrayList<Path>();
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
        List<String> command = new ArrayList<String>();
        command.add(HDIUTIL.toString());
        command.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(new File("/dev/null")).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    private static List<Path> volumes() {
        Assumptions.assumeTrue(fat != null && exfat != null, "needs hdiutil (macOS) to attach a FAT and an exFAT image");
        return Arrays.asList(fat, exfat);
    }

    private static List<String> visible(Path volume) throws IOException {
        List<String> names = new ArrayList<String>();
        DirectoryStream<Path> entries = Files.newDirectoryStream(volume);
        try {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().startsWith(".")) {
                    names.add(entry.getFileName().toString());
                }
            }
        } finally {
            entries.close();
        }
        return names;
    }

    @Test
    void theAgentWritesNoDiscoveryDirectoryAndNoTokenThere() throws Exception {
        for (final Path volume : volumes()) {
            String type = Files.getFileStore(volume).type();
            assertEquals(SecureFiles.Protection.NONE, SecureFiles.protection(volume), type);
            IOException dir = assertThrows(IOException.class, () -> SecureFiles.prepareDirectory(
                    volume.resolve("skillroy-desk")));
            assertTrue(dir.getMessage().contains("neither POSIX permissions nor ACLs (its volume is " + type
                    + ", which keeps no permissions)"), dir.getMessage());
            IOException token = assertThrows(IOException.class, () -> SecureFiles.writeOwnerOnly(
                    volume.resolve("skillroy-41-1700000000000.token"), "tok".getBytes("UTF-8")));
            assertTrue(token.getMessage().contains("neither POSIX permissions nor ACLs"), token.getMessage());
            assertEquals(new ArrayList<String>(), visible(volume), "nothing was created on " + type);
        }
    }

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
    }
}
