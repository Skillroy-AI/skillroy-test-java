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

package ai.skillroy.testjava.spikes.bs14.probe;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The binary names of a class file's CONSTANT_Class entries (the shape of the contract's ClassFileRefs). */
final class ClassRefs {

    private ClassRefs() {
    }

    static List<String> classReferences(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IllegalArgumentException("not a class file");
        }
        in.readUnsignedShort();
        int major = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf8[i] = in.readUTF(); break;
                case 7: classIndex[i] = in.readUnsignedShort(); break;
                case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                default: throw new IllegalArgumentException("constant pool tag " + tag + " at " + i);
            }
        }
        List<String> names = new ArrayList<String>();
        names.add("#major=" + major);
        for (int i = 1; i < count; i++) {
            if (classIndex[i] != 0) {
                names.add(utf8[classIndex[i]]);
            }
        }
        return names;
    }
}
