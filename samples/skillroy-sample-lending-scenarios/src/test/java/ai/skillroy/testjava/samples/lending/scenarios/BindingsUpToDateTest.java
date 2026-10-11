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
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.image.ImageCompare;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 2 acceptance 14.1 item 11 and Phase 3 acceptance 11.1 item 6: every committed bindings file
 * ({@code bindings/<env>/<id>.bindings.json}, scenarios A, B and C on both seeds) is in canonical form (read and
 * written again, it is byte-identical) and is tied to the committed plan (its {@code resolveHash} equals the plan's). A
 * file that fails here would fail every {@code replay} as {@code PLAN_CHANGED} or be rewritten on the next approval as
 * noise in the diff.
 *
 * <p>Scenario C ({@code return-refunds-deposit}, phase5-contracts.md 14.3), which the planner authored, has the files
 * {@code approve} wrote on both seeds after the review (tier (a) only), under the label they carry
 * ({@link ScenarioC#approvedBy}).
 *
 * <p>Scenario D ({@code supervisor-return-llm-sql}, phase5-contracts.md 14.2) has its files since the recording wave
 * of the Phase 5 build wrote them through the recorded gateway ({@link ScenarioD#HAS_BINDINGS}, which these checks
 * follow), from live recordings since the live session: the same checks apply to them, and its supervisor's approval
 * names the live re-record it came from.
 *
 * <p>Phase 7 (phase7-contracts.md 8.7, 12.4, 13.1 item 9): {@code keypad-image}, the one Phase 7 scenario with a
 * bindings file per seed (its {@code images}), is in the list since step 2b committed it; every
 * PNG under a {@code <scenarioId>.images/} directory is walked separately, and must be named by an entry of its
 * bindings file and match that entry's {@code sha256}. {@code -Dskillroy.bindings.rehash=true} rewrites the
 * {@code planHash} of every committed bindings file (the standard ones, the branch variant's, the session exports')
 * to the hash of the plan its YAML compiles to now, and (step 2b, VS) its {@code packVersion} to that plan's, nothing
 * else: VC's script for the one regeneration of plan
 * version 6 (phase7-contracts.md 8.2, 12.5), run after {@code -Dskillroy.plans.update=true}, and again after the pack's
 * version changes.
 */
class BindingsUpToDateTest {

    /** {@code -Dskillroy.bindings.rehash=true}: every committed bindings file's {@code planHash}, from its YAML. */
    @BeforeAll
    static void rehashWhenAsked() throws Exception {
        if (!Boolean.getBoolean("skillroy.bindings.rehash")) {
            return;
        }
        rehash(ScenarioA.bindingsRoot(), id -> ItEnv.scenario(id + ".yaml"), new LendingPack());
        rehash(BranchVariant.bindingsRoot(), id -> ItEnv.scenario(id + ".yaml"), BranchVariant.pack());
        Path exported = ItEnv.moduleDir().resolve("scenarios").resolve("exported");
        rehash(exported.resolve("bindings"), id -> exported.resolve(id + ".yaml"), new LendingPack());
    }

    private static void rehash(Path root, java.util.function.Function<String, Path> yamlOf, DomainPack pack)
            throws Exception {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(f -> f.getFileName().toString().endsWith(BindingsFile.SUFFIX)).sorted().forEach(files::add);
        }
        for (Path file : files) {
            BindingsFile read = BindingsFile.read(file);
            Plan compiled = PlanCompiler.compile(yamlOf.apply(read.scenarioId()), pack);
            String hash = compiled.hash();
            // Phase 7, step 2b (VS): the pack's version follows too, as approve would write it (the field is
            // information: neither the plan hash nor the resolve hash covers it)
            if (!hash.equals(read.planHash()) || !compiled.packVersion().equals(read.packVersion())) {
                BindingsFile rehashed = new BindingsFile(read.bindingsVersion(), read.scenarioId(), read.envId(),
                        read.pack(), compiled.packVersion(), hash, read.resolveHash(), read.env(), read.resolutions(),
                        read.oracles(), read.images());
                Files.writeString(file, rehashed.toJsonText(), StandardCharsets.UTF_8);
            }
        }
    }

    /** The committed files of a bindings root, the baselines under {@code *.images/} left to their own walk. */
    static List<Path> bindingsFiles(Path root) throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(f -> !f.getParent().getFileName().toString().endsWith(".images"))
                    .sorted().forEach(files::add);
        }
        return files;
    }

    @Test
    void everyCommittedBindingsFileIsCanonicalAndTiedToItsPlan() throws Exception {
        Path root = ScenarioA.bindingsRoot();
        List<Path> files = bindingsFiles(root);
        List<Path> expected = new ArrayList<>();
        for (Env env : Env.values()) {
            expected.add(ScenarioB.committedBindings(env));
            expected.add(BindingsFile.locate(root, env.id, KEYPAD_IMAGE));
            expected.add(ScenarioA.committedBindings(env));
            expected.add(ScenarioC.committedBindings(env));
            expected.add(ScenarioE.committedBindings(env));
            if (ScenarioD.HAS_BINDINGS) {
                expected.add(ScenarioD.committedBindings(env));
            }
        }
        expected.sort(null);
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
            assertEquals(ai.skillroy.testjava.samples.lending.pack.LendingPack.VERSION, read.packVersion());
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
            assertEquals(BindingsFile.read(ScenarioA.committedBindings(env)).env(),
                    BindingsFile.read(ScenarioC.committedBindings(env)).env(), "one environment, one fingerprint");
            // phase6-contracts.md 6.5 (D128): hooks never enter the fingerprint, so scenario E's one file per seed
            // serves its runs with and without hooks.
            assertEquals(BindingsFile.read(ScenarioA.committedBindings(env)).env(),
                    BindingsFile.read(ScenarioE.committedBindings(env)).env(), "one environment, one fingerprint");
        }
    }

    /** The one Phase 7 scenario with bindings files (phase7-contracts.md 12.4). */
    static final String KEYPAD_IMAGE = "keypad-image";

    /**
     * phase7-contracts.md 8.7, 13.1 item 9: every PNG under a {@code <scenarioId>.images/} directory of the bindings
     * root is named by an entry of its bindings file ({@code <label>.<renderKey>.png}) and is that entry's raster
     * ({@code sha256}); every entry has its PNG; nothing else lies there.
     */
    @Test
    void everyBaselinePngIsNamedByItsBindingsFileAndIsItsRaster() throws Exception {
        Path root = ScenarioA.bindingsRoot();
        List<Path> pngs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(f -> f.getParent().getFileName().toString().endsWith(".images"))
                    .sorted().forEach(pngs::add);
        }
        for (Path png : pngs) {
            String dir = png.getParent().getFileName().toString();
            String scenarioId = dir.substring(0, dir.length() - ".images".length());
            Path bindings = png.getParent().getParent().resolve(scenarioId + BindingsFile.SUFFIX);
            assertTrue(Files.isRegularFile(bindings), png + " lies beside no bindings file");
            BindingsFile file = BindingsFile.read(bindings);
            String name = png.getFileName().toString();
            assertTrue(name.endsWith(".png"), png + " is a PNG");
            String stem = name.substring(0, name.length() - ".png".length());
            int dot = stem.lastIndexOf('.');
            assertTrue(dot > 0, png + " is <label>.<renderKey>.png");
            BindingsFile.ImageEntry entry = file.images().getOrDefault(stem.substring(0, dot), Map.of())
                    .get(stem.substring(dot + 1));
            assertNotNull(entry, png + " is named by no entry of " + bindings.getFileName());
            assertEquals(BindingsFile.ImageEntry.fileName(scenarioId, stem.substring(0, dot), stem.substring(dot + 1)),
                    entry.file(), "the entry names its derived file");
            assertEquals(entry.sha256(), ImageCompare.rasterHash(ImageCompare.decodePng(Files.readAllBytes(png))),
                    png + " is the entry's raster");
        }
        for (Path bindings : bindingsFiles(root)) {
            BindingsFile file = BindingsFile.read(bindings);
            file.images().forEach((label, byKey) -> byKey.keySet().forEach(key -> assertTrue(Files.isRegularFile(
                    bindings.getParent().resolve(BindingsFile.ImageEntry.fileName(file.scenarioId(), label, key))),
                    bindings + ": the baseline " + label + " for " + key + " has its PNG")));
        }
        assertFalse(pngs.isEmpty(), "keypad-image's baselines are committed (step 2b)");
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
        List<String> expectedNeeding = new ArrayList<>(List.of(ScenarioB.ID, KEYPAD_IMAGE, ScenarioA.ID, ScenarioC.ID,
                ScenarioE.ID, ScenarioD.ID));
        assertEquals(expectedNeeding, needing, "the scenarios with query slots, and keypad-image with its images"
                + " (phase7-contracts.md 8.2: needsBindings() over images)");
        assertEquals(ScenarioD.HAS_BINDINGS, ScenarioD.anyBindingsCommitted(), "ScenarioD.HAS_BINDINGS says whether"
                + " scenario D's bindings are committed");
    }

    /**
     * Scenario C's files (phase5-contracts.md 14.3): on each seed the values the review names
     * ({@link ScenarioC#expected}), found by the committed SQL (tier (a): confidence 1, no model usage), every
     * verifier passed, every slot {@code APPROVED} under the one label the file carries ({@link ScenarioC#approvedBy});
     * the assistant joined to its staff credential and resolved with the item's deposit as the bound of the return it
     * may take; the refund, the deposit negated. Both seeds' files share the plan hash and the resolve hash; neither
     * holds a date outside an approval's label, nor a password as a value.
     */
    @Test
    void scenarioCsFilesHoldTheReviewedValuesApprovedOnTierA() throws Exception {
        Set<String> planHashes = new HashSet<>();
        Set<String> resolveHashes = new HashSet<>();
        for (Env env : Env.values()) {
            Path committed = ScenarioC.committedBindings(env);
            BindingsFile file = BindingsFile.read(committed);
            ScenarioC.Expected expected = ScenarioC.expected(env);
            String label = ScenarioC.approvedBy(env);
            assertEquals(ScenarioC.QUERY_SLOTS, List.copyOf(file.resolutions().keySet()), env.id);
            for (Resolution r : file.resolutions().values()) {
                assertEquals(Strategy.CANONICAL_SQL, r.strategy(), env.id + " " + r.slot() + ": tier (a) only");
                assertEquals(new Approval(Approval.State.APPROVED, label), r.approval(), env.id + " " + r.slot());
                assertEquals(1.0, r.confidence(), r.slot());
                assertNull(r.usage(), r.slot() + ": no model behind it");
                assertFalse(r.verified().isEmpty(), r.slot());
                assertTrue(r.verified().stream().allMatch(VerifierResult::passed), r.slot() + ": " + r.verified());
            }
            Resolution item = file.resolutions().get("item");
            assertEquals(expected.item(), item.value(), env.id);
            assertNull(item.credential());
            assertEquals(List.of("lending.deposit"), item.verified().stream().map(VerifierResult::verifierId)
                    .toList());
            Resolution assistant = file.resolutions().get("assistant");
            assertEquals(expected.assistant(), assistant.value(), env.id);
            assertEquals(new CredentialRef("staff", expected.assistantCode()), assistant.credential());
            assertEquals(Map.of("gte", expected.deposit(), "type", "RETURN"), ((Map<?, ?>) assistant.args())
                    .get("limit"), "the bound of the return the assistant may take is the item's deposit");
            assertEquals(List.of("lending.access.check"), assistant.verified().stream()
                    .map(VerifierResult::verifierId).toList());
            assertEquals(Set.of("refund"), file.oracles().keySet());
            assertEquals("Money.negate", file.oracles().get("refund").fn());
            assertEquals(expected.refund(), file.oracles().get("refund").value(), env.id);
            String text = Files.readString(committed, StandardCharsets.UTF_8);
            Matcher date = ScenarioB.ISO_DATE.matcher(text.replaceAll("\"by\": \"[^\"]*\"", ""));
            if (date.find()) {
                fail(committed + " holds a date: " + date.group());
            }
            assertEquals(List.of(), SecretScan.leaks(committed, Set.copyOf(SampleCredentials.staff(env).values())),
                    "no password as a value");
            planHashes.add(file.planHash());
            resolveHashes.add(file.resolveHash());
        }
        assertEquals(1, planHashes.size(), "both seeds' files were approved for the one plan: " + planHashes);
        assertEquals(1, resolveHashes.size(), "and are tied to its resolve section: " + resolveHashes);
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
     * Scenario D's files (phase5-contracts.md 14.2): the supervisor is a tier (b) binding that holds what any live
     * answer holds ({@link ScenarioD#assertLiveAnswer}, as the record route checks it before it commits): approved with
     * the label of the live re-record it came from ({@code recorded <date>}, read from the file:
     * {@link ScenarioD#approvedBy}), the model's rationale, the usage of a model of the Sonnet line, a confidence at or
     * above the settings' threshold and one guarded statement; and every slot and oracle holds scenario A's committed
     * value on the same seed: the model found the supervisor the committed SQL finds.
     */
    @Test
    void scenarioDsFilesHoldTheModelsSupervisorWithScenarioAsValues() throws Exception {
        for (Env env : Env.values()) {
            BindingsFile d = BindingsFile.read(ScenarioD.committedBindings(env));
            BindingsFile a = BindingsFile.read(ScenarioA.committedBindings(env));
            ScenarioD.assertLiveAnswer(env, d.resolutions().get(ScenarioD.MODEL_SLOT), ScenarioD.approvedBy(env));
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
     * Scenario E's files (phase6-contracts.md 6.3): the query slots of its {@code resolve}, every verifier passed, the
     * assistant's limit at least the item's deposit (so the oracle {@code auth} is {@code NONE}: the assistant takes
     * the return alone), the return line the deposit negated; both seeds' files share the plan and resolve hashes.
     */
    @Test
    void scenarioEsFilesBindAnAssistantWhoMayTakeTheReturnAlone() throws Exception {
        Set<String> planHashes = new HashSet<>();
        for (Env env : Env.values()) {
            BindingsFile file = BindingsFile.read(ScenarioE.committedBindings(env));
            assertEquals(ScenarioE.QUERY_SLOTS, List.copyOf(file.resolutions().keySet()), env.id);
            for (Resolution r : file.resolutions().values()) {
                assertTrue(r.verified().stream().allMatch(VerifierResult::passed), r.slot() + ": " + r.verified());
                assertNull(r.usage(), r.slot() + ": no model behind it");
            }
            assertEquals("NONE", file.oracles().get("auth").value(), env.id);
            assertEquals(new CredentialRef("staff", String.valueOf(((Map<?, ?>) file.resolutions().get("assistant")
                    .value()).get("code"))), file.resolutions().get("assistant").credential());
            planHashes.add(file.planHash());
        }
        assertEquals(1, planHashes.size(), String.valueOf(planHashes));
    }

    /**
     * The branch variant's files (phase6-contracts.md 11.4): {@code variants/branch/bindings/branch-a/} holds scenarios
     * A, B and E, canonical, tied to the variant's committed plans, approved with the label {@code approve}, the
     * overlaid pack's composite version; the environment is the branch edition's (its {@code lending.schema} checksum
     * differs from seed A's, the seed and the site do not), so bindings never cross editions.
     */
    @Test
    void theBranchVariantsFilesAreCanonicalAndTiedToItsPlans() throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(BranchVariant.bindingsRoot())) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        List<Path> expected = BranchVariant.SCENARIOS.stream().map(BranchVariant::committedBindings).sorted()
                .toList();
        assertEquals(expected, files);
        BindingsFile seedA = BindingsFile.read(ScenarioA.committedBindings(Env.SEED_A));
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            BindingsFile read = BindingsFile.read(file);
            assertEquals(text, read.toJsonText(), file + " is in canonical form");
            Plan plan = Plan.read(BranchVariant.plan(read.scenarioId()));
            assertEquals(plan.resolveHash(), read.resolveHash(), file.toString());
            assertEquals(plan.hash(), read.planHash(), file.toString());
            assertEquals(BranchVariant.ENV, read.envId());
            assertEquals(BranchVariant.PACK_VERSION, read.packVersion());
            for (Resolution r : read.resolutions().values()) {
                assertEquals("approve", r.approval().by(), file + " " + r.slot());
            }
            assertNotEquals(seedA.env().hash(), read.env().hash(), "another edition, another fingerprint");
            assertNotEquals(seedA.env().components().get("lending.schema"), read.env().components().get(
                    "lending.schema"), "the branch edition's schema");
            assertEquals(seedA.env().components().get("lending.seed"), read.env().components().get("lending.seed"));
            assertFalse(text.contains("\"jdbc\""), "recorded as the end-to-end agent channel sees it");
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
