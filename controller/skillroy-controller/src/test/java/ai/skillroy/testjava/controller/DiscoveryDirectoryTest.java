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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract amendment A3 on the controller side: the per-user default discovery directory, the sanitised user name,
 * and the trust check (owner, group/others write, symbolic link) that Discovery, attach and launch apply before
 * using a directory.
 */
class DiscoveryDirectoryTest {

    private static final long PID = ProcessHandle.current().pid();

    @TempDir
    Path dir;

    private FakeAgent agent;

    @BeforeEach
    void startAgent() throws IOException {
        agent = new FakeAgent();
    }

    @AfterEach
    void stopAgent() {
        agent.close();
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void chmod(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static long deadPid() throws Exception {
        Process process = new ProcessBuilder(Fixtures.currentJava().toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return process.pid();
    }

    private static List<Path> optionFiles(Path discovery) throws IOException {
        if (!Files.isDirectory(discovery)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(discovery)) {
            return files.filter(p -> p.getFileName().toString().startsWith("skillroy-options-")).toList();
        }
    }

    @Test
    void theDefaultDirectoryIsPerUserUnderTheTempDirectory() {
        String tmp = System.getProperty("java.io.tmpdir");
        Path expected = Path.of(tmp, "skillroy-" + Discovery.sanitiseUserName(System.getProperty("user.name")));
        assertEquals(expected, Discovery.defaultDir());
        assertEquals(Path.of(tmp), Discovery.defaultDir().getParent());
        assertTrue(Discovery.defaultDir().getFileName().toString().matches("skillroy-[A-Za-z0-9._-]*"),
                Discovery.defaultDir().toString());
        assertEquals(Path.of("/t", "skillroy-alice"), Discovery.defaultDir("/t", "alice"));
    }

    /** The rule must match the agent's exactly: characters outside [A-Za-z0-9._-] are dropped, null gives "". */
    @Test
    void oddUserNamesAreReducedToSafeCharacters() {
        assertEquals("alice", Discovery.sanitiseUserName("alice"));
        assertEquals("Jane.Doe-2_x", Discovery.sanitiseUserName("Jane.Doe-2_x"), "allowed characters are kept");
        assertEquals("JaneDoe", Discovery.sanitiseUserName("Jane Doe"));
        assertEquals("CORPjdoe", Discovery.sanitiseUserName("CORP\\jdoe"));
        assertEquals("jdoecorp.example", Discovery.sanitiseUserName("jdoe@corp.example"));
        assertEquals("abcd", Discovery.sanitiseUserName("a/b:c*d"));
        assertEquals("tabnl", Discovery.sanitiseUserName("tab\tnl\n"));
        assertEquals("jos", Discovery.sanitiseUserName("josé"), "non-ASCII letters are dropped");
        assertEquals("", Discovery.sanitiseUserName("山田"));
        assertEquals("x", Discovery.sanitiseUserName("😀x"));
        assertEquals("", Discovery.sanitiseUserName("?"), "the JVM's user.name for a uid without a passwd entry");
        assertEquals("", Discovery.sanitiseUserName(""));
        assertEquals("", Discovery.sanitiseUserName(null));
        assertEquals(Path.of("/t", "skillroy-"), Discovery.defaultDir("/t", null));

        // The name always follows the prefix, so it stays one segment directly under the temp directory.
        Path climbing = Discovery.defaultDir("/t", "../../etc");
        assertEquals(Path.of("/t", "skillroy-....etc"), climbing);
        assertEquals(Path.of("/t"), climbing.normalize().getParent());
        assertEquals(Path.of("/t", "skillroy-.."), Discovery.defaultDir("/t", ".."));
        assertEquals(Path.of("/t"), Discovery.defaultDir("/t", "..").normalize().getParent());
    }

    @Test
    void aMissingDefaultStyleDirectoryIsCreatedOwnerOnly() throws Exception {
        Path created = SecureFiles.ensureOwnerOnlyDirectory(Discovery.defaultDir(dir.toString(), "odd user"));
        assertEquals(dir.resolve("skillroy-odduser").toAbsolutePath().normalize(), created);
        if (posix()) {
            assertEquals("rwx------", permissions(created));
        }
        assertNull(SecureFiles.untrustedDirectoryReason(created));
        assertEquals(created, SecureFiles.ensureOwnerOnlyDirectory(created), "an existing trusted one is reused");
    }

    @Test
    void aDirectoryWritableByGroupOrOthersIsIgnoredAndRefusedUnchanged() throws Exception {
        assumeTrue(posix(), "no POSIX permissions on this file system: the check is skipped here");
        Path shared = dir.resolve("shared");
        agent.writeDiscovery(shared);
        assertEquals(1, Discovery.list(shared).size());

        for (String bad : List.of("rwxrwx---", "rwx---rwx", "rwxrwxrwx", "rwx-w----", "rwx----w-")) {
            chmod(shared, bad);
            String reason = SecureFiles.untrustedDirectoryReason(shared);
            assertTrue(reason != null && reason.contains("writable by group or others"), bad + ": " + reason);
            assertEquals(List.of(), Discovery.list(shared), bad);
            assertEquals(List.of(), Discovery.entries(shared), bad);
            assertEquals(Optional.empty(), Discovery.forPid(PID, shared), bad);
            IOException refused = assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(shared));
            assertTrue(refused.getMessage().contains("writable by group or others"), refused.getMessage());
            assertTrue(refused.getMessage().contains("discoveryDir"), "says how to fix it: " + refused.getMessage());
            assertEquals(bad, permissions(shared), "an existing directory is never changed");
            assertThrows(IOException.class, () -> SecureFiles.writeOptionsFile(shared, Map.of("token", "t")));
            assertEquals(List.of(), optionFiles(shared));
        }

        chmod(shared, "rwxr-xr-x");
        assertNull(SecureFiles.untrustedDirectoryReason(shared), "readable by others is fine; writable is not");
        assertEquals(1, Discovery.list(shared).size());
        chmod(shared, "rwx------");
        assertEquals(1, Discovery.list(shared).size());
    }

    @Test
    void aDirectoryOfAnotherUserIsNotTrusted() throws Exception {
        assumeTrue(posix(), "no POSIX owners on this file system: the check is skipped here");
        Path theirs = Files.createDirectory(dir.resolve("theirs"));
        chmod(theirs, "rwx------");
        UserPrincipal someoneElse = () -> "skillroy-test-someone-else";
        String reason = SecureFiles.untrustedDirectoryReason(theirs, someoneElse);
        assertTrue(reason != null && reason.contains("owned by") && reason.contains("not by the current user"),
                String.valueOf(reason));
        assertNull(SecureFiles.untrustedDirectoryReason(theirs));
    }

    @Test
    void aDirectoryReallyOwnedByAnotherUserIsIgnored() throws Exception {
        assumeTrue(posix(), "no POSIX owners on this file system");
        assumeTrue("root".equals(System.getProperty("user.name")), "only root can give a directory away");
        UserPrincipal nobody;
        try {
            nobody = dir.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("nobody");
        } catch (IOException e) {
            assumeTrue(false, "no user 'nobody' here: " + e);
            return;
        }
        Path theirs = dir.resolve("theirs");
        agent.writeDiscovery(theirs);
        assertEquals(1, Discovery.list(theirs).size());
        Files.setOwner(theirs, nobody);
        assertTrue(SecureFiles.untrustedDirectoryReason(theirs).contains("owned by nobody"),
                SecureFiles.untrustedDirectoryReason(theirs));
        assertEquals(List.of(), Discovery.list(theirs));
        assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(theirs));
    }

    @Test
    void aSymbolicLinkCountsAsTheDirectoryItNamesWhenTheLinkIsOurs() throws Exception {
        Path real = dir.resolve("real");
        agent.writeDiscovery(real);
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here: " + e);
        }
        // The agent accepts a link of the current user to a safe directory, so Discovery must read it too.
        assertNull(SecureFiles.untrustedDirectoryReason(link));
        assertEquals(Discovery.list(real), Discovery.list(link));
        assertEquals(1, Discovery.list(link).size());
        assertEquals(link.toAbsolutePath().normalize(), SecureFiles.ensureOwnerOnlyDirectory(link));
        if (posix()) {
            chmod(real, "rwxrwxrwx");
            assertTrue(SecureFiles.untrustedDirectoryReason(link).contains("writable by group or others"),
                    "the directory behind the link is checked");
            assertEquals(List.of(), Discovery.list(link));
            chmod(real, "rwx------");
            UserPrincipal someoneElse = () -> "skillroy-test-someone-else";
            String reason = SecureFiles.untrustedDirectoryReason(link, someoneElse);
            assertTrue(reason != null && reason.startsWith("it is a symbolic link owned by ")
                    && reason.endsWith(", not by the current user"), String.valueOf(reason));
        }
        Path dangling = dir.resolve("dangling");
        Files.createSymbolicLink(dangling, dir.resolve("gone"));
        assertEquals("it does not exist", SecureFiles.untrustedDirectoryReason(dangling));
        assertEquals(List.of(), Discovery.list(dangling));
    }

    @Test
    void aFileOrAMissingPathIsNotADiscoveryDirectory() throws Exception {
        Path file = Files.writeString(dir.resolve("plain-file"), "x");
        assertEquals("it is not a directory", SecureFiles.untrustedDirectoryReason(file));
        assertEquals("it does not exist", SecureFiles.untrustedDirectoryReason(dir.resolve("missing")));
        assertEquals(List.of(), Discovery.list(dir.resolve("missing")));
    }

    @Test
    void awaitPidNamesAnIgnoredDirectory() throws Exception {
        assumeTrue(posix(), "no POSIX permissions on this file system");
        Path shared = dir.resolve("shared");
        agent.writeDiscovery(shared);
        chmod(shared, "rwxrwxrwx");
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> Discovery.awaitPid(PID, shared, Duration.ofMillis(300)));
        assertTrue(lost.getMessage().contains("discovery directory " + shared + " is ignored because it is "
                + "writable by group or others (rwxrwxrwx)"), lost.getMessage());

        chmod(shared, "rwx------");
        assertEquals(agent.endpoint(), Discovery.awaitPid(PID, shared, Duration.ofSeconds(30)));
    }

    @Test
    void launchAndAttachRefuseAnUntrustedDiscoveryDirectoryBeforeTouchingTheTarget() throws Exception {
        assumeTrue(posix(), "no POSIX permissions on this file system");
        Fixtures f = Fixtures.get();
        Path shared = Files.createDirectory(dir.resolve("shared"));
        chmod(shared, "rwxrwxr-x");

        IOException launch = assertThrows(IOException.class, () -> TargetLauncher.java(Fixtures.targetJava())
                .mainClass(Fixtures.CHILD_MAIN).classPath(List.of(f.childClasses)).discoveryDir(shared)
                .javaAgent(f.home, Map.of()).start());
        assertTrue(launch.getMessage().contains("writable by group or others"), launch.getMessage());
        IOException viaOption = assertThrows(IOException.class, () -> TargetLauncher.java(Fixtures.targetJava())
                .mainClass(Fixtures.CHILD_MAIN).classPath(List.of(f.childClasses))
                .javaAgent(f.home, Map.of("discoveryDir", shared.toString())).start());
        assertTrue(viaOption.getMessage().contains("writable by group or others"), viaOption.getMessage());

        if (AttachSession.attachModulePresent()) {
            AttachFailedException attach = assertThrows(AttachFailedException.class,
                    () -> AttachSession.attach(deadPid(), f.home, Map.of("discoveryDir", shared.toString())));
            assertTrue(attach.getMessage().contains("writable by group or others"), attach.getMessage());
        }
        assertEquals(List.of(), optionFiles(shared));
        assertEquals("rwxrwxr-x", permissions(shared));
    }

    @Test
    void anEmbeddedAgentRefusesAnUntrustedDirectoryAndAwaitAgentSaysWhy() throws Exception {
        assumeTrue(posix(), "no POSIX permissions on this file system");
        Fixtures f = Fixtures.get();
        Path shared = Files.createDirectory(dir.resolve("shared"));
        chmod(shared, "rwxrwx---");
        Path report = dir.resolve("report.properties");
        try (LaunchedTarget target = TargetLauncher.java(Fixtures.targetJava())
                .classPath(List.of(f.childClasses, f.agentJar))
                .mainClass(Fixtures.CHILD_MAIN)
                .jvmArg("-Dskillroy.agent.enabled=true")
                .discoveryDir(shared)
                .arg("embedded=true")
                .arg("report=" + report)
                .inheritOutputTo(dir.resolve("embedded.log"))
                .start()) {
            // main() writes the report after the embedded start has returned, so its log line is already written.
            Properties seen = Fixtures.report(report);
            assertEquals(shared.toString(), seen.getProperty("prop.skillroy.agent.discoveryDir"));
            AgentLostException lost = assertThrows(AgentLostException.class,
                    () -> target.awaitAgent(Duration.ofMillis(500)));
            assertTrue(lost.getMessage().contains("is ignored because it is writable by group or others"),
                    lost.getMessage());
            assertTrue(lost.getMessage().contains("[skillroy] agent failed to start"), lost.getMessage());
            assertTrue(lost.getMessage().contains("writable by group or others: " + shared), lost.getMessage());
            assertTrue(target.process().isAlive(), "a refused directory never ends the host");
        }
        try (Stream<Path> files = Files.list(shared)) {
            assertEquals(List.of(), files.toList(), "nothing was written into the refused directory");
        }
    }

    // ---- findings S8/S19: one deterministic fall-back, the same rule as the agent's ----

    @Test
    void theFallbackIsUsedExactlyWhenTheTempCandidateBelongsToSomeoneElse() throws Exception {
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path a = tmp.resolve("skillroy-alice");
        Path b = home.resolve(".skillroy").resolve("run");
        UserPrincipal me = SecureFiles.currentUser();
        UserPrincipal someoneElse = () -> "skillroy-test-someone-else";

        assertEquals(a, Discovery.defaultDir(tmp.toString(), "alice", home.toString(), me), "(a) missing: (a)");
        assertEquals(a, Discovery.defaultDir(tmp.toString(), "alice", home.toString(), someoneElse),
                "(a) missing: (a), whoever asks");
        Files.createDirectory(a);
        assertEquals(a, Discovery.defaultDir(tmp.toString(), "alice", home.toString(), me), "(a) is ours: (a)");
        // Seen as another user, (a) is occupied by someone else: (b).
        assertEquals(b, Discovery.defaultDir(tmp.toString(), "alice", home.toString(), someoneElse));
        // Without a usable home there is no (b): the refusal stays.
        for (String noHome : new String[] {null, "", "?", "relative/home", dir.resolve("missing").toString()}) {
            assertEquals(a, Discovery.defaultDir(tmp.toString(), "alice", noHome, someoneElse), noHome);
        }
        Files.delete(a);
        Files.writeString(a, "a plain file planted by someone else");
        assertEquals(b, Discovery.defaultDir(tmp.toString(), "alice", home.toString(), someoneElse),
                "any entry of another user, not only a directory");
    }

    @Test
    void aSelfOwnedGroupWritableTempCandidateIsRefusedWithoutFallback() throws Exception {
        assumeTrue(posix(), "no POSIX permissions on this file system");
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path a = Files.createDirectory(tmp.resolve("skillroy-alice"));
        chmod(a, "rwxrwxrwx");
        assertEquals(a, Discovery.defaultDir(tmp.toString(), "alice", home.toString(), SecureFiles.currentUser()),
                "not another user's doing: no fall-back");
        assertTrue(SecureFiles.untrustedDirectoryReason(a).contains("writable by group or others"));
    }

    @Test
    void theDefaultCandidatesAreTheTempAndTheHomeDirectory() {
        List<Path> candidates = Discovery.defaultCandidates();
        assertEquals(Discovery.defaultDir(System.getProperty("java.io.tmpdir"), System.getProperty("user.name")),
                candidates.get(0));
        Path home = Discovery.homeCandidate(System.getProperty("user.home"));
        if (home != null) {
            assertEquals(List.of(candidates.get(0), Path.of(System.getProperty("user.home"), ".skillroy", "run")),
                    candidates);
        }
    }

    @Test
    void missingParentsAreCreatedOwnerOnlyAsTheAgentCreatesThem() throws Exception {
        Path deep = dir.resolve("home").resolve(".skillroy").resolve("run");
        assertEquals(deep, SecureFiles.ensureOwnerOnlyDirectory(deep));
        if (posix()) {
            assertEquals("rwx------", permissions(deep));
            assertEquals("rwx------", permissions(deep.getParent()));
            assertEquals("rwx------", permissions(deep.getParent().getParent()));
        }
    }

    @Test
    void aListingWithoutADirectoryReadsTheDefaultCandidates() throws Exception {
        // Only a smoke test against the real default locations: whatever is there, a null directory is accepted.
        assertTrue(Discovery.list(null, false).stream().allMatch(e -> e.port() > 0));
    }

    // ---- findings S7/S15: the ACL judgement (not verified on Windows: built ACL values only) ----

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... permissions) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(permissions.length == 0 ? EnumSet.noneOf(AclEntryPermission.class)
                        : EnumSet.of(permissions[0], permissions)).build();
    }

    @Test
    void theAclJudgementAcceptsOnlyMeAndTheBuiltInPrincipals() {
        UserPrincipal me = () -> "me";
        UserPrincipal system = () -> "SYSTEM";
        UserPrincipal admins = () -> "Administrators";
        UserPrincipal users = () -> "Users";
        AclEntry mine = SecureFiles.ownerOnlyAcl(me, true).get(0);
        assertTrue(mine.flags().contains(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT));
        assertEquals(EnumSet.allOf(AclEntryPermission.class), mine.permissions());

        assertNull(SecureFiles.aclProblem(me, List.of(mine), me, system, admins,
                SecureFiles.DIRECTORY_WRITE_RIGHTS));
        assertNull(SecureFiles.aclProblem(me, List.of(mine, allow(system, AclEntryPermission.WRITE_DATA),
                allow(admins, AclEntryPermission.WRITE_ACL)), me, system, admins, SecureFiles.DIRECTORY_WRITE_RIGHTS));
        assertNull(SecureFiles.aclProblem(me, List.of(mine, allow(users, AclEntryPermission.READ_DATA,
                AclEntryPermission.EXECUTE)), me, system, admins, SecureFiles.DIRECTORY_WRITE_RIGHTS),
                "others may list and traverse a directory");
        for (AclEntryPermission right : SecureFiles.DIRECTORY_WRITE_RIGHTS) {
            String problem = SecureFiles.aclProblem(me, List.of(mine, allow(users, right)), me, system, admins,
                    SecureFiles.DIRECTORY_WRITE_RIGHTS);
            assertTrue(problem != null && problem.contains("Users") && problem.contains(right.name()),
                    right + ": " + problem);
        }
        AclEntry deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(users)
                .setPermissions(AclEntryPermission.WRITE_DATA).build();
        assertNull(SecureFiles.aclProblem(me, List.of(mine, deny), me, system, admins,
                SecureFiles.DIRECTORY_WRITE_RIGHTS), "a DENY entry grants nothing");

        assertTrue(SecureFiles.aclProblem(users, List.of(mine), me, system, admins,
                SecureFiles.DIRECTORY_WRITE_RIGHTS).contains("owned by Users"));
        assertTrue(SecureFiles.aclProblem(me, List.of(mine), null, system, admins,
                SecureFiles.DIRECTORY_WRITE_RIGHTS).contains("cannot be determined"));
        assertTrue(SecureFiles.aclProblem(me, null, me, system, admins, SecureFiles.DIRECTORY_WRITE_RIGHTS)
                .contains("cannot be read"));
        // Built-in principals that could not be resolved are not exempt.
        assertTrue(SecureFiles.aclProblem(me, List.of(mine, allow(system, AclEntryPermission.WRITE_DATA)), me, null,
                null, SecureFiles.DIRECTORY_WRITE_RIGHTS).contains("SYSTEM"));
        // A file holding a secret: anybody else reading it is refused.
        assertTrue(SecureFiles.aclProblem(me, List.of(SecureFiles.ownerOnlyAcl(me, false).get(0),
                allow(users, AclEntryPermission.READ_DATA)), me, system, admins, SecureFiles.ANY_RIGHT)
                .contains("READ_DATA"));
        assertFalse(SecureFiles.ownerOnlyAcl(me, false).get(0).flags()
                .contains(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT));
    }

    @Test
    void theProtectionOfThisFileSystemIsKnown() {
        SecureFiles.Protection protection = SecureFiles.protection(dir);
        assertEquals(posix() ? SecureFiles.Protection.POSIX : SecureFiles.Protection.ACL, protection,
                "a test host has POSIX permissions or ACLs");
    }
}
