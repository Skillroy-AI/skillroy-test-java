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

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.instrument.ClassFileRefs;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.agent.builder.AgentBuilder;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.asm.Advice;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.asm.AsmVisitorWrapper;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.description.method.MethodDescription;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.description.type.TypeDescription;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.dynamic.DynamicType;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.dynamic.Nexus;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.dynamic.NexusAccessor;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.matcher.ElementMatchers;
import ai.skillroy.testjava.agent.hooks.shaded.bytebuddy.utility.JavaModule;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's three class-file transformers (phase6-contracts.md sections 3.2 and 3.5, D122, D155):
 *
 * <ul>
 *   <li>the {@link Recorder}, a no-op registered <b>ahead of</b> the hook transformer, which keeps the bytes of a
 *       touched class's first transform (the reference of the restore) and counts every transform event of it (the
 *       engine's own and anyone else's);</li>
 *   <li>the {@link PreFilter}, the registered hook transformer: it answers null at once for a class the ledger does not
 *       hold (a registered transformer costs the host 3-8 µs on every class it loads, BS3 RESULT section 9 fact 20),
 *       for a class being restored and for a class larger than {@code hooks.maxClassBytes}, and otherwise calls Byte
 *       Buddy's transformer, taken with {@code makeRaw()} from one {@code AgentBuilder} configured
 *       {@code disableClassFormatChanges()} (the frozen type strategy, never replaced), {@code RedefinitionStrategy
 *       .DISABLED} (the engine retransforms the ledger's classes itself), {@code InitializationStrategy.NoOp} and
 *       the engine's own type matcher. It holds the engine's handler in a final field: while it is registered (while
 *       any class carries an advice) the handler stays strongly reachable from the JVM and the weak {@code Spy} slot
 *       cannot clear (D117);</li>
 *   <li>the {@link After} recorder, registered <b>last</b> for one restore's retransformation only: what it sees is
 *       what the JVM installs, so the restore and its verification are one retransformation.</li>
 * </ul>
 *
 * <p>Before the first {@code AgentBuilder} is constructed the {@code Nexus} measure is taken (BS3 RESULT section 6):
 * Byte Buddy's default initialisation strategy would inject {@code Nexus} into the system loader (and, on Java 9 and
 * later, an accessor into {@code java.lang} through {@code sun.misc.Unsafe}) when its {@code NexusAccessor}
 * initialises; the relocated {@code nexus.disabled} property is set while it does, then cleared.
 */
final class Transformers {

    /** The internal name every advised class references. */
    static final String SPY_INTERNAL = "ai/skillroy/testjava/agent/spy/Spy";

    private final Ledger ledger;
    private final AgentLog log;
    private final Map<String, AsmVisitorWrapper> wrappers = new ConcurrentHashMap<String, AsmVisitorWrapper>();
    final Recorder recorder;
    final PreFilter preFilter;
    /** The install or remove in progress, whose transformations are collected; null between them. */
    volatile Round round;

    Transformers(Ledger ledger, Object handler, long maxClassBytes, AgentLog log) {
        this.ledger = ledger;
        this.log = log;
        disableNexus();
        this.recorder = new Recorder(ledger);
        ClassFileTransformer raw = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(new Listener())
                .ignore(new AgentBuilder.RawMatcher.Inversion(new Matcher()))
                .type(new Matcher())
                .transform(new Transformer())
                .makeRaw();
        this.preFilter = new PreFilter(ledger, raw, handler, maxClassBytes);
    }

    /**
     * Sets the relocated {@code nexus.disabled} property while Byte Buddy's {@code NexusAccessor} initialises, then
     * restores it (the property name is relocated with the code, so a host's own Byte Buddy is untouched).
     */
    static void disableNexus() {
        String name = Nexus.PROPERTY;
        String previous = System.getProperty(name);
        System.setProperty(name, "true");
        try {
            Class.forName(NexusAccessor.class.getName(), true, Transformers.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the relocated Byte Buddy is incomplete", e);
        } finally {
            if (previous == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, previous);
            }
        }
    }

    /** The relocated property the measure sets (for the tests). */
    static String nexusProperty() {
        return Nexus.PROPERTY;
    }

    /** Builds and caches the advice of a site; on the engine's worker, while the module's loader is open. */
    void prepare(Site site) {
        if (wrappers.containsKey(site.key)) {
            return;
        }
        Class<?> advice = site.isVoid ? VoidAdvice.class : ValueAdvice.class;
        AsmVisitorWrapper wrapper = Advice.withCustomMapping().bind(HookId.class, site.key).to(advice)
                .on(ElementMatchers.<MethodDescription>named(site.method)
                        .and(ElementMatchers.<MethodDescription>hasDescriptor(site.descriptor)));
        wrappers.put(site.key, wrapper);
    }

    void forget(Site site) {
        wrappers.remove(site.key);
    }

    /** What one install or remove's retransformations produced, by {@code name@loaderId}. */
    static final class Round {
        final Set<String> transformed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        final Map<String, String> errors = new ConcurrentHashMap<String, String>();

        static String key(String name, ClassLoader loader) {
            return name + "@" + Ledger.loaderId(loader);
        }
    }

    private final class Matcher implements AgentBuilder.RawMatcher {
        @Override
        public boolean matches(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                               Class<?> classBeingRedefined, ProtectionDomain protectionDomain) {
            if (classBeingRedefined == null) {
                return false;
            }
            Ledger.Entry e = ledger.find(typeDescription.getInternalName(), classBeingRedefined);
            return e != null && !e.restoring && !e.sites.isEmpty();
        }
    }

    private final class Transformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
                                                ClassLoader classLoader, JavaModule module,
                                                ProtectionDomain protectionDomain) {
            Ledger.Entry e = ledger.find(typeDescription.getName(), classLoader);
            if (e == null) {
                return builder;
            }
            DynamicType.Builder<?> b = builder;
            for (Site site : e.sites) {
                AsmVisitorWrapper wrapper = wrappers.get(site.key);
                if (wrapper != null) {
                    b = b.visit(wrapper);
                }
            }
            return b;
        }
    }

    private final class Listener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                     boolean loaded, DynamicType dynamicType) {
            Round r = round;
            if (r == null) {
                return;
            }
            String key = Round.key(typeDescription.getName(), classLoader);
            try {
                if (ClassFileRefs.classReferences(dynamicType.getBytes()).contains(SPY_INTERNAL)) {
                    r.transformed.add(key);
                } else {
                    r.errors.put(key, "the advice was not applied (no method matched)");
                }
            } catch (RuntimeException e) {
                r.errors.put(key, "the transformed class is unreadable: " + e.getMessage());
            }
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                            Throwable throwable) {
            String text = throwable.getClass().getName() + ": " + throwable.getMessage();
            Round r = round;
            if (r != null) {
                r.errors.put(Round.key(typeName, classLoader), text);
            } else {
                log.warn("hooks: transforming " + typeName + " failed outside an install: " + text);
            }
        }
    }

    /** No-op, retransformation-capable, registered ahead of the hook transformer (D122). */
    static final class Recorder implements ClassFileTransformer {
        private final Ledger ledger;

        Recorder(Ledger ledger) {
            this.ledger = ledger;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            try {
                if (classBeingRedefined != null) {
                    Ledger.Entry e = ledger.find(className, classBeingRedefined);
                    if (e != null) {
                        e.events.incrementAndGet();
                        if (e.first == null) {
                            e.first = classfileBuffer.clone();
                        }
                    }
                }
            } catch (Throwable ignored) {
                // A recorder never fails a transformation.
            }
            return null;
        }
    }

    /** The registered hook transformer: the name pre-filter in front of Byte Buddy's (D155). */
    static final class PreFilter implements ClassFileTransformer {
        private final Ledger ledger;
        private final ClassFileTransformer raw;
        /** Held so that the handler stays strongly reachable while this transformer is registered (D117). */
        final Object handler;
        private final long maxClassBytes;

        PreFilter(Ledger ledger, ClassFileTransformer raw, Object handler, long maxClassBytes) {
            this.ledger = ledger;
            this.raw = raw;
            this.handler = handler;
            this.maxClassBytes = maxClassBytes;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == null || className == null) {
                return null;
            }
            Ledger.Entry e = ledger.find(className, classBeingRedefined);
            if (e == null || e.restoring || e.sites.isEmpty()) {
                return null;
            }
            if (classfileBuffer.length > maxClassBytes) {
                e.error = "class too large: " + classfileBuffer.length + " bytes > " + maxClassBytes;
                return null;
            }
            try {
                return raw.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            } catch (Throwable t) {
                e.error = "transform failed: " + t.getClass().getName() + ": " + t.getMessage();
                return null;
            }
        }
    }

    /** Registered last for one restore's retransformation: the bytes the JVM installs (D122). */
    static final class After implements ClassFileTransformer {
        private final Map<Class<?>, byte[]> seen = new IdentityHashMap<Class<?>, byte[]>();
        private final Set<Class<?>> watched;

        After(Set<Class<?>> watched) {
            this.watched = watched;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined != null && watched.contains(classBeingRedefined)) {
                synchronized (seen) {
                    seen.put(classBeingRedefined, classfileBuffer.clone());
                }
            }
            return null;
        }

        byte[] bytes(Class<?> type) {
            synchronized (seen) {
                return seen.get(type);
            }
        }
    }

    /** For the engine's log: a short description of a batch. */
    static String describe(java.util.List<Class<?>> batch) {
        Map<String, Boolean> names = new LinkedHashMap<String, Boolean>();
        for (Class<?> c : batch) {
            names.put(c.getName(), Boolean.TRUE);
        }
        return names.keySet().toString();
    }
}
