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
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.TopicFilter;

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

/**
 * An in-memory {@link AgentContext} for the Byteman module's tests: real registries, an event bus that keeps what is
 * published (checked to be plain JSON), handles per object identity, the test agent's {@code Instrumentation} (or none),
 * options as given. {@link #call} plays the dispatcher's capability gate and nothing else (its confirm and read-only
 * gates are the core's and are proven by {@code BytemanIT}).
 */
final class FakeAgentContext implements AgentContext {

    final Map<String, String> options = new LinkedHashMap<String, String>();
    final Primitives primitives = new Primitives();
    final Spi spi = new Spi();
    final Capabilities capabilities = new Capabilities();
    final Bus bus = new Bus();
    final Handles handles = new Handles();
    final Log log = new Log();
    private final Instrumentation instrumentation;
    private final LoadMode mode;

    FakeAgentContext(Instrumentation instrumentation) {
        this(instrumentation, instrumentation == null ? LoadMode.EMBEDDED : LoadMode.JAVAAGENT);
    }

    FakeAgentContext(Instrumentation instrumentation, LoadMode mode) {
        this.instrumentation = instrumentation;
        this.mode = mode;
    }

    @Override
    public Instrumentation instrumentation() {
        return instrumentation;
    }

    @Override
    public LoadMode loadMode() {
        return mode;
    }

    @Override
    public Map<String, String> options() {
        return Collections.unmodifiableMap(options);
    }

    @Override
    public ClassLoader hostClassLoader() {
        return ClassLoader.getSystemClassLoader();
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
        return bus;
    }

    @Override
    public HandleRegistry handles() {
        return handles;
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

    @Override
    public boolean isSecretKey(String key) {
        return key != null && SecretKeys.looksSecret(key);
    }

    /** Calls a primitive with the capability gate of the dispatcher (UNAVAILABLE → CAPABILITY_UNAVAILABLE). */
    Object call(String primitive, Map<String, Object> args) throws Exception {
        Primitive p = primitives.find(primitive);
        if (p == null) {
            throw new IllegalStateException("no primitive " + primitive);
        }
        String cap = p.spec().capabilityId();
        if (cap != null) {
            Capability c = capabilities.get(cap);
            if (c == null) {
                c = capabilities.probe(cap);
            }
            if (c != null && c.state() == CapabilityState.UNAVAILABLE) {
                throw new RpcException(-32011, "capability " + cap + " is UNAVAILABLE: " + c.reason());
            }
        }
        Object value = p.invoke(invocation(args));
        Json.parse(Json.write(value));
        return value;
    }

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
                return 30000;
            }
        };
    }

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
            byName.put(primitive.spec().name(), primitive);
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
        private final Map<Class<?>, List<Object>> byType = new HashMap<Class<?>, List<Object>>();

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

        private List<Object> listFor(Class<?> type) {
            List<Object> list = byType.get(type);
            if (list == null) {
                list = new CopyOnWriteArrayList<Object>();
                byType.put(type, list);
            }
            return list;
        }
    }

    final class Capabilities implements CapabilityRegistry {
        private final Map<String, Probe> probes = new TreeMap<String, Probe>();
        private final Map<String, Capability> states = new TreeMap<String, Capability>();

        @Override
        public synchronized AutoCloseable declare(final String id, Probe probe) {
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

        synchronized boolean declared(String id) {
            return probes.containsKey(id);
        }

        /** Runs the declared probe as the core does (an exception is UNAVAILABLE) and records the state. */
        Capability probe(String id) {
            Probe probe;
            synchronized (this) {
                probe = probes.get(id);
            }
            if (probe == null) {
                return get(id);
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

    static final class Bus implements EventBus {
        final List<Event> events = new CopyOnWriteArrayList<Event>();
        private long seq;

        @Override
        public long publish(String topic, String source, Map<String, Object> payload) {
            Json.parse(Json.write(payload));
            Event e;
            synchronized (this) {
                e = new Event(++seq, System.currentTimeMillis(), topic, source, null, null, payload);
            }
            events.add(e);
            return e.seq();
        }

        @Override
        public synchronized long lastSeq() {
            return seq;
        }

        @Override
        public List<Event> since(long seqExclusive, TopicFilter filter, int limit) {
            List<Event> out = new ArrayList<Event>();
            for (Event e : events) {
                if (e.seq() > seqExclusive && filter.matches(e.topic()) && out.size() < limit) {
                    out.add(e);
                }
            }
            return out;
        }

        @Override
        public Event await(long seqExclusive, TopicFilter filter, Map<String, Object> payloadMatch, long timeoutMs) {
            List<Event> found = since(seqExclusive, filter, 1);
            return found.isEmpty() ? null : found.get(0);
        }

        @Override
        public AutoCloseable subscribe(TopicFilter filter, EventSink sink) {
            return new AutoCloseable() {
                @Override
                public void close() {
                }
            };
        }

        @Override
        public long oldestSeq() {
            return 1;
        }

        /** The payloads published under {@code topic}, in order. */
        List<Map<String, Object>> payloads(String topic) {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Event e : events) {
                if (e.topic().equals(topic)) {
                    out.add(e.payload());
                }
            }
            return out;
        }
    }

    static final class Handles implements HandleRegistry {
        private final Map<Object, String> byObject = new IdentityHashMap<Object, String>();
        private final Map<String, Object> byHandle = new HashMap<String, Object>();
        private int next;

        @Override
        public synchronized String handleFor(Object object, String kind) {
            String h = byObject.get(object);
            if (h == null) {
                h = "h" + (++next);
                byObject.put(object, h);
                byHandle.put(h, object);
            }
            return h;
        }

        @Override
        public synchronized Object resolve(String handle) {
            return byHandle.get(handle);
        }

        @Override
        public <T> T resolve(String handle, Class<T> type) {
            return type.cast(resolve(handle));
        }

        @Override
        public synchronized void release(String handle) {
            Object o = byHandle.remove(handle);
            if (o != null) {
                byObject.remove(o);
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

        String all() {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
