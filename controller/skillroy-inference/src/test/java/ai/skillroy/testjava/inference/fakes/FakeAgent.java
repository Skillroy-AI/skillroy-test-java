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

package ai.skillroy.testjava.inference.fakes;

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
 * A scriptable agent for this module's tests, in the manner of the executor tests' fake (which lives in another
 * module's test tree): an event bus with sequence numbers, every call recorded, each method answered by a programmed
 * handler that may publish events or throw. Defaults: {@code trace.mark} publishes a {@code trace.mark} event,
 * {@code evt.query} answers from the bus, everything else is METHOD_NOT_FOUND. {@link #loseOn} makes every later call
 * throw {@link AgentLostException}.
 *
 * <p>Thread-safe through synchronisation.
 */
public final class FakeAgent implements AgentConnection {

    /** One recorded call. */
    public record Call(String method, Map<String, Object> params, CallOptions options) {
    }

    /** Answers one call. */
    @FunctionalInterface
    public interface Handler {
        Object handle(Map<String, Object> params) throws AgentRpcException, AgentLostException;
    }

    private final List<Call> calls = new ArrayList<>();
    private final List<Event> bus = new ArrayList<>();
    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, Capability> capabilities = new TreeMap<>();
    private final List<PrimitiveDescriptor> schema = new ArrayList<>();
    private long seq;
    private String loseOn;
    private boolean lost;

    public FakeAgent() {
        on("trace.mark", params -> Map.of("seq", publish("trace.mark", params)));
        on("evt.query", this::query);
    }

    /** Programs a method. */
    public synchronized FakeAgent on(String method, Handler handler) {
        handlers.put(method, handler);
        return this;
    }

    /** Programs a method that answers a fixed value. */
    public FakeAgent value(String method, Object value) {
        return on(method, params -> value);
    }

    /** Declares a primitive in the schema ({@code GET /schema}). */
    public synchronized FakeAgent describe(String name, boolean mutating, boolean destructive) {
        schema.add(new PrimitiveDescriptor(name, "the test's " + name, null, null, "EDT", 5000, null, mutating,
                destructive));
        return this;
    }

    /** A capability OK. */
    public synchronized FakeAgent capability(String id) {
        capabilities.put(id, new Capability(id, CapabilityState.OK, null, null));
        return this;
    }

    /** Every call after {@code method} is called (that one included) throws {@link AgentLostException}. */
    public synchronized FakeAgent loseOn(String method) {
        this.loseOn = method;
        return this;
    }

    /** Publishes an event on the bus; returns its sequence number. */
    public synchronized long publish(String topic, Map<String, ?> payload) {
        seq++;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach(copy::put);
        }
        bus.add(new Event(seq, 1_000_000L + seq, topic, "fake", null, null, copy));
        return seq;
    }

    /** The calls so far, in order. */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The methods called, without the executor's own bookkeeping ({@code trace.mark}, {@code evt.query}). */
    public synchronized List<String> stepMethods() {
        return calls.stream().map(Call::method).filter(m -> !m.equals("evt.query") && !m.equals("trace.mark"))
                .toList();
    }

    /** The calls of one method. */
    public synchronized List<Call> callsTo(String method) {
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
        TopicFilter filter = TopicFilter.parse(topics);
        for (Event event : bus) {
            if (event.seq() > sinceSeq && filter.matches(event.topic())) {
                return event;
            }
        }
        return null;
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
    public synchronized List<PrimitiveDescriptor> schema() {
        return List.copyOf(schema);
    }

    @Override
    public Map<String, Object> ping() {
        return Map.of("protocol", Protocol.VERSION);
    }

    @Override
    public synchronized boolean isLost() {
        return lost;
    }

    @Override
    public void close() {
    }
}
