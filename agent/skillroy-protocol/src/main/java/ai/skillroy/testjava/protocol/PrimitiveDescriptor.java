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
 * One entry of {@code GET /schema}: what a primitive is called, what it takes and returns, where it runs and how it
 * may be used. The controller and authoring tools read the catalogue from the live agent instead of hard-coding it.
 *
 * <p>Immutable (schemas are deep-copied, see {@link Event}); safe to share between threads.
 */
public final class PrimitiveDescriptor {

    private static final String TYPE = "PrimitiveDescriptor";

    private final String name;
    private final String description;
    private final Map<String, Object> paramsSchema;
    private final Map<String, Object> resultSchema;
    private final String thread;
    private final long timeoutMs;
    private final String capabilityId;
    private final boolean mutating;
    private final boolean destructive;
    private final boolean script;
    private final long quiescentBudgetMs;
    private final String lane;

    /**
     * @param name primitive name, not null
     * @param paramsSchema JSON Schema of {@code params}, may be null
     * @param resultSchema JSON Schema of the result value, may be null
     * @param thread name of the thread policy the primitive runs under, may be null
     * @param capabilityId capability the call depends on, may be null
     * @param mutating the call changes target state (rejected in read-only mode)
     * @param destructive the call needs {@code _meta.confirm=true}
     * @throws JsonException if a schema holds NaN or Infinity or nests deeper than 184
     */
    public PrimitiveDescriptor(String name, String description, Map<String, Object> paramsSchema,
                               Map<String, Object> resultSchema, String thread, long timeoutMs, String capabilityId,
                               boolean mutating, boolean destructive) {
        this(name, description, paramsSchema, resultSchema, thread, timeoutMs, capabilityId, mutating, destructive,
                false);
    }

    /**
     * As the nine-argument constructor, with the {@code script} flag (phase6-contracts.md 12.9, D156; security fix
     * S42).
     *
     * @param script the call evaluates a caller-supplied expression or script in the target (rejected in read-only
     *     mode, as a mutating call is)
     * @throws JsonException if a schema holds NaN or Infinity or nests deeper than 184
     */
    public PrimitiveDescriptor(String name, String description, Map<String, Object> paramsSchema,
                               Map<String, Object> resultSchema, String thread, long timeoutMs, String capabilityId,
                               boolean mutating, boolean destructive, boolean script) {
        this(name, description, paramsSchema, resultSchema, thread, timeoutMs, capabilityId, mutating, destructive,
                script, 0L, null);
    }

    /**
     * As the ten-argument constructor, with the two thread keys of Phase 8 (phase8-contracts.md 4.1, 4.2; D519,
     * D526), each written by {@link #toJson()} only when set.
     *
     * @param quiescentBudgetMs the budget of a primitive whose thread is {@code APP_QUIESCENT}; 0 (or less) for any
     *     other primitive
     * @param lane the lane of a primitive whose thread is {@code LANE}, or null
     * @throws JsonException if a schema holds NaN or Infinity or nests deeper than 184
     */
    public PrimitiveDescriptor(String name, String description, Map<String, Object> paramsSchema,
                               Map<String, Object> resultSchema, String thread, long timeoutMs, String capabilityId,
                               boolean mutating, boolean destructive, boolean script, long quiescentBudgetMs,
                               String lane) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.paramsSchema = Coerce.freezeMap(paramsSchema);
        this.resultSchema = Coerce.freezeMap(resultSchema);
        this.thread = thread;
        this.timeoutMs = timeoutMs;
        this.capabilityId = capabilityId;
        this.mutating = mutating;
        this.destructive = destructive;
        this.script = script;
        this.quiescentBudgetMs = quiescentBudgetMs;
        this.lane = lane;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** Unmodifiable, or null. */
    public Map<String, Object> paramsSchema() {
        return paramsSchema;
    }

    /** Unmodifiable, or null. */
    public Map<String, Object> resultSchema() {
        return resultSchema;
    }

    public String thread() {
        return thread;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public String capabilityId() {
        return capabilityId;
    }

    public boolean mutating() {
        return mutating;
    }

    public boolean destructive() {
        return destructive;
    }

    /**
     * True when the call evaluates a caller-supplied expression or script in the target ({@code web.evaluate},
     * {@code web.waitFlag}): the agent's read-only mode and the CDP path's read-only check refuse it as they refuse a
     * mutating call, and a tool projection never marks it read-only (D156).
     */
    public boolean script() {
        return script;
    }

    /**
     * The most a task of an {@code APP_QUIESCENT} primitive may hold the application's thread, in ms; 0 for a primitive
     * under any other policy and for a catalogue entry of an agent older than Phase 8 (phase8-contracts.md 4.1).
     */
    public long quiescentBudgetMs() {
        return quiescentBudgetMs;
    }

    /** The lane a {@code LANE} primitive runs on, or null (phase8-contracts.md 4.2). */
    public String lane() {
        return lane;
    }

    /**
     * {@code {"name","description","paramsSchema","resultSchema","thread","timeoutMs","capabilityId","mutating",
     * "destructive"}}, all keys always present (null values written as null), then {@code "script": true} only when
     * the flag is set, so that the entry of a primitive without it reads as before (D156); then
     * {@code "quiescentBudgetMs"} only when positive and {@code "lane"} only when set (Phase 8, D519, D526), likewise.
     * A new map on each call.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("name", name);
        json.put("description", description);
        json.put("paramsSchema", paramsSchema);
        json.put("resultSchema", resultSchema);
        json.put("thread", thread);
        json.put("timeoutMs", Long.valueOf(timeoutMs));
        json.put("capabilityId", capabilityId);
        json.put("mutating", Boolean.valueOf(mutating));
        json.put("destructive", Boolean.valueOf(destructive));
        if (script) {
            json.put("script", Boolean.TRUE);
        }
        if (quiescentBudgetMs > 0) {
            json.put("quiescentBudgetMs", Long.valueOf(quiescentBudgetMs));
        }
        if (lane != null) {
            json.put("lane", lane);
        }
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}. Only {@code name} is required; absent fields take null, 0 or
     * false ({@code script}, {@code quiescentBudgetMs} and {@code lane} included: an older agent's catalogue has no
     * such key), so that a catalogue from a newer agent with fewer or extra fields still reads.
     *
     * @throws JsonException on a missing name or a field of the wrong type
     */
    public static PrimitiveDescriptor fromJson(Map<String, Object> json) {
        Wire.requireObject(json, TYPE);
        return new PrimitiveDescriptor(
                Wire.requireString(json, "name", TYPE),
                Wire.optString(json, "description", TYPE),
                Wire.optObject(json, "paramsSchema", TYPE),
                Wire.optObject(json, "resultSchema", TYPE),
                Wire.optString(json, "thread", TYPE),
                Wire.optLong(json, "timeoutMs", 0L, TYPE),
                Wire.optString(json, "capabilityId", TYPE),
                Wire.optBool(json, "mutating", false, TYPE),
                Wire.optBool(json, "destructive", false, TYPE),
                Wire.optBool(json, "script", false, TYPE),
                Wire.optLong(json, "quiescentBudgetMs", 0L, TYPE),
                Wire.optString(json, "lane", TYPE));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PrimitiveDescriptor)) {
            return false;
        }
        PrimitiveDescriptor other = (PrimitiveDescriptor) o;
        return name.equals(other.name) && Objects.equals(description, other.description)
                && Objects.equals(paramsSchema, other.paramsSchema) && Objects.equals(resultSchema, other.resultSchema)
                && Objects.equals(thread, other.thread) && timeoutMs == other.timeoutMs
                && Objects.equals(capabilityId, other.capabilityId) && mutating == other.mutating
                && destructive == other.destructive && script == other.script
                && quiescentBudgetMs == other.quiescentBudgetMs && Objects.equals(lane, other.lane);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, paramsSchema, resultSchema, thread, Long.valueOf(timeoutMs),
                capabilityId, Boolean.valueOf(mutating), Boolean.valueOf(destructive), Boolean.valueOf(script),
                Long.valueOf(quiescentBudgetMs), lane);
    }

    @Override
    public String toString() {
        return TYPE + Coerce.safeWrite(toJson());
    }
}
