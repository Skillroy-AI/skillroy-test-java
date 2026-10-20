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

package ai.skillroy.testjava.agent.api;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A unit of agent functionality (the generic Swing and data modules, or one target application's domain module),
 * discovered with {@code ServiceLoader} from a module jar and installed by the core. Lets features be added to the
 * agent without changing the core.
 *
 * <p>The core calls these methods one at a time, never concurrently for the same module.
 *
 * <p>Agent threads (those that run {@code install}, {@code onTargetReady}, {@code uninstall}, primitives, probes and
 * SPI calls) carry the host's class loader ({@link AgentContext#hostClassLoader()}) as their context class loader
 * (finding S34), so that context-loader lookups and caches in host code are keyed on the host's loader. A module that
 * needs its own loader, for example for {@code ServiceLoader}, passes it explicitly. Agent threads are daemon threads
 * in the agent's thread group, and a thread host code creates on them inherits both: providers and modules must not
 * lazily start host infrastructure that spawns threads (pools, timers) on an agent thread; where that cannot be
 * avoided, run the call under {@code ThreadPolicy.APP} with a {@code ThreadPolicyProvider}, on a host thread.
 */
public interface AgentModule {

    /** Stable module id ("swing", "data", ...), used in {@link #requires()} and reported by {@code agent.info}. */
    String id();

    /** Module version, reported by {@code agent.info}. */
    String version();

    /** Ids of modules that must be installed before this one. Unknown ids keep this module from installing. */
    default Set<String> requires() {
        return Collections.emptySet();
    }

    /**
     * Called once at agent start, on the agent's own thread, possibly before the application is initialised.
     * Register primitives, SPIs, capabilities. Must not touch application singletons.
     *
     * <p>It runs on the agent's start thread while the host's start call ({@code premain}, {@code agentmain},
     * {@code EmbeddedAgent.start}) waits for it, bounded by the agent's start timeout. It must therefore not wait
     * for the event-dispatch thread or for the thread that called the start (either may be the one waiting), and it
     * must not initialise AWT: in JAVAAGENT mode it runs before the application's {@code main}, and touching AWT
     * there can freeze the application's headless decision or start the toolkit on the wrong thread. Work that needs
     * the application or its UI belongs in {@link #onTargetReady}, or in a primitive or probe that runs later.
     */
    void install(AgentContext ctx) throws Exception;

    /** Called once after every registered ReadinessSource reports ready, on an agent thread of its own. */
    default void onTargetReady(AgentContext ctx) throws Exception {
    }

    /**
     * Reverse of install; must remove every listener it added to the host. Called on an agent thread when the agent
     * is disabled; must not throw and must not block for long.
     */
    default void uninstall() {
    }

    /**
     * What the module wants an operator to see about its running state, shown as the {@code info} entry of the
     * module's record in {@code agent.info.modules[]} when non-empty (and printed by {@code skillroy info}); empty by
     * default (Phase 8, phase8-contracts.md 3.2 rule 4, A68). Plain JSON values only (strings, numbers, booleans,
     * lists and maps of them); called on an agent thread, must be cheap, must not block and should not throw (a
     * failure is logged and the entry left out).
     */
    default Map<String, Object> info() {
        return Collections.emptyMap();
    }
}
