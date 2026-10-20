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
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A scriptable {@link AgentConnection} for executor tests. It keeps an event bus with sequence numbers, records every
 * call and await, and answers each method from a programmed handler. Handlers may publish events (which then appear in
 * the call's result and on the bus) and throw {@link AgentRpcException} or {@link AgentLostException}.
 *
 * <p>Defaults: {@code trace.mark} publishes a {@code trace.mark} event; {@code evt.query} answers from the bus;
 * {@code jvm.properties} answers from {@link #property}; {@code swing.settle} settles at once; {@code sync.await}
 * answers from the bus or fails with TIMEOUT; everything else is METHOD_NOT_FOUND. {@link #await} answers from the
 * bus at once (null if nothing matches, as on a timeout).
 *
 * <p>Thread-safe through synchronisation; tests use it from one thread.
 */
final class FakeAgentConnection implements AgentConnection {

    /** One recorded call. */
    record Call(String method, Map<String, Object> params, CallOptions options) {
    }

    /** One recorded await. */
    record Await(String topics, long since, Map<String, Object> match, Duration timeout) {
    }

    /** Answers one call. */
    @FunctionalInterface
    interface Handler {
        Object handle(Map<String, Object> params) throws AgentRpcException, AgentLostException;
    }

    final List<Call> calls = new ArrayList<>();
    final List<Await> awaits = new ArrayList<>();
    final List<Event> bus = new ArrayList<>();
    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, Capability> capabilities = new TreeMap<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private long seq;
    private String loseOn;
    private boolean lost;
    private boolean closed;
    private RuntimeException awaitFailure;
    private final List<PrimitiveDescriptor> schema = new ArrayList<>();
    /** Methods the fake refuses with CONFIRM_REQUIRED unless the call is confirmed, as the dispatcher does. */
    private final java.util.Set<String> destructive = new java.util.HashSet<>();
    /** How often {@link #capabilities()} was asked. */
    int capabilityReads;

    FakeAgentConnection() {
        on("trace.mark", params -> Map.of("seq", publish("trace.mark", params)));
        on("evt.query", this::query);
        on("jvm.properties", params -> {
            Map<String, Object> props = new LinkedHashMap<>();
            for (Object name : (List<?>) params.get("names")) {
                props.put((String) name, properties.get((String) name));
            }
            return Map.of("properties", props);
        });
        on("swing.settle", params -> Map.of("settled", true, "ms", 1L));
        on("sync.await", params -> {
            Object topics = params.get("topics");
            String filter = topics instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf)
                    .toList()) : String.valueOf(topics);
            @SuppressWarnings("unchecked")
            Map<String, Object> match = (Map<String, Object>) params.get("match");
            Event event = firstMatch(filter, ((Number) params.get("since")).longValue(), match);
            if (event == null) {
                throw new AgentRpcException("sync.await", new RpcError(ErrorCodes.TIMEOUT, "no event",
                        Map.of("lastSeq", seq)));
            }
            return event.toJson();
        });
    }

    synchronized FakeAgentConnection on(String method, Handler handler) {
        handlers.put(method, handler);
        return this;
    }

    /** The handler currently programmed for {@code method}, for wrapping; null if none. */
    synchronized Handler handler(String method) {
        return handlers.get(method);
    }

    FakeAgentConnection value(String method, Object value) {
        return on(method, params -> value);
    }

    FakeAgentConnection error(String method, int code, String message) {
        return on(method, params -> {
            throw new AgentRpcException(method, new RpcError(code, message, Map.of("why", "test")));
        });
    }

    synchronized FakeAgentConnection capability(String id, CapabilityState state, String reason) {
        capabilities.put(id, new Capability(id, state, reason, null));
        return this;
    }

    FakeAgentConnection capabilities(String... okIds) {
        for (String id : okIds) {
            capability(id, CapabilityState.OK, null);
        }
        return this;
    }

    /** A 1 x 1 PNG, as {@code web.bitmap} and {@code swing.capture} answer with base64. */
    static final String PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9aw"
            + "AAAABJRU5ErkJggg==";

    /**
     * A {@code web.browsers} entry as the agent's web module lists it (phase4-contracts.md 4.2): adapter
     * {@code jcef}, rendering {@code WINDOWED}, {@code EVALUATE}, {@code FRAMES} and {@code BITMAP}, the target id
     * {@code T-<tag>}, one main frame.
     */
    static Map<String, Object> browser(String ref, String tag, boolean visible) {
        Map<String, Object> browser = new LinkedHashMap<>();
        browser.put("ref", ref);
        browser.put("adapter", "jcef");
        browser.put("nativeId", ref.substring(1));
        browser.put("url", "data:text/html," + tag);
        browser.put("title", tag + " page");
        browser.put("closed", false);
        browser.put("visibleInSwing", visible);
        if (visible) {
            browser.put("component", "h2");
        }
        browser.put("rendering", "WINDOWED");
        browser.put("viewport", Map.of("w", 800L, "h", 600L));
        browser.put("capabilities", List.of("EVALUATE", "FRAMES", "BITMAP"));
        browser.put("cdpTargetId", "T-" + tag);
        browser.put("tag", tag);
        browser.put("frames", List.of(Map.of("key", "F-" + tag, "name", "", "url", "data:text/html," + tag,
                "main", true)));
        return browser;
    }

    /**
     * The agent's web module (phase4-contracts.md 4): two browsers, {@code h7} (tag {@code front}, visible in Swing)
     * and {@code h9} (tag {@code back}, hidden); the web capabilities OK except {@code web.cdp}, which a test adds
     * when it wants an endpoint; every {@code web.*} primitive answering as the module does for a page that
     * cooperates. Tests replace single handlers.
     */
    FakeAgentConnection withWeb() {
        capabilities("web.browsers", "web.evaluate", "web.events", "web.initScript", "web.resize", "web.bitmap");
        value("web.browsers", Map.of("browsers", List.of(browser("h7", "front", true), browser("h9", "back",
                false))));
        on("web.dom.click", params -> Map.of("matched", 1L, "clicked", Map.of("role", "button", "name", "Borrow",
                "tag", "BUTTON"), "fidelity", "DOM"));
        on("web.dom.fill", params -> Map.of("matched", 1L, "filled", Map.of("tag", "INPUT", "type", "text"),
                "typed", (long) String.valueOf(params.get("text")).length()));
        on("web.dispatchEvent", params -> Map.of("dispatched", true));
        on("web.tap", params -> Map.of("installed", (long) ((List<?>) params.get("patterns")).size(), "timing",
                "DOCUMENT_START"));
        on("web.waitFlag", params -> Map.of("ready", true, "ms", 5L, "polls", 1L));
        on("web.resize", params -> Map.of("applied", "OVERRIDE", "viewport", Map.of("w", 1024L, "h", 768L)));
        on("web.dom.text", params -> Map.of("text", "page of " + params.get("browser"), "truncated", false));
        on("web.ariaSnapshot", params -> Map.of("nodes", List.of(Map.of("role", "heading", "name", "Borrow",
                "level", 1L), Map.of("role", "textbox", "name", "Item", "value", "hammer"), Map.of("role", "button",
                "name", "Borrow")), "truncated", false));
        on("web.bitmap", params -> Map.of("format", "png", "width", 1L, "height", 1L, "base64", PNG_1X1, "source",
                "PROTOCOL"));
        on("web.evaluate", params -> Map.of("json", "1", "typeofResult", "number"));
        return this;
    }

    synchronized FakeAgentConnection property(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    /** Every later call, await or capabilities() throws AgentLostException once {@code method} is called. */
    synchronized FakeAgentConnection loseOn(String method) {
        this.loseOn = method;
        return this;
    }

    /**
     * Every later {@link #await} throws {@code failure}, as {@code AgentClient.await} throws IllegalStateException
     * for a JSON-RPC error other than TIMEOUT.
     */
    synchronized FakeAgentConnection failAwait(RuntimeException failure) {
        this.awaitFailure = failure;
        return this;
    }

    /** Publishes an event on the bus and returns its sequence number. */
    synchronized long publish(String topic, Map<String, ?> payload) {
        seq++;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach(copy::put);
        }
        bus.add(new Event(seq, 1_000_000L + seq, topic, "fake", null, null, copy));
        return seq;
    }

    synchronized long lastSeq() {
        return seq;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    /** The methods called, in order. */
    synchronized List<String> methods() {
        return calls.stream().map(Call::method).toList();
    }

    /** The methods called, without the executor's own bookkeeping (trace.mark, evt.query). */
    synchronized List<String> stepMethods() {
        return calls.stream().map(Call::method).filter(m -> !m.equals("evt.query") && !m.equals("trace.mark"))
                .toList();
    }

    /** The recorded calls of one method. */
    synchronized List<Call> callsTo(String method) {
        return calls.stream().filter(c -> c.method().equals(method)).toList();
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException, AgentLostException {
        return call(method, params, CallOptions.defaults());
    }

    @Override
    public synchronized RpcResult call(String method, Map<String, Object> params, CallOptions options)
            throws AgentRpcException, AgentLostException {
        Map<String, Object> copy = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        calls.add(new Call(method, copy, options));
        if (method.equals(loseOn)) {
            lost = true;
        }
        if (lost) {
            throw new AgentLostException("connection refused");
        }
        Handler handler = handlers.get(method);
        if (handler == null) {
            throw new AgentRpcException(method, new RpcError(ErrorCodes.METHOD_NOT_FOUND, "no " + method, null));
        }
        if (destructive.contains(method) && !confirmed(copy, options)) {
            throw new AgentRpcException(method, new RpcError(ErrorCodes.CONFIRM_REQUIRED, method
                    + " is destructive and needs _meta.confirm=true", null));
        }
        long before = seq;
        Object value = handler.handle(copy);
        List<Event> events = new ArrayList<>();
        for (Event event : bus) {
            if (event.seq() > before) {
                events.add(event);
            }
        }
        return new RpcResult(value, events, before, seq, 1, "fake-worker");
    }

    /**
     * Whether a call reaches the agent confirmed: by its options, or by a {@code confirm: true} in a {@code _meta} map
     * among its params, as a connection that merges the params' {@code _meta} into the request's would deliver it
     * (the HTTP client did until the Phase 5 fix wave). So a test sees any {@code _meta} that reaches a connection.
     */
    private static boolean confirmed(Map<String, Object> params, CallOptions options) {
        if (options != null && options.confirm()) {
            return true;
        }
        return params.get("_meta") instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("confirm"));
    }

    @Override
    public synchronized Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
            throws AgentLostException {
        awaits.add(new Await(topics, sinceSeq, match, timeout));
        if (lost) {
            throw new AgentLostException("connection refused");
        }
        if (awaitFailure != null) {
            throw awaitFailure;
        }
        return firstMatch(topics, sinceSeq, match);
    }

    private synchronized Event firstMatch(String topics, long sinceSeq, Map<String, Object> match) {
        TopicFilter filter = TopicFilter.parse(topics);
        for (Event event : bus) {
            if (event.seq() > sinceSeq && filter.matches(event.topic()) && matches(event, match)) {
                return event;
            }
        }
        return null;
    }

    private static boolean matches(Event event, Map<String, Object> match) {
        if (match == null) {
            return true;
        }
        for (Map.Entry<String, Object> e : match.entrySet()) {
            if (!String.valueOf(e.getValue()).equals(String.valueOf(event.payload().get(e.getKey())))) {
                return false;
            }
        }
        return true;
    }

    private Object query(Map<String, Object> params) {
        long since = params.get("since") instanceof Number n ? n.longValue() : 0;
        int limit = params.get("limit") instanceof Number n ? n.intValue() : 200;
        TopicFilter filter = TopicFilter.parse((String) params.get("topics"));
        List<Object> out = new ArrayList<>();
        for (Event event : bus) {
            if (event.seq() > since && filter.matches(event.topic()) && out.size() < limit) {
                out.add(event.toJson());
            }
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("events", out);
        value.put("lastSeq", seq);
        value.put("oldestSeq", bus.isEmpty() ? 0L : bus.get(0).seq());
        return value;
    }

    @Override
    public synchronized CapabilityMatrix capabilities() throws AgentLostException {
        capabilityReads++;
        if (lost) {
            throw new AgentLostException("connection refused");
        }
        return new CapabilityMatrix(capabilities);
    }

    @Override
    public synchronized List<PrimitiveDescriptor> schema() {
        return List.copyOf(schema);
    }

    /** Adds a primitive descriptor to what {@link #schema()} answers. */
    synchronized FakeAgentConnection describe(String name, boolean mutating) {
        schema.add(new PrimitiveDescriptor(name, null, null, null, null, 5000, null, mutating, false));
        return this;
    }

    /** Adds a primitive descriptor naming a capability. */
    synchronized FakeAgentConnection describe(String name, boolean mutating, String capabilityId) {
        schema.add(new PrimitiveDescriptor(name, null, null, null, null, 5000, capabilityId, mutating, false));
        return this;
    }

    /** Adds a descriptor of a {@code script} primitive (phase4-contracts.md 4.2), neither mutating nor destructive. */
    synchronized FakeAgentConnection describeScript(String name) {
        schema.add(new PrimitiveDescriptor(name, null, null, null, null, 5000, null, false, false, true));
        return this;
    }

    /**
     * Makes {@code method} destructive: described as such, and refused with CONFIRM_REQUIRED unless the call is
     * confirmed ({@code _meta.confirm}, from the options or from a {@code _meta} among the params), as the agent's
     * dispatcher refuses it.
     */
    synchronized FakeAgentConnection destructive(String method) {
        destructive.add(method);
        schema.add(new PrimitiveDescriptor(method, null, null, null, null, 5000, null, true, true));
        return this;
    }

    @Override
    public Map<String, Object> ping() {
        return Map.of("protocol", Protocol.VERSION);
    }

    /** The agent session {@link #agentSession()} answers (phase8-contracts.md 9.3.2); null (the default) = none. */
    private volatile String agentSession;

    /** Sets the agent session this connection answers; null = none, nothing is cached for it. */
    FakeAgentConnection session(String session) {
        this.agentSession = session;
        return this;
    }

    @Override
    public String agentSession() {
        return agentSession;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
