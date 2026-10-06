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
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.withMeta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding S30: bounded worker and caller pools, and the abandoned-call gate that keeps a primitive whose earlier call
 * timed out (and still runs in host code that ignores interrupts) from piling up threads.
 */
class BoundedPoolsTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;

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
    }

    private static int threadsNamed(String prefix) {
        int n = 0;
        for (Thread t : CorePrimitives.allThreads()) {
            if (t.getName().startsWith(prefix) && t.isAlive()) {
                n++;
            }
        }
        return n;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPrimitiveWithATimedOutCallStillRunningAnswersBusyUntilThatCallEnds() throws Exception {
        assertStuckPrimitiveIsGated("test.stuck.worker", "skillroy-worker-");
    }

    @Test
    void theGateAppliesToCallerPrimitivesToo() throws Exception {
        assertStuckPrimitiveIsGated("test.stuck.caller", "skillroy-http-call-");
    }

    @SuppressWarnings("unchecked")
    private void assertStuckPrimitiveIsGated(String primitive, String pool) throws Exception {
        agent = AgentHarness.start(tmp);
        Map<String, Object> timeout = agent.error(primitive, withMeta(null, "timeoutMs", Long.valueOf(200)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(timeout));
        assertEquals(1, TestState.STUCK_RUNNING.get(), "the body ignores the interrupt and keeps running");
        int poolThreads = threadsNamed(pool);

        for (int i = 0; i < 5; i++) {
            Map<String, Object> busy = agent.error(primitive, withMeta(null, "timeoutMs", Long.valueOf(200)));
            assertEquals((long) CoreThreadBroker.BUSY, code(busy));
            assertEquals(primitive, data(busy).get("primitive"));
            assertTrue(String.valueOf(data(busy).get("thread")).startsWith(pool), String.valueOf(data(busy)));
            assertTrue(((Number) data(busy).get("runningForMs")).longValue() >= 0);
        }
        assertEquals(1, TestState.STUCK_RUNNING.get(), "no second body ever started");
        assertEquals(poolThreads, threadsNamed(pool), "no thread was added for the refused calls");
        Map<String, Object> threads = (Map<String, Object>) agent.valueMap("agent.info", null).get("threads");
        assertEquals(1L, ((Number) ((Map<String, Object>) threads.get("abandoned")).get(primitive)).longValue());
        assertTrue(agent.value("test.echo", null) != null, "other primitives are not affected");

        TestState.stuck.countDown();
        AgentHarness.await("the abandoned call ended", 10000, new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(TestState.STUCK_RUNNING.get() == 0);
            }
        });
        AgentHarness.await("the gate opened", 10000, new Callable<Boolean>() {
            @Override
            @SuppressWarnings("unchecked")
            public Boolean call() throws Exception {
                Map<String, Object> t = (Map<String, Object>) agent.valueMap("agent.info", null).get("threads");
                return Boolean.valueOf(((Map<String, Object>) t.get("abandoned")).isEmpty());
            }
        });
        assertEquals("released", agent.value(primitive, null), "it works again");
        assertTrue(String.valueOf(agent.runtime.context().log().tail(200)).contains("ended after"));
    }

    @Test
    void aFullPoolAnswersBusyInsteadOfCreatingAThread() throws Exception {
        RingLog log = new RingLog();
        ThreadPoolExecutor pool = DaemonThreads.boundedPool(new ThreadGroup("skillroy"), "skillroy-worker-", 1, log,
                getClass().getClassLoader());
        try {
            CoreThreadBroker broker = new CoreThreadBroker(pool, new CoreSpiRegistry(), log);
            final CountDownLatch hold = new CountDownLatch(1);
            final CountDownLatch running = new CountDownLatch(1);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    running.countDown();
                    try {
                        hold.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        // Ends the test task.
                    }
                }
            });
            assertTrue(running.await(10, TimeUnit.SECONDS));
            RpcException busy = assertThrows(RpcException.class, () -> broker.call(ThreadPolicy.WORKER,
                    new Callable<String>() {
                        @Override
                        public String call() {
                            return "ran";
                        }
                    }, 1000L));
            assertEquals(CoreThreadBroker.BUSY, busy.error().code());
            assertEquals("pool full", busy.error().data().get("reason"));
            RpcException app = assertThrows(RpcException.class, () -> broker.call(ThreadPolicy.APP,
                    new Callable<String>() {
                        @Override
                        public String call() {
                            return "ran";
                        }
                    }, 1000L));
            assertEquals(CoreThreadBroker.BUSY, app.error().code(), "APP without a provider uses the workers");
            assertEquals(1, pool.getPoolSize());
            hold.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePoolsAreBoundedRelativeToHttpThreads() throws Exception {
        agent = AgentHarness.start(tmp, "httpThreads", "4");
        Map<String, Object> threads = (Map<String, Object>) agent.valueMap("agent.info", null).get("threads");
        assertEquals(2L * 4 + AgentRuntime.POOL_HEADROOM,
                ((Number) ((Map<String, Object>) threads.get("workers")).get("max")).longValue());
        assertEquals(4L + AgentRuntime.POOL_HEADROOM,
                ((Number) ((Map<String, Object>) threads.get("callers")).get("max")).longValue());
    }
}
