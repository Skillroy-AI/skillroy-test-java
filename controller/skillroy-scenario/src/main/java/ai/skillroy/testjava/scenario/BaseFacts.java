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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.scenario.pack.FactProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The fact roots every pack has (contract section 8.3), consulted after the pack's own roots:
 *
 * <ul>
 *   <li>{@code app}: the {@code app.state} value, {@code {providerId: {…}}}.</li>
 *   <li>{@code ui}: the {@code swing.snapshot} value, {@code {seq, truncated, windows:[…]}}.</li>
 *   <li>{@code dialogs}: the list inside {@code swing.dialogs} ({@code dialogs[0].text}).</li>
 *   <li>{@code caps}: {@code agent.capabilities} as a list of {@code {id, state, reason, strategy}} sorted by id, so
 *       that ids containing dots can be selected with a filter ({@code caps[id=swing.tree].state}).</li>
 *   <li>{@code devices} (phase3-contracts.md 3.4, 5.4, decision D43): every {@code DeviceAdapter} of the target as
 *       a list of {@code {id, kind, actions, state}} sorted by id, from {@code device.list} and one
 *       {@code device.state {id}} per device, in id order ({@code devices[id=printer].state.last.text}). A
 *       {@code device.state} that fails fails the fact read, as any fact read does. Read-only: reading the root
 *       costs one call per device plus one, and a scenario that does not name it pays nothing.</li>
 * </ul>
 *
 * <p>A pack's own root of the same name takes precedence over a base root. Stateless; safe from any thread.
 */
public final class BaseFacts {

    /** The base root names. */
    public static final List<String> ROOTS = List.of("app", "ui", "dialogs", "caps", "devices");

    private static final CallOptions READ = CallOptions.defaults().withTimeoutMs(10_000L);

    private BaseFacts() {
    }

    /** The five base providers, in {@link #ROOTS} order. */
    public static List<FactProvider> all() {
        return List.of(
                provider("app", agent -> agent.call("app.state", Map.of(), READ).value()),
                provider("ui", agent -> agent.call("swing.snapshot", Map.of(), READ).value()),
                provider("dialogs", agent -> {
                    Object value = agent.call("swing.dialogs", Map.of(), READ).value();
                    return value instanceof Map<?, ?> map ? map.get("dialogs") : value;
                }),
                provider("caps", agent -> {
                    Object value = agent.call("agent.capabilities", Map.of(), READ).value();
                    if (!(value instanceof Map<?, ?> map) || !(map.get("capabilities") instanceof Map<?, ?> caps)) {
                        return value;
                    }
                    List<Object> list = new ArrayList<>();
                    new TreeMap<>(caps).forEach((id, capability) -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("id", String.valueOf(id));
                        if (capability instanceof Map<?, ?> fields) {
                            fields.forEach((k, v) -> entry.put(String.valueOf(k), v));
                        }
                        list.add(entry);
                    });
                    return list;
                }),
                provider("devices", BaseFacts::devices));
    }

    /**
     * {@code device.list}, then {@code device.state {id}} for every listed device in id order: a list of
     * {@code {id, kind, actions, state}} sorted by id. An entry without an id is left out.
     *
     * @throws Exception a JSON-RPC error of either primitive (the fact read fails), or a lost agent
     */
    private static Object devices(AgentConnection agent) throws Exception {
        Object value = agent.call("device.list", Map.of(), READ).value();
        Object listed = value instanceof Map<?, ?> map ? map.get("devices") : null;
        TreeMap<String, Map<?, ?>> byId = new TreeMap<>();
        if (listed instanceof List<?> devices) {
            for (Object device : devices) {
                if (device instanceof Map<?, ?> fields && fields.get("id") != null) {
                    byId.putIfAbsent(String.valueOf(fields.get("id")), fields);
                }
            }
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Map<?, ?>> e : byId.entrySet()) {
            Object state = agent.call("device.state", Map.of("id", e.getKey()), READ).value();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getKey());
            entry.put("kind", e.getValue().get("kind"));
            Object actions = e.getValue().get("actions");
            entry.put("actions", actions instanceof List<?> list ? list : List.of());
            entry.put("state", state);
            out.add(entry);
        }
        return out;
    }

    private interface Fetch {
        Object fetch(AgentConnection agent) throws Exception;
    }

    private static FactProvider provider(String root, Fetch fetch) {
        return new FactProvider() {
            @Override
            public String root() {
                return root;
            }

            @Override
            public Object fetch(AgentConnection agent) throws Exception {
                return fetch.fetch(agent);
            }

            @Override
            public String toString() {
                return "BaseFact[" + root + "]";
            }
        };
    }
}
