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
package ai.skillroy.testjava.cdp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CDP path's private trace directory (P4 D59) through the controller's SecureFiles (phase6-contracts.md 9.3, D138): owner-only on the POSIX test host, and
 * refused, nothing created, on a file system with neither POSIX permissions nor ACLs (a zip file system).
 */
class CdpTraceDirectoryTest {

    @TempDir
    Path tmp;

    @Test
    void itIsOwnerOnlyOnThisHost() throws IOException {
        Path made = CdpWebPath.createTraceDir(tmp);
        assertEquals(tmp.toAbsolutePath().normalize(), made.toAbsolutePath().getParent());
        assertTrue(made.getFileName().toString().startsWith("skillroy-cdp-trace-"), made.toString());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Files.isDirectory(made) ? "rwx------" : "rw-------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(made)));
        }
    }

    @Test
    void aFileSystemWithNeitherViewIsRefusedAndNothingIsCreated() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(tmp.resolve("none.zip"), Map.of("create", "true"))) {
            Path root = zip.getPath("/");
            IOException refused = assertThrows(IOException.class, () -> CdpWebPath.createTraceDir(root));
            assertTrue(refused.getMessage().contains("neither POSIX permissions nor ACLs"), refused.getMessage());
            try (Stream<Path> entries = Files.list(root)) {
                assertEquals(List.of(), entries.toList());
            }
        }
    }
}
