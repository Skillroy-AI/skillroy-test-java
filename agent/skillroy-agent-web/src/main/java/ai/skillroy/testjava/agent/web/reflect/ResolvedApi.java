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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The roles of one {@link Descriptor}, looked up once in one class loader: the classes, the {@link Method}s and
 * {@link Field}s, and for each role that could not be found, why. Made by {@link ReflectiveApi#resolve}, which caches
 * it weakly per loader.
 *
 * <p>Looking a role up loads its class without initialising it ({@code Class.forName(name, false, loader)}) and, for
 * a member, the types of the member's signature and whatever linking the class needs (what core reflection does); a
 * role {@link Descriptor.Role#byName() matched by name} is never loaded by the lookup itself (linking another role's
 * class may load it). Nothing is initialised. An adapter resolves its descriptor only after its probe found the
 * technology's gate class already loaded by the host (phase4-contracts.md section 3 as amended: the gate must be
 * loaded by the host; the lookups may then load the technology's classes, never initialise them).
 *
 * <p>Immutable; safe to share between threads. Calls ({@link #invoke}, {@link #read}) run on the calling thread.
 */
public final class ResolvedApi {

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<String, Class<?>>();

    static {
        PRIMITIVES.put("boolean", boolean.class);
        PRIMITIVES.put("byte", byte.class);
        PRIMITIVES.put("char", char.class);
        PRIMITIVES.put("short", short.class);
        PRIMITIVES.put("int", int.class);
        PRIMITIVES.put("long", long.class);
        PRIMITIVES.put("float", float.class);
        PRIMITIVES.put("double", double.class);
    }

    private final Descriptor descriptor;
    private final ClassLoader loader;
    private final Map<String, Entry> entries;
    private final Map<String, String> missing;

    ResolvedApi(Descriptor descriptor, ClassLoader loader) {
        this.descriptor = descriptor;
        this.loader = loader;
        Map<String, Entry> found = new LinkedHashMap<String, Entry>();
        Map<String, String> absent = new LinkedHashMap<String, String>();
        for (String name : descriptor.roles()) {
            Descriptor.Role role = descriptor.role(name);
            String reason = null;
            Entry entry = null;
            try {
                entry = resolve(role);
            } catch (Missing m) {
                reason = m.getMessage();
                for (Descriptor.Role alternative : role.alternatives()) {
                    try {
                        entry = resolve(alternative);
                        break;
                    } catch (Missing ignored) {
                        // the next alternative
                    }
                }
            }
            if (entry != null) {
                found.put(name, entry);
            } else {
                absent.put(name, reason);
            }
        }
        this.entries = Collections.unmodifiableMap(found);
        this.missing = Collections.unmodifiableMap(absent);
    }

    private Entry resolve(Descriptor.Role role) throws Missing {
        if (role.byName()) {
            return new Entry(role, null, null, null);
        }
        Class<?> type = load(role.className(), role);
        if (role.isInterface() && !type.isInterface()) {
            throw new Missing(role.describe() + " is not an interface");
        }
        if (role.method() != null && role.arity() >= 0) {
            Method m = findByArity(type, role);
            if (Modifier.isStatic(m.getModifiers()) != role.isStatic()) {
                throw new Missing(role.describe() + (role.isStatic() ? " is not static" : " is static"));
            }
            return new Entry(role, type, m, null);
        }
        if (role.method() != null) {
            Class<?>[] params = new Class<?>[role.params().size()];
            for (int i = 0; i < params.length; i++) {
                String p = role.params().get(i);
                Class<?> primitive = PRIMITIVES.get(p);
                params[i] = primitive != null ? primitive : load(p, role);
            }
            Method m = findMethod(type, role, params);
            if (Modifier.isStatic(m.getModifiers()) != role.isStatic()) {
                throw new Missing(role.describe() + (role.isStatic() ? " is not static" : " is static"));
            }
            return new Entry(role, type, m, null);
        }
        if (role.field() != null) {
            Field f = findField(type, role);
            if (Modifier.isStatic(f.getModifiers()) != role.isStatic()) {
                throw new Missing(role.describe() + (role.isStatic() ? " is not static" : " is static"));
            }
            return new Entry(role, type, null, f);
        }
        return new Entry(role, type, null, null);
    }

    private Class<?> load(String name, Descriptor.Role role) throws Missing {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new Missing("class " + name + " not found (" + role.name() + ")");
        } catch (LinkageError e) {
            throw new Missing("class " + name + " cannot be loaded (" + role.name() + "): " + e);
        }
    }

    private static Method findMethod(Class<?> type, Descriptor.Role role, Class<?>[] params) throws Missing {
        try {
            if (!role.isPrivate()) {
                return type.getMethod(role.method(), params);
            }
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(role.method(), params);
                    makeAccessible(m, role);
                    return m;
                } catch (NoSuchMethodException next) {
                    // look in the superclass
                }
            }
            throw new NoSuchMethodException(role.describe());
        } catch (NoSuchMethodException e) {
            throw new Missing("no method " + role.describe());
        } catch (LinkageError e) {
            throw new Missing("cannot read the members of " + role.className() + ": " + e);
        } catch (SecurityException e) {
            throw new Missing(role.describe() + " is not accessible: " + e.getMessage());
        }
    }

    /** The one method of the role's name and parameter count (public, or declared when private). */
    private static Method findByArity(Class<?> type, Descriptor.Role role) throws Missing {
        try {
            Method found = null;
            if (!role.isPrivate()) {
                for (Method m : type.getMethods()) {
                    if (m.getName().equals(role.method()) && m.getParameterTypes().length == role.arity()
                            && !m.isBridge()) {
                        if (found != null && !sameSignature(found, m)) {
                            throw new Missing("more than one method " + role.describe());
                        }
                        found = found == null ? m : found;
                    }
                }
            } else {
                for (Class<?> c = type; c != null && found == null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(role.method()) && m.getParameterTypes().length == role.arity()
                                && !m.isBridge()) {
                            if (found != null) {
                                throw new Missing("more than one method " + role.describe());
                            }
                            found = m;
                        }
                    }
                }
                if (found != null) {
                    makeAccessible(found, role);
                }
            }
            if (found == null) {
                throw new Missing("no method " + role.describe());
            }
            return found;
        } catch (LinkageError e) {
            throw new Missing("cannot read the members of " + role.className() + ": " + e);
        } catch (SecurityException e) {
            throw new Missing(role.describe() + " is not accessible: " + e.getMessage());
        }
    }

    private static boolean sameSignature(Method a, Method b) {
        return java.util.Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    private static Field findField(Class<?> type, Descriptor.Role role) throws Missing {
        try {
            if (!role.isPrivate()) {
                return type.getField(role.field());
            }
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(role.field());
                    makeAccessible(f, role);
                    return f;
                } catch (NoSuchFieldException next) {
                    // look in the superclass
                }
            }
            throw new NoSuchFieldException(role.describe());
        } catch (NoSuchFieldException e) {
            throw new Missing("no field " + role.describe());
        } catch (LinkageError e) {
            throw new Missing("cannot read the members of " + role.className() + ": " + e);
        } catch (SecurityException e) {
            throw new Missing(role.describe() + " is not accessible: " + e.getMessage());
        }
    }

    /**
     * {@code setAccessible(true)}; refused on a strongly encapsulated module (a {@code RuntimeException} on Java 9+)
     * or by a security manager, which makes the role missing ("setAccessible is refused").
     */
    private static void makeAccessible(java.lang.reflect.AccessibleObject member, Descriptor.Role role)
            throws Missing {
        try {
            member.setAccessible(true);
        } catch (RuntimeException e) {
            throw new Missing("setAccessible is refused for " + role.describe() + ": " + e.getMessage());
        }
    }

    /** The descriptor this was resolved from. */
    public Descriptor descriptor() {
        return descriptor;
    }

    /** The loader the roles were looked up in. */
    public ClassLoader loader() {
        return loader;
    }

    /** True if the role was found (itself or an alternative). */
    public boolean has(String role) {
        return entries.containsKey(role);
    }

    /** Why the role is missing, or null if it was found (or the descriptor has no such role). */
    public String missingReason(String role) {
        return missing.get(role);
    }

    /** Every missing role with its reason, in the descriptor's order. */
    public Map<String, String> missing() {
        return missing;
    }

    /** The groups ({@code "for"}) that miss at least one role. */
    public Set<String> missingGroups() {
        Set<String> out = new LinkedHashSet<String>();
        for (String name : missing.keySet()) {
            out.add(descriptor.role(name).group());
        }
        return out;
    }

    /** True when every role of the group was found. */
    public boolean complete(String group) {
        return !missingGroups().contains(group);
    }

    /** The first missing role of the group as {@code "role: reason"}, or null when the group is complete. */
    public String firstMissing(String group) {
        for (Map.Entry<String, String> e : missing.entrySet()) {
            if (descriptor.role(e.getKey()).group().equals(group)) {
                return e.getKey() + ": " + e.getValue();
            }
        }
        return null;
    }

    /** The role's class, or null when the role is missing or matched by name only. */
    public Class<?> type(String role) {
        Entry e = entries.get(role);
        return e == null ? null : e.type;
    }

    /**
     * True if {@code object} is of the role's class: {@code isInstance} for a loaded role, and for a role matched by
     * name, a superclass or interface of the object's class with that name (nothing is loaded). False for a missing
     * role or null.
     */
    public boolean isInstance(String role, Object object) {
        Entry e = entries.get(role);
        if (e == null || object == null) {
            return false;
        }
        if (e.type != null) {
            return e.type.isInstance(object);
        }
        return hasTypeNamed(object.getClass(), e.role.className());
    }

    private static boolean hasTypeNamed(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name)) {
                return true;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (hasTypeNamed(i, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Calls the role's method on {@code target} (null for a static method).
     *
     * @throws IllegalStateException if the role is missing or is not a method (a defect in the adapter)
     * @throws ReflectiveException if the vendor's method threw, or the call could not be made
     */
    public Object invoke(String role, Object target, Object... args) throws ReflectiveException {
        Entry e = require(role);
        if (e.method == null) {
            throw new IllegalStateException("role " + role + " is not a method");
        }
        try {
            return e.method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw new ReflectiveException(role, ex.getCause());
        } catch (IllegalAccessException ex) {
            throw new ReflectiveException(role, ex);
        } catch (IllegalArgumentException ex) {
            throw new ReflectiveException(role, ex);
        } catch (NullPointerException ex) {
            throw new ReflectiveException(role, ex);
        } catch (LinkageError ex) {
            throw new ReflectiveException(role, ex);
        }
    }

    /**
     * Reads the role's field of {@code target} (null for a static field).
     *
     * @throws IllegalStateException if the role is missing or is not a field (a defect in the adapter)
     * @throws ReflectiveException if the field cannot be read
     */
    public Object read(String role, Object target) throws ReflectiveException {
        Entry e = require(role);
        if (e.field == null) {
            throw new IllegalStateException("role " + role + " is not a field");
        }
        try {
            return e.field.get(target);
        } catch (IllegalAccessException ex) {
            throw new ReflectiveException(role, ex);
        } catch (IllegalArgumentException ex) {
            throw new ReflectiveException(role, ex);
        } catch (NullPointerException ex) {
            throw new ReflectiveException(role, ex);
        } catch (LinkageError ex) {
            throw new ReflectiveException(role, ex);
        }
    }

    /**
     * The role's value for {@code target}: a no-argument method is invoked, a field is read (whichever the role, or
     * the alternative that was found, names).
     *
     * @throws IllegalStateException if the role is missing or is a class only (a defect in the adapter)
     * @throws ReflectiveException if the call or the read failed
     */
    public Object get(String role, Object target) throws ReflectiveException {
        Entry e = require(role);
        if (e.method != null) {
            return invoke(role, target);
        }
        if (e.field != null) {
            return read(role, target);
        }
        throw new IllegalStateException("role " + role + " is a class only");
    }

    /**
     * Implements the role's vendor interface with a {@link Proxy} defined in the interface's own loader (the vendor's),
     * so that the vendor can call it. The handler sees the interface's methods only; {@code equals}, {@code hashCode}
     * and {@code toString} answer by the proxy's identity and never reach it. A handler that throws is the handler's
     * own business: the vendor sees what it throws.
     *
     * @throws IllegalStateException if the role is missing or is not an interface (a defect in the adapter)
     */
    public Object implement(String role, final InvocationHandler handler) {
        Entry e = require(role);
        if (e.type == null || !e.type.isInterface()) {
            throw new IllegalStateException("role " + role + " is not an interface");
        }
        final String label = "skillroy:" + e.type.getName();
        return Proxy.newProxyInstance(e.type.getClassLoader(), new Class<?>[] {e.type}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getDeclaringClass() == Object.class) {
                    String name = method.getName();
                    if ("equals".equals(name)) {
                        return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                    }
                    if ("hashCode".equals(name)) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    return label + "@" + Integer.toHexString(System.identityHashCode(proxy));
                }
                return handler.invoke(proxy, method, args);
            }
        });
    }

    private Entry require(String role) {
        Entry e = entries.get(role);
        if (e == null) {
            throw new IllegalStateException("role " + role + " is missing: " + missing.get(role));
        }
        return e;
    }

    @Override
    public String toString() {
        return "ResolvedApi{" + descriptor.source() + ", " + entries.size() + " found, " + missing.size()
                + " missing}";
    }

    /** One resolved role. */
    private static final class Entry {
        final Descriptor.Role role;
        final Class<?> type;
        final Method method;
        final Field field;

        Entry(Descriptor.Role role, Class<?> type, Method method, Field field) {
            this.role = role;
            this.type = type;
            this.method = method;
            this.field = field;
        }
    }

    /** A role that cannot be found; the message says why. */
    private static final class Missing extends Exception {
        private static final long serialVersionUID = 1L;

        Missing(String why) {
            super(why, null, false, false);
        }
    }
}
