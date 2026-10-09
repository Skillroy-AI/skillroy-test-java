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

/**
 * The byte comparison of a restore (phase6-contracts.md section 3.5, D122, D153): the bytes a class had at its first
 * transform (as the JVM reconstitutes them, never the class file) against the bytes the restoring retransformation
 * installed. The caller reports the count of foreign transform events beside it: on Java 8 a restore reverts another
 * agent's redefinition and the bytes compare equal, so that count is the only witness there.
 */
public enum ByteVerification {
    /** The bytes after the restore equal the captured bytes, byte for byte. */
    IDENTICAL,
    /** They differ, and hold no reference under the residue prefix: another transformer is present (DEGRADED). */
    FOREIGN_CHANGE,
    /** The bytes after the restore still reference a class under the residue prefix: the restore failed (FAILED). */
    RESIDUE;

    /**
     * Compares the bytes before (the first transform's) with the bytes after a restore.
     *
     * @param residueClassPrefix the internal-name prefix whose presence among the class references of {@code after}
     *     means residue ({@code ai/skillroy/testjava/agent/spy/}); dots are read as slashes
     * @return {@link #RESIDUE} if {@code after} references a class under the prefix, else {@link #IDENTICAL} if the
     *     arrays are equal, else {@link #FOREIGN_CHANGE}
     * @throws IllegalArgumentException if {@code after} is not a well-formed class file
     * @throws NullPointerException if an argument is null
     */
    public static ByteVerification compare(byte[] before, byte[] after, String residueClassPrefix) {
        if (before == null || after == null || residueClassPrefix == null) {
            throw new NullPointerException("before, after and residueClassPrefix are required");
        }
        String prefix = residueClassPrefix.replace('.', '/');
        for (String reference : ClassFileRefs.classReferences(after)) {
            if (reference.startsWith(prefix)) {
                return RESIDUE;
            }
        }
        return Arrays.equals(before, after) ? IDENTICAL : FOREIGN_CHANGE;
    }
}
