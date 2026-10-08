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

package ai.skillroy.testjava.inference.resolve;

import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.data.JdbcDataChannel;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.recorded.RecordedGateway;
import ai.skillroy.testjava.inference.recorded.RecordingGateway;
import ai.skillroy.testjava.inference.resolve.DeskPack.ItemByCategory;
import ai.skillroy.testjava.inference.resolve.DeskPack.ItemRef;
import ai.skillroy.testjava.inference.resolve.DeskPack.LoanCount;
import ai.skillroy.testjava.inference.resolve.DeskPack.LoansByStatus;
import ai.skillroy.testjava.inference.resolve.DeskPack.StaffPlain;
import ai.skillroy.testjava.inference.resolve.DeskPack.StaffRef;
import ai.skillroy.testjava.inference.resolve.DeskPack.StaffWithRole;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.Effort;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceException.Kind;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Prompts;
import ai.skillroy.testjava.inference.sql.LendingDatabase;
import ai.skillroy.testjava.inference.sql.LendingFixture;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.Schemas;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.ValueLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 6: the tier (b) resolver with a scripted gateway over the lending schema on the
 * embedded database through the controller's JDBC channel: a good proposal's candidates in statement order; each gate
 * ({@code cannotAnswer}, the confidence, the guard, the parameter rule, the row bounds, the shape); a refusal; a cut
 * answer; {@code NO_RECORDING}; a retryable error tried once more; the budget before and after a call; the ledger entry
 * and the {@code inference.jsonl} record; the value loader (D90); the batch mode; the committed recording that pins
 * the prompt.
 */
class LlmSqlResolverTest {

    static final String POWER_SQL = "SELECT i.code, i.title, i.deposit FROM item i WHERE i.category = ? AND"
            + " i.deposit <= ? ORDER BY i.deposit";

    private LendingDatabase db;
    private JdbcDataChannel channel;
    private final CostLedger ledger = new CostLedger();
    private final InferenceLog.Memory log = InferenceLog.inMemory();
    private TestContext ctx;
    private final List<BindState> states = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        db = LendingDatabase.create("resolver");
        channel = db.channel(List.of());
        ctx = new TestContext(channel, ledger);
    }

    @AfterEach
    void close() throws Exception {
        states.forEach(BindState::close);
        channel.close();
        db.close();
    }

    private BindState state(InferenceGateway gateway, String budget) {
        BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SCENARIO, null, budget, ledger);
        PromptMask mask = PromptMask.of(SecretMask.of(List.of(LendingDatabase.READER_PASSWORD)),
                List.of(LendingFixture.allowList(SqlDialect.HSQLDB)), List.of());
        LedgerWriter writer = new LedgerWriter(ledger, meter, log, mask, false, Clock.systemUTC());
        BindState s = new BindState(gateway, PriceTable.builtIn(), meter, writer, mask,
                List.of(LendingFixture.allowList(SqlDialect.HSQLDB)), new BigDecimal("0.8"), Effort.LOW, 120_000L,
                "claude-sonnet-5-5");
        states.add(s);
        return s;
    }

    private static <Q extends Query<T>, T> LlmSqlResolver<Q, T> resolver(String kind, Class<Q> query,
                                                                         ValueLoader<Q, T> loader, BindState s) {
        return new LlmSqlResolver<>(QueryType.of(kind, query), loader, s);
    }

    private LlmSqlResolver<ItemByCategory, ItemRef> items(InferenceGateway gateway) {
        return resolver("ItemByCategory", ItemByCategory.class, null, state(gateway, "0.50"));
    }

    static Map<String, Object> param(String name, String value, String type) {
        return Map.of("name", name, "value", value, "type", type);
    }

    static Map<String, Object> proposal(String sql, List<Map<String, Object>> params, double confidence, int min,
                                        int max) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sql", sql);
        p.put("params", params);
        p.put("rationale", "the items of the category within the deposit, cheapest first");
        p.put("tablesUsed", List.of("item"));
        p.put("orderBy", "i.deposit");
        p.put("confidence", confidence);
        p.put("expectedRows", Map.of("min", (long) min, "max", (long) max));
        p.put("cannotAnswer", false);
        p.put("cannotAnswerReason", "");
        return p;
    }

    static Map<String, Object> goodProposal() {
        return proposal(POWER_SQL, List.of(param("category", "POWER", "STRING"), param("maxDeposit", "60.00",
                "NUMBER")), 0.9, 1, 5);
    }

    private static ScriptedGateway answering(Map<String, Object> proposal) {
        return ScriptedGateway.answering(r -> ScriptedGateway.structured(proposal,
                ScriptedGateway.usage(1200, 150, "0.0039")));
    }

    private static final ItemByCategory POWER = new ItemByCategory("POWER", new BigDecimal("60.00"));

    // ------------------------------------------------------------------------------------------------ the request

    @Test
    void aGoodProposalYieldsCandidatesInTheStatementsOrder() throws Exception {
        ScriptedGateway gateway = answering(goodProposal());
        List<ResolverCandidate<ItemRef>> candidates = items(gateway).candidates(POWER, ctx);
        assertEquals(List.of(new ItemRef("DRL-01", "Cordless drill", new BigDecimal("40.00")),
                new ItemRef("DRL-02", "Hammer drill", new BigDecimal("60.00"))),
                candidates.stream().map(ResolverCandidate::value).toList());
        assertEquals(List.of("DRL-01", "DRL-02"), candidates.stream().map(ResolverCandidate::label).toList());
        assertEquals(0.9, candidates.get(0).confidence());
        String ran = ctx.statements.get(ctx.statements.size() - 1);
        assertEquals("SELECT i.code, i.title, i.deposit FROM item i WHERE i.category = ? AND i.deposit <= ? ORDER BY"
                + " i.deposit, i.code LIMIT 200 [POWER, 60.00]", ran, "the guard's rewrite ran, with the arguments");
        assertEquals(List.of("the items of the category within the deposit, cheapest first"), ctx.rationales,
                "the rationale through the provenance seam");
        assertFalse(ctx.allNotes().contains("rationale"), "not as a note: " + ctx.allNotes());
        assertTrue(ctx.rejected.isEmpty(), ctx.allRejected());
        ModelRequest r = gateway.requests().get(0);
        assertEquals("resolve", r.purpose());
        assertEquals(ModelClass.SONNET_CLASS, r.modelClass());
        assertEquals(Effort.LOW, r.effort());
        assertEquals(2048, r.maxTokens());
        assertEquals(CacheHint.SHORT, r.cache());
        assertTrue(((Content.Text) r.system().get(0)).cacheBreakpoint(), "the catalogue is the cached part");
        assertTrue(((Content.Text) r.system().get(0)).text().startsWith("# SQL catalogue"));
        assertEquals(LlmSqlResolver.INSTRUCTIONS, ((Content.Text) r.system().get(1)).text());
        String question = ((Content.Text) r.messages().get(0).parts().get(0)).text();
        assertTrue(question.startsWith("Query kind: ItemByCategory\n"), question);
        assertTrue(question.contains("- deposit: a decimal number"), question);
        assertTrue(question.contains("{\"category\":\"POWER\",\"maxDeposit\":\"60.00\"}"), question);
        assertEquals(Schemas.ofRecord(SqlProposal.class), r.outputSchema());
        assertTrue(r.tools().isEmpty(), "one structured answer, not a loop");
        assertEquals(Map.of("kind", "ItemByCategory", "scenarioId", "desk-scenario", "slot", "item"), r.metadata());
        CostLedger.Entry e = ledger.entries().get(0);
        assertEquals(new CostLedger.Entry("desk-scenario", "item", Strategy.LLM_SQL, "scripted", "scripted-model",
                "scripted-1200-150", 1200, 150, "0.0039", "resolve", 0, 0, false, null, e.details()), e);
        assertEquals(log.records().get(0), e.details(), "the entry carries the inference.jsonl record (D91)");
        Map<String, Object> record = log.records().get(0);
        assertEquals(r.fingerprint(), record.get("fingerprint"));
        assertEquals(POWER_SQL, ((Map<?, ?>) record.get("structured")).get("sql"));
        String prompt = Prompts.canonical(Prompts.fingerprintJson(r));
        for (String never : List.of("Dana Reyes", "Ada Moss", "ada@example.org", LendingDatabase.READER_PASSWORD)) {
            assertFalse(prompt.contains(never), never);
        }
    }

    @Test
    void underReResolveTheCatalogueIsCachedForAnHour() throws Exception {
        ScriptedGateway gateway = answering(goodProposal());
        ctx.mode = BindMode.RE_RESOLVE;
        items(gateway).candidates(POWER, ctx);
        assertEquals(CacheHint.LONG, gateway.requests().get(0).cache());
    }

    // ------------------------------------------------------------------------------------------------ the gates

    @Test
    void cannotAnswerGivesNoCandidates() throws Exception {
        Map<String, Object> p = proposal("", List.of(), 0.95, 1, 1);
        p.put("cannotAnswer", true);
        p.put("cannotAnswerReason", "no table holds the item's colour");
        assertTrue(items(answering(p)).candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allNotes().contains("the model cannot answer (no table holds the item's colour)"));
    }

    @Test
    void aLowConfidenceIsRejected() throws Exception {
        Map<String, Object> p = goodProposal();
        p.put("confidence", 0.6);
        assertTrue(items(answering(p)).candidates(POWER, ctx).isEmpty());
        assertEquals(List.of("proposal: confidence 0.6 below 0.8"), ctx.rejected,
                "rejected through the provenance seam (5.4 step 3)");
        assertTrue(ctx.statements.stream().noneMatch(s -> s.contains("i.category = ?")), "nothing ran");
    }

    @Test
    void aGuardViolationIsRejectedWithItsReasonAndNothingRuns() throws Exception {
        assertTrue(items(answering(proposal("SELECT * FROM item", List.of(), 0.9, 1, 5))).candidates(POWER, ctx)
                .isEmpty());
        assertTrue(ctx.allRejected().startsWith("proposal: guard STAR"), ctx.allRejected());
        assertTrue(ctx.allNotes().contains("refused statement (masked): SELECT * FROM item"),
                "the whole statement only as a note: " + ctx.allNotes());
        ctx.notes.clear();
        ctx.rejected.clear();
        assertTrue(items(answering(proposal("SELECT a.detail FROM audit_log a", List.of(), 0.9, 1, 5)))
                .candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("guard TABLE"), ctx.allRejected());
        assertTrue(ctx.statements.stream().noneMatch(s -> s.contains("audit_log")));
    }

    @Test
    void aParameterMustHoldAnArgumentsValue() throws Exception {
        assertTrue(items(answering(proposal(POWER_SQL, List.of(param("category", "TOOLS", "STRING"),
                param("maxDeposit", "60.00", "NUMBER")), 0.9, 1, 5))).candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("proposal: PARAM: parameter category does not hold the argument's"
                + " value"), ctx.allRejected());
        assertTrue(items(answering(proposal(POWER_SQL, List.of(param("colour", "POWER", "STRING"),
                param("maxDeposit", "60.00", "NUMBER")), 0.9, 1, 5))).candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("PARAM: parameter colour names no argument of the query"));
        assertTrue(items(answering(proposal(POWER_SQL, List.of(param("category", "POWER", "STRING")), 0.9, 1, 5)))
                .candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("PARAM: the statement has 2 parameters and the proposal lists 1"));
        assertEquals(2, items(answering(proposal(POWER_SQL, List.of(param("category", "POWER", "STRING"),
                param("maxDeposit", "60", "NUMBER")), 0.9, 1, 5))).candidates(POWER, ctx).size(),
                "a number equal in value is the argument's value");
    }

    @Test
    void theRowCountMustLieWithinTheStatedBounds() throws Exception {
        Map<String, Object> p = goodProposal();
        p.put("expectedRows", Map.of("min", 5L, "max", 9L));
        assertTrue(items(answering(p)).candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("proposal: row count 2 outside 5..9"), ctx.allRejected());
        p.put("expectedRows", Map.of("min", -4L, "max", 900L));
        assertEquals(2, items(answering(p)).candidates(POWER, ctx).size(), "bounds are clamped to 1..200");
    }

    @Test
    void theRowsMustHaveTheValuesShape() throws Exception {
        assertTrue(items(answering(proposal("SELECT i.code, i.title FROM item i WHERE i.category = ?",
                List.of(param("category", "POWER", "STRING")), 0.9, 1, 5))).candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("proposal: SHAPE: the rows lack the column deposit"),
                ctx.allRejected());
        assertTrue(items(answering(proposal("SELECT i.code, i.title, i.deposit, i.barcode FROM item i WHERE"
                + " i.category = ?", List.of(param("category", "POWER", "STRING")), 0.9, 1, 5))).candidates(POWER,
                ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("SHAPE: the rows have columns [BARCODE] that ItemRef does not"),
                ctx.allRejected());
        assertTrue(resolver("StaffPlain", StaffPlain.class, null, state(answering(proposal("SELECT s.code, s.code AS"
                + " roles FROM staff s", List.of(), 0.9, 1, 9)), "0.50")).candidates(new StaffPlain("DESK"), ctx)
                .isEmpty());
        assertTrue(ctx.allRejected().contains("SHAPE: component roles of StaffRef is a list, which one row cannot"
                + " hold; a pack registers a ValueLoader for kind StaffPlain"), ctx.allRejected());
        assertTrue(resolver("LoansByStatus", LoansByStatus.class, null, state(answering(proposal("SELECT l.status,"
                + " COUNT(*) AS n FROM loan l GROUP BY l.status", List.of(), 0.9, 1, 9)), "0.50"))
                .candidates(new LoansByStatus("2026-01-01"), ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("SHAPE: the value type of kind LoansByStatus is a map; a pack registers"
                + " a ValueLoader for it"), ctx.allRejected());
    }

    @Test
    void aScalarValueComesFromItsOneColumn() throws Exception {
        List<ResolverCandidate<Long>> c = resolver("LoanCount", LoanCount.class, null, state(answering(proposal(
                "SELECT COUNT(*) AS value FROM loan l WHERE l.status = ?", List.of(param("status", "OPEN",
                        "STRING")), 0.9, 1, 1)), "0.50")).candidates(new LoanCount("OPEN"), ctx);
        assertEquals(List.of(2L), c.stream().map(ResolverCandidate::value).toList());
    }

    // ------------------------------------------------------------------------------------------------ the loader

    @Test
    void aKindWithAValueLoaderSelectsTheIdentityAndLoadsTheRest() throws Exception {
        String sql = "SELECT s.code FROM staff s JOIN staff_role r ON r.staff_code = s.code WHERE r.role = ? AND"
                + " s.active = TRUE";
        Map<String, Object> p = proposal(sql, List.of(param("role", "DESK", "STRING")), 0.9, 1, 5);
        p.put("tablesUsed", List.of("staff", "staff_role"));
        ScriptedGateway gateway = answering(p);
        LlmSqlResolver<StaffWithRole, StaffRef> r = resolver("StaffWithRole", StaffWithRole.class,
                new DeskPack.RolesLoader(Set.of("S02")), state(gateway, "0.50"));
        List<ResolverCandidate<StaffRef>> c = r.candidates(new StaffWithRole("DESK"), ctx);
        assertEquals(List.of(new StaffRef("S01", List.of("DESK", "SUPERVISOR"))), c.stream()
                .map(ResolverCandidate::value).toList(), "S02 refused by the loader");
        assertEquals("S01", c.get(0).label(), "the identity values joined");
        assertEquals(List.of("S02: the value loader refused it (NOT_ELIGIBLE): staff S02 is on leave"),
                ctx.rejected, "the refused row through the provenance seam");
        String question = ((Content.Text) gateway.requests().get(0).messages().get(0).parts().get(0)).text();
        assertTrue(question.contains("Select exactly these columns, each under this name:\n- code\n"), question);
        assertTrue(ctx.statements.stream().anyMatch(s -> s.startsWith("SELECT r.role FROM staff_role r")),
                "the loader read through the slot's channel");
        Map<String, Object> missing = proposal("SELECT s.name AS who FROM staff s", List.of(), 0.9, 1, 5);
        missing.put("tablesUsed", List.of("staff"));
        assertTrue(resolver("StaffWithRole", StaffWithRole.class, new DeskPack.RolesLoader(Set.of()),
                state(answering(missing), "0.50")).candidates(new StaffWithRole("DESK"), ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("guard PERSONAL"), "the guard first: " + ctx.allRejected());
        Map<String, Object> noIdentity = proposal("SELECT s.joined FROM staff s", List.of(), 0.9, 1, 5);
        noIdentity.put("tablesUsed", List.of("staff"));
        assertTrue(resolver("StaffWithRole", StaffWithRole.class, new DeskPack.RolesLoader(Set.of()),
                state(answering(noIdentity), "0.50")).candidates(new StaffWithRole("DESK"), ctx).isEmpty());
        assertTrue(ctx.allRejected().contains("SHAPE: the rows lack the identity column code"), ctx.allRejected());
    }

    // ------------------------------------------------------------------------------------------------ the model

    @Test
    void aRefusalAndACutAnswerGiveNoCandidates() throws Exception {
        assertTrue(items(ScriptedGateway.answering(r -> ScriptedGateway.refusal("cyber"))).candidates(POWER, ctx)
                .isEmpty());
        assertTrue(ctx.allNotes().contains("the model refused (refusal: cyber)"), ctx.allNotes());
        ModelResponse cut = new ModelResponse(List.of(Content.Text.of("{\"sql\":")), ModelResponse.StopReason.MAX_TOKENS,
                ScriptedGateway.usage(10, 2048, "0.02"), null);
        assertTrue(items(ScriptedGateway.answering(r -> cut)).candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allNotes().contains("the answer was cut at 2048 tokens"));
        assertTrue(items(ScriptedGateway.answering(r -> ScriptedGateway.structured(Map.of("sql", "x"))))
                .candidates(POWER, ctx).isEmpty());
        assertTrue(ctx.allNotes().contains("the answer does not fit SqlProposal"), ctx.allNotes());
    }

    @Test
    void noRecordingIsAnEnvironmentFailureNamingTheSlot(@org.junit.jupiter.api.io.TempDir Path empty) {
        DataException e = assertThrows(DataException.class, () -> items(new RecordedGateway(empty))
                .candidates(POWER, ctx));
        assertEquals(DataException.Kind.UNAVAILABLE, e.kind());
        assertEquals(LlmSqlResolver.SOURCE, e.source());
        assertTrue(e.getMessage().startsWith("no recording for the request of slot item; record with"
                + " -Pinference-record"), e.getMessage());
    }

    @Test
    void aRetryableErrorIsTriedOnceMore() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ScriptedGateway flaky = ScriptedGateway.of(r -> {
            if (calls.getAndIncrement() == 0) {
                throw new InferenceException(Kind.RATE_LIMIT, "HTTP 429 rate_limit_error");
            }
            return ScriptedGateway.structured(goodProposal(), ScriptedGateway.usage(100, 10, "0.001"));
        });
        assertEquals(2, items(flaky).candidates(POWER, ctx).size());
        assertEquals(2, flaky.requests().size());
        assertEquals(1, ledger.size(), "the failure was not billed");
        assertEquals("RATE_LIMIT", log.records().get(0).get("error"));
        ScriptedGateway down = ScriptedGateway.failing(new InferenceException(Kind.OVERLOADED, "HTTP 529"));
        DataException e = assertThrows(DataException.class, () -> items(down).candidates(POWER, ctx));
        assertEquals("the model provider could not be reached: OVERLOADED (HTTP 529)", e.getMessage());
        assertEquals(2, down.requests().size(), "tried once more, not again");
        ScriptedGateway auth = ScriptedGateway.failing(new InferenceException(Kind.AUTH, "HTTP 401"));
        assertThrows(DataException.class, () -> items(auth).candidates(POWER, ctx));
        assertEquals(1, auth.requests().size(), "AUTH fails at once");
    }

    @Test
    void theBudgetIsCheckedBeforeEveryCall() throws Exception {
        ScriptedGateway gateway = answering(goodProposal());
        ResolutionException zero = assertThrows(ResolutionException.class,
                () -> resolver("ItemByCategory", ItemByCategory.class, null, state(gateway, "0"))
                        .candidates(POWER, ctx));
        assertEquals(ResolutionException.BUDGET, zero.reason());
        assertEquals("SCENARIO", zero.detail().get("scope"));
        assertEquals("desk-scenario", zero.detail().get("id"));
        assertEquals("0", zero.detail().get("limitUsd"));
        assertTrue(gateway.requests().isEmpty(), "no call was made");
        ScriptedGateway costly = ScriptedGateway.answering(r -> ScriptedGateway.structured(goodProposal(),
                ScriptedGateway.usage(5000, 500, "0.04")));
        LlmSqlResolver<ItemByCategory, ItemRef> r = resolver("ItemByCategory", ItemByCategory.class, null,
                state(costly, "0.06"));
        assertEquals(2, r.candidates(POWER, ctx).size(), "the first call fits");
        ResolutionException after = assertThrows(ResolutionException.class, () -> r.candidates(POWER, ctx));
        assertEquals(ResolutionException.BUDGET, after.reason());
        assertEquals("0.04", after.detail().get("spentUsd"));
        assertEquals(1, costly.requests().size());
    }

    // ------------------------------------------------------------------------------------------------ batches

    @Test
    void theBatchModePreparesAndCompletes() throws Exception {
        ScriptedGateway gateway = answering(goodProposal());
        BindState s = state(gateway, "0.50");
        LlmSqlResolver<ItemByCategory, ItemRef> r = resolver("ItemByCategory", ItemByCategory.class, null, s);
        ctx.mode = BindMode.RE_RESOLVE;
        Object prepared = r.prepare(POWER, ctx);
        ModelRequest request = assertInstanceOf(ModelRequest.class, prepared);
        assertEquals(CacheHint.LONG, request.cache());
        assertTrue(s.meter.remaining().compareTo(new BigDecimal("0.50")) < 0, "the estimate is reserved");
        assertTrue(gateway.requests().isEmpty(), "nothing is called while parked");
        ModelResponse answer = ScriptedGateway.structured(goodProposal(), ScriptedGateway.usage(100, 10, "0.0005"));
        assertEquals(2, r.complete(POWER, ctx, answer).size());
        assertEquals(new BigDecimal("0.0005"), s.meter.spent());
        assertEquals(2, r.complete(POWER, ctx, new BatchResult(0, answer, null)).size());
        assertEquals(request.fingerprint(), log.records().get(0).get("fingerprint"), "the same request");
        BatchResult overloaded = new BatchResult(0, null, new InferenceException(Kind.OVERLOADED, "busy"));
        assertEquals(2, r.complete(POWER, ctx, overloaded).size(), "a retryable failure is tried inline once");
        assertEquals(1, gateway.requests().size());
        assertThrows(DataException.class, () -> r.complete(POWER, ctx, new InferenceException(Kind.AUTH, "no")));
        assertThrows(IllegalArgumentException.class, () -> r.complete(POWER, ctx, "not an answer"));
    }

    // ------------------------------------------------------------------------------------------------ recordings

    /**
     * The committed recording of the request this code builds for {@code ItemByCategory(POWER, 60.00)} over the test
     * schema (phase5-contracts.md 4.2): a prompt change fails here with "no recording", not with a different answer.
     * {@code -Dskillroy.inference.fixtures.update=true} rewrites it from a scripted answer.
     */
    @Test
    void theCommittedRecordingAnswersTheRequestTheCodeBuildsToday() throws Exception {
        Path fixtures = Path.of("src", "test", "resources", "inference");
        if (Boolean.getBoolean("skillroy.inference.fixtures.update")) {
            Clock clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
            try (RecordingGateway rec = new RecordingGateway(ScriptedGateway.answering(r ->
                    ScriptedGateway.structured(goodProposal(), ScriptedGateway.usage(1310, 140, "0.0040"))),
                    fixtures, PromptMask.keysOnly(), true, clock)) {
                items(rec).candidates(POWER, ctx);
            }
            ctx.notes.clear();
        }
        Path committed = Path.of(LlmSqlResolverTest.class.getResource("/inference").toURI());
        assertTrue(Files.list(committed).anyMatch(f -> f.toString().endsWith(".json")));
        List<ResolverCandidate<ItemRef>> c = items(new RecordedGateway(committed)).candidates(POWER, ctx);
        assertEquals(List.of("DRL-01", "DRL-02"), c.stream().map(ResolverCandidate::label).toList());
        assertEquals("recorded", ledger.entries().get(ledger.size() - 1).provider());
        assertEquals("0", ledger.entries().get(ledger.size() - 1).costUsd());
    }
}
