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

package ai.skillroy.testjava.agent.web.kit;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Which classes of a fake technology have been initialised, per class loader (phase4-contracts.md section 10.1 item 1:
 * the probe initialises no class of the technology). A fake's classes call {@link #record} from their static
 * initialiser; a fixture answers {@link WebAdapterFixture#initialisedClasses} from {@link #of}. The class lives outside
 * the fakes' packages, so that a recording class loader that defines the fakes' classes itself still records here,
 * in the one instance of the test's loader. A real technology's classes cannot report their initialisation; its
 * fixture answers null there.
 *
 * <p>Thread-safe; class loaders are held weakly.
 */
public final class ClassInitialisations {

    private static final Map<ClassLoader, Set<String>> INITIALISED = new WeakHashMap<ClassLoader, Set<String>>();

    private ClassInitialisations() {
    }

    /** Records that the class was initialised (called from its static initialiser). */
    public static void record(Class<?> type) {
        synchronized (INITIALISED) {
            Set<String> names = INITIALISED.get(type.getClassLoader());
            if (names == null) {
                names = new LinkedHashSet<String>();
                INITIALISED.put(type.getClassLoader(), names);
            }
            names.add(type.getName());
        }
    }

    /** The names of the recorded classes the loader defined and initialised so far, in order. */
    public static Set<String> of(ClassLoader loader) {
        synchronized (INITIALISED) {
            Set<String> names = INITIALISED.get(loader);
            return names == null ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<String>(names));
        }
    }
}
