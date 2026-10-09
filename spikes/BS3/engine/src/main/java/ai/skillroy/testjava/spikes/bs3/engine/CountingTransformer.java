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
import java.util.concurrent.atomic.AtomicInteger;

/** Counts how often the JVM hands it one loaded class: proof that a step did, or did not, retransform it. */
final class CountingTransformer implements ClassFileTransformer {

    private final Class<?> target;
    final AtomicInteger count = new AtomicInteger();

    CountingTransformer(Class<?> target) {
        this.target = target;
    }

    @Override
    public byte[] transform(ClassLoader cl, String name, Class<?> classBeingRedefined, ProtectionDomain pd,
                            byte[] buffer) {
        if (classBeingRedefined == target) {
            count.incrementAndGet();
        }
        return null;
    }
}
