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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ActionKey;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LendableItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LimitCondition;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffWithPermission;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.SystemLimit;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialIndex;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.FingerprintContext;
import ai.skillroy.testjava.scenario.resolve.NumberRange;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.RecordCodec;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's resolution model on its own (phase2-contracts.md 13.3, 14.1 item 8): the catalogue, the oracles, the
 * resolvers over canned rows and the verifiers over a scripted desk. The same code against the real schema and the
 * desk's real access rules is tested in the scenarios module ({@code PackOnRealSchemaTest}).
 */
class LendingResolutionTest {

    // ------------------------------------------------------------------ catalogue

    @Test
    void theCatalogueBuildsAndIsTheSameOnEveryCall() {
        LendingPack pack = new LendingPack();
        ResolutionCatalogue catalogue = pack.resolution();
        assertSame(catalogue, pack.resolution(), "built once");
        assertSame(catalogue, new LendingPack().resolution());
        ResolutionCatalogue again = LendingPack.catalogue();
        assertEquals(catalogue.kinds(), again.kinds(), "a fresh build gives the same catalogue");
        assertEquals(List.of("SystemLimit", "LendableItem", "StaffWithPermission", "ActionKey",
                "ActiveWaiverRuleWithTriggers", "ItemsTriggering"), List.copyOf(catalogue.kinds()));
        assertEquals(List.of("ExpectedAuthorisation", "Money.negate", "LoanFromTrigger", "ExpectedWaiver",
                "ExpectedTotals"), List.copyOf(catalogue.oracleFns()));
        assertEquals(catalogue.oracleFns(), again.oracleFns());
        Map<String, List<String>> resolvers = new LinkedHashMap<>();
        Map<String, List<String>> verifiers = new LinkedHashMap<>();
        for (String kind : catalogue.kinds()) {
            resolvers.put(kind, catalogue.resolvers(kind).stream().map(r -> r.id() + "/" + r.strategy()).toList());
            verifiers.put(kind, catalogue.verifiers(kind).stream().map(v -> v.id() + " " + v.requires()).toList());
            assertEquals(resolvers.get(kind), again.resolvers(kind).stream().map(r -> r.id() + "/" + r.strategy())
                    .toList());
            assertEquals(verifiers.get(kind), again.verifiers(kind).stream().map(v -> v.id() + " " + v.requires())
                    .toList());
        }
        assertEquals(Map.of(
                "SystemLimit", List.of("lending.sql.SystemLimit/CANONICAL_SQL"),
                "LendableItem", List.of("lending.sql.LendableItem/CANONICAL_SQL"),
                "StaffWithPermission", List.of("lending.sql.StaffWithPermission/CANONICAL_SQL"),
                "ActionKey", List.of("lending.java.ActionKey/CANONICAL_JAVA"),
                "ActiveWaiverRuleWithTriggers", List.of("lending.sql.ActiveWaiverRuleWithTriggers/CANONICAL_SQL"),
                "ItemsTriggering", List.of("lending.java.ItemsTriggering/CANONICAL_JAVA")), resolvers);
        assertEquals(Map.of(
                "SystemLimit", List.of("lending.limits [lending.limits]"),
                "LendableItem", List.of("lending.deposit [lending.deposit]"),
                "StaffWithPermission", List.of("lending.access.check [lending.access.check]"),
                "ActionKey", List.of("swing.find [swing.tree]"),
                "ActiveWaiverRuleWithTriggers", List.of("lending.waiver.evaluate [lending.waiver.evaluate]"),
                "ItemsTriggering", List.of("lending.deposit [lending.deposit]")), verifiers);
        assertEquals(new LendingPack().fingerprinter().getClass(), LendingFingerprinter.class);
    }

    /** One cross-check, for ExpectedWaiver only, the same on every build (phase3-contracts.md 10.3, 11.1 item 5). */
    @Test
    void theCatalogueHoldsTheWaiverCrossCheckForExpectedWaiverOnly() {
        ResolutionCatalogue catalogue = new LendingPack().resolution();
        CrossCheck<?, ?> check = catalogue.crossCheck("lending.waiver.evaluate");
        assertTrue(check instanceof LendingCrossChecks.WaiverEvaluate, String.valueOf(check));
        assertEquals("ExpectedWaiver", check.oracleFn());
        assertEquals(List.of("lending.waiver.evaluate"), check.requires());
        assertTrue(check.acceptsTolerance());
        assertEquals(catalogue.oracle("ExpectedWaiver").inputType(), check.inputType());
        assertEquals(catalogue.oracle("ExpectedWaiver").outputType(), check.outputType());
        for (String fn : catalogue.oracleFns()) {
            assertEquals(fn.equals("ExpectedWaiver") ? List.of(check) : List.of(), catalogue.crossChecks(fn), fn);
        }
        ResolutionCatalogue again = LendingPack.catalogue();
        assertEquals(List.of("lending.waiver.evaluate"), again.crossChecks("ExpectedWaiver").stream()
                .map(CrossCheck::id).toList(), "a fresh build holds the same check");
        assertSame(catalogue, new LendingPack().resolution(), "built once");
    }

    /**
     * A variant pack overlays the waiver oracle (decision D24, D46): its types equal the real one's, so the catalogue
     * still builds with the cross-check that is declared for the function.
     */
    @Test
    void aVariantCatalogueReplacesTheWaiverOracleAndKeepsTheCrossCheck() {
        Oracle<ExpectedWaiver.Input, ExpectedWaiver.Waiver> other = new Oracle<>() {
            @Override
            public String fn() {
                return "ExpectedWaiver";
            }

            @Override
            public ExpectedWaiver.Waiver compute(ExpectedWaiver.Input input) {
                return new ExpectedWaiver.Waiver(input.rule().code(), new BigDecimal("1.98"), 2);
            }
        };
        ResolutionCatalogue variant = ResolutionCatalogue.builder(new LendingPack().resolution()).replace(other)
                .build();
        assertSame(other, variant.oracle("ExpectedWaiver"));
        assertEquals(new LendingPack().resolution().crossChecks("ExpectedWaiver"),
                variant.crossChecks("ExpectedWaiver"));
    }

    /** A variant pack can replace a resolver by id and keep the rest (decision D24). */
    @Test
    void aVariantCatalogueReplacesTheItemResolverAndKeepsItsPosition() {
        Resolver<LendableItem, ItemRef> other = new Resolver<>() {
            @Override
            public String id() {
                return "lending.sql.LendableItem";
            }

            @Override
            public Class<LendableItem> queryClass() {
                return LendableItem.class;
            }

            @Override
            public Strategy strategy() {
                return Strategy.CANONICAL_SQL;
            }

            @Override
            public List<ResolverCandidate<ItemRef>> candidates(LendableItem query, ResolveContext ctx) {
                return List.of();
            }
        };
        ResolutionCatalogue variant = ResolutionCatalogue.builder(new LendingPack().resolution()).replace(other)
                .build();
        assertSame(other, variant.resolvers("LendableItem").get(0));
        assertEquals(new LendingPack().resolution().kinds(), variant.kinds());
    }

    /** Every committed statement passes the statement guard and ends in an ORDER BY. */
    @Test
    void theCommittedStatementsPassTheGuard() {
        for (String sql : List.of(LendingResolvers.SYSTEM_LIMIT_SQL, LendingResolvers.LENDABLE_ITEM_SQL,
                LendingResolvers.STAFF_SQL, LendingResolvers.PERMISSIONS_SQL, LendingResolvers.WAIVER_RULE_SQL,
                LendingFingerprinter.COLUMNS_SQL, LendingPack.DatabaseCheck.SQL)) {
            assertEquals(sql, StatementGuard.check(sql), sql);
            if (sql != LendingPack.DatabaseCheck.SQL) {
                assertTrue(sql.contains(" ORDER BY "), sql);
            }
            assertFalse(sql.toLowerCase(Locale.ROOT).contains("password"), "no statement selects the password: "
                    + sql);
        }
    }

    // ------------------------------------------------------------------ oracles

    @Test
    void expectedAuthorisationCoversEachBranchAndBothBoundaries() {
        ExpectedAuthorisation oracle = new ExpectedAuthorisation();
        assertEquals("ExpectedAuthorisation", oracle.fn());
        BigDecimal limit = new BigDecimal("20.00");
        BigDecimal max = new BigDecimal("500.00");
        assertEquals(ExpectedAuthorisation.Outcome.SUPERVISOR_PROMPT, oracle.compute(input(limit, max, "34.99")));
        assertEquals(ExpectedAuthorisation.Outcome.NONE, oracle.compute(input(limit, max, "20.00")), "a == limit");
        assertEquals(ExpectedAuthorisation.Outcome.SUPERVISOR_PROMPT, oracle.compute(input(limit, max, "20.01")));
        assertEquals(ExpectedAuthorisation.Outcome.NONE, oracle.compute(input(limit, max, "19.99")));
        assertEquals(ExpectedAuthorisation.Outcome.SUPERVISOR_PROMPT, oracle.compute(input(limit, max, "500.00")),
                "a == systemMax is still allowed");
        assertEquals(ExpectedAuthorisation.Outcome.NOT_ALLOWED, oracle.compute(input(limit, max, "500.01")));
        assertEquals(ExpectedAuthorisation.Outcome.NOT_ALLOWED, oracle.compute(input(null, max, "649.00")),
                "the system maximum comes first, even for an unlimited staff member");
        assertEquals(ExpectedAuthorisation.Outcome.NONE, oracle.compute(input(null, max, "389.00")), "unlimited");
        assertEquals(ExpectedAuthorisation.Outcome.SUPERVISOR_PROMPT, oracle.compute(input(limit, null, "9999.00")),
                "no system maximum");
        assertEquals(ExpectedAuthorisation.Outcome.NONE, oracle.compute(input(null, null, "9999.00")));
        assertEquals(ExpectedAuthorisation.Outcome.SUPERVISOR_PROMPT, oracle.compute(input(limit, max, "-34.99")),
                "a negative amount (a return line) counts by its absolute value");
        assertEquals(ExpectedAuthorisation.Outcome.NONE, oracle.compute(input(limit, max, "20.004")),
                "the amount is rounded half up to scale 2 first");
        assertEquals(ExpectedAuthorisation.Outcome.SUPERVISOR_PROMPT, oracle.compute(input(limit, max, "20.005")));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(input(limit, max, null)));
        assertThrows(IllegalArgumentException.class, () -> oracle.compute(null));
        assertEquals("SUPERVISOR_PROMPT", RecordCodec.DEFAULT.encode(oracle.compute(input(limit, max, "34.99"))));
    }

    private static ExpectedAuthorisation.Input input(BigDecimal limit, BigDecimal max, String amount) {
        return new ExpectedAuthorisation.Input(limit, max, amount == null ? null : new BigDecimal(amount));
    }

    @Test
    void moneyNegateKeepsTheScale() {
        Oracle<BigDecimal, BigDecimal> negate = new MoneyNegate();
        assertEquals("Money.negate", negate.fn());
        assertEquals("-34.99", RecordCodec.DEFAULT.encode(negate.compute(new BigDecimal("34.99"))));
        assertEquals("-27.50", RecordCodec.DEFAULT.encode(negate.compute(new BigDecimal("27.50"))));
        assertEquals("3.50", RecordCodec.DEFAULT.encode(negate.compute(new BigDecimal("-3.50"))));
        assertEquals("0.00", RecordCodec.DEFAULT.encode(negate.compute(new BigDecimal("0.00"))));
        assertThrows(IllegalArgumentException.class, () -> negate.compute(null));
    }

    // ------------------------------------------------------------------ resolvers over canned rows

    @Test
    void theSystemLimitResolverReadsMaxReturnAsMoney() throws Exception {
        Ctx ctx = new Ctx();
        ctx.answer(LendingResolvers.SYSTEM_LIMIT_SQL, params -> rows(List.of("VALUE"),
                "max_return".equals(params.get(0)) ? List.of(List.of("500.0")) : List.of()));
        List<ResolverCandidate<BigDecimal>> found = new LendingResolvers.SystemLimitResolver().candidates(
                new SystemLimit("RETURN"), ctx);
        assertEquals(List.of(new BigDecimal("500.00")), found.stream().map(ResolverCandidate::value).toList());
        assertEquals("500.00", found.get(0).value().toPlainString());
        assertEquals(List.of("max_return"), ctx.params.get(0));
        assertEquals(1, new LendingResolvers.SystemLimitResolver().candidates(new SystemLimit(null), ctx).size(),
                "RETURN by default");
        assertEquals(List.of(), new LendingResolvers.SystemLimitResolver().candidates(new SystemLimit("WANT"), ctx));
        ctx.answer(LendingResolvers.SYSTEM_LIMIT_SQL, params -> rows(List.of("VALUE"), List.of(List.of("lots"))));
        assertEquals(List.of(), new LendingResolvers.SystemLimitResolver().candidates(new SystemLimit("RETURN"), ctx),
                "a value that is not a decimal gives no candidate");
        ctx.answer(LendingResolvers.SYSTEM_LIMIT_SQL, params -> rows(List.of("VALUE"), List.of(nullRow(1))));
        assertEquals(List.of(), new LendingResolvers.SystemLimitResolver().candidates(new SystemLimit("RETURN"), ctx));
    }

    @Test
    void theItemResolverNarrowsInSqlAndAppliesTheStrictBoundsInJava() throws Exception {
        Ctx ctx = new Ctx();
        ctx.answer(LendingResolvers.LENDABLE_ITEM_SQL, params -> rows(List.of("CODE", "BARCODE", "TITLE", "DEPOSIT"),
                List.of(List.of("A", "1", "Cheap", "20.00"), List.of("B", "2", "Mid", "34.99"),
                        List.of("C", "3", "Dear", "40.00"))));
        NumberRange range = new NumberRange(new BigDecimal("20.00"), null, null, null,
                List.of(new BigDecimal("10.00"), new BigDecimal("200.00")));
        List<ResolverCandidate<ItemRef>> found = new LendingResolvers.LendableItemResolver().candidates(
                new LendableItem(range, null), ctx);
        assertEquals(List.of("B", "C"), found.stream().map(c -> c.value().code()).toList(), "gt 20.00 is strict");
        assertEquals(new ItemRef("B", "2", "Mid", new BigDecimal("34.99")), found.get(0).value());
        assertEquals("item B", found.get(0).label());
        assertEquals(List.of(Boolean.FALSE, "20.00", "200.00"), ctx.params.get(0),
                "perDay false by default; the tightest bounds as plain decimal strings");
        new LendingResolvers.LendableItemResolver().candidates(new LendableItem(null, true), ctx);
        assertEquals(List.of(Boolean.TRUE, "0", "99999999.99"), ctx.params.get(1), "open bounds");
    }

    @Test
    void theStaffResolverAppliesEveryConditionInCodeOrderAndReadsPermissionsOnlyForRowsStillInQuestion()
            throws Exception {
        Ctx ctx = new Ctx();
        // code, auto_sign_on, limit_amount
        ctx.answer(LendingResolvers.STAFF_SQL, params -> rows(List.of("CODE", "AUTO_SIGN_ON", "LIMIT_AMOUNT"),
                List.of(List.of("100", false, "20.00"), List.of("101", false, "5.00"), List.of("103", true, "20.00"),
                        List.of("104", false, "10.00"), Arrays2.row("200", false, null),
                        List.of("300", false, "40.00"))));
        Map<String, List<String>> permissions = Map.of("100", List.of("RETURN", "SIGN_ON"), "101", List.of("SIGN_ON"),
                "104", List.of("RETURN", "SIGN_ON"), "200", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON"),
                "300", List.of("RETURN", "SIGN_ON"));
        ctx.answer(LendingResolvers.PERMISSIONS_SQL, params -> {
            List<List<?>> out = new ArrayList<>();
            permissions.getOrDefault(String.valueOf(params.get(0)), List.of()).forEach(p -> out.add(List.of(p)));
            return rows(List.of("PERMISSION"), out);
        });
        ctx.credential = new CredentialSpec("staff", "code");
        ctx.known = Set.of("100", "101", "103", "200", "300");
        StaffWithPermission assistant = new StaffWithPermission(List.of("SIGN_ON", "RETURN"),
                new LimitCondition("RETURN", new BigDecimal("34.99"), null, null, null), true, null);
        List<ResolverCandidate<StaffRef>> found = new LendingResolvers.StaffResolver().candidates(assistant, ctx);
        // 101 lacks RETURN, 103 signs on without a password, 104 has no credential, 200 is unlimited (not below),
        // 300's limit 40.00 is not below 34.99.
        assertEquals(List.of("100"), found.stream().map(c -> c.value().code()).toList());
        assertEquals(new StaffRef("100", List.of("RETURN", "SIGN_ON"), Map.of("RETURN", new BigDecimal("20.00"))),
                found.get(0).value());
        assertEquals(List.of(List.of("RETURN"), List.of("100"), List.of("101")), ctx.params,
                "permissions read only for rows still in question, never for 103 (auto sign-on) or 104 (no "
                        + "credential)");

        ctx.params.clear();
        StaffWithPermission supervisor = new StaffWithPermission(List.of("RETURN_OVERRIDE"),
                new LimitCondition(null, null, null, null, new BigDecimal("34.99")), null, List.of("100"));
        List<ResolverCandidate<StaffRef>> sup = new LendingResolvers.StaffResolver().candidates(supervisor, ctx);
        assertEquals(List.of("200"), sup.stream().map(c -> c.value().code()).toList(), "unlimited satisfies gte");
        Map<String, BigDecimal> unlimited = new HashMap<>();
        unlimited.put("RETURN", null);
        assertEquals(new StaffRef("200", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON"), unlimited),
                sup.get(0).value());

        ctx.max = 1;
        ctx.params.clear();
        ctx.credential = null;
        List<ResolverCandidate<StaffRef>> first = new LendingResolvers.StaffResolver().candidates(
                new StaffWithPermission(List.of("SIGN_ON"), null, null, null), ctx);
        assertEquals(List.of("100"), first.stream().map(c -> c.value().code()).toList(), "stops at maxCandidates");
        assertEquals(2, ctx.params.size());
    }

    /**
     * Credentials early, but never hiding why nothing fits: with no entry for any qualifying staff member, the rows
     * skipped for that reason are offered after all, so that the binder rejects them with "no credential"
     * (ALL_REJECTED) instead of reporting that nobody qualifies.
     */
    @Test
    void staffSkippedOnlyForWantOfACredentialAreOfferedWhenNobodyElseQualifies() throws Exception {
        Ctx ctx = new Ctx();
        ctx.answer(LendingResolvers.STAFF_SQL, params -> rows(List.of("CODE", "AUTO_SIGN_ON", "LIMIT_AMOUNT"),
                List.of(List.of("100", false, "20.00"), List.of("101", false, "5.00"), List.of("102", false,
                        "10.00"))));
        Map<String, List<String>> permissions = Map.of("100", List.of("RETURN", "SIGN_ON"), "101",
                List.of("SIGN_ON"), "102", List.of("RETURN", "SIGN_ON"));
        ctx.answer(LendingResolvers.PERMISSIONS_SQL, params -> {
            List<List<?>> out = new ArrayList<>();
            permissions.get(String.valueOf(params.get(0))).forEach(p -> out.add(List.of(p)));
            return rows(List.of("PERMISSION"), out);
        });
        ctx.credential = new CredentialSpec("staff", "code");
        StaffWithPermission query = new StaffWithPermission(List.of("SIGN_ON", "RETURN"), null, null, null);
        ctx.known = Set.of();
        List<ResolverCandidate<StaffRef>> offered = new LendingResolvers.StaffResolver().candidates(query, ctx);
        assertEquals(List.of("100", "102"), offered.stream().map(c -> c.value().code()).toList(),
                "the binder drops these with 'no credential'");
        ctx.params.clear();
        ctx.known = Set.of("102");
        List<ResolverCandidate<StaffRef>> found = new LendingResolvers.StaffResolver().candidates(query, ctx);
        assertEquals(List.of("102"), found.stream().map(c -> c.value().code()).toList());
        assertEquals(List.of(List.of("RETURN"), List.of("102")), ctx.params,
                "with a qualifying entry nothing else is read");
    }

    @Test
    void aTruncatedPermissionLookupFailsTheSlot() {
        Ctx ctx = new Ctx();
        ctx.answer(LendingResolvers.STAFF_SQL, params -> rows(List.of("CODE", "AUTO_SIGN_ON", "LIMIT_AMOUNT"),
                List.of(List.of("100", false, "20.00"))));
        ctx.answer(LendingResolvers.PERMISSIONS_SQL, params -> Rows.of(List.of(new Rows.Column("PERMISSION",
                "VARCHAR")), List.of(List.of("SIGN_ON")), true));
        ResolutionException e = assertThrows(ResolutionException.class, () -> new LendingResolvers.StaffResolver()
                .candidates(new StaffWithPermission(List.of("SIGN_ON"), null, null, null), ctx));
        assertEquals("TRUNCATED", e.reason());
    }

    @Test
    void theActionKeyResolverKnowsTheKeypad() {
        LendingResolvers.ActionKeyResolver resolver = new LendingResolvers.ActionKeyResolver();
        List<ResolverCandidate<Map<String, String>>> found = resolver.candidates(new ActionKey(List.of("RETURN",
                "DUE")), new Ctx());
        assertEquals(1, found.size());
        assertEquals(Map.of("RETURN", "RETURN", "DUE", "DUE"), found.get(0).value());
        assertEquals(Map.of("DUE", "DUE", "RETURN", "RETURN"), RecordCodec.DEFAULT.encode(found.get(0).value()));
        assertEquals(List.of(), resolver.candidates(new ActionKey(List.of("RETURN", "PRINT")), new Ctx()));
        assertEquals(List.of(), resolver.candidates(new ActionKey(List.of()), new Ctx()));
        assertEquals(List.of(), resolver.candidates(new ActionKey(null), new Ctx()));
    }

    // ------------------------------------------------------------------ verifiers over a scripted desk

    @Test
    void theLimitsVerifierComparesWithTheDesksMaximum() throws Exception {
        Desk desk = new Desk();
        desk.on("lending.limits", p -> Map.of("maxReturn", "500.00"));
        LendingVerifiers.LimitsVerifier verifier = new LendingVerifiers.LimitsVerifier();
        VerifierResult ok = verifier.verify(new SystemLimit("RETURN"), new BigDecimal("500.0"), desk.ctx());
        assertTrue(ok.passed(), ok.reason());
        assertEquals("the desk reports maxReturn 500.00", ok.reason());
        assertEquals(1, ((List<?>) ok.evidence().get("calls")).size());
        VerifierResult wrong = verifier.verify(new SystemLimit("RETURN"), new BigDecimal("350.00"), desk.ctx());
        assertFalse(wrong.passed());
        assertEquals("the desk reports maxReturn 500.00, not 350.00", wrong.reason());
        Map<String, Object> none = new HashMap<>();
        none.put("maxReturn", null);
        desk.on("lending.limits", p -> none);
        assertFalse(verifier.verify(new SystemLimit("RETURN"), new BigDecimal("500.00"), desk.ctx()).passed());
        assertFalse(verifier.verify(new SystemLimit("WANT"), new BigDecimal("500.00"), desk.ctx()).passed());
    }

    @Test
    void theDepositVerifierScansTheCandidatesBarcode() throws Exception {
        Desk desk = new Desk();
        Map<String, Object> drill = new LinkedHashMap<>(Map.of("found", true, "code", "PWR-001", "barcode",
                "5000000000093", "title", "Cordless drill", "deposit", "34.99", "active", true, "perDay", false,
                "inductionRequired", false));
        desk.on("lending.deposit", p -> "5000000000093".equals(p.get("barcode")) ? drill : Map.of("found", false));
        LendingVerifiers.DepositVerifier verifier = new LendingVerifiers.DepositVerifier();
        ItemRef ref = new ItemRef("PWR-001", "5000000000093", "Cordless drill", new BigDecimal("34.99"));
        VerifierResult ok = verifier.verify(new LendableItem(null, null), ref, desk.ctx());
        assertTrue(ok.passed(), ok.reason());
        assertEquals("a scan of 5000000000093 finds PWR-001 at 34.99, active, counted", ok.reason());
        assertEquals(Map.of("barcode", "5000000000093"), desk.params.get(0));
        assertFalse(verifier.verify(new LendableItem(null, true), ref, desk.ctx()).passed(), "not per day");
        assertFalse(verifier.verify(new LendableItem(null, null), new ItemRef("PWR-001", "5000000000093", "x",
                new BigDecimal("35.00")), desk.ctx()).passed(), "another deposit");
        assertFalse(verifier.verify(new LendableItem(null, null), new ItemRef("PWR-002", "5000000000093", "x",
                new BigDecimal("34.99")), desk.ctx()).passed(), "another item behind the barcode");
        assertFalse(verifier.verify(new LendableItem(null, null), new ItemRef("PWR-001", "1", "x",
                new BigDecimal("34.99")), desk.ctx()).passed(), "unknown barcode");
        drill.put("active", false);
        assertEquals("the desk reports item PWR-001 not for loan", verifier.verify(new LendableItem(null, null), ref,
                desk.ctx()).reason());
    }

    /**
     * The staff verifier against a scripted access control that follows the desk's rules for staff 100 (limit
     * 20.00, SIGN_ON and RETURN), 103 (the same, auto sign-on) and 200 (limit 300.00, with RETURN_OVERRIDE).
     */
    @Test
    void theAccessVerifierChecksEveryConditionWithTheDesksDecisions() throws Exception {
        Desk desk = new Desk();
        Map<String, BigDecimal> limits = Map.of("100", new BigDecimal("20.00"), "103", new BigDecimal("20.00"),
                "200", new BigDecimal("300.00"));
        Map<String, List<String>> permissions = Map.of("100", List.of("SIGN_ON", "RETURN"), "103",
                List.of("SIGN_ON", "RETURN"), "200", List.of("SIGN_ON", "RETURN", "RETURN_OVERRIDE"));
        desk.on("lending.access.check", p -> {
            String staff = String.valueOf(p.get("staff"));
            String permission = String.valueOf(p.get("permission"));
            boolean limited = permission.startsWith("RETURN");
            BigDecimal limit = limited ? limits.get(staff) : null;
            boolean allowed = permissions.get(staff).contains(permission);
            String reason = allowed ? "permitted" : "missing permission " + permission;
            if (allowed && limited && p.get("amount") != null
                    && new BigDecimal(String.valueOf(p.get("amount"))).compareTo(limit) > 0) {
                allowed = false;
                reason = "amount exceeds the limit " + limit;
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("allowed", allowed);
            answer.put("limit", limit == null ? null : limit.toPlainString());
            answer.put("reason", reason);
            answer.put("autoSignOn", "103".equals(staff));
            return answer;
        });
        LendingVerifiers.AccessVerifier verifier = new LendingVerifiers.AccessVerifier();
        StaffWithPermission assistant = new StaffWithPermission(List.of("SIGN_ON", "RETURN"),
                new LimitCondition("RETURN", new BigDecimal("34.99"), null, null, null), true, null);
        StaffRef staff100 = new StaffRef("100", List.of("RETURN", "SIGN_ON"), Map.of("RETURN",
                new BigDecimal("20.00")));
        VerifierResult ok = verifier.verify(assistant, staff100, desk.ctx());
        assertTrue(ok.passed(), ok.reason());
        assertEquals("SIGN_ON and RETURN permitted; RETURN of 34.99 refused; limit 20.00; asked for a password",
                ok.reason());
        assertEquals(List.of(Map.of("staff", "100", "permission", "SIGN_ON"), Map.of("staff", "100", "permission",
                "RETURN"), Map.of("staff", "100", "permission", "RETURN"), Map.of("staff", "100", "permission",
                "RETURN", "amount", "34.99")), desk.params);

        VerifierResult auto = verifier.verify(assistant, new StaffRef("103", List.of("RETURN", "SIGN_ON"),
                Map.of("RETURN", new BigDecimal("20.00"))), desk.ctx());
        assertEquals("the desk signs staff 103 on without a password", auto.reason(), "Q12");

        StaffRef staff200 = new StaffRef("200", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON"), Map.of("RETURN",
                new BigDecimal("300.00")));
        VerifierResult wrongAssistant = verifier.verify(assistant, staff200, desk.ctx());
        assertFalse(wrongAssistant.passed());
        assertEquals("the desk's RETURN limit 300.00 for staff 200 does not satisfy below 34.99",
                wrongAssistant.reason());

        VerifierResult claimed = verifier.verify(assistant, new StaffRef("100", List.of(), Map.of("RETURN",
                new BigDecimal("15.00"))), desk.ctx());
        assertEquals("the desk reports a RETURN limit of 20.00 for staff 100, not 15.00", claimed.reason());

        StaffWithPermission supervisor = new StaffWithPermission(List.of("RETURN_OVERRIDE"),
                new LimitCondition("RETURN", null, null, null, new BigDecimal("34.99")), null, List.of("100"));
        desk.params.clear();
        VerifierResult sup = verifier.verify(supervisor, staff200, desk.ctx());
        assertTrue(sup.passed(), sup.reason());
        assertEquals("RETURN_OVERRIDE permitted; RETURN_OVERRIDE of 34.99 allowed; limit 300.00", sup.reason());
        assertEquals(Map.of("staff", "200", "permission", "RETURN_OVERRIDE", "amount", "34.99"),
                desk.params.get(desk.params.size() - 1), "the check the engine makes when it authorises");
        assertEquals("the desk refuses RETURN_OVERRIDE to staff 100: missing permission RETURN_OVERRIDE",
                verifier.verify(new StaffWithPermission(List.of("RETURN_OVERRIDE"), null, null, null), staff100,
                        desk.ctx()).reason());
        assertEquals("staff 100 is one of [100]", verifier.verify(new StaffWithPermission(List.of("SIGN_ON"), null,
                null, List.of("100")), staff100, desk.ctx()).reason());
        assertFalse(verifier.verify(new StaffWithPermission(List.of("SIGN_ON"), new LimitCondition("WANT", null,
                null, null, null), null, null), staff100, desk.ctx()).passed());
    }

    /**
     * Q12, both halves (14.1 item 8): the staff verifier rejects a candidate whom the desk signs on without a
     * password only under {@code interactiveSignOn: true}; without that condition (absent, or false) it accepts the
     * same candidate. No seeded supervisor signs on automatically, so the scripted desk answers {@code autoSignOn:
     * true} for every staff member: the assistant 103 (limit 20.00) and a supervisor 201 (limit 300.00, with
     * RETURN_OVERRIDE).
     */
    @Test
    void anAutoSignOnCandidateIsRefusedOnlyUnderInteractiveSignOn() throws Exception {
        Desk desk = new Desk();
        Map<String, BigDecimal> limits = Map.of("103", new BigDecimal("20.00"), "201", new BigDecimal("300.00"));
        Map<String, List<String>> permissions = Map.of("103", List.of("SIGN_ON", "RETURN"), "201",
                List.of("SIGN_ON", "RETURN", "RETURN_OVERRIDE"));
        desk.on("lending.access.check", p -> {
            String staff = String.valueOf(p.get("staff"));
            String permission = String.valueOf(p.get("permission"));
            BigDecimal limit = permission.startsWith("RETURN") ? limits.get(staff) : null;
            Object amount = p.get("amount");
            boolean allowed = permissions.get(staff).contains(permission) && (limit == null || amount == null
                    || new BigDecimal(String.valueOf(amount)).compareTo(limit) <= 0);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("allowed", allowed);
            answer.put("limit", limit == null ? null : limit.toPlainString());
            answer.put("reason", allowed ? "permitted" : "refused");
            answer.put("autoSignOn", true);
            return answer;
        });
        LendingVerifiers.AccessVerifier verifier = new LendingVerifiers.AccessVerifier();
        StaffRef staff103 = new StaffRef("103", List.of("RETURN", "SIGN_ON"), Map.of("RETURN",
                new BigDecimal("20.00")));
        LimitCondition belowTheDeposit = new LimitCondition("RETURN", new BigDecimal("34.99"), null, null, null);
        StaffRef staff201 = new StaffRef("201", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON"), Map.of("RETURN",
                new BigDecimal("300.00")));
        LimitCondition atLeastTheDeposit = new LimitCondition("RETURN", null, null, null, new BigDecimal("34.99"));
        for (Boolean interactive : java.util.Arrays.asList(null, Boolean.FALSE)) {
            VerifierResult assistant = verifier.verify(new StaffWithPermission(List.of("SIGN_ON", "RETURN"),
                    belowTheDeposit, interactive, null), staff103, desk.ctx());
            assertTrue(assistant.passed(), interactive + ": " + assistant.reason());
            assertEquals("SIGN_ON and RETURN permitted; RETURN of 34.99 refused; limit 20.00", assistant.reason());
            VerifierResult supervisor = verifier.verify(new StaffWithPermission(List.of("RETURN_OVERRIDE"),
                    atLeastTheDeposit, interactive, List.of("103")), staff201, desk.ctx());
            assertTrue(supervisor.passed(), interactive + ": " + supervisor.reason());
            assertEquals("RETURN_OVERRIDE permitted; RETURN_OVERRIDE of 34.99 allowed; limit 300.00",
                    supervisor.reason());
        }
        VerifierResult assistant = verifier.verify(new StaffWithPermission(List.of("SIGN_ON", "RETURN"),
                belowTheDeposit, true, null), staff103, desk.ctx());
        assertFalse(assistant.passed());
        assertEquals("the desk signs staff 103 on without a password", assistant.reason());
        VerifierResult supervisor = verifier.verify(new StaffWithPermission(List.of("RETURN_OVERRIDE"),
                atLeastTheDeposit, true, List.of("103")), staff201, desk.ctx());
        assertFalse(supervisor.passed());
        assertEquals("the desk signs staff 201 on without a password", supervisor.reason());
    }

    @Test
    void theKeyVerifierFindsExactlyOneKeyPerAction() throws Exception {
        Desk desk = new Desk();
        Map<String, Integer> keys = new HashMap<>(Map.of("RETURN", 1, "DUE", 1));
        desk.on("swing.find", p -> {
            String action = String.valueOf(((Map<?, ?>) p.get("by")).get("action"));
            List<Object> matches = new ArrayList<>();
            for (int i = 0; i < keys.getOrDefault(action, 0); i++) {
                matches.add(Map.of("name", action));
            }
            return Map.of("matches", matches);
        });
        LendingVerifiers.KeyVerifier verifier = new LendingVerifiers.KeyVerifier();
        ActionKey query = new ActionKey(List.of("RETURN", "DUE"));
        VerifierResult ok = verifier.verify(query, Map.of("RETURN", "RETURN", "DUE", "DUE"), desk.ctx());
        assertTrue(ok.passed(), ok.reason());
        assertEquals(List.of(Map.of("by", Map.of("action", "DUE"), "all", true), Map.of("by", Map.of("action",
                "RETURN"), "all", true)), desk.params, "in a fixed order");
        keys.put("DUE", 2);
        assertEquals("the window has 2 keys with action DUE, not exactly one", verifier.verify(query,
                Map.of("RETURN", "RETURN", "DUE", "DUE"), desk.ctx()).reason());
        keys.put("DUE", 0);
        assertFalse(verifier.verify(query, Map.of("RETURN", "RETURN", "DUE", "DUE"), desk.ctx()).passed());
        assertFalse(verifier.verify(query, Map.of("RETURN", "RETURN"), desk.ctx()).passed(), "DUE not covered");
    }

    // ------------------------------------------------------------------ fingerprinter

    @Test
    void theFingerprintIsASchemaChecksumTheSiteAndTheSeed() throws Exception {
        Desk desk = new Desk();
        desk.on("app.state", p -> Map.of("lending", Map.of("seed", "A")));
        Ctx data = new Ctx();
        data.answer(LendingFingerprinter.COLUMNS_SQL, params -> rows(List.of("COLUMN_NAME", "DATA_TYPE",
                "IS_NULLABLE"), List.of(List.of("ID", "INTEGER", "NO"), List.of(params.get(0) + "_X", "VARCHAR",
                "YES"))));
        data.answer(LendingResolvers.SYSTEM_LIMIT_SQL, params -> rows(List.of("VALUE"),
                List.of(List.of("TOOL LIBRARY - MILL LANE"))));
        Map<String, String> components = new LendingFingerprinter().components(fingerprint(desk, data));
        assertEquals(List.of("lending.schema", "lending.seed", "lending.site"), components.keySet().stream()
                .sorted().toList());
        assertTrue(components.get("lending.schema").matches("sha256:[0-9a-f]{64}"), components.toString());
        assertEquals("A", components.get("lending.seed"));
        assertEquals("TOOL LIBRARY - MILL LANE", components.get("lending.site"));
        assertEquals(LendingFingerprinter.TABLES.size() + 1, data.params.size());
        assertEquals(List.of("ROLE_CAP"), data.params.get(3), "the renamed limit table");
        assertTrue(data.params.containsAll(List.of(List.of("WAIVER_RULE"), List.of("WAIVER_RULE_ITEM"))),
                "the waiver tables the pack reads from Phase 3 on: " + data.params);
        assertEquals(components, new LendingFingerprinter().components(fingerprint(desk, data)), "stable");
        desk.on("app.state", p -> Map.of("lending", Map.of("seed", "A", "dbPort", 9101L, "slips", 3L, "today",
                "2026-10-03")));
        assertEquals(components, new LendingFingerprinter().components(fingerprint(desk, data)),
                "the listener's port, the business date and the desk's other state are no components");
        desk.on("app.state", p -> Map.of("lending", Map.of("seed", "A", "today", "2026-10-04")));
        assertEquals(components, new LendingFingerprinter().components(fingerprint(desk, data)),
                "the next day is the same environment (decision D44)");

        data.answer(LendingFingerprinter.COLUMNS_SQL, params -> rows(List.of("COLUMN_NAME"), List.of()));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> new LendingFingerprinter().components(fingerprint(desk, data)));
        assertEquals("the lending schema has no table STAFF", missing.getMessage());
    }

    private static FingerprintContext fingerprint(Desk desk, Ctx data) {
        return new FingerprintContext() {
            @Override
            public AgentConnection agent() {
                return desk;
            }

            @Override
            public DataChannel data() {
                return data.data();
            }

            @Override
            public String envId() {
                return "seed-a";
            }

            @Override
            public long remainingMs() {
                return 60_000L;
            }
        };
    }

    // ------------------------------------------------------------------ helpers

    static Rows rows(List<String> names, List<? extends List<?>> rows) {
        List<Rows.Column> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(new Rows.Column(name, "VARCHAR"));
        }
        return Rows.of(columns, rows, false);
    }

    static List<Object> nullRow(int n) {
        List<Object> row = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            row.add(null);
        }
        return row;
    }

    /** Row literals that may hold null. */
    static final class Arrays2 {
        static List<Object> row(Object... values) {
            return new ArrayList<>(java.util.Arrays.asList(values));
        }
    }

    /** A resolve context over canned statements: SQL text to an answer for the parameters. */
    static final class Ctx implements ResolveContext {
        final Map<String, Function<List<Object>, Rows>> answers = new HashMap<>();
        final List<List<Object>> params = new ArrayList<>();
        CredentialSpec credential;
        Set<String> known = Set.of();
        int max = 20;
        /** The desk the resolver may ask (read-only calls), or null when no agent call is expected. */
        AgentConnection agent;

        void answer(String sql, Function<List<Object>, Rows> answer) {
            answers.put(sql, answer);
        }

        @Override
        public DataChannel data() {
            return new DataChannel() {
                @Override
                public boolean serves(String source) {
                    return "lending".equals(source);
                }

                @Override
                public Rows query(String source, String sql, List<Object> p, int maxRows, Duration timeout) {
                    assertEquals("lending", source);
                    assertEquals(DataChannel.MAX_ROWS, maxRows);
                    for (Object value : p) {
                        assertTrue(value == null || value instanceof String || value instanceof Long
                                || value instanceof Boolean, "a parameter form of 7.1: " + value);
                    }
                    params.add(new ArrayList<>(p));
                    Function<List<Object>, Rows> answer = answers.get(sql);
                    if (answer == null) {
                        throw new AssertionError("unexpected statement " + sql);
                    }
                    return answer.apply(p);
                }

                @Override
                public String kind(String source) {
                    return "agent";
                }
            };
        }

        @Override
        public AgentConnection agent() {
            if (agent == null) {
                throw new AssertionError("no agent call expected");
            }
            return agent;
        }

        @Override
        public CredentialSpec credential() {
            return credential;
        }

        @Override
        public CredentialIndex credentials() {
            return (realm, id) -> "staff".equals(realm) && known.contains(id);
        }

        @Override
        public String envId() {
            return "seed-a";
        }

        @Override
        public BindMode mode() {
            return BindMode.LIVE;
        }

        @Override
        public int maxCandidates() {
            return max;
        }

        @Override
        public long remainingMs() {
            return 60_000L;
        }

        @Override
        public CostLedger ledger() {
            return new CostLedger();
        }

        @Override
        public void note(String text) {
        }

        @Override
        public Rows requireComplete(Rows rows) throws ResolutionException {
            if (rows.truncated()) {
                throw new ResolutionException("TRUNCATED", null, "truncated");
            }
            return rows;
        }
    }

    /** A scripted desk: primitive to an answer for the params; records every call's params. */
    static final class Desk implements AgentConnection {
        final Map<String, Function<Map<String, Object>, Object>> answers = new HashMap<>();
        final Map<String, RpcError> errors = new HashMap<>();
        final List<Map<String, Object>> params = new ArrayList<>();
        /** The primitives asked, in order. */
        final List<String> methods = new ArrayList<>();

        void on(String primitive, Function<Map<String, Object>, Object> answer) {
            errors.remove(primitive);
            answers.put(primitive, answer);
        }

        /** The primitive answers with this JSON-RPC error. */
        void fail(String primitive, RpcError error) {
            answers.put(primitive, p -> null);
            errors.put(primitive, error);
        }

        VerifyContext ctx() {
            return new VerifyContext() {
                @Override
                public AgentConnection agent() {
                    return Desk.this;
                }

                @Override
                public DataChannel data() {
                    throw new AssertionError("a verifier asks the application, not the database");
                }

                @Override
                public String envId() {
                    return "seed-a";
                }

                @Override
                public long remainingMs() {
                    return 60_000L;
                }
            };
        }

        @Override
        public RpcResult call(String method, Map<String, Object> p) throws AgentRpcException {
            return call(method, p, CallOptions.defaults());
        }

        @Override
        public RpcResult call(String method, Map<String, Object> p, CallOptions options) throws AgentRpcException {
            Function<Map<String, Object>, Object> answer = answers.get(method);
            if (answer == null) {
                throw new AgentRpcException(method, new RpcError(ErrorCodes.METHOD_NOT_FOUND, method, null));
            }
            params.add(p);
            methods.add(method);
            if (errors.containsKey(method)) {
                throw new AgentRpcException(method, errors.get(method));
            }
            return new RpcResult(answer.apply(p), List.of(), 0, 0, 1, "scripted");
        }

        @Override
        public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CapabilityMatrix capabilities() {
            return new CapabilityMatrix(Map.of());
        }

        @Override
        public List<PrimitiveDescriptor> schema() {
            return List.of();
        }

        @Override
        public Map<String, Object> ping() {
            return Map.of();
        }

        @Override
        public void close() {
        }
    }
}
