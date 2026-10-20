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

/**
 * Where a primitive runs. Swing state must be touched on the EDT, domain state often only on the application's own
 * thread, and the agent must never block either, so every primitive declares one of these.
 */
public enum ThreadPolicy {
    /** The control-channel worker thread. */
    CALLER,
    /** EventQueue.invokeAndWait, bounded by the timeout; for reads. */
    EDT_SYNC,
    /** EventQueue.invokeLater, returns once posted; for actions (an action may open a modal dialog). */
    EDT_ASYNC,
    /** An agent-owned daemon worker. */
    WORKER,
    /**
     * An application-registered thread supplied by the first registered {@code ThreadPolicyProvider}; falls back to
     * WORKER when no provider is registered. While that provider reports
     * {@link ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider#available() available()} {@code false} (the
     * application has not created its thread yet; contract amendment A19), a primitive with this policy runs on
     * WORKER if its spec is not {@code mutating}, and answers {@code NOT_READY} ("application thread not available
     * yet") if it is. {@code ThreadBroker.call} and {@code post} under APP run on WORKER in that case.
     */
    APP,
    /**
     * Runs on the application's own thread at its next quiescent point, through the first
     * {@code ThreadPolicyProvider}'s {@link ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider#quiescentPoint()
     * quiescentPoint()}; never falls back to another thread (phase8-contracts.md 4.1, D519). Admitted by the spec's
     * {@link PrimitiveSpec#quiescentBudgetMs() budget} and never pre-empted: the agent never interrupts the
     * application's thread. Without a point the call answers {@code CAPABILITY_UNAVAILABLE}, while the provider is
     * not {@code available()} {@code NOT_READY}, for reads and mutating calls alike. {@code ThreadBroker.call} under
     * this policy is {@code callQuiescent(task, 50, timeoutMs)}; {@code post} refuses it (a posted task has no caller
     * whose time bounds it).
     */
    APP_QUIESCENT,
    /**
     * Runs on a named lane of agent threads that the primitive's module declared with
     * {@link ThreadBroker#declareLane}; the spec names it ({@link PrimitiveSpec.Builder#lane(String)}). A lane the
     * module has not declared, or has retired, answers {@code CAPABILITY_UNAVAILABLE} (phase8-contracts.md 4.2,
     * D526). {@code post} refuses it (no lane name in {@code post}).
     */
    LANE
}
