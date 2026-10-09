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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.instrument.ByteVerification;
import ai.skillroy.testjava.agent.api.instrument.RetransformBatches;
import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.hook.HookHealth;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookProbe;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookState;
import ai.skillroy.testjava.protocol.hook.HookStatus;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The hook engine (phase6-contracts.md section 3): the probe of a spec, the first install with its bootstrap append
 * and handler, the transformer, the ledger, the restore with its byte verification, health, stubs and the detach.
 * Used by the six primitives and, as the {@link HookEngine} SPI, by domain modules.
 *
 * <p><b>Nothing here names a {@code Spy} type</b> in a signature or a field: the handler is held as {@code Object} and
 * created by reflection after the append; {@link SpyBridge} is called only once {@code Spy} is on the bootstrap path.
 *
 * <p>Thread-safe: an install, a remove, a stub and the detach are serialised by one lock (the detach waits for it at
 * most its budget); probes and health read concurrent state. Every method runs on an agent thread.
 */
final class HookEngineImpl implements HookEngine {

    /**
     * Set by a detach that ran out of budget to the number of classes left carrying an inert advice, so that a later
     * agent's probe can say how many (section 3.2: the slot stays held until the JVM exits).
     */
    static final String ADVICE_LEFT_PROPERTY = "skillroy.hooks.adviceLeft";
    static final int MAX_BATCH_CLASSES = 8;
    static final int MAX_SPECS = 64;

    private final AgentContext ctx;
    private final Instrumentation inst;
    final HookOptions options;
    private final AgentLog log;
    final Ledger ledger = new Ledger();
    final ConcurrentHashMap<String, HookEntry> hooks = new ConcurrentHashMap<String, HookEntry>();
    final ConcurrentHashMap<String, Site> sites = new ConcurrentHashMap<String, Site>();
    /** Hooks whose class kept a reference to Spy after a remove (section 3.5): health reports them ERROR. */
    private final Map<String, HookEntry> residue = new ConcurrentHashMap<String, HookEntry>();
    final HandlerCore core;
    private final Probes probes;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, AutoCloseable> declarations = new LinkedHashMap<String, AutoCloseable>();
    private final Map<String, HookStatus> lastStatuses = new LinkedHashMap<String, HookStatus>();

    /** The handler, a {@code HookHandler} held as Object (no Spy type in a field). */
    private volatile Object handler;
    private volatile boolean handlerInstalled;
    private Transformers transformers;
    private boolean registered;
    private volatile Catalogue catalogue;
    /** Why the spy jar was refused at the last append attempt, or null. */
    private volatile String spyJarRefusal;
    private volatile boolean detached;

    HookEngineImpl(AgentContext ctx, HookOptions options) {
        this.ctx = ctx;
        this.inst = ctx.instrumentation();
        this.options = options;
        this.log = ctx.log();
        this.core = new HandlerCore(sites, ctx.events(), new Capture(ctx, options.captureMaxBytes));
        this.probes = inst == null ? null : new Probes(inst, ctx.hostClassLoader(), options, ledger);
    }

    /** The installed catalogue. */
    static final class Catalogue {
        final String id;
        final Object version;
        final String hash;
        final String application;
        final long installedAt;
        final List<HookSpec> specs;

        Catalogue(String id, Object version, String hash, String application, long installedAt,
                  List<HookSpec> specs) {
            this.id = id;
            this.version = version;
            this.hash = hash;
            this.application = application;
            this.installedAt = installedAt;
            this.specs = Collections.unmodifiableList(new ArrayList<HookSpec>(specs));
        }

        boolean sameSpecs(List<HookSpec> other) {
            return new HashSet<HookSpec>(specs).equals(new HashSet<HookSpec>(other));
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("id", id);
            json.put("hash", hash);
            json.put("installedAt", Long.valueOf(installedAt));
            return json;
        }
    }

    // ------------------------------------------------------------------------------------------------ the engine probe

    /** {@code hook.engine}'s state (section 3.2): OK(strategy advice), or UNAVAILABLE with the first reason. */
    ProbeResult engineProbe() {
        String reason = engineUnavailable();
        return reason == null ? ProbeResult.ok(Probes.STRATEGY) : ProbeResult.unavailable(reason);
    }

    /** The probe's checks in order; null when the engine can work. */
    String engineUnavailable() {
        if (inst == null) {
            return "no Instrumentation in " + ctx.loadMode() + " mode";
        }
        if (!inst.isRetransformClassesSupported()) {
            return "retransformation is not supported by this JVM";
        }
        Class<?> boot = SpyBoot.bootSpy();
        if (boot == null) {
            File jar = spyJar();
            if (jar == null || !jar.isFile()) {
                return "spy jar not found: " + (jar == null ? "(no home and no hooks.spyJar)" : jar.getPath());
            }
            return spyJarRefusal;
        }
        int version = SpyBoot.version(boot);
        if (version != SpyBoot.VERSION) {
            return "Spy version " + version + " is already on the bootstrap path; this module needs "
                    + SpyBoot.VERSION;
        }
        if (!handlerInstalled && SpyBridge.held()) {
            String left = System.getProperty(ADVICE_LEFT_PROPERTY);
            return "Spy slot held by a disarmed handler of an earlier agent; " + (left == null ? "an unknown number of"
                    : left) + " classes still carry advice";
        }
        return null;
    }

    private File spyJar() {
        return options.spyJar == null ? null : new File(options.spyJar);
    }

    private void requireInstrumentation() {
        if (inst == null) {
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "hook.engine is UNAVAILABLE: "
                    + engineUnavailable(), data("capability", HooksModule.ENGINE, "reason", engineUnavailable()));
        }
    }

    // ------------------------------------------------------------------------------------------------------- probe

    @Override
    public HookProbe probe(HookSpec spec) {
        return probeAll(Collections.singletonList(spec)).get(0);
    }

    /** The probe answers of the specs, in order (section 3.3); never installs, never appends. */
    List<HookProbe> probeAll(List<HookSpec> specs) {
        requireInstrumentation();
        validate(specs);
        Class<?> boot = SpyBoot.bootSpy();
        Map<String, List<Class<?>>> index = probes.index(specs);
        List<HookProbe> out = new ArrayList<HookProbe>();
        for (HookSpec spec : specs) {
            out.add(probes.probe(spec, index, boot).probe);
        }
        return out;
    }

    /** {@code hook.probe}'s answer. */
    Map<String, Object> probeCall(List<HookSpec> specs) {
        List<Object> answers = new ArrayList<Object>();
        for (HookProbe p : probeAll(specs)) {
            answers.add(p.toJson());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("hooks", answers);
        out.put("spy", spyInfo());
        out.put("otherAgents", OtherAgents.list());
        return out;
    }

    Map<String, Object> spyInfo() {
        Class<?> boot = SpyBoot.bootSpy();
        Map<String, Object> spy = new LinkedHashMap<String, Object>();
        spy.put("onBootPath", Boolean.valueOf(boot != null));
        spy.put("version", boot == null ? null : Long.valueOf(SpyBoot.version(boot)));
        spy.put("slotFree", Boolean.valueOf(boot == null || !SpyBridge.held()));
        spy.put("slotHeldByThisEngine", Boolean.valueOf(handlerInstalled));
        return spy;
    }

    /** Section 3.3 item 1, and the call's own rules: {@code INVALID_PARAMS} naming the field. */
    static void validate(List<HookSpec> specs) {
        if (specs.size() > MAX_SPECS) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "at most " + MAX_SPECS + " specs per call",
                    data("param", "specs"));
        }
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < specs.size(); i++) {
            HookSpec spec = specs.get(i);
            HookSpec.Violation v = spec.violation();
            if (v != null) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, v.message(), data("param", "specs[" + i + "]."
                        + v.field()));
            }
            if (spec.capture() != null && spec.mode() != HookMode.CAPTURE) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "capture is for CAPTURE hooks; hook " + spec.id()
                        + " is " + spec.mode(), data("param", "specs[" + i + "].capture"));
            }
            if (!ids.add(spec.id())) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "duplicate hook id " + spec.id(),
                        data("param", "specs[" + i + "].id"));
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------- install

    @Override
    public List<HookStatus> install(List<HookSpec> specs, String catalogueId, String catalogueHash, boolean replace) {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("id", catalogueId);
        info.put("hash", catalogueHash);
        return statuses(installCall(specs, info, replace));
    }

    @SuppressWarnings("unchecked")
    private static List<HookStatus> statuses(Map<String, Object> result) {
        List<HookStatus> out = new ArrayList<HookStatus>();
        for (Object o : (List<Object>) result.get("hooks")) {
            out.add(HookStatus.fromJson((Map<String, Object>) o));
        }
        return out;
    }

    /** {@code hook.install} (sections 3.2 and 4.2): idempotent by catalogue hash. */
    Map<String, Object> installCall(List<HookSpec> specs, Map<String, Object> info, boolean replace) {
        requireInstrumentation();
        validate(specs);
        String catalogueId = string(info.get("id"));
        String catalogueHash = string(info.get("hash"));
        if (catalogueId == null || catalogueHash == null) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "the catalogue needs an id and a hash",
                    data("param", catalogueId == null ? "catalogue.id" : "catalogue.hash"));
        }
        lock.lock();
        try {
            if (detached) {
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "the hooks module is uninstalling",
                        data("capability", HooksModule.ENGINE));
            }
            Catalogue current = catalogue;
            if (current != null) {
                boolean sameId = current.id.equals(catalogueId);
                boolean sameHash = current.hash.equals(catalogueHash);
                boolean sameSpecs = current.sameSpecs(specs);
                if (sameId && sameHash && sameSpecs) {
                    return alreadyInstalled(current);
                }
                if (!replace) {
                    String reason = sameHash ? "spec set differs under the same hash"
                            : "catalogue " + current.id + " (" + current.hash + ") is installed";
                    throw new RpcException(ErrorCodes.HOOK_CONFLICT, reason, data("reason", reason, "installed",
                            current.toJson()));
                }
                removeLocked(Collections.<String>emptyList(), Long.MAX_VALUE, false, false);
            }
            return installLocked(specs, catalogueId, catalogueHash, info);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> alreadyInstalled(Catalogue current) {
        Map<String, Object> cat = current.toJson();
        cat.put("alreadyInstalled", Boolean.TRUE);
        List<Object> statuses = new ArrayList<Object>();
        for (HookSpec spec : current.specs) {
            statuses.add(currentStatus(spec.id()).toJson());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("catalogue", cat);
        out.put("hooks", statuses);
        out.put("touched", new ArrayList<Object>());
        out.put("otherAgents", OtherAgents.list());
        return out;
    }

    private HookStatus currentStatus(String id) {
        HookEntry hook = hooks.get(id);
        if (hook == null) {
            HookStatus last = lastStatuses.get(id);
            return last != null ? last : new HookStatus(id, CapabilityState.UNAVAILABLE, "not installed", null,
                    null);
        }
        ProbeResult state = capabilityState(hook);
        return new HookStatus(id, state.state(), state.reason(), Probes.STRATEGY, hook.spec.capabilityId());
    }

    private Map<String, Object> installLocked(List<HookSpec> specs, String catalogueId, String catalogueHash,
                                              Map<String, Object> info) {
        // (1) the probe's checks again, so that a stale matrix cannot pass a broken state (a jar refused earlier is
        // checked again at step 3: the operator may have replaced it)
        spyJarRefusal = null;
        String unavailable = engineUnavailable();
        if (unavailable != null) {
            throw unavailable(unavailable);
        }
        // (2) section 3.3 items 1 to 5 and 7 for every spec
        Class<?> boot = SpyBoot.bootSpy();
        Map<String, List<Class<?>>> index = probes.index(specs);
        List<Probes.Outcome> outcomes = new ArrayList<Probes.Outcome>();
        Set<String> taken = new HashSet<String>();
        for (Site s : sites.values()) {
            taken.add(s.className + "." + s.method + s.descriptor);
        }
        Set<Class<?>> newClasses = Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
        for (HookSpec spec : specs) {
            Probes.Outcome o = probes.probe(spec, index, boot);
            if (o.passes()) {
                String clash = null;
                int adding = 0;
                for (Probes.Match m : o.matches) {
                    for (Method pinned : m.pinned) {
                        String where = m.type.getName() + "." + pinned.getName() + Descriptors.of(pinned);
                        if (taken.contains(where)) {
                            clash = where;
                        }
                    }
                    if (ledger.find(m.type.getName().replace('.', '/'), m.type) == null
                            && !newClasses.contains(m.type)) {
                        adding++;
                    }
                }
                if (clash != null) {
                    o.refuse("method already hooked: " + clash, o.probe.spyVisibility());
                } else if (ledger.size() + newClasses.size() + adding > options.maxClasses) {
                    o.refuse("ledger full", o.probe.spyVisibility());
                } else {
                    for (Probes.Match m : o.matches) {
                        for (Method pinned : m.pinned) {
                            taken.add(m.type.getName() + "." + pinned.getName() + Descriptors.of(pinned));
                        }
                        if (ledger.find(m.type.getName().replace('.', '/'), m.type) == null) {
                            newClasses.add(m.type);
                        }
                    }
                }
            }
            outcomes.add(o);
        }
        boolean any = false;
        for (Probes.Outcome o : outcomes) {
            any |= o.passes();
        }
        if (!any) {
            return result(catalogueId, catalogueHash, null, outcomes, Collections.<HookEntry>emptyList(),
                    new ArrayList<Object>());
        }
        // (3) the append, once per JVM, after the content check
        if (boot == null) {
            File jar = spyJar();
            String refusal = SpyBoot.checkJar(jar);
            if (refusal != null) {
                spyJarRefusal = refusal;
                throw unavailable(refusal);
            }
            try {
                boot = SpyBoot.append(inst, jar);
            } catch (Throwable t) {
                throw unavailable("appending the spy jar failed: " + t.getClass().getName() + ": " + t.getMessage());
            }
            log.info("hooks: appended " + jar.getPath() + " (" + jar.length() + " bytes) to the bootstrap search path"
                    + " (once for the JVM's life)");
            int version = SpyBoot.version(boot);
            if (version != SpyBoot.VERSION) {
                throw unavailable("Spy version " + version + " is on the bootstrap path; this module needs "
                        + SpyBoot.VERSION);
            }
        }
        // (4) the one handler, created by reflection now that Spy is on the path
        if (handler == null) {
            handler = newHandler();
        }
        if (!handlerInstalled) {
            if (!SpyBridge.install(handler)) {
                throw unavailable(engineUnavailable() != null ? engineUnavailable()
                        : "Spy slot held by another handler");
            }
            handlerInstalled = true;
        }
        // (5) item 6, the visibility check by identity, now decidable
        for (Probes.Outcome o : outcomes) {
            if (!o.passes()) {
                continue;
            }
            for (Probes.Match m : o.matches) {
                String[] v = SpyBoot.visibility(m.type.getClassLoader(), boot);
                if (!"VISIBLE".equals(v[0])) {
                    o.refuse(v[1], HookProbe.SpyVisibility.NOT_VISIBLE);
                    break;
                }
            }
        }
        // (6) the transformer, the registry and the retransformation
        if (transformers == null) {
            transformers = new Transformers(ledger, handler, options.maxClassBytes, log);
        }
        if (!registered) {
            inst.addTransformer(transformers.recorder, true);
            inst.addTransformer(transformers.preFilter, true);
            registered = true;
        }
        Map<HookEntry, Probes.Outcome> installing = new LinkedHashMap<HookEntry, Probes.Outcome>();
        Map<Ledger.Entry, List<Site>> added = new LinkedHashMap<Ledger.Entry, List<Site>>();
        Set<Ledger.Entry> created = new HashSet<Ledger.Entry>();
        for (Probes.Outcome o : outcomes) {
            if (!o.passes()) {
                continue;
            }
            HookEntry hook = new HookEntry(o.spec, o.probe.state() == CapabilityState.DEGRADED ? o.probe.reason()
                    : null);
            List<Site> hookSites = new ArrayList<Site>();
            List<Ledger.Entry> classes = new ArrayList<Ledger.Entry>();
            for (Probes.Match m : o.matches) {
                Ledger.Entry entry = ledger.find(m.type.getName().replace('.', '/'), m.type);
                if (entry == null) {
                    entry = new Ledger.Entry(m.type, Ledger.loaderId(m.type.getClassLoader()), m.sizeBytes,
                            m.methods);
                    ledger.add(entry);
                    created.add(entry);
                }
                classes.add(entry);
                for (Method pinned : m.pinned) {
                    Site site = new Site(hook, m.type.getName(), pinned.getName(), Descriptors.of(pinned),
                            pinned.getReturnType());
                    hookSites.add(site);
                    List<Site> list = added.get(entry);
                    if (list == null) {
                        list = new ArrayList<Site>();
                        added.put(entry, list);
                    }
                    list.add(site);
                    transformers.prepare(site);
                }
            }
            hook.sites(hookSites);
            hook.classes = Collections.unmodifiableList(classes);
            for (Site s : hookSites) {
                sites.put(s.key, s);
            }
            hooks.put(hook.id, hook);
            installing.put(hook, o);
        }
        for (Map.Entry<Ledger.Entry, List<Site>> e : added.entrySet()) {
            List<Site> all = new ArrayList<Site>(e.getKey().sites);
            all.addAll(e.getValue());
            e.getKey().sites = Collections.unmodifiableList(all);
        }
        List<Object> touched = new ArrayList<Object>();
        Transformers.Round round = retransform(new ArrayList<Ledger.Entry>(added.keySet()), touched, "install");
        List<HookEntry> installed = new ArrayList<HookEntry>();
        for (Map.Entry<HookEntry, Probes.Outcome> e : installing.entrySet()) {
            HookEntry hook = e.getKey();
            String failure = null;
            for (Ledger.Entry entry : hook.classes) {
                String key = Transformers.Round.key(entry.name, entry.loader.get());
                String error = round.errors.get(key);
                if (error == null && !round.transformed.contains(key)) {
                    error = entry.error != null ? entry.error : "the class was not retransformed";
                }
                if (error != null) {
                    failure = "retransform failed: " + error;
                    break;
                }
            }
            if (failure == null) {
                installed.add(hook);
            } else {
                e.getValue().refuse(failure, e.getValue().probe.spyVisibility());
                dropSites(hook);
            }
        }
        for (Ledger.Entry entry : created) {
            if (entry.sites.isEmpty()) {
                ledger.remove(entry);
            }
        }
        // (7) the capabilities, the catalogue and the event
        for (HookEntry hook : installed) {
            declare(hook);
        }
        Catalogue installedCatalogue = null;
        if (!installed.isEmpty()) {
            installedCatalogue = new Catalogue(catalogueId, info.get("version"), catalogueHash,
                    string(info.get("application")), System.currentTimeMillis(), specs);
            catalogue = installedCatalogue;
        }
        Map<String, Object> result = result(catalogueId, catalogueHash, installedCatalogue, outcomes, installed,
                touched);
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        Map<String, Object> cat = new LinkedHashMap<String, Object>();
        cat.put("id", catalogueId);
        cat.put("hash", catalogueHash);
        event.put("catalogue", cat);
        List<Object> ids = new ArrayList<Object>();
        for (HookEntry hook : installed) {
            ids.add(hook.id);
        }
        event.put("hooks", ids);
        event.put("touched", Long.valueOf(touched.size()));
        ctx.events().publish(Topics.HOOKS_INSTALLED, HooksModule.SOURCE, event);
        return result;
    }

    private Map<String, Object> result(String catalogueId, String catalogueHash, Catalogue installed,
                                       List<Probes.Outcome> outcomes, List<HookEntry> hooksInstalled,
                                       List<Object> touched) {
        Set<String> ok = new HashSet<String>();
        for (HookEntry hook : hooksInstalled) {
            ok.add(hook.id);
        }
        List<Object> statuses = new ArrayList<Object>();
        for (Probes.Outcome o : outcomes) {
            HookStatus status = ok.contains(o.spec.id())
                    ? new HookStatus(o.spec.id(), o.probe.state(), o.probe.reason(), Probes.STRATEGY,
                    o.spec.capabilityId())
                    : new HookStatus(o.spec.id(), CapabilityState.UNAVAILABLE, o.probe.reason(), null, null);
            lastStatuses.put(o.spec.id(), status);
            statuses.add(status.toJson());
        }
        Map<String, Object> cat = new LinkedHashMap<String, Object>();
        cat.put("id", catalogueId);
        cat.put("hash", catalogueHash);
        cat.put("installedAt", installed == null ? null : Long.valueOf(installed.installedAt));
        cat.put("alreadyInstalled", Boolean.FALSE);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("catalogue", cat);
        out.put("hooks", statuses);
        out.put("touched", touched);
        out.put("otherAgents", OtherAgents.list());
        return out;
    }

    /** Retransforms the entries in batches (section 3.2); fills {@code touched} with one line per class. */
    private Transformers.Round retransform(List<Ledger.Entry> entries, List<Object> touched, String why) {
        Transformers.Round round = new Transformers.Round();
        List<Class<?>> classes = new ArrayList<Class<?>>();
        final Map<Class<?>, Ledger.Entry> byClass = new IdentityHashMap<Class<?>, Ledger.Entry>();
        for (Ledger.Entry e : entries) {
            Class<?> c = e.type.get();
            if (c != null) {
                classes.add(c);
                byClass.put(c, e);
            }
        }
        for (Ledger.Entry e : entries) {
            e.error = null;
        }
        transformers.round = round;
        try {
            for (List<Class<?>> batch : RetransformBatches.of(classes, new RetransformBatches.Sizes() {
                @Override
                public long bytes(Class<?> type) {
                    Ledger.Entry e = byClass.get(type);
                    byte[] first = e == null ? null : e.first;
                    return first != null ? first.length : e != null && e.sizeBytes > 0 ? e.sizeBytes : -1;
                }
            }, options.maxClassBytes, MAX_BATCH_CLASSES)) {
                long t0 = System.nanoTime();
                try {
                    inst.retransformClasses(batch.toArray(new Class<?>[0]));
                } catch (Throwable t) {
                    for (Class<?> c : batch) {
                        round.errors.put(Transformers.Round.key(c.getName(), c.getClassLoader()),
                                t.getClass().getName() + ": " + t.getMessage());
                    }
                }
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                for (Class<?> c : batch) {
                    Ledger.Entry e = byClass.get(c);
                    e.own++;
                    if (touched != null) {
                        Map<String, Object> line = new LinkedHashMap<String, Object>();
                        line.put("class", c.getName());
                        line.put("loader", e.loaderId);
                        byte[] first = e.first;
                        line.put("bytes", Long.valueOf(first != null ? first.length : e.sizeBytes));
                        line.put("methods", Long.valueOf(e.methods));
                        line.put("ms", Long.valueOf(ms));
                        touched.add(line);
                    }
                }
                log.info("hooks: " + why + " retransformed " + Transformers.describe(batch) + " in " + ms + " ms");
            }
        } finally {
            transformers.round = null;
        }
        return round;
    }

    private void dropSites(HookEntry hook) {
        hooks.remove(hook.id);
        Set<Site> mine = new HashSet<Site>(hook.sites);
        for (Site s : hook.sites) {
            sites.remove(s.key);
            transformers.forget(s);
        }
        for (Ledger.Entry entry : ledger.entries()) {
            List<Site> keep = new ArrayList<Site>();
            for (Site s : entry.sites) {
                if (!mine.contains(s)) {
                    keep.add(s);
                }
            }
            entry.sites = Collections.unmodifiableList(keep);
        }
    }

    private void declare(final HookEntry hook) {
        AutoCloseable previous = declarations.remove(hook.id);
        closeQuietly(previous);
        declarations.put(hook.id, ctx.capabilities().declare(hook.spec.capabilityId(), new Probe() {
            @Override
            public ProbeResult run(AgentContext context) {
                return capabilityState(hook);
            }
        }));
    }

    /** {@code hook.<id>} (section 4.6): OK while INSTALLED or FIRING, DEGRADED for STALE, UNAVAILABLE for ERROR. */
    private ProbeResult capabilityState(HookEntry hook) {
        HookHealth h = healthOf(hook, false);
        switch (h.state()) {
            case ERROR:
                return ProbeResult.unavailable(h.reason());
            case STALE:
                return ProbeResult.degraded(h.reason());
            default:
                return hook.degraded != null ? ProbeResult.degraded(hook.degraded) : ProbeResult.ok(Probes.STRATEGY);
        }
    }

    private Object newHandler() {
        try {
            Class<?> type = Class.forName(HookEngineImpl.class.getPackage().getName() + ".HookHandler", true,
                    HookEngineImpl.class.getClassLoader());
            return type.getDeclaredConstructor(HandlerCore.class).newInstance(core);
        } catch (Exception e) {
            throw unavailable("the handler cannot be created: " + e);
        } catch (LinkageError e) {
            throw unavailable("the handler cannot be linked against Spy: " + e);
        }
    }

    // ------------------------------------------------------------------------------------------------------ stubs

    @Override
    public boolean stub(String id, Object value, boolean clear) {
        if (ctx.readOnly()) {
            throw new RpcException(ErrorCodes.READ_ONLY, "a stub changes the target's behaviour and the agent is "
                    + "read-only", data("primitive", "hook.stub"));
        }
        return Boolean.TRUE.equals(stubCall(id, value, clear, "spi").get("armed"));
    }

    /** {@code hook.stub} (section 4.2): arms or clears; publishes {@code hooks.stubbed}. */
    Map<String, Object> stubCall(String id, Object value, boolean clear, String by) {
        requireInstrumentation();
        lock.lock();
        try {
            HookEntry hook = id == null ? null : hooks.get(id);
            if (hook == null) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "hook " + id + " is not installed",
                        data("param", "id"));
            }
            if (hook.mode != HookMode.STUB) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "hook " + id + " is a " + hook.mode
                        + " hook; only a STUB hook is armed", data("param", "id"));
            }
            List<String> returnTypes = new ArrayList<String>();
            for (Site s : hook.sites) {
                if (!returnTypes.contains(s.returnTypeName())) {
                    returnTypes.add(s.returnTypeName());
                }
            }
            String returnType = returnTypes.size() == 1 ? returnTypes.get(0) : returnTypes.toString();
            if (clear) {
                for (Site s : hook.sites) {
                    s.armed = null;
                }
            } else {
                Map<Site, HandlerCore.Armed> converted = new LinkedHashMap<Site, HandlerCore.Armed>();
                for (Site s : hook.sites) {
                    try {
                        converted.put(s, new HandlerCore.Armed(StubValues.convert(value, s)));
                    } catch (RuntimeException e) {
                        throw new RpcException(ErrorCodes.INVALID_PARAMS, e.getMessage(), data("param", "value",
                                "returnType", s.returnTypeName()));
                    }
                }
                for (Map.Entry<Site, HandlerCore.Armed> e : converted.entrySet()) {
                    e.getKey().armed = e.getValue();
                }
            }
            boolean armed = hook.armed();
            Map<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("id", id);
            event.put("armed", Boolean.valueOf(armed));
            event.put("by", by);
            ctx.events().publish(Topics.HOOKS_STUBBED, HooksModule.SOURCE, event);
            log.info("hooks: stub " + (armed ? "armed" : "cleared") + " on " + id + " by " + by);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("armed", Boolean.valueOf(armed));
            out.put("returnType", returnType);
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------------------ remove

    @Override
    @SuppressWarnings("unchecked")
    public List<HookStatus> remove(List<String> ids) {
        List<HookStatus> out = new ArrayList<HookStatus>();
        for (Object o : (List<Object>) removeCall(ids).get("hooks")) {
            out.add(HookStatus.fromJson((Map<String, Object>) o));
        }
        return out;
    }

    /** {@code hook.remove} (section 3.5); throws {@code HOOK_VERIFY_FAILED} when a class kept a reference to Spy. */
    Map<String, Object> removeCall(List<String> ids) {
        requireInstrumentation();
        lock.lock();
        try {
            return removeLocked(ids == null ? Collections.<String>emptyList() : ids, Long.MAX_VALUE, true, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Steps 1 to 5 of section 3.5 for the named hooks (all for an empty list), restoring until the deadline
     * ({@link System#nanoTime}); a class not restored by then stays in the ledger.
     */
    private Map<String, Object> removeLocked(List<String> ids, long deadline, boolean throwOnFailure,
                                             boolean classByClass) {
        List<String> targets = new ArrayList<String>(ids.isEmpty() ? new ArrayList<String>(hooks.keySet()) : ids);
        List<Object> statuses = new ArrayList<Object>();
        Set<HookEntry> removing = new LinkedHashSet<HookEntry>();
        for (String id : targets) {
            HookEntry hook = hooks.get(id);
            if (hook == null) {
                statuses.add(new HookStatus(id, CapabilityState.UNAVAILABLE, "not installed", null, null).toJson());
            } else {
                removing.add(hook);
            }
        }
        // 1. out of the registry: an advice that fires now finds no entry and does nothing
        Set<Site> gone = new HashSet<Site>();
        for (HookEntry hook : removing) {
            hooks.remove(hook.id);
            for (Site s : hook.sites) {
                sites.remove(s.key);
                gone.add(s);
            }
        }
        List<Ledger.Entry> restore = new ArrayList<Ledger.Entry>();
        List<Ledger.Entry> partial = new ArrayList<Ledger.Entry>();
        for (Ledger.Entry entry : ledger.entries()) {
            List<Site> keep = new ArrayList<Site>();
            boolean changed = false;
            for (Site s : entry.sites) {
                if (gone.contains(s)) {
                    changed = true;
                } else {
                    keep.add(s);
                }
            }
            if (keep.isEmpty() && (changed || ids.isEmpty())) {
                restore.add(entry);
            } else if (changed) {
                entry.sites = Collections.unmodifiableList(keep);
                partial.add(entry);
            }
        }
        if (!partial.isEmpty() && transformers != null) {
            retransform(partial, null, "remove (other hooks stay)");
        }
        // 2. restore and verification, one retransformation, the recorder of the restore registered last; in the
        // batches of section 3.2 for hook.remove, class by class for the detach (whose budget is checked between)
        List<Object> restored = new ArrayList<Object>();
        List<Object> failures = new ArrayList<Object>();
        String[] overall = {"OK"};
        Map<Ledger.Entry, String> verdicts = new IdentityHashMap<Ledger.Entry, String>();
        final Map<Class<?>, Ledger.Entry> byClass = new IdentityHashMap<Class<?>, Ledger.Entry>();
        List<Class<?>> live = new ArrayList<Class<?>>();
        for (Ledger.Entry entry : restore) {
            Class<?> type = entry.type.get();
            if (type == null) {
                Map<String, Object> line = new LinkedHashMap<String, Object>();
                line.put("class", entry.name);
                line.put("loader", entry.loaderId);
                line.put("verified", "OK");
                line.put("reason", "class unloaded: nothing to restore");
                line.put("foreignEvents", Long.valueOf(entry.foreignEvents()));
                ledger.remove(entry);
                restored.add(line);
                verdicts.put(entry, "OK");
            } else {
                byClass.put(type, entry);
                live.add(type);
            }
        }
        List<List<Class<?>>> batches = RetransformBatches.of(live, new RetransformBatches.Sizes() {
            @Override
            public long bytes(Class<?> type) {
                byte[] first = byClass.get(type).first;
                return first == null ? -1 : first.length;
            }
        }, options.maxClassBytes, classByClass ? 1 : MAX_BATCH_CLASSES);
        for (List<Class<?>> batch : batches) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            for (Class<?> type : batch) {
                Ledger.Entry entry = byClass.get(type);
                entry.sites = Collections.emptyList();
                entry.restoring = true;
            }
            Set<Class<?>> watched = Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
            watched.addAll(batch);
            Transformers.After after = new Transformers.After(watched);
            String failure = null;
            long t0 = System.nanoTime();
            inst.addTransformer(after, true);
            try {
                inst.retransformClasses(batch.toArray(new Class<?>[0]));
            } catch (Throwable t) {
                failure = "restore failed: " + t.getClass().getName() + ": " + t.getMessage();
            } finally {
                inst.removeTransformer(after);
                for (Class<?> type : batch) {
                    byClass.get(type).own++;
                }
            }
            log.info("hooks: restore retransformed " + Transformers.describe(batch) + " in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms");
            for (Class<?> type : batch) {
                verify(byClass.get(type), after.bytes(type), failure, restored, failures, verdicts, overall);
            }
        }
        // 4. statuses, capabilities, the event
        for (HookEntry hook : removing) {
            String worst = "OK";
            String why = null;
            for (Ledger.Entry entry : hook.classes) {
                String v = verdicts.get(entry);
                if (v == null && restore.contains(entry)) {
                    v = "FAILED";
                    why = "not restored within the budget";
                }
                if ("FAILED".equals(v)) {
                    worst = "FAILED";
                    why = why == null ? entry.error : why;
                } else if ("DEGRADED".equals(v) && !"FAILED".equals(worst)) {
                    worst = "DEGRADED";
                }
            }
            closeQuietly(declarations.remove(hook.id));
            for (Site s : hook.sites) {
                s.armed = null;
                if (transformers != null) {
                    transformers.forget(s);
                }
            }
            HookStatus status;
            if ("FAILED".equals(worst)) {
                hook.error = "residue after remove";
                residue.put(hook.id, hook);
                status = new HookStatus(hook.id, CapabilityState.UNAVAILABLE, "residue after remove", null,
                        hook.spec.capabilityId());
            } else {
                status = new HookStatus(hook.id, "OK".equals(worst) ? CapabilityState.OK : CapabilityState.DEGRADED,
                        "OK".equals(worst) ? "removed" : "removed; restored with a difference", Probes.STRATEGY,
                        hook.spec.capabilityId());
            }
            lastStatuses.put(hook.id, new HookStatus(hook.id, CapabilityState.UNAVAILABLE, "removed", null, null));
            statuses.add(status.toJson());
        }
        for (HookEntry hook : new ArrayList<HookEntry>(residue.values())) {
            boolean left = false;
            for (Ledger.Entry entry : hook.classes) {
                left |= ledger.entries().contains(entry);
            }
            if (!left && !removing.contains(hook)) {
                residue.remove(hook.id);
            }
        }
        if (hooks.isEmpty()) {
            catalogue = null;
        }
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        String overallVerdict = overall[0];
        List<Object> removedIds = new ArrayList<Object>();
        for (HookEntry hook : removing) {
            removedIds.add(hook.id);
        }
        List<Object> classes = new ArrayList<Object>();
        for (Object line : restored) {
            classes.add(((Map<?, ?>) line).get("class"));
        }
        event.put("ids", removedIds);
        event.put("restored", classes);
        event.put("verified", overallVerdict);
        ctx.events().publish(Topics.HOOKS_REMOVED, HooksModule.SOURCE, event);
        // 5. only when the ledger is empty: the transformers go and the slot is released
        releaseIfEmpty();
        if (!failures.isEmpty() && throwOnFailure) {
            throw new RpcException(ErrorCodes.HOOK_VERIFY_FAILED, "a restored class still references Spy",
                    data("classes", failures, "restored", restored));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("hooks", statuses);
        out.put("restored", restored);
        return out;
    }

    /** Step 3 of section 3.5 for one class: the outcome, its line, and the ledger entry dropped unless FAILED. */
    private void verify(Ledger.Entry entry, byte[] now, String failure, List<Object> restored, List<Object> failures,
                        Map<Ledger.Entry, String> verdicts, String[] overall) {
        Map<String, Object> line = new LinkedHashMap<String, Object>();
        line.put("class", entry.name);
        line.put("loader", entry.loaderId);
        byte[] first = entry.first;
        int foreign = entry.foreignEvents();
        String verified;
        String reason = null;
        if (failure != null || now == null || first == null) {
            verified = "FAILED";
            reason = failure != null ? failure : now == null ? "the restore was not seen" : "no first bytes";
        } else {
            ByteVerification v = ByteVerification.compare(first, now, SpyBoot.PACKAGE);
            if (v == ByteVerification.RESIDUE) {
                verified = "FAILED";
                reason = "a reference to Spy is left";
            } else if (foreign > 0) {
                verified = "DEGRADED";
                reason = "foreign redefinition observed: " + foreign + " events";
            } else if (v == ByteVerification.FOREIGN_CHANGE) {
                verified = "DEGRADED";
                reason = "bytes differ: another transformer is present";
            } else {
                verified = "OK";
            }
        }
        line.put("verified", verified);
        if (reason != null) {
            line.put("reason", reason);
        }
        line.put("foreignEvents", Long.valueOf(foreign));
        restored.add(line);
        verdicts.put(entry, verified);
        if ("FAILED".equals(verified)) {
            entry.failed = true;
            entry.error = reason;
            overall[0] = "FAILED";
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("class", entry.name);
            f.put("loader", entry.loaderId);
            f.put("bytesBefore", Long.valueOf(first == null ? -1 : first.length));
            f.put("bytesAfter", Long.valueOf(now == null ? -1 : now.length));
            f.put("foreignEvents", Long.valueOf(foreign));
            failures.add(f);
        } else {
            if ("DEGRADED".equals(verified) && "OK".equals(overall[0])) {
                overall[0] = "DEGRADED";
            }
            ledger.remove(entry);
        }
        log.info("hooks: restored " + entry.name + " (" + entry.loaderId + "): " + verified
                + (reason == null ? "" : " (" + reason + ")"));
    }

    private void releaseIfEmpty() {
        if (!ledger.isEmpty() || !registered) {
            return;
        }
        inst.removeTransformer(transformers.preFilter);
        inst.removeTransformer(transformers.recorder);
        registered = false;
        if (handlerInstalled) {
            SpyBridge.uninstall(handler);
            handlerInstalled = false;
        }
        log.info("hooks: the ledger is empty; the transformers are removed and the Spy slot released");
    }

    // ------------------------------------------------------------------------------------------------------ health

    @Override
    public Map<String, HookHealth> health(List<String> ids) {
        Map<String, HookHealth> out = new LinkedHashMap<String, HookHealth>();
        List<HookEntry> all = new ArrayList<HookEntry>(hooks.values());
        all.addAll(residue.values());
        Collections.sort(all, new java.util.Comparator<HookEntry>() {
            @Override
            public int compare(HookEntry a, HookEntry b) {
                return a.id.compareTo(b.id);
            }
        });
        for (HookEntry hook : all) {
            if (ids != null && !ids.isEmpty() && !ids.contains(hook.id)) {
                continue;
            }
            out.put(hook.id, healthOf(hook, true));
        }
        return out;
    }

    private HookHealth healthOf(HookEntry hook, boolean withLast) {
        long count = hook.count.get();
        long lastFired = hook.lastFiredMs;
        Long lastFiredMs = lastFired == 0 ? null : Long.valueOf(lastFired);
        Map<String, Object> last = withLast ? hook.last : null;
        String error = hook.error;
        if (error == null) {
            for (Ledger.Entry entry : hook.classes) {
                if (entry.error != null && !entry.restoring) {
                    error = entry.error;
                    break;
                }
            }
        }
        if (error != null) {
            return new HookHealth(HookState.ERROR, count, lastFiredMs, hook.armed(), error, Probes.STRATEGY, last);
        }
        for (Ledger.Entry entry : hook.classes) {
            if (entry.stale()) {
                return new HookHealth(HookState.STALE, count, lastFiredMs, hook.armed(),
                        "a hooked class's loader has been collected", Probes.STRATEGY, last);
            }
        }
        return new HookHealth(count > 0 ? HookState.FIRING : HookState.INSTALLED, count, lastFiredMs, hook.armed(),
                null, Probes.STRATEGY, last);
    }

    /** {@code hook.health}'s answer (section 4.2). */
    Map<String, Object> healthCall(List<String> ids) {
        requireInstrumentation();
        Map<String, Object> byId = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, HookHealth> e : health(ids).entrySet()) {
            byId.put(e.getKey(), e.getValue().toJson());
        }
        List<Object> stubs = new ArrayList<Object>();
        for (HookEntry hook : hooks.values()) {
            if (hook.armed()) {
                stubs.add(hook.id);
            }
        }
        Collections.sort(stubs, new java.util.Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                return String.valueOf(a).compareTo(String.valueOf(b));
            }
        });
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("hooks", byId);
        out.put("stubs", stubs);
        Class<?> boot = SpyBoot.bootSpy();
        Map<String, Object> spy = new LinkedHashMap<String, Object>();
        spy.put("onBootPath", Boolean.valueOf(boot != null));
        spy.put("version", boot == null ? null : Long.valueOf(SpyBoot.version(boot)));
        long[] counters = boot == null ? new long[4] : SpyBridge.counters();
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("enters", Long.valueOf(counters[0]));
        c.put("unhandled", Long.valueOf(counters[1]));
        c.put("failures", Long.valueOf(counters[2]));
        c.put("skips", Long.valueOf(counters[3]));
        spy.put("counters", c);
        out.put("spy", spy);
        Catalogue current = catalogue;
        if (current != null) {
            out.put("catalogue", current.toJson());
        }
        out.put("otherAgents", OtherAgents.list());
        return out;
    }

    @Override
    public Map<String, Object> catalogue() {
        Catalogue current = catalogue;
        return current == null ? new LinkedHashMap<String, Object>() : current.toJson();
    }

    /** The topic of an installed hook, or {@code hook.<id>}. */
    String topicOf(String id) {
        HookEntry hook = hooks.get(id);
        return hook != null ? hook.topic : HookSpec.TOPIC_PREFIX + id;
    }

    // ------------------------------------------------------------------------------------------------------ detach

    /**
     * The module's uninstall (section 3.5): disarm the handler first (whatever is not restored in time is inert), keep
     * it in the slot, then restore class by class until {@code hooks.detachBudgetMs} is spent; the transformers and the
     * slot go only when the ledger is empty. Never throws, never waits beyond the budget.
     */
    void detach() {
        core.disarmed = true;
        detached = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.detachBudgetMs);
        boolean locked = false;
        try {
            locked = lock.tryLock(options.detachBudgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!locked) {
                log.warn("hooks: an install or remove still runs; the detach leaves every hooked class disarmed");
                return;
            }
            for (AutoCloseable declaration : declarations.values()) {
                closeQuietly(declaration);
            }
            declarations.clear();
            if (inst != null && transformers != null) {
                try {
                    removeLocked(Collections.<String>emptyList(), deadline, false, true);
                } catch (Throwable t) {
                    log.warn("hooks: the restore at detach failed", t);
                }
            }
            List<Ledger.Entry> left = ledger.entries();
            if (!left.isEmpty()) {
                System.setProperty(ADVICE_LEFT_PROPERTY, String.valueOf(left.size()));
                for (Ledger.Entry entry : left) {
                    log.warn("hooks: " + entry.name + " (" + entry.loaderId + ") still carries a disarmed advice after"
                            + " the detach budget of " + options.detachBudgetMs + " ms: it is inert, the Spy slot"
                            + " stays held until the JVM exits, and a re-attached agent cannot hook; restart the"
                            + " target to drop it");
                }
            }
        } finally {
            if (locked) {
                lock.unlock();
            }
            core.dropThreadState();
        }
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private RpcException unavailable(String reason) {
        return new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "hook.engine is UNAVAILABLE: " + reason,
                data("capability", HooksModule.ENGINE, "reason", reason));
    }

    static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String string(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // the core's handles never throw
        }
    }

    // For the tests.
    boolean handlerInstalled() {
        return handlerInstalled;
    }

    Transformers transformers() {
        return transformers;
    }
}
