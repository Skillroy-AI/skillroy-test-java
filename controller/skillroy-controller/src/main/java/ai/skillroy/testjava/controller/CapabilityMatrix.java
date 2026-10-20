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
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

    /** {@link #source(String)} of an entry the agent declared. */
    public static final String SOURCE_AGENT = "agent";

    /** The prefix of {@link #source(String)} for an entry a controller-side contributor added. */
    public static final String SOURCE_CONTROLLER_PREFIX = "controller:";

    /**
     * The prefix of {@link #source(String)} for an entry the running domain pack contributed
     * ({@code pack:<packId>}; phase8-contracts.md 9.1.4, decision D613).
     */
    public static final String SOURCE_PACK_PREFIX = "pack:";

    private final Map<String, Capability> byId;
    /**
     * The source of each contributed entry (phase7-contracts.md 8.5; phase8-contracts.md 9.1.4); an id not in it is
     * the agent's.
     */
    private final Map<String, String> sources;

    /**
     * @param capabilities capabilities keyed by id; null means none. The map key is authoritative and must equal
     *     the capability's own id.
     * @throws IllegalArgumentException if a key differs from its capability's id
     */
    public CapabilityMatrix(Map<String, Capability> capabilities) {
        this(sorted(capabilities), Map.of());
    }

    private CapabilityMatrix(TreeMap<String, Capability> sorted, Map<String, String> sources) {
        this.byId = Collections.unmodifiableMap(sorted);
        this.sources = Collections.unmodifiableMap(new TreeMap<>(sources));
    }

    private static TreeMap<String, Capability> sorted(Map<String, Capability> capabilities) {
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
        return sorted;
    }

    /**
     * This matrix with the capabilities a controller-side module contributes (phase7-contracts.md 8.5, decision
     * D326) or the running pack contributes (phase8-contracts.md 9.1.4, decision D613), each remembered with
     * {@code source} for {@link #source(String)}; the wire shape ({@link #toJson()}, {@link #hash()}) carries no
     * source. The caller decides conflicts first: an id this matrix already holds is refused here.
     *
     * @param contributed capabilities keyed by id; null means none
     * @param source the source to remember, {@code controller:<contributor id>} ({@link #SOURCE_CONTROLLER_PREFIX})
     *     or {@code pack:<packId>} ({@link #SOURCE_PACK_PREFIX})
     * @throws IllegalArgumentException if a key differs from its capability's id, an id is already in this matrix, or
     *     the source is blank
     */
    public CapabilityMatrix with(Map<String, Capability> contributed, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("a contributed capability needs a source");
        }
        TreeMap<String, Capability> added = sorted(contributed);
        TreeMap<String, Capability> merged = new TreeMap<>(byId);
        Map<String, String> mergedSources = new TreeMap<>(sources);
        for (Map.Entry<String, Capability> e : added.entrySet()) {
            if (merged.containsKey(e.getKey())) {
                throw new IllegalArgumentException("capability '" + e.getKey() + "' is already in the matrix (source "
                        + source(e.getKey()) + "); " + source + " cannot add it");
            }
            merged.put(e.getKey(), e.getValue());
            mergedSources.put(e.getKey(), source);
        }
        return new CapabilityMatrix(merged, mergedSources);
    }

    /**
     * Where an entry came from (phase7-contracts.md 8.5; phase8-contracts.md 9.1.4): {@link #SOURCE_AGENT}, or the
     * source it was added with by {@link #with} ({@code controller:<contributor id>} or {@code pack:<packId>}); null
     * for an id the matrix does not hold.
     */
    public String source(String id) {
        if (!byId.containsKey(id)) {
            return null;
        }
        return sources.getOrDefault(id, SOURCE_AGENT);
    }

    /**
     * The matrix as the agent declared it: this one without the entries {@link #with} added, the contributors' and the
     * pack's (phase7-contracts.md 8.5; phase8-contracts.md 9.1.4); itself when nothing was contributed.
     */
    public CapabilityMatrix agentOnly() {
        if (sources.isEmpty()) {
            return this;
        }
        TreeMap<String, Capability> agent = new TreeMap<>(byId);
        agent.keySet().removeAll(sources.keySet());
        return new CapabilityMatrix(agent, Map.of());
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

    /**
     * The wire shape {@code GET /capabilities} serves (phase6-contracts.md 6.4, decision D130):
     * {@code {"capabilities": {id: {"state", "reason", "strategy"}}}}, ids sorted, {@code reason} and {@code strategy}
     * written as null when absent. What a run archives as {@code capabilities.json}; {@link #fromJson} reads it back.
     *
     * @return a new mutable map
     */
    public Map<String, Object> toJson() {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Capability> e : byId.entrySet()) {
            entries.put(e.getKey(), e.getValue().toJson());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("capabilities", entries);
        return json;
    }

    /**
     * {@code "sha256:"} and the lower-case hex SHA-256 of the canonical JSON of {@link #toJson()}: two matrices with
     * the same ids, states, reasons and strategies have the same hash (phase6-contracts.md 6.4).
     */
    public String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Json.writeCanonical(toJson())
                    .getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
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
