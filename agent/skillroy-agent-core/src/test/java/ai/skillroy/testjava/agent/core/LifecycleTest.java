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
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.Topics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static ai.skillroy.testjava.agent.core.AgentHarness.withMeta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enable flag, readiness, production markers, modules, discovery, read-only mode and agent.disable. */
class LifecycleTest {

    private static final String[] DISCOVERY_PROPS = {"port", "nonce", "tokenFile", "protocol", "loadMode",
        "discoveryFile", "session"};

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @BeforeEach
    void reset() {
        TestState.reset();
    }

    @AfterEach
    void stop() {
        TestState.READY.set(true);
        if (agent != null) {
            agent.close();
        }
        System.clearProperty(Protocol.PROP_PREFIX + "error");
    }

    @Test
    void nothingStartsUnlessEnabled() throws Exception {
        List<String> before = AgentHarness.skillroyThreads();
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("discoveryDir", tmp.toString());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentRuntime.start(options, null, null, "EMBEDDED", null));
        assertTrue(e.getMessage().contains("enabled"));
        options.put("enabled", "yes");
        assertThrows(IllegalStateException.class, () -> AgentRuntime.start(options, null, null, "EMBEDDED", null));
        List<String> after = AgentHarness.skillroyThreads();
        after.removeAll(before);
        assertTrue(after.isEmpty(), "no thread was started: " + after);
        assertNull(System.getProperty(Protocol.PROP_PREFIX + "port"));
        assertEquals(0, tmp.toFile().list().length, "no file was written");
        assertTrue(TestState.INSTALLED.isEmpty(), "no module was installed");
    }

    @Test
    void invalidOptionsAreRejectedBeforeStarting() {
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("port", "70000");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntime.start(options, null, null, "EMBEDDED", null));
        options.put("port", "0");
        options.put("readOnly", "maybe");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntime.start(options, null, null, "EMBEDDED", null));
        options.put("readOnly", "false");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntime.start(options, null, null, "SIDEWAYS",
                null));
        options.put("httpThreads", "1");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntime.start(options, null, null, "EMBEDDED", null));
        options.put("httpThreads", "32");
        for (String bad : new String[] {"99", "3600001", "soon"}) {
            options.put("httpIdleTimeoutMs", bad);
            IllegalArgumentException idle = assertThrows(IllegalArgumentException.class,
                    () -> AgentRuntime.resolveOptions(options));
            assertTrue(idle.getMessage().contains("httpIdleTimeoutMs"), idle.getMessage());
        }
        options.put("httpIdleTimeoutMs", "100");
        assertEquals("100", AgentRuntime.resolveOptions(options).get("httpIdleTimeoutMs"));
        options.remove("httpIdleTimeoutMs");
        options.put("redact", "(unclosed");
        IllegalArgumentException badRegex = assertThrows(IllegalArgumentException.class,
                () -> AgentRuntime.start(options, null, null, "EMBEDDED", null));
        assertTrue(badRegex.getMessage().contains("redact"), badRegex.getMessage());
        Map<String, String> defaults = AgentRuntime.resolveOptions(null);
        assertEquals("0", defaults.get("port"));
        assertEquals("false", defaults.get("readOnly"));
        assertEquals("120000", defaults.get("readyTimeoutMs"));
        assertEquals("10000", defaults.get("eventRing"));
        assertEquals("32", defaults.get("httpThreads"));
        assertEquals("120000", defaults.get("httpIdleTimeoutMs"), "longer than the JDK HttpClient's 30 s pool");
        assertEquals(new File(System.getProperty("java.io.tmpdir"), "skillroy-"
                + System.getProperty("user.name").replaceAll("[^A-Za-z0-9._-]", "")).getPath(),
                defaults.get("discoveryDir"), "per-user default (contract amendment A3)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessGatesMutationsThenOnTargetReadyProbesAndTargetReady() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.readiness", "manual");
        assertFalse(agent.runtime.isReady());
        assertEquals(Boolean.FALSE, agent.get("/ping").json().get("ready"));
        Map<String, Object> notReady = agent.error("test.mutate", null);
        assertEquals((long) ErrorCodes.NOT_READY, code(notReady));
        assertTrue(String.valueOf(data(notReady).get("reason")).contains("coretest.readiness"));
        Map<String, Object> unprobed = agent.error("test.needsProbe", null);
        assertEquals((long) ErrorCodes.NOT_READY, code(unprobed), "declared capability not probed yet");
        assertEquals((long) ErrorCodes.NOT_READY, code(agent.error("test.needsReady", null)));
        assertEquals("ok", agent.value("test.degraded", null), "read-only primitives are served before readiness");
        assertEquals(0, TestState.PROBE_RUNS.get());
        assertTrue(TestState.READY_CALLS.isEmpty());

        long since = agent.runtime.context().events().lastSeq();
        TestState.READY.set(true);
        Map<String, Object> event = agent.valueMap("sync.await", map("topics", Topics.TARGET_READY,
                "since", Long.valueOf(since), "timeoutMs", Long.valueOf(30000)));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) event.get("payload")).get("ready"));
        assertTrue(agent.runtime.isReady());
        assertEquals(1, TestState.PROBE_RUNS.get());
        assertEquals(Arrays.asList("coretest", "order.b", "order.a"), stripThreads(TestState.READY_CALLS));
        // On a thread of its own per module (finding S30), not on a pool thread a stuck primitive could hold.
        assertTrue(TestState.READY_CALLS.get(0).contains("@skillroy-on-ready-coretest"),
                TestState.READY_CALLS.toString());
        Map<String, Object> caps = (Map<String, Object>) agent.valueMap("agent.capabilities", null)
                .get("capabilities");
        assertEquals("OK", ((Map<String, Object>) caps.get("target.ready")).get("state"));
        assertEquals("probe-strategy", ((Map<String, Object>) caps.get("coretest.probed")).get("strategy"));
        assertEquals(Long.valueOf(1), agent.value("test.mutate", null));
        assertEquals("ok", agent.value("test.needsProbe", null));
        agent.valueMap("agent.capabilities", map("reprobe", Boolean.TRUE));
        assertEquals(2, TestState.PROBE_RUNS.get());
        assertEquals(Boolean.TRUE, agent.get("/ping").json().get("ready"));
    }

    private static List<String> stripThreads(List<String> calls) {
        String[] out = new String[calls.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = calls.get(i).substring(0, calls.get(i).indexOf('@'));
        }
        return Arrays.asList(out);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessTimeoutReportsTargetReadyUnavailable() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.readiness", "manual", "readyTimeoutMs", "300");
        Event event = agent.runtime.context().events().await(0, TopicFilter.parse(Topics.TARGET_READY), null, 30000);
        assertNotNull(event);
        assertEquals(Boolean.FALSE, event.payload().get("ready"));
        Map<String, Object> ready = (Map<String, Object>) ((Map<String, Object>) agent.valueMap(
                "agent.capabilities", null).get("capabilities")).get("target.ready");
        assertEquals("UNAVAILABLE", ready.get("state"));
        assertTrue(String.valueOf(ready.get("reason")).contains("readiness timeout"));
        assertEquals((long) ErrorCodes.CAPABILITY_UNAVAILABLE, code(agent.error("test.needsReady", null)));
        assertEquals((long) ErrorCodes.NOT_READY, code(agent.error("test.mutate", null)));
        assertEquals((long) ErrorCodes.NOT_READY, code(agent.error("test.needsProbe", null)));
        assertTrue(TestState.READY_CALLS.isEmpty(), "onTargetReady is never called for an unready target");
        assertEquals(0, TestState.PROBE_RUNS.get());

        // A target that becomes ready after the timeout is still served.
        TestState.READY.set(true);
        Event late = agent.runtime.context().events().await(event.seq(), TopicFilter.parse(Topics.TARGET_READY), null,
                30000);
        assertNotNull(late, "a second target.ready event");
        assertEquals(Boolean.TRUE, late.payload().get("ready"));
        assertTrue(agent.runtime.isReady());
        assertEquals(3, TestState.READY_CALLS.size());
        assertEquals(1, TestState.PROBE_RUNS.get());
        assertEquals("ok", agent.value("test.needsReady", null));
        assertEquals(Long.valueOf(1), agent.value("test.mutate", null));
    }

    @Test
    void productionMarkerShutsTheAgentDown() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.production", "looks live");
        final int port = agent.runtime.port();
        AgentHarness.await("agent closed", 30000, () -> agent.runtime.isClosed());
        agent.runtime.close();  // waits for the shutdown already in progress to finish
        assertTrue(AgentHarness.portClosed(port));
        assertTrue(TestState.READY_CALLS.isEmpty(), "modules are never wired on a production target");
        List<Event> stopping = agent.runtime.context().events().since(0, TopicFilter.parse(Topics.AGENT_STOPPING),
                10);
        assertEquals(1, stopping.size());
        assertTrue(String.valueOf(stopping.get(0).payload().get("reason")).contains("looks live"));
        assertTrue(System.getProperty(Protocol.PROP_PREFIX + "error").contains("looks live"));
        assertNull(System.getProperty(Protocol.PROP_PREFIX + "port"));
        assertEquals(0, tmp.toFile().list().length, "discovery and token files removed");
        assertEquals(Arrays.asList("failing", "order.a", "order.b", "coretest"), TestState.UNINSTALLED,
                "the failed module cleaned up at install; the others in reverse install order");
    }

    @Test
    @SuppressWarnings("unchecked")
    void modulesInstallInDependencyOrderAndFailuresAreReported() throws Exception {
        agent = AgentHarness.start(tmp);
        assertEquals(Arrays.asList("coretest", "order.b", "order.a"), TestState.INSTALLED);
        Map<String, Object> info = agent.valueMap("agent.info", null);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<String, Map<String, Object>>();
        for (Object module : (List<Object>) info.get("modules")) {
            Map<String, Object> m = (Map<String, Object>) module;
            byId.put((String) m.get("id"), m);
        }
        assertEquals(Arrays.asList("coretest", "order.a", "failing", "needs.missing", "order.b"),
                Arrays.asList(byId.keySet().toArray()), "discovery order, duplicate dropped");
        assertEquals("INSTALLED", byId.get("coretest").get("state"));
        assertEquals("1.0-test", byId.get("coretest").get("version"));
        assertFalse(byId.get("coretest").containsKey("message"));
        assertEquals("INSTALLED", byId.get("order.a").get("state"));
        assertEquals("1", byId.get("order.b").get("version"), "first of the duplicate ids wins");
        assertEquals("FAILED", byId.get("failing").get("state"));
        assertTrue(String.valueOf(byId.get("failing").get("message")).contains("install boom"));
        assertEquals("FAILED", byId.get("needs.missing").get("state"));
        assertTrue(String.valueOf(byId.get("needs.missing").get("message")).contains("nope"));
        assertEquals((long) ErrorCodes.METHOD_NOT_FOUND, code(agent.error("failing.partial", null)),
                "a failed install is rolled back");
        assertFalse(((Map<String, Object>) agent.value("app.state", null)).containsKey("failing"));
        assertTrue(TestState.UNINSTALLED.contains("failing"), "a failed module may clean up after itself");
        assertTrue(String.valueOf(agent.runtime.context().log().tail(500)).contains("duplicate module id 'order.b'"));
        List<Event> installed = agent.runtime.context().events().since(0, TopicFilter.parse(
                Topics.MODULE_INSTALLED), 10);
        assertEquals(3, installed.size());
        assertEquals("order.b", installed.get(1).payload().get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void moduleJarsFromHomeAndTheModulesOptionComeFirstInTheirOwnLoaders() throws Exception {
        File home = tmp.resolve("home").toFile();
        File modules = new File(home, "modules");
        assertTrue(modules.mkdirs());
        TestJars.moduleJar(new File(modules, "10-jarmod.jar"), "ai.skillroy.testjava.agent.core.jarfixture.JarModule");
        new File(modules, "notes.txt").createNewFile();
        File extra = tmp.resolve("extra.jar").toFile();
        TestJars.moduleJar(extra, "ai.skillroy.testjava.agent.core.jarfixture.JarModuleTwo");
        File empty = tmp.resolve("empty.jar").toFile();
        TestJars.jar(empty, new LinkedHashMap<String, byte[]>());
        agent = AgentHarness.startWithHost(tmp.resolve("disc"), "host-object", "home", home.getPath(), "modules",
                extra.getPath() + ", " + empty.getPath() + "," + tmp.resolve("missing.jar"));
        List<Object> ids = new java.util.ArrayList<Object>();
        Map<Object, Map<String, Object>> byId = new LinkedHashMap<Object, Map<String, Object>>();
        for (Object module : (List<Object>) agent.valueMap("agent.info", null).get("modules")) {
            ids.add(((Map<String, Object>) module).get("id"));
            byId.put(((Map<String, Object>) module).get("id"), (Map<String, Object>) module);
        }
        // Finding S14: a jar without a service entry and a missing path are FAILED records in their source position.
        String missing = tmp.resolve("missing.jar").toString();
        assertEquals(Arrays.asList("jarmod", "jarmod2", empty.getPath(), missing, "coretest"), ids.subList(0, 5));
        assertEquals("FAILED", byId.get(empty.getPath()).get("state"));
        assertTrue(String.valueOf(byId.get(empty.getPath()).get("message")).contains("declares no"));
        assertEquals("FAILED", byId.get(missing).get("state"));
        assertTrue(String.valueOf(byId.get(missing).get("message")).contains("does not exist"));
        for (String id : new String[] {"jarmod", "jarmod2"}) {
            Map<String, Object> where = agent.valueMap(id + ".where", null);
            assertEquals(ModuleClassLoader.class.getName(), where.get("loader"), id);
            assertEquals(Boolean.TRUE, where.get("apiShared"), id);
            assertEquals(Boolean.TRUE, where.get("seesHostClass"), id);
            assertEquals("host-object", where.get("hostContext"), id);
        }
        String log = String.valueOf(agent.runtime.context().log().tail(500));
        assertTrue(log.contains("declares no"), log);
        assertTrue(log.contains("does not exist"), log);
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoveryFileAndPropertiesArePublishedAndRemoved() throws Exception {
        agent = AgentHarness.start(tmp);
        String name = "skillroy-" + JvmIds.pid() + "-" + JvmIds.startTimeMs();
        File discovery = tmp.resolve(name + ".json").toFile();
        File token = tmp.resolve(name + ".token").toFile();
        assertEquals(discovery.getAbsolutePath(), agent.runtime.discoveryFile());
        assertEquals(token.getAbsolutePath(), agent.runtime.tokenFile());
        AgentHarness.assertOwnerOnly(discovery);
        AgentHarness.assertOwnerOnly(token);
        assertEquals(64, agent.token.length());
        Map<String, Object> json = Json.parseObject(new String(Files.readAllBytes(discovery.toPath()),
                AgentHarness.UTF8));
        assertEquals(Arrays.asList("pid", "startTimeMs", "port", "tokenFile", "nonce", "sessionId", "protocol",
                "loadMode", "mainCommand"), Arrays.asList(json.keySet().toArray()));
        assertEquals(agent.runtime.sessionId(), json.get(Protocol.KEY_SESSION_ID));
        assertTrue(ai.skillroy.testjava.protocol.Auth.isValidSessionId(agent.runtime.sessionId()));
        assertEquals(agent.runtime.sessionId(), System.getProperty(Protocol.PROP_SESSION));
        assertEquals(agent.runtime.sessionId(), agent.get("/ping").json().get(Protocol.KEY_SESSION_ID));
        assertEquals(Long.valueOf(JvmIds.pid()), json.get("pid"));
        assertEquals(Long.valueOf(agent.runtime.port()), json.get("port"));
        assertEquals(token.getAbsolutePath(), json.get("tokenFile"));
        assertEquals(agent.runtime.nonce(), json.get("nonce"));
        assertEquals("EMBEDDED", json.get("loadMode"));
        assertEquals(String.valueOf(agent.runtime.port()), System.getProperty(Protocol.PROP_PREFIX + "port"));
        assertEquals(agent.runtime.nonce(), System.getProperty(Protocol.PROP_PREFIX + "nonce"));
        assertEquals(token.getAbsolutePath(), System.getProperty(Protocol.PROP_PREFIX + "tokenFile"));
        assertEquals(Protocol.VERSION, System.getProperty(Protocol.PROP_PREFIX + "protocol"));
        assertEquals("EMBEDDED", System.getProperty(Protocol.PROP_PREFIX + "loadMode"));
        assertEquals(discovery.getAbsolutePath(), System.getProperty(Protocol.PROP_PREFIX + "discoveryFile"));

        assertTrue(discovery.delete());
        agent.runtime.republishDiscovery();
        assertTrue(discovery.isFile(), "re-publish writes the file again");

        agent.close();
        agent.close();
        assertFalse(discovery.exists());
        assertFalse(token.exists());
        for (String prop : DISCOVERY_PROPS) {
            assertNull(System.getProperty(Protocol.PROP_PREFIX + prop), prop);
        }
        AgentHarness.await("agent threads gone", 10000, () -> AgentHarness.skillroyThreads().isEmpty());
        agent = null;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSuppliedTokenAndNonceAreUsedAndTheirFileIsNotOwned() throws Exception {
        File tokenFile = tmp.resolve("given.token").toFile();
        Files.write(tokenFile.toPath(), "supplied-token".getBytes(AgentHarness.UTF8));
        agent = AgentHarness.start(tmp, "token", "supplied-token", "nonce", "n-0", "tokenFile", tokenFile.getPath());
        assertEquals("supplied-token", agent.token);
        assertEquals("n-0", agent.get("/ping").json().get("agentNonce"));
        Map<String, Object> options = (Map<String, Object>) agent.valueMap("agent.info", null).get("options");
        assertFalse(options.containsKey("token"));
        assertFalse(options.containsKey("nonce"));
        assertEquals(Redactor.REDACTED, options.get("tokenFile"), "a key containing 'token' is redacted (A5)");
        agent.close();
        assertTrue(tokenFile.exists(), "a token file the agent did not write is left alone");
        agent = null;
    }

    @Test
    void readOnlyRejectsMutatingPrimitives() throws Exception {
        agent = AgentHarness.start(tmp, "readOnly", "true");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        assertEquals(Boolean.TRUE, agent.get("/ping").json().get("readOnly"));
        assertEquals((long) ErrorCodes.READ_ONLY, code(agent.error("test.mutate", null)));
        assertEquals((long) ErrorCodes.READ_ONLY, code(agent.error("device.act", map("id", "printer",
                "action", "print"))));
        assertEquals(0, TestState.MUTATIONS.get());
        assertEquals(Boolean.TRUE, agent.valueMap("agent.info", null).get("readOnly"));
    }

    @Test
    void agentDisableStopsTheAgentButNotTheHost() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.readiness", "manual");
        final int port = agent.runtime.port();
        String discovery = agent.runtime.discoveryFile();
        assertEquals((long) ErrorCodes.CONFIRM_REQUIRED, code(agent.error("agent.disable", null)));
        Map<String, Object> value = agent.valueMap("agent.disable", withMeta(null, "confirm", Boolean.TRUE));
        assertEquals(map("stopping", Boolean.TRUE), value, "exempt from NOT_READY, answered before stopping");
        AgentHarness.await("port closed", 30000, () -> AgentHarness.portClosed(port));
        AgentHarness.await("closed", 30000, () -> agent.runtime.isClosed());
        agent.runtime.close();  // waits for the disable already in progress to finish
        assertFalse(new File(discovery).exists());
        assertNull(System.getProperty(Protocol.PROP_PREFIX + "port"));
        assertEquals(Arrays.asList("failing", "order.a", "order.b", "coretest"), TestState.UNINSTALLED,
                "the failed module cleaned up at install; the others in reverse install order");
        List<Event> stopping = agent.runtime.context().events().since(0, TopicFilter.parse(Topics.AGENT_STOPPING),
                10);
        assertEquals("agent.disable", stopping.get(0).payload().get("reason"));
        AgentHarness.await("agent threads gone", 10000, () -> AgentHarness.skillroyThreads().isEmpty());
    }
}
