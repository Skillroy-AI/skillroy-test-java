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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The toy application and pack an interactive session runs against in the session and journal tests: the executor
 * tests' fake agent answering the toy primitives (input with ack and ready topics, a prompt that an unknown entry
 * raises and an answer closes, a state and a loan fact, a poke, a read and a wipe that needs a confirmation), and the
 * toy resolution model (items, staff with credentials, limits, the oracles).
 */
final class SessionFixture {

    final ToyResolution toy = new ToyResolution();
    final ToyPack pack = toy.pack();
    final FakeAgentConnection agent = new FakeAgentConnection();
    final TestClock clock = new TestClock();
    final Map<String, Object> state = new LinkedHashMap<>(Map.of("name", "IDLE"));
    final Path dir;

    SessionFixture(Path dir) {
        this.dir = dir;
        toy.install(agent);
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.item");
        agent.on("toy.input", params -> {
            Object value = params.get("value");
            agent.publish("input.acked", Map.of("source", params.get("source")));
            if ("UNKNOWN".equals(value)) {
                agent.publish("prompt.waiting", Map.of("id", "unknown", "text", "Unknown item", "buttons",
                        List.of("OK")));
            } else if ("POST".equals(value)) {
                agent.publish("loan.posted", Map.of("loanId", 41L, "total", "7.00"));
                agent.publish("input.requested", Map.of("state", "IDLE"));
            } else {
                state.put("name", "LOAN");
                agent.publish("input.requested", Map.of("state", "LOAN"));
            }
            return Map.of("accepted", true, "acked", true);
        });
        agent.on("toy.prompt.answer", params -> {
            agent.publish("input.acked", Map.of("source", "prompt"));
            agent.publish("prompt.answered", Map.of("choice", params.get("choice")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("answered", true);
        });
        agent.on("toy.state", params -> new LinkedHashMap<>(state));
        agent.value("toy.loan", Json.parse("{\"total\":\"7.00\",\"lines\":[{\"code\":\"B2\",\"qty\":2}]}"));
        agent.value("toy.poke", Map.of());
        agent.value("toy.read", Map.of("x", 1L));
        agent.value("toy.wipe", Map.of("wiped", true));
        agent.destructive("toy.wipe");
        agent.value("swing.snapshot", Json.parse("{\"seq\":9,\"truncated\":false,\"windows\":[{\"ref\":\"h1\","
                + "\"role\":\"window\",\"name\":\"Toy\",\"children\":[{\"ref\":\"h12\",\"role\":\"button\","
                + "\"name\":\"DUE\",\"enabled\":false}]}]}"));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
    }

    /** The options of the tests' sessions: the journal in the fixture's directory, the test clock, seed-a. */
    SessionOptions options() {
        return SessionOptions.defaults(dir).withClock(clock).withBind(TestCredentials.bind().withEnvId("seed-a"))
                .withId("sess-1");
    }

    /** A session with {@link #options()}. */
    ExecutorSession open() throws Exception {
        return ExecutorSession.open(pack, agent, options());
    }

    /** The item and the assistant of the toy model resolved: B2 (5.00), and staff 100 with its password joined. */
    static void resolveItemAndAssistant(ExecutorSession session, Origin origin) throws Exception {
        session.resolve("item", "Item", Map.of("deposit", Map.of("between", List.of("2.00", "50.00"))), List.of(),
                List.of(), null, origin);
        session.resolve("assistant", "Staff", Map.of("limitBelow", "$item.deposit"), List.of(), List.of(),
                new CredentialSpec("staff", "code"), origin);
    }
}
