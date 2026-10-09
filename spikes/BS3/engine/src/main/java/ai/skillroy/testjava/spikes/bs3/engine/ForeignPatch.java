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

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for another agent (BS3 item 3): rewrites one string constant of one class to another of the same length,
 * by patching the UTF-8 bytes in the constant pool, so the class stays valid and only that constant changes. Matches
 * the class by name and, when given, by loader.
 */
final class ForeignPatch implements ClassFileTransformer {

    private final String internalName;
    private final ClassLoader loader;
    private final byte[] from;
    private final byte[] to;
    final AtomicInteger applied = new AtomicInteger();

    ForeignPatch(String className, ClassLoader loader, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("same length only");
        }
        this.internalName = className.replace('.', '/');
        this.loader = loader;
        this.from = from.getBytes(StandardCharsets.UTF_8);
        this.to = to.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] transform(ClassLoader cl, String name, Class<?> classBeingRedefined, ProtectionDomain pd,
                            byte[] buffer) {
        if (!internalName.equals(name) || (loader != null && cl != loader)) {
            return null;
        }
        byte[] out = patch(buffer, from, to);
        if (out != null) {
            applied.incrementAndGet();
        }
        return out;
    }

    /** @return a copy with every occurrence of {@code from} replaced, or null if there is none */
    static byte[] patch(byte[] in, byte[] from, byte[] to) {
        byte[] out = null;
        for (int i = 0; i + from.length <= in.length; i++) {
            boolean hit = true;
            for (int j = 0; j < from.length; j++) {
                if (in[i + j] != from[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                if (out == null) {
                    out = in.clone();
                }
                System.arraycopy(to, 0, out, i, to.length);
            }
        }
        return out;
    }
}
