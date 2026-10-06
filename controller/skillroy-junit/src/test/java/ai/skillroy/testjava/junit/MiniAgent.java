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

package ai.skillroy.testjava.junit;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal in-process agent for the glue tests (no HTTP, so no authentication): the {@code mini} application is
 * always IDLE, and its window never takes focus, so a COMPONENT click fires only through its ACTION fall-back.
 */
final class MiniAgent implements AgentConnection {

    final AtomicInteger calls = new AtomicInteger();
    volatile boolean closed;
    /** What {@code mini.echo} answers; null echoes the text it is given. */
    volatile String echoAnswer;

    @Override
    public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException {
        return call(method, params, CallOptions.defaults());
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params, CallOptions options) throws AgentRpcException {
        calls.incrementAndGet();
        Object value = switch (method) {
            case "trace.mark" -> Map.of("seq", 1L);
            case "app.state" -> Map.of("mini", Map.of("state", "IDLE"));
            case "mini.poke" -> Map.of();
            case "mini.echo" -> Map.of("text", echoAnswer != null ? echoAnswer : String.valueOf(params.get("text")));
            case "swing.settle" -> Map.of("settled", true, "ms", 1L);
            case "swing.click" -> !"COMPONENT".equals(params.get("rung"))
                    ? Map.of("rung", "ACTION", "fired", true, "fidelity", "FULL")
                    : "ACTION".equals(params.get("fallback"))
                    ? Map.of("rung", "ACTION", "requestedRung", "COMPONENT", "fidelity", "DEGRADED", "reason",
                            "FOCUS_FAILED: x", "fired", true)
                    : Map.of("rung", "FOCUS_FAILED", "fired", false, "reason", "FOCUS_FAILED: x");
            default -> throw new AgentRpcException(method, new RpcError(ErrorCodes.METHOD_NOT_FOUND, "no " + method,
                    null));
        };
        return new RpcResult(value, List.of(), 0, 1, 1, "mini");
    }

    @Override
    public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
        return null;
    }

    @Override
    public CapabilityMatrix capabilities() {
        return new CapabilityMatrix(Map.of("mini.state", new Capability("mini.state", CapabilityState.OK, null,
                null)));
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
        closed = true;
    }
}
