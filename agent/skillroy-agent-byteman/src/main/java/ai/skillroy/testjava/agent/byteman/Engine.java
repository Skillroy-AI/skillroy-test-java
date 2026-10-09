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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.instrument.ByteVerification;
import ai.skillroy.testjava.agent.api.instrument.RetransformBatches;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarFile;

/**
 * The module's state and its Byteman route (phase6-contracts.md 7.3-7.5, spike BS14): loading Byteman in process under
 * {@code rule.submit} only, reusing a parked {@code Retransformer}, the two byte recorders, the rules the module
 * installed, the type check through {@code RuleCheck}, the restore and its verification, and the handler that turns a
 * firing into {@code rule.fired}. Every Byteman, {@code RuleManager} and {@code RuleHelper} type is reached by
 * reflection through the system class loader; nothing here names one in a signature or a field.
 *
 * <p>Guarded by its own lock: one submit, unload, list or check at a time (they run on agent workers). The firing
 * path ({@link #fired}) runs on the application's thread and takes no lock. The {@code uninstall} waits for the
 * lock at most the detach budget and keeps it to its end, so that the budget bounds it (7.5 and its fix-wave note).
 */
final class Engine implements FiredSink {

    static final String MAIN = JarCheck.MAIN;
    static final String MANAGER = "ai.skillroy.testjava.agent.byteman.sys.RuleManager";
    static final String HANDLER = "ai.skillroy.testjava.agent.byteman.FiredHandler";
    static final String RULE_CHECK = "org.jboss.byteman.check.RuleCheck";
    static final String HELPER_TRACE = "org.jboss.byteman.rule.helper.Helper";
    static final String RETRANSFORMER = "org.jboss.byteman.agent.Retransformer";
    static final String LOADED_PROPERTY = "org.jboss.byteman.agent.loaded";
    /**
     * Byteman's options: no listener, the manager. In this order (a {@code listener:false} after {@code manager:}
     * clears the manager), and nothing else: {@code prop:} prints on the host's stdout and, set to anything, turns
     * Byteman's verbose output on (BS14 RESULT condition 1).
     */
    static final String OPTIONS = "listener:false,manager:" + MANAGER;
    /** The residue prefix of the byte check: an injected trigger references {@code org/jboss/byteman/rule/Rule}. */
    static final String RESIDUE = "org/jboss/byteman/";
    /** The test seam of 7.6: the uninstall leaves the rules installed, as a cut-short detach budget would. */
    static final String KEEP_RULES_SEAM = "skillroy.agent.byteman.test.keepRulesAtUninstall";
    static final String SOURCE = "byteman";
    static final List<String> TRACE_KEYS = Collections.unmodifiableList(Arrays.asList("out", "err", "vrb", "dbg",
            "nzy"));

    /** A rule the module installed. */
    static final class RuleRecord {
        final String name;
        final String storedName;
        final String text;
        final String className;
        final String method;
        final Set<String> classes = new LinkedHashSet<String>();
        /** The ahead recorder's foreign-event count per class when the rule was installed. */
        final Map<String, Integer> foreignAtInstall = new LinkedHashMap<String, Integer>();

        RuleRecord(RuleLint.Result lint) {
            this.name = lint.name();
            this.storedName = lint.storedName();
            this.text = lint.submitText();
            this.className = lint.className();
            this.method = lint.method();
        }
    }

    private final AgentContext ctx;
    private final Settings settings;
    private final File sysJar;
    private final ClassLoader system;
    private final Set<ClassLoader> agentLoaders;
    /**
     * One {@code rule.*} call at a time; the {@code uninstall} waits for it at most the detach budget
     * ({@code tryLock}) and then keeps it to its end. Package-private for the detach tests.
     */
    final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Thread> own = new AtomicReference<Thread>();
    private final AtomicBoolean armed = new AtomicBoolean();
    /** Set first by {@code uninstall}: from then on no {@code rule.*} call proceeds and nothing is published. */
    private volatile boolean detached;
    /** Guarded by {@link #lock}. */
    private final Map<String, RuleRecord> rules = new LinkedHashMap<String, RuleRecord>();
    /** The names of the rules installed or being installed; read without the lock by a cut-short uninstall. */
    private final Set<String> liveNames = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final BindingEncoder encoder;

    // Written under the lock; volatile so that a cut-short uninstall may read them without it.
    private volatile Object retransformer;
    private volatile boolean reused;
    private volatile ByteRecorder ahead;
    private volatile ByteRecorder behind;
    private volatile Object handler;
    private volatile Class<?> handlerClass;
    private volatile PrintStream trace;
    // Read and written under the lock only.
    private Method installScript;
    private Method removeScripts;
    private Method listScripts;

    /**
     * @param sysJar the jar appended to the system class path before Byteman ({@code <home>/optional/…})
     * @param system the loader Byteman and the sys jar live in (the system class loader; tests pass another)
     */
    Engine(AgentContext ctx, Settings settings, File sysJar, ClassLoader system, Set<ClassLoader> agentLoaders) {
        this.ctx = ctx;
        this.settings = settings;
        this.sysJar = sysJar;
        this.system = system;
        this.agentLoaders = agentLoaders;
        this.encoder = new BindingEncoder(ctx, settings.captureMaxBytes);
    }

    // ------------------------------------------------------------------------------------------- the capability

    /** The {@code rule.engine} probe (7.5). Never loads Byteman and never appends. */
    ProbeResult probe() {
        Instrumentation inst = ctx.instrumentation();
        if (inst == null) {
            return ProbeResult.unavailable("no Instrumentation in " + ctx.loadMode() + " mode");
        }
        lock.lock();
        try {
            if (retransformer != null) {
                return ProbeResult.ok(reused ? "in-process, reused" : "in-process");
            }
        } finally {
            lock.unlock();
        }
        String jar = JarCheck.problem(settings.bytemanJar);
        if (jar != null) {
            return ProbeResult.unavailable(jar);
        }
        if ("true".equals(property(LOADED_PROPERTY))) {
            Class<?> manager = loadable(MANAGER);
            if (manager == null) {
                return ProbeResult.unavailable("Byteman was loaded by the host; the agent cannot reach its rule base");
            }
            Object parked = peek(manager);
            if (parked != null && RETRANSFORMER.equals(parked.getClass().getName())) {
                return ProbeResult.ok("in-process, reused");
            }
            return ProbeResult.unavailable(leftover(parked));
        }
        return ProbeResult.ok("in-process");
    }

    boolean loaded() {
        return retransformer != null;
    }

    boolean reused() {
        lock.lock();
        try {
            return reused;
        } finally {
            lock.unlock();
        }
    }

    /** Takes the lock for a {@code rule.*} call; refuses once the module is detaching. */
    private void enter() {
        lock.lock();
        if (detached) {
            lock.unlock();
            throw unavailable("the byteman module is detaching");
        }
    }

    // ---------------------------------------------------------------------------------------------- rule.check

    /** {@code rule.check}: the lint, then {@code RuleCheck} only when Byteman is loaded and the class is the system loader's. */
    Map<String, Object> check(String text) {
        RuleLint.Result lint = RuleLint.check(text);
        List<String> errors = new ArrayList<String>(lint.errors());
        List<String> warnings = new ArrayList<String>();
        boolean typeChecked = false;
        if (lint.ok()) {
            enter();
            try {
                if (retransformer == null) {
                    warnings.add("type check skipped: Byteman is not loaded (the first rule.submit loads it)");
                } else if (!loadedBySystem(lint.className())) {
                    warnings.add("type check skipped: class not loaded");
                } else {
                    typeCheck(lint, errors, warnings);
                    typeChecked = true;
                }
            } finally {
                lock.unlock();
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", errors.isEmpty());
        out.put("name", lint.name());
        out.put("class", lint.className());
        out.put("method", lint.method());
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("typeChecked", typeChecked);
        return out;
    }

    // --------------------------------------------------------------------------------------------- rule.submit

    /**
     * {@code rule.submit} after the dispatcher's gates: lint, bound, load, type check, install, statuses. The class
     * bound runs before the load (fix wave, L2-F5), so that a rule it refuses appends nothing and runs no
     * {@code premain}.
     */
    Map<String, Object> submit(String text) throws Exception {
        RuleLint.Result lint = RuleLint.check(text);
        if (!lint.ok()) {
            throw rejected(lint.errors());
        }
        Instrumentation inst = ctx.instrumentation();
        if (inst == null) {
            throw unavailable("no Instrumentation in " + ctx.loadMode() + " mode");
        }
        enter();
        try {
            Class<?>[] loaded = inst.getAllLoadedClasses();
            ClassBound.Result bound = new ClassBound(jvm(inst, loaded), settings, agentLoaders).check(lint);
            if (!bound.ok()) {
                throw rejected(bound.errors);
            }
            ensureLoaded();
            if (loadedBySystem(lint.className(), loaded)) {
                List<String> errors = new ArrayList<String>();
                typeCheck(lint, errors, new ArrayList<String>());
                if (!errors.isEmpty()) {
                    throw rejected(errors);
                }
            }
            RuleRecord previous = rules.get(lint.storedName());
            RuleRecord record = new RuleRecord(lint);
            if (previous != null) {
                record.classes.addAll(previous.classes);
            }
            record.classes.add(lint.className());
            for (Class<?> c : bound.matched) {
                record.classes.add(c.getName());
            }
            for (String c : record.classes) {
                ahead.watch(c);
                behind.watch(c);
            }
            if (reused) {
                prime(inst, bound.matched);
            }
            StringWriter out = new StringWriter();
            liveNames.add(lint.name());
            if (detached) {
                // An uninstall gave up waiting for this call (7.5): nothing more is installed, and when no rule of the
                // module is left the Retransformer goes back to the manager as an uninstall with no rule leaves it.
                if (previous == null) {
                    liveNames.remove(lint.name());
                }
                if (rules.isEmpty()) {
                    giveBack(inst);
                }
                throw unavailable("the byteman module is detaching");
            }
            own.set(Thread.currentThread());
            try {
                installScript.invoke(retransformer, Collections.singletonList(record.text),
                        Collections.singletonList("rule.submit"), new PrintWriter(out, true));
            } catch (InvocationTargetException e) {
                if (previous == null) {
                    liveNames.remove(lint.name());
                }
                throw rejected(Collections.singletonList("Byteman refused the rule: " + clip(e.getCause())));
            } finally {
                own.set(null);
            }
            List<Listing.ClassStatus> statuses = Listing.of(Listing.parse(list()), record.storedName);
            List<String> failures = new ArrayList<String>();
            for (Listing.ClassStatus s : statuses) {
                if (s.failed) {
                    failures.add(s.className + ": " + s.status);
                }
            }
            if (!failures.isEmpty()) {
                remove(record);
                liveNames.remove(lint.name());
                rules.remove(record.storedName);
                throw rejected(failures);
            }
            for (Class<?> c : loadedNamed(record.classes)) {
                ByteRecorder.Entry e = ahead.entry(c.getName(), c.getClassLoader());
                record.foreignAtInstall.put(key(c), e == null ? 0 : e.foreign);
            }
            rules.put(record.storedName, record);
            ctx.log().info("byteman: rule " + record.name + " installed on " + record.classes);
            publishChanged(Collections.singletonList(record.name), Collections.<String>emptyList());
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("name", record.name);
            result.put("installed", true);
            result.put("classes", maps(statuses));
            result.put("messages", lines(out.toString()));
            return result;
        } finally {
            lock.unlock();
        }
    }

    // --------------------------------------------------------------------------------------------- rule.unload

    /** {@code rule.unload}: removes one rule of the module and verifies the classes no other rule of it touches. */
    Map<String, Object> unload(String name) throws Exception {
        enter();
        try {
            RuleRecord record = rules.get(RuleLint.NAME_PREFIX + name);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            if (record == null || retransformer == null) {
                result.put("removed", false);
                result.put("restored", Collections.emptyList());
                return result;
            }
            Map<String, Integer> before = behindCounts(record);
            remove(record);
            rules.remove(record.storedName);
            liveNames.remove(record.name);
            handlerStatic("forget", String.class, record.storedName);
            List<Map<String, Object>> restored = verify(record, before);
            publishChanged(Collections.<String>emptyList(), Collections.singletonList(record.name));
            result.put("removed", true);
            result.put("restored", restored);
            List<Map<String, Object>> failed = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : restored) {
                if ("FAILED".equals(r.get("verified"))) {
                    failed.add(r);
                }
            }
            if (!failed.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("classes", failed);
                throw new RpcException(ErrorCodes.HOOK_VERIFY_FAILED, "a class still references Byteman after the"
                        + " rule " + name + " was removed", data);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    // ----------------------------------------------------------------------------------------------- rule.list

    /** {@code rule.list}: the module's own rules, with Byteman's statuses when it is loaded. */
    Map<String, Object> describe() throws Exception {
        enter();
        try {
            Map<String, List<Listing.ClassStatus>> statuses = retransformer == null
                    ? Collections.<String, List<Listing.ClassStatus>>emptyMap() : Listing.parse(list());
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            for (RuleRecord r : rules.values()) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", r.name);
                m.put("class", r.className);
                m.put("method", r.method);
                m.put("classes", maps(Listing.of(statuses, r.storedName)));
                list.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("rules", list);
            out.put("loaded", retransformer != null);
            out.put("reused", reused);
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------- the firing path

    @Override
    public void fired(String rule, String className, String method, Object[] bindings) {
        try {
            if (detached || !armed.get() || rule == null || !rule.startsWith(RuleLint.NAME_PREFIX)) {
                return;
            }
            String name = rule.substring(RuleLint.NAME_PREFIX.length());
            if (!liveNames.contains(name)) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("rule", name);
            payload.put("class", className);
            payload.put("method", method);
            payload.putAll(encoder.encode(name, bindings));
            ctx.events().publish(Topics.RULE_FIRED, SOURCE, payload);
        } catch (Throwable ignored) {
            // Never into the host: a firing that cannot be published is lost.
        }
    }

    // ------------------------------------------------------------------------------------------------ uninstall

    /**
     * The module's {@code uninstall} (7.5 and its fix-wave note), bounded by the detach budget: marks the module
     * detaching and disarms the handler; waits for a running {@code rule.*} call at most the budget; unloads every rule
     * of the module in one {@code removeScripts} on a worker, joined for what is left of the budget; and only when the
     * removal completed in time verifies, parks the {@code Retransformer} and releases the helper's slot and the
     * recorders. Otherwise (a call held the module past the budget, the removal was slower, or it failed) the rules are
     * left behind: logged at WARN by name with the remedy, their names parked in place of the {@code Retransformer} (so
     * the next instance refuses with the names), and the ahead recorder stays registered holding the disarmed handler.
     * A rule left behind keeps running its condition and action (only {@code rule.fired} stops). The worker does
     * nothing but the removal, so a removal that ends after the cut runs no code of the module (the parked names then
     * over-report, which the contract records).
     */
    void uninstall() {
        detached = true;
        armed.set(false);
        long budgetMs = settings.detachBudgetMs;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        Instrumentation inst = ctx.instrumentation();
        boolean locked = false;
        try {
            locked = lock.tryLock(budgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            if (retransformer == null) {
                ctx.log().warn("byteman: a rule.submit still held the module after the detach budget of " + budgetMs
                        + " ms; no rule of the module is installed, and a Retransformer it loads goes back to the"
                        + " manager when it ends");
            } else {
                leaveBehind(inst, new ArrayList<String>(liveNames), "a rule.* call still held the module after the"
                        + " detach budget of " + budgetMs + " ms");
            }
            return;
        }
        try {
            Object r = retransformer;
            if (r == null) {
                ByteRecorder a = ahead;
                if (inst != null && a != null) {
                    inst.removeTransformer(a);
                }
                return;
            }
            List<RuleRecord> all = new ArrayList<RuleRecord>(rules.values());
            Map<RuleRecord, Map<String, Integer>> before = new LinkedHashMap<RuleRecord, Map<String, Integer>>();
            List<String> texts = new ArrayList<String>();
            for (RuleRecord rec : all) {
                before.put(rec, behindCounts(rec));
                texts.add(rec.text);
            }
            Removal removal = new Removal(r, removeScripts, texts);
            Thread worker = new Thread(removal, "skillroy-uninstall-byteman");
            worker.setDaemon(true);
            worker.start();
            joinUntil(worker, deadline);
            int outcome = removal.outcome();
            if (outcome == Removal.DONE) {
                for (RuleRecord rec : all) {
                    rules.remove(rec.storedName);
                    liveNames.remove(rec.name);
                    handlerStatic("forget", String.class, rec.storedName);
                }
                for (Map.Entry<RuleRecord, Map<String, Integer>> e : before.entrySet()) {
                    ctx.log().info("byteman: restored " + verify(e.getKey(), e.getValue()));
                }
                giveBack(inst);
                ctx.log().info("byteman: every rule of the module removed; the Retransformer parked for a later agent"
                        + " instance (Byteman stays loaded; no rule of the agent is installed)");
            } else {
                Throwable failure = outcome == Removal.FAILED ? removal.failure : null;
                if (failure != null) {
                    ctx.log().warn("byteman: the rules could not be removed at detach", failure);
                }
                List<String> names = new ArrayList<String>();
                for (RuleRecord rec : all) {
                    names.add(rec.name);
                }
                leaveBehind(inst, names, failure != null ? "the removal failed"
                        : "the removal did not finish within the detach budget of " + budgetMs + " ms");
            }
        } finally {
            retransformer = null;
            lock.unlock();
        }
    }

    /**
     * The state of 7.5 for rules left behind: the ahead recorder holds the disarmed handler (so the helper's weak slot
     * cannot free while a rule exists), the behind recorder and the trace streams are removed, and the rules' names are
     * parked in the manager's slot in place of the {@code Retransformer}.
     */
    private void leaveBehind(Instrumentation inst, List<String> names, String why) {
        ByteRecorder a = ahead;
        ByteRecorder b = behind;
        if (a != null) {
            a.keep.set(handler);
        }
        if (inst != null && b != null) {
            inst.removeTransformer(b);
        }
        removeTrace();
        park(Collections.unmodifiableList(new ArrayList<String>(names)));
        ctx.log().warn("byteman: rules still installed after detach: " + names + "; their conditions and actions keep"
                + " running (rule.fired stops); restart the target (" + why + ")");
    }

    /**
     * No rule of the module is installed: the {@code Retransformer} goes back to the manager for a later instance, the
     * helper's slot and both recorders are released. Called under the lock.
     */
    private void giveBack(Instrumentation inst) {
        Object r = retransformer;
        retransformer = null;
        if (r == null) {
            return;
        }
        removeTrace();
        park(r);
        handlerStatic("release", Object.class, handler);
        ByteRecorder a = ahead;
        ByteRecorder b = behind;
        if (a != null) {
            a.keep.set(null);
        }
        if (inst != null) {
            if (a != null) {
                inst.removeTransformer(a);
            }
            if (b != null) {
                inst.removeTransformer(b);
            }
        }
    }

    private static void joinUntil(Thread t, long deadline) {
        try {
            for (long left = deadline - System.nanoTime(); left > 0 && t.isAlive();
                    left = deadline - System.nanoTime()) {
                t.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The removal of every rule of the module, on the worker {@code skillroy-uninstall-byteman}. It does nothing but
     * {@code removeScripts} and records its outcome by compare-and-set, so that a removal that ends after the
     * {@code uninstall} gave up changes nothing and needs no class of the module (the loader may be closed by then):
     * nothing is logged, nothing escapes to the default handler.
     */
    private final class Removal implements Runnable {
        static final int RUNNING = 0;
        static final int DONE = 1;
        static final int FAILED = 2;
        static final int ABANDONED = 3;

        private final AtomicInteger state = new AtomicInteger(RUNNING);
        private final Object retransformer;
        private final Method remove;
        private final List<String> texts;
        volatile Throwable failure;

        Removal(Object retransformer, Method remove, List<String> texts) {
            this.retransformer = retransformer;
            this.remove = remove;
            this.texts = texts;
        }

        @Override
        public void run() {
            try {
                if (Boolean.getBoolean(KEEP_RULES_SEAM)) {
                    return;
                }
                if (!texts.isEmpty()) {
                    Thread me = Thread.currentThread();
                    own.set(me);
                    try {
                        remove.invoke(retransformer, texts, new PrintWriter(new StringWriter(), true));
                    } finally {
                        own.compareAndSet(me, null);
                    }
                }
                state.compareAndSet(RUNNING, DONE);
            } catch (Throwable t) {
                failure = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                state.compareAndSet(RUNNING, FAILED);
            }
        }

        /**
         * {@link #DONE} or {@link #FAILED} when the removal ended in time; otherwise {@link #ABANDONED}, set here, so
         * that its late end changes nothing.
         */
        int outcome() {
            state.compareAndSet(RUNNING, ABANDONED);
            return state.get();
        }
    }

    // ------------------------------------------------------------------------------------------------- loading

    /** Loads Byteman (or reuses a parked Retransformer); throws CAPABILITY_UNAVAILABLE with the probe's reason. */
    private void ensureLoaded() throws Exception {
        if (retransformer != null) {
            return;
        }
        Instrumentation inst = ctx.instrumentation();
        if (inst == null) {
            throw unavailable("no Instrumentation in " + ctx.loadMode() + " mode");
        }
        JarCheck.Result jar = JarCheck.check(settings.bytemanJar);
        if (jar.problem != null) {
            throw unavailable(jar.problem);
        }
        if ("true".equals(property(LOADED_PROPERTY))) {
            Class<?> manager = loadable(MANAGER);
            if (manager == null) {
                throw unavailable("Byteman was loaded by the host; the agent cannot reach its rule base");
            }
            Object parked = take(manager);
            if (parked == null || !RETRANSFORMER.equals(parked.getClass().getName())) {
                if (parked != null) {
                    park(parked);
                }
                throw unavailable(leftover(parked));
            }
            reused = true;
            retransformer = parked;
            ahead = new ByteRecorder("ahead", own);
            inst.addTransformer(ahead, true);
            afterLoad(inst);
            ctx.log().info("byteman: reusing the Retransformer an earlier agent instance parked");
            return;
        }
        ahead = new ByteRecorder("ahead", own);
        inst.addTransformer(ahead, true);
        try {
            File bytemanJar = jar.path.toFile();
            inst.appendToSystemClassLoaderSearch(new JarFile(sysJar));
            ctx.log().info("byteman: appended " + sysJar + " (" + sysJar.length() + " bytes) to the system class path");
            inst.appendToSystemClassLoaderSearch(new JarFile(bytemanJar));
            ctx.log().info("byteman: appended " + bytemanJar + " (" + bytemanJar.length() + " bytes) to the system"
                    + " class path");
            Class<?> main = Class.forName(MAIN, true, system);
            Method premain = main.getMethod("premain", String.class, Instrumentation.class);
            long t0 = System.nanoTime();
            own.set(Thread.currentThread());
            try {
                premain.invoke(null, OPTIONS, inst);
            } finally {
                own.set(null);
            }
            Object taken = take(Class.forName(MANAGER, true, system));
            if (taken == null || !RETRANSFORMER.equals(taken.getClass().getName())) {
                throw unavailable("Byteman did not hand its rule base to the manager");
            }
            reused = false;
            retransformer = taken;
            afterLoad(inst);
            ctx.log().info("byteman: loaded in process in " + (System.nanoTime() - t0) / 1000000 + " ms (no listener)");
        } catch (RpcException e) {
            inst.removeTransformer(ahead);
            throw e;
        } catch (Throwable t) {
            inst.removeTransformer(ahead);
            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
            throw unavailable("Byteman could not be loaded: " + clip(cause));
        }
    }

    /** After the Retransformer is in hand: the behind recorder, the methods, the trace map, the handler. */
    private void afterLoad(Instrumentation inst) throws Exception {
        behind = new ByteRecorder("behind", own);
        inst.addTransformer(behind, true);
        Class<?> rc = retransformer.getClass();
        installScript = rc.getMethod("installScript", List.class, List.class, PrintWriter.class);
        removeScripts = rc.getMethod("removeScripts", List.class, PrintWriter.class);
        listScripts = rc.getMethod("listScripts", PrintWriter.class);
        redirectTrace();
        handlerClass = Class.forName(HANDLER, true, Engine.class.getClassLoader());
        handler = handlerClass.getMethod("arm", FiredSink.class).invoke(null, this);
        if (handler == null) {
            throw unavailable("the helper's handler slot is held by another handler");
        }
        armed.set(!detached);
    }

    /** One retransformation of the classes whose reference bytes the ahead recorder has not seen (reuse only). */
    private void prime(Instrumentation inst, List<Class<?>> classes) throws Exception {
        List<Class<?>> todo = new ArrayList<Class<?>>();
        for (Class<?> c : classes) {
            ByteRecorder.Entry e = ahead.entry(c.getName(), c.getClassLoader());
            if (e == null || e.first == null) {
                todo.add(c);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        own.set(Thread.currentThread());
        try {
            for (List<Class<?>> batch : RetransformBatches.of(todo, settings.maxClassBytes, 8)) {
                inst.retransformClasses(batch.toArray(new Class<?>[0]));
            }
        } finally {
            own.set(null);
        }
    }

    private void redirectTrace() {
        try {
            Class<?> helper = Class.forName(HELPER_TRACE, true, system);
            Field field = helper.getDeclaredField("traceMap");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentMap<Object, PrintStream> map = (ConcurrentMap<Object, PrintStream>) field.get(null);
            PrintStream stream = new PrintStream(new LogStream(ctx.log()), true, "UTF-8");
            for (String key : TRACE_KEYS) {
                map.put(key, stream);
            }
            trace = stream;
        } catch (ReflectiveOperationException | RuntimeException | UnsupportedEncodingException e) {
            ctx.log().warn("byteman: its console output could not be redirected to the agent's log: " + e);
        }
    }

    private void removeTrace() {
        PrintStream stream = trace;
        if (stream == null) {
            return;
        }
        try {
            Class<?> helper = Class.forName(HELPER_TRACE, true, system);
            Field field = helper.getDeclaredField("traceMap");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentMap<Object, PrintStream> map = (ConcurrentMap<Object, PrintStream>) field.get(null);
            for (String key : TRACE_KEYS) {
                map.remove(key, stream);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Byteman's own stream then stays ours; it writes to a closed log.
        }
        trace = null;
    }

    // -------------------------------------------------------------------------------- remove and verification

    private void remove(RuleRecord record) throws Exception {
        removeAll(Collections.singletonList(record));
    }

    private void removeAll(List<RuleRecord> records) throws Exception {
        if (records.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<String>();
        for (RuleRecord r : records) {
            texts.add(r.text);
        }
        StringWriter out = new StringWriter();
        own.set(Thread.currentThread());
        try {
            removeScripts.invoke(retransformer, texts, new PrintWriter(out, true));
        } finally {
            own.set(null);
        }
    }

    private Map<String, Integer> behindCounts(RuleRecord record) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Class<?> c : loadedNamed(record.classes)) {
            ByteRecorder.Entry e = behind.entry(c.getName(), c.getClassLoader());
            counts.put(key(c), e == null ? 0 : e.events);
        }
        return counts;
    }

    /**
     * The byte check of 3.5 per class the rule touched that no other rule of the module still touches: the behind
     * recorder's last bytes (the removal's retransformation) against the ahead recorder's first (the reference), with
     * the foreign-event count; {@code OK}, {@code DEGRADED} (bytes differ, foreign events, or no restore observed) or
     * {@code FAILED} (a reference to Byteman is left).
     */
    private List<Map<String, Object>> verify(RuleRecord record, Map<String, Integer> before) {
        Set<String> stillUsed = new LinkedHashSet<String>();
        for (RuleRecord other : rules.values()) {
            if (other != record) {
                stillUsed.addAll(other.classes);
            }
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Class<?> c : loadedNamed(record.classes)) {
            if (stillUsed.contains(c.getName())) {
                continue;
            }
            ByteRecorder.Entry first = ahead.entry(c.getName(), c.getClassLoader());
            ByteRecorder.Entry last = behind.entry(c.getName(), c.getClassLoader());
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("class", c.getName());
            m.put("loader", c.getClassLoader() == null ? "bootstrap" : c.getClassLoader().getClass().getName());
            Integer atInstall = record.foreignAtInstall.get(key(c));
            int foreign = Math.max(0, (first == null ? 0 : first.foreign) - (atInstall == null ? 0 : atInstall));
            Integer seen = before.get(key(c));
            if (first == null || first.first == null || last == null || last.last == null
                    || last.events <= (seen == null ? 0 : seen)) {
                m.put("verified", "DEGRADED");
                m.put("reason", "no restore observed");
            } else {
                ByteVerification v = ByteVerification.compare(first.first, last.last, RESIDUE);
                if (v == ByteVerification.RESIDUE) {
                    m.put("verified", "FAILED");
                    m.put("reason", "the class still references Byteman");
                    m.put("bytesBefore", (long) first.first.length);
                    m.put("bytesAfter", (long) last.last.length);
                } else if (foreign > 0) {
                    m.put("verified", "DEGRADED");
                    m.put("reason", "foreign redefinition observed: " + foreign + " events");
                } else if (v == ByteVerification.FOREIGN_CHANGE) {
                    m.put("verified", "DEGRADED");
                    m.put("reason", "bytes differ: another transformer is present");
                } else {
                    m.put("verified", "OK");
                }
            }
            m.put("foreignEvents", (long) foreign);
            out.add(m);
        }
        return out;
    }

    private List<Class<?>> loadedNamed(Set<String> names) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        Instrumentation inst = ctx.instrumentation();
        if (inst == null) {
            return out;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c != null && names.contains(c.getName()) && (ahead.entry(c.getName(), c.getClassLoader()) != null
                    || behind.entry(c.getName(), c.getClassLoader()) != null)) {
                out.add(c);
            }
        }
        return out;
    }

    private static String key(Class<?> c) {
        return c.getName() + "@" + System.identityHashCode(c.getClassLoader());
    }

    // ------------------------------------------------------------------------------------------- the type check

    private void typeCheck(RuleLint.Result lint, List<String> errors, List<String> warnings) {
        try {
            Class<?> rc = Class.forName(RULE_CHECK, true, system);
            Object check = rc.getConstructor().newInstance();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(buffer, true, "UTF-8");
            rc.getMethod("setPrintStream", PrintStream.class).invoke(check, ps);
            rc.getMethod("addRule", String.class, String.class).invoke(check, lint.storedName(), lint.submitText());
            rc.getMethod("checkRules").invoke(check);
            Object result = rc.getMethod("getResult").invoke(check);
            Class<?> res = result.getClass();
            for (String getter : new String[] {"getErrorMessages", "getParseErrorMessages", "getTypeErrorMessages",
                    "getTypeWarningMessages"}) {
                for (Object message : (List<?>) res.getMethod(getter).invoke(result)) {
                    errors.add(clip(String.valueOf(message)));
                }
            }
            for (Object message : (List<?>) res.getMethod("getWarningMessages").invoke(result)) {
                warnings.add(clip(String.valueOf(message)));
            }
            for (String line : lines(buffer.toString("UTF-8"))) {
                if (line.startsWith("WARNING") && !warnings.contains(line)) {
                    warnings.add(clip(line));
                }
            }
        } catch (InvocationTargetException e) {
            errors.add("the type check failed: " + clip(e.getCause()));
        } catch (ReflectiveOperationException | RuntimeException | UnsupportedEncodingException | LinkageError e) {
            errors.add("the type check failed: " + clip(e));
        }
    }

    private boolean loadedBySystem(String className) {
        Instrumentation inst = ctx.instrumentation();
        return inst != null && loadedBySystem(className, inst.getAllLoadedClasses());
    }

    private boolean loadedBySystem(String className, Class<?>[] loaded) {
        List<Class<?>> named = ClassBound.index(loaded).get(className);
        if (named == null) {
            return false;
        }
        for (Class<?> c : named) {
            if (c.getClassLoader() == system) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private String list() throws Exception {
        StringWriter out = new StringWriter();
        listScripts.invoke(retransformer, new PrintWriter(out, true));
        return out.toString();
    }

    /**
     * A static method of the handler class, by reflection: the class names a sys-jar type and is linked only after the
     * append (BS3 RESULT section 9 fact 9), so nothing here calls it directly.
     */
    private void handlerStatic(String method, Class<?> type, Object arg) {
        Class<?> c = handlerClass;
        if (c == null) {
            return;
        }
        try {
            c.getMethod(method, type).invoke(null, arg);
        } catch (ReflectiveOperationException | RuntimeException e) {
            ctx.log().warn("byteman: " + method + " failed: " + e);
        }
    }

    private void publishChanged(List<String> installed, List<String> removed) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("installed", installed);
        payload.put("removed", removed);
        ctx.events().publish(Topics.RULES_CHANGED, SOURCE, payload);
    }

    private Class<?> loadable(String name) {
        try {
            return Class.forName(name, false, system);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** What {@code RuleManager} holds, read without taking it (the probe). */
    private static Object peek(Class<?> manager) {
        try {
            Field slot = manager.getDeclaredField("slot");
            slot.setAccessible(true);
            return slot.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object take(Class<?> manager) throws ReflectiveOperationException {
        Method take = manager.getDeclaredMethod("take");
        take.setAccessible(true);
        return take.invoke(null);
    }

    private void park(Object value) {
        try {
            Method park = Class.forName(MANAGER, false, system).getDeclaredMethod("park", Object.class);
            park.setAccessible(true);
            park.invoke(null, value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ctx.log().warn("byteman: the manager could not keep " + (value instanceof List ? "the names of the rules"
                    + " left behind" : "the Retransformer") + ": " + e);
        }
    }

    private static String leftover(Object parked) {
        String names = parked instanceof List && !((List<?>) parked).isEmpty()
                ? String.valueOf(parked).replace("[", "").replace("]", "")
                : "unknown (see the earlier instance's log)";
        return "Byteman rules of an earlier agent instance are still installed: " + names + "; restart the target";
    }

    private static String property(String name) {
        try {
            return System.getProperty(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ClassBound.Jvm jvm(final Instrumentation inst, final Class<?>[] loaded) {
        return new ClassBound.Jvm() {
            @Override
            public Class<?>[] loadedClasses() {
                return loaded;
            }

            @Override
            public boolean isModifiable(Class<?> c) {
                return inst.isModifiableClass(c);
            }
        };
    }

    private static RpcException rejected(List<String> errors) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("errors", new ArrayList<String>(errors));
        return new RpcException(ErrorCodes.RULE_REJECTED, "the rule was refused: " + (errors.isEmpty() ? ""
                : errors.get(0)) + (errors.size() > 1 ? " (and " + (errors.size() - 1) + " more)" : ""), data);
    }

    private static RpcException unavailable(String reason) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("capability", BytemanModule.CAPABILITY);
        data.put("reason", reason);
        return new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, reason, data);
    }

    private static List<Map<String, Object>> maps(List<Listing.ClassStatus> statuses) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Listing.ClassStatus s : statuses) {
            out.add(s.toMap());
        }
        return out;
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<String>();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (!line.trim().isEmpty()) {
                out.add(clip(line.trim()));
            }
        }
        return out;
    }

    private static String clip(Object o) {
        String s = String.valueOf(o);
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }
}
