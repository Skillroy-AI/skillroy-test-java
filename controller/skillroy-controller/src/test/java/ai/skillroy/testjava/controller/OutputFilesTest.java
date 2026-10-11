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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The run-output rule (Phase 6 security fix, pass 2, P2-L8-F3, P2-L8-S1; phase2-contracts.md 11.3): a file is never
 * written through what is in its place, and a directory a run writes into is the current user's. Against the JDK's
 * defaults, which every writer used until then, each case below wrote through the planted link or into the planted
 * file. Another OS user is not available on the test host: "a directory whose entries the run cannot remove" is a
 * directory without the owner's write bit (as another user's sticky directory is for the run), and "another user" is a
 * principal of another name given to the judgement; both are skipped as root, which neither rule binds.
 */
class OutputFilesTest {

    @TempDir
    Path tmp;

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static boolean root() {
        return "root".equals(System.getProperty("user.name"));
    }

    /** A file outside the output directory that a planted link names: the operator's own. */
    private Path victim() throws IOException {
        Path victim = tmp.resolve("home").resolve("victim.txt");
        Files.createDirectories(victim.getParent());
        Files.writeString(victim, "operator's own content\n");
        return victim;
    }

    @Test
    void aPlantedLinkIsReplacedAndItsTargetIsNeverWritten() throws IOException {
        Path victim = victim();
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path file = out.resolve("run.jsonl");
        Files.createSymbolicLink(file, victim);
        OutputFiles.writeString(file, "{\"type\":\"run-start\"}\n");
        assertEquals("operator's own content\n", Files.readString(victim), "the link's target is untouched");
        assertFalse(Files.isSymbolicLink(file));
        assertEquals("{\"type\":\"run-start\"}\n", Files.readString(file));

        Path writer = out.resolve("journal.jsonl");
        Files.createSymbolicLink(writer, victim);
        try (BufferedWriter w = OutputFiles.newWriter(writer)) {
            w.write("x");
        }
        assertEquals("operator's own content\n", Files.readString(victim));
        assertEquals("x", Files.readString(writer));
    }

    @Test
    void aDanglingLinkCreatesNothingWhereItPoints() throws IOException {
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path nowhere = tmp.resolve("home").resolve(".bash_aliases");
        Files.createSymbolicLink(out.resolve("web-ref2-aria.json"), nowhere);
        OutputFiles.write(out.resolve("web-ref2-aria.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertFalse(Files.exists(nowhere, LinkOption.NOFOLLOW_LINKS), "no file where the link pointed");
        assertEquals("{}", Files.readString(out.resolve("web-ref2-aria.json")));
    }

    @Test
    void anEntryTheRunCannotRemoveIsNeverWrittenThrough() throws IOException {
        assumeTrue(posix());
        assumeFalse(root(), "root may remove any entry");
        Path victim = victim();
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.createSymbolicLink(out.resolve("run.jsonl"), victim);
        Path foreign = out.resolve("bindings.json");
        Files.writeString(foreign, "planted\n");
        Files.setPosixFilePermissions(foreign, PosixFilePermissions.fromString("rw-rw-rw-"));
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            IOException link = assertThrows(IOException.class, () -> OutputFiles.writeString(out.resolve(
                    "run.jsonl"), "{\"type\":\"run-start\"}\n"));
            assertTrue(link.getMessage().contains("run.jsonl") && link.getMessage().contains("cannot be removed"),
                    link.getMessage());
            IOException file = assertThrows(IOException.class, () -> OutputFiles.writeString(foreign,
                    "{\"slot\":\"value\"}\n"));
            assertTrue(file.getMessage().contains("bindings.json"), file.getMessage());
        } finally {
            Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwx------"));
        }
        assertEquals("operator's own content\n", Files.readString(victim), "the link's target is untouched");
        assertEquals("planted\n", Files.readString(foreign), "the run wrote nothing into the planted file");
    }

    @Test
    void appendingRefusesALinkAndAnotherKindOfEntry() throws IOException {
        Path victim = victim();
        Path out = Files.createDirectories(tmp.resolve("telemetry"));
        Path spans = out.resolve("spans.jsonl");
        Files.createSymbolicLink(spans, victim);
        IOException e = assertThrows(IOException.class, () -> OutputFiles.append(spans,
                "{\"format\":\"skillroy-spans/1\"}\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertEquals("operator's own content\n", Files.readString(victim));
        Files.delete(spans);
        Files.createSymbolicLink(spans, tmp.resolve("missing"));
        assertThrows(IOException.class, () -> OutputFiles.appendWriter(spans));
        assertFalse(Files.exists(tmp.resolve("missing"), LinkOption.NOFOLLOW_LINKS));
        Files.delete(spans);

        OutputFiles.append(spans, "a\n".getBytes(StandardCharsets.UTF_8));
        try (BufferedWriter w = OutputFiles.appendWriter(spans)) {
            w.write("b\n");
        }
        assertEquals("a\nb\n", Files.readString(spans), "an own file is appended to; an absent one created");
    }

    @Test
    void aDirectoryIsCreatedAndOneOthersMayWriteToWithoutTheStickyBitIsRefused() throws IOException {
        Path made = OutputFiles.directory(tmp.resolve("a").resolve("b"));
        assertTrue(Files.isDirectory(made));
        assertNull(OutputFiles.directoryProblem(made));
        assumeTrue(posix());
        Path shared = Files.createDirectories(tmp.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        String problem = OutputFiles.directoryProblem(shared);
        assertTrue(problem != null && problem.contains("others may write"), String.valueOf(problem));
        IOException refused = assertThrows(IOException.class, () -> OutputFiles.writeString(shared.resolve(
                "run.jsonl"), "x"));
        assertTrue(refused.getMessage().contains(shared.toString()), refused.getMessage());
        assertFalse(Files.exists(shared.resolve("run.jsonl")));
        Files.setAttribute(shared, "unix:mode", 01777);
        assertNull(OutputFiles.directoryProblem(shared), "the sticky bit keeps others from renaming entries");
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxr-x"));
        assertNull(OutputFiles.directoryProblem(shared), "group write (a user-private-group umask) is allowed");
    }

    @Test
    void aDirectoryOrALinkOfAnotherUserIsRefused() throws IOException {
        assumeTrue(posix());
        assumeFalse(root(), "root's directories are accepted whoever runs");
        UserPrincipal other = () -> "someone-else";
        Path dir = Files.createDirectories(tmp.resolve("theirs"));
        String problem = OutputFiles.directoryProblem(dir, other, other);
        assertTrue(problem != null && problem.contains("owned by") && problem.contains("not by the current user"),
                String.valueOf(problem));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), dir);
        String linked = OutputFiles.directoryProblem(link, other, other);
        assertTrue(linked != null && linked.contains("symbolic link owned by"), String.valueOf(linked));
        assertNull(OutputFiles.directoryProblem(link), "the current user's own link to their own directory");
        assertTrue(OutputFiles.directoryProblem(tmp.resolve("missing")).contains("does not exist"));
        Path file = Files.writeString(tmp.resolve("plain"), "x");
        assertTrue(OutputFiles.directoryProblem(file).contains("not a directory"));
    }

    private static java.nio.file.attribute.AclEntry allow(UserPrincipal who, Set<java.nio.file.attribute.AclEntryFlag>
            flags, java.nio.file.attribute.AclEntryPermission... rights) {
        return java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(who).setFlags(flags).setPermissions(rights).build();
    }

    /**
     * Phase 7 security fix, S141 (phase2-contracts.md 11.3), Windows-only behaviour unit-tested on built ACL values
     * (no Windows host): the ACL branch of the directory rule now judges what others may write, as the POSIX branch
     * does. Modify for Authenticated Users (inherited from a drive root, the default under {@code C:\}) is refused, also
     * when it applies only to the files created in the directory; the owner's, the system account's and the
     * administrators' entries, a read-only entry, a DENY entry and an inherit-only CREATOR OWNER entry (a server
     * edition's drive root passes one to every folder) are accepted. Before the fix the ACL branch judged the owner
     * only, and the judgement did not exist.
     */
    @Test
    void anAclThatLetsAnotherPrincipalWriteIsRefused() {
        UserPrincipal me = () -> "me";
        UserPrincipal system = () -> "SYSTEM";
        UserPrincipal admins = () -> "Administrators";
        UserPrincipal creator = () -> "CREATOR OWNER";
        UserPrincipal users = () -> "Authenticated Users";
        Set<java.nio.file.attribute.AclEntryFlag> inherit = Set.of(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,
                java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT);
        Set<java.nio.file.attribute.AclEntryFlag> inheritOnly = Set.of(java.nio.file.attribute.AclEntryFlag
                .FILE_INHERIT, java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,
                java.nio.file.attribute.AclEntryFlag.INHERIT_ONLY);
        java.nio.file.attribute.AclEntryPermission[] all = java.nio.file.attribute.AclEntryPermission.values();
        java.util.List<java.nio.file.attribute.AclEntry> accepted = java.util.List.of(allow(me, inherit, all),
                allow(system, inherit, all), allow(admins, inherit, all), allow(creator, inheritOnly, all),
                allow(users, inherit, java.nio.file.attribute.AclEntryPermission.READ_DATA,
                        java.nio.file.attribute.AclEntryPermission.EXECUTE),
                java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.DENY)
                        .setPrincipal(users).setPermissions(all).build());
        assertNull(OutputFiles.aclWriteProblem(accepted, me, system, admins, creator));

        java.util.List<java.nio.file.attribute.AclEntry> modify = new java.util.ArrayList<>(accepted);
        modify.add(allow(users, inherit, java.nio.file.attribute.AclEntryPermission.READ_DATA,
                java.nio.file.attribute.AclEntryPermission.WRITE_DATA, java.nio.file.attribute.AclEntryPermission
                        .APPEND_DATA, java.nio.file.attribute.AclEntryPermission.DELETE));
        String problem = OutputFiles.aclWriteProblem(modify, me, system, admins, creator);
        assertTrue(problem != null && problem.startsWith("its ACL allows Authenticated Users APPEND_DATA, DELETE,"
                + " WRITE_DATA (remove the inheritance") && problem.contains("icacls"), String.valueOf(problem));

        java.util.List<java.nio.file.attribute.AclEntry> files = java.util.List.of(allow(me, inherit, all),
                allow(users, inheritOnly, java.nio.file.attribute.AclEntryPermission.WRITE_DATA));
        String toFiles = OutputFiles.aclWriteProblem(files, me, system, admins, creator);
        assertTrue(toFiles != null && toFiles.contains("on the files created in it"), String.valueOf(toFiles));
        assertTrue(OutputFiles.aclWriteProblem(java.util.List.of(allow(creator, inherit,
                java.nio.file.attribute.AclEntryPermission.WRITE_DATA)), me, system, admins, creator) != null,
                "CREATOR OWNER is exempt only where it is inherit-only");
        assertTrue(OutputFiles.aclWriteProblem(java.util.List.of(allow(admins, inherit, all)), me, system, null,
                creator) != null, "a built-in that could not be resolved is not exempt");
        assertEquals("its ACL cannot be read", OutputFiles.aclWriteProblem(null, me, system, admins, creator));
    }
}
