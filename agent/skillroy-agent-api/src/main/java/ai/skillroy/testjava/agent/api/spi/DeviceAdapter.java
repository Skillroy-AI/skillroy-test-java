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

package ai.skillroy.testjava.agent.api.spi;

import ai.skillroy.testjava.protocol.JsonView;

import java.util.Map;
import java.util.Set;

/**
 * Peripherals as observable, actionable nodes. Lets scenarios read and drive devices (printers, scanners, badge readers)
 * through {@code device.state} and {@code device.act} without device-specific primitives.
 *
 * <p>{@link #state} and {@link #act} are called under {@code ThreadPolicy.APP}; the others from any agent thread.
 *
 * <p>Agent threads carry the host's class loader as context class loader and are daemon threads in the agent's
 * thread group, which threads created on them inherit: do not lazily start host infrastructure that spawns threads
 * (connection-pool housekeeping, driver timers) from these calls; see {@code AgentModule} (finding S34).
 */
public interface DeviceAdapter {

    /** Device id, unique within the agent. */
    String id();

    /** Device kind ("printer", "scanner", ...). */
    String kind();

    /** A JSON-able snapshot of the device's state; no side effects. */
    Map<String, Object> state();

    /** Action names accepted by {@link #act}. */
    Set<String> actions();

    /** Performs an action; returns a JSON-able result. Throw RpcException(INVALID_PARAMS) for an unknown action. */
    Object act(String action, JsonView args) throws Exception;
}
