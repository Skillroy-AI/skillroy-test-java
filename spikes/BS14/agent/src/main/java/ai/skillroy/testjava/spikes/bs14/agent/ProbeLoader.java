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

package ai.skillroy.testjava.spikes.bs14.agent;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * The probe's loader, shaped like a module loader: child-first for the probe's own package, parent-first (the host's
 * system loader) for everything else, so that RuleHelper and RuleManager, once appended to the system class path, are
 * the system loader's classes.
 */
public final class ProbeLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    static final String CHILD_FIRST = "ai.skillroy.testjava.spikes.bs14.probe.";

    public ProbeLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(CHILD_FIRST)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = findClass(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public String toString() {
        return "ProbeLoader@" + Integer.toHexString(System.identityHashCode(this));
    }
}
