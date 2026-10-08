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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A scriptable {@link AgentConnection} for the CDP path's tests (the scenario module's fake is package-private
 * there): every call is recorded and answered by a programmed handler; {@code evt.query} answers the fake bus's
 * {@code lastSeq}, which starts at 100 so that the agent's sequence is never confused with the path's own;
 * {@code ping} answers {@code readOnly: false} unless set. {@link #await} records the wait and answers null (a
 * timeout) unless an answer is set. Thread-safe.
 */
final class FakeAgent implements AgentConnection {

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

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<Await> awaits = new CopyOnWriteArrayList<>();
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(100);
    volatile boolean readOnly;
    volatile Event awaitAnswer;

    FakeAgent() {
        on("evt.query", p -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("events", List.of());
            v.put("lastSeq", seq.get());
            v.put("oldestSeq", 1L);
            return v;
        });
    }

    FakeAgent on(String method, Handler handler) {
        handlers.put(method, handler);
        return this;
    }

    FakeAgent value(String method, Object value) {
        return on(method, p -> value);
    }

    FakeAgent error(String method, int code, String message) {
        return on(method, p -> {
            throw new AgentRpcException(method, new RpcError(code, message, Map.of("why", "test")));
        });
    }

    /** Advances the agent's bus as if an event was published; returns the new last seq. */
    long publish() {
        return seq.incrementAndGet();
    }

    long lastSeq() {
        return seq.get();
    }

    List<Call> callsTo(String method) {
        return calls.stream().filter(c -> c.method().equals(method)).toList();
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException, AgentLostException {
        return call(method, params, CallOptions.defaults());
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params, CallOptions options)
            throws AgentRpcException, AgentLostException {
        Map<String, Object> copy = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        calls.add(new Call(method, copy, options));
        Handler handler = handlers.get(method);
        if (handler == null) {
            throw new AgentRpcException(method, new RpcError(ErrorCodes.METHOD_NOT_FOUND, "no " + method, null));
        }
        long before = seq.get();
        Object value = handler.handle(copy);
        return new RpcResult(value, List.of(), before, seq.get(), 1, "fake-worker");
    }

    @Override
    public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
        awaits.add(new Await(topics, sinceSeq, match, timeout));
        return awaitAnswer;
    }

    @Override
    public CapabilityMatrix capabilities() {
        return new CapabilityMatrix(Map.of());
    }

    @Override
    public List<PrimitiveDescriptor> schema() {
        return List.of();
    }

    @Override
    public Map<String, Object> ping() {
        return Map.of("protocol", "0.2", "readOnly", readOnly);
    }

    @Override
    public void close() {
    }

    /** A {@code web.browsers} entry. */
    static Map<String, Object> browser(String ref, String targetId, String tag, boolean visible, String url) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ref", ref);
        b.put("adapter", "jcef");
        b.put("url", url);
        b.put("title", tag + " page");
        b.put("closed", false);
        b.put("visibleInSwing", visible);
        b.put("rendering", "WINDOWED");
        b.put("capabilities", List.of("EVALUATE"));
        if (targetId != null) {
            b.put("cdpTargetId", targetId);
        }
        if (tag != null) {
            b.put("tag", tag);
        }
        b.put("frames", List.of());
        return b;
    }

    /** A {@code web.cdp.status} value with one endpoint on 127.0.0.1. */
    static Map<String, Object> status(int port) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("host", "127.0.0.1");
        endpoint.put("port", (long) port);
        endpoint.put("engineLabel", "jcef");
        endpoint.put("adapter", "jcef");
        return Map.of("endpoints", List.of(endpoint), "browsers", List.of());
    }
}
