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

import ai.skillroy.testjava.protocol.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The class and member names one embedded-browser technology is reached through (phase4-contracts.md section 4.7,
 * decision D54), read from a small JSON resource such as {@code jcef.json} or {@code jxbrowser.json}. An adapter
 * never names a vendor class in its code: it names <em>roles</em> ({@code browser.url},
 * {@code frame.executeJavaScriptAsync}, ...) and {@link ReflectiveApi#resolve} looks the roles up in the vendor's class
 * loader. A fork of a technology, or another product line, is served by another descriptor without a code change;
 * the neutral fake of the contract kit is the same adapter code driven by a descriptor whose classes live under this
 * project's package.
 *
 * <p>The format ({@value #FORMAT}), a JSON object:
 * <pre>
 * {
 *   "format": 1,
 *   "adapter": "jxbrowser",                          // the adapter the descriptor is for
 *   "gate": "com.example.engine.Engine",             // the class whose being loaded by the host means "present"
 *   "versionResource": "com/example/version.info",   // optional: a resource with the product's version
 *   "roles": {
 *     "browser.url": {                               // role name: letters, digits, '.', '_' and '$'
 *       "class": "com.example.browser.Browser",      // binary class name
 *       "method": "url",                             // or "field": "name", or neither (the class itself)
 *       "params": ["java.lang.String", "int"],       // method parameter types; default none
 *       "arity": 2,                                  // instead of params: the one method of that name and
 *                                                    //   parameter count (for generic signatures whose erasure
 *                                                    //   is the vendor's own bound)
 *       "static": false,                             // default false
 *       "access": "public",                          // "public" (default) or "private" (setAccessible)
 *       "interface": false,                          // true: a vendor interface the adapter implements (Proxy)
 *       "match": "type",                             // "type" (default) or "name": compared by class name only,
 *                                                    //   never loaded by the lookup itself (a class-only role)
 *       "for": "core",                               // the group the role serves (see below)
 *       "alternatives": [ { ...the same keys except "for" and "alternatives" } ]
 *     }
 *   }
 * }
 * </pre>
 * The group ({@code "for"}) says what a missing role costs. {@code "core"}: the adapter cannot work without it (its
 * probe answers UNAVAILABLE); the name of a {@code WebCapability} ({@code "BITMAP"}): that capability is absent; any
 * other name: a group the adapter interprets itself ({@code "walk"}, {@code "endpoints"}, ...). A missing optional
 * role is a missing capability, never an error. Alternatives are tried in order when the role's own member cannot be
 * found.
 *
 * <p>JSON resources carry no licence header: the strict parser accepts no comments.
 *
 * <p>Immutable; equality is identity (a descriptor is loaded once per adapter and keys the resolution cache).
 */
public final class Descriptor {

    /** The only descriptor format this version reads. */
    public static final int FORMAT = 1;

    /** The group of the roles an adapter cannot work without. */
    public static final String CORE = "core";

    private static final Pattern ROLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.$]*");
    private static final Pattern CLASS_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Set<String> TOP_KEYS = new HashSet<String>(Arrays.asList(
            "format", "adapter", "gate", "versionResource", "roles"));
    private static final Set<String> ROLE_KEYS = new HashSet<String>(Arrays.asList(
            "class", "method", "field", "params", "arity", "static", "access", "interface", "match", "for",
            "alternatives"));

    private final String source;
    private final String adapter;
    private final String gate;
    private final String versionResource;
    private final Map<String, Role> roles;

    private Descriptor(String source, String adapter, String gate, String versionResource, Map<String, Role> roles) {
        this.source = source;
        this.adapter = adapter;
        this.gate = gate;
        this.versionResource = versionResource;
        this.roles = Collections.unmodifiableMap(roles);
    }

    /**
     * Reads a descriptor from a class-path resource.
     *
     * @param resource the resource name, for example {@code ai/skillroy/testjava/agent/web/jcef.json}
     * @param loader the loader to read it with (the adapter's own, never the host's)
     * @throws IllegalArgumentException if the resource is missing or not a valid descriptor
     */
    public static Descriptor load(String resource, ClassLoader loader) {
        InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalArgumentException("missing descriptor resource " + resource);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return parse(resource, new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read descriptor " + resource + ": " + e.getMessage(), e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // a class-path resource
            }
        }
    }

    /**
     * Parses a descriptor.
     *
     * @param source where the text came from, for messages
     * @throws IllegalArgumentException if the text is not a valid descriptor of format {@value #FORMAT}
     */
    public static Descriptor parse(String source, String json) {
        Map<String, Object> top;
        try {
            top = Json.parseObject(json);
        } catch (RuntimeException e) {
            throw invalid(source, "not a JSON object: " + e.getMessage());
        }
        for (String key : top.keySet()) {
            if (!TOP_KEYS.contains(key)) {
                throw invalid(source, "unknown key '" + key + "'");
            }
        }
        Object format = top.get("format");
        if (!(format instanceof Number) || ((Number) format).intValue() != FORMAT) {
            throw invalid(source, "format must be " + FORMAT);
        }
        String adapter = requireString(source, top, "adapter");
        String gate = requireString(source, top, "gate");
        requireClassName(source, "gate", gate);
        Object version = top.get("versionResource");
        if (version != null && !(version instanceof String)) {
            throw invalid(source, "versionResource must be a string");
        }
        Object rawRoles = top.get("roles");
        if (!(rawRoles instanceof Map)) {
            throw invalid(source, "roles must be an object");
        }
        Map<String, Role> roles = new LinkedHashMap<String, Role>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) rawRoles).entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!ROLE_NAME.matcher(name).matches()) {
                throw invalid(source, "invalid role name '" + name + "'");
            }
            roles.put(name, role(source, name, e.getValue(), true));
        }
        return new Descriptor(source, adapter, gate, (String) version, roles);
    }

    private static Role role(String source, String name, Object raw, boolean top) {
        if (!(raw instanceof Map)) {
            throw invalid(source, "role " + name + " must be an object");
        }
        Map<?, ?> m = (Map<?, ?>) raw;
        for (Object key : m.keySet()) {
            String k = String.valueOf(key);
            if (!ROLE_KEYS.contains(k) || (!top && ("for".equals(k) || "alternatives".equals(k)))) {
                throw invalid(source, "role " + name + ": unknown key '" + k + "'");
            }
        }
        String cls = requireString(source, m, "class", "role " + name);
        requireClassName(source, "role " + name, cls);
        String method = optionalString(source, m, "method", name);
        String field = optionalString(source, m, "field", name);
        if (method != null && field != null) {
            throw invalid(source, "role " + name + " names both a method and a field");
        }
        List<String> params = new ArrayList<String>();
        Object rawParams = m.get("params");
        if (rawParams != null) {
            if (!(rawParams instanceof List) || method == null) {
                throw invalid(source, "role " + name + ": params must be a list and need a method");
            }
            for (Object p : (List<?>) rawParams) {
                if (!(p instanceof String) || !CLASS_NAME.matcher((String) p).matches()) {
                    throw invalid(source, "role " + name + ": invalid parameter type " + p);
                }
                params.add((String) p);
            }
        }
        int arity = -1;
        Object rawArity = m.get("arity");
        if (rawArity != null) {
            if (!(rawArity instanceof Number) || method == null || rawParams != null
                    || ((Number) rawArity).intValue() < 0) {
                throw invalid(source, "role " + name + ": arity must be a non-negative number, with a method and "
                        + "without params");
            }
            arity = ((Number) rawArity).intValue();
        }
        boolean isStatic = flag(source, m, "static", name);
        boolean isInterface = flag(source, m, "interface", name);
        String access = optionalString(source, m, "access", name);
        if (access != null && !"public".equals(access) && !"private".equals(access)) {
            throw invalid(source, "role " + name + ": access must be public or private");
        }
        String match = optionalString(source, m, "match", name);
        if (match != null && !"type".equals(match) && !"name".equals(match)) {
            throw invalid(source, "role " + name + ": match must be type or name");
        }
        boolean byName = "name".equals(match);
        if (byName && (method != null || field != null || isInterface)) {
            throw invalid(source, "role " + name + ": a role matched by name is a class only");
        }
        String group = top ? optionalString(source, m, "for", name) : null;
        if (top && group == null) {
            throw invalid(source, "role " + name + " needs \"for\"");
        }
        List<Role> alternatives = new ArrayList<Role>();
        Object rawAlternatives = top ? m.get("alternatives") : null;
        if (rawAlternatives != null) {
            if (!(rawAlternatives instanceof List)) {
                throw invalid(source, "role " + name + ": alternatives must be a list");
            }
            for (Object alt : (List<?>) rawAlternatives) {
                alternatives.add(role(source, name, alt, false));
            }
        }
        return new Role(name, cls, method, field, params, arity, isStatic, "private".equals(access), isInterface,
                byName, group, alternatives);
    }

    private static String requireString(String source, Map<?, ?> m, String key) {
        return requireString(source, m, key, "descriptor");
    }

    private static String requireString(String source, Map<?, ?> m, String key, String where) {
        Object v = m.get(key);
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
            throw invalid(source, where + ": " + key + " must be a non-empty string");
        }
        return (String) v;
    }

    private static String optionalString(String source, Map<?, ?> m, String key, String role) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
            throw invalid(source, "role " + role + ": " + key + " must be a non-empty string");
        }
        return (String) v;
    }

    private static boolean flag(String source, Map<?, ?> m, String key, String role) {
        Object v = m.get(key);
        if (v == null) {
            return false;
        }
        if (!(v instanceof Boolean)) {
            throw invalid(source, "role " + role + ": " + key + " must be a boolean");
        }
        return ((Boolean) v).booleanValue();
    }

    private static void requireClassName(String source, String where, String name) {
        if (!CLASS_NAME.matcher(name).matches()) {
            throw invalid(source, where + ": invalid class name '" + name + "'");
        }
    }

    private static IllegalArgumentException invalid(String source, String why) {
        return new IllegalArgumentException("invalid descriptor " + source + ": " + why);
    }

    /** Where the descriptor came from (a resource name), for messages. */
    public String source() {
        return source;
    }

    /** The adapter id the descriptor is for. */
    public String adapter() {
        return adapter;
    }

    /** The class whose being loaded by the host means the technology is present. */
    public String gate() {
        return gate;
    }

    /** The resource that holds the product's version, or null. */
    public String versionResource() {
        return versionResource;
    }

    /** Every role name, in the descriptor's order. */
    public Set<String> roles() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(roles.keySet()));
    }

    /** One role, or null. */
    public Role role(String name) {
        return roles.get(name);
    }

    /** The class names the descriptor mentions, the gate first; for tests that compare descriptors. */
    public Set<String> classNames() {
        Set<String> out = new LinkedHashSet<String>();
        out.add(gate);
        for (Role r : roles.values()) {
            out.add(r.className());
            for (Role alt : r.alternatives()) {
                out.add(alt.className());
            }
            for (String p : r.params()) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "Descriptor{" + source + ", adapter=" + adapter + ", " + roles.size() + " roles}";
    }

    /** One role: a class, optionally one of its members, and the group that misses it when it cannot be found. */
    public static final class Role {
        private final String name;
        private final String className;
        private final String method;
        private final String field;
        private final List<String> params;
        private final int arity;
        private final boolean isStatic;
        private final boolean isPrivate;
        private final boolean isInterface;
        private final boolean byName;
        private final String group;
        private final List<Role> alternatives;

        Role(String name, String className, String method, String field, List<String> params, int arity,
             boolean isStatic, boolean isPrivate, boolean isInterface, boolean byName, String group,
             List<Role> alternatives) {
            this.name = name;
            this.className = className;
            this.method = method;
            this.field = field;
            this.params = Collections.unmodifiableList(params);
            this.arity = arity;
            this.isStatic = isStatic;
            this.isPrivate = isPrivate;
            this.isInterface = isInterface;
            this.byName = byName;
            this.group = group;
            this.alternatives = Collections.unmodifiableList(alternatives);
        }

        public String name() {
            return name;
        }

        /** The binary name of the role's class. */
        public String className() {
            return className;
        }

        /** The method name, or null. */
        public String method() {
            return method;
        }

        /** The field name, or null. */
        public String field() {
            return field;
        }

        /** The method's parameter types (binary names or primitive names). */
        public List<String> params() {
            return params;
        }

        /** The method's parameter count when it is matched by name and count only, else -1. */
        public int arity() {
            return arity;
        }

        public boolean isStatic() {
            return isStatic;
        }

        /** True for a non-public member, looked up with {@code getDeclared*} and made accessible. */
        public boolean isPrivate() {
            return isPrivate;
        }

        /** True for a vendor interface the adapter implements with a {@code Proxy}. */
        public boolean isInterface() {
            return isInterface;
        }

        /**
         * True for a class compared by name only: the lookup never loads it (linking another role's class may still
         * load it, as verification loads the types a method body needs).
         */
        public boolean byName() {
            return byName;
        }

        /** The group the role serves ({@link #CORE}, a capability name, or the adapter's own). */
        public String group() {
            return group;
        }

        public List<Role> alternatives() {
            return alternatives;
        }

        /** {@code Class.member(params)} for messages. */
        public String describe() {
            StringBuilder sb = new StringBuilder(className);
            if (method != null) {
                sb.append('.').append(method).append('(');
                if (arity >= 0) {
                    sb.append(arity).append(" parameters");
                }
                for (int i = 0; i < params.size(); i++) {
                    sb.append(i == 0 ? "" : ", ").append(params.get(i));
                }
                sb.append(')');
            } else if (field != null) {
                sb.append('.').append(field);
            }
            return sb.toString();
        }
    }
}
