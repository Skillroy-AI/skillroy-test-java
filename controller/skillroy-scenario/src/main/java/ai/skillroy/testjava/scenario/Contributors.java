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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.resolve.ResolverContributor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The {@link ResolverContributor}s on a class path (phase5-contracts.md 5.5): found with {@code ServiceLoader} on the
 * given class loader, the thread's context class loader by default, as {@code WebRun.find} finds a web path: a
 * provider that cannot be loaded ({@code ServiceConfigurationError}, {@code LinkageError}, a runtime exception from
 * its constructor) is skipped and its first error kept for the message of a slot that then has no resolver. At most
 * 100 providers are looked at.
 *
 * <p>Owned by one engine, on its thread; {@link #close()} closes every loaded contributor.
 */
final class Contributors implements AutoCloseable {

    private static final int MAX_PROVIDERS = 100;

    private final List<ResolverContributor> loaded;
    private final String loadError;

    private Contributors(List<ResolverContributor> loaded, String loadError) {
        this.loaded = loaded;
        this.loadError = loadError;
    }

    /** Loads the contributors {@code ServiceLoader} finds through {@code loader} (null: the context class loader). */
    static Contributors load(ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        if (effective == null) {
            effective = ResolverContributor.class.getClassLoader();
        }
        List<ResolverContributor> found = new ArrayList<>();
        String error = null;
        try {
            Iterator<ResolverContributor> providers = ServiceLoader.load(ResolverContributor.class, effective)
                    .iterator();
            for (int i = 0; i < MAX_PROVIDERS; i++) {
                try {
                    if (!providers.hasNext()) {
                        break;
                    }
                    found.add(providers.next());
                } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                    if (error == null) {
                        error = message(e);
                    }
                }
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            error = message(e);
        }
        return new Contributors(List.copyOf(found), error);
    }

    /** The contributors that loaded, in {@code ServiceLoader} order. */
    List<ResolverContributor> loaded() {
        return loaded;
    }

    /** The first loading error, or null. */
    String loadError() {
        return loadError;
    }

    /** Closes every contributor; never throws. */
    @Override
    public void close() {
        for (ResolverContributor contributor : loaded) {
            try {
                contributor.close();
            } catch (RuntimeException | LinkageError e) {
                // Best effort: a contributor that cannot close holds nothing the run needs.
            }
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
