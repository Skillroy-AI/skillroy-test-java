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

package ai.skillroy.testjava.mcp;

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
 * A scriptable {@link AgentConnection} for the server's tests, modelled on the executor tests' fake of
 * {@code skillroy-scenario} (which is not published as a test jar): an event bus with sequence numbers, every call
 * recorded, each method answered by a programmed handler that may publish events and throw
 * {@link AgentRpcException} or {@link AgentLostException}; a catalogue of descriptors and a capability matrix; the
 * lost latch of the real client ({@link #isLost()}), set by {@link #loseOn}.
 *
 * <p>A destructive primitive of the catalogue called without {@code confirm} in its options answers
 * {@code CONFIRM_REQUIRED}, as the agent's dispatcher does (the call is recorded first).
 *
 * <p>Defaults: {@code trace.mark} publishes a {@code trace.mark} event; {@code evt.query} answers from the bus with
 * {@code lastSeq}; {@code swing.settle} settles at once; {@code sync.await} answers from the bus or fails with
 * TIMEOUT; everything else is METHOD_NOT_FOUND. {@link #await} answers from the bus at once (null when nothing
 * matches, as on a timeout).
 *
 * <p>Thread-safe: the server calls it from its tool threads.
 */
final class FakeAgent implements AgentConnection {

    /** One recorded call. */
    record Call(String method, Map<String, Object> params, CallOptions options) {
    }

    /** Answers one call. */
    @FunctionalInterface
    interface Handler {
        Object handle(Map<String, Object> params) throws AgentRpcException, AgentLostException;
    }

    /** A 1 x 1 PNG, as {@code swing.capture} answers with base64. */
    static final String PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9aw"
            + "AAAABJRU5ErkJggg==";

    private final List<Call> calls = new ArrayList<>();
    private final List<Event> bus = new ArrayList<>();
    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, Capability> capabilities = new TreeMap<>();
    private final List<PrimitiveDescriptor> schema = new ArrayList<>();
    private long seq;
    private String loseOn;
    private boolean lost;
    private boolean closed;

    FakeAgent() {
        on("trace.mark", params -> Map.of("seq", publish("trace.mark", params)));
        on("evt.query", this::query);
        on("swing.settle", params -> Map.of("settled", true, "ms", 1L));
        on("sync.await", params -> {
            Object topics = params.get("topics");
            String filter = topics instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf)
                    .toList()) : String.valueOf(topics);
            Event event = firstMatch(filter, ((Number) params.getOrDefault("since", 0L)).longValue(), null);
            if (event == null) {
                throw new AgentRpcException("sync.await", new RpcError(ErrorCodes.TIMEOUT, "no event",
                        Map.of("lastSeq", lastSeq())));
            }
            return event.toJson();
        });
    }

    synchronized FakeAgent on(String method, Handler handler) {
        handlers.put(method, handler);
        return this;
    }

    FakeAgent value(String method, Object value) {
        return on(method, params -> value);
    }

    synchronized FakeAgent capability(String id, CapabilityState state) {
        capabilities.put(id, new Capability(id, state, null, null));
        return this;
    }

    /** Describes a primitive in the catalogue. */
    synchronized FakeAgent describe(String name, boolean mutating, boolean destructive, Map<String, Object> params) {
        schema.add(new PrimitiveDescriptor(name, "does " + name, params, null, "WORKER", 5000, null, mutating,
                destructive));
        return this;
    }

    /** Describes a primitive that evaluates a caller's expression (the {@code script} flag, D156). */
    synchronized FakeAgent describeScript(String name, boolean mutating, Map<String, Object> params) {
        schema.add(new PrimitiveDescriptor(name, "does " + name, params, null, "WORKER", 5000, null, mutating, false,
                true));
        return this;
    }

    /** Every later call, await or catalogue read throws AgentLostException once {@code method} is called. */
    synchronized FakeAgent loseOn(String method) {
        this.loseOn = method;
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

    /** The recorded calls of one method. */
    synchronized List<Call> callsTo(String method) {
        return calls.stream().filter(c -> c.method().equals(method)).toList();
    }

    /** Every method called, in order. */
    synchronized List<String> methods() {
        return calls.stream().map(Call::method).toList();
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
        // The dispatcher's confirmation gate (phase1-contracts.md 5): a destructive primitive needs _meta.confirm.
        for (PrimitiveDescriptor d : schema) {
            if (d.name().equals(method) && d.destructive() && (options == null || !options.confirm())) {
                throw new AgentRpcException(method, new RpcError(ErrorCodes.CONFIRM_REQUIRED, method
                        + " is destructive: it needs a confirmation", null));
            }
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

    @Override
    public synchronized Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
            throws AgentLostException {
        if (lost) {
            throw new AgentLostException("connection refused");
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
        if (lost) {
            throw new AgentLostException("connection refused");
        }
        return new CapabilityMatrix(capabilities);
    }

    @Override
    public synchronized List<PrimitiveDescriptor> schema() throws AgentLostException {
        if (lost) {
            throw new AgentLostException("connection refused");
        }
        return List.copyOf(schema);
    }

    @Override
    public synchronized Map<String, Object> ping() throws AgentLostException {
        if (lost) {
            throw new AgentLostException("connection refused");
        }
        return Map.of("protocol", Protocol.VERSION, "pid", 4242L, "nonce", "n-1", "loadMode", "ATTACH");
    }

    @Override
    public synchronized boolean isLost() {
        return lost;
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
