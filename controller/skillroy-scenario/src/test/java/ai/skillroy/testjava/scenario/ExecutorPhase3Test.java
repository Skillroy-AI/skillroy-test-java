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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executor's Phase 3 behaviour (phase3-contracts.md 6.2, 7, 3.4, 8.2, 11.1 items 2 and 3): a skipped guarded
 * cross-check makes the run DEGRADED with its line and the B14 mapping, a disagreeing one sends nothing after the
 * bind, aggregates over the rows a {@code db} check read, and the base fact root {@code devices}.
 */
class ExecutorPhase3Test {

    static final String WAIVER_SQL = "SELECT loan_id, rule_code, amount, units, waived, posted_at FROM loan_waiver"
            + " WHERE loan_id = ? ORDER BY loan_id, rule_code";

    @TempDir
    Path dir;

    private final ToyResolution toy = new ToyResolution();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private ToyPack pack;
    private RunOptions options;
    /** What the fake database answers for {@link #WAIVER_SQL}, attempt by attempt (the last one repeats). */
    private final List<Rows> waiverRows = new ArrayList<>();
    private final AtomicInteger waiverQueries = new AtomicInteger();

    @BeforeEach
    void setUp() {
        toy.withCrossChecks = true;
        toy.install(agent);
        pack = toy.pack();
        pack.storedTables = List.of(Phase3CompileTest.waiverTable());
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.item", "toy.negate");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "IDLE"));
            return Map.of("accepted", true);
        });
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("toy.unlock", Map.of());
        agent.value("toy.lock", Map.of());
        FakeAgentConnection.Handler query = agent.handler("data.query");
        agent.on("data.query", params -> {
            if (WAIVER_SQL.equals(params.get("sql"))) {
                int attempt = waiverQueries.getAndIncrement();
                return waiverRows.get(Math.min(attempt, waiverRows.size() - 1)).toJson();
            }
            return query.handle(params);
        });
        options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withOutputDir(dir.resolve("out"))
                .withBind(TestCredentials.bind().withEnvId("seed-a").withBindingsRoot(dir.resolve("bindings"))
                        .withMode(BindMode.LIVE));
    }

    private RunResult run(String yaml) throws Exception {
        return new PlanExecutor(pack, agent, options).run(plan(yaml));
    }

    private Plan plan(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    private List<Map<String, Object>> log() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("out").resolve(RunLog.FILE))) {
            out.add(Json.parseObject(line));
        }
        return out;
    }

    private List<Map<String, Object>> log(String type) throws Exception {
        return log().stream().filter(r -> type.equals(r.get("type"))).toList();
    }

    private static Rows waivers(List<List<Object>> rows, boolean truncated) {
        return Rows.of(List.of(new Rows.Column("LOAN_ID", "INTEGER"), new Rows.Column("RULE_CODE", "VARCHAR"),
                new Rows.Column("AMOUNT", "DECIMAL"), new Rows.Column("UNITS", "INTEGER"),
                new Rows.Column("WAIVED", "BOOLEAN"), new Rows.Column("POSTED_AT", "TIMESTAMP")), rows, truncated);
    }

    private static List<Object> row(String code, String amount, Long units) {
        return Arrays.asList(41L, code, amount, units, true, "2026-10-03T10:00:00");
    }

    // ------------------------------------------------------------------------------------------- cross-checks

    static final String CHECKED = """
            scenario: t
            pack: toy
            resolve:
              item: { query: Item, args: { deposit: { between: ["2.00", "50.00"] } } }
            oracle:
              neg: { fn: Negate, args: [$item.deposit], crossCheck: %s }
            steps:
              - lock: { name: a }
              - enter: { value: $item.code }
            verify:
              - { "state.name": IDLE }
            """;

    @Test
    void aRunWhoseBindSkippedAGuardedCheckEndsDegradedWithTheLine() throws Exception {
        agent.capability("toy.negate", CapabilityState.UNAVAILABLE, "no dry run on this build");
        RunResult result = run(CHECKED.formatted("{ check: toy.negate.check, capability: toy.negate }"));
        assertEquals(Status.DEGRADED, result.status(), result.message());
        String line = "oracle neg: cross-check toy.negate.check skipped: capability toy.negate is UNAVAILABLE (no dry"
                + " run on this build)";
        assertEquals(List.of(line), result.skippedExpectations());
        assertEquals(List.of(line), result.skippedCrossChecks());
        assertEquals(List.of(), result.skippedChecks());
        assertEquals("skipped cross-checks: " + line, result.degradedSummary());
        assertEquals(List.of(), result.steps().get(0).skippedExpectations(), "the bind's lines belong to no step");
        Map<String, Object> bind = log("bind").stream().filter(r -> Boolean.TRUE.equals(r.get("ok"))
                && r.containsKey("slots")).findFirst().orElseThrow();
        assertEquals(List.of(line), bind.get("skipped"), "8.2: the bind record lists skipped");
        Map<String, Object> crosscheck = log("crosscheck").get(0);
        assertEquals(true, crosscheck.get("skipped"));
        assertEquals("UNAVAILABLE", crosscheck.get("state"));
        assertTrue(agent.callsTo("toy.negate").isEmpty());
        Map<?, ?> oracles = (Map<?, ?>) Json.parseObject(Files.readString(dir.resolve("out/bindings.json")))
                .get("oracles");
        assertEquals(Map.of("check", "toy.negate.check", "skipped", true), ((Map<?, ?>) oracles.get("neg"))
                .get("crossCheck"), "8.3: the run's bindings.json says {check, skipped}");
        assertEquals(1, agent.capabilityReads, "the binder used the run's one capabilities read");

        String skipped = new JunitXmlReporter(RunOptions.DegradedAs.SKIPPED).render("s", List.of(result), null);
        assertTrue(skipped.contains("<skipped message=\"DEGRADED: skipped cross-checks: " + line + "\"/>"), skipped);
        String failure = new JunitXmlReporter(RunOptions.DegradedAs.FAILURE).render("s", List.of(result), null);
        assertTrue(failure.contains("type=\"DEGRADED\""), failure);
        String pass = new JunitXmlReporter(RunOptions.DegradedAs.PASS).render("s", List.of(result), null);
        assertFalse(pass.contains("<skipped") || pass.contains("<failure"), pass);
    }

    @Test
    void theDegradedSummaryListsSkippedCrossChecksBesideSkippedExpectations() {
        String expectation = "s2: state.name {\"equals\":\"IDLE\"} skipped: capability toy.fancy is UNAVAILABLE";
        String crossCheck = "oracle neg: cross-check toy.negate.check skipped: capability toy.negate is DEGRADED";
        RunResult result = new RunResult("t", Status.DEGRADED, null, null, null, Map.of(), List.of(),
                List.of(crossCheck, expectation), Duration.ZERO, List.of("s3: click fell back from COMPONENT to"
                + " ACTION"));
        assertEquals(List.of(crossCheck), result.skippedCrossChecks());
        assertEquals(List.of(expectation), result.skippedChecks());
        assertEquals("skipped expectations: " + expectation + "; skipped cross-checks: " + crossCheck
                + "; degraded fidelity: s3: click fell back from COMPONENT to ACTION", result.degradedSummary());
        assertFalse(RunResult.isSkippedCrossCheck("verify: oracle x: cross-check y skipped: z"));
    }

    @Test
    void anAgreeingCheckLeavesThePassAndIsRecorded() throws Exception {
        RunResult result = run(CHECKED.formatted("{ check: toy.negate.check, tolerance: \"0.00\" }"));
        assertEquals(Status.PASS, result.status(), result.message());
        Map<String, Object> crosscheck = log("crosscheck").get(0);
        assertEquals(true, crosscheck.get("agreed"));
        assertEquals("0.00", crosscheck.get("tolerance"));
        Map<?, ?> oracles = (Map<?, ?>) Json.parseObject(Files.readString(dir.resolve("out/bindings.json")))
                .get("oracles");
        assertEquals(Map.of("check", "toy.negate.check", "agreed", true), ((Map<?, ?>) oracles.get("neg"))
                .get("crossCheck"));
        List<String> types = log().stream().map(r -> (String) r.get("type")).toList();
        assertTrue(types.indexOf("crosscheck") < types.indexOf("call"), "before the start mark: " + types);
    }

    @Test
    void aDisagreementIsOracleSuspectAndSendsNothingAfterTheBind() throws Exception {
        toy.negateAnswer = "-4.00";
        toy.negateEvidenceNote = "seen " + TestCredentials.PW_100;
        RunResult result = run(CHECKED.formatted("toy.negate.check"));
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.ORACLE_SUSPECT, result.failureKind());
        assertEquals("DISAGREED", result.detail().get("reason"));
        assertEquals("live", result.detail().get("mode"));
        assertEquals("seed-a", result.detail().get("envId"));
        assertTrue(result.message().startsWith("oracle neg (Negate) disagrees with toy.negate.check: expected"),
                result.message());
        assertTrue(agent.callsTo("trace.mark").isEmpty(), "no start mark");
        assertTrue(agent.callsTo("toy.lock").isEmpty() && agent.callsTo("toy.unlock").isEmpty(), "no step, no"
                + " cleanup call");
        assertEquals(0, pack.recovered.get(), "no recover");
        assertEquals(List.of(), result.steps());
        List<String> types = log().stream().map(r -> (String) r.get("type")).toList();
        assertFalse(types.contains("cleanup") || types.contains("recover"), types.toString());
        Map<String, Object> failed = log("bind").stream().filter(r -> Boolean.FALSE.equals(r.get("ok"))).findFirst()
                .orElseThrow();
        assertEquals("ORACLE_SUSPECT", failed.get("failureKind"));
        Map<?, ?> detail = (Map<?, ?>) failed.get("detail");
        assertEquals("DISAGREED", detail.get("reason"), "8.2: the failing bind record carries the detail");
        assertEquals("toy.negate.check", detail.get("check"));
        String everything = Files.readString(dir.resolve("out").resolve(RunLog.FILE)) + result;
        assertFalse(everything.contains(TestCredentials.PW_100), "the evidence is masked everywhere");
        assertTrue(Files.readString(dir.resolve("out").resolve(RunLog.FILE)).contains("seen <redacted>"));
        String xml = new JunitXmlReporter().render("s", List.of(result), null);
        assertTrue(xml.contains("<error message=\"ORACLE_SUSPECT: oracle neg (Negate) disagrees with"
                + " toy.negate.check: expected &quot;-5.00&quot;, the application computes"), xml);
        assertTrue(xml.contains("type=\"ORACLE_SUSPECT\""), xml);
    }

    /**
     * phase3-contracts.md 9: the {@code crosscheck} records a bind emitted reach the caller of {@code resolve} and
     * {@code runWithOutcome} also when the bind failed (as ORACLE_SUSPECT, or later, at a guard), as run.jsonl holds
     * them; after a completed bind they are those of the outcome.
     */
    @Test
    void theCrossCheckRecordsOfAFailedBindReachTheCaller() throws Exception {
        toy.negateAnswer = "-4.00";
        toy.negateEvidenceNote = "seen " + TestCredentials.PW_100;
        PlanExecutor.Resolved suspect = new PlanExecutor(pack, agent, options).runWithOutcome(plan(
                CHECKED.formatted("toy.negate.check")));
        assertEquals(FailureKind.ORACLE_SUSPECT, suspect.result().failureKind());
        assertNull(suspect.outcome(), "the bind failed");
        assertEquals(1, suspect.crossChecks().size());
        Map<String, Object> record = suspect.crossChecks().get(0);
        assertEquals(false, record.get("agreed"));
        assertEquals(Map.of("amount", "-4.00"), record.get("actual"));
        assertEquals("seen <redacted>", ((Map<?, ?>) record.get("evidence")).get("note"), "masked");
        Map<String, Object> logged = new LinkedHashMap<>(log("crosscheck").get(0));
        logged.remove("type");
        logged.remove("t");
        assertEquals(logged, record, "as run.jsonl holds it");
        assertEquals(new ArrayList<>(logged.keySet()), new ArrayList<>(record.keySet()));

        toy.negateAnswer = null;
        toy.negateEvidenceNote = null;
        String guarded = CHECKED.formatted("toy.negate.check").replace("args: [$item.deposit],",
                "args: [$item.deposit], must_equal: \"-1.00\",");
        PlanExecutor.Resolved guard = new PlanExecutor(pack, agent, options).resolve(plan(guarded));
        assertEquals(FailureKind.RESOLUTION_FAILED, guard.result().failureKind(), guard.result().message());
        assertEquals("GUARD", guard.result().detail().get("reason"));
        assertEquals(List.of(true), guard.crossChecks().stream().map(r -> r.get("agreed")).toList(),
                "the agreement before the guard failed");

        PlanExecutor.Resolved bound = new PlanExecutor(pack, agent, options).resolve(plan(CHECKED.formatted(
                "toy.negate.check")));
        assertEquals(Status.PASS, bound.result().status(), bound.result().message());
        assertEquals(List.of(bound.outcome().bindings().crossChecks().get("neg").toJson()), bound.crossChecks());
        assertEquals(bound.crossChecks(), new PlanExecutor.Resolved(bound.result(), bound.outcome()).crossChecks(),
                "the two-argument form derives the records from the outcome");
        assertEquals(List.of(), new PlanExecutor.Resolved(suspect.result(), null).crossChecks());
    }

    // ------------------------------------------------------------------------------------------- aggregates

    static final String AGGREGATES = """
            scenario: t
            pack: toy
            resolve:
              loan: { fixture: { id: 41 } }
            steps: []
            verify:
              - db.loan_waiver: { where: { loan_id: $loan.id }, rows: %s, %s }
            """;

    private RunResult aggregates(String rows, String aggregates) throws Exception {
        return run(AGGREGATES.formatted(rows, aggregates));
    }

    @Test
    void sumMinAndMaxOverDecimalAndIntegerColumnsIgnoringNullCells() throws Exception {
        waiverRows.add(waivers(List.of(row("R1", "0.99", 1L), row("R2", "1.10", 2L), row("R3", null, null)),
                false));
        RunResult result = aggregates("3", "\"sum(amount)\": \"2.09\", \"min(amount)\": 0.99, \"max(amount)\":"
                + " \"1.1\", \"sum(units)\": 3, \"min(units)\": \"1\", \"max(units)\": { gte: 2 }");
        assertEquals(Status.PASS, result.status(), result.message());
        Map<String, Object> db = log("db").get(0);
        Map<String, Object> computed = new LinkedHashMap<>();
        computed.put("max(amount)", "1.10");
        computed.put("max(units)", 2L);
        computed.put("min(amount)", "0.99");
        computed.put("min(units)", 1L);
        computed.put("sum(amount)", "2.09");
        computed.put("sum(units)", 3L);
        assertEquals(computed, db.get("aggregates"), "8.2: the db record carries them as computed, a DECIMAL as"
                + " its plain string, an INTEGER as a whole number");
        assertEquals(List.of("type", "t", "step", "table", "source", "channel", "params", "rowCount", "ok",
                "aggregates"), new ArrayList<>(db.keySet()));
    }

    @Test
    void anAggregateOverNoCellsIsNull() throws Exception {
        waiverRows.add(waivers(List.of(), false));
        RunResult none = aggregates("0", "\"sum(amount)\": { exists: false }, \"max(units)\": { exists: false }");
        assertEquals(Status.PASS, none.status(), none.message());
        Map<String, Object> nulls = new LinkedHashMap<>();
        nulls.put("max(units)", null);
        nulls.put("sum(amount)", null);
        assertEquals(nulls, log("db").get(0).get("aggregates"));
        waiverRows.set(0, waivers(List.of(row("R1", null, null), row("R2", null, null)), false));
        RunResult allNull = aggregates("2", "\"min(amount)\": { exists: false }");
        assertEquals(Status.PASS, allNull.status(), "every cell null: null as well");
    }

    @Test
    void aFailingAggregateIsAssertionWithTheAggregatesInTheDetail() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(300));
        waiverRows.add(waivers(List.of(row("R1", "0.99", 1L), row("R2", "1.10", 2L)), false));
        RunResult result = aggregates("2", "\"sum(amount)\": \"1.98\", \"max(units)\": 2");
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("verify", result.failedStepId());
        assertEquals(Map.of("sum(amount)", Map.of("expected", Map.of("equals", "1.98"), "actual", "2.09")),
                result.detail().get("aggregates"), "only the ones that did not hold");
        assertTrue(result.message().contains("aggregate sum(amount): expected \"1.98\" but was \"2.09\""),
                result.message());
        List<Map<String, Object>> db = log("db");
        assertTrue(db.size() > 1, "retried like an expectation");
        assertEquals(Map.of("max(units)", 2L, "sum(amount)", "2.09"), db.get(0).get("aggregates"));
    }

    @Test
    void aTruncatedResultFailsBeforeAnyAggregateIsComputed() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(300));
        waiverRows.add(waivers(List.of(row("R1", "0.99", 1L)), true));
        RunResult result = aggregates("{ gte: 1 }", "\"sum(amount)\": \"0.99\"");
        assertEquals(Status.FAIL, result.status());
        assertTrue(result.message().contains("more than 200 rows match; narrow the where"), result.message());
        for (Map<String, Object> record : log("db")) {
            assertFalse(record.containsKey("aggregates"), "nothing computed over a cut result");
        }
        assertNull(result.detail().get("aggregates"));
    }

    @Test
    void aggregatesAreRetriedWithTheExpectations() throws Exception {
        waiverRows.add(waivers(List.of(row("R1", "1.10", 2L)), false));
        waiverRows.add(waivers(List.of(row("R1", "0.99", 1L), row("R2", "1.10", 2L)), false));
        RunResult result = aggregates("{ gte: 1 }", "\"sum(amount)\": \"2.09\"");
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(2, waiverQueries.get());
        assertEquals(1, clock.sleeps);
        List<Map<String, Object>> db = log("db");
        assertEquals(List.of(false, true), db.stream().map(r -> r.get("ok")).toList());
        assertEquals(List.of(Map.of("sum(amount)", "1.10"), Map.of("sum(amount)", "2.09")), db.stream()
                .map(r -> r.get("aggregates")).toList());
    }

    @Test
    void aCellThatIsNotANumberCannotBeAggregated() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(300));
        waiverRows.add(waivers(List.of(row("R1", "<redacted>", 1L)), false));
        RunResult result = aggregates("1", "\"sum(amount)\": { exists: true }");
        assertEquals(Status.FAIL, result.status());
        assertTrue(result.message().contains("row 1 holds a value in column amount that is not a number"),
                result.message());
    }

    @Test
    void rowMasksApplyToTheCellsBeforeAggregation() throws Exception {
        String url = "jdbc:hsqldb:mem:aggregates-" + System.nanoTime();
        try (Connection db = DriverManager.getConnection(url, "SA", ""); Statement st = db.createStatement()) {
            st.execute("CREATE TABLE loan_waiver (loan_id BIGINT, rule_code VARCHAR(20), amount DECIMAL(10,2),"
                    + " units INT, waived BOOLEAN, posted_at TIMESTAMP)");
            st.execute("INSERT INTO loan_waiver VALUES (41, 'R1', 0.99, 1, TRUE, TIMESTAMP '2026-10-03 10:00:00')");
            st.execute("INSERT INTO loan_waiver VALUES (41, 'R2', 1.10, 2, TRUE, TIMESTAMP '2026-10-03 10:00:00')");
            Credentials jdbc = TestCredentials.of(Map.of("version", 1L, "jdbc", Map.of(ToyResolution.SOURCE,
                    Map.of("url", url, "user", "SA"))));
            options = options.withBind(options.bind().withCredentials(jdbc));
            // A rule that hides cents: the aggregate must see what the mask left, never the stored value.
            pack.rowMasks = List.of((source, column, value) -> "AMOUNT".equalsIgnoreCase(column) && value != null
                    ? "1.00" : value);
            RunResult masked = aggregates("2", "\"sum(amount)\": \"2.00\", \"sum(units)\": 3");
            assertEquals(Status.PASS, masked.status(), masked.message());
            Map<String, Object> record = log("db").get(0);
            assertEquals("jdbc", record.get("channel"));
            assertEquals(Map.of("sum(amount)", "2.00", "sum(units)", 3L), record.get("aggregates"));
            assertTrue(agent.callsTo("data.query").isEmpty(), "the JDBC source is read on the controller");
        }
    }

    // ------------------------------------------------------------------------------------------- aggregate refs

    static final String REFERENCED = """
            scenario: t
            pack: toy
            resolve:
              loan: { fixture: { id: 41 } }
              assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
            steps:
              - key: { action: DUE }
            verify:
              - db.loan_waiver: { where: { loan_id: $loan.id }, rows: 1, %s }
            """;

    /**
     * phase3-contracts.md 3.2 rule 3, phase2-contracts.md 8.3 step 10: a reference in an aggregate matcher is checked
     * with the rest of the {@code db} element before the first step, as a per-row matcher's is, also under a guard
     * whose capability the target lacks (which would otherwise skip the check and end DEGRADED), and a joined
     * credential field is refused there as anywhere outside call arguments.
     */
    @Test
    void aBadReferenceInAnAggregateFailsBeforeAnyStepAsInAPerRowMatcher() throws Exception {
        waiverRows.add(waivers(List.of(row("R1", "0.99", 1L)), false));
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("rule_code: $loan.nosuch", "$loan.nosuch");
        cases.put("\"sum(amount)\": $loan.nosuch", "$loan.nosuch");
        cases.put("\"max(units)\": { gte: $loan.nosuch }", "$loan.nosuch");
        cases.put("capability: toy.absent, \"sum(amount)\": $loan.nosuch", "$loan.nosuch");
        cases.put("\"sum(amount)\": $assistant.password", "$assistant.password");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            agent.calls.clear();
            RunResult result = run(REFERENCED.formatted(c.getKey()));
            assertEquals(Status.ERROR, result.status(), c.getKey() + ": " + result.message());
            assertEquals(FailureKind.RESOLUTION_FAILED, result.failureKind(), c.getKey());
            assertNull(result.failedStepId(), c.getKey() + ": before the first step");
            assertEquals("REFERENCE", result.detail().get("reason"), c.getKey());
            assertEquals(c.getValue(), result.detail().get("reference"), c.getKey());
            assertTrue(result.message().startsWith("verify db.loan_waiver "), result.message());
            assertTrue(agent.callsTo("toy.input").isEmpty(), c.getKey() + ": no step touched the application");
            assertTrue(agent.callsTo("trace.mark").isEmpty(), c.getKey() + ": no start mark");
            assertEquals(0, waiverQueries.get(), c.getKey() + ": the statement never ran");
            assertEquals(List.of(), result.skippedExpectations(), c.getKey());
        }
        RunResult secret = run(REFERENCED.formatted("\"sum(amount)\": $assistant.password"));
        assertTrue(secret.message().contains("usable only in the arguments of step and cleanup calls"),
                secret.message());
        assertFalse(String.valueOf(secret).contains(TestCredentials.PW_100));
    }

    /** As above with caller-supplied bindings, which only {@link Binder#check} sees. */
    @Test
    void callerSuppliedBindingsAreCheckedInTheAggregatesToo() throws Exception {
        Plan plan = plan(REFERENCED.formatted("\"sum(amount)\": $loan.nosuch"));
        Bindings bindings = Bindings.of(Map.of("loan", Map.of("id", 41L), "assistant", Map.of("code", "100")));
        ResolutionException e = org.junit.jupiter.api.Assertions.assertThrows(ResolutionException.class,
                () -> Binder.check(plan, bindings));
        assertEquals("REFERENCE", e.reason());
        assertEquals("$loan.nosuch", e.reference());
        RunResult result = new PlanExecutor(pack, agent, options).run(plan, bindings);
        assertEquals(FailureKind.RESOLUTION_FAILED, result.failureKind());
        assertNull(result.failedStepId());
        assertTrue(agent.callsTo("toy.input").isEmpty(), "no step touched the application");

        Plan joined = plan(REFERENCED.formatted("\"sum(amount)\": $assistant.password"));
        Bindings withSecret = bindings.withCredential("assistant", Map.of("password", TestCredentials.PW_100));
        ResolutionException refused = org.junit.jupiter.api.Assertions.assertThrows(ResolutionException.class,
                () -> Binder.check(joined, withSecret));
        assertEquals("REFERENCE", refused.reason());
        assertTrue(refused.getMessage().contains("usable only in the arguments of step and cleanup calls"),
                refused.getMessage());
    }

    /** {@code approve} (and {@code skillroy resolve}) write no bindings file for a plan whose aggregate is broken. */
    @Test
    void approveWritesNoBindingsFileWhenAnAggregateReferenceDoesNotResolve() throws Exception {
        waiverRows.add(waivers(List.of(row("R1", "0.99", 1L)), false));
        String yaml = """
                scenario: t
                pack: toy
                resolve:
                  item: { query: Item, args: { deposit: { between: ["2.00", "50.00"] } } }
                  loan: { fixture: { id: 41 } }
                steps:
                  - key: { action: DUE }
                verify:
                  - db.loan_waiver: { where: { loan_id: $loan.id }, %s }
                """;
        Path root = dir.resolve("bindings");
        options = options.withBind(options.bind().withMode(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        RunResult resolved = new PlanExecutor(pack, agent, options).resolve(plan(yaml.formatted(
                "\"sum(amount)\": $item.nosuch"))).result();
        assertEquals(FailureKind.RESOLUTION_FAILED, resolved.failureKind(), resolved.message());
        assertEquals("REFERENCE", resolved.detail().get("reason"));
        assertEquals("$item.nosuch", resolved.detail().get("reference"));
        RunResult run = run(yaml.formatted("\"sum(amount)\": $item.nosuch"));
        assertEquals(FailureKind.RESOLUTION_FAILED, run.failureKind(), run.message());
        assertNull(run.failedStepId());
        assertFalse(Files.exists(BindingsFile.locate(root, "seed-a", "t")), "nothing was written");
        assertTrue(agent.callsTo("toy.input").isEmpty(), "no step touched the application");

        RunResult good = new PlanExecutor(pack, agent, options).resolve(plan(yaml.formatted(
                "\"sum(amount)\": $item.deposit"))).result();
        assertEquals(Status.PASS, good.status(), good.message());
        assertTrue(Files.exists(BindingsFile.locate(root, "seed-a", "t")), "a reference that resolves is written");
    }

    // ------------------------------------------------------------------------------------------- devices

    private void devices(Map<String, Map<String, Object>> states, Object list) {
        agent.on("device.list", params -> list);
        agent.on("device.state", params -> {
            Map<String, Object> state = states.get(String.valueOf(params.get("id")));
            if (state == null) {
                throw new ai.skillroy.testjava.controller.AgentRpcException("device.state",
                        new ai.skillroy.testjava.protocol.RpcError(ErrorCodes.NOT_FOUND, "no device", Map.of()));
            }
            return state;
        });
    }

    static final String DEVICES = """
            scenario: t
            pack: toy
            steps: []
            verify:
              - %s
            """;

    @Test
    void theDevicesRootIsBuiltFromTheListAndOneStatePerDeviceInIdOrder() throws Exception {
        devices(Map.of("printer", Map.of("last", Map.of("loanId", 41L, "text", "WAIVER B2W1-HAND")),
                "drawer", Map.of("open", false)), Map.of("devices", List.of(
                Map.of("id", "printer", "kind", "PRINTER", "actions", List.of("cut", "print")),
                Map.of("id", "drawer", "kind", "DRAWER", "actions", List.of()))));
        RunResult result = run(DEVICES.formatted("{ \"devices[id=printer].state.last.text\": { contains: B2W1 },"
                + " \"devices[id=printer].state.last.loanId\": 41, \"devices[0].id\": drawer,"
                + " \"devices[1].actions\": [cut, print], \"devices[id=drawer].kind\": DRAWER }"));
        assertEquals(Status.PASS, result.status(), result.message());
        List<String> calls = agent.calls.stream().filter(c -> c.method().startsWith("device."))
                .map(c -> c.method() + (c.params().isEmpty() ? "" : " " + c.params().get("id"))).toList();
        assertEquals(List.of("device.list", "device.state drawer", "device.state printer"), calls,
                "one list, then one state per device in id order (one read per attempt)");
        assertTrue(BaseFacts.ROOTS.contains("devices"));
    }

    /** phase8-contracts.md 7.6 (ES): the root mirrors a device's rung ladders, and only when it has one. */
    @Test
    void theDevicesRootMirrorsALadderOnlyWhenTheListHasOne() throws Exception {
        devices(Map.of("printer", Map.of(), "scanner", Map.of("driver", true)), Map.of("devices", List.of(
                Map.of("id", "printer", "kind", "PRINTER", "actions", List.of("print")),
                Map.of("id", "scanner", "kind", "scanner", "actions", List.of("scan"), "rungs",
                        Map.of("scan", List.of("PHYSICAL", "SEMANTIC"))))));
        RunResult result = run(DEVICES.formatted("{ \"devices[id=scanner].rungs.scan[0]\": PHYSICAL,"
                + " \"devices[id=scanner].rungs.scan\": [PHYSICAL, SEMANTIC], \"devices[id=printer].kind\": PRINTER }"));
        assertEquals(Status.PASS, result.status(), result.message());
        RunResult none = run(DEVICES.formatted("{ \"devices[id=printer].rungs\": { size: 0 } }"));
        assertEquals(Status.FAIL, none.status(), none.message());
        assertTrue(none.message().contains("no field 'rungs'"), none.message());
    }

    @Test
    void anEmptyListFailsTheExpectationAndAStateErrorFailsTheFactRead() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(300));
        devices(Map.of(), Map.of("devices", List.of()));
        RunResult empty = run(DEVICES.formatted("{ \"devices[id=printer].state.last.text\": { contains: B2W1 } }"));
        assertEquals(Status.FAIL, empty.status());
        assertEquals(FailureKind.ASSERTION, empty.failureKind());
        assertTrue(empty.message().contains("no element of devices match [id=printer]"), empty.message());

        devices(Map.of(), Map.of("devices", List.of(Map.of("id", "printer", "kind", "PRINTER", "actions",
                List.of()))));
        RunResult broken = run(DEVICES.formatted("{ \"devices[id=printer].kind\": PRINTER }"));
        assertEquals(Status.ERROR, broken.status());
        assertEquals(FailureKind.ENVIRONMENT, broken.failureKind());
        assertTrue(broken.message().startsWith("fact 'devices' could not be read"), broken.message());
    }

    @Test
    void aPackRootNamedDevicesWins() throws Exception {
        devices(Map.of(), Map.of("devices", List.of()));
        agent.value("toy.devices", List.of(Map.of("id", "printer", "kind", "PACK")));
        pack.facts = List.of(new FactProvider() {
            @Override
            public String root() {
                return "devices";
            }

            @Override
            public Object fetch(ai.skillroy.testjava.controller.AgentConnection a) throws Exception {
                return a.call("toy.devices", Map.of()).value();
            }
        }, new FactProvider() {
            @Override
            public String root() {
                return "state";
            }

            @Override
            public Object fetch(ai.skillroy.testjava.controller.AgentConnection a) throws Exception {
                return a.call("toy.state", Map.of()).value();
            }
        });
        RunResult result = run(DEVICES.formatted("{ \"devices[id=printer].kind\": PACK }"));
        assertEquals(Status.PASS, result.status(), result.message());
        assertTrue(agent.callsTo("device.list").isEmpty(), "the base root is not read");
    }
}
