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

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.JunitXmlReporter;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Phase 2 and Phase 3 end to end (phase2-contracts.md 14.2, phase3-contracts.md 11.2): the checks of
 * {@code ResolutionChecks} against one sample lending desk launched on {@link #env()}'s seed with the agent loaded in
 * {@link #mode()}, through the real {@code PlanExecutor}, {@code Binder} and {@code AgentClient} over HTTP, with the
 * committed YAML, plans and bindings of scenarios A and B. The target is launched without any scenario-specific
 * property: which seed it runs is the environment's business, and the same committed plans run on both.
 *
 * <ol>
 *   <li>{@link #liveResolvesTheTableOf13_4AndLeavesTheDeskUntouched()}: P2 item 1;</li>
 *   <li>{@link #approveIsScriptableAndWritesTheCommittedBytes()}: P2 item 2;</li>
 *   <li>{@link #replayOfTheCommittedPlanPassesWithoutSleepsResolversOrModelCalls()}: P2 items 3, 4, 5 and 6;</li>
 *   <li>{@link #aWrongBindingIsRebindNeededAndNothingIsSent()}: P2 item 7;</li>
 *   <li>{@link #aFailureBeforeTheBindSendsNothingAfterTheCapabilityCheck()}: P2 item 7, the failures before the
 *       bind (Q11);</li>
 *   <li>{@link #aForeignEnvironmentIsRebindNeeded()}: P2 item 8;</li>
 *   <li>{@link #aQueryNothingSatisfiesIsResolutionFailed()}: P2 item 9;</li>
 *   <li>{@link #scenarioBLiveResolvesTheTableOf10_4AndTheCrossCheckAgrees()}: P3 item 1;</li>
 *   <li>{@link #scenarioBApproveWritesTheCommittedBytes()}: P3 item 2;</li>
 *   <li>{@link #scenarioBReplayPassesWithoutSleepsAndTheWaiverIsReal()}: P3 items 3, 4 and 5;</li>
 *   <li>{@link #aWrongOracleIsOracleSuspectAndNothingIsSent()}: P3 item 6;</li>
 *   <li>{@link #aGuardedCrossCheckThatCannotRunMakesTheRunDegraded()}: the guarded form of P3 10.4;</li>
 *   <li>{@link #scenarioBStillRunsAfterTheWrongOracle()}: P3 item 7;</li>
 *   <li>{@link #noPasswordIsWrittenAsAValue()}: P2 item 10 and P3 item 8, over the outputs of every check
 *       above;</li>
 *   <li>{@link #theNextScenarioStillRuns()}: P2 item 11.</li>
 * </ol>
 *
 * <p>Needs a display (skipped when headless); one process per subclass, shared by its checks in order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class ResolutionChecks {

    static final CallOptions CALL = CallOptions.defaults().withTimeoutMs(60_000L);
    /** Topics the desk publishes when anything happens at it. */
    static final List<String> DESK_TOPICS = List.of("input.", "prompt.", "loan.", "slip.", "state.");

    LendingTarget target;
    String java;
    /** The ledger shared by this target's runs (item 5). */
    final CostLedger ledger = new CostLedger();
    /** Output directories and bindings files of the checks before {@link #noPasswordIsWrittenAsAValue()}. */
    final List<Path> outputs = new ArrayList<>();
    final List<RunResult> results = new ArrayList<>();
    /** The output directory of check 3's {@code replay} run, whose calls item 10 looks at (A34). */
    Path replayDir;
    /** The output directory of check 10's {@code replay} run of scenario B, whose calls P3 item 8 looks at (A34). */
    Path replayDirB;

    abstract LendingTarget.Mode mode();

    abstract Env env();

    String slug() {
        return mode().slug + "-" + env().id;
    }

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        target = LendingTarget.launch(mode(), env().seed, false);
        java = String.valueOf(LoadModeChecks.value(client().call("jvm.info", Map.of(), CALL)).get("javaVersion"));
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " env=" + env().id + " resolution target ready ms="
                + target.readyMs);
    }

    @AfterAll
    void close() {
        if (target != null) {
            target.close();
        }
    }

    AgentClient client() {
        return target.client;
    }

    Path runDir(String what) {
        Path dir = ItEnv.runDir("resolution", slug() + "/" + what);
        outputs.add(dir);
        return dir;
    }

    BindOptions bind(BindMode mode) {
        return Runs.bind(env()).withMode(mode).withLedger(ledger);
    }

    /** Item 1: {@code live} resolves the slots and oracles of 13.4 over the agent channel and touches nothing. */
    @Test
    @Order(1)
    void liveResolvesTheTableOf13_4AndLeavesTheDeskUntouched() throws Exception {
        assertSignedOffAndQuiet(-1);
        long seq = lastSeq();
        Path root = ItEnv.runDir("resolution", slug() + "/live-root");
        List<String> types = new ArrayList<>();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioA.plan()), Runs.PACK, client(),
                bind(BindMode.LIVE).withBindingsRoot(root), (type, fields) -> types.add(type));
        ScenarioA.Expected expected = ScenarioA.expected(env());
        Map<String, Object> values = outcome.bindings().values();
        assertEquals(expected.systemMax(), values.get("systemMax"));
        assertEquals(expected.item(), values.get("item"));
        assertEquals(ScenarioA.KEYS, values.get("keys"));
        assertEquals("SUPERVISOR_PROMPT", outcome.bindings().oracle("auth"));
        assertEquals(expected.returnLine(), outcome.bindings().oracle("returnLine"));
        BindingsFile committed = BindingsFile.read(ScenarioA.committedBindings(env()));
        for (String slot : ScenarioA.QUERY_SLOTS) {
            Resolution r = outcome.bindings().resolutions().get(slot);
            assertTrue(r.verified().stream().allMatch(VerifierResult::passed), slot + ": " + r.verified());
            assertEquals(committed.resolutions().get(slot).value(), r.value(), slot);
            List<String> sql = r.provenance().statements().stream().map(Provenance.Statement::sql).toList();
            assertEquals(committed.resolutions().get(slot).provenance().statements().stream()
                    .map(Provenance.Statement::sql).toList(), sql, "the committed SQL texts: " + slot);
            assertTrue(r.provenance().statements().stream().allMatch(s -> s.channel().equals("agent")), slot);
        }
        assertEquals(5L, types.stream().filter("resolver"::equals).count());
        assertNull(outcome.written());
        assertFalse(Files.exists(root), "nothing was written to the bindings directory");
        assertSignedOffAndQuiet(seq);
    }

    /** Item 2: {@code approve} with a scripted approver writes the committed file's bytes into an empty root. */
    @Test
    @Order(2)
    void approveIsScriptableAndWritesTheCommittedBytes() throws Exception {
        Path root = ItEnv.runDir("resolution", slug() + "/approve-root");
        deleteTree(root);
        long seq = lastSeq();
        List<Approver.ApprovalRequest> shown = new ArrayList<>();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioA.plan()), Runs.PACK, client(),
                bind(BindMode.APPROVE).withBindingsRoot(root).withApprover(request -> {
                    shown.add(request);
                    for (Resolution r : request.resolutions()) {
                        assertNotNull(r.value(), r.slot());
                        assertFalse(r.verified().isEmpty(), r.slot());
                        assertTrue(r.verified().stream().allMatch(v -> !v.evidence().isEmpty()), r.slot());
                        assertTrue(r.slot().equals("keys") || !r.provenance().statements().isEmpty(), r.slot());
                    }
                    return new Approver.Decision(true, null, "scripted");
                }), BindListener.NONE);
        assertEquals(1, shown.size());
        assertEquals(ScenarioA.QUERY_SLOTS, shown.get(0).resolutions().stream().map(Resolution::slot).toList());
        assertNotNull(outcome.written());
        outputs.add(outcome.written());
        assertEquals(Files.readString(ScenarioA.committedBindings(env()), StandardCharsets.UTF_8),
                Files.readString(outcome.written(), StandardCharsets.UTF_8), "byte-identical to the committed file");
        assertSignedOffAndQuiet(seq);
    }

    /**
     * Items 3-6: the committed plan, the same file for both seeds, runs in {@code replay} on this environment and
     * passes; the executor never paused, every expectation and every {@code db} check held at its first attempt (the
     * {@code db} checks read the rows the application wrote, over the agent channel), no resolver ran, every slot's
     * verifier ran once, no model call was made, and the captured loan id is the desk's.
     */
    @Test
    @Order(3)
    void replayOfTheCommittedPlanPassesWithoutSleepsResolversOrModelCalls() throws Exception {
        Plan plan = Plan.read(ScenarioA.plan());
        assertEquals("none", plan.inference());
        Runs.Outcome outcome = Runs.run(client(), plan, runDir("replay"), bind(BindMode.REPLAY));
        replayDir = outcome.dir();
        results.add(outcome.result());
        Runs.assertPassedWithoutSleeping(outcome);
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " env=" + env().id + " scenario=" + ScenarioA.ID
                + " ms=" + outcome.result().duration().toMillis());
        List<Map<String, Object>> log = Runs.runLog(outcome.dir());
        assertFalse(log.stream().anyMatch(r -> "resolver".equals(r.get("type"))), "no resolver ran in replay");
        List<Map<String, Object>> resolves = log.stream().filter(r -> "resolve".equals(r.get("type"))).toList();
        assertEquals(ScenarioA.QUERY_SLOTS, resolves.stream().map(r -> r.get("slot")).toList());
        assertTrue(resolves.stream().allMatch(r -> "REPLAY".equals(r.get("strategy"))), String.valueOf(resolves));
        List<Map<String, Object>> verifiers = log.stream().filter(r -> "verifier".equals(r.get("type"))).toList();
        assertEquals(ScenarioA.QUERY_SLOTS, verifiers.stream().map(r -> r.get("slot")).toList(),
                "every slot's verifier ran once");
        List<Map<String, Object>> db = log.stream().filter(r -> "db".equals(r.get("type"))).toList();
        assertEquals(List.of("loan", "loan_line", "loan_authorisation"), db.stream().map(r -> r.get("table"))
                .toList(), "each db check held at its first attempt");
        assertTrue(db.stream().allMatch(r -> Boolean.TRUE.equals(r.get("ok")) && Long.valueOf(1).equals(
                r.get("rowCount")) && "agent".equals(r.get("channel"))), String.valueOf(db));
        // Item 5: zero model calls, asserted.
        assertEquals(List.of(), outcome.result().modelCalls());
        Path ledgerFile = outcome.dir().resolve("cost-ledger.jsonl");
        assertTrue(Files.isRegularFile(ledgerFile));
        assertEquals(0L, Files.size(ledgerFile));
        assertTrue(ledger.isEmpty());
        // Item 6: the captured loan is the desk's newest loan and slip.
        Map<String, Object> bindings = Json.parseObject(Files.readString(outcome.dir().resolve("bindings.json")));
        Map<?, ?> captured = (Map<?, ?>) ((Map<?, ?>) bindings.get("captured")).get("loan");
        Object loanId = captured.get("id");
        assertEquals(LoadModeChecks.value(client().call("lending.loan", Map.of(), CALL)).get("id"), loanId);
        Map<String, Object> slips = LoadModeChecks.value(client().call("lending.slip", Map.of("last", 1L), CALL));
        assertEquals(loanId, ((Map<?, ?>) ((List<?>) slips.get("slips")).get(0)).get("loanId"));
        assertEquals(ScenarioA.expected(env()).returnLine(), captured.get("total"));
        assertSignedOffAndQuiet(-1);
    }

    /** Item 7: the generated {@code wrong-assistant} bindings are REBIND_NEEDED; nothing is sent to the desk. */
    @Test
    @Order(4)
    void aWrongBindingIsRebindNeededAndNothingIsSent() throws Exception {
        Path root = ItEnv.runDir("resolution", slug() + "/wrong-assistant-root");
        deleteTree(root);
        outputs.add(ScenarioA.writeWrongAssistant(env(), root));
        long seq = lastSeq();
        Runs.Outcome outcome = Runs.run(client(), Plan.read(ScenarioA.plan()), runDir("wrong-assistant"),
                bind(BindMode.REPLAY).withBindingsRoot(root));
        results.add(outcome.result());
        RunResult result = outcome.result();
        assertEquals(Status.ERROR, result.status(), outcome.describe());
        assertEquals(FailureKind.REBIND_NEEDED, result.failureKind(), outcome.describe());
        assertEquals("VERIFIER", result.detail().get("reason"));
        assertEquals("assistant", result.detail().get("slot"));
        Map<?, ?> verifier = (Map<?, ?>) result.detail().get("verifier");
        assertEquals("lending.access.check", verifier.get("id"));
        String evidence = Json.write(verifier.get("evidence"));
        assertTrue(evidence.contains("lending.access.check") && evidence.contains("\"limit\""),
                "the application's answer is in the evidence: " + evidence);
        assertTrue(result.steps().isEmpty(), "no step ran");
        assertNothingSent(outcome.dir());
        assertSignedOffAndQuiet(seq);
    }

    /**
     * Item 7, the failures before the bind (Q11): scenario A with a {@code requires} entry no target has ends
     * PRECONDITION_FAILED, and run with a pack whose pre-flight is scripted to fail it ends ENVIRONMENT. In both,
     * nothing is sent to the target after the capability check: the executor's only request is the capability
     * matrix, {@code run.jsonl} ends with the failed check (no bind, call, cleanup or recover record), and the desk is
     * untouched (signed off, no prompt, and no event since the check began).
     */
    @Test
    @Order(5)
    void aFailureBeforeTheBindSendsNothingAfterTheCapabilityCheck() throws Exception {
        long seq = lastSeq();
        String yaml = Files.readString(ScenarioA.yaml(), StandardCharsets.UTF_8);
        String missing = "lending.teleport";
        String withMissing = yaml.replace("lending.slip]", "lending.slip, " + missing + "]");
        assertFalse(yaml.equals(withMissing), "scenario A's requires list ends with lending.slip");
        Plan unmet = PlanCompiler.compile(withMissing.getBytes(StandardCharsets.UTF_8), ScenarioA.YAML, Runs.PACK);
        assertTrue(unmet.requires().contains(missing), String.valueOf(unmet.requires()));
        Recording agent = new Recording(client());
        Runs.Outcome precondition = Runs.run(Runs.PACK, agent, unmet, runDir("unmet-requires"),
                bind(BindMode.REPLAY));
        results.add(precondition.result());
        assertEquals(FailureKind.PRECONDITION_FAILED, precondition.result().failureKind(), precondition.describe());
        assertTrue(precondition.result().message().contains(missing), precondition.describe());
        assertTrue(precondition.result().steps().isEmpty(), "no step ran");
        assertEquals(List.of("capabilities"), agent.sent, "nothing was sent after the capability check");
        assertEquals(List.of("run-start", "requires", "ledger", "run-end"), types(precondition.dir()));
        assertNothingSent(precondition.dir());

        Recording again = new Recording(client());
        Runs.Outcome preflight = Runs.run(new FailingPreflight(Runs.PACK), again, Plan.read(ScenarioA.plan()),
                runDir("failed-preflight"), bind(BindMode.REPLAY));
        results.add(preflight.result());
        assertEquals(FailureKind.ENVIRONMENT, preflight.result().failureKind(), preflight.describe());
        assertEquals("pre-flight check " + FailingPreflight.ID + ": " + FailingPreflight.REASON,
                preflight.result().message(), preflight.describe());
        assertTrue(preflight.result().steps().isEmpty(), "no step ran");
        assertEquals(List.of("capabilities"), again.sent, "nothing was sent after the capability check");
        assertEquals(List.of("run-start", "requires", "preflight", "ledger", "run-end"), types(preflight.dir()));
        assertNothingSent(preflight.dir());
        assertSignedOffAndQuiet(seq);
    }

    /**
     * Item 8: the other seed's environment id (and so its committed bindings) on this target is REBIND_NEEDED,
     * reason FINGERPRINT, naming the seed and the site, and no verifier was called; an environment without a
     * bindings file is NO_BINDINGS.
     */
    @Test
    @Order(6)
    void aForeignEnvironmentIsRebindNeeded() throws Exception {
        Env other = env() == Env.SEED_A ? Env.SEED_B : Env.SEED_A;
        long seq = lastSeq();
        Runs.Outcome foreign = Runs.run(client(), Plan.read(ScenarioA.plan()), runDir("foreign-env"),
                Runs.bind(other).withLedger(ledger));
        results.add(foreign.result());
        assertEquals(FailureKind.REBIND_NEEDED, foreign.result().failureKind(), foreign.describe());
        assertEquals("FINGERPRINT", foreign.result().detail().get("reason"), foreign.describe());
        String components = Json.write(foreign.result().detail().get("components"));
        assertTrue(components.contains("lending.seed") && components.contains("lending.site"), components);
        assertFalse(components.contains("lending.schema"), "the seeds share the schema: " + components);
        List<Map<String, Object>> log = Runs.runLog(foreign.dir());
        assertFalse(log.stream().anyMatch(r -> "verifier".equals(r.get("type"))), "no verifier was called");
        assertNothingSent(foreign.dir());

        Runs.Outcome none = Runs.run(client(), Plan.read(ScenarioA.plan()), runDir("no-bindings"),
                bind(BindMode.REPLAY).withEnvId("seed-c"));
        results.add(none.result());
        assertEquals(FailureKind.REBIND_NEEDED, none.result().failureKind(), none.describe());
        assertEquals("NO_BINDINGS", none.result().detail().get("reason"), none.describe());
        assertNothingSent(none.dir());
        assertSignedOffAndQuiet(seq);
    }

    /** Item 9: queries nothing satisfies, in {@code live}: NO_CANDIDATE and GUARD; no step ran in either. */
    @Test
    @Order(7)
    void aQueryNothingSatisfiesIsResolutionFailed() throws Exception {
        long seq = lastSeq();
        Path root = ItEnv.runDir("resolution", slug() + "/negative-root");
        Runs.Outcome noItem = Runs.run(client(), Runs.negative("no-item-in-range.yaml"), runDir("no-item-in-range"),
                bind(BindMode.LIVE).withBindingsRoot(root));
        results.add(noItem.result());
        assertEquals(FailureKind.RESOLUTION_FAILED, noItem.result().failureKind(), noItem.describe());
        assertEquals("NO_CANDIDATE", noItem.result().detail().get("reason"));
        assertEquals("item", noItem.result().detail().get("slot"));
        assertTrue(noItem.result().steps().isEmpty());
        assertNothingSent(noItem.dir());

        Runs.Outcome guard = Runs.run(client(), Runs.negative("return-within-limit.yaml"),
                runDir("return-within-limit"), bind(BindMode.LIVE).withBindingsRoot(root));
        results.add(guard.result());
        assertEquals(FailureKind.RESOLUTION_FAILED, guard.result().failureKind(), guard.describe());
        assertEquals("GUARD", guard.result().detail().get("reason"));
        assertEquals("NONE", ((Map<?, ?>) guard.result().detail().get("guard")).get("actual"));
        assertTrue(guard.result().steps().isEmpty());
        assertNothingSent(guard.dir());
        assertFalse(Files.exists(root), "live wrote nothing");
        assertSignedOffAndQuiet(seq);
    }

    /**
     * Item 10 (and P3 item 8): no file under the output directories of the checks before this one (scenario A's and
     * scenario B's), no written bindings file, neither scenario's plan, no {@code RunResult} and no JUnit XML
     * rendered from them holds the password of a staff member those scenarios signed on or authorised with as a
     * value (a JSON string value, an XML attribute value or text token, a whitespace-delimited token). With A34 the
     * same holds for the agent's own log after those runs (every line, split on whitespace and quotes), and in the
     * {@code replay} run of check 3 every {@code lending.input} call that entered a password (recorded as
     * {@code <redacted>}: the assistant's sign-on and the supervisor's authorisation) carried
     * {@code _meta.secretParams: ["value"]}, and no other {@code lending.input} call did; so in scenario B's
     * {@code replay} run of check 10 (its assistant's sign-on only). Without the credentials, a
     * {@code replay} run is ENVIRONMENT naming {@code staff/<code>} and a {@code live} run is ALL_REJECTED with
     * "no credential".
     */
    @Test
    @Order(14)
    void noPasswordIsWrittenAsAValue() throws Exception {
        ScenarioA.Expected expected = ScenarioA.expected(env());
        Set<String> passwords = Set.copyOf(List.of(expected.assistantPassword(), expected.supervisorPassword(),
                ScenarioB.expected(env()).assistantPassword()));
        Path xml = ItEnv.runDir("resolution", slug() + "/junit-report.xml");
        new JunitXmlReporter().write(xml, "lending-resolution-" + slug(), results, null);
        List<Path> files = new ArrayList<>(List.of(xml, ScenarioA.plan(), ScenarioB.plan()));
        for (Path output : outputs) {
            if (Files.isDirectory(output)) {
                try (Stream<Path> walk = Files.walk(output)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
            } else if (Files.isRegularFile(output)) {
                files.add(output);
            }
        }
        List<String> leaks = new ArrayList<>();
        Set<String> kinds = new TreeSet<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(".png")) {
                continue;
            }
            kinds.add(name.endsWith(".bindings.json") ? "bindings file" : name.endsWith(".plan.json") ? "plan"
                    : name.endsWith(".xml") ? "junit.xml" : name);
            for (String hit : leaks(file, passwords)) {
                leaks.add(file + ": " + hit);
            }
        }
        for (RunResult result : results) {
            List<String> strings = new ArrayList<>();
            LoadModeChecks.collectStrings(Json.parse(Json.write(Map.of("message", String.valueOf(result.message()),
                    "detail", result.detail(), "steps", String.valueOf(result.steps())))), strings);
            for (String hit : tokenLeaks(strings, passwords)) {
                leaks.add(result.scenarioId() + " RunResult: " + hit);
            }
        }
        List<?> lines = (List<?>) LoadModeChecks.value(client().call("agent.log", Map.of("n", 2000L), CALL))
                .get("lines");
        assertFalse(lines.isEmpty(), "the agent's log has lines");
        for (Object line : lines) {
            for (String hit : tokenLeaks(List.of(String.valueOf(line).split("[\\s\"']+")), passwords)) {
                leaks.add("agent.log: " + hit);
            }
        }
        assertEquals(List.of(), leaks, "a password was written as a value");
        assertTrue(kinds.containsAll(List.of("run.jsonl", "bindings.json", "cost-ledger.jsonl", "bindings file",
                "plan", "junit.xml")), "every kind of output was looked at: " + kinds);

        assertNotNull(replayDir, "check 3's replay ran");
        assertPasswordInputsAnnounced(replayDir, Plan.read(ScenarioA.plan()), List.of("signOn", "authorise"));
        assertNotNull(replayDirB, "check 10's replay ran");
        assertPasswordInputsAnnounced(replayDirB, Plan.read(ScenarioB.plan()), List.of("signOn"));

        Runs.Outcome replay = Runs.run(client(), Plan.read(ScenarioA.plan()), runDir("replay-no-credentials"),
                bind(BindMode.REPLAY).withCredentials(Credentials.empty()));
        assertEquals(FailureKind.ENVIRONMENT, replay.result().failureKind(), replay.describe());
        assertTrue(replay.result().message().contains("staff/" + expected.assistantCode()),
                replay.result().message());
        assertNothingSent(replay.dir());
        Runs.Outcome live = Runs.run(client(), Plan.read(ScenarioA.plan()), runDir("live-no-credentials"),
                bind(BindMode.LIVE).withCredentials(Credentials.empty())
                        .withBindingsRoot(ItEnv.runDir("resolution", slug() + "/none-root")));
        assertEquals(FailureKind.RESOLUTION_FAILED, live.result().failureKind(), live.describe());
        assertEquals("ALL_REJECTED", live.result().detail().get("reason"), live.describe());
        assertTrue(Json.write(live.result().detail().get("rejected")).contains("no credential staff/"),
                live.describe());
        assertNothingSent(live.dir());
    }

    /** Item 11: after the failures above, a scenario still passes without a restart. */
    @Test
    @Order(15)
    void theNextScenarioStillRuns() throws Exception {
        Runs.Outcome next = env() == Env.SEED_A
                ? Runs.run(client(), Runs.committed("basic-loan"), runDir("basic-loan-after"), bind(BindMode.REPLAY))
                : Runs.run(client(), Plan.read(ScenarioA.plan()), runDir("replay-again"), bind(BindMode.REPLAY));
        Runs.assertPassedWithoutSleeping(next);
        assertTrue(ledger.isEmpty(), "the ledger shared by this target's runs stayed empty");
        assertSignedOffAndQuiet(-1);
    }

    // ------------------------------------------------------------------ scenario B (phase3-contracts.md 11.2)

    /** The desk's business date, as {@code app.state} reports it: the date its waiver rules are evaluated on. */
    String deskToday() throws Exception {
        return String.valueOf(LoadModeChecks.lendingState(client()).get("today"));
    }

    /**
     * P3 item 1: {@code live} resolves scenario B to the values of 10.4 for this seed over the agent channel. Every
     * verifier passed, the cross-check agreed and ran once (its {@code actual.date} is the desk's business date, read
     * before and after the bind: either is accepted, so that a bind across the target's midnight passes), the
     * recorded statements are the committed SQL with channel {@code agent}, the provenance of {@code rule} holds the
     * {@code app.state} call and its statement the kind as its one parameter (no date); nothing is written and the
     * desk is untouched.
     */
    @Test
    @Order(8)
    void scenarioBLiveResolvesTheTableOf10_4AndTheCrossCheckAgrees() throws Exception {
        assertSignedOffAndQuiet(-1);
        long seq = lastSeq();
        String before = deskToday();
        Path root = ItEnv.runDir("resolution", slug() + "/b-live-root");
        deleteTree(root);
        List<String> types = new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioB.plan()), Runs.PACK, client(),
                bind(BindMode.LIVE).withBindingsRoot(root), (type, fields) -> {
                    types.add(type);
                    records.add(fields);
                });
        String after = deskToday();
        ScenarioB.Expected expected = ScenarioB.expected(env());
        Map<String, Object> values = outcome.bindings().values();
        assertEquals(expected.rule(), values.get("rule"));
        assertEquals(expected.item(), values.get("item"));
        assertEquals(ScenarioB.KEYS, values.get("keys"));
        assertEquals(expected.assistantCode(), ((Map<?, ?>) values.get("assistant")).get("code"));
        assertEquals(expected.needed(), outcome.bindings().oracle("needed"));
        assertEquals(expected.waiver(), outcome.bindings().oracle("waiver"));
        assertEquals(expected.due(), outcome.bindings().oracle("due"));
        BindingsFile committed = BindingsFile.read(ScenarioB.committedBindings(env()));
        for (String slot : ScenarioB.QUERY_SLOTS) {
            Resolution r = outcome.bindings().resolutions().get(slot);
            assertTrue(r.verified().stream().allMatch(VerifierResult::passed), slot + ": " + r.verified());
            assertEquals(committed.resolutions().get(slot).value(), r.value(), slot);
            List<String> sql = r.provenance().statements().stream().map(Provenance.Statement::sql).toList();
            assertEquals(committed.resolutions().get(slot).provenance().statements().stream()
                    .map(Provenance.Statement::sql).toList(), sql, "the committed SQL texts: " + slot);
            assertTrue(r.provenance().statements().stream().allMatch(s -> s.channel().equals("agent")), slot);
        }
        Resolution rule = outcome.bindings().resolutions().get("rule");
        assertEquals(List.of(new Provenance.Call("app.state", Map.of())), rule.provenance().calls(),
                "the business date was read from the desk");
        assertEquals(List.of("BORROW_N_WAIVE_M"), rule.provenance().statements().get(0).params(),
                "the window is applied in Java: no date is recorded");
        assertEquals(1L, types.stream().filter("crosscheck"::equals).count(), "one cross-check, run once");
        Map<String, Object> check = records.get(types.indexOf("crosscheck"));
        assertEquals(Boolean.TRUE, check.get("agreed"), String.valueOf(check));
        Object date = ((Map<?, ?>) check.get("actual")).get("date");
        assertTrue(before.equals(date) || after.equals(date), "the desk's business date: " + date + " (" + before
                + " before the bind, " + after + " after it)");
        assertEquals(expected.amount(), ((Map<?, ?>) check.get("actual")).get("amount"));
        assertEquals((long) ScenarioB.QUERY_SLOTS.size(), types.stream().filter("resolver"::equals).count());
        assertNull(outcome.written());
        assertFalse(Files.exists(root), "nothing was written to the bindings directory");
        assertSignedOffAndQuiet(seq);
    }

    /**
     * P3 item 2: a scripted {@code approve} of scenario B writes the committed file's bytes into an empty root (the
     * file holds no date, so the desk's business date makes no difference); the approval request shows the
     * cross-check, agreed.
     */
    @Test
    @Order(9)
    void scenarioBApproveWritesTheCommittedBytes() throws Exception {
        Path root = ItEnv.runDir("resolution", slug() + "/b-approve-root");
        deleteTree(root);
        long seq = lastSeq();
        List<Approver.ApprovalRequest> shown = new ArrayList<>();
        BindOutcome outcome = Binder.bind(Plan.read(ScenarioB.plan()), Runs.PACK, client(),
                bind(BindMode.APPROVE).withBindingsRoot(root).withApprover(request -> {
                    shown.add(request);
                    return new Approver.Decision(true, null, "scripted");
                }), BindListener.NONE);
        assertEquals(1, shown.size());
        assertEquals(ScenarioB.QUERY_SLOTS, shown.get(0).resolutions().stream().map(Resolution::slot).toList());
        assertEquals(1, shown.get(0).crossChecks().size());
        assertTrue(shown.get(0).crossChecks().get(0).agreed(), "the approval screen shows the cross-check, agreed");
        assertNotNull(outcome.written());
        outputs.add(outcome.written());
        assertEquals(Files.readString(ScenarioB.committedBindings(env()), StandardCharsets.UTF_8),
                Files.readString(outcome.written(), StandardCharsets.UTF_8), "byte-identical to the committed file");
        assertSignedOffAndQuiet(seq);
    }

    /**
     * P3 items 3, 4 and 5: the committed plan of scenario B runs in {@code replay} on this environment with its
     * committed bindings and passes; the executor never paused, every expectation, {@code db} check and aggregate
     * held at its first attempt, no resolver ran, every slot's verifier and the cross-check ran once (agreed). The
     * waiver is real: the loan the desk posted has the oracle's waiver and total, its {@code loan_waiver} row sums to
     * the oracle's amount, and the slip the desk printed (through the pack's fact and through the {@code devices}
     * root's primitive) belongs to the captured loan and names the rule and the item.
     */
    @Test
    @Order(10)
    void scenarioBReplayPassesWithoutSleepsAndTheWaiverIsReal() throws Exception {
        Runs.Outcome outcome = Runs.run(client(), Plan.read(ScenarioB.plan()), runDir("b-replay"),
                bind(BindMode.REPLAY));
        replayDirB = outcome.dir();
        results.add(outcome.result());
        Runs.assertPassedWithoutSleeping(outcome);
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " env=" + env().id + " scenario=" + ScenarioB.ID
                + " ms=" + outcome.result().duration().toMillis());
        ScenarioB.Expected expected = ScenarioB.expected(env());
        List<Map<String, Object>> log = Runs.runLog(outcome.dir());
        assertFalse(log.stream().anyMatch(r -> "resolver".equals(r.get("type"))), "no resolver ran in replay");
        assertEquals(ScenarioB.QUERY_SLOTS, log.stream().filter(r -> "verifier".equals(r.get("type")))
                .map(r -> r.get("slot")).toList(), "every slot's verifier ran once");
        List<Map<String, Object>> checks = log.stream().filter(r -> "crosscheck".equals(r.get("type"))).toList();
        assertEquals(1, checks.size(), "the cross-check ran once");
        assertEquals("waiver", checks.get(0).get("oracle"));
        assertEquals(Boolean.TRUE, checks.get(0).get("agreed"), String.valueOf(checks.get(0)));
        List<Map<String, Object>> db = log.stream().filter(r -> "db".equals(r.get("type"))).toList();
        assertEquals(List.of("loan", "loan_line", "loan_waiver"), db.stream().map(r -> r.get("table")).toList(),
                "each db check held at its first attempt");
        assertTrue(db.stream().allMatch(r -> Boolean.TRUE.equals(r.get("ok")) && Long.valueOf(1).equals(
                r.get("rowCount")) && "agent".equals(r.get("channel"))), String.valueOf(db));
        assertEquals(Map.of("sum(amount)", expected.amount()), db.get(2).get("aggregates"),
                "the aggregate over the row the desk wrote");
        assertEquals(List.of(), outcome.result().modelCalls());
        Map<String, Object> bindings = Json.parseObject(Files.readString(outcome.dir().resolve("bindings.json")));
        Map<?, ?> captured = (Map<?, ?>) bindings.get("captured");
        Object loanId = ((Map<?, ?>) captured.get("loan")).get("id");
        assertEquals(expected.total(), ((Map<?, ?>) captured.get("loan")).get("total"));
        assertEquals(loanId, ((Map<?, ?>) captured.get("slip")).get("loanId"), "the slip printed for the loan");
        Map<String, Object> loan = LoadModeChecks.value(client().call("lending.loan", Map.of(), CALL));
        assertEquals(loanId, loan.get("id"));
        assertEquals(List.of(Map.of("ruleCode", expected.ruleCode(), "amount", expected.amount())), loan.get(
                "waivers"));
        assertEquals(expected.amount(), loan.get("waiverTotal"));
        assertEquals(expected.total(), loan.get("total"));
        Map<String, Object> slips = LoadModeChecks.value(client().call("lending.slip", Map.of("last", 1L), CALL));
        Map<?, ?> slip = (Map<?, ?>) ((List<?>) slips.get("slips")).get(0);
        Map<?, ?> printed = (Map<?, ?>) LoadModeChecks.value(client().call("device.state", Map.of("id", "printer"),
                CALL)).get("last");
        for (Map<?, ?> last : List.of(slip, printed)) {
            assertEquals(loanId, last.get("loanId"), String.valueOf(last));
            String text = String.valueOf(last.get("text"));
            assertTrue(text.contains(expected.ruleCode()) && text.contains(expected.itemTitle()), text);
        }
        assertSignedOffAndQuiet(-1);
    }

    /**
     * P3 item 6: with the wrong-oracle overlay ({@link OverlayPack#wrongOracle()}), {@code live} ends ERROR /
     * ORACLE_SUSPECT, reason DISAGREED, oracle {@code waiver}, expected the wrong amount ({@code 1.98} on seed A,
     * {@code 1.58} on seed B), the desk's own amount in {@code actual}; no step ran, no cleanup call and no
     * {@code recover}, the desk untouched, nothing written to the bindings directory.
     */
    @Test
    @Order(11)
    void aWrongOracleIsOracleSuspectAndNothingIsSent() throws Exception {
        long seq = lastSeq();
        Path root = ItEnv.runDir("resolution", slug() + "/b-wrong-oracle-root");
        deleteTree(root);
        ScenarioB.Expected expected = ScenarioB.expected(env());
        Runs.Outcome outcome = Runs.run(OverlayPack.wrongOracle(), client(), Plan.read(ScenarioB.plan()),
                runDir("b-wrong-oracle"), bind(BindMode.LIVE).withBindingsRoot(root));
        results.add(outcome.result());
        RunResult result = outcome.result();
        assertEquals(Status.ERROR, result.status(), outcome.describe());
        assertEquals(FailureKind.ORACLE_SUSPECT, result.failureKind(), outcome.describe());
        assertEquals("DISAGREED", result.detail().get("reason"), outcome.describe());
        assertEquals("waiver", result.detail().get("oracle"));
        assertEquals(expected.wrongAmount(), ((Map<?, ?>) result.detail().get("expected")).get("amount"));
        assertEquals(expected.amount(), ((Map<?, ?>) result.detail().get("actual")).get("amount"),
                "what the desk waives");
        assertTrue(result.message().startsWith("oracle waiver (ExpectedWaiver) disagrees with"
                + " lending.waiver.evaluate"), result.message());
        assertTrue(result.steps().isEmpty(), "no step ran");
        assertNothingSent(outcome.dir());
        assertFalse(Files.exists(root), "nothing was written to the bindings directory");
        assertSignedOffAndQuiet(seq);
    }

    /**
     * The guarded form of 10.4's negative material (6.2, gap 26): scenario B with its cross-check guarded by the
     * capability {@code lending.waiver.evaluate}, approved here (the capability is OK, so the check runs) and run in
     * {@code replay} through a connection that reports that capability DEGRADED: the check is skipped, the bind
     * succeeds, the steps pass and the run ends DEGRADED with the skipped line. Why DEGRADED and not UNAVAILABLE: the
     * rule's verifier needs the same capability, so the plan requires it and an UNAVAILABLE one would end the run as
     * PRECONDITION_FAILED before the bind (the headless test covers UNAVAILABLE at the binder).
     */
    @Test
    @Order(12)
    void aGuardedCrossCheckThatCannotRunMakesTheRunDegraded() throws Exception {
        Plan guarded = PlanCompiler.compile(ScenarioB.guardedYaml(), ScenarioB.YAML, Runs.PACK);
        Path root = ItEnv.runDir("resolution", slug() + "/b-guarded-root");
        deleteTree(root);
        BindOutcome approved = Binder.bind(guarded, Runs.PACK, client(), bind(BindMode.APPROVE)
                .withBindingsRoot(root).withApprover(Approver.APPROVE_ALL), BindListener.NONE);
        assertTrue(approved.bindings().crossChecks().get("waiver").agreed(), "an OK capability runs the check");
        outputs.add(approved.written());
        String reason = "scripted by the end-to-end test";
        Runs.Outcome outcome = Runs.run(Runs.PACK, new ScriptedCapability(client(), "lending.waiver.evaluate",
                CapabilityState.DEGRADED, reason), guarded, runDir("b-guarded-degraded"),
                bind(BindMode.REPLAY).withBindingsRoot(root));
        results.add(outcome.result());
        String line = "oracle waiver: cross-check lending.waiver.evaluate skipped: capability lending.waiver.evaluate"
                + " is DEGRADED (" + reason + ")";
        assertEquals(Status.DEGRADED, outcome.result().status(), outcome.describe());
        assertEquals(List.of(line), outcome.result().skippedExpectations(), outcome.describe());
        assertEquals(0, outcome.sleeps());
        List<Map<String, Object>> log = Runs.runLog(outcome.dir());
        List<Map<String, Object>> checks = log.stream().filter(r -> "crosscheck".equals(r.get("type"))).toList();
        assertEquals(1, checks.size());
        assertEquals(Boolean.TRUE, checks.get(0).get("skipped"), String.valueOf(checks.get(0)));
        assertTrue(log.stream().anyMatch(r -> "bind".equals(r.get("type")) && List.of(line).equals(
                r.get("skipped"))), "the bind record lists the skipped check");
        assertSignedOffAndQuiet(-1);
    }

    /** P3 item 7: after the wrong oracle (and the degraded run), scenario B passes without a restart. */
    @Test
    @Order(13)
    void scenarioBStillRunsAfterTheWrongOracle() throws Exception {
        Runs.Outcome again = Runs.run(client(), Plan.read(ScenarioB.plan()), runDir("b-replay-again"),
                bind(BindMode.REPLAY));
        results.add(again.result());
        Runs.assertPassedWithoutSleeping(again);
        assertSignedOffAndQuiet(-1);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A34 over one run's {@code call} records: every {@code lending.input} call whose {@code value} is recorded as
     * {@code <redacted>} carried {@code _meta.secretParams: ["value"]}, those calls are exactly the steps of the given
     * verbs, in order, and no other {@code lending.input} call carried {@code secretParams}.
     */
    static void assertPasswordInputsAnnounced(Path runDir, Plan plan, List<String> expected) throws Exception {
        Map<String, String> verbs = new HashMap<>();
        for (Plan.Step step : plan.steps()) {
            verbs.put(step.id(), step.verb());
        }
        List<String> announced = new ArrayList<>();
        for (Map<String, Object> call : Runs.runLog(runDir)) {
            if (!"call".equals(call.get("type")) || !"lending.input".equals(call.get("primitive"))) {
                continue;
            }
            if (SecretKeys.REDACTED.equals(((Map<?, ?>) call.get("params")).get("value"))) {
                assertEquals(List.of("value"), call.get("secretParams"), "a password input is announced as secret"
                        + " (A34): " + call);
                announced.add(verbs.get(String.valueOf(call.get("step"))));
            } else {
                assertFalse(call.containsKey("secretParams"), "only a password input is announced: " + call);
            }
        }
        assertEquals(expected, announced, runDir + ": the steps that entered a password");
    }

    /**
     * Where a file holds a secret as a value (item 10): in JSON and JSON Lines, a string (value or key) that equals a
     * secret or has one as a whitespace-delimited token; numbers are not strings (a time or a sequence number may
     * have the digits of a four-digit password). In XML, an attribute value or a text token equal to a secret. In
     * any other text, a whitespace-delimited token.
     */
    static List<String> leaks(Path file, Set<String> secrets) throws Exception {
        String name = file.getFileName().toString();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<String> strings = new ArrayList<>();
        if (name.endsWith(".jsonl")) {
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    LoadModeChecks.collectStrings(Json.parse(line), strings);
                }
            }
        } else if (name.endsWith(".json")) {
            LoadModeChecks.collectStrings(Json.parse(text), strings);
        } else if (name.endsWith(".xml")) {
            Matcher attribute = Pattern.compile("=\"([^\"]*)\"").matcher(text);
            while (attribute.find()) {
                strings.add(attribute.group(1));
            }
            strings.add(text.replaceAll("<[^>]*>", " "));
        } else {
            strings.add(text);
        }
        return tokenLeaks(strings, secrets);
    }

    /** The strings that equal a secret, or hold one as a whitespace-delimited token. */
    static List<String> tokenLeaks(List<String> strings, Set<String> secrets) {
        List<String> hits = new ArrayList<>();
        for (String string : strings) {
            Set<String> tokens = new LinkedHashSet<>(List.of(string.split("\\s+")));
            tokens.add(string);
            for (String secret : secrets) {
                if (tokens.contains(secret)) {
                    int at = string.indexOf(secret);
                    hits.add("..." + string.substring(Math.max(0, at - 40), Math.min(string.length(), at + 40))
                            + "...");
                }
            }
        }
        return hits;
    }

    /** The desk's newest event sequence number. */
    long lastSeq() throws Exception {
        RpcResult result = client().call("evt.query", Map.of("since", 0L, "limit", 1L), CALL);
        return ((Number) LoadModeChecks.value(result).get("lastSeq")).longValue();
    }

    /**
     * The desk is signed off with no prompt open and, when {@code since} is not negative, published nothing since
     * then: no input was sent, no prompt asked, no loan posted.
     */
    void assertSignedOffAndQuiet(long since) throws Exception {
        Map<String, Object> state = LoadModeChecks.value(client().call("lending.state", Map.of(), CALL));
        assertEquals("SIGNED_OFF", state.get("name"), String.valueOf(state));
        assertNull(client().call("lending.prompt", Map.of(), CALL).value(), "no prompt is open");
        if (since >= 0) {
            Map<String, Object> events = LoadModeChecks.value(client().call("evt.query", Map.of("since", since,
                    "limit", 500L), CALL));
            List<Object> desk = new ArrayList<>();
            for (Object e : (List<?>) events.get("events")) {
                String topic = String.valueOf(((Map<?, ?>) e).get("topic"));
                if (DESK_TOPICS.stream().anyMatch(topic::startsWith)) {
                    desk.add(e);
                }
            }
            assertEquals(List.of(), desk, "the desk was touched");
        }
    }

    /** The types of a run's {@code run.jsonl} records, in order. */
    static List<String> types(Path dir) throws Exception {
        return Runs.runLog(dir).stream().map(r -> String.valueOf(r.get("type"))).toList();
    }

    /**
     * The target's connection, recording every request the executor sends through it: the method of a call, or
     * the kind of request ({@code capabilities}, {@code schema}, {@code ping}, {@code await <topics>}). Closing it
     * leaves the target's connection open.
     */
    static final class Recording implements AgentConnection {
        final AgentConnection target;
        final List<String> sent = new CopyOnWriteArrayList<>();

        Recording(AgentConnection target) {
            this.target = target;
        }

        @Override
        public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException,
                AgentLostException {
            sent.add(method);
            return target.call(method, params);
        }

        @Override
        public RpcResult call(String method, Map<String, Object> params, CallOptions options)
                throws AgentRpcException, AgentLostException {
            sent.add(method);
            return target.call(method, params, options);
        }

        @Override
        public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
                throws AgentRpcException, AgentLostException {
            sent.add("await " + topics);
            return target.await(topics, sinceSeq, match, timeout);
        }

        @Override
        public CapabilityMatrix capabilities() throws AgentLostException {
            sent.add("capabilities");
            return target.capabilities();
        }

        @Override
        public List<PrimitiveDescriptor> schema() throws AgentLostException {
            sent.add("schema");
            return target.schema();
        }

        @Override
        public Map<String, Object> ping() throws AgentLostException {
            sent.add("ping");
            return target.ping();
        }

        @Override
        public boolean isLost() {
            return target.isLost();
        }

        @Override
        public void close() {
            // The test owns the target's connection.
        }
    }

    /**
     * The target's connection with one capability of its matrix scripted (the other capabilities as the agent reports
     * them): a target that lacks, or only partly has, what the scenario's guard names. Closing it leaves the target's
     * connection open.
     */
    static final class ScriptedCapability implements AgentConnection {
        final AgentConnection target;
        final Capability scripted;

        ScriptedCapability(AgentConnection target, String id, CapabilityState state, String reason) {
            this.target = target;
            this.scripted = new Capability(id, state, reason, "scripted");
        }

        @Override
        public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException,
                AgentLostException {
            return target.call(method, params);
        }

        @Override
        public RpcResult call(String method, Map<String, Object> params, CallOptions options)
                throws AgentRpcException, AgentLostException {
            return target.call(method, params, options);
        }

        @Override
        public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout)
                throws AgentRpcException, AgentLostException {
            return target.await(topics, sinceSeq, match, timeout);
        }

        @Override
        public CapabilityMatrix capabilities() throws AgentLostException {
            Map<String, Capability> all = new HashMap<>(target.capabilities().all());
            all.put(scripted.id(), scripted);
            return new CapabilityMatrix(all);
        }

        @Override
        public List<PrimitiveDescriptor> schema() throws AgentLostException {
            return target.schema();
        }

        @Override
        public Map<String, Object> ping() throws AgentLostException {
            return target.ping();
        }

        @Override
        public boolean isLost() {
            return target.isLost();
        }

        @Override
        public void close() {
            // The test owns the target's connection.
        }
    }

    /**
     * The lending pack with its pre-flight replaced by one check that fails without sending anything (item 7, Q11).
     * {@code LendingPack} is final, so this wraps it and forwards everything else.
     */
    static final class FailingPreflight implements DomainPack {
        static final String ID = "scripted-failure";
        static final String REASON = "scripted to fail by the end-to-end test";
        private final DomainPack pack;

        FailingPreflight(DomainPack pack) {
            this.pack = pack;
        }

        @Override
        public String id() {
            return pack.id();
        }

        @Override
        public String version() {
            return pack.version();
        }

        @Override
        public StepLibrary steps() {
            return pack.steps();
        }

        @Override
        public SyncPolicy syncPolicy() {
            return pack.syncPolicy();
        }

        @Override
        public List<FactProvider> facts() {
            return pack.facts();
        }

        @Override
        public PromptPolicy promptPolicy() {
            return pack.promptPolicy();
        }

        @Override
        public List<PreflightCheck> preflight() {
            return List.of(new PreflightCheck() {
                @Override
                public String id() {
                    return ID;
                }

                @Override
                public String check(AgentConnection agent) {
                    return REASON;
                }

                @Override
                public String check(PreflightContext ctx) {
                    return REASON;
                }
            });
        }

        @Override
        public void recover(AgentConnection agent) throws Exception {
            pack.recover(agent);
        }

        @Override
        public ResolutionCatalogue resolution() {
            return pack.resolution();
        }

        @Override
        public EnvFingerprinter fingerprinter() {
            return pack.fingerprinter();
        }

        @Override
        public List<StoredTable> storedTables() {
            return pack.storedTables();
        }

        @Override
        public List<RowMask> rowMasks() {
            return pack.rowMasks();
        }
    }

    /** A failed bind sends nothing to the target: no step, no cleanup and no recover record in run.jsonl. */
    static void assertNothingSent(Path dir) throws Exception {
        List<Map<String, Object>> log = Runs.runLog(dir);
        assertFalse(log.stream().anyMatch(r -> List.of("call", "sync", "expect", "db", "capture", "prompt", "sources",
                "evidence", "cleanup", "recover").contains(String.valueOf(r.get("type")))),
                "nothing was sent to the target: " + log);
    }

    static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
