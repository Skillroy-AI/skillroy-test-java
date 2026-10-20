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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scripted stand-in for the lending agent behind an {@link AgentConnection}: a tiny desk state machine that
 * answers {@code lending.state}, {@code lending.prompt}, {@code lending.prompt.answer}, {@code lending.input},
 * {@code lending.slip} and {@code data.query} the way the real module does, completes every input at once and
 * records each call. {@link #await} returns an event as soon as one was "published" since the given sequence number.
 */
final class FakeDesk implements AgentConnection {

    String state = "SIGNED_OFF";
    boolean awaiting = true;
    /** The open prompt's id and buttons, or null. */
    String promptId;
    List<String> promptButtons;
    /** What answering the open prompt with its first button leads to (state name), or null to stay. */
    String afterAnswer;
    /** When true, inputs are rejected with INPUT_REJECTED. */
    boolean rejectInputs;
    /**
     * When false, the module has no engine listener yet (an agent attached while a prompt was open): answering the
     * prompt publishes nothing and reports {@code listening:false}; the listener is attached, and
     * {@code input.requested} published, by the next call that looks at the desk.
     */
    boolean listening = true;
    /** The value data.query returns; an RpcError to fail. */
    Object dataQuery;
    /** When set, {@link #await} answers with this JSON-RPC error, as the real client does since amendment A31. */
    RpcError awaitError;
    List<Object> slips = new ArrayList<>();

    final List<String> calls = new ArrayList<>();
    private long seq = 100;
    private final List<Event> events = new ArrayList<>();

    @Override
    public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException {
        return call(method, params, CallOptions.defaults());
    }

    @Override
    public RpcResult call(String method, Map<String, Object> params, CallOptions options) throws AgentRpcException {
        long before = seq;
        String label = method + (params != null && params.containsKey("source") ? " " + params.get("source")
                : params != null && params.containsKey("choice") ? " " + params.get("choice") : "");
        calls.add(label);
        if (!listening && promptId == null && method.startsWith("lending.")) {
            listening = true;
            publish("input.requested");
        }
        Object value = switch (method) {
            case "lending.state" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", state);
                m.put("validInputs", awaiting && promptId == null ? validInputs() : List.of());
                m.put("awaitingInput", awaiting && promptId == null);
                yield m;
            }
            case "lending.prompt" -> promptId == null ? null : Map.of("id", promptId, "text", "t", "buttons",
                    promptButtons);
            case "lending.prompt.answer" -> {
                if (promptId == null) {
                    throw error(method, ErrorCodes.NOT_FOUND, "no prompt is open");
                }
                promptId = null;
                if (afterAnswer != null) {
                    state = afterAnswer;
                }
                if (listening) {
                    publish("input.requested");
                }
                yield Map.of("answered", true, "listening", listening);
            }
            case "lending.input" -> {
                String source = String.valueOf(params.get("source"));
                if (rejectInputs || promptId != null || !validInputs().contains(source)) {
                    throw error(method, ErrorCodes.INPUT_REJECTED, "rejected " + source);
                }
                state = switch (source) {
                    case "Key.CANCEL" -> "IDLE";
                    case "Key.SIGN_OFF" -> "SIGNED_OFF";
                    default -> state;
                };
                // phase8-contracts.md 7.4: the input's id in the result and in its acknowledgement.
                String inputId = params.get("inputId") instanceof String given ? given : "fake-" + (seq + 1);
                publish("input.acked", Map.of("source", source, "inputId", inputId));
                publish("input.requested");
                yield Map.of("accepted", true, "acked", true, "ackMs", 1L, "inputId", inputId, "consumed",
                        "CONSUMED");
            }
            case "lending.slip" -> Map.of("slips", slips);
            case "data.query" -> {
                if (dataQuery instanceof RpcError e) {
                    throw new AgentRpcException(method, e);
                }
                yield dataQuery;
            }
            default -> throw error(method, ErrorCodes.METHOD_NOT_FOUND, "unknown " + method);
        };
        return new RpcResult(value, List.of(), before, seq, 1, "fake");
    }

    private List<String> validInputs() {
        return switch (state) {
            case "SIGNED_OFF" -> List.of("Entry");
            case "PASSWORD", "LOCKED" -> List.of("Entry", "Key.SIGN_OFF");
            case "IDLE" -> List.of("Barcode", "Entry", "Key.RETURN", "Key.SIGN_OFF", "Key.QTY");
            case "DEPOSIT" -> List.of("Entry", "Key.DEPOSIT", "Key.CANCEL");
            default -> List.of("Barcode", "Entry", "Key.DUE", "Key.CANCEL", "Key.QTY");
        };
    }

    private void publish(String topic) {
        publish(topic, Map.of());
    }

    private void publish(String topic, Map<String, Object> payload) {
        seq++;
        events.add(new Event(seq, 0, topic, "lending", null, null, payload));
    }

    private static AgentRpcException error(String method, int code, String message) {
        return new AgentRpcException(method, new RpcError(code, message, null));
    }

    @Override
    public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
            throws AgentRpcException {
        calls.add("await " + topics);
        if (awaitError != null) {
            throw new AgentRpcException("sync.await", awaitError);
        }
        for (Event e : events) {
            if (e.seq() > sinceSeq && List.of(topics.split(",")).contains(e.topic())) {
                return e;
            }
        }
        return null;
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

    /** The agent session {@link #agentSession()} answers (phase8-contracts.md 9.3.2); null (the default) = none. */
    String session;

    @Override
    public String agentSession() {
        return session;
    }

    @Override
    public void close() {
    }
}
