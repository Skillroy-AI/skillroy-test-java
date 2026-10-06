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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.samples.lending.Catalogue;
import ai.skillroy.testjava.samples.lending.Item;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.LoanLine;
import ai.skillroy.testjava.samples.lending.LoanWaiver;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.samples.lending.WaiverEngine;
import ai.skillroy.testjava.samples.lending.WaiverRule;
import ai.skillroy.testjava.samples.lending.pack.ExpectedWaiver;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ActiveWaiverRuleWithTriggers;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LoanLineRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverRuleRef;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roadmap's exit criterion of Phase 3 (phase3-contracts.md 11.1 item 6): the committed oracle
 * {@code ExpectedWaiver} equals the desk's own computation for every seeded waiver rule. For every
 * {@code waiver_rule} row of both seeds, on dates inside its window and with the rule made effective for the purpose
 * (a test copy with {@code active} true), for loans of one, two and three groups, one unit short of a group, and
 * mixed items of the rule in every order (with a counted item and a per-day item the rule does not cover among them),
 * the oracle's amount and rule code equal {@code WaiverEngine.evaluate} on the same lines and
 * {@code WaiverEngine.dryRun}, the computation the cross-check asks for. On a date outside its window the dry run
 * waives nothing, and the pack's verifier, asking the desk's dry run on such a business date, reports the failure.
 *
 * <p>The oracle's rule is built the way its resolver reads it: the items the rule covers that the desk can lend
 * (active, with a barcode), cheapest first, ties by code. Headless: the sample's database in this JVM.
 */
class WaiverOracleAgreementTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final LocalDate SEEDED = LocalDate.now();

    private LendingDb db;

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    private record Desk(WaiverEngine engine, Catalogue catalogue) {
    }

    private Desk open(Env env) throws Exception {
        db = LendingDb.create("p3agree" + env.seed + DATABASES.incrementAndGet(), Seed.parse(env.seed), SEEDED);
        return new Desk(WaiverEngine.load(db.connection()), Catalogue.load(db.connection()));
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void theOracleEqualsTheDesksComputationForEverySeededRule(Env env) throws Exception {
        Desk desk = open(env);
        List<WaiverRule> rules = desk.engine().waiverRules();
        assertEquals(3, rules.size(), "every seed has three waiver rules: " + rules);
        int cases = 0;
        int waiving = 0;
        for (WaiverRule seeded : rules) {
            WaiverRule effective = activeCopy(seeded);
            WaiverEngine alone = new WaiverEngine(List.of(effective));
            WaiverRuleRef ref = asResolved(seeded, desk.catalogue());
            assertFalse(ref.items().isEmpty(), seeded + " covers an item the desk can lend");
            for (LocalDate date : inside(seeded)) {
                assertTrue(effective.effectiveOn(date), seeded + " on " + date);
                for (List<LoanLineRef> lines : loans(ref, desk.catalogue())) {
                    String expected = agree(alone, desk.catalogue(), ref, lines, date);
                    if (!expected.endsWith(" 0.00")) {
                        waiving++;
                    }
                    cases++;
                }
            }
        }
        assertTrue(cases > 100, "loans checked: " + cases);
        assertTrue(waiving > cases / 3, "most of them waive something: " + waiving + " of " + cases);
    }

    /**
     * The oracle, the desk's evaluation of the same loan lines and its dry run of them agree, amount and rule code.
     *
     * @return {@code "<rule code> <amount>"}, for counting
     */
    private static String agree(WaiverEngine engine, Catalogue catalogue, WaiverRuleRef ref, List<LoanLineRef> lines,
                                LocalDate date) {
        ExpectedWaiver.Waiver oracle = new ExpectedWaiver().compute(new ExpectedWaiver.Input(ref, lines));
        List<LoanLine> loan = new ArrayList<>();
        Map<String, Integer> units = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            LoanLineRef line = lines.get(i);
            Item item = catalogue.findByCode(line.code());
            loan.add(LoanLine.of(i + 1, item, BigDecimal.valueOf(line.qty()), false));
            if (!item.perDay()) {
                units.merge(item.code(), (int) line.qty(), Integer::sum);
            }
        }
        String what = ref.code() + " on " + date + " for " + lines;
        String evaluated = text(ref.code(), engine.evaluate(loan, date));
        assertEquals(oracle.ruleCode() + " " + oracle.amount().toPlainString(), evaluated, what);
        assertEquals(evaluated, text(ref.code(), engine.dryRun(units, catalogue, date)), "the dry run: " + what);
        return evaluated;
    }

    /** {@code "<rule code> <amount>"} of the desk's waivers under one rule: {@code 0.00} when there is none. */
    private static String text(String ruleCode, List<LoanWaiver> waivers) {
        assertTrue(waivers.size() <= 1, "one rule, at most one waiver: " + waivers);
        if (waivers.isEmpty()) {
            return ruleCode + " 0.00";
        }
        assertEquals(ruleCode, waivers.get(0).ruleCode());
        return ruleCode + " " + waivers.get(0).amount().toPlainString();
    }

    /**
     * Outside its window, the desk's dry run under the rule waives nothing; for the seeded rules that are active, the
     * pack's verifier, asking a desk whose business date is that day, says so (and passes on a day inside the window).
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void outsideItsWindowTheDryRunWaivesNothingAndTheVerifierSaysSo(Env env) throws Exception {
        Desk desk = open(env);
        @SuppressWarnings("unchecked")
        Verifier<ActiveWaiverRuleWithTriggers, WaiverRuleRef> verifier =
                (Verifier<ActiveWaiverRuleWithTriggers, WaiverRuleRef>) Runs.PACK.resolution()
                        .verifier("ActiveWaiverRuleWithTriggers", "lending.waiver.evaluate");
        ActiveWaiverRuleWithTriggers query = new ActiveWaiverRuleWithTriggers(null, null, null, true);
        int outside = 0;
        int refused = 0;
        for (WaiverRule seeded : desk.engine().waiverRules()) {
            WaiverRuleRef ref = asResolved(seeded, desk.catalogue());
            WaiverItem cheapest = ref.items().stream().filter(i -> !i.perDay()).findFirst().orElseThrow();
            Map<String, Integer> oneGroup = Map.of(cheapest.code(), seeded.borrowQty() + seeded.waivedQty());
            WaiverEngine alone = new WaiverEngine(List.of(activeCopy(seeded)));
            for (LocalDate date : outside(seeded)) {
                assertEquals(List.of(), alone.dryRun(oneGroup, desk.catalogue(), date), seeded + " on " + date);
                outside++;
                if (!seeded.active()) {
                    continue;
                }
                VerifierResult result = verifier.verify(query, ref, context(date));
                assertFalse(result.passed(), seeded + " on " + date + ": " + result.reason());
                assertEquals("the desk waives nothing under " + seeded.code() + " for " + oneGroup.get(cheapest.code())
                        + " units of " + cheapest.code(), result.reason());
                assertEquals(date.toString(), result.evidence().get("date"), "the desk's date, as evidence");
                refused++;
            }
            if (seeded.active()) {
                LocalDate inside = inside(seeded).get(0);
                VerifierResult result = verifier.verify(query, ref, context(inside));
                assertTrue(result.passed(), seeded + " on " + inside + ": " + result.reason());
            }
        }
        assertTrue(outside >= 6, "a day before or after the window of every rule: " + outside);
        assertTrue(refused >= 2, "the active rules: " + refused);
    }

    /** What the verifier gets: a desk on this database whose business date is {@code today}. */
    private VerifyContext context(LocalDate today) throws Exception {
        AgentConnection agent = new DeskConnection(db, today);
        return new VerifyContext() {
            @Override
            public AgentConnection agent() {
                return agent;
            }

            @Override
            public DataChannel data() {
                throw new AssertionError("the verifier asks the desk, not the database");
            }

            @Override
            public String envId() {
                return "agreement";
            }

            @Override
            public long remainingMs() {
                return 60_000L;
            }
        };
    }

    // ------------------------------------------------------------------ the rules, dates and loans

    /** The test copy of a seeded rule, made effective for the purpose: {@code active} true, everything else kept. */
    static WaiverRule activeCopy(WaiverRule rule) {
        return new WaiverRule(rule.id(), rule.code(), rule.description(), rule.kind(), rule.borrowQty(),
                rule.waivedQty(), true, rule.startsOn(), rule.endsOn(), rule.itemCodes());
    }

    /** The rule as the resolver reads it: the covered items the desk can lend, cheapest first, ties by code. */
    static WaiverRuleRef asResolved(WaiverRule rule, Catalogue catalogue) {
        List<WaiverItem> items = new ArrayList<>();
        for (String code : rule.itemCodes()) {
            Item item = catalogue.findByCode(code);
            if (item != null && item.active() && item.barcode() != null) {
                items.add(new WaiverItem(item.code(), item.barcode(), item.title(), item.unitDeposit(),
                        item.perDay(), item.inductionRequired()));
            }
        }
        items.sort(Comparator.comparing(WaiverItem::deposit).thenComparing(WaiverItem::code));
        return new WaiverRuleRef(rule.code(), rule.kind(), rule.borrowQty(), rule.waivedQty(), List.copyOf(items));
    }

    /** Days inside the rule's window: its first and last day (a null bound open: the seeding date). */
    static List<LocalDate> inside(WaiverRule rule) {
        List<LocalDate> days = new ArrayList<>();
        days.add(rule.startsOn() != null ? rule.startsOn() : SEEDED);
        if (rule.endsOn() != null && !rule.endsOn().equals(days.get(0))) {
            days.add(rule.endsOn());
        }
        return days;
    }

    /** The day before the window and the day after it, where the window has those bounds. */
    static List<LocalDate> outside(WaiverRule rule) {
        List<LocalDate> days = new ArrayList<>();
        if (rule.startsOn() != null) {
            days.add(rule.startsOn().minusDays(1));
        }
        if (rule.endsOn() != null) {
            days.add(rule.endsOn().plusDays(1));
        }
        return days;
    }

    /**
     * The loans checked for one rule: for each counted item of the rule, one, two and three groups, one unit short of
     * a group, and one unit over; for the rule's items in every order, mixed quantities (some of a group or more,
     * some fewer); and the same mixtures with a counted item and a per-day item the rule does not cover.
     */
    static List<List<LoanLineRef>> loans(WaiverRuleRef rule, Catalogue catalogue) {
        int group = rule.borrowQty() + rule.waivedQty();
        List<WaiverItem> counted = rule.items().stream().filter(i -> !i.perDay()).toList();
        List<List<LoanLineRef>> loans = new ArrayList<>();
        for (WaiverItem item : counted) {
            for (int qty : List.of(group, 2 * group, 3 * group, group - 1, group + 1)) {
                if (qty > 0) {
                    loans.add(List.of(line(item.code(), qty, item.deposit())));
                }
            }
        }
        List<List<Integer>> patterns = List.of(List.of(1, 1, 1), List.of(2, 1, 1), List.of(1, 2, 3),
                List.of(group, group - 1, 1), List.of(3 * group, 1, 2), List.of(group, group, group));
        List<LoanLineRef> others = others(rule, catalogue);
        for (List<WaiverItem> order : permutations(counted)) {
            for (List<Integer> pattern : patterns) {
                List<LoanLineRef> lines = new ArrayList<>();
                for (int i = 0; i < order.size(); i++) {
                    int qty = pattern.get(i % pattern.size());
                    if (qty > 0) {
                        lines.add(line(order.get(i).code(), qty, order.get(i).deposit()));
                    }
                }
                if (!lines.isEmpty()) {
                    loans.add(List.copyOf(lines));
                    List<LoanLineRef> mixed = new ArrayList<>(others);
                    mixed.addAll(lines);
                    loans.add(List.copyOf(mixed));
                }
            }
        }
        return loans;
    }

    /** A counted item and a per-day item that the rule does not cover, one group of each, as loan lines. */
    private static List<LoanLineRef> others(WaiverRuleRef rule, Catalogue catalogue) {
        List<String> covered = rule.items().stream().map(WaiverItem::code).toList();
        List<LoanLineRef> lines = new ArrayList<>();
        Item counted = catalogue.items().stream().filter(i -> i.active() && i.barcode() != null && !i.perDay()
                && !covered.contains(i.code())).findFirst().orElseThrow();
        Item perDay = catalogue.items().stream().filter(i -> i.active() && i.perDay()).findFirst().orElseThrow();
        int group = rule.borrowQty() + rule.waivedQty();
        lines.add(line(counted.code(), group, counted.unitDeposit()));
        lines.add(line(perDay.code(), group, perDay.unitDeposit()));
        return lines;
    }

    private static LoanLineRef line(String code, long qty, BigDecimal deposit) {
        return new LoanLineRef(code, qty, deposit);
    }

    static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(List.copyOf(items));
        }
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T first = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> one = new ArrayList<>();
                one.add(first);
                one.addAll(tail);
                out.add(List.copyOf(one));
            }
        }
        return out;
    }
}
