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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Error paths, boundary values and concurrency of the engine: what happens when posting fails, when a return
 * crosses a limit only cumulatively, when inputs arrive faster than they are consumed, and at the edges of the
 * engine's lifecycle.
 */
class EngineEdgeTest {

    private static BigDecimal money(String s) {
        return new BigDecimal(s);
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for: " + what);
            }
            Thread.sleep(10);
        }
    }

    private static List<Recorder.Ev> ofKind(List<Recorder.Ev> events, Recorder.Kind kind) {
        return events.stream().filter(e -> e.kind == kind).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ error paths

    @Test
    void postingFailureKeepsTheLoanInDeposit() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.scan("YRD-001");
            expectRequested(h.key(KeyAction.DUE), LendingState.DEPOSIT);
            h.db.close();

            int mark = h.rec.size();
            Recorder.Ev ev = h.key(KeyAction.DEPOSIT, "5.00");
            expectPrompt(ev, Engine.PROMPT_POSTING_FAILED);
            assertEquals("Loan could not be recorded", ev.prompt);
            expectRequested(h.answer("OK"), LendingState.DEPOSIT);

            List<Recorder.Ev> since = h.rec.since(mark);
            assertTrue(ofKind(since, Recorder.Kind.LOAN_POSTED).isEmpty(), "nothing was posted");
            assertTrue(ofKind(since, Recorder.Kind.SLIP_PRINTED).isEmpty(), "nothing was printed");
            assertEquals(0, h.printer.count());
            assertNull(h.engine.lastLoan());
            Loan open = h.engine.currentLoan();
            assertNotNull(open, "the loan is still open");
            assertNull(open.id());
            assertTrue(open.deposits().isEmpty(), "the failed deposit is not kept on the loan");
            assertEquals(money("3.50"), open.total());
            assertTrue(h.engine.isRunning());

            expectRequested(h.key(KeyAction.CANCEL), LendingState.IDLE);
            assertNull(h.engine.currentLoan());
        }
    }

    @Test
    void failingSlipListenerDoesNotLoseTheLoan() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.printer.addListener(slip -> {
                throw new IllegalStateException("a failing slip listener is logged and ignored");
            });
            h.signOn("100", "1111");
            h.scan("YRD-001");
            h.key(KeyAction.DUE);
            int mark = h.rec.size();
            expectRequested(h.key(KeyAction.DEPOSIT), LendingState.IDLE);
            assertEquals(1, ofKind(h.rec.since(mark), Recorder.Kind.LOAN_POSTED).size());
            assertEquals(1, ofKind(h.rec.since(mark), Recorder.Kind.SLIP_PRINTED).size());
            assertEquals(1, h.query("SELECT * FROM loan").size());
        }
    }

    // ------------------------------------------------------------------ return boundaries

    @Test
    void returnLimitsApplyToTheWholeReturnNotToEachLine() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.key(KeyAction.RETURN);
            h.key(KeyAction.QTY, "14");
            expectRequested(h.scan("STK-001"), LendingState.RETURN);
            expectRequested(h.scan("HND-001"), LendingState.RETURN);
            assertEquals(money("20.00"), h.engine.currentLoan().returnValue(), "exactly the assistant's limit");
            assertTrue(h.engine.currentLoan().authorisations().isEmpty());

            expectPrompt(h.scan("HND-003"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            h.entry("200");
            expectRequested(h.entry("3333"), LendingState.RETURN);
            assertEquals(money("20.99"), h.engine.currentLoan().returnValue());
            assertEquals(3, h.engine.currentLoan().lines().size());

            expectRequested(h.scan("PWR-001"), LendingState.RETURN);
            assertEquals(1, h.engine.currentLoan().authorisations().size(), "55.98 is within the supervisor's 300");

            expectPrompt(h.scan("SND-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            h.entry("300");
            expectRequested(h.entry("4444"), LendingState.RETURN);
            assertEquals(money("444.98"), h.engine.currentLoan().returnValue());

            expectRequested(h.scan("PWR-001"), LendingState.RETURN);
            assertEquals(money("479.97"), h.engine.currentLoan().returnValue());
            expectPrompt(h.scan("PWR-001"), Engine.PROMPT_RETURN_NOT_ALLOWED);
            expectRequested(h.answer("OK"), LendingState.RETURN);
            assertEquals(money("479.97"), h.engine.currentLoan().returnValue(), "the refused line was not added");
            assertEquals("2", Deposits.formatQty(h.engine.currentLoan().lines().get(3).qty()));

            h.key(KeyAction.DUE);
            expectRequested(h.key(KeyAction.DEPOSIT), LendingState.IDLE);
            assertEquals(Arrays.asList(Arrays.<Object>asList("RETURN_OVERRIDE", "200"),
                    Arrays.<Object>asList("RETURN_OVERRIDE", "300")),
                h.query("SELECT kind, staff_code FROM loan_authorisation WHERE loan_id = 1001 ORDER BY staff_code"));
            assertEquals(Collections.singletonList(Arrays.<Object>asList("-479.97")),
                h.query("SELECT total FROM loan WHERE id = 1001"));
            assertEquals(Collections.singletonList(Collections.<Object>singletonList("-479.97")),
                h.query("SELECT amount FROM loan_deposit WHERE loan_id = 1001"));
            String text = h.printer.last().text();
            assertTrue(text.contains("RETURN_OVERRIDE AUTHORISED BY 200"), text);
            assertTrue(text.contains("RETURN_OVERRIDE AUTHORISED BY 300"), text);

            h.key(KeyAction.RETURN);
            expectPrompt(h.scan("PWR-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
        }
    }

    @Test
    void unknownOrInactiveSupervisorCannotAuthorise() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.key(KeyAction.RETURN);
            for (String[] attempt : new String[][] {{"999", "3333"}, {"102", "5555"}, {"100", "1111"},
                {"103", "6666"}}) {
                expectPrompt(h.scan("PWR-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
                h.answer("OK");
                h.entry(attempt[0]);
                expectPrompt(h.entry(attempt[1]), Engine.PROMPT_AUTHORISATION_FAILED);
                expectRequested(h.answer("OK"), LendingState.RETURN);
                assertNull(h.engine.currentLoan(), "supervisor " + attempt[0] + " must not authorise");
            }
            expectPrompt(h.scan("PWR-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            Recorder.Ev ev = h.key(KeyAction.CANCEL);
            expectRequested(ev, LendingState.IDLE);
            assertEquals("Scan or enter item", ev.prompt, "CANCEL leaves the supervisor sub-flow");
            assertFalse(h.engine.isReturnMode());
        }
    }

    // ------------------------------------------------------------------ inputs

    @Test
    void blankAndNullEntriesAreConsumedAndChangeNothing() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            expectRequested(h.entry(null), LendingState.SIGNED_OFF);
            expectRequested(h.entry("   "), LendingState.SIGNED_OFF);
            expectRequested(h.entry(" 100 "), LendingState.PASSWORD);
            expectRequested(h.entry(""), LendingState.PASSWORD);
            expectRequested(h.entry("1111"), LendingState.IDLE);
            expectRequested(h.entry(" "), LendingState.IDLE);
            assertNull(h.engine.currentLoan());
            expectRequested(h.barcode(null), LendingState.IDLE);
            expectRequested(h.key(KeyAction.QTY, "  "), LendingState.IDLE);
            assertEquals("Enter quantity", h.engine.prompt());
            expectRequested(h.entry(""), LendingState.IDLE);
            assertEquals("Enter quantity", h.engine.prompt(), "a blank quantity keeps asking");
            h.entry("2");
            h.scan("YRD-001");
            expectRequested(h.key(KeyAction.DUE), LendingState.DEPOSIT);
            expectRequested(h.entry(""), LendingState.DEPOSIT);
            assertEquals("Deposit received", h.engine.prompt());
            assertTrue(h.rec.events().stream().noneMatch(e -> e.kind == Recorder.Kind.PROMPT_SHOWN));
        }
    }

    @Test
    void unknownSourcesAreDropped() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            int mark = h.rec.size();
            for (String source : new String[] {"Key.BOGUS", "Key.", "key.qty", "Entry ", ""}) {
                LendingInput input = new LendingInput(source, "1");
                assertNull(input.keyAction(), source);
                assertFalse(h.engine.isValid(input), source);
                assertTrue(h.engine.inbox().offer(input));
            }
            assertFalse(h.engine.isValid(LendingInput.key(KeyAction.DUE)), "a known key that IDLE does not accept");
            assertTrue(h.engine.inbox().offer(LendingInput.key(KeyAction.DUE)));
            assertFalse(h.engine.isValid(null));
            assertFalse(h.engine.inbox().offer(null));
            expectRequested(h.scan("YRD-001"), LendingState.LOAN);
            assertEquals(1, ofKind(h.rec.since(mark), Recorder.Kind.ACCEPTED).size(), "only the scan was accepted");
        }
    }

    @Test
    void inputsOfferedWhileAPromptIsOpenAreProcessedInOrderAfterwards() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            expectPrompt(h.barcode("0000000000000"), Engine.PROMPT_UNKNOWN_ITEM);
            int mark = h.rec.size();
            LendingInput spade = LendingInput.barcode(h.catalogue.findByCode("YRD-001").barcode());
            assertTrue(h.engine.inbox().offer(spade));
            assertTrue(h.engine.inbox().offer(LendingInput.key(KeyAction.SIGN_OFF)));
            assertTrue(h.engine.inbox().offer(LendingInput.key(KeyAction.DUE)));
            assertEquals(3, h.engine.inbox().size(), "the engine takes nothing while the prompt is open");
            assertEquals(mark, h.rec.size());

            h.view.press("OK");
            h.rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED && e.state == LendingState.DEPOSIT);
            List<Recorder.Ev> accepted = ofKind(h.rec.since(mark), Recorder.Kind.ACCEPTED);
            assertEquals(2, accepted.size(), "SIGN_OFF is not valid with a loan open and was dropped");
            assertEquals(spade, accepted.get(0).input);
            assertEquals(LendingInput.key(KeyAction.DUE), accepted.get(1).input);
            assertEquals(money("3.50"), h.engine.currentLoan().total());
        }
    }

    @Test
    void aFullInboxRefusesInputsAndTheEngineRecovers() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            expectPrompt(h.barcode("0000000000000"), Engine.PROMPT_UNKNOWN_ITEM);
            int queued = 0;
            while (h.engine.inbox().offer(LendingInput.key(KeyAction.DUE))) {
                queued++;
                assertTrue(queued <= Inbox.CAPACITY, "the inbox is bounded");
            }
            assertEquals(Inbox.CAPACITY, queued);
            assertEquals(Inbox.CAPACITY, h.engine.inbox().size());

            int mark = h.rec.size();
            expectRequested(h.answer("OK"), LendingState.IDLE);
            eventually("the queued inputs are drained", () -> h.engine.inbox().size() == 0);
            assertTrue(ofKind(h.rec.since(mark), Recorder.Kind.ACCEPTED).isEmpty(), "DUE is not valid in IDLE");
            expectRequested(h.scan("YRD-001"), LendingState.LOAN);
        }
    }

    @Test
    void concurrentProducersLoseNoInput() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            int producers = 8;
            int perProducer = 60;
            LendingInput spade = LendingInput.barcode(h.catalogue.findByCode("YRD-001").barcode());
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger offered = new AtomicInteger();
            List<Thread> threads = new ArrayList<Thread>();
            for (int p = 0; p < producers; p++) {
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < perProducer; i++) {
                        if (h.engine.inbox().offer(spade)) {
                            offered.incrementAndGet();
                        }
                        // An input that is never valid, to exercise the drop path in between.
                        h.engine.inbox().offer(LendingInput.key(KeyAction.DEPOSIT));
                    }
                }, "producer-" + p);
                t.start();
                threads.add(t);
            }
            go.countDown();
            for (Thread t : threads) {
                t.join(Recorder.TIMEOUT_MS);
                assertFalse(t.isAlive(), "offer never blocks");
            }
            int expected = offered.get();
            assertTrue(expected > 0);
            eventually("every offered scan is on the loan", () -> {
                Loan loan = h.engine.currentLoan();
                return loan != null && loan.lines().get(0).qty().intValue() == expected
                    && h.engine.isAwaitingInput() && h.engine.inbox().size() == 0;
            });
            Loan loan = h.engine.currentLoan();
            assertEquals(1, loan.lines().size());
            assertEquals(Deposits.lineAmount(money("3.50"), BigDecimal.valueOf(expected)), loan.total());
            assertEquals(expected, ofKind(h.rec.events(), Recorder.Kind.ACCEPTED).stream()
                .filter(e -> spade.equals(e.input)).count());
            for (Recorder.Ev e : h.rec.events()) {
                assertEquals(Engine.THREAD_NAME, e.thread);
            }
        }
    }

    // ------------------------------------------------------------------ idle lock

    @Test
    void idleLockNeverAppliesBeforeSignOn() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A, 200)) {
            assertEquals(200, h.engine.idleLockMillis());
            // Several idle times long: SIGNED_OFF and PASSWORD never lock.
            long until = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < until) {
                assertEquals(LendingState.SIGNED_OFF, h.engine.state());
                Thread.sleep(25);
            }
            expectRequested(h.entry("100"), LendingState.PASSWORD);
            until = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < until) {
                assertEquals(LendingState.PASSWORD, h.engine.state());
                Thread.sleep(25);
            }
            assertTrue(h.rec.events().stream().noneMatch(e -> e.state == LendingState.LOCKED));
        }
    }

    @Test
    void aReturnSurvivesTheIdleLock() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A, 3000)) {
            int mark = h.rec.size();
            h.signOn("100", "1111");
            h.key(KeyAction.RETURN);
            h.scan("HND-001");
            Recorder.Ev locked = h.rec.await(mark,
                e -> e.kind == Recorder.Kind.STATE && e.state == LendingState.LOCKED);
            assertEquals(LendingState.RETURN, locked.from);
            h.rec.await(locked.index, e -> e.kind == Recorder.Kind.REQUESTED);
            assertTrue(h.engine.isLocked());
            assertTrue(h.engine.isReturnMode(), "still a return while locked");
            assertFalse(h.engine.isValid(LendingInput.barcode("5000000000048")));

            expectRequested(h.entry("1111"), LendingState.RETURN);
            assertFalse(h.engine.isLocked());
            assertTrue(h.engine.isReturnMode());
            assertEquals(money("-1.10"), h.engine.currentLoan().total());
            h.key(KeyAction.DUE);
            expectRequested(h.key(KeyAction.DEPOSIT), LendingState.IDLE);
            assertEquals(Collections.singletonList(Arrays.<Object>asList("-1.10")),
                h.query("SELECT total FROM loan"));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void lifecycleEdges() throws Exception {
        LendingDb db = LendingDb.create("engine_lifecycle", Seed.A, LocalDate.now(EngineHarness.CLOCK));
        try {
            AccessControl access = AccessControl.load(db.connection());
            Catalogue catalogue = Catalogue.load(db.connection());
            WaiverEngine waiverRules = WaiverEngine.load(db.connection());
            db.connection().commit();
            PromptService prompts = new PromptService();
            SlipPrinter printer = new SlipPrinter(null);
            assertThrows(IllegalArgumentException.class, () -> new Engine(db, catalogue, access, waiverRules, prompts,
                printer, EngineHarness.CLOCK, -1));
            Engine engine = new Engine(db, catalogue, access, waiverRules, prompts, printer, EngineHarness.CLOCK, 0);

            assertFalse(engine.isRunning());
            assertFalse(engine.status().running());
            assertFalse(engine.isAwaitingInput());
            assertTrue(engine.validInputs().isEmpty(), "nothing is accepted before the engine runs");
            assertNull(engine.thread());
            assertEquals(LendingState.SIGNED_OFF, engine.state());

            Recorder rec = new Recorder();
            engine.addListener(rec);
            engine.addListener(null);
            assertEquals(1, engine.listenerCount());
            engine.start();
            assertThrows(IllegalStateException.class, engine::start);
            rec.await(0, e -> e.kind == Recorder.Kind.REQUESTED);
            assertTrue(engine.isRunning());
            assertFalse(engine.thread().isDaemon(), "the engine thread keeps a window-less desk alive");

            long begin = System.nanoTime();
            engine.stop(0);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < Recorder.TIMEOUT_MS,
                "stop(0) does not wait");
            assertTrue(engine.stop(Recorder.TIMEOUT_MS));
            engine.stop();
            assertTrue(engine.stop(1), "stopping again is harmless");
            assertFalse(engine.isRunning());
            assertFalse(engine.status().running());
            assertTrue(engine.validInputs().isEmpty());
            assertThrows(IllegalStateException.class, engine::start, "a stopped engine is not restarted");
            assertTrue(engine.inbox().offer(LendingInput.entry("100")), "offering to a stopped engine does not block");
            engine.removeListener(rec);
            assertEquals(0, engine.listenerCount());
        } finally {
            db.close();
        }
    }

    @Test
    void optionsSaturateInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, LendingOptions.defaults().idleLockSeconds(Long.MAX_VALUE).idleLockMillis());
        assertEquals(5000, LendingOptions.defaults().idleLockSeconds(5).idleLockMillis());
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.defaults().idleLockSeconds(-1));
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.defaults().idleLockSeconds(Long.MIN_VALUE));
    }
}
