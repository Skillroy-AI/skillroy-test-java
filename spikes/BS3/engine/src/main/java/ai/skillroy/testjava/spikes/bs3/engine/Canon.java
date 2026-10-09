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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.IdentityHashMap;
import java.util.Map;

import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * A canonical text of a class file that does not depend on constant-pool layout, member order or debug attributes:
 * header, then fields and methods sorted by name and descriptor, per method every instruction with its operands
 * resolved (labels numbered in order). Two class files of the same
 * class are "canonically equal" when their texts are; the byte check reports both comparisons.
 */
final class Canon {

    private Canon() {
    }

    static String text(byte[] bytes) {
        final StringBuilder b = new StringBuilder();
        final java.util.TreeMap<String, StringBuilder> members = new java.util.TreeMap<String, StringBuilder>();
        new ClassReader(bytes).accept(new ClassVisitor(OpenedClassReader.ASM_API) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                b.append("class ").append(version).append(' ').append(access).append(' ').append(name).append(' ')
                        .append(superName).append(' ').append(java.util.Arrays.toString(interfaces)).append('\n');
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                b.append("annotation ").append(descriptor).append('\n');
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                                           Object value) {
                StringBuilder f = new StringBuilder();
                f.append("field ").append(access).append(' ').append(name).append(' ').append(descriptor)
                        .append(' ').append(value).append('\n');
                members.put("f " + name + descriptor, f);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                StringBuilder m = new StringBuilder();
                m.append("method ").append(access).append(' ').append(name).append(descriptor).append('\n');
                members.put("m " + name + descriptor, m);
                return new CanonMethod(m);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (StringBuilder m : members.values()) {
            b.append(m);
        }
        return b.toString();
    }

    static String hash(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text(bytes).getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                h.append(String.format("%02x", d[i]));
            }
            return h.toString();
        } catch (Exception e) {
            return "error:" + e;
        }
    }

    private static final class CanonMethod extends MethodVisitor {
        private final StringBuilder b;
        private final Map<Label, Integer> labels = new IdentityHashMap<Label, Integer>();

        CanonMethod(StringBuilder b) {
            super(OpenedClassReader.ASM_API);
            this.b = b;
        }

        private String l(Label label) {
            Integer id = labels.get(label);
            if (id == null) {
                id = labels.size();
                labels.put(label, id);
            }
            return "L" + id;
        }

        private void line(Object... parts) {
            b.append(' ');
            for (Object p : parts) {
                b.append(' ').append(p);
            }
            b.append('\n');
        }

        @Override
        public void visitInsn(int opcode) {
            line(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            line(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            line(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            line(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            line(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            line(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
            line("indy", name, descriptor, bsm, java.util.Arrays.toString(args));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            line(opcode, l(label));
        }

        @Override
        public void visitLabel(Label label) {
            line("label", l(label));
        }

        @Override
        public void visitLdcInsn(Object value) {
            line("ldc", value.getClass().getSimpleName(), value);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            line("iinc", var, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... ls) {
            StringBuilder s = new StringBuilder();
            for (Label x : ls) {
                s.append(l(x)).append(',');
            }
            line("tableswitch", min, max, l(dflt), s);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] ls) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < keys.length; i++) {
                s.append(keys[i]).append(':').append(l(ls[i])).append(',');
            }
            line("lookupswitch", l(dflt), s);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dims) {
            line("multianewarray", descriptor, dims);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            line("try", l(start), l(end), l(handler), type);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            line("maxs", maxStack, maxLocals);
        }
    }
}
