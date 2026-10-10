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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The touched-class ledger (phase6-contracts.md section 3.2): per class the engine has retransformed, its name, its
 * loader (weakly), the bytes the recorder saw at the first transform (what the restore is compared with), the sites
 * hooked, the engine's own retransform count, the recorder's event count and the last error. At most
 * {@code hooks.maxClasses} entries. The transformers read it on any thread through an immutable index by name; the
 * engine changes it under its own lock and republishes the index.
 */
final class Ledger {

    /** One touched class. */
    static final class Entry {
        final String name;
        final String internalName;
        final String loaderId;
        final WeakReference<Class<?>> type;
        final WeakReference<ClassLoader> loader;
        /** The bytes of the first transform event (the JVM's reconstituted bytes), set by the recorder. */
        volatile byte[] first;
        /** The sites to hook in this class now; empty while it is being restored. */
        volatile List<Site> sites = Collections.emptyList();
        /** Set while a restore retransforms the class: the hook transformer answers null. */
        volatile boolean restoring;
        /** Every transform event of the class the recorder saw (the engine's own and anyone else's). */
        final AtomicInteger events = new AtomicInteger();
        /**
         * The transform events of the class the recorder saw on the engine's own thread while it retransformed (Phase
         * 6 security fix, pass 2, P2-L2-F4: counted where they happen, not one per class of a batch).
         */
        final AtomicInteger own = new AtomicInteger();
        volatile String error;
        /** True when a restore left a reference to Spy: the entry stays (section 3.5). */
        volatile boolean failed;
        final long sizeBytes;
        final int methods;

        Entry(Class<?> type, String loaderId, long sizeBytes, int methods) {
            this.name = type.getName();
            this.internalName = name.replace('.', '/');
            this.loaderId = loaderId;
            this.type = new WeakReference<Class<?>>(type);
            this.loader = new WeakReference<ClassLoader>(type.getClassLoader());
            this.sizeBytes = sizeBytes;
            this.methods = methods;
        }

        /** The foreign transform events: the recorder's count less the engine's own retransformations. */
        int foreignEvents() {
            return Math.max(0, events.get() - own.get());
        }

        /** True once the class (and so its loader) has been collected. */
        boolean stale() {
            return type.get() == null;
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<Entry>();
    private volatile Map<String, Entry[]> byName = Collections.emptyMap();

    /** The entry of exactly this class, or null; any thread. */
    Entry find(String internalName, Class<?> type) {
        if (internalName == null || type == null) {
            return null;
        }
        Entry[] candidates = byName.get(internalName);
        if (candidates == null) {
            return null;
        }
        for (Entry e : candidates) {
            if (e.type.get() == type) {
                return e;
            }
        }
        return null;
    }

    /** The entry of the class with this name defined by this loader, or null; any thread. */
    Entry find(String binaryName, ClassLoader loader) {
        Entry[] candidates = byName.get(binaryName.replace('.', '/'));
        if (candidates == null) {
            return null;
        }
        for (Entry e : candidates) {
            Class<?> c = e.type.get();
            if (c != null && c.getClassLoader() == loader) {
                return e;
            }
        }
        return null;
    }

    /** Under the engine's lock. */
    void add(Entry entry) {
        entries.add(entry);
        reindex();
    }

    /** Under the engine's lock. */
    void remove(Entry entry) {
        entries.remove(entry);
        reindex();
    }

    List<Entry> entries() {
        return new ArrayList<Entry>(entries);
    }

    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    private void reindex() {
        Map<String, List<Entry>> grouped = new HashMap<String, List<Entry>>();
        for (Entry e : entries) {
            List<Entry> list = grouped.get(e.internalName);
            if (list == null) {
                list = new ArrayList<Entry>();
                grouped.put(e.internalName, list);
            }
            list.add(e);
        }
        Map<String, Entry[]> index = new HashMap<String, Entry[]>();
        for (Map.Entry<String, List<Entry>> g : grouped.entrySet()) {
            index.put(g.getKey(), g.getValue().toArray(new Entry[0]));
        }
        byName = Collections.unmodifiableMap(index);
    }

    /** The identity reported for a loader: {@code bootstrap}, {@code system}, or its identity hash in hex. */
    static String loaderId(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        if (loader == ClassLoader.getSystemClassLoader()) {
            return "system";
        }
        return Integer.toHexString(System.identityHashCode(loader));
    }
}
