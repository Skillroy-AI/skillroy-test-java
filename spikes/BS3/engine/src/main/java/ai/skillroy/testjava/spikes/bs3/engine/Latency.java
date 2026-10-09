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

import java.util.Arrays;

/**
 * BS3 item 4: retransformation time against class size, and the cost an installed hook adds to every class the host
 * loads. For each generated class: the JVM alone (a retransformation with only a counting transformer registered,
 * median of three), the hook's install (Byte Buddy plus the retransformation) and its remove (with the byte check),
 * each with the longest pause a spinning host thread saw.
 */
final class Latency {

    private final Ctx c;

    Latency(Ctx c) {
        this.c = c;
    }

    void run() throws Throwable {
        Report r = c.r;
        r.section("item 4: retransformation against class size");
        r.line("  kind | methods | class bytes | cp entries | jvm retransform ms (median of 3) | jvm pause ms"
                + " | install ms | install pause ms | remove ms | remove pause ms | fired | restored");
        int[] counts = {1, 10, 100, 1000, 3000, 10000, 20000};
        for (int n : counts) {
            one("methods", n, ClassGen.manyMethods(name("Ledger", n), n));
        }
        int[] bodies = {1, 4, 16, 30};
        for (int m : bodies) {
            one("code", m, ClassGen.longBodies(name("Tally", m), m, 20000));
        }
        loadOverhead();
        pauseVsLoadedClasses();
    }

    /**
     * The pause of one retransformation of a small class against the number of classes loaded in the JVM (HotSpot
     * walks every loaded class during a redefinition), and Byte Buddy's install with explicit discovery against its
     * default discovery (which matches every loaded class).
     */
    private void pauseVsLoadedClasses() throws Throwable {
        Report r = c.r;
        r.section("item 4: retransformation pause against the number of loaded classes");
        r.line("  loaded classes | jvm retransform ms (median of 5) | pause ms (median) | pause ms (max)"
                + " | install explicit ms | install discover-all ms");
        byte[] bytes = ClassGen.manyMethods("ai.skillroy.testjava.spikes.bs3.gen.Counter", 10);
        Class<?> k = new ClassGen.Loader(ClassLoader.getSystemClassLoader())
                .define("ai.skillroy.testjava.spikes.bs3.gen.Counter", bytes);
        java.util.List<Class<?>> keep = new java.util.ArrayList<Class<?>>();
        ClassGen.Loader extra = new ClassGen.Loader(ClassLoader.getSystemClassLoader());
        int[] targets = {0, 10000, 30000, 60000};
        for (int target : targets) {
            while (keep.size() < target) {
                String n = "ai.skillroy.testjava.spikes.bs3.gen.many.Slip" + keep.size();
                keep.add(extra.define(n, ClassGen.manyMethods(n, 1)));
            }
            int loaded = c.inst.getAllLoadedClasses().length;
            CountingTransformer ct = new CountingTransformer(k);
            c.inst.addTransformer(ct, true);
            long[] t = new long[5];
            long[] p = new long[5];
            for (int i = 0; i < 5; i++) {
                c.ticker.arm();
                long t0 = System.nanoTime();
                c.inst.retransformClasses(k);
                t[i] = System.nanoTime() - t0;
                p[i] = c.ticker.disarm();
            }
            c.inst.removeTransformer(ct);
            Arrays.sort(t);
            Arrays.sort(p);
            HookEngine.Spec explicit = new HookEngine.Spec("pause.explicit." + target, k, "settle", "(J)J",
                    HookMode.OBSERVE);
            HookEngine.Installed ie = c.engine.install(explicit);
            c.engine.remove(explicit.id, false);
            HookEngine.Spec all = new HookEngine.Spec("pause.all." + target, k, "settle", "(J)J", HookMode.OBSERVE);
            all.discoverAll = true;
            HookEngine.Installed ia = c.engine.install(all);
            c.engine.remove(all.id, false);
            r.put("item4.pause.loaded." + target, loaded + " | " + Report.ms(t[2]) + " | " + Report.ms(p[2]) + " | "
                    + Report.ms(p[4]) + " | " + Report.ms(ie.totalNanos) + " | " + Report.ms(ia.totalNanos)
                    + (ie.ok() && ia.ok() ? "" : " | " + ie.status + "/" + ia.status));
        }
        keep.clear();
    }

    private static String name(String base, int n) {
        return "ai.skillroy.testjava.spikes.bs3.gen." + base + n;
    }

    private void one(String kind, int n, byte[] bytes) throws Throwable {
        ClassGen.Loader loader = new ClassGen.Loader(ClassLoader.getSystemClassLoader());
        String className = ClassFacts.parse(bytes).thisClass.substring("Class ".length()).replace('/', '.');
        Class<?> k = loader.define(className, bytes);
        Object o = k.newInstance();
        k.getMethod("settle", long.class).invoke(o, 1L);
        int cp = ClassFacts.parse(bytes).cp.size();

        CountingTransformer ct = new CountingTransformer(k);
        c.inst.addTransformer(ct, true);
        long[] jvm = new long[3];
        long[] jvmPause = new long[3];
        for (int i = 0; i < 3; i++) {
            c.ticker.arm();
            long t0 = System.nanoTime();
            c.inst.retransformClasses(k);
            jvm[i] = System.nanoTime() - t0;
            jvmPause[i] = c.ticker.disarm();
        }
        c.inst.removeTransformer(ct);
        Arrays.sort(jvm);
        Arrays.sort(jvmPause);

        String id = "gen." + kind + "." + n;
        HookEngine.Installed in = c.engine.install(new HookEngine.Spec(id, k, "settle", "(J)J", HookMode.OBSERVE));
        long seq = c.ring.last();
        Object fired = k.getMethod("settle", long.class).invoke(o, 41L);
        boolean events = c.countEvents(seq, id, null) == 2;
        HookEngine.Removal rm = c.engine.remove(id, false);
        String key = "item4.size." + kind + "." + n;
        c.r.put(key, kind + " | " + n + " | " + bytes.length + " | " + cp + " | " + Report.ms(jvm[1]) + " | "
                + Report.ms(jvmPause[1]) + " | " + Report.ms(in.totalNanos) + " | " + Report.ms(in.pauseNanos) + " | "
                + Report.ms(rm.removeNanos) + " | " + Report.ms(rm.pauseNanos) + " | "
                + (in.ok() && events && Long.valueOf(42L).equals(fired)) + " | " + rm.bytesIdentical);
        if (!in.ok()) {
            c.r.put(key + ".errors", in.status + " " + in.errors);
        }
        c.check(key + ".hookedAndRestored", in.ok() && events && rm.bytesIdentical, in.status);
    }

    /** The cost of an installed hook on every class the host defines: 2000 small classes with and without one. */
    private void loadOverhead() throws Throwable {
        Report r = c.r;
        r.section("item 4: class-loading cost while a hook is installed");
        Object rule = c.host.newToyLoader("load-overhead").loadClass(HostView.DEPOSIT_RULE).newInstance();
        Class<?> k = rule.getClass();
        int n = 2000;
        long[] without = new long[3];
        long[] with = new long[3];
        for (int round = 0; round < 3; round++) {
            without[round] = defineMany("w" + round, n);
            HookEngine.Installed in = c.engine.install(new HookEngine.Spec("load.overhead." + round, k, "rateName",
                    "()Ljava/lang/String;", HookMode.OBSERVE));
            with[round] = defineMany("h" + round, n);
            c.engine.remove("load.overhead." + round, false);
            if (!in.ok()) {
                r.put("item4.load.errors", in.errors);
            }
        }
        Arrays.sort(without);
        Arrays.sort(with);
        r.put("item4.load.withoutHookUsPerClass", Report.us(without[1] / n));
        r.put("item4.load.withHookUsPerClass", Report.us(with[1] / n));
        r.put("item4.load.overheadUsPerClass", Report.us((with[1] - without[1]) / n));
    }

    private long defineMany(String tag, int n) {
        ClassGen.Loader loader = new ClassGen.Loader(ClassLoader.getSystemClassLoader());
        byte[][] all = new byte[n][];
        for (int i = 0; i < n; i++) {
            all[i] = ClassGen.manyMethods("ai.skillroy.testjava.spikes.bs3.gen.load." + tag + ".Slip" + i, 1);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            loader.define("ai.skillroy.testjava.spikes.bs3.gen.load." + tag + ".Slip" + i, all[i]);
        }
        return System.nanoTime() - t0;
    }
}
