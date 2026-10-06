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

import java.util.Map;

/**
 * The app{...} section of a UI snapshot: current state name, prompt, anything a scenario asserts on. Cheap, no side
 * effects. Served by {@code app.state} so that generic tooling can show what state the application is in.
 *
 * <p>Called under {@code ThreadPolicy.APP} (the application's own thread when one is provided).
 *
 * <p>Agent threads carry the host's class loader as context class loader and are daemon threads in the agent's
 * thread group, which threads created on them inherit: do not lazily start host infrastructure that spawns threads
 * (connection-pool housekeeping, driver timers) from these calls; see {@code AgentModule} (finding S34).
 */
public interface AppStateProvider {

    /** Key of this provider's section in {@code app.state}. */
    String id();

    /** A JSON-able map; must not change application state. */
    Map<String, Object> appState();
}
