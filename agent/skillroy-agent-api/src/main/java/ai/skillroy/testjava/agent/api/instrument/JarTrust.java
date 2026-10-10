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

package ai.skillroy.testjava.agent.api.instrument;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether a jar the agent loads into the target from a path the operator names outside the agent home can have been
 * changed by another user (phase6-contracts.md section 3.6 and 3.7, D153; Phase 6 security fix, pass 2, P2-L1-F5): the
 * one rule for the {@code modules} paths, {@code hooks.spyJar} and {@code byteman.jar}, whose own check
 * ({@code JarCheck}) delegates here. A jar is code execution in the target, whoever wrote it, so the jar and the
 * directory it sits in (its real directory, and, when the path is a symbolic link, the directory that holds the link)
 * must be writable by nobody but their owner, and that owner must be the current user or the administrator. On a
 * POSIX file system: neither is writable by group or others, and each is owned by the current user (by name, or as
 * the owner of a file this process creates) or by root. On an ACL file system (not verified on a Windows host: the
 * judgement {@link #aclProblem} is tested on built ACL values): the owner is the current user or the system account,
 * the administrators group or the installer service, each resolved as a principal and never matched by a bare name,
 * and no ALLOW entry gives anyone else a right to write, delete or change the ACL or owner. A file system with neither
 * view cannot be checked and is refused. The directories above are not judged (as for {@code byteman.jar}): the path
 * is the operator's. Reads attributes only; never opens the jar.
 *
 * <p>Stateless apart from the lazily resolved current user; safe from any thread.
 */
public final class JarTrust {

    /** ACL rights that let a principal change, replace or take over a file or a directory's entries. */
    public static final Set<AclEntryPermission> WRITE_RIGHTS = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.WRITE_NAMED_ATTRS));

    /** {@link #problem}: the path names no readable regular file (a caller reports that in its own words). */
    public static final String NOT_A_FILE = "it is not a readable regular file";

    /** {@link #problem}: someone other than the owner, or an owner other than the user or the administrator. */
    public static final String WRITABLE_BY_OTHERS = "it or its directory is writable by others";

    private JarTrust() {
    }

    /**
     * Why the jar at {@code jar} must not be loaded, or null when only its owner (the current user or the
     * administrator) can have changed it: {@link #NOT_A_FILE} or {@link #WRITABLE_BY_OTHERS}.
     */
    public static String problem(Path jar) {
        Path real;
        try {
            real = jar.toRealPath();
        } catch (IOException | RuntimeException e) {
            return NOT_A_FILE;
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(real)) {
            return NOT_A_FILE;
        }
        Path dir = real.getParent();
        if (dir == null || writableByOthers(real) || writableByOthers(dir)) {
            return WRITABLE_BY_OTHERS;
        }
        if (Files.isSymbolicLink(jar)) {
            Path linkDir = jar.toAbsolutePath().getParent();
            if (linkDir == null || writableByOthers(linkDir)) {
                return WRITABLE_BY_OTHERS;
            }
        }
        return null;
    }

    /**
     * True when someone but the owner may write to {@code path} (looked at without following a link at its end), or
     * when the owner is neither the current user nor the administrator, or when that cannot be told.
     */
    public static boolean writableByOthers(Path path) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                PosixFileAttributes a = posix.readAttributes();
                Set<PosixFilePermission> p = a.permissions();
                if (p.contains(PosixFilePermission.GROUP_WRITE) || p.contains(PosixFilePermission.OTHERS_WRITE)) {
                    return true;
                }
                String owner = a.owner().getName();
                return !(owner.equals(System.getProperty("user.name")) || owner.equals("root") || isMine(path));
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                return true;
            }
            return aclProblem(acl.getOwner(), acl.getAcl(), Me.PRINCIPAL, BuiltIns.ALL) != null;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /**
     * The pure ACL judgement (the shape of the agent core's {@code SecureFiles.aclProblem}, with the owners a jar may
     * have): why an object with this owner and ACL must not be trusted, or null. The owner must be {@code me} or one of
     * {@code builtIns}; no ALLOW entry may give a principal other than those a write right. A built-in that could not
     * be resolved is not in {@code builtIns}, so a principal is never exempt by its name alone.
     */
    public static String aclProblem(UserPrincipal owner, List<AclEntry> acl, UserPrincipal me,
                                    Collection<UserPrincipal> builtIns) {
        if (me == null) {
            return "the current user cannot be determined";
        }
        if (owner == null || !(owner.equals(me) || builtIns.contains(owner))) {
            return "it is owned by " + (owner == null ? "nobody known" : owner.getName())
                    + ", not by the current user or an administrator";
        }
        if (acl == null) {
            return "its ACL cannot be read";
        }
        for (AclEntry entry : acl) {
            UserPrincipal who = entry.principal();
            if (entry.type() != AclEntryType.ALLOW || me.equals(who) || builtIns.contains(who)) {
                continue;
            }
            Set<String> granted = new TreeSet<String>();
            for (AclEntryPermission right : entry.permissions()) {
                if (WRITE_RIGHTS.contains(right)) {
                    granted.add(right.name());
                }
            }
            if (!granted.isEmpty()) {
                StringBuilder joined = new StringBuilder();
                for (String name : granted) {
                    joined.append(joined.length() == 0 ? "" : ", ").append(name);
                }
                return "its ACL allows " + who.getName() + " " + joined;
            }
        }
        return null;
    }

    /** The owner of {@code path} is the owner of a file this process creates (uid comparison without a name). */
    private static boolean isMine(Path path) {
        UserPrincipal me = Me.PRINCIPAL;
        try {
            return me != null && me.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** "Me": the owner of a file this process has just created; null if unknown. Resolved on first use only. */
    private static final class Me {
        static final UserPrincipal PRINCIPAL = ownerOfNewFile();

        private Me() {
        }

        private static UserPrincipal ownerOfNewFile() {
            Path probe = null;
            try {
                probe = Files.createTempFile("skillroy-jar-owner-", ".tmp");
                return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException e) {
                return null;
            } finally {
                if (probe != null) {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (IOException | RuntimeException ignored) {
                        // an empty probe file in the temporary directory is harmless
                    }
                }
            }
        }
    }

    /** The system account, the administrators group and the installer service, resolved once; those that resolve. */
    private static final class BuiltIns {
        static final List<UserPrincipal> ALL = resolve();

        private BuiltIns() {
        }

        private static List<UserPrincipal> resolve() {
            List<UserPrincipal> out = new ArrayList<UserPrincipal>();
            add(out, "SYSTEM", false);
            add(out, "Administrators", true);
            add(out, "NT SERVICE\\TrustedInstaller", false);
            return Collections.unmodifiableList(out);
        }

        private static void add(List<UserPrincipal> out, String name, boolean group) {
            try {
                UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
                out.add(group ? lookup.lookupPrincipalByGroupName(name) : lookup.lookupPrincipalByName(name));
            } catch (IOException | RuntimeException e) {
                // not resolvable here (a POSIX host, or no such principal): not exempt
            }
        }
    }
}
