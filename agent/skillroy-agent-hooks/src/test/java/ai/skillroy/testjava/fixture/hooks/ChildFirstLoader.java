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


package ai.skillroy.testjava.fixture.hooks;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A host's own loader: child-first for the class-name prefixes it is given, parent-first for everything else. The
 * tests use it to define a second copy of a fixture class (two ledger entries for one name), to hide a class (a
 * method signature naming a missing type) and, with the hooks module's classes, as a stand-in for a module loader.
 */
public class ChildFirstLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String[] prefixes;
    private final String hidden;

    public ChildFirstLoader(URL[] urls, ClassLoader parent, String... prefixes) {
        this(urls, parent, prefixes, null);
    }

    private ChildFirstLoader(URL[] urls, ClassLoader parent, String[] prefixes, String hidden) {
        super(urls, parent);
        this.prefixes = prefixes;
        this.hidden = hidden;
    }

    /** A loader that also refuses to load the class {@code hidden} (a binary name). */
    public static ChildFirstLoader hiding(URL[] urls, ClassLoader parent, String hidden, String... prefixes) {
        return new ChildFirstLoader(urls, parent, prefixes, hidden);
    }

    private boolean own(String name) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(hidden)) {
            throw new ClassNotFoundException(name + " is hidden by this loader");
        }
        if (!own(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException notHere) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        if (own(name.replace('/', '.'))) {
            URL url = findResource(name);
            if (url != null) {
                return url;
            }
        }
        return super.getResource(name);
    }
}
