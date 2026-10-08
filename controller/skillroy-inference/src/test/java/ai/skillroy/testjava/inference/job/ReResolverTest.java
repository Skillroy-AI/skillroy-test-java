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

package ai.skillroy.testjava.inference.job;

import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.fakes.FakeAgent;
import ai.skillroy.testjava.inference.fakes.MutableClock;
import ai.skillroy.testjava.inference.resolve.DeskPack;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.CacheHint;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.inference.sql.LendingDatabase;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 9: the {@code re-resolve} job with a scripted batch gateway over three scenarios of two
 * graph depths (and one that needs no bindings) on the inference module's test pack and the embedded lending database:
 * two rounds, one batch per round, the scenarios parked and resumed in order, the verifiers run on resumption, the
 * files written {@code APPROVED} by {@code re-resolve:<jobId>}, the report's statuses and changes, the dry run, the job
 * budget cutting a round, a batch that does not end in time (cancelled, {@code BATCH_TIMEOUT}), a gateway without a
 * batch interface, and the refusals before anything runs.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ReResolverTest {

    static final String ITEM = """
            scenario: desk-item
            pack: lending
            resolve:
              item: { query: ItemByCategory, args: { category: POWER, maxDeposit: "60.00" }, strategy: [llm-sql] }
            steps: []
            """;

    /** Two graph depths: {@code cheaper} needs {@code item}'s deposit, so it parks in the second round. */
    static final String TWO = """
            scenario: desk-two
            pack: lending
            resolve:
              item: { query: ItemByCategory, args: { category: POWER, maxDeposit: "60.00" }, strategy: [llm-sql] }
              cheaper: { query: ItemByCategory, args: { category: POWER, maxDeposit: $item.deposit }, strategy: [llm-sql] }
            steps: []
            """;

    static final String COUNT = """
            scenario: desk-count
            pack: lending
            resolve:
              open: { query: LoanCount, args: { status: OPEN }, strategy: [llm-sql] }
            steps: []
            """;

    static final String QUIET = """
            scenario: desk-quiet
            pack: lending
            steps: []
            """;

    static final String ITEM_SQL = "SELECT i.code, i.title, i.deposit FROM item i WHERE i.category = ? AND"
            + " i.deposit <= ? ORDER BY i.deposit";
    /** Ignores the bound, so that the verifier has to refuse the dearer rows first. */
    static final String CHEAPER_SQL = "SELECT i.code, i.title, i.deposit FROM item i WHERE i.category = ? ORDER BY"
            + " i.deposit DESC";
    static final String COUNT_SQL = "SELECT COUNT(*) AS value FROM loan l WHERE l.status = ?";

    @TempDir
    Path tmp;

    private final DeskPack pack = new DeskPack();
    private final FakeAgent agent = new FakeAgent();
    private LendingDatabase db;
    private Path recordings;
    private List<Plan> plans;

    @BeforeEach
    void open() throws Exception {
        db = LendingDatabase.create("job");
        recordings = Files.createDirectories(tmp.resolve("recordings"));
        plans = List.of(plan(ITEM, "desk-item"), plan(TWO, "desk-two"), plan(COUNT, "desk-count"),
                plan(QUIET, "desk-quiet"));
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    private Plan plan(String yaml, String id) throws Exception {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), id + ".yaml", pack);
    }

    private InferenceSettings settings() {
        return new InferenceSettings("recorded", recordings, false, null, null, null, null, null, null, true, false,
                null, false, null);
    }

    private BindOptions options(Path root) {
        return BindOptions.defaults().withEnvId("test-env").withBindingsRoot(root).withCredentials(db.credentials())
                .withInference(settings());
    }

    private static Map<String, Object> param(String name, String value, String type) {
        return Map.of("name", name, "value", value, "type", type);
    }

    private static Map<String, Object> proposal(String sql, List<Map<String, Object>> params, int min, int max,
                                                String rationale) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sql", sql);
        p.put("params", params);
        p.put("rationale", rationale);
        p.put("tablesUsed", List.of(sql.contains("loan") ? "loan" : "item"));
        p.put("orderBy", "");
        p.put("confidence", 0.9);
        p.put("expectedRows", Map.of("min", (long) min, "max", (long) max));
        p.put("cannotAnswer", false);
        p.put("cannotAnswerReason", "");
        return p;
    }

    /** The scripted model: one answer per slot, a usage with cache reads (90 % of the input). */
    static ModelResponse answer(ModelRequest request, String itemSql) {
        String slot = request.metadata().get(ModelRequest.META_SLOT);
        Usage usage = new Usage("scripted", "claude-sonnet-5-5", "claude-sonnet-5-5", "req-" + slot, 100, 50, 900,
                0, 0, false, "0.0010", Duration.ZERO, null);
        Map<String, Object> p = switch (slot) {
            case "item" -> proposal(itemSql, List.of(param("category", "POWER", "STRING"), param("maxDeposit",
                    "60.00", "NUMBER")), 1, 5, "the items of the category within the bound, cheapest first");
            case "cheaper" -> proposal(CHEAPER_SQL, List.of(param("category", "POWER", "STRING")), 1, 5,
                    "the items of the category, dearest first");
            case "open" -> proposal(COUNT_SQL, List.of(param("status", "OPEN", "STRING")), 1, 1,
                    "the loans of the status counted");
            default -> throw new IllegalStateException("no answer for slot " + slot);
        };
        return ScriptedGateway.structured(p, usage);
    }

    /**
     * A batch gateway over a scripted one: it records each batch's requests, may keep a batch in progress for a
     * number of status reads (moving a clock forward on each), records cancels, and may offer no batch interface.
     */
    static final class TestGateway implements InferenceGateway {

        final ScriptedGateway scripted;
        final List<List<ModelRequest>> batches = new CopyOnWriteArrayList<>();
        final List<String> cancelled = new CopyOnWriteArrayList<>();
        final AtomicInteger statusReads = new AtomicInteger();
        final AtomicInteger collected = new AtomicInteger();
        int inProgressReads;
        /**
         * The job's clock (the tests run the job on it): moved forward on every status read, by default a minute,
         * so that a wait loop that never sees its batch end meets the job's two-hour bound instead of spinning.
         */
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T01:00:00Z"));
        Duration perRead = Duration.ofMinutes(1);
        boolean batch = true;
        InferenceException submitFailure;
        /** Counted down on the first status read. */
        final java.util.concurrent.CountDownLatch firstRead = new java.util.concurrent.CountDownLatch(1);

        TestGateway(Function<ModelRequest, ModelResponse> answers) {
            this.scripted = ScriptedGateway.answering(answers);
        }

        @Override
        public String provider() {
            return "scripted";
        }

        @Override
        public Set<GatewayCapability> capabilities() {
            EnumSet<GatewayCapability> caps = EnumSet.copyOf(scripted.capabilities());
            if (!batch) {
                caps.remove(GatewayCapability.BATCH);
            }
            return caps;
        }

        @Override
        public ModelResponse complete(ModelRequest request) throws InferenceException {
            return scripted.complete(request);
        }

        @Override
        public BatchHandle submitBatch(List<ModelRequest> requests) throws InferenceException {
            if (!batch) {
                throw new UnsupportedOperationException("BATCH");
            }
            if (submitFailure != null) {
                throw submitFailure;
            }
            batches.add(List.copyOf(requests));
            return scripted.submitBatch(requests);
        }

        @Override
        public BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
            statusReads.incrementAndGet();
            firstRead.countDown();
            if (clock != null) {
                clock.advance(perRead);
            }
            if (inProgressReads > 0) {
                inProgressReads--;
                return new BatchStatus(BatchStatus.State.IN_PROGRESS, 0, 0, 0, 0, handle.requests());
            }
            return scripted.batchStatus(handle);
        }

        @Override
        public List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
            collected.incrementAndGet();
            List<BatchResult> results = new ArrayList<>(scripted.collectBatch(handle));
            java.util.Collections.reverse(results);
            return results;
        }

        @Override
        public void cancelBatch(BatchHandle handle) {
            cancelled.add(handle.id());
            scripted.cancelBatch(handle);
        }

        @Override
        public long countTokens(ModelRequest request) {
            return scripted.countTokens(request);
        }

        @Override
        public void close() {
        }
    }

    private static TestGateway gateway() {
        return new TestGateway(r -> answer(r, ITEM_SQL));
    }

    private static List<String> slots(List<ModelRequest> batch) {
        return batch.stream().map(r -> r.metadata().get(ModelRequest.META_SCENARIO) + "/"
                + r.metadata().get(ModelRequest.META_SLOT)).toList();
    }

    /** Runs the job through {@code gateway}; a test gateway's clock is the job's (it moves on every status read). */
    private ReResolveReport run(Path root, JobOptions job, InferenceGateway gateway) throws Exception {
        JobOptions timed = gateway instanceof TestGateway t && t.clock != null ? job.withClock(t.clock) : job;
        return ReResolver.run(plans, pack, agent, options(root), timed, gateway);
    }

    private static JobOptions job(Path out, String id, String budget) {
        return JobOptions.of(out, id, new BigDecimal(budget)).withBatchTimes(Duration.ofHours(2), Duration.ZERO);
    }

    // ------------------------------------------------------------------------------------------------ rounds

    @Test
    void twoRoundsOfOneBatchEachParkAndResumeTheScenariosInOrderAndWriteTheFilesApproved() throws Exception {
        Path root = tmp.resolve("bindings");
        Path out = tmp.resolve("out");
        TestGateway gateway = gateway();
        ReResolveReport report = run(root, job(out, "nightly-1", "1.00"), gateway);

        assertEquals(2, report.rounds());
        assertEquals(2, report.batches(), "one batch per round");
        assertEquals(4, report.requests());
        assertEquals(List.of("desk-item/item", "desk-two/item", "desk-count/open"), slots(gateway.batches.get(0)),
                "the first round's requests in parking order");
        assertEquals(List.of("desk-two/cheaper"), slots(gateway.batches.get(1)), "the second depth parks again");
        for (List<ModelRequest> batch : gateway.batches) {
            for (ModelRequest r : batch) {
                assertEquals(CacheHint.LONG, r.cache(), "the catalogue is cached for an hour in a batch round");
                assertEquals("resolve", r.purpose());
            }
        }
        assertTrue(gateway.scripted.requests().size() == 4, "no request outside the batches");

        assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-item").status());
        assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-two").status());
        assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-count").status());
        assertEquals(ReResolveReport.Status.SKIPPED, report.scenario("desk-quiet").status(), "no bindings needed");
        assertEquals(2, report.scenario("desk-two").parked(), "parked once per depth");
        assertEquals(0, report.exitCode());

        BindingsFile two = BindingsFile.read(BindingsFile.locate(root, "test-env", "desk-two"));
        Resolution cheaper = two.resolutions().get("cheaper");
        assertEquals(Map.of("code", "DRL-01", "deposit", "40.00", "title", "Cordless drill"), cheaper.value(),
                "the verifier ran on resumption: the dearer rows were refused, the first that fits accepted");
        List<String> refused = cheaper.provenance().rejected().stream().map(Provenance.Rejected::label).toList();
        assertEquals(List.of("SAW-01", "DRL-02"), refused);
        assertTrue(cheaper.provenance().rejected().get(0).reason().startsWith("lending.verify.deposit"),
                cheaper.provenance().rejected().get(0).reason());
        assertEquals(Map.of("category", "POWER", "maxDeposit", "40.00"), cheaper.args(), "the upstream value");
        assertEquals("the items of the category, dearest first", cheaper.provenance().rationale(),
                "the rationale is in the file's provenance");
        for (String id : List.of("desk-item", "desk-two", "desk-count")) {
            BindingsFile file = BindingsFile.read(BindingsFile.locate(root, "test-env", id));
            for (Resolution r : file.resolutions().values()) {
                assertEquals(new Approval(Approval.State.APPROVED, "re-resolve:nightly-1"), r.approval(), id);
                assertEquals(Strategy.LLM_SQL, r.strategy());
                assertNotNull(r.usage(), "the model's usage from the ledger");
            }
        }
        assertEquals(2L, BindingsFile.read(BindingsFile.locate(root, "test-env", "desk-count")).resolutions()
                .get("open").value());
        assertFalse(Files.exists(BindingsFile.locate(root, "test-env", "desk-quiet")));

        assertEquals(new BigDecimal("0.0040"), report.costUsd(), "four answers at the scripted cost");
        assertEquals(new BigDecimal("0.9000"), report.cacheHitRatio(), "900 read of 1000 input tokens");
        assertEquals(List.of("claude-sonnet-5-5"), report.models());
        assertTrue(report.ledger().stream().allMatch(l -> Boolean.TRUE.equals(l.get("batch"))), "batch-priced");
        assertEquals(List.of("desk-item/item", "desk-two/item", "desk-count/open", "desk-two/cheaper"),
                report.ledger().stream().map(l -> l.get("scenarioId") + "/" + l.get("slot")).toList(),
                "each answer recorded as its bind resumed: in parking order, round by round");
        String md = Files.readString(out.resolve(ReResolveReport.MARKDOWN_FILE));
        assertTrue(md.contains("3 new, 0 failed, 1 skipped"), md);
        assertTrue(md.contains("Model cost: $0.0040 (budget $1.00)"), md);
        assertTrue(md.contains("cache hits 90.0 %"), md);
        assertTrue(md.contains(ReResolveReport.REVIEWER_INSTRUCTION), md);
        Map<String, Object> json = Json.parseObject(Files.readString(out.resolve(ReResolveReport.JSON_FILE)));
        assertEquals("nightly-1", json.get("jobId"));
        assertEquals(4, Files.readAllLines(out.resolve("cost-ledger.jsonl")).size());
        assertEquals(4, Files.readAllLines(out.resolve("inference.jsonl")).size());
        for (String line : Files.readAllLines(out.resolve("inference.jsonl"))) {
            assertEquals("resolve", Json.parseObject(line).get("purpose"));
        }
        assertTrue(agent.calls().isEmpty(), "the binds read over JDBC: the agent was not asked");

        TestGateway again = gateway();
        ReResolveReport unchanged = run(root, job(out, "nightly-1", "1.00"), again);
        for (String id : List.of("desk-item", "desk-two", "desk-count")) {
            assertEquals(ReResolveReport.Status.UNCHANGED, unchanged.scenario(id).status(), id);
            assertFalse(unchanged.scenario(id).written(), "an identical file is left untouched");
        }

        TestGateway changed = new TestGateway(r -> answer(r, ITEM_SQL.replace("ORDER BY i.deposit",
                "ORDER BY i.deposit DESC")));
        ReResolveReport report3 = run(root, job(out, "nightly-1", "1.00"), changed);
        ReResolveReport.ScenarioReport item = report3.scenario("desk-item");
        assertEquals(ReResolveReport.Status.CHANGED, item.status());
        assertTrue(item.written());
        assertTrue(item.changes().stream().anyMatch(c -> c.startsWith("resolutions.item.value: ")
                && c.contains("DRL-01") && c.contains("DRL-02")), item.changes().toString());
        assertEquals(ReResolveReport.Status.UNCHANGED, report3.scenario("desk-count").status());
        String md3 = report3.toMarkdown();
        assertTrue(md3.contains("## Changed"), md3);
        assertTrue(md3.contains("resolutions.item.value"), md3);

        ReResolveReport relabelled = run(root, job(out, "nightly-2", "1.00"), gateway());
        ReResolveReport.ScenarioReport count = relabelled.scenario("desk-count");
        assertEquals(ReResolveReport.Status.CHANGED, count.status(), "another job id is another approval label");
        assertEquals(List.of("resolutions.open.approval: APPROVED by re-resolve:nightly-1 -> APPROVED by"
                + " re-resolve:nightly-2"), count.changes());
    }

    @Test
    void aDryRunWritesNoBindingsFileAndReportsWhatWouldChange() throws Exception {
        Path root = tmp.resolve("dry");
        Path out = tmp.resolve("dry-out");
        ReResolveReport report = run(root, job(out, "dry-1", "1.00").withDryRun(true), gateway());
        assertTrue(report.dryRun());
        for (String id : List.of("desk-item", "desk-two", "desk-count")) {
            assertEquals(ReResolveReport.Status.NEW, report.scenario(id).status(), id);
            assertFalse(report.scenario(id).written());
            assertFalse(Files.exists(BindingsFile.locate(root, "test-env", id)), "nothing written");
        }
        assertTrue(report.scenario("desk-item").changes().get(0).startsWith("resolutions.item.value: "));
        assertTrue(Files.readString(out.resolve(ReResolveReport.MARKDOWN_FILE)).contains("Dry run"));
        assertTrue(Files.exists(out.resolve("cost-ledger.jsonl")), "the report and the ledger are written");
    }

    // ------------------------------------------------------------------------------------------------ bounds

    @Test
    void theJobBudgetCutsARoundAndStopsLaterBatches() throws Exception {
        TestGateway probe = gateway();
        run(tmp.resolve("probe"), job(null, "probe", "1.00"), probe);
        List<ModelRequest> first = probe.batches.get(0);
        BigDecimal e1 = estimate(probe, first.get(0));
        BigDecimal e2 = estimate(probe, first.get(1));
        BigDecimal budget = e1.add(e2);

        TestGateway gateway = gateway();
        ReResolveReport report = run(tmp.resolve("cut"), job(null, "cut-1", budget.toPlainString()), gateway);
        assertEquals(1, report.batches(), "no batch after the cut round");
        assertEquals(List.of("desk-item/item", "desk-two/item"), slots(gateway.batches.get(0)),
                "the requests that fit, in parking order");
        assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-item").status());
        ReResolveReport.ScenarioReport count = report.scenario("desk-count");
        assertEquals(ReResolveReport.Status.FAILED, count.status());
        assertEquals(FailureKind.RESOLUTION_FAILED, count.failureKind());
        assertEquals("BUDGET", count.reason());
        assertTrue(count.message().contains("job budget of $" + budget.toPlainString()), count.message());
        ReResolveReport.ScenarioReport two = report.scenario("desk-two");
        assertEquals(ReResolveReport.Status.FAILED, two.status(), "its second depth would have needed a batch");
        assertEquals("BUDGET", two.reason());
        assertEquals(1, report.exitCode());
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("cut round 1: 2 of 3 requests")),
                report.notes().toString());
        assertTrue(report.toMarkdown().contains("## Failed"));
    }

    private static BigDecimal estimate(TestGateway gateway, ModelRequest request) {
        return PriceTable.builtIn().estimate("claude-sonnet-5-5", gateway.countTokens(request), request.maxTokens(),
                true);
    }

    @Test
    void aBatchThatDoesNotEndInTimeIsCancelledAndItsScenariosAreBatchTimeout() throws Exception {
        TestGateway gateway = gateway();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T02:00:00Z"));
        gateway.clock = clock;
        gateway.perRead = Duration.ofMinutes(31);
        gateway.inProgressReads = Integer.MAX_VALUE;
        JobOptions job = job(tmp.resolve("late-out"), "late-1", "1.00").withClock(clock);
        ReResolveReport report = run(tmp.resolve("late"), job, gateway);
        assertEquals(1, gateway.cancelled.size(), "the cancel was sent");
        assertEquals(0, gateway.collected.get(), "nothing collected");
        assertEquals(4, gateway.statusReads.get(), "a status read every poll until two hours had passed");
        for (String id : List.of("desk-item", "desk-two", "desk-count")) {
            ReResolveReport.ScenarioReport s = report.scenario(id);
            assertEquals(ReResolveReport.Status.FAILED, s.status(), id);
            assertEquals(FailureKind.ENVIRONMENT, s.failureKind());
            assertEquals(ReResolveReport.REASON_BATCH_TIMEOUT, s.reason());
            assertFalse(Files.exists(BindingsFile.locate(tmp.resolve("late"), "test-env", id)));
        }
        assertEquals(ReResolveReport.Status.SKIPPED, report.scenario("desk-quiet").status());
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("was cancelled")), report.notes().toString());
        assertEquals(Duration.ofMinutes(124), report.duration(), "measured on the job's clock");
    }

    @Test
    void aBatchThatEndsAfterSomeStatusReadsIsCollected() throws Exception {
        TestGateway gateway = gateway();
        gateway.inProgressReads = 3;
        ReResolveReport report = run(tmp.resolve("slow"), job(null, "slow-1", "1.00"), gateway);
        assertEquals(5, gateway.statusReads.get(), "three in progress, then ended, then the second round's");
        assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-two").status());
    }

    /** A positive poll interval: the job waits between two status reads (the bounded wait), then collects. */
    @Test
    void aPositivePollWaitsBetweenStatusReads() throws Exception {
        TestGateway gateway = gateway();
        gateway.inProgressReads = 2;
        long started = System.nanoTime();
        ReResolveReport report = run(tmp.resolve("polled"), job(null, "polled-1", "1.00").withBatchTimes(
                Duration.ofHours(2), Duration.ofMillis(40)), gateway);
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 80, "two waits of the poll interval");
        assertEquals(4, gateway.statusReads.get(), "two in progress, then ended, then the second round's");
        assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-two").status());
    }

    /** An interrupt while the job waits for a batch cancels the batch and ends the job with the interrupt. */
    @Test
    void anInterruptWhileWaitingForABatchCancelsIt() throws Exception {
        TestGateway gateway = gateway();
        gateway.inProgressReads = Integer.MAX_VALUE;
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic
                .AtomicReference<>();
        Thread job = new Thread(() -> {
            try {
                run(tmp.resolve("interrupted"), job(null, "interrupted-1", "1.00").withBatchTimes(
                        Duration.ofHours(2), Duration.ofMinutes(10)), gateway);
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "re-resolve-job");
        job.start();
        assertTrue(gateway.firstRead.await(30, TimeUnit.SECONDS), "the job reached its wait for the batch");
        job.interrupt();
        job.join(30_000);
        assertFalse(job.isAlive(), "the interrupt ended the wait");
        assertTrue(thrown.get() instanceof InterruptedException, String.valueOf(thrown.get()));
        assertEquals(1, gateway.cancelled.size(), "the batch in flight was cancelled");
        assertEquals(0, gateway.collected.get());
    }

    /**
     * 8.3 (the L1 sceptic's S3): after a failed batch, a resolver retries inline at the regular rate; the job meter
     * reserves that call's estimate before the bind resumes, so that the job budget bounds it: without room the
     * scenario fails BUDGET and no call is made; with room the call is made and counted.
     */
    @Test
    void anInlineRetryAfterAFailedBatchIsReservedOnTheJobBudget() throws Exception {
        plans = List.of(plan(ITEM, "desk-item"));
        TestGateway probe = gateway();
        run(tmp.resolve("probe-retry"), job(null, "probe-retry", "1.00"), probe);
        BigDecimal batchRound = estimate(probe, probe.batches.get(0).get(0));

        TestGateway gateway = gateway();
        gateway.submitFailure = new InferenceException(InferenceException.Kind.OVERLOADED, "HTTP 529 overloaded");
        try (ai.skillroy.testjava.inference.Gateways.Served served = ai.skillroy.testjava.inference.Gateways.serve(
                settings(), gateway)) {
            // The round's batch fits exactly; its retry, at twice the batch rate, does not.
            ReResolveReport report = run(tmp.resolve("no-room"), job(null, "no-room-1", batchRound.toPlainString()),
                    gateway);
            ReResolveReport.ScenarioReport item = report.scenario("desk-item");
            assertEquals(ReResolveReport.Status.FAILED, item.status());
            assertEquals(FailureKind.RESOLUTION_FAILED, item.failureKind());
            assertEquals("BUDGET", item.reason());
            assertTrue(item.message().contains("no room for the inline retry"), item.message());
            assertTrue(gateway.scripted.requests().isEmpty(), "no retry was made");
            assertTrue(report.costUsd().compareTo(batchRound) <= 0, "within the job budget");
        }
        TestGateway roomy = gateway();
        roomy.submitFailure = new InferenceException(InferenceException.Kind.OVERLOADED, "HTTP 529 overloaded");
        try (ai.skillroy.testjava.inference.Gateways.Served served = ai.skillroy.testjava.inference.Gateways.serve(
                settings(), roomy)) {
            ReResolveReport report = run(tmp.resolve("room"), job(null, "room-1", "1.00"), roomy);
            assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-item").status());
            assertEquals(1, roomy.scripted.requests().size(), "the inline retry, made");
            assertEquals(0, new BigDecimal("0.0010").compareTo(report.costUsd()), "and counted by the job");
        }
    }

    @Test
    void aGatewayWithoutABatchInterfaceAnswersTheRoundsOneByOne() throws Exception {
        TestGateway gateway = gateway();
        gateway.batch = false;
        ReResolveReport report = run(tmp.resolve("single"), job(null, "single-1", "1.00"), gateway);
        assertEquals(0, report.batches());
        assertEquals(4, report.requests());
        assertEquals(2, report.rounds());
        assertTrue(gateway.batches.isEmpty());
        assertEquals(4, gateway.scripted.requests().size());
        assertTrue(report.notes().stream().anyMatch(n -> n.contains("has no batch interface")));
        assertEquals(ReResolveReport.Status.NEW, report.scenario("desk-two").status());
        assertFalse(report.ledger().get(0).get("batch").equals(Boolean.TRUE), "the regular rate");
    }

    @Test
    void aFailedSubmissionFailsTheParkedScenariosThroughTheirResolver() throws Exception {
        TestGateway gateway = gateway();
        gateway.submitFailure = new InferenceException(InferenceException.Kind.AUTH, "HTTP 401");
        ReResolveReport report = run(tmp.resolve("auth"), job(null, "auth-1", "1.00"), gateway);
        ReResolveReport.ScenarioReport item = report.scenario("desk-item");
        assertEquals(ReResolveReport.Status.FAILED, item.status());
        assertEquals(FailureKind.ENVIRONMENT, item.failureKind());
        assertTrue(item.message().contains("could not be reached: AUTH"), item.message());
        assertEquals(1, report.exitCode());
    }

    // ------------------------------------------------------------------------------------------------ refusals

    @Test
    void theJobRefusesToStartWithoutABudgetOrAGatewayOrWithTwoPlansOfOneId() throws Exception {
        ReResolveException noBudget = assertThrows(ReResolveException.class, () -> ReResolver.run(plans, pack, agent,
                options(tmp.resolve("x")), new JobOptions(null, "j", null, false, null, null, null, null)));
        assertTrue(noBudget.beforeStart());
        assertTrue(noBudget.getMessage().contains("needs a job budget"), noBudget.getMessage());
        ReResolveException none = assertThrows(ReResolveException.class, () -> ReResolver.run(plans, pack, agent,
                options(tmp.resolve("x")).withInference(InferenceSettings.none()), job(null, "j", "1.00")));
        assertTrue(none.getMessage().contains("name none"), none.getMessage());
        List<Plan> twice = List.of(plans.get(0), plans.get(0));
        ReResolveException dup = assertThrows(ReResolveException.class, () -> ReResolver.run(twice, pack, agent,
                options(tmp.resolve("x")), job(null, "j", "1.00"), gateway()));
        assertTrue(dup.getMessage().contains("desk-item"), dup.getMessage());
        assertThrows(IllegalArgumentException.class, () -> JobOptions.of(null, "no spaces", BigDecimal.ONE));
        assertEquals("re-resolve:j", job(null, "j", "1").approvalLabel());
        assertEquals("pipeline", job(null, "j", "1").withApprovedBy("pipeline").approvalLabel());

        BindException oneShot = assertThrows(BindException.class, () -> Binder.bind(plans.get(0), pack, agent,
                options(tmp.resolve("x")).withMode(BindMode.RE_RESOLVE), BindListener.NONE));
        assertEquals(FailureKind.ENVIRONMENT, oneShot.kind());
        assertTrue(oneShot.getMessage().startsWith("re-resolve is a command: skillroy re-resolve"),
                oneShot.getMessage());
    }

    @Test
    void aFailedPreflightFailsEveryScenarioThatNeedsBindingsBeforeAnyBind() throws Exception {
        DomainPack checked = new DomainPack() {
            @Override
            public String id() {
                return pack.id();
            }

            @Override
            public String version() {
                return pack.version();
            }

            @Override
            public ai.skillroy.testjava.scenario.pack.StepLibrary steps() {
                return pack.steps();
            }

            @Override
            public ai.skillroy.testjava.scenario.pack.SyncPolicy syncPolicy() {
                return pack.syncPolicy();
            }

            @Override
            public List<ai.skillroy.testjava.scenario.pack.FactProvider> facts() {
                return pack.facts();
            }

            @Override
            public ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue resolution() {
                return pack.resolution();
            }

            @Override
            public List<ai.skillroy.testjava.scenario.resolve.SqlAllowList> sqlAllowLists() {
                return pack.sqlAllowLists();
            }

            @Override
            public List<PreflightCheck> preflight() {
                return List.of(new PreflightCheck() {
                    @Override
                    public String id() {
                        return "desk.open";
                    }

                    @Override
                    public String check(ai.skillroy.testjava.controller.AgentConnection a) {
                        return "the desk is closed";
                    }
                });
            }
        };
        TestGateway gateway = gateway();
        ReResolveReport report = ReResolver.run(plans, checked, agent, options(tmp.resolve("pre")),
                job(null, "pre-1", "1.00"), gateway);
        for (String id : List.of("desk-item", "desk-two", "desk-count")) {
            ReResolveReport.ScenarioReport s = report.scenario(id);
            assertEquals(FailureKind.ENVIRONMENT, s.failureKind(), id);
            assertEquals(ReResolveReport.REASON_PREFLIGHT, s.reason());
            assertEquals("pre-flight check desk.open: the desk is closed", s.message());
        }
        assertEquals(ReResolveReport.Status.SKIPPED, report.scenario("desk-quiet").status());
        assertTrue(gateway.batches.isEmpty(), "nothing was bound or asked");
    }

    @Test
    void theSharedGatewayOfTheSettingsServesTheJobWhenNoneIsGiven() throws Exception {
        // The recorded gateway of an empty directory has no recording: each request fails NO_RECORDING through the
        // resolver, which reports it as ENVIRONMENT, and the job still ends with a report.
        ReResolveReport report = ReResolver.run(plans, pack, agent, options(tmp.resolve("rec")),
                job(tmp.resolve("rec-out"), "rec-1", "1.00"));
        assertEquals("recorded", report.provider());
        ReResolveReport.ScenarioReport item = report.scenario("desk-item");
        assertEquals(ReResolveReport.Status.FAILED, item.status());
        assertTrue(item.message().contains("no recording"), item.message());
        assertNull(report.cacheHitRatio(), "nothing answered");
        assertTrue(Files.exists(tmp.resolve("rec-out").resolve(ReResolveReport.JSON_FILE)));
    }
}
