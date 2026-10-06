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

import java.lang.instrument.Instrumentation;

/**
 * Entry point of the agent jar for {@code -javaagent} (JAVAAGENT) and dynamic attach (ATTACH). It is the only agent
 * class the host JVM loads directly; everything else runs in the isolated agent loader built from
 * {@code <home>/lib}.
 *
 * <p>The agent argument string is {@code k=v;k=v} (phase1-contracts.md 2.4); {@code cmd=stop} closes a running
 * agent. Both methods are idempotent: when an agent already runs in this JVM a further call re-publishes discovery
 * and returns. Neither ever throws into the host: failures are recorded in the system property
 * {@code skillroy.agent.error} and reported as one {@code [skillroy]} line on {@code System.err}.
 *
 * <p>Threading: called by the JVM on the main thread (premain) or the attach listener thread (agentmain); both
 * return once the agent is serving or has refused, bounded by the core's start timeout.
 */
public final class AgentBootstrap {

    private AgentBootstrap() {
    }

    /**
     * JAVAAGENT mode. Starts only when enabled by the system property {@code skillroy.agent.enabled} or the
     * environment variable {@code SKILLROY_AGENT_ENABLED}, and, when {@code mainClassGuard} is set, only if the
     * launch target in {@code sun.java.command} (the main class, or the jar path for {@code -jar}) equals it: the
     * command equals the guard or continues with white space after it (finding S17).
     */
    public static void premain(String args, Instrumentation inst) {
        load(BootOptions.JAVAAGENT, args, inst);
    }

    /** ATTACH mode; {@code enabled=true} in the attach arguments or the options file they name is accepted. */
    public static void agentmain(String args, Instrumentation inst) {
        load(BootOptions.ATTACH, args, inst);
    }

    private static void load(String mode, String args, Instrumentation inst) {
        try {
            BootLauncher.load(mode, args, null, inst, ClassLoader.getSystemClassLoader(), null);
        } catch (Throwable t) {
            // An exception out of premain would abort the host JVM's start; BootLauncher reports its own failures.
            try {
                BootLauncher.fail(mode + " start failed", t);
            } catch (Throwable ignored) {
                // Nothing may reach the host.
            }
        }
    }
}
