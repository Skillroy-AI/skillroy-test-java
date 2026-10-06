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

package ai.skillroy.testjava.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entry of the capability matrix: whether a feature works in this target, why not, and which strategy it uses.
 * The id lives outside the JSON form because the wire shape is a map keyed by id
 * ({@code {"capabilities":{id:{"state","reason","strategy"}}}}).
 *
 * <p>Immutable; safe to share between threads.
 */
public final class Capability {

    private static final String TYPE = "Capability";

    private final String id;
    private final CapabilityState state;
    private final String reason;
    private final String strategy;

    /**
     * @param reason why the state is not OK (or a note), may be null
     * @param strategy which implementation strategy is in use, may be null
     */
    public Capability(String id, CapabilityState state, String reason, String strategy) {
        this.id = Objects.requireNonNull(id, "id");
        this.state = Objects.requireNonNull(state, "state");
        this.reason = reason;
        this.strategy = strategy;
    }

    public String id() {
        return id;
    }

    public CapabilityState state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public String strategy() {
        return strategy;
    }

    /** {@code {"state":…,"reason":…,"strategy":…}} with all three keys present (null values written as null). */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("state", state.name());
        json.put("reason", reason);
        json.put("strategy", strategy);
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}.
     *
     * @throws JsonException if {@code state} is missing or not a {@link CapabilityState} name
     */
    public static Capability fromJson(String id, Map<String, Object> json) {
        Wire.requireObject(json, TYPE);
        String state = Wire.requireString(json, "state", TYPE);
        CapabilityState parsed;
        try {
            parsed = CapabilityState.valueOf(state);
        } catch (IllegalArgumentException unknown) {
            throw new JsonException(TYPE + ": unknown state \"" + state + "\"");
        }
        return new Capability(id, parsed, Wire.optString(json, "reason", TYPE), Wire.optString(json, "strategy", TYPE));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Capability)) {
            return false;
        }
        Capability other = (Capability) o;
        return id.equals(other.id) && state == other.state && Objects.equals(reason, other.reason)
                && Objects.equals(strategy, other.strategy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, state, reason, strategy);
    }

    @Override
    public String toString() {
        return TYPE + "{id=" + id + ", " + Coerce.safeWrite(toJson()) + "}";
    }
}
