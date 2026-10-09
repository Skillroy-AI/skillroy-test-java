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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The byte comparison of a restore (phase6-contracts.md section 3.5, D122, D153): the bytes a class had at its first
 * transform (as the JVM reconstitutes them, never the class file) against the bytes the restoring retransformation
 * installed. The caller reports the count of foreign transform events beside it: on Java 8 a restore reverts another
 * agent's redefinition and the bytes compare equal, so that count is the only witness there.
 *
 * <p>A residue is a reference under the prefix that the restored bytes hold <b>and the first bytes did not</b>: when a
 * class was redefined after an earlier transformation, the JVM reconstitutes it with the constant-pool entries it
 * merged across the redefinitions, so its first bytes may already name the prefix without any code using it; restoring
 * those bytes exactly is not a residue.
 */
public enum ByteVerification {
    /** The bytes after the restore equal the captured bytes, byte for byte. */
    IDENTICAL,
    /** They differ, and add no reference under the residue prefix: another transformer is present (DEGRADED). */
    FOREIGN_CHANGE,
    /** The bytes after the restore reference a class under the residue prefix that the first bytes did not (FAILED). */
    RESIDUE;

    /**
     * Compares the bytes before (the first transform's) with the bytes after a restore.
     *
     * @param residueClassPrefix the internal-name prefix whose appearance among the class references of {@code after}
     *     (and not among those of {@code before}) means residue ({@code ai/skillroy/testjava/agent/spy/}); dots are
     *     read as slashes
     * @return {@link #RESIDUE} if {@code after} references a class under the prefix that {@code before} does not
     *     reference, else {@link #IDENTICAL} if the arrays are equal, else {@link #FOREIGN_CHANGE}
     * @throws IllegalArgumentException if {@code before} or {@code after} is not a well-formed class file
     * @throws NullPointerException if an argument is null
     */
    public static ByteVerification compare(byte[] before, byte[] after, String residueClassPrefix) {
        if (before == null || after == null || residueClassPrefix == null) {
            throw new NullPointerException("before, after and residueClassPrefix are required");
        }
        String prefix = residueClassPrefix.replace('.', '/');
        Set<String> already = new HashSet<String>();
        for (String reference : ClassFileRefs.classReferences(before)) {
            if (reference.startsWith(prefix)) {
                already.add(reference);
            }
        }
        for (String reference : ClassFileRefs.classReferences(after)) {
            if (reference.startsWith(prefix) && !already.contains(reference)) {
                return RESIDUE;
            }
        }
        return Arrays.equals(before, after) ? IDENTICAL : FOREIGN_CHANGE;
    }
}
