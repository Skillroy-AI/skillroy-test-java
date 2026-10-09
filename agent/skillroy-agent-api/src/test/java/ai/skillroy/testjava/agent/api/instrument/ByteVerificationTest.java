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

import java.io.IOException;
import java.util.Arrays;

import static ai.skillroy.testjava.agent.api.instrument.ClassFileRefsTest.bytesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link ByteVerification#compare}: identical, another transformer's change, and a residue under the prefix. */
class ByteVerificationTest {

    private static final String SPY = "ai/skillroy/testjava/agent/spy/";

    /** Stands for {@code Spy}: a class the "restored" class still references. */
    static final class Marker {
        static Object enter() {
            return null;
        }
    }

    /** Stands for a restored class that still carries advice. */
    static final class Advised {
        Object call() {
            return Marker.enter();
        }
    }

    @Test
    void equalBytesWithoutAReferenceAreIdentical() throws IOException {
        byte[] first = bytesOf(Advised.class);
        assertEquals(ByteVerification.IDENTICAL, ByteVerification.compare(first, first.clone(), SPY));
    }

    @Test
    void differentBytesWithoutAReferenceAreAForeignChange() throws IOException {
        byte[] first = bytesOf(Advised.class);
        byte[] other = bytesOf(Marker.class);
        assertEquals(ByteVerification.FOREIGN_CHANGE, ByteVerification.compare(first, other, SPY));
        byte[] patched = Arrays.copyOf(first, first.length + 1);
        assertEquals(ByteVerification.FOREIGN_CHANGE, ByteVerification.compare(first, patched, SPY),
                "a trailing byte the constant pool never reaches is still a difference");
    }

    @Test
    void aReferenceUnderThePrefixIsResidueEvenWhenTheBytesAreEqual() throws IOException {
        byte[] advised = bytesOf(Advised.class);
        String prefix = "ai/skillroy/testjava/agent/api/instrument/ByteVerificationTest$Marker";
        assertEquals(ByteVerification.RESIDUE, ByteVerification.compare(bytesOf(Marker.class), advised, prefix));
        assertEquals(ByteVerification.RESIDUE, ByteVerification.compare(advised, advised, prefix));
        assertEquals(ByteVerification.RESIDUE, ByteVerification.compare(advised, advised, prefix.replace('/', '.')),
                "a dotted prefix is read as slashes");
    }

    @Test
    void badInputIsRefused() throws IOException {
        byte[] first = bytesOf(Advised.class);
        assertThrows(NullPointerException.class, () -> ByteVerification.compare(null, first, SPY));
        assertThrows(NullPointerException.class, () -> ByteVerification.compare(first, first, null));
        assertThrows(IllegalArgumentException.class, () -> ByteVerification.compare(first, new byte[] {1, 2}, SPY));
    }
}
