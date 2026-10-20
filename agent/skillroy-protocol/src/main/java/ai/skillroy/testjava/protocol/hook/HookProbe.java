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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the engine's probe found for one {@link HookSpec} (phase6-contracts.md sections 3.3 and 4.1): JSON
 * {@code {id, state: OK|DEGRADED|UNAVAILABLE, reason?, strategy?, loaders?, loaderIds?[], codeSources?[], declared?[],
 * sizeBytes?, methods?, spyVisibility: VISIBLE|NOT_VISIBLE|UNCHECKED}}. {@code loaders} is how many loaders define the
 * class, {@code loaderIds} the identities a later {@link HookTarget#loader()} may name, {@code codeSources} the
 * code-source name of each copy found, aligned with {@code loaderIds} (at most {@link #MAX_CODE_SOURCES}; never a
 * directory; phase8-contracts.md section 6.4), {@code declared} the descriptors the class does declare under the
 * method's name when a pinned one matches nothing, {@code sizeBytes} and {@code methods} the two bounds of section
 * 3.7.
 *
 * <p>Read leniently (an unknown key is ignored, as for the other answer types). Immutable; safe to share between
 * threads.
 */
public final class HookProbe {

    /** Whether the target class's loader resolves {@code Spy} to the bootstrap copy (section 3.3 item 6). */
    public enum SpyVisibility {
        /** It does. */
        VISIBLE,
        /** It does not (a strict-delegation loader), or it would shadow it with a copy of its own. */
        NOT_VISIBLE,
        /** Not decided yet: no {@code Spy} is on the bootstrap path before the first install. */
        UNCHECKED
    }

    /** The most code-source names a probe answer carries (phase8-contracts.md section 6.4). */
    public static final int MAX_CODE_SOURCES = 16;

    private static final String TYPE = "HookProbe";

    private final String id;
    private final CapabilityState state;
    private final String reason;
    private final String strategy;
    private final Integer loaders;
    private final List<String> loaderIds;
    private final List<String> codeSources;
    private final List<String> declared;
    private final Long sizeBytes;
    private final Integer methods;
    private final SpyVisibility spyVisibility;

    /**
     * @param id the hook id, not null
     * @param state not null
     * @param reason why the state is not OK, may be null
     * @param strategy {@code advice} when OK, may be null
     * @param loaders how many loaders define the class, may be null
     * @param loaderIds the identities of those loaders, may be null
     * @param declared the descriptors the class declares under the method's name, may be null
     * @param sizeBytes the class file's size, may be null
     * @param methods the class's declared methods and constructors, may be null
     * @param spyVisibility null means {@link SpyVisibility#UNCHECKED}
     */
    public HookProbe(String id, CapabilityState state, String reason, String strategy, Integer loaders,
                     List<String> loaderIds, List<String> declared, Long sizeBytes, Integer methods,
                     SpyVisibility spyVisibility) {
        this(id, state, reason, strategy, loaders, loaderIds, null, declared, sizeBytes, methods, spyVisibility);
    }

    /**
     * @param id the hook id, not null
     * @param state not null
     * @param reason why the state is not OK, may be null
     * @param strategy {@code advice} when OK, may be null
     * @param loaders how many loaders define the class, may be null
     * @param loaderIds the identities of those loaders, may be null
     * @param codeSources the code-source name of each copy, aligned with {@code loaderIds}, may be null; the first
     *     {@link #MAX_CODE_SOURCES} are kept
     * @param declared the descriptors the class declares under the method's name, may be null
     * @param sizeBytes the class file's size, may be null
     * @param methods the class's declared methods and constructors, may be null
     * @param spyVisibility null means {@link SpyVisibility#UNCHECKED}
     */
    public HookProbe(String id, CapabilityState state, String reason, String strategy, Integer loaders,
                     List<String> loaderIds, List<String> codeSources, List<String> declared, Long sizeBytes,
                     Integer methods, SpyVisibility spyVisibility) {
        this.id = Objects.requireNonNull(id, "id");
        this.state = Objects.requireNonNull(state, "state");
        this.reason = reason;
        this.strategy = strategy;
        this.loaders = loaders;
        this.loaderIds = loaderIds == null ? null : Collections.unmodifiableList(new ArrayList<String>(loaderIds));
        this.codeSources = codeSources == null ? null : Collections.unmodifiableList(new ArrayList<String>(
                codeSources.size() > MAX_CODE_SOURCES ? codeSources.subList(0, MAX_CODE_SOURCES) : codeSources));
        this.declared = declared == null ? null : Collections.unmodifiableList(new ArrayList<String>(declared));
        this.sizeBytes = sizeBytes;
        this.methods = methods;
        this.spyVisibility = spyVisibility == null ? SpyVisibility.UNCHECKED : spyVisibility;
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
    public Integer loaders() {
        return loaders;
    }

    /** Unmodifiable, or null. */
    public List<String> loaderIds() {
        return loaderIds;
    }

    /** The code-source name of each copy found, aligned with {@link #loaderIds()}; unmodifiable, or null. */
    public List<String> codeSources() {
        return codeSources;
    }

    /** Unmodifiable, or null. */
    public List<String> declared() {
        return declared;
    }

    /** May be null. */
    public Long sizeBytes() {
        return sizeBytes;
    }

    /** May be null. */
    public Integer methods() {
        return methods;
    }

    /** Never null. */
    public SpyVisibility spyVisibility() {
        return spyVisibility;
    }

    /** The optional keys only when set; a new map on each call. */
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
        if (loaders != null) {
            json.put("loaders", Long.valueOf(loaders.longValue()));
        }
        if (loaderIds != null) {
            json.put("loaderIds", new ArrayList<Object>(loaderIds));
        }
        if (codeSources != null) {
            json.put("codeSources", new ArrayList<Object>(codeSources));
        }
        if (declared != null) {
            json.put("declared", new ArrayList<Object>(declared));
        }
        if (sizeBytes != null) {
            json.put("sizeBytes", sizeBytes);
        }
        if (methods != null) {
            json.put("methods", Long.valueOf(methods.longValue()));
        }
        json.put("spyVisibility", spyVisibility.name());
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}; {@code id} and {@code state} are required.
     *
     * @throws JsonException on a missing required field, an unknown state or visibility, or a field of the wrong type
     */
    public static HookProbe fromJson(Map<String, Object> json) {
        HookFields.requireObject(json, TYPE);
        Long loaders = HookFields.optLong(json, "loaders", TYPE);
        Long methods = HookFields.optLong(json, "methods", TYPE);
        SpyVisibility visibility = json.get("spyVisibility") == null ? SpyVisibility.UNCHECKED
                : HookFields.requireEnum(json, "spyVisibility", SpyVisibility.class, TYPE);
        return new HookProbe(HookFields.requireString(json, "id", TYPE),
                HookFields.requireEnum(json, "state", CapabilityState.class, TYPE),
                HookFields.optString(json, "reason", TYPE), HookFields.optString(json, "strategy", TYPE),
                loaders == null ? null : Integer.valueOf(loaders.intValue()),
                HookFields.optStrings(json, "loaderIds", TYPE), HookFields.optStrings(json, "codeSources", TYPE),
                HookFields.optStrings(json, "declared", TYPE),
                HookFields.optLong(json, "sizeBytes", TYPE),
                methods == null ? null : Integer.valueOf(methods.intValue()), visibility);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookProbe)) {
            return false;
        }
        HookProbe other = (HookProbe) o;
        return id.equals(other.id) && state == other.state && Objects.equals(reason, other.reason)
                && Objects.equals(strategy, other.strategy) && Objects.equals(loaders, other.loaders)
                && Objects.equals(loaderIds, other.loaderIds) && Objects.equals(codeSources, other.codeSources)
                && Objects.equals(declared, other.declared)
                && Objects.equals(sizeBytes, other.sizeBytes) && Objects.equals(methods, other.methods)
                && spyVisibility == other.spyVisibility;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, state, reason, strategy, loaders, loaderIds, codeSources, declared, sizeBytes,
                methods, spyVisibility);
    }

    @Override
    public String toString() {
        return TYPE + Json.write(toJson());
    }
}
