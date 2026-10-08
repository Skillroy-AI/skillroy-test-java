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

package ai.skillroy.testjava.scenario.web;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The built-in web path (phase4-contracts.md section 6.1): every {@code web.*} primitive is the agent's own, served in
 * process by its {@code web} module through the embedded browser's channel. It is the path when the scenario pins
 * {@code web.path: agent}, when {@code auto} finds no usable CDP path, and for every call another path reports as
 * unsupported. It opens nothing, its events are the agent's bus (so {@link #eventsSince} is empty), and closing it
 * changes nothing.
 *
 * <p>Thread-safe as far as its connection is.
 */
public final class AgentWebPath implements WebPath {

    /** The id of this path, {@code "agent"}. */
    public static final String ID = "agent";

    private volatile AgentConnection agent;

    /** A path that learns its connection in {@link #open}. */
    public AgentWebPath() {
    }

    /** A path over this connection, usable without {@link #open}. */
    public AgentWebPath(AgentConnection agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    @Override
    public String id() {
        return ID;
    }

    /** Keeps the connection; the agent path is always usable, so the answer is {@code null}. */
    @Override
    public String open(AgentConnection connection, WebSettings settings, WebRunContext ctx) {
        this.agent = Objects.requireNonNull(connection, "connection");
        return null;
    }

    /** Every {@code web.*} primitive. */
    @Override
    public boolean serves(String primitive) {
        return primitive != null && primitive.startsWith("web.");
    }

    /** The agent's own call: its result, or its {@code AgentRpcException} or {@code AgentLostException}. */
    @Override
    public RpcResult call(String primitive, Map<String, Object> args, CallOptions options) throws Exception {
        AgentConnection connection = agent;
        if (connection == null) {
            throw new IllegalStateException("the agent path has no connection: open it first");
        }
        return connection.call(primitive, args, options == null ? CallOptions.defaults() : options);
    }

    /** Always empty: the agent's events are on its own bus, which the executor reads itself. */
    @Override
    public List<Event> eventsSince(long seq, String topics) {
        return List.of();
    }

    /** Nothing to close: the connection belongs to the caller. */
    @Override
    public void close() {
    }
}
