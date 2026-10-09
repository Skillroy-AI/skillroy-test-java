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

package ai.skillroy.testjava.spikes.bs3.engine;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.Nexus;
import net.bytebuddy.dynamic.NexusAccessor;
import net.bytebuddy.utility.JavaModule;

/**
 * The prototype hook engine: install (visibility probe, a byte recorder ahead of the hook, one Byte Buddy transformer
 * per hook, retransformation of exactly the target class), the touched-class ledger, and remove ({@code
 * removeTransformer}, the recorder moved to the end of the chain, one {@code retransformClasses(touched)}, the byte
 * check). Mode changes go through {@link HookRegistry} and never retransform.
 */
final class HookEngine {

    /** What to hook. */
    static final class Spec {
        final String id;
        final Class<?> target;
        final String method;
        final String descriptor;
        final HookMode mode;
        boolean raw;
        boolean force;
        /** Byte Buddy's default discovery (every loaded class is matched) instead of the explicit target. */
        boolean discoverAll;

        Spec(String id, Class<?> target, String method, String descriptor, HookMode mode) {
            this.id = id;
            this.target = target;
            this.method = method;
            this.descriptor = descriptor;
            this.mode = mode;
        }
    }

    /** One installation and what it did. */
    static final class Installed {
        final Spec spec;
        ResettableClassFileTransformer transformer;
        ByteRecorder recorder;
        String visibility;
        String status;
        volatile boolean transformed;
        volatile byte[] advised;
        final List<String> errors = Collections.synchronizedList(new ArrayList<String>());
        long buildNanos;
        long installOnNanos;
        long totalNanos;
        long pauseNanos;
        /** The engine's own retransformations of the target before this hook's recorder was registered. */
        int ownBase;

        Installed(Spec spec) {
            this.spec = spec;
        }

        boolean ok() {
            return "INSTALLED".equals(status);
        }
    }

    /** One removal and its byte check. */
    static final class Removal {
        String status = "RESTORED";
        long removeNanos;
        long pauseNanos;
        byte[] first;
        byte[] after;
        boolean bytesIdentical;
        boolean canonicalIdentical;
        List<String> diff = new ArrayList<String>();
        int recorderCalls;
        /**
         * Transformations of the class the recorder saw while the hook was installed that the engine did not cause:
         * another agent's retransformation or a redefinition. The byte check alone cannot see these on every JDK.
         */
        int foreignEvents;
    }

    final Instrumentation inst;
    final HookRegistry registry;
    final Ticker ticker;
    private final Map<String, Installed> installed = new LinkedHashMap<String, Installed>();
    /** How often the engine itself retransformed each class: the ledger's own share of the transform events. */
    private final Map<Class<?>, int[]> own = new java.util.IdentityHashMap<Class<?>, int[]>();

    private int own(Class<?> c) {
        int[] n = own.get(c);
        return n == null ? 0 : n[0];
    }

    private void countOwn(Class<?> c) {
        int[] n = own.get(c);
        if (n == null) {
            own.put(c, new int[] {1});
        } else {
            n[0]++;
        }
    }

    HookEngine(Instrumentation inst, HookRegistry registry, Ticker ticker) {
        this.inst = inst;
        this.registry = registry;
        this.ticker = ticker;
    }

    /**
     * {@code new AgentBuilder.Default()} builds the default {@code InitializationStrategy.SelfInjection.Split}, whose
     * {@code NexusAccessor} injects {@code Nexus} into the system class loader when its class initialises, before any
     * {@code .with(InitializationStrategy.NoOp)} can take effect (found by item 6). Byte Buddy skips the injection when
     * the property {@code Nexus.PROPERTY} is true at that moment; the constant is relocated with the rest, so this sets
     * the engine's own property name, forces the initialisation, and clears the property again.
     *
     * @return the dispatcher state Byte Buddy settled on, for the report
     */
    static String disableNexus(boolean disable) {
        if (!disable) {
            return "left at Byte Buddy's default";
        }
        // A local copy, so that the report's text is built at run time from the (relocated) constant; a constant
        // folded into a longer literal is not relocated by the shade plugin, because it no longer starts with the name.
        String name = Nexus.PROPERTY;
        String previous = System.getProperty(name);
        System.setProperty(name, "true");
        try {
            Class.forName(NexusAccessor.class.getName(), true, HookEngine.class.getClassLoader());
            return "disabled through " + name + " (set during NexusAccessor's initialisation, then cleared)";
        } catch (Throwable t) {
            return "failed: " + t;
        } finally {
            if (previous == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, previous);
            }
        }
    }

    synchronized Installed install(final Spec spec) {
        final Installed in = new Installed(spec);
        in.visibility = SpyBoot.visibility(spec.target.getClassLoader());
        if (!"VISIBLE".equals(in.visibility) && !spec.force) {
            in.status = "UNAVAILABLE(" + in.visibility + ")";
            return in;
        }
        if (!inst.isModifiableClass(spec.target)) {
            in.status = "UNAVAILABLE(not modifiable)";
            return in;
        }
        long t0 = System.nanoTime();
        registry.define(spec.id, spec.mode);
        in.recorder = new ByteRecorder(spec.target);
        in.ownBase = own(spec.target);
        inst.addTransformer(in.recorder, true);
        final Class<?> target = spec.target;
        AgentBuilder.RawMatcher only = new AgentBuilder.RawMatcher() {
            @Override
            public boolean matches(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                   Class<?> classBeingRedefined, ProtectionDomain protectionDomain) {
                return classBeingRedefined == target;
            }
        };
        final AsmVisitorWrapper visitor = Advice.withCustomMapping()
                .bind(HookId.class, spec.id)
                .to(spec.raw ? RawHookAdvice.class : HookAdvice.class)
                .on(named(spec.method).and(hasDescriptor(spec.descriptor)));
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(spec.discoverAll ? AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.SinglePass.INSTANCE
                        : new AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(target))
                .with(new AgentBuilder.RedefinitionStrategy.Listener.Adapter() {
                    @Override
                    public Iterable<? extends List<Class<?>>> onError(int index, List<Class<?>> batch,
                                                                      Throwable throwable, List<Class<?>> types) {
                        in.errors.add("retransform: " + throwable);
                        return Collections.emptyList();
                    }
                })
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                 JavaModule module, boolean loaded, DynamicType dynamicType) {
                        in.transformed = true;
                        in.advised = dynamicType.getBytes();
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                                        Throwable throwable) {
                        in.errors.add("transform " + typeName + ": " + throwable);
                    }
                })
                .ignore(new AgentBuilder.RawMatcher.Inversion(only))
                .type(only)
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> b, TypeDescription typeDescription,
                                                            ClassLoader classLoader, JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return b.visit(visitor);
                    }
                });
        long t1 = System.nanoTime();
        in.buildNanos = t1 - t0;
        if (ticker != null) {
            ticker.arm();
        }
        try {
            in.transformer = builder.installOn(inst);
        } catch (Throwable t) {
            in.errors.add("installOn: " + t);
        }
        countOwn(spec.target);
        if (ticker != null) {
            in.pauseNanos = ticker.disarm();
        }
        long t2 = System.nanoTime();
        in.installOnNanos = t2 - t1;
        in.totalNanos = t2 - t0;
        if (in.transformed && in.errors.isEmpty()) {
            in.status = "INSTALLED";
            installed.put(spec.id, in);
        } else {
            in.status = "FAILED";
            if (in.transformer != null) {
                inst.removeTransformer(in.transformer);
            }
            inst.removeTransformer(in.recorder);
            registry.forget(spec.id);
        }
        return in;
    }

    synchronized Installed installed(String id) {
        return installed.get(id);
    }

    synchronized List<String> touched() {
        List<String> out = new ArrayList<String>();
        for (Installed in : installed.values()) {
            out.add(in.spec.id + " -> " + in.spec.target.getName() + "@" + loaderName(in.spec.target.getClassLoader()));
        }
        return out;
    }

    /**
     * Removes one hook: handler off (unless {@code keepHandler}), transformer removed, the recorder moved to the end of
     * the chain, one retransformation of the touched class, and the byte check against the first transform's input.
     */
    synchronized Removal remove(String id, boolean keepHandler) {
        Removal r = new Removal();
        Installed in = installed.remove(id);
        if (in == null) {
            r.status = "NOT_INSTALLED";
            return r;
        }
        if (!keepHandler) {
            registry.mode(id, HookMode.OFF);
        }
        int before = in.recorder.count();
        r.foreignEvents = before - (own(in.spec.target) - in.ownBase);
        long t0 = System.nanoTime();
        if (ticker != null) {
            ticker.arm();
        }
        inst.removeTransformer(in.transformer);
        inst.removeTransformer(in.recorder);
        inst.addTransformer(in.recorder, true);
        try {
            inst.retransformClasses(in.spec.target);
            countOwn(in.spec.target);
        } catch (Throwable t) {
            r.status = "RESTORE_FAILED " + t;
        } finally {
            inst.removeTransformer(in.recorder);
        }
        if (ticker != null) {
            r.pauseNanos = ticker.disarm();
        }
        r.removeNanos = System.nanoTime() - t0;
        r.recorderCalls = in.recorder.count();
        r.first = in.recorder.first();
        r.after = in.recorder.count() > before ? in.recorder.last() : null;
        if (r.first != null && r.after != null) {
            r.bytesIdentical = Arrays.equals(r.first, r.after);
            r.canonicalIdentical = Canon.hash(r.first).equals(Canon.hash(r.after));
            r.diff = ClassFacts.diff("first", r.first, "after", r.after);
        } else {
            r.status = "NO_BYTES first=" + (r.first != null) + " after=" + (r.after != null);
        }
        if (!keepHandler) {
            registry.forget(id);
        }
        return r;
    }

    /**
     * One more retransformation with a fresh recorder at the end of the chain (the brief's "retransform once more"):
     * the bytes the class has now, as the JVM reconstitutes them.
     */
    byte[] currentBytes(Class<?> c) {
        ByteRecorder rec = new ByteRecorder(c);
        inst.addTransformer(rec, true);
        try {
            inst.retransformClasses(c);
            synchronized (this) {
                countOwn(c);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            inst.removeTransformer(rec);
        }
        return rec.last();
    }

    /** Removes every hook still installed (the detach path). */
    synchronized List<String> removeAll() {
        List<String> out = new ArrayList<String>();
        for (String id : new ArrayList<String>(installed.keySet())) {
            Removal r = remove(id, false);
            out.add(id + ": " + r.status + " identical=" + r.bytesIdentical);
        }
        return out;
    }

    static String loaderName(ClassLoader l) {
        if (l == null) {
            return "bootstrap";
        }
        if (l == ClassLoader.getSystemClassLoader()) {
            return "system";
        }
        return l.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(l));
    }
}
