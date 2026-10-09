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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the classes a class file refers to (phase6-contracts.md section 3.6, D153): the {@code CONSTANT_Class}
 * entries of its constant pool. Used to check that a restored class holds no reference to {@code Spy} any more
 * (section 3.5), that the spy jar's code reaches nothing but the JDK (section 3.2) and, in the distribution's tests,
 * that the agent home holds no unrelocated third-party reference. A type that appears only in a method or field
 * descriptor is a UTF-8 entry, not a class entry, and is not reported.
 *
 * <p>Stateless; safe from any thread.
 */
public final class ClassFileRefs {

    private static final int MAGIC = 0xCAFEBABE;

    private ClassFileRefs() {
    }

    /**
     * The binary names, in internal form ({@code java/lang/String}), of the classes the constant pool's
     * {@code CONSTANT_Class} entries name, in constant-pool order without duplicates. An array class entry is reported
     * as its element class ({@code [Ljava/lang/String;} as {@code java/lang/String}); an array of a primitive type
     * names no class and is left out.
     *
     * @throws IllegalArgumentException if the bytes are not a well-formed class file header and constant pool
     */
    public static List<String> classReferences(byte[] classFile) {
        Pool pool = Pool.read(classFile);
        Set<String> names = new LinkedHashSet<String>();
        for (int i = 1; i < pool.count; i++) {
            if (pool.tags[i] == 7) {
                String element = elementClass(pool.utf8(pool.refs[i]));
                if (element != null) {
                    names.add(element);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    /**
     * The class file's major version (52 for Java 8, 69 for Java 25).
     *
     * @throws IllegalArgumentException if the bytes do not start with a class file header
     */
    public static int majorVersion(byte[] classFile) {
        if (classFile == null || classFile.length < 10 || readInt(classFile, 0) != MAGIC) {
            throw new IllegalArgumentException("not a class file");
        }
        return readU2(classFile, 6);
    }

    /**
     * The internal name of the class the file defines ({@code this_class}).
     *
     * @throws IllegalArgumentException if the file is not well formed up to {@code this_class}
     */
    public static String thisClass(byte[] classFile) {
        Pool pool = Pool.read(classFile);
        int at = pool.end;
        if (at + 4 > classFile.length) {
            throw new IllegalArgumentException("class file truncated after the constant pool");
        }
        int index = readU2(classFile, at + 2);
        if (index <= 0 || index >= pool.count || pool.tags[index] != 7) {
            throw new IllegalArgumentException("this_class is not a class entry: " + index);
        }
        return pool.utf8(pool.refs[index]);
    }

    private static String elementClass(String name) {
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return name;
        }
        if (name.length() > dims + 2 && name.charAt(dims) == 'L' && name.endsWith(";")) {
            return name.substring(dims + 1, name.length() - 1);
        }
        return null;
    }

    private static int readU2(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** The constant pool: each entry's tag and, for a class entry, the index of its name. */
    private static final class Pool {
        final byte[] bytes;
        final int count;
        final byte[] tags;
        final int[] refs;
        final int[] offsets;
        /** The offset just after the constant pool (the access flags). */
        int end;

        private Pool(byte[] bytes, int count) {
            this.bytes = bytes;
            this.count = count;
            this.tags = new byte[count];
            this.refs = new int[count];
            this.offsets = new int[count];
        }

        static Pool read(byte[] b) {
            if (b == null || b.length < 10) {
                throw new IllegalArgumentException("not a class file: too short");
            }
            if (readInt(b, 0) != MAGIC) {
                throw new IllegalArgumentException("not a class file: bad magic");
            }
            int count = readU2(b, 8);
            if (count == 0) {
                throw new IllegalArgumentException("malformed class file: constant pool count 0");
            }
            Pool pool = new Pool(b, count);
            int at = 10;
            for (int i = 1; i < count; i++) {
                require(b, at, 1);
                int tag = b[at] & 0xFF;
                pool.tags[i] = (byte) tag;
                pool.offsets[i] = at;
                switch (tag) {
                    case 1: // Utf8
                        require(b, at, 3);
                        int length = readU2(b, at + 1);
                        require(b, at, 3 + length);
                        at += 3 + length;
                        break;
                    case 7: // Class
                        require(b, at, 3);
                        pool.refs[i] = readU2(b, at + 1);
                        at += 3;
                        break;
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        require(b, at, 3);
                        at += 3;
                        break;
                    case 15: // MethodHandle
                        require(b, at, 4);
                        at += 4;
                        break;
                    case 3: // Integer
                    case 4: // Float
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        require(b, at, 5);
                        at += 5;
                        break;
                    case 5: // Long
                    case 6: // Double
                        require(b, at, 9);
                        at += 9;
                        i++; // takes two slots
                        break;
                    default:
                        throw new IllegalArgumentException("malformed class file: constant pool tag " + tag
                                + " at entry " + i);
                }
            }
            pool.end = at;
            for (int i = 1; i < count; i++) {
                if (pool.tags[i] == 7) {
                    int name = pool.refs[i];
                    if (name <= 0 || name >= count || pool.tags[name] != 1) {
                        throw new IllegalArgumentException("malformed class file: class entry " + i
                                + " names entry " + name + ", which is not a UTF-8 entry");
                    }
                }
            }
            return pool;
        }

        String utf8(int index) {
            int at = offsets[index];
            int length = readU2(bytes, at + 1);
            return ModifiedUtf8.decode(bytes, at + 3, length);
        }

        private static void require(byte[] b, int at, int n) {
            if (at + n > b.length) {
                throw new IllegalArgumentException("malformed class file: truncated constant pool");
            }
        }
    }

    /** The class file's modified UTF-8 (JVMS 4.4.7). */
    private static final class ModifiedUtf8 {
        static String decode(byte[] b, int from, int length) {
            StringBuilder out = new StringBuilder(length);
            int i = from;
            int end = from + length;
            while (i < end) {
                int c = b[i] & 0xFF;
                if (c < 0x80) {
                    out.append((char) c);
                    i++;
                } else if ((c & 0xE0) == 0xC0 && i + 1 < end) {
                    out.append((char) (((c & 0x1F) << 6) | (b[i + 1] & 0x3F)));
                    i += 2;
                } else if ((c & 0xF0) == 0xE0 && i + 2 < end) {
                    out.append((char) (((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F)));
                    i += 3;
                } else {
                    throw new IllegalArgumentException("malformed class file: bad modified UTF-8");
                }
            }
            return out.toString();
        }
    }
}
