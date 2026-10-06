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
 */
class BindingsUpToDateTest {

    @Test
    void everyCommittedBindingsFileIsCanonicalAndTiedToItsPlan() throws Exception {
        Path root = ScenarioA.bindingsRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        assertEquals(List.of(ScenarioB.committedBindings(Env.SEED_A), ScenarioA.committedBindings(Env.SEED_A),
                ScenarioB.committedBindings(Env.SEED_B), ScenarioA.committedBindings(Env.SEED_B)), files,
                "one file per scenario and environment, nothing else (no credentials file is committed)");
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            BindingsFile read = BindingsFile.read(file);
            assertEquals(text, read.toJsonText(), file + " is in canonical form");
            Plan plan = Plan.read(ItEnv.plan(read.scenarioId()));
            assertEquals(plan.resolveHash(), read.resolveHash(), file + " belongs to the committed plan");
            assertEquals(plan.hash(), read.planHash(), file + " was approved for the committed plan");
            assertEquals(file.getParent().getFileName().toString(), read.envId());
            assertEquals("lending", read.pack());
            assertEquals("0.3.0", read.packVersion());
            assertTrue(text.endsWith("}\n"), "ends with a newline");
            assertFalse(ScenarioB.ISO_DATE.matcher(text).find(), file + " holds no date: it is the same on every day");
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
