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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link JarTrust} (Phase 6 security fix, pass 2, P2-L1-F5): the one provenance rule of the jars the agent loads from
 * an operator's path. The POSIX branch on this host; the ACL judgement on built values (no Windows host here; the
 * Byteman module's {@code JarCheckTest} covers it further through the delegation).
 */
class JarTrustTest {

    @TempDir
    Path tmp;

    private static void chmod(Path path, String mode) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }

    @Test
    void aJarAndItsDirectoryMustBeWritableByTheirOwnerOnly() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        Path dir = Files.createDirectory(tmp.resolve("lib"));
        Path jar = Files.write(dir.resolve("module.jar"), new byte[] {'P', 'K'});
        chmod(jar, "rw-r--r--");
        chmod(dir, "rwxr-xr-x");
        assertNull(JarTrust.problem(jar), "the operator's own jar in the operator's own directory");

        chmod(dir, "rwxrwxr-x");
        assertEquals(JarTrust.WRITABLE_BY_OTHERS, JarTrust.problem(jar), "a group-writable directory");
        chmod(dir, "rwxr-xrwx");
        assertEquals(JarTrust.WRITABLE_BY_OTHERS, JarTrust.problem(jar), "an other-writable directory");
        chmod(dir, "rwxr-xr-x");
        chmod(jar, "rw-rw-r--");
        assertEquals(JarTrust.WRITABLE_BY_OTHERS, JarTrust.problem(jar), "a group-writable jar");
        chmod(jar, "rw-r--r--");

        Path open = Files.createDirectory(tmp.resolve("open"));
        chmod(open, "rwxrwxrwx");
        Path viaOpen = Files.createSymbolicLink(open.resolve("module.jar"), jar);
        assertEquals(JarTrust.WRITABLE_BY_OTHERS, JarTrust.problem(viaOpen),
                "a link in a directory others may write to can be re-pointed");
        Path elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));
        Path target = Files.write(open.resolve("real.jar"), new byte[] {'P', 'K'});
        Path intoOpen = Files.createSymbolicLink(elsewhere.resolve("module.jar"), target);
        assertEquals(JarTrust.WRITABLE_BY_OTHERS, JarTrust.problem(intoOpen), "the real jar's directory counts");
        Path safeLink = Files.createSymbolicLink(elsewhere.resolve("safe.jar"), jar);
        assertNull(JarTrust.problem(safeLink));

        assertEquals(JarTrust.NOT_A_FILE, JarTrust.problem(dir.resolve("missing.jar")));
        assertEquals(JarTrust.NOT_A_FILE, JarTrust.problem(dir));
    }

    @Test
    void theAclJudgementOnBuiltValues() {
        UserPrincipal me = new Who("HOST\\alice");
        UserPrincipal other = new Who("HOST\\borrower");
        UserPrincipal admins = new Who("BUILTIN\\Administrators");
        List<UserPrincipal> builtIns = Collections.singletonList(admins);
        assertNull(JarTrust.aclProblem(me, Arrays.asList(allow(me, AclEntryPermission.values()),
                allow(other, AclEntryPermission.READ_DATA)), me, builtIns));
        assertNull(JarTrust.aclProblem(admins, Collections.singletonList(allow(admins, AclEntryPermission.values())),
                me, builtIns), "installed by an administrator");
        assertEquals("its ACL allows HOST\\borrower DELETE_CHILD", JarTrust.aclProblem(me,
                Collections.singletonList(allow(other, AclEntryPermission.DELETE_CHILD)), me, builtIns));
        assertEquals("it is owned by HOST\\borrower, not by the current user or an administrator",
                JarTrust.aclProblem(other, Collections.<AclEntry>emptyList(), me, builtIns));
    }

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... rights) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who).setPermissions(rights).build();
    }

    /** A principal for built ACL values, equal by name. */
    private static final class Who implements UserPrincipal {
        private final String name;

        Who(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Who && ((Who) other).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }
}
