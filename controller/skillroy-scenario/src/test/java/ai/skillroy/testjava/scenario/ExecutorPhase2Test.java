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

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executor's Phase 2 behaviour (phase2-contracts.md 11, 14.1 item 7): capture, stored-row checks, nothing sent
 * after a failed bind, the ledger check and the run outputs.
 */
class ExecutorPhase2Test {

    static final String LINE_SQL = "SELECT loan_id, item_code, amount, returned, note FROM loan_line WHERE loan_id"
            + " = ? AND item_code = ? AND amount = CAST(? AS DECIMAL(10,2)) AND returned = ? ORDER BY loan_id,"
            + " item_code";

    @TempDir
    Path dir;

    private final ToyResolution toy = new ToyResolution();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private ToyPack pack;
    private RunOptions options;

    @BeforeEach
    void setUp() {
        toy.install(agent);
        pack = toy.pack();
        pack.storedTables = List.of(StoredTable.of("loan_line", ToyResolution.SOURCE, "loan_line", List.of(
                StoredColumn.of("loan_id", ColumnKind.INTEGER),
                StoredColumn.of("item_code", ColumnKind.TEXT),
                StoredColumn.of("amount", ColumnKind.DECIMAL).param("CAST(? AS DECIMAL(10,2))"),
                StoredColumn.of("returned", ColumnKind.BOOLEAN),
                StoredColumn.of("note", ColumnKind.TEXT)), List.of("loan_id", "item_code")));
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.item");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            if ("POST".equals(params.get("value"))) {
                agent.publish("loan.posted", Map.of("loanId", 41L, "header", Map.of("total", "-5.00")));
            }
            agent.publish("input.requested", Map.of("state", "IDLE"));
            return Map.of("accepted", true);
        });
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("toy.unlock", Map.of());
        agent.value("toy.lock", Map.of());
        options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withOutputDir(dir.resolve("out"))
                .withBind(TestCredentials.bind().withEnvId("seed-a").withBindingsRoot(dir.resolve("bindings")));
    }

    private RunResult run(String yaml) throws Exception {
        Plan plan = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    private List<Map<String, Object>> log() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("out").resolve(RunLog.FILE))) {
            out.add(Json.parseObject(line));
        }
        return out;
    }

    private List<String> types() throws Exception {
        return log().stream().map(r -> (String) r.get("type")).toList();
    }

    private static Rows line(List<List<Object>> rows, boolean truncated) {
        return Rows.of(List.of(new Rows.Column("LOAN_ID", "INTEGER"), new Rows.Column("ITEM_CODE", "VARCHAR"),
                new Rows.Column("AMOUNT", "DECIMAL"), new Rows.Column("RETURNED", "BOOLEAN"),
                new Rows.Column("NOTE", "VARCHAR")), rows, truncated);
    }

    static final String CAPTURE = """
            scenario: t
            pack: toy
            steps:
              - enter: { value: POST }
              - waitFor: { topic: loan.posted, within: 2s,
                           capture: { slot: loan, fields: { id: loanId, total: header.total } } }
              - enter: { value: "loan $loan.id" }
            verify:
              - { "state.name": IDLE }
            """;

    @Test
    void captureBindsTheSlotFromTheAwaitedEventAndBindingsJsonIsRewritten() throws Exception {
        RunResult result = run(CAPTURE);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals("loan 41", agent.callsTo("toy.input").get(1).params().get("value"));
        Map<String, Object> capture = log().stream().filter(r -> "capture".equals(r.get("type"))).findFirst()
                .orElseThrow();
        assertEquals(Map.of("id", 41L, "total", "-5.00"), capture.get("value"));
        assertEquals("s2", capture.get("step"));
        Map<String, Object> bindings = Json.parseObject(Files.readString(dir.resolve("out/bindings.json")));
        assertEquals(Map.of("loan", Map.of("id", 41L, "total", "-5.00")), bindings.get("captured"),
                "rewritten at run end with captured");
        assertEquals(BindMode.REPLAY, result.mode());
        assertEquals("seed-a", result.envId());
        assertEquals(List.of(), result.modelCalls());
        assertEquals("", Files.readString(dir.resolve("out/cost-ledger.jsonl")), "always written; empty");
    }

    @Test
    void anEventWithoutTheCapturedPayloadValueFailsTheStep() throws Exception {
        RunResult result = run(CAPTURE.replace("header.total", "header.missing"));
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("s2", result.failedStepId());
        assertEquals("event loan.posted has no payload value at header.missing", result.message());
    }

    @Test
    void anEventSyncCallCapturesToo() throws Exception {
        agent.on("toy.emit", params -> {
            agent.publish("toy.done", Map.of("id", "7"));
            return Map.of();
        });
        RunResult result = run("""
                scenario: t
                pack: toy
                steps:
                  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done,
                            capture: { slot: done, fields: { id: id } } } }
                  - enter: { value: $done.id }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals("7", agent.callsTo("toy.input").get(0).params().get("value"));
    }

    static final String DB = """
            scenario: t
            pack: toy
            resolve:
              item: { fixture: { code: B2 } }
            steps:
              - enter: { value: POST }
              - waitFor: { topic: loan.posted, within: 2s, capture: { slot: loan, fields: { id: loanId } } }
            verify:
              - db.loan_line: { where: { loan_id: $loan.id, item_code: $item.code, amount: "-5.00",
                                         returned: "true" },
                                amount: "-5", returned: true, note: { exists: false } }
            """;

    @Test
    void dbChecksMatchColumnsByPositionConvertParametersAndRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<List<Object>> seen = new ArrayList<>();
        FakeAgentConnection.Handler query = agent.handler("data.query");
        agent.on("data.query", params -> {
            if (LINE_SQL.equals(params.get("sql"))) {
                seen.add(new ArrayList<>((List<?>) params.get("params")));
                List<List<Object>> rows = attempts.incrementAndGet() == 1 ? List.of()
                        : List.of(java.util.Arrays.asList(41L, "B2", "-5.00", true, null));
                return line(rows, false).toJson();
            }
            return query.handle(params);
        });
        RunResult result = run(DB);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(41L, "B2", "-5.00", Boolean.TRUE), seen.get(0), "INTEGER as Long, DECIMAL as its"
                + " plain string, BOOLEAN as Boolean");
        assertEquals(2, attempts.get(), "retried like an expectation");
        assertEquals(1, clock.sleeps);
        List<Map<String, Object>> db = log().stream().filter(r -> "db".equals(r.get("type"))).toList();
        assertEquals(List.of(0L, 1L), db.stream().map(r -> r.get("rowCount")).toList());
        assertEquals(List.of(false, true), db.stream().map(r -> r.get("ok")).toList());
        assertEquals(List.of("agent", "agent"), db.stream().map(r -> r.get("channel")).toList(),
                "each attempt records the channel that served the source");
        for (Map<String, Object> record : db) {
            assertEquals(List.of("type", "t", "step", "table", "source", "channel", "params", "rowCount", "ok"),
                    List.copyOf(record.keySet()), "11.3: per attempt table, source, channel, params, row count, ok");
            assertEquals("loan_line", record.get("table"));
            assertEquals(ToyResolution.SOURCE, record.get("source"));
        }
        assertTrue(types().contains("sources"));
    }

    /**
     * phase8-contracts.md 4.4 and 13.1 row 11 (gaps 12, 117): over a positional source the agent names the columns
     * {@code c1..cn} (or by the statement's labels) and the executor matches stored-row checks by position, never by
     * name, so such a source can be checked; a zero-row answer carries the columns' width (the agent's width rule), so
     * an absence check ({@code rows: 0}) holds. No controller code changed for this: the test documents the rule.
     */
    @Test
    void aPositionalSourceIsCheckedByPositionAndAnAbsenceCheckHolds() throws Exception {
        FakeAgentConnection.Handler query = agent.handler("data.query");
        List<Rows.Column> positional = List.of(new Rows.Column("c1", null), new Rows.Column("c2", null),
                new Rows.Column("c3", null), new Rows.Column("c4", null), new Rows.Column("c5", null));
        List<Object> answer = new ArrayList<>();
        agent.on("data.query", params -> LINE_SQL.equals(params.get("sql")) ? answer.get(0) : query.handle(params));
        answer.add(Rows.of(positional, List.of(java.util.Arrays.asList(41L, "B2", "-5.00", true, null)), false)
                .toJson());
        RunResult held = run(DB);
        assertEquals(Status.PASS, held.status(), held.message());

        answer.set(0, Rows.of(positional, List.of(), false).toJson());
        RunResult absent = run(DB.replace("note: { exists: false } }", "rows: 0 }")
                .replace("amount: \"-5\", returned: true, ", ""));
        assertEquals(Status.PASS, absent.status(), absent.message());

        answer.set(0, Rows.of(List.of(), List.of(), false).toJson());
        RunResult noColumns = run(DB.replace("note: { exists: false } }", "rows: 0 }")
                .replace("amount: \"-5\", returned: true, ", ""));
        assertEquals(Status.ERROR, noColumns.status(), "a zero-row answer without columns, as before Phase 8");
        assertTrue(noColumns.message().contains("returned 0 columns, the pack declares 5"), noColumns.message());
    }

    @Test
    void aDbCheckWhoseCapabilityIsNotOkIsSkippedAndRecordedUnderItsTable() throws Exception {
        RunResult result = run(DB.replace("note: { exists: false } }", "note: { exists: false },"
                + " capability: toy.db }"));
        assertEquals(Status.DEGRADED, result.status(), result.message());
        Map<String, Object> skip = log().stream().filter(r -> "db".equals(r.get("type"))).findFirst().orElseThrow();
        assertEquals("skipped", skip.get("result"));
        assertEquals("loan_line", skip.get("table"), "the key is table, as in every other db record");
        assertEquals(ToyResolution.SOURCE, skip.get("source"));
        assertFalse(skip.containsKey("db"));
    }

    @Test
    void dbCheckFailuresByKind() throws Exception {
        options = options.withStepTimeout(Duration.ofMillis(300));
        FakeAgentConnection.Handler query = agent.handler("data.query");
        List<Object> answer = new ArrayList<>();
        agent.on("data.query", params -> LINE_SQL.equals(params.get("sql")) ? answer.get(0) : query.handle(params));

        answer.add(line(List.of(java.util.Arrays.asList(41L, "B2", "-4.00", true, null)), false).toJson());
        RunResult wrongValue = run(DB);
        assertEquals(Status.FAIL, wrongValue.status());
        assertEquals(FailureKind.ASSERTION, wrongValue.failureKind());
        assertEquals("verify", wrongValue.failedStepId());
        assertTrue(wrongValue.message().contains("row 1 column amount"), wrongValue.message());
        assertEquals("loan_line", wrongValue.detail().get("db"));
        assertEquals(Map.of("loan_id", 41L, "item_code", "B2", "amount", "-4.00", "returned", true, "note", "null"),
                stringNull(wrongValue.detail().get("row")), "the first non-matching row");

        answer.set(0, line(List.of(), true).toJson());
        RunResult truncated = run(DB);
        assertTrue(truncated.message().contains("more than 200 rows match"), truncated.message());

        answer.set(0, Rows.of(List.of(new Rows.Column("X", "INTEGER")), List.of(List.of(1L)), false).toJson());
        RunResult columns = run(DB);
        assertEquals(Status.ERROR, columns.status());
        assertEquals(FailureKind.ENVIRONMENT, columns.failureKind());
        assertTrue(columns.message().contains("returned 1 columns, the pack declares 5"), columns.message());
        assertFailedDbRecord();

        agent.on("data.query", params -> {
            throw new ai.skillroy.testjava.controller.AgentRpcException("data.query",
                    new ai.skillroy.testjava.protocol.RpcError(ai.skillroy.testjava.protocol.ErrorCodes.INTERNAL_ERROR,
                            "driver said something", Map.of("sqlState", "42000")));
        });
        RunResult sql = run(DB);
        assertEquals(FailureKind.ENVIRONMENT, sql.failureKind());
        assertEquals("SQL", sql.detail().get("dataKind"));
        assertFailedDbRecord();

        RunResult nullParam = run(DB.replace("item_code: $item.code", "item_code: $item.none")
                .replace("code: B2 }", "code: B2, none: null }"));
        assertEquals(FailureKind.RESOLUTION_FAILED, nullParam.failureKind());
        assertEquals("REFERENCE", nullParam.detail().get("reason"));
    }

    /**
     * {@code expectationsOnMaskedValues} on a stored-row check (phase5-contracts.md 12.2, the fix wave): a column value
     * the run's mask changes satisfies no matcher and the row reported carries it masked; without the option (a
     * scenario a person committed) the value is matched as read.
     */
    @Test
    void aDbCheckOnAMaskedColumnHoldsOnlyWithoutTheMaskedOption() throws Exception {
        FakeAgentConnection.Handler query = agent.handler("data.query");
        agent.on("data.query", params -> LINE_SQL.equals(params.get("sql")) ? line(List.of(java.util.Arrays.asList(
                41L, "B2", "-5.00", true, TestCredentials.PW_100)), false).toJson() : query.handle(params));
        String yaml = DB.replace("note: { exists: false }", "note: { matches: \"^"
                + TestCredentials.PW_100.substring(0, 4) + "\" }");
        RunResult plain = run(yaml);
        assertEquals(Status.PASS, plain.status(), plain.message());

        options = options.withStepTimeout(Duration.ofMillis(300)).withExpectationsOnMaskedValues(true);
        RunResult masked = run(yaml);
        assertEquals(Status.FAIL, masked.status(), masked.message());
        assertEquals(FailureKind.ASSERTION, masked.failureKind());
        assertTrue(masked.message().endsWith("row 1 column note: " + Matchers.HOLDS_A_SECRET), masked.message());
        assertEquals(SecretMask.REDACTED, ((Map<?, ?>) masked.detail().get("row")).get("note"));
    }

    /** The last db record failed and, like every attempt, names the table, the source and the channel (11.3). */
    private void assertFailedDbRecord() throws Exception {
        List<Map<String, Object>> db = log().stream().filter(r -> "db".equals(r.get("type"))).toList();
        Map<String, Object> failed = db.get(db.size() - 1);
        assertEquals(false, failed.get("ok"));
        assertEquals("loan_line", failed.get("table"));
        assertEquals(ToyResolution.SOURCE, failed.get("source"));
        assertEquals("agent", failed.get("channel"));
    }

    private static Map<String, Object> stringNull(Object row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        ((Map<?, ?>) row).forEach((k, v) -> out.put(String.valueOf(k), v == null ? "null" : v));
        return out;
    }

    @Test
    void aSourceNoChannelServesIsAPreconditionAndNothingIsSent() throws Exception {
        agent.value("data.sources", Map.of("sources", List.of()));
        RunResult result = run(DB);
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.PRECONDITION_FAILED, result.failureKind());
        assertTrue(result.message().contains("toy"), result.message());
        assertTrue(agent.callsTo("trace.mark").isEmpty());
    }

    @Test
    void resolveChecksTheServedSourcesToo() throws Exception {
        agent.value("data.sources", Map.of("sources", List.of()));
        Plan plan = PlanCompiler.compile(DB.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        PlanExecutor.Resolved resolved = new PlanExecutor(pack, agent, options).resolve(plan);
        assertEquals(Status.ERROR, resolved.result().status());
        assertEquals(FailureKind.PRECONDITION_FAILED, resolved.result().failureKind(), "11.2 step 3, as for run");
        assertTrue(resolved.result().message().contains("toy"), resolved.result().message());
        assertTrue(agent.callsTo("trace.mark").isEmpty());
        assertTrue(types().contains("sources"));

        agent.value("data.sources", Map.of("sources", List.of(Map.of("id", ToyResolution.SOURCE))));
        PlanExecutor.Resolved served = new PlanExecutor(pack, agent, options).resolve(plan);
        assertEquals(Status.PASS, served.result().status(), served.result().message());
        assertTrue(agent.callsTo("trace.mark").isEmpty(), "resolve never marks a start");
    }

    /**
     * 12.1 and 11.4: {@code resolve} in {@code live} without {@code writeBindings} returns the file the CLI's
     * {@code --dry-run} prints; a file that would hold a credential value (here a 17-character one with a quote and a
     * backslash, which the text mask cannot see in written JSON) is refused there as {@code approve} refuses to write
     * it, with the same kind and message; a plain {@code live} run, which prints no file, is not affected.
     */
    @Test
    void resolveInLiveRefusesAFileThatWouldHoldACredentialAsApproveDoes() throws Exception {
        String secret = "pw\"in\\side-secret";
        pack.resolution = ResolutionCatalogue.builder(toy.catalogue())
                .oracle(new CrossCheckBinderTest.Echo()).build();
        options = options.withBind(options.bind().withMode(BindMode.LIVE).withCredentials(TestCredentials.of(Map.of(
                "version", 1L, "credentials", Map.of("staff", Map.of("1", Map.of("password", secret)))))));
        String yaml = """
                scenario: t
                pack: toy
                oracle:
                  same: { fn: Echo, args: %s }
                steps: []
                """;
        Plan leaky = PlanCompiler.compile(yaml.formatted("'pw\"in\\side-secret'").getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
        PlanExecutor.Resolved refused = new PlanExecutor(pack, agent, options).resolve(leaky);
        assertEquals(Status.ERROR, refused.result().status());
        assertEquals(FailureKind.ENVIRONMENT, refused.result().failureKind());
        assertEquals("the bindings file was not written: a recorded value equals a credential",
                refused.result().message());
        assertNull(refused.outcome(), "nothing of the file reaches the caller");
        assertEquals("live", refused.result().detail().get("mode"));
        Map<String, Object> failed = log().stream().filter(r -> "bind".equals(r.get("type"))
                && Boolean.FALSE.equals(r.get("ok"))).findFirst().orElseThrow();
        assertEquals("ENVIRONMENT", failed.get("failureKind"));
        assertFalse(Files.readString(dir.resolve("out").resolve(RunLog.FILE)).contains("side-secret"));
        assertFalse(Files.exists(dir.resolve("bindings")), "nothing written");

        PlanExecutor.Resolved plain = new PlanExecutor(pack, agent, options).resolve(PlanCompiler.compile(
                yaml.formatted("IDLE").getBytes(StandardCharsets.UTF_8), "t.yaml", pack));
        assertEquals(Status.PASS, plain.result().status(), plain.result().message());
        assertEquals("IDLE", plain.outcome().file().oracles().get("same").value(), "the file a dry run shows");

        RunResult run = new PlanExecutor(pack, agent, options).run(leaky);
        assertEquals(Status.PASS, run.status(), "a live run prints no file: " + run.message());
        assertFalse(Files.readString(dir.resolve("out").resolve(RunLog.FILE)).contains("side-secret"));

        options = options.withBind(options.bind().withMode(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL));
        RunResult approve = new PlanExecutor(pack, agent, options).resolve(leaky).result();
        assertEquals(FailureKind.ENVIRONMENT, approve.failureKind());
        assertEquals(refused.result().message(), approve.message(), "the same refusal as approve's");
    }

    @Test
    void afterAFailedBindNoCleanupCallAndNoRecoverGoOut() throws Exception {
        RunResult result = run(BinderTest.YAML.replace("steps:", "steps:\n  - lock: { name: a }"));
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.REBIND_NEEDED, result.failureKind());
        assertEquals("NO_BINDINGS", result.detail().get("reason"));
        assertEquals("replay", result.detail().get("mode"));
        assertEquals("seed-a", result.detail().get("envId"));
        assertTrue(agent.callsTo("trace.mark").isEmpty(), "no start mark");
        assertTrue(agent.callsTo("toy.unlock").isEmpty() && agent.callsTo("toy.input").isEmpty(), "no cleanup");
        assertEquals(0, pack.recovered.get(), "no recover");
        List<String> types = types();
        assertFalse(types.contains("cleanup") || types.contains("recover"), types.toString());
        assertTrue(types.contains("fingerprint"));
        assertEquals(List.of(), result.steps(), "no step ran");
    }

    static final String WITH_CLEANUPS = """
            scenario: t
            pack: toy
            resolve:
              assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
            steps:
              - lock: { name: a }
              - signOn: { staff: $assistant }
            """;

    @Test
    void afterAFailureBeforeTheStartMarkOrOfTheStartMarkNoCleanupCallAndNoRecoverGoOut() throws Exception {
        Map<String, java.util.function.Consumer<ExecutorPhase2Test>> failures = new java.util.LinkedHashMap<>();
        failures.put("launch property", t -> t.agent.property("toy.seed", "B"));
        failures.put("pre-flight", t -> t.pack.preflight = List.of(new PreflightCheck() {
            @Override
            public String id() {
                return "toy.down";
            }

            @Override
            public String check(ai.skillroy.testjava.controller.AgentConnection a) {
                return "the toy database is down";
            }
        }));
        failures.put("unserved source", t -> t.agent.value("data.sources", Map.of("sources", List.of())));
        failures.put("failed start mark", t -> t.agent.error("trace.mark",
                ai.skillroy.testjava.protocol.ErrorCodes.INTERNAL_ERROR, "the event bus is down"));
        Map<String, FailureKind> kinds = Map.of("launch property", FailureKind.PRECONDITION_FAILED, "pre-flight",
                FailureKind.ENVIRONMENT, "unserved source", FailureKind.PRECONDITION_FAILED, "failed start mark",
                FailureKind.ENVIRONMENT);
        for (Map.Entry<String, java.util.function.Consumer<ExecutorPhase2Test>> failure : failures.entrySet()) {
            ExecutorPhase2Test t = new ExecutorPhase2Test();
            t.dir = dir.resolve(failure.getKey().replace(' ', '-'));
            t.setUp();
            failure.getValue().accept(t);
            String yaml = WITH_CLEANUPS;
            if (failure.getKey().equals("launch property")) {
                yaml = yaml.replace("steps:", "launchProperties: { toy.seed: A }\nsteps:");
            }
            if (failure.getKey().equals("unserved source")) {
                yaml = yaml + "verify:\n  - db.loan_line: { where: { item_code: B2 }, rows: 1 }\n";
            }
            RunResult result = t.run(yaml);
            String what = failure.getKey();
            assertEquals(Status.ERROR, result.status(), what);
            assertEquals(kinds.get(what), result.failureKind(), what + ": " + result.message());
            assertTrue(t.agent.callsTo("toy.unlock").isEmpty(), what + ": no cleanup call");
            assertTrue(t.agent.callsTo("toy.input").isEmpty(), what + ": no step input, no sign-off");
            assertEquals(0, t.pack.recovered.get(), what + ": no recover");
            List<String> types = t.types();
            assertFalse(types.contains("cleanup") || types.contains("recover"), what + ": " + types);
            assertEquals(List.of(), result.steps(), what + ": no step ran");
        }
    }

    @Test
    void aCleanupCallWhoseArgumentReferencesAJoinedSecretFieldAnnouncesIt() throws Exception {
        RunResult result = run(WITH_CLEANUPS.replace("lock: { name: a }", "lock: { name: $assistant.password }"));
        assertEquals(Status.PASS, result.status(), result.message());
        FakeAgentConnection.Call unlock = agent.callsTo("toy.unlock").get(0);
        assertEquals(TestCredentials.PW_100, unlock.params().get("which"), "the cleanup call gets the secret");
        assertEquals(List.of("which"), unlock.options().secretParams(), "A34 on a cleanup call");
        assertEquals(List.of("which"), agent.callsTo("toy.lock").get(0).options().secretParams());
        FakeAgentConnection.Call signOff = agent.callsTo("toy.input").get(agent.callsTo("toy.input").size() - 1);
        assertEquals("Key.SIGN_OFF", signOff.params().get("source"));
        assertEquals(List.of(), signOff.options().secretParams(), "a cleanup call without a secret names none");
    }

    @Test
    void approveWithoutABindingsRootSendsNothingAndCallsNoPackCode() throws Exception {
        AtomicInteger preflights = new AtomicInteger();
        pack.preflight = List.of(new PreflightCheck() {
            @Override
            public String id() {
                return "toy.counted";
            }

            @Override
            public String check(ai.skillroy.testjava.controller.AgentConnection a) {
                preflights.incrementAndGet();
                return null;
            }
        });
        options = options.withBind(options.bind().withMode(BindMode.APPROVE).withApprover(Approver.APPROVE_ALL)
                .withBindingsRoot(null));
        RunResult result = run(BinderTest.YAML.replace("steps:", "steps:\n  - lock: { name: a }"));
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertTrue(result.message().startsWith("approve needs a bindings root"), result.message());
        assertEquals("approve", result.detail().get("mode"));
        assertTrue(agent.calls.isEmpty(), "nothing was sent: " + agent.methods());
        assertEquals(0, preflights.get(), "no pre-flight ran");
        assertEquals(0, toy.itemResolverCalls.get());
        assertEquals(0, pack.recovered.get());
        assertEquals(List.of("run-start", "bind", "ledger", "run-end"), types());

        Plan plan = PlanCompiler.compile(BinderTest.YAML.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        PlanExecutor.Resolved resolved = new PlanExecutor(pack, agent, options).resolve(plan);
        assertEquals(FailureKind.ENVIRONMENT, resolved.result().failureKind(), "resolve alike");
        assertNull(resolved.outcome());
        assertTrue(agent.calls.isEmpty());
    }

    @Test
    void approveThenReplayAndTheLedgerCheck() throws Exception {
        RunOptions approve = options.withBind(options.bind().withMode(BindMode.APPROVE)
                .withApprover(Approver.APPROVE_ALL));
        toy.items.clear();
        toy.items.add(List.of("B2", "5.00"));
        Plan plan = PlanCompiler.compile(BinderTest.YAML.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        agent.value("toy.loan", Map.of("total", "-5.00"));
        RunResult approved = new PlanExecutor(pack, agent, approve).run(plan);
        assertEquals(Status.PASS, approved.status(), approved.message());
        assertTrue(Files.exists(dir.resolve("bindings/seed-a/toy-resolve.bindings.json")));

        RunResult replay = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.PASS, replay.status(), replay.message());
        List<String> types = types();
        assertFalse(types.contains("resolver"), "no resolver in replay");
        assertEquals(3, types.stream().filter("verifier"::equals).count());
        Map<String, Object> bindings = Json.parseObject(Files.readString(dir.resolve("out/bindings.json")));
        Map<?, ?> item = (Map<?, ?>) ((Map<?, ?>) bindings.get("resolutions")).get("item");
        assertEquals("REPLAY", item.get("strategy"));
        assertEquals("CANONICAL_SQL", item.get("recordedStrategy"));
        assertFalse(Files.readString(dir.resolve("out/bindings.json")).contains(TestCredentials.PW_100));
        FakeAgentConnection.Call password = agent.callsTo("toy.input").get(agent.callsTo("toy.input").size() - 3);
        assertEquals(TestCredentials.PW_100, password.params().get("value"));
        assertEquals(List.of("value"), password.options().secretParams(), "A34");
        assertFalse(password.params().containsKey("_meta"), "announced through the call options, not the params");

        CostLedger ledger = options.bind().ledger();
        toy.beforeStaffCheck = ctx -> ledger.record(new CostLedger.Entry("toy-resolve", "assistant",
                Strategy.LLM_REASON, "test", "model", "r1", 10, 20, "0.01"));
        RunResult charged = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.ERROR, charged.status());
        assertEquals(FailureKind.ENVIRONMENT, charged.failureKind());
        assertEquals("replay made 1 model call", charged.message());
        assertEquals(1, charged.modelCalls().size());
        String ledgerFile = Files.readString(dir.resolve("out/cost-ledger.jsonl"));
        assertEquals(1, ledgerFile.lines().count());
        assertTrue(ledgerFile.contains("\"inputTokens\":10"), "the key rule is not applied to the ledger");

        RunResult live = new PlanExecutor(pack, agent, options.withBind(options.bind().withMode(BindMode.LIVE)))
                .run(plan);
        assertEquals(Status.PASS, live.status(), "live is left alone: " + live.message());
        assertEquals(1, live.modelCalls().size());
    }

    @Test
    void preflightReadsThroughTheDataChannel() throws Exception {
        List<String> seen = new ArrayList<>();
        pack.preflight = List.of(new PreflightCheck() {
            @Override
            public String id() {
                return "toy.database";
            }

            @Override
            public String check(ai.skillroy.testjava.controller.AgentConnection a) {
                return "the old entry must not be called";
            }

            @Override
            public String check(PreflightContext ctx) throws Exception {
                seen.add(ctx.envId());
                return ctx.data().query(ToyResolution.SOURCE, ToyResolution.LIMIT_SQL, List.of("max_return"), 1,
                        null).rowCount() == 1 ? null : "no max";
            }
        });
        RunResult result = run("scenario: t\npack: toy\nsteps: []\n");
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of("seed-a"), seen);
        assertNull(result.failureKind());
    }
}
