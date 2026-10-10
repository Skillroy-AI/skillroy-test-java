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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * An {@link AgentContext} for the hooks module's tests, with the test agent's {@code Instrumentation}, a small event
 * bus, handles, an SPI registry, a capability registry with the core's declare, set and probe semantics, and a
 * dispatcher that applies the core's gates (the capability's NOT_READY and CAPABILITY_UNAVAILABLE, READ_ONLY for a
 * mutating primitive, CONFIRM_REQUIRED for a destructive one without {@code _meta.confirm}). {@link #rollback()} drops
 * what the module registered, as the core's module context does at uninstall.
 */
final class FakeHookContext implements AgentContext {

    final Prims primitives = new Prims();
    final Bus events = new Bus();
    final Handles handles = new Handles();
    final Caps capabilities = new Caps();
    final Spi spi = new Spi();
    final Log log = new Log();
    final Map<String, String> options = new LinkedHashMap<String, String>();
    volatile Instrumentation instrumentation;
    volatile LoadMode loadMode = LoadMode.JAVAAGENT;
    volatile boolean readOnly;
    volatile ClassLoader hostLoader = ClassLoader.getSystemClassLoader();
    /** Extra secret keys (the core's redact option, for the tests); null: the protocol's rule only. */
    volatile Pattern redact;
    /** Every text the module handed to {@link #redactText}, in order (what reaches the wire went through it). */
    final List<String> redacting = new CopyOnWriteArrayList<String>();

    FakeHookContext() {
        this.instrumentation = TestAgent.instrumentation();
    }

    @Override
    public Instrumentation instrumentation() {
        return instrumentation;
    }

    @Override
    public LoadMode loadMode() {
        return loadMode;
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
        return readOnly;
    }

    @Override
    public boolean targetReady() {
        return capabilities.ready;
    }

    @Override
    public boolean isSecretKey(String key) {
        return key != null && (SecretKeys.looksSecret(key) || (redact != null && redact.matcher(key).find()));
    }

    @Override
    public String redactText(String text) {
        if (text == null) {
            return null;
        }
        redacting.add(text);
        // The core's two rules in small: a secret key=value pair's value, and the password of URL user-info.
        return text.replaceAll("(?i)(password|token|secret)=[^\\s;,&]*", "$1=" + SecretKeys.REDACTED)
                .replaceAll("(://[^/?#\\s:@]*:)[^/?#\\s]*@", "$1" + SecretKeys.REDACTED + "@");
    }

    /** The target becomes ready: every declared capability is probed, and later declarations at once. */
    void ready() {
        capabilities.ready(this);
    }

    /** Drops what the module registered (the core's module context at uninstall, A18). */
    void rollback() {
        primitives.clear();
        spi.clear();
        capabilities.clear();
    }

    /** A call through the core's gates, on the calling thread. */
    Object call(String method, Map<String, Object> params) throws Exception {
        return call(method, params, null);
    }

    Object call(String method, Map<String, Object> params, Map<String, Object> meta) throws Exception {
        Primitive p = primitives.find(method);
        if (p == null) {
            throw new RpcException(ErrorCodes.METHOD_NOT_FOUND, "unknown method " + method);
        }
        PrimitiveSpec spec = p.spec();
        String capabilityId = spec.capabilityId();
        if (capabilityId != null) {
            if (capabilities.isPending(capabilityId)) {
                throw new RpcException(ErrorCodes.NOT_READY, "capability " + capabilityId + " not probed yet");
            }
            Capability c = capabilities.get(capabilityId);
            if (c != null && c.state() == CapabilityState.UNAVAILABLE) {
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, capabilityId + " is UNAVAILABLE: "
                        + c.reason());
            }
        }
        if (spec.mutating() && readOnly) {
            throw new RpcException(ErrorCodes.READ_ONLY, method + " changes the target and the agent is read-only");
        }
        if (spec.destructive() && (meta == null || !Boolean.TRUE.equals(meta.get("confirm")))) {
            throw new RpcException(ErrorCodes.CONFIRM_REQUIRED, method + " needs _meta.confirm=true");
        }
        final JsonView args = JsonView.of(params == null ? new LinkedHashMap<String, Object>()
                : (Map<String, Object>) Json.parse(Json.write(params)));
        Object value = p.invoke(new Invocation() {
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
                return FakeHookContext.this;
            }

            @Override
            public long remainingMs() {
                return 60000;
            }
        });
        return Json.parse(Json.write(value));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String method, Map<String, Object> params) throws Exception {
        return (Map<String, Object>) call(method, params);
    }

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

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
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

        synchronized void clear() {
            map.clear();
        }
    }

    static final class Bus implements EventBus {
        private final List<Event> events = new ArrayList<Event>();
        private long seq;

        @Override
        public long publish(String topic, String source, Map<String, Object> payload) {
            synchronized (this) {
                Event e = new Event(++seq, System.currentTimeMillis(), topic, source, null, null, payload);
                events.add(e);
                notifyAll();
                return e.seq();
            }
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
                    if (e.seq() > seqExclusive && filter.matches(e.topic())) {
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

        @Override
        public AutoCloseable subscribe(TopicFilter filter, EventSink sink) {
            return new AutoCloseable() {
                @Override
                public void close() {
                }
            };
        }

        @Override
        public synchronized long oldestSeq() {
            return events.isEmpty() ? 0 : events.get(0).seq();
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

        /** The last event of a topic, or null. */
        synchronized Event last(String topic) {
            List<Event> all = topic(topic);
            return all.isEmpty() ? null : all.get(all.size() - 1);
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
            Object o = byHandle.get(handle);
            if (o == null) {
                throw new RpcException(ErrorCodes.STALE_HANDLE, "handle " + handle + " is unknown");
            }
            return o;
        }

        @Override
        public <T> T resolve(String handle, Class<T> type) {
            return type.cast(resolve(handle));
        }

        @Override
        public synchronized void release(String handle) {
            byObject.remove(byHandle.remove(handle));
        }
    }

    /** The capability registry with the core's declare/set/probe semantics. */
    static final class Caps implements CapabilityRegistry {
        static final String PENDING = "not probed yet: the target is not ready";
        private final Map<String, Capability> map = new TreeMap<String, Capability>();
        private final Map<String, Probe> probes = new LinkedHashMap<String, Probe>();
        private final Map<String, Boolean> pending = new HashMap<String, Boolean>();
        volatile boolean ready;
        private volatile FakeHookContext owner;

        @Override
        public AutoCloseable declare(final String id, final Probe probe) {
            synchronized (this) {
                probes.put(id, probe);
                pending.put(id, Boolean.TRUE);
                map.put(id, new Capability(id, CapabilityState.UNAVAILABLE, PENDING, null));
            }
            if (ready && owner != null) {
                probeOne(id, owner);
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

        void ready(FakeHookContext ctx) {
            owner = ctx;
            ready = true;
            reprobe(ctx);
        }

        /** {@code agent.capabilities {reprobe: true}}. */
        void reprobe(FakeHookContext ctx) {
            List<String> ids;
            synchronized (this) {
                ids = new ArrayList<String>(probes.keySet());
            }
            for (String id : ids) {
                probeOne(id, ctx);
            }
        }

        private void probeOne(String id, FakeHookContext ctx) {
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

        synchronized void clear() {
            map.clear();
            probes.clear();
            pending.clear();
        }
    }

    static final class Spi implements SpiRegistry {
        private final Map<Class<?>, List<Object>> map = new HashMap<Class<?>, List<Object>>();

        @Override
        public synchronized <T> AutoCloseable register(Class<T> type, final T implementation) {
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

        synchronized void clear() {
            map.clear();
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
