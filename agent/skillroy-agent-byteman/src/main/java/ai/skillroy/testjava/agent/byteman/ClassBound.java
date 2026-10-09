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

package ai.skillroy.testjava.agent.byteman;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The class bound of a rule (phase6-contracts.md 7.3, D124): before a rule reaches Byteman the module counts the loaded
 * classes it would match (by name; by supertype for a {@code ^} rule; by interface for an {@code INTERFACE} rule), over
 * the loaded classes read once per call ({@code Instrumentation.getAllLoadedClasses()}), and refuses
 * ({@code RULE_REJECTED}) above {@code hooks.maxClasses}, a class above {@code hooks.maxClassBytes} or
 * {@code hooks.maxMethods}, a class that is not modifiable, and any class defined by the bootstrap, platform or
 * extension loader or by a loader of the agent (the loader rule of 3.3 item 2). Every retransformation is a
 * stop-the-world pause, and a rule is bounded as a hook is. The walk never loads a class.
 */
final class ClassBound {

    /** Answers the questions the bound asks of the JVM (Instrumentation in production; tests supply their own). */
    interface Jvm {
        Class<?>[] loadedClasses();

        boolean isModifiable(Class<?> c);
    }

    /** What the bound found. */
    static final class Result {
        final List<Class<?>> matched;
        final List<String> errors;

        Result(List<Class<?>> matched, List<String> errors) {
            this.matched = Collections.unmodifiableList(matched);
            this.errors = Collections.unmodifiableList(errors);
        }

        boolean ok() {
            return errors.isEmpty();
        }
    }

    private final Jvm jvm;
    private final Settings settings;
    private final Set<ClassLoader> agentLoaders;
    private final Set<ClassLoader> jdkLoaders;

    ClassBound(Jvm jvm, Settings settings, Set<ClassLoader> agentLoaders) {
        this.jvm = jvm;
        this.settings = settings;
        this.agentLoaders = agentLoaders;
        this.jdkLoaders = jdkLoaders();
    }

    /** The loaders between {@code start} and the host's system loader (exclusive): the agent's own. */
    static Set<ClassLoader> chainOf(ClassLoader start) {
        Set<ClassLoader> chain = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        ClassLoader system = ClassLoader.getSystemClassLoader();
        for (ClassLoader l = start; l != null && l != system; l = l.getParent()) {
            if (l == system.getParent()) {
                break;
            }
            chain.add(l);
        }
        return chain;
    }

    /** The platform loader (Java 9 and later, reached reflectively) or the extension loader (Java 8). */
    private static Set<ClassLoader> jdkLoaders() {
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        try {
            Method platform = ClassLoader.class.getMethod("getPlatformClassLoader");
            Object l = platform.invoke(null);
            if (l instanceof ClassLoader) {
                loaders.add((ClassLoader) l);
            }
        } catch (ReflectiveOperationException | RuntimeException java8) {
            ClassLoader ext = ClassLoader.getSystemClassLoader().getParent();
            if (ext != null) {
                loaders.add(ext);
            }
        }
        return loaders;
    }

    /** Matches and bounds the rule the lint accepted. */
    Result check(RuleLint.Result rule) {
        List<Class<?>> matched = new ArrayList<Class<?>>();
        List<String> errors = new ArrayList<String>();
        String target = rule.className();
        for (Class<?> c : jvm.loadedClasses()) {
            if (c == null || c.isArray() || c.isPrimitive()) {
                continue;
            }
            boolean hit;
            if (rule.isInterface()) {
                hit = !c.isInterface() && implementsInterface(c, target);
            } else if (rule.overriding()) {
                hit = !c.isInterface() && extendsClass(c, target);
            } else {
                hit = c.getName().equals(target);
            }
            if (hit) {
                matched.add(c);
            }
        }
        if (matched.size() > settings.maxClasses) {
            errors.add("rule would retransform " + matched.size() + " classes, more than hooks.maxClasses ("
                    + settings.maxClasses + ")");
            return new Result(matched, errors);
        }
        for (Class<?> c : matched) {
            String problem = problem(c);
            if (problem != null) {
                errors.add(c.getName() + ": " + problem);
            }
        }
        return new Result(matched, errors);
    }

    /** Why {@code c} may not be retransformed, or null. */
    String problem(Class<?> c) {
        ClassLoader loader = c.getClassLoader();
        if (loader == null || jdkLoaders.contains(loader) || agentLoaders.contains(loader)
                || loader.getClass().getName().startsWith("ai.skillroy.testjava.agent.")) {
            return "class is the agent's own or the JDK's";
        }
        if (!jvm.isModifiable(c)) {
            return "class is not modifiable";
        }
        int methods;
        try {
            methods = c.getDeclaredMethods().length + c.getDeclaredConstructors().length;
        } catch (Throwable t) {
            return "its methods cannot be read: " + t.getClass().getName();
        }
        if (methods > settings.maxMethods) {
            return "class too large: " + methods + " methods > " + settings.maxMethods;
        }
        long bytes = classFileSize(c, settings.maxClassBytes + 1);
        if (bytes > settings.maxClassBytes) {
            return "class too large: " + bytes + " bytes > " + settings.maxClassBytes;
        }
        return null;
    }

    /** The class file's size through its loader's resource, read up to {@code limit} bytes; -1 when unknown. */
    static long classFileSize(Class<?> c, long limit) {
        ClassLoader loader = c.getClassLoader();
        if (loader == null) {
            return -1;
        }
        String resource = c.getName().replace('.', '/') + ".class";
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return -1;
            }
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total >= limit) {
                    return total;
                }
            }
            return total;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static boolean extendsClass(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code c} implements the interface {@code name} through any path (its own list, a superclass's, a
     * sub-interface's): the bound counts every class an INTERFACE rule could reach, whatever Byteman's own matching
     * of direct and inherited implementations does.
     */
    private static boolean implementsInterface(Class<?> c, String name) {
        Set<Class<?>> seen = new HashSet<Class<?>>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (reaches(i, name, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean reaches(Class<?> i, String name, Set<Class<?>> seen) {
        if (!seen.add(i)) {
            return false;
        }
        if (i.getName().equals(name)) {
            return true;
        }
        for (Class<?> parent : i.getInterfaces()) {
            if (reaches(parent, name, seen)) {
                return true;
            }
        }
        return false;
    }

    /** The loaded classes by name, for the type check's "loaded by the system loader" rule. */
    static Map<String, List<Class<?>>> index(Class<?>[] loaded) {
        Map<String, List<Class<?>>> byName = new java.util.HashMap<String, List<Class<?>>>();
        for (Class<?> c : loaded) {
            if (c == null) {
                continue;
            }
            List<Class<?>> list = byName.get(c.getName());
            if (list == null) {
                list = new ArrayList<Class<?>>(1);
                byName.put(c.getName(), list);
            }
            list.add(c);
        }
        return byName;
    }
}
