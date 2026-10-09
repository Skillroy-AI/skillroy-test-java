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

package ai.skillroy.testjava.spikes.bs3.host;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

/**
 * A loader that delegates only {@code java.*} to its parent and defines everything else from its own URLs or fails,
 * the way an OSGi framework's bundle loaders behave without boot delegation (BS3 item 1). Resources follow the same
 * rule.
 */
public final class StrictDelegationLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String name;

    public StrictDelegationLoader(String name, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.name = name;
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
        if (className.startsWith("java.")) {
            return getParent().loadClass(className);
        }
        synchronized (getClassLoadingLock(className)) {
            Class<?> c = findLoadedClass(className);
            if (c == null) {
                c = findClass(className);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public URL getResource(String resource) {
        return resource.startsWith("java/") ? getParent().getResource(resource) : findResource(resource);
    }

    @Override
    public Enumeration<URL> getResources(String resource) throws IOException {
        return resource.startsWith("java/") ? getParent().getResources(resource) : findResources(resource);
    }

    @Override
    public String toString() {
        return "StrictDelegationLoader[" + name + "]";
    }
}
