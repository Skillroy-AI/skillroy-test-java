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
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveRegistry;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.LoadMode;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The context one module installs with: the core context, except that primitive, SPI and capability registrations
 * are recorded so that a module whose {@code install} fails, or that is uninstalled, leaves nothing behind (its
 * capabilities are withdrawn, contract amendment A18). Each accessor returns the same
 * object for the module's lifetime.
 *
 * <p>Thread-safe.
 */
final class ModuleContext implements AgentContext {

    private final CoreContext core;
    private final Primitives primitives = new Primitives();
    private final Spi spi = new Spi();
    private final Capabilities capabilities = new Capabilities();

    ModuleContext(CoreContext core) {
        this.core = core;
    }

    /** Removes everything the module registered through this context. */
    void rollback() {
        primitives.removeAll();
        spi.removeAll();
        capabilities.removeAll();
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
        return core.threads();
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

    private final class Spi implements SpiRegistry {
        private final List<AutoCloseable> handles = new ArrayList<AutoCloseable>();

        @Override
        public <T> AutoCloseable register(Class<T> type, T implementation) {
            AutoCloseable handle = core.spi.register(type, implementation);
            synchronized (handles) {
                handles.add(handle);
            }
            return handle;
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
