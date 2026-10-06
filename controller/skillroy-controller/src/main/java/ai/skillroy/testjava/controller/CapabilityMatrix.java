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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.JsonException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The controller's view of what an agent can do in this target, used to refuse a scenario up front
 * (PRECONDITION_FAILED) when something it requires is missing or UNAVAILABLE, instead of failing it half-way.
 * DEGRADED capabilities count as available.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class CapabilityMatrix {

    private final Map<String, Capability> byId;

    /**
     * @param capabilities capabilities keyed by id; null means none. The map key is authoritative and must equal
     *     the capability's own id.
     * @throws IllegalArgumentException if a key differs from its capability's id
     */
    public CapabilityMatrix(Map<String, Capability> capabilities) {
        TreeMap<String, Capability> sorted = new TreeMap<>();
        if (capabilities != null) {
            for (Map.Entry<String, Capability> e : capabilities.entrySet()) {
                Capability capability = Objects.requireNonNull(e.getValue(), "capability " + e.getKey());
                if (!capability.id().equals(e.getKey())) {
                    throw new IllegalArgumentException("key '" + e.getKey() + "' holds capability '"
                            + capability.id() + "'");
                }
                sorted.put(e.getKey(), capability);
            }
        }
        this.byId = Collections.unmodifiableMap(sorted);
    }

    /**
     * Reads the shape served by {@code GET /capabilities} and {@code agent.capabilities}:
     * {@code {"capabilities":{id:{"state","reason","strategy"}}}}.
     *
     * @throws JsonException if the shape is wrong
     */
    public static CapabilityMatrix fromJson(Map<String, Object> json) {
        Objects.requireNonNull(json, "json");
        Object inner = json.get("capabilities");
        if (!(inner instanceof Map<?, ?> entries)) {
            throw new JsonException("CapabilityMatrix: missing object \"capabilities\"");
        }
        TreeMap<String, Capability> parsed = new TreeMap<>();
        for (Map.Entry<?, ?> e : entries.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> value)) {
                throw new JsonException("CapabilityMatrix: capability \"" + id + "\" is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> capabilityJson = (Map<String, Object>) value;
            parsed.put(id, Capability.fromJson(id, capabilityJson));
        }
        return new CapabilityMatrix(parsed);
    }

    /** The capability, or null if the agent did not declare it. */
    public Capability get(String id) {
        return byId.get(id);
    }

    /** True only for state OK (DEGRADED is false here, though it satisfies {@link #unmet}). */
    public boolean isOk(String id) {
        Capability capability = byId.get(id);
        return capability != null && capability.state() == CapabilityState.OK;
    }

    /** All capabilities sorted by id; unmodifiable. */
    public Map<String, Capability> all() {
        return byId;
    }

    /**
     * Ids from `required` that are missing or UNAVAILABLE, with reasons. "a | b" means either suffices.
     *
     * <p>One string per unmet entry, in the order of {@code required} (duplicates and blank entries skipped):
     * {@code "<id>: missing"}, {@code "<id>: UNAVAILABLE (<reason>)"} (or {@code "<id>: UNAVAILABLE"} without a
     * reason), and for alternatives {@code "a | b: a: missing; b: UNAVAILABLE (<reason>)"}. An empty list means
     * every requirement is met.
     */
    public List<String> unmet(Collection<String> required) {
        List<String> unmet = new ArrayList<>();
        if (required == null) {
            return unmet;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : required) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            List<String> alternatives = new ArrayList<>();
            for (String part : entry.split("\\|")) {
                String id = part.strip();
                if (!id.isEmpty() && !alternatives.contains(id)) {
                    alternatives.add(id);
                }
            }
            if (alternatives.isEmpty()) {
                continue;
            }
            String label = String.join(" | ", alternatives);
            if (!seen.add(label)) {
                continue;
            }
            List<String> problems = new ArrayList<>();
            for (String id : alternatives) {
                String problem = problem(id);
                if (problem == null) {
                    problems = null;
                    break;
                }
                problems.add(alternatives.size() == 1 ? problem : id + ": " + problem);
            }
            if (problems != null) {
                unmet.add(label + ": " + String.join("; ", problems));
            }
        }
        return unmet;
    }

    /** Why {@code id} is not usable, or null if it is (OK or DEGRADED). */
    private String problem(String id) {
        Capability capability = byId.get(id);
        if (capability == null) {
            return "missing";
        }
        if (capability.state() != CapabilityState.UNAVAILABLE) {
            return null;
        }
        String reason = capability.reason();
        return reason == null || reason.isBlank() ? "UNAVAILABLE" : "UNAVAILABLE (" + reason + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CapabilityMatrix other && byId.equals(other.byId);
    }

    @Override
    public int hashCode() {
        return byId.hashCode();
    }

    @Override
    public String toString() {
        return "CapabilityMatrix" + byId.values();
    }
}
