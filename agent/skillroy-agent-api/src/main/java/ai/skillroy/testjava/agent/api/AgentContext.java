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

import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.SecretKeys;

import java.lang.instrument.Instrumentation;
import java.util.Map;

/**
 * Everything a module may use from the running agent, handed to {@link AgentModule#install} and available to
 * primitives through {@link Invocation#context()}. Keeps modules decoupled from the core's implementation classes.
 *
 * <p>Implementations are thread-safe; every accessor returns the same object for the agent's lifetime.
 */
public interface AgentContext {

    /** The JVM's Instrumentation, or null when not available (EMBEDDED without self-attach). */
    Instrumentation instrumentation();

    /** How the agent was loaded. */
    LoadMode loadMode();

    /**
     * Merged agent options (defaults, system properties, options file, agent arguments) as they were at start;
     * unmodifiable. A system property set after the start is not in it: see {@link #option(String)}.
     */
    Map<String, String> options();

    /**
     * One option, looked up live (contract amendment A20): its value in the start-time {@link #options()} if present,
     * else the <em>current</em> value of the system property {@code skillroy.agent.<key>}, else null. Lets a module
     * see an option the host sets after the agent has started. Never throws (a system property that cannot be read
     * counts as absent).
     */
    default String option(String key) {
        if (key == null) {
            return null;
        }
        String value = options().get(key);
        if (value != null) {
            return value;
        }
        try {
            return System.getProperty(Protocol.PROP_PREFIX + key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The loader of the target application's classes; use it to look up host classes reflectively. */
    ClassLoader hostClassLoader();

    /**
     * What the host passed to {@code EmbeddedAgent.start}; null in other load modes. Lets a domain module reach host
     * objects that are not reachable through statics; the agent itself never inspects it.
     */
    Object hostContext();

    /** Where primitives are registered and looked up. */
    PrimitiveRegistry primitives();

    /** The agent's event bus. */
    EventBus events();

    /** Stable, weakly held handles for live host objects. */
    HandleRegistry handles();

    /** Runs work on the EDT, an agent worker or the application's own thread, with timeouts. */
    ThreadBroker threads();

    /** Capability declarations and probe results. */
    CapabilityRegistry capabilities();

    /** Registered implementations of the {@code spi} interfaces. */
    SpiRegistry spi();

    /** The agent's log (never {@code System.out}). */
    AgentLog log();

    /** True when the {@code readOnly} option is set: mutating primitives are rejected. */
    boolean readOnly();

    /** True once every ReadinessSource has reported ready and modules have run {@code onTargetReady}. */
    boolean targetReady();

    /**
     * The hook engine (phase6-contracts.md section 3.6, D118): {@code spi().first(HookEngine.class)}, null when the
     * optional hooks module is not loaded. Look it up in {@code onTargetReady}, never in {@code requires()}.
     */
    default HookEngine hooks() {
        SpiRegistry registry = spi();
        return registry == null ? null : registry.first(HookEngine.class);
    }

    /**
     * True if the key names a secret (phase6-contracts.md section 3.6). The core's contexts answer with the agent's
     * own rule: the protocol's {@link SecretKeys} rule, the operator's {@code redact} option and every registered
     * {@code RedactionKeys} pattern. This default (for test fakes) applies the protocol's rule only.
     */
    default boolean isSecretKey(String key) {
        return key != null && SecretKeys.looksSecret(key);
    }

    /**
     * The text with its secret values hidden (phase6-contracts.md section 3.6): in the core's contexts the agent's
     * redaction of free text (a secret {@code key=value} pair's value, the password of URL user-info, under the same
     * keys as {@link #isSecretKey}). This default (for test fakes) returns the text unchanged. Null stays null.
     */
    default String redactText(String text) {
        return text;
    }
}
