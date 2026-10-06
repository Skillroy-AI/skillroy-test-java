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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.samples.lending.Catalogue;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.samples.lending.WaiverEngine;
import ai.skillroy.testjava.samples.lending.WaiverRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.agent.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lending.waiver.evaluate} (Phase 3 contract sections 10.2 and 11.1 item 5): the desk's own dry run, asked of
 * running headless desks of both seeds, so of the application's real {@code WaiverEngine} and {@code Catalogue}. The
 * desks run on a fixed clock, so that the seeded rules' dates (relative to the day a desk is seeded) and every date in
 * an answer are known. What the seeds hold ({@code SeedData}):
 *
 * <ul>
 *   <li>seed A: {@code B2W1-HAND} (rule 1, borrow 2 waive 1, from 30 days before to 30 days after the seeding day;
 *       {@code HND-003} 0.99, {@code HND-001} 1.10, {@code HND-002} 1.45), {@code B2W1-GARDEN} (inactive;
 *       {@code YRD-002} 2.95), {@code B1W1-PAINT} (rule 3, borrow 1 waive 1; {@code PNT-001} 4.25; from 60 days
 *       before to the day before the seeding day); the per-day item {@code CLN-001} 1.20;</li>
 *   <li>seed B: {@code B2W1-CYCLE} (rule 21, borrow 2 waive 1, 7 days either side; {@code T1002} 0.79, {@code T1001}
 *       0.89, {@code T1013} 1.15), {@code B2W1-SEALING} (inactive; {@code T1004} 2.80), {@code B3W1-WRENCH} (borrow 3
 *       waive 1; {@code T1005} 2.15, {@code T1006} 3.60; from 10 to 40 days after the seeding day); the per-day item
 *       {@code T1007} 2.40.</li>
 * </ul>
 */
class LendingWaiversTest {

    private static final String WAIVER = "lending.waiver.evaluate";
    /** The day every desk of this test is seeded on and runs on. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC);

    private static final String QTY_RULE = "must be a whole number from 1 to 999";
    private static final String NOT_A_DATE = "is not an ISO-8601 local date such as 2026-10-03";

    /** One seed's effective borrow-2-waive-1 rule, its items (cheapest first) and the amounts it waives. */
    private static final class Seeded {
        String staff;
        String password;
        String rule;
        int ruleId;
        /** Days either side of the seeding day that the rule's dates reach. */
        int reach;
        String cheapest;
        String cheapestBarcode;
        String cheapestDeposit;
        String middle;
        String middleBarcode;
        String middleDeposit;
        String dearest;
        String dearestBarcode;
        /** Six units of cheapest, two groups. */
        String twoGroups;
        /** Nine units of cheapest, three groups. */
        String threeGroups;
        /** Four of the dearest, one of the middle and one of the cheapest: the two lowest deposits. */
        String mixed;
        String perDay;
        /** A counted item in no rule, and a retired one (in no rule either). */
        String uncovered;
        String retired;
        String inactiveRule;
        String inactiveRuleItem;
        String inactiveRuleItemDeposit;
    }

    private static Seeded seeded(Seed seed) {
        Seeded s = new Seeded();
        if (seed == Seed.A) {
            s.staff = "100";
            s.password = "1111";
            s.rule = "B2W1-HAND";
            s.ruleId = 1;
            s.reach = 30;
            s.cheapest = "HND-003";
            s.cheapestBarcode = "5000000000062";
            s.cheapestDeposit = "0.99";
            s.middle = "HND-001";
            s.middleBarcode = "5000000000048";
            s.middleDeposit = "1.10";
            s.dearest = "HND-002";
            s.dearestBarcode = "5000000000055";
            s.twoGroups = "1.98";
            s.threeGroups = "2.97";
            s.mixed = "2.09";
            s.perDay = "CLN-001";
            s.uncovered = "STK-001";
            s.retired = "TIL-001";
            s.inactiveRule = "B2W1-GARDEN";
            s.inactiveRuleItem = "YRD-002";
            s.inactiveRuleItemDeposit = "2.95";
        } else {
            s.staff = "4711";
            s.password = "9182";
            s.rule = "B2W1-CYCLE";
            s.ruleId = 21;
            s.reach = 7;
            s.cheapest = "T1002";
            s.cheapestBarcode = "4006000000028";
            s.cheapestDeposit = "0.79";
            s.middle = "T1001";
            s.middleBarcode = "4006000000011";
            s.middleDeposit = "0.89";
            s.dearest = "T1013";
            s.dearestBarcode = "4006000000127";
            s.twoGroups = "1.58";
            s.threeGroups = "2.37";
            s.mixed = "1.68";
            s.perDay = "T1007";
            s.uncovered = "T1003";
            s.retired = "T1012";
            s.inactiveRule = "B2W1-SEALING";
            s.inactiveRuleItem = "T1004";
            s.inactiveRuleItemDeposit = "2.80";
        }
        return s;
    }

    // ------------------------------------------------------------------ what the desk waives

    @ParameterizedTest
    @EnumSource(Seed.class)
    void oneGroupOfTheCheapestItemWaivesOneDeposit(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            long before = desk.ctx.events.lastSeq();
            Map<String, Object> expected = answer(DAY, waiver(s.rule, s.cheapestDeposit));
            assertEquals(expected, desk.ctx.call(WAIVER, lines(line("code", s.cheapest, 3L))));
            assertEquals(expected, desk.ctx.call(WAIVER, lines(line("barcode", s.cheapestBarcode, 3L))),
                    "by barcode, as a scan finds it");
            assertEquals(expected, desk.ctx.call(WAIVER, lines(line("code", s.cheapest.toLowerCase(Locale.ROOT),
                    3L))), "a code ignores case, as the desk's lookup does");
            assertEquals(expected, desk.ctx.call(WAIVER, lines(line("code", s.cheapest, "3"))), "numeric text");
            assertEquals(expected, desk.ctx.call(WAIVER, lines(line("code", s.cheapest, 3.0d))), "a whole double");
            assertEquals(expected, desk.ctx.call(WAIVER, args("lines", Collections.singletonList(
                    line("code", s.cheapest, 3L)), "date", null)), "a null date counts as absent");
            assertEquals(before, desk.ctx.events.lastSeq(), "a dry run publishes nothing");
            assertEquals("SIGNED_OFF", desk.app.engine().status().state().name(), "and changes nothing");
            assertNull(desk.app.engine().currentLoan());
            assertEquals(0, desk.app.printer().count());
        } finally {
            desk.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Seed.class)
    void oneUnitShortOfAGroupWaivesNothing(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.cheapest, 2L))));
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.cheapest, 1L))));
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.dearest, 1L),
                    line("code", s.middle, 1L))), "two units of different items are no group either");
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.uncovered, 5L),
                    line("code", s.cheapest, 2L))), "units of an item the rule does not cover do not count");
        } finally {
            desk.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Seed.class)
    void everyWholeGroupWaivesOneMoreDeposit(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            assertEquals(answer(DAY, waiver(s.rule, s.twoGroups)), desk.ctx.call(WAIVER,
                    lines(line("code", s.cheapest, 6L))), "two groups waive two deposits");
            assertEquals(answer(DAY, waiver(s.rule, s.cheapestDeposit)), desk.ctx.call(WAIVER,
                    lines(line("code", s.cheapest, 5L))), "a group and two units over waive one");
            assertEquals(answer(DAY, waiver(s.rule, s.twoGroups)), desk.ctx.call(WAIVER,
                    lines(line("code", s.cheapest, 8L))), "one short of the third group");
            assertEquals(answer(DAY, waiver(s.rule, s.threeGroups)), desk.ctx.call(WAIVER,
                    lines(line("code", s.cheapest, 9L))));
        } finally {
            desk.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Seed.class)
    void mixedItemsWaiveTheLowestDeposits(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            List<Map<String, Object>> mix = Arrays.asList(line("code", s.dearest, 4L), line("code", s.middle, 1L),
                    line("barcode", s.cheapestBarcode, 1L));
            int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
            for (int[] order : orders) {
                assertEquals(answer(DAY, waiver(s.rule, s.mixed)), desk.ctx.call(WAIVER, lines(mix.get(order[0]),
                        mix.get(order[1]), mix.get(order[2]))), "six units, the two lowest deposits, in order "
                        + Arrays.toString(order));
            }
            assertEquals(answer(DAY, waiver(s.rule, s.middleDeposit)), desk.ctx.call(WAIVER,
                    lines(line("code", s.dearest, 2L), line("code", s.middle, 1L))),
                    "without the cheapest item the lowest deposit is the middle one's");
            assertEquals(answer(DAY, waiver(s.rule, s.cheapestDeposit)), desk.ctx.call(WAIVER,
                    lines(line("code", s.cheapest, 1L), line("barcode", s.cheapestBarcode, 1L),
                            line("code", s.cheapest.toLowerCase(Locale.ROOT), 1L))),
                    "lines of the same item add up, however the item is named");
        } finally {
            desk.close();
        }
    }

    /**
     * The dry run is the computation the desk makes when {@code DUE} is pressed: the same lines entered at the desk
     * give the same waivers on the open loan.
     */
    @ParameterizedTest
    @EnumSource(Seed.class)
    void theDryRunIsWhatTheDeskWaivesWhenDueIsPressed(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            desk.send("Entry", s.staff);
            assertEquals("IDLE", desk.send("Entry", s.password).payload().get("state"));
            desk.send("Key.QTY", "3");
            assertEquals("LOAN", desk.send("Barcode", s.cheapestBarcode).payload().get("state"));
            assertEquals("DEPOSIT", desk.send("Key.DUE", null).payload().get("state"));
            Object dueWaivers = map(desk.ctx.call("lending.loan", args())).get("waivers");
            assertEquals(Collections.singletonList(waiver(s.rule, s.cheapestDeposit)), dueWaivers);
            assertEquals(dueWaivers, map(desk.ctx.call(WAIVER, lines(line("barcode", s.cheapestBarcode, 3L))))
                    .get("waivers"));
            assertEquals("IDLE", desk.send("Key.CANCEL", null).payload().get("state"));

            desk.send("Key.QTY", "4");
            desk.send("Barcode", s.dearestBarcode);
            desk.send("Barcode", s.middleBarcode);
            desk.send("Barcode", s.cheapestBarcode);
            assertEquals("DEPOSIT", desk.send("Key.DUE", null).payload().get("state"));
            dueWaivers = map(desk.ctx.call("lending.loan", args())).get("waivers");
            assertEquals(Collections.singletonList(waiver(s.rule, s.mixed)), dueWaivers);
            assertEquals(dueWaivers, map(desk.ctx.call(WAIVER, lines(line("barcode", s.dearestBarcode, 4L),
                    line("barcode", s.middleBarcode, 1L), line("barcode", s.cheapestBarcode, 1L)))).get("waivers"));
        } finally {
            desk.close();
        }
    }

    // ------------------------------------------------------------------ what a line is

    /**
     * A line is a counted loan line: a return has no way to be written (no such field, no negative quantity), nor has
     * a number of days; a per-day item may be named and is ignored by the rules, as the desk ignores it.
     */
    @ParameterizedTest
    @EnumSource(Seed.class)
    void aReturnCannotBeExpressedAndAPerDayItemIsIgnored(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            String notAField = "is not a field of a line: a line is one counted loan line {code or barcode, qty}, "
                    + "so a return or a number of days cannot be expressed";
            assertInvalid(desk, lines(args("code", s.cheapest, "qty", 3L, "returned", Boolean.TRUE)),
                    "lines[0].returned", notAField);
            assertInvalid(desk, lines(line("code", s.cheapest, -3L)), "lines[0].qty", QTY_RULE);
            assertInvalid(desk, lines(args("code", s.perDay, "qty", 3L, "days", "1.5")), "lines[0].days", notAField);
            assertInvalid(desk, lines(line("code", s.perDay, "1.5")), "lines[0].qty", QTY_RULE);

            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.perDay, 6L))));
            assertEquals(answer(DAY, waiver(s.rule, s.cheapestDeposit)), desk.ctx.call(WAIVER,
                    lines(line("code", s.perDay, 9L), line("code", s.cheapest, 3L))));
        } finally {
            desk.close();
        }
    }

    /**
     * A per-day item that a rule covers is still ignored. No seed has such a rule, so the test database makes one:
     * the seed's effective rule also covers its per-day item, three days of which would be a group of their own if
     * they were counted units.
     */
    @ParameterizedTest
    @EnumSource(Seed.class)
    void aPerDayItemThatARuleCoversIsIgnored(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingDb db = LendingDb.create("lending_agent_waiver_per_day_" + seed, seed, DAY);
        try {
            execute(db, "INSERT INTO waiver_rule_item (rule_id, item_code) VALUES (" + s.ruleId + ", '" + s.perDay
                    + "')");
            WaiverEngine rules = WaiverEngine.load(db.connection());
            Catalogue catalogue = Catalogue.load(db.connection());
            assertTrue(rule(rules, s.rule).itemCodes().contains(s.perDay), "the test's premise: the rule covers it");
            assertTrue(catalogue.findByCode(s.perDay).perDay(), "the test's premise: it is lent by the day");
            assertEquals(answer(DAY), LendingWaivers.evaluate(rules, catalogue, lines(line("code", s.perDay, 3L)),
                    DAY), "three days are no group of three units");
            assertEquals(answer(DAY), LendingWaivers.evaluate(rules, catalogue, lines(line("code", s.cheapest, 2L),
                    line("code", s.perDay, 1L)), DAY), "nor are two units and a day");
            assertEquals(answer(DAY, waiver(s.rule, s.cheapestDeposit)), LendingWaivers.evaluate(rules, catalogue,
                    lines(line("code", s.perDay, 9L), line("code", s.cheapest, 3L)), DAY),
                    "the counted units alone make the group, and only theirs is waived");
        } finally {
            db.close();
        }
    }

    // ------------------------------------------------------------------ which rules apply on which date

    /** The seed's inactive rule waives nothing, although the date is inside its dates; made active, it would. */
    @ParameterizedTest
    @EnumSource(Seed.class)
    void anInactiveRuleWaivesNothing(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            WaiverRule inactive = rule(desk.app.waiverRules(), s.inactiveRule);
            assertFalse(inactive.active(), "the test's premise: inactive");
            assertTrue(!DAY.isBefore(inactive.startsOn()) && !DAY.isAfter(inactive.endsOn()),
                    "the test's premise: the day is inside its dates");
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.inactiveRuleItem, 3L))));
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.inactiveRuleItem, 6L))));
            assertEquals(answer(DAY.plusDays(5)), desk.ctx.call(WAIVER, args("lines", Collections.singletonList(
                    line("code", s.inactiveRuleItem, 3L)), "date", DAY.plusDays(5).toString())));
        } finally {
            desk.close();
        }
        LendingDb db = LendingDb.create("lending_agent_waiver_active_" + seed, seed, DAY);
        try {
            execute(db, "UPDATE waiver_rule SET active = TRUE WHERE code = '" + s.inactiveRule + "'");
            assertEquals(answer(DAY, waiver(s.inactiveRule, s.inactiveRuleItemDeposit)), LendingWaivers.evaluate(
                    WaiverEngine.load(db.connection()), Catalogue.load(db.connection()),
                    lines(line("code", s.inactiveRuleItem, 3L)), DAY), "the control: active, it waives");
        } finally {
            db.close();
        }
    }

    /** The effective rule waives on the first and the last day of its dates and not on the days around them. */
    @ParameterizedTest
    @EnumSource(Seed.class)
    void theEffectiveRuleWaivesOnlyInsideItsDates(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            Map<String, Object> group = line("code", s.cheapest, 3L);
            for (LocalDate inside : Arrays.asList(DAY.minusDays(s.reach), DAY, DAY.plusDays(s.reach))) {
                assertEquals(answer(inside, waiver(s.rule, s.cheapestDeposit)), desk.ctx.call(WAIVER, on(inside,
                        group)), inside.toString());
            }
            for (LocalDate outside : Arrays.asList(DAY.minusDays(s.reach + 1), DAY.plusDays(s.reach + 1))) {
                assertEquals(answer(outside), desk.ctx.call(WAIVER, on(outside, group)), outside.toString());
            }
        } finally {
            desk.close();
        }
    }

    /**
     * Seed A's {@code B1W1-PAINT} ended the day before the desk's day: nothing on that day, its deposit on a date
     * inside its dates. On such a date both of seed A's effective rules apply, and the waivers come in rule-id order,
     * whatever the order of the lines.
     */
    @Test
    void aRuleWhoseDatesAreOverWaivesNothing() throws Exception {
        LendingFixture desk = LendingFixture.start(Seed.A, CLOCK);
        try {
            Map<String, Object> paint = line("code", "PNT-001", 2L);
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(paint)));
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, on(DAY, paint)));
            for (LocalDate inside : Arrays.asList(DAY.minusDays(1), DAY.minusDays(60))) {
                assertEquals(answer(inside, waiver("B1W1-PAINT", "4.25")), desk.ctx.call(WAIVER, on(inside, paint)),
                        inside.toString());
            }
            assertEquals(answer(DAY.minusDays(61)), desk.ctx.call(WAIVER, on(DAY.minusDays(61), paint)));

            Map<String, Object> hand = line("code", "HND-003", 3L);
            Map<String, Object> both = answer(DAY.minusDays(1), waiver("B2W1-HAND", "0.99"),
                    waiver("B1W1-PAINT", "4.25"));
            assertEquals(both, desk.ctx.call(WAIVER, on(DAY.minusDays(1), hand, paint)));
            assertEquals(both, desk.ctx.call(WAIVER, on(DAY.minusDays(1), paint, hand)), "rule-id order");
        } finally {
            desk.close();
        }
    }

    /** Seed B's {@code B3W1-WRENCH} (borrow 3, waive 1) begins ten days after the desk's day. */
    @Test
    void aRuleWhoseDatesHaveNotBegunWaivesNothing() throws Exception {
        LendingFixture desk = LendingFixture.start(Seed.B, CLOCK);
        try {
            Map<String, Object> wrenches = line("code", "T1005", 4L);
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(wrenches)));
            assertEquals(answer(DAY.plusDays(9)), desk.ctx.call(WAIVER, on(DAY.plusDays(9), wrenches)));
            for (LocalDate inside : Arrays.asList(DAY.plusDays(10), DAY.plusDays(40))) {
                assertEquals(answer(inside, waiver("B3W1-WRENCH", "2.15")), desk.ctx.call(WAIVER, on(inside,
                        wrenches)), inside.toString());
            }
            assertEquals(answer(DAY.plusDays(41)), desk.ctx.call(WAIVER, on(DAY.plusDays(41), wrenches)));
            assertEquals(answer(DAY.plusDays(10), waiver("B3W1-WRENCH", "2.15")), desk.ctx.call(WAIVER,
                    on(DAY.plusDays(10), line("code", "T1006", 3L), line("code", "T1005", 1L))),
                    "four units of two items: the lower deposit");
        } finally {
            desk.close();
        }
    }

    /** Without a date the rules are evaluated on the desk's business date, which follows the desk's clock. */
    @Test
    void withoutADateTheDesksBusinessDateIsUsed() throws Exception {
        TestClock clock = new TestClock(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC);
        LendingFixture desk = LendingFixture.start(Seed.A, clock);
        try {
            Map<String, Object> group = lines(line("code", "HND-003", 3L));
            assertEquals(answer(DAY, waiver("B2W1-HAND", "0.99")), desk.ctx.call(WAIVER, group));
            clock.advanceDays(30);
            assertEquals(answer(DAY.plusDays(30), waiver("B2W1-HAND", "0.99")), desk.ctx.call(WAIVER, group),
                    "the last day of the rule's dates");
            clock.advanceDays(1);
            assertEquals(answer(DAY.plusDays(31)), desk.ctx.call(WAIVER, group), "the day after");
            assertEquals(DAY.plusDays(31).toString(), desk.ctx.spi.first(AppStateProvider.class).appState()
                    .get("today"), "the date app.state reports");
        } finally {
            desk.close();
        }
    }

    // ------------------------------------------------------------------ the parameter rules

    @ParameterizedTest
    @EnumSource(Seed.class)
    void anUnknownItemIsInvalidParams(Seed seed) throws Exception {
        Seeded s = seeded(seed);
        LendingFixture desk = LendingFixture.start(seed, CLOCK);
        try {
            assertInvalid(desk, lines(line("code", "NOPE-1", 1L)), "lines[0].code", "unknown item");
            assertInvalid(desk, lines(line("barcode", "0000000000000", 1L)), "lines[0].barcode", "unknown item");
            assertInvalid(desk, lines(line("code", s.cheapest, 3L), line("code", "NOPE-1", 1L)), "lines[1].code",
                    "unknown item");
            assertInvalid(desk, lines(line("barcode", s.cheapest, 3L)), "lines[0].barcode", "unknown item");
            assertInvalid(desk, lines(line("code", s.cheapestBarcode, 3L)), "lines[0].code", "unknown item");
            assertInvalid(desk, lines(line("code", "", 1L)), "lines[0].code", "unknown item");
            assertEquals(answer(DAY), desk.ctx.call(WAIVER, lines(line("code", s.retired, 3L))),
                    "a retired item is known; no rule covers the seeds' retired items");
        } finally {
            desk.close();
        }
    }

    @Test
    void theParameterRules() throws Exception {
        LendingFixture desk = LendingFixture.start(Seed.A, CLOCK);
        try {
            Map<String, Object> group = line("code", "HND-003", 3L);
            String missing = "is missing: give 1 to 50 lines, each {code or barcode, qty}";
            String notAnArray = "must be an array of 1 to 50 lines, each {code or barcode, qty}";
            assertInvalid(desk, args(), "lines", missing);
            assertInvalid(desk, args("lines", null), "lines", missing);
            assertInvalid(desk, args("lines", "HND-003"), "lines", notAnArray);
            assertInvalid(desk, args("lines", group), "lines", notAnArray);
            assertInvalid(desk, args("lines", Collections.emptyList()), "lines", "must hold 1 to 50 lines, not 0");
            List<Object> fifty = new ArrayList<Object>(Collections.nCopies(50, line("code", "HND-003", 1L)));
            assertEquals(answer(DAY, waiver("B2W1-HAND", "15.84")), desk.ctx.call(WAIVER, args("lines", fifty)),
                    "fifty lines are the most: fifty units, sixteen groups");
            assertEquals(answer(DAY, waiver("B2W1-HAND", "16483.50")), desk.ctx.call(WAIVER, args("lines",
                    Collections.nCopies(50, line("code", "HND-003", 999L)))), "the largest call: 49950 units");
            fifty.add(line("code", "HND-003", 1L));
            assertInvalid(desk, args("lines", fifty), "lines", "must hold 1 to 50 lines, not 51");

            String shape = "must be an object {code or barcode, qty}";
            assertInvalid(desk, args("lines", Collections.singletonList("HND-003")), "lines[0]", shape);
            assertInvalid(desk, args("lines", Arrays.asList(group, null)), "lines[1]", shape);
            assertInvalid(desk, args("lines", Collections.singletonList(Arrays.asList("HND-003", 3L))), "lines[0]",
                    shape);

            assertInvalid(desk, lines(args("code", "HND-003", "barcode", "5000000000062", "qty", 3L)),
                    "lines[0].barcode", "must not be given together with code: give exactly one of code and barcode");
            assertInvalid(desk, lines(args("qty", 3L)), "lines[0].code",
                    "is missing: give exactly one of code and barcode");
            assertInvalid(desk, lines(args("code", null, "barcode", null, "qty", 3L)), "lines[0].code",
                    "is missing: give exactly one of code and barcode");
            assertEquals(answer(DAY, waiver("B2W1-HAND", "0.99")), desk.ctx.call(WAIVER, lines(args("code", null,
                    "barcode", "5000000000062", "qty", 3L))), "a null field counts as absent");
            assertInvalid(desk, lines(args("code", args("value", "HND-003"), "qty", 3L)), "lines[0].code",
                    "must be a string");
            assertInvalid(desk, lines(args("barcode", Collections.singletonList("5000000000062"), "qty", 3L)),
                    "lines[0].barcode", "must be a string");
            assertInvalid(desk, lines(args("code", "HND-003", "qty", 3L, "item", "HND-001")), "lines[0].item",
                    "is not a field of a line: a line is one counted loan line {code or barcode, qty}, so a return "
                            + "or a number of days cannot be expressed");
            assertEquals(answer(DAY, waiver("B2W1-HAND", "0.99")), desk.ctx.call(WAIVER, lines(args("code",
                    "HND-003", "qty", 3L, "item", null))), "a field given as null counts as absent");

            assertInvalid(desk, lines(args("code", "HND-003")), "lines[0].qty",
                    "is missing: give a whole number from 1 to 999");
            for (Object bad : Arrays.<Object>asList(0L, -1L, 1000L, Long.MAX_VALUE, 2.5d, "2.5", "three", "",
                    Boolean.TRUE, args(), Collections.singletonList(3L))) {
                assertInvalid(desk, lines(line("code", "HND-003", bad)), "lines[0].qty", QTY_RULE);
            }
            for (Object good : Arrays.<Object>asList(1L, 999L, Integer.valueOf(7), "7", 7.0d)) {
                assertEquals(DAY.toString(), map(desk.ctx.call(WAIVER, lines(line("code", "HND-003", good))))
                        .get("date"), String.valueOf(good));
            }
            assertInvalid(desk, lines(group, line("code", "HND-001", 0L)), "lines[1].qty", QTY_RULE);

            for (String bad : Arrays.asList("2026-13-01", "03/10/2026", "2026-10-3", "", "2026-10-03T10:00",
                    "tomorrow")) {
                assertInvalid(desk, on(bad, group), "date", NOT_A_DATE);
            }
            assertInvalid(desk, args("lines", Collections.singletonList(group), "date", 20261003L), "date",
                    NOT_A_DATE);
            assertInvalid(desk, args("lines", Collections.singletonList(group), "date", args()), "date",
                    "must be a string: an ISO-8601 local date such as 2026-10-03");
            assertEquals("2026-10-04", map(desk.ctx.call(WAIVER, on("2026-10-04", group))).get("date"));

            assertInvalid(desk, args("lines", Collections.singletonList(group), "day", "2026-10-04"), "day",
                    "is not a parameter of lending.waiver.evaluate: give lines and, optionally, date");
            assertEquals(answer(DAY, waiver("B2W1-HAND", "0.99")), desk.ctx.call(WAIVER, args("lines",
                    Collections.singletonList(group), "day", null)), "a parameter given as null counts as absent");
            assertEquals(answer(DAY, waiver("B2W1-HAND", "0.99")), LendingWaivers.evaluate(desk.app.waiverRules(),
                    desk.app.catalogue(), args("lines", Collections.singletonList(group), "_meta",
                            args("timeoutMs", 100L)), DAY), "the dispatcher's _meta is left alone");
            assertInvalid(desk, args("lines", Collections.singletonList(line("code", "NOPE-1", 1L)), "date", "x"),
                    "lines[0].code", "unknown item");
        } finally {
            desk.close();
        }
    }

    // ------------------------------------------------------------------ the helper without an agent

    /**
     * {@link LendingWaivers#evaluate} is what a test connection without an agent calls: loaded by a class loader that
     * sees the module's classes, the protocol and the application but nothing of the agent (as on a controller's
     * class path), it answers as the primitive does, errors included.
     */
    @Test
    void theHelperNeedsNothingOfTheAgent() throws Exception {
        LendingFixture desk = LendingFixture.start(Seed.A, CLOCK);
        URL classes = LendingWaivers.class.getProtectionDomain().getCodeSource().getLocation();
        final NoAgentLoader loader = new NoAgentLoader(classes, LendingWaiversTest.class.getClassLoader());
        try {
            Class<?> copy = Class.forName(LendingWaivers.class.getName(), true, loader);
            assertNotSame(LendingWaivers.class, copy, "the test's premise: the module's class, loaded once more");
            assertSame(loader, copy.getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> Class.forName(AgentContext.class.getName(), false,
                    loader), "the test's premise: the agent cannot be seen");
            final Method evaluate = copy.getMethod("evaluate", WaiverEngine.class, Catalogue.class, Map.class,
                    LocalDate.class);
            final WaiverEngine rules = desk.app.waiverRules();
            final Catalogue catalogue = desk.app.catalogue();
            Map<String, Object> group = lines(line("code", "HND-003", 3L));
            assertEquals(desk.ctx.call(WAIVER, group), evaluate.invoke(null, rules, catalogue, group, DAY));
            final Map<String, Object> unknown = lines(line("code", "NOPE-1", 1L));
            InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                    () -> evaluate.invoke(null, rules, catalogue, unknown, DAY));
            assertTrue(failed.getCause() instanceof RpcException, String.valueOf(failed.getCause()));
            assertEquals(desk.ctx.fails(WAIVER, unknown).error(), ((RpcException) failed.getCause()).error());
        } finally {
            loader.close();
            desk.close();
        }
    }

    /**
     * Loads the lending module's own package itself and refuses every class of the agent; everything else (the
     * protocol, the application, the JDK) comes from the parent.
     */
    private static final class NoAgentLoader extends URLClassLoader {
        private static final String MODULE = LendingWaivers.class.getPackage().getName() + ".";
        private static final String AGENT = "ai.skillroy.testjava.agent.";

        NoAgentLoader(URL classes, ClassLoader parent) {
            super(new URL[] {classes}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith(AGENT)) {
                    throw new ClassNotFoundException(name + " (the agent is not on this class path)");
                }
                if (!name.startsWith(MODULE)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> line(String field, String value, Object qty) {
        return args(field, value, "qty", qty);
    }

    @SafeVarargs
    private static Map<String, Object> lines(Map<String, Object>... lines) {
        return args("lines", Arrays.asList(lines));
    }

    @SafeVarargs
    private static Map<String, Object> on(Object date, Map<String, Object>... lines) {
        return args("lines", Arrays.asList(lines), "date", date instanceof LocalDate ? date.toString() : date);
    }

    private static Map<String, Object> waiver(String ruleCode, String amount) {
        return args("ruleCode", ruleCode, "amount", amount);
    }

    @SafeVarargs
    private static Map<String, Object> answer(LocalDate date, Map<String, Object>... waivers) {
        return args("date", date.toString(), "waivers", Arrays.asList(waivers));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static void assertInvalid(LendingFixture desk, Map<String, Object> params, String param, String reason)
            throws Exception {
        RpcException e = desk.ctx.fails(WAIVER, params);
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code(), String.valueOf(params));
        assertEquals(param, e.error().data().get("param"), String.valueOf(params));
        assertEquals(reason, e.error().data().get("reason"), String.valueOf(params));
    }

    private static WaiverRule rule(WaiverEngine rules, String code) {
        for (WaiverRule rule : rules.waiverRules()) {
            if (rule.code().equals(code)) {
                return rule;
            }
        }
        throw new AssertionError("no waiver rule " + code);
    }

    private static void execute(LendingDb db, String sql) throws Exception {
        Statement st = db.connection().createStatement();
        try {
            assertEquals(1, st.executeUpdate(sql), sql);
        } finally {
            st.close();
        }
    }
}
