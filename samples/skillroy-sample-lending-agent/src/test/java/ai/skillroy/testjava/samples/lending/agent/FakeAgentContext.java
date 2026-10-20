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

package ai.skillroy.testjava.samples.lending.agent;

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
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * An in-memory {@link AgentContext} for the lending module's tests: real registries, an event bus that tests can wait
 * on, a thread broker that honours {@code ThreadPolicy.APP} through the registered {@link ThreadPolicyProvider} (and
 * its forbidden-thread rule), and a recording log. No HTTP, no handles, no EDT.
 */
final class FakeAgentContext implements AgentContext {

    final Primitives primitives = new Primitives();
    final Spi spi = new Spi();
    final Capabilities capabilities = new Capabilities();
    final Bus events = new Bus();
    final Log log = new Log();
    final Broker threads = new Broker();
    private final Object hostContext;
    private final LoadMode loadMode;

    FakeAgentContext(Object hostContext, LoadMode loadMode) {
        this.hostContext = hostContext;
        this.loadMode = loadMode;
    }

    @Override
    public Instrumentation instrumentation() {
        return null;
    }

    @Override
    public LoadMode loadMode() {
        return loadMode;
    }

    @Override
    public Map<String, String> options() {
        return Collections.emptyMap();
    }

    @Override
    public ClassLoader hostClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public Object hostContext() {
        return hostContext;
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
        throw new UnsupportedOperationException("not used by the lending module");
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
        return false;
    }

    @Override
    public boolean targetReady() {
        return true;
    }

    /** Invokes a primitive the way the dispatcher does: under its thread policy, bounded by its timeout. */
    Object call(String name, Map<String, Object> args) throws Exception {
        return call(name, args, java.util.Collections.<String>emptyList());
    }

    /**
     * The warnings the primitives raised through {@code Invocation.warn}, in order, each {@code {code, message,
     * data}} (phase8-contracts.md 7.1, ES); a test clears it.
     */
    final List<Map<String, Object>> warnings = java.util.Collections.synchronizedList(
            new ArrayList<Map<String, Object>>());

    /**
     * {@link #call(String, Map)} with the parameters the caller marked secret ({@code Invocation.secretParams()},
     * amendment A34; phase8-contracts.md 7.2, ES).
     */
    Object call(String name, Map<String, Object> args, final List<String> secretParams) throws Exception {
        final Primitive p = primitives.find(name);
        if (p == null) {
            throw new IllegalStateException("no primitive " + name);
        }
        final PrimitiveSpec spec = p.spec();
        final Invocation inv = invocation(args, spec.timeoutMs(), secretParams);
        Callable<Object> body = new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return p.invoke(inv);
            }
        };
        if (spec.thread() == ThreadPolicy.APP_QUIESCENT) {
            // phase8-contracts.md 4.1: at the provider's quiescent point, admitted by the spec's budget.
            return threads.callQuiescent(body, spec.quiescentBudgetMs(), spec.timeoutMs());
        }
        if (spec.thread() == ThreadPolicy.LANE) {
            return threads.callOnLane(spec.lane(), body, spec.timeoutMs());
        }
        return threads.call(spec.thread(), body, spec.timeoutMs());
    }

    /** Invokes and expects an {@link RpcException}. */
    RpcException fails(String name, Map<String, Object> args) throws Exception {
        try {
            Object value = call(name, args);
            throw new AssertionError("expected an RpcException from " + name + ", got " + value);
        } catch (RpcException expected) {
            return expected;
        }
    }

    Invocation invocation(final Map<String, Object> args, final long remainingMs) {
        return invocation(args, remainingMs, java.util.Collections.<String>emptyList());
    }

    Invocation invocation(final Map<String, Object> args, final long remainingMs, final List<String> secretParams) {
        final AgentContext ctx = this;
        return new Invocation() {
            @Override
            public List<String> secretParams() {
                return secretParams;
            }

            @Override
            public void warn(String code, String message, Map<String, Object> data) {
                if (!ai.skillroy.testjava.protocol.CallWarning.isValidCode(code)) {
                    throw new IllegalArgumentException("not a warning code: " + code);
                }
                Map<String, Object> warning = new LinkedHashMap<String, Object>();
                warning.put("code", code);
                warning.put("message", message);
                warning.put("data", data);
                warnings.add(warning);
            }

            @Override
            public JsonView args() {
                return JsonView.of(args);
            }

            @Override
            public String correlationId() {
                return "c-test";
            }

            @Override
            public String traceparent() {
                return null;
            }

            @Override
            public AgentContext context() {
                return ctx;
            }

            @Override
            public long remainingMs() {
                return remainingMs;
            }
        };
    }

    /** {@code args("k", v, "k2", v2)}: an insertion-ordered map. */
    static Map<String, Object> args(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** Runs every declared probe, as the core does after the target is ready. */
    void probeAll() {
        capabilities.probeAll(this);
    }

    // ------------------------------------------------------------------ registries

    static final class Primitives implements PrimitiveRegistry {
        private final Map<String, Primitive> byName = new TreeMap<String, Primitive>();

        @Override
        public synchronized void register(Primitive primitive) {
            String name = primitive.spec().name();
            if (byName.containsKey(name)) {
                throw new IllegalStateException("duplicate primitive " + name);
            }
            byName.put(name, primitive);
        }

        @Override
        public synchronized void unregister(String name) {
            byName.remove(name);
        }

        @Override
        public synchronized Primitive find(String name) {
            return byName.get(name);
        }

        @Override
        public synchronized List<PrimitiveSpec> list() {
            List<PrimitiveSpec> specs = new ArrayList<PrimitiveSpec>();
            for (Primitive p : byName.values()) {
                specs.add(p.spec());
            }
            return specs;
        }
    }

    static final class Spi implements SpiRegistry {
        private final Map<Class<?>, List<Object>> byType = new LinkedHashMap<Class<?>, List<Object>>();

        @Override
        public synchronized <T> AutoCloseable register(Class<T> type, T implementation) {
            final List<Object> list = listFor(type);
            final Object impl = implementation;
            list.add(impl);
            return new AutoCloseable() {
                @Override
                public void close() {
                    list.remove(impl);
                }
            };
        }

        @Override
        public synchronized <T> List<T> all(Class<T> type) {
            List<T> result = new ArrayList<T>();
            for (Object o : listFor(type)) {
                result.add(type.cast(o));
            }
            return result;
        }

        @Override
        public synchronized <T> T first(Class<T> type) {
            List<T> all = all(type);
            return all.isEmpty() ? null : all.get(0);
        }

        synchronized int count() {
            int n = 0;
            for (List<Object> list : byType.values()) {
                n += list.size();
            }
            return n;
        }

        private List<Object> listFor(Class<?> type) {
            List<Object> list = byType.get(type);
            if (list == null) {
                list = new CopyOnWriteArrayList<Object>();
                byType.put(type, list);
            }
            return list;
        }
    }

    static final class Capabilities implements CapabilityRegistry {
        private final Map<String, Probe> probes = new TreeMap<String, Probe>();
        private final Map<String, Capability> states = new TreeMap<String, Capability>();

        @Override
        public synchronized AutoCloseable declare(final String id, final Probe probe) {
            probes.put(id, probe);
            states.put(id, new Capability(id, CapabilityState.UNAVAILABLE, "not probed yet", null));
            return new AutoCloseable() {
                @Override
                public void close() {
                    synchronized (Capabilities.this) {
                        if (probes.get(id) == probe) {
                            undeclare(id);
                        }
                    }
                }
            };
        }

        @Override
        public synchronized void undeclare(String id) {
            probes.remove(id);
            states.remove(id);
        }

        @Override
        public synchronized void set(String id, CapabilityState state, String reason, String strategy) {
            states.put(id, new Capability(id, state, reason, strategy));
        }

        @Override
        public synchronized Capability get(String id) {
            return states.get(id);
        }

        @Override
        public synchronized Map<String, Capability> snapshot() {
            return new TreeMap<String, Capability>(states);
        }

        synchronized Map<String, Probe> probes() {
            return new TreeMap<String, Probe>(probes);
        }

        void probeAll(AgentContext ctx) {
            for (Map.Entry<String, Probe> e : probes().entrySet()) {
                ProbeResult r;
                try {
                    r = e.getValue().run(ctx);
                } catch (Exception ex) {
                    r = ProbeResult.unavailable(ex.toString());
                }
                set(e.getKey(), r.state(), r.reason(), r.strategy());
            }
        }
    }

    /** A bounded in-memory event log with blocking waits, like the core's ring bus. */
    static final class Bus implements EventBus {
        private final List<Event> events = new ArrayList<Event>();
        private long seq;

        @Override
        public synchronized long publish(String topic, String source, Map<String, Object> payload) {
            return publish(topic, source, payload, null);
        }

        /** phase8-contracts.md 7.4 (ES): the given correlation id is stamped, as the core's bus does. */
        @Override
        public synchronized long publish(String topic, String source, Map<String, Object> payload,
                                         String correlationId) {
            seq++;
            events.add(new Event(seq, System.currentTimeMillis(), topic, source, correlationId, null, payload));
            publishers.add(Thread.currentThread().getName());
            notifyAll();
            return seq;
        }

        /** The name of the thread that published each event, in order (ES). */
        final List<String> publishers = new ArrayList<String>();

        @Override
        public synchronized long lastSeq() {
            return seq;
        }

        @Override
        public synchronized List<Event> since(long seqExclusive, TopicFilter filter, int limit) {
            List<Event> out = new ArrayList<Event>();
            for (Event e : events) {
                if (e.seq() > seqExclusive && (filter == null || filter.matches(e.topic())) && out.size() < limit) {
                    out.add(e);
                }
            }
            return out;
        }

        @Override
        public synchronized Event await(long seqExclusive, TopicFilter filter, Map<String, Object> payloadMatch,
                                        long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                for (Event e : events) {
                    if (e.seq() > seqExclusive && (filter == null || filter.matches(e.topic()))
                            && matches(e, payloadMatch)) {
                        return e;
                    }
                }
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return null;
                }
                wait(remaining);
            }
        }

        /** Waits for the first event on {@code topic} after {@code since}; fails the test on timeout. */
        Event next(String topic, long since) throws InterruptedException {
            Event e = await(since, TopicFilter.parse(topic), null, 10000);
            if (e == null) {
                throw new AssertionError("no " + topic + " event after seq " + since + "; events: " + all());
            }
            return e;
        }

        private static boolean matches(Event e, Map<String, Object> match) {
            if (match == null) {
                return true;
            }
            for (Map.Entry<String, Object> m : match.entrySet()) {
                if (!String.valueOf(m.getValue()).equals(String.valueOf(e.payload().get(m.getKey())))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public AutoCloseable subscribe(TopicFilter filter, EventSink sink) {
            throw new UnsupportedOperationException("not used by the lending module");
        }

        @Override
        public synchronized long oldestSeq() {
            return events.isEmpty() ? 0 : events.get(0).seq();
        }

        synchronized List<Event> all() {
            return new ArrayList<Event>(events);
        }

        synchronized List<String> topics() {
            List<String> out = new ArrayList<String>();
            for (Event e : events) {
                out.add(e.topic());
            }
            return out;
        }
    }

    /** CALLER/WORKER/EDT run inline; APP goes through the first registered ThreadPolicyProvider. */
    final class Broker implements ThreadBroker {
        @Override
        public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
            if (isForbiddenThread(Thread.currentThread())) {
                throw new RpcException(ErrorCodes.FORBIDDEN_THREAD, "forbidden thread "
                        + Thread.currentThread().getName());
            }
            ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
            if (policy == ThreadPolicy.APP && provider != null) {
                return provider.callOnAppThread(task, timeoutMs);
            }
            return task.call();
        }

        @Override
        public void post(ThreadPolicy policy, Runnable task) {
            task.run();
        }

        @Override
        public boolean isForbiddenThread(Thread thread) {
            for (ThreadPolicyProvider p : spi.all(ThreadPolicyProvider.class)) {
                if (p.isForbidden(thread)) {
                    return true;
                }
            }
            return false;
        }

        /** Lanes declared through this broker (phase8-contracts.md 4.2), by name. */
        final Map<String, java.util.concurrent.ExecutorService> lanes =
                new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ExecutorService>();

        /**
         * At the first provider's quiescent point, as the core does it (minus the overrun penalty): the point's own
         * admission, refusals and timeout (phase8-contracts.md 4.1).
         */
        @Override
        public <T> T callQuiescent(Callable<T> task, long budgetMs, long timeoutMs) throws Exception {
            ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
            ai.skillroy.testjava.agent.api.QuiescentPoint point = provider == null ? null
                    : provider.quiescentPoint();
            if (point == null) {
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "no quiescent point offered");
            }
            return point.await(task, budgetMs, timeoutMs);
        }

        @Override
        public ai.skillroy.testjava.agent.api.Lane declareLane(final String name, final int threads,
                                                                final int queue) {
            final java.util.concurrent.atomic.AtomicInteger number = new java.util.concurrent.atomic.AtomicInteger();
            if (lanes.containsKey(name)) {
                throw new IllegalStateException("lane " + name + " is already declared");
            }
            lanes.put(name, java.util.concurrent.Executors.newFixedThreadPool(threads,
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "skillroy-lane-" + name + "-" + number.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    }));
            return new ai.skillroy.testjava.agent.api.Lane() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public int threads() {
                    return threads;
                }

                @Override
                public int queue() {
                    return queue;
                }

                @Override
                public void close() {
                    java.util.concurrent.ExecutorService pool = lanes.remove(name);
                    if (pool != null) {
                        pool.shutdownNow();
                    }
                }
            };
        }

        /** On the lane's threads, bounded, the task interrupted on expiry (phase8-contracts.md 4.2). */
        @Override
        public <T> T callOnLane(String lane, Callable<T> task, long timeoutMs) throws Exception {
            java.util.concurrent.ExecutorService pool = lanes.get(lane);
            if (pool == null) {
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "unknown lane: " + lane);
            }
            java.util.concurrent.Future<T> future = pool.submit(task);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                throw new RpcException(ErrorCodes.TIMEOUT, "timed out after " + timeoutMs + " ms");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw e;
            }
        }
    }

    static final class Log implements AgentLog {
        final List<String> lines = new CopyOnWriteArrayList<String>();

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
            lines.add("WARN " + msg + " " + t);
        }

        @Override
        public void error(String msg, Throwable t) {
            lines.add("ERROR " + msg + " " + t);
        }

        @Override
        public List<String> tail(int n) {
            List<String> all = new ArrayList<String>(lines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }
}
