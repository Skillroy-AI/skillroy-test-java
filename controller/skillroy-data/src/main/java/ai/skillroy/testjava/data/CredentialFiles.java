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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The permission rule for {@code credentials.yaml} (phase2-contracts.md 4): a file that other users can read or write
 * is refused. Where the file system has POSIX permissions, any group or other bit refuses it. Where it has ACLs only,
 * the owner-only rule of findings S7/S15 applies (the owner is the current user, and no ALLOW entry gives anybody but
 * the current user, the system account or the administrators group any right; <em>not verified on Windows</em>: the
 * judgement is unit-tested on built ACL values). Where it has neither, nothing can be checked: the file is accepted
 * and a warning is logged once per JVM.
 *
 * <p>Phase 6 security fix, pass 2, P2-L7-F1: the kind is the volume's ({@link Volumes#protection}), not the file
 * system provider's, and a file on a FAT or exFAT volume ({@link Volumes#WITHOUT_PERMISSIONS}) is refused before the
 * mode is looked at: such a volume reads back owner-only modes (macOS) while anybody with the volume reads the file.
 * The warning branch stays for any other volume without either view (D138).
 *
 * <p>The file system is reached through {@link Access}, so that the ACL and no-permission branches can be tested on any
 * host. Stateless apart from the warn-once flag; safe from any thread.
 */
final class CredentialFiles {

    private static final System.Logger LOG = System.getLogger(CredentialFiles.class.getName());
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private CredentialFiles() {
    }

    /** How a file system protects files. */
    enum Kind { POSIX, ACL, NONE }

    /** What the rule needs to know about a file and the current user. */
    interface Access {
        Kind kind(Path file) throws IOException;

        Set<PosixFilePermission> posixPermissions(Path file) throws IOException;

        UserPrincipal owner(Path file) throws IOException;

        List<AclEntry> acl(Path file) throws IOException;

        /** The current user as a file owner, or null when it cannot be determined. */
        UserPrincipal me();

        /** The system account, or null where it cannot be resolved. */
        UserPrincipal system();

        /** The administrators group, or null where it cannot be resolved. */
        UserPrincipal administrators();

        /** The type of the volume that holds the file, or null when unknown (P2-L7-F1). */
        default String volumeType(Path file) {
            return null;
        }
    }

    /** The real file system. */
    static final Access REAL = new Access() {
        @Override
        public Kind kind(Path file) {
            // The volume decides (P2-L7-F1); the views must also be there for the reads below.
            return switch (Volumes.protection(file)) {
                case POSIX -> Files.getFileAttributeView(file, PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS) != null ? Kind.POSIX : Kind.NONE;
                case ACL -> Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        != null ? Kind.ACL : Kind.NONE;
                case NONE -> Kind.NONE;
            };
        }

        @Override
        public String volumeType(Path file) {
            return Volumes.type(file);
        }

        @Override
        public Set<PosixFilePermission> posixPermissions(Path file) throws IOException {
            return Files.getPosixFilePermissions(file);
        }

        @Override
        public UserPrincipal owner(Path file) throws IOException {
            return Files.getOwner(file);
        }

        @Override
        public List<AclEntry> acl(Path file) throws IOException {
            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            return view == null ? null : view.getAcl();
        }

        @Override
        public UserPrincipal me() {
            return Me.OWNER;
        }

        @Override
        public UserPrincipal system() {
            return lookup("SYSTEM", false);
        }

        @Override
        public UserPrincipal administrators() {
            return lookup("Administrators", true);
        }
    };

    /**
     * Why {@code file} must not be used as a credentials file, or null when it may be. Logs the "cannot be checked"
     * warning once when the file system has neither POSIX permissions nor ACLs.
     */
    static String problem(Path file, Access access) {
        try {
            String volume = access.volumeType(file);
            if (Volumes.withoutPermissions(volume)) {
                return "credentials file is on a volume of type " + volume + ", which keeps no permissions, so other"
                        + " users may read it (move it to a volume with POSIX permissions or ACLs and make it owner-only)";
            }
            switch (access.kind(file)) {
                case POSIX -> {
                    Set<PosixFilePermission> permissions = access.posixPermissions(file);
                    for (PosixFilePermission p : permissions) {
                        if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE
                                && p != PosixFilePermission.OWNER_EXECUTE) {
                            return "credentials file is accessible to other users ("
                                    + PosixFilePermissions.toString(permissions) + "; make it owner-only, e.g. mode"
                                    + " 600)";
                        }
                    }
                    return null;
                }
                case ACL -> {
                    String acl = aclProblem(access.owner(file), access.acl(file), access.me(), access.system(),
                            access.administrators());
                    return acl == null ? null : "credentials file is accessible to other users (" + acl + ")";
                }
                default -> {
                    if (WARNED.compareAndSet(false, true)) {
                        LOG.log(System.Logger.Level.WARNING, "the file system of " + file + " has neither POSIX"
                                + " permissions nor ACLs, so who can read the credentials file cannot be checked");
                    }
                    return null;
                }
            }
        } catch (IOException | RuntimeException e) {
            return "the permissions of the credentials file cannot be read (" + e.getClass().getName() + ")";
        }
    }

    /**
     * The ACL judgement of findings S7/S15 for a file holding a secret: the owner must be {@code me}; no ALLOW entry
     * may give a principal other than {@code me}, {@code system} or {@code administrators} any right. A built-in that
     * could not be resolved (null) is not exempt.
     */
    static String aclProblem(UserPrincipal owner, List<AclEntry> acl, UserPrincipal me, UserPrincipal system,
                             UserPrincipal administrators) {
        if (me == null) {
            return "the current user cannot be determined";
        }
        if (owner == null || !owner.equals(me)) {
            return "it is owned by " + (owner == null ? "nobody known" : owner.getName()) + ", not by the current user";
        }
        if (acl == null) {
            return "its ACL cannot be read";
        }
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.ALLOW || entry.permissions().isEmpty()) {
                continue;
            }
            UserPrincipal who = entry.principal();
            if (me.equals(who) || who.equals(system) || who.equals(administrators)) {
                continue;
            }
            return "its ACL allows " + who.getName() + " access";
        }
        return null;
    }

    /** Forgets that the warning was logged (tests only). */
    static void resetWarning() {
        WARNED.set(false);
    }

    /** True once the "cannot be checked" warning was logged in this JVM. */
    static boolean warned() {
        return WARNED.get();
    }

    private static UserPrincipal lookup(String name, boolean group) {
        try {
            UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
            return group ? lookup.lookupPrincipalByGroupName(name) : lookup.lookupPrincipalByName(name);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** "Me" for the ACL rule: the owner of a file this process has just created; resolved once, on first use. */
    private static final class Me {
        static final UserPrincipal OWNER = ownerOfNewFile();

        private Me() {
        }

        private static UserPrincipal ownerOfNewFile() {
            Path probe = null;
            try {
                probe = Files.createTempFile("skillroy-owner-", ".tmp");
                return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException e) {
                return null;
            } finally {
                if (probe != null) {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (IOException | RuntimeException ignored) {
                        // A probe file left in the temporary directory holds nothing.
                    }
                }
            }
        }
    }
}
