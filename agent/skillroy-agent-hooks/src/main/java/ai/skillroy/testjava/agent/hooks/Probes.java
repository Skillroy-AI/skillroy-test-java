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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.instrument.CodeSources;
import ai.skillroy.testjava.agent.api.instrument.RetransformBatches;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookProbe;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.text.BoundedRegex;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Probing a spec (phase6-contracts.md section 3.3): the checks in order, the first failure deciding the state, every
 * finding in the answer. The engine never loads the target class itself; reading its methods loads (without
 * initialising) the types their signatures name, as Byte Buddy's own description does at install.
 *
 * <p>Phase 8 (phase8-contracts.md section 6.4): item 2b, the class deny, between the protected packages and "loaded?",
 * so that a denied class answers the same whether or not it is loaded and nothing of it is read; and item 3's
 * code-source filter, which keeps the copies whose code-source name the target's {@code codeSource} matches as a whole
 * (through {@link BoundedRegex}, the clocked matcher, on the anchored pattern) and reports every copy's name in
 * {@code codeSources}, qualifier or not.
 *
 * <p>Used on agent workers only, under the engine's lock for an install.
 */
final class Probes {

    /** The protected packages (D124): the JDK's, Byteman's, the agent's own and the protocol's. */
    static final String[] PROTECTED = {"java.", "javax.", "sun.", "com.sun.", "jdk.", "org.jboss.byteman.",
            "ai.skillroy.testjava.agent.", "ai.skillroy.testjava.protocol."};
    /** A loader whose own class is under this prefix is the agent's (the agent loader, every module loader). */
    static final String AGENT_LOADER_PREFIX = "ai.skillroy.testjava.agent.";
    static final String STRATEGY = "advice";
    /** Item 3 (c): a copy's code source could not be read under a qualifier (restrictive, all copies or none). */
    static final String CODE_SOURCE_UNREADABLE = "the class's code source cannot be read: SecurityException";
    /** Item 3 (c): the qualifier ran past {@link BoundedRegex#BUDGET_MS} on a copy's name (D640). */
    static final String CODE_SOURCE_TOO_SLOW = "codeSource regex too slow";
    /** Item 3 (c): no copy's code source matches the qualifier; the names follow. */
    static final String OTHER_CODE_SOURCE = "class from another code source: ";
    /** The most names a refusal lists. */
    static final int REFUSAL_NAMES = 4;

    /** Item 2b's sources (phase8-contracts.md section 6.5): null to allow, else the refusal's reason. */
    interface Deny {
        String refuse(String className);
    }

    /** Reads a copy's code-source name: {@link CodeSources#of}, or a test's seam. */
    interface CodeSourceReader {
        String of(Class<?> type);
    }

    /** The reader every engine uses. */
    static final CodeSourceReader CODE_SOURCES = new CodeSourceReader() {
        @Override
        public String of(Class<?> type) {
            return CodeSources.of(type);
        }
    };

    /** One class a spec matched, with the methods it pins. */
    static final class Match {
        final Class<?> type;
        final long sizeBytes;
        final int methods;
        final List<Method> pinned;
        /** The copy's code-source name, read once by this probe. */
        final String codeSource;

        Match(Class<?> type, long sizeBytes, int methods, List<Method> pinned, String codeSource) {
            this.type = type;
            this.sizeBytes = sizeBytes;
            this.methods = methods;
            this.pinned = pinned;
            this.codeSource = codeSource;
        }
    }

    /** A spec's probe answer and, when it passes, the classes it matched. */
    static final class Outcome {
        final HookSpec spec;
        HookProbe probe;
        final List<Match> matches;

        Outcome(HookSpec spec, HookProbe probe, List<Match> matches) {
            this.spec = spec;
            this.probe = probe;
            this.matches = matches;
        }

        boolean passes() {
            return probe.state() != CapabilityState.UNAVAILABLE;
        }

        void refuse(String reason, HookProbe.SpyVisibility visibility) {
            probe = new HookProbe(spec.id(), CapabilityState.UNAVAILABLE, reason, null, probe.loaders(),
                    probe.loaderIds(), probe.codeSources(), probe.declared(), probe.sizeBytes(), probe.methods(),
                    visibility);
        }
    }

    private final Instrumentation inst;
    private final ClassLoader hostLoader;
    private final HookOptions options;
    private final Ledger ledger;
    private final Deny deny;
    /** The code-source reader; a package-private seam for the tests (an unreadable code source). */
    volatile CodeSourceReader reader = CODE_SOURCES;

    Probes(Instrumentation inst, ClassLoader hostLoader, HookOptions options, Ledger ledger, Deny deny) {
        this.inst = inst;
        this.hostLoader = hostLoader;
        this.options = options;
        this.ledger = ledger;
        this.deny = deny;
    }

    /**
     * The loaded classes the specs name, by binary name: {@code getAllLoadedClasses()} read once for the call (it
     * allocates an array of every loaded class) and filtered to the names wanted.
     */
    Map<String, List<Class<?>>> index(Collection<HookSpec> specs) {
        Map<String, List<Class<?>>> index = new HashMap<String, List<Class<?>>>();
        for (HookSpec spec : specs) {
            index.put(spec.target().className(), new ArrayList<Class<?>>());
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.isArray() || c.isPrimitive()) {
                continue;
            }
            List<Class<?>> list = index.get(c.getName());
            if (list != null) {
                list.add(c);
            }
        }
        return index;
    }

    /**
     * Item 1's constructor state, items 2, 2b, 3 to 5 and 7, and item 6 as far as it can be decided now ({@code boot}:
     * the bootstrap Spy or null).
     */
    Outcome probe(HookSpec spec, Map<String, List<Class<?>>> index, Class<?> boot) {
        String id = spec.id();
        String className = spec.target().className();
        if (HookSpec.CONSTRUCTOR.equals(spec.target().method())) {
            return refused(spec, "constructors are not hookable", null, null, null);
        }
        for (String prefix : PROTECTED) {
            if (className.startsWith(prefix)) {
                return refused(spec, "class in a protected package", null, null, null);
            }
        }
        // item 2b, the class deny: before "loaded?", so that nothing of a denied class is read
        String denied = deny == null ? null : deny.refuse(className);
        if (denied != null) {
            return refused(spec, denied, null, null, null);
        }
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        for (Class<?> c : index.containsKey(className) ? index.get(className) : Collections.<Class<?>>emptyList()) {
            if (loaderMatches(spec.target().loader(), c.getClassLoader())) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            return refused(spec, "class not loaded", null, null, null);
        }
        List<String> loaderIds = new ArrayList<String>();
        List<String> codeSources = new ArrayList<String>();
        CodeSourceReader read = reader;
        for (Class<?> c : candidates) {
            loaderIds.add(Ledger.loaderId(c.getClassLoader()));
            codeSources.add(read.of(c));
        }
        Integer loaders = Integer.valueOf(candidates.size());
        // item 3 (c), the code-source filter, only under a qualifier
        List<Class<?>> kept = candidates;
        List<String> keptSources = codeSources;
        String qualifier = spec.target().codeSource();
        if (qualifier != null) {
            String refusal = null;
            kept = new ArrayList<Class<?>>();
            keptSources = new ArrayList<String>();
            if (codeSources.contains(CodeSources.UNREADABLE)) {
                refusal = CODE_SOURCE_UNREADABLE;
            } else {
                Pattern pattern;
                try {
                    pattern = Pattern.compile("^(?:" + qualifier + ")$");
                } catch (PatternSyntaxException e) {
                    return refused(spec, "not a regular expression: " + e.getDescription(), loaders, loaderIds,
                            codeSources);
                }
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BoundedRegex.BUDGET_MS);
                for (int i = 0; i < candidates.size() && refusal == null; i++) {
                    try {
                        if (BoundedRegex.find(pattern, codeSources.get(i), deadline - System.nanoTime())) {
                            kept.add(candidates.get(i));
                            keptSources.add(codeSources.get(i));
                        }
                    } catch (BoundedRegex.TooSlow e) {
                        refusal = CODE_SOURCE_TOO_SLOW;
                    }
                }
                if (refusal == null && kept.isEmpty()) {
                    refusal = OTHER_CODE_SOURCE + names(codeSources);
                }
            }
            if (refusal != null) {
                return refused(spec, refusal, loaders, loaderIds, codeSources);
            }
        }
        List<Match> matches = new ArrayList<Match>();
        long maxSize = 0;
        int maxMethods = 0;
        HookProbe.SpyVisibility visibility = HookProbe.SpyVisibility.VISIBLE;
        String degraded = null;
        for (int k = 0; k < kept.size(); k++) {
            Class<?> c = kept.get(k);
            ClassLoader loader = c.getClassLoader();
            if (isAgentOrJdkLoader(loader)) {
                return refused(spec, "class is the agent's own or the JDK's", loaders, loaderIds, codeSources);
            }
            if (!inst.isModifiableClass(c)) {
                return refused(spec, "class is not modifiable", loaders, loaderIds, codeSources);
            }
            Ledger.Entry entry = ledger.find(c.getName().replace('.', '/'), c);
            byte[] first = entry == null ? null : entry.first;
            long size = first != null ? first.length : RetransformBatches.CLASS_FILE_RESOURCE.bytes(c);
            Method[] declared;
            int methods;
            try {
                declared = c.getDeclaredMethods();
                methods = declared.length + c.getDeclaredConstructors().length;
            } catch (LinkageError e) {
                return refused(spec, "a method signature of the class names a missing type: " + missingType(e),
                        loaders, loaderIds, codeSources);
            }
            maxSize = Math.max(maxSize, size);
            maxMethods = Math.max(maxMethods, methods);
            if (size > options.maxClassBytes) {
                return new Outcome(spec, answer(id, CapabilityState.UNAVAILABLE, "class too large: " + size
                        + " bytes > " + options.maxClassBytes, loaders, loaderIds, codeSources, null, size, methods,
                        null), null);
            }
            if (methods > options.maxMethods) {
                return new Outcome(spec, answer(id, CapabilityState.UNAVAILABLE, "class too large: " + methods
                        + " methods > " + options.maxMethods, loaders, loaderIds, codeSources, null, size, methods,
                        null), null);
            }
            List<Method> pinned = new ArrayList<Method>();
            for (String descriptor : spec.target().descriptors()) {
                Method found = null;
                List<String> sameName = new ArrayList<String>();
                for (Method m : declared) {
                    if (m.getName().equals(spec.target().method())) {
                        String d = Descriptors.of(m);
                        sameName.add(d);
                        if (d.equals(descriptor)) {
                            found = m;
                        }
                    }
                }
                if (found == null) {
                    return new Outcome(spec, answer(id, CapabilityState.UNAVAILABLE, "no method "
                            + spec.target().method() + descriptor, loaders, loaderIds, codeSources, sameName, size,
                            methods, null), null);
                }
                if (Modifier.isNative(found.getModifiers())) {
                    return refused(spec, "method is native", loaders, loaderIds, codeSources);
                }
                if (Modifier.isAbstract(found.getModifiers())) {
                    return refused(spec, "method is abstract", loaders, loaderIds, codeSources);
                }
                pinned.add(found);
            }
            String[] seen = SpyBoot.visibility(loader, boot);
            HookProbe.SpyVisibility v = HookProbe.SpyVisibility.valueOf(seen[0]);
            if (v == HookProbe.SpyVisibility.NOT_VISIBLE) {
                return new Outcome(spec, answer(id, CapabilityState.UNAVAILABLE, seen[1], loaders, loaderIds,
                        codeSources, null, size, methods, v), null);
            }
            if (v == HookProbe.SpyVisibility.UNCHECKED) {
                visibility = v;
            }
            if (spec.mode() == HookMode.CAPTURE && nothingToCapture(spec.capture(), pinned)) {
                degraded = "nothing to capture";
            }
            matches.add(new Match(c, size, methods, pinned, keptSources.get(k)));
        }
        CapabilityState state = degraded == null ? CapabilityState.OK : CapabilityState.DEGRADED;
        return new Outcome(spec, answer(id, state, degraded, loaders, loaderIds, codeSources, null, maxSize,
                maxMethods, visibility), matches);
    }

    /**
     * The distinct names of a refusal, sorted (the loaded classes' order is the JVM's), at most
     * {@link #REFUSAL_NAMES}, then "and n more".
     */
    static String names(List<String> codeSources) {
        Set<String> distinct = new TreeSet<String>(codeSources);
        StringBuilder out = new StringBuilder();
        int i = 0;
        for (String name : distinct) {
            if (i == REFUSAL_NAMES) {
                out.append(" and ").append(distinct.size() - REFUSAL_NAMES).append(" more");
                break;
            }
            if (i > 0) {
                out.append(", ");
            }
            out.append(name);
            i++;
        }
        return out.toString();
    }

    private static Outcome refused(HookSpec spec, String reason, Integer loaders, List<String> loaderIds,
                                   List<String> codeSources) {
        return new Outcome(spec, answer(spec.id(), CapabilityState.UNAVAILABLE, reason, loaders, loaderIds,
                codeSources, null, null, null, null), null);
    }

    private static HookProbe answer(String id, CapabilityState state, String reason, Integer loaders,
                                    List<String> loaderIds, List<String> codeSources, List<String> declared,
                                    Long size, Integer methods, HookProbe.SpyVisibility visibility) {
        return new HookProbe(id, state, reason, state == CapabilityState.UNAVAILABLE ? null : STRATEGY, loaders,
                loaderIds, codeSources, declared, size, methods, visibility);
    }

    private static HookProbe answer(String id, CapabilityState state, String reason, Integer loaders,
                                    List<String> loaderIds, List<String> codeSources, List<String> declared,
                                    long size, int methods, HookProbe.SpyVisibility visibility) {
        return answer(id, state, reason, loaders, loaderIds, codeSources, declared, Long.valueOf(size),
                Integer.valueOf(methods), visibility);
    }

    /** True when CAPTURE would copy nothing: no in-range argument, no result of a non-void method, no receiver. */
    static boolean nothingToCapture(HookCapture capture, List<Method> pinned) {
        if (capture == null || capture.isEmpty()) {
            return true;
        }
        for (Method m : pinned) {
            for (Integer i : capture.args()) {
                if (i.intValue() < m.getParameterTypes().length) {
                    return false;
                }
            }
            if (capture.result() && m.getReturnType() != void.class) {
                return false;
            }
            if (capture.self() && !Modifier.isStatic(m.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private boolean loaderMatches(String filter, ClassLoader loader) {
        if (filter == null) {
            return true;
        }
        if ("system".equals(filter)) {
            return loader == ClassLoader.getSystemClassLoader();
        }
        if ("host".equals(filter)) {
            return loader == hostLoader;
        }
        return loader != null && filter.equalsIgnoreCase(Integer.toHexString(System.identityHashCode(loader)));
    }

    /**
     * The bootstrap, platform (Java 9 and later) or extension (Java 8) loader, or a loader of the agent's own: one
     * whose class is under {@code ai.skillroy.testjava.agent.} (the agent loader and every module loader), or one of
     * the engine's own loader chain up to the host's (D124).
     */
    boolean isAgentOrJdkLoader(ClassLoader loader) {
        if (loader == null) {
            return true;
        }
        if (loader == platformLoader()) {
            return true;
        }
        if (loader.getClass().getName().startsWith(AGENT_LOADER_PREFIX)) {
            return true;
        }
        for (ClassLoader own = Probes.class.getClassLoader(); own != null && own != hostLoader
                && own != ClassLoader.getSystemClassLoader(); own = own.getParent()) {
            if (own == loader) {
                return true;
            }
        }
        return false;
    }

    private static volatile Object platform;

    /** The platform loader (Java 9 and later, reflectively) or the extension loader (Java 8). */
    static ClassLoader platformLoader() {
        Object p = platform;
        if (p == null) {
            ClassLoader found;
            try {
                found = (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
            } catch (Exception java8) {
                found = ClassLoader.getSystemClassLoader().getParent();
            }
            platform = found == null ? Boolean.FALSE : found;
            return found;
        }
        return p instanceof ClassLoader ? (ClassLoader) p : null;
    }

    private static String missingType(LinkageError e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getName() : message.replace('/', '.');
    }
}
