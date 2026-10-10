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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The public members of {@link SecureFiles} (phase6-contracts.md 9.3, D138): exactly five, the owner-only results on
 * the POSIX test host, the role in a refusal, and a file system with neither POSIX permissions nor ACLs (a zip file
 * system) refused with the remedy and nothing created. The ACL branch waits for the Windows host (9.5).
 */
class SecureFilesTest {

    @TempDir
    Path tmp;

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void exactlyTheFiveMembersOf93ArePublic() {
        assertTrue(Modifier.isPublic(SecureFiles.class.getModifiers()));
        assertTrue(Modifier.isFinal(SecureFiles.class.getModifiers()));
        List<String> publicMembers = new ArrayList<>();
        for (Method m : SecureFiles.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                assertTrue(Modifier.isStatic(m.getModifiers()), m.toString());
                List<String> params = new ArrayList<>();
                for (Class<?> p : m.getParameterTypes()) {
                    params.add(p.getSimpleName());
                }
                publicMembers.add(m.getName() + "(" + String.join(",", params) + ")");
            }
        }
        Collections.sort(publicMembers);
        assertEquals(List.of("createOwnerOnlyTempDirectory(Path,String)", "ensureOwnerOnlyDirectory(Path)",
                "ensureOwnerOnlyDirectory(Path,String)", "untrustedDirectoryReason(Path)",
                "writeOwnerOnly(Path,byte[])"), publicMembers, "the token readers and writeOptionsFile stay confined");
    }

    @Test
    void aTemporaryDirectoryIsNewOwnerOnlyAndRandomlyNamed() throws IOException {
        Path a = SecureFiles.createOwnerOnlyTempDirectory(tmp, "skillroy-test-");
        Path b = SecureFiles.createOwnerOnlyTempDirectory(tmp, "skillroy-test-");
        assertNotEquals(a, b);
        for (Path dir : List.of(a, b)) {
            assertEquals(tmp.toAbsolutePath().normalize(), dir.getParent());
            assertTrue(dir.getFileName().toString().matches("skillroy-test-[0-9a-f]{16}"), dir.toString());
            assertTrue(Files.isDirectory(dir));
            assertNull(SecureFiles.untrustedDirectoryReason(dir));
            if (posix()) {
                assertEquals("rwx------", permissions(dir));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> SecureFiles.createOwnerOnlyTempDirectory(tmp, "../x-"));
        IOException missing = assertThrows(IOException.class,
                () -> SecureFiles.createOwnerOnlyTempDirectory(tmp.resolve("missing"), "x-"));
        assertTrue(missing.getMessage().contains("not a directory"), missing.getMessage());
    }

    @Test
    void anOwnerOnlyFileIsCreatedExclusively() throws IOException {
        Path file = tmp.resolve("config.json");
        SecureFiles.writeOwnerOnly(file, "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{}", Files.readString(file));
        if (posix()) {
            assertEquals("rw-------", permissions(file));
        }
        assertThrows(FileAlreadyExistsException.class,
                () -> SecureFiles.writeOwnerOnly(file, "{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{}", Files.readString(file), "an existing file is never overwritten");
    }

    @Test
    void theRefusalNamesTheRoleItWasGiven() throws IOException {
        assumeTrue(posix(), "no POSIX permissions on this file system: the check is skipped here");
        Path shared = Files.createDirectory(tmp.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        IOException role = assertThrows(IOException.class,
                () -> SecureFiles.ensureOwnerOnlyDirectory(shared, "the test discovery directory"));
        assertTrue(role.getMessage().startsWith("the test discovery directory " + shared.toAbsolutePath()),
                role.getMessage());
        assertTrue(role.getMessage().contains("writable by group or others"), role.getMessage());
        assertFalse(role.getMessage().contains("discoveryDir"), "the discovery option is not this role's remedy");
        IOException discovery = assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(shared));
        assertTrue(discovery.getMessage().startsWith("the discovery directory "), discovery.getMessage());
        assertTrue(discovery.getMessage().contains("discoveryDir"), discovery.getMessage());
        assertEquals("rwxrwxrwx", permissions(shared), "an existing directory is never changed");

        Path fresh = SecureFiles.ensureOwnerOnlyDirectory(tmp.resolve("a/b"), "the planner's work directory");
        assertEquals("rwx------", permissions(fresh));
        assertEquals("rwx------", permissions(fresh.getParent()), "missing parents are created owner-only too");
    }

    /**
     * Phase 6 security fix, pass 2, P2-L1-F3: a directory that passes its own check is still refused when a directory
     * above it lets others rename entries (group- or other-writable without the sticky bit), because another user could
     * then move it away and put a directory of their own with planted files under its name; the ancestors of a link's
     * real path count too. With the sticky bit (as {@code /tmp} has) the parent is fine.
     */
    @Test
    void aDirectoryBelowAParentOthersMayWriteToWithoutTheStickyBitIsRefused() throws IOException {
        assumeTrue(posix(), "no POSIX permissions on this file system: the check is skipped here");
        Path checkout = Files.createDirectory(tmp.resolve("checkout"));
        Path discovery = SecureFiles.ensureOwnerOnlyDirectory(checkout.resolve("build").resolve("skillroy"));
        Files.setPosixFilePermissions(checkout, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertNull(SecureFiles.untrustedDirectoryReason(discovery), "the directory itself is fine");
        IOException refused = assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(discovery));
        assertTrue(refused.getMessage().contains("the directory " + checkout.toAbsolutePath().normalize()
                + " above it is writable by group or others without the sticky bit (rwxrwxrwx)"), refused.getMessage());
        assertTrue(refused.getMessage().contains("discoveryDir"), refused.getMessage());
        assertThrows(IOException.class, () -> SecureFiles.writeOptionsFile(discovery,
                Map.of("token", "0123456789abcdef0123456789abcdef")));
        try (Stream<Path> entries = Files.list(discovery)) {
            assertEquals(List.of(), entries.toList(), "no options file was written");
        }

        Files.setAttribute(checkout, "unix:mode", 01777);
        assertEquals(discovery, SecureFiles.ensureOwnerOnlyDirectory(discovery), "a sticky parent, as /tmp");

        Path elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));
        Path real = SecureFiles.ensureOwnerOnlyDirectory(elsewhere.resolve("run"));
        Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rwxrwxr-x"));
        Path link = Files.createSymbolicLink(tmp.resolve("run-link"), real);
        IOException viaLink = assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(link));
        assertTrue(viaLink.getMessage().contains(elsewhere.toRealPath() + " above it is writable"),
                viaLink.getMessage());
    }

    /** P2-L1-F3: the ancestor judgement on ACL file systems, on built values (not verified on a Windows host). */
    @Test
    void anAncestorsAclMayLetOthersAddEntriesButNotReplaceThem() {
        UserPrincipal me = () -> "me";
        UserPrincipal system = () -> "SYSTEM";
        UserPrincipal admins = () -> "Administrators";
        UserPrincipal users = () -> "Authenticated Users";
        assertNull(SecureFiles.ancestorAclProblem(List.of(allow(me, AclEntryPermission.values()),
                allow(system, AclEntryPermission.values()), allow(admins, AclEntryPermission.values())), me, system,
                admins));
        assertNull(SecureFiles.ancestorAclProblem(List.of(allow(users, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_DATA, AclEntryPermission.EXECUTE)), me, system, admins),
                "a drive root: everyone may add a folder, nobody may replace one");
        AclEntry inheritOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(users)
                .setPermissions(AclEntryPermission.DELETE, AclEntryPermission.WRITE_DATA)
                .setFlags(AclEntryFlag.INHERIT_ONLY, AclEntryFlag.DIRECTORY_INHERIT).build();
        assertNull(SecureFiles.ancestorAclProblem(List.of(inheritOnly), me, system, admins),
                "an entry only children inherit does not apply to the directory");
        for (AclEntryPermission right : SecureFiles.ANCESTOR_REPLACE_RIGHTS) {
            String problem = SecureFiles.ancestorAclProblem(List.of(allow(users, right)), me, system, admins);
            assertEquals("has an ACL that allows Authenticated Users " + right.name(), problem);
        }
        AclEntry deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(users)
                .setPermissions(AclEntryPermission.DELETE_CHILD).build();
        assertNull(SecureFiles.ancestorAclProblem(List.of(deny), me, system, admins));
        assertEquals("has an ACL that cannot be read", SecureFiles.ancestorAclProblem(null, me, system, admins));
    }

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... rights) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who).setPermissions(rights).build();
    }

    /**
     * P2-L1-F3: the file is created and written through one channel that never follows a link; an entry already at
     * the path, a link included, is never followed, written or replaced.
     */
    @Test
    void anOwnerOnlyFileIsNeverWrittenThroughALink() throws IOException {
        Path target = tmp.resolve("someone-elses.txt");
        Path link = Files.createSymbolicLink(tmp.resolve("options.properties"), target);
        assertThrows(FileAlreadyExistsException.class,
                () -> SecureFiles.writeOwnerOnly(link, "token=secret".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Files.exists(target), "nothing was written where the link points");
        Path file = tmp.resolve("written.properties");
        byte[] content = new byte[300_000];
        java.util.Arrays.fill(content, (byte) 'x');
        SecureFiles.writeOwnerOnly(file, content);
        assertEquals(content.length, Files.size(file), "the whole content through the one channel");
        if (posix()) {
            assertEquals("rw-------", permissions(file));
        }
    }

    /**
     * Phase 6 security fix, pass 2, P2-L1-F4: a token is 16 to 1024 characters of the token alphabet, so a short
     * supplied token is refused by the launcher, the attach and the client alike; the generated one is 64 hex digits.
     */
    @Test
    void aTokenHasAtLeastSixteenCharacters() {
        assertFalse(SecureFiles.isValidToken("fifteen-chars-x"));
        assertTrue(SecureFiles.isValidToken("sixteen-chars-xy"));
        assertFalse(SecureFiles.isValidToken("x".repeat(1025)));
        assertFalse(SecureFiles.isValidToken("sixteen chars xy"), "the alphabet is unchanged");
        assertTrue(SecureFiles.isValidToken(SecureFiles.newToken()));
        assertEquals(64, SecureFiles.newToken().length());
        assertEquals(16, SecureFiles.MIN_TOKEN_LENGTH);
    }

    @Test
    void aFileSystemWithNeitherViewIsRefusedWithTheRemedyAndNothingIsCreated() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(tmp.resolve("none.zip"), Map.of("create", "true"))) {
            Path root = zip.getPath("/");
            assertFalse(zip.supportedFileAttributeViews().contains("posix"));
            assertFalse(zip.supportedFileAttributeViews().contains("acl"));

            IOException temp = assertThrows(IOException.class,
                    () -> SecureFiles.createOwnerOnlyTempDirectory(root, "skillroy-author-"));
            assertNeither(temp, "temporary directory skillroy-author-*");
            IOException dir = assertThrows(IOException.class,
                    () -> SecureFiles.ensureOwnerOnlyDirectory(root.resolve("work"), "the planner's work directory"));
            assertNeither(dir, "the planner's work directory");
            IOException discovery = assertThrows(IOException.class,
                    () -> SecureFiles.ensureOwnerOnlyDirectory(root.resolve("discovery")));
            assertNeither(discovery, "the discovery directory");
            IOException file = assertThrows(IOException.class,
                    () -> SecureFiles.writeOwnerOnly(root.resolve("mcp.json"), new byte[] {1}));
            assertNeither(file, "owner-only");
            assertTrue(SecureFiles.untrustedDirectoryReason(root).contains("neither POSIX permissions nor ACLs"));

            try (Stream<Path> entries = Files.list(root)) {
                assertEquals(List.of(), entries.toList(), "nothing was created");
            }
        }
    }

    private static void assertNeither(IOException e, String role) {
        assertTrue(e.getMessage().contains(role), role + ": " + e.getMessage());
        assertTrue(e.getMessage().contains("neither POSIX permissions nor ACLs"), e.getMessage());
        assertTrue(e.getMessage().contains("use a directory on a file system that has one of them"), e.getMessage());
    }
}
