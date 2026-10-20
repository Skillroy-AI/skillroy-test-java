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

package ai.skillroy.testjava.samples.lending;

import static ai.skillroy.testjava.samples.lending.EngineHarness.expectPrompt;
import static ai.skillroy.testjava.samples.lending.EngineHarness.expectRequested;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The engine's idle callback and its plant (phase8-contracts.md 12.1, 13.1 row 12; D535): {@code idle} right after
 * {@code inputRequested} and per tick, never while an input is processed or a prompt is open; {@code availableMs}
 * counting down to the idle lock; {@link Engine#idleView()} only inside the callback on the engine thread.
 */
class EngineIdleTest {

    /** Records every idle call and every violation of the rules above, and the view each idle call reads. */
    static final class IdleRecorder implements EngineListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final List<Long> available = Collections.synchronizedList(new ArrayList<Long>());
        final List<String> violations = Collections.synchronizedList(new ArrayList<String>());
        final List<IdleView> views = Collections.synchronizedList(new ArrayList<IdleView>());
        final List<Throwable> outsideIdle = Collections.synchronizedList(new ArrayList<Throwable>());
        volatile Engine engine;
        private boolean processing;
        private boolean promptOpen;

        @Override
        public void idle(LendingState state, long availableMs) {
            if (!Engine.THREAD_NAME.equals(Thread.currentThread().getName())) {
                violations.add("idle on " + Thread.currentThread().getName());
            }
            if (processing) {
                violations.add("idle while an input is processed");
            }
            if (promptOpen) {
                violations.add("idle while a prompt is open");
            }
            events.add("idle " + state);
            available.add(availableMs);
            if (engine != null) {
                views.add(engine.idleView());
            }
        }

        @Override
        public void inputRequested(LendingState state, String prompt) {
            processing = false;
            events.add("requested " + state);
            if (engine != null) {
                try {
                    engine.idleView();
                    violations.add("idleView answered outside an idle callback");
                } catch (IllegalStateException expected) {
                    outsideIdle.add(expected);
                }
            }
        }

        @Override
        public void inputAccepted(LendingInput input) {
            processing = true;
        }

        @Override
        public void promptShown(String id, String text, List<String> buttons) {
            promptOpen = true;
            events.add("prompt " + id);
        }

        @Override
        public void promptAnswered(String id, String choice) {
            promptOpen = false;
        }

        int idleCount() {
            int n = 0;
            synchronized (events) {
                for (String e : events) {
                    n += e.startsWith("idle ") ? 1 : 0;
                }
            }
            return n;
        }

        IdleView lastView() {
            synchronized (views) {
                return views.isEmpty() ? null : views.get(views.size() - 1);
            }
        }
    }

    private static void awaitIdleCalls(IdleRecorder idle, int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (idle.idleCount() < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(idle.idleCount() >= atLeast, "idle calls: " + idle.events);
    }

    @Test
    void idleFollowsInputRequestedAndThenEveryTickButNeverDuringAnInputOrAPrompt() throws Exception {
        IdleRecorder idle = new IdleRecorder();
        try (EngineHarness h = EngineHarness.start(Seed.A, 0, 20, idle)) {
            idle.engine = h.engine;
            awaitIdleCalls(idle, 5);
            assertEquals("requested SIGNED_OFF", idle.events.get(0));
            assertEquals("idle SIGNED_OFF", idle.events.get(1), "right after inputRequested");
            h.signOn("100", "1111");
            int before = idle.idleCount();
            expectPrompt(h.entry("NO-SUCH-ITEM"), Engine.PROMPT_UNKNOWN_ITEM);
            Thread.sleep(150);
            expectRequested(h.answer(Engine.OK), LendingState.IDLE);
            awaitIdleCalls(idle, before + 3);
            assertEquals(Collections.<String>emptyList(), idle.violations);
            assertTrue(idle.events.indexOf("idle IDLE") > idle.events.indexOf("requested IDLE"));
            assertTrue(idle.outsideIdle.size() >= 3, "idleView refused inside inputRequested");
        }
    }

    @Test
    void withATickOfZeroIdleIsCalledOnceAfterEachInputRequested() throws Exception {
        IdleRecorder idle = new IdleRecorder();
        try (EngineHarness h = EngineHarness.start(Seed.A, 0, 0, idle)) {
            awaitIdleCalls(idle, 1);
            Thread.sleep(200);
            assertEquals(1, idle.idleCount(), idle.events.toString());
            h.signOn("100", "1111");
            Thread.sleep(100);
            assertEquals(3, idle.idleCount(), "one per wait: " + idle.events);
            assertEquals(Collections.<String>emptyList(), idle.violations);
        }
    }

    @Test
    void availableMsCountsDownToTheIdleLockAndIsUnboundedWithoutOne() throws Exception {
        IdleRecorder idle = new IdleRecorder();
        try (EngineHarness h = EngineHarness.start(Seed.A, 3000, 50, idle)) {
            idle.engine = h.engine;
            awaitIdleCalls(idle, 2);
            assertEquals(Long.MAX_VALUE, (long) idle.available.get(0), "SIGNED_OFF: no idle lock applies");
            assertNull(idle.lastView().idleLockRemainingMs());
            h.signOn("100", "1111");
            int mark = idle.available.size();
            Thread.sleep(400);
            List<Long> idleInIdle = new ArrayList<Long>(idle.available.subList(mark, idle.available.size()));
            assertTrue(idleInIdle.size() >= 4, idleInIdle.toString());
            for (int i = 0; i < idleInIdle.size(); i++) {
                assertTrue(idleInIdle.get(i) <= 3000 && idleInIdle.get(i) > 2000, idleInIdle.toString());
                if (i > 0) {
                    assertTrue(idleInIdle.get(i) <= idleInIdle.get(i - 1), "counts down: " + idleInIdle);
                }
            }
            Long remaining = idle.lastView().idleLockRemainingMs();
            assertTrue(remaining != null && remaining <= 3000, String.valueOf(remaining));
        }
    }

    @Test
    void idleViewReadsTheEngineConfinedStateOnlyAtTheIdlePoint() throws Exception {
        IdleRecorder idle = new IdleRecorder();
        try (EngineHarness h = EngineHarness.start(Seed.A, 0, 20, idle)) {
            idle.engine = h.engine;
            assertThrows(IllegalStateException.class, h.engine::idleView, "not on the engine thread");
            h.signOn("100", "1111");
            h.key(KeyAction.QTY);
            int mark = idle.views.size();
            awaitIdleCalls(idle, idle.idleCount() + 2);
            IdleView quantity = idle.views.get(idle.views.size() - 1);
            assertTrue(idle.views.size() > mark);
            assertEquals("QUANTITY", quantity.step(), "the quantity sub-step, in no published snapshot");
            assertNull(quantity.pendingQty());
            h.entry("3");
            awaitIdleCalls(idle, idle.idleCount() + 2);
            IdleView view = idle.lastView();
            assertEquals(LendingState.IDLE, view.state());
            assertEquals("NONE", view.step());
            assertEquals(new BigDecimal("3"), view.pendingQty());
            assertEquals(0, view.lineCount());
            assertNull(view.lockedFrom());
            h.scan("YRD-001");
            awaitIdleCalls(idle, idle.idleCount() + 2);
            assertEquals(1, idle.lastView().lineCount());
            assertEquals(LendingState.LOAN, idle.lastView().state());
            assertNull(idle.lastView().pendingQty(), "used by the item");
            assertEquals(Collections.<String>emptyList(), idle.violations);
        }
    }

    @Test
    void theIdleTickOptionIsReadAndChecked() {
        java.util.Properties props = new java.util.Properties();
        assertEquals(Engine.DEFAULT_IDLE_TICK_MILLIS, LendingOptions.fromProperties(props).idleTickMillis());
        props.setProperty(LendingOptions.PROP_IDLE_TICK, "0");
        assertEquals(0L, LendingOptions.fromProperties(props).idleTickMillis());
        props.setProperty(LendingOptions.PROP_IDLE_TICK, " 250 ");
        assertEquals(250L, LendingOptions.fromProperties(props).idleTickMillis());
        props.setProperty(LendingOptions.PROP_IDLE_TICK, "soon");
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.fromProperties(props));
        props.setProperty(LendingOptions.PROP_IDLE_TICK, "-1");
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.fromProperties(props));
        assertEquals("lending.idleTickMillis", LendingOptions.PROP_IDLE_TICK);
    }
}
