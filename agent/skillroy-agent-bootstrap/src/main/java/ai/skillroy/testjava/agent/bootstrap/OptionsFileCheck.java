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

package ai.skillroy.testjava.agent.bootstrap;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
 * Who may have written the options file named by {@code t=} or {@code config=} (phase1-contracts.md 2.4 and 7
 * "Options file"; Phase 6 security fix, pass 2, P2-L1-F3). The file can enable the agent in ATTACH mode, hands it its
 * token and names module jars to load, so a file another user planted or could change is code execution in the
 * target. Looked at without following links, the file must be a regular file owned by the current user (or by the
 * administrator: root, the system account or the administrators group, who can do anything anyway) that nobody else
 * may write to; a file that holds a {@code token} must, in addition, be readable by nobody else, as the controller
 * always writes it (S5, S6). Where the file system has POSIX attributes the owner is compared by uid when the
 * process uid is known (by name otherwise) and the group and other bits are judged; where it has ACLs (Windows; not
 * verified on a Windows host: the judgement is unit-tested on built ACL values) "me" is the owner of a file this
 * process has just created and no ALLOW entry may give anyone else a write right (any right, for a file with a
 * token); a file system with neither cannot be checked and is refused. Reads attributes only; the caller reads the
 * file.
 *
 * <p>Stateless apart from the lazily resolved current user; safe from any thread. Runs before any agent class is
 * loaded and only when an options file is named.
 */
final class OptionsFileCheck {

    /** ACL rights that let a principal change a file or take it over. */
    static final Set<AclEntryPermission> WRITE_RIGHTS = Collections.unmodifiableSet(EnumSet.of(
            AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE,
            AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_NAMED_ATTRS));

    /** Any right at all: a file holding the token may be accessible to its owner (and the administrator) only. */
    static final Set<AclEntryPermission> ANY_RIGHT =
            Collections.unmodifiableSet(EnumSet.allOf(AclEntryPermission.class));

    /** Sentinel for "the process uid cannot be determined". */
    static final long UNKNOWN_UID = Long.MIN_VALUE;

    private OptionsFileCheck() {
    }

    /**
     * Why the options file must not be read, or null when it may: the attributes of {@code file} looked at without
     * following links.
     *
     * @param holdsToken whether the file carries a {@code token} (then nobody else may read it either)
     */
    static String problem(Path file, boolean holdsToken) {
        try {
            if (Files.isSymbolicLink(file)) {
                return "it is a symbolic link";
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return "it is not a regular file";
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                PosixFileAttributes attributes = posix.readAttributes();
                Object uid = null;
                try {
                    uid = Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS);
                } catch (IOException | RuntimeException noUnixView) {
                    // Owner names are compared instead.
                }
                long fileUid = uid instanceof Number ? ((Number) uid).longValue() : UNKNOWN_UID;
                return posixProblem(attributes.owner().getName(), fileUid, attributes.permissions(),
                        CurrentUser.uid(), System.getProperty("user.name"), holdsToken);
            }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                return "its file system has neither POSIX permissions nor ACLs, so who may change it cannot be told";
            }
            return aclProblem(acl.getOwner(), acl.getAcl(), CurrentUser.fileOwner(), BuiltIns.ALL,
                    holdsToken ? ANY_RIGHT : WRITE_RIGHTS);
        } catch (IOException | RuntimeException e) {
            return "its attributes cannot be read (" + e.getClass().getName() + ")";
        }
    }

    /**
     * The POSIX judgement: the owner is the current user (by uid when both uids are known, else by name) or root; no
     * group or other write bit; and, for a file with a token, no group or other bit at all.
     */
    static String posixProblem(String ownerName, long ownerUid, Set<PosixFilePermission> permissions,
                               long processUid, String userName, boolean holdsToken) {
        boolean mine;
        if (ownerUid != UNKNOWN_UID && processUid != UNKNOWN_UID) {
            mine = ownerUid == processUid || ownerUid == 0L;
        } else {
            mine = "root".equals(ownerName) || (userName != null && userName.equals(ownerName));
        }
        if (!mine) {
            return "it is owned by " + ownerName + ", not by the current user";
        }
        boolean othersWrite = permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE);
        if (othersWrite) {
            return "it is writable by group or others (" + PosixFilePermissions.toString(permissions) + ")";
        }
        if (holdsToken) {
            for (PosixFilePermission p : permissions) {
                if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE
                        && p != PosixFilePermission.OWNER_EXECUTE) {
                    return "it holds a token and is readable by group or others ("
                            + PosixFilePermissions.toString(permissions) + ")";
                }
            }
        }
        return null;
    }

    /**
     * The ACL judgement (the shape of the core's {@code SecureFiles.aclProblem}): the owner is {@code me} or one of
     * {@code builtIns}; no ALLOW entry gives a principal other than those any of {@code rights}. A built-in that could
     * not be resolved is not in {@code builtIns}, so a principal is never exempt by its name alone.
     */
    static String aclProblem(UserPrincipal owner, List<AclEntry> acl, UserPrincipal me,
                             Collection<UserPrincipal> builtIns, Set<AclEntryPermission> rights) {
        if (me == null) {
            return "the current user cannot be determined";
        }
        if (owner == null || !(owner.equals(me) || builtIns.contains(owner))) {
            return "it is owned by " + (owner == null ? "nobody known" : owner.getName())
                    + ", not by the current user";
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
                if (rights.contains(right)) {
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

    /** The current user, resolved on first use only (an options file is rare; probing may create a file). */
    private static final class CurrentUser {
        private static volatile Long uid;

        private CurrentUser() {
        }

        /** The uid of this process, or {@link #UNKNOWN_UID}. */
        static long uid() {
            Long cached = uid;
            if (cached == null) {
                cached = Long.valueOf(lookUpUid());
                uid = cached;
            }
            return cached.longValue();
        }

        private static long lookUpUid() {
            try {
                // JDK API on every Unix JDK (jdk.security.auth since Java 9), used reflectively so that a runtime
                // without it falls back instead of failing to link; as the core's SecureFiles.
                Class<?> type = Class.forName("com.sun.security.auth.module.UnixSystem");
                Object system = type.getConstructor().newInstance();
                return ((Number) type.getMethod("getUid").invoke(system)).longValue();
            } catch (Throwable ignored) {
                // Not available: try /proc.
            }
            try {
                Path self = Paths.get("/proc/self");
                if (Files.exists(self)) {
                    Object value = Files.getAttribute(self, "unix:uid");
                    if (value instanceof Number) {
                        return ((Number) value).longValue();
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Unknown: owner names are compared instead.
            }
            return UNKNOWN_UID;
        }

        /** "Me" for the ACL rule: the owner of a file this process has just created; null if unknown. */
        static UserPrincipal fileOwner() {
            return FileOwner.OWNER;
        }
    }

    /** Resolved on first use only: probing creates a temporary file. */
    private static final class FileOwner {
        static final UserPrincipal OWNER = ownerOfNewFile();

        private FileOwner() {
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
                        // An empty probe file in the temporary directory is harmless.
                    }
                }
            }
        }
    }

    /** The system account and the administrators group, resolved once; those that resolve. */
    private static final class BuiltIns {
        static final List<UserPrincipal> ALL = resolve();

        private BuiltIns() {
        }

        private static List<UserPrincipal> resolve() {
            List<UserPrincipal> out = new ArrayList<UserPrincipal>();
            add(out, "SYSTEM", false);
            add(out, "Administrators", true);
            return Collections.unmodifiableList(out);
        }

        private static void add(List<UserPrincipal> out, String name, boolean group) {
            try {
                UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
                out.add(group ? lookup.lookupPrincipalByGroupName(name) : lookup.lookupPrincipalByName(name));
            } catch (IOException | RuntimeException e) {
                // Not resolvable here: not exempt.
            }
        }
    }
}
