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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.ToLongBiFunction;

/**
 * The base of BS3: hook a method of the sample lending desk ({@code AccessControl.check}, the limit check behind
 * scenario A) and a method of a toy class in a custom {@code URLClassLoader}; fire both from the host's own desk
 * thread; reconfigure without retransforming; the strict-delegation loader of item 1; the fire costs of item 4; and
 * removal with the byte check.
 */
final class Base {

    private final Ctx c;
    private Class<?> accessClass;
    private Object toy;
    private Class<?> toyClass;
    private String decisionAliceBefore;
    private String decisionCarolBefore;
    private String depositBefore;
    private long checkBaselineNs = -1;
    private HookEngine.Installed h1;
    private HookEngine.Installed h2;

    Base(Ctx c) {
        this.c = c;
    }

    private String check(String code, String permission, String amount) throws Exception {
        return HostView.decision(c.host.check(code, permission, amount));
    }

    void installAndFire() throws Exception {
        Report r = c.r;
        r.section("base: install and fire");
        accessClass = c.host.accessControlClass();
        toy = c.host.field("TOY_RULE");
        toyClass = toy.getClass();
        r.put("base.target1", accessClass.getName() + " in " + HookEngine.loaderName(accessClass.getClassLoader()));
        r.put("base.target2", toyClass.getName() + " in " + HookEngine.loaderName(toyClass.getClassLoader())
                + " (parent " + HookEngine.loaderName(toyClass.getClassLoader().getParent()) + ")");
        decisionAliceBefore = check("100", "RETURN", "45.00");
        decisionCarolBefore = check("200", "RETURN_OVERRIDE", "45.00");
        depositBefore = Probe.deposit(c, toy, "HND-001", 3);
        r.put("base.before.alice", decisionAliceBefore);
        r.put("base.before.carol", decisionCarolBefore);
        r.put("base.before.deposit", depositBefore);
        // The unhooked cost of the sample's check, for item 4 (warmed first).
        loopCheck(200000);
        checkBaselineNs = best(new Loop() {
            public long run(int n) throws Exception {
                return loopCheck(n);
            }
        }, 200000);
        r.put("item4.fire.check.unhookedNsPerCall", checkBaselineNs / 200000.0);

        byte[] original1 = HostView.classFile(accessClass);
        h1 = c.engine.install(new HookEngine.Spec("limit.check", accessClass, "check", Probe.CHECK_DESC,
                HookMode.OBSERVE));
        report("limit.check", h1);
        h2 = c.engine.install(new HookEngine.Spec("toy.deposit", toyClass, "depositFor", Probe.DEPOSIT_DESC,
                HookMode.OBSERVE));
        report("toy.deposit", h2);
        r.put("memory.afterFirstInstalls", Probe.memory());
        c.check("base.install.limitCheck", h1.ok(), h1.status + " " + h1.errors);
        c.check("base.install.toyDeposit", h2.ok(), h2.status + " " + h2.errors);
        if (h1.advised != null) {
            r.dump("AccessControl.original", original1);
            r.dump("AccessControl.first", h1.recorder.first());
            r.dump("AccessControl.advised", h1.advised);
            r.put("base.advised.limitCheck", ClassFacts.summary(h1.advised));
        }
        r.put("item4.install.cold.limitCheck.totalMs", Report.ms(h1.totalNanos));
        r.put("item4.install.warm.toyDeposit.totalMs", Report.ms(h2.totalNanos));
        c.check("item4.install.underOneSecond", h1.totalNanos < 1000000000L && h2.totalNanos < 1000000000L,
                Report.ms(h1.totalNanos) + " ms cold, " + Report.ms(h2.totalNanos) + " ms warm");

        long seq = c.ring.last();
        String alice = check("100", "RETURN", "45.00");
        String carol = check("200", "RETURN_OVERRIDE", "45.00");
        String deposit = Probe.deposit(c, toy, "HND-001", 3);
        String negative = Probe.deposit(c, toy, "HND-001", -1);
        r.put("base.fired.alice", alice);
        r.put("base.fired.carol", carol);
        r.put("base.fired.deposit", deposit);
        r.put("base.fired.negativeDays", negative);
        c.events("base", seq);
        int checkEnters = c.countEvents(seq, "limit.check", "enter");
        int checkExits = c.countEvents(seq, "limit.check", "exit");
        int toyEnters = c.countEvents(seq, "toy.deposit", "enter");
        int toyExits = c.countEvents(seq, "toy.deposit", "exit");
        boolean deskThread = true;
        boolean threwSeen = false;
        for (EventRing.Event e : c.ring.since(seq)) {
            deskThread &= "lending-desk".equals(e.thread);
            threwSeen |= e.hookId.equals("toy.deposit") && e.detail.contains("threw java.lang.IllegalArgumentException");
        }
        c.check("base.events.bothHooks", checkEnters == 2 && checkExits == 2 && toyEnters == 2 && toyExits == 2,
                "limit.check " + checkEnters + "/" + checkExits + ", toy.deposit " + toyEnters + "/" + toyExits);
        c.check("base.events.onHostThread", deskThread, "lending-desk");
        c.check("base.events.throwableObserved", threwSeen, negative);
        c.check("base.hostBehaviourUnchanged", alice.equals(decisionAliceBefore) && carol.equals(decisionCarolBefore)
                && deposit.equals(depositBefore) && negative.contains("IllegalArgumentException"), alice);
    }

    private void report(String id, HookEngine.Installed in) {
        Report r = c.r;
        r.put("install." + id + ".status", in.status);
        r.put("install." + id + ".visibility", in.visibility);
        r.put("install." + id + ".buildMs", Report.ms(in.buildNanos));
        r.put("install." + id + ".installOnMs", Report.ms(in.installOnNanos));
        r.put("install." + id + ".totalMs", Report.ms(in.totalNanos));
        r.put("install." + id + ".hostPauseMs", Report.ms(in.pauseNanos));
        if (!in.errors.isEmpty()) {
            r.put("install." + id + ".errors", in.errors);
        }
    }

    /** Mode changes and a handler swap; a counting transformer proves that none of it retransforms. */
    void reconfigure() throws Exception {
        Report r = c.r;
        r.section("reconfigure without retransformation");
        CountingTransformer ct1 = new CountingTransformer(accessClass);
        CountingTransformer ct2 = new CountingTransformer(toyClass);
        c.inst.addTransformer(ct1, true);
        c.inst.addTransformer(ct2, true);
        try {
            // CAPTURE
            long t0 = System.nanoTime();
            c.registry.mode("limit.check", HookMode.CAPTURE);
            long modeNs = System.nanoTime() - t0;
            String d = check("100", "RETURN", "45.00");
            HookRegistry.Hook hk = c.registry.hook("limit.check");
            String capturedArgs = hk.capturedArgs == null ? "none" : Render.args(hk.capturedArgs);
            String capturedResult = HostView.decision(hk.capturedResult);
            r.put("reconfigure.capture.args", capturedArgs);
            r.put("reconfigure.capture.result", capturedResult);
            r.put("reconfigure.modeChangeNs", modeNs);
            c.check("reconfigure.capture", capturedArgs.contains("\"100\"") && capturedArgs.contains("45.00")
                    && capturedResult.equals(d), capturedArgs + " -> " + capturedResult);

            // STUB on the sample: the original body skipped, the handler's decision returned.
            Object stub = c.host.newDecision(true, null, "stubbed by BS3 (hook limit.check)");
            long[] spyBefore = SpyAccess.counters();
            c.registry.stub("limit.check", stub);
            Object returned = c.host.check("100", "RETURN", "45.00");
            long[] spyAfter = SpyAccess.counters();
            r.put("reconfigure.stub.returned", HostView.decision(returned));
            c.check("reconfigure.stub.sample", returned == stub && spyAfter[3] - spyBefore[3] == 1,
                    "same object: " + (returned == stub) + ", Spy skips +" + (spyAfter[3] - spyBefore[3]));

            // STUB on the toy: a primitive return (DYNAMIC typing unboxes), a skipped throwing body, a mistyped value.
            c.registry.stub("toy.deposit", Long.valueOf(7L));
            String stubbed = Probe.deposit(c, toy, "HND-001", 3);
            String stubbedNegative = Probe.deposit(c, toy, "HND-001", -1);
            c.registry.stub("toy.deposit", "not a number");
            String mistyped = Probe.deposit(c, toy, "HND-001", 3);
            r.put("reconfigure.stub.toy", stubbed);
            r.put("reconfigure.stub.toySkipsThrowingBody", stubbedNegative);
            r.put("reconfigure.stub.mistypedValueReturns", mistyped);
            c.check("reconfigure.stub.toy", "7".equals(stubbed) && "7".equals(stubbedNegative),
                    stubbed + ", " + stubbedNegative);
            c.check("reconfigure.stub.mistypedGivesDefault", "0".equals(mistyped), mistyped);

            // OBSERVE again, then a whole-handler swap: the next call lands in the second handler.
            c.registry.mode("limit.check", HookMode.OBSERVE);
            c.registry.mode("toy.deposit", HookMode.OBSERVE);
            HookRegistry second = new HookRegistry("handler-2", c.ring);
            second.define("limit.check", HookMode.OBSERVE);
            long seq = c.ring.last();
            long t1 = System.nanoTime();
            SpyAccess.handler(second);
            long swapNs = System.nanoTime() - t1;
            check("100", "RETURN", "45.00");
            boolean inSecond = false;
            for (EventRing.Event e : c.ring.since(seq)) {
                inSecond |= "handler-2".equals(e.handler);
            }
            SpyAccess.handler(c.registry);
            r.put("reconfigure.handlerSwapNs", swapNs);
            c.check("reconfigure.handlerSwap", inSecond, "next call seen by handler-2");

            // No handler at all: Spy is a no-op and the host gets the original result.
            SpyAccess.handler(null);
            long[] u0 = SpyAccess.counters();
            String plain = check("100", "RETURN", "45.00");
            long[] u1 = SpyAccess.counters();
            SpyAccess.handler(c.registry);
            c.check("reconfigure.noHandler", plain.equals(decisionAliceBefore) && u1[1] - u0[1] == 2,
                    "unhandled +" + (u1[1] - u0[1]));

            // OFF: installed, silent.
            c.registry.mode("limit.check", HookMode.OFF);
            long seqOff = c.ring.last();
            check("100", "RETURN", "45.00");
            c.check("reconfigure.off", c.countEvents(seqOff, "limit.check", null) == 0, "no events");
            c.registry.mode("limit.check", HookMode.OBSERVE);
        } finally {
            c.inst.removeTransformer(ct1);
            c.inst.removeTransformer(ct2);
        }
        r.put("reconfigure.retransformsSeen", ct1.count.get() + ct2.count.get());
        c.check("reconfigure.noRetransform", ct1.count.get() == 0 && ct2.count.get() == 0,
                "the counting transformers saw " + (ct1.count.get() + ct2.count.get()) + " retransformations");
    }

    /** Item 1: a loader that delegates only java.* to its parent. */
    void strictLoader() throws Throwable {
        Report r = c.r;
        r.section("item 1: strict-delegation loader");
        ClassLoader strict = c.host.newStrictLoader("strict-a");
        Class<?> k = strict.loadClass(HostView.DEPOSIT_RULE);
        Object rule = k.newInstance();
        String visibility = SpyBoot.visibility(strict);
        r.put("item1.loader", strict + " parent " + HookEngine.loaderName(strict.getParent()));
        r.put("item1.visibility", visibility);
        String before = Probe.deposit(c, rule, "LAD-001", 2);
        HookEngine.Spec spec = new HookEngine.Spec("strict.deposit", k, "depositFor", Probe.DEPOSIT_DESC,
                HookMode.OBSERVE);
        HookEngine.Installed refused = null;
        if (!"VISIBLE".equals(visibility)) {
            refused = c.engine.install(spec);
            r.put("item1.unforcedInstall", refused.status);
            c.check("item1.installRefusedWhenInvisible", refused.status.startsWith("UNAVAILABLE"), refused.status);
            spec.force = true;
        }
        HookEngine.Installed in = c.engine.install(spec);
        r.put("item1.install", in.status + " " + in.errors + " " + Report.ms(in.totalNanos) + " ms");
        long seq = c.ring.last();
        long[] s0 = SpyAccess.counters();
        String fired = Probe.deposit(c, rule, "LAD-001", 2);
        long[] s1 = SpyAccess.counters();
        int events = c.countEvents(seq, "strict.deposit", null);
        r.put("item1.fired", fired + " (before " + before + ")");
        r.put("item1.events", events);
        r.put("item1.spyEntersDelta", s1[0] - s0[0]);
        if ("VISIBLE".equals(visibility)) {
            c.check("item1.eventsFromStrictLoader", events == 2 && fired.equals(before), events + " events");
        } else {
            c.check("item1.invisibleSpySilentWithSuppress", events == 0 && fired.equals(before)
                    && s1[0] == s0[0], "events " + events + ", host result " + fired);
            // The cost of an unresolvable Spy under suppress: every call throws and catches inside the method.
            @SuppressWarnings("unchecked")
            final ToLongBiFunction<String, Integer> f = (ToLongBiFunction<String, Integer>) rule;
            loopDeposit(f, 20000);
            long hidden = best(new Loop() {
                public long run(int n) throws Exception {
                    return loopDeposit(f, n);
                }
            }, 20000);
            r.put("item1.invisibleSpyNsPerCall", hidden / 20000.0);
        }
        if (SpyBoot.SPY.startsWith("java.")) {
            // The java.* placement's cost: only the bootstrap (and platform) loader may define such a class.
            String res = SpyBoot.SPY.replace('.', '/') + ".class";
            byte[] bytes;
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(c.spyJar())) {
                java.io.InputStream entry = jar.getInputStream(jar.getEntry(res));
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = entry.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
                bytes = out.toByteArray();
            }
            String defined;
            try {
                new ClassGen.Loader(ClassLoader.getSystemClassLoader()).define(SpyBoot.SPY, bytes);
                defined = "defined";
            } catch (Throwable t) {
                defined = t.toString();
            }
            r.put("item1.javaPackageInOrdinaryLoader", defined);
            c.check("item1.javaPackageOnlyOnBootstrap", defined.contains("SecurityException"), defined);
        }
        HookEngine.Removal rm = c.engine.remove("strict.deposit", false);
        r.put("item1.remove", rm.status + " identical=" + rm.bytesIdentical);
        c.check("item1.restored", rm.bytesIdentical, rm.diff);

        if (!"VISIBLE".equals(visibility)) {
            // Without suppress the host's own call fails: what the guard in the advice prevents.
            ClassLoader strictB = c.host.newStrictLoader("strict-b");
            Class<?> kb = strictB.loadClass(HostView.DEPOSIT_RULE);
            Object ruleB = kb.newInstance();
            HookEngine.Spec raw = new HookEngine.Spec("strict.raw", kb, "depositFor", Probe.DEPOSIT_DESC,
                    HookMode.OBSERVE);
            raw.force = true;
            raw.raw = true;
            HookEngine.Installed inRaw = c.engine.install(raw);
            String first = Probe.deposit(c, ruleB, "LAD-001", 2);
            String second = Probe.deposit(c, ruleB, "LAD-001", 2);
            r.put("item1.unguarded.install", inRaw.status);
            r.put("item1.unguarded.firstCall", first);
            r.put("item1.unguarded.secondCall", second);
            c.check("item1.unguardedFailsInHost", first.contains("NoClassDefFoundError"), first);
            HookEngine.Removal rmRaw = c.engine.remove("strict.raw", false);
            String after = Probe.deposit(c, ruleB, "LAD-001", 2);
            r.put("item1.unguarded.afterRemove", after + " identical=" + rmRaw.bytesIdentical);
            c.check("item1.unguardedRecoversAfterRemove", after.equals(before), after);
        }
    }

    /** Item 4: cost per call of the toy's trivial method and the sample's check, per handler state. */
    void fireCosts() throws Exception {
        Report r = c.r;
        r.section("item 4: fire cost per call");
        final int n = 1000000;
        ClassLoader plainLoader = c.host.newToyLoader("plain");
        @SuppressWarnings("unchecked")
        final ToLongBiFunction<String, Integer> plain = (ToLongBiFunction<String, Integer>)
                plainLoader.loadClass(HostView.DEPOSIT_RULE).newInstance();
        @SuppressWarnings("unchecked")
        final ToLongBiFunction<String, Integer> hooked = (ToLongBiFunction<String, Integer>) toy;
        Loop plainLoop = new Loop() {
            public long run(int k) throws Exception {
                return loopDeposit(plain, k);
            }
        };
        Loop hookedLoop = new Loop() {
            public long run(int k) throws Exception {
                return loopDeposit(hooked, k);
            }
        };
        Loop checkLoop = new Loop() {
            public long run(int k) throws Exception {
                return loopCheck(k);
            }
        };
        loopDeposit(plain, n);
        r.put("item4.fire.toy.unhookedNsPerCall", best(plainLoop, n) / (double) n);
        String[][] states = {{"OBSERVE", "observe"}, {"CAPTURE", "capture"}, {"STUB", "stub"}, {"OFF", "off"},
                             {"NULL", "noHandler"}};
        for (String[] s : states) {
            set("toy.deposit", s[0]);
            set("limit.check", s[0]);
            loopDeposit(hooked, n / 4);
            r.put("item4.fire.toy." + s[1] + "NsPerCall", best(hookedLoop, n) / (double) n);
            loopCheck(50000);
            r.put("item4.fire.check." + s[1] + "NsPerCall", best(checkLoop, 200000) / 200000.0);
        }
        SpyAccess.handler(c.registry);
        c.registry.mode("toy.deposit", HookMode.OBSERVE);
        c.registry.mode("limit.check", HookMode.OBSERVE);
    }

    private void set(String id, String state) throws Exception {
        if ("NULL".equals(state)) {
            SpyAccess.handler(null);
            return;
        }
        SpyAccess.handler(c.registry);
        HookMode m = HookMode.valueOf(state);
        if (m == HookMode.STUB) {
            c.registry.stub(id, "limit.check".equals(id) ? c.host.newDecision(true, null, "stub") : Long.valueOf(1L));
        } else {
            c.registry.mode(id, m);
        }
    }

    /** Removal of both base hooks with the byte check, one more retransformation, and the after-remove behaviour. */
    void removeAndVerify() throws Exception {
        Report r = c.r;
        r.section("remove and the byte check");
        for (String id : new String[] {"limit.check", "toy.deposit"}) {
            HookEngine.Installed in = c.engine.installed(id);
            if (in == null) {
                continue;
            }
            Class<?> k = in.spec.target;
            byte[] file = HostView.classFile(k);
            HookEngine.Removal rm = c.engine.remove(id, false);
            byte[] again = c.engine.currentBytes(k);
            r.put("remove." + id + ".status", rm.status);
            r.put("remove." + id + ".removeMs", Report.ms(rm.removeNanos));
            r.put("remove." + id + ".hostPauseMs", Report.ms(rm.pauseNanos));
            r.put("remove." + id + ".firstVsAfter", rm.diff);
            r.put("remove." + id + ".canonicalIdentical", rm.canonicalIdentical);
            r.put("remove." + id + ".foreignEvents", rm.foreignEvents);
            r.put("remove." + id + ".onceMore", again == null ? "no bytes"
                    : ClassFacts.diff("first", rm.first, "again", again));
            r.put("remove." + id + ".fileVsFirst", ClassFacts.diff("file", file, "first", rm.first));
            r.put("remove." + id + ".fileVsFirstCanonical", Canon.hash(file).equals(Canon.hash(rm.first)));
            c.check("remove." + id + ".byteIdentical", rm.bytesIdentical && again != null
                    && Arrays.equals(again, rm.first) && rm.foreignEvents == 0, rm.diff);
        }
        long[] s0 = SpyAccess.counters();
        long seq = c.ring.last();
        String alice = check("100", "RETURN", "45.00");
        String carol = check("200", "RETURN_OVERRIDE", "45.00");
        String deposit = Probe.deposit(c, toy, "HND-001", 3);
        long[] s1 = SpyAccess.counters();
        r.put("remove.after.alice", alice);
        r.put("remove.after.deposit", deposit);
        c.check("remove.noSpyCallsAfterRemove", s1[0] == s0[0] && c.ring.since(seq).isEmpty(),
                "Spy enters +" + (s1[0] - s0[0]));
        c.check("remove.behaviourAsBefore", alice.equals(decisionAliceBefore) && carol.equals(decisionCarolBefore)
                && deposit.equals(depositBefore), alice + " / " + deposit);
        r.put("item4.remove.limitCheckMs", r.get("remove.limit.check.removeMs"));
    }

    interface Loop {
        long run(int n) throws Exception;
    }

    /** @return the best of five timed runs, in nanoseconds */
    static long best(Loop loop, int n) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            best = Math.min(best, loop.run(n));
        }
        return best;
    }

    private long loopCheck(int n) throws Exception {
        return (Long) c.host.call("loopCheck", new Class<?>[] {int.class}, n);
    }

    private long loopDeposit(ToLongBiFunction<String, Integer> f, int n) throws Exception {
        return (Long) c.host.call("loopDeposit", new Class<?>[] {ToLongBiFunction.class, int.class}, f, n);
    }

    static Callable<Object> noop() {
        return new Callable<Object>() {
            @Override
            public Object call() {
                return null;
            }
        };
    }

    static BigDecimal amount(String s) {
        return new BigDecimal(s);
    }
}
