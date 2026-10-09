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
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A no-op, retransformation-capable transformer that records the bytes it is handed for one class: either a loaded
 * class (by identity) or a class about to be defined (by name and loader). Registered before the hook's transformer it
 * sees what the JVM hands the hook; registered last it sees what the class becomes.
 */
final class ByteRecorder implements ClassFileTransformer {

    private final Class<?> target;
    private final String internalName;
    private final ClassLoader loader;
    private final List<byte[]> seen = Collections.synchronizedList(new ArrayList<byte[]>());
    private final List<Boolean> redefining = Collections.synchronizedList(new ArrayList<Boolean>());

    ByteRecorder(Class<?> target) {
        this.target = target;
        this.internalName = target.getName().replace('.', '/');
        this.loader = target.getClassLoader();
    }

    /** Records a class that is not loaded yet, when {@code loader} defines {@code className}. */
    ByteRecorder(String className, ClassLoader loader) {
        this.target = null;
        this.internalName = className.replace('.', '/');
        this.loader = loader;
    }

    @Override
    public byte[] transform(ClassLoader cl, String name, Class<?> classBeingRedefined, ProtectionDomain pd,
                            byte[] buffer) {
        boolean match = target != null ? classBeingRedefined == target
                : internalName.equals(name) && cl == loader;
        if (match) {
            seen.add(buffer.clone());
            redefining.add(classBeingRedefined != null);
        }
        return null;
    }

    int count() {
        return seen.size();
    }

    byte[] get(int i) {
        return seen.get(i);
    }

    byte[] first() {
        return seen.isEmpty() ? null : seen.get(0);
    }

    byte[] last() {
        return seen.isEmpty() ? null : seen.get(seen.size() - 1);
    }

    void clear() {
        seen.clear();
        redefining.clear();
    }
}
