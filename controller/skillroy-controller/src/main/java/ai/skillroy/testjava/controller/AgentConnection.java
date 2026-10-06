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

import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The seam the scenario executor is written against: a live connection to one agent. Lets the executor be tested
 * with a fake and keeps transport details (HTTP, SSE, attach) out of scenario code.
 *
 * <p>Implementations are thread-safe: calls may be issued concurrently from several threads. Every method that talks
 * to the agent throws {@link AgentLostException} when the agent cannot be reached or its JVM has changed.
 */
public interface AgentConnection extends AutoCloseable {

    /**
     * Calls a primitive with default {@link CallOptions}.
     *
     * @param params the primitive's params; null means none
     * @throws AgentRpcException if the agent answered with a JSON-RPC error
     */
    RpcResult call(String method, Map<String, Object> params) throws AgentRpcException, AgentLostException;

    /**
     * Calls a primitive with per-call options (correlationId, timeoutMs, confirm), sent as {@code params._meta}.
     *
     * @throws AgentRpcException if the agent answered with a JSON-RPC error
     */
    RpcResult call(String method, Map<String, Object> params, CallOptions options)
            throws AgentRpcException, AgentLostException;

    /**
     * Waits for the first event after {@code sinceSeq} whose topic matches {@code topics} (comma-separated globs)
     * and whose payload contains every {@code match} entry. Blocks the calling thread.
     *
     * @param match payload entries to match by string equality; null means any payload
     * @return the event, or null on timeout (the agent's TIMEOUT error)
     * @throws AgentRpcException for any other JSON-RPC error of the wait (for example INVALID_PARAMS for a malformed
     *     topic filter); contract amendment A31
     */
    Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
            throws AgentRpcException, AgentLostException;

    /** The agent's current capability matrix ({@code GET /capabilities}). */
    CapabilityMatrix capabilities() throws AgentLostException;

    /** The agent's primitive catalogue ({@code GET /schema}), sorted by name. */
    List<PrimitiveDescriptor> schema() throws AgentLostException;

    /** The {@code /ping} reply (nonce, protocol, pid, start time, load mode, ready, readOnly, sessionId). */
    Map<String, Object> ping() throws AgentLostException;

    /**
     * True once this connection knows the agent is gone for good (hardening item H2, the client latch): from then on
     * every method that talks to the agent throws {@link AgentLostException} without any I/O. A plain timeout does
     * not set it. The default is false, for connections that have no such state.
     */
    default boolean isLost() {
        return false;
    }

    /** Releases the connection (and any event streams it opened). Idempotent; never throws. */
    @Override
    void close();
}
