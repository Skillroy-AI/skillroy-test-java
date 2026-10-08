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

import ai.skillroy.testjava.inference.sql.SqlGuard;
import ai.skillroy.testjava.junit.ScenarioSource;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sample scenario D, {@code supervisor-return-llm-sql} (phase5-contracts.md 14.2): scenario A with its supervisor
 * found by a model (tier (b), strategy {@code llm-sql}). Where its files are, and what a live answer must hold.
 *
 * <p><b>Its bindings files</b> on {@code seed-a} and {@code seed-b} are written by {@code approve} against the recorded
 * gateway (14.2, 14.5) from recordings made live by the record route ({@code -Pinference-record},
 * {@link ScenarioDRecordIT}; decision D111), with {@code approval.by} {@code recorded <date>}, the UTC date of the
 * re-record. The tests read that label from the files ({@link #approvedBy}), so that a later re-record changes the
 * files and not the tests, and they pin what any live answer must hold, never what a model may word differently:
 * {@link #assertLiveAnswer} and {@link #assertLiveRecording}, which the unit tests apply to the committed files and the
 * record route to a new answer before it commits it (a model of the Sonnet line, {@value #SONNET_LINE}; a confidence
 * at or above the settings' threshold, {@link #confidenceThreshold()}; a rationale; one guarded statement,
 * {@link #guardedStatement}; the value loader's statement; and above all the supervisor the committed SQL finds on the
 * same seed). {@code ScenarioDRecordedTest} rewrites the files from the recordings that exist
 * ({@code -Dskillroy.inference.fixtures.update=true}); the recordings' README says how they were recorded.
 * {@link #HAS_BINDINGS} says that they are committed: the JUnit glue's replay of the committed directory runs scenario
 * D with A and B ({@link #withoutScenarioDUntilItHasBindings} passes the directory through), and the bindings files'
 * own checks ({@code BindingsUpToDateTest}) cover its files.
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioD {

    static final String ID = "supervisor-return-llm-sql";
    static final String YAML = ID + ".yaml";

    /** The slot a model resolves; the other query slots keep scenario A's strategies. */
    static final String MODEL_SLOT = "supervisor";

    /** The {@code approval.by} a live re-record writes: {@code recorded <yyyy-mm-dd>} (phase5-contracts.md 4.2). */
    static final Pattern RECORDED_LABEL = Pattern.compile("recorded \\d{4}-\\d{2}-\\d{2}");

    /** How every model id of the Sonnet line starts: the class a tier (b) request asks (phase5-contracts.md 5.4). */
    static final String SONNET_LINE = "claude-sonnet-";

    /** How a guarded statement starts: a {@code SELECT}, or a {@code WITH} clause before one (the guard takes both). */
    private static final Pattern GUARDED_START = Pattern.compile("(?i)^(?:WITH|SELECT)\\b");
    /** How a guarded statement ends: the row limit the guard injected, or a smaller one the model wrote and it kept. */
    private static final Pattern GUARDED_LIMIT = Pattern.compile("(?i)\\bLIMIT\\s+(\\d{1,9})$");
    /** The table {@code staff} named as a whole word ({@code staff_role} and {@code staff_code} do not count). */
    private static final Pattern NAMES_STAFF = Pattern.compile("(?i)\\bstaff\\b");

    /**
     * Whether scenario D's bindings files are committed: true since the recording wave of the Phase 5 build
     * (2026-10-08) wrote them through the recorded gateway (from scripted stand-ins, which the record route replaced
     * with live recordings in the live session the same day).
     */
    static final boolean HAS_BINDINGS = true;

    private ScenarioD() {
    }

    /** {@code scenarios/<id>.yaml} (committed). */
    static Path yaml() {
        return ItEnv.scenario(YAML);
    }

    /** {@code plan/<id>.plan.json} (committed). */
    static Path plan() {
        return ItEnv.plan(ID);
    }

    /** {@code bindings/<env>/<id>.bindings.json}: committed once {@link #HAS_BINDINGS} is true. */
    static Path committedBindings(Env env) {
        return BindingsFile.locate(ScenarioA.bindingsRoot(), env.id, ID);
    }

    /**
     * The {@code approval.by} of the supervisor slot in the committed bindings file of {@code env}: the label the
     * record route wrote ({@code recorded <date>}, phase5-contracts.md 4.2, 14.2), read from the file itself (D111) so
     * that a later re-record changes the files and not the tests. The file is read as JSON, not through the bindings
     * reader, so that the update path can take the label from a file in an earlier format.
     *
     * @throws AssertionError when the file cannot be read or holds no such label, or the label is not
     *     {@code recorded <yyyy-mm-dd>}
     */
    static String approvedBy(Env env) {
        Path file = committedBindings(env);
        Object by;
        try {
            Object node = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (String key : List.of("resolutions", MODEL_SLOT, "approval", "by")) {
                node = node instanceof Map<?, ?> map ? map.get(key) : null;
            }
            by = node;
        } catch (IOException | JsonException e) {
            throw new AssertionError("scenario D's committed bindings file " + file + " cannot be read: " + e, e);
        }
        if (!(by instanceof String label) || !RECORDED_LABEL.matcher(label).matches()) {
            throw new AssertionError("the approval.by of slot " + MODEL_SLOT + " in " + file + " is "
                    + (by == null ? "missing" : "'" + by + "'") + "; a live re-record writes 'recorded <yyyy-mm-dd>'"
                    + " (-Pinference-record, ScenarioDRecordIT)");
        }
        return label;
    }

    /**
     * The confidence a model's answer must reach: the {@code confidence} threshold of the settings this module binds
     * scenario D with ({@link ScriptedAnswers#recorded}; the default {@code 0.8}, phase5-contracts.md 4.4, 5.4), read
     * as the inference module's contributor reads it.
     */
    static BigDecimal confidenceThreshold() {
        return new BigDecimal(ScriptedAnswers.recorded(ScriptedAnswers.recordings()).thresholds().get("confidence"));
    }

    /**
     * The guarded statement among a tier (b) resolution's provenance statements (phase5-contracts.md 5.3, 14.2): the
     * one statement that starts with {@code SELECT} or {@code WITH}, ends with {@code LIMIT <n>} for an n from 1 to the
     * guard's cap ({@link SqlGuard#ROW_CAP}: the guard injects the cap, or keeps a smaller limit the model wrote) and
     * reads the table {@code staff}. The prompt catalogue's probes and the value loader's statements end without a
     * limit, and its samples read other tables; what a model may word differently (its conditions, joins, aliases, its
     * own smaller limit) is not pinned.
     *
     * @return that statement
     * @throws AssertionError when no statement, or more than one, is such a statement
     */
    static String guardedStatement(List<String> statements) {
        List<String> guarded = statements.stream().filter(ScenarioD::guarded).toList();
        if (guarded.size() != 1) {
            throw new AssertionError("one guarded statement that reads staff and ends with LIMIT <n> (n from 1 to "
                    + SqlGuard.ROW_CAP + ") is expected, found " + guarded.size() + " in " + statements);
        }
        return guarded.get(0);
    }

    private static boolean guarded(String sql) {
        String statement = sql.strip();
        Matcher limit = GUARDED_LIMIT.matcher(statement);
        if (!GUARDED_START.matcher(statement).find() || !limit.find() || !NAMES_STAFF.matcher(statement).find()) {
            return false;
        }
        long rows = Long.parseLong(limit.group(1));
        return rows >= 1 && rows <= SqlGuard.ROW_CAP;
    }

    /**
     * What any live answer to scenario D's supervisor query holds, and nothing a model may word differently
     * (phase5-contracts.md 14.2): the slot resolved through tier (b) by the inference module's resolver to the
     * supervisor the committed SQL finds on this seed (scenario A's), every verifier passed with the desk's access
     * check as the gate, approved by {@code label}, answered by a model of the Sonnet line at a confidence at or above
     * the settings' threshold, with a rationale, one guarded statement ({@link #guardedStatement}) and the value
     * loader's own statement (the staff resolver's committed SQL) in the provenance. The unit tests pin the committed
     * files with it, and the record route a new answer before it commits it.
     *
     * @throws Exception when scenario A's committed bindings file cannot be read
     */
    static void assertLiveAnswer(Env env, Resolution supervisor, String label) throws Exception {
        assertNotNull(supervisor, env.id + ": slot " + MODEL_SLOT + " resolved");
        assertEquals(Strategy.LLM_SQL, supervisor.strategy(), env.id);
        assertEquals("inference.sql.StaffWithPermission", supervisor.provenance().resolverId(), env.id);
        assertEquals(ScenarioA.expected(env).supervisor(), supervisor.value(), env.id
                + ": the supervisor the committed SQL finds");
        assertFalse(supervisor.verified().isEmpty(), env.id + ": verified");
        assertTrue(supervisor.verified().stream().allMatch(VerifierResult::passed), env.id + ": "
                + supervisor.verified());
        assertEquals("lending.access.check", supervisor.verified().get(0).verifierId(), env.id);
        assertEquals(new Approval(Approval.State.APPROVED, label), supervisor.approval(), env.id);
        assertNotNull(supervisor.usage(), env.id + ": the model's usage is kept");
        assertTrue(String.valueOf(supervisor.usage().model()).startsWith(SONNET_LINE), env.id + ": "
                + supervisor.usage().model());
        assertTrue(BigDecimal.valueOf(supervisor.confidence()).compareTo(confidenceThreshold()) >= 0, env.id
                + ": confidence " + supervisor.confidence() + " below the threshold " + confidenceThreshold());
        String rationale = supervisor.provenance().rationale();
        assertTrue(rationale != null && !rationale.isBlank(), env.id + ": the model's rationale is kept");
        List<String> sql = supervisor.provenance().statements().stream().map(Provenance.Statement::sql).toList();
        guardedStatement(sql);
        String staffSql = BindingsFile.read(ScenarioA.committedBindings(env)).resolutions().get("assistant")
                .provenance().statements().get(0).sql();
        assertTrue(sql.contains(staffSql), env.id + ": the value loader reads with the staff resolver's own"
                + " statement: " + sql);
    }

    /**
     * A recording of scenario D's tier (b) request made live (phase5-contracts.md 4.2, 14.2): its usage names provider
     * {@code claude}, the model the recording names and the model its usage names are of the Sonnet line, and its
     * {@code recordedAt} is an instant.
     *
     * @throws IOException when the file cannot be read
     */
    static void assertLiveRecording(Path file) throws IOException {
        String name = file.getFileName().toString();
        Map<String, Object> json = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        Map<?, ?> usage = (Map<?, ?>) ((Map<?, ?>) json.get("response")).get("usage");
        assertEquals("claude", usage.get("provider"), name + ": recorded live");
        assertTrue(String.valueOf(json.get("model")).startsWith(SONNET_LINE), name + ": " + json.get("model"));
        assertTrue(String.valueOf(usage.get("model")).startsWith(SONNET_LINE), name + ": " + usage.get("model"));
        assertDoesNotThrow(() -> Instant.parse(String.valueOf(json.get("recordedAt"))), name + ": recordedAt "
                + json.get("recordedAt"));
    }

    /**
     * {@code source} without scenario D's file while {@link #HAS_BINDINGS} is false, so that a replay of the
     * committed directory does not run a scenario whose bindings are not written; the scenario directory (and with it
     * the glue's default bindings root) stays the source's. With the files committed, {@code source} itself.
     */
    static ScenarioSource withoutScenarioDUntilItHasBindings(ScenarioSource source) {
        if (HAS_BINDINGS) {
            return source;
        }
        return new ScenarioSource() {
            @Override
            public List<Entry> entries() throws IOException {
                return source.entries().stream().filter(entry -> !entry.name().equals(YAML)).toList();
            }

            @Override
            public Path scenarioDirectory() {
                return source.scenarioDirectory();
            }
        };
    }

    /** True when either seed's bindings file of scenario D exists. */
    static boolean anyBindingsCommitted() {
        for (Env env : Env.values()) {
            if (Files.exists(committedBindings(env))) {
                return true;
            }
        }
        return false;
    }
}
