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

import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.agent.api.spi.DeviceAdapter;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.Topics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The core primitives of phase1-contracts.md 5 ({@code agent.*}, {@code jvm.*}, {@code sync.await},
 * {@code evt.query}, {@code trace.mark}, {@code app.state}, {@code device.*}). All run on the control-channel thread
 * (CALLER) unless the contract says otherwise.
 *
 * <p>{@code jvm.info} ({@code inputArguments}, {@code mainCommand}), {@code jvm.properties} and
 * {@code agent.info.options} pass every value through the {@link Redactor} (contract amendment A5). None of them
 * touches AWT: {@code jvm.info.headless} is the {@code java.awt.headless} property as a string, or null
 * (amendment A4).
 *
 * <p>Stateless apart from the runtime they read; safe for concurrent calls.
 */
final class CorePrimitives {

    static final int MAX_PROPERTY_NAMES = 50;
    static final int MAX_QUERY_LIMIT = 1000;
    static final long MAX_AWAIT_MS = 600000L;
    private static final String SOURCE = "core";

    private final AgentRuntime runtime;
    private final CoreContext ctx;
    private final Redactor redactor;

    CorePrimitives(AgentRuntime runtime, CoreContext ctx, Redactor redactor) {
        this.runtime = runtime;
        this.ctx = ctx;
        this.redactor = redactor;
    }

    void registerAll() {
        reg(PrimitiveSpec.builder("agent.info").description("Agent identity, load mode, modules and options."),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return agentInfo();
                    }
                });
        reg(PrimitiveSpec.builder("agent.capabilities")
                        .description("The capability matrix; reprobe re-runs every probe once the target is ready.")
                        .param("reprobe", "boolean", false, "re-run the probes first")
                        .timeoutMs(60000L),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        if (inv.args().bool("reprobe", false) && runtime.isReady()) {
                            ctx.capabilities.probeAll();
                        }
                        return ctx.capabilities.toJson();
                    }
                });
        reg(PrimitiveSpec.builder("agent.log").description("The newest lines of the agent log.")
                        .param("n", "integer", false, "number of lines (default 100, at most "
                                + RingLog.CAPACITY + ")"),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        long n = inv.args().num("n", 100L);
                        if (n < 0) {
                            throw invalid("n must not be negative", "n");
                        }
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        out.put("lines", new ArrayList<Object>(ctx.log.tail((int) Math.min(n, RingLog.CAPACITY))));
                        return out;
                    }
                });
        reg(PrimitiveSpec.builder("agent.disable").destructive()
                        .description("Stops the agent after replying: modules uninstall, the server stops, discovery "
                                + "is removed. The application keeps running."),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        runtime.requestDisable();
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        out.put("stopping", Boolean.TRUE);
                        return out;
                    }
                });
        reg(PrimitiveSpec.builder("jvm.info").description("Facts about the target JVM; secret-looking argument values "
                        + "are " + Redactor.REDACTED + " and headless is the java.awt.headless property (or null)."),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return jvmInfo();
                    }
                });
        reg(PrimitiveSpec.builder("jvm.threads").description("Live threads, optionally with stacks.")
                        .param("stacks", "boolean", false, "include stack frames")
                        .param("nameContains", "string", false, "only threads whose name contains this")
                        .param("maxDepth", "integer", false, "frames per stack (default 20)"),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return threads(inv.args());
                    }
                });
        reg(PrimitiveSpec.builder("jvm.properties")
                        .description("The named system properties only (at most " + MAX_PROPERTY_NAMES
                                + "); never a dump, because system properties can hold secrets. A present property "
                                + "with a secret-looking name is " + Redactor.REDACTED + "; an absent one is null.")
                        .param("names", "array", true, "property names"),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return properties(inv.args());
                    }
                });
        ctx.primitives.register(new SyncAwait());
        reg(PrimitiveSpec.builder("evt.query").description("Events from the ring, oldest first.")
                        .param("since", "integer", false, "events with a higher seq (default 0)")
                        .param("topics", "string|array", false, "topic globs")
                        .param("limit", "integer", false, "default 200, at most " + MAX_QUERY_LIMIT),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return query(inv.args());
                    }
                });
        reg(PrimitiveSpec.builder("trace.mark").description("Publishes a trace.mark event and returns its seq.")
                        .param("label", "string", true, "mark label")
                        .param("attrs", "object", false, "extra attributes"),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("label", inv.args().requireStr("label"));
                        payload.put("attrs", inv.args().obj("attrs").raw());
                        long seq = ctx.events.publish(Topics.TRACE_MARK, SOURCE, payload);
                        if (seq < 0) {
                            throw invalid("attrs cannot be published as an event (nested too deeply)", "attrs");
                        }
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        out.put("seq", Long.valueOf(seq));
                        return out;
                    }
                });
        reg(PrimitiveSpec.builder("app.state").thread(ThreadPolicy.APP)
                        .description("The app sections of every AppStateProvider, by provider id."),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return appState();
                    }
                });
        reg(PrimitiveSpec.builder("device.list").description("Registered device adapters."),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return deviceList();
                    }
                });
        reg(PrimitiveSpec.builder("device.state").thread(ThreadPolicy.APP)
                        .description("The state map of one device adapter.")
                        .param("id", "string", true, "device id"),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return device(inv.args().requireStr("id")).state();
                    }
                });
        reg(PrimitiveSpec.builder("device.act").thread(ThreadPolicy.APP).mutating()
                        .description("Performs an action on a device adapter.")
                        .param("id", "string", true, "device id")
                        .param("action", "string", true, "one of the adapter's actions")
                        .param("args", "object", false, "action arguments"),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        JsonView args = inv.args();
                        DeviceAdapter adapter = device(args.requireStr("id"));
                        String action = args.requireStr("action");
                        Set<String> actions = adapter.actions();
                        if (actions == null || !actions.contains(action)) {
                            Map<String, Object> data = new LinkedHashMap<String, Object>();
                            data.put("param", "action");
                            data.put("actions", actions == null ? Collections.emptyList()
                                    : new ArrayList<Object>(new TreeSet<String>(actions)));
                            throw new RpcException(ErrorCodes.INVALID_PARAMS, "device " + adapter.id()
                                    + " has no action '" + action + "'", data);
                        }
                        return adapter.act(action, args.obj("args"));
                    }
                });
    }

    private void reg(PrimitiveSpec.Builder spec, Primitives.Body body) {
        ctx.primitives.register(Primitives.of(spec.build(), body));
    }

    Map<String, Object> agentInfo() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("protocol", Protocol.VERSION);
        out.put("agentVersion", AgentRuntime.agentVersion());
        out.put("pid", Long.valueOf(runtime.pid()));
        out.put("startTimeMs", Long.valueOf(runtime.startTimeMs()));
        out.put("loadMode", ctx.loadMode().name());
        out.put("instrumentation", Boolean.valueOf(ctx.instrumentation() != null));
        out.put("readOnly", Boolean.valueOf(ctx.readOnly()));
        out.put("ready", Boolean.valueOf(runtime.isReady()));
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("modules", runtime.modulesJson());
        out.put("threads", runtime.threadsJson());
        out.put("events", ctx.events.masks().toJson());
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> option : ctx.options().entrySet()) {
            String key = option.getKey();
            if (!"token".equals(key) && !"nonce".equals(key)) {
                options.put(key, redactor.value(key, option.getValue()));
            }
        }
        out.put("options", options);
        return out;
    }

    private Map<String, Object> jvmInfo() {
        RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("javaVendor", System.getProperty("java.vendor"));
        out.put("vmName", System.getProperty("java.vm.name"));
        out.put("pid", Long.valueOf(runtime.pid()));
        out.put("uptimeMs", Long.valueOf(mx.getUptime()));
        out.put("mainCommand", redactor.argument(JvmIds.mainCommand()));
        List<Object> arguments = new ArrayList<Object>();
        for (String argument : mx.getInputArguments()) {
            arguments.add(redactor.argument(argument));
        }
        out.put("inputArguments", arguments);
        // The property, not GraphicsEnvironment.isHeadless(): asking AWT would freeze the application's headless
        // decision (and load the toolkit) before the application has made it (contract amendment A4).
        out.put("headless", System.getProperty("java.awt.headless"));
        out.put("osName", System.getProperty("os.name"));
        out.put("osArch", System.getProperty("os.arch"));
        out.put("user", System.getProperty("user.name"));
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> heapJson = new LinkedHashMap<String, Object>();
        heapJson.put("used", Long.valueOf(heap.getUsed()));
        heapJson.put("max", Long.valueOf(heap.getMax()));
        out.put("heap", heapJson);
        return out;
    }

    private Map<String, Object> threads(JsonView args) {
        boolean stacks = args.bool("stacks", false);
        String nameContains = args.str("nameContains", null);
        long maxDepth = args.num("maxDepth", 20L);
        if (maxDepth < 0) {
            throw invalid("maxDepth must not be negative", "maxDepth");
        }
        List<Thread> threads;
        Map<Thread, StackTraceElement[]> traces = null;
        if (stacks) {
            traces = Thread.getAllStackTraces();
            threads = new ArrayList<Thread>(traces.keySet());
        } else {
            threads = allThreads();
        }
        Collections.sort(threads, new Comparator<Thread>() {
            @Override
            public int compare(Thread a, Thread b) {
                return Long.compare(a.getId(), b.getId());
            }
        });
        List<Object> out = new ArrayList<Object>();
        for (Thread t : threads) {
            if (nameContains != null && !t.getName().contains(nameContains)) {
                continue;
            }
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("id", Long.valueOf(t.getId()));
            json.put("name", t.getName());
            json.put("state", t.getState().name());
            json.put("daemon", Boolean.valueOf(t.isDaemon()));
            if (traces != null) {
                StackTraceElement[] frames = traces.get(t);
                List<Object> stack = new ArrayList<Object>();
                for (int i = 0; frames != null && i < frames.length && i < maxDepth; i++) {
                    stack.add(frames[i].toString());
                }
                json.put("stack", stack);
            }
            out.add(json);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("threads", out);
        return result;
    }

    static List<Thread> allThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] array = new Thread[root.activeCount() + 64];
        int n = root.enumerate(array, true);
        List<Thread> out = new ArrayList<Thread>(n);
        for (int i = 0; i < n; i++) {
            out.add(array[i]);
        }
        return out;
    }

    private Map<String, Object> properties(JsonView args) {
        if (!(args.get("names") instanceof List)) {
            throw invalid("names must be an array of strings", "names");
        }
        List<Object> names = args.list("names");
        if (names.size() > MAX_PROPERTY_NAMES) {
            throw invalid("at most " + MAX_PROPERTY_NAMES + " names", "names");
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Object name : names) {
            if (!(name instanceof String)) {
                throw invalid("names must be an array of strings", "names");
            }
            values.put((String) name, redactor.value((String) name, System.getProperty((String) name)));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("properties", values);
        return out;
    }

    private Map<String, Object> query(JsonView args) {
        long since = args.num("since", 0L);
        long limit = args.num("limit", 200L);
        if (limit <= 0) {
            throw invalid("limit must be positive", "limit");
        }
        TopicFilter filter = topics(args, "topics", false);
        List<Object> events = new ArrayList<Object>();
        for (Event event : ctx.events.since(since, filter, (int) Math.min(limit, MAX_QUERY_LIMIT))) {
            events.add(event.toJson());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("events", events);
        out.put("lastSeq", Long.valueOf(ctx.events.lastSeq()));
        out.put("oldestSeq", Long.valueOf(ctx.events.oldestSeq()));
        return out;
    }

    /** A topic filter from a string of globs or an array of strings. */
    static TopicFilter topics(JsonView args, String key, boolean required) {
        Object raw = args.get(key);
        if (raw == null) {
            if (required) {
                throw invalid("missing required parameter '" + key + "'", key);
            }
            return TopicFilter.parse(null);
        }
        if (raw instanceof String) {
            return TopicFilter.parse((String) raw);
        }
        if (raw instanceof List) {
            List<String> globs = new ArrayList<String>();
            for (Object glob : (List<?>) raw) {
                if (!(glob instanceof String)) {
                    throw invalid(key + " must be a string or an array of strings", key);
                }
                globs.add((String) glob);
            }
            return TopicFilter.of(globs);
        }
        throw invalid(key + " must be a string or an array of strings", key);
    }

    private Map<String, Object> appState() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (AppStateProvider provider : ctx.spi.all(AppStateProvider.class)) {
            String id = provider.getClass().getName();
            try {
                id = provider.id();
                out.put(id, provider.appState());
            } catch (Throwable t) {
                ctx.log.warn("AppStateProvider " + id + " failed", t);
                Map<String, Object> failure = new LinkedHashMap<String, Object>();
                failure.put("error", t.getClass().getName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                out.put(id, failure);
            }
        }
        return out;
    }

    private Map<String, Object> deviceList() {
        List<Object> devices = new ArrayList<Object>();
        for (DeviceAdapter adapter : ctx.spi.all(DeviceAdapter.class)) {
            try {
                Map<String, Object> json = new LinkedHashMap<String, Object>();
                json.put("id", adapter.id());
                json.put("kind", adapter.kind());
                Set<String> actions = adapter.actions();
                json.put("actions", actions == null ? new ArrayList<Object>()
                        : new ArrayList<Object>(new TreeSet<String>(actions)));
                Map<String, Object> rungs = rungsOf(adapter, actions);
                if (!rungs.isEmpty()) {
                    json.put("rungs", rungs);
                }
                devices.add(json);
            } catch (Throwable t) {
                ctx.log.warn("DeviceAdapter " + adapter.getClass().getName() + " failed", t);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("devices", devices);
        return out;
    }

    /**
     * phase8-contracts.md 7.6 (amendment A85): {@code {<action>: [rung, ...]}} of the actions whose ladder is not
     * empty, in action order; an adapter whose {@code rungs} throws is logged and read as empty, a null or blank rung
     * name is left out.
     */
    private Map<String, Object> rungsOf(DeviceAdapter adapter, Set<String> actions) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (actions == null) {
            return out;
        }
        for (String action : new TreeSet<String>(actions)) {
            List<String> ladder;
            try {
                ladder = adapter.rungs(action);
            } catch (Throwable t) {
                ctx.log.warn("DeviceAdapter " + adapter.getClass().getName() + ".rungs(" + action + ") failed", t);
                continue;
            }
            if (ladder == null || ladder.isEmpty()) {
                continue;
            }
            List<Object> names = new ArrayList<Object>();
            for (String name : ladder) {
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                out.put(action, names);
            }
        }
        return out;
    }

    private DeviceAdapter device(String id) {
        List<Object> ids = new ArrayList<Object>();
        for (DeviceAdapter adapter : ctx.spi.all(DeviceAdapter.class)) {
            String adapterId = adapter.id();
            if (id.equals(adapterId)) {
                return adapter;
            }
            ids.add(adapterId);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("devices", ids);
        throw new RpcException(ErrorCodes.NOT_FOUND, "no device with id '" + id + "'", data);
    }

    static RpcException invalid(String message, String param) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("param", param);
        return new RpcException(ErrorCodes.INVALID_PARAMS, message, data);
    }

    /** {@code sync.await}: its dispatcher timeout is its own {@code timeoutMs} plus one second. */
    private final class SyncAwait implements Primitive, Dispatcher.DispatchTimeout {
        private final PrimitiveSpec spec = PrimitiveSpec.builder("sync.await")
                .description("Waits for the first event after 'since' that matches the topics and payload match; "
                        + "TIMEOUT with data.lastSeq if none arrives.")
                .param("topics", "string|array", true, "topic globs")
                .param("since", "integer", true, "only events with a higher seq")
                .param("match", "object", false, "payload entries that must be equal (String.valueOf)")
                .param("timeoutMs", "integer", false, "default 10000, at most " + MAX_AWAIT_MS)
                .build();

        @Override
        public PrimitiveSpec spec() {
            return spec;
        }

        @Override
        public long dispatchTimeoutMs(JsonView args) {
            return waitMs(args) + 1000L;
        }

        private long waitMs(JsonView args) {
            long ms = args.num("timeoutMs", 10000L);
            if (ms <= 0) {
                throw invalid("timeoutMs must be positive", "timeoutMs");
            }
            return Math.min(ms, MAX_AWAIT_MS);
        }

        @Override
        public Object invoke(Invocation inv) throws Exception {
            JsonView args = inv.args();
            TopicFilter filter = topics(args, "topics", true);
            long since = args.requireNum("since");
            Map<String, Object> match = args.has("match") ? args.obj("match").raw() : null;
            long wait = Math.min(waitMs(args), inv.remainingMs());
            Event event = ctx.events.await(since, filter, match, wait);
            if (event == null) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("lastSeq", Long.valueOf(ctx.events.lastSeq()));
                data.put("topics", filter.toString());
                data.put("since", Long.valueOf(since));
                data.put("timeoutMs", Long.valueOf(wait));
                throw new RpcException(ErrorCodes.TIMEOUT, "no matching event within " + wait + " ms", data);
            }
            return event.toJson();
        }
    }
}
