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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A small class-file reader for the byte check (BS3 item 3): the constant pool with every entry resolved to text, the
 * members in file order with their attribute names, and the code bytes per method. Enough to say exactly how two
 * class files of the same class differ (constant-pool order, dropped or reordered attributes, method order).
 */
final class ClassFacts {

    /** A field or method. */
    static final class Member {
        int access;
        String name;
        String desc;
        final List<String> attrs = new ArrayList<String>();
        final List<String> codeAttrs = new ArrayList<String>();
        int codeLength = -1;
        int codeHash;

        String key() {
            return name + desc;
        }
    }

    int minor;
    int major;
    int size;
    final List<String> cp = new ArrayList<String>();
    int access;
    String thisClass;
    String superClass;
    final List<String> interfaces = new ArrayList<String>();
    final List<Member> fields = new ArrayList<Member>();
    final List<Member> methods = new ArrayList<Member>();
    final List<String> classAttrs = new ArrayList<String>();

    private int[] tags;
    private int[][] refs;
    private String[] utf;
    private Object[] values;

    static ClassFacts parse(byte[] bytes) {
        try {
            return new ClassFacts().read(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a class file: " + e, e);
        }
    }

    private ClassFacts read(byte[] bytes) throws IOException {
        size = bytes.length;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("bad magic");
        }
        minor = in.readUnsignedShort();
        major = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        tags = new int[count];
        refs = new int[count][];
        utf = new String[count];
        values = new Object[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1: utf[i] = in.readUTF(); break;
                case 3: values[i] = in.readInt(); break;
                case 4: values[i] = in.readFloat(); break;
                case 5: values[i] = in.readLong(); i++; break;
                case 6: values[i] = in.readDouble(); i++; break;
                case 7: case 8: case 16: case 19: case 20: refs[i] = new int[] {in.readUnsignedShort()}; break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    refs[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()}; break;
                case 15: refs[i] = new int[] {in.readUnsignedByte(), in.readUnsignedShort()}; break;
                default: throw new IOException("constant pool tag " + tag + " at #" + i);
            }
        }
        for (int i = 1; i < count; i++) {
            cp.add(tags[i] == 0 ? "(second slot)" : text(i));
        }
        access = in.readUnsignedShort();
        thisClass = text(in.readUnsignedShort());
        int sup = in.readUnsignedShort();
        superClass = sup == 0 ? "-" : text(sup);
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            interfaces.add(text(in.readUnsignedShort()));
        }
        readMembers(in, fields);
        readMembers(in, methods);
        int ac = in.readUnsignedShort();
        for (int i = 0; i < ac; i++) {
            String an = utf[in.readUnsignedShort()];
            int len = in.readInt();
            in.skipBytes(len);
            classAttrs.add(an);
        }
        return this;
    }

    private void readMembers(DataInputStream in, List<Member> into) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            Member m = new Member();
            m.access = in.readUnsignedShort();
            m.name = utf[in.readUnsignedShort()];
            m.desc = utf[in.readUnsignedShort()];
            int ac = in.readUnsignedShort();
            for (int a = 0; a < ac; a++) {
                String an = utf[in.readUnsignedShort()];
                int len = in.readInt();
                m.attrs.add(an);
                if ("Code".equals(an)) {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    int codeLength = in.readInt();
                    byte[] code = new byte[codeLength];
                    in.readFully(code);
                    m.codeLength = codeLength;
                    m.codeHash = Arrays.hashCode(code);
                    int ex = in.readUnsignedShort();
                    in.skipBytes(ex * 8);
                    int sub = in.readUnsignedShort();
                    for (int s = 0; s < sub; s++) {
                        m.codeAttrs.add(utf[in.readUnsignedShort()]);
                        in.skipBytes(in.readInt());
                    }
                } else {
                    in.skipBytes(len);
                }
            }
            into.add(m);
        }
    }

    private String text(int i) {
        if (i <= 0 || i >= tags.length) {
            return "#" + i;
        }
        switch (tags[i]) {
            case 1: return "Utf8 " + utf[i];
            case 3: case 4: case 5: case 6: return "Const " + values[i];
            case 7: return "Class " + utf[refs[i][0]];
            case 8: return "String " + utf[refs[i][0]];
            case 16: return "MethodType " + utf[refs[i][0]];
            case 19: return "Module " + utf[refs[i][0]];
            case 20: return "Package " + utf[refs[i][0]];
            case 9: case 10: case 11:
                return (tags[i] == 9 ? "Field " : tags[i] == 10 ? "Method " : "IMethod ")
                        + utf[refs[refs[i][0]][0]] + "." + nat(refs[i][1]);
            case 12: return "NameAndType " + nat(i);
            case 15: return "MethodHandle " + refs[i][0] + " " + text(refs[i][1]);
            case 17: case 18: return (tags[i] == 17 ? "Dynamic " : "InvokeDynamic ") + refs[i][0] + " "
                    + nat(refs[i][1]);
            default: return "?" + tags[i];
        }
    }

    private String nat(int i) {
        return utf[refs[i][0]] + ":" + utf[refs[i][1]];
    }

    private static List<String> keys(List<Member> members) {
        List<String> out = new ArrayList<String>();
        for (Member m : members) {
            out.add(m.key());
        }
        return out;
    }

    private static Member find(List<Member> members, String key) {
        for (Member m : members) {
            if (m.key().equals(key)) {
                return m;
            }
        }
        return null;
    }

    /** @return lines that say how {@code b} differs from {@code a}; a single "identical" line when the bytes match */
    static List<String> diff(String la, byte[] ba, String lb, byte[] bb) {
        List<String> out = new ArrayList<String>();
        if (Arrays.equals(ba, bb)) {
            out.add("bytes identical (" + ba.length + " bytes)");
            return out;
        }
        ClassFacts a = parse(ba);
        ClassFacts b = parse(bb);
        out.add("bytes differ: " + la + " " + a.size + " bytes, " + lb + " " + b.size + " bytes");
        if (a.major != b.major || a.minor != b.minor) {
            out.add("version: " + a.major + "." + a.minor + " vs " + b.major + "." + b.minor);
        }
        int firstDiff = -1;
        for (int i = 0; i < Math.min(a.cp.size(), b.cp.size()); i++) {
            if (!a.cp.get(i).equals(b.cp.get(i))) {
                firstDiff = i + 1;
                break;
            }
        }
        Set<String> sa = new LinkedHashSet<String>(a.cp);
        Set<String> sb = new LinkedHashSet<String>(b.cp);
        Set<String> onlyA = new LinkedHashSet<String>(sa);
        onlyA.removeAll(sb);
        Set<String> onlyB = new LinkedHashSet<String>(sb);
        onlyB.removeAll(sa);
        String cpLine = "constant pool: " + a.cp.size() + " vs " + b.cp.size() + " entries; ";
        if (firstDiff < 0 && a.cp.size() == b.cp.size()) {
            cpLine += "same entries in the same order";
        } else {
            cpLine += (firstDiff < 0 ? "common prefix equal, " + (b.cp.size() - a.cp.size()) + " entries appended"
                    : "first difference at #" + firstDiff + " (" + la + ": " + clip(a.cp.get(firstDiff - 1))
                    + " | " + lb + ": " + clip(firstDiff - 1 < b.cp.size() ? b.cp.get(firstDiff - 1) : "-") + ")")
                    + "; only in " + la + ": " + onlyA.size() + sample(onlyA) + "; only in " + lb + ": "
                    + onlyB.size() + sample(onlyB);
        }
        out.add(cpLine);
        if (a.access != b.access || !a.superClass.equals(b.superClass) || !a.interfaces.equals(b.interfaces)) {
            out.add("header: access " + a.access + "/" + b.access + ", super " + a.superClass + "/" + b.superClass
                    + ", interfaces " + a.interfaces + "/" + b.interfaces);
        }
        compareMembers(out, "field", a.fields, b.fields, la, lb);
        compareMembers(out, "method", a.methods, b.methods, la, lb);
        if (!a.classAttrs.equals(b.classAttrs)) {
            out.add("class attributes: " + la + " " + a.classAttrs + " | " + lb + " " + b.classAttrs);
        }
        return out;
    }

    private static void compareMembers(List<String> out, String kind, List<Member> ma, List<Member> mb, String la,
                                       String lb) {
        List<String> ka = keys(ma);
        List<String> kb = keys(mb);
        if (!ka.equals(kb)) {
            if (new HashSet<String>(ka).equals(new HashSet<String>(kb))) {
                out.add(kind + " order differs: " + la + " " + ka + " | " + lb + " " + kb);
            } else {
                out.add(kind + "s differ: " + la + " " + ka + " | " + lb + " " + kb);
            }
        }
        int codeDiffs = 0;
        for (Member x : ma) {
            Member y = find(mb, x.key());
            if (y == null) {
                continue;
            }
            if (x.access != y.access || !x.attrs.equals(y.attrs) || !x.codeAttrs.equals(y.codeAttrs)) {
                out.add(kind + " " + x.key() + ": access " + x.access + "/" + y.access + ", attributes " + la + " "
                        + x.attrs + x.codeAttrs + " | " + lb + " " + y.attrs + y.codeAttrs);
            }
            if (x.codeLength != y.codeLength || x.codeHash != y.codeHash) {
                codeDiffs++;
            }
        }
        if (codeDiffs > 0) {
            out.add(kind + "s whose code bytes differ: " + codeDiffs + " of " + ma.size());
        }
    }

    private static String clip(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    private static String sample(Set<String> s) {
        if (s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(" e.g. [");
        int i = 0;
        for (String x : s) {
            if (i++ == 3) {
                b.append(", ...");
                break;
            }
            b.append(i > 1 ? ", " : "").append(clip(x));
        }
        return b.append(']').toString();
    }

    /** One line: version, size, constant-pool entries, members, attributes; for the report. */
    static String summary(byte[] bytes) {
        ClassFacts f = parse(bytes);
        return "v" + f.major + " " + f.size + " B, cp " + f.cp.size() + ", " + f.fields.size() + " fields, "
                + f.methods.size() + " methods, class attributes " + f.classAttrs;
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
