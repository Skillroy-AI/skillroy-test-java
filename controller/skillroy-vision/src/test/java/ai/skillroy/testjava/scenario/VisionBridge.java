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
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The executor tests' toy pack, fake agent and session fixture ({@code skillroy-scenario}'s test-jar,
 * phase7-contracts.md 6.1) for the vision module's tests: in the scenario module's package so that it reaches the
 * fixtures, which are package-private (as the telemetry module's {@code TelemetryFixture} does).
 */
public final class VisionBridge {

    /** Answers one call of the fake agent. */
    @FunctionalInterface
    public interface Handler {
        Object handle(Map<String, Object> params) throws AgentRpcException, AgentLostException;
    }

    /** The toy staff member 100's password (the test credentials'). */
    public static final String PASSWORD_100 = TestCredentials.PW_100;

    private final SessionFixture fixture;

    /** @param dir the test's temporary directory (the session's journal goes below it) */
    public VisionBridge(Path dir) {
        this.fixture = new SessionFixture(dir.resolve("session"));
    }

    /** The toy pack. */
    public DomainPack pack() {
        return fixture.pack;
    }

    /** The fake agent. */
    public AgentConnection agent() {
        return fixture.agent;
    }

    /** The session options of the fixture (seed-a, the test clock, the journal in the directory). */
    public SessionOptions options() {
        return fixture.options();
    }

    /** The fixture's clock. */
    public Clock clock() {
        return fixture.clock;
    }

    /** Programs a method. */
    public VisionBridge on(String method, Handler handler) {
        fixture.agent.on(method, handler::handle);
        return this;
    }

    /** Programs a method to fail with an agent error. */
    public VisionBridge error(String method, int code, String message, Map<String, Object> data) {
        fixture.agent.on(method, params -> {
            throw new AgentRpcException(method, new RpcError(code, message, data));
        });
        return this;
    }

    /** Adds a primitive to the catalogue. */
    public VisionBridge describe(String name, boolean mutating) {
        fixture.agent.describe(name, mutating);
        return this;
    }

    /** Sets a capability of the agent's matrix. */
    public VisionBridge capability(String id, CapabilityState state, String reason) {
        fixture.agent.capability(id, state, reason);
        return this;
    }

    /** Publishes an event on the fake bus. */
    public long publish(String topic, Map<String, ?> payload) {
        return fixture.agent.publish(topic, payload);
    }

    /** The bus position. */
    public long lastSeq() {
        return fixture.agent.lastSeq();
    }

    /** The parameters of every call of a method, in order. */
    public List<Map<String, Object>> callsTo(String method) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FakeAgentConnection.Call call : fixture.agent.callsTo(method)) {
            out.add(call.params());
        }
        return out;
    }

    /** The methods called, without the executor's bookkeeping. */
    public List<String> stepMethods() {
        return fixture.agent.stepMethods();
    }

    /** Resolves the toy item and the assistant (staff 100 with its password joined) as a person. */
    public static void resolveItemAndAssistant(ExecutorSession session, Origin origin) throws Exception {
        SessionFixture.resolveItemAndAssistant(session, origin);
    }
}
