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
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.scenario.pack.FactProvider;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

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
 *   <li>{@code web} (phase4-contracts.md 6.2): {@code {browsers: [{ref, adapter, url, title?, visibleInSwing,
 *       component?, rendering, viewport?, cdpTargetId?, tag?, frames, text?}]}} from {@code web.browsers} (the
 *       entry's other members are left out) and, for each browser whose {@code capabilities} list {@code EVALUATE},
 *       one {@code web.dom.text {browser, maxChars: 4000}} into {@code text}
 *       ({@code web.browsers[url~pattern].text: {contains: …}}). The whole read is bounded by 10 s; a text read that
 *       fails, or that the budget no longer allows, leaves {@code text} out (and is reported, so that the executor
 *       logs it) while the root still resolves; a failing {@code web.browsers} fails the read. In a run the
 *       executor reads through the run's chosen web path with the usual fall-back ({@link PlanExecutor}); this
 *       class's own provider reads from the agent.</li>
 *   <li>{@code hooks} (phase6-contracts.md 6.3, decision D131): a list as {@code caps} is, because a hook id holds
 *       dots, of {@code {id, state, count, lastFiredMs, armed, last}} sorted by id
 *       ({@code hooks[id=limit.applied].last.returned}): the ids and states from {@code hook.health {}}, and per id
 *       {@code hook.events {since, id, limit: 1000}}, where {@code since} is the agent's sequence the executor
 *       recorded when the first step began, so that {@code count} (the number of those events), {@code last} (the
 *       newest one's payload) and {@code lastFiredMs} (its time) describe this run's steps only: a bind-time
 *       verifier's call or an earlier run's never count. When {@code hook.health} answers {@code METHOD_NOT_FOUND}
 *       (no hooks module), {@code CAPABILITY_UNAVAILABLE} (no {@code Instrumentation}) or {@code NOT_READY}, the root
 *       is an empty list. This class's own provider counts from sequence 0.</li>
 * </ul>
 *
 * <p>A pack's own root of the same name takes precedence over a base root. Stateless; safe from any thread.
 */
public final class BaseFacts {

    /** The base root names. */
    public static final List<String> ROOTS = List.of("app", "ui", "dialogs", "caps", "devices", "web", "hooks");

    /** The most events of one hook the root {@code hooks} reads per attempt ({@code hook.events}' cap). */
    static final long HOOK_EVENTS_LIMIT = 1000L;

    private static final CallOptions READ = CallOptions.defaults().withTimeoutMs(10_000L);

    /** The budget of one read of the root {@code web}. */
    static final long WEB_BUDGET_MS = 10_000L;

    /** The characters of page text the root {@code web} reads per browser. */
    static final long WEB_TEXT_CHARS = 4000L;

    /** The members of a {@code web.browsers} entry the root {@code web} keeps, in this order. */
    static final List<String> WEB_KEYS = List.of("ref", "adapter", "url", "title", "visibleInSwing", "component",
            "rendering", "viewport", "cdpTargetId", "tag", "frames");

    /** Reads one {@code web.*} primitive's value for the root {@code web}. */
    @FunctionalInterface
    interface WebReads {
        /**
         * @param timeoutMs the call's budget, at least 1
         * @throws Exception the read's failure: an {@code AgentRpcException} with the agent's code, a lost agent, or
         *     any failure of another path
         */
        Object read(AgentConnection agent, String primitive, Map<String, Object> params, long timeoutMs)
                throws Exception;
    }

    /** Told that the root left a browser's {@code text} out, and why. */
    @FunctionalInterface
    interface TextOmitted {
        void omitted(String ref, String reason);
    }

    /** The root's reads straight from the agent. */
    static final WebReads AGENT_READS = (agent, primitive, params, timeoutMs) -> agent.call(primitive, params,
            CallOptions.defaults().withTimeoutMs(Math.max(1L, Math.min(timeoutMs, WEB_BUDGET_MS)))).value();

    private BaseFacts() {
    }

    /** The seven base providers, in {@link #ROOTS} order. */
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
                provider("devices", BaseFacts::devices),
                web(AGENT_READS, Clock.systemUTC(), (ref, reason) -> {
                }),
                hooks(() -> 0L));
    }

    /**
     * The root {@code hooks} counting the events after the agent sequence {@code since} supplies (read at every
     * fetch: the executor fixes it when the first step begins).
     */
    static FactProvider hooks(LongSupplier since) {
        return provider("hooks", agent -> hooks(agent, since.getAsLong()));
    }

    /**
     * {@code [{id, state, count, lastFiredMs, armed, last}]} sorted by id (see the class comment).
     *
     * @throws Exception a failure of {@code hook.health} or {@code hook.events} other than the three that mean "no
     *     hooks here", or a lost agent
     */
    private static Object hooks(AgentConnection agent, long since) throws Exception {
        Object health;
        try {
            health = agent.call("hook.health", Map.of(), READ).value();
        } catch (AgentRpcException e) {
            if (noHooks(e)) {
                return List.of();
            }
            throw e;
        }
        if (!(health instanceof Map<?, ?> map) || !(map.get("hooks") instanceof Map<?, ?> byId)) {
            return List.of();
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : new TreeMap<String, Object>(stringKeys(byId)).entrySet()) {
            Map<?, ?> fields = e.getValue() instanceof Map<?, ?> f ? f : Map.of();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("since", since);
            params.put("id", e.getKey());
            params.put("limit", HOOK_EVENTS_LIMIT);
            List<?> events;
            try {
                Object value = agent.call("hook.events", params, READ).value();
                events = value instanceof Map<?, ?> m && m.get("events") instanceof List<?> list ? list : List.of();
            } catch (AgentRpcException failure) {
                if (noHooks(failure)) {
                    return List.of();
                }
                throw failure;
            }
            Map<?, ?> newest = events.isEmpty() || !(events.get(events.size() - 1) instanceof Map<?, ?> last)
                    ? null : last;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getKey());
            entry.put("state", fields.get("state"));
            entry.put("count", (long) events.size());
            entry.put("lastFiredMs", newest == null ? null : newest.get("ts"));
            entry.put("armed", Boolean.TRUE.equals(fields.get("armed")));
            entry.put("last", newest == null ? null : newest.get("payload"));
            out.add(entry);
        }
        return out;
    }

    /** {@code METHOD_NOT_FOUND}, {@code CAPABILITY_UNAVAILABLE} or {@code NOT_READY}: the target has no hooks now. */
    private static boolean noHooks(AgentRpcException e) {
        int code = e.error().code();
        return code == ErrorCodes.METHOD_NOT_FOUND || code == ErrorCodes.CAPABILITY_UNAVAILABLE
                || code == ErrorCodes.NOT_READY;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * The root {@code web} reading through {@code reads}, bounded by {@code clock}, telling {@code omitted} about
     * every {@code text} it leaves out.
     */
    static FactProvider web(WebReads reads, Clock clock, TextOmitted omitted) {
        return provider("web", agent -> web(agent, reads, clock, omitted));
    }

    /**
     * {@code {browsers: [...]}}: each {@code web.browsers} entry reduced to {@link #WEB_KEYS}, with {@code text} for
     * a browser that has {@code EVALUATE}, within one 10 s budget.
     *
     * @throws Exception the failure of {@code web.browsers}, or a lost agent
     */
    private static Object web(AgentConnection agent, WebReads reads, Clock clock, TextOmitted omitted)
            throws Exception {
        long deadline = clock.millis() + WEB_BUDGET_MS;
        Object value = reads.read(agent, "web.browsers", Map.of(), WEB_BUDGET_MS);
        Object listed = value instanceof Map<?, ?> map ? map.get("browsers") : null;
        List<Object> browsers = new ArrayList<>();
        if (listed instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> browser)) {
                    continue;
                }
                Map<String, Object> fact = new LinkedHashMap<>();
                for (String key : WEB_KEYS) {
                    if (browser.containsKey(key)) {
                        fact.put(key, browser.get(key));
                    }
                }
                Object ref = browser.get("ref");
                if (ref != null && browser.get("capabilities") instanceof List<?> capabilities
                        && capabilities.contains("EVALUATE")) {
                    text(agent, reads, clock, omitted, deadline, String.valueOf(ref), fact);
                }
                browsers.add(fact);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("browsers", browsers);
        return root;
    }

    /** One browser's {@code text}, or nothing when the read fails or the budget is spent. */
    private static void text(AgentConnection agent, WebReads reads, Clock clock, TextOmitted omitted, long deadline,
                             String ref, Map<String, Object> fact) throws AgentLostException, InterruptedException {
        long remaining = deadline - clock.millis();
        if (remaining <= 0) {
            omitted.omitted(ref, "the root's " + WEB_BUDGET_MS + " ms budget was spent");
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("browser", ref);
        params.put("maxChars", WEB_TEXT_CHARS);
        try {
            Object value = reads.read(agent, "web.dom.text", params, remaining);
            if (value instanceof Map<?, ?> map && map.get("text") != null) {
                fact.put("text", map.get("text"));
            } else {
                omitted.omitted(ref, "web.dom.text answered no text");
            }
        } catch (AgentLostException | InterruptedException e) {
            throw e;
        } catch (AgentRpcException e) {
            omitted.omitted(ref, "web.dom.text failed: " + ErrorCodes.name(e.error().code()) + ": "
                    + e.error().message());
        } catch (Exception e) {
            omitted.omitted(ref, "web.dom.text failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
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
