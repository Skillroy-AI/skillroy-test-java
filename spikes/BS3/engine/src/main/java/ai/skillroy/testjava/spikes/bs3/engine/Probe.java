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

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.ToLongBiFunction;
import java.util.jar.JarFile;

import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * The BS3 probe script, run once per target JVM on the agent's {@code bs3-probe} thread. Its parts: the Spy append and
 * the early-reference check, the base install and fire on both targets, reconfiguration without retransformation, the
 * strict-delegation loader (item 1), removal with the byte check, the byte-check scenarios (item 3, {@link ByteChecks}),
 * the call in flight (item 5, {@link InFlight}), latency and the size sweep (item 4, {@link Latency}), what the shared
 * loaders hold (item 6) and a second install from a fresh engine loader ({@link SecondInstall}), and which copies of the
 * libraries each side uses (item 2). Every part records into {@code result.properties} and {@code probe.log}; nothing
 * throws into the host.
 */
public final class Probe {

    static final String CHECK_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/math/BigDecimal;)Lai/skillroy/testjava/samples/lending/AccessDecision;";
    static final String DEPOSIT_DESC = "(Ljava/lang/String;I)J";

    private Probe() {
    }

    /** Entry point, called reflectively by the agent; never throws. */
    public static void run(Instrumentation inst, Map<String, String> opts) {
        Report r;
        try {
            r = new Report(new File(opts.get("out")));
        } catch (Throwable t) {
            System.err.println("[bs3] cannot open the report: " + t);
            System.setProperty("bs3.probe.result", "ERROR");
            System.setProperty("bs3.probe.done", "true");
            return;
        }
        String verdict = "ERROR";
        Ctx c = null;
        try {
            c = runAll(inst, opts, r);
            verdict = c.failed().isEmpty() ? "PASS" : "FAIL " + c.failed();
            r.put("probe.checks", c.checks());
            r.put("probe.failed", c.failed());
        } catch (Throwable t) {
            r.error("probe.error", t);
        } finally {
            r.put("probe.verdict", verdict);
            r.flush();
            System.setProperty("bs3.probe.result", verdict.startsWith("PASS") ? "PASS" : verdict);
            System.setProperty("bs3.probe.done", "true");
        }
    }

    private static Ctx runAll(Instrumentation inst, Map<String, String> opts, Report r) throws Exception {
        r.section("environment");
        r.put("jvm.version", System.getProperty("java.version"));
        r.put("jvm.vm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        r.put("jvm.vendor", System.getProperty("java.vendor"));
        r.put("os.arch", System.getProperty("os.arch"));
        r.put("load.mode", opts.get("mode"));
        r.put("load.entry", opts.get("entry"));
        r.put("load.agentToProbeMs", opts.get("agentToProbeMs"));
        r.put("engine.jar", new File(opts.get("engineJar")).getName());
        r.put("spy.jar", new File(opts.get("spyJar")).getName());
        r.put("engine.byteBuddyVersion", manifest(Probe.class, "Bs3-Byte-Buddy-Version"));
        r.put("engine.loader", Probe.class.getClassLoader());
        r.put("inst.retransformSupported", inst.isRetransformClassesSupported());
        r.put("inst.redefineSupported", inst.isRedefineClassesSupported());
        r.put("inst.nativePrefixSupported", inst.isNativeMethodPrefixSupported());
        long waitStart = System.nanoTime();
        long deadline = waitStart + 60000L * 1000000L;
        while (!"true".equals(System.getProperty("bs3.host.ready")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        r.put("host.readyWaitMs", Report.ms(System.nanoTime() - waitStart));
        if (!"true".equals(System.getProperty("bs3.host.ready"))) {
            throw new IllegalStateException("host not ready within 60 s");
        }
        HostView host = new HostView();

        // Item 6 (part): the shared loaders before anything of ours reaches them.
        Injection s0 = Injection.snapshot(inst);
        r.put("memory.atStart", memory());

        // Item 1/6 (part): Spy on the bootstrap path; a class linked against Spy before the append.
        r.section("spy");
        boolean present = SpyBoot.bootSpy() != null;
        r.put("spy.presentAtStart", present);
        String early1 = present ? "skipped (already present)" : earlyTouch();
        r.put("spy.earlyReferenceBeforeAppend", early1);
        SpyBoot.Result sr = SpyBoot.ensure(inst, new File(opts.get("spyJar")), false);
        r.put("spy.name", sr.spyName);
        r.put("spy.appended", sr.appended);
        r.put("spy.appendMs", Report.ms(sr.appendNanos));
        r.put("spy.loadMs", Report.ms(sr.loadNanos));
        r.put("spy.error", sr.appendError);
        r.put("spy.bootstrapLoaded", sr.bootstrapLoaded);
        Ctx probe = null;
        String early2 = present ? "skipped" : earlyTouch();
        r.put("spy.earlyReferenceAfterAppend", early2);
        r.put("spy.freshReferenceAfterAppend", SpyAccess.version());
        r.put("spy.class", SpyAccess.spyClass().getName() + " loader=" + SpyAccess.spyClass().getClassLoader());
        Injection s1 = Injection.snapshot(inst);
        for (String line : s1.added(s0)) {
            r.line("  after the append: " + line);
        }

        r.put("engine.nexus", HookEngine.disableNexus(!"default".equals(opts.get("nexus"))));
        EventRing ring = new EventRing(8192);
        HookRegistry reg = new HookRegistry("handler-1", ring);
        SpyAccess.handler(reg);
        Ticker ticker = new Ticker();
        HookEngine engine = new HookEngine(inst, reg, ticker);
        Ctx c = new Ctx(inst, opts, r, host, ring, reg, engine, ticker);
        c.check("spy.onBootstrap", sr.bootstrapLoaded, sr.spyName);

        Base base = new Base(c);
        guarded(c, "base", new Step() {
            public void run() throws Throwable {
                base.installAndFire();
            }
        });
        guarded(c, "reconfigure", new Step() {
            public void run() throws Throwable {
                base.reconfigure();
            }
        });
        guarded(c, "item1", new Step() {
            public void run() throws Throwable {
                base.strictLoader();
            }
        });
        guarded(c, "item4.fire", new Step() {
            public void run() throws Throwable {
                base.fireCosts();
            }
        });
        guarded(c, "remove", new Step() {
            public void run() throws Throwable {
                base.removeAndVerify();
            }
        });
        guarded(c, "item3", new Step() {
            public void run() throws Throwable {
                new ByteChecks(c).run();
            }
        });
        guarded(c, "item5", new Step() {
            public void run() throws Throwable {
                new InFlight(c).run();
            }
        });
        if (c.flag("sweep", true)) {
            guarded(c, "item4.sweep", new Step() {
                public void run() throws Throwable {
                    new Latency(c).run();
                }
            });
        }

        // Detach: every hook removed, the handler cleared, our threads stopped.
        r.section("detach");
        List<String> left = engine.removeAll();
        r.put("detach.removedAtDetach", left);
        r.put("detach.touchedLeft", engine.touched());
        SpyAccess.handler(null);
        ticker.stop();
        System.gc();
        r.put("memory.afterDetach", memory());
        Injection s2 = Injection.snapshot(inst);
        r.section("item 6: what the shared loaders hold");
        List<String> added = s2.added(s0);
        r.put("item6.addedSinceStart", added);
        r.put("item6.suspiciousNames", s2.suspicious());
        List<String> bootAdded = s2.addedNames(s0, "boot");
        List<String> sysAdded = s2.addedNames(s0, "system");
        List<String> platAdded = s2.addedNames(s0, "platform");
        boolean onlySpy = present ? bootAdded.isEmpty() : !bootAdded.isEmpty();
        for (String n : bootAdded) {
            onlySpy &= n.startsWith(sr.spyName);
        }
        for (String n : sysAdded) {
            onlySpy &= !Injection.ours(n);
        }
        for (String n : platAdded) {
            onlySpy &= !Injection.ours(n);
        }
        r.put("item6.bootstrapAdded", bootAdded);
        r.put("item6.systemAdded", sysAdded);
        r.put("item6.platformAdded", platAdded);
        boolean nexusDefault = "default".equals(opts.get("nexus"));
        boolean unrelocated = "none".equals(opts.get("expectEngine"));
        if (nexusDefault || unrelocated) {
            // The controls: what the shared loaders get without the measures (an observation, expected to differ).
            r.put("item6.control", (nexusDefault ? "Nexus left at Byte Buddy's default" : "engine not relocated")
                    + "; nothing but Spy: " + onlySpy);
            c.check("item6.controlShowsWhatTheMeasurePrevents", !onlySpy, "system " + sysAdded.size()
                    + " classes of ours, bootstrap " + bootAdded);
        } else {
            c.check("item6.nothingButSpy", onlySpy, "bootstrap " + bootAdded + "; system and platform: none of ours");
        }

        guarded(c, "item6.second", new Step() {
            public void run() throws Throwable {
                secondInstall(c);
            }
        });
        Injection s3 = Injection.snapshot(inst);
        r.put("item6.addedBySecondInstall", s3.added(s2));
        System.gc();
        r.put("memory.afterSecondEngineDropped", memory());

        guarded(c, "item2", new Step() {
            public void run() throws Throwable {
                libraries(c);
            }
        });
        if (c.flag("sysAppend", false)) {
            guarded(c, "item1.systemAppend", new Step() {
                public void run() throws Throwable {
                    systemAppend(c);
                }
            });
        }
        return c;
    }

    interface Step {
        void run() throws Throwable;
    }

    static void guarded(Ctx c, String name, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            c.r.error("error." + name, t);
            c.check(name + ".completed", false, t.toString());
        }
    }

    private static String earlyTouch() {
        try {
            return "resolved " + EarlySpyRef.touch();
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    static String manifest(Class<?> c, String key) {
        try {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            try (JarFile jar = new JarFile(new File(cs.getLocation().toURI()))) {
                return jar.getManifest().getMainAttributes().getValue(key);
            }
        } catch (Exception e) {
            return "unknown (" + e + ")";
        }
    }

    /** Item 6: a second engine from a fresh loader over the same jar, after the first one detached. */
    private static void secondInstall(Ctx c) throws Exception {
        Report r = c.r;
        r.section("item 6: second install from a fresh engine loader");
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        Class<?> loaderClass = Class.forName("ai.skillroy.testjava.spikes.bs3.agent.EngineLoader", true, sys);
        URL engineUrl = new File(c.opts.get("engineJar")).toURI().toURL();
        URLClassLoader l2 = (URLClassLoader) loaderClass.getConstructor(URL[].class, ClassLoader.class)
                .newInstance(new URL[] {engineUrl}, sys);
        Class<?> si = Class.forName(SecondInstall.class.getName(), true, l2);
        java.util.Map<String, String> args = new java.util.HashMap<String, String>(c.opts);
        args.put("spyIdentity", String.valueOf(System.identityHashCode(SpyAccess.spyClass())));
        args.put("firstAgentBuilder", String.valueOf(System.identityHashCode(AgentBuilder.class)));
        @SuppressWarnings("unchecked")
        Map<String, String> res = (Map<String, String>) si.getMethod("run", Instrumentation.class, Map.class)
                .invoke(null, c.inst, args);
        for (Map.Entry<String, String> e : res.entrySet()) {
            r.put("item6.second." + e.getKey(), e.getValue());
        }
        c.check("item6.second.events", "true".equals(res.get("eventsArrived")), res.get("events"));
        c.check("item6.second.restored", "true".equals(res.get("bytesIdentical")), res.get("diff"));
        c.check("item6.second.sameSpy", "true".equals(res.get("sameSpyClass")), res.get("spyVersion"));
        l2.close();
        WeakReference<ClassLoader> ref = new WeakReference<ClassLoader>(l2);
        l2 = null;
        si = null;
        res = null;
        boolean collected = false;
        for (int i = 0; i < 20 && !collected; i++) {
            System.gc();
            Thread.sleep(50);
            collected = ref.get() == null;
        }
        r.put("item6.second.loaderCollected", collected);
    }

    /**
     * Item 1, the alternative placement: what appending a jar to the system class path (where the agent jar itself
     * already is) prints, for comparison with the bootstrap append. Run last: it changes the host's system class path.
     */
    private static void systemAppend(Ctx c) throws Exception {
        c.r.section("item 1: a system class path append, for comparison");
        java.io.File toy = c.host.toyJar();
        System.err.println("[bs3] stderr marker: appending " + toy.getName() + " to the system class path");
        long t0 = System.nanoTime();
        c.inst.appendToSystemClassLoaderSearch(new JarFile(toy));
        long t1 = System.nanoTime();
        Class<?> k = Class.forName(HostView.DEPOSIT_RULE, false, ClassLoader.getSystemClassLoader());
        System.err.println("[bs3] stderr marker: appended to the system class path");
        c.r.put("item1.systemAppendMs", Report.ms(t1 - t0));
        c.r.put("item1.systemAppendLoads", k.getName() + " from " + where(k));
    }

    /** Item 2: which copy of Byte Buddy, ASM and JNA each side uses. */
    private static void libraries(Ctx c) {
        Report r = c.r;
        r.section("item 2: library copies");
        ClassLoader engineLoader = Probe.class.getClassLoader();
        ClassLoader bbLoader = AgentBuilder.class.getClassLoader();
        r.put("item2.engine.agentBuilder", AgentBuilder.class.getName() + " from " + where(AgentBuilder.class));
        boolean relocated = !AgentBuilder.class.getName().startsWith(Ctx.join("net", "bytebuddy"));
        r.put("item2.engine.relocated", relocated);
        r.put("item2.engine.byteBuddyFromEngineLoader", bbLoader == engineLoader);
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        r.put("item2.host.byteBuddy", describe(sys, Ctx.join("net", "bytebuddy", "ByteBuddy")));
        r.put("item2.host.asm", describe(sys, Ctx.join("org", "objectweb", "asm", "ClassReader")));
        r.put("item2.host.jna", describe(sys, Ctx.join("com", "sun", "jna", "Native")));
        int hostNamed = 0;
        int engineNamed = 0;
        String plain = Ctx.join("net", "bytebuddy") + ".";
        for (Class<?> k : c.inst.getAllLoadedClasses()) {
            ClassLoader l = k.getClassLoader();
            if (k.getName().startsWith(plain)) {
                if (l == engineLoader) {
                    engineNamed++;
                } else {
                    hostNamed++;
                }
            }
        }
        r.put("item2.loaded.netBytebuddyInEngineLoader", engineNamed);
        r.put("item2.loaded.netBytebuddyElsewhere", hostNamed);
        if ("relocated".equals(c.opts.get("expectEngine"))) {
            c.check("item2.ownCopy", relocated && bbLoader == engineLoader && engineNamed == 0,
                    AgentBuilder.class.getName());
        } else if (!describe(sys, Ctx.join("net", "bytebuddy", "ByteBuddy")).endsWith("absent")) {
            // The control: an engine whose Byte Buddy is not relocated, in a host that ships its own copy.
            c.check("item2.control.engineSilentlyRunsOnHostCopy", bbLoader == sys, where(AgentBuilder.class));
        }
    }

    /** Metaspace used and loaded-class count, for the agent's footprint in the host. */
    static String memory() {
        long meta = -1;
        for (java.lang.management.MemoryPoolMXBean p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(p.getName())) {
                meta = p.getUsage().getUsed();
            }
        }
        return "metaspace " + (meta / 1024) + " KB, loaded classes "
                + java.lang.management.ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
    }

    static String where(Class<?> k) {
        CodeSource cs = k.getProtectionDomain() == null ? null : k.getProtectionDomain().getCodeSource();
        return HookEngine.loaderName(k.getClassLoader()) + " " + (cs == null ? "-" : cs.getLocation());
    }

    private static String describe(ClassLoader l, String name) {
        try {
            Class<?> k = Class.forName(name, false, l);
            String v = k.getPackage() == null ? null : k.getPackage().getImplementationVersion();
            return name + " from " + where(k) + (v == null ? "" : " version " + v);
        } catch (Throwable t) {
            return name + " absent";
        }
    }

    /** Runs a task on the host's desk thread and renders what came back, an exception included. */
    static String onDesk(Ctx c, final Callable<?> task) {
        try {
            Object o = c.host.onDesk(new Callable<Object>() {
                @Override
                public Object call() {
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return "threw " + t;
                    }
                }
            });
            return String.valueOf(o);
        } catch (Throwable t) {
            return "desk failed " + t;
        }
    }

    /** {@code rule.depositFor(code, days)} through the toy's {@code ToLongBiFunction} face, on the desk thread. */
    static String deposit(Ctx c, final Object rule, final String code, final int days) {
        return onDesk(c, new Callable<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object call() {
                return ((ToLongBiFunction<String, Integer>) rule).applyAsLong(code, days);
            }
        });
    }

    static String ids(List<?> l) {
        return Arrays.toString(l.toArray());
    }
}
