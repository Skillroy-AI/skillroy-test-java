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

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.CapabilityRegistry;
import ai.skillroy.testjava.agent.api.EventBus;
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
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcException;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory AgentContext for module tests: real registries, a recording log, no event bus or handles.
 */
final class FakeAgentContext implements AgentContext {

    final Primitives primitives = new Primitives();
    final Spi spi = new Spi();
    final Capabilities capabilities = new Capabilities();
    final Log log = new Log();

    /** Remaining time each invocation reports. */
    volatile long remainingMs = 10000L;

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
        return Collections.emptyMap();
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
        throw new UnsupportedOperationException("not used by the data module");
    }

    @Override
    public HandleRegistry handles() {
        throw new UnsupportedOperationException("not used by the data module");
    }

    @Override
    public ThreadBroker threads() {
        return new ThreadBroker() {
            @Override
            public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
                return task.call();
            }

            @Override
            public void post(ThreadPolicy policy, Runnable task) {
                task.run();
            }

            @Override
            public boolean isForbiddenThread(Thread thread) {
                return false;
            }
        };
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

    /** Invokes a registered primitive the way the dispatcher would (minus threading and the wire). */
    Object call(String primitive, Map<String, Object> args) throws Exception {
        Primitive p = primitives.find(primitive);
        if (p == null) {
            throw new IllegalStateException("no primitive " + primitive);
        }
        return p.invoke(invocation(args));
    }

    /** Invokes and expects an RpcException. */
    RpcException fails(String primitive, Map<String, Object> args) throws Exception {
        try {
            Object value = call(primitive, args);
            throw new AssertionError("expected an RpcException, got " + value);
        } catch (RpcException expected) {
            return expected;
        }
    }

    Invocation invocation(final Map<String, Object> args) {
        final AgentContext ctx = this;
        return new Invocation() {
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
            return () -> list.remove(impl);
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

        private List<Object> listFor(Class<?> type) {
            List<Object> list = byType.get(type);
            if (list == null) {
                list = new CopyOnWriteArrayList<Object>();
                byType.put(type, list);
            }
            return list;
        }
    }

    /** The capability registry modules see, with test hooks to inspect declarations and run probes. */
    final class Capabilities implements CapabilityRegistry {
        private final Map<String, Probe> probes = new TreeMap<String, Probe>();
        private final Map<String, Capability> states = new TreeMap<String, Capability>();
        /** When set, {@link #declare} throws it instead of declaring. */
        volatile RuntimeException declareFailure;

        /** Declares {@code id}; the handle undeclares it. */
        @Override
        public synchronized AutoCloseable declare(final String id, Probe probe) {
            if (declareFailure != null) {
                throw declareFailure;
            }
            probes.put(id, probe);
            return new AutoCloseable() {
                @Override
                public void close() {
                    undeclare(id);
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

        boolean declared(String id) {
            return probes.containsKey(id);
        }

        /** Runs the declared probe the way the core does: an exception is UNAVAILABLE(message). */
        Capability probe(String id) {
            Probe probe;
            synchronized (this) {
                probe = probes.get(id);
            }
            ProbeResult result;
            try {
                result = probe.run(FakeAgentContext.this);
            } catch (Exception e) {
                result = ProbeResult.unavailable(e.getMessage());
            }
            set(id, result.state(), result.reason(), result.strategy());
            return get(id);
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

        String all() {
            return String.join("\n", lines);
        }
    }
}
