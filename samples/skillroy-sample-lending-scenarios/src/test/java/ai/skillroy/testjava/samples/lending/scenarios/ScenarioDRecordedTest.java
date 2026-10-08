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
import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.job.JobOptions;
import ai.skillroy.testjava.inference.job.ReResolveReport;
import ai.skillroy.testjava.inference.job.ReResolver;
import ai.skillroy.testjava.inference.recorded.Recording;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.protocol.Json;
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
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Scenario D ({@code supervisor-return-llm-sql}, phase5-contracts.md 14.2) bound through tier (b) without a display
 * and without a key, the module's normal build: the sample's database in this JVM over the controller-side JDBC
 * channel, the desk's real access rules behind {@link DeskConnection} (as {@link PackOnRealSchemaTest} binds), the
 * real binder finding the inference module's contributor, and the committed recordings answering through the recorded
 * gateway. Those recordings were made live by the record route ({@link ScenarioDRecordIT}, decision D111), so these
 * checks pin what any live answer must hold and nothing a model may word differently, with the record route's own
 * checks ({@link ScenarioD#assertLiveRecording}, {@link ScenarioD#assertLiveAnswer}): provider {@code claude} and a
 * model of the Sonnet line, a confidence at or above the settings' threshold, a rationale, one guarded statement (a
 * {@code SELECT}, or a {@code WITH} before one, that reads {@code staff} and ends with a {@code LIMIT} of at most the
 * guard's cap), the value loader's statement, and above all the supervisor the committed SQL finds on the same seed;
 * the label is the one the committed file holds ({@link ScenarioD#approvedBy}).
 *
 * <ul>
 *   <li>{@code approve} without reuse ({@code --no-reuse-bindings}) makes one recorded call per seed and writes the
 *       committed bindings file byte for byte (apart from the channel, {@code jdbc} here and {@code agent} in the
 *       committed file, which the end-to-end check writes); the model's supervisor is the committed SQL's, found
 *       through the pack's value loader and passed by the desk's access check;</li>
 *   <li>with reuse on (the default) it makes no call and writes the same file;</li>
 *   <li>{@code replay} loads the committed file and makes no call, whatever the inference settings say;</li>
 *   <li>a changed prompt catalogue (one word more in a table's description) ends ENVIRONMENT, "no recording";</li>
 *   <li>a scripted gateway answering a statement of the guard's reject corpus ends RESOLUTION_FAILED with the guard's
 *       reason in the detail;</li>
 *   <li>the nightly job's dry run (8.2) leaves scenarios A, B and D unchanged, D's request answered through the
 *       recorded gateway's batch interface (15.2 item 7, here without a display);</li>
 *   <li>every committed recording is named by its request's fingerprint: scenario D's two are live, the session's are
 *       scripted stand-ins (14.5).</li>
 * </ul>
 *
 * <p>{@code -Dskillroy.inference.fixtures.update=true} records nothing here (4.2): it rewrites the committed bindings
 * files of both seeds from the recordings that exist, through the recorded gateway, with the label each file holds, so
 * that a change of the bindings format regenerates them without a key and never replaces a live recording with a
 * scripted one. Recording live is the record route's ({@code -Pinference-record}, {@link ScenarioDRecordIT}).
 */
class ScenarioDRecordedTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    /** The read-only account's password here: test-local, at least 8 characters (masked in longer texts). */
    private static final String READER_PASSWORD = "Scenario-D-Reader-4419";

    @TempDir
    Path tmp;

    private LendingDb db;

    @AfterEach
    void closeDatabase() {
        if (db != null) {
            db.close();
        }
    }

    /** The seeded database, the credentials naming it, and the desk connection. */
    private record Setup(LendingDb db, Credentials credentials, DeskConnection desk) {
    }

    private static Setup setUp(Env env, Path dir) throws Exception {
        LocalDate today = LocalDate.now();
        LendingDb db = LendingDb.create("p5scenariod" + env.seed + DATABASES.incrementAndGet(), Seed.parse(env.seed),
                today);
        db.createReadOnlyUser(READER_PASSWORD);
        Credentials credentials = SampleCredentials.load(dir.resolve("credentials-" + env.id), env,
                new JdbcSource(db.url(), LendingDb.READER_USER, READER_PASSWORD));
        return new Setup(db, credentials, new DeskConnection(db, today));
    }

    private Setup open(Env env) throws Exception {
        Setup setup = setUp(env, tmp);
        db = setup.db();
        return setup;
    }

    /** {@code approve} with the label the committed file of this seed holds, the scripted approver, a ledger. */
    private static BindOptions approve(Setup setup, Env env, Path root, InferenceSettings inference,
                                       CostLedger ledger) {
        return BindOptions.defaults().withMode(BindMode.APPROVE).withEnvId(env.id).withBindingsRoot(root)
                .withCredentials(setup.credentials()).withLedger(ledger).withInference(inference)
                .withApprovedBy(ScenarioD.approvedBy(env))
                .withApprover(request -> new Approver.Decision(true, null, "checked by the test"));
    }

    /** The text as the end-to-end agent channel writes it. */
    static String asTheAgentWritesIt(String text) {
        return text.replace("\"channel\": \"jdbc\"", "\"channel\": \"agent\"");
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

        Map<String, Object> first(String type, String slot) {
            for (int i = 0; i < types.size(); i++) {
                if (type.equals(types.get(i)) && slot.equals(records.get(i).get("slot"))) {
                    return records.get(i);
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ update

    /**
     * {@code -Dskillroy.inference.fixtures.update=true} (phase5-contracts.md 4.2): each seed's committed bindings file
     * rewritten from the recordings that exist, through the recorded gateway, with the label the file holds
     * ({@link ScenarioD#approvedBy}, read before it is rewritten), as the agent channel would write it. Nothing is
     * recorded: the tier (b) recordings are live, and only the record route ({@link ScenarioDRecordIT}) replaces them.
     */
    @BeforeAll
    static void regenerateWhenAsked(@TempDir Path dir) throws Exception {
        if (!ScriptedAnswers.update()) {
            return;
        }
        for (Env env : Env.values()) {
            Setup setup = setUp(env, dir);
            try {
                BindOutcome outcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, setup.desk(),
                        approve(setup, env, dir.resolve("recorded-" + env.id), ScriptedAnswers.recorded(
                                ScriptedAnswers.recordings()), new CostLedger()).withReuseBindings(false),
                        BindListener.NONE);
                Path committed = ScenarioD.committedBindings(env);
                Files.createDirectories(committed.getParent());
                Files.writeString(committed, asTheAgentWritesIt(Files.readString(outcome.written(),
                        StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            } finally {
                setup.db().close();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ checks

    /**
     * {@code approve --no-reuse-bindings} against the recordings: one recorded call, at no cost, answered by the live
     * recording of this request; the supervisor found through tier (b) is the committed SQL's supervisor of this seed
     * (the model's statement selected the code, the guard bounded it, the pack's value loader built the value, the
     * desk's access check passed it); the written file is the committed one byte for byte, apart from the channel.
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void approveWithoutReuseMakesOneRecordedCallAndWritesTheCommittedFile(Env env) throws Exception {
        Setup setup = open(env);
        CostLedger ledger = new CostLedger();
        Events events = new Events();
        Path root = tmp.resolve("approve-" + env.id);
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, setup.desk(), approve(setup, env,
                root, ScriptedAnswers.recorded(ScriptedAnswers.recordings()), ledger).withReuseBindings(false),
                events);
        assertEquals(1, ledger.size(), "one model call: " + ledger.entries());
        CostLedger.Entry call = ledger.entries().get(0);
        assertEquals("recorded", call.provider());
        assertEquals("0", call.costUsd());
        assertEquals(ScenarioD.MODEL_SLOT, call.slot());
        assertEquals(Strategy.LLM_SQL, call.strategy());
        assertNotNull(call.details(), "the record of inference.jsonl travels with the entry");
        Path recording = Recording.file(ScriptedAnswers.recordings(), String.valueOf(call.details().get(
                "fingerprint")));
        assertTrue(Files.isRegularFile(recording), "the committed recording of this request: " + recording);
        ScenarioD.assertLiveRecording(recording);

        Resolution supervisor = outcome.bindings().resolutions().get(ScenarioD.MODEL_SLOT);
        ScenarioD.assertLiveAnswer(env, supervisor, ScenarioD.approvedBy(env));
        assertEquals("0", supervisor.usage().costUsd(), "a recorded answer costs nothing");
        Map<String, String> strategies = new java.util.LinkedHashMap<>();
        for (String slot : ScenarioA.QUERY_SLOTS) {
            strategies.put(slot, outcome.bindings().strategy(slot));
        }
        assertEquals(Map.of("systemMax", "CANONICAL_SQL", "item", "CANONICAL_SQL", "assistant", "CANONICAL_SQL",
                "supervisor", "LLM_SQL", "keys", "CANONICAL_JAVA"), strategies, "two tiers in one scenario");
        assertEquals("SUPERVISOR_PROMPT", outcome.bindings().oracle("auth"), "the scenario still needs a supervisor");

        String text = Files.readString(outcome.written(), StandardCharsets.UTF_8);
        assertEquals(Files.readString(ScenarioD.committedBindings(env), StandardCharsets.UTF_8),
                asTheAgentWritesIt(text), "byte-identical to the committed file except for the channel (regenerate"
                        + " with -D" + ScriptedAnswers.UPDATE + "=true)");
        assertFalse(text.contains(READER_PASSWORD), "no JDBC password in the file");
        for (String password : SampleCredentials.staff(env).values()) {
            assertFalse(text.contains("\"" + password + "\""), "no password as a value in the file");
        }
        assertEquals(1L, events.count("inference"), "one inference record: " + events.types);
    }

    /** {@code approve} with reuse on (the default, D82): the recorded value holds, no model call, the same file. */
    @ParameterizedTest
    @EnumSource(Env.class)
    void approveWithReuseMakesNoCallAndWritesTheSameFile(Env env) throws Exception {
        Setup setup = open(env);
        Path root = tmp.resolve("reuse-" + env.id);
        Path existing = BindingsFile.locate(root, env.id, ScenarioD.ID);
        Files.createDirectories(existing.getParent());
        Files.copy(ScenarioD.committedBindings(env), existing);
        CostLedger ledger = new CostLedger();
        Events events = new Events();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, setup.desk(), approve(setup, env,
                root, ScriptedAnswers.recorded(tmp.resolve("no-recordings")), ledger), events);
        assertTrue(ledger.isEmpty(), "no model call: " + ledger.entries());
        Map<String, Object> resolve = events.first("resolve", ScenarioD.MODEL_SLOT);
        assertNotNull(resolve, String.valueOf(events.types));
        assertEquals(Boolean.TRUE, resolve.get("reused"), String.valueOf(resolve));
        assertEquals(ScenarioD.approvedBy(env), resolve.get("reusedFrom"));
        assertEquals(Files.readString(ScenarioD.committedBindings(env), StandardCharsets.UTF_8),
                asTheAgentWritesIt(Files.readString(outcome.written(), StandardCharsets.UTF_8)),
                "the file comes out byte-identical");
    }

    /** {@code replay} loads the committed file, re-runs every verifier, asks no resolver and makes no model call. */
    @ParameterizedTest
    @EnumSource(Env.class)
    void replayLoadsTheCommittedFileAndMakesNoCall(Env env) throws Exception {
        Setup setup = open(env);
        CostLedger ledger = new CostLedger();
        Events events = new Events();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, setup.desk(), BindOptions.defaults()
                .withMode(BindMode.REPLAY).withEnvId(env.id).withBindingsRoot(ScenarioA.bindingsRoot())
                .withCredentials(setup.credentials()).withLedger(ledger)
                .withInference(ScriptedAnswers.recorded(ScriptedAnswers.recordings())), events);
        assertTrue(ledger.isEmpty(), "zero model calls");
        assertEquals(0, events.count("resolver"), "a replay never calls a resolver");
        assertEquals(5, events.count("verifier"), "every slot's verifier ran once");
        assertNull(outcome.written());
        assertEquals("REPLAY", outcome.bindings().strategy(ScenarioD.MODEL_SLOT));
        assertEquals(ScenarioA.expected(env).supervisor(), outcome.bindings().resolutions().get(ScenarioD.MODEL_SLOT)
                .value(), "the recorded value (the joined password is in the bindings' values only)");
        Map<String, Object> inference = events.records.get(events.types.indexOf("inference"));
        assertEquals(InferenceSettings.GATEWAY_NONE, inference.get("gateway"), "replay substitutes none: " + inference);
    }

    /**
     * A changed prompt (the catalogue a model is shown, one word more in the description of the table {@code staff})
     * has no recording: ENVIRONMENT, "no recording", nothing written.
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void aChangedCatalogueEndsEnvironmentWithNoRecording(Env env) throws Exception {
        Setup setup = open(env);
        Path root = tmp.resolve("changed-" + env.id);
        DomainPack changed = OverlayPack.changedCatalogue();
        BindException e = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioD.plan()), changed,
                setup.desk(), approve(setup, env, root, ScriptedAnswers.recorded(ScriptedAnswers.recordings()),
                        new CostLedger()).withReuseBindings(false), BindListener.NONE));
        assertEquals(FailureKind.ENVIRONMENT, e.kind(), e.getMessage());
        assertTrue(e.getMessage().contains("no recording"), e.getMessage());
        assertEquals(ScenarioD.MODEL_SLOT, e.slot());
        assertFalse(Files.exists(root), "nothing written");
    }

    /**
     * A model that answers with a statement of the guard's HSQLDB reject corpus ({@code DELETE}): the guard refuses
     * it before anything runs, no candidate is offered, and the slot fails RESOLUTION_FAILED with the guard's reason
     * code in the detail; nothing is written and nothing reached the database.
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void aRejectCorpusStatementEndsResolutionFailedWithTheGuardsReason(Env env) throws Exception {
        Setup setup = open(env);
        Path root = tmp.resolve("reject-" + env.id);
        ScriptedGateway rejecting = ScriptedGateway.of(ScriptedAnswers::rejecting);
        CostLedger ledger = new CostLedger();
        BindException e;
        try (Gateways.Served served = Gateways.serve(ScriptedAnswers.scripted(), rejecting)) {
            e = assertThrows(BindException.class, () -> Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK,
                    setup.desk(), approve(setup, env, root, ScriptedAnswers.scripted(), ledger)
                            .withReuseBindings(false), BindListener.NONE));
        }
        assertEquals(FailureKind.RESOLUTION_FAILED, e.kind(), e.getMessage());
        assertEquals(ScenarioD.MODEL_SLOT, e.slot());
        String detail = Json.write(e.detail());
        assertTrue(detail.contains("guard " + ScriptedAnswers.REJECT_REASON), "the guard's reason: " + detail);
        assertFalse(detail.contains(ScriptedAnswers.REJECT_SQL), "the refused statement is not in the detail");
        assertEquals(1, rejecting.requests().size(), "one call, no retry of a refused statement");
        assertEquals(1, ledger.size());
        assertFalse(Files.exists(root), "nothing written");
    }

    /**
     * The nightly job's dry run (phase5-contracts.md 8.2, 15.2 item 7) on this seed against the recordings: scenarios A
     * and B (approved as {@code approve}) and scenario D (approved with the label its committed file holds) all come
     * out {@code UNCHANGED}, byte for byte, against copies of the committed files in the channel this test reads
     * through; D's tier (b) request goes through the recorded gateway's batch interface, and the report has its cache
     * line.
     * Two jobs, because a job approves every file it writes under one label (D's differs from A's and B's).
     */
    @ParameterizedTest
    @EnumSource(Env.class)
    void aDryRunOfTheNightlyJobLeavesEveryFileUnchanged(Env env) throws Exception {
        Setup setup = open(env);
        Path root = tmp.resolve("nightly-" + env.id);
        Map<Path, String> copies = new java.util.LinkedHashMap<>();
        for (Path committed : List.of(ScenarioA.committedBindings(env), ScenarioB.committedBindings(env),
                ScenarioD.committedBindings(env))) {
            Path copy = root.resolve(env.id).resolve(committed.getFileName());
            Files.createDirectories(copy.getParent());
            String text = Files.readString(committed, StandardCharsets.UTF_8).replace("\"channel\": \"agent\"",
                    "\"channel\": \"jdbc\"");
            Files.writeString(copy, text, StandardCharsets.UTF_8);
            copies.put(copy, text);
        }
        BindOptions options = BindOptions.defaults().withEnvId(env.id).withBindingsRoot(root)
                .withCredentials(setup.credentials()).withLedger(new CostLedger())
                .withInference(ScriptedAnswers.recorded(ScriptedAnswers.recordings()));
        JobOptions job = JobOptions.of(tmp.resolve("nightly-out-" + env.id), "nightly", new BigDecimal("1.00"))
                .withDryRun(true).withBatchTimes(Duration.ofMinutes(5), Duration.ZERO);
        ReResolveReport tierA = ReResolver.run(List.of(Plan.read(ScenarioA.plan()), Plan.read(ScenarioB.plan())),
                Runs.PACK, setup.desk(), options, job.withApprovedBy("approve"));
        assertEquals(List.of(ReResolveReport.Status.UNCHANGED, ReResolveReport.Status.UNCHANGED), tierA.scenarios()
                .stream().map(ReResolveReport.ScenarioReport::status).toList(), tierA.toMarkdown());
        assertEquals(0, tierA.requests(), "tier (a) needs no model");
        ReResolveReport tierB = ReResolver.run(List.of(Plan.read(ScenarioD.plan())), Runs.PACK, setup.desk(),
                options, job.withApprovedBy(ScenarioD.approvedBy(env)));
        assertEquals(ReResolveReport.Status.UNCHANGED, tierB.scenarios().get(0).status(), tierB.toMarkdown());
        assertEquals(1, tierB.batches(), "one batch of one request: " + tierB.toMarkdown());
        assertEquals(1, tierB.requests());
        assertTrue(tierB.toMarkdown().contains("; cache hits "), tierB.toMarkdown());
        assertEquals(0, tierB.exitCode());
        for (Map.Entry<Path, String> copy : copies.entrySet()) {
            assertEquals(copy.getValue(), Files.readString(copy.getKey(), StandardCharsets.UTF_8),
                    "a dry run writes nothing: " + copy.getKey().getFileName());
        }
    }

    /**
     * Every committed recording is named by its request's fingerprint; scenario D's two tier (b) recordings (purpose
     * {@code resolve}) are live (provider {@code claude}, a model of the Sonnet line, a real {@code recordedAt}), and
     * the session's (purpose {@code session}) are the scripted stand-ins made at {@link ScriptedAnswers#RECORDED_AT}
     * (phase5-contracts.md 14.5); no recording of another purpose is committed.
     */
    @Test
    void everyCommittedRecordingIsNamedByItsFingerprintScenarioDsLiveAndTheSessionsScripted() throws Exception {
        List<Path> files;
        try (Stream<Path> list = Files.list(ScriptedAnswers.recordings())) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "the recordings are committed");
        int resolve = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            Map<String, Object> json = Json.parseObject(Files.readString(file));
            assertEquals(name, json.get("fingerprint") + ".json");
            assertEquals(Recording.read(file).fingerprint(), json.get("fingerprint"));
            Map<?, ?> usage = (Map<?, ?>) ((Map<?, ?>) json.get("response")).get("usage");
            switch (String.valueOf(json.get("purpose"))) {
                case "resolve" -> {
                    resolve++;
                    ScenarioD.assertLiveRecording(file);
                }
                case "session" -> {
                    assertEquals(ScriptedGateway.PROVIDER, usage.get("provider"), name + ": a scripted stand-in");
                    assertEquals(ScriptedGateway.MODEL, usage.get("model"), name);
                    assertEquals(ScriptedAnswers.RECORDED_AT.toString(), json.get("recordedAt"), name);
                }
                default -> fail(name + ": a recording of purpose " + json.get("purpose") + " is not committed here");
            }
        }
        assertEquals(2, resolve, "scenario D's supervisor query on seed A and on seed B");
    }

    /**
     * The statement pin ({@link ScenarioD#guardedStatement}) is no stricter than the guard (phase5-contracts.md 5.3): it
     * takes the injected limit, a smaller limit the model wrote and a {@code WITH} clause, and finds the one such
     * statement that reads {@code staff} among the catalogue's samples and probes and the value loader's statements;
     * none, two, one that reads another table, a limit over the guard's cap or a statement that is not a query fail.
     */
    @Test
    void theStatementPinTakesWhatTheGuardTakes() {
        String sample = "SELECT DISTINCT s0.permission FROM role_permission s0 ORDER BY s0.permission LIMIT 21";
        String probe = "SELECT * FROM staff WHERE 1 = 0";
        String loader = "SELECT s.code AS code FROM staff s WHERE s.active = TRUE ORDER BY s.code";
        String injected = "SELECT s.code AS code FROM staff s WHERE s.code <> ? ORDER BY s.code LIMIT 200";
        String smaller = "SELECT s.code AS code FROM staff s ORDER BY s.code LIMIT 5";
        String with = "WITH r AS (SELECT sr.staff_code FROM staff_role sr) SELECT s.code AS code FROM staff s"
                + " JOIN r ON r.staff_code = s.code ORDER BY s.code LIMIT 200";
        for (String guarded : List.of(injected, smaller, with)) {
            assertEquals(guarded, ScenarioD.guardedStatement(List.of(probe, sample, guarded, loader)), guarded);
        }
        assertThrows(AssertionError.class, () -> ScenarioD.guardedStatement(List.of(probe, sample, loader)), "none");
        assertThrows(AssertionError.class, () -> ScenarioD.guardedStatement(List.of(injected, smaller)), "two");
        assertThrows(AssertionError.class, () -> ScenarioD.guardedStatement(List.of(
                "SELECT sr.staff_code AS code FROM staff_role sr ORDER BY sr.staff_code LIMIT 200")), "not staff");
        assertThrows(AssertionError.class, () -> ScenarioD.guardedStatement(List.of(
                "SELECT s.code AS code FROM staff s ORDER BY s.code LIMIT 201")), "over the cap");
        assertThrows(AssertionError.class, () -> ScenarioD.guardedStatement(List.of("DELETE FROM staff LIMIT 1")),
                "not a query");
    }
}
