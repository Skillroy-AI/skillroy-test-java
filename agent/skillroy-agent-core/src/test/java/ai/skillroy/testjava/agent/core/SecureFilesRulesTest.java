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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file-system rules of the hardening wave that need no Windows host: the pure ACL judgement of findings S7/S15
 * (unit-tested on built ACL values; the ACL branch as a whole is <em>not verified on Windows</em>), the refusal on a
 * file system with neither POSIX permissions nor ACLs, and the default discovery directory rule of S8/S19, which the
 * controller's {@code Discovery.defaultDir()} applies identically.
 */
class SecureFilesRulesTest {

    @TempDir
    Path tmp;

    /** A principal for built ACL values (UserPrincipal has no public implementation). */
    private static final class Principal implements UserPrincipal {
        private final String name;

        Principal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Principal && ((Principal) o).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    private static final UserPrincipal ME = new Principal("me");
    private static final UserPrincipal OTHER = new Principal("other");
    private static final UserPrincipal SYSTEM = new Principal("SYSTEM");
    private static final UserPrincipal ADMINS = new Principal("Administrators");

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... permissions) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(EnumSet.copyOf(Arrays.asList(permissions))).build();
    }

    private static AclEntry deny(UserPrincipal who, AclEntryPermission... permissions) {
        return AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(who)
                .setPermissions(EnumSet.copyOf(Arrays.asList(permissions))).build();
    }

    // ---- S7, S15 ----

    @Test
    void aDirectoryIsTrustedOnlyWhenMeOwnsItAndNobodyElseMayChangeItsEntries() {
        List<AclEntry> ownerOnly = SecureFiles.ownerOnlyAcl(ME, true);
        assertNull(SecureFiles.aclProblem(ME, ownerOnly, ME, SYSTEM, ADMINS, SecureFiles.DIRECTORY_WRITE_RIGHTS));

        List<AclEntry> withBuiltIns = new ArrayList<AclEntry>(ownerOnly);
        withBuiltIns.add(allow(SYSTEM, AclEntryPermission.values()));
        withBuiltIns.add(allow(ADMINS, AclEntryPermission.values()));
        withBuiltIns.add(allow(OTHER, AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES));
        withBuiltIns.add(deny(OTHER, AclEntryPermission.WRITE_DATA));
        assertNull(SecureFiles.aclProblem(ME, withBuiltIns, ME, SYSTEM, ADMINS, SecureFiles.DIRECTORY_WRITE_RIGHTS),
                "built-in principals, reading and DENY entries are fine for a directory");

        for (AclEntryPermission right : SecureFiles.DIRECTORY_WRITE_RIGHTS) {
            List<AclEntry> acl = new ArrayList<AclEntry>(ownerOnly);
            acl.add(allow(OTHER, right));
            String problem = SecureFiles.aclProblem(ME, acl, ME, SYSTEM, ADMINS, SecureFiles.DIRECTORY_WRITE_RIGHTS);
            assertTrue(problem != null && problem.contains("other") && problem.contains(right.name()), problem);
        }
        String foreign = SecureFiles.aclProblem(OTHER, ownerOnly, ME, SYSTEM, ADMINS,
                SecureFiles.DIRECTORY_WRITE_RIGHTS);
        assertTrue(foreign.contains("owned by other"), foreign);
        assertTrue(SecureFiles.aclProblem(null, ownerOnly, ME, SYSTEM, ADMINS,
                SecureFiles.DIRECTORY_WRITE_RIGHTS).contains("nobody known"));
        assertTrue(SecureFiles.aclProblem(ME, null, ME, SYSTEM, ADMINS, SecureFiles.DIRECTORY_WRITE_RIGHTS)
                .contains("cannot be read"));
        assertTrue(SecureFiles.aclProblem(ME, ownerOnly, null, SYSTEM, ADMINS, SecureFiles.DIRECTORY_WRITE_RIGHTS)
                .contains("current user"));
    }

    @Test
    void anUnresolvedBuiltInPrincipalIsNotExempt() {
        List<AclEntry> acl = new ArrayList<AclEntry>(SecureFiles.ownerOnlyAcl(ME, true));
        acl.add(allow(SYSTEM, AclEntryPermission.WRITE_DATA));
        assertTrue(SecureFiles.aclProblem(ME, acl, ME, null, ADMINS, SecureFiles.DIRECTORY_WRITE_RIGHTS) != null);
    }

    @Test
    void aFileHoldingASecretMayGrantNothingToAnybodyElse() {
        List<AclEntry> acl = new ArrayList<AclEntry>(SecureFiles.ownerOnlyAcl(ME, false));
        assertNull(SecureFiles.aclProblem(ME, acl, ME, SYSTEM, ADMINS, SecureFiles.ANY_RIGHT));
        acl.add(allow(OTHER, AclEntryPermission.READ_DATA));
        String problem = SecureFiles.aclProblem(ME, acl, ME, SYSTEM, ADMINS, SecureFiles.ANY_RIGHT);
        assertTrue(problem != null && problem.contains("READ_DATA"), problem);
    }

    @Test
    void theOwnerOnlyAclHasOneAllowEntryForMeInheritedOnlyByADirectorysChildren() {
        List<AclEntry> dir = SecureFiles.ownerOnlyAcl(ME, true);
        assertEquals(1, dir.size());
        assertEquals(AclEntryType.ALLOW, dir.get(0).type());
        assertEquals(ME, dir.get(0).principal());
        assertEquals(EnumSet.allOf(AclEntryPermission.class), dir.get(0).permissions());
        assertEquals(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT), dir.get(0).flags());
        assertTrue(SecureFiles.ownerOnlyAcl(ME, false).get(0).flags().isEmpty());
    }

    @Test
    void aFileSystemWithNeitherPosixPermissionsNorAclsIsRefused() throws Exception {
        Path zip = tmp.resolve("plain.zip");
        Map<String, String> env = new HashMap<String, String>();
        env.put("create", "true");
        FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), env);
        try {
            Path dir = fs.getPath("/discovery");
            Assumptions.assumeTrue(SecureFiles.protection(dir) == SecureFiles.Protection.NONE,
                    "this zip file system offers " + fs.supportedFileAttributeViews());
            IOException e = assertThrows(IOException.class, () -> SecureFiles.prepareDirectory(dir));
            assertTrue(e.getMessage().contains("neither POSIX permissions nor ACLs"), e.getMessage());
            assertFalse(Files.exists(dir), "nothing is created where nothing can be restricted");
            assertThrows(IOException.class, () -> SecureFiles.writeOwnerOnly(fs.getPath("/token"),
                    new byte[] {1}));
            assertFalse(Files.exists(fs.getPath("/token")));
        } finally {
            fs.close();
        }
    }

    @Test
    void theDefaultFileSystemOfTheTestHostIsPosixAndCreatesOwnerOnly() throws Exception {
        Assumptions.assumeTrue(SecureFiles.protection(tmp) == SecureFiles.Protection.POSIX);
        Path dir = tmp.resolve("a").resolve("b");
        SecureFiles.prepareDirectory(dir);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        SecureFiles.writeOwnerOnly(dir.resolve("f"), new byte[] {1});
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("f"))));
    }

    // ---- S8, S19 ----

    @Test
    void theCandidatesAreTheTempDirectoryAndTheHomeFallBack() throws Exception {
        assertEquals(Paths.get("/t", "skillroy-alice"), SecureFiles.tmpCandidate("/t", "alice"));
        assertEquals(Paths.get("/t", "skillroy-alicecorp"), SecureFiles.tmpCandidate("/t", "alice@corp"),
                "the same reduction as the controller: every other character is dropped");
        assertEquals(Paths.get("/t", "skillroy-"), SecureFiles.tmpCandidate("/t", null));
        assertEquals(tmp.resolve(".skillroy").resolve("run"), SecureFiles.homeCandidate(tmp.toString()));
        assertNull(SecureFiles.homeCandidate(null));
        assertNull(SecureFiles.homeCandidate(""));
        assertNull(SecureFiles.homeCandidate("?"));
        assertNull(SecureFiles.homeCandidate("relative/home"));
        assertNull(SecureFiles.homeCandidate(tmp.resolve("missing").toString()));
        Files.write(tmp.resolve("file"), new byte[0]);
        assertNull(SecureFiles.homeCandidate(tmp.resolve("file").toString()), "not a directory");
    }

    @Test
    void theHomeFallBackIsChosenExactlyWhenTheTempCandidateBelongsToAnotherUser() throws Exception {
        Path b = tmp.resolve("home").resolve(".skillroy").resolve("run");
        Path missing = tmp.resolve("skillroy-nobody");
        assertEquals(missing, SecureFiles.choose(missing, b), "missing: the temp candidate");
        Path own = Files.createDirectory(tmp.resolve("skillroy-me"));
        assertEquals(own, SecureFiles.choose(own, b), "the user's own: the temp candidate");
        if (SecureFiles.protection(tmp) == SecureFiles.Protection.POSIX) {
            Files.setPosixFilePermissions(own, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertEquals(own, SecureFiles.choose(own, b),
                    "the user's own but writable by others is not replaced: it is refused later, never repaired");
            assertThrows(IOException.class, () -> SecureFiles.prepareDirectory(own));
        }
        Path link = tmp.resolve("skillroy-link");
        Files.createSymbolicLink(link, tmp.resolve("elsewhere"));
        assertEquals(link, SecureFiles.choose(link, b), "a dangling link of the user's own is not another user's");
        assertEquals(own, SecureFiles.choose(own, null), "no home candidate: never a fall-back");

        Path rootOwned = Paths.get("/");
        Assumptions.assumeTrue(!"root".equals(System.getProperty("user.name")) && Files.exists(rootOwned));
        assertTrue(SecureFiles.occupiedByAnotherUser(rootOwned), "/ belongs to root, not to the test user");
        assertEquals(b, SecureFiles.choose(rootOwned, b), "another user's entry: the home fall-back");
        assertEquals(rootOwned, SecureFiles.choose(rootOwned, null), "unless there is no home candidate");
    }

    @Test
    void theDefaultOfThisJvmFollowsTheRule() {
        Path a = SecureFiles.tmpCandidate(System.getProperty("java.io.tmpdir"), System.getProperty("user.name"));
        Path expected = SecureFiles.choose(a, SecureFiles.homeCandidate(System.getProperty("user.home")));
        assertEquals(expected.toFile(), SecureFiles.defaultDiscoveryDir());
        assertEquals(Collections.singletonList(expected.toFile().getPath()),
                Collections.singletonList(AgentRuntime.resolveOptions(null).get("discoveryDir")));
    }
}
