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
import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.Plan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 2 acceptance 14.1 item 11 and Phase 3 acceptance 11.1 item 6: every committed bindings file
 * ({@code bindings/<env>/<id>.bindings.json}, scenarios A and B on both seeds) is in canonical form (read and written
 * again, it is byte-identical) and is tied to the committed plan (its {@code resolveHash} equals the plan's). A file
 * that fails here would fail every {@code replay} as {@code PLAN_CHANGED} or be rewritten on the next approval as
 * noise in the diff.
 *
 * <p>Scenario D ({@code supervisor-return-llm-sql}, phase5-contracts.md 14.2) has its files since the recording wave
 * of the Phase 5 build wrote them through the recorded gateway ({@link ScenarioD#HAS_BINDINGS}, which these checks
 * follow): the same checks apply to them, and its supervisor's approval names the recordings it came from.
 */
class BindingsUpToDateTest {

    @Test
    void everyCommittedBindingsFileIsCanonicalAndTiedToItsPlan() throws Exception {
        Path root = ScenarioA.bindingsRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        List<Path> expected = new ArrayList<>();
        for (Env env : Env.values()) {
            expected.add(ScenarioB.committedBindings(env));
            expected.add(ScenarioA.committedBindings(env));
            if (ScenarioD.HAS_BINDINGS) {
                expected.add(ScenarioD.committedBindings(env));
            }
        }
        assertEquals(expected, files, "one file per scenario and environment, nothing else (no credentials file is"
                + " committed; scenario D's come with the recordings)");
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            BindingsFile read = BindingsFile.read(file);
            assertEquals(text, read.toJsonText(), file + " is in canonical form");
            Plan plan = Plan.read(ItEnv.plan(read.scenarioId()));
            assertEquals(plan.resolveHash(), read.resolveHash(), file + " belongs to the committed plan");
            assertEquals(plan.hash(), read.planHash(), file + " was approved for the committed plan");
            assertEquals(file.getParent().getFileName().toString(), read.envId());
            assertEquals("lending", read.pack());
            assertEquals("0.4.0", read.packVersion());
            assertTrue(text.endsWith("}\n"), "ends with a newline");
            assertFalse(ScenarioB.ISO_DATE.matcher(text.replaceAll("\"by\": \"[^\"]*\"", "")).find(), file
                    + " holds no date outside an approval's label: it is the same on every day");
            assertFalse(text.contains("\"jdbc\""), "recorded as the end-to-end agent channel sees it");
        }
        BindingsFile a = BindingsFile.read(ScenarioA.committedBindings(Env.SEED_A));
        BindingsFile b = BindingsFile.read(ScenarioA.committedBindings(Env.SEED_B));
        assertNotEquals(a.env().hash(), b.env().hash(), "two environments, two fingerprints");
        assertEquals(a.env().components().get("lending.schema"), b.env().components().get("lending.schema"),
                "the seeds share the schema");
        for (Env env : Env.values()) {
            assertEquals(BindingsFile.read(ScenarioA.committedBindings(env)).env(),
                    BindingsFile.read(ScenarioB.committedBindings(env)).env(), "one environment, one fingerprint");
        }
    }

    /**
     * Every committed scenario whose plan needs bindings has its file on both seeds, and one that needs none has none;
     * scenario D's follow {@link ScenarioD#HAS_BINDINGS}, which must say what is on disk.
     */
    @Test
    void everyPlanThatNeedsBindingsHasThemOnBothSeedsScenarioDOnceRecorded() throws Exception {
        List<String> needing = new ArrayList<>();
        for (Path yaml : PlansUpToDateTest.committedScenarios()) {
            Plan plan = Plan.read(PlansUpToDateTest.planFor(yaml));
            boolean expected = plan.needsBindings() && (!plan.scenarioId().equals(ScenarioD.ID)
                    || ScenarioD.HAS_BINDINGS);
            for (Env env : Env.values()) {
                Path file = BindingsFile.locate(ScenarioA.bindingsRoot(), env.id, plan.scenarioId());
                assertEquals(expected, Files.isRegularFile(file), file.toString());
            }
            if (plan.needsBindings()) {
                needing.add(plan.scenarioId());
            }
        }
        assertEquals(List.of(ScenarioB.ID, ScenarioA.ID, ScenarioD.ID), needing, "the scenarios with query slots");
        assertEquals(ScenarioD.HAS_BINDINGS, ScenarioD.anyBindingsCommitted(), "ScenarioD.HAS_BINDINGS says whether"
                + " scenario D's bindings are committed");
    }

    /**
     * Scenario B's files (phase3-contracts.md 8.3, 10.4): the oracle {@code waiver} records the cross-check it was
     * approved under, informational ({@code check} and {@code tolerance}; no capability, as the check is unguarded),
     * and no other oracle does; and although the waiver rule depends on the desk's business date, the file holds no
     * date anywhere (no token of the form {@code dddd-dd-dd}): the rule's statement has the kind as its one parameter
     * and its verifier's reason names no date, so the file is the same whatever day it was approved on.
     */
    @Test
    void scenarioBsFilesRecordTheCrossCheckAndNoDate() throws Exception {
        for (Env env : Env.values()) {
            BindingsFile file = BindingsFile.read(ScenarioB.committedBindings(env));
            assertEquals(new Plan.CrossCheckSpec("lending.waiver.evaluate", "0.00", null),
                    file.oracles().get("waiver").crossCheck());
            assertEquals(null, file.oracles().get("needed").crossCheck());
            assertEquals(null, file.oracles().get("due").crossCheck());
            ScenarioB.Expected expected = ScenarioB.expected(env);
            assertEquals(expected.waiver(), file.oracles().get("waiver").value());
            assertEquals(expected.rule(), file.resolutions().get("rule").value());
            var rule = file.resolutions().get("rule");
            assertEquals(List.of("BORROW_N_WAIVE_M"), rule.provenance().statements().get(0).params());
            assertEquals("the desk waives " + expected.amount() + " for 3 units of " + expected.itemCode() + " under "
                    + expected.ruleCode(), rule.verified().get(0).reason());
            String text = Files.readString(ScenarioB.committedBindings(env), StandardCharsets.UTF_8);
            Matcher date = ScenarioB.ISO_DATE.matcher(text);
            if (date.find()) {
                fail(ScenarioB.committedBindings(env) + " holds a date: " + date.group());
            }
            assertFalse(text.contains("\"" + expected.assistantPassword() + "\""), "no password as a value");
        }
    }

    /**
     * Scenario D's files (phase5-contracts.md 14.2): the supervisor is a tier (b) binding, {@code LLM_SQL}, approved
     * with the label of the recordings it came from, with the model's rationale and usage; and every slot and oracle
     * holds scenario A's committed value on the same seed: the model found the supervisor the committed SQL finds.
     */
    @Test
    void scenarioDsFilesHoldTheModelsSupervisorWithScenarioAsValues() throws Exception {
        for (Env env : Env.values()) {
            BindingsFile d = BindingsFile.read(ScenarioD.committedBindings(env));
            BindingsFile a = BindingsFile.read(ScenarioA.committedBindings(env));
            var supervisor = d.resolutions().get(ScenarioD.MODEL_SLOT);
            assertEquals(ai.skillroy.testjava.scenario.resolve.Strategy.LLM_SQL, supervisor.strategy());
            assertEquals(new ai.skillroy.testjava.scenario.resolve.Approval(
                    ai.skillroy.testjava.scenario.resolve.Approval.State.APPROVED, ScriptedAnswers.APPROVED_BY),
                    supervisor.approval());
            assertEquals(ScriptedAnswers.SUPERVISOR_RATIONALE, supervisor.provenance().rationale());
            assertEquals("scripted-model", supervisor.usage().model(), "a stand-in until recorded live");
            assertEquals(0.9, supervisor.confidence());
            assertEquals(a.resolutions().keySet(), d.resolutions().keySet());
            for (String slot : a.resolutions().keySet()) {
                assertEquals(a.resolutions().get(slot).value(), d.resolutions().get(slot).value(), env.id + " " + slot);
                assertEquals(a.resolutions().get(slot).argsHash(), d.resolutions().get(slot).argsHash(), slot);
            }
            for (String oracle : a.oracles().keySet()) {
                assertEquals(a.oracles().get(oracle).value(), d.oracles().get(oracle).value(), oracle);
            }
            assertEquals(a.env(), d.env(), "one environment, one fingerprint");
        }
    }

    /**
     * The committed {@code credentials.example.yaml} documents the format the sample needs and is read by the real
     * reader (placeholders are text); its name is not one {@code .gitignore} hides, unlike {@code credentials.yaml}.
     */
    @Test
    void theExampleCredentialsFileIsValidAndHoldsPlaceholdersOnly() throws Exception {
        Path example = ItEnv.moduleDir().resolve("credentials.example.yaml");
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) StrictYaml.read(Files.readAllBytes(example)).root();
        Credentials credentials = Credentials.of(document, example.getFileName().toString());
        assertTrue(credentials.has("staff", "100") && credentials.has("staff", "200"));
        assertEquals(java.util.Set.of("lending"), credentials.jdbcSources());
        for (String password : SampleCredentials.SEED_A.values()) {
            assertFalse(Files.readString(example).contains(password), "no real sample password");
        }
        String text = Files.readString(example);
        assertTrue(text.contains("credentials of 8 or more characters"), "the advice of phase2-contracts.md 4");
        assertTrue(text.contains("four-digit staff passwords are protected by"), "and why the sample's are safe");
        assertTrue(text.contains("-Dlending.dbPassword"), "the jdbc password is the value the desk was launched with");
        assertNotEquals(Credentials.FILE_NAME, example.getFileName().toString());
        assertFalse(example.getFileName().toString().endsWith("." + Credentials.FILE_NAME));
    }
}
