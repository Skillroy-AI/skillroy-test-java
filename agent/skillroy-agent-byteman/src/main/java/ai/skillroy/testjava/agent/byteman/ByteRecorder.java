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

package ai.skillroy.testjava.agent.byteman;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A no-op, retransformation-capable recorder of the transform events of the classes a rule of the module names
 * (phase6-contracts.md 7.4 step 4, 7.5; BS14 RESULT condition 5). The module registers two: one <b>ahead</b> of
 * Byteman's transformer (before {@code premain}), which sees the bytes the JVM hands the chain, the reference of the
 * byte check; and one <b>behind</b> it (right after {@code premain}), which sees what Byteman returns, the bytes the JVM
 * installs. After a removal the behind recorder's last bytes are compared with the ahead recorder's first. A
 * retransformation of a watched class that the module did not cause (not on the module's own thread while it submits
 * or removes) is counted as a foreign event.
 *
 * <p>Answers null at once for a class it does not watch; never throws. It loads every class it needs at construction
 * (the module loader is closed after {@code uninstall}, and a recorder left registered must keep working). The ahead
 * recorder also holds the module's disarmed handler strongly while a rule is left behind ({@link #keep}), so that the
 * helper's weak slot cannot free while the rule exists.
 */
final class ByteRecorder implements ClassFileTransformer {

    /** One class's record. */
    static final class Entry {
        volatile byte[] first;
        volatile byte[] last;
        volatile int events;
        volatile int foreign;
    }

    private final String role;
    private final AtomicReference<Thread> own;
    private final Set<String> watched = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, Entry> entries = new ConcurrentHashMap<String, Entry>();
    /** Holds the disarmed handler strongly while a rule of the module is left installed. */
    final AtomicReference<Object> keep = new AtomicReference<Object>();

    /**
     * @param own the thread on which the module is submitting or removing rules (null otherwise)
     */
    ByteRecorder(String role, AtomicReference<Thread> own) {
        this.role = role;
        this.own = own;
        Entry warm = new Entry();
        warm.events = 0;
        key("warm", null);
    }

    /** Starts recording a class by its binary name (any loader). */
    void watch(String binaryName) {
        watched.add(binaryName.replace('.', '/'));
    }

    boolean watches(String binaryName) {
        return watched.contains(binaryName.replace('.', '/'));
    }

    /** The record of {@code binaryName} in {@code loader}, or null. */
    Entry entry(String binaryName, ClassLoader loader) {
        return entries.get(key(binaryName.replace('.', '/'), loader));
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            if (className == null || classfileBuffer == null || !watched.contains(className)) {
                return null;
            }
            String key = key(className, loader);
            Entry e = entries.get(key);
            if (e == null) {
                Entry fresh = new Entry();
                Entry raced = ((ConcurrentHashMap<String, Entry>) entries).putIfAbsent(key, fresh);
                e = raced == null ? fresh : raced;
            }
            byte[] copy = classfileBuffer.clone();
            if (e.first == null) {
                e.first = copy;
            }
            e.last = copy;
            e.events++;
            if (classBeingRedefined != null && own.get() != Thread.currentThread()) {
                e.foreign++;
            }
        } catch (Throwable ignored) {
            // A recorder never throws into a class load or a retransformation.
        }
        return null;
    }

    private static String key(String internalName, ClassLoader loader) {
        return internalName + "@" + (loader == null ? "boot" : Integer.toHexString(System.identityHashCode(loader)));
    }

    @Override
    public String toString() {
        return "skillroy byteman " + role + " recorder";
    }
}
