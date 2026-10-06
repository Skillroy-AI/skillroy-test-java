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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ActiveWaiverRuleWithTriggers;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemsTriggering;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LoanLineRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverRuleRef;
import ai.skillroy.testjava.samples.lending.pack.LendingResolutionTest.Ctx;
import ai.skillroy.testjava.samples.lending.pack.LendingResolutionTest.Desk;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.RecordCodec;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.pack.LendingResolutionTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's waiver model on its own (phase3-contracts.md 10.3, 11.1 item 5, pack part): the oracles against
 * hand-computed cases for every branch of 10.1 steps 2 and 3, the waiver rule resolver over canned rows (the business
 * date read first and recorded, the statement's one parameter, the date window applied in Java, grouping, the
 * quantity filters, {@code exclusiveItems}, {@code NO_DATE}), the item resolver, the two verifiers and the cross-check
 * against a scripted desk, every pass and every fail reason. The same code against the real schema, the real waiver
 * engine and every seeded rule is tested in the scenarios module.
 */
class LendingWaiverTest {

    static final String KIND = "BORROW_N_WAIVE_M";
    static final String TODAY = "2026-10-03";

    static final WaiverItem HND_003 = item("HND-003", "5000000000062", "Screwdriver set", "0.99", false, false);
    static final WaiverItem HND_001 = item("HND-001", "5000000000048", "Claw hammer", "1.10", false, false);
    static final WaiverItem HND_002 = item("HND-002", "5000000000055", "Hand saw", "1.45", false, false);
    static final WaiverItem CLN_001 = item("CLN-001", "2000000000107", "Carpet cleaner, per day", "1.20", true, false);
    static final WaiverItem LAD_001 = item("LAD-001", "5000000000086", "Extension ladder", "6.50", false, true);
    /** Seed A's effective rule as the resolver reads it: 2 + 1 over three hand tools, cheapest first. */
    static final WaiverRuleRef HAND = new WaiverRuleRef("B2W1-HAND", KIND, 2, 1, List.of(HND_003, HND_001, HND_002));

    static WaiverItem item(String code, String barcode, String title, String deposit, boolean perDay,
                           boolean induction) {
        return new WaiverItem(code, barcode, title, new BigDecimal(deposit), perDay, induction);
    }

    static LoanLineRef line(String code, long qty, String deposit) {
        return new LoanLineRef(code, qty, new BigDecimal(deposit));
    }

    static WaiverRuleRef rule(String code, int borrow, int waived, WaiverItem... items) {
        return new WaiverRuleRef(code, KIND, borrow, waived, List.of(items));
    }

    // ------------------------------------------------------------------ LoanFromTrigger

    @Test
    void loanFromTriggerIsOneGroupOfTheItem() {
        LoanFromTrigger oracle = new LoanFromTrigger();
        assertEquals("LoanFromTrigger", oracle.fn());
        ItemRef screwdrivers = new ItemRef("HND-003", "5000000000062", "Screwdriver set", new BigDecimal("0.99"));
        LoanLineRef needed = oracle.compute(new LoanFromTrigger.Input(HAND, screwdrivers));
        assertEquals(line("HND-003", 3, "0.99"), needed);
        assertEquals(Map.of("code", "HND-003", "qty", 3L, "unitDeposit", "0.99"), RecordCodec.DEFAULT.encode(needed));
        assertEquals(4L, oracle.compute(new LoanFromTrigger.Input(rule("B3W1-WRENCH", 3, 1, HND_003), screwdrivers))
                .qty(), "borrow 3, the fourth waived");
        assertEquals(2L, oracle.compute(new LoanFromTrigger.Input(rule("B1W1-PAINT", 1, 1, HND_003), screwdrivers))
                .qty());
        assertEquals("1.10", oracle.compute(new LoanFromTrigger.Input(HAND, new ItemRef("HND-001", "1", "x",
                new BigDecimal("1.1")))).unitDeposit().toPlainString(), "money at scale 2");
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new LoanFromTrigger.Input(
                rule("B2W0", 2, 0, HND_003), screwdrivers)), "a rule that waives no unit is a pack defect");
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new LoanFromTrigger.Input(
                rule("NEG", -1, 1, HND_003), screwdrivers)));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new LoanFromTrigger.Input(null,
                screwdrivers)));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new LoanFromTrigger.Input(HAND, null)));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(null));
    }

    // ------------------------------------------------------------------ ExpectedWaiver (10.1 step 2)

    private static ExpectedWaiver.Waiver waiver(WaiverRuleRef rule, LoanLineRef... lines) {
        return new ExpectedWaiver().compute(new ExpectedWaiver.Input(rule, List.of(lines)));
    }

    private static String text(ExpectedWaiver.Waiver waiver) {
        return waiver.ruleCode() + " " + waiver.amount().toPlainString() + " x" + waiver.waivedUnits();
    }

    @Test
    void expectedWaiverWaivesTheCheapestDepositOfEachGroup() {
        assertEquals("ExpectedWaiver", new ExpectedWaiver().fn());
        assertEquals("B2W1-HAND 0.99 x1", text(waiver(HAND, line("HND-003", 3, "0.99"))), "one group");
        assertEquals("B2W1-HAND 0.00 x0", text(waiver(HAND, line("HND-003", 2, "0.99"))), "one unit short");
        assertEquals("B2W1-HAND 1.98 x2", text(waiver(HAND, line("HND-003", 6, "0.99"))), "two groups");
        assertEquals("B2W1-HAND 1.98 x2", text(waiver(HAND, line("HND-003", 8, "0.99"))), "two groups and two over");
        assertEquals("B2W1-HAND 2.97 x3", text(waiver(HAND, line("HND-003", 9, "0.99"))));
        assertEquals(Map.of("amount", "0.99", "ruleCode", "B2W1-HAND", "waivedUnits", 1L),
                RecordCodec.DEFAULT.encode(waiver(HAND, line("HND-003", 3, "0.99"))));
        assertEquals(Map.of("amount", "0.00", "ruleCode", "B2W1-HAND", "waivedUnits", 0L),
                RecordCodec.DEFAULT.encode(waiver(HAND, line("HND-003", 1, "0.99"))), "nothing is 0.00 and 0");
    }

    /** The units of every line are sorted by deposit, so the lowest deposits are waived whatever the line order. */
    @Test
    void expectedWaiverSortsTheUnitsOfMixedItemsByDeposit() {
        assertEquals("B2W1-HAND 0.99 x1", text(waiver(HAND, line("HND-002", 1, "1.45"), line("HND-001", 1, "1.10"),
                line("HND-003", 1, "0.99"))), "one of each: the cheapest is waived");
        assertEquals("B2W1-HAND 1.10 x1", text(waiver(HAND, line("HND-002", 2, "1.45"), line("HND-001", 1,
                "1.10"))));
        assertEquals("B2W1-HAND 2.09 x2", text(waiver(HAND, line("HND-002", 4, "1.45"), line("HND-001", 1, "1.10"),
                line("HND-003", 1, "0.99"))), "6 units, 2 waived: 0.99 and 1.10");
        assertEquals("B2W1-HAND 2.09 x2", text(waiver(HAND, line("HND-003", 1, "0.99"), line("HND-001", 1, "1.10"),
                line("HND-002", 4, "1.45"))), "the order of the lines does not matter");
        assertEquals("B2W1-HAND 2.20 x2", text(waiver(HAND, line("HND-001", 3, "1.10"), line("HND-002", 3,
                "1.45"))));
    }

    /**
     * Ties: equal deposits are taken by item code, then by the line's position. The amount is the same either way
     * (equal deposits), so the order is checked on the units themselves; deposits are compared at scale 2, as the
     * desk holds them, so {@code 0.995} ties with {@code 1.00} and {@code 0.994} sorts first.
     */
    @Test
    void expectedWaiverBreaksTiesByCodeAndThenByPosition() {
        WaiverRuleRef tie = rule("B1W1-TIE", 1, 1, item("A", "1", "a", "1.00", false, false),
                item("B", "2", "b", "1.00", false, false), item("C", "3", "c", "1.00", false, false));
        List<LoanLineRef> lines = List.of(line("B", 1, "1.00"), line("A", 1, "1.00"), line("B", 2, "1.00"),
                line("C", 1, "0.995"), line("A", 1, "0.994"));
        assertEquals(List.of("A@4 0.99", "A@1 1.00", "B@0 1.00", "B@2 1.00", "C@3 1.00"),
                ExpectedWaiver.units(tie, lines).stream().map(u -> u.code() + "@" + u.position() + " "
                        + u.deposit().toPlainString()).toList());
        assertEquals("B1W1-TIE 2.99 x3", text(new ExpectedWaiver().compute(new ExpectedWaiver.Input(tie, lines))),
                "6 units, 3 waived: 0.99, 1.00 and 1.00");
    }

    /** One unit per whole quantity: a line without a positive quantity counts nothing; a large one costs nothing. */
    @Test
    void expectedWaiverCountsOneUnitPerWholeQuantity() {
        assertEquals("B2W1-HAND 0.99 x1", text(waiver(HAND, line("HND-003", 0, "0.99"), line("HND-003", -3, "0.99"),
                line("HND-003", 3, "0.99"))));
        assertEquals("B2W1-HAND 990.00 x1000", text(waiver(HAND, line("HND-003", 3000, "0.99"))));
        assertEquals("B2W1-HAND 1.98 x2", text(waiver(HAND, line("HND-003", 1, "0.99"), line("HND-003", 2, "0.99"),
                line("HND-003", 3, "0.99"))), "lines of one item add up");
    }

    /** What the desk does not count: per-day items, items the rule does not cover, and rules that waive nothing. */
    @Test
    void expectedWaiverIgnoresWhatTheDeskIgnores() {
        WaiverRuleRef withPerDay = rule("B2W1-MIX", 2, 1, HND_003, CLN_001);
        assertEquals("B2W1-MIX 0.00 x0", text(waiver(withPerDay, line("CLN-001", 3, "1.20"))), "per day: ignored");
        assertEquals("B2W1-MIX 0.99 x1", text(waiver(withPerDay, line("CLN-001", 5, "1.20"), line("HND-003", 3,
                "0.99"))), "the per-day line is no unit, the counted ones are");
        assertEquals("B2W1-HAND 0.00 x0", text(waiver(HAND, line("YRD-001", 3, "3.50"))), "not covered");
        assertEquals("B2W1-HAND 0.99 x1", text(waiver(HAND, line("YRD-001", 3, "3.50"), line("HND-003", 3,
                "0.99"))));
        assertEquals("B2W0 0.00 x0", text(waiver(rule("B2W0", 2, 0, HND_003), line("HND-003", 9, "0.99"))),
                "waivedQty 0");
        assertEquals("NEG 0.00 x0", text(waiver(rule("NEG", -1, 1, HND_003), line("HND-003", 9, "0.99"))));
        assertEquals("OTHER 0.00 x0", text(waiver(new WaiverRuleRef("OTHER", "SPEND_N_SAVE_M", 2, 1,
                List.of(HND_003)), line("HND-003", 3, "0.99"))), "a kind the desk does not evaluate");
        assertEquals("NONE 0.00 x0", text(waiver(new WaiverRuleRef("NONE", KIND, 2, 1, null), line("HND-003", 3,
                "0.99"))));
        assertEquals("B2W1-HAND 0.00 x0", text(waiver(HAND)), "no lines");
    }

    @Test
    void expectedWaiverRefusesAMissingRuleLineOrDeposit() {
        ExpectedWaiver oracle = new ExpectedWaiver();
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(null));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new ExpectedWaiver.Input(null,
                List.of(line("HND-003", 3, "0.99")))));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new ExpectedWaiver.Input(HAND, null)));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new ExpectedWaiver.Input(HAND,
                List.of(new LoanLineRef("HND-003", 3, null)))));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new ExpectedWaiver.Input(HAND,
                List.of(new LoanLineRef(null, 3, BigDecimal.ONE)))));
    }

    // ------------------------------------------------------------------ ExpectedTotals (10.1 step 3)

    @Test
    void expectedTotalsAreTheSubtotalLessTheWaivers() {
        ExpectedTotals oracle = new ExpectedTotals();
        assertEquals("ExpectedTotals", oracle.fn());
        ExpectedTotals.Totals a = oracle.compute(new ExpectedTotals.Input(List.of(line("HND-003", 3, "0.99")),
                List.of(new BigDecimal("0.99"))));
        assertEquals(Map.of("subtotal", "2.97", "waiverTotal", "0.99", "total", "1.98"),
                RecordCodec.DEFAULT.encode(a), "seed A");
        assertEquals(Map.of("subtotal", "2.37", "waiverTotal", "0.79", "total", "1.58"),
                RecordCodec.DEFAULT.encode(oracle.compute(new ExpectedTotals.Input(List.of(line("T1002", 3, "0.79")),
                        List.of(new BigDecimal("0.79"))))), "seed B");
        assertEquals(Map.of("subtotal", "3.65", "waiverTotal", "0.75", "total", "2.90"),
                RecordCodec.DEFAULT.encode(oracle.compute(new ExpectedTotals.Input(List.of(line("HND-001", 2, "1.10"),
                        line("HND-002", 1, "1.45")), List.of(new BigDecimal("0.50"), new BigDecimal("0.25"))))));
        assertEquals(Map.of("subtotal", "2.97", "waiverTotal", "0.00", "total", "2.97"),
                RecordCodec.DEFAULT.encode(oracle.compute(new ExpectedTotals.Input(List.of(line("HND-003", 3,
                        "0.99")), List.of()))));
        assertEquals(Map.of("subtotal", "2.97", "waiverTotal", "0.00", "total", "2.97"),
                RecordCodec.DEFAULT.encode(oracle.compute(new ExpectedTotals.Input(List.of(line("HND-003", 3,
                        "0.99")), null))), "no waivers");
        assertEquals("3.00", oracle.compute(new ExpectedTotals.Input(List.of(line("X", 3, "0.995")), List.of()))
                .subtotal().toPlainString(), "the unit deposit at scale 2 first, as the desk holds it");
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new ExpectedTotals.Input(null, List.of())));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new ExpectedTotals.Input(
                List.of(new LoanLineRef("X", 1, null)), List.of())));
        List<BigDecimal> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(new ExpectedTotals.Input(
                List.of(line("X", 1, "1.00")), withNull)));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(null));
    }

    // ------------------------------------------------------------------ the waiver rule resolver

    static final List<String> RULE_COLUMNS = List.of("ID", "CODE", "KIND", "BORROW_QTY", "WAIVED_QTY", "STARTS_ON",
            "ENDS_ON", "CODE", "BARCODE", "TITLE", "DEPOSIT", "PER_DAY", "INDUCTION_REQUIRED");

    /** A row of a rule whose window holds {@link #TODAY}: a month either side. */
    static List<Object> ruleRow(long id, String code, Object borrow, Object waived, WaiverItem item) {
        return ruleRow(id, code, borrow, waived, "2026-09-03", "2026-11-02", item);
    }

    /** A row of a rule with this window; the bounds as the data channels convert a date (ISO-8601 text), or null. */
    static List<Object> ruleRow(long id, String code, Object borrow, Object waived, Object startsOn, Object endsOn,
                                WaiverItem item) {
        return LendingResolutionTest.Arrays2.row(id, code, KIND, borrow, waived, startsOn, endsOn, item.code(),
                item.barcode(), item.title(), item.deposit().toPlainString(), item.perDay(), item.inductionRequired());
    }

    /** A desk whose {@code app.state} reports {@code today}, and a context that asks it. */
    static Ctx withDate(Desk desk, Object today) {
        Map<String, Object> lending = new HashMap<>();
        lending.put("seed", "A");
        lending.put("today", today);
        desk.on("app.state", p -> Map.of("lending", lending));
        Ctx ctx = new Ctx();
        ctx.agent = desk;
        return ctx;
    }

    private static List<String> codes(List<ResolverCandidate<WaiverRuleRef>> found) {
        return found.stream().map(c -> c.value().code()).toList();
    }

    @Test
    void theWaiverRuleResolverReadsTheDatesFirstAndGroupsTheRowsByRule() throws Exception {
        Desk desk = new Desk();
        Ctx ctx = withDate(desk, TODAY);
        WaiverItem wrench = item("T1005", "4006000000059", "Pipe wrench", "2.15", false, false);
        ctx.answer(LendingResolvers.WAIVER_RULE_SQL, params -> {
            assertEquals(ctx.params.size(), desk.methods.size(), "the business date is read before each statement");
            return rows(RULE_COLUMNS, List.of(ruleRow(1, "B2W1-HAND", 2L, 1L, HND_003),
                    ruleRow(1, "B2W1-HAND", 2L, 1L, HND_001), ruleRow(1, "B2W1-HAND", 2L, 1L, HND_002),
                    ruleRow(4, "B3W1-WRENCH", 3L, 1L, wrench), ruleRow(7, "B2W1-MIX", 2L, 1L, CLN_001),
                    ruleRow(7, "B2W1-MIX", 2L, 1L, LAD_001)));
        });
        LendingResolvers.WaiverRuleResolver resolver = new LendingResolvers.WaiverRuleResolver();
        assertEquals("lending.sql.ActiveWaiverRuleWithTriggers", resolver.id());
        List<ResolverCandidate<WaiverRuleRef>> found = resolver.candidates(new ActiveWaiverRuleWithTriggers(null,
                null, null, null), ctx);
        assertEquals(List.of("B2W1-HAND", "B3W1-WRENCH", "B2W1-MIX"), codes(found), "one candidate per rule, by id");
        assertEquals(HAND, found.get(0).value());
        assertEquals("rule B2W1-HAND", found.get(0).label());
        assertEquals(new WaiverRuleRef("B2W1-MIX", KIND, 2, 1, List.of(CLN_001, LAD_001)), found.get(2).value(),
                "the flags of each item");
        assertEquals(List.of(List.of(KIND)), ctx.params, "the kind (default) and no date");
        assertEquals(List.of("app.state"), desk.methods, "one read-only call: the provenance call");
        assertEquals(List.of(Map.of()), desk.params);
        assertEquals(Map.of("borrowQty", 2L, "code", "B2W1-HAND", "items", List.of(
                Map.of("barcode", "5000000000062", "code", "HND-003", "deposit", "0.99", "inductionRequired", false,
                        "perDay", false, "title", "Screwdriver set"),
                Map.of("barcode", "5000000000048", "code", "HND-001", "deposit", "1.10", "inductionRequired", false,
                        "perDay", false, "title", "Claw hammer"),
                Map.of("barcode", "5000000000055", "code", "HND-002", "deposit", "1.45", "inductionRequired", false,
                        "perDay", false, "title", "Hand saw")), "kind", KIND, "waivedQty", 1L),
                RecordCodec.DEFAULT.encode(found.get(0).value()), "the value a binding records: no rule id");

        ctx.params.clear();
        desk.methods.clear();
        assertEquals(List.of("B2W1-HAND", "B2W1-MIX"), codes(resolver.candidates(new ActiveWaiverRuleWithTriggers(
                KIND, 2, null, null), ctx)), "borrowQty");
        assertEquals(List.of("B3W1-WRENCH"), codes(resolver.candidates(new ActiveWaiverRuleWithTriggers(KIND, 3, 1,
                false), ctx)), "borrowQty and waivedQty");
        assertEquals(List.of(), codes(resolver.candidates(new ActiveWaiverRuleWithTriggers(KIND, null, 2, null),
                ctx)));
        resolver.candidates(new ActiveWaiverRuleWithTriggers("SPEND_N_SAVE_M", null, null, null), ctx);
        assertEquals(List.of("SPEND_N_SAVE_M"), ctx.params.get(ctx.params.size() - 1), "the kind");
    }

    /**
     * The date window, applied in Java with the desk's own rule ({@code WaiverRule.effectiveOn}): {@code starts_on <=
     * today <= ends_on}, both bounds inclusive, a null bound open. A bound that is not an ISO-8601 date makes the rule
     * no candidate. The statement's parameter is the kind alone, whatever the date.
     */
    @Test
    void theWaiverRuleResolverKeepsTheRulesWhoseWindowHoldsTheDesksDate() throws Exception {
        Desk desk = new Desk();
        Ctx ctx = withDate(desk, TODAY);
        ctx.answer(LendingResolvers.WAIVER_RULE_SQL, params -> rows(RULE_COLUMNS, List.of(
                ruleRow(1, "INSIDE", 2L, 1L, "2026-09-03", "2026-11-02", HND_003),
                ruleRow(2, "STARTS-TODAY", 2L, 1L, TODAY, "2026-11-02", HND_003),
                ruleRow(3, "ENDS-TODAY", 2L, 1L, "2026-09-03", TODAY, HND_003),
                ruleRow(4, "NO-START", 2L, 1L, null, "2026-11-02", HND_003),
                ruleRow(5, "NO-END", 2L, 1L, "2026-09-03", null, HND_003),
                ruleRow(6, "OPEN", 2L, 1L, null, null, HND_003),
                ruleRow(7, "STARTS-TOMORROW", 2L, 1L, "2026-10-04", "2026-11-02", HND_003),
                ruleRow(8, "ENDED-YESTERDAY", 2L, 1L, "2026-09-03", "2026-10-02", HND_003),
                ruleRow(9, "NOT-A-DATE", 2L, 1L, "next week", null, HND_003))));
        LendingResolvers.WaiverRuleResolver resolver = new LendingResolvers.WaiverRuleResolver();
        assertEquals(List.of("INSIDE", "STARTS-TODAY", "ENDS-TODAY", "NO-START", "NO-END", "OPEN"),
                codes(resolver.candidates(new ActiveWaiverRuleWithTriggers(null, null, null, null), ctx)));
        Ctx tomorrow = withDate(new Desk(), "2026-10-04");
        tomorrow.answers.putAll(ctx.answers);
        assertEquals(List.of("INSIDE", "STARTS-TODAY", "NO-START", "NO-END", "OPEN", "STARTS-TOMORROW"),
                codes(resolver.candidates(new ActiveWaiverRuleWithTriggers(null, null, null, null), tomorrow)),
                "the same rows on the next day");
        assertEquals(List.of(List.of(KIND)), tomorrow.params, "the same statement and parameter on every day");
        assertTrue(LendingResolvers.WaiverRuleResolver.effective(null, null, java.time.LocalDate.parse(TODAY)));
        assertFalse(LendingResolvers.WaiverRuleResolver.effective("2026-10-03T00:00", null,
                java.time.LocalDate.parse(TODAY)), "a date only, as the channels convert it");
    }

    /**
     * {@code exclusiveItems}: a rule that shares an item with another effective rule of the kind is dropped, and so is
     * the other, before the quantities narrow the result (a 3 + 1 rule over a hand tool still waives at the desk); a
     * rule outside its window waives nothing today and excludes nothing.
     */
    @Test
    void exclusiveItemsDropsEveryRuleThatSharesAnItem() throws Exception {
        Desk desk = new Desk();
        Ctx ctx = withDate(desk, TODAY);
        WaiverItem wrench = item("T1005", "4006000000059", "Pipe wrench", "2.15", false, false);
        ctx.answer(LendingResolvers.WAIVER_RULE_SQL, params -> rows(RULE_COLUMNS, List.of(
                ruleRow(1, "B2W1-HAND", 2L, 1L, HND_003), ruleRow(1, "B2W1-HAND", 2L, 1L, HND_001),
                ruleRow(4, "B3W1-WRENCH", 3L, 1L, wrench),
                ruleRow(5, "B3W1-HAMMER", 3L, 1L, HND_001), ruleRow(6, "B2W1-SPADE", 2L, 1L, HND_002),
                ruleRow(8, "B1W1-LATER", 1L, 1L, "2026-10-13", null, HND_002))));
        LendingResolvers.WaiverRuleResolver resolver = new LendingResolvers.WaiverRuleResolver();
        assertEquals(List.of("B2W1-HAND", "B3W1-WRENCH", "B3W1-HAMMER", "B2W1-SPADE"), codes(resolver.candidates(
                new ActiveWaiverRuleWithTriggers(null, null, null, false), ctx)));
        assertEquals(List.of("B3W1-WRENCH", "B2W1-SPADE"), codes(resolver.candidates(
                new ActiveWaiverRuleWithTriggers(null, null, null, true), ctx)), "HAND and HAMMER share HND-001;"
                + " LATER shares HND-002 with SPADE but is not effective today, so SPADE stays");
        assertEquals(List.of("B2W1-SPADE"), codes(resolver.candidates(new ActiveWaiverRuleWithTriggers(null, 2, 1,
                true), ctx)), "HAND is not offered although HAMMER is no 2 + 1 rule");
    }

    @Test
    void aDeskWithoutABusinessDateIsNoDate() {
        for (Object today : java.util.Arrays.asList(null, "03/10/2026", 20261003L)) {
            Desk desk = new Desk();
            Ctx ctx = withDate(desk, today);
            ResolutionException e = assertThrows(ResolutionException.class, () -> new LendingResolvers
                    .WaiverRuleResolver().candidates(new ActiveWaiverRuleWithTriggers(null, null, null, null), ctx));
            assertEquals("NO_DATE", e.reason(), String.valueOf(today));
            assertTrue(e.getMessage().contains("app.state lending.today"), e.getMessage());
            assertEquals(List.of(), ctx.params, "no statement without a date");
        }
        Desk empty = new Desk();
        empty.on("app.state", p -> Map.of("other", Map.of()));
        Ctx ctx = new Ctx();
        ctx.agent = empty;
        assertEquals("NO_DATE", assertThrows(ResolutionException.class, () -> new LendingResolvers
                .WaiverRuleResolver().candidates(new ActiveWaiverRuleWithTriggers(null, null, null, null), ctx))
                .reason(), "no lending section");
    }

    @Test
    void theWaiverRuleStatementIsReadWholeAndOddRowsAreSkipped() throws Exception {
        Desk desk = new Desk();
        Ctx ctx = withDate(desk, TODAY);
        ctx.answer(LendingResolvers.WAIVER_RULE_SQL, params -> Rows.of(rows(RULE_COLUMNS, List.of()).columns(),
                List.of(ruleRow(1, "B2W1-HAND", 2L, 1L, HND_003)), true));
        ResolutionException cut = assertThrows(ResolutionException.class, () -> new LendingResolvers
                .WaiverRuleResolver().candidates(new ActiveWaiverRuleWithTriggers(null, null, null, null), ctx));
        assertEquals("TRUNCATED", cut.reason(), "a cut result would cut a rule's items");
        List<Object> badDeposit = ruleRow(2, "B2W1-ODD", 2L, 1L, HND_001);
        badDeposit.set(10, "n/a");
        ctx.answer(LendingResolvers.WAIVER_RULE_SQL, params -> rows(RULE_COLUMNS, List.of(
                ruleRow(1, "B2W1-HALF", "2.5", 1L, HND_003), badDeposit, ruleRow(2, "B2W1-ODD", "2", "1", HND_002),
                ruleRow(3, "B2W1-LAST", 2L, 1L, HND_003))));
        List<ResolverCandidate<WaiverRuleRef>> found = new LendingResolvers.WaiverRuleResolver().candidates(
                new ActiveWaiverRuleWithTriggers(null, null, null, null), ctx);
        assertEquals(List.of("B2W1-ODD", "B2W1-LAST"), codes(found), "a rule of 2.5 units is no candidate");
        assertEquals(List.of(HND_002), found.get(0).value().items(), "an item without a decimal deposit is left out");
    }

    // ------------------------------------------------------------------ the item resolver

    @Test
    void theItemsTriggeringResolverFiltersTheRulesItemsByItsFlags() {
        WaiverRuleRef rule = rule("B2W1-MIX", 2, 1, HND_003, HND_001, CLN_001, LAD_001);
        LendingResolvers.ItemsTriggeringResolver resolver = new LendingResolvers.ItemsTriggeringResolver();
        assertEquals("lending.java.ItemsTriggering", resolver.id());
        Ctx ctx = new Ctx();
        List<ResolverCandidate<ItemRef>> plain = resolver.candidates(new ItemsTriggering(rule, null, null), ctx);
        assertEquals(List.of(new ItemRef("HND-003", "5000000000062", "Screwdriver set", new BigDecimal("0.99")),
                new ItemRef("HND-001", "5000000000048", "Claw hammer", new BigDecimal("1.10"))),
                plain.stream().map(ResolverCandidate::value).toList(), "plain counted tools, cheapest first");
        assertEquals("item HND-003", plain.get(0).label());
        assertEquals(plain.stream().map(ResolverCandidate::value).toList(), resolver.candidates(
                new ItemsTriggering(rule, false, false), ctx).stream().map(ResolverCandidate::value).toList());
        assertEquals(List.of("CLN-001"), resolver.candidates(new ItemsTriggering(rule, true, null), ctx).stream()
                .map(c -> c.value().code()).toList());
        assertEquals(List.of("LAD-001"), resolver.candidates(new ItemsTriggering(rule, null, true), ctx).stream()
                .map(c -> c.value().code()).toList());
        assertEquals(List.of(), resolver.candidates(new ItemsTriggering(rule, true, true), ctx));
        assertEquals(List.of(), resolver.candidates(new ItemsTriggering(null, null, null), ctx));
        assertEquals(List.of(), resolver.candidates(new ItemsTriggering(new WaiverRuleRef("X", KIND, 2, 1, null),
                null, null), ctx));
        assertEquals(List.of(), ctx.params, "no statement: the rule's value holds everything");
    }

    // ------------------------------------------------------------------ the waiver rule verifier

    /** A desk whose dry run answers these waivers on {@link #TODAY}. */
    static Desk dryRun(Map<String, String> waivers) {
        Desk desk = new Desk();
        List<Object> list = new ArrayList<>();
        waivers.forEach((code, amount) -> list.add(Map.of("ruleCode", code, "amount", amount)));
        desk.on("lending.waiver.evaluate", p -> Map.of("date", TODAY, "waivers", list));
        return desk;
    }

    private static Map<String, String> ordered(String... codesAndAmounts) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < codesAndAmounts.length; i += 2) {
            map.put(codesAndAmounts[i], codesAndAmounts[i + 1]);
        }
        return map;
    }

    @Test
    void theWaiverVerifierAsksTheDesksDryRunOfOneGroup() throws Exception {
        LendingVerifiers.WaiverVerifier verifier = new LendingVerifiers.WaiverVerifier();
        assertEquals("lending.waiver.evaluate", verifier.id());
        assertEquals(List.of("lending.waiver.evaluate"), verifier.requires());
        ActiveWaiverRuleWithTriggers exclusive = new ActiveWaiverRuleWithTriggers(KIND, null, null, true);
        Desk desk = dryRun(ordered("B2W1-HAND", "0.99"));
        VerifierResult ok = verifier.verify(exclusive, HAND, desk.ctx());
        assertTrue(ok.passed(), ok.reason());
        assertEquals("the desk waives 0.99 for 3 units of HND-003 under B2W1-HAND", ok.reason(),
                "no date in a reason: it is written to the bindings file");
        assertEquals(TODAY, ok.evidence().get("date"), "the date the desk evaluated on is evidence");
        assertEquals(List.of(Map.of("lines", List.of(Map.of("code", "HND-003", "qty", 3L)))), desk.params,
                "one group of the cheapest counted item, on the desk's own date (no date parameter)");
        assertEquals(1, ((List<?>) ok.evidence().get("calls")).size());
        WaiverRuleRef perDayFirst = rule("B2W1-MIX", 2, 1, CLN_001, HND_001);
        Desk mixed = dryRun(ordered("B2W1-MIX", "1.10"));
        assertEquals("the desk waives 1.10 for 3 units of HND-001 under B2W1-MIX",
                verifier.verify(exclusive, perDayFirst, mixed.ctx()).reason(), "the first item that is counted");
        assertEquals("the desk waives 2.15 for 4 units of T1005 under B3W1-WRENCH",
                verifier.verify(new ActiveWaiverRuleWithTriggers(null, 3, 1, null), rule("B3W1-WRENCH", 3, 1,
                        item("T1005", "4006000000059", "Pipe wrench", "2.15", false, false)),
                        dryRun(ordered("B3W1-WRENCH", "2.15")).ctx()).reason());
    }

    @Test
    void theWaiverVerifierGivesTheDesksReasonForEveryFailure() throws Exception {
        LendingVerifiers.WaiverVerifier verifier = new LendingVerifiers.WaiverVerifier();
        ActiveWaiverRuleWithTriggers exclusive = new ActiveWaiverRuleWithTriggers(KIND, null, null, true);
        Desk untouched = dryRun(ordered());
        assertEquals("rule B2W1-HAND is of kind BORROW_N_WAIVE_M, not SPEND_N_SAVE_M", verifier.verify(
                new ActiveWaiverRuleWithTriggers("SPEND_N_SAVE_M", null, null, null), HAND, untouched.ctx()).reason());
        assertEquals("rule B2W1-HAND is 2 + 1, not what the query asks", verifier.verify(
                new ActiveWaiverRuleWithTriggers(null, 3, null, null), HAND, untouched.ctx()).reason());
        assertEquals("rule B2W1-HAND is 2 + 1, not what the query asks", verifier.verify(
                new ActiveWaiverRuleWithTriggers(null, null, 2, null), HAND, untouched.ctx()).reason());
        assertEquals("the rule covers no counted item the desk can lend", verifier.verify(exclusive,
                rule("B2W1-DAYS", 2, 1, CLN_001), untouched.ctx()).reason());
        assertEquals("the rule covers no counted item the desk can lend", verifier.verify(exclusive,
                new WaiverRuleRef("B2W1-EMPTY", KIND, 2, 1, null), untouched.ctx()).reason());
        assertEquals("rule B2W0 is 2 + 0, which waives no unit", verifier.verify(new ActiveWaiverRuleWithTriggers(
                null, null, null, null), rule("B2W0", 2, 0, HND_003), untouched.ctx()).reason());
        assertEquals(List.of(), untouched.methods, "none of these needed the desk");

        VerifierResult nothing = verifier.verify(exclusive, HAND, dryRun(ordered()).ctx());
        assertEquals("the desk waives nothing under B2W1-HAND for 3 units of HND-003", nothing.reason(),
                "out of its window, or off");
        assertEquals(TODAY, nothing.evidence().get("date"), "the date of that answer is evidence");
        assertEquals("the desk waives nothing under B2W1-HAND for 3 units of HND-003; it waives B1W1-OTHER 0.50",
                verifier.verify(exclusive, HAND, dryRun(ordered("B1W1-OTHER", "0.50")).ctx()).reason());
        assertEquals("the desk waives 1.98 for 3 units of HND-003 under B2W1-HAND, not 0.99",
                verifier.verify(exclusive, HAND, dryRun(ordered("B2W1-HAND", "1.98")).ctx()).reason());
        Desk two = dryRun(ordered("B2W1-HAND", "0.99", "B1W1-OTHER", "0.50"));
        VerifierResult shared = verifier.verify(exclusive, HAND, two.ctx());
        assertFalse(shared.passed());
        assertEquals("3 units of HND-003 trigger other rules too: B1W1-OTHER 0.50", shared.reason());
        assertTrue(verifier.verify(new ActiveWaiverRuleWithTriggers(KIND, null, null, false), HAND, two.ctx())
                .passed(), "another waiver is allowed without exclusiveItems");

        Desk unknown = new Desk();
        unknown.fail("lending.waiver.evaluate", new RpcError(ErrorCodes.INVALID_PARAMS, "lines[0].code unknown item",
                Map.of("param", "lines[0].code", "reason", "unknown item")));
        VerifierResult notKnown = verifier.verify(exclusive, HAND, unknown.ctx());
        assertEquals("the desk does not know item HND-003", notKnown.reason());
        assertTrue(String.valueOf(notKnown.evidence()).contains("unknown item"), "the error is evidence");
        Desk broken = new Desk();
        broken.fail("lending.waiver.evaluate", new RpcError(ErrorCodes.INTERNAL_ERROR, "boom", null));
        assertThrows(AgentRpcException.class, () -> verifier.verify(exclusive, HAND, broken.ctx()),
                "any other error is not a verifier's no");
        Desk odd = new Desk();
        odd.on("lending.waiver.evaluate", p -> Map.of("date", TODAY));
        assertThrows(IllegalStateException.class, () -> verifier.verify(exclusive, HAND, odd.ctx()),
                "an answer without waivers is a defect");
    }

    // ------------------------------------------------------------------ the item verifier

    @Test
    void theTriggeringItemVerifierScansTheItemAndChecksTheInductionFlag() throws Exception {
        Map<String, Map<String, Object>> catalogue = Map.of(
                "5000000000062", Map.of("found", true, "code", "HND-003", "barcode", "5000000000062", "title",
                        "Screwdriver set", "deposit", "0.99", "active", true, "perDay", false, "inductionRequired",
                        false),
                "5000000000086", Map.of("found", true, "code", "LAD-001", "barcode", "5000000000086", "title",
                        "Extension ladder", "deposit", "6.50", "active", true, "perDay", false, "inductionRequired",
                        true));
        Desk desk = new Desk();
        desk.on("lending.deposit", p -> catalogue.getOrDefault(String.valueOf(p.get("barcode")),
                Map.of("found", false)));
        LendingVerifiers.TriggeringItemVerifier verifier = new LendingVerifiers.TriggeringItemVerifier();
        assertEquals("lending.deposit", verifier.id());
        assertEquals(List.of("lending.deposit"), verifier.requires());
        WaiverRuleRef rule = rule("B2W1-MIX", 2, 1, HND_003, LAD_001);
        ItemRef screwdrivers = new ItemRef("HND-003", "5000000000062", "Screwdriver set", new BigDecimal("0.99"));
        ItemRef ladder = new ItemRef("LAD-001", "5000000000086", "Extension ladder", new BigDecimal("6.50"));
        VerifierResult ok = verifier.verify(new ItemsTriggering(rule, null, null), screwdrivers, desk.ctx());
        assertTrue(ok.passed(), ok.reason());
        assertEquals("a scan of 5000000000062 finds HND-003 at 0.99, active, counted, no induction check",
                ok.reason());
        assertEquals("a scan of 5000000000086 finds LAD-001 at 6.50, active, counted, induction check",
                verifier.verify(new ItemsTriggering(rule, null, true), ladder, desk.ctx()).reason());
        assertEquals("the desk asks for the induction check when it lends LAD-001",
                verifier.verify(new ItemsTriggering(rule, null, null), ladder, desk.ctx()).reason());
        assertEquals("the desk lends HND-003 without the induction check, which the query asks for",
                verifier.verify(new ItemsTriggering(rule, null, true), screwdrivers, desk.ctx()).reason());
        assertEquals("the desk lends HND-003 counted, not per day",
                verifier.verify(new ItemsTriggering(rule, true, null), screwdrivers, desk.ctx()).reason());
        desk.params.clear();
        assertEquals("item HND-003 is not one that rule B2W1-OTHER covers", verifier.verify(new ItemsTriggering(
                rule("B2W1-OTHER", 2, 1, HND_001), null, null), screwdrivers, desk.ctx()).reason());
        assertEquals("the query names no rule", verifier.verify(new ItemsTriggering(null, null, null), screwdrivers,
                desk.ctx()).reason());
        assertEquals(List.of(), desk.params, "a candidate outside the rule is refused before any scan");
        assertEquals("a scan of 1 finds no item", verifier.verify(new ItemsTriggering(rule("B2W1-X", 2, 1,
                item("HND-003", "1", "x", "0.99", false, false)), null, null), new ItemRef("HND-003", "1", "x",
                new BigDecimal("0.99")), desk.ctx()).reason());
    }

    // ------------------------------------------------------------------ the cross-check

    private static CrossCheckResult crossCheck(Desk desk, List<LoanLineRef> lines, String oracleAmount,
                                               String tolerance) throws Exception {
        return new LendingCrossChecks.WaiverEvaluate().check(new ExpectedWaiver.Input(HAND, lines),
                new ExpectedWaiver.Waiver("B2W1-HAND", new BigDecimal(oracleAmount), 1),
                tolerance == null ? null : new BigDecimal(tolerance), desk.ctx());
    }

    @Test
    void theCrossCheckAgreesWhenTheDesksDryRunWaivesTheOraclesAmount() throws Exception {
        Desk desk = dryRun(ordered("B2W1-HAND", "0.99"));
        CrossCheckResult agreed = crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "0.99", "0.00");
        assertTrue(agreed.agreed(), agreed.reason());
        assertEquals("the desk waives 0.99 for 3 units of HND-003 under B2W1-HAND on 2026-10-03; the oracle expects"
                + " 0.99", agreed.reason());
        assertEquals(Map.of("ruleCode", "B2W1-HAND", "amount", "0.99", "date", TODAY, "waivers",
                List.of(Map.of("ruleCode", "B2W1-HAND", "amount", "0.99"))), agreed.actual());
        assertEquals(List.of(Map.of("lines", List.of(Map.of("code", "HND-003", "qty", 3L)))), desk.params,
                "the oracle's lines, no date: the desk's business date");
        assertEquals(List.of("lending.waiver.evaluate"), desk.methods);
        assertEquals(1, ((List<?>) agreed.evidence().get("calls")).size());
        assertTrue(crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "0.990", null).agreed(),
                "no tolerance: exact, as numbers");
    }

    @Test
    void theCrossCheckDisagreesWithAWrongOracleAndNamesBothAmounts() throws Exception {
        Desk desk = dryRun(ordered("B2W1-HAND", "0.99"));
        CrossCheckResult wrong = crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "1.98", "0.00");
        assertFalse(wrong.agreed());
        assertEquals("the desk waives 0.99 for 3 units of HND-003 under B2W1-HAND on 2026-10-03; the oracle expects"
                + " 1.98", wrong.reason());
        assertEquals("0.99", ((Map<?, ?>) wrong.actual()).get("amount"), "the desk's amount");
        assertEquals(TODAY, ((Map<?, ?>) wrong.actual()).get("date"), "and its date");
        assertFalse(crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "1.00", null).agreed(), "exact");
    }

    @Test
    void theCrossCheckToleranceIsAnAbsoluteAmountOfMoney() throws Exception {
        Desk desk = dryRun(ordered("B2W1-HAND", "0.99"));
        CrossCheckResult within = crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "1.00", "0.01");
        assertTrue(within.agreed(), within.reason());
        assertEquals("the desk waives 0.99 for 3 units of HND-003 under B2W1-HAND on 2026-10-03; the oracle expects"
                + " 1.00 (tolerance 0.01)", within.reason());
        assertTrue(crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "0.98", "0.01").agreed(), "either way");
        assertFalse(crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "1.00", "0.009").agreed());
        assertFalse(crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "1.01", "0.01").agreed());
    }

    @Test
    void aRuleTheDeskDoesNotWaiveUnderCountsAsZero() throws Exception {
        Desk desk = dryRun(ordered("B1W1-OTHER", "0.50"));
        CrossCheckResult nothing = crossCheck(desk, List.of(line("HND-003", 2, "0.99")), "0.00", "0.00");
        assertTrue(nothing.agreed(), nothing.reason());
        assertEquals("the desk waives nothing under B2W1-HAND for 2 units of HND-003 on 2026-10-03; the oracle"
                + " expects 0.00", nothing.reason());
        assertEquals("0.00", ((Map<?, ?>) nothing.actual()).get("amount"));
        assertEquals(List.of(Map.of("ruleCode", "B1W1-OTHER", "amount", "0.50")),
                ((Map<?, ?>) nothing.actual()).get("waivers"), "the whole answer");
        assertFalse(crossCheck(desk, List.of(line("HND-003", 3, "0.99")), "0.99", "0.00").agreed());
    }

    @Test
    void theCrossCheckSendsEveryLineInOrder() throws Exception {
        Desk desk = dryRun(ordered("B2W1-HAND", "0.99"));
        CrossCheckResult two = crossCheck(desk, List.of(line("HND-003", 1, "0.99"), line("HND-001", 2, "1.10")),
                "0.99", null);
        assertEquals("the desk waives 0.99 for 1 unit of HND-003 and 2 units of HND-001 under B2W1-HAND on"
                + " 2026-10-03; the oracle expects 0.99", two.reason());
        assertEquals(Map.of("lines", List.of(Map.of("code", "HND-003", "qty", 1L), Map.of("code", "HND-001", "qty",
                2L))), desk.params.get(0));
        assertTrue(crossCheck(desk, List.of(line("HND-003", 1, "0.99"), line("HND-001", 1, "1.10"),
                line("HND-002", 1, "1.45")), "0.99", null).reason().contains("for 1 unit of HND-003, 1 unit of"
                + " HND-001 and 1 unit of HND-002 under"));
        assertThrows(IllegalArgumentException.class, () -> new LendingCrossChecks.WaiverEvaluate().check(
                new ExpectedWaiver.Input(HAND, null), new ExpectedWaiver.Waiver("B2W1-HAND", BigDecimal.ONE, 1), null,
                desk.ctx()));
    }
}
