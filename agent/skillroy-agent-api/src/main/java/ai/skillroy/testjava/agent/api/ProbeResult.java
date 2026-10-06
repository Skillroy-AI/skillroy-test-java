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

import ai.skillroy.testjava.protocol.CapabilityState;

import java.util.Objects;

/**
 * Outcome of a {@link Probe}: the state plus a reason or strategy for the capability matrix.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class ProbeResult {

    private static final ProbeResult OK = new ProbeResult(CapabilityState.OK, null, null);

    private final CapabilityState state;
    private final String reason;
    private final String strategy;

    private ProbeResult(CapabilityState state, String reason, String strategy) {
        this.state = state;
        this.reason = reason;
        this.strategy = strategy;
    }

    /** Works, default strategy. */
    public static ProbeResult ok() {
        return OK;
    }

    /** Works, using the named strategy. */
    public static ProbeResult ok(String strategy) {
        return new ProbeResult(CapabilityState.OK, null, strategy);
    }

    /** Works with limitations, explained by {@code reason}. */
    public static ProbeResult degraded(String reason) {
        return new ProbeResult(CapabilityState.DEGRADED, reason, null);
    }

    /** Does not work, explained by {@code reason}. */
    public static ProbeResult unavailable(String reason) {
        return new ProbeResult(CapabilityState.UNAVAILABLE, reason, null);
    }

    public CapabilityState state() {
        return state;
    }

    /** May be null. */
    public String reason() {
        return reason;
    }

    /** May be null. */
    public String strategy() {
        return strategy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProbeResult)) {
            return false;
        }
        ProbeResult other = (ProbeResult) o;
        return state == other.state && Objects.equals(reason, other.reason)
                && Objects.equals(strategy, other.strategy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, reason, strategy);
    }

    @Override
    public String toString() {
        return "ProbeResult{state=" + state + ", reason=" + reason + ", strategy=" + strategy + "}";
    }
}
