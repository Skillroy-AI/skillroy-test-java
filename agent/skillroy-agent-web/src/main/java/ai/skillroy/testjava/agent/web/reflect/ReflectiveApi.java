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

package ai.skillroy.testjava.agent.web.reflect;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Resolves a {@link Descriptor} in a class loader (phase4-contracts.md section 4.7): every role is looked up once per
 * vendor class loader and the result is cached <em>weakly</em> per loader, keys and values both, so that the cache
 * never keeps a vendor's class loader (and with it the application's classes) alive: a {@link ResolvedApi} holds the
 * vendor's classes, which hold their loader, so a strong value would pin the weak key. An adapter keeps the
 * {@code ResolvedApi} it works with in a field while it works with it; once nothing uses it, the next call resolves
 * again (cheap: a few reflective look-ups).
 *
 * <p>Stateless apart from the cache; thread-safe.
 */
public final class ReflectiveApi {

    private static final Map<Descriptor, Map<ClassLoader, WeakReference<ResolvedApi>>> CACHE =
            new WeakHashMap<Descriptor, Map<ClassLoader, WeakReference<ResolvedApi>>>();

    private ReflectiveApi() {
    }

    /**
     * The descriptor's roles looked up in {@code loader}: from the cache when this loader was resolved before and
     * the result is still in use, else looked up now. Never throws for a missing role: the result lists it.
     *
     * @param loader the vendor's class loader (null means the bootstrap loader, where no vendor class lives)
     */
    public static ResolvedApi resolve(Descriptor descriptor, ClassLoader loader) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        synchronized (CACHE) {
            Map<ClassLoader, WeakReference<ResolvedApi>> perLoader = CACHE.get(descriptor);
            if (perLoader != null) {
                WeakReference<ResolvedApi> ref = perLoader.get(loader);
                ResolvedApi cached = ref == null ? null : ref.get();
                if (cached != null) {
                    return cached;
                }
            }
        }
        ResolvedApi resolved = new ResolvedApi(descriptor, loader);
        synchronized (CACHE) {
            Map<ClassLoader, WeakReference<ResolvedApi>> perLoader = CACHE.get(descriptor);
            if (perLoader == null) {
                perLoader = new WeakHashMap<ClassLoader, WeakReference<ResolvedApi>>();
                CACHE.put(descriptor, perLoader);
            }
            WeakReference<ResolvedApi> ref = perLoader.get(loader);
            ResolvedApi raced = ref == null ? null : ref.get();
            if (raced != null) {
                return raced;
            }
            perLoader.put(loader, new WeakReference<ResolvedApi>(resolved));
        }
        return resolved;
    }

    /**
     * How many class loaders hold a live cached resolution of the descriptor (for tests and diagnostics); a loader
     * whose resolution was collected, or that was itself collected, no longer counts.
     */
    public static int cachedLoaders(Descriptor descriptor) {
        synchronized (CACHE) {
            Map<ClassLoader, WeakReference<ResolvedApi>> perLoader = CACHE.get(descriptor);
            if (perLoader == null) {
                return 0;
            }
            int n = 0;
            for (WeakReference<ResolvedApi> ref : perLoader.values()) {
                if (ref != null && ref.get() != null) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Drops the descriptor's cached resolutions (an adapter's {@code detach()} forgets cached reflection). */
    public static void forget(Descriptor descriptor) {
        synchronized (CACHE) {
            CACHE.remove(descriptor);
        }
    }
}
