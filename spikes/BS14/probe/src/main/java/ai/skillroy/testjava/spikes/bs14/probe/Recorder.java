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

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A no-op, retransformation-capable recorder of one class's transform events. Registered ahead of Byteman's
 * transformer it sees the bytes the JVM hands the chain (the reference at the first transform); registered behind it,
 * it sees what Byteman returns, which is what the JVM installs. Events while {@link #own} is false are counted as
 * foreign.
 */
final class Recorder implements ClassFileTransformer {

    static final AtomicBoolean own = new AtomicBoolean();

    private final String name;
    private final String internalName;
    private final List<byte[]> events = new ArrayList<byte[]>();
    private final AtomicInteger foreign = new AtomicInteger();

    Recorder(String name, String className) {
        this.name = name;
        this.internalName = className.replace('.', '/');
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (internalName.equals(className) && classBeingRedefined != null) {
            synchronized (events) {
                events.add(classfileBuffer.clone());
            }
            if (!own.get()) {
                foreign.incrementAndGet();
            }
        }
        return null;
    }

    byte[] first() {
        synchronized (events) {
            return events.isEmpty() ? null : events.get(0);
        }
    }

    byte[] last() {
        synchronized (events) {
            return events.isEmpty() ? null : events.get(events.size() - 1);
        }
    }

    int count() {
        synchronized (events) {
            return events.size();
        }
    }

    int foreign() {
        return foreign.get();
    }

    @Override
    public String toString() {
        return name;
    }
}
