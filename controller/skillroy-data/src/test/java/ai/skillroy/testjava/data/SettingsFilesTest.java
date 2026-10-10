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

package ai.skillroy.testjava.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The owner rule for the operator's settings files (Phase 6 security fix, pass 2, P2-L8-F1): on the test host, the
 * operator's own file (readable by others) is read, a file others may write to and a dangling link are refused, a link
 * of the current user's is followed to the file it names; on built values, a file of another owner (POSIX and ACL), a
 * group-writable file whose group is not the owner's private group, an ACL with a write right for anybody else, a
 * volume without permissions, and a file swapped while it was read are refused. Another OS user is not available on
 * the test host: the owner cases go through the {@code Access} seam.
 */
class SettingsFilesTest {

    @TempDir
    Path dir;

    private record Principal(String name) implements GroupPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }

    private static final UserPrincipal ME = new Principal("me");
    private static final UserPrincipal OTHER = new Principal("other");
    private static final UserPrincipal SYSTEM = new Principal("SYSTEM");
    private static final UserPrincipal ADMINS = new Principal("Administrators");
    private static final GroupPrincipal STAFF = new Principal("staff");
    private static final GroupPrincipal MY_GROUP = new Principal("me");

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static Set<PosixFilePermission> mode(String text) {
        return PosixFilePermissions.fromString(text);
    }

    @Test
    void theOperatorsOwnFileIsReadAndOneOthersMayWriteIsRefused() throws IOException {
        Path file = Files.writeString(dir.resolve("telemetry.yaml"), "version: 1\n");
        assertArrayEquals("version: 1\n".getBytes(StandardCharsets.UTF_8), SettingsFiles.read(file, 64));
        assertEquals(6, SettingsFiles.read(file, 5).length, "at most max + 1 bytes, so that the caller sees the bound");
        assumeTrue(posix());
        Files.setPosixFilePermissions(file, mode("rw-r--r--"));
        assertNull(SettingsFiles.problem(file, SettingsFiles.REAL), "readable by others: it holds no secret");
        Files.setPosixFilePermissions(file, mode("rw-r--rw-"));
        SettingsFiles.RefusedException e = assertThrows(SettingsFiles.RefusedException.class,
                () -> SettingsFiles.read(file, 64));
        assertTrue(e.getMessage().startsWith("others may write to it"), e.getMessage());
    }

    @Test
    void aLinkOfTheCurrentUserIsFollowedAndADanglingOneRefused() throws IOException {
        Path target = Files.writeString(dir.resolve("real.yaml"), "version: 1\n");
        Path link = Files.createSymbolicLink(dir.resolve("inference.yaml"), target);
        assertNull(SettingsFiles.problem(link, SettingsFiles.REAL));
        Files.delete(link);
        Files.createSymbolicLink(link, dir.resolve("missing.yaml"));
        assertEquals("it is a symbolic link to a file that does not exist", SettingsFiles.problem(link,
                SettingsFiles.REAL));
        assertEquals("it does not exist", SettingsFiles.problem(dir.resolve("absent.yaml"), SettingsFiles.REAL));
        assertEquals("it is not a regular file", SettingsFiles.problem(dir, SettingsFiles.REAL));
    }

    @Test
    void thePosixJudgement() {
        assertNull(SettingsFiles.posixProblem(ME, STAFF, mode("rw-r--r--"), ME));
        assertNull(SettingsFiles.posixProblem(new Principal("root"), STAFF, mode("rw-r--r--"), ME),
                "a file of root's");
        assertTrue(SettingsFiles.posixProblem(OTHER, STAFF, mode("rw-------"), ME).contains("owned by other"));
        assertTrue(SettingsFiles.posixProblem(ME, STAFF, mode("rw-rw-r--"), ME).contains("its group staff may"
                + " write to it"));
        assertNull(SettingsFiles.posixProblem(ME, MY_GROUP, mode("rw-rw-r--"), ME),
                "the owner's user-private group (umask 002)");
        assertTrue(SettingsFiles.posixProblem(ME, MY_GROUP, mode("rw-rw-rw-"), ME).contains("others may write"));
        assertTrue(SettingsFiles.posixProblem(ME, STAFF, mode("rw-r--r--"), null).contains("owned by me"),
                "an unknown current user accepts no owner but root");
    }

    @Test
    void theAclJudgement() {
        assertNull(SettingsFiles.aclProblem(ME, List.of(allow(ME, AclEntryPermission.WRITE_DATA),
                allow(new Principal("Users"), AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES)),
                ME, SYSTEM, ADMINS), "reading is allowed");
        assertNull(SettingsFiles.aclProblem(ADMINS, List.of(allow(SYSTEM, AclEntryPermission.WRITE_DATA)), ME, SYSTEM,
                ADMINS), "a file an elevated process wrote");
        assertTrue(SettingsFiles.aclProblem(OTHER, List.of(), ME, SYSTEM, ADMINS).contains("owned by other"));
        assertEquals("its ACL allows Authenticated Users APPEND_DATA, WRITE_DATA", SettingsFiles.aclProblem(ME,
                List.of(allow(new Principal("Authenticated Users"), AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_DATA)), ME, SYSTEM, ADMINS));
        assertTrue(SettingsFiles.aclProblem(ME, null, ME, SYSTEM, ADMINS).contains("cannot be read"));
    }

    @Test
    void aPlantedFileAVolumeWithoutPermissionsAndASwapAreRefused() throws IOException {
        Path file = Files.writeString(dir.resolve("telemetry.yaml"), "version: 1\n");
        assertTrue(SettingsFiles.problem(file, fake(file, OTHER, null, null)).contains("owned by other"),
                "another user's file in the operator's directory");
        assertTrue(SettingsFiles.problem(file, fake(file, ME, "exfat", null)).contains("volume of type exfat"));
        AtomicInteger reads = new AtomicInteger();
        SettingsFiles.RefusedException swapped = assertThrows(SettingsFiles.RefusedException.class,
                () -> SettingsFiles.read(file, 64, fake(file, ME, null, () -> "key-" + reads.incrementAndGet())));
        assertEquals("it was replaced while it was read", swapped.getMessage());
    }

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... permissions) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who).setPermissions(permissions)
                .build();
    }

    /** A regular file owned by {@code owner}, mode rw-r--r--, on a volume of {@code volume}; its key from {@code key}. */
    private static SettingsFiles.Access fake(Path real, UserPrincipal owner, String volume,
                                             java.util.function.Supplier<Object> key) {
        return new SettingsFiles.Access() {
            @Override
            public BasicFileAttributes entry(Path file) throws IOException {
                return attributes(file);
            }

            @Override
            public UserPrincipal linkOwner(Path file) {
                return owner;
            }

            @Override
            public BasicFileAttributes file(Path file) throws IOException {
                return attributes(file);
            }

            private BasicFileAttributes attributes(Path file) throws IOException {
                if (!file.equals(real)) {
                    throw new NoSuchFileException(file.toString());
                }
                BasicFileAttributes basic = Files.readAttributes(file, BasicFileAttributes.class);
                return new BasicFileAttributes() {
                    @Override
                    public FileTime lastModifiedTime() {
                        return basic.lastModifiedTime();
                    }

                    @Override
                    public FileTime lastAccessTime() {
                        return basic.lastAccessTime();
                    }

                    @Override
                    public FileTime creationTime() {
                        return basic.creationTime();
                    }

                    @Override
                    public boolean isRegularFile() {
                        return true;
                    }

                    @Override
                    public boolean isDirectory() {
                        return false;
                    }

                    @Override
                    public boolean isSymbolicLink() {
                        return false;
                    }

                    @Override
                    public boolean isOther() {
                        return false;
                    }

                    @Override
                    public long size() {
                        return basic.size();
                    }

                    @Override
                    public Object fileKey() {
                        return key == null ? basic.fileKey() : key.get();
                    }
                };
            }

            @Override
            public CredentialFiles.Kind kind(Path file) {
                return CredentialFiles.Kind.POSIX;
            }

            @Override
            public String volumeType(Path file) {
                return volume;
            }

            @Override
            public PosixFileAttributes posix(Path file) throws IOException {
                BasicFileAttributes basic = attributes(file);
                return new PosixFileAttributes() {
                    @Override
                    public UserPrincipal owner() {
                        return owner;
                    }

                    @Override
                    public GroupPrincipal group() {
                        return STAFF;
                    }

                    @Override
                    public Set<PosixFilePermission> permissions() {
                        return mode("rw-r--r--");
                    }

                    @Override
                    public FileTime lastModifiedTime() {
                        return basic.lastModifiedTime();
                    }

                    @Override
                    public FileTime lastAccessTime() {
                        return basic.lastAccessTime();
                    }

                    @Override
                    public FileTime creationTime() {
                        return basic.creationTime();
                    }

                    @Override
                    public boolean isRegularFile() {
                        return true;
                    }

                    @Override
                    public boolean isDirectory() {
                        return false;
                    }

                    @Override
                    public boolean isSymbolicLink() {
                        return false;
                    }

                    @Override
                    public boolean isOther() {
                        return false;
                    }

                    @Override
                    public long size() {
                        return basic.size();
                    }

                    @Override
                    public Object fileKey() {
                        return basic.fileKey();
                    }
                };
            }

            @Override
            public UserPrincipal owner(Path file) {
                return owner;
            }

            @Override
            public List<AclEntry> acl(Path file) {
                return List.of();
            }

            @Override
            public UserPrincipal me() {
                return ME;
            }

            @Override
            public UserPrincipal system() {
                return SYSTEM;
            }

            @Override
            public UserPrincipal administrators() {
                return ADMINS;
            }
        };
    }
}
