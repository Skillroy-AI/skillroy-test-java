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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.protocol.Volumes;

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
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The checks of the agent option {@code byteman.jar} (phase6-contracts.md 7.4 step 1): an absolute path to a readable
 * jar whose manifest names {@code Premain-Class: org.jboss.byteman.agent.Main}, in a directory no other user may write
 * to and itself not writable by others. A symbolic link is resolved first: the checks apply to the real jar and its
 * real directory, and the real jar is the one the module appends (fix wave, L2-S2). On a POSIX file system: neither
 * the jar nor its directory is writable by group or others, and each is owned by the current user or by root (a
 * Byteman installed by an administrator is fine). On an ACL file system: the owner is the current user (the owner of a
 * file this process creates) or the system account, the administrators group or the installer service, each resolved
 * as a principal and never matched by a bare name, and no ALLOW entry gives anyone else a right to write, delete or
 * change the ACL or owner (fix wave, L2-F6; not verified on a Windows host: the judgement is tested on built ACL
 * values). A file system with neither view cannot be checked and is refused; the view is the volume's
 * ({@link Volumes#protection}), so a jar or directory on a FAT or exFAT volume, which anybody with the volume may write,
 * is "writable by others" (Phase 6 security fix, pass 2, P2-L7-F1). Reads only; never loads a class from the jar.
 */
final class JarCheck {

    static final String MAIN = "org.jboss.byteman.agent.Main";

    private static final Set<AclEntryPermission> WRITE_RIGHTS = EnumSet.of(AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_NAMED_ATTRS);

    private JarCheck() {
    }

    /** What the check found: the UNAVAILABLE reason, or the real path of the jar to append. */
    static final class Result {
        final String problem;
        final Path path;

        private Result(String problem, Path path) {
            this.problem = problem;
            this.path = path;
        }
    }

    /** The UNAVAILABLE reason for the option's value, or null when the jar may be loaded. */
    static String problem(String option) {
        return check(option).problem;
    }

    /** The checks of 7.4 step 1 on the real jar the option names; the result's path is that jar's real path. */
    static Result check(String option) {
        if (option == null) {
            return new Result("no byteman.jar configured", null);
        }
        String notByteman = "not a Byteman jar: " + option;
        Path jar;
        try {
            Path given = Paths.get(option);
            if (!given.isAbsolute()) {
                return new Result(notByteman, null);
            }
            jar = given.toRealPath();
        } catch (IOException | RuntimeException e) {
            return new Result(notByteman, null);
        }
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(jar)) {
            return new Result(notByteman, null);
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            String premain = manifest == null ? null : manifest.getMainAttributes().getValue("Premain-Class");
            if (!MAIN.equals(premain == null ? null : premain.trim())) {
                return new Result(notByteman, null);
            }
        } catch (IOException | RuntimeException e) {
            return new Result(notByteman, null);
        }
        Path dir = jar.getParent();
        if (dir == null || writableByOthers(jar) || writableByOthers(dir)) {
            return new Result("byteman.jar is writable by others: " + option, null);
        }
        return new Result(null, jar);
    }

    /** True when someone but the owner may write to {@code path} (a real path), or that cannot be told. */
    static boolean writableByOthers(Path path) {
        try {
            if (Volumes.protection(path) == Volumes.Protection.NONE) {
                return true;
            }
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
     * The pure ACL judgement (the shape of the agent core's {@code SecureFiles.aclProblem}, with the owners a Byteman
     * jar may have): why an object with this owner and ACL must not be trusted, or null. The owner must be {@code me}
     * or one of {@code builtIns}; no ALLOW entry may give a principal other than those a write right. A built-in that
     * could not be resolved is not in {@code builtIns}, so a principal is never exempt by its name alone.
     */
    static String aclProblem(UserPrincipal owner, List<AclEntry> acl, UserPrincipal me,
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
                probe = Files.createTempFile("skillroy-byteman-owner-", ".tmp");
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
