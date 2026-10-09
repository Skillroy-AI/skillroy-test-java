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

package ai.skillroy.testjava.spikes.bs3.agent;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The isolated engine loader, shaped like the real {@code AgentClassLoader} of {@code skillroy-agent-bootstrap}:
 * child-first for the project's own prefix (so the engine and the Byte Buddy relocated under that prefix are always
 * the agent's copies), parent-first for everything else (the JDK, host classes, and anything not relocated). Two
 * packages of the prefix are excluded: this bootstrap's own (shared with the host's system loader) and the spy's
 * (which must come from the bootstrap loader, never from an engine jar).
 */
public final class EngineLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    static final String OWN_PREFIX = "ai.skillroy.testjava.";
    static final String BOOTSTRAP_PREFIX = "ai.skillroy.testjava.spikes.bs3.agent.";
    static final String SPY_PREFIX = "ai.skillroy.testjava.spikes.bs3.spy.";

    public EngineLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    static boolean isChildFirst(String name) {
        return name.startsWith(OWN_PREFIX) && !name.startsWith(BOOTSTRAP_PREFIX) && !name.startsWith(SPY_PREFIX);
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
                } catch (ClassNotFoundException notInEngineJar) {
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
        String dotted = name.replace('/', '.');
        if (isChildFirst(dotted)) {
            URL own = findResource(name);
            if (own != null) {
                return own;
            }
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (!isChildFirst(name.replace('/', '.'))) {
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
        return "Bs3EngineLoader@" + Integer.toHexString(System.identityHashCode(this));
    }
}
