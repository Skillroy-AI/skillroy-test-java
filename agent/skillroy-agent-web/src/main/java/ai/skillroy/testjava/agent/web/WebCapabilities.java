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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The module's capabilities (phase4-contracts.md section 4.4) and the probe cycle behind them.
 *
 * <p><b>Install.</b> Every capability is set UNAVAILABLE({@value #NO_TECHNOLOGY}) ({@code web.adapter.jcef},
 * {@code web.adapter.jxbrowser} and the generic ones), except {@code web.hittest} and {@code web.pointer}, which are
 * always UNAVAILABLE({@value #RESERVED}); nothing is probed and no AWT or vendor class is touched.
 *
 * <p><b>The cycle.</b> One computation serves every capability: each adapter (the two built-in ones first, then every
 * {@code WebEngineAdapter} registered through {@code SpiRegistry}, in registration order; an id that is not lower-case
 * letters and digits, or that an earlier adapter has, is skipped with a log line) is asked {@code probe(hostLoader)};
 * those whose probe is not UNAVAILABLE are the present adapters, and the index is rebuilt over them; then:
 * <ul>
 *   <li>{@code web.adapter.<id>}: the adapter's probe result (OK with the product and line as strategy);</li>
 *   <li>{@code web.browsers}: UNAVAILABLE when no adapter is present; else the worst {@code enumerationState()} of the
 *       adapters that found a browser (of every present adapter when none found one), never worse than DEGRADED
 *       (a present adapter's UNAVAILABLE enumeration counts as DEGRADED with its reason, so that {@code web.browsers}
 *       itself stays callable);</li>
 *   <li>{@code web.evaluate}, {@code web.events}, {@code web.resize}, {@code web.bitmap}: OK (strategy: the adapters
 *       that provide it) when at least one open indexed browser has {@code EVALUATE}, {@code EVENTS_LOAD},
 *       {@code RESIZE}, {@code BITMAP}; else UNAVAILABLE with {@value #NO_BROWSER} or "no indexed browser has
 *       &lt;FLAG&gt;" when adapters are present, and with the first informative adapter reason (one that is not
 *       "not present" or "not loaded") or {@value #NO_TECHNOLOGY} when none is;</li>
 *   <li>{@code web.initScript}: OK with strategy {@code DOCUMENT_START} when a browser has
 *       {@code INIT_SCRIPT_DOCUMENT_START}, DEGRADED({@value #AFTER_LOAD}) when browsers can only take scripts late,
 *       else as above;</li>
 *   <li>{@code web.cdp}: OK (strategy {@code host:port} of the first endpoint) when a present adapter reports a
 *       configured endpoint, else UNAVAILABLE({@value #NO_ENDPOINT}).</li>
 * </ul>
 * {@code onTargetReady} runs a cycle and then {@code declare}s every capability with a probe; the core's
 * {@code probeAll} (after {@code onTargetReady}, and on {@code agent.capabilities {reprobe: true}}) runs the probes,
 * and the first probe of each round computes a new cycle that the others of the round reuse (a probe whose id was
 * already served by the current cycle, or a cycle older than {@value #MAX_AGE_MS} ms, starts a new one). The module's
 * own re-probe after a {@code window.opened} event ({@link #reprobeChanged()}, on a worker) probes again only the
 * adapters whose technology may have arrived since the last cycle (a built-in adapter whose technology is present
 * but not loaded by the application yet, a registered adapter whose probe was UNAVAILABLE, an adapter registered or
 * withdrawn since), with nothing else (no index rebuild, no EDT read), and runs a full cycle, whose results it
 * {@code set}s, only when one of their results changed; an adapter whose technology is not present is probed again
 * only on reprobe. The declared probes stay for later reprobes. After an index rebuild outside a cycle
 * ({@code web.browsers}, a selection) the per-browser capabilities are improved when a browser now provides them
 * (never worsened outside a cycle) and {@code web.browsers} follows the rebuild's enumeration states. Once
 * {@link #close() closed} (the module's uninstall) no cycle runs any more: a cycle in progress is waited for, later
 * ones answer the last results without probing or rebuilding.
 *
 * <p>Thread-safe: cycles are serialised by one lock.
 */
final class WebCapabilities {

    static final String ADAPTER_PREFIX = "web.adapter.";
    static final String BROWSERS = "web.browsers";
    static final String EVALUATE = "web.evaluate";
    static final String EVENTS = "web.events";
    static final String INIT_SCRIPT = "web.initScript";
    static final String RESIZE = "web.resize";
    static final String BITMAP = "web.bitmap";
    static final String CDP = "web.cdp";
    static final String HITTEST = "web.hittest";
    static final String POINTER = "web.pointer";

    /** The capabilities every cycle computes besides {@code web.adapter.<id>}. */
    static final List<String> GENERIC = Collections.unmodifiableList(Arrays.asList(
            BROWSERS, EVALUATE, EVENTS, INIT_SCRIPT, RESIZE, BITMAP, CDP));

    static final String NO_TECHNOLOGY = "no embedded browser technology found";
    static final String RESERVED = "reserved for the vision bolt-on";
    static final String NO_ENDPOINT = "no remote-debugging endpoint configured";
    static final String AFTER_LOAD = "after document load";
    static final String NO_BROWSER = "no browser found";
    /** Reasons of an adapter whose technology is simply absent; not informative for the generic capabilities. */
    static final String NOT_PRESENT = "not present";
    static final String NOT_LOADED = "present but not loaded by the application yet";

    /** A cycle older than this is recomputed by the next probe. */
    static final long MAX_AGE_MS = 30000;

    private static final Pattern ADAPTER_ID = Pattern.compile("[a-z0-9]+");

    private final AgentContext ctx;
    private final BrowserIndex index;
    private final List<WebEngineAdapter> builtIns;
    private final Object cycleLock = new Object();
    private final Map<String, AutoCloseable> declared = new LinkedHashMap<String, AutoCloseable>();
    private final Set<String> served = new HashSet<String>();
    private final Set<String> warnedAdapters = new HashSet<String>();
    private volatile Cycle current;
    private volatile boolean computing;
    private boolean ready;
    private volatile boolean closed;

    WebCapabilities(AgentContext ctx, BrowserIndex index, List<WebEngineAdapter> builtIns) {
        this.ctx = ctx;
        this.index = index;
        this.builtIns = Collections.unmodifiableList(new ArrayList<WebEngineAdapter>(builtIns));
    }

    /** Install-time states (see the class comment). Touches nothing but the capability registry. */
    void installDefaults() {
        for (WebEngineAdapter a : builtIns) {
            ctx.capabilities().set(ADAPTER_PREFIX + a.id(), CapabilityState.UNAVAILABLE, NO_TECHNOLOGY, null);
        }
        for (String id : GENERIC) {
            ctx.capabilities().set(id, CapabilityState.UNAVAILABLE, NO_TECHNOLOGY, null);
        }
        ctx.capabilities().set(HITTEST, CapabilityState.UNAVAILABLE, RESERVED, null);
        ctx.capabilities().set(POINTER, CapabilityState.UNAVAILABLE, RESERVED, null);
    }

    /** The built-in adapters, then the registered ones with a valid, unused id. */
    List<WebEngineAdapter> adapters() {
        List<WebEngineAdapter> out = new ArrayList<WebEngineAdapter>(builtIns);
        Set<String> ids = new HashSet<String>();
        for (WebEngineAdapter a : builtIns) {
            ids.add(a.id());
        }
        List<WebEngineAdapter> registered;
        try {
            registered = ctx.spi().all(WebEngineAdapter.class);
        } catch (RuntimeException e) {
            registered = Collections.emptyList();
        }
        for (WebEngineAdapter a : registered) {
            String id;
            try {
                id = a.id();
            } catch (RuntimeException e) {
                id = null;
            }
            if (id == null || !ADAPTER_ID.matcher(id).matches() || !ids.add(id)) {
                warnOnce(a.getClass().getName() + "/" + id, "web: a registered WebEngineAdapter is skipped: its id '"
                        + id + "' is not lower-case letters and digits, or an earlier adapter has it");
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /**
     * The module's re-probe after a {@code window.opened} event (see the class comment): probes the candidates again,
     * at once, and answers true when the caller should run a full cycle: a candidate's result (state or reason)
     * differs from the last cycle's, or adapters were registered or withdrawn since. Runs on an agent worker, cheap
     * (the probes only); never throws; false before the first cycle and once closed.
     */
    boolean reprobeChanged() {
        synchronized (cycleLock) {
            Cycle c = current;
            if (c == null || closed) {
                return false;
            }
            List<WebEngineAdapter> all = adapters();
            Set<String> ids = new HashSet<String>();
            for (WebEngineAdapter a : all) {
                ids.add(a.id());
            }
            if (!ids.equals(c.adapterProbes.keySet())) {
                return true;
            }
            for (WebEngineAdapter a : all) {
                ProbeResult last = c.adapterProbes.get(a.id());
                if (last == null || last.state() != CapabilityState.UNAVAILABLE) {
                    continue;
                }
                if (builtIn(a) && !NOT_LOADED.equals(last.reason())) {
                    continue; // not present, too old, a member missing: only a reprobe asks again
                }
                ProbeResult now = probeOne(a);
                if (now.state() != last.state() || !equal(now.reason(), last.reason())) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean builtIn(WebEngineAdapter a) {
        for (WebEngineAdapter b : builtIns) {
            if (b == a) {
                return true;
            }
        }
        return false;
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** One adapter's probe with the host's loader; a null or a throw is an UNAVAILABLE result. */
    private ProbeResult probeOne(WebEngineAdapter a) {
        try {
            ProbeResult r = a.probe(ctx.hostClassLoader());
            return r != null ? r : ProbeResult.unavailable("the adapter's probe returned nothing");
        } catch (Throwable t) {
            return ProbeResult.unavailable("the adapter's probe failed: " + t);
        }
    }

    /**
     * One cycle (see the class comment): probes, index rebuild, every capability. Runs on an agent worker; bounded by
     * the adapters' probes and the index's own bound. Never throws. Once closed it probes and rebuilds nothing and
     * answers the last cycle (an empty one when there was none).
     */
    Cycle compute() {
        synchronized (cycleLock) {
            if (closed) {
                return lastOrEmpty();
            }
            computing = true;
            try {
                List<WebEngineAdapter> all = adapters();
                Map<String, ProbeResult> probes = new LinkedHashMap<String, ProbeResult>();
                List<WebEngineAdapter> present = new ArrayList<WebEngineAdapter>();
                for (WebEngineAdapter a : all) {
                    ProbeResult r = probeOne(a);
                    probes.put(a.id(), r);
                    if (r.state() != CapabilityState.UNAVAILABLE) {
                        present.add(a);
                    }
                }
                if (closed) {
                    return lastOrEmpty();
                }
                index.adapters(present);
                BrowserIndex.Snapshot s = index.rebuild(BrowserIndex.BUDGET_MS);
                Map<String, ProbeResult> caps = new LinkedHashMap<String, ProbeResult>();
                for (Map.Entry<String, ProbeResult> p : probes.entrySet()) {
                    caps.put(ADAPTER_PREFIX + p.getKey(), p.getValue());
                }
                caps.putAll(derive(probes, present, s, !closed));
                Cycle c = new Cycle(probes, caps);
                current = c;
                served.clear();
                if (ready) {
                    syncDeclarations(c);
                }
                return c;
            } finally {
                computing = false;
            }
        }
    }

    private Cycle lastOrEmpty() {
        Cycle c = current;
        return c != null ? c : new Cycle(new LinkedHashMap<String, ProbeResult>(),
                new LinkedHashMap<String, ProbeResult>());
    }

    /** The module is ready: declares every capability of the cycle with a probe (they are then probed by the core). */
    void declareAll(Cycle c) {
        synchronized (cycleLock) {
            ready = true;
            syncDeclarations(c);
        }
    }

    /** Sets every capability of the cycle at once (the module's own re-probe); the declared probes stay. */
    void apply(Cycle c) {
        for (Map.Entry<String, ProbeResult> e : c.capabilities.entrySet()) {
            set(e.getKey(), e.getValue());
        }
    }

    /** Declares the cycle's capabilities not declared yet and withdraws those of adapters no longer registered. */
    private void syncDeclarations(Cycle c) {
        if (closed) {
            return;
        }
        for (String id : c.capabilities.keySet()) {
            if (!declared.containsKey(id)) {
                declared.put(id, ctx.capabilities().declare(id, new CapabilityProbe(id)));
            }
        }
        List<String> gone = new ArrayList<String>();
        for (String id : declared.keySet()) {
            if (id.startsWith(ADAPTER_PREFIX) && !c.capabilities.containsKey(id)) {
                gone.add(id);
            }
        }
        for (String id : gone) {
            closeQuietly(declared.remove(id));
        }
    }

    /**
     * The probe body of capability {@code id}: the current cycle's result, or a new cycle when this id was already
     * served by the current one (a new probe round) or the cycle is old.
     */
    ProbeResult probe(String id) {
        synchronized (cycleLock) {
            Cycle c = current;
            if (c == null || served.contains(id) || c.ageMs() > MAX_AGE_MS) {
                c = compute();
            }
            served.add(id);
            ProbeResult r = c.capabilities.get(id);
            if (r != null) {
                return r;
            }
            return ProbeResult.unavailable(id.startsWith(ADAPTER_PREFIX) ? "the adapter is no longer registered"
                    : NO_TECHNOLOGY);
        }
    }

    /**
     * After an index rebuild outside a cycle: improves the per-browser capabilities a browser now provides and sets
     * {@code web.browsers} from the rebuild (see the class comment). Never throws.
     */
    void afterRebuild(BrowserIndex.Snapshot s) {
        if (computing || closed) {
            return;
        }
        try {
            Cycle c = current;
            if (c == null) {
                return;
            }
            List<WebEngineAdapter> present = index.adapters();
            if (present.isEmpty()) {
                return;
            }
            Map<String, ProbeResult> derived = derive(c.adapterProbes, present, s, false);
            set(BROWSERS, derived.get(BROWSERS));
            for (String id : Arrays.asList(EVALUATE, EVENTS, INIT_SCRIPT, RESIZE, BITMAP)) {
                ProbeResult now = derived.get(id);
                Capability before = ctx.capabilities().get(id);
                if (before != null && better(now.state(), before.state())) {
                    set(id, now);
                }
            }
        } catch (RuntimeException e) {
            ctx.log().warn("web: could not update the capabilities after an index rebuild: " + e);
        }
    }

    /** The capabilities derived from the probes and the index (endpoints only when {@code withEndpoints}). */
    private Map<String, ProbeResult> derive(Map<String, ProbeResult> probes, List<WebEngineAdapter> present,
                                            BrowserIndex.Snapshot s, boolean withEndpoints) {
        Map<String, ProbeResult> out = new LinkedHashMap<String, ProbeResult>();
        String absent = absentReason(probes);
        if (present.isEmpty()) {
            out.put(BROWSERS, ProbeResult.unavailable(NO_TECHNOLOGY));
            for (String id : Arrays.asList(EVALUATE, EVENTS, INIT_SCRIPT, RESIZE, BITMAP)) {
                out.put(id, ProbeResult.unavailable(absent));
            }
            if (withEndpoints) {
                out.put(CDP, ProbeResult.unavailable(NO_ENDPOINT));
            }
            return out;
        }
        out.put(BROWSERS, browsersState(present, s));
        out.put(EVALUATE, flag(s, WebCapability.EVALUATE));
        out.put(EVENTS, flag(s, WebCapability.EVENTS_LOAD));
        out.put(INIT_SCRIPT, initScript(s));
        out.put(RESIZE, flag(s, WebCapability.RESIZE));
        out.put(BITMAP, flag(s, WebCapability.BITMAP));
        if (withEndpoints) {
            out.put(CDP, endpoints(present, s));
        }
        return out;
    }

    private static ProbeResult browsersState(List<WebEngineAdapter> present, BrowserIndex.Snapshot s) {
        List<ProbeResult> considered = new ArrayList<ProbeResult>();
        for (WebEngineAdapter a : present) {
            ProbeResult r = s.states.get(a.id());
            if (r != null && (s.found.isEmpty() || s.found.contains(a.id()))) {
                considered.add(r);
            }
        }
        ProbeResult worst = ProbeResult.ok();
        for (ProbeResult r : considered) {
            if (r.state() != CapabilityState.OK && worst.state() == CapabilityState.OK) {
                worst = ProbeResult.degraded(r.reason() == null ? "enumeration incomplete" : r.reason());
            }
        }
        return worst;
    }

    private static ProbeResult flag(BrowserIndex.Snapshot s, WebCapability flag) {
        Set<String> providers = providers(s, flag);
        if (!providers.isEmpty()) {
            return ProbeResult.ok(join(providers));
        }
        return ProbeResult.unavailable(s.entries.isEmpty() ? NO_BROWSER : "no indexed browser has " + flag.name());
    }

    private static ProbeResult initScript(BrowserIndex.Snapshot s) {
        if (!providers(s, WebCapability.INIT_SCRIPT_DOCUMENT_START).isEmpty()) {
            return ProbeResult.ok("DOCUMENT_START");
        }
        if (!providers(s, WebCapability.EVALUATE).isEmpty()) {
            return ProbeResult.degraded(AFTER_LOAD);
        }
        return ProbeResult.unavailable(s.entries.isEmpty() ? NO_BROWSER : "no indexed browser can run scripts");
    }

    private static Set<String> providers(BrowserIndex.Snapshot s, WebCapability flag) {
        Set<String> out = new LinkedHashSet<String>();
        for (Entry e : s.entries) {
            WebBrowser b = e.browser();
            try {
                if (!b.isClosed() && b.capabilities().contains(flag)) {
                    out.add(e.adapter.id());
                }
            } catch (RuntimeException ex) {
                // a failing handle provides nothing
            }
        }
        return out;
    }

    private ProbeResult endpoints(List<WebEngineAdapter> present, BrowserIndex.Snapshot s) {
        for (WebEngineAdapter a : present) {
            try {
                List<CdpEndpoint> list = a.cdpEndpoints(s.seeds);
                if (list != null && !list.isEmpty() && list.get(0) != null) {
                    return ProbeResult.ok(list.get(0).host() + ":" + list.get(0).port());
                }
            } catch (Throwable t) {
                ctx.log().warn("web: adapter " + a.id() + " could not report its endpoints: " + t);
            }
        }
        return ProbeResult.unavailable(NO_ENDPOINT);
    }

    /** The first adapter reason that says more than "absent", else {@value #NO_TECHNOLOGY}. */
    private static String absentReason(Map<String, ProbeResult> probes) {
        for (ProbeResult r : probes.values()) {
            String reason = r.reason();
            if (r.state() == CapabilityState.UNAVAILABLE && reason != null && !reason.equals(NOT_PRESENT)
                    && !reason.equals(NOT_LOADED)) {
                return reason;
            }
        }
        return NO_TECHNOLOGY;
    }

    private static boolean better(CapabilityState now, CapabilityState before) {
        return rank(now) < rank(before);
    }

    private static int rank(CapabilityState s) {
        return s == CapabilityState.OK ? 0 : s == CapabilityState.DEGRADED ? 1 : 2;
    }

    private void set(String id, ProbeResult r) {
        if (r == null || closed) {
            return;
        }
        ctx.capabilities().set(id, r.state(), r.reason(), r.strategy());
    }

    /**
     * Withdraws the declared capabilities and ends the cycles (the module's uninstall; the core withdraws the rest):
     * waits for a cycle in progress (which stops at its next step), and no cycle probes or rebuilds afterwards.
     */
    void close() {
        closed = true;
        synchronized (cycleLock) {
            for (AutoCloseable c : declared.values()) {
                closeQuietly(c);
            }
            declared.clear();
        }
    }

    /** The current cycle, or null (for tests). */
    Cycle current() {
        return current;
    }

    private void warnOnce(String key, String message) {
        boolean first;
        synchronized (warnedAdapters) {
            first = warnedAdapters.add(key);
        }
        if (first) {
            ctx.log().warn(message);
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // the handle never throws
        }
    }

    private static String join(Set<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(sb.length() == 0 ? "" : ",").append(p);
        }
        return sb.toString();
    }

    /** One computation of every capability. Immutable. */
    static final class Cycle {
        /** Each adapter's probe result, by adapter id, in adapter order. */
        final Map<String, ProbeResult> adapterProbes;
        /** Every capability the cycle computed, by capability id. */
        final Map<String, ProbeResult> capabilities;
        private final long computedNanos = System.nanoTime();

        Cycle(Map<String, ProbeResult> adapterProbes, Map<String, ProbeResult> capabilities) {
            this.adapterProbes = Collections.unmodifiableMap(adapterProbes);
            this.capabilities = Collections.unmodifiableMap(capabilities);
        }

        long ageMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - computedNanos);
        }
    }

    /** The probe declared for one capability. */
    private final class CapabilityProbe implements Probe {
        private final String id;

        CapabilityProbe(String id) {
            this.id = id;
        }

        @Override
        public ProbeResult run(AgentContext context) {
            return probe(id);
        }
    }
}
