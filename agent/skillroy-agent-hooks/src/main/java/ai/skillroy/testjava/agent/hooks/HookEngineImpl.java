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
import ai.skillroy.testjava.agent.api.spi.hook.ClassDeny;
import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.hook.ClassPattern;
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
import java.util.Collection;
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
 * <p><b>The class deny</b> (phase8-contracts.md sections 6.4 to 6.6): item 2b of every probe asks the agent option
 * {@code hooks.deny}, then the catalogue deny-list this engine holds, then every other registered {@link ClassDeny}
 * in registration order; the first refusal wins. The engine is itself the catalogue's {@link ClassDeny} in the shared
 * registry (registered by {@link HooksModule} beside the {@link HookEngine}), so that the Byteman module asks it and
 * tells it apart from a domain module's by {@code instanceof HookEngine}. The held list is add-only for the engine's
 * life (D564): merged under the lock before the conflict check, kept across {@code hook.remove {}} and
 * {@code replace}, at most {@link #MAX_HELD_DENY} patterns, echoed in every {@code hook.install} answer.
 *
 * <p>Thread-safe: an install, a remove, a stub and the detach are serialised by one lock (the detach waits for it at
 * most its budget); probes and health read concurrent state. Every method runs on an agent thread.
 */
final class HookEngineImpl implements HookEngine, ClassDeny {

    /**
     * Set by a detach that ran out of budget to the number of classes left carrying an inert advice, so that a later
     * agent's probe can say how many (section 3.2: the slot stays held until the JVM exits).
     */
    static final String ADVICE_LEFT_PROPERTY = "skillroy.hooks.adviceLeft";
    static final int MAX_BATCH_CLASSES = 8;
    static final int MAX_SPECS = 64;
    /** The health reason of a hook refused at install whose advice a class kept (its restore left it FAILED). */
    static final String RESIDUE_AFTER_REFUSAL = "residue after a refused install";
    /** The health reason of a removed hook a class may still carry ({@code FAILED} restore, or the partial path). */
    static final String RESIDUE_AFTER_REMOVE = "residue after remove";
    /** The most catalogue deny patterns an engine holds (phase8-contracts.md section 6.6). */
    static final int MAX_HELD_DENY = 256;
    /** The most delivering catalogue ids {@code hook.health.deny.from} lists. */
    static final int MAX_DENY_FROM = 16;
    /** The longest reason of a module's {@link ClassDeny}. */
    static final int MAX_DENY_REASON = 200;
    static final String DENIED_BY_OPTION = "denied by the agent option " + HookOptions.DENY + ": ";
    static final String DENIED_BY_CATALOGUE = "denied by the catalogue ";
    static final String DENIED_BY_MODULE = "denied by a module: ";

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
    /**
     * Set by a detach that could not take the engine's lock within its budget (Phase 6 security fix, pass 2,
     * P2-L2-F6): the install or remove that held it reports the classes left behind again when it ends.
     */
    private volatile boolean detachGaveUp;
    /** The held catalogue deny-list: an immutable snapshot, replaced under the lock, read on any agent thread. */
    private volatile HeldDeny held = HeldDeny.EMPTY;
    /** The option's patterns, compiled once. */
    private final List<ClassPattern> optionDeny;
    /** The classes of the module {@link ClassDeny}s that threw, each logged once. */
    private final Set<String> denyThrows = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    HookEngineImpl(AgentContext ctx, HookOptions options) {
        this.ctx = ctx;
        this.inst = ctx.instrumentation();
        this.options = options;
        this.log = ctx.log();
        this.core = new HandlerCore(sites, ctx.events(), new Capture(ctx, options.captureMaxBytes));
        List<ClassPattern> compiled = new ArrayList<ClassPattern>();
        for (String p : options.deny) {
            compiled.add(ClassPattern.compile(p));
        }
        this.optionDeny = Collections.unmodifiableList(compiled);
        this.probes = inst == null ? null : new Probes(inst, ctx.hostClassLoader(), options, ledger,
                new Probes.Deny() {
                    @Override
                    public String refuse(String className) {
                        return deniedBy(className);
                    }
                });
    }

    /**
     * The held catalogue deny-list (phase8-contracts.md section 6.6): the patterns in the order first delivered, each
     * with the catalogue that delivered it first. Immutable.
     */
    static final class HeldDeny {
        static final HeldDeny EMPTY = new HeldDeny(new LinkedHashMap<String, String>());

        /** Pattern to the id of the catalogue that delivered it first, in delivery order. */
        final Map<String, String> from;
        final List<ClassPattern> compiled;

        HeldDeny(LinkedHashMap<String, String> from) {
            this.from = Collections.unmodifiableMap(from);
            List<ClassPattern> c = new ArrayList<ClassPattern>();
            for (String p : from.keySet()) {
                c.add(ClassPattern.compile(p));
            }
            this.compiled = Collections.unmodifiableList(c);
        }

        /** The union with a call's patterns: a new snapshot, or this one when nothing is new. */
        HeldDeny with(List<String> patterns, String catalogueId) {
            LinkedHashMap<String, String> union = new LinkedHashMap<String, String>(from);
            for (String p : patterns) {
                if (!union.containsKey(p)) {
                    union.put(p, catalogueId);
                }
            }
            return union.size() == from.size() ? this : new HeldDeny(union);
        }

        /** How many distinct patterns the union with {@code patterns} holds. */
        int unionSize(List<String> patterns) {
            Set<String> union = new HashSet<String>(from.keySet());
            union.addAll(patterns);
            return union.size();
        }

        List<String> sorted() {
            List<String> out = new ArrayList<String>(from.keySet());
            Collections.sort(out);
            return out;
        }

        /** The delivering catalogue ids, distinct, in the order of first delivery, at most {@link #MAX_DENY_FROM}. */
        List<String> catalogues() {
            List<String> out = new ArrayList<String>(new LinkedHashSet<String>(from.values()));
            return out.size() > MAX_DENY_FROM ? new ArrayList<String>(out.subList(0, MAX_DENY_FROM)) : out;
        }
    }

    // ------------------------------------------------------------------------------------------------- the deny

    /**
     * The catalogue's source of the class deny (phase8-contracts.md section 6.5 item 2): {@code denied by the
     * catalogue <id>: <pattern>} for the first held pattern that matches, or null. Any agent thread.
     */
    @Override
    public String refuse(String className) {
        HeldDeny h = held;
        for (ClassPattern p : h.compiled) {
            if (p.matches(className)) {
                return DENIED_BY_CATALOGUE + h.from.get(p.pattern()) + ": " + p.pattern();
            }
        }
        return null;
    }

    /** The catalogue deny patterns held, sorted (phase8-contracts.md section 6.6). */
    @Override
    public List<String> deny() {
        return Collections.unmodifiableList(held.sorted());
    }

    /**
     * Item 2b of section 6.4: the option's patterns, then the catalogue's held list, then every other registered
     * {@link ClassDeny} in registration order; the first refusal wins, a throw refuses. Null to allow.
     */
    String deniedBy(String className) {
        for (ClassPattern p : optionDeny) {
            if (p.matches(className)) {
                return DENIED_BY_OPTION + p.pattern();
            }
        }
        String catalogue = refuse(className);
        if (catalogue != null) {
            return catalogue;
        }
        for (ClassDeny d : ctx.spi().all(ClassDeny.class)) {
            if (d == this || d instanceof HookEngine) {
                continue;
            }
            String reason;
            try {
                reason = d.refuse(className);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) {
                String type = d.getClass().getName();
                if (denyThrows.add(type)) {
                    log.warn("hooks: the deny policy " + type + " threw " + t.getClass().getName()
                            + "; every class it is asked about is refused");
                }
                return DENIED_BY_MODULE + "the deny policy " + type + " threw " + t.getClass().getName();
            }
            if (reason != null) {
                return DENIED_BY_MODULE + (reason.length() > MAX_DENY_REASON ? reason.substring(0, MAX_DENY_REASON)
                        : reason);
            }
        }
        return null;
    }

    /** The number of registered {@link ClassDeny}s other than a hook engine's (the catalogue's). */
    int moduleDenies() {
        int n = 0;
        for (ClassDeny d : ctx.spi().all(ClassDeny.class)) {
            if (d != this && !(d instanceof HookEngine)) {
                n++;
            }
        }
        return n;
    }

    /** {@code hook.health.deny} (section 6.6): always present on a Phase 8 engine. */
    Map<String, Object> denyInfo() {
        HeldDeny h = held;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("catalogue", new ArrayList<Object>(h.sorted()));
        out.put("from", new ArrayList<Object>(h.catalogues()));
        out.put("option", new ArrayList<Object>(options.deny));
        out.put("modules", Long.valueOf(moduleDenies()));
        return out;
    }

    /**
     * {@code catalogue.deny} of a {@code hook.install} (section 6.6): absent, or a list of at most 64 class patterns;
     * checked before the lock.
     */
    static List<String> denyOf(Map<String, Object> info) {
        if (!info.containsKey("deny") || info.get("deny") == null) {
            return Collections.emptyList();
        }
        Object raw = info.get("deny");
        if (!(raw instanceof List)) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "catalogue.deny must be a list of class patterns",
                    data("param", "catalogue.deny"));
        }
        List<?> list = (List<?>) raw;
        if (list.size() > ClassPattern.MAX_PER_SOURCE) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "at most " + ClassPattern.MAX_PER_SOURCE
                    + " deny patterns per catalogue", data("param", "catalogue.deny"));
        }
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof String)) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "deny pattern " + i + ": not a string",
                        data("param", "catalogue.deny[" + i + "]"));
            }
            String violation = ClassPattern.violation((String) item);
            if (violation != null) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, "deny pattern " + i + ": " + violation,
                        data("param", "catalogue.deny[" + i + "]"));
            }
            if (!out.contains(item)) {
                out.add((String) item);
            }
        }
        return out;
    }

    /** Under the lock: the call's patterns merged into the held list (add-only; at most {@link #MAX_HELD_DENY}). */
    private void mergeDeny(List<String> patterns, String catalogueId) {
        if (patterns.isEmpty()) {
            return;
        }
        HeldDeny h = held;
        if (h.unionSize(patterns) > MAX_HELD_DENY) {
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "the engine holds " + MAX_HELD_DENY
                    + " deny patterns; restart the target", data("param", "catalogue.deny"));
        }
        HeldDeny next = h.with(patterns, catalogueId);
        if (next != h) {
            held = next;
            log.info("hooks: catalogue " + catalogueId + " delivered " + patterns.size() + " deny patterns; the engine"
                    + " holds " + next.from.size() + " (add-only until the agent restarts)");
        }
    }

    /** {@code catalogue.deny} of an install answer: the held patterns, sorted; an empty list when none is held. */
    private List<Object> denyEcho() {
        return new ArrayList<Object>(held.sorted());
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
        List<String> deny = denyOf(info);
        lock.lock();
        try {
            if (detached) {
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "the hooks module is uninstalling",
                        data("capability", HooksModule.ENGINE));
            }
            // Section 6.6: merged before the conflict check, so that the call's own specs are checked against it and
            // a catalogue refused with HOOK_CONFLICT has still tightened the engine (the safe direction).
            mergeDeny(deny, catalogueId);
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
                // Phase 6 security fix, pass 2, P2-L2-F3: the implicit remove's restore and verdict travel in the
                // answer, so that a FAILED class (a reference to Spy left) is not answered as a plain success.
                String[] verdict = {"OK"};
                Map<String, Object> removed = removeLocked(Collections.<String>emptyList(), Long.MAX_VALUE, false,
                        false, verdict);
                Map<String, Object> replaced = new LinkedHashMap<String, Object>();
                replaced.put("restored", removed.get("restored"));
                replaced.put("verified", verdict[0]);
                Map<String, Object> result = installLocked(specs, catalogueId, catalogueHash, info);
                result.put("replaced", replaced);
                return result;
            }
            return installLocked(specs, catalogueId, catalogueHash, info);
        } finally {
            reportIfDetachGaveUp();
            lock.unlock();
        }
    }

    private Map<String, Object> alreadyInstalled(Catalogue current) {
        Map<String, Object> cat = current.toJson();
        cat.put("alreadyInstalled", Boolean.TRUE);
        cat.put("deny", denyEcho());
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
            if (o.passes() && residue.containsKey(spec.id())) {
                // A class may still carry this id's advice, whose site key a new site of the id would answer (L2-S3).
                o.refuse("a residue of hook " + spec.id() + " is left: hook.remove {} retries its classes",
                        o.probe.spyVisibility());
            }
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
            String refusal = SpyBoot.checkJar(jar, options.spyJarNamed);
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
        // (6) the transformer, the registry and the retransformation, for the specs still passing; none once a detach
        // has begun (Phase 6 security fix, pass 2, P2-L2-F6: a detach that waits for this call, or gave up waiting,
        // must not find classes advised after it started)
        if (detached) {
            releaseIfEmpty();
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "the hooks module is uninstalling",
                    data("capability", HooksModule.ENGINE));
        }
        List<Object> touched = new ArrayList<Object>();
        List<HookEntry> installed = passing(outcomes) ? retransformAndDecide(outcomes, touched)
                : new ArrayList<HookEntry>();
        // (7) the capabilities, the catalogue and the event; not for a module whose detach began meanwhile (its
        // capabilities were withdrawn by the core's rollback, and its handler is disarmed)
        boolean detaching = detached;
        for (HookEntry hook : installed) {
            if (!detaching) {
                declare(hook);
            }
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
        if (!detaching) {
            ctx.events().publish(Topics.HOOKS_INSTALLED, HooksModule.SOURCE, event);
        }
        // Section 3.5 step 5 at the end of an install as well (L2-F7): an install that left no class carrying an
        // advice (every spec refused at step 5 or 6, each refused hook's classes restored and verified) gives back
        // the slot and the transformers; a class whose restore failed keeps its entry and so the slot (D117).
        releaseIfEmpty();
        return result;
    }

    private static boolean passing(List<Probes.Outcome> outcomes) {
        for (Probes.Outcome o : outcomes) {
            if (o.passes()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Step 6 of section 3.2 for the specs that passed step 5: the transformers registered, the sites added, the classes
     * retransformed, and per hook the decision; a hook with a class that failed is refused and taken out of the classes
     * that did take its advice ({@link #takeOut}) before the call answers. Returns the hooks installed.
     */
    private List<HookEntry> retransformAndDecide(List<Probes.Outcome> outcomes, List<Object> touched) {
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
                            m.methods, m.codeSource);
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
        Transformers.Round round = retransform(new ArrayList<Ledger.Entry>(added.keySet()), touched, "install");
        List<HookEntry> installed = new ArrayList<HookEntry>();
        Map<HookEntry, String> refused = new LinkedHashMap<HookEntry, String>();
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
                refused.put(hook, failure);
                dropSites(hook);
            }
        }
        Map<HookEntry, String> left = takeOut(refused.keySet(), created, round);
        for (Map.Entry<HookEntry, String> e : refused.entrySet()) {
            HookEntry hook = e.getKey();
            String why = left.get(hook);
            if (why != null) {
                hook.error = RESIDUE_AFTER_REFUSAL;
                residue.put(hook.id, hook);
            }
            Probes.Outcome o = installing.get(hook);
            o.refuse(why == null ? e.getValue() : e.getValue() + "; " + why, o.probe.spyVisibility());
        }
        return installed;
    }

    /**
     * A refused hook taken out of every class of the call that took its advice (D178: a hook is installed on all its
     * classes or on none; D117; the Phase 6 review's L2-S3), its sites already dropped. Per class of the refused hooks:
     * <ul>
     *   <li>a class whose batch threw kept its earlier bytes and needs nothing;</li>
     *   <li>a class that still holds other hooks' sites is retransformed once without the refused ones (as
     *       {@code hook.remove} does for a class that keeps other hooks);</li>
     *   <li>a class with no site left that took the advice is restored and verified as {@code hook.remove} restores
     *       (section 3.5 steps 2 and 3): {@code OK} or {@code DEGRADED} drops its entry, {@code FAILED} keeps it and
     *       so the slot;</li>
     *   <li>an entry this call created for a class that never took the advice is dropped.</li>
     * </ul>
     * Returns, for each refused hook one of whose classes may still carry its advice, why; that class keeps its ledger
     * entry, and {@code hook.remove {}} retries it.
     */
    private Map<HookEntry, String> takeOut(Collection<HookEntry> refused, Set<Ledger.Entry> created,
                                           Transformers.Round round) {
        Map<HookEntry, String> left = new LinkedHashMap<HookEntry, String>();
        if (refused.isEmpty()) {
            return left;
        }
        Set<Ledger.Entry> seen = Collections.newSetFromMap(new IdentityHashMap<Ledger.Entry, Boolean>());
        List<Ledger.Entry> partial = new ArrayList<Ledger.Entry>();
        List<Ledger.Entry> restore = new ArrayList<Ledger.Entry>();
        for (HookEntry hook : refused) {
            for (Ledger.Entry entry : hook.classes) {
                if (!seen.add(entry)) {
                    continue;
                }
                String key = Transformers.Round.key(entry.name, entry.loader.get());
                boolean kept = round.kept.contains(key);
                if (!entry.sites.isEmpty()) {
                    if (!kept) {
                        partial.add(entry);
                    }
                } else if (round.transformed.contains(key) && !kept) {
                    restore.add(entry);
                } else if (created.contains(entry)) {
                    ledger.remove(entry);
                }
            }
        }
        Map<Ledger.Entry, String> dirty = new IdentityHashMap<Ledger.Entry, String>();
        if (!partial.isEmpty()) {
            Transformers.Round again = retransform(partial, null, "install (a refused hook taken out)");
            for (Ledger.Entry entry : partial) {
                String key = Transformers.Round.key(entry.name, entry.loader.get());
                if (again.kept.contains(key)) {
                    dirty.put(entry, entry.name + " (" + entry.loaderId + ") kept its advice: "
                            + again.errors.get(key));
                }
            }
        }
        if (!restore.isEmpty()) {
            Map<Ledger.Entry, String> verdicts = new IdentityHashMap<Ledger.Entry, String>();
            restore(restore, Long.MAX_VALUE, false, new ArrayList<Object>(), new ArrayList<Object>(), verdicts,
                    new String[] {"OK"});
            for (Ledger.Entry entry : restore) {
                if ("FAILED".equals(verdicts.get(entry)) || verdicts.get(entry) == null) {
                    dirty.put(entry, "the restore of " + entry.name + " (" + entry.loaderId + ") failed: "
                            + entry.error);
                }
            }
        }
        for (HookEntry hook : refused) {
            for (Ledger.Entry entry : hook.classes) {
                String why = dirty.get(entry);
                if (why != null) {
                    left.put(hook, why + " (hook.remove {} retries it)");
                    break;
                }
            }
        }
        return left;
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
                    ? new HookStatus(o.spec.id(), o.probe.state(), wire(o.probe.reason()), Probes.STRATEGY,
                    o.spec.capabilityId())
                    : new HookStatus(o.spec.id(), CapabilityState.UNAVAILABLE, wire(o.probe.reason()), null, null);
            lastStatuses.put(o.spec.id(), status);
            statuses.add(status.toJson());
        }
        Map<String, Object> cat = new LinkedHashMap<String, Object>();
        cat.put("id", catalogueId);
        cat.put("hash", catalogueHash);
        cat.put("installedAt", installed == null ? null : Long.valueOf(installed.installedAt));
        cat.put("alreadyInstalled", Boolean.FALSE);
        cat.put("deny", denyEcho());
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
                transformers.own.set(Thread.currentThread());
                try {
                    inst.retransformClasses(batch.toArray(new Class<?>[0]));
                } catch (Throwable t) {
                    for (Class<?> c : batch) {
                        String key = Transformers.Round.key(c.getName(), c.getClassLoader());
                        round.errors.put(key, t.getClass().getName() + ": " + t.getMessage());
                        round.kept.add(key);
                    }
                } finally {
                    transformers.own.set(null);
                }
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                for (Class<?> c : batch) {
                    Ledger.Entry e = byClass.get(c);
                    if (touched != null) {
                        Map<String, Object> line = new LinkedHashMap<String, Object>();
                        line.put("class", c.getName());
                        line.put("loader", e.loaderId);
                        line.put("codeSource", e.codeSource);
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

    /**
     * Declares {@code hook.<id>} with its health probe and sets it at once to the probe's first answer (section 4.6,
     * as built in Phase 7, FX, D449). The core probes a declaration made after the target is ready later, on the agent
     * worker it posts to, and reads it {@code UNAVAILABLE("not probed yet: ...")} until then; without the first answer
     * a matrix read right after {@code hook.install}'s answer could catch the hook pending. The probe stays declared for
     * {@code agent.capabilities {reprobe}}, and the worker's later run answers the same. Called under {@link #lock}.
     */
    private void declare(final HookEntry hook) {
        AutoCloseable previous = declarations.remove(hook.id);
        closeQuietly(previous);
        String capability = hook.spec.capabilityId();
        declarations.put(hook.id, ctx.capabilities().declare(capability, new Probe() {
            @Override
            public ProbeResult run(AgentContext context) {
                return capabilityState(hook);
            }
        }));
        ProbeResult first;
        try {
            first = capabilityState(hook);
        } catch (RuntimeException e) {
            return; // the worker's probe answers instead, as before
        }
        if (!detached) {
            ctx.capabilities().set(capability, first.state(), first.reason(), first.strategy());
        }
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
            return removeLocked(ids == null ? Collections.<String>emptyList() : ids, Long.MAX_VALUE, true, false,
                    new String[] {"OK"});
        } finally {
            reportIfDetachGaveUp();
            lock.unlock();
        }
    }

    /**
     * Steps 1 to 5 of section 3.5 for the named hooks (all for an empty list), restoring until the deadline
     * ({@link System#nanoTime}); a class not restored by then stays in the ledger. {@code verdict[0]} receives the
     * overall outcome of the restored classes ({@code OK}, {@code DEGRADED} or {@code FAILED}).
     */
    private Map<String, Object> removeLocked(List<String> ids, long deadline, boolean throwOnFailure,
                                             boolean classByClass, String[] verdict) {
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
        // A class that keeps other hooks is retransformed once without the removed ones. When that does not go
        // through (its batch threw, so the JVM kept its bytes and the removed hook's advice with them; or the hook
        // transformer failed on it), the removed hooks with a site there are residues (Phase 6 security fix, pass 2,
        // P2-L2-S1, P2-L2-F4): a new site of the id, on any loader's copy, would answer the advice left behind.
        Map<Ledger.Entry, String> kept = new IdentityHashMap<Ledger.Entry, String>();
        if (!partial.isEmpty() && transformers != null) {
            Transformers.Round round = retransform(partial, null, "remove (other hooks stay)");
            for (Ledger.Entry entry : partial) {
                if (entry.type.get() == null) {
                    continue;
                }
                String key = Transformers.Round.key(entry.name, entry.loader.get());
                String error = round.errors.get(key);
                if (error == null && !round.transformed.contains(key)) {
                    error = entry.error != null ? entry.error : "the class was not retransformed";
                }
                if (round.kept.contains(key)) {
                    kept.put(entry, error == null ? "its retransformation threw" : error);
                } else if (error != null) {
                    // The JVM installed bytes the hook transformer did not advise: the hooks that stay lost theirs.
                    entry.error = error;
                    kept.put(entry, error);
                }
            }
        }
        // 2. restore and verification, one retransformation, the recorder of the restore registered last; in the
        // batches of section 3.2 for hook.remove, class by class for the detach (whose budget is checked between)
        List<Object> restored = new ArrayList<Object>();
        List<Object> failures = new ArrayList<Object>();
        String[] overall = {"OK"};
        Map<Ledger.Entry, String> verdicts = new IdentityHashMap<Ledger.Entry, String>();
        restore(restore, deadline, classByClass, restored, failures, verdicts, overall);
        // 4. statuses, capabilities, the event
        removed(removing, restore, verdicts, kept, statuses);
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        String overallVerdict = overall[0];
        verdict[0] = overallVerdict;
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

    /**
     * Steps 2 and 3 of section 3.5 for these entries (none holds a site any more): the restore and its verification,
     * one retransformation per batch of section 3.2 ({@code classByClass}: one class per batch, the detach's) with the
     * {@code After} recorder registered last, until the deadline ({@link System#nanoTime}); a class not reached keeps
     * its entry and gets no verdict. Shared by {@code hook.remove}, the detach and an install's refused hook.
     */
    private void restore(List<Ledger.Entry> restore, long deadline, boolean classByClass, List<Object> restored,
                         List<Object> failures, Map<Ledger.Entry, String> verdicts, String[] overall) {
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
            Transformers mine = transformers;
            if (mine != null) {
                mine.own.set(Thread.currentThread());
            }
            try {
                inst.retransformClasses(batch.toArray(new Class<?>[0]));
            } catch (Throwable t) {
                failure = "restore failed: " + t.getClass().getName() + ": " + t.getMessage();
            } finally {
                if (mine != null) {
                    mine.own.set(null);
                }
                inst.removeTransformer(after);
            }
            log.info("hooks: restore retransformed " + Transformers.describe(batch) + " in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms");
            for (Class<?> type : batch) {
                verify(byClass.get(type), after.bytes(type), failure, restored, failures, verdicts, overall);
            }
        }
    }

    /**
     * Step 4 of section 3.5 for the hooks removed: their statuses, their capabilities withdrawn, the residues. A hook
     * with a site in a class of {@code kept} (the retransformation without it did not go through) is a residue as a
     * {@code FAILED} restore makes one (Phase 6 security fix, pass 2, P2-L2-S1).
     */
    private void removed(Set<HookEntry> removing, List<Ledger.Entry> restore, Map<Ledger.Entry, String> verdicts,
                         Map<Ledger.Entry, String> kept, List<Object> statuses) {
        for (HookEntry hook : removing) {
            String worst = "OK";
            String why = null;
            String left = null;
            for (Ledger.Entry entry : hook.classes) {
                if (left == null && kept.containsKey(entry)) {
                    left = entry.name + " (" + entry.loaderId + "): " + kept.get(entry);
                }
            }
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
                hook.error = RESIDUE_AFTER_REMOVE;
                residue.put(hook.id, hook);
                status = new HookStatus(hook.id, CapabilityState.UNAVAILABLE, RESIDUE_AFTER_REMOVE, null,
                        hook.spec.capabilityId());
            } else if (left != null) {
                hook.error = RESIDUE_AFTER_REMOVE + ": the retransformation without it failed: " + left
                        + " (hook.remove {} retries it)";
                residue.put(hook.id, hook);
                status = new HookStatus(hook.id, CapabilityState.UNAVAILABLE, wire(hook.error), null,
                        hook.spec.capabilityId());
                log.warn("hooks: " + hook.id + " was removed from the registry, but " + wire(left) + "; the class"
                        + " may still carry its inert advice, and the id is refused until hook.remove {} restores it");
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
            line.put("reason", wire(reason));
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

    /**
     * Section 3.5 step 5: only when the ledger is empty (no class carries an advice the engine put there), the
     * transformers are removed and the slot released. Called at the end of every remove and of every install (an
     * install appends and fills the slot at steps 3 and 4, before steps 5 and 6 can refuse everything; L2-F7).
     */
    private void releaseIfEmpty() {
        if (!ledger.isEmpty() || (!registered && !handlerInstalled)) {
            return;
        }
        if (registered) {
            inst.removeTransformer(transformers.preFilter);
            inst.removeTransformer(transformers.recorder);
            registered = false;
        }
        if (handlerInstalled) {
            SpyBridge.uninstall(handler);
            handlerInstalled = false;
        }
        log.info("hooks: the ledger is empty; the transformers are removed and the Spy slot released");
    }

    /**
     * A failure's text as it leaves the engine, in an install or remove answer, a health reason or a capability's
     * reason (section 3.6: "the exception's class and message, redacted"; L1-F2): the core's {@code redactText}, cut
     * as a thrown message is (section 4.3).
     */
    private String wire(String text) {
        if (text == null) {
            return null;
        }
        String cut = text.length() > Capture.MESSAGE_CUT * 4 ? text.substring(0, Capture.MESSAGE_CUT * 4) : text;
        String redacted = ctx.redactText(cut);
        return redacted != null && redacted.length() > Capture.MESSAGE_CUT
                ? redacted.substring(0, Capture.MESSAGE_CUT) : redacted;
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
            return new HookHealth(HookState.ERROR, count, lastFiredMs, hook.armed(), wire(error), Probes.STRATEGY,
                    last);
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
        Map<Ledger.Entry, String> deniedNow = new IdentityHashMap<Ledger.Entry, String>();
        for (Ledger.Entry entry : ledger.entries()) {
            String reason = deniedBy(entry.name);
            if (reason != null) {
                deniedNow.put(entry, wire(reason));
            }
        }
        for (Map.Entry<String, HookHealth> e : health(ids).entrySet()) {
            Map<String, Object> json = e.getValue().toJson();
            HookEntry hook = hooks.get(e.getKey());
            if (hook == null) {
                hook = residue.get(e.getKey());
            }
            if (hook != null) {
                for (Ledger.Entry entry : hook.classes) {
                    if (deniedNow.containsKey(entry)) {
                        // Section 6.6 (D568): a hook installed before its class was denied is flagged, not removed.
                        json.put("deniedNow", Boolean.TRUE);
                        break;
                    }
                }
            }
            byId.put(e.getKey(), json);
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
        out.put("classes", classesInfo(deniedNow));
        out.put("deny", denyInfo());
        return out;
    }

    /**
     * {@code hook.health.classes} (phase8-contracts.md section 6.4, D559): one line per ledger class, sorted by class
     * then loader, at most {@code hooks.maxClasses}: {@code {class, loader, codeSource, bytes, methods, hooks: [id...],
     * deniedNow?}}.
     */
    private List<Object> classesInfo(Map<Ledger.Entry, String> deniedNow) {
        List<Ledger.Entry> entries = ledger.entries();
        Collections.sort(entries, new java.util.Comparator<Ledger.Entry>() {
            @Override
            public int compare(Ledger.Entry a, Ledger.Entry b) {
                int byName = a.name.compareTo(b.name);
                return byName != 0 ? byName : a.loaderId.compareTo(b.loaderId);
            }
        });
        List<HookEntry> all = new ArrayList<HookEntry>(hooks.values());
        all.addAll(residue.values());
        List<Object> out = new ArrayList<Object>();
        for (Ledger.Entry entry : entries) {
            if (out.size() >= options.maxClasses) {
                break;
            }
            List<String> ids = new ArrayList<String>();
            for (HookEntry hook : all) {
                for (Ledger.Entry c : hook.classes) {
                    if (c == entry && !ids.contains(hook.id)) {
                        ids.add(hook.id);
                    }
                }
            }
            Collections.sort(ids);
            Map<String, Object> line = new LinkedHashMap<String, Object>();
            line.put("class", entry.name);
            line.put("loader", entry.loaderId);
            line.put("codeSource", entry.codeSource);
            byte[] first = entry.first;
            line.put("bytes", Long.valueOf(first != null ? first.length : entry.sizeBytes));
            line.put("methods", Long.valueOf(entry.methods));
            line.put("hooks", new ArrayList<Object>(ids));
            String denied = deniedNow.get(entry);
            if (denied != null) {
                line.put("deniedNow", denied);
            }
            out.add(line);
        }
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
                // Phase 6 security fix, pass 2, P2-L2-F6: the classes known now (a lock-free snapshot, which may
                // over-report a class the running call then takes out: the safe direction), each at WARN with the
                // remedy, and D177's count; the running call reports again when it ends.
                detachGaveUp = true;
                reportLeftBehind(ledger.entries());
                return;
            }
            for (AutoCloseable declaration : declarations.values()) {
                closeQuietly(declaration);
            }
            declarations.clear();
            if (inst != null && transformers != null) {
                try {
                    removeLocked(Collections.<String>emptyList(), deadline, false, true, new String[] {"OK"});
                } catch (Throwable t) {
                    log.warn("hooks: the restore at detach failed", t);
                }
            }
            reportLeftBehind(ledger.entries());
        } finally {
            if (locked) {
                lock.unlock();
            }
            core.dropThreadState();
        }
    }

    /**
     * Section 3.5's report of the classes a detach leaves carrying a disarmed advice: each at WARN by name with the
     * remedy, and their number in {@link #ADVICE_LEFT_PROPERTY} (D177) for a later agent's probe.
     */
    private void reportLeftBehind(List<Ledger.Entry> left) {
        if (left.isEmpty()) {
            return;
        }
        System.setProperty(ADVICE_LEFT_PROPERTY, String.valueOf(left.size()));
        for (Ledger.Entry entry : left) {
            log.warn("hooks: " + entry.name + " (" + entry.loaderId + ") still carries a disarmed advice after"
                    + " the detach budget of " + options.detachBudgetMs + " ms: it is inert, the Spy slot"
                    + " stays held until the JVM exits, and a re-attached agent cannot hook; restart the"
                    + " target to drop it");
        }
    }

    /**
     * At the end of an install or remove that held the lock past a detach's budget (P2-L2-F6): the classes left now,
     * reported again, so that the WARN lines and the count reflect what the call finished with.
     */
    private void reportIfDetachGaveUp() {
        if (!detachGaveUp) {
            return;
        }
        List<Ledger.Entry> left = ledger.entries();
        if (left.isEmpty()) {
            // The call took every class out after all (and released the slot): the detach's snapshot over-reported.
            System.clearProperty(ADVICE_LEFT_PROPERTY);
            log.info("hooks: the call the detach could not wait for left no class carrying an advice");
            return;
        }
        reportLeftBehind(left);
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

    boolean registered() {
        lock.lock();
        try {
            return registered;
        } finally {
            lock.unlock();
        }
    }

    Transformers transformers() {
        return transformers;
    }

    /** The probe, whose code-source reader is the tests' seam (phase8-contracts.md section 13.1 row 24). */
    Probes probes() {
        return probes;
    }
}
