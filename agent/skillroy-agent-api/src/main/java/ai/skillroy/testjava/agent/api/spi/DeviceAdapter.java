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

import java.util.Collections;
import java.util.List;
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

    /**
     * The rung ladder of an action, best first (phase8-contracts.md 7.6, amendment A85): for an action that has a
     * physical path and a shortcut, the names of its rungs ({@code RungResult.PHYSICAL}: through the device's own
     * path, a simulator or the application's driver code, where the real device hands data over;
     * {@code RungResult.SEMANTIC}: a shortcut into the application), each matching {@code RungResult.NAME}. An action
     * with a ladder reports the rung that acted in its result ({@code RungResult}'s keys), refuses to act below a
     * {@code minRung} argument ({@code CAPABILITY_UNAVAILABLE} with {@code data {rung, minRung, capability?}}) and
     * echoes {@code minRung} when it received one. {@code device.list} shows a non-empty ladder as
     * {@code rungs: {<action>: [..]}}; one that throws is logged and read as empty.
     *
     * <p>The default is empty: the action has no ladder.
     */
    default List<String> rungs(String action) {
        return Collections.emptyList();
    }
}
