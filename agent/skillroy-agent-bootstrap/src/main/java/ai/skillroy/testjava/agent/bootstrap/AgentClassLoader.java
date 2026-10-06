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

package ai.skillroy.testjava.agent.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The isolated agent loader over {@code <home>/lib/*.jar} (phase1-contracts.md 2.2). Child-first for
 * {@code ai.skillroy.testjava.*} except the bootstrap package, so that the agent always runs its own copy of the
 * protocol, API and core even when the host class path carries other copies; everything else (the JDK, host
 * classes) is delegated to the host loader as usual. Nothing loaded here is visible to the host.
 *
 * <p>Parallel capable; safe to use from any thread.
 */
final class AgentClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static final String OWN_PREFIX = "ai.skillroy.testjava.";
    private static final String BOOTSTRAP_PREFIX = "ai.skillroy.testjava.agent.bootstrap.";
    private static final String OWN_RESOURCE_PREFIX = "ai/skillroy/testjava/";
    private static final String BOOTSTRAP_RESOURCE_PREFIX = "ai/skillroy/testjava/agent/bootstrap/";

    AgentClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /** True for the classes this loader defines itself before asking the parent. */
    static boolean isChildFirst(String className) {
        return className.startsWith(OWN_PREFIX) && !className.startsWith(BOOTSTRAP_PREFIX);
    }

    private static boolean isChildFirstResource(String name) {
        return name.startsWith(OWN_RESOURCE_PREFIX) && !name.startsWith(BOOTSTRAP_RESOURCE_PREFIX);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isChildFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException notInAgentJars) {
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
        if (isChildFirstResource(name)) {
            URL own = findResource(name);
            if (own != null) {
                return own;
            }
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (!isChildFirstResource(name)) {
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
        return "SkillroyAgentClassLoader" + java.util.Arrays.toString(getURLs());
    }
}
