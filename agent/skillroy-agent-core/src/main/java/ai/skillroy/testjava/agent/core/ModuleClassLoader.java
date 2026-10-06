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

package ai.skillroy.testjava.agent.core;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The loader of one module jar (phase1-contracts.md 2.2): child-first for the classes and resources the jar contains,
 * so that a module may bundle its own helpers without clashing with the host; parent-first for the JDK and for the
 * shared agent types ({@code skillroy-protocol}, {@code skillroy-agent-api}), which must exist exactly once or
 * {@code ServiceLoader} and every SPI cast would fail. The parent is the agent loader, which in turn delegates to the
 * host loader, so a module sees the agent API and the host application's classes.
 *
 * <p>Parallel capable; safe from any thread.
 */
final class ModuleClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static final String[] PARENT_FIRST = {
        "java.", "javax.", "sun.", "com.sun.", "jdk.",
        "ai.skillroy.testjava.protocol.", "ai.skillroy.testjava.agent.api."
    };
    private static final String[] PARENT_FIRST_RESOURCES = {
        "java/", "javax/", "sun/", "com/sun/", "jdk/",
        "ai/skillroy/testjava/protocol/", "ai/skillroy/testjava/agent/api/"
    };

    ModuleClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    static boolean isParentFirst(String className) {
        for (String prefix : PARENT_FIRST) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isParentFirstResource(String name) {
        for (String prefix : PARENT_FIRST_RESOURCES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isParentFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException notInModule) {
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
        if (!isParentFirstResource(name)) {
            URL own = findResource(name);
            if (own != null) {
                return own;
            }
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (isParentFirstResource(name)) {
            return super.getResources(name);
        }
        List<URL> urls = new ArrayList<URL>(Collections.list(findResources(name)));
        ClassLoader parent = getParent();
        if (parent != null) {
            for (URL url : Collections.list(parent.getResources(name))) {
                if (!urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        return Collections.enumeration(urls);
    }

    @Override
    public String toString() {
        return "SkillroyModuleClassLoader" + java.util.Arrays.toString(getURLs());
    }
}
