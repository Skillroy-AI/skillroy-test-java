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

package ai.skillroy.testjava.agent.dist.fixture.module;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A module packaged into a jar at test time and loaded by a child JVM's agent (from {@code home/modules} or the
 * {@code modules} option). {@code probe.check} reports what the module's class loader can see. With the option
 * {@code probe.forbidMain=true} it forbids the application's main thread; {@code uninstall} leaves the system
 * property {@code probe.uninstalled} behind so that the application can see it happened. {@code probe.contextLoader}
 * (WORKER) reports the context class loader of its agent thread and of a thread it starts there, which stays alive
 * (finding S34).
 */
public final class ProbeModule implements AgentModule {

    static final String APP_CLASS = "ai.skillroy.testjava.agent.dist.fixture.app.HostThing";

    @Override
    public String id() {
        return "probe";
    }

    @Override
    public String version() {
        return "it";
    }

    @Override
    public void uninstall() {
        System.setProperty("probe.uninstalled", "true");
    }

    @Override
    public void install(final AgentContext ctx) {
        if ("true".equals(ctx.options().get("probe.forbidMain"))) {
            ctx.spi().register(ThreadPolicyProvider.class, new ThreadPolicyProvider() {
                @Override
                public String id() {
                    return "probe";
                }

                @Override
                public boolean isForbidden(Thread thread) {
                    return "main".equals(thread.getName());
                }

                @Override
                public <T> T callOnAppThread(Callable<T> task, long timeoutMs) throws Exception {
                    return task.call();
                }
            });
        }
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("probe.contextLoader")
                .thread(ThreadPolicy.WORKER).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        final ClassLoader[] seen = new ClassLoader[1];
                        final CountDownLatch started = new CountDownLatch(1);
                        Thread spawned = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                seen[0] = Thread.currentThread().getContextClassLoader();
                                started.countDown();
                                try {
                                    new CountDownLatch(1).await();   // host code's own long-lived thread
                                } catch (InterruptedException ignored) {
                                    // End.
                                }
                            }
                        }, "probe-spawned");
                        spawned.start();
                        started.await(10, TimeUnit.SECONDS);
                        ClassLoader worker = Thread.currentThread().getContextClassLoader();
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        out.put("workerLoader", worker == null ? "null" : worker.getClass().getName());
                        out.put("workerIsHost", Boolean.valueOf(worker == ctx.hostClassLoader()));
                        out.put("spawnedIsHost", Boolean.valueOf(seen[0] == ctx.hostClassLoader()));
                        return out;
                    }
                }));
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("probe.check").build(), new Primitives.Body() {
            @Override
            public Object invoke(Invocation inv) throws Exception {
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                ClassLoader own = ProbeModule.class.getClassLoader();
                out.put("moduleLoader", own.getClass().getName());
                out.put("apiLoader", String.valueOf(AgentModule.class.getClassLoader().getClass().getName()));
                Class<?> app = Class.forName(APP_CLASS, false, own);
                out.put("appClassVisible", Boolean.TRUE);
                out.put("appClassLoader", app.getClassLoader().getClass().getName());
                out.put("hostLoaderSeesApp", Boolean.valueOf(
                        Class.forName(APP_CLASS, false, ctx.hostClassLoader()) == app));
                Object host = ctx.hostContext();
                out.put("hostContext", String.valueOf(host));
                if (host != null) {
                    Method greeting = host.getClass().getMethod("greeting");
                    out.put("greeting", greeting.invoke(host));
                }
                return out;
            }
        }));
    }
}
