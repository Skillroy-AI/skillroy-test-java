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


package ai.skillroy.testjava.agent.hooks;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.Charset;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Another agent's retransformation-capable transformer: rewrites one string constant of one class (same length, so a
 * byte replacement keeps the class file valid), as BS3's {@code ForeignPatch} did.
 */
final class ForeignPatch implements ClassFileTransformer {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String internalName;
    private final byte[] from;
    private final byte[] to;
    final AtomicInteger applied = new AtomicInteger();

    ForeignPatch(Class<?> type, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("same length only");
        }
        this.internalName = type.getName().replace('.', '/');
        this.from = from.getBytes(UTF8);
        this.to = to.getBytes(UTF8);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!internalName.equals(className)) {
            return null;
        }
        byte[] out = patch(classfileBuffer, from, to);
        if (out != null) {
            applied.incrementAndGet();
        }
        return out;
    }

    /** The bytes with the first occurrence of {@code from} replaced, or null when there is none. */
    static byte[] patch(byte[] bytes, byte[] from, byte[] to) {
        outer:
        for (int i = 0; i + from.length <= bytes.length; i++) {
            for (int j = 0; j < from.length; j++) {
                if (bytes[i + j] != from[j]) {
                    continue outer;
                }
            }
            byte[] out = bytes.clone();
            System.arraycopy(to, 0, out, i, to.length);
            return out;
        }
        return null;
    }

    static byte[] patch(byte[] bytes, String from, String to) {
        return patch(bytes, from.getBytes(UTF8), to.getBytes(UTF8));
    }
}
