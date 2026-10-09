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

package ai.skillroy.testjava.spikes.bs14.probe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The BS14 probe script (phase6-contracts.md 7.2 items 1-6, plus the reuse of a parked Retransformer by a second
 * module instance, 7.4 step 2). Everything of Byteman, RuleManager and RuleHelper is reached by reflection after the
 * two appends to the system class path; nothing here names one of their types.
 */
public final class Probe {

    static final String TOY = "ai.skillroy.testjava.spikes.bs14.toy.LendingToy";
    static final String HELPER = "ai.skillroy.testjava.spikes.bs14.sys.RuleHelper";
    static final String MANAGER = "ai.skillroy.testjava.spikes.bs14.sys.RuleManager";
    static final String HANDLER = "ai.skillroy.testjava.spikes.bs14.probe.FiredHandler";

    private final Instrumentation inst;
    private final Map<String, String> opts;
    private final Report r;
    private final Host host;
    private final ClassLoader sys = ClassLoader.getSystemClassLoader();

    private Probe(Instrumentation inst, Map<String, String> opts, Report r) throws Exception {
        this.inst = inst;
        this.opts = opts;
        this.r = r;
        this.host = new Host();
    }

    public static void run(Instrumentation inst, Map<String, String> opts) throws Exception {
        Report r = new Report(new File(opts.get("out")));
        try {
            new Probe(inst, opts, r).script();
        } catch (Throwable t) {
            r.fail("probe.exception", t instanceof InvocationTargetException ? t.getCause() : t);
            r.check("probe.noException", false);
        } finally {
            r.put("probe.verdict", r.allPass() ? "PASS" : "FAIL " + r.failures());
            r.write();
        }
    }

    static void mark(String what) {
        System.out.println("BS14-MARK " + what);
        System.out.flush();
        System.err.println("BS14-MARK " + what);
        System.err.flush();
    }

    static String rule(String name, String method, String at, String action) {
        return "RULE " + name + "\n"
                + "CLASS " + TOY + "\n"
                + "METHOD " + method + "\n"
                + "HELPER " + HELPER + "\n"
                + "AT " + at + "\n"
                + "IF true\n"
                + "DO " + action + "\n"
                + "ENDRULE\n";
    }

    private void script() throws Exception {
        mark("begin");
        r.put("java.version", System.getProperty("java.version"));
        r.put("java.vm.name", System.getProperty("java.vm.name"));
        r.put("byteman.options", opts.get("bmopts"));
        boolean proc = ProcNet.available();
        r.put("proc.available", proc);
        Class<?> toy = loaded(TOY);
        r.put("toy.loader", toy == null ? "not loaded" : String.valueOf(toy.getClassLoader()));
        r.check("setup.toyLoadedBySystemLoader", toy != null && toy.getClassLoader() == sys);
        r.put("toy.firstCall", host.call("STAFF-1"));

        // ---- item 5, before: sockets and threads before anything of Byteman exists
        Set<String> socketsBefore = proc ? ProcNet.listening() : Collections.<String>emptySet();
        Set<String> threadsBefore = threads();
        r.put("item5.socketsBefore", socketsBefore);
        r.put("item5.threadsBefore", threadsBefore);
        r.put("item1.loadedPropertyBefore", System.getProperty("org.jboss.byteman.agent.loaded"));
        r.put("item1.bytemanClassesBefore", countLoaded("org.jboss.byteman."));

        // ---- item 1: the appends and the reflective premain
        File sysJar = new File(opts.get("sys"));
        File bmJar = new File(opts.get("byteman"));
        try (JarFile jf = new JarFile(bmJar)) {
            r.put("item1.bytemanPremainClass", jf.getManifest().getMainAttributes().getValue("Premain-Class"));
            r.put("item1.bytemanVersion", jf.getManifest().getMainAttributes().getValue("Implementation-Version"));
        }
        Recorder ahead = new Recorder("ahead", TOY);
        inst.addTransformer(ahead, true);
        inst.appendToSystemClassLoaderSearch(new JarFile(sysJar));
        inst.appendToSystemClassLoaderSearch(new JarFile(bmJar));
        Class<?> main = Class.forName("org.jboss.byteman.agent.Main", true, sys);
        r.put("item1.mainLoader", String.valueOf(main.getClassLoader()));
        Method premain = main.getMethod("premain", String.class, Instrumentation.class);
        String bmopts = opts.get("bmopts");
        mark("premain.begin");
        long t0 = System.nanoTime();
        Recorder.own.set(true);
        try {
            premain.invoke(null, bmopts, inst);
        } finally {
            Recorder.own.set(false);
        }
        long premainMs = (System.nanoTime() - t0) / 1_000_000;
        mark("premain.end");
        r.put("item1.premainMs", premainMs);
        r.put("item1.loadedPropertyAfter", System.getProperty("org.jboss.byteman.agent.loaded"));

        Class<?> manager = Class.forName(MANAGER, true, sys);
        r.put("item1.managerLoader", String.valueOf(manager.getClassLoader()));
        Method take = manager.getDeclaredMethod("take");
        take.setAccessible(true);
        Method park = manager.getDeclaredMethod("park", Object.class);
        park.setAccessible(true);
        Method evidence = manager.getDeclaredMethod("evidence");
        evidence.setAccessible(true);
        r.put("item1.initializeEvidence", evidence.invoke(null));
        Object retransformer = take.invoke(null);
        r.put("item1.retransformer", retransformer == null ? "null" : retransformer.getClass().getName());
        boolean gotIt = retransformer != null
                && "org.jboss.byteman.agent.Retransformer".equals(retransformer.getClass().getName());
        r.check("item1.initializeCalledWithRetransformer", gotIt);
        r.check("item1.takeOnce", take.invoke(null) == null);
        r.check("item1.managerOnePublicMethod", publicMethods(manager).equals(Collections.singletonList(
                "initialize(org.jboss.byteman.agent.Retransformer,java.lang.String,java.lang.Integer)")));
        r.put("item1.managerPublicMethods", publicMethods(manager));
        r.check("item1.takeParkPackagePrivate", !Modifier.isPublic(take.getModifiers())
                && !Modifier.isPublic(park.getModifiers()));
        if (!gotIt) {
            r.put("item1.note", "no Retransformer: the rest of the probe needs one");
            closingChecks(socketsBefore, threadsBefore, proc);
            return;
        }

        Recorder behind = new Recorder("behind", TOY);
        inst.addTransformer(behind, true);

        // ---- item 5, during: after the load
        Set<String> socketsLoaded = proc ? ProcNet.listening() : Collections.<String>emptySet();
        Set<String> threadsLoaded = threads();
        r.put("item5.socketsAfterLoad", socketsLoaded);
        r.put("item5.newThreadsAfterLoad", minus(threadsLoaded, threadsBefore));
        r.check("item5.noListenerAfterLoad", socketsLoaded.equals(socketsBefore));
        r.check("item5.noBytemanThreadAfterLoad", minus(threadsLoaded, threadsBefore).isEmpty());

        // ---- item 2: the Retransformer's methods
        Class<?> rc = retransformer.getClass();
        Method install = rc.getMethod("installScript", List.class, List.class, PrintWriter.class);
        Method remove = rc.getMethod("removeScripts", List.class, PrintWriter.class);
        Method list = rc.getMethod("listScripts", PrintWriter.class);
        r.put("item2.installScript", install.toGenericString());
        r.put("item2.removeScripts", remove.toGenericString());
        r.put("item2.listScripts", list.toGenericString());
        r.check("item2.shapes", true);

        // ---- the handler, loaded by name after the appends
        Class<?> handlerClass = Class.forName(HANDLER, true, Probe.class.getClassLoader());
        Object handler = handlerClass.getMethod("arm").invoke(null);
        r.check("item3.handlerInstalled", handler != null);
        r.check("item3.handlerHeld", (Boolean) handlerClass.getMethod("held").invoke(null));

        // ---- item 4a: RuleCheck in process against the loaded toy
        String sound = rule("skillroy:check.sound", "ceiling", "EXIT", "observe($0, $!)");
        String typeError = rule("skillroy:check.typeerror", "ceiling", "EXIT", "observe($0, $!.noSuchMethod())");
        String entryReturn = rule("skillroy:check.entryreturn", "ceiling", "ENTRY", "observe($0, $!)");
        String varargs = rule("skillroy:check.varargs", "ceiling", "ENTRY", "fired($0, $1)");
        ruleCheck("item4.sound", sound);
        ruleCheck("item4.typeError", typeError);
        ruleCheck("item4.entryReturn", entryReturn);
        ruleCheck("item4.varargs", varargs);

        // ---- item 3: install, fire, list, remove, compare bytes
        String entryVarargs = rule("skillroy:entry.varargs", "ceiling", "ENTRY", "fired($0, $1)");
        String entryFixed = rule("skillroy:entry.fixed", "ceiling", "ENTRY", "observe($0, $1)");
        String exitFixed = rule("skillroy:exit.fixed", "ceiling", "EXIT", "observe($0, $!)");
        List<String> texts = Arrays.asList(entryVarargs, entryFixed, exitFixed);
        List<String> names = Arrays.asList("bs14-entry-varargs", "bs14-entry-fixed", "bs14-exit-fixed");
        StringWriter installOut = new StringWriter();
        mark("item3.install.begin");
        t0 = System.nanoTime();
        Recorder.own.set(true);
        try {
            install.invoke(retransformer, texts, names, new PrintWriter(installOut, true));
        } finally {
            Recorder.own.set(false);
        }
        r.put("item3.installMs", (System.nanoTime() - t0) / 1_000_000);
        mark("item3.install.end");
        r.put("item3.installOutput", installOut.toString().trim());
        mark("item3.fire.begin");
        Object result = host.call("STAFF-7");
        mark("item3.fire.end");
        r.put("item3.callResult", result);
        List<String> events = drain(handlerClass);
        r.put("item3.events", events);
        String ev = String.valueOf(events);
        r.check("item3.entryFixedFired", ev.contains("skillroy:entry.fixed|" + TOY + "|ceiling|lending-toy|handle:"
                + TOY + ",String:STAFF-7|n=2"));
        r.check("item3.exitFixedFired", ev.contains("skillroy:exit.fixed|" + TOY + "|ceiling|lending-toy|handle:"
                + TOY + ",BigDecimal:150.00|n=2"));
        r.put("item3.varargsFired", ev.contains("skillroy:entry.varargs|"));
        long[] counters = (long[]) handlerClass.getMethod("counters").invoke(null);
        r.put("item3.helperConstructedWithRule", counters[0]);
        r.put("item3.helperConstructedNoArg", counters[1]);
        r.put("item3.helperFailures", counters[2]);
        r.check("item3.helperRuleConstructor", counters[0] > 0 && counters[1] == 0);
        StringWriter listOut = new StringWriter();
        list.invoke(retransformer, new PrintWriter(listOut, true));
        r.put("item3.listOutput", listOut.toString().trim());
        r.check("item3.listShowsRules", listOut.toString().contains("skillroy:entry.fixed")
                && listOut.toString().contains("skillroy:exit.fixed"));

        byte[] reference = ahead.first();
        byte[] installed = behind.last();
        r.put("item3.referenceBytes", reference == null ? -1 : reference.length);
        r.put("item3.installedBytes", installed == null ? -1 : installed.length);
        List<String> installedRefs = installed == null ? Collections.<String>emptyList()
                : ClassRefs.classReferences(installed);
        r.put("item3.installedBytemanRefs", filter(installedRefs, "org/jboss/byteman/"));
        r.check("item3.installedCarriesRule", !filter(installedRefs, "org/jboss/byteman/").isEmpty());

        StringWriter removeOut = new StringWriter();
        mark("item3.remove.begin");
        t0 = System.nanoTime();
        Recorder.own.set(true);
        try {
            remove.invoke(retransformer, Arrays.asList(entryVarargs, entryFixed, exitFixed),
                    new PrintWriter(removeOut, true));
        } finally {
            Recorder.own.set(false);
        }
        r.put("item3.removeMs", (System.nanoTime() - t0) / 1_000_000);
        mark("item3.remove.end");
        r.put("item3.removeOutput", removeOut.toString().trim());
        byte[] restored = behind.last();
        r.put("item3.restoredBytes", restored == null ? -1 : restored.length);
        r.check("item3.byteIdentical", reference != null && Arrays.equals(reference, restored));
        r.check("item3.noBytemanRefsAfterRemove",
                restored != null && filter(ClassRefs.classReferences(restored), "org/jboss/byteman/").isEmpty());
        r.put("item3.aheadEvents", ahead.count());
        r.put("item3.behindEvents", behind.count());
        r.put("item3.foreignEventsAhead", ahead.foreign());
        r.put("item3.foreignEventsBehind", behind.foreign());
        r.check("item3.noForeignEvents", ahead.foreign() == 0 && behind.foreign() == 0);
        host.call("STAFF-8");
        r.check("item3.noEventsAfterRemove", drain(handlerClass).isEmpty());
        StringWriter listAfter = new StringWriter();
        list.invoke(retransformer, new PrintWriter(listAfter, true));
        r.put("item3.listAfterRemove", listAfter.toString().trim());

        // ---- item 4b: a trigger-time failure, first on Byteman's own streams, then redirected
        String badPlain = rule("skillroy:bad.plain", "deposit", "ENTRY", "noSuchHelperMethod($0, $1)");
        triggerFailure("item4.plainTrigger", install, remove, retransformer, badPlain, "DRL-1");
        String unknownVar = rule("skillroy:bad.unknownvar", "deposit", "ENTRY", "observe($0, undefinedName)");
        triggerFailure("item4.unknownVariable", install, remove, retransformer, unknownVar, "DRL-3");
        Field traceMapField = Class.forName("org.jboss.byteman.rule.helper.Helper", false, sys)
                .getDeclaredField("traceMap");
        traceMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<Object, PrintStream> traceMap = (ConcurrentMap<Object, PrintStream>) traceMapField.get(null);
        ByteArrayOutputStream redirected = new ByteArrayOutputStream();
        PrintStream redirect = new PrintStream(redirected, true, "UTF-8");
        for (String key : new String[] {"out", "err", "vrb", "dbg", "nzy"}) {
            traceMap.put(key, redirect);
        }
        String badRedirected = rule("skillroy:bad.redirected", "deposit", "ENTRY", "noSuchHelperMethod($0, $1)");
        triggerFailure("item4.redirectedTrigger", install, remove, retransformer, badRedirected, "DRL-2");
        r.put("item4.redirectedText", redirected.toString("UTF-8").trim());
        r.check("item4.redirectCaughtTriggerFailure", redirected.size() > 0);
        for (String key : new String[] {"out", "err", "vrb", "dbg", "nzy"}) {
            traceMap.remove(key, redirect);
        }

        // ---- park and release, as the module's uninstall does with no rule left
        park.invoke(null, retransformer);
        Object disarmed = handlerClass.getMethod("disarm", Object.class).invoke(null, handler);
        r.check("item3.handlerReleased", Boolean.TRUE.equals(disarmed)
                && !(Boolean) handlerClass.getMethod("held").invoke(null));

        // ---- reuse: a second module instance takes the parked Retransformer; premain cannot run twice
        reuse();
        try {
            premain.invoke(null, bmopts, inst);
            r.check("reuse.secondPremainRefused", false);
        } catch (InvocationTargetException e) {
            r.put("reuse.secondPremain", e.getCause());
            r.check("reuse.secondPremainRefused", String.valueOf(e.getCause()).contains("more than once"));
        }

        // ---- the sys jar's constant pool
        sysJarRefs(sysJar);

        closingChecks(socketsBefore, threadsBefore, proc);
        mark("end");
    }

    private void closingChecks(Set<String> socketsBefore, Set<String> threadsBefore, boolean proc) throws Exception {
        Set<String> socketsAfter = proc ? ProcNet.listening() : Collections.<String>emptySet();
        Set<String> threadsAfter = threads();
        r.put("item5.socketsAfterUnload", socketsAfter);
        r.put("item5.newThreadsAfterUnload", minus(threadsAfter, threadsBefore));
        r.check("item5.socketsEqualAndEmpty", proc && socketsBefore.isEmpty() && socketsAfter.isEmpty());
        r.check("item5.noNewThreadAfterUnload", minus(threadsAfter, threadsBefore).isEmpty());
    }

    private void ruleCheck(String key, String text) throws Exception {
        Set<String> before = loadedNames();
        Class<?> rcClass = Class.forName("org.jboss.byteman.check.RuleCheck", true, sys);
        Object check = rcClass.getConstructor().newInstance();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, "UTF-8");
        rcClass.getMethod("setPrintStream", PrintStream.class).invoke(check, ps);
        rcClass.getMethod("addRule", String.class, String.class).invoke(check, key, text);
        mark(key + ".begin");
        long t0 = System.nanoTime();
        rcClass.getMethod("checkRules").invoke(check);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        mark(key + ".end");
        Object result = rcClass.getMethod("getResult").invoke(check);
        Class<?> resClass = result.getClass();
        int errors = (Integer) resClass.getMethod("getErrorCount").invoke(result);
        int typeErrors = (Integer) resClass.getMethod("getTypeErrorCount").invoke(result);
        int parseErrors = (Integer) resClass.getMethod("getParseErrorCount").invoke(result);
        int warnings = (Integer) resClass.getMethod("getWarningCount").invoke(result);
        int typeWarnings = (Integer) resClass.getMethod("getTypeWarningCount").invoke(result);
        r.put(key + ".counts", "errors=" + errors + " typeErrors=" + typeErrors + " parseErrors=" + parseErrors
                + " warnings=" + warnings + " typeWarnings=" + typeWarnings);
        r.put(key + ".typeErrorMessages", resClass.getMethod("getTypeErrorMessages").invoke(result));
        r.put(key + ".errorMessages", resClass.getMethod("getErrorMessages").invoke(result));
        r.put(key + ".infoMessages", resClass.getMethod("getInfoMessages").invoke(result));
        r.put(key + ".printStream", buf.toString("UTF-8").trim());
        r.put(key + ".ms", ms);
        Set<String> defined = minus(loadedNames(), before);
        r.put(key + ".classesDefined", defined.size());
        r.put(key + ".classesDefinedSample", sample(defined, 30));
        if (key.endsWith("sound")) {
            r.check(key + ".noError", errors == 0 && typeErrors == 0 && parseErrors == 0);
        }
        if (key.endsWith("typeError")) {
            r.check(key + ".reported", errors + typeErrors + parseErrors > 0);
        }
    }

    private void triggerFailure(String key, Method install, Method remove, Object retransformer, String text,
            String item) throws Exception {
        StringWriter out = new StringWriter();
        mark(key + ".begin");
        Recorder.own.set(true);
        try {
            install.invoke(retransformer, Collections.singletonList(text), Collections.singletonList(key),
                    new PrintWriter(out, true));
        } finally {
            Recorder.own.set(false);
        }
        r.put(key + ".installOutput", out.toString().trim());
        r.put(key + ".callResult", host.call("DEP:" + item));
        StringWriter listed = new StringWriter();
        retransformer.getClass().getMethod("listScripts", PrintWriter.class).invoke(retransformer,
                new PrintWriter(listed, true));
        r.put(key + ".listOutput", listed.toString().trim());
        StringWriter rem = new StringWriter();
        Recorder.own.set(true);
        try {
            remove.invoke(retransformer, Collections.singletonList(text), new PrintWriter(rem, true));
        } finally {
            Recorder.own.set(false);
        }
        mark(key + ".end");
        r.put(key + ".removeOutput", rem.toString().trim());
    }

    private void reuse() throws Exception {
        Class<?> loaderClass = Class.forName("ai.skillroy.testjava.spikes.bs14.agent.ProbeLoader", true, sys);
        URL jar = new File(opts.get("probe")).toURI().toURL();
        Constructor<?> c = loaderClass.getConstructor(URL[].class, ClassLoader.class);
        ClassLoader second = (ClassLoader) c.newInstance(new URL[] {jar}, sys);
        Class<?> secondClass = Class.forName("ai.skillroy.testjava.spikes.bs14.probe.Second", true, second);
        r.check("reuse.secondInstanceIsolated", secondClass != Second.class);
        @SuppressWarnings("unchecked")
        Map<String, String> facts = (Map<String, String>) secondClass.getMethod("run").invoke(null);
        for (Map.Entry<String, String> e : facts.entrySet()) {
            if (e.getKey().startsWith("check.")) {
                r.check(e.getKey().substring(6), "PASS".equals(e.getValue()));
            } else {
                r.put(e.getKey(), e.getValue());
            }
        }
    }

    private void sysJarRefs(File sysJar) throws Exception {
        List<String> outside = new ArrayList<String>();
        List<String> entries = new ArrayList<String>();
        try (JarFile jf = new JarFile(sysJar)) {
            for (JarEntry e : Collections.list(jf.entries())) {
                entries.add(e.getName());
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jf.getInputStream(e)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        b.write(buf, 0, n);
                    }
                    bytes = b.toByteArray();
                }
                for (String ref : ClassRefs.classReferences(bytes)) {
                    if (ref.startsWith("#major=")) {
                        r.put("sysjar.major." + e.getName(), ref.substring(7));
                    } else if (!ref.startsWith("java/") && !ref.startsWith("ai/skillroy/")
                            && !ref.startsWith("[Ljava/")) {
                        outside.add(e.getName() + "->" + ref);
                    }
                }
            }
        }
        r.put("sysjar.entries", entries);
        r.put("sysjar.refsOutsideJavaAndProject", outside);
        r.check("sysjar.constantPoolClean", outside.isEmpty());
    }

    static List<String> drain(Class<?> handlerClass) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> events = (List<String>) handlerClass.getMethod("drain").invoke(null);
        return events;
    }

    private Class<?> loaded(String name) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private int countLoaded(String prefix) {
        int n = 0;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    private Set<String> loadedNames() {
        Set<String> names = new HashSet<String>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            ClassLoader l = c.getClassLoader();
            names.add(c.getName() + "@" + (l == null ? "bootstrap" : l.getClass().getName()));
        }
        return names;
    }

    static Set<String> threads() {
        Set<String> names = new TreeSet<String>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            names.add(t.getName());
        }
        names.remove("bs14-worker");
        return names;
    }

    static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> d = new TreeSet<String>(a);
        d.removeAll(b);
        return d;
    }

    static List<String> filter(List<String> refs, String prefix) {
        List<String> out = new ArrayList<String>();
        for (String s : refs) {
            if (s.startsWith(prefix)) {
                out.add(s);
            }
        }
        return out;
    }

    static List<String> sample(Set<String> s, int max) {
        List<String> out = new ArrayList<String>(new TreeSet<String>(s));
        return out.size() > max ? out.subList(0, max) : out;
    }

    static List<String> publicMethods(Class<?> c) {
        List<String> out = new ArrayList<String>();
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                StringBuilder sb = new StringBuilder(m.getName()).append('(');
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(p[i].getName());
                }
                out.add(sb.append(')').toString());
            }
        }
        Collections.sort(out);
        return out;
    }
}
