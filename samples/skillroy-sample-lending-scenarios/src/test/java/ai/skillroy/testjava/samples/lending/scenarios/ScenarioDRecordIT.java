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
import ai.skillroy.testjava.inference.recorded.Recording;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.JdbcSource;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The record route of the profile {@code inference-record} (phase5-contracts.md 2.3, 4.2, 14.2; decision D111):
 * scenario D's tier (b) requests recorded live by a maintainer with the model key, checked, and committed together with
 * the bindings files of both seeds written from them, all or nothing.
 *
 * <p>Runs only under {@code -Pinference-record} (the property {@code skillroy.inference.record}) with
 * {@code ANTHROPIC_API_KEY} in the environment ({@link Live#assumeKey()}); skipped otherwise, never failed, so that
 * every {@code verify} may run it. Of the key only its presence is looked at. Then, without a display, against the
 * sample's database in this JVM over the controller-side JDBC channel (as {@link ScenarioDRecordedTest} binds), and
 * with nothing of the module changed until every step has passed on both seeds:
 *
 * <ol>
 *   <li>on each seed scenario D is bound in {@code approve} without reuse through the live gateway wrapped in a
 *       {@code RecordingGateway} ({@code gateway: claude}, {@code record: true}) that writes into a new temporary
 *       directory, not the module's; the answer must hold what the unit tests pin ({@link ScenarioD#assertLiveAnswer}:
 *       the supervisor the committed SQL finds, the desk's access check passed, a model of the Sonnet line, a
 *       confidence at or above the settings' threshold, a rationale, one guarded statement, the value loader's
 *       statement, the label) and the recording must be a live one ({@link ScenarioD#assertLiveRecording});</li>
 *   <li>on each seed the bindings file is written from the new recording through the recorded gateway, as
 *       {@code approve} writes it, with the label {@code recorded <date>} (the UTC date of the run), into the temporary
 *       directory with the channel rewritten to the agent's (as the end-to-end check writes it); its answer is checked
 *       the same way, and the file as {@code BindingsUpToDateTest} checks every committed one (canonical form, no date
 *       outside an approval's label);</li>
 *   <li>the two recordings and the two bindings files pass the secret scan ({@link SecretScan}) for both seeds' staff
 *       passwords, the desk's PIN and the read-only account's password;</li>
 *   <li>only then are they committed: the two recordings move into the module's recordings directory, replacing its
 *       recordings of purpose {@code resolve}, the two bindings files replace the committed ones, and the label read
 *       back from them ({@link ScenarioD#approvedBy}) is this run's.</li>
 * </ol>
 *
 * <p>A failure on either seed, in a check or in the scan leaves the module's recordings and bindings files as they
 * were, so that a re-record that would break the unit tests' pins fails in the build that made it. What was recorded is
 * printed (the model, the usage and cost of each call, the recording's file, the confidence, the rationale, the
 * statements), never a key and never a prompt. The tests that read the committed files take the label from the files
 * ({@link ScenarioD#approvedBy}), so that a later re-record changes the files and not the tests.
 */
class ScenarioDRecordIT {

    /** The system property the profile {@code inference-record} sets for the test JVMs. */
    static final String RECORD_PROPERTY = "skillroy.inference.record";
    /** The read-only account's password here: test-local, at least 8 characters (masked in longer texts). */
    private static final String READER_PASSWORD = "Scenario-D-Record-7731";

    @TempDir
    Path tmp;

    @Test
    void recordsScenarioDsRequestsLiveAndWritesTheCommittedBindingsFiles() throws Exception {
        assumeTrue(Boolean.getBoolean(RECORD_PROPERTY), "the profile inference-record is not active");
        Live.assumeKey();

        String label = "recorded " + LocalDate.now(ZoneOffset.UTC);
        assertTrue(ScenarioD.RECORDED_LABEL.matcher(label).matches(), "the label the tests read back: " + label);
        Path staging = Files.createDirectories(tmp.resolve("recordings"));
        InferenceSettings live = new InferenceSettings(InferenceSettings.GATEWAY_CLAUDE, staging, true, null, null,
                null, null, null, null, true, false, null, false, null);
        Map<Env, Path> recordings = new EnumMap<>(Env.class);
        Map<Env, Path> bindings = new EnumMap<>(Env.class);
        LocalDate today = LocalDate.now();
        for (Env env : Env.values()) {
            LendingDb db = LendingDb.create("p5record" + env.seed, Seed.parse(env.seed), today);
            try {
                db.createReadOnlyUser(READER_PASSWORD);
                Credentials credentials = SampleCredentials.load(tmp.resolve("credentials-" + env.id), env,
                        new JdbcSource(db.url(), LendingDb.READER_USER, READER_PASSWORD));
                DeskConnection desk = new DeskConnection(db, today);

                // 1. Live, recorded on the way back into the staging directory.
                CostLedger ledger = new CostLedger();
                BindOutcome liveOutcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, desk,
                        approve(env, tmp.resolve("live-" + env.id), credentials, live, label, ledger),
                        BindListener.NONE);
                assertEquals(1, ledger.size(), "one live model call: " + ledger.entries());
                CostLedger.Entry call = ledger.entries().get(0);
                assertEquals("claude", call.provider(), String.valueOf(call));
                assertNotNull(call.details());
                Path recording = Recording.file(staging, String.valueOf(call.details().get("fingerprint")));
                assertTrue(Files.isRegularFile(recording), "the recording was written: " + recording);
                Resolution supervisor = liveOutcome.bindings().resolutions().get(ScenarioD.MODEL_SLOT);
                print(env, recording, call, supervisor);
                ScenarioD.assertLiveRecording(recording);
                ScenarioD.assertLiveAnswer(env, supervisor, label);
                recordings.put(env, recording);

                // 2. The bindings file from the new recording, through the recorded gateway, staged too.
                CostLedger again = new CostLedger();
                BindOutcome outcome = Binder.bind(Plan.read(ScenarioD.plan()), Runs.PACK, desk,
                        approve(env, tmp.resolve("recorded-" + env.id), credentials, ScriptedAnswers.recorded(staging),
                                label, again), BindListener.NONE);
                assertEquals(1, again.size());
                assertEquals("recorded", again.entries().get(0).provider());
                ScenarioD.assertLiveAnswer(env, outcome.bindings().resolutions().get(ScenarioD.MODEL_SLOT), label);
                String text = ScenarioDRecordedTest.asTheAgentWritesIt(Files.readString(outcome.written(),
                        StandardCharsets.UTF_8));
                assertEquals(text, BindingsFile.parse(text).toJsonText(), env.id + ": in canonical form");
                assertFalse(ScenarioB.ISO_DATE.matcher(text.replaceAll("\"by\": \"[^\"]*\"", "")).find(), env.id
                        + ": no date outside an approval's label, as every committed bindings file");
                Path staged = tmp.resolve("bindings").resolve(env.id).resolve(ScenarioD.committedBindings(env)
                        .getFileName());
                Files.createDirectories(staged.getParent());
                Files.writeString(staged, text, StandardCharsets.UTF_8);
                bindings.put(env, staged);
            } finally {
                db.close();
            }
        }

        // 3. No secret in anything about to be committed.
        Set<String> secrets = new HashSet<>();
        for (Env env : Env.values()) {
            secrets.addAll(SampleCredentials.staff(env).values());
        }
        secrets.add(LendingTarget.PIN);
        secrets.add(READER_PASSWORD);
        List<String> leaks = new ArrayList<>();
        for (Path file : Stream.concat(recordings.values().stream(), bindings.values().stream()).toList()) {
            for (String hit : SecretScan.leaks(file, secrets)) {
                leaks.add(file.getFileName() + ": " + hit);
            }
        }
        assertEquals(List.of(), leaks, "a secret in what the record route would commit");

        // 4. Both seeds passed: the recordings replace the module's of purpose resolve, the files the committed ones.
        Path dir = Files.createDirectories(ScriptedAnswers.recordings());
        Set<String> fresh = new HashSet<>();
        for (Path recording : recordings.values()) {
            Files.move(recording, dir.resolve(recording.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            fresh.add(recording.getFileName().toString());
        }
        List<Path> removed = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if (!fresh.contains(file.getFileName().toString())
                        && "resolve".equals(Recording.read(file).purpose())) {
                    Files.delete(file);
                    removed.add(file.getFileName());
                }
            }
        }
        System.out.println("[record] committed recordings " + fresh + "; removed " + removed.size()
                + " earlier recording(s) of purpose resolve: " + removed);
        for (Env env : Env.values()) {
            Path committed = ScenarioD.committedBindings(env);
            Files.createDirectories(committed.getParent());
            Files.copy(bindings.get(env), committed, StandardCopyOption.REPLACE_EXISTING);
            assertEquals(label, ScenarioD.approvedBy(env), "the label the tests read back");
            System.out.println("[record] " + env.id + ": wrote " + committed + " with approval.by '" + label + "'");
        }
    }

    /** The figures of a live call and what the model answered: never a key, never a prompt. */
    private static void print(Env env, Path recording, CostLedger.Entry call, Resolution supervisor)
            throws Exception {
        Map<String, Object> recorded = Json.parseObject(Files.readString(recording));
        Map<?, ?> usage = (Map<?, ?>) ((Map<?, ?>) recorded.get("response")).get("usage");
        System.out.println("[record] " + env.id + ": provider " + usage.get("provider") + ", model "
                + usage.get("model") + ", request " + usage.get("requestId") + ", tokens in "
                + usage.get("inputTokens") + " out " + usage.get("outputTokens") + " cacheRead "
                + usage.get("cacheReadTokens") + " cacheWrite5m " + usage.get("cacheWrite5mTokens")
                + " cacheWrite1h " + usage.get("cacheWrite1hTokens") + ", cost USD " + usage.get("costUsd")
                + ", elapsed " + call.details().get("elapsedMs") + " ms, recording " + recording.getFileName());
        if (supervisor == null) {
            return;
        }
        System.out.println("[record] " + env.id + ": confidence " + supervisor.confidence() + ", rationale: "
                + supervisor.provenance().rationale());
        for (Provenance.Statement statement : supervisor.provenance().statements()) {
            System.out.println("[record] " + env.id + ": statement " + statement.sql());
        }
    }

    /** {@code approve} without reuse, the scripted approver, the given label and settings. */
    private static BindOptions approve(Env env, Path root, Credentials credentials, InferenceSettings inference,
                                       String label, CostLedger ledger) {
        return BindOptions.defaults().withMode(BindMode.APPROVE).withEnvId(env.id).withBindingsRoot(root)
                .withCredentials(credentials).withLedger(ledger).withInference(inference).withApprovedBy(label)
                .withApprover(request -> new Approver.Decision(true, null, "the record route"))
                .withReuseBindings(false);
    }
}
