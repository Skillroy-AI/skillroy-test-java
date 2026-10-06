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

package ai.skillroy.testjava.data;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** An {@link AgentConnection} whose primitives are lambdas; records every call. Unknown methods: METHOD_NOT_FOUND. */
final class FakeAgent implements AgentConnection {

    /** The body of one fake primitive: its value, or an {@link AgentRpcException}. */
    interface Body {
        Object call(Map<String, Object> params) throws AgentRpcException, AgentLostException;
    }

    record Call(String method, Map<String, Object> params, CallOptions options) { }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Body> bodies = new ConcurrentHashMap<>();

    FakeAgent on(String method, Body body) {
        bodies.put(method, body);
        return this;
    }

    /** A failing primitive. */
    FakeAgent fails(String method, int code, String message, Map<String, Object> data) {
        return on(method, params -> {
            throw new AgentRpcException(method, new RpcError(code, message, data));
        });
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
        calls.add(new Call(method, params, options));
        Body body = bodies.get(method);
        if (body == null) {
            throw new AgentRpcException(method, new RpcError(ErrorCodes.METHOD_NOT_FOUND, "unknown method " + method,
                    Map.of("method", method)));
        }
        return new RpcResult(body.call(params), List.of(), 0, 0, 1, "fake");
    }

    @Override
    public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
        throw new UnsupportedOperationException("not used by the data channels");
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
        return Map.of();
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
