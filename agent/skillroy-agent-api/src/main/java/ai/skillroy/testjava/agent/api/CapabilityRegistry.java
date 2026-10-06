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

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;

import java.util.Map;

/**
 * The agent side of the capability matrix: modules declare what they can do and how to check it, and the core
 * probes once the target is ready. Lets the controller refuse a scenario up front instead of failing it half-way.
 *
 * <p>A capability can be withdrawn (contract amendment A18): by closing the handle {@link #declare} returns, by
 * {@link #undeclare}, or by the core when the module that declared or set it is uninstalled (or its install fails).
 * A withdrawn capability is no longer listed by {@code /capabilities} and {@code agent.capabilities}, and a primitive
 * that names it answers {@code CAPABILITY_UNAVAILABLE} with the reason "capability withdrawn" until the id is
 * declared or set again.
 *
 * <p>Implementations are thread-safe.
 */
public interface CapabilityRegistry {

    /**
     * Declares a capability checked by a probe: probed after onTargetReady, and again on
     * {@code agent.capabilities{reprobe}} (at once when the target is already ready).
     *
     * @return a handle whose {@code close()} withdraws the capability, as {@link #undeclare} does, but only while
     *     this declaration is still the current one (a later declaration of the same id is left alone); closing it
     *     twice is harmless and it never throws
     */
    AutoCloseable declare(String id, Probe probe);

    /** Withdraws the capability (see the class comment); no effect for an id that is not declared. */
    void undeclare(String id);

    /** Sets a capability's state directly (declaring it if needed), for states known without probing. */
    void set(String id, CapabilityState state, String reason, String strategy);

    /** Null if never declared, or withdrawn. */
    Capability get(String id);

    /** Every capability, sorted by id; a snapshot. */
    Map<String, Capability> snapshot();
}
