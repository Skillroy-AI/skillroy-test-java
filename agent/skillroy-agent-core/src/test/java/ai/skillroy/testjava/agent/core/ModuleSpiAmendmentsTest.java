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

import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SPI amendments of the hardening wave, end to end through a running agent: withdrawn capabilities (A18), APP
 * before the application thread exists (A19), live options (A20), module redaction keys (A21) and the host loader as
 * context class loader of agent threads (finding S34).
 */
class ModuleSpiAmendmentsTest {

    private static final String LIVE = Protocol.PROP_PREFIX + "coretest.live";

    @TempDir
    Path tmp;

    private AgentHarness agent;
    private AgentRuntime runtime;

    @BeforeEach
    void reset() {
        TestState.reset();
    }

    @AfterEach
    void stop() {
        TestState.stuck.countDown();
        if (agent != null) {
            agent.close();
        }
        if (runtime != null) {
            runtime.close();
        }
        System.clearProperty(LIVE);
        System.clearProperty("coretest.pin");
    }

    private void awaitReady() throws Exception {
        AgentHarness.await("target ready", 30000, new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(agent.runtime.isReady());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capabilities() throws Exception {
        return (Map<String, Object>) agent.valueMap("agent.capabilities", null).get("capabilities");
    }

    // ---- A18 ----

    @Test
    void aWithdrawnCapabilityIsNotListedAndItsPrimitivesAnswerCapabilityUnavailable() throws Exception {
        agent = AgentHarness.start(tmp);
        awaitReady();
        assertEquals("ok", agent.value("test.needsDynamic", null), "never declared: no gate");

        agent.value("test.capability", map("id", "coretest.dynamic", "action", "declare"));
        AgentHarness.await("probed at once once the target is ready", 10000,
                new java.util.concurrent.Callable<Boolean>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Boolean call() throws Exception {
                        Object cap = capabilities().get("coretest.dynamic");
                        return Boolean.valueOf(cap != null && "OK".equals(((Map<String, Object>) cap).get("state")));
                    }
                });
        assertEquals("ok", agent.value("test.needsDynamic", null));

        agent.value("test.capability", map("id", "coretest.dynamic", "action", "closeHandle"));
        assertFalse(capabilities().containsKey("coretest.dynamic"), "closing the handle withdraws it");
        Map<String, Object> error = agent.error("test.needsDynamic", null);
        assertEquals((long) ErrorCodes.CAPABILITY_UNAVAILABLE, code(error));
        assertEquals("capability withdrawn", data(error).get("reason"));
        assertEquals("coretest.dynamic", data(error).get("capability"));

        agent.value("test.capability", map("id", "coretest.dynamic", "action", "set"));
        assertEquals("ok", agent.value("test.needsDynamic", null), "set again: the withdrawal is forgotten");
        agent.value("test.capability", map("id", "coretest.dynamic", "action", "undeclare"));
        assertEquals((long) ErrorCodes.CAPABILITY_UNAVAILABLE, code(agent.error("test.needsDynamic", null)));
        Map<String, Object> listed = agent.get("/capabilities").json();
        assertFalse(String.valueOf(listed).contains("coretest.dynamic"), "/capabilities does not list it either");
    }

    @Test
    void aStaleHandleDoesNotWithdrawALaterDeclaration() throws Exception {
        agent = AgentHarness.start(tmp);
        awaitReady();
        CoreCapabilityRegistry caps = (CoreCapabilityRegistry) agent.runtime.context().capabilities();
        AutoCloseable first = caps.declare("x.cap", new ai.skillroy.testjava.agent.api.Probe() {
            @Override
            public ai.skillroy.testjava.agent.api.ProbeResult run(ai.skillroy.testjava.agent.api.AgentContext c) {
                return ai.skillroy.testjava.agent.api.ProbeResult.ok("first");
            }
        });
        caps.declare("x.cap", new ai.skillroy.testjava.agent.api.Probe() {
            @Override
            public ai.skillroy.testjava.agent.api.ProbeResult run(ai.skillroy.testjava.agent.api.AgentContext c) {
                return ai.skillroy.testjava.agent.api.ProbeResult.ok("second");
            }
        });
        first.close();
        assertTrue(caps.get("x.cap") != null, "the second declaration is current and stays");
        assertFalse(caps.isWithdrawn("x.cap"));
        caps.undeclare("x.cap");
        assertNull(caps.get("x.cap"));
        assertTrue(caps.isWithdrawn("x.cap"));
        first.close();
        caps.undeclare("never.declared");
        assertFalse(caps.isWithdrawn("never.declared"), "undeclaring an unknown id has no effect");
    }

    @Test
    void aModulesCapabilitiesAreWithdrawnAfterItsUninstall() throws Exception {
        agent = AgentHarness.start(tmp);
        awaitReady();
        agent.value("test.capability", map("id", "coretest.set", "action", "set"));
        CoreCapabilityRegistry caps = (CoreCapabilityRegistry) agent.runtime.context().capabilities();
        assertTrue(caps.get("coretest.probed") != null && caps.get("coretest.set") != null);
        agent.close();
        assertTrue(TestState.UNINSTALLED.contains("coretest"));
        for (String id : Arrays.asList("coretest.probed", "coretest.off", "coretest.degraded", "coretest.set")) {
            assertNull(caps.get(id), id);
            assertTrue(caps.isWithdrawn(id), id);
        }
        assertTrue(caps.get(AgentRuntime.TARGET_READY) != null, "the core's own capabilities are not a module's");
        agent = null;
    }

    // ---- A19 ----

    @Test
    void beforeTheApplicationThreadExistsReadOnlyAppPrimitivesRunOnAWorkerAndMutatingOnesAreNotReady()
            throws Exception {
        agent = AgentHarness.start(tmp, "coretest.appThread", "true");
        awaitReady();
        TestState.appAvailable = false;
        assertTrue(String.valueOf(agent.value("test.where.app", null)).startsWith("skillroy-worker-"));
        Map<String, Object> error = agent.error("test.app.mutate", null);
        assertEquals((long) ErrorCodes.NOT_READY, code(error));
        assertEquals("application thread not available yet", data(error).get("reason"));
        assertEquals("application thread not available yet", error.get("message"));

        TestState.appAvailable = true;
        assertEquals("coretest-app", agent.value("test.where.app", null));
        assertEquals("coretest-app", agent.value("test.app.mutate", null));
    }

    // ---- A20 ----

    @Test
    void optionSeesASystemPropertySetAfterTheStart() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.both", "from-start");
        assertNull(agent.value("test.option", map("key", "coretest.live")));
        System.setProperty(LIVE, "set-later");
        assertEquals("set-later", agent.value("test.option", map("key", "coretest.live")));
        System.setProperty(Protocol.PROP_PREFIX + "coretest.both", "from-property");
        try {
            assertEquals("from-start", agent.value("test.option", map("key", "coretest.both")));
        } finally {
            System.clearProperty(Protocol.PROP_PREFIX + "coretest.both");
        }
        assertFalse(agent.runtime.context().options().containsKey("coretest.live"), "options() is the snapshot");
    }

    // ---- A21 ----

    @Test
    @SuppressWarnings("unchecked")
    void aModulesRedactionKeysHideItsPropertiesInJvmProperties() throws Exception {
        System.setProperty("coretest.pin", "4711");
        agent = AgentHarness.start(tmp, "coretest.redactionKeys", "coretest\\.pin");
        Map<String, Object> values = (Map<String, Object>) agent.valueMap("jvm.properties",
                map("names", Arrays.asList("coretest.pin", "java.version"))).get("properties");
        assertEquals(Redactor.REDACTED, values.get("coretest.pin"));
        assertEquals(System.getProperty("java.version"), values.get("java.version"));
        agent.close();
        agent = null;

        agent = AgentHarness.start(tmp.resolve("2"));
        values = (Map<String, Object>) agent.valueMap("jvm.properties",
                map("names", Arrays.asList("coretest.pin"))).get("properties");
        assertEquals("4711", values.get("coretest.pin"), "without the module's keys the value is not secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailingRedactionKeysProviderMakesEveryKeySecret() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.redactionKeys", "throw");
        Map<String, Object> values = (Map<String, Object>) agent.valueMap("jvm.properties",
                map("names", Arrays.asList("java.version"))).get("properties");
        assertEquals(Redactor.REDACTED, values.get("java.version"));
    }

    // ---- S34 ----

    @Test
    @SuppressWarnings("unchecked")
    void agentThreadsAndThreadsTheyStartCarryTheHostLoader() throws Exception {
        ClassLoader host = new ClassLoader(getClass().getClassLoader()) {
        };
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("discoveryDir", tmp.toString());
        runtime = AgentRuntime.start(options, null, host, "EMBEDDED", null);
        Map<String, Object> response = runtime.call("test.contextLoader", null);
        Map<String, Object> value = (Map<String, Object>) ((Map<String, Object>) response.get("result")).get("value");
        assertEquals(Boolean.TRUE, value.get("workerIsHost"), "the worker's context loader is the host's");
        assertEquals(Boolean.TRUE, value.get("spawnedIsHost"), "and so is that of a thread host code starts on it");
    }
}
