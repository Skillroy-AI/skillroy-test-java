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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.CapabilityRegistry;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * The {@link CapabilityRegistry}. A capability declared with a probe is <em>pending</em> (reported as UNAVAILABLE
 * "not probed yet") until the target is ready and its probe has run; the dispatcher answers NOT_READY for it in the
 * meantime. One declared after the target is ready says so in its reason ({@link #LATE_REASON}, gap 92) while its
 * posted probe waits for a worker. Probes run on agent workers, one at a time, each bounded by {@link #PROBE_TIMEOUT_MS}.
 *
 * <p>Withdrawal (contract amendment A18): each entry records who declared or first set it (a module's context, or
 * null for the core); {@link #removeOwnedBy} withdraws a module's capabilities after its {@code uninstall()} or a
 * failed install, and {@link #undeclare} or the handle {@link #declare} returns withdraw one. A withdrawn id is
 * removed from the matrix and remembered, so that the dispatcher answers a primitive that names it with
 * {@code CAPABILITY_UNAVAILABLE} "capability withdrawn" instead of running it; declaring or setting the id again
 * forgets the withdrawal.
 *
 * <p>Thread-safe.
 */
final class CoreCapabilityRegistry implements CapabilityRegistry {

    static final long PROBE_TIMEOUT_MS = 10000L;
    static final String PENDING_REASON = "not probed yet: the target is not ready";
    /** The pending reason of a capability declared once probes run: its probe is posted to a worker (gap 92). */
    static final String LATE_REASON = "not probed yet: declared after the target was ready, its probe is queued";

    private final AgentLog log;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private final Set<String> withdrawn = new HashSet<String>();
    private final Object probeLock = new Object();
    private volatile CoreThreadBroker broker;
    private volatile AgentContext context;
    private volatile boolean probesEnabled;

    CoreCapabilityRegistry(AgentLog log) {
        this.log = log;
    }

    /** Wires what probes need; called once by the runtime before modules install. */
    void attach(CoreThreadBroker threadBroker, AgentContext agentContext) {
        this.broker = threadBroker;
        this.context = agentContext;
    }

    @Override
    public AutoCloseable declare(String id, Probe probe) {
        return declare(id, probe, null);
    }

    /**
     * {@link #declare(String, Probe)} on behalf of {@code owner} (a module's context; null for the core), which owns
     * the entry if it creates it.
     */
    AutoCloseable declare(final String id, Probe probe, Object owner) {
        if (id == null || id.isEmpty() || probe == null) {
            throw new IllegalArgumentException("capability id and probe must not be null");
        }
        boolean probeNow;
        final Object declaration = new Object();
        synchronized (this) {
            Entry entry = entryFor(id, owner);
            entry.probe = probe;
            entry.declaration = declaration;
            entry.pending = true;
            probeNow = probesEnabled;
            entry.capability = new Capability(id, CapabilityState.UNAVAILABLE, probeNow ? LATE_REASON : PENDING_REASON,
                    null);
        }
        if (probeNow) {
            CoreThreadBroker b = broker;
            if (b != null) {
                b.post(ThreadPolicy.WORKER, new Runnable() {
                    @Override
                    public void run() {
                        probeOne(id);
                    }
                });
            }
        }
        return new AutoCloseable() {
            @Override
            public void close() {
                synchronized (CoreCapabilityRegistry.this) {
                    Entry entry = entries.get(id);
                    if (entry != null && entry.declaration == declaration) {
                        withdraw(id);
                    }
                }
            }
        };
    }

    /** The entry of {@code id}, created for {@code owner} if missing; forgets an earlier withdrawal. */
    private Entry entryFor(String id, Object owner) {
        Entry entry = entries.get(id);
        if (entry == null) {
            entry = new Entry();
            entry.owner = owner;
            entries.put(id, entry);
        }
        withdrawn.remove(id);
        return entry;
    }

    @Override
    public void set(String id, CapabilityState state, String reason, String strategy) {
        set(id, state, reason, strategy, null);
    }

    /** {@link #set(String, CapabilityState, String, String)} on behalf of {@code owner} (see {@link #declare}). */
    synchronized void set(String id, CapabilityState state, String reason, String strategy, Object owner) {
        if (id == null || id.isEmpty() || state == null) {
            throw new IllegalArgumentException("capability id and state must not be null");
        }
        Entry entry = entryFor(id, owner);
        entry.pending = false;
        entry.capability = new Capability(id, state, reason, strategy);
    }

    @Override
    public synchronized void undeclare(String id) {
        if (id != null) {
            withdraw(id);
        }
    }

    /** Removes the entry and remembers the withdrawal; no effect for an unknown id. Caller holds the monitor. */
    private void withdraw(String id) {
        if (entries.remove(id) != null) {
            withdrawn.add(id);
        }
    }

    /** Withdraws every capability {@code owner} declared or first set (a module that uninstalled or failed). */
    synchronized void removeOwnedBy(Object owner) {
        if (owner == null) {
            return;
        }
        List<String> owned = new ArrayList<String>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().owner == owner) {
                owned.add(e.getKey());
            }
        }
        for (String id : owned) {
            withdraw(id);
        }
    }

    /** True if the capability was withdrawn and has not been declared or set since. */
    synchronized boolean isWithdrawn(String id) {
        return id != null && withdrawn.contains(id);
    }

    @Override
    public synchronized Capability get(String id) {
        Entry entry = id == null ? null : entries.get(id);
        return entry == null ? null : entry.capability;
    }

    @Override
    public synchronized Map<String, Capability> snapshot() {
        Map<String, Capability> sorted = new TreeMap<String, Capability>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            sorted.put(e.getKey(), e.getValue().capability);
        }
        return Collections.unmodifiableMap(sorted);
    }

    /** Marks a capability the core resolves itself (target.ready) as pending, with the given reason. */
    synchronized void markPending(String id, String reason) {
        Entry entry = entryFor(id, null);
        entry.pending = true;
        entry.capability = new Capability(id, CapabilityState.UNAVAILABLE, reason, null);
    }

    /** True if declared with a probe that has not run yet. */
    synchronized boolean isPending(String id) {
        Entry entry = id == null ? null : entries.get(id);
        return entry != null && entry.pending;
    }

    /** The {@code {"capabilities":{id:{state,reason,strategy}}}} wire shape. */
    Map<String, Object> toJson() {
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        for (Capability capability : snapshot().values()) {
            caps.put(capability.id(), capability.toJson());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("capabilities", caps);
        return out;
    }

    /** Runs every probe (once the target is ready, and on reprobe); later declarations are probed at once. */
    void probeAll() {
        List<String> ids;
        synchronized (this) {
            probesEnabled = true;
            ids = new ArrayList<String>();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                if (e.getValue().probe != null) {
                    ids.add(e.getKey());
                }
            }
        }
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            probeOne(id);
        }
    }

    private void probeOne(String id) {
        synchronized (probeLock) {
            final Probe probe;
            synchronized (this) {
                Entry entry = entries.get(id);
                if (entry == null || entry.probe == null) {
                    return;
                }
                probe = entry.probe;
            }
            Capability result = runProbe(id, probe);
            if (result == null) {
                return;
            }
            synchronized (this) {
                Entry entry = entries.get(id);
                if (entry != null && entry.probe == probe) {
                    entry.capability = result;
                    entry.pending = false;
                }
            }
        }
    }

    /** The probed capability, or null if the calling thread was interrupted. */
    private Capability runProbe(String id, final Probe probe) {
        final AgentContext ctx = context;
        CoreThreadBroker b = broker;
        try {
            ProbeResult result;
            Callable<ProbeResult> task = new Callable<ProbeResult>() {
                @Override
                public ProbeResult call() throws Exception {
                    return probe.run(ctx);
                }
            };
            result = b == null ? task.call() : b.call(ThreadPolicy.WORKER, task, PROBE_TIMEOUT_MS);
            if (result == null) {
                return new Capability(id, CapabilityState.UNAVAILABLE, "probe returned null", null);
            }
            return new Capability(id, result.state(), result.reason(), result.strategy());
        } catch (InterruptedException e) {
            // The caller gave up (a reprobe that timed out): keep the previous state.
            Thread.currentThread().interrupt();
            return null;
        } catch (RpcException e) {
            String reason = e.error().code() == ErrorCodes.TIMEOUT
                    ? "probe timed out after " + PROBE_TIMEOUT_MS + " ms" : e.getMessage();
            log.warn("probe of capability " + id + " failed: " + reason);
            return new Capability(id, CapabilityState.UNAVAILABLE, reason, null);
        } catch (Throwable t) {
            log.warn("probe of capability " + id + " failed", t);
            String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            return new Capability(id, CapabilityState.UNAVAILABLE, reason, null);
        }
    }

    private static final class Entry {
        /** Who created the entry: a module's context, or null for the core. */
        Object owner;
        /** Identifies the declaration a handle from {@link #declare} may withdraw. */
        Object declaration;
        Probe probe;
        Capability capability;
        boolean pending;
    }
}
