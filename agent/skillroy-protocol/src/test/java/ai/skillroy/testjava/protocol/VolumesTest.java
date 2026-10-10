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

package ai.skillroy.testjava.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix P2-L7-F1 (pass 2): what a volume lets a process restrict is decided per volume. The rule on built
 * values (the types Linux, macOS and Windows give FAT and exFAT volumes are neither, whatever views they report), and on
 * this host's temporary directory and a zip file system. Real FAT and exFAT volumes: {@code PermissionlessVolumeTest}
 * of the controller and the agent core (macOS).
 */
class VolumesTest {

    @TempDir
    Path tmp;

    @Test
    void aFatOrExfatVolumeIsNeitherWhateverViewsItReports() {
        for (String type : Arrays.asList("msdos", "vfat", "fat", "FAT", "fat12", "fat16", "FAT32", "exfat", "exFAT",
                " vfat ", "umsdos")) {
            assertEquals(Volumes.Protection.NONE, Volumes.protection(type, true, false), type);
            assertEquals(Volumes.Protection.NONE, Volumes.protection(type, false, true), type);
            assertTrue(Volumes.withoutPermissions(type), type);
        }
        assertEquals(Volumes.Protection.POSIX, Volumes.protection("apfs", true, false));
        assertEquals(Volumes.Protection.POSIX, Volumes.protection("ext4", true, false));
        assertEquals(Volumes.Protection.ACL, Volumes.protection("NTFS", false, true));
        assertEquals(Volumes.Protection.NONE, Volumes.protection("zipfs", false, false));
        assertEquals(Volumes.Protection.POSIX, Volumes.protection(null, true, true), "POSIX first");
        assertFalse(Volumes.withoutPermissions(null));
        assertFalse(Volumes.withoutPermissions("fuseblk"), "a FUSE volume is left to its views and the read-back");
    }

    @Test
    void thisHostsTemporaryDirectoryAndAPathNotYetCreatedAreJudgedByTheirVolume() {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Volumes.Protection expected = posix ? Volumes.Protection.POSIX : Volumes.Protection.ACL;
        assertEquals(expected, Volumes.protection(tmp));
        assertEquals(expected, Volumes.protection(tmp.resolve("not").resolve("yet")), "the nearest existing ancestor");
        assertNotNull(Volumes.type(tmp));
        assertEquals("", Volumes.note(tmp));
    }

    @Test
    void aZipFileSystemIsNeither() throws Exception {
        URI uri = URI.create("jar:" + tmp.resolve("v.zip").toUri());
        FileSystem zip = FileSystems.newFileSystem(uri, Collections.singletonMap("create", "true"));
        try {
            assertEquals(Volumes.Protection.NONE, Volumes.protection(zip.getPath("/")));
            assertEquals(Volumes.Protection.NONE, Volumes.protection(zip.getPath("/a/b")));
        } finally {
            zip.close();
        }
    }
}
