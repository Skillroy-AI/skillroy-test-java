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

import java.io.IOException;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The checks of the agent option {@code byteman.jar} (phase6-contracts.md 7.4 step 1): an absolute path to a readable
 * jar whose manifest names {@code Premain-Class: org.jboss.byteman.agent.Main}, in a directory no other user may write
 * to and itself not writable by others. On a POSIX file system: neither the jar nor its directory is writable by group
 * or others, and each is owned by the current user or by root (a Byteman installed by an administrator is fine). On an
 * ACL file system: the owner is the current user or a built-in administrator principal and no ALLOW entry gives anyone
 * else a right to write, delete or change the ACL or owner. A file system with neither view cannot be checked and is
 * refused. Reads only; never loads a class from the jar.
 */
final class JarCheck {

    static final String MAIN = "org.jboss.byteman.agent.Main";

    private static final Set<AclEntryPermission> WRITE_RIGHTS = EnumSet.of(AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER, AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_NAMED_ATTRS);

    private JarCheck() {
    }

    /** The UNAVAILABLE reason for the option's value, or null when the jar may be loaded. */
    static String problem(String option) {
        if (option == null) {
            return "no byteman.jar configured";
        }
        Path jar;
        try {
            jar = Paths.get(option);
        } catch (RuntimeException e) {
            return "not a Byteman jar: " + option;
        }
        if (!jar.isAbsolute() || !Files.isRegularFile(jar) || !Files.isReadable(jar)) {
            return "not a Byteman jar: " + option;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            String premain = manifest == null ? null : manifest.getMainAttributes().getValue("Premain-Class");
            if (!MAIN.equals(premain == null ? null : premain.trim())) {
                return "not a Byteman jar: " + option;
            }
        } catch (IOException | RuntimeException e) {
            return "not a Byteman jar: " + option;
        }
        Path dir = jar.toAbsolutePath().getParent();
        if (dir == null || writableByOthers(jar) || writableByOthers(dir)) {
            return "byteman.jar is writable by others: " + option;
        }
        return null;
    }

    /** True when someone but the owner may write to {@code path}, or that cannot be told. */
    static boolean writableByOthers(Path path) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                PosixFileAttributes a = posix.readAttributes();
                Set<PosixFilePermission> p = a.permissions();
                if (p.contains(PosixFilePermission.GROUP_WRITE) || p.contains(PosixFilePermission.OTHERS_WRITE)) {
                    return true;
                }
                String owner = a.owner().getName();
                return !(owner.equals(System.getProperty("user.name")) || owner.equals("root") || isMine(path));
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) {
                return true;
            }
            UserPrincipal owner = acl.getOwner();
            for (AclEntry entry : acl.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW || entry.principal().equals(owner) || builtIn(entry.principal())) {
                    continue;
                }
                for (AclEntryPermission right : entry.permissions()) {
                    if (WRITE_RIGHTS.contains(right)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** The owner of {@code path} is the owner of a file this process creates (uid comparison without a name). */
    private static boolean isMine(Path path) {
        Path probe = null;
        try {
            probe = Files.createTempFile("skillroy-byteman-owner-", ".tmp");
            return Files.getOwner(probe).equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // a temporary file left behind
                }
            }
        }
    }

    private static boolean builtIn(UserPrincipal principal) {
        String name = principal.getName();
        int slash = name.lastIndexOf('\\');
        String bare = slash >= 0 ? name.substring(slash + 1) : name;
        return bare.equalsIgnoreCase("SYSTEM") || bare.equalsIgnoreCase("Administrators")
                || bare.equalsIgnoreCase("TrustedInstaller");
    }
}
