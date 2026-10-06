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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding S14: the production gate fails closed when no {@code ProductionMarker} is registered and a module FAILED
 * (the fixture module {@code failing} registers a passing marker and then throws, so its marker is rolled back),
 * unless {@code allowFailedModules=true}; {@code requireProductionMarker=true} refuses outright.
 */
class ProductionGateTest {

    private static final String PROP_ERROR = Protocol.PROP_PREFIX + "error";

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @BeforeEach
    void reset() {
        TestState.reset();
        System.clearProperty(PROP_ERROR);
    }

    @AfterEach
    void stop() {
        if (agent != null) {
            agent.close();
        }
        System.clearProperty(PROP_ERROR);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> targetReady() throws Exception {
        Map<String, Object> caps = (Map<String, Object>) agent.valueMap("agent.capabilities", null)
                .get("capabilities");
        return (Map<String, Object>) caps.get(AgentRuntime.TARGET_READY);
    }

    private void awaitGateDecision() throws Exception {
        AgentHarness.await("the gate decided", 30000, new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                Object reason = targetReady().get("reason");
                return Boolean.valueOf(String.valueOf(reason).startsWith("production gate not evaluated"));
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLostMarkerOfAFailedModuleKeepsMutatingPrimitivesNotReady() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.noMarker", "true");
        awaitGateDecision();
        Map<String, Object> error = agent.error("test.mutate", null);
        assertEquals((long) ErrorCodes.NOT_READY, code(error));
        String reason = String.valueOf(data(error).get("reason"));
        assertTrue(reason.contains("no ProductionMarker registered") && reason.contains("failing"), reason);
        assertEquals(0, TestState.MUTATIONS.get());
        assertFalse(agent.runtime.isReady());
        assertEquals(Boolean.FALSE, agent.get("/ping").json().get("ready"));
        assertEquals("UNAVAILABLE", targetReady().get("state"));
        assertTrue(String.valueOf(System.getProperty(PROP_ERROR)).startsWith("production gate not evaluated"));
        assertTrue(TestState.READY_CALLS.isEmpty(), "no module is wired on an unchecked target");
        boolean listed = false;
        for (Object module : (List<Object>) agent.valueMap("agent.info", null).get("modules")) {
            Map<String, Object> m = (Map<String, Object>) module;
            listed |= "failing".equals(m.get("id")) && "FAILED".equals(m.get("state"));
        }
        assertTrue(listed, "read-only primitives stay available and show which module failed");
    }

    @Test
    void allowFailedModulesServesAnyway() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.noMarker", "true", "allowFailedModules", "true");
        AgentHarness.await("ready", 30000, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(agent.runtime.isReady());
            }
        });
        assertEquals(1L, ((Number) agent.value("test.mutate", null)).longValue());
    }

    @Test
    void aRegisteredMarkerThatPassesLetsFailedModulesServe() throws Exception {
        agent = AgentHarness.start(tmp);   // coretest registers a passing marker; failing and needs.missing FAILED
        AgentHarness.await("ready", 30000, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(agent.runtime.isReady());
            }
        });
    }

    @Test
    void requireProductionMarkerRefusesWhenNoneIsRegistered() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.noMarker", "true", "requireProductionMarker", "true");
        AgentHarness.await("refused", 30000, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(agent.runtime.isClosed());
            }
        });
        String error = System.getProperty(PROP_ERROR);
        assertTrue(error.startsWith("refused: production marker: no ProductionMarker registered "
                + "(requireProductionMarker=true)"), error);
        assertTrue(error.contains("failing"), "the FAILED modules are named: " + error);
    }

    @Test
    void aMissingModulesPathIsAFailedRecordThatBlocksTheGate() throws Exception {
        String missing = tmp.resolve("no-such-module.jar").toString();
        agent = AgentHarness.start(tmp.resolve("disc"), "coretest.noMarker", "true", "modules", missing);
        awaitGateDecision();
        assertTrue(String.valueOf(targetReady().get("reason")).contains(missing));
        assertEquals((long) ErrorCodes.NOT_READY, code(agent.error("test.mutate", null)));
    }

    @Test
    void theOptionsAreBooleans() {
        java.util.Map<String, String> options = new java.util.LinkedHashMap<String, String>();
        options.put("allowFailedModules", "yes");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AgentRuntime.resolveOptions(options)).getMessage().contains("allowFailedModules"));
        options.put("allowFailedModules", "TRUE");
        options.put("requireProductionMarker", "1");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AgentRuntime.resolveOptions(options)).getMessage().contains("requireProductionMarker"));
        assertEquals("false", AgentRuntime.resolveOptions(null).get("requireProductionMarker"));
    }
}
