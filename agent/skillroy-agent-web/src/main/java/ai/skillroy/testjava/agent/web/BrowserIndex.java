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
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.BrowserRegistryAdapter;
import ai.skillroy.testjava.agent.api.spi.web.BrowserSeed;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The browser index (phase4-contracts.md section 4.1): every browser the present adapters find, each under a ref of
 * kind {@code browser} minted by the agent's {@code HandleRegistry} from {@code vendorObject()}, so that the ref is
 * stable for the life of the vendor object. Rebuilt on every {@code web.browsers} call, on a call naming an unknown or
 * stale ref, at every capability probe and when a selection finds the index older than {@link #STALE_MS}; each
 * rebuild is bounded at {@link #BUDGET_MS} of the calling worker's time:
 * <ol>
 *   <li>seeds from every {@link BrowserRegistryAdapter}, each called once; one that throws is logged and skipped
 *       (null collections and null elements are tolerated);</li>
 *   <li>seeds from the Swing tree ({@link SeedWalk}: every displayable component of every window, one bounded EDT
 *       read, only when an event-dispatch thread runs);</li>
 *   <li>each present adapter's {@code browsers(seeds)}, with the {@link BrowserSeed#object() objects} of the seeds
 *       (not the wrappers); an adapter the budget no longer reaches keeps its previous browsers;</li>
 *   <li>the refs, the components' refs (kind {@code component}, so that they equal {@code swing.snapshot} refs) and
 *       whether each component is showing (one bounded EDT read);</li>
 *   <li>(Phase 8, phase8-contracts.md 5.2, D547) each entry's {@link Entry#masked} flag: while a {@code CaptureMask} or
 *       a {@code BrowserMask} is registered, the {@link BrowserMasks} rule for every entry (no EDT read) and, for the
 *       entries it leaves to their component, one bounded EDT read of the {@code CaptureMask}s for all of them together
 *       ({@link SeedWalk#masked}); a read that fails or has no time masks every such entry.</li>
 * </ol>
 * A browser that left the index has its ref released (a later call naming it answers {@code STALE_HANDLE}) and its
 * subscription cancelled; {@code web.browsers.changed {added, removed}} is published when the set changed. A seed's
 * tag goes to the browser whose vendor object or component is that seed's object.
 *
 * <p>Thread-safe: rebuilds are serialised (a caller that cannot get the lock within its budget uses the index as it
 * is); readers see an immutable {@link Snapshot}.
 */
final class BrowserIndex {

    /** The bound of one rebuild, in milliseconds of the calling worker's time. */
    static final long BUDGET_MS = 2000;
    /** A selection by {@code by} or by default rebuilds an index older than this. */
    static final long STALE_MS = 2000;

    /** Told about browsers entering and leaving the index, on the rebuilding thread, after the swap. */
    interface Listener {
        void entered(Entry entry);

        void left(Entry entry);
    }

    private final AgentContext ctx;
    private final SeedWalk walk;
    private final BrowserMasks masks;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, String> notes = new HashMap<String, String>();
    private final Set<String> failingRegistries = new LinkedHashSet<String>();
    private volatile Listener listener;
    private volatile Hook hook;
    private volatile List<WebEngineAdapter> adapters = Collections.emptyList();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private boolean walkFailureLogged;
    private boolean maskReadFailureLogged;

    BrowserIndex(AgentContext ctx, SeedWalk walk) {
        this.ctx = ctx;
        this.walk = walk;
        this.masks = new BrowserMasks(ctx);
    }

    /** The {@code BrowserMask} rule the index and the per-call reads share (phase8-contracts.md 5.2). */
    BrowserMasks masks() {
        return masks;
    }

    void listener(Listener l) {
        this.listener = l;
    }

    /** Called with the new snapshot after every rebuild, on the rebuilding thread, after the listeners. */
    interface Hook {
        void rebuilt(Snapshot snapshot);
    }

    void hook(Hook h) {
        this.hook = h;
    }

    /** The adapters whose probe found their technology present (set by the capability cycle). */
    void adapters(List<WebEngineAdapter> present) {
        this.adapters = Collections.unmodifiableList(new ArrayList<WebEngineAdapter>(present));
    }

    List<WebEngineAdapter> adapters() {
        return adapters;
    }

    /** The index as the last rebuild left it. */
    Snapshot snapshot() {
        return snapshot;
    }

    /** The entry of a vendor object in the current index, or null. */
    Entry entryFor(Object vendor) {
        return vendor == null ? null : snapshot.byVendor.get(vendor);
    }

    /**
     * Rebuilds the index within {@code min(budgetMs, BUDGET_MS)}; never throws. When another rebuild holds the lock
     * longer than the budget, returns the index as it is.
     */
    Snapshot rebuild(long budgetMs) {
        long budget = Math.max(1, Math.min(budgetMs, BUDGET_MS));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
        try {
            if (!lock.tryLock(budget, TimeUnit.MILLISECONDS)) {
                return snapshot;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return snapshot;
        }
        List<Entry> added = new ArrayList<Entry>();
        List<Entry> removed = new ArrayList<Entry>();
        Snapshot after;
        try {
            Snapshot before = snapshot;
            after = build(before, deadline);
            for (Entry e : after.entries) {
                if (!before.byVendor.containsKey(e.vendor())) {
                    added.add(e);
                }
            }
            for (Entry e : before.entries) {
                if (after.byVendor.get(e.vendor()) != e) {
                    removed.add(e);
                }
            }
            snapshot = after;
        } finally {
            lock.unlock();
        }
        Listener l = listener;
        for (Entry e : removed) {
            if (!after.byRef.containsKey(e.ref)) {
                ctx.handles().release(e.ref);
            }
            if (l != null) {
                l.left(e);
            }
        }
        for (Entry e : added) {
            if (l != null) {
                l.entered(e);
            }
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("added", refs(added));
            payload.put("removed", refs(removed));
            ctx.events().publish(WebTopics.BROWSERS_CHANGED, WebTopics.SOURCE, payload);
        }
        Hook h = hook;
        if (h != null) {
            try {
                h.rebuilt(after);
            } catch (RuntimeException e) {
                ctx.log().warn("web: a rebuild hook failed: " + e);
            }
        }
        return after;
    }

    /** Forgets every browser (the module's uninstall, or no adapter present any more); never throws. */
    void clear() {
        List<WebEngineAdapter> none = Collections.emptyList();
        adapters(none);
        rebuild(BUDGET_MS);
    }

    private Snapshot build(Snapshot before, long deadline) {
        List<WebEngineAdapter> present = adapters;
        if (present.isEmpty()) {
            return Snapshot.empty(System.nanoTime(), true);
        }
        List<Object> seeds = new ArrayList<Object>();
        Map<Object, String> tags = new IdentityHashMap<Object, String>();
        registrySeeds(seeds, tags);
        long left = remainingMs(deadline);
        if (left > 0) {
            try {
                seeds.addAll(walk.components(left));
            } catch (Exception e) {
                if (!walkFailureLogged) {
                    walkFailureLogged = true;
                    ctx.log().warn("web: the Swing tree could not be read for browser seeds (" + e + "); the index "
                            + "goes on with the registered seeds");
                }
            }
        }
        List<Object> frozen = Collections.unmodifiableList(seeds);
        List<Entry> entries = new ArrayList<Entry>();
        Map<Object, Entry> byVendor = new IdentityHashMap<Object, Entry>();
        Map<String, ProbeResult> states = new LinkedHashMap<String, ProbeResult>();
        Set<String> found = new LinkedHashSet<String>();
        for (WebEngineAdapter adapter : present) {
            String id = adapter.id();
            List<WebBrowser> browsers = null;
            if (remainingMs(deadline) > 0) {
                try {
                    browsers = adapter.browsers(frozen);
                    ProbeResult state = adapter.enumerationState();
                    states.put(id, state == null ? ProbeResult.ok() : state);
                    logNotes(adapter);
                } catch (Throwable t) {
                    ctx.log().warn("web: adapter " + id + " could not enumerate its browsers: " + t);
                    browsers = null;
                }
            }
            if (browsers == null) {
                keepPrevious(before, adapter, entries, byVendor);
                if (before.states.containsKey(id)) {
                    states.put(id, before.states.get(id));
                }
                if (before.found.contains(id)) {
                    found.add(id);
                }
                continue;
            }
            for (WebBrowser b : browsers) {
                Object vendor = b == null ? null : b.vendorObject();
                if (vendor == null || byVendor.containsKey(vendor)) {
                    continue;
                }
                Entry e = before.byVendor.get(vendor);
                if (e == null || e.adapter != adapter) {
                    e = new Entry(ctx.handles().handleFor(vendor, "browser"), adapter, b);
                } else {
                    e.browser(b);
                }
                byVendor.put(vendor, e);
                entries.add(e);
                found.add(id);
            }
        }
        List<Object> components = new ArrayList<Object>();
        for (Entry e : entries) {
            Object c = safeComponent(e);
            String tag = tags.get(e.vendor());
            if (tag == null && c != null) {
                tag = tags.get(c);
            }
            if (tag != null) {
                e.tag = tag;
            }
            e.componentRef = c == null ? null : ctx.handles().handleFor(c, "component");
            e.component = c;
            if (c != null) {
                components.add(c);
            }
        }
        Map<Object, Boolean> showing = Collections.emptyMap();
        long remaining = remainingMs(deadline);
        if (!components.isEmpty() && remaining > 0) {
            try {
                showing = walk.showing(components, remaining);
            } catch (Exception e) {
                ctx.log().warn("web: could not read whether the browsers' components are showing: " + e);
            }
        }
        for (Entry e : entries) {
            Object c = safeComponent(e);
            e.showing = c != null && Boolean.TRUE.equals(showing.get(c));
        }
        computeMasked(entries, deadline);
        return new Snapshot(entries, byVendor, frozen, states, found, System.nanoTime(), true);
    }

    /**
     * Sets every entry's {@link Entry#masked} flag (phase8-contracts.md 5.2, D547; see the class comment): no mask
     * registered, nothing masked and nothing called; else the {@link BrowserMasks} rule per entry and one bounded EDT
     * read for the components it leaves undecided, made only while the rebuild has time; a read that fails or has no
     * time masks every component-bearing entry it was to judge (the safe side).
     */
    private void computeMasked(List<Entry> entries, long deadline) {
        boolean registered;
        try {
            registered = walk.masksRegistered();
        } catch (RuntimeException ex) {
            registered = true;
        }
        List<Entry> pending = new ArrayList<Entry>();
        List<Object> components = new ArrayList<Object>();
        for (Entry e : entries) {
            Object c = e.component;
            Boolean decided = masks.decide(registered, e, c);
            if (decided != null) {
                e.masked = decided.booleanValue();
            } else {
                pending.add(e);
                components.add(c);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        Map<Object, Boolean> read = null;
        long remaining = remainingMs(deadline);
        if (remaining > 0) {
            try {
                read = walk.masked(components, remaining);
            } catch (Exception ex) {
                if (!maskReadFailureLogged) {
                    maskReadFailureLogged = true;
                    ctx.log().warn("web: could not read whether a CaptureMask names the browsers' components (" + ex
                            + "); they are masked until a read succeeds");
                }
            }
        }
        for (Entry e : pending) {
            Boolean m = read == null ? null : read.get(e.component);
            e.masked = m == null || m.booleanValue();
        }
    }

    private static void keepPrevious(Snapshot before, WebEngineAdapter adapter, List<Entry> entries,
                                     Map<Object, Entry> byVendor) {
        for (Entry e : before.entries) {
            if (e.adapter == adapter && !byVendor.containsKey(e.vendor())) {
                byVendor.put(e.vendor(), e);
                entries.add(e);
            }
        }
    }

    private void registrySeeds(List<Object> seeds, Map<Object, String> tags) {
        List<BrowserRegistryAdapter> registries;
        try {
            registries = ctx.spi().all(BrowserRegistryAdapter.class);
        } catch (RuntimeException e) {
            ctx.log().warn("web: the SPI registry failed: " + e);
            return;
        }
        for (BrowserRegistryAdapter registry : registries) {
            String name = registry.getClass().getName();
            try {
                Collection<BrowserSeed> given = registry.seeds();
                if (given != null) {
                    for (BrowserSeed seed : given) {
                        if (seed == null) {
                            continue;
                        }
                        seeds.add(seed.object());
                        if (seed.tag() != null && !tags.containsKey(seed.object())) {
                            tags.put(seed.object(), seed.tag());
                        }
                    }
                }
                synchronized (failingRegistries) {
                    failingRegistries.remove(name);
                }
            } catch (Throwable t) {
                boolean first;
                synchronized (failingRegistries) {
                    first = failingRegistries.add(name);
                }
                if (first) {
                    ctx.log().warn("web: BrowserRegistryAdapter " + name + " failed and is skipped: " + t);
                }
            }
        }
    }

    private void logNotes(WebEngineAdapter adapter) {
        List<String> list;
        try {
            list = adapter.enumerationNotes();
        } catch (Throwable t) {
            return;
        }
        String joined = list == null || list.isEmpty() ? "" : String.valueOf(list);
        String previous;
        synchronized (notes) {
            previous = notes.put(adapter.id(), joined);
        }
        if (!joined.isEmpty() && !joined.equals(previous)) {
            ctx.log().info("web: adapter " + adapter.id() + " notes: " + joined);
        }
    }

    private static Object safeComponent(Entry e) {
        try {
            return e.browser().component();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<Object> refs(List<Entry> entries) {
        List<Object> out = new ArrayList<Object>();
        for (Entry e : entries) {
            out.add(e.ref);
        }
        return out;
    }

    private static long remainingMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    /** The index at one moment; immutable. */
    static final class Snapshot {
        static final Snapshot EMPTY = empty(0, false);

        /** In discovery order (adapters in probe order, each adapter's browsers in its own order). */
        final List<Entry> entries;
        final Map<String, Entry> byRef;
        final Map<Object, Entry> byVendor;
        /** The seeds of the rebuild, for {@code cdpEndpoints}. */
        final List<Object> seeds;
        /** Each consulted adapter's {@code enumerationState()} at this rebuild. */
        final Map<String, ProbeResult> states;
        /** The adapters that found at least one browser. */
        final Set<String> found;
        final long builtNanos;
        final boolean built;

        Snapshot(List<Entry> entries, Map<Object, Entry> byVendor, List<Object> seeds,
                 Map<String, ProbeResult> states, Set<String> found, long builtNanos, boolean built) {
            this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
            Map<String, Entry> refs = new LinkedHashMap<String, Entry>();
            for (Entry e : entries) {
                refs.put(e.ref, e);
            }
            this.byRef = Collections.unmodifiableMap(refs);
            this.byVendor = Collections.unmodifiableMap(new IdentityHashMap<Object, Entry>(byVendor));
            this.seeds = seeds;
            this.states = Collections.unmodifiableMap(new LinkedHashMap<String, ProbeResult>(states));
            this.found = Collections.unmodifiableSet(new LinkedHashSet<String>(found));
            this.builtNanos = builtNanos;
            this.built = built;
        }

        static Snapshot empty(long nanos, boolean built) {
            return new Snapshot(Collections.<Entry>emptyList(), Collections.<Object, Entry>emptyMap(),
                    Collections.emptyList(), Collections.<String, ProbeResult>emptyMap(),
                    Collections.<String>emptySet(), nanos, built);
        }

        /** Milliseconds since this snapshot was built ({@code Long.MAX_VALUE} for one never built). */
        long ageMs() {
            return built ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - builtNanos) : Long.MAX_VALUE;
        }
    }
}
