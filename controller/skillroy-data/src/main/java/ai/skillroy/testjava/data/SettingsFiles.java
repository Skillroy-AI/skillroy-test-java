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

import ai.skillroy.testjava.protocol.Volumes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The owner rule for the operator's settings files, {@code telemetry.yaml} and {@code inference.yaml} (Phase 6 security
 * fix, pass 2, P2-L8-F1; phase6-contracts.md 10.3, phase5-contracts.md 4.4, threat-model.md 3): both are read
 * implicitly from the credentials file's directory or the bindings environment directory, and a file alone can turn
 * export on and name the environment variables whose values become request headers (telemetry) or send the masked
 * prompts and answers of live calls into a directory of its choosing (inference). Such a file is therefore read only
 * when nobody but the current user could have written it:
 *
 * <ul>
 *   <li>looked at without following links, it is a regular file; a symbolic link is followed only when the current
 *       user (or root, the system account, the administrators group) owns the link, and the file it names is then
 *       judged;</li>
 *   <li>POSIX: the file is owned by the current user or by root, others may not write to it, and its group may not
 *       either unless the group is the owner's user-private group (a group of the owner's name, which a
 *       {@code umask 002} distribution gives every user; macOS's shared {@code staff} is not one);</li>
 *   <li>ACL (Windows; <em>not verified on a Windows host</em>: the judgement is unit-tested on built values): the owner
 *       is the current user (the owner of a file this process creates), the system account or the administrators
 *       group, and no ALLOW entry gives anybody else a right to write, append, delete or change the ACL, owner or
 *       attributes (reading is allowed: the file holds no secret);</li>
 *   <li>a volume with neither, or one of {@link Volumes#WITHOUT_PERMISSIONS}, is refused: anybody with the volume may
 *       have written it.</li>
 * </ul>
 *
 * <p>The file is judged before and after it is read, and the second judgement must find the same file (its file key,
 * where the file system has one), so that a file swapped in while it was read is refused too. Unlike the credentials
 * file's rule ({@code CredentialFiles}), others may read a settings file (it holds no secret and may be committed), and
 * the owner is compared. A refusal is never silent: a planted file stops the command with a message naming the file
 * and the rule, so that the operator learns of it.
 *
 * <p>Stateless; safe from any thread.
 */
public final class SettingsFiles {

    /** ACL rights that let a principal change a file's content or take it over. */
    static final Set<AclEntryPermission> WRITE_RIGHTS = EnumSet.of(AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER, AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_NAMED_ATTRS);

    /** A settings file the rule refuses; the message is the reason, without the file's name. */
    public static final class RefusedException extends IOException {
        private static final long serialVersionUID = 1L;

        RefusedException(String reason) {
            super(reason);
        }
    }

    /** What the rule needs to know about a file (tests pass their own). */
    interface Access {
        /** The attributes of the entry itself (links not followed); NoSuchFileException when there is none. */
        BasicFileAttributes entry(Path file) throws IOException;

        /** The owner of the entry itself (links not followed). */
        UserPrincipal linkOwner(Path file) throws IOException;

        /** The attributes of the file (links followed). */
        BasicFileAttributes file(Path file) throws IOException;

        /** The kind of the file's volume (P2-L7-F1). */
        CredentialFiles.Kind kind(Path file) throws IOException;

        /** The type of the file's volume, or null. */
        String volumeType(Path file);

        /** The POSIX attributes of the file (links followed). */
        PosixFileAttributes posix(Path file) throws IOException;

        /** The owner of the file (links followed). */
        UserPrincipal owner(Path file) throws IOException;

        /** The file's ACL (links followed), or null. */
        List<AclEntry> acl(Path file) throws IOException;

        /** The current user as a file owner, or null when it cannot be determined. */
        UserPrincipal me();

        UserPrincipal system();

        UserPrincipal administrators();
    }

    /** The real file system. */
    static final Access REAL = new Access() {
        @Override
        public BasicFileAttributes entry(Path file) throws IOException {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public UserPrincipal linkOwner(Path file) throws IOException {
            return Files.getOwner(file, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public BasicFileAttributes file(Path file) throws IOException {
            return Files.readAttributes(file, BasicFileAttributes.class);
        }

        @Override
        public CredentialFiles.Kind kind(Path file) throws IOException {
            return CredentialFiles.REAL.kind(file);
        }

        @Override
        public String volumeType(Path file) {
            return CredentialFiles.REAL.volumeType(file);
        }

        @Override
        public PosixFileAttributes posix(Path file) throws IOException {
            return Files.readAttributes(file, PosixFileAttributes.class);
        }

        @Override
        public UserPrincipal owner(Path file) throws IOException {
            return Files.getOwner(file);
        }

        @Override
        public List<AclEntry> acl(Path file) throws IOException {
            return CredentialFiles.REAL.acl(file);
        }

        @Override
        public UserPrincipal me() {
            return CredentialFiles.REAL.me();
        }

        @Override
        public UserPrincipal system() {
            return CredentialFiles.REAL.system();
        }

        @Override
        public UserPrincipal administrators() {
            return CredentialFiles.REAL.administrators();
        }
    };

    private SettingsFiles() {
    }

    /**
     * Reads at most {@code maxBytes + 1} bytes of {@code file} (so that the caller can tell a file over its bound)
     * under the rule of the class comment.
     *
     * @throws RefusedException when the rule refuses the file; the message is the reason
     * @throws IOException when the file cannot be read
     */
    public static byte[] read(Path file, int maxBytes) throws IOException {
        return read(file, maxBytes, REAL);
    }

    static byte[] read(Path file, int maxBytes, Access access) throws IOException {
        Object before = judged(file, access);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(maxBytes + 1);
        }
        Object after = judged(file, access);
        if (!Objects.equals(before, after)) {
            throw new RefusedException("it was replaced while it was read");
        }
        return bytes;
    }

    /** The file key of the file the rule accepts (null where the file system has none). */
    private static Object judged(Path file, Access access) throws IOException {
        String problem = problem(file, access);
        if (problem != null) {
            throw new RefusedException(problem);
        }
        return access.file(file).fileKey();
    }

    /** Why {@code file} must not be read as a settings file, or null when it may be. */
    static String problem(Path file, Access access) {
        try {
            BasicFileAttributes entry;
            try {
                entry = access.entry(file);
            } catch (NoSuchFileException e) {
                return "it does not exist";
            }
            String volume = access.volumeType(file);
            if (Volumes.withoutPermissions(volume)) {
                return "it is on a volume of type " + volume + ", which keeps no permissions, so anybody with the"
                        + " volume may have written it";
            }
            CredentialFiles.Kind kind = access.kind(file);
            if (kind == CredentialFiles.Kind.NONE) {
                return "its file system has neither POSIX permissions nor ACLs, so who may have written it cannot be"
                        + " checked";
            }
            if (entry.isSymbolicLink()) {
                UserPrincipal linkOwner = access.linkOwner(file);
                if (!trusted(linkOwner, kind, access)) {
                    return "it is a symbolic link owned by " + name(linkOwner) + ", not by the current user";
                }
                try {
                    entry = access.file(file);
                } catch (NoSuchFileException e) {
                    return "it is a symbolic link to a file that does not exist";
                }
            }
            if (!entry.isRegularFile()) {
                return "it is not a regular file";
            }
            if (kind == CredentialFiles.Kind.POSIX) {
                PosixFileAttributes posix = access.posix(file);
                return posixProblem(posix.owner(), posix.group(), posix.permissions(), access.me());
            }
            return aclProblem(access.owner(file), access.acl(file), access.me(), access.system(),
                    access.administrators());
        } catch (IOException | RuntimeException e) {
            return "its attributes cannot be read (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * The POSIX judgement: the owner is {@code me} or root; no other-write bit; no group-write bit unless the group
     * has the owner's name (a user-private group). {@code me} null (unknown) compares by nothing and refuses every
     * owner but root.
     */
    static String posixProblem(UserPrincipal owner, UserPrincipal group, Set<PosixFilePermission> permissions,
                               UserPrincipal me) {
        if (owner == null || !("root".equals(owner.getName()) || owner.equals(me)
                || me != null && owner.getName().equals(me.getName()))) {
            return "it is owned by " + name(owner) + ", not by the current user";
        }
        if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            return "others may write to it (" + PosixFilePermissions.toString(permissions) + ")";
        }
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                && (group == null || !group.getName().equals(owner.getName()))) {
            return "its group " + name(group) + " may write to it (" + PosixFilePermissions.toString(permissions)
                    + ")";
        }
        return null;
    }

    /**
     * The ACL judgement: the owner is {@code me}, the system account or the administrators group; no ALLOW entry gives
     * a principal other than those a right of {@link #WRITE_RIGHTS}. A built-in that could not be resolved (null) is
     * not exempt.
     */
    static String aclProblem(UserPrincipal owner, List<AclEntry> acl, UserPrincipal me, UserPrincipal system,
                             UserPrincipal administrators) {
        if (owner == null || !(owner.equals(me) || owner.equals(system) || owner.equals(administrators))) {
            return "it is owned by " + name(owner) + ", not by the current user";
        }
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
            Set<AclEntryPermission> granted = EnumSet.noneOf(AclEntryPermission.class);
            granted.addAll(entry.permissions());
            granted.retainAll(WRITE_RIGHTS);
            if (!granted.isEmpty()) {
                Set<String> names = new TreeSet<>();
                granted.forEach(p -> names.add(p.name()));
                return "its ACL allows " + who.getName() + " " + String.join(", ", names);
            }
        }
        return null;
    }

    private static boolean trusted(UserPrincipal owner, CredentialFiles.Kind kind, Access access) {
        if (owner == null) {
            return false;
        }
        UserPrincipal me = access.me();
        if (kind == CredentialFiles.Kind.POSIX) {
            return "root".equals(owner.getName()) || owner.equals(me)
                    || me != null && owner.getName().equals(me.getName());
        }
        return owner.equals(me) || owner.equals(access.system()) || owner.equals(access.administrators());
    }

    private static String name(UserPrincipal principal) {
        return principal == null ? "nobody known" : principal.getName();
    }
}
