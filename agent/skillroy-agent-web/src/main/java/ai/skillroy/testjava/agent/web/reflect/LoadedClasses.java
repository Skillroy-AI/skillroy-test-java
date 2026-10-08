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

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;

/**
 * Answers "has the host already loaded this class?" without loading it (phase4-contracts.md section 3,
 * {@code WebEngineAdapter.probe}: "a class that is not yet loaded by the host counts as absent").
 * {@code Class.forName(name, false, loader)} cannot answer it, because it loads the class when it is absent. The
 * question is asked of the host's loader and its parents, in this order of means:
 * <ol>
 *   <li>with an {@link Instrumentation} (JAVAAGENT and ATTACH, and EMBEDDED with self-attach): the loader is recorded
 *       by the JVM as an initiating loader of the class ({@code getInitiatedClasses}); exact;</li>
 *   <li>without one, on Java 8, or on Java 9+ when {@code java.base/java.lang} is open to the agent: the loader's
 *       {@code findLoadedClass}, called reflectively; exact. (On Java 9-15 the reflective call is not attempted
 *       unless the package is open, because the JDK would print an "illegal reflective access" warning on the
 *       host's standard error.)</li>
 *   <li>otherwise (Java 9+, no Instrumentation, {@code java.lang} closed): the loader has defined the class's
 *       <em>package</em> ({@code ClassLoader.getDefinedPackage}); a heuristic: some class of the technology was
 *       loaded, so the technology is in use; the gate class itself may still be loaded by the probe that follows.</li>
 * </ol>
 * A loader whose parent chain does not reach the defining loader (a child-first plugin loader that bundles the
 * technology itself) can make the answer of means 1 and 3 too optimistic; the probe then loads the gate class in
 * that loader, without initialising it.
 *
 * <p>Immutable after construction; thread-safe.
 */
public final class LoadedClasses {

    /** Which means answers (for diagnostics and tests). */
    public enum Means { INSTRUMENTATION, FIND_LOADED_CLASS, DEFINED_PACKAGE, NONE }

    private final Instrumentation instrumentation;
    private final Method findLoadedClass;
    private final Method getDefinedPackage;

    /**
     * @param instrumentation the JVM's instrumentation ({@code AgentContext.instrumentation()}), or null
     */
    public LoadedClasses(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        Method find = null;
        Method pkg = null;
        if (instrumentation == null) {
            find = findLoadedClassIfOpen();
            if (find == null) {
                pkg = definedPackageMethod();
            }
        }
        this.findLoadedClass = find;
        this.getDefinedPackage = pkg;
    }

    /** The means this instance answers with. */
    public Means means() {
        if (instrumentation != null) {
            return Means.INSTRUMENTATION;
        }
        if (findLoadedClass != null) {
            return Means.FIND_LOADED_CLASS;
        }
        return getDefinedPackage != null ? Means.DEFINED_PACKAGE : Means.NONE;
    }

    /**
     * True if the host has loaded the class: {@code loader} or one of its parents has it (see the class comment).
     * Never loads a class and never throws.
     */
    public boolean isLoaded(String className, ClassLoader loader) {
        if (className == null) {
            return false;
        }
        try {
            for (ClassLoader l = loader; l != null; l = l.getParent()) {
                if (loadedBy(className, l)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        } catch (LinkageError e) {
            return false;
        }
        return false;
    }

    private boolean loadedBy(String className, ClassLoader l) {
        if (instrumentation != null) {
            for (Class<?> c : instrumentation.getInitiatedClasses(l)) {
                if (className.equals(c.getName())) {
                    return true;
                }
            }
            return false;
        }
        if (findLoadedClass != null) {
            try {
                return findLoadedClass.invoke(l, className) != null;
            } catch (Exception e) {
                return false;
            }
        }
        if (getDefinedPackage != null) {
            int dot = className.lastIndexOf('.');
            String pkg = dot < 0 ? "" : className.substring(0, dot);
            try {
                return getDefinedPackage.invoke(l, pkg) != null;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * True if the class file can be found through the loader ({@code getResource}, which loads nothing): the
     * technology is on the class path, whether or not the host has loaded it.
     */
    public static boolean isReachable(String className, ClassLoader loader) {
        String resource = className.replace('.', '/') + ".class";
        try {
            URL url = loader == null ? ClassLoader.getSystemResource(resource) : loader.getResource(resource);
            return url != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True on Java 8 and older (no module system). */
    static boolean beforeJava9() {
        String v = System.getProperty("java.specification.version", "");
        return v.startsWith("1.");
    }

    /** {@code ClassLoader.findLoadedClass} made accessible, where that needs no warning; else null. */
    private static Method findLoadedClassIfOpen() {
        if (!beforeJava9() && !javaLangOpenToUs()) {
            return null;
        }
        try {
            Method m = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    /** True on Java 9+ when {@code java.base} opens {@code java.lang} to this class's module. */
    private static boolean javaLangOpenToUs() {
        try {
            Method getModule = Class.class.getMethod("getModule");
            Object javaBase = getModule.invoke(ClassLoader.class);
            Object mine = getModule.invoke(LoadedClasses.class);
            Class<?> moduleClass = Class.forName("java.lang.Module");
            Method isOpen = moduleClass.getMethod("isOpen", String.class, moduleClass);
            return Boolean.TRUE.equals(isOpen.invoke(javaBase, "java.lang", mine));
        } catch (Exception e) {
            return false;
        } catch (LinkageError e) {
            return false;
        }
    }

    /** {@code ClassLoader.getDefinedPackage(String)} (public on Java 9+), or null. */
    private static Method definedPackageMethod() {
        if (beforeJava9()) {
            return null;
        }
        try {
            return ClassLoader.class.getMethod("getDefinedPackage", String.class);
        } catch (Exception e) {
            return null;
        }
    }
}
