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

package ai.skillroy.testjava.cli;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code skillroy author} on Windows, tested on any platform (phase6-contracts.md 9.2, D137): the planner's executable
 * found as {@code claude} plus a {@code PATHEXT} extension through a fake {@code PATH} and {@code PATHEXT}; the plain
 * {@code cmd.exe /c} form for a batch launcher and its refusals; the configuration file owner-only through
 * {@code SecureFiles} (9.3). Running them on a Windows host waits for the host (9.5).
 */
class AuthorCommandWindowsTest {

    @TempDir
    Path tmp;

    private Path file(Path dir, String name, boolean executable) throws IOException {
        Files.createDirectories(dir);
        Path f = Files.writeString(dir.resolve(name), "x");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(executable ? "rwx------" : "rw-------"));
        }
        return f;
    }

    /** The same file: a case-insensitive file system (macOS, Windows) answers with the name that was asked for. */
    private static void assertSame(Path expected, Path found, String message) throws IOException {
        assertTrue(found != null && found.isAbsolute(), message + ": " + found);
        assertTrue(Files.isSameFile(expected, found), message + ": " + found);
        assertTrue(found.getFileName().toString().equalsIgnoreCase(expected.getFileName().toString()),
                message + ": " + found);
    }

    @Test
    void onWindowsClaudeIsFoundWithAPathextExtensionWhateverItsCase() throws IOException {
        Path first = tmp.resolve("first");
        Path npm = tmp.resolve("npm");
        Files.createDirectories(first);
        // npm installs an extensionless shell shim beside claude.cmd: the extension comes first.
        file(npm, "claude", true);
        Path cmd = file(npm, "claude.cmd", false);
        String path = first + ";" + npm;
        assertSame(cmd, AuthorCommand.findOnPath(Map.of("Path", path, "PATHEXT", ".COM;.EXE;.BAT;.CMD"),
                "Windows 11"), "PATHEXT's .CMD matches claude.cmd; Path read as PATH");
        Path exe = file(first, "claude.exe", false);
        assertSame(exe, AuthorCommand.findOnPath(Map.of("PATH", path, "PATHEXT", ".exe;.cmd"),
                "Windows Server 2025"), "the first directory that has one wins");
        assertEquals(npm.resolve("claude").toAbsolutePath(), AuthorCommand.findOnPath(Map.of("PATH", path,
                "PATHEXT", ".BAT"), "Windows 11"), "an extension PATHEXT does not name is not a candidate");
        Path bare = tmp.resolve("bare");
        Path onlyBare = file(bare, "claude", false);
        assertEquals(onlyBare.toAbsolutePath(), AuthorCommand.findOnPath(Map.of("PATH", bare.toString(),
                "PATHEXT", ".EXE"), "Windows 11"), "a file named exactly claude is still accepted");
        assertNull(AuthorCommand.findOnPath(Map.of("PATHEXT", ".EXE"), "Windows 11"), "no PATH, nothing");
    }

    @Test
    void elsewhereOnlyAnExecutableNamedExactlyClaudeCounts() throws IOException {
        Path dir = tmp.resolve("bin");
        file(dir, "claude.cmd", true);
        assertNull(AuthorCommand.findOnPath(Map.of("PATH", dir.toString(), "PATHEXT", ".CMD"), "Linux"),
                "PATHEXT means nothing off Windows");
        Path notExecutable = file(dir, "claude", false);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertNull(AuthorCommand.findOnPath(Map.of("PATH", dir.toString()), "Linux"));
            Files.setPosixFilePermissions(notExecutable, PosixFilePermissions.fromString("rwx------"));
        }
        assertEquals(notExecutable.toAbsolutePath(), AuthorCommand.findOnPath(Map.of("PATH", dir.toString()),
                "Linux"));
    }

    @Test
    void aBatchLauncherIsStartedThroughCmdExeInThePlainForm() throws UsageException {
        List<Object> serverArgs = List.of("mcp", "--mutating", "--inference", "none", "--env", "dev");
        Map<String, Object> bat = AuthorCommand.serverCommand("C:\\skillroy\\bin\\skillroy.bat", serverArgs);
        assertEquals("cmd.exe", bat.get("command"));
        assertEquals(List.of("/c", "C:\\skillroy\\bin\\skillroy.bat", "mcp", "--mutating", "--inference", "none",
                "--env", "dev"), bat.get("args"));
        Map<String, Object> cmd = AuthorCommand.serverCommand("D:\\tools\\SKILLROY.CMD", serverArgs);
        assertEquals("cmd.exe", cmd.get("command"), "the extension is compared without regard to case");
        Map<String, Object> sh = AuthorCommand.serverCommand("/opt/skillroy/bin/skillroy", serverArgs);
        assertEquals("/opt/skillroy/bin/skillroy", sh.get("command"), "the shell launcher is named as it is");
        assertEquals(serverArgs, sh.get("args"));
    }

    @Test
    void aPathCmdExeWouldSplitOrExpandIsRefusedWithTheRemedy() {
        List<Object> serverArgs = List.of("mcp", "--mutating");
        for (String launcher : List.of("C:\\Program Files\\skillroy\\bin\\skillroy.bat",
                "C:\\100%\\skillroy\\bin\\skillroy.bat", "C:\\a&b\\bin\\skillroy.cmd", "C:\\a^b\\bin\\skillroy.bat")) {
            UsageException refused = assertThrows(UsageException.class,
                    () -> AuthorCommand.serverCommand(launcher, serverArgs));
            assertTrue(refused.getMessage().contains("move the installation or the pack to a path without spaces or"
                    + " special characters"), refused.getMessage());
        }
        UsageException pack = assertThrows(UsageException.class, () -> AuthorCommand.serverCommand(
                "C:\\skillroy\\bin\\skillroy.bat", List.of("mcp", "--pack", "C:\\My Packs\\lending.jar")));
        assertTrue(pack.getMessage().contains("a space"), pack.getMessage());
        UsageException percent = assertThrows(UsageException.class, () -> AuthorCommand.serverCommand(
                "C:\\skillroy\\bin\\skillroy.bat", List.of("mcp", "--env", "%USERNAME%")));
        assertTrue(percent.getMessage().contains("'%'"), percent.getMessage());
    }

    @Test
    void theConfigurationIsWrittenOwnerOnlyAndRefusedWhereNothingCanBeRestricted() throws IOException {
        Path config = AuthorCommand.writeConfig(tmp, "{\"mcpServers\":{}}");
        assertTrue(config.getFileName().toString().matches("skillroy-mcp-[0-9a-f]{16}\\.json"), config.toString());
        assertEquals("{\"mcpServers\":{}}", Files.readString(config));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)));
        }
        try (FileSystem zip = FileSystems.newFileSystem(tmp.resolve("none.zip"), Map.of("create", "true"))) {
            Path root = zip.getPath("/");
            IOException refused = assertThrows(IOException.class, () -> AuthorCommand.writeConfig(root, "{}"));
            assertTrue(refused.getMessage().contains("neither POSIX permissions nor ACLs"), refused.getMessage());
            try (Stream<Path> entries = Files.list(root)) {
                assertEquals(List.of(), entries.toList());
            }
        }
    }
}
