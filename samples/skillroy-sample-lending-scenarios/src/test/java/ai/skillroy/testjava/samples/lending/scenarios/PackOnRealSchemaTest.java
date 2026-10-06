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

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.JdbcDataChannel;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.JdbcSource;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.Check;
import ai.skillroy.testjava.scenario.CrossCheckOutcome;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.RowCheck;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 acceptance 14.1 item 8 and Phase 3 acceptance 11.1 item 6: the pack's SQL on the real schema over the
 * controller-side JDBC channel, without a display. Each test starts the sample's own database ({@link LendingDb}, seed
 * A or B, seeded on the day the test runs, as the desk seeds itself) inside this JVM with its read-only account, points
 * {@code credentials.yaml}'s JDBC source {@code lending} at it, and binds the committed plans of scenarios A and B
 * through the real {@link Binder} with a {@link DeskConnection} that answers the verifiers and the cross-check from
 * the application's real access rules, catalogue and waiver engine, and reports that day as the desk's business date.
 * Since the committed bindings files are compared byte for byte, every run of the suite proves again that they do not
 * depend on the day.
 *
 * <p>Scenario A on each seed: {@code live} resolves the table of P2 13.4; {@code approve} writes a file
 * byte-identical to the committed one except for {@code "channel": "jdbc"} in the statements; {@code replay} of the
 * committed file passes the bind; the generated {@code wrong-assistant} file is REBIND_NEEDED; the two negative
 * scenarios fail as P2 13.4 says; the plan's {@code db.*} statements run against the same database and their columns
 * match by position.
 *
 * <p>Scenario B on each seed (P3 10.4): {@code live} resolves the table of 10.4 and the cross-check agrees with the
 * desk's dry run; {@code approve} writes the committed bytes except for the channel; {@code replay} of the committed
 * file passes and the cross-check agrees; the wrong-oracle overlay is ORACLE_SUSPECT in {@code live} and
 * {@code approve} and ORACLE_CHANGED in {@code replay}; a file approved with the wrong oracle is ORACLE_SUSPECT in
 * {@code replay}; the two negatives fail as 10.4 says; a guarded check whose capability is not OK is skipped; the
 * {@code db.loan_waiver} statement and its aggregate run on rows the test inserts.
 *
 * <p>{@code -Dskillroy.bindings.update=true} (re)writes the committed bindings files of both scenarios from what
 * {@code approve} produces here, with the channel the end-to-end tests see ({@code agent}); the end-to-end check 2 then
 * proves that the real agent produces the same bytes.
 */
class PackOnRealSchemaTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    /**
     * The read-only account's password here: test-local (the database has no listener), and at least 8 characters,
     * so that the masking of its occurrences in longer texts applies (phase2-contracts.md 4).
     */
    private static final String READER_PASSWORD = "Pack-Schema-Reader-3851";

    @TempDir
    Path tmp;

    private LendingDb db;

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    /**
     * The seeded database with its read-only account, the credentials naming it, the desk connection, and the desk's
     * business date (the day the database was seeded on).
     */
    private record Setup(Credentials credentials, DeskConnection desk, LocalDate today) {
    }

    /** Seed {@code env} today, the business date the desk connection reports, as the desk seeds itself. */
    private Setup setUp(Env env) throws Exception {
        LocalDate today = LocalDate.now();
        db = LendingDb.create("p2bind" + env.seed + DATABASES.incrementAndGet(), Seed.parse(env.seed), today);
        db.createReadOnlyUser(READER_PASSWORD);
        Credentials credentials = SampleCredentials.load(tmp.resolve("credentials-" + env.id), env,
                new JdbcSource(db.url(), LendingDb.READER_USER, READER_PASSWORD));
        assertEquals(java.util.Set.of("lending"), credentials.jdbcSources());
        return new Setup(credentials, new DeskConnection(db, today), today);
    }

    private static BindOptions options(Setup setup, Env env, BindMode mode, Path root) {
        return BindOptions.defaults().withMode(mode).withEnvId(env.id).withBindingsRoot(root)
                .withCredentials(setup.credentials());
    }

    /** run.jsonl-like records of a bind. */
    private static final class Events implements BindListener {
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> records = new ArrayList<>();

        @Override
        public void event(String type, Map<String, Object> fields) {
            types.add(type);
            records.add(fields);
        }

        long count(String type) {
            return types.stream().filter(type::equals).count();
        }
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void liveResolvesTheTableOf13_4OverJdbcAndWritesNothing(Env env) throws Exception {
        Setup setup = setUp(env);
        Path root = tmp.resolve("live-root");
        Events events = new Events();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioA.plan()), Runs.PACK, setup.desk(),
                options(setup, env, BindMode.LIVE, root), events);
        ScenarioA.Expected expected = ScenarioA.expected(env);
        Map<String, Object> values = outcome.bindings().values();
        assertEquals(expected.systemMax(), values.get("systemMax"));
        assertEquals(expected.item(), values.get("item"));
        assertEquals(ScenarioA.KEYS, values.get("keys"));
        assertEquals(expected.assistantCode(), ((Map<?, ?>) values.get("assistant")).get("code"));
        assertEquals(expected.supervisorCode(), ((Map<?, ?>) values.get("supervisor")).get("code"));
        assertEquals("SUPERVISOR_PROMPT", outcome.bindings().oracle("auth"));
        assertEquals(expected.returnLine(), outcome.bindings().oracle("returnLine"));
        for (String slot : ScenarioA.QUERY_SLOTS) {
            Resolution r = outcome.bindings().resolutions().get(slot);
            assertNotNull(r, slot);
            assertFalse(r.verified().isEmpty(), slot);
            assertTrue(r.verified().stream().allMatch(VerifierResult::passed), slot + ": " + r.verified());
            for (Provenance.Statement statement : r.provenance().statements()) {
                assertEquals("jdbc", statement.channel(), slot);
                assertEquals("lending", statement.source(), slot);
            }
        }
        Resolution assistant = outcome.bindings().resolutions().get("assistant");
        assertEquals(expected.assistant(), assistant.value(), "the value holds no secret field");
        assertEquals(expected.assistantCode(), assistant.credential().id());
        assertEquals(expected.supervisor(), outcome.bindings().resolutions().get("supervisor").value());
        assertEquals("lending.java.ActionKey", outcome.bindings().resolutions().get("keys").provenance()
                .resolverId());
        assertEquals(List.of(), outcome.bindings().resolutions().get("keys").provenance().statements());
        assertEquals(5, events.count("resolver"), "one resolver call per query slot");
        assertNull(outcome.written());
        assertFalse(Files.exists(root), "live writes nothing");
        assertFalse(setup.desk().calls.contains("lending.input"), "a bind never sends an input");
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void approveWritesTheCommittedFileExceptForTheChannel(Env env) throws Exception {
        Setup setup = setUp(env);
        Path root = tmp.resolve("approve-root");
        List<Approver.ApprovalRequest> shown = new ArrayList<>();
        Approver scripted = request -> {
            shown.add(request);
            return new Approver.Decision(true, null, "checked by the test");
        };
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioA.plan()), Runs.PACK, setup.desk(),
                options(setup, env, BindMode.APPROVE, root).withApprover(scripted), BindListener.NONE);
        assertEquals(1, shown.size(), "asked once");
        Approver.ApprovalRequest request = shown.get(0);
        assertEquals(ScenarioA.ID, request.scenarioId());
        assertEquals(env.id, request.envId());
        assertEquals(ScenarioA.QUERY_SLOTS, request.resolutions().stream().map(Resolution::slot).toList());
        for (Resolution r : request.resolutions()) {
            assertNotNull(r.value(), r.slot());
            assertFalse(r.verified().isEmpty(), r.slot());
            assertTrue(r.verified().stream().allMatch(v -> !v.evidence().isEmpty()), "evidence shown: " + r.slot());
            if (!r.slot().equals("keys")) {
                assertFalse(r.provenance().statements().isEmpty(), "statements shown: " + r.slot());
            }
        }
        assertEquals(List.of("auth", "returnLine"), request.oracles().stream().map(o -> o.name()).toList());
        Path written = BindingsFile.locate(root, env.id, ScenarioA.ID);
        assertEquals(written, outcome.written());
        String text = Files.readString(written, StandardCharsets.UTF_8);
        assertFalse(text.contains("\"channel\": \"agent\""));
        String asTheAgentWouldWriteIt = text.replace("\"channel\": \"jdbc\"", "\"channel\": \"agent\"");
        Path committed = ScenarioA.committedBindings(env);
        if (Boolean.getBoolean("skillroy.bindings.update")) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, asTheAgentWouldWriteIt, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(committed, StandardCharsets.UTF_8), asTheAgentWouldWriteIt,
                "byte-identical to the committed file except for the channel (regenerate with "
                        + "-Dskillroy.bindings.update=true)");
        for (String password : SampleCredentials.staff(env).values()) {
            assertFalse(text.contains("\"" + password + "\""), "no password as a value in the file");
        }
        assertFalse(text.contains(READER_PASSWORD), "no JDBC password in the file");
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void replayOfTheCommittedFilePassesTheBindWithoutAResolver(Env env) throws Exception {
        Setup setup = setUp(env);
        Events events = new Events();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioA.plan()), Runs.PACK, setup.desk(),
                options(setup, env, BindMode.REPLAY, ScenarioA.bindingsRoot()), events);
        assertEquals(0, events.count("resolver"), "a replay never calls a resolver");
        assertEquals(ScenarioA.QUERY_SLOTS.size(), events.count("verifier"), "every slot's verifier ran once");
        assertNull(outcome.written(), "replay writes nothing");
        ScenarioA.Expected expected = ScenarioA.expected(env);
        assertEquals(expected.item(), outcome.bindings().values().get("item"));
        assertEquals("REPLAY", outcome.bindings().strategy("assistant"));
        assertEquals(expected.returnLine(), outcome.bindings().oracle("returnLine"));
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void theWrongAssistantIsRebindNeededByTheVerifier(Env env) throws Exception {
        Setup setup = setUp(env);
        Path root = tmp.resolve("wrong-assistant");
        ScenarioA.writeWrongAssistant(env, root);
        Events events = new Events();
        BindException e = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioA.plan()),
                Runs.PACK, setup.desk(), options(setup, env, BindMode.REPLAY, root), events));
        assertEquals(FailureKind.REBIND_NEEDED, e.kind(), e.getMessage());
        assertEquals("VERIFIER", e.reason(), e.getMessage());
        assertEquals("assistant", e.slot());
        Map<?, ?> verifier = (Map<?, ?>) e.detail().get("verifier");
        assertEquals("lending.access.check", verifier.get("id"), String.valueOf(e.detail()));
        assertTrue(String.valueOf(verifier.get("reason")).contains("does not satisfy below"),
                "the desk's limit is not below the deposit: " + verifier);
        assertEquals(0, events.count("resolver"));
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void theNegativeScenariosFailInLiveAsTheContractSays(Env env) throws Exception {
        Setup setup = setUp(env);
        Path root = tmp.resolve("negative-root");
        BindException none = assertThrows(BindException.class, () -> Binder.bind(Runs.negative(
                "no-item-in-range.yaml"), Runs.PACK, setup.desk(), options(setup, env, BindMode.LIVE, root), null));
        assertEquals(FailureKind.RESOLUTION_FAILED, none.kind(), none.getMessage());
        assertEquals("NO_CANDIDATE", none.reason());
        assertEquals("item", none.slot());

        Events events = new Events();
        BindException guard = assertThrows(BindException.class, () -> Binder.bind(Runs.negative(
                "return-within-limit.yaml"), Runs.PACK, setup.desk(), options(setup, env, BindMode.LIVE, root),
                events));
        assertEquals(FailureKind.RESOLUTION_FAILED, guard.kind(), guard.getMessage());
        assertEquals("GUARD", guard.reason());
        Map<?, ?> detail = guard.detail();
        assertEquals("auth", detail.get("oracle"), String.valueOf(detail));
        Map<?, ?> check = (Map<?, ?>) detail.get("guard");
        assertEquals("SUPERVISOR_PROMPT", check.get("mustEqual"), String.valueOf(detail));
        assertEquals("NONE", check.get("actual"), String.valueOf(detail));
        ScenarioA.Expected expected = ScenarioA.expected(env);
        List<String> resolved = new ArrayList<>();
        for (int i = 0; i < events.types.size(); i++) {
            if ("resolve".equals(events.types.get(i))) {
                Map<String, Object> record = events.records.get(i);
                Object credential = record.get("credential");
                resolved.add(record.get("slot") + "=" + (credential instanceof Map<?, ?> c ? c.get("id") : "-"));
            }
        }
        assertTrue(resolved.contains("assistant=" + expected.withinLimitAssistant()), String.valueOf(resolved));
        assertTrue(resolved.contains("supervisor=" + expected.withinLimitSupervisor()), String.valueOf(resolved));
        assertFalse(Files.exists(root), "nothing written");
        assertFalse(setup.desk().calls.contains("lending.input"));
    }

    /**
     * Without staff credentials, {@code live} fails as ALL_REJECTED whose rejections say "no credential" (section 4),
     * and {@code replay} as ENVIRONMENT naming the entry it looked for (8.3 step 6d).
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void withoutCredentialsLiveIsAllRejectedAndReplayIsEnvironment(Env env) throws Exception {
        Setup setup = setUp(env);
        Credentials jdbcOnly = Credentials.of(Map.of("version", 1L, "jdbc", Map.of("lending", Map.of("url",
                db.url(), "user", LendingDb.READER_USER, "password", READER_PASSWORD))), "jdbc only");
        Setup noStaff = new Setup(jdbcOnly, setup.desk(), setup.today());
        BindException live = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioA.plan()),
                Runs.PACK, noStaff.desk(), options(noStaff, env, BindMode.LIVE, tmp.resolve("root")), null));
        assertEquals(FailureKind.RESOLUTION_FAILED, live.kind(), live.getMessage());
        assertEquals("ALL_REJECTED", live.reason(), live.getMessage());
        assertEquals("assistant", live.slot());
        assertTrue(String.valueOf(live.detail().get("rejected")).contains("no credential staff/"
                + ScenarioA.expected(env).assistantCode()), String.valueOf(live.detail()));
        BindException replay = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioA.plan()),
                Runs.PACK, noStaff.desk(), options(noStaff, env, BindMode.REPLAY, ScenarioA.bindingsRoot()), null));
        assertEquals(FailureKind.ENVIRONMENT, replay.kind(), replay.getMessage());
        assertTrue(replay.getMessage().contains("staff/" + ScenarioA.expected(env).assistantCode()),
                replay.getMessage());
    }

    /**
     * The {@code db.*} statements the committed plan holds, run through the JDBC channel against a loan inserted the
     * way the desk records one: one row each, columns matched by position to the pack's stored tables (the driver
     * reports upper-case labels, which are not looked at).
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void thePlansStoredRowStatementsRunOnTheSameDatabase(Env env) throws Exception {
        Setup setup = setUp(env);
        ScenarioA.Expected expected = ScenarioA.expected(env);
        long loanId = 990_001L;
        Connection c = db.connection();
        try (PreparedStatement loan = c.prepareStatement("INSERT INTO loan (id, staff_code, posted_at, total)"
                     + " VALUES (?, ?, CURRENT_TIMESTAMP, CAST(? AS DECIMAL(10,2)))");
             PreparedStatement line = c.prepareStatement("INSERT INTO loan_line (loan_id, line_no, item_code, qty,"
                     + " unit_deposit, amount, returned) VALUES (?, 1, ?, 1, CAST(? AS DECIMAL(10,2)),"
                     + " CAST(? AS DECIMAL(10,2)), TRUE)");
             PreparedStatement auth = c.prepareStatement("INSERT INTO loan_authorisation (loan_id, kind,"
                     + " staff_code) VALUES (?, 'RETURN_OVERRIDE', ?)")) {
            loan.setLong(1, loanId);
            loan.setString(2, expected.assistantCode());
            loan.setString(3, expected.returnLine());
            loan.executeUpdate();
            line.setLong(1, loanId);
            line.setString(2, expected.itemCode());
            line.setString(3, (String) expected.item().get("deposit"));
            line.setString(4, expected.returnLine());
            line.executeUpdate();
            auth.setLong(1, loanId);
            auth.setString(2, expected.supervisorCode());
            auth.executeUpdate();
            if (!c.getAutoCommit()) {
                c.commit();
            }
        }
        Plan plan = Plan.read(ScenarioA.plan());
        List<RowCheck> checks = new ArrayList<>();
        for (Check check : plan.verify()) {
            if (check instanceof RowCheck row) {
                checks.add(row);
            }
        }
        assertEquals(List.of("loan", "loan_line", "loan_authorisation"), checks.stream().map(RowCheck::table)
                .toList());
        Map<String, StoredTable> tables = new java.util.HashMap<>();
        Runs.PACK.storedTables().forEach(t -> tables.put(t.name(), t));
        Map<String, Object> values = Map.of("$loan.id", loanId, "$item.code", expected.itemCode());
        try (JdbcDataChannel jdbc = new JdbcDataChannel(setup.credentials(), Runs.PACK.rowMasks())) {
            for (RowCheck check : checks) {
                List<Object> params = new ArrayList<>();
                for (RowCheck.Param param : check.params()) {
                    params.add(values.get(String.valueOf(param.value())));
                }
                Rows rows = jdbc.query(check.source(), check.sql(), params, DataChannel.MAX_ROWS, null);
                StoredTable table = tables.get(check.table());
                assertEquals(table.columns().size(), rows.columns().size(), check.table());
                assertEquals(1, rows.rowCount(), check.table() + ": " + rows);
                for (int i = 0; i < table.columns().size(); i++) {
                    assertEquals(table.columns().get(i).name().toUpperCase(Locale.ROOT), rows.columns().get(i).name(),
                            "position " + i + " of " + check.table());
                }
                List<Object> row = rows.rows().get(0);
                switch (check.table()) {
                    case "loan" -> {
                        assertEquals(loanId, ((Number) row.get(0)).longValue());
                        assertEquals(expected.assistantCode(), row.get(1));
                        assertEquals(expected.returnLine(), row.get(3));
                    }
                    case "loan_line" -> {
                        assertEquals(expected.itemCode(), row.get(2));
                        assertEquals(expected.returnLine(), row.get(5));
                        assertEquals(Boolean.TRUE, row.get(6));
                    }
                    default -> {
                        assertEquals("RETURN_OVERRIDE", row.get(1));
                        assertEquals(expected.supervisorCode(), row.get(2));
                    }
                }
            }
        }
    }

    /** The plan compiled now is the committed one, and names no channel: either serves it. */
    @ParameterizedTest
    @EnumSource(Env.class)
    void thePlanIsTheSameForBothSeeds(Env env) throws Exception {
        Plan compiled = PlanCompiler.compile(ScenarioA.yaml(), Runs.PACK);
        assertEquals(Files.readString(ScenarioA.plan(), StandardCharsets.UTF_8), compiled.toJsonText());
        assertEquals(ScenarioA.GRAPH, compiled.resolutionGraph());
        assertEquals(Map.of(), compiled.launchProperties(), "no launch property: the environment decides");
        assertTrue(Stream.of(ScenarioA.committedBindings(env)).allMatch(Files::isRegularFile), env.id);
    }

    // ------------------------------------------------------------------------------------------- scenario B

    /** Phase 3 11.1 item 6: live resolves the table of 10.4 over JDBC, and the cross-check agrees with the desk. */
    @ParameterizedTest
    @EnumSource(Env.class)
    void scenarioBLiveResolvesTheTableOf10_4AndTheCrossCheckAgrees(Env env) throws Exception {
        Setup setup = setUp(env);
        Path root = tmp.resolve("b-live-root");
        Events events = new Events();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioB.plan()), Runs.PACK, setup.desk(),
                options(setup, env, BindMode.LIVE, root), events);
        ScenarioB.Expected expected = ScenarioB.expected(env);
        Map<String, Object> values = outcome.bindings().values();
        assertEquals(expected.rule(), values.get("rule"));
        assertEquals(expected.item(), values.get("item"));
        assertEquals(ScenarioB.KEYS, values.get("keys"));
        assertEquals(expected.assistantCode(), ((Map<?, ?>) values.get("assistant")).get("code"));
        assertEquals(expected.assistant(), outcome.bindings().resolutions().get("assistant").value(),
                "the value holds no secret field");
        assertEquals(expected.needed(), outcome.bindings().oracle("needed"));
        assertEquals(expected.waiver(), outcome.bindings().oracle("waiver"));
        assertEquals(expected.due(), outcome.bindings().oracle("due"));
        for (String slot : ScenarioB.QUERY_SLOTS) {
            Resolution r = outcome.bindings().resolutions().get(slot);
            assertTrue(r.verified().stream().allMatch(VerifierResult::passed), slot + ": " + r.verified());
            assertTrue(r.provenance().statements().stream().allMatch(st -> st.channel().equals("jdbc")), slot);
        }
        String today = setup.today().toString();
        Resolution rule = outcome.bindings().resolutions().get("rule");
        assertEquals("lending.sql.ActiveWaiverRuleWithTriggers", rule.provenance().resolverId());
        assertEquals(List.of(new Provenance.Call("app.state", Map.of())), rule.provenance().calls(),
                "the business date was read from the desk, and the read (not its answer) is in the provenance");
        assertEquals(1, rule.provenance().statements().size());
        assertEquals(List.of("BORROW_N_WAIVE_M"), rule.provenance().statements().get(0).params(),
                "the kind only: the window is applied in Java, so no date is recorded");
        assertEquals("the desk waives " + expected.amount() + " for 3 units of " + expected.itemCode() + " under "
                + expected.ruleCode(), rule.verified().get(0).reason(), "a reason the bindings file records: no date");
        assertEquals(today, rule.verified().get(0).evidence().get("date"), "the date is evidence");
        assertEquals("lending.java.ItemsTriggering", outcome.bindings().resolutions().get("item").provenance()
                .resolverId());
        assertEquals(List.of(), outcome.bindings().resolutions().get("item").provenance().statements());
        CrossCheckOutcome check = outcome.bindings().crossChecks().get("waiver");
        assertTrue(check.agreed(), check.summary());
        Map<?, ?> actual = (Map<?, ?>) check.result().actual();
        assertEquals(expected.amount(), actual.get("amount"));
        assertEquals(today, actual.get("date"), "the desk's business date");
        assertEquals("cross-check lending.waiver.evaluate: agrees (the desk waives " + expected.amount() + " for 3"
                + " units of " + expected.itemCode() + " under " + expected.ruleCode() + " on " + today + "; the oracle"
                + " expects " + expected.amount() + ")", check.summary());
        assertEquals(java.util.Set.of("waiver"), outcome.bindings().crossChecks().keySet(), "only waiver has one");
        assertEquals(1, events.count("crosscheck"), "one record, for the one oracle with a cross-check");
        Map<String, Object> record = events.records.get(events.types.indexOf("crosscheck"));
        assertEquals(Boolean.TRUE, record.get("agreed"), String.valueOf(record));
        assertEquals("0.00", record.get("tolerance"));
        assertEquals(ScenarioB.QUERY_SLOTS.size(), events.count("resolver"), "one resolver call per query slot");
        assertEquals(List.of(), outcome.skipped());
        assertNull(outcome.written());
        assertFalse(Files.exists(root), "live writes nothing");
        assertFalse(setup.desk().calls.contains("lending.input"), "a bind never sends an input");
        assertFalse(setup.desk().calls.contains("capabilities"), "an unguarded check never consults a capability");
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void scenarioBApproveWritesTheCommittedFileExceptForTheChannel(Env env) throws Exception {
        Setup setup = setUp(env);
        Path root = tmp.resolve("b-approve-root");
        List<Approver.ApprovalRequest> shown = new ArrayList<>();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioB.plan()), Runs.PACK, setup.desk(),
                options(setup, env, BindMode.APPROVE, root).withApprover(request -> {
                    shown.add(request);
                    return new Approver.Decision(true, null, "checked by the test");
                }), BindListener.NONE);
        assertEquals(1, shown.size(), "asked once");
        Approver.ApprovalRequest request = shown.get(0);
        assertEquals(ScenarioB.QUERY_SLOTS, request.resolutions().stream().map(Resolution::slot).toList());
        assertEquals(List.of("needed", "waiver", "due"), request.oracles().stream().map(o -> o.name()).toList());
        assertEquals(1, request.crossChecks().size(), "the approval screen shows the cross-check");
        assertTrue(request.crossChecks().get(0).agreed());
        Path written = BindingsFile.locate(root, env.id, ScenarioB.ID);
        assertEquals(written, outcome.written());
        String text = Files.readString(written, StandardCharsets.UTF_8);
        assertFalse(text.contains("\"channel\": \"agent\""));
        String asTheAgentWouldWriteIt = text.replace("\"channel\": \"jdbc\"", "\"channel\": \"agent\"");
        Path committed = ScenarioB.committedBindings(env);
        if (Boolean.getBoolean("skillroy.bindings.update")) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, asTheAgentWouldWriteIt, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(committed, StandardCharsets.UTF_8), asTheAgentWouldWriteIt,
                "byte-identical to the committed file except for the channel (regenerate with "
                        + "-Dskillroy.bindings.update=true)");
        BindingsFile file = BindingsFile.read(written);
        assertEquals(new Plan.CrossCheckSpec("lending.waiver.evaluate", "0.00", null), file.oracles().get("waiver")
                .crossCheck(), "the oracle entry records what it was cross-checked against");
        assertNull(file.oracles().get("due").crossCheck());
        assertFalse(text.contains("\"" + expected(env).assistantPassword() + "\""), "no password as a value");
        assertFalse(text.contains(READER_PASSWORD), "no JDBC password in the file");
    }

    private static ScenarioB.Expected expected(Env env) {
        return ScenarioB.expected(env);
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void scenarioBReplayOfTheCommittedFilePassesAndTheCrossCheckAgrees(Env env) throws Exception {
        Setup setup = setUp(env);
        Events events = new Events();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioB.plan()), Runs.PACK, setup.desk(),
                options(setup, env, BindMode.REPLAY, ScenarioA.bindingsRoot()), events);
        assertEquals(0, events.count("resolver"), "a replay never calls a resolver");
        assertEquals(ScenarioB.QUERY_SLOTS.size(), events.count("verifier"), "every slot's verifier ran once");
        assertEquals(1, events.count("crosscheck"), "the cross-check ran again");
        assertEquals(Boolean.TRUE, events.records.get(events.types.indexOf("crosscheck")).get("agreed"));
        assertNull(outcome.written(), "replay writes nothing");
        ScenarioB.Expected expected = expected(env);
        assertEquals(expected.item(), outcome.bindings().values().get("item"));
        assertEquals("REPLAY", outcome.bindings().strategy("rule"));
        assertEquals(expected.waiver(), outcome.bindings().oracle("waiver"));
        assertEquals(expected.due(), outcome.bindings().oracle("due"));
        assertTrue(outcome.bindings().crossChecks().get("waiver").agreed());
        assertEquals(List.of(), outcome.skipped());
    }

    /**
     * The wrong-oracle overlay (10.4, decision D46): ORACLE_SUSPECT, reason DISAGREED, in {@code live} and in
     * {@code approve}, before the guard and before anything is written or sent; in {@code replay} against the
     * committed file, ORACLE_CHANGED comes first (6.1), since the wrong value differs from the recorded one.
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void theWrongOracleIsOracleSuspectInLiveAndApproveAndOracleChangedInReplay(Env env) throws Exception {
        Setup setup = setUp(env);
        ScenarioB.Expected expected = expected(env);
        OverlayPack wrong = OverlayPack.wrongOracle();
        Path root = tmp.resolve("b-wrong-root");
        Events events = new Events();
        BindException live = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioB.plan()), wrong,
                setup.desk(), options(setup, env, BindMode.LIVE, root), events));
        assertOracleSuspect(live, expected, "live", env, setup.today());
        assertEquals(1, events.count("crosscheck"));
        assertEquals(Boolean.FALSE, events.records.get(events.types.indexOf("crosscheck")).get("agreed"));
        assertEquals(0, events.count("guard"), "the guard was not reached");
        assertFalse(events.records.stream().anyMatch(r -> "due".equals(r.get("name"))), "nor the next oracle");

        List<Approver.ApprovalRequest> asked = new ArrayList<>();
        BindException approve = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioB.plan()),
                wrong, setup.desk(), options(setup, env, BindMode.APPROVE, root).withApprover(request -> {
                    asked.add(request);
                    return new Approver.Decision(true, null, "must not be asked");
                }), BindListener.NONE));
        assertOracleSuspect(approve, expected, "approve", env, setup.today());
        assertEquals(List.of(), asked, "the approver is never asked");
        assertFalse(Files.exists(root), "nothing written");
        assertFalse(setup.desk().calls.contains("lending.input"), "the desk is untouched");

        Events replayEvents = new Events();
        BindException replay = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioB.plan()),
                wrong, setup.desk(), options(setup, env, BindMode.REPLAY, ScenarioA.bindingsRoot()), replayEvents));
        assertEquals(FailureKind.REBIND_NEEDED, replay.kind(), replay.getMessage());
        assertEquals("ORACLE_CHANGED", replay.reason(), replay.getMessage());
        assertEquals("waiver", replay.detail().get("oracle"));
        assertEquals(expected.waiver(), replay.detail().get("expected"), "the recorded value");
        assertEquals(expected.wrongAmount(), ((Map<?, ?>) replay.detail().get("actual")).get("amount"));
        assertEquals(0, replayEvents.count("crosscheck"), "step 7 comes before the cross-check");
    }

    private static void assertOracleSuspect(BindException e, ScenarioB.Expected expected, String mode, Env env,
                                            LocalDate today) {
        assertEquals(FailureKind.ORACLE_SUSPECT, e.kind(), e.getMessage());
        assertEquals("DISAGREED", e.reason(), e.getMessage());
        Map<?, ?> detail = e.detail();
        assertEquals("waiver", detail.get("oracle"), String.valueOf(detail));
        assertEquals("ExpectedWaiver", detail.get("fn"));
        assertEquals("lending.waiver.evaluate", detail.get("check"));
        assertEquals(expected.wrongAmount(), ((Map<?, ?>) detail.get("expected")).get("amount"),
                "the oracle's output, encoded");
        Map<?, ?> actual = (Map<?, ?>) detail.get("actual");
        assertEquals(expected.amount(), actual.get("amount"), "what the desk waives");
        assertEquals(today.toString(), actual.get("date"), "the desk's business date, in the run's outputs only");
        assertEquals(expected.ruleCode(), actual.get("ruleCode"));
        assertEquals("0.00", detail.get("tolerance"));
        assertEquals(mode, detail.get("mode"));
        assertEquals(env.id, detail.get("envId"));
        assertTrue(detail.get("inputsHash") instanceof String hash && hash.startsWith("sha256:"), String.valueOf(
                detail));
        assertTrue(String.valueOf(detail.get("evidence")).contains("lending.waiver.evaluate"), "the call as evidence");
        assertEquals("the desk waives " + expected.amount() + " for 3 units of " + expected.itemCode() + " under "
                + expected.ruleCode() + " on " + today + "; the oracle expects "
                + expected.wrongAmount(), detail.get("message"));
        assertEquals(List.of("rule"), ((List<?>) detail.get("fixedUpstream")).stream()
                .map(u -> ((Map<?, ?>) u).get("slot")).toList(), "the query slots its own arguments reference ($rule;"
                + " $oracle.needed is an oracle)");
        assertTrue(e.getMessage().startsWith("oracle waiver (ExpectedWaiver) disagrees with lending.waiver.evaluate:"
                + " expected "), e.getMessage());
    }

    /**
     * A bindings file approved with the wrong oracle (by a pack whose check agreed without asking) is ORACLE_SUSPECT
     * in {@code replay} with the real cross-check: step 7 finds the value it recorded, step 8 the desk's own dry run
     * (11.1 item 6). The right oracle against the same file is ORACLE_CHANGED.
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void aFileApprovedWithTheWrongOracleIsOracleSuspectInReplay(Env env) throws Exception {
        Setup setup = setUp(env);
        ScenarioB.Expected expected = expected(env);
        Path root = tmp.resolve("b-wrong-file");
        BindOutcome approved = Binder.bind(Plan.read(ScenarioB.plan()), OverlayPack.wrongOracleUnchecked(),
                setup.desk(), options(setup, env, BindMode.APPROVE, root).withApprover(Approver.APPROVE_ALL),
                BindListener.NONE);
        assertNotNull(approved.written());
        BindingsFile file = BindingsFile.read(approved.written());
        assertEquals(expected.wrongAmount(), ((Map<?, ?>) file.oracles().get("waiver").value()).get("amount"),
                "the file holds the wrong oracle's value");
        Events events = new Events();
        BindException suspect = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioB.plan()),
                OverlayPack.wrongOracle(), setup.desk(), options(setup, env, BindMode.REPLAY, root), events));
        assertOracleSuspect(suspect, expected, "replay", env, setup.today());
        assertEquals(ScenarioB.QUERY_SLOTS.size(), events.count("verifier"), "every slot passed its verifier first");
        BindException changed = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioB.plan()),
                Runs.PACK, setup.desk(), options(setup, env, BindMode.REPLAY, root), null));
        assertEquals("ORACLE_CHANGED", changed.reason(), changed.getMessage());
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void theWaiverNegativesFailInLiveAsTheContractSays(Env env) throws Exception {
        Setup setup = setUp(env);
        ScenarioB.Expected expected = expected(env);
        Path root = tmp.resolve("b-negative-root");
        BindException none = assertThrows(BindException.class, () -> Binder.bind(Runs.negative(
                "no-rule-of-kind.yaml"), Runs.PACK, setup.desk(), options(setup, env, BindMode.LIVE, root), null));
        assertEquals(FailureKind.RESOLUTION_FAILED, none.kind(), none.getMessage());
        assertEquals("NO_CANDIDATE", none.reason());
        assertEquals("rule", none.slot());

        Events events = new Events();
        BindException guard = assertThrows(BindException.class, () -> Binder.bind(Runs.negative(
                "waiver-guard-fails.yaml"), Runs.PACK, setup.desk(), options(setup, env, BindMode.LIVE, root),
                events));
        assertEquals(FailureKind.RESOLUTION_FAILED, guard.kind(), guard.getMessage());
        assertEquals("GUARD", guard.reason());
        assertEquals("waiver", guard.detail().get("oracle"), String.valueOf(guard.detail()));
        Map<?, ?> check = (Map<?, ?>) guard.detail().get("guard");
        assertEquals(Map.of("ruleCode", expected.ruleCode(), "amount", "0.00", "waivedUnits", 0L),
                check.get("mustEqual"), String.valueOf(check));
        assertEquals(expected.waiver(), check.get("actual"));
        int crossCheck = events.types.indexOf("crosscheck");
        assertTrue(crossCheck >= 0 && crossCheck < events.types.indexOf("guard"), "step 8 before step 9: "
                + events.types);
        assertEquals(Boolean.TRUE, events.records.get(crossCheck).get("agreed"), "the cross-check agreed");
        assertFalse(Files.exists(root), "nothing written");
        assertFalse(setup.desk().calls.contains("lending.input"));
    }

    /**
     * The guarded form (10.4, the negative material; 6.2): scenario B with its cross-check guarded by the capability
     * {@code lending.waiver.evaluate}. Approved where the capability is OK, the check runs; replayed where it is
     * UNAVAILABLE (or undeclared), the check is skipped, recorded, and listed in {@code BindOutcome.skipped}, which
     * makes the run DEGRADED at best; the bind itself succeeds. The verifier still asks the dry run: only the check
     * is guarded, and the plan still requires the capability for the verifier.
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void aGuardedCrossCheckWhoseCapabilityIsUnavailableIsSkipped(Env env) throws Exception {
        Setup setup = setUp(env);
        Plan guarded = PlanCompiler.compile(ScenarioB.guardedYaml(), ScenarioB.YAML, Runs.PACK);
        assertEquals(new Plan.CrossCheckSpec("lending.waiver.evaluate", null, "lending.waiver.evaluate"),
                guarded.oracle("waiver").crossCheck());
        assertTrue(guarded.requires().contains("lending.waiver.evaluate"), "the rule's verifier needs it");
        Path root = tmp.resolve("b-guarded");
        DeskConnection ok = setup.desk().capability("lending.waiver.evaluate", CapabilityState.OK, null);
        BindOutcome approved = Binder.bind(guarded, Runs.PACK, ok, options(setup, env, BindMode.APPROVE, root)
                .withApprover(Approver.APPROVE_ALL), BindListener.NONE);
        assertTrue(approved.bindings().crossChecks().get("waiver").agreed(), "an OK capability runs the check");
        assertEquals(List.of(), approved.skipped());

        for (CapabilityState state : List.of(CapabilityState.UNAVAILABLE, CapabilityState.DEGRADED)) {
            DeskConnection desk = new DeskConnection(db, setup.today()).capability(
                    "lending.waiver.evaluate", state, "scripted by the test");
            Events events = new Events();
            BindOutcome replay = Binder.bind(guarded, Runs.PACK, desk, options(setup, env, BindMode.REPLAY, root),
                    events);
            assertEquals(List.of("oracle waiver: cross-check lending.waiver.evaluate skipped: capability"
                    + " lending.waiver.evaluate is " + state + " (scripted by the test)"), replay.skipped());
            Map<String, Object> record = events.records.get(events.types.indexOf("crosscheck"));
            assertEquals(Boolean.TRUE, record.get("skipped"), String.valueOf(record));
            assertEquals(state.name(), record.get("state"));
            assertTrue(replay.bindings().crossChecks().get("waiver").skipped());
            assertEquals(expected(env).waiver(), replay.bindings().oracle("waiver"), "the oracle's value stands");
            assertEquals(1L, desk.calls.stream().filter("lending.waiver.evaluate"::equals).count(),
                    "the verifier's dry run only");
        }
        DeskConnection undeclared = new DeskConnection(db, setup.today());
        BindOutcome replay = Binder.bind(guarded, Runs.PACK, undeclared, options(setup, env, BindMode.REPLAY, root),
                BindListener.NONE);
        assertEquals(List.of("oracle waiver: cross-check lending.waiver.evaluate skipped: capability"
                + " lending.waiver.evaluate is UNAVAILABLE (the target does not declare it)"), replay.skipped());
    }

    /**
     * Scenario B's {@code db.*} statements, run through the JDBC channel against a loan inserted the way the desk
     * records one (a line of the trigger item and its waiver), with a second loan of two waiver rows: the
     * {@code loan_waiver} statement reads one loan's rows only, and its aggregate {@code sum(amount)}, computed over
     * the rows returned as the executor computes it (7), equals the oracle's amount; over two rows it sums both.
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void scenarioBsStoredRowStatementsAndTheAggregateRunOnTheSameDatabase(Env env) throws Exception {
        Setup setup = setUp(env);
        ScenarioB.Expected expected = expected(env);
        long loanId = 990_101L;
        long other = 990_102L;
        Connection c = db.connection();
        try (PreparedStatement loan = c.prepareStatement("INSERT INTO loan (id, staff_code, posted_at, total)"
                     + " VALUES (?, ?, CURRENT_TIMESTAMP, CAST(? AS DECIMAL(10,2)))");
             PreparedStatement line = c.prepareStatement("INSERT INTO loan_line (loan_id, line_no, item_code, qty,"
                     + " unit_deposit, amount, returned) VALUES (?, 1, ?, 3, CAST(? AS DECIMAL(10,2)),"
                     + " CAST(? AS DECIMAL(10,2)), FALSE)");
             PreparedStatement waiver = c.prepareStatement("INSERT INTO loan_waiver (loan_id, rule_code, amount)"
                     + " VALUES (?, ?, CAST(? AS DECIMAL(10,2)))")) {
            for (long id : List.of(loanId, other)) {
                loan.setLong(1, id);
                loan.setString(2, expected.assistantCode());
                loan.setString(3, expected.total());
                loan.executeUpdate();
                line.setLong(1, id);
                line.setString(2, expected.itemCode());
                line.setString(3, (String) expected.item().get("deposit"));
                line.setString(4, expected.subtotal());
                line.executeUpdate();
                waiver.setLong(1, id);
                waiver.setString(2, expected.ruleCode());
                waiver.setString(3, expected.amount());
                waiver.executeUpdate();
            }
            waiver.setLong(1, other);
            waiver.setString(2, "B1W1-EXTRA");
            waiver.setString(3, "0.50");
            waiver.executeUpdate();
            if (!c.getAutoCommit()) {
                c.commit();
            }
        }
        Plan plan = Plan.read(ScenarioB.plan());
        List<RowCheck> checks = new ArrayList<>();
        for (Check check : plan.verify()) {
            if (check instanceof RowCheck row) {
                checks.add(row);
            }
        }
        assertEquals(List.of("loan", "loan_line", "loan_waiver"), checks.stream().map(RowCheck::table).toList());
        RowCheck waivers = checks.get(2);
        assertEquals(Map.of("sum(amount)", Map.of("equals", "$oracle.waiver.amount")), waivers.aggregates());
        Map<String, StoredTable> tables = new java.util.HashMap<>();
        Runs.PACK.storedTables().forEach(t -> tables.put(t.name(), t));
        try (JdbcDataChannel jdbc = new JdbcDataChannel(setup.credentials(), Runs.PACK.rowMasks())) {
            for (long id : List.of(loanId, other)) {
                Map<String, Object> values = Map.of("$loan.id", id, "$item.code", expected.itemCode());
                for (RowCheck check : checks) {
                    List<Object> params = new ArrayList<>();
                    for (RowCheck.Param param : check.params()) {
                        params.add(values.get(String.valueOf(param.value())));
                    }
                    Rows rows = jdbc.query(check.source(), check.sql(), params, DataChannel.MAX_ROWS, null);
                    StoredTable table = tables.get(check.table());
                    assertEquals(table.columns().size(), rows.columns().size(), check.table());
                    for (int i = 0; i < table.columns().size(); i++) {
                        assertEquals(table.columns().get(i).name().toUpperCase(Locale.ROOT),
                                rows.columns().get(i).name(), "position " + i + " of " + check.table());
                    }
                    if (!check.table().equals("loan_waiver")) {
                        assertEquals(1, rows.rowCount(), check.table() + ": " + rows);
                        continue;
                    }
                    assertEquals(id == loanId ? 1 : 2, rows.rowCount(), "the where reads one loan's rows: " + rows);
                    for (Map.Entry<String, Object> aggregate : check.aggregates().entrySet()) {
                        RowCheck.Aggregate parsed = RowCheck.Aggregate.parse(aggregate.getKey());
                        int column = table.columns().stream().map(col -> col.name()).toList().indexOf(
                                parsed.column());
                        BigDecimal sum = BigDecimal.ZERO;
                        for (List<Object> row : rows.rows()) {
                            sum = sum.add(new BigDecimal(String.valueOf(row.get(column))));
                        }
                        BigDecimal wanted = new BigDecimal(expected.amount());
                        assertEquals(0, sum.compareTo(id == loanId ? wanted : wanted.add(new BigDecimal("0.50"))),
                                aggregate.getKey() + " over " + rows);
                    }
                    assertTrue(rows.rows().stream().anyMatch(row -> expected.ruleCode().equals(row.get(1))),
                            "the rule's row: " + rows);
                }
            }
        }
    }

    /** The plan compiled now is the committed one, and names no channel and no date: either serves it, any day. */
    @ParameterizedTest
    @EnumSource(Env.class)
    void scenarioBsPlanIsTheSameForBothSeeds(Env env) throws Exception {
        Plan compiled = PlanCompiler.compile(ScenarioB.yaml(), Runs.PACK);
        assertEquals(Files.readString(ScenarioB.plan(), StandardCharsets.UTF_8), compiled.toJsonText());
        assertEquals(ScenarioB.GRAPH, compiled.resolutionGraph());
        assertEquals(Map.of(), compiled.launchProperties(), "no launch property: the environment decides");
        assertFalse(ScenarioB.ISO_DATE.matcher(compiled.toJsonText()).find(), "no date in the plan");
        assertTrue(Files.isRegularFile(ScenarioB.committedBindings(env)), env.id);
    }
}
