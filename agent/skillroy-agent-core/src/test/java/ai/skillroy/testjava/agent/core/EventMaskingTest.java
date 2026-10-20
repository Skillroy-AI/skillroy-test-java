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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.core.fixture.CoreTestModule;
import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mask pass through a running agent (phase8-contracts.md 5.1, 13.1 row 15): a module's {@code MaskPolicy} masks
 * the module's own events whether they are published from {@code install}'s context or from a primitive through
 * {@code inv.context()} (the core's context, {@code Dispatcher.java:302}), and every reader of the ring, the call's
 * result, {@code evt.query}, {@code sync.await} and its match, sees the masked payload; {@code agent.info.events}
 * counts.
 */
class EventMaskingTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @BeforeEach
    void reset() {
        TestState.reset();
    }

    @AfterEach
    void stop() {
        if (agent != null) {
            agent.close();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> queried() throws Exception {
        return (List<Object>) agent.valueMap("evt.query", map("topics", CoreTestModule.MASKED_TOPIC, "since",
                Long.valueOf(0))).get("events");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Object event) {
        return (Map<String, Object>) ((Map<String, Object>) event).get("payload");
    }

    @SuppressWarnings("unchecked")
    private static void assertMasked(Map<String, Object> payload, String via, String personal) {
        assertEquals(via, payload.get("via"));
        assertEquals(personal, payload.get("name"), "a top-level column");
        assertEquals(personal, ((Map<String, Object>) payload.get("person")).get("name"), "a dotted column");
        assertEquals("S1", ((Map<String, Object>) payload.get("person")).get("code"), "the rest is kept");
        assertEquals("<redacted>", payload.get("password"), "the key rule");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsFromInstallAndFromAnInvocationContextAreBothMasked() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.eventMask", "on");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());

        List<Object> installed = queried();
        assertEquals(1, installed.size(), String.valueOf(installed));
        assertMasked(payloadOf(installed.get(0)), "install", CoreTestModule.PERSONAL);

        Map<String, Object> result = agent.result("test.publish.masked", null);
        long seq = ((Number) result.get("value")).longValue();
        List<Object> recorded = (List<Object>) result.get("events");
        assertEquals(1, recorded.size(), "the call's own event");
        assertMasked(payloadOf(recorded.get(0)), "invocation", CoreTestModule.PERSONAL);

        List<Object> all = queried();
        assertEquals(2, all.size());
        assertEquals(Long.valueOf(seq), ((Map<String, Object>) all.get(1)).get("seq"));
        assertMasked(payloadOf(all.get(1)), "invocation", CoreTestModule.PERSONAL);

        Map<String, Object> awaited = agent.valueMap("sync.await", map("topics", CoreTestModule.MASKED_TOPIC,
                "since", Long.valueOf(0), "match", map("via", "invocation", "name", CoreTestModule.PERSONAL),
                "timeoutMs", Long.valueOf(5000)));
        assertEquals(Long.valueOf(seq), awaited.get("seq"), "the match sees the masked value");
        Map<String, Object> missed = agent.error("sync.await", map("topics", CoreTestModule.MASKED_TOPIC,
                "since", Long.valueOf(0), "match", map("name", CoreTestModule.PLANTED_NAME), "timeoutMs",
                Long.valueOf(200)));
        assertEquals((long) ErrorCodes.TIMEOUT, AgentHarness.code(missed), "no event matches the published value");

        String everything = Json.write(agent.valueMap("evt.query", map("since", Long.valueOf(0), "limit",
                Long.valueOf(1000))));
        assertFalse(everything.contains(CoreTestModule.PLANTED_NAME), everything);
        assertFalse(everything.contains(CoreTestModule.PLANTED_PASSWORD), everything);

        Map<String, Object> events = (Map<String, Object>) agent.valueMap("agent.info", null).get("events");
        assertEquals(Long.valueOf(1), events.get("maskPolicies"), "coretest's policy");
        assertEquals(Long.valueOf(0), events.get("maskFailures"));
        assertEquals(Long.valueOf(0), events.get("overBudget"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aThrowingModulePolicyRedactsCountsAndLogsWithoutTheValue() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.eventMask", "throw");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        agent.result("test.publish.masked", null);
        List<Object> all = queried();
        assertEquals(2, all.size());
        assertMasked(payloadOf(all.get(0)), "install", "<redacted>");
        assertMasked(payloadOf(all.get(1)), "invocation", "<redacted>");
        Map<String, Object> events = (Map<String, Object>) agent.valueMap("agent.info", null).get("events");
        assertEquals(Long.valueOf(4), events.get("maskFailures"), "two scalars per event");
        String log = String.valueOf(agent.runtime.context().log().tail(500));
        assertTrue(log.contains("MaskPolicy "), log);
        assertFalse(log.contains(CoreTestModule.PLANTED_NAME), "the exception's message is not logged: " + log);
    }
}
