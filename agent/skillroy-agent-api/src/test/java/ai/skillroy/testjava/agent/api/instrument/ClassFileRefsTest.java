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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClassFileRefs}: the constant pool's class entries, every constant kind skipped correctly, malformed refused. */
class ClassFileRefsTest {

    /** Every kind of constant javac writes for Java 8 code: long, double, string, arrays, a lambda, a method ref. */
    static final class Fixture {
        long big = 123456789012345L;
        double precise = 3.5e300;
        float small = 1.25f;
        int odd = 1234567;

        Object strings() {
            return new String[0][];
        }

        Object ints() {
            return new int[2][3];
        }

        Runnable lambda() {
            return () -> System.out.println("fixture");
        }

        Object list() {
            return new ArrayList<Object>();
        }

        String text() {
            return "constant text é中";
        }
    }

    static byte[] bytesOf(Class<?> type) throws IOException {
        String name = type.getName();
        InputStream in = type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class");
        assertTrue(in != null, "class file of " + name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    @Test
    void theClassEntriesOfARealClassFileWithEveryConstantKind() throws IOException {
        byte[] bytes = bytesOf(Fixture.class);
        List<String> refs = ClassFileRefs.classReferences(bytes);
        assertTrue(refs.contains("ai/skillroy/testjava/agent/api/instrument/ClassFileRefsTest$Fixture"), refs.toString());
        assertTrue(refs.contains("java/lang/Object"), refs.toString());
        assertTrue(refs.contains("java/util/ArrayList"), refs.toString());
        assertTrue(refs.contains("java/lang/String"), "the array class [Ljava/lang/String; as its element: " + refs);
        assertTrue(refs.contains("java/lang/System"), "a reference inside the lambda's synthetic body: " + refs);
        for (String ref : refs) {
            assertFalse(ref.startsWith("["), "no array descriptor is reported: " + ref);
            assertFalse(ref.contains("."), "internal form: " + ref);
        }
        assertEquals(refs.size(), new ArrayList<String>(new java.util.LinkedHashSet<String>(refs)).size(),
                "no duplicates");
        assertEquals("ai/skillroy/testjava/agent/api/instrument/ClassFileRefsTest$Fixture",
                ClassFileRefs.thisClass(bytes));
        assertEquals(52, ClassFileRefs.majorVersion(bytes), "the module's tests compile with --release 8");
    }

    @Test
    void aTypeInADescriptorOnlyIsNotAClassReference() throws IOException {
        List<String> refs = ClassFileRefs.classReferences(bytesOf(DescriptorOnly.class));
        assertFalse(refs.contains("java/util/concurrent/TimeUnit"), "a parameter type is a UTF-8 entry: " + refs);
    }

    /** Names {@code TimeUnit} in a signature only. */
    abstract static class DescriptorOnly {
        abstract void sleep(java.util.concurrent.TimeUnit unit);
    }

    @Test
    void malformedBytesAreRefusedWithIllegalArgument() throws IOException {
        byte[] good = bytesOf(Fixture.class);
        assertThrows(IllegalArgumentException.class, () -> ClassFileRefs.classReferences(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ClassFileRefs.classReferences(null));
        byte[] magic = good.clone();
        magic[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> ClassFileRefs.classReferences(magic));
        assertThrows(IllegalArgumentException.class, () -> ClassFileRefs.majorVersion(magic));
        byte[] truncated = Arrays.copyOf(good, 40);
        assertThrows(IllegalArgumentException.class, () -> ClassFileRefs.classReferences(truncated));
        byte[] badTag = good.clone();
        badTag[10] = 99;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassFileRefs.classReferences(badTag));
        assertTrue(e.getMessage().contains("tag 99"), e.getMessage());
    }
}
