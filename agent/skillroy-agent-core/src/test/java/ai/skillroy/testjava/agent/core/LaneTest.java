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

import ai.skillroy.testjava.agent.api.Lane;
import ai.skillroy.testjava.agent.api.ThreadBroker;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.withMeta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Named lanes (phase8-contracts.md 4.2, 13.1 row 7): declaration, the bounds, retirement, the dispatcher. */
class LaneTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;
    private final List<Lane> declared = new ArrayList<Lane>();

    private CoreThreadBroker start() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, "coretest.appThread", "true");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        return (CoreThreadBroker) agent.runtime.context().threads();
    }

    @AfterEach
    void stop() {
        TestState.laneRelease.countDown();
        for (Lane lane : declared) {
            lane.close();
        }
        if (agent != null) {
            agent.close();
        }
    }

    private Lane declare(ThreadBroker broker, String name, int threads, int queue) {
        Lane lane = broker.declareLane(name, threads, queue);
        declared.add(lane);
        return lane;
    }

    @Test
    void declarationsKeepTheBoundsAndTheGrammar() throws Exception {
        CoreThreadBroker broker = start();
        for (String bad : Arrays.asList(null, "", "lending", "Lending.reads", "lending.", ".reads", "1x.reads",
                "lending.re ads", "lending..reads", "lending.reads.", repeat('a', 60) + ".reads")) {
            assertThrows(IllegalArgumentException.class, () -> broker.declareLane(bad, 1, 0), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> broker.declareLane("t.zero", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> broker.declareLane("t.five", 5, 0));
        assertThrows(IllegalArgumentException.class, () -> broker.declareLane("t.minus", 1, -1));
        assertThrows(IllegalArgumentException.class, () -> broker.declareLane("t.big", 1, 17));
        Lane lane = declare(broker, "t.ok-1.a", 4, 16);
        assertEquals("t.ok-1.a", lane.name());
        assertEquals(4, lane.threads());
        assertEquals(16, lane.queue());
        assertThrows(IllegalStateException.class, () -> broker.declareLane("t.ok-1.a", 1, 0), "a duplicate");
        // The fixture holds two lanes; with this one, five more fill the agent's eight.
        for (int i = 0; i < 5; i++) {
            declare(broker, "t.more" + i, 1, 0);
        }
        assertThrows(IllegalStateException.class, () -> broker.declareLane("t.ninth", 1, 0));
        lane.close();
        lane.close();   // idempotent
        declare(broker, "t.ok-1.a", 1, 0);   // the name is free again, and so is the slot
    }

    @Test
    void lanesRunTasksInOrderOnTheirOwnNamedThreads() throws Exception {
        CoreThreadBroker broker = start();
        declare(broker, "t.fifo", 1, 4);
        assertTrue(broker.callOnLane("t.fifo", threadName(), 5000).startsWith("skillroy-lane-t.fifo-"));
        final CountDownLatch hold = new CountDownLatch(1);
        final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        Thread first = background(() -> broker.callOnLane("t.fifo", () -> {
            hold.await(10, TimeUnit.SECONDS);
            order.add(0);
            return null;
        }, 10000));
        AgentHarness.await("the first runs", 10000, () -> active(broker, "t.fifo") == 1);
        List<Thread> waiting = new ArrayList<Thread>();
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            waiting.add(background(() -> broker.callOnLane("t.fifo", () -> {
                order.add(n);
                return null;
            }, 10000)));
            AgentHarness.await("queued " + n, 10000, () -> queued(broker, "t.fifo") == n);
        }
        hold.countDown();
        first.join(10000);
        for (Thread t : waiting) {
            t.join(10000);
        }
        assertEquals(Arrays.asList(0, 1, 2, 3), order);
    }

    @Test
    void aFullLaneIsBusyAndAQueuedTaskNeverRunsAfterItsCallerGaveUp() throws Exception {
        CoreThreadBroker broker = start();
        declare(broker, "t.small", 1, 1);
        final CountDownLatch hold = new CountDownLatch(1);
        Thread first = background(() -> broker.callOnLane("t.small", () -> hold.await(10, TimeUnit.SECONDS), 10000));
        AgentHarness.await("the first runs", 10000, () -> active(broker, "t.small") == 1);
        final AtomicInteger ranLate = new AtomicInteger();
        final AtomicReference<Object> second = new AtomicReference<Object>();
        // Review fix L3-F4: the queued caller has 3 s, so the BUSY probe below has seconds to be sent while it is
        // still queued; its TIMEOUT is awaited before the lane's first task is released.
        Thread queued = background(() -> {
            try {
                second.set(broker.callOnLane("t.small", () -> ranLate.incrementAndGet(), 3000));
            } catch (Exception e) {
                second.set(e);
            }
            return null;
        });
        AgentHarness.await("the second is queued", 10000, () -> queued(broker, "t.small") == 1);
        RpcException full = assertThrows(RpcException.class, () -> broker.callOnLane("t.small", threadName(), 1000));
        assertEquals(ErrorCodes.BUSY, full.error().code());
        assertEquals("lane full", full.error().data().get("reason"));
        assertEquals("t.small", full.error().data().get("lane"));
        assertEquals(1L, full.error().data().get("threads"));
        assertEquals(1L, full.error().data().get("queue"));
        queued.join(10000);
        assertTrue(second.get() instanceof RpcException, String.valueOf(second.get()));
        assertEquals(ErrorCodes.TIMEOUT, ((RpcException) second.get()).error().code());
        hold.countDown();
        first.join(10000);
        // The lane is one thread in front of a FIFO queue: a withdrawn task left there would run before this one.
        assertEquals("ok", broker.callOnLane("t.small", () -> "ok", 5000));
        assertEquals(0, ranLate.get(), "a task withdrawn at its caller's deadline never runs");
    }

    @Test
    void aStuckLaneCountsItsAbandonedTaskAndAnswersBusyAtOnce() throws Exception {
        CoreThreadBroker broker = start();
        declare(broker, "t.stuck", 1, 2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger interrupts = new AtomicInteger();
        RpcException timeout = assertThrows(RpcException.class, () -> broker.callOnLane("t.stuck", () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() - deadline < 0) {
                try {
                    if (release.await(50, TimeUnit.MILLISECONDS)) {
                        return "released";
                    }
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();   // like host code on a monitor
                }
            }
            return "gave up";
        }, 300));
        assertEquals(ErrorCodes.TIMEOUT, timeout.error().code());
        AgentHarness.await("the running task was interrupted, best effort", 5000, () -> interrupts.get() == 1);
        assertEquals(1L, laneJson(broker, "t.stuck").get("abandoned"));
        long start = System.nanoTime();
        RpcException stuck = assertThrows(RpcException.class, () -> broker.callOnLane("t.stuck", threadName(), 5000));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "at once, before queueing");
        assertEquals(ErrorCodes.BUSY, stuck.error().code());
        assertEquals("lane stuck", stuck.error().data().get("reason"));
        assertTrue(((Number) stuck.error().data().get("runningForMs")).longValue() >= 0);
        // Another policy answers meanwhile.
        assertEquals("coretest-app", broker.call(ThreadPolicy.APP, threadName(), 5000));
        release.countDown();
        AgentHarness.await("the abandoned task ended", 10000,
                () -> Long.valueOf(0).equals(laneJson(broker, "t.stuck").get("abandoned")));
        assertTrue(broker.callOnLane("t.stuck", threadName(), 5000).startsWith("skillroy-lane-t.stuck-"));
        assertTrue(String.valueOf(agent.runtime.context().log().tail(200)).contains(
                "the abandoned task on lane t.stuck ended after"));
    }

    @Test
    void closingTheHandleRefusesQueuedTasksAndLaterCalls() throws Exception {
        CoreThreadBroker broker = start();
        Lane lane = declare(broker, "t.retire", 1, 2);
        final CountDownLatch hold = new CountDownLatch(1);
        background(() -> broker.callOnLane("t.retire", () -> hold.await(10, TimeUnit.SECONDS), 10000));
        AgentHarness.await("the first runs", 10000, () -> active(broker, "t.retire") == 1);
        final AtomicReference<Object> queuedOutcome = new AtomicReference<Object>();
        Thread queued = background(() -> {
            try {
                queuedOutcome.set(broker.callOnLane("t.retire", threadName(), 10000));
            } catch (Exception e) {
                queuedOutcome.set(e);
            }
            return null;
        });
        AgentHarness.await("queued", 10000, () -> queued(broker, "t.retire") == 1);
        lane.close();
        queued.join(10000);
        RpcException closed = (RpcException) queuedOutcome.get();
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, closed.error().code());
        assertEquals("lane closed", closed.error().data().get("reason"));
        RpcException later = assertThrows(RpcException.class, () -> broker.callOnLane("t.retire", threadName(), 1000));
        assertEquals("lane closed", later.error().data().get("reason"));
        RpcException unknown = assertThrows(RpcException.class, () -> broker.callOnLane("t.never", threadName(),
                1000));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, unknown.error().code());
        assertEquals("unknown lane", unknown.error().data().get("reason"));
        assertEquals("t.never", unknown.error().data().get("lane"));
        assertFalse(broker.lanesJson().containsKey("t.retire"));
        hold.countDown();
    }

    @Test
    void aModulesLanesAreRetiredOnRollbackAndReportTheirOwner() throws Exception {
        CoreThreadBroker broker = start();
        ModuleContext module = new ModuleContext((CoreContext) agent.runtime.context(), "lanetest");
        Lane lane = module.threads().declareLane("lanetest.reads", 1, 2);
        Map<String, Object> json = laneJson(broker, "lanetest.reads");
        assertEquals("lanetest", json.get("owner"));
        assertEquals(1L, json.get("threads"));
        assertEquals(2L, json.get("queue"));
        assertEquals(0L, json.get("active"));
        assertEquals(0L, json.get("queued"));
        assertEquals(0L, json.get("abandoned"));
        assertEquals("x", module.threads().callOnLane("lanetest.reads", () -> "x", 5000));
        module.rollback();
        assertFalse(broker.lanesJson().containsKey("lanetest.reads"));
        RpcException closed = assertThrows(RpcException.class, () -> broker.callOnLane("lanetest.reads",
                threadName(), 1000));
        assertEquals("lane closed", closed.error().data().get("reason"));
        lane.close();   // idempotent with the rollback
        // The fixture's own lanes, declared through its module context.
        assertEquals("coretest", laneJson(broker, "coretest.where").get("owner"));
        assertEquals("coretest", laneJson(broker, CoreTestModule.SLOW_LANE).get("owner"));
    }

    @Test
    void closingTheAgentRetiresEveryLaneAndLeavesNoLaneThread() throws Exception {
        CoreThreadBroker broker = start();
        for (int i = 0; i < 3; i++) {
            agent.result("test.where.lane", null);
        }
        declare(broker, "t.close", 2, 0);
        broker.callOnLane("t.close", threadName(), 5000);
        assertFalse(laneThreads().isEmpty());
        agent.close();
        AgentHarness.await("no lane thread left", 10000, () -> laneThreads().isEmpty());
        assertThrows(IllegalStateException.class, () -> broker.declareLane("t.after", 1, 0),
                "no declaration once the agent closes");
    }

    @Test
    void laneAndQuiescentPrimitivesGoThroughTheDispatcherWithTheAbandonedCallGate() throws Exception {
        CoreThreadBroker broker = start();
        RpcResult where = RpcResult.fromJson(agent.result("test.where.lane", withMeta(null, "correlationId", "l-1")));
        assertTrue(where.thread().startsWith("skillroy-lane-coretest.where-"), where.thread());
        assertEquals("l-1", where.events().get(0).correlationId(), "the call's scope travels with the task");

        Map<String, Object> none = agent.error("test.lane.none", null);
        assertEquals((long) ErrorCodes.CAPABILITY_UNAVAILABLE, code(none));
        assertEquals("unknown lane", data(none).get("reason"));
        assertEquals(0, TestState.LANE_RUNS.get());

        Map<String, Object> timeout = agent.error("test.lane.stuck", withMeta(null, "timeoutMs", Long.valueOf(300)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(timeout));
        assertEquals("test.lane.stuck", data(timeout).get("primitive"));
        Map<String, Object> busy = agent.error("test.lane.stuck", null);
        assertEquals((long) ErrorCodes.BUSY, code(busy), "S30: the primitive's abandoned call still runs");
        assertTrue(((String) data(busy).get("thread")).startsWith("skillroy-lane-" + CoreTestModule.SLOW_LANE));
        // The lane itself is stuck too, and the application lane answers.
        RpcException stuck = assertThrows(RpcException.class, () -> broker.callOnLane(CoreTestModule.SLOW_LANE,
                threadName(), 1000));
        assertEquals("lane stuck", stuck.error().data().get("reason"));
        assertEquals("coretest-app", agent.value("test.where.app", null));
        @SuppressWarnings("unchecked")
        Map<String, Object> lanes = (Map<String, Object>) ((Map<String, Object>) agent.valueMap("agent.info", null)
                .get("threads")).get("lanes");
        assertEquals(1L, ((Map<?, ?>) lanes.get(CoreTestModule.SLOW_LANE)).get("abandoned"));
        TestState.laneRelease.countDown();
        AgentHarness.await("the stuck call ended", 10000, () -> ((Map<?, ?>) ((Map<?, ?>) agent.valueMap(
                "agent.info", null).get("threads")).get("abandoned")).isEmpty());
        assertTrue(((String) agent.value("test.lane.stuck", null)).startsWith("skillroy-lane-"));
        assertEquals(2, TestState.LANE_RUNS.get());

        final AtomicInteger ran = new AtomicInteger();
        broker.post(ThreadPolicy.LANE, () -> ran.incrementAndGet());
        Thread.sleep(200);
        assertEquals(0, ran.get(), "post(LANE) is refused");
    }

    @Test
    void aForbiddenCallerMayNotWaitOnALane() throws Exception {
        CoreThreadBroker broker = start();
        final AtomicReference<Object> outcome = new AtomicReference<Object>();
        Thread forbidden = new Thread(() -> {
            try {
                outcome.set(broker.callOnLane("coretest.where", threadName(), 1000));
            } catch (Exception e) {
                outcome.set(e);
            }
        }, "coretest-forbidden-lane-caller");
        forbidden.start();
        forbidden.join(10000);
        assertTrue(outcome.get() instanceof RpcException, String.valueOf(outcome.get()));
        assertEquals(ErrorCodes.FORBIDDEN_THREAD, ((RpcException) outcome.get()).error().code());
        assertThrows(IllegalArgumentException.class, () -> broker.callOnLane("coretest.where", threadName(), 0));
    }

    private static long active(CoreThreadBroker broker, String lane) {
        return ((Number) laneJson(broker, lane).get("active")).longValue();
    }

    private static long queued(CoreThreadBroker broker, String lane) {
        return ((Number) laneJson(broker, lane).get("queued")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> laneJson(CoreThreadBroker broker, String lane) {
        Map<String, Object> json = (Map<String, Object>) broker.lanesJson().get(lane);
        if (json == null) {
            throw new AssertionError("no lane " + lane + " in " + broker.lanesJson());
        }
        return json;
    }

    private static List<String> laneThreads() {
        List<String> names = new ArrayList<String>();
        for (Thread t : CorePrimitives.allThreads()) {
            if (t.getName().startsWith("skillroy-lane-") && t.isAlive()) {
                names.add(t.getName());
            }
        }
        return names;
    }

    private static Thread background(final Callable<?> body) {
        Thread t = new Thread(() -> {
            try {
                body.call();
            } catch (Exception ignored) {
                // The test asserts the outcome where it matters.
            }
        }, "skillroy-test-lane-caller");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Callable<String> threadName() {
        return () -> Thread.currentThread().getName();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
