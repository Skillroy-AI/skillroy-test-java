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
import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.CapabilityRegistry;
import ai.skillroy.testjava.agent.api.EventBus;
import ai.skillroy.testjava.agent.api.EventSink;
import ai.skillroy.testjava.agent.api.HandleRegistry;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveRegistry;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PayloadMatch;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.awt.EventQueue;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-test {@link AgentContext} that behaves like the agent core where the web module relies on it:
 * <ul>
 *   <li>{@link #call} plays the dispatcher: METHOD_NOT_FOUND, READ_ONLY for a mutating or script primitive in
 *       read-only mode (D156),
 *       NOT_READY for a pending capability, CAPABILITY_UNAVAILABLE with {@code data {primitive, capability, reason}},
 *       {@code _meta.timeoutMs} and {@code _meta.secretParams} (handed to {@link Invocation#secretParams()}), the
 *       primitive run on its thread policy with the timeout (TIMEOUT, the worker interrupted), and a check that the
 *       value is plain JSON;</li>
 *   <li>the capability registry keeps a {@code declare}d capability pending until {@link #probeAll()}, keeps its probe
 *       when it is {@code set} later, and probes a declaration made after the first {@code probeAll} at once;</li>
 *   <li>handles are minted per object identity (first kind sticks) and {@code release} forgets one (STALE_HANDLE);
 *   </li>
 *   <li>the event bus numbers events, delivers to sinks on the publishing thread and answers {@code await}.</li>
 * </ul>
 */
final class FakeAgentContext implements AgentContext {

    final Prims primitives = new Prims();
    final Bus events = new Bus();
    final Handles handles = new Handles();
    final Broker threads = new Broker();
    final Caps capabilities = new Caps();
    final Spi spi = new Spi();
    final Log log = new Log();
    final Map<String, String> options = new LinkedHashMap<String, String>();
    volatile boolean readOnly;
    volatile ClassLoader hostLoader = FakeAgentContext.class.getClassLoader();
    private final ExecutorService callers = Executors.newCachedThreadPool(daemon("skillroy-test-caller"));

    @Override
    public Instrumentation instrumentation() {
        return null;
    }

    @Override
    public LoadMode loadMode() {
        return LoadMode.EMBEDDED;
    }

    @Override
    public Map<String, String> options() {
        return Collections.unmodifiableMap(options);
    }

    @Override
    public ClassLoader hostClassLoader() {
        return hostLoader;
    }

    @Override
    public Object hostContext() {
        return null;
    }

    @Override
    public PrimitiveRegistry primitives() {
        return primitives;
    }

    @Override
    public EventBus events() {
        return events;
    }

    @Override
    public HandleRegistry handles() {
        return handles;
    }

    @Override
    public ThreadBroker threads() {
        return threads;
    }

    @Override
    public CapabilityRegistry capabilities() {
        return capabilities;
    }

    @Override
    public SpiRegistry spi() {
        return spi;
    }

    @Override
    public AgentLog log() {
        return log;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public boolean targetReady() {
        return true;
    }

    /** Runs every declared probe, as the core does after onTargetReady and on reprobe. */
    void probeAll() {
        capabilities.probeAll(this);
    }

    /** Calls a primitive like the dispatcher; {@code meta} may hold {@code timeoutMs} and {@code secretParams}. */
    Object call(String method, Map<String, Object> params) throws Exception {
        return call(method, params, null);
    }

    @SuppressWarnings("unchecked")
    Object call(final String method, Map<String, Object> params, Map<String, Object> meta) throws Exception {
        final Primitive p = primitives.find(method);
        if (p == null) {
            throw new RpcException(ErrorCodes.METHOD_NOT_FOUND, "unknown method " + method);
        }
        final PrimitiveSpec spec = p.spec();
        if ((spec.mutating() || spec.script()) && readOnly) {
            throw new RpcException(ErrorCodes.READ_ONLY, method + " changes the target and the agent is read-only",
                    data("primitive", method));
        }
        String capabilityId = spec.capabilityId();
        if (capabilityId != null) {
            if (capabilities.isPending(capabilityId)) {
                throw new RpcException(ErrorCodes.NOT_READY, "capability " + capabilityId + " not probed yet",
                        data("primitive", method, "capability", capabilityId));
            }
            Capability cap = capabilities.get(capabilityId);
            if (cap != null && cap.state() == CapabilityState.UNAVAILABLE) {
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "capability " + capabilityId
                        + " is UNAVAILABLE", data("primitive", method, "capability", capabilityId, "reason",
                        cap.reason()));
            }
        }
        long timeout = spec.timeoutMs();
        final List<String> secrets = new ArrayList<String>();
        if (meta != null) {
            if (meta.get("timeoutMs") instanceof Number) {
                timeout = Math.min(((Number) meta.get("timeoutMs")).longValue(), 120000L);
            }
            if (meta.get("secretParams") instanceof List) {
                for (Object n : (List<Object>) meta.get("secretParams")) {
                    if (!secrets.contains(String.valueOf(n))) {
                        secrets.add(String.valueOf(n));
                    }
                }
            }
        }
        final long timeoutMs = timeout;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        final JsonView args = JsonView.of(params == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(params));
        final Invocation inv = new Invocation() {
            @Override
            public JsonView args() {
                return args;
            }

            @Override
            public String correlationId() {
                return null;
            }

            @Override
            public String traceparent() {
                return null;
            }

            @Override
            public AgentContext context() {
                return FakeAgentContext.this;
            }

            @Override
            public long remainingMs() {
                return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            }

            @Override
            public List<String> secretParams() {
                return Collections.unmodifiableList(secrets);
            }
        };
        Future<Object> f = callers.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return threads.call(spec.thread(), new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return p.invoke(inv);
                    }
                }, timeoutMs);
            }
        });
        Object value;
        try {
            value = f.get(timeoutMs + 200, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new RpcException(ErrorCodes.TIMEOUT, method + " timed out after " + timeoutMs + " ms",
                    data("primitive", method, "timeoutMs", Long.valueOf(timeoutMs)));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
        Json.parse(Json.write(value));
        new RpcResult(value, Collections.<Event>emptyList(), 0, 0, 0, "test");
        return value;
    }

    /** Calls and returns the value as a map. */
    @SuppressWarnings("unchecked")
    Map<String, Object> map(String method, Map<String, Object> params) throws Exception {
        return (Map<String, Object>) call(method, params);
    }

    /** The RPC error a call answers (fails when it succeeds). */
    RpcException error(String method, Map<String, Object> params) {
        return error(method, params, null);
    }

    RpcException error(String method, Map<String, Object> params, Map<String, Object> meta) {
        try {
            Object v = call(method, params, meta);
            throw new AssertionError(method + " succeeded: " + v);
        } catch (RpcException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError(method + " failed with " + e, e);
        }
    }

    void shutdown() {
        callers.shutdownNow();
        threads.workers.shutdownNow();
    }

    static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    static ThreadFactory daemon(final String prefix) {
        final AtomicInteger n = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ registries

    static final class Prims implements PrimitiveRegistry {
        private final Map<String, Primitive> map = new TreeMap<String, Primitive>();

        @Override
        public synchronized void register(Primitive primitive) {
            String name = primitive.spec().name();
            if (map.containsKey(name)) {
                throw new IllegalStateException("duplicate primitive " + name);
            }
            map.put(name, primitive);
        }

        @Override
        public synchronized void unregister(String name) {
            map.remove(name);
        }

        @Override
        public synchronized Primitive find(String name) {
            return map.get(name);
        }

        @Override
        public synchronized List<PrimitiveSpec> list() {
            List<PrimitiveSpec> out = new ArrayList<PrimitiveSpec>();
            for (Primitive p : map.values()) {
                out.add(p.spec());
            }
            return out;
        }
    }

    static final class Bus implements EventBus {
        private final List<Event> events = new ArrayList<Event>();
        private final List<Object[]> sinks = new CopyOnWriteArrayList<Object[]>();
        private long seq;

        @Override
        public long publish(String topic, String source, Map<String, Object> payload) {
            Event e;
            synchronized (this) {
                Json.parse(Json.write(payload));
                e = new Event(++seq, System.currentTimeMillis(), topic, source, null, null, payload);
                events.add(e);
                notifyAll();
            }
            for (Object[] s : sinks) {
                if (((TopicFilter) s[0]).matches(topic)) {
                    ((EventSink) s[1]).onEvent(e);
                }
            }
            return e.seq();
        }

        @Override
        public synchronized long lastSeq() {
            return seq;
        }

        @Override
        public synchronized List<Event> since(long seqExclusive, TopicFilter filter, int limit) {
            List<Event> out = new ArrayList<Event>();
            for (Event e : events) {
                if (e.seq() > seqExclusive && (filter == null || filter.matches(e.topic()))) {
                    out.add(e);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
            return out;
        }

        @Override
        public synchronized Event await(long seqExclusive, TopicFilter filter, Map<String, Object> match,
                                        long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                for (Event e : events) {
                    if (e.seq() > seqExclusive && (filter == null || filter.matches(e.topic())) && matches(e, match)) {
                        return e;
                    }
                }
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    return null;
                }
                wait(left);
            }
        }

        /** The agent's payload rule, {@code PayloadMatch} (phase8-contracts.md 8.9). */
        private static boolean matches(Event e, Map<String, Object> match) {
            return PayloadMatch.matches(e.payload(), match);
        }

        @Override
        public AutoCloseable subscribe(TopicFilter filter, EventSink sink) {
            final Object[] entry = {filter, sink};
            sinks.add(entry);
            return new AutoCloseable() {
                @Override
                public void close() {
                    sinks.remove(entry);
                }
            };
        }

        @Override
        public synchronized long oldestSeq() {
            return events.isEmpty() ? 0 : events.get(0).seq();
        }

        synchronized List<Event> all() {
            return new ArrayList<Event>(events);
        }

        /** The events of a topic, in order. */
        synchronized List<Event> topic(String topic) {
            List<Event> out = new ArrayList<Event>();
            for (Event e : events) {
                if (e.topic().equals(topic)) {
                    out.add(e);
                }
            }
            return out;
        }

        /** The first event of the topic after seq whose payload matches, waiting at most the bound. */
        Event awaitTopic(String topic, long afterSeq, Map<String, Object> match, long boundMs)
                throws InterruptedException {
            return await(afterSeq, TopicFilter.parse(topic), match, boundMs);
        }
    }

    static final class Handles implements HandleRegistry {
        private final Map<Object, String> byObject = new IdentityHashMap<Object, String>();
        private final Map<String, Object> byHandle = new HashMap<String, Object>();
        private final Map<String, String> kinds = new HashMap<String, String>();
        private int next;

        @Override
        public synchronized String handleFor(Object object, String kind) {
            String h = byObject.get(object);
            if (h == null) {
                h = "h" + (++next);
                byObject.put(object, h);
                byHandle.put(h, object);
                kinds.put(h, kind);
            }
            return h;
        }

        @Override
        public synchronized Object resolve(String handle) {
            Object o = handle == null ? null : byHandle.get(handle);
            if (o == null) {
                throw new RpcException(ErrorCodes.STALE_HANDLE, "handle " + handle + " is unknown",
                        data("handle", handle));
            }
            return o;
        }

        @Override
        public <T> T resolve(String handle, Class<T> type) {
            Object o = resolve(handle);
            if (!type.isInstance(o)) {
                throw new RpcException(ErrorCodes.INVALID_PARAMS, handle + " is not a " + type.getSimpleName());
            }
            return type.cast(o);
        }

        @Override
        public synchronized void release(String handle) {
            Object o = byHandle.remove(handle);
            if (o != null) {
                byObject.remove(o);
            }
        }

        synchronized String kindOf(String handle) {
            return kinds.get(handle);
        }
    }

    final class Broker implements ThreadBroker {
        final ExecutorService workers = Executors.newCachedThreadPool(daemon("skillroy-test-worker"));
        /** Runs posted tasks on the posting thread (for tests that want a re-probe to be done at once). */
        volatile boolean inlinePosts;
        /** Drops this many of the next posted tasks without a word, as the core's broker does when its pool is full. */
        final AtomicInteger dropPosts = new AtomicInteger();
        final AtomicInteger edtCalls = new AtomicInteger();

        @Override
        public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
            switch (policy) {
                case CALLER:
                    return task.call();
                case EDT_SYNC:
                    edtCalls.incrementAndGet();
                    if (EventQueue.isDispatchThread()) {
                        return task.call();
                    }
                    FutureTask<T> ft = new FutureTask<T>(task);
                    EventQueue.invokeLater(ft);
                    return await(ft, timeoutMs, "the EDT");
                case EDT_ASYNC:
                    edtCalls.incrementAndGet();
                    EventQueue.invokeLater(new FutureTask<T>(task));
                    return null;
                default:
                    return await(workers.submit(task), timeoutMs, "a worker");
            }
        }

        private <T> T await(Future<T> f, long timeoutMs, String where) throws Exception {
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new RpcException(ErrorCodes.TIMEOUT, where + " did not run the task within " + timeoutMs
                        + " ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw (Error) cause;
            }
        }

        @Override
        public void post(ThreadPolicy policy, final Runnable task) {
            if (dropPosts.get() > 0 && dropPosts.getAndDecrement() > 0) {
                return;
            }
            if (inlinePosts) {
                task.run();
                return;
            }
            if (policy == ThreadPolicy.EDT_SYNC || policy == ThreadPolicy.EDT_ASYNC) {
                edtCalls.incrementAndGet();
                EventQueue.invokeLater(task);
            } else {
                workers.execute(task);
            }
        }

        @Override
        public boolean isForbiddenThread(Thread thread) {
            return false;
        }
    }

    /** The capability registry with the core's declare/set/probe semantics. */
    static final class Caps implements CapabilityRegistry {
        static final String PENDING = "not probed yet: the target is not ready";
        private final Map<String, Capability> map = new TreeMap<String, Capability>();
        private final Map<String, Probe> probes = new LinkedHashMap<String, Probe>();
        private final Map<String, Boolean> pending = new HashMap<String, Boolean>();
        private volatile boolean probesEnabled;
        private volatile FakeAgentContext owner;

        @Override
        public AutoCloseable declare(final String id, final Probe probe) {
            final Object token = new Object();
            boolean now;
            synchronized (this) {
                probes.put(id, probe);
                pending.put(id, Boolean.TRUE);
                map.put(id, new Capability(id, CapabilityState.UNAVAILABLE, PENDING, null));
                now = probesEnabled;
            }
            if (now && owner != null) {
                final FakeAgentContext ctx = owner;
                ctx.threads.workers.execute(new Runnable() {
                    @Override
                    public void run() {
                        probeOne(id, ctx);
                    }
                });
            }
            return new AutoCloseable() {
                @Override
                public void close() {
                    synchronized (Caps.this) {
                        if (probes.get(id) == probe) {
                            undeclare(id);
                        }
                    }
                }
            };
        }

        @Override
        public synchronized void undeclare(String id) {
            map.remove(id);
            probes.remove(id);
            pending.remove(id);
        }

        @Override
        public synchronized void set(String id, CapabilityState state, String reason, String strategy) {
            map.put(id, new Capability(id, state, reason, strategy));
            pending.remove(id);
        }

        @Override
        public synchronized Capability get(String id) {
            return map.get(id);
        }

        @Override
        public synchronized Map<String, Capability> snapshot() {
            return new TreeMap<String, Capability>(map);
        }

        synchronized boolean isPending(String id) {
            return Boolean.TRUE.equals(pending.get(id));
        }

        synchronized boolean declared(String id) {
            return probes.containsKey(id);
        }

        void probeAll(FakeAgentContext ctx) {
            List<String> ids;
            synchronized (this) {
                probesEnabled = true;
                owner = ctx;
                ids = new ArrayList<String>(probes.keySet());
            }
            for (String id : ids) {
                probeOne(id, ctx);
            }
        }

        private void probeOne(String id, FakeAgentContext ctx) {
            Probe probe;
            synchronized (this) {
                probe = probes.get(id);
            }
            if (probe == null) {
                return;
            }
            Capability c;
            try {
                ProbeResult r = probe.run(ctx);
                c = new Capability(id, r.state(), r.reason(), r.strategy());
            } catch (Exception e) {
                c = new Capability(id, CapabilityState.UNAVAILABLE, String.valueOf(e.getMessage()), null);
            }
            synchronized (this) {
                if (probes.get(id) == probe) {
                    map.put(id, c);
                    pending.remove(id);
                }
            }
        }
    }

    static final class Spi implements SpiRegistry {
        private final Map<Class<?>, List<Object>> map = new HashMap<Class<?>, List<Object>>();

        @Override
        public synchronized <T> AutoCloseable register(final Class<T> type, final T implementation) {
            List<Object> list = map.get(type);
            if (list == null) {
                list = new CopyOnWriteArrayList<Object>();
                map.put(type, list);
            }
            list.add(implementation);
            final List<Object> owner = list;
            return new AutoCloseable() {
                @Override
                public void close() {
                    owner.remove(implementation);
                }
            };
        }

        @Override
        public synchronized <T> List<T> all(Class<T> type) {
            List<T> out = new ArrayList<T>();
            List<Object> list = map.get(type);
            if (list != null) {
                for (Object o : list) {
                    out.add(type.cast(o));
                }
            }
            return out;
        }

        @Override
        public <T> T first(Class<T> type) {
            List<T> all = all(type);
            return all.isEmpty() ? null : all.get(0);
        }
    }

    static final class Log implements AgentLog {
        private final List<String> lines = new CopyOnWriteArrayList<String>();

        @Override
        public void info(String msg) {
            lines.add("INFO " + msg);
        }

        @Override
        public void warn(String msg) {
            lines.add("WARN " + msg);
        }

        @Override
        public void warn(String msg, Throwable t) {
            lines.add("WARN " + msg + (t == null ? "" : " " + t));
        }

        @Override
        public void error(String msg, Throwable t) {
            lines.add("ERROR " + msg + (t == null ? "" : " " + t));
        }

        @Override
        public List<String> tail(int n) {
            List<String> all = new ArrayList<String>(lines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }

        List<String> all() {
            return new ArrayList<String>(lines);
        }
    }
}
