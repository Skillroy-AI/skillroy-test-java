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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one way every writer of a run's output opens a file (Phase 6 security fix, pass 2, P2-L8-F3 and P2-L8-S1;
 * phase2-contracts.md 11.3, phase6-contracts.md 10.5): {@code run.jsonl}, {@code bindings.json}, the ledgers,
 * {@code capabilities.json}, the {@code failure/} and {@code artifacts/} files, {@code spans.jsonl}, a session's
 * journal and ledgers, triage's files, the re-resolve report, the JUnit report, the planner's records and the MCP
 * server's files.
 *
 * <p>Two rules. <b>A directory a run writes into must be the current user's</b> ({@link #directoryProblem}): looked at
 * without following links, it is a directory owned by the current user (or by root, as {@code /tmp} is) that others may
 * not write to unless it carries the sticky bit; a symbolic link in its place is followed only when the current user
 * (or root) owns the link. Group write is allowed: a user-private-group umask ({@code 002}) makes every directory so,
 * and the group is the operator's own choice. <b>A file is never written through what is already there</b>: a file
 * that is replaced ({@link #write}, {@link #newWriter}) is created exclusively ({@code CREATE_NEW}, not following
 * links) after the old entry was removed (a symbolic link itself, never its target), so a link or another user's file
 * planted in its place makes the write fail with a message that says so instead of truncating the link's target or
 * filling a file another user can read; a file that is appended to ({@link #append}, {@link #appendWriter}) must be a
 * regular file of the current user, opened without following links, and is created exclusively when absent.
 *
 * <p>Until this class, every writer opened its file with the JDK's defaults, which follow a symbolic link, truncate an
 * existing file and keep its owner and mode; the executor's delete of its old outputs was best effort, so in a
 * directory whose entries the run cannot remove (another user's directory with the sticky bit, which a path under
 * {@code /tmp} that another user created first is) the run log replaced whatever file a planted link named, and the
 * run wrote its evidence into a world-readable file another user had put there, whatever the umask.
 *
 * <p>On a volume with neither POSIX permissions nor ACLs (FAT, exFAT) no owner can be judged and nothing can be
 * linked: the directory rule accepts it. The ACL branch (Windows) compares the owner with the owner of a file this
 * process creates, the system account and the administrators group, and refuses a directory whose ACL lets another
 * principal write to it or to the files created in it ({@link #aclWriteProblem}; Phase 7 security fix, S141, the ACL
 * twin of the POSIX "others may write" rule); <em>not verified on a Windows host</em>.
 *
 * <p>Stateless; safe from any thread.
 */
public final class OutputFiles {

    /** The sticky bit of {@code unix:mode}. */
    private static final int STICKY = 01000;

    private OutputFiles() {
    }

    /**
     * Creates {@code dir} if needed (with any missing parent) and checks that a run may write into it
     * ({@link #directoryProblem(Path)}).
     *
     * @return the directory, absolute and normalised
     * @throws IOException when it cannot be created, or when it is refused; the message names the directory and the
     *     rule
     */
    public static Path directory(Path dir) throws IOException {
        Path absolute = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            Files.createDirectories(absolute);
        }
        String problem = directoryProblem(absolute);
        if (problem != null) {
            throw new IOException(refusal(absolute, problem));
        }
        return absolute;
    }

    /** The message of a refused directory. */
    static String refusal(Path dir, String problem) {
        return "the output directory " + dir + " cannot be used: " + problem + " (a run writes only into a directory"
                + " of the current user that others may not write to; choose another directory)";
    }

    /**
     * Why a run must not write into {@code dir}, or null when it may (see the class comment): it does not exist, it is
     * not a directory, a symbolic link in its place belongs to another user, it is owned by another user, or others
     * may write to it without the sticky bit.
     */
    public static String directoryProblem(Path dir) {
        return directoryProblem(dir, SecureFiles.currentUser(), SecureFiles.fileOwner());
    }

    /**
     * {@link #directoryProblem(Path)} with "me" given: {@code posixMe} for a POSIX volume (null skips the owner
     * comparison, as {@link SecureFiles} does when the current user cannot be determined), {@code aclMe} for an ACL
     * volume. Tests pass another principal.
     */
    static String directoryProblem(Path dir, UserPrincipal posixMe, UserPrincipal aclMe) {
        try {
            Path absolute = dir.toAbsolutePath();
            BasicFileAttributes entry;
            try {
                entry = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return "it does not exist";
            }
            SecureFiles.Protection protection = SecureFiles.protection(absolute);
            if (protection == SecureFiles.Protection.NONE) {
                return entry.isDirectory() || entry.isSymbolicLink() && Files.isDirectory(absolute) ? null
                        : "it is not a directory";
            }
            if (entry.isSymbolicLink()) {
                UserPrincipal linkOwner = Files.getOwner(absolute, LinkOption.NOFOLLOW_LINKS);
                if (!trustedOwner(linkOwner, protection, posixMe, aclMe)) {
                    return "it is a symbolic link owned by " + linkOwner.getName() + ", not by the current user";
                }
            }
            if (!Files.isDirectory(absolute)) {
                return "it is not a directory";
            }
            UserPrincipal owner = Files.getOwner(absolute);
            if (!trustedOwner(owner, protection, posixMe, aclMe)) {
                return "it is owned by " + owner.getName() + ", not by the current user";
            }
            if (protection == SecureFiles.Protection.POSIX) {
                PosixFileAttributes posix = Files.readAttributes(absolute, PosixFileAttributes.class);
                Set<PosixFilePermission> permissions = posix.permissions();
                if (permissions.contains(PosixFilePermission.OTHERS_WRITE) && !sticky(absolute)) {
                    return "others may write to it without the sticky bit ("
                            + PosixFilePermissions.toString(permissions) + ")";
                }
            }
            if (protection == SecureFiles.Protection.ACL) {
                AclFileAttributeView view = Files.getFileAttributeView(absolute, AclFileAttributeView.class);
                return aclWriteProblem(view == null ? null : view.getAcl(), aclMe, SecureFiles.systemAccount(),
                        SecureFiles.administrators(), CreatorOwner.PRINCIPAL);
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "its attributes cannot be read (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * ACL rights that let a principal plant, replace or remove an entry of a directory, change a file created in it, or
     * take either over (Phase 7 security fix, S141).
     */
    static final Set<AclEntryPermission> WRITE_RIGHTS = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER));

    /**
     * The ACL half of "others may not write to it" (Phase 7 security fix, S141; phase2-contracts.md 11.3): why the
     * directory's ACL lets a principal other than the current user, the system account or the administrators group
     * write to the directory or to the files a run creates in it, or null. Every ALLOW entry is judged, the ones only
     * children inherit ({@code INHERIT_ONLY}) too, since the run's files and subdirectories receive them; an
     * inherit-only entry for CREATOR OWNER is not refused (it becomes the creator's own entry on the new file, and a
     * server edition's drive root passes one to every folder); DENY entries are not credited. A built-in that could
     * not be resolved (null) is not exempt. The remedy in the message is the one of {@code SECURITY.md}.
     *
     * @param acl the directory's ACL, or null when it cannot be read
     */
    static String aclWriteProblem(List<AclEntry> acl, UserPrincipal me, UserPrincipal system,
                                  UserPrincipal administrators, UserPrincipal creatorOwner) {
        if (acl == null) {
            return "its ACL cannot be read";
        }
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.ALLOW) {
                continue;
            }
            UserPrincipal who = entry.principal();
            if (who.equals(me) || who.equals(system) || who.equals(administrators)) {
                continue;
            }
            if (entry.flags().contains(AclEntryFlag.INHERIT_ONLY) && who.equals(creatorOwner)) {
                continue;
            }
            Set<AclEntryPermission> granted = EnumSet.noneOf(AclEntryPermission.class);
            granted.addAll(entry.permissions());
            granted.retainAll(WRITE_RIGHTS);
            if (!granted.isEmpty()) {
                Set<String> names = new TreeSet<>();
                granted.forEach(p -> names.add(p.name()));
                return "its ACL allows " + who.getName() + " " + String.join(", ", names)
                        + (entry.flags().contains(AclEntryFlag.INHERIT_ONLY) ? " on the files created in it" : "")
                        + " (remove the inheritance and grant only yourself: icacls <dir> /inheritance:r /grant:r"
                        + " \"%USERNAME%\":(OI)(CI)F)";
            }
        }
        return null;
    }

    /** The CREATOR OWNER principal, resolved once; null where it cannot be resolved (then it is not exempt). */
    private static final class CreatorOwner {
        static final UserPrincipal PRINCIPAL = lookup();

        private CreatorOwner() {
        }

        private static UserPrincipal lookup() {
            try {
                return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("CREATOR OWNER");
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /** True when the sticky bit is set (links followed); false when the mode cannot be read. */
    private static boolean sticky(Path dir) {
        try {
            Object mode = Files.getAttribute(dir, "unix:mode");
            return mode instanceof Integer bits && (bits & STICKY) != 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True for the current user or root (POSIX), or the current user, the system account or the administrators group
     * (ACL; the owner of a file an elevated process creates is the administrators group).
     */
    private static boolean trustedOwner(UserPrincipal owner, SecureFiles.Protection protection, UserPrincipal posixMe,
                                        UserPrincipal aclMe) {
        if (owner == null) {
            return false;
        }
        if (protection == SecureFiles.Protection.POSIX) {
            return "root".equals(owner.getName()) || SecureFiles.isUser(owner, posixMe);
        }
        return aclMe != null && aclMe.equals(owner) || owner.equals(SecureFiles.systemAccount())
                || owner.equals(SecureFiles.administrators());
    }

    /**
     * Writes {@code content} as the new {@code file}: its directory as {@link #directory} makes and checks it, the old
     * entry of that name removed (a symbolic link itself, never what it names), the file created exclusively without
     * following links.
     *
     * @throws IOException when the directory is refused, the old entry cannot be removed, or something took its place
     *     before the file was created (nothing is written then)
     */
    public static void write(Path file, byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        try (OutputStream out = replace(file)) {
            out.write(content);
        }
    }

    /** {@link #write(Path, byte[])} of {@code text} in UTF-8. */
    public static void writeString(Path file, String text) throws IOException {
        write(file, Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
    }

    /** A UTF-8 writer on a new {@code file}, created as {@link #write} creates it. */
    public static BufferedWriter newWriter(Path file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(replace(file), StandardCharsets.UTF_8));
    }

    /**
     * Appends {@code content} to {@code file}: an existing entry must be a regular file of the current user (a
     * symbolic link is refused, never followed); an absent one is created exclusively.
     *
     * @throws IOException when the directory or the existing entry is refused
     */
    public static void append(Path file, byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        try (OutputStream out = appending(file)) {
            out.write(content);
        }
    }

    /** A UTF-8 writer appending to {@code file} under the rule of {@link #append}. */
    public static BufferedWriter appendWriter(Path file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(appending(file), StandardCharsets.UTF_8));
    }

    private static OutputStream replace(Path file) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        directory(parent(target));
        try {
            Files.deleteIfExists(target);
        } catch (DirectoryNotEmptyException e) {
            throw new IOException(target + " cannot be written: a directory of that name is in the way");
        } catch (IOException e) {
            throw new IOException(target + " cannot be written: the entry of that name cannot be removed ("
                    + e.getClass().getSimpleName() + "), and a run never writes through an entry it did not create"
                    + " (a symbolic link or another user's file)", e);
        }
        try {
            return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException e) {
            throw new IOException(target + " cannot be written: an entry of that name appeared before the file was"
                    + " created, and a run never writes through an entry it did not create", e);
        }
    }

    private static OutputStream appending(Path file) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        directory(parent(target));
        BasicFileAttributes before = existing(target);
        if (before == null) {
            try {
                return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (FileAlreadyExistsException raced) {
                before = existing(target);
                if (before == null) {
                    throw raced;
                }
            }
        }
        OutputStream out = Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS);
        BasicFileAttributes after = existing(target);
        if (after == null || before.fileKey() != null && !before.fileKey().equals(after.fileKey())) {
            out.close();
            throw new IOException(target + " cannot be appended to: it was replaced while it was opened");
        }
        return out;
    }

    /**
     * The attributes of an existing entry to append to (not following links), or null when there is none; refused
     * unless it is a regular file of the current user.
     */
    private static BasicFileAttributes existing(Path target) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(target + " cannot be appended to: it is " + (attributes.isSymbolicLink()
                    ? "a symbolic link" : "not a regular file") + ", and a run never writes through an entry it did"
                    + " not create");
        }
        SecureFiles.Protection protection = SecureFiles.protection(target);
        if (protection != SecureFiles.Protection.NONE) {
            UserPrincipal owner = Files.getOwner(target, LinkOption.NOFOLLOW_LINKS);
            if (!trustedOwner(owner, protection, SecureFiles.currentUser(), SecureFiles.fileOwner())) {
                throw new IOException(target + " cannot be appended to: it is owned by " + owner.getName()
                        + ", not by the current user");
            }
        }
        return attributes;
    }

    private static Path parent(Path absolute) throws IOException {
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException(absolute + " is not a file in a directory");
        }
        return parent;
    }
}
