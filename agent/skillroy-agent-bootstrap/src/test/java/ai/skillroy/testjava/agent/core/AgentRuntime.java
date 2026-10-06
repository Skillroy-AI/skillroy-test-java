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

import ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent;
import ai.skillroy.testjava.agent.bootstrap.FakeRecorder;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Test double of the core runtime with the reflective surface the bootstrap uses ({@code start}, {@code close},
 * {@code isClosed}, {@code republishDiscovery}, {@code onClosed}). The bootstrap tests package it into a fake agent
 * home's {@code lib/} so that the real bootstrap code path (options, guard, loader, reflection) runs without the
 * core. With the option {@code fake.asyncClose=true} its {@code close} returns before the shutdown has finished,
 * as the real runtime does on a forbidden thread; the test finishes it through {@link FakeRecorder#finishClose}.
 * With {@code fake.reenter=same|agentThread|stop} its start calls back into {@code EmbeddedAgent} the way host code
 * or a module's install can (on the starting thread, or on an agent thread the start waits for, which the real core
 * runs install on); with {@code fake.gate=true} it holds the start until the test opens {@link FakeRecorder#startGate};
 * with {@code fake.closeGate=true} its close waits for {@link FakeRecorder#closeGate}. Like the real core it offers the
 * six-argument start with a release hook (finding S32), which it runs itself when its start fails, and
 * {@code isForbiddenThread}, which forbids threads named {@code fake-forbidden-*} (finding S33).
 */
public final class AgentRuntime implements AutoCloseable {

    private volatile boolean closed;
    private volatile Runnable closedHook;
    private final boolean asyncClose;
    private final boolean stopInClose;
    private final boolean closeGate;

    private AgentRuntime(boolean asyncClose, boolean stopInClose, boolean closeGate) {
        this.asyncClose = asyncClose;
        this.stopInClose = stopInClose;
        this.closeGate = closeGate;
    }

    public static AgentRuntime start(Map<String, String> options, Instrumentation inst, ClassLoader hostLoader,
                                     String loadMode, Object hostContext) throws Exception {
        return start(options, inst, hostLoader, loadMode, hostContext, null);
    }

    public static AgentRuntime start(Map<String, String> options, Instrumentation inst, ClassLoader hostLoader,
                                     String loadMode, Object hostContext, Runnable onReleased) throws Exception {
        FakeRecorder.lastHadReleaseHook = onReleased != null;
        try {
            AgentRuntime runtime = startFake(options, inst, hostLoader, loadMode, hostContext);
            runtime.closedHook = onReleased;
            final AgentRuntime self = runtime;
            FakeRecorder.selfClose = new Runnable() {
                @Override
                public void run() {
                    self.close();
                }
            };
            return runtime;
        } catch (Exception | Error e) {
            // The real core runs the hook once no agent code runs any more; a failed start of the double ends here.
            if (onReleased != null) {
                onReleased.run();
            }
            throw e;
        }
    }

    public boolean isForbiddenThread(Thread thread) {
        return thread.getName().startsWith("fake-forbidden");
    }

    private static AgentRuntime startFake(Map<String, String> options, Instrumentation inst, ClassLoader hostLoader,
                                          String loadMode, Object hostContext) throws Exception {
        FakeRecorder.lastStartThread = Thread.currentThread().getName();
        FakeRecorder.lastStartContextLoader = Thread.currentThread().getContextClassLoader();
        if ("true".equals(options.get("fake.fail"))) {
            throw new IllegalStateException("fake start failure");
        }
        if ("true".equals(options.get("fake.interrupt"))) {
            throw new InterruptedException("fake interrupted start");
        }
        FakeRecorder.CALLS.add("start " + loadMode);
        FakeRecorder.lastOptions = new LinkedHashMap<String, String>(options);
        FakeRecorder.lastHostContext = hostContext;
        FakeRecorder.lastHostLoader = hostLoader;
        FakeRecorder.lastHadInstrumentation = inst != null;
        FakeRecorder.runtimeLoader = AgentRuntime.class.getClassLoader();
        reenter(options.get("fake.reenter"), options.get("home"));
        if ("true".equals(options.get("fake.gate"))) {
            FakeRecorder.startEntered.countDown();
            if (!FakeRecorder.startGate.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never opened the start gate");
            }
        }
        return new AgentRuntime("true".equals(options.get("fake.asyncClose")),
                "true".equals(options.get("fake.stopInClose")), "true".equals(options.get("fake.closeGate")));
    }

    private static void reenter(String how, String home) throws InterruptedException {
        if (how == null) {
            return;
        }
        if ("stopOnAgentThread".equals(how)) {
            onAgentThread(new Runnable() {
                @Override
                public void run() {
                    EmbeddedAgent.stop();
                    FakeRecorder.reentrantResult = "stop returned";
                }
            });
            return;
        }
        final Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("home", home);
        if ("same".equals(how)) {
            FakeRecorder.reentrantResult = Boolean.valueOf(EmbeddedAgent.start(props));
            return;
        }
        final boolean stop = "stop".equals(how);
        // Like the real core: install runs on skillroy-start, in the agent's thread group, while start waits.
        Thread agentThread = new Thread(new ThreadGroup("skillroy"), new Runnable() {
            @Override
            public void run() {
                if (stop) {
                    EmbeddedAgent.stop();
                    FakeRecorder.reentrantResult = "stop returned";
                } else {
                    FakeRecorder.reentrantResult = Boolean.valueOf(EmbeddedAgent.start(props));
                }
            }
        }, "skillroy-start");
        agentThread.setDaemon(true);
        agentThread.start();
        agentThread.join(TimeUnit.SECONDS.toMillis(30));
    }

    public void onClosed(Runnable hook) {
        closedHook = hook;
    }

    public void republishDiscovery() {
        FakeRecorder.CALLS.add("republish");
    }

    public boolean isClosed() {
        return closed;
    }

    /** Runs the task on a thread like the core's own (skillroy-*, group skillroy) and waits for it, bounded. */
    private static void onAgentThread(Runnable task) throws InterruptedException {
        Thread agentThread = new Thread(new ThreadGroup("skillroy"), task, "skillroy-worker-1");
        agentThread.setDaemon(true);
        agentThread.start();
        agentThread.join(TimeUnit.SECONDS.toMillis(30));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        FakeRecorder.CALLS.add("close");
        if (closeGate) {
            FakeRecorder.closeEntered.countDown();
            try {
                FakeRecorder.closeGate.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (stopInClose) {
            // Like a module's uninstall calling EmbeddedAgent.stop while the host's stop waits for it.
            try {
                reenter("stopOnAgentThread", null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runnable hook = closedHook;
        closedHook = null;
        if (hook == null) {
            return;
        }
        if (asyncClose) {
            FakeRecorder.finishClose = hook;
        } else {
            hook.run();
        }
    }
}
