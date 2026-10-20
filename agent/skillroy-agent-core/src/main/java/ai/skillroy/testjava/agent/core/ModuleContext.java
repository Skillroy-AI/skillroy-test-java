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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.CapabilityRegistry;
import ai.skillroy.testjava.agent.api.EventBus;
import ai.skillroy.testjava.agent.api.HandleRegistry;
import ai.skillroy.testjava.agent.api.Lane;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveRegistry;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.LoadMode;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * The context one module installs with: the core context, except that primitive, SPI and capability registrations
 * are recorded so that a module whose {@code install} fails, or that is uninstalled, leaves nothing behind (its
 * capabilities are withdrawn, contract amendment A18). Each accessor returns the same
 * object for the module's lifetime.
 *
 * <p>Phase 8 (phase8-contracts.md 4.1, 4.2): {@link #threads()} is a scoped broker that records the lanes the module
 * declares, so that they are retired with the module (in {@link #rollback()}, after a failed install and after the
 * module's {@code uninstall}); {@link #closeQuiescentPoints()} closes the quiescent point of every
 * {@code ThreadPolicyProvider} the module registered, before its {@code uninstall} returns the thread to the host.
 *
 * <p>Thread-safe.
 */
final class ModuleContext implements AgentContext {

    private final CoreContext core;
    private final String moduleId;
    private final Primitives primitives = new Primitives();
    private final Spi spi = new Spi();
    private final Capabilities capabilities = new Capabilities();
    private final Threads threads = new Threads();

    /** @param moduleId the module's id: the owner of the lanes it declares */
    ModuleContext(CoreContext core, String moduleId) {
        this.core = core;
        this.moduleId = moduleId;
    }

    /**
     * Removes everything the module registered through this context: closes the quiescent points of its providers
     * first, then retires its lanes and drops its primitives, SPIs and capabilities.
     */
    void rollback() {
        closeQuiescentPoints();
        threads.retireAll();
        primitives.removeAll();
        spi.removeAll();
        capabilities.removeAll();
    }

    /**
     * Closes the quiescent point of every {@code ThreadPolicyProvider} this module registered (idempotent with the
     * module's own close), so that no module code runs on the application's thread once the module is going
     * (phase8-contracts.md 4.1 "Lifecycle").
     */
    void closeQuiescentPoints() {
        for (ThreadPolicyProvider provider : spi.providers()) {
            core.threads.closePoint(provider);
        }
    }

    @Override
    public Instrumentation instrumentation() {
        return core.instrumentation();
    }

    @Override
    public LoadMode loadMode() {
        return core.loadMode();
    }

    @Override
    public Map<String, String> options() {
        return core.options();
    }

    @Override
    public ClassLoader hostClassLoader() {
        return core.hostClassLoader();
    }

    @Override
    public Object hostContext() {
        return core.hostContext();
    }

    @Override
    public PrimitiveRegistry primitives() {
        return primitives;
    }

    @Override
    public EventBus events() {
        return core.events();
    }

    @Override
    public HandleRegistry handles() {
        return core.handles();
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
        return core.log();
    }

    @Override
    public boolean readOnly() {
        return core.readOnly();
    }

    @Override
    public boolean targetReady() {
        return core.targetReady();
    }

    @Override
    public boolean isSecretKey(String key) {
        return core.isSecretKey(key);
    }

    @Override
    public String redactText(String text) {
        return core.redactText(text);
    }

    private final class Primitives implements PrimitiveRegistry {
        private final Set<String> names = new LinkedHashSet<String>();

        @Override
        public void register(Primitive primitive) {
            core.primitives.register(primitive);
            synchronized (names) {
                names.add(primitive.spec().name());
            }
        }

        @Override
        public void unregister(String name) {
            core.primitives.unregister(name);
            synchronized (names) {
                names.remove(name);
            }
        }

        @Override
        public Primitive find(String name) {
            return core.primitives.find(name);
        }

        @Override
        public List<PrimitiveSpec> list() {
            return core.primitives.list();
        }

        void removeAll() {
            List<String> copy;
            synchronized (names) {
                copy = new ArrayList<String>(names);
                names.clear();
            }
            for (String name : copy) {
                core.primitives.unregister(name);
            }
        }
    }

    /**
     * The module's view of the core's broker: every call goes to the core's, and a lane declared here is owned by
     * this module and retired with it (phase8-contracts.md 4.2; the A18 pattern for capabilities).
     */
    private final class Threads implements ThreadBroker {
        private final List<Lane> lanes = new ArrayList<Lane>();

        @Override
        public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
            // A module's call(APP_QUIESCENT, …) is penalised under its own id (L2-F3).
            return core.threads.call(moduleId, policy, task, timeoutMs);
        }

        @Override
        public void post(ThreadPolicy policy, Runnable task) {
            core.threads.post(policy, task);
        }

        @Override
        public boolean isForbiddenThread(Thread thread) {
            return core.threads.isForbiddenThread(thread);
        }

        @Override
        public <T> T callQuiescent(Callable<T> task, long budgetMs, long timeoutMs) throws Exception {
            // The overrun penalty and its log line are keyed by this module (L2-F3).
            return core.threads.callQuiescent(moduleId, task, budgetMs, timeoutMs);
        }

        @Override
        public Lane declareLane(String name, int threads, int queue) {
            Lane lane = core.threads.declareLane(moduleId, name, threads, queue);
            synchronized (lanes) {
                lanes.add(lane);
            }
            return lane;
        }

        @Override
        public <T> T callOnLane(String lane, Callable<T> task, long timeoutMs) throws Exception {
            return core.threads.callOnLane(lane, task, timeoutMs);
        }

        void retireAll() {
            List<Lane> copy;
            synchronized (lanes) {
                copy = new ArrayList<Lane>(lanes);
                lanes.clear();
            }
            for (Lane lane : copy) {
                try {
                    lane.close();
                } catch (RuntimeException e) {
                    core.log.warn("retiring lane " + lane.name() + " failed: " + e.getClass().getName());
                }
            }
        }
    }

    private final class Spi implements SpiRegistry {
        private final List<AutoCloseable> handles = new ArrayList<AutoCloseable>();
        /** The thread policy providers this module registered (their quiescent points close with it). */
        private final List<ThreadPolicyProvider> providers = new ArrayList<ThreadPolicyProvider>();

        @Override
        public <T> AutoCloseable register(Class<T> type, T implementation) {
            AutoCloseable handle = core.spi.register(type, implementation);
            synchronized (handles) {
                handles.add(handle);
                if (type == ThreadPolicyProvider.class) {
                    providers.add((ThreadPolicyProvider) implementation);
                }
            }
            return handle;
        }

        List<ThreadPolicyProvider> providers() {
            synchronized (handles) {
                return new ArrayList<ThreadPolicyProvider>(providers);
            }
        }

        @Override
        public <T> List<T> all(Class<T> type) {
            return core.spi.all(type);
        }

        @Override
        public <T> T first(Class<T> type) {
            return core.spi.first(type);
        }

        void removeAll() {
            List<AutoCloseable> copy;
            synchronized (handles) {
                copy = new ArrayList<AutoCloseable>(handles);
                handles.clear();
                providers.clear();
            }
            for (AutoCloseable handle : copy) {
                try {
                    handle.close();
                } catch (Exception ignored) {
                    // The core's handles do not throw.
                }
            }
        }
    }

    /** Records this module as the owner of what it declares or sets first, so that it can be withdrawn (A18). */
    private final class Capabilities implements CapabilityRegistry {

        @Override
        public AutoCloseable declare(String id, Probe probe) {
            return core.capabilities.declare(id, probe, ModuleContext.this);
        }

        @Override
        public void undeclare(String id) {
            core.capabilities.undeclare(id);
        }

        @Override
        public void set(String id, CapabilityState state, String reason, String strategy) {
            core.capabilities.set(id, state, reason, strategy, ModuleContext.this);
        }

        @Override
        public Capability get(String id) {
            return core.capabilities.get(id);
        }

        @Override
        public Map<String, Capability> snapshot() {
            return core.capabilities.snapshot();
        }

        void removeAll() {
            core.capabilities.removeOwnedBy(ModuleContext.this);
        }
    }
}
