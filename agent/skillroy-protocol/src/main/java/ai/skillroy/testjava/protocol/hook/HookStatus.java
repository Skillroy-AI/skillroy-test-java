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

package ai.skillroy.testjava.protocol.hook;

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What an install or a remove did to one hook (phase6-contracts.md sections 4.1 and 4.2): JSON {@code {id, state,
 * reason?, strategy?, capability}}.
 *
 * <p>From {@code hook.install}: {@code state} is the hook's capability state, {@code OK} (with strategy
 * {@code advice}) for an installed hook and the probe's {@code DEGRADED} or {@code UNAVAILABLE} with its reason for one
 * that is not installed; {@code capability} is {@code hook.<id>} when the hook was declared, else null. From
 * {@code hook.remove}: {@code OK} for a hook removed and restored, {@code DEGRADED} with the verification's reason
 * for one restored with a difference, {@code UNAVAILABLE} for an id that was not installed ({@code "not installed"})
 * or whose class kept a residue ({@code "residue after remove"}); {@code capability} names the withdrawn capability.
 *
 * <p>Read leniently (an unknown key is ignored). Immutable; safe to share between threads.
 */
public final class HookStatus {

    private static final String TYPE = "HookStatus";

    private final String id;
    private final CapabilityState state;
    private final String reason;
    private final String strategy;
    private final String capability;

    /**
     * @param id the hook id, not null
     * @param state not null
     * @param reason may be null
     * @param strategy may be null
     * @param capability the capability id {@code hook.<id>}, or null when it was not declared
     */
    public HookStatus(String id, CapabilityState state, String reason, String strategy, String capability) {
        this.id = Objects.requireNonNull(id, "id");
        this.state = Objects.requireNonNull(state, "state");
        this.reason = reason;
        this.strategy = strategy;
        this.capability = capability;
    }

    public String id() {
        return id;
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

    /** May be null. */
    public String capability() {
        return capability;
    }

    /** {@code {"id","state","reason"?,"strategy"?,"capability"}} ({@code capability} always present); a new map. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("id", id);
        json.put("state", state.name());
        if (reason != null) {
            json.put("reason", reason);
        }
        if (strategy != null) {
            json.put("strategy", strategy);
        }
        json.put("capability", capability);
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}; {@code id} and {@code state} are required.
     *
     * @throws JsonException on a missing required field, an unknown state or a field of the wrong type
     */
    public static HookStatus fromJson(Map<String, Object> json) {
        HookFields.requireObject(json, TYPE);
        return new HookStatus(HookFields.requireString(json, "id", TYPE),
                HookFields.requireEnum(json, "state", CapabilityState.class, TYPE),
                HookFields.optString(json, "reason", TYPE), HookFields.optString(json, "strategy", TYPE),
                HookFields.optString(json, "capability", TYPE));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookStatus)) {
            return false;
        }
        HookStatus other = (HookStatus) o;
        return id.equals(other.id) && state == other.state && Objects.equals(reason, other.reason)
                && Objects.equals(strategy, other.strategy) && Objects.equals(capability, other.capability);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, state, reason, strategy, capability);
    }

    @Override
    public String toString() {
        return TYPE + Json.write(toJson());
    }
}
