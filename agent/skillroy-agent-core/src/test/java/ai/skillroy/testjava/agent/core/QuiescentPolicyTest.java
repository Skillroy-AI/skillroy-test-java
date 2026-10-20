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
import ai.skillroy.testjava.agent.core.fixture.CoreTestModule;
import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static ai.skillroy.testjava.agent.core.AgentHarness.withMeta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ThreadPolicy.APP_QUIESCENT} through the dispatcher (phase8-contracts.md 4.1, 13.1 row 7): the fixture's
 * provider offers a point that an "engine" thread with the forbidden prefix drives every 2 ms.
 */
class QuiescentPolicyTest {

    private static final String ENGINE = "coretest-forbidden-engine";
    private static final String VIEW = "test.quiescent.view";

    @TempDir
    Path tmp;

    private AgentHarness agent;

    private void start(String... options) throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, options);
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
    }

    private void startWithPoint() throws Exception {
        start("coretest.appThread", "true", "coretest.quiescent", "true");
    }

    @AfterEach
    void stop() {
        TestState.enginePaused = false;
        TestState.quiescentSleepMs = 0L;
        TestState.blocker.countDown();
        if (agent != null) {
            agent.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> quiescentInfo() throws Exception {
        Map<String, Object> threads = (Map<String, Object>) agent.valueMap("agent.info", null).get("threads");
        return (Map<String, Object>) threads.get("quiescent");
    }

    @Test
    void theTaskRunsOnTheForbiddenEngineThreadWithTheCallsScope() throws Exception {
        startWithPoint();
        RpcResult result = RpcResult.fromJson(agent.result(VIEW, withMeta(null, "correlationId", "q-1")));
        assertEquals(ENGINE, result.thread(), "the envelope names the application's thread");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertEquals(ENGINE, value.get("thread"));
        assertEquals("q-1", value.get("correlationId"));
        assertEquals(Boolean.FALSE, value.get("interrupted"));
        assertEquals(1, result.events().size());
        assertEquals("q-1", result.events().get(0).correlationId(), "the call's scope is bound at the point");
        assertEquals(ENGINE, result.events().get(0).payload().get("thread"));
        // A primitive of every other policy still never runs on a forbidden thread.
        assertTrue(agent.runtime.context().threads().isForbiddenThread(findThread(ENGINE)));
        Map<String, Object> info = quiescentInfo();
        assertEquals("coretest", info.get("provider"));
        assertEquals(CoreTestModule.ALLOWANCE_MS, ((Number) info.get("allowanceMs")).longValue());
        assertEquals(4L, ((Number) info.get("capacity")).longValue());
        assertEquals(1L, ((Number) info.get("ran")).longValue());
        assertEquals(Collections.emptyList(), info.get("penalised"));
        assertNull(info.get("lastOverrun"));
        assertFalse(TestState.engineInterrupted);
    }

    @Test
    void noPointWithinTheTimeoutIsNotReadyAndTheTaskNeverRunsLater() throws Exception {
        startWithPoint();
        TestState.enginePaused = true;
        long start = System.nanoTime();
        Map<String, Object> error = agent.error(VIEW, withMeta(null, "timeoutMs", Long.valueOf(300)));
        long tookMs = (System.nanoTime() - start) / 1000000L;
        assertEquals((long) ErrorCodes.NOT_READY, code(error));
        assertEquals("no quiescent point within 300 ms", data(error).get("reason"));
        assertEquals(Long.valueOf(CoreTestModule.VIEW_BUDGET_MS), data(error).get("budgetMs"));
        assertTrue(tookMs < 2000, "answered within the call's time: " + tookMs);
        TestState.enginePaused = false;
        Thread.sleep(200);
        assertEquals(0, TestState.QUIESCENT_RUNS.get(), "a withdrawn task never runs");
        assertEquals(1L, ((Number) quiescentInfo().get("notReady")).longValue());
        assertEquals(ENGINE, RpcResult.fromJson(agent.result(VIEW, null)).thread(), "the next call runs");
        assertFalse(TestState.engineInterrupted);
    }

    @Test
    void withoutAProviderOrAPointTheCallIsCapabilityUnavailable() throws Exception {
        start();
        assertUnavailable(VIEW, "no quiescent point offered");
        agent.close();
        start("coretest.appThread", "true");
        assertUnavailable(VIEW, "no quiescent point offered");
        agent.close();
        start("coretest.appThread", "true", "coretest.quiescent", "throw");
        assertUnavailable(VIEW, "no quiescent point offered");
        assertTrue(String.valueOf(agent.runtime.context().log().tail(200)).contains("failed in quiescentPoint()"));
        assertEquals(0, TestState.QUIESCENT_RUNS.get());
    }

    @Test
    void aBudgetAboveTheAllowanceIsRefused() throws Exception {
        startWithPoint();
        Map<String, Object> error = assertUnavailable("test.quiescent.big", "quiescent budget exceeds the allowance");
        assertEquals(Long.valueOf(2 * CoreTestModule.ALLOWANCE_MS), data(error).get("budgetMs"));
        assertEquals(Long.valueOf(CoreTestModule.ALLOWANCE_MS), data(error).get("allowanceMs"));
        assertEquals(0, TestState.QUIESCENT_RUNS.get());
    }

    @Test
    void anUnavailableApplicationThreadIsNotReadyForReadsAndMutatingCallsAlike() throws Exception {
        startWithPoint();
        TestState.appAvailable = false;
        for (String primitive : Arrays.asList(VIEW, "test.quiescent.mutate")) {
            Map<String, Object> error = agent.error(primitive, null);
            assertEquals((long) ErrorCodes.NOT_READY, code(error), primitive);
            assertEquals(primitive, data(error).get("primitive"));
            assertEquals(Dispatcher.APP_UNAVAILABLE, data(error).get("reason"));
        }
        assertEquals(0, TestState.QUIESCENT_RUNS.get(), "no WORKER fall-back (D522)");
        TestState.appAvailable = true;
        assertEquals(ENGINE, agent.value("test.quiescent.mutate", null));
    }

    @Test
    void aQuiescentTaskMayNotWaitForAgentWorkButMayPostAndPublish() throws Exception {
        startWithPoint();
        for (String mode : Arrays.asList("call", "callQuiescent", "await")) {
            TestState.NESTED.clear();
            TestState.quiescentNested = mode;
            RpcResult result = RpcResult.fromJson(agent.result(VIEW, null));
            assertEquals(ENGINE, result.thread());
            assertEquals(Collections.<Object>singletonList(Long.valueOf(ErrorCodes.FORBIDDEN_THREAD)),
                    TestState.NESTED, mode);
        }
        TestState.NESTED.clear();
        TestState.quiescentNested = "post";
        RpcResult result = RpcResult.fromJson(agent.result(VIEW, null));
        assertEquals(1, result.events().size(), "publish is allowed");
        AgentHarness.await("the posted task ran", 10000, () -> TestState.NESTED.size() == 2);
        assertEquals("posted", TestState.NESTED.get(0));
        assertTrue(String.valueOf(TestState.NESTED.get(1)).startsWith("posted ran on skillroy-worker-"),
                TestState.NESTED.toString());
        assertFalse(TestState.engineInterrupted);
    }

    @Test
    void anOverrunIsCountedAndATwofoldOverrunPenalisesThePrimitiveUntilReprobe() throws Exception {
        startWithPoint();
        TestState.quiescentSleepMs = CoreTestModule.VIEW_BUDGET_MS + 10;
        agent.result(VIEW, null);
        Map<String, Object> info = quiescentInfo();
        assertEquals(1L, ((Number) info.get("overruns")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) info.get("lastOverrun");
        assertNotNull(last);
        assertEquals(VIEW, last.get("primitive"));
        assertEquals(Long.valueOf(CoreTestModule.VIEW_BUDGET_MS), last.get("budgetMs"));
        assertTrue(((Number) last.get("ranMs")).longValue() > CoreTestModule.VIEW_BUDGET_MS, last.toString());
        assertEquals(Collections.emptyList(), info.get("penalised"));

        TestState.quiescentSleepMs = 3 * CoreTestModule.VIEW_BUDGET_MS;
        agent.result(VIEW, null);
        assertEquals(Collections.<Object>singletonList(VIEW), quiescentInfo().get("penalised"));
        TestState.quiescentSleepMs = 0L;
        int runs = TestState.QUIESCENT_RUNS.get();
        Map<String, Object> error = assertUnavailable(VIEW, "quiescent task overran its budget");
        assertEquals(Long.valueOf(CoreTestModule.VIEW_BUDGET_MS), data(error).get("budgetMs"));
        assertTrue(((Number) data(error).get("ranMs")).longValue() > 2 * CoreTestModule.VIEW_BUDGET_MS);
        assertEquals(runs, TestState.QUIESCENT_RUNS.get(), "a penalised primitive does not run");
        assertEquals(ENGINE, agent.value("test.quiescent.mutate", null), "another primitive is not penalised");

        agent.value("agent.capabilities", map("reprobe", Boolean.TRUE));
        assertEquals(Collections.emptyList(), quiescentInfo().get("penalised"));
        assertEquals(ENGINE, RpcResult.fromJson(agent.result(VIEW, null)).thread());
        long logged = 0;
        for (String line : agent.runtime.context().log().tail(500)) {
            if (line.contains(VIEW + " held the application's thread")) {
                logged++;
            }
        }
        assertEquals(1, logged, "an overrun is logged once per primitive");
    }

    @Test
    void aTaskStillRunningAtItsCallersDeadlineIsATimeoutThenBusyUntilItEnds() throws Exception {
        startWithPoint();
        TestState.quiescentSleepMs = 1500L;
        Map<String, Object> error = agent.error(VIEW, withMeta(null, "timeoutMs", Long.valueOf(300)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(error));
        assertEquals(VIEW, data(error).get("primitive"));
        assertEquals(Long.valueOf(300), data(error).get("timeoutMs"));
        Map<String, Object> busy = agent.error(VIEW, null);
        assertEquals((long) ErrorCodes.BUSY, code(busy));
        assertEquals(ENGINE, data(busy).get("thread"));
        TestState.quiescentSleepMs = 0L;
        AgentHarness.await("the abandoned task ended", 10000,
                () -> ((Map<?, ?>) ((Map<?, ?>) agent.valueMap("agent.info", null).get("threads")).get("abandoned"))
                        .isEmpty());
        // It ran far past twice its budget: penalised once it ended.
        assertUnavailable(VIEW, "quiescent task overran its budget");
        agent.value("agent.capabilities", map("reprobe", Boolean.TRUE));
        assertEquals(ENGINE, RpcResult.fromJson(agent.result(VIEW, null)).thread());
        assertEquals(2, TestState.QUIESCENT_RUNS.get());
        assertFalse(TestState.engineInterrupted, "the application's thread is never interrupted");
    }

    @Test
    void postAndCallOfTheTwoNewPoliciesBehaveAsTheContractSays() throws Exception {
        startWithPoint();
        CoreThreadBroker broker = (CoreThreadBroker) agent.runtime.context().threads();
        final AtomicBoolean ran = new AtomicBoolean();
        broker.post(ThreadPolicy.APP_QUIESCENT, () -> ran.set(true));
        broker.post(ThreadPolicy.LANE, () -> ran.set(true));
        Thread.sleep(300);
        assertFalse(ran.get(), "a posted APP_QUIESCENT or LANE task never runs anywhere");
        String log = String.valueOf(agent.runtime.context().log().tail(200));
        assertTrue(log.contains("posted under APP_QUIESCENT is refused"), log);
        assertTrue(log.contains("posted under LANE is refused"), log);
        // call(APP_QUIESCENT) is callQuiescent(task, 50, t), on the engine thread.
        assertEquals(ENGINE, broker.call(ThreadPolicy.APP_QUIESCENT, threadName(), 2000));
        assertEquals(ENGINE, broker.callQuiescent(threadName(), 10, 2000));
        RpcException lane = expectRpc(() -> broker.call(ThreadPolicy.LANE, threadName(), 2000));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, lane.error().code());
        assertEquals("unknown lane", lane.error().data().get("reason"));
        assertFalse(TestState.engineInterrupted);
    }

    /**
     * Review fix L2-F3 (as the sceptic narrowed it): a module's own quiescent task ({@code callQuiescent}, and
     * {@code call(APP_QUIESCENT, …)}) that runs past twice its budget penalises that module, keyed
     * {@code module:<id>}, until reprobe; another module is not penalised, and the overrun is logged once per module.
     */
    @Test
    void aModulesOwnQuiescentTaskThatOverranTwiceItsBudgetPenalisesThatModuleOnly() throws Exception {
        startWithPoint();
        CoreContext core = (CoreContext) agent.runtime.context();
        ThreadBrokerHolder a = new ThreadBrokerHolder(new ModuleContext(core, "test.a"));
        ThreadBrokerHolder b = new ThreadBrokerHolder(new ModuleContext(core, "test.b"));
        assertEquals(ENGINE, a.threads.callQuiescent(sleeping(40), 10, 5000), "runs, then is penalised");
        assertEquals(Collections.<Object>singletonList("module:test.a"), quiescentInfo().get("penalised"));
        RpcException refused = expectRpc(() -> a.threads.callQuiescent(threadName(), 10, 5000));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, refused.error().code());
        assertEquals("module test.a: quiescent task overran its budget", refused.getMessage());
        assertEquals(Arrays.asList("reason", "module", "budgetMs", "ranMs"),
                new java.util.ArrayList<String>(refused.error().data().keySet()));
        assertEquals("quiescent task overran its budget", refused.error().data().get("reason"));
        assertEquals("test.a", refused.error().data().get("module"));
        assertEquals(10L, refused.error().data().get("budgetMs"));
        assertTrue(((Number) refused.error().data().get("ranMs")).longValue() > 20L, refused.error().toString());
        RpcException viaCall = expectRpc(() -> a.threads.call(ThreadPolicy.APP_QUIESCENT, threadName(), 5000));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, viaCall.error().code(), "call(APP_QUIESCENT) too");
        assertEquals(ENGINE, b.threads.callQuiescent(threadName(), 10, 5000), "another module is not penalised");
        assertEquals(ENGINE, agent.runtime.context().threads().callQuiescent(threadName(), 10, 5000),
                "the core's own call carries no penalty");
        // call(APP_QUIESCENT, …) is the default budget of 50 ms, keyed by the module as well.
        assertEquals(ENGINE, b.threads.call(ThreadPolicy.APP_QUIESCENT, sleeping(120), 5000));
        assertEquals(Arrays.<Object>asList("module:test.a", "module:test.b"), quiescentInfo().get("penalised"));
        agent.value("agent.capabilities", map("reprobe", Boolean.TRUE));
        assertEquals(Collections.emptyList(), quiescentInfo().get("penalised"));
        assertEquals(ENGINE, a.threads.callQuiescent(threadName(), 10, 5000));
        long logged = 0;
        for (String line : agent.runtime.context().log().tail(500)) {
            if (line.contains("a quiescent task of module test.a held the application's thread")) {
                logged++;
            }
        }
        assertEquals(1, logged, "logged once per module");
        assertFalse(TestState.engineInterrupted);
    }

    /** A module context's broker (the scoped view a module's install receives). */
    private static final class ThreadBrokerHolder {
        final ai.skillroy.testjava.agent.api.ThreadBroker threads;

        ThreadBrokerHolder(ModuleContext context) {
            this.threads = context.threads();
        }
    }

    private static Callable<String> sleeping(final long ms) {
        return () -> {
            long deadline = System.nanoTime() + ms * 1000000L;
            long left;
            while ((left = deadline - System.nanoTime()) > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(left);
            }
            return Thread.currentThread().getName();
        };
    }

    @Test
    void closeWithdrawsQueuedTasksAndClosesThePointBeforeTheModulesUninstall() throws Exception {
        startWithPoint();
        TestState.enginePaused = true;
        final CoreThreadBroker broker = (CoreThreadBroker) agent.runtime.context().threads();
        final AtomicReference<Object> outcome = new AtomicReference<Object>();
        Thread caller = new Thread(() -> {
            try {
                outcome.set(broker.callQuiescent(threadName(), 10, 10000));
            } catch (Exception e) {
                outcome.set(e);
            }
        }, "skillroy-test-quiescent-caller");
        caller.setDaemon(true);
        caller.start();
        AgentHarness.await("the task is queued", 10000,
                () -> ((Number) TestState.point.stats().get("queued")).intValue() == 1);
        agent.close();
        caller.join(10000);
        assertTrue(outcome.get() instanceof RpcException, String.valueOf(outcome.get()));
        RpcException e = (RpcException) outcome.get();
        assertEquals(ErrorCodes.NOT_READY, e.error().code());
        assertEquals("quiescent point closed", e.error().data().get("reason"));
        assertTrue(TestState.point.isClosed());
        assertEquals(Boolean.TRUE, TestState.pointClosedAtUninstall,
                "the core closed the point before the module's uninstall");
        assertFalse(TestState.engineInterrupted);
    }

    @Test
    void agentDisableClosesThePointBeforeTheModulesUninstall() throws Exception {
        startWithPoint();
        agent.value("agent.disable", withMeta(null, "confirm", Boolean.TRUE));
        AgentHarness.await("uninstalled", 30000, () -> TestState.pointClosedAtUninstall != null);
        assertEquals(Boolean.TRUE, TestState.pointClosedAtUninstall);
        assertTrue(TestState.point.isClosed());
    }

    private Map<String, Object> assertUnavailable(String primitive, String reason) throws Exception {
        Map<String, Object> error = agent.error(primitive, null);
        assertEquals((long) ErrorCodes.CAPABILITY_UNAVAILABLE, code(error), String.valueOf(error));
        assertEquals(primitive, data(error).get("primitive"));
        assertEquals(reason, data(error).get("reason"));
        return error;
    }

    private static Callable<String> threadName() {
        return () -> Thread.currentThread().getName();
    }

    private static RpcException expectRpc(Callable<?> call) throws Exception {
        try {
            Object value = call.call();
            throw new AssertionError("expected an RpcException, got " + value);
        } catch (RpcException e) {
            return e;
        }
    }

    private static Thread findThread(String name) {
        List<Thread> all = CorePrimitives.allThreads();
        for (Thread t : all) {
            if (name.equals(t.getName())) {
                return t;
            }
        }
        throw new AssertionError("no thread " + name);
    }
}
