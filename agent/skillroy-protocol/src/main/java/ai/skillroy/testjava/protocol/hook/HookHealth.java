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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How one installed hook is doing (phase6-contracts.md sections 3.6 and 4.1): JSON {@code {state, count, lastFiredMs?,
 * armed, reason?, strategy?, last?}}. {@code count} is the calls that reached the handler since the install,
 * {@code lastFiredMs} the last one's epoch milliseconds, {@code last} the last event's payload. {@code armed} means one
 * thing only: a {@link HookMode#STUB} hook holds a value; the value itself is never in a health answer.
 *
 * <p>Read leniently (an unknown key is ignored). Immutable (the payload is copied and unmodifiable); safe to share
 * between threads.
 */
public final class HookHealth {

    private static final String TYPE = "HookHealth";

    private final HookState state;
    private final long count;
    private final Long lastFiredMs;
    private final boolean armed;
    private final String reason;
    private final String strategy;
    private final Map<String, Object> last;

    /**
     * @param state not null
     * @param count the calls that reached the handler since the install
     * @param lastFiredMs epoch ms of the last such call, or null
     * @param armed a STUB hook holds a value
     * @param reason why the state is STALE or ERROR, may be null
     * @param strategy {@code advice}, may be null
     * @param last the last event's payload (JSON normal form), or null
     */
    public HookHealth(HookState state, long count, Long lastFiredMs, boolean armed, String reason, String strategy,
                      Map<String, Object> last) {
        this.state = Objects.requireNonNull(state, "state");
        this.count = count;
        this.lastFiredMs = lastFiredMs;
        this.armed = armed;
        this.reason = reason;
        this.strategy = strategy;
        this.last = last == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(last));
    }

    public HookState state() {
        return state;
    }

    public long count() {
        return count;
    }

    /** May be null. */
    public Long lastFiredMs() {
        return lastFiredMs;
    }

    public boolean armed() {
        return armed;
    }

    /** May be null. */
    public String reason() {
        return reason;
    }

    /** May be null. */
    public String strategy() {
        return strategy;
    }

    /** Unmodifiable, or null. */
    public Map<String, Object> last() {
        return last;
    }

    /** {@code {"state","count","lastFiredMs"?,"armed","reason"?,"strategy"?,"last"?}}; a new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("state", state.name());
        json.put("count", Long.valueOf(count));
        if (lastFiredMs != null) {
            json.put("lastFiredMs", lastFiredMs);
        }
        json.put("armed", Boolean.valueOf(armed));
        if (reason != null) {
            json.put("reason", reason);
        }
        if (strategy != null) {
            json.put("strategy", strategy);
        }
        if (last != null) {
            json.put("last", last);
        }
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}; {@code state} is required, {@code count} defaults to 0 and
     * {@code armed} to false.
     *
     * @throws JsonException on a missing state, an unknown state or a field of the wrong type
     */
    public static HookHealth fromJson(Map<String, Object> json) {
        HookFields.requireObject(json, TYPE);
        Long count = HookFields.optLong(json, "count", TYPE);
        return new HookHealth(HookFields.requireEnum(json, "state", HookState.class, TYPE),
                count == null ? 0L : count.longValue(), HookFields.optLong(json, "lastFiredMs", TYPE),
                HookFields.optBool(json, "armed", false, TYPE), HookFields.optString(json, "reason", TYPE),
                HookFields.optString(json, "strategy", TYPE), HookFields.optObject(json, "last", TYPE));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookHealth)) {
            return false;
        }
        HookHealth other = (HookHealth) o;
        return state == other.state && count == other.count && Objects.equals(lastFiredMs, other.lastFiredMs)
                && armed == other.armed && Objects.equals(reason, other.reason)
                && Objects.equals(strategy, other.strategy) && Objects.equals(last, other.last);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, Long.valueOf(count), lastFiredMs, Boolean.valueOf(armed), reason, strategy, last);
    }

    @Override
    public String toString() {
        return TYPE + Json.write(toJson());
    }
}
