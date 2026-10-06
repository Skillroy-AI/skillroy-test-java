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

import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.EventQueue;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.withMeta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every thread policy, the APP provider and its fallback, and forbidden threads. */
class ThreadPolicyTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;

    private void start(String... options) throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, options);
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
    }

    @AfterEach
    void stop() {
        TestState.blocker.countDown();
        if (agent != null) {
            agent.close();
        }
    }

    private String threadOf(String primitive) throws Exception {
        RpcResult result = RpcResult.fromJson(agent.result(primitive, null));
        assertEquals(result.thread(), result.value(), "value and envelope name the same thread");
        return result.thread();
    }

    @Test
    void eachPolicyRunsWhereItSays() throws Exception {
        start();
        assertTrue(threadOf("test.where.caller").startsWith("skillroy-http-"));
        assertTrue(threadOf("test.where.worker").startsWith("skillroy-worker-"));
        assertTrue(threadOf("test.where.edtSync").startsWith("AWT-EventQueue-"));
        assertTrue(threadOf("test.where.app").startsWith("skillroy-worker-"), "APP without a provider = WORKER");
    }

    @Test
    void edtAsyncReturnsOncePostedAndTheTaskKeepsTheCorrelationId() throws Exception {
        start();
        long before = agent.runtime.context().events().lastSeq();
        RpcResult result = RpcResult.fromJson(agent.result("test.where.edtAsync",
                withMeta(null, "correlationId", "async-1")));
        assertNull(result.value());
        Event event = agent.runtime.context().events().await(before, TopicFilter.parse("test.where"), null, 30000);
        assertNotNull(event);
        assertTrue(((String) event.payload().get("thread")).startsWith("AWT-EventQueue-"));
        assertEquals("async-1", event.correlationId());
    }

    @Test
    void correlationIdFollowsTheTaskToItsThread() throws Exception {
        start("coretest.appThread", "true");
        for (String primitive : new String[] {"test.where.caller", "test.where.worker", "test.where.edtSync",
                "test.where.app"}) {
            RpcResult result = RpcResult.fromJson(agent.result(primitive,
                    withMeta(null, "correlationId", "corr-" + primitive)));
            assertEquals(1, result.events().size(), primitive);
            assertEquals("corr-" + primitive, result.events().get(0).correlationId());
            assertEquals(result.thread(), result.events().get(0).payload().get("thread"));
        }
    }

    @Test
    void appPolicyUsesTheProvidersThread() throws Exception {
        start("coretest.appThread", "true");
        assertEquals("coretest-app", threadOf("test.where.app"));
        Map<String, Object> state = agent.valueMap("app.state", null);
        assertEquals("coretest-app", ((Map<?, ?>) state.get("coretest")).get("thread"));
    }

    @Test
    void appTimeoutIsTheDispatchersTimeout() throws Exception {
        start("coretest.appThread", "true");
        Map<String, Object> error = agent.error("test.block.app", withMeta(null, "timeoutMs", Long.valueOf(300)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(error));
        assertEquals("test.block.app", data(error).get("primitive"));
        assertEquals(Long.valueOf(300), data(error).get("timeoutMs"));
    }

    @Test
    void aForbiddenThreadMayNotWaitForAgentWork() throws Exception {
        start("coretest.appThread", "true");
        assertEquals(Long.valueOf(ErrorCodes.FORBIDDEN_THREAD), agent.value("test.callFromForbidden", null));
    }

    @Test
    void aPrimitiveNeverRunsOnAForbiddenThread() throws Exception {
        start("coretest.appThread", "true");
        TestState.appOnForbidden = true;
        Map<String, Object> error = agent.error("test.app.count", null);
        assertEquals((long) ErrorCodes.FORBIDDEN_THREAD, code(error));
        assertTrue(((String) data(error).get("thread")).startsWith("coretest-forbidden"));
        assertEquals(0, TestState.APP_BODY_RUNS.get(), "the body did not run");
        TestState.appOnForbidden = false;
        assertEquals("coretest-app", agent.value("test.app.count", null));
        assertEquals(1, TestState.APP_BODY_RUNS.get());
    }

    @Test
    void brokerContract() throws Exception {
        start();
        CoreThreadBroker broker = (CoreThreadBroker) agent.runtime.context().threads();
        assertEquals("x", broker.call(ThreadPolicy.CALLER, constant("x"), 1000));
        assertEquals("y", broker.call(ThreadPolicy.WORKER, constant("y"), 5000));
        assertEquals("z", broker.call(ThreadPolicy.EDT_SYNC, constant("z"), 5000));
        assertNull(broker.call(ThreadPolicy.EDT_ASYNC, constant("ignored"), 5000));
        assertEquals("w", broker.call(ThreadPolicy.APP, constant("w"), 5000));
        RpcException timeout = assertThrows(RpcException.class, () -> broker.call(ThreadPolicy.WORKER,
                new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return Boolean.valueOf(TestState.blocker.await(30, java.util.concurrent.TimeUnit.SECONDS));
                    }
                }, 200));
        assertEquals(ErrorCodes.TIMEOUT, timeout.error().code());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> broker.call(
                ThreadPolicy.WORKER, new Callable<Object>() {
                    @Override
                    public Object call() {
                        throw new IllegalStateException("from worker");
                    }
                }, 5000));
        assertEquals("from worker", thrown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> broker.call(ThreadPolicy.WORKER, constant("x"), 0));
        // EDT_SYNC called on the EDT runs inline instead of deadlocking.
        final Object[] inline = new Object[1];
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    inline[0] = broker.call(ThreadPolicy.EDT_SYNC, constant("inline"), 1000);
                } catch (Exception e) {
                    inline[0] = e;
                }
            }
        });
        assertEquals("inline", inline[0]);
        // post never throws, even for a failing task.
        broker.post(ThreadPolicy.WORKER, new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("posted failure");
            }
        });
        AgentHarness.await("posted failure logged", 10000, () -> String.valueOf(
                agent.runtime.context().log().tail(50)).contains("posted failure"));
    }

    private static Callable<Object> constant(final Object value) {
        return new Callable<Object>() {
            @Override
            public Object call() {
                return value;
            }
        };
    }
}
