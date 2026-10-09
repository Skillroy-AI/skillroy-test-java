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

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * Generates the classes of the size sweep (BS3 item 4) with the shaded ASM: a class with one small method to hook,
 * {@code long settle(long)}, plus either many small methods that each return their own string constant (the constant
 * pool grows with the method count) or a few methods with long straight-line bodies (the code grows, the pool does
 * not). Java 8 class files, no frames needed (no branches).
 */
final class ClassGen {

    /** A loader that defines generated classes; one per class, so every class is fresh. */
    static final class Loader extends ClassLoader {
        Loader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private ClassGen() {
    }

    /** @return a class with {@code methods} small methods {@code item<i>()} each returning a distinct string */
    static byte[] manyMethods(String name, int methods) {
        ClassWriter cw = start(name);
        for (int i = 0; i < methods; i++) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "item" + i, "()Ljava/lang/String;", null, null);
            mv.visitCode();
            mv.visitLdcInsn("lending-item-" + i + "-on-loan-from-the-tool-library");
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** @return a class with {@code methods} methods of {@code steps} {@code iinc} instructions each (3 bytes a step) */
    static byte[] longBodies(String name, int methods, int steps) {
        ClassWriter cw = start(name);
        for (int i = 0; i < methods; i++) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tally" + i, "()I", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 1);
            for (int s = 0; s < steps; s++) {
                mv.visitIincInsn(1, 1);
            }
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 2);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The constructor and the hooked method {@code public long settle(long days) { return days + 1; }}. */
    private static ClassWriter start(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name.replace('.', '/'), null,
                "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor settle = cw.visitMethod(Opcodes.ACC_PUBLIC, "settle", "(J)J", null, null);
        settle.visitCode();
        settle.visitVarInsn(Opcodes.LLOAD, 1);
        settle.visitInsn(Opcodes.LCONST_1);
        settle.visitInsn(Opcodes.LADD);
        settle.visitInsn(Opcodes.LRETURN);
        settle.visitMaxs(4, 3);
        settle.visitEnd();
        return cw;
    }
}
