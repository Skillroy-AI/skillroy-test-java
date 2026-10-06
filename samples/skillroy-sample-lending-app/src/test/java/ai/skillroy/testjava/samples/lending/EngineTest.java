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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Headless engine tests: inputs go through the inbox, prompts are answered through a test {@link PromptView},
 * results are checked through listener events, the engine's snapshots and the database.
 */
class EngineTest {

    private static final String ENTRY = LendingInput.ENTRY;
    private static final String BARCODE = LendingInput.BARCODE;

    private static BigDecimal money(String s) {
        return new BigDecimal(s);
    }

    // ------------------------------------------------------------------ sign-on

    @Test
    void signOnSucceedsWithCodeAndPassword() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            EngineStatus st = h.engine.status();
            assertEquals(LendingState.SIGNED_OFF, st.state());
            assertEquals("Enter staff code", st.prompt());
            assertEquals(Collections.singletonList(ENTRY), st.validInputs());
            assertTrue(st.awaitingInput());
            assertTrue(st.running());

            Recorder.Ev ev = h.entry("100");
            expectRequested(ev, LendingState.PASSWORD);
            assertEquals("Enter password", ev.prompt);
            assertTrue(h.engine.status().secretEntry());
            assertEquals(Arrays.asList(ENTRY, "Key.SIGN_OFF"), h.engine.validInputs());

            ev = h.entry("1111");
            expectRequested(ev, LendingState.IDLE);
            assertEquals("Scan or enter item", ev.prompt);
            assertEquals("100", h.engine.currentStaff().code());
            assertEquals("Alice Archer", h.engine.currentStaff().name());
            assertEquals(Arrays.asList(BARCODE, ENTRY, "Key.RETURN", "Key.SIGN_OFF", "Key.QTY"),
                h.engine.validInputs());
            assertFalse(h.engine.status().secretEntry());

            List<Recorder.Ev> events = h.rec.events();
            long transitions = events.stream().filter(e -> e.kind == Recorder.Kind.STATE).count();
            assertEquals(2, transitions);
            for (Recorder.Ev e : events) {
                assertEquals(Engine.THREAD_NAME, e.thread, "callbacks run on the engine thread");
            }
        }
    }

    @Test
    void unknownStaffCodeShowsPromptAndStaysSignedOff() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            Recorder.Ev ev = h.entry("999");
            expectPrompt(ev, Engine.PROMPT_UNKNOWN_STAFF);
            assertEquals("Unknown staff code", ev.prompt);
            assertEquals(Collections.singletonList("OK"), ev.buttons);
            assertFalse(h.engine.isAwaitingInput(), "blocked in the prompt");
            assertTrue(h.engine.validInputs().isEmpty(), "nothing accepted while a prompt is open");
            assertEquals(Engine.PROMPT_UNKNOWN_STAFF, h.prompts.current().id());

            expectRequested(h.answer("OK"), LendingState.SIGNED_OFF);
            assertNull(h.prompts.current());
            assertNull(h.engine.currentStaff());
            assertEquals(1, h.view.hidden().size());
        }
    }

    @Test
    void wrongPasswordShowsPromptAndReturnsToSignedOff() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            expectRequested(h.entry("100"), LendingState.PASSWORD);
            Recorder.Ev ev = h.entry("0000");
            expectPrompt(ev, Engine.PROMPT_WRONG_PASSWORD);
            assertEquals("Wrong password", ev.prompt);
            expectRequested(h.answer("OK"), LendingState.SIGNED_OFF);
            assertNull(h.engine.currentStaff());
        }
    }

    @Test
    void inactiveStaffAndStaffWithoutSignOnPermissionAreRefused() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            expectPrompt(h.entry("102"), Engine.PROMPT_STAFF_INACTIVE);
            expectRequested(h.answer("OK"), LendingState.SIGNED_OFF);
            expectRequested(h.entry("104"), LendingState.PASSWORD);
            expectPrompt(h.entry("7777"), Engine.PROMPT_SIGN_ON_NOT_ALLOWED);
            expectRequested(h.answer("OK"), LendingState.SIGNED_OFF);
        }
    }

    @Test
    void autoSignOnStaffSkipsThePassword() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.B)) {
            expectRequested(h.entry("4714"), LendingState.IDLE);
            assertEquals("4714", h.engine.currentStaff().code());
        }
    }

    @Test
    void signOnOnSeedBUsesSeedBStaff() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.B)) {
            expectPrompt(h.entry("100"), Engine.PROMPT_UNKNOWN_STAFF);
            expectRequested(h.answer("OK"), LendingState.SIGNED_OFF);
            h.signOn("4711", "9182");
            assertEquals("Hana Hill", h.engine.currentStaff().name());
        }
    }

    @Test
    void signOffReturnsToSignedOff() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            expectRequested(h.key(KeyAction.SIGN_OFF), LendingState.SIGNED_OFF);
            assertNull(h.engine.currentStaff());
            assertEquals("Enter staff code", h.engine.prompt());

            h.signOn("100", "1111");
            expectRequested(h.scan("YRD-001"), LendingState.LOAN);
            assertFalse(h.engine.isValid(LendingInput.key(KeyAction.SIGN_OFF)), "no sign-off with a loan open");
            expectRequested(h.key(KeyAction.CANCEL), LendingState.IDLE);
            expectRequested(h.key(KeyAction.SIGN_OFF), LendingState.SIGNED_OFF);
            assertEquals(0, h.query("SELECT * FROM loan").size(), "a cancelled loan is not posted");
        }
    }

    // ------------------------------------------------------------------ input handling

    @Test
    void invalidInputIsSilentlyDropped() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            assertFalse(h.engine.isValid(LendingInput.key(KeyAction.DUE)));
            int mark = h.rec.size();
            assertTrue(h.engine.inbox().offer(LendingInput.key(KeyAction.DUE)));
            assertTrue(h.engine.inbox().offer(LendingInput.barcode("5000000000017")));
            assertTrue(h.engine.inbox().offer(LendingInput.key(KeyAction.DEPOSIT, "5.00")));
            assertTrue(h.engine.inbox().offer(LendingInput.entry("100")));
            Recorder.Ev accepted = h.rec.await(mark, e -> e.kind == Recorder.Kind.ACCEPTED);
            assertEquals(LendingInput.entry("100"), accepted.input, "the dropped inputs were never accepted");
            for (Recorder.Ev e : h.rec.since(mark)) {
                if (e.index >= accepted.index) {
                    break;
                }
                throw new AssertionError("no event may precede the accepted input: " + e);
            }
            expectRequested(h.rec.await(accepted.index, e -> e.kind == Recorder.Kind.REQUESTED),
                LendingState.PASSWORD);
            assertEquals(0, h.engine.inbox().size());
        }
    }

    @Test
    void basicLoanWithQuantity() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            Recorder.Ev ev = h.key(KeyAction.QTY);
            expectRequested(ev, LendingState.IDLE);
            assertEquals("Enter quantity", ev.prompt);
            assertEquals(Arrays.asList(ENTRY, "Key.CANCEL"), h.engine.validInputs());
            ev = h.entry("3");
            assertEquals("Qty 3 - Scan or enter item", ev.prompt);

            expectRequested(h.scan("YRD-001"), LendingState.LOAN);
            Loan loan = h.engine.currentLoan();
            assertEquals(1, loan.lines().size());
            assertEquals(0, loan.lines().get(0).qty().compareTo(money("3")));
            assertEquals(money("10.50"), loan.lines().get(0).amount());
            assertNull(loan.id(), "an open loan has no id");

            h.scan("YRD-001");
            ev = h.entry("stk-001");
            assertEquals("Scan or enter item, or DUE", ev.prompt);
            loan = h.engine.currentLoan();
            assertEquals(2, loan.lines().size(), "the second YRD-001 scan added to its line");
            assertEquals("4", Deposits.formatQty(loan.lines().get(0).qty()));
            assertEquals(money("14.00"), loan.lines().get(0).amount());
            assertEquals("STK-001", loan.lines().get(1).code());
            assertEquals(2, loan.lines().get(1).lineNo());
            assertEquals(Arrays.asList(BARCODE, ENTRY, "Key.DUE", "Key.CANCEL", "Key.QTY"), h.engine.validInputs());

            ev = h.key(KeyAction.DUE);
            expectRequested(ev, LendingState.DEPOSIT);
            assertEquals("Deposit received", ev.prompt);
            assertEquals(Arrays.asList(ENTRY, "Key.DEPOSIT", "Key.CANCEL"), h.engine.validInputs());
            assertTrue(h.engine.currentLoan().waivers().isEmpty());
            assertEquals(money("15.35"), h.engine.currentLoan().total());

            int mark = h.rec.size();
            expectRequested(h.key(KeyAction.DEPOSIT, "20"), LendingState.IDLE);
            Recorder.Ev posted = h.rec.await(mark, e -> e.kind == Recorder.Kind.LOAN_POSTED);
            Recorder.Ev printed = h.rec.await(mark, e -> e.kind == Recorder.Kind.SLIP_PRINTED);
            assertEquals(1001L, posted.loanId);
            assertTrue(posted.index < printed.index);
            assertNull(h.engine.currentLoan());
            Loan last = h.engine.lastLoan();
            assertEquals(Long.valueOf(1001L), last.id());
            assertEquals(money("4.65"), last.change());

            assertEquals(Collections.singletonList(Arrays.<Object>asList(1001L, "100", "15.35")),
                h.query("SELECT id, staff_code, total FROM loan"));
            assertEquals(Arrays.asList(
                    Arrays.<Object>asList(1, "YRD-001", "4.000", "3.50", "14.00", false),
                    Arrays.<Object>asList(2, "STK-001", "1.000", "1.35", "1.35", false)),
                h.query("SELECT line_no, item_code, qty, unit_deposit, amount, returned FROM loan_line"
                    + " WHERE loan_id = ? ORDER BY line_no", 1001L));
            assertEquals(Collections.singletonList(Collections.<Object>singletonList("20.00")),
                h.query("SELECT amount FROM loan_deposit WHERE loan_id = 1001"));
            assertTrue(h.query("SELECT * FROM loan_waiver").isEmpty());
            assertTrue(h.query("SELECT * FROM loan_authorisation").isEmpty());

            String text = h.printer.last().text();
            assertEquals(printed.lines, h.printer.last().lines());
            assertTrue(text.contains("LOAN 1001"), text);
            assertTrue(text.contains("Garden spade"), text);
            assertTrue(text.contains("YRD-001  4 x 3.50"), text);
            assertTrue(text.matches("(?s).*DEPOSIT DUE +15\\.35.*"), text);
            assertTrue(text.matches("(?s).*RECEIVED +20\\.00.*"), text);
            assertTrue(text.matches("(?s).*CHANGE +4\\.65.*"), text);
        }
    }

    @Test
    void depositAcceptsEntryThenDepositKeyAndExactAmount() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.scan("YRD-001");
            h.key(KeyAction.DUE);
            Recorder.Ev ev = h.entry("5");
            expectRequested(ev, LendingState.DEPOSIT);
            assertEquals("Deposit received 5.00: press DEPOSIT", ev.prompt);
            expectRequested(h.key(KeyAction.DEPOSIT), LendingState.IDLE);
            assertEquals(money("1.50"), h.engine.lastLoan().change());

            h.scan("YRD-001");
            h.key(KeyAction.DUE);
            expectRequested(h.key(KeyAction.DEPOSIT), LendingState.IDLE);
            assertEquals(money("3.50"), h.engine.lastLoan().deposits().get(0).amount(), "exactly the amount due");
            assertEquals(money("0.00"), h.engine.lastLoan().change());
            assertEquals(2, h.query("SELECT * FROM loan").size());
        }
    }

    @Test
    void depositBelowAmountDueOrMalformedIsRefused() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.scan("YRD-001");
            h.key(KeyAction.DUE);
            expectPrompt(h.key(KeyAction.DEPOSIT, "3.00"), Engine.PROMPT_INSUFFICIENT_AMOUNT);
            expectRequested(h.answer("OK"), LendingState.DEPOSIT);
            expectPrompt(h.key(KeyAction.DEPOSIT, "abc"), Engine.PROMPT_INVALID_AMOUNT);
            expectRequested(h.answer("OK"), LendingState.DEPOSIT);
            assertTrue(h.query("SELECT * FROM loan").isEmpty());
            expectRequested(h.key(KeyAction.DEPOSIT, "3.50"), LendingState.IDLE);
        }
    }

    @Test
    void unknownItemShowsPrompt() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            Recorder.Ev ev = h.barcode("0000000000000");
            expectPrompt(ev, Engine.PROMPT_UNKNOWN_ITEM);
            assertEquals("Unknown item", ev.prompt);
            assertEquals(Collections.singletonList("OK"), ev.buttons);
            assertEquals(Engine.PROMPT_UNKNOWN_ITEM, h.view.awaitShown(1).get(0).id());
            expectRequested(h.answer("OK"), LendingState.IDLE);
            assertNull(h.engine.currentLoan());

            expectPrompt(h.entry("NOPE-1"), Engine.PROMPT_UNKNOWN_ITEM);
            expectRequested(h.answer("ok"), LendingState.IDLE);
            expectPrompt(h.scan("TIL-001"), Engine.PROMPT_NOT_FOR_LOAN);
            expectRequested(h.answer("OK"), LendingState.IDLE);
        }
    }

    @Test
    void inductionRequiredItemAsksForTheInductionCheck() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            Recorder.Ev ev = h.scan("LAD-001");
            expectPrompt(ev, Engine.PROMPT_INDUCTION_CHECK);
            assertEquals(Arrays.asList("Yes", "No"), ev.buttons);
            expectRequested(h.answer("No"), LendingState.IDLE);
            assertNull(h.engine.currentLoan());
            expectPrompt(h.scan("LAD-001"), Engine.PROMPT_INDUCTION_CHECK);
            expectRequested(h.answer("Yes"), LendingState.LOAN);
            assertEquals("LAD-001", h.engine.currentLoan().lines().get(0).code());
        }
    }

    @Test
    void perDayItemNeedsDays() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            expectPrompt(h.scan("CLN-001"), Engine.PROMPT_DAYS_REQUIRED);
            expectRequested(h.answer("OK"), LendingState.IDLE);
            h.key(KeyAction.QTY, "0.5");
            expectRequested(h.scan("CLN-001"), LendingState.LOAN);
            LoanLine line = h.engine.currentLoan().lines().get(0);
            assertTrue(line.perDay());
            assertEquals(money("0.60"), line.amount());
            h.key(KeyAction.QTY, "1.5");
            expectPrompt(h.scan("YRD-001"), Engine.PROMPT_INVALID_QUANTITY);
            expectRequested(h.answer("OK"), LendingState.LOAN);
            expectPrompt(h.key(KeyAction.QTY, "-2"), Engine.PROMPT_INVALID_QUANTITY);
            expectRequested(h.answer("OK"), LendingState.LOAN);
            assertEquals(1, h.engine.currentLoan().lines().size());
        }
    }

    // ------------------------------------------------------------------ waiver rules

    @Test
    void borrowTwoWaiveOneOnSeedA() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.scan("HND-001");
            h.scan("HND-001");
            h.scan("HND-002");
            h.scan("YRD-001");
            assertTrue(h.engine.currentLoan().waivers().isEmpty(), "waiver rules apply at DUE");
            h.key(KeyAction.DUE);
            Loan loan = h.engine.currentLoan();
            assertEquals(1, loan.waivers().size());
            assertEquals("B2W1-HAND", loan.waivers().get(0).ruleCode());
            assertEquals(money("1.10"), loan.waivers().get(0).amount(), "the lowest deposit is waived");
            assertEquals(money("7.15"), loan.subtotal());
            assertEquals(money("6.05"), loan.total());
            h.key(KeyAction.DEPOSIT, "10");
            assertEquals(Collections.singletonList(Arrays.<Object>asList("B2W1-HAND", "1.10")),
                h.query("SELECT rule_code, amount FROM loan_waiver WHERE loan_id = 1001"));
            assertEquals(Collections.singletonList(Collections.<Object>singletonList("6.05")),
                h.query("SELECT total FROM loan WHERE id = 1001"));
            String text = h.printer.last().text();
            assertTrue(text.matches("(?s).*WAIVER B2W1-HAND +-1\\.10.*"), text);
            assertTrue(text.matches("(?s).*SUBTOTAL +7\\.15.*"), text);
        }
    }

    @Test
    void borrowTwoWaiveOneOnSeedB() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.B)) {
            h.signOn("4711", "9182");
            h.scan("T1001");
            h.scan("T1013");
            h.scan("T1001");
            h.key(KeyAction.DUE);
            Loan loan = h.engine.currentLoan();
            assertEquals(1, loan.waivers().size());
            assertEquals("B2W1-CYCLE", loan.waivers().get(0).ruleCode());
            assertEquals(money("0.89"), loan.waivers().get(0).amount());
            assertEquals(money("2.04"), loan.total());
            h.key(KeyAction.DEPOSIT, "2.04");
            assertEquals(Collections.singletonList(Arrays.<Object>asList(50001L, "B2W1-CYCLE", "0.89")),
                h.query("SELECT loan_id, rule_code, amount FROM loan_waiver"));
            assertTrue(h.printer.last().text().contains("WAIVER B2W1-CYCLE"));
            assertTrue(h.printer.last().text().startsWith("TOOL LIBRARY - CANAL STREET"));
        }
    }

    @Test
    void inactiveAndOutOfWindowWaiverRulesGiveNothing() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.key(KeyAction.QTY, "3");
            h.scan("YRD-001");
            h.key(KeyAction.QTY, "2");
            h.scan("PNT-001");
            h.key(KeyAction.DUE);
            assertTrue(h.engine.currentLoan().waivers().isEmpty());
        }
    }

    // ------------------------------------------------------------------ returns

    @Test
    void returnWithinLimit() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            Recorder.Ev ev = h.key(KeyAction.RETURN);
            expectRequested(ev, LendingState.RETURN);
            assertEquals("Return: scan or enter item", ev.prompt);
            assertTrue(h.engine.isReturnMode());
            assertEquals(Arrays.asList(BARCODE, ENTRY, "Key.RETURN", "Key.CANCEL", "Key.QTY"), h.engine.validInputs());

            expectRequested(h.scan("HND-002"), LendingState.RETURN);
            LoanLine line = h.engine.currentLoan().lines().get(0);
            assertTrue(line.returned());
            assertEquals(money("-1.45"), line.amount());
            assertEquals(Arrays.asList(BARCODE, ENTRY, "Key.DUE", "Key.CANCEL", "Key.QTY"), h.engine.validInputs());

            expectRequested(h.key(KeyAction.DUE), LendingState.DEPOSIT);
            assertTrue(h.engine.isReturnMode());
            assertEquals(money("-1.45"), h.engine.currentLoan().total());
            expectRequested(h.key(KeyAction.DEPOSIT), LendingState.IDLE);
            assertFalse(h.engine.isReturnMode());

            assertEquals(Collections.singletonList(Arrays.<Object>asList("-1.45")),
                h.query("SELECT total FROM loan WHERE id = 1001"));
            assertEquals(Collections.singletonList(Arrays.<Object>asList("HND-002", "-1.45", true)),
                h.query("SELECT item_code, amount, returned FROM loan_line WHERE loan_id = 1001"));
            assertEquals(Collections.singletonList(Collections.<Object>singletonList("-1.45")),
                h.query("SELECT amount FROM loan_deposit WHERE loan_id = 1001"));
            assertTrue(h.query("SELECT * FROM loan_authorisation").isEmpty());
            String text = h.printer.last().text();
            assertTrue(text.contains("RETURN 1001"), text);
            assertTrue(text.matches("(?s).*CHANGE +0\\.00.*"), text);
        }
    }

    @Test
    void returnOverLimitNeedsSupervisorAndRecordsTheAuthorisation() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.key(KeyAction.RETURN);
            Recorder.Ev ev = h.scan("PWR-001");
            expectPrompt(ev, Engine.PROMPT_SUPERVISOR_REQUIRED);
            assertEquals("Supervisor required", ev.prompt);
            assertEquals(Arrays.asList("OK", "Cancel"), ev.buttons);

            ev = h.answer("OK");
            expectRequested(ev, LendingState.RETURN);
            assertEquals("Enter supervisor code", ev.prompt);
            assertEquals(Arrays.asList(ENTRY, "Key.CANCEL"), h.engine.validInputs());
            assertNull(h.engine.currentLoan(), "the line waits for the authorisation");

            ev = h.entry("200");
            assertEquals("Enter supervisor password", ev.prompt);
            assertTrue(h.engine.status().secretEntry());
            expectRequested(h.entry("3333"), LendingState.RETURN);

            Loan loan = h.engine.currentLoan();
            assertEquals(money("-34.99"), loan.lines().get(0).amount());
            assertEquals(1, loan.authorisations().size());
            assertEquals("RETURN_OVERRIDE", loan.authorisations().get(0).kind());
            assertEquals("200", loan.authorisations().get(0).staffCode());

            h.key(KeyAction.DUE);
            expectRequested(h.key(KeyAction.DEPOSIT, "34.99"), LendingState.IDLE);
            assertEquals(Collections.singletonList(Arrays.<Object>asList(1001L, "RETURN_OVERRIDE", "200")),
                h.query("SELECT loan_id, kind, staff_code FROM loan_authorisation"));
            assertEquals(Collections.singletonList(Arrays.<Object>asList("100", "-34.99")),
                h.query("SELECT staff_code, total FROM loan WHERE id = 1001"));
            String text = h.printer.last().text();
            assertTrue(text.contains("RETURN_OVERRIDE AUTHORISED BY 200"), text);
            assertTrue(text.contains("Cordless drill"), text);
        }
    }

    @Test
    void supervisorFlowOnSeedB() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.B)) {
            h.signOn("4711", "9182");
            h.key(KeyAction.RETURN);
            expectPrompt(h.scan("T1009"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            h.entry("4720");
            expectRequested(h.entry("5546"), LendingState.RETURN);
            h.key(KeyAction.DUE);
            h.key(KeyAction.DEPOSIT);
            assertEquals(Collections.singletonList(Arrays.<Object>asList(50001L, "RETURN_OVERRIDE", "4720")),
                h.query("SELECT loan_id, kind, staff_code FROM loan_authorisation"));
        }
    }

    @Test
    void supervisorAuthorisationFailures() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("100", "1111");
            h.key(KeyAction.RETURN);

            expectPrompt(h.scan("PWR-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            expectRequested(h.answer("Cancel"), LendingState.RETURN);
            assertNull(h.engine.currentLoan());

            expectPrompt(h.scan("PWR-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            h.entry("200");
            expectPrompt(h.entry("9999"), Engine.PROMPT_AUTHORISATION_FAILED);
            expectRequested(h.answer("OK"), LendingState.RETURN);
            assertNull(h.engine.currentLoan());

            expectPrompt(h.scan("SND-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            h.entry("200");
            expectPrompt(h.entry("3333"), Engine.PROMPT_AUTHORISATION_FAILED);
            h.answer("OK");

            expectPrompt(h.scan("SND-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            h.entry("300");
            expectRequested(h.entry("4444"), LendingState.RETURN);
            assertEquals("300", h.engine.currentLoan().authorisations().get(0).staffCode());

            expectRequested(h.scan("PWR-001"), LendingState.RETURN);
            assertEquals(1, h.engine.currentLoan().authorisations().size(),
                "the manager's authorisation covers the next line");
            assertEquals(money("423.99"), h.engine.currentLoan().returnValue());
            expectRequested(h.key(KeyAction.CANCEL), LendingState.IDLE);
            assertFalse(h.engine.isReturnMode());
        }
    }

    @Test
    void supervisorCannotAuthoriseTheirOwnReturn() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("200", "3333");
            h.key(KeyAction.RETURN);
            expectPrompt(h.scan("SND-001"), Engine.PROMPT_SUPERVISOR_REQUIRED);
            h.answer("OK");
            h.entry("200");
            expectPrompt(h.entry("3333"), Engine.PROMPT_AUTHORISATION_FAILED);
        }
    }

    @Test
    void returnWithoutPermissionIsRefused() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("101", "2222");
            expectRequested(h.key(KeyAction.RETURN), LendingState.RETURN);
            Recorder.Ev ev = h.scan("HND-001");
            expectPrompt(ev, Engine.PROMPT_RETURN_NOT_PERMITTED);
            assertEquals("Return not permitted", ev.prompt);
            expectRequested(h.answer("OK"), LendingState.RETURN);
            assertNull(h.engine.currentLoan());
            expectRequested(h.key(KeyAction.RETURN), LendingState.IDLE);
            assertFalse(h.engine.isReturnMode());
        }
    }

    @Test
    void returnAboveTheSystemMaximumIsNotAllowed() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            h.signOn("300", "4444");
            h.key(KeyAction.RETURN);
            Recorder.Ev ev = h.scan("PNT-900");
            expectPrompt(ev, Engine.PROMPT_RETURN_NOT_ALLOWED);
            assertEquals("Return not allowed", ev.prompt);
            expectRequested(h.answer("OK"), LendingState.RETURN);
            assertNull(h.engine.currentLoan());
            expectRequested(h.scan("SND-001"), LendingState.RETURN);
        }
        try (EngineHarness h = EngineHarness.start(Seed.B)) {
            h.signOn("4711", "9182");
            h.key(KeyAction.RETURN);
            expectPrompt(h.scan("T1011"), Engine.PROMPT_RETURN_NOT_ALLOWED);
        }
    }

    // ------------------------------------------------------------------ idle lock

    @Test
    void idleLockAndUnlock() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A, 3000)) {
            int mark = h.rec.size();
            h.signOn("100", "1111");
            h.scan("YRD-001");
            Recorder.Ev locked = h.rec.await(mark,
                e -> e.kind == Recorder.Kind.STATE && e.state == LendingState.LOCKED);
            assertEquals(LendingState.LOAN, locked.from);
            Recorder.Ev waiting = h.rec.await(locked.index, e -> e.kind == Recorder.Kind.REQUESTED);
            assertEquals("Locked: enter password", waiting.prompt);
            assertTrue(h.engine.isLocked());
            assertTrue(h.engine.status().secretEntry());
            assertEquals(Arrays.asList(ENTRY, "Key.SIGN_OFF"), h.engine.validInputs());

            expectPrompt(h.entry("0000"), Engine.PROMPT_WRONG_PASSWORD);
            expectRequested(h.answer("OK"), LendingState.LOCKED);
            expectRequested(h.entry("1111"), LendingState.LOAN);
            assertNotNull(h.engine.currentLoan(), "the loan survives the lock");

            mark = h.rec.size();
            h.rec.await(mark, e -> e.kind == Recorder.Kind.STATE && e.state == LendingState.LOCKED);
            expectRequested(h.key(KeyAction.SIGN_OFF), LendingState.SIGNED_OFF);
            assertNull(h.engine.currentLoan(), "signing off a locked desk abandons the loan");
            assertTrue(h.query("SELECT * FROM loan").isEmpty());
        }
    }

    // ------------------------------------------------------------------ lifecycle and listeners

    @Test
    void listenersAndStop() throws Exception {
        try (EngineHarness h = EngineHarness.start(Seed.A)) {
            assertEquals(1, h.engine.listenerCount());
            EngineListener nothing = new EngineListener() { };
            assertTrue(h.engine.isAwaitingInput());
            h.engine.addListener(nothing);
            assertEquals(2, h.engine.listenerCount());
            h.engine.addListener(new EngineListener() {
                @Override
                public void inputAccepted(LendingInput input) {
                    throw new IllegalStateException("a failing listener must not break the engine");
                }
            });
            h.signOn("100", "1111");
            h.engine.removeListener(nothing);
            assertEquals(2, h.engine.listenerCount());
            assertTrue(h.engine.isRunning());
            assertEquals(Engine.THREAD_NAME, h.engine.thread().getName());

            assertTrue(h.engine.inbox().offer(LendingInput.barcode("0000000000000")));
            h.rec.await(0, e -> e.kind == Recorder.Kind.PROMPT_SHOWN);
            assertTrue(h.engine.stop(10_000), "stop ends the thread even while a prompt is open");
            assertFalse(h.engine.isRunning());
            assertFalse(h.engine.status().running());
            assertNull(h.prompts.current());
        }
    }
}
