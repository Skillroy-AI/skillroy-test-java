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
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.EventQueue;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Close racing the other lifecycle steps: a module must never be left wired into the host. */
class LifecycleRaceTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;
    private final ExecutorService helper = Executors.newCachedThreadPool();

    @BeforeEach
    void reset() {
        TestState.reset();
    }

    @AfterEach
    void stop() {
        TestState.gate.countDown();
        if (agent != null) {
            agent.close();
        }
        helper.shutdownNow();
        System.clearProperty(Protocol.PROP_PREFIX + "error");
    }

    @Test
    void uninstallWaitsForARunningOnTargetReady() throws Exception {
        agent = AgentHarness.start(tmp, "coretest.slowReady", "true");
        assertTrue(TestState.entered.await(30, TimeUnit.SECONDS), "onTargetReady entered");
        Future<?> closing = helper.submit(new Runnable() {
            @Override
            public void run() {
                agent.runtime.close();
            }
        });
        AgentHarness.await("close begun", 30000, () -> agent.runtime.isClosed());
        // Give a wrong implementation time to uninstall underneath the running onTargetReady.
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline && !TestState.LIFECYCLE.contains("uninstall")) {
            Thread.sleep(20);
        }
        TestState.gate.countDown();
        closing.get(60, TimeUnit.SECONDS);
        AgentHarness.await("uninstalled", 30000, () -> TestState.LIFECYCLE.contains("uninstall"));
        assertEquals(Arrays.asList("ready-enter", "ready-exit", "uninstall"), TestState.LIFECYCLE,
                "a listener added by onTargetReady would otherwise outlive the uninstall that removes it");
        assertEquals(1, TestState.READY_CALLS.size(), "no module is wired once the agent is closing: "
                + TestState.READY_CALLS);
        assertEquals(0, TestState.PROBE_RUNS.get(), "no probe runs once the agent is closing");
        assertFalse(agent.runtime.isReady());
    }

    @Test
    void closeOnTheEventDispatchThreadHandsOverInsteadOfBlockingIt() throws Exception {
        agent = AgentHarness.start(tmp);
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        final int port = agent.runtime.port();
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                agent.runtime.close();
            }
        });
        AgentHarness.await("the handed-over close began", 30000, () -> agent.runtime.isClosed());
        agent.runtime.close();   // on this thread: waits for the handed-over close to finish
        assertTrue(AgentHarness.portClosed(port));
        assertEquals(Arrays.asList("failing", "order.a", "order.b", "coretest"), TestState.UNINSTALLED);
        String log = String.valueOf(agent.runtime.context().log().tail(50));
        assertTrue(log.contains("[skillroy-stop] stopping: closed"), "the shutdown ran on the agent's own thread, "
                + "not on the event-dispatch thread: " + log);
        AgentHarness.await("agent threads gone", 30000, () -> AgentHarness.skillroyThreads().isEmpty());
    }

    @Test
    void anAgentClosedWhileAModuleInstallsLeavesNoModuleBehind() throws Exception {
        final int port = freePort();
        final Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("enabled", "true");
        options.put("discoveryDir", tmp.toString());
        options.put("port", String.valueOf(port));
        options.put("token", "race-token-0123456789");
        options.put("coretest.slowInstall", "true");
        Future<AgentRuntime> starting = helper.submit(new Callable<AgentRuntime>() {
            @Override
            public AgentRuntime call() throws Exception {
                return AgentRuntime.start(options, null, LifecycleRaceTest.class.getClassLoader(), "EMBEDDED", null);
            }
        });
        assertTrue(TestState.entered.await(30, TimeUnit.SECONDS), "install entered");

        // Discovery is not published yet: learn the session from a verified /ping, as a client in --port mode does.
        String session = sessionFromPing(port, "race-token-0123456789");
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.disable\","
                + "\"params\":{\"_meta\":{\"confirm\":true}}}").getBytes(AgentHarness.UTF8);
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/rpc").openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization",
                AgentHarness.credentials("race-token-0123456789", session, "POST", "/rpc", body)[1]);
        c.setRequestProperty(ai.skillroy.testjava.protocol.Auth.HEADER_BODY_SHA256,
                ai.skillroy.testjava.protocol.Auth.sha256hex(body));
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        OutputStream out = c.getOutputStream();
        out.write(body);
        out.close();
        assertEquals(200, c.getResponseCode());
        Map<String, Object> response = Json.parseObject(AgentHarness.readAll(c.getInputStream()));
        assertNull(response.get("error"), String.valueOf(response));
        AgentHarness.await("port closed", 30000, () -> AgentHarness.portClosed(port));

        TestState.gate.countDown();
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> starting.get(90, TimeUnit.SECONDS));
        assertTrue(failed.getCause() instanceof IllegalStateException, String.valueOf(failed.getCause()));
        assertTrue(failed.getCause().getMessage().contains("closed during start"), failed.getCause().getMessage());
        AgentHarness.await("the late module is uninstalled", 30000,
                () -> TestState.UNINSTALLED.contains("coretest"));
        assertEquals(Collections.singletonList("coretest"), TestState.INSTALLED,
                "nothing else is installed into a closed agent");
        assertEquals(Collections.singletonList("coretest"), TestState.UNINSTALLED);
        assertNull(System.getProperty(Protocol.PROP_PREFIX + "port"), "discovery is never published");
        assertEquals(0, tmp.toFile().list().length, "no discovery or token file is left: "
                + Arrays.toString(tmp.toFile().list()));
        AgentHarness.await("agent threads gone", 30000, () -> AgentHarness.skillroyThreads().isEmpty());
    }

    /** The session id named by a {@code /ping} reply whose MAC verifies with that id. */
    static String sessionFromPing(int port, String token) throws Exception {
        String nonce = ai.skillroy.testjava.protocol.Auth.newNonce();
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/ping?nonce=" + nonce)
                .openConnection();
        c.setRequestProperty("Authorization", ai.skillroy.testjava.protocol.Auth.helloAuthorization(token, nonce));
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        assertEquals(200, c.getResponseCode());
        byte[] body = AgentHarness.readBytes(c.getInputStream());
        String session = c.getHeaderField(ai.skillroy.testjava.protocol.Auth.HEADER_SESSION);
        AgentHarness.assertReplyMac(token, session, nonce, 200, body,
                c.getHeaderField(ai.skillroy.testjava.protocol.Auth.HEADER_MAC), session);
        assertEquals(session, Json.parseObject(new String(body, AgentHarness.UTF8)).get("sessionId"));
        return session;
    }

    private static int freePort() throws Exception {
        ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }
}
