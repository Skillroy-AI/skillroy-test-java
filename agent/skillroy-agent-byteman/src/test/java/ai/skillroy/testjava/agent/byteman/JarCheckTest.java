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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The checks of {@code byteman.jar} (phase6-contracts.md 7.4 step 1). Fix wave: a symbolic link is checked at the jar it
 * names and that jar is the one appended (L2-S2); on an ACL file system the owner must be the current user or a
 * built-in administrator principal resolved as such, never a name that merely reads like one (L2-F6). The ACL
 * judgement is tested on built ACL values (no ACL file system here; the Windows branch is not verified on a host).
 */
class JarCheckTest {

    @TempDir
    Path tmp;

    /** A principal by name, as an ACL file system would hand it over. */
    static final class Who implements UserPrincipal {
        private final String name;

        Who(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Who && ((Who) o).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final Who ME = new Who("HOST\\lender");
    static final Who OTHER = new Who("HOST\\borrower");
    static final Who SYSTEM = new Who("NT AUTHORITY\\SYSTEM");
    static final Who ADMINISTRATORS = new Who("BUILTIN\\Administrators");
    static final List<UserPrincipal> BUILT_INS = Arrays.<UserPrincipal>asList(SYSTEM, ADMINISTRATORS);

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... rights) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(EnumSet.copyOf(Arrays.asList(rights))).build();
    }

    private static AclEntry deny(UserPrincipal who, AclEntryPermission... rights) {
        return AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(who)
                .setPermissions(EnumSet.copyOf(Arrays.asList(rights))).build();
    }

    private static Path jarWithManifest(Path dir, String premain) throws Exception {
        Files.createDirectories(dir);
        Path jar = dir.resolve("byteman.jar");
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(new Attributes.Name("Premain-Class"), premain);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()), m)) {
            out.flush();
        }
        return jar;
    }

    @Test
    void aLinkIsCheckedAtTheJarItNamesAndThatJarIsAppended() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX only here");
        Path shared = tmp.resolve("shared");
        Path jar = jarWithManifest(shared, JarCheck.MAIN);
        Path safe = Files.createDirectories(tmp.resolve("safe"));
        Files.setPosixFilePermissions(safe, PosixFilePermissions.fromString("rwx------"));
        Path link = Files.createSymbolicLink(safe.resolve("byteman.jar"), jar);

        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertEquals("byteman.jar is writable by others: " + link, JarCheck.problem(link.toString()),
                "the directory others may write to is the jar's, not the link's");

        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertNull(JarCheck.problem(link.toString()));
        assertEquals(jar.toRealPath(), JarCheck.check(link.toString()).path, "the jar appended is the real file");
    }

    @Test
    void theAclOwnerIsTheCurrentUserOrABuiltInAdministrator() {
        List<AclEntry> ownerOnly = Collections.singletonList(allow(OTHER, AclEntryPermission.values()));
        assertEquals("it is owned by HOST\\borrower, not by the current user or an administrator",
                JarCheck.aclProblem(OTHER, ownerOnly, ME, BUILT_INS),
                "another user's jar whose ACL grants writing to that owner only is refused");
        assertNull(JarCheck.aclProblem(ME, Arrays.asList(allow(ME, AclEntryPermission.values()),
                allow(SYSTEM, AclEntryPermission.values()), allow(ADMINISTRATORS, AclEntryPermission.values())), ME,
                BUILT_INS));
        assertNull(JarCheck.aclProblem(ADMINISTRATORS, Arrays.asList(allow(ADMINISTRATORS,
                AclEntryPermission.values()), allow(OTHER, AclEntryPermission.READ_DATA)), ME, BUILT_INS),
                "a jar an administrator installed, readable by others");
        assertNull(JarCheck.aclProblem(ME, Arrays.asList(deny(OTHER, AclEntryPermission.WRITE_DATA)), ME, BUILT_INS),
                "a DENY entry grants nothing");
        assertEquals("the current user cannot be determined",
                JarCheck.aclProblem(ME, Collections.<AclEntry>emptyList(), null, BUILT_INS));
        assertEquals("its ACL cannot be read", JarCheck.aclProblem(ME, null, ME, BUILT_INS));
    }

    @Test
    void anAclEntryGivingAnyoneElseAWriteRightIsRefused() {
        assertEquals("its ACL allows HOST\\borrower APPEND_DATA, WRITE_DATA", JarCheck.aclProblem(ME, Arrays.asList(
                allow(ME, AclEntryPermission.values()), allow(OTHER, AclEntryPermission.READ_DATA,
                        AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA)), ME, BUILT_INS));
        assertEquals("its ACL allows OTHERDOMAIN\\Administrators WRITE_OWNER", JarCheck.aclProblem(ME,
                Arrays.asList(allow(new Who("OTHERDOMAIN\\Administrators"), AclEntryPermission.WRITE_OWNER)), ME,
                BUILT_INS), "a principal is exempt as the resolved built-in, never by its bare name");
        assertEquals("it is owned by OTHERDOMAIN\\SYSTEM, not by the current user or an administrator",
                JarCheck.aclProblem(new Who("OTHERDOMAIN\\SYSTEM"), Collections.<AclEntry>emptyList(), ME,
                        BUILT_INS));
    }
}
