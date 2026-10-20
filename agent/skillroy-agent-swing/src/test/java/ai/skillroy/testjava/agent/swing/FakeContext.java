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

package ai.skillroy.testjava.agent.swing;

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
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PayloadMatch;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.awt.EventQueue;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
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
 * A small in-test {@link AgentContext}: map-backed registries, a list-backed event bus, a thread broker that runs
 * CALLER/EDT_SYNC/EDT_ASYNC/WORKER the way the contract describes, and an identity, weakly held handle registry.
 * {@link #call} plays the dispatcher: capability gate, timeout, and a check that the result is plain JSON.
 */
final class FakeContext implements AgentContext {

    final Prims primitives = new Prims();
    final Bus events = new Bus();
    final Handles handles = new Handles();
    final Broker threads = new Broker();
    final Caps capabilities = new Caps();
    final Spi spi = new Spi();
    final Log log = new Log();
    /** The start-time options ({@code AgentContext.options()}); a test puts {@code robot=true} here before install. */
    final Map<String, String> options = new java.util.concurrent.ConcurrentHashMap<String, String>();

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
        return Collections.unmodifiableMap(new HashMap<String, String>(options));
    }

    @Override
    public ClassLoader hostClassLoader() {
        return getClass().getClassLoader();
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

    /** {@code AgentContext.option} (amendment A20): no options here, so the system property alone. */
    @Override
    public String option(String key) {
        return System.getProperty("skillroy.agent." + key);
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
        return false;
    }

    @Override
    public boolean targetReady() {
        return true;
    }

    /** Calls a primitive like the dispatcher with its spec timeout. */
    Map<String, Object> call(String method, Map<String, Object> params) throws Exception {
        return call(method, params, 0);
    }

    /** Calls a primitive like the dispatcher; {@code timeoutMs <= 0} = the spec's timeout. */
    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, Map<String, Object> params, long timeoutMs) throws Exception {
        Object v = callValue(method, params, timeoutMs);
        return (Map<String, Object>) v;
    }

    Object callValue(String method, Map<String, Object> params, long timeoutMs) throws Exception {
        final Primitive p = primitives.find(method);
        if (p == null) {
            throw new RpcException(ErrorCodes.METHOD_NOT_FOUND, method);
        }
        final PrimitiveSpec spec = p.spec();
        if (spec.capabilityId() != null) {
            Capability cap = capabilities.get(spec.capabilityId());
            if (cap != null && cap.state() == CapabilityState.UNAVAILABLE) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("reason", cap.reason());
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, spec.capabilityId(), data);
            }
        }
        final long timeout = timeoutMs > 0 ? timeoutMs : spec.timeoutMs();
        final long deadline = System.currentTimeMillis() + timeout;
        final JsonView args = JsonView.of(params == null ? new LinkedHashMap<String, Object>() : params);
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
                return FakeContext.this;
            }

            @Override
            public long remainingMs() {
                return Math.max(0, deadline - System.currentTimeMillis());
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
                }, timeout);
            }
        });
        Object value;
        try {
            value = f.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new RpcException(ErrorCodes.TIMEOUT, method + " timed out after " + timeout + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
        JsonCheck.requirePlainJson(value, method);
        new RpcResult(value, Collections.<Event>emptyList(), 0, 0, 0, "test"); // the wire must accept it
        return value;
    }

    void shutdown() {
        callers.shutdownNow();
        threads.workers.shutdownNow();
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
        public synchronized Event await(long seqExclusive, TopicFilter filter, Map<String, Object> payloadMatch,
                                        long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                for (Event e : events) {
                    if (e.seq() > seqExclusive && (filter == null || filter.matches(e.topic()))
                            && matches(e, payloadMatch)) {
                        return e;
                    }
                }
                long left = deadline - System.currentTimeMillis();
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
    }

    static final class Handles implements HandleRegistry {
        private final Map<Object, String> byObject = new WeakHashMap<Object, String>();
        private final Map<String, WeakReference<Object>> byHandle = new HashMap<String, WeakReference<Object>>();
        private int next;

        @Override
        public synchronized String handleFor(Object object, String kind) {
            String h = byObject.get(object);
            if (h == null) {
                h = "h" + (++next);
                byObject.put(object, h);
                byHandle.put(h, new WeakReference<Object>(object));
            }
            return h;
        }

        @Override
        public synchronized Object resolve(String handle) {
            WeakReference<Object> ref = byHandle.get(handle);
            Object o = ref == null ? null : ref.get();
            if (o == null) {
                throw new RpcException(ErrorCodes.STALE_HANDLE, "stale handle " + handle);
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
            WeakReference<Object> ref = byHandle.remove(handle);
            Object o = ref == null ? null : ref.get();
            if (o != null) {
                byObject.remove(o);
            }
        }
    }

    final class Broker implements ThreadBroker {
        final ExecutorService workers = Executors.newCachedThreadPool(daemon("skillroy-test-worker"));
        /**
         * The index (from 0) of the one {@code EDT_ASYNC} call that is refused with {@code FORBIDDEN_THREAD}, once the
         * tasks queued before it have run; -1 for none (the fix wave's L2-F7 test).
         */
        volatile int failEdtAsyncCall = -1;
        final AtomicInteger edtAsyncCalls = new AtomicInteger();
        /** When true, a {@code WORKER} post is refused and logged, as a full pool's would be (L2-F8's test). */
        volatile boolean refuseWorkerPosts;

        @Override
        public <T> T call(ThreadPolicy policy, final Callable<T> task, long timeoutMs) throws Exception {
            switch (policy) {
                case CALLER:
                    return task.call();
                case EDT_SYNC:
                    if (EventQueue.isDispatchThread()) {
                        return task.call();
                    }
                    FutureTask<T> ft = new FutureTask<T>(task);
                    EventQueue.invokeLater(ft);
                    return await(ft, timeoutMs, "EDT");
                case EDT_ASYNC:
                    if (edtAsyncCalls.getAndIncrement() == failEdtAsyncCall) {
                        if (!EventQueue.isDispatchThread()) {
                            EventQueue.invokeAndWait(new Runnable() {
                                @Override
                                public void run() {
                                    // the tasks queued before this one have run
                                }
                            });
                        }
                        throw new RpcException(ErrorCodes.FORBIDDEN_THREAD, "test: the post is refused");
                    }
                    EventQueue.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                task.call();
                            } catch (Throwable t) {
                                log.warn("broker: posted task failed: " + t, t);
                            }
                        }
                    });
                    return null;
                default:
                    return await(workers.submit(task), timeoutMs, "worker");
            }
        }

        private <T> T await(Future<T> f, long timeoutMs, String where) throws Exception {
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(false);
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
            if (policy == ThreadPolicy.WORKER && refuseWorkerPosts) {
                log.warn("broker: post refused (test): the worker pool is full", null);
                return;
            }
            try {
                call(policy, new Callable<Object>() {
                    @Override
                    public Object call() {
                        task.run();
                        return null;
                    }
                }, 10000);
            } catch (Exception e) {
                log.warn("broker: post failed: " + e, e);
            }
        }

        @Override
        public boolean isForbiddenThread(Thread thread) {
            return false;
        }
    }

    /** The capability registry: declared capabilities start UNAVAILABLE("not probed"); nothing is probed. */
    static final class Caps implements CapabilityRegistry {
        private final Map<String, Capability> map = new TreeMap<String, Capability>();

        /** Declares {@code id}; the handle undeclares it. */
        @Override
        public synchronized AutoCloseable declare(final String id, Probe probe) {
            if (!map.containsKey(id)) {
                map.put(id, new Capability(id, CapabilityState.UNAVAILABLE, "not probed", null));
            }
            return new AutoCloseable() {
                @Override
                public void close() {
                    undeclare(id);
                }
            };
        }

        @Override
        public synchronized void undeclare(String id) {
            map.remove(id);
        }

        @Override
        public synchronized void set(String id, CapabilityState state, String reason, String strategy) {
            map.put(id, new Capability(id, state, reason, strategy));
        }

        @Override
        public synchronized Capability get(String id) {
            return map.get(id);
        }

        @Override
        public synchronized Map<String, Capability> snapshot() {
            return new TreeMap<String, Capability>(map);
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
            lines.add("WARN " + msg);
        }

        @Override
        public void error(String msg, Throwable t) {
            lines.add("ERROR " + msg);
        }

        @Override
        public List<String> tail(int n) {
            List<String> all = new ArrayList<String>(lines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }

        List<String> warnings() {
            List<String> out = new ArrayList<String>();
            for (String l : lines) {
                if (!l.startsWith("INFO")) {
                    out.add(l);
                }
            }
            return out;
        }
    }
}
