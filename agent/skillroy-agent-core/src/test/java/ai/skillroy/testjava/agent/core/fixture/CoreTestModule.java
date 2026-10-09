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

package ai.skillroy.testjava.agent.core.fixture;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.agent.api.spi.DeviceAdapter;
import ai.skillroy.testjava.agent.api.spi.ProductionMarker;
import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.agent.api.spi.RedactionKeys;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The in-process test module ("coretest"): test primitives for every dispatch path and thread policy, plus SPIs
 * switched on by options ({@code coretest.readiness=manual}, {@code coretest.production=<reason>},
 * {@code coretest.appThread=true}, {@code coretest.redactionKeys=<regex>}). {@code coretest.slowInstall=true} and
 * {@code coretest.slowReady=true} make {@code install} and {@code onTargetReady} wait for {@link TestState#gate}
 * ({@code coretest.slowInstall=stubborn}: ignoring interrupts).
 *
 * <p>It registers a ProductionMarker that passes, so that the default fixture set (which contains FAILED modules)
 * still becomes ready under the production gate of finding S14; {@code coretest.noMarker=true} leaves it out.
 */
public final class CoreTestModule implements AgentModule {

    static final String FORBIDDEN_PREFIX = "coretest-forbidden";
    /** Handles of capabilities declared through {@code test.capability}. */
    static final Map<String, AutoCloseable> HANDLES = new ConcurrentHashMap<String, AutoCloseable>();

    private ExecutorService appThread;
    private ExecutorService forbiddenThread;

    @Override
    public String id() {
        return "coretest";
    }

    @Override
    public String version() {
        return "1.0-test";
    }

    @Override
    public void install(final AgentContext ctx) throws Exception {
        TestState.INSTALLED.add(id());
        String slowInstall = ctx.options().get("coretest.slowInstall");
        if ("true".equals(slowInstall)) {
            TestState.entered.countDown();
            TestState.gate.await(60, TimeUnit.SECONDS);
        } else if ("stubborn".equals(slowInstall)) {
            // Like an install blocked in host code: an interrupt does not end the wait.
            TestState.entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() - deadline < 0) {
                try {
                    if (TestState.gate.await(100, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException ignored) {
                    // Keep waiting.
                }
            }
        }
        appThread = Executors.newSingleThreadExecutor(named("coretest-app"));
        forbiddenThread = Executors.newSingleThreadExecutor(named(FORBIDDEN_PREFIX));

        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.echo").param("value", "string", false,
                null).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        out.put("args", inv.args().raw());
                        out.put("correlationId", inv.correlationId());
                        out.put("traceparent", inv.traceparent());
                        out.put("remainingMs", Long.valueOf(inv.remainingMs()));
                        out.put("thread", Thread.currentThread().getName());
                        return out;
                    }
                }));
        // phase6-contracts.md 3.6: the context's secret-key rule and text redaction, as a module sees them.
        final AgentContext moduleContext = ctx;
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.redact")
                .param("key", "string", false, null).param("text", "string", false, null).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        out.put("secret", Boolean.valueOf(moduleContext.isSecretKey(inv.args().str("key"))));
                        out.put("coreSecret", Boolean.valueOf(inv.context().isSecretKey(inv.args().str("key"))));
                        out.put("text", moduleContext.redactText(inv.args().str("text")));
                        return out;
                    }
                }));
        for (final ThreadPolicy policy : ThreadPolicy.values()) {
            String name = "test.where." + lowerCamel(policy.name());
            ctx.primitives().register(Primitives.of(PrimitiveSpec.builder(name).thread(policy).build(),
                    new Primitives.Body() {
                        @Override
                        public Object invoke(Invocation inv) {
                            String thread = Thread.currentThread().getName();
                            Map<String, Object> payload = new LinkedHashMap<String, Object>();
                            payload.put("policy", policy.name());
                            payload.put("thread", thread);
                            ctx.events().publish("test.where", "coretest", payload);
                            return thread;
                        }
                    }));
        }
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.mutate").mutating().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return Long.valueOf(TestState.MUTATIONS.incrementAndGet());
                    }
                }));
        // phase6-contracts.md 12.9, D156: a read that evaluates a caller's expression.
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.script").script().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return Long.valueOf(TestState.MUTATIONS.incrementAndGet());
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.destroy").destructive().build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return "destroyed";
                    }
                }));
        registerBlock(ctx, "test.block", ThreadPolicy.CALLER);
        registerBlock(ctx, "test.block.worker", ThreadPolicy.WORKER);
        registerBlock(ctx, "test.block.edt", ThreadPolicy.EDT_SYNC);
        registerBlock(ctx, "test.block.app", ThreadPolicy.APP);
        registerStuck(ctx, "test.stuck.worker", ThreadPolicy.WORKER);
        registerStuck(ctx, "test.stuck.caller", ThreadPolicy.CALLER);
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.app.mutate").thread(ThreadPolicy.APP)
                .mutating().build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return Thread.currentThread().getName();
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.contextLoader")
                .thread(ThreadPolicy.WORKER).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        final ClassLoader[] spawned = new ClassLoader[1];
                        final boolean[] daemon = new boolean[1];
                        Thread child = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                spawned[0] = Thread.currentThread().getContextClassLoader();
                                daemon[0] = Thread.currentThread().isDaemon();
                            }
                        });
                        child.start();
                        child.join(10000L);
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        ClassLoader host = inv.context().hostClassLoader();
                        ClassLoader own = Thread.currentThread().getContextClassLoader();
                        out.put("workerIsHost", Boolean.valueOf(own == host));
                        out.put("spawnedIsHost", Boolean.valueOf(spawned[0] == host));
                        out.put("spawnedDaemon", Boolean.valueOf(daemon[0]));
                        return out;
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.option")
                .param("key", "string", true, null).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return ctx.option(inv.args().requireStr("key"));
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.capability")
                .param("id", "string", true, null).param("action", "string", true, null).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        String id = inv.args().requireStr("id");
                        String action = inv.args().requireStr("action");
                        if ("declare".equals(action)) {
                            HANDLES.put(id, ctx.capabilities().declare(id, new Probe() {
                                @Override
                                public ProbeResult run(AgentContext c) {
                                    return ProbeResult.ok("declared by a test");
                                }
                            }));
                        } else if ("closeHandle".equals(action)) {
                            HANDLES.remove(id).close();
                        } else if ("undeclare".equals(action)) {
                            ctx.capabilities().undeclare(id);
                        } else {
                            ctx.capabilities().set(id, CapabilityState.OK, null, null);
                        }
                        return action;
                    }
                }));
        registerWithCapability(ctx, "test.needsDynamic", "coretest.dynamic");
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.fail").build(), new Primitives.Body() {
            @Override
            public Object invoke(Invocation inv) {
                throw new IllegalStateException("boom");
            }
        }));
        // Amendment A34: echoes its 'value' into the log, an event and (by mode) an error, the way a careless
        // module would, so that the dispatcher's redaction of secret params can be observed. With 'event' it also
        // publishes that object unchanged on topic test.app, as the application would during the call.
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.leak")
                .param("value", "string|number", false, null).param("mode", "string", false, null)
                .param("event", "object", false, null).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Object value = inv.args().get("value");
                        TestState.LEAK_SEEN.add(value);
                        TestState.LEAK_SECRET_NAMES.add(inv.secretParams());
                        ctx.log().info("typed " + value + " into the entry");
                        ctx.log().warn("echo failed", new IllegalArgumentException("value " + value,
                                new IllegalStateException("cause " + value)));
                        Map<String, Object> nested = new LinkedHashMap<String, Object>();
                        nested.put("text", "entered " + value);
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("echo", value);
                        payload.put("nested", nested);
                        payload.put("count", Long.valueOf(1));
                        ctx.events().publish("test.leak", "coretest", payload);
                        if (inv.args().has("event")) {
                            ctx.events().publish("test.app", "coretest", inv.args().obj("event").raw());
                        }
                        String mode = inv.args().str("mode", "ok");
                        if ("rpcError".equals(mode)) {
                            Map<String, Object> data = new LinkedHashMap<String, Object>();
                            data.put("text", value);
                            data.put("list", Arrays.asList(value, "keep"));
                            throw new RpcException(ErrorCodes.INPUT_REJECTED, "rejected input " + value, data);
                        }
                        if ("fail".equals(mode)) {
                            throw new IllegalStateException("bad value " + value);
                        }
                        return Collections.<String, Object>singletonMap("seen", value);
                    }
                }));
        // Amendment A34: an EDT_ASYNC primitive that fails on the EDT after its call was answered, with its value in
        // the message and the cause, so that the broker's line about the failure can be observed.
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.leak.async")
                .thread(ThreadPolicy.EDT_ASYNC).param("value", "string|number", false, null).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Object value = inv.args().get("value");
                        TestState.LEAK_SEEN.add(value);
                        throw new IllegalStateException("bad value " + value + " via dispatch EDT_ASYNC.",
                                new IllegalArgumentException("cause " + value));
                    }
                }));
        // Amendment A34: hands the broker a task that fails with the call's value in its message, through
        // ThreadBroker.post ('how' = post) or call ('how' = call, for EDT_ASYNC, which returns once posted) under
        // 'policy', so that the broker's line about a failed posted task can be observed on every path.
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.leak.post")
                .param("value", "string|number", false, null).param("policy", "string", true, null)
                .param("how", "string", false, null).param("tag", "string", true, null).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        final Object value = inv.args().get("value");
                        final String tag = inv.args().requireStr("tag");
                        TestState.LEAK_SEEN.add(value);
                        ThreadPolicy policy = ThreadPolicy.valueOf(inv.args().requireStr("policy"));
                        if ("call".equals(inv.args().str("how", "post"))) {
                            ctx.threads().call(policy, new Callable<Object>() {
                                @Override
                                public Object call() {
                                    throw new IllegalStateException("bad value " + value + " via " + tag + ".");
                                }
                            }, 5000L);
                        } else {
                            ctx.threads().post(policy, new Runnable() {
                                @Override
                                public void run() {
                                    throw new IllegalStateException("bad value " + value + " via " + tag + ".");
                                }
                            });
                        }
                        return tag;
                    }
                }));
        // Amendment A34: a WORKER primitive that outlives its timeout (it waits for TestState.leakRelease and
        // ignores interrupts, as host code blocked on a monitor) and logs its value before and after the wait, so
        // that the lines of a timed-out, abandoned call can be observed.
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.leak.slow")
                .thread(ThreadPolicy.WORKER).param("value", "string|number", false, null).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Object value = inv.args().get("value");
                        TestState.LEAK_SEEN.add(value);
                        ctx.log().info("waiting with " + value);
                        boolean released = awaitIgnoringInterrupts(TestState.leakRelease, 60);
                        ctx.log().info((released ? "released" : "gave up") + " with " + value);
                        return released ? "released" : "gave up";
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.rpcError").build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        throw new RpcException(ErrorCodes.NOT_FOUND, "nothing here",
                                Collections.<String, Object>singletonMap("what", "x"));
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.nan").build(), new Primitives.Body() {
            @Override
            public Object invoke(Invocation inv) {
                return Double.valueOf(Double.NaN);
            }
        }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.publish")
                .param("n", "integer", true, null).param("topic", "string", false, null).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        long n = inv.args().requireNum("n");
                        String topic = inv.args().str("topic", "test.burst");
                        for (int i = 0; i < n; i++) {
                            ctx.events().publish(topic, "coretest", Collections.<String, Object>singletonMap("i",
                                    Integer.valueOf(i)));
                        }
                        return Long.valueOf(n);
                    }
                }));
        ctx.capabilities().declare("coretest.probed", new Probe() {
            @Override
            public ProbeResult run(AgentContext c) {
                TestState.PROBE_RUNS.incrementAndGet();
                return ProbeResult.ok("probe-strategy");
            }
        });
        ctx.capabilities().set("coretest.off", CapabilityState.UNAVAILABLE, "switched off", null);
        ctx.capabilities().set("coretest.degraded", CapabilityState.DEGRADED, "partly", null);
        registerWithCapability(ctx, "test.needsProbe", "coretest.probed");
        registerWithCapability(ctx, "test.off", "coretest.off");
        registerWithCapability(ctx, "test.degraded", "coretest.degraded");
        registerWithCapability(ctx, "test.needsReady", "target.ready");
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.callFromForbidden")
                .thread(ThreadPolicy.WORKER).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        Future<Object> f = forbiddenThread.submit(new Callable<Object>() {
                            @Override
                            public Object call() {
                                try {
                                    ctx.threads().call(ThreadPolicy.WORKER, new Callable<String>() {
                                        @Override
                                        public String call() {
                                            return "ran";
                                        }
                                    }, 1000L);
                                    return "no error";
                                } catch (RpcException e) {
                                    return Long.valueOf(e.error().code());
                                } catch (Exception e) {
                                    return e.toString();
                                }
                            }
                        });
                        return f.get(10, TimeUnit.SECONDS);
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("test.app.count").thread(ThreadPolicy.APP)
                .build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        TestState.APP_BODY_RUNS.incrementAndGet();
                        return Thread.currentThread().getName();
                    }
                }));

        if ("manual".equals(ctx.options().get("coretest.readiness"))) {
            ctx.spi().register(ReadinessSource.class, new ReadinessSource() {
                @Override
                public String id() {
                    return "coretest.readiness";
                }

                @Override
                public boolean isReady() {
                    return TestState.READY.get();
                }

                @Override
                public String describe() {
                    return "the test flips it";
                }
            });
        }
        final String production = ctx.options().get("coretest.production");
        if (production != null || !"true".equals(ctx.options().get("coretest.noMarker"))) {
            ctx.spi().register(ProductionMarker.class, new ProductionMarker() {
                @Override
                public String productionReason() {
                    return production;
                }
            });
        }
        final String redactionKeys = ctx.options().get("coretest.redactionKeys");
        if (redactionKeys != null) {
            ctx.spi().register(RedactionKeys.class, new RedactionKeys() {
                @Override
                public Pattern secretKeys() {
                    if ("throw".equals(redactionKeys)) {
                        throw new IllegalStateException("no keys today");
                    }
                    return Pattern.compile(redactionKeys);
                }
            });
        }
        if ("true".equals(ctx.options().get("coretest.appThread"))) {
            ctx.spi().register(ThreadPolicyProvider.class, new ThreadPolicyProvider() {
                @Override
                public String id() {
                    return "coretest";
                }

                @Override
                public boolean isForbidden(Thread thread) {
                    return thread.getName().startsWith(FORBIDDEN_PREFIX);
                }

                @Override
                public boolean available() {
                    return TestState.appAvailable;
                }

                @Override
                public <T> T callOnAppThread(Callable<T> task, long timeoutMs) throws Exception {
                    Future<T> f = (TestState.appOnForbidden ? forbiddenThread : appThread).submit(task);
                    try {
                        return f.get(timeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        throw new RpcException(ErrorCodes.TIMEOUT, "app thread busy");
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        throw cause instanceof Exception ? (Exception) cause : e;
                    }
                }
            });
        }
        ctx.spi().register(AppStateProvider.class, new AppStateProvider() {
            @Override
            public String id() {
                return "coretest";
            }

            @Override
            public Map<String, Object> appState() {
                Map<String, Object> state = new LinkedHashMap<String, Object>();
                state.put("state", "IDLE");
                state.put("thread", Thread.currentThread().getName());
                return state;
            }
        });
        ctx.spi().register(DeviceAdapter.class, new DeviceAdapter() {
            private final AtomicInteger printed = new AtomicInteger();

            @Override
            public String id() {
                return "printer";
            }

            @Override
            public String kind() {
                return "printer";
            }

            @Override
            public Map<String, Object> state() {
                return Collections.<String, Object>singletonMap("printed", Integer.valueOf(printed.get()));
            }

            @Override
            public Set<String> actions() {
                return new LinkedHashSet<String>(Arrays.asList("print", "cut"));
            }

            @Override
            public Object act(String action, JsonView args) {
                printed.incrementAndGet();
                return Collections.<String, Object>singletonMap("printed", args.str("text", ""));
            }
        });
    }

    /** A primitive that waits for {@link TestState#stuck} and ignores interrupts, as host code blocked on a monitor. */
    private void registerStuck(AgentContext ctx, String name, ThreadPolicy policy) {
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder(name).thread(policy).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        TestState.STUCK_RUNNING.incrementAndGet();
                        try {
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                            while (System.nanoTime() - deadline < 0) {
                                try {
                                    if (TestState.stuck.await(100, TimeUnit.MILLISECONDS)) {
                                        return "released";
                                    }
                                } catch (InterruptedException ignored) {
                                    // Like a thread blocked on a monitor: an interrupt does not free it.
                                }
                            }
                            return "gave up";
                        } finally {
                            TestState.STUCK_RUNNING.decrementAndGet();
                        }
                    }
                }));
    }

    /** Waits for the latch at most {@code seconds}; an interrupt does not end the wait (a thread on a monitor). */
    private static boolean awaitIgnoringInterrupts(CountDownLatch latch, long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() - deadline < 0) {
            try {
                if (latch.await(100, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException ignored) {
                // Keep waiting.
            }
        }
        return false;
    }

    private void registerBlock(AgentContext ctx, String name, ThreadPolicy policy) {
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder(name).thread(policy).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        boolean released = TestState.blocker.await(30, TimeUnit.SECONDS);
                        return released ? "released" : "gave up";
                    }
                }));
    }

    private static void registerWithCapability(AgentContext ctx, String name, String capability) {
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder(name).capability(capability).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        return "ok";
                    }
                }));
    }

    @Override
    public void onTargetReady(AgentContext ctx) throws InterruptedException {
        TestState.READY_CALLS.add(id() + "@" + Thread.currentThread().getName());
        if ("true".equals(ctx.options().get("coretest.slowReady"))) {
            TestState.LIFECYCLE.add("ready-enter");
            TestState.entered.countDown();
            try {
                TestState.gate.await(60, TimeUnit.SECONDS);
            } finally {
                TestState.LIFECYCLE.add("ready-exit");
            }
        }
    }

    @Override
    public void uninstall() {
        TestState.LIFECYCLE.add("uninstall");
        TestState.UNINSTALLED.add(id());
        if (appThread != null) {
            appThread.shutdownNow();
        }
        if (forbiddenThread != null) {
            forbiddenThread.shutdownNow();
        }
    }

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    private static String lowerCamel(String constant) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : constant.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
