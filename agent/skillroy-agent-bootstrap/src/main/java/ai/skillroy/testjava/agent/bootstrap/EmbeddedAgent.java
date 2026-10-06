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

package ai.skillroy.testjava.agent.bootstrap;

import java.util.Properties;

/**
 * EMBEDDED mode: lets a host start the agent through its own plugin mechanism, without changing the launch command
 * and without {@code Instrumentation}. The bootstrap jar is on the host class path (or in a loader the plugin
 * creates); the rest of the agent is loaded from the agent home (the directory of the bootstrap jar, or the
 * {@code home} option) in an isolated loader.
 *
 * <p>The agent starts only when {@code enabled=true} is in the given options, the system property
 * {@code skillroy.agent.enabled} is {@code true} or the environment variable {@code SKILLROY_AGENT_ENABLED} is
 * {@code true}. The options have the highest precedence (phase1-contracts.md 2.4).
 *
 * <p>Idempotent and re-entrant (contract amendment A6): a call while an agent is running in this JVM, in any load
 * mode and from any copy of the bootstrap (for example one started with {@code -javaagent} as well), returns
 * {@code true} and changes nothing: neither these options nor the host context are applied. A call made while the
 * agent is starting, from the thread that is starting it or from one of the agent's own threads (a module's
 * {@code install}), also returns {@code true} at once instead of waiting for itself.
 *
 * <p>Reflective use: the methods are found by name, so a host plugin class can call them without the bootstrap jar
 * on its compile or link class path, and keep running when the jar is absent:
 * <pre>{@code
 * Class<?> agent = Class.forName("ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent", true, loader);
 * Method start = agent.getMethod("start", Properties.class, ClassLoader.class, Object.class);
 * boolean started = (Boolean) start.invoke(null, options, hostLoader, hostContext);
 * }</pre>
 * The signatures use JDK types only and do not change.
 *
 * <p>Threading: may be called from any host thread; {@code start} returns once the agent serves or has refused. It
 * never blocks longer than the start timeout (60 s, module installs included) for its own start, and waits at most
 * as long again for a start or stop another thread has in progress, so a {@code start} on the EDT may block it for
 * up to 65 s. Host hooks that must return quickly, or that run on the event-dispatch thread, use
 * {@link #startAsync}, which returns at once. Nothing here ever throws.
 */
public final class EmbeddedAgent {

    private EmbeddedAgent() {
    }

    /**
     * Starts the agent with the caller's context class loader as the host loader.
     *
     * @return true if the agent is running (started now or already running), false if it refused or failed
     */
    public static boolean start(Properties options) {
        ClassLoader context = null;
        try {
            context = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {
            // A security manager may refuse; the bootstrap's own loader is used then.
        }
        return start(options, context, null);
    }

    /**
     * As {@link #start(Properties)} with an explicit host loader (the loader of the host's application classes).
     *
     * @return true if the agent is running (started now or already running), false if it refused or failed
     */
    public static boolean start(Properties options, ClassLoader hostLoader) {
        return start(options, hostLoader, null);
    }

    /**
     * As {@link #start(Properties, ClassLoader)}.
     *
     * @param hostContext any object the host wants its own domain module to receive through
     *     {@code AgentContext.hostContext()}; the agent never inspects it. Lets a module reach host objects that are
     *     not reachable through statics.
     * @return true if the agent is running (started now or already running), false if it refused or failed
     */
    public static boolean start(Properties options, ClassLoader hostLoader, Object hostContext) {
        try {
            ClassLoader loader = hostLoader != null ? hostLoader : EmbeddedAgent.class.getClassLoader();
            return BootLauncher.load(BootOptions.EMBEDDED, null, options, null, loader, hostContext);
        } catch (Throwable t) {
            // BootLauncher.load reports its own failures; this catches what could not even reach it.
            report("EMBEDDED start failed", t);
            return false;
        }
    }

    /**
     * Starts the agent without blocking the caller (contract amendment A22): returns at once, and the start runs on a
     * daemon thread {@code skillroy-start} with the same enable and idempotence rules as
     * {@link #start(Properties, ClassLoader, Object)}. Its outcome is visible as the system property
     * {@code skillroy.agent.loadMode} (set while an agent is running) or {@code skillroy.agent.error} (set when the
     * start failed or refused; cleared by this call when a start is initiated). Meant for host plugin hooks that must
     * return quickly or run on the event-dispatch thread. A {@link #stop()} made after this call and before the start
     * has begun withdraws the start. Callable by name with JDK types only; never throws.
     *
     * @param options the agent options, copied at once (later changes by the caller have no effect)
     * @param hostLoader the loader of the host's application classes (null: this class's loader)
     * @param hostContext as for {@link #start(Properties, ClassLoader, Object)}
     * @return true when a start was initiated, a start is already in progress or an agent is already running; false
     *     when the start is refused outright (not enabled) or cannot be initiated
     */
    public static boolean startAsync(Properties options, ClassLoader hostLoader, Object hostContext) {
        try {
            ClassLoader loader = hostLoader != null ? hostLoader : EmbeddedAgent.class.getClassLoader();
            return BootLauncher.loadAsync(options, loader, hostContext);
        } catch (Throwable t) {
            report("EMBEDDED start failed", t);
            return false;
        }
    }

    /**
     * Stops the agent this bootstrap started (in any mode); no effect if none is running. Never throws. Called on
     * the event-dispatch thread, or on a thread an agent module declared forbidden, it returns at once and the agent
     * finishes stopping in the background, also when another thread's start or stop is in progress: the stop then
     * runs after that operation (finding S33). On any other thread it returns when the agent has stopped (bounded);
     * while another thread is starting the agent it returns at once and the stop runs after that start. Called while
     * the agent is starting, from the starting thread or from an agent thread, it returns at once and the agent stops
     * as soon as the start has finished.
     */
    public static void stop() {
        try {
            BootLauncher.stop();
        } catch (Throwable t) {
            report("stop failed", t);
        }
    }

    private static void report(String what, Throwable t) {
        try {
            BootLauncher.fail(what, t);
        } catch (Throwable ignored) {
            // Not even the bootstrap could be loaded; nothing may reach the host.
        }
    }
}
