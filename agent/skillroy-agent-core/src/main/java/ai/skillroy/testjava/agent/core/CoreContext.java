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
import ai.skillroy.testjava.agent.api.PrimitiveRegistry;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.protocol.LoadMode;

import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * The runtime's {@link AgentContext}: a fixed set of collaborators created at start. Every accessor returns the same
 * object for the agent's lifetime; {@link #targetReady()} reads the runtime's live readiness flag.
 *
 * <p>Thread-safe (immutable apart from the readiness flag it reads).
 */
final class CoreContext implements AgentContext {

    private final AgentRuntime runtime;
    private final Instrumentation instrumentation;
    private final LoadMode loadMode;
    private final Map<String, String> options;
    private final ClassLoader hostClassLoader;
    private final Object hostContext;
    final CorePrimitiveRegistry primitives;
    final RingEventBus events;
    final WeakHandleRegistry handles;
    final CoreThreadBroker threads;
    final CoreCapabilityRegistry capabilities;
    final CoreSpiRegistry spi;
    final RingLog log;
    private final boolean readOnly;

    CoreContext(AgentRuntime runtime, Instrumentation instrumentation, LoadMode loadMode, Map<String, String> options,
                ClassLoader hostClassLoader, Object hostContext, CorePrimitiveRegistry primitives,
                RingEventBus events, WeakHandleRegistry handles, CoreThreadBroker threads,
                CoreCapabilityRegistry capabilities, CoreSpiRegistry spi, RingLog log, boolean readOnly) {
        this.runtime = runtime;
        this.instrumentation = instrumentation;
        this.loadMode = loadMode;
        this.options = options;
        this.hostClassLoader = hostClassLoader;
        this.hostContext = hostContext;
        this.primitives = primitives;
        this.events = events;
        this.handles = handles;
        this.threads = threads;
        this.capabilities = capabilities;
        this.spi = spi;
        this.log = log;
        this.readOnly = readOnly;
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
        return options;
    }

    @Override
    public ClassLoader hostClassLoader() {
        return hostClassLoader;
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
        return handles;
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
        return readOnly;
    }

    @Override
    public boolean targetReady() {
        return runtime.isReady();
    }
}
