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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A class loader that records what it is asked for and what it defines: it defines the classes of the given package
 * prefixes itself (child-first, from the class files a source loader can read) and hands every other request to a
 * delegate. Its parent, as {@link #getParent()} reports it, is the bootstrap loader, so that a question asked of it
 * and its parents never reaches the delegate (where the same classes may already be defined by other tests).
 *
 * <p>Used by the kit's item 1 (a probe loads no class the host had not loaded) and by the module's install test (no
 * AWT and no vendor class requested). Thread-safe (parallel capable).
 */
public final class RecordingClassLoader extends ClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ClassLoader source;
    private final ClassLoader delegate;
    private final String[] prefixes;
    private final Set<String> defined = Collections.synchronizedSet(new LinkedHashSet<String>());
    private final Set<String> requested = Collections.synchronizedSet(new LinkedHashSet<String>());

    /**
     * @param source the loader that can read the class files of the recorded packages
     * @param delegate the loader for every other class
     * @param prefixes the package (or class name) prefixes this loader defines itself
     */
    public RecordingClassLoader(ClassLoader source, ClassLoader delegate, String... prefixes) {
        super(null);
        this.source = source;
        this.delegate = delegate;
        this.prefixes = prefixes.clone();
    }

    /** Every class name this loader was asked for, in order (including those it delegated). */
    public Set<String> requested() {
        synchronized (requested) {
            return new LinkedHashSet<String>(requested);
        }
    }

    /** Every class this loader defined itself, in order. */
    public Set<String> defined() {
        synchronized (defined) {
            return new LinkedHashSet<String>(defined);
        }
    }

    /**
     * Loads every class of a package of the recorded prefixes whose class files sit in a directory of the source's
     * class path (a test's own classes), as an application that used all of them would have; returns how many.
     */
    public int loadPackage(String packageName) throws ClassNotFoundException {
        URL dir = source.getResource(packageName.replace('.', '/') + "/");
        if (dir == null || !"file".equals(dir.getProtocol())) {
            throw new IllegalArgumentException("no class directory for " + packageName);
        }
        java.io.File[] files;
        try {
            files = new java.io.File(dir.toURI()).listFiles();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        int n = 0;
        if (files != null) {
            for (java.io.File f : files) {
                String file = f.getName();
                if (f.isFile() && file.endsWith(".class")) {
                    Class.forName(packageName + "." + file.substring(0, file.length() - 6), false, this);
                    n++;
                }
            }
        }
        return n;
    }

    private boolean ours(String name) {
        for (String p : prefixes) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        requested.add(name);
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = ours(name) ? define(name) : delegate.loadClass(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private Class<?> define(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        InputStream in = source.getResourceAsStream(resource);
        if (in == null) {
            return delegate.loadClass(name);
        }
        byte[] bytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            bytes = out.toByteArray();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // a class-path resource
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String pkg = name.substring(0, dot);
            if (getPackageSafely(pkg) == null) {
                try {
                    definePackage(pkg, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException alreadyDefined) {
                    // defined concurrently
                }
            }
        }
        Class<?> c = defineClass(name, bytes, 0, bytes.length);
        defined.add(name);
        return c;
    }

    @SuppressWarnings("deprecation")
    private Package getPackageSafely(String pkg) {
        return getPackage(pkg);
    }

    @Override
    public URL getResource(String name) {
        String className = name.endsWith(".class") ? name.substring(0, name.length() - 6).replace('/', '.') : null;
        if (className != null && ours(className)) {
            return source.getResource(name);
        }
        return delegate.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return delegate.getResources(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        URL url = getResource(name);
        try {
            return url == null ? null : url.openStream();
        } catch (IOException e) {
            return null;
        }
    }
}
