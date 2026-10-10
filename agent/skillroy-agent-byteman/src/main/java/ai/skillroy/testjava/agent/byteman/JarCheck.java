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

import ai.skillroy.testjava.agent.api.instrument.JarTrust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collection;
import java.util.List;
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
 * values). A file system with neither view cannot be checked and is refused. Reads only; never loads a class from the
 * jar. The ownership and ACL judgement is the shared {@link JarTrust} rule of every jar the agent loads from an
 * operator's path (Phase 6 security fix, pass 2, P2-L1-F5).
 */
final class JarCheck {

    static final String MAIN = "org.jboss.byteman.agent.Main";

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

    /**
     * True when someone but the owner may write to {@code path} (a real path), or that cannot be told: the shared rule
     * of every jar the agent loads from an operator's path ({@link JarTrust#writableByOthers}, Phase 6 security fix,
     * pass 2, P2-L1-F5; the judgement is unchanged, it moved there).
     */
    static boolean writableByOthers(Path path) {
        return JarTrust.writableByOthers(path);
    }

    /** The pure ACL judgement: {@link JarTrust#aclProblem} (moved there by P2-L1-F5, unchanged). */
    static String aclProblem(UserPrincipal owner, List<AclEntry> acl, UserPrincipal me,
                             Collection<UserPrincipal> builtIns) {
        return JarTrust.aclProblem(owner, acl, me, builtIns);
    }
}
