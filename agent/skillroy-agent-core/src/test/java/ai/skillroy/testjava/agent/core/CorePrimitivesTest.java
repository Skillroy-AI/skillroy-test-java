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
import ai.skillroy.testjava.protocol.RpcResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The core primitives of phase1-contracts.md 5. */
class CorePrimitivesTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @BeforeEach
    void start() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, "custom.option", "kept");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
    }

    @AfterEach
    void stop() {
        agent.close();
        System.clearProperty("skillroy.test.prop");
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentInfo() throws Exception {
        Map<String, Object> info = agent.valueMap("agent.info", null);
        assertEquals(Arrays.asList("protocol", "agentVersion", "pid", "startTimeMs", "loadMode", "instrumentation",
                "readOnly", "ready", "javaVersion", "modules", "threads", "events", "options"),
                new ArrayList<Object>(info.keySet()));
        assertEquals(Protocol.VERSION, info.get("protocol"));
        assertTrue(String.valueOf(info.get("agentVersion")).matches("\\d+\\.\\d+\\.\\d+.*"), "filtered version");
        assertEquals("EMBEDDED", info.get("loadMode"));
        assertEquals(Boolean.FALSE, info.get("instrumentation"));
        assertEquals(Boolean.TRUE, info.get("ready"));
        assertEquals(System.getProperty("java.version"), info.get("javaVersion"));
        Map<String, Object> options = (Map<String, Object>) info.get("options");
        assertEquals("kept", options.get("custom.option"));
        assertEquals("120000", options.get("readyTimeoutMs"));
        assertFalse(options.containsKey("token"));
        assertFalse(options.containsKey("nonce"));
        // Phase 8 (phase8-contracts.md 5.1, D543): the mask pass's counters; coretest registers no MaskPolicy here.
        Map<String, Object> events = (Map<String, Object>) info.get("events");
        assertEquals(Arrays.asList("maskPolicies", "maskFailures", "overBudget", "slowPasses", "maxPassMs"),
                new ArrayList<Object>(events.keySet()));
        assertEquals(Long.valueOf(0), events.get("maskPolicies"));
        assertEquals(Long.valueOf(0), events.get("maskFailures"));
        assertEquals(Long.valueOf(0), events.get("overBudget"));
        assertTrue(((Number) events.get("maxPassMs")).longValue() >= 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentLog() throws Exception {
        List<Object> lines = (List<Object>) agent.valueMap("agent.log", map("n", Long.valueOf(500))).get("lines");
        assertTrue(String.valueOf(lines).contains("control server on 127.0.0.1:" + agent.runtime.port()));
        assertEquals(2, ((List<Object>) agent.valueMap("agent.log", map("n", Long.valueOf(2))).get("lines")).size());
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("agent.log", map("n", "many"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jvmInfoAndThreads() throws Exception {
        Map<String, Object> info = agent.valueMap("jvm.info", null);
        assertEquals(Long.valueOf(JvmIds.pid()), info.get("pid"));
        assertEquals(System.getProperty("java.vendor"), info.get("javaVendor"));
        assertTrue(((Number) info.get("uptimeMs")).longValue() > 0);
        assertTrue(info.get("inputArguments") instanceof List);
        assertEquals(System.getProperty("java.awt.headless"), info.get("headless"),
                "the system property as a string (or null), never GraphicsEnvironment.isHeadless() (A4)");
        assertTrue(((Map<String, Object>) info.get("heap")).containsKey("max"));
        List<Object> threads = (List<Object>) agent.valueMap("jvm.threads", map("nameContains", "skillroy-http"))
                .get("threads");
        assertFalse(threads.isEmpty());
        Map<String, Object> first = (Map<String, Object>) threads.get(0);
        assertEquals(Boolean.TRUE, first.get("daemon"));
        assertFalse(first.containsKey("stack"));
        List<Object> withStacks = (List<Object>) agent.valueMap("jvm.threads", map("stacks", Boolean.TRUE,
                "nameContains", "skillroy-http", "maxDepth", Long.valueOf(3))).get("threads");
        for (Object t : withStacks) {
            assertTrue(((List<Object>) ((Map<String, Object>) t).get("stack")).size() <= 3);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void jvmPropertiesReturnsOnlyTheNamedOnes() throws Exception {
        System.setProperty("skillroy.test.prop", "v");
        Map<String, Object> props = (Map<String, Object>) agent.valueMap("jvm.properties",
                map("names", Arrays.asList("skillroy.test.prop", "no.such.prop"))).get("properties");
        assertEquals(2, props.size());
        assertEquals("v", props.get("skillroy.test.prop"));
        assertTrue(props.containsKey("no.such.prop"));
        assertNull(props.get("no.such.prop"));
        List<String> many = new ArrayList<String>(Collections.nCopies(CorePrimitives.MAX_PROPERTY_NAMES + 1, "x"));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("jvm.properties", map("names", many))));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("jvm.properties",
                map("names", Arrays.asList(Long.valueOf(1))))));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("jvm.properties", null)));
    }

    @Test
    void aTraceMarkThatCannotBecomeAnEventIsRejectedNotAnsweredWithABogusSeq() throws Exception {
        Map<String, Object> attrs = new LinkedHashMap<String, Object>();
        Map<String, Object> inner = attrs;
        for (int i = 0; i < 190; i++) {
            Map<String, Object> next = new LinkedHashMap<String, Object>();
            inner.put("n", next);
            inner = next;
        }
        long before = agent.runtime.context().events().lastSeq();
        Map<String, Object> error = agent.error("trace.mark", map("label", "deep", "attrs", attrs));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(error));
        assertEquals(before, agent.runtime.context().events().lastSeq(), "nothing was published");
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceMarkAndEvtQuery() throws Exception {
        long seq = ((Number) agent.valueMap("trace.mark", map("label", "here", "attrs", map("k", "v")))
                .get("seq")).longValue();
        Map<String, Object> query = agent.valueMap("evt.query", map("since", Long.valueOf(seq - 1),
                "topics", Arrays.asList("trace.*")));
        List<Object> events = (List<Object>) query.get("events");
        assertEquals(1, events.size());
        Map<String, Object> event = (Map<String, Object>) events.get(0);
        assertEquals("trace.mark", event.get("topic"));
        assertEquals(map("label", "here", "attrs", map("k", "v")), event.get("payload"));
        assertTrue(((Number) query.get("lastSeq")).longValue() >= seq);
        assertEquals(Long.valueOf(1), query.get("oldestSeq"));
        Map<String, Object> all = agent.valueMap("evt.query", map("limit", Long.valueOf(5000)));
        assertTrue(((List<Object>) all.get("events")).size() <= CorePrimitives.MAX_QUERY_LIMIT);
        assertEquals("agent.started", ((Map<String, Object>) ((List<Object>) agent.valueMap("evt.query",
                map("topics", "agent.started")).get("events")).get(0)).get("topic"));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("evt.query", map("limit",
                Long.valueOf(0)))));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("trace.mark", null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncAwaitReturnsTheFirstMatchAfterSince() throws Exception {
        final long since = agent.runtime.context().events().lastSeq();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> waiting = pool.submit(new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() throws Exception {
                    return agent.valueMap("sync.await", map("topics", Arrays.asList("trace.mark"),
                            "since", Long.valueOf(since), "match", map("label", "wanted"),
                            "timeoutMs", Long.valueOf(30000)));
                }
            });
            agent.value("trace.mark", map("label", "other"));
            long wanted = ((Number) agent.valueMap("trace.mark", map("label", "wanted")).get("seq")).longValue();
            Map<String, Object> event = waiting.get(30, TimeUnit.SECONDS);
            assertEquals(Long.valueOf(wanted), event.get("seq"));
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> already = agent.valueMap("sync.await", map("topics", "trace.mark", "since",
                Long.valueOf(since), "timeoutMs", Long.valueOf(1000)));
        assertEquals("other", ((Map<String, Object>) already.get("payload")).get("label"), "already in the ring");
        Map<String, Object> timeout = agent.error("sync.await", map("topics", "trace.mark", "since",
                Long.valueOf(agent.runtime.context().events().lastSeq()), "timeoutMs", Long.valueOf(150)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(timeout));
        assertEquals(Long.valueOf(agent.runtime.context().events().lastSeq()), data(timeout).get("lastSeq"));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("sync.await", map("topics", "x"))));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("sync.await", map("since",
                Long.valueOf(0)))));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("sync.await", map("topics", "x",
                "since", Long.valueOf(0), "timeoutMs", Long.valueOf(-1)))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appStateAndDevices() throws Exception {
        Map<String, Object> state = agent.valueMap("app.state", null);
        assertEquals("IDLE", ((Map<String, Object>) state.get("coretest")).get("state"));
        Map<String, Object> devices = agent.valueMap("device.list", null);
        assertEquals(Collections.singletonList(map("id", "printer", "kind", "printer", "actions",
                Arrays.asList("cut", "print"))), devices.get("devices"));
        assertEquals(map("printed", Long.valueOf(0)), agent.value("device.state", map("id", "printer")));
        RpcResult acted = RpcResult.fromJson(agent.result("device.act", map("id", "printer", "action", "print",
                "args", map("text", "hello"))));
        assertEquals(map("printed", "hello"), acted.value());
        assertEquals(map("printed", Long.valueOf(1)), agent.value("device.state", map("id", "printer")));
        Map<String, Object> unknown = agent.error("device.state", map("id", "scanner"));
        assertEquals((long) ErrorCodes.NOT_FOUND, code(unknown));
        assertEquals(Arrays.asList("printer"), data(unknown).get("devices"));
        Map<String, Object> badAction = agent.error("device.act", map("id", "printer", "action", "explode"));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(badAction));
        assertEquals(Arrays.asList("cut", "print"), data(badAction).get("actions"));
    }

    /**
     * phase8-contracts.md 7.6 (ES): {@code device.list} adds {@code rungs} to a device only when an action has a
     * ladder; an old adapter's entry is unchanged ({@link #appStateAndDevices}) and a throwing {@code rungs} is logged
     * and read as empty.
     */
    @Test
    @SuppressWarnings("unchecked")
    void deviceListShowsAnActionsRungLadderOnlyWhenThereIsOne() throws Exception {
        agent.close();
        agent = AgentHarness.start(java.nio.file.Files.createDirectories(tmp.resolve("rungs")),
                "coretest.semanticInput", "on");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        List<Object> devices = (List<Object>) agent.valueMap("device.list", null).get("devices");
        Map<String, Object> byId = new java.util.LinkedHashMap<String, Object>();
        for (Object device : devices) {
            byId.put((String) ((Map<String, Object>) device).get("id"), device);
        }
        assertEquals(map("id", "printer", "kind", "printer", "actions", Arrays.asList("cut", "print")),
                byId.get("printer"), "no ladder, no key: the entry as before");
        assertEquals(map("id", "scanner", "kind", "scanner", "actions", Arrays.asList("reset", "scan"), "rungs",
                map("scan", Arrays.asList("PHYSICAL", "SEMANTIC"))), byId.get("scanner"));
        assertEquals(map("id", "badge", "kind", "badge", "actions", Arrays.asList("read")), byId.get("badge"),
                "a throwing rungs reads as empty");
        assertTrue(String.valueOf(agent.runtime.context().log().tail(200)).contains(".rungs(read) failed"));
    }
}
